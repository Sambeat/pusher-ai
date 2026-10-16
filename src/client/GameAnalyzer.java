package client;

import game.BoardSquare;
import game.Columns;
import game.GameBoard;
import game.Move;
import game.Piece;
import game.Player;
import game.Pushee;
import game.Pusher;

import java.util.HashSet;
import java.util.Set;

/**
 * Role is to evaluate the state of the board and return a corresponding score
 * for the minmax algorithm.
 * @author Samuel Charade
 * @since 11/06/2015.
 */
public class GameAnalyzer {

    /**
     * T4: a Pushee that can legally push onto the goal row on the next move is
     * a win one ply away (unless opposed); 400 outranks any material or
     * advancement difference but stays far below the +/-9000 forced-win cutoff
     * that getBestMoveQuick() stops iterative deepening on.
     */
    public static final int READY_TO_WIN = 400;

    /**
     * T4: controlling the mirror square behind an enemy ready-to-win Pushee
     * (standing on it with a Pusher, or being able to capture the enemy
     * Pusher that stands there) kills one of its winning pushes; 20 is a soft
     * term, below a full Pusher's value (7) plus realistic advancement.
     */
    public static final int PUSHEE_SHIELD = 20;

    /**
     * T8: the defensive premium charged for EVERY enemy piece, identical for
     * both colors. White always had it (a flat +2 per enemy piece); Black
     * is unified to match, so the two strategies are exact mirrors of each
     * other (see analyzeFor and EvalSymmetryTest).
     */
    public static final int DEFENSIVE_PREMIUM = 2;

    public static int analyze(GameBoard board, Strategy strategy){
        return strategy.analyze();
    }

    /**
     * T8: the single shared static evaluation, parameterized by the player
     * the score belongs to. WhiteStrategy and BlackStrategy both delegate
     * here, so the two strategies are exact mirrors: for every position p,
     * evaluate(p, WHITE) on p equals evaluate(mirror(p), BLACK) on the
     * mirrored position (mirror = swap colors, row r -> 7 - r, columns
     * unchanged). Both scores are from the given player's own perspective -
     * the convention GameTree's alpha-beta search requires - so a perfectly
     * mirrored position scores equally for the two colors, not oppositely.
     *
     * Terms (all integers, kept far below the +/-9000 forced-win cutoff):
     *   + (value + advancement)   for each own piece; advancement is the row
     *                             for WHITE and GAME_BOARD_LENGTH - 1 - row
     *                             for BLACK. A Pushee on an edge file (A or
     *                             H) is worth nothing.
     *   - (value + advancement + DEFENSIVE_PREMIUM) for each enemy piece,
     *                             advancement in the ENEMY's own direction.
     *   + own mobility / 2        for the player the score belongs to (NOT
     *                             the side to move - getAllLegalMovesQuick()
     *                             returns the side to move's moves, which at
     *                             half of all leaves is the opponent).
     *   + the T4 threat & safety terms (see threatTerms), which are
     *                             mirror-invariant by construction.
     *
     * Uses the pieces' cached legal-move lists, which movePiece()/undo() keep
     * consistent during search (see the engine invariants); outside of search,
     * call board.updateAllLegaLMoves() first. The score does not depend on
     * the side to move.
     */
    public static int analyzeFor(GameBoard board, Player player){
        return analyzeFor(board, player, true);
    }

    /**
     * T15: analyzeFor with the T4 threat &amp; safety terms included or not.
     * With them off the score is exactly the pre-T4 evaluation —
     * (value + advancement) for each own piece, -(value + advancement +
     * DEFENSIVE_PREMIUM) for each enemy piece, own mobility / 2 — which is
     * what the t2/t3 engine levels (T15) evaluate. The 2-argument overload
     * (the production path) always includes the terms.
     */
    public static int analyzeFor(GameBoard board, Player player,
                                 boolean threatTermsEnabled){
        Player enemy = player.other();
        int score = 0;

        for (Piece piece : board.getPieceList()){
            int value = piece.getScoreValue();

            if (piece.getOwner() == player){
                if (value == Pushee.VALUE && (piece.getColumn() == Columns.A.pos()
                        || piece.getColumn() == Columns.H.pos())){

                    // A Pushee on an edge file is worthless: it contributes
                    // nothing. (The original code was missing the
                    // parentheses, which made ANY own piece on file H,
                    // including Pushers, worth 0 - pinned by BugProofTest.)

                } else {
                    score += value + advancement(piece, player);
                }

            } else {
                score -= (value + advancement(piece, enemy) + DEFENSIVE_PREMIUM);
            }

        }

        int ownMobility = 0;
        for (Piece piece : board.getPieceList()){
            if (piece.getOwner() == player)
                ownMobility += piece.getLegalMovesQuick().size();
        }
        score += ownMobility / 2;

        if (threatTermsEnabled)
            score += threatTerms(board, player);

        return score;
    }

    /**
     * How far a piece has advanced toward its OWN goal row: its row for
     * WHITE (advances to row 7), GAME_BOARD_LENGTH - 1 - row for BLACK
     * (advances to row 0). The parameter is the piece's owner.
     */
    private static int advancement(Piece piece, Player owner){
        return (owner == Player.WHITE)
                ? piece.getRow()
                : GameBoard.GAME_BOARD_LENGTH - 1 - piece.getRow();
    }

