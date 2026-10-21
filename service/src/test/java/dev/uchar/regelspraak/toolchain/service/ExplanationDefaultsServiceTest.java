package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ?explanation=true} on a defaults group ({@code Uitzondering op}, {@code Afwijking van}, {@code Uitzonderingen
 * ongeordend op}; uchar's RUNTIME-SEMANTICS ch. 7): one sentence per object and group, from what the engine decided
 * ({@code DroolsBackend.decisionsOf}): the variant that decided by the name of its block, with its condition; the
 * variants before it with the reason they did not decide; the variants after it by name, as displaced; and where no
 * variant decided, what the target holds. Two members of an unordered block that decide differently are named as a
 * conflict. Before, the explanation went over the ordinary rules alone and said nothing of a group.
 *
 * <p>Both starts of the service: from sources the pointers of the engine lay on the model of the entry point's scope,
 * from a {@code uchar-ir-v1} document on what uchar's loader read of it. The scenario route is pinned in
 * {@code ExplanationDefaultsTest}, with the same model and the same sentences.
 */
class ExplanationDefaultsServiceTest {

    private static final String SOURCE = """
        Objecttype de Jutter (mv: Jutters)
            de leeftijd Numeriek (geheel getal);
            het aantal vondsten Numeriek (geheel getal);
            de bonus Numeriek (getal met 2 decimalen);
            de premie Numeriek (getal met 2 decimalen);
            is ingezetene kenmerk (bijvoeglijk);
            is in dienst kenmerk (bijvoeglijk);
            het recht op de penning kenmerk (bezittelijk);

        Regel penning
            geldig altijd
                Een Jutter heeft recht op de penning
                indien hij ingezetene is.

        // Bron: Jutterswet art. 3
        Uitzondering op penning: uitsluiting bij dienstverband
            geldig altijd
                Een Jutter heeft geen recht op de penning
                indien hij in dienst is.

        Uitzondering op uitsluiting bij dienstverband: herstel bij veel vondsten
            geldig altijd
                Een Jutter heeft recht op de penning
                indien zijn aantal vondsten groter is dan 10.

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
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Uitleg van defaults-groepen","version":"1"},
          "entrypoints": [ { "name":"penning","path":"penning",
            "scope": {"ruleNames": ["penning", "premie"]},
            "input": ["Jutter"], "output": ["Jutter:recht op de penning", "Jutter.premie"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
        { "instances": [
            { "type": "Jutter", "id": "J1", "attributes": { "leeftijd": 30, "aantal vondsten": 0 },
              "characteristics": { "ingezetene": true } },
            { "type": "Jutter", "id": "J2", "attributes": { "leeftijd": 30, "aantal vondsten": 5, "bonus": 20.00 },
              "characteristics": { "ingezetene": true, "in dienst": true } },
            { "type": "Jutter", "id": "J3", "attributes": { "leeftijd": 30, "aantal vondsten": 12 },
              "characteristics": { "ingezetene": true, "in dienst": true } },
            { "type": "Jutter", "id": "J4", "attributes": { "leeftijd": 16, "aantal vondsten": 0 } },
            { "type": "Jutter", "id": "J5", "attributes": { "leeftijd": 30, "aantal vondsten": 0 } },
            { "type": "Jutter", "id": "J6", "attributes": { "leeftijd": 16, "premie": 7.50 } } ] }""";

    private static final String HERSTEL =
            "'herstel bij veel vondsten' (uitzondering op 'uitsluiting bij dienstverband' op 'penning')";

    /** The sentences of {@code ExplanationDefaultsTest}, the scenario route, on the same model and input. */
    private static final List<String> EXPECTED = List.of(
            "J1 heeft recht op de penning volgens de regel 'penning', omdat het onderwerp ingezetene is; 'jeugdpenning'"
                    + " gold niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18; " + HERSTEL + " gold niet,"
                    + " want niet geldt: het onderwerp in dienst is; 'uitsluiting bij dienstverband' gold niet, want"
                    + " niet geldt: het onderwerp in dienst is",
            "J1 heeft als premie 100,00 volgens de regel 'premie', omdat zijn leeftijd (30) is groter dan 17;"
                    + " 'premie uit bonus' gaf geen waarde",
            "J2 heeft geen recht op de penning volgens 'uitsluiting bij dienstverband', omdat het onderwerp ingezetene"
                    + " is en het onderwerp in dienst is; 'jeugdpenning' gold niet, want niet geldt: zijn leeftijd (30)"
                    + " is kleiner dan 18; " + HERSTEL + " gold niet, want niet geldt: zijn aantal vondsten (5) is"
                    + " groter dan 10; verdrongen: de regel 'penning'. Grondslag: Jutterswet art. 3",
            "J2 heeft als premie 20,00 volgens 'premie uit bonus', omdat zijn leeftijd (30) is groter dan 17 en het"
                    + " onderwerp ingezetene is; verdrongen: de regel 'premie'",
            "J3 heeft recht op de penning volgens " + HERSTEL + ", omdat het onderwerp ingezetene is en het onderwerp in"
                    + " dienst is en zijn aantal vondsten (12) is groter dan 10; 'jeugdpenning' gold niet, want niet"
                    + " geldt: zijn leeftijd (30) is kleiner dan 18; verdrongen: 'uitsluiting bij dienstverband', de"
                    + " regel 'penning'",
            "J3 heeft als premie 100,00 volgens de regel 'premie', omdat zijn leeftijd (30) is groter dan 17;"
                    + " 'premie uit bonus' gaf geen waarde",
            "J4 heeft recht op de penning volgens 'jeugdpenning', omdat zijn leeftijd (16) is kleiner dan 18;"
                    + " verdrongen: " + HERSTEL + ", 'uitsluiting bij dienstverband', de regel 'penning'",
            "J4 heeft geen premie, want geen variant besliste; 'premie uit bonus' gold niet, want niet geldt: zijn"
                    + " leeftijd (16) is groter dan 17; de regel 'premie' gold niet, want niet geldt: zijn leeftijd (16)"
                    + " is groter dan 17",
            "J5 heeft geen recht op de penning, want geen variant besliste; 'jeugdpenning' gold niet, want niet geldt:"
                    + " zijn leeftijd (30) is kleiner dan 18; " + HERSTEL + " gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is; 'uitsluiting bij dienstverband' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is; de regel 'penning' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J5 heeft als premie 100,00 volgens de regel 'premie', omdat zijn leeftijd (30) is groter dan 17;"
                    + " 'premie uit bonus' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J6 heeft recht op de penning volgens 'jeugdpenning', omdat zijn leeftijd (16) is kleiner dan 18;"
                    + " verdrongen: " + HERSTEL + ", 'uitsluiting bij dienstverband', de regel 'penning'",
            "J6 houdt als premie de invoerwaarde 7,50, want geen variant besliste; 'premie uit bonus' gold niet, want"
                    + " niet geldt: zijn leeftijd (16) is groter dan 17; de regel 'premie' gold niet, want niet geldt:"
                    + " zijn leeftijd (16) is groter dan 17");

    /** An unordered block on a base (ch. 7.9): on the wire a group-default above the base, whose two members inherit
     *  the base's condition and reconcile as an unordered group. */
    private static final String UNORDERED = """
        Objecttype de Jutter (mv: Jutters)
            de vergoeding Numeriek (geheel getal);
            is ingezetene kenmerk (bijvoeglijk);
            is getroffen door storm kenmerk (bijvoeglijk);
            is getroffen door vloed kenmerk (bijvoeglijk);

        Regel vergoeding
            geldig altijd
                De vergoeding van een Jutter moet gesteld worden op 10
                indien hij ingezetene is.

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

    private static final String UNORDERED_DESCRIPTOR = """
        { "service": {"name":"Uitleg van een conflict","version":"1"},
          "entrypoints": [ { "name":"vergoeding","path":"vergoeding",
            "scope": {"ruleNames": ["vergoeding"]},
            "input": ["Jutter"], "output": ["Jutter.vergoeding"] } ],
          "parameters": {} }""";

    /** K1: both members decide, differently. K2: the first member decides. K3: the second member decides. */
    private static final String UNORDERED_REQUEST = """
        { "instances": [
            { "type": "Jutter", "id": "K1",
              "characteristics": { "ingezetene": true, "getroffen door storm": true, "getroffen door vloed": true } },
            { "type": "Jutter", "id": "K2", "characteristics": { "ingezetene": true, "getroffen door storm": true } },
            { "type": "Jutter", "id": "K3", "characteristics": { "ingezetene": true, "getroffen door vloed": true } } ] }""";

    private static final List<String> UNORDERED_EXPECTED = List.of(
            "K1 heeft geen vergoeding, want er is een conflict in de uitzonderingen op de regel 'vergoeding':"
                    + " 'stormvergoeding' (uitzondering op 'vergoeding') besliste 'heeft als vergoeding 30' en"
                    + " 'vloedvergoeding' (uitzondering op 'vergoeding') besliste 'heeft als vergoeding 45', dus de"
                    + " groep schrijft niets",
            "K2 heeft als vergoeding 30 volgens 'stormvergoeding' (uitzondering op 'vergoeding'), omdat het onderwerp"
                    + " ingezetene is en het onderwerp getroffen door storm is; 'vloedvergoeding' (uitzondering op"
                    + " 'vergoeding') besliste niet anders; verdrongen: de regel 'vergoeding'",
            "K3 heeft als vergoeding 45 volgens 'vloedvergoeding' (uitzondering op 'vergoeding'), omdat het onderwerp"
                    + " ingezetene is en het onderwerp getroffen door vloed is; 'stormvergoeding' (uitzondering op"
                    + " 'vergoeding') gold niet, want niet geldt: het onderwerp getroffen door storm is; verdrongen: de"
                    + " regel 'vergoeding'");

    /** A subtype's own ordinary rule replaces the inherited base (ch. 7.10); the engine names it by its rule version, which
     *  the explanation lays on the rules of the scope's model, or on those uchar's loader read. */
    private static final String INHERITANCE = """
        Objecttype het Recht (mv: Rechten)
            de uitkering Numeriek (geheel getal);
            is betwist kenmerk (bijvoeglijk);
            is erkend kenmerk (bijvoeglijk);

        Objecttype het Wrakrecht is een Recht (mv: Wrakrechten)
            is gezonken kenmerk (bijvoeglijk);

        Regel uitkering
            geldig altijd
                De uitkering van een Recht moet gesteld worden op 10
                indien hij erkend is.

        Uitzondering op uitkering: betwiste uitkering
            geldig altijd
                De uitkering van een Recht moet gesteld worden op 99
                indien hij betwist is.

        Regel uitkering van een wrakrecht
            geldig altijd
                De uitkering van een Wrakrecht moet gesteld worden op 200
                indien hij gezonken is.
        """;

    private static final String INHERITANCE_DESCRIPTOR = """
        { "service": {"name":"Uitleg van een samengestelde stapel","version":"1"},
          "entrypoints": [ { "name":"uitkering","path":"uitkering",
            "scope": {"ruleNames": ["uitkering", "uitkering van een wrakrecht"]},
            "input": ["Recht"], "output": ["Recht.uitkering", "Wrakrecht.uitkering"] } ],
          "parameters": {} }""";

    private static final String INHERITANCE_REQUEST = """
        { "instances": [
            { "type": "Recht", "id": "R1", "characteristics": { "erkend": true } },
            { "type": "Wrakrecht", "id": "W1", "characteristics": { "gezonken": true } },
            { "type": "Wrakrecht", "id": "W2", "characteristics": { "erkend": true, "betwist": true } },
            { "type": "Wrakrecht", "id": "W3", "characteristics": { "erkend": true } } ] }""";

    /** The sentences of {@code ExplanationDefaultsTest}, the scenario route. */
    private static final List<String> INHERITANCE_EXPECTED = List.of(
            "R1 heeft als uitkering 10 volgens de regel 'uitkering', omdat het onderwerp erkend is; 'betwiste"
                    + " uitkering' gold niet, want niet geldt: het onderwerp betwist is",
            "W1 heeft als uitkering 200 volgens de regel 'uitkering van een wrakrecht', omdat het onderwerp gezonken"
                    + " is; 'betwiste uitkering' gold niet, want niet geldt: het onderwerp erkend is",
            "W2 heeft als uitkering 99 volgens 'betwiste uitkering', omdat het onderwerp erkend is en het onderwerp"
                    + " betwist is; verdrongen: de regel 'uitkering van een wrakrecht'",
            "W3 heeft geen uitkering, want geen variant besliste; 'betwiste uitkering' gold niet, want niet geldt:"
                    + " het onderwerp betwist is; de regel 'uitkering van een wrakrecht' gold niet, want niet geldt:"
                    + " het onderwerp gezonken is");

    @Test
    void from_sources_a_stack_composed_by_inheritance_follows_the_order_the_engine_gives() throws Exception {
        assertEquals(INHERITANCE_EXPECTED,
                explanation(fromSources(INHERITANCE, INHERITANCE_DESCRIPTOR), "uitkering", INHERITANCE_REQUEST));
    }

    @Test
    void from_a_document_a_stack_composed_by_inheritance_follows_the_order_the_engine_gives() throws Exception {
        assertEquals(INHERITANCE_EXPECTED,
                explanation(fromDocument(INHERITANCE, INHERITANCE_DESCRIPTOR), "uitkering", INHERITANCE_REQUEST));
    }

    @Test
    void from_sources_every_defaults_group_gets_a_sentence_from_what_the_engine_decided() throws Exception {
        assertEquals(EXPECTED, explanation(fromSources(SOURCE, DESCRIPTOR), "penning", REQUEST));
    }

    @Test
    void from_a_document_every_defaults_group_gets_a_sentence_from_what_the_engine_decided() throws Exception {
        assertEquals(EXPECTED, explanation(fromDocument(SOURCE, DESCRIPTOR), "penning", REQUEST));
    }

    @Test
    void from_sources_an_unordered_block_names_its_conflict() throws Exception {
        assertEquals(UNORDERED_EXPECTED,
                explanation(fromSources(UNORDERED, UNORDERED_DESCRIPTOR), "vergoeding", UNORDERED_REQUEST));
    }

    @Test
    void from_a_document_an_unordered_block_names_its_conflict() throws Exception {
        assertEquals(UNORDERED_EXPECTED,
                explanation(fromDocument(UNORDERED, UNORDERED_DESCRIPTOR), "vergoeding", UNORDERED_REQUEST));
    }

    private static DecisionCore fromSources(String source, String descriptor) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("jutters.rgs", source);
        return DecisionCore.assemble(descriptor, sources, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument(String source, String descriptor) {
        return DecisionCore.buildFromDocument(descriptor, UcharBuild.document(Resolver.resolve(source)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static List<String> explanation(DecisionCore core, String path, String request) throws Exception {
        JsonNode answer = core.process(path, DescriptorLoader.MAPPER.readTree(request), true);
        List<String> sentences = new ArrayList<>();
        for (JsonNode sentence : answer.get("explanation")) sentences.add(sentence.asText());
        return sentences;
    }
}
