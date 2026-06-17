package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.facts.Passagier;
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
}
