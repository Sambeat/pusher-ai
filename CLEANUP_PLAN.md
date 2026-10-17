# Pusher-AI Code Cleanup Plan

This is a **new, separate plan** from `IMPROVEMENT_PLAN.md` (tasks T1–T16, all merged,
engine work). That file is history; this file is the code-cleanup track.

Each task below is an **independent work order**: pick any single task using only this
file + the repository, no follow-up questions. Every task is based on `main` and must
leave the full suite green:

```bash
./build.sh test
# Expected: JUnit 5 console summary with [ 135 tests successful ] and
#           [ 0 tests failed ] (C10 moved the runner from JUnit 4's
#           "OK (135 tests)" line to the Jupiter console launcher)
```

`tests.BugProofTest` is the regression guard (inherited from the T-plan). A task that
breaks a BugProofTest test is wrong by definition.

**The one rule that overrides everything else: no behavior changes.** Search results,
timings, the wire protocol, move strings, board rendering, and stdout of `Bench` must
all stay byte-identical (or, for the two tasks that explicitly say otherwise, changed
only as stated). These are pinned:

1. **Wire protocol (server-confirmed, T11/T12):** 4-char moves ("D2E3"), 64-int board
   payload in H8-first order, **no framing**, one `input.available()`-based read per
   payload. Do not "fix" the `available()` reads.
2. **Determinism:** `TranspositionTable.ZOBRIST_SEED` and `GameTree`'s
   seed + golden-ratio `nextRandom()` derivation must stay exactly as they are.
   Seeded `new Random(seed)` stays; only *unseeded* `new Random()` may change (C9).
3. **Engine invariants (T-plan):** `|score| < 9000` bound; singleton `GameBoard` with
   cached legal-move lists kept consistent by `movePiece`/`undo`/`revertToState`;
   `createMemento`/`revertToState` exactness.
4. **Test count 135** is the default acceptance bar (the two tasks that delete
   dead-code unit tests state their new count explicitly).

## Repository layout (for orientation)

- `src/game/` — board model: `GameBoard` (singleton 8×8), `Piece` (abstract) with
  `Pusher`/`Pushee`, `BoardSquare`, `Move`, `Player`, `Columns`.
- `src/client/` — engine + app: `GameTree` (1218 lines, alpha-beta + TT/PVS/
  aspiration/quiescence/repetition/ordering flags), `WhiteStrategy`/`BlackStrategy`
  (both just delegate to `GameAnalyzer.analyzeFor`), `GameStep` (memento),
  `TranspositionTable`, `GameAnalyzer`, `PusherApplication` (socket client),
  `Bench` + `VisualGame` (self-play harnesses).
- `src/tests/` — JUnit 4, 18 test files, 135 tests, ~9.7k lines total in the repo.
- Build: plain `javac` via `build.sh` (no Maven/Gradle), pinned JUnit 4.13.2 +
  Hamcrest 1.3 jars in `lib/`.

## Why a cleanup is due (measured state, July 2026)

- 0 uses of `var`, 0 streams, 0 `Optional`, 0 `List.of`, 0 text blocks, 0 sealed
  classes; 13 old-style switch **statements** (0 switch expressions);
  6 `catch (Exception e)` blocks; anonymous `Runnable` classes ×6 (all in tests);
  the search core passes `(score, move)` around as raw `Object[]` and signals
  timeouts by throwing a bare `new Exception("Exceeded time")`.
- `GameTree` has 9 public + 1 private (13-arg) telescoping constructors (~165 lines).
- Dead code: `PusherApplication.letterToInt`, `Piece.getID`, `Piece.hasAdjacentPusher`,
  the `verbose` flag (always false), 4 unused imports, a dead try/catch in `GameStep`.
- The build compiles with **no `--release` flag**, so the language level silently
  follows whatever JDK is installed; the README says "JDK 17+".
- `.idea/` IDE files are committed to git.

**Recommended order:** C1 first (everything builds on it), then C2–C9 in any order
(each is independent), C10 last (biggest blast radius, optional), C11 anytime.

---

## C1 — Pin the Java baseline to 21 in the build toolchain

**Goal.** Make the language level explicit and modern. The machine building this repo
has Homebrew OpenJDK 27; the code today compiles at "whatever the local JDK says"
(`build.sh` runs bare `javac -cp ... -d out`). Target **Java 21** — the LTS where
records, sealed classes, pattern matching for `instanceof`, switch expressions,
text blocks and `var` are all final. (Java 25/27 add nothing this codebase uses;
pinning 21 keeps the bar portable while still compiling under the local 27.)

