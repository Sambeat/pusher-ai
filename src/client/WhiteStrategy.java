package client;

import game.*;

/**
 * Created by Sam on 11/9/2015.
 */
public class WhiteStrategy implements Strategy {

    private static final GameBoard board = GameBoard.getInstance();
    private static final Player maxPlayer = Player.WHITE;

    public int analyze(){

        int score = 0;

        for (Piece piece : board.getPieceList()) {
            int value = piece.getScoreValue();

            if (piece.getOwner() == maxPlayer) {


                if (value == 3 && piece.getColumn() == Columns.A.pos() ||
                        piece.getColumn() == Columns.H.pos()){

                } else {
                    score += value;
                }

            } else {

                score -= (2 + value);
            }

        }

        score += board.getAllLegalMovesQuick().size() / 2;
        return score;
    }
}
