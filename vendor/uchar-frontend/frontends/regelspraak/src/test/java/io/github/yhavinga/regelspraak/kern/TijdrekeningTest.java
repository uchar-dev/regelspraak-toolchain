package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Conditie.Operator;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import org.junit.jupiter.api.Test;

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
}
