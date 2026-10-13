package client;

import game.GameBoard;
import game.Piece;
import game.Player;

import java.util.ArrayList;

/**
 * Role is to evaluate the state of the board and return a corresponding score
 * for the minmax algorithm.
 * @author Samuel Charade
 * @since 11/06/2015.
 */
public class GameAnalyzer {

    public static int analyze(GameBoard board, Strategy strategy){
        return strategy.analyze();
    }

}
