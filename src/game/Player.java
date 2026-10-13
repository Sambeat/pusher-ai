package game;

/**
 * Player contains the information of the symbol to use to represent each
 * player's Pieces on the board.
 * @author Samuel Charade
 * @since 10/31/2015.
 */
public enum Player {

   WHITE ('O', 1),
   BLACK ('X', -1);


    private char symbol;
    private int direction;

    Player(char symbol, int direction){
        this.symbol = symbol;
        this.direction = direction;
    }

    public char symbol(){
        return symbol;
    }

    public int direction(){
        return direction;
    }

    public Player other(){
        if (this.symbol == 'O')
            return Player.BLACK;
        else
            return Player.WHITE;
    }


}
