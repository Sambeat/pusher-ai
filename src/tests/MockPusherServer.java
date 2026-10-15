package tests;

import game.GameBoard;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * T2: a minimal in-process Pusher server, backed by a real ServerSocket and a
 * background thread, so PusherApplication can be integration-tested over an
 * actual socket connection (loopback, ephemeral port) instead of a live game
 * server on port 8888.
 *
 * It speaks the current protocol (see docs/RULES.md, item 5):
 * <ul>
 *   <li>'1' + the 64-int board text — new game, the AI plays White;</li>
 *   <li>'2' + the 64-int board text — new game, the AI plays Black;</li>
 *   <li>'3' + the opponent's move in the server's 7-char format
 *       (e.g. "D2   E3", see {@link #serverMove(String)});</li>
 *   <li>'4' — the client answers with a random legal move (no payload).</li>
 * </ul>
 *
 * Messages are newline-terminated, the framing T9 assumes the real server
 * uses (docs/RULES.md item 5, open question 6): command byte, payload,
 * '\n'. By default the command byte and the whole payload are written in a
 * single flushed write; sendFragmented additionally simulates a message
 * split across several TCP writes (each flushed, with a gap between them),
 * which the client's pre-T9 input.available()-based reads could not survive.
 *
 * Client responses are the 7-char move strings the app writes raw (no
 * newline, see PusherApplication.communicateMove). This server reads them as
 * 7-byte chunks and records every response, in order, for assertions.
 */
public class MockPusherServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptThread;

    private final List<String> responses =
            Collections.synchronizedList(new ArrayList<String>());
    private final Object responseLock = new Object();
    private final Object sendLock = new Object();

    private Socket client;
    private OutputStream clientOut;
    private volatile boolean clientConnected;

    /**
     * Start listening on an ephemeral loopback port and spawn the thread that
     * will accept the single test client.
     */
    public MockPusherServer() throws IOException {
        serverSocket = new ServerSocket(0);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "MockPusherServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** The ephemeral port the test client should connect to. */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * Block until the test client has connected.
     * @throws IllegalStateException if it does not connect in time.
     */
    public void awaitConnection(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!clientConnected) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
                throw new IllegalStateException(
                        "the test client did not connect to the mock server in time");
            Thread.sleep(Math.min(10, remaining));
        }
    }

    /** New game, the AI plays White, with the given board. */
    public void sendNewGameWhite(int[][] board) {
        send((byte) '1', boardText(board));
    }

    /** New game, the AI plays Black, with the given board. */
    public void sendNewGameBlack(int[][] board) {
        send((byte) '2', boardText(board));
    }

    /** The opponent's move in the server's 7-char format (e.g. "D2   E3"). */
    public void sendOpponentMove(String moveText) {
        send((byte) '3', moveText);
    }

    /**
     * Encode a move in the server's 7-char format (docs/RULES.md item 6):
     * the 4-char "D2E3" becomes "D2   E3" — origin at indexes 0-1, destination
     * at indexes 5-6, three padding spaces between (the app sends the same
     * padding; assumed, see the doc's open questions).
     *
     * @param fourCharMove The move in compact "D2E3" form.
     * @return the 7-char server form.
     */
    public static String serverMove(String fourCharMove) {
        return fourCharMove.substring(0, 2) + "   " + fourCharMove.substring(2, 4);
    }

    /** Command '4': the client answers with a random legal move. */
    public void sendRandomMove() {
        send((byte) '4', "");
    }

    /**
     * Wait for the client's next response (one 4-char move string).
     * @return the response, or null when none arrived within the timeout.
     */
    public String waitForResponse(long timeoutMillis) throws InterruptedException {
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

    /** Every response received so far, in order. */
    public List<String> getResponses() {
        synchronized (responses) {
            return new ArrayList<String>(responses);
        }
    }

    /**
     * Encode a board in the 64-int text format the real server sends
     * (server-confirmed, T11, docs/RULES.md item 5): the tokens run from the
     * top-left corner (A1, row 0 column 0), left-to-right, then top-to-bottom,
     * so the 1st token is A1 ... the 64th is H8.
     *
     * @param board board[row][column]; 0 = empty, 1 = Black Pushee,
     *              2 = Black Pusher, 3 = White Pushee, 4 = White Pusher.
     */
    public static String boardText(int[][] board) {
        StringBuilder sb = new StringBuilder(128);
        for (int row = 0; row < GameBoard.GAME_BOARD_LENGTH; row++) {
            for (int column = 0; column < GameBoard.GAME_BOARD_LENGTH; column++) {
                if (sb.length() > 0)
                    sb.append(' ');
                sb.append(board[row][column]);
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        try {
            if (client != null)
                client.close();
        } catch (IOException ignored) {
            //The client was already gone.
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            //Already closed.
        }
    }

    private void acceptLoop() {
        try {
            client = serverSocket.accept();
            clientOut = new BufferedOutputStream(client.getOutputStream());
            clientConnected = true;
            readResponses(client.getInputStream());
        } catch (IOException e) {
            //The test closed the socket or the server was shut down: done.
        }
    }

    private void readResponses(InputStream in) throws IOException {
        //One response is one 7-char move string (T11, docs/RULES.md item 6).
        StringBuilder pending = new StringBuilder(7);
        int b;
        while ((b = in.read()) != -1) {
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
    }

    private void send(byte command, String payload) {
        sendFragmented(command, payload, 1, 0);
    }

    /**
     * T9: send command + payload + '\n' in `fragmentCount` separate writes,
     * each flushed, `gapMillis` apart, to simulate a message split across
     * TCP segments (e.g. fragmentCount 2 = "send a few bytes, sleep, send
     * the rest"). No single read on the client side sees the whole message
     * at once while the gaps hold.
     */
    public void sendFragmented(byte command, String payload,
                               int fragmentCount, long gapMillis) {
        if (fragmentCount < 1)
            throw new IllegalArgumentException("fragmentCount must be >= 1");
        //Command byte, payload, then the framing newline (T9's assumed
        //server framing, docs/RULES.md item 5).
        byte[] full = new byte[2 + payload.length()];
        full[0] = command;
        for (int i = 0; i < payload.length(); i++)
            full[1 + i] = (byte) payload.charAt(i);
        full[full.length - 1] = '\n';

        synchronized (sendLock) {
            try {
                if (!clientConnected)
                    throw new IOException("the test client is not connected yet");
                int base = full.length / fragmentCount;
                int offset = 0;
                for (int fragment = 0; fragment < fragmentCount; fragment++) {
                    //Contiguous chunks; the last one takes the remainder so
                    //the fragments cover the message exactly.
                    int chunk = (fragment == fragmentCount - 1)
                            ? full.length - offset : base;
                    clientOut.write(full, offset, chunk);
                    clientOut.flush();
                    offset += chunk;
                    if (gapMillis > 0 && fragment < fragmentCount - 1)
                        Thread.sleep(gapMillis);
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                        "failed to send fragmented command '" + (char) command + "'", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while sending fragmented command", e);
            }
        }
    }

    /** New game (AI plays White), board split across writes (T9). */
    public void sendNewGameWhiteFragmented(int[][] board,
                                           int fragmentCount, long gapMillis) {
        sendFragmented((byte) '1', boardText(board), fragmentCount, gapMillis);
    }

    /** New game (AI plays Black), board split across writes (T9). */
    public void sendNewGameBlackFragmented(int[][] board,
                                           int fragmentCount, long gapMillis) {
        sendFragmented((byte) '2', boardText(board), fragmentCount, gapMillis);
    }

    /** The opponent's move split across writes (T9). */
    public void sendOpponentMoveFragmented(String moveText,
                                           int fragmentCount, long gapMillis) {
        sendFragmented((byte) '3', moveText, fragmentCount, gapMillis);
    }
}
