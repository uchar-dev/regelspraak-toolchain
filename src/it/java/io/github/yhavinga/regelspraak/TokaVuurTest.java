package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TOKA casus fired END-TO-END: the real object model (plus the examples/toka-reconciliatie
 * extensie for the casus's own undeclared members), {@link FactModelGenerator} beans compiled
 * in-memory, the generated DRL fired in a real session — asserting ABSOLUTE values, never
 * back-end parity. The verdeling expectations are the spec's own §9.7 worked examples
 * (the specificatie is leading; the casus document carries no numbers): §9.7.1 gelijke delen
 * 1000/2→500 elk; §9.7.4 afronding 1001 naar rato 3:2 → 600/400 rest 1; §9.7.5 Tabel 18
 * 1800 → 500/500/433/250/91 rest 26. The spec fixes NO parameter values, so the tax-chain
 * scenario chooses them and computes the expectation by hand from the toka-45/toka-24 formulas.
 * Scenario-sliced: the six verdelingen are §9.7 VARIANTS all writing the same doel-attribuut,
 * so each scenario loads the object model plus exactly the rules under test.
 */
class TokaVuurTest {

    /** The TOKA object model: reconciliation + every non-rule fixture (declarations only). */
    private static final String MODEL;
    static {
        try (Stream<Path> files = Files.list(Path.of("examples/toka"))) {
            MODEL = Files.readString(Path.of("examples/toka-reconciliatie/casus-omissies.rgs")) + "\n"
                    + files.filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".rgs") && !n.contains("-regel-") && !n.contains("beslistabel");
                        }).sorted()
                        .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                        .collect(Collectors.joining("\n"));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static String regels(String... nummers) {
        StringBuilder b = new StringBuilder(MODEL);
        for (String nr : nummers) {
            try (Stream<Path> files = Files.list(Path.of("examples/toka"))) {
                b.append("\n").append(files.filter(p -> p.getFileName().toString().startsWith("toka-" + nr + "-"))
                        .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                        .collect(Collectors.joining("\n")));
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return b.toString();
    }

    /** Resolve → generate beans → compile in-memory → contract-validate → build the DRL → open a session. */
    private record Wereld(KieSession sessie, ClassLoader cl) {
        Object nieuw(String klasse) throws Exception {
            return Class.forName(FactModelGenerator.PAKKET + "." + klasse, true, cl).getConstructor().newInstance();
        }
        void zet(Object o, String setter, Object waarde, Class<?> type) throws Exception {
            o.getClass().getMethod(setter, type).invoke(o, waarde);
        }
        void zet(Object o, String setter, int waarde) throws Exception {
            zet(o, setter, new BigDecimal(waarde), BigDecimal.class);
        }
        Object lees(Object o, String getter) throws Exception { return o.getClass().getMethod(getter).invoke(o); }
        BigDecimal getal(Object o, String getter) throws Exception { return (BigDecimal) lees(o, getter); }
    }

    private static Wereld wereld(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "TOKA-scenario hoort te resolven: " + m.errors());
        ClassLoader cl = FactModelGeneratorTest.compileer(FactModelGenerator.genereer(m));
        var schendingen = ContractValidator.valideer(m, cl);
        assertTrue(schendingen.isEmpty(),
                () -> "gegenereerde beans moeten het contract halen:\n  " + String.join("\n  ", schendingen));
        KieHelper h = new KieHelper();
        h.setClassLoader(cl);
        h.addContent(DrlTranspiler.transpile(m), ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "TOKA-DRL compileert niet: " + h.verify().getMessages());
        KieBase kb = h.build();
        return new Wereld(kb.newKieSession(), cl);
    }

    /** A contingent + n rechthebbenden joined via the te-verdelen rol; returns {contingent, p1, …, pn}. */
    private static Object[] contingentMet(Wereld w, int totaal, int aantal) throws Exception {
        Object c = w.nieuw("ContingentTreinmiles");
        w.zet(c, "setTotaalAantalTreinmiles", totaal);
        Object[] uit = new Object[aantal + 1];
        uit[0] = c;
        Class<?> ct = Class.forName(FactModelGenerator.PAKKET + ".ContingentTreinmiles", true, w.cl());
        for (int i = 1; i <= aantal; i++) {
            Object p = w.nieuw("NatuurlijkPersoon");
            w.zet(p, "setTeVerdelenContingentTreinmiles", c, ct);
            uit[i] = p;
        }
        return uit;
    }

    private static void vuur(Wereld w, Object... facts) {
        java.util.Map<String, Integer> telling = new java.util.TreeMap<>();
        w.sessie().addEventListener(new org.kie.api.event.rule.DefaultAgendaEventListener() {
            @Override public void afterMatchFired(org.kie.api.event.rule.AfterMatchFiredEvent e) {
                telling.merge(e.getMatch().getRule().getName(), 1, Integer::sum);
            }
        });
        for (Object f : facts) w.sessie().insert(f);
        int n = w.sessie().fireAllRules(10_000);
        w.sessie().dispose();
        assertTrue(n < 10_000, () -> "regel-loop: " + telling);
    }

    private static void assertGetal(int verwacht, BigDecimal werkelijk, String wat) {
        assertNotNull(werkelijk, () -> wat + ": verwacht " + verwacht + ", kreeg leeg");
        assertEquals(0, new BigDecimal(verwacht).compareTo(werkelijk), () -> wat + ": verwacht " + verwacht + ", kreeg " + werkelijk);
    }

    @Test void verdeling_gelijke_delen_spec_971() throws Exception {
        // §9.7.1 (spec L4066): contingent 1000 over 2 rechthebbenden in gelijke delen -> 500 elk.
        Wereld w = wereld(regels("32"));
        Object[] f = contingentMet(w, 1000, 2);
        vuur(w, f);
        assertGetal(500, w.getal(f[1], "getTreinmiles"), "gelijke delen p1");
        assertGetal(500, w.getal(f[2], "getTreinmiles"), "gelijke delen p2");
    }

    @Test void verdeling_naar_rato_met_afronding_en_rest_spec_974() throws Exception {
        // §9.7.4 (spec L4147): contingent 1001 naar rato woonregio factor 3:2, afgerond naar beneden ->
        // 600 (Tom) / 400 (Maria), onverdeelde rest 1 op het contingent.
        Wereld w = wereld(regels("36"));
        Object[] f = contingentMet(w, 1001, 2);
        w.zet(f[1], "setWoonregioFactor", 3);
        w.zet(f[2], "setWoonregioFactor", 2);
        vuur(w, f);
        assertGetal(600, w.getal(f[1], "getTreinmiles"), "naar rato Tom (3/5 van 1001, floor)");
        assertGetal(400, w.getal(f[2], "getTreinmiles"), "naar rato Maria (2/5 van 1001, floor)");
        assertGetal(1, w.getal(f[0], "getRestantNaVerdeling"), "onverdeelde rest");
    }

    @Test void verdeling_op_volgorde_met_maximum_en_afronding_tabel_18() throws Exception {
        // §9.7.5 Tabel 18 (spec L4174-4192): contingent 1800, op volgorde van (jongste) leeftijd, naar rato
        // woonregio factor, met maximum en afronding -> Piet 500, Jan 500, Klaas 433, Nel 250, Ria 91; rest 26.
        Wereld w = wereld(regels("37"));
        Object[] f = contingentMet(w, 1800, 5);
        int[][] p = { {22, 3, 500}, {39, 2, 500}, {39, 1, 500}, {66, 3, 250}, {66, 1, 250} };  // leeftijd, factor, max
        for (int i = 0; i < 5; i++) {
            w.zet(f[i + 1], "setLeeftijd", p[i][0]);
            w.zet(f[i + 1], "setWoonregioFactor", p[i][1]);
            w.zet(f[i + 1], "setMaximaalAantalTeOntvangenTreinmiles", p[i][2]);
        }
        vuur(w, f);
        int[] verwacht = {500, 500, 433, 250, 91};
        String[] naam = {"Piet", "Jan", "Klaas", "Nel", "Ria"};
        for (int i = 0; i < 5; i++)
            assertGetal(verwacht[i], w.getal(f[i + 1], "getTreinmiles"), "Tabel 18 " + naam[i]);
        assertGetal(26, w.getal(f[0], "getRestantNaVerdeling"), "Tabel 18 onverdeelde rest");
    }

    @Test void belastingketen_afstand_weigert_dimensioneel_inconsistent() {
        // Sinds besluit 16Y (2026-08-29, uchar eenheid-identiteit-typering, gevendorde frontend):
        // toka-45 rekent een unitloos Bedrag MIN (unitloos tarief x afstand-in-km). De typering
        // maakt het product km, waarna de min de §4-eenheidsregel raakt — de casus is dimensioneel
        // inconsistent zoals geschreven; de oude Onbekend-degradatie maskeerde dit en de oude
        // vuurketen (39 -> 45 -> 24, met de hand 60 -> 50) is daarmee onspeelbaar. Deze test is de
        // vuurzijde-getuige van de weigering: precies de min-fouten, niets anders, geen wereld.
        // De §9.6-initialisatie-guard houdt zijn eigen getuige hieronder. Spiegelt de gate-11-
        // holdout toka-45 en de CasusTest-quirklijst.
        RegelSpraakModel m = Resolver.resolve(regels("39", "45", "24", "31"));
        var minFout = (java.util.function.Predicate<String>) msg ->
                msg.contains("min:") && msg.contains("moeten beide er een hebben");
        assertFalse(m.errors().isEmpty(), "toka-45 hoort sinds 16Y te weigeren");
        assertTrue(m.errors().stream().allMatch(d -> minFout.test(d.message())),
                () -> "verwachtte uitsluitend de min-eenheidsfouten (toka-45): " + m.errors());
    }

    @Test void initialisatie_vult_wanneer_geen_berekening_geladen_is() throws Exception {
        // toka-31 alleen: het lege doel krijgt de initiele belasting (§9.6).
        Wereld w = wereld(regels("31"));
        w.sessie().setGlobal("initieleBelasting", new BigDecimal("25.00"));
        Object p = w.nieuw("NatuurlijkPersoon");
        vuur(w, p);
        assertGetal(25, w.getal(p, "getTeBetalenBelasting"), "toka-31 initialisatie");
    }

    // ---- CARD V1 MEASUREMENT: toka-22/25 under the EXEC back-end after the shared-naming fix. Root cause was that
    //      the exec/runtime accessor-name derivation (RegelSpraakRuntime.pascal) split a name on whitespace ONLY,
    //      leaving the "-" in a koppelteken attribute and targeting a getter that does not exist — so toka-22
    //      ("de datum-tijd voor het berekenen…") and toka-25 ("de verwachte datum-tijd van aankomst") could not
    //      compile under exec. pascal now delegates to kern.Namen.ident (split on every non-letter/digit run), the
    //      SAME authority the DRL back-end and FactModelGenerator use. This test builds each rule under the exec
    //      transpiler and asserts NO skip reason is a koppelteken getter/accessor mismatch. (The exec transpiler's
    //      factClass resolves facts.* from ITS OWN classloader — the precompiled TOKA fact beans lack toka-22/25's
    //      own DatumTijd members, so any residual skip here is the fase-3 classloader/bean-shape gap, NOT the naming
    //      bug this card's fase 1 closes; the koppelteken proof is phase_KOPPELTEKEN in ExecModelDifferentialTest.) ----
    @Test void toka_22_25_geen_koppelteken_getter_skip_onder_exec() {
        for (String nr : new String[]{"22", "25"}) {
            RegelSpraakModel m = Resolver.resolve(regels(nr));
            assertTrue(m.errors().isEmpty(), () -> "toka-" + nr + " hoort te resolven: " + m.errors());
            // The exec transpiler resolves facts.* from ITS OWN classloader; the precompiled TOKA fact beans do NOT
            // declare toka-22/25's own DatumTijd members, so exec's eager typed-accessor wiring throws a bean-SHAPE
            // miss. That residual is the fase-3 classloader/bean-shape gap. What fase 1 fixed is the getter NAME: the
            // miss must be reported against the attribute's REAL member name (kern.Namen.ident collapses the "-"), NOT
            // a mangled getter that still carries the koppelteken ("getDatum-tijd…"). We assert exactly that shape.
            try {
                var em = io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler.build(m);
                System.out.println("toka-" + nr + " exec overgeslagen: " + em.overgeslagen());
                for (String reden : em.overgeslagen())
                    assertFalse(reden.contains("getDatum-tijd") || reden.contains("Datum-tijd"),
                            "toka-" + nr + ": koppelteken lekt in een afgeleide getter-naam: " + reden);
            } catch (RuntimeException ex) {
                String msg = String.valueOf(ex.getMessage());
                System.out.println("toka-" + nr + " exec build faalt (fase-3 bean-shape gap): " + msg);
                // the koppelteken NAME fix: the derived getter/accessor must NOT still carry a "-" (would read
                // "getDatum-tijd…"); the residual is a missing MEMBER on the precompiled bean, not a mangled name.
                assertFalse(msg.contains("getDatum-tijd") || msg.contains("Datum-tijdVoor"),
                        "toka-" + nr + ": koppelteken lekt in de afgeleide getter-naam: " + msg);
            }
        }
    }

    @Test void eerste_van_verwachte_en_daadwerkelijke_vertrektijd() throws Exception {
        // toka-22: de eerste (vroegste) van de verwachte en de daadwerkelijke datum-tijd van vertrek —
        // de daadwerkelijke (09:30) ligt voor de verwachte (10:00), dus die wint.
        Wereld w = wereld(regels("22"));
        Object vlucht = w.nieuw("Vlucht");
        LocalDateTime verwacht = LocalDateTime.of(2024, 6, 15, 10, 0), echt = LocalDateTime.of(2024, 6, 15, 9, 30);
        w.zet(vlucht, "setVerwachteDatumTijdVanVertrek", verwacht, LocalDateTime.class);
        w.zet(vlucht, "setDaadwerkelijkeDatumTijdVanVertrek", echt, LocalDateTime.class);
        vuur(w, vlucht);
        assertEquals(echt, w.lees(vlucht, "getDatumTijdVoorHetBerekenenVanDeBelastingOpBasisVanAfstand"),
                "toka-22: de vroegste van de twee vertrektijden");
    }
}
