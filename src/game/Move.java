package game;

/**
 * Represent a move with the origin and the destination.
 *
 * <p>C3: a record — two effectively-immutable fields, nothing else. The
 * generated value-based equals/hashCode replace the hand-written ones
 * (BoardSquare.equals is value-based on row+column), and the fields are
 * now final (no mutators ever existed). No container keys on Move's hash
 * (the capture log is a LinkedList, all lookups are List.contains on
 * equals), so only the equals contract — not the exact hash values — is
 * load-bearing.</p>
 *
 * @author Samuel Charade .
 * @since 11/2/2015.
 */
public record Move(BoardSquare pieceLocation, BoardSquare destination) {

    @Override
    public String toString(){
        // The 4-char wire format ("D2E3") — MockPusherServer and
        // PusherApplicationTest depend on it, so the record's default
        // toString must not leak through.
        return pieceLocation.toString() + destination.toString();
    }

}
