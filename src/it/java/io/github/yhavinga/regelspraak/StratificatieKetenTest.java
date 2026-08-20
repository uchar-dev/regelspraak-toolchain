package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §9.2 THE TWO-LAYER NEGATION CHAIN (the red case behind the {@code Stratificatie} derivation, uchar
 * PHASE4-DEFAULTS §5 / the WF-046 strata principle as a lowering).
 *
 * <p>"Een Bemanningslid is reservist indien hij niet inzetbaar is." over "Een Bemanningslid is inzetbaar
 * indien hij niet afgekeurd is." — two negation layers over one derived kenmerk. The former one-level
 * heuristic sank BOTH rules to salience -1: within that band the agenda follows source order, so with the
 * reservist-consumer FIRST in the source it fired in the transient window (inzetbaar not yet set), wrote the
 * once-true kenmerk, and the fact ended BOTH inzetbaar AND reservist — a contradiction the monotone kenmerk
 * write can never retract. The derived stratification sinks the consumer one level deeper (-2), so the
 * producer's whole layer quiesces first.
 *
 * <p>The test drives REAL source through parser/resolver, fires BOTH back-ends (single-runtime principle),
 * and asserts the fixpoint reading: no afgekeurd → inzetbaar, NOT reservist.
 */
class StratificatieKetenTest {

    /** The consumer FIRST in the source: exactly the order that broke under the one-level heuristic. */
    private static final String BRON = """
            Objecttype de Bemanningslid (mv: Bemanningsleden)
                de leeftijd Numeriek (geheel getal);
                is afgekeurd kenmerk (bijvoeglijk);
                is inzetbaar kenmerk (bijvoeglijk);
                is reservist kenmerk (bijvoeglijk);
            Regel R1
                geldig altijd
                Een Bemanningslid is reservist indien hij niet inzetbaar is.
            Regel R2
                geldig altijd
                Een Bemanningslid is inzetbaar indien hij niet afgekeurd is.
            """;

    @Test
    void tweeLaagsNegatieketenBereiktHetFixpuntInBeideRoutes() throws Exception {
        RegelSpraakModel m = Resolver.resolve(BRON);
        ClassLoader cl = FactModelGeneratorTest.compileer(FactModelGenerator.genereer(m));

        for (String route : new String[] { "DRL", "EXEC" }) {
            KieBase kb;
            if (route.equals("DRL")) {
                DrlTranspiler t = DrlTranspiler.build(m);
                KieHelper h = new KieHelper();
                h.setClassLoader(cl);
                h.addContent(t.drl(), ResourceType.DRL);
                assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                        () -> "DRL compileert niet:\n" + h.verify().getMessages());
                kb = h.build();
            } else {
                kb = KieBaseBuilder.createKieBaseFromModel(ExecModelTranspiler.build(m, cl).model());
            }

            Object lid = Class.forName(FactModelGenerator.PAKKET + ".Bemanningslid", true, cl)
                    .getDeclaredConstructor().newInstance();
            KieSession s = kb.newKieSession();
            try {
                s.insert(lid);
                int fired = s.fireAllRules(10_000);
                assertTrue(fired < 10_000, route + ": fire-cap (regel-lus)");
                assertEquals(Boolean.TRUE, kenmerk(lid, "inzetbaar"),
                        route + ": niet afgekeurd, dus inzetbaar hoort WAAR te zijn");
                assertNotEquals(Boolean.TRUE, kenmerk(lid, "reservist"),
                        route + ": inzetbaar geldt aan het fixpunt, dus reservist mag NIET gezet zijn — "
                                + "een consumer die in het transiente venster vuurde is de §9.2-keten-bug");
            } finally {
                s.dispose();
            }
        }
    }

    private static Object kenmerk(Object bean, String naam) throws Exception {
        for (Method mm : bean.getClass().getMethods())
            if (mm.getParameterCount() == 0
                    && (mm.getName().equalsIgnoreCase("is" + naam) || mm.getName().equalsIgnoreCase("get" + naam)))
                return mm.invoke(bean);
        fail("geen getter voor kenmerk " + naam);
        return null;
    }
}
