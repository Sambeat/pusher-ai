package client;

import game.*;

import java.util.HashMap;

/**
 * Represent a snapshot of the GameBoard
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public class GameStep{

    Piece[] boardPieces;
    HashMap<Move, Piece> hashMap;
    Player turnPlayer;
    int turnNumber;

    public GameStep(BoardSquare[] boardSquares, Player turnPlayer, HashMap<Move, Piece> hashMap, int turnNumber) {
        this.turnPlayer = turnPlayer;
        this.hashMap = (HashMap<Move, Piece>) hashMap.clone();
        this.turnNumber = turnNumber;
        boardPieces = new Piece[GameBoard.GAME_BOARD_LENGTH * GameBoard.GAME_BOARD_LENGTH];

        for (int i = 0; i < boardPieces.length; i++){
            Piece pieceToClone = boardSquares[i].getPiece();

            if (pieceToClone != null) {
                try {
                    boardPieces[i] = (Piece) pieceToClone.clone();
                } catch (Exception e){
                    e.printStackTrace();
                }

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

    public HashMap<Move, Piece> getHashMap() { return hashMap; }

}
