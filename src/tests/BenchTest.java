package tests;

import client.Bench;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T7 smoke test: the bench runs one real self-play game on a short budget,
 * reports it (per-game line + single-line JSON summary) and stays fast.
 * The full-suite guard for the T7 acceptance criteria (bench CLI under 60 s
 * for 3 games @ 1000 ms) is the manual run documented in the T7 task.
 */
public class BenchTest {

    private static final int MAX_ALLOTTED_TIME_MILLIS = 10_000;

    @Test
    public void oneGameShortBudgetRunsAndReports() {
        long before = System.currentTimeMillis();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        Bench.Summary summary;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            summary = Bench.run(new String[]{
                    "--games", "1", "--budget", "500", "--seed", "7"});
        } finally {
            System.setOut(originalOut);
        }
        long elapsed = System.currentTimeMillis() - before;

        assertNotNull(summary, "bench must run and return a summary");
        assertEquals(1, summary.games().size());

        Bench.GameResult game = summary.games().get(0);
        assertTrue(game.plies() > 0, "game must have produced moves (plies=" + game.plies() + ")");
        assertTrue(game.winner() != null || game.truncated(), "winner must be set unless the game was truncated");
        assertTrue(game.maxDepth() >= 1, "search must complete at least level 1 (maxDepth="
                + game.maxDepth() + ")");
        assertTrue(game.elapsedMs() > 0);
        assertTrue(summary.avgMsPerMove() > 0);
        assertEquals(1, summary.whiteWins() + summary.blackWins()
                + (game.truncated() ? 1 : 0));

        String stdout = captured.toString(StandardCharsets.UTF_8);
        String jsonLine = summary.toJsonLine();
        assertTrue(stdout.contains(jsonLine), "summary JSON line must be printed: " + jsonLine);
        assertTrue(stdout.contains("game 1:"), "per-game report line must be printed");

        assertTrue(elapsed < MAX_ALLOTTED_TIME_MILLIS, "one 500 ms-budget game must finish in under 10 s "
                + "(took " + elapsed + " ms)");
    }

    @Test
    public void invalidArgumentsAreRejected() {
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        Bench.Summary summary;
        try {
            System.setErr(
                    new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
            summary = Bench.run(new String[]{"--games"});
        } finally {
            System.setErr(originalErr);
        }

        assertNull(summary, "a missing value must be rejected");
        assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("Usage:"), "usage must be printed: "
                + capturedErr.toString(StandardCharsets.UTF_8));
    }
}
