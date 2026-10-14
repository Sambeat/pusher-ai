package client;

import game.GameBoard;
import game.Move;
import game.Piece;
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

    /**
     * Quiescence extension bound (T3): a q-node deeper than this many
     * capture plies returns its stand-pat score. Captures are rare in this
     * game (average capture branching ~1-2), so the bounded extension is
     * cheap while killing the horizon effect on the lines that matter.
     */
    private static final int Q_MAX_DEPTH = 8;

    private static GameBoard board = GameBoard.getInstance();

    private Player maxPlayer;
    private Player minPlayer;
    private Strategy strategy;

    //Starting time of tree generation
    private long start;

    // Per-move time budget for the iterative deepening search.
    private long timeBudgetMillis = MAX_TIME_MILLIS;

    // Non-null when the move-order shuffling must be reproducible (T7 bench).
    // Each search gets a Random derived from this seed plus the search count,
    // so consecutive searches still shuffle differently while a whole run
    // stays replayable with the same seed.
    private Long randomSeed;
    private long searchCount;

    // Legacy per-level logging (silenced by the bench constructor).
    private boolean verbose = true;

    // Deepest level fully completed by the last getBestMoveQuick() call
    // (0 when even level 1 did not finish).
    private int lastCompletedLevel;

    // Used to randomize the move order on each search (tie-breaking and
    // move ordering). A fresh sequence is created for every getBestMoveQuick()
    // call so repeated searches on the same position are not deterministic
    // (unless a seed was given, see the constructor below).
    private Random random;


    public GameTree(Player maxPlayer, Player minPlayer){
        this(maxPlayer, minPlayer, MAX_TIME_MILLIS, null, true);
    }

    /**
     * Construct a tree with an explicit per-move time budget and an optional
     * seed that makes the move-order shuffling reproducible (T7 bench).
     * A null seed keeps the legacy non-deterministic behavior. This
     * constructor silences the per-level logging of the default one.
     * @param maxPlayer The player the search maximizes for.
     * @param minPlayer The player the search minimizes for.
     * @param timeBudgetMillis The per-move search budget in milliseconds.
     * @param seed Seed for the move-order shuffling, or null for
     *             non-deterministic play.
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis, Long seed){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false);
    }

    private GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                     Long randomSeed, boolean verbose){
        this.maxPlayer = maxPlayer;
        this.minPlayer = minPlayer;
        this.timeBudgetMillis = timeBudgetMillis;
        this.randomSeed = randomSeed;
        this.verbose = verbose;

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
     * Fresh Random for a new search: unseeded in legacy mode, otherwise
     * derived from the seed and the search count. The golden-ratio constant
     * mixes consecutive counters so successive searches decorrelate.
     */
    private Random nextRandom(){
        if (randomSeed == null)
            return new Random();
        return new Random(randomSeed + 0x9E3779B97F4A7C15L * searchCount++);
    }

    /**
     * The deepest search level fully completed by the last
     * getBestMoveQuick() call. 0 when the first level did not complete.
     * Read by the T7 bench to report the max depth reached per move.
     */
    public int getLastCompletedLevel(){
        return lastCompletedLevel;
    }



    /**
     * Uses iterative deepening to dynamically look ahead as much as possible
     * to get the best move to play.
     * @return The best move to play, or null when the side to move has no
     *         legal move (e.g. the game is already lost).
     */
    public Move getBestMoveQuick(){
        start = System.currentTimeMillis();
        random = nextRandom();
        int level = 1;
        lastCompletedLevel = 0;
        GameStep snapshot = board.createMemento();

        Object[] moveInfo = null;

        do {
            try {
                moveInfo = alphaBeta(level, maxPlayer, Integer.MIN_VALUE,
                        Integer.MAX_VALUE);
                lastCompletedLevel = level;
                level++;
            } catch (Exception e){
                //When minmax can't complete in time, it will leave the board
                //in a messed up state. We do a revert to its initial state then.
                board.revertToState(snapshot);
                break;
            }

            if ( (int)moveInfo[0] > 9000 || (int) moveInfo[0] < -9000)
                break;

            if (verbose)
                System.out.println(moveInfo[1] + ":" + moveInfo[0]);
        }
        while (System.currentTimeMillis() - start <= timeBudgetMillis);

        return moveInfo == null ? null : (Move)moveInfo[1];
    }

    /**
     * Look ahead a specified number of move to decide the best move to play.
     * @param depth The number of move ahead to look.
     * @return The best Move, or null when the side to move has no legal move.
     */
    public Move getBestMoveQuick(int depth){
        start = System.currentTimeMillis();
        random = nextRandom();
        try {
            Object[] result = alphaBeta(depth, maxPlayer, Integer.MIN_VALUE, Integer.MAX_VALUE);
            lastCompletedLevel = depth;
            return (Move) result[1];
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
        return alphaBeta(depth, player, alpha, beta, 0);
    }

    private Object[] alphaBeta(int depth, Player player, int alpha, int beta,
                               int qDepth) throws Exception {
        if (System.currentTimeMillis() - start > timeBudgetMillis){
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

        if (depth == 0){
            // T3: at the horizon, do not trust the static score while
            // captures are available: extend through captures only.
            return quiescence(player, legalMoves, alpha, beta, qDepth);
        }

        int score;
        Move bestMove = null;

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

    /**
     * Quiescence search (T3): stand-pat + captures-only extension run at
     * every depth-0 node instead of returning the static score directly.
     *
     * stand-pat is the static evaluation. At a max node: if stand-pat >=
     * beta the node fails high and stand-pat is returned; otherwise every
     * capture is searched (recursing into further q-nodes) and the best of
     * stand-pat and the capture replies is returned. At a min node the
     * logic is symmetric with alpha. Terminal positions (winner, no legal
     * moves) keep their exact scores: alphaBeta checks them at the entry of
     * every q-node, so a capture onto the goal row scores as an immediate
     * win/loss. The extension is bounded by Q_MAX_DEPTH; beyond it the
     * stand-pat value is returned.
     *
     * q-nodes go through alphaBeta, so they obey the same time check as
     * normal nodes; on timeout the exception propagates up and the memento
     * revert in getBestMoveQuick() still restores the board exactly (the
     * move/undo pairs add no state of their own).
     *
     * Returns an Object[] with the first element being the score and the
     * second element being null (q-nodes never choose a root move).
     */
    private Object[] quiescence(Player player, ArrayList<Move> legalMoves,
                                int alpha, int beta, int qDepth) throws Exception {
        int standPat = GameAnalyzer.analyze(board, strategy);

        if (player == maxPlayer) {

            if (standPat >= beta || qDepth >= Q_MAX_DEPTH)
                return new Object[]{standPat, null};

            int best = standPat;
            if (standPat > alpha)
                alpha = standPat;

            for (Move move : legalMoves){
                if (!isCapture(move, player))
                    continue;

                board.movePiece(move);
                int score = (Integer) alphaBeta(0, minPlayer, alpha, beta, qDepth + 1)[0];
                board.undo(move);

                if (score > best)
                    best = score;

                if (best >= beta)
                    break;
            }

            return new Object[]{best, null};

        } else {

            if (standPat <= alpha || qDepth >= Q_MAX_DEPTH)
                return new Object[]{standPat, null};

            int best = standPat;
            if (standPat < beta)
                beta = standPat;

            for (Move move : legalMoves){
                if (!isCapture(move, player))
                    continue;

                board.movePiece(move);
                int score = (Integer) alphaBeta(0, maxPlayer, alpha, beta, qDepth + 1)[0];
                board.undo(move);

                if (score < best)
                    best = score;

                if (best <= alpha)
                    break;
            }

            return new Object[]{best, null};

        }
    }

    /**
     * A capture is a diagonal move onto a square occupied by an enemy
     * piece. Straight-ahead moves require an empty square and friendly
     * pieces are never legal destinations, so any occupied destination in
     * a legal move list is an enemy piece; the owner check keeps this true
     * even if that ever stops being the case.
     */
    private static boolean isCapture(Move move, Player player){
        Piece destinationPiece = move.getDestination().getPiece();
        return destinationPiece != null
                && destinationPiece.getOwner() != player;
    }

}
