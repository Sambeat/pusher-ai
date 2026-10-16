package tests;

import client.BlackStrategy;
import client.GameAnalyzer;
import client.WhiteStrategy;
import game.GameBoard;
import game.Piece;
import game.Player;
import game.Pushee;
import game.Pusher;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * T8: symmetric evaluations.
 *
 * The two strategies must be exact mirrors of each other: a position and its
 * mirror (swap colors, map row r to 7 - r, columns unchanged) must score
 * identically from each color's perspective.
 *
 * Sign convention: this codebase scores from the MAX player's perspective
 * (GameTree maximizes strategy.analyze()), so each strategy returns a
 * POSITIVE score for its own color's advantage. Under that convention a
 * perfectly mirrored position scores EQUAL for the two colors, not opposite:
 * a lone own Pusher is worth +13 (value + advancement) to its owner in both
 * positions. The plan's acceptance formula "white.analyze(p) ==
 * -black.analyze(mirror(p))" is written for the single-perspective
 * convention (one function scoring from White's view, negated for Black);
 * with own material worth a positive value it cannot hold here (it would
 * require a lone own Pusher to score -13). The assertion below is the same
 * mirror symmetry under this codebase's convention:
 *
 *     white.analyze(p) == black.analyze(mirror(p))
 *
 * T4's threat terms (ready-to-win, capture danger, pushee shield) were
 * implemented in shared GameAnalyzer helpers parameterized by the player;
 * these tests verify they mirror identically (they do only if the whole
 * evaluation is unified, which T8 does via GameAnalyzer.analyzeFor).
 *
 * Coordinates: (row, col) with row 0 = top = BLACK's goal row, row 7 = bottom
 * = WHITE's goal row, col 0 = file A.
 *
 * @since T8
 */
public class EvalSymmetryTest {

    /** Piece description: {player, type, row, col}. player: 0=WHITE 1=BLACK.
     *  type: 0=Pushee 1=Pusher. Used to rebuild a position (and its mirror)
     *  on the singleton board. */
    private static final int WHITE = 0;
    private static final int BLACK = 1;
    private static final int PUSHEE = 0;
    private static final int PUSHER = 1;

    private final GameBoard board = GameBoard.getInstance();
    private final WhiteStrategy white = new WhiteStrategy();
    private final BlackStrategy black = new BlackStrategy();

    @After
    public void cleanBoard(){
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    // --------------------------------------------------------------- acceptance: mirror symmetry

    /**
     * T8 acceptance: for 5+ hand-built positions (edge files, ready-to-win
     * shapes, captures available, asymmetric piece counts) the two
     * strategies are exact mirrors: white.analyze(p) ==
     * black.analyze(mirror(p)). The singleton makes this a
     * set-up/evaluate/set-up/evaluate sequence per position.
     */
    @Test
    public void strategiesAreExactMirrorsOfEachOther(){
        mirrorHolds(benchStart(), "bench start (2 pushers + 3 pushees per side)");
        mirrorHolds(full32PieceStart(), "full 32-piece start");
        mirrorHolds(edgeFiles(), "edge-file pieces (Pushees on A, Pushers on H)");
        mirrorHolds(readyToWinShapes(), "ready-to-win shapes on both sides");
        mirrorHolds(capturesAvailable(), "mutual-capture shapes");
        mirrorHolds(asymmetricCounts(), "asymmetric piece counts (2 vs 3)");
    }

    /**
     * T15: T8's mirror invariant must survive the threat-terms flag — the
     * same positions with the T4 terms OFF (what the t2/t3 engine levels
     * evaluate) are still exact mirrors. The shapes are the same: several
     * have non-zero threat terms, so the flag provably removes a term from
     * the mirrored comparison, not just a no-op.
     */
    @Test
    public void strategiesAreExactMirrorsWithThreatTermsOff(){
        int[][][] positions = {benchStart(), full32PieceStart(), edgeFiles(),
                readyToWinShapes(), capturesAvailable(), asymmetricCounts()};
        String[] labels = {
                "bench start (2 pushers + 3 pushees per side)",
                "full 32-piece start",
                "edge-file pieces (Pushees on A, Pushers on H)",
                "ready-to-win shapes on both sides",
                "mutual-capture shapes",
                "asymmetric piece counts (2 vs 3)",
        };
        // Of the six positions, only the ready-to-win shapes carry T4
        // terms (White: READY_TO_WIN = +400); the rest score 0 there, so
        // the off/on comparison is a genuine term removal in exactly that
        // case (the other non-zero shapes are pinned by
        // EngineLevelTest.withThreatTermsOffTheEvalIsExactlyThePreT4Formula).
        boolean[] termsExpectedNonZero = {false, false, false, true, false,
                false};

        for (int i = 0; i < positions.length; i++) {
            int[][] pieces = positions[i];
            place(pieces, false);
            if (termsExpectedNonZero[i]) {
                int removed = GameAnalyzer.threatTerms(board, Player.WHITE);
                assertTrue(labels[i] + ": the threat terms must be non-"
                        + "zero here (removed=" + removed + ")",
                        removed != 0);
            }
            mirrorHolds(pieces, labels[i], false);
        }
    }

    /** Set up p (White to move), evaluate as White; rebuild mirror(p) (Black
     *  to move), evaluate as Black; the two scores must be equal. */
    private void mirrorHolds(int[][] pieces, String label){
        mirrorHolds(pieces, label, true);
    }

    /** mirrorHolds with the T4 threat terms on (the T8 check) or off
     *  (T15's t2/t3 evaluation). */
    private void mirrorHolds(int[][] pieces, String label,
                             boolean threatTerms){
        place(pieces, false);
        int whiteScore = white.analyze(threatTerms);

        place(pieces, true);
        int blackScore = black.analyze(threatTerms);

        assertEquals(label + " (threat terms " + (threatTerms ? "on" : "off")
                        + "): eval_white(p) " + whiteScore
                        + " must equal eval_black(mirror(p)) " + blackScore,
                whiteScore, blackScore);
    }

    /** Bench position (T7): 2 Pushers + 3 Pushees per side, files C/F and B/D/E. */
    private static int[][] benchStart(){
        int[][] p = new int[10][4];
        int i = 0;
        for (int col : new int[]{2, 5})
            p[i++] = new int[]{WHITE, PUSHER, 0, col};
        for (int col : new int[]{1, 3, 4})
            p[i++] = new int[]{WHITE, PUSHEE, 1, col};
        for (int col : new int[]{2, 5})
            p[i++] = new int[]{BLACK, PUSHER, 7, col};
        for (int col : new int[]{1, 3, 4})
            p[i++] = new int[]{BLACK, PUSHEE, 6, col};
        return p;
    }

    private static int[][] full32PieceStart(){
        int[][] p = new int[32][4];
        int i = 0;
        for (int j = 0; j < GameBoard.GAME_BOARD_LENGTH; j++){
            p[i++] = new int[]{WHITE, PUSHER, 0, j};
            p[i++] = new int[]{WHITE, PUSHEE, 1, j};
            p[i++] = new int[]{BLACK, PUSHER, 7, j};
            p[i++] = new int[]{BLACK, PUSHEE, 6, j};
        }
        return p;
    }

    /** Edge files: own Pushee on file A (worth 0), own Pusher on file H (full
     *  value), plus one enemy piece on each edge file at different depths. */
    private static int[][] edgeFiles(){
        return new int[][]{
                {WHITE, PUSHEE, 3, 0},  // edge-file Pushee: worth nothing
                {WHITE, PUSHER, 2, 7},  // edge-file Pusher: full value + advancement
                {BLACK, PUSHER, 5, 0},
                {BLACK, PUSHEE, 4, 7},
        };
    }

    /** A ready-to-win team per side, on different files (White row 6, Black
     *  row 1, each with the pushing Pusher directly behind). */
    private static int[][] readyToWinShapes(){
        return new int[][]{
                {WHITE, PUSHEE, 6, 3},
                {WHITE, PUSHER, 5, 3},
                {BLACK, PUSHEE, 1, 4},
                {BLACK, PUSHER, 2, 4},
        };
    }

    /** Two mutual-capture Pusher pairs (diagonally adjacent, opposite colors)
     *  plus a Pushee on each side with no Pusher behind it (immobile). */
    private static int[][] capturesAvailable(){
        return new int[][]{
                {WHITE, PUSHER, 5, 3},
                {BLACK, PUSHER, 6, 2},   // mutual capture with (5,3)
                {WHITE, PUSHER, 3, 5},
                {BLACK, PUSHER, 4, 4},   // mutual capture with (3,5)
                {WHITE, PUSHEE, 1, 6},   // no Pusher behind: no moves
                {BLACK, PUSHEE, 6, 1},
        };
    }

    /** Piece counts differ per side (the defensive premium is per enemy
     *  piece, so the mirror must agree despite the asymmetry). */
    private static int[][] asymmetricCounts(){
        return new int[][]{
                {WHITE, PUSHER, 2, 5},
                {WHITE, PUSHEE, 4, 1},   // no Pusher behind: no moves
                {BLACK, PUSHER, 7, 3},
                {BLACK, PUSHER, 6, 7},
                {BLACK, PUSHEE, 5, 6},   // pushed by (6,7): one move
        };
    }

    // --------------------------------------------------------------- the unified form, pinned

    /**
     * The mirror test alone cannot distinguish "unified correctly" from
     * "symmetric but with the wrong terms" (e.g. a premium dropped on both
     * sides would still mirror). These pins fix the exact T8 form:
     * own piece value + advancement, enemy piece -(value + advancement + 2),
     * own mobility / 2, edge-file Pushee = 0.
     */
    @Test
    public void unifiedFormIsPinnedExactly(){
        // Lone White Pusher on file H: 7 + advancement 3 + mobility (2/2) = 11.
        place(new int[][]{{WHITE, PUSHER, 3, 7}}, false);
        assertEquals(11, white.analyze());

        // Lone Black Pusher on file H: 7 + (7-3) + mobility (2/2) = 12.
        // 12, not 11: the premium only applies to ENEMY pieces, so a lone
        // own piece scores its bare potential plus mobility.
        place(new int[][]{{BLACK, PUSHER, 3, 7}}, false);
        assertEquals(12, black.analyze());

        // Mixed position, White to move:
        //   own:      W Pusher (3,3)                    7 + 3  = +10
        //   enemy:    B Pusher (6,2)                    -(7 + (7-6) + 2) = -10
        //   mobility: 3 own moves / 2 = +1; threats/ready/shield: none
        //   total = 1.
        place(new int[][]{{WHITE, PUSHER, 3, 3}, {BLACK, PUSHER, 6, 2}}, false);
        assertEquals(1, white.analyze());

        // Its mirror (Black to move): own B Pusher (4,3) 7 + (7-4) = +10;
        // enemy W Pusher (1,5) -(7 + 1 + 2) = -10; mobility 3/2 = +1 -> 1.
        place(new int[][]{{WHITE, PUSHER, 3, 3}, {BLACK, PUSHER, 6, 2}}, true);
        assertEquals(1, black.analyze());

        // T4 terms mirror too: the shield position (White Pusher (1,2) can
        // capture the Black Pusher (2,3) that pushes the ready Black Pushee
        // (1,3)):
        //   own:      W Pusher (1,2)              7 + 1 = +8
        //   enemy:    B Pushee (1,3)  -(3 + 6 + 2) = -11
        //             B Pusher (2,3)  -(7 + 5 + 2) = -14
        //   capture threat: +3 (W takes the pusher) - 3 (B takes W back) = 0
        //   shield: the enemy pusher (2,3) can be captured -> +20
        //   mobility: 3/2 = +1  ->  -17 + 20 + 1 = 4
        int[][] shield = {{WHITE, PUSHER, 1, 2}, {BLACK, PUSHEE, 1, 3}, {BLACK, PUSHER, 2, 3}};
        place(shield, false);
        assertEquals(4, white.analyze());

        place(shield, true);
        assertEquals(4, black.analyze());

        // The defensive premium: a lone White Pusher (3,3) has no enemy
        // pieces, so the premium never applies: 7 + 3 + mobility (3/2 = 1)
        // = 11. Add a far-away Black Pusher on (7,7) and White's eval drops
        // by exactly value + advancement + DEFENSIVE_PREMIUM = 7 + 0 + 2 = 9
        // (White's own mobility is unchanged, no threats): 11 - 9 = 2.
        place(new int[][]{{WHITE, PUSHER, 3, 3}}, false);
        assertEquals(11, white.analyze());

        place(new int[][]{{WHITE, PUSHER, 3, 3}, {BLACK, PUSHER, 7, 7}}, false);
        assertEquals(2, white.analyze());
    }

    /**
     * The evaluation must not depend on the side to move (all terms use the
     * pieces' cached legal-move lists and the max player, never the turn
     * player). This is what makes "evaluate p, then evaluate mirror(p)"
     * well-defined on the singleton board.
     */
    @Test
    public void evaluationDoesNotDependOnSideToMove(){
        int[][] p = {{WHITE, PUSHER, 2, 5}, {WHITE, PUSHEE, 4, 1},
                     {BLACK, PUSHER, 7, 3}, {BLACK, PUSHER, 6, 7},
                     {BLACK, PUSHEE, 5, 6}};

        place(p, false);
        board.setCurrentPlayer(Player.WHITE);
        board.updateAllLegaLMoves();
        int whiteOnWhiteTurn = white.analyze();

        place(p, false);
        board.setCurrentPlayer(Player.BLACK);
        board.updateAllLegaLMoves();
        int whiteOnBlackTurn = white.analyze();

        assertEquals("White's eval must not change with the side to move",
                whiteOnWhiteTurn, whiteOnBlackTurn);

        place(p, false);
        board.setCurrentPlayer(Player.WHITE);
        board.updateAllLegaLMoves();
        int blackOnWhiteTurn = black.analyze();

        place(p, false);
        board.setCurrentPlayer(Player.BLACK);
        board.updateAllLegaLMoves();
        int blackOnBlackTurn = black.analyze();

        assertEquals("Black's eval must not change with the side to move",
                blackOnWhiteTurn, blackOnBlackTurn);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Place the described pieces on the board (mirroring player, row ->
     * 7 - row when mirrored), side to move = WHITE unmirrored / BLACK
     * mirrored, and refresh all cached legal-move lists (threat terms rely
     * on them outside of search).
     */
    private void place(int[][] pieces, boolean mirrored){
        board.reset();
        board.setCurrentPlayer(mirrored ? Player.BLACK : Player.WHITE);

        for (int[] piece : pieces){
            int player = mirrored ? piece[0] ^ 1 : piece[0];
            int row = mirrored ? GameBoard.GAME_BOARD_LENGTH - 1 - piece[2] : piece[2];
            int col = piece[3];
            Piece p = piece[1] == PUSHER
                    ? new Pusher(player == WHITE ? Player.WHITE : Player.BLACK)
                    : new Pushee(player == WHITE ? Player.WHITE : Player.BLACK);
            board.putPiece(p, row, col);
        }
        board.updateAllLegaLMoves();
    }
}
