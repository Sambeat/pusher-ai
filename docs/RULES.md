# Pusher — Rules Contract (as implemented)

Every rules assumption the engine makes, in one place. For each item this doc
states **what the code does today** (with the class/method that implements it)
and **whether it is confirmed against the server**.

**Status: fully confirmed against the server owner (T11 + follow-ups, T12).**
Items 1–4 are confirmed exactly as implemented. Items 5–6 are confirmed as
the ORIGINAL wire behavior, which the initial client used successfully
against the real server: board tokens start at H8 (bottom-right), the move
format is the initial 4-char one, and there is no message framing (the
payload is read in the original available()-based read). T11 briefly
deviated (top-left-first placement, 7-char send) and T12 restored the
original wire behavior per the server owner's follow-up. Item 7 (endless
play) is resolved: under the confirmed rules, repetitions are impossible
and every game ends. If a future answer changes a rule, implement it as a
follow-up task (do not guess) and update this doc together with
`tests.RulesContractTest`, which pins items 1–4 as characterization tests.

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
  that was never intended. The server owner confirmed in T11 that this
  position is unreachable in a real game — players alternate, and the first
  piece to land on the enemy goal row ends the game immediately — so the
  rule is only a deterministic fallback for by-hand positions.) There is no
  "double win" concept.
- The win is detected at every search node in `client.GameTree.alphaBeta`
  (terminal scores `±(MAX/MIN ∓ turnNumber)`); the board model alone only
  provides `winner()`.

**Confirmed against the server?** YES (server owner, T11, plus the
follow-up "both pieces can win"): any piece — Pusher or Pushee — reaching
the enemy goal row wins, and the BLACK-wins-the-tie rule stands (the tie
position is unreachable in play — see above; `RulesContractTest` still pins
it for the by-hand case).

## 2. Captures

**Code today** — `game.Piece.computeLegalDestinations()`:

- A piece may move 1 square forward: straight or to either diagonal
  (row ± 1 toward its goal, column −1/0/+1).
- A diagonal destination may be empty or occupied by an enemy piece → capture.
- A straight destination must be empty → straight-ahead capture is illegal.
- A square occupied by a friendly piece is never a destination.
- There is no "blocking" semantics: a piece never moves another piece aside.

**Confirmed against the server?** YES (server owner, T11): captures exist
and are diagonal-only — a straight-ahead capture is not legal.

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

**Confirmed against the server?** YES (server owner, T11): a player with no
legal move loses. (And see item 7: under the confirmed rules every game is
finite, so the protocol needs no draw command.)

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

