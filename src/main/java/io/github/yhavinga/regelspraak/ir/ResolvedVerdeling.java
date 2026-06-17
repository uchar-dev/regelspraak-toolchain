package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

/**
 * A resolved §13.4.10 verdeling: a source amount ({@code bronAttribuut} of
 * {@code bronType}) is distributed over a recipient attribute ({@code doelAttribuut}
 * of {@code doelType}, the objects related to the source) by some method.
 *
 * Only {@link Methode#GELIJKE_DELEN equal shares} has a clean single-rule Drools
 * form (total / count); proportional and group/ordering/remainder distributions are
 * captured here for coverage but transpile to a marked placeholder — they are
 * multi-step allocations, not one forward rule.
 *
 * @param ratoAttribuut for {@link Methode#NAAR_RATO}, the weight attribute; else null
 */
public record ResolvedVerdeling(String naam, Objecttype bronType, String bronAttribuut,
                                Objecttype doelType, String doelAttribuut,
                                Methode methode, String ratoAttribuut, Conditie voorwaarde) {
    public enum Methode { GELIJKE_DELEN, NAAR_RATO, COMPLEX }
}
