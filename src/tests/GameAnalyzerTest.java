package tests;

import static game.Columns.*;

import client.GameAnalyzer;
import client.PlayerStrategy;
import game.GameBoard;

import game.Player;
import game.Pusher;
import org.junit.jupiter.api.*;
/**
 * Created by M on 11/8/2015.
 */
public class GameAnalyzerTest {

    private GameBoard board = GameBoard.getInstance();

    @Test
    public void testAnalyze1(){
        board.reset();
        board.putPiece(new Pusher(Player.WHITE), 3, C.pos());
        board.putPiece(new Pusher(Player.BLACK), 4, D.pos());

        System.out.println(GameAnalyzer.analyze(board, new PlayerStrategy(Player.WHITE)));
    }
}
