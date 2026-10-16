package tests;

import client.GameTree;
import client.GameTreeConfig;
import client.TranspositionTable;
import game.*;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * T13 Part A — aspiration windows.
 *
 * The production path (the no-arg {@code getBestMoveQuick}) iterative deepens
 * under a wall-clock budget, so its final depth (and hence any tie between
 * equally-best moves) is not reproducible from a seed. To test the aspiration
 * logic deterministically, these tests drive the SAME per-level aspiration
 * search through two depth-capped score hooks that share the production
 * {@code searchLevelWithAspiration} helper:
 *
 *   - {@code aspirationScoreAtDepth(d)}: iterative deepening WITH aspiration
 *     windows, stopped at depth d, returning the root score;
 *   - {@code debugSearchScore(d, alpha, beta)}: a single full (or explicitly
 *     windowed) search at depth d, returning the root score.
 *
 * Because a full-window alpha-beta search returns the exact minimax value at
 * the depth, and aspiration (which retries on a fail until the window contains
 * the true score) is exact, the two must agree at every depth. That equality is
 * the safety net for subtle value errors; the retry and cutoff tests pin the
 * specific interactions the plan calls out.
 *
 * Part B (PVS) tests below use the same score-based pattern: PVS is exact
 * (a null window reports a violation exactly when the child is a PV candidate,
 * and the fail-high re-search recovers the true value), so every root score
 * with PVS on must equal the pre-Part-B engine's (PVS off — the constructor
 * flag restores the full-window child loop verbatim) on the same position and
 * seed. Move-identity checks are limited to positions whose best move at the
 * test depth is unique in value, so shuffle ties cannot flake them (the same
 * lesson as Part A's move-based check).
 *
 * @see IMPROVEMENT_PLAN.md task T13 (Parts A and B)
 */
public class AspirationPvsTest {

    private final GameBoard board = GameBoard.getInstance();

    /** A generous per-move budget: the fixed depths used here finish in ms. */
    private static final long BUDGET = GameTree.MAX_TIME_MILLIS;

    @After
    public void cleanBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    // ------------------------------------------------------------------
    // Positions (all White to move). Verified to exercise the behaviors below.
    // ------------------------------------------------------------------

    /** A position whose level-1 and level-2 scores differ by more than the
     *  aspiration margin, so an aspiration fail/retry is forced. */
    private void setRace(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 3, 0);
        board.putPiece(new Pusher(Player.WHITE), 4, 2);
        board.putPiece(new Pusher(Player.BLACK), 4, 1);
        board.putPiece(new Pusher(Player.BLACK), 3, 3);
        board.putPiece(new Pushee(Player.BLACK), 5, 2);
        board.updateAllLegaLMoves();
    }

    /** A capture-heavy tactical position (no forced line at the depths used). */
    private void setTactical(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 3, 0);
        board.putPiece(new Pusher(Player.WHITE), 3, 2);
        board.putPiece(new Pushee(Player.WHITE), 4, 1);
        board.putPiece(new Pusher(Player.BLACK), 5, 0);
        board.putPiece(new Pusher(Player.BLACK), 5, 3);
        board.putPiece(new Pushee(Player.BLACK), 4, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 1);
        board.updateAllLegaLMoves();
    }

    /** The 32-piece default start (rich branching for the node-count test). */
    private void setDefaultStart(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            board.putPiece(new Pusher(Player.WHITE), 0, j);
            board.putPiece(new Pushee(Player.WHITE), 1, j);
            board.putPiece(new Pusher(Player.BLACK), 7, j);
            board.putPiece(new Pushee(Player.BLACK), 6, j);
        }
        board.updateAllLegaLMoves();
    }

    /** A White forced win at depth 3 (White's pusher reaches the goal row in
     *  two quiet moves; Black's pusher needs three). Several moves win, so the
     *  tests assert on the SCORE (|score| > 9000), not a specific move. */
    private void setWhiteForcedWin(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.BLACK), 3, 6);
        board.updateAllLegaLMoves();
    }

    /** Two root captures; the best-ordered one (MVV-LVA: the pushee's capture
     *  of a pusher, C5D6) is worth ~2, while the second-ordered capture (E6D7)
     *  is a depth-3 forced win. Only the null window + fail-high re-search of
     *  E6D7 finds the winning score: without the re-search the root score is a
     *  bound and later tied quiet moves can steal the choice. */
    private void setCaptureRace(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 3, 1);   // behind the pushee
        board.putPiece(new Pushee(Player.WHITE), 4, 2);   // captures E5
        board.putPiece(new Pusher(Player.WHITE), 5, 4);   // captures D7 (wins)
        board.putPiece(new Pusher(Player.BLACK), 5, 3);   // the pushee's target
        board.putPiece(new Pusher(Player.BLACK), 6, 3);   // the pusher's target
        board.putPiece(new Pusher(Player.BLACK), 6, 2);   // recaptures the pushee
        board.updateAllLegaLMoves();
    }

    /** White to move. The Black pusher (1,1) is one move from the Black goal
     *  row; the unique best move is the rescue capture (0,0) -> (1,1). */
    private void setRescue(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 0, 0);
        board.putPiece(new Pusher(Player.WHITE), 5, 5);
        board.putPiece(new Pusher(Player.BLACK), 1, 1);
        board.updateAllLegaLMoves();
    }

    /** GameTreeTest.testStupidLoss geometry, Black to move: Black must capture
     *  the White pusher (6,6) that threatens the White goal row. */
    private void setStupidLoss(){
        board.reset();
        board.setCurrentPlayer(Player.BLACK);
        board.putPiece(new Pusher(Player.BLACK), 2, 5);
        board.putPiece(new Pusher(Player.BLACK), 7, 5);
        board.putPiece(new Pusher(Player.WHITE), 6, 6);
        board.updateAllLegaLMoves();
    }

    // ------------------------------------------------------------------
    // 1. Aspiration returns the exact value (the safety net; acceptance 7).
    // ------------------------------------------------------------------

    /**
     * At every depth, the aspiration iterative deepening (with its retry on a
     * fail) returns exactly the same root score as a plain full-window search
     * at that depth. Aspiration is pure search pruning: it changes which nodes
     * are visited, never the game-theoretic value.
     */
    @Test
    public void aspirationReturnsExactValueAtEveryDepth(){
        final long seed = 42L;
        final int[] depths = {2, 3, 4};

        checkExact("race", seed, depths);
        checkExact("tactical", seed, depths);
        checkExact("defaultStart", seed, depths);
        checkExact("whiteForcedWin", seed, depths);
    }

    private void checkExact(String which, long seed, int[] depths){
        for (int depth : depths){
            setPos(which);
            int withAspiration = treeFor(which, seed, true, true, true)
                    .aspirationScoreAtDepth(depth);

            // The reference is the pre-T13 engine: full window at every level,
            // and PVS off as well (a single debugSearchScore does not use
            // aspiration, but the flag keeps the child loop verbatim pre-T13).
            setPos(which);
            int reference = treeFor(which, seed, true, false, false)
                    .debugSearchScore(depth, Integer.MIN_VALUE, Integer.MAX_VALUE);

            assertEquals(withAspiration, reference);
        }
    }

    // ------------------------------------------------------------------
    // 2. A narrow aspiration window is exact AND prunes more (acceptance 3).
    // ------------------------------------------------------------------

    /**
     * On the 32-piece start at depth 4, a window of (score +/- 5) around the
     * true score returns the SAME score as a full window but visits fewer
     * nodes. The tight window lets the root's min children fail low sooner.
     * PVS is disabled on BOTH trees: with PVS active the null windows already
     * prune this position down to the same node count, so the aspiration
     * effect would be invisible (see the PVS node test below).
     */
    @Test
    public void narrowAspirationWindowIsExactAndPrunesMore(){
        final long seed = 42L;
        final int depth = 4;

        setDefaultStart();
        GameTree fullTree = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, false, true, false,
                        true, true, true, true, true));
        int fullScore = fullTree.debugSearchScore(depth,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        long fullNodes = fullTree.getNodeCount();

        setDefaultStart();
        GameTree narrowTree = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, false, true, false,
                        true, true, true, true, true));
        int narrowScore = narrowTree.debugSearchScore(depth,
                fullScore - 5, fullScore + 5);
        long narrowNodes = narrowTree.getNodeCount();

        assertEquals("a tight window must not change the value", fullScore, narrowScore);
        assertTrue("a tight window must visit no more nodes (full=" + fullNodes
                + ", narrow=" + narrowNodes + ")", narrowNodes <= fullNodes);
        assertTrue("a tight window should prune at least one node here (full="
                + fullNodes + ", narrow=" + narrowNodes + ")", narrowNodes < fullNodes);
    }

    // ------------------------------------------------------------------
    // 3. An aspiration failure is retried and still exact (acceptance 4).
    // ------------------------------------------------------------------

    /**
     * The 'race' position's level-1 score and level-2 score differ by more than
     * the +/-5 aspiration margin, so the level-2 search fails the narrow window
     * and must widen and retry. The retry recovers the exact value: the
     * aspiration score equals the full-window reference AND at least one retry
     * actually happened.
     */
    @Test
    public void aspirationFailureIsRetriedAndStillExact(){
        final long seed = 42L;
        final int depth = 4;

        setRace();
        GameTree withAspiration = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, true, true, true, true,
                        true, true, true, true));
        int score = withAspiration.aspirationScoreAtDepth(depth);

        setRace();
        int reference = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, true, false, true, true,
                        true, true, true, true))
                .debugSearchScore(depth,
                Integer.MIN_VALUE, Integer.MAX_VALUE);

        assertTrue("expected this position to force an aspiration retry, but none "
                + "happened (score=" + score + ")",
                withAspiration.getAspirationRetries() > 0);
        assertEquals("a retry must still recover the exact value", reference, score);
    }

    // ------------------------------------------------------------------
    // 4. The forced-win cutoff is unaffected by aspiration (acceptance 5).
    // ------------------------------------------------------------------

    /**
     * A position with a forced win at depth 3 returns |score| > 9000 with
     * aspiration active: the forced line is found regardless of the window
     * width, and the fail-high that a narrow window would otherwise retry is
     * the cutoff breaking the deepening instead (it is NOT retried).
     */
    @Test
    public void forcedWinCutoffIsUnaffectedByAspiration(){
        final long seed = 42L;
        final int depth = 3;

        setWhiteForcedWin();
        int score = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, true, true, true, true,
                        true, true, true, true))
                .aspirationScoreAtDepth(depth);

        assertTrue("the forced win must be found (|score| > 9000), got " + score,
                Math.abs(score) > 9000);
        assertEquals("a forced win ranks above every static score", score,
                Integer.MAX_VALUE - 3);
    }

    // ------------------------------------------------------------------
    // 5. Disabling aspiration restores the pre-T13 full-window behavior.
    // ------------------------------------------------------------------

    /**
     * With aspiration disabled the iterative deepening uses a full window at
     * every level: on the 'race' position (which retries when aspiration is on)
     * no retry occurs, and the score still matches the full-window reference.
     */
    @Test
    public void disabledAspirationUsesFullWindowsAndNeverRetries(){
        final long seed = 42L;
        final int depth = 4;

        setRace();
        GameTree enabled = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, true, true, true, true,
                        true, true, true, true));
        int enabledScore = enabled.aspirationScoreAtDepth(depth);
        assertTrue("sanity: aspiration is on and does retry here",
                enabled.getAspirationRetries() > 0);

        setRace();
        GameTree disabled = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, seed, true, false, true, true,
                        true, true, true, true));
        int disabledScore = disabled.aspirationScoreAtDepth(depth);
        assertEquals(0, disabled.getAspirationRetries());

        assertEquals("disabling aspiration must not change the value",
                enabledScore, disabledScore);
    }

    // ------------------------------------------------------------------
    // 6. The production path stays legal and within budget with aspiration.
    // ------------------------------------------------------------------

    /**
     * The real {@code getBestMoveQuick()} (iterative deepening under the wall
     * clock, aspiration on) still returns a legal move for the side to move and
     * finishes within the budget, on the full 32-piece start.
     */
    @Test
    public void getBestMoveQuickWithAspirationStaysLegalAndWithinBudget(){
        setDefaultStart();
        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);

        long before = System.currentTimeMillis();
        Move best = gameTree.getBestMoveQuick();
        long elapsed = System.currentTimeMillis() - before;

        assertNotNull("expected a move on the full start", best);
        assertTrue("getBestMoveQuick took " + elapsed + " ms, expected <= "
                + (GameTree.MAX_TIME_MILLIS + 500),
                elapsed <= GameTree.MAX_TIME_MILLIS + 500);
        assertTrue("the returned move must be legal for the side to move",
                board.getAllLegalMovesQuick().contains(best));
    }

    private void setPos(String which){
        switch (which){
            case "race": setRace(); break;
            case "tactical": setTactical(); break;
            case "defaultStart": setDefaultStart(); break;
            case "whiteForcedWin": setWhiteForcedWin(); break;
            case "captureRace": setCaptureRace(); break;
            case "rescue": setRescue(); break;
            case "stupidLoss": setStupidLoss(); break;
            default: throw new IllegalArgumentException(which);
        }
    }

    /**
     * A fresh tree for the given position: max = side to move (the
     * production configuration — each tree is for the side that must move,
     * so the root is always a max node).
     */
    private GameTree treeFor(String which, long seed, boolean transpositionEnabled,
                             boolean aspirationEnabled, boolean pvsEnabled){
        setPos(which);
        Player max = board.getCurrentPlayer();
        return new GameTree(max, max.other(),
                new GameTreeConfig(BUDGET, seed, transpositionEnabled,
                        aspirationEnabled, pvsEnabled, true, true, true,
                        true, true));
    }

    /** A diagonal move onto an enemy square (the tests cannot reach
     *  GameTree's private capture check). */
    private static boolean isCapture(Move move, Player player){
        Piece destinationPiece = move.destination().getPiece();
        return destinationPiece != null
                && destinationPiece.getOwner() != player;
    }

    // ------------------------------------------------------------------
    // T13 Part B — PVS (null window + fail-high re-search in the child loop)
    // ------------------------------------------------------------------

    /**
     * B1 — PVS correctness (fixed value; acceptance 1). At every depth, the
     * root score with PVS active equals the pre-Part-B engine's (PVS off —
     * the constructor flag restores the full-window child loop verbatim,
     * which is the inlined reference the plan describes). PVS is pure
     * search pruning: it changes which nodes are visited, never the
     * game-theoretic value. The forced-win positions (whiteForcedWin, and
     * the depth-3/4 lines of captureRace) additionally show the ±9000
     * cutoff still fires with PVS active.
     */
    @Test
    public void pvsReturnsExactValueAtEveryDepth(){
        final long seed = 42L;
        final int[] depths = {2, 3, 4};

        checkPvsExact("race", seed, depths);
        checkPvsExact("tactical", seed, depths);
        checkPvsExact("captureRace", seed, depths);
        checkPvsExact("rescue", seed, depths);
        checkPvsExact("stupidLoss", seed, depths);
        checkPvsExact("defaultStart", seed, depths);
        checkPvsExact("whiteForcedWin", seed, depths);
    }

    private void checkPvsExact(String which, long seed, int[] depths){
        for (int depth : depths){
            setPos(which);
            int withPvs = treeFor(which, seed, true, false, true)
                    .debugSearchScore(depth, Integer.MIN_VALUE, Integer.MAX_VALUE);

            setPos(which);
            int reference = treeFor(which, seed, true, false, false)
                    .debugSearchScore(depth, Integer.MIN_VALUE, Integer.MAX_VALUE);

            assertEquals(which + " depth " + depth, reference, withPvs);
        }
    }

    /**
     * B2 — the PVS re-search fires and finds the best move (acceptance 2).
     * On the 'captureRace' position the best-ordered root child (the MVV-LVA
     * capture C5D6) is worth ~2; the best move is the OTHER capture (E6D7),
     * a depth-3 forced win ordered second. Its null window (alpha, alpha + 1)
     * fails high and the fail-high re-search is what recovers the winning
     * score. Without the re-search the root score would be the null-window
     * bound and a later tied quiet move could steal the choice, so the
     * assertions below pin the re-search directly. (Under T6's ordering a
     * capture can never follow a quiet move at a fresh root, so the plan's
     * "quiet first child" variant cannot occur; this position realizes the
     * essential case: the best-ordered child is not the best move, and the
     * best move is a capture found only through the re-search.)
     */
    @Test
    public void pvsResearchedWhenNullWindowViolates(){
        final long seed = 42L;
        final int depth = 3;

        setPos("captureRace");
        GameTree reference = treeFor("captureRace", seed, true, false, false);
        Move expected = reference.getBestMoveQuick(depth);

        setPos("captureRace");
        GameTree pvs = treeFor("captureRace", seed, true, false, true);
        Move chosen = pvs.getBestMoveQuick(depth);

        assertEquals("PVS must choose the same move as the pre-PVS engine",
                expected, chosen);
        assertTrue("the chosen move must be the capture: " + chosen,
                isCapture(chosen, Player.WHITE));
        assertTrue("a null-window violation must have triggered a re-search",
                pvs.getPvsResearched() > 0);
    }

    /**
     * B3 — PVS is exact and prunes (the PVS counterpart of acceptance 3).
     * On the 32-piece start at depth 4, with the TT disabled on both trees
     * (isolating PVS), PVS visits fewer nodes than the pre-PVS full-window
     * search and returns the same score: the null windows cut siblings that
     * the full window would have searched, and the re-searches are rarer
     * than the siblings they replace at this branching.
     */
    @Test
    public void pvsIsExactAndPrunesMore(){
        final long seed = 42L;
        final int depth = 4;

        setDefaultStart();
        GameTree offTree = treeFor("defaultStart", seed, false, false, false);
        int offScore = offTree.debugSearchScore(depth,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        long offNodes = offTree.getNodeCount();

        setDefaultStart();
        GameTree onTree = treeFor("defaultStart", seed, false, false, true);
        int onScore = onTree.debugSearchScore(depth,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        long onNodes = onTree.getNodeCount();

        assertEquals("PVS must not change the value", offScore, onScore);
        assertTrue("PVS must visit fewer nodes (off=" + offNodes
                + ", on=" + onNodes + ")", onNodes < offNodes);
    }

    /**
     * B4 — TT flag consistency (acceptance 6). On the 'tactical' position at
     * depth 3 (unique best move, so both trees search the same child), the
     * entry stored at the root's child after a PVS search has the same flag,
     * score and depth as the one the pre-PVS search stores there: the store
     * uses the node's final alpha/beta after any re-search, exactly as
     * before.
     */
    @Test
    public void pvsStoresSameTTFlagsAsPrePvs(){
        final long seed = 42L;
        final int depth = 3;

        setPos("tactical");
        Move childMove = treeFor("tactical", seed, true, false, false)
                .getBestMoveQuick(depth);

        TranspositionTable.Entry[] entries = new TranspositionTable.Entry[2];
        for (int i = 0; i < 2; i++){
            boolean t13on = (i == 1);
            setPos("tactical");
            GameTree tree = treeFor("tactical", seed, true, t13on, t13on);
            Move best = tree.getBestMoveQuick(depth);
            assertEquals("the best move must be the same with and without PVS",
                    childMove, best);

            board.movePiece(best);
            long hash = board.getZobristHash();
            int turn = board.getTurnNumber();
            board.undo(best);
            entries[i] = tree.probeTT(hash, turn);
        }

        assertNotNull("the child position must be in the pre-PVS table", entries[0]);
        assertNotNull("the child position must be in the PVS table", entries[1]);
        assertEquals("PVS must store the same flag at the root's child",
                entries[0].flag(), entries[1].flag());
        assertEquals("... with the same score", entries[0].score(), entries[1].score());
        assertEquals("... at the same depth", entries[0].depth(), entries[1].depth());
    }

    /**
     * B5 — determinism with T13 on vs off (acceptance 7). With a fixed seed,
     * the best move chosen on five fixed positions (each with a unique best
     * move at the test depth, verified across the seeds below so shuffle ties
     * cannot flake the check) is identical with T13 (aspiration + PVS) on
     * and off. The positions cover the interesting PVS shapes: a quiet race,
     * a capture cluster, a re-search-found capture, a rescue capture, and a
     * Black-to-move root (a max node for the Black tree, as in production).
     */
    @Test
    public void t13OnAndOffChooseTheSameMove(){
        final int depth = 3;
        final long[] seeds = {42L, 1337L, 2024L, 7L, 999L};
        final String[] positions = {"race", "tactical", "captureRace",
                "rescue", "stupidLoss"};

        for (String which : positions){
            for (long seed : seeds){
                setPos(which);
                Move off = treeFor(which, seed, true, false, false)
                        .getBestMoveQuick(depth);
                setPos(which);
                Move on = treeFor(which, seed, true, true, true)
                        .getBestMoveQuick(depth);
                assertEquals(which + " seed=" + seed + " (T13 on vs off)", off, on);
            }
        }
    }
}
