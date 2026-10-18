package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;

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
class RolDoeltypeTest {

    private static final String BRON = """
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

    private static VerzoekMapper MAPPER;

    @BeforeAll
    static void bouw() {
        RegelSpraakModel model = Resolver.resolve(BRON);
        assertTrue(model.isOk(), model.errors().toString());
        MAPPER = new VerzoekMapper(model, UcharBouw.bouw(model, DroolsBackend.Branch.EXEC_MODEL));
    }

    private static VerzoekMapper.Resultaat map(String json) {
        try { return MAPPER.map(new Verzoek(DescriptorLoader.MAPPER.readTree(json))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void id_van_een_ander_objecttype_op_een_rol_is_verkeerd_type() {
        // both rollen of the self-typed feittype, the to-many and the to-one, and no feittype relates a Jutter to a
        // Strandpaal: uchar's input step (CaseInvoer) found no field and refused the link as NIET_ONDERSTEUND
        var r = map("""
            { "instanties": [
                { "type": "Jutter", "id": "J1", "rollen": { "leerling": ["P1"] } },
                { "type": "Jutter", "id": "J2", "rollen": { "leermeester": "P1" } },
                { "type": "Strandpaal", "id": "P1" } ] }""");
        assertEquals(List.of(
                Schending.van(Schending.Soort.VERKEERD_TYPE, "instanties[0].rollen.leerling",
                        "rol 'leerling' verwacht een instantie van objecttype 'Jutter', maar id 'P1' is van"
                                + " objecttype 'Strandpaal'"),
                Schending.van(Schending.Soort.VERKEERD_TYPE, "instanties[1].rollen.leermeester",
                        "rol 'leermeester' verwacht een instantie van objecttype 'Jutter', maar id 'P1' is van"
                                + " objecttype 'Strandpaal'")),
                r.schendingen());
    }

    @Test
    void id_van_een_ander_objecttype_komt_niet_via_het_typepaar_in_een_andere_rol() {
        // Jutter and Strandvondst are related by vinderschap: CaseInvoer's by-pair fallback took that relation
        // and made J1 the vinder of S1, a link the request does not name
        var r = map("""
            { "instanties": [
                { "type": "Jutter", "id": "J1", "rollen": { "leerling": ["S1"] } },
                { "type": "Strandvondst", "id": "S1" } ] }""");
        assertEquals(List.of(Schending.van(Schending.Soort.VERKEERD_TYPE, "instanties[0].rollen.leerling",
                        "rol 'leerling' verwacht een instantie van objecttype 'Jutter', maar id 'S1' is van"
                                + " objecttype 'Strandvondst'")),
                r.schendingen());
        assertNull(RegelSpraakRuntime.get(r.wereld().beans().get("S1"), "vinder"), "S1 kreeg een vinder");
    }

    @Test
    void id_van_een_ander_objecttype_op_een_rol_van_een_relatie_is_verkeerd_type() {
        // the by-pair fallback made J1 the vinder of S1 and then found no field for the other direction
        var r = map("""
            { "instanties": [ { "type": "Jutter", "id": "J1" }, { "type": "Strandvondst", "id": "S1" } ],
              "relaties": [ { "feittype": "leerlingschap",
                              "rollen": { "leermeester": "J1", "leerling": "S1" } } ] }""");
        assertEquals(List.of(Schending.van(Schending.Soort.VERKEERD_TYPE, "relaties[0].rollen.leerling",
                        "rol 'leerling' verwacht een instantie van objecttype 'Jutter', maar id 'S1' is van"
                                + " objecttype 'Strandvondst'")),
                r.schendingen());
        assertNull(RegelSpraakRuntime.get(r.wereld().beans().get("S1"), "vinder"), "S1 kreeg een vinder");
    }

    @Test
    void id_van_een_subtype_vult_de_rol() {
        var r = map("""
            { "instanties": [
                { "type": "Jutter", "id": "J1", "rollen": { "vondst": ["W1"] } },
                { "type": "Wrakstuk", "id": "W1" } ] }""");
        assertTrue(r.schendingen().isEmpty(), r.schendingen().toString());
        assertSame(r.wereld().beans().get("J1"), RegelSpraakRuntime.get(r.wereld().beans().get("W1"), "vinder"));
    }
}
