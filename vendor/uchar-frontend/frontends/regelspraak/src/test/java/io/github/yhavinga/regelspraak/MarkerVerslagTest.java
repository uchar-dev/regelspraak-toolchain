package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WF-113 (b) on the producer route (IR-SPEC ch. 2, 2026-09-23): every {@code Onopgelost} in a document the frontend
 * WRITES is accounted for by a WARNING whose message contains the marker's {@code bron} verbatim. The verifier holds
 * the rule on every document that reaches it; this suite holds it where the documents are made, on the emitted JSON
 * ({@link Verslag}).
 *
 * <p><b>Every marker route, in three classes.</b> A marker whose {@code reden} is set is reported by the resolver's
 * survivor sweep, whose text quotes the bron; every route test in {@link StilleMarkerTest} passes through its
 * {@code markerMetMelding}, which runs {@link Verslag#verantwoord} on the emission. A marker whose {@code reden} is
 * null is spoken for by its own route, and {@link MarkerRedenPinTest} lists each such family with the diagnostic that
 * speaks: where that is a WARNING, the route is pinned here with a sentence that reaches it; where it is an ERROR,
 * the frontend writes no document at all (irModus writes nothing, and {@code IrEmitter.emit} refuses the model under
 * WF-113 (a)), so there is no wire for (b) to hold on. {@link #elke_gesanctioneerde_familie_is_ingedeeld()} keeps
 * that split complete: a new family on the pin list fails here until it is placed.
 */
class MarkerVerslagTest {

    /** A Jutter with a set of vondsten, a temporal attribute and a Jutter-to-Jutter relation: enough for every
     *  aggregation route below and for a rule that binds 'Jutter' twice. */
    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het totaalgewicht Numeriek (getal met 2 decimalen);
            de maandvangst Numeriek (getal met 2 decimalen) voor elke maand;
            het vangsttotaal Numeriek (getal met 2 decimalen);
            is bevoegd kenmerk (bijvoeglijk);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);
            is gekeurd kenmerk (bijvoeglijk);

        Feittype vondstaanmelding
            de aanmelder Jutter
            de aangemelde vondst (mv: aangemelde vondsten) Vondst
        Eén aanmelder meldt meerdere aangemelde vondsten

        Feittype vriendschap
            de jutter Jutter
            de strandmaat (mv: strandmaten) Jutter
        Eén jutter heeft meerdere strandmaten
        """;

    /** Each null-reden family of {@link MarkerRedenPinTest#GESANCTIONEERD}, placed: the WARNING routes it has and
     *  the test here that holds (b) on each, or the ERROR that keeps its document from being written. */
    private static final Map<String, String> INDELING = new LinkedHashMap<>();
    static {
        INDELING.put("RuleChecker.java#keurGeheelPeriode", "ERROR (§8.2 over een statisch onderwerp): geen document");
        INDELING.put("RuleChecker.java#dagsoortOperandFout", "ERROR (Tabel 57): geen document");
        INDELING.put("ExpressieBouwer.java#bouwFunctie", "WARNING: aggregatie_over_een_ongedeclareerd_lid, "
                + "dimensieselectie_of_gedurende_op_een_aggregatie; ERROR (WF-037, aantal gevulde): geen document");
        INDELING.put("ExpressieBouwer.java#keurVouwbron", "WARNING: vouwbron_geneste_verzamelingsstap, "
                + "vouwbron_tussenstap, vouwbron_rolanker, vouwbron_bindt_niet_op_de_geschreven_naam");
        INDELING.put("ExpressieBouwer.java#aggregatieFilter", "WARNING (van bouwFunctie): deelselectie_op_een_aggregatie_operand");
        INDELING.put("ExpressieBouwer.java#keurRangtelwoord",
                "WARNING: rangtelwoord_over_twee_bindingen; ERROR (positie voorbij de bindingen): geen document");
    }

    // ---------------- the predicate itself ----------------

    @Test void het_predicaat_leest_de_json_en_niet_het_model() {
        String marker = "{\"node\": \"Onopgelost\", \"bron\": \"de som van \\u00e9\\u00e9n \\\"lid\\\"\"}";
        String goed = document(marker, "{\"node\": \"Diagnostic\", \"severity\": \"WARNING\", "
                + "\"message\": \"onopgelost: x \\u2014 'de som van \\u00e9\\u00e9n \\\"lid\\\"' blijft\", \"line\": 3}");
        Verslag.verantwoord(goed);
        assertEquals(java.util.List.of("de som van één \"lid\""), Verslag.markerBronnen(goed));

        String zonderVerslag = document(marker, "");
        assertThrows(AssertionError.class, () -> Verslag.verantwoord(zonderVerslag), "(b): een marker, lege lijst");
        String andereBron = document(marker, "{\"node\": \"Diagnostic\", \"severity\": \"WARNING\", "
                + "\"message\": \"onopgelost: x \\u2014 'de som van een lid' blijft\", \"line\": 3}");
        assertThrows(AssertionError.class, () -> Verslag.verantwoord(andereBron), "(b): een melding over iets anders");
        String fout = document("null", "{\"node\": \"Diagnostic\", \"severity\": \"ERROR\", \"message\": \"x\", \"line\": 1}");
        assertThrows(AssertionError.class, () -> Verslag.verantwoord(fout), "(a): een ERROR op de wire");

        // (b) counts a blank bron as absent: an empty one is contained in every message, so the WARNING beside it
        // would account for it under a literal reading, and a null one is contained in none
        String melding = "{\"node\": \"Diagnostic\", \"severity\": \"WARNING\", \"message\": \"iets\", \"line\": 1}";
        for (String bron : new String[] { "\"\"", "\"   \"", "null" }) {
            String zonderBron = document("{\"node\": \"Onopgelost\", \"bron\": " + bron + "}", melding);
            assertThrows(AssertionError.class, () -> Verslag.verantwoord(zonderBron), "(b): een marker met bron " + bron);
        }
    }

    // ---------------- the null-reden WARNING routes ----------------

    @Test void aggregatie_over_een_ongedeclareerd_lid() {
        geschreven(MODEL + """

            Regel totaalgewicht
                geldig altijd
                    Het totaalgewicht van een Jutter moet berekend worden als de som van de schelpen van alle aangemelde vondsten van de Jutter.
            """, "is geen (meervoud van een) gedeclareerd attribuut van Vondst");
    }

    @Test void deelselectie_op_een_aggregatie_operand() {
        geschreven(MODEL + """

            Regel totaalgewicht
                geldig altijd
                    Het totaalgewicht van een Jutter moet berekend worden als de som van het gewicht van alle aangemelde vondsten van de Jutter die een kind hebben.
            """, "'die/dat'-subselectie op een aggregatie-operand");
    }

    @Test void dimensieselectie_of_gedurende_op_een_aggregatie() {
        geschreven(MODEL + """

            Regel vangsttotaal
                geldig altijd
                    Het vangsttotaal van een Jutter moet berekend worden als het totaal van de maandvangst van de Jutter gedurende de tijd dat de strandmaat van de Jutter bevoegd is.
            """, "een dimensieselectie / 'gedurende de tijd dat' op een aggregatie");
    }

    @Test void vouwbron_geneste_verzamelingsstap() {
        geschreven(GenesteVouwbronTest.MODEL_A + GenesteVouwbronTest.GEMENGDE_KETENREGEL,
                "geneste verzamelingsstap in een vouwbron");
    }

    @Test void vouwbron_tussenstap() {
        geschreven(GenesteVouwbronTest.MODEL_B + GenesteVouwbronTest.VREEMD_ANKERREGEL, "tussenstap in een vouwbron");
    }

    @Test void vouwbron_rolanker() {
        geschreven(GenesteVouwbronTest.ROLANKERMODEL + GenesteVouwbronTest.VERRE_ROLANKERREGEL,
                "rolanker in een vouwbron");
    }

    @Test void vouwbron_bindt_niet_op_de_geschreven_naam() {
        geschreven(GenesteVouwbronTest.MODEL_B + GenesteVouwbronTest.BOTSENDE_EENSTAPSREGEL,
                "vouwbron bindt niet op de geschreven naam");
    }

    @Test void rangtelwoord_over_twee_bindingen() {
        geschreven(MODEL + """

            Regel totaalgewicht
                geldig altijd
                    Het totaalgewicht van een Jutter moet berekend worden als het vangsttotaal van de laatstgenoemde Jutter
                    indien een Jutter bevoegd is.
            """, "rangtelwoord: de regel bindt 2 onderwerpen 'Jutter'");
    }

    // ---------------- a sweep route that does not pass through StilleMarkerTest's helper ----------------

    @Test void een_telwoord_onder_een_naast_de_sweepmelding() {
        // the fold's own decline speaks AND the sweep reports the surviving marker (StilleMarkerTest's
        // een_luide_route_wordt_niet_twee_keer_gemeld asserts the count, not the wire)
        geschreven(StilleMarkerTest.MODEL + """
            Regel bevoegde ploeg
                geldig altijd
                    Een Schouwploeg is bevoegd
                    indien ten minste 0 van de wachten van de Schouwploeg gediplomeerd zijn.
            """, "telwoord van de kwantor");
    }

    // ---------------- completeness ----------------

    @Test void elke_gesanctioneerde_familie_is_ingedeeld() {
        Set<String> families = MarkerRedenPinTest.GESANCTIONEERD.stream()
                .map(p -> p.bestand() + "#" + p.methode()).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(families, new TreeSet<>(INDELING.keySet()),
                "elke null-reden-familie van MarkerRedenPinTest hoort hier als WARNING-route met een zin of als "
                        + "ERROR-route zonder document ingedeeld te staan");
    }

    // ---------------- helpers ----------------

    /** The route is reached (its own WARNING is there), the model carries no ERROR so the document is written, a
     *  marker is on the wire, and WF-113 holds on the JSON. */
    private static void geschreven(String bron, String route) {
        RegelSpraakModel m = Resolver.resolve(bron);
        assertTrue(m.isOk(), () -> "een ERROR houdt het document tegen, dus de route wordt hier niet bereikt: "
                + m.diagnostics);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains(route)),
                () -> "de route '" + route + "' wordt niet bereikt: " + m.diagnostics);
        String json = IrEmitter.emit(m);
        assertFalse(Verslag.markerBronnen(json).isEmpty(), () -> "geen marker op de wire:\n" + json);
        Verslag.verantwoord(json);
    }

    private static String document(String regelwaarde, String diagnostic) {
        return "{\"format\": \"uchar-ir-v1\", \"model\": {}, \"rules\": {\"resolvedRegels\": [{\"node\": "
                + "\"ResolvedRegel\", \"waarde\": " + regelwaarde + "}]}, \"diagnostics\": [" + diagnostic + "]}";
    }
}
