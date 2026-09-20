package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registration guard against the NORMATIVE CALENDAR TABLE (`RUNTIME-SEMANTICS.md` ch. 3.8), the
 * producer half of WF-017 (`IR-SPEC.md` ch. 2.9).
 *
 * <p><b>Red first, measured 2026-09-20 on the commit before this card.</b> The guard compared against
 * eighteen names — the singular and the plural of the nine units — while the table recognises THIRTY.
 * Every one of these five sources emitted with exit 0 and an EMPTY {@code diagnostics} array:
 *
 * <pre>
 *   Dagsoort de wk (mv: wks);                  dagsoorten ["wk"]            dpe {wk, wks}
 *   Dagsoort de secondes (mv: secondessen);    dagsoorten ["secondes"]      dpe {secondes, secondessen}
 *   Dagsoort de millisecondes (mv: …);         dagsoorten ["millisecondes"] dpe {millisecondes, …}
 *   Dagsoort de vaardag (mv: wk);              dagsoorten ["vaardag"]       dpe {vaardag, wk}
 *   Dagsoort de vaardag (mv: secondes);        dagsoorten ["vaardag"]       dpe {vaardag, secondes}
 * </pre>
 *
 * With WF-017 widened to the thirty spellings each of those documents is verifier-REFUSED, so the IR
 * would be refusing what its own producer made without a word — which is exactly the state this card
 * exists to end.
 *
 * <p><b>Why the two positions get different answers.</b> A declared PLURAL that stands in the table is
 * dropped with a WARNING and the document still emits: the day kind survives under its own name, only
 * that one spelling does not reach the registry, and what comes out is verifier-clean. A declared NAME
 * cannot be treated that way — dropping it would leave its §9.8 definition pointing at a day kind
 * {@code dagsoorten} no longer declares, which the verifier refuses in its own right. So the name is an
 * ERROR, which blocks the emission entirely, and that is also what the grammar already does for the
 * fourteen unit names that are keywords: no IR at all rather than an IR nobody may use.
 *
 * <p>The counter-measurement is the load-bearing half: the twelve spellings must stay usable as
 * ORDINARY calendar units. {@code corpus/golden/jutterswet-vervaltermijn} carries {@code wk},
 * {@code mnd} and {@code kw} that way and gate 10 holds its emission byte-identical.
 */
class DagsoortKalenderspellingTest {

    private static RegelSpraakModel resolveer(String bron) {
        return Resolver.resolve(bron);
    }

    private static List<Diagnostic> van(RegelSpraakModel m, Severity s) {
        return m.diagnostics.stream().filter(d -> d.severity() == s).toList();
    }

    /** The single diagnostic of severity {@code s}, or an assertion failure naming what was there instead. */
    private static Diagnostic enigeDiagnostic(RegelSpraakModel m, Severity s) {
        List<Diagnostic> d = van(m, s);
        assertEquals(1, d.size(), "exactly one " + s + ": " + m.diagnostics);
        return d.get(0);
    }

    private static String enige(RegelSpraakModel m, Severity s) {
        return enigeDiagnostic(m, s).message();
    }

    // ---------------- the declared NAME: an ERROR, so nothing is emitted ----------------

    @Test void een_afkorting_als_dagsoortnaam_is_een_fout() {
        // THE ROW. `wk` is the week's abbreviation in the ch. 3.8 table, so a day kind of that name makes
        // a document WF-017 refuses. Silent before this card.
        RegelSpraakModel m = resolveer("Dagsoort de wk (mv: wks);");
        assertTrue(enige(m, Severity.ERROR).contains("'wk'"), "the refusal quotes the name it refused");
        assertFalse(m.isOk(), "an ERROR is what stops Resolver.irModus writing the document at all");
        assertEquals(List.of(), m.dagsoorten, "and the refused name never reaches the wire");
        assertEquals(List.of(), List.copyOf(m.dagsoortPerEenheid.keySet()),
                "nor does any spelling of it — the plural would bind a day kind that is not declared");
    }

