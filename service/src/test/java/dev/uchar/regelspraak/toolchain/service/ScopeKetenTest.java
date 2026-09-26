package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.graaf.ProducentConsument.ElementSleutel;
import dev.uchar.regelspraak.graaf.RegelGraaf;
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
 * The chain half of {@link ScopeVolledigheidTest}: the scope-completeness gate over an objecttype-inheritance
 * chain. The element identity the gate matches on is the member's DECLARING type (IR-SPEC ch. 2.2, see
 * {@link ProducentConsument#elementVan}), so a producer and a consumer that range over different types of one
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
class ScopeKetenTest {

    private static final String MODEL_VLAK = """
        Objecttype de Persoon (mv: Personen)
            de leeftijd Numeriek (geheel getal);
            de korting Numeriek (getal met 2 decimalen) met eenheid EUR;
            het volwassen kenmerk (bijvoeglijk);
        """;
    /** Student ⊑ Persoon; Student declares only its own lid, everything else is inherited. */
    private static final String MODEL_KETEN = MODEL_VLAK + """
        Objecttype de Student is een Persoon (mv: Studenten)
            het collegejaar Numeriek (geheel getal);
        """;

    private static final String PRODUCENT_SUPER = """
        Regel bepaal volwassen
        geldig altijd
        Een Persoon is volwassen indien de leeftijd van de Persoon groter is dan 17.
        """;
    private static final String PRODUCENT_SUB = """
        Regel bepaal volwassen
        geldig altijd
        Een Student is volwassen indien de leeftijd van de Student groter is dan 17.
        """;
    private static final String CONSUMENT_SUPER = """
        Regel bepaal korting
        geldig altijd
        De korting van een Persoon moet gesteld worden op 5,00 EUR indien de Persoon volwassen is.
        """;
    private static final String CONSUMENT_SUB = """
        Regel bepaal korting
        geldig altijd
        De korting van een Student moet gesteld worden op 5,00 EUR indien de Student volwassen is.
        """;

    private static Map<String, String> bron(String model, String producent, String consument) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("model.rgs", model);
        m.put("producent.rgs", producent);
        m.put("consument.rgs", consument);
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

    private static BeslisserKern bouw(String descriptor, Map<String, String> bron) {
        return BeslisserKern.bouw(descriptor, bron, KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());
    }

    private static void assertScopeSchending(DienstFout f) {
        assertTrue(f.schendingen().stream().anyMatch(s -> s.soort() == Schending.Soort.SCOPE_ONVOLLEDIG),
                () -> "verwacht SCOPE_ONVOLLEDIG: " + f.schendingen());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.bericht().contains("volwassen")),
                () -> "de schending hoort het element te noemen: " + f.schendingen());
        assertTrue(f.schendingen().stream().anyMatch(s -> s.bericht().contains("bepaal volwassen")),
                () -> "en de producent die buiten de scope viel: " + f.schendingen());
    }

    // ---------------- richting (a): producent op het supertype, consument op het subtype ----------------

    @Test
    void producent_op_het_supertype_telt_voor_een_consument_op_het_subtype() {
        DienstFout f = assertThrows(DienstFout.class,
                () -> bouw(descriptor("Student", ""), bron(MODEL_KETEN, PRODUCENT_SUPER, CONSUMENT_SUB)));
        assertScopeSchending(f);
    }

    @Test
    void met_de_producent_in_scope_klopt_het_antwoord_dat_de_stille_doorlaat_misrekende() {
        // Precies de situatie die de poort liet passeren, nu volledig gescoopt: 30 jaar -> volwassen -> 5,00 EUR.
        // De stille doorlaat antwoordde hier null, want het geërfde kenmerk evalueerde als onwaar.
        String volledig = """
            { "dienst": {"naam":"scope-keten-vol","versie":"1"},
              "entrypoints": [ { "naam":"bepaal korting","pad":"bepaal-korting",
                "scope": {"regelnamen": ["bepaal korting", "bepaal volwassen"]},
                "invoer": ["Student"], "uitvoer": ["Student.korting"] } ],
              "parameters": {} }""";
        BeslisserKern kern = bouw(volledig, bron(MODEL_KETEN, PRODUCENT_SUPER, CONSUMENT_SUB));
        ObjectNode a = kern.verwerk("bepaal-korting", parse("""
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
    void een_producent_op_het_subtype_is_partiele_dekking_en_telt_ook() {
        // De producent bedient alleen Student-instanties, de consument loopt over elke Persoon. Dat is PARTIELE
        // dekking, en de poort meldt hem: haar vraag is of er een producent BESTAAT die buiten de scope viel, en
        // een gedeeltelijke producent buiten de scope is een producent buiten de scope. Zwijgen zou betekenen dat
        // een Student in het verzoek het kenmerk stil als onwaar leest — precies het gebrek dat deze poort dekt.
        DienstFout f = assertThrows(DienstFout.class,
                () -> bouw(descriptor("Persoon", ""), bron(MODEL_KETEN, PRODUCENT_SUB, CONSUMENT_SUPER)));
        assertScopeSchending(f);
    }

    // ---------------- de opt-out spreekt beide spellingen ----------------

    @Test
    void het_invoerElement_mag_op_het_declarerende_type_staan() {
        BeslisserKern kern = bouw(descriptor("Student", ", \"invoerElementen\": [\"Persoon:volwassen\"]"),
                bron(MODEL_KETEN, PRODUCENT_SUPER, CONSUMENT_SUB));
        assertNotNull(kern.entrypoint("bepaal-korting"));
    }

    @Test
    void het_invoerElement_mag_ook_op_het_subtype_staan() {
        // Dezelfde consumptie, in de andere spelling gedeclareerd. Een ontsnappingsluik dat wél bestaat maar niet
        // te spellen is, is geen ontsnappingsluik.
        BeslisserKern kern = bouw(descriptor("Student", ", \"invoerElementen\": [\"Student:volwassen\"]"),
                bron(MODEL_KETEN, PRODUCENT_SUPER, CONSUMENT_SUB));
        assertNotNull(kern.entrypoint("bepaal-korting"));
    }

    // ---------------- de sleutel zelf, en de graaf die hem deelt ----------------

    @Test
    void producent_en_consument_sleutelen_op_het_declarerende_type() {
        RegelSpraakModel m = Resolver.resolve(MODEL_KETEN + PRODUCENT_SUPER + CONSUMENT_SUB);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        ElementSleutel verwacht = ElementSleutel.van("Persoon", "volwassen", true);
        var index = ProducentConsument.producentIndex(m, m.prioritizedDefaults);
        assertTrue(index.containsKey(verwacht), () -> "producentsleutel: " + index.keySet());
        boolean gevonden = false;
        for (var r : m.resolvedRegels)
            if (ProducentConsument.basisNaam(r.naam()).equals("bepaal korting"))
                gevonden = ProducentConsument.verbruikt(r, ProducentConsument.bronNavigatie(m)).contains(verwacht);
        assertTrue(gevonden, "de consument op het subtype hoort dezelfde sleutel te dragen");
    }

    @Test
    void de_sleutel_is_soort_bewust() {
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
        var student = m.objecttypeOpNaam("Student");
        assertEquals(ElementSleutel.van("Persoon", "gekeurd", true),
                ProducentConsument.elementVan(student, "gekeurd", true),
                "het kenmerk is op Persoon gedeclareerd");
        assertEquals(ElementSleutel.van("Student", "gekeurd", false),
                ProducentConsument.elementVan(student, "gekeurd", false),
                "het gelijknamige attribuut op Student");
    }

    @Test
    void de_graaf_deelt_de_sleutel_en_tekent_het_lid_een_keer() {
        // README-invariant: de scope-poort en de graaf lezen dezelfde IR-feiten. Op een ketenmodel tekende de
        // graaf het lid TWEE keer — 'volwassen · student' als INVOER die niemand schrijft, 'volwassen · persoon'
        // als eind-output die niemand leest — en allebei die beweringen waren onwaar.
        RegelGraaf g = graaf(MODEL_KETEN + PRODUCENT_SUPER + CONSUMENT_SUB);
        long volwassenNodes = g.nodes().stream().filter(n -> n.label().startsWith("volwassen")).count();
        assertEquals(1, volwassenNodes, () -> "één element-node voor één lid: " + g.nodes());
        RegelGraaf.Node volwassen = g.nodes().stream().filter(n -> n.label().startsWith("volwassen"))
                .findFirst().orElseThrow();
        assertEquals("volwassen · persoon", volwassen.label(), "gelabeld op zijn declarant");
        assertEquals(RegelGraaf.NodeKind.KENMERK, volwassen.kind(), "geen INVOER: er IS een producent");
        assertFalse(volwassen.eindOutput(), "geen eind-output: er IS een consument");
    }

    @Test
    void de_graaf_op_een_vlak_model_verandert_niet() {
        // De ketenlezing mag op een model zonder subtypen niets doen: elke keten is één element lang.
        RegelGraaf g = graaf(MODEL_VLAK + PRODUCENT_SUPER + CONSUMENT_SUPER);
        assertEquals(5, g.nodes().size(), () -> "" + g.nodes());
        assertEquals(4, g.edges().size(), () -> "" + g.edges());
        assertTrue(g.nodes().stream().anyMatch(n -> n.label().equals("leeftijd · persoon")
                && n.kind() == RegelGraaf.NodeKind.INVOER), () -> "" + g.nodes());
    }

    /** uchar's rule graph of a resolver's model: its defaults groups ride on the model, and a navigated kenmerk
     *  check reaches the type of the role it names. */
    private static RegelGraaf graaf(String bron) {
        RegelSpraakModel m = Resolver.resolve(bron);
        return RegelGraaf.bouw(m, m.prioritizedDefaults, ProducentConsument.bronNavigatie(m));
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
