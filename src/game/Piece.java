package game;

import java.util.ArrayList;

/**
 * Piece is an abstract representation of the pieces that are on the GameBoard.
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public sealed abstract class Piece implements Cloneable permits Pusher, Pushee {

    static final int POSSIBLE_MOVES = 3;


    private Player owner;
    private BoardSquare position;

    ArrayList<BoardSquare> legalDestinations;
    ArrayList<Move> legalMoves;

    public Piece(Player owner){
        this.owner = owner;
        this.position = null;
        legalDestinations = new ArrayList<>();
        legalMoves = new ArrayList<>();
    }

    @Override
    public Piece clone(){
        try {
            Piece clone = (Piece) super.clone();

            clone.owner = this.owner;

            return clone;
        } catch (CloneNotSupportedException e){
            return null;
        }
    }

    public Player getOwner(){
        return owner;
    }

    /**
     * Place the current Piece on the board on the specified row and column.
     * @param i The row on which to put the Piece.
     * @param j The column on which to put the Piece.
     */
    protected void setPosition(int i, int j){
        GameBoard theBoard = GameBoard.getInstance();

        setPosition(theBoard.getSquare(i, j));
    }

    /**
     * Place the current Piece on the board on the specified BoardSquare.
     * @param position The BoardSquare on which to place the Piece.
     */
    protected void setPosition(BoardSquare position){
        this.position = position;
        updateLegalMoves();
    }

    public abstract boolean canPush();

    public BoardSquare getPosition(){
        return position;
    }

    /**
     * Return the row in which this piece is located.
     * @return The row in which this piece is located.
     */
    public int getRow(){
        return position.getRow();
    }

    /**
     * Returns if the specified owner is this Piece owner
     * @return True if the specified owner is this Piece owner, false otherwise.
     */
    public boolean isOwner(Player player){
        return owner == player;
    }

    /**
     * Return the column in which this piece is located.
     * @return The column in which this piece is located.
     */
    public int getColumn(){
        return position.getColumn();
    }

    public abstract int getScoreValue();

    /**
     * Update the list of legal moves of this piece based on its current
     * surroundings. This should always be called on the Piece that moves
     * and on Pieces surrounding its initial location and its destination.
     */
    public void updateLegalMoves(){
        legalDestinations.clear();
        legalMoves.clear();

        computeLegalDestinations();
        computeLegalMoves();
    }

    abstract void computeLegalMoves();

    /**
     * Verify the occupancy of the 3 squares in front of this piece and compute
     * the legal destinations.
     */
    private void computeLegalDestinations(){
        GameBoard gameBoard = GameBoard.getInstance();

        for (int i = 0; i < POSSIBLE_MOVES; i++){
            BoardSquare possibleSquare =
                    gameBoard.getSquare(getRow() + getDirection(), getColumn() - 1 + i);

            //In case the piece is at the edge of the board.
            if (possibleSquare != null) {

                if (!possibleSquare.isInSameColumn(position)){
                    Piece pieceInPossibleSquare = possibleSquare.getPiece();

                    if (!(pieceInPossibleSquare != null
                            && pieceInPossibleSquare.getOwner().equals(this.getOwner()))){
                        legalDestinations.add(possibleSquare);
                    }
                } else {

                    if (possibleSquare.isEmpty())
                        legalDestinations.add(possibleSquare);

                }

            }

        }

    }

    /**
     * Returns all legal moves for this Piece. Must call updateLegalMoves() after
     * every move to ensure this methods will return actual legal moves.
     * @return The legally playable moves for this Piece.
     */
    public ArrayList<Move> getLegalMovesQuick(){
        return legalMoves;
    }

    /**
     * Get the Player color dependant direction multiplier. If a Piece is owned
     * by the WHITE player, it only can move to increasing rows. If a Piece is
     * owned by the BLACK player, it only can move to decreasing rows.
     * @return 1 if the owner is WHITE, -1 if the owner is BLACK.
     */
    public int getDirection(){
        return owner.direction();
    }
}
