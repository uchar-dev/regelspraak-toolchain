package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An enumeration value whose designation in the chosen vocabulary differs from the declared value, such as a code
 * ({@code (cgm: 01)}), stands in the explanation as {@code <designation> · <declared value>}: alone, the code does not
 * say what it stands for. That holds for the value read from the bean and for the value compared against. A
 * designation that differs only as uchar does not tell declared names apart ({@code Namen.norm}), and a value without a
 * designation, stay as they were; the canonical choice does not change.
 */
class UitlegCodeWithMeaningTest {

    private static final String DOCUMENT = """
            Vocabulaire cgm (taal: nl)

            Domein Reden einde is van het type Enumeratie
                'Hoogte inkomsten uit verzekerde arbeid' (cgm: 01)
                'Overlijden' (cgm: OVERLIJDEN)
                'Onbekend'

            Objecttype de Uitkering (mv: Uitkeringen)
                de reden (cgm: REDEN_EINDE) Reden einde;
                is beëindigd door inkomsten kenmerk (bijvoeglijk);

            Regel einde door inkomsten
                geldig altijd
                    Een Uitkering is beëindigd door inkomsten indien zijn reden gelijk is aan 'Hoogte inkomsten uit verzekerde arbeid'.

            Scenario drie redenen
            Gegeven is de volgende situatie:
            een Uitkering U1
            - de reden is 'Hoogte inkomsten uit verzekerde arbeid'
            een Uitkering U2
            - de reden is 'Overlijden'
            een Uitkering U3
            - de reden is 'Onbekend'
            Met als verwacht resultaat:
            - U1 is beëindigd door inkomsten
            - U2 is niet beëindigd door inkomsten
            - U3 is niet beëindigd door inkomsten
            """;

    private static List<String> uitleg(String vocabulary) {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithUitleg(ScenarioResolver.resolve(DOCUMENT), vocabulary);
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).uitlegError(), u.get(0)::uitlegError);
        return u.get(0).uitleg();
    }

    @Test
    void a_code_stands_with_its_value_behind_it() {
        assertEquals(List.of(
                "U1 is beëindigd door inkomsten, omdat zijn reden (01 · Hoogte inkomsten uit verzekerde arbeid)"
                        + " is gelijk aan 01 · Hoogte inkomsten uit verzekerde arbeid",
                "U2 is niet beëindigd door inkomsten, want niet geldt: zijn reden (OVERLIJDEN)"
                        + " is gelijk aan 01 · Hoogte inkomsten uit verzekerde arbeid",
                "U3 is niet beëindigd door inkomsten, want niet geldt: zijn reden (Onbekend)"
                        + " is gelijk aan 01 · Hoogte inkomsten uit verzekerde arbeid"),
                uitleg("cgm"));
    }

    @Test
    void canonical_names_only_the_value() {
        assertEquals(List.of(
                "U1 is beëindigd door inkomsten, omdat zijn reden (Hoogte inkomsten uit verzekerde arbeid)"
                        + " is gelijk aan Hoogte inkomsten uit verzekerde arbeid",
                "U2 is niet beëindigd door inkomsten, want niet geldt: zijn reden (Overlijden)"
                        + " is gelijk aan Hoogte inkomsten uit verzekerde arbeid",
                "U3 is niet beëindigd door inkomsten, want niet geldt: zijn reden (Onbekend)"
                        + " is gelijk aan Hoogte inkomsten uit verzekerde arbeid"),
                uitleg(Naming.CANONICAL));
    }
}
