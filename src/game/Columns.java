package game;

/**
 * Columns of a GameBoard and their possible representation.
 * @author Samuel Charade
 * @since 10/31/2015.
 */
public enum Columns {
    A (0, 1, 'A'),
    B (1, 2, 'B'),
    C (2, 3, 'C'),
    D (3, 4, 'D'),
    E (4, 5, 'E'),
    F (5, 6, 'F'),
    G (6, 7, 'G'),
    H (7, 8, 'H');

    private final int pos;
    private final int numeral;
    private final char text;

    Columns(int pos, int numeral, char text) {
        this.pos = pos;
        this.numeral = numeral;
        this.text = text;
    }

    public int value(){
        return numeral;
    }

    public char text(){
        return text;
    }

    public int pos(){
        return pos;
    }

    public String toString(){
        return "" + text;
    }
}
