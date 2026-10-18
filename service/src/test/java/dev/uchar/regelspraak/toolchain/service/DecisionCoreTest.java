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
 * The core end-to-end: build the TOKA {@link DecisionCore} over the real sources + descriptor via the DRL
 * back-end (the ScenarioRunner recipe), then round-trip a few golden scenarios' situaties by hand (the FULL
 * 29-scenario oracle is fase 5). The fact beans live on the CHILD bean classloader (BeanCompiler), so the
 * strict (fail-fast) posture already succeeds — every golden scope LOWERS under DRL. The loader argument is the
 * parent of the bean classloader, not the fact source.
 */
class DecisionCoreTest {

    private static DecisionCore CORE;

    @BeforeAll
    static void assemble() {
        // uchar carries every golden scope, so strict startup succeeds and no marker is exposed.
        CORE = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    @Test
    void all_golden_entrypoints_are_built() {
        // 29 descriptor entrypoints = ALL 29 exit-0 golden scenariotoetsen (30 until besluit 16Y dropped toka-45
        // — see TokaSource), INCLUDING toka-29 (see its round trip below); every one runs.
        assertEquals(29, CORE.entrypoints().size());
        assertEquals(29, CORE.entrypoints().values().stream().filter(DecisionCore.Entrypoint::executable).count());
    }

    @Test
    void toka17_bepaal_leeftijd_round_trip() {
        // Situatie basis-17: Vlucht V1 vluchtdatum 15-06-2024; Persoon P1 geboortedatum 15-06-1990, reis V1.
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "1990-06-15" },
                  "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("bepaal-leeftijd", parse(request));
        JsonNode leeftijd = find(answer, "P1", "attributes", "leeftijd");
        assertNotNull(leeftijd, "antwoord: " + answer);
        assertEquals(0, new BigDecimal("34").compareTo(leeftijd.decimalValue()), "leeftijd: " + answer);
    }

