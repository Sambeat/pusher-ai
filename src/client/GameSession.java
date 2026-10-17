package client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.random.RandomGenerator;

import game.GameBoard;
import game.Move;
import game.Piece;
import game.Player;
import game.Pushee;
import game.Pusher;

/**
 * One client-side game session: the per-connection mutable state of the
 * Pusher client and its command loop, factored out of
 * {@link PusherApplication} (C12: the state used to be
 * {@code public static} there, so the warning-cap test had to poke and
 * restore globals and all state of every connection was shared and
 * unconfigurable).
 *
 * A session owns:
 * <ul>
 * <li>{@code move} — the move chosen but not yet applied to the local
 *     board (applied on the next '3');</li>
 * <li>{@code gameTree} — the search tree for the side the AI plays;</li>
 * <li>{@code plies} — plies applied to the local board in the current
 *     game, and the per-session {@code maxPlies} warning cap (T10).</li>
 * </ul>
 *
 * The board is the shared {@link GameBoard} singleton (its invariants —
 * cached legal-move lists kept consistent by movePiece/undo/revertToState —
 * are a deliberate engine decision, see CLEANUP_PLAN.md), so a session only
 * holds a reference to it; one session = one connection = one game at a
 * time (a new '1'/'2' command restarts the session's game state in place).
 *
 * The wire protocol, stdout, and the "one bad command must not kill the
 * client" boundary are byte-identical to the pre-C12 static
 * implementation; only the ownership of the state changed.
 *
 * @since C12
 */
public class GameSession {

	private static final int ORIGIN_COLUMN = 0;
	private static final int ORIGIN_ROW = 1;
	private static final int DESTINATION_COL = 5;
	private static final int DESTINATION_ROW = 6;

	/**
	 * T10: the default cap on plies without a winner. After this many plies
	 * the client logs a warning but keeps playing — the protocol has no draw
	 * command (docs/RULES.md item 7, resolved: under the confirmed rules a
	 * game always ends, so this cap is a defensive safety net, never a rule
	 * trigger). Per-session final: a test that wants a small cap builds a
	 * dedicated session (tests.RepetitionTest) instead of poking a global.
	 */
	public static final int DEFAULT_MAX_PLIES = 300;

	private final GameBoard board = GameBoard.getInstance();
	private final int maxPlies;

	private Move move;
	private GameTree gameTree;

	/**
	 * T10: plies applied to the local board in the current game. The command
	 * loop ({@link #beginGame}) is the only reset point;
	 * {@link #registerPly} is the only writer between games.
	 */
	private int plies = 0;

	/**
	 * One generator per class (not per session), exactly like the pre-C12
	 * static on PusherApplication: the '4' command's random picks keep the
	 * same draw sequence a long-lived client used to produce.
	 */
	private static final RandomGenerator RANDOM = RandomGenerator.getDefault();

	/** A session with the default {@link #DEFAULT_MAX_PLIES} cap. */
	public GameSession() {
		this(DEFAULT_MAX_PLIES);
	}

	/**
	 * @param maxPlies The ply cap after which the session logs the endless-
	 *                 play warning once and keeps playing (see
	 *                 {@link #DEFAULT_MAX_PLIES} for why this is a safety
	 *                 net, never a draw trigger).
	 */
	public GameSession(int maxPlies) {
		this.maxPlies = maxPlies;
	}

	public int getPlies() {
		return plies;
	}

	public int getMaxPlies() {
		return maxPlies;
	}

