package tests;

import client.Bench;
import client.Bench.Level;
import client.GameAnalyzer;
import client.GameTree;
import client.GameTreeConfig;
import client.PlayerStrategy;
import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T15 — A/B strength harness: flag-correctness tests.
 *
 * The engine-level stack (Bench.Level) turns features off one merge at a
 * time; these tests pin that the flags do exactly what their documents say:
 *   - TT / aspiration / PVS are SPEED-ONLY: they never change the root
 *     value (requirement 4.1);
 *   - the stack collapses to a single value once the window features are
 *     off (the plan's "t13 with aspiration+PVS off == t10 == t6" chain);
 *   - with the threat terms off the evaluation is exactly the pre-T4
 *     formula — what t2/t3 evaluate (requirement 4.2);
 *   - the quiescence and repetition flags are wired to their observable
 *     behavior (q-node counter, history counter, root penalty);
 *   - same level + seed replays the same games (requirement 4.3);
 *   - level parsing/aliasing and the t13 == current-engine identity
 *     (requirement 2).
 *
 * The mirror invariant with the threat terms off lives in
 * tests.EvalSymmetryTest (T8's invariant must survive the flag).
 */
public class EngineLevelTest {

    /** A generous budget: every fixed-depth search used here finishes in ms. */
    private static final long BUDGET = GameTree.MAX_TIME_MILLIS;
    private static final long SEED = 42L;

    private final GameBoard board = GameBoard.getInstance();

    @AfterEach
    public void cleanBoard() {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    // ------------------------------------------------------------------
    // Requirement 4.1 — value identity across speed-only features
    // ------------------------------------------------------------------

    /**
     * TT and PVS never change the value, only speed: on fixed positions +
     * seed, the fixed-depth full-window root score of t4 (no TT, no PVS)
     * equals t6's (TT on, no PVS) equals either with PVS on (the fail-high
     * re-search must restore the exact value). A full window is exact, so
     * any ordering or pruning change that leaked into the value shows up
     * here.
     */
    @Test
    public void speedOnlyFeaturesNeverChangeTheValue() {
        final int[] depths = {2, 3, 4};
        final String[] positions = {"race", "tactical", "captureRace",
                "defaultStart"};

        for (String which : positions) {
            for (int depth : depths) {
                setT15Position(which);
                Player max = board.getCurrentPlayer();
                Player min = max.other();

                int t4 = Bench.treeFor(Level.T4, max, min, BUDGET, SEED)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                int t6 = Bench.treeFor(Level.T6, max, min, BUDGET, SEED)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                // t6 + PVS (TT on, PVS on, no aspiration: a single full-
                // window search, so the window features stay out).
                int t6pvs = new GameTree(max, min,
                        new GameTreeConfig(BUDGET, SEED, true, false, true,
                                true, true, true, true, true))
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                // t4 + PVS (PVS without a TT must be exact too).
                int t4pvs = new GameTree(max, min,
                        new GameTreeConfig(BUDGET, SEED, false, false, true,
                                true, true, true, true, true))
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);

                assertEquals(t4, t6, which + " depth " + depth + ": the TT must be "
                        + "value-inert");
                assertEquals(t6, t6pvs, which + " depth " + depth + ": PVS must be "
                        + "value-inert (with TT)");
                assertEquals(t4, t4pvs, which + " depth " + depth + ": PVS must be "
                        + "value-inert (without TT)");
            }
        }
    }

    /**
     * The plan's chain identity: "t13 with aspiration+PVS off" is t10's
     * flag set, and with the window features off the whole stack collapses
     * to one value: t10 == t6 == t4 on the same positions (iterative
     * deepening to a depth cap, full window at every level). Repetition
     * (t10 over t6) is value-inert on this path by construction: the
     * measurement tool appends nothing to the history, so the root penalty
     * never fires (its designed effect on the production path is pinned by
     * tests.RepetitionTest).
     */
    @Test
    public void theStackCollapsesToOneValueWithoutTheWindowFeatures() {
        final int cap = 6;
        final String[] positions = {"defaultStart", "race", "tactical"};

        for (String which : positions) {
            setT15Position(which);
            Player max = board.getCurrentPlayer();
            Player min = max.other();

            int t10 = Bench.treeFor(Level.T10, max, min, BUDGET, SEED)
                    .aspirationScoreAtDepth(cap);
            int t6 = Bench.treeFor(Level.T6, max, min, BUDGET, SEED)
                    .aspirationScoreAtDepth(cap);
            int t4 = Bench.treeFor(Level.T4, max, min, BUDGET, SEED)
                    .aspirationScoreAtDepth(cap);

            assertEquals(t10, t6, which + ": t10 == t6 (repetition is value-inert "
                    + "without a history)");
            assertEquals(t6, t4, which + ": t6 == t4 (the TT is value-inert under "
                    + "iterative deepening)");
        }
    }

    // ------------------------------------------------------------------
    // Requirement 4.2 — the eval floor (what t2/t3 evaluate)
    // ------------------------------------------------------------------

    /**
     * With the threat terms off the evaluation is exactly the pre-T4
     * formula — (value + advancement) for each own piece,
     * -(value + advancement + DEFENSIVE_PREMIUM) for each enemy piece,
     * own mobility / 2 — computed here by direct formula, not by calling
     * the analyzer. The positions are chosen so the removed term is
     * NON-ZERO (shield +20; ready-to-win +400), so the flag provably
     * changes the score. The last assertion pins the engine side: t2
     * (no quiescence, no threat terms) evaluates a depth-0 node as exactly
     * this stand-pat.
     */
    @Test
    public void withThreatTermsOffTheEvalIsExactlyThePreT4Formula() {
        // Position 1: the shield shape (EvalSymmetryTest's pin):
        //   own:      W Pusher (1,2)               7 + 1  = +8
        //   enemy:    B Pushee (1,3)  -(3 + 6 + 2) = -11
        //             B Pusher (2,3)  -(7 + 5 + 2) = -14
        //   mobility: 3 own moves / 2 = +1
        //   base     = -16;  threat terms (White's view) = +20 shield
        //   => full eval = 4 (pinned by EvalSymmetryTest).
        resetAndPlace(new Pusher(Player.WHITE), 1, 2);
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 3);
        board.updateAllLegaLMoves();

        assertEquals(20, GameAnalyzer.threatTerms(board, Player.WHITE), "sanity: the removed term must be non-zero");
        int base1 = (7 + 1)
                - (3 + (GameBoard.GAME_BOARD_LENGTH - 1 - 1)
                        + GameAnalyzer.DEFENSIVE_PREMIUM)
                - (7 + (GameBoard.GAME_BOARD_LENGTH - 1 - 2)
                        + GameAnalyzer.DEFENSIVE_PREMIUM)
                + 3 / 2;
        assertEquals(-16, base1);
        assertEquals(base1, GameAnalyzer.analyzeFor(board, Player.WHITE, false), "terms off must be exactly the pre-T4 formula");
        assertEquals(4, GameAnalyzer.analyzeFor(board, Player.WHITE), "terms on keeps the pinned value");
        assertEquals(base1, new PlayerStrategy(Player.WHITE).analyze(false), "the strategies must honor the flag");
        // t2 evaluates exactly this: no quiescence, no threat terms ->
        // the depth-0 root returns the stand-pat directly.
        assertEquals(base1, Bench.treeFor(Level.T2, Player.WHITE, Player.BLACK,
                        BUDGET, SEED)
                        .debugSearchScore(0, Integer.MIN_VALUE,
                                Integer.MAX_VALUE), "t2's depth-0 evaluation must be the pre-T4 stand-pat");

        // Position 2: ready-to-win (White Pushee one push from the goal
        // row with its Pusher behind):
        //   own:      W Pushee (6,3)  3 + 6 = 9;  W Pusher (5,3)  7 + 5 = 12
        //   enemy:    none
        //   mobility: (1 + 2) / 2 = +1
        //   base     = 22;  threat terms = READY_TO_WIN = +400
        //   => full eval = 422.
        resetAndPlace(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pushee(Player.WHITE), 6, 3);
        board.updateAllLegaLMoves();

        assertEquals(GameAnalyzer.READY_TO_WIN, GameAnalyzer.threatTerms(board, Player.WHITE), "sanity: the removed term must be non-zero");
        int base2 = (3 + 6) + (7 + 5) + 3 / 2;
        assertEquals(22, base2);
        assertEquals(base2, GameAnalyzer.analyzeFor(board, Player.WHITE, false), "terms off must be exactly the pre-T4 formula");
        assertEquals(base2
                + GameAnalyzer.READY_TO_WIN, GameAnalyzer.analyzeFor(board, Player.WHITE), "terms on adds exactly READY_TO_WIN");
    }

    // ------------------------------------------------------------------
    // Flag wiring (requirement 1, observable behavior)
    // ------------------------------------------------------------------

    /**
     * quiescenceEnabled: off, depth-0 nodes return the stand-pat directly
     * (no q-node is visited); on, the same position enters the q-search
     * (a q-node is visited). Both paths feed the same threatTermsEnabled
     * flag to the stand-pat evaluation (pinned in the eval-floor test).
     */
    @Test
    public void quiescenceOffVisitsNoQNodeAndReturnsStandPat() {
        // A capture is available, so a live q-search would extend.
        setT15Position("captureRace");
        Player max = board.getCurrentPlayer();

        GameTree qOff = new GameTree(max, max.other(),
                new GameTreeConfig(BUDGET, SEED, true, false, false, true,
                        false, true, true, true));
        int standPat = GameAnalyzer.analyzeFor(board, max, true);
        assertEquals(standPat, qOff.debugSearchScore(0, Integer.MIN_VALUE,
                        Integer.MAX_VALUE), "q off: the depth-0 node is the stand-pat itself");
        assertEquals(0, qOff.getQNodeCount(), "q off: no q-node is visited");

        setT15Position("captureRace");
        GameTree qOn = new GameTree(max, max.other(),
                new GameTreeConfig(BUDGET, SEED, true, false, false, true,
                        true, true, true, true));
        qOn.debugSearchScore(0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertTrue(qOn.getQNodeCount() > 0, "q on: the depth-0 node is a q-node");
    }

    /**
     * repetitionEnabled: off, a search appends nothing to the history and
     * a poisoned history is IGNORED (the natural best move — the
     * repeating one — is still chosen); on (the default), the same poison
     * history flips the choice exactly as T10 pins it. Geometry and scores
     * are RepetitionTest's (depth-1 root scores are stand-pat statics:
     * the repeating move A5->B6 scores 15, every other move 14, so the
     * penalty of 5 alone decides).
     */
    @Test
    public void repetitionOffIgnoresHistoryAndAppendsNothing() {
        // White: Pusher A5 (4,0), Pusher C4 (3,2). Black: Pusher H2 (6,7)
        // (inert). Natural best move: A5->B6.
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 4, 0);
        board.putPiece(new Pusher(Player.WHITE), 3, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 7);
        board.updateAllLegaLMoves();

        Move repeating = new Move(board.getSquare(4, 0), board.getSquare(5, 1));
        board.movePiece(repeating);
        long seenArrangement = GameTree.arrangementHash(
                board.getZobristHash(), board.getCurrentPlayer());
        board.undo(repeating);

        List<Long> poison = new ArrayList<>();
        poison.add(seenArrangement);

        // Flag ON (production default): the penalty fires, a non-repeating
        // move is chosen, and the search appended the current arrangement.
        GameTree on = new GameTree(Player.WHITE, Player.BLACK);
        on.setRepetitionHistory(poison);
        Move chosenOn = on.getBestMoveQuick(1);
        assertNotNull(chosenOn);
        assertNotEquals(repeating, chosenOn, "sanity (T10's pin): the poison must bite");
        assertEquals(2, on.getRepetitionHistorySize(), "flag on: the search appended the current arrangement");

        // Flag OFF: the same poison history is ignored — the natural best
        // move is chosen — and nothing was appended.
        GameTree off = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, SEED, true, false, false, true,
                        true, true, false, true));
        off.setRepetitionHistory(poison);
        assertEquals(repeating, off.getBestMoveQuick(1), "flag off: the poisoned history must be ignored");
        assertEquals(1, off.getRepetitionHistorySize(), "flag off: the search must not append to the history");
    }

    // ------------------------------------------------------------------
    // Requirement 2 — levels: parsing, the monotone stack, t16 == current
    // ------------------------------------------------------------------

    /**
     * "current" is an alias for t16 (the same constant), unknown levels
     * are rejected at parse time, and the stack is monotone in merge
     * order: each level contains every earlier level's features plus at
     * least one more (t2 is the bare base, t16 the full current engine).
     */
    @Test
    public void levelsParseAndFormAMonotoneStack() {
        assertSame(Level.T16, Level.parse("current"), "current must alias t16");
        assertEquals(Level.T2, Level.parse("t2"));
        assertEquals(Level.T3, Level.parse("t3"));
        assertEquals(Level.T4, Level.parse("t4"));
        assertEquals(Level.T6, Level.parse("t6"));
        assertEquals(Level.T10, Level.parse("t10"));
        assertEquals(Level.T13, Level.parse("t13"));
        assertEquals(Level.T16, Level.parse("t16"));
        assertNull(Level.parse("bogus"), "an unknown level must not parse");
        assertNull(Level.parse(null), "null must not parse");

        Level[] stack = {Level.T2, Level.T3, Level.T4, Level.T6, Level.T10,
                Level.T13, Level.T16};
        for (int i = 1; i < stack.length; i++) {
            Level earlier = stack[i - 1];
            Level later = stack[i];
            assertTrue(earlier.quiescence
                    ? later.quiescence : true, later.key() + " must keep " + earlier.key()
                    + "'s quiescence");
            assertTrue(earlier.threatTerms
                    ? later.threatTerms : true, later.key() + " must keep " + earlier.key()
                    + "'s threat terms");
            assertTrue(earlier.transposition
                    ? later.transposition : true, later.key() + " must keep " + earlier.key()
                    + "'s transposition table");
            assertTrue(earlier.repetition
                    ? later.repetition : true, later.key() + " must keep " + earlier.key()
                    + "'s repetition guard");
            assertTrue(earlier.aspiration
                    ? later.aspiration : true, later.key() + " must keep " + earlier.key()
                    + "'s aspiration windows");
            assertTrue(earlier.pvs ? later.pvs : true, later.key() + " must keep " + earlier.key()
                    + "'s PVS");
            assertTrue(earlier.ordering
                    ? later.ordering : true, later.key() + " must keep " + earlier.key()
                    + "'s proximity ordering");
        }
        // And each step adds at least one feature (the stack strictly
        // grows: t2 bare, t16 full).
        assertFalse(Level.T2.quiescence || Level.T2.threatTerms
                        || Level.T2.transposition || Level.T2.repetition
                        || Level.T2.aspiration || Level.T2.pvs
                        || Level.T2.ordering, "t2 must be the bare base");
        assertTrue(Level.T16.quiescence && Level.T16.threatTerms
                        && Level.T16.transposition && Level.T16.repetition
                        && Level.T16.aspiration && Level.T16.pvs
                        && Level.T16.ordering, "t16 must be the full current engine");
    }

    /**
     * t16 IS the current engine: a tree built through the level plumbing
     * (Bench.treeFor) searches identically to the 4-argument constructor
     * (every off-flag defaulting to on).
     */
    @Test
    public void t16IsBitIdenticalToTheCurrentEngine() {
        final int[] depths = {2, 3, 4};
        for (String which : new String[]{"race", "tactical",
                "defaultStart"}) {
            for (int depth : depths) {
                setT15Position(which);
                Player max = board.getCurrentPlayer();
                Player min = max.other();
                int viaLevel = Bench.treeFor(Level.T16, max, min, BUDGET,
                        SEED)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                setT15Position(which);
                int legacy = new GameTree(max, min, BUDGET, SEED)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                assertEquals(viaLevel, legacy, which + " depth " + depth + ": treeFor(T16) "
                        + "must equal the 4-argument constructor");
            }
        }
    }

    /**
     * CLI: unknown levels are usage errors (both modes), mirror mode
     * rejects --ab, and runAb without --ab is rejected — each prints the
     * usage and returns null (the pre-T15 contract of Bench.run).
     */
    @Test
    public void invalidEngineLevelsAreUsageErrors() {
        assertEquals(null, Bench.runAb(new String[]{
                "--ab", "t13", "bogus", "--games", "1", "--budget", "100"}));
        assertEquals(null, Bench.run(new String[]{
                "--games", "1", "--budget", "100", "--engine", "bogus"}));
        assertEquals(null, Bench.run(new String[]{
                "--ab", "t13", "t4", "--games", "1", "--budget", "100"}));
        assertEquals(null, Bench.runAb(new String[]{
                "--games", "1", "--budget", "100"}));
    }

    // ------------------------------------------------------------------
    // Requirement 4.3 — determinism of the A/B harness
    // ------------------------------------------------------------------

    /**
     * Same level + seed replays the same games: two `--ab t13 t13` runs
     * with the same seed produce identical per-game lines (modulo the
     * wall-clock elapsedMs field, which inherently varies with machine
     * load) and an identical JSON summary.
     *
     * The budget is the T7-validated 2000 ms: on the small board the
     * search ends by CONTENT (the forced-win level completes in tens of
     * ms, so iterative deepening stops on the >9000 cutoff long before
     * the budget), leaving a 25-50x margin so the completed level — and
     * thus the moves, winner and plies — is determined by the position,
     * not by wall-clock jitter (the accepted T14 item-6 behavior, which
     * corrupts same-seed runs only when the budget binds mid-level).
     */
    @Test
    public void sameLevelAndSeedReplayIdentically() {
        String[] args = {"--ab", "t13", "t13", "--games", "2",
                "--budget", "2000", "--seed", "42", "--small"};

        String json1;
        List<String> lines1 = new ArrayList<>();
        String json2;
        List<String> lines2 = new ArrayList<>();
        json1 = runAbCaptured(args, lines1).toJsonLine();
        json2 = runAbCaptured(args, lines2).toJsonLine();

        assertEquals(json1, json2, "the JSON summary must be identical");
        assertEquals(lines1.size(), lines2.size(), "both runs must report the same number of games");
        for (int i = 0; i < lines1.size(); i++) {
            assertEquals(stripElapsedMs(lines1.get(i)), stripElapsedMs(lines2.get(i)), "per-game line " + i + " must be identical "
                    + "(modulo elapsedMs): " + lines1.get(i) + " vs "
                    + lines2.get(i));
        }
    }

    /** runAb with System.out captured; the "game " lines are collected
     *  into lines and the AbSummary returned. */
    private static Bench.AbSummary runAbCaptured(String[] args,
                                                 List<String> lines) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        Bench.AbSummary summary;
        try {
            System.setOut(new PrintStream(captured, true,
                    StandardCharsets.UTF_8));
            summary = Bench.runAb(args);
        } finally {
            System.setOut(originalOut);
        }
        assertNotNull(summary, "runAb must succeed on valid arguments");
        for (String line : captured.toString(StandardCharsets.UTF_8)
                .split("\n")) {
            if (line.startsWith("game "))
                lines.add(line);
        }
        return summary;
    }

    private static String stripElapsedMs(String line) {
        return line.replaceFirst("elapsedMs=\\d+", "elapsedMs=X");
    }

    // ------------------------------------------------------------------
    // helpers

    private void resetAndPlace(Pusher first, int row, int col) {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(first, row, col);
    }

    private void setT15Position(String which) {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        switch (which) {
            case "race":
                board.putPiece(new Pusher(Player.WHITE), 3, 0);
                board.putPiece(new Pusher(Player.WHITE), 4, 2);
                board.putPiece(new Pusher(Player.BLACK), 4, 1);
                board.putPiece(new Pusher(Player.BLACK), 3, 3);
                board.putPiece(new Pushee(Player.BLACK), 5, 2);
                break;
            case "tactical":
                board.putPiece(new Pusher(Player.WHITE), 3, 0);
                board.putPiece(new Pusher(Player.WHITE), 3, 2);
                board.putPiece(new Pushee(Player.WHITE), 4, 1);
                board.putPiece(new Pusher(Player.BLACK), 5, 0);
                board.putPiece(new Pusher(Player.BLACK), 5, 3);
                board.putPiece(new Pushee(Player.BLACK), 4, 2);
                board.putPiece(new Pusher(Player.BLACK), 6, 1);
                break;
            case "captureRace":
                board.putPiece(new Pusher(Player.WHITE), 3, 1);
                board.putPiece(new Pushee(Player.WHITE), 4, 2);
                board.putPiece(new Pusher(Player.WHITE), 5, 4);
                board.putPiece(new Pusher(Player.BLACK), 5, 3);
                board.putPiece(new Pusher(Player.BLACK), 6, 3);
                board.putPiece(new Pusher(Player.BLACK), 6, 2);
                break;
            case "defaultStart":
                for (int c = 0; c < GameBoard.GAME_BOARD_LENGTH; c++) {
                    board.putPiece(new Pusher(Player.WHITE), 0, c);
                    board.putPiece(new Pushee(Player.WHITE), 1, c);
                    board.putPiece(new Pusher(Player.BLACK), 7, c);
                    board.putPiece(new Pushee(Player.BLACK), 6, c);
                }
                break;
            default:
                throw new IllegalArgumentException(which);
        }
        board.updateAllLegaLMoves();
    }
}
