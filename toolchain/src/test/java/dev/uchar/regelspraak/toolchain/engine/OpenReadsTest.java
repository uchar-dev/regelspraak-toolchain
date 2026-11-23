package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.ir.Condition;
import dev.uchar.regelspraak.ir.ResolvedRule;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenReads#reads}, whether the filter of a quantifier fold can read an open member on a member of its range, on an
 * end state written by hand. uchar's Drools backend refuses a sum whose fallback reads a member that can be open
 * (DROOLS-370, form {@code set-fold}), so no session reaches this filter and the explanation of its fold cannot be asked;
 * the read is pinned here, where it does not depend on that refusal.
 */
class OpenReadsTest {

    /** The filter compares the sum over the onderdelen of a Vondst, or its taxatie where it has none. uchar evaluates that
     *  fallback always (RUNTIME-SEMANTICS ch. 5.11), also over an empty range: on V2, without onderdelen and without a
     *  taxatie, the filter reads the open taxatie; on V1, whose taxatie is given, it reads nothing open. */
    @Test
    void the_fallback_of_a_sum_in_a_filter_is_read_over_an_empty_range() throws Exception {
        RegelSpraakModel model = Resolver.resolve("""
            Afleiden uit leeg: nee

            Objecttype de Jutter (mv: Jutters) (bezield)
                is kundig kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
                de taxatie Numeriek (geheel getal);

            Objecttype het Onderdeel (mv: Onderdelen)
                de massa Numeriek (geheel getal);

            Feittype vangst
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
                één jutter heeft meerdere vondsten

            Feittype samenstelling
                de bron Vondst
                het onderdeel (mv: onderdelen) Onderdeel
                één bron heeft meerdere onderdelen

            Regel kundig
                geldig altijd
                    Een Jutter is kundig indien het gewicht van alle vondsten van de Jutter die aan de volgende voorwaarde voldoen: de som van de massa van alle onderdelen van de Vondst of zijn taxatie als die er niet zijn is groter dan 10 groter is dan 10.
            """);
        if (!model.errors().isEmpty()) throw new IllegalArgumentException(model.errors().toString());
        Verbaliser.Document document = Verbaliser.Document.of(
                IrLoader.loadWithPositions(new ObjectMapper().readTree(UcharBuild.document(model))));
        Condition filter = null;
        for (ResolvedRule r : document.rules())
            if (r.name().equals("kundig") && r.condition() instanceof Condition.QuantifierFold q) filter = q.filter();
        if (filter == null) throw new IllegalStateException("de regel 'kundig' heeft geen kwantorvouw met een filter");

        Object j1 = new Object(), v1 = new Object(), v2 = new Object();
        EndState end = new EndState(List.of(
                new EndState.EndObject(j1, "J1", "Jutter", null, null, List.of(), List.of()),
                new EndState.EndObject(v1, "V1", "Vondst", null, null, List.of(), List.of()),
                new EndState.EndObject(v2, "V2", "Vondst", null, null, List.of(),
                        List.of(new EndState.Open(false, "taxatie", null)))),
                List.of(new EndState.Link("vangst", "jutter", j1, "vondst", v1, false),
                        new EndState.Link("vangst", "jutter", j1, "vondst", v2, false)),
                List.of(), List.of());
        OpenReads reads = new OpenReads(document, List.of(), end);

        assertTrue(reads.reads(filter, v2, j1));
        assertFalse(reads.reads(filter, v1, j1));
    }
}
