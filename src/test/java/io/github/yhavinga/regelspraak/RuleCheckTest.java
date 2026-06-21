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

    static final String VERDEEL_MODEL = """
        Objecttype de Pot
            de totaal Numeriek (getal met 2 decimalen);
            de restant Numeriek (geheel getal);
        Objecttype de Deelnemer
            de aandeel Numeriek (geheel getal);
            de gewicht Numeriek (geheel getal);
        Feittype Pot over deelnemers
            de pot Pot
            de deelnemer Deelnemer
        Eén pot wordt verdeeld over meerdere deelnemers
        """;

    @Test void verdeling_over_precieze_deling_wordt_als_6_1_2_gemeld() {
        // §6.1.2, consistent with `regel`: a share whose decimal count (here the 2-decimal `totaal` over an
        // integer count) exceeds the integer target's must be explicitly rounded — the resolver flags it, just
        // as it flags the equivalent ordinary assignment.
        String src = VERDEEL_MODEL + """
            Regel deel
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("aandeel") && d.message().contains("6.1.2")),
                () -> "verwachtte een §6.1.2 verdeling-precisie-fout, kreeg: " + m.errors());
    }

    @Test void verdeling_met_afronding_onderdrukt_de_6_1_2_melding() {
        // the §9.7.4 "afgerond op N decimalen naar beneden" IS the explicit afronding §6.1.2 demands, so a
        // rounded share fits the bounded target — no error (exactly as an Expr.Afgerond suppresses it for regel).
        String src = VERDEEL_MODEL + """
            Regel deel afgerond
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - afgerond op 0 decimalen naar beneden
                    Als onverdeelde rest blijft de restant van een pot over.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een afgeronde verdeling mag geen §6.1.2-fout geven: " + m.errors());
    }

    @Test void verdeling_van_gehele_getallen_geeft_geen_6_1_2_fout() {
        // every TOKA verdeling is integer-typed -> the share is 0 decimals -> no false positive.
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel deel geheel
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de gewicht.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een geheel-getal verdeling mag geen §6.1.2-fout geven: " + m.errors());
    }

    @Test void verdeling_naar_rato_met_niet_numeriek_gewicht_geeft_geen_6_1_2_fout() {
        // a non-getalachtig weight makes the naar-rato verdeling an honest no-op in the transpiler, so the
        // §6.1.2 precision check must NOT fire on a share that is never computed (a false positive — regel
        // reports a TYPE error there, not a precision one).
        String src = """
            Objecttype de Pot
                de totaal Numeriek (getal met 2 decimalen);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de label Tekst;
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel deel tekst
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de label.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().noneMatch(d -> d.message().contains("6.1.2")),
                () -> "een niet-numeriek gewicht mag geen §6.1.2 verdeling-fout geven (de verdeling lowert niet): " + m.errors());
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

    @Test void elfproef_op_datum_in_voorwaarde_wordt_gemeld() {
        // p34: "voldoet aan de elfproef" is a VERB-LEADING (nonToplevel) operator, but under INDIEN it
        // sits in a toplevelElementaireVoorwaarde. The type-check used to read only c.toplevelOperator(),
        // so this predicate was silently UNCHECKED. §5.13 Tabel 55 admits only Numeriek (geheel getal) or
        // Tekst — a Datum operand must now be flagged.
        String src = """
            Objecttype de Ding
                de datum Datum in dagen;
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de datum van een Ding voldoet aan de elfproef.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("elfproef")),
                () -> "verwachtte een Tabel-55 elfproef-fout in de voorwaarde, kreeg: " + m.errors());
    }

    @Test void tijd_component_uit_een_datum_in_dagen_wordt_gemeld() {
        // §6.12 / §13.4.17.21-.24: "het uur uit X" needs a datum-TIJD (a plain Datum in dagen has no time).
        // The transpiler already markers it; the resolver must now also raise an ERROR. The DATE parts
        // (jaar/maand/dag) accept a Datum in dagen, so those must NOT be flagged.
        String src = """
            Objecttype de Ding
                de reisdatum Datum in dagen;
                de moment Datum en tijd in millisecondes;
                de uurnr Numeriek (geheel getal);
                de jaartal Numeriek (geheel getal);
            Regel fout
                geldig altijd
                    De uurnr van een Ding moet gesteld worden op het uur uit de reisdatum van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("tijd-component")),
                () -> "verwachtte een §13.4.17.21-.24 tijd-component-fout, kreeg: " + m.errors());

        // uur uit a Datum-en-tijd, and jaar uit a Datum in dagen, are both legal — no error.
        String ok = """
            Objecttype de Ding
                de reisdatum Datum in dagen;
                de moment Datum en tijd in millisecondes;
                de uurnr Numeriek (geheel getal);
                de jaartal Numeriek (geheel getal);
            Regel u
                geldig altijd
                    De uurnr van een Ding moet gesteld worden op het uur uit de moment van een Ding.
            Regel j
                geldig altijd
                    De jaartal van een Ding moet gesteld worden op het jaar uit de reisdatum van een Ding.
            """;
        assertTrue(Resolver.resolve(ok).errors().stream().noneMatch(d -> d.message().contains("tijd-component")),
                () -> "uur uit een datum-tijd / jaar uit een datum mag geen tijd-component-fout geven");
    }

    @Test void datumdeel_eerder_dan_een_datum_wordt_gemeld() {
        // §13.4.17.18-.24 / typeringen Tabel 28: a date component (maand uit) is a Numeriek, not a Datum.
        // Comparing it with 'eerder dan' a Datum is a §5.7/Tabel 45 type error (which restricts eerder/later
        // to two dates). The builtin used to be typed Onbekend, so Typering.vergelijk passed it silently and
        // the transpiler then emitted an uncompilable BigDecimal-vs-LocalDate term; it must now be flagged.
        String src = """
            Objecttype de Ding
                de reisdatum Datum in dagen;
                de mijlpaal Datum in dagen;
                is vroeg kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is vroeg
                    indien de maand uit (de reisdatum van een Ding) eerder is dan de mijlpaal van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("datum-operanden")),
                () -> "verwachtte een Tabel-45 datum-operanden-fout, kreeg: " + m.errors());
    }

    @Test void datumdeel_gelijk_aan_getal_geen_fout() {
        // The legal counterpart (toka-26 Hoogseizoen): a date component compared numerically to an integer
        // literal is fine — maand uit is a Numeriek (Tabel 28), 6 is a Numeriek literal.
        String src = """
            Objecttype de Ding
                de reisdatum Datum in dagen;
                is zomers kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is zomers
                    indien de maand uit (de reisdatum van een Ding) gelijk is aan 6.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een datumdeel == getal mag geen fout zijn: " + m.errors());
    }

    @Test void elfproef_op_geheel_getal_in_voorwaarde_geen_fout() {
        String src = """
            Objecttype de Ding
                de saldo Numeriek (geheel getal);
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de saldo van een Ding voldoet aan de elfproef.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een geheel-getal elfproef-operand is geldig: " + m.errors());
    }

    @Test void numeriek_met_exact_op_numeriek_in_voorwaarde_wordt_gemeld() {
        // p34 sibling: §5.16 numeriek-met-exact is Tekst-only; the verb-leading form under INDIEN was
        // likewise unchecked. A Numeriek operand must be flagged.
        String src = """
            Objecttype de Ding
                de saldo Numeriek (geheel getal);
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de saldo van een Ding is numeriek met exact 9 cijfers.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("met exact")),
                () -> "verwachtte een §5.16-fout in de voorwaarde, kreeg: " + m.errors());
    }

    @Test void verb_leidende_vergelijking_in_toplevel_positie_wordt_getypeerd() {
        // The broader root cause (p34): a VERB-LEADING comparison ("is groter dan", a nonToplevelOperator)
        // under INDIEN was unchecked because checkTopVoorwaarde only inspected the toplevelOperator. The
        // check must dispatch on whichever operator is present — a Tekst vs numeric ordering is a fout.
        String src = MODEL + """
            Regel r
                geldig altijd
                    De teller van een Ding moet gesteld worden op 0
                    indien de naam van een Ding is groter dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("voorwaarde")),
                () -> "verwachtte een voorwaarde-fout voor de verb-leidende vergelijking, kreeg: " + m.errors());
    }

    @Test void elfproef_op_min_van_onbeperkte_operanden_wordt_gemeld() {
        // p35: a min/max value-fold is geheel only if EVERY operand is geheel. A min of unbounded "getal"
        // operands is NOT provably integral, so the §5.13 elfproef must reject it. It used to be wrongly
        // accepted: the result was mislabelled geheel from minDecimalen==0, which an unbounded operand's
        // sentinel 0 also satisfies. (Observable since p34 wired the elfproef check in a voorwaarde.)
        String src = """
            Objecttype de Ding
                de a Numeriek (getal);
                de b Numeriek (getal);
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de minimale waarde van de a van een Ding en de b van een Ding voldoet aan de elfproef.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("elfproef")),
                () -> "een min van onbeperkte getallen is geen geheel getal; elfproef moet afwijzen: " + m.errors());
    }

    @Test void elfproef_op_min_van_decimaal_operand_wordt_gemeld() {
        // a min of a geheel and a 2-decimal operand can win the decimal value, so it is not integral.
        String src = """
            Objecttype de Ding
                de a Numeriek (geheel getal);
                de b Numeriek (getal met 2 decimalen);
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de minimale waarde van de a van een Ding en de b van een Ding voldoet aan de elfproef.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("elfproef")),
                () -> "een min met een decimaal-operand is niet geheel; elfproef moet afwijzen: " + m.errors());
    }

    @Test void elfproef_op_min_van_gehele_operanden_geen_fout() {
        // control: a min of all-geheel operands IS provably integral, so elfproef accepts it.
        String src = """
            Objecttype de Ding
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de minimale waarde van de a van een Ding en de b van een Ding voldoet aan de elfproef.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een min van gehele getallen is geheel: " + m.errors());
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

    static final String BEGRENS_MODEL = """
        Objecttype de Reis
            de prijs Numeriek (geheel getal);
            de basis Numeriek (geheel getal);
        Regel r
            geldig altijd
        """;

    @Test void bare_maximum_zonder_met_standalone_wordt_afgewezen() {
        // §13.4.17.28/.30: a STANDALONE maximum requires "met een maximum van"; the bare "een
        // maximum van" (no 'met') is admitted ONLY after 'en', so standalone it is a syntax error.
        String src = BEGRENS_MODEL +
                "        De prijs van een Reis moet gesteld worden op de basis van een Reis, een maximum van 100.\n";
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("syntaxfout")),
                () -> "bare 'een maximum van' standalone had afgewezen moeten worden, kreeg: " + m.errors());
    }

    @Test void met_een_maximum_van_standalone_en_bare_na_en_worden_geaccepteerd() {
        // the 'met' form standalone, and the bare form after 'en', both parse cleanly.
        String metStandalone = BEGRENS_MODEL +
                "        De prijs van een Reis moet gesteld worden op de basis van een Reis, met een maximum van 100.\n";
        assertTrue(Resolver.resolve(metStandalone).errors().isEmpty(),
                () -> "'met een maximum van' standalone: " + Resolver.resolve(metStandalone).errors());
        String bareNaEn = BEGRENS_MODEL +
                "        De prijs van een Reis moet gesteld worden op de basis van een Reis, met een minimum van 0 en een maximum van 100.\n";
        assertTrue(Resolver.resolve(bareNaEn).errors().isEmpty(),
                () -> "bare 'en een maximum van' na een minimum: " + Resolver.resolve(bareNaEn).errors());
    }

    @Test void leden_die_alleen_in_hoofdletters_verschillen_worden_gewaarschuwd() {
        // §13.2.30: a naam is not hoofdletter-onderscheidend, so two members differing only in
        // case are the same name twice — a third casing binds whichever was declared first, and
        // the typeringen verdict then flips on declaration order. Warn at registration.
        String src = """
            Objecttype de Rekening
                de saldo Numeriek (geheel getal);
                de saLdo Tekst;
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("hoofdletters")),
                () -> "verwachtte een hoofdletter-botsing-waarschuwing, kreeg: " + m.diagnostics);
    }

    @Test void leden_zonder_hoofdletterbotsing_geven_geen_waarschuwing() {
        // distinct member names must not warn.
        String src = """
            Objecttype de Rekening
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("hoofdletters")),
                () -> "geen botsing verwacht, kreeg: " + m.diagnostics);
    }

    @Test void attribuut_en_kenmerk_met_zelfde_naam_botsen_niet() {
        // The check is SAME-KIND: an attribute and a kenmerk may legitimately share a name (TOKA's
        // Vlucht has both a "bereikbaar per trein" kenmerk and attribute). A kenmerk carries no
        // datatype and never enters attribuut(), so even a case-only difference is not ambiguous.
        String src = """
            Objecttype de Vlucht
                is bereikBaar per trein kenmerk (bijvoeglijk);
                de bereikbaar per trein Boolean;
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("hoofdletters")),
                () -> "een attribuut en kenmerk met dezelfde naam mogen niet botsen, kreeg: " + m.diagnostics);
    }

    @Test void kenmerken_die_alleen_in_hoofdletters_verschillen_worden_gewaarschuwd() {
        // same-kind kenmerk collision is still flagged.
        String src = """
            Objecttype de Persoon
                is jong kenmerk (bijvoeglijk);
                is jOng kenmerk (bijvoeglijk);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("hoofdletters")),
                () -> "kenmerk-botsing verwacht, kreeg: " + m.diagnostics);
    }

    // ---- p22: §6.1.2 assignment precision (a value may not exceed the target's decimals) ----

    static final String GETAL_MODEL = """
        Objecttype de Ding
            de uitkomst Numeriek (geheel getal);
            de prijs Numeriek (getal met 2 decimalen);
        """;

    @Test void resultaat_met_meer_decimalen_dan_het_attribuut_wordt_gemeld() {
        // §6.1.2: a 1-decimal value (1,5) cannot be assigned to a geheel-getal attribute without an
        // explicit afronding. The literal types as 1 decimal now, so the assignment is flagged.
        String src = GETAL_MODEL + """
            Regel r
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op 1,5.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("decimalen")),
                () -> "verwachtte een §6.1.2 decimalen-fout, kreeg: " + m.errors());
    }

    @Test void resultaat_met_expliciete_afronding_geen_fout() {
        // §6.1.3: rounding first makes it legal — the afronding sets the result's precision to 0.
        String src = GETAL_MODEL + """
            Regel r
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op 1,5 naar beneden afgerond op 0 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een afgerond resultaat mag geen §6.1.2-fout geven: " + m.errors());
    }

    @Test void attribuut_met_meer_decimalen_naar_minder_wordt_gemeld() {
        // a read of a 2-decimal attribute assigned to a geheel-getal attribute exceeds its precision.
        String src = GETAL_MODEL + """
            Regel r
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op de prijs van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("decimalen")),
                () -> "verwachtte een §6.1.2 decimalen-fout, kreeg: " + m.errors());
    }

    @Test void minder_of_gelijke_decimalen_geen_fout() {
        // an integer (0 dec) into geheel getal, and a 1-decimal literal into a 2-decimal attribute,
        // both fit — no false positive.
        String src = GETAL_MODEL + """
            Regel a
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op 5.
            Regel b
                geldig altijd
                    De prijs van een Ding moet gesteld worden op 1,5.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geen §6.1.2-fout verwacht voor passende precisie: " + m.errors());
    }

    @Test void onbeperkt_getal_doel_accepteert_elke_precisie_geen_6_1_2_fout() {
        // p33: §13.3.3 form (c) "getal" is UNBOUNDED — no precision ceiling. Assigning a decimal value
        // to a Numeriek (getal) target must NOT trip §6.1.2 (the false positive: the unbounded target
        // read as decimalen 0 like a geheel getal). The geheel-getal and 2-decimal ceilings still apply.
        String src = """
            Objecttype de Ding
                de vrij Numeriek (getal);
                de geheel Numeriek (geheel getal);
                de prijs Numeriek (getal met 2 decimalen);
            Regel a
                geldig altijd
                    De vrij van een Ding moet gesteld worden op 1,5.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(),
                () -> "een onbeperkt 'getal'-doel mag geen §6.1.2-fout geven: " + m.errors());

        // control: the bounded ceilings are NOT relaxed — a decimal into geheel getal, and a 3-decimal
        // value into a 2-decimal attribute, both still error.
        RegelSpraakModel geheelFout = Resolver.resolve("""
            Objecttype de Ding
                de geheel Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De geheel van een Ding moet gesteld worden op 1,5.
            """);
        assertTrue(geheelFout.errors().stream().anyMatch(d -> d.message().contains("decimalen")),
                () -> "geheel-getal-plafond moet blijven gelden: " + geheelFout.errors());
        RegelSpraakModel prijsFout = Resolver.resolve("""
            Objecttype de Ding
                de prijs Numeriek (getal met 2 decimalen);
            Regel r
                geldig altijd
                    De prijs van een Ding moet gesteld worden op 1,234.
            """);
        assertTrue(prijsFout.errors().stream().anyMatch(d -> d.message().contains("decimalen")),
                () -> "2-decimalen-plafond moet blijven gelden: " + prijsFout.errors());
    }

    @Test void getal_met_0_decimalen_is_geheel_niet_onbeperkt() {
        // p33 review (grammar-adversary, fixed): "getal met 0 decimalen" (§13.3.3.7 wants >= 1) used to
        // encode as (geheel=false, decimalen=0), aliasing the UNBOUNDED form-(c) sentinel — so onbeperkt()
        // wrongly exempted it and the 0-decimal ceiling vanished. It denotes a whole number; normalize it
        // to geheel (capped at 0) with a WARNING, so assigning a decimal value is still rejected.
        String src = """
            Objecttype de Ding
                de nul Numeriek (getal met 0 decimalen);
            Regel r
                geldig altijd
                    De nul van een Ding moet gesteld worden op 1,5.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("decimalen")
                        && d.message().contains("§6.1.2")),
                () -> "een 'getal met 0 decimalen'-doel moet als geheel getal worden begrensd: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("getal met 0 decimalen")),
                () -> "verwachtte een WARNING over de degenererende getalspecificatie: " + m.diagnostics);
    }

    @Test void rekenkunde_met_incompatibele_eenheden_wordt_gemeld() {
        // p45: binairExpr used to SWALLOW the typeringen unit-incompatibility error, so "appels plus peren"
        // resolved silently (raw addition). The arithmetic path now surfaces the fout like the comparison /
        // begrenzing paths do.
        String src = """
            Eenheidsysteem Mix
                de appel app
                de peer pr
            Objecttype de Ding
                de a Numeriek (geheel getal) met eenheid app;
                de b Numeriek (geheel getal) met eenheid pr;
                de som Numeriek (geheel getal) met eenheid app;
            Regel r
                geldig altijd
                    De som van een Ding moet gesteld worden op de a van een Ding plus de b van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("in elkaar om te rekenen")),
                () -> "een plus over inconvertibele eenheden moet een eenheidfout geven: " + m.errors());
    }

    @Test void rekenkunde_met_verschillende_families_wordt_gemeld() {
        // the same swallowed path also dropped a family mismatch: Tekst plus Numeriek is a type error.
        String src = """
            Objecttype de Ding
                de naam Tekst;
                de teller Numeriek (geheel getal);
                de som Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De som van een Ding moet gesteld worden op de naam van een Ding plus de teller van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.errors().isEmpty(), () -> "Tekst plus Numeriek moet een fout geven");
    }

    @Test void rekenkunde_met_convertibele_eenheden_geen_fout() {
        // control: a convertible plus (jr + mnd, declared) stays clean; no false positive on a real sum.
        String src = """
            Eenheidsysteem Tijd
                de maand mnd
                het jaar jr = 12 mnd
            Objecttype de Ding
                de a Numeriek (geheel getal) met eenheid jr;
                de b Numeriek (geheel getal) met eenheid mnd;
                de som Numeriek (geheel getal) met eenheid mnd;
            Regel r
                geldig altijd
                    De som van een Ding moet gesteld worden op de a van een Ding plus de b van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een convertibele plus mag geen fout geven: " + m.errors());
    }

    @Test void percentage_van_decimaal_attribuut_naar_geheel_getal_geen_fout() {
        // p22 review (HIGH false positive, fixed): §4.8 makes "X% van Y" an exact fraction with no
        // decimal precision of its own (it shares only Y's unit), so 50% van prijs(2 dec) must NOT
        // inherit 2 decimals and trip §6.1.2 against a geheel-getal target. 50% van 4,00 = 2.
        String src = GETAL_MODEL + """
            Regel r
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op 50% van de prijs van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "percentage-van mag geen §6.1.2 valse positieve geven: " + m.errors());
    }

    @Test void multi_operand_maximum_met_gemengde_precisie_geen_valse_positieve() {
        // p22 review (MED false positive, fixed): a multi-operand min/max result is the winning
        // VALUE, whose precision is value-dependent; typing it from the first operand was
        // order-dependent. The conservative least-precision avoids flagging a valid assignment to a
        // geheel-getal target when the whole-number operand could win.
        String src = """
            Objecttype de Ding
                de uitkomst Numeriek (geheel getal);
                de hoeveelheid Numeriek (geheel getal);
                de prijs Numeriek (getal met 2 decimalen);
            Regel r
                geldig altijd
                    De uitkomst van een Ding moet gesteld worden op de maximale waarde van de hoeveelheid van een Ding en de prijs van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "multi-operand min/max mag geen §6.1.2 valse positieve geven: " + m.errors());
    }

    @Test void product_van_twee_decimalen_overschrijdt_de_precisie() {
        // p25: §4.4 — a product gets the SUM of the operands' decimals, not the max. prijs(2) maal
        // factor(2) is a 4-decimal result, so assigning it to a 2-decimal attribute is a §6.1.2
        // violation (it used to slip because maxDec reported 2).
        String src = GETAL_MODEL + """
            Regel r
                geldig altijd
                    De prijs van een Ding moet gesteld worden op de prijs van een Ding maal de prijs van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("decimalen")),
                () -> "verwachtte een §6.1.2 decimalen-fout (2+2 > 2), kreeg: " + m.errors());
    }

    @Test void product_van_decimaal_en_geheel_blijft_binnen_de_precisie() {
        // §4.4: 2 + 0 = 2 decimals — prijs(2) maal een geheel getal fits a 2-decimal attribute, so no
        // false positive (the common "bedrag maal aantal" shape).
        String src = GETAL_MODEL + """
            Regel r
                geldig altijd
                    De prijs van een Ding moet gesteld worden op de prijs van een Ding maal de uitkomst van een Ding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "2dec maal geheel (2+0=2) mag geen §6.1.2-fout geven: " + m.errors());
    }

    @Test void beslistabel_wordt_niet_stil_gedropt_maar_gemeld() {
        // honest-floor: a beslistabel (Ch.12 decision table) is parsed only as a name shell and check() loops
        // ONLY doc.regel(), so it used to vanish with NO diagnostic — the project's one true silent drop.
        // It must now surface an honest WARNING (no ERROR — it is an unimplemented gap, not invalid input).
        RegelSpraakModel m = Resolver.resolve("Beslistabel Woonregio factor");
        assertTrue(m.errors().isEmpty(), () -> "een beslistabel is geen fout, alleen nog niet omgezet: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("beslistabel")),
                () -> "een beslistabel moet een honest WARNING geven, niet stil verdwijnen: " + m.diagnostics);
    }
}
