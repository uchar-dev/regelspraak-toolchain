package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Conditie.Operator;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Granulariteit;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The shared tijdseenheid → ChronoUnit mapping and §8.2 toets codes (single shared implementation). */
class TijdrekeningTest {

    private static Datatype met(String afkorting) {
        return new Datatype.Numeriek(true, 0, null, new Eenheid(afkorting, "Tijd"));
    }

    @Test void chronoUnit_normaliseert_de_tijdseenheden() {
        assertEquals("YEARS",  Tijdrekening.chronoUnit(met("jr")));
        assertEquals("YEARS",  Tijdrekening.chronoUnit(met("jaren")));   // plural -> singular reducer
        assertEquals("MONTHS", Tijdrekening.chronoUnit(met("mnd")));
        assertEquals("WEEKS",  Tijdrekening.chronoUnit(met("wk")));
        assertEquals("DAYS",   Tijdrekening.chronoUnit(met("dag")));
        assertEquals("DAYS",   Tijdrekening.chronoUnit(met("dg")));   // §3.7 the declared dag afkorting
        assertEquals("HOURS",  Tijdrekening.chronoUnit(met("u")));
        assertEquals("MILLIS", Tijdrekening.chronoUnit(met("ms")));
        // §3.7 kwartaal = 3 maanden; java.time has no QUARTERS -> the runtime's pseudo-unit
        assertEquals("KWARTAAL", Tijdrekening.chronoUnit(met("kwartaal")));
        assertEquals("KWARTAAL", Tijdrekening.chronoUnit(met("kw"))); // §3.7 the declared kwartaal afkorting
        assertNull(Tijdrekening.chronoUnit(met("werkdagen")), "een dagsoort/onbekende eenheid -> null (marker)");
        assertNull(Tijdrekening.chronoUnit(new Datatype.Numeriek(true, 0, null, null)), "geen eenheid -> null");
    }

    @Test void isKalenderSpelling_kent_precies_de_dertig_spellingen_van_de_tabel() {
        // The day-kind registration guard (DefinitionResolver) and WF-017 both compare against THIS, so the
        // set has to be the normative table of RUNTIME-SEMANTICS ch. 3.8 exactly: nine units, thirty
        // spellings — the singular, the abbreviation and the plural for each, plus THREE extras that make
        // the count come out at thirty rather than twenty-seven: the second carries a second abbreviation
        // (`s` beside `sec`), and the second and the millisecond each carry a second plural in -s.
        // Pinned as a whole rather than sampled, because both a missing and a surplus spelling is a
        // producer that disagrees with the wire rule.
        List<String> tabel = List.of(
                "jaar", "jr", "jaren", "maand", "mnd", "maanden", "kwartaal", "kw", "kwartalen",
                "week", "wk", "weken", "dag", "dg", "dagen", "uur", "u", "uren",
                "minuut", "min", "minuten", "seconde", "sec", "s", "seconden", "secondes",
                "milliseconde", "ms", "milliseconden", "millisecondes");
        assertEquals(30, tabel.size());
        for (String s : tabel) assertTrue(Tijdrekening.isKalenderSpelling(s), s + " staat in de tabel");
        // and nothing beside it: a day-kind spelling, a near miss, the empty spelling, none
        for (String s : List.of("werkdag", "werkdagen", "schouwdagen", "wkn", "seconds", "kwartaaltje", ""))
            assertFalse(Tijdrekening.isKalenderSpelling(s), s + " staat er niet in");
        assertFalse(Tijdrekening.isKalenderSpelling(null));
    }

    @Test void de_tabel_wordt_hoofdletterongevoelig_en_getrimd_gelezen() {
        // ch. 3.8 says "recognised by SPELLING, case-insensitive", and the interpreter reads it that way
        // (lower-case after a trim). Until 2026-09-20 only the nine -en plurals survived a capital here:
        // they are the ones the plural reducer REPLACES, and the replacement happened to be lowercase,
        // while every other spelling fell through the reducer's default arm in its original case. Measured
        // then: WEKEN -> WEEKS but WK, Wk, ' wk ' and Sec -> null, and a verifier-clean document with the
        // unit spelled WK split the executors — ref reckoned in weeks, both Drools branches refused the
        // rule as an unknown time unit.
        for (String s : List.of("WK", "Wk", " wk ", "\tSec\n", "S", "JR", "Secondes", "MILLISECONDES"))
            assertTrue(Tijdrekening.isKalenderSpelling(s), "'" + s + "' is dezelfde spelling als zijn kleine letter");
        assertEquals("WEEKS", Tijdrekening.chronoUnit(met(" WK ")));
        assertEquals("SECONDS", Tijdrekening.chronoUnit(met("Sec")));
        assertEquals("KWARTAAL", Tijdrekening.chronoUnit(met("KWARTALEN")));
    }

