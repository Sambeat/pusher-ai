package tests;

import client.PusherApplication;
import game.BoardSquare;
import game.GameBoard;
import game.Move;
import game.Piece;
import game.Player;
import game.Pushee;
import game.Pusher;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * T2: end-to-end tests of PusherApplication (the app layer: protocol handling,
 * multi-game resets, null-move handling), which previously had zero coverage
 * because it needed a live server.
 *
 * The app is driven through PusherApplication.run(InputStream, OutputStream):
 * scenarios 1 and 2 over a real socket against MockPusherServer (loopback,
 * ephemeral port), scenarios 3 and 4 over in-memory piped streams.
 *
 * The app plays its moves with the full GameTree time budget, so each
 * response can take up to a few seconds; RESPONSE_TIMEOUT_MILLIS is generous
 * on purpose.
 */
public class PusherApplicationTest {

    /** Plenty of margin over GameTree.MAX_TIME_MILLIS (4950). */
    private static final long RESPONSE_TIMEOUT_MILLIS = 30_000;

    private static final Pattern MOVE_FORMAT =
            Pattern.compile("[A-H][1-8][A-H][1-8]");

    //Game 1 board (row 0 = top = BLACK's goal row, column 0 = file A):
    //  White: Pusher D4 (3,3), Pusher F4 (3,5), Pushee E3 (2,4)
    //  Black: Pusher D8 (7,3), Pusher F8 (7,5), Pushee E7 (6,4)
    //White has 6 legal moves (3 per Pusher); the Black Pusher D8 can move
    //straight ahead to D7 (6,3).
    private static int[][] gameOneBoard() {
        int[][] board = new int[8][8];
        board[3][3] = 4; //White Pusher D4
        board[3][5] = 4; //White Pusher F4
        board[2][4] = 3; //White Pushee E3
        board[7][3] = 2; //Black Pusher D8
        board[7][5] = 2; //Black Pusher F8
        board[6][4] = 1; //Black Pushee E7
        return board;
    }

    //Game 2 board: a different, disjoint arrangement of 5 pieces so that any
    //leftover from game 1 (or replay of its move) is visible. The White
    //Pusher F3 (2,5) can move to E4 (3,4); "F3E4" is the opponent move used
    //after the game 2 reset.
    private static int[][] gameTwoBoard() {
        int[][] board = new int[8][8];
        board[5][2] = 2; //Black Pusher C6
        board[4][4] = 2; //Black Pusher E5
        board[3][3] = 1; //Black Pushee D4
        board[2][5] = 4; //White Pusher F3
        board[1][3] = 4; //White Pusher D2
        return board;
    }

