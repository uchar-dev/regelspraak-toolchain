package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.*;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Parameter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Front-end for the scenariotoetsen extension (docs/SCENARIOTOETSEN.md — a DOCUMENTED EXTENSION reachable
 * only via {@code testDocument}, not a §13 construct). It:
 * <ol>
 *   <li>parses the source under {@code testDocument};</li>
 *   <li>runs the NON-scenario constructs (definities / regelgroepen / regels / beslistabellen) through the
 *       EXISTING production pipeline — {@link DefinitionResolver#build} + {@link RuleChecker#check} — unchanged,
 *       so {@link Resolver#resolve}'s behaviour is byte-identical (a synthetic production document is populated
 *       with those children and handed to the same two calls);</li>
 *   <li>resolves the {@code Situatie} definitions and the {@code Scenario} blocks into {@link ResolvedScenario}
 *       IR, type-checking every situatie/verwachting value against the typeringen machinery and enforcing the
 *       § "Semantiek"/"Gates" checks (each failure a resolver ERROR with the source line).</li>
 * </ol>
 * The result is a {@link ScenarioBundel}: the built {@link RegelSpraakModel} (carrying ALL diagnostics — the
 * production ones AND the scenario ones, line-numbered) plus the resolved scenarios. The runner layer
 * ({@code engine/}) consumes the bundle; it never re-reads the parse tree.
 */
public final class ScenarioResolver {

    /** The resolver output: the built model (with every diagnostic) and the resolved scenarios. */
    public record ScenarioBundel(RegelSpraakModel model, List<ResolvedScenario> scenarios) {
        public boolean isOk() { return model.isOk(); }
    }

    private final RegelSpraakModel model;
    private final Map<String, BlokResultaat> situaties = new LinkedHashMap<>(); // genormaliseerde naam -> gevalideerd blok

    private ScenarioResolver(RegelSpraakModel model) { this.model = model; }

    // ---------------- entry point ----------------

    public static ScenarioBundel resolve(String bron) {
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(bron));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RegelSpraak parser = new RegelSpraak(tokens);

        List<Diagnostic> syntax = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                              String msg, RecognitionException e) {
                syntax.add(new Diagnostic(Severity.ERROR, "syntaxfout: " + msg, line));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        parser.removeErrorListeners(); parser.addErrorListener(listener);

        TestDocumentContext test = parser.testDocument();

        // Reuse the production pipeline UNCHANGED: build a synthetic regelSpraakDocument holding exactly the
        // non-scenario children (definitie / regelGroep / regel / beslistabel), in document order, and hand it
        // to the same DefinitionResolver.build + RuleChecker.check that Resolver.resolve calls. ANTLR's typed
        // accessors scan `children` dynamically, so re-parenting the children is enough — no change to those
        // classes, so Resolver.resolve stays byte-identical.
        RegelSpraakDocumentContext prod = productieDocument(test);
        RegelSpraakModel model = new DefinitionResolver().build(prod);
        model.diagnostics.addAll(syntax);
        new RuleChecker(model).check(prod);

        ScenarioResolver r = new ScenarioResolver(model);
        List<ResolvedScenario> scenarios = r.resolveScenarios(test);
        return new ScenarioBundel(model, scenarios);
    }

    /** A synthetic {@code regelSpraakDocument} carrying the non-scenario children of {@code test}, in order. */
    private static RegelSpraakDocumentContext productieDocument(TestDocumentContext test) {
        RegelSpraakDocumentContext prod = new RegelSpraakDocumentContext(null, 0);
        if (test.children != null)
            for (ParseTree kind : test.children)
                if (kind instanceof DefinitieContext || kind instanceof RegelGroepContext
                        || kind instanceof RegelContext || kind instanceof BeslistabelContext) {
                    prod.addAnyChild(kind);
                }
        return prod;
    }

    // ---------------- scenario resolution ----------------

    private List<ResolvedScenario> resolveScenarios(TestDocumentContext test) {
        List<ResolvedScenario> uit = new ArrayList<>();
        if (test.children == null) return uit;
        for (ParseTree kind : test.children) {
            if (kind instanceof SituatieDefinitieContext sd) registreerSituatie(sd);
            else if (kind instanceof ScenarioContext sc) uit.add(resolveScenario(sc));
        }
        return uit;
    }

    /** Record + type-check a herbruikbare situatie for declaratie-vóór-gebruik lookup; a duplicate name is an
     *  ERROR. The block is validated ONCE here (so an UNused situatie is still type-checked, per the contract);
     *  a scenario's merge later combines the pre-validated result without re-reporting. */
    private void registreerSituatie(SituatieDefinitieContext sd) {
        String naam = normSituatie(src(sd.situatieNaam()));
        int line = sd.getStart().getLine();
        if (situaties.containsKey(naam))
            fout(line, "situatie '" + src(sd.situatieNaam()) + "' is meer dan eens gedeclareerd");
        situaties.put(naam, valideerBlok(sd.situatieBlok()));
    }

    private ResolvedScenario resolveScenario(ScenarioContext sc) {
        int line = sc.getStart().getLine();
        String naam = src(sc.scenarioNaam());

        Scope scope = resolveScope(sc.scenarioScope());

        // peildatum (optioneel)
        java.time.temporal.Temporal peildatum = null;
        if (sc.scenarioPeildatum() != null)
            peildatum = leesDatumTijd(sc.scenarioPeildatum().datumWaarde());

        // situatiebronnen: gebaseerd-op (in volgorde), dan inline. A scenario met geen enkele bron is een ERROR.
        List<BlokResultaat> bronnen = new ArrayList<>();
        for (ScenarioBasisContext basis : sc.scenarioBasis()) {
            String sitNaam = src(basis.situatieNaam());
            BlokResultaat blok = situaties.get(normSituatie(sitNaam));
            if (blok == null)
                fout(basis.getStart().getLine(),
                        "onbekende of nog niet gedeclareerde situatie '" + sitNaam + "' (declareer een 'Situatie "
                        + sitNaam + ":' vóór dit scenario)");
            else bronnen.add(blok);
        }
        if (sc.inlineSituatie() != null) bronnen.add(valideerBlok(sc.inlineSituatie().situatieBlok()));
        if (sc.scenarioBasis().isEmpty() && sc.inlineSituatie() == null)
            fout(line, "scenario '" + naam + "' heeft geen enkele situatiebron (gebaseerd op … of een inline "
                    + "'Gegeven is de volgende situatie:')");

        Situatie situatie = mergeSituaties(bronnen);
        checkRolDoelen(situatie);

        // peildatum-gate + parametergate over de scope
        peildatumGate(scope, peildatum, line);
        parameterGate(scope, situatie, line);

        List<Verwachting> verwachtingen = new ArrayList<>();
        for (VerwachtingContext v : sc.verwachting()) verwachtingen.add(resolveVerwachting(v, situatie));

        return new ResolvedScenario(naam, scope, peildatum, situatie, verwachtingen, line);
    }

    // ---------------- scope ----------------

    private Scope resolveScope(ScenarioScopeContext scope) {
        if (scope instanceof ScopeEnkeleRegelContext s) {
            ScopeRegel r = scopeRegel(s.regelReferentie());
            return new Scope(ScopeSoort.ENKELE_REGEL, List.of(r), List.of());
        }
        if (scope instanceof ScopeMeerdereRegelsContext s) {
            List<ScopeRegel> regels = new ArrayList<>();
            for (RegelReferentieContext ref : s.regelReferentie()) regels.add(scopeRegel(ref));
            return new Scope(ScopeSoort.MEERDERE_REGELS, regels, List.of());
        }
        ScopeRegelgroepContext s = (ScopeRegelgroepContext) scope;
        String groep = src(s.regelReferentie());
        var g = model.regelGroepen.stream().filter(rg -> normNaam(rg.naam()).equals(normNaam(groep)))
                .findFirst().orElse(null);
        if (g == null) {
            fout(s.getStart().getLine(), "onbekende regelgroep '" + groep + "' in scope");
            return new Scope(ScopeSoort.REGELGROEP, List.of(new ScopeRegel(groep, false)), List.of());
        }
        return new Scope(ScopeSoort.REGELGROEP, List.of(new ScopeRegel(groep, true)), List.copyOf(g.leden()));
    }

    /** Resolve one scope rule reference, checking it names a rule that exists in the model. */
    private ScopeRegel scopeRegel(RegelReferentieContext ref) {
        String naam = src(ref);
        boolean bestaat = regelBestaat(naam);
        if (!bestaat)
            fout(ref.getStart().getLine(), "onbekende regel '" + naam + "' in scope");
        return new ScopeRegel(naam, bestaat);
    }

    /** Does the model declare a rule named {@code naam} (a top-level regel or a beslistabel row's parent)? */
    private boolean regelBestaat(String naam) {
        String n = normNaam(naam);
        return model.regels.stream().anyMatch(r -> normNaam(r.naam()).equals(n));
    }

    // ---------------- situatie validatie + merge ----------------

    /** The validated, assembled result of ONE situatieblok: its instantie fragments (per label, in first-seen
     *  order) and its parameterwaarden (in order). Type errors were reported when the block was validated;
     *  merging combines these fragments without re-reporting. */
    private record BlokResultaat(List<InstantieFragment> instanties, List<ParameterWaarde> parameters) {}
    /** One block's contribution to a labelled instance (its type as declared here + its assignments). */
    private record InstantieFragment(String label, Objecttype objecttype, List<AttribuutWaarde> attributen,
                                     List<RolWaarde> rollen, List<KenmerkWaarde> kenmerken, int line) {}

    /** Type-check ONE situatieblok and assemble it into a {@link BlokResultaat}, reporting every label/attr/
     *  rol/kenmerk/parameter/unit error exactly once (with its source line). */
    private BlokResultaat valideerBlok(SituatieBlokContext blok) {
        List<InstantieFragment> instanties = new ArrayList<>();
        List<ParameterWaarde> parameters = new ArrayList<>();
        for (SituatieOnderdeelContext ond : blok.situatieOnderdeel()) {
            if (ond instanceof InstantieOnderdeelContext io) instanties.add(valideerInstantie(io.instantie()));
            else if (ond instanceof ParameterWaardeOnderdeelContext po) {
                ParameterWaarde pw = valideerParameter(po.parameterWaarde());
                if (pw != null) parameters.add(pw);
            }
        }
        return new BlokResultaat(instanties, parameters);
    }

    /** Type-check one instantie declaration and assemble its assignments (type/label/attr/rol/kenmerk). */
    private InstantieFragment valideerInstantie(InstantieContext inst) {
        int line = inst.getStart().getLine();
        String typeNaam = src(inst.objectTypeNaam());
        String label = inst.UPPER_ID().getText();
        Objecttype ot = model.objecttypeOpNaam(typeNaam);
        if (ot == null) fout(line, "onbekend objecttype '" + typeNaam + "'");
        if (labelBotstMetType(label))
            fout(line, "label '" + label + "' botst met (het eerste woord van) een objecttypenaam");

        List<AttribuutWaarde> attributen = new ArrayList<>();
        List<RolWaarde> rollen = new ArrayList<>();
        List<KenmerkWaarde> kenmerken = new ArrayList<>();
        for (ToewijzingContext t : inst.toewijzing()) valideerToewijzing(t, ot, attributen, rollen, kenmerken);
        return new InstantieFragment(label, ot, attributen, rollen, kenmerken, line);
    }

    private void valideerToewijzing(ToewijzingContext t, Objecttype ot, List<AttribuutWaarde> attributen,
                                    List<RolWaarde> rollen, List<KenmerkWaarde> kenmerken) {
        if (t instanceof AttribuutToewijzingContext a) {
            int line = a.getStart().getLine();
            String attr = stripArtikel(src(a.toewijzingNaam()));
            Datatype dt = ot == null ? null : ot.attribuut(attr);
            if (ot != null && dt == null) { fout(line, "onbekend attribuut '" + attr + "' op objecttype '" + ot.naam + "'"); return; }
            Waarde w = leesEnTypeer(a.situatieWaarde(), dt, line, "attribuut '" + attr + "'");
            if (w != null) attributen.add(new AttribuutWaarde(attr, dt, w, line));
        } else if (t instanceof RolToOneToewijzingContext r) {
            int line = r.getStart().getLine();
            String rol = stripArtikel(src(r.toewijzingNaam()));
            checkRol(ot, rol, false, line);
            rollen.add(new RolWaarde(rol, List.of(r.UPPER_ID().getText()), false, line));
        } else if (t instanceof RolToManyToewijzingContext r) {
            int line = r.getStart().getLine();
            String rol = stripArtikel(src(r.toewijzingNaam()));
            checkRol(ot, rol, true, line);
            List<String> labels = new ArrayList<>();
            for (var u : r.UPPER_ID()) labels.add(u.getText());
            rollen.add(new RolWaarde(rol, labels, true, line));
        } else if (t instanceof KenmerkToewijzingContext k) {
            int line = k.getStart().getLine();
            boolean bezittelijk = k.HEEFT() != null;
            String geschreven = stripArtikel(src(k.kaleNaam()));
            String kenmerk = ot == null ? null : KenmerkMatcher.vind(ot, geschreven);
            if (ot != null && kenmerk == null) { fout(line, "onbekend kenmerk '" + geschreven + "' op objecttype '" + ot.naam + "'"); return; }
            kenmerken.add(new KenmerkWaarde(kenmerk == null ? geschreven : kenmerk, bezittelijk, line));
        }
    }

    /** Merge the pre-validated situatie sources in order into one composite situatie. Same LABEL = same instance
     *  (extra assignments accumulate; a re-assignment of the same attribuut/rol wins later); a later parameter-
     *  waarde overrides an earlier one (§ "Semantiek"). No re-reporting — each block was already validated. */
    private Situatie mergeSituaties(List<BlokResultaat> bronnen) {
        Map<String, InstantieBouwer> instanties = new LinkedHashMap<>(); // label -> bouwer (insertion order)
        Map<String, ParameterWaarde> parameters = new LinkedHashMap<>(); // param-naam -> laatste waarde

        for (BlokResultaat blok : bronnen) {
            for (InstantieFragment frag : blok.instanties()) {
                InstantieBouwer bouwer = instanties.get(frag.label());
                if (bouwer == null) {
                    bouwer = new InstantieBouwer(frag.label(), frag.objecttype());
                    instanties.put(frag.label(), bouwer);
                } else if (frag.objecttype() != null && bouwer.objecttype != null && bouwer.objecttype != frag.objecttype()) {
                    fout(frag.line(), "label '" + frag.label() + "' is eerder gedeclareerd als '"
                            + bouwer.objecttype.naam + "', hier als '" + frag.objecttype().naam + "'");
                }
                frag.attributen().forEach(bouwer::zetAttribuut);
                frag.rollen().forEach(bouwer::zetRol);
                frag.kenmerken().forEach(bouwer::zetKenmerk);
            }
            for (ParameterWaarde pw : blok.parameters()) parameters.put(normNaam(pw.parameter()), pw);
        }

        List<Instantie> uit = new ArrayList<>();
        for (InstantieBouwer b : instanties.values()) uit.add(b.bouw());
        return new Situatie(uit, new ArrayList<>(parameters.values()));
    }

    /** Post-merge: every rol-doelwaarde ('de reis is V1') must name a LABEL that exists in the SAMENGESTELDE
     *  situatie, and that label's objecttype must match the rol's doeltype — cross-source references (a rol in
     *  een gebaseerd-op-situatie naar een inline-instantie) are legal, so this runs on the merged result, not
     *  per blok. Zonder deze check zou een dangling/mis-getypeerde rol pas bij het vuren opvallen — precies de
     *  fire-time verrassing die het contract verbiedt. */
    private void checkRolDoelen(Situatie situatie) {
        for (Instantie i : situatie.instanties()) {
            if (i.objecttype() == null) continue;                              // onbekend type: al gemeld
            for (RolWaarde r : i.rollen()) {
                Objecttype doel = model.rolDoeltypeNaarSubject(i.objecttype(), r.rol());
                if (doel == null) continue;                                    // onbekende rol: al gemeld
                for (String label : r.doelLabels()) {
                    Instantie ref = situatie.instantie(label);
                    if (ref == null)
                        fout(r.line(), "rol '" + r.rol() + "' verwijst naar label '" + label
                                + "' dat nergens in de samengestelde situatie gedeclareerd is");
                    else if (ref.objecttype() != null && ref.objecttype() != doel)
                        fout(r.line(), "rol '" + r.rol() + "' verwacht een " + doel.naam
                                + ", maar label '" + label + "' is een " + ref.objecttype().naam);
                }
            }
        }
    }

    /** Check that role {@code rol} joins {@code ot} to another objecttype, with the right arity for the verb
     *  used (to-one → {@code is}, to-many → {@code zijn}-list). */
    private void checkRol(Objecttype ot, String rol, boolean meervoudVerb, int line) {
        if (ot == null) return;
        Objecttype doel = model.rolDoeltypeNaarSubject(ot, rol);
        if (doel == null) { fout(line, "onbekende rol '" + rol + "' vanaf objecttype '" + ot.naam + "'"); return; }
        boolean meervoudig = model.naarVeelRol(ot, doel, rol);
        if (meervoudig && !meervoudVerb)
            fout(line, "rol '" + rol + "' is een naar-veel-rol; gebruik de 'zijn'-lijstvorm ('de " + rol + " zijn …')");
        if (!meervoudig && meervoudVerb)
            fout(line, "rol '" + rol + "' is een naar-één-rol; gebruik de 'is'-vorm ('de " + rol + " is …')");
    }

    private ParameterWaarde valideerParameter(ParameterWaardeContext pw) {
        int line = pw.getStart().getLine();
        String geschreven = src(pw.parameterNaam());       // as written, incl. article (for a clear diagnostic)
        String naam = stripArtikel(geschreven);
        Parameter p = parameterOpNaam(naam);
        if (p == null) { fout(line, "onbekende parameter '" + geschreven + "'"); return null; }
        Waarde w = leesEnTypeer(pw.situatieWaarde(), p.datatype(), line, "parameter '" + p.naam() + "'");
        return w == null ? null : new ParameterWaarde(p.naam(), p.datatype(), w, line);
    }

    // ---------------- verwachting ----------------

    private Verwachting resolveVerwachting(VerwachtingContext v, Situatie situatie) {
        if (v instanceof VerwachtAttribuutLeegContext a) {
            int line = a.getStart().getLine();
            String[] al = attrEnLabel(src(a.verwachtingNaam()), a.UPPER_ID() == null ? null : a.UPPER_ID().getText());
            String attr = al[0], label = al[1];
            checkVerwachtAttribuut(label, attr, situatie, line);
            return new Verwachting(VerwachtingSoort.ATTRIBUUT_LEEG, label, attr, null, false, Waarde.LEEG, line, src(a));
        }
        if (v instanceof VerwachtAttribuutWaardeContext a) {
            int line = a.getStart().getLine();
            String[] al = attrEnLabel(src(a.verwachtingNaam()), a.UPPER_ID() == null ? null : a.UPPER_ID().getText());
            String attr = al[0], label = al[1];
            Datatype dt = checkVerwachtAttribuut(label, attr, situatie, line);
            Waarde w = leesEnTypeer(a.situatieWaarde(), dt, line, "verwachting voor '" + attr + "'");
            return new Verwachting(VerwachtingSoort.ATTRIBUUT_WAARDE, label, attr, null, false,
                    w == null ? Waarde.LEEG : w, line, src(a));
        }
        if (v instanceof VerwachtKenmerkContext k) return verwachtKenmerk(
                k.UPPER_ID().getText(), stripArtikel(src(k.kaleNaam())), false, situatie, k.getStart().getLine(), src(k));
        if (v instanceof VerwachtKenmerkHeeftContext k) return verwachtKenmerk(
                k.UPPER_ID().getText(), stripArtikel(src(k.kaleNaam())), true, situatie, k.getStart().getLine(), src(k));
        if (v instanceof VerwachtInconsistentContext c)
            return new Verwachting(VerwachtingSoort.INCONSISTENT, null, null, null, false, null, c.getStart().getLine(), src(c));
        VerwachtConsistentContext c = (VerwachtConsistentContext) v;
        return new Verwachting(VerwachtingSoort.CONSISTENT, null, null, null, false, null, c.getStart().getLine(), src(c));
    }

    private Verwachting verwachtKenmerk(String label, String geschreven, boolean bezittelijk,
                                        Situatie situatie, int line, String bron) {
        // `geen`-prefix strip: "geen <kenmerk>" is the absent form (grammar keeps `geen` inside the kaleNaam).
        boolean afwezig = false;
        String naam = geschreven;
        if (naam.toLowerCase().startsWith("geen ")) { afwezig = true; naam = naam.substring(5).trim(); }
        Objecttype ot = labelType(label, situatie);
        if (ot == null) fout(line, "onbekend label '" + label + "' in verwachting");
        String kenmerk = ot == null ? null : KenmerkMatcher.vind(ot, naam);
        if (ot != null && kenmerk == null) fout(line, "onbekend kenmerk '" + naam + "' op objecttype '" + ot.naam + "'");
        return new Verwachting(afwezig ? VerwachtingSoort.KENMERK_AFWEZIG : VerwachtingSoort.KENMERK_AANWEZIG,
                label, null, kenmerk == null ? naam : kenmerk, bezittelijk, null, line, bron);
    }

    /** Check the LABEL exists in the merged situatie and its type declares the attribute; return the datatype. */
    private Datatype checkVerwachtAttribuut(String label, String attr, Situatie situatie, int line) {
        Objecttype ot = labelType(label, situatie);
        if (ot == null) { fout(line, "onbekend label '" + label + "' in verwachting"); return null; }
        Datatype dt = ot.attribuut(attr);
        if (dt == null) fout(line, "onbekend attribuut '" + attr + "' op objecttype '" + ot.naam + "'");
        return dt;
    }

    /** Split a verwachting's "de &lt;attribuut&gt; van &lt;LABEL&gt;" head into (attribuut, label). The greedy
     *  {@code kaleNaam} of {@code verwachtingNaam} normally stops before the final "van &lt;LABEL&gt;" (ANTLR's
     *  lookahead leaves the {@code VAN UPPER_ID} for the outer rule, so {@code upperId} is non-null and the
     *  attribuut is the whole verwachtingNaam). When it instead absorbs the trailing "van &lt;LABEL&gt;"
     *  ({@code upperId} == null), re-split at the LAST " van " so the attribuut and the label are still
     *  recovered — the "attribuut-vs-rol-lezing" open-risk handled defensively rather than as an NPE. */
    private static String[] attrEnLabel(String verwachtingNaam, String upperId) {
        String kop = stripArtikel(verwachtingNaam);
        if (upperId != null) return new String[]{ kop, upperId };
        int idx = kop.toLowerCase().lastIndexOf(" van ");
        if (idx < 0) return new String[]{ kop, "" };
        return new String[]{ kop.substring(0, idx).trim(), kop.substring(idx + 5).trim() };
    }

    private Objecttype labelType(String label, Situatie situatie) {
        Instantie i = situatie.instantie(label);
        return i == null ? null : i.objecttype();
    }

    // ---------------- gates ----------------

    /** peildatum-gate: a scope rule referencing Rekendatum/Rekenjaar needs a peildatum (§ "Semantiek"). */
    private void peildatumGate(Scope scope, java.time.temporal.Temporal peildatum, int line) {
        if (peildatum != null) return;
        for (String regel : scopeRegelNamen(scope))
            if (regelGebruiktRekendatum(regel)) {
                fout(line, "scope-regel '" + regel + "' gebruikt de Rekendatum/Rekenjaar maar het scenario heeft "
                        + "geen peildatum (voeg 'met peildatum <datum>' toe)");
                return;
            }
    }

    /** parametergate: every parameter a scope rule references must have a value in the merged situatie. */
    private void parameterGate(Scope scope, Situatie situatie, int line) {
        java.util.Set<String> gezet = new java.util.HashSet<>();
        for (ParameterWaarde p : situatie.parameters()) gezet.add(normNaam(p.parameter()));
        java.util.LinkedHashSet<String> gemeld = new java.util.LinkedHashSet<>();
        for (String regel : scopeRegelNamen(scope))
            for (String param : regelParameters(regel))
                if (!gezet.contains(normNaam(param)) && gemeld.add(normNaam(param)))
                    fout(line, "scope-regel '" + regel + "' verwijst naar parameter '" + param
                            + "' die geen waarde heeft in de samengestelde situatie");
    }

    /** The rule names a scope covers: the scope's own rule names, or a regelgroep's member names. */
    private List<String> scopeRegelNamen(Scope scope) {
        if (scope.soort() == ScopeSoort.REGELGROEP) return scope.groepLeden();
        List<String> uit = new ArrayList<>();
        for (ScopeRegel r : scope.regels()) if (r.bestaat()) uit.add(r.naam());
        return uit;
    }

    /** Whether any resolved construct named {@code regel} references the Rekendatum/Rekenjaar (as a value or a
     *  §4.2 rule-version window) — walked over the rule's waarde/voorwaarde/doel Expr trees and conditions. */
    private boolean regelGebruiktRekendatum(String regel) {
        String n = normNaam(regel);
        for (var r : model.resolvedRegels)
            if (naamMatcht(r.naam(), n)) {
                if (!r.geldigheid().altijd()) return true;   // §4.2 version window guards on Rekendatum
                if (Expr.bevat(r.waarde(), Expr::isRekenConstante) || Expr.bevat(r.doel(), Expr::isRekenConstante)) return true;
                if (Conditie.bevatOperand(r.voorwaarde(), Expr::isRekenConstante)) return true;
            }
        for (var c : model.consistenties) if (naamMatcht(c.naam(), n)) {
            if (!c.geldigheid().altijd()) return true;
            if (Conditie.bevatOperand(c.assertie(), Expr::isRekenConstante)) return true;
        }
        return false;
    }

    /** The declared parameter names referenced by any resolved construct named {@code regel}. */
    private List<String> regelParameters(String regel) {
        String n = normNaam(regel);
        java.util.LinkedHashSet<String> uit = new java.util.LinkedHashSet<>();
        java.util.function.Consumer<Expr> vangExpr = e ->
                Expr.bevat(e, x -> { if (x instanceof Expr.ParameterRef pr) uit.add(pr.parameter().naam()); return false; });
        for (var r : model.resolvedRegels)
            if (naamMatcht(r.naam(), n)) {
                vangExpr.accept(r.waarde());
                vangExpr.accept(r.doel());
                Conditie.bevatOperand(r.voorwaarde(), x -> { if (x instanceof Expr.ParameterRef pr) uit.add(pr.parameter().naam()); return false; });
            }
        for (var c : model.consistenties)
            if (naamMatcht(c.naam(), n))
                Conditie.bevatOperand(c.assertie(), x -> { if (x instanceof Expr.ParameterRef pr) uit.add(pr.parameter().naam()); return false; });
        return new ArrayList<>(uit);
    }

    /** Match a possibly version-suffixed resolved-rule name ("<naam> #k") against a bare normalised name. */
    private static boolean naamMatcht(String resolvedNaam, String genormaliseerd) {
        String basis = resolvedNaam;
        int hash = basis.indexOf(" #");
        if (hash > 0) basis = basis.substring(0, hash);
        return normNaam(basis).equals(genormaliseerd);
    }

    // ---------------- value reading + normalisation ----------------

    /** Read a situatieWaarde and type-check + unit-normalise it against the target datatype {@code doel}. On a
     *  type/unit mismatch raises an ERROR with {@code line} and returns null; a convertible unit is exactly
     *  restated to the doel's declared unit. A null {@code doel} (unresolved target) reads the raw value. */
    private Waarde leesEnTypeer(SituatieWaardeContext ctx, Datatype doel, int line, String wat) {
        RuweWaarde ruw = leesRuw(ctx, line);
        if (ruw == null) return null;
        if (doel == null || doel.isOnbekend()) return ruw.zonderConversie();

        // Enumeratie: a scenario literal ('geboekt') carries no domein, so Typering.toekenning's same-domein
        // check (rij 7) cannot apply — instead validate membership against the TARGET domain's declared values.
        if (doel instanceof Datatype.Enumeratie e) {
            if (ruw.enumWaarde == null) {
                fout(line, wat + ": een enumeratie-attribuut verwacht een enum-literal ('waarde'), kreeg " + ruw.type.naam());
                return null;
            }
            boolean lid = e.waarden().stream().anyMatch(w -> w.equalsIgnoreCase(ruw.enumWaarde));
            if (!lid) {
                fout(line, wat + ": '" + ruw.enumWaarde + "' is geen waarde van enumeratie " + e.domein()
                        + " (" + String.join(", ", e.waarden()) + ")");
                return null;
            }
            return Waarde.vanEnum(ruw.enumWaarde);
        }

        String fout = Typering.toekenning(doel, ruw.type);
        if (fout != null) { fout(line, wat + ": " + fout); return null; }

        // Numeric: exactly restate to the doel's declared unit (2 uur op een minuut-attribuut -> 120).
        if (doel.isGetalachtig() && ruw.getal != null) {
            Eenheid bron = ruw.type.eenheidOf();
            Eenheid doelE = doel.eenheidOf();
            BigDecimal g = herschaal(ruw.getal, bron, doelE);
            return Waarde.vanGetal(g);
        }
        return ruw.zonderConversie();
    }

    /** Exactly restate {@code waarde} from unit {@code bron} to unit {@code doel}; identity when either is
     *  absent or they need no conversion. Uses the exact ×num/÷den factors when available (lossless), else the
     *  single ratio. */
    private static BigDecimal herschaal(BigDecimal waarde, Eenheid bron, Eenheid doel) {
        if (bron == null || doel == null || !bron.vereistOmrekening(doel)) return waarde;
        BigDecimal[] f = bron.exacteHerschaalFactoren(doel);
        BigDecimal uit;
        if (f != null) {
            BigDecimal num = waarde.multiply(f[0]);
            try { uit = num.divide(f[1]); }                        // exact when it terminates
            catch (ArithmeticException nietEindig) { uit = num.divide(f[1], MathContext.DECIMAL64); }
        } else {
            uit = waarde.multiply(bron.omrekenfactorNaar(doel));
        }
        // Strip the DECIMAL64 residual tail (the documented reverse-factor precision edge) so a clean value
        // stays clean: 2 u -> 120 minuut is stored as 120, not 120.0000000000000. compareTo already ignores
        // trailing zeros, but a clean scale keeps the IR and any equals()-based check exact.
        BigDecimal genormaliseerd = uit.stripTrailingZeros();
        return genormaliseerd.scale() < 0 ? genormaliseerd.setScale(0) : genormaliseerd;
    }

    /** The raw value + its intrinsic datatype (unit as written), before any target-driven conversion. */
    private record RuweWaarde(BigDecimal getal, LocalDate datum, LocalDateTime datumTijd, Boolean bool,
                              String tekst, String enumWaarde, Datatype type) {
        Waarde zonderConversie() {
            if (getal != null) return Waarde.vanGetal(getal);
            if (datum != null) return Waarde.vanDatum(datum);
            if (datumTijd != null) return Waarde.vanDatumTijd(datumTijd);
            if (bool != null) return Waarde.vanBoolean(bool);
            if (enumWaarde != null) return Waarde.vanEnum(enumWaarde);
            return Waarde.vanTekst(tekst);
        }
    }

    private RuweWaarde leesRuw(SituatieWaardeContext ctx, int line) {
        if (ctx == null) { fout(line, "kon de waarde niet lezen"); return null; }
        if (ctx instanceof GetalSituatieWaardeContext g) return leesGetal(g.getalWaarde(), line);
        if (ctx instanceof DatumSituatieWaardeContext d) {
            String tekst = d.datumWaarde().getText();
            if (tekst.matches(".*\\d{1,2}:\\d{2}:\\d{2}.*")) {
                LocalDateTime dt = leesLocalDateTime(tekst);
                return new RuweWaarde(null, null, dt, null, null, null, new Datatype.DatumTijd());
            }
            LocalDate dd = leesLocalDate(tekst);
            if (dd == null) { fout(line, "ongeldige datum '" + tekst + "'"); return null; }
            return new RuweWaarde(null, dd, null, null, null, null, new Datatype.DatumInDagen());
        }
        if (ctx instanceof BooleanSituatieWaardeContext b)
            return new RuweWaarde(null, null, null, "waar".equalsIgnoreCase(b.getText()), null, null, new Datatype.Booleaan());
        if (ctx instanceof EnumSituatieWaardeContext e) {
            String rauw = e.ENUM_LITERAL().getText();
            String waarde = rauw.length() >= 2 ? rauw.substring(1, rauw.length() - 1) : rauw;
            return new RuweWaarde(null, null, null, null, null, waarde,
                    new Datatype.Enumeratie(null, List.of(waarde)));
        }
        if (ctx instanceof TekstSituatieWaardeContext t) {
            String tekst = tekstInhoud(t.tekstWaarde());
            return new RuweWaarde(null, null, null, null, tekst, null, new Datatype.Tekst());
        }
        fout(line, "onbekende waardevorm '" + src(ctx) + "'");
        return null;
    }

    /** A getalWaarde: its exact BigDecimal value + its datatype (Percentage / Numeriek-with-unit / bare). */
    private RuweWaarde leesGetal(GetalWaardeContext g, int line) {
        String bron = src(g);
        BigDecimal[] r = io.github.yhavinga.regelspraak.model.Rationaal.van(bron);
        if (r == null) { fout(line, "ongeldig getal '" + bron + "'"); return null; }
        BigDecimal waarde;
        try { waarde = r[0].divide(r[1]); }
        catch (ArithmeticException nietEindig) { waarde = r[0].divide(r[1], MathContext.DECIMAL64); }
        int dec = Math.max(0, waarde.stripTrailingZeros().scale());
        boolean geheel = dec == 0;
        if (g.PERCENT_SIGN() != null)
            return new RuweWaarde(waarde, null, null, null, null, null, new Datatype.Percentage(geheel, dec));
        Eenheid e = g.eenheidExpressie() != null ? DefinitionResolver.eenheidVan(model, g.eenheidExpressie()) : null;
        return new RuweWaarde(waarde, null, null, null, null, null,
                new Datatype.Numeriek(geheel, dec, Datatype.Teken.ONBEPAALD, e));
    }

    // ---------------- date helpers (mirrors RuleChecker.grens) ----------------

    /** A datumWaarde into a LocalDate or LocalDateTime (peildatum: the time component decides). */
    private java.time.temporal.Temporal leesDatumTijd(DatumWaardeContext ctx) {
        String tekst = ctx.getText();
        if (tekst.matches(".*\\d{1,2}:\\d{2}:\\d{2}.*")) {
            LocalDateTime dt = leesLocalDateTime(tekst);
            if (dt == null) fout(ctx.getStart().getLine(), "ongeldige peildatum '" + tekst + "'");
            return dt;
        }
        LocalDate d = leesLocalDate(tekst);
        if (d == null) fout(ctx.getStart().getLine(), "ongeldige peildatum '" + tekst + "'");
        return d;
    }

    private static LocalDate leesLocalDate(String tekst) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matcher(tekst);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (java.time.DateTimeException e) { return null; }
    }

    private static LocalDateTime leesLocalDateTime(String tekst) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})\\s+(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{3}))?").matcher(tekst);
        if (!m.find()) return null;
        try {
            int ms = m.group(7) != null ? Integer.parseInt(m.group(7)) : 0;
            return LocalDateTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), ms * 1_000_000);
        } catch (java.time.DateTimeException e) { return null; }
    }

    // ---------------- small helpers ----------------

    /** Text content of a tekstWaarde (concatenated literal chunks; a scenario value has no «expressie»). */
    private static String tekstInhoud(TekstWaardeContext t) {
        StringBuilder sb = new StringBuilder();
        for (var deel : t.tekstReeksDeel())
            if (deel.STRING_TEXT() != null) sb.append(deel.STRING_TEXT().getText());
        return sb.toString();
    }

    private Parameter parameterOpNaam(String naam) {
        Parameter exact = model.parameters.get(naam);
        if (exact != null) return exact;
        for (var e : model.parameters.entrySet())
            if (normNaam(e.getKey()).equals(normNaam(naam))) return e.getValue();
        return null;
    }

    /** Does {@code label} equal (the first word of) a declared objecttype name? (a resolver ERROR — § "Situatieblok"). */
    private boolean labelBotstMetType(String label) {
        for (String naam : model.objecttypen.keySet()) {
            if (naam.equalsIgnoreCase(label)) return true;
            String eerste = naam.split("\\s+")[0];
            if (eerste.equalsIgnoreCase(label)) return true;
        }
        return false;
    }

    private static String normSituatie(String s) { return normNaam(s); }
    private static String normNaam(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(); }

    private static String src(ParserRuleContext ctx) { return DefinitionResolver.src(ctx); }

    /** Strip a leading article (de/het/een) from a name, keeping the rest verbatim. */
    private static String stripArtikel(String naam) {
        if (naam == null) return "";
        String n = naam.trim();
        for (String lw : new String[]{"de ", "het ", "een "})
            if (n.toLowerCase().startsWith(lw)) return n.substring(lw.length()).trim();
        return n;
    }

    private void fout(int line, String bericht) {
        model.diagnostics.add(new Diagnostic(Severity.ERROR, "scenario: " + bericht, line));
    }

    /** Accumulates the assignments to one LABELed instance across the merge (last write wins per attr/rol). */
    private static final class InstantieBouwer {
        final String label;
        Objecttype objecttype;
        private final Map<String, AttribuutWaarde> attributen = new LinkedHashMap<>();
        private final Map<String, RolWaarde> rollen = new LinkedHashMap<>();
        private final Map<String, KenmerkWaarde> kenmerken = new LinkedHashMap<>();

        InstantieBouwer(String label, Objecttype objecttype) { this.label = label; this.objecttype = objecttype; }

        void zetAttribuut(AttribuutWaarde w) { attributen.put(normNaam(w.attribuut()), w); }
        void zetRol(RolWaarde w) { rollen.put(normNaam(w.rol()), w); }
        void zetKenmerk(KenmerkWaarde w) { kenmerken.put(normNaam(w.kenmerk()), w); }

        Instantie bouw() {
            return new Instantie(label, objecttype, new ArrayList<>(attributen.values()),
                    new ArrayList<>(rollen.values()), new ArrayList<>(kenmerken.values()));
        }
    }
}