    /**
     * The T4 threat &amp; safety terms, shared by WhiteStrategy and
     * BlackStrategy (parameterized by the player the score belongs to) so
     * both colors score these terms identically, mirrored — T8's symmetry
     * test relies on that. The terms:
     *   + READY_TO_WIN  for each own Pushee that can legally push onto the
     *                   goal row on the next move;
     *   + value/2       for each enemy piece own pieces can capture on the
     *                   next move;
     *   - value/2       for each own piece the enemy can capture on the next
     *                   move (each piece counted once, no matter how many
     *                   enemy pieces reach it);
     *   + PUSHEE_SHIELD for each enemy push square behind a ready-to-win
     *                   enemy Pushee that a friendly Pusher occupies or can
     *                   capture.
     *
     * All terms are integers. Uses the pieces' cached legal-move lists, which
     * movePiece()/undo() keep consistent during search (see the engine
     * invariants); outside of search, call board.updateAllLegaLMoves() first.
     */
    public static int threatTerms(GameBoard board, Player player){
        return readyToWinScore(board, player)
                + captureThreatScore(board, player)
                + pusheeShieldScore(board, player);
    }

    /**
     * T4: READY_TO_WIN for each of player's Pushees on the row adjacent to
     * its goal row that has a legal move onto the goal row. A Pushee's legal
     * move generation already enforces the empty-destination and
     * mirror-square-Pusher rules, so "a legal destination on the goal row"
     * is exactly "ready to win".
     */
    public static int readyToWinScore(GameBoard board, Player player){
        int goalRow = goalRowOf(player);
        int total = 0;

        for (Piece piece : board.getPieceList()){
            if (piece instanceof Pushee pushee
                    && pushee.getOwner() == player
                    && pushee.getRow() == goalRow - player.direction()
                    && canWinNextMove(pushee, goalRow))
                total += READY_TO_WIN;
        }

        return total;
    }

    /**
     * T4: net next-ply capture threat for player: the halved value of enemy
     * pieces player can capture on its next move, minus the halved value of
     * player's pieces the enemy can capture on its next move. A straight
     * destination must be empty, so any legal move onto an occupied square is
     * a diagonal capture.
     */
    public static int captureThreatScore(GameBoard board, Player player){
        return capturableValue(board, player, player.other())
                - capturableValue(board, player.other(), player);
    }

    /**
     * T4: PUSHEE_SHIELD for each push square behind a ready-to-win enemy
     * Pushee that player controls: a friendly Pusher standing on the mirror
     * square (occupying the push square) or an enemy Pusher on it that
     * player can capture on its next move.
     */
    public static int pusheeShieldScore(GameBoard board, Player player){
        Player enemy = player.other();
        int total = 0;

        for (Piece piece : board.getPieceList()){
            if (!(piece instanceof Pushee pushee) || pushee.getOwner() != enemy)
                continue;
            if (!canWinNextMove(pushee, goalRowOf(enemy)))
                continue;

            int row = pushee.getRow();
            int col = pushee.getColumn();
            int dir = pushee.getDirection();

            for (int dc = -1; dc <= 1; dc++){
                int destCol = col + dc;
                if (destCol < 0 || destCol >= GameBoard.GAME_BOARD_LENGTH)
                    continue;

                // The mirror square behind this goal-adjacent destination
                // (same rule Pushee.computeLegalMoves uses).
                int mirrorRow = row - dir;
                int mirrorCol = col + (col - destCol);
                if (mirrorRow < 0 || mirrorRow >= GameBoard.GAME_BOARD_LENGTH
                        || mirrorCol < 0 || mirrorCol >= GameBoard.GAME_BOARD_LENGTH)
                    continue;

                Piece occupant = board.getSquare(mirrorRow, mirrorCol).getPiece();

                if (occupant instanceof Pusher pusher && pusher.getOwner() == player)
                    total += PUSHEE_SHIELD;      // friendly Pusher occupies the push square
                else if (occupant instanceof Pusher pusher && pusher.getOwner() == enemy
                        && hasMoveTo(board, player,
                                     board.getSquare(mirrorRow, mirrorCol)))
                    total += PUSHEE_SHIELD;      // the enemy pusher can be captured
            }
        }

        return total;
    }

    /** Half-value of each of victim's pieces that attacker can capture next move. */
    private static int capturableValue(GameBoard board, Player attacker, Player victim){
        Set<BoardSquare> attackedSquares = new HashSet<>();

        for (Piece piece : board.getPieceList()){
            if (piece.getOwner() != attacker)
                continue;
            for (Move move : piece.getLegalMovesQuick()){
                if (move.destination().getPiece() != null)
                    attackedSquares.add(move.destination());
            }
        }

        int total = 0;
        for (Piece piece : board.getPieceList()){
            if (piece.getOwner() == victim
                    && attackedSquares.contains(piece.getPosition()))
                total += piece.getScoreValue() / 2;
        }

        return total;
    }

    /** The goal row the given player scores on (row 0 for BLACK, row 7 for WHITE). */
    private static int goalRowOf(Player player){
        return (player == Player.WHITE)
                ? GameBoard.WHITE_GOAL_ROW : GameBoard.BLACK_GOAL_ROW;
    }

    /** Whether the piece has a legal move onto the given goal row. */
    private static boolean canWinNextMove(Piece piece, int goalRow){
        for (Move move : piece.getLegalMovesQuick()){
            if (move.destination().getRow() == goalRow)
                return true;
        }
        return false;
    }

    /** Whether any of player's pieces has a legal move onto the given square. */
    private static boolean hasMoveTo(GameBoard board, Player player,
                                     BoardSquare square){
        for (Piece piece : board.getPieceList()){
            if (piece.getOwner() != player)
                continue;
            for (Move move : piece.getLegalMovesQuick()){
                if (move.destination().equals(square))
                    return true;
            }
        }
        return false;
    }

}