    @Test
    public void newGameWhiteRespondsWithALegalWhiteMove() throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (MockPusherServer server = new MockPusherServer();
             Socket socket = new Socket("localhost", server.getPort())) {

            startApp(socket.getInputStream(), socket.getOutputStream());
            server.awaitConnection(RESPONSE_TIMEOUT_MILLIS);

            //Scenario 1: '1' + full board -> a legal 4-char move for White.
            server.sendNewGameWhite(gameOneBoard());

            String response = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to '1' + board", response);
            assertTrue("response '" + response
                    + "' is not a 4-char move", MOVE_FORMAT.matcher(response).matches());

            //The board was built exactly as sent (position AND piece type).
            assertBoardMatches(gameOneBoard());

            //The response is in White's legal move list on the parsed board.
            assertEquals(Player.WHITE, board.getCurrentPlayer());
            Move move = parseFourChar(response);
            assertTrue("response '" + response + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(move));
            assertEquals(Player.WHITE,
                    board.getPiece(move.getPieceLocation().getRow(),
                            move.getPieceLocation().getColumn()).getOwner());

            //Command '4': the app answers with a random legal move.
            server.sendRandomMove();
            String randomResponse = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to '4'", randomResponse);
            assertTrue("random response '" + randomResponse
                    + "' is not a 4-char move",
                    MOVE_FORMAT.matcher(randomResponse).matches());
            assertTrue("random response '" + randomResponse
                    + "' is not a legal White move",
                    board.getAllLegalMovesQuick()
                            .contains(parseFourChar(randomResponse)));
        }
    }

    @Test
    public void opponentMoveIsAppliedAndAnsweredWithALegalWhiteMove()
            throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (MockPusherServer server = new MockPusherServer();
             Socket socket = new Socket("localhost", server.getPort())) {

            startApp(socket.getInputStream(), socket.getOutputStream());
            server.awaitConnection(RESPONSE_TIMEOUT_MILLIS);

            //Scenario 2: a full two-ply exchange.
            server.sendNewGameWhite(gameOneBoard());
            String whiteMove1 = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull(whiteMove1);
            Move m1 = parseFourChar(whiteMove1);

            //The Black Pusher D8 moves straight ahead to D7 (empty square).
            server.sendOpponentMove("D8D7");
            String whiteMove2 = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to '3' + opponent move",
                    whiteMove2);
            assertTrue("response '" + whiteMove2
                    + "' is not a 4-char move", MOVE_FORMAT.matcher(whiteMove2).matches());

            //The app applied the opponent's move to the singleton board:
            //the Black Pusher is on D7 and D8 is empty.
            BoardSquare d8 = board.getSquare(7, 3);
            BoardSquare d7 = board.getSquare(6, 3);
            assertTrue("D8 should be empty after D8D7", d8.isEmpty());
            assertNotNull("D7 should hold the Black Pusher after D8D7",
                    d7.getPiece());
            assertEquals(Player.BLACK, d7.getPiece().getOwner());
            assertTrue("D7 should hold a Pusher", d7.getPiece() instanceof Pusher);

            //The app applied its own first move as well: the White piece sits
            //on the destination of the move it sent, the origin is empty.
            Piece whitePiece = board.getPiece(m1.getDestination().getRow(),
                    m1.getDestination().getColumn());
            assertNotNull("no White piece on the destination of its own move",
                    whitePiece);
            assertEquals(Player.WHITE, whitePiece.getOwner());
            assertTrue("the origin of the app's own move should be empty",
                    board.getSquare(m1.getPieceLocation().getRow(),
                            m1.getPieceLocation().getColumn()).isEmpty());

            //Two plies have been played and it is White's turn again.
            assertEquals(2, board.getTurnNumber());
            assertEquals(Player.WHITE, board.getCurrentPlayer());

            //The second response is a legal White move.
            Move m2 = parseFourChar(whiteMove2);
            assertTrue("second response '" + whiteMove2
                    + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(m2));
        }
    }

    @Test
    public void secondGameStartsFromACleanBoardWithoutReplayingTheOldMove()
            throws Exception {
        final GameBoard board = GameBoard.getInstance();
        final int[][] second = gameTwoBoard();

        try (AppSession session = new AppSession()) {

            //Game 1: AI plays White, one full exchange so the game 1 state is
            //non-trivial (two plies, a pending, not-yet-applied White move).
            session.sendNewGameWhite(gameOneBoard());
            assertNotNull(session.waitForResponse(RESPONSE_TIMEOUT_MILLIS));
            session.sendOpponentMove("D8D7");
            assertNotNull(session.waitForResponse(RESPONSE_TIMEOUT_MILLIS));

            //Game 2: '2' + a new, different board. The '2' handler sends no
            //response (the AI plays Black and waits for the opponent).
            session.sendNewGameBlack(second);
            //Barrier: wait until the app has built the game 2 board.
            awaitBoardMatches(second);

            //No leftovers from game 1, turn state reset. (The board currently
            //holds exactly the new game's pieces: if reset() left game 1
            //pieces behind, the exact match above would fail.)
            assertEquals(Player.WHITE, board.getCurrentPlayer());
            assertEquals(0, board.getTurnNumber());

            //The opponent (White) plays F3E4. If the app replayed game 1's
            //pending move here, the state below would be corrupted.
            session.sendOpponentMove("F3E4");
            String blackMove = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);

            //The board holds exactly game 2's pieces with F3E4 applied: the
            //White Pusher is on E4, F3 is empty, every other piece where it
            //was. A replayed game 1 move would have moved (or killed) one of
            //these pieces.
            assertBoardMatches(applyMove(second, "F3E4"));
            assertEquals(1, board.getTurnNumber());
            assertEquals(Player.BLACK, board.getCurrentPlayer());

            //The app answered with a legal Black move instead of hanging.
            assertNotNull("the app did not answer game 2's first opponent move",
                    blackMove);
            assertTrue("response '" + blackMove
                    + "' is not a 4-char move", MOVE_FORMAT.matcher(blackMove).matches());
            assertTrue("response '" + blackMove + "' is not a legal Black move",
                    board.getAllLegalMovesQuick().contains(parseFourChar(blackMove)));
        }
    }

    @Test
    public void garbageOpponentMoveIsLoggedAndSurvived() throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (AppSession session = new AppSession()) {

            session.sendNewGameWhite(gameOneBoard());
            String whiteMove = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull(whiteMove);
            Move m1 = parseFourChar(whiteMove);

            //Scenario 4: a garbage '3' payload. The app logs
            //("Could not parse opponent move: [XYZ]"), sends nothing, and the
            //command loop must keep running.
            session.sendOpponentMove("XYZ");
            assertNull("the app must not respond to a garbage move",
                    session.waitForResponse(2000));

            //The loop survived: the next valid command is still handled.
            //D8D7 is legal regardless of the app's first move (it never
            //reaches the Black pieces' row 6-7 neighborhood... the White
            //piece it moved lands on row 4).
            session.sendOpponentMove("D8D7");
            String whiteMove2 = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not recover after a garbage move",
                    whiteMove2);
            assertTrue("response '" + whiteMove2
                    + "' is not a 4-char move", MOVE_FORMAT.matcher(whiteMove2).matches());

            //Board state: game 1's pending White move was applied when the
            //garbage '3' arrived (the app applies its pending move before
            //parsing the payload), then D8D7 was applied on top of it.
            assertEquals(2, board.getTurnNumber());
            assertEquals(Player.WHITE, board.getCurrentPlayer());
            Piece whitePiece = board.getPiece(m1.getDestination().getRow(),
                    m1.getDestination().getColumn());
            assertNotNull(whitePiece);
            assertEquals(Player.WHITE, whitePiece.getOwner());
            assertTrue(board.getSquare(m1.getPieceLocation().getRow(),
                    m1.getPieceLocation().getColumn()).isEmpty());
            assertTrue("D8 should be empty after D8D7",
                    board.getSquare(7, 3).isEmpty());
            Piece d7 = board.getPiece(6, 3);
            assertNotNull("D7 should hold the Black Pusher after D8D7", d7);
            assertEquals(Player.BLACK, d7.getOwner());

            //And the response is a legal White move on that state.
            assertTrue("post-garbage response '" + whiteMove2
                    + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(parseFourChar(whiteMove2)));
        }
    }

    //--- helpers -------------------------------------------------------------

    /**
     * The 4-char move format the app sends: "D2E3" = from D2 to E3
     * (column letter, row digit 1-8 with 1 = row 0).
     */
    private static Move parseFourChar(String s) {
        GameBoard board = GameBoard.getInstance();
        int originRow = s.charAt(1) - '1';
        int originColumn = s.charAt(0) - 'A';
        int destRow = s.charAt(3) - '1';
        int destColumn = s.charAt(2) - 'A';
        return new Move(board.getSquare(originRow, originColumn),
                board.getSquare(destRow, destColumn));
    }

    /**
     * Assert the singleton board holds exactly the pieces of the expected
     * encoding (position, type and color for all 64 squares).
     */
    private static void assertBoardMatches(int[][] expected) {
        GameBoard board = GameBoard.getInstance();
        int expectedCount = 0;
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                int value = expected[row][column];
                Piece actual = board.getPiece(row, column);
                String where = "square (" + row + "," + column + ")";
                if (value == 0) {
                    if (actual != null)
                        fail(where + " should be empty but holds " + actual);
                } else {
                    if (actual == null) {
                        fail(where + " should hold a piece (" + value + ")");
                    }
                    if (value == 1) {
                        assertTrue(where, isPiece(actual, Player.BLACK, false));
                    } else if (value == 2) {
                        assertTrue(where, isPiece(actual, Player.BLACK, true));
                    } else if (value == 3) {
                        assertTrue(where, isPiece(actual, Player.WHITE, false));
                    } else {
                        assertTrue(where, isPiece(actual, Player.WHITE, true));
                    }
                    expectedCount++;
                }
            }
        }
        assertEquals("total piece count", expectedCount,
                board.getNumberOfPieces());
    }

    private static boolean isPiece(Piece piece, Player owner, boolean pusher) {
        return piece.getOwner() == owner
                && (pusher ? piece instanceof Pusher : piece instanceof Pushee);
    }

    /** A copy of the board with the 4-char move applied. */
    private static int[][] applyMove(int[][] board, String fourCharMove) {
        int[][] copy = new int[8][8];
        for (int row = 0; row < 8; row++)
            System.arraycopy(board[row], 0, copy[row], 0, 8);
        int originRow = fourCharMove.charAt(1) - '1';
        int originColumn = fourCharMove.charAt(0) - 'A';
        int destRow = fourCharMove.charAt(3) - '1';
        int destColumn = fourCharMove.charAt(2) - 'A';
        copy[destRow][destColumn] = copy[originRow][originColumn];
        copy[originRow][originColumn] = 0;
        return copy;
    }

    /**
     * Wait (polling the singleton) until the board matches the expected
     * encoding. Needed because the '2' handler sends no response to use as a
     * synchronization point.
     */
    private static void awaitBoardMatches(int[][] expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (boardMatches(expected))
                return;
            Thread.sleep(10);
        }
        assertBoardMatches(expected);
    }

    private static boolean boardMatches(int[][] expected) {
        GameBoard board = GameBoard.getInstance();
        for (int row = 0; row < 8; row++) {
            for (int column = 0; column < 8; column++) {
                Piece actual = board.getPiece(row, column);
                int value = expected[row][column];
                if (value == 0) {
                    if (actual != null)
                        return false;
                } else {
                    if (actual == null)
                        return false;
                    boolean pusher = (value == 2 || value == 4);
                    Player owner = (value == 1 || value == 2)
                            ? Player.BLACK : Player.WHITE;
                    if (!isPiece(actual, owner, pusher))
                        return false;
                }
            }
        }
        return true;
    }

    /**
     * Run PusherApplication.run over a real socket to the mock server, in a
     * background thread (the loop blocks on input).
     */
    private static Thread startApp(InputStream in, OutputStream out) {
        Thread appThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PusherApplication.run(in, out);
                } catch (IOException e) {
                    //The socket was closed: the session is over.
                }
            }
        }, "PusherApplication-under-test");
        appThread.setDaemon(true);
        appThread.start();
        return appThread;
    }

    /**
     * Drive PusherApplication.run with in-memory piped streams (no socket):
     * the test writes protocol commands into the app's input and reads the
     * app's 4-byte move responses from its output.
     */
    private static class AppSession implements AutoCloseable {

        private final PipedOutputStream toApp;
        private final PipedInputStream appIn;
        private final PipedOutputStream appOut;
        private final PipedInputStream fromApp;
        private final Thread appThread;
        private final Thread readerThread;

        private final List<String> responses =
                Collections.synchronizedList(new ArrayList<String>());
        private final Object responseLock = new Object();

        AppSession() throws IOException {
            appIn = new PipedInputStream();
            toApp = new PipedOutputStream(appIn);
            appOut = new PipedOutputStream();
            fromApp = new PipedInputStream(appOut);

            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readResponses();
                }
            }, "app-response-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            appThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        PusherApplication.run(appIn, appOut);
                    } catch (IOException e) {
                        //EOF: the session is over.
                    }
                }
            }, "PusherApplication-under-test");
            appThread.setDaemon(true);
            appThread.start();
        }

        void sendNewGameWhite(int[][] board) throws IOException {
            send((byte) '1', MockPusherServer.boardText(board));
        }

        void sendNewGameBlack(int[][] board) throws IOException {
            send((byte) '2', MockPusherServer.boardText(board));
        }

        void sendOpponentMove(String fourCharMove) throws IOException {
            send((byte) '3', fourCharMove);
        }

        String waitForResponse(long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            synchronized (responseLock) {
                int startSize = responses.size();
                while (responses.size() == startSize) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0)
                        return null;
                    responseLock.wait(remaining);
                }
                return responses.get(startSize);
            }
        }

        @Override
        public void close() throws IOException {
            //EOF on the app's input ends the command loop...
            toApp.close();
            try {
                joinQuietly(appThread, "the app");
                //...closing the app's output pipe ends the response reader.
                appOut.close();
                joinQuietly(readerThread, "the response reader");
            } finally {
                appIn.close();
                fromApp.close();
            }
        }

        /**
         * Command byte and payload in a single write: the pipe delivers the
         * whole message atomically, so the app's input.available()-based
         * payload reads never see a partial message.
         */
        private void send(byte command, String payload) throws IOException {
            byte[] full = new byte[1 + payload.length()];
            full[0] = command;
            for (int i = 0; i < payload.length(); i++)
                full[1 + i] = (byte) payload.charAt(i);
            toApp.write(full);
            toApp.flush();
        }

        private void readResponses() {
            StringBuilder pending = new StringBuilder(4);
            try {
                int b;
                while ((b = fromApp.read()) != -1) {
                    pending.append((char) b);
                    if (pending.length() == 4) {
                        synchronized (responses) {
                            responses.add(pending.toString());
                            pending.setLength(0);
                        }
                        synchronized (responseLock) {
                            responseLock.notifyAll();
                        }
                    }
                }
            } catch (IOException e) {
                //Pipe closed: the session is over.
            }
        }

        private boolean joinQuietly(Thread thread, String what)
                throws IOException {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while closing the session");
            }
            if (thread.isAlive()) {
                fail("PusherApplication did not exit after the session closed ("
                        + what + " thread still running)");
            }
            return true;
        }
    }
}
