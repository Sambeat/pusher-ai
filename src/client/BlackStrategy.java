package client;

import game.GameBoard;
import game.Player;

/**
 * Static evaluation of the board from BLACK's perspective.
 *
 * Delegates to GameAnalyzer.analyzeFor(board, Player.BLACK): the mirror of
 * WhiteStrategy (T8). Black advances toward row 0 (decreasing rows), so a
 * piece's advancement is (GAME_BOARD_LENGTH - 1 - row): the number of rows
 * covered from the home row.
 */
public class BlackStrategy implements Strategy {

    private static final GameBoard board = GameBoard.getInstance();

    @Override
    public int analyze(){
        return analyze(true);
    }

    @Override
    public int analyze(boolean threatTermsEnabled){
        return GameAnalyzer.analyzeFor(board, Player.BLACK, threatTermsEnabled);
    }
}
