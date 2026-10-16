package client;

import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * T7 — self-play &amp; depth benchmark harness. T15 — A/B strength harness.
 *
 * Plays N self-play games (alternating who starts) on the default bench
 * board — 2 Pushers (files 2 and 5) + 3 Pushees (files 1, 3, 4) per side,
 * in the same top/bottom row convention as
 * GameTreeTest.setBoardToDefaultStart (White pushers row 0 / pushees row 1,
 * Black pushers row 7 / pushees row 6) — and reports, per game, the winner,
 * the ply count, the elapsed search time and the max depth completed within
 * the per-move time budget.
 *
 * T15 adds mixed play: the current engine against a reference engine with
 * features switched off, so strength is measurable (mirror self-play cannot
 * separate strength from a draw — two identical engines always split).
 *
 * Usage:
 *   Mirror mode (T7, both engines at the same level):
 *     java -cp out client.Bench --games N --budget MS --seed S
 *         [--tt-stats] [--engine LEVEL]
 *
 *   Mixed mode (T15):
 *     java -cp out client.Bench --ab LEVEL_A LEVEL_B --games N --budget MS
 *         --seed S [--small]
 *
 * Engine levels (a monotone feature stack in merge order):
 *   t2    = base: no quiescence, threat terms, TT, repetition, aspiration
 *           or PVS (the pre-T3 stack)
 *   t3    = t2 + quiescence (T3)
 *   t4    = t3 + threat terms (T4)
 *   t6    = t4 + transposition table (T6, incl. the T14 ordering handoff)
 *   t10   = t6 + anti-repetition (T10)
 *   t13   = t10 + aspiration windows + PVS (T13)
 *   t16   = t13 + goal-proximity ordering of quiet moves (T16)
 *          = the current engine
 *   current = alias for t16
 *
 * Mirror mode with no --engine (or --engine current) is bit-identical to
 * the pre-T15 bench: every feature off-flag defaults to on.
 *
 * Mixed mode plays on the FULL 8+8 start by default (--small selects the
 * 2+3 bench board), alternating which engine takes each color (White always
 * moves first, so the first-move advantage alternates with the colors and
 * cancels over the pair of games — it is real: the starter force-wins the
 * 2+3 board in 13 plies). It prints one line per game plus a JSON summary
 * line:
 *   {"games":N,"aWins":n,"bWins":n,"aWinsAsWhite":n,"aWinsAsBlack":n,
 *    "truncated":n,"avgPlies":x,"avgMaxDepthA":x,"avgMaxDepthB":x}
 * (both engines report depth).
 *
 * --seed makes the move-order shuffling reproducible: two runs with the same
 * seed play the same moves, so winners, plies and max depths come out
 * identical (the millisecond fields inherently vary a little with machine
 * load, because the iterative-deepening stop condition is wall-clock based).
 *
 * --tt-stats (debug, T6, mirror mode) prints one extra line after the
 * summary with the transposition table probe/hit totals and hit rate over
 * all games.
 *
 * Degenerate cases are detected and reported, never fatal: a null move (a
 * side with no legal move — a loss by the stalemate rule, docs/RULES.md
 * item 3) ends the game, and a game that reaches the 200-ply cap is
 * truncated with no winner.
 *
 * @see IMPROVEMENT_PLAN.md tasks T7 and T15
 */
public class Bench {

    /** Games reaching this many plies without a goal-row win are truncated. */
    public static final int PLIES_CAP = 200;

    /** Default bench board: Pusher files, then Pushee files (per side). */
    private static final int[] PUSHER_COLS = {2, 5};
    private static final int[] PUSHEE_COLS = {1, 3, 4};

    /**
     * T15 (extended by T16): a monotone engine feature stack in merge
     * order. Each level adds exactly the feature that merged in that task;
     * t16 is the current engine (every default-on flag) and "current" is
     * its alias. The t2 base is the pre-T3 stack (no quiescence, no threat
     * terms, no TT, no repetition, no aspiration, no PVS, no proximity
     * ordering).
     */
    public enum Level {
        T2("t2",    false, false, false, false, false, false, false),
        T3("t3",    true,  false, false, false, false, false, false),
        T4("t4",    true,  true,  false, false, false, false, false),
        T6("t6",    true,  true,  true,  false, false, false, false),
        T10("t10",  true,  true,  true,  true,  false, false, false),
        T13("t13",  true,  true,  true,  true,  true,  true,  false),
        T16("t16",  true,  true,  true,  true,  true,  true,  true);

