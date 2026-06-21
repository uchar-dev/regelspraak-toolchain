package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * A resolved §13.4.10 verdeling: a source amount ({@code bronAttribuut} of
 * {@code bronType}) is distributed over a recipient attribute ({@code doelAttribuut}
 * of {@code doelType}, the objects related to the source) by some method.
 *
 * {@link Methode#GELIJKE_DELEN equal shares} (total / count) and {@link Methode#NAAR_RATO
 * proportional} (total · weight / sum-of-weights) each have a clean single forward-rule
 * Drools form; the group/ordering/remainder distributions ({@link Methode#COMPLEX}) are
 * captured here for coverage but transpile to a marked placeholder — they are multi-step
 * allocations, not one forward rule.
 *
 * <p>The §9.7.4 "afgerond op N decimalen naar beneden" and §9.7.5 "onverdeelde rest" modifiers
 * (the {@code meervoudigCriterium} bullet form) ride on the equal-shares / naar-rato spine when
 * no group ordering and no per-recipient maximum is present: each share is floored to
 * {@code afrondingDecimalen} (the §6.1.2 explicit afronding the spec requires before assigning a
 * division result to a bounded target), and {@code restantAttribuut} on the {@code bronType}
 * receives the remainder (total − Σ shares). With grouping or a maximum the distribution is
 * genuinely multi-pass and stays {@link Methode#COMPLEX}.
 *
 * @param ratoAttribuut      for {@link Methode#NAAR_RATO}, the bare recipient weight attribute
 *                           (lidwoord stripped, so it maps straight to a getter); else null
 * @param afrondingDecimalen §9.7.4 floor each share to this many decimals (always "naar beneden");
 *                           null = no rounding
 * @param restantAttribuut   §9.7.5 the bare {@code bronType} attribute that receives the
 *                           undistributed remainder; null = none
 */
public record ResolvedVerdeling(String naam, Objecttype bronType, String bronAttribuut,
                                Objecttype doelType, String doelAttribuut,
                                Methode methode, String ratoAttribuut,
                                Integer afrondingDecimalen, String restantAttribuut,
                                Conditie voorwaarde, Geldigheid geldigheid) {
    public enum Methode { GELIJKE_DELEN, NAAR_RATO, COMPLEX }
}
