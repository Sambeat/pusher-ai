package tests;

import client.*;
import game.*;

import static org.junit.Assert.*;
import org.junit.*;

/**
 * Tests that demonstrate the algorithmic bugs found in review.
 *
 * Against the ORIGINAL code the following tests FAIL (proving the bugs):
 *   - testMinPlayerWin_alreadyLost_returnsNull        (no terminal check for the min player)
 *   - testMinPlayerWin_preventsOpponentOneMoveWin     (same bug, in a real game line)
 *   - testWhiteStrategy_pusherOnHFileValued           (operator precedence bug)
 *   - testBlackStrategy_pusherOnHFileValued           (operator precedence bug)
 *   - testBlackStrategy_advancedPieceWorthMoreThanHomePiece (row term has wrong sign for Black)
 *   - testWhiteStrategy_mobilityIsOwnPiecesOnly       (mobility counted for side to move, not White)
 *   - testMementoRestoresTurnNumber                   (turn number not part of the memento)
 *   - testMovePieceEmptyOrigin_doesNotCorruptBoard    (movePiece silently kills the destination piece)
 *   - testNestedCaptureSameSquarePair_restoresOuterVictim (capture log lost victims of nested
 *                                                       same-square-pair captures, T2)
 *   - testRevertToState_refreshesLegalMoveLists       (revert left cached move lists stale, T2)
 *   - testSearchLeavesBoardUntouched                  (search corruption symptom, T2)
 *
 * testWhiteStrategy_edgeFilePusheeWorthZero and testNoLegalMovesReturnsNull are
 * characterization tests: they pin intended engine behavior (they pass before and after).
 *
 * @since bug review
 */
public class BugProofTest {

    private final GameBoard board = GameBoard.getInstance();
    private final GameTree whiteTree = new GameTree(Player.WHITE, Player.BLACK);
    private final GameTree blackTree = new GameTree(Player.BLACK, Player.WHITE);

    @After
    public void cleanBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void setUpWhiteToMove(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void setUpBlackToMove(){
        board.reset();
        board.setCurrentPlayer(Player.BLACK);
    }

    // ---------------------------------------------------------------------------------
    // Bug: the search only detects wins for the MAX player. A win for the MIN player
    // (opponent reaching the goal row) is never cut off, so the AI misses the
    // opponent's one-move wins and evaluates lost lines with the static heuristic.
    // ---------------------------------------------------------------------------------

    /**
     * Black to move, but White is already on its goal row: Black has already lost.
     * There is nothing to compute, the engine must return null.
     * The original code keeps searching and returns a move as if the game were alive.
     */
    @Test
    public void testMinPlayerWin_alreadyLost_returnsNull(){
        setUpBlackToMove();
        board.putPiece(new Pusher(Player.WHITE), 7, 3); // White already on its goal row
        board.putPiece(new Pusher(Player.BLACK), 2, 4);
        board.updateAllLegaLMoves();

        assertNull(blackTree.getBestMoveQuick(1));
    }

    /**
     * Black to move. White threatens an immediate win: the White Pushee on (6,3) can be
     * pushed onto its goal row (7,3) - the Pusher on (5,3) sits right behind it.
     *
     * The saving move is for the Black Pusher on (7,2) to capture the threatening Pushee
     * at (6,3). The Black Pusher on (4,1) can instead capture a far-away White Pusher on
     * (3,0): that looks better on the static material heuristic, but it leaves the
     * threat untouched and loses next move.
     *
     * A search that recognizes the min player's win must pick the saving move.
     * The original code picks the material-gaining move (B5A4) and loses.
     */
    @Test
    public void testMinPlayerWin_preventsOpponentOneMoveWin(){
        setUpBlackToMove();
        board.putPiece(new Pushee(Player.WHITE), 6, 3);  // one row away from White's goal row
        board.putPiece(new Pusher(Player.WHITE), 5, 3);  // pushes it (mirror square behind)
        board.putPiece(new Pusher(Player.WHITE), 3, 0);  // far-away White material
        board.putPiece(new Pusher(Player.BLACK), 7, 2);  // can capture the threatening Pushee
        board.putPiece(new Pusher(Player.BLACK), 4, 1);  // can capture the far-away Pusher
        board.updateAllLegaLMoves();

        Move savingMove = new Move(board.getSquare(7, 2), board.getSquare(6, 3));
        assertEquals(savingMove, blackTree.getBestMoveQuick(2));
    }

    // ---------------------------------------------------------------------------------
    // Bug: "value == 3 && col == A || col == H" parses as
    // "(value == 3 && col == A) || col == H", so ANY of your own pieces on file H
    // (including a Pusher) is valued at 0 instead of its real value.
    // ---------------------------------------------------------------------------------

    /**
     * A lone White Pusher on file H must be worth its full value (7), plus its row
     * advancement and mobility. The original code values it at 0 (+ mobility).
     */
    @Test
    public void testWhiteStrategy_pusherOnHFileValued(){
        setUpWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 7); // row 3, file H
        board.updateAllLegaLMoves();

        // value 7 + advancement (row 3) + mobility (2 moves / 2) = 11
        assertEquals(11, new PlayerStrategy(Player.WHITE).analyze());
    }

