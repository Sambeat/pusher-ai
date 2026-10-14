package game;

/**
 * Represent a move with the origin and the destination.
 * @author Samuel Charade .
 * @since 11/2/2015.
 */
public class Move {

    BoardSquare pieceLocation;
    BoardSquare destination;

    public Move(BoardSquare pieceLocation, BoardSquare destination){
        this.pieceLocation = pieceLocation;
        this.destination = destination;
    }

    public BoardSquare getPieceLocation(){
        return pieceLocation;
    }

    public BoardSquare getDestination(){
        return destination;
    }

    @Override
    public boolean equals(Object toCompare) {

        if (toCompare instanceof Move) {
            Move moveToCompare = (Move) toCompare;

            return (this.pieceLocation.equals(moveToCompare.getPieceLocation())
                    && this.destination.equals(moveToCompare.getDestination()));
        }
        else{
            return false;
        }

    }

    @Override
    public int hashCode(){
        //equals() is overridden, so hashCode() must be too: the search's capture
        //log (formerly a HashMap keyed by Move) and any Set/Map of moves only
        //worked by luck of instance identity before this.
        int result = pieceLocation.hashCode();
        result = 31 * result + destination.hashCode();
        return result;
    }

    @Override
    public String toString(){
        return pieceLocation.toString() + destination.toString();
    }

}
