package tests;

import client.GameTree;
import game.*;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * T3: quiescence search.
 *
 * Without quiescence a depth-limited search scores the horizon position
 * statically and blunders when the depth limit lands just before a capture.
 *
 * The tactical tests below FAIL on the pre-quiescence engine:
 *   - testHangingPiece_prefersSafeMove: a depth-1 search sees the bait
 *     capture's material gain at the horizon but not the recapture one
 *     ply later.
 *   - testWinningCaptureFoundByQSearch: the only non-losing move is a
 *     capture whose safety (no recapture) is one ply beyond the horizon.
 *
 * @since T3
 */
public class QuiescenceTest {

    private final GameBoard board = GameBoard.getInstance();

    @After
    public void cleanBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    /**
     * White to move, depth-1 search.
     *
     *   W1 (pusher) 3,0    E1 (pushee) 4,1    B1 (pusher) 5,2
     *
     * Candidate moves:
     *   A: W1 3,0 -> 4,1  captures E1 (material gain at the horizon) but
     *      lands on a square B1 recaptures from (B1 attacks 4,1 and 4,3).
     *      W1 is White's only piece, so the recapture leaves White without
     *      any legal move: a loss.
     *   B: W1 3,0 -> 4,0  quiet and safe (B1 does not attack 4,0).
     *
     * Statically A scores 5 points above B (E1's 5 enemy points disappear
     * and A's pusher has one extra legal destination), so without
     * quiescence the engine plays the losing capture. With quiescence the
     * recapture is seen and B is picked.
     */
    @Test
    public void testHangingPiece_prefersSafeMove(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);

        board.putPiece(new Pusher(Player.WHITE), 3, 0);
        board.putPiece(new Pushee(Player.BLACK), 4, 1);
        board.putPiece(new Pusher(Player.BLACK), 5, 2);
        board.updateAllLegaLMoves();

        Move expected = new Move(board.getSquare(3, 0), board.getSquare(4, 0));

        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);
        Move best = gameTree.getBestMoveQuick(1);

        assertEquals("Depth-1 search must not hang the pusher to an "
                + "immediate recapture (q-search sees it)", expected, best);
    }

    /**
     * White to move, depth-1 search. W1 is White's only piece, so any
     * recapture of W1 leaves White without a legal move: a loss.
     *
     *   W1 (pusher) 0,3    E1 (pushee) 1,2    B1 (pusher) 1,4
     *                      B3 (pusher) 2,4    B2 (pusher) 2,5
     *
     * Candidate moves:
     *   A1: W1 0,3 -> 1,2  captures E1 and lands UNSCATHABLE: B1 attacks
     *      row 0, B2 attacks 1,4/1,6, B3 attacks 1,3/1,5. The only safe
     *      move.
     *   A2: W1 0,3 -> 1,4  captures B1 (the bigger material gain at the
     *      horizon) but B2 recaptures from 2,5.
     *   C:  W1 0,3 -> 1,3  quiet, B3 recaptures from 2,4.
     *
     * A plain depth-0 (stand-pat) view ranks A2 first, so without
     * quiescence the engine plays the losing capture. With quiescence A1
     * is picked: the capture whose safety is one ply beyond the horizon.
     */
    @Test
    public void testWinningCaptureFoundByQSearch(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);

        board.putPiece(new Pusher(Player.WHITE), 0, 3);
        board.putPiece(new Pushee(Player.BLACK), 1, 2);
        board.putPiece(new Pusher(Player.BLACK), 1, 4);
        board.putPiece(new Pusher(Player.BLACK), 2, 4);
        board.putPiece(new Pusher(Player.BLACK), 2, 5);
        board.updateAllLegaLMoves();

        Move expected = new Move(board.getSquare(0, 3), board.getSquare(1, 2));

        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);
        Move best = gameTree.getBestMoveQuick(1);

        assertEquals("The only non-losing move is a capture whose safety is "
                + "one ply beyond the horizon; q-search must find it", expected, best);
    }

    /**
     * Quiescence must not blow the time budget: on the full 32-piece
     * starting position the iterative deepening search still finishes
     * within MAX_TIME_MILLIS plus a small slack.
     */
    @Test
    public void testFullSearchStillWithinBudget(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        setBoardToDefaultStart();

        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);

        long before = System.currentTimeMillis();
        gameTree.getBestMoveQuick();
        long elapsed = System.currentTimeMillis() - before;

        assertTrue("getBestMoveQuick took " + elapsed + " ms, expected <= "
                + (GameTree.MAX_TIME_MILLIS + 500),
                elapsed <= GameTree.MAX_TIME_MILLIS + 500);
    }

    /** 32-piece position, same layout as GameTreeTest.setBoardToDefaultStart. */
    private void setBoardToDefaultStart(){
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            board.putPiece(new Pusher(Player.WHITE), 0, j);
            board.putPiece(new Pushee(Player.WHITE), 1, j);
            board.putPiece(new Pusher(Player.BLACK), 7, j);
            board.putPiece(new Pushee(Player.BLACK), 6, j);
        }
    }
}
