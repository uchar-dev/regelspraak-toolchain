package io.github.yhavinga.regelspraak.engine;

import java.util.List;

/**
 * The outcome of running ONE scenariotoets (docs/SCENARIOTOETSEN.md) through the {@link ScenarioRunner}: its
 * {@link Status}, the per-expectation verdicts, the fired-rules {@link Trace}, and the per-scope-rule coverage.
 * It carries both the VERWACHTE and the WERKELIJKE value per expectation — the werkelijk side is the oracle the
 * next phase (the golden-TOKA differential) reads, and the verwacht/werkelijk pair is what a FAIL prints.
 *
 * @param naam        the scenario name (as written)
 * @param status      PASS (all expectations met, no marker), FAIL (≥1 expectation missed / resolve error),
 *                    or MARKER (a scope rule the transpiler could not lower — accepted-but-inert, never a PASS)
 * @param verwachtingen the per-expectation verdicts, in written order
 * @param trace       the fired-rules trace (rule name + bound LABELs + produced values), always available
 * @param dekking     the per-scope-rule coverage (getest/gevuurd/niet-geraakt/marker)
 * @param diagnose    a human-readable one-line summary of WHY it is not a PASS (empty for PASS)
 * @param uitleg      the {@link Verbalisator} verhaal — one NL sentence per line justifying the assigned
 *                    kenmerken with their evaluated operand values, empty when {@code --uitleg} was not asked
 *                    (or nothing to verbalise). Optional/additive: the six-arg constructor defaults it empty,
 *                    so every existing call site (and the golden differential) is byte-unchanged.
 */
public record ScenarioResultaat(String naam, Status status, List<VerwachtingResultaat> verwachtingen,
                                Trace trace, List<DekkingRegel> dekking, String diagnose, List<String> uitleg) {

    /** Backward-compatible constructor: no uitleg (the pre-verbalisator shape). */
    public ScenarioResultaat(String naam, Status status, List<VerwachtingResultaat> verwachtingen,
                             Trace trace, List<DekkingRegel> dekking, String diagnose) {
        this(naam, status, verwachtingen, trace, dekking, diagnose, List.of());
    }

    /** This resultaat with the verbalised uitleg attached (the runner adds it after firing when asked). */
    public ScenarioResultaat metUitleg(List<String> uitleg) {
        return new ScenarioResultaat(naam, status, verwachtingen, trace, dekking, diagnose,
                uitleg == null ? List.of() : List.copyOf(uitleg));
    }

    public enum Status { PASS, FAIL, MARKER }

    public boolean pass() { return status == Status.PASS; }

    /** One expectation's verdict: its source text ({@code bron}), the expected and actual value (rendered
     *  human-readably), and whether it held. */
    public record VerwachtingResultaat(String bron, String verwacht, String werkelijk, boolean ok, int line) {}

    /** The fired-rules trace: one entry per rule activation, in fire order. */
    public record Trace(List<Vuring> vuringen) {
        public int totaal() { return vuringen.stream().mapToInt(Vuring::aantal).sum(); }
    }
    /** One traced rule: the (de-versioned, de-prefixed) rule name, the LABELs of the facts it bound, the
     *  produced value where reasonably determinable (else empty), and how many times it fired. */
    public record Vuring(String regel, List<String> labels, String waarde, int aantal) {}

    /** One scope rule's coverage verdict for this run (§ "Dekking per scenario-run": getest/gevuurd/niet-geraakt). */
    public record DekkingRegel(String regel, DekkingSoort soort) {}
    public enum DekkingSoort {
        GEVUURD,       // the rule is in scope, lowered, and fired at least once in this scenario
        NIET_GERAAKT,  // in scope and lowered, but did not fire (its guard did not match the facts)
        MARKER         // in scope but NOT lowered by the transpiler (an honest marker → scenario status MARKER)
    }
}
