package tests;

import client.BlackStrategy;
import client.GameAnalyzer;
import client.GameTree;
import client.WhiteStrategy;
import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T4: threat &amp; safety evaluation terms.
 *
 * These tests cover the T4 acceptance criteria:
 *   1. a ready-to-win position scores at least 3x the same position with the
 *      Pushee one row further back (tests 1-2, both colors);
 *   2. a position that hangs a Pusher to a next-ply capture scores lower than
 *      the identical position with the Pusher moved off the square (test 3);
 *   3. symmetry eval_white(p) == eval_black(mirror(p)): T8 landed, implemented
 *      as mirrorSymmetryForTheThreatTerms (the plan's "-" is for the
 *      single-perspective eval convention; this codebase scores from each
 *      color's own perspective, so a mirrored position scores EQUAL - see
 *      EvalSymmetryTest);
 *   4. all realistic evals stay within +/-8000 (and below the +/-9000
 *      forced-win cutoff) over a handful of constructed positions (test 4);
 *   5. at depth 1 the engine does not play a move that hangs a Pusher to an
 *      immediate capture when a better safe move exists (test 5).
 *
 * The tests use pure analyze() calls (plus one depth-1 engine test): no
 * iterative deepening, no timing dependence.
 *
 * Coordinates: (row, col) with row 0 = top = BLACK's goal row, row 7 = bottom
 * = WHITE's goal row, col 0 = file A. WHITE moves to increasing rows.
 *
 * @since T4
 */
public class ThreatEvalTest {

    private final GameBoard board = GameBoard.getInstance();
    private final WhiteStrategy white = new WhiteStrategy();
    private final BlackStrategy black = new BlackStrategy();
    private final GameTree whiteTree = new GameTree(Player.WHITE, Player.BLACK);

    @After
    public void cleanBoard() {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void setupWhiteToMove() {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void setupBlackToMove() {
        board.reset();
        board.setCurrentPlayer(Player.BLACK);
    }

    // --------------------------------------------------------------- acceptance 1

    /**
     * A White Pushee on row 6 with a Pusher directly behind it can push onto
     * its goal row next move: the position must score at least 3x higher than
     * the identical shape one row further back (Pushee on row 5, Pusher still
     * directly behind).
     */
    @Test
    public void readyToWinScoresAtLeastTriplePositionOneRowFurtherBack() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 6, 3);
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.updateAllLegaLMoves();
        int ready = white.analyze();

        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 4, 3);
        board.updateAllLegaLMoves();
        int furtherBack = white.analyze();

