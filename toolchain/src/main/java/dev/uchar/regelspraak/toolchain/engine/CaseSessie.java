package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.CaseInvoer;
import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.transpiler.kern.Namen;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;

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
 * put in, fired to the fixpoint, read and written back through uchar's entry ({@link DroolsBackend#voegIn},
 * {@link DroolsBackend#vuur}, {@link DroolsBackend#lees}, {@link DroolsBackend#zetAfgeleideWaarden}), the firings
 * recorded by uchar's {@link DroolsBackend.Waarnemer} with the labels of the input instances, as uchar's replay records
 * them, so the trace has one definition. It exists for what uchar's replay ({@code CaseRunner.rapport}) does not return: the beans with their
 * derived values, from which the {@link Verbalisator} explains an outcome, and uchar's {@link Fixpunt}, what stands at
 * the fixpoint with or without an expectation on it.
 *
 * <p>The input is read by uchar's own input step ({@link CaseInvoer#van}), the one the replay reads every case with:
 * the beans, their attributes, characteristics, timelines and roles, and the {@link CaseInvoer#invoer()} that puts them
 * in as the replay does (each to-many list in the replay's order, each bucket scalar named). This session sets the
 * parameters as globals under their camelCase name and the evaluation date as {@code rekendatum}. A caller that uses
 * the outcome to explain uchar's verdict compares {@link Uitkomst#vuringen()} with the trace uchar reported, so a
 * session that set an input otherwise does not explain a run that did not happen. A form uchar's input step does not
 * carry, and a parameter with a timeline or a rational value, is refused by name ({@link NietSpeelbaar}).
 */
public final class CaseSessie {

    private CaseSessie() {}

    /** A case form this session does not set. */
    public static final class NietSpeelbaar extends RuntimeException {
        NietSpeelbaar(String reden) { super(reden); }
    }

    /** The input beans by label, their object types, the globals the session carried (parameters by their camelCase
     *  name, {@code rekendatum}), the firings in the order they first fired, and uchar's reading of the fixpoint, read
     *  before the write-back with the labels of the input beans. The beans carry the derived values written back,
     *  unless an evaluation fault stands at the fixpoint ({@code fixpunt.fouten()} not empty): the write-back would
     *  throw it, so the beans then hold the input alone and the fault is in the fixpoint as data. */
    public record Uitkomst(Map<String, Object> beans, Map<String, Objecttype> typePerLabel,
                           Map<String, Object> globals, List<ScenarioResultaat.Vuring> vuringen, Fixpunt fixpunt) {}

    /** Play case {@code c} on {@code build}, a build of the exec-model branch, whose
     *  {@link DroolsBackend.Build#model()} names the types. The branch is required because the caller compares the
     *  firings with the trace of uchar's report ({@code CaseRunner.rapport}), which plays the exec-model branch: the
     *  DRL-text branch reaches the same fixpoint, but at an accumulate it can fire in another order and number. */
    public static Uitkomst speel(DroolsBackend.Build build, JsonNode c) {
        if (build.branch() != DroolsBackend.Branch.EXEC_MODEL)
            throw new IllegalArgumentException("de uitleg speelt alleen de exec-model-tak, niet de " + build.branch());
        return new Speler(build).speel(c);
    }

    private static final class Speler {
        private final DroolsBackend.Build build;
        private final Map<String, Object> globals = new LinkedHashMap<>();

        Speler(DroolsBackend.Build build) {
            this.build = build;
        }

        Uitkomst speel(JsonNode c) {
            CaseInvoer invoer;
            try {
                invoer = CaseInvoer.van(build, c);
            } catch (CaseRunner.Excluded e) {
                throw new NietSpeelbaar(e.getMessage());
            }
            Map<Object, String> labels = new IdentityHashMap<>();
            invoer.beans().forEach((label, bean) -> labels.put(bean, label));
            JsonNode asOf = c.get("asOfDate");
            LocalDate rekendatum = asOf == null || asOf.isNull() ? null : LocalDate.parse(asOf.asText());
            ClassLoader vorige = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(build.beanLoader());
            KieSession sessie = build.kieBase().newKieSession();
            try {
                JsonNode params = c.get("input").get("parameters");
                if (params != null) for (Map.Entry<String, JsonNode> e : params.properties()) {
                    if (e.getValue().has("timeline"))
                        throw new NietSpeelbaar("de parameter '" + e.getKey() + "' heeft een tijdlijn");
                    globals.put(Namen.ident(e.getKey(), false), waarde(e.getValue(), e.getKey()));
                }
                if (rekendatum != null) globals.put("rekendatum", rekendatum);
                for (Map.Entry<String, Object> g : globals.entrySet()) setGlobalVeilig(sessie, g.getKey(), g.getValue());
                for (Map.Entry<String, java.util.function.Predicate<LocalDate>> d : build.dagsoortGlobalen().entrySet())
                    setGlobalVeilig(sessie, d.getKey(), d.getValue());
                DroolsBackend.voegIn(build, sessie, invoer.invoer());
                DroolsBackend.Waarnemer waarnemer = new DroolsBackend.Waarnemer(labels::get);
                DroolsBackend.Vuring vuring = DroolsBackend.vuur(build, sessie, waarnemer);
                if (vuring.geenFixpunt() != null) throw new NietSpeelbaar(vuring.geenFixpunt());
                // read before the write-back, which throws a standing evaluation fault where the reading gives it as
                // data, and writes nothing after a throw (uchar's entry, as the service's Uitvoerder reads it)
                Fixpunt fixpunt = DroolsBackend.lees(build, sessie, labels::get);
                if (fixpunt.fouten().isEmpty()) DroolsBackend.zetAfgeleideWaarden(build, sessie, labels::get);
                return new Uitkomst(new LinkedHashMap<>(invoer.beans()), new LinkedHashMap<>(invoer.typen()),
                        new LinkedHashMap<>(globals), waarnemer.vuringen(), fixpunt);
            } finally {
                sessie.dispose();
                Thread.currentThread().setContextClassLoader(vorige);
            }
        }
    }

    /** The Java value of a case value node, as the replay reads it. */
    private static Object waarde(JsonNode w, String veld) {
        if (w == null || w.isNull()) return null;
        if (w.has("number")) {
            String s = w.get("number").asText();
            if (s.contains("/")) throw new NietSpeelbaar("de waarde op '" + veld + "' is een breuk");
            return new BigDecimal(s);
        }
        if (w.has("date")) return LocalDate.parse(w.get("date").asText());
        if (w.has("dateTime")) return LocalDateTime.parse(w.get("dateTime").asText());
        if (w.has("boolean")) return w.get("boolean").asBoolean();
        if (w.has("text")) return w.get("text").asText();
        if (w.has("enumeration")) return w.get("enumeration").asText();
        throw new NietSpeelbaar("de waardevorm op '" + veld + "' wordt niet gezet: " + w);
    }

    /** A getter read, the boolean {@code is…} form included. */
    public static Object lees(Object bean, String getter) {
        for (String m : new String[]{ getter, "is" + getter.substring(3) }) {
            try { return bean.getClass().getMethod(m).invoke(bean); }
            catch (NoSuchMethodException volgende) { /* the other spelling */ }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("kon " + m + " niet lezen", e); }
        }
        throw new IllegalStateException("geen getter " + getter + " op " + bean.getClass().getSimpleName());
    }

    private static void setGlobalVeilig(KieSession sessie, String naam, Object waarde) {
        try { sessie.setGlobal(naam, waarde); }
        catch (RuntimeException nietGedeclareerd) { /* no rule of this build reads that global */ }
    }
}
