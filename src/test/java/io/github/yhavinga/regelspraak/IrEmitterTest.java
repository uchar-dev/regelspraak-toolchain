package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The v0 IR emitter also writes the symbolic extension fields the uchar wire admits — the
 * navigation/scope/creation role bindings — each derived from the declared names and emitted
 * only where uniquely derivable. These tests exercise the EMITTED JSON (not just the resolved
 * records): the four families plus the fold-source discipline, so a regression in the wire shape
 * is caught here. Assertions compare against whitespace-collapsed JSON so they are robust to
 * indentation while still pinning the exact field/value sequence.
 */
class IrEmitterTest {

    /** The emitted v0 IR JSON with all whitespace removed — deterministic, so an exact
     *  compacted fragment pins the wire shape without depending on indentation. */
    private static String emitCompact(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        return IrEmitter.emit(m).replaceAll("\\s+", "");
    }

    @Test void navigatiebindingen_op_ketenlezing_subject_uitwaarts() {
        // "het bedrijfsniveau van het bedrijf van zijn afdeling": a §13.4.1 declared chain
        // Werknemer -[afdeling]-> Afdeling -[bedrijf]-> Bedrijf. One binding per hop, subject-
        // outward, each naming the REACHED role by its declared name.
        String src = """
            Objecttype de Werknemer
                de rang Numeriek (geheel getal);
            Objecttype de Afdeling
            Objecttype het Bedrijf
                het bedrijfsniveau Numeriek (geheel getal);
            Feittype aanstelling
                de werknemer Werknemer
                de afdeling Afdeling
            Eén afdeling telt meerdere werknemers
            Feittype onderdeel
                de afdeling Afdeling
                het bedrijf Bedrijf
            Eén bedrijf telt meerdere afdelingen
            Regel rang
                geldig altijd
                    De rang van een Werknemer moet gesteld worden op het bedrijfsniveau van het bedrijf van zijn afdeling.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"navigatieBindingen\":["
                + "{\"node\":\"RolBinding\",\"feittype\":\"aanstelling\",\"rol\":\"afdeling\"},"
                + "{\"node\":\"RolBinding\",\"feittype\":\"onderdeel\",\"rol\":\"bedrijf\"}]"),
                () -> "keten-bindingen (subject-uitwaarts, gedeclareerde namen) ontbreken:\n" + c);
    }

    @Test void navigatiebinding_op_genavigeerde_kenmerkcheck() {
        // "indien zijn afdeling kernafdeling is": a navigated §8.1.8 characteristic check binds
        // the reached role of the one hop.
        String src = """
            Objecttype de Werknemer
                is sleutelkracht kenmerk (bijvoeglijk);
            Objecttype de Afdeling
                is kernafdeling kenmerk (bijvoeglijk);
            Feittype aanstelling
                de werknemer Werknemer
                de afdeling Afdeling
            Eén afdeling telt meerdere werknemers
            Regel sleutelkracht
                geldig altijd
                    Een Werknemer is sleutelkracht indien zijn afdeling kernafdeling is.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"navigatieBinding\":{\"node\":\"RolBinding\",\"feittype\":\"aanstelling\",\"rol\":\"afdeling\"}"),
                () -> "navigatieBinding op de genavigeerde kenmerkcheck ontbreekt:\n" + c);
    }

    @Test void scoperol_op_parent_scoped_uniciteit() {
        // "De X van alle Werknemer van de afdeling moeten uniek zijn": parent-scoped uniqueness;
        // scopeRol names the scope-parent relation (the reference field made symbolic).
        String src = """
            Objecttype de Werknemer
                het personeelsnummer Numeriek (geheel getal);
            Objecttype de Afdeling
            Feittype aanstelling
                de werknemer Werknemer
                de afdeling Afdeling
            Eén afdeling telt meerdere werknemers
            Regel uniek
                geldig altijd
                    De personeelsnummer van alle Werknemer van de afdeling moeten uniek zijn.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"scopeRol\":{\"node\":\"RolBinding\",\"feittype\":\"aanstelling\",\"rol\":\"afdeling\"}"),
                () -> "scopeRol op de parent-scoped uniciteit ontbreekt:\n" + c);
    }

    @Test void feitcreatie_symbolisch_blok_is_heel_of_niet() {
        // A §13.4.7 feitcreatie with one tussenstap: the whole symbolic block (created fact type,
        // both ends, the final join) plus a RolBinding on the intermediate hop.
        String src = """
            Objecttype het Bedrijf
            Objecttype de Afdeling
            Objecttype het Project
            Feittype onderdeel
                de afdeling Afdeling
                het bedrijf Bedrijf
            Eén bedrijf telt meerdere afdelingen
            Feittype uitvoering
                de afdeling Afdeling
                het project (mv: projecten) Project
            Eén afdeling telt meerdere projecten
            Feittype sponsoring
                het sponsorbedrijf Bedrijf
                het sponsorproject (mv: sponsorprojecten) Project
            Eén sponsorbedrijf telt meerdere sponsorprojecten
            Regel sponsoring
                geldig altijd
                    Een sponsorproject van een Bedrijf is een project van de afdeling van het bedrijf.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"viaOuderVeld\":false,"
                + "\"rolBinding\":{\"node\":\"RolBinding\",\"feittype\":\"onderdeel\",\"rol\":\"afdeling\"}"),
                () -> "tussenstap-rolBinding ontbreekt:\n" + c);
        assertTrue(c.contains(
                "\"feittype\":\"sponsoring\",\"ankerRol\":\"sponsorbedrijf\",\"doelRol\":\"sponsorproject\","
                + "\"joinRol\":{\"node\":\"RolBinding\",\"feittype\":\"uitvoering\",\"rol\":\"project\"}"),
                () -> "het symbolische feitcreatieblok ontbreekt of is onvolledig:\n" + c);
    }

    @Test void fold_bron_argument_draagt_geen_navigatiebindingen() {
        // The fold's SOURCE argument routes through bronRol; even though its type (Persoon) differs
        // from the fold's outer subject (Vlucht) — the by-pair shape — it must carry NO
        // navigatieBindingen (IR-SPEC ch. 4.1a; the uchar verifier rejects nav bindings in a fold slot).
        String src = """
            Objecttype de Vlucht
                het totaalbedrag Numeriek (geheel getal);
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype vluchtdeelname
                de reis Vlucht
                de passagier (mv: passagiers) Persoon
            Eén reis telt meerdere passagiers
            Regel som
                geldig altijd
                    Het totaalbedrag van een reis moet berekend worden als de som van de leeftijd van alle passagiers van de reis.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"bronRol\":{\"node\":\"RolBinding\",\"feittype\":\"vluchtdeelname\",\"rol\":\"passagier\"}"),
                () -> "de fold moet zijn bronRol dragen:\n" + c);
        assertFalse(c.contains("navigatieBindingen"),
                () -> "een fold-bron-argument mag GEEN navigatieBindingen dragen:\n" + c);
    }

    @Test void fold_filter_elementlezing_draagt_geen_navigatiebindingen() {
        // The filter is a subselectie over the fold ELEMENT: "die kleiner zijn dan 18" reads the
        // passagier's OWN leeftijd — an own read, not a navigation from the outer reis. So the filter
        // read carries no spurious by-pair navigatieBindingen (the emitter walks the filter with the
        // element as subject).
        String src = """
            Objecttype de Vlucht
                het totaalbedrag Numeriek (geheel getal);
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype vluchtdeelname
                de reis Vlucht
                de passagier (mv: passagiers) Persoon
            Eén reis telt meerdere passagiers
            Regel som
                geldig altijd
                    Het totaalbedrag van een reis moet berekend worden als de som van de leeftijd van alle passagiers van de reis die kleiner zijn dan 18.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"bronRol\":{\"node\":\"RolBinding\",\"feittype\":\"vluchtdeelname\",\"rol\":\"passagier\"}"),
                () -> "de fold moet zijn bronRol dragen:\n" + c);
        assertFalse(c.contains("navigatieBindingen"),
                () -> "een element-eigen filter-lezing mag GEEN navigatieBindingen dragen:\n" + c);
    }

    @Test void tijdsevenredig_deel_draagt_basistype_geen_sentinel() {
        // §7.3.2/§13.4.17.58: the pro-rata node carries its BASE's value type on the wire — the
        // uchar spec forbids the unknown-type sentinel inside a structured node (its WF-034), so
        // an onbekend-typed TIJDSEVENREDIG_DEEL cannot enter its corpus.
        String src = """
            Objecttype de Bakenmeester (mv: Bakenmeesters) (bezield)
                het onderhoudstarief Numeriek (getal met 2 decimalen) voor elk jaar;
                de onderhoudsbijdrage Numeriek (getal met 2 decimalen) voor elke maand;
                is dienstdoend kenmerk (bijvoeglijk) voor elke maand;
            Regel onderhoudsbijdrage
                geldig altijd
                    De onderhoudsbijdrage van een Bakenmeester moet gesteld worden op het tijdsevenredig deel per maand van zijn onderhoudstarief gedurende de tijd dat hij dienstdoend is.
            """;
        String c = emitCompact(src);
        int at = c.indexOf("\"kind\":\"TIJDSEVENREDIG_DEEL\"");
        assertTrue(at >= 0, () -> "de pro-rata-node ontbreekt:\n" + c);
        // scope to the node's OWN type field: it sits between the node's dimensieSelectie
        // (the first one after kind — argument refs and the Kenmerk filter carry none) and
        // its bron, so the base argument's type object cannot satisfy the assertion.
        int from = c.indexOf("\"dimensieSelectie\"", at);
        int to = c.indexOf("\"bron\"", from);
        assertTrue(from > at && to > from, () -> "de node-veldvolgorde is onverwacht:\n" + c);
        String ownType = c.substring(from, to);
        assertTrue(ownType.contains("\"type\":{\"node\":\"Numeriek\"") && ownType.contains("\"decimalen\":2"),
                () -> "de pro-rata-node draagt het basistype (Numeriek, 2 decimalen) niet zelf:\n" + ownType);
        assertFalse(ownType.contains("\"node\":\"Onbekend\""),
                () -> "de pro-rata-node mag niet de onbekend-sentinel dragen:\n" + ownType);
    }
}
