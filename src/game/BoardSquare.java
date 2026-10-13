package game;

/**
 * This class represent a square of a GameBoard.
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public class BoardSquare implements Cloneable {

    //The piece in this square
    private Piece piece;

    private int row;
    private Columns column;

    public BoardSquare(Piece piece, int row, Columns column){
        this.piece = piece;
        this.row = row;
        this.column = column;
    }

    public BoardSquare(int row, Columns column){
        this(null, row, column);
    }

    public String toString(){

        return column.toString() + Integer.toString(row + 1);
    }

    @Override
    public BoardSquare clone() throws CloneNotSupportedException {
        BoardSquare clone = (BoardSquare)super.clone();

        clone.piece = (Piece)piece.clone();
        clone.row = row;
        clone.column = column;

        return clone;
    }

    public int getRow(){
        return row;
    }

    public int getColumn(){
        return column.pos();
    }

    public void setPiece(Piece piece){
        this.piece = piece;

        if (piece != null)
            piece.setPosition(this);
    }

    public boolean isInSameColumn(BoardSquare other){
        return column == other.column;
    }

    public Piece getPiece(){
        return piece;
    }

    public boolean hasPusher(){
        return piece instanceof  Pusher;
    }

    public boolean hasPushee(){
        return piece instanceof Pushee;
    }

    /**
     * Return if this BoardSquare is empty. That is, it has no Piece.
     * @return True if empty, false otherwise.
     */
    public boolean isEmpty(){
        return piece == null;
    }

    /**
     * Remove any Piece in this BoardSquare.
     */
    public void emptySquare(){
        piece = null;
    }
}
