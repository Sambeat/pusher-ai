package client;

import game.GameBoard;
import game.Player;

/**
 * C5: the single strategy that replaced WhiteStrategy and BlackStrategy
 * (T8's mirror pair): parameterized by the player the score belongs to.
 *
 * Delegates to GameAnalyzer.analyzeFor: the shared evaluation where
 * advancement is measured toward the player's goal row (White: increasing
 * rows; Black: rows covered from the home row) and enemy pieces are charged
 * value + advancement + the shared defensive premium (T8).
 */
public class PlayerStrategy implements Strategy {

    private final Player player;

    public PlayerStrategy(Player player){
        this.player = player;
    }

    @Override
    public int analyze(){
        return analyze(true);
    }

    @Override
    public int analyze(boolean threatTermsEnabled){
        return GameAnalyzer.analyzeFor(GameBoard.getInstance(), player, threatTermsEnabled);
    }
}
