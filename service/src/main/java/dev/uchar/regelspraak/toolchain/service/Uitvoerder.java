package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Invoer;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;

import org.kie.api.runtime.KieSession;

import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-request executor: given an entry point's {@link DroolsBackend.Build} (built once at startup by uchar,
 * immutable thereafter) and a mapped {@link Wereld}, it opens a FRESH session of the build's KieBase, sets the
 * parameter globals (the descriptor defaults, overridden by the request), sets the rekendatum global when the request
 * carried one, and then fills, fires and reads it through uchar's entry: {@link DroolsBackend#voegIn} puts the beans
 * in, each to-many list in the order uchar's replay gives it ({@link Wereld#lijsten()}), {@link DroolsBackend#vuur}
 * fires to the fixpoint, {@link DroolsBackend#lees} gives the evaluation faults that stand there, and where none
 * stands {@link DroolsBackend#zetAfgeleideWaarden} puts the derived values on the beans, which the branch keeps as
 * facts of the session until then. It returns the beans and the {@code Inconsistentie} facts for the response builder.
 * Session-per-request; the KieBase is shared and immutable, so this is thread-safe by construction.
 *
 * <p>Every fault that stands at the fixpoint leaves here as an {@link Evaluatiefout}, and nothing is written back: the
 * rules give no answer on this input. Such a fault is an evaluation fault by its place at the fixpoint, whatever its
 * type, so it is read as data before the write-back, which would throw it as an exception like those the entry throws
 * itself for a session used wrongly; those stay internal faults. A run without a fixpoint, at uchar's fire cap or at
 * its creation budget, leaves as a {@link GeenFixpunt} with uchar's sentence, and nothing is read or written back.
 */
public final class Uitvoerder {

    private final DroolsBackend.Build build;
    private final Map<String, Object> defaultGlobals;   // descriptor parameter defaults (global name -> value)

    public Uitvoerder(DroolsBackend.Build build, Map<String, Object> defaultGlobals) {
        this.build = build;
        this.defaultGlobals = defaultGlobals == null ? Map.of() : defaultGlobals;
    }

    /** The result of a fire: the beans by id with the derived values written back, the firings, the
     *  inconsistenties, what stood at the fixpoint before the write-back, as uchar's entry reads it, and uchar's
     *  verdict on the expectations the caller gave ({@link DroolsBackend#vergelijk}), null when it gave none. */
    public record Uitkomst(Map<String, Object> beans, int gevuurd, List<Object> inconsistenties, Fixpunt fixpunt,
                           DroolsBackend.Vergelijking vergelijking) {}

    /** Fire the entry point's rules over {@code wereld} and return the beans with their derived values and the
     *  inconsistenties. */
    public Uitkomst vuur(Wereld wereld) {
        return vuur(wereld, Map.of());
    }

    /**
     * Fire the entry point's rules over {@code wereld}, with {@code setGlobals} the parameterset-vintage resolved
     * values (fase 5). PRECEDENCE (decision D): descriptor defaults FIRST, then the set-resolved values (the
     * BeslisserKern conflict rule guarantees these never name the same parameter as a default), then the request
     * parameter overrides LAST (explicit caller-intent wins — mirrors the scenario situatie-override). The
     * rekendatum global stays {@code wereld.rekendatum()} — that IS the peildatum (the one date authority).
     *
     * @throws Evaluatiefout the evaluation faults that stand at the fixpoint, one or more, of any type
     */
    public Uitkomst vuur(Wereld wereld, Map<String, Object> setGlobals) {
        return vuur(wereld, setGlobals, null);
    }

    /**
     * As {@link #vuur(Wereld, Map)}, with {@code waarnemer} (null: none) recording what the session fires, through
     * uchar's {@link DroolsBackend#vuur(DroolsBackend.Build, KieSession, DroolsBackend.Waarnemer)}. A waarnemer belongs
     * to one session, so the caller gives each request a fresh one and reads it after this returns or throws.
     *
     * @throws Evaluatiefout the evaluation faults that stand at the fixpoint, one or more, of any type, with the
     *                       fixpoint they stand at
     */
    public Uitkomst vuur(Wereld wereld, Map<String, Object> setGlobals, DroolsBackend.Waarnemer waarnemer) {
        return vuur(wereld, setGlobals, waarnemer, null);
    }

    /**
     * As {@link #vuur(Wereld, Map, DroolsBackend.Waarnemer)}, and with {@code verwachtingen} (null: none), the
     * {@code expected} of a {@code uchar-case-v0} case whose labels are ids of the request, uchar's verdict on them over
     * the written-back session ({@link DroolsBackend#vergelijk}, with the ids of the request as labels), before the
     * session is disposed. uchar compares; this class only hands the session over.
     *
     * @throws Evaluatiefout            the evaluation faults that stand at the fixpoint, with the fixpoint they stand
     *                                  at; nothing is compared then, as uchar's report compares nothing of such a case
     * @throws GeenFixpunt              a run that reached no fixpoint, with uchar's sentence; nothing is compared
     * @throws dev.uchar.regelspraak.cases.CaseRunner.Excluded an expectation of a form uchar does not compare, in the
     *                                  sentence of its replay
     */
    public Uitkomst vuur(Wereld wereld, Map<String, Object> setGlobals, DroolsBackend.Waarnemer waarnemer,
                         JsonNode verwachtingen) {
        // descriptor defaults first, set-vintage values next, request parameters override last
        Map<String, Object> globals = new LinkedHashMap<>(defaultGlobals);
        if (setGlobals != null) globals.putAll(setGlobals);
        globals.putAll(wereld.globals());

        ClassLoader vorige = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(build.beanLoader());   // Drools resolves fact types via the TCCL
        KieSession sessie = build.kieBase().newKieSession();
        try {
            // the branch states the globals as facts at the first fire, so they all go on before it
            for (var e : build.dagsoortGlobalen().entrySet()) setGlobalVeilig(sessie, e.getKey(), e.getValue());
            for (var e : globals.entrySet()) setGlobalVeilig(sessie, e.getKey(), e.getValue());
            LocalDate rd = wereld.rekendatum();
            if (rd != null) setGlobalVeilig(sessie, "rekendatum", rd);
            // the order of a list decides which member a fault of a fold names: in the replay's order, the 422 names
            // the member uchar's replay names on the same input
            DroolsBackend.voegIn(build, sessie, wereld.lijsten().geefAan(new Invoer().beans(wereld.beans().values())));
            DroolsBackend.Vuring vuring = waarnemer == null ? DroolsBackend.vuur(build, sessie)
                    : DroolsBackend.vuur(build, sessie, waarnemer);
            if (vuring.geenFixpunt() != null) throw new GeenFixpunt(vuring.geenFixpunt());
            // a fault names its object by the id of the request; a made object has none, and uchar names it as ref does
            Map<Object, String> label = new IdentityHashMap<>();
            wereld.beans().forEach((id, bean) -> label.put(bean, id));
            // a fault is an evaluation fault by its place at the fixpoint, not by its type: the write-back would throw
            // it as one exception among those the entry throws itself, so the faults are read as data first
            Fixpunt fixpunt = DroolsBackend.lees(build, sessie, label::get);
            if (!fixpunt.fouten().isEmpty()) throw Evaluatiefout.van(fixpunt, teruggeworpen(sessie, label, fixpunt));
            DroolsBackend.zetAfgeleideWaarden(build, sessie, label::get);
            List<Object> inc = leesInconsistenties(sessie);
            DroolsBackend.Vergelijking vergelijking = verwachtingen == null ? null
                    : DroolsBackend.vergelijk(build, sessie, label::get, verwachtingen);
            return new Uitkomst(wereld.beans(), vuring.vuringen(), inc, fixpunt, vergelijking);
        } finally {
            sessie.dispose();
            Thread.currentThread().setContextClassLoader(vorige);
        }
    }

    /** The exception uchar's write-back throws for the faults that stand at {@code fixpunt}: the fault's own for one,
     *  uchar's {@code Evaluatiefouten} for two or more. It writes nothing back then; a write-back that does not throw
     *  is a broken promise of uchar's entry. */
    private RuntimeException teruggeworpen(KieSession sessie, Map<Object, String> label, Fixpunt fixpunt) {
        try {
            DroolsBackend.zetAfgeleideWaarden(build, sessie, label::get);
        } catch (RuntimeException e) {
            return e;
        }
        throw new IllegalStateException("uchars terugzetting wierp niets, terwijl aan het fixpunt "
                + fixpunt.fouten().size() + " evaluatiefout(en) staan, de eerste: " + fixpunt.fouten().get(0).melding());
    }

    /** setGlobal tolerating a global the compiled DRL did not declare (a parameter no scoped rule reads is not
     *  registered as a global — writing it would throw; harmless to skip). Mirrors ScenarioRunner. */
    private static void setGlobalVeilig(KieSession sessie, String naam, Object waarde) {
        try { sessie.setGlobal(naam, waarde); }
        catch (RuntimeException onbekend) { /* global not declared: no scoped rule reads it */ }
    }

    /** uchar's generator writes the {@code Inconsistentie} bean for every model, so a class loader without it is not
     *  the one the KieBase was built against: that fails here rather than dropping every inconsistency. */
    private List<Object> leesInconsistenties(KieSession sessie) {
        String naam = FactModelGenerator.PAKKET + ".Inconsistentie";
        Class<?> inc;
        try {
            inc = Class.forName(naam, true, build.beanLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("de bean-classloader van dit entrypoint draagt " + naam + " niet", e);
        }
        return List.copyOf(sessie.getObjects(inc::isInstance));
    }
}
