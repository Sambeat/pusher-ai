package tests;

import client.GameTree;
import client.GameTreeConfig;
import game.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * T16 — move ordering: goal proximity of quiet moves
 * (see IMPROVEMENT_PLAN.md, task T16).
 *
 * T16 part (a) — the internal-node TT best-move handoff from any stored
 * depth — already landed in T14 (the ttMoveHandoff flag, pinned in
 * GameTreeTest). T16 lands (b): with orderingEnabled (the default),
 * orderMoves buckets the quiet moves by goal proximity — fewer rows from
 * the destination row to this side's goal row first — and shuffles only
 * WITHIN each bucket. These tests pin:
 *   - ordering is ORDERING only: on vs off return identical root scores
 *     (a full window is exact either way);
 *   - the performance pin: full board at depth 4, ordering on visits
 *     strictly fewer nodes than off, with TT off AND with TT on;
 *   - the pinned shuffle survives: equidistant quiet moves share a bucket
 *     and are still chosen randomly (the
 *     testRandomnessForMoreThanOneMove geometry, ordering on);
 *   - the T14 ID handoff still fires with the new ordering in place: on a
 *     full-board position, internal nodes whose ordered first move came
 *     from a TT entry (getTtOrderHits) &gt; 0 at ID levels 3–4 (level 2
 *     finds nothing: the level-1 search stores only its root entry).
 */
public class MoveOrderingTest {

    private static final long BUDGET = GameTree.MAX_TIME_MILLIS;
    private static final long SEED = 42L;

    private final GameBoard board = GameBoard.getInstance();

    @AfterEach
    public void cleanBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void resetBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    /**
     * T16: goal-proximity ordering of quiet moves is pure ORDERING — on vs
     * off (the pre-T16 plain shuffle) return the identical root score at
     * every depth on fixed positions. A full window is exact either way,
     * and the score-return gate in alphaBeta is independent of the
     * ordering, so any leak shows up here. TT on, aspiration and PVS off
     * (a single full-window search), the T14 handoff on (the default the
     * production engine runs with), so the on side is the exact production
     * order: TT first, MVV-LVA captures, then proximity-bucketed quiet
     * moves.
     */
    @Test
    public void orderingIsOrderingOnlyScoresUnchanged(){
        final int[] depths = {2, 3, 4};
        final String[] positions = {"race", "tactical", "tacticalBlack",
                "captureRace", "defaultStart"};

        for (String which : positions){
            for (int depth : depths){
                setT16Position(which);
                int off = t16Tree(which, SEED, false)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                setT16Position(which);
                int on = t16Tree(which, SEED, true)
                        .debugSearchScore(depth, Integer.MIN_VALUE,
                                Integer.MAX_VALUE);
                assertEquals(off, on, which + " depth " + depth
                        + ": proximity ordering must not change the value");
            }
        }
    }

    /**
     * T16: the performance pin. On the full 32-piece board at depth 4, the
     * proximity-ordered tree visits strictly fewer nodes than the pre-T16
     * shuffled one at the same score — both with the TT off (the ordering
     * alone does the work) and with the TT on (ordering and handoff
     * compose). Aspiration and PVS are off so the reduction is attributable
     * to ordering (the same isolation the T14 handoff pin uses).
     */
    @Test
    public void orderingReducesNodesOnFullBoard(){
        final int depth = 4;
        int[] scores = new int[4];
        long[] nodes = new long[4];
        int i = 0;
        for (boolean tt : new boolean[]{false, true}){
            for (boolean ordering : new boolean[]{false, true}){
                setT16Position("defaultStart");
                Player max = board.getCurrentPlayer();
                GameTree tree = new GameTree(max, max.other(),
                        new GameTreeConfig(BUDGET, SEED, tt, false, false,
                                true, true, true, true, ordering));
                scores[i] = tree.debugSearchScore(depth,
                        Integer.MIN_VALUE, Integer.MAX_VALUE);
                nodes[i] = tree.getNodeCount();
                i++;
            }
        }
        assertEquals(scores[0], scores[1], "TT off: ordering must not change the value");
        assertTrue(nodes[1] < nodes[0], "TT off: the proximity ordering must visit fewer nodes "
                + "(off=" + nodes[0] + ", on=" + nodes[1] + ")");
        assertEquals(scores[2], scores[3], "TT on: ordering must not change the value");
        assertTrue(nodes[3] < nodes[2], "TT on: the proximity ordering must visit fewer nodes "
                + "(off=" + nodes[2] + ", on=" + nodes[3] + ")");
    }

