package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Conditie.Operator;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
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
