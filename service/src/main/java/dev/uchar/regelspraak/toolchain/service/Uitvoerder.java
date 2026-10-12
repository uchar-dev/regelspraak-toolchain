package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Invoer;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.tms.Fout;

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
 * itself for a session used wrongly; those stay internal faults. A run without a fixpoint is no evaluation fault but
 * a fault of the lowering, since uchar refuses a cycle at the build, and leaves as an {@link IllegalStateException}
 * with uchar's sentence.
 */
public final class Uitvoerder {

    private final DroolsBackend.Build build;
    private final Map<String, Object> defaultGlobals;   // descriptor parameter defaults (global name -> value)

    public Uitvoerder(DroolsBackend.Build build, Map<String, Object> defaultGlobals) {
        this.build = build;
        this.defaultGlobals = defaultGlobals == null ? Map.of() : defaultGlobals;
    }

    /** The result of a fire: the beans by id with the derived values written back, the firings, the
     *  inconsistenties. */
    public record Uitkomst(Map<String, Object> beans, int gevuurd, List<Object> inconsistenties) {}

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
            DroolsBackend.Vuring vuring = DroolsBackend.vuur(build, sessie);
            if (vuring.geenFixpunt() != null) throw new IllegalStateException(vuring.geenFixpunt());
            // a fault names its object by the id of the request; a made object has none, and uchar names it as ref does
            Map<Object, String> label = new IdentityHashMap<>();
            wereld.beans().forEach((id, bean) -> label.put(bean, id));
            // a fault is an evaluation fault by its place at the fixpoint, not by its type: the write-back would throw
            // it as one exception among those the entry throws itself, so the faults are read as data first
            List<Fout> fouten = DroolsBackend.lees(build, sessie, label::get).fouten();
            if (!fouten.isEmpty()) throw Evaluatiefout.van(fouten);
            DroolsBackend.zetAfgeleideWaarden(build, sessie, label::get);
            List<Object> inc = leesInconsistenties(sessie);
            return new Uitkomst(wereld.beans(), vuring.vuringen(), inc);
        } finally {
            sessie.dispose();
            Thread.currentThread().setContextClassLoader(vorige);
        }
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
