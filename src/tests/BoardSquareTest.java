package tests;

import game.*;

import static org.junit.Assert.*;
import org.junit.*;


/**
 * Unit tests for the BoardSquare.java class.
 * @author Samuel Charade
 * @since 10/31/2015.
 */
public class BoardSquareTest {

    private final static int TEST_ROW = 4;
    private final static Columns TEST_COLUMN = Columns.C;
    private final static Piece TEST_PIECE = new Pusher(Player.BLACK);

    private BoardSquare boardSquare;
    @Before
    public void before(){
        GameBoard theBoard = GameBoard.getInstance();
        boardSquare = new BoardSquare(TEST_PIECE, TEST_ROW, TEST_COLUMN);
        theBoard.putPiece(TEST_PIECE, boardSquare);
    }

    @Test
    public void testToString(){
        assertEquals("C5", boardSquare.toString());
    }

    @Test
    public void testIsPushee(){
        boardSquare.setPiece(new Pushee(Player.BLACK));

        assertTrue(boardSquare.hasPushee());
        assertFalse(boardSquare.hasPusher());
    }

    @Test
    public void testIsPusher(){
        boardSquare.setPiece(new Pusher(Player.BLACK));

        assertTrue(boardSquare.hasPusher());
        assertFalse(boardSquare.hasPushee());
    }

    @Test
    public void testIsEmpty(){
        boardSquare.setPiece(null);

        assertTrue(boardSquare.isEmpty());
    }

    @Test
    public void testEmptySquare(){
        boardSquare.emptySquare();

        assertTrue(boardSquare.isEmpty());
    }


}