    @Test
    void toka20_total_to_betalen_round_trip() {
        // Situatie basis-20: V1; P1 reis V1, te betalen belasting 30,00; P2 reis V1, 20,00 -> totaal 50,00.
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "te betalen belasting": 30.00 }, "roles": { "reis": "V1" } },
                { "type": "Natuurlijk persoon", "id": "P2",
                  "attributes": { "te betalen belasting": 20.00 }, "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("totaal-te-betalen-belasting", parse(request));
        JsonNode total = find(answer, "V1", "attributes", "totaal te betalen belasting");
        assertNotNull(total, "antwoord: " + answer);
        assertEquals(0, new BigDecimal("50.00").compareTo(total.decimalValue()), "totaal: " + answer);
    }

    @Test
    void bigdecimal_is_rendered_exactly_not_double() {
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "te betalen belasting": 30.00 }, "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("totaal-te-betalen-belasting", parse(request));
        JsonNode total = find(answer, "V1", "attributes", "totaal te betalen belasting");
        assertNotNull(total);
        assertTrue(total.isBigDecimal() || total.isNumber(), "moet een JSON-getal zijn: " + total);
        assertFalse(total.isDouble(), "mag NOOIT een double zijn: " + total);
    }

    @Test
    void inconsistencies_are_reported_empty_when_consistent() {
        // toka-17 has no consistency check -> inconsistenties is an empty array (a present, empty field).
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": { "vluchtdatum": "2024-06-15" } },
                { "type": "Natuurlijk persoon", "id": "P1",
                  "attributes": { "geboortedatum": "1990-06-15" }, "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("bepaal-leeftijd", parse(request));
        assertTrue(answer.get("inconsistencies").isArray());
        assertTrue(answer.get("inconsistencies").isEmpty());
    }

    @Test
    void unknown_path_throws_UnknownPath() {
        org.junit.jupiter.api.Assertions.assertThrows(DecisionCore.UnknownPath.class,
                () -> CORE.process("dit-pad-bestaat-niet", parse("{ \"instances\": [] }")));
    }

    @Test
    void paaskorting_lowers_under_drl_no_marker() {
        // Paaskorting is a MARKER under the exec-model back-end but LOWERS under DRL (the pivot's point) — so
        // its entrypoint exists and carries NO marker under the strict-started DRL core.
        DecisionCore.Entrypoint ep = CORE.entrypoint("paaskorting");
        assertNotNull(ep);
        assertTrue(ep.markers().isEmpty(), "Paaskorting hoort te lowered onder de DRL-motor: " + ep.markers());
    }

    @Test
    void toka22_date_time_for_belasting_round_trip() {
        // toka-22: the exec-model back-end CANNOT compile this (hyphenated datum-tijd getter mismatch); under DRL
        // it lowers. eerste-van(verwacht 10:00, daadwerkelijk 09:30) -> 09:30.
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": {
                    "verwachte datum-tijd van vertrek": "2024-06-15T10:00:00",
                    "daadwerkelijke datum-tijd van vertrek": "2024-06-15T09:30:00" } } ] }""";
        ObjectNode answer = CORE.process(
                "datum-tijd-voor-het-berekenen-van-de-belasting-op-basis-van-afstand", parse(request));
        JsonNode dt = find(answer, "V1", "attributes",
                "datum-tijd voor het berekenen van de belasting op basis van afstand");
        assertNotNull(dt, "antwoord: " + answer);
        assertEquals("2024-06-15T09:30", dt.asText(), "antwoord: " + answer);
    }

    @Test
    void toka25_expected_aankomst_round_trip() {
        // toka-25 (also exec-uncompilable, DRL-lowered): vertrek 10:00 + duur 90 minuut -> aankomst 11:30.
        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1", "attributes": {
                    "verwachte datum-tijd van vertrek": "2024-06-15T10:00:00",
                    "verwachte duur": { "value": 90, "unit": "minuut" } } } ] }""";
        ObjectNode answer = CORE.process("verwachte-datum-tijd-van-aankomst-van-een-vlucht", parse(request));
        JsonNode dt = find(answer, "V1", "attributes", "verwachte datum-tijd van aankomst");
        assertNotNull(dt, "antwoord: " + answer);
        assertEquals("2024-06-15T11:30", dt.asText(), "antwoord: " + answer);
    }

    @Test
    void toka29_passagier_with_recht_to_treinmiles_round_trip() {
        // toka-29 is a §13.4.7 FEITCREATIE. The entry point's document carries the form uchar's frontend made of it
        // in the full model, and the exec-model branch lowers it: one LOWERED feitcreatie verdict. Until the scope
        // was taken per form, the entry point re-resolved the foundation plus the toka-29 file alone, where the
        // frontend makes no form of it (a warning: the reference field of the intermediate step is not derivable);
        // the build then held no rule at all and a request was answered as if the rule had run.
        DecisionCore.Entrypoint ep = CORE.entrypoint("passagier-met-recht-op-treinmiles");
        assertNotNull(ep, "de feitcreatie-entrypoint hoort gebouwd te zijn");
        assertTrue(ep.executable() && ep.markers().isEmpty(), ep.toString());
        assertEquals(1, ep.coverage().size(), ep.coverage().toString());
        assertEquals("feitcreatie", ep.coverage().get(0).category(), ep.coverage().toString());
        assertTrue(ep.coverage().get(0).lowered(), ep.coverage().toString());

        String request = """
            { "instances": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Contingent treinmiles", "id": "C1" },
                { "type": "Natuurlijk persoon", "id": "P1", "roles": { "reis": "V1" } } ] }""";
        ObjectNode answer = CORE.process("passagier-met-recht-op-treinmiles", parse(request));
        assertTrue(answer.get("inconsistencies").isArray(), "antwoord: " + answer);
        assertTrue(answer.get("inconsistencies").isEmpty(), "antwoord: " + answer);
    }

    @Test
    void coverage_is_the_manifest_of_the_document_of_the_scope() {
        // the document carries only the scoped forms, so the exec-model manifest is the scope's: one LOWERED verdict
        // for the one rule toka-17 scopes
        DecisionCore.Entrypoint ep = CORE.entrypoint("bepaal-leeftijd");
        assertEquals(1, ep.coverage().size(), ep.coverage().toString());
        assertTrue(ep.coverage().get(0).lowered(), ep.coverage().toString());
    }

    @Test
    void descriptor_covers_every_exit0_golden_scope() throws Exception {
        // Pin descriptor completeness against the golden set FOREVER: parse the manifest + each exit-0 scenario's
        // "toetst de regel <naam>" line mechanically, and assert the 29-entrypoint TOKA descriptor scopes every
        // such name. This wires the descriptor to the golden manifest so a dropped/renamed scope fails here.
        java.nio.file.Path manifest = TokaSource.REPO.resolve("examples/toka-scenarios/manifest");
        java.util.List<String> golden = new java.util.ArrayList<>();       // exit-0 scoped rule names
        for (String line : java.nio.file.Files.readAllLines(manifest)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] fields = t.split("\\s+");
            if (!fields[0].equals("0")) continue;                          // only exit-0 golden
            java.nio.file.Path scenario = TokaSource.REPO.resolve(fields[1]);
            for (String sr : java.nio.file.Files.readAllLines(scenario)) {
                String s = sr.strip();
                if (s.startsWith("toetst de regel ")) golden.add(s.substring("toetst de regel ".length()).trim());
            }
        }
        assertEquals(29, golden.size(), "verwacht 29 exit-0 golden scope-regels, kreeg " + golden);

        ServiceDescriptor d = DescriptorLoader.load(TokaSource.descriptorJson(), TokaSource.model());
        assertEquals(29, d.entrypoints().size(), "de TOKA-descriptor hoort 29 entrypoints te hebben");
        java.util.Set<String> scoped = new java.util.LinkedHashSet<>();
        for (ServiceDescriptor.Entrypoint ep : d.entrypoints())
            for (String n : ep.scope().ruleNamesOrEmpty())
                scoped.add(dev.uchar.regelspraak.emit.CaseEmitter.norm(n));

        for (String name : golden)
            assertTrue(scoped.contains(dev.uchar.regelspraak.emit.CaseEmitter.norm(name)),
                    "geen enkel descriptor-entrypoint scoopt de golden regel '" + name + "' — gescoopt: " + scoped);
    }

    // ---- helpers ----

    private static JsonNode find(ObjectNode answer, String id, String group, String member) {
        for (JsonNode inst : answer.get("instances"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(group).get(member);
        return null;
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void assertFalse(boolean b, String m) { org.junit.jupiter.api.Assertions.assertFalse(b, m); }
    private static void assertFalse(boolean b) { org.junit.jupiter.api.Assertions.assertFalse(b); }
}
