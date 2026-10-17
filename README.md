# Pusher-AI

A Java engine + client for the board game **Pusher**. It reads a board position
over a socket, searches with alpha-beta pruning (see `src/client/GameTree.java`),
and replies with its chosen move.

- `src/game/` — the board model (`GameBoard` singleton, `Piece`, `Pusher`,
  `Pushee`, `Move`, ...).
- `src/client/` — the engine and the socket client (`GameTree`, `PlayerStrategy`,
  `GameAnalyzer`, `PusherApplication`).
- `src/tests/` — JUnit 5 (Jupiter) tests. `BugProofTest` is the regression
  guard: it encodes every bug fixed in the `fix/bugs-and-tests` work.

## Requirements

- **JDK 21+** (`java` and `javac` on `PATH`).
- `curl` (only used to fetch the test dependencies on first run).

## Build & test

One command builds everything and runs the whole test suite:

```bash
./build.sh test
```

What it does:
1. Downloads `lib/junit-platform-console-standalone-1.14.4.jar` (JUnit 5 /
   Jupiter console launcher, standalone edition) from Maven Central **only if
   missing** (pinned exact version).
2. Compiles all of `src/` into `out/`.
3. Runs every test class under `src/tests` with the JUnit 5 console launcher
   (`execute --select-package tests --fail-if-no-tests`) and prints its summary
   block (expected: `[ 135 tests successful ]` and `[ 0 tests failed ]`).

It exits non-zero on a missing toolchain, a compile error, or any test failure.
`lib/` and `out/` are git-ignored, so this works from a fresh clone.

## Running the client

`PusherApplication` is the socket client. It expects a game server listening on
`localhost:8888`:

```bash
./build.sh test            # builds out/ first (or: javac -d out $(find src -name '*.java'))
java -cp out client.PusherApplication
```

On connect it reads the protocol commands (side to play, a 64-int board, the
opponent's moves) from `localhost:8888` and writes back its 4-char moves. The
server address is currently hard-coded to `localhost:8888`.
