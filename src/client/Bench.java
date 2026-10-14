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
 * T7 — self-play &amp; depth benchmark harness.
 *
 * Plays N full self-play games (both AIs, alternating who starts) on the
 * default bench board — 2 Pushers (files 2 and 5) + 3 Pushees (files 1, 3,
 * 4) per side, in the same top/bottom row convention as
 * GameTreeTest.setBoardToDefaultStart (White pushers row 0 / pushees row 1,
 * Black pushers row 7 / pushees row 6) — and reports, per game, the winner,
 * the ply count, the elapsed search time and the max depth completed within
 * the per-move time budget.
 *
 * Usage:
 *   java -cp out client.Bench --games N --budget MS --seed S
 *
 * --seed makes the move-order shuffling reproducible: two runs with the same
 * seed play the same moves, so winners, plies and max depths come out
 * identical. The millisecond fields inherently vary a little with machine
 * load, because the iterative-deepening stop condition is wall-clock based.
 *
 * Degenerate cases are detected and reported, never fatal: a null move (a
 * side with no legal move — a loss by the stalemate rule, docs/RULES.md
 * item 3) ends the game, and a game that reaches the 200-ply cap is
 * truncated with no winner.
 *
 * @see IMPROVEMENT_PLAN.md task T7
 */
public class Bench {

    /** Games reaching this many plies without a goal-row win are truncated. */
    public static final int PLIES_CAP = 200;

    /** Default bench board: Pusher files, then Pushee files (per side). */
    private static final int[] PUSHER_COLS = {2, 5};
    private static final int[] PUSHEE_COLS = {1, 3, 4};

    public static void main(String[] args) {
        if (run(args) == null)
            System.exit(2);
    }

    /**
     * Parse the arguments, play the games, print one report line per game
     * followed by the single-line JSON summary, and return the summary.
     * @param args --games N --budget MS --seed S
     * @return the summary, or null when the arguments are invalid (the
     *         usage is printed to stderr).
     */
    public static Summary run(String[] args) {
        int games;
        long budget;
        Long seed;

        try {
            games = 10;
            budget = GameTree.MAX_TIME_MILLIS;
            seed = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--games":
                        games = parsePositiveInt(args, ++i, "--games");
                        break;
                    case "--budget":
                        budget = parsePositiveLong(args, ++i, "--budget");
                        break;
                    case "--seed":
                        seed = parseLong(args, ++i, "--seed");
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "unknown argument: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
            usage();
            return null;
        }

        List<GameResult> results = new ArrayList<>(games);
        for (int i = 0; i < games; i++) {
            // Alternate who starts so neither color is favored by move order.
            Player starter = (i % 2 == 0) ? Player.WHITE : Player.BLACK;
            GameResult result = playGame(i, starter, budget, seed);
            results.add(result);
            System.out.println(describe(result));
        }

        Summary summary = new Summary(results);
        System.out.println(summary.toJsonLine());
        return summary;
    }

    private static void usage() {
        System.err.println(
                "Usage: java -cp out client.Bench --games N --budget MS --seed S");
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

    /**
     * Play one full self-play game from the default bench board.
     * @param index The zero-based game number (for reporting).
     * @param starter The player to move first.
     * @param budget The per-move search budget in milliseconds.
     * @param seed Seed for the move-order shuffling, or null for
     *             non-deterministic play.
     */
    private static GameResult playGame(int index, Player starter,
                                       long budget, Long seed) {
        GameBoard board = GameBoard.getInstance();
        board.reset();
        setDefaultBenchBoard(board);
        board.setCurrentPlayer(starter);

        GameTree whiteTree =
                new GameTree(Player.WHITE, Player.BLACK, budget, seed);
        GameTree blackTree =
                new GameTree(Player.BLACK, Player.WHITE, budget, seed);

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

        return new GameResult(index, starter, winner, plies, elapsedMs,
                maxDepth, stalemated, winner == null);
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
}
