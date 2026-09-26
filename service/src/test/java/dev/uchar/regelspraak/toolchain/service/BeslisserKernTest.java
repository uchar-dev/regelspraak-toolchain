package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core end-to-end: build the TOKA {@link BeslisserKern} over the real sources + descriptor via the DRL
 * back-end (the ScenarioRunner recipe), then round-trip a few golden scenarios' situaties by hand (the FULL
 * 29-scenario oracle is fase 5). The fact beans live on the CHILD bean classloader (BeanCompiler), so the
 * strict (fail-fast) posture already succeeds — every golden scope LOWERS under DRL. The loader argument is the
 * parent of the bean classloader, not the fact source.
 */
class BeslisserKernTest {

    private static BeslisserKern KERN;

    @BeforeAll
    static void bouw() {
        // Every golden scope LOWERS under the DRL back-end, so strict startup succeeds and no marker is exposed.
        KERN = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
    }

    @Test
    void alle_golden_entrypoints_zijn_gebouwd() {
        // 29 descriptor entrypoints = ALL 29 exit-0 golden scenariotoetsen (30 until besluit 16Y dropped toka-45
        // — see TokaBron), INCLUDING toka-29 "passagier met recht
        // op treinmiles". That golden is a §13.4.7 FEITCREATIE (a role-relationship rule): RegelSpraakModel#isValueWriter
        // keeps it out of every RESOLVED collection, so DrlTranspiler emits ZERO rule blocks AND ZERO dekking entries
        // for it — but the RAW model.regels list DOES carry its name, so it is nameable through the file-index/descriptor
        // path (exactly as ScenarioResolver scopes "toetst de regel X" against model.regels). Its empty DRL compiles to
        // an inert KieBase, it is NOT a marker (no dekking entry, nothing to lower), and it starts clean under strict
        // mode. The 28 value-writing golden all LOWER under DRL; the pivot's point (toka-22/25, exec-uncompilable) is
        // pinned by the two round-trip tests below.
        assertEquals(29, KERN.entrypoints().size());
    }