    @Test void een_eenheid_zonder_afkorting_is_geen_tijdseenheid_en_geen_dagsoort() {
        // The wire admits an Eenheid whose afkorting is null (the interpreter reads it as "no unit"), and
        // the plural reducer hands a null straight back through its default arm — so both readers below
        // would throw on a document the IR admits. They answer null instead, which is the same verdict
        // ref gives.
        Eenheid zonder = new Eenheid(null, "Tijd");
        assertNull(Tijdrekening.chronoUnit(zonder));
        assertNull(Tijdrekening.chronoUnit(new Datatype.Numeriek(true, 0, null, zonder)));
        RegelSpraakModel model = new RegelSpraakModel();
        model.dagsoortPerEenheid.put("werkdagen", "werkdag");
        assertNull(Tijdrekening.dagsoortVoorEenheid(model, zonder));
        assertNull(Tijdrekening.dagsoortVoorEenheid(model, null));
        // and the reader still finds a real day kind beside it
        assertEquals("werkdag", Tijdrekening.dagsoortVoorEenheid(model, new Eenheid("werkdagen", null)));
    }

    @Test void toets_codeert_de_zes_vergelijkingen_en_weigert_datum_ordening() {
        assertEquals(0, Tijdrekening.toets(Operator.KLEINER));
        assertEquals(1, Tijdrekening.toets(Operator.KLEINER_OF_GELIJK));
        assertEquals(2, Tijdrekening.toets(Operator.GROTER));
        assertEquals(3, Tijdrekening.toets(Operator.GROTER_OF_GELIJK));
        assertEquals(4, Tijdrekening.toets(Operator.GELIJK));
        assertEquals(5, Tijdrekening.toets(Operator.ONGELIJK));
        assertNull(Tijdrekening.toets(Operator.EERDER), "een datum-ordening valt buiten rsGeheelPeriode");
        assertNull(Tijdrekening.toets(Operator.LATER_OF_GELIJK));
    }

    @Test void periodeGrensLowerbaar_leest_de_facetten_van_een_geerfd_attribuut_door_de_keten() {
        // Both Drools branches share this helper, so a wrong answer here is a wrong answer in two
        // executors at once. It must judge the FACETS of the bound attribute — a timeline or a
        // dimensioned attribute is not a lowerable §8.4.3 bound — and those facets sit at the
        // DECLARANT. Reading them on the subject's own maps calls an inherited timeline scalar and
        // admits a bound the runtime cannot compute.
        Objecttype ouder = new Objecttype("Jutter");
        ouder.attributen.put("peildatum", new Datatype.DatumInDagen());
        ouder.attributen.put("knipdatum", new Datatype.DatumInDagen());
        ouder.attribuutGranulariteit.put("knipdatum", Granulariteit.MAAND);
        ouder.attributen.put("seizoensdatum", new Datatype.DatumInDagen());
        ouder.attribuutDimensies.put("seizoensdatum", List.of("seizoen"));
        Objecttype sub = new Objecttype("Klipjutter");
        sub.supertype = ouder;

        assertTrue(Tijdrekening.periodeGrensLowerbaar(grens("peildatum", sub), sub),
                "een geërfde scalaire datum is een gewone lowerbare grens");
        assertFalse(Tijdrekening.periodeGrensLowerbaar(grens("knipdatum", sub), sub),
                "een geërfd TIJDLIJN-attribuut is geen scalaire grens — het antwoord mag niet van de "
                        + "declaratieplaats afhangen");
        assertFalse(Tijdrekening.periodeGrensLowerbaar(grens("seizoensdatum", sub), sub),
                "een geërfd GEDIMENSIONEERD attribuut evenmin");
        // …en het vlakke equivalent geeft precies dezelfde drie antwoorden
        assertTrue(Tijdrekening.periodeGrensLowerbaar(grens("peildatum", ouder), ouder));
        assertFalse(Tijdrekening.periodeGrensLowerbaar(grens("knipdatum", ouder), ouder));
        assertFalse(Tijdrekening.periodeGrensLowerbaar(grens("seizoensdatum", ouder), ouder));
    }

    private static Expr grens(String attribuut, Objecttype subject) {
        return new Expr.AttribuutRef(attribuut, subject, "zijn", null, new Datatype.DatumInDagen(), null);
    }
}
