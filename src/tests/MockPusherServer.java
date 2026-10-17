package tests;

import game.GameBoard;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 *   <li>'3' + the opponent's 4-char move (e.g. "D2E3");</li>
 *   <li>'4' — the client answers with a random legal move (no payload).</li>
 * </ul>
 *
 * Every command byte is written together with its payload in a single
 * buffered write (one flush), mirroring how a real server would send it in
 * one segment: the client's input.available()-based reads then see the whole
 * payload at once.
 *
 * Client responses are the 4-char move strings the app writes raw (no
 * newline, see GameSession.communicateMove). This server reads them as
 * 4-byte chunks and records every response, in order, for assertions.
 */
public class MockPusherServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Thread acceptThread;

    private final List<String> responses =
            new CopyOnWriteArrayList<String>();
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

    /** The opponent's move (4-char format, e.g. "D2E3"). */
    public void sendOpponentMove(String fourCharMove) {
        send((byte) '3', fourCharMove);
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
     * Encode a board in the 64-int text format that
     * GameSession.createInitialBoard expects: tokens start at H8 (row 7,
     * column 7), proceed right-to-left, then bottom-to-top, so the 1st token
     * is H8 ... the 64th is A1 (docs/RULES.md, item 5).
     *
     * @param board board[row][column]; 0 = empty, 1 = Black Pushee,
     *              2 = Black Pusher, 3 = White Pushee, 4 = White Pusher.
     */
    public static String boardText(int[][] board) {
        StringBuilder sb = new StringBuilder(128);
        for (int row = GameBoard.GAME_BOARD_LENGTH - 1; row >= 0; row--) {
            for (int column = GameBoard.GAME_BOARD_LENGTH - 1; column >= 0; column--) {
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
        StringBuilder pending = new StringBuilder(4);
        int b;
        while ((b = in.read()) != -1) {
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
    }

    private void send(byte command, String payload) {
        synchronized (sendLock) {
            try {
                if (!clientConnected)
                    throw new IOException("the test client is not connected yet");
                //Command byte and payload in a single write, flushed once: the
                //client's input.available()-based payload reads then see the
                //whole message at once, as with a real single-segment send.
                byte[] full = new byte[1 + payload.length()];
                full[0] = command;
                for (int i = 0; i < payload.length(); i++)
                    full[1 + i] = (byte) payload.charAt(i);
                clientOut.write(full);
                clientOut.flush();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "failed to send command '" + (char) command + "'", e);
            }
        }
    }
}
