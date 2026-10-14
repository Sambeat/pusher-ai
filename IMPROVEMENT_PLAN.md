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
# Expected: OK (92 tests). GameTreeTest takes ~5 s (real time-limited searches);
# PusherApplicationTest drives full-budget searches (a few seconds).
# (Count history: 42 at T1, +20 from T5's RulesContractTest, +2 from T7's
# BenchTest, +3 from T3's QuiescenceTest, +14 from T4's ThreatEvalTest,
# +7 from T2: 4 PusherApplicationTest + 3 BugProofTest regression tests,
# +4 from T8: 3 EvalSymmetryTest + 1 ThreatEvalTest mirror test.)
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

---

## Suggested execution order

```
T1 ─┬─► T7 (baseline) ─► T3 ─► T4 ─► T8 ─► T6
    │
T2 ─┴──────────────────► T9
                          T10 (anytime after T1)
T5: do first among engine tasks (0.5 session, removes guesswork for T3/T4)
```

**Deliberately not planned** (and why):
- **Aspiration windows** — marginal gain in this branching factor, real
  complexity; revisit only if T6's depth gains disappoint.
- **Pseudo-legal move generation** — the board is tiny; not a bottleneck.
- **ML/NN evaluation** — no training data, overkill for 8x8 Pusher; the
  handcrafted threat terms (T4) cover the motif that matters.
- **Multi-PV / search parallelism** — the socket budget is one move per ~5 s;
  single-threaded depth is the right currency.
