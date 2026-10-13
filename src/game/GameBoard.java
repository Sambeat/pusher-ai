package game;

import client.GameStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

/**
 * GameBoard is a 1D implementation of a 2D LENGTH*LENGTH board. It is a
 * Singleton for ease of access and supports operation to move Pieces around on
 * its board.
 * @author Samuel Charade
 * @since 10/29/2015.
 */
public class GameBoard {

    private static GameBoard instance = new GameBoard();

    public static final int GAME_BOARD_LENGTH = 8;

    /**
     * Row 0 (top of the board): BLACK's goal row. Any BLACK piece (Pusher or
     * Pushee) on this row wins. See docs/RULES.md (item 1).
     */
    public static final int BLACK_GOAL_ROW = 0;

    /**
     * Bottom row: WHITE's goal row. Any WHITE piece (Pusher or Pushee) on this
     * row wins. See docs/RULES.md (item 1).
     */
    public static final int WHITE_GOAL_ROW = GAME_BOARD_LENGTH - 1;

    private static final String LARGE_SPACER = "||";
    private static final String SMALL_SPACER = "|";
    private static final String HORIZONTAL_SPACER =
            " |||-||-||-||-||-||-||-||-|||";
    private static final String COLUMN_LETTERING =
            "    A  B  C  D  E  F  G  H   ";
    private static final String EMPTY_SQUARE = " ";

    private BoardSquare[] board;
    private ArrayList<Piece> pieceList;

    private HashMap<Move, Piece> killedPieceMap;
    private Player turnPlayer;
    private int turnNumber;

    /**
     * Singleton pattern implementation.
     * @return The GameBoard singleton instance.
     */
    public static GameBoard getInstance() {return instance;}

    GameBoard(){
        initBoard();
        killedPieceMap = new HashMap<>(40);
        pieceList = new ArrayList<>();
        turnPlayer = Player.WHITE;
        turnNumber = 0;
    }

    private void initBoard(){
        this.board = new BoardSquare[GAME_BOARD_LENGTH * GAME_BOARD_LENGTH];
        Columns[] columns = Columns.values();

        for (int i = 0; i < GAME_BOARD_LENGTH; i++){

            for (int j = 0; j < GAME_BOARD_LENGTH; j++){

                board[to1D(i, j)] = new BoardSquare(null, i, columns[j]);
            }

        }

    }

    /**
     * Create a GameStep object encapsulating the current state of the GameBoard
     * object. This GameStep object can be used at any given time with the
     * revertToState() method to restore the GameBoard to this state.
     * @return A GameStep object encapsulating the current State.
     */
    public GameStep createMemento(){
        return new GameStep(board, turnPlayer, killedPieceMap, turnNumber);
    }

    /**
     * Revert the board to a previous state using a snapshot from GameStep object.
     * The pieces are reverted to the location specified in the GameStep and
     * the player which turn it was to play is also restored.
     * Use this GameBoard createMemento() method to create a GameStep object to
     * save the GameBoard state at any given time.
     * @param state A GameStep object of the state to revert to.
     */
    public void revertToState(GameStep state){
        turnPlayer = state.getTurnPlayer();
        killedPieceMap = state.getHashMap();
        //The turn number is part of the state too: a search aborted by the time
        //limit applies some moves without undoing them, which used to leak into
        //the permanent turn count.
        turnNumber = state.getTurnNumber();
        pieceList = new ArrayList<>();
        Piece[] piecesToPlace = state.getBoardState();

        for (int i = 0; i < board.length; i++){
            Piece thisSquarePiece = piecesToPlace[i];
            board[i].setPiece(thisSquarePiece);

            if (thisSquarePiece != null)
                pieceList.add(thisSquarePiece);

        }

    }


    public int getTurnNumber(){
        return turnNumber;
    }

    /**
     * Return if a specified Piece is still in play
     * @param piece The Piece to look for.
     * @return True if the Piece exists on the board, false otherwise.
     */
    public boolean pieceIsAlive(Piece piece){
        return pieceList.contains(piece);
    }

    /**
     * Return the number of Piece currently on the board.
     * @return The number of Piece currently on the board.
     */
    public int getNumberOfPieces(){
        return pieceList.size();
    }

    public ArrayList<Piece> getPieceList(){
        return pieceList;
    }

    /**
     * Forces an update for all Pieces currently on the GameBoard.
     */
    public void updateAllLegaLMoves(){

        for (Piece piece : pieceList){
            piece.updateLegalMoves();
        }
    }

    /**
     * Return all precomputed legal moves.
     */
    public ArrayList<Move> getAllLegalMovesQuick(){
        ArrayList<Move> legalMovesList = new ArrayList<>(30);

        for (Piece piece : pieceList){

            if (piece.getOwner() == turnPlayer){
                legalMovesList.addAll(piece.getLegalMovesQuick());
            }

        }

        return legalMovesList;
    }

