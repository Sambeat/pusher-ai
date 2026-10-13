package client;

import game.GameBoard;
import game.Move;
import game.Player;

import java.util.*;

/**
 * Represents the game tree created by the minmax algorithm during play for
 * evaluating the best move to play.
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public class GameTree {

    public static final int MAX_TIME_MILLIS = 4950;

    private static GameBoard board = GameBoard.getInstance();

    private Player maxPlayer;
    private Player minPlayer;
    private Strategy strategy;

    private ArrayList<Move> iterativeMoveList = new ArrayList<>(10);

    //Starting time of tree generation
    private long start;

    //The depth at which to shuffle the moves. Usually the first level.
    private int shuffleDepth;


    public GameTree(Player maxPlayer, Player minPlayer){
        this.maxPlayer = maxPlayer;
        this.minPlayer = minPlayer;

        switch (maxPlayer){
            case WHITE:
                strategy = new WhiteStrategy();
                break;
            case BLACK:
                strategy = new BlackStrategy();
                break;
        }

    }



    /**
     * Uses iterative deepening to dynamically look ahead as much as possible
     * to get the best move to play.
     * @return The best move to play.
     */
    public Move getBestMoveQuick(){
        start = System.currentTimeMillis();
        int level = 1;
        GameStep snapshot = board.createMemento();

        Object[] moveInfo = {,};

        do {
            shuffleDepth = level;

            try {
                moveInfo = principalVariationAlphaBeta(level++, maxPlayer, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, iterativeMoveList);
            } catch (Exception e){
                //When minmax can't complete in time, it will leave the board
                //in a messed up state. We do a revert to its initial state then.
                board.revertToState(snapshot);
                break;
            }

            if ( (int)moveInfo[0] > 9000 || (int) moveInfo[0] < -9000)
                break;

            System.out.println(moveInfo[1] + ":" + moveInfo[0]);
        }
        while (System.currentTimeMillis() - start <= MAX_TIME_MILLIS);

        return (Move)moveInfo[1];
    }

    /**
     * Look ahead a specified number of move to decide the best move to play.
     * @param depth The number of move ahead to look.
     * @return The best move to play
     */
    public Move getBestMoveQuick(int depth){
        shuffleDepth = depth;
        start = System.currentTimeMillis();
        try {
            return (Move) principalVariationAlphaBeta(depth, maxPlayer, Integer.MIN_VALUE, Integer.MAX_VALUE, iterativeMoveList)[1];
        } catch (Exception e){
            return null;
        }
    }

    /**
     * Inspired by http://www3.ntu.edu.sg/home/ehchua/programming/java/javagame_tictactoe_ai.html
     * Minmax algorithm with alpha-beta pruning which evaluate every possible
     * move up to a certain number of turns. Alpha-beta pruning is aided by
     * a principal variation line of the best sequence of move found by the last
     * iteration of iterative deepening. This method returns an int
     * which indicate the best score found in this manner.
     * @param depth How many moves to look forward.
     * @param player The Player who would be playing the move.
     * @param alpha The maximum score found yet at that depth level.
     * @param beta The minimum score found yet at that depth level.
     * @param currentLine The current principalVariation line to fill with the
     *                    best found move.
     * @return An Object[] with the first element being the score and the second
     * element being the best Move.
     */
    private Object[] principalVariationAlphaBeta(int depth, Player player, int alpha, int beta, ArrayList<Move> currentLine) throws Exception {
        if (System.currentTimeMillis() - start > MAX_TIME_MILLIS){
            throw new Exception("Exceeded time");
        }


        Player winner = board.winner();

        if (winner == maxPlayer) {
            return new Object[]{Integer.MAX_VALUE - board.getTurnNumber(), null};
        }



        ArrayList<Move> moveLine = new ArrayList<>(10);
        int score;
        Move bestMove = null;

        if (depth == 0){
            score = GameAnalyzer.analyze(board, strategy);
            return new Object[] {score, null};
        }


        //We first try the previous best branch
        if (currentLine.size() != 0) {
            Move principalVariationMove = currentLine.remove(0);

            board.movePiece(principalVariationMove);

            if (player == maxPlayer) {
                score = (Integer) principalVariationAlphaBeta(depth - 1, minPlayer, alpha, beta, currentLine)[0];

                if (score > alpha) {
                    alpha = score;
                    bestMove = principalVariationMove;
                }

            } else {
                score = (Integer) principalVariationAlphaBeta(depth - 1, maxPlayer, alpha, beta, currentLine)[0];

                if (score < beta) {
                    beta = score;
                    bestMove = principalVariationMove;
                }

            }

            //Undo move (get previous node state)
            board.undo(principalVariationMove);
        }

        //Then we check all other move branches.
        ArrayList<Move> legalMoves = board.getAllLegalMovesQuick();

        for (Move move : legalMoves){

            board.movePiece(move);

            //Update score with alpha/beta pruning
            if (player == maxPlayer) {
                score = (Integer) principalVariationAlphaBeta(depth - 1, minPlayer, alpha, beta, moveLine)[0];

                if (score > alpha) {
                    alpha = score;
                    bestMove = move;
                }

            } else {
                score = (Integer) principalVariationAlphaBeta(depth - 1, maxPlayer, alpha, beta, moveLine)[0];

                if (score < beta) {
                    beta = score;
                    bestMove = move;
                }

            }

            //Undo move (get previous node state)
            board.undo(move);

            if (alpha >= beta){
                currentLine.add(0, move);
                currentLine.addAll(moveLine);
                break;
            }
        }

        return new Object[]{
                player == maxPlayer ?
                        new Integer(alpha) : new Integer(beta),
                bestMove};

    }

}
