package client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * PusherApplication is the entry point of the Pusher AI client: it opens the
 * socket to the game server and hands the buffered streams to a
 * {@link GameSession}, which owns the per-connection state (the pending
 * move, the search tree, the ply count) and runs the command loop.
 *
 * Before C12 this class also carried that state as {@code public static}
 * fields; the state and the loop moved to {@link GameSession} so a session
 * is a self-contained object and tests can build sessions with their own
 * configuration (e.g. a small ply cap) instead of poking globals. The wire
 * protocol, stdout, and error handling are unchanged.
 *
 * @author Mathieu Ouellet
 * @since 10/29/2015.
 */
public class PusherApplication {

	public static void main(String[] args) {
		try {
			System.out.println("Creating socket to localhost:8888");
			Socket myClient = new Socket("localhost", 8888);
			System.out.println("Got socket with Streams");
			run(new BufferedInputStream(myClient.getInputStream()),
				new BufferedOutputStream(myClient.getOutputStream()));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Run one default {@link GameSession} (the
	 * {@link GameSession#DEFAULT_MAX_PLIES} cap) against the given streams.
	 *
	 * Kept as the long-standing static entry point for tests and embedders
	 * that don't need a custom session; the protocol documentation and the
	 * loop itself live on {@link GameSession#run(InputStream, OutputStream)}.
	 *
	 * @param in  Stream from the server (command bytes + payloads).
	 * @param out Stream to the server (the chosen moves).
	 * @throws IOException If reading from the server fails. The loop exits
	 *                     normally when the server closes the connection.
	 */
	public static void run(InputStream in, OutputStream out) throws IOException {
		new GameSession().run(in, out);
	}
}