    /**
     * Put a piece on the board at the specified row and column.
     * @param toPut The piece to put.
     * @param i The row in which to put the piece.
     * @param j The column in which to put the piece.
     */
    public void putPiece(Piece toPut, int i, int j){
        int index = to1D(i, j);
        Piece pieceToKill = board[index].getPiece();

        kill(pieceToKill);

        board[to1D(i, j)].setPiece(toPut);
        pieceList.add(toPut);
    }

    /**
     * Remove the Piece to kill from the current GameBoard.
     * @param toKill The piece to remove from play.
     */
    private void kill(Piece toKill){
        if (toKill != null) {
            pieceList.remove(toKill);
        }
    }

    /**
     * Put a piece on the board on the specified BoardSquare.
     * @param toPut The piece to put
     * @param location The BoardSquare on which to put the Piece.
     */
    public void putPiece(Piece toPut, BoardSquare location){
       putPiece(toPut, location.getRow(), location.getColumn());
    }

    /**
     * Utility method used to convert (row, column) coordinate into the
     * appropriate 1D coordinate for the board[].
     * @param i The "row" coordinate.
     * @param j The "column" coordinate.
     * @return The index to use to access the BoardSquare in the board[] at the
     * corresponding (row, column) coordinates.
     */
    private int to1D(int i, int j){
        return (i * GAME_BOARD_LENGTH) + (j % GAME_BOARD_LENGTH);
    }

    /**
     * Return the BoardSquare at the specified (row, column) coordinates.
     * Rows go from 0 to GAME_BOARD_LENGTH.
     * Columns go from 0 to GAME_BOARD_LENGTH.
     * @param i The row coordinate.
     * @param j The column coordinate.
     * @return The BoardSquare at located at (i, j) on the current GameBoard.
     */
    public BoardSquare getSquare(int i, int j){

        if ((i >= 0 && j >= 0) && (i < GAME_BOARD_LENGTH && j < GAME_BOARD_LENGTH))
            return board[to1D(i, j)];
        else
            return null;
    }

    public Piece getPiece(int i, int j){
        BoardSquare pieceSquare = getSquare(i, j);

        if (pieceSquare == null){
            return null;
        } else {
            return pieceSquare.getPiece();
        }
    }

    /**
     * Return the Player whose turn it is to play.
     * @return The Player whose turn it is to play.
     */
    public Player getCurrentPlayer(){
        return turnPlayer;
    }

    public void setCurrentPlayer(Player player){
        this.turnPlayer = player;
    }

    /**
     * Check if the board is empty at the specified row-column intersection.
     * @param i The row to check.
     * @param j The column to check.
     * @return True if empty, false otherwise.
     */
    public boolean isEmptyAt(int i, int j){
        return getSquare(i, j).isEmpty();
    }

    /**
     * Remove every Piece from the GameBoard. Also resets the turn number, the
     * current player and the killed piece map so a fresh game can start.
     */
    public void reset(){

        for (BoardSquare bs : board){
            bs.emptySquare();
        }

        turnNumber = 0;
        turnPlayer = Player.WHITE;
        killedPieceMap = new HashMap<>(40);
        pieceList = new ArrayList<>();
    }

    @Override
    public String toString(){
        String stringBuilder = "";

        stringBuilder = stringBuilder.concat(rowsToString());

        stringBuilder = stringBuilder.concat(bottomBoardToString());

        return stringBuilder;
    }

    private String rowsToString(){
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < GAME_BOARD_LENGTH; i++){

            stringBuilder.append(rowEdgeToString(i + 1));

            stringBuilder.append(rowBodyToString(i));

            stringBuilder.append(rowEdgeToString());
            stringBuilder.append('\n');

            stringBuilder.append(rowSpacerToString());

        }

