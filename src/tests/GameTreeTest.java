package tests;

import client.GameAnalyzer;
import client.GameTree;
import client.TranspositionTable;
import game.*;
import org.junit.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * JUnit tests for GameTree.java
 * @author Samuel Charade
 * @since 11/4/2015.
 */
public class GameTreeTest {

    private static final int MAX_ALLOTTED_TIME_MILLIS = 5000;

    private GameBoard board = GameBoard.getInstance();
    private GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);
    private GameTree gameTreeBlack = new GameTree(Player.BLACK, Player.WHITE);

    @Test
    public void testQuickTimeTaken(){
        resetBoard();
        setBoardToDefaultStart();

        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);
        gameTree.getBestMoveQuick(1);
        long before = System.currentTimeMillis();
        gameTree.getBestMoveQuick();
        long after = System.currentTimeMillis();

        assertTrue("Generating tree took over " + MAX_ALLOTTED_TIME_MILLIS
                + "milliseconds...",
                after - before <= MAX_ALLOTTED_TIME_MILLIS);
    }

    @Test
    public void wtfTest(){
        board.reset();
        setUpWtfSituation();

        System.out.println(gameTree.getBestMoveQuick());
        System.out.println(board);
    }

    private void playWhite(){
        Move move = gameTree.getBestMoveQuick();
        board.movePiece(move);
        System.out.println(board);
        board.updateAllLegaLMoves();
    }

    private void playBlack(){
        Move move = gameTreeBlack.getBestMoveQuick();
        board.movePiece(move);
        System.out.println(board);
        board.updateAllLegaLMoves();
    }

    private void setUpWtfSituation(){
        board.putPiece(new Pusher(Player.WHITE),1, 5);
        board.putPiece(new Pusher(Player.WHITE),1, 4);
        board.putPiece(new Pusher(Player.WHITE),1, 3);
        board.putPiece(new Pusher(Player.WHITE),2, 3);
        board.putPiece(new Pusher(Player.BLACK),2, 4);
    }

    @Test
    public void testWinCondition(){
        resetBoard();
        Piece winPiece = new Pusher(Player.WHITE);
        Piece trollPiece = new Pusher(Player.WHITE);
        Piece losePiece = new Pusher(Player.BLACK);

        board.putPiece(winPiece, 6, 5);
        board.putPiece(trollPiece, 0, 3);
        board.putPiece(losePiece, 1, 3);
        board.movePiece(gameTree.getBestMoveQuick());

        assertTrue(board.isEmptyAt(6, 5));
    }


    @Test
    public void testStupidLoss(){
        board.reset();
        board.setCurrentPlayer(Player.BLACK);
        board.putPiece(new Pusher(Player.BLACK), 2,5);
        board.putPiece(new Pusher(Player.BLACK), 7,5);
        board.putPiece(new Pusher(Player.WHITE), 6,6);

        Move expectedBestMove = new Move(board.getSquare(7,5), board.getSquare(6,6));

        assertEquals(expectedBestMove, gameTreeBlack.getBestMoveQuick());

    }

    @Test
    public void test2DepthLevel(){
        resetBoard();
        Piece whitePiece = new Pusher(Player.WHITE);
        Piece blackPiece = new Pusher(Player.BLACK);
        Move expectedBestMove = new Move(board.getSquare(3, 3), board.getSquare(4, 3));

        board.putPiece(whitePiece, 3, 3);
        board.putPiece(blackPiece, 5, 3);
        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);
        board.updateAllLegaLMoves();

        System.out.println(gameTree.getBestMoveQuick());
        assertEquals(expectedBestMove, gameTree.getBestMoveQuick(3));
    }


    @Test
    public void testRandomnessForMoreThanOneMove(){
        int timesPiece1Moved = 0;
        int timesPiece2Moved = 0;

        resetBoard();
        Piece piece1 = new Pusher(Player.WHITE);
        Piece piece2 = new Pusher(Player.WHITE);

        board.putPiece(piece1, 0, 1);
        board.putPiece(piece2, 0, 2);
        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);

        for (int i = 0; i < 5; i++) {
            Move chosenMove = gameTree.getBestMoveQuick();

            if (chosenMove.getPieceLocation() == board.getSquare(0, 1))
                timesPiece1Moved++;
            else
                timesPiece2Moved++;


        }

        assertTrue("Always chose first possible move...",
                timesPiece2Moved > 0);
    }

    private void resetBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void setBoardToDefaultStart(){
        createWhitePushers();
        createWhitePushees();
        createBlackPushers();
        createBlackPushees();
    }

    private void createWhitePushers(){
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            board.putPiece(new Pusher(Player.WHITE), 0, j);
        }
    }

    private void createWhitePushees(){
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            board.putPiece(new Pushee(Player.WHITE), 1, j);
        }
    }

    private void createBlackPushers(){
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            board.putPiece(new Pusher(Player.BLACK), 7, j);
        }
    }

    private void createBlackPushees(){
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            board.putPiece(new Pushee(Player.BLACK), 6, j);
        }
    }

    // ==================================================================
    // T14 — search-stack audit (see IMPROVEMENT_PLAN.md, task T14)
    // ==================================================================

    /** A generous per-move budget: the fixed depths used here finish in ms. */
    private static final long T14_BUDGET = GameTree.MAX_TIME_MILLIS;

    // ------------------------------------------------------------------
    // Item 1 — internal-node TT best-move handoff (BUG-FIXED)
    // ------------------------------------------------------------------

    /**
     * Item 1: the relaxed handoff (internal nodes order by the TT best move
     * from an entry at ANY stored depth — the standard ID handoff) is pure
     * ORDERING: on vs off returns the identical root score at every depth
     * (a full-window search is exact either way, and the score-RETURN gate
     * is strict in both). The fixed behavior is handoff ON (the default);
     * this test pins that turning it off never changes a value.
     */
    @Test
    public void ttMoveHandoffIsOrderingOnlyScoresUnchanged(){
        final long seed = 42L;
        final int[] depths = {2, 3, 4, 5};
        final String[] positions = {"race", "tactical", "tacticalBlack",
                "whiteForcedWin", "captureRace", "defaultStart"};

        for (String which : positions){
            for (int depth : depths){
                setT14Position(which);
                int handoffOff = t14Tree(which, seed, false)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                setT14Position(which);
                int handoffOn = t14Tree(which, seed, true)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                assertEquals(which + " depth " + depth
                        + ": the handoff must not change the value",
                        handoffOff, handoffOn);
            }
        }
    }

    /**
     * Item 1: the performance pin. On the full 32-piece board, iterative
     * deepening (TT warm from depths 1..7) then the depth-8 search: with the
     * relaxed handoff it visits strictly fewer nodes than with the pre-T14
     * gate — the previous level's best move now reaches the ordering at
     * every internal node, so the best-ordered child cuts its siblings
     * earlier. (The audit measured -38% at depth 8 and -47% at depth 9 with
     * TT + PVS on, seed 42; this pin runs with PVS off so the reduction is
     * attributable to ordering alone, and still strictly smaller.)
     */
    @Test
    public void ttMoveHandoffReducesNodesOnFullBoard(){
        final long seed = 42L;
        final int measuredDepth = 8;

        long[] nodes = new long[2];
        for (int i = 0; i < 2; i++){
            boolean handoff = (i == 1);
            setT14Position("defaultStart");
            GameTree tree = t14Tree("defaultStart", seed, handoff);
            for (int d = 1; d <= measuredDepth; d++){
                tree.debugSearchScore(d, Integer.MIN_VALUE, Integer.MAX_VALUE);
                nodes[i] = tree.getNodeCount();
            }
        }
        assertTrue("the relaxed handoff must visit fewer nodes at depth "
                + measuredDepth + " (off=" + nodes[0] + ", on=" + nodes[1]
                + ")", nodes[1] < nodes[0]);
    }

    // ------------------------------------------------------------------
    // Item 1 (implementation note) — constructor wiring regression guard
    // ------------------------------------------------------------------

    /**
     * The T14 implementation added an 8-argument public constructor next to
     * a 9-argument private one, and every public constructor delegates with
     * an 8-argument this() call — which silently binds to the PUBLIC
     * 8-argument overload and shifts every flag (caught live during T14:
     * aspiration was disabled and the TT dropped exactly that way). This
     * test pins each public constructor's flags against observable behavior
     * (TT probes, aspiration retries, PVS re-searches) so a future
     * overload-shape change fails here instead of in a silent behavior flip.
     */
    @Test
    public void constructorFlagsAreWiredToTheirDocuments(){
        final long seed = 42L;

        // TT: probes accumulate iff the table is enabled.
        for (boolean tt : new boolean[]{true, false}){
            setT14Position("race");
            GameTree tree = new GameTree(Player.WHITE, Player.BLACK,
                    T14_BUDGET, seed, tt);
            tree.debugSearchScore(3, Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertEquals("tt flag " + tt, tt, tree.getTTProbes() > 0);
        }

        // Aspiration: the 'race' position forces a retry at level 2+ iff
        // aspiration is enabled (its level-1 and level-2 scores differ by
        // more than the margin).
        for (boolean asp : new boolean[]{true, false}){
            setT14Position("race");
            GameTree tree = new GameTree(Player.WHITE, Player.BLACK,
                    T14_BUDGET, seed, true, asp);
            tree.aspirationScoreAtDepth(4);
            assertEquals("aspiration flag " + asp, asp,
                    tree.getAspirationRetries() > 0);
        }

        // PVS: the 'captureRace' position forces a fail-high re-search iff
        // PVS is enabled (the best-ordered capture is not the best move).
        for (boolean pvs : new boolean[]{true, false}){
            setT14Position("captureRace");
            GameTree tree = new GameTree(Player.WHITE, Player.BLACK,
                    T14_BUDGET, seed, true, false, pvs);
            tree.debugSearchScore(3, Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertEquals("pvs flag " + pvs, pvs,
                    tree.getPvsResearched() > 0);
        }
    }

    // ------------------------------------------------------------------
    // Item 3 — the +/-9000 forced-win bound margin (VERIFIED-FINE)
    // ------------------------------------------------------------------

    /**
     * Item 3: the worst-case static score on the full board, measured on a
     * maximally loaded position (every T4 term at once, White's view):
     *   - all 8 White Pushees on row 6, each with a mirror Pusher behind
     *     (row 5) => READY_TO_WIN x 8 = 3200, the dominant term;
     *   - every White piece maximally advanced (own base: 8 Pushers at
     *     7+5, 6 non-edge Pushees at 3+6 = 150);
     *   - Black's 16 pieces in the two rows of least advancement that fit
     *     (penalty: 8 Pushers at 7+3+2, 8 Pushees at 3+4+2 = -168);
     *   - White mobility 20 / 2 = 10; no capture threat, no shield
     *     (Black has no ready Pushee).
     * Total: 150 - 168 + 10 + 3200 = 3192, leaving 9000 - 3192 = 5808
     * (~64%) of headroom under the forced-win cutoff. The quiescence
     * stand-pat path is this SAME analyze call, so it cannot exceed the
     * same bound (a q-node exceeds it only through a capture line into a
     * terminal win, which is the forced-win signal the cutoff exists for).
     */
    @Test
    public void maxStaticScoreStaysBelowForcedWinBound(){
        resetBoard();
        for (int c = 0; c < GameBoard.GAME_BOARD_LENGTH; c++){
            board.putPiece(new Pusher(Player.WHITE), 5, c);
            board.putPiece(new Pushee(Player.WHITE), 6, c);
            board.putPiece(new Pusher(Player.BLACK), 4, c);
            board.putPiece(new Pushee(Player.BLACK), 3, c);
        }
        board.updateAllLegaLMoves();

        int whiteScore = GameAnalyzer.analyzeFor(board, Player.WHITE);
        int blackScore = GameAnalyzer.analyzeFor(board, Player.BLACK);

        assertEquals("the term breakdown (150 - 168 + 10 + 3200) must be "
                + "exact — a drift here changes the documented headroom",
                3192, whiteScore);
        assertTrue("max achievable static score must stay under the forced-"
                + "win bound with headroom (white=" + whiteScore + ")",
                Math.abs(whiteScore) < 9000);
        assertTrue("... from Black's view as well (black=" + blackScore + ")",
                Math.abs(blackScore) < 9000);
    }

    // ------------------------------------------------------------------
    // Item 4 — PVS x TT dirty-table fuzz (VERIFIED-FINE)
    // ------------------------------------------------------------------

    /**
     * Item 4: on 20 seeded random small positions, a full-window depth-4
     * search with an EMPTY table, the SAME position/depth re-run with the
     * table still DIRTY from that search (PVS on), and the same re-run by a
     * PVS-off search probing the same dirty table — all three root scores
     * must agree. Any unsound probe path through the bound entries that the
     * null-window searches stored (UPPER/LOWER from zero-width windows) would
     * show up as a score change in pass 2 or 3. The board state is asserted
     * exact after every pass (item 8's no-dirty-board path, for the same
     * searches).
     */
    @Test
    public void pvsXttDirtyTableReuseIsSound(){
        final int depth = 4;
        final long baseSeed = 42L;

        for (int i = 0; i < 20; i++){
            long seed = baseSeed + i;
            resetBoard();
            buildRandomSmallPosition(seed);
            Player max = board.getCurrentPlayer();
            Player min = max.other();

            TranspositionTable shared = new TranspositionTable();

            // Pass 1: fresh (empty) table, PVS on, full window.
            GameTree pvsOn = new GameTree(max, min, T14_BUDGET, seed,
                    true, false, true);
            pvsOn.setTranspositionTable(shared);
            int s1 = pvsOn.debugSearchScore(depth, Integer.MIN_VALUE,
                    Integer.MAX_VALUE);
            long hash0 = board.getZobristHash();

            // Pass 2: same tree, table still dirty from pass 1.
            int s2 = pvsOn.debugSearchScore(depth, Integer.MIN_VALUE,
                    Integer.MAX_VALUE);
            assertEquals("position " + i + ": pass 2 must leave the board "
                    + "exact", hash0, board.getZobristHash());

            // Pass 3: a PVS-off search probing the SAME dirty table.
            GameTree pvsOff = new GameTree(max, min, T14_BUDGET, seed,
                    true, false, false);
            pvsOff.setTranspositionTable(shared);
            int s3 = pvsOff.debugSearchScore(depth, Integer.MIN_VALUE,
                    Integer.MAX_VALUE);
            assertEquals("position " + i + ": pass 3 must leave the board "
                    + "exact", hash0, board.getZobristHash());

            assertEquals("position " + i + " (seed " + seed + "): dirty-"
                    + "table reuse (PVS on) must return the same root score",
                    s1, s2);
            assertEquals("position " + i + " (seed " + seed + "): dirty-"
                    + "table reuse (PVS off) must return the same root score",
                    s1, s3);
        }
    }

    // ------------------------------------------------------------------
    // Item 8 — board-state discipline (VERIFIED-FINE)
    // ------------------------------------------------------------------

    /**
     * Item 8: no dirty-board path in the search machinery. A search that
     * performs PVS fail-high RE-SEARCHES (the re-search reuses the
     * already-applied move and must undo its own descendants exactly once)
     * and a search that performs aspiration RETRIES (each retry reverts to
     * the same pre-iteration snapshot) both leave the board exact: same key,
     * side to move, turn number and the same SET of legal moves (the
     * aggregate's order is not part of the invariant — the board's
     * incremental list maintenance can re-order it across move/undo pairs,
     * pre-T13 as well, and the search shuffles the non-TT, non-capture
     * moves regardless).
     */
    @Test
    public void boardStateIsExactAfterResearchedAndRetriedSearches(){
        // PVS re-searches: 'captureRace' at depth 3 (the best-ordered
        // capture is not the best move; the re-search fires).
        setT14Position("captureRace");
        long h = board.getZobristHash();
        int turn = board.getTurnNumber();
        List<Move> moves = new ArrayList<>(board.getAllLegalMovesQuick());
        GameTree pvs = new GameTree(Player.WHITE, Player.BLACK, T14_BUDGET,
                42L, true, false, true);
        pvs.debugSearchScore(3, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertTrue("sanity: a re-search must have happened", pvs
                .getPvsResearched() > 0);
        assertEquals(h, board.getZobristHash());
        assertEquals(turn, board.getTurnNumber());
        assertEquals(Player.WHITE, board.getCurrentPlayer());
        assertSameLegalMoveSet(moves);

        // Aspiration retries: 'race' deepened to depth 4 (level 2 fails the
        // narrow window and retries from the pre-iteration snapshot).
        setT14Position("race");
        h = board.getZobristHash();
        turn = board.getTurnNumber();
        moves = new ArrayList<>(board.getAllLegalMovesQuick());
        GameTree asp = new GameTree(Player.WHITE, Player.BLACK, T14_BUDGET,
                42L, true, true, true);
        asp.aspirationScoreAtDepth(4);
        assertTrue("sanity: a retry must have happened", asp
                .getAspirationRetries() > 0);
        assertEquals(h, board.getZobristHash());
        assertEquals(turn, board.getTurnNumber());
        assertEquals(Player.WHITE, board.getCurrentPlayer());
        assertSameLegalMoveSet(moves);

        // The production path (iterative deepening under the wall clock,
        // all T13 features on) on a small position.
        setT14Position("tactical");
        h = board.getZobristHash();
        turn = board.getTurnNumber();
        moves = new ArrayList<>(board.getAllLegalMovesQuick());
        GameTree production = new GameTree(Player.WHITE, Player.BLACK);
        Move best = production.getBestMoveQuick();
        assertNotNull(best);
        assertEquals(h, board.getZobristHash());
        assertEquals(turn, board.getTurnNumber());
        assertEquals(Player.WHITE, board.getCurrentPlayer());
        assertSameLegalMoveSet(moves);
    }

    /**
     * Item 8 helper: the current legal-move set equals the recorded one
     * (order-insensitive; multiplicity checked via the size assertion).
     */
    private void assertSameLegalMoveSet(List<Move> before){
        assertEquals(new HashSet<>(before),
                new HashSet<>(board.getAllLegalMovesQuick()));
        assertEquals(before.size(), board.getAllLegalMovesQuick().size());
    }

    // ------------------------------------------------------------------
    // T14 test positions (geometries from AspirationPvsTest)
    // ------------------------------------------------------------------

    private void setT14Position(String which){
        resetBoard();
        switch (which){
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
            case "tacticalBlack":
                setT14Position("tactical");
                board.setCurrentPlayer(Player.BLACK);
                break;
            case "whiteForcedWin":
                board.putPiece(new Pusher(Player.WHITE), 5, 3);
                board.putPiece(new Pusher(Player.BLACK), 3, 6);
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
                setBoardToDefaultStart();
                break;
            default:
                throw new IllegalArgumentException(which);
        }
        board.updateAllLegaLMoves();
    }

    /** A fresh tree for the T14 score-invariance / node tests: max = side to
     *  move, TT on, aspiration and PVS off (a single full-window search),
     *  handoff per the parameter. */
    private GameTree t14Tree(String which, long seed, boolean handoff){
        Player max = board.getCurrentPlayer();
        return new GameTree(max, max.other(), T14_BUDGET, seed,
                true, false, false, handoff);
    }

    /**
     * A seeded random small position (2 Pushers + 1 Pushee per side) on
     * distinct squares in rows 1..6 (never a goal row: no terminal at the
     * root), random side to move. The legal-move lists are refreshed once
     * the position is complete (engine invariant 3).
     */
    private void buildRandomSmallPosition(long seed){
        Random random = new Random(seed);
        List<int[]> squares = new ArrayList<>();
        for (int r = 1; r <= 6; r++)
            for (int c = 0; c < GameBoard.GAME_BOARD_LENGTH; c++)
                squares.add(new int[]{r, c});
        Collections.shuffle(squares, random);

        board.putPiece(new Pusher(Player.WHITE), squares.get(0)[0],
                squares.get(0)[1]);
        board.putPiece(new Pusher(Player.WHITE), squares.get(1)[0],
                squares.get(1)[1]);
        board.putPiece(new Pushee(Player.WHITE), squares.get(2)[0],
                squares.get(2)[1]);
        board.putPiece(new Pusher(Player.BLACK), squares.get(3)[0],
                squares.get(3)[1]);
        board.putPiece(new Pusher(Player.BLACK), squares.get(4)[0],
                squares.get(4)[1]);
        board.putPiece(new Pushee(Player.BLACK), squares.get(5)[0],
                squares.get(5)[1]);
        board.setCurrentPlayer(random.nextBoolean()
                ? Player.WHITE : Player.BLACK);
        board.updateAllLegaLMoves();
    }

}
