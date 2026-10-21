package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The explanation names an object a creation made by the name uchar's canonical text gives it,
 * {@code «"<creatie>"»(<onderwerp>)#1}, with the subject as the explanation names it (card
 * explanation-made-object-names), on both routes of the decision service: from sources and from an IR document. The
 * model, the objects and the sentences are those of {@code ExplanationMadeObjectsTest} (the scenario route): two
 * creations give a Jutter a penning, a third gives a penning above 10 € a kwitantie, and two rules on the Jutter fold
 * over his penningen. The parts carry the same name as {@code SUBJECT}.
 */
class ExplanationMadeObjectsServiceTest {

    private static final String SOURCE = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €

            Objecttype de Jutter (mv: Jutters)
                de vangst Numeriek (geheel getal) met eenheid €;
                is beloond kenmerk (bijvoeglijk);
                is rijk kenmerk (bijvoeglijk);

            Objecttype de Penning (mv: Penningen)
                het bedrag Numeriek (geheel getal) met eenheid €;
                is waardevol kenmerk (bijvoeglijk);

            Objecttype de Kwitantie (mv: Kwitanties)
                het bedrag Numeriek (geheel getal) met eenheid €;
                is geldig kenmerk (bijvoeglijk);

            Feittype penningtoekenning
                de houder Jutter
                de penning (mv: penningen) Penning
            Eén houder heeft meerdere penningen

            Feittype kwitering
                de gekwiteerde penning Penning
                de kwitantie Kwitantie
            Eén gekwiteerde penning heeft één kwitantie

            Regel geef een vangstpenning
                geldig altijd
                    Een Jutter heeft een penning met bedrag gelijk aan zijn vangst
                    indien zijn vangst groter is dan 0 €.

            Regel geef een bonuspenning
                geldig altijd
                    Een Jutter heeft een penning met bedrag gelijk aan 5 €
                    indien zijn vangst groter is dan 20 €.

            Regel schrijf een kwitantie
                geldig altijd
                    Een Penning heeft een kwitantie met bedrag gelijk aan zijn bedrag
                    indien zijn bedrag groter is dan 10 €.

            Regel waardevolle penning
                geldig altijd
                    Een Penning is waardevol
                    indien zijn bedrag groter is dan 10 €.

            Regel geldige kwitantie
                geldig altijd
                    Een Kwitantie is geldig
                    indien zijn bedrag groter is dan 0 €.

            Regel beloond
                geldig altijd
                    Een Jutter is beloond
                    indien alle penningen van de Jutter waardevol zijn.

            Regel rijk
                geldig altijd
                    Een Jutter is rijk
                    indien ten minste één van de penningen van de Jutter waardevol is.
            """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Gemaakte objecten","version":"1"},
          "entrypoints": [ { "name":"jutters","path":"jutters",
            "scope": {"ruleNames": ["geef een vangstpenning", "geef een bonuspenning", "schrijf een kwitantie",
                                    "waardevolle penning", "geldige kwitantie", "beloond", "rijk"]},
            "input": ["Jutter", "Penning"],
            "output": ["Jutter:beloond", "Jutter:rijk"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
            { "instances": [
                { "type": "Jutter", "id": "J1", "attributes": { "vangst": 30 } },
                { "type": "Jutter", "id": "J2", "attributes": { "vangst": 8 } },
                { "type": "Penning", "id": "P1", "attributes": { "bedrag": 15 }, "roles": { "houder": "J2" } } ] }""";

    private static final String BONUS_J1 = "«\"geef een bonuspenning\"»(J1)#1";
    private static final String CATCH_J1 = "«\"geef een vangstpenning\"»(J1)#1";
    private static final String CATCH_J2 = "«\"geef een vangstpenning\"»(J2)#1";
    private static final String RECEIPT_P1 = "«\"schrijf een kwitantie\"»(P1)#1";
    private static final String RECEIPT_CATCH_J1 = "«\"schrijf een kwitantie\"»(" + CATCH_J1 + ")#1";

    private static final String ALL = "aan de voorwaarde \"alle penningen van de Jutter waardevol zijn\"";
    private static final String AT_LEAST_ONE =
            "aan de voorwaarde \"ten minste één van de penningen van de Jutter waardevol is\"";

    private static final List<String> EXPECTED = List.of(
            "J1 is niet beloond, want " + ALL + " is niet voldaan: niet voor " + BONUS_J1 + " (1 van de 2)",
            "J1 is rijk, omdat " + AT_LEAST_ONE + " is voldaan: voor 1 van de 2 (" + CATCH_J1 + ")",
            "J2 is niet beloond, want " + ALL + " is niet voldaan: niet voor " + CATCH_J2 + " (1 van de 2)",
            "J2 is rijk, omdat " + AT_LEAST_ONE + " is voldaan: voor 1 van de 2 (P1)",
            "P1 is waardevol, omdat zijn bedrag (15 EUR) is groter dan 10 EUR",
            BONUS_J1 + " is niet waardevol, want niet geldt: zijn bedrag (5 EUR) is groter dan 10 EUR",
            CATCH_J1 + " is waardevol, omdat zijn bedrag (30 EUR) is groter dan 10 EUR",
            CATCH_J2 + " is niet waardevol, want niet geldt: zijn bedrag (8 EUR) is groter dan 10 EUR",
            RECEIPT_P1 + " is geldig, omdat zijn bedrag (15 EUR) is groter dan 0 EUR",
            RECEIPT_CATCH_J1 + " is geldig, omdat zijn bedrag (30 EUR) is groter dan 0 EUR");

    /** The parts of two sentences: a made object as a member of a fold, and as the subject. */
    private static final Map<Integer, String> PARTS = Map.of(
            0, "SUBJECT«J1» « is niet » NAME{declaration=KENMERK, owner=Jutter, canonical=beloond}«beloond» «, »"
                    + " CONNECTIVE«want» « » CONDITION{holds=false, quantifier=all}[«aan de voorwaarde \"»"
                    + " QUOTE«alle penningen van de Jutter waardevol zijn» «\" is niet voldaan: »"
                    + " MEMBERS{members=2, holding=1, unjudged=0}[«niet voor » SUBJECT«" + BONUS_J1 + "»"
                    + " « (1 van de 2)»]]",
            9, "SUBJECT«" + RECEIPT_CATCH_J1 + "» « is » NAME{declaration=KENMERK, owner=Kwitantie,"
                    + " canonical=geldig}«geldig» «, » CONNECTIVE«omdat» « » CONDITION{holds=true}["
                    + "QUOTE{declaration=ATTRIBUUT, owner=Kwitantie, canonical=bedrag}«zijn bedrag» « (» VALUE«30 EUR»"
                    + " «) is groter dan » VALUE«0 EUR»]");

    private static DecisionCore fromSources() {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument() {
        return DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static void assertNamed(DecisionCore core) throws Exception {
        JsonNode answer = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        List<String> sentences = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sentences.add(z.asText());
        assertEquals(EXPECTED, sentences);
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        for (Map.Entry<Integer, String> e : new java.util.TreeMap<>(PARTS).entrySet())
            assertEquals(e.getValue(), ExplanationPartsCheck.render(answer.get("explanationParts").get(e.getKey())),
                    "zin " + e.getKey() + ": " + EXPECTED.get(e.getKey()));
    }

    @Test
    void from_sources_a_made_object_is_named_by_its_creation_and_its_subject() throws Exception {
        assertNamed(fromSources());
    }

    @Test
    void from_a_document_a_made_object_is_named_by_its_creation_and_its_subject() throws Exception {
        assertNamed(fromDocument());
    }
}
