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
        assertTrue(drl.contains("leeftijd < new java.math.BigDecimal(\"18\")"), () -> "voorwaarde:\n" + drl);
        assertTrue(drl.contains("setMinderjarig( true )"), () -> "kenmerk-setter:\n" + drl);
    }

    @Test void definiete_terugverwijzing_via_eigen_rolnaam_leest_eigen_attribuut() {
        // §5.5.3: a definite back-reference to the subject by a role it itself fills
        // ("de reis", where the subject IS the Vlucht playing role reis) names the SAME
        // instance — it must read the subject's own attribute, not degrade to a marker.
        String src = """
            Objecttype de Vlucht
                de vertrek Numeriek (geheel getal);
                de aankomst Numeriek (geheel getal);
            Objecttype de Passagier
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel kopieer
                geldig altijd
                    De aankomst van een Vlucht moet gesteld worden op de vertrek van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("setAankomst( $subject.getVertrek() )"), () -> "eigen-attribuut-lezing:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"), () -> "mag geen navigatie-marker zijn:\n" + drl);
    }

    @Test void exacte_rolnaam_wint_van_gesynthetiseerd_meervoud_van_andere_rol() {
        // Two feittypen over Reis<->Vlucht; one role is literally "heenvluchten", which
        // also happens to be the synthesized +en plural of the other feittype's
        // "heenvlucht". A reference naming "heenvluchten" exactly must bind THAT role, not
        // collapse to a false ambiguity (§3.1 footnote 2: a synthesized plural never
        // defeats a declared name).
        String src = """
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
            Objecttype de Vlucht
                de tarief Numeriek (geheel getal);
            Feittype boeking
                de reis Reis
                de heenvlucht Vlucht
            Eén reis heeft één heenvlucht
            Feittype extra
                de extrareis Reis
                de heenvluchten Vlucht
            Eén extrareis heeft één heenvluchten
            Regel prijs
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de tarief van de heenvluchten.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject.getHeenvluchten().getTarief()"), () -> "exacte rol moet binden:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"), () -> "geen valse ambiguïteit:\n" + drl);
    }

    @Test void consistentieregel_vuurt_op_de_schending_en_markeert_inconsistentie() {
        // §13.4.8 / §9.5: "X moet ongelijk zijn aan Y" asserts X != Y; the rule is
        // INCONSISTENT when that is violated (X == Y). So the generated rule fires on the
        // negated assertion and inserts an Inconsistentie naming the rule and the subject.
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel rondvlucht
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"rondvlucht\""), () -> "regel ontbreekt:\n" + drl);
        // ongelijk asserted -> fires on the gelijk violation
        assertTrue(drl.contains("$subject : Route( vertrek == bestemming )"), () -> "schending-constraint:\n" + drl);
        assertTrue(drl.contains("insert(new io.github.yhavinga.regelspraak.facts.Inconsistentie(\"rondvlucht\", $subject))"),
                () -> "inconsistentie-markering ontbreekt:\n" + drl);
    }

    @Test void consistentieregel_ordening_bewaakt_lege_operanden() {
        // §9.5 + leegwaarde rekentabels: "saldo moet kleiner zijn dan limiet" is onwaar —
        // inconsistent — when violated AND when an operand is empty. The algebraic
        // complement alone is also onwaar on null, so the violation guards the nulls.
        String src = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            Regel saldocheck
                geldig altijd
                    De saldo van een Konto moet kleiner zijn dan de limiet van een Konto.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("saldo == null || limiet == null || saldo >= limiet"),
                () -> "leeg-operand-bewaking ontbreekt:\n" + drl);
    }

    @Test void consistentieregel_naam_met_quote_levert_geldige_drl_header() {
        // a rule name is free text and may contain a double quote (§13.2.8); it must be
        // escaped so the DRL header and the Inconsistentie literal stay well-formed.
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel Controleer "rondvlucht" status
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"Controleer 'rondvlucht' status\""), () -> "header niet geëscaped:\n" + drl);
        assertFalse(drl.contains("\"rondvlucht\""), () -> "ongeëscapete quote lekt door:\n" + drl);
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
