# Pusher — Rules Contract (as implemented)

Every rules assumption the engine makes, in one place. For each item this doc
states **what the code does today** (with the class/method that implements it)
and **whether it is confirmed against the server**.

**Status: items 1–5 are NOT confirmed against the server.** Nothing in this
repository records a server owner's answer; the protocol in particular is
reverse-engineered from what the client itself sends and accepts. If the answers
to the §Open questions change a rule, implement it as a follow-up task (do not
guess) and update this doc together with `tests.RulesContractTest`, which pins
items 1–4 as characterization tests.

Coordinates used throughout: rows 0–7, row 0 = top = BLACK's goal row,
row 7 = bottom = WHITE's goal row; columns 0–7 = files A–H. WHITE moves toward
increasing rows, BLACK toward decreasing rows.

## 1. Win condition

**Code today** — `game.GameBoard.winner()`:

- Any own piece — a Pusher *or* a Pushee — on the enemy goal row wins
  immediately: BLACK on row 0 (`GameBoard.BLACK_GOAL_ROW`), WHITE on row 7
  (`GameBoard.WHITE_GOAL_ROW`).
- A piece on its own goal row does not win.
- If both sides have a piece on their goal row at the same time, **BLACK
  always wins the tie**, regardless of the columns: `winner()` scans BLACK's
  goal row in full before WHITE's. (Deliberate project decision — the previous
  column-major scan let a WHITE piece in a lower-numbered column win the tie;
  that was never intended. Whether the server's real rules match is still an
  open question below.) There is no "double win" concept.
- The win is detected at every search node in `client.GameTree.alphaBeta`
  (terminal scores `±(MAX/MIN ∓ turnNumber)`); the board model alone only
  provides `winner()`.

**Confirmed against the server?** NO. Many versions of Pusher score only a
Pushee reaching the goal row (the Pusher being a "driver"); counting a Pusher
landing on the goal row as a full win, as the engine does, is an assumption.

## 2. Captures

**Code today** — `game.Piece.computeLegalDestinations()`:

- A piece may move 1 square forward: straight or to either diagonal
  (row ± 1 toward its goal, column −1/0/+1).
- A diagonal destination may be empty or occupied by an enemy piece → capture.
- A straight destination must be empty → straight-ahead capture is illegal.
- A square occupied by a friendly piece is never a destination.
- There is no "blocking" semantics: a piece never moves another piece aside.

**Confirmed against the server?** NO. It is not even confirmed that the real
game has captures; if it does not, the engine's tactical model (capturing,
"hanging" pieces) is built on the wrong rule.

## 3. Stalemate (no legal moves)

**Code today** — implemented in the search only, not in the board model:

- `client.GameTree.alphaBeta`: the side to move with zero legal moves **loses**
  (checkmate-like). Terminal score `±(MAX/MIN ∓ turnNumber ∓ STALEMATE_MARGIN)`.
- Ranked strictly below a goal-row win/loss at the same depth via
  `GameTree.STALEMATE_MARGIN` (1000): the engine prefers the actual objective
  (goal row) over stalemating the opponent, and prefers stalemating the
  opponent over any losing line.
- App level: `client.PusherApplication.communicateMove` logs
  `No legal move to play.` and sends nothing when the engine returns null.
- `GameBoard` has no stalemate concept of its own (`winner()` only checks goal
  rows).

**Confirmed against the server?** NO. The real rules may treat a player with no
legal moves as a draw (or the game may simply continue); there is no known draw
command in the protocol.

## 4. Pushee movement (the mirror rule)

**Code today** — `game.Pushee.computeLegalMoves()`:

- A Pushee's candidate destinations are the same forward 3 squares as a
  Pusher's (the capture rules of item 2 apply).
- A candidate is a legal move only if a friendly **Pusher** occupies the mirror
  square behind the destination: `(row − direction, 2·col − destCol)`.
  - Straight push: the Pusher is directly behind.
  - Diagonal push to the right: the Pusher is diagonally behind-left; a
    diagonal push to the left needs it diagonally behind-right.
- An enemy Pusher on the mirror square does not count. With no friendly Pusher
  in a mirror position, a Pushee has no legal moves at all.

**Confirmed against the server?** NO.

## 5. Protocol (client ↔ server, port 8888)

**Code today** — `client.PusherApplication.java`:

- Connection: the client opens `localhost:8888` and reads command bytes in an
  endless loop; unknown command bytes are silently ignored.
- Commands (single byte):
  - `'1'` — new game, AI plays White; the board payload follows. The client
    immediately computes and sends its first move.
  - `'2'` — new game, AI plays Black; the board payload follows. The client
    builds the board and waits (no move is sent; Black responds to `'3'`).
  - `'3'` — the opponent's move; the move payload follows. Before applying the
    opponent's move, the client applies its own previously sent move (if any)
    to the local board, then computes and sends its next move.
  - `'4'` — the client answers with a random legal move (no payload).
- Board payload (after `'1'`/`'2'`): whitespace-separated integers, read as
  "whatever bytes are currently available" (`input.available()` — no line or
  length framing; see T9). Tokens are placed starting at H8 (row 7, col 7),
  right-to-left, bottom-to-top: the 1st token is H8, the 8th is A8, the 9th is
  H7, …, the 64th is A1. Values: `1` = Black Pushee, `2` = Black Pusher,
  `3` = White Pushee, `4` = White Pusher, `0` = empty (ignored). There is no
  validation: fewer than 64 tokens leaves the rest of the board empty, and an
  unparseable token aborts the board build (caught; the loop continues with a
  partial board).
- Move payload (after `'3'`): the client's own format is 4 characters
  `colLetter rowDigit colLetter rowDigit` (e.g. `D2E3` = from D2 to E3; columns
  A–H, row digits 1–8 with 1 = top row 0 and 8 = bottom row 7). The parser also
  accepts a legacy layout of ≥ 7 characters with the origin at indices 0–1 and
  the destination at indices 5–6 (same letter/digit pairs).
- Client responses: the 4-character move string, written raw (no newline, no
  length prefix) and flushed; nothing is sent when the client has no legal move.

**Confirmed against the server?** NO. The board orientation (which corner is
the first of the 64 integers), the move-string layout, and the framing
(newline-terminated? length-prefixed? raw chunks?) are all assumptions; the
client currently accepts its own 4-char format and the legacy 7-char layout,
and it is not verified which one the server actually sends.

## Open questions (for the server owner)

1. **Win condition:** does a Pusher landing on the goal row count as a win, or
   only a Pushee? And if both sides reach their goal row in the same position,
   is a BLACK win the correct outcome? (Item 1.)
2. **Captures:** are captures allowed at all? If yes, is diagonal-only capture
   (no straight-ahead capture) correct? (Item 2.)
3. **Stalemate:** a player with no legal move — loss, draw, or game continues?
   (Item 3.)
4. **Pushee rule:** is the mirror-square Pusher requirement correct as
   implemented, including the diagonal case? (Item 4.)
5. **Board text:** which corner is the first of the 64 board integers, and are
   exactly 64 always sent? (Item 5.)
6. **Move text / framing:** which move-string layout does the server send and
   accept (4-char `D2E3` vs. the 7-char layout)? Are messages
   newline-terminated, length-prefixed, or raw chunks? (Item 5.)
