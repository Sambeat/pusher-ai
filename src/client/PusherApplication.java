package client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Random;

import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

/**
 * PusherApplication is the main class for an artificial intelligence playing
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
		//Je suis un changement.
		Socket MyClient;
		BufferedInputStream input;
		BufferedOutputStream output;

		try {
			System.out.println("Creating socket to localhost:8888");
			MyClient = new Socket("localhost", 8888);
			input = new BufferedInputStream(MyClient.getInputStream());
			output = new BufferedOutputStream(MyClient.getOutputStream());
			System.out.println("Got socket with Streams");

			while (1 == 1) {

				char cmd = (char) input.read();

				switch (cmd) {
				case '1':
					createInitialBoard(input);
					gameTree = new GameTree(Player.WHITE, Player.BLACK);
					board.updateAllLegaLMoves();

					move = gameTree.getBestMoveQuick();
					communicateMove(output, move);
					break;
				case '2':
					createInitialBoard(input);
					gameTree = new GameTree(Player.BLACK, Player.WHITE);
					board.updateAllLegaLMoves();
					break;
				case '3':
					if (move != null)
						board.movePiece(move);

					byte[] aBuffer = new byte[16];

					int size = input.available();
					input.read(aBuffer, 0, size);

					String s = new String(aBuffer).trim();
					board.movePiece(Character.getNumericValue(s.charAt(ORIGIN_ROW)) - 1,
							letterToInt(s.charAt(ORIGIN_COLUMN)),
							Character.getNumericValue(s.charAt(DESTINATION_ROW)) - 1,
							letterToInt(s.charAt(DESTINATION_COLUM)));

					move = gameTree.getBestMoveQuick();
					communicateMove(output, move);
					board.updateAllLegaLMoves();
					break;
				case '4':
					move = getRandomMove(board.getAllLegalMovesQuick());
					communicateMove(output, move);
					break;
				}

			}
		} catch (IOException e) {

		}
	}

	/**
	 * Communicate the chosen move to the server.
	 * @param output
	 * @param move
	 * @throws IOException
	 */
	private static void communicateMove(BufferedOutputStream output, Move move) throws IOException {
		String moveParameters = move.toString();
		output.write(moveParameters.getBytes(), 0, moveParameters.length());
		output.flush();

		System.out.println(move);
		System.out.println(board);
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
