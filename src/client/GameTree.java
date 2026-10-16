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

    /**
     * T13 Part A: aspiration-window margin. From the second iterative
     * deepening level on, the root is searched in a window of
     * (previous level's score +/- ASPIRATION_MARGIN) instead of full width.
     * A tight window prunes far more, buying depth at the same budget; the
     * fail/retry below makes it exact.
     */
    private static final int ASPIRATION_MARGIN = 5;

    /**
     * T13 Part A: cap on the widened aspiration margin. Each aspiration
     * failure doubles the margin up to this; once it reaches the cap a full
     * window (which cannot fail) is used.
     */
    private static final int MAX_ASPIRATION_MARGIN = 200;

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

    // T13 Part A: when false, getBestMoveQuick() searches every level with a
    // full window (the pre-T13 behavior). The determinism check searches the
    // same positions with aspiration on and off.
    private boolean aspirationEnabled;

    // T13 Part B: when false, alphaBeta searches every child with the full
    // window (the pre-Part-B behavior). The determinism check searches the
    // same positions with PVS on and off.
    private boolean pvsEnabled;

    // T13 test hook: number of alphaBeta node visits by the most recent
    // search (main-search and q-nodes alike). A plain counter; the tests use
    // it to show an aspiration window visits fewer nodes than a full window
    // on the same position. Reset at the start of each search.
    private long nodeCount;

    // T13 test hook: number of aspiration-window retries (a fail that
    // widened the window and re-searched the same level) by the most recent
    // getBestMoveQuick. Reset at the start of each search; always 0 when
    // aspiration is disabled.
    private int aspirationRetries;

    // T13 test hook: number of PVS fail-high re-searches (a null-window
    // child whose score violated the bound and was re-searched with the
    // full window) by the most recent search. Reset at the start of each
    // search; always 0 when PVS is disabled.
    private int pvsResearched;

    // T14 item 1 test hook: internal nodes (depth > 0, non-root) of the most
    // recent search whose ordered FIRST move came from a TT entry (the active
    // gate's best move was still legal). The audit's measurement ran this
    // counter with the current gate (entry.depth >= depth) and the relaxed
    // gate (any entry depth); the fixed behavior keeps the relaxed gate at
    // internal nodes while the ROOT stays on its strictly-shallower rule.
    private long ttOrderHits;

    // T14 item 5 test hooks: q-nodes visited by the most recent search, and
    // how many of them hit the Q_MAX_DEPTH capture-ply cap (the
    // qDepth >= Q_MAX_DEPTH exit that returns stand-pat early).
    private long qNodeCount;
    private long qBoundHits;

    // T14 item 1: when true (the default — the fixed behavior), internal
    // nodes order by the TT best move from an entry at ANY stored depth: the
    // standard iterative-deepening handoff (the previous level's best move —
    // at a node, the previous level's entry is exactly one depth shallower
    // than the probe, so the pre-T14 gate below never saw it below the root)
    // plus same-depth/deeper entries. False restores the pre-T14 gate
    // (entries searched at least as deep only) for the score-invariance
    // test. Ordering only: it never affects returned values — the
    // score-return gate in alphaBeta stays strict in all cases, and the
    // ROOT keeps its strictly-shallower rule (root ties must stay
    // shuffle-random, pinned by testRandomnessForMoreThanOneMove).
    private final boolean ttMoveHandoff;

    // T15: A/B feature off-flags (the engine-level stack, all default ON so
    // the production constructors are bit-identical to pre-T15). When
    // quiescenceEnabled is false, depth-0 nodes return the stand-pat static
    // score directly (the pre-T3 behavior) instead of extending through
    // captures. When threatTermsEnabled is false, the stand-pat evaluation
    // skips T4's threat & safety terms (the pre-T4 evaluation — what the
    // t2/t3 engine levels evaluate). When repetitionEnabled is false, no
    // arrangement is appended to the anti-repetition history and no root
    // penalty is applied (the pre-T10 behavior).
    private final boolean quiescenceEnabled;
    private final boolean threatTermsEnabled;
    private final boolean repetitionEnabled;

    // T16: when true (the default — the post-T16 behavior), the quiet moves
    // in orderMoves are bucketed by goal proximity (fewer rows from the
    // destination row to this side's goal row first) and shuffled only
    // WITHIN each bucket: equally-close quiet moves keep their
    // shuffle-random order (pinned by testRandomnessForMoreThanOneMove),
    // while a pusher one row from the goal row always precedes one two rows
    // from. False restores exactly the pre-T16 behavior (every quiet move
    // in one plain shuffle). The TT best-move-first slot and the MVV-LVA
    // capture ordering are untouched by the flag in both directions (they
    // predate it). Ordering only: it never affects returned values — the
    // score-return gate in alphaBeta is independent of it.
    private final boolean orderingEnabled;

    public GameTree(Player maxPlayer, Player minPlayer){
        // NOTE: delegates to the 13-argument private constructor (verbose,
        // tt, aspiration, pvs, handoff, quiescence, threat terms,
        // repetition, ordering). The delegation arity must match the
        // PRIVATE constructor — an 8-argument this() call would silently
        // bind to the public 8-argument overload and shift every flag
        // (caught live during T14; pinned by
        // constructorFlagsAreWiredToTheirDocuments).
        this(maxPlayer, minPlayer, MAX_TIME_MILLIS, null, true, true, true,
                true, true, true, true, true, true);
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
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false, true, true,
                true, true, true, true, true, true);
    }

    /**
     * T6: like the 4-argument constructor, with the transposition table
     * explicitly enabled or disabled (the determinism check needs the
     * same search with the TT off).
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, true, true, true, true, true, true,
                true);
    }

    /**
     * T13 Part A: like the 5-argument constructor, with the aspiration
     * windows explicitly enabled or disabled (the determinism check needs
     * the same search with aspiration off, the pre-T13 full-window
     * behavior).
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, true, true,
                true, true, true, true);
    }

    /**
     * T13 Part B: like the 6-argument constructor, with PVS (the
     * principal-variation null-window search + fail-high re-search in the
     * child loop) explicitly enabled or disabled (the determinism check
     * needs the same search with PVS off, the pre-Part-B full-window
     * behavior).
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled, boolean pvsEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, pvsEnabled, true,
                true, true, true, true);
    }

    /**
     * T14 item 1: like the 7-argument constructor, with the internal-node TT
     * best-move handoff explicitly enabled or disabled (the score-invariance
     * test searches the same positions both ways; see the ttMoveHandoff
     * field for what the flag controls). Default in all other constructors:
     * enabled (the fixed behavior).
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled, boolean pvsEnabled,
                    boolean ttMoveHandoff){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, pvsEnabled,
                ttMoveHandoff, true, true, true, true);
    }

    /**
     * T15: like the 8-argument constructor, with T3's quiescence extension
     * explicitly enabled or disabled (the t2/t4/t6 engine levels of the
     * A/B harness search pre-T3: depth-0 nodes return the stand-pat static
     * score directly). Default in all other constructors: enabled.
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled, boolean pvsEnabled,
                    boolean ttMoveHandoff, boolean quiescenceEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, pvsEnabled,
                ttMoveHandoff, quiescenceEnabled, true, true, true);
    }

    /**
     * T15: like the 9-argument constructor, with T4's threat &amp; safety
     * evaluation terms explicitly enabled or disabled (the t2/t3 engine
     * levels evaluate pre-T4). The flag is plumbed to the stand-pat
     * evaluation in both the quiescence and the pre-T3 direct-return path;
     * both strategies honor it. Default in all other constructors: enabled.
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled, boolean pvsEnabled,
                    boolean ttMoveHandoff, boolean quiescenceEnabled,
                    boolean threatTermsEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, pvsEnabled,
                ttMoveHandoff, quiescenceEnabled, threatTermsEnabled, true,
                true);
    }

    /**
     * T15: like the 10-argument constructor, with T10's anti-repetition
     * (history append + root penalty) explicitly enabled or disabled (the
     * t2/t3/t4/t6 engine levels predate it). Default in all other
     * constructors: enabled.
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled, boolean pvsEnabled,
                    boolean ttMoveHandoff, boolean quiescenceEnabled,
                    boolean threatTermsEnabled, boolean repetitionEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, pvsEnabled,
                ttMoveHandoff, quiescenceEnabled, threatTermsEnabled,
                repetitionEnabled, true);
    }

    /**
     * T16: like the 11-argument constructor, with T16's goal-proximity
     * ordering of quiet moves explicitly enabled or disabled (off is
     * exactly the pre-T16 behavior: every quiet move in one plain
     * shuffle). Default in all other constructors: enabled (the post-T16
     * behavior).
     */
    public GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                    Long seed, boolean transpositionEnabled,
                    boolean aspirationEnabled, boolean pvsEnabled,
                    boolean ttMoveHandoff, boolean quiescenceEnabled,
                    boolean threatTermsEnabled, boolean repetitionEnabled,
                    boolean orderingEnabled){
        this(maxPlayer, minPlayer, timeBudgetMillis, seed, false,
                transpositionEnabled, aspirationEnabled, pvsEnabled,
                ttMoveHandoff, quiescenceEnabled, threatTermsEnabled,
                repetitionEnabled, orderingEnabled);
    }

    private GameTree(Player maxPlayer, Player minPlayer, long timeBudgetMillis,
                     Long randomSeed, boolean verbose, boolean transpositionEnabled,
                     boolean aspirationEnabled, boolean pvsEnabled,
                     boolean ttMoveHandoff, boolean quiescenceEnabled,
                     boolean threatTermsEnabled, boolean repetitionEnabled,
                     boolean orderingEnabled){
        this.maxPlayer = maxPlayer;
        this.minPlayer = minPlayer;
        this.timeBudgetMillis = timeBudgetMillis;
        this.randomSeed = randomSeed;
        this.verbose = verbose;
        this.tt = transpositionEnabled ? new TranspositionTable() : null;
        this.aspirationEnabled = aspirationEnabled;
        this.pvsEnabled = pvsEnabled;
        this.ttMoveHandoff = ttMoveHandoff;
        this.quiescenceEnabled = quiescenceEnabled;
        this.threatTermsEnabled = threatTermsEnabled;
        this.repetitionEnabled = repetitionEnabled;
        this.orderingEnabled = orderingEnabled;

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
     * T14 item 2b bench hook: probes rejected by the transposition table's
     * turn-number guard since construction (0 when the table was disabled).
     */
    public long getTTTurnGuardMisses(){
        return tt == null ? 0 : tt.turnGuardMisses();
    }

    /**
     * T13 test hook: alphaBeta node visits by the most recent search
     * (main-search nodes and q-nodes alike). Reset at the start of each
     * getBestMoveQuick/debugSearch.
     */
    public long getNodeCount(){
        return nodeCount;
    }

    /**
     * T13 test hook: aspiration-window retries by the most recent
     * getBestMoveQuick (0 when aspiration is disabled or no fail occurred).
     */
    public int getAspirationRetries(){
        return aspirationRetries;
    }

    /**
     * T13 test hook: PVS fail-high re-searches by the most recent search
     * (0 when PVS is disabled or no null-window search violated its bound).
     */
    public int getPvsResearched(){
        return pvsResearched;
    }

    /**
     * T14 item 1 test hook: internal nodes of the most recent search whose
     * ordered first move came from a TT entry (see the ttOrderHits field).
     * Reset at the start of each search.
     */
    public long getTtOrderHits(){
        return ttOrderHits;
    }

    /**
     * T14 item 5 test hook: q-nodes visited by the most recent search
     * (stand-pat + capture extension nodes). Reset at the start of each
     * search.
     */
    public long getQNodeCount(){
        return qNodeCount;
    }

    /**
     * T14 item 5 test hook: how many of the most recent search's q-nodes hit
     * the Q_MAX_DEPTH capture-ply cap (returned stand-pat without searching
     * captures). 0 means the cap never bound in that search.
     */
    public long getQBoundHits(){
        return qBoundHits;
    }

    /**
     * T14 item 4 test hook: replace the transposition table. The PVS × TT
     * dirty-table fuzz (tests.GameTreeTest) probes a table still dirty from
     * ANOTHER tree's search — the table must be from the same maxPlayer
     * perspective (scores are never sign-converted, see the field's doc).
     */
    public void setTranspositionTable(TranspositionTable tt){
        this.tt = tt;
    }

    /**
     * T13 test hook: probe the transposition table directly (the TT flag
     * consistency check compares the entry a PVS search stores at a position
     * with the one the pre-PVS search stores there). Returns null when the
     * table was disabled or the slot is empty. Counts as a probe, as for any
     * lookup.
     */
    public TranspositionTable.Entry probeTT(long hash, int turnNumber){
        return tt == null ? null : tt.probe(hash, turnNumber);
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

    /**
     * T15 test hook: number of arrangements in the anti-repetition history.
     * 0 after every search when repetitionEnabled is false (no append);
     * grows by one per getBestMoveQuick call when it is on. The wiring of
     * the flag is pinned via this counter in tests.EngineLevelTest.
     */
    public int getRepetitionHistorySize(){
        return repetitionHistory.size();
    }

    private void addRepetitionPosition(long arrangementHash){
        repetitionHistory.addLast(arrangementHash);
        while (repetitionHistory.size() > REPETITION_HISTORY_LIMIT)
            repetitionHistory.removeFirst();
    }

    /**
     * C2: the search core's (score, best-move) result. Replaces the raw
     * Object[] tuples that used to be downcast with (Integer) x[0] /
     * (Move) x[1] at every call site. Private because it never leaves
     * this class.
     */
    private record ScoreAndMove(int score, Move bestMove) {}

    /**
     * C2: the search's timeout signal. Replaces the bare
     * new Exception("Exceeded time"): thrown only by the time check in
     * alphaBeta, and caught only where the bare Exception used to be
     * caught (same spots, same recovery). Any other exception (an NPE in
     * search, say) now propagates instead of being silently treated as a
     * timeout.
     */
    private static final class SearchTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;
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
        resetSearchCounters();
        //T10: remember this arrangement so the root loop can penalize a
        //move that returns to it (shuffle-breaking; root only). T15:
        //skipped entirely when the repetition guard is disabled (the t2-
        //t6 engine levels predate it).
        if (repetitionEnabled)
            addRepetitionPosition(arrangementHash(board.getZobristHash(),
                    board.getCurrentPlayer()));
        int level = 1;
        lastCompletedLevel = 0;
        GameStep snapshot = board.createMemento();

        ScoreAndMove moveInfo = null;
        int prevScore = 0;

        do {
            try {
                moveInfo = searchLevelWithAspiration(level, prevScore, snapshot);
                lastCompletedLevel = level;
            } catch (SearchTimeoutException e){
                //When minmax can't complete in time, it will leave the board
                //in a messed up state. We do a revert to its initial state then.
                board.revertToState(snapshot);
                break;
            }

            if (moveInfo.score() > 9000 || moveInfo.score() < -9000)
                break;

            if (verbose)
                System.out.println(moveInfo.bestMove() + ":" + moveInfo.score());

            prevScore = moveInfo.score();
            level++;
        }
        while (System.currentTimeMillis() - start <= timeBudgetMillis);

        return moveInfo == null ? null : moveInfo.bestMove();
    }

    /**
     * T13 Part A: search a single iterative-deepening level with aspiration
     * windows, retrying on a fail. Level 1 (or aspiration disabled) uses a
     * full window; level 2+ uses (prevScore +/- ASPIRATION_MARGIN) and, on a
     * fail, widens the margin (doubling up to MAX_ASPIRATION_MARGIN, then a
     * full window). Each retry reverts the board to the pre-iteration
     * snapshot. Returns the level's result (score + best move). A forced
     * win/loss (|score| > 9000) is returned WITHOUT a retry — the caller
     * breaks the deepening on it. Throws on timeout (the caller reverts the
     * board).
     */
    private ScoreAndMove searchLevelWithAspiration(int level, int prevScore,
                                               GameStep snapshot) throws SearchTimeoutException {
        // Level 1 has no prior score, so it is full-width; later levels narrow
        // to prevScore +/- ASPIRATION_MARGIN. When aspiration is disabled every
        // level is full-width, which is exactly the pre-T13 behavior.
        int alpha, beta;
        if (aspirationEnabled && level > 1){
            alpha = prevScore - ASPIRATION_MARGIN;
            beta  = prevScore + ASPIRATION_MARGIN;
        } else {
            alpha = Integer.MIN_VALUE;
            beta  = Integer.MAX_VALUE;
        }
        int margin = ASPIRATION_MARGIN;

        do {
            ScoreAndMove result = alphaBeta(level, maxPlayer, alpha, beta);
            int score = result.score();

            // Forced-win/loss cutoff: the forced line is found regardless of
            // the window width, so no retry (the fail is NOT an aspiration
            // failure to be widened around) — return it and let the caller
            // stop deepening.
            if (score > 9000 || score < -9000)
                return result;

            // Aspiration failure (level 2+ only): the true score escaped the
            // window (fail-high >= beta or fail-low <= alpha). Revert to the
            // pre-iteration snapshot and retry this level with a wider window;
            // once the margin reaches the cap a full window (which cannot
            // fail) is used. A timeout during the retry is handled by the
            // unchanged time check in alphaBeta (it throws; the caller reverts).
            if (aspirationEnabled && level > 1
                    && (score >= beta || score <= alpha)){
                board.revertToState(snapshot);
                aspirationRetries++;
                if (margin >= MAX_ASPIRATION_MARGIN){
                    alpha = Integer.MIN_VALUE;
                    beta  = Integer.MAX_VALUE;
                } else {
                    margin = Math.min(margin * 2, MAX_ASPIRATION_MARGIN);
                    alpha = prevScore - margin;
                    beta  = prevScore + margin;
                }
                continue;
            }
            return result;
        } while (true);
    }

    /**
     * T13 test hook: run the aspiration iterative deepening up to maxDepth
     * (depth-capped — like getBestMoveQuick but stopping at maxDepth instead
     * of a timeout) and return the root score. A measurement tool: it does
     * not append to the repetition history (so no repetition penalty applies)
     * and the caller should use a fresh tree so the history is empty.
     * Returns Integer.MIN_VALUE if even level 1 timed out.
     */
    public int aspirationScoreAtDepth(int maxDepth){
        start = System.currentTimeMillis();
        random = nextRandom();
        resetSearchCounters();
        GameStep snapshot = board.createMemento();
        ScoreAndMove moveInfo = null;
        int prevScore = 0;
        for (int level = 1; level <= maxDepth; level++){
            try {
                moveInfo = searchLevelWithAspiration(level, prevScore, snapshot);
            } catch (SearchTimeoutException e){
                board.revertToState(snapshot);
                break;
            }
            int score = moveInfo.score();
            if (score > 9000 || score < -9000)
                return score;
            prevScore = score;
        }
        return moveInfo == null ? Integer.MIN_VALUE : moveInfo.score();
    }

    /**
     * Resets every per-search counter (nodeCount, aspirationRetries,
     * pvsResearched, ttOrderHits, qNodeCount, qBoundHits) at the start of a
     * search. Each of the search entry points calls this once.
     */
    private void resetSearchCounters(){
        nodeCount = 0;
        aspirationRetries = 0;
        pvsResearched = 0;
        ttOrderHits = 0;
        qNodeCount = 0;
        qBoundHits = 0;
    }

    /**
     * Look ahead a specified number of move to decide the best move to play.
     * @param depth The number of move ahead to look.
     * @return The best Move, or null when the side to move has no legal move.
     */
    public Move getBestMoveQuick(int depth){
        start = System.currentTimeMillis();
        random = nextRandom();
        resetSearchCounters();
        //T10: same history append as the no-arg overload (both enter the
        //search at the root, where the penalty is applied). T15: gated on
        //repetitionEnabled, as in the no-arg overload.
        if (repetitionEnabled)
            addRepetitionPosition(arrangementHash(board.getZobristHash(),
                    board.getCurrentPlayer()));
        try {
            ScoreAndMove result = alphaBeta(depth, maxPlayer, Integer.MIN_VALUE, Integer.MAX_VALUE);
            lastCompletedLevel = depth;
            return result.bestMove();
        } catch (SearchTimeoutException e){
            return null;
        }
    }

    /**
     * T13 test hook: search a fixed depth with an explicit window and report
     * the root score. The window is parameterized so tests can run the same
     * position full-width and aspiration-narrow and compare both the score
     * (must match) and the node count (read via getNodeCount). Like
     * getBestMoveQuick(int) it runs a single alphaBeta call (no iterative
     * deepening, no aspiration retry of its own — the caller chooses the
     * window) and does not append to the repetition history (it is a
     * measurement tool, not a move chooser). Returns Integer.MIN_VALUE on a
     * timeout.
     */
    public int debugSearchScore(int depth, int alpha, int beta){
        start = System.currentTimeMillis();
        random = nextRandom();
        resetSearchCounters();
        try {
            return alphaBeta(depth, maxPlayer, alpha, beta).score();
        } catch (SearchTimeoutException e){
            return Integer.MIN_VALUE;
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
     * Returns a ScoreAndMove with the score and the best Move.
     * @param depth How many moves to look forward.
     * @param player The Player who would be playing the move.
     * @param alpha The maximum score found yet at that depth level.
     * @param beta The minimum score found yet at that depth level.
     * @return A ScoreAndMove with the score and the best Move.
     */
    /** Root entry point of the search (T6: see the isRoot handling in the
     *  6-argument overload for the root's transposition-table semantics). */
    private ScoreAndMove alphaBeta(int depth, Player player, int alpha, int beta) throws SearchTimeoutException {
        return alphaBeta(depth, player, alpha, beta, 0, true);
    }

    /** Internal-node entry point (recursions, including quiescence's). */
    private ScoreAndMove alphaBeta(int depth, Player player, int alpha, int beta,
                               int qDepth) throws SearchTimeoutException {
        return alphaBeta(depth, player, alpha, beta, qDepth, false);
    }

    private ScoreAndMove alphaBeta(int depth, Player player, int alpha, int beta,
                               int qDepth, boolean isRoot) throws SearchTimeoutException {
        nodeCount++;
        if (System.currentTimeMillis() - start > timeBudgetMillis){
            throw new SearchTimeoutException();
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
            return new ScoreAndMove(ttEntry.score, ttEntry.bestMove);

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
            return new ScoreAndMove(score, null);
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
            return new ScoreAndMove(score, null);
        }

        if (depth == 0){
            // T3: at the horizon, do not trust the static score while
            // captures are available: extend through captures only.
            // T15: the t2/t4/t6 engine levels search pre-T3 — the stand-pat
            // static score is returned directly, exactly as the pre-T3
            // depth-0 exit did (the T4 terms are still subject to
            // threatTermsEnabled: t2 evaluates pre-T4, t3/t4/t6 evaluate
            // with the terms on).
            if (!quiescenceEnabled)
                return new ScoreAndMove(strategy.analyze(threatTermsEnabled), null);
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
        else if (ttMoveHandoff)
            // T14 item 1 (the fixed behavior): the standard ID handoff — the
            // previous level's best move (the entry one depth shallower than
            // the probe) plus same-depth/deeper entries, at any stored depth.
            // Measured on the full board: -47% nodes at depth 9 (1.91M vs
            // 3.62M at seed 42), scores unchanged (pinned in GameTreeTest).
            ttOrderMove = (ttEntry != null && ttEntry.bestMove != null)
                    ? ttEntry.bestMove : null;
        else
            ttOrderMove = (ttEntry != null && ttEntry.depth >= depth)
                    ? ttEntry.bestMove : null;
        if (!isRoot && depth > 0
                && ttOrderMove != null
                && legalMoves.contains(ttOrderMove))
            ttOrderHits++;
        orderMoves(legalMoves, ttOrderMove, player);

        int originalAlpha = alpha;
        int originalBeta = beta;

        // T13 Part B: PVS. The first child (the best-ordered move: the TT
        // best move, or the first shuffled move at level 1) is searched with
        // the full window, so the bound it establishes is exact for the rest;
        // every later child is searched with a zero-width "null" window
        // (max node: (alpha, alpha + 1), min node: (beta - 1, beta)). A
        // null-window result that violates the bound (score > alpha at a max
        // node, score < beta at a min node) means the child is a principal
        // variation candidate: it is re-searched immediately with the full
        // window (the standard PVS fail-high retry) and the RE-SEARCHED score
        // is the one used below. The re-search reuses the already-applied
        // move: the null-window search undid all of its own descendants, so
        // the board is still in the position after `move`. PVS applies at
        // depth > 0 only: depth-0 nodes exit to quiescence above and have no
        // child loop. The repetition penalty, the alpha/beta update and the
        // cutoff below are unchanged and run on the final score. With
        // pvsEnabled false this is exactly the pre-Part-B full-window loop
        // (the determinism check). The null window is always a legal window:
        // alpha < beta here (the loop breaks on alpha >= beta), so
        // alpha + 1 <= beta at a max node and beta - 1 >= alpha at a min
        // node — no overflow.
        int moveNumber = 0;
        for (Move move : legalMoves){

            board.movePiece(move);

            //Update score with alpha/beta pruning
            if (player == maxPlayer) {
                if (pvsEnabled && moveNumber > 0){
                    score = alphaBeta(depth - 1, minPlayer,
                            alpha, alpha + 1, 0).score();
                    if (score > alpha){
                        pvsResearched++;
                        score = alphaBeta(depth - 1, minPlayer,
                                alpha, beta, 0).score();
                    }
                } else {
                    score = alphaBeta(depth - 1, minPlayer, alpha, beta, 0).score();
                }

            } else {
                if (pvsEnabled && moveNumber > 0){
                    score = alphaBeta(depth - 1, maxPlayer,
                            beta - 1, beta, 0).score();
                    if (score < beta){
                        pvsResearched++;
                        score = alphaBeta(depth - 1, maxPlayer,
                                alpha, beta, 0).score();
                    }
                } else {
                    score = alphaBeta(depth - 1, maxPlayer, alpha, beta, 0).score();
                }
            }
            moveNumber++;

            // T10: anti-repetition, at the root only. A move that returns
            // the game to a recently seen piece arrangement is damped
            // (REPETITION_PENALTY) so an endless shuffle is broken. The
            // penalty is noise next to terminal scores (~±2^31): a forced
            // win/loss is never sacrificed to avoid a repetition (pinned by
            // tests.RepetitionTest.aForcedWinIsNeverSacrificedToAvoidRepetition).
            // Applied after the child returns and before the undo, so the
            // board state is exactly the resulting position's.
            if (isRoot
                    && repetitionEnabled
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

        return new ScoreAndMove(nodeScore, bestMove);
    }

    /**
     * T6 (extended by T16): in-place move ordering: the TT's best move
     * first (if it is still in the list), then captures in MVV-LVA order
     * (most valuable victim over least valuable attacker, descending),
     * then the remaining quiet moves. With orderingEnabled (T16, the
     * default) the quiet moves are bucketed by goal proximity — fewer rows
     * from the destination row to this side's goal row first — and shuffled
     * only WITHIN each bucket, so equally-close quiet moves keep their
     * pinned shuffle-random order (testRandomnessForMoreThanOneMove). With
     * it disabled (exactly the pre-T16 behavior) the quiet moves are one
     * plain shuffle. The shuffle is preserved either way —
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

        if (orderingEnabled){
            // T16: bucket the quiet moves by goal proximity (ascending —
            // a pusher one row from the goal row before one two rows from),
            // then shuffle WITHIN each bucket: equidistant quiet moves still
            // shuffle, so the pinned randomness survives. Pushers and Pushees
            // are keyed alike (a Pushee's advance matters equally; its
            // legality already requires a friendly Pusher behind it);
            // captures keep their MVV-LVA priority above this.
            TreeMap<Integer, ArrayList<Move>> buckets = new TreeMap<>();
            for (Move move : rest)
                buckets.computeIfAbsent(proximityKey(move, player),
                        k -> new ArrayList<>()).add(move);
            ArrayList<Move> byProximity = new ArrayList<>(rest.size());
            for (ArrayList<Move> bucket : buckets.values()){
                Collections.shuffle(bucket, random);
                byProximity.addAll(bucket);
            }
            rest = byProximity;
        }
        else
            Collections.shuffle(rest, random);

        moves.clear();
        if (first != null)
            moves.add(first);
        moves.addAll(captures);
        moves.addAll(rest);
    }

    /**
     * T16: the goal-proximity key of a move by the side to move — the
     * number of rows from its DESTINATION row to this side's goal row,
     * direction-aware (WHITE's goal row is 7, BLACK's is 0). Smaller keys
     * are closer to the goal and are ordered first; a quiet move onto the
     * goal row (an immediate win) has key 0. Pushers and Pushees are keyed
     * alike.
     */
    private int proximityKey(Move move, Player player){
        int goalRow = (player == Player.WHITE)
                ? GameBoard.WHITE_GOAL_ROW : GameBoard.BLACK_GOAL_ROW;
        return (goalRow - move.getDestination().getRow()) * player.direction();
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
     * Returns a ScoreAndMove with the score and a null best move (q-nodes
     * never choose a root move).
     */
    private ScoreAndMove quiescence(Player player, ArrayList<Move> legalMoves,
                                int alpha, int beta, int qDepth) throws SearchTimeoutException {
        // T14 item 5 (measurement): count q-nodes and how many of them hit
        // the capture-ply cap (qDepth >= Q_MAX_DEPTH returns stand-pat
        // without searching captures).
        qNodeCount++;
        if (qDepth >= Q_MAX_DEPTH)
            qBoundHits++;

        // T15: the stand-pat evaluation honors threatTermsEnabled (the t2
        // level evaluates pre-T4; the term is mirror-invariant either way,
        // so T8's invariant holds with it both on and off).
        int standPat = strategy.analyze(threatTermsEnabled);

        if (player == maxPlayer) {

            if (standPat >= beta || qDepth >= Q_MAX_DEPTH)
                return new ScoreAndMove(standPat, null);

            int best = standPat;
            if (standPat > alpha)
                alpha = standPat;

            for (Move move : legalMoves){
                if (!isCapture(move, player))
                    continue;

                board.movePiece(move);
                int score = alphaBeta(0, minPlayer, alpha, beta, qDepth + 1).score();
                board.undo(move);

                if (score > best)
                    best = score;

                if (best >= beta)
                    break;
            }

            return new ScoreAndMove(best, null);

        } else {

            if (standPat <= alpha || qDepth >= Q_MAX_DEPTH)
                return new ScoreAndMove(standPat, null);

            int best = standPat;
            if (standPat < beta)
                beta = standPat;

            for (Move move : legalMoves){
                if (!isCapture(move, player))
                    continue;

                board.movePiece(move);
                int score = alphaBeta(0, maxPlayer, alpha, beta, qDepth + 1).score();
                board.undo(move);

                if (score < best)
                    best = score;

                if (best <= alpha)
                    break;
            }

            return new ScoreAndMove(best, null);

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
