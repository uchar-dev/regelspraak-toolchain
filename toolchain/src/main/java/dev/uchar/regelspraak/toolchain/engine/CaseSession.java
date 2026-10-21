package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.CaseInput;
import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import org.kie.api.runtime.KieSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One case of the {@code uchar-case-v0} format played in a session of its own on a build uchar made
 * ({@link DroolsBackend.Build}): the input beans the case describes, its parameters and evaluation date as globals,
 * put in, fired to the fixpoint, read and written back through uchar's entry ({@link DroolsBackend#insert},
 * {@link DroolsBackend#fire}, {@link DroolsBackend#fixpointOf}, {@link DroolsBackend#setDerivedValues}), the firings
 * recorded by uchar's {@link DroolsBackend.Observer} with the labels of the input instances, as uchar's replay records
 * them, so the trace has one definition. It exists for what uchar's replay ({@code CaseRunner.report}) does not return: the beans with their
 * derived values, from which the {@link Verbaliser} explains an outcome, and uchar's {@link Fixpoint}, what stands at
 * the fixpoint with or without an expectation on it.
 *
 * <p>The input is read by uchar's own input step ({@link CaseInput#of}), the one the replay reads every case with:
 * the beans, their attributes, characteristics, timelines and roles, and the {@link CaseInput#invoer()} that puts them
 * in as the replay does (each to-many list in the replay's order, each bucket scalar named). This session sets the
 * parameters as globals under their camelCase name and the evaluation date as {@code evaluationDate}. A caller that uses
 * the outcome to explain uchar's verdict compares {@link Outcome#firings()} with the trace uchar reported, so a
 * session that set an input otherwise does not explain a run that did not happen. A form uchar's input step does not
 * carry, and a parameter with a timeline or a rational value, is refused by name ({@link NotPlayable}).
 */
public final class CaseSession {

    private CaseSession() {}

    /** A case form this session does not set. */
    public static final class NotPlayable extends RuntimeException {
        NotPlayable(String reason) { super(reason); }
    }

    /** The input beans by label, their object types, the globals the session carried (parameters by their camelCase
     *  name, {@code evaluationDate}), the firings in the order they first fired, and uchar's reading of the fixpoint, read
     *  before the write-back with the labels of the input beans. The beans carry the derived values written back,
     *  unless an evaluation fault stands at the fixpoint ({@code fixpoint.errors()} not empty): the write-back would
     *  throw it, so the beans then hold the input alone and the fault is in the fixpoint as data. {@code decisions} says
     *  which default of each defaults stack decided ({@link DroolsBackend#decisionsOf}), read after the write-back, and is
     *  empty where an evaluation fault stands: the session has no answer then. */
    public record Outcome(Map<String, Object> beans, Map<String, ObjectType> typePerLabel,
                           Map<String, Object> globals, List<ScenarioResult.Firing> firings, Fixpoint fixpoint,
                           List<DefaultsDecision> decisions) {}

    /** Play case {@code c} on {@code build}, a build of the exec-model branch, whose
     *  {@link DroolsBackend.Build#model()} names the types. The branch is required because the caller compares the
     *  firings with the trace of uchar's report ({@code CaseRunner.report}), which plays the exec-model branch: the
     *  DRL-text branch reaches the same fixpoint, but at an accumulate it can fire in another order and number. */
    public static Outcome play(DroolsBackend.Build build, JsonNode c) {
        if (build.branch() != DroolsBackend.Branch.EXEC_MODEL)
            throw new IllegalArgumentException("de uitleg speelt alleen de exec-model-tak, niet de " + build.branch());
        return new Player(build).play(c);
    }

    private static final class Player {
        private final DroolsBackend.Build build;
        private final Map<String, Object> globals = new LinkedHashMap<>();

        Player(DroolsBackend.Build build) {
            this.build = build;
        }

        Outcome play(JsonNode c) {
            CaseInput input;
            try {
                input = CaseInput.of(build, c);
            } catch (CaseRunner.Excluded e) {
                throw new NotPlayable(e.getMessage());
            }
            Map<Object, String> labels = new IdentityHashMap<>();
            input.beans().forEach((label, bean) -> labels.put(bean, label));
            JsonNode asOf = c.get("asOfDate");
            LocalDate evaluationDate = asOf == null || asOf.isNull() ? null : LocalDate.parse(asOf.asText());
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(build.beanLoader());
            KieSession session = build.kieBase().newKieSession();
            try {
                JsonNode params = c.get("input").get("parameters");
                if (params != null) for (Map.Entry<String, JsonNode> e : params.properties()) {
                    if (e.getValue().has("timeline"))
                        throw new NotPlayable("de parameter '" + e.getKey() + "' heeft een tijdlijn");
                    globals.put(Namen.ident(e.getKey(), false), value(e.getValue(), e.getKey()));
                }
                if (evaluationDate != null) globals.put("rekendatum", evaluationDate);
                for (Map.Entry<String, Object> g : globals.entrySet()) setGlobalSafe(session, g.getKey(), g.getValue());
                for (Map.Entry<String, java.util.function.Predicate<LocalDate>> d : build.dayKindGlobals().entrySet())
                    setGlobalSafe(session, d.getKey(), d.getValue());
                DroolsBackend.insert(build, session, input.inputData());
                DroolsBackend.Observer observer = new DroolsBackend.Observer(labels::get);
                DroolsBackend.Firing firing = DroolsBackend.fire(build, session, observer);
                if (firing.noFixpoint() != null) throw new NotPlayable(firing.noFixpoint());
                // read before the write-back, which throws a standing evaluation fault where the reading gives it as
                // data, and writes nothing after a throw (uchar's entry, as the service's Uitvoerder reads it)
                Fixpoint fixpoint = DroolsBackend.fixpointOf(build, session, labels::get);
                List<DefaultsDecision> decisions = List.of();
                if (fixpoint.faults().isEmpty()) {
                    DroolsBackend.setDerivedValues(build, session, labels::get);
                    decisions = DroolsBackend.decisionsOf(build, session);
                }
                return new Outcome(new LinkedHashMap<>(input.beans()), new LinkedHashMap<>(input.types()),
                        new LinkedHashMap<>(globals), observer.firings(), fixpoint, decisions);
            } finally {
                session.dispose();
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    /** The Java value of a case value node, as the replay reads it. */
    private static Object value(JsonNode w, String field) {
        if (w == null || w.isNull()) return null;
        if (w.has("number")) {
            String s = w.get("number").asText();
            if (s.contains("/")) throw new NotPlayable("de waarde op '" + field + "' is een breuk");
            return new BigDecimal(s);
        }
        if (w.has("date")) return LocalDate.parse(w.get("date").asText());
        if (w.has("dateTime")) return LocalDateTime.parse(w.get("dateTime").asText());
        if (w.has("boolean")) return w.get("boolean").asBoolean();
        if (w.has("text")) return w.get("text").asText();
        if (w.has("enumeration")) return w.get("enumeration").asText();
        throw new NotPlayable("de waardevorm op '" + field + "' wordt niet gezet: " + w);
    }

    /** A getter read, the boolean {@code is…} form included. */
    public static Object read(Object bean, String getter) {
        for (String m : new String[]{ getter, "is" + getter.substring(3) }) {
            try { return bean.getClass().getMethod(m).invoke(bean); }
            catch (NoSuchMethodException next) { /* the other spelling */ }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("kon " + m + " niet lezen", e); }
        }
        throw new IllegalStateException("geen getter " + getter + " op " + bean.getClass().getSimpleName());
    }

    private static void setGlobalSafe(KieSession session, String name, Object value) {
        try { session.setGlobal(name, value); }
        catch (RuntimeException notDeclared) { /* no rule of this build reads that global */ }
    }
}
