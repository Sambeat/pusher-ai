package client;

import game.GameBoard;
import game.Player;

/**
 * Static evaluation of the board from WHITE's perspective.
 *
 * Delegates to GameAnalyzer.analyzeFor(board, Player.WHITE): the single
 * shared evaluation that BlackStrategy mirrors. White advances toward row 7
 * (increasing rows), so a piece's advancement is its row; enemy pieces are
 * charged value + advancement + the shared defensive premium (T8).
 */
public class WhiteStrategy implements Strategy {

    private static final GameBoard board = GameBoard.getInstance();

    @Override
    public int analyze(){
        return GameAnalyzer.analyzeFor(board, Player.WHITE);
    }
}
