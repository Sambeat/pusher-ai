package tests;

import game.*;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
/**
 * Unit tests for the Pushee.java class.
 * @author Samuel Charade
 * @since 10/31/2015.
 */
public class PusheeTest {

    private GameBoard board = GameBoard.getInstance();

    @Test
    public void testToString(){
        Pushee whitePushee = new Pushee(Player.WHITE);
        Pushee blackPushee = new Pushee(Player.BLACK);

        assertEquals("o", whitePushee.toString());
        assertEquals("x", blackPushee.toString());
    }

    @Test
    public void testLegalMovesWhite(){
        Pushee pushee = new Pushee(Player.WHITE);
        BoardSquare pusheePosition = board.getSquare(3, 3);
        Move[] expectedLegalMoves = {
                new Move(pusheePosition, board.getSquare(4, 4))
        };

        board.putPiece(pushee, pusheePosition);
        board.putPiece(new Pusher(Player.WHITE), 2, 2);
        board.putPiece(new Pusher(Player.BLACK), 2, 4);
        board.putPiece(new Pushee(Player.WHITE), 2, 3);
        board.updateAllLegaLMoves();

        assertArrayEquals(expectedLegalMoves, pushee.getLegalMovesQuick().toArray());
    }

    @Test
    public void testLegalMovesBlack(){
        Pushee pushee = new Pushee(Player.BLACK);
        BoardSquare pusheePosition = board.getSquare(3, 3);
        Move[] expectedLegalMoves = {
                new Move(pusheePosition, board.getSquare(2, 2))
        };

        board.putPiece(pushee, pusheePosition);
        board.putPiece(new Pusher(Player.BLACK), 4, 4);
        board.putPiece(new Pusher(Player.WHITE), 4, 2);
        board.putPiece(new Pushee(Player.BLACK), 4, 3);
        board.updateAllLegaLMoves();

        assertArrayEquals(expectedLegalMoves, pushee.getLegalMovesQuick().toArray());
    }

}
