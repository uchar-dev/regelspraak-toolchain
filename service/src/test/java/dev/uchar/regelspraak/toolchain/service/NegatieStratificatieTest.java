package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Invoer;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;

import org.junit.jupiter.api.Test;
import org.kie.api.runtime.KieSession;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIFFERENTIAL: the HTTP dienst-pad ({@link BeslisserKern}) must reproduce the exec-model firing on a
 * kenmerk-NEGATION dependency EXACTLY (spec-honest identity of the two execution paths — the EVAL-RAPPORT
 * §4.2 klasse-II defect: for the scenario s08/s21 shape the dienst-pad returned the logically-impossible
 * {@code geblokkeerd door maandloon = true} AND {@code herleefbaar = true}, while the exec-model path
 * correctly derives {@code herleefbaar = false}).
 *
 * <p>The minimal reproduction mirrors hww-13/hww-15: rule B ({@code Blokkade door maandloon}) writes the
 * kenmerk {@code geblokkeerd}; rule A ({@code Kenmerktoekenning herleefbaar}) asserts {@code herleefbaar}
 * only when the subject is <b>niet</b> {@code geblokkeerd}. When B fires (maandloon over the grens), A must
 * NOT fire — the negation-consumer must observe B's write, not the stale default {@code false}.
 */
class NegatieStratificatieTest {

    /** The objecttype foundation (a prelude file — declares NO rules). */
    static final String FUNDAMENT = """
            Objecttype de Werknemer (mv: Werknemers)
                het maandloon nieuw Numeriek (getal);
                is geblokkeerd kenmerk (bijvoeglijk);
                is herleefbaar kenmerk (bijvoeglijk);
            """;

    /** The PRODUCER file (mirrors hww-13): writes the kenmerk {@code geblokkeerd}. */
    static final String PRODUCENT = """
            Regel Blokkade door maandloon
                geldig altijd
                    Een Werknemer is geblokkeerd
                    indien de maandloon nieuw van de Werknemer groter is dan 100.
            """;

    /** The CONSUMER file (mirrors hww-15): asserts {@code herleefbaar} only when NOT {@code geblokkeerd}. */
    static final String CONSUMENT = """
            Regel Kenmerktoekenning herleefbaar
                geldig altijd
                    Een Werknemer is herleefbaar
                    indien de Werknemer niet geblokkeerd is.
            """;

    /** The FULL source with the CONSUMER declared BEFORE its producer — the ADVERSARIAL order. Without the §9.2
     *  negation-stratification salience the consumer fires in the transient window (geblokkeerd still default-false)
     *  and sets herleefbaar=true, which the monotonic kenmerktoekenning never un-sets. The salience lift makes the
     *  producer out-prioritise the consumer regardless of declaration/agenda order, so both back-ends and both
     *  paths reach the fixpoint verdict (herleefbaar=false). */
    static final String SRC = FUNDAMENT + CONSUMENT + PRODUCENT;

    /** The dienst descriptor: ONE entrypoint scoping BOTH rules (so the producer is in scope — the scope is
     *  complete). The consumer is listed FIRST in the scope (as the real domain descriptor lists
     *  {@code Kenmerktoekenning herleefbaar} before {@code Blokkade door maandloon}). The maandloon is a
     *  caller-supplied element; herleefbaar and geblokkeerd are outputs. */
    static final String DESCRIPTOR = """
            { "dienst": { "naam": "negatie-test", "versie": "1" },
              "entrypoints": [
                { "naam": "herleefbaar", "pad": "herleefbaar",
                  "scope": { "regelnamen": [ "Kenmerktoekenning herleefbaar", "Blokkade door maandloon" ] },
                  "invoer": [ "Werknemer" ],
                  "uitvoer": [ "Werknemer:herleefbaar", "Werknemer:geblokkeerd" ] } ] }
            """;

