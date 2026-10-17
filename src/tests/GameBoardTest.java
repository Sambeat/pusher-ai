package tests;

import client.GameStep;
import game.*;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;

/**
 * Unit tests for the GameBoard.java class.
 * @author Samuel Charade
 * @since 10/31/2015.
 */
public class GameBoardTest {

    private GameBoard board = GameBoard.getInstance();

    @Test
    public void testSquaresNames(){
        BoardSquare square = board.getSquare(2, 3);

        assertEquals("D3", square.toString());
    }

    @Test
    public void testPrint(){
        createWhitePushers();
        createWhitePushees();
        createBlackPushees();
        createBlackPushers();

        System.out.print(board);
    }

    @Test
    public void testCleanBoard(){
        createBlackPushees();

        board.reset();

        for (int i = 0; i < GameBoard.GAME_BOARD_LENGTH; i++){

            for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){

                assertTrue(board.isEmptyAt(i, j));
            }

        }
        assertEquals(0, board.getNumberOfPieces());
    }

    @Test
    public void testPutPiece(){
        Piece piece = new Pusher(Player.BLACK);

        board.putPiece(piece, 5, 5);

        assertTrue(board.pieceIsAlive(piece));
    }

    @Test
    public void testPutKill(){
        Piece toKill = new Pusher(Player.BLACK);

        board.putPiece(toKill, 3, 2);
        board.putPiece(new Pusher(Player.WHITE), 3, 2);

        assertFalse(board.pieceIsAlive(toKill));
    }

    @Test
    public void testMovePiece(){
        createWhitePushees();
        createWhitePushers();

        board.movePiece(1, 1, 2, 2);

        assertTrue(board.isEmptyAt(1, 1));
        assertFalse(board.isEmptyAt(2, 2));
    }

    @Test
    public void testMoveKill(){
        Pusher toKill = new Pusher(Player.BLACK);
        board.putPiece(toKill, 2, 2);
        board.putPiece(new Pusher(Player.WHITE), 1, 1);

        board.movePiece(1, 1, 2, 2);

        assertFalse(board.pieceIsAlive(toKill));
    }

    @Test
    public void testLegalMoves(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        Pusher pusher = new Pusher(Player.WHITE);
        Pushee pushee = new Pushee(Player.WHITE);
        ArrayList<Move> expectedLegalMoves = new ArrayList<>();
        expectedLegalMoves.add(new Move(board.getSquare(2, 0), board.getSquare(3, 0)));
        expectedLegalMoves.add(new Move(board.getSquare(3, 1), board.getSquare(4, 2)));


        board.putPiece(pusher, 2, 0);
        board.putPiece(pushee, 3, 1);
        board.updateAllLegaLMoves();

        assertEquals(expectedLegalMoves, board.getAllLegalMovesQuick());
    }

    @Test
    public void testLegalMovesBlack(){
        board.reset();
        board.setCurrentPlayer(Player.BLACK);
        Pusher pusher = new Pusher(Player.BLACK);
        Pushee pushee = new Pushee(Player.BLACK);
        ArrayList<Move> expectedLegalMoves = new ArrayList<>();
        expectedLegalMoves.add(new Move(board.getSquare(3, 0), board.getSquare(2, 0)));
        expectedLegalMoves.add(new Move(board.getSquare(2, 1), board.getSquare(1, 2)));

        board.putPiece(pusher, 3, 0);
        board.putPiece(pushee, 2, 1);
        board.updateAllLegaLMoves();

        assertEquals(expectedLegalMoves, board.getAllLegalMovesQuick());

    }


    @Test
    public void testCurrentPlayer(){
        //Self-contained setup: a movePiece() from an empty square is now a
        //fail-fast error, so the piece to move must exist.
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        board.putPiece(new Pusher(Player.WHITE), 1, 1);

        Player currentPlayer = board.getCurrentPlayer();
        Player expectedPlayer = currentPlayer.other();

        board.movePiece(1, 1, 2, 2);

        assertEquals(expectedPlayer, board.getCurrentPlayer());
    }

    @Test
    public void testUndo(){
        board.reset();
        createBlackPushers();
        createWhitePushers();
        createBlackPushees();
        createWhitePushees();
        Move toUndo = new Move(board.getSquare(1, 2), board.getSquare(2, 2));

        board.movePiece(toUndo);
        board.undo(toUndo);

        assertFalse(board.isEmptyAt(1, 2));
    }

    @Test
    public void testRevertState(){
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        GameStep previousState = board.createMemento();

        board.movePiece(3, 3, 4, 4);
        board.revertToState(previousState);

        assertTrue(board.isEmptyAt(4, 4));
        assertFalse(board.isEmptyAt(3, 3));
        assertEquals(1, board.getNumberOfPieces());
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

}
