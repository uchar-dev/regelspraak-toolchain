package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.runtime.KieSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One case of the {@code uchar-case-v0} format played in a session of its own on a build uchar made
 * ({@link DroolsBackend.Build}): the input beans the case describes, its parameters and evaluation date as globals,
 * fired to a fixpoint, with a listener that records every firing under the rule's base name with the labels of the
 * input instances its match bound. It exists for what uchar's replay ({@code CaseRunner.rapport}) does not return:
 * the fired beans, from which the {@link Verbalisator} explains an outcome.
 *
 * <p>The inputs are set as uchar's replay sets them, whose input step is not public API: the beans of the generated
 * classes, the attributes and characteristics through their setters, the roles through the role the case writes
 * (and its sister role, the far field of a one-to-one relation, and the mirror of a reciprocal self-relation), the
 * parameters as globals under their camelCase name, the evaluation date as {@code rekendatum}. A caller that uses the
 * outcome to explain uchar's verdict compares {@link Uitkomst#vuringen()} with the trace uchar reported, so a session
 * that set an input otherwise does not explain a run that did not happen. A form this session does not set — a
 * timeline, a dimensioned attribute, a rational value — is refused by name ({@link NietSpeelbaar}).
 */
public final class CaseSessie {

    private CaseSessie() {}

    /** A case form this session does not set. */
    public static final class NietSpeelbaar extends RuntimeException {
        NietSpeelbaar(String reden) { super(reden); }
    }

    /** The fired input beans by label, their object types, the globals the session carried (parameters by their
     *  camelCase name, {@code rekendatum}), and the firings in the order they first fired. */
    public record Uitkomst(Map<String, Object> beans, Map<String, Objecttype> typePerLabel,
                           Map<String, Object> globals, List<ScenarioResultaat.Vuring> vuringen) {}

    /** Play case {@code c} on {@code build}, whose {@link DroolsBackend.Build#model()} names the types. */
    public static Uitkomst speel(DroolsBackend.Build build, JsonNode c) {
        return new Speler(build).speel(c);
    }

    private static final class Speler {
        private final RegelSpraakModel model;
        private final DroolsBackend.Build build;
        private final ClassLoader cl;
        private final Map<String, Object> labelNaarBean = new LinkedHashMap<>();
        private final Map<String, Objecttype> labelType = new LinkedHashMap<>();
        private final Map<String, Object> globals = new LinkedHashMap<>();
        private final Map<String, Integer> telling = new LinkedHashMap<>();
        private final Map<String, List<String>> labels = new LinkedHashMap<>();

        private final RolSchrijver rollenSchrijver;

        Speler(DroolsBackend.Build build) {
            this.build = build;
            this.model = build.model();
            this.cl = build.beanLoader();
            this.rollenSchrijver = new RolSchrijver(model);
        }

        Uitkomst speel(JsonNode c) {
            JsonNode asOf = c.get("asOfDate");
            LocalDate rekendatum = asOf == null || asOf.isNull() ? null : LocalDate.parse(asOf.asText());
            ClassLoader vorige = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(cl);
            KieSession sessie = build.kieBase().newKieSession();
            try {
                JsonNode instances = c.get("input").get("instances");
                for (JsonNode i : instances) beanVoor(i);
                for (JsonNode i : instances) zetAttributenEnKenmerken(i);
                for (JsonNode i : instances) zetRollen(i);
                spiegelWederkerigeRollen();
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
                sessie.addEventListener(luisteraar());
                for (Object bean : labelNaarBean.values()) sessie.insert(bean);
                if (sessie.fireAllRules(100_000) >= 100_000)
                    throw new NietSpeelbaar("geen fixpunt binnen 100000 activaties");
                List<ScenarioResultaat.Vuring> vuringen = new ArrayList<>();
                for (Map.Entry<String, Integer> e : telling.entrySet())
                    vuringen.add(new ScenarioResultaat.Vuring(e.getKey(), List.copyOf(labels.get(e.getKey())), e.getValue()));
                return new Uitkomst(new LinkedHashMap<>(labelNaarBean), new LinkedHashMap<>(labelType),
                        new LinkedHashMap<>(globals), vuringen);
            } finally {
                sessie.dispose();
                Thread.currentThread().setContextClassLoader(vorige);
            }
        }

        private void beanVoor(JsonNode i) {
            String label = i.get("label").asText();
            if (labelNaarBean.containsKey(label)) return;
            Objecttype ot = model.objecttypeOpNaam(i.get("objectType").asText());
            if (ot == null) throw new NietSpeelbaar("de case noemt een onbekend objecttype: " + i.get("objectType").asText());
            String klasse = FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true);
            try {
                labelNaarBean.put(label, Class.forName(klasse, true, cl).getConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("kon de bean " + klasse + " voor '" + label + "' niet maken", e);
            }
            labelType.put(label, ot);
        }