        public final boolean quiescence;     // T3
        public final boolean threatTerms;    // T4
        public final boolean transposition;  // T6 (incl. the T14 handoff)
        public final boolean repetition;     // T10
        public final boolean aspiration;     // T13 Part A
        public final boolean pvs;            // T13 Part B
        public final boolean ordering;       // T16
        private final String key;

        Level(String key, boolean quiescence, boolean threatTerms,
              boolean transposition, boolean repetition,
              boolean aspiration, boolean pvs, boolean ordering) {
            this.key = key;
            this.quiescence = quiescence;
            this.threatTerms = threatTerms;
            this.transposition = transposition;
            this.repetition = repetition;
            this.aspiration = aspiration;
            this.pvs = pvs;
            this.ordering = ordering;
        }

        public String key() {
            return key;
        }

        /** T15: parse a level name; "current" is an alias for T16 (the
         *  post-T16 engine). Returns null for an unknown level (a usage
         *  error at the CLI). */
        public static Level parse(String name) {
            if (name == null)
                return null;
            if ("current".equals(name))
                return T16;
            for (Level level : values())
                if (level.key.equals(name))
                    return level;
            return null;
        }
    }

    /**
     * T15 (extended by T16): build a tree at the given engine level. The
     * T14 ordering handoff rides with the TT (there are no entries to order
     * from when the table is off, and on it is the fixed T14 behavior). All
     * seven feature flags are passed explicitly; for T16 this is
     * bit-identical to the 4-argument constructor (every flag defaults to
     * on).
     */
    public static GameTree treeFor(Level level, Player maxPlayer,
                                   Player minPlayer, long timeBudgetMillis,
                                   Long seed) {
        return new GameTree(maxPlayer, minPlayer, timeBudgetMillis, seed,
                level.transposition, level.aspiration, level.pvs,
                level.transposition, level.quiescence,
                level.threatTerms, level.repetition, level.ordering);
    }

    public static void main(String[] args) {
        Args parsed = parseArgs(args);
        if (parsed == null) {
            usage();
            System.exit(2);
        }
        if (parsed.abA != null)
            runAb(parsed);
        else
            run(parsed);
    }

    /**
     * Mirror mode (T7): both engines at the same level (the current engine
     * when --engine is absent). Parses the arguments, plays the games,
     * prints one report line per game followed by the single-line JSON
     * summary, and returns the summary.
     * @return the summary, or null when the arguments are invalid (the
     *         usage is printed to stderr).
     */
    public static Summary run(String[] args) {
        Args parsed = parseArgs(args);
        if (parsed == null) {
            usage();
            return null;
        }
        if (parsed.abA != null) {
            System.err.println("ERROR: --ab LEVEL_A LEVEL_B is mixed mode; "
                    + "call runAb(...)");
            usage();
            return null;
        }
        return run(parsed);
    }

    /**
     * Mixed mode (T15): engine A against engine B on the full 8+8 start
     * (or the 2+3 bench board with --small), alternating which engine
     * takes each color. Prints one report line per game followed by the
     * single-line JSON summary, and returns the summary.
     * @return the summary, or null when the arguments are invalid (the
     *         usage is printed to stderr).
     */
    public static AbSummary runAb(String[] args) {
        Args parsed = parseArgs(args);
        if (parsed == null) {
            usage();
            return null;
        }
        if (parsed.abA == null) {
            System.err.println("ERROR: runAb needs --ab LEVEL_A LEVEL_B");
            usage();
            return null;
        }
        return runAb(parsed);
    }

    // --------------------------------------------------------------- mirror mode (T7)

    private static Summary run(Args parsed) {
        // T6 debug hook: [probes, hits] accumulated over all games.
        long[] tt = new long[2];

        List<GameResult> results = new ArrayList<>(parsed.games);
        for (int i = 0; i < parsed.games; i++) {
            // Alternate who starts so neither color is favored by move order.
            Player starter = (i % 2 == 0) ? Player.WHITE : Player.BLACK;
            GameResult result = playGame(i, starter, parsed.budget, parsed.seed,
                    parsed.engine, tt);
            results.add(result);
            System.out.println(describe(result));
        }

        Summary summary = new Summary(results);
        System.out.println(summary.toJsonLine());

        if (parsed.ttStats)
            System.out.println(String.format(Locale.ROOT,
                    "ttStats={\"probes\":%d,\"hits\":%d,\"hitRate\":%.4f}",
                    tt[0], tt[1],
                    tt[0] == 0 ? 0.0 : (double) tt[1] / tt[0]));

        return summary;
    }

