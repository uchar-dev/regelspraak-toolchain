package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Phase C: rules are type-checked against the typeringen matrix end-to-end. */
class RuleCheckTest {

    static final String MODEL = """
        Objecttype de Ding
            de teller Numeriek (geheel getal);
            de naam Tekst;
        """;

    @Test void verkeerde_begrenzing_grens_wordt_gemeld() {
        // §6.1.4: a begrenzing bound must be the same numeric family as the bounded value; a
        // Tekst bound is a type error (it would otherwise lower to an uncompilable comparison).
        String src = MODEL + """
            Regel grensfout
                geldig altijd
                    De teller van een Ding moet gesteld worden op de teller van een Ding, met een maximum van de naam van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.errors().isEmpty(), "begrenzing met een Tekst-grens had gemeld moeten worden");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("begrenzing")),
                () -> "verwachtte een begrenzing-fout, kreeg: " + m.errors());
    }

    @Test void begrenzing_met_geldige_grens_geen_fout() {
        // a numeric bound is fine — no false positive.
        String src = MODEL + """
            Regel grensok
                geldig altijd
                    De teller van een Ding moet gesteld worden op de teller van een Ding, met een maximum van 100.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void percentage_waarde_met_numerieke_literal_grens_geen_fout() {
        // §6.1.4 admits a Percentage value bounded by a plain numeric expression — the bound need
        // not be the SAME family as the bounded value, only numeric (getalachtig). No false positive.
        String src = """
            Objecttype de Ding
                de korting Percentage (geheel getal);
                de basis Percentage (geheel getal);
            Regel grenspct
                geldig altijd
                    De korting van een Ding moet gesteld worden op de basis van een Ding, met een maximum van 100.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een numerieke literal-grens op een Percentage-waarde mag geen fout zijn: " + m.errors());
    }

    @Test void verkeerde_toekenning_wordt_gemeld() {
        // assigning a Tekst attribute to a Numeriek attribute violates §6.1
        String src = MODEL + """
            Regel fout
                geldig altijd
                    De teller van een Ding moet gesteld worden op de naam van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.errors().isEmpty(), "type-mismatch had gemeld moeten worden");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("toekennen")),
                () -> "verwachtte een toekenning-fout, kreeg: " + m.errors());
    }

    @Test void correcte_toekenning_geen_fout() {
        String src = MODEL + """
            Regel goed
                geldig altijd
                    De teller van een Ding moet gesteld worden op de teller van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void voorwaarde_type_mismatch_wordt_gemeld() {
        // comparing a Tekst attribute with 'groter dan' (numeric ordering) violates §5.5
        String src = MODEL + """
            Regel cond
                geldig altijd
                    De teller van een Ding moet gesteld worden op 0
                    indien de naam van een Ding groter is dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("voorwaarde")),
                () -> "verwachtte een voorwaarde-fout, kreeg: " + m.errors());
    }

    @Test void geldige_voorwaarde_geen_fout() {
        String src = MODEL + """
            Regel cond ok
                geldig altijd
                    De teller van een Ding moet gesteld worden op 0
                    indien de teller van een Ding groter is dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void ongedefinieerd_domein_wordt_gemeld() {
        String src = """
            Objecttype de Ding
                de prijs Bedrag;
            """; // Bedrag is never declared
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("ongedefinieerd domein")),
                () -> "verwachtte een domein-fout, kreeg: " + m.errors());
    }

    @Test void consistentiecriterium_wordt_getypeerd_als_een_voorwaarde() {
        // §8.3.1: a single criterion is the same predicate as the matching voorwaarde, so an
        // ill-typed comparison (Tekst ordered against Numeriek) must error identically.
        String src = MODEL + """
            Regel typecheck
                geldig altijd
                    De naam van een Ding moet groter zijn dan de teller van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("ordeningsvergelijking")),
                () -> "verwachtte een type-fout op het consistentiecriterium, kreeg: " + m.errors());
    }

    @Test void niet_liftbare_consistentieregel_waarschuwt_ipv_stil_te_vallen() {
        // a membership criterion ("moet een <kenmerk> zijn") cannot yet be transpiled to a
        // faithful violation rule; it must surface as a WARNING, not vanish into an inert rule.
        String src = """
            Objecttype de Vlucht
                is rondvlucht kenmerk;
            Regel controleer rondvlucht
                geldig altijd
                    Een Vlucht moet een rondvlucht zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geen fout verwacht, kreeg: " + m.errors());
        assertTrue(m.consistenties.isEmpty(), "een niet-liftbare vorm mag geen IR opleveren");
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("consistentieregel")),
                () -> "verwachtte een WARNING, kreeg: " + m.diagnostics);
    }

    @Test void niet_numerieke_leegwaarde_van_een_sommatie_wordt_gemeld() {
        // §4.10/§5.8.2: the "of … als die er niet zijn" fallback substitutes for the sum's
        // numeriek result, so a Tekst fallback must error (else it becomes a type-mixed DRL
        // ternary that throws at fire time).
        String src = """
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel met
                geldig altijd
                    De somMet van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis of "geen" als die er niet zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("leegwaarde-alternatief")),
                () -> "verwachtte een leegwaarde-type-fout, kreeg: " + m.errors());
    }

    @Test void numerieke_leegwaarde_van_een_sommatie_geen_fout() {
        String src = """
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel met
                geldig altijd
                    De somMet van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis of 0 als die er niet zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void ongeldige_drempel_in_samengestelde_voorwaarde_wordt_gemeld() {
        // §13.4.14.4: a counting quantifier's threshold must be a positive integer; a 0 (or a
        // decimal/fraction/overflow that coerces to 0) would silently collapse the compound
        // condition to always-true, so it is rejected.
        String src = """
            Objecttype de Persoon
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                is gekozen kenmerk (bijvoeglijk);
            Regel kies
                geldig altijd
                    Een Persoon is gekozen
                    indien hij aan ten minste 0 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("telwoord")),
                () -> "verwachtte een drempel-fout, kreeg: " + m.errors());
    }

    static final String HEFFING = """
        Objecttype de Heffing
            de prijs Numeriek (geheel getal);
        """;

    @Test void overlappende_regelversie_perioden_worden_gemeld() {
        // §4.2 (spec l.1387): version validity periods must not overlap — else more than one
        // version applies at a Rekendatum (and writing the same attribute they loop in the engine).
        String src = HEFFING + """
            Regel verhoog
                geldig vanaf 2020 t/m 2022
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2021 t/m 2023
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("overlappen")),
                () -> "verwachtte een overlap-fout, kreeg: " + m.errors());
    }

    @Test void niet_overlappende_regelversies_geen_fout() {
        String src = HEFFING + """
            Regel verhoog
                geldig vanaf 2024 t/m 2024
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2025
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void ongeldige_versiegeldigheidsgrens_wordt_gemeld_niet_stil_altijd() {
        // a decimal "year" can't resolve to a date; it must error, not silently collapse the
        // bounded version to always-active (§13.2.24 year is four digits).
        String src = HEFFING + """
            Regel verhoog
                geldig vanaf 2024,5
                    De prijs van een Heffing moet gesteld worden op 100.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("versiegeldigheidsgrens")),
                () -> "verwachtte een grens-fout, kreeg: " + m.errors());
    }

    @Test void kalender_ongeldige_datum_in_geldigheid_crasht_niet_maar_meldt() {
        // an out-of-range calendar date must be a diagnostic, not an uncaught DateTimeException.
        String src = HEFFING + """
            Regel verhoog
                geldig t/m 31-06-2022
                    De prijs van een Heffing moet gesteld worden op 100.
            """;
        RegelSpraakModel m = Resolver.resolve(src); // must not throw
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("versiegeldigheidsgrens")),
                () -> "verwachtte een grens-fout, kreeg: " + m.errors());
    }

    @Test void onoplosbare_referentie_geeft_geen_valse_fout() {
        // a deep van-chain the checker cannot fully resolve must NOT be reported as an error
        String src = MODEL + """
            Regel onbekend
                geldig altijd
                    De teller van een Ding moet gesteld worden op de som van de tellers van alle Dingen van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geen valse positief verwacht, kreeg: " + m.errors());
    }

    @Test void maximale_waarde_met_niet_numerieke_operand_wordt_gemeld() {
        // §4.13 Tabel 23: every "maximale waarde van"-operand must be numeriek; a Tekst operand
        // (here "de naam") is a type error.
        String src = MODEL + """
            Regel maxfout
                geldig altijd
                    De teller van een Ding moet gesteld worden op de maximale waarde van de naam van een Ding en de teller van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("maximale waarde van vereist numerieke")),
                () -> "verwachtte een operand-typefout voor maximale waarde van, kreeg: " + m.errors());
    }

    @Test void minimale_waarde_met_onverenigbare_eenheden_wordt_gemeld() {
        // §4.12 Tabel 22: operanden met eenheden die niet in elkaar om te rekenen zijn (km vs €).
        String src = """
            Eenheidsysteem Afstand
                de kilometer km
            Eenheidsysteem Geld
                de euro EUR
            Objecttype de Ding
                de afstand Numeriek (geheel getal) met eenheid km;
                de prijs Numeriek (geheel getal) met eenheid EUR;
                de uitkomst Numeriek (geheel getal) met eenheid km;
            Regel eenheidfout
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op de minimale waarde van de afstand van een Ding en de prijs van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("niet in elkaar om te rekenen")),
                () -> "verwachtte een eenheidfout voor minimale waarde van, kreeg: " + m.errors());
    }

    @Test void maximale_waarde_van_percentages_wordt_gemeld() {
        // Tabel 22/23 list the min/max operand strictly as Numeriek (no Percentage row, unlike the
        // §5.1-5.4 ordinal-comparison tables which DO carry one) — so a Percentage min/max operand
        // is a type error, not silently accepted.
        String src = """
            Objecttype de Ding
                de korting Percentage (geheel getal);
                de toeslag Percentage (geheel getal);
            Regel pctmax
                geldig altijd
                    De korting van een Ding moet gesteld worden op de maximale waarde van de korting van een Ding en de toeslag van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("maximale waarde van vereist numerieke")),
                () -> "een Percentage min/max-operand had gemeld moeten worden, kreeg: " + m.errors());
    }

    @Test void schone_maximale_waarde_geen_fout() {
        // two numeriek operands, no units -> no false positive.
        String src = MODEL + """
            Regel maxok
                geldig altijd
                    De teller van een Ding moet gesteld worden op de maximale waarde van de teller van een Ding en de teller van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void eerste_van_met_niet_datum_operand_wordt_gemeld() {
        // §4.14 Tabel 24: "eerste van" is a datumaggregatie — every operand must be a datum;
        // a Numeriek operand ("de teller") is a type error.
        String src = """
            Objecttype de Ding
                de start Datum in dagen;
                de teller Numeriek (geheel getal);
                de uitkomst Datum in dagen;
            Regel datumfout
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op de eerste van de start van een Ding en de teller van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("eerste van vereist datumexpressies")),
                () -> "verwachtte een datum-operandfout voor eerste van, kreeg: " + m.errors());
    }

    @Test void schone_eerste_van_geen_fout() {
        // two same-precision datums -> no false positive.
        String src = """
            Objecttype de Ding
                de start Datum in dagen;
                de einde Datum in dagen;
                de uitkomst Datum in dagen;
            Regel datumok
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op de eerste van de start van een Ding en de einde van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    static final String RATE_SYS = """
        Eenheidsysteem Geld
            de euro €
        Eenheidsysteem Afstand
            de kilometer km
        Eenheidsysteem Tijd
            de maand    mnd
            het jaar    jr    =    12 mnd
        """;

    @Test void samengestelde_eenheden_met_convertibele_delen_geen_fout() {
        // §13.3.3.2: €/jr and €/mnd are convertible (same numerator, convertible denominator).
        String src = RATE_SYS + """
            Objecttype de Abonnement
                de jaarprijs Numeriek (geheel getal) met eenheid €/jr;
                de maandprijs Numeriek (geheel getal) met eenheid €/mnd;
            Regel r
                geldig altijd
                    De jaarprijs van een Abonnement moet gesteld worden op de maandprijs van een Abonnement.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "€/jr en €/mnd zijn convertibel: " + m.errors());
    }

    @Test void samengestelde_eenheden_met_onverenigbare_teller_wordt_gemeld() {
        // €/jr and km/jr share a denominator but the numerators (€ vs km) are not convertible.
        String src = RATE_SYS + """
            Objecttype de Abonnement
                de jaarprijs Numeriek (geheel getal) met eenheid €/jr;
                de snelheid Numeriek (geheel getal) met eenheid km/jr;
            Regel r
                geldig altijd
                    De jaarprijs van een Abonnement moet gesteld worden op de snelheid van een Abonnement.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("'€/jr' en 'km/jr'")),
                () -> "verwachtte een eenheidfout met de samengestelde weergave, kreeg: " + m.errors());
    }
}
