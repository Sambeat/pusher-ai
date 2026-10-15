package client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Random;

import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

/**
 * PusherApplication is the main class of an artificial intelligence playing
 * the Pusher game.
 *
 * @author Mathieu Ouellet
 * @since 10/29/2015.
 */
public class PusherApplication {

	static final int ORIGIN_COLUMN = 0;
	static final int ORIGIN_ROW = 1;
	static final int DESTINATION_COLUM = 5;
	static final int DESTINATION_ROW = 6;

	/**
	 * The number of integers a well-formed board payload must contain (the
	 * 8x8 board, docs/RULES.md item 5).
	 */
	static final int BOARD_TOKEN_COUNT =
			GameBoard.GAME_BOARD_LENGTH * GameBoard.GAME_BOARD_LENGTH;

	static GameBoard board = GameBoard.getInstance();
	static Move move;
	static GameTree gameTree;

	/**
	 * T10: plies applied to the local board in the current game. Public
	 * static (not final) so tests can exercise the warning path with a small
	 * cap; the command loop is the only writer.
	 */
	public static int plies = 0;

	/**
	 * T10: after this many plies the client logs a warning but keeps
	 * playing — the protocol has no known draw command (docs/RULES.md, open
	 * question), so the client must not unilaterally end the game.
	 * Public static (not final) so tests can lower it; see {@link #plies}.
	 */
	public static int MAX_PLIES = 300;

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
	 * The command loop of the Pusher client, factored out of
	 * {@link #main(String[])} (T2) so tests can drive the app with in-memory
	 * streams or the mock server instead of a real socket on port 8888.
	 * main() only does the socket setup and hands the buffered streams over.
	 *
	 * Message framing (T9): the command byte is read as a single byte, and
	 * each payload (board after '1'/'2', opponent move after '3') is read to
	 * the end of its newline-terminated line, byte by byte, so a message
	 * split or coalesced across any number of TCP reads is reassembled
	 * exactly. Newline-terminated framing is the assumption documented in
	 * docs/RULES.md item 5 (open question 6) until the server owner
	 * confirms the real framing.
	 *
	 * @param in  Stream from the server (command bytes + payloads).
	 * @param out Stream to the server (the chosen moves).
	 * @throws IOException If reading from the server fails. The loop exits
	 *                     normally when the server closes the connection.
	 */
	public static void run(InputStream in, OutputStream out) throws IOException {
		//main() already hands over buffered socket streams; wrapping them again
		//would only add a redundant buffering layer.
		BufferedInputStream input = (in instanceof BufferedInputStream)
				? (BufferedInputStream) in : new BufferedInputStream(in);
		BufferedOutputStream output = (out instanceof BufferedOutputStream)
				? (BufferedOutputStream) out : new BufferedOutputStream(out);

		while (true) {

			int incoming = input.read();
			if (incoming == -1) {
				//The server closed the connection: a raw -1 cast to char
				//used to match no case and spin the loop forever.
				System.out.println("Server closed the connection.");
				break;
			}
			char cmd = (char) incoming;

			try {
				switch (cmd) {
				case '1':
					//A fresh game: read and validate the board payload first,
					//then clear whatever the previous game left on the board
					//(leftover pieces, turn player, turn number, capture log)
					//and any stale move from the old game.
					if (beginNewGame(input, Player.WHITE, Player.BLACK)) {
						move = gameTree.getBestMoveQuick();
						communicateMove(output, move);
					}
					break;
				case '2':
					beginNewGame(input, Player.BLACK, Player.WHITE);
					break;
				case '3':
					if (move != null) {
						board.movePiece(move);
						registerPly();
					}
					move = null;

					String s = readLine(input);
					if (s == null) {
						//EOF before any payload: the outer loop sees the closed
						//connection on its next command read and exits.
						break;
					}

					Move opponentMove = parseMove(s);
					if (opponentMove == null) {
						//The board would be out of sync with the server from
						//here on: report it and wait for the next command
						//instead of corrupting the board state.
						System.out.println("Could not parse opponent move: [" + s + "]");
						break;
					}

					board.movePiece(opponentMove);
					registerPly();

					move = gameTree.getBestMoveQuick();
					communicateMove(output, move);
					board.updateAllLegaLMoves();
					break;
				case '4':
					ArrayList<Move> legalMoves = board.getAllLegalMovesQuick();
					move = legalMoves.isEmpty() ? null : getRandomMove(legalMoves);
					communicateMove(output, move);
					break;
				}
			} catch (Exception e) {
				//One bad command must not kill the client: the original code
				//let every non-IOException (NPE on a null move,
				//StringIndexOutOfBounds on a short move string, ...) crash
				//the process and drop the connection.
				e.printStackTrace();
			}
		}
	}

	/**
	 * T10: count one ply applied to the local board. At MAX_PLIES log a
	 * warning and keep playing — the protocol has no known draw command
	 * (docs/RULES.md, open question), so the client must not unilaterally
	 * end the game.
	 */
	static void registerPly() {
		plies++;
		if (plies == MAX_PLIES) {
			System.out.println("Warning: " + MAX_PLIES + " plies without a "
					+ "winner — possible endless play (repetition). No draw "
					+ "command is known to the protocol; keeping play going "
					+ "(docs/RULES.md).");
		}
	}

	/**
	 * Communicate the chosen move to the server.
	 * @param output
	 * @param move
	 * @throws IOException
	 */
	private static void communicateMove(BufferedOutputStream output, Move move) throws IOException {
		if (move == null) {
			//No legal move (e.g. the side to move is already lost): there is
			//nothing to send. The original code called move.toString() here and
			//crashed with a NullPointerException.
			System.out.println("No legal move to play.");
			return;
		}

		String moveParameters = move.toString();
		output.write(moveParameters.getBytes(), 0, moveParameters.length());
		output.flush();

		System.out.println(move);
		System.out.println(board);
	}

	/**
	 * Parse a move string from the server.
	 *
	 * Accepts the compact format this client itself sends ("D2E3":
	 * origin column, origin row, destination column, destination row) and the
	 * legacy 7-character layout with the origin at indices 0-1 and the
	 * destination at indices 5-6.
	 *
	 * @param raw The raw move string.
	 * @return The parsed Move, or null if the string is not a valid move.
	 */
	static Move parseMove(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.replaceAll("\\s", "");

		if (s.length() == 4 && isValidMoveQuad(s, 0, 1, 2, 3)) {
			return buildMove(s, 0, 1, 2, 3);
		}
		if (s.length() >= 7 && isValidMoveQuad(s, ORIGIN_COLUMN, ORIGIN_ROW, DESTINATION_COLUM, DESTINATION_ROW)) {
			return buildMove(s, ORIGIN_COLUMN, ORIGIN_ROW, DESTINATION_COLUM, DESTINATION_ROW);
		}
		return null;
	}

	private static boolean isValidMoveQuad(String s, int oc, int or, int dc, int dr) {
		return isColumnLetter(s.charAt(oc))
				&& isRowDigit(s.charAt(or))
				&& isColumnLetter(s.charAt(dc))
				&& isRowDigit(s.charAt(dr));
	}

	private static boolean isColumnLetter(char c) {
		return c >= 'A' && c <= 'H';
	}

	private static boolean isRowDigit(char c) {
		return c >= '1' && c <= '8';
	}

	private static Move buildMove(String s, int oc, int or, int dc, int dr) {
		int originRow = s.charAt(or) - '1';
		int originCol = s.charAt(oc) - 'A';
		int destRow = s.charAt(dr) - '1';
		int destCol = s.charAt(dc) - 'A';

		return new Move(board.getSquare(originRow, originCol), board.getSquare(destRow, destCol));
	}

	/**
	 * Handle the board payload of a '1'/'2' command (T9).
	 *
	 * The payload is read to the end of its line (newlines are the assumed
	 * server framing, docs/RULES.md item 5), validated (exactly 64 integers,
	 * each 0..4), and only then is the singleton board reset and the pieces
	 * placed. An invalid payload is consumed (resync) and logged; the board
	 * and all other game state are left untouched and the command loop waits
	 * for the next command instead of playing on a partial board.
	 *
	 * @param input     The buffered server input, positioned just after the
	 *                  command byte.
	 * @param maxPlayer The AI's player for the new game.
	 * @param minPlayer The opponent.
	 * @return true if the board was built and a new GameTree created.
	 * @throws IOException if reading the payload fails.
	 */
	static boolean beginNewGame(BufferedInputStream input, Player maxPlayer,
								  Player minPlayer) throws IOException {
		String payload = readLine(input);
		int[] values = parseBoardPayload(payload);
		if (values == null) {
			System.out.println("Invalid board payload [" + payload + "] "
					+ "(expected 64 integers 0..4, newline-terminated). "
					+ "Board left untouched; waiting for the next command.");
			return false;
		}

		board.reset();
		move = null;
		plies = 0;
		placeBoard(values);
		gameTree = new GameTree(maxPlayer, minPlayer);
		board.updateAllLegaLMoves();
		return true;
	}

	/**
	 * Read one newline-terminated payload (T9) from the server, byte by
	 * byte, so a message split or coalesced across any number of TCP reads
	 * is reassembled exactly. The pre-T9 code used input.available() plus a
	 * single read(), which silently truncated or coalesced payloads whenever
	 * the kernel did not deliver the whole message at once.
	 *
	 * The line ends at '\n' (or '\r\n'; the '\n' half of a CRLF pair is
	 * consumed so the outer command loop does not misread it as an unknown
	 * command byte). At EOF without a terminator, whatever was received is
	 * returned (a final message that lost its newline at connection close is
	 * still complete if all its payload arrived); at EOF with no payload,
	 * null.
	 *
	 * @param input The buffered server input.
	 * @return the payload with surrounding whitespace stripped, or null at
	 *         EOF without a payload.
	 * @throws IOException if reading fails.
	 */
	public static String readLine(BufferedInputStream input) throws IOException {
		StringBuilder payload = new StringBuilder();
		int b;
		while ((b = input.read()) != -1) {
			char c = (char) b;
			if (c == '\n')
				break;
			if (c == '\r') {
				input.mark(1);
				int next = input.read();
				if (next != '\n' && next != -1)
					input.reset();
				break;
			}
			payload.append(c);
		}
		if (b == -1 && payload.length() == 0)
			return null;
		return payload.toString().trim();
	}

	/**
	 * Parse and validate a board payload (T9): whitespace-separated
	 * integers, exactly 64 of them, each 0..4 (0 = empty, 1 = Black Pushee,
	 * 2 = Black Pusher, 3 = White Pushee, 4 = White Pusher). The tokens
	 * address the squares starting at H8 (row 7, col 7), right-to-left, then
	 * bottom-to-top: the 1st token is H8, the 64th A1 (docs/RULES.md item 5).
	 * Exposed for tests.
	 *
	 * @param payload The raw payload line.
	 * @return the 64 values, or null if the payload is not exactly 64
	 *         integers in 0..4 (the caller must not place a partial board).
	 */
	public static int[] parseBoardPayload(String payload) {
		if (payload == null)
			return null;
		String s = payload.trim();
		if (s.isEmpty())
			return null;
		String[] tokens = s.split("\\s+");
		if (tokens.length != BOARD_TOKEN_COUNT)
			return null;
		int[] values = new int[BOARD_TOKEN_COUNT];
		for (int i = 0; i < BOARD_TOKEN_COUNT; i++) {
			int value;
			try {
				value = Integer.parseInt(tokens[i]);
			} catch (NumberFormatException e) {
				return null;
			}
			if (value < 0 || value > 4)
				return null;
			values[i] = value;
		}
		return values;
	}

	/**
	 * Place the validated 64 board values on the (already reset) board:
	 * token 0 at H8 (row 7, col 7), right-to-left, then bottom-to-top.
	 *
	 * @param values The validated payload values (64 entries, each 0..4).
	 */
	static private void placeBoard(int[] values) {
		int x = 7, y = 7;
		for (int value : values) {

			switch (value) {

			case 1:
				board.putPiece(new Pushee(Player.BLACK), y, x);
				break;
			case 2:
				board.putPiece(new Pusher(Player.BLACK), y, x);
				break;
			case 3:
				board.putPiece(new Pushee(Player.WHITE), y, x);
				break;
			case 4:
				board.putPiece(new Pusher(Player.WHITE), y, x);
				break;
			default:
				//0: empty square.
				break;
			}

			x--;
			if (x == -1) {
				x = 7;
				y--;
			}
		}
	}

	/**
	 * Chose a move randomly based on all legal moves.
	 *
	 * @param legalMoves Array of all legal moves.
	 * @return The chosen move.
	 */
	static private Move getRandomMove(ArrayList<Move> legalMoves) {
		Random random = new Random();
		int r = random.nextInt(legalMoves.size());

		return legalMoves.get(r);
	}

	/**
	 * Transform the column's letter into an int.
	 *
	 * @param letter
	 *
	 * @return an int representing the column.
	 */
	static private int letterToInt(char letter) {
		switch (letter) {
		case 'A':
			return 0;
		case 'B':
			return 1;
		case 'C':
			return 2;
		case 'D':
			return 3;
		case 'E':
			return 4;
		case 'F':
			return 5;
		case 'G':
			return 6;
		case 'H':
			return 7;

		default:
			return -1;
		}
	}
}