    /**
     * Characterization of the intended rule behind the zero-value branch: a PUSHEE
     * (value 3) on an edge file (A or H) is worth nothing.
     */
    @Test
    public void testWhiteStrategy_edgeFilePusheeWorthZero(){
        setUpWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 0); // row 3, file A, no pusher behind
        board.updateAllLegaLMoves();

        assertEquals(0, new PlayerStrategy(Player.WHITE).analyze());
    }

    /**
     * Same precedence bug on the Black side: a lone Black Pusher on file H must be
     * worth its full value plus advancement, not 0.
     */
    @Test
    public void testBlackStrategy_pusherOnHFileValued(){
        setUpBlackToMove();
        board.putPiece(new Pusher(Player.BLACK), 3, 7); // row 3, file H
        board.updateAllLegaLMoves();

        // value 7 + advancement (7 - 3 = 4 rows covered toward the goal)
        // + mobility (2 moves / 2) = 12 (the mobility term is part of the
        // unified T8 evaluation; enemy pieces are absent, so no premium)
        assertEquals(12, new PlayerStrategy(Player.BLACK).analyze());
    }

    // ---------------------------------------------------------------------------------
    // Bug: Black advances toward row 0 (decreasing rows), but BlackStrategy adds
    // piece.getRow(), which rewards pieces that stay near their own home row and
    // punishes pieces that make progress.
    // ---------------------------------------------------------------------------------

    /**
     * A Black Pusher that has advanced (row 1) must be worth more than the same
     * Pusher sitting near home (row 6). The original code has it backwards.
     */
    @Test
    public void testBlackStrategy_advancedPieceWorthMoreThanHomePiece(){
        int advanced = evalBlackPusherAt(1, 4);
        int home = evalBlackPusherAt(6, 4);

        assertTrue("advanced piece (" + advanced + ") must be worth more than home piece ("
                + home + ")", advanced > home);
    }

    private int evalBlackPusherAt(int row, int col){
        setUpBlackToMove();
        board.putPiece(new Pusher(Player.BLACK), row, col);
        board.updateAllLegaLMoves();
        return new PlayerStrategy(Player.BLACK).analyze();
    }

    // ---------------------------------------------------------------------------------
    // Bug: WhiteStrategy adds board.getAllLegalMovesQuick().size() / 2 to White's
    // score, but getAllLegalMovesQuick() returns the SIDE TO MOVE's moves. At leaves
    // where it is Black's turn, Black's mobility is added to WHITE's score.
    // ---------------------------------------------------------------------------------

    /**
     * It is Black's turn and Black has MORE mobility (7 moves) than White (3 moves).
     * White's score must include only White's own mobility:
     * material +10 (White pusher: 7 + advancement row 3) - (9 + 10 + 10)
     * (Black: value + advancement + the shared defensive +2, T8 unified)
     * = -19, own mobility 3/2 = +1 -> -18.
     * If the mobility were (as in the original bug) counted for the side to
     * move - Black's 7 moves / 2 = 3 - this position would score -16 instead
     * of -18, so the pin still detects the regression.
     */
    @Test
    public void testWhiteStrategy_mobilityIsOwnPiecesOnly(){
        setUpBlackToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.BLACK), 7, 3);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 4);
        board.updateAllLegaLMoves();

        assertEquals(-18, new PlayerStrategy(Player.WHITE).analyze());
    }

    // ---------------------------------------------------------------------------------
    // Bug: GameStep (the memento used to revert the board after a search timeout)
    // does not capture the turn number. Every timed-out search leaves the applied-but-
    // not-undown half-moves permanently added to turnNumber.
    // ---------------------------------------------------------------------------------

    @Test
    public void testMementoRestoresTurnNumber(){
        setUpWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        board.updateAllLegaLMoves();

        int before = board.getTurnNumber();
        GameStep snapshot = board.createMemento();

        board.movePiece(3, 3, 4, 3); // increments the turn number
        board.revertToState(snapshot);

        assertEquals(before, board.getTurnNumber());
    }

    // ---------------------------------------------------------------------------------
    // Characterization: when the max player has no legal move at all the engine must
    // return null (rather than crash the application with an NPE in communicateMove).
    // ---------------------------------------------------------------------------------

    @Test
    public void testNoLegalMovesReturnsNull(){
        setUpWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 3); // a Pushee with no Pusher behind it
        board.updateAllLegaLMoves();

        assertTrue(board.getAllLegalMovesQuick().isEmpty());
        assertNull(whiteTree.getBestMoveQuick(1));
    }

    // ---------------------------------------------------------------------------------
    // Bug: movePiece() performs no validity check. Moving from an empty square
    // silently "captures" (kills) the piece on the destination square and puts a
    // null piece on the board. The engine must fail fast instead.
    // ---------------------------------------------------------------------------------

    @Test
    public void testMovePieceEmptyOrigin_doesNotCorruptBoard(){
        setUpWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 4, 3); // a victim on the destination square
        board.updateAllLegaLMoves();

        try {
            board.movePiece(new Move(board.getSquare(3, 3), board.getSquare(4, 3)));
            fail("movePiece must refuse to move from an empty square");
        } catch (IllegalStateException expected){
            // the engine must fail fast ...
        }

        // ... and must not have killed the piece on the destination square
        assertTrue("victim on D4 was corrupted by the illegal move",
                board.getPiece(4, 3) instanceof Pusher);
    }

    // ---------------------------------------------------------------------------------
    // Bug (found by T2): the capture log was a HashMap<Move, Piece> keyed by the move's
    // square pair. Two nested captures on the SAME square pair shared one key: the inner
    // capture's victim overwrote the outer's record, and when the outer capture was
    // undone its victim was never restored. The piece silently vanished from the board
    // and every later search on the drifted state played phantom moves (the client
    // crashed with "movePiece: no piece at origin" or answered from a corrupted tree).
    // The line below replays that pattern: E5F6 captures a Black Pusher, then (after
    // the origin is re-occupied and the destination re-captured) E5F6 captures again.
    // ---------------------------------------------------------------------------------

    @Test
    public void testNestedCaptureSameSquarePair_restoresOuterVictim(){
        setUpWhiteToMove();
        Pusher white1 = new Pusher(Player.WHITE);
        Pusher white2 = new Pusher(Player.WHITE);
        Pusher black1 = new Pusher(Player.BLACK);
        Pusher victim = new Pusher(Player.BLACK);

        board.putPiece(white1, 4, 4); //E5
        board.putPiece(victim, 5, 5); //F6
        board.putPiece(white2, 3, 5); //F4
        board.putPiece(black1, 7, 3); //D8
        board.updateAllLegaLMoves();

        Move e5f6 = new Move(board.getSquare(4, 4), board.getSquare(5, 5));
        board.movePiece(e5f6);                                //W: E5F6 captures the victim
        board.movePiece(new Move(board.getSquare(7, 3), board.getSquare(6, 4))); //B: D8E7
        board.movePiece(new Move(board.getSquare(3, 5), board.getSquare(4, 4))); //W: F4E5
        board.movePiece(new Move(board.getSquare(6, 4), board.getSquare(5, 5))); //B: E7F6 captures white1
        board.movePiece(e5f6);                                //W: E5F6 captures black1 (same square pair as the first capture!)

        //Undo in reverse order (as the search does).
        board.undo(e5f6);
        board.undo(new Move(board.getSquare(6, 4), board.getSquare(5, 5)));
        board.undo(new Move(board.getSquare(3, 5), board.getSquare(4, 4)));
        board.undo(new Move(board.getSquare(7, 3), board.getSquare(6, 4)));
        board.undo(e5f6);

        assertEquals("a capture victim was lost when nested captures shared a square pair",
                4, board.getNumberOfPieces());
        assertNotNull("the outer capture's victim must be restored on F6",
                board.getPiece(5, 5));
        assertEquals(Player.BLACK, board.getPiece(5, 5).getOwner());
        assertNotNull(board.getPiece(4, 4));
        assertNotNull(board.getPiece(3, 5));
        assertNotNull(board.getPiece(7, 3));
        assertEquals(0, board.getTurnNumber());
    }

    // ---------------------------------------------------------------------------------
    // Bug (found by T2): revertToState() placed the snapshot pieces row by row and
    // each placement recomputed that piece's cached legal moves against a board that
    // was only PARTIALLY restored. Pieces whose forward neighborhood was restored
    // later kept lists computed for the wrong position; the next search then played
    // phantom moves from those stale lists.
    // ---------------------------------------------------------------------------------

    @Test
    public void testRevertToState_refreshesLegalMoveLists(){
        setUpWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 3); //D4
        board.putPiece(new Pusher(Player.WHITE), 4, 4); //E5: a friendly blocker
        board.updateAllLegaLMoves();

        GameStep snapshot = board.createMemento();
        board.movePiece(4, 4, 5, 5); //the blocker moves away
        board.revertToState(snapshot); //... and must be back on E5

        assertNotNull("the blocker must be restored on E5", board.getPiece(4, 4));
        Piece pusher = board.getPiece(3, 3);
        assertNotNull(pusher);
        for (Move m : pusher.getLegalMovesQuick()) {
            assertFalse("E5 holds a friendly piece after the revert: it must not be a cached legal destination of D4",
                    m.destination() == board.getSquare(4, 4));
        }
    }

    // ---------------------------------------------------------------------------------
    // Symptom guard (found by T2): with the seed below, the search used to hit the
    // nested-capture corruption above (a lost Black Pushee), crash mid-search, and
    // leave the singleton board in a wrong state (missing piece, stale lists) that
    // poisoned the client's next move. A search must leave the board exactly as it
    // found it, whether it completes or aborts on the time limit.
    // ---------------------------------------------------------------------------------

    @Test
    public void testSearchLeavesBoardUntouched(){
        setUpWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.WHITE), 3, 5);
        board.putPiece(new Pushee(Player.WHITE), 2, 4);
        board.putPiece(new Pusher(Player.BLACK), 7, 3);
        board.putPiece(new Pusher(Player.BLACK), 7, 5);
        board.putPiece(new Pushee(Player.BLACK), 6, 4);
        board.updateAllLegaLMoves();

        GameTree seeded = new GameTree(Player.WHITE, Player.BLACK, 4950, 1L);
        seeded.getBestMoveQuick();

        assertEquals("the search lost a piece", 6, board.getNumberOfPieces());
        Piece pushee = board.getPiece(6, 4);
        assertNotNull("the Black Pushee on E7 vanished during the search", pushee);
        assertEquals(Player.BLACK, pushee.getOwner());
        assertEquals(0, board.getTurnNumber());
        assertEquals(Player.WHITE, board.getCurrentPlayer());
    }
}
