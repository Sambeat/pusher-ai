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

	static GameBoard board = GameBoard.getInstance();
	static Move move;
	static GameTree gameTree;

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
					//A fresh game: clear whatever the previous game left on
					//the board (leftover pieces, turn player, turn number,
					//capture log) and any stale move from the old game.
					board.reset();
					move = null;
					createInitialBoard(input);
					gameTree = new GameTree(Player.WHITE, Player.BLACK);
					board.updateAllLegaLMoves();

					move = gameTree.getBestMoveQuick();
					communicateMove(output, move);
					break;
				case '2':
					board.reset();
					move = null;
					createInitialBoard(input);
					gameTree = new GameTree(Player.BLACK, Player.WHITE);
					board.updateAllLegaLMoves();
					break;
				case '3':
					if (move != null)
						board.movePiece(move);
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
						break;
					}

					board.movePiece(opponentMove);

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
	 * Create the initial game board of the game.
	 *
	 * @param input
	 * @throws IOException
	 */
	static private void createInitialBoard(BufferedInputStream input) throws IOException {
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

			switch (Integer.parseInt(boardValues[i])) {

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