        assertTrue("ready-to-win (" + ready + ") must be >= 3x the further-back "
                + "position (" + furtherBack + ")", ready >= 3 * furtherBack);
        assertTrue("the ready-to-win bonus must dominate material differences: "
                + "gap = " + (ready - furtherBack), ready - furtherBack >= 300);
    }

    /**
     * The bonus must exist on BOTH strategies (mirror definitions): a Black
     * Pushee on row 1 with a Pusher directly behind (row 2) is one push from
     * Black's goal row (row 0).
     */
    @Test
    public void blackSideGetsTheReadyToWinBonusToo() {
        setupBlackToMove();
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 3);
        board.updateAllLegaLMoves();
        int ready = black.analyze();

        setupBlackToMove();
        board.putPiece(new Pushee(Player.BLACK), 2, 3);
        board.putPiece(new Pusher(Player.BLACK), 3, 3);
        board.updateAllLegaLMoves();
        int furtherBack = black.analyze();

        assertTrue("ready-to-win (" + ready + ") must be >= 3x the further-back "
                + "position (" + furtherBack + ")", ready >= 3 * furtherBack);
        assertTrue("the ready-to-win bonus must dominate material differences: "
                + "gap = " + (ready - furtherBack), ready - furtherBack >= 300);
    }

    // --------------------------------------------------------------- acceptance 2

    /**
     * Hanging a Pusher to a next-ply capture must score lower than the
     * identical position with the Pusher moved off the attacked square.
     *
     * The attacker is a Pushee (not a Pusher) on purpose: a Pusher attack on a
     * Pusher is always answered by a capture (the two pieces sit diagonally
     * adjacent to each other), so its net threat cancels. A Pushee attack
     * costs the full value gap (Pusher 7 vs Pushee 3 halved: -3 + 1 = -2).
     */
    @Test
    public void hangingPusherScoresLowerThanSamePusherMovedOffTheSquare() {
        // Hanging: the White Pusher on (5,3) is the only legal destination of
        // the Black Pushee (6,4) pushed by (7,5).
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pushee(Player.BLACK), 6, 4);
        board.putPiece(new Pusher(Player.BLACK), 7, 5);
        board.updateAllLegaLMoves();
        int hanging = white.analyze();

        // Identical position with the White Pusher moved off (5,3) to (4,3).
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 4, 3);
        board.putPiece(new Pushee(Player.BLACK), 6, 4);
        board.putPiece(new Pusher(Player.BLACK), 7, 5);
        board.updateAllLegaLMoves();
        int safe = white.analyze();

        assertTrue("hanging position (" + hanging + ") must score lower than the "
                + "same position with the Pusher off the square (" + safe + ")",
                hanging < safe);

        // Same property for a hanging Pushee: (5,3) is attacked by the Black
        // Pusher on (6,2); moving the Pushee to (5,2) (straight-ahead square,
        // never capturable) removes the threat.
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 4, 3);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.updateAllLegaLMoves();
        int hangingPushee = white.analyze();

        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 5, 2);
        board.putPiece(new Pusher(Player.WHITE), 4, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.updateAllLegaLMoves();
        int safePushee = white.analyze();

        assertTrue("hanging pushee position (" + hangingPushee + ") must score "
                + "lower than with the Pushee off the square (" + safePushee + ")",
                hangingPushee < safePushee);
    }

    // --------------------------------------------------------------- acceptance 4

    /**
     * Every T4 term must keep realistic positions far below the +/-9000
     * forced-win cutoff that getBestMoveQuick() uses to stop iterative
     * deepening. Checked over a handful of constructed positions, including
     * the worst case for the ready-to-win term (all 8 files ready at once).
     */
    @Test
    public void realisticEvalsStayWithinBound() {
        checkWithinBound(benchStart(), "bench start (2 pushers + 3 pushees per side)");
        checkWithinBound(full32PieceStart(), "full 32-piece start");
        checkWithinBound(threatCluster(), "mutual-capture threat cluster");
        checkWithinBound(allEightFilesReady(), "all 8 own Pushees ready to win");
    }

    private void checkWithinBound(java.util.function.IntSupplier whiteEval,
                                  String label) {
        int w = whiteEval.getAsInt();
        // Black is evaluated on the same loaded board (the position persists
        // until the @After reset).
        int b = black.analyze();
        assertTrue(label + ": white eval " + w + " out of bounds",
                Math.abs(w) <= 8000 && Math.abs(w) < 9000);
        assertTrue(label + ": black eval " + b + " out of bounds",
                Math.abs(b) <= 8000 && Math.abs(b) < 9000);
    }

    private java.util.function.IntSupplier benchStart() {
        return () -> {
            setupWhiteToMove();
            for (int col : new int[]{2, 5})
                board.putPiece(new Pusher(Player.WHITE), 0, col);
            for (int col : new int[]{1, 3, 4})
                board.putPiece(new Pushee(Player.WHITE), 1, col);
            for (int col : new int[]{2, 5})
                board.putPiece(new Pusher(Player.BLACK), 7, col);
            for (int col : new int[]{1, 3, 4})
                board.putPiece(new Pushee(Player.BLACK), 6, col);
            board.updateAllLegaLMoves();
            return white.analyze();
        };
    }

    private java.util.function.IntSupplier full32PieceStart() {
        return () -> {
            setupWhiteToMove();
            for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++) {
                board.putPiece(new Pusher(Player.WHITE), 0, j);
                board.putPiece(new Pushee(Player.WHITE), 1, j);
                board.putPiece(new Pusher(Player.BLACK), 7, j);
                board.putPiece(new Pushee(Player.BLACK), 6, j);
            }
            board.updateAllLegaLMoves();
            return white.analyze();
        };
    }

    /**
     * Three White ready-to-win teams, three Black ready-to-win teams' worth of
     * pushers, and a mesh of mutual captures on rows 5-6: every T4 term is
     * active at once for both colors.
     */
    private java.util.function.IntSupplier threatCluster() {
        return () -> {
            setupWhiteToMove();
            board.putPiece(new Pushee(Player.WHITE), 6, 1);
            board.putPiece(new Pushee(Player.WHITE), 6, 3);
            board.putPiece(new Pushee(Player.WHITE), 6, 5);
            board.putPiece(new Pusher(Player.WHITE), 5, 1);
            board.putPiece(new Pusher(Player.WHITE), 5, 3);
            board.putPiece(new Pusher(Player.WHITE), 5, 5);
            board.putPiece(new Pusher(Player.BLACK), 6, 2);
            board.putPiece(new Pusher(Player.BLACK), 6, 4);
            board.putPiece(new Pushee(Player.BLACK), 1, 3);
            board.putPiece(new Pusher(Player.BLACK), 2, 3);
            board.updateAllLegaLMoves();
            return white.analyze();
        };
    }

    /**
     * Worst case for the ready-to-win term: all 8 White Pushees on row 6 with
     * a Pusher directly behind each one (8 x READY_TO_WIN at once).
     */
    private java.util.function.IntSupplier allEightFilesReady() {
        return () -> {
            setupWhiteToMove();
            for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++) {
                board.putPiece(new Pushee(Player.WHITE), 6, j);
                board.putPiece(new Pusher(Player.WHITE), 5, j);
            }
            board.updateAllLegaLMoves();
            return white.analyze();
        };
    }

    // --------------------------------------------------------------- T4 term semantics
    // (shared GameAnalyzer helpers, unit level)

    /**
     * The ready-to-win bonus requires a LEGAL move onto the goal row: a
     * Pusher behind the Pushee is not enough when the straight goal square
     * is blocked (straight destinations must be empty) and the diagonal
     * mirrors are empty. A Pushee without any Pusher behind has no move at
     * all and is not ready either.
     */
    @Test
    public void readyToWinRequiresALegalGoalRowMove() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 6, 3);
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.BLACK), 7, 3); // blocks the straight goal square
        board.updateAllLegaLMoves();

        assertEquals(0, GameAnalyzer.readyToWinScore(board, Player.WHITE));

        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 6, 3); // no Pusher behind at all
        board.updateAllLegaLMoves();

        assertEquals(0, GameAnalyzer.readyToWinScore(board, Player.WHITE));
    }

    /**
     * A Pusher attack on a Pusher is always mutual (the pieces sit diagonally
     * adjacent to each other): -3 for the hang, +3 for the capture back, net 0.
     */
    @Test
    public void captureThreatCancelsOnMutualPusherAttacks() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.updateAllLegaLMoves();

        assertEquals(0, GameAnalyzer.captureThreatScore(board, Player.WHITE));
        // symmetric: the Black side sees the same cancellation
        assertEquals(0, GameAnalyzer.captureThreatScore(board, Player.BLACK));
    }

    /**
     * A Pusher (7) hanging to a Pushee (3) attack: -3 + 1 = -2 net, because
     * the capture back only recovers half the Pushee's value.
     */
    @Test
    public void hangingPusherToPusheeAttackerIsChargedTheValueGap() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pushee(Player.BLACK), 6, 4);
        board.putPiece(new Pusher(Player.BLACK), 7, 5); // pushes the capture
        board.updateAllLegaLMoves();

        assertEquals(-2, GameAnalyzer.captureThreatScore(board, Player.WHITE));
    }

    /**
     * A piece is counted once no matter how many enemy pieces reach it: the
     * White Pusher is attacked from BOTH (6,2) and (6,4) (-3, not -6), while
     * it answers with two captures (+3 +3). Net: +3. (Per-attacker counting
     * would give 0 here.)
     */
    @Test
    public void hangingPieceIsCountedOncePerVictim() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 4);
        board.updateAllLegaLMoves();

        assertEquals(3, GameAnalyzer.captureThreatScore(board, Player.WHITE));
    }

    /**
     * A hanging Pushee costs half its value (3/2 = 1) when the attacker
     * cannot be answered by a capture: the Pushee's only legal move is the
     * straight push to (6,3) (mirror (4,3)), and (6,2) is not a legal
     * destination for it.
     */
    @Test
    public void hangingPusheeIsChargedHalfItsValue() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 4, 3);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.updateAllLegaLMoves();

        assertEquals(-1, GameAnalyzer.captureThreatScore(board, Player.WHITE));
    }

    /**
     * Shield, capture case: the Black Pushee on (1,3) is ready to win via the
     * straight push onto (0,3); its pusher on (2,3) can be captured by the
     * White Pusher on (1,2) next ply. White scores one PUSHEE_SHIELD.
     */
    @Test
    public void shieldBonusWhenWeCanCaptureTheEnemyPusher() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 1, 2);
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 3);
        board.updateAllLegaLMoves();

        assertEquals(GameAnalyzer.PUSHEE_SHIELD,
                GameAnalyzer.pusheeShieldScore(board, Player.WHITE));
        // The Black side has no ready-to-win WHITE Pushee: no shield for it.
        assertEquals(0, GameAnalyzer.pusheeShieldScore(board, Player.BLACK));

        // Integration (T8 unified form): base -17 (W pusher 8, B pushee
        // -11, B pusher -14), capture threat cancels (-3 hang +3 capture
        // back), shield +20, mobility +1.
        assertEquals(4, white.analyze());
    }

    /**
     * Shield, occupy case: the Black Pushee on (1,3) is ready to win via the
     * diagonal push onto (0,4) (pusher on (2,2)); the White Pusher stands on
     * (2,4), the mirror square of the OTHER diagonal push (0,2), blocking it.
     */
    @Test
    public void shieldBonusWhenAFriendlyPusherOccupiesTheMirrorSquare() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 2, 4);
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 2);
        board.updateAllLegaLMoves();

        assertEquals(GameAnalyzer.PUSHEE_SHIELD,
                GameAnalyzer.pusheeShieldScore(board, Player.WHITE));

        // Integration (T8 unified form): base -16 (W pusher 9, B pushee
        // -11, B pusher -14), no capture threat, shield +20, mobility +1.
        assertEquals(5, white.analyze());
    }

    /**
     * No shield for an enemy Pushee that is not ready to win: the Black
     * Pushee on (4,3) pushes toward row 3, one row short of its goal row,
     * even with a Pusher behind and a White Pusher on a mirror square.
     */
    @Test
    public void noShieldBonusForAPusheeThatIsNotReady() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.BLACK), 4, 3);
        board.putPiece(new Pusher(Player.BLACK), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 5, 2); // on a mirror square, irrelevant
        board.updateAllLegaLMoves();

        assertEquals(0, GameAnalyzer.pusheeShieldScore(board, Player.WHITE));
    }

    /**
     * Full integration of all T4 terms in one position for both colors
     * (the "threat cluster" of the bound test): three ready-to-win teams per
     * side plus a mesh of mutual captures. Pinned exactly (T8 unified form:
     * enemy piece = -(value + own advancement + 2), own mobility /2 for both):
     *   White: base 18 (own 63 - enemy 45: pushers (6,2)/(6,4) -10 each,
     *          pusher (2,3) -14, pushee (1,3) -11), capture threat -3
     *          (9 own hang - 6 enemy hang), ready-to-win 3 x 400, shield 0,
     *          mobility 9/2 = 4  -> 1219
     *          (each White Pusher's straight square is its own Pushee, so
     *          only the two capture diagonals are legal)
     *   Black: base -38 (own 37 - enemy 75: 3 pushees -11, 3 pushers -14),
     *          capture threat +3, ready-to-win 1 x 400, shield 3 x 20 (it can
     *          capture all three pushing White Pushers), mobility 9/2 = 4
     *          -> 429.
     */
    @Test
    public void threatClusterEvaluatesToTheHandComputedValues() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 6, 1);
        board.putPiece(new Pushee(Player.WHITE), 6, 3);
        board.putPiece(new Pushee(Player.WHITE), 6, 5);
        board.putPiece(new Pusher(Player.WHITE), 5, 1);
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 5, 5);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 4);
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 3);
        board.updateAllLegaLMoves();

        assertEquals(1219, white.analyze());
        assertEquals(429, black.analyze());
    }

    /**
     * T4 acceptance 3 (implemented now that T8 landed): the T4 threat terms
     * are mirror-invariant, so on this threat-heavy position the two
     * strategies are exact mirrors: white.analyze(p) ==
     * black.analyze(mirror(p)). Mirror = swap colors, row r -> 7 - r
     * (columns unchanged). Equality, not negation: both strategies score
     * from their own color's perspective (see EvalSymmetryTest).
     */
    @Test
    public void mirrorSymmetryForTheThreatTerms() {
        // The threat cluster (same position as the pinned test above).
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 6, 1);
        board.putPiece(new Pushee(Player.WHITE), 6, 3);
        board.putPiece(new Pushee(Player.WHITE), 6, 5);
        board.putPiece(new Pusher(Player.WHITE), 5, 1);
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 5, 5);
        board.putPiece(new Pusher(Player.BLACK), 6, 2);
        board.putPiece(new Pusher(Player.BLACK), 6, 4);
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 3);
        board.updateAllLegaLMoves();
        int whiteOnP = white.analyze();

        // The mirror: White<->Black, row r -> 7 - r.
        setupBlackToMove();
        board.putPiece(new Pusher(Player.BLACK), 2, 1);
        board.putPiece(new Pusher(Player.BLACK), 2, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 5);
        board.putPiece(new Pushee(Player.BLACK), 1, 1);
        board.putPiece(new Pushee(Player.BLACK), 1, 3);
        board.putPiece(new Pushee(Player.BLACK), 1, 5);
        board.putPiece(new Pusher(Player.WHITE), 1, 2);
        board.putPiece(new Pusher(Player.WHITE), 1, 4);
        board.putPiece(new Pushee(Player.WHITE), 6, 3);
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.updateAllLegaLMoves();
        int blackOnMirror = black.analyze();

        assertEquals("T4 terms must mirror identically (T8)",
                whiteOnP, blackOnMirror);
    }

    // --------------------------------------------------------------- acceptance 5

    /**
     * Depth-1 engine check: the engine must not play a move that hangs a
     * Pusher to an immediate capture while an equal-or-better safe move
     * exists.
     *
     * White: P1 (pusher) (4,3), P2 (pusher) (4,1).
     * Black: Pushee (6,4) + Pusher (7,5) - the Pushee's only legal move is
     * the diagonal push-capture onto (5,3) (mirror rule: the right diagonal
     * push needs the Pusher behind-LEFT, on (7,5)).
     *
     * Root moves and their depth-1 scores (post-T4):
     *   S:  P2 (4,1) -> (5,1)  safe (the Black Pushee only reaches (5,3),
     *       the Black Pusher only reaches row 6): 9 + 6/2 = 12, unique best.
     *   H:  P1 (4,3) -> (5,3)  HANGS the Pusher: capture threat -2 (hang -3,
     *       capture back of the Pushee +1), and the q-search sees the Black
     *       push-capture one ply deep -> -2.
     *   all other quiet moves: 11.
     *
     * Without the T4 capture-threat term H ties S (12 = 12) and the engine
     * hangs the Pusher about half the time; the threat term makes S the
     * unique best.
     */
    @Test
    public void engineDoesNotHangPusherToImmediateCaptureAtDepth1() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 4, 3);
        board.putPiece(new Pusher(Player.WHITE), 4, 1);
        board.putPiece(new Pushee(Player.BLACK), 6, 4);
        board.putPiece(new Pusher(Player.BLACK), 7, 5);
        board.updateAllLegaLMoves();

        Move expected = new Move(board.getSquare(4, 1), board.getSquare(5, 1));
        Move best = whiteTree.getBestMoveQuick(1);

        assertNotNull(best);
        assertNotEquals("must not play into the push-capture on (5,3)",
                board.getSquare(5, 3), best.getDestination());
        assertEquals("the safe move must be the unique best",
                expected, best);
    }
}
