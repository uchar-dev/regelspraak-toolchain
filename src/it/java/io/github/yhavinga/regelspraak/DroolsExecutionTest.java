package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Bestelling;
import io.github.yhavinga.regelspraak.facts.Bestemming;
import io.github.yhavinga.regelspraak.facts.Burger;
import io.github.yhavinga.regelspraak.facts.Deelnemer;
import io.github.yhavinga.regelspraak.facts.Factuur;
import io.github.yhavinga.regelspraak.facts.Cirkel;
import io.github.yhavinga.regelspraak.facts.Contingent;
import io.github.yhavinga.regelspraak.facts.Heffing;
import io.github.yhavinga.regelspraak.facts.Inconsistentie;
import io.github.yhavinga.regelspraak.facts.Kandidaat;
import io.github.yhavinga.regelspraak.facts.Klok;
import io.github.yhavinga.regelspraak.facts.Konto;
import io.github.yhavinga.regelspraak.facts.Luchthaven;
import io.github.yhavinga.regelspraak.facts.Meting;
import io.github.yhavinga.regelspraak.facts.NatuurlijkPersoon;
import io.github.yhavinga.regelspraak.facts.Passagier;
import io.github.yhavinga.regelspraak.facts.Periode;
import io.github.yhavinga.regelspraak.facts.Persoon;
import io.github.yhavinga.regelspraak.facts.Pot;
import io.github.yhavinga.regelspraak.facts.Reis;
import io.github.yhavinga.regelspraak.facts.Rekensom;
import io.github.yhavinga.regelspraak.facts.Reiziger;
import io.github.yhavinga.regelspraak.facts.Route;
import io.github.yhavinga.regelspraak.facts.Trip;
import io.github.yhavinga.regelspraak.facts.Vierkant;
import io.github.yhavinga.regelspraak.facts.Vlucht;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The end-to-end proof: RegelSpraak the transpiler emits is not merely
 * string-shaped like DRL — it actually COMPILES and FIRES in a real Drools engine
 * and produces the right result. Runs under Maven only (the jar-only `make test`
 * stays Drools-free); the fact POJOs in io.github.yhavinga.regelspraak.facts are the runtime
 * contract the generated rules import.
 */
class DroolsExecutionTest {

    /** Compile the generated DRL; fail loudly with the builder errors if it does not. */
    private KieBase build(String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        Results results = helper.verify();
        assertFalse(results.hasMessages(Message.Level.ERROR),
                () -> "generated DRL did not compile:\n" + results.getMessages() + "\n---\n" + drl);
        return helper.build();
    }

    @Test void berekening_met_voorwaarde_vuurt_en_zet_attribuut() {
        String src = """
            Parameter de grens : Numeriek (geheel getal)
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Regel verhoog
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de totaalprijs van een Vlucht plus de grens
                    indien de totaalprijs van een Vlucht kleiner is dan de grens.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        sessie.setGlobal("grens", new BigDecimal("100"));
        Vlucht v = new Vlucht();
        v.setTotaalprijs(new BigDecimal("10"));   // 10 < 100 -> rule fires: 10 + 100
        sessie.insert(v);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("110").compareTo(v.getTotaalprijs()),
                () -> "verwacht 110, kreeg " + v.getTotaalprijs());
    }

    @Test void parameter_numeriek_zonder_getalspecificatie_vuurt_als_drempel() {
        // The §4.4 worked-example form "Numeriek met eenheid jr" (no getalspecificatie) used to NPE the
        // resolver (p31). Once it degrades gracefully it must still be a usable, well-typed global: this
        // fires a kenmerktoekenning gated on it to prove the resolved BigDecimal threshold works end-to-end.
        String src = """
            Eenheidsysteem Tijd
                het jaar           jr
            Parameter de grens : Numeriek met eenheid jr
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                is gepensioneerd kenmerk (bijvoeglijk);
            Regel pensioencheck
                geldig altijd
                    Een Persoon is gepensioneerd
                    indien zijn leeftijd groter is dan de grens.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        sessie.setGlobal("grens", new BigDecimal("67"));
        Persoon oud = new Persoon();  oud.setLeeftijd(new BigDecimal("70"));   // 70 > 67 -> gepensioneerd
        Persoon jong = new Persoon(); jong.setLeeftijd(new BigDecimal("30"));  // 30 > 67 -> niet
        sessie.insert(oud); sessie.insert(jong);
        sessie.fireAllRules();
        sessie.dispose();

        assertTrue(oud.isGepensioneerd(), "70 > grens(67) had moeten vuren");
        assertFalse(jong.isGepensioneerd(), "30 > grens(67) had niet mogen vuren");
    }