        return stringBuilder.toString();
    }

    private String rowEdgeToString(int rowNumbering){
        return rowNumbering + LARGE_SPACER;
    }

    private String rowEdgeToString(){
        return LARGE_SPACER;
    }

    private String rowSpacerToString(){
        return HORIZONTAL_SPACER + '\n';
    }

    private String bottomBoardToString(){
        return COLUMN_LETTERING + '\n';
    }

    /**
     * Move a Piece from the specified row-column intersection origin to the
     * specified row-column intersection destination.
     * @param originRow The row from which to move a Piece.
     * @param originColumn The column from which to move a Piece.
     * @param destRow The row to which to move a Piece.
     * @param destColumn The column to which to move a Piece.
     */
    public void movePiece(int originRow, int originColumn, int destRow, int destColumn){
        movePiece(getSquare(originRow, originColumn), getSquare(destRow, destColumn));
    }

    /**
     * Move a Piece from a BoardSquare to another BoardSquare. Doesn't perform
     * any validity check. Increments the turn number.
     * @param origin The BoardSquare to move the Piece from.
     * @param destination The BoardSquare to move the Piece to.
     */
    public void movePiece(BoardSquare origin, BoardSquare destination){
        movePiece(new Move(origin, destination));
    }

    /**
     * Used by undo. Doesn't update surrounding pieces. Doesn't increase turn
     * number.
     * @param origin
     * @param destination
     */
    private void movePieceLite(BoardSquare origin, BoardSquare destination){
        destination.setPiece(origin.getPiece());
        origin.emptySquare();

        turnPlayer = turnPlayer.other();
    }

    /**
     * Update the legal moves of all Pieces surrounding the specified move.
     * In other words, looks at the origin and destination of the move to update
     * pieces. Should always be called right after moving a Piece.
     * @param move
     */
    public void updateLegalMovesOfSurroundingPieces(Move move){
        updateSurroundingPieces(move.getPieceLocation(), move.getDestination());
    }

    private void updateSurroundingPieces(BoardSquare origin, BoardSquare destination){
        Piece[] piecesToUpdateLegalMoves = {
                getPiece(origin.getRow() - turnPlayer.direction(), origin.getColumn() - 1),
                getPiece(origin.getRow() - turnPlayer.direction(), origin.getColumn()),
                getPiece(origin.getRow() - turnPlayer.direction(), origin.getColumn()+ 1),
                getPiece(origin.getRow() + turnPlayer.direction(), origin.getColumn()),
                getPiece(origin.getRow() + turnPlayer.direction(), origin.getColumn() - 1),
                getPiece(origin.getRow() + turnPlayer.direction(), origin.getColumn() + 1),

                getPiece(destination.getRow() - turnPlayer.direction(), destination.getColumn() - 1),
                getPiece(destination.getRow() - turnPlayer.direction(), destination.getColumn()),
                getPiece(destination.getRow() - turnPlayer.direction(), destination.getColumn() + 1),
                getPiece(destination.getRow() + turnPlayer.direction(), destination.getColumn()),
                getPiece(destination.getRow() + turnPlayer.direction(), destination.getColumn() - 1),
                getPiece(destination.getRow() + turnPlayer.direction(), destination.getColumn() + 1)
        };

        for (Piece pieceToUpdate : piecesToUpdateLegalMoves){

            if (pieceToUpdate != null)
                pieceToUpdate.updateLegalMoves();

        }
    }

    /**
     * Move a Piece using the specified Move. Doesn't perform any validity check.
     * ALWAYS CALL updateLegalMovesOfSurroundPieces() AFTER CALLING THIS.
     * @param move Move indicating the move to play.
     */
    public void movePiece(Move move){
        BoardSquare origin = move.getPieceLocation();
        BoardSquare destination = move.getDestination();

        Piece movingPiece = origin.getPiece();

        if (movingPiece == null) {
            //Failing fast here prevents the silent corruption this used to cause:
            //a move from an empty square "captured" the piece on the destination
            //square and placed a null piece on the board.
            throw new IllegalStateException("movePiece: no piece at origin " + origin);
        }

        Piece toKill = destination.getPiece();

        if (toKill != null) {
            kill(toKill);
            killedPieceMap.put(move, toKill);
        }

        destination.setPiece(origin.getPiece());
        origin.emptySquare();

        updateSurroundingPieces(origin, destination);

        turnPlayer = turnPlayer.other();
        turnNumber++;
    }

    /**
     * Do the reverse of the specified move. If the specified move killed a Piece,
     * restore it to the location it was when it was killed. Does not verify
     * if the specified move was the last move done.
     * @param move The move to reverse
     */
    public void undo(Move move){
        movePieceLite(move.getDestination(), move.getPieceLocation());

        //Remove 2 turns because the previous movePiece() call incremented the
        //turn number once more...
        turnNumber -= 1;

        Piece pieceKilled = killedPieceMap.get(move);
        if (pieceKilled != null) {
            putPiece(pieceKilled, move.getDestination());
            killedPieceMap.remove(move, pieceKilled);
        }

        updateLegalMovesOfSurroundingPieces(move);

    }

    /**
     * Verify if any player has won and thus if the game should end.
     * BLACK's goal row is scanned in full before WHITE's: if both sides have a
     * piece on their goal row at the same time, BLACK wins the tie.
     * @return The Player who won the game or null if the game is not done.
     */
    public Player winner(){

        for (int i = 0; i < GAME_BOARD_LENGTH; i++){
            Piece toCheck = board[to1D(BLACK_GOAL_ROW, i)].getPiece();
            if (toCheck != null && toCheck.getOwner() == Player.BLACK)
                return Player.BLACK;
        }

        for (int i = 0; i < GAME_BOARD_LENGTH; i++){
            Piece toCheck = board[to1D(WHITE_GOAL_ROW, i)].getPiece();
            if (toCheck != null && toCheck.getOwner() == Player.WHITE)
                return Player.WHITE;
        }

        return null;
    }

    private String rowBodyToString(int row){
        StringBuilder stringBuilder = new StringBuilder();

        for (int j = 0; j < GAME_BOARD_LENGTH; j++){
            Piece toPrint = board[to1D(row, j)].getPiece();

            stringBuilder.append(SMALL_SPACER);

            if (toPrint != null)
                stringBuilder.append(toPrint);
            else
                stringBuilder.append(EMPTY_SQUARE);

            stringBuilder.append(SMALL_SPACER);

        }

        return stringBuilder.toString();
    }



}
