package tests;

import client.GameTree;
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
 * Part B (PVS) tests will be added to this file when that task lands.
 *
 * @see IMPROVEMENT_PLAN.md task T13 (Part A)
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
            int withAspiration = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                    true, true).aspirationScoreAtDepth(depth);

            setPos(which);
            int reference = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                    true, false).debugSearchScore(depth, Integer.MIN_VALUE, Integer.MAX_VALUE);

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
     */
    @Test
    public void narrowAspirationWindowIsExactAndPrunesMore(){
        final long seed = 42L;
        final int depth = 4;

        setDefaultStart();
        GameTree fullTree = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                false, true);
        int fullScore = fullTree.debugSearchScore(depth,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        long fullNodes = fullTree.getNodeCount();

        setDefaultStart();
        GameTree narrowTree = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                false, true);
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
        GameTree withAspiration = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                true, true);
        int score = withAspiration.aspirationScoreAtDepth(depth);

        setRace();
        int reference = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                true, false).debugSearchScore(depth,
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
        int score = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                true, true).aspirationScoreAtDepth(depth);

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
        GameTree enabled = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                true, true);
        int enabledScore = enabled.aspirationScoreAtDepth(depth);
        assertTrue("sanity: aspiration is on and does retry here",
                enabled.getAspirationRetries() > 0);

        setRace();
        GameTree disabled = new GameTree(Player.WHITE, Player.BLACK, BUDGET, seed,
                true, false);
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
            default: throw new IllegalArgumentException(which);
        }
    }
}
