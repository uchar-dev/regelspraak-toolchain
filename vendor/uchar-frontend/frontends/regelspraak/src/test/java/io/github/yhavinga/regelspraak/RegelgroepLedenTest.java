package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code Regelgroep}'s members on the wire are RULE names (IR-SPEC ch. 3.9, WF-033: every member resolves to a
 * rule form). A decision table is no rule form — it desugars to one rule per row — so a group that holds one lists
 * the rules of its rows. Measured on the frontend before this fix (a8ab1639): the table's own name went out as
 * the member, silently, and the verifier refused every such document ({@code WF-033 FAIL /regelGroepen/0: group
 * member 'Bergingspremie' does not resolve to a rule form}), with or without anything else in it.
 */
class RegelgroepLedenTest {

    static final String GROEP = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
            de bergingspremie Numeriek (niet-negatief geheel getal);
            de bergingsbonus Numeriek (niet-negatief geheel getal);

        Regelgroep berging
        Regel bergingsbonus
            geldig altijd
                De bergingsbonus van een Jutter moet berekend worden als zijn aantal geborgen wrakken maal 2.

        Beslistabel Bergingspremie
            geldig altijd
        | | de bergingspremie van een Jutter moet gesteld worden op | indien zijn aantal geborgen wrakken groter is dan |
        |---|---|---|
        | 1 | 10 | 5 |
        | 2 | 20 | 10 |
        """;

    @Test void een_gegroepeerde_beslistabel_noemt_haar_rijen_als_leden() {
        RegelSpraakModel m = Resolver.resolve(GROEP);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        assertEquals(List.of("bergingsbonus", "Bergingspremie 1", "Bergingspremie 2"), m.regelGroepen.get(0).leden(),
                "de leden zijn regelnamen, in geschreven volgorde; de tabel staat voor haar rijen");
        // every member names a rule the model carries, which is what WF-033 asks of the wire
        for (String lid : m.regelGroepen.get(0).leden())
            assertTrue(m.resolvedRegels.stream().anyMatch(r -> r.naam().equals(lid)), () -> "lid '" + lid + "' is geen regel");
    }
}
