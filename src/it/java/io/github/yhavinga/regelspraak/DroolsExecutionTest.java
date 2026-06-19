package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Bestelling;
import io.github.yhavinga.regelspraak.facts.Bestemming;
import io.github.yhavinga.regelspraak.facts.Burger;
import io.github.yhavinga.regelspraak.facts.Deelnemer;
import io.github.yhavinga.regelspraak.facts.Factuur;
import io.github.yhavinga.regelspraak.facts.Heffing;
import io.github.yhavinga.regelspraak.facts.Inconsistentie;
import io.github.yhavinga.regelspraak.facts.Kandidaat;
import io.github.yhavinga.regelspraak.facts.Konto;
import io.github.yhavinga.regelspraak.facts.Luchthaven;
import io.github.yhavinga.regelspraak.facts.Meting;
import io.github.yhavinga.regelspraak.facts.Passagier;
import io.github.yhavinga.regelspraak.facts.Periode;
import io.github.yhavinga.regelspraak.facts.Persoon;
import io.github.yhavinga.regelspraak.facts.Pot;
import io.github.yhavinga.regelspraak.facts.Reis;
import io.github.yhavinga.regelspraak.facts.Reiziger;
import io.github.yhavinga.regelspraak.facts.Route;
import io.github.yhavinga.regelspraak.facts.Trip;
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
        assertTrue(drl.contains(")).setScale(0, java.math.RoundingMode.FLOOR) )"),
                () -> "de afronding moet de hele begrensde expressie omsluiten:\n" + drl);

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
        assertTrue(drl.contains("($acc1 * factor)"), () -> "de maal moet buiten de telling binden:\n" + drl);
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
        assertTrue(drl.contains("($acc1 * aantalPuntenPerPassagier)"),
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
}
