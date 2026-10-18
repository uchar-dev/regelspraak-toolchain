package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;

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
class RelationsTest {

    private static RegelSpraakModel MODEL;
    private static RequestMapper MAPPER;

    @BeforeAll
    static void assemble() {
        StringBuilder source = new StringBuilder();
        for (String text : TokaSource.sourcePerFile().values()) source.append(text).append('\n');
        MODEL = Resolver.resolve(source.toString());
        MAPPER = new RequestMapper(MODEL, TokaSource.build());
    }

    private static RequestMapper.Result map(String json) {
        try { return MAPPER.map(new Request(DescriptorLoader.MAPPER.readTree(json))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void one_to_many_relation_sets_field_on_the_many_side() {
        // "Vlucht van natuurlijke personen": reis (one) / passagier (many) -> field on persoon.reis
        var r = map("""
            { "instances": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Natuurlijk persoon", "id": "P1" } ],
              "relations": [ { "factType": "Vlucht van natuurlijke personen",
                              "roles": { "reis": "V1", "passagier": "P1" } } ] }""");
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        Object p1 = r.world().beans().get("P1");
        Object v1 = r.world().beans().get("V1");
        assertSame(v1, RegelSpraakRuntime.get(p1, "reis"), "persoon.reis moet naar V1 wijzen");
    }

    @Test
    void one_to_one_relation_sets_both_reference_fields() {
        // "reis met contingent treinmiles": both to-one -> reference field on BOTH beans.
        var r = map("""
            { "instances": [
                { "type": "Vlucht", "id": "V1" },
                { "type": "Contingent treinmiles", "id": "C1" } ],
              "relations": [ { "factType": "reis met contingent treinmiles",
                              "roles": { "reis met treinmiles": "V1",
                                          "vastgestelde contingent treinmiles": "C1" } } ] }""");
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        Object v1 = r.world().beans().get("V1");
        Object c1 = r.world().beans().get("C1");
        // Vlucht -> Contingent AND Contingent -> Vlucht must both be set (the 1:1 both-directions fix)
        assertSame(c1, RegelSpraakRuntime.get(v1, "vastgesteldeContingentTreinmiles"),
                "Vlucht.vastgesteldeContingentTreinmiles moet naar C1 wijzen");
        assertSame(v1, RegelSpraakRuntime.get(c1, "reisMetTreinmiles"),
                "Contingent.reisMetTreinmiles moet naar V1 wijzen (de terugweg mag niet leeg blijven)");
    }

    @Test
    void reciprocal_relation_with_a_role_and_two_ids_is_parsed() {
        // "Partnerrelatie" is wederkerig (one role "partner", two ids). Until the move to uchar the fact model
        // materialised no 'partner' field and the mapper reported UNSUPPORTED; uchar's generator carries the
        // §13.3.9.2 reciprocal self-relation as a self-typed reference field, so the mapper writes both ends.
        var r = map("""
            { "instances": [
                { "type": "Natuurlijk persoon", "id": "A" },
                { "type": "Natuurlijk persoon", "id": "B" } ],
              "relations": [ { "factType": "Partnerrelatie",
                              "roles": { "partner": ["A", "B"] } } ] }""");
        // ONE role name carrying two ids, parsed into two endpoints, each written onto the other
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        Object a = r.world().beans().get("A");
        Object b = r.world().beans().get("B");
        assertSame(b, RegelSpraakRuntime.get(a, "partner"), "A.partner moet naar B wijzen");
        assertSame(a, RegelSpraakRuntime.get(b, "partner"), "B.partner moet naar A wijzen");
    }

    @Test
    void relation_to_unknown_id_is_reported() {
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1" } ],
              "relations": [ { "factType": "Vlucht van natuurlijke personen",
                              "roles": { "reis": "V1", "passagier": "PX" } } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD && s.message().contains("PX")), r.violations().toString());
    }

    @Test
    void unknown_fact_type_is_reported() {
        var r = map("""
            { "instances": [ { "type": "Vlucht", "id": "V1" } ],
              "relations": [ { "factType": "sprookjesrelatie", "roles": {} } ] }""");
        assertTrue(r.violations().stream().anyMatch(s ->
                s.kind() == Violation.Kind.UNKNOWN_FIELD && s.message().contains("sprookjesrelatie")),
                r.violations().toString());
    }
}
