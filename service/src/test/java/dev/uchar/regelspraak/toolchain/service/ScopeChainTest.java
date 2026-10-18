package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.graph.ProducerConsumer.ElementKey;
import dev.uchar.regelspraak.graph.RuleGraph;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain half of {@link ScopeCompletenessTest}: the scope-completeness gate over an objecttype-inheritance
 * chain. The element identity the gate matches on is the member's DECLARING type (IR-SPEC ch. 2.2, see
 * {@link ProducerConsumer#elementOf}), so a producer and a consumer that range over different types of one
 * chain still meet.
 *
 * <p>Keyed on the rule's own subject type instead, the gate concluded "no producer at all, so this is a genuine
 * request input" and let the scope through — after which the kenmerk evaluated silently as false and the answer
 * was {@code null} where 5,00 belonged. That silent wrong answer is what the first test below pins.
 *
 * <p>The producer lives in its OWN source file, so scoping only the consumer really keeps it out of the built
 * KieBase — otherwise the rule would be compiled along with its file and fire anyway, and the divergence the
 * gate exists for would not be observable.
 */
class ScopeChainTest {

    private static final String MODEL_FLAT = """
        Objecttype de Persoon (mv: Personen)
            de leeftijd Numeriek (geheel getal);
            de korting Numeriek (getal met 2 decimalen) met eenheid EUR;
            het volwassen kenmerk (bijvoeglijk);
        """;
    /** Student ⊑ Persoon; Student declares only its own lid, everything else is inherited. */
    private static final String MODEL_CHAIN = MODEL_FLAT + """
        Objecttype de Student is een Persoon (mv: Studenten)
            het collegejaar Numeriek (geheel getal);
        """;

    private static final String PRODUCER_SUPER = """
        Regel bepaal volwassen
        geldig altijd
        Een Persoon is volwassen indien de leeftijd van de Persoon groter is dan 17.
        """;
    private static final String PRODUCER_SUB = """
        Regel bepaal volwassen
        geldig altijd
        Een Student is volwassen indien de leeftijd van de Student groter is dan 17.
        """;
    private static final String CONSUMER_SUPER = """
        Regel bepaal korting
        geldig altijd
        De korting van een Persoon moet gesteld worden op 5,00 EUR indien de Persoon volwassen is.
        """;
    private static final String CONSUMER_SUB = """
        Regel bepaal korting
        geldig altijd
        De korting van een Student moet gesteld worden op 5,00 EUR indien de Student volwassen is.
        """;

    private static Map<String, String> source(String model, String producer, String consumer) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("model.rgs", model);
        m.put("producent.rgs", producer);
        m.put("consument.rgs", consumer);
        return m;
    }

    private static String descriptor(String type, String extra) {
        return """
            { "dienst": {"naam":"scope-keten","versie":"1"},
              "entrypoints": [ { "naam":"bepaal korting","pad":"bepaal-korting",
                "scope": {"regelnamen": ["bepaal korting"]},
                "invoer": ["%s"], "uitvoer": ["%s.korting"]%s } ],
              "parameters": {} }""".formatted(type, type, extra);
    }

    private static DecisionCore assemble(String descriptor, Map<String, String> source) {
        return DecisionCore.assemble(descriptor, source, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static void assertScopeViolation(ServiceError f) {
        assertTrue(f.violations().stream().anyMatch(s -> s.kind() == Violation.Kind.SCOPE_ONVOLLEDIG),
                () -> "verwacht SCOPE_ONVOLLEDIG: " + f.violations());
        assertTrue(f.violations().stream().anyMatch(s -> s.message().contains("volwassen")),
                () -> "de schending hoort het element te noemen: " + f.violations());
        assertTrue(f.violations().stream().anyMatch(s -> s.message().contains("bepaal volwassen")),
                () -> "en de producent die buiten de scope viel: " + f.violations());
    }

    // ---------------- richting (a): producent op het supertype, consument op het subtype ----------------

    @Test
    void producer_on_the_supertype_counts_for_a_consumer_on_the_subtype() {
        ServiceError f = assertThrows(ServiceError.class,
                () -> assemble(descriptor("Student", ""), source(MODEL_CHAIN, PRODUCER_SUPER, CONSUMER_SUB)));
        assertScopeViolation(f);
    }

    @Test
    void with_the_producer_in_scope_the_answer_is_right_that_the_silent_passthrough_miscalculated() {
        // Precies de situatie die de poort liet passeren, nu volledig gescoopt: 30 jaar -> volwassen -> 5,00 EUR.
        // De stille doorlaat antwoordde hier null, want het geërfde kenmerk evalueerde als onwaar.
        String complete = """
            { "dienst": {"naam":"scope-keten-vol","versie":"1"},
              "entrypoints": [ { "naam":"bepaal korting","pad":"bepaal-korting",
                "scope": {"regelnamen": ["bepaal korting", "bepaal volwassen"]},
                "invoer": ["Student"], "uitvoer": ["Student.korting"] } ],
              "parameters": {} }""";
        DecisionCore core = assemble(complete, source(MODEL_CHAIN, PRODUCER_SUPER, CONSUMER_SUB));
        ObjectNode a = core.process("bepaal-korting", parse("""
            { "instanties": [
                { "type": "Student", "id": "S1", "attributen": { "leeftijd": 30, "collegejaar": 2 } } ] }"""));
        JsonNode korting = null;
        for (JsonNode i : a.get("instanties"))
            if ("S1".equals(i.path("id").asText())) korting = i.path("attributen").get("korting");
        assertNotNull(korting, () -> "antwoord: " + a);
        assertFalse(korting.isNull(), () -> "de geërfde kenmerktoekenning hoort te vuren: " + a);
        assertEquals(0, new BigDecimal("5.00").compareTo(korting.decimalValue()), () -> "antwoord: " + a);
    }

    // ---------------- richting (b): producent op het subtype, consument op het supertype ----------------

    @Test
    void a_producer_on_the_subtype_is_partial_coverage_and_also_counts() {
        // De producent bedient alleen Student-instanties, de consument loopt over elke Persoon. Dat is PARTIELE
        // dekking, en de poort meldt hem: haar vraag is of er een producent BESTAAT die buiten de scope viel, en
        // een gedeeltelijke producent buiten de scope is een producent buiten de scope. Zwijgen zou betekenen dat
        // een Student in het verzoek het kenmerk stil als onwaar leest — precies het gebrek dat deze poort dekt.
        ServiceError f = assertThrows(ServiceError.class,
                () -> assemble(descriptor("Persoon", ""), source(MODEL_CHAIN, PRODUCER_SUB, CONSUMER_SUPER)));
        assertScopeViolation(f);
    }

    // ---------------- de opt-out spreekt beide spellingen ----------------

    @Test
    void the_input_element_may_be_on_the_declaring_type() {
        DecisionCore core = assemble(descriptor("Student", ", \"invoerElementen\": [\"Persoon:volwassen\"]"),
                source(MODEL_CHAIN, PRODUCER_SUPER, CONSUMER_SUB));
        assertNotNull(core.entrypoint("bepaal-korting"));
    }

    @Test
    void the_input_element_may_also_be_on_the_subtype() {
        // Dezelfde consumptie, in de andere spelling gedeclareerd. Een ontsnappingsluik dat wél bestaat maar niet
        // te spellen is, is geen ontsnappingsluik.
        DecisionCore core = assemble(descriptor("Student", ", \"invoerElementen\": [\"Student:volwassen\"]"),
                source(MODEL_CHAIN, PRODUCER_SUPER, CONSUMER_SUB));
        assertNotNull(core.entrypoint("bepaal-korting"));
    }

    // ---------------- de sleutel zelf, en de graaf die hem deelt ----------------

    @Test
    void producer_and_consumer_key_on_the_declaring_type() {
        RegelSpraakModel m = Resolver.resolve(MODEL_CHAIN + PRODUCER_SUPER + CONSUMER_SUB);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        ElementKey expected = ElementKey.van("Persoon", "volwassen", true);
        var index = ProducerConsumer.producerIndex(m, m.prioritizedDefaults);
        assertTrue(index.containsKey(expected), () -> "producentsleutel: " + index.keySet());
        boolean found = false;
        for (var r : m.resolvedRegels)
            if (ProducerConsumer.baseName(r.naam()).equals("bepaal korting"))
                found = ProducerConsumer.consumed(r, ProducerConsumer.sourceNavigation(m)).contains(expected);
        assertTrue(found, "de consument op het subtype hoort dezelfde sleutel te dragen");
    }

    @Test
    void the_key_is_kind_aware() {
        // Een kenmerk en een attribuut mogen dezelfde naam dragen op VERSCHILLENDE typen van één keten. De
        // declarant-zoektocht is daarom per soort; anders zou de kenmerklezing op het subtype het attribuut
        // daar vinden en op de verkeerde declarant sleutelen.
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Persoon (mv: Personen)
                het gekeurd kenmerk (bijvoeglijk);
            Objecttype de Student is een Persoon (mv: Studenten)
                de gekeurd Numeriek (geheel getal);
            """);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        var student = m.objectTypeByName("Student");
        assertEquals(ElementKey.van("Persoon", "gekeurd", true),
                ProducerConsumer.elementOf(student, "gekeurd", true),
                "het kenmerk is op Persoon gedeclareerd");
        assertEquals(ElementKey.van("Student", "gekeurd", false),
                ProducerConsumer.elementOf(student, "gekeurd", false),
                "het gelijknamige attribuut op Student");
    }

    @Test
    void the_graph_shares_the_key_and_draws_the_member_one_time() {
        // README-invariant: de scope-poort en de graaf lezen dezelfde IR-feiten. Op een ketenmodel tekende de
        // graaf het lid TWEE keer — 'volwassen · student' als INPUT die niemand schrijft, 'volwassen · persoon'
        // als eind-output die niemand leest — en allebei die beweringen waren onwaar.
        RuleGraph g = graph(MODEL_CHAIN + PRODUCER_SUPER + CONSUMER_SUB);
        long volwassenNodes = g.nodes().stream().filter(n -> n.label().startsWith("volwassen")).count();
        assertEquals(1, volwassenNodes, () -> "één element-node voor één lid: " + g.nodes());
        RuleGraph.Node volwassen = g.nodes().stream().filter(n -> n.label().startsWith("volwassen"))
                .findFirst().orElseThrow();
        assertEquals("volwassen · persoon", volwassen.label(), "gelabeld op zijn declarant");
        assertEquals(RuleGraph.NodeKind.CHARACTERISTIC, volwassen.kind(), "geen INVOER: er IS een producent");
        assertFalse(volwassen.endOutput(), "geen eind-output: er IS een consument");
    }

    @Test
    void the_graph_on_a_flat_model_does_not_change() {
        // De ketenlezing mag op een model zonder subtypen niets doen: elke keten is één element lang.
        RuleGraph g = graph(MODEL_FLAT + PRODUCER_SUPER + CONSUMER_SUPER);
        assertEquals(5, g.nodes().size(), () -> "" + g.nodes());
        assertEquals(4, g.edges().size(), () -> "" + g.edges());
        assertTrue(g.nodes().stream().anyMatch(n -> n.label().equals("leeftijd · persoon")
                && n.kind() == RuleGraph.NodeKind.INPUT), () -> "" + g.nodes());
    }

    /** uchar's rule graph of a resolver's model: its defaults groups ride on the model, and a navigated kenmerk
     *  check reaches the type of the role it names. */
    private static RuleGraph graph(String source) {
        RegelSpraakModel m = Resolver.resolve(source);
        return RuleGraph.build(m, m.prioritizedDefaults, ProducerConsumer.sourceNavigation(m));
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
