package game;

/**
 * A Pushee is a Piece that can only move if a Pusher is behind it.
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public final class Pushee extends Piece{

    private static final int LOWERCASE_DELTA = 32;
    public static final int VALUE = 3;

    public Pushee(Player owner){
        super(owner);
    }

    @Override
    void computeLegalMoves() {
        GameBoard board = GameBoard.getInstance();

        //Check for every possible destination there is a Pusher in the opposite
        //direction.
        for (BoardSquare legalDestination : legalDestinations){

            //Check the opposite direction square.
            BoardSquare possiblePusherSquare = board.getSquare(
                    (getRow() - getDirection()),
                    (getColumn() + (getColumn() - legalDestination.getColumn()))
            );

            Piece possiblePusher = null;
            if (possiblePusherSquare != null)
                possiblePusher = possiblePusherSquare.getPiece();

            //Make sure its populated by a Pusher owned by this player.
            if (possiblePusher instanceof Pusher p && p.isOwner(this.getOwner()))
                legalMoves.add(new Move(this.getPosition(), legalDestination));

        }
    }

    public String toString(){
        return Character.toString((char) (getOwner().symbol() + LOWERCASE_DELTA));
    }

    public boolean canPush(){
        return false;
    }

    @Override
    public int getScoreValue() {
        return VALUE;
    }

}
