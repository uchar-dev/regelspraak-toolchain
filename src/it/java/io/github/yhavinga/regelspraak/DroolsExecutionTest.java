package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Bestelling;
import io.github.yhavinga.regelspraak.facts.Burger;
import io.github.yhavinga.regelspraak.facts.Deelnemer;
import io.github.yhavinga.regelspraak.facts.Factuur;
import io.github.yhavinga.regelspraak.facts.Meting;
import io.github.yhavinga.regelspraak.facts.Passagier;
import io.github.yhavinga.regelspraak.facts.Periode;
import io.github.yhavinga.regelspraak.facts.Pot;
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
}
