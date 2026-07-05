package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The top-level {@code relaties} desugaring across the three feittype cardinalities in TOKA: a 1:N feittype
 * (field on the many side), a 1:1 feittype (a reference field on BOTH sides — both must be wired, else a
 * navigation from the unset end reads a spurious leegwaarde), and a §13.3.9.2 WEDERKERIG (reflexive) feittype
 * (one role, two ids).
 */
class RelatiesTest {

    private static RegelSpraakModel MODEL;
    private static VerzoekMapper MAPPER;

    @BeforeAll
    static void bouw() {
        StringBuilder bron = new StringBuilder();
        for (String tekst : TokaBron.bronPerBestand().values()) bron.append(tekst).append('\n');
        MODEL = Resolver.resolve(bron.toString());
        MAPPER = new VerzoekMapper(MODEL, TokaBron.beanLoader());
    }

    private static VerzoekMapper.Resultaat map(String json) {
        try { return MAPPER.map(new Verzoek(DescriptorLoader.MAPPER.readTree(json))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void een_op_veel_relatie_zet_veld_op_de_veel_kant() {
        // "Vlucht van natuurlijke personen": reis (one) / passagier (many) -> field on persoon.reis
        var r = map("""
            { "instanties": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1" } ],
              "relaties": [ { "feittype": "Vlucht van natuurlijke personen",
                              "rollen": { "reis": "V1", "passagier": "P1" } } ] }""");
        assertTrue(r.schendingen().isEmpty(), r.schendingen().toString());
        Object p1 = r.wereld().beans().get("P1");
        Object v1 = r.wereld().beans().get("V1");
        assertSame(v1, RegelSpraakRuntime.get(p1, "reis"), "persoon.reis moet naar V1 wijzen");
    }

    @Test
    void een_op_een_relatie_zet_BEIDE_referentievelden() {
        // "reis met contingent treinmiles": both to-one -> reference field on BOTH beans.
        var r = map("""
            { "instanties": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Contingent treinmiles", "id": "C1" } ],
              "relaties": [ { "feittype": "reis met contingent treinmiles",
                              "rollen": { "reis met treinmiles": "V1",
                                          "vastgestelde contingent treinmiles": "C1" } } ] }""");
        assertTrue(r.schendingen().isEmpty(), r.schendingen().toString());
        Object v1 = r.wereld().beans().get("V1");
        Object c1 = r.wereld().beans().get("C1");
        // Vlucht -> Contingent AND Contingent -> Vlucht must both be set (the 1:1 both-directions fix)
        assertSame(c1, RegelSpraakRuntime.get(v1, "vastgesteldeContingentTreinmiles"),
                "Vlucht.vastgesteldeContingentTreinmiles moet naar C1 wijzen");
        assertSame(v1, RegelSpraakRuntime.get(c1, "reisMetTreinmiles"),
                "Contingent.reisMetTreinmiles moet naar V1 wijzen (de terugweg mag niet leeg blijven)");
    }

    @Test
    void wederkerige_relatie_met_een_rol_en_twee_ids_wordt_ontleed() {
        // "Partnerrelatie" is wederkerig (one role "partner", two ids). No TOKA rule navigates it, so the fact
        // model materialises NO 'partner' reference field on NatuurlijkPersoon — the mapper must report that as
        // a CLEAN "niet ondersteund" violation (naming the feittype), never throw a reflective setter error.
        var r = map("""
            { "instanties": [
                { "type": "Natuurlijk persoon", "id": "A" },
                { "type": "Natuurlijk persoon", "id": "B" } ],
              "relaties": [ { "feittype": "Partnerrelatie",
                              "rollen": { "partner": ["A", "B"] } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.NIET_ONDERSTEUND && s.bericht().contains("Partnerrelatie")),
                "verwacht een NIET_ONDERSTEUND schending voor het niet-gematerialiseerde partner-veld, kreeg: "
                        + r.schendingen());
        // and it was ONE role name carrying two ids, correctly parsed into two endpoints (no MALFORMED)
        assertTrue(r.schendingen().stream().noneMatch(s -> s.soort() == Schending.Soort.MALFORMED),
                r.schendingen().toString());
    }

    @Test
    void relatie_naar_onbekend_id_wordt_gemeld() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1" } ],
              "relaties": [ { "feittype": "Vlucht van natuurlijke personen",
                              "rollen": { "reis": "V1", "passagier": "PX" } } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD && s.bericht().contains("PX")), r.schendingen().toString());
    }

    @Test
    void onbekend_feittype_wordt_gemeld() {
        var r = map("""
            { "instanties": [ { "type": "Vlucht", "id": "V1" } ],
              "relaties": [ { "feittype": "sprookjesrelatie", "rollen": {} } ] }""");
        assertTrue(r.schendingen().stream().anyMatch(s ->
                s.soort() == Schending.Soort.ONBEKEND_VELD && s.bericht().contains("sprookjesrelatie")),
                r.schendingen().toString());
    }
}
