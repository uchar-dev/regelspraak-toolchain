package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.service.http.DienstHttp;

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
 * service. It drives the OWN, rights-free {@link KetenBron} fixture (Jutterswet strandvondsten — no TOKA
 * material) through every layer the nine raw facet readings sat in: {@link OpenApiEmitter} (the contract
 * document), {@link DescriptorLoader} (descriptor→model validation), {@link VerzoekMapper} (request mapping)
 * and {@link BeslisserKern}/{@link AntwoordBouwer} (fire + response), plus one real HTTP round trip.
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
class KetenDienstTest {

    private static RegelSpraakModel MODEL;
    private static DienstDescriptor DESCRIPTOR;
    private static BeslisserKern KERN;
    private static ObjectNode OPENAPI;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void bouw() throws Exception {
        MODEL = KetenBron.model();
        assertTrue(MODEL.errors().isEmpty(), () -> "de fixture-bron hoort te resolven: " + MODEL.errors());
        DESCRIPTOR = DescriptorLoader.MAPPER.readValue(KetenBron.descriptorJson(), DienstDescriptor.class);
        OPENAPI = OpenApiEmitter.emit(DESCRIPTOR, MODEL);
        KERN = BeslisserKern.bouw(KetenBron.descriptorJson(), KetenBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
    }

    // ---------------- (i) het OpenAPI-schema van het subtype draagt de geërfde leden ----------------

    @Test
    void schema_van_het_subtype_draagt_de_geerfde_attributen_en_kenmerken() {
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
    void schema_van_het_subtype_wijst_de_geerfde_tijdlijn_leden_af_in_plaats_van_ze_als_scalar_aan_te_bieden() {
        JsonNode schema = subtypeSchema();
        JsonNode props = schema.get("properties");
        // een tijdlijn-lid is door de dienst-kern NIET te bedienen: het mag geen eigenschap zijn ...
        assertFalse(props.has("meldtermijn"),
                "het geërfde tijdlijn-attribuut mag geen scalar-eigenschap worden: " + props);
        assertFalse(props.has("verjaard"),
                "het geërfde tijdlijn-kenmerk mag geen boolean-eigenschap worden: " + props);
        // ... en de beschrijving MOET het noemen, anders verdwijnt het lid stilzwijgend uit het contract
        String beschrijving = schema.path("description").asText();
        assertTrue(beschrijving.contains("meldtermijn (tijdlijn)"),
                "de beschrijving hoort het geërfde tijdlijn-attribuut af te wijzen: " + beschrijving);
        assertTrue(beschrijving.contains("verjaard (tijdlijn-kenmerk)"),
                "de beschrijving hoort het geërfde tijdlijn-kenmerk af te wijzen: " + beschrijving);
        assertTrue(beschrijving.contains("aangemeld") && beschrijving.contains("meldplichtig"),
                "de beschrijving hoort de geërfde kenmerken op te sommen: " + beschrijving);
    }

    // ---------------- (iii) de descriptor laadt de regel op het geërfde lid ----------------

    @Test
    void de_descriptor_laadt_een_uitvoerselector_op_een_geerfd_kenmerk() {
        // 'Wrakstuk:meldplichtig' en 'Wrakstuk:aangemeld' noemen kenmerken die STRANDVONDST declareert.
        DienstDescriptor d = DescriptorLoader.laad(KetenBron.descriptorJson(), MODEL);
        assertEquals(3, d.entrypoints().size());
        assertTrue(DescriptorLoader.valideer(d, MODEL).isEmpty(),
                () -> "de descriptor hoort schoon te valideren: " + DescriptorLoader.valideer(d, MODEL));

        Objecttype wrakstuk = DescriptorLoader.objecttype(MODEL, "Wrakstuk");
        assertNotNull(wrakstuk);
        assertTrue(DescriptorLoader.heeftKenmerk(wrakstuk, "aangemeld"),
                "een geërfd kenmerk is een kenmerk van het subtype");
        assertTrue(DescriptorLoader.heeftKenmerk(wrakstuk, "MELDPLICHTIG"),
                "en blijft hoofdletter-ongevoelig te vinden");
        assertFalse(DescriptorLoader.heeftKenmerk(wrakstuk, "gekaapt"),
                "een kenmerk dat de keten NIET declareert blijft onbekend");
    }

    @Test
    void een_onbekend_lid_op_het_subtype_blijft_een_harde_descriptorfout() {
        String kapot = KetenBron.descriptorJson()
                .replace("\"Wrakstuk:meldplichtig\"", "\"Wrakstuk:gekaapt\"");
        DienstFout f = assertThrows(DienstFout.class, () -> DescriptorLoader.laad(kapot, MODEL));
        assertTrue(f.schendingen().stream().anyMatch(s ->
                        s.soort() == Schending.Soort.ONBEKENDE_VERWIJZING && s.bericht().contains("gekaapt")),
                () -> "verwacht een ONBEKENDE_VERWIJZING op 'gekaapt': " + f.schendingen());
    }

    // ---------------- (ii) een verzoek met geërfde invoer levert de handberekende waarde ----------------

    @Test
    void verzoek_met_geerfd_attribuut_en_geerfd_kenmerk_levert_de_handberekende_waarde() {
        // W1: geërfd attribuut 240 eur + geërfd kenmerk aangemeld=true -> 240 x 0,25 = 60,00
        ObjectNode antwoord = KERN.verwerk("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1",
                  "attributen": { "vondstwaarde": 240, "bergingsdiepte": 12 },
                  "kenmerken": { "aangemeld": true } } ] }"""));
        assertBedrag("60.00", vind(antwoord, "W1", "attributen", "bergloon"), antwoord);
        assertEquals("Wrakstuk", instantie(antwoord, "W1").path("type").asText(),
                "het antwoord noemt het CONCRETE type van de instantie: " + antwoord);
    }

    @Test
    void het_geerfde_kenmerk_stuurt_de_voorwaarde_echt_aan() {
        // W2: zelfde waarde, aangemeld=false -> de voorwaarde is onwaar, niets vuurt, bergloon blijft leeg.
        ObjectNode antwoord = KERN.verwerk("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W2",
                  "attributen": { "vondstwaarde": 240 },
                  "kenmerken": { "aangemeld": false } } ] }"""));
        JsonNode bergloon = vind(antwoord, "W2", "attributen", "bergloon");
        assertNotNull(bergloon, "de uitvoer-selector hoort ook op het subtype te gelden: " + antwoord);
        assertTrue(bergloon.isNull(), "zonder aanmelding vuurt de regel niet: " + antwoord);
    }

    @Test
    void het_supertype_zelf_blijft_gewoon_bediend() {
        // S1: dezelfde regel op een instantie van het SUPERtype -> 80 x 0,25 = 20,00
        ObjectNode antwoord = KERN.verwerk("bergloon", parse("""
            { "instanties": [
                { "type": "Strandvondst", "id": "S1",
                  "attributen": { "vondstwaarde": { "waarde": 80, "eenheid": "eur" } },
                  "kenmerken": { "aangemeld": true } } ] }"""));
        assertBedrag("20.00", vind(antwoord, "S1", "attributen", "bergloon"), antwoord);
    }

    @Test
    void een_regel_op_het_subtype_schrijft_een_geerfd_kenmerk() {
        // meldplicht: W1 (240 > 100) -> true, W2 (80 > 100) -> false. Beide op het GEERFDE kenmerk.
        ObjectNode antwoord = KERN.verwerk("meldplicht", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "attributen": { "vondstwaarde": 240 } },
                { "type": "Wrakstuk", "id": "W2", "attributen": { "vondstwaarde": 80 } } ] }"""));
        assertEquals(true, vind(antwoord, "W1", "kenmerken", "meldplichtig").asBoolean(),
                "240 eur > 100 eur: " + antwoord);
        assertEquals(false, vind(antwoord, "W2", "kenmerken", "meldplichtig").asBoolean(),
                "80 eur > 100 eur is onwaar: " + antwoord);
    }

    @Test
    void een_regel_op_het_supertype_kent_het_kenmerk_toe_aan_de_subtype_instantie_en_legt_het_uit() {
        // 'Een Strandvondst is bergingsplichtig indien zijn vondstwaarde groter is dan 500 eur' is op het
        // SUPERTYPE geschreven. W1 (600 eur) hoort hem te krijgen — én de uitleg hoort hem te noemen: het
        // verhaal achter ?uitleg=true selecteert zijn kenmerktoekenningsregels per instantie.
        ObjectNode antwoord = KERN.verwerk("bergingsplicht", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "attributen": { "vondstwaarde": 600 } },
                { "type": "Wrakstuk", "id": "W2", "attributen": { "vondstwaarde": 400 } } ] }"""), true);
        assertEquals(true, vind(antwoord, "W1", "kenmerken", "bergingsplichtig").asBoolean(),
                "600 eur > 500 eur: " + antwoord);
        assertEquals(false, vind(antwoord, "W2", "kenmerken", "bergingsplichtig").asBoolean(),
                "400 eur > 500 eur is onwaar: " + antwoord);

        JsonNode uitleg = antwoord.get("uitleg");
        assertNotNull(uitleg, "geen uitleg-array: " + antwoord);
        String verhaal = uitleg.toString();
        assertTrue(verhaal.contains("W1") && verhaal.contains("bergingsplichtig"),
                "de uitleg hoort de toekenning op de subtype-instantie te verklaren: " + verhaal);
        assertTrue(verhaal.contains("W2"),
                "en hoort ook te verklaren waarom W2 hem NIET kreeg: " + verhaal);
    }

    // ---------------- de geërfde tijdlijn-leden: luid geweigerd, nooit stil verkeerd geschreven ----------------

    @Test
    void een_geerfd_tijdlijn_attribuut_wordt_luid_geweigerd_op_het_subtype() {
        DienstFout f = assertThrows(DienstFout.class, () -> KERN.verwerk("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "attributen": { "meldtermijn": 30 } } ] }""")));
        assertTrue(f.schendingen().stream().anyMatch(s ->
                        s.soort() == Schending.Soort.NIET_ONDERSTEUND && s.bericht().contains("meldtermijn")),
                () -> "een geërfd tijdlijn-attribuut hoort NIET_ONDERSTEUND te zijn (niet stil als scalar"
                        + " geschreven en niet 'onbekend'): " + f.schendingen());
    }

    @Test
    void een_geerfd_tijdlijn_kenmerk_wordt_luid_geweigerd_op_het_subtype() {
        DienstFout f = assertThrows(DienstFout.class, () -> KERN.verwerk("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "kenmerken": { "verjaard": true } } ] }""")));
        assertTrue(f.schendingen().stream().anyMatch(s ->
                        s.soort() == Schending.Soort.NIET_ONDERSTEUND && s.bericht().contains("verjaard")),
                () -> "een geërfd tijdlijn-kenmerk hoort NIET_ONDERSTEUND te zijn: " + f.schendingen());
    }

    @Test
    void een_echt_onbekend_lid_blijft_ONBEKEND_VELD() {
        DienstFout f = assertThrows(DienstFout.class, () -> KERN.verwerk("bergloon", parse("""
            { "instanties": [
                { "type": "Wrakstuk", "id": "W1", "kenmerken": { "gekaapt": true } } ] }""")));
        assertTrue(f.schendingen().stream().anyMatch(s ->
                        s.soort() == Schending.Soort.ONBEKEND_VELD && s.bericht().contains("gekaapt")),
                () -> "de keten mag geen enkel lid verzinnen: " + f.schendingen());
    }

    // ---------------- de HTTP-laag ----------------

    @Test
    void de_hele_keten_over_HTTP() throws Exception {
        DienstHttp dienst = DienstHttp.bind(KERN, "127.0.0.1", 0).start();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + dienst.poort() + "/bergloon"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        { "instanties": [
                            { "type": "Wrakstuk", "id": "W1",
                              "attributen": { "vondstwaarde": 240, "bergingsdiepte": 12 },
                              "kenmerken": { "aangemeld": true } } ] }""", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, res.statusCode(), "antwoord: " + res.body());
            JsonNode antwoord = DescriptorLoader.MAPPER.readTree(res.body());
            JsonNode bergloon = null;
            for (JsonNode inst : antwoord.get("instanties"))
                if ("W1".equals(inst.path("id").asText())) bergloon = inst.path("attributen").get("bergloon");
            assertNotNull(bergloon, "antwoord: " + res.body());
            assertEquals(0, new BigDecimal("60.00").compareTo(bergloon.decimalValue()), "antwoord: " + res.body());
        } finally {
            dienst.stop(0);
        }
    }

    // ---------------- helpers ----------------

    private static JsonNode subtypeSchema() {
        JsonNode schema = OPENAPI.get("components").get("schemas").get("Wrakstuk");
        assertNotNull(schema, "geen component-schema voor het subtype Wrakstuk");
        return schema;
    }

    private static JsonNode instantie(ObjectNode antwoord, String id) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText())) return inst;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode vind(ObjectNode antwoord, String id, String groep, String lid) {
        return instantie(antwoord, id).path(groep).get(lid);
    }

    private static void assertBedrag(String verwacht, JsonNode werkelijk, ObjectNode antwoord) {
        assertNotNull(werkelijk, () -> "verwacht " + verwacht + ", geen waarde in: " + antwoord);
        assertFalse(werkelijk.isNull(), () -> "verwacht " + verwacht + ", kreeg leeg in: " + antwoord);
        assertEquals(0, new BigDecimal(verwacht).compareTo(werkelijk.decimalValue()),
                () -> "verwacht " + verwacht + ", kreeg " + werkelijk + " in: " + antwoord);
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