    @Test
    void toka17_bepaal_leeftijd_round_trip() {
        // Situatie basis-17: Vlucht V1 vluchtdatum 15-06-2024; Persoon P1 geboortedatum 15-06-1990, reis V1.
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "1990-06-15" },
                  "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("bepaal-leeftijd", parse(verzoek));
        JsonNode leeftijd = vind(antwoord, "P1", "attributen", "leeftijd");
        assertNotNull(leeftijd, "antwoord: " + antwoord);
        assertEquals(0, new BigDecimal("34").compareTo(leeftijd.decimalValue()), "leeftijd: " + antwoord);
    }

    @Test
    void toka20_totaal_te_betalen_round_trip() {
        // Situatie basis-20: V1; P1 reis V1, te betalen belasting 30,00; P2 reis V1, 20,00 -> totaal 50,00.
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "te betalen belasting": 30.00 }, "rollen": { "reis": "V1" } },
                { "type": "Natuurlijk persoon", "id": "P2",
                  "attributen": { "te betalen belasting": 20.00 }, "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("totaal-te-betalen-belasting", parse(verzoek));
        JsonNode totaal = vind(antwoord, "V1", "attributen", "totaal te betalen belasting");
        assertNotNull(totaal, "antwoord: " + antwoord);
        assertEquals(0, new BigDecimal("50.00").compareTo(totaal.decimalValue()), "totaal: " + antwoord);
    }

    @Test
    void bigdecimal_wordt_exact_gerenderd_geen_double() {
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "te betalen belasting": 30.00 }, "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("totaal-te-betalen-belasting", parse(verzoek));
        JsonNode totaal = vind(antwoord, "V1", "attributen", "totaal te betalen belasting");
        assertNotNull(totaal);
        assertTrue(totaal.isBigDecimal() || totaal.isNumber(), "moet een JSON-getal zijn: " + totaal);
        assertFalse(totaal.isDouble(), "mag NOOIT een double zijn: " + totaal);
    }

    @Test
    void inconsistenties_worden_gerapporteerd_leeg_bij_consistent() {
        // toka-17 has no consistency check -> inconsistenties is an empty array (a present, empty field).
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributen": { "geboortedatum": "1990-06-15" }, "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("bepaal-leeftijd", parse(verzoek));
        assertTrue(antwoord.get("inconsistenties").isArray());
        assertTrue(antwoord.get("inconsistenties").isEmpty());
    }

    @Test
    void onbekend_pad_gooit_OnbekendPad() {
        org.junit.jupiter.api.Assertions.assertThrows(BeslisserKern.OnbekendPad.class,
                () -> KERN.verwerk("dit-pad-bestaat-niet", parse("{ \"instanties\": [] }")));
    }

    @Test
    void paaskorting_lowert_onder_drl_geen_marker() {
        // Paaskorting is a MARKER under the exec-model back-end but LOWERS under DRL (the pivot's point) — so
        // its entrypoint exists and carries NO marker under the strict-started DRL core.
        BeslisserKern.Entrypoint ep = KERN.entrypoint("paaskorting");
        assertNotNull(ep);
        assertTrue(ep.markers().isEmpty(), "Paaskorting hoort te lowered onder de DRL-motor: " + ep.markers());
    }

    @Test
    void toka22_datum_tijd_voor_belasting_round_trip() {
        // toka-22: the exec-model back-end CANNOT compile this (hyphenated datum-tijd getter mismatch); under DRL
        // it lowers. eerste-van(verwacht 10:00, daadwerkelijk 09:30) -> 09:30.
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": {
                    "verwachte datum-tijd van vertrek": "2024-06-15T10:00:00",
                    "daadwerkelijke datum-tijd van vertrek": "2024-06-15T09:30:00" } } ] }""";
        ObjectNode antwoord = KERN.verwerk(
                "datum-tijd-voor-het-berekenen-van-de-belasting-op-basis-van-afstand", parse(verzoek));
        JsonNode dt = vind(antwoord, "V1", "attributen",
                "datum-tijd voor het berekenen van de belasting op basis van afstand");
        assertNotNull(dt, "antwoord: " + antwoord);
        assertEquals("2024-06-15T09:30", dt.asText(), "antwoord: " + antwoord);
    }

    @Test
    void toka25_verwachte_aankomst_round_trip() {
        // toka-25 (also exec-uncompilable, DRL-lowered): vertrek 10:00 + duur 90 minuut -> aankomst 11:30.
        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1", "attributen": {
                    "verwachte datum-tijd van vertrek": "2024-06-15T10:00:00",
                    "verwachte duur": { "waarde": 90, "eenheid": "minuut" } } } ] }""";
        ObjectNode antwoord = KERN.verwerk("verwachte-datum-tijd-van-aankomst-van-een-vlucht", parse(verzoek));
        JsonNode dt = vind(antwoord, "V1", "attributen", "verwachte datum-tijd van aankomst");
        assertNotNull(dt, "antwoord: " + antwoord);
        assertEquals("2024-06-15T11:30", dt.asText(), "antwoord: " + antwoord);
    }

    @Test
    void toka29_passagier_met_recht_op_treinmiles_round_trip() {
        // toka-29 is a §13.4.7 FEITCREATIE. It writes no scalar/kenmerk, so DrlTranspiler emits ZERO rules and ZERO
        // dekking entries: the entrypoint compiles to an inert KieBase, is NOT a marker, and the request fires
        // nothing. The golden scenario-toka-29 therefore passes as a VACUOUS consistency check — no Inconsistentie
        // is produced, so "de gegevens zijn consistent". We reproduce that over the core's transport: build the
        // request from the situatie basis-29 shape (Vlucht V1 + Contingent treinmiles C1 + Natuurlijk persoon P1
        // with rol reis V1) and assert an EMPTY inconsistenties array. We do not assert a fired-activation count:
        // the executor does not expose one, and "nothing fires" is already witnessed by the empty inconsistenties
        // (a consistency violation would have appeared there).
        BeslisserKern.Entrypoint ep = KERN.entrypoint("passagier-met-recht-op-treinmiles");
        assertNotNull(ep, "de feitcreatie-entrypoint hoort gebouwd te zijn");
        assertTrue(ep.markers().isEmpty(),
                "een feitcreatie (geen dekking-entry) is geen marker: " + ep.markers());

        String verzoek = """
            { "instanties": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Contingent treinmiles", "id": "C1" },
                { "type": "Natuurlijk persoon", "id": "P1", "rollen": { "reis": "V1" } } ] }""";
        ObjectNode antwoord = KERN.verwerk("passagier-met-recht-op-treinmiles", parse(verzoek));
        assertTrue(antwoord.get("inconsistenties").isArray(), "antwoord: " + antwoord);
        assertTrue(antwoord.get("inconsistenties").isEmpty(),
                "een vacuöze consistentietoets levert een lege inconsistenties-array: " + antwoord);
    }

    @Test
    void descriptor_dekt_elke_exit0_golden_scope() throws Exception {
        // Pin descriptor completeness against the golden set FOREVER: parse the manifest + each exit-0 scenario's
        // "toetst de regel <naam>" line mechanically, and assert the 29-entrypoint TOKA descriptor scopes every
        // such name. This wires the descriptor to the golden manifest so a dropped/renamed scope fails here.
        java.nio.file.Path manifest = TokaBron.REPO.resolve("examples/toka-scenarios/manifest");
        java.util.List<String> golden = new java.util.ArrayList<>();       // exit-0 scoped rule names
        for (String regel : java.nio.file.Files.readAllLines(manifest)) {
            String t = regel.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] velden = t.split("\\s+");
            if (!velden[0].equals("0")) continue;                          // only exit-0 golden
            java.nio.file.Path scenario = TokaBron.REPO.resolve(velden[1]);
            for (String sr : java.nio.file.Files.readAllLines(scenario)) {
                String s = sr.strip();
                if (s.startsWith("toetst de regel ")) golden.add(s.substring("toetst de regel ".length()).trim());
            }
        }
        assertEquals(29, golden.size(), "verwacht 29 exit-0 golden scope-regels, kreeg " + golden);

        DienstDescriptor d = DescriptorLoader.laad(TokaBron.descriptorJson(), TokaBron.model());
        assertEquals(29, d.entrypoints().size(), "de TOKA-descriptor hoort 29 entrypoints te hebben");
        java.util.Set<String> gescoopt = new java.util.LinkedHashSet<>();
        for (DienstDescriptor.Entrypoint ep : d.entrypoints())
            for (String n : ep.scope().regelnamenOfLeeg())
                gescoopt.add(RegelBestandIndex.basisNaam(n));

        for (String naam : golden)
            assertTrue(gescoopt.contains(RegelBestandIndex.basisNaam(naam)),
                    "geen enkel descriptor-entrypoint scoopt de golden regel '" + naam + "' — gescoopt: " + gescoopt);
    }

    // ---- helpers ----

    private static JsonNode vind(ObjectNode antwoord, String id, String groep, String lid) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(groep).get(lid);
        return null;
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void assertFalse(boolean b, String m) { org.junit.jupiter.api.Assertions.assertFalse(b, m); }
    private static void assertFalse(boolean b) { org.junit.jupiter.api.Assertions.assertFalse(b); }
}
