# Pusher-AI Improvement Plan

Each task below is an **independent work order**. An AI agent (or person) can pick up
any single task using only this file + the repository, with no follow-up questions.
Tasks are grouped in two parallel tracks; the only hard dependency is that every
task is based on `main` (the bug fixes from `fix/bugs-and-tests` are already
merged into `main`), and T6 is recommended after T3.

---

## Shared context (read this before any task)

### Repository layout
- `src/game/` — board model: `GameBoard` (singleton 8x8 board), `Piece` (abstract),
  `Pusher`, `Pushee`, `BoardSquare`, `Move`, `Player`, `Columns`.
- `src/client/` — engine + app: `GameTree` (alpha-beta search), `WhiteStrategy` /
  `BlackStrategy` (static evaluations), `GameStep` (memento), `GameAnalyzer`,
  `PusherApplication` (socket client).
- `src/tests/` — JUnit 4 tests. **`BugProofTest` is the regression guard**: it encodes
  every bug fixed in `fix/bugs-and-tests`. A task that breaks a BugProofTest test is
  wrong by definition.

### Game model (as implemented — see T5 for rules open questions)
- Rows 0..7. Row 0 (top) is BLACK's goal row; row 7 (bottom) is WHITE's goal row.
- `Player.WHITE.direction() == +1`, `Player.BLACK.direction() == -1`.
- **Pusher** (score value 7): moves 1 square straight or diagonally forward.
- **Pushee** (score value 3): same destinations, but a destination is only legal if
  a friendly Pusher occupies the mirror square behind it:
  `(row - direction, 2*col - destCol)`.
- Straight-ahead moves require an EMPTY square. Diagonal moves may capture enemy pieces.
- **Win**: any own piece on the enemy goal row (`GameBoard.winner()`).
- **No legal moves = loss** (stalemate, checkmate-like), ranked just below a goal-row
  win/loss via `GameTree.STALEMATE_MARGIN`.

### Engine invariants (do not violate)
1. `GameTree.alphaBeta` is the only search entry point besides the two
   `getBestMoveQuick` overloads. Terminals: `winner() == maxPlayer` →
   `MAX - turnNumber`; `winner() == minPlayer` → `MIN + turnNumber`;
   no legal moves → `±(MAX/MIN ∓ turnNumber ∓ STALEMATE_MARGIN)`.
2. **Static evaluations must stay |score| < 9000.** `getBestMoveQuick()` stops
   iterative deepening when the root score exceeds ±9000, using that as a
   "forced win/loss found" signal. Any evaluation term (threats, safety, ...)
   must keep realistic positions well below that bound.
3. `GameBoard` is a singleton and shared by all tests; `movePiece`/`undo` must
   keep every piece's cached legal-move list consistent (the 12-square
   `updateSurroundingPieces` set). Never assume fresh move lists in evaluation.
4. The move/undo pair and `createMemento`/`revertToState` must remain exact —
   the search and the timeout fallback rely on them. T2 landed: the capture
   log is LIFO (a `HashMap<Move, Piece>` keyed by square pair lost victims of
   nested same-pair captures) and `revertToState` refreshes all cached legal
   move lists after placement; `tests.BugProofTest` pins both.

### Build & test
T1 has landed: from the repo root, one command downloads the pinned test jars
(if missing), compiles `src/` -> `out/`, and runs every test class:
```bash
./build.sh test
# Expected: OK (121 tests). GameTreeTest takes ~5 s (real time-limited searches);
# PusherApplicationTest drives full-budget searches (a few seconds).
# (Count history: 42 at T1, +20 from T5's RulesContractTest, +2 from T7's
# BenchTest, +3 from T3's QuiescenceTest, +14 from T4's ThreatEvalTest,
# +7 from T2: 4 PusherApplicationTest + 3 BugProofTest regression tests,
# +4 from T8: 3 EvalSymmetryTest + 1 ThreatEvalTest mirror test,
# +7 from T6's TranspositionTableTest,
# +6 from T9: 3 PusherApplicationTest framing tests + 3 readLine/
# parseBoardPayload unit tests,
# +3 from T10's RepetitionTest: 2 engine + 1 app-level,
# +1 from T11 (board-orientation test; the two move-format tests from its
# first pass were reverted after the server owner confirmed the initial
# 4-char format),
# -6 from T12: T9's framing tests removed with the newline-framing revert
# (the wire stays unframed per the server owner),
# +6 from T13 Part A (AspirationPvsTest: exact-value, narrow-window,
# retry, cutoff, disable-flag, production-path tests),
# +5 from T13 Part B (PVS: exact-value at every depth, re-search fires,
# node reduction, TT flag consistency, on/off determinism),
# +7 from T14 (6 GameTreeTest: handoff score-invariance, handoff node
# reduction, constructor wiring, max-static-score bound, PVS×TT dirty-table
# fuzz, board-state discipline; 1 TranspositionTableTest: replacement
# contract).)
```
JDK 17+ required. `build.sh` auto-detects a JDK (macOS `java_home`, Homebrew,
sdkman, `/usr/lib/jvm`) if none is on `PATH`. (See `README.md`.)

### Per-task workflow
1. `git checkout main && git checkout -b <task-id>-<short-name>`.
2. Write the failing test(s) from the acceptance criteria first.
3. Implement. Do not touch anything outside the listed files unless the
   acceptance criteria require it.
4. Full suite green (67 + your new tests), run twice (two tests are
   shuffle-sensitive: run `tests.GameTreeTest` a second time).
5. Commit with a message starting `T<n>:`. Push. Open a PR against `main`.

---

## Track 0 — unblockers (do these first, in any order)

### T1 — One-command build & test
**Goal.** Any agent can build and run the whole test suite from a fresh clone
with one command.

**Scope.** New files only: `build.sh` (or `Makefile`) at the repo root. Do not
modify any `src/` file.

**Requirements.**
- `./build.sh test` (or `make test`):
  - downloads `lib/junit-4.13.2.jar` and `lib/hamcrest-core-1.3.jar` from Maven
    Central if missing (pin exact URLs/versions),
  - compiles all of `src/` to `out/`,
  - runs all test classes in `src/tests` via `org.junit.runner.JUnitCore`,
  - exits non-zero on compile or test failure, prints the JUnit summary line.
- Document JDK requirement (17+) in a one-line header comment.
- Add a short `README.md` section: what this is, how to run the tests,
  how to run the client (`PusherApplication`, expects a server on localhost:8888).

**Acceptance.** From a fresh clone (no `out/`, no `lib/`): `./build.sh test`
prints `OK (42 tests)` and exits 0. A deliberately broken assertion makes it
exit non-zero.

**Verification.** `rm -rf out lib && ./build.sh test`.

**Risk.** Low. **Estimate.** 0.5 agent-session.

---

### T2 — Mock pusher server + app integration test
**Goal.** Make `PusherApplication` testable end-to-end. Today the app layer
(protocol handling, multi-game resets, null-move handling) has zero coverage
because it needs a live server.

**Scope.** `src/tests/MockPusherServer.java` (test helper),
`src/tests/PusherApplicationTest.java`. Allowed refactor: extract the
`while(true)` command loop of `PusherApplication.main` into
`static void run(InputStream in, OutputStream out)` so tests can drive it with
in-memory streams (no real socket in the unit test). No engine changes.

**Requirements.**
- Mock server helper (real `ServerSocket`, thread):
  - speaks the current protocol: commands `'1'` (AI = White) / `'2'` (AI = Black)
    followed by a 64-int board (space separated, `1`=Black Pushee, `2`=Black
    Pusher, `3`=White Pushee, `4`=White Pusher, `0`=empty); `'3'` followed by
    the opponent's 4-char move (e.g. `D2E3`); `'4'` random move;
  - records every response it receives for assertions.
- Refactor `main` → `run(in, out)`; `main` only does socket setup.
- Integration test (drives `run` over `PipedStreams` or over the mock socket):
  1. `'1'` + a full board → app responds with a legal 4-char move for White
     (assert: matches `[A-H][1-8][A-H][1-8]`, origin/dest are in White's legal
     move list per the `GameBoard` singleton);
  2. `'3'` + a legal Black move → app applies it to the singleton board
     (assert board state changed correctly) and responds with a legal White move;
  3. two consecutive games (`'1'` … then `'2'` + new board): assert the board
     contains exactly the new game's pieces (no leftovers), turn player is
     reset, and the app does not replay the previous game's move;
  4. a garbage `'3'` payload (e.g. `XYZ`) → app logs an error and survives
     (loop continues; next valid command still handled).

**Acceptance.** All four scenarios above are tests and pass; `main` is
unchanged in behavior for a real server (socket setup code path preserved).

**Verification.** Full suite green ×2.

**Risk.** Low-medium (app refactor). **Estimate.** 1 agent-session.

