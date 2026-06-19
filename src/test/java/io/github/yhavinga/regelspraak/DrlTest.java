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
        // wortel has no accumulate equivalent yet; it must surface as an explicit marker, never
        // as silently-wrong DRL. (§13.4.17.13 makes the afronding mandatory.)
        String src = """
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
                de wortelprijs Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De wortelprijs van een Reis moet berekend worden als de wortel van de prijs van een Reis naar beneden afgerond op 2 decimalen.
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

    @Test void macht_afronding_rondt_de_macht_af_en_een_trailing_operator_bindt_buiten() {
        // §13.4.17.14: the mandatory afronding rounds the POWER (it is a sibling of the exponent,
        // not inside it), and a trailing 'maal' binds OUTSIDE the rounded power — the exponent is
        // now a primaireExpressie, so neither the afronding nor the operator is absorbed into it.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 2 naar beneden afgerond op 0 decimalen maal 3.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // the setScale (rounding the power) is multiplied by 3 -> the maal is outside the afronding
        assertTrue(drl.contains(".setScale(0, java.math.RoundingMode.FLOOR) * new java.math.BigDecimal(\"3\")"),
                () -> "afronding moet de macht afronden en 'maal 3' moet erbuiten binden:\n" + drl);
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

    @Test void lege_sommatie_is_leegwaarde_tenzij_een_fallback_is_opgegeven() {
        // §5.8.2: an empty sommatie is a leegwaarde, NOT 0 — Drools sum() returns 0, so the
        // non-empty contributors are counted and the result branches on that count. With
        // "of 0 als die er niet zijn" the empty case takes the fallback instead.
        String src = """
            Objecttype de Vlucht
                de somMet Numeriek (geheel getal);
                de somZonder Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel met
                geldig altijd
                    De somMet van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis of 0 als die er niet zijn.
            Regel zonder
                geldig altijd
                    De somZonder van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        // empty contributors counted; only non-null values summed
        assertTrue(drl.contains("$e1 : Passagier( reis == $subject, prijs != null )"), () -> "leeg-filter ontbreekt:\n" + drl);
        assertTrue(drl.contains("$n1 : count($e1)"), () -> "count-bewaking ontbreekt:\n" + drl);
        // fallback present -> 0 on empty; absent -> leegwaarde (null)
        assertTrue(drl.contains("setSomMet( ($n1 == 0 ? new java.math.BigDecimal(\"0\") : $acc1) )"), () -> "fallback-tak:\n" + drl);
        assertTrue(drl.contains("setSomZonder( ($n1 == 0 ? null : $acc1) )"), () -> "leegwaarde-tak:\n" + drl);
    }

    @Test void drempelkwantoren_worden_booleaanse_telcombinaties() {
        // §13.4.14.4: the threshold quantifiers count satisfied criteria. Drools constraints
        // take only booleans, so this is the OR of k-subsets (minstens): at-least-N directly,
        // at-most-N as !at-least-(N+1), exactly-N as their conjunction. No ternary arithmetic.
        String src = """
            Objecttype de Persoon
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                de c Numeriek (geheel getal);
                is gekozen kenmerk (bijvoeglijk);
                is afgewezen kenmerk (bijvoeglijk);
                is uniek kenmerk (bijvoeglijk);
            Regel minstens
                geldig altijd
                    Een Persoon is gekozen
                    indien hij aan ten minste 2 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0
                    - de c van een Persoon is groter dan 0.
            Regel hoogstens
                geldig altijd
                    Een Persoon is afgewezen
                    indien hij aan ten hoogste 1 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0.
            Regel preciesEen
                geldig altijd
                    Een Persoon is uniek
                    indien hij aan precies 1 van de volgende voorwaarden voldoet:
                    - de a van een Persoon is groter dan 0
                    - de b van een Persoon is groter dan 0.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("? 1 : 0"), () -> "geen ternaire telkunst in een constraint:\n" + drl);
        // ten minste 2 of 3 -> OR of the three pairs (a&&b) || (a&&c) || (b&&c)
        assertTrue(drl.contains(") && (b > ") && drl.contains(") || ("), () -> "minstens-combinaties:\n" + drl);
        // ten hoogste 1 of 2 -> !(at least 2) = !((a&&b))
        assertTrue(drl.contains("Persoon( !("), () -> "ten hoogste -> negatie:\n" + drl);
        // precies 1 of 2 -> (at least 1) && !(at least 2)
        assertTrue(drl.contains(" && !("), () -> "precies -> minstens N en niet minstens N+1:\n" + drl);
    }

    @Test void rule_versies_krijgen_een_rekendatum_venster_en_unieke_namen() {
        // §13.4.2.5 / §4.2: each version becomes a distinct rule guarded by the Rekendatum
        // window; a single-version "geldig altijd" rule keeps its bare name and no guard.
        String src = """
            Objecttype de Heffing
                de prijs Numeriek (geheel getal);
            Regel verhoog
                geldig vanaf 2024 t/m 2024
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2025
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("global java.time.LocalDate rekendatum;"), () -> "rekendatum-global ontbreekt:\n" + drl);
        assertTrue(drl.contains("rule \"verhoog #1\"") && drl.contains("rule \"verhoog #2\""),
                () -> "versies krijgen geen unieke namen:\n" + drl);
        assertTrue(drl.contains("!rekendatum.isBefore(java.time.LocalDate.of(2024, 1, 1))")
                && drl.contains("!rekendatum.isAfter(java.time.LocalDate.of(2024, 12, 31))"),
                () -> "venster van versie #1 ontbreekt:\n" + drl);
        // open-ended "vanaf 2025": a lower bound but no upper
        assertTrue(drl.contains("!rekendatum.isBefore(java.time.LocalDate.of(2025, 1, 1))"), () -> "venster #2:\n" + drl);
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

    @Test void onschaalbare_telling_wordt_marker_geen_globale_telling() {
        // p11 (verdict C): a count whose element has NO relationship to the subject cannot be
        // scoped to it. It must surface as a marker, NOT a comment-placeholder accumulate that
        // would count EVERY instance globally (§5.8.1: a count ranges over the related instances).
        String src = """
            Objecttype de Reiziger
                het aantal dingen Numeriek (geheel getal);
            Objecttype de Ding
            Regel tel
                geldig altijd
                    Het aantal dingen van een Reiziger moet gesteld worden op het aantal Ding van de reiziger.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("accumulate("), () -> "een onschaalbare telling mag geen globale accumulate worden:\n" + drl);
        assertTrue(drl.contains("/* TODO"), () -> "verwachtte een marker:\n" + drl);
    }

    @Test void naar_veel_rol_scalar_read_wordt_marker_niet_een_getter() {
        // §13.3.9.1: a scalar read through a TO-MANY role ("de leeftijd van de passagier", where
        // passagier is the meerdere side) cannot read one of many via a scalar getter — it must be
        // an aggregation. Emit a marker, not $subject.getPassagier().getLeeftijd().
        String src = """
            Objecttype de Vlucht
                de uitkomst Numeriek (geheel getal);
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    De uitkomst van een Vlucht moet gesteld worden op de leeftijd van de passagier.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO naar-veel-navigatie"), () -> "to-many read niet gemarkeerd:\n" + drl);
        assertFalse(drl.contains("getPassagier().getLeeftijd()"), () -> "mag geen scalar getter zijn:\n" + drl);
    }

    @Test void naar_een_rol_blijft_een_scalar_getter() {
        // the reverse direction (de vluchtprijs van de reis, reis = the één side) stays a getter.
        String src = """
            Objecttype de Vlucht
                de vluchtprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de uitkomst Numeriek (geheel getal);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    De uitkomst van een Passagier moet gesteld worden op de vluchtprijs van de reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject.getReis().getVluchtprijs()"), () -> "to-one getter ontbreekt:\n" + drl);
        assertFalse(drl.contains("naar-veel"), () -> "to-one mag geen marker zijn:\n" + drl);
    }

    @Test void telling_concatenatie_met_oplosbare_kenmerkfilters_wordt_een_union_accumulate() {
        // §4.11/§5.8.1: "het aantal X, Y en Z" (a concatenation) must PARSE, and when every operand
        // resolves to a kenmerk filter the union count lowers to ONE accumulate whose source ORs the
        // filters (p29 part 4) — NOT a silent count of only the first operand.
        String src = """
            Objecttype de Vlucht
                het aantal Numeriek (geheel getal);
            Objecttype de Passagier
                is jong kenmerk (bijvoeglijk);
                is oud kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het aantal van een Vlucht moet berekend worden als het aantal passagiers van de reis die een jong zijn en passagiers van de reis die een oud zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de concatenatie-telling moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate(") && drl.contains("jong == true") && drl.contains("oud == true")
                && drl.contains("||"), () -> "de union-telling moet één accumulate met OR-filters zijn:\n" + drl);
        assertFalse(drl.contains("/* TODO aggregatie AANTAL"), () -> "de oplosbare union mag geen marker zijn:\n" + drl);
    }

    @Test void telling_concatenatie_met_onoplosbare_operand_blijft_marker() {
        // A union with an operand that has NO resolvable kenmerk filter (here a bare set) cannot be
        // lowered faithfully — it stays an honest marker, never a count of only the first operand.
        String src = """
            Objecttype de Vlucht
                het aantal Numeriek (geheel getal);
            Objecttype de Passagier
                is jong kenmerk (bijvoeglijk);
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel r
                geldig altijd
                    Het aantal van een Vlucht moet berekend worden als het aantal passagiers van de reis die een jong zijn en passagiers van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de concatenatie-telling moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"), () -> "een onoplosbare union moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("accumulate("), () -> "een onoplosbare union mag niet stil tellen:\n" + drl);
    }

    @Test void union_over_verschillende_elementtypes_blijft_marker() {
        // p29(4) review (HIGH): a single accumulate has one source pattern, so operands ranging over
        // DIFFERENT element types cannot be unioned — even when they share a kenmerk name. Resolving
        // every operand against operand-0's element would silently drop the foreign set and OR its
        // filter onto the wrong fact. Such a union must stay a marker, never a wrong count.
        String src = """
            Objecttype de Vlucht
                het aantal Numeriek (geheel getal);
            Objecttype de Passagier
                is jong kenmerk (bijvoeglijk);
            Objecttype de Bemanning
                is jong kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype Vlucht met bemanning
                de vlucht Vlucht
                het bemanningslid Bemanning
            Eén vlucht betreft meerdere bemanningsleden
            Regel r
                geldig altijd
                    Het aantal van een Vlucht moet berekend worden als het aantal passagiers van de reis die een jong zijn, bemanningsleden van de reis die een jong zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"),
                () -> "een union over verschillende elementtypes moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("accumulate("), () -> "een type-mismatch union mag niet stil tellen:\n" + drl);
    }

    @Test void uniciteit_lowert_naar_een_accumulate_count() {
        // §8.1.6: a uniqueness check becomes a rule that binds the non-null key on the subject and
        // counts the instances whose key equals it; more than one (the subject plus a twin) is the
        // violation. Counting (not an exists self-join) flags each subject exactly once.
        String src = """
            Objecttype de Natuurlijk persoon
                het burgerservicenummer Numeriek (geheel getal);
            Regel bsn uniek
                geldig altijd
                    De burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : NatuurlijkPersoon( $k0 : burgerservicenummer != null )"),
                () -> "de sleutel moet niet-leeg gebonden worden:\n" + drl);
        assertTrue(drl.contains("Number( intValue > 1 ) from accumulate( $o : NatuurlijkPersoon( burgerservicenummer == $k0 ), count($o) )"),
                () -> "meer dan een instantie met dezelfde sleutel is de schending:\n" + drl);
    }

    @Test void uniciteit_met_gedeclareerd_onregelmatig_attribuut_meervoud_lowert() {
        // §8.1.6 writes the attribute in its meervoudsvorm; an attribuut is a naamwoord, so it may
        // declare "(mv: …)". The declared plural (incl. an irregular -eren one synthesis refuses)
        // binds the singular attribute exactly, so the uniqueness rule lowers.
        String src = """
            Objecttype de Reiziger
                het kind (mv: kinderen) Numeriek (geheel getal);
            Regel uniek
                geldig altijd
                    De kinderen van alle Reiziger moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject : Reiziger( $k0 : kind != null )"),
                () -> "de gedeclareerde meervoudsvorm moet het enkelvoud-attribuut binden:\n" + drl);
        assertTrue(drl.contains("from accumulate( $o : Reiziger( kind == $k0 ), count($o) )"),
                () -> drl);
    }

    @Test void uniciteit_over_onbekend_type_blijft_een_waarschuwing() {
        // The object type must resolve; "alle Routes" with no Route declared cannot lower — it stays a
        // WARNING and emits no rule, rather than silently dropping the consistency check.
        String src = """
            Objecttype de Vlucht
                het vluchtnummer Numeriek (geheel getal);
            Regel x
                geldig altijd
                    De vluchtnummers van alle Routes moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een onresolvebare uniciteit moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("accumulate("),
                "een onresolvebare uniciteit mag geen regel opleveren");
    }

    @Test void uniciteit_verenigd_met_verschillende_datatypes_blijft_een_waarschuwing() {
        // §8.1.6: a vereniging combines attributes of the SAME datatype. A Tekst attribute unioned with
        // a Numeriek one is ill-formed (mvel's coercive == would cross-match "5" and 5) -> WARNING, no rule.
        String src = """
            Objecttype de Cirkel
                de naam Tekst;
            Objecttype het Vierkant
                de code Numeriek (geheel getal);
            Regel mismatch
                geldig altijd
                    De namen van alle Cirkels verenigd met de codes van alle Vierkanten moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("consistentieregel")),
                "een datatype-mismatch vereniging moet waarschuwen");
        assertFalse(DrlTranspiler.transpile(m).contains("accumulate("),
                "een datatype-mismatch vereniging mag geen regel opleveren");
    }
}