    /** The EXEC-model reference: build the FULL-model KieBase through uchar and fire the same fact through uchar's
     *  entry. This is the ORACLE — it must (and does) derive herleefbaar=false when geblokkeerd is set. */
    @Test
    void exec_model_leidt_herleefbaar_false_af_wanneer_geblokkeerd() throws Exception {
        RegelSpraakModel model = Resolver.resolve(SRC);
        assertTrue(model.errors().isEmpty(), () -> "bron moet schoon resolven: " + model.errors());
        DroolsBackend.Build build = UcharBouw.bouw(model, DroolsBackend.Branch.EXEC_MODEL);
        ClassLoader cl = build.beanLoader();

        Class<?> wn = Class.forName(FactModelGenerator.PAKKET + ".Werknemer", true, cl);
        Object bean = wn.getConstructor().newInstance();
        wn.getMethod("setMaandloonNieuw", BigDecimal.class).invoke(bean, new BigDecimal("150"));

        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        try {
            KieSession s = build.kieBase().newKieSession();
            try {
                DroolsBackend.voegIn(build, s, new Invoer().bean(bean));
                assertNull(DroolsBackend.vuur(build, s).geenFixpunt(), "de sessie bereikt haar fixpunt");
                DroolsBackend.zetAfgeleideWaarden(build, s);
            } finally { s.dispose(); }
        } finally { Thread.currentThread().setContextClassLoader(vorige); }

        boolean geblokkeerd = (Boolean) wn.getMethod("isGeblokkeerd").invoke(bean);
        boolean herleefbaar = (Boolean) wn.getMethod("isHerleefbaar").invoke(bean);
        assertTrue(geblokkeerd, "B moet vuren: maandloon 150 > 100");
        assertFalse(herleefbaar, "exec-model ORACLE: herleefbaar mag NIET waar zijn als geblokkeerd waar is");
    }

    /** The DIENST path over the SAME rules + SAME fact: must match the exec-model oracle (herleefbaar=false).
     *  RED before the fix (the dienst-pad returned herleefbaar=true — the negation read the stale default). */
    @Test
    void dienst_pad_reproduceert_de_exec_model_negatie() {
        // Files in the ADVERSARIAL order: consumer before producer (as the descriptor scope also lists them).
        // Neither the file order nor the scope order may decide the verdict — the §9.2 salience must.
        Map<String, String> bron = new LinkedHashMap<>();
        bron.put("00-fundament.rgs", FUNDAMENT);
        bron.put("15-consument.rgs", CONSUMENT);
        bron.put("13-producent.rgs", PRODUCENT);
        BeslisserKern kern = BeslisserKern.bouw(DESCRIPTOR, bron, KernConfig.strikt(),
                Thread.currentThread().getContextClassLoader());

        String verzoek = """
            { "instanties": [
                { "type": "Werknemer", "id": "W1", "attributen": { "maandloon nieuw": 150 } } ] }""";
        ObjectNode antwoord = kern.verwerk("herleefbaar", parse(verzoek));

        JsonNode geblokkeerd = vind(antwoord, "W1", "kenmerken", "geblokkeerd");
        JsonNode herleefbaar = vind(antwoord, "W1", "kenmerken", "herleefbaar");
        assertTrue(geblokkeerd != null && geblokkeerd.asBoolean(), "B moet vuren: " + antwoord);
        assertTrue(herleefbaar != null, "herleefbaar hoort in het antwoord te staan: " + antwoord);
        assertFalse(herleefbaar.asBoolean(),
                "dienst-pad ≡ exec-model: herleefbaar mag NIET waar zijn als geblokkeerd waar is: " + antwoord);
    }

    // ---- helpers ----

    private static JsonNode vind(ObjectNode antwoord, String id, String groep, String lid) {
        for (JsonNode inst : antwoord.get("instanties"))
            if (id.equals(inst.path("id").asText()))
                return inst.path(groep).get(lid);
        return null;
    }

    private static JsonNode parse(String json) {
        try { return DescriptorLoader.MAPPER.readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
