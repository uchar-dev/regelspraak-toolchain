package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An id on a rol, inline or in a relatie, must name an instance whose objecttype fills that rol: the declared type
 * or a subtype of it. Another objecttype is an error of the caller, refused as VERKEERD_TYPE with the rol, the id
 * and both types, before anything is written. Both writers fall back on the type pair when the rol does not fit,
 * and then refused the link as a form the service does not carry (NIET_ONDERSTEUND) or as a rol they could not
 * determine (ONBEKEND_VELD), or wrote it into the field of another relation between the two types.
 *
 * <p>Own material: a self-typed one-to-many feittype on Jutter, a one-to-many feittype between Jutter and
 * Strandvondst (which has the subtype Wrakstuk), and a Strandpaal no feittype relates.
 */
class RoleTargetTypeTest {

    private static final String SOURCE = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het leerlingaantal Numeriek (niet-negatief geheel getal);

        Objecttype de Strandvondst (mv: Strandvondsten)
            de vondstwaarde Numeriek (niet-negatief geheel getal) met eenheid eur;

        Objecttype het Wrakstuk is een Strandvondst (mv: Wrakstukken)
            de bergingsdiepte Numeriek (niet-negatief geheel getal) met eenheid m;

        Objecttype de Strandpaal (mv: Strandpalen)
            het paalnummer Numeriek (niet-negatief geheel getal);

        Feittype leerlingschap
            de leermeester (mv: leermeesters) Jutter
            de leerling (mv: leerlingen) Jutter
        Eén leermeester heeft meerdere leerlingen

        Feittype vinderschap
            de vinder (mv: vinders) Jutter
            de vondst (mv: vondsten) Strandvondst
        Eén vinder heeft meerdere vondsten

        Regel leerlingaantal van een jutter
            geldig altijd
                Het leerlingaantal van een Jutter moet berekend worden als het aantal leerlingen van de Jutter.
        """;

    private static RequestMapper MAPPER;

    @BeforeAll
    static void assemble() {
        RegelSpraakModel model = Resolver.resolve(SOURCE);
        assertTrue(model.isOk(), model.errors().toString());
        MAPPER = new RequestMapper(model, UcharBuild.assemble(model, DroolsBackend.Branch.EXEC_MODEL));
    }

    private static RequestMapper.Result map(String json) {
        try { return MAPPER.map(new Request(DescriptorLoader.MAPPER.readTree(json))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void id_of_another_object_type_on_a_role_is_wrong_type() {
        // both rollen of the self-typed feittype, the to-many and the to-one, and no feittype relates a Jutter to a
        // Strandpaal: uchar's input step (CaseInvoer) found no field and refused the link as NIET_ONDERSTEUND
        var r = map("""
            { "instanties": [
                { "type": "Jutter", "id": "J1", "rollen": { "leerling": ["P1"] } },
                { "type": "Jutter", "id": "J2", "rollen": { "leermeester": "P1" } },
                { "type": "Strandpaal", "id": "P1" } ] }""");
        assertEquals(List.of(
                Violation.van(Violation.Kind.VERKEERD_TYPE, "instanties[0].rollen.leerling",
                        "rol 'leerling' verwacht een instantie van objecttype 'Jutter', maar id 'P1' is van"
                                + " objecttype 'Strandpaal'"),
                Violation.van(Violation.Kind.VERKEERD_TYPE, "instanties[1].rollen.leermeester",
                        "rol 'leermeester' verwacht een instantie van objecttype 'Jutter', maar id 'P1' is van"
                                + " objecttype 'Strandpaal'")),
                r.violations());
    }

    @Test
    void id_of_another_object_type_does_not_come_via_the_type_pair_in_another_role() {
        // Jutter and Strandvondst are related by vinderschap: CaseInvoer's by-pair fallback took that relation
        // and made J1 the vinder of S1, a link the request does not name
        var r = map("""
            { "instanties": [
                { "type": "Jutter", "id": "J1", "rollen": { "leerling": ["S1"] } },
                { "type": "Strandvondst", "id": "S1" } ] }""");
        assertEquals(List.of(Violation.van(Violation.Kind.VERKEERD_TYPE, "instanties[0].rollen.leerling",
                        "rol 'leerling' verwacht een instantie van objecttype 'Jutter', maar id 'S1' is van"
                                + " objecttype 'Strandvondst'")),
                r.violations());
        assertNull(RegelSpraakRuntime.get(r.world().beans().get("S1"), "vinder"), "S1 kreeg een vinder");
    }

    @Test
    void id_of_another_object_type_on_a_role_of_a_relation_is_wrong_type() {
        // the by-pair fallback made J1 the vinder of S1 and then found no field for the other direction
        var r = map("""
            { "instanties": [ { "type": "Jutter", "id": "J1" }, { "type": "Strandvondst", "id": "S1" } ],
              "relaties": [ { "feittype": "leerlingschap",
                              "rollen": { "leermeester": "J1", "leerling": "S1" } } ] }""");
        assertEquals(List.of(Violation.van(Violation.Kind.VERKEERD_TYPE, "relaties[0].rollen.leerling",
                        "rol 'leerling' verwacht een instantie van objecttype 'Jutter', maar id 'S1' is van"
                                + " objecttype 'Strandvondst'")),
                r.violations());
        assertNull(RegelSpraakRuntime.get(r.world().beans().get("S1"), "vinder"), "S1 kreeg een vinder");
    }

    @Test
    void id_of_a_subtype_fills_the_role() {
        var r = map("""
            { "instanties": [
                { "type": "Jutter", "id": "J1", "rollen": { "vondst": ["W1"] } },
                { "type": "Wrakstuk", "id": "W1" } ] }""");
        assertTrue(r.violations().isEmpty(), r.violations().toString());
        assertSame(r.world().beans().get("J1"), RegelSpraakRuntime.get(r.world().beans().get("W1"), "vinder"));
    }
}
