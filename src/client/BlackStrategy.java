package client;

import game.*;

/**
 * Static evaluation of the board from BLACK's perspective.
 *
 * Black advances toward row 0 (decreasing rows), so a piece's advancement is
 * (GAME_BOARD_LENGTH - 1 - row): the number of rows covered from the home row.
 * The original code added piece.getRow() for own pieces, which rewarded pieces
 * sitting near home and punished progress - the opposite of the intent.
 */
public class BlackStrategy implements Strategy {

    private static final GameBoard board = GameBoard.getInstance();
    private static final Player maxPlayer = Player.BLACK;

    @Override
    public int analyze() {
        int score = 0;

        for (Piece piece : board.getPieceList()) {
            int value = piece.getScoreValue();

            if (piece.getOwner() == maxPlayer) {

                if (value == 3 && (piece.getColumn() == Columns.A.pos()
                        || piece.getColumn() == Columns.H.pos())){

                    //A Pushee on an edge file is worthless: it contributes nothing.
                    //The original code was missing the parentheses, which made
                    //ANY own piece on file H (including Pushers) worth 0.

                } else {
                    score += value
                            + (GameBoard.GAME_BOARD_LENGTH - 1 - piece.getRow());
                }

            } else {

                //White advances toward row 7 (increasing rows), so a more
                //advanced enemy piece has a higher row and a larger penalty.
                score -= (value + piece.getRow());
            }

        }

        return score;
    }
}