    /**
     * Play one full self-play game from the default bench board.
     * @param index The zero-based game number (for reporting).
     * @param starter The player to move first.
     * @param budget The per-move search budget in milliseconds.
     * @param seed Seed for the move-order shuffling, or null for
     *             non-deterministic play.
     * @param engine T15: the level both engines play at, or null for the
     *               current engine via the pre-T15 constructor path.
     */
    private static GameResult playGame(int index, Player starter,
                                       long budget, Long seed, Level engine,
                                       long[] tt) {
        GameBoard board = GameBoard.getInstance();
        board.reset();
        setDefaultBenchBoard(board);
        board.setCurrentPlayer(starter);

        // Null engine: the exact pre-T15 construction (all features on via
        // the default-on chain) — mirror mode without --engine is
        // bit-identical to the T7 bench.
        GameTree whiteTree = (engine == null)
                ? new GameTree(Player.WHITE, Player.BLACK, budget, seed)
                : treeFor(engine, Player.WHITE, Player.BLACK, budget, seed);
        GameTree blackTree = (engine == null)
                ? new GameTree(Player.BLACK, Player.WHITE, budget, seed)
                : treeFor(engine, Player.BLACK, Player.WHITE, budget, seed);

        Player winner = null;
        Player stalemated = null;
        int plies = 0;
        long elapsedMs = 0;
        int maxDepth = 0;

        while (winner == null && plies < PLIES_CAP) {
            Player toMove = board.getCurrentPlayer();
            GameTree tree = (toMove == Player.WHITE) ? whiteTree : blackTree;

            long moveStart = System.currentTimeMillis();
            Move move = tree.getBestMoveQuick();
            elapsedMs += System.currentTimeMillis() - moveStart;
            maxDepth = Math.max(maxDepth, tree.getLastCompletedLevel());

            if (move == null) {
                // The side to move has no legal move: it loses by the
                // stalemate rule (docs/RULES.md item 3). Reported, not fatal.
                stalemated = toMove;
                winner = toMove.other();
                break;
            }

            board.movePiece(move);
            plies++;
            winner = board.winner();
        }

        // T6 debug hook: accumulate this game's transposition table traffic.
        tt[0] += whiteTree.getTTProbes() + blackTree.getTTProbes();
        tt[1] += whiteTree.getTTHits() + blackTree.getTTHits();

        return new GameResult(index, starter, winner, plies, elapsedMs,
                maxDepth, stalemated, winner == null);
    }

    // --------------------------------------------------------------- mixed mode (T15)

    private static AbSummary runAb(Args parsed) {
        List<AbGameResult> results = new ArrayList<>(parsed.games);
        for (int i = 0; i < parsed.games; i++) {
            // T15: alternate which engine takes each color. White always
            // moves first, so the first-move advantage alternates with the
            // colors and cancels over each pair of games.
            boolean aIsWhite = (i % 2 == 0);
            AbGameResult result = playAbGame(i, aIsWhite, parsed.abA,
                    parsed.abB, parsed.budget, parsed.seed, parsed.small);
            results.add(result);
            System.out.println(describeAb(result));
        }

        AbSummary summary = new AbSummary(results);
        System.out.println(summary.toJsonLine());
        return summary;
    }

