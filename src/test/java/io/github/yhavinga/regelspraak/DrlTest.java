package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Drools back-end walks the resolved IR into DRL: a target object-type pattern
 * for the "when", a modify-block setter for the "then", parameters as globals, and
 * an explicit marker wherever the IR was left unresolved.
 */
class DrlTest {

    static final String SRC = """
        Parameter de grens : Numeriek (geheel getal)
        Objecttype de Reis
            de prijs Numeriek (geheel getal);
            de toeslag Numeriek (geheel getal);
        Regel verhoog prijs
            geldig altijd
                De prijs van een Reis moet gesteld worden op de toeslag van een Reis plus de grens
                indien de prijs van een Reis kleiner is dan de grens.
        """;

    @Test void rule_skeleton_en_globals() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        assertTrue(drl.contains("package io.github.yhavinga.regelspraak.generated;"), drl);
        assertTrue(drl.contains("global java.math.BigDecimal grens;"), drl);
        assertTrue(drl.contains("rule \"verhoog prijs\""), drl);
        assertTrue(drl.contains("$subject : Reis("), () -> "subject pattern ontbreekt:\n" + drl);
    }

    @Test void conditie_wordt_when_constraint() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        // "de prijs ... kleiner is dan de grens" -> prijs < grens
        assertTrue(drl.contains("prijs < grens"), () -> "constraint ontbreekt:\n" + drl);
    }

    @Test void toekenning_wordt_modify_setter() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        // "de prijs ... := de toeslag plus de grens" -> modify { setPrijs( ($subject.getToeslag() + grens) ) }
        assertTrue(drl.contains("modify($subject)"), () -> "modify-blok ontbreekt:\n" + drl);
        assertTrue(drl.contains("setPrijs("), () -> "setter ontbreekt:\n" + drl);
        assertTrue(drl.contains("$subject.getToeslag() + grens"), () -> "waarde-expressie ontbreekt:\n" + drl);
    }

    @Test void aggregaties_worden_accumulate_patronen() {
        String src = """
            Objecttype de Vlucht
                het totaalbedrag Numeriek (geheel getal);
                het aantal reizigers Numeriek (geheel getal);
            Objecttype de Persoon
                het bedrag Numeriek (geheel getal);
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel som
                geldig altijd
                    Het totaalbedrag van een reis moet berekend worden als de som van het bedrag van alle passagiers van de reis.
            Regel telling
                geldig altijd
                    Het aantal reizigers van een reis moet berekend worden als het aantal passagiers van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // sum over the passenger (element type via role), scoped to the subject by the
        // feittype join, result fed to the setter
        assertTrue(drl.contains("$e1 : Persoon( reis == $subject )"), () -> "accumulate-join ontbreekt:\n" + drl);
        assertTrue(drl.contains("sum( $e1.getBedrag() )"), () -> "sum-functie ontbreekt:\n" + drl);
        assertTrue(drl.contains("setTotaalbedrag(") && drl.contains("$acc1"), () -> "som-resultaat niet gebruikt:\n" + drl);
        // count
        assertTrue(drl.contains("count( $e1 )"), () -> "count-functie ontbreekt:\n" + drl);
    }

    @Test void navigatie_wordt_join_pad_in_waarde_en_voorwaarde() {
        // The subject navigates to a related object via the feittype role "reis":
        // value -> $subject.getReis().getVluchtdatum(); condition -> reis.vluchtdatum.
        String src = """
            Objecttype de Vlucht
                de vluchtdatum Datum in dagen;
            Objecttype de Persoon
                de reisdatum Datum in dagen;
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel kopieer datum
                geldig altijd
                    De reisdatum van een passagier moet gesteld worden op de vluchtdatum van de reis
                    indien de vluchtdatum van de reis later is dan de reisdatum van een passagier.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : Persoon("), () -> "subject:\n" + drl);
        // value navigation through the role reference (getters)
        assertTrue(drl.contains("$subject.getReis().getVluchtdatum()"), () -> "waarde-navigatie:\n" + drl);
        // condition navigation as a property path on the pattern
        assertTrue(drl.contains("reis.vluchtdatum > reisdatum"), () -> "voorwaarde-navigatie:\n" + drl);
    }

    @Test void onmapbare_aggregatie_wordt_gemarkeerd_niet_verzonnen() {
        // wortel has no accumulate equivalent yet; it must surface as an explicit
        // marker, never as silently-wrong DRL.
        String src = """
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
                de wortelprijs Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De wortelprijs van een Reis moet berekend worden als de wortel van de prijs van een Reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO aggregatie WORTEL"), () -> "wortel niet gemarkeerd:\n" + drl);
    }

    @Test void begrenzing_en_afronding_worden_geemit() {
        // Kept in separate rules: a begrenzing whose bound value is itself followed by
        // an afronding parses the afronding INTO the bound value (a grammar-precedence
        // limitation noted in docs/grammar-divergences.md), so they are tested apart.
        String src = """
            Parameter de bodem : Numeriek (geheel getal)
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
                de basis Numeriek (geheel getal);
            Regel afrond
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de basis van een Reis naar beneden afgerond op 2 decimalen.
            Regel begrens
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de basis van een Reis, met een minimum van de bodem.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains(".setScale(2, java.math.RoundingMode.FLOOR)"), () -> "afronding:\n" + drl);
        assertTrue(drl.contains("< bodem ? bodem :"), () -> "begrenzing-minimum:\n" + drl);
    }

    @Test void kenmerktoekenning_wordt_boolean_setter_onder_voorwaarde() {
        String src = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Regel minderjarig
                geldig altijd
                    Een Persoon is minderjarig
                    indien de leeftijd van een Persoon kleiner is dan 18.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : Persoon( leeftijd < 18 )"), () -> "voorwaarde:\n" + drl);
        assertTrue(drl.contains("setMinderjarig( true )"), () -> "kenmerk-setter:\n" + drl);
    }

    @Test void namen_worden_geldige_java_identifiers() {
        String src = """
            Objecttype de Natuurlijk persoon
                de te betalen belasting Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De te betalen belasting van een Natuurlijk persoon moet gesteld worden op 0.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : NatuurlijkPersoon("), () -> "klassenaam:\n" + drl);
        assertTrue(drl.contains("setTeBetalenBelasting("), () -> "setternaam:\n" + drl);
    }
}
