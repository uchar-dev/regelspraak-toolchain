package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The resolver builds a typed expression IR (the transpiler's input), not just a
 * pass/fail type check: a berekening becomes a {@link ResolvedRegel} whose value is
 * a structured {@link Expr} tree with references bound to the model and datatypes
 * carried on every node.
 */
class IrTest {

    static final String SRC = """
        Eenheidsysteem Geld
            de euro      €
        Objecttype de Verkoop
            het bedrag Numeriek (getal met 2 decimalen) met eenheid €;
            het totaal Numeriek (getal met 2 decimalen) met eenheid €;
        Parameter de toeslag : Numeriek (getal met 2 decimalen) met eenheid €
        Regel bereken totaal
            geldig altijd
                Het totaal van een Verkoop moet berekend worden als het bedrag van de Verkoop plus de toeslag.
        """;

    @Test void berekening_wordt_een_getypeerde_resolved_regel() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.resolvedRegels.size());

        ResolvedRegel rr = m.resolvedRegels.get(0);
        assertEquals("bereken totaal", rr.naam());
        assertEquals(ResolvedRegel.Soort.BEREKENING, rr.soort());
    }

    @Test void doel_is_een_gebonden_attribuutreferentie() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        ResolvedRegel rr = m.resolvedRegels.get(0);

        Expr.AttribuutRef doel = assertInstanceOf(Expr.AttribuutRef.class, rr.doel());
        assertEquals("totaal", doel.attribuut());
        assertNotNull(doel.objecttype(), "doel-onderwerp niet opgelost");
        assertSame(m.objecttypen.get("Verkoop"), doel.objecttype());
        assertInstanceOf(Datatype.Numeriek.class, doel.type());
        assertEquals("€", ((Datatype.Numeriek) doel.type()).eenheid().afkorting());
    }

    @Test void waarde_is_een_getypeerde_optelboom_met_gebonden_operanden() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        ResolvedRegel rr = m.resolvedRegels.get(0);

        Expr.Binair som = assertInstanceOf(Expr.Binair.class, rr.waarde());
        assertEquals(Expr.BinOp.PLUS, som.op());
        assertTrue(som.type().isNumeriek(), "som moet numeriek zijn");

        // left operand: het bedrag van de Verkoop -> attribuut bound to objecttype Verkoop
        Expr.AttribuutRef links = assertInstanceOf(Expr.AttribuutRef.class, som.links());
        assertEquals("bedrag", links.attribuut());
        assertSame(m.objecttypen.get("Verkoop"), links.objecttype());

        // right operand: de toeslag -> bound to the parameter
        Expr.ParameterRef rechts = assertInstanceOf(Expr.ParameterRef.class, som.rechts());
        assertSame(m.parameters.get("toeslag"), rechts.parameter());
    }

    @Test void aggregatie_structuur_wordt_vastgelegd() {
        // The som-aggregation's structure is captured even where its element type is
        // not yet resolved (the argument's subject is a role, not an object type).
        String src = """
            Objecttype de Reis
                de prijs Numeriek (getal met 2 decimalen);
            Regel tel
                geldig altijd
                    De prijs van een Reis moet berekend worden als de som van de prijs van een Reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        Expr waarde = m.resolvedRegels.get(0).waarde();
        Expr.Functie som = assertInstanceOf(Expr.Functie.class, waarde);
        assertEquals(Expr.FunctieKind.SOM, som.kind());
        assertEquals(1, som.argumenten().size());
    }

    @Test void enkelvoudige_voorwaarde_wordt_een_vergelijking() {
        String src = """
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
            Regel duur
                geldig altijd
                    De prijs van een Reis moet gesteld worden op 100
                    indien de prijs van een Reis groter is dan de grens.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        Conditie c = m.resolvedRegels.get(0).voorwaarde();
        Conditie.Vergelijking v = assertInstanceOf(Conditie.Vergelijking.class, c);
        assertEquals(Conditie.Operator.GROTER, v.op());
        assertInstanceOf(Expr.AttribuutRef.class, v.links());
        assertInstanceOf(Expr.ParameterRef.class, v.rechts());
    }

    @Test void samengestelde_voorwaarde_wordt_een_boom_van_onderdelen() {
        String src = """
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
                is duurzaam kenmerk (bijvoeglijk);
            Regel meervoud
                geldig altijd
                    De prijs van een Reis moet gesteld worden op 1
                    indien de Reis aan alle volgende voorwaarden voldoet:
                        - de prijs van een Reis groter is dan 0
                        - de prijs van een Reis kleiner is dan 100.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        Conditie c = m.resolvedRegels.get(0).voorwaarde();
        Conditie.Samengesteld s = assertInstanceOf(Conditie.Samengesteld.class, c);
        assertEquals(Conditie.Kwantor.ALLE, s.kwantor());
        assertEquals(2, s.onderdelen().size());
        assertInstanceOf(Conditie.Vergelijking.class, s.onderdelen().get(0));
        assertEquals(Conditie.Operator.KLEINER,
                ((Conditie.Vergelijking) s.onderdelen().get(1)).op());
    }

    @Test void onvoorwaardelijke_regel_heeft_geen_voorwaarde() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        assertNull(m.resolvedRegels.get(0).voorwaarde());
    }

    @Test void referenties_lossen_op_via_feittype_rollen() {
        // Subjects are roles, not object-type names: "een reis" → Vlucht (role reis),
        // "een passagier"/"alle passagiers" → Persoon (role passagier, incl. plural).
        String src = """
            Objecttype de Vlucht
                de prijs Numeriek (geheel getal);
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    De prijs van een reis moet gesteld worden op de leeftijd van alle passagiers.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "valse fout via rolnavigatie: " + m.errors());

        ResolvedRegel rr = m.resolvedRegels.get(0);
        Expr.AttribuutRef doel = assertInstanceOf(Expr.AttribuutRef.class, rr.doel());
        assertNotNull(doel.objecttype(), "rol 'reis' niet opgelost");
        assertEquals("Vlucht", doel.objecttype().naam);          // via role reis

        Expr.AttribuutRef waarde = assertInstanceOf(Expr.AttribuutRef.class, rr.waarde());
        assertNotNull(waarde.objecttype(), "meervoudige rol 'passagiers' niet opgelost");
        assertEquals("Persoon", waarde.objecttype().naam);       // via plural role passagiers
        assertTrue(waarde.type().isNumeriek());
    }

    @Test void onopgeloste_referentie_blijft_onbekend_geen_valse_fout() {
        // A deep van-chain the resolver cannot bind must not produce a type error.
        String src = """
            Objecttype de Reis
                de prijs Numeriek (getal met 2 decimalen);
            Regel x
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de korting van de aanbieding van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "valse fout op onopgeloste referentie: " + m.errors());
        // value is an attribuut-ref whose datatype stayed Onbekend (subject not resolved)
        Expr waarde = m.resolvedRegels.get(0).waarde();
        assertTrue(waarde.type().isOnbekend());
    }
}
