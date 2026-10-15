package tests;

import client.GameTree;
import client.PusherApplication;
import game.GameBoard;
import game.Move;
import game.Piece;
import game.Player;
import game.Pushee;
import game.Pusher;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T10: the endless-play guard.
 *
 * Engine level: GameTree keeps a bounded history of recent piece
 * arrangements (T6's Zobrist key with the side-to-move bit masked, so the
 * identifier is the arrangement alone) and subtracts REPETITION_PENALTY from
 * a root move's score when the move would return the game to a seen
 * arrangement — enough to break a shuffle, never enough to sacrifice a
 * forced win (terminal scores are ~±2^31).
 *
 * App level: PusherApplication counts plies per game and logs a warning at
 * MAX_PLIES but keeps playing (the protocol has no known draw command).
 */
public class RepetitionTest {

    private static final GameBoard board = GameBoard.getInstance();

    /** Plenty of margin over GameTree.MAX_TIME_MILLIS (4950). */
    private static final long RESPONSE_TIMEOUT_MILLIS = 30_000;

    /**
     * Clear the singleton and place {row, col, kind} pieces (the protocol
     * encoding: 1 = Black Pushee, 2 = Black Pusher, 3 = White Pushee,
     * 4 = White Pusher).
     */
    private static void setup(int[][] pieces) {
        board.reset();
        for (int[] p : pieces) {
            Piece piece;
            switch (p[2]) {
            case 1:
                piece = new Pushee(Player.BLACK);
                break;
            case 2:
                piece = new Pusher(Player.BLACK);
                break;
            case 3:
                piece = new Pushee(Player.WHITE);
                break;
            default:
                piece = new Pusher(Player.WHITE);
                break;
            }
            board.putPiece(piece, p[0], p[1]);
        }
        board.updateAllLegaLMoves();
    }

    @Test
    public void repeatingRootMoveIsPenalizedSoANonRepeatingMoveIsChosen() {
        //White: Pusher A5 (4,0), Pusher C4 (3,2). Black: Pusher H2 (6,7) —
        //inert (keeps Black from being stalemated, cannot reach the White
        //pieces, is not itself capturable).
        //
        //Depth-1 root scores are the child positions' static evals (the min
        //nodes are capture-less q-nodes, so they return stand-pat), from
        //White's perspective (value + advancement, enemy -(value +
        //advancement + 2), own mobility / 2):
        //  A5->B6 (5,1): (7+5) + (7+3) - (7+1+2) + (3+3)/2 = 22 - 10 + 3 = 15
        //  A5->A6 (5,0): 22 - 10 + (2+3)/2 = 14
        //  C4->B5/C5/D5: 22 - 10 + (3+2)/2 = 14 each
        //The natural best move is A5->B6, unique. Injecting its child
        //position into the repetition history must make the engine choose a
        //non-repeating move instead (15 - 5 = 10 < 14).
        setup(new int[][]{
                {4, 0, 4},   //White Pusher A5
                {3, 2, 4},   //White Pusher C4
                {6, 7, 2},   //Black Pusher H2
        });

        Move repeating = new Move(board.getSquare(4, 0), board.getSquare(5, 1));
        board.movePiece(repeating);
        long seenArrangement = GameTree.arrangementHash(
                board.getZobristHash(), board.getCurrentPlayer());
        board.undo(repeating);

        //Sanity: with no repetition history the engine plays the repeating
        //move (it is the unique natural best move).
        GameTree fresh = new GameTree(Player.WHITE, Player.BLACK);
        assertEquals("sanity: the natural best move is the repeating one",
                repeating, fresh.getBestMoveQuick(1));

        //With the child position in the history, a non-repeating move must
        //be chosen.
        GameTree tree = new GameTree(Player.WHITE, Player.BLACK);
        List<Long> history = new ArrayList<>();
        history.add(seenArrangement);
        tree.setRepetitionHistory(history);
        Move chosen = tree.getBestMoveQuick(1);
        assertNotNull(chosen);
        assertNotEquals("a non-repeating move exists and must be preferred",
                repeating, chosen);
    }

    @Test
    public void aForcedWinIsNeverSacrificedToAvoidRepetition() {
        //White: Pushee D7 (6,3) + Pusher D6 (5,3) — the mirror square behind
        //the goal push. The ONLY winning move is the pushee's straight push
        //D7->D8 (the diagonal pushes lack their mirror Pushers, and the
        //driver's D7 square is occupied). Its child position is terminal.
        //
        //Inject that child position into the repetition history: the
        //winning move "repeats" — the engine must still play it (a forced
        //win outranks a penalty of 5 by ~2^31).
        setup(new int[][]{
                {6, 3, 3},   //White Pushee D7
                {5, 3, 4},   //White Pusher D6 (the mirror)
        });

        Move winning = new Move(board.getSquare(6, 3), board.getSquare(7, 3));
        board.movePiece(winning);
        long seenArrangement = GameTree.arrangementHash(
                board.getZobristHash(), board.getCurrentPlayer());
        board.undo(winning);

        GameTree tree = new GameTree(Player.WHITE, Player.BLACK);
        List<Long> history = new ArrayList<>();
        history.add(seenArrangement);
        tree.setRepetitionHistory(history);

        Move chosen = tree.getBestMoveQuick(1);
        assertEquals("the forced win must be played even though it repeats",
                winning, chosen);
    }

    @Test
    public void endlessPlayLogsAWarningAtThePlyCapButKeepsPlaying() throws Exception {
        final int originalCap = PusherApplication.MAX_PLIES;
        final int originalPlies = PusherApplication.plies;
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        //A two-piece race that cannot end (nor collide) within the few plies
        //this test plays: White D4 (3,3) wins at most at its 4th move (ply
        //7), Black H8 (7,7) is a full file away and walks straight down file
        //H — H8H7, H7H6, H6H5 are legal no matter which of White's C5/D5/E5
        //lines the engine picks.
        final int[][] raceBoard = new int[8][8];
        raceBoard[3][3] = 4;   //White Pusher D4
        raceBoard[7][7] = 2;   //Black Pusher H8

        System.setOut(new PrintStream(captured, true));
        try (PusherApplicationTest.AppSession session =
                     new PusherApplicationTest.AppSession()) {

            PusherApplication.MAX_PLIES = 4;
            PusherApplication.plies = 0;

            session.sendNewGameWhite(raceBoard);
            String white1 = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not open the game", white1);

            //Each '3' applies two plies (the pending White move, then the
            //opponent's): after the second exchange the count reaches the
            //cap of 4 and must log a warning — and the third exchange must
            //still be answered (keeping play going, no draw declared).
            session.sendOpponentMove("H8H7");
            String white2 = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull(white2);

            session.sendOpponentMove("H7H6");
            String white3 = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull(white3);

            session.sendOpponentMove("H6H5");
            String white4 = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app must keep playing past the ply cap",
                    white4);
            assertTrue("plies were counted past the cap",
                    PusherApplication.plies >= PusherApplication.MAX_PLIES);
        } finally {
            System.setOut(originalOut);
            PusherApplication.MAX_PLIES = originalCap;
            PusherApplication.plies = originalPlies;
        }

        String log = captured.toString();
        assertTrue("the ply-cap warning must be logged, got:\n" + log,
                log.contains("possible endless play"));
    }
}