        private void zetAttributenEnKenmerken(JsonNode i) {
            String label = i.get("label").asText();
            Object bean = labelNaarBean.get(label);
            Objecttype ot = labelType.get(label);
            JsonNode attrs = i.get("attributes");
            if (attrs != null) for (Map.Entry<String, JsonNode> e : attrs.properties()) {
                Datatype dt = ot.attribuut(e.getKey());
                if (dt == null) throw new NietSpeelbaar("de case zet het onbekende attribuut '" + e.getKey() + "'");
                if (!ot.dimensiesVan(e.getKey()).isEmpty())
                    throw new NietSpeelbaar("het attribuut '" + e.getKey() + "' is gedimensioneerd");
                if (Namen.bevatNaam(ot.granulariteitLeden(), e.getKey()))
                    throw new NietSpeelbaar("het attribuut '" + e.getKey() + "' is tijdsafhankelijk");
                roep(bean, Namen.setter(e.getKey()), argType(dt), waarde(e.getValue(), e.getKey()));
            }
            JsonNode kenmerken = i.get("characteristics");
            if (kenmerken != null) for (JsonNode k : kenmerken) {
                if (Namen.bevatNaam(ot.kenmerkGranulariteitLeden(), k.asText()))
                    throw new NietSpeelbaar("het kenmerk '" + k.asText() + "' is tijdsafhankelijk");
                roep(bean, Namen.setter(k.asText()), boolean.class, Boolean.TRUE);
            }
            JsonNode ctls = i.get("characteristicTimelines");
            if (ctls != null && !ctls.isEmpty()) throw new NietSpeelbaar("de case geeft een kenmerk met perioden");
        }

        /** The case's roles, written as the replay writes them ({@link RolSchrijver}). */
        private void zetRollen(JsonNode i) {
            String label = i.get("label").asText();
            JsonNode rollen = i.get("roles");
            if (rollen == null) return;
            for (Map.Entry<String, JsonNode> e : rollen.properties())
                for (JsonNode labelNode : e.getValue()) {
                    Object doel = labelNaarBean.get(labelNode.asText());
                    if (doel == null)
                        throw new NietSpeelbaar("de rol '" + e.getKey() + "' noemt het onbekende label " + labelNode.asText());
                    try {
                        rollenSchrijver.schrijf(labelNaarBean.get(label), labelType.get(label), e.getKey(), doel,
                                labelType.get(labelNode.asText()));
                    } catch (RolSchrijver.Geweigerd g) {
                        throw new NietSpeelbaar(g.getMessage());
                    }
                }
        }

        private void spiegelWederkerigeRollen() {
            try {
                rollenSchrijver.spiegelWederkerig(labelNaarBean, labelType);
            } catch (RolSchrijver.Geweigerd g) {
                throw new NietSpeelbaar(g.getMessage());
            }
        }

        private DefaultAgendaEventListener luisteraar() {
            return new DefaultAgendaEventListener() {
                @Override public void afterMatchFired(AfterMatchFiredEvent e) {
                    String regel = ProducentConsument.basisNaam(e.getMatch().getRule().getName());
                    telling.merge(regel, 1, Integer::sum);
                    List<String> ls = labels.computeIfAbsent(regel, k -> new ArrayList<>());
                    for (Object o : e.getMatch().getObjects()) {
                        String l = labelVan(o);
                        if (l != null && !ls.contains(l)) ls.add(l);
                    }
                }
            };
        }

        private String labelVan(Object bean) {
            for (Map.Entry<String, Object> e : labelNaarBean.entrySet()) if (e.getValue() == bean) return e.getKey();
            return null;
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

    private static Class<?> argType(Datatype dt) {
        if (dt.isNumeriek() || dt.isPercentage()) return BigDecimal.class;
        if (dt.isTekst() || dt instanceof Datatype.Enumeratie) return String.class;
        if (dt instanceof Datatype.Booleaan) return Boolean.class;
        if (dt instanceof Datatype.DatumInDagen) return LocalDate.class;
        if (dt instanceof Datatype.DatumTijd) return LocalDateTime.class;
        return Object.class;
    }

    private static void roep(Object bean, String methode, Class<?> type, Object waarde) {
        try {
            bean.getClass().getMethod(methode, type).invoke(bean, waarde);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kon " + methode + "(" + type.getSimpleName() + ") niet aanroepen op "
                    + bean.getClass().getSimpleName(), e);
        }
    }

    /** A getter read, the boolean {@code is…} form included. */
    static Object lees(Object bean, String getter) {
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
