package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.DekkingSoort;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Status;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Trace;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Vuring;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.AttribuutWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Instantie;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.KenmerkWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.ParameterWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.RolWaarde;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Scope;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.ScopeRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.ScopeSoort;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Verwachting;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.Waarde;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;
import io.github.yhavinga.regelspraak.transpiler.kern.Namen;

import org.kie.api.KieBase;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The RUNNER layer for the scenariotoetsen extension (docs/SCENARIOTOETSEN.md): it takes a resolved
 * {@link ScenarioBundel}, and per scenario builds a FRESH world of {@link FactModelGenerator} beans from the
 * merged situatie, fires the compiled rules over it, and compares each expectation EXACTLY against the produced
 * fact state (BigDecimal.compareTo / equals / boolean read / null⇔leeg / Inconsistentie facts). It reports a
 * {@link ScenarioResultaat} per scenario carrying PASS/FAIL/MARKER, the per-expectation verwacht/werkelijk, the
 * fired-rules trace, and the per-scope-rule coverage.
 *
 * <p><b>Back-end</b>: the DOCUMENTED DRL fallback (docs/SCENARIOTOETSEN.md, § "Runner & CLI"). The bundle's beans
 * are compiled ONCE (in {@link BeanCompiler}), the model is contract-validated against them, and
 * {@link DrlTranspiler} + {@link org.kie.internal.utils.KieHelper#setClassLoader} build the KieBase over those
 * in-memory beans (the proven {@code TokaVuurTest} pattern). The exec-model back-end is preferred in principle, but
 * its LambdaMetafactory fact-accessors cannot bind against a FOREIGN (child) bean classloader — a parent Lookup
 * cannot link a child loader's classes — so it NoClassDefFounds at the first consequence; the differential suites
 * already pin DRL==exec parity, so the runner's verdicts are the shippable path's too. Compilation is done once per
 * bundle (not per scenario) — only the SESSION and the facts are fresh per scenario.
 */
public final class ScenarioRunner {
    private ScenarioRunner() {}

    // ---------------- entry points ----------------

    /** Resolve {@code bron} under the scenario front-end and run every scenario in it. A resolve error throws
     *  {@link ResolveFout} carrying the diagnostics (the CLI turns that into exit 1). */
    public static List<ScenarioResultaat> run(String bron) {
        return run(ScenarioResolver.resolve(bron));
    }

    /** Run every scenario in an already-resolved bundle. Throws {@link ResolveFout} if the bundle has resolver
     *  errors (a scenario cannot run against an ill-typed model). */
    public static List<ScenarioResultaat> run(ScenarioBundel bundel) {
        if (!bundel.isOk()) throw new ResolveFout(diagnostiek(bundel));
        RegelSpraakModel model = bundel.model();

        // Compile the beans ONCE for the whole bundle (same objectmodel for every scenario), validate the
        // fact-model contract against them, then build the KieBase (DRL path below) over that bean classloader.
        Map<String, String> bronnen = FactModelGenerator.genereer(model);
        ClassLoader cl = BeanCompiler.compileer(bronnen);
        List<String> schendingen = ContractValidator.valideer(model, cl);
        if (!schendingen.isEmpty()) throw new ResolveFout(schendingen);

        // Back-end: the DOCUMENTED DRL fallback (docs/SCENARIOTOETSEN.md, § "Runner & CLI"). The shippable
        // exec-model back-end cannot bind its LambdaMetafactory fact-accessors against a FOREIGN (child) bean
        // classloader — Accessors' Lookup lives on the parent loader and a parent Lookup cannot link a child
        // loader's classes — so an in-memory-bean run would NoClassDefFound at the first consequence. The DRL
        // path takes an EXPLICIT classloader (KieHelper.setClassLoader, the proven TokaVuurTest pattern), and the
        // differential suites already pin DRL==exec parity, so the runner's verdicts are the shippable path's too.
        DrlTranspiler transpiler = DrlTranspiler.build(model);
        // A MARKER rule emits a "/* TODO expressie … */" the MVEL dialect cannot parse, so it would sink the WHOLE
        // DRL compilation. The marker is by definition an accepted-but-inert no-op (it produces no value), so strip
        // those rule blocks BEFORE compiling — an omitted marker rule never fires, which is exactly its semantics.
        // The Dekking manifest below still records it as MARKER, so a scenario whose scope includes it reads MARKER
        // (exit 3), never PASS. The stripped-out set is precisely the manifest's non-LOWERED verdicts.
        String drl = stripMarkerRegels(transpiler.drl());
        KieHelper helper = new KieHelper();
        helper.setClassLoader(cl);
        helper.addContent(drl, ResourceType.DRL);
        var meldingen = helper.verify();
        if (meldingen.hasMessages(org.kie.api.builder.Message.Level.ERROR))
            throw new ResolveFout(List.of("DRL compileert niet tegen de gegenereerde beans: " + meldingen.getMessages()));
        KieBase kieBase = helper.build();

        // Coverage lookup: rule name -> its lowering verdict. A rule can carry SEVERAL manifest entries (one per
        // version / desugared construct); a non-LOWERED verdict DOMINATES, so a rule with one lowered and one
        // markered version still reads MARKER — accepted-but-inert must never hide behind a lowered sibling.
        Map<String, Dekking> dekkingPerRegel = new LinkedHashMap<>();
        for (Dekking d : transpiler.dekking())
            dekkingPerRegel.merge(basisNaam(d.naam()), d, (oud, nieuw) -> oud.lowered() && !nieuw.lowered() ? nieuw : oud);

        List<ScenarioResultaat> uit = new ArrayList<>();
        for (ResolvedScenario s : bundel.scenarios())
            uit.add(new Loop(model, kieBase, cl, dekkingPerRegel).run(s));
        return uit;
    }

    /** The resolver errors of a bundle rendered as "regel N: message" lines (for the CLI diagnostics). */
    private static List<String> diagnostiek(ScenarioBundel bundel) {
        List<String> uit = new ArrayList<>();
        for (var d : bundel.model().errors()) uit.add("regel " + d.line() + ": " + d.message());
        return uit;
    }

    /** Thrown when a bundle does not resolve or its beans miss the contract — the CLI maps it to exit 1. */
    public static final class ResolveFout extends RuntimeException {
        private final transient List<String> diagnostiek;
        public ResolveFout(List<String> diagnostiek) { super(String.join("\n", diagnostiek)); this.diagnostiek = List.copyOf(diagnostiek); }
        public List<String> diagnostiek() { return diagnostiek; }
    }

    // ---------------- one scenario ----------------

    /** Per-scenario runner state: the fresh session, the LABEL↔bean identity maps, the coverage lookup. */
    private static final class Loop {
        private final RegelSpraakModel model;
        private final KieBase kieBase;
        private final ClassLoader cl;
        private final Map<String, Dekking> dekkingPerRegel;
        private final Map<String, Object> labelNaarBean = new LinkedHashMap<>();
        private final Map<Object, String> beanNaarLabel = new IdentityHashMap<>();

        Loop(RegelSpraakModel model, KieBase kieBase, ClassLoader cl, Map<String, Dekking> dekkingPerRegel) {
            this.model = model; this.kieBase = kieBase; this.cl = cl; this.dekkingPerRegel = dekkingPerRegel;
        }

        ScenarioResultaat run(ResolvedScenario s) {
            ClassLoader vorige = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(cl);   // Drools resolves fact types via the TCCL at fire time
            try {
                return runInner(s);
            } finally {
                Thread.currentThread().setContextClassLoader(vorige);
            }
        }

        private ScenarioResultaat runInner(ResolvedScenario s) {
            KieSession sessie = kieBase.newKieSession();
            java.util.Map<String, Integer> telling = new java.util.LinkedHashMap<>();
            java.util.Map<String, List<String>> labelsPerRegel = new LinkedHashMap<>();
            try {
                // 1. build the beans for every labelled instance
                for (Instantie i : s.situatie().instanties()) beanVoor(i);
                // 2. attribuut/kenmerk/rol assignments (rollen last so their targets already exist)
                for (Instantie i : s.situatie().instanties()) zetAttributenEnKenmerken(i);
                for (Instantie i : s.situatie().instanties()) zetRollen(i);
                // 3. globals: parameters + rekendatum (peildatum)
                zetGlobals(sessie, s);
                // 4. trace listener
                sessie.addEventListener(new DefaultAgendaEventListener() {
                    @Override public void afterMatchFired(AfterMatchFiredEvent e) {
                        String rn = basisNaam(e.getMatch().getRule().getName());
                        telling.merge(rn, 1, Integer::sum);
                        labelsPerRegel.computeIfAbsent(rn, k -> new ArrayList<>());
                        for (Object o : e.getMatch().getObjects()) {
                            String lab = beanNaarLabel.get(o);
                            if (lab != null && !labelsPerRegel.get(rn).contains(lab)) labelsPerRegel.get(rn).add(lab);
                        }
                    }
                });
                // 5. insert + fire
                for (Object bean : labelNaarBean.values()) sessie.insert(bean);
                int n = sessie.fireAllRules(100_000);
                if (n >= 100_000) throw new IllegalStateException("regel-herberekening bereikt geen fixpunt (kringverwijzing?)");

                // 6. compare expectations
                List<VerwachtingResultaat> vr = new ArrayList<>();
                for (Verwachting v : s.verwachtingen()) vr.add(vergelijk(v, sessie));

                // 7. trace + coverage
                Trace trace = bouwTrace(telling, labelsPerRegel);
                List<DekkingRegel> dekking = bouwDekking(s.scope(), telling.keySet());
                boolean marker = dekking.stream().anyMatch(d -> d.soort() == DekkingSoort.MARKER);
                boolean allePass = vr.stream().allMatch(VerwachtingResultaat::ok);

                Status status = !allePass ? Status.FAIL : marker ? Status.MARKER : Status.PASS;
                String diagnose = diagnose(status, vr, dekking);
                return new ScenarioResultaat(s.naam(), status, vr, trace, dekking, diagnose);
            } finally {
                sessie.dispose();
            }
        }

        // ---- bean construction ----

        private Object beanVoor(Instantie i) {
            Object bean = labelNaarBean.get(i.label());
            if (bean != null) return bean;
            if (i.objecttype() == null) throw new IllegalStateException("label '" + i.label() + "' heeft geen objecttype");
            try {
                bean = Class.forName(FactModelGenerator.PAKKET + "." + Namen.ident(i.objecttype().naam, true), true, cl)
                        .getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("kon bean voor label '" + i.label() + "' niet maken", e);
            }
            labelNaarBean.put(i.label(), bean);
            beanNaarLabel.put(bean, i.label());
            return bean;
        }

        private void zetAttributenEnKenmerken(Instantie i) {
            Object bean = labelNaarBean.get(i.label());
            for (AttribuutWaarde a : i.attributen())
                roep(bean, Namen.setter(a.attribuut()), argType(a.type()), waardeNaarArg(a.waarde()));
            for (KenmerkWaarde k : i.kenmerken())
                roep(bean, Namen.setter(k.kenmerk()), boolean.class, Boolean.TRUE);   // a situatie only ever ASSERTS a kenmerk
        }

        private void zetRollen(Instantie i) {
            Object bean = labelNaarBean.get(i.label());
            for (RolWaarde r : i.rollen()) {
                if (!r.meervoudig()) {
                    // to-one: the field lives on THIS (the many) side, named after the role; write the target bean.
                    Object doel = doelBean(r.doelLabels().get(0), r.rol());
                    roep(bean, Namen.setter(r.rol()), doel.getClass(), doel);
                } else {
                    // to-many: THIS is the ONE side; the reference lives on each MANY-side target, named after the
                    // reverse role (the role THIS objecttype plays from the target's side). Set each target -> this.
                    for (String label : r.doelLabels()) {
                        Object doel = doelBean(label, r.rol());
                        Objecttype doelType = model.objecttypen.values().stream()
                                .filter(ot -> Namen.ident(ot.naam, true).equals(doel.getClass().getSimpleName()))
                                .findFirst().orElse(null);
                        String reverse = doelType == null ? null : model.verbindendeRol(doelType, i.objecttype());
                        if (reverse == null)
                            throw new IllegalStateException("kon de omgekeerde rol voor de naar-veel-rol '" + r.rol()
                                    + "' niet bepalen (label '" + label + "')");
                        roep(doel, Namen.setter(reverse), bean.getClass(), bean);
                    }
                }
            }
        }

        /** The built bean for a rol target LABEL, with a clear diagnostic if it was never declared as an instance
         *  (the resolver's checkRolDoelen already guarantees this, so this is a defensive guard, not a normal path). */
        private Object doelBean(String label, String rol) {
            Object doel = labelNaarBean.get(label);
            if (doel == null)
                throw new IllegalStateException("rol '" + rol + "' verwijst naar label '" + label
                        + "' dat geen instantie in de situatie heeft");
            return doel;
        }

        private void zetGlobals(KieSession sessie, ResolvedScenario s) {
            for (ParameterWaarde p : s.situatie().parameters())
                setGlobalVeilig(sessie, Namen.ident(p.parameter(), false), waardeNaarArg(p.waarde()));
            if (s.peildatum() != null)
                setGlobalVeilig(sessie, "rekendatum", s.peildatum() instanceof LocalDateTime dt ? dt.toLocalDate() : s.peildatum());
        }

        /** setGlobal, tolerating a model that declared no such global (a parameter a scope rule never reads is not
         *  registered as a global by the transpiler — writing it would throw; the parametergate already guaranteed
         *  every scope-referenced parameter has a value). */
        private static void setGlobalVeilig(KieSession sessie, String naam, Object waarde) {
            try { sessie.setGlobal(naam, waarde); }
            catch (RuntimeException onbekend) { /* global not declared: harmless — no rule reads it */ }
        }

        // ---- comparison ----

        private VerwachtingResultaat vergelijk(Verwachting v, KieSession sessie) {
            return switch (v.soort()) {
                case ATTRIBUUT_WAARDE -> attribuut(v, sessie, false);
                case ATTRIBUUT_LEEG   -> attribuut(v, sessie, true);
                case KENMERK_AANWEZIG -> kenmerk(v, true);
                case KENMERK_AFWEZIG  -> kenmerk(v, false);
                case CONSISTENT       -> consistentie(v, sessie, false);
                case INCONSISTENT     -> consistentie(v, sessie, true);
            };
        }

        private VerwachtingResultaat attribuut(Verwachting v, KieSession sessie, boolean leegVerwacht) {
            Object bean = labelNaarBean.get(v.label());
            Object werkelijk = bean == null ? null : lees(bean, Namen.getter(v.attribuut()));
            if (leegVerwacht) {
                boolean ok = werkelijk == null;
                return new VerwachtingResultaat(v.bron(), "leeg", render(werkelijk), ok, v.line());
            }
            Waarde verw = v.verwachteWaarde();
            boolean ok = gelijk(verw, werkelijk);
            return new VerwachtingResultaat(v.bron(), renderWaarde(verw), render(werkelijk), ok, v.line());
        }

        private VerwachtingResultaat kenmerk(Verwachting v, boolean aanwezigVerwacht) {
            Object bean = labelNaarBean.get(v.label());
            Object gelezen = bean == null ? null : lees(bean, Namen.getter(v.kenmerk()));   // get<X> or is<X>
            boolean aanwezig = Boolean.TRUE.equals(gelezen);
            boolean ok = aanwezig == aanwezigVerwacht;
            return new VerwachtingResultaat(v.bron(), aanwezigVerwacht ? "aanwezig" : "afwezig",
                    aanwezig ? "aanwezig" : "afwezig", ok, v.line());
        }

        private VerwachtingResultaat consistentie(Verwachting v, KieSession sessie, boolean inconsistentVerwacht) {
            long aantal = 0;
            try {
                Class<?> inc = Class.forName(FactModelGenerator.PAKKET + ".Inconsistentie", true, cl);
                aantal = sessie.getObjects(inc::isInstance).size();
            } catch (ClassNotFoundException e) { /* no Inconsistentie bean → none */ }
            boolean inconsistent = aantal > 0;
            boolean ok = inconsistent == inconsistentVerwacht;
            return new VerwachtingResultaat(v.bron(), inconsistentVerwacht ? "inconsistent" : "consistent",
                    inconsistent ? "inconsistent (" + aantal + " feit(en))" : "consistent", ok, v.line());
        }

        // ---- trace + coverage ----

        private Trace bouwTrace(Map<String, Integer> telling, Map<String, List<String>> labelsPerRegel) {
            List<Vuring> vuringen = new ArrayList<>();
            for (var e : telling.entrySet())
                vuringen.add(new Vuring(e.getKey(), labelsPerRegel.getOrDefault(e.getKey(), List.of()), "", e.getValue()));
            return new Trace(vuringen);
        }

        private List<DekkingRegel> bouwDekking(Scope scope, java.util.Set<String> gevuurd) {
            List<DekkingRegel> uit = new ArrayList<>();
            List<String> namen = scope.soort() == ScopeSoort.REGELGROEP ? scope.groepLeden()
                    : scope.regels().stream().filter(ScopeRegel::bestaat).map(ScopeRegel::naam).toList();
            for (String naam : namen) {
                Dekking d = zoekDekking(naam);
                DekkingSoort soort;
                if (d != null && !d.lowered()) soort = DekkingSoort.MARKER;
                else if (gevuurdBevat(gevuurd, naam)) soort = DekkingSoort.GEVUURD;
                else soort = DekkingSoort.NIET_GERAAKT;
                uit.add(new DekkingRegel(naam, soort));
            }
            return uit;
        }

        /** Coverage entry for a scope rule name, matching a possibly group-prefixed / version-suffixed manifest key. */
        private Dekking zoekDekking(String naam) {
            String n = norm(naam);
            Dekking exact = dekkingPerRegel.get(basisNaam(naam));
            if (exact != null && norm(basisNaam(exact.naam())).equals(n)) return exact;
            for (Dekking d : dekkingPerRegel.values())
                if (norm(basisNaam(d.naam())).equals(n)) return d;
            return null;
        }

        private static boolean gevuurdBevat(java.util.Set<String> gevuurd, String naam) {
            String n = norm(naam);
            for (String g : gevuurd) if (norm(basisNaam(g)).equals(n)) return true;
            return false;
        }

        private static String diagnose(Status status, List<VerwachtingResultaat> vr, List<DekkingRegel> dekking) {
            if (status == Status.PASS) return "";
            if (status == Status.MARKER) {
                String m = dekking.stream().filter(d -> d.soort() == DekkingSoort.MARKER)
                        .map(DekkingRegel::regel).findFirst().orElse("?");
                return "scope-regel '" + m + "' is niet-gelowerd (marker) — accepted-but-inert";
            }
            StringBuilder b = new StringBuilder();
            for (VerwachtingResultaat r : vr)
                if (!r.ok()) {
                    if (b.length() > 0) b.append("; ");
                    b.append("regel ").append(r.line()).append(" '").append(r.bron().trim())
                     .append("': verwacht ").append(r.verwacht()).append(", werkelijk ").append(r.werkelijk());
                }
            return b.toString();
        }
    }

    // ---------------- value helpers ----------------

    private static boolean gelijk(Waarde verw, Object werkelijk) {
        if (verw == null || verw.leeg()) return werkelijk == null;
        if (verw.getal() != null) return werkelijk instanceof BigDecimal b && verw.getal().compareTo(b) == 0;
        if (verw.datum() != null) return verw.datum().equals(werkelijk);
        if (verw.datumTijd() != null) return verw.datumTijd().equals(werkelijk);
        if (verw.bool() != null) return verw.bool().equals(werkelijk);
        if (verw.enumWaarde() != null) return verw.enumWaarde().equals(werkelijk);
        if (verw.tekst() != null) return verw.tekst().equals(werkelijk);
        return werkelijk == null;
    }

    /** The Java value to pass into a setter / global — the already-normalised value the IR carries (a
     *  BigDecimal / LocalDate / LocalDateTime / Boolean / String; an enumeratiewaarde is its String label). */
    private static Object waardeNaarArg(Waarde w) {
        if (w == null || w.leeg()) return null;
        if (w.getal() != null) return w.getal();
        if (w.datum() != null) return w.datum();
        if (w.datumTijd() != null) return w.datumTijd();
        if (w.bool() != null) return w.bool();
        if (w.enumWaarde() != null) return w.enumWaarde();   // an enumeratiewaarde is its String label
        return w.tekst();
    }

    /** The setter's declared parameter type for a datatype (matches FactModelGenerator.javaType). */
    private static Class<?> argType(Datatype dt) {
        if (dt == null) return Object.class;
        if (dt.isNumeriek() || dt.isPercentage()) return BigDecimal.class;
        if (dt.isTekst() || dt instanceof Datatype.Enumeratie) return String.class;
        if (dt instanceof Datatype.Booleaan) return Boolean.class;
        if (dt instanceof Datatype.DatumInDagen) return LocalDate.class;
        if (dt instanceof Datatype.DatumTijd) return LocalDateTime.class;
        return Object.class;
    }

    private static void roep(Object bean, String methode, Class<?> type, Object waarde) {
        try { bean.getClass().getMethod(methode, type).invoke(bean, waarde); }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kon setter " + methode + "(" + type.getSimpleName() + ") niet aanroepen op "
                    + bean.getClass().getSimpleName(), e);
        }
    }

    private static Object lees(Object bean, String getter) {
        // a kenmerk uses the isX form; try getX first, then isX (Namen.getter always yields getX).
        for (String m : new String[]{ getter, "is" + getter.substring(3) }) {
            try { return bean.getClass().getMethod(m).invoke(bean); }
            catch (NoSuchMethodException ignore) { /* try next form */ }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("kon " + m + " niet lezen", e); }
        }
        throw new IllegalStateException("geen getter " + getter + " op " + bean.getClass().getSimpleName());
    }

    private static String render(Object o) {
        if (o == null) return "leeg";
        return o.toString();
    }

    private static String renderWaarde(Waarde w) {
        if (w == null || w.leeg()) return "leeg";
        if (w.getal() != null) return w.getal().toPlainString();
        if (w.datum() != null) return w.datum().toString();
        if (w.datumTijd() != null) return w.datumTijd().toString();
        if (w.bool() != null) return w.bool().toString();
        if (w.enumWaarde() != null) return "'" + w.enumWaarde() + "'";
        return "\"" + w.tekst() + "\"";
    }

    /** Remove every DRL {@code rule "…" … end} block whose body carries an honest marker ({@code /* TODO} or
     *  {@code // onopgelost}) — the MVEL dialect cannot parse the placeholder, so keeping the block would sink the
     *  whole compilation. A marker rule produces no value (accepted-but-inert), so dropping it is behaviour-neutral;
     *  the Dekking manifest still flags it MARKER for the coverage/exit-3 verdict. The package header and every
     *  lowered rule are preserved verbatim. */
    static String stripMarkerRegels(String drl) {
        String[] regels = drl.split("\n", -1);
        StringBuilder uit = new StringBuilder();
        List<String> blok = new ArrayList<>();
        boolean inRegel = false;
        for (String r : regels) {
            String t = r.stripLeading();
            if (!inRegel && t.startsWith("rule ")) { inRegel = true; blok.clear(); blok.add(r); continue; }
            if (inRegel) {
                blok.add(r);
                if (t.equals("end") || t.startsWith("end ")) {
                    inRegel = false;
                    String tekst = String.join("\n", blok);
                    // Detect the marker sentinels the SAME way the Dekking manifest does (DrlTranspiler.markerVerdict):
                    // over the text with STRING LITERALS blanked, so a genuinely-lowered rule whose emitted DRL carries
                    // a Tekst/enum literal like "/* TODO …" or "// onopgelost" (a value the model compares against) is
                    // NOT mistaken for a marker and stripped. Mirrors DrlTranspiler.zonderStrings.
                    String zonderStrings = tekst.replaceAll("\"(?:\\\\.|[^\"\\\\\n])*\"", "\"\"");
                    if (!zonderStrings.contains("/* TODO") && !zonderStrings.contains("// onopgelost"))
                        uit.append(tekst).append('\n');
                }
                continue;
            }
            uit.append(r).append('\n');
        }
        if (uit.length() > 0) uit.setLength(uit.length() - 1);   // drop the trailing newline we added last
        return uit.toString();
    }

    /** Strip a §9.10 "<groep>: " prefix and a §13.4.2.3 " #k" version suffix off an emitted rule name. */
    static String basisNaam(String naam) {
        if (naam == null) return "";
        String n = naam;
        int hash = n.indexOf(" #");
        if (hash > 0) n = n.substring(0, hash);
        int colon = n.indexOf(": ");
        if (colon > 0) n = n.substring(colon + 2);
        return n.trim();
    }

    private static String norm(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(); }
}
