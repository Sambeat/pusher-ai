package client;

import game.*;

/**
 * Static evaluation of the board from WHITE's perspective.
 *
 * White advances toward row 7 (increasing rows), so a piece's advancement is
 * its row. Enemy pieces are charged a +2 defensive premium so the AI avoids
 * unfavorable trades.
 */
public class WhiteStrategy implements Strategy {

    private static final GameBoard board = GameBoard.getInstance();
    private static final Player maxPlayer = Player.WHITE;

    public int analyze(){

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
                    score += value + piece.getRow();
                }

            } else {

                score -= (2 + value);
            }

        }

        // Mobility must be counted for the player the score belongs to (WHITE),
        // not for the side to move: getAllLegalMovesQuick() returns the side to
        // move's moves, which is Black at half of all leaves.
        int ownMobility = 0;
        for (Piece piece : board.getPieceList()) {
            if (piece.getOwner() == maxPlayer) {
                ownMobility += piece.getLegalMovesQuick().size();
            }
        }
        score += ownMobility / 2;

        return score;
    }
}