**Confirmed against the server?** YES (server owner, T11): the
mirror-square Pusher requirement is correct, including the diagonal case —
"mirror everywhere". Verified against the code in T11: `Pushee
.computeLegalMoves()` checks exactly one square per candidate destination,
`(row − direction, 2·col − destCol)`, and demands a friendly Pusher on it
for all three destination types (straight + both diagonals). No alternative
setup enables Pushee movement anywhere in the code (the never-used
`Piece.hasAdjacentPusher()` is not wired into move generation), and
`Pusher.computeLegalMoves()` has no mirror requirement — Pushers move freely.

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
- Board payload (after `'1'`/`'2'`): whitespace-separated integers. **No
  framing** (confirmed by the server owner in T12: "if there were no
  framing in place before, I wouldn't add one"): the server sends the
  command byte followed by the payload, and the client reads the payload in
  a single `input.available()`-based read — the original 2015 scheme. (T9
  briefly replaced it with a newline-terminated read, an unconfirmed
  assumption; T12 reverted it — a server that sends no newlines would hang
  a newline-based reader, and the original read is what worked in
  production.) Tokens are placed starting at H8 (row 7, col 7),
  right-to-left, then bottom-to-top: the 1st token is H8, the 8th is A8,
  the 9th is H7, …, the 64th is A1 — **the original order, confirmed as the
  wire format in T12** ("the old original code probably had the orientation
  right too": the initial client parsed the real server correctly. The
  earlier "top-left to bottom-right" answer was a design preference, not a
  fact; T11 briefly inverted the placement and T12 reverted it). Values:
  `1` = Black Pushee, `2` = Black Pusher, `3` = White Pushee, `4` = White
  Pusher, `0` = empty.
- Move payload (after `'3'`): read the same way (one available()-based read,
  no framing; the parser itself is unchanged — `PusherApplication.parseMove`).
  The client's format is 4 characters
  `colLetter rowDigit colLetter rowDigit` (e.g. `D2E3` = from D2 to E3; columns
  A–H, row digits 1–8 with 1 = top row 0 and 8 = bottom row 7) — **confirmed
  as the right format by the server owner** (the initial format; T11 briefly
  switched the send to a 7-char layout and reverted it after the follow-up).
  The parser also accepts a legacy layout of ≥ 7 characters with the origin
  at indices 0–1 and the destination at indices 5–6 (same letter/digit
  pairs).
- Client responses: the 4-character move string, written raw (no newline, no
  length prefix) and flushed; nothing is sent when the client has no legal
  move.

**Confirmed against the server?** YES (T11 + follow-ups, T12).
**Board text:** YES — the 64 integers start at H8 (bottom-right) and run
right-to-left, bottom-to-top: the original order, which the initial client
used successfully against the real server (server owner: "the old original
code probably had the orientation right too").
**Move text:** YES — the client's initial 4-char format (`D2E3`) is the
right one ("the initial format was the right one"); T11's 7-char send was
reverted. The parser's legacy 7-char acceptance is unchanged.
**Framing:** confirmed to be **none** — no newline, no length prefix; the
payload is read with the original available()-based read (T9's newline
framing was reverted in T12: "if there were no framing in place before, I
wouldn't add one").

## Open questions (for the server owner)

None — T11 + the follow-ups (T11 review, T12) closed all seven original
questions; see the resolved list below.

## Resolved (server owner's answers, T11 + follow-ups)

1. **Win condition:** any piece (Pusher or Pushee) on the enemy goal row
   wins — explicitly confirmed in the follow-up ("both pieces can win").
   The BLACK-wins-the-tie rule stands, but a double goal-row position is
   impossible in play: players alternate, and the first piece to land on
   the enemy goal row ends the game immediately.
2. **Captures:** they exist, and are diagonal-only (no straight-ahead
   capture).
3. **Stalemate:** no legal move is a loss.
4. **Pushee rule:** the mirror-square Pusher requirement, for all three
   destination types (straight + both diagonals) — "mirror everywhere",
   as implemented. No other enabling setup exists in the code (T11
   verification, item 4 above).
5. **Board text:** 64 integers, from H8 (bottom-right) to A1 (top-left),
   right-to-left, bottom-to-top — the original order. The initial client
   parsed the real server correctly, so its orientation is the wire format
   (T12; the earlier "top-left to bottom-right" answer was a guess and was
   retracted).
6. **Move text:** the client's initial 4-char format (`D2E3`) is the right
   one ("the initial format was the right one"; T11 briefly sent a 7-char
   layout and reverted it). The parser keeps accepting the legacy 7-char
   layout as well. **Framing: none** — no newline, no length prefix; the
   payload is read with the original available()-based read (T12; T9's
   newline framing was reverted).
7. **Endless play — impossible under the confirmed rules.** The owner's
   intuition ("you can basically only move forward and when you reach the
   end of the board you win") is provably complete: every move advances
   exactly one piece exactly one row toward its goal row (the forward 3
   squares only — `Piece.computeLegalDestinations`), so let
   Φ = Σ over all pieces of (rows still to travel to its goal row). Each
   ply decreases Φ by 1, plus the remaining distance of any captured piece,
   so Φ strictly decreases from a finite start (≤ 70 with the standard
   5+5-piece setup) and the game ends when a piece reaches the enemy goal
   row (a win) or the side to move runs out of legal moves (a loss, item
   3). A repeated position would need the same Φ at two different plies —
   impossible. No draw command is needed. The T10 guards (the app's
   300-ply warning, the engine's root repetition penalty) are kept as
   zero-cost defensive safety nets against a misbehaving server, not as a
   rule requirement.
