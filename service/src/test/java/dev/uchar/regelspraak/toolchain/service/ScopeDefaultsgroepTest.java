package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defaults-group half of {@link ScopeVolledigheidTest}: a defaults group the scope carries reads what it reads
 * in the scope too. A rule with an {@code Uitzondering op}, an {@code Afwijking van} or an {@code Uitzonderingen
 * ongeordend} block above it is no ordinary rule after the lowering but a defaults group, and the opgave marker and
 * an exception for a subtype generate one. When such a group reads an attribute or characteristic that only a form
 * outside the scope writes, the service used to start and decide the group on an empty reading: 10 where the whole
 * model gives 5, {@code null} where it gives a date. {@link BeslisserKern#bouw} now refuses that start, naming the
 * group as its author wrote it (the rule the group belongs to, or the generated group by its name), the element and
 * the writers outside the scope; an {@code invoerElementen} declaration and {@code scopeControleUit} still opt out.
 *
 * <p>Every source is self-contained Jutterswet material, so the core compiles its beans in memory.
 */
class ScopeDefaultsgroepTest {

    private static final String TIJD = """
        Eenheidsysteem Tijd
            de dag dg
            de week wk = 7 dg
            de maand mnd
            het jaar jr = 12 mnd

        """;

    private static final String BERGDAG = """

        Regel bergdag
            geldig altijd
                De bergdag van een Jutter moet berekend worden als zijn vinddag plus 3 dg.
        """;

    /** R1/R2: the exception's justification reads 'bergdag', which an ordinary rule writes. */
    private static final String UITZONDERING = TIJD + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de premie Numeriek (geheel getal);
        """ + BERGDAG + """

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10.

        Uitzondering op premie: late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien zijn bergdag later is dan 01-06-2026.
        """;

    /** R3: the base default's consequence reads 'bergdag'. */
    private static final String AFWIJKING = TIJD + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de gemelde meldtermijn Datum in dagen;
            de meldtermijn Datum in dagen;
        """ + BERGDAG + """

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.

        Afwijking van meldtermijn: gemelde meldtermijn gaat voor
            geldig altijd
                De meldtermijn van een Jutter moet gesteld worden op zijn gemelde meldtermijn
                indien zijn gemelde meldtermijn gevuld is.
        """;

    /** R4: the opgave marker generates 'opgave boven berekening meldtermijn', which reads 'berekende meldtermijn'. */
    private static final String OPGAVE = TIJD + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de meldtermijn Datum in dagen (mag opgegeven worden);
        """ + BERGDAG + """

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.
        """;

    /** R5: the exception checks the characteristic 'laatgeborgen', which an ordinary rule attributes. */
    private static final String KENMERK = TIJD + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de premie Numeriek (geheel getal);
            laatgeborgen kenmerk (bijvoeglijk);

        Regel laatgeborgen
            geldig altijd
                Een Jutter is laatgeborgen
                indien zijn vinddag later is dan 01-06-2026.

        Regel premie
            geldig altijd
                De premie van een Jutter moet gesteld worden op 10.

        Uitzondering op premie: late berging
            geldig altijd
                De premie van een Jutter moet gesteld worden op 5
                indien hij laatgeborgen is.
        """;

    /** R6 and R7: 'bergdag' is a group of its own; the reader of it is a group (R6) or an ordinary rule (R7). */
    private static final String BERGDAGGROEP = TIJD + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de gemelde bergdag Datum in dagen;
            de bergdag Datum in dagen;
            de gemelde meldtermijn Datum in dagen;
            de meldtermijn Datum in dagen;
        """ + BERGDAG + """

        Afwijking van bergdag: gemelde bergdag gaat voor
            geldig altijd
                De bergdag van een Jutter moet gesteld worden op zijn gemelde bergdag
                indien zijn gemelde bergdag gevuld is.

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.
        """;
    private static final String GROEP_LEEST_GROEP = BERGDAGGROEP + """

        Afwijking van meldtermijn: gemelde meldtermijn gaat voor
            geldig altijd
                De meldtermijn van een Jutter moet gesteld worden op zijn gemelde meldtermijn
                indien zijn gemelde meldtermijn gevuld is.
        """;

    /** R7: the same chain with an ordinary rule as the reader, which the check always refused. */
    private static final String GEWOON = TIJD + """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de vinddag Datum in dagen;
            de bergdag Datum in dagen;
            de meldtermijn Datum in dagen;
        """ + BERGDAG + """

        Regel meldtermijn
            geldig altijd
                De meldtermijn van een Jutter moet berekend worden als zijn bergdag plus 6 mnd.
        """;

    private static final String BUITEN_DE_SCOPE =
            " vallen buiten de scope en het element is niet als invoerElement gedeclareerd";

    // ---------------- R1, R3, R4, R5, R6: a group the scope carries reads what only a form outside it writes ----------------

    @Test
    void r1_de_rechtvaardiging_van_een_uitzondering_leest_een_attribuut_van_buiten_de_scope() {
        assertEquals(List.of("defaults-groep van regel 'premie' in entrypoint 'premie' leest attribuut 'bergdag' van "
                        + "'jutter', maar de enige producent(en) [bergdag]" + BUITEN_DE_SCOPE),
                scopeSchendingen(UITZONDERING, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void r3_de_consequentie_van_de_basis_onder_een_afwijking_leest_van_buiten_de_scope() {
        assertEquals(List.of("defaults-groep van regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut "
                        + "'bergdag' van 'jutter', maar de enige producent(en) [bergdag]" + BUITEN_DE_SCOPE),
                scopeSchendingen(AFWIJKING, descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
    }

    @Test
    void r4_de_groep_van_de_opgavemarkering_noemt_zich_bij_haar_gegenereerde_naam() {
        assertEquals(List.of("gegenereerde defaults-groep 'opgave boven berekening meldtermijn' in entrypoint "
                        + "'meldtermijn' leest attribuut 'berekende meldtermijn' van 'jutter', maar de enige "
                        + "producent(en) [meldtermijn]" + BUITEN_DE_SCOPE),
                scopeSchendingen(OPGAVE, descriptor("meldtermijn", List.of("opgave boven berekening meldtermijn"),
                        "Jutter.meldtermijn", "")));
    }

    @Test
    void r5_een_kenmerkcheck_in_de_rechtvaardiging_leest_een_kenmerk_van_buiten_de_scope() {
        assertEquals(List.of("defaults-groep van regel 'premie' in entrypoint 'premie' leest kenmerk 'laatgeborgen' "
                        + "van 'jutter', maar de enige producent(en) [laatgeborgen]" + BUITEN_DE_SCOPE),
                scopeSchendingen(KENMERK, descriptor("premie", List.of("premie"), "Jutter.premie", "")));
    }

    @Test
    void r6_een_groep_leest_wat_een_andere_groep_buiten_de_scope_schrijft() {
        assertEquals(List.of("defaults-groep van regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut "
                        + "'bergdag' van 'jutter', maar de enige producent(en) [bergdag]" + BUITEN_DE_SCOPE),
                scopeSchendingen(GROEP_LEEST_GROEP,
                        descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
    }

    // ---------------- the flanks: R2, R7 and the opt-outs ----------------

    @Test
    void r2_met_de_schrijver_in_de_scope_start_de_dienst_en_beslist_de_uitzondering() {
        BeslisserKern kern = bouw(UITZONDERING,
                descriptor("premie", List.of("bergdag", "premie"), "Jutter.premie", ""));
        assertEquals(5, premie(kern, "2026-05-30", null), "de bergdag 02-06-2026 valt na 01-06-2026");
        assertEquals(10, premie(kern, "2026-03-01", null));
    }

    @Test
    void r7_een_gewone_regel_als_lezer_weigert_als_voorheen() {
        String verwacht = "regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut 'bergdag' van 'jutter', "
                + "maar de enige producent(en) [bergdag]" + BUITEN_DE_SCOPE;
        assertEquals(List.of(verwacht), scopeSchendingen(GEWOON,
                descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
        // the producer index counts a group as a writer, so an ordinary rule that reads a group's outcome refuses too
        assertEquals(List.of(verwacht), scopeSchendingen(BERGDAGGROEP,
                descriptor("meldtermijn", List.of("meldtermijn"), "Jutter.meldtermijn", "")));
    }

    @Test
    void het_invoerElement_laat_de_aanroeper_de_lezing_van_de_groep_leveren() {
        BeslisserKern kern = bouw(UITZONDERING, descriptor("premie", List.of("premie"), "Jutter.premie",
                ", \"invoerElementen\": [\"Jutter.bergdag\"]"));
        assertEquals(5, premie(kern, "2026-05-30", "2026-06-02"));
        assertEquals(10, premie(kern, "2026-05-30", "2026-05-31"));
    }

    @Test
    void scopeControleUit_laat_de_groep_ongetoetst_starten() {
        BeslisserKern kern = bouw(UITZONDERING, descriptor("premie", List.of("premie"), "Jutter.premie",
                ", \"scopeControleUit\": true"));
        assertNotNull(kern.entrypoint("premie"));
    }

    // ---------------- helpers ----------------

    private static String descriptor(String pad, List<String> regelnamen, String uitvoer, String extra) {
        String namen = regelnamen.stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(", "));
        return """
            { "dienst": {"naam":"Jutterswet scopeproef","versie":"1"},
              "entrypoints": [ { "naam":"%s","pad":"%s",
                "scope": {"regelnamen": [%s]},
                "invoer": ["Jutter"], "uitvoer": ["%s"]%s } ],
              "parameters": {} }""".formatted(pad, pad, namen, uitvoer, extra);
    }

    private static BeslisserKern bouw(String bron, String descriptor) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("jutterswet.rgs", bron);
        return BeslisserKern.bouw(descriptor, m, KernConfig.strikt(), Thread.currentThread().getContextClassLoader());
    }

    /** The messages of the refusal, which must consist of scope violations only. */
    private static List<String> scopeSchendingen(String bron, String descriptor) {
        DienstFout f = assertThrows(DienstFout.class, () -> bouw(bron, descriptor),
                "de dienst hoort niet te starten op een scope die een lezing mist");
        assertTrue(f.schendingen().stream().allMatch(s -> s.soort() == Schending.Soort.SCOPE_ONVOLLEDIG),
                () -> "verwacht alleen SCOPE_ONVOLLEDIG: " + f.schendingen());
        return f.schendingen().stream().map(Schending::bericht).toList();
    }

    /** The premie the entry point 'premie' gives one Jutter with this vinddag (and bergdag, when given). */
    private static int premie(BeslisserKern kern, String vinddag, String bergdag) {
        String attributen = "\"vinddag\": \"" + vinddag + "\""
                + (bergdag == null ? "" : ", \"bergdag\": \"" + bergdag + "\"");
        ObjectNode a = kern.verwerk("premie", parse(
                "{ \"instanties\": [ { \"type\": \"Jutter\", \"id\": \"J1\", \"attributen\": { " + attributen + " } } ] }"));
        JsonNode premie = a.path("instanties").path(0).path("attributen").get("premie");
        assertNotNull(premie, () -> "antwoord: " + a);
        assertTrue(premie.isNumber(), () -> "antwoord: " + a);
        return premie.intValue();
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
