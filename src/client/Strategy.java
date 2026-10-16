package client;

/**
 * Created by Sam on 11/9/2015.
 */
public interface Strategy {

    public int analyze();

    /**
     * T15: the same evaluation with T4's threat &amp; safety terms included
     * or not. The pre-T4 engine stack (t2/t3 in the T15 level naming)
     * evaluates without them; both strategies must honor the flag (T8's
     * mirror invariant holds with the term both on and off, pinned in
     * tests.EvalSymmetryTest).
     */
    public int analyze(boolean threatTermsEnabled);
}
