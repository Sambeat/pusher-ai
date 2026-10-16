package client;

import game.GameBoard;
import game.Move;
import game.Piece;
import game.Player;

import java.util.Arrays;
import java.util.Random;

/**
 * T6 — Zobrist position keys and the transposition table.
 *
 * <p><b>Position keys.</b> 64 squares x 4 piece types (owner x canPush) +
 * 1 side-to-move key = 257 random 64-bit values, generated once from a
 * fixed seed so every position key is reproducible across runs. A
 * position's key is the XOR of its pieces' keys plus the side-to-move key
 * when it is BLACK's move (WHITE to move is the base, which is also the
 * empty-board key after {@code GameBoard.reset()}). The board maintains
 * the key incrementally in movePiece()/undo()/putPiece()/reset()/
 * setCurrentPlayer(); GameStep carries it so revertToState() restores it
 * exactly (the memento path replaces the board at once, so incrementality
 * does not apply there).</p>
 *
 * <p><b>Table.</b> 1 &lt;&lt; 20 slots, direct-mapped on the low bits of
 * the key. Each slot stores the key (64-bit truncated), the turn number,
 * the depth, the score, an EXACT/LOWER/UPPER flag and the best move.
 * Replacement is "deeper entry wins": a stored entry deeper than the one
 * being stored is kept.</p>
 *
 * <p><b>Flags.</b> Scores are from the owning search's maxPlayer
 * perspective (fixed for a GameTree, so no sign conversion is ever
 * needed). EXACT: the position was fully searched, the score is its value
 * at the stored depth, usable in any window. LOWER: the search failed
 * high (score &gt;= beta), the true value is at least the score, usable
 * only when score &gt;= beta. UPPER: the search failed low (score &lt;=
 * alpha), the true value is at most the score, usable only when score
 * &lt;= alpha. A probe is usable only when the stored depth is at least
 * the current depth AND the turn number matches: the 64-bit truncated key
 * can collide, and a match on a different turn number is treated as a
 * miss (cheap, bounds the truncation error).</p>
 *
 * @see IMPROVEMENT_PLAN.md task T6
 */
public class TranspositionTable {

    /** 64 squares x 4 piece types + 1 side-to-move key. */
    public static final int ZOBRIST_KEY_COUNT =
            GameBoard.GAME_BOARD_LENGTH * GameBoard.GAME_BOARD_LENGTH * 4 + 1;

    /**
     * Fixed seed (the golden-ratio constant GameTree already uses for its
     * seeded shuffling) so the 257 keys — and therefore every position
     * key — are identical on every run and machine.
     */
    private static final long ZOBRIST_SEED = 0x9E3779B97F4A7C15L;

    private static final long[] ZOBRIST = new long[ZOBRIST_KEY_COUNT];

    static {
        Random random = new Random(ZOBRIST_SEED);
        for (int i = 0; i < ZOBRIST_KEY_COUNT; i++)
            ZOBRIST[i] = random.nextLong();
    }

    /** The bound flags (see the class javadoc). */
    public enum Flag { EXACT, LOWER, UPPER }

    /** A transposition table entry, snapshotted on probe. */
    public static final class Entry {
        public final long hash;
        public final int depth;
        public final int score;
        public final Flag flag;
        public final Move bestMove;

        public Entry(long hash, int depth, int score, Flag flag, Move bestMove) {
            this.hash = hash;
            this.depth = depth;
            this.score = score;
            this.flag = flag;
            this.bestMove = bestMove;
        }
    }

    private final int sizeMask;
    private final long[] hashes;
    private final int[] turns;    // stored turn number (collision guard)
    private final int[] depths;   // -1 = empty slot
    private final int[] scores;
    private final byte[] flags;   // Flag.ordinal()
    private final Move[] bestMoves;

    private long probes;
    private long hits;

    /**
     * T14 item 2b: probes that matched a stored key but a DIFFERENT turn
     * number (treated as a miss). This is what the turn-number guard catches:
     * a direct-mapped collision with another position of the same key is
     * impossible at 64 bits (the key IS the 64-bit hash), so these are the
     * same position reached at a different turn number (a game cycle) or the
     * truncation-error cases the guard exists for. A nonzero rate is
     * expected and harmless (the miss is the correct behavior); the audit
     * measures it over a full-board game.
     */
    private long turnGuardMisses;

