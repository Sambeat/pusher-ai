package tests;

import client.GameTree;
import game.GameBoard;
import game.Move;
import game.Player;
import game.Pushee;
import game.Pusher;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

import java.util.ArrayList;

/**
 * Characterization tests for the rules contract documented in docs/RULES.md.
 *
 * They pin the rules the engine implements today (items 1-4: win condition,
 * captures, stalemate, pushee mirror rule). They pass against the current code
 * by definition; their purpose is to force a deliberate, documented change
 * (update docs/RULES.md first) if the server owner's answers require different
 * rules.
 *
 * Coordinates: (row, col) with row 0 = top = BLACK's goal row, row 7 = bottom
 * = WHITE's goal row, col 0 = file A. WHITE moves to increasing rows.
 *
 * Expected move lists are order-sensitive: getAllLegalMovesQuick() iterates
 * pieces in placement order, and a piece's destinations are generated in the
 * order (col-1, col, col+1).
 *
 * @since T5
 */
public class RulesContractTest {

    private final GameBoard board = GameBoard.getInstance();
    private final GameTree whiteTree = new GameTree(Player.WHITE, Player.BLACK);

    @After
    public void cleanBoard() {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    // --- helpers -----------------------------------------------------------------

    private void setupWhiteToMove() {
        board.reset();
        board.setCurrentPlayer(Player.WHITE);
    }

    private void setupBlackToMove() {
        board.reset();
        board.setCurrentPlayer(Player.BLACK);
    }

    private Move move(int fromRow, int fromCol, int toRow, int toCol) {
        return new Move(board.getSquare(fromRow, fromCol), board.getSquare(toRow, toCol));
    }

    private ArrayList<Move> legalMoves() {
        return board.getAllLegalMovesQuick();
    }

    // ------------------------------------------------------------------- item 1
    // Win condition: any own piece (Pusher OR Pushee) on the enemy goal row.

    @Test
    public void blackPusherOnRowZeroWinsForBlack() {
        board.reset();
        board.putPiece(new Pusher(Player.BLACK), 0, 2);
        assertEquals(Player.BLACK, board.winner());
    }

    @Test
    public void blackPusheeOnRowZeroWinsForBlack() {
        board.reset();
        board.putPiece(new Pushee(Player.BLACK), 0, 5);
        assertEquals(Player.BLACK, board.winner());
    }

    @Test
    public void whitePusherOnRowSevenWinsForWhite() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 7, 3);
        assertEquals(Player.WHITE, board.winner());
    }

    @Test
    public void whitePusheeOnRowSevenWinsForWhite() {
        board.reset();
        board.putPiece(new Pushee(Player.WHITE), 7, 6);
        assertEquals(Player.WHITE, board.winner());
    }

    @Test
    public void ownPieceOnOwnGoalRowDoesNotWin() {
        board.reset();
        board.putPiece(new Pusher(Player.BLACK), 7, 3); // Black on its OWN goal row
        board.putPiece(new Pusher(Player.WHITE), 0, 4); // White on its OWN goal row
        assertNull(board.winner());
    }

    @Test
    public void noPiecesOnGoalRowsNoWinner() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.BLACK), 4, 4);
        assertNull(board.winner());
    }

    /**
     * Rule (deliberate decision): when both sides are on their goal row at the
     * same time, BLACK always wins the tie — no matter which columns the pieces
     * are on. winner() scans BLACK's goal row in full before WHITE's.
     */
    @Test
    public void bothSidesOnGoalRowsBlackAlwaysWinsTheTie() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 7, 3); // lower column...
        board.putPiece(new Pusher(Player.BLACK), 0, 4); // ...yet BLACK still wins
        assertEquals(Player.BLACK, board.winner());
    }

    @Test
    public void bothSidesOnGoalRowsSameColumnBlackWins() {
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 7, 3);
        board.putPiece(new Pusher(Player.BLACK), 0, 3);
        assertEquals(Player.BLACK, board.winner());
    }

    /**
     * Pins the goal rows as named constants: row 0 (top) is BLACK's, row 7
     * (bottom) is WHITE's.
     */
    @Test
    public void goalRowsArePinned() {
        assertEquals(0, GameBoard.BLACK_GOAL_ROW);
        assertEquals(GameBoard.GAME_BOARD_LENGTH - 1, GameBoard.WHITE_GOAL_ROW);
        assertEquals(7, GameBoard.WHITE_GOAL_ROW);
    }

    // ------------------------------------------------------------------- item 2
    // Captures: diagonal capture legal, straight-ahead capture illegal.

    /**
     * A diagonal destination occupied by an enemy piece is a legal capture.
     */
    @Test
    public void whiteDiagonalCaptureIsLegal() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.BLACK), 4, 2); // diagonally in front
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(3, 3, 4, 2)); // the capture
        expected.add(move(3, 3, 4, 3)); // straight, empty
        expected.add(move(3, 3, 4, 4)); // diagonal, empty
        assertEquals(expected, legalMoves());
    }

    /**
     * A straight destination must be empty: with enemy pieces on all three
     * forward squares, the diagonals are capturable but the straight square is
     * not.
     */
    @Test
    public void whiteStraightCaptureIsIllegal() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.BLACK), 4, 2); // diagonal: capturable
        board.putPiece(new Pusher(Player.BLACK), 4, 3); // straight: NOT capturable
        board.putPiece(new Pusher(Player.BLACK), 4, 4); // diagonal: capturable
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(3, 3, 4, 2));
        expected.add(move(3, 3, 4, 4));
        assertEquals(expected, legalMoves());
    }

    /**
     * Same rules for Black (direction -1), mirrored vertically.
     */
    @Test
    public void blackDiagonalCaptureLegalStraightIllegal() {
        setupBlackToMove();
        board.putPiece(new Pusher(Player.BLACK), 4, 3);
        board.putPiece(new Pusher(Player.WHITE), 3, 2); // diagonal: capturable
        board.putPiece(new Pusher(Player.WHITE), 3, 3); // straight: NOT capturable
        board.putPiece(new Pusher(Player.WHITE), 3, 4); // diagonal: capturable
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(4, 3, 3, 2));
        expected.add(move(4, 3, 3, 4));
        assertEquals(expected, legalMoves());
    }

    /**
     * Items 2 + 4 combined: a Pushee captures by diagonal push — the diagonal
     * destination may hold an enemy piece, provided the mirror-Pusher
     * requirement is met.
     */
    @Test
    public void pusheeCanCaptureDiagonallyWhenPushed() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.WHITE), 2, 2); // mirror square for the (4,4) destination
        board.putPiece(new Pusher(Player.BLACK), 4, 4); // the capture target
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(3, 3, 4, 4)); // the pushee's capture
        expected.add(move(2, 2, 3, 1)); // the pusher's own moves
        expected.add(move(2, 2, 3, 2));
        assertEquals(expected, legalMoves());
    }

    // ------------------------------------------------------------------- item 3
    // Stalemate: no legal moves = loss, ranked below a goal-row win.

    /**
     * White to move has two kinds of moves: the pusher on row 6 can step onto
     * its goal row (immediate win), the pusher on row 5 can only reach row 6 —
     * after any such move Black (a lone Pushee with no Pusher behind) has no
     * legal move, i.e. a stalemate win. At depth 1 both terminals are visible;
     * the engine must take the goal-row win (the stalemate terminal is strictly
     * lower via GameTree.STALEMATE_MARGIN).
     */
    @Test
    public void goalRowWinIsPreferredOverStalemateWin() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pusher(Player.WHITE), 6, 3);
        board.putPiece(new Pushee(Player.BLACK), 4, 4); // no Pusher behind: Black has no moves
        board.updateAllLegaLMoves();

        Move best = whiteTree.getBestMoveQuick(1);

        assertNotNull(best);
        assertEquals("the goal-row win must outrank the stalemate win at the same depth",
                7, best.getDestination().getRow());
    }

    /**
     * A stalemate win is still a win: when stalemating the opponent is the best
     * (and only) available outcome, the engine must still play — the current
     * rules treat "no legal moves" as a loss, not a draw.
     */
    @Test
    public void stalemateWinIsStillTaken() {
        setupWhiteToMove();
        board.putPiece(new Pusher(Player.WHITE), 5, 3);
        board.putPiece(new Pushee(Player.BLACK), 4, 4); // Black has no legal move
        board.updateAllLegaLMoves();

        Move best = whiteTree.getBestMoveQuick(1);

        assertNotNull("the engine must play to stalemate the opponent, not stall", best);
        assertEquals(6, best.getDestination().getRow());
    }

    // ------------------------------------------------------------------- item 4
    // Pushee movement: the mirror-square Pusher requirement.

    /**
     * Straight push: a friendly Pusher directly behind enables the straight
     * destination and nothing else.
     */
    @Test
    public void straightPushNeedsPusherDirectlyBehind() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.WHITE), 2, 3); // directly behind
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(3, 3, 4, 3)); // the straight push
        expected.add(move(2, 3, 3, 2)); // the pusher's own moves
        expected.add(move(2, 3, 3, 4));
        assertEquals(expected, legalMoves());
    }

    /**
     * Diagonal push: a Pusher diagonally behind-left enables the diagonal push
     * to the right, and nothing else.
     */
    @Test
    public void diagonalPushNeedsPusherDiagonallyBehind() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.WHITE), 2, 2); // behind-left: enables the push to (4,4)
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(3, 3, 4, 4));
        expected.add(move(2, 2, 3, 1));
        expected.add(move(2, 2, 3, 2));
        assertEquals(expected, legalMoves());
    }

    /**
     * The mirror square is exact, not "any Pusher nearby": a Pusher on (2,4)
     * (behind-right) enables only the diagonal push to (4,2) (to the left),
     * not the push to (4,4).
     */
    @Test
    public void mirrorSquareIsExact() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.WHITE), 2, 4); // behind-right
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(3, 3, 4, 2)); // enabled by the (2,4) Pusher
        expected.add(move(2, 4, 3, 4)); // the pusher's own moves
        expected.add(move(2, 4, 3, 5));
        assertEquals(expected, legalMoves());
    }

    /**
     * The Pusher behind must be friendly: an enemy Pusher on the mirror square
     * does not enable the push, so the Pushee has no legal move at all.
     */
    @Test
    public void enemyPusherBehindDoesNotEnableThePush() {
        setupWhiteToMove();
        board.putPiece(new Pushee(Player.WHITE), 3, 3);
        board.putPiece(new Pusher(Player.BLACK), 2, 3); // enemy on the mirror square
        board.updateAllLegaLMoves();

        assertTrue("White's Pushee must have no legal move", legalMoves().isEmpty());
    }

    /**
     * Black side (direction -1): the mirror square is behind in Black's
     * direction of travel (higher rows).
     */
    @Test
    public void blackPusheeMirrorRule() {
        setupBlackToMove();
        board.putPiece(new Pushee(Player.BLACK), 4, 3);
        board.putPiece(new Pusher(Player.BLACK), 5, 3); // directly behind (row 5)
        board.updateAllLegaLMoves();

        ArrayList<Move> expected = new ArrayList<>();
        expected.add(move(4, 3, 3, 3)); // the straight push toward row 0
        expected.add(move(5, 3, 4, 2)); // the pusher's own moves
        expected.add(move(5, 3, 4, 4));
        assertEquals(expected, legalMoves());
    }
}
