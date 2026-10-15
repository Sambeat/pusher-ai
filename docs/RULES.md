# Pusher — Rules Contract (as implemented)

Every rules assumption the engine makes, in one place. For each item this doc
states **what the code does today** (with the class/method that implements it)
and **whether it is confirmed against the server**.

**Status: confirmed against the server owner (T11).** Items 1–4 are
confirmed exactly as implemented. Item 5 was confirmed with a correction:
the board text runs from the top-left corner — the client was placing the
1st token at the bottom-right, and T11 fixed the placement. Item 6 is
confirmed: the client's initial 4-char move format is the right one (T11
briefly switched it to a 7-char layout and reverted after the server owner's
follow-up); the message framing remains an assumption (see the open
questions below). Item 7 (endless play) is resolved:
under the confirmed rules, repetitions are impossible and every game ends.
If a future answer changes a rule, implement it as a follow-up task (do not
guess) and update this doc together with `tests.RulesContractTest`, which
pins items 1–4 as characterization tests.

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
- Board payload (after `'1'`/`'2'`): whitespace-separated integers, read to
  the end of the message's line: **the client assumes newline-terminated
  messages** (T9; the framing was never confirmed with the server owner, see
  open question 6, and T9's plan fallback is newline-terminated). The payload
  is read byte by byte until `\n` (or `\r\n`), so a message split or
  coalesced across any number of TCP reads is reassembled exactly; the
  pre-T9 `input.available()` + single-`read()` scheme silently truncated or
  coalesced payloads. Tokens are placed starting at the top-left corner,
  A1 (row 0, col 0), left-to-right, then top-to-bottom: the 1st token is A1,
  the 8th is H1, the 9th is A2, …, the 64th is H8 (server-confirmed
  orientation, T11 — the pre-T11 client started at H8 (bottom-right), the
  exact 180° rotation, and was corrected). Values: `1` = Black Pushee,
  `2` = Black Pusher, `3` = White Pushee, `4` = White Pusher, `0` = empty.
  **Validated (T9):**
  exactly 64 tokens, each an integer 0..4. Any other payload (30 ints, 65
  ints, a `5`, a `x`, …) is logged, the line is consumed (resync), the board
  and all other game state are left untouched, and the client waits for the
  next command instead of playing on a partial board.
- Move payload (after `'3'`): also read to the end of the line (T9; the
  parser itself is unchanged — `PusherApplication.parseMove`). The client's
  format is 4 characters
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

**Confirmed against the server?** PARTLY (T11 + follow-up).
**Board text:** YES — the 64 integers start at the top-left corner (A1) and
run left-to-right, top-to-bottom, exactly 64 of them; the pre-T11 client
assumed the opposite corner first and was corrected.
**Move text:** YES — the client's initial 4-char format (`D2E3`) is the
right one (server owner follow-up: "the initial format was the right one";
T11's 7-char send was reverted). The parser's legacy 7-char acceptance is
unchanged.
**Framing:** still unconfirmed — T9's **newline-terminated messages**
assumption stands (payload read to `\n`/`\r\n`, board payload validated as 64
ints in 0..4, resync-on-invalid). A server that sends unterminated chunks
would hang the client at the first payload read; if the real framing turns
out to be raw chunks, `PusherApplication.readLine` is the single place to
revisit.

## Open questions (for the server owner)

T11 + follow-up closed six of the seven original questions (see the resolved
list below). One remains:

1. **Message framing:** are messages newline-terminated (T9's assumption),
   length-prefixed, or raw chunks?

## Resolved in T11 (server owner's answers)

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
5. **Board text:** 64 integers, from top-left (A1) to bottom-right (H8),
   left-to-right, top-to-bottom. The pre-T11 client started at H8 — the
   exact 180° rotation — and was corrected.
6. **Move text:** the client's initial 4-char format (`D2E3`) is the right
   one (follow-up: "the initial format was the right one"; T11 briefly sent
   a 7-char layout and reverted it). The parser keeps accepting the legacy
   7-char layout as well. The framing remains open (see above).
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
