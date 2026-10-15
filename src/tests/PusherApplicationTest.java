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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
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

    /**
     * The server's 7-char move format (T11, docs/RULES.md item 6): origin at
     * indexes 0-1, destination at indexes 5-6; the three middle characters
     * are padding (the app sends spaces; the fixed positions are what the
     * format guarantees).
     */
    private static final Pattern MOVE_FORMAT =
            Pattern.compile("[A-H][1-8].{3}[A-H][1-8]");

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

            //Scenario 1: '1' + full board -> a legal 7-char server move for White.
            server.sendNewGameWhite(gameOneBoard());

            String response = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to '1' + board", response);
            assertTrue("response '" + response
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(response).matches());

            //The board was built exactly as sent (position AND piece type).
            assertBoardMatches(gameOneBoard());

            //The response is in White's legal move list on the parsed board.
            assertEquals(Player.WHITE, board.getCurrentPlayer());
            Move move = parseServerMove(response);
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
                    + "' is not a 7-char server move",
                    MOVE_FORMAT.matcher(randomResponse).matches());
            assertTrue("random response '" + randomResponse
                    + "' is not a legal White move",
                    board.getAllLegalMovesQuick()
                            .contains(parseServerMove(randomResponse)));
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
            Move m1 = parseServerMove(whiteMove1);

            //The Black Pusher D8 moves straight ahead to D7 (empty square).
            server.sendOpponentMove(MockPusherServer.serverMove("D8D7"));
            String whiteMove2 = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to '3' + opponent move",
                    whiteMove2);
            assertTrue("response '" + whiteMove2
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(whiteMove2).matches());

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
            Move m2 = parseServerMove(whiteMove2);
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
            session.sendOpponentMove(MockPusherServer.serverMove("D8D7"));
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
            session.sendOpponentMove(MockPusherServer.serverMove("F3E4"));
            String blackMove = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);

            //The board holds exactly game 2's pieces with F3E4 applied: the
            //White Pusher is on E4, F3 is empty, every other piece where it
            //was. A replayed game 1 move would have moved (or killed) one of
            //these pieces.
            assertBoardMatches(applyMove(second, MockPusherServer.serverMove("F3E4")));
            assertEquals(1, board.getTurnNumber());
            assertEquals(Player.BLACK, board.getCurrentPlayer());

            //The app answered with a legal Black move instead of hanging.
            assertNotNull("the app did not answer game 2's first opponent move",
                    blackMove);
            assertTrue("response '" + blackMove
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(blackMove).matches());
            assertTrue("response '" + blackMove + "' is not a legal Black move",
                    board.getAllLegalMovesQuick().contains(parseServerMove(blackMove)));
        }
    }

    @Test
    public void garbageOpponentMoveIsLoggedAndSurvived() throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (AppSession session = new AppSession()) {

            session.sendNewGameWhite(gameOneBoard());
            String whiteMove = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull(whiteMove);
            Move m1 = parseServerMove(whiteMove);

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
            session.sendOpponentMove(MockPusherServer.serverMove("D8D7"));
            String whiteMove2 = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not recover after a garbage move",
                    whiteMove2);
            assertTrue("response '" + whiteMove2
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(whiteMove2).matches());

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
                    board.getAllLegalMovesQuick().contains(parseServerMove(whiteMove2)));
        }
    }

    //--- T9: proper message framing ------------------------------------------

    @Test
    public void boardArrivingInThreeTcpFragmentsIsParsedCorrectly() throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (MockPusherServer server = new MockPusherServer();
             Socket socket = new Socket("localhost", server.getPort())) {

            startApp(socket.getInputStream(), socket.getOutputStream());
            server.awaitConnection(RESPONSE_TIMEOUT_MILLIS);

            //T9 (a): the '1' + 64-int board arrives in 3 separate flushed
            //writes ~150 ms apart, so no read sees the whole message at once.
            //The pre-T9 input.available()-based read built the board from
            //whatever was buffered at that instant (a partial board, no
            //response); the newline-terminated read must reassemble it.
            server.sendNewGameWhiteFragmented(gameOneBoard(), 3, 150);

            String response = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to a board split across 3 writes",
                    response);
            assertTrue("response '" + response
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(response).matches());

            //The full board was reassembled (position AND piece type).
            assertBoardMatches(gameOneBoard());
            assertEquals(Player.WHITE, board.getCurrentPlayer());
            assertTrue("response '" + response + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(parseServerMove(response)));

            //T9 (c): a normal single-shot command right after the fragmented
            //one still works: the framing left the stream in sync.
            server.sendOpponentMove(MockPusherServer.serverMove("D8D7"));
            String response2 = server.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app desynced after the fragmented board", response2);
            assertTrue("post-fragment response '" + response2
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(response2).matches());
            assertTrue("post-fragment response '" + response2
                    + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(parseServerMove(response2)));
        }
    }

    @Test
    public void truncatedBoardIsRejectedWithoutCorruptingTheBoard() throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (AppSession session = new AppSession()) {

            //Establish a valid game first, so "no corruption" has something
            //to mean: the board holds gameOneBoard's six pieces.
            session.sendNewGameWhite(gameOneBoard());
            String firstMove = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull(firstMove);
            assertBoardMatches(gameOneBoard());

            //T9 (b): a new game whose board payload carries only 30 of the 64
            //integers. The pre-T9 code placed the first 30 tokens and played
            //on the partial board; the app must consume the bad line (resync),
            //log it, touch nothing, and wait for the next command.
            String[] tokens = MockPusherServer.boardText(gameOneBoard()).split(" ");
            StringBuilder truncated = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                if (i > 0)
                    truncated.append(' ');
                truncated.append(tokens[i]);
            }
            session.sendRaw((byte) '1', truncated.toString() + "\n");

            assertNull("the app must not respond to a truncated board",
                    session.waitForResponse(2000));

            //The board is exactly as it was: the six original pieces, the
            //pending first move still unapplied, turn state untouched.
            assertBoardMatches(gameOneBoard());
            assertEquals(0, board.getTurnNumber());
            assertEquals(Player.WHITE, board.getCurrentPlayer());

            //The loop survived: the next valid command is still handled, with
            //the pending move applied first (the T2 '3' contract).
            session.sendOpponentMove(MockPusherServer.serverMove("D8D7"));
            String response = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not recover after a truncated board", response);
            assertTrue("response '" + response
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(response).matches());

            //gameOneBoard's pieces with the pending move and D8D7 applied in
            //order; a partial board build would have misplaced or dropped
            //pieces (Black's pieces sit past token 30 in the confirmed
            //top-left-first order).
            int[][] expected = applyMove(applyMove(gameOneBoard(), firstMove), MockPusherServer.serverMove("D8D7"));
            assertBoardMatches(expected);
            assertEquals(2, board.getTurnNumber());
            assertEquals(Player.WHITE, board.getCurrentPlayer());
            assertTrue("post-rejection response '" + response
                    + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(parseServerMove(response)));
        }
    }

    @Test
    public void singleShotBoardStillWorks() throws Exception {
        //T9 (c): the normal single-write, newline-terminated board send is
        //unchanged by the framing work.
        final GameBoard board = GameBoard.getInstance();

        try (AppSession session = new AppSession()) {
            session.sendNewGameWhite(gameOneBoard());
            String response = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to a single-shot board", response);
            assertTrue("response '" + response
                    + "' is not a 7-char server move", MOVE_FORMAT.matcher(response).matches());
            assertBoardMatches(gameOneBoard());
            assertTrue("response '" + response + "' is not a legal White move",
                    board.getAllLegalMovesQuick().contains(parseServerMove(response)));
        }
    }

    //--- T9: framing helpers (unit level) -------------------------------------

    @Test
    public void readLineStopsAtTheLineEndAndStripsWhitespace() throws IOException {
        //Byte-by-byte delivery of a multi-word payload (what a split TCP
        //read looks like to a byte-oriented reader).
        assertEquals("0 0 0 0 1 2 3 4", PusherApplication.readLine(
                new BufferedInputStream(new ByteArrayInputStream(
                        "0 0 0 0 1 2 3 4\nmore".getBytes()))));
        //CRLF terminates too, and the '\n' half of the pair is consumed so
        //the outer command loop does not misread it.
        assertEquals("D8D7", PusherApplication.readLine(
                new BufferedInputStream(new ByteArrayInputStream(
                        "D8D7\r\nmore".getBytes()))));
        //Surrounding whitespace is stripped.
        assertEquals("D8D7", PusherApplication.readLine(
                new BufferedInputStream(new ByteArrayInputStream(
                        "  D8D7  \n".getBytes()))));
    }

    @Test
    public void readLineAtEofReturnsNullWithoutPayloadAndKeepsAPartialPayload()
            throws IOException {
        assertNull(PusherApplication.readLine(
                new BufferedInputStream(new ByteArrayInputStream(new byte[0]))));
        //A final message that lost its terminator at connection close is
        //still returned: the loop exits on the next command read anyway.
        assertEquals("0 1", PusherApplication.readLine(
                new BufferedInputStream(new ByteArrayInputStream(
                        "0 1".getBytes()))));
    }

    @Test
    public void boardPayloadMustBeExactly64IntegersIn0To4() {
        assertNotNull("64 valid tokens must parse",
                PusherApplication.parseBoardPayload(boardString(64, "0")));
        assertNull("30 tokens (the T9 truncated-board case)",
                PusherApplication.parseBoardPayload(boardString(30, "0")));
        assertNull("65 tokens",
                PusherApplication.parseBoardPayload(boardString(65, "0")));
        assertNull("value 5 is out of range",
                PusherApplication.parseBoardPayload(boardString(64, "5")));
        assertNull("negative value",
                PusherApplication.parseBoardPayload(boardString(64, "-1")));
        assertNull("non-numeric token",
                PusherApplication.parseBoardPayload(boardString(64, "x")));
        assertNull("null payload", PusherApplication.parseBoardPayload(null));
        assertNull("empty payload", PusherApplication.parseBoardPayload(""));
        assertNull("whitespace-only payload",
                PusherApplication.parseBoardPayload("   "));
    }

    //--- T11: server-confirmed protocol -------------------------------------

    /**
     * Marker board for the orientation test: a piece on each corner and two
     * mid-board squares — all different token indexes in the confirmed
     * top-left-first layout (docs/RULES.md item 5).
     */
    private static int[][] orientationMarkerBoard() {
        int[][] board = new int[8][8];
        board[0][0] = 4; //token 0  -> A1, top-left
        board[0][7] = 3; //token 7  -> H1, top-right
        board[3][4] = 4; //token 28
        board[4][3] = 2; //token 35
        board[7][0] = 2; //token 56 -> A8, bottom-left
        board[7][7] = 1; //token 63 -> H8, bottom-right
        return board;
    }

    /**
     * T11: the board payload runs from the top-left corner (A1), left-to-right,
     * top-to-bottom (server owner, docs/RULES.md item 5). The pre-T11 client
     * started at H8 (bottom-right) — the exact 180° rotation. '2' (AI plays
     * Black) builds the board without searching, so this is a pure
     * wire-format round trip.
     */
    @Test
    public void boardPayloadIsPlacedTopLeftFirst() throws Exception {
        try (AppSession session = new AppSession()) {
            session.sendNewGameBlack(orientationMarkerBoard());
            awaitBoardMatches(orientationMarkerBoard());
        }
    }

    /**
     * T11: the app's responses use the server's 7-char move format: origin at
     * indexes 0-1, destination at indexes 5-6 (docs/RULES.md item 6). The
     * pre-T11 app sent the 4-char compact form, which the server does not
     * expect.
     */
    @Test
    public void appRespondsWithSevenCharServerMoves() throws Exception {
        final GameBoard board = GameBoard.getInstance();

        try (AppSession session = new AppSession()) {
            session.sendNewGameWhite(gameOneBoard());
            String response = session.waitForResponse(RESPONSE_TIMEOUT_MILLIS);
            assertNotNull("the app did not respond to '1' + board", response);
            assertEquals("the server expects 7-char moves, got '" + response + "'",
                    7, response.length());
            assertTrue("response '" + response
                    + "' is not a 7-char server move",
                    MOVE_FORMAT.matcher(response).matches());
            assertTrue("response '" + response + "' is not a legal White move",
                    board.getAllLegalMovesQuick()
                            .contains(parseServerMove(response)));
        }
    }

    /**
     * T11: the 7-char encoding — "D2   E3" for D2 -> E3: origin at indexes
     * 0-1, destination at indexes 5-6, three padding spaces between (assumed
     * padding, docs/RULES.md open questions).
     */
    @Test
    public void serverMoveStringPutsOriginAndDestinationAtTheProtocolIndexes() {
        GameBoard board = GameBoard.getInstance();
        Move d2ToE3 = new Move(board.getSquare(1, 3), board.getSquare(2, 4));
        String s = PusherApplication.toServerMoveString(d2ToE3);
        assertEquals("D2   E3", s);
        assertEquals(7, s.length());
        assertEquals('D', s.charAt(0));
        assertEquals('2', s.charAt(1));
        assertEquals('E', s.charAt(5));
        assertEquals('3', s.charAt(6));
    }

    //--- helpers -------------------------------------------------------------

    /**
     * The 7-char server move format the app sends and receives: "D2   E3" =
     * from D2 to E3 (column letter, row digit 1-8 with 1 = row 0), origin at
     * indexes 0-1 and destination at indexes 5-6 (T11, docs/RULES.md item 6).
     */
    private static Move parseServerMove(String s) {
        GameBoard board = GameBoard.getInstance();
        int originRow = s.charAt(1) - '1';
        int originColumn = s.charAt(0) - 'A';
        int destRow = s.charAt(6) - '1';
        int destColumn = s.charAt(5) - 'A';
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

    /** A 64-int board payload: `count` zeros except the last token. */
    private static String boardString(int count, String lastToken) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0)
                sb.append(' ');
            sb.append(i == count - 1 ? lastToken : "0");
        }
        return sb.toString();
    }

    /** A copy of the board with the 7-char server move applied. */
    private static int[][] applyMove(int[][] board, String serverMove) {
        int[][] copy = new int[8][8];
        for (int row = 0; row < 8; row++)
            System.arraycopy(board[row], 0, copy[row], 0, 8);
        int originRow = serverMove.charAt(1) - '1';
        int originColumn = serverMove.charAt(0) - 'A';
        int destRow = serverMove.charAt(6) - '1';
        int destColumn = serverMove.charAt(5) - 'A';
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
     * app's 7-char move responses from its output. Package-visible so the
     * T10 app-level test (tests.RepetitionTest) can reuse it.
     */
    static class AppSession implements AutoCloseable {

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

        void sendOpponentMove(String moveText) throws IOException {
            send((byte) '3', moveText);
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
         * Command byte + payload + '\n' in a single write: the assumed server
         * framing is newline-terminated (T9, docs/RULES.md item 5). The pipe
         * delivers the message in one shot here; fragmented delivery is
         * exercised over a real socket by MockPusherServer.sendFragmented.
         */
        private void send(byte command, String payload) throws IOException {
            byte[] full = new byte[2 + payload.length()];
            full[0] = command;
            for (int i = 0; i < payload.length(); i++)
                full[1 + i] = (byte) payload.charAt(i);
            full[full.length - 1] = '\n';
            toApp.write(full);
            toApp.flush();
        }

        /**
         * Send a command byte followed by a raw payload, with the framing
         * left to the caller (T9: malformed-message tests such as the
         * truncated board, which deliberately carries a partial line).
         */
        void sendRaw(byte command, String payload) throws IOException {
            toApp.write(command);
            toApp.write(payload.getBytes());
            toApp.flush();
        }

        private void readResponses() {
            //One response is one 7-char move string (T11, docs/RULES.md 6).
            StringBuilder pending = new StringBuilder(7);
            try {
                int b;
                while ((b = fromApp.read()) != -1) {
                    pending.append((char) b);
                    if (pending.length() == 7) {
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
