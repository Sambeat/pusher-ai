package client;

import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

import java.util.Locale;

/**
 * Plays a single self-play game and prints the board after every move.
 * Usage: java -cp out client.VisualGame [--budget MS]
 */
public class VisualGame {

    private static void setDefaultBoard(GameBoard board) {
        for (int col = 0; col < GameBoard.GAME_BOARD_LENGTH; col++) {
            board.putPiece(new Pusher(Player.WHITE), 0, col);
            board.putPiece(new Pushee(Player.WHITE), 1, col);
            board.putPiece(new Pusher(Player.BLACK), 7, col);
            board.putPiece(new Pushee(Player.BLACK), 6, col);
        }
    }

    public static void main(String[] args) {
        long budget = 50; // 50ms per move — fast enough to watch

        for (int i = 0; i < args.length; i++) {
            if ("--budget".equals(args[i]) && i + 1 < args.length)
                budget = Long.parseLong(args[i + 1]);
        }

        GameBoard board = GameBoard.getInstance();
        board.reset();
        setDefaultBoard(board);
        board.setCurrentPlayer(Player.WHITE);

        GameTree whiteTree = new GameTree(Player.WHITE, Player.BLACK, budget, 42L);
        GameTree blackTree = new GameTree(Player.BLACK, Player.WHITE, budget, 42L);

        System.out.println("=== Pusher — self-play game ===");
        System.out.println("White pieces start at bottom, Black at top.");
        System.out.println("Goal: get any piece to the enemy's back row.");
        System.out.println();

        printBoard(board, "Initial position");

        Player winner = null;
        int plies = 0;

        while (winner == null && plies < 100) {
            Player toMove = board.getCurrentPlayer();
            GameTree tree = (toMove == Player.WHITE) ? whiteTree : blackTree;

            Move move = tree.getBestMoveQuick();

            if (move == null) {
                System.out.println("\n" + toMove + " has no legal move — "
                        + toMove.other() + " wins by stalemate!");
                winner = toMove.other();
                break;
            }

            String moveDesc = describeMove(move);
            int depth = tree.getLastCompletedLevel();
            board.movePiece(move);
            plies++;
            winner = board.winner();

            System.out.println("Ply " + plies + ": " + toMove + " plays "
                    + moveDesc + "  (search depth: " + depth + " plies ahead)");
            printBoard(board, null);

            if (winner != null) {
                System.out.println(winner + " wins by reaching the goal row!");
            }
        }

        if (winner == null)
            System.out.println("Game truncated at 100 plies (no winner).");
    }

    private static String describeMove(Move move) {
        return coord(move.pieceLocation().getRow(),
                move.pieceLocation().getColumn()) + " → "
                + coord(move.destination().getRow(),
                        move.destination().getColumn());
    }

    private static String coord(int row, int col) {
        return "" + (char) ('A' + col) + (row + 1);
    }

    private static void printBoard(GameBoard board, String label) {
        System.out.println();
        if (label != null) {
            System.out.println("─── " + label + " ───");
        }
        System.out.println(board.toString());
    }
}
