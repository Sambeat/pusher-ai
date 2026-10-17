package tests;

import client.GameTree;
import game.*;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;

/**
 * JUnit test for Pusher.java
 * @author Samuel Charade
 * @since 10/31/2015.
 */
public class PusherTest {

    GameBoard board = GameBoard.getInstance();

    @Test
    public void testToString(){
        Pusher whitePusher = new Pusher(Player.WHITE);
        Pusher blackPusher = new Pusher(Player.BLACK);

        assertEquals("O", whitePusher.toString());
        assertEquals("X", blackPusher.toString());
    }

    @Test
    public void testLegalMoveWhiteSpecialCase(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
        Piece toTest = new Pusher(Player.WHITE);
        createWhitePushers();
        createWhitePushees();
        board.putPiece(toTest, 0, 0);

        Move[] expectedLegalMoves = {};

        assertArrayEquals(expectedLegalMoves, toTest.getLegalMovesQuick().toArray());
    }

    @Test
    public void testLegalMoveWhiteEdgeCase(){
        board.reset();
        Piece toTest = new Pusher(Player.WHITE);
        board.putPiece(toTest, 0, 7);
        board.putPiece(new Pushee(Player.WHITE), 1, 7);
        Move[] expectedLegalMoves = {
                new Move(board.getSquare(0, 7), board.getSquare(1, 6)),
        };
        toTest.updateLegalMoves();

        GameTree gameTree = new GameTree(Player.WHITE, Player.BLACK);
        gameTree.getBestMoveQuick();

        assertArrayEquals(expectedLegalMoves, toTest.getLegalMovesQuick().toArray());
    }

    @Test
    public void testLegalMoveWhite(){
        Pusher toTest = new Pusher(Player.WHITE);
        BoardSquare pusherPosition = board.getSquare(3, 3);
        Move[] expectedLegalMoves = {
                new Move(pusherPosition, board.getSquare(4, 2)),
                new Move(pusherPosition, board.getSquare(4, 3))
        };

        board.putPiece(toTest, pusherPosition);
        putObstaclesForPiece(toTest);
        toTest.updateLegalMoves();


        assertArrayEquals(expectedLegalMoves, toTest.getLegalMovesQuick().toArray());
    }

    @Test
    public void testLegalMoveBlack(){
        Pusher toTest = new Pusher(Player.BLACK);
        BoardSquare pusherPosition = board.getSquare(3, 3);
        Move[] expectedLegalMoves = {
                new Move(pusherPosition, board.getSquare(2, 2)),
                new Move(pusherPosition, board.getSquare(2, 3))
        };

        board.putPiece(toTest, pusherPosition);
        putObstaclesForPiece(toTest);
        toTest.updateLegalMoves();


        assertArrayEquals(expectedLegalMoves, toTest.getLegalMovesQuick().toArray());
    }

    /**
     * The piece must have a position assigned before calling this method.
     * @param pusher The piece to put obstacles in front of.
     */
    private void putObstaclesForPiece(Pusher pusher){

        Pusher obstacle = new Pusher(Player.WHITE);
        Pusher fodder = new Pusher(Player.BLACK);

        board.putPiece(obstacle,
                pusher.getRow() + pusher.getDirection(),
                pusher.getColumn() + pusher.getDirection());
        board.putPiece(fodder,
                pusher.getRow() + pusher.getDirection(),
                pusher.getColumn() - pusher.getDirection());
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