    @Test void onbeperkt_getal_doel_neemt_een_decimaalwaarde_aan_en_vuurt() {
        // p33: assigning a 2-decimal value to an unbounded Numeriek (getal) target used to be rejected
        // by a spurious §6.1.2 precision error (the unbounded sentinel read as a 0-decimal ceiling), so
        // the rule never resolved. Now it resolves and fires, copying the decimal value intact.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (getal);
            Parameter de basis : Numeriek (getal met 2 decimalen)
            Regel zet
                geldig altijd
                    De totaalprijs van een Vlucht moet gesteld worden op de basis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onbeperkt doel mag geen §6.1.2-fout geven: " + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        sessie.setGlobal("basis", new BigDecimal("12.34"));
        Vlucht v = new Vlucht();
        sessie.insert(v);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("12.34").compareTo(v.getTotaalprijs()),
                () -> "verwacht 12.34 ongewijzigd, kreeg " + v.getTotaalprijs());
    }

    @Test void aggregatie_accumuleert_over_de_join() {
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel totaal
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate("), () -> "geen accumulate:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setPrijs(new BigDecimal("30")); a.setReis(v);
        Passagier b = new Passagier(); b.setPrijs(new BigDecimal("70")); b.setReis(v);
        Vlucht ander = new Vlucht();                                  // a flight with no passengers
        Passagier c = new Passagier(); c.setPrijs(new BigDecimal("999")); c.setReis(ander);
        sessie.insert(v); sessie.insert(ander);
        sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("100").compareTo(v.getTotaalprijs()),
                () -> "som over de juiste passagiers faalde: " + v.getTotaalprijs());
    }

    @Test void aantal_telt_via_de_rol_niet_alle_facts() {
        // p11: "het aantal bestemming van de reiziger" collides with the objecttype Bestemming.
        // The count must range over the Luchthavens the reiziger reaches via the "bestemming"
        // role (reiziger == $subject) -- NOT all Bestemming facts, NOT all Luchthavens globally.
        String src = """
            Objecttype de Reiziger
                het aantal bestemmingen Numeriek (geheel getal);
            Objecttype de Luchthaven
            Objecttype de Bestemming
            Feittype Reiziger met bestemmingen
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger betreft meerdere bestemmingen
            Regel tel
                geldig altijd
                    Het aantal bestemmingen van een Reiziger moet gesteld worden op het aantal bestemming van de reiziger.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("Luchthaven( reiziger == $subject )"),
                () -> "verwachtte een join via de rol, niet een comment-placeholder:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Reiziger r = new Reiziger();
        Reiziger ander = new Reiziger();
        Luchthaven l1 = new Luchthaven(); l1.setReiziger(r);
        Luchthaven l2 = new Luchthaven(); l2.setReiziger(r);
        Luchthaven l3 = new Luchthaven(); l3.setReiziger(ander);    // another reiziger's -> excluded
        Bestemming b1 = new Bestemming(); Bestemming b2 = new Bestemming(); // free objecttype -> excluded
        sessie.insert(r); sessie.insert(ander);
        sessie.insert(l1); sessie.insert(l2); sessie.insert(l3);
        sessie.insert(b1); sessie.insert(b2);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("2").compareTo(r.getAantalBestemmingen()),
                () -> "telt 2 luchthavens van deze reiziger, niet alle facts; kreeg " + r.getAantalBestemmingen());
    }

    @Test void aantal_kiest_de_join_via_de_geschreven_rol_bij_twee_feittypen() {
        // p11 review (verdict B): when TWO feittypen join the (element, subject) pair, the by-pair
        // join search is ambiguous; the count names exactly one element role ("vertrek"), so the
        // join must be the matching feittype's subject-side field (vertrekkendeReiziger), not a
        // comment placeholder counting every Luchthaven.
        String src = """
            Objecttype de Reiziger
                het aantal vertrekluchthavens Numeriek (geheel getal);
            Objecttype de Luchthaven
            Feittype Reiziger en vertrekluchthaven
                de vertrekkende reiziger Reiziger
                de vertrek Luchthaven
            Eén vertrekkende reiziger betreft meerdere vertrekken
            Feittype Reiziger en aankomstluchthaven
                de aankomende reiziger Reiziger
                de aankomst Luchthaven
            Eén aankomende reiziger betreft meerdere aankomsten
            Regel tel
                geldig altijd
                    Het aantal vertrekluchthavens van een Reiziger moet gesteld worden op het aantal vertrek van de reiziger.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("Luchthaven( vertrekkendeReiziger == $subject )"),
                () -> "verwachtte de join via de geschreven rol 'vertrek':\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Reiziger r = new Reiziger();
        Luchthaven v1 = new Luchthaven(); v1.setVertrekkendeReiziger(r);
        Luchthaven v2 = new Luchthaven(); v2.setVertrekkendeReiziger(r);
        Luchthaven aankomst = new Luchthaven(); aankomst.setAankomendeReiziger(r);  // an arrival, not counted
        sessie.insert(r); sessie.insert(v1); sessie.insert(v2); sessie.insert(aankomst);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("2").compareTo(r.getAantalVertrekluchthavens()),
                () -> "telt 2 vertrek-luchthavens, niet de aankomst; kreeg " + r.getAantalVertrekluchthavens());
    }

    @Test void begrenzing_grens_wordt_omgerekend_naar_de_eenheid_van_de_waarde() {
        // §4/§6.1.4: a convertible-but-different bound unit is restated in the basis unit before
        // the clamp. duur 20 mnd capped at a 1 jr maximum (= 12 mnd) must give 12, not 1 (a raw
        // "20 > 1 ? 1 : 20" comparison would wrongly clamp to 1).
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Periode
                de duur Numeriek (geheel getal) met eenheid mnd;
                de netto Numeriek (geheel getal) met eenheid mnd;
                de looptijd Numeriek (geheel getal) met eenheid jr;
            Regel netto
                geldig altijd
                    De netto van een Periode moet gesteld worden op de duur van een Periode, met een maximum van de looptijd van een Periode.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Periode p = new Periode(); p.setDuur(new BigDecimal("20")); p.setLooptijd(new BigDecimal("1")); // cap = 1 jr = 12 mnd
        sessie.insert(p);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("12").compareTo(p.getNetto()),
                () -> "min(20 mnd, 1 jr=12 mnd) = 12, kreeg " + p.getNetto());
    }

    @Test void maximale_waarde_van_concatenatie_kiest_de_grootste_en_rekent_eenheden_om() {
        // §4.13 Tabel 23: "de maximale waarde van A en B" over two LISTED operands is a pairwise
        // max, NOT a collection accumulate. The operands carry different-but-convertible units
        // (duur in mnd, looptijd in jr); the jr operand must be restated in mnd before comparing,
        // so max(5 mnd, 1 jr = 12 mnd) = 12, not a raw "5 > 1 ? 5 : 1" = 5.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Periode
                de duur Numeriek (geheel getal) met eenheid mnd;
                de netto Numeriek (geheel getal) met eenheid mnd;
                de looptijd Numeriek (geheel getal) met eenheid jr;
            Regel netto
                geldig altijd
                    De netto van een Periode moet gesteld worden op de maximale waarde van de duur van een Periode en de looptijd van een Periode.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("accumulate("), () -> "concatenatie mag GEEN collectie-accumulate worden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Periode p = new Periode(); p.setDuur(new BigDecimal("5")); p.setLooptijd(new BigDecimal("1")); // 1 jr = 12 mnd
        sessie.insert(p);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("12").compareTo(p.getNetto()),
                () -> "max(5 mnd, 1 jr=12 mnd) = 12, kreeg " + p.getNetto());
    }

    @Test void eerste_van_concatenatie_kiest_de_vroegste_datum() {
        // §4.14 Tabel 24: "de eerste van A en B" over two LISTED date operands is the earliest of
        // the two (a pairwise comparison), not a collection accumulate.
        String src = """
            Objecttype de Vlucht
                de verwacht vertrek Datum in dagen;
                de daadwerkelijk vertrek Datum in dagen;
                de vroegste vertrek Datum in dagen;
            Regel vroegste
                geldig altijd
                    De vroegste vertrek van een Vlucht moet gesteld worden op de eerste van de verwacht vertrek van een Vlucht en de daadwerkelijk vertrek van een Vlucht.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        v.setVerwachtVertrek(LocalDate.of(2025, 3, 10));
        v.setDaadwerkelijkVertrek(LocalDate.of(2025, 3, 5));   // earlier -> "eerste van" picks this
        sessie.insert(v);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(LocalDate.of(2025, 3, 5), v.getVroegsteVertrek(),
                () -> "eerste van (10 mrt, 5 mrt) = 5 mrt, kreeg " + v.getVroegsteVertrek());
    }

    @Test void concatenatie_aggregatie_telt_leegwaarden_niet_mee() {
        // §4.12-4.15: "leegwaarden … worden niet meegeteld; alle leeg → leeg". A null (empty)
        // operand must be SKIPPED, not poison the result — a bare "5 > null ? 5 : null" would
        // wrongly yield null (mvel evaluates null comparisons as false). Fire with one empty
        // operand in each position and with both empty.
        String src = """
            Objecttype de Periode
                de duur Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
                de looptijd Numeriek (geheel getal);
            Regel netto
                geldig altijd
                    De netto van een Periode moet gesteld worden op de maximale waarde van de duur van een Periode en de looptijd van een Periode.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieBase kb = build(DrlTranspiler.transpile(m));

        assertEquals(0, new BigDecimal("5").compareTo(maxNetto(kb, "5", null)),
                "looptijd leeg -> max telt alleen duur=5");
        assertEquals(0, new BigDecimal("9").compareTo(maxNetto(kb, null, "9")),
                "duur leeg -> max telt alleen looptijd=9");
        assertNull(maxNetto(kb, null, null), "beide leeg -> resultaat leeg (null)");
    }

    private static BigDecimal maxNetto(KieBase kb, String duur, String looptijd) {
        KieSession sessie = kb.newKieSession();
        Periode p = new Periode();
        if (duur != null) p.setDuur(new BigDecimal(duur));
        if (looptijd != null) p.setLooptijd(new BigDecimal(looptijd));
        sessie.insert(p);
        sessie.fireAllRules();
        sessie.dispose();
        return p.getNetto();
    }

    @Test void afronding_na_begrenzing_rondt_het_geheel_af_niet_de_grens() {
        // §13.4.17.25-30: "X, met een minimum van 0 naar beneden afgerond op 0 decimalen" must
        // round the WHOLE bounded result (floor(max(X,0))), not the 0-bound. A full-expressie
        // bound would absorb the afronding and leave the result unrounded; the primaireExpressie
        // bound keeps the afronding on the outer expression. Proven by firing: basis 7 -> 7/2=3.5
        // -> max(3.5,0)=3.5 -> floor = 3 (a bound-only rounding would leave 3.5).
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel netto
                geldig altijd
                    De netto van een Heffing moet berekend worden als de basis van een Heffing gedeeld door 2, met een minimum van 0 naar beneden afgerond op 0 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rsAfronding((") && drl.contains(", 0, java.math.RoundingMode.FLOOR) )"),
                () -> "de afronding (rsAfronding) moet de hele begrensde expressie omsluiten:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Heffing pos = new Heffing(); pos.setBasis(new BigDecimal("7"));    // 7/2=3.5 -> floor 3
        Heffing neg = new Heffing(); neg.setBasis(new BigDecimal("-4"));   // -4/2=-2 -> min 0 -> 0
        sessie.insert(pos); sessie.insert(neg);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("3").compareTo(pos.getNetto()),
                () -> "floor(max(7/2,0)) = 3, kreeg " + pos.getNetto());
        assertEquals(0, BigDecimal.ZERO.compareTo(neg.getNetto()),
                () -> "max(-2,0) = 0, kreeg " + neg.getNetto());
    }

    @Test void geparenthethiseerde_rekenkundige_grens_bindt_binnen_de_begrenzing() {
        // The bound is a primaireExpressie, so a BARE arithmetic bound mis-binds (documented
        // divergence); the workaround is to parenthesise it. "met een maximum van (de basis maal 2)"
        // must bind the whole "basis maal 2" as the cap: min(basis, basis*2) = basis for basis>0.
        // basis 10 -> 10 (a mis-bound "maal 2 outside" would give min(10,10)*2 = 20).
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel netto
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing, met een maximum van (de basis van een Heffing maal 2).
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Heffing h = new Heffing(); h.setBasis(new BigDecimal("10"));
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("10").compareTo(h.getNetto()),
                () -> "min(10, 10*2) = 10 (parenthesised bound), kreeg " + h.getNetto());
    }

    @Test void samengestelde_eenheid_rekent_de_rate_om() {
        // §13.3.3.2 / §6.5: a rate unit (€/mnd, €/jr) must convert numerator AND denominator.
        // Assigning a €/mnd value to a €/jr attribute scales by 12 (the denominator jr is 12 of
        // the mnd it replaces): 10 €/mnd = 120 €/jr. Before, the denominator was dropped and both
        // resolved to € — no scaling at all.
        String src = """
            Eenheidsysteem Geld
                de euro €
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Heffing
                de prijs Numeriek (geheel getal) met eenheid €/jr;
                de basis Numeriek (geheel getal) met eenheid €/mnd;
            Regel r
                geldig altijd
                    De prijs van een Heffing moet gesteld worden op de basis van een Heffing.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains(".multiply(new java.math.BigDecimal(\"12\"))"),
                () -> "rate-omrekening (x12) ontbreekt:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Heffing h = new Heffing(); h.setBasis(new BigDecimal("10"));   // 10 €/mnd
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("120").compareTo(h.getPrijs()),
                () -> "10 €/mnd = 120 €/jr, kreeg " + h.getPrijs());
    }

    @Test void voorwaarde_niet_numerieke_gelijkheid_beide_leeg_vuurt_niet() {
        // p40: "indien vertrek gelijk aan bestemming" over Tekst. §5.3/Tabel 38 make a BOTH-empty
        // non-numeriek equality "fout" (a foutmelding), not "waar" — so the rule guard is not satisfied and
        // must NOT fire. Plain mvel "null == null" is true (would fire); the guard excludes the both-empty
        // case. Gevuld-equal still fires; one-empty and unequal do not.
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
                is gemarkeerd kenmerk (bijvoeglijk);
            Regel mark
                geldig altijd
                    Een Route is gemarkeerd
                    indien de vertrek van een Route gelijk is aan de bestemming van een Route.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Route beideLeeg = new Route();                                                  // null == null -> fout -> niet
        Route gelijk = new Route(); gelijk.setVertrek("A"); gelijk.setBestemming("A");  // waar -> wel
        Route eenLeeg = new Route(); eenLeeg.setVertrek("A");                           // "A" == null -> onwaar -> niet
        Route anders = new Route(); anders.setVertrek("A"); anders.setBestemming("B");  // onwaar -> niet
        sessie.insert(beideLeeg); sessie.insert(gelijk); sessie.insert(eenLeeg); sessie.insert(anders);
        sessie.fireAllRules();
        sessie.dispose();

        assertFalse(beideLeeg.isGemarkeerd(), "beide leeg is 'fout' (Tabel 38) -> mag niet vuren");
        assertTrue(gelijk.isGemarkeerd(), "twee gelijke gevulde waarden -> vuurt");
        assertFalse(eenLeeg.isGemarkeerd(), "een gevuld, een leeg is onwaar -> niet");
        assertFalse(anders.isGemarkeerd(), "twee ongelijke waarden -> niet");
    }

    @Test void cross_unit_gelijkheid_is_exact_geen_vals_positief() {
        // p43: comparing across convertible units must scale by the DECLARED (exact) factors, not by a
        // truncated ratio. "grensjr (jr) gelijk aan duurmnd (mnd)" used to lower to "grensjr != duurmnd *
        // 0.08333..." (mnd->jr = 1/12 truncated), so 2 jr == 24 mnd read "2 != 1.9999..." -> a spurious
        // violation. Now both sides scale to the canonical (mnd): (grensjr * 12) != duurmnd -> 24 != 24.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Meting
                de duurmnd Numeriek (geheel getal) met eenheid mnd;
                de grensjr Numeriek (geheel getal) met eenheid jr;
            Regel gelijkcheck
                geldig altijd
                    De grensjr van een Meting moet gelijk zijn aan de duurmnd van een Meting.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("0.083"), () -> "geen lossy 1/12-factor meer:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Meting gelijk = new Meting(); gelijk.setGrensjr(new BigDecimal("2"));  gelijk.setDuurmnd(new BigDecimal("24")); // 2 jr == 24 mnd
        Meting anders = new Meting(); anders.setGrensjr(new BigDecimal("2"));  anders.setDuurmnd(new BigDecimal("25")); // 2 jr != 25 mnd
        sessie.insert(gelijk); sessie.insert(anders);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertFalse(fouten.contains(gelijk), () -> "2 jr == 24 mnd is consistent (geen vals-positief): " + fouten);
        assertTrue(fouten.contains(anders), () -> "2 jr != 25 mnd is een echte schending: " + fouten);
    }

    @Test void macht_afronding_rondt_de_macht_af_en_compileert_als_bigdecimal() {
        // §13.4.17.14: the mandatory afronding rounds the POWER, not the exponent. "basis tot de
        // macht 2 afgerond op 0" with basis=5 must give 25 (5^2), and the Math.pow result must
        // lower to a BigDecimal so setScale compiles and fires.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel macht
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 2 naar beneden afgerond op 0 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Heffing h = new Heffing(); h.setBasis(new BigDecimal("5"));
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("25").compareTo(h.getNetto()),
                () -> "5 tot de macht 2 = 25, kreeg " + h.getNetto());
    }

    @Test void wortel_rondt_de_root_af_via_zijn_verplichte_afronding() {
        // §13.4.17.13: the wortel's afronding is MANDATORY and rounds the ROOT. sqrt(20) = 4,4721…, floored
        // to 2 decimals -> 4,47 (not the unrounded double). The afronding used to be parsed and dropped.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (getal met 2 decimalen);
            Regel w
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 2 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Heffing h = new Heffing(); h.setBasis(new BigDecimal("20"));
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("4.47").compareTo(h.getNetto()),
                () -> "wortel van 20, op 2 decimalen naar beneden = 4,47, kreeg " + h.getNetto());
        assertTrue(h.getNetto().scale() <= 2, () -> "de afronding moet de root afronden, kreeg scale " + h.getNetto().scale());
    }

    @Test void wortel_van_een_terminerende_root_rondt_exact_geen_double_ruis() {
        // 897.3^2 = 805147.29, so the root is EXACTLY 897.3. A double Math.sqrt gives 897.3000000000001, which
        // CEILING-to-1-decimal wrongly bumps to 897.4; BigDecimal.sqrt keeps it exact -> 897.3.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (getal met 2 decimalen);
                de netto Numeriek (getal met 1 decimalen);
            Regel w
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar boven afgerond op 1 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Heffing h = new Heffing(); h.setBasis(new BigDecimal("805147.29"));
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("897.3").compareTo(h.getNetto()),
                () -> "wortel van 805147,29 naar boven op 1 decimaal = 897,3 (geen double-ruis naar 897,4), kreeg " + h.getNetto());
    }

    @Test void macht_met_gehele_exponent_is_exact_boven_de_double_grens() {
        // §4.7: the result is an exact Numeriek rounded by the afronding. An integer power above
        // 2^53 must be exact — 7^19 = 11398895185373143, not the 11398895185373144 that Math.pow
        // (a double) yields. BigDecimal.pow(int) computes it exactly.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (geheel getal);
            Regel macht
                geldig altijd
                    De netto van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 19 naar beneden afgerond op 0 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Heffing h = new Heffing(); h.setBasis(new BigDecimal("7"));
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("11398895185373143").compareTo(h.getNetto()),
                () -> "7 tot de macht 19 = 11398895185373143 exact, kreeg " + h.getNetto());
    }

    @Test void gedeeld_door_abs_rondt_af_op_vijf_decimalen_richting_nul() {
        // §6.5: "gedeeld door (ABS)" yields at most 5 decimals, rounded TOWARD ZERO — unlike the exact /
        // fraction "gedeeld door". 5 / 3 = 1,6666… -> 1,66666 (truncated to 5, not the DECIMAL64 16-digit
        // 1,6666666666666667). A negative quotient truncates toward zero too: 2 / -3 -> -0,66666.
        String src = """
            Objecttype de Kandidaat
                de a Numeriek (getal met 5 decimalen);
                de b Numeriek (getal met 5 decimalen);
                de c Numeriek (getal met 5 decimalen);
            Regel deel abs
                geldig altijd
                    De c van een Kandidaat moet gesteld worden op de a van een Kandidaat gedeeld door (ABS) de b van een Kandidaat.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Kandidaat pos = new Kandidaat(); pos.setA(new BigDecimal("5")); pos.setB(new BigDecimal("3"));
        Kandidaat neg = new Kandidaat(); neg.setA(new BigDecimal("2")); neg.setB(new BigDecimal("-3"));
        sessie.insert(pos); sessie.insert(neg);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("1.66666").compareTo(pos.getC()),
                () -> "5 gedeeld door (ABS) 3 = 1,66666 (richting 0, 5 decimalen), kreeg " + pos.getC());
        assertTrue(pos.getC().scale() <= 5, () -> "ten hoogste 5 decimalen, kreeg scale " + pos.getC().scale());
        assertEquals(0, new BigDecimal("-0.66666").compareTo(neg.getC()),
                () -> "2 gedeeld door (ABS) -3 = -0,66666 (richting 0), kreeg " + neg.getC());
    }

    @Test void scalaire_builtins_absolute_waarde_datumdelen_en_datum_met() {
        // §13.4.17.17-.24/.35/.36: the absolute value, a date's year/month parts, an hour from a datetime, and a
        // date/datetime built from numeric parts each lower to a single Java expression that fires in the engine.
        String src = """
            Objecttype de Klok
                de saldo Numeriek (geheel getal);
                de absval Numeriek (geheel getal);
                de reisdatum Datum in dagen;
                de jaartal Numeriek (geheel getal);
                de maandnr Numeriek (geheel getal);
                de bouwjaar Numeriek (geheel getal);
                de gemaakt Datum in dagen;
                de moment Datum en tijd in millisecondes;
                de uurnr Numeriek (geheel getal);
                de stip Datum en tijd in millisecondes;
            Regel abs
                geldig altijd
                    De absval van een Klok moet gesteld worden op de absolute waarde van (de saldo van een Klok).
            Regel jaar
                geldig altijd
                    De jaartal van een Klok moet gesteld worden op het jaar uit de reisdatum van een Klok.
            Regel maand
                geldig altijd
                    De maandnr van een Klok moet gesteld worden op de maand uit de reisdatum van een Klok.
            Regel bouw
                geldig altijd
                    De gemaakt van een Klok moet gesteld worden op de datum met jaar: de bouwjaar van een Klok, maand: 3 en dag: 15.
            Regel uur
                geldig altijd
                    De uurnr van een Klok moet gesteld worden op het uur uit de moment van een Klok.
            Regel dt
                geldig altijd
                    De stip van een Klok moet gesteld worden op de datumtijd met jaar: de bouwjaar van een Klok, maand: 3, dag: 15, uur: 10, minuut: 30, seconde: 0 en milliseconde: 500.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Klok k = new Klok();
        k.setSaldo(new BigDecimal("-7"));
        k.setReisdatum(LocalDate.of(2021, 6, 15));
        k.setBouwjaar(new BigDecimal("2000"));
        k.setMoment(LocalDateTime.of(2022, 1, 2, 14, 5, 9));
        sessie.insert(k);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("7").compareTo(k.getAbsval()), () -> "|-7| = 7, kreeg " + k.getAbsval());
        assertEquals(0, new BigDecimal("2021").compareTo(k.getJaartal()), () -> "jaar uit 2021-06-15 = 2021, kreeg " + k.getJaartal());
        assertEquals(0, new BigDecimal("6").compareTo(k.getMaandnr()), () -> "maand uit 2021-06-15 = 6, kreeg " + k.getMaandnr());
        assertEquals(LocalDate.of(2000, 3, 15), k.getGemaakt(), () -> "datum met 2000-3-15, kreeg " + k.getGemaakt());
        assertEquals(0, new BigDecimal("14").compareTo(k.getUurnr()), () -> "uur uit 14:05:09 = 14, kreeg " + k.getUurnr());
        assertEquals(LocalDateTime.of(2000, 3, 15, 10, 30, 0, 500_000_000), k.getStip(),
                () -> "datumtijd met …, milliseconde 500 = .500s, kreeg " + k.getStip());
    }

    @Test void tijdsduur_telt_volledige_eenheden_absolute_keert_teken_leeg_blijft_leeg() {
        // §6.10: tijdsduur is the number of COMPLETE units (= ChronoUnit.between, signed); absolute takes the
        // magnitude; an empty operand yields an empty result (Tabel 14). 2000-01-15 -> 2023-06-20 is 23 whole
        // years and 281 whole months.
        String src = """
            Eenheidsysteem Tijd
                de maand            mnd
                het jaar            jr       =    12 mnd
            Objecttype de Klok
                de reisdatum Datum in dagen;
                de peildatum Datum in dagen;
                de jaartal Numeriek (niet-negatief geheel getal) met eenheid jr;
                de maandnr Numeriek (geheel getal) met eenheid mnd;
            Regel j
                geldig altijd
                    De jaartal van een Klok moet gesteld worden op de tijdsduur van de reisdatum van een Klok tot de peildatum van een Klok in hele jaren.
            Regel m
                geldig altijd
                    De maandnr van een Klok moet gesteld worden op de absolute tijdsduur van de peildatum van een Klok tot de reisdatum van een Klok in hele maanden.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Klok k1 = new Klok(); k1.setReisdatum(LocalDate.of(2000, 1, 15)); k1.setPeildatum(LocalDate.of(2023, 6, 20));
        Klok leeg = new Klok(); leeg.setReisdatum(LocalDate.of(2000, 1, 15));   // peildatum leeg -> result leeg
        sessie.insert(k1); sessie.insert(leeg);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("23").compareTo(k1.getJaartal()), () -> "hele jaren 2000-01-15 -> 2023-06-20 = 23, kreeg " + k1.getJaartal());
        assertEquals(0, new BigDecimal("281").compareTo(k1.getMaandnr()), () -> "absolute hele maanden = 281, kreeg " + k1.getMaandnr());
        assertNull(leeg.getJaartal(), "een lege peildatum maakt de tijdsduur leeg, niet 0");
        assertNull(leeg.getMaandnr(), "een lege peildatum maakt de absolute tijdsduur leeg, niet 0");
    }

    @Test void eerste_paasdag_berekent_paaszondag_via_de_computus() {
        // §13.4.17.37: the first day of Easter (Easter Sunday) for a year, via the Gregorian computus.
        // 2023 -> 9 apr, 2024 -> 31 mrt, 2025 -> 20 apr.
        String src = """
            Objecttype de Klok
                de jaartal Numeriek (geheel getal);
                de gemaakt Datum in dagen;
            Regel p
                geldig altijd
                    De gemaakt van een Klok moet gesteld worden op de eerste paasdag van de jaartal van een Klok.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Klok k23 = new Klok(); k23.setJaartal(new BigDecimal("2023"));
        Klok k24 = new Klok(); k24.setJaartal(new BigDecimal("2024"));
        Klok k25 = new Klok(); k25.setJaartal(new BigDecimal("2025"));
        sessie.insert(k23); sessie.insert(k24); sessie.insert(k25);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(LocalDate.of(2023, 4, 9), k23.getGemaakt(), () -> "paaszondag 2023 = 9 apr, kreeg " + k23.getGemaakt());
        assertEquals(LocalDate.of(2024, 3, 31), k24.getGemaakt(), () -> "paaszondag 2024 = 31 mrt, kreeg " + k24.getGemaakt());
        assertEquals(LocalDate.of(2025, 4, 20), k25.getGemaakt(), () -> "paaszondag 2025 = 20 apr, kreeg " + k25.getGemaakt());
    }

    @Test void conditie_zijde_builtin_paaskorting_vergelijkt_datum_met_berekende_paasdag() {
        // §13.4.17 on the CONDITION side: a scalar builtin used as a comparison operand lowers to a bare
        // pattern-constraint term (the field name, not a $subject getter). This is TOKA's toka-27 Paaskorting:
        // a vlucht is een reis met paaskorting iff its vluchtdatum IS the Easter Sunday of that date's own year.
        // Proven by firing — the right operand nests two builtins (eerste paasdag van (het jaar uit (datum))).
        String src = """
            Objecttype de Vlucht
                de reis met paaskorting kenmerk;
                de vluchtdatum Datum in dagen;
            Regel Paaskorting
                geldig altijd
                    Een Vlucht is een reis met paaskorting
                    indien de vluchtdatum van een Vlucht gelijk is aan de eerste paasdag van (het jaar uit (de vluchtdatum van een Vlucht)).
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("TODO voorwaarde"), () -> "de conditie-builtin mag geen marker meer zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht opPaaszondag = new Vlucht(); opPaaszondag.setVluchtdatum(LocalDate.of(2024, 3, 31));  // paaszondag 2024
        Vlucht andereDag   = new Vlucht(); andereDag.setVluchtdatum(LocalDate.of(2024, 1, 1));       // geen paaszondag
        Vlucht legeDatum   = new Vlucht();                                                           // §6.12: leeg -> leeg
        sessie.insert(opPaaszondag); sessie.insert(andereDag); sessie.insert(legeDatum);
        sessie.fireAllRules();   // a null vluchtdatum must NOT abort the whole session (it used to NPE in (d).getYear())
        sessie.dispose();
        assertTrue(opPaaszondag.isReisMetPaaskorting(), "vlucht op paaszondag 2024 had moeten vuren");
        assertFalse(andereDag.isReisMetPaaskorting(), "vlucht op 1 jan had niet mogen vuren");
        assertFalse(legeDatum.isReisMetPaaskorting(), "een lege vluchtdatum is geen paaskorting (§5.3 beide leeg -> fout)");
    }

    @Test void leegwaarde_verminderd_met_verschilt_van_min_en_plus_maal_tellen_leeg_als_nul() {
        // §6.2-6.4 rekentabellen. The headline: "min" and "verminderd met" are the SAME subtraction EXCEPT for
        // an empty LEFT operand — min treats it as 0 (Tabel 7: leeg min 4 = -4), verminderd met yields leeg
        // (Tabel 8: leeg verminderd met 4 = leeg). They used to lower identically (a silent-wrong). Plus/maal
        // treat any empty operand as 0 (Tabel 5/10). Proven by firing with a null grondslag.
        String src = """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de verminderdResultaat Numeriek (geheel getal);
                de minResultaat Numeriek (geheel getal);
                de plusResultaat Numeriek (geheel getal);
                de maalResultaat Numeriek (geheel getal);
            Regel rv
                geldig altijd
                    De verminderdResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom verminderd met de aftrek van een Rekensom.
            Regel rm
                geldig altijd
                    De minResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom min de aftrek van een Rekensom.
            Regel rp
                geldig altijd
                    De plusResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom plus de aftrek van een Rekensom.
            Regel rx
                geldig altijd
                    De maalResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom maal de aftrek van een Rekensom.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();

        Rekensom leegLinks = new Rekensom();   // grondslag leeg, aftrek 4
        leegLinks.setAftrek(new BigDecimal("4"));
        leegLinks.setVerminderdResultaat(new BigDecimal("999"));  // sentinels: prove the rule overwrote them
        leegLinks.setMinResultaat(new BigDecimal("999"));
        Rekensom gevuld = new Rekensom();       // 10, 4
        gevuld.setGrondslag(new BigDecimal("10")); gevuld.setAftrek(new BigDecimal("4"));
        sessie.insert(leegLinks); sessie.insert(gevuld);
        sessie.fireAllRules();
        sessie.dispose();

        // leeg LEFT: verminderd met -> leeg (null); min -> -4; plus -> 4 (leeg=0); maal -> 0 (leeg=0)
        assertNull(leegLinks.getVerminderdResultaat(), "leeg verminderd met 4 = leeg (Tabel 8)");
        assertEquals(0, new BigDecimal("-4").compareTo(leegLinks.getMinResultaat()), "leeg min 4 = -4 (Tabel 7)");
        assertEquals(0, new BigDecimal("4").compareTo(leegLinks.getPlusResultaat()), "leeg plus 4 = 4 (Tabel 5)");
        assertEquals(0, BigDecimal.ZERO.compareTo(leegLinks.getMaalResultaat()), "leeg maal 4 = 0 (Tabel 10)");
        // gevuld: all four are the ordinary arithmetic, unchanged
        assertEquals(0, new BigDecimal("6").compareTo(gevuld.getVerminderdResultaat()), "10 verminderd met 4 = 6");
        assertEquals(0, new BigDecimal("6").compareTo(gevuld.getMinResultaat()), "10 min 4 = 6");
        assertEquals(0, new BigDecimal("14").compareTo(gevuld.getPlusResultaat()), "10 plus 4 = 14");
        assertEquals(0, new BigDecimal("40").compareTo(gevuld.getMaalResultaat()), "10 maal 4 = 40");
    }

    static final String GEDEELD_SRC = """
            Objecttype de Rekensom
                de grondslag Numeriek (geheel getal);
                de aftrek Numeriek (geheel getal);
                de deelResultaat Numeriek (getal met 2 decimalen);
            Regel deel
                geldig altijd
                    De deelResultaat van een Rekensom moet gesteld worden op de grondslag van een Rekensom gedeeld door de aftrek van een Rekensom.
            """;

    @Test void leegwaarde_gedeeld_door_leeg_links_is_nul_waarde_door_leeg_is_leeg() {
        // §6.5 Tabel 12 (footnote 22): an empty LEFT short-circuits to 0 (even leeg/leeg = 0), but a non-empty
        // value over an empty divisor is a fout — lowered as leeg (null). Proven by firing the full 2x2.
        KieSession sessie = build(DrlTranspiler.transpile(Resolver.resolve(GEDEELD_SRC))).newKieSession();
        Rekensom leegLeeg = new Rekensom();                                                     // leeg / leeg -> 0
        Rekensom leegRecht = new Rekensom(); leegRecht.setAftrek(new BigDecimal("4"));           // leeg / 4    -> 0
        Rekensom waardeLeeg = new Rekensom(); waardeLeeg.setGrondslag(new BigDecimal("12"));     // 12 / leeg   -> leeg (fout)
        Rekensom beide = new Rekensom(); beide.setGrondslag(new BigDecimal("12")); beide.setAftrek(new BigDecimal("4")); // 12/4 -> 3
        for (Object o : new Object[]{leegLeeg, leegRecht, waardeLeeg, beide}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, BigDecimal.ZERO.compareTo(leegLeeg.getDeelResultaat()), "leeg / leeg = 0");
        assertEquals(0, BigDecimal.ZERO.compareTo(leegRecht.getDeelResultaat()), "leeg / 4 = 0");
        assertNull(waardeLeeg.getDeelResultaat(), "12 / leeg = leeg (de fout-waarde, niet een getal of crash)");
        assertEquals(0, new BigDecimal("3").compareTo(beide.getDeelResultaat()), "12 / 4 = 3");
    }

    @Test void gedeeld_door_leeg_meldt_een_inconsistentie_maar_leeg_door_iets_niet() {
        // §6.5: the "waarde gedeeld door leeg" fout is surfaced via the §9.5 Inconsistentie channel (a SEPARATE
        // companion rule, dividend != null && divisor == null). A leeg dividend (= 0, not a fout) and an
        // ordinary division must NOT raise one.
        String drl = DrlTranspiler.transpile(Resolver.resolve(GEDEELD_SRC));
        assertTrue(drl.contains("rule \"deel (deling door leeg)\""), () -> "geen deling-door-leeg companion-regel:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Rekensom waardeLeeg = new Rekensom(); waardeLeeg.setGrondslag(new BigDecimal("12"));     // 12 / leeg -> FOUT
        Rekensom leegRecht = new Rekensom(); leegRecht.setAftrek(new BigDecimal("4"));           // leeg / 4  -> 0, geen fout
        Rekensom beide = new Rekensom(); beide.setGrondslag(new BigDecimal("12")); beide.setAftrek(new BigDecimal("4")); // geen fout
        for (Object o : new Object[]{waardeLeeg, leegRecht, beide}) sessie.insert(o);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();
        assertEquals(1, fouten.size(), () -> "precies één Inconsistentie (alleen 12/leeg), kreeg " + fouten.size());
        assertSame(waardeLeeg, fouten.get(0), "de Inconsistentie moet de waarde/leeg-rekensom noemen");
    }

    @Test void wortel_en_macht_van_leeg_zijn_leeg_door_de_null_veilige_afronding() {
        // §6.6/§6.7: wortel/macht of a leeg operand is leeg. Both carry a MANDATORY afronding, so a leeg result
        // used to NPE in (null).setScale and ABORT the session. Now rsWortel/rsMacht return null and rsAfronding
        // passes it through. Proven by firing a null basis ALONGSIDE a filled one: the session completes, the
        // leeg basis yields leeg, the filled one computes wortel(16)=4 / 16^2=256.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de wortelr Numeriek (geheel getal);
                de machtr Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De wortelr van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 0 decimalen.
            Regel m
                geldig altijd
                    De machtr van een Heffing moet gesteld worden op de basis van een Heffing tot de macht 2 naar beneden afgerond op 0 decimalen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Heffing gevuld = new Heffing(); gevuld.setBasis(new BigDecimal("16"));
        Heffing leeg = new Heffing();                                   // basis leeg -> wortel/macht leeg
        leeg.setWortelr(new BigDecimal("999")); leeg.setMachtr(new BigDecimal("999"));  // sentinels
        sessie.insert(gevuld); sessie.insert(leeg);
        sessie.fireAllRules();   // a null basis must NOT abort the session in (null).setScale
        sessie.dispose();
        assertEquals(0, new BigDecimal("4").compareTo(gevuld.getWortelr()), () -> "wortel(16)=4, kreeg " + gevuld.getWortelr());
        assertEquals(0, new BigDecimal("256").compareTo(gevuld.getMachtr()), () -> "16^2=256, kreeg " + gevuld.getMachtr());
        assertNull(leeg.getWortelr(), "wortel van leeg = leeg");
        assertNull(leeg.getMachtr(), "macht van leeg = leeg");
    }

    @Test void wortel_van_negatief_is_leeg_en_meldt_een_inconsistentie() {
        // §6.6: a negative radicand is a Fout (RegelSpraak has no complex numbers). The value degrades to leeg
        // (rsWortel returns null for a negative — no session-aborting sqrt throw), and a companion rule surfaces
        // the §6.6 foutmelding as an Inconsistentie. A leeg radicand is leeg WITHOUT a fout; a positive one
        // computes normally.
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de wortelr Numeriek (geheel getal);
            Regel w
                geldig altijd
                    De wortelr van een Heffing moet gesteld worden op de wortel van de basis van een Heffing naar beneden afgerond op 0 decimalen.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("rule \"w (wortel van negatief)\""), () -> "geen wortel-negatief companion-regel:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Heffing neg = new Heffing(); neg.setBasis(new BigDecimal("-4")); neg.setWortelr(new BigDecimal("999"));  // -> leeg + fout
        Heffing pos = new Heffing(); pos.setBasis(new BigDecimal("16"));                                          // -> 4, geen fout
        Heffing leeg = new Heffing(); leeg.setWortelr(new BigDecimal("999"));                                     // -> leeg, geen fout
        for (Object o : new Object[]{neg, pos, leeg}) sessie.insert(o);
        sessie.fireAllRules();   // a negative radicand must NOT abort the session (sqrt(-4) used to throw)
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();
        assertEquals(1, fouten.size(), () -> "precies één Inconsistentie (alleen de negatieve), kreeg " + fouten.size());
        assertSame(neg, fouten.get(0), "de Inconsistentie moet de negatieve heffing noemen");
        assertNull(neg.getWortelr(), "wortel van een negatief getal = leeg (geen crash)");
        assertEquals(0, new BigDecimal("4").compareTo(pos.getWortelr()), () -> "wortel(16)=4, kreeg " + pos.getWortelr());
        assertNull(leeg.getWortelr(), "wortel van leeg = leeg (geen fout)");
    }

    @Test void conditie_zijde_wortel_onder_zijn_verplichte_afronding() {
        // §13.4.17.13: "de wortel van X" carries a MANDATORY afronding, so a condition-side wortel is always
        // Afgerond(Functie(WORTEL)) — operand() must descend the afronding wrapper (a pure setScale, valid in a
        // pattern constraint) to reach the builtin, else the rule no-ops. saldo 16 -> wortel 4 (afgerond) -> fires.
        String src = """
            Objecttype de Klok
                de saldo Numeriek (getal met 2 decimalen);
                is wortel vier kenmerk (bijvoeglijk);
            Regel w
                geldig altijd
                    Een Klok is wortel vier
                    indien de wortel van de saldo van een Klok naar beneden afgerond op 0 decimalen gelijk is aan 4.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("TODO voorwaarde"), () -> "de wortel-in-voorwaarde mag geen marker zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Klok zestien = new Klok(); zestien.setSaldo(new BigDecimal("16"));   // sqrt 4 -> fires
        Klok tien    = new Klok(); tien.setSaldo(new BigDecimal("10"));      // sqrt 3.16 -> afgerond 3 -> niet
        sessie.insert(zestien); sessie.insert(tien);
        sessie.fireAllRules();
        sessie.dispose();
        assertTrue(zestien.isWortelVier(), "wortel(16)=4 had moeten vuren");
        assertFalse(tien.isWortelVier(), "wortel(10)~3 had niet mogen vuren");
    }

    @Test void conditie_zijde_builtin_in_kwantor_vergelijkt_numeriek_gelijk() {
        // toka-26 Hoogseizoen: a condition-side builtin (maand uit) inside a "ten minste één" quantifier, each
        // criterion a NUMERIC == against an integer literal. This is the first firing BigDecimal == BigDecimal
        // in the corpus, so prove mvel's == on two scale-0 BigDecimals does value-equality (not a type/scale
        // miss): month 7 -> hoogseizoen, month 1 -> not.
        String src = """
            Objecttype de Vlucht
                is in het hoogseizoen kenmerk (bijvoeglijk);
                de vluchtdatum Datum in dagen;
            Regel Hoogseizoen
                geldig altijd
                    Een Vlucht is in het hoogseizoen
                    indien er aan ten minste één van de volgende voorwaarden wordt voldaan:
                    - de maand uit (de vluchtdatum van de Vlucht) is gelijk aan 6
                    - de maand uit (de vluchtdatum van de Vlucht) is gelijk aan 7
                    - de maand uit (de vluchtdatum van de Vlucht) is gelijk aan 8.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("TODO voorwaarde"), () -> "de kwantor-builtins mogen geen marker zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht juli = new Vlucht();    juli.setVluchtdatum(LocalDate.of(2024, 7, 15));   // maand 7 -> hoogseizoen
        Vlucht januari = new Vlucht(); januari.setVluchtdatum(LocalDate.of(2024, 1, 15)); // maand 1 -> niet
        Vlucht legeDatum = new Vlucht();                                                  // §6.12: maand uit leeg -> leeg
        sessie.insert(juli); sessie.insert(januari); sessie.insert(legeDatum);
        sessie.fireAllRules();   // a null vluchtdatum must NOT abort the session (it used to NPE in (d).getMonthValue())
        sessie.dispose();
        assertFalse(legeDatum.isInHetHoogseizoen(), "een lege vluchtdatum is geen hoogseizoen (maand uit leeg -> leeg)");
        assertTrue(juli.isInHetHoogseizoen(), "een vlucht in juli (maand 7) is hoogseizoen");
        assertFalse(januari.isInHetHoogseizoen(), "een vlucht in januari (maand 1) is geen hoogseizoen");
    }

    @Test void telling_maal_parameter_bindt_de_maal_buiten_de_telling() {
        // §13.4.17.47 telling precedence: "het aantal X van de reis maal de factor" must be
        // (count of X) maal factor, NOT count(X maal factor). The telling takes a
        // primaireExpressie operand, so the maal binds outside. Proven by firing: 2 passengers
        // maal 10 = 20 (a count-of-(passenger-maal-10) would not even resolve).
        String src = """
            Parameter de factor : Numeriek (geheel getal)
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis maal de factor.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("(rsNul($acc1) * rsNul(factor))"), () -> "de maal moet buiten de telling binden:\n" + drl);
        assertFalse(drl.contains("TODO aggregatie"), () -> "de telling mag de maal niet opslokken:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        sessie.setGlobal("factor", new BigDecimal("10"));
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v);
        Passagier b = new Passagier(); b.setReis(v);
        Vlucht ander = new Vlucht();                              // a flight with no passengers, to prove the join
        sessie.insert(v); sessie.insert(ander); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("20").compareTo(v.getTotaalprijs()),
                () -> "2 passagiers maal 10 = 20, kreeg " + v.getTotaalprijs());
    }

    @Test void het_aantal_gevolgd_door_parameternaam_is_de_parameter_niet_een_telling() {
        // A parameter whose name begins with "aantal" ("aantal punten per passagier"), written
        // "het aantal punten per passagier", must resolve to the PARAMETER, not be grabbed by the
        // telling rule as count(punten per passagier). Mirrors TOKA's contingent-treinmiles
        // parameter. Proven by firing: 2 passengers maal 10 = 20.
        String src = """
            Parameter de aantal punten per passagier : Numeriek (geheel getal)
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis maal het aantal punten per passagier.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("(rsNul($acc1) * rsNul(aantalPuntenPerPassagier))"),
                () -> "de aantal-parameter moet als parameter binden:\n" + drl);
        assertFalse(drl.contains("TODO aggregatie"), () -> "de parameternaam mag geen geneste telling worden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        sessie.setGlobal("aantalPuntenPerPassagier", new BigDecimal("10"));
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v);
        Passagier b = new Passagier(); b.setReis(v);
        sessie.insert(v); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("20").compareTo(v.getTotaalprijs()),
                () -> "2 passagiers maal 10 = 20, kreeg " + v.getTotaalprijs());
    }

    @Test void objectcreatie_voegt_een_gekoppeld_feit_toe() {
        String src = """
            Objecttype de Bestelling
                de waarde Numeriek (geheel getal);
            Objecttype de Factuur
                de bedrag Numeriek (geheel getal);
            Feittype Bestelling met factuur
                de bestelling Bestelling
                de factuur Factuur
            Eén bestelling heeft één factuur
            Regel maak factuur
                geldig altijd
                    Een Bestelling heeft een factuur met bedrag gelijk aan de waarde van een Bestelling.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("insert($new)"), () -> "geen insert:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Bestelling b = new Bestelling();
        b.setWaarde(new BigDecimal("50"));
        sessie.insert(b);
        sessie.fireAllRules();
        var facturen = sessie.getObjects().stream().filter(o -> o instanceof Factuur).map(o -> (Factuur) o).toList();
        sessie.dispose();

        assertEquals(1, facturen.size(), "objectcreatie maakte geen Factuur");
        Factuur f = facturen.get(0);
        assertEquals(0, new BigDecimal("50").compareTo(f.getBedrag()), () -> "bedrag: " + f.getBedrag());
        assertSame(b, f.getBestelling(), "terugkoppeling naar de bestelling ontbreekt");
    }

    @Test void verdeling_in_gelijke_delen_geeft_ieder_een_gelijk_aandeel() {
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("count($c)"), () -> "geen accumulate-count:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("90"));
        Deelnemer d1 = new Deelnemer(); d1.setPot(p);
        Deelnemer d2 = new Deelnemer(); d2.setPot(p);
        Deelnemer d3 = new Deelnemer(); d3.setPot(p);
        sessie.insert(p); sessie.insert(d1); sessie.insert(d2); sessie.insert(d3);
        sessie.fireAllRules();
        sessie.dispose();

        for (Deelnemer d : new Deelnemer[]{d1, d2, d3})
            assertEquals(0, new BigDecimal("30").compareTo(d.getAandeel()),
                    () -> "ieder aandeel moet 90/3 = 30 zijn, kreeg " + d.getAandeel());
    }

    static final String VERDELING_NAAR_RATO_SRC = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel naar rato
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld naar rato van de gewicht.
            """;

    @Test void verdeling_naar_rato_weegt_ieder_aandeel_met_zijn_gewicht() {
        // §13.4.10 naar rato: each recipient gets total · weight / sum(weight). totaal 120 over weights
        // 1/2/3 (sum 6) -> 20/40/60. A weightless recipient is excluded from the sum and left unassigned —
        // never a divide-by-null crash. (A spec-faithful Fout on an empty weight is a documented divergence;
        // see docs/grammar-divergences.md and the backlog.)
        RegelSpraakModel m = Resolver.resolve(VERDELING_NAAR_RATO_SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("sum($c.getGewicht())"), () -> "geen gewogen accumulate-som:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("120"));
        Deelnemer d1 = new Deelnemer(); d1.setPot(p); d1.setGewicht(new BigDecimal("1"));
        Deelnemer d2 = new Deelnemer(); d2.setPot(p); d2.setGewicht(new BigDecimal("2"));
        Deelnemer d3 = new Deelnemer(); d3.setPot(p); d3.setGewicht(new BigDecimal("3"));
        Deelnemer dx = new Deelnemer(); dx.setPot(p);   // weightless: excluded, not assigned, no NPE
        sessie.insert(p); sessie.insert(d1); sessie.insert(d2); sessie.insert(d3); sessie.insert(dx);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("20").compareTo(d1.getAandeel()), () -> "gewicht 1 -> 20, kreeg " + d1.getAandeel());
        assertEquals(0, new BigDecimal("40").compareTo(d2.getAandeel()), () -> "gewicht 2 -> 40, kreeg " + d2.getAandeel());
        assertEquals(0, new BigDecimal("60").compareTo(d3.getAandeel()), () -> "gewicht 3 -> 60, kreeg " + d3.getAandeel());
        assertNull(dx.getAandeel(), "een gewichtloze deelnemer mag geen aandeel krijgen");
    }

    @Test void verdeling_naar_rato_met_leeg_gewicht_meldt_een_inconsistentie() {
        // §9.7.6 Tabel 19: an empty weight on ANY recipient makes the verdeling a Fout — now surfaced as an
        // Inconsistentie (the §9.5 channel). The Inconsistentie rule is SEPARATE from the distribution (a `not`/
        // exists added to the modify rule cancels sibling recipient activations under no-loop), so the weighted
        // recipients must STILL get their shares: this fires both and checks they coexist. A fully-weighted pot
        // produces NO Inconsistentie (the `exists weight==null` guard discriminates per bron).
        String drl = DrlTranspiler.transpile(Resolver.resolve(VERDELING_NAAR_RATO_SRC));
        assertTrue(drl.contains("(leeg gewicht)"), () -> "geen lege-gewicht Inconsistentie-regel:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot fout = new Pot(); fout.setTotaal(new BigDecimal("120"));         // has a weightless recipient -> Fout
        Deelnemer a1 = new Deelnemer(); a1.setPot(fout); a1.setGewicht(new BigDecimal("1"));
        Deelnemer a2 = new Deelnemer(); a2.setPot(fout); a2.setGewicht(new BigDecimal("2"));
        Deelnemer ax = new Deelnemer(); ax.setPot(fout);                     // weightless
        Pot goed = new Pot(); goed.setTotaal(new BigDecimal("100"));         // all weighted -> no Fout
        Deelnemer b1 = new Deelnemer(); b1.setPot(goed); b1.setGewicht(new BigDecimal("1"));
        Deelnemer b2 = new Deelnemer(); b2.setPot(goed); b2.setGewicht(new BigDecimal("1"));
        for (Object o : new Object[]{fout, a1, a2, ax, goed, b1, b2}) sessie.insert(o);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertEquals(1, fouten.size(), () -> "precies één Inconsistentie verwacht (alleen de fout-pot), kreeg " + fouten.size());
        assertSame(fout, fouten.get(0), "de Inconsistentie moet de fout-pot noemen, niet de goede");
        // the separate rule did NOT cancel the distribution: weighted recipients of the fout-pot still share (sum 3)
        assertEquals(0, new BigDecimal("40").compareTo(a1.getAandeel()), () -> "120·1/3 = 40, kreeg " + a1.getAandeel());
        assertEquals(0, new BigDecimal("80").compareTo(a2.getAandeel()), () -> "120·2/3 = 80, kreeg " + a2.getAandeel());
        assertNull(ax.getAandeel(), "de gewichtloze deelnemer krijgt geen aandeel");
        // the good pot distributed normally (sum 2 -> 50/50)
        assertEquals(0, new BigDecimal("50").compareTo(b1.getAandeel()), () -> "100·1/2 = 50, kreeg " + b1.getAandeel());
    }

    static final String VERDELING_REST_SRC = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel met afronding
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - afgerond op 0 decimalen naar beneden
                    Als onverdeelde rest blijft de restant van een pot over.
            """;

    @Test void verdeling_naar_rato_met_afronding_floort_de_aandelen_en_houdt_de_rest() {
        // §9.7.4/§9.7.5 worked example (toka-36): 1001 treinmiles naar rato van 3:2 -> 600,6 and 400,4,
        // floored "naar beneden" to 600 and 400; the 1 left over is the onverdeelde rest, kept by the pot.
        // A second pot proves per-source isolation and a remainder of exactly 0 (no rounding loss).
        RegelSpraakModel m = Resolver.resolve(VERDELING_REST_SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("java.math.RoundingMode.FLOOR"), () -> "aandeel niet naar beneden afgerond:\n" + drl);
        assertTrue(drl.contains("(onverdeelde rest)"), () -> "geen onverdeelde-rest regel:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1001"));
        Deelnemer tom = new Deelnemer(); tom.setPot(p); tom.setGewicht(new BigDecimal("3"));
        Deelnemer maria = new Deelnemer(); maria.setPot(p); maria.setGewicht(new BigDecimal("2"));
        Pot q = new Pot(); q.setTotaal(new BigDecimal("120"));
        Deelnemer q1 = new Deelnemer(); q1.setPot(q); q1.setGewicht(new BigDecimal("1"));
        Deelnemer q2 = new Deelnemer(); q2.setPot(q); q2.setGewicht(new BigDecimal("2"));
        for (Object o : new Object[]{p, tom, maria, q, q1, q2}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("600").compareTo(tom.getAandeel()), () -> "floor(1001·3/5)=600, kreeg " + tom.getAandeel());
        assertEquals(0, new BigDecimal("400").compareTo(maria.getAandeel()), () -> "floor(1001·2/5)=400, kreeg " + maria.getAandeel());
        assertEquals(0, new BigDecimal("1").compareTo(p.getRestant()), () -> "onverdeelde rest 1001-600-400 = 1, kreeg " + p.getRestant());
        // per-source isolation + exact remainder 0
        assertEquals(0, new BigDecimal("40").compareTo(q1.getAandeel()), () -> "floor(120·1/3)=40, kreeg " + q1.getAandeel());
        assertEquals(0, new BigDecimal("80").compareTo(q2.getAandeel()), () -> "floor(120·2/3)=80, kreeg " + q2.getAandeel());
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getRestant()), () -> "rest van q = 0, kreeg " + q.getRestant());
    }

    @Test void verdeling_onverdeelde_rest_corrigeert_zich_bij_incrementeel_arriverende_deelnemers() {
        // §9.4/§9.7.5: recipients are often RULE-DERIVED (toka-29 "passagier met recht op treinmiles"), so they
        // appear incrementally. The onverdeelde rest must reflect the FINAL distribution, not freeze at the first
        // complete-LOOKING set: when only tom is present he gets the whole pot (floor(1001·3/3)=1001), and a
        // fire-once rest would lock in 1001-1001=0. The rest rule is recomputable, so once maria arrives and the
        // shares recompute to 600/400 the rest self-corrects to 1. Staged inserts model the incremental arrival.
        String drl = DrlTranspiler.transpile(Resolver.resolve(VERDELING_REST_SRC));
        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1001"));
        Deelnemer tom = new Deelnemer(); tom.setPot(p); tom.setGewicht(new BigDecimal("3"));
        sessie.insert(p); sessie.insert(tom);
        sessie.fireAllRules();                                  // tom alone -> 1001; a frozen rest would be 0
        Deelnemer maria = new Deelnemer(); maria.setPot(p); maria.setGewicht(new BigDecimal("2"));
        sessie.insert(maria);
        sessie.fireAllRules();                                  // now 600/400 -> the rest must self-correct to 1
        sessie.dispose();

        assertEquals(0, new BigDecimal("600").compareTo(tom.getAandeel()), () -> "tom 600, kreeg " + tom.getAandeel());
        assertEquals(0, new BigDecimal("400").compareTo(maria.getAandeel()), () -> "maria 400, kreeg " + maria.getAandeel());
        assertEquals(0, new BigDecimal("1").compareTo(p.getRestant()),
                () -> "de onverdeelde rest moet zich naar 1 corrigeren, niet op 0 blijven staan, kreeg " + p.getRestant());
    }

    static final String VERDELING_MAX_SRC = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel met maximum
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - naar rato van de gewicht,
                    - met een maximum van de maximum.
                    Als onverdeelde rest blijft de restant van een pot over.
            """;

    @Test void verdeling_naar_rato_met_maximumaanspraak_capt_de_aandelen_en_houdt_de_rest() {
        // §9.7.3 worked example: 1200 over woonregio factors 3:1:2 (Σ=6), max 300 per recipient. Wilma 3/6·1200=600
        // -> capped to 300; Hans 1/6·1200=200 (under the max); Diederik 2/6·1200=400 -> capped to 300. The capped
        // surplus is NOT redistributed — the 1200-300-200-300 = 400 left over is the onverdeelde rest.
        RegelSpraakModel m = Resolver.resolve(VERDELING_MAX_SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rsPlafond("), () -> "aandeel niet op een maximum gecapt:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1200"));
        Deelnemer wilma = new Deelnemer(); wilma.setPot(p); wilma.setGewicht(new BigDecimal("3")); wilma.setMaximum(new BigDecimal("300"));
        Deelnemer hans = new Deelnemer(); hans.setPot(p); hans.setGewicht(new BigDecimal("1")); hans.setMaximum(new BigDecimal("300"));
        Deelnemer diederik = new Deelnemer(); diederik.setPot(p); diederik.setGewicht(new BigDecimal("2")); diederik.setMaximum(new BigDecimal("300"));
        for (Object o : new Object[]{p, wilma, hans, diederik}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("300").compareTo(wilma.getAandeel()), () -> "wilma 600 -> cap 300, kreeg " + wilma.getAandeel());
        assertEquals(0, new BigDecimal("200").compareTo(hans.getAandeel()), () -> "hans 200 (onder max), kreeg " + hans.getAandeel());
        assertEquals(0, new BigDecimal("300").compareTo(diederik.getAandeel()), () -> "diederik 400 -> cap 300, kreeg " + diederik.getAandeel());
        assertEquals(0, new BigDecimal("400").compareTo(p.getRestant()), () -> "onverdeelde rest 1200-300-200-300 = 400, kreeg " + p.getRestant());
    }

    @Test void verdeling_max_met_leeg_of_negatief_maximum_meldt_een_inconsistentie_en_degradeert() {
        // §9.7.6 Tabel 19: a leeg or NEGATIVE maximum at any recipient makes the whole verdeling a Fout. Like the
        // empty weight, the Fout is surfaced as a SEPARATE Inconsistentie (one per fout-pot), while the value side
        // degrades the bad cap to UNCAPPED — never a negative share (which would be silently wrong). A pot whose
        // maxima are all valid raises no Inconsistentie.
        String drl = DrlTranspiler.transpile(Resolver.resolve(VERDELING_MAX_SRC));
        assertTrue(drl.contains("(leeg of negatief maximum)"), () -> "geen leeg/negatief-maximum Inconsistentie-regel:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot fout = new Pot(); fout.setTotaal(new BigDecimal("1200"));
        Deelnemer leeg = new Deelnemer(); leeg.setPot(fout); leeg.setGewicht(new BigDecimal("3"));            // maximum == null
        Deelnemer neg = new Deelnemer(); neg.setPot(fout); neg.setGewicht(new BigDecimal("2")); neg.setMaximum(new BigDecimal("-5"));
        Deelnemer ok = new Deelnemer(); ok.setPot(fout); ok.setGewicht(new BigDecimal("1")); ok.setMaximum(new BigDecimal("300"));
        Pot goed = new Pot(); goed.setTotaal(new BigDecimal("120"));
        Deelnemer g1 = new Deelnemer(); g1.setPot(goed); g1.setGewicht(new BigDecimal("1")); g1.setMaximum(new BigDecimal("300"));
        for (Object o : new Object[]{fout, leeg, neg, ok, goed, g1}) sessie.insert(o);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertEquals(1, fouten.size(), () -> "precies één Inconsistentie verwacht (alleen de fout-pot), kreeg " + fouten.size());
        assertSame(fout, fouten.get(0), "de Inconsistentie moet de fout-pot noemen");
        // the bad caps degrade to UNCAPPED (positive shares, never negative): leeg 3/6·1200=600, neg 2/6·1200=400
        assertEquals(0, new BigDecimal("600").compareTo(leeg.getAandeel()), () -> "leeg maximum -> uncapped 600, kreeg " + leeg.getAandeel());
        assertEquals(0, new BigDecimal("400").compareTo(neg.getAandeel()), () -> "negatief maximum -> uncapped 400 (niet negatief), kreeg " + neg.getAandeel());
        assertEquals(0, new BigDecimal("200").compareTo(ok.getAandeel()), () -> "geldig 1/6·1200=200 (< max 300), kreeg " + ok.getAandeel());
        assertEquals(1, fouten.size());   // the good pot raised nothing (already asserted by the count)
    }

    static final String VERDELING_OP_VOLGORDE_SRC = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel op volgorde
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht.
                    Als onverdeelde rest blijft de restant van een pot over.
            """;

    @Test void verdeling_op_volgorde_geeft_de_eerste_groep_alles_zonder_maximum() {
        // §9.7.2 worked example: 1200 op volgorde van toenemende leeftijd, tie naar rato van gewicht, no maximum.
        // The youngest group {Wilma(20,w3), Hans(20,w1)} splits the WHOLE pot (Σ=4 -> 900/300), exhausting it, so
        // Diederik(22) gets 0 and the onverdeelde rest is 0. This pins the group-entry-pot reading: Hans is 1/4·1200
        // = 300, NOT 1/4 of a pot already depleted by Wilma. It also empirically verifies the three index-aligned
        // collectList lists stay aligned and that "Map() from <helper>" binds the share map once.
        RegelSpraakModel m = Resolver.resolve(VERDELING_OP_VOLGORDE_SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("from rsVerdeelOpVolgorde("), () -> "geen op-volgorde allocatie:\n" + drl);
        assertTrue(drl.contains("collectList($p.getLeeftijd())"), () -> "ordeningscriterium niet verzameld:\n" + drl);
        assertTrue(drl.contains("$facts, $groep, true,"), () -> "richting toenemend niet gelowerd:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1200"));
        Deelnemer wilma = new Deelnemer(); wilma.setPot(p); wilma.setLeeftijd(new BigDecimal("20")); wilma.setGewicht(new BigDecimal("3"));
        Deelnemer hans = new Deelnemer(); hans.setPot(p); hans.setLeeftijd(new BigDecimal("20")); hans.setGewicht(new BigDecimal("1"));
        Deelnemer diederik = new Deelnemer(); diederik.setPot(p); diederik.setLeeftijd(new BigDecimal("22")); diederik.setGewicht(new BigDecimal("2"));
        for (Object o : new Object[]{p, wilma, hans, diederik}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("900").compareTo(wilma.getAandeel()), () -> "wilma 3/4·1200 = 900, kreeg " + wilma.getAandeel());
        assertEquals(0, new BigDecimal("300").compareTo(hans.getAandeel()), () -> "hans 1/4·1200 = 300 (van dezelfde pot), kreeg " + hans.getAandeel());
        assertEquals(0, BigDecimal.ZERO.compareTo(diederik.getAandeel()), () -> "diederik (oudere groep) krijgt 0, kreeg " + diederik.getAandeel());
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getRestant()), () -> "onverdeelde rest 1200-1200 = 0, kreeg " + p.getRestant());
    }

    static final String VERDELING_OP_VOLGORDE_MAX_SRC = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
                de restant Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
                de gewicht Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
                de maximum Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel op volgorde met maximum en afronding
                geldig altijd
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld:
                    - op volgorde van toenemende de leeftijd,
                    - bij een even groot criterium naar rato van de gewicht,
                    - met een maximum van de maximum,
                    - afgerond op 0 decimalen naar beneden.
                    Als onverdeelde rest blijft de restant van een pot over.
            """;

    @Test void verdeling_op_volgorde_met_maximum_en_afronding_reproduceert_tabel_18() {
        // §9.7.5 worked example (Tabel 18): 1800 op volgorde van toenemende leeftijd, tie naar rato van gewicht,
        // max per recipient, floor 0 decimals. The cap+floor are applied INSIDE the helper so the remainder carries
        // group by group: Piet(22) alone -> 1800 capped 500 (pot 1300); Jan/Klaas(39) split 1300 (Jan 2/3·1300=866
        // capped 500, Klaas 1/3·1300=433.33 floored 433; pot 367); Nel/Ria(66) split 367 (Nel 3/4·367=275.25 capped
        // 250, Ria 1/4·367=91.75 floored 91). Shares 500/500/433/250/91, onverdeelde rest 1800-1774 = 26. This is
        // the regression that catches a post-hoc clamp against the full plafond (which would compute each group off
        // 1800, not the carried-down pot).
        RegelSpraakModel m = Resolver.resolve(VERDELING_OP_VOLGORDE_MAX_SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("from rsVerdeelOpVolgorde("), () -> "geen op-volgorde allocatie:\n" + drl);
        assertTrue(drl.contains("$gewicht, $max,"), () -> "maximum niet meegegeven aan de allocatie:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1800"));
        Deelnemer piet = deelnemer(p, "22", "3", "500");
        Deelnemer jan = deelnemer(p, "39", "2", "500");
        Deelnemer klaas = deelnemer(p, "39", "1", "500");
        Deelnemer nel = deelnemer(p, "66", "3", "250");
        Deelnemer ria = deelnemer(p, "66", "1", "250");
        for (Object o : new Object[]{p, piet, jan, klaas, nel, ria}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("500").compareTo(piet.getAandeel()), () -> "piet 1800 -> cap 500, kreeg " + piet.getAandeel());
        assertEquals(0, new BigDecimal("500").compareTo(jan.getAandeel()), () -> "jan 2/3·1300=866 -> cap 500, kreeg " + jan.getAandeel());
        assertEquals(0, new BigDecimal("433").compareTo(klaas.getAandeel()), () -> "klaas 1/3·1300=433.33 -> floor 433, kreeg " + klaas.getAandeel());
        assertEquals(0, new BigDecimal("250").compareTo(nel.getAandeel()), () -> "nel 3/4·367=275.25 -> cap 250, kreeg " + nel.getAandeel());
        assertEquals(0, new BigDecimal("91").compareTo(ria.getAandeel()), () -> "ria 1/4·367=91.75 -> floor 91, kreeg " + ria.getAandeel());
        assertEquals(0, new BigDecimal("26").compareTo(p.getRestant()), () -> "onverdeelde rest 1800-1774 = 26 (Tabel 18), kreeg " + p.getRestant());
    }

    @Test void verdeling_op_volgorde_afnemend_keert_de_groepsvolgorde_om() {
        // §9.7.2 with AFNEMENDE: the OLDEST group is served first. Diederik(22) alone takes the whole 1200 (his
        // group's tie weight is irrelevant for a singleton), exhausting the pot, so Wilma/Hans(20) get 0. This pins
        // that the sort direction flips the depletion order (the share map keys off the reversed group order).
        RegelSpraakModel m = Resolver.resolve(VERDELING_OP_VOLGORDE_SRC.replace("toenemende de leeftijd", "afnemende de leeftijd"));
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$facts, $groep, false,"), () -> "richting afnemend niet gelowerd:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1200"));
        Deelnemer wilma = new Deelnemer(); wilma.setPot(p); wilma.setLeeftijd(new BigDecimal("20")); wilma.setGewicht(new BigDecimal("3"));
        Deelnemer hans = new Deelnemer(); hans.setPot(p); hans.setLeeftijd(new BigDecimal("20")); hans.setGewicht(new BigDecimal("1"));
        Deelnemer diederik = new Deelnemer(); diederik.setPot(p); diederik.setLeeftijd(new BigDecimal("22")); diederik.setGewicht(new BigDecimal("2"));
        for (Object o : new Object[]{p, wilma, hans, diederik}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("1200").compareTo(diederik.getAandeel()), () -> "afnemend: diederik (22) eerst, neemt alles, kreeg " + diederik.getAandeel());
        assertEquals(0, BigDecimal.ZERO.compareTo(wilma.getAandeel()), () -> "wilma (20) krijgt 0, kreeg " + wilma.getAandeel());
        assertEquals(0, BigDecimal.ZERO.compareTo(hans.getAandeel()), () -> "hans (20) krijgt 0, kreeg " + hans.getAandeel());
    }

    @Test void verdeling_op_volgorde_negatief_gewicht_overschrijdt_het_plafond_niet() {
        // A negative tie weight has no proportional meaning (a "factor" cannot be negative). The helper clamps each
        // weight to 0, so a negative-weight member claims nothing AND cannot shrink the weight-sum to inflate the
        // others past the pot — which would give Σ(shares) > plafond and a NEGATIVE onverdeelde rest, a state the
        // §9.7 model cannot represent. One age group: A(gewicht -1) -> 0, B(gewicht 3) -> the full 1200 (Σw clamps
        // to 3), rest exactly 0 (never -600). Regression for the review's negative-rest finding.
        String drl = DrlTranspiler.transpile(Resolver.resolve(VERDELING_OP_VOLGORDE_SRC));
        KieSession sessie = build(drl).newKieSession();
        Pot p = new Pot(); p.setTotaal(new BigDecimal("1200"));
        Deelnemer a = new Deelnemer(); a.setPot(p); a.setLeeftijd(new BigDecimal("20")); a.setGewicht(new BigDecimal("-1"));
        Deelnemer b = new Deelnemer(); b.setPot(p); b.setLeeftijd(new BigDecimal("20")); b.setGewicht(new BigDecimal("3"));
        for (Object o : new Object[]{p, a, b}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, BigDecimal.ZERO.compareTo(a.getAandeel()), () -> "negatief gewicht -> geen aandeel, kreeg " + a.getAandeel());
        assertEquals(0, new BigDecimal("1200").compareTo(b.getAandeel()), () -> "b krijgt het hele plafond (Σw=3), kreeg " + b.getAandeel());
        assertTrue(p.getRestant().signum() >= 0, () -> "de onverdeelde rest mag nooit negatief zijn, kreeg " + p.getRestant());
        assertEquals(0, BigDecimal.ZERO.compareTo(p.getRestant()), () -> "rest 1200-1200 = 0, kreeg " + p.getRestant());
    }

    private static Deelnemer deelnemer(Pot p, String leeftijd, String gewicht, String maximum) {
        Deelnemer d = new Deelnemer();
        d.setPot(p);
        d.setLeeftijd(new BigDecimal(leeftijd));
        d.setGewicht(new BigDecimal(gewicht));
        d.setMaximum(new BigDecimal(maximum));
        return d;
    }

    @Test void cross_unit_optelling_wordt_omgerekend_niet_rauw_geteld() {
        // duur(mnd) := looptijd(jr) + duur(mnd). looptijd must be scaled jr->mnd (×12),
        // NOT added raw: 2 jr + 3 mnd = 27 mnd, not 5.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Periode
                de duur Numeriek (geheel getal) met eenheid mnd;
                de looptijd Numeriek (geheel getal) met eenheid jr;
            Regel tel
                geldig altijd
                    De duur van een Periode moet gesteld worden op de duur van een Periode plus de looptijd van een Periode.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Periode p = new Periode();
        p.setLooptijd(new BigDecimal("2"));   // 2 years
        p.setDuur(new BigDecimal("3"));       // 3 months
        sessie.insert(p);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("27").compareTo(p.getDuur()),
                () -> "2 jr + 3 mnd moet 27 mnd zijn (niet 5), kreeg " + p.getDuur());
    }

    @Test void cross_unit_vergelijking_wordt_omgerekend_niet_rauw_vergeleken() {
        // "duurmnd (mnd) groter dan grensjr (jr)": 2 jr is 24 mnd. 25 mnd > 24 mnd is
        // true; 20 mnd > 24 mnd is false. A raw "25 > 2 / 20 > 2" would wrongly say both.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Meting
                de duurmnd Numeriek (geheel getal) met eenheid mnd;
                de grensjr Numeriek (geheel getal) met eenheid jr;
                is oud kenmerk (bijvoeglijk);
            Regel oud
                geldig altijd
                    Een Meting is oud
                    indien de duurmnd van een Meting groter is dan de grensjr van een Meting.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        KieSession sessie = build(drl).newKieSession();
        Meting boven = new Meting(); boven.setDuurmnd(new BigDecimal("25")); boven.setGrensjr(new BigDecimal("2"));
        Meting onder = new Meting(); onder.setDuurmnd(new BigDecimal("20")); onder.setGrensjr(new BigDecimal("2"));
        sessie.insert(boven); sessie.insert(onder);
        sessie.fireAllRules();
        sessie.dispose();

        assertTrue(boven.isOud(), "25 mnd > 2 jr (24 mnd) moet waar zijn");
        assertFalse(onder.isOud(), "20 mnd > 2 jr (24 mnd) moet onwaar zijn (niet rauw 20>2)");
    }

    @Test void cross_unit_literal_grens_compileert_en_vuurt() {
        // The bound is a bare unit LITERAL "2 jr" (not an attribute). It must be scaled
        // to mnd and — crucially — emitted as a BigDecimal so (2).multiply does not
        // fail to compile in mvel.
        String src = """
            Eenheidsysteem Tijd
                de maand    mnd
                het jaar    jr    =    12 mnd
            Objecttype de Meting
                de duurmnd Numeriek (geheel getal) met eenheid mnd;
                is oud kenmerk (bijvoeglijk);
            Regel oud
                geldig altijd
                    Een Meting is oud
                    indien de duurmnd van een Meting groter is dan 2 jr.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        KieSession sessie = build(drl).newKieSession();      // build() asserts it COMPILES
        Meting boven = new Meting(); boven.setDuurmnd(new BigDecimal("25"));
        Meting onder = new Meting(); onder.setDuurmnd(new BigDecimal("20"));
        sessie.insert(boven); sessie.insert(onder);
        sessie.fireAllRules();
        sessie.dispose();

        assertTrue(boven.isOud(), "25 mnd > 2 jr (24) moet waar zijn");
        assertFalse(onder.isOud(), "20 mnd > 2 jr (24) moet onwaar zijn");
    }

    @Test void afwezig_attribuut_op_doeltype_wordt_marker_in_de_waarde() {
        // p7b: a navigation reads an attribute the target type does not declare (Luchthaven
        // has afstand, not rang). Emitting $subject.getBestemming().getRang() would call a
        // method the fact has no declaration for -> uncompilable DRL. The transpiler must
        // emit a /* TODO navigatie */ marker instead, which compiles and leaves the field unset.
        String src = """
            Objecttype de Reiziger
                de doelrang Numeriek (geheel getal);
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel rang overnemen
                geldig altijd
                    De doelrang van een Reiziger moet gesteld worden op de rang van de bestemming.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("TODO navigatie"), () -> "afwezig attribuut moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("getRang()"), () -> "mag geen getter voor een afwezig attribuut emitteren:\n" + drl);

        KieSession sessie = build(drl).newKieSession();   // build() asserts the DRL COMPILES
        Reiziger r = new Reiziger(); r.setBestemming(new Luchthaven());
        sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();
        assertNull(r.getDoelrang(), () -> "doelrang moet leeg blijven, kreeg " + r.getDoelrang());
    }

    @Test void afwezig_attribuut_in_een_voorwaarde_compileert_en_vuurt_niet() {
        // p7b, the harder position: the absent attribute is a CONDITION operand. A broken
        // getter in a pattern constraint fails to compile and would sink the whole package;
        // the marker must keep the DRL compiling, and the un-evaluable condition must not fire.
        String src = """
            Objecttype de Reiziger
                de doelrang Numeriek (geheel getal);
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel rang conditie
                geldig altijd
                    De doelrang van een Reiziger moet gesteld worden op 5
                    indien de rang van de bestemming groter is dan 3.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();   // build() asserts the DRL COMPILES
        Reiziger r = new Reiziger(); r.setBestemming(new Luchthaven());
        sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();
        assertNull(r.getDoelrang(), () -> "een onevalueerbare voorwaarde mag niet vuren, kreeg " + r.getDoelrang());
    }

    @Test void attribuutverwijzing_bindt_hoofdletterongevoelig() {
        // Object-type names already resolve case-insensitively; an attribute reference that
        // differs only in case ("de Afstand" vs declared "afstand") must bind the same
        // attribute and emit the getter, not be dropped to a marker. Proven by firing: 250.
        String src = """
            Objecttype de Reiziger
                de afstand tot doel Numeriek (geheel getal) met eenheid km;
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel afstand overnemen
                geldig altijd
                    De afstand tot doel van een Reiziger moet gesteld worden op de Afstand van de bestemming.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject.getBestemming().getAfstand()"),
                () -> "hoofdletterongevoelige verwijzing moet de getter binden:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"), () -> "mag geen marker zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Luchthaven doel = new Luchthaven(); doel.setAfstand(new BigDecimal("250"));
        Reiziger r = new Reiziger(); r.setBestemming(doel);
        sessie.insert(doel); sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("250").compareTo(r.getAfstandTotDoel()),
                () -> "Afstand (250) moet binden ondanks hoofdletter, kreeg " + r.getAfstandTotDoel());
    }

    @Test void consistentieregel_met_afwezig_attribuut_vuurt_niet() {
        // p7b regression: a consistentieregel whose ordering operand cannot be resolved (the role
        // target Luchthaven has no "rang") must NOT flag anything (§9.5: inconsistent only on a
        // real violation). The schending-constraint wraps operands in "<op> == null" null-guards,
        // so a /* TODO */ marker there would read "null == null" (always true) and fire on EVERY
        // subject. The transpiler must instead drop to the no-op path. Proven by firing: 0 Inconsistentie.
        String src = """
            Objecttype de Reiziger
                de doelrang Numeriek (geheel getal);
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel rang controle
                geldig altijd
                    De doelrang van een Reiziger moet groter zijn dan de rang van de bestemming.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();   // build() asserts the DRL COMPILES
        Reiziger r = new Reiziger(); r.setDoelrang(new BigDecimal("5")); r.setBestemming(new Luchthaven());
        sessie.insert(r);
        sessie.fireAllRules();
        long fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie).count();
        sessie.dispose();
        assertEquals(0, fouten, "een onevalueerbare consistentieregel mag niets markeren");
    }

    @Test void consistentieregel_gevuld_met_afwezig_attribuut_vuurt_niet() {
        // The negation branch of the same hazard: "moet gevuld zijn" over an absent attribute
        // negates to "<marker> == null" (always true). Must drop to the no-op path -> 0 Inconsistentie.
        String src = """
            Objecttype de Reiziger
                de doelrang Numeriek (geheel getal);
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel rang gevuld
                geldig altijd
                    De rang van de bestemming van een Reiziger moet gevuld zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Reiziger r = new Reiziger(); r.setBestemming(new Luchthaven());
        sessie.insert(r);
        sessie.fireAllRules();
        long fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie).count();
        sessie.dispose();
        assertEquals(0, fouten, "een onevalueerbare gevuld-criterium mag niets markeren");
    }

    @Test void navigatie_bindt_de_rol_die_de_bron_noemt_niet_een_willekeurige() {
        // Two feittypen connect the SAME object-type pair (Burger↔Trip) and a wederkerig
        // Partnerrelatie connects Burger to itself. A by-pair join is ambiguous for both
        // (Burger→Trip has two feittypen; Burger→Burger via a single wederkerig role); the
        // source names which one, so each navigation must bind the WRITTEN role:
        //   heensom := de tarief van de heenvlucht   -> getHeenvlucht()
        //   terugsom := de tarief van de terugvlucht -> getTerugvlucht()
        //   partnerleeftijd := de leeftijd van zijn partner -> getPartner() (NOT own leeftijd)
        String src = """
            Objecttype de Burger
                de heensom Numeriek (geheel getal);
                de terugsom Numeriek (geheel getal);
                de partnerleeftijd Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Objecttype de Trip
                de tarief Numeriek (geheel getal);
            Feittype heenreis
                de reiziger Burger
                de heenvlucht Trip
            Eén reiziger heeft één heenvlucht
            Feittype terugreis
                de passagier Burger
                de terugvlucht Trip
            Eén passagier heeft één terugvlucht
            Wederkerig feittype Partnerrelatie
                de partner Burger
            Eén partner heeft één partner
            Regel heen
                geldig altijd
                    De heensom van een Burger moet gesteld worden op de tarief van de heenvlucht.
            Regel terug
                geldig altijd
                    De terugsom van een Burger moet gesteld worden op de tarief van de terugvlucht.
            Regel partner
                geldig altijd
                    De partnerleeftijd van een Burger moet gesteld worden op de leeftijd van zijn partner.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        // the joins bind the NAMED reference fields, not the marker the by-pair search emits
        assertTrue(drl.contains("$subject.getHeenvlucht().getTarief()"), () -> "heenvlucht-navigatie:\n" + drl);
        assertTrue(drl.contains("$subject.getTerugvlucht().getTarief()"), () -> "terugvlucht-navigatie:\n" + drl);
        assertTrue(drl.contains("$subject.getPartner().getLeeftijd()"), () -> "wederkerige partner-navigatie:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"), () -> "navigatie bleef onopgelost:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Trip t1 = new Trip(); t1.setTarief(new BigDecimal("100"));   // p's heenvlucht
        Trip t2 = new Trip(); t2.setTarief(new BigDecimal("50"));    // p's terugvlucht
        Trip t3 = new Trip(); t3.setTarief(new BigDecimal("10"));    // q's heenvlucht
        Trip t4 = new Trip(); t4.setTarief(new BigDecimal("20"));    // q's terugvlucht
        Burger p = new Burger(); p.setLeeftijd(new BigDecimal("30")); p.setHeenvlucht(t1); p.setTerugvlucht(t2);
        Burger q = new Burger(); q.setLeeftijd(new BigDecimal("40")); q.setHeenvlucht(t3); q.setTerugvlucht(t4);
        p.setPartner(q); q.setPartner(p);                            // wederkerig: symmetric
        sessie.insert(t1); sessie.insert(t2); sessie.insert(t3); sessie.insert(t4);
        sessie.insert(p); sessie.insert(q);
        sessie.fireAllRules();
        sessie.dispose();

        // heen/terug must not be swapped: heensom is the HEENvlucht tarief, not the terug
        assertEquals(0, new BigDecimal("100").compareTo(p.getHeensom()), () -> "heensom: " + p.getHeensom());
        assertEquals(0, new BigDecimal("50").compareTo(p.getTerugsom()), () -> "terugsom: " + p.getTerugsom());
        assertEquals(0, new BigDecimal("10").compareTo(q.getHeensom()), () -> "q heensom: " + q.getHeensom());
        // wederkerig navigation reads the PARTNER's leeftijd, not the subject's own
        assertEquals(0, new BigDecimal("40").compareTo(p.getPartnerleeftijd()), () -> "p partnerleeftijd: " + p.getPartnerleeftijd());
        assertEquals(0, new BigDecimal("30").compareTo(q.getPartnerleeftijd()), () -> "q partnerleeftijd: " + q.getPartnerleeftijd());
    }

    @Test void exacte_rolnaam_bindt_ondanks_botsend_gesynthetiseerd_meervoud() {
        // A role literally named "heenvluchten" collides with the +en plural a best-effort
        // pluraliser would form for another feittype's "heenvlucht" role. A reference that
        // names "heenvluchten" exactly must bind THAT role (§3.1 footnote 2: a synthesized
        // plural never defeats a declared name), not collapse to a null marker. Proven by
        // compiling+firing the navigation getter against the fact model.
        String src = """
            Objecttype de Reis
                de prijs Numeriek (geheel getal);
            Objecttype de Trip
                de tarief Numeriek (geheel getal);
            Feittype boeking
                de reis Reis
                de heenvlucht Trip
            Eén reis heeft één heenvlucht
            Feittype extra
                de extrareis Reis
                de heenvluchten Trip
            Eén extrareis heeft één heenvluchten
            Regel prijs
                geldig altijd
                    De prijs van een Reis moet gesteld worden op de tarief van de heenvluchten.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject.getHeenvluchten().getTarief()"), () -> "exacte rol moet binden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Trip heen = new Trip(); heen.setTarief(new BigDecimal("7"));          // the "heenvlucht" role
        Trip extra = new Trip(); extra.setTarief(new BigDecimal("99"));       // the "heenvluchten" role
        Reis r = new Reis(); r.setHeenvlucht(heen); r.setHeenvluchten(extra);
        sessie.insert(heen); sessie.insert(extra); sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();

        // prijs is the tarief of the named "heenvluchten" (99), not the colliding "heenvlucht" (7)
        assertEquals(0, new BigDecimal("99").compareTo(r.getPrijs()), () -> "prijs: " + r.getPrijs());
    }

    @Test void botsende_objecttype_en_rolnaam_bindt_de_rol_die_de_subject_bereikt() {
        // §13.4.1.2/.3: a navigation filler may be an objecttypenaam OR a rolnaam. "bestemming"
        // is BOTH — a free objecttype AND a role (of Luchthaven) on the subject Reiziger. The
        // free objecttype does NOT join Reiziger; the role does. So "de afstand van de
        // bestemming" must bind the ROLE (Reiziger -> Luchthaven via getBestemming), not the
        // same-named objecttype (which would leave a "TODO navigatie" null). Proven by firing.
        String src = """
            Objecttype de Reiziger
                de afstand tot doel Numeriek (geheel getal) met eenheid km;
            Objecttype de Bestemming
                de naam Tekst;
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel afstand overnemen
                geldig altijd
                    De afstand tot doel van een Reiziger moet gesteld worden op de afstand van de bestemming.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject.getBestemming().getAfstand()"),
                () -> "navigatie moet via de rol bestemming -> Luchthaven binden:\n" + drl);
        assertFalse(drl.contains("TODO navigatie"),
                () -> "de botsende objecttypenaam mag de rol niet overschaduwen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Luchthaven doel = new Luchthaven(); doel.setAfstand(new BigDecimal("250"));
        Reiziger r = new Reiziger(); r.setBestemming(doel);
        sessie.insert(doel); sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("250").compareTo(r.getAfstandTotDoel()),
                () -> "afstand tot doel moet de afstand van de luchthaven (250) zijn, kreeg " + r.getAfstandTotDoel());
    }

    @Test void botsende_naam_kaapt_geen_eigen_subjectreferentie() {
        // §5.5.3: a definite back-reference to the subject's OWN objecttype ("de persoon" when
        // the subject is a Persoon) denotes the same instance -> the subject's own attribute.
        // Here "persoon" is ALSO a role name (of Organisatie). The role-preference must NOT
        // hijack the self-reference: "de leeftijd van de persoon" reads the subject's own
        // leeftijd ($subject.getLeeftijd()), not a navigation to Organisatie.
        String src = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
                de gekopieerd Numeriek (geheel getal);
            Objecttype de Organisatie
                de omvang Numeriek (geheel getal);
            Feittype lidmaatschap
                de persoon Organisatie
                de deelnemer Persoon
            Eén persoon heeft één deelnemer
            Regel kopieer
                geldig altijd
                    De gekopieerd van een Persoon moet gesteld worden op de leeftijd van de persoon.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject.getLeeftijd()"), () -> "zelfreferentie moet eigen attribuut lezen:\n" + drl);
        assertFalse(drl.contains("getPersoon()"), () -> "een gelijknamige rol mag de zelfreferentie niet kapen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon p = new Persoon(); p.setLeeftijd(new BigDecimal("30"));
        sessie.insert(p);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("30").compareTo(p.getGekopieerd()),
                () -> "gekopieerd moet de eigen leeftijd (30) zijn, kreeg " + p.getGekopieerd());
    }

    @Test void botsende_naam_zonder_attribuut_op_de_rol_blijft_een_marker() {
        // The role target (Luchthaven) does NOT carry the referenced attribute "rang" — only the
        // name-twin objecttype Bestemming does, but that objecttype does not join the subject. So
        // the role reading must be REFUSED (it would emit a getter Luchthaven cannot answer, which
        // fails to compile); the reference stays an honest /* TODO navigatie */ null marker that
        // still compiles. Proven by COMPILING the DRL in Drools.
        String src = """
            Objecttype de Reiziger
                de doelrang Numeriek (geheel getal);
            Objecttype de Bestemming
                de rang Numeriek (geheel getal);
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Regel rang overnemen
                geldig altijd
                    De doelrang van een Reiziger moet gesteld worden op de rang van de bestemming.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("TODO navigatie"), () -> "onbereikbaar attribuut moet een marker blijven:\n" + drl);
        assertFalse(drl.contains("getBestemming().getRang()"), () -> "mag geen getter voor een afwezig attribuut emitteren:\n" + drl);

        KieSession sessie = build(drl).newKieSession();   // build() asserts the DRL COMPILES
        Reiziger r = new Reiziger();
        sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();

        assertNull(r.getDoelrang(), () -> "doelrang moet leeg blijven (marker), kreeg " + r.getDoelrang());
    }

    @Test void botsende_naam_bindt_de_geschreven_rol_niet_een_andere_die_toevallig_verbindt() {
        // The name-twin objecttype Bestemming IS reachable from the subject — but through a
        // DIFFERENT, unwritten role ("plek"). The author wrote "bestemming", which names the role
        // to Luchthaven. The navigation must bind the WRITTEN role (Reiziger -> Luchthaven via
        // getBestemming), not collapse to the by-pair join through "plek" (which would read the
        // wrong instance). Proven by firing: it must read 250 (the luchthaven), not 999 (the plek).
        String src = """
            Objecttype de Reiziger
                de afstand tot doel Numeriek (geheel getal) met eenheid km;
            Objecttype de Bestemming
                de afstand Numeriek (geheel getal) met eenheid km;
            Objecttype de Luchthaven
                de afstand Numeriek (geheel getal) met eenheid km;
            Feittype reis naar luchthaven
                de reiziger Reiziger
                de bestemming Luchthaven
            Eén reiziger reist naar één bestemming
            Feittype bezoekt
                de bezoeker Reiziger
                de plek Bestemming
            Eén bezoeker bezoekt één plek
            Regel afstand overnemen
                geldig altijd
                    De afstand tot doel van een Reiziger moet gesteld worden op de afstand van de bestemming.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$subject.getBestemming().getAfstand()"), () -> "moet de geschreven rol binden:\n" + drl);
        assertFalse(drl.contains("getPlek()"), () -> "mag niet via de ongeschreven rol plek joinen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Luchthaven doel = new Luchthaven(); doel.setAfstand(new BigDecimal("250"));   // the WRITTEN role bestemming
        Bestemming plek = new Bestemming(); plek.setAfstand(new BigDecimal("999"));   // the unwritten role plek
        Reiziger r = new Reiziger(); r.setBestemming(doel); r.setPlek(plek);
        sessie.insert(doel); sessie.insert(plek); sessie.insert(r);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("250").compareTo(r.getAfstandTotDoel()),
                () -> "moet de afstand van de geschreven bestemming (250) lezen, niet de plek (999), kreeg " + r.getAfstandTotDoel());
    }

    @Test void consistentieregel_markeert_alleen_de_schendende_instantie() {
        // §13.4.8: "de vertrek moet ongelijk zijn aan de bestemming" — a Route with equal
        // endpoints is a round trip and must be flagged inconsistent; one with distinct
        // endpoints must NOT. Proven by firing in a real engine and counting Inconsistentie.
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel rondvlucht
                geldig altijd
                    De vertrek van een Route moet ongelijk zijn aan de bestemming van een Route.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Route rond = new Route(); rond.setVertrek("AMS"); rond.setBestemming("AMS");   // round trip -> inconsistent
        Route enkel = new Route(); enkel.setVertrek("AMS"); enkel.setBestemming("JFK"); // distinct -> consistent
        sessie.insert(rond); sessie.insert(enkel);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();

        assertEquals(1, fouten.size(), () -> "precies de rondvlucht moet inconsistent zijn: " + fouten);
        assertEquals("rondvlucht", fouten.get(0).getRegel());
        assertSame(rond, fouten.get(0).getSubject(), "de gemarkeerde instantie moet de rondvlucht zijn");
    }

    @Test void consistentie_genavigeerde_gelijkheid_gooit_geen_npe_op_lege_referentie() {
        // p30/p41: a consistentie equality over a TO-ONE navigation ("de roepnaam ... gelijk aan de roepnaam
        // van zijn partner") read "partner.roepnaam", which NPEs in mvel when partner is null (mvel "." is not
        // null-safe). p30 used Drools "!." (no NPE) but that made a null partner a silent non-match, under-
        // reporting the gevuld-vs-leeg violation. p41 replaces it with explicit leegwaarde guards (Tabel 38):
        // a partnerless person's "Bo" gelijk-aan an empty value is "onwaar" -> a real violation, and still no
        // NPE (the "partner == null" guard short-circuits before any "." dereference).
        String src = """
            Objecttype de Persoon
                de roepnaam Tekst;
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel naamcheck
                geldig altijd
                    De roepnaam van een Persoon moet gelijk zijn aan de roepnaam van zijn partner.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("partner == null || partner.roepnaam == null"),
                () -> "navigatie moet via een expliciete leegwaarde-guard (geen !.):\n" + drl);
        assertFalse(drl.contains("!."), () -> "geen Drools !. meer voor een consistentie-vergelijking:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon a = new Persoon(); a.setRoepnaam("Ann");
        Persoon b = new Persoon(); b.setRoepnaam("Ann"); a.setPartner(b); b.setPartner(a);  // gelijk -> consistent
        Persoon c = new Persoon(); c.setRoepnaam("Cy");
        Persoon d = new Persoon(); d.setRoepnaam("Dee"); c.setPartner(d); d.setPartner(c);  // ongelijk -> schending
        Persoon eenzaam = new Persoon(); eenzaam.setRoepnaam("Bo");                          // partner null -> geen NPE
        sessie.insert(a); sessie.insert(b); sessie.insert(c); sessie.insert(d); sessie.insert(eenzaam);
        sessie.fireAllRules();   // must NOT throw an NPE on the null partner
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertFalse(fouten.contains(a), () -> "gelijke roepnamen -> consistent: " + fouten);
        assertTrue(fouten.contains(c), () -> "ongelijke partner-roepnamen moeten een schending zijn: " + fouten);
        assertTrue(fouten.contains(d), () -> "ongelijke partner-roepnamen moeten een schending zijn: " + fouten);
        assertTrue(fouten.contains(eenzaam), () -> "p41: 'Bo' gelijk-aan een lege partner-navigatie is onwaar (Tabel 38) -> schending, zonder NPE: " + fouten);
    }

    @Test void consistentie_genavigeerde_numerieke_gelijkheid_volgt_tabel_37() {
        // p41: a NUMERIEK "gelijk aan" over a to-one navigation follows Tabel 37 — BOTH operands empty is
        // "waar" (consistent), but a ONE-sided empty is "onwaar" (a violation). The navigated operand is
        // guarded so a null partner reads as empty: no NPE, and no "!." silent non-match.
        String src = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel leeftijdcheck
                geldig altijd
                    De leeftijd van een Persoon moet gelijk zijn aan de leeftijd van zijn partner.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Persoon eenzijdig = new Persoon(); eenzijdig.setLeeftijd(new BigDecimal("30"));   // partner leeg -> onwaar -> schending
        Persoon beideLeeg = new Persoon();                                                // leeftijd & partner leeg -> waar
        Persoon g1 = new Persoon(); g1.setLeeftijd(new BigDecimal("40"));
        Persoon g2 = new Persoon(); g2.setLeeftijd(new BigDecimal("40")); g1.setPartner(g2); g2.setPartner(g1); // gelijk
        sessie.insert(eenzijdig); sessie.insert(beideLeeg); sessie.insert(g1); sessie.insert(g2);
        sessie.fireAllRules();   // must NOT NPE
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertTrue(fouten.contains(eenzijdig), () -> "30 gelijk-aan een lege partner-leeftijd is onwaar -> schending: " + fouten);
        assertFalse(fouten.contains(beideLeeg), () -> "beide leeg is waar (Tabel 37) -> geen schending: " + fouten);
        assertFalse(fouten.contains(g1), () -> "gelijke leeftijden -> consistent: " + fouten);
    }

    @Test void consistentie_genavigeerde_operand_links_volgt_dezelfde_leegwaarde() {
        // p41 symmetry: the navigated operand may be on the LEFT ("de roepnaam van zijn partner moet gelijk
        // zijn aan de roepnaam"). The same explicit guards apply, so a partnerless person's empty left
        // operand vs a gevuld right is onwaar (Tabel 38) -> a violation, without NPE.
        String src = """
            Objecttype de Persoon
                de roepnaam Tekst;
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel naamcheck
                geldig altijd
                    De roepnaam van zijn partner moet gelijk zijn aan de roepnaam van een Persoon.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Persoon eenzaam = new Persoon(); eenzaam.setRoepnaam("Bo");                        // partner leeg (links) -> schending
        Persoon a = new Persoon(); a.setRoepnaam("Ann");
        Persoon b = new Persoon(); b.setRoepnaam("Ann"); a.setPartner(b); b.setPartner(a); // gelijk -> consistent
        sessie.insert(eenzaam); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();   // must NOT NPE
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertTrue(fouten.contains(eenzaam), () -> "lege LINKER-navigatie vs gevuld is onwaar -> schending: " + fouten);
        assertFalse(fouten.contains(a), () -> "gelijke roepnamen -> consistent: " + fouten);
    }

    @Test void consistentie_genavigeerde_ordening_telt_lege_navigatie_als_schending() {
        // p41: ordening (Tabel 40-43) over a to-one navigation — any empty operand is "onwaar", so a person
        // whose partner is absent violates "moet kleiner zijn dan de leeftijd van zijn partner", without NPE.
        String src = """
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel jongercheck
                geldig altijd
                    De leeftijd van een Persoon moet kleiner zijn dan de leeftijd van zijn partner.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Persoon eenzaam = new Persoon(); eenzaam.setLeeftijd(new BigDecimal("30"));        // partner leeg -> onwaar -> schending
        Persoon jong = new Persoon(); jong.setLeeftijd(new BigDecimal("20"));
        Persoon oud = new Persoon(); oud.setLeeftijd(new BigDecimal("50")); jong.setPartner(oud); oud.setPartner(jong);
        sessie.insert(eenzaam); sessie.insert(jong); sessie.insert(oud);
        sessie.fireAllRules();   // must NOT NPE
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> ((Inconsistentie) o).getSubject()).toList();
        sessie.dispose();

        assertTrue(fouten.contains(eenzaam), () -> "een lege partner-navigatie -> ordening onwaar -> schending: " + fouten);
        assertTrue(fouten.contains(oud), () -> "50 < 20 is onwaar -> schending: " + fouten);
        assertFalse(fouten.contains(jong), () -> "20 < 50 is waar -> consistent: " + fouten);
    }

    @Test void genavigeerde_leeg_predikaat_is_waar_voor_een_afwezige_referentie() {
        // p30 review (HIGH): "is leeg" over a to-one navigation must NOT use Drools "!." — "!." short-circuits
        // to a non-match on a null reference, the WRONG answer for `is leeg` (typeringen Tabel 54: an absent
        // value IS leeg -> waar). The reference is guarded explicitly so a partnerless person reads the
        // navigated operand as empty and the leeg-gated rule FIRES.
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                de leeftijd Numeriek (geheel getal);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel leegcheck
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1 indien de leeftijd van zijn partner leeg is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("(partner == null || partner.leeftijd == null)"), () -> "leeg moet de referentie expliciet guarden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon eenzaam = new Persoon();                                          // geen partner -> leeftijd-van-partner is leeg -> vuurt
        Persoon metPartner = new Persoon();
        Persoon partner = new Persoon(); partner.setLeeftijd(new BigDecimal("40"));
        metPartner.setPartner(partner);                                          // partner met leeftijd -> niet leeg -> niet
        sessie.insert(eenzaam); sessie.insert(metPartner); sessie.insert(partner);
        sessie.fireAllRules();   // must NOT NPE
        sessie.dispose();

        assertEquals(0, new BigDecimal("1").compareTo(eenzaam.getUitkomst()),
                () -> "een afwezige partner -> leeftijd-van-partner is leeg (Tabel 54 waar) -> vuurt, kreeg " + eenzaam.getUitkomst());
        assertNull(metPartner.getUitkomst(), () -> "een gevulde partner-leeftijd is niet leeg -> niet gegated");
    }

    @Test void consistentie_tekst_gelijkheid_met_beide_leeg_is_een_schending() {
        // typeringen Tabel 38: for a NON-numeriek "gelijk aan", BOTH operands empty evaluates to "fout"
        // (the numeriek Tabel 37 case is "waar"). A consistentie criterion is satisfied only when its
        // comparison is "waar", so the both-empty Tekst case is a violation — not silently consistent (which
        // the plain `vertrek != bestemming` -> `null != null` -> false would report).
        String src = """
            Objecttype de Route
                de vertrek Tekst;
                de bestemming Tekst;
            Regel gelijke eindpunten
                geldig altijd
                    De vertrek van een Route moet gelijk zijn aan de bestemming van een Route.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Route beideLeeg = new Route();                                              // beide leeg -> fout -> schending
        Route gelijk = new Route(); gelijk.setVertrek("AMS"); gelijk.setBestemming("AMS"); // gelijk -> consistent
        Route eenLeeg = new Route(); eenLeeg.setVertrek("AMS");                     // één leeg -> onwaar -> schending
        Route ongelijk = new Route(); ongelijk.setVertrek("AMS"); ongelijk.setBestemming("JFK"); // -> schending
        sessie.insert(beideLeeg); sessie.insert(gelijk); sessie.insert(eenLeeg); sessie.insert(ongelijk);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).map(Inconsistentie::getSubject).toList();
        sessie.dispose();

        assertTrue(fouten.contains(beideLeeg), () -> "beide-leeg Tekst-gelijkheid moet een schending zijn (Tabel 38 fout): " + fouten);
        assertTrue(fouten.contains(eenLeeg), () -> "één-leeg moet een schending zijn (onwaar): " + fouten);
        assertTrue(fouten.contains(ongelijk), () -> "ongelijke eindpunten moeten een schending zijn: " + fouten);
        assertFalse(fouten.contains(gelijk), () -> "gelijke eindpunten mogen geen schending zijn: " + fouten);
    }

    @Test void uniciteit_markeert_de_instanties_die_een_sleutel_delen() {
        // §8.1.6: "de burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn" — two
        // persons sharing a BSN violate uniqueness (both flagged), a unique BSN does not. The plural
        // attribuut "burgerservicenummers" binds the declared singular "burgerservicenummer" via the
        // meervoud. Proven by firing in a real engine and counting Inconsistentie.
        String src = """
            Objecttype de Natuurlijk persoon
                het burgerservicenummer Numeriek (geheel getal);
            Regel bsn uniek
                geldig altijd
                    De burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("from accumulate( $o : NatuurlijkPersoon( burgerservicenummer == $k0 ), count($o) )"),
                () -> "uniciteit moet de sleutel-tweelingen tellen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        NatuurlijkPersoon a = new NatuurlijkPersoon(); a.setBurgerservicenummer(new BigDecimal("100"));
        NatuurlijkPersoon b = new NatuurlijkPersoon(); b.setBurgerservicenummer(new BigDecimal("100")); // deelt BSN
        NatuurlijkPersoon c = new NatuurlijkPersoon(); c.setBurgerservicenummer(new BigDecimal("200")); // uniek
        NatuurlijkPersoon d = new NatuurlijkPersoon();                                                  // BSN leeg
        NatuurlijkPersoon e = new NatuurlijkPersoon();                                                  // BSN leeg
        for (NatuurlijkPersoon p : new NatuurlijkPersoon[]{a, b, c, d, e}) sessie.insert(p);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();

        // Both duplicates flagged; the unique one not; and two NULL keys do NOT collide (§8.1.6: two
        // unknown identities are not the same real-world object — the key is guarded "!= null").
        assertEquals(2, fouten.size(), () -> "alleen de twee met gedeeld BSN mogen gemarkeerd zijn: " + fouten);
        assertTrue(fouten.stream().allMatch(f -> f.getRegel().equals("bsn uniek")));
        assertTrue(fouten.stream().anyMatch(f -> f.getSubject() == a) && fouten.stream().anyMatch(f -> f.getSubject() == b),
                "beide instanties met hetzelfde BSN moeten gemarkeerd zijn");
        assertTrue(fouten.stream().noneMatch(f -> f.getSubject() == c || f.getSubject() == d || f.getSubject() == e),
                "de unieke en de lege-BSN instanties mogen niet gemarkeerd zijn");
    }

    @Test void uniciteit_telt_niet_dubbel_bij_incrementeel_vuren() {
        // p2 review (MED, fixed): a duplicate inserted in a LATER fire cycle raises the accumulate
        // count for already-flagged subjects, re-activating the rule — no-loop does not block a
        // re-activation caused by a DIFFERENT fact's insertion. Without a guard each growth step
        // re-inserts an Inconsistentie (3 shared keys -> 5 facts). The "not Inconsistentie(regel,
        // subject)" self-guard keeps the consequence idempotent: each subject flagged exactly once.
        String src = """
            Objecttype de Natuurlijk persoon
                het burgerservicenummer Numeriek (geheel getal);
            Regel bsn uniek
                geldig altijd
                    De burgerservicenummers van alle Natuurlijk persoon moeten uniek zijn.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        KieSession sessie = build(drl).newKieSession();
        for (String bsn : new String[]{"100", "100", "100"}) {   // arrive one per fire cycle
            NatuurlijkPersoon p = new NatuurlijkPersoon(); p.setBurgerservicenummer(new BigDecimal(bsn));
            sessie.insert(p);
            sessie.fireAllRules();
        }
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();
        assertEquals(3, fouten.size(), () -> "elk subject precies een keer, geen dubbele inserts: " + fouten);
        assertEquals(3, fouten.stream().map(Inconsistentie::getSubject).distinct().count(),
                "drie verschillende subjecten");
    }

    @Test void uniciteit_concatenatie_markeert_alleen_de_volledige_sleuteldubbelen() {
        // §8.1.6 + §5.7: "de concatenatie van de luchthaven en de vertrektijd van alle Vluchten moeten
        // uniek zijn" — two flights sharing the SAME (luchthaven, vertrektijd) violate uniqueness;
        // sharing only ONE part does not. The composite key is AND-matched in the accumulate. Fired.
        String src = """
            Objecttype de Vlucht
                de luchthaven Numeriek (geheel getal);
                de vertrektijd Numeriek (geheel getal);
            Regel vlucht uniek
                geldig altijd
                    De concatenatie van de luchthaven en de vertrektijd van alle Vluchten moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$o : Vlucht( luchthaven == $k0, vertrektijd == $k1 )"),
                () -> "de combinatie moet op BEIDE sleuteldelen joinen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht a = new Vlucht(); a.setLuchthaven(new BigDecimal("1")); a.setVertrektijd(new BigDecimal("900"));
        Vlucht b = new Vlucht(); b.setLuchthaven(new BigDecimal("1")); b.setVertrektijd(new BigDecimal("900")); // == a
        Vlucht c = new Vlucht(); c.setLuchthaven(new BigDecimal("1")); c.setVertrektijd(new BigDecimal("930")); // alleen luchthaven gedeeld
        Vlucht d = new Vlucht(); d.setLuchthaven(new BigDecimal("2")); d.setVertrektijd(new BigDecimal("900")); // alleen tijd gedeeld
        for (Vlucht v : new Vlucht[]{a, b, c, d}) sessie.insert(v);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();
        assertEquals(2, fouten.size(), () -> "alleen de twee met dezelfde (luchthaven, vertrektijd): " + fouten);
        assertTrue(fouten.stream().anyMatch(f -> f.getSubject() == a) && fouten.stream().anyMatch(f -> f.getSubject() == b),
                "beide volledige-sleutel-dubbelen gemarkeerd");
        assertTrue(fouten.stream().noneMatch(f -> f.getSubject() == c || f.getSubject() == d),
                "wie maar EEN sleuteldeel deelt mag niet gemarkeerd zijn");
    }

    @Test void uniciteit_verenigd_met_markeert_dubbelen_over_beide_typen() {
        // §8.1.6 "verenigd met": "de namen van alle Cirkels verenigd met de namen van alle Vierkanten
        // moeten uniek zijn" — a naam must be unique across BOTH types: no two Cirkels, no two
        // Vierkanten, and no Cirkel sharing a naam with a Vierkant. One rule per source sums the
        // cross-type count. Fired.
        String src = """
            Objecttype de Cirkel
                de naam Tekst;
            Objecttype het Vierkant
                de naam Tekst;
            Regel vorm uniek
                geldig altijd
                    De namen van alle Cirkels verenigd met de namen van alle Vierkanten moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Cirkel c1 = new Cirkel(); c1.setNaam("A");
        Cirkel c2 = new Cirkel(); c2.setNaam("A");      // twee Cirkels met dezelfde naam
        Cirkel c3 = new Cirkel(); c3.setNaam("B");      // uniek
        Vierkant v1 = new Vierkant(); v1.setNaam("A");  // deelt naam met de Cirkels
        Vierkant v2 = new Vierkant(); v2.setNaam("C");  // uniek
        sessie.insert(c1); sessie.insert(c2); sessie.insert(c3); sessie.insert(v1); sessie.insert(v2);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();
        // c1, c2 (two Cirkels "A") and v1 (Vierkant "A" == Cirkel "A") flagged; the unique "B"/"C" not.
        assertEquals(3, fouten.size(), () -> "de drie 'A'-vormen over beide typen: " + fouten);
        assertTrue(fouten.stream().anyMatch(f -> f.getSubject() == c1) && fouten.stream().anyMatch(f -> f.getSubject() == c2)
                && fouten.stream().anyMatch(f -> f.getSubject() == v1), "alle drie 'A'-vormen gemarkeerd");
        assertTrue(fouten.stream().noneMatch(f -> f.getSubject() == c3 || f.getSubject() == v2),
                "de unieke namen mogen niet gemarkeerd zijn");
    }

    @Test void uniciteit_verenigd_met_zelfde_eenheid_vuurt_over_beide_typen() {
        // §8.1.6: a vereniging of two SAME-unit (EUR) Numeriek attributes is well-formed and fires — a
        // value shared across the two types is a duplicate. (A EUR ∪ jr union instead warns and emits no
        // rule, so "5 EUR" and "5 jr" are never cross-matched; that is pinned in DrlTest.)
        String src = """
            Eenheidsysteem Geld
                de euro EUR
            Objecttype de Heffing
                de prijs (mv: prijzen) Numeriek (geheel getal) met eenheid EUR;
            Objecttype de Passagier
                de prijs (mv: prijzen) Numeriek (geheel getal) met eenheid EUR;
            Regel bedrag uniek
                geldig altijd
                    De prijzen van alle Heffing verenigd met de prijzen van alle Passagier moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("accumulate("), () -> "zelfde-eenheid vereniging moet lowereren:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Heffing h1 = new Heffing(); h1.setPrijs(new BigDecimal("100"));
        Heffing h2 = new Heffing(); h2.setPrijs(new BigDecimal("50"));    // uniek
        Passagier p1 = new Passagier(); p1.setPrijs(new BigDecimal("100")); // deelt 100 met h1 (zelfde eenheid)
        Passagier p2 = new Passagier(); p2.setPrijs(new BigDecimal("200")); // uniek
        sessie.insert(h1); sessie.insert(h2); sessie.insert(p1); sessie.insert(p2);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();
        assertEquals(2, fouten.size(), () -> "alleen de gedeelde 100 over beide typen: " + fouten);
        assertTrue(fouten.stream().anyMatch(f -> f.getSubject() == h1) && fouten.stream().anyMatch(f -> f.getSubject() == p1),
                "de twee '100'-bedragen over beide typen gemarkeerd");
        assertTrue(fouten.stream().noneMatch(f -> f.getSubject() == h2 || f.getSubject() == p2),
                "de unieke bedragen mogen niet gemarkeerd zijn");
    }

    @Test void uniciteit_scoped_telt_alleen_binnen_dezelfde_ouder() {
        // §8.1.6 parent-scoped: "de stoelnummers van alle passagiers van de reis moeten uniek zijn" —
        // a stoelnummer must be unique PER reis, not globally. Two passagiers of the SAME reis sharing
        // a stoelnummer are flagged; the same stoelnummer on a DIFFERENT reis is fine (different scope).
        // The accumulate is restricted to the subject's reis. Fired.
        String src = """
            Objecttype de Vlucht
            Objecttype de Passagier
                het stoelnummer Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel stoel uniek
                geldig altijd
                    De stoelnummers van alle passagiers van de reis moeten uniek zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("reis == $scope, stoelnummer == $k0"),
                () -> "de telling moet binnen dezelfde reis (scope) blijven:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v1 = new Vlucht(); Vlucht v2 = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v1); a.setStoelnummer(new BigDecimal("1"));
        Passagier b = new Passagier(); b.setReis(v1); b.setStoelnummer(new BigDecimal("1")); // == a, zelfde reis
        Passagier c = new Passagier(); c.setReis(v1); c.setStoelnummer(new BigDecimal("2")); // uniek in v1
        Passagier d = new Passagier(); d.setReis(v2); d.setStoelnummer(new BigDecimal("1")); // stoel 1, ANDERE reis
        for (Object o : new Object[]{v1, v2, a, b, c, d}) sessie.insert(o);
        sessie.fireAllRules();
        var fout = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();
        assertEquals(2, fout.size(), () -> "alleen de twee met dezelfde reis EN stoel: " + fout);
        assertTrue(fout.stream().anyMatch(f -> f.getSubject() == a) && fout.stream().anyMatch(f -> f.getSubject() == b),
                "beide passagiers met reis v1 + stoel 1 zijn gemarkeerd");
        assertTrue(fout.stream().noneMatch(f -> f.getSubject() == c || f.getSubject() == d),
                "uniek-in-scope (c) en dezelfde stoel op een andere reis (d) zijn niet gemarkeerd");
    }

    @Test void consistentieregel_ordening_markeert_ook_de_lege_operand() {
        // §9.5 + leegwaarde rekentabels: "saldo moet kleiner zijn dan limiet" is violated when
        // saldo >= limiet AND when saldo is empty (a missing value is itself an inconsistency).
        // The algebraic complement (saldo >= limiet) alone is false for null saldo and would
        // miss it; the null-guarded violation must fire. Proven by firing in a real engine.
        String src = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
            Regel saldocheck
                geldig altijd
                    De saldo van een Konto moet kleiner zijn dan de limiet van een Konto.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Konto ok = new Konto();   ok.setSaldo(new BigDecimal("10"));   ok.setLimiet(new BigDecimal("50")); // 10<50 consistent
        Konto over = new Konto(); over.setSaldo(new BigDecimal("80")); over.setLimiet(new BigDecimal("50")); // 80>=50 inconsistent
        Konto leeg = new Konto(); leeg.setLimiet(new BigDecimal("50"));                                      // saldo null inconsistent
        sessie.insert(ok); sessie.insert(over); sessie.insert(leeg);
        sessie.fireAllRules();
        var fouten = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();

        assertEquals(2, fouten.size(), () -> "over-limiet en lege saldo moeten beide inconsistent zijn: " + fouten);
        assertTrue(fouten.stream().noneMatch(f -> f.getSubject() == ok), "de consistente Konto mag niet gemarkeerd zijn");
    }

    @Test void samengestelde_consistentie_alle_criteria_markeert_elke_schending() {
        // §13.4.8: "moet voldoen aan alle volgende criteria: - saldo < limiet - saldo > minimum" — a
        // Konto is inconsistent when ANY criterion fails (violation = OR of the per-criterion
        // violations). Within the band is consistent; over the limit, under the minimum, or an empty
        // saldo (leegwaarde -> onwaar -> violated) is flagged. Fired in real Drools.
        String src = """
            Objecttype de Konto
                de saldo Numeriek (geheel getal);
                de limiet Numeriek (geheel getal);
                de minimum Numeriek (geheel getal);
            Regel grenzen
                geldig altijd
                    Een Konto moet voldoen aan alle volgende criteria:
                        - het saldo van een Konto is kleiner dan de limiet van een Konto.
                        - het saldo van een Konto is groter dan het minimum van een Konto.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Konto binnen = new Konto(); binnen.setSaldo(new BigDecimal("50")); binnen.setLimiet(new BigDecimal("100")); binnen.setMinimum(BigDecimal.ZERO);  // in band
        Konto teHoog = new Konto(); teHoog.setSaldo(new BigDecimal("150")); teHoog.setLimiet(new BigDecimal("100")); teHoog.setMinimum(BigDecimal.ZERO); // >= limiet
        Konto teLaag = new Konto(); teLaag.setSaldo(new BigDecimal("-10")); teLaag.setLimiet(new BigDecimal("100")); teLaag.setMinimum(BigDecimal.ZERO); // <= minimum
        Konto leeg = new Konto(); leeg.setLimiet(new BigDecimal("100")); leeg.setMinimum(BigDecimal.ZERO);                                              // saldo leeg
        for (Konto k : new Konto[]{binnen, teHoog, teLaag, leeg}) sessie.insert(k);
        sessie.fireAllRules();
        var fout = sessie.getObjects().stream().filter(o -> o instanceof Inconsistentie)
                .map(o -> (Inconsistentie) o).toList();
        sessie.dispose();
        assertEquals(3, fout.size(), () -> "te hoog, te laag en leeg zijn inconsistent; binnen de band niet: " + fout);
        assertTrue(fout.stream().noneMatch(f -> f.getSubject() == binnen), "een Konto binnen de band is consistent");
        assertTrue(fout.stream().anyMatch(f -> f.getSubject() == teHoog) && fout.stream().anyMatch(f -> f.getSubject() == teLaag)
                && fout.stream().anyMatch(f -> f.getSubject() == leeg), "elke schending wordt gemarkeerd");
    }

    @Test void lege_sommatie_geeft_leegwaarde_of_de_opgegeven_fallback() {
        // §5.8.2: a sum over zero contributors is a leegwaarde (null) — UNLESS "of 0 als die
        // er niet zijn" is added, then it is 0. A non-empty sum is unaffected. Drools sum()
        // returns 0 for empty, so without the count-guard both empty cases would wrongly be 0.
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
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Vlucht vol = new Vlucht();
        Passagier a = new Passagier(); a.setPrijs(new BigDecimal("30")); a.setReis(vol);
        Passagier b = new Passagier(); b.setPrijs(new BigDecimal("70")); b.setReis(vol);
        Vlucht leeg = new Vlucht();                                   // no passengers
        sessie.insert(vol); sessie.insert(leeg); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();
        sessie.dispose();

        // non-empty: both forms sum to 100
        assertEquals(0, new BigDecimal("100").compareTo(vol.getSomMet()), () -> "vol somMet: " + vol.getSomMet());
        assertEquals(0, new BigDecimal("100").compareTo(vol.getSomZonder()), () -> "vol somZonder: " + vol.getSomZonder());
        // empty: fallback -> 0, no fallback -> leegwaarde (null)
        assertEquals(0, BigDecimal.ZERO.compareTo(leeg.getSomMet()), () -> "lege somMet moet 0 zijn: " + leeg.getSomMet());
        assertNull(leeg.getSomZonder(), () -> "lege somZonder moet leegwaarde (null) zijn: " + leeg.getSomZonder());
    }

    @Test void lege_totaal_van_is_nul_niet_leegwaarde() {
        // typeringen §4.20: an empty "totaal van" is unconditionally 0 (it has no
        // "of 0 als die er niet zijn" fallback and no empty=leegwaarde rule, unlike "som van").
        // So it must NOT go through the §5.8.2 empty=leegwaarde count-guard.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel totaal
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het totaal van de prijs van alle passagiers van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("? null :"), () -> "totaal van mag geen leegwaarde-tak hebben:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht leeg = new Vlucht();   // no passengers
        sessie.insert(leeg);
        sessie.fireAllRules();
        sessie.dispose();

        assertNotNull(leeg.getTotaalprijs(), "lege 'totaal van' moet 0 zijn, geen leegwaarde");
        assertEquals(0, BigDecimal.ZERO.compareTo(leeg.getTotaalprijs()), () -> "lege totaal van: " + leeg.getTotaalprijs());
    }

    @Test void ten_minste_n_van_de_criteria_telt_de_vervulde_voorwaarden() {
        // §13.4.14.4: "aan ten minste 2 van de volgende voorwaarden voldoet" must count how
        // many criteria hold, not AND them all. So 2 or 3 satisfied -> chosen, 0 or 1 -> not.
        String src = """
            Objecttype de Kandidaat
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
                de c Numeriek (geheel getal);
                is gekozen kenmerk (bijvoeglijk);
            Regel kies
                geldig altijd
                    Een Kandidaat is gekozen
                    indien hij aan ten minste 2 van de volgende voorwaarden voldoet:
                    - de a van een Kandidaat is groter dan 0
                    - de b van een Kandidaat is groter dan 0
                    - de c van een Kandidaat is groter dan 0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Kandidaat k0 = kandidaat(0, 0, 0);   // 0 satisfied
        Kandidaat k1 = kandidaat(5, 0, 0);   // 1 satisfied
        Kandidaat k2 = kandidaat(5, 5, 0);   // 2 satisfied -> chosen
        Kandidaat k3 = kandidaat(5, 5, 5);   // 3 satisfied -> chosen
        sessie.insert(k0); sessie.insert(k1); sessie.insert(k2); sessie.insert(k3);
        sessie.fireAllRules();
        sessie.dispose();

        assertFalse(k0.isGekozen(), "0 vervuld < 2");
        assertFalse(k1.isGekozen(), "1 vervuld < 2");
        assertTrue(k2.isGekozen(), "2 vervuld >= 2");
        assertTrue(k3.isGekozen(), "3 vervuld >= 2");
    }

    @Test void rule_versie_vuurt_alleen_binnen_zijn_geldigheidsvenster() {
        // §4.2 / §13.4.2.5: the version whose window contains the Rekendatum applies. Two
        // versions set prijs differently; each fires only in its period, and a Rekendatum
        // outside both windows leaves the value untouched.
        String src = """
            Objecttype de Heffing
                de prijs Numeriek (geheel getal);
            Regel verhoog
                geldig vanaf 2024 t/m 2024
                    De prijs van een Heffing moet gesteld worden op 100.
                geldig vanaf 2025
                    De prijs van een Heffing moet gesteld worden op 200.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieBase kb = build(DrlTranspiler.transpile(m));

        assertEquals(0, new BigDecimal("100").compareTo(prijsOp(kb, LocalDate.of(2024, 6, 1))), "in 2024 -> versie #1 (100)");
        assertEquals(0, new BigDecimal("200").compareTo(prijsOp(kb, LocalDate.of(2025, 6, 1))), "in 2025 -> versie #2 (200)");
        assertNull(prijsOp(kb, LocalDate.of(2023, 6, 1)), "voor beide vensters -> geen versie vuurt");
    }

    @Test void objectcreatie_versies_kiezen_op_rekendatum_en_compileren_zonder_naamconflict() {
        // §13.4.2.5 / §4.2 for an objectcreatie: two versions create the factuur differently;
        // each fires only in its window, and the two versions must get distinct DRL rule names
        // (a multi-version rule of this kind used to collide into one name and fail the package).
        String src = """
            Objecttype de Bestelling
                de waarde Numeriek (geheel getal);
            Objecttype de Factuur
                de bedrag Numeriek (geheel getal);
            Feittype Bestelling met factuur
                de bestelling Bestelling
                de factuur Factuur
            Eén bestelling heeft één factuur
            Regel maak factuur
                geldig vanaf 2024 t/m 2024
                    Een Bestelling heeft een factuur met bedrag gelijk aan de waarde van een Bestelling.
                geldig vanaf 2025
                    Een Bestelling heeft een factuur met bedrag gelijk aan 999.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rule \"maak factuur #1\"") && drl.contains("rule \"maak factuur #2\""),
                () -> "versies krijgen geen unieke namen:\n" + drl);
        KieBase kb = build(drl); // asserts the package COMPILES (no duplicate rule name)

        assertEquals(0, new BigDecimal("50").compareTo(factuurBedragOp(kb, LocalDate.of(2024, 6, 1))), "2024 -> versie #1 (waarde 50)");
        assertEquals(0, new BigDecimal("999").compareTo(factuurBedragOp(kb, LocalDate.of(2025, 6, 1))), "2025 -> versie #2 (999)");
        assertNull(factuurBedragOp(kb, LocalDate.of(2023, 6, 1)), "voor beide vensters -> geen factuur aangemaakt");
    }

    @Test void verdeling_versies_compileren_en_vuren_alleen_binnen_hun_venster() {
        // p6b for a verdeling: two versions guard on the Rekendatum and must compile to distinct
        // DRL rules (the $src/accumulate/$r when-shape with an added eval). In-window the pot is
        // distributed; outside every window no version fires.
        String src = """
            Objecttype de Pot
                de totaal Numeriek (geheel getal);
            Objecttype de Deelnemer
                de aandeel Numeriek (geheel getal);
            Feittype Pot over deelnemers
                de pot Pot
                de deelnemer Deelnemer
            Eén pot wordt verdeeld over meerdere deelnemers
            Regel verdeel
                geldig vanaf 2024 t/m 2024
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
                geldig vanaf 2025
                    De totaal van een pot wordt verdeeld over de aandeel van alle deelnemers van een pot, waarbij wordt verdeeld in gelijke delen.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rule \"verdeel #1\"") && drl.contains("rule \"verdeel #2\""), () -> "versie-namen:\n" + drl);
        KieBase kb = build(drl); // asserts the package COMPILES (guard in the $src/accumulate/$r shape)

        assertEquals(0, new BigDecimal("30").compareTo(aandeelOp(kb, LocalDate.of(2024, 6, 1))), "in 2024 -> verdeeld (90/3)");
        assertNull(aandeelOp(kb, LocalDate.of(2023, 6, 1)), "voor beide vensters -> niet verdeeld");
    }

    /** Fire the verdeel rules at one Rekendatum over a Pot(90) with 3 Deelnemers; return one
     *  deelnemer's aandeel, or null when no version fired. */
    private static BigDecimal aandeelOp(KieBase kb, LocalDate rekendatum) {
        KieSession sessie = kb.newKieSession();
        sessie.setGlobal("rekendatum", rekendatum);
        Pot p = new Pot(); p.setTotaal(new BigDecimal("90"));
        Deelnemer d1 = new Deelnemer(); d1.setPot(p);
        Deelnemer d2 = new Deelnemer(); d2.setPot(p);
        Deelnemer d3 = new Deelnemer(); d3.setPot(p);
        sessie.insert(p); sessie.insert(d1); sessie.insert(d2); sessie.insert(d3);
        sessie.fireAllRules();
        sessie.dispose();
        return d1.getAandeel();
    }

    /** Fire the objectcreatie rules at one Rekendatum over a Bestelling(waarde=50); return the
     *  created Factuur's bedrag, or null when no Factuur was created. */
    private static BigDecimal factuurBedragOp(KieBase kb, LocalDate rekendatum) {
        KieSession sessie = kb.newKieSession();
        sessie.setGlobal("rekendatum", rekendatum);
        Bestelling b = new Bestelling(); b.setWaarde(new BigDecimal("50"));
        sessie.insert(b);
        sessie.fireAllRules();
        var facturen = sessie.getObjects().stream().filter(o -> o instanceof Factuur).map(o -> (Factuur) o).toList();
        sessie.dispose();
        return facturen.isEmpty() ? null : facturen.get(0).getBedrag();
    }

    /** Fire the heffing rules at one Rekendatum and return the resulting prijs. */
    private static BigDecimal prijsOp(KieBase kb, LocalDate rekendatum) {
        KieSession sessie = kb.newKieSession();
        sessie.setGlobal("rekendatum", rekendatum);
        Heffing h = new Heffing();
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        return h.getPrijs();
    }

    private static Kandidaat kandidaat(int a, int b, int c) {
        Kandidaat k = new Kandidaat();
        k.setA(new BigDecimal(a)); k.setB(new BigDecimal(b)); k.setC(new BigDecimal(c));
        return k;
    }

    // ---------------- p21: §13.2.5 rational literals (a/c, mixed a_b/c) ----------------
    // The lowering used to read only a rational's leading integer ("4/2" -> 4), so every
    // arithmetic position with a rational was silently wrong. These fire the real engine. The
    // netto type is a parameter so a fractional result lands in a decimal attribute (a fractional
    // value into a geheel-getal attribute would be a §6.1.2 precision violation — carded, not yet
    // enforced — and we do not want to bake a spec-invalid model into the regression suite).

    /** Fire one 'De netto van een Heffing moet <berekening>.' rule over a single Heffing. */
    private BigDecimal heffingNetto(String nettoType, String berekening, BigDecimal basis) {
        String src = """
            Objecttype de Heffing
                de basis Numeriek (geheel getal);
                de netto Numeriek (%s);
            Regel r
                geldig altijd
                    De netto van een Heffing moet %s.
            """.formatted(nettoType, berekening);
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        KieSession sessie = build(DrlTranspiler.transpile(m)).newKieSession();
        Heffing h = new Heffing(); h.setBasis(basis);
        sessie.insert(h);
        sessie.fireAllRules();
        sessie.dispose();
        return h.getNetto();
    }

    @Test void rationeel_literal_dat_exact_deelt_is_zijn_quotient_niet_zijn_kop() {
        // "maal 4/2" is *2, not the *4 the leading-integer reader produced. 3 * 4/2 = 6 (not 12).
        assertEquals(0, new BigDecimal("6").compareTo(heffingNetto("geheel getal",
                "berekend worden als de basis van een Heffing maal 4/2", new BigDecimal("3"))),
                "4/2 moet als 2 lager komen, niet als 4");
    }

    @Test void rationeel_literal_dat_een_echte_breuk_is_blijft_fractioneel() {
        // "maal 3/2" is *1.5. 5 * 3/2 = 7,5 — the half survives (a *3 mis-read would give 15).
        assertEquals(0, new BigDecimal("7.5").compareTo(heffingNetto("getal met 2 decimalen",
                "berekend worden als de basis van een Heffing maal 3/2", new BigDecimal("5"))),
                "3/2 moet als 1,5 lager komen");
    }

    @Test void gemengd_rationeel_literal_telt_heel_en_breuk_op() {
        // The mixed form "1_1/2" = 1 + 1/2 = 1,5. 4 * 1_1/2 = 6. The product is a 1-decimal result
        // (the rational types as 1 decimal now), so the target is a 2-decimal attribute — assigning
        // it to a geheel getal would be a §6.1.2 precision violation.
        assertEquals(0, new BigDecimal("6").compareTo(heffingNetto("getal met 2 decimalen",
                "berekend worden als de basis van een Heffing maal 1_1/2", new BigDecimal("4"))),
                "1_1/2 moet als 1,5 lager komen");
    }

    @Test void negatief_gemengd_rationeel_met_nul_heel_behoudt_zijn_teken() {
        // "-0_1/2" = -(0 + 1/2) = -0,5. The sign must come from the leading '-', not the whole
        // part: BigDecimal("-0").signum() is 0, so reading the sign from it dropped the minus.
        // basis 1 maal -0_1/2 = -0,5.
        assertEquals(0, new BigDecimal("-0.5").compareTo(heffingNetto("getal met 2 decimalen",
                "berekend worden als de basis van een Heffing maal -0_1/2", new BigDecimal("1"))),
                "-0_1/2 moet -0,5 zijn, niet +0,5");
    }

    @Test void rationeel_exponent_dat_tot_een_geheel_getal_reduceert_is_een_exacte_macht() {
        // "tot de macht 4/2" is ^2, not ^4. 3^2 = 9 (a pow(4) mis-read would give 81).
        assertEquals(0, new BigDecimal("9").compareTo(heffingNetto("geheel getal",
                "gesteld worden op de basis van een Heffing tot de macht 4/2 naar beneden afgerond op 0 decimalen",
                new BigDecimal("3"))), "4/2 als exponent moet 2 zijn, niet 4");
    }

    @Test void niet_eindigend_rationeel_literal_vuurt_via_decimal64_zonder_te_gooien() {
        // "maal 1/3" cannot lower to an exact BigDecimal — it must divide under DECIMAL64 rather
        // than throw a non-terminating ArithmeticException at runtime. 9 * 1/3 ~= 3 (the residual
        // imprecision is the engine-wide decimal-vs-exact-fraction limitation, carded). The
        // non-terminating literal types as 16 decimals (DECIMAL64), so the target carries 16 — a
        // narrower attribute would (correctly, per §6.1.2) demand an explicit afronding first.
        BigDecimal netto = heffingNetto("getal met 16 decimalen",
                "berekend worden als de basis van een Heffing maal 1/3", new BigDecimal("9"));
        assertTrue(netto.subtract(new BigDecimal("3")).abs().compareTo(new BigDecimal("0.0001")) < 0,
                () -> "9 maal 1/3 ~= 3, kreeg " + netto);
    }

    @Test void rationeel_literal_met_nul_noemer_wordt_bij_het_parsen_afgewezen() {
        // §13.2.5: the denominator is POSITIVE_DIGIT, so "1/0" is not a number — the lexer splits
        // it into NUMBER SLASH NUMBER and the parser rejects the stray '/'. It must NOT lower to a
        // BigDecimal divide-by-zero that throws at fire time (the regression this guards).
        String src = """
            Objecttype de Heffing
                de netto Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De netto van een Heffing moet gesteld worden op 1/0.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.errors().isEmpty(), "1/0 moet een syntaxfout geven, niet stil naar 1 herstellen");
    }

    // ---------------- §8.1.7/§8.1.8 membership condition (rolcheck + EEN-form kenmerkcheck) ----------------

    static final String MEMBERSHIP_SRC = """
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk;
            Objecttype de Vlucht
            Feittype Vlucht met passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel rolgate
                geldig altijd
                    Een passagier is minderjarig indien hij is een passagier.
            """;

    @Test void membership_rolcheck_gate_de_regel_op_rolvervulling() {
        // §8.1.7 rolcheck "hij is een passagier" — the subject FILLS the "passagier" role iff it joins the
        // other end of the feittype, so it lowers to "reis != null" (NOT the old silent false /* TODO */ gate
        // that never fired). The gate must DISCRIMINATE: fire only for passagiers that actually have a reis.
        RegelSpraakModel m = Resolver.resolve(MEMBERSHIP_SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("reis != null"), () -> "rolcheck niet als reis != null gelowerd:\n" + drl);
        assertFalse(drl.contains("/* TODO"), () -> "membership-regel mag geen marker meer bevatten:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier p1 = new Passagier(); p1.setReis(v);   // fills the passagier role
        Passagier p2 = new Passagier();                  // no reis -> not a passagier
        for (Object o : new Object[]{v, p1, p2}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();
        assertTrue(p1.isMinderjarig(), "p1 is een passagier (heeft reis) -> de rolgate vuurt");
        assertFalse(p2.isMinderjarig(), "p2 is geen passagier (geen reis) -> de rolgate vuurt niet");
    }

    @Test void membership_rolcheck_op_de_een_zijde_markert_ipv_uncompilable_drl() {
        // §8.1.7: "de Vlucht is een reis" — the subject (Vlucht) fills the to-ONE "reis" role, but in the §9.4
        // reference-field model the relation field lives on the MANY side (Passagier.reis), NOT on Vlucht. A bare
        // "passagier != null" on a Vlucht pattern is uncompilable DRL (no such field), so the far-side rolcheck
        // must stay an honest marker (an exists-join is carded), never a wrong/uncompilable constraint.
        String src = """
            Objecttype de Passagier
            Objecttype de Vlucht
                is internationaal kenmerk;
            Feittype Vluchtboeking
                de reis Vlucht
                de passagier Passagier
            Eén reis heeft meerdere passagiers
            Regel vol
                geldig altijd
                    Een Vlucht is internationaal indien hij is een reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("passagier != null"), () -> "een far-side rolcheck mag geen scalair veld op de één-zijde lezen:\n" + drl);
        assertTrue(drl.contains("/* TODO"), () -> "een far-side rolcheck moet een marker blijven:\n" + drl);
        build(drl);  // must COMPILE — an uncompilable `passagier != null` on Vlucht would throw here
    }

    @Test void membership_eenvorm_kenmerkcheck_bindt_alleen_een_zelfstandig_kenmerk() {
        // §8.1.8.3: "hij is een <zelfstandig kenmerk>" binds the boolean property (student == true); a BIJVOEGLIJK
        // kenmerk (written "is <k>", no "een") must NOT bind the EEN-form — it stays a marker, so a rule gated on
        // "is een <bijvoeglijk>" never fires. The soort comes from the §3.5 (bijvoeglijk)/(bezittelijk) tag.
        String src = """
            Objecttype de Passagier
                is student kenmerk;
                is minderjarig kenmerk (bijvoeglijk);
                is kind kenmerk;
            Regel zelfstandig
                geldig altijd
                    Een passagier is kind indien hij is een student.
            Regel bijvoeglijk
                geldig altijd
                    Een passagier is kind indien hij is een minderjarig.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("student == true"), () -> "zelfstandig EEN-vorm kenmerkcheck niet gelowerd:\n" + drl);
        assertTrue(drl.contains("/* TODO"), () -> "een bijvoeglijk kenmerk mag de EEN-vorm NIET binden:\n" + drl);
        assertFalse(drl.contains("minderjarig == true"), () -> "bijvoeglijk 'is een minderjarig' mag niet binden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Passagier student = new Passagier(); student.setStudent(true);    // gates the zelfstandig rule
        Passagier ander = new Passagier();                                // not a student
        sessie.insert(student); sessie.insert(ander);
        sessie.fireAllRules();
        sessie.dispose();
        assertTrue(student.isKind(), "een student -> de zelfstandige kenmerkgate vuurt");
        assertFalse(ander.isKind(), "geen student -> de gate vuurt niet (en de bijvoeglijke regel is inert)");
    }

    // ---------------- p29(1): §13.4.2 inflected kenmerk die-filter ----------------

    @Test void getelde_die_verbogen_kenmerk_filter_telt_alleen_de_minderjarige_passagiers() {
        // The spec's OWN canonical §13.4.2 example: kenmerk declared "minderjarig", but the rule
        // writes the nominalized "-e" form "die een minderjarige zijn". Exact equality misses that,
        // so it used to stay a marker; KenmerkMatcher binds the inflection to the declared base, so
        // it lowers to Passagier( reis == $subject, minderjarig == true ). Fired: 3 passagiers, 2
        // minderjarig -> the count is 2, the inflected form is no longer a silent no-op.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een minderjarige zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("minderjarig == true"),
                () -> "de verbogen die-filter moet een accumulate-constraint zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setMinderjarig(true);
        Passagier b = new Passagier(); b.setReis(v); b.setMinderjarig(true);
        Passagier c = new Passagier(); c.setReis(v); c.setMinderjarig(false);  // meerderjarig -> excluded
        sessie.insert(v); sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("2").compareTo(v.getTotaalprijs()),
                () -> "alleen de minderjarige passagiers tellen mee (2), kreeg " + v.getTotaalprijs());
    }

    @Test void getelde_die_onbekend_kenmerk_blijft_een_marker_niet_een_telling_over_alles() {
        // The inflection match must stay TIGHT: a predicaat naming a kenmerk the element does NOT
        // declare ("die een volwassene zijn", with only "minderjarig" declared) is no inflection of
        // any declared kenmerk, so it must NOT silently bind one and must NOT count all. It lowers to
        // a marker and warns; fired with 3 passagiers the result is never set to 3.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een volwassene zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("die/dat")),
                "de niet-omgezette die-filter moet een waarschuwing geven");
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("/* TODO aggregatie AANTAL"), () -> "een onbekend kenmerk moet een marker zijn:\n" + drl);
        assertFalse(drl.contains("count("), () -> "een onbekende die-filter mag niet als count over alle instanties lager komen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht(); v.setTotaalprijs(new BigDecimal("999")); // sentinel
        Passagier a = new Passagier(); a.setReis(v);
        Passagier b = new Passagier(); b.setReis(v);
        Passagier c = new Passagier(); c.setReis(v);
        sessie.insert(v); sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();
        BigDecimal res = v.getTotaalprijs();
        assertTrue(res == null || new BigDecimal("3").compareTo(res) != 0,
                () -> "de onbekende die-filter telde stil ALLE passagiers: " + res);
    }

    @Test void verbuiging_die_bij_meerdere_kenmerken_matcht_is_orde_onafhankelijk_een_marker() {
        // p29(1) review (HIGH, fixed): "grote" is the regular inflection of BOTH "groot" (shortened)
        // and "grot" (plain +e). Binding either would make the count depend on which kenmerk was
        // declared first — a silent, order-dependent wrong total. The ambiguous inflection must bind
        // NONE: identical DRL (a marker, no count) regardless of declaration order, fired as a no-op.
        java.util.function.Function<String, String> drlVoor = volgorde -> DrlTranspiler.transpile(Resolver.resolve("""
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
            %s
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een grote zijn.
            """.formatted(volgorde)));
        String grootEerst = drlVoor.apply("    is groot kenmerk (bijvoeglijk);\n    is grot kenmerk (bijvoeglijk);");
        String grotEerst = drlVoor.apply("    is grot kenmerk (bijvoeglijk);\n    is groot kenmerk (bijvoeglijk);");
        for (String drl : new String[]{grootEerst, grotEerst}) {
            assertTrue(drl.contains("/* TODO aggregatie AANTAL"), () -> "ambigue verbuiging moet een marker zijn:\n" + drl);
            assertFalse(drl.contains("count("), () -> "ambigue verbuiging mag niet als telling lager komen:\n" + drl);
            KieSession sessie = build(drl).newKieSession();
            Vlucht v = new Vlucht(); v.setTotaalprijs(new BigDecimal("999"));
            Passagier a = new Passagier(); a.setReis(v);
            Passagier b = new Passagier(); b.setReis(v);
            sessie.insert(v); sessie.insert(a); sessie.insert(b);
            sessie.fireAllRules();
            sessie.dispose();
            assertTrue(v.getTotaalprijs() == null || new BigDecimal("2").compareTo(v.getTotaalprijs()) != 0,
                    () -> "ambigue verbuiging telde stil: " + v.getTotaalprijs());
        }
    }

    @Test void die_op_een_min_max_operand_is_geen_telling_filter_en_breekt_de_fold_niet() {
        // p20b review (HIGH regression, fixed): die-detection is scoped to a SINGLE-operand telling.
        // A die on a min/max value-fold operand ("… en de leeftijd van de eerste passagier die …")
        // belongs to that operand's own navigation, not the aggregation's element — reading it as a
        // telling filter silently markered a working min/max. So no die/dat warning fires here, and a
        // plain min over a set still lowers to a real accumulate (proving tellingFilter is AANTAL-only).
        String fold = """
            Objecttype de Vlucht
                de uitkomst Numeriek (geheel getal);
                de basiswaarde Numeriek (geheel getal);
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel m
                geldig altijd
                    De uitkomst van een Vlucht moet berekend worden als de minimale waarde van de basiswaarde van de Vlucht en de leeftijd van de eerste passagier die een minderjarige is.
            """;
        assertFalse(Resolver.resolve(fold).diagnostics.stream().anyMatch(d -> d.message().contains("die/dat")),
                "een die op een min/max-operand mag niet als telling-filter gelezen worden");

        String set = """
            Objecttype de Vlucht
                de uitkomst Numeriek (geheel getal);
            Objecttype de Passagier
                de leeftijd Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel m
                geldig altijd
                    De uitkomst van een Vlucht moet berekend worden als de minimale waarde van de leeftijd van alle passagiers van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(set);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        assertTrue(DrlTranspiler.transpile(m).contains("min( $e1.getLeeftijd() )"),
                () -> "min over de set moet een echte accumulate blijven:\n" + DrlTranspiler.transpile(m));
    }

    // ---------------- p24(1): §8.1.8 kenmerk-membership test in a condition ----------------

    @Test void kenmerk_check_in_voorwaarde_gateert_de_regel() {
        // "indien de Meting oud is" gates on the subject's boolean kenmerk. It used to lower to a
        // /* TODO voorwaarde */ marker (the rule never honoured the kenmerk); it now lowers to the
        // boolean property constraint "oud == true" (which resolves to the fact's isOud()).
        String src = """
            Objecttype de Meting
                is oud kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel geefbonus
                geldig altijd
                    Een Meting is bonus
                    indien de Meting oud is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("oud == true"), () -> "kenmerk-check moet een boolean constraint zijn:\n" + drl);
        assertFalse(drl.contains("TODO voorwaarde"), () -> "kenmerk-check mag geen marker meer zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Meting oud = new Meting(); oud.setOud(true);
        Meting jong = new Meting(); jong.setOud(false);
        sessie.insert(oud); sessie.insert(jong);
        sessie.fireAllRules();
        sessie.dispose();
        assertTrue(oud.isBonus(), "een oude Meting moet bonus krijgen");
        assertFalse(jong.isBonus(), "een niet-oude Meting mag geen bonus krijgen");
    }

    @Test void hij_kenmerk_check_gateert_de_regel() {
        // The bezielde "hij" form also denotes the subject: "indien hij oud is".
        String src = """
            Objecttype de Meting
                is oud kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel geefbonus
                geldig altijd
                    Een Meting is bonus
                    indien hij oud is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("oud == true"), () -> "'hij oud is' moet een boolean constraint zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Meting oud = new Meting(); oud.setOud(true);
        sessie.insert(oud);
        sessie.fireAllRules();
        sessie.dispose();
        assertTrue(oud.isBonus(), "een oude Meting moet bonus krijgen via 'hij oud is'");
    }

    @Test void meerwoordig_kenmerk_check_in_definiete_vorm_vuurt() {
        // p24a review: a MULTI-WORD kenmerk in the definite form "de Meting bereikbaar per trein is".
        // The greedy onderwerp parse splits the kenmerk across the operand and the operator, so the
        // resolver reconstructs the full name from the absorbed prefix + the operator's kaleNaam. It
        // lowers to the camelCase boolean property "bereikbaarPerTrein == true" (-> isBereikbaarPerTrein()).
        String src = """
            Objecttype de Meting
                is bereikbaar per trein kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Meting is bonus
                    indien de Meting bereikbaar per trein is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("bereikbaarPerTrein == true"), () -> "meerwoordig kenmerk moet lager komen:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Meting wel = new Meting(); wel.setBereikbaarPerTrein(true);
        Meting niet = new Meting(); niet.setBereikbaarPerTrein(false);
        sessie.insert(wel); sessie.insert(niet);
        sessie.fireAllRules();
        sessie.dispose();
        assertTrue(wel.isBonus(), "een per-trein-bereikbare Meting moet bonus krijgen");
        assertFalse(niet.isBonus(), "een niet-bereikbare Meting mag geen bonus krijgen");
    }

    @Test void een_lidwoord_membership_wordt_geen_stille_kenmerk_gate() {
        // p24a review (MED): "is een <naam>" is an objecttype/role-membership test (§13.4.15.44/.45),
        // NOT a kenmerk reference — even when <naam> also names a subject kenmerk. It must stay an
        // unresolved marker, not be silently gated as the kenmerk (which would be a wrong gate).
        String src = """
            Objecttype de Meting
                de x Numeriek (geheel getal);
            Objecttype de Ding
                is meting kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Ding is bonus
                    indien de Ding een meting is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("meting == true"),
                () -> "'is een meting' mag niet als kenmerk-gate lager komen (objecttype-lidmaatschap):\n" + drl);
    }

    @Test void onopgeloste_voorwaarde_compileert_als_no_op() {
        // p27: an unresolved condition (here an objecttype/role-membership "is een werkdag", not
        // lowered) used to emit a `null` pattern constraint, which is not a boolean and made the
        // WHOLE generated DRL uncompilable. It now lowers to an always-false constraint: the DRL
        // COMPILES (build() asserts it) and the rule is a visible no-op (it never fires).
        String src = """
            Objecttype de Meting
                is bonus kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Meting is bonus
                    indien hij een werkdag is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("false /* TODO voorwaarde"),
                () -> "een onopgeloste voorwaarde moet een compileerbare false-marker zijn:\n" + drl);
        KieSession sessie = build(drl).newKieSession();   // build() FAILS if the DRL does not compile
        Meting m = new Meting();
        sessie.insert(m);
        sessie.fireAllRules();
        sessie.dispose();
        assertFalse(m.isBonus(), "een regel met een onopgeloste voorwaarde mag niet vuren (no-op)");
    }

    @Test void onopgeloste_subvoorwaarde_in_geen_compound_vuurt_niet() {
        // p27 review (HIGH): under a negated quantifier (geen / ten hoogste / precies) an unresolved
        // sub-condition lowered to `false` is DROPPED by the negation, so the rule fired when it must
        // not. The whole compound is now a no-op marker instead. Here: "geen van {oud, werkdag}" with
        // werkdag unresolved — a Meting that is NOT oud would (buggily) satisfy "geen" and fire; it
        // must not, because the werkdag criterion could not be evaluated.
        String src = """
            Objecttype de Meting
                is oud kenmerk (bijvoeglijk);
                is bonus kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    Een Meting is bonus
                    indien hij aan geen van de volgende voorwaarden voldoet:
                    - hij is oud
                    - hij een werkdag is.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("!("), () -> "een compound met een onopgeloste sub mag niet als !(...) lager komen:\n" + drl);
        KieSession sessie = build(drl).newKieSession();
        Meting m = new Meting(); m.setOud(false);   // would buggily satisfy "geen van {oud, …}"
        sessie.insert(m);
        sessie.fireAllRules();
        sessie.dispose();
        assertFalse(m.isBonus(), "een compound met een onopgeloste sub moet een no-op zijn, niet stil vuren");
    }

    @Test void getelde_die_kenmerk_filter_telt_alleen_de_gefilterde_elementen() {
        // p24(2): "het aantal passagiers van de reis die een kind zijn" must count only the kind
        // passagiers. The die-filter resolves to a kenmerk on the counted ELEMENT and lowers to an
        // accumulate-source constraint Passagier( reis == $subject, kind == true ). Fired: 3
        // passagiers, 2 of them kind -> the count is 2, not 3 (the p20b silent-all is gone for real).
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is kind kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een kind zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("kind == true"), () -> "die-filter moet een accumulate-constraint zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setKind(true);
        Passagier b = new Passagier(); b.setReis(v); b.setKind(true);
        Passagier c = new Passagier(); c.setReis(v); c.setKind(false);   // not a kind -> excluded
        sessie.insert(v); sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("2").compareTo(v.getTotaalprijs()),
                () -> "alleen de kinderen tellen mee (2), kreeg " + v.getTotaalprijs());
    }

    @Test void die_heeft_possessie_telt_niet_als_kenmerk_filter() {
        // p24b review (LOW): "die heeft een kind" is a possession/rolcheck query (§8.1.7; a kenmerk
        // is "is/zijn", §8.1.8), NOT the element's boolean kenmerk. It must NOT lower to the
        // same "kind == true" constraint as "die een kind zijn" — it stays an honest marker.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is kind kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die heeft een kind.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertFalse(drl.contains("kind == true"),
                () -> "'die heeft een kind' (possessie) mag niet als kenmerk-constraint lager komen:\n" + drl);
    }

    @Test void som_met_die_kenmerk_filter_telt_alleen_de_gefilterde_elementen() {
        // p29(3): "de som van de prijs van alle passagiers van de reis die een kind zijn" must sum
        // only the kind passagiers. The die-filter generalises beyond AANTAL to a single-operand SOM
        // (it used to silently sum EVERY passagier). Fired: prijs 30 + 70 (kind) + 999 (not kind) ->
        // 100, not 1099.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
                is kind kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel som
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als de som van de prijs van alle passagiers van de reis die een kind zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("kind == true") && drl.contains("sum("),
                () -> "SOM die-filter moet een gefilterde accumulate zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setKind(true);  a.setPrijs(new BigDecimal("30"));
        Passagier b = new Passagier(); b.setReis(v); b.setKind(true);  b.setPrijs(new BigDecimal("70"));
        Passagier c = new Passagier(); c.setReis(v); c.setKind(false); c.setPrijs(new BigDecimal("999"));
        sessie.insert(v); sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("100").compareTo(v.getTotaalprijs()),
                () -> "alleen de prijzen van de kinderen tellen mee (100), kreeg " + v.getTotaalprijs());
    }

    @Test void union_telling_telt_de_vereniging_van_de_gefilterde_sets() {
        // p29(4): §5.8.1 "het aantal X, Y en Z" counts the UNION of the per-operand kenmerk-filtered
        // sets — lowered to ONE accumulate whose source ORs the filters, so an instance in several
        // sets counts once. Used to be a marker. Fired: kind, student, kind+student, neither -> 3.
        String src = """
            Objecttype de Vlucht
                de totaalprijs Numeriek (geheel getal);
            Objecttype de Passagier
                is kind kenmerk (bijvoeglijk);
                is student kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel tel
                geldig altijd
                    De totaalprijs van een reis moet berekend worden als het aantal passagiers van de reis die een kind zijn en passagiers van de reis die een student zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("kind == true") && drl.contains("student == true") && drl.contains("||"),
                () -> "union-telling moet één accumulate met OR-filters zijn:\n" + drl);
        assertFalse(drl.contains("TODO aggregatie"), () -> "union-telling mag geen marker meer zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setKind(true);
        Passagier b = new Passagier(); b.setReis(v); b.setStudent(true);
        Passagier c = new Passagier(); c.setReis(v); c.setKind(true); c.setStudent(true);  // in BOTH sets -> once
        Passagier d = new Passagier(); d.setReis(v);                                        // neither -> excluded
        sessie.insert(v); sessie.insert(a); sessie.insert(b); sessie.insert(c); sessie.insert(d);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("3").compareTo(v.getTotaalprijs()),
                () -> "de vereniging telt elk lid één keer (3), kreeg " + v.getTotaalprijs());
    }

    @Test void feitcreatie_1hop_legt_de_nieuwe_relatie_op_de_passagiers_van_de_vlucht() {
        // §13.4.7/§9.4: a feitcreatie creates an instance of the feittype <rolB> names, relating each
        // anker to the objects filling <rolC> of it. Here the new "bonusrelatie" is created between a
        // Vlucht and each of its passengers (reached by the existing "passagier" relation, reis).
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("setBonusvlucht") && drl.contains("reis == $subject"),
                () -> "feitcreatie moet de nieuwe relatie zetten op de juiste join:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v1 = new Vlucht(), v2 = new Vlucht();
        Passagier p1 = new Passagier(); p1.setReis(v1);
        Passagier p2 = new Passagier(); p2.setReis(v1);
        Passagier p3 = new Passagier(); p3.setReis(v2);
        Passagier zonder = new Passagier();                       // geen vlucht -> geen relatie
        sessie.insert(v1); sessie.insert(v2);
        sessie.insert(p1); sessie.insert(p2); sessie.insert(p3); sessie.insert(zonder);
        sessie.fireAllRules();
        sessie.dispose();

        assertSame(v1, p1.getBonusvlucht(), () -> "passagier van v1 moet aan v1 gekoppeld zijn");
        assertSame(v1, p2.getBonusvlucht(), () -> "tweede passagier van v1 moet aan v1 gekoppeld zijn");
        assertSame(v2, p3.getBonusvlucht(), () -> "passagier van v2 moet aan v2 gekoppeld zijn");
        assertNull(zonder.getBonusvlucht(), () -> "een passagier zonder vlucht krijgt geen relatie");
    }

    @Test void feitcreatie_met_doel_voorwaarde_koppelt_alleen_de_passende_passagiers() {
        // A feitcreatie guard on the doel ("indien de prijs van de passagier groter is dan 18") selects
        // WHICH passengers get the relation; it must host on the $doel pattern and actually fire.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de prijs van de passagier groter is dan 18.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier rijk = new Passagier(); rijk.setReis(v); rijk.setPrijs(new BigDecimal("99"));  // > 18 -> gekoppeld
        Passagier arm = new Passagier(); arm.setReis(v); arm.setPrijs(new BigDecimal("5"));      // <= 18 -> niet
        sessie.insert(v); sessie.insert(rijk); sessie.insert(arm);
        sessie.fireAllRules();
        sessie.dispose();

        assertSame(v, rijk.getBonusvlucht(), () -> "een passagier die aan de voorwaarde voldoet moet gekoppeld zijn");
        assertNull(arm.getBonusvlucht(), () -> "een passagier die niet voldoet mag niet gekoppeld zijn");
    }

    @Test void feitcreatie_met_doel_kenmerk_voorwaarde_koppelt_alleen_de_passende_passagiers() {
        // p32: a feitcreatie guard naming a kenmerk of the navigated DOEL ("indien de passagier student is")
        // now resolves against the doel type and hosts on $doel (student == true), so the relation is created
        // only for the matching passengers — like the comparison guard, no longer an inert no-op.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                is student kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de passagier student is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("student == true"), () -> "doel-kenmerk niet gelowered:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setStudent(true);   // student -> gekoppeld
        Passagier b = new Passagier(); b.setReis(v);                       // geen student -> niet
        sessie.insert(v); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();
        sessie.dispose();

        assertSame(v, a.getBonusvlucht(), () -> "een student-passagier moet gekoppeld zijn");
        assertNull(b.getBonusvlucht(), () -> "een niet-student mag niet gekoppeld zijn");
    }

    @Test void feitcreatie_met_echt_onlowerbare_voorwaarde_creeert_niets_geen_overcreatie() {
        // A guard that lowers to NEITHER anker nor doel — here an objecttype/role-membership test
        // ("indien de passagier een werkdag is", left a marker, p28), not a doel kenmerk — makes the rule
        // inert (eval(false)) rather than dropping the guard. Dropping it would link EVERY passenger
        // (over-creation); inert must link NONE. Better to create nothing visibly than to create wrongly.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de passagier een werkdag is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("eval( false )"), () -> "een echt onlowerbare voorwaarde moet inert zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v);
        Passagier b = new Passagier(); b.setReis(v);
        sessie.insert(v); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();
        sessie.dispose();

        assertNull(a.getBonusvlucht(), () -> "een inerte feitcreatie mag geen relatie creëren (geen over-creatie)");
        assertNull(b.getBonusvlucht(), () -> "een inerte feitcreatie mag geen relatie creëren");
    }

    @Test void feitcreatie_doel_kenmerk_lowert_ook_als_de_rol_anders_heet_dan_het_doeltype() {
        // p32 review (MED): the doel is named by the ROLE it fills ("de klant"), which need NOT equal its
        // type name ("Passagier") — the canonical §9.4/TOKA shape (type "Natuurlijk persoon" filling role
        // "passagier"). The doel re-resolution must recognise "de <rolC>" as denoting the doel, not only a
        // type-name match (which my first cut relied on, masked by tests that named the type = the role).
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                is student kenmerk (bijvoeglijk);
            Feittype Vlucht van klanten
                de reis Vlucht
                de klant Passagier
            Eén reis betreft meerdere klanten
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een klant van de vlucht
                    indien de klant student is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("student == true"), () -> "rol != doeltype: doel-kenmerk niet gelowered:\n" + drl);
        assertFalse(drl.contains("eval( false )"), () -> "mag niet inert zijn als de rol anders heet:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setStudent(true);
        Passagier b = new Passagier(); b.setReis(v);
        sessie.insert(v); sessie.insert(a); sessie.insert(b);
        sessie.fireAllRules();
        sessie.dispose();
        assertSame(v, a.getBonusvlucht(), () -> "een student-klant moet gekoppeld zijn (rol != type)");
        assertNull(b.getBonusvlucht(), () -> "een niet-student mag niet gekoppeld zijn");
    }

    @Test void feitcreatie_hij_kenmerk_verwijst_naar_de_anker_niet_naar_de_doel() {
        // p32 review (MED): "hij" denotes the bezielde ANKER (§5.5.4), not the doel — the doel is written with
        // a definite article ("de passagier"). A guard "indien hij <kenmerk> is" naming a kenmerk the anker
        // does NOT declare must stay inert, NOT be silently re-targeted to the doel (which would create a
        // wrong link). Here the anker (Vlucht) is the only bezield onderwerp; student is only on the doel.
        String src = """
            Objecttype de Vlucht (bezield)
                de naam Tekst;
            Objecttype de Passagier
                is student kenmerk (bijvoeglijk);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien hij student is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("eval( false )"), () -> "een hij-guard op de anker mag niet naar de doel verlegd:\n" + drl);
        assertFalse(drl.contains("student == true"), () -> "het kenmerk mag NIET op de doel hosten:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht v = new Vlucht();
        Passagier a = new Passagier(); a.setReis(v); a.setStudent(true);   // would be wrongly linked by the bug
        sessie.insert(v); sessie.insert(a);
        sessie.fireAllRules();
        sessie.dispose();
        assertNull(a.getBonusvlucht(), () -> "een hij-guard voor de anker mag geen doel-relatie creëren");
    }

    @Test void feitcreatie_meerstaps_legt_de_relatie_over_de_navigatieketen() {
        // §9.4 (Figure 17) — the canonical multi-hop feitcreatie: the doel is reached from the anker over
        // a CHAIN, Contingent -> Vlucht -> passagier. The to-one hop reads the parent's forward reference
        // (Vlucht this == $subject.contingentvlucht), the to-many hop the child's back reference
        // (Passagier reis == $t0); the new relation is set on each reached passagier.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
            Objecttype de Contingent
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype contingentrelatie
                de contingentvlucht Vlucht
                de contingent Contingent
            Eén contingentvlucht heeft meerdere contingenten
            Feittype bonusrelatie
                de bonuscontingent Contingent
                de bonuspassagier Passagier
            Eén bonuscontingent kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een contingent is een passagier van de contingentvlucht van het contingent.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("$t0 : Vlucht( this == $subject.contingentvlucht )"), () -> drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht vlucht = new Vlucht();
        Vlucht andere = new Vlucht();
        Contingent c = new Contingent(); c.setContingentvlucht(vlucht);
        Passagier p1 = new Passagier(); p1.setReis(vlucht);   // op de contingent-vlucht -> gekoppeld
        Passagier p2 = new Passagier(); p2.setReis(vlucht);   // idem
        Passagier q = new Passagier();  q.setReis(andere);    // andere vlucht -> niet
        sessie.insert(vlucht); sessie.insert(andere); sessie.insert(c);
        sessie.insert(p1); sessie.insert(p2); sessie.insert(q);
        sessie.fireAllRules();
        sessie.dispose();

        assertSame(c, p1.getBonuscontingent(), () -> "p1 op de contingent-vlucht moet gekoppeld zijn");
        assertSame(c, p2.getBonuscontingent(), () -> "p2 op de contingent-vlucht moet gekoppeld zijn");
        assertNull(q.getBonuscontingent(), () -> "een passagier van een andere vlucht mag niet gekoppeld zijn");
    }

    @Test void feitcreatie_met_anker_kenmerk_voorwaarde_vuurt_alleen_voor_passende_ankers() {
        // A kenmerk guard on the anker ("indien de vlucht internationaal is") resolves on $subject and
        // gates which flights' passengers get the relation. Passengers of a non-international flight stay
        // unlinked. (Vlucht.internationaal is a boolean kenmerk on the fact.)
        String src = """
            Objecttype de Vlucht
                is internationaal kenmerk (bijvoeglijk);
            Objecttype de Passagier
                de naam Tekst;
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Feittype bonusrelatie
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            Regel ken bonus toe
                geldig altijd
                    Een bonuspassagier van een vlucht is een passagier van de vlucht
                    indien de vlucht internationaal is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("eval( false )"), () -> "anker-kenmerk moet resolven, niet inert:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht intl = new Vlucht(); intl.setInternationaal(true);
        Vlucht binnen = new Vlucht();                                  // niet internationaal
        Passagier p1 = new Passagier(); p1.setReis(intl);
        Passagier p2 = new Passagier(); p2.setReis(binnen);
        sessie.insert(intl); sessie.insert(binnen); sessie.insert(p1); sessie.insert(p2);
        sessie.fireAllRules();
        sessie.dispose();

        assertSame(intl, p1.getBonusvlucht(), () -> "passagier van een internationale vlucht moet gekoppeld zijn");
        assertNull(p2.getBonusvlucht(), () -> "passagier van een binnenlandse vlucht mag niet gekoppeld zijn");
    }

    @Test void navigatie_kenmerk_voorwaarde_vuurt_via_de_to_one_rol() {
        // §8.1.8 navigated: "indien de partner van een Persoon gepensioneerd is" reads the kenmerk through
        // the to-one partner role -> "partner.gepensioneerd == true". Fires only when the partner is
        // gepensioneerd; a person whose partner is not, or who has no partner, is not gated in.
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                is gepensioneerd kenmerk (bijvoeglijk);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de partner van een Persoon gepensioneerd is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("partner.gepensioneerd == true"), () -> "navigated kenmerk niet gelowered:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon gepensioneerde = new Persoon(); gepensioneerde.setGepensioneerd(true);
        Persoon werkende = new Persoon();                                  // niet gepensioneerd
        Persoon a = new Persoon(); a.setPartner(gepensioneerde);           // partner gepensioneerd -> vuurt
        Persoon b = new Persoon(); b.setPartner(werkende);                 // partner werkt -> niet
        Persoon c = new Persoon();                                         // geen partner -> niet
        sessie.insert(gepensioneerde); sessie.insert(werkende); sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("1").compareTo(a.getUitkomst()),
                () -> "een persoon met gepensioneerde partner moet gegated zijn, kreeg " + a.getUitkomst());
        assertNull(b.getUitkomst(), () -> "partner werkt niet -> niet gegated");
        assertNull(c.getUitkomst(), () -> "geen partner -> niet gegated (geen NPE op null-navigatie)");
    }

    @Test void navigatie_eenvorm_kenmerkcheck_bindt_alleen_een_zelfstandig_kenmerk() {
        // §8.1.8.3 navigated EEN-form: "de reis van een Passagier is een reis met paaskorting" reads a ZELFSTANDIG
        // kenmerk through the to-one reis role -> "reis != null && reis.reisMetPaaskorting == true", gating the rule
        // only when the navigated vlucht bears the kenmerk (and never NPEing on a passenger without a reis). The
        // soort gate mirrors the subject's own EEN-form (zelfstandigKenmerk); a bijvoeglijk navigated kenmerk would
        // stay a marker (asserted structurally in DrlTest).
        String src = """
            Objecttype de Vlucht
                is reis met paaskorting kenmerk;
            Objecttype de Passagier
                de prijs Numeriek (geheel getal);
            Feittype Vlucht van passagiers
                de reis Vlucht
                de passagier Passagier
            Eén reis betreft meerdere passagiers
            Regel zelfstandig
                geldig altijd
                    De prijs van een Passagier moet gesteld worden op 1
                    indien de reis van een Passagier een reis met paaskorting is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("reis.reisMetPaaskorting == true"), () -> "navigated zelfstandig EEN-vorm niet gelowerd:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Vlucht paasvlucht = new Vlucht(); paasvlucht.setReisMetPaaskorting(true);
        Vlucht gewoon = new Vlucht();
        Passagier a = new Passagier(); a.setReis(paasvlucht);   // reis is een reis met paaskorting -> gegated
        Passagier b = new Passagier(); b.setReis(gewoon);        // niet
        Passagier c = new Passagier();                           // geen reis -> niet (geen NPE op null-navigatie)
        for (Object o : new Object[]{paasvlucht, gewoon, a, b, c}) sessie.insert(o);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("1").compareTo(a.getPrijs()), () -> "reis met paaskorting -> gegated, kreeg " + a.getPrijs());
        assertNull(b.getPrijs(), "gewone reis -> niet gegated");
        assertNull(c.getPrijs(), "geen reis -> niet gegated (geen NPE op null-navigatie)");
    }

    @Test void negatie_kenmerk_voorwaarde_vuurt_voor_de_niet_instanties() {
        // §8.1.8 negation: "indien de Meting niet oud is" -> "oud == false". Fires for the NOT-oud
        // metingen only.
        String src = """
            Objecttype de Meting
                de uitkomst Numeriek (geheel getal);
                is oud kenmerk (bijvoeglijk);
            Regel r
                geldig altijd
                    De uitkomst van een Meting moet gesteld worden op 1 indien de Meting niet oud is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("oud == false"), () -> "negatie niet gelowered:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Meting jong = new Meting();                    // niet oud -> vuurt
        Meting oud = new Meting(); oud.setOud(true);   // oud -> niet
        sessie.insert(jong); sessie.insert(oud);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("1").compareTo(jong.getUitkomst()),
                () -> "een niet-oude meting moet gegated zijn, kreeg " + jong.getUitkomst());
        assertNull(oud.getUitkomst(), () -> "een oude meting mag niet gegated zijn");
    }

    // ---------------- p36: §8.1.8.2 bezittelijk "geen" negation in a condition ----------------

    /** §8.1.8.2: the bezittelijk negation "heeft geen <kenmerk>" lowers to "== false". The kenmerk is the
     *  multi-word TOKA kenmerk "het recht op duurzaamheidskorting"; both the negation AND its lowered name
     *  exercise the article-strip on the greedily kop-absorbed "het ... op" (there is no GEEN token, so
     *  "geen" survives into the reconstructed name). Fires for the personen WITHOUT the recht. */
    @Test void bezittelijk_geen_negatie_vuurt_voor_de_personen_zonder_het_recht() {
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de Persoon geen recht op duurzaamheidskorting heeft.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rechtOpDuurzaamheidskorting == false"), () -> "bezittelijk geen-negatie niet gelowered:\n" + drl);
        assertFalse(drl.contains("TODO voorwaarde"), () -> "bezittelijk geen-negatie mag geen marker zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon zonder = new Persoon();                                       // geen recht -> vuurt
        Persoon met = new Persoon(); met.setRechtOpDuurzaamheidskorting(true); // heeft recht -> niet
        sessie.insert(zonder); sessie.insert(met);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("1").compareTo(zonder.getUitkomst()),
                () -> "een persoon zonder recht moet gegated zijn, kreeg " + zonder.getUitkomst());
        assertNull(met.getUitkomst(), () -> "een persoon met recht mag niet gegated zijn");
    }

    /** The positive complement: "heeft het recht op duurzaamheidskorting" must lower symmetrically to
     *  "== true". The leading article on the kop-absorbed multi-word kenmerk used to leave it a marker
     *  (an inert no-op), so the positive silently never fired while the negation gated — the asymmetry the
     *  article-strip closes. Fires for the personen WITH the recht. */
    @Test void bezittelijk_positief_meerwoordig_met_lidwoord_vuurt_voor_de_personen_met_het_recht() {
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de Persoon het recht op duurzaamheidskorting heeft.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rechtOpDuurzaamheidskorting == true"), () -> "positief bezittelijk niet gelowered:\n" + drl);
        assertFalse(drl.contains("TODO voorwaarde"), () -> "positief bezittelijk mag geen marker zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon met = new Persoon(); met.setRechtOpDuurzaamheidskorting(true);  // heeft recht -> vuurt
        Persoon zonder = new Persoon();                                         // geen recht -> niet
        sessie.insert(met); sessie.insert(zonder);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("1").compareTo(met.getUitkomst()),
                () -> "een persoon met recht moet gegated zijn, kreeg " + met.getUitkomst());
        assertNull(zonder.getUitkomst(), () -> "een persoon zonder recht mag niet gegated zijn");
    }

    /** The navigated bezittelijk negation: "de partner van een Persoon heeft geen recht …" reads the kenmerk
     *  through the to-one partner role, null-guarded -> "partner != null && partner.rechtOp… == false".
     *  Fires only for a person whose partner exists and lacks the recht. */
    @Test void navigatie_bezittelijk_geen_negatie_vuurt_via_de_to_one_partner() {
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                het recht op duurzaamheidskorting kenmerk (bezittelijk);
            Feittype partnerschap
                de persoon Persoon
                de partner Persoon
            Eén persoon heeft één partner
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de partner van een Persoon geen recht op duurzaamheidskorting heeft.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("partner != null && partner.rechtOpDuurzaamheidskorting == false"),
                () -> "navigated bezittelijk geen-negatie niet gelowered:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        Persoon metRecht = new Persoon(); metRecht.setRechtOpDuurzaamheidskorting(true);
        Persoon zonderRecht = new Persoon();
        Persoon a = new Persoon(); a.setPartner(zonderRecht);  // partner zonder recht -> vuurt
        Persoon b = new Persoon(); b.setPartner(metRecht);     // partner met recht -> niet
        Persoon c = new Persoon();                             // geen partner -> niet (geen NPE)
        sessie.insert(metRecht); sessie.insert(zonderRecht); sessie.insert(a); sessie.insert(b); sessie.insert(c);
        sessie.fireAllRules();
        sessie.dispose();
        assertEquals(0, new BigDecimal("1").compareTo(a.getUitkomst()),
                () -> "partner zonder recht -> gegated, kreeg " + a.getUitkomst());
        assertNull(b.getUitkomst(), () -> "partner met recht -> niet gegated");
        assertNull(c.getUitkomst(), () -> "geen partner -> niet gegated (geen NPE op null-navigatie)");
    }

    /** The noch-noch "is geen <kenmerk>" (§8.1.8.3) stays an honest marker, NOT a silent gate: it is the
     *  negation of "is een <kenmerk>", which is itself deferred for the §13.4.15.44/.45 membership ambiguity
     *  (p28). Lowering only the negation would gate one of a complementary pair while the other is an inert
     *  no-op. So it lowers to a compileerbare false-marker and the rule never fires. */
    @Test void noch_noch_is_geen_negatie_blijft_een_marker_geen_stille_gate() {
        String src = """
            Objecttype de Persoon
                de uitkomst Numeriek (geheel getal);
                de reiziger kenmerk;
            Regel r
                geldig altijd
                    De uitkomst van een Persoon moet gesteld worden op 1
                    indien de Persoon geen reiziger is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("false /* TODO voorwaarde"), () -> "is-geen noch-noch moet een marker blijven:\n" + drl);
        assertFalse(drl.contains("reiziger == false"), () -> "is-geen noch-noch mag niet stil gegated worden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();   // build() FAILS if the marker is not compileerbaar
        Persoon p = new Persoon();
        sessie.insert(p);
        sessie.fireAllRules();
        sessie.dispose();
        assertNull(p.getUitkomst(), "een noch-noch is-geen marker moet een no-op zijn (niet stil vuren)");
    }

    /** §5.8.5 dimensieaggregatie: "de som van zijn betaalde belasting in jaar vanaf vier jaar geleden
     *  t/m een jaar geleden" sums a gedimensioneerd attribuut over a CONTIGUOUS label interval of the
     *  jaardimensie. The labels are static (§13.3.7.5 declared order), so the transpiler folds map.get(label)
     *  over exactly positions 3..6 — the out-of-interval labels ("vijf jaar geleden", "heden") must NOT
     *  contribute. p5 slice: this used to drop the selectie and emit a marker (an unrestricted no-op). */
    @Test void dimensieaggregatie_som_vanaf_tm_telt_alleen_het_interval() {
        String src = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. zes jaar geleden
                2. vijf jaar geleden
                3. vier jaar geleden
                4. drie jaar geleden
                5. twee jaar geleden
                6. een jaar geleden
                7. heden
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar vanaf vier jaar geleden t/m een jaar geleden.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        // the dimensieselectie now RESOLVES, so the "nog niet omgezet" marker WARNING must be gone.
        assertTrue(m.diagnostics.stream().noneMatch(d -> d.message().contains("dimensieselectie")),
                () -> "dimensieselectie zou nu opgelost moeten zijn, geen marker-WARNING: " + m.diagnostics);
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("rsDimSom("), () -> "verwacht een dimensie-som helper-aanroep:\n" + drl);
        assertFalse(drl.contains("TODO aggregatie"), () -> "de aggregatie mag geen marker meer zijn:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        NatuurlijkPersoon np = new NatuurlijkPersoon();
        Map<String, BigDecimal> belasting = new HashMap<>();
        belasting.put("zes jaar geleden", new BigDecimal("11"));    // buiten interval
        belasting.put("vijf jaar geleden", new BigDecimal("22"));   // buiten interval
        belasting.put("vier jaar geleden", new BigDecimal("100"));  // [interval
        belasting.put("drie jaar geleden", new BigDecimal("200"));  //  interval
        belasting.put("twee jaar geleden", new BigDecimal("300"));  //  interval
        belasting.put("een jaar geleden", new BigDecimal("400"));   //  interval]
        belasting.put("heden", new BigDecimal("9999"));             // buiten interval
        np.setBetaaldeBelastingInJaar(belasting);
        sessie.insert(np);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("1000").compareTo(np.getBetaaldeBelastingOverDeAfgelopenVierJaar()),
                () -> "som over [vier..een jaar geleden] = 100+200+300+400 = 1000, kreeg "
                        + np.getBetaaldeBelastingOverDeAfgelopenVierJaar());
    }

    /** §5.8.2 over a dimensieselectie: a label with no value (map.get -> null) is SKIPPED, not 0, and a
     *  partial interval still sums the present labels. (An all-empty interval would be a leegwaarde/null.) */
    @Test void dimensieaggregatie_som_slaat_lege_labels_over() {
        String src = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. zes jaar geleden
                2. vijf jaar geleden
                3. vier jaar geleden
                4. drie jaar geleden
                5. twee jaar geleden
                6. een jaar geleden
                7. heden
            Regel Totaal betaalde belasting over 4 jaar
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet berekend worden als de som van zijn betaalde belasting in jaar vanaf vier jaar geleden t/m een jaar geleden.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);

        KieSession sessie = build(drl).newKieSession();
        NatuurlijkPersoon np = new NatuurlijkPersoon();
        Map<String, BigDecimal> belasting = new HashMap<>();
        belasting.put("drie jaar geleden", new BigDecimal("200"));  // vier/twee/een afwezig -> overgeslagen
        belasting.put("een jaar geleden", new BigDecimal("400"));
        np.setBetaaldeBelastingInJaar(belasting);
        sessie.insert(np);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("600").compareTo(np.getBetaaldeBelastingOverDeAfgelopenVierJaar()),
                () -> "200 + 400 (lege labels overgeslagen) = 600, kreeg "
                        + np.getBetaaldeBelastingOverDeAfgelopenVierJaar());
    }

    /** §3.6/§3.8 a single dimensioned READ: "zijn betaalde belasting in jaar van huidig jaar" reads exactly
     *  the one cell of the gedimensioneerd attribuut (the Map keyed by the jaardimensie label "huidig jaar"),
     *  not the whole map and not another label. The voorzetsel "van" + label is peeled off the attribute run
     *  and lowered to map.get("huidig jaar"). */
    @Test void dimensie_cel_lezen_pakt_het_juiste_label() {
        String src = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Regel lees
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet gesteld worden op zijn betaalde belasting in jaar van huidig jaar.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains(".get(\"huidig jaar\")"), () -> "verwacht een cel-lezing op het label:\n" + drl);

        KieSession sessie = build(drl).newKieSession();
        NatuurlijkPersoon np = new NatuurlijkPersoon();
        Map<String, BigDecimal> belasting = new HashMap<>();
        belasting.put("vorig jaar", new BigDecimal("111"));
        belasting.put("huidig jaar", new BigDecimal("222"));
        np.setBetaaldeBelastingInJaar(belasting);
        sessie.insert(np);
        sessie.fireAllRules();
        sessie.dispose();

        assertEquals(0, new BigDecimal("222").compareTo(np.getBetaaldeBelastingOverDeAfgelopenVierJaar()),
                () -> "moet de cel 'huidig jaar' (222) lezen, niet 'vorig jaar' of de hele map, kreeg "
                        + np.getBetaaldeBelastingOverDeAfgelopenVierJaar());
    }

    /** §3.6 review (HIGH): a dimensioned read on the CONDITION side must not emit a bare Map-vs-number
     *  comparison ("betaaldeBelastingInJaar > 5") — that is a type error that sinks the WHOLE DRL build. An
     *  under-specified bare read and a (deferred) labeled cell read in a voorwaarde both lower to a compilable
     *  always-false marker, so the package builds and the rule is an honest no-op rather than uncompilable. */
    @Test void gedimensioneerde_lezing_in_voorwaarde_is_bouwbaar_en_geen_stille_misvuur() {
        String src = """
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)
                de betaalde belasting over de afgelopen vier jaar Numeriek (geheel getal);
                de betaalde belasting in jaar Numeriek (geheel getal) gedimensioneerd met jaardimensie;
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            Regel bare
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet gesteld worden op 7 indien zijn betaalde belasting in jaar groter is dan 5.
            Regel cel
              geldig altijd
                De betaalde belasting over de afgelopen vier jaar van een Natuurlijk persoon moet gesteld worden op 8 indien zijn betaalde belasting in jaar van huidig jaar groter is dan 5.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertFalse(drl.contains("betaaldeBelastingInJaar >"), () -> "een kale Map-vergelijking is onbouwbaar:\n" + drl);
        assertTrue(drl.contains("false /* TODO voorwaarde"), () -> "de gedimensioneerde lezing moet een marker-guard worden:\n" + drl);

        KieSession sessie = build(drl).newKieSession();   // build() FAILS (whole package) if a bare Map comparison leaks
        NatuurlijkPersoon np = new NatuurlijkPersoon();    // cel 'huidig jaar' = 10 (> 5), maar de voorwaarde is een no-op
        Map<String, BigDecimal> mh = new HashMap<>(); mh.put("huidig jaar", new BigDecimal("10")); np.setBetaaldeBelastingInJaar(mh);
        sessie.insert(np);
        sessie.fireAllRules();
        sessie.dispose();

        assertNull(np.getBetaaldeBelastingOverDeAfgelopenVierJaar(),
                "een gedimensioneerde lezing in een voorwaarde is (nog) een honest marker -> de regel mag niet vuren");
    }
}
