package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end witness of the dienst-keten card: a SUBTYPE instance is fully serviceable through the HTTP
 * service. It drives the OWN, rights-free {@link ChainSource} fixture (Jutterswet strandvondsten — no TOKA
 * material) through every layer the nine raw facet readings sat in: {@link OpenApiEmitter} (the contract
 * document), {@link DescriptorLoader} (descriptor→model validation), {@link RequestMapper} (request mapping)
 * and {@link DecisionCore}/{@link AnswerBuilder} (fire + response), plus one real HTTP round trip.
 *
 * <p>The fixture chain: {@code Wrakstuk} is een {@code Strandvondst}. The subtype declares only
 * {@code bergingsdiepte}; everything else — the attributen {@code vondstwaarde} (eur) and {@code bergloon}
 * (eur), the kenmerken {@code aangemeld} / {@code meldplichtig}, and the TEMPORAL members {@code meldtermijn}
 * (tijdlijn-attribuut) and {@code verjaard} (tijdlijn-kenmerk) — is inherited.
 *
 * <p><b>The hand-computed expectations</b> (worked out before the first run, and the reason the fixture uses a
 * whole-number {@code vondstwaarde}: 0 decimals × the 2-decimal factor 0,25 lands exactly on the 2 decimals
 * {@code bergloon} declares):
 * <ul>
 *   <li>{@code bergloon = vondstwaarde × 0,25}, only {@code indien de Strandvondst aangemeld is}. W1 is a
 *       Wrakstuk with vondstwaarde 240 eur and aangemeld=true → 240 × 0,25 = <b>60,00 eur</b>.</li>
 *   <li>W2 is a Wrakstuk with vondstwaarde 240 eur and aangemeld=false → the condition is false, nothing
 *       fires, bergloon stays <b>leeg</b> (JSON null).</li>
 *   <li>S1 is a Strandvondst (the supertype itself) with vondstwaarde 80 eur and aangemeld=true →
 *       80 × 0,25 = <b>20,00 eur</b>.</li>
 *   <li>{@code Een Wrakstuk is meldplichtig indien zijn vondstwaarde groter is dan 100 eur}: W1 (240) →
 *       <b>true</b>, W2 (80) → <b>false</b>.</li>
 * </ul>
 */
class ChainServiceTest {

