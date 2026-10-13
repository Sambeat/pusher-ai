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

    /**
     * Score margin separating a win by reaching the goal row from a win by
     * leaving the opponent without any legal move. Both are wins, but the
     * goal row is the actual objective of the game, so at equal depth a goal
     * row win must always rank above a stalemate win (and symmetrically a
     * stalemate loss ranks above a goal row loss).
     */
    public static final int STALEMATE_MARGIN = 1000;

    private static GameBoard board = GameBoard.getInstance();

    private Player maxPlayer;
    private Player minPlayer;
    private Strategy strategy;

    //Starting time of tree generation
    private long start;

    // Used to randomize the move order on each search (tie-breaking and
    // move ordering). A fresh sequence is created for every getBestMoveQuick()
    // call so repeated searches on the same position are not deterministic.
    private Random random;


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
     * @return The best move to play, or null when the side to move has no
     *         legal move (e.g. the game is already lost).
     */
    public Move getBestMoveQuick(){
        start = System.currentTimeMillis();
        random = new Random();
        int level = 1;
        GameStep snapshot = board.createMemento();

        Object[] moveInfo = null;

        do {
            try {
                moveInfo = alphaBeta(level++, maxPlayer, Integer.MIN_VALUE,
                        Integer.MAX_VALUE);
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

        return moveInfo == null ? null : (Move)moveInfo[1];
    }

    /**
     * Look ahead a specified number of move to decide the best move to play.
     * @param depth The number of move ahead to look.
     * @return The best Move, or null when the side to move has no legal move.
     */
    public Move getBestMoveQuick(int depth){
        start = System.currentTimeMillis();
        random = new Random();
        try {
            return (Move) alphaBeta(depth, maxPlayer, Integer.MIN_VALUE, Integer.MAX_VALUE)[1];
        } catch (Exception e){
            return null;
        }
    }

    /**
     * Minmax algorithm with alpha-beta pruning which evaluates every possible
     * move up to a certain number of turns.
     *
     * Terminal positions:
     *  - the max player reached its goal row: huge positive score (earlier wins
     *    are preferred through the turn number);
     *  - the min player reached its goal row: huge negative score. The original
     *    code only detected max player wins, so lost lines were evaluated with
     *    the static heuristic and the AI never saw the opponent's one-move wins;
     *  - the side to move has no legal move: it loses, as in checkmate.
     *
     * Moves are explored in random order (a fresh sequence per search) so ties
     * are not always resolved in favor of the first piece of the board setup.
     *
     * Returns an Object[] with the first element being the score and the second
     * element being the best Move.
     * @param depth How many moves to look forward.
     * @param player The Player who would be playing the move.
     * @param alpha The maximum score found yet at that depth level.
     * @param beta The minimum score found yet at that depth level.
     * @return An Object[] with the first element being the score and the second
     * element being the best Move.
     */
    private Object[] alphaBeta(int depth, Player player, int alpha, int beta) throws Exception {
        if (System.currentTimeMillis() - start > MAX_TIME_MILLIS){
            throw new Exception("Exceeded time");
        }

        Player winner = board.winner();

        if (winner == maxPlayer) {
            return new Object[]{Integer.MAX_VALUE - board.getTurnNumber(), null};
        }

        if (winner == minPlayer) {
            return new Object[]{Integer.MIN_VALUE + board.getTurnNumber(), null};
        }

        ArrayList<Move> legalMoves = new ArrayList<>(board.getAllLegalMovesQuick());

        if (legalMoves.isEmpty()){
            //The player to move has no legal move: it loses, as in checkmate.
            //Returning the passed alpha/beta here (as the original code did)
            //produced garbage scores that depend on the search window.
            //The margin keeps this terminal strictly below (above) the goal
            //row terminal at the same depth, so the AI prefers the actual
            //objective to stalemating the opponent.
            return new Object[]{player == maxPlayer
                    ? Integer.MIN_VALUE + board.getTurnNumber() + STALEMATE_MARGIN
                    : Integer.MAX_VALUE - board.getTurnNumber() - STALEMATE_MARGIN, null};
        }

        int score;
        Move bestMove = null;

        if (depth == 0){
            score = GameAnalyzer.analyze(board, strategy);
            return new Object[] {score, null};
        }

        Collections.shuffle(legalMoves, random);

        for (Move move : legalMoves){

            board.movePiece(move);

            //Update score with alpha/beta pruning
            if (player == maxPlayer) {
                score = (Integer) alphaBeta(depth - 1, minPlayer, alpha, beta)[0];

                if (score > alpha) {
                    alpha = score;
                    bestMove = move;
                }

            } else {
                score = (Integer) alphaBeta(depth - 1, maxPlayer, alpha, beta)[0];

                if (score < beta) {
                    beta = score;
                    bestMove = move;
                }
            }

            //Undo move (get previous node state)
            board.undo(move);

            if (alpha >= beta){
                break;
            }
        }

        return new Object[]{
                player == maxPlayer ?
                        new Integer(alpha) : new Integer(beta),
                bestMove};

    }

}