**Changes.**
1. `build.sh` `compile()`: `javac --release 21 -cp ... -d "$OUT" $sources`.
2. `build.sh`: error message becomes "JDK 21+ required…"; header comment
   "Requires JDK 21+".
3. `README.md` Requirements: "JDK 21+ (`java` and `javac` on PATH)."
4. Optionally add `-Xlint:all` to the `javac` line to start collecting warnings
   (do **not** fix them here; C8 uses them).

**Verification.** `./build.sh test` → `OK (135 tests)`, unmodified source compiles
cleanly at 21 (it does: nothing newer than 17 is used today).

**Notes.** `--release` only pins compilation; the runtime stays the local JDK.
No source changes in this task.

---

## C2 — Replace the `Object[]` (score, move) tuples and the raw timeout exception in `GameTree`

**Goal.** The search core returns `new Object[]{score, bestMove}` from
`alphaBeta` (3 overloads) and `quiescence`, and callers downcast with
`(Integer) result[0]` / `(Move) result[1]` (~20 sites: lines 562, 567, 601, 617,
664, 668, 711, 735, 807, 820, 838, 850, 931, 935, 939, 944, 1130s). Timeouts are
signalled by `throw new Exception("Exceeded time")` (line 781) and swallowed by
five `catch (Exception e)` blocks (lines 569, 669, 714, 736; plus
`searchLevelWithAspiration`'s `throws Exception` at 602). Both are pre-Java-7
habits that hide real bugs (any NPE in search is silently treated as a timeout).

**Changes.**
1. Add `private record ScoreAndMove(int score, Move bestMove) {}` in `GameTree`
   (private nested — the type never leaves the class).
2. `alphaBeta`/`quiescence`/`searchLevelWithAspiration` return `ScoreAndMove`;
   all `(Integer) x[0]`/`(Move) x[1]` become `x.score()`/`x.bestMove()`.
3. Add `private static final class SearchTimeoutException extends Exception {}`
   and change line 781 to `throw new SearchTimeoutException()`. The `throws`
   clauses and the five `catch` blocks catch `SearchTimeoutException` **in exactly
   the same places with the same recovery** (revert snapshot / return null /
   return `Integer.MIN_VALUE`). No other exception type may start being caught —
   if that tightens anything in practice, the suite will show it.
4. Update the two javadoc mentions of "Object[] with the first element being the
   score" (lines 756, 762).

**Verification.** `OK (135 tests)` — `GameTreeTest`, `AspirationPvsTest`,
`TranspositionTableTest`, `MoveOrderingTest`, `QuiescenceTest`, `EngineLevelTest`
exercise every changed path (they only touch the public API: `getBestMoveQuick`,
`debugSearchScore`, `probeTT` — whose signatures do **not** change).

**Notes.** This is the single biggest readability win in the engine. Nothing in
`src/tests` references `Object[]` or the exception.

---

## C3 — Records for pure data carriers

**Goal.** Four classes are "fields + one constructor + nothing else":

1. `TranspositionTable.Entry` (client/TranspositionTable.java:79–97) —
   `final long hash; int depth; int score; Flag flag; Move bestMove;`.
2. `Bench.GameResult` (client/Bench.java:547) — 9 final fields, one constructor.
3. `Bench.AbGameResult` (client/Bench.java:~576) — same shape, 9 final fields.
4. `Bench.Summary` (client/Bench.java:~600) — final fields computed in one
   constructor → record with a compact constructor.

**Changes.** Convert each to a `record`. Accessor syntax changes from field access
(`entry.score`) to methods (`entry.score()`); update call sites:
- `Entry`: `GameTree` lines 805–807, 881–893; `TranspositionTable.usable` (line
  201, also switch-expression-ify it in C4); `TranspositionTableTest` from line
  120; `AspirationPvsTest:496` (`Entry[]` array type — works unchanged).
- `GameResult`/`AbGameResult`/`Summary`: only inside `Bench` itself (field reads
  in the report/JSON formatting, lines ~420–440 and 619–680). `BenchTest` pins the
  printed stdout — the format strings must stay character-identical.
- `stalemate` is computed from `stalemated != null` in both result classes: keep it
  as a record component with the compact-constructor derivation, or as an
  accessor `boolean stalemate()` — pick one, stay consistent.

**Optional sub-item (do only if nothing else in the task is flaky): `Move` → record.**
`Move` (game/Move.java) has two effectively-immutable fields (verified: only the
constructor assigns them), a hand-written `equals`/`hashCode` that a record would
generate identically (`BoardSquare.equals` is already value-based on row+column),
and a `toString()` that is the **wire format** ("D2E3"). If done:
- `record Move(BoardSquare pieceLocation, BoardSquare destination) { @Override
  public String toString() { return pieceLocation + destination; } }` — the
  `toString` override is mandatory (MockPusherServer and PusherApplicationTest
  depend on the 4-char format).
- 43 call sites of `getPieceLocation()`/`getDestination()` become
  `pieceLocation()`/`destination()`.
- Fields become `final` — safe, no mutators exist.
This sub-item is the riskiest part of C3; if in doubt, ship C3 without it and file
a follow-up.

**Verification.** `OK (135 tests)`; `BenchTest` (stdout pin) green.

---

## C4 — Sealed `Piece` hierarchy + pattern matching + switch expressions

**Goal.** Use the Java 17/21 constructs the codebase pre-dates.

**Changes.**
1. **Sealed classes:** `public sealed abstract class Piece permits Pusher, Pushee`;
   `public final class Pusher extends Piece`; `public final class Pushee extends
   Piece`. No `non-sealed` anywhere. (Cloning is unaffected.)
2. **Pattern matching for `instanceof`** (production code):
   - `game/Move.java:29` — `equals` (or delete with C3's record, in which case
     note it here as done-by-C3).
   - `game/BoardSquare.java:66` — `equals`.
   - `game/Pushee.java:46` — `if (possiblePusher instanceof Pusher p &&
     p.isOwner(this.getOwner()))`.
   - `client/GameAnalyzer.java:181, 214, 238, 240`.
   - `client/PusherApplication.java:87–89` —
     `in instanceof BufferedInputStream bis ? bis : new BufferedInputStream(in)`.
   - Test files (`BugProofTest:260`, `PusherApplicationTest:158, 377`) — optional,
     same style.
3. **Switch expressions** (statement → expression where the shape allows):
   - `client/GameTree.java:369` — `strategy = switch (maxPlayer) { case WHITE ->
     new WhiteStrategy(); case BLACK -> new BlackStrategy(); };` (exhaustive on
     the enum, no `default` needed). *If C5 lands first, this line becomes
     `strategy = new PlayerStrategy(maxPlayer);` instead — do whichever lands
     second, keep one version.*
   - `client/TranspositionTable.java:201` (`usable`) — switch expression; the
     `default: throw new IllegalStateException` disappears (enum is exhaustive).
   - `client/PusherApplication.java:298` (`createInitialBoard`) —
     `Piece piece = switch (Integer.parseInt(tok)) { case 1 -> new
     Pushee(Player.BLACK); case 2 -> new Pusher(Player.BLACK); case 3 -> new
     Pushee(Player.WHITE); case 4 -> new Pusher(Player.WHITE); default -> null; };`
     then `if (piece != null) board.putPiece(piece, y, x);` — keep the x/y
     decrement/wrap logic exactly as-is.
   - **Leave alone:** `PusherApplication:104` (command loop — C7 restructures it,
     it needs method extraction, not an expression), `Bench:463` (arg parsing with
     `++i` inside cases — switch expressions make that uglier), and the 7 test-only
     `switch (which)` helpers (harmless).
4. **Text blocks: deliberate non-change.** The only multi-line strings are the
   dynamic board rendering in `GameBoard` (built per row) and `Bench`/`VisualGame`
   output; text blocks would not shorten them. Recorded here so nobody retries.

**Verification.** `OK (135 tests)`.

**Notes.** No class outside `game.Pusher`/`game.Pushee` extends `Piece`, so sealing
breaks nothing (verified by grep; the compiler double-checks at build time).

---

## C5 — Collapse `WhiteStrategy`/`BlackStrategy` into one class

**Goal.** The two classes are byte-identical except the `Player` they pass to
`GameAnalyzer.analyzeFor` (each is 30 lines). T8 made them mirrors; the mirror is
now the whole implementation.

**Changes.**
1. New `client/PlayerStrategy implements Strategy`:
   ```java
   public class PlayerStrategy implements Strategy {
       private final Player player;
       public PlayerStrategy(Player player) { this.player = player; }
       @Override public int analyze() { return analyze(true); }
       @Override public int analyze(boolean threatTermsEnabled) {
           return GameAnalyzer.analyzeFor(GameBoard.getInstance(), player, threatTermsEnabled);
       }
   }
   ```
2. `GameTree` constructor: `strategy = new PlayerStrategy(maxPlayer);` (replaces
   the C4-#3 switch if C4 landed first).
3. Delete `WhiteStrategy.java` and `BlackStrategy.java`.
4. Update the 29 test references: `new WhiteStrategy()` → `new
   PlayerStrategy(Player.WHITE)`, `new BlackStrategy()` → `new
   PlayerStrategy(Player.BLACK)` (in `BugProofTest`, `EvalSymmetryTest`,
   `ThreatEvalTest`, `EngineLevelTest`, `GameAnalyzerTest`).
5. Keep the `Strategy` interface and `GameAnalyzer.analyze(GameBoard, Strategy)`
   (used by `GameAnalyzerTest:25`).

**Verification.** `OK (135 tests)` — `EvalSymmetryTest` specifically pins the
mirror invariant that this refactor preserves by construction.

**Notes.** Class names appear in some T-plan javadoc history; don't rewrite
historical comments — only live references.

---

## C6 — Kill the `GameTree` constructor telescoping

**Goal.** `GameTree` has 9 public constructors (lines 195–360) that forward to a
private 13-arg one, each adding one more boolean feature flag with a default.
Production uses the 2-arg form (`PusherApplication`) and the 4-arg form
(`Bench`, `VisualGame`); the rest exist purely so tests can flip individual flags.

**Changes.**
1. New `public record GameTreeConfig(long timeBudgetMillis, Long seed,
   boolean transpositionEnabled, boolean aspirationEnabled, boolean pvsEnabled,
   boolean ttMoveHandoff, boolean quiescenceEnabled, boolean threatTermsEnabled,
   boolean repetitionEnabled, boolean orderingEnabled)` with
   `static GameTreeConfig defaults()` returning all-true flags,
   `MAX_TIME_MILLIS`, `null` seed (the current `GameTree(max, min)` defaults).
2. Keep exactly two convenience constructors: `GameTree(Player, Player)` and
   `GameTree(Player, Player, long, Long)`; both delegate to one new
   `GameTree(Player maxPlayer, Player minPlayer, GameTreeConfig config)` next to
   the current private 13-arg constructor (absorbed).
3. **Delete the `verbose` parameter entirely** (and the field, and the
   `if (verbose)` block at line 579): every public constructor forwards `false`
   (line 342), the field initializer `true` is immediately overwritten, and no
   test can set it. The debug print at 579–580 is dead code — verified by the
   constructor chain, re-verify with `git grep verbose` after the change.
4. Migrate test call sites: the 21 × 2-arg and 6 × 3-arg/4-arg calls are
   untouched; the 13 × 5-arg, 5 × 6-arg, and the 12-arg calls (notably
   `EngineLevelTest:91, 236, 246`, `MoveOrderingTest`, `AspirationPvsTest`,
   `TranspositionTableTest`, `QuiescenceTest`, `RepetitionTest`,
   `GameTreeTest` where they appear) become `new GameTree(max, min, new
   GameTreeConfig(budget, seed, …))`.
5. Preserve the exact default per flag (post-T16: tt, aspiration, pvs, handoff,
   quiescence, threat terms, repetition, ordering **all enabled** by default).

**Verification.** `OK (135 tests)` — `EngineLevelTest` is the canary: it exists
precisely to prove each flag combination behaves as before.

**Notes.** Estimated net deletion ≈ 150 lines. This task is mechanical but
touches the most test files; do it in one commit so the diff is reviewable as a
whole.

---

## C7 — `PusherApplication` cleanup

**Goal.** The socket client carries the repo's oldest dead weight.

**Changes.**
1. Delete `letterToInt(char)` (lines 338–358): defined, never called (the live
   code already does `s.charAt(oc) - 'A'` in `buildMove`).
2. Rename `DESTINATION_COLUM` → `DESTINATION_COL` (package-private, 3 references,
   lines 29/236/237).
3. `getRandomMove` (line 329): `new Random()` per call → one
   `private static final Random` field. (Unseeded path, so no determinism
   invariant — see shared rules; seeded behavior is untouched.)
4. `communicateMove` (line 209): `output.write(moveParameters.getBytes(), 0,
   moveParameters.length())` → `output.write(moveParameters.getBytes(
   StandardCharsets.US_ASCII))` — byte-identical on the wire (moves are
   A–H/1–8), removes the platform-default-charset dependence.
5. **Command loop (lines 84–173):** extract the case bodies into private static
   methods and make the loop a thin dispatcher:
   - `case '1'` and `'2'` share 90% of their body (reset, `plies = 0`,
     `createInitialBoard`, tree construction, `updateAllLegaLMoves`) — extract
     `beginGame(boolean aiPlaysWhite, boolean replyImmediately)`: `'1'` answers
     with the best move, `'2'` stays silent (keep exactly that difference).
   - `case '3'` → `applyOpponentPly()` (apply own move, read the 16-byte
     payload, parse, apply opponent move, answer, refresh move lists).
   - `case '4'` → one-liner over `getRandomMove`.
   - **Keep the `try { switch … } catch (Exception e) { e.printStackTrace(); }`
     boundary around the whole dispatch** (T2 invariant: one bad command must not
     kill the client — pinned by `PusherApplicationTest` framing tests). The
     dispatch itself may be a switch expression calling the methods.
6. `createInitialBoard`: switch expression per C4-#3; keep the `available()`
   read and the H8-first x/y walk **exactly** (wire protocol, server-confirmed).

**Verification.** `OK (135 tests)` — `PusherApplicationTest` (pipelines + real
loopback sockets via `MockPusherServer`) and `RepetitionTest` cover the loop.

**Non-goal here:** the `public static` mutable state (`board`, `move`,
`gameTree`, `plies`, `MAX_PLIES`). `RepetitionTest` pokes `MAX_PLIES`/`plies`
directly, and an instance-based `Session` class would touch every integration
test. Parked as a future task, not part of this cleanup — **done as C12.**

---

## C8 — Dead code, unused imports, and warning sweep

**Goal.** Remove what nothing calls. (Do this after C2–C7 so nothing deletes
live code that another task just created.)

**Changes.**
1. `game/Piece.java`: delete `abstract int getID()` and the three
   `public static final int ID` constants + `getID()` overrides in `Pusher`/
   `Pushee` (only referenced inside their own classes).
2. `game/Piece.java`: delete `hasAdjacentPusher()` (zero callers repo-wide).
   **Keep** `BoardSquare.hasPusher()`/`hasPushee()`: after #2 `hasPusher()` is
   test-only, but both are tiny, tested in `BoardSquareTest`, and keeping them
   preserves the 135-test bar.
3. Unused imports (all verified unused):
   - `game/Piece.java:3` — `import client.GameStep;` (also a package-layering
     inversion: `game` must not import `client`);
   - `game/Pushee.java:3–4` — `java.lang.reflect.Array`, `java.util.ArrayList`;
   - `game/Pusher.java:3` — `java.util.ArrayList`;
   - `game/GameBoard.java:4` — `client.TranspositionTable` (javadoc mention only;
     the javadoc already qualifies it).
4. `client/GameStep.java:37–43`: the `try { … clone() … } catch (Exception e) {
   e.printStackTrace(); }` is dead — `Piece.clone()` catches
   `CloneNotSupportedException` itself and never throws. Reduce to
   `boardPieces[i] = pieceToClone.clone();`
5. `game/GameBoard.java:330–337` (`toString`): replace the
   `String.concat` chain with `return rowsToString() + bottomBoardToString();`
   (or one `StringBuilder`) — identical output.
6. Run `javac -Xlint:all` (flag added in C1) and fix every remaining warning
   (unchecked casts, deprecations, serial warnings) without behavior change.
   If a warning cannot be fixed without behavior risk, list it in the commit
   message instead.

**Verification.** `OK (135 tests)` (none of the deleted members have dedicated
unit tests — verified; `BoardSquareTest`'s `hasPushee`/`hasPusher` assertions
stay).

---

## C9 — Collection & API modernization (small wins)

**Goal.** Interface-over-implementation APIs and modern randoms, in places where
the change is mechanical.

**Changes.**
1. Return `List<Move>` / `List<Piece>` instead of concrete `ArrayList` from the
   public API: `Piece.getLegalMovesQuick()`, `GameBoard.getPieceList()`,
   `GameBoard.getAllLegalMovesQuick()`; parameter of
   `GameTree.orderMoves(ArrayList<Move> …)` → `List<Move>` (callers already pass
   a fresh copy at `GameTree:823`). Fields may stay `ArrayList`; only signatures
   and the handful of `ArrayList<…> x = …get…()` declarations in
   `PusherApplication:160` and tests change (→ `List<Move>` or `var`).
   **Check before landing:** no caller mutates a returned list
   (`grep -n "legalMoves\.\(add\|set\|remove\)"`).
2. Unseeded `new Random()` → `RandomGenerator.getDefault()` (Java 17+):
   `PusherApplication.getRandomMove` (if C7 didn't already land it as a field)
   and `GameTree.nextRandom()`'s unseeded branch (line 387). **Seeded paths
   stay `new Random(seed)` untouched** (reproducibility invariant — Bench
   golden values, Zobrist, seeded shuffles).
3. `tests/MockPusherServer:51`: `Collections.synchronizedList(new
   ArrayList<>())` → `new CopyOnWriteArrayList<>()` (append-only usage).
4. `client/GameTree.java:823` and friends: `new ArrayList<>(list)` copies are
   fine as-is; do **not** convert `killedPieceLog` (`LinkedList` deque — the
   LIFO capture log's `push`/`peekLast`/`pollLast` semantics are load-bearing,
   see T2/BugProofTest).

**Verification.** `OK (135 tests)`; `BenchTest` stdout pin still green.

---

## C10 — (Optional, do last) Migrate tests from JUnit 4 to JUnit 5 (Jupiter)

**Goal.** JUnit 4 is EOL. The migration is mechanical here: 135 tests,
`@Before`/`@After` (10 files), plain `org.junit.Assert.*` statics, and **no**
JUnit-4-only features (no `@Rule`, no `expected=`/`timeout=`, no `Assume`, no
Hamcrest matchers — verified).

**Changes.**
1. `build.sh`: add a pinned `junit-platform-console-standalone` jar to the
   pinned-deps block (same exact-version + Maven-Central-URL pattern as JUnit 4).
   This single jar replaces both `junit-4.13.2.jar` and
   `hamcrest-core-1.3.jar` (Jupiter ships its own assertions; drop the Hamcrest
   download — nothing imports it).
2. `run_tests()`: replace the hand-rolled class-discovery loop + `JUnitCore`
   with the console launcher:
   `java -jar "$JUPITER_JAR" execute --class-path "$OUT" --select-package tests
   --fail-if-no-tests --disable-ansi-colors`
   (verify the exact subcommand/flags against the pinned version before wiring).
   Keep the same "no test classes → error" failure mode.
3. Test sources: `org.junit.jupiter.api.{Test,BeforeEach,AfterEach}`,
   `org.junit.jupiter.api.Assertions.*`. Pure import/annotation rename — no test
   logic changes, no assertion-message rewording (tests' messages are part of
   their regression value).
4. `README.md`: update the dep list and the summary-line description (the
   launcher prints "test count" — update the "Expected: OK (135 tests)" note to
   the launcher's actual output, keeping 135 as the number).

**Verification.** Same 135 tests pass under Jupiter; `BugProofTest` included.
Re-run `BenchTest` — it captures stdout, and the runner change must not leak
output into captured streams.

**Notes.** Biggest blast radius in this plan, entirely confined to `src/tests` +
`build.sh`. Do it in its own branch; if the pinned launcher's CLI turns out
awkward, the task is still shippable by keeping `JUnitCore`-style explicit
class lists — the framework migration and the runner change are separable.

---

## C11 — Repository hygiene

**Changes.**
1. Untrack IDE files: `git rm --cached .idea/artifacts/log320_pusher_jar.xml
   .idea/uiDesigner.xml` (both currently committed) and add `.idea/` to
   `.gitignore` (alongside `out/`, `lib/`).
2. `README.md`: after C1, the Requirements line says JDK 21+; re-check the
   "Expected: OK (135 tests)" note against whichever of C8/C10 landed (both
   preserve 135 by design — only C10 changes the *format* of the summary line).
3. Keep `src/META-INF/MANIFEST.MF` (referenced by the IDE run config; not used
   by `build.sh`) — no action.

**Verification.** `git status` clean after the untrack; `./build.sh test` still
green (untracked IDE files are not part of the build).

---

## C12 — `GameSession`: the parked `PusherApplication` state refactor

This is the follow-up parked by C7, tracked here so the decision record
stays in one place. It is a small project, not a cleanup pass, but it follows
the same one rule: **no behavior changes** — wire protocol, stdout, timing
and the "one bad command must not kill the client" boundary stay
byte-identical.

**Goal.** The client's per-connection state (`move`, `gameTree`, `plies`,
`MAX_PLIES`) was `public static` on `PusherApplication`, so the app could
only ever run one game at a time and app-level tests had to poke and restore
globals (`RepetitionTest` saved/restored `MAX_PLIES`/`plies` around the
warning test). Move the state and the command loop into an instance-based
`GameSession`; `PusherApplication` shrinks to the socket entry point.

**Changes (as landed).**
1. New `client/GameSession` owns: `move`, `gameTree`, `plies`, and the cap as
   a per-session `final int maxPlies` (`GameSession.DEFAULT_MAX_PLIES = 300`,
   the old `MAX_PLIES` value) with `new GameSession()` / `new
   GameSession(maxPlies)` and `getPlies()`/`getMaxPlies()` accessors. The
   command loop (`run`), `beginGame`, `applyOpponentPly`, `playRandomMove`,
   `registerPly`, `communicateMove`, `parseMove` and friends moved there
   verbatim as instance methods, together with the wire-protocol javadoc.
   `board` is the shared `GameBoard` singleton held by reference (the
   singleton stays — see Decisions); the legacy-layout constants and the
   unseeded `RANDOM` generator moved with the logic (still one generator per
   class, so the '4' random-pick sequence is unchanged for a long-lived
   client).
2. `PusherApplication` keeps `main` (identical stdout) and the long-standing
   `public static void run(InputStream, OutputStream)` as a thin delegator to
   `new GameSession().run(...)` — so `main`, the MANIFEST `Main-Class`, and
   the test call sites that drive the app through the static stay untouched.
3. `tests.PusherApplicationTest.AppSession` gains a `GameSession` constructor
   overload + `getAppSession()` (the default-arg constructor and the socket
   path via the static `run` are unchanged); `tests.RepetitionTest` now
   builds a dedicated `new GameSession(4)` instead of poking and restoring
   `PusherApplication.MAX_PLIES`/`plies`, and asserts on
   `appSession.getPlies() >= appSession.getMaxPlies()`.
4. Live doc references updated (`docs/RULES.md` "Code today" sections,
   `MockPusherServer` javadoc, README class list); historical T-plan
   mentions of the old layout are left alone.

**Verification.** `./build.sh test` → 135/135 green (the suite drives both
entry points: `AppSession`'s piped-stream path over `GameSession.run` and
`startApp`'s real loopback-socket path over the static `PusherApplication.run`
via `MockPusherServer`).

---

## Decisions & non-goals (recorded so they aren't re-litigated)

- **No Maven/Gradle.** The dependency-free `build.sh` (two pinned jars, curl) is a
  feature: fresh clone, one command, works offline after first run. C10 keeps that
  property (one more pinned jar, still no build tool).
- **No virtual threads / NIO.** The client is a single blocking socket connection
  whose loop shape *is* the protocol; `MockPusherServer`'s two daemon threads are
  fine. Nothing to gain, only risk.
- **No `GameBoard` singleton removal.** 54 test `@After` resets and the engine
  invariants (shared cached move lists) are built on it. A context-object refactor
  is a real project, not cleanup.
- **No renaming of `getBestMoveQuick` / `updateAllLegaLMoves` / `getLegalMovesQuick`**
  (the "LegaL"/"Quick" names). They appear in hundreds of javadoc lines across the
  T1–T16 history; a rename is churn with zero behavior value.
- **No text blocks** (see C4-#4) and **no streamification of the search hot
  paths** — `alphaBeta`, `orderMoves` and the `GameBoard` move-list maintenance are
  performance-sensitive and already tight; streams would add allocation, not
  clarity. Streams remain available for cold code if a task wants them.
- **Test count 135 is preserved by every task** except where a task explicitly
  says otherwise (none does — C8 keeps the `hasPusher`/`hasPushee` tests on
  purpose).
