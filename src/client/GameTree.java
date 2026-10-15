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

    /**
     * T10: score penalty applied at the root to a move that would return
     * the game to a recently seen piece arrangement. Small on purpose:
     * large enough to break a shuffle (realistic static-evaluation margins
     * between quiet moves are single digits), small enough to never
     * override a real evaluation difference of any magnitude that matters —
     * and categorically never a forced win/loss, whose terminal scores are
     * ~±2^31: the penalty is noise next to them.
     */
    public static final int REPETITION_PENALTY = 5;

    /** T10: how many recent arrangements the anti-repetition history keeps. */
    public static final int REPETITION_HISTORY_LIMIT = 60;

    /**
     * T10: arrangement-only identifiers (Zobrist with the side-to-move bit
     * removed) of the positions this tree has been asked to move from,
     * oldest first, at most REPETITION_HISTORY_LIMIT entries.
     */
    private final ArrayDeque<Long> repetitionHistory = new ArrayDeque<>();

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

    /**
     * T6: the transposition table, or null when the constructor disabled
     * it (the determinism check searches the same positions with the TT
     * on and off). One table per GameTree: its scores are from this
     * tree's maxPlayer perspective, so no sign conversion is ever needed.
     */
    private TranspositionTable tt;


    public GameTree(Player maxPlayer, Player minPlayer){
        this(maxPlayer, minPlayer, MAX_TIME_MILLIS, null, true, true);
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
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false, true);
    }

    /**
     * T6: like the 4-argument constructor, with the transposition table
     * explicitly enabled or disabled (the determinism check needs the
     * same search with the TT off).
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled);
    }

    private GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                     Long randomSeed, boolean verbose, boolean transpositionEnabled){
        this.maxPlayer = maxPlayer;
        this.minPlayer = minPlayer;
        this.timeBudgetMillis = timeBudgetMillis;
        this.randomSeed = randomSeed;
        this.verbose = verbose;
        this.tt = transpositionEnabled ? new TranspositionTable() : null;

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
     * T6 bench hook: transposition table probes/hits since construction
     * (both 0 when the table was disabled).
     */
    public long getTTProbes(){
        return tt == null ? 0 : tt.probes();
    }

    public long getTTHits(){
        return tt == null ? 0 : tt.hits();
    }

    /**
     * T10: the Zobrist key (T6) with the side-to-move contribution removed:
     * it identifies the piece arrangement alone, so "the game has been in
     * this position before" does not depend on which side is to move (a
     * root child always has the OPPOSITE side to move from any position
     * this tree has been asked to move from). The side key is XORed into
     * the board's hash exactly when it is BLACK's turn (the base value is
     * WHITE to move, see GameBoard.reset), so removing it is a conditional
     * XOR. Exposed for tests.
     */
    public static long arrangementHash(long zobristHash, Player currentPlayer){
        return (currentPlayer == Player.BLACK)
                ? zobristHash ^ TranspositionTable.sideToMoveKey()
                : zobristHash;
    }

    /**
     * T10 test hook: replace the anti-repetition history. The task plan
     * calls for a package-visible setter; the tests live in the tests
     * package, so it is public. Entries beyond REPETITION_HISTORY_LIMIT are
     * trimmed, oldest first.
     */
    public void setRepetitionHistory(List<Long> history){
        repetitionHistory.clear();
        if (history == null)
            return;
        for (long arrangement : history)
            addRepetitionPosition(arrangement);
    }

    private void addRepetitionPosition(long arrangementHash){
        repetitionHistory.addLast(arrangementHash);
        while (repetitionHistory.size() > REPETITION_HISTORY_LIMIT)
            repetitionHistory.removeFirst();
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
        //T10: remember this arrangement so the root loop can penalize a
        //move that returns to it (shuffle-breaking; root only).
        addRepetitionPosition(arrangementHash(board.getZobristHash(),
                board.getCurrentPlayer()));
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
        //T10: same history append as the no-arg overload (both enter the
        //search at the root, where the penalty is applied).
        addRepetitionPosition(arrangementHash(board.getZobristHash(),
                board.getCurrentPlayer()));
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
    /** Root entry point of the search (T6: see the isRoot handling in the
     *  6-argument overload for the root's transposition-table semantics). */
    private Object[] alphaBeta(int depth, Player player, int alpha, int beta) throws Exception {
        return alphaBeta(depth, player, alpha, beta, 0, true);
    }

    /** Internal-node entry point (recursions, including quiescence's). */
    private Object[] alphaBeta(int depth, Player player, int alpha, int beta,
                               int qDepth) throws Exception {
        return alphaBeta(depth, player, alpha, beta, qDepth, false);
    }

    private Object[] alphaBeta(int depth, Player player, int alpha, int beta,
                               int qDepth, boolean isRoot) throws Exception {
        if (System.currentTimeMillis() - start > timeBudgetMillis){
            throw new Exception("Exceeded time");
        }

        long positionHash = board.getZobristHash();
        int turn = board.getTurnNumber();

        // T6: transposition table lookup. Main search only (depth > 0):
        // q-node values use the capture-horizon convention and are never
        // stored or reused.
        TranspositionTable.Entry ttEntry = null;
        if (tt != null && depth > 0)
            ttEntry = tt.probe(positionHash, turn);

        // Internal nodes: the stored score is returned when the entry was
        // searched at least as deep as now AND its flag is compatible with
        // the window (see TranspositionTable.usable). The root NEVER returns
        // a stored score: its result is the move that will be played, and it
        // must come from a fresh search — an entry left by a previous search
        // on the same position would replay that search's move, determinizing
        // root tie-breaks that the shuffle is pinned to keep random (see
        // testRandomnessForMoreThanOneMove, whose position ties every move
        // at the root).
        if (!isRoot
                && ttEntry != null
                && ttEntry.depth >= depth
                && TranspositionTable.usable(ttEntry, alpha, beta))
            return new Object[]{ttEntry.score, ttEntry.bestMove};

        Player winner = board.winner();

        if (winner != null) {
            //T6: terminal scores are stored like any other (EXACT: they do
            //not depend on depth, the turn number keeps them exact).
            int score = winner == maxPlayer
                    ? Integer.MAX_VALUE - turn
                    : Integer.MIN_VALUE + turn;
            if (tt != null && depth > 0)
                tt.store(positionHash, turn, depth, score,
                        TranspositionTable.Flag.EXACT, null);
            return new Object[]{score, null};
        }

        ArrayList<Move> legalMoves = new ArrayList<>(board.getAllLegalMovesQuick());

        if (legalMoves.isEmpty()){
            //The player to move has no legal move: it loses, as in checkmate.
            //Returning the passed alpha/beta here (as the original code did)
            //produced garbage scores that depend on the search window.
            //The margin keeps this terminal strictly below (above) the goal
            //row terminal at the same depth, so the AI prefers the actual
            //objective to stalemating the opponent.
            int score = player == maxPlayer
                    ? Integer.MIN_VALUE + turn + STALEMATE_MARGIN
                    : Integer.MAX_VALUE - turn - STALEMATE_MARGIN;
            if (tt != null && depth > 0)
                tt.store(positionHash, turn, depth, score,
                        TranspositionTable.Flag.EXACT, null);
            return new Object[]{score, null};
        }

        if (depth == 0){
            // T3: at the horizon, do not trust the static score while
            // captures are available: extend through captures only.
            return quiescence(player, legalMoves, alpha, beta, qDepth);
        }

        int score;
        Move bestMove = null;

        // T6: move ordering — the TT's best move first (if still legal),
        // then captures in MVV-LVA order, then the rest in the existing
        // shuffled order (the shuffle stays: non-deterministic tie-breaks
        // are pinned behavior, see testRandomnessForMoreThanOneMove).
        //
        // Which entry's best move is trusted for ordering:
        //  - internal nodes: an entry searched at least as deep as the
        //    current one (same depth from an earlier iteration, or deeper
        //    from another line — both are proven at this depth);
        //  - the root: ONLY a strictly shallower entry, i.e. this search's
        //    own earlier iterative-deepening level (the standard best-move
        //    handoff). A same-depth-or-deeper entry at the root can only
        //    come from a previous search on the same position; preferring
        //    its winner would make root ties sticky across searches (a tied
        //    move can never overtake one searched first), determinizing the
        //    tie-break the shuffle must keep random. Level 1 of every search
        //    has no shallower entry, so it is always a pure shuffle.
        //
        // The list is ordered for depth-0 callers too: the q-search iterates
        // the same list, filtered to captures, so the ordering helps the
        // q-search as well.
        Move ttOrderMove;
        if (isRoot)
            ttOrderMove = (ttEntry != null && ttEntry.depth < depth)
                    ? ttEntry.bestMove : null;
        else
            ttOrderMove = (ttEntry != null && ttEntry.depth >= depth)
                    ? ttEntry.bestMove : null;
        orderMoves(legalMoves, ttOrderMove, player);

        int originalAlpha = alpha;
        int originalBeta = beta;

        for (Move move : legalMoves){

            board.movePiece(move);

            //Update score with alpha/beta pruning
            if (player == maxPlayer) {
                score = (Integer) alphaBeta(depth - 1, minPlayer, alpha, beta, 0)[0];

            } else {
                score = (Integer) alphaBeta(depth - 1, maxPlayer, alpha, beta, 0)[0];
            }

            // T10: anti-repetition, at the root only. A move that returns
            // the game to a recently seen piece arrangement is damped
            // (REPETITION_PENALTY) so an endless shuffle is broken. The
            // penalty is noise next to terminal scores (~±2^31): a forced
            // win/loss is never sacrificed to avoid a repetition (pinned by
            // tests.RepetitionTest.aForcedWinIsNeverSacrificedToAvoidRepetition).
            // Applied after the child returns and before the undo, so the
            // board state is exactly the resulting position's.
            if (isRoot
                    && repetitionHistory.contains(arrangementHash(
                            board.getZobristHash(), board.getCurrentPlayer())))
                score -= REPETITION_PENALTY;

            if (player == maxPlayer) {
                if (score > alpha) {
                    alpha = score;
                    bestMove = move;
                }

            } else {
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

        int nodeScore = (player == maxPlayer) ? alpha : beta;

        // T6: store with the standard bound flags. A max node fails high
        // (score = alpha >= beta: the value is at least beta, LOWER) or
        // fails low (score = alpha never beat the original alpha: every
        // child's true value is at most the original alpha, so the value is
        // at most the score, UPPER); a min node is the mirror — it fails
        // low when beta dropped to the original alpha (some child's true
        // value is at most that: UPPER) and fails high when beta never
        // improved (every child's true value is at least the original beta:
        // LOWER). In between, the full window was searched: EXACT.
        //
        // Note: the plan's shorthand for these rules ("score <= original
        // alpha -> UPPER / LOWER respectively") has a sign typo, like T8's
        // mirror formula: a min node's fail-low stores UPPER, not LOWER —
        // its returned beta bounds the true value from above.
        if (tt != null){
            TranspositionTable.Flag flag;
            if (player == maxPlayer){
                if (nodeScore >= originalBeta)
                    flag = TranspositionTable.Flag.LOWER;
                else if (nodeScore <= originalAlpha)
                    flag = TranspositionTable.Flag.UPPER;
                else
                    flag = TranspositionTable.Flag.EXACT;
            } else {
                if (nodeScore <= originalAlpha)
                    flag = TranspositionTable.Flag.UPPER;
                else if (nodeScore >= originalBeta)
                    flag = TranspositionTable.Flag.LOWER;
                else
                    flag = TranspositionTable.Flag.EXACT;
            }
            tt.store(positionHash, turn, depth, nodeScore, flag, bestMove);
        }

        return new Object[]{nodeScore, bestMove};
    }

    /**
     * T6: in-place move ordering: the TT's best move first (if it is still
     * in the list), then captures in MVV-LVA order (most valuable victim
     * over least valuable attacker, descending), then the remaining moves
     * in the existing shuffled order. The shuffle is preserved —
     * non-deterministic tie-breaking is pinned behavior.
     */
    private void orderMoves(ArrayList<Move> moves, Move ttBestMove, Player player){
        if (moves.size() <= 1)
            return;

        Move first = null;
        ArrayList<Move> captures = new ArrayList<>();
        ArrayList<Move> rest = new ArrayList<>(moves.size());

        for (Move move : moves){
            if (first == null && ttBestMove != null && move.equals(ttBestMove))
                first = move;
            else if (isCapture(move, player))
                captures.add(move);
            else
                rest.add(move);
        }

        // MVV-LVA: victim value / own value, descending. Cross-multiplying
        // keeps this integer-exact (the values are 3 and 7).
        captures.sort((a, b) -> {
            int victimA = a.getDestination().getPiece().getScoreValue();
            int ownA = a.getPieceLocation().getPiece().getScoreValue();
            int victimB = b.getDestination().getPiece().getScoreValue();
            int ownB = b.getPieceLocation().getPiece().getScoreValue();
            return Integer.compare(victimB * ownA, victimA * ownB);
        });

        Collections.shuffle(rest, random);

        moves.clear();
        if (first != null)
            moves.add(first);
        moves.addAll(captures);
        moves.addAll(rest);
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