    /**
     * T16: the pinned shuffle survives the bucketing. The
     * testRandomnessForMoreThanOneMove geometry: two white pushers with
     * only equidistant quiet moves (every legal destination is row 1), so
     * all quiet moves share ONE proximity bucket and the within-bucket
     * shuffle must keep them random — over repeated searches a side must
     * not stick to the first possible move. (The original pin in
     * GameTreeTest uses the default constructor, which is now ordering ON;
     * this one drives the same path through the explicit T16 flag.)
     */
    @Test
    public void equidistantQuietMovesStillShuffleWithOrderingOn(){
        final GameTree tree = new GameTree(Player.WHITE, Player.BLACK,
                new GameTreeConfig(BUDGET, null, true, false, false,
                        true, true, true, true, true));
        int timesPiece1Moved = 0;
        int timesPiece2Moved = 0;

        for (int i = 0; i < 5; i++){
            resetBoard();
            board.putPiece(new Pusher(Player.WHITE), 0, 1);
            board.putPiece(new Pusher(Player.WHITE), 0, 2);
            board.updateAllLegaLMoves();

            Move chosenMove = tree.getBestMoveQuick();

            if (chosenMove.pieceLocation() == board.getSquare(0, 1))
                timesPiece1Moved++;
            else
                timesPiece2Moved++;
        }

        assertTrue(timesPiece2Moved > 0, "Always chose first possible move... (the within-bucket "
                + "shuffle must survive the proximity bucketing)");
    }

    /**
     * T16: the T14 ID handoff still fires with the new ordering in place.
     * Full board, all production features on (TT, handoff, quiescence,
     * threat terms, repetition, PVS, proximity ordering), iterative
     * deepening: as the table warms, the previous level's entries must
     * reach the ordering at internal nodes — the instrumented count of
     * internal nodes whose ordered FIRST move came from a TT entry
     * (getTtOrderHits, the existing T14 hook) is &gt; 0 at levels 3 and 4.
     * (Level 2 is legitimately 0: the level-1 search stores its only entry
     * at the root — q-nodes never store — and the root entry applies to the
     * root only, so level 2's internal nodes find nothing to hand off.)
     */
    @Test
    public void ttHandoffStillFiresWithOrderingOn(){
        setT16Position("defaultStart");
        Player max = board.getCurrentPlayer();
        GameTree tree = new GameTree(max, max.other(), BUDGET, SEED);
        for (int depth = 1; depth <= 4; depth++){
            tree.debugSearchScore(depth, Integer.MIN_VALUE,
                    Integer.MAX_VALUE);
            if (depth >= 3)
                assertTrue(tree.getTtOrderHits() > 0, "the TT handoff must reach internal nodes at "
                        + "level " + depth + " with ordering on "
                        + "(ttOrderHits=" + tree.getTtOrderHits() + ")");
        }
    }

    // ------------------------------------------------------------------
    // T16 test positions (geometries from AspirationPvsTest, via T14/T15)
    // ------------------------------------------------------------------

    private void setT16Position(String which){
        resetBoard();
        switch (which){
            case "race":
                board.putPiece(new Pusher(Player.WHITE), 3, 0);
                board.putPiece(new Pusher(Player.WHITE), 4, 2);
                board.putPiece(new Pusher(Player.BLACK), 4, 1);
                board.putPiece(new Pusher(Player.BLACK), 3, 3);
                board.putPiece(new Pushee(Player.BLACK), 5, 2);
                break;
            case "tactical":
                board.putPiece(new Pusher(Player.WHITE), 3, 0);
                board.putPiece(new Pusher(Player.WHITE), 3, 2);
                board.putPiece(new Pushee(Player.WHITE), 4, 1);
                board.putPiece(new Pusher(Player.BLACK), 5, 0);
                board.putPiece(new Pusher(Player.BLACK), 5, 3);
                board.putPiece(new Pushee(Player.BLACK), 4, 2);
                board.putPiece(new Pusher(Player.BLACK), 6, 1);
                break;
            case "tacticalBlack":
                setT16Position("tactical");
                board.setCurrentPlayer(Player.BLACK);
                break;
            case "captureRace":
                board.putPiece(new Pusher(Player.WHITE), 3, 1);
                board.putPiece(new Pushee(Player.WHITE), 4, 2);
                board.putPiece(new Pusher(Player.WHITE), 5, 4);
                board.putPiece(new Pusher(Player.BLACK), 5, 3);
                board.putPiece(new Pusher(Player.BLACK), 6, 3);
                board.putPiece(new Pusher(Player.BLACK), 6, 2);
                break;
            case "defaultStart":
                for (int c = 0; c < GameBoard.GAME_BOARD_LENGTH; c++){
                    board.putPiece(new Pusher(Player.WHITE), 0, c);
                    board.putPiece(new Pushee(Player.WHITE), 1, c);
                    board.putPiece(new Pusher(Player.BLACK), 7, c);
                    board.putPiece(new Pushee(Player.BLACK), 6, c);
                }
                break;
            default:
                throw new IllegalArgumentException(which);
        }
        board.updateAllLegaLMoves();
    }

    /** A fresh tree for the T16 score-invariance / node tests: max = side
     *  to move, TT on, aspiration and PVS off (a single full-window
     *  search), the T14 handoff on, quiescence and threat terms on,
     *  repetition on — ordering per the parameter. */
    private GameTree t16Tree(String which, long seed, boolean ordering){
        Player max = board.getCurrentPlayer();
        return new GameTree(max, max.other(),
                new GameTreeConfig(BUDGET, seed, true, false, false,
                        true, true, true, true, ordering));
    }
}