    private static RegelSpraakModel MODEL;
    private static ServiceDescriptor DESCRIPTOR;
    private static DecisionCore CORE;
    private static ObjectNode OPENAPI;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void assemble() throws Exception {
        MODEL = ChainSource.model();
        assertTrue(MODEL.errors().isEmpty(), () -> "de fixture-bron hoort te resolven: " + MODEL.errors());
        DESCRIPTOR = DescriptorLoader.MAPPER.readValue(ChainSource.descriptorJson(), ServiceDescriptor.class);
        OPENAPI = OpenApiEmitter.emit(DESCRIPTOR, MODEL);
        CORE = DecisionCore.assemble(ChainSource.descriptorJson(), ChainSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    // ---------------- (i) het OpenAPI-schema van het subtype draagt de geërfde leden ----------------

    @Test
    void schema_of_the_subtype_carries_the_inherited_attributes_and_characteristics() {
        JsonNode props = subtypeSchema().get("properties");
        assertNotNull(props, "geen properties op het Wrakstuk-schema");

        // eigen lid
        assertEquals("integer", props.path("bergingsdiepte").path("type").asText());
        assertEquals("m", props.path("bergingsdiepte").path("x-eenheid").asText());
        // geërfde attributen — met hun eigen JSON-type EN hun eenheid
        assertEquals("integer", props.path("vondstwaarde").path("type").asText(),
                "het geërfde attribuut 'vondstwaarde' hoort in het subtype-schema te staan: " + props);
        assertEquals("eur", props.path("vondstwaarde").path("x-eenheid").asText());
        assertEquals("number", props.path("bergloon").path("type").asText(),
                "het geërfde attribuut 'bergloon' hoort in het subtype-schema te staan: " + props);
        assertEquals("eur", props.path("bergloon").path("x-eenheid").asText());
        // geërfde kenmerken — boolean-eigenschappen
        assertEquals("boolean", props.path("aangemeld").path("type").asText(),
                "het geërfde kenmerk 'aangemeld' hoort in het subtype-schema te staan: " + props);
        assertEquals("boolean", props.path("meldplichtig").path("type").asText(),
                "het geërfde kenmerk 'meldplichtig' hoort in het subtype-schema te staan: " + props);
    }

    @Test
    void schema_of_the_subtype_rejects_the_inherited_timeline_members_instead_of_offering_them_as_scalar() {
        JsonNode schema = subtypeSchema();
        JsonNode props = schema.get("properties");
        // een tijdlijn-lid is door de dienst-kern NIET te bedienen: het mag geen eigenschap zijn ...
        assertFalse(props.has("meldtermijn"),
                "het geërfde tijdlijn-attribuut mag geen scalar-eigenschap worden: " + props);
        assertFalse(props.has("verjaard"),
                "het geërfde tijdlijn-kenmerk mag geen boolean-eigenschap worden: " + props);
        // ... en de beschrijving MOET het noemen, anders verdwijnt het lid stilzwijgend uit het contract
        String description = schema.path("description").asText();
        assertTrue(description.contains("meldtermijn (tijdlijn)"),
                "de beschrijving hoort het geërfde tijdlijn-attribuut af te wijzen: " + description);
        assertTrue(description.contains("verjaard (tijdlijn-kenmerk)"),
                "de beschrijving hoort het geërfde tijdlijn-kenmerk af te wijzen: " + description);
        assertTrue(description.contains("aangemeld") && description.contains("meldplichtig"),
                "de beschrijving hoort de geërfde kenmerken op te sommen: " + description);
    }

    // ---------------- (iii) de descriptor laadt de regel op het geërfde lid ----------------

    @Test
    void the_descriptor_loads_an_output_selector_on_an_inherited_characteristic() {
        // 'Wrakstuk:meldplichtig' en 'Wrakstuk:aangemeld' noemen kenmerken die STRANDVONDST declareert.
        ServiceDescriptor d = DescriptorLoader.load(ChainSource.descriptorJson(), MODEL);
        assertEquals(3, d.entrypoints().size());
        assertTrue(DescriptorLoader.validate(d, MODEL).isEmpty(),
                () -> "de descriptor hoort schoon te valideren: " + DescriptorLoader.validate(d, MODEL));

        Objecttype wrakstuk = DescriptorLoader.objectType(MODEL, "Wrakstuk");
        assertNotNull(wrakstuk);
        assertTrue(DescriptorLoader.hasCharacteristic(wrakstuk, "aangemeld"),
                "een geërfd kenmerk is een kenmerk van het subtype");
        assertTrue(DescriptorLoader.hasCharacteristic(wrakstuk, "MELDPLICHTIG"),
                "en blijft hoofdletter-ongevoelig te vinden");
        assertFalse(DescriptorLoader.hasCharacteristic(wrakstuk, "gekaapt"),
                "een kenmerk dat de keten NIET declareert blijft onbekend");
    }

    @Test
    void an_unknown_member_on_the_subtype_stays_a_hard_descriptor_error() {
        String broken = ChainSource.descriptorJson()
                .replace("\"Wrakstuk:meldplichtig\"", "\"Wrakstuk:gekaapt\"");
        ServiceError f = assertThrows(ServiceError.class, () -> DescriptorLoader.load(broken, MODEL));
        assertTrue(f.violations().stream().anyMatch(s ->
                        s.kind() == Violation.Kind.ONBEKENDE_VERWIJZING && s.message().contains("gekaapt")),
                () -> "verwacht een ONBEKENDE_VERWIJZING op 'gekaapt': " + f.violations());
    }

    // ---------------- (ii) een verzoek met geërfde invoer levert de handberekende waarde ----------------

    @Test
    void request_with_inherited_attribute_and_inherited_characteristic_yields_the_hand_computed_value() {
        // W1: geërfd attribuut 240 eur + geërfd kenmerk aangemeld=true -> 240 x 0,25 = 60,00
        ObjectNode answer = CORE.process("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1",
                  "attributen": { "vondstwaarde": 240, "bergingsdiepte": 12 },
                  "kenmerken": { "aangemeld": true } } ] }"""));
        assertAmount("60.00", find(answer, "W1", "attributen", "bergloon"), answer);
        assertEquals("Wrakstuk", instance(answer, "W1").path("type").asText(),
                "het antwoord noemt het CONCRETE type van de instantie: " + answer);
    }

    @Test
    void the_inherited_characteristic_really_drives_the_condition() {
        // W2: zelfde waarde, aangemeld=false -> de voorwaarde is onwaar, niets vuurt, bergloon blijft leeg.
        ObjectNode answer = CORE.process("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W2",
                  "attributen": { "vondstwaarde": 240 },
                  "kenmerken": { "aangemeld": false } } ] }"""));
        JsonNode bergloon = find(answer, "W2", "attributen", "bergloon");
        assertNotNull(bergloon, "de uitvoer-selector hoort ook op het subtype te gelden: " + answer);
        assertTrue(bergloon.isNull(), "zonder aanmelding vuurt de regel niet: " + answer);
    }

    @Test
    void the_supertype_itself_stays_simply_served() {
        // S1: dezelfde regel op een instantie van het SUPERtype -> 80 x 0,25 = 20,00
        ObjectNode answer = CORE.process("bergloon", parse("""
            { "instanties": [
                { "type": "Strandvondst", "id": "S1",
                  "attributen": { "vondstwaarde": { "waarde": 80, "eenheid": "eur" } },
                  "kenmerken": { "aangemeld": true } } ] }"""));
        assertAmount("20.00", find(answer, "S1", "attributen", "bergloon"), answer);
    }

    @Test
    void a_rule_on_the_subtype_writes_an_inherited_characteristic() {
        // meldplicht: W1 (240 > 100) -> true, W2 (80 > 100) -> false. Beide op het GEERFDE kenmerk.
        ObjectNode answer = CORE.process("meldplicht", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "attributen": { "vondstwaarde": 240 } },
                { "type": "Wrakstuk", "id": "W2", "attributen": { "vondstwaarde": 80 } } ] }"""));
        assertEquals(true, find(answer, "W1", "kenmerken", "meldplichtig").asBoolean(),
                "240 eur > 100 eur: " + answer);
        assertEquals(false, find(answer, "W2", "kenmerken", "meldplichtig").asBoolean(),
                "80 eur > 100 eur is onwaar: " + answer);
    }

    @Test
    void a_rule_on_the_supertype_assigns_the_characteristic_to_the_subtype_instance_and_explains_it() {
        // 'Een Strandvondst is bergingsplichtig indien zijn vondstwaarde groter is dan 500 eur' is op het
        // SUPERTYPE geschreven. W1 (600 eur) hoort hem te krijgen — én de uitleg hoort hem te noemen: het
        // verhaal achter ?uitleg=true selecteert zijn kenmerktoekenningsregels per instantie.
        ObjectNode answer = CORE.process("bergingsplicht", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "attributen": { "vondstwaarde": 600 } },
                { "type": "Wrakstuk", "id": "W2", "attributen": { "vondstwaarde": 400 } } ] }"""), true);
        assertEquals(true, find(answer, "W1", "kenmerken", "bergingsplichtig").asBoolean(),
                "600 eur > 500 eur: " + answer);
        assertEquals(false, find(answer, "W2", "kenmerken", "bergingsplichtig").asBoolean(),
                "400 eur > 500 eur is onwaar: " + answer);

        JsonNode uitleg = answer.get("uitleg");
        assertNotNull(uitleg, "geen uitleg-array: " + answer);
        String story = uitleg.toString();
        assertTrue(story.contains("W1") && story.contains("bergingsplichtig"),
                "de uitleg hoort de toekenning op de subtype-instantie te verklaren: " + story);
        assertTrue(story.contains("W2"),
                "en hoort ook te verklaren waarom W2 hem NIET kreeg: " + story);
    }

    // ---------------- de geërfde tijdlijn-leden: luid geweigerd, nooit stil verkeerd geschreven ----------------

    @Test
    void an_inherited_timeline_attribute_is_refused_loudly_on_the_subtype() {
        ServiceError f = assertThrows(ServiceError.class, () -> CORE.process("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "attributen": { "meldtermijn": 30 } } ] }""")));
        assertTrue(f.violations().stream().anyMatch(s ->
                        s.kind() == Violation.Kind.NIET_ONDERSTEUND && s.message().contains("meldtermijn")),
                () -> "een geërfd tijdlijn-attribuut hoort NIET_ONDERSTEUND te zijn (niet stil als scalar"
                        + " geschreven en niet 'onbekend'): " + f.violations());
    }

    @Test
    void an_inherited_timeline_characteristic_is_refused_loudly_on_the_subtype() {
        ServiceError f = assertThrows(ServiceError.class, () -> CORE.process("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "kenmerken": { "verjaard": true } } ] }""")));
        assertTrue(f.violations().stream().anyMatch(s ->
                        s.kind() == Violation.Kind.NIET_ONDERSTEUND && s.message().contains("verjaard")),
                () -> "een geërfd tijdlijn-kenmerk hoort NIET_ONDERSTEUND te zijn: " + f.violations());
    }

    @Test
    void a_real_unknown_member_stays_UNKNOWN_FIELD() {
        ServiceError f = assertThrows(ServiceError.class, () -> CORE.process("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "kenmerken": { "gekaapt": true } } ] }""")));
        assertTrue(f.violations().stream().anyMatch(s ->
                        s.kind() == Violation.Kind.ONBEKEND_VELD && s.message().contains("gekaapt")),
                () -> "de keten mag geen enkel lid verzinnen: " + f.violations());
    }

    // ---------------- de HTTP-laag ----------------

    @Test
    void the_whole_chain_over_HTTP() throws Exception {
        ServiceHttp service = ServiceHttp.bind(CORE, "127.0.0.1", 0).start();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + service.port() + "/bergloon"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        { "instanties": [
                            { "type": "Wrakstuk", "id": "W1",
                              "attributen": { "vondstwaarde": 240, "bergingsdiepte": 12 },
                              "kenmerken": { "aangemeld": true } } ] }""", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, res.statusCode(), "antwoord: " + res.body());
            JsonNode answer = DescriptorLoader.MAPPER.readTree(res.body());
            JsonNode bergloon = null;
            for (JsonNode inst : answer.get("instanties"))
                if ("W1".equals(inst.path("id").asText())) bergloon = inst.path("attributen").get("bergloon");
            assertNotNull(bergloon, "antwoord: " + res.body());
            assertEquals(0, new BigDecimal("60.00").compareTo(bergloon.decimalValue()), "antwoord: " + res.body());
        } finally {
            service.stop(0);
        }
    }

    // ---------------- helpers ----------------

    private static JsonNode subtypeSchema() {
        JsonNode schema = OPENAPI.get("components").get("schemas").get("Wrakstuk");
        assertNotNull(schema, "geen component-schema voor het subtype Wrakstuk");
        return schema;
    }

    private static JsonNode instance(ObjectNode answer, String id) {
        for (JsonNode inst : answer.get("instanties"))
            if (id.equals(inst.path("id").asText())) return inst;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode find(ObjectNode answer, String id, String group, String member) {
        return instance(answer, id).path(group).get(member);
    }

    private static void assertAmount(String expected, JsonNode actual, ObjectNode answer) {
        assertNotNull(actual, () -> "verwacht " + expected + ", geen waarde in: " + answer);
        assertFalse(actual.isNull(), () -> "verwacht " + expected + ", kreeg leeg in: " + answer);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                () -> "verwacht " + expected + ", kreeg " + actual + " in: " + answer);
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
