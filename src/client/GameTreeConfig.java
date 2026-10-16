package client;

/**
 * C6: the explicit search-feature configuration of {@link GameTree},
 * replacing the telescoping 5- through 12-argument constructors (each of
 * which only added one more feature flag with an implicit default).
 *
 * The flag order is the historical constructor order: transposition,
 * aspiration, PVS, TT move handoff, quiescence, threat terms, repetition,
 * ordering.
 */
public record GameTreeConfig(long timeBudgetMillis, Long seed,
                             boolean transpositionEnabled,
                             boolean aspirationEnabled,
                             boolean pvsEnabled,
                             boolean ttMoveHandoff,
                             boolean quiescenceEnabled,
                             boolean threatTermsEnabled,
                             boolean repetitionEnabled,
                             boolean orderingEnabled) {

    /**
     * The production defaults: the default per-move budget, a null
     * (non-reproducible) seed, every feature flag enabled — exactly what
     * {@code new GameTree(max, min)} built before C6.
     */
    public static GameTreeConfig defaults(){
        return new GameTreeConfig(GameTree.MAX_TIME_MILLIS, null,
                true, true, true, true, true, true, true, true);
    }
}
