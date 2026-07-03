package io.github.yhavinga.regelspraak.transpiler.kern;

import io.github.yhavinga.regelspraak.ir.Conditie;

/**
 * THE comparison-operator table both back-ends share (single shared implementation): the mvel/rsAggVergelijk symbol,
 * the §13.4.8 logical negation ({@link #flip}) and the operand-swap reversal ({@link #omkeren}).
 * The two transformations are deliberately side by side because confusing them is THE classic bug:
 * negation complements the boundary (GROTER → KLEINER_OF_GELIJK), reversal mirrors it (GROTER →
 * KLEINER) — §8.2 putting a right-written temporal operand on the left needs omkeren; flip there
 * would move the OF_GELIJK boundary.
 */
public final class Operatoren {
    private Operatoren() {}

    /** The mvel / {@code rsAggVergelijk} comparison symbol. */
    public static String symbool(Conditie.Operator op) {
        return switch (op) {
            case KLEINER, EERDER -> "<";
            case KLEINER_OF_GELIJK, EERDER_OF_GELIJK -> "<=";
            case GROTER, LATER -> ">";
            case GROTER_OF_GELIJK, LATER_OF_GELIJK -> ">=";
            case GELIJK -> "==";
            case ONGELIJK -> "!=";
        };
    }

    /** §13.4.8 logical NEGATION — the complement that detects a violation ("moet kleiner zijn dan" is
     *  violated when {@code >=}). */
    public static Conditie.Operator flip(Conditie.Operator op) {
        return switch (op) {
            case GELIJK -> Conditie.Operator.ONGELIJK;
            case ONGELIJK -> Conditie.Operator.GELIJK;
            case KLEINER -> Conditie.Operator.GROTER_OF_GELIJK;
            case KLEINER_OF_GELIJK -> Conditie.Operator.GROTER;
            case GROTER -> Conditie.Operator.KLEINER_OF_GELIJK;
            case GROTER_OF_GELIJK -> Conditie.Operator.KLEINER;
            case EERDER -> Conditie.Operator.LATER_OF_GELIJK;
            case EERDER_OF_GELIJK -> Conditie.Operator.LATER;
            case LATER -> Conditie.Operator.EERDER_OF_GELIJK;
            case LATER_OF_GELIJK -> Conditie.Operator.EERDER;
        };
    }

    /** Operand REVERSAL (swap LHS/RHS): "a = b" reverses to "b = a" (GELIJK stays GELIJK), "a &gt; b" to
     *  "b &lt; a". NOT a negation — see the class doc. */
    public static Conditie.Operator omkeren(Conditie.Operator op) {
        return switch (op) {
            case GELIJK -> Conditie.Operator.GELIJK;
            case ONGELIJK -> Conditie.Operator.ONGELIJK;
            case KLEINER -> Conditie.Operator.GROTER;
            case KLEINER_OF_GELIJK -> Conditie.Operator.GROTER_OF_GELIJK;
            case GROTER -> Conditie.Operator.KLEINER;
            case GROTER_OF_GELIJK -> Conditie.Operator.KLEINER_OF_GELIJK;
            case EERDER -> Conditie.Operator.LATER;
            case EERDER_OF_GELIJK -> Conditie.Operator.LATER_OF_GELIJK;
            case LATER -> Conditie.Operator.EERDER;
            case LATER_OF_GELIJK -> Conditie.Operator.EERDER_OF_GELIJK;
        };
    }
}