    /**
     * Play one mixed game: engine A against engine B. The default board is
     * the full 8+8 start (the strength-relevant position — mirror self-
     * play on the 2+3 board is a forced win and measures nothing);
     * smallBoard selects the 2+3 bench board instead.
     */
    private static AbGameResult playAbGame(int index, boolean aIsWhite,
                                           Level levelA, Level levelB,
                                           long budget, Long seed,
                                           boolean smallBoard) {
        GameBoard board = GameBoard.getInstance();
        board.reset();
        if (smallBoard)
            setDefaultBenchBoard(board);
        else
            setFullBoard(board);
        board.setCurrentPlayer(Player.WHITE);

        Level whiteLevel = aIsWhite ? levelA : levelB;
        Level blackLevel = aIsWhite ? levelB : levelA;
        GameTree whiteTree = treeFor(whiteLevel, Player.WHITE, Player.BLACK,
                budget, seed);
        GameTree blackTree = treeFor(blackLevel, Player.BLACK, Player.WHITE,
                budget, seed);

        Player aColor = aIsWhite ? Player.WHITE : Player.BLACK;
        Player winner = null;
        Player stalemated = null;
        int plies = 0;
        long elapsedMs = 0;
        int maxDepthA = 0;
        int maxDepthB = 0;

        while (winner == null && plies < PLIES_CAP) {
            Player toMove = board.getCurrentPlayer();
            GameTree tree = (toMove == Player.WHITE) ? whiteTree : blackTree;

            long moveStart = System.currentTimeMillis();
            Move move = tree.getBestMoveQuick();
            elapsedMs += System.currentTimeMillis() - moveStart;
            int depth = tree.getLastCompletedLevel();
            if (toMove == aColor)
                maxDepthA = Math.max(maxDepthA, depth);
            else
                maxDepthB = Math.max(maxDepthB, depth);

            if (move == null) {
                stalemated = toMove;
                winner = toMove.other();
                break;
            }

            board.movePiece(move);
            plies++;
            winner = board.winner();
        }

        return new AbGameResult(index, aColor, winner, plies, elapsedMs,
                maxDepthA, maxDepthB, stalemated, winner == null);
    }

    /**
     * The default bench board: 2 Pushers + 3 Pushees per side. Follows the
     * row convention of GameTreeTest.setBoardToDefaultStart (each side
     * starts in the two rows closest to the enemy goal row).
     */
    private static void setDefaultBenchBoard(GameBoard board) {
        for (int col : PUSHER_COLS)
            board.putPiece(new Pusher(Player.WHITE), 0, col);
        for (int col : PUSHEE_COLS)
            board.putPiece(new Pushee(Player.WHITE), 1, col);
        for (int col : PUSHER_COLS)
            board.putPiece(new Pusher(Player.BLACK), 7, col);
        for (int col : PUSHEE_COLS)
            board.putPiece(new Pushee(Player.BLACK), 6, col);
    }

    /** The full 8+8 start (the same layout VisualGame uses): 8 Pushers +
     *  8 Pushees per side in the two rows closest to the enemy goal row. */
    private static void setFullBoard(GameBoard board) {
        for (int col = 0; col < GameBoard.GAME_BOARD_LENGTH; col++) {
            board.putPiece(new Pusher(Player.WHITE), 0, col);
            board.putPiece(new Pushee(Player.WHITE), 1, col);
            board.putPiece(new Pusher(Player.BLACK), 7, col);
            board.putPiece(new Pushee(Player.BLACK), 6, col);
        }
    }

    // --------------------------------------------------------------- reporting

    private static String describe(GameResult g) {
        String winner = (g.winner == null) ? "NONE" : g.winner.name();
        String note = g.stalemate
                ? " (stalemate: " + g.stalemated + " had no legal move)"
                : (g.truncated ? " (truncated at " + PLIES_CAP + " plies)"
                               : "");
        return String.format(Locale.ROOT,
                "game %d: starter=%s winner=%s plies=%d elapsedMs=%d maxDepth=%d%s",
                g.index + 1, g.starter, winner, g.plies, g.elapsedMs,
                g.maxDepth, note);
    }

    private static String describeAb(AbGameResult g) {
        String winner = (g.winner == null) ? "NONE" : g.winner.name();
        String note = g.stalemate
                ? " (stalemate: " + g.stalemated + " had no legal move)"
                : (g.truncated ? " (truncated at " + PLIES_CAP + " plies)"
                               : "");
        return String.format(Locale.ROOT,
                "game %d: A=%s B=%s winner=%s plies=%d elapsedMs=%d maxDepthA=%d maxDepthB=%d%s",
                g.index + 1, g.aColor, g.aColor.other(), winner, g.plies,
                g.elapsedMs, g.maxDepthA, g.maxDepthB, note);
    }

    // --------------------------------------------------------------- argument parsing