	/**
	 * The command loop of the Pusher client, factored out of
	 * {@link PusherApplication#main(String[])} (T2; the loop itself moved
	 * from static PusherApplication to this session in C12) so tests can
	 * drive the app with in-memory streams or the mock server instead of a
	 * live socket on port 8888.
	 *
	 * Wire protocol (docs/RULES.md item 5, server-confirmed): there is NO
	 * message framing — the server sends a command byte followed by its
	 * payload (the 64-int board after '1'/'2', the move after '3'), and the
	 * payload is read in one input.available()-based read, as the original
	 * 2015 client did. T9's newline-terminated read was an unconfirmed
	 * assumption and was reverted (T12): a server that sends no newlines
	 * would hang a newline-based reader, and the original read is what
	 * worked in production.
	 *
	 * @param in  Stream from the server (command bytes + payloads).
	 * @param out Stream to the server (the chosen moves).
	 * @throws IOException If reading from the server fails. The loop exits
	 *                     normally when the server closes the connection.
	 */
	public void run(InputStream in, OutputStream out) throws IOException {
		//The caller (PusherApplication.main / the tests) may or may not hand
		//over buffered streams; wrapping them again would only add a
		//redundant buffering layer.
		BufferedInputStream input = in instanceof BufferedInputStream bis
				? bis : new BufferedInputStream(in);
		BufferedOutputStream output = out instanceof BufferedOutputStream bos
				? bos : new BufferedOutputStream(out);

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
				case '1' -> beginGame(input, output, true, true);
				case '2' -> beginGame(input, output, false, false);
				case '3' -> applyOpponentPly(input, output);
				case '4' -> playRandomMove(output);
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
	 * Commands '1' (we play white) and '2' (we play black): start a fresh
	 * game from the board payload the server just sent.
	 *
	 * @param aiPlaysWhite     true for '1' (we are the maximizing player),
	 *                         false for '2'.
	 * @param replyImmediately true for '1': answer with the best move right
	 *                         away; '2' stays silent and waits for the
	 *                         opponent's first move.
	 * @throws IOException
	 */
	private void beginGame(BufferedInputStream input, BufferedOutputStream output,
			boolean aiPlaysWhite, boolean replyImmediately) throws IOException {
		//A fresh game: clear whatever the previous game left on
		//the board (leftover pieces, turn player, turn number,
		//capture log) and any stale move from the old game.
		board.reset();
		move = null;
		plies = 0;
		createInitialBoard(input);
		gameTree = aiPlaysWhite ? new GameTree(Player.WHITE, Player.BLACK)
				: new GameTree(Player.BLACK, Player.WHITE);
		board.updateAllLegaLMoves();
		if (replyImmediately) {
			move = gameTree.getBestMoveQuick();
			communicateMove(output, move);
		}
	}

	/**
	 * Command '3': apply the move we chose, read and apply the opponent's
	 * move (one available()-based read, the confirmed wire behavior),
	 * answer with our next best move.
	 *
	 * @throws IOException
	 */
	private void applyOpponentPly(BufferedInputStream input,
			BufferedOutputStream output) throws IOException {
		if (move != null) {
			board.movePiece(move);
			registerPly();
		}
		move = null;

		byte[] aBuffer = new byte[16];

		int size = input.available();
		if (size > 0) {
			input.read(aBuffer, 0, size);
		}

		String s = new String(aBuffer).trim();

		Move opponentMove = parseMove(s);
		if (opponentMove == null) {
			//The board would be out of sync with the server from
			//here on: report it and wait for the next command
			//instead of corrupting the board state.
			System.out.println("Could not parse opponent move: [" + s + "]");
			return;
		}

		board.movePiece(opponentMove);
		registerPly();

		move = gameTree.getBestMoveQuick();
		communicateMove(output, move);
		board.updateAllLegaLMoves();
	}

	/**
	 * Command '4': play a random legal move (or nothing if there is none).
	 *
	 * @throws IOException
	 */
	private void playRandomMove(BufferedOutputStream output) throws IOException {
		List<Move> legalMoves = board.getAllLegalMovesQuick();
		move = legalMoves.isEmpty() ? null : getRandomMove(legalMoves);
		communicateMove(output, move);
	}

	/**
	 * T10: count one ply applied to the local board. At maxPlies log a
	 * warning and keep playing — the protocol has no draw command, and under
	 * the confirmed rules a game always ends anyway (docs/RULES.md item 7),
	 * so the client never unilaterally ends the game.
	 */
	private void registerPly() {
		plies++;
		if (plies == maxPlies) {
			System.out.println("Warning: " + maxPlies + " plies without a "
					+ "winner — possible endless play. No draw command is "
					+ "known to the protocol; keeping play going "
					+ "(docs/RULES.md item 7).");
		}
	}

	/**
	 * Communicate the chosen move to the server.
	 * @param output
	 * @param move
	 * @throws IOException
	 */
	private void communicateMove(BufferedOutputStream output, Move move) throws IOException {
		if (move == null) {
			//No legal move (e.g. the side to move is already lost): there is
			//nothing to send. The original code called move.toString() here and
			//crashed with a NullPointerException.
			System.out.println("No legal move to play.");
			return;
		}

		//The initial 4-char format ("D2E3"), confirmed as the right one by the
		//server owner (T11 follow-up: "the initial format was the right one").
		String moveParameters = move.toString();
		output.write(moveParameters.getBytes(StandardCharsets.US_ASCII));
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
	private Move parseMove(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.replaceAll("\\s", "");

		if (s.length() == 4 && isValidMoveQuad(s, 0, 1, 2, 3)) {
			return buildMove(s, 0, 1, 2, 3);
		}
		if (s.length() >= 7 && isValidMoveQuad(s, ORIGIN_COLUMN, ORIGIN_ROW, DESTINATION_COL, DESTINATION_ROW)) {
			return buildMove(s, ORIGIN_COLUMN, ORIGIN_ROW, DESTINATION_COL, DESTINATION_ROW);
		}
		return null;
	}

	private boolean isValidMoveQuad(String s, int oc, int or, int dc, int dr) {
		return isColumnLetter(s.charAt(oc))
				&& isRowDigit(s.charAt(or))
				&& isColumnLetter(s.charAt(dc))
				&& isRowDigit(s.charAt(dr));
	}

	private boolean isColumnLetter(char c) {
		return c >= 'A' && c <= 'H';
	}

	private boolean isRowDigit(char c) {
		return c >= '1' && c <= '8';
	}

	private Move buildMove(String s, int oc, int or, int dc, int dr) {
		int originRow = s.charAt(or) - '1';
		int originCol = s.charAt(oc) - 'A';
		int destRow = s.charAt(dr) - '1';
		int destCol = s.charAt(dc) - 'A';

		return new Move(board.getSquare(originRow, originCol), board.getSquare(destRow, destCol));
	}

	/**
	 * Create the initial game board of the game.
	 *
	 * The payload is read in one input.available()-based read — the
	 * original, server-confirmed wire behavior (docs/RULES.md item 5): the
	 * server sends the command byte and the payload together, and there is
	 * no framing (no newline, no length prefix).
	 *
	 * The tokens address the squares starting at H8 (row 7, col 7),
	 * right-to-left, then bottom-to-top: the 1st token is H8, the 64th A1 —
	 * the original order, which the server owner confirmed as the wire
	 * format (T12: "the old original code probably had the orientation
	 * right too"; the original client parsed the real server correctly).
	 *
	 * @param input
	 * @throws IOException
	 */
	private void createInitialBoard(BufferedInputStream input) throws IOException {
		byte[] aBuffer = new byte[1024];

		int size = input.available();
		if (size <= 0) {
			throw new IOException("No board data received.");
		}
		input.read(aBuffer, 0, size);
		String s = new String(aBuffer).trim();
		System.out.println(s);
		String[] boardValues;
		boardValues = s.split(" ");
		int x = 7, y = 7;
		for (int i = 0; i < boardValues.length; i++) {

			//Unknown tokens stay null: no piece, but the x/y walk below
			//advances exactly as before.
			Piece piece = switch (Integer.parseInt(boardValues[i])) {
			case 1 -> new Pushee(Player.BLACK);
			case 2 -> new Pusher(Player.BLACK);
			case 3 -> new Pushee(Player.WHITE);
			case 4 -> new Pusher(Player.WHITE);
			default -> null;
			};
			if (piece != null) {
				board.putPiece(piece, y, x);
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
	 * @param legalMoves List of all legal moves.
	 * @return The chosen move.
	 */
	private Move getRandomMove(List<Move> legalMoves) {
		int r = RANDOM.nextInt(legalMoves.size());

		return legalMoves.get(r);
	}
}
