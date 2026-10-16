package client;

import game.*;

import java.util.LinkedList;

/**
 * Represent a snapshot of the GameBoard
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public class GameStep{

    Piece[] boardPieces;
    LinkedList<Piece> killLog;
    Player turnPlayer;
    int turnNumber;

    /**
     * T6: the Zobrist key of the position at snapshot time. revertToState()
     * replaces the whole board at once, so the incremental movePiece/undo
     * updates do not apply there: the key must be restored from the
     * snapshot, like the turn number.
     */
    long zobristHash;

    public GameStep(BoardSquare[] boardSquares, Player turnPlayer, LinkedList<Piece> killLog, int turnNumber, long zobristHash) {
        this.turnPlayer = turnPlayer;
        this.killLog = new LinkedList<>(killLog);
        this.turnNumber = turnNumber;
        this.zobristHash = zobristHash;
        boardPieces = new Piece[GameBoard.GAME_BOARD_LENGTH * GameBoard.GAME_BOARD_LENGTH];

        for (int i = 0; i < boardPieces.length; i++){
            Piece pieceToClone = boardSquares[i].getPiece();

            if (pieceToClone != null) {
                // Piece.clone() swallows CloneNotSupportedException itself
                // (returns null); nothing else can escape, so no try/catch.
                boardPieces[i] = pieceToClone.clone();
            }

        }

    }

    public Piece[] getBoardState(){
        return boardPieces;
    }

    public Player getTurnPlayer(){
        return turnPlayer;
    }

    public int getTurnNumber(){ return turnNumber; }

    public long getZobristHash(){ return zobristHash; }

    public LinkedList<Piece> getKillLog() { return killLog; }

}