    @Test void een_tweede_meervoudsvorm_als_dagsoortnaam_is_een_fout() {
        // The OTHER class of the twelve: `secondes` is not an abbreviation but a second plural the table
        // carries beside `seconden`. A guard repaired for abbreviations alone would still admit it.
        RegelSpraakModel m = resolveer("Dagsoort de secondes (mv: secondessen);");
        assertTrue(enige(m, Severity.ERROR).contains("'secondes'"), "the refusal quotes the name it refused");
        assertFalse(m.isOk());
        assertEquals(List.of(), m.dagsoorten);
    }

    @Test void een_ingebouwde_meervoudsnaam_blijft_een_fout() {
        // The regression half: of the eighteen names the old guard knew, four PARSE as a day-kind name
        // (`uren`, `minuten`, `seconden`, `milliseconden`). Before this card such a document emitted with
        // exit 0, a warning about the SPELLING, and `dagsoorten: ["uren"]` on the wire — which WF-017's
        // declared-name clause has refused all along. The name position now stops it at the producer.
        RegelSpraakModel m = resolveer("Dagsoort de uren (mv: urens);");
        assertTrue(enige(m, Severity.ERROR).contains("'uren'"));
        assertFalse(m.isOk());
        assertEquals(List.of(), m.dagsoorten);
    }

    // ---------------- a declared PLURAL: a WARNING, the entry dropped, the document emits ----------------

    @Test void een_afkorting_als_meervoudsvorm_wordt_niet_geregistreerd() {
        RegelSpraakModel m = resolveer("""
            Dagsoort de zeildag (mv: zeildagen);
            Dagsoort de vaardag (mv: wk);
            """);
        Diagnostic d = enigeDiagnostic(m, Severity.WARNING);
        assertTrue(d.message().contains("'wk'"), "the warning quotes the spelling it dropped");
        // The line was hard-coded 0 until 2026-09-20, which points an author at nothing when a document
        // declares several day kinds — as this one does, so a warning that still said 0 or 1 fails here.
        assertEquals(2, d.line(), "and points at the declaration it dropped it from: " + d);
        assertTrue(m.isOk(), "a dropped plural leaves a well-formed document — only that spelling is gone");
        assertEquals(List.of("zeildag", "vaardag"), m.dagsoorten);
        assertEquals(List.of("zeildag", "zeildagen", "vaardag"),
                List.copyOf(m.dagsoortPerEenheid.keySet()),
                "the day kind keeps its own name as a unit spelling; `wk` stays the calendar week");
    }

    @Test void een_tweede_meervoudsvorm_als_meervoudsvorm_wordt_niet_geregistreerd() {
        RegelSpraakModel m = resolveer("Dagsoort de vaardag (mv: secondes);");
        assertTrue(enige(m, Severity.WARNING).contains("'secondes'"));
        assertTrue(m.isOk());
        assertEquals(List.of("vaardag"), List.copyOf(m.dagsoortPerEenheid.keySet()));
    }

    // ---------------- the counter-measurement: the guard refuses nothing else ----------------

    @Test void een_gewone_dagsoort_wordt_onveranderd_geregistreerd() {
        // A guard that refuses too much is as wrong as one that refuses nothing: every spelling OUTSIDE
        // the table is a declared day kind (ch. 3.8), and both of this one's spellings are.
        RegelSpraakModel m = resolveer("Dagsoort de schouwdag (mv: schouwdagen);");
        assertEquals(List.of(), m.diagnostics, "no diagnostic on an ordinary day kind");
        assertEquals(List.of("schouwdag"), m.dagsoorten);
        assertEquals(List.of("schouwdag", "schouwdagen"), List.copyOf(m.dagsoortPerEenheid.keySet()));
    }

    @Test void de_afkortingen_blijven_gewone_kalendereenheden() {
        // The direction the corpus depends on: `wk`, `mnd` and `kw` are live calendar units in
        // `jutterswet-vervaltermijn`. The guard reads the day-kind registry and must not touch a unit
        // written on an attribute.
        RegelSpraakModel m = resolveer("""
            Objecttype de Vondst (mv: Vondsten)
                de bewaartermijn Numeriek (geheel getal) met eenheid wk;
                de meldtermijn Numeriek (geheel getal) met eenheid mnd;
                de keurtermijn Numeriek (geheel getal) met eenheid kw;
            """);
        assertEquals(List.of(), m.diagnostics, "a calendar unit on an attribute is not a day-kind spelling");
        assertEquals(List.of(), m.dagsoorten);
    }
}