    /** The default 1 &lt;&lt; 20-entry table. */
    public TranspositionTable(){
        this(1 << 20);
    }

    /**
     * A table with the given number of entries (a power of two). Sizes
     * below the default exist for the synthetic unit tests.
     */
    public TranspositionTable(int entries){
        if (entries < 1 || (entries & (entries - 1)) != 0)
            throw new IllegalArgumentException("entries must be a power of two");
        this.sizeMask = entries - 1;
        this.hashes = new long[entries];
        this.turns = new int[entries];
        this.depths = new int[entries];
        this.scores = new int[entries];
        this.flags = new byte[entries];
        this.bestMoves = new Move[entries];
        Arrays.fill(this.depths, -1);
    }

    /**
     * The Zobrist key contribution of the given piece on the given square.
     * The 4 piece types are the owner x canPush combinations (Pusher and
     * Pushee per color).
     */
    public static long pieceKey(int row, int col, Piece piece){
        int square = row * GameBoard.GAME_BOARD_LENGTH + col;
        return ZOBRIST[square * 4 + pieceType(piece)];
    }

    /**
     * The side-to-move XOR component, applied when it is BLACK's move
     * (WHITE to move is the base, contributing nothing).
     */
    public static long sideToMoveKey(){
        return ZOBRIST[ZOBRIST_KEY_COUNT - 1];
    }

    /** owner x canPush index: 4 distinct piece identities. */
    private static int pieceType(Piece piece){
        int owner = (piece.getOwner() == Player.BLACK) ? 1 : 0;
        int kind = piece.canPush() ? 0 : 1;
        return owner * 2 + kind;
    }

    private int index(long hash){
        return ((int) hash) & sizeMask;
    }

    /**
     * Look up the entry for (hash, turnNumber). A hash match with a
     * different turn number is treated as a miss. Every call counts as a
     * probe; a returned entry counts as a hit (the bench's hit-rate hook).
     */
    public Entry probe(long hash, int turnNumber){
        probes++;
        int i = index(hash);
        if (depths[i] != -1 && hashes[i] == hash && turns[i] == turnNumber) {
            hits++;
            return new Entry(hashes[i], depths[i], scores[i],
                    Flag.values()[flags[i]], bestMoves[i]);
        }
        if (depths[i] != -1 && hashes[i] == hash && turns[i] != turnNumber)
            turnGuardMisses++; // T14 item 2b: the guard caught a key/turn mismatch
        return null;
    }

    /**
     * Store an entry. "Deeper entry wins": a stored entry deeper than the
     * one being stored is kept (an equal-depth store replaces).
     */
    public void store(long hash, int turnNumber, int depth, int score,
                      Flag flag, Move bestMove){
        int i = index(hash);
        if (depths[i] != -1 && depths[i] > depth)
            return;
        hashes[i] = hash;
        turns[i] = turnNumber;
        depths[i] = depth;
        scores[i] = score;
        flags[i] = (byte) flag.ordinal();
        bestMoves[i] = bestMove;
    }

    /**
     * Whether a stored score is usable in the (alpha, beta) window: EXACT
     * always, LOWER only when score &gt;= beta, UPPER only when score
     * &lt;= alpha. The depth check is the caller's job.
     */
    public static boolean usable(Entry entry, int alpha, int beta){
        switch (entry.flag){
            case EXACT: return true;
            case LOWER: return entry.score >= beta;
            case UPPER: return entry.score <= alpha;
            default:    throw new IllegalStateException("unknown flag");
        }
    }

    /** Total probes since construction (bench hook). */
    public long probes(){
        return probes;
    }

    /** Total hits since construction (bench hook). */
    public long hits(){
        return hits;
    }

    /**
     * T14 item 2b: probes rejected by the turn-number guard (stored key
     * matched, turn number did not) since construction.
     */
    public long turnGuardMisses(){
        return turnGuardMisses;
    }

}
