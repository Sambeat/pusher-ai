package tests;

import client.GameStep;
import client.GameTree;
import client.GameTreeConfig;
import client.TranspositionTable;
import game.*;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T6 — Zobrist position keys, transposition table semantics, and the
 * mandatory determinism check: with a fixed seed, the best move chosen on
 * 5 fixed positions is identical with the transposition table on and off.
 *
 * The Zobrist tests exercise the GameBoard's incremental key maintenance
 * (movePiece/undo, putPiece, reset, setCurrentPlayer, memento/revert); the
 * table tests use a small synthetic table (no board involved); the
 * determinism test searches real positions with seeded GameTrees.
 *
 * @see IMPROVEMENT_PLAN.md task T6
 */
public class TranspositionTableTest {

    private final GameBoard board = GameBoard.getInstance();

    // ------------------------------------------------------------------
    // Zobrist position keys
    // ------------------------------------------------------------------

    /**
     * The same position reached by two move orders has the same key, one
     * piece moved changes the key, and undoing restores it.
     */
    @Test
    public void samePositionTwoMoveOrdersSameKey() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 4, 1);
        board.putPiece(new Pusher(Player.WHITE), 4, 3);
        long base = board.getZobristHash();

        Move first  = new Move(board.getSquare(4, 1), board.getSquare(5, 1));
        Move second = new Move(board.getSquare(4, 3), board.getSquare(5, 3));

        board.movePiece(first);
        assertNotEquals("one piece moved must change the key",
                base, board.getZobristHash());
        board.movePiece(second);
        long keyFirstThenSecond = board.getZobristHash();
        board.undo(second);
        board.undo(first);
        assertEquals("undoing both moves must restore the key",
                base, board.getZobristHash());

        board.movePiece(second);
        board.movePiece(first);
        long keySecondThenFirst = board.getZobristHash();
        board.undo(first);
        board.undo(second);
        assertEquals(base, board.getZobristHash());

        assertEquals("same position via two move orders must have the same key",
                keyFirstThenSecond, keySecondThenFirst);
    }

    /** The side to move is part of the position key. */
    @Test
    public void sideToMoveFlipsTheKey() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 4, 2);
        long whiteToMove = board.getZobristHash();

        board.setCurrentPlayer(Player.BLACK);
        assertNotEquals(whiteToMove, board.getZobristHash());

        board.setCurrentPlayer(Player.WHITE);
        assertEquals(whiteToMove, board.getZobristHash());

        // Setting the same side to move must not change the key.
        board.setCurrentPlayer(Player.WHITE);
        assertEquals(whiteToMove, board.getZobristHash());
    }

    /**
     * The memento/revert path replaces the board at once, so it must carry
     * the key along (like the turn number): the search's timeout fallback
     * relies on the key being exact after a revert.
     */
    @Test
    public void mementoRestoresTheKey() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 4, 1);
        board.putPiece(new Pusher(Player.BLACK), 5, 2);
        GameStep snapshot = board.createMemento();

        board.movePiece(new Move(board.getSquare(4, 1), board.getSquare(5, 1)));
        board.movePiece(new Move(board.getSquare(5, 2), board.getSquare(4, 2)));
        assertNotEquals(snapshot.getZobristHash(), board.getZobristHash());
        board.revertToState(snapshot);
        assertEquals(snapshot.getZobristHash(), board.getZobristHash());
    }

    // ------------------------------------------------------------------
    // Table semantics (synthetic table, no board involved)
    // ------------------------------------------------------------------

    /**
     * Store + probe round-trip; a hash match with a different turn number
     * is treated as a miss (bounds the error from the 64-bit truncation);
     * a different key is a miss.
     */
    @Test
    public void storeLookupAndMisses() {
        TranspositionTable tt = new TranspositionTable(1 << 8);

        assertNull(tt.probe(0x1234L, 7));

        tt.store(0x1234L, 7, 3, 42, TranspositionTable.Flag.EXACT, null);

        TranspositionTable.Entry entry = tt.probe(0x1234L, 7);
        assertNotNull(entry);
        assertEquals(0x1234L, entry.hash());
        assertEquals(3, entry.depth());
        assertEquals(42, entry.score());
        assertEquals(TranspositionTable.Flag.EXACT, entry.flag());
        assertNull(entry.bestMove());

        assertNull(tt.probe(0x1234L, 8));
        assertNull(tt.probe(0x9999L, 7));
    }

    /** Replacement is "deeper entry wins": a shallower store is ignored. */
    @Test
    public void deeperEntryWins() {
        TranspositionTable tt = new TranspositionTable(1 << 8);
        long key = 0xABC0000000001L;

        tt.store(key, 5, 4, 100, TranspositionTable.Flag.EXACT, null);
        tt.store(key, 5, 2, 55, TranspositionTable.Flag.UPPER, null);
        assertEquals("a shallower entry must not replace a deeper one",
                100, tt.probe(key, 5).score());

        tt.store(key, 5, 6, 77, TranspositionTable.Flag.LOWER, null);
        assertEquals("a deeper entry replaces the older one",
                77, tt.probe(key, 5).score());
    }

    /**
     * T14 item 2c: the full replacement contract — "deeper entry wins" is
     * compared on STORED depth only, an equal-depth store replaces
     * (last write wins, deterministic — it cannot oscillate), and a deeper
     * entry, once stored, survives any number of shallower stores. This is
     * what keeps the table monotonic in depth along a search's lifetime.
     */
    @Test
    public void replacementComparesOnStoredDepthAndNeverOscillates() {
        TranspositionTable tt = new TranspositionTable(1 << 8);
        long key = 0xDEF0000000002L;

        tt.store(key, 3, 5, 10, TranspositionTable.Flag.EXACT, null);
        // Equal depth: replaces (last write wins).
        tt.store(key, 3, 5, 20, TranspositionTable.Flag.EXACT, null);
        assertEquals(20, tt.probe(key, 3).score());
        // A deeper entry replaces the equal-depth one...
        tt.store(key, 3, 7, 30, TranspositionTable.Flag.EXACT, null);
        assertEquals(30, tt.probe(key, 3).score());
        // ...and survives repeated shallower stores (no oscillation,
        // no eviction of the deeper entry).
        for (int i = 0; i < 4; i++)
            tt.store(key, 3, 4, 40 + i, TranspositionTable.Flag.UPPER, null);
        assertEquals(30, tt.probe(key, 3).score());
        assertEquals(7, tt.probe(key, 3).depth());
        // A still-deeper entry takes over.
        tt.store(key, 3, 9, 50, TranspositionTable.Flag.LOWER, null);
        assertEquals(50, tt.probe(key, 3).score());
        assertEquals(9, tt.probe(key, 3).depth());
    }

    /**
     * Window compatibility of the bound flags: EXACT is always usable,
     * LOWER only when score >= beta, UPPER only when score <= alpha.
     */
    @Test
    public void flagUsability() {
        TranspositionTable tt = new TranspositionTable(1 << 8);
        tt.store(1L, 0, 3, 50, TranspositionTable.Flag.EXACT, null);
        tt.store(2L, 0, 3, 50, TranspositionTable.Flag.LOWER, null);
        tt.store(3L, 0, 3, 50, TranspositionTable.Flag.UPPER, null);

        assertTrue(TranspositionTable.usable(tt.probe(1L, 0), 0, 10));
        assertTrue(TranspositionTable.usable(tt.probe(1L, 0), 60, 70));

        assertTrue(TranspositionTable.usable(tt.probe(2L, 0), 0, 49));
        assertFalse(TranspositionTable.usable(tt.probe(2L, 0), 51, 60));

        assertTrue(TranspositionTable.usable(tt.probe(3L, 0), 50, 100));
        assertFalse(TranspositionTable.usable(tt.probe(3L, 0), 49, 100));
    }

    // ------------------------------------------------------------------
    // Determinism: TT on vs off (acceptance 2 — mandatory)
    // ------------------------------------------------------------------

    /**
     * With a fixed seed, the best move chosen on 5 fixed positions is
     * identical with the transposition table on and off. Each position has
     * a unique best move (asserted against the expected move first, so a
     * broken premise fails loudly instead of silently); a disagreement
     * between the TT-on and TT-off searches can only come from
     * flag-handling bugs.
     */
    @Test
    public void determinismTTOnVsOff() {
        final long seed = 42L;
        final int depth = 4;

        for (int i = 0; i < 5; i++) {
            resetBoard();
            buildPosition(i);
            Move expected = expectedMove(i);

            GameTree ttOn = new GameTree(maxPlayer(i), minPlayer(i),
                    new GameTreeConfig(GameTree.MAX_TIME_MILLIS, seed, true,
                            true, true, true, true, true, true, true));
            GameTree ttOff = new GameTree(maxPlayer(i), minPlayer(i),
                    new GameTreeConfig(GameTree.MAX_TIME_MILLIS, seed, false,
                            true, true, true, true, true, true, true));

            Move withTT = ttOn.getBestMoveQuick(depth);
            Move withoutTT = ttOff.getBestMoveQuick(depth);

            assertEquals("position " + i
                    + ": unique best move not found (test premise broken)",
                    expected, withTT);
            assertEquals("position " + i + ": TT on/off disagree",
                    withTT, withoutTT);
        }
    }

    private Player maxPlayer(int i) {
        return i == 4 ? Player.BLACK : Player.WHITE;
    }

    private Player minPlayer(int i) {
        return maxPlayer(i).other();
    }

    private void resetBoard() {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    /**
     * The 5 fixed positions, each with a unique best move:
     *   0: White's Pushee can be pushed onto the goal row now — the only
     *      winning move.
     *   1: same, plus Black threatens a one-move win of its own: White
     *      must win NOW.
     *   2: White can capture the enemy Pusher (7) or the enemy Pushee (3);
     *      capturing the Pusher leaves Black with no legal move (a win).
     *   3: both sides ready to win; White moves first.
     *   4: the same ready-to-win shape, Black to move.
     */
    private void buildPosition(int i) {
        switch (i) {
            case 0:
                board.putPiece(new Pushee(Player.WHITE), 6, 3);
                board.putPiece(new Pusher(Player.WHITE), 5, 3);
                board.putPiece(new Pusher(Player.WHITE), 3, 5);
                board.putPiece(new Pusher(Player.BLACK), 4, 6);
                break;
            case 1:
                buildPosition(0);
                board.putPiece(new Pusher(Player.BLACK), 1, 4);
                break;
            case 2:
                board.putPiece(new Pusher(Player.WHITE), 4, 2);
                board.putPiece(new Pusher(Player.BLACK), 5, 3);
                board.putPiece(new Pushee(Player.BLACK), 5, 1);
                break;
            case 3:
                board.putPiece(new Pushee(Player.WHITE), 6, 3);
                board.putPiece(new Pusher(Player.WHITE), 5, 3);
                board.putPiece(new Pushee(Player.BLACK), 1, 4);
                board.putPiece(new Pusher(Player.BLACK), 2, 4);
                break;
            default:
                board.putPiece(new Pushee(Player.BLACK), 1, 4);
                board.putPiece(new Pusher(Player.BLACK), 2, 4);
                board.putPiece(new Pusher(Player.WHITE), 4, 2);
                board.setCurrentPlayer(Player.BLACK);
        }
        // Pieces are placed one by one: a piece placed before the pieces
        // around it keeps a stale cached legal-move list (engine invariant
        // 3), so refresh every list once the position is complete.
        board.updateAllLegaLMoves();
    }

    private Move expectedMove(int i) {
        switch (i) {
            case 0:
            case 1:
            case 3:
                return new Move(board.getSquare(6, 3), board.getSquare(7, 3));
            case 2:
                return new Move(board.getSquare(4, 2), board.getSquare(5, 3));
            default:
                return new Move(board.getSquare(1, 4), board.getSquare(0, 4));
        }
    }

}
