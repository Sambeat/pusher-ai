package game;

/**
 * A Pusher is a Piece that can move by itself or move a Pushee by "pushing" it.
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public final class Pusher extends Piece {

    public static final int VALUE = 7;

    public Pusher(Player owner){
        super(owner);
    }

    public String toString(){
        return Character.toString(getOwner().symbol());
    }

    public boolean canPush(){
        return true;
    }

    @Override
    public int getScoreValue() {
        return VALUE;
    }

    @Override
    void computeLegalMoves() {

        //A pusher is a big man and can move by itself to any legal destination.
        for (BoardSquare square : legalDestinations){
            legalMoves.add(new Move(getPosition(), square));
        }

    }

}
