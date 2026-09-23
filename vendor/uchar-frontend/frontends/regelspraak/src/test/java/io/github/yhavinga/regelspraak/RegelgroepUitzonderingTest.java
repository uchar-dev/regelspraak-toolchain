package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code Regelgroep} (§9.10) and the defaults extension (dialect §4) do not mix: an extension block within
 * the scope of a group is refused loudly, at the block's own head (maintainer decision 2026-09-23, option (a)
 * of the card frontend-regelgroep-sluit-bij-uitzondering).
 *
 * <p><b>Why a refusal is needed at all.</b> {@code groepLid} matches rules and decision tables only, so a
 * group ends silently at the first extension block. Measured on the pre-card frontend (3a140fe4), the M5
 * document of {@code spec/proposals/DIALECT-DEFAULTS-NIET-EN-NESTING.md} — a group {@code berging} over a
 * base rule, its {@code Uitzondering op} and a third rule — emitted {@code leden: ["bergingsbevoegd bij
 * quotum"]} with {@code diagnostics: []}: the exception and the rule after it fell outside the group without a
 * word, and because the lowering lifts the base into a defaults group, the verifier then refused the document
 * with {@code WF-033 FAIL /regelGroepen/0: group member 'bergingsbevoegd bij quotum' does not resolve to a rule
 * form} — the author got the fault on the wrong line and without its cause. (The pre-M1 path; since M1,
 * 2026-09-23, the verifier names the position /rules/regelGroepen/0.)
 *
 * <p><b>Two arms, each measured.</b> The POSITIONAL arm refuses a block standing directly after a group's last
 * member: that is where the group would still read on if the block were a rule, and where it ends in silence.
 * The MEMBERSHIP arm refuses a block whose base rule is a group member, wherever the block stands: the lift
 * removes that member from the rule forms, which is the WF-033 document again (measured: base in the group, the
 * exception behind a {@code Parameter} that ends the group — emitted with {@code diagnostics: []}, then WF-033).
 * A block that satisfies both gets ONE diagnostic naming both reasons.
 *
 * <p><b>What stays clean</b> is pinned too: an extension block before the group's header, and one behind a
 * definition that ends the group, both over a base outside it.
 */
class RegelgroepUitzonderingTest {

    private static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            is bergingsbevoegd kenmerk (bijvoeglijk);
            is noodweer verklaard kenmerk (bijvoeglijk);
            is kustwacht kenmerk (bijvoeglijk);
            is havenmeester kenmerk (bijvoeglijk);
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
            de bergingspremie Numeriek (niet-negatief geheel getal);
            het wachtgeld Numeriek (geheel getal);

        """;

    private static final String BASIS = """
        Regel bergingsbevoegd bij quotum
            geldig altijd
                Een Jutter is bergingsbevoegd
                indien zijn aantal geborgen wrakken groter of gelijk is aan 20.

        """;

    private static final String UITZONDERING = """
        Uitzondering op bergingsbevoegd bij quotum: bergingsbevoegd bij noodweer
            geldig altijd
                Een Jutter is bergingsbevoegd
                indien hij aan alle volgende voorwaarden voldoet:
                - hij is noodweer verklaard
                - zijn aantal geborgen wrakken is groter of gelijk aan 8.

        """;

    private static final String PREMIE = """
        Regel bergingspremie
            geldig altijd
                De bergingspremie van een Jutter moet berekend worden als zijn aantal geborgen wrakken maal 10.

        """;

    private static final String ONGEORDEND = """
        Uitzonderingen ongeordend:
        Regel wachtgeld voor kustwachters
            geldig altijd
                Het wachtgeld van een Jutter moet gesteld worden op 30
                indien hij kustwacht is.

        Regel wachtgeld voor havenmeesters
            geldig altijd
                Het wachtgeld van een Jutter moet gesteld worden op 45
                indien hij havenmeester is.

        """;

    /** The M5 document of the proposal: group, base, its exception, a third rule. */
    static final String M5 = MODEL + "Regelgroep berging\n" + BASIS + UITZONDERING + PREMIE;

    private static int regelVan(String src, String needle) {
        int idx = src.indexOf(needle);
        assertTrue(idx >= 0, () -> "needle niet gevonden: " + needle);
        return 1 + (int) src.substring(0, idx).chars().filter(c -> c == '\n').count();
    }

    /** The single ERROR, asserting there is exactly one — the test pins THIS refusal and no bystander. */
    private static Diagnostic enigeFout(RegelSpraakModel m) {
        List<Diagnostic> fouten = m.errors();
        assertEquals(1, fouten.size(), () -> "verwachtte precies één fout, kreeg: " + fouten);
        return fouten.get(0);
    }

    // -------------------------------------------------------------------- the refusals

    @Test void m5_uitzondering_in_de_reikwijdte_weigert_op_haar_kop() {
        RegelSpraakModel m = Resolver.resolve(M5);
        // An ERROR is what stops Resolver.irModus writing the document at all: the WF-033 document of M5
        // never reaches disk.
        assertFalse(m.isOk(), () -> "het M5-document hoort geweigerd te worden, diagnostiek: " + m.diagnostics);
        Diagnostic d = enigeFout(m);
        assertEquals(regelVan(M5, "Uitzondering op"), d.line(), () -> "de melding hoort op de kop van het blok: " + d);
        String t = d.message();
        assertTrue(t.contains("regelgroep 'berging'"), () -> "de melding noemt de groep niet: " + t);
        assertTrue(t.contains("direct na het laatste lid"), () -> "de melding noemt de plaats niet: " + t);
        assertTrue(t.contains("'bergingsbevoegd bij quotum' is lid van regelgroep 'berging'"),
                () -> "de melding noemt de basis als groepslid niet: " + t);
        assertTrue(t.contains("WF-033"), () -> "de melding noemt het gevolg niet: " + t);
        assertTrue(t.contains("§9.10") && t.contains("§4"), () -> "de melding verwijst niet naar §9.10 en §4: " + t);
        assertTrue(t.contains("vóór de eerste regelgroepkop van het document ('Regelgroep berging')"),
                () -> "de melding wijst de uitweg niet: " + t);
        // A refused block stays un-lifted, as every other refusal of the defaults pass leaves it.
        assertTrue(m.prioritizedDefaults.isEmpty(), () -> "een geweigerd blok hoort ongelift te blijven: " + m.prioritizedDefaults);
    }

    @Test void basis_als_groepslid_weigert_ook_buiten_de_reikwijdte() {
        // The membership arm alone: a Parameter ends the group, so the block is NOT in its scope — but its base
        // is a member, and the lift would leave that member resolving to no rule form (WF-033).
        String src = MODEL + "Regelgroep berging\n" + BASIS
                + "Parameter de noodweerdrempel : Numeriek (geheel getal)\n\n" + UITZONDERING;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), () -> "een gelifte basis in een regelgroep hoort geweigerd te worden: " + m.diagnostics);
        Diagnostic d = enigeFout(m);
        assertEquals(regelVan(src, "Uitzondering op"), d.line(), () -> "de melding hoort op de kop van het blok: " + d);
        assertTrue(d.message().contains("'bergingsbevoegd bij quotum' is lid van regelgroep 'berging'")
                        && d.message().contains("WF-033"),
                () -> "de melding noemt de basis als groepslid niet: " + d);
        assertFalse(d.message().contains("direct na het laatste lid"),
                () -> "de plaatsingsreden hoort hier niet te vuren: " + d);
    }

    @Test void uitzondering_direct_na_de_groep_weigert_ook_met_de_basis_erbuiten() {
        // The positional arm alone: the base stands before the group and is no member, but the block stands
        // where the group would still read on — any rule behind it would fall outside the group in silence.
        String src = MODEL + BASIS + "Regelgroep premie\n" + PREMIE + UITZONDERING;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), () -> "een blok in de reikwijdte van een regelgroep hoort geweigerd te worden: " + m.diagnostics);
        Diagnostic d = enigeFout(m);
        assertEquals(regelVan(src, "Uitzondering op"), d.line());
        assertTrue(d.message().contains("direct na het laatste lid van regelgroep 'premie'"),
                () -> "de melding noemt de plaats niet: " + d);
        assertFalse(d.message().contains("WF-033"), () -> "de lidmaatschapsreden hoort hier niet te vuren: " + d);
    }

    @Test void ongeordend_blok_in_de_reikwijdte_weigert_op_zijn_kop() {
        // The sister head: 'Uitzonderingen ongeordend' directly after a group member.
        String src = MODEL + "Regelgroep premie\n" + PREMIE + ONGEORDEND;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), () -> "een ongeordend blok in de reikwijdte hoort geweigerd te worden: " + m.diagnostics);
        Diagnostic d = enigeFout(m);
        assertEquals(regelVan(src, "Uitzonderingen ongeordend"), d.line());
        assertTrue(d.message().startsWith("Uitzonderingen ongeordend:"), () -> "de melding noemt de kop niet: " + d);
        assertTrue(d.message().contains("regelgroep 'premie'") && d.message().contains("§9.10"),
                () -> "de melding noemt de groep of §9.10 niet: " + d);
        assertTrue(m.prioritizedDefaults.isEmpty(), () -> "een geweigerd blok hoort ongelift te blijven: " + m.prioritizedDefaults);
    }

    @Test void bij_twee_groepen_leidt_de_uitweg_buiten_elke_groep() {
        // Base in group 'a', the block behind group 'b', the two groups back to back. The advice used to name
        // the header of 'b' — and a block and base moved there land behind a's last member, refused again.
        String groepA = "Regelgroep a\n" + BASIS + """
            Regel tweede lid
                geldig altijd
                    De bergingspremie van een Jutter moet berekend worden als 5.

            """;
        String groepB = "Regelgroep b\n" + """
            Regel lid van b
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30.

            """;
        String src = MODEL + groepA + groepB + UITZONDERING;
        Diagnostic d = enigeFout(Resolver.resolve(src));
        assertTrue(d.message().contains("vóór de eerste regelgroepkop van het document ('Regelgroep a')"), d.message());
        assertFalse(d.message().contains("'Regelgroep b'"), d.message());
        // and the advice, followed, gives a clean document
        String gevolgd = MODEL + BASIS + UITZONDERING
                + groepA.replace(BASIS, "") + groepB;
        RegelSpraakModel m = Resolver.resolve(gevolgd);
        assertTrue(m.errors().isEmpty(), () -> "de uitweg zelf wordt geweigerd: " + m.errors());
    }

    @Test void een_groepskop_zonder_leden_geeft_alleen_de_syntaxfout() {
        // A header directly followed by a block is a syntax error (a group needs a member); the placement arm
        // has no last member to speak of there, and used to add a message about one.
        String src = MODEL + BASIS + "Regelgroep berging\n" + UITZONDERING;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(e -> e.message().startsWith("syntaxfout")), () -> "" + m.errors());
        assertTrue(m.diagnostics.stream().noneMatch(e -> e.message().contains("direct na het laatste lid")),
                () -> "een groep zonder leden heeft geen laatste lid: " + m.diagnostics);
    }

    // -------------------------------------------------------------------- a decision table in the group

    /** A group whose member is a decision table: the table is no rule, its ROWS are ("Bergingspremie 1", …).
     *  A Parameter ends the group, so only the membership arm can see the block below. */
    private static final String GEGROEPEERDE_TABEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
            de bergingspremie Numeriek (niet-negatief geheel getal);
            is noodweer verklaard kenmerk (bijvoeglijk);

        Regelgroep berging
        Beslistabel Bergingspremie
            geldig altijd
        | | de bergingspremie van een Jutter moet gesteld worden op | indien zijn aantal geborgen wrakken groter is dan |
        |---|---|---|
        | 1 | 10 | 5 |
        | 2 | 20 | 10 |

        Parameter de noodweerdrempel : Numeriek (geheel getal)

        """;

    private static String uitzonderingOp(String basis) {
        return """
            Uitzondering op %s: bergingspremie bij noodweer
                geldig altijd
                    De bergingspremie van een Jutter moet gesteld worden op 50
                    indien hij noodweer verklaard is.
            """.formatted(basis);
    }

    @Test void een_rij_van_een_gegroepeerde_beslistabel_als_basis_weigert() {
        // Measured on 57114772: written with diagnostics: [], then WF-033 and WF-050 at the verifier — the arm
        // keyed on the table's name, and the base is a row.
        String src = GEGROEPEERDE_TABEL + uitzonderingOp("Bergingspremie 1");
        RegelSpraakModel m = Resolver.resolve(src);
        Diagnostic d = enigeFout(m);
        assertEquals(regelVan(src, "Uitzondering op"), d.line(), () -> "de melding hoort op de kop van het blok: " + d);
        assertTrue(d.message().contains(
                        "'Bergingspremie 1' is een rij van beslistabel 'Bergingspremie', lid van regelgroep 'berging'"),
                d.message());
        assertTrue(m.prioritizedDefaults.isEmpty(), "een geweigerd blok hoort ongelift te blijven");
    }

    @Test void de_tabelnaam_als_basis_krijgt_de_juistere_melding() {
        // The table's name names no rule, so no lift can happen: the ordinary "no base rule" refusal is the
        // true one, and the group refusal must not speak in its place.
        String src = GEGROEPEERDE_TABEL + uitzonderingOp("Bergingspremie");
        RegelSpraakModel m = Resolver.resolve(src);
        Diagnostic d = enigeFout(m);
        assertTrue(d.message().contains("geen basisregel met die naam gevonden"), d.message());
        assertFalse(d.message().contains("regelgroep"), d.message());
        // and it names the construct, as it does for the other non-rule forms: a table, and the rules it is
        assertTrue(d.message().contains("'Bergingspremie' is een beslistabel")
                && d.message().contains("'Bergingspremie 1' en 'Bergingspremie 2'"), d.message());
    }

    @Test void een_objectcreatie_in_de_groep_als_basis_krijgt_de_juistere_melding() {
        // A member that is no plain rule cannot be lifted, so the membership arm has no lift to speak of: the
        // lowering's "no base rule" refusal, which names the construct, is the true one.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is noodweer verklaard kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                is gewogen kenmerk (bijvoeglijk);

            Feittype vondstaanmelding
                de aanmelder Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmelder heeft meerdere vondsten

            Regelgroep berging
            Regel maak een vondst
                geldig altijd
                    Een Jutter heeft een vondst met gewogen gelijk aan waar
                    indien hij noodweer verklaard is.

            Parameter de noodweerdrempel : Numeriek (geheel getal)

            Uitzondering op maak een vondst: noodweer
                geldig altijd
                    Een Jutter is noodweer verklaard
                    indien hij noodweer verklaard is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        Diagnostic d = enigeFout(m);
        assertTrue(d.message().contains("geen basisregel met die naam gevonden")
                && d.message().contains("objectcreatie"), d.message());
        assertFalse(d.message().contains("regelgroep"), d.message());
    }

    // -------------------------------------------------------------------- what stays clean

    @Test void blok_voor_de_groepskop_blijft_schoon() {
        String src = MODEL + BASIS + UITZONDERING + "Regelgroep premie\n" + PREMIE;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.prioritizedDefaults.size(), "de basis en haar uitzondering horen één defaults-groep te worden");
        assertEquals(List.of("bergingspremie"), m.regelGroepen.get(0).leden());
    }

    @Test void blok_achter_een_definitie_die_de_groep_sluit_blijft_schoon() {
        String src = MODEL + BASIS + "Regelgroep premie\n" + PREMIE
                + "Parameter de noodweerdrempel : Numeriek (geheel getal)\n\n" + UITZONDERING;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.prioritizedDefaults.size());
        assertEquals(List.of("bergingspremie"), m.regelGroepen.get(0).leden());
    }
}
