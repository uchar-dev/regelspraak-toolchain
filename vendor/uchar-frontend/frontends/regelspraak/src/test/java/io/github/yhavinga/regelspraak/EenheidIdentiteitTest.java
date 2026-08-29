package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rood-eerst end-to-end proof of the eenheid-identiteit typerfix (besluit 16Y + 17b): the
 * dimensionless "1" is the group identity and absorbs under multiplication (1 × E = E), while a
 * unit the {@link io.github.yhavinga.regelspraak.model.Eenheid} model cannot spell is a LOUD
 * resolver error — never the old Onbekend degradation that silently rode a Binair onto the wire.
 *
 * <p>Each test was RED on the pre-fix code: (a) the product's type emitted as
 * {@code {"node":"Onbekend","reden":"samengestelde eenheid"}}; (b) and (c) resolved clean (no
 * diagnostic), degrading to Onbekend instead of faulting.
 */
class EenheidIdentiteitTest {

    /** (a) A unitless ATTRIBUTE times a unit-bearing attribute now carries the real unit on the
     *  wire (§4.4 Tabel 11 rij 4 + Tabel 13), not the old Onbekend("samengestelde eenheid"). */
    @Test void unitloos_attribuut_maal_eenheid_emitteert_het_echte_type() {
        String src = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €
            Objecttype de Verkoop
                het stuktal Numeriek (geheel getal);
                de stukprijs Numeriek (getal met 2 decimalen) met eenheid €;
                de opbrengst Numeriek (getal met 2 decimalen) met eenheid €;
            Regel opbrengst
                geldig altijd
                    De opbrengst van een Verkoop moet berekend worden als het stuktal van de Verkoop maal de stukprijs van de Verkoop.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = IrEmitter.emit(m).replaceAll("\\s+", "");
        // the whole rule has no count function, so any Onbekend on the wire would be the old degradation.
        assertFalse(c.contains("\"node\":\"Onbekend\""),
                () -> "het product mag niet meer degraderen naar Onbekend(\"samengestelde eenheid\"):\n" + c);
        // the MAAL product carries its own type Numeriek with the EUR unit (2 = 0 + 2 decimalen, Tabel 13).
        // NOTE emitCompact strips ALL whitespace, also inside the bron string value.
        assertTrue(c.contains("\"op\":\"MAAL\","), () -> "het MAAL-product ontbreekt:\n" + c);
        assertTrue(c.contains(
                "\"type\":{\"node\":\"Numeriek\",\"geheel\":false,\"decimalen\":2,\"teken\":\"ONBEPAALD\","
                + "\"eenheid\":{\"node\":\"Eenheid\",\"afkorting\":\"EUR\",\"stelsel\":\"Munt\",\"canoniek\":\"EUR\","
                + "\"factorNaarCanoniek\":1,\"noemer\":null}},"
                + "\"bron\":\"hetstuktalvandeVerkoopmaaldestukprijsvandeVerkoop\""),
                () -> "het product moet het echte EUR-type (2 decimalen) dragen:\n" + c);
    }

    /** (b) A unitless numerator divided by a unit-bearing denominator is a reciprocal 1/E the Eenheid
     *  model cannot spell — besluit 17b diverges from the (unsound) Tabel 14 rij 4 with a LOUD error. */
    @Test void unitloos_gedeeld_door_eenheid_weigert_luid() {
        String src = """
            Eenheidsysteem Tijdsduur
                de maand mnd
            Objecttype de Verkoop
                het stuktal Numeriek (geheel getal);
                de looptijd Numeriek (geheel getal) met eenheid mnd;
                het tempo Numeriek (getal met 2 decimalen);
            Regel tempo
                geldig altijd
                    Het tempo van een Verkoop moet berekend worden als het stuktal van de Verkoop gedeeld door de looptijd van de Verkoop.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("omgekeerde eenheid")),
                () -> "verwachtte een luide 1/eenheid-fout, kreeg: " + m.errors());
    }

    /** (c) A rate factor (€/jr maal m = €·m/jr) is a product unit with a denominator the model cannot
     *  spell — besluit 17c makes it a LOUD error instead of the old Onbekend. */
    @Test void onspelbare_ratefactor_weigert_luid() {
        String src = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €
            Eenheidsysteem Tijdsduur
                het jaar jr
            Eenheidsysteem Lengte
                de meter m
            Objecttype de Verkoop
                het bedrag Numeriek (getal met 2 decimalen) met eenheid €;
                de looptijd Numeriek (geheel getal) met eenheid jr;
                de afstand Numeriek (geheel getal) met eenheid m;
                het resultaat Numeriek (getal met 2 decimalen) met eenheid €;
            Regel resultaat
                geldig altijd
                    Het resultaat van een Verkoop moet berekend worden als (het bedrag van de Verkoop gedeeld door de looptijd van de Verkoop) maal de afstand van de Verkoop.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("niet uitdrukken")),
                () -> "verwachtte een luide onspelbare-samengestelde-eenheid-fout, kreeg: " + m.errors());
    }
}
