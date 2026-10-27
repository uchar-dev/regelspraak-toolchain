package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card explanation-part-groups: a part a consumer may leave out carries its glue, so that a sentence without every
 * {@code MEMBERS}, every {@code VARIANT} with {@code decided: false} and every {@code READING} is still a sentence. The
 * colon before the members of a fold is the first text of {@code MEMBERS}; each variant of a defaults group that did
 * not decide is a {@code VARIANT} with its separator (before the decider, beside it in an unordered group, displaced
 * after it, or where no variant decided); a value the engine read stands with its brackets in a {@code READING}. The
 * plain sentences do not change.
 *
 * <p>Both starts of the service: from sources and from a {@code uchar-ir-v1} document.
 */
class ExplanationPartGroupsServiceTest {

    private static final String SOURCE = """
        Objecttype de Jutter (mv: Jutters)
            de leeftijd Numeriek (geheel getal);
            de bonus Numeriek (getal met 2 decimalen);
            de premie Numeriek (getal met 2 decimalen);
            de vergoeding Numeriek (geheel getal);
            de laatste vinddag Datum in dagen;
            is ingezetene kenmerk (bijvoeglijk);
            is in dienst kenmerk (bijvoeglijk);
            is getroffen door storm kenmerk (bijvoeglijk);
            is getroffen door vloed kenmerk (bijvoeglijk);
            is gelukkig kenmerk (bijvoeglijk);
            het recht op de penning kenmerk (bezittelijk);

        Objecttype de Vondst (mv: Vondsten)
            de vinddag Datum in dagen;
            is waardevol kenmerk (bijvoeglijk);

        Feittype vondst van een jutter
            de jutter Jutter
            de vondst (mv: vondsten) Vondst
        Eén jutter heeft meerdere vondsten

        Regel penning
            geldig altijd
                Een Jutter heeft recht op de penning
                indien hij ingezetene is.

        Uitzondering op penning: uitsluiting bij dienstverband
            geldig altijd
                Een Jutter heeft geen recht op de penning
                indien hij in dienst is.

        Afwijking van penning: jeugdpenning
            geldig altijd
                Een Jutter heeft recht op de penning
                indien zijn leeftijd kleiner is dan 18.

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 100,00
                indien zijn leeftijd groter is dan 17.

        Uitzondering op premie: premie uit bonus
            geldig altijd
                De premie van een Jutter moet gesteld worden op zijn bonus
                indien hij ingezetene is.

        Regel vergoeding
            geldig altijd
                De vergoeding van een Jutter moet gesteld worden op 10
                indien hij ingezetene is.

        Regel laatste vinddag
            geldig altijd
                De laatste vinddag van een Jutter moet berekend worden als de laatste van de vinddag van alle vondsten van de Jutter.

        Regel gelukkig
            geldig altijd
                Een Jutter is gelukkig
                indien alle vondsten van de Jutter waardevol zijn.

        Uitzonderingen ongeordend op vergoeding:
        Regel stormvergoeding
            geldig altijd
                De vergoeding van een Jutter moet gesteld worden op 30
                indien hij getroffen door storm is.
        Regel vloedvergoeding
            geldig altijd
                De vergoeding van een Jutter moet gesteld worden op 45
                indien hij getroffen door vloed is.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Weglaatbare stukken","version":"1"},
          "entrypoints": [ { "name":"jutters","path":"jutters",
            "scope": {"ruleNames": ["penning", "premie", "vergoeding", "laatste vinddag", "gelukkig"]},
            "input": ["Jutter"], "output": ["Jutter.premie"] } ],
          "parameters": {} }""";

    /** J1: the bases decide below variants that did not hold ('premie uit bonus' holds and gives no value); two
     *  finds, one not valuable. J2: the deviation decides the penning, and the exception and the base after it are
     *  displaced; no variant of the premie holds; in the unordered group the storm decides and the flood decided
     *  nothing else. J3: no variant of the penning or the vergoeding decides, and there is no find. */
    private static final String REQUEST = """
        { "instances": [
            { "type": "Jutter", "id": "J1", "attributes": { "leeftijd": 30 },
              "characteristics": { "ingezetene": true } },
            { "type": "Jutter", "id": "J2", "attributes": { "leeftijd": 16, "bonus": 20.00 },
              "characteristics": { "ingezetene": true, "in dienst": true, "getroffen door storm": true } },
            { "type": "Jutter", "id": "J3", "attributes": { "leeftijd": 30 } },
            { "type": "Vondst", "id": "V1", "attributes": { "vinddag": "2026-02-01" },
              "characteristics": { "waardevol": true }, "roles": { "jutter": "J1" } },
            { "type": "Vondst", "id": "V2", "attributes": { "vinddag": "2026-03-01" }, "roles": { "jutter": "J1" } } ] }""";

    /** Every sentence without its omissible parts. */
    private static final List<String> EXPECTED = List.of(
            "J1 is niet gelukkig, want aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is niet"
                    + " voldaan",
            "J1 heeft recht op de penning volgens de regel 'penning', omdat het onderwerp ingezetene is",
            "J1 heeft als premie 100,00 volgens de regel 'premie': 100,00, omdat zijn leeftijd is groter dan 17",
            "J1 heeft als vergoeding 10 volgens de regel 'vergoeding': 10, omdat het onderwerp ingezetene is",
            "J1 heeft als laatste vinddag 01-03-2026 volgens de regel 'laatste vinddag' (onvoorwaardelijk): de laatste"
                    + " van de vinddag van alle vondsten van de Jutter",
            "J2 is gelukkig, omdat aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is voldaan",
            "J2 heeft recht op de penning volgens 'jeugdpenning', omdat zijn leeftijd is kleiner dan 18",
            "J2 heeft geen premie, want geen variant besliste",
            "J2 heeft als vergoeding 30 volgens 'stormvergoeding' (uitzondering op 'vergoeding'): 30, omdat het"
                    + " onderwerp ingezetene is en het onderwerp getroffen door storm is",
            "J2 heeft geen laatste vinddag, want de regel 'laatste vinddag' gaf geen waarde (onvoorwaardelijk): de"
                    + " laatste van de vinddag van alle vondsten van de Jutter",
            "J3 is gelukkig, omdat aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is voldaan",
            "J3 heeft geen recht op de penning, want geen variant besliste",
            "J3 heeft als premie 100,00 volgens de regel 'premie': 100,00, omdat zijn leeftijd is groter dan 17",
            "J3 heeft geen vergoeding, want geen variant besliste",
            "J3 heeft geen laatste vinddag, want de regel 'laatste vinddag' gaf geen waarde (onvoorwaardelijk): de"
                    + " laatste van de vinddag van alle vondsten van de Jutter");

    @Test
    void from_sources_a_sentence_without_its_omissible_parts_is_a_sentence() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("jutters.rgs", SOURCE);
        assertReduced(DecisionCore.assemble(DESCRIPTOR, sources, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
    }

    @Test
    void from_a_document_a_sentence_without_its_omissible_parts_is_a_sentence() throws Exception {
        assertReduced(DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null));
    }

    private static void assertReduced(DecisionCore core) throws Exception {
        JsonNode answer = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        List<String> given = new ArrayList<>();
        for (JsonNode sentence : answer.get("explanationParts"))
            given.add(ExplanationPartsCheck.withoutOmissibleParts(sentence));
        assertEquals(String.join("\n", EXPECTED), String.join("\n", given));
    }
}