**Findings (recorded in PR #6; full suite green ×3 at 88 tests):**
- **The new tests exposed two pre-existing engine bugs, both fixed and pinned
  in `BugProofTest`** (`testNestedCaptureSameSquarePair_restoresOuterVictim`,
  `testRevertToState_refreshesLegalMoveLists`, `testSearchLeavesBoardUntouched`):
  1. `GameBoard.killedPieceMap` was a `HashMap<Move, Piece>` keyed by the
     move's square pair. Two *nested* captures on the **same square pair**
     (capture, origin re-occupied, destination re-captured, capture again)
     shared one key: the inner capture's victim overwrote the outer's record
     and the outer victim was never restored on undo. The piece silently
     vanished, the board state drifted *consistently* (every list/square/
     turnNumber invariant held), and later searches played phantom moves —
     the client crashed with `movePiece: no piece at origin` or answered from
     a corrupted tree. Fixed with a LIFO capture log (`movePiece` pushes the
     victim or null, `undo` pops its own record; every undo call site is
     strictly nested move/undo). `GameStep`'s memento carries the log copy.
     Note: T3's quiescence (captures-only extension) makes the same-pair
     pattern common — the bug predates T3 but T3 is what makes it trigger in
     practice.
  2. `revertToState()` placed snapshot pieces row by row and each placement
     recomputed that piece's cached legal moves against a still-partially
     restored board, leaving stale lists after a timed-out search. Fixed with
     a full `updateAllLegaLMoves()` once the whole board is back.
- **Bench (post-fix, replaces the pre-T2 numbers as the current baseline):**
  `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":5,"blackWins":5,"avgPlies":13.00,
  "avgMaxDepth":12.50,"avgMsPerMove":~294}`; sanity seed 7, 6 games:
  `3/3 white, 13.00 plies, depth 12.00, ~286 ms`.
- **The starter wins every game in exactly 13 plies again (T7's baseline
  pattern), on every seed tried.** The T3 and T4 findings above — recorded on
  the pre-fix engine — do not reproduce on the fixed engine: the 10/0 White
  (post-T3) and 10/0 Black (post-T4) splits are gone (now 5/5 on the
  standard seed). At least on the standard bench position, part or all of the
  "strength changes" T3/T4 attributed to quiescence and threat/safety terms
  were artifacts of searches corrupting/aborting on the capture-log bug.
  **Read the T3/T4 Findings' color-split claims against this 5/5 baseline,
  not each other.** T8's symmetry check and bench verification must use the
  post-T2 numbers; expect its "within a small band" check to see ~50/50
  (starter-wins) as the null hypothesis.
- App layer (for T9/T10): `PusherApplication.run(InputStream, OutputStream)`
  is the test entry point; the `'3'` handler applies the pending own move
  **before** parsing the payload (pinned by test 4); the `'3'` payload is read
  with `available()` into a 16-byte buffer (T9's framing work replaces this);
  `move`/`gameTree`/`board` are static and carry over across `run()`
  invocations — each game reset (`'1'`/`'2'`) is what clears them.

---

## Track A — engine strength

### T5 — Pin down the rules (docs + constants)
**Goal.** Make every rules assumption explicit, in one place, so later tasks
(T3/T4 especially) build on a documented contract.

**Scope.** New `docs/RULES.md`; optional small refactor: introduce named
constants in `game/` for the rule-relevant numbers (goal rows 0/7,
`STALEMATE_MARGIN` already exists).

**Requirements.** `docs/RULES.md` must state, for each item, **what the code
does today** and **whether it is confirmed against the server**:
1. Win condition: *any* own piece (Pusher or Pushee) on the enemy goal row —
   confirmed? (Many versions of this game score only Pushees.)
2. Captures: diagonal capture legal, straight-ahead capture illegal — confirmed?
3. Stalemate: player with no legal move loses (ranked below a goal-row win) —
   or is it a draw?
4. Pushee movement: mirror-square Pusher requirement (straight push needs a
   Pusher directly behind; diagonal push needs one diagonally behind) — confirmed?
5. Protocol: exact board text format (orientation, which corner first) and move
   string format — the app currently accepts its own 4-char format and a
   legacy 7-char layout; which does the server actually use?
End the doc with an **Open questions** list for the server owner. If answers
come back that change a rule, implement it as a follow-up task (do not guess).

**Acceptance.** Doc merged; a JUnit test `RulesContractTest` asserting the
currently-implemented behavior of items 1–4 (characterization tests, so future
rule changes must be deliberate).

**Verification.** Full suite green.

**Risk.** Low. **Estimate.** 0.5 agent-session.

---

### T7 — Self-play & depth benchmark harness
**Goal.** A repeatable measurement of engine strength so T3/T4/T6 improvements
can be *quantified*, and regressions caught.

**Scope.** New `src/client/Bench.java` (main class, not a JUnit test) +
`src/tests/BenchTest.java` (smoke test: bench runs and reports, short config).

**Requirements.**
- `Bench` main, args: `--games N --budget MS --seed S`:
  - plays N full self-play games (both AIs, alternating who starts; board =
    2 Pushers + 3 Pushees per side, columns {2,5}/{1,3,4} — see
    `src/tests/GameTreeTest.setBoardToDefaultStart` for a heavier alternative),
  - per game reports: winner, ply count, elapsed ms, and the **max depth
    completed** within the budget (instrument `GameTree.getBestMoveQuick` to
    expose the last completed level — add a getter, do not change search
    behavior),
  - prints a JSON line summary: `{games, whiteWins, blackWins, avgPlies,
    avgMaxDepth, avgMsPerMove}`; `--seed` makes move-order shuffling
    reproducible (thread the seed into `GameTree`'s `Random` via a
    constructor overload).
- The harness must detect and report (not crash on): null move (stuck side)
  and games > 200 plies.
- **Baseline:** run `--games 10 --budget 2000 --seed 42` on `main`
  (the pre-T3/T4/T6 state) and record the JSON output in the PR
  description. Later tasks quote this baseline.

**Acceptance.** `java -cp out client.Bench --games 3 --budget 1000 --seed 1`
completes in < 60 s and prints the summary; `BenchTest` (1 game, budget 500)
passes in < 10 s; default behavior of `GameTree` (no-arg constructor) is
unchanged.

**Verification.** Full suite green; run bench twice with the same seed →
identical summaries (determinism check).

**Findings (baseline, recorded in PR #3 against the pre-T3/T4/T6 engine —
the T7 branch's `GameTree` delta is additive-only and the full suite is
green):**
- `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":5,"blackWins":5,"avgPlies":13.00,
  "avgMaxDepth":13.00,"avgMsPerMove":145.36}`
- **The starter wins every game in exactly 13 plies, identical across
  seeds.** The engine converges on a forced line: the side to move first
  converts a forced win every time, and the second side never defends it.
  T4's threat/safety terms must first be checked against the second side's
  defense of that line (expect the 5/5 split to shift or avgPlies to move);
  T3 should be checked against the 13-ply closing sequence.
- avgMsPerMove ≈ 145 ms at a 2000 ms budget: the ±9000 forced-win cutoff
  ends most searches far below budget, so T6's depth gains will show up
  mainly in the pre-cutoff opening moves.
- Determinism: same seed replays identical winners/plies/depths; ms fields
  vary with wall clock (the iterative-deepening stop is time-based).

**Risk.** Low. **Estimate.** 1 agent-session.

---

### T3 — Quiescence search
**Goal.** Kill the horizon effect: at depth 0, the engine currently scores
mid-tactic positions statically, blundering when the depth limit lands just
before an opponent's capture.

**Scope.** `src/client/GameTree.java` only (plus new tests in
`src/tests/QuiescenceTest.java`).

**Requirements.**
- In `alphaBeta`, when `depth == 0`: instead of returning the static score
  immediately, run a **stand-pat + captures-only** extension:
  - stand-pat value = `GameAnalyzer.analyze(board, strategy)`;
  - at a max node: if stand-pat ≥ β, return stand-pat; otherwise search the
    **capture** moves only (a capture = diagonal move onto a square occupied
    by an enemy piece — add a small `Move` helper or filter in
    `GameBoard` if cleaner), then return max(stand-pat, best child);
  - at a min node: symmetric with α;
  - terminals (`winner()`, no-moves) keep their exact scores and must be
    checked at every q-node (a capture onto the goal row is an immediate win);
  - bound the extension: a `qDepth` counter, hard cap 8 plies, beyond which
    return stand-pat;
  - q-nodes must obey the same time check as normal nodes; on timeout the
    existing memento revert still restores the board exactly.
- Keep normal (quiet) move selection at the parent nodes unchanged.
- **Do not** change `WhiteStrategy`/`BlackStrategy` (that's T4).

**Acceptance.** In `QuiescenceTest`:
1. **Hanging piece:** construct a position where the side to move (max) has two
   candidate moves at depth 1: move A leaves one of its own Pushers capturable
   by the opponent next ply; move B is safe and statically slightly worse.
   `getBestMoveQuick(1)` (depth-1 search ends exactly at the opponent's capture)
   must pick B. (Without quiescence this test fails — verify that first.)
2. **Winning capture in q-search:** a position where the only way to avoid loss
   at depth 0 is a capture that a plain depth-0 search would not see; with
   quiescence the engine picks it.
3. **Time:** on the 32-piece `setBoardToDefaultStart` position,
   `getBestMoveQuick()` still returns within `MAX_TIME_MILLIS + 500`.
4. Full suite (incl. `BugProofTest`) green ×2.

**Verification.** T7 bench (landed — see T7 findings): avg max depth
and/or win rate vs. the recorded baseline; at minimum the suite + the two
tactical tests.

**Findings (recorded in PR #4, post-T3 engine; full suite green ×2 at
67 tests, bench deterministic across two runs):**
- `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":10,"blackWins":0,"avgPlies":13.50,
  "avgMaxDepth":12.00,"avgMsPerMove":108-114}`
  (vs. baseline 5/5, 13.00 plies, 13.00 depth, 145.36 ms).
- **The starter-wins-every-game pattern is broken.** In the five games
  Black starts, White now wins: the second side defends the 13-ply
  closing sequence by seeing horizon captures — exactly the line T7
  flagged for T3. Caveat for T4/T8: the 10/0 color split may partly
  reflect the known White/Black evaluation asymmetry (White's +2
  defensive premium, see T8), not only the strength gain — T8's
  symmetry check should re-verify both colors, and T4's win-rate
  expectation should be read against this 10/0, not the old 5/5.
- avgMsPerMove dropped ~25% (145.36 → ~108–114 ms): q-node stand-pat
  tightens α/β, so the ±9000 forced-win cutoff fires earlier.
- avgMaxDepth 13 → 12 at the same budget: most moves still terminate on
  the forced-win cutoff before the budget is spent, so T6's depth
  gains remain the main lever (consistent with the T7 note).

**Risk.** Medium (perf; q-explosion is bounded by the 8-ply cap and the fact
that captures are rare in this game — average capture branching ≈ 1–2).
**Estimate.** 1–1.5 agent-sessions.

---

### T4 — Threat & safety evaluation terms
**Goal.** Let the static evaluation "see" the game's actual winning motif
without extra search depth: a Pushee one row from the goal with a Pusher
behind it is worth far more than 3 + row.

**Scope.** `src/client/WhiteStrategy.java`, `src/client/BlackStrategy.java`,
new `src/tests/ThreatEvalTest.java`. No search changes.

**Requirements.**
- Add to **both** strategies (mirror definitions, see T8):
  1. **Ready-to-win bonus:** own Pushee on the row adjacent to the enemy goal
     row (White: row 6; Black: row 1), where at least one of its legal
     destinations is the goal row (empty destination, valid mirror Pusher) →
     `+READY_TO_WIN` (choose 300–500; must respect the |eval| < 9000
     invariant — add an assertion in the test for that).
  2. **Capture danger penalty:** for each own piece that an opponent move can
     capture next ply (opponent has a legal diagonal move onto the piece's
     square), `−(piece value × 0.5, integer)`; and the symmetric bonus for
     enemy pieces you can capture next ply. Implement with one shared helper
     (e.g. in `GameAnalyzer`) that counts "capture targets" for a given player,
     using `getLegalMovesQuick()` of the opponent's pieces.
  3. **Pushee shielding (optional, only if time):** small bonus when a Pusher
     stands on the mirror square behind an enemy ready-to-win Pushee (you can
     occupy/capture the pushing square).
- Keep every term integer; document each constant with a one-line "why".

**Acceptance.** In `ThreatEvalTest` (pure `analyze()` calls, no search):
1. `position readyToWin` scores ≥ 3× higher than the same position with the
   Pushee one row further back (all else equal).
2. A position where you hang a Pusher to a next-ply capture scores lower than
   the identical position with the Pusher shielded (or moved off the square).
3. Symmetry (only if T8 landed first; otherwise skip this test): eval_white(p)
   == −eval_black(mirror(p)).
4. All realistic evals stay within ±8000 (property test over a handful of
   constructed positions).
5. Engine level: at depth 1, the engine does not play a move that hangs a
   Pusher to an immediate capture when an equal-or-better safe move exists
   (construct the position; assert the chosen move).
6. Full suite green ×2.

**Verification.** T7 bench vs. baseline (expect the ready-to-win term to raise
win rate or reduce average plies to win; see T7 findings on the 13-ply
forced starter win).

**Risk.** Low (pure evaluation). Main trap: accidentally crossing the 9000
bound — the property test above guards it. **Estimate.** 1 agent-session.

**Findings (post-T4 engine; full suite green ×2 at 81 tests, bench
deterministic across two runs with the same seed):**
- `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":0,"blackWins":10,"avgPlies":15.50,
  "avgMaxDepth":11.50,"avgMsPerMove":108-112}`
  (vs. the post-T3 engine: 10/0 WHITE, 13.50 plies, 12.00 depth,
  108-114 ms; sanity run on a different seed, 6 games: 0/6 white,
  13.50 plies, 11.50 depth).
- **The color split flipped completely: Black now wins every game (10/0 on
  the standard seed, 0/6 on a second seed) where the post-T3 engine was
  10/0 White.** T4's terms are color-symmetric by construction (one shared
  GameAnalyzer helper parameterized by the player), so the flip is an
  interaction with the remaining base-eval asymmetry (White's +2 defensive
  premium, Black's missing mobility term — both T8's target), exactly as the
  T3 note predicted ("T4's win-rate expectation should be read against this
  10/0"). T8's symmetry check must re-verify both colors; the T4-vs-T3
  strength comparison should be read on plies and behavior, not on the color
  split.
- avgPlies 13.50 → 15.50: the forced line is ~2 plies longer — the
  defending side now contests hanging pieces and the ready-to-win race
  instead of the previously forced conversion (the plan's expectation of a
  reduced avg plies did not materialize; the forced-win structure remains,
  with the winning side flipped).
- avgMaxDepth 12.00 → 11.50 at the same budget and avgMsPerMove flat
  (~108-112 vs ~108-114 ms): the tighter stand-pat values in q-nodes trip
  the ±9000 forced-win cutoff slightly earlier; the per-leaf cost of the
  threat computation is negligible. T6 (TT + move ordering) remains the main
  depth lever.

---

### T8 — Symmetric evaluations
**Goal.** The two strategies currently disagree on material treatment (White
charges a +2 defensive premium per enemy piece, Black does not). Unify them so
`eval_white(p) == −eval_black(mirror(p))` holds exactly.

**Scope.** `src/client/WhiteStrategy.java`, `src/client/BlackStrategy.java`,
new `src/tests/EvalSymmetryTest.java`.

**Requirements.**
- Extract the evaluation into one shared implementation parameterized by the
  max player (e.g. `GameAnalyzer.analyzeFor(board, maxPlayer)`), or restructure
  the two classes to be pure mirrors. Decide which keeps the `Strategy`
  interface usage in `GameTree` clean; both strategies must then delegate.
- The shared implementation must produce: own piece `value + advancement`
  (White: `+row`; Black: `+(7−row)`); edge-file Pushee = 0 (both); enemy piece
  `−(value + advancement + DEFENSIVE_PREMIUM)` with the **same**
  `DEFENSIVE_PREMIUM` for both colors (keep the current 2 for White — do not
  change engine behavior more than unifying Black to match); own mobility `/2`
  for both.
- **Mirror** = swap colors and map row r → 7−r (columns unchanged).

**Acceptance.** `EvalSymmetryTest`: for 5+ hand-built positions (including
pieces on edge files, ready-to-win shapes, and captures-available shapes):
`white.analyze(p) == −black.analyze(mirror(p))` — compute both on the same
board by rebuilding the mirrored position (the singleton makes this a
set-up/evaluate/set-up/evaluate sequence). Full suite green ×2. Note: if T4
landed first, its terms must be mirrored identically (that's the point of
doing T4 via shared helpers).

**Verification.** Full suite; T7 bench (expect near-identical strength for
both colors: in the T7 summary, whiteWins and blackWins should be within a
small band over 20+ games).

**Risk.** Low-medium (touching both evals at once — the symmetry test is the
safety net). **Estimate.** 0.5–1 agent-session.

**Findings (post-T8 engine; full suite green ×2 at 92 tests, bench
deterministic across two runs with the same seed):**
- `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":5,"blackWins":5,"avgPlies":13.00,
  "avgMaxDepth":12.00,"avgMsPerMove":309-332}`; 20-game runs (seeds 42 and
  7): 10/10, 13.00 plies, 12.00 depth, ~302-309 ms; sanity seed 7, 6 games:
  3/3, 13.00 plies, 12.00 depth.
- **The color split is exactly even and is now move-order-driven: the
  starter wins every game in 13 plies (10/10 over 20 games on both seeds) —
  the T2 null hypothesis.** T4's 0/10 Black flip is gone: it was the
  base-eval asymmetry (White charged a flat +2 with no enemy advancement,
  Black charged advancement with no premium, and only White had the mobility
  term), exactly what T3/T4 predicted T8 would resolve. T6 should read its
  baseline against this 13-ply starter-win pattern (12.00 depth, ~310 ms per
  move at a 2000 ms budget).
- avgPlies 15.50 → 13.00 and avgMsPerMove ~110 → ~310 ms: the post-T4
  engine's early ±9000 forced-win cutoffs and the contested 15.5-ply line
  were artifacts of the biased eval. The unified engine's search profile
  matches the post-T2 fixed-engine baseline (~294 ms, 13.00 plies, 12.50
  depth) with T3's quiescence still in place; avgMaxDepth 11.50 → 12.00.
- **The plan's mirror formula has a sign typo.** T8's acceptance writes
  `white.analyze(p) == −black.analyze(mirror(p))`, but every strategy here
  scores from its OWN color's perspective (GameTree maximizes it — a lone
  own Pusher scores +11 = value + advancement + mobility), so the exact
  mirror identity under this convention is
  `white.analyze(p) == +black.analyze(mirror(p))`; the negated form would
  require own material to score negative and is unsatisfiable by the plan's
  own required term form. EvalSymmetryTest asserts the equal-sign identity
  (documented in the test) and additionally pins the exact unified form, so
  a "symmetric but wrong terms" implementation is caught too.
- Re-pinned exact values (same regression properties, unified form):
  BugProofTest lone Black Pusher on file H 11 → 12 (mobility term now on
  both colors) and the own-mobility position −16 → −18 (enemy advancement
  now counted for White); ThreatEvalTest threat cluster 1232/437 → 1219/429
  and the two shield integrations 15/16 → 4/5. T4 acceptance 3 (skipped when
  T4 landed) is implemented as
  ThreatEvalTest.mirrorSymmetryForTheThreatTerms.

---

### T6 — Transposition table + move ordering
**Goal.** Reach more effective depth in the 4.95 s budget: stop re-searching
transposed positions and search the best-looking move first (better alpha-beta
cutoffs). **Recommended after T3** (ordering helps the q-search too).

**Scope.** New `src/client/TranspositionTable.java`; changes in
`src/client/GameTree.java`; new `src/tests/TranspositionTableTest.java`.

**Requirements.**
- **Position key:** Zobrist hashing — 64 squares × 4 piece types + 1
  side-to-move key = 257 `long[]` random keys (fixed seed for reproducibility);
  incremental update in `GameBoard.movePiece`/`undo` (add the key field +
  `update` calls; keep the existing API). Store a 64-bit truncated hash.
- **TT entry:** `hash`, `depth`, `score`, `flag` (EXACT / LOWER / UPPER),
  `bestMove`. Size: 1<<20 entries, replace-agnostic (deeper entry wins).
- **Lookup in `alphaBeta`:**
  - if entry depth ≥ current depth and the hash matches, return the stored
    score **only** when the flag is compatible with the window (EXACT always;
    LOWER only if score ≥ β; UPPER only if score ≤ α);
  - store after the child loop using the standard flag rules
    (original α ≥ β → LOWER for max / UPPER for min; score ≤ original α →
    UPPER / LOWER respectively; else EXACT).
  - Terminal scores (wins/stalemates) are stored like any other.
  - **Collision safety:** also store the turn number; on hash match with a
    different turn number, treat as a miss (cheap, bounds the error from the
    64-bit truncation).
  - **Memento path:** `revertToState` replaces the entire board at once, so
    incrementality does not apply there — it must restore the stored hash
    (add it to `GameStep`, like `turnNumber` was) or recompute from scratch.
- **Move ordering** (at every non-leaf node, before the existing shuffle):
  1. TT `bestMove` first (if present and still in the legal list),
  2. captures in MVV-LVA order (victim value / own value),
  3. remaining moves in the existing shuffled order (keep the shuffle —
     `testRandomnessForMoreThanOneMove` depends on non-deterministic tie-breaks).
- Add a bench hook: print the TT hit-rate per search (debug flag).

**Acceptance.**
1. `TranspositionTableTest`: unit tests for Zobrist (same position reached by
   two move orders → same key; one piece moved → different key; side-to-move
   flips the key), and for flag storage/lookup on a synthetic table.
2. **Determinism check:** with a fixed seed (T7's `--seed`), the best move
   chosen on 5 fixed positions is **identical** with TT on vs. off (add a
   constructor/flag to disable the TT for this test) — this catches flag-handling
   bugs.
3. T7 bench: avg max depth strictly higher than the pre-T6 baseline at the
   same budget, and wall-clock per move within 20% of baseline (TT must not
   cost more than it saves).
4. Full suite green ×2.

**Risk.** **High** — TT flag handling is the classic source of subtly wrong
  searches. The determinism check (acceptance 2) is mandatory, not optional.
  If depth does not improve after a careful pass, the task is considered
  done-with-findings (report hit-rate and move-ordering effect separately).
**Estimate.** 2–3 agent-sessions.

**Findings (post-T6 engine; full suite green ×2 at 99 tests, bench
deterministic across two runs with the same seed):**
- `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":5,"blackWins":5,"avgPlies":13.00,
  "avgMaxDepth":13.00,"avgMsPerMove":~72-75}` (TT hit rate 48.5% over
  ~7.7M probes, via the new `--tt-stats` flag) vs. the pre-T6 baseline
  (12.00 depth, ~317 ms per move).
- **avgMaxDepth 12 → 13 at the same budget (acceptance 3): the TT + move
  ordering is worth a full extra level on the standard bench position, and
  avgMsPerMove dropped ~77% (317 → ~72 ms)** — table reuse plus tighter
  windows more than pay for the overhead, far inside the 20% wall-clock
  bound. The starter-wins-in-13-plies pattern (the T8 null hypothesis) is
  unchanged: same 5/5 split, same plies, same winners on both colors.
- **The root bypasses TT score returns, by design.** The plan's literal
  "return the stored score on a usable hit" applied to the root of a
  repeated search on the same position replays the previous search's move
  whenever the root ties — and testRandomnessForMoreThanOneMove's position
  ties every root move (Black has no pieces at all, so any White move is a
  one-ply stalemate win and the ±9000 cutoff ends every search at level
  1), collapsing the pinned non-deterministic tie-break to the same move in
  all 5 searches (measured: 40% flake rate on GameTreeTest). The root now
  (a) never returns a stored score and (b) takes ordering only from
  strictly shallower entries — this search's own earlier levels, the
  standard iterative-deepening handoff, which never exists at level 1. The
  root therefore stays a pure shuffle at level 1, exactly as before T6.
  Internal nodes use the standard rules (score and ordering from entries of
  depth ≥ current). The pinned test runs 20/20 clean after the fix (its
  residual ~6% flake — five consecutive fair-coin ties — is inherent to the
  test and exists on main too).
- **The plan's flag shorthand has a sign typo, like T8's mirror formula.**
  "score ≤ original α → UPPER / LOWER respectively" would store a min
  node's fail-low as LOWER; it is UPPER (the returned beta bounds the true
  value from above — a max child can never return below the window's
  alpha, so a min node's beta only ever lands exactly on it). Implemented
  and commented the derived-correct rules; the max-node half of the
  shorthand was already right.
- Implementation notes: one 1<<20 table per GameTree (scores are from that
  tree's maxPlayer perspective, so no sign conversion is ever needed across
  the White/Black trees); q-nodes are excluded from probe/store (their
  values use the capture-horizon convention); the Zobrist key (257
  fixed-seed keys) is maintained incrementally in GameBoard's
  movePiece/undo/putPiece/reset/setCurrentPlayer and carried by GameStep
  so revertToState restores it exactly; `--tt-stats` on the bench prints
  the aggregate probes/hits/hit-rate line quoted above.

---

## Track B — client robustness

### T9 — Proper message framing
**Goal.** Replace `input.available()` + single-`read()` with real framing, so
split or coalesced TCP reads can't corrupt game state.

**Depends on:** T2 (mock server) for testing. T2 has landed: the server is
`src/tests/MockPusherServer.java` (single write + one flush per command — add
split-write support here, e.g. send 2 bytes, sleep, send the rest) and
`PusherApplicationTest.AppSession` already drives `run()` over piped streams
for the no-socket cases.

**Scope.** `src/client/PusherApplication.java` (reader helpers); extend the
T2 mock server to send a message split across two writes (e.g. 2 bytes,
sleep, rest) to exercise the fix.

**Requirements.**
- Confirm with the server owner the exact framing (newline-terminated? length
  prefix? raw chunks?). Document the answer in `docs/RULES.md` §protocol.
  **If unconfirmed, assume newline-terminated messages** and implement
  `readLine()`-style reads: command byte read as today; board payload and
  move payload read until `\n` (strip whitespace).
- Board read must validate: exactly 64 integers 0..4, else log + resync
  (wait for next command) instead of placing a partial board.
- Keep `parseMove` (4-char + legacy 7-char) as the move validator.

**Acceptance.** T2-based tests: (a) board sent in 3 TCP fragments is parsed
correctly; (b) a truncated board (30 ints) is rejected without corrupting the
`GameBoard`; (c) normal single-shot sends still work (regression).

**Verification.** Full suite green ×2.

**Risk.** Medium (protocol guesswork — the doc step first). **Estimate.**
1 agent-session.

**Findings (post-T9 client; full suite green ×2 at 105 tests):**
- Framing was never confirmed with the server owner, so the plan's fallback
  is implemented: **newline-terminated messages**. `PusherApplication.readLine`
  reads each payload byte-by-byte to `\n`/`\r\n` (the `\n` half of a CRLF pair
  is consumed so the outer command loop cannot misread it; EOF without a
  payload → null; EOF with a partial payload → returned as-is). The command
  byte is still read as a single byte. **Carried-forward risk:** a server that
  sends unterminated chunks would stall the client at the first payload read —
  if the real framing turns out to be raw chunks, `readLine` is the single
  place to revisit (docs/RULES.md item 5 and open question 6 say exactly
  that).
- Board payloads are now validated (`parseBoardPayload`): exactly 64
  whitespace-separated integers, each 0..4. Anything else is logged, the line
  is consumed (resync), the singleton board and all game state are left
  untouched, and the loop waits for the next command — no more partial boards.
  `beginNewGame` validates *before* `board.reset()`, so a bad `'1'`/`'2'`
  cannot wipe an in-progress game or its pending move.
- The `'3'` move payload uses the same line read; `parseMove` (4-char +
  legacy 7-char) is the unchanged validator.
- `MockPusherServer` now sends newline-terminated messages and gained
  `sendFragmented(command, payload, fragmentCount, gapMillis)` (plus the
  `sendNewGameWhiteFragmented` / `sendNewGameBlackFragmented` /
  `sendOpponentMoveFragmented` wrappers) to split a message across flushed
  writes with a gap; `AppSession.send` mirrors the framing and
  `AppSession.sendRaw` feeds deliberately malformed lines.
- New tests (6; suite 99 → 105): a board sent in 3 socket fragments ~150 ms
  apart is reassembled exactly and the stream stays in sync for the next
  single-shot command (red pre-T9: partial board, no response); a 30-int
  board is rejected with the board and turn state byte-identical to before,
  and the loop recovers on the next valid `'3'` (red pre-T9: 30 tokens
  placed, game played on the partial board); single-shot regression; unit
  tests for `readLine` (split bytes, CRLF, EOF) and `parseBoardPayload`
  (30/65 tokens, `5`, `-1`, `x`, null, empty).

---

### T10 — Endless-play guard (repetition + ply cap)
**Goal.** If both engines settle into a repetition, the game never ends and
the client burns CPU forever.

**Scope.** `src/client/GameTree.java` (small: anti-repetition move penalty) and
`src/client/PusherApplication.java` (ply cap bookkeeping); new
`src/tests/RepetitionTest.java`.

**Requirements.**
- **Engine level:** `GameTree` keeps a bounded list (e.g. last 60) of position
  identifiers (reuse T6's Zobrist key if T6 landed; otherwise a compact
  board-state string like `12340000…` + side to move). When evaluating
  candidate root moves, subtract `REPETITION_PENALTY` (e.g. 5) from a move's
  score if its resulting position is already in the list. Apply at the root
  only (cheap, no search-semantics risk).
- **App level:** count plies per game; after `MAX_PLIES` (e.g. 300) log a
  warning and keep playing (do **not** unilaterally declare a draw — the
  protocol has no known draw command; record this as an open question in
  `docs/RULES.md`).
- The move chosen after the cap/pref must still be legal.

**Acceptance.** `RepetitionTest`: (a) construct a position where the engine's
  preferred move (without penalty) creates a position it has "seen" in its
  history (inject the history via a package-visible setter); assert a
  non-repeating move is chosen when one exists; (b) the full suite green —
  specifically `BugProofTest` (the penalty must not override terminal logic:
  a forced win is never sacrificed to avoid repetition — add a test: forced
  win available AND the winning move repeats → the engine still wins).

**Verification.** Full suite green ×2; T7 bench: no game exceeds the ply cap.

**Risk.** Low-medium. **Estimate.** 1 agent-session.

**Findings (post-T10 engine; full suite green ×2 at 108 tests):**
- **The position identifier is T6's Zobrist key with the side-to-move bit
  removed** (`GameTree.arrangementHash`, a conditional XOR of
  `TranspositionTable.sideToMoveKey()`, which the board carries exactly when
  it is BLACK's turn). With the full key the guard could never fire in real
  play: every position a tree is asked to move from has the AI to move, while
  a root child has the OPPONENT to move, so full-key child hashes never match
  the history. The arrangement-only key is what "the game has been in this
  position before" means for shuffle-breaking.
- The history (bounded `REPETITION_HISTORY_LIMIT` = 60, oldest evicted) is
  appended in BOTH `getBestMoveQuick` overloads at search start; the penalty
  (`REPETITION_PENALTY` = 5) is applied in the `alphaBeta` root loop only,
  after the child returns and before the undo, so the checked hash is exactly
  the resulting position's. The damped root score stored in the TT is
  harmless: the root never returns stored scores and only takes ordering from
  strictly shallower entries (T6), and internal nodes are never damped.
- **The penalty can never override terminal logic**: terminal child scores
  are ~±2^31 minus the turn number, so a forced win repeats or not, it beats
  every non-terminal score (realistic evals stay below |9000|), and between
  two winning moves the non-repeating one is preferred (the 5-point gap). Pinned
  by `RepetitionTest.aForcedWinIsNeverSacrificedToAvoidRepetition` (unique
  winning push, its child position injected into the history — the engine
  still plays it). `BugProofTest` stays green.
- App level: `PusherApplication.plies` counts applied plies (own pending
  move + opponent move per `'3'`; both via `registerPly`), reset in
  `beginNewGame` (so a rejected T9 board payload leaves the running count
  intact, consistent with its resync semantics); at `MAX_PLIES` (300) the
  client logs a warning and keeps playing — no draw is declared, because the
  protocol has no known draw command (now docs/RULES.md open question 7).
  Both fields are public non-final statics so the app-level test exercises
  the warning path with a small cap (restored in `finally`).
- The plan's "package-visible setter" had to be `public` (`setRepetitionHistory`):
  the tests live in the `tests` package, one package away from `client`.
- `RepetitionTest` (3 tests; suite 105 → 108): (a) a position where the
  unique natural best move (static eval 15 vs 14, hand-computed in the test)
  returns to an injected seen arrangement — with the history the engine
  plays a non-repeating move; (b) the forced-win pin above; (c) app level: a
  collision-free two-piece race with `MAX_PLIES = 4` — the warning is logged
  at the cap (asserted on captured stdout) and the app keeps answering past
  it. `AppSession` became package-visible for (c) to reuse.
- **T7 bench (verification):** `--games 10 --budget 2000 --seed 42` →
  `{"games":10,"whiteWins":5,"blackWins":5,"avgPlies":13.00,
  "avgMaxDepth":13.00,"avgMsPerMove":~71-73}` — identical winners/plies/
  depths to the T6 baseline across repeated same-seed runs (deterministic),
  sanity seed 7 (6 games): 3/3, 13.00 plies, 13.00 depth. No game comes
  close to the 300-ply cap, and the penalty has no measurable effect on the
  standard bench position (the 13-ply forced line contains no repetitions).

### T11 — Apply the server-confirmed rules (board orientation; move format = initial 4-char)
**Goal.** Record the server owner's answers to T5's open questions
(`docs/RULES.md`) and implement the change they require: the board-text
orientation. (The first pass also switched the move send to a 7-char layout;
the follow-up confirmed the initial 4-char format was the right one and that
was reverted — see answer 6.)

**Depends on:** T5 (the questions it answers), T9 (the payload plumbing it
changes). **Not dependent on any engine task — no search/eval changes.**

**Scope.** `src/client/PusherApplication.java` (board placement order; the
move send stays the initial 4-char — see answer 6), `src/tests/MockPusherServer.java` (encode what the real server
sends), `src/tests/PusherApplicationTest.java`, `src/tests/RepetitionTest.java`
(opponent moves stay 4-char), `docs/RULES.md`, `README.md` (the one-line client
description).

**Answers (server owner):**
1. **Win condition:** any piece (Pusher or Pushee) on the enemy goal row
   wins — explicitly confirmed in the follow-up ("both pieces can win");
   the BLACK-wins-tie rule stands, but a double goal-row position is
   impossible in play (players alternate; the first goal-row landing ends
   the game). → no code change.
2. **Captures:** diagonal-only. → no code change.
3. **Stalemate:** no legal move is a loss. → no code change.
4. **Pushee rule:** mirror-square Pusher requirement, "mirror everywhere"
   including the diagonal case — verified against the code: it is the *only*
   setup that lets a Pushee move (no alternative adjacency rule anywhere).
   → no code change.
5. **Board text:** **top-left to bottom-right** — the 1st of the 64 ints is
   A1 (row 0, col 0), left-to-right, top-to-bottom. The client placed the
   1st token at H8 (bottom-right): the exact 180° rotation. → `placeBoard`
   flipped.
6. **Move text:** the client's **initial 4-char format is the right one**
   (follow-up: "the initial format was the right one, why are you trying to
   change the format?"). T11's first pass had read the earlier "Didn't the
   server expect 7 chars?" as confirmation of a 7-char send and added
   `toServerMoveString` — that was reverted: `communicateMove` sends
   `move.toString()` (4-char) again, and `parseMove`'s lenient legacy
   7-char acceptance is unchanged. Framing stays unconfirmed (T9's newline
   assumption stands).
7. **Endless play:** impossible under the confirmed rules (every ply
   advances one piece one row toward its goal row; the total
   remaining-distance potential strictly decreases, so a position can never
   repeat and every game ends in a win or a stalemate-loss). No draw
   command is needed. → the T10 guards are kept as defensive safety nets.

**Acceptance.**
1. `boardPayloadIsPlacedTopLeftFirst` — a marker board (a piece on each
   corner + two mid-board squares) sent via `'2'` is placed exactly as sent
   (red pre-flip: the board comes back as the 180° rotation).
2. Move send: the client sends the initial 4-char format (`move.toString()`),
   confirmed as the right one by the server owner's follow-up. (First pass,
   reverted: a 7-char `toServerMoveString` send plus two format tests were
   added on the strength of "Didn't the server expect 7 chars?"; the
   follow-up made it clear the initial format was correct, so they were
   removed.)
3. All scenario tests speak the confirmed protocol: the mock encodes boards
   top-left-first; client responses are the 4-char format (mock + AppSession
   read 4-byte chunks); `RulesContractTest` stays untouched (items 1–4 are
   unchanged by this task).
4. Full suite green ×2.

**Verification.** Full suite ×2; `./build.sh test` expected count updated.

**Risk.** Low-medium (wire format; the round-trip scenario tests are the
safety net — they pin position AND piece type for every board sent).
**Estimate.** 1 agent-session.

**Findings (post-T11 client; full suite green ×2 at 109 tests):**
- The orientation fix is a pure 180° rotation: `placeBoard` now walks the
  tokens left-to-right, top-to-bottom from A1 (row 0, col 0), and
  `MockPusherServer.boardText` encodes the same order, so every existing
  round-trip scenario test (which pins position AND piece type for each
  board sent) needed no edits beyond the format updates.
- Move send: **reverted to the initial 4-char format after the server
  owner's follow-up** ("the initial format was the right one"). The first
  pass — built on the earlier "Didn't the server expect 7 chars?" — had
  switched `communicateMove` to a 7-char `toServerMoveString` (origin 0–1,
  destination 5–6, three spaces between) with two format tests; all of that
  was removed and `communicateMove` sends `move.toString()` again. The
  `parseMove` leniency (accepts 4-char + the legacy 7-char layout) is
  unchanged, so a server that sends the 7-char layout is still parsed.
- RULES.md: items 1–4 confirmed as implemented (item 1 explicitly —
  "both pieces can win"; Q4 with the code verification — the mirror-square
  friendly Pusher is the only setup that enables a Pushee move for all three
  destination types; the `hasAdjacentPusher` helper exists but is unused),
  item 5 confirmed with the H8→A1 correction, item 6 confirmed (initial
  4-char format; framing remains an assumption), item 7 resolved with the
  strict-decrease proof (Φ = total remaining forward distance; every ply
  decreases Φ by ≥ 1; Φ₀ ≤ 70 for the standard 5+5 setup, so the T10 300-ply
  cap is unreachable under the rules). The open-questions list is down to
  one: the framing (assumed newline-terminated).
- Red/green: with the mock encoding the confirmed order, exactly the 10
  app-level tests failed (boards came back as the 180° rotation) while all
  101 engine/board tests stayed green. After the `placeBoard` flip the full
  suite was green ×2 at 111 tests; after the move-format revert it is green
  ×2 at 109.
- New tests: 1 kept (board orientation; suite 108 → 109 — the two
  move-format tests were added in the first pass and removed with the
  revert).

### T12 — Restore the original wire protocol (H8-first orientation, no framing)
**Goal.** The server owner's follow-up (T11 review): "the old original code
probably had the orientation right too... if there were no framing in place
before, I wouldn't add one. The parsing was already functioning correctly."
The initial client demonstrably worked against the real server, so the
original wire behavior is the contract: board tokens start at H8
(bottom-right), the move send is the initial 4-char format, and there is no
message framing (payload read in the original available()-based read).
T11's orientation flip and T9's newline framing are both reverted; T10's
ply bookkeeping is preserved.

**Depends on:** T11 (the follow-up it elicited), T9 (the framing it reverts).

**Scope.** `src/client/PusherApplication.java` (restore the pre-T9 wire
logic — `createInitialBoard` with the H8-first placement and available()
reads, the 16-byte move read — keeping the T2 robustness fixes and T10's
plies bookkeeping; drop readLine/parseBoardPayload/beginNewGame
validation), `src/tests/MockPusherServer.java` (restore the T2 mock:
single write+flush per command, no newline, H8-first boardText, 4-byte
response reads, no sendFragmented), `src/tests/PusherApplicationTest.java`
(drop T9's six framing tests; the orientation test now pins the original
H8-first order), `docs/RULES.md`.

**Answers this task records (server owner):**
- **Orientation:** the original order (1st token = H8, right-to-left,
  bottom-to-top) is the wire format — the initial code "probably had the
  orientation right too" because the parsing was already functioning
  correctly against the real server. The earlier "top-left to bottom-right"
  answer (T11) was a design preference, not a fact; retracted.
- **Framing:** none. "if there were no framing in place before, I wouldn't
  add one" — T9's newline-terminated read (an unconfirmed assumption) is
  reverted; a server that sends no newlines would hang a newline-based
  reader, and the original available() read is what worked in production.
  The theoretical weakness of the available() read (a payload split across
  TCP segments) is accepted by the server owner.
- **Move format:** unchanged — the initial 4-char send (T11 follow-up).

**Acceptance.**
1. The orientation test pins the original order: a marker board (a piece on
   each corner + two mid-board squares) sent via '2' places token 0 at H8
   (row 7, col 7), token 7 at A8, token 56 at H1, token 63 at A1.
2. All T2 scenario tests pass unchanged against the restored wire behavior
   (mock sends command+payload in one write without newline; the app reads
   via available()).
3. T9's framing tests (fragmented board, truncated board, single-shot,
   readLine ×2, parseBoardPayload unit) are removed with the framing.
4. T10's ply bookkeeping (plies/MAX_PLIES/registerPly, reset per new game)
   is preserved — RepetitionTest stays green.
5. Full suite green ×2 (109 → 103).

**Verification.** Full suite ×2.

**Risk.** Low — restores a known-working state; the T2 scenario tests are
the regression net. **Estimate.** 0.5 agent-session.

**Findings (post-T12 client; full suite green ×2 at 103 tests):**
- `PusherApplication` is the pre-T9 wire logic (available() reads, H8-first
  `createInitialBoard`, 4-char `move.toString()` send, lenient 4+-/7-char
  `parseMove`) with T10's ply bookkeeping re-applied (plies/MAX_PLIES/
  registerPly + reset in the '1'/'2' handlers) and the T2 robustness fixes
  (EOF exit, per-command exception containment, null-move no-send).
  `readLine`/`parseBoardPayload`/`beginNewGame` validation are gone. The
  `run()` javadoc now documents the no-framing contract so it is not
  "fixed" back into newline framing.
- `MockPusherServer` is the T2 mock restored verbatim (single write+flush
  per command, no newline, H8-first `boardText`, 4-byte response reads);
  the six T9 framing tests are removed; the orientation test now pins the
  original H8-first order (`boardPayloadIsPlacedH8First`).
- RULES.md: items 5–6 now read "the original wire behavior is the
  contract"; the open-questions list is empty — all seven T5 questions are
  resolved.
- Suite: 109 → 103 (−6 T9 framing tests; the orientation test was kept and
  flipped).

---

### T13 — Aspiration windows + PVS

**Goal.** Push the effective search depth past the 8–9 plateau observed in
full-board games (8 Pushers + 8 Pushees per side, 4950 ms budget). T6's TT
+ move ordering tightened the windows, but the root still searches every
iteration with a full `(-∞, +∞)` window, and every non-best child at an
internal node also gets a full-width window. Aspiration windows narrow the
root's window to the previous iteration's score ± a small margin; PVS
narrows every non-first child to a zero-width window. Together they should
buy 0.5–1.5 effective plies at the same budget.

**Depends on:** T6 (TT + move ordering — PVS requires the first move to be
the best-ordered one for the zero-width trick to work well), T3 (quiescence
— the depth-0 exit is unchanged; PVS applies only to depth > 0 nodes).

**Scope.** `src/client/GameTree.java` only (plus new tests in
`src/tests/AspirationPvsTest.java`). No changes to `GameBoard`, evaluation,
or protocol code.

**Requirements.**

#### Part A — Aspiration windows

1. In `getBestMoveQuick()`, replace the full-width iterative-deepening loop
   with an aspiration-window version:
   - **Iteration 1:** full window `(-∞, +∞)` (no prior score exists).
   - **Iteration N ≥ 2:** window `(prevScore - MARGIN, prevScore + MARGIN)`
     where `prevScore` is the root score returned by iteration N−1 and
     `MARGIN` starts at `ASPIRATION_MARGIN` (constant, initial value 5).
   - **Aspiration failure:** if the search returns a score ≥ the window's
     upper bound (fail-high) or ≤ the lower bound (fail-low), the true score
     is outside the window. Revert the board to the pre-iteration snapshot
     (the existing memento mechanism) and retry the same level with a wider
     window: double the margin (up to a cap of `MAX_ASPIRATION_MARGIN` = 200);
     if the margin reaches the cap, use a full window.
   - **Forced-win cutoff interaction:** if the (failed) search already
     returned |score| > 9000, do NOT retry — the forced-win/loss cutoff
     fires regardless of the window. Break out of the iteration loop as
     today.
   - **Time check:** the time check is unchanged. An aspiration failure
     within budget triggers a retry; a timeout during the retry is handled
     by the existing catch/revert path.
   - The memento snapshot is taken once before the iteration loop (as today);
     each aspiration-failure retry reverts to it before the next attempt.

2. Add constants:
   ```java
   private static final int ASPIRATION_MARGIN = 5;
   private static final int MAX_ASPIRATION_MARGIN = 200;
   ```

#### Part B — PVS (Principal Variation Search)

3. In `alphaBeta` (the 6-argument overload), restructure the child loop:
   - After `orderMoves`, iterate:
     - **First child (index 0):** full window `[alpha, beta]` — this is the
       TT best-move (or the first shuffled move at level 1). The full window
       is needed to establish the correct bound for the remaining children.
     - **Subsequent children (index ≥ 1):** zero-width window
       `[alpha, alpha + 1]` at a max node, or `[beta - 1, beta]` at a min
       node. This is the "null window" — if the child's true score exceeds
       alpha (max) or falls below beta (min), it is a PV candidate.
     - **PV re-search:** if a subsequent child's score violates the bound
       (score > alpha at max, score < beta at min), immediately re-search
       that same child with the full window `[alpha, beta]` (max) or
       `[alpha, beta]` (min — the window is the same, only the initial
       alpha/beta passed differ). Update alpha/beta with the re-searched
       score. This re-search is the standard PVS "fail-high retry".
   - The rest of the loop (undo, repetition penalty, cutoff check) is
     unchanged.
   - **TT interaction:** the stored score and flag use the FINAL alpha/beta
     after any re-search, exactly as today. No TT changes needed.
   - **Quiescence interaction:** PVS applies only at `depth > 0`. The
     `depth == 0` exit (quiescence) is reached by the children and is
     unaffected — the q-search already does its own stand-pat + captures.
   - **Time check:** the re-search is subject to the same time check. A
     timeout during the re-search propagates the exception and the existing
     catch/revert handles it (the child move is already on the board;
     `revertToState` restores everything).
   - **Cutoff:** the `alpha >= beta` cutoff after the child loop is
     unchanged. With PVS, the zero-width window makes cutoffs fire earlier
     (which is the point), so the break happens sooner on average.

4. The `getBestMoveQuick(int depth)` overload (fixed-depth, no iterative
   deepening) also benefits from PVS internally but does NOT use aspiration
   windows (there is no previous iteration). This is correct as-is: it calls
   `alphaBeta` once at the given depth, and the PVS restructured child loop
   applies automatically.

#### Invariants that must hold (add assertions in tests)

- The best move returned by the search is identical (modulo shuffle ties)
  to the pre-T13 engine on a fixed seed — PVS and aspiration are pure
  search-pruning techniques; they change which nodes are visited, not the
  game-theoretic value.
- Terminal scores (goal-row wins, stalemates) are unaffected: they are
  returned before the child loop and are not subject to PVS or aspiration.
- The ±9000 forced-win cutoff still fires: a forced-win line found at depth
  D returns |score| > 9000 regardless of the window width.
- The TT stores the same flag (EXACT/LOWER/UPPER) as before for the same
  final score — the flag logic uses `originalAlpha`/`originalBeta` of the
  node, not the window passed to children.

#### Performance guard

- After implementation, run the T7 bench: `--games 10 --budget 2000 --seed 42`
  and `--games 10 --budget 4950 --seed 42` (full board variant, 8+8 per
  side). Acceptance thresholds:
  - avgMaxDepth at 2000 ms budget: **≥ 13** (pre-T13: 13; must not regress;
    target 14).
  - avgMaxDepth at 4950 ms budget (full board): **≥ 10** (pre-T13: ~9;
    target 10–11).
  - avgMsPerMove must not increase by more than 20% vs. pre-T13 (the
    re-search cost must be amortized by the earlier cutoffs).

**Acceptance.** In `src/tests/AspirationPvsTest.java`:

1. **PVS correctness (fixed value):** on a fixed position (seeded, small
   board — 2 Pushers + 1 Pushee per side), the root score returned by
   `getBestMoveQuick(3)` (PVS active) equals the root score from a reference
   full-window alpha-beta on the same position (a test-only copy of the
   pre-PVS child loop, inlined in the test). This pins that PVS does not
   change the game-theoretic value.

2. **PVS re-search fires:** construct a 3-move position where the best-ordered
   child scores below alpha (a quiet move) but the second child (a capture)
   exceeds alpha. Assert that the engine's chosen move is the capture (not
   the quiet best-ordered move) — this only works if the zero-width search
   of the capture triggers the re-search and the re-searched score updates
   alpha.

3. **Aspiration window narrows then widens:** instrument (or spy) the number
   of alpha-beta node visits at depth 2 on a fixed position:
   - full window: N₁ visits
   - aspiration window (score ± 5): N₂ visits, assert N₂ < N₁.
   (If the margin is too tight and triggers a fail-high, the test should
   still pass — the assertion is on the final score, not the visit count;
   make the visit-count check a soft assertion or use a position where the
   true score is well inside the margin.)

4. **Aspiration failure retry:** on a position where the true score is
   `prevScore + 10` (outside a ±5 window), the engine still returns the
   correct score (the retry with a wider window finds it). Use a
   `getBestMoveQuick()` call with a forced 2-iteration sequence (first
   iteration returns a score, second uses aspiration).

5. **Forced-win cutoff unaffected:** a position with a forced win at depth 3
   returns |score| > 9000 with aspiration windows active (the fail-high is
   NOT retried — the cutoff breaks the iteration loop).

6. **TT flag consistency:** on a fixed position, the TT entry stored at the
   root's child after a PVS search has the same flag (EXACT/LOWER/UPPER) as
   the pre-PVS engine would store for the same final score. (Use the
   `--tt-stats` debug hook or a direct TT probe in the test.)

7. **Determinism:** with a fixed seed, the best move chosen on 5 fixed
   positions is identical with T13 on vs. off (add a constructor flag to
   disable aspiration + PVS for the test, analogous to T6's TT disable).

8. Full suite green ×2.

**Verification.**
- T7 bench (both budgets) vs. the pre-T13 baseline recorded in the PR.
- Full-board visual game (`VisualGame --budget 4950`): report the depth
  profile (max depth, median depth, depth at each ply) and compare against
  the pre-T13 run (median 8, max 10, 47 plies).
- Determinism: same seed → identical winners/plies/depths (aspiration and
  PVS do not introduce new randomness).

**Risk.** Medium — PVS re-search + aspiration retry interact with the
existing timeout/revert mechanism. The key danger: an aspiration-failure
retry that times out mid-search must revert cleanly (the board is in a
post-move state from the first attempt). Mitigation: the memento revert is
already proven (T2/T3); the retry reverts to the same pre-iteration
snapshot. The PVS re-search is lower risk: it happens inside the child loop
where the existing timeout already works (a timeout during the re-search
propagates up through the child loop to the catch in `getBestMoveQuick`).

The acceptance test 7 (determinism with T13 off vs. on) is the safety net
for subtle value errors.

**Estimate.** 1.5–2 agent-sessions.

#### Part A Findings (aspiration windows landed; PVS still to come)

- **Implementation:** `getBestMoveQuick()` now iterative-deepens through a new
  shared `searchLevelWithAspiration(level, prevScore, snapshot)` helper: level 1
  is full-width, level 2+ uses `prevScore ± ASPIRATION_MARGIN` (5), and a fail
  (score ≥ β or ≤ α) reverts to the pre-iteration memento and retries with the
  margin doubled (10, 20, …, capped at `MAX_ASPIRATION_MARGIN` = 200, then a full
  window). A forced win/loss (|score| > 9000) is returned without a retry and
  breaks the deepening, exactly as before. `ASPIRATION_MARGIN` /
  `MAX_ASPIRATION_MARGIN` are the two new constants. A 6-arg constructor adds
  `aspirationEnabled` (default on) for the on/off determinism check, analogous to
  T6's TT disable.
- **Test hooks (all in `GameTree`):** `aspirationScoreAtDepth(d)` runs the
  aspiration deepening stopped at depth `d` and returns the root score; 
  `debugSearchScore(d, α, β)` runs a single full/windowed search at depth `d`;
  `getNodeCount()` and `getAspirationRetries()` count node visits and retries.
  The first two share the production `searchLevelWithAspiration`, so the tests
  exercise the real aspiration logic.
- **Why the tests are score-based, not move-based:** the production
  `getBestMoveQuick()` iterative-deepens under the wall clock, so its final depth
  (and any tie between equally-best moves) is not reproducible from a seed — the
  T6-style "same move on/off" check flakes on positions with multiple best moves
  (the forced-win bench lines have several). Instead `AspirationPvsTest` pins
  `aspirationScoreAtDepth(d) == debugSearchScore(d, −∞, +∞)` at each depth (a
  full-window search is exact, and aspiration is exact, so they must agree) — a
  deterministic, tie-free value check — plus dedicated retry, cutoff, narrow-window
  node-reduction, disable-flag, and a production-path-within-budget test. Suite
  green ×2 at 109 tests (103 + 6).
- **Bench, `--games 10 --budget 2000 --seed 42` (2+3-per-side board):**
  `{whiteWins:5, blackWins:5, avgPlies:13.00, avgMaxDepth:13.00,
  avgMsPerMove:~84–92}` — same winners/plies/depth as pre-T13 (depth cannot
  improve: this position's 13-ply forced win caps it), avgMsPerMove up from the
  T6 ~72–75. **The extra time is the ×2-widening at the forced-win level.**
  Instrumented: on the opener the 13-ply win first appears at level 13, whose
  window is centered on level 12's score (−6) and is nowhere near the win score
  (+2³¹); the winning move is not ordered first, so a narrow-window root cuts off
  before reaching it and returns a weak fail-high bound (== β). The engine then
  doubles 5→200 (7 retries, each a full level-13 re-search, +63% nodes) before the
  full window finds the win. This is inherent to the plan's widening whenever a
  forced win first appears at a deep level.
- **Full board (8 Pushers + 8 Pushees per side, 4950 ms) — the actual T13 target:**
  aspiration raises max depth **8 → 9** (avgDepth 8.0 → 8.2) with **no** ms-per-move
  cost (both exhaust the budget) and only ~1 retry (no quick forced win to trip the
  widening). So on the position T13 is designed for, Part A is a clean depth gain.
- **Guard status:** 2000 ms avgMaxDepth 13 (meets ≥ 13, no regression); full-board
  4950 ms depth 9 (pre-T13 ~9; the 10–11 target is for the complete T13 with PVS).
  The 2000 ms avgMsPerMove exceeds the 20% band only on the forced-win bench line
  (the widening artifact above); on the full board it is flat. Part B (PVS) is the
  expected source of the remaining depth and of keeping the ms-per-move in band.

#### Part B Findings (PVS landed; T13 complete)

- **Implementation:** the `alphaBeta` child loop (the 6-argument overload) is now
  PVS: the first child (the best-ordered move — the TT best move, or the first
  shuffled move at level 1) is searched with the full window; every later child
  with a zero-width null window (max node: `(alpha, alpha + 1)`, min node:
  `(beta - 1, beta)`); a violation (score > alpha at a max node, score < beta at
  a min node) triggers the immediate full-window fail-high re-search, which
  reuses the already-applied move (the null-window search undid all of its own
  descendants) and whose RE-SEARCHED score is the one used by the unchanged
  update/cutoff below. Quiescence (no child loop at depth 0), the repetition
  penalty, the time check, and the TT store (final alpha/beta after any
  re-search) are untouched. A `pvsEnabled` flag (default on) + 7-argument
  constructor for the determinism check, analogous to T6's TT disable; hooks:
  `getPvsResearched()` (fail-high re-search count) and `probeTT(hash, turn)`
  (direct TT probe for the flag-consistency test).
- **Why the move-identity test is restricted to unique-best-move positions:**
  PVS changes which nodes are visited, hence when each node's shuffle consumes
  the seeded `Random`; equally-valued moves can be chosen differently on/off
  (defaultStart at depth 3 picks a different tied move per seed, on AND off).
  So acceptance 7 runs five positions whose depth-3 best move is unique in
  value (verified across 5 seeds × on/off); score equality (acceptance 1) is
  unrestricted and holds on every position, including the tied ones.
- **Acceptance 2's literal scenario (quiet first child, capture second) cannot
  occur at a fresh root under T6's ordering** (captures are always ordered
  before quiet moves). The 'captureRace' position realizes the essential case:
  the best-ordered child (the MVV-LVA capture C5D6, worth ~2) is NOT the best
  move; the true best move is the other capture (E6D7, a depth-3 forced win)
  ordered second, found only via the null-window violation + re-search. Pinned:
  chosen move == the pre-PVS engine's, is a capture, and the re-search count is
  > 0.
- **Bench, `--games 10 --budget 2000 --seed 42`:**
  `{whiteWins:5, blackWins:5, avgPlies:13.00, avgMaxDepth:13.00,
  avgMsPerMove:~80}` vs. Part A's ~92: **avgMsPerMove −13%** (the forced-win
  level's widening retries got cheaper: each doubled-margin retry is now a
  PVS-pruned search). Same winners/plies/depth — the bench's game-theoretic
  content is unchanged. The 14-depth target is unreachable on this board (the
  13-ply forced win caps it, as in Part A). Determinism: two same-seed runs are
  identical (winners/plies/depths).
- **Full board (8 Pushers + 8 Pushees per side, 4950 ms, VisualGame seed 42):**
  max depth 9 in both post-PVS runs — the observed band is 9–10 (pre-T13 ~9,
  Part A 9, the pre-Part B baseline run 10): **no regression, and no clean
  step to the 10–11 target.** Median depth 8 (unchanged). Games ended sooner:
  48 and 43 plies (pre-Part B run: 56), and one post-PVS run was won by WHITE
  (the others BLACK) — consistent with the pre-existing wall-clock sensitivity
  of the iterative-deepening stop (the final interrupted level varies run to
  run, pre-T13 as well), not new randomness.
- **Node counts (TT off, full window, depth 4):** full board 1560 → 1447 (−7%).
  On the small 7-piece capture position PVS is SLOWER (d3: 61 → 76, d4: 179 →
  279): with small branching and many near-PV siblings, the re-search overhead
  outweighs the null-window savings. PVS pays exactly where the plan expected
  (bigger branching, deeper levels, the aspiration retry path) — hence the
  ms-per-move win on the forced-win bench line despite no full-board depth
  step.
- **TT flags:** the root's child stores the same flag/score/depth (EXACT/−8/2
  on 'tactical' at depth 3) with and without PVS: null-window searches store
  only bounds, and the re-search (when it fires) replaces them with the exact
  value, so the final store matches the pre-PVS one (pinned via `probeTT`).
- **Interaction with Part A:** with PVS active, a narrow aspiration window no
  longer reduces node count on the full board (full and narrow both hit 1447
  at depth 4 — the null windows already prune), so Part A's narrow-window node
  test now runs with PVS off on both trees (isolating the aspiration effect),
  and its exact-value reference is now the true pre-T13 engine (aspiration and
  PVS both off).
- **Suite:** 109 → 114 (+5 Part B tests: exact value at every depth on 7
  positions, re-search fires, node reduction, TT flag consistency, on/off
  determinism). Green ×2.

---

### T14 — Search-stack audit (targeted review)

**Goal.** Before any further strength work, confirm the search foundation
(TT + iterative deepening + quiescence + PVS + aspiration + repetition) is
sound, and identify what actually limits full-board depth (stuck at median 8,
max 9 since T6). This is a *focused* audit — a numbered checklist where each
item gets a verdict (VERIFIED-FINE / BUG-FIXED / NOTE) backed by evidence —
not a rewrite. Fix what is a bug; record the rest. The top suspect for the
depth plateau is a concrete weakness found while implementing T13, listed
first.

**Depends on:** T13 (merged).

**Scope.** `src/client/GameTree.java`, `src/client/TranspositionTable.java`
(read-mostly; fixes only where a verdict is BUG), new/extended tests in
`src/tests/TranspositionTableTest.java` and `src/tests/GameTreeTest.java`, and
a **T14 audit findings** section in this file. No behavior change for any item
verdicted VERIFIED-FINE.

**Checklist** (keep this order — items 1–2 are the depth-plateau suspects):

1. **TT best-move handoff is missing at internal nodes (prime suspect).**
   In `alphaBeta`, `ttOrderMove` is used for *ordering* at internal nodes only
   when `ttEntry.depth >= depth`; during iterative deepening the previous
   level's entry at that node is one depth shallower, so the standard
   "previous level's best move" handoff exists at the ROOT only (which uses
   `ttEntry.depth < depth`) and not below it. Levels 2+ ordering below the
   root rides on same-depth transpositions and cross-move reuse alone. Measure
   it: on the full board, count internal nodes whose ordered first move comes
   from a TT entry under the current gate vs. a relaxed gate
   (`ttEntry != null && ttEntry.bestMove != null`), depths 6–9 (a temporary
   diagnostic counter or an instrumented run). If the relaxed gate
   measurably cuts nodes, implement it here (ordering only — it never changes
   values; pin that with a score-invariance test on/off) and note in T16's
   scope that part (a) is already done. The score-RETURN gate (`usable()` +
   `depth >= depth`) must stay strict in all cases — that one is correctness.
2. **TT soundness edges.** (a) An EXACT entry stored at a *deeper* search is
   returned at a shallower one (`usable()` + `depth >=`): the standard TT
   horizon approximation, but measure whether it hurts here — full-board bench
   with a "same-depth-only" probe variant vs. current: report depth/node
   deltas. (b) Direct-mapped collisions: the turn-number guard bounds the
   64-bit-truncation error; measure the collision rate over a full-board game
   (an optional probe counter suffices). (c) "Deeper entry wins" replacement:
   confirm the comparison is on stored depth (sound) and cannot oscillate or
   evict a deeper entry.
3. **±9000 forced-win bound margin.** Compute the worst-case static score
   reachable on the full board (8 Pushers + 8 Pushees per side, every T4 term:
   READY_TO_WIN = 400, capture-threat, PUSHEE_SHIELD = 20, mobility/2, base
   value + advancement + DEFENSIVE_PREMIUM) — by a maximally-loaded position
   or by analysis. `getBestMoveQuick()` stops iterative deepening when the root
   score exceeds ±9000 treating it as a forced win, so a realistic static
   score near that bound would be a latent bug. If the margin is thin, lower a
   term weight or raise the constant, and add a test pinning
   "max achievable static score < 9000" with documented headroom. Confirm the
   quiescence stand-pat path cannot exceed the same bound.
4. **PVS × TT interaction.** T13 Part B pins the root-child's stored flag; extend
   to a fuzz: on 20 seeded random small positions, run a full-window depth-4
   search with an EMPTY table, then re-run the same position/depth with the
   table still DIRTY from the first run — root scores must match (dirty-table
   reuse is sound). Repeat the second pass with PVS off. Catches any unsound
   probe path through bound entries stored by null-window searches.
5. **Quiescence bound.** `Q_MAX_DEPTH = 8`: measure how often the
   `qDepth >= Q_MAX_DEPTH` exit binds in a full-board game (a counter). If it
   binds often, raising it is cheap (average capture branching ~1–2) — gate on
   no ms-per-move regression; otherwise NOTE.
6. **Wall-clock level start.** Iterative deepening starts every level and
   aborts the last on timeout; the final completed level varies run to run
   (observed ±1 full-board depth jitter and a winner flip between same-seed
   runs). Evaluate "start a level only if the previous one took less than half
   the remaining budget" vs. the current: three same-seed full-board runs,
   compare depth-profile variance and average max depth. If it cuts variance
   without losing average depth, implement it (the timeout/revert path stays as
   the backstop); otherwise record as accepted behavior.
7. **Repetition penalty × PVS corner.** At a max root with a narrow aspiration
   window, a penalized fail-high child records `beta − REPETITION_PENALTY`
   (a bound, not the exact value) when the child's value ≥ the root's beta.
   Pre-existing (the same formula ran pre-PVS), root-only, magnitude ≤ 5. Expect
   a NOTE (no fix) — record the reasoning so it is not "fixed" later.
8. **Board-state discipline.** BugProofTest pins move/undo + revert. Confirm no
   new dirty-board path exists: the PVS re-search performs no extra board ops
   (the null-window search undid its own descendants), and the aspiration retry
   reverts to the same pre-iteration snapshot. Expected VERIFIED-FINE.

**Acceptance.**
1. A **T14 audit findings** section in this file: one subsection per checklist
   item, each with its verdict and evidence (a measured number or a test name).
   No item may be "verified" without one.
2. Every BUG verdict ships with a regression test that fails before the fix
   and passes after (BugProofTest or the relevant test class).
3. Full suite green ×2. The 2000 ms bench (`--games 10 --budget 2000
   --seed 42`) reports unchanged winners/plies/depth (5/5, 13, 13) unless a
   fix intentionally changes speed, in which case the delta is reported.

**Verification.** `./build.sh test` ×2; the 2000 ms bench command above.

**Risk.** Low — mostly measurement and documentation; fixes are isolated and
  test-gated. **Estimate.** 1 session.

#### T14 Audit Findings

Verdicts, in checklist order: **1 — BUG-FIXED; 2a/2b/2c, 3, 4, 8 —
VERIFIED-FINE; 5, 6, 7 — NOTE.** One subsection per item, each with its
evidence (a measured number or a test name), as the acceptance requires.

**Setup for the measurements.** "Full board" = 32-piece start (8 Pushers +
8 Pushees per side), 4950 ms budget, seed 42. The ID measurements
(items 1, 2a) ran a single tree per variant, full window, TT + PVS on,
`debugSearchScore(d, −∞, +∞)` at depths 1..9 (each call seeded from the
same seed, so the two variants are apples-to-apples). Full-board games
(items 2b, 5, 6) played the production path (`getBestMoveQuick`, aspiration
+ PVS on) to completion, 3 runs per variant.

**Item 1 — TT best-move handoff at internal nodes: BUG-FIXED.**

Measured on the full board (seed 42), per-level node counts, current gate
(`entry.depth >= depth`) vs. relaxed gate (`bestMove != null`):

| depth | current | relaxed | delta |
|---|---|---|---|
| 6 | 25,521 | 19,740 | −23% |
| 7 | 207,612 | 206,175 | −1% |
| 8 | 411,282 | 254,498 | −38% |
| 9 | 3,616,537 | 1,909,140 | **−47%** |

Scores identical at every depth (−30, −31, −30, −31, −30, −31, −29, −30,
−29 — value invariance holds in the measurement itself). The `ttOrderHits`
counter (internal nodes whose ordered first move came from a TT entry) shows
the mechanism: at depth 9 the current gate hands a TT first move to 1,025
internal nodes (cross-path same-depth entries only) vs. 90,183 under the
relaxed gate (the previous level's best move — exactly the missing standard
ID handoff, since at a node the previous level's entry is one depth
shallower than the probe). The score-RETURN gate stayed strict in both.

Implemented as the `ttMoveHandoff` flag (default ON; the 8-argument public
constructor exposes it). The root keeps its strictly-shallower rule. Pinned:
`ttMoveHandoffIsOrderingOnlyScoresUnchanged` (6 positions × depths 2–5, on
vs. off, identical scores) and `ttMoveHandoffReducesNodesOnFullBoard`
(depth-8 node count strictly smaller with the handoff). **T16's scope note:
part (a) — TT handoff at internal nodes from any stored depth — is DONE**;
T16 is (b) goal proximity plus the acceptance gate.

Production effect: 2000 ms bench avgMsPerMove ~80 → **46.8 (−41%)** with
winners/plies/depth unchanged (5/5, 13, 13 — the forced-win level's
widening retries got cheaper). Full-board same-seed games: average completed
depth per move 7.40–8.54 (pre-fix) → 8.21–8.51 (post-fix), and the early/
mid-game profile shifts from mostly 8s to mostly 9s.

Implementation note (caught live, pinned): adding the 8-argument public
constructor next to the 9-argument private one made the existing
8-argument `this(...)` delegations silently bind to the PUBLIC overload and
shift every flag (aspiration off became on, TT on became off — two tests
failed). All delegations now pass all nine arguments, and
`constructorFlagsAreWiredToTheirDocuments` pins each public constructor's
flags against observable behavior (TT probes / aspiration retries / PVS
re-searches) so a future overload-shape change fails there instead of in a
silent behavior flip.

**Item 2 — TT soundness edges: VERIFIED-FINE (a, b, c).**

- **(a) Deeper-returned-at-shallower (the TT horizon approximation):
unreachable in this engine, measured identical.** The "same-depth-only"
  probe variant (`entry.depth == depth` for score return) produced a
  BYTE-IDENTICAL node/probe/hit profile at depths 1–9 on the full board.
  The reason is structural, not luck: a node's probe depth is determined by
  its ply (root depth − ply), so within a single search no stored entry can
  be deeper than the probe (entries from earlier iterations are one ply
  shallower; aspiration retries re-search the same depth); across searches
  the turn-number guard rejects every entry (turn numbers are strictly
  monotonic within a game, and a cycle back to the same arrangement changes
  the turn). The `depth >=` gate is therefore behaviorally `==` today, and
  the standard horizon-approximation error cannot occur. No change made
  (the gate stays `>=`: it is the standard, and a future feature that does
  store deeper-than-probed entries — e.g. a multi-search TT — gets the
  sound behavior for free).
- **(b) Direct-mapped collisions:** the turn guard's catch rate over nine
  full-board games is ≤ 0.0041% of probes (47,327 / 1,159,802,080 worst
  run; most runs ≤ 0.001%) — `turnGuardMisses()` / `probes()` (new bench
  hooks). With a full 64-bit key the remaining error is bounded as designed
  (a key match is the key; a mismatch on turn is a correct miss). Negligible
  and harmless: VERIFIED-FINE.
- **(c) Replacement:** the comparison is on STORED depth only
  (`depths[i] > depth → keep`): a deeper entry is never evicted by a
  shallower store, and an equal-depth store replaces (last write wins —
  deterministic, no oscillation). Pinned by the pre-existing
  `deeperEntryWins` plus the new
  `replacementComparesOnStoredDepthAndNeverOscillates` (equal-depth replace,
  deeper-takes-over, survival under repeated shallower stores).

**Item 3 — ±9000 forced-win bound margin: VERIFIED-FINE (5808 headroom).**

Worst case measured on a maximally loaded full-board position (every T4 term
at once, White's view): all 8 White Pushees on row 6 each with a mirror
Pusher behind (READY_TO_WIN × 8 = 3200 — the dominant term), every White
piece maximally advanced (own base 150: 8 Pushers at 7+5, 6 non-edge Pushees
at 3+6), Black's 16 pieces in the two rows of least advancement that fit
(penalty 168: 8 Pushers at 7+3+2, 8 Pushees at 3+4+2), White mobility 20/2
= 10, no capture threat, no shield (Black has no ready Pushee):
**150 − 168 + 10 + 3200 = 3192**, leaving **9000 − 3192 = 5808 (~64%)** of
headroom. A loose analytic bound (all terms at geometrically impossible
simultaneous maxima) lands at ~3800 — still 57% under. The quiescence
stand-pat path is this SAME `GameAnalyzer.analyze` call, so it cannot exceed
the same bound; a q-node exceeds 9000 only through a capture line into a
terminal win (~±2³¹), which is the forced-win signal the cutoff exists to
catch. Pinned by `maxStaticScoreStaysBelowForcedWinBound` (the exact 3192
and the < 9000 bound, both colors' views). No weight or constant change
needed.

**Item 4 — PVS × TT dirty-table reuse: VERIFIED-FINE (fuzz clean).**

`pvsXttDirtyTableReuseIsSound`: 20 seeded random small positions (2 Pushers
+ 1 Pushee per side, random squares in rows 1–6, random side to move, seeds
42–61). Each position: pass 1 = full-window depth-4 search with a fresh
table; pass 2 = same position/depth, same table still dirty, PVS on; pass 3
= the same dirty table probed by a PVS-off tree (the table is shared via the
new `setTranspositionTable` test hook). All 20 × 3 root scores agree — no
unsound probe path through the UPPER/LOWER entries the null-window searches
store. The board state is asserted exact after every pass (see item 8).

**Item 5 — Q_MAX_DEPTH = 8 quiescence cap: NOTE (never really binds; no
change).**

The cap's exit fired on **0.003%–0.067%** of q-nodes across the nine
full-board games (e.g. 120,583 / 179,280,739; 4,255 / 138,085,704; counted
by the new `getQBoundHits()` / `getQNodeCount()` hooks). Eight CONSECUTIVE
capture plies are a vanishingly rare event in this game (average capture
branching ~1–2, as T3 noted), so the bounded extension is costing nothing:
raising the cap would buy ~0.05% of q-nodes a little more depth at a real
ms-per-move risk. Accepted as-is.

**Item 6 — wall-clock level start: NOTE (measured; the half-budget rule does
NOT win; accepted behavior).**

Three same-seed (42) full-board 4950 ms runs per variant, on the post-item-1
engine (so the only difference is the level-start rule):

| variant | winners | maxDepth | avgDepth (per move) |
|---|---|---|---|
| current (start any level within budget) | BLACK, WHITE, WHITE | 10, 10, 11 | 8.21, 8.25, 8.51 |
| half-budget (start only if previous level < ½ remaining) | WHITE, WHITE, BLACK | 11, 10, 10 | 8.56, 8.11, 8.34 |

Mean max depth: 10.33 = 10.33. Mean avgDepth: 8.32 vs 8.34 (within noise).
The same-seed winner flip is UNCHANGED (one BLACK in three either way), and
the per-run avgDepth spread is actually slightly larger with the rule
(8.11–8.56 vs 8.21–8.51). The flip is not the interrupted level varying in
isolation — it is the accumulated effect of the interrupted level on the
move CHOSEN, which then diverges the game; starting levels more
conservatively does not make the choice more deterministic, it only removes
a level that sometimes would have completed. Per the checklist's gate
("if it cuts variance without losing average depth, implement it; otherwise
record as accepted behavior"): **recorded as accepted behavior, not
implemented** — the timeout/revert path stays the sole backstop, and
`getBestMoveQuick` is byte-identical to pre-audit. (The measurement hook was
removed after the runs.)

**Item 7 — repetition penalty × PVS corner: NOTE (no fix; the unsound value
is never consumed).**

The corner is real: at a max root in a narrow aspiration window, a penalized
fail-high child returns `beta − REPETITION_PENALTY` (the child search itself
returns the bound `beta`, then the root subtracts 5). The root then stores
itself in the TT with flag EXACT at `beta − 5` — an underestimate of the
true value (≥ beta) by at least 5. Why this is safe and needs no fix:

1. **Never consumed as a value.** A root's stored score is never returned:
   the score-return gate requires `!isRoot`. The same (hash, turn) cannot
   reappear as an INTERNAL node — the turn number is strictly monotonic
   within a search, and a cycle back to the same arrangement changes the
   turn, which the guard rejects. The only consumer of the root entry is the
   NEXT iterative-deepening level's ordering (the `depth <` rule at the root
   — a best-MOVE, not a score).
2. **Self-correcting within the move.** The returned (penalized) score
   becomes `prevScore`; if the true root value exceeds the next level's
   narrow window, the standard aspiration fail-high retry widens and recovers
   it (or the |score| > 9000 forced-win path returns it without a retry).
3. **Pre-existing and bounded.** The identical formula ran pre-PVS (the
   penalty is applied to whatever the child search returns, bound or exact);
   it is root-only by design (T10), and the magnitude is exactly
   REPETITION_PENALTY = 5 — noise next to any real margin, and never a
   forced win/loss (pinned by RepetitionTest). Recording it here so it is
   not "fixed" later into something that changes the pinned shuffle-breaking
   behavior.

**Item 8 — board-state discipline: VERIFIED-FINE (no new dirty-board path).**

Walkthrough: (a) the PVS re-search performs NO extra board ops — the
null-window search undid all of its own descendants, the re-search applies
and undoes its own, and the loop's single `undo(move)` runs once in both
paths; (b) the aspiration retry reverts to the SAME pre-iteration memento
snapshot each time (taken once, before the level loop). Pinned by
`boardStateIsExactAfterResearchedAndRetriedSearches`: after a search that
actually performed PVS re-searches (`getPvsResearched() > 0`), after one that
performed aspiration retries (`getAspirationRetries() > 0`), and after a
production `getBestMoveQuick()`, the board's Zobrist key, side to move, turn
number and legal-move SET are exactly the pre-search state.

Side note (pre-existing, verified NOT a T13 regression): the AGGREGATE
legal-move list's order can change across move/undo pairs (same set, e.g.
a piece's move re-appended after the search — reproduced on the pre-T13
engine with TT/aspiration/PVS all off). The invariant (engine invariant 3)
is the cached SET per piece, and the search shuffles the non-TT,
non-capture moves regardless, so behavior is unaffected; the test therefore
asserts set + size, not order.

**Acceptance status.** Findings: this section (8 items, verdict + evidence
each). BUG verdicts: item 1 ships with regression tests that fail before
the fix (`ttMoveHandoffIsOrderingOnlyScoresUnchanged` with handoff off vs. on
pins the value both ways; `ttMoveHandoffReducesNodesOnFullBoard` pins the
speed; both run against the flag the fix introduced, and the pre-fix gate is
the flag-off path). Full suite green ×2 at 121 tests. 2000 ms bench
(`--games 10 --budget 2000 --seed 42`): `{whiteWins:5, blackWins:5,
avgPlies:13.00, avgMaxDepth:13.00, avgMsPerMove:46.80}` — unchanged
winners/plies/depth (5/5, 13, 13) vs. T13's ~80 ms/move; the delta is the
intentional speed change from the item-1 fix (reported above).

---

### T15 — A/B strength harness (measuring engine strength)

**Goal.** Make strength measurable. Mirror self-play (Bench, VisualGame) cannot
separate strength from a draw: two identical engines always split, so "depth 9
vs 9" and "the winner flips" prove nothing about progress. This adds mixed
play to Bench — the current engine against a reference engine with features
switched off — and reports win rates usable as the acceptance gate for T16 and
beyond (and, retroactively, for the T3/T4/T6/T13 contributions).

**Depends on:** T13. (Independent of T14 — can run in parallel.)

**Scope.** `src/client/Bench.java` (mixed-play mode + flag plumbing),
`src/client/GameTree.java` and `src/client/GameAnalyzer.java` (feature
off-flags only — all default ON so current behavior is bit-identical). No
protocol changes.

**Requirements.**

1. **Feature off-flags on GameTree** (constructor chain, every one defaulting
   to on; all existing constructors keep working and every existing test must
   pass unchanged):
   - `quiescenceEnabled` (T3): when off, depth-0 nodes return the stand-pat
     static score directly (pre-T3 behavior) instead of entering `quiescence`.
   - `threatTermsEnabled` (T4): when off, the `GameAnalyzer` evaluation skips
     `threatTerms` (plumb the flag from GameTree to the eval call; both
     strategies must honor it, and T8's mirror invariant must hold with the
     term both on and off).
   - `repetitionEnabled` (T10): when off, no history append and no root
     penalty.
   - (TT / aspiration / PVS off-flags already exist from T6/T13.)
2. **Engine levels.** Bench `--engine LEVEL` selects a monotone feature stack
   in merge order:
   `t2` = base (no quiescence, threat terms, TT, repetition, aspiration, PVS);
   `t3` = t2 + quiescence; `t4` = t3 + threat terms; `t6` = t4 + TT;
   `t10` = t6 + repetition; `t13` = t10 + aspiration + PVS (= current);
   `current` is an alias for `t13`. An unknown level is a usage error.
3. **Mixed mode.** `--ab LEVEL_A LEVEL_B --games N --budget MS --seed S
   [--small]`: default board is the full 8+8 start; `--small` selects the 2+3
   bench board. Play N games, ALTERNATING which engine takes each color (so a
   first-move advantage cancels — it is real: the starter force-wins the 2+3
   board in 13 plies). Print the existing per-game line plus one JSON summary
   line: `{"games":N,"aWins":n,"bWins":n,"aWinsAsWhite":n,"aWinsAsBlack":n,
   "truncated":n,"avgPlies":x,"avgMaxDepthA":x,"avgMaxDepthB":x}` (both
   engines report depth).
4. **Flag-correctness tests** (BenchTest or a new EngineLevelTest):
   - Value identity across speed-only features: on fixed positions + seed, the
     root score of `t13` with aspiration+PVS off equals `t10`'s equals `t6`'
     (TT/aspiration/PVS never change the value, only speed).
   - Eval floor: with threat terms off, the `GameAnalyzer` score on a fixed
     position equals (value + advancement + DEFENSIVE_PREMIUM + ownMobility/2)
     by direct formula — pins exactly what `t2`/`t3` evaluate.
   - Determinism: same level + seed → identical per-game lines across two
     `--ab X X` runs.
   - Mirror with the term off: add a threat-terms-off case to
     EvalSymmetryTest (T8's invariant must survive the flag).
5. **Baseline measurements** (recorded in findings). Full board, 4950 ms,
   seed 42, 20 games each: `--ab t13 t6` (isolates T13: aspiration + PVS) and
   `--ab t13 t4` (isolates T6 + T10 + T13). Report the splits. This is the
   first real strength measurement of the features that landed since T4.

**Acceptance.**
1. `--ab t13 t13` (mirror) reproduces mirror behavior: a ~50/50 split and a
   depth profile matching VisualGame (median 8, max ~9).
2. The flag-correctness tests are green and the full suite is green ×2
   (defaults on ⇒ zero behavior change is the regression net).
3. The baseline numbers are recorded in the plan's findings with the sample-
   size caveat: ≤20 games per matchup is indicative (≈±10 pp noise at 20
   games); a delta under 10 pp is not a conclusion.

**Verification.** `./build.sh test` ×2; the two baseline `--ab` runs; report.

**Risk.** Low — additive; defaults preserve current behavior exactly. **Estimate.**
1 session.

---

### T16 — Move ordering: TT handoff + goal proximity

**Goal.** Attack the full-board depth plateau from the ordering side — the
strongest remaining lever. PVS and aspiration narrow the window, but pruning
only pays when the best move is searched first: internal nodes do not inherit
the previous level's best move (T14 item 1) and quiet moves are a pure shuffle.
T16 lands (a) TT best-move ordering at internal nodes from ANY stored depth
and (b) goal-proximity ordering of quiet moves. (If T14 already implemented
(a) per its checklist, T16 is (b) plus the acceptance gate.)

**Depends on:** T14 (verdicts on items 1–2), T15 (acceptance uses the A/B
harness).

**Scope.** `src/client/GameTree.java` (the `ttOrderMove` selection +
`orderMoves`), a new `src/tests/MoveOrderingTest.java`. No eval or protocol
changes.

**Requirements.**

1. **TT best-move handoff at internal nodes.** Use the TT best move for
   ORDERING whenever an entry exists (any stored depth, non-null bestMove,
   still legal). Ordering is a speed heuristic and never changes the value.
   The ROOT keeps its current rule (strictly-shallower only: a same-depth or
   deeper root entry would make root ties sticky across moves — pinned
   behavior). The score-return gate (`usable()` + `depth >=`) is unchanged.
   *(Status: T14 implemented (a) per its checklist — see the T14 findings,
   item 1: the `ttMoveHandoff` flag, default ON, pinned by
   `ttMoveHandoffIsOrderingOnlyScoresUnchanged` +
   `ttMoveHandoffReducesNodesOnFullBoard`. T16 does not re-land (a); its
   scope is (b) goal proximity plus the acceptance gate, and its
   instrumented handoff count (acceptance 3) reuses the existing
   `getTtOrderHits()` hook.)*
2. **Goal-proximity ordering of quiet moves.** In `orderMoves`, replace
   `Collections.shuffle(rest, random)` with a bucket sort by a proximity key,
   then shuffle WITHIN each bucket (the non-determinism pinned by
   `testRandomnessForMoreThanOneMove` must survive — its two quiet moves are
   equidistant, so they share a bucket and still shuffle). Proximity key for a
   quiet move by the side to move: rows from the DESTINATION row to the
   opponent's goal row (fewer rows first — a pusher one row from the goal row
   before one two rows from). Pushers and Pushees are keyed alike (a Pushee's
   advance matters equally; its legality already requires a friendly Pusher
   behind). Captures keep their existing priority (MVV-LVA before quiet).
3. **Flag + tests.** An `orderingEnabled` constructor flag (default on; off =
   exactly the pre-T16 behavior). In MoveOrderingTest:
   - Score invariance: five fixed positions × depths {2,3,4}: ordering on vs.
     off return identical root scores (a full window is exact either way).
   - Node reduction (the performance pin): full board at depth 4, TT off AND
     TT on — ordering on visits strictly fewer nodes than off at the same
     score.
   - The shuffle pin stays green (testRandomnessForMoreThanOneMove).
   - The ID handoff fires: on a full-board position, an instrumented count of
     internal nodes whose ordered first move came from a TT entry is > 0 at
     levels 2–4 (with the current gate it is ~0 below the root).
4. **Acceptance gates** (via T15 + the T7 bench):
   - Full board 4950 ms (VisualGame, seed 42, three runs): median depth ≥ 8
     (no regression), max ≥ 9; target median 9 / max 10–11.
   - 2000 ms small-board bench (`--games 10 --budget 2000 --seed 42`): 5/5,
     13 plies, depth 13 unchanged; avgMsPerMove within noise of the current
     ~80 (T13 Part B).
   - **Strength gate:** `--ab t16 t13 --games 20 --budget 4950 --seed 42`
     (full board): t16 wins ≥ 55% (indicative, per T15's sample-size caveat);
     record the actual split.

**Verification.** `./build.sh test` ×2; three full-board VisualGame runs; the
2000 ms bench; the `--ab` strength gate; a findings section in the plan with
the numbers and an ablation (handoff-only vs proximity-only node counts) so
the record shows which ordering piece did the work.

**Risk.** Medium — ordering interacts with the pinned shuffle behavior (the
bucket shuffle preserves it) and with TT entry quality (early levels order by
stale, shallow best moves; the proximity key must be exact: direction-aware
row arithmetic, edge files). **Estimate.** 1–1.5 sessions.

---

## Suggested execution order

```
T1 ─┬─► T7 (baseline) ─► T3 ─► T4 ─► T8 ─► T6 ─► T13
    │
T2 ─┴──────────────────► T9
                          T10 (anytime after T1)
T5: do first among engine tasks (0.5 session, removes guesswork for T3/T4)
T11: wire-format follow-up — needs T5's answers and T9's payload plumbing
T12: owner follow-up — restore the original wire behavior (H8-first,
     no framing); reverts T11's flip + T9's framing
T13: after T6 (needs TT + move ordering for PVS to be effective) — MERGED
T14 (audit)  ┐ independent of each other; both after T13, run in parallel
T15 (A/B)    ┘
T16: after T14 + T15 (T14's verdicts on the plateau suspects; T15's
     harness is T16's strength gate)
```

**Deliberately not planned** (and why):
- **Pseudo-legal move generation** — the board is tiny; not a bottleneck.
- **ML/NN evaluation** — no training data, overkill for 8x8 Pusher; the
  handcrafted threat terms (T4) cover the motif that matters.
- **Multi-PV / search parallelism** — the socket budget is one move per ~5 s;
  single-threaded depth is the right currency.
- **Null-move pruning** — every ply advances a piece exactly one row toward
  its goal (docs/RULES.md item 7); skipping a ply would break the strict
  potential-decrease invariant and allow positions the rules say are
  unreachable. The game's structure makes this unsound.
- **Endgame tablebases** — the game is provably finite (≤ ~70 plies from
  the standard start) and the forced-win cutoff already handles endgame
  detection; a tablebase would add complexity for marginal gain in a space
  that's already solved by the ±9000 cutoff.