    /** The parsed bench arguments (the defaults mirror the pre-T15 CLI). */
    private static final class Args {
        int games = 10;
        long budget = GameTree.MAX_TIME_MILLIS;
        Long seed = null;
        boolean ttStats = false;
        boolean small = false;        // mixed mode: 2+3 board instead of full
        Level engine = null;          // mirror mode: both engines' level
        Level abA = null;             // non-null => mixed mode
        Level abB = null;
    }

    private static Args parseArgs(String[] args) {
        Args parsed = new Args();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--games":
                        parsed.games = parsePositiveInt(args, ++i, "--games");
                        break;
                    case "--budget":
                        parsed.budget = parsePositiveLong(args, ++i, "--budget");
                        break;
                    case "--seed":
                        parsed.seed = parseLong(args, ++i, "--seed");
                        break;
                    case "--tt-stats":
                        parsed.ttStats = true;
                        break;
                    case "--small":
                        parsed.small = true;
                        break;
                    case "--engine":
                        parsed.engine = parseLevel(args, ++i, "--engine");
                        break;
                    case "--ab":
                        parsed.abA = parseLevel(args, ++i, "--ab (engine A)");
                        parsed.abB = parseLevel(args, ++i, "--ab (engine B)");
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "unknown argument: " + args[i]);
                }
            }
            if (parsed.abA != null && parsed.engine != null)
                throw new IllegalArgumentException("--engine applies to mirror "
                        + "mode; --ab supplies both levels");
            return parsed;
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            return null;
        }
    }

    private static Level parseLevel(String[] args, int i, String name) {
        if (i >= args.length)
            throw new IllegalArgumentException(name + " needs a value");
        Level level = Level.parse(args[i]);
        if (level == null)
            throw new IllegalArgumentException(name + " expects an engine "
                    + "level (t2, t3, t4, t6, t10, t13 or current), got '"
                    + args[i] + "'");
        return level;
    }

    private static void usage() {
        System.err.println(
            "Usage: java -cp out client.Bench --games N --budget MS --seed S [--tt-stats] [--engine LEVEL]\n"
          + "       java -cp out client.Bench --ab LEVEL_A LEVEL_B --games N --budget MS --seed S [--small]\n"
          + "  LEVEL: t2 | t3 | t4 | t6 | t10 | t13 | current (t13 = the current engine)");
    }

    private static int parsePositiveInt(String[] args, int i, String name) {
        long v = parseLong(args, i, name);
        if (v < 1 || v > Integer.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be an integer >= 1");
        return (int) v;
    }

    private static long parsePositiveLong(String[] args, int i, String name) {
        long v = parseLong(args, i, name);
        if (v < 1)
            throw new IllegalArgumentException(name + " must be >= 1");
        return v;
    }

    private static long parseLong(String[] args, int i, String name) {
        if (i >= args.length)
            throw new IllegalArgumentException(name + " needs a value");
        try {
            return Long.parseLong(args[i]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    name + " expects a number, got '" + args[i] + "'");
        }
    }

    // --------------------------------------------------------------- results

    /** The outcome of one self-play game. */
    public static final class GameResult {
        public final int index;
        public final Player starter;
        public final Player winner;     // null when the game was truncated
        public final int plies;
        public final long elapsedMs;    // sum of the per-move search time
        public final int maxDepth;      // deepest level fully completed
        public final boolean stalemate; // ended via a null move
        public final Player stalemated; // the side that had no legal move
        public final boolean truncated; // hit the ply cap without a winner

        GameResult(int index, Player starter, Player winner, int plies,
                   long elapsedMs, int maxDepth, Player stalemated,
                   boolean truncated) {
            this.index = index;
            this.starter = starter;
            this.winner = winner;
            this.plies = plies;
            this.elapsedMs = elapsedMs;
            this.maxDepth = maxDepth;
            this.stalemate = (stalemated != null);
            this.stalemated = stalemated;
            this.truncated = truncated;
        }
    }

    /** T15: the outcome of one mixed (A vs B) game. */
    public static final class AbGameResult {
        public final int index;
        public final Player aColor;     // the color engine A played
        public final Player winner;     // null when the game was truncated
        public final int plies;
        public final long elapsedMs;    // sum of both engines' search time
        public final int maxDepthA;     // deepest level A fully completed
        public final int maxDepthB;     // deepest level B fully completed
        public final boolean stalemate; // ended via a null move
        public final Player stalemated; // the side that had no legal move
        public final boolean truncated; // hit the ply cap without a winner

        AbGameResult(int index, Player aColor, Player winner, int plies,
                     long elapsedMs, int maxDepthA, int maxDepthB,
                     Player stalemated, boolean truncated) {
            this.index = index;
            this.aColor = aColor;
            this.winner = winner;
            this.plies = plies;
            this.elapsedMs = elapsedMs;
            this.maxDepthA = maxDepthA;
            this.maxDepthB = maxDepthB;
            this.stalemate = (stalemated != null);
            this.stalemated = stalemated;
            this.truncated = truncated;
        }
    }

    /** Aggregate over all bench games, printed as a single JSON line. */
    public static final class Summary {
        public final List<GameResult> games;
        public final int whiteWins;
        public final int blackWins;
        public final double avgPlies;
        public final double avgMaxDepth;
        public final double avgMsPerMove;

        Summary(List<GameResult> games) {
            this.games = games;
            int white = 0;
            int black = 0;
            double plies = 0;
            double depth = 0;
            double ms = 0;
            for (GameResult g : games) {
                if (g.winner == Player.WHITE)
                    white++;
                else if (g.winner == Player.BLACK)
                    black++;
                plies += g.plies;
                depth += g.maxDepth;
                ms += g.elapsedMs;
            }
            int n = games.size();
            whiteWins = white;
            blackWins = black;
            avgPlies = plies / n;
            avgMaxDepth = depth / n;
            avgMsPerMove = (plies > 0) ? (ms / plies) : 0.0;
        }

        /** The one-line JSON summary: the fields T3/T4/T6 quote against. */
        public String toJsonLine() {
            return String.format(Locale.ROOT,
                    "{\"games\":%d,\"whiteWins\":%d,\"blackWins\":%d,"
                    + "\"avgPlies\":%.2f,\"avgMaxDepth\":%.2f,"
                    + "\"avgMsPerMove\":%.2f}",
                    games.size(), whiteWins, blackWins,
                    avgPlies, avgMaxDepth, avgMsPerMove);
        }
    }

    /** T15: the aggregate over all mixed games, one JSON line. */
    public static final class AbSummary {
        public final List<AbGameResult> games;
        public final int aWins;         // games A won, in either color
        public final int bWins;         // games B won, in either color
        public final int aWinsAsWhite;  // = aWins split by A's color
        public final int aWinsAsBlack;
        public final int truncated;     // no winner (ply cap)
        public final double avgPlies;
        public final double avgMaxDepthA;
        public final double avgMaxDepthB;

        AbSummary(List<AbGameResult> games) {
            this.games = games;
            int a = 0, b = 0, aWhite = 0, aBlack = 0, trunc = 0;
            double plies = 0, depthA = 0, depthB = 0;
            for (AbGameResult g : games) {
                if (g.winner != null) {
                    if (g.winner == g.aColor) {
                        a++;
                        if (g.aColor == Player.WHITE)
                            aWhite++;
                        else
                            aBlack++;
                    } else {
                        b++;
                    }
                } else {
                    trunc++;
                }
                plies += g.plies;
                depthA += g.maxDepthA;
                depthB += g.maxDepthB;
            }
            int n = games.size();
            aWins = a;
            bWins = b;
            aWinsAsWhite = aWhite;
            aWinsAsBlack = aBlack;
            truncated = trunc;
            avgPlies = plies / n;
            avgMaxDepthA = depthA / n;
            avgMaxDepthB = depthB / n;
        }

        /** The one-line JSON summary (the fields T16's gate reads). */
        public String toJsonLine() {
            return String.format(Locale.ROOT,
                    "{\"games\":%d,\"aWins\":%d,\"bWins\":%d,"
                    + "\"aWinsAsWhite\":%d,\"aWinsAsBlack\":%d,"
                    + "\"truncated\":%d,\"avgPlies\":%.2f,"
                    + "\"avgMaxDepthA\":%.2f,\"avgMaxDepthB\":%.2f}",
                    games.size(), aWins, bWins, aWinsAsWhite, aWinsAsBlack,
                    truncated, avgPlies, avgMaxDepthA, avgMaxDepthB);
        }
    }
}
