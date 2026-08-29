package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * A resolved §13.4.10 verdeling: a source amount ({@code bronAttribuut} of
 * {@code bronType}) is distributed over a recipient attribute ({@code doelAttribuut}
 * of {@code doelType}, the objects related to the source) by some method.
 *
 * {@link Methode#GELIJKE_DELEN equal shares} (total / count) and {@link Methode#NAAR_RATO
 * proportional} (total · weight / sum-of-weights) each have a clean single forward-rule
 * Drools form. {@link Methode#OP_VOLGORDE} (§9.7.2 "op volgorde van toenemende/afnemende
 * \<criterium\>, bij een even groot criterium ...") is a genuinely multi-pass ORDERED allocation:
 * recipients are partitioned into groups by {@code groepeerAttribuut} and the pot is depleted
 * group-by-group in sort order, each group splitting its entry-pot among its members (naar-rato by
 * {@code ratoAttribuut}, or equal shares when that is null), carrying the undistributed remainder
 * (after the §9.7.3 cap / §9.7.4 floor) to the next group. It lowers via the registered
 * {@code rsVerdeelOpVolgorde} helper that computes the whole allocation in one shot. Only the truly
 * unhandled forms — an unresolvable join, a non-numeric ordering/weight key, or a maximum on a
 * gelijke-delen tie — stay {@link Methode#COMPLEX} (a marked placeholder).
 *
 * <p>The §9.7.3 "met een maximum van", §9.7.4 "afgerond op N decimalen naar beneden" and §9.7.5
 * "onverdeelde rest" modifiers (the {@code meervoudigCriterium} bullet form) ride on every fireable
 * method. For GELIJKE_DELEN / NAAR_RATO without ordering, each share is capped at {@code maximumAttribuut}
 * (§9.7.3, naar-rato only — the surplus is NOT redistributed, it falls to the rest), then floored to
 * {@code afrondingDecimalen} (the §6.1.2 explicit afronding the spec requires before assigning a division
 * result to a bounded target). For OP_VOLGORDE the cap and floor are applied <em>inside</em> the helper,
 * group by group, so the carry-over reflects the capped+floored shares (the §9.7.5 worked example,
 * Tabel 18). {@code restantAttribuut} on the {@code bronType} receives the remainder (total − Σ shares)
 * via a separate method-agnostic rule.
 *
 * @param ratoAttribuut      for {@link Methode#NAAR_RATO} the bare recipient weight attribute, and for
 *                           {@link Methode#OP_VOLGORDE} the within-group tie weight ("bij een even groot
 *                           criterium naar rato van \<gewicht\>"); null for a gelijke-delen tie. The
 *                           lidwoord is stripped, so it maps straight to a getter.
 * @param maximumAttribuut   §9.7.3 the bare recipient ({@code doelType}) attribute capping each share
 *                           (naar-rato / op-volgorde-with-naar-rato-tie only); null = no cap
 * @param afrondingDecimalen §9.7.4 floor each share to this many decimals (always "naar beneden");
 *                           null = no rounding
 * @param restantAttribuut   §9.7.5 the bare {@code bronType} attribute that receives the
 *                           undistributed remainder; null = none
 * @param groepeerAttribuut  §9.7.2 the bare recipient ordering key partitioning the recipients into
 *                           groups; null unless {@link Methode#OP_VOLGORDE}
 * @param afnemend           §9.7.2 true = descending ("afnemende"), false = ascending ("toenemende");
 *                           read only when {@link Methode#OP_VOLGORDE}
 */
public record ResolvedVerdeling(String naam, Objecttype bronType, String bronAttribuut,
                                Objecttype doelType, String doelAttribuut,
                                Methode methode, String ratoAttribuut, String maximumAttribuut,
                                Integer afrondingDecimalen, String restantAttribuut,
                                String groepeerAttribuut, boolean afnemend,
                                Conditie voorwaarde, Geldigheid geldigheid) {
    public enum Methode { GELIJKE_DELEN, NAAR_RATO, OP_VOLGORDE, COMPLEX }
}
