package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
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

    @Test void tekstreeks_met_guillemets_lowert_naar_een_concat_niet_een_fout() {
        // §5.4/§13.4.17.9: a "…«expressie»…" tekstreeks now SUBSTITUTES its «…» parts — the lexer splits the quoted
        // span (TEKST_MODE) into literal chunks and «expressie» escapes, so it resolves without error and lowers to an
        // mvel concat (each part rsTekst-coerced). No longer the old honest-ERROR safety net; a plain "…" stays clean.
        String src = MODEL + """
            Regel zet naam
                geldig altijd
                    De naam van een Ding moet berekend worden als "teller is «de teller van een Ding» klaar".
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().noneMatch(d -> d.message().contains("tekstreeks")),
                () -> "een tekstreeks mag geen fout meer geven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("\"teller is \" + rsTekst(") && drl.contains(") + \" klaar\""),
                () -> "tekstreeks moet naar een rsTekst-concat lowereren:\n" + drl);

        String schoon = MODEL + """
            Regel zet naam schoon
                geldig altijd
                    De naam van een Ding moet berekend worden als "gewone tekst zonder gidsels".
            """;
        assertTrue(Resolver.resolve(schoon).errors().stream().noneMatch(d -> d.message().contains("tekstreeks")),
                () -> "een gewone tekst-literal mag geen tekstreeks-fout geven: " + Resolver.resolve(schoon).errors());
    }

    @Test void losse_guillemet_buiten_een_string_meldt_een_fout_niet_crasht() {
        // Robustness (adversarial review): a stray » (or an «…» the author forgot to wrap in "…") at TOP level must be
        // an honest syntaxfout, never an uncaught mode-stack underflow. The » lexer rule pops the mode ONLY inside an
        // escape (the mode stack is non-empty); at top level it is just a token the parser rejects. (Teeth: drop the
        // `!_modeStack.isEmpty()` guard on R_ANGLE_QUOTE and Resolver.resolve throws EmptyStackException on a stray ».)
        String stray = MODEL + """
            Regel r
                geldig altijd
                    De naam van een Ding moet berekend worden als »foutief.
            """;
        RegelSpraakModel m = assertDoesNotThrow(() -> Resolver.resolve(stray), "een losse » mag de lexer niet laten crashen");
        assertFalse(m.errors().isEmpty(), "een losse » moet een syntaxfout opleveren");
    }

    @Test void product_van_twee_eenheden_toegekend_aan_een_enkele_eenheid_wordt_gemeld() {
        // §4.4 / §6.1: "de prijs (€) maal de lengte (m)" is €·m, an opaque product unit convertible only to an
        // identical product — assigning it to a € attribute is a unit mismatch. Previously the product degraded
        // to Onbekend and the assignment passed silently.
        String src = """
            Eenheidsysteem Geld
                de euro EUR
            Eenheidsysteem Lengte
                de meter m
            Objecttype de Doos
                de prijs Numeriek (geheel getal) met eenheid EUR;
                de lengte Numeriek (geheel getal) met eenheid m;
                de uitkomst Numeriek (geheel getal) met eenheid EUR;
            Regel r
                geldig altijd
                    De uitkomst van een Doos moet gesteld worden op de prijs van een Doos maal de lengte van een Doos.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("EUR·m")),
                () -> "verwachtte een €·m eenheidfout, kreeg: " + m.errors());
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

    @Test void verdeling_met_onomrekenbare_eenheid_wordt_gemeld_zoals_een_regel() {
        // §13.4.10: a verdeling assigns a share to the doel like a regel assignment, so the same UNIT rule
        // applies — a source in € distributed into a target in metres is "eenheden niet om te rekenen", exactly
        // as the ordinary `regel` flags it (the verdeling previously ran only the §6.1.2 precision check).
        String src = """
            Eenheidsysteem Geld
                de euro EUR
            Eenheidsysteem Lengte
                de meter m
            Objecttype de Pot
                de totaal Numeriek (geheel getal) met eenheid EUR;
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal) met eenheid m;
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel deel
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("verdeling")
                        && d.message().contains("niet in elkaar om te rekenen")),
                () -> "verwachtte een onomrekenbare-eenheid verdeling-fout, kreeg: " + m.errors());
        // exactly one "verdeling:" prefix (not the double prefix the shared helper would add)
        assertFalse(m.errors().stream().anyMatch(d -> d.message().contains("verdeling: verdeling:")),
                () -> "de verdeling-eenheidsfout mag geen dubbele prefix hebben: " + m.errors());
    }

    @Test void verdeling_met_gelijke_eenheden_geeft_geen_fout() {
        // both source and target in EUR, inter-convertible -> no fout (and no false positive on the unit check).
        String src = """
            Eenheidsysteem Geld
                de euro EUR
            Objecttype de Pot
                de totaal Numeriek (geheel getal) met eenheid EUR;
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal) met eenheid EUR;
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel deel
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een verdeling met gelijke eenheden mag geen fout geven: " + m.errors());
    }

    @Test void verdeling_over_een_niet_numeriek_doel_meldt_een_familiefout_en_markert() {
        // §13.4.10 + toekenning family rule: a numeric verdeelplafond distributed over a NON-numeric recipient
        // (a Tekst "aandeel") is the same family mismatch the ordinary regel raises — the family check runs
        // BEFORE the getalachtig guard, so it is flagged, and the transpiler markers it instead of emitting an
        // uncompilable BigDecimal setter against a Tekst attribute. (Found by the slice's adversarial review.)
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Tekst;
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel deel
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("verdelen over een Tekst")),
                () -> "verwachtte een familie-fout voor een niet-numeriek doel, kreeg: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("onopgelost verdeling"), () -> "een niet-numeriek doel moet markeren, niet loweren:\n" + drl);
        assertFalse(drl.contains("setAandeel("), () -> "geen BigDecimal-setter tegen een Tekst-attribuut:\n" + drl);
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
        // p34: under INDIEN the elfproef predicate takes the vragende (verb-trailing) form "aan de elfproef
        // voldoet" (toplevelOperator). §5.13 Tabel 55 admits only Numeriek (geheel getal) or Tekst — a Datum
        // operand must be flagged.
        String src = """
            Objecttype de Ding
                de datum Datum in dagen;
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de datum van een Ding aan de elfproef voldoet.
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
                    indien de saldo van een Ding aan de elfproef voldoet.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een geheel-getal elfproef-operand is geldig: " + m.errors());
    }

    @Test void numeriek_met_exact_op_numeriek_in_voorwaarde_wordt_gemeld() {
        // p34 sibling: §5.16 numeriek-met-exact is Tekst-only; under INDIEN it takes the vragende form
        // "numeriek is met exact N cijfers". A Numeriek operand must be flagged.
        String src = """
            Objecttype de Ding
                de saldo Numeriek (geheel getal);
                is ok kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is ok
                    indien de saldo van een Ding numeriek is met exact 9 cijfers.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("met exact")),
                () -> "verwachtte een §5.16-fout in de voorwaarde, kreeg: " + m.errors());
    }

    @Test void verb_leidende_vergelijking_onder_indien_wordt_afgewezen() {
        // §8.3.1: a TOPLEVEL voorwaarde (after 'indien') takes ONLY the vragende (verb-trailing) form. The
        // verb-LEADING "is groter dan" is the stellende register, reserved for samengestelde bullets (§8.3.2),
        // so under INDIEN it is now a SYNTAX error — it used to be over-accepted.
        String verbLeidend = MODEL + """
            Regel r
                geldig altijd
                    De teller van een Ding moet gesteld worden op 0
                    indien de naam van een Ding is groter dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(verbLeidend);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("syntaxfout")),
                () -> "een verb-leidende vergelijking onder 'indien' moet nu een syntaxfout zijn, kreeg: " + m.errors());
        // the canonical vragende form (verb-trailing) still parses AND type-checks — a Tekst ordered vs Numeriek is a fout.
        String vragend = MODEL + """
            Regel r
                geldig altijd
                    De teller van een Ding moet gesteld worden op 0
                    indien de naam van een Ding groter is dan 0.
            """;
        RegelSpraakModel mv = Resolver.resolve(vragend);
        assertTrue(mv.errors().stream().anyMatch(d -> d.message().contains("voorwaarde")),
                () -> "de vragende vorm moet parsen én de Tekst-vs-Numeriek ordening als voorwaarde-fout typeren, kreeg: " + mv.errors());
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
                    indien de minimale waarde van de a van een Ding en de b van een Ding aan de elfproef voldoet.
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
                    indien de minimale waarde van de a van een Ding en de b van een Ding aan de elfproef voldoet.
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
                    indien de minimale waarde van de a van een Ding en de b van een Ding aan de elfproef voldoet.
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

    @Test void verdeling_met_dubbelzinnige_rol_geeft_geen_6_1_2_fout() {
        // Every verdeling method markers when the verbindende rol between source and recipient is ambiguous
        // (two feittypes relate Pot and Deelnemer -> verbindendeRol is null), so the transpiler assigns nothing.
        // The §6.1.2 precision check must mirror that join gate, not just the method/weight, else it flags a share
        // that is never computed (the over-precise 2-decimal totaal into an integer aandeel). Op-volgorde here, but
        // the join gate is shared across methods.
        String src = """
            Objecttype de Pot
                de totaal Numeriek (getal met 2 decimalen);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Feittype Pot naast deelnemers
                de buurpot Pot
                de buurdeelnemer Deelnemer
            Eén buurpot ligt naast meerdere buurdeelnemers
            Regel deel op volgorde
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht.
                    Als onverdeelde rest blijft de restant van een pot over.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().noneMatch(d -> d.message().contains("6.1.2")),
                () -> "een dubbelzinnige rol mag geen §6.1.2 verdeling-fout geven (de verdeling lowert niet): " + m.errors());
    }

    // ---- §3.7/§5.1 appositive "in <eenheid>" on an attribuut read (the spec's §12 reisduur conditie, main-spec
    //      L4744 "de reisduur per trein IN MINUTEN van zijn reis groter is dan ...") + §13.3.5 literal-vs-eenheid ----

    static final String AFSTAND_MODEL = """
        Eenheidsysteem afstand
            de kilometer (mv: kilometers)   km   =   1000 m
            de meter (mv: meters)           m
        Objecttype de Vlucht (mv: Vluchten)
            de afstand tot bestemming Numeriek (geheel getal) met eenheid km;
            de teller Numeriek (geheel getal);
        Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
            is ver kenmerk (bijvoeglijk);
        Feittype Vlucht van natuurlijke personen
            de reis Vlucht
            de passagier Natuurlijk persoon
        Eén reis betreft de verplaatsing van meerdere passagiers
        """;

    @Test void appositieve_eenheid_qualifier_wordt_gestript_en_literal_vs_eenheid_geeft_geen_fout() {
        // The resolver strips a native/convertible "in <eenheid>" suffix on a navigated attribuut read, so the bare
        // "afstand tot bestemming" (eenheid km) resolves and lowers to reis!.afstandTotBestemming — and a dimensionless
        // numeric literal (0) adopts the operand's unit, so "... in kilometers ... groter is dan 0" type-checks. This
        // is the exact TOKA-casus shape (concatenated L325). RED-before-green: without the strip the voorwaarde is a
        // marker; without the literal promotion it raises the §4 "beide er een hebben" unit error.
        String src = AFSTAND_MODEL + """
            Regel ver
                geldig altijd
                    Een Natuurlijk persoon is ver indien de afstand tot bestemming in kilometers van zijn reis groter is dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "in <eenheid> + literal-vs-eenheid mag geen fout geven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("reis!.afstandTotBestemming >"),
                () -> "de 'in kilometers' read moet gestript en gelowerd worden tot reis!.afstandTotBestemming:\n" + drl);
        assertFalse(drl.contains("/* TODO voorwaarde"), () -> "de voorwaarde mag geen marker zijn:\n" + drl);
    }

    @Test void onverenigbare_eenheid_suffix_blijft_marker_en_eenheidloos_attribuut_vs_eenheid_blijft_fout() {
        // Guard rails. (1) The strip fires ONLY for a convertible unit: "in euro" on a km attribute is not in elkaar
        // om te rekenen, so it stays an honest marker (not silently mis-read). (2) The literal exemption is
        // LITERAL-specific: a unitless ATTRIBUTE ("de teller") compared to a km attribute still trips the §4 unit rule.
        String marker = AFSTAND_MODEL + """
            Regel ver2
                geldig altijd
                    Een Natuurlijk persoon is ver indien de afstand tot bestemming in euro van zijn reis groter is dan 0.
            """;
        String mdrl = DrlTranspiler.transpile(Resolver.resolve(marker));
        assertTrue(mdrl.contains("/* TODO voorwaarde"),
                () -> "een onverenigbare 'in euro' eenheid mag NIET stripen (blijft marker):\n" + mdrl);

        String fout = AFSTAND_MODEL + """
            Regel ver3
                geldig altijd
                    Een Natuurlijk persoon is ver indien de afstand tot bestemming van zijn reis groter is dan de teller van zijn reis.
            """;
        RegelSpraakModel mf = Resolver.resolve(fout);
        assertTrue(mf.errors().stream().anyMatch(d -> d.message().contains("eenheid")),
                () -> "een eenheid-attribuut vs een eenheidloos ATTRIBUUT (geen literal) moet de §4 unit-fout geven: " + mf.errors());
    }

    @Test void niet_native_converteerbare_eenheid_suffix_blijft_marker_geen_stille_misrekening() {
        // The strip is IDENTITY-only: it re-states an attribute's OWN unit ("in maanden" on a mnd attribute). A
        // non-identity but convertible qualifier ("in jaren" on a mnd attribute) must NOT silently strip — dropping
        // it and reading the native magnitude would compare 6[mnd] to the literal as "6 > 1" where the author meant
        // 0,5[jr] > 1 (a shared bug both back-ends would agree on). It stays an honest marker.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Vlucht (mv: Vluchten)
                de vertrektijd Numeriek (geheel getal) met eenheid mnd;
            Objecttype de Persoon (mv: Personen) (bezield)
                is laat kenmerk (bijvoeglijk);
            Feittype Vlucht van personen
                de reis Vlucht
                de passagier Persoon
            Eén reis betreft meerdere passagiers
            Regel laat
                geldig altijd
                    Een Persoon is laat indien de vertrektijd in jaren van zijn reis groter is dan 1.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO voorwaarde"),
                () -> "een non-native 'in jaren' op een mnd-attribuut mag NIET stripen (zou 6>1 misrekenen) — blijft marker:\n" + drl);
        assertFalse(drl.contains("reis!.vertrektijd >"),
                () -> "de non-native qualifier mag NIET naar een rauwe reis!.vertrektijd vergelijking lowereren:\n" + drl);
    }

    private static boolean heeftNegatieSoortWaarschuwing(RegelSpraakModel m) {
        return m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                && d.message().contains("Tabel 16 vereist"));
    }

    @Test void cross_soort_negatie_werkwoord_wordt_gewaarschuwd() {
        // §8.1.8 / Tabel 16: the kenmerkcheck negation verb is keyed to the soort — a bezittelijk kenmerk negates
        // with "heeft", not "is". The de-negation is verb-agnostic (lowering stays correct), so "is geen <bezittelijk>"
        // is over-accepted; surface it as a WARNING (a writing-form lint, not an ERROR — the lowering is unchanged).
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                het recht kenmerk (bezittelijk);
                is jong kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is jong indien hij aan alle volgende voorwaarden voldoet:
                    - hij is geen recht
                    - hij is niet jong.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een writing-form lint mag geen ERROR zijn: " + m.errors());
        assertTrue(heeftNegatieSoortWaarschuwing(m),
                () -> "verwachtte een Tabel-16 soort-werkwoord waarschuwing voor 'is geen <bezittelijk>': " + m.diagnostics);
    }

    @Test void gehele_periode_op_niet_temporeel_onderwerp_wordt_afgewezen() {
        // §8.2 line 3437: "Deze check kan alleen worden uitgevoerd als het onderwerp van het predicaat
        // tijdsafhankelijk is." A "gedurende het gehele jaar" qualifier over a STATIC kenmerk is a spec error -> an
        // ERROR (rejected), not a silent never-fires marker.
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is jong kenmerk (bijvoeglijk);
                is duur kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is duur indien hij gedurende het gehele jaar jong is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("volledige periode")),
                () -> "verwachtte een §8.2 fout: gehele periode op een niet-temporeel onderwerp: " + m.errors());
    }

    @Test void gehele_periode_op_temporeel_onderwerp_geen_fout() {
        // Control: the same qualifier over a TEMPORAL kenmerk is valid -> no §8.2 error.
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                is rechthebbend kenmerk (bijvoeglijk) voor elke maand;
                is duur kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Passagier is duur indien hij niet gedurende het gehele jaar rechthebbend is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.errors().stream().anyMatch(d -> d.message().contains("volledige periode")),
                () -> "een temporeel onderwerp mag geen §8.2 fout geven: " + m.errors());
    }

    @Test void correcte_soort_negatie_geen_waarschuwing() {
        // Control: the verb matches the soort (bezittelijk -> heeft, bijvoeglijk -> is) -> no warning.
        String src = """
            Objecttype de Persoon (mv: Personen) (bezield)
                het recht kenmerk (bezittelijk);
                is jong kenmerk (bijvoeglijk);
            Regel R
                geldig altijd
                    Een Persoon is jong indien hij aan alle volgende voorwaarden voldoet:
                    - hij heeft geen recht
                    - hij is niet jong.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(heeftNegatieSoortWaarschuwing(m),
                () -> "correcte soort-werkwoord-combinaties mogen niet waarschuwen: " + m.diagnostics);
    }
}
