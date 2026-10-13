package tests;

import client.GameTree;
import game.*;
import org.junit.*;
import static org.junit.Assert.*;

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

}
