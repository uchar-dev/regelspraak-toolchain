package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.DefaultLadder;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.Rung;
import io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie;
import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.ir.ResolvedVerdeling;
import io.github.yhavinga.regelspraak.ir.Bron;
import io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.*;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import io.github.yhavinga.regelspraak.kern.Literalen;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type-checks rules against the typeringen matrix (Phase: rule checking). It
 * infers an expression's datatype where the references resolve against the model
 * — literals, parameters, arithmetic, and attribute-of-subject references whose
 * subject is a plain object type — and applies {@link Typering}. References it
 * cannot resolve yield {@link Datatype.Onbekend}, which {@link Typering} treats
 * as "no claim", so the checker never raises a false positive on a construct it
 * does not yet fully resolve (e.g. deep van-chains or aggregations). The result
 * type rules are the spec's; the coverage is honest about its limits.
 */
final class RuleChecker {
    private final RegelSpraakModel model;
    private final ExpressieBouwer bouwer;
    /** Set (to the role the doel fills) only while re-resolving a §13.4.7 feitcreatie guard against the DOEL:
     *  it makes {@link #subjectKop} also accept "de &lt;rolC&gt;" as denoting the (re-)subject, and REJECT the
     *  bezielde "hij"/bare reference — which in a feitcreatie names the anker, not the doel. null otherwise. */
    private String doelRol = null;
    /** The {@code // Bron: …} statuutannotaties of the rule currently being resolved (the text after
     *  {@code Bron:}, in source order), set at the start of {@link #regelVersies} and carried onto every
     *  {@link ResolvedRegel} version it produces. Empty when the rule carried none. */
    private List<String> huidigeBronAnnotaties = List.of();

    /** The main parse's token stream, kept so the §Bron statuutannotaties on the HIDDEN channel
     *  (the {@code // Bron: …} comments) can be gathered directly above each rule. Null when the
     *  checker is driven without a stream (no annotations are then collected). */
    private final CommonTokenStream tokens;

    RuleChecker(RegelSpraakModel model) { this(model, null); }

    RuleChecker(RegelSpraakModel model, CommonTokenStream tokens) {
        this.model = model;
        this.tokens = tokens;
        this.bouwer = new ExpressieBouwer(model);
        this.bouwer.setSamengesteldeFilter(this::samengesteldeElementFilter);   // §13.4.13.4 compound subselectie
    }

    void check(RegelSpraakDocumentContext doc) {
        domeinReferenties(doc);
        // §9.10 Regelgroep (DOCUMENTED EXTENSION): a grouped rule resolves EXACTLY as an ungrouped one, so the
        // whole rule pipeline runs over the flattened list (top-level + group members, in document order).
        for (RegelContext r : DefinitionResolver.alleRegels(doc)) regelVersies(r);
        // §4 (LADDER-EVOLUTIE) the ordered exception blocks resolve like regels too, so their bodies get the same
        // type-checks; the group members were already resolved above (alleRegels includes them).
        for (UitzonderingRegelContext u : DefinitionResolver.alleUitzonderingen(doc)) uitzonderingVersies(u);
        beslistabellen(doc);
        regelGroepen(doc);   // build the §9.10 group IR + the fase-2 recursief structural checks
        keurKringverwijzingen();
        ladders(doc);        // §4 lift the base+exception families and unordered groups into DefaultLadder nodes
    }

    /** §9.10 build the {@link io.github.yhavinga.regelspraak.ir.ResolvedRegelGroep} carriers (member RULE NAMES + the
     *  {@code recursief} marker) and run the fase-2 structural checks — a DOCUMENTED EXTENSION (see
     *  docs/grammar-divergences.md '§9.10 recursie / regelgroep'). Members were already resolved as ungrouped
     *  rules above (their {@link ResolvedRegel}/objectcreatie/… entries keep the bare written name); this only
     *  records the grouping and validates the recursief marker. Fase-2 checks:
     *  (a) a recursief group with NO objectcreatie member -> ERROR (nothing can advance the chain);
     *  (b) an objectcreatie member WITHOUT any voorwaarde -> WARNING (termination is not automatable — the engine
     *      fire cap is the backstop, sound because the §13.4.6/§9 idempotence guard advances one generation per
     *      instance). The ungrouped cyclic-WARNING posture (RecursieTest) is unchanged. */
    private void regelGroepen(RegelSpraakDocumentContext doc) {
        for (RegelGroepContext g : doc.regelGroep()) {
            boolean recursief = g.RECURSIEF() != null;
            int line = g.getStart().getLine();
            List<String> leden = new ArrayList<>();
            for (GroepLidContext lid : g.groepLid())
                if (lid.regel() != null) leden.add(DefinitionResolver.src(lid.regel().regelNaam()));
                else if (lid.beslistabel() != null) leden.add(DefinitionResolver.src(lid.beslistabel().beslistabelNaam()));
            String naam = DefinitionResolver.src(g.kaleNaam());
            model.regelGroepen.add(new io.github.yhavinga.regelspraak.ir.ResolvedRegelGroep(naam, recursief, leden, bron(g.kaleNaam()), line));
            if (!recursief) continue;

            // §9.10 fase-2: which members are objectcreatie rules (by bare name, the same key the IR uses).
            List<ResolvedObjectCreatie> creaties = model.objectCreaties.stream()
                    .filter(oc -> leden.stream().anyMatch(l -> l.equals(oc.naam()))).toList();
            if (creaties.isEmpty()) {
                model.diagnostics.add(new Diagnostic(Severity.ERROR,
                        "recursieve regelgroep '" + naam + "' zonder objectcreatieregel — de 'is recursief'-markering "
                        + "kan de keten niet laten voortschrijden (§9.10, gedocumenteerde extensie)", line));
                continue;
            }
            for (ResolvedObjectCreatie oc : creaties)
                if (oc.voorwaarde() == null)
                    model.diagnostics.add(new Diagnostic(Severity.WARNING,
                            "objectcreatieregel '" + oc.naam() + "' in recursieve regelgroep '" + naam
                            + "' heeft geen voorwaarde — terminatie is niet automatisch te garanderen "
                            + "(de engine begrenst het aantal activaties als vangnet; §9.10, gedocumenteerde extensie)", line));
        }
    }

    // ==============================================================================================
    // §4 (LADDER-EVOLUTIE) ladder lowering — the uchar DIALECT EXTENSION 'Uitzondering op' /
    // 'Uitzonderingen ongeordend'. Recognition only ever lowers the extension's OWN blocks; a flat
    // rule family is NEVER folded into a ladder (LADDER-EVOLUTIE §4.2 rejects that — the source never
    // encoded the priority). Both forms LIFT already-resolved ResolvedRegels out of resolvedRegels into
    // DefaultLadder nodes: WF-050 forbids both a rule AND a ladder on one target, so the lifted rule's
    // semantics now live in the ladder alone. See grammar/DIVERGENCES.md for the surface + lowering.
    // ==============================================================================================

    /** The (subject, kenmerk-vs-attribuut, target-name) a lifted rule writes. */
    private record LadderDoel(Objecttype onderwerp, boolean kenmerk, String naam) {
        boolean gelijk(LadderDoel a) {
            return a != null && onderwerp == a.onderwerp && kenmerk == a.kenmerk && naam.equalsIgnoreCase(a.naam);
        }
    }

    /** §4 lower the extension blocks into {@link DefaultLadder} nodes. Fails fast (a clear ERROR, and the block
     *  stays un-lifted) on an unknown base, a target mismatch, an unsupported base kind, or a multi-version rule. */
    private void ladders(RegelSpraakDocumentContext doc) {
        ordeningsLadders(doc);
        groepsLadders(doc);
    }

    /** The ordered form: each base rule with one-or-more 'Uitzondering op &lt;base&gt;' blocks becomes ONE ladder —
     *  the exception rung(s) above the base rung, descending priority. Later-written blocks stack HIGHER
     *  (LADDER-EVOLUTIE §4.2). The base rule and every exception leave resolvedRegels. */
    private void ordeningsLadders(RegelSpraakDocumentContext doc) {
        java.util.LinkedHashMap<String, List<UitzonderingRegelContext>> perBasis = new java.util.LinkedHashMap<>();
        for (UitzonderingRegelContext u : DefinitionResolver.alleUitzonderingen(doc))
            perBasis.computeIfAbsent(DefinitionResolver.src(u.uitzonderingBasisNaam()), k -> new ArrayList<>()).add(u);

        for (var en : perBasis.entrySet()) {
            String basisNaam = en.getKey();
            List<UitzonderingRegelContext> excepties = en.getValue();
            int line = excepties.get(0).getStart().getLine();

            List<ResolvedRegel> basis = resolvedVan(basisNaam);
            if (basis.isEmpty()) {
                model.diagnostics.add(new Diagnostic(Severity.ERROR,
                        "Uitzondering op '" + basisNaam + "': geen basisregel met die naam gevonden — de basis moet "
                        + "een gewone platte regel (kenmerktoekenning of gelijkstelling) zijn" + basisReden(basisNaam), line));
                continue;
            }
            if (basis.size() > 1) { multiVersieFout("basisregel", basisNaam, line); continue; }
            ResolvedRegel basisRegel = basis.get(0);
            LadderDoel doel = doelVan(basisRegel);
            if (doel == null || basisRegel.soort() == ResolvedRegel.Soort.INITIALISATIE) {
                model.diagnostics.add(new Diagnostic(Severity.ERROR,
                        "Uitzondering op '" + basisNaam + "': de basisregel is geen kenmerktoekenning of gelijkstelling "
                        + "(bv. een initialisatie) — daarover wordt geen ladder gebouwd (§4)", line));
                continue;
            }

            List<Rung> excRungs = new ArrayList<>();
            boolean fout = false;
            for (UitzonderingRegelContext u : excepties) {
                String exNaam = DefinitionResolver.src(u.regelNaam());
                List<ResolvedRegel> ex = resolvedVan(exNaam);
                if (ex.isEmpty()) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzondering op '" + basisNaam + "': de uitzondering '" + exNaam + "' leverde geen "
                            + "kenmerktoekenning/gelijkstelling op (§4)", u.getStart().getLine()));
                    fout = true; break;
                }
                if (ex.size() > 1) { multiVersieFout("uitzondering", exNaam, u.getStart().getLine()); fout = true; break; }
                LadderDoel exDoel = doelVan(ex.get(0));
                if (exDoel == null || ex.get(0).soort() == ResolvedRegel.Soort.INITIALISATIE) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzondering op '" + basisNaam + "': de uitzondering '" + exNaam + "' is geen "
                            + "kenmerktoekenning of gelijkstelling (bv. een initialisatie) (§4)", u.getStart().getLine()));
                    fout = true; break;
                }
                // Rung carries no per-rung geldigheid: mixed windows are unrepresentable on the ladder,
                // so a diverging window must refuse — the same fence the multi-version case gets.
                if (!ex.get(0).geldigheid().equals(basisRegel.geldigheid())) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzondering op '" + basisNaam + "': uitzondering '" + exNaam + "' draagt een ander "
                            + "geldigheidsvenster dan de basisregel — de ladder draagt één venster; splits per venster "
                            + "of maak de vensters gelijk (§4)", u.getStart().getLine()));
                    fout = true; break;
                }
                if (!doel.gelijk(exDoel)) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzondering op '" + basisNaam + "': uitzondering '" + exNaam + "' schrijft een ander doel ("
                            + doelTekst(exDoel) + ") dan de basisregel (" + doelTekst(doel) + ") — een uitzondering moet "
                            + "hetzelfde onderwerp en kenmerk/attribuut betreffen (§4, WF-050)", u.getStart().getLine()));
                    fout = true; break;
                }
                excRungs.add(rungVan(ex.get(0), doel.kenmerk()));
            }
            if (fout) continue;
            java.util.Collections.reverse(excRungs);   // later-written Uitzondering-op stacks higher

            List<Rung> rungs = new ArrayList<>(excRungs);
            rungs.add(rungVan(basisRegel, doel.kenmerk()));
            model.defaultLadders.add(ladderVan(basisNaam, doel, false, rungs, basisRegel.geldigheid()));

            model.resolvedRegels.remove(basisRegel);
            for (UitzonderingRegelContext u : excepties) {
                String exNaam = DefinitionResolver.src(u.regelNaam());
                model.resolvedRegels.removeIf(rr -> rr.naam().equals(exNaam));
            }
        }
    }

    /** The unordered group: each 'Uitzonderingen ongeordend:' block becomes SIBLING declared-unordered ladders
     *  (one per member) over ONE shared target — the §9.5 surface. Every sibling carries {@code ongeordend}
     *  (WF-050: two-or-more ladders on one target require it). All members leave resolvedRegels. */
    private void groepsLadders(RegelSpraakDocumentContext doc) {
        for (UitzonderingGroepContext g : DefinitionResolver.alleUitzonderingGroepen(doc)) {
            int line = g.getStart().getLine();
            List<ResolvedRegel> leden = new ArrayList<>();
            LadderDoel gedeeld = null;
            boolean fout = false;
            for (RegelContext lid : g.regel()) {
                String naam = DefinitionResolver.src(lid.regelNaam());
                List<ResolvedRegel> rr = resolvedVan(naam);
                if (rr.isEmpty()) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzonderingen ongeordend: groepslid '" + naam + "' leverde geen "
                            + "kenmerktoekenning/gelijkstelling op (§4)", line));
                    fout = true; break;
                }
                if (rr.size() > 1) { multiVersieFout("groepslid", naam, line); fout = true; break; }
                LadderDoel d = doelVan(rr.get(0));
                if (d == null || rr.get(0).soort() == ResolvedRegel.Soort.INITIALISATIE) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzonderingen ongeordend: groepslid '" + naam + "' is geen kenmerktoekenning of "
                            + "gelijkstelling (§4)", line));
                    fout = true; break;
                }
                if (gedeeld == null) gedeeld = d;
                else if (!gedeeld.gelijk(d)) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "Uitzonderingen ongeordend: groepslid '" + naam + "' schrijft een ander doel ("
                            + doelTekst(d) + ") dan een eerder lid (" + doelTekst(gedeeld) + ") — een ongeordende "
                            + "groep betreft één gedeeld doel (§4, §9.5)", line));
                    fout = true; break;
                }
                leden.add(rr.get(0));
            }
            if (fout || gedeeld == null) continue;
            for (ResolvedRegel r : leden) {
                model.defaultLadders.add(ladderVan(r.naam(), gedeeld, true,
                        List.of(rungVan(r, gedeeld.kenmerk())), r.geldigheid()));
                model.resolvedRegels.remove(r);
            }
        }
    }

    /** Assemble a {@link DefaultLadder} for target {@code doel}: kenmerk XOR attribuut per WF-050, no per-ladder
     *  bron (a frontend ladder carries no statute annotation of its own), the base/member's geldigheid faithfully. */
    private DefaultLadder ladderVan(String naam, LadderDoel doel, boolean ongeordend, List<Rung> rungs, Geldigheid g) {
        return new DefaultLadder(naam, doel.onderwerp().naam,
                doel.kenmerk() ? doel.naam() : null, doel.kenmerk() ? null : doel.naam(),
                ongeordend, rungs, null, g);
    }

    /** One rung from a resolved flat rule: on a CHARACTERISTIC ladder a §9.2-monotone kenmerktoekenning always
     *  decides {@code true} (RegelSpraak cannot withdraw a characteristic); on an ATTRIBUTE ladder the rung's
     *  value is the rule's resolved value expression. The justification is the rule's condition. No rung bron. */
    private Rung rungVan(ResolvedRegel r, boolean kenmerk) {
        return kenmerk
                ? new Rung(Boolean.TRUE, r.voorwaarde(), null, false, null, null)
                : new Rung(null, r.voorwaarde(), r.waarde(), false, null, null);
    }

    /** The resolved versions of the rule named {@code naam} — matched exactly or by the "&lt;naam&gt; #k" version
     *  suffix. Empty when no such rule resolved (it was a non-scalar construct, or the name is unknown). */
    private List<ResolvedRegel> resolvedVan(String naam) {
        List<ResolvedRegel> uit = new ArrayList<>();
        for (ResolvedRegel r : model.resolvedRegels)
            if (r.naam().equals(naam) || r.naam().startsWith(naam + " #")) uit.add(r);
        return uit;
    }

    /** The (subject, kenmerk-vs-attribuut, target-name) a lifted rule writes, or null when its target is not a
     *  plain attribute/characteristic reference (an objectcreatie/verdeling/consistentie never lands here). */
    private LadderDoel doelVan(ResolvedRegel r) {
        if (!(r.doel() instanceof Expr.AttribuutRef d) || d.objecttype() == null) return null;
        return new LadderDoel(d.objecttype(), r.soort() == ResolvedRegel.Soort.KENMERKTOEKENNING, d.attribuut());
    }

    private static String doelTekst(LadderDoel d) {
        return d == null ? "onbekend"
                : d.onderwerp().naam + "." + d.naam() + (d.kenmerk() ? " (kenmerk)" : " (attribuut)");
    }

    /** A hint appended to the unknown-base ERROR when the name IS resolved, but as a construct a ladder cannot
     *  be built over (a verdeling/consistentie/objectcreatie lives in its own model list, not resolvedRegels). */
    private String basisReden(String naam) {
        boolean anders = model.verdelingen.stream().anyMatch(v -> v.naam().equals(naam))
                || model.consistenties.stream().anyMatch(c -> c.naam().equals(naam))
                || model.objectCreaties.stream().anyMatch(o -> o.naam().equals(naam))
                || model.uniciteiten.stream().anyMatch(u -> u.naam().equals(naam));
        return anders ? " (regel '" + naam + "' is een verdeling/consistentie/objectcreatie/uniciteit, geen platte regel)" : "";
    }

    private void multiVersieFout(String rol, String naam, int line) {
        model.diagnostics.add(new Diagnostic(Severity.ERROR,
                "Uitzondering op: de " + rol + " '" + naam + "' heeft meer dan één regelversie — meerdere versies op "
                + "één ladder worden in deze kaart nog niet ondersteund (§4)", line));
    }

    /** A read-write dependency CYCLE between assignments (x = y+1; y = x+1) never reaches a fixpoint
     *  under the freshness semantics (each write re-fires the other reader with a CHANGED value — the
     *  engine caps the run-away instead of hanging, and both back-ends would diverge forever). The spec
     *  does not forbid the writing, so this is a WARNING, not an ERROR: a voorwaarde may break the cycle
     *  at runtime (statically undecidable). Edges: doel-attribuut -> every OWN attribute its value reads,
     *  same objecttype; a SELF-read (x = x+1) is excluded — no-loop makes the single writer terminate. */
    private void keurKringverwijzingen() {
        Map<String, Set<String>> leest = new LinkedHashMap<>();
        for (ResolvedRegel r : model.resolvedRegels) {
            if (!(r.doel() instanceof Expr.AttribuutRef d) || d.navigatieRol() != null || d.objecttype() == null) continue;
            String doel = d.objecttype().naam + "." + d.attribuut();
            Set<String> reads = leest.computeIfAbsent(doel, k -> new java.util.LinkedHashSet<>());
            verzamelEigenReads(r.waarde(), d.objecttype(), doel, reads);
        }
        Set<String> gemeld = new java.util.HashSet<>();
        for (String start : leest.keySet()) {
            List<String> pad = zoekKring(start, start, leest, new java.util.HashSet<>(), new ArrayList<>());
            if (pad != null && gemeld.addAll(new java.util.TreeSet<>(pad)))
                model.diagnostics.add(new Diagnostic(Severity.WARNING,
                        "kringverwijzing tussen berekeningen: " + String.join(" -> ", pad) + " -> " + start
                        + " — herberekening bereikt mogelijk geen fixpunt (de engine begrenst het aantal activaties)", 0));
        }
    }

    private static List<String> zoekKring(String start, String hier, Map<String, Set<String>> leest,
                                          Set<String> bezocht, List<String> pad) {
        if (!bezocht.add(hier)) return null;
        pad.add(hier);
        for (String naar : leest.getOrDefault(hier, Set.of())) {
            if (naar.equals(start) && pad.size() > 1) return new ArrayList<>(pad);
            List<String> diep = zoekKring(start, naar, leest, bezocht, pad);
            if (diep != null) return diep;
        }
        pad.remove(pad.size() - 1);
        return null;
    }

    /** The OWN attributes (same objecttype) an expression reads, as "Objecttype.attribuut" nodes —
     *  {@code zelf} (the doel itself) excluded: a self-read terminates via no-loop. */
    private static void verzamelEigenReads(Expr e, Objecttype subject, String zelf, Set<String> uit) {
        if (e instanceof Expr.AttribuutRef a) {
            if (a.navigatieRol() == null && a.objecttype() == subject) {
                String node = subject.naam + "." + a.attribuut();
                if (!node.equals(zelf)) uit.add(node);
            }
        } else if (e instanceof Expr.Binair b) {
            verzamelEigenReads(b.links(), subject, zelf, uit);
            verzamelEigenReads(b.rechts(), subject, zelf, uit);
        } else if (e instanceof Expr.Functie f) {
            for (Expr a : f.argumenten()) verzamelEigenReads(a, subject, zelf, uit);
        } else if (e instanceof Expr.Afgerond af) {
            verzamelEigenReads(af.basis(), subject, zelf, uit);
        } else if (e instanceof Expr.Begrensd bg) {
            verzamelEigenReads(bg.basis(), subject, zelf, uit);
            verzamelEigenReads(bg.minimum(), subject, zelf, uit);
            verzamelEigenReads(bg.maximum(), subject, zelf, uit);
        } else if (e instanceof Expr.TekstReeks tr) {
            for (Expr.TekstReeks.Deel d : tr.delen())
                if (d instanceof Expr.TekstReeks.Ingevoegd i) verzamelEigenReads(i.expr(), subject, zelf, uit);
        } else if (e instanceof Expr.DimensieCel dc) {
            verzamelEigenReads(dc.attribuut(), subject, zelf, uit);
        }
    }

    /** Process one §13.4.2 regel's versions into the model. Reused for each §12 beslistabel row, which
     *  {@link BeslistabelDesugar} lowers to an ordinary regel and {@link #beslistabellen} re-parses to a
     *  {@link RegelContext} — so a table row reuses the WHOLE rule pipeline (and both back-ends) unchanged. */
    private void regelVersies(RegelContext r) {
        regelVersies(DefinitionResolver.src(r.regelNaam()), r.regelVersie(), bronAnnotaties(r), r.getStart().getLine());
    }

    /** §4 (LADDER-EVOLUTIE) resolve an ordered 'Uitzondering op' block's body exactly as a §13.4.2 regel — its
     *  own regelNaam + regelVersie+ run through the whole rule pipeline into resolvedRegels, so its condition,
     *  value, geldigheid and type-checks are the standard ones. The ladder pass then LIFTS it (and the base rule)
     *  into a {@link io.github.yhavinga.regelspraak.ir.DefaultLadder}, removing it from resolvedRegels. */
    private void uitzonderingVersies(UitzonderingRegelContext u) {
        regelVersies(DefinitionResolver.src(u.regelNaam()), u.regelVersie(), bronAnnotaties(u), u.getStart().getLine());
    }

    /** The shared §13.4.2 version pipeline, over a rule's NAME + versions (a top-level regel, a beslistabel row,
     *  or a §4 exception block). Each §13.4.2.3 version becomes a distinct {@link ResolvedRegel}. */
    private void regelVersies(String naam, List<RegelVersieContext> versies, List<String> bronAnn, int line) {
        // §Bron: the statuutannotaties directly above the rule header, carried onto every version's ResolvedRegel.
        huidigeBronAnnotaties = bronAnn;
        // §13.4.2.5 validity per version, computed up front so §4.2's non-overlap
        // invariant can be checked across the rule's versions. A version whose validity
        // bound is malformed is null here (already diagnosed) and is not emitted.
        List<Geldigheid> gelden = new ArrayList<>();
        for (RegelVersieContext v : versies) gelden.add(geldigheidVan(v.versie()));
        if (versies.size() > 1) meldOverlap(naam, gelden, line);
        for (int i = 0; i < versies.size(); i++) {
            Geldigheid geldigheid = gelden.get(i);
            if (geldigheid == null) continue; // malformed validity bound — already reported
            RegelVersieContext v = versies.get(i);
            RegelSpraakRegelContext rsr = v.regelSpraakRegel();
            // Each §13.4.2.3 version is a distinct rule selected by Rekendatum (§4.2):
            // when a rule has several, give each a unique name.
            String versieNaam = versies.size() > 1 ? naam + " #" + (i + 1) : naam;
            bouwer.setSubject(subjectVan(rsr.resultaatDeel())); // so "zijn …" resolves against the rule subject
            bouwer.zetVariabelen(rsr.variabeleDeel());          // §11 inline "Daarbij geldt:" vars into voorwaarde + resultaat (clears per rule)
            Conditie voorwaarde = rsr.voorwaardeDeel() != null ? bouwVoorwaarde(rsr.voorwaardeDeel()) : null;
            resultaat(naam, versieNaam, rsr.resultaatDeel(), rsr.voorwaardeDeel(), voorwaarde, geldigheid);
            voorwaarden(rsr);   // exhaustive condition type-check (§5), unchanged
        }
    }

    /** §12 Beslistabel (Ch.12 decision tables): desugar the captured inline pipe-table body — one ordinary regel
     *  per (data-row × conclusie-column) via {@link BeslistabelDesugar} — re-parse each synthetic regel and process
     *  it through {@link #regelVersies}, so a beslistabel reuses the WHOLE rule pipeline and BOTH back-ends get its
     *  rows for free, with zero new IR. A header-only shell (the pre-body corpus, or a table with no data rows) keeps
     *  the honest WARNING — restoring the never-silently-dropped invariant. */
    private void beslistabellen(RegelSpraakDocumentContext doc) {
        for (BeslistabelContext b : DefinitionResolver.alleBeslistabellen(doc)) {
            String naam = DefinitionResolver.src(b.beslistabelNaam());
            // Split each captured row on '|', dropping the markdown '---' separator row(s).
            List<List<String>> grid = new ArrayList<>();
            for (TerminalNode rij : b.TABEL_RIJ()) {
                List<String> cellen = splitRij(rij.getText());
                if (!isScheidingsRij(cellen)) grid.add(cellen);
            }
            if (grid.size() < 2) { // need a header row + ≥1 data row to produce anything
                model.diagnostics.add(new Diagnostic(Severity.WARNING,
                        "beslistabel '" + naam + "' heeft geen tabelinhoud (alleen een kop) — wordt niet naar IR/DRL omgezet",
                        b.getStart().getLine()));
                continue;
            }
            String geldig = b.versie() != null ? DefinitionResolver.src(b.versie().versieGeldigheid()) : "altijd";
            List<String> headers = grid.get(0);
            List<List<String>> data = grid.subList(1, grid.size());
            int line = b.getStart().getLine();
            // a row the desugar cannot faithfully render (kenmerk "onwaar" conclusie, >1 OR-cel in an EN-rij) is
            // surfaced as a WARNING — never silently dropped.
            java.util.function.Consumer<String> waarschuw = msg -> model.diagnostics.add(
                    new Diagnostic(Severity.WARNING, "beslistabel '" + naam + "': " + msg, line));
            for (String regelSrc : BeslistabelDesugar.desugar(naam, geldig, headers, data, waarschuw)) {
                RegelContext rc = parseRegel(regelSrc, line);
                if (rc != null) regelVersies(rc);
            }
        }
    }

    /** Split a captured markdown table row into trimmed cells: strip the surrounding pipes/whitespace (a continuation
     *  row carries a leading newline), then split on each TOP-LEVEL '|'. The split is QUOTE-AWARE — a '|' inside a
     *  double-quoted tekstwaarde ("…", §5.4) or a single-quoted enumeratiewaarde ('…', §13.2.14), both preserved
     *  verbatim by the lexer inside TABEL_MODE, is NOT a cell boundary. The empty col-0 header and the numbering cell
     *  are interior, so only the outer pipes are removed. */
    private static List<String> splitRij(String rij) {
        String s = rij.strip();
        if (s.startsWith("|")) s = s.substring(1);
        // Only a BALANCED quote char (even count) opens spans — so a stray apostrophe (a §13.2 ' that is NOT an
        // enumeratiewaarde delimiter, e.g. in "1's") stays a literal char and never swallows the following pipes.
        boolean dq = even(s, '"'), sq = even(s, '\'');
        if (s.endsWith("|") && !eindigtInQuote(s, dq, sq)) s = s.substring(0, s.length() - 1);
        List<String> cellen = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;   // 0 = outside any quote; '"' or '\'' = the char that opened the current span
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote == 0 && ((c == '"' && dq) || (c == '\'' && sq))) quote = c;
            else if (quote == c) quote = 0;
            if (c == '|' && quote == 0) { cellen.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        cellen.add(cur.toString().trim());
        return cellen;
    }
    /** True when {@code q} occurs an even number of times in {@code s} (so its spans are balanced and may be tracked
     *  as quotes; an odd count means an unbalanced ' / " that must be treated as an ordinary character). */
    static boolean even(String s, char q) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == q) n++;
        return (n & 1) == 0;
    }
    /** True when the final '|' of {@code s} sits INSIDE a (balanced) quoted span, so it is part of a value rather than
     *  the row's trailing edge pipe. */
    private static boolean eindigtInQuote(String s, boolean dq, boolean sq) {
        char quote = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (quote == 0 && ((c == '"' && dq) || (c == '\'' && sq))) quote = c;
            else if (quote == c) quote = 0;
        }
        return quote != 0;
    }

    /** A markdown separator row ("| --- | :--: | --- |"): every non-empty cell is dashes/colons and at least one is a
     *  dash run. Such rows are layout, not data, and are dropped before desugaring. */
    private static boolean isScheidingsRij(List<String> cellen) {
        boolean streep = false;
        for (String c : cellen) {
            if (c.isEmpty()) continue;
            if (!c.matches("[:\\-\\s]+")) return false;
            if (c.indexOf('-') >= 0) streep = true;
        }
        return streep;
    }

    /** Parse a synthetic regel source (from {@link BeslistabelDesugar}) into a {@link RegelContext}. A syntax error
     *  lands in the model's diagnostics tagged to the beslistabel's line and yields null (the row is skipped, never
     *  silently mis-rendered). */
    private RegelContext parseRegel(String src, int line) {
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(src));
        RegelSpraak parser = new RegelSpraak(new CommonTokenStream(lexer));
        boolean[] fout = {false};
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int l, int col,
                                              String msg, RecognitionException e) {
                fout[0] = true;
                model.diagnostics.add(new Diagnostic(Severity.ERROR,
                        "beslistabel-desugar: syntaxfout in gegenereerde regel: " + msg, line));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        parser.removeErrorListeners(); parser.addErrorListener(listener);
        RegelContext rc = parser.regel();
        return fout[0] ? null : rc;
    }

    /** §4.2: the validity periods of a rule's versions must not overlap —
     *  otherwise more than one version applies at a Rekendatum (and, writing the same
     *  attribute, they ping-pong in the engine). Flag any intersecting pair. */
    private void meldOverlap(String naam, List<Geldigheid> gelden, int line) {
        for (int i = 0; i < gelden.size(); i++)
            for (int j = i + 1; j < gelden.size(); j++) {
                Geldigheid a = gelden.get(i), b = gelden.get(j);
                if (a != null && b != null && overlapt(a, b)) {
                    model.diagnostics.add(new Diagnostic(Severity.ERROR,
                            "geldigheidsperioden van regelversies overlappen (regel '" + naam + "')", line));
                    return;
                }
            }
    }
    /** Two inclusive windows intersect unless one's upper bound is strictly before the
     *  other's lower bound; a null bound is an open end (±∞), so ALTIJD overlaps everything. */
    private static boolean overlapt(Geldigheid a, Geldigheid b) {
        return !voorbij(a.tot(), b.vanaf()) && !voorbij(b.tot(), a.vanaf());
    }
    private static boolean voorbij(LocalDate tot, LocalDate vanaf) {
        return tot != null && vanaf != null && tot.isBefore(vanaf);
    }

    /** §13.4.2.5 version validity, or null when a present bound is not a valid date/year
     *  (already diagnosed): such a version is dropped, not silently made always-active.
     *  "geldig altijd" is open both ends; a year widens to the whole year (vanaf -> 1 Jan,
     *  t/m -> 31 Dec), t/m being inclusive. */
    private Geldigheid geldigheidVan(VersieContext v) {
        if (v == null || v.versieGeldigheid() == null) return Geldigheid.ALTIJD;
        VersieGeldigheidContext g = v.versieGeldigheid();
        if (g.ALTIJD() != null) return Geldigheid.ALTIJD;
        int line = v.getStart().getLine();
        java.util.List<DatumOfJaarContext> data = g.datumOfJaar();
        LocalDate vanaf = null, tot = null;
        if (g.VANAF() != null) {
            vanaf = grensOfMeld(data.get(0), true, line);
            if (vanaf == null) return null;
            if (g.TM() != null && data.size() > 1) {
                tot = grensOfMeld(data.get(1), false, line);
                if (tot == null) return null;
            }
        } else if (g.TM() != null && !data.isEmpty()) {
            tot = grensOfMeld(data.get(0), false, line);
            if (tot == null) return null;
        }
        return new Geldigheid(vanaf, tot);
    }

    /** Resolve a validity bound, or report an ERROR and return null when it is neither a
     *  valid calendar date (dd-mm-jjjj) nor a four-digit year (§13.2.24). */
    private LocalDate grensOfMeld(DatumOfJaarContext d, boolean onder, int line) {
        LocalDate b = grens(d, onder);
        if (b == null)
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "ongeldige versiegeldigheidsgrens '" + (d != null ? src(d) : "")
                            + "' (verwacht een datum dd-mm-jjjj of een jaartal van vier cijfers)", line));
        return b;
    }

    /** A datumOfJaar to a concrete date, or null when it is not a real calendar date or a
     *  four-digit year. Never throws — an out-of-range day/month/year yields null. */
    private static LocalDate grens(DatumOfJaarContext d, boolean onder) {
        if (d == null) return null;
        if (d.DATE() != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})-(\\d{4})").matcher(d.DATE().getText());
            if (!m.find()) return null;
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (java.time.DateTimeException e) { return null; }
        }
        // §13.2.24: a bare year is exactly four digits — reject a decimal/fraction/signed/
        // out-of-range NUMBER the grammar's generic token would otherwise admit.
        if (d.NUMBER() != null && d.NUMBER().getText().matches("\\d{4}")) {
            try {
                int jaar = Integer.parseInt(d.NUMBER().getText());
                return onder ? LocalDate.of(jaar, 1, 1) : LocalDate.of(jaar, 12, 31);
            } catch (NumberFormatException | java.time.DateTimeException e) { return null; }
        }
        return null;
    }

    /** A domain-typed attribute/parameter must reference a declared domain (§13.3.4). */
    private void domeinReferenties(RegelSpraakDocumentContext doc) {
        model.objecttypen.values().forEach(ot -> ot.attributen.forEach((naam, dt) -> meldOnbekendDomein(dt, naam)));
        model.parameters.values().forEach(p -> meldOnbekendDomein(p.datatype(), p.naam()));
    }
    private void meldOnbekendDomein(Datatype dt, String waar) {
        if (dt instanceof Datatype.Onbekend o && o.reden().startsWith("domein ")) {
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "ongedefinieerd " + o.reden() + " (gebruikt door '" + waar + "')", 0));
        }
    }

    /** Resolve an assignment resultaatdeel into a {@link ResolvedRegel} (the transpiler IR)
     *  and type-check the value against the target (§6 toekenning). The assignment kinds
     *  carry the version's {@code geldigheid} and its per-version {@code versieNaam}; the
     *  other resultaatdeel kinds keep the base {@code naam} and (for now) no validity guard. */
    private void resultaat(String naam, String versieNaam, ResultaatDeelContext rd,
                           VoorwaardeDeelContext vd, Conditie voorwaarde, Geldigheid geldigheid) {
        if (rd == null) return;
        if (rd.gelijkstelling() != null) {
            GelijkstellingContext g = rd.gelijkstelling();
            ResolvedRegel.Soort soort = g.WORDT_BEREKEND_ALS() != null
                    ? ResolvedRegel.Soort.BEREKENING : ResolvedRegel.Soort.GELIJKSTELLING;
            registreer(versieNaam, soort, g.attribuutVanOnderwerp(), g.expressie(), g.getStart().getLine(), voorwaarde, geldigheid);
        } else if (rd.initialisatie() != null) {
            InitialisatieContext i = rd.initialisatie();
            registreer(versieNaam, ResolvedRegel.Soort.INITIALISATIE, i.attribuutVanOnderwerp(),
                       i.expressie(), i.getStart().getLine(), voorwaarde, geldigheid);
        } else if (rd.kenmerkToekenning() != null) {
            kenmerk(versieNaam, rd.kenmerkToekenning(), voorwaarde, geldigheid);
        } else if (rd.objectCreatie() != null) {
            objectcreatie(versieNaam, rd.objectCreatie(), voorwaarde, geldigheid);
        } else if (rd.verdeling() != null) {
            verdeling(versieNaam, rd.verdeling(), voorwaarde, geldigheid);
        } else if (rd.consistentieRegel() != null) {
            consistentie(versieNaam, rd.consistentieRegel(), geldigheid);
        } else if (rd.feitCreatie() != null) {
            feitCreatie(versieNaam, rd.feitCreatie(), vd, voorwaarde, geldigheid);
        } else if (rd.dagsoortResultaat() != null) {
            // §9.8 dagsoortdefinitie ("Een dag is een <dagsoort> indien <voorwaarde over de dag>"): lift the
            // (dagsoort name, condition-over-the-day) into the IR. The voorwaardendeel was already resolved into
            // `voorwaarde` — its subject is "de dag" and its leaves are date-component tests, so both transpilers
            // can compile it to a per-date boolean predicate (§8.1.5 controls + §6 dagsoort durations consume it).
            String dag = src(rd.dagsoortResultaat().kaleNaam()).trim();
            if (voorwaarde == null)
                model.diagnostics.add(new Diagnostic(Severity.WARNING,
                        "dagsoortdefinitie '" + dag + "' mist een voorwaarde (regel '" + naam + "')", rd.getStart().getLine()));
            else {
                // The predicate compilers key on the dagsoort NAME and use the FIRST definitie; a second definitie of
                // the same dagsoort (e.g. per §13.4.2.5 version window) is not version-dispatched -> warn, don't drop silently.
                if (model.resolvedDagsoorten.stream().anyMatch(rd2 -> rd2.naam().equalsIgnoreCase(dag)))
                    model.diagnostics.add(new Diagnostic(Severity.WARNING,
                            "dagsoort '" + dag + "' heeft meerdere definities; alleen de eerste wordt gebruikt (versievensters worden nog niet toegepast)", rd.getStart().getLine()));
                model.resolvedDagsoorten.add(new io.github.yhavinga.regelspraak.ir.ResolvedDagsoort(dag, voorwaarde, geldigheid));
            }
        } else if (rd.startpuntBepaling() != null) {
            startpunt(versieNaam, rd.startpuntBepaling(), voorwaarde, geldigheid);
        } else {
            // parsed but not yet lifted into the IR. Surface it as a diagnostic
            // rather than letting the rule silently disappear from the model.
            model.diagnostics.add(new Diagnostic(Severity.WARNING,
                    "resultaatdeel 'resultaatdeel' wordt nog niet naar IR/DRL omgezet (regel '" + naam + "')",
                    rd.getStart().getLine()));
        }
    }

    /** §13.4.8 consistentieregel (single-criterion form): "&lt;expressie&gt; moet
     *  &lt;operator&gt;". The assertion is built with the same operator machinery as a
     *  voorwaarde (uitToplevel), so the operand types and operator legality are the
     *  §5 ones; the subject is the object type the asserted expression is about. Compound
     *  criteria and uniciteit (different shapes) are not yet lifted — they keep a WARNING. */
    private void consistentie(String naam, ConsistentieRegelContext cr, Geldigheid geldigheid) {
        EnkelvoudigeConsistentieRegelContext e = cr.enkelvoudigeConsistentieRegel();
        if (e != null && e.expressie() != null && e.toplevelOperator() != null) {
            Expr lhs = bouwer.bouw(e.expressie());
            Conditie assertie = uitToplevel(lhs, e.toplevelOperator(), bron(e));
            Objecttype subject = lhs instanceof Expr.AttribuutRef a ? a.objecttype() : null;
            if (subject != null && transpileerbaar(assertie)) {
                model.consistenties.add(new io.github.yhavinga.regelspraak.ir.ResolvedConsistentie(naam, subject, assertie, bron(cr), cr.getStart().getLine(), geldigheid));
                return;
            }
        }
        if (cr.uniciteitsControle() != null && uniciteit(naam, cr.uniciteitsControle(), geldigheid)) return;
        // §13.4.8 samengestelde consistentieregel — quantified bullet criteria, in either the
        // subject-led ("<onderwerp> moet voldoen aan alle volgende criteria: …") or the impersonal
        // ("er moet worden voldaan aan …") form. Build a Conditie.Samengesteld over the criteria; the
        // subject is the type the criteria are about (the first criterion's left operand).
        ConsistentieKwantificatieContext kw = null;
        SamengesteldCriteriumOnderdeelContext crit = null;
        if (cr.samengesteldeConsistentie() != null) {
            kw = cr.samengesteldeConsistentie().consistentieKwantificatie();
            crit = cr.samengesteldeConsistentie().samengesteldCriteriumOnderdeel();
        } else if (cr.toplevelSamengesteldCriterium() != null) {
            kw = cr.toplevelSamengesteldCriterium().consistentieKwantificatie();   // null for the single "volgend criterium"
            crit = cr.toplevelSamengesteldCriterium().samengesteldCriteriumOnderdeel();
        }
        if (crit != null) {
            Conditie assertie = bouwCriteria(kw, crit, bron(cr));
            Objecttype subject = subjectVanAssertie(assertie);
            // All criteria must be about the SAME subject: the rule lowers to one subject pattern, so a
            // criterion over a different object type would emit that type's attribute on the wrong fact
            // (an uncompilable property that sinks the whole DRL package). Mixed subjects -> WARNING.
            if (subject != null && transpileerbaar(assertie) && eenSubject(assertie, subject)) {
                model.consistenties.add(new io.github.yhavinga.regelspraak.ir.ResolvedConsistentie(naam, subject, assertie, bron(cr), cr.getStart().getLine(), geldigheid));
                return;
            }
        }
        // A membership criterion ("moet een <kenmerk|rol> zijn"), elfproef /
        // numeriek-met-exact, a cross-type "verenigd met" uniqueness, or a computed/unresolved
        // subject: forms whose "inconsistent" we cannot yet express as a faithful violation rule.
        // Surface the gap as a WARNING rather than lift it into a silent inert rule (which would
        // both drop the check and lose the diagnostic the §13.4.7/.11/.12 path still gives).
        model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "consistentieregel wordt nog niet (volledig) naar IR/DRL omgezet (regel '" + naam + "')",
                cr.getStart().getLine()));
    }

    /** §8.1.6 uniciteitscontrole — either "de &lt;attribuut&gt; van alle &lt;Type&gt; moeten uniek zijn"
     *  or a §5.7 concatenatie "de concatenatie van &lt;attribuut&gt; en … van alle &lt;Type&gt; …". Resolves
     *  the object type (by name, declared meervoudsvorm, or regular plural) and the key attribute(s)
     *  over which the value — or the COMBINATION of values — must be unique, into a
     *  {@link io.github.yhavinga.regelspraak.ir.ResolvedUniciteit}. Returns false — leaving the WARNING — for the
     *  cross-type "verenigd met" union, a uniqueness scoped within a parent ("… van alle X van &lt;Y&gt;"),
     *  or any name that does not resolve; the transpiler then never silently drops or mis-checks. */
    /** The count of temporal (attribuutGranulariteit) components in a uniqueness key list. */
    private static long telTemporeel(Objecttype type, java.util.List<String> sleutels) {
        return sleutels.stream().filter(k -> type.attribuutGranulariteit.containsKey(k)).count();
    }

    private boolean uniciteit(String naam, UniciteitsControleContext u, Geldigheid geldigheid) {
        // §8.1.6 parent-scoped "de X van alle Y van <parent>": unique within each parent, not globally.
        if (u.vereniging().isEmpty() && u.alleAttribuutVanOnderwerp() != null) {
            var scoped = parentScoped(naam, u.alleAttribuutVanOnderwerp(), bron(u), geldigheid);
            if (scoped != null) {
                if (telTemporeel(scoped.subject(), scoped.sleutels()) > 1) return false;
                model.uniciteiten.add(scoped); return true;
            }
        }
        ResolvedUniciteitUnie.Bron eerste = bronVan(u.alleAttribuutVanOnderwerp(), u.uniciteitConcatenatie());
        if (eerste == null) return false;
        if (u.vereniging().isEmpty()) {                              // the common single-source form
            if (telTemporeel(eerste.type(), eerste.sleutels()) > 1) return false;
            model.uniciteiten.add(new io.github.yhavinga.regelspraak.ir.ResolvedUniciteit(
                    naam, eerste.type(), eerste.sleutels(), null, bron(u), geldigheid));
            return true;
        }
        // §8.1.6 "verenigd met": uniqueness across the UNION of two or more sources' key spaces. Every
        // source must resolve and carry the SAME key arity (the values union positionally — "attributen
        // met hetzelfde datatype"); any unresolved/mismatched source leaves the whole rule a WARNING.
        java.util.List<ResolvedUniciteitUnie.Bron> bronnen = new ArrayList<>();
        bronnen.add(eerste);
        for (VerenigingContext v : u.vereniging()) {
            ResolvedUniciteitUnie.Bron b = bronVan(v.alleAttribuutVanOnderwerp(), v.uniciteitConcatenatie());
            // §8.1.6: "een vereniging is de combinatie van twee attributen met HETZELFDE datatype". The
            // values join one space, so each source's key parts must match the first's in arity AND
            // datatype-kind; otherwise the union is ill-formed (Tekst vs Numeriek would cross-match
            // under mvel's coercive ==) — leave it a WARNING, like an arity mismatch.
            if (b == null || b.sleutels().size() != eerste.sleutels().size() || !zelfdeDatatypes(eerste, b))
                return false;
            bronnen.add(b);
        }
        // §8.1.6 ever-uniqueness (uchar RS ch. 5.9): at most ONE temporal component per key list —
        // in the single form and per union source alike; a second would need the same-moment tuple
        // join, which no reading pins yet. More than one leaves the whole rule a WARNING, like an
        // arity mismatch, so BOTH back-ends stay identically silent on the undefined shape.
        for (ResolvedUniciteitUnie.Bron b : bronnen) {
            if (telTemporeel(b.type(), b.sleutels()) > 1) return false;
        }
        model.uniciteitUnies.add(new io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie(naam, bronnen, bron(u), geldigheid));
        return true;
    }

    /** One uniqueness source — "de &lt;attribuut&gt; van alle &lt;Type&gt;" or a §5.7 concatenatie — resolved
     *  to its object type and key attribute(s), or null when it does not resolve (an unknown name, a
     *  parent-scoped navigation, a nested-"van" attribute). Exactly one of the two contexts is non-null. */
    private ResolvedUniciteitUnie.Bron bronVan(AlleAttribuutVanOnderwerpContext a, UniciteitConcatenatieContext c) {
        if (a != null) {
            if (!a.onderwerpKeten().isEmpty()) return null;          // scoped within a parent: not lifted
            Objecttype type = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), src(a.kaleNaam(1)));
            if (type == null) return null;
            String attr = MeervoudMatcher.vindAttribuut(type, src(a.kaleNaam(0)));  // "DE attr VAN ALLE": no absorbed article
            return attr == null ? null : new ResolvedUniciteitUnie.Bron(type, java.util.List.of(attr));
        }
        if (c != null) {
            if (!c.onderwerpKeten().isEmpty()) return null;
            // CONCATENATIE_VAN kaleNaam (COMMA kaleNaam)* EN kaleNaam VAN ALLE <type>: the last kaleNaam
            // is the type, the rest are the key attributes (each absorbs its leading article — strip it);
            // a nested-"van" part does not resolve, leaving a WARNING rather than a partial key.
            java.util.List<KaleNaamContext> namen = c.kaleNaam();
            Objecttype type = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), src(namen.get(namen.size() - 1)));
            if (type == null) return null;
            java.util.List<String> sleutels = new ArrayList<>();
            for (int i = 0; i < namen.size() - 1; i++) {
                String attr = MeervoudMatcher.vindAttribuut(type, stripArtikel(src(namen.get(i))));
                if (attr == null) return null;
                sleutels.add(attr);
            }
            return new ResolvedUniciteitUnie.Bron(type, sleutels);
        }
        return null;
    }

    /** §8.1.6 parent-scoped uniqueness "de X van alle Y van &lt;parent&gt;" — unique within each parent.
     *  Resolves the element Y, its key attribute X, and the reference field on a Y that points at its
     *  scope owner &lt;parent&gt; (so the count is restricted per-parent). Null when it is not the scoped
     *  shape — then the global single-source path applies — or when the scope does not resolve. */
    private io.github.yhavinga.regelspraak.ir.ResolvedUniciteit parentScoped(String naam, AlleAttribuutVanOnderwerpContext a,
                                                                 Bron bron, Geldigheid g) {
        if (!a.onderwerpKeten().isEmpty()) return null;
        java.util.List<KaleNaamDeelContext> delen = a.kaleNaam(1).kaleNaamDeel();
        if (delen.size() != 2) return null;                          // element + exactly one parent navigation
        // The element is an object type ("alle Ploegen") or, more often when scoped, a role name
        // ("alle deelnemers van de groep") — the role's plural matches via the feittype, not the
        // conservative object-type plural.
        Objecttype elem = MeervoudMatcher.vindObjecttype(model.objecttypen.values(), src(delen.get(0)));
        if (elem == null) elem = model.rolDoeltypeNaarSubject(null, src(delen.get(0)));
        if (elem == null) return null;
        String attr = MeervoudMatcher.vindAttribuut(elem, src(a.kaleNaam(0)));
        if (attr == null) return null;
        String parentNav = stripArtikel(src(delen.get(1)));
        Objecttype parentType = model.rolDoeltypeNaarSubject(elem, parentNav);  // the role's object type
        if (parentType == null) return null;
        String joinVeld = model.verbindendeRol(elem, parentType);    // field on a Y pointing at its <parent>
        if (joinVeld == null) return null;
        // uchar scopeRol: the same reference field made symbolic — the fact type plus the (declared)
        // scope-parent role. Withheld (null -> absent) when the connecting relation is not the
        // two-role shape the binding form covers; the wire then keeps only the bean-field scopeVeld.
        RolBindingRef scopeRol = model.verbindendeRolBinding(elem, parentType);
        return new io.github.yhavinga.regelspraak.ir.ResolvedUniciteit(
                naam, elem, java.util.List.of(attr), joinVeld, bron, g, scopeRol);
    }

    /** §8.1.6: two union sources may be combined only when their key parts share a datatype. Compared
     *  by datatype KIND positionally (Tekst vs Numeriek differ; a finer unit/precision check is carded).
     *  A non-matching pair is ill-formed — the caller leaves it a WARNING. */
    private static boolean zelfdeDatatypes(ResolvedUniciteitUnie.Bron a, ResolvedUniciteitUnie.Bron b) {
        for (int i = 0; i < a.sleutels().size(); i++) {
            Datatype da = a.type().attribuut(a.sleutels().get(i));
            Datatype db = b.type().attribuut(b.sleutels().get(i));
            if (da == null || db == null || da.getClass() != db.getClass()) return false;
            // §8.1.6: the sources combine "attributen met HETZELFDE datatype". For Numeriek the unit IS
            // part of the datatype, and units do not survive into the fact model (values are bare
            // BigDecimals), so a union of a EUR attribute and a jr attribute would cross-match "5 EUR" and
            // "5 jr" as one value — a silently-wrong false duplicate. Require the SAME eenheid (both unitless
            // counts too); a unit mismatch is as ill-formed as a Tekst∪Numeriek one and leaves a WARNING.
            if (da instanceof Datatype.Numeriek na && db instanceof Datatype.Numeriek nb
                    && !java.util.Objects.equals(na.eenheid(), nb.eenheid())) return false;
        }
        return true;
    }

    /** The single-criterion assertions whose negation the transpiler can emit as a
     *  faithful violation rule: a comparison, or an emptiness predicate. Everything else
     *  (membership, elfproef, numeriek-met-exact, unstructured) is left to a WARNING. */
    private static boolean transpileerbaar(Conditie c) {
        if (c instanceof Conditie.Vergelijking) return true;
        if (c instanceof Conditie.Predicaat p)
            return p.soort() == Conditie.Predicaatsoort.LEEG || p.soort() == Conditie.Predicaatsoort.GEVULD;
        // An ALLE compound is the violation = OR of the criteria's violations (transpiler), lowerable
        // when every criterion is. GEEN and the counting kwantoren (ten minste/hoogste/precies) need a
        // satisfied-criterion count the single violation rule cannot express yet — left to a WARNING.
        if (c instanceof Conditie.Samengesteld s)
            return s.kwantor() == Conditie.Kwantor.ALLE && !s.onderdelen().isEmpty()
                    && s.onderdelen().stream().allMatch(RuleChecker::transpileerbaar);
        return false;
    }

    /** §13.4.10 verdeling: distribute a source amount over a recipient attribute. The
     *  method (equal shares / proportional / a group-and-remainder scheme) is captured;
     *  only equal shares has a single-rule Drools form (the transpiler marks the rest). */
    private void verdeling(String naam, VerdelingContext v, Conditie voorwaarde, Geldigheid geldigheid) {
        if (v.attribuutVanOnderwerp().size() < 2) return;
        Expr bron = bouwer.bouwAttribuut(v.attribuutVanOnderwerp(0));
        Expr doel = bouwer.bouwAttribuut(v.attribuutVanOnderwerp(1));
        if (!(bron instanceof Expr.AttribuutRef b) || !(doel instanceof Expr.AttribuutRef d)) return;

        // The bare "in gelijke delen" / "naar rato van" names the method directly. The bulleted
        // meervoudigCriterium (§9.7.7) wraps that same method with optional modifiers; only the subset
        // WITHOUT group ordering has a single forward-rule form, so a present verdelenOverGroepen keeps the
        // whole verdeling COMPLEX (a marker). The §9.7.3 maximum (naar-rato only), the §9.7.4 afronding (floor)
        // and the §9.7.5 onverdeelde rest ride on the equal-shares / naar-rato spine.
        VerdelenZonderGroepenContext z = v.verdelenZonderGroepen();
        Integer afrDec = null;
        String restAttr = null, maxAttr = null, groepeerAttr = null;
        boolean afnemend = false;
        MeervoudigCriteriumContext mc = v.meervoudigCriterium();
        // The §9.7.3/§9.7.4/§9.7.5 modifiers (maximum, afronding, onverdeelde rest) attach to BOTH the
        // bare-method form and the op-volgorde form (the grammar lets them co-occur with verdelenOverGroepen),
        // so capture them whenever a meervoudigCriterium is present; only the bare-method `z` is branch-specific.
        if (z == null && mc != null) {
            if (mc.verdelenOverGroepen() == null) z = mc.verdelenZonderGroepen();
            if (mc.verdeelAfronding() != null)
                try { afrDec = Integer.valueOf(mc.verdeelAfronding().NUMBER().getText()); }
                catch (NumberFormatException ignore) {}
            if (mc.maximumAanspraak() != null && mc.maximumAanspraak().attribuutMetLidwoord() != null
                    && mc.maximumAanspraak().attribuutMetLidwoord().kaleNaam() != null)
                maxAttr = src(mc.maximumAanspraak().attribuutMetLidwoord().kaleNaam());   // a recipient (doel) attribute
            if (mc.onverdeeldeRest() != null && b.objecttype() != null
                    && bouwer.bouwAttribuut(mc.onverdeeldeRest().attribuutVanOnderwerp()) instanceof Expr.AttribuutRef ra
                    && ra.objecttype() == b.objecttype())   // the remainder lives on the verdeler (the source)
                restAttr = ra.attribuut();
            if ((afrDec != null || maxAttr != null) && restAttr == null && b.objecttype() != null)
                // §9.7.3/§9.7.4: a maximum OR an afronding can leave the plafond under-distributed, so the spec
                // REQUIRES an onverdeelde rest. Without a captured one the leftover is silently lost — surface it.
                // Only when the bron resolves (an unresolved-verdeler fragment markers anyway, so warning is noise).
                model.diagnostics.add(new Diagnostic(Severity.WARNING,
                        "verdeling met afronding/maximum zonder herkenbare onverdeelde rest (§9.7.3/§9.7.4): het "
                        + "restant wordt niet vastgelegd (regel '" + naam + "')", v.getStart().getLine()));
        }

        ResolvedVerdeling.Methode methode = ResolvedVerdeling.Methode.COMPLEX;
        String rato = null;
        if (z != null && z.IN_GELIJKE_DELEN() != null) methode = ResolvedVerdeling.Methode.GELIJKE_DELEN;
        else if (z != null && z.NAAR_RATO_VAN() != null) {
            methode = ResolvedVerdeling.Methode.NAAR_RATO;
            // the BARE weight name (kaleNaam, lidwoord stripped) so it maps straight to a recipient getter,
            // exactly like b.attribuut()/d.attribuut() — "de bruto premie" -> "bruto premie".
            rato = z.attribuutMetLidwoord() != null && z.attribuutMetLidwoord().kaleNaam() != null
                    ? src(z.attribuutMetLidwoord().kaleNaam()) : null;
        }
        // §9.7.2 op volgorde van: partition the recipients by an ordering attribute and deplete the pot
        // group-by-group; within a tie-group the share is "in gelijke delen" or "naar rato van <gewicht>"
        // (the same role as `rato`, so reuse it). The whole allocation lowers via rsVerdeelOpVolgorde — it
        // stays COMPLEX (a marker) only when the ordering key or the tie weight is missing or non-getalachtig
        // (the helper compareTo-sorts the key and sums the weight, so both must be numeric to lower).
        VerdelenOverGroepenContext og = mc != null ? mc.verdelenOverGroepen() : null;
        if (og != null) {
            afnemend = og.AFNEMENDE() != null;
            groepeerAttr = og.attribuutMetLidwoord() != null && og.attribuutMetLidwoord().kaleNaam() != null
                    ? src(og.attribuutMetLidwoord().kaleNaam()) : null;
            CriteriumBijGelijkeVolgordeContext crit = og.criteriumBijGelijkeVolgorde();
            if (crit != null && crit.NAAR_RATO_VAN() != null && crit.attribuutMetLidwoord() != null
                    && crit.attribuutMetLidwoord().kaleNaam() != null)
                rato = src(crit.attribuutMetLidwoord().kaleNaam());   // gelijke-delen tie leaves rato null
            Datatype groepType = groepeerAttr != null && d.objecttype() != null ? d.objecttype().attribuut(groepeerAttr) : null;
            Datatype ratoType = rato != null && d.objecttype() != null ? d.objecttype().attribuut(rato) : null;
            boolean tieOk = rato == null || (ratoType != null && ratoType.isGetalachtig());
            // §9.7.2 the ordering key need only be ORDERABLE (a number or a date) — the helper compareTo-sorts it;
            // the tie WEIGHT stays getalachtig (it is summed). A non-orderable key keeps the verdeling COMPLEX.
            if (groepType != null && groepType.isOrdenbaar() && tieOk)
                methode = ResolvedVerdeling.Methode.OP_VOLGORDE;
        }
        // §9.7.3: a maximale aanspraak is valid ONLY for a naar-rato split ("niet mogelijk bij gelijke verdeling"
        // — a gelijke verdeling met maximum is voor meerdere uitleg vatbaar), i.e. top-level naar rato OR an
        // op-volgorde with a naar-rato tie, and only when it names a getalachtig recipient attribute (else the
        // cap getter would not compile). Otherwise the verdeling falls back to COMPLEX (a marker).
        if (maxAttr != null) {
            Datatype maxType = d.objecttype() != null ? d.objecttype().attribuut(maxAttr) : null;
            boolean ratoTie = methode == ResolvedVerdeling.Methode.NAAR_RATO
                    || (methode == ResolvedVerdeling.Methode.OP_VOLGORDE && rato != null);
            if (!ratoTie || maxType == null || !maxType.isGetalachtig()) {
                methode = ResolvedVerdeling.Methode.COMPLEX;
                maxAttr = null;
            }
        }
        // §6.1.2 precision, consistent with the ordinary `regel` assignment: a share whose decimal count
        // exceeds the target attribute's must be explicitly rounded (the §9.7.4 afronding). Run it only when
        // the verdeling actually ASSIGNS a share — a COMPLEX marker assigns nothing, a naar-rato whose weight is
        // not getalachtig is lowered to an honest no-op, and EVERY method markers when the join is unresolvable
        // (the transpiler gates all three on join != null), so flagging the precision of a share that is never
        // computed would be a false positive. (No-op for all-integer verdelingen.)
        boolean joinResolves = b.objecttype() != null && d.objecttype() != null
                && model.verbindendeRol(d.objecttype(), b.objecttype()) != null;
        if (methode != ResolvedVerdeling.Methode.COMPLEX && joinResolves) {
            Datatype gewType = (methode == ResolvedVerdeling.Methode.NAAR_RATO
                    || methode == ResolvedVerdeling.Methode.OP_VOLGORDE) && rato != null
                    ? d.objecttype().attribuut(rato) : null;   // op-volgorde naar-rato tie weighs like NAAR_RATO
            boolean lowert = methode == ResolvedVerdeling.Methode.GELIJKE_DELEN
                    || methode == ResolvedVerdeling.Methode.OP_VOLGORDE     // both lower once the join resolves
                    || (gewType != null && gewType.isGetalachtig());   // mirror the transpiler's lowering gate
            if (lowert) {
                String fout = Typering.verdeelToekenning(d.objecttype().attribuut(d.attribuut()),
                        b.objecttype().attribuut(b.attribuut()), gewType, afrDec);
                if (fout != null)
                    model.diagnostics.add(new Diagnostic(Severity.ERROR, "verdeling: " + fout, v.getStart().getLine()));
            }
        }
        model.verdelingen.add(new ResolvedVerdeling(naam, b.objecttype(), b.attribuut(),
                d.objecttype(), d.attribuut(), methode, rato, maxAttr, afrDec, restAttr,
                groepeerAttr, afnemend, voorwaarde, geldigheid));
    }

    /** §13.4.6 objectcreatie: "<subject> heeft <het rol> met <attribuut> gelijk aan <expr>
     *  (, <attribuut> gelijk aan <expr>)*". The first kaleNaam carries "<rol> met
     *  <attribuut>" (the 'met' fold the grammar leaves to the resolver); the rest are
     *  bare attribute names. */
    private void objectcreatie(String naam, ObjectCreatieContext oc, Conditie voorwaarde, Geldigheid geldigheid) {
        Objecttype subject = bouwer.objecttypeVanKeten(oc.onderwerpKeten());
        bouwer.setSubject(subject);
        List<KaleNaamContext> namen = oc.kaleNaam();
        List<ExpressieContext> waarden = oc.expressie();
        if (namen.isEmpty()) return;

        String[] rolEnAttr = src(namen.get(0)).split("(?i)\\s+met\\s+", 2);
        Objecttype nieuwType = bouwer != null
                ? model.resolveObjecttype(stripArtikel(rolEnAttr[0])) : null;

        List<ResolvedObjectCreatie.Toekenning> toek = new ArrayList<>();
        if (rolEnAttr.length == 2 && !waarden.isEmpty())
            toek.add(maakToekenning(nieuwType, stripArtikel(rolEnAttr[1]), waarden.get(0), oc));
        for (int i = 1; i < namen.size() && i < waarden.size(); i++)
            toek.add(maakToekenning(nieuwType, stripArtikel(src(namen.get(i))), waarden.get(i), oc));

        // typeringen §6.5: each ATTRIBUUT met-clause init follows the SAME §6.2/Tabel 61 toekenning rules
        // as a gelijkstelling (family + eenheid + §6.1.2 precision; an Expr.Afgerond value already reports
        // its rounded decimal count, and toekenning itself no-ops on an Onbekend value). A kenmerk-init was
        // already §13.4.6.4-checked in maakToekenning; an unresolved created type carries no claim: skip.
        if (nieuwType != null)
            for (ResolvedObjectCreatie.Toekenning t : toek) {
                Datatype doelType = nieuwType.attribuut(t.attribuut());
                if (doelType == null) continue;
                if (t.waarde() instanceof Expr.Literal lit)   // §13.3.4.2 enum-label membership, like registreer
                    keurEnumLabel(doelType, Literalen.enumLabel(lit.waarde()), oc.getStart().getLine());
                keurTeken(doelType, t.waarde(), oc.getStart().getLine());   // §3.3.1 Teken-beperking, like registreer
                String fout = Typering.toekenning(doelType, t.waarde().type());
                if (fout != null)
                    model.diagnostics.add(new Diagnostic(Severity.ERROR, "objectcreatie: " + fout,
                            oc.getStart().getLine()));
            }

        model.objectCreaties.add(new ResolvedObjectCreatie(naam, nieuwType, subject, toek, voorwaarde, geldigheid));
    }

    /** §13.4.6.2 one waardetoekenning of an objectcreatie: the §13.4.6.3 ATTRIBUUT form (generic expressie
     *  RHS, §6.5-type-checked by the caller) or the DISTINCT §13.4.6.4 KENMERK form, whose RHS the EBNF
     *  restricts to the waar|onwaar literal — anything else is an ERROR (it would otherwise lower to a
     *  boolean setter fed a non-boolean: a broken rule at build/fire time). A matched kenmerk stores its
     *  DECLARED name (the match is case-insensitive, the fact property is not). A name that is NEITHER a
     *  declared attribute NOR a kenmerk of the created type is an ERROR too — the old silent skip emitted
     *  a setter on a property that does not exist. */
    private ResolvedObjectCreatie.Toekenning maakToekenning(Objecttype nieuwType, String naam,
                                                            ExpressieContext waardeCtx, ObjectCreatieContext oc) {
        Expr waarde = bouwer.bouw(waardeCtx);
        if (nieuwType == null || nieuwType.attribuut(naam) != null)
            return new ResolvedObjectCreatie.Toekenning(naam, waarde);
        String kenmerk = nieuwType.kenmerken.stream().filter(k -> k.equalsIgnoreCase(naam)).findFirst().orElse(null);
        if (kenmerk != null) {
            if (!(waarde instanceof Expr.Literal lit && lit.type() instanceof Datatype.Booleaan))
                model.diagnostics.add(new Diagnostic(Severity.ERROR,
                        "kenmerkwaardetoekenning '" + kenmerk + "' vereist waar of onwaar (§13.4.6.4), niet '"
                                + waarde.bron() + "'", oc.getStart().getLine()));
            return new ResolvedObjectCreatie.Toekenning(kenmerk, waarde, true);
        }
        model.diagnostics.add(new Diagnostic(Severity.ERROR,
                "objectcreatie: '" + naam + "' is geen attribuut of kenmerk van " + nieuwType.naam + " (§13.4.6.2)",
                oc.getStart().getLine()));
        return new ResolvedObjectCreatie.Toekenning(naam, waarde);
    }

    private static String stripArtikel(String s) { return s.replaceFirst("^(?i)(de|het|een)\\s+", "").trim(); }

    /** §13.4.7 feitcreatie: "Een &lt;rolB&gt; van een &lt;anker&gt; is een &lt;rolC&gt; van
     *  &lt;onderwerpD&gt;" creates an instance of the feittype that {@code rolB} names, relating each
     *  {@code anker} to the {@code doel} objects that fill {@code rolC} of it (§9.4). The grammar's
     *  greedy kaleNaam splits the RHS at the LAST "van" (the rule's trailing {@code VAN onderwerp}),
     *  so the role part is the FIRST kaleNaam segment and any further segment is a navigation; this
     *  resolver re-splits per the §13.4-names design rather than changing the grammar.
     *  <p>Lifts BOTH the 1-hop shape — {@code onderwerpD} is the anker itself, reached by the single
     *  role {@code rolC} — AND the §9.4 MULTI-HOP shape (a navigation chain on the RHS), whose
     *  intermediate hops become {@link ResolvedFeitCreatie.Tussenstap}s walked below. The one hard gate
     *  is that {@code onderwerpD} must be a back-reference to the iterated anker itself (a DIFFERENT
     *  object of the same type reached by a role — "de buurgroep" — keeps an honest WARNING rather
     *  than a silent wrong link); an unresolvable role/onderwerp/cardinaliteit likewise WARNs. */
    private void feitCreatie(String naam, FeitCreatieContext fc, VoorwaardeDeelContext vd, Conditie voorwaarde, Geldigheid geldigheid) {
        String rolB = stripArtikel(src(fc.kaleNaam(0)));               // LHS role — the relation being created
        Objecttype anker = bouwer.resolveOnderwerp(fc.onderwerp(0));   // the "one" side / iterated subject
        KaleNaamContext rhs = fc.kaleNaam(1);
        java.util.List<KaleNaamDeelContext> delen = rhs.kaleNaamDeel();
        String rolC = stripArtikel(src(delen.get(0)));                 // RHS role — the FINAL hop onto the doel
        // onderwerpD must be a back-reference to the iterated anker ITSELF, not a different object of the
        // same type reached by a role: "de groep" lifts, but "de buurgroep" must WARN, not silently
        // reuse the anker's own join. It denotes the anker when its written noun matches the ANKER's own
        // written noun (the anker may itself be named by a role — "een toegewezen reeks punten" —
        // so a type-name-only test is too strict); the strict type match is kept as an additional path.
        String ankerNoun = bouwer.primaireNaamVanOnderwerp(fc.onderwerp(0));
        String terugNoun = bouwer.primaireNaamVanOnderwerp(fc.onderwerp(1));
        boolean directeAnker = anker != null && terugNoun != null
                && ((ankerNoun != null && stripArtikel(terugNoun).equalsIgnoreCase(stripArtikel(ankerNoun)))
                    || model.objecttypeOpNaam(stripArtikel(terugNoun)) == anker);

        String reden = null;
        if (anker == null) reden = "anker-objecttype niet herleidbaar";
        else if (!directeAnker) reden = "doelketen verwijst niet rechtstreeks naar de anker (geen back-reference)";
        else {
            // Walk the §9.4 navigation chain anker -> … -> doel-parent. The written kaleNaamDeel are outside-in
            // (rolC at index 0, the innermost hop last), so the hops from the anker are the segments in REVERSE
            // (index size-1 down to 1); segment 0 (rolC) is the final hop onto the doel. The reference field
            // lives on the many side: a to-many hop keeps it on the child (joined `child(veld == $prev)`), a
            // to-one hop on the parent (joined `child(this == $prev.veld)`) — the side objectcreatie set it on.
            java.util.List<ResolvedFeitCreatie.Tussenstap> stappen = new ArrayList<>();
            Objecttype cur = anker;
            for (int i = delen.size() - 1; i >= 1; i--) {
                String role = stripArtikel(src(delen.get(i)));
                Objecttype next = model.rolDoeltypeNaarSubject(cur, role);
                if (next == null) { reden = "tussenstap-rol '" + role + "' niet eenduidig te herleiden"; break; }
                ResolvedFeitCreatie.Tussenstap stap = tussenstap(cur, next, role);
                if (stap == null) { reden = "referentieveld-zijde voor tussenstap '" + role + "' niet af te leiden"; break; }
                stappen.add(stap);
                cur = next;
            }
            if (reden == null) {
                Objecttype doel = model.rolDoeltypeNaarSubject(cur, rolC);
                String joinVeld = model.verbindendeRolViaElementnaam(cur, rolC); // existing rolC relation's field on doel
                String zetVeld  = model.verbindendeRolViaElementnaam(anker, rolB); // the NEW relation's field on doel -> anker
                if (doel == null || joinVeld == null || zetVeld == null)
                    reden = "rol of feittype niet eenduidig te herleiden";
                else if (joinVeld.equalsIgnoreCase(zetVeld))
                    reden = "de nieuwe en de bestaande relatie vallen samen (zelfde referentieveld)";
                // The reference field lives on the MANY side, pointing at the ONE side (§13.3.9.1 cardinaliteit;
                // the convention objectCreatie links by). Both the final join (rolC, from the last hop) and the
                // set (rolB, from the anker) field exist on doel only when doel is the MANY side of BOTH — else
                // the field belongs on the other side and a setter would be rejected or link the wrong way. WARN.
                else if (!(model.naarVeelRol(cur, doel, rolC) && model.naarVeelRol(anker, doel, rolB)))
                    reden = "referentieveld hoort aan de meervoudige kant; het doel is hier niet de meervoudige kant";
                else {
                    Conditie effectief = doelVoorwaarde(voorwaarde, vd, anker, doel, rolC);
                    // uchar symbolic form (WF-028, all-or-nothing): the created relation (anker/doel via
                    // rolB) and the final join relation (cur/doel via rolC), each as a declared-name
                    // binding, plus a RolBinding on every tussenstap. Emitted only when all of that is
                    // uniquely derivable AND the created relation differs from the final hop's; otherwise
                    // the bean-field-only form stays (symbolic fields, and the tussenstap bindings, absent).
                    ElementBindingRef created = model.elementBinding(anker, rolB);
                    ElementBindingRef join = model.elementBinding(cur, rolC);
                    boolean symbolisch = created != null && join != null
                            && !created.feittype().equalsIgnoreCase(join.feittype())
                            && stappen.stream().allMatch(s -> s.rolBinding() != null);
                    if (symbolisch) {
                        RolBindingRef joinRol = new RolBindingRef(join.feittype(), join.elementRol());
                        model.feitCreaties.add(new io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie(
                                naam, anker, doel, zetVeld, joinVeld, stappen, effectief, geldigheid,
                                created.feittype(), created.subjectRol(), created.elementRol(), joinRol));
                    } else {
                        List<ResolvedFeitCreatie.Tussenstap> kaal = new ArrayList<>();
                        for (ResolvedFeitCreatie.Tussenstap s : stappen)
                            kaal.add(new ResolvedFeitCreatie.Tussenstap(s.type(), s.veld(), s.viaOuderVeld()));
                        model.feitCreaties.add(new io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie(
                                naam, anker, doel, zetVeld, joinVeld, kaal, effectief, geldigheid));
                    }
                    return;
                }
            }
        }
        model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "feitcreatie wordt nog niet naar IR/DRL omgezet (regel '" + naam + "'): " + reden,
                fc.getStart().getLine()));
    }

    /** One §9.4 chain hop cur -&gt; next via {@code role}: which side holds the reference field, and the join
     *  form, or null when undeterminable. The field lives on the MANY side (§13.3.9.1). A TO-MANY hop keeps it
     *  on the CHILD (next) -&gt; {@code next(veld == $prev)}. A TO-ONE hop keeps it on the PARENT (cur) only when
     *  cur is genuinely the many side (the reverse role is meervoudig) or an objectcreatie placed the link on
     *  cur -&gt; {@code next(this == $prev.veld)}; it is on the CHILD when an objectcreatie placed it on next. A
     *  bare one-to-one with NEITHER signal is not derivable from the model — return null (honest WARN), never a
     *  guessed side that may emit an uncompilable wrong-side join. */
    private ResolvedFeitCreatie.Tussenstap tussenstap(Objecttype cur, Objecttype next, String role) {
        // The uchar RolBinding of a hop names the REACHED (far) role: for a to-many / on-child hop that
        // is the ELEMENT role the written name denotes (elementRolBinding); for a to-one / on-parent hop
        // it is the role next fills (verbindendeRolBinding). Null there withholds the whole symbolic form.
        if (model.naarVeelRol(cur, next, role)) {                            // to-many: field on the child
            String veld = model.verbindendeRolViaElementnaam(cur, role);
            return veld == null ? null
                    : new ResolvedFeitCreatie.Tussenstap(next, veld, false, model.elementRolBinding(cur, role));
        }
        String curRol = model.verbindendeRolViaElementnaam(cur, role);       // cur's own role in this feittype
        boolean curIsVeel = curRol != null && model.naarVeelRol(next, cur, curRol); // reverse to-many -> cur is many
        if (curIsVeel || aangemaaktNaar(cur, next)) {                        // field on cur (parent)
            String veld = model.verbindendeRol(cur, next);
            return veld == null ? null
                    : new ResolvedFeitCreatie.Tussenstap(next, veld, true, model.verbindendeRolBinding(cur, next));
        }
        if (aangemaaktNaar(next, cur)) {                                     // 1-1: objectcreatie put it on next
            String veld = model.verbindendeRolViaElementnaam(cur, role);
            return veld == null ? null
                    : new ResolvedFeitCreatie.Tussenstap(next, veld, false, model.elementRolBinding(cur, role));
        }
        return null;                                                        // bare 1-1: side not derivable
    }

    /** True when an objectcreatie creates {@code houder} linked to {@code doelwit} ("Een doelwit heeft een
     *  houder"), so — by the convention objectCreatie sets the reference on the new object — {@code houder}
     *  holds the reference field to {@code doelwit}. (Source-order: an objectcreatie earlier in the document.) */
    private boolean aangemaaktNaar(Objecttype houder, Objecttype doelwit) {
        return model.objectCreaties.stream().anyMatch(oc -> oc.nieuwType() == houder && oc.subject() == doelwit);
    }

    /** §13.4.7 voorwaarde hosting. A feitcreatie guard is built with the ANKER as subject; a guard that
     *  names a kenmerk of the DOEL ("indien de deelnemer vip is") is then an unresolved marker, because the
     *  single-subject §8.1.8 path refuses a to-many navigation from the anker (it would need a separate
     *  pattern). The feitcreatie HAS that pattern ($doel), so re-resolve the guard with the doel as subject;
     *  if it is then a single BARE doel-kenmerk, tag it with {@code eigenaar = doel} so the transpiler hosts
     *  it on the $doel pattern (and never the anker — even under a same-named anker kenmerk). Restricted to
     *  the simple single-kenmerk guard and to {@code doel != anker}: an anker guard that already resolved, a
     *  compound, a self-relation, or a still-unresolved guard is returned unchanged — an un-lowerable guard
     *  stays an honest inert no-op, never a wrong link. */
    private Conditie doelVoorwaarde(Conditie ankerVoorwaarde, VoorwaardeDeelContext vd, Objecttype anker, Objecttype doel, String rolC) {
        if (!(ankerVoorwaarde instanceof Conditie.Onopgelost) || vd == null || doel == anker) return ankerVoorwaarde;
        Conditie doelV;
        int diagSnapshot = model.diagnostics.size();
        doelRol = rolC;                                    // accept "de <rolC>" as the doel; reject "hij"/anker
        try {
            bouwer.setSubject(doel);
            doelV = bouwVoorwaarde(vd);
        } finally {
            doelRol = null;
            bouwer.setSubject(anker);                       // restore: the anker is the iterated subject
            // This second build is SPECULATIVE — the anker build and the separate voorwaarden() type-check own
            // the diagnostics; drop any this pass appended so a flawed guard is never reported twice.
            model.diagnostics.subList(diagSnapshot, model.diagnostics.size()).clear();
        }
        if (doelV instanceof Conditie.Kenmerk k && k.navigatie() == null && k.eigenaar() == null)
            return new Conditie.Kenmerk(k.kenmerk(), null, k.negatie(), doel, k.bron());
        return ankerVoorwaarde;
    }

    /** §13.4.5 kenmerktoekenning: the subject acquires a kenmerk (a boolean property).
     *  Modelled as setting that boolean attribute to true. */
    private void kenmerk(String naam, KenmerkToekenningContext kt, Conditie voorwaarde, Geldigheid geldigheid) {
        Objecttype subject = bouwer.objecttypeVanKeten(kt.onderwerpKeten());
        String kenmerk = src(kt.kaleNaam()).replaceFirst("^(?i)(de|het|een)\\s+", "");
        Expr doel = new Expr.AttribuutRef(kenmerk, subject, src(kt.onderwerpKeten()),
                null, new Datatype.Booleaan(), bron(kt));
        Expr waarde = new Expr.Literal("waar", new Datatype.Booleaan(), Bron.synthetisch("waar"));
        model.resolvedRegels.add(new ResolvedRegel(naam, ResolvedRegel.Soort.KENMERKTOEKENNING, doel, waarde, voorwaarde, geldigheid, huidigeBronAnnotaties));
    }

    /** §9.9/§13.4.12 startpuntbepaling: "Het startpunt van <tijdlijn> voor <onderwerp> wordt bepaald
     *  door <expressie>". The anchor materialises as a synthetic derived attribute
     *  "startpunt <tijdlijn>" (Datum in dagen) on the subject, written by an ordinary
     *  {@link ResolvedRegel} — so voorwaarde, geldigheid, freshness and idempotence ride the standard
     *  machinery in both back-ends; §9.9's "verschillende startpunten onder verschillende voorwaarden"
     *  are simply several writers of that one attribute. §6.8: the deriving expression must be
     *  Datum in dagen (ERROR otherwise; Onbekend makes no claim), and a leeg value is a fout — the
     *  back-ends raise the §9.5 Inconsistentie for it (the write itself degrades to leeg). */
    private void startpunt(String naam, StartpuntBepalingContext sb, Conditie voorwaarde, Geldigheid geldigheid) {
        int line = sb.getStart().getLine();
        String tijdlijnNaam = RegelSpraakModel.normaliseerTijdlijnNaam(src(sb.tijdlijnNaam()));
        RegelSpraakModel.Tijdlijn tijdlijn = model.tijdlijnOpNaam(tijdlijnNaam);
        if (tijdlijn == null) {
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "startpuntbepaling: tijdlijn '" + tijdlijnNaam + "' is niet gedeclareerd (regel '" + naam + "')", line));
            return;
        }
        if (!tijdlijn.variabelStartpunt())
            // §9.9 defines the startpuntbepaling for a tijdlijn MET variabel startpunt; a fixed-startpunt
            // tijdlijn getting one is a model inconsistency — warn, but lower it (the anchor is inert there).
            model.diagnostics.add(new Diagnostic(Severity.WARNING,
                    "startpuntbepaling: tijdlijn '" + tijdlijnNaam + "' heeft geen variabel startpunt (§3.8) (regel '" + naam + "')", line));
        Objecttype subject = model.resolveObjecttype(src(sb.universeelOnderwerp().kaleNaam()));
        if (subject == null) {
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "startpuntbepaling: onderwerp '" + src(sb.universeelOnderwerp().kaleNaam())
                    + "' is geen objecttype of rol (regel '" + naam + "')", line));
            return;
        }
        Expr anker = bouwer.bouw(sb.expressie());
        if (!anker.type().isOnbekend() && !(anker.type() instanceof Datatype.DatumInDagen))
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "startpuntbepaling: de expressie moet datum in dagen zijn, niet "
                    + anker.type().naam() + " (§6.8) (regel '" + naam + "')", line));
        String lid = "startpunt " + tijdlijnNaam;
        // the synthetic anchor name is reserved: an author-declared attribute of another type under this
        // exact name would otherwise silently merge with the anchor (a latent type-mismatched write).
        Datatype bestaand = subject.attributen.get(lid);
        if (bestaand != null && !(bestaand instanceof Datatype.DatumInDagen)) {
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "startpuntbepaling: attribuutnaam '" + lid + "' is gereserveerd voor het startpunt-anker "
                    + "maar al gedeclareerd als " + bestaand.naam() + " (regel '" + naam + "')", line));
            return;
        }
        subject.attributen.putIfAbsent(lid, new Datatype.DatumInDagen());
        if (model.startpuntLid(subject, tijdlijnNaam) == null)
            model.resolvedStartpunten.add(new io.github.yhavinga.regelspraak.ir.ResolvedStartpunt(tijdlijnNaam, subject.naam, lid));
        Expr doel = new Expr.AttribuutRef(lid, subject, src(sb.universeelOnderwerp()),
                null, new Datatype.DatumInDagen(), bron(sb));
        model.resolvedRegels.add(new ResolvedRegel(naam, ResolvedRegel.Soort.GELIJKSTELLING, doel, anker, voorwaarde, geldigheid, huidigeBronAnnotaties));
    }

    /** The object type a rule is about — the object type of its assignment target. */
    private Objecttype subjectVan(ResultaatDeelContext rd) {
        AttribuutVanOnderwerpContext doel = null;
        if (rd == null) return null;
        if (rd.gelijkstelling() != null) doel = rd.gelijkstelling().attribuutVanOnderwerp();
        else if (rd.initialisatie() != null) doel = rd.initialisatie().attribuutVanOnderwerp();
        else if (rd.kenmerkToekenning() != null) return bouwer.objecttypeVanKeten(rd.kenmerkToekenning().onderwerpKeten());
        else if (rd.objectCreatie() != null) return bouwer.objecttypeVanKeten(rd.objectCreatie().onderwerpKeten());
        else if (rd.feitCreatie() != null) return bouwer.resolveOnderwerp(rd.feitCreatie().onderwerp(0)); // the anker — so its voorwaarde (incl. a kenmerk) resolves
        else if (rd.startpuntBepaling() != null) // §13.4.12 — so "zijn <attribuut>" in the anchor + voorwaarde resolves
            return model.resolveObjecttype(src(rd.startpuntBepaling().universeelOnderwerp().kaleNaam()));
        else if (rd.consistentieRegel() != null) {
            EnkelvoudigeConsistentieRegelContext e = rd.consistentieRegel().enkelvoudigeConsistentieRegel();
            return e != null && e.expressie() != null
                    && bouwer.bouw(e.expressie()) instanceof Expr.AttribuutRef a ? a.objecttype() : null;
        }
        return doel != null && bouwer.bouwAttribuut(doel) instanceof Expr.AttribuutRef a ? a.objecttype() : null;
    }

    private void registreer(String naam, ResolvedRegel.Soort soort, AttribuutVanOnderwerpContext doel,
                            ExpressieContext waarde, int line, Conditie voorwaarde, Geldigheid geldigheid) {
        Expr doelExpr = bouwer.bouwAttribuut(doel);
        Expr waardeExpr = bouwer.bouw(waarde);
        model.resolvedRegels.add(new ResolvedRegel(naam, soort, doelExpr, waardeExpr, voorwaarde, geldigheid, huidigeBronAnnotaties));
        Datatype doelType = doelExpr.type(), waardeType = waardeExpr.type();
        // §13.3.4.2 membership runs BEFORE the Onbekend bail: an enum literal is deliberately typed
        // Onbekend (its domain is context-dependent), and the doel IS that context here.
        if (waardeExpr instanceof Expr.Literal lit)
            keurEnumLabel(doelType, Literalen.enumLabel(lit.waarde()), line);
        keurTeken(doelType, waardeExpr, line);   // §3.3.1 Teken-beperking, static over a literal value
        if (doelType.isOnbekend() || waardeType.isOnbekend()) return; // not enough resolved to judge
        String fout = Typering.toekenning(doelType, waardeType);
        if (fout != null)
            model.diagnostics.add(new Diagnostic(Severity.ERROR, soort.name().toLowerCase() + ": " + fout, line));
    }

    /** §3.3.1 / §13.3.3.7: the optional Teken-beperking (negatief / niet-negatief / positief) narrows the
     *  datatype's toegestane waarden (§3.3.6 Tabel: "-49" is niet toegestaan for a niet-negatief geheel
     *  getal). The spec states NO runtime rule for a computed out-of-sign value (unlike §6.1.2 decimals,
     *  which demands an explicit afronding), so enforcement is RESOLVER-STATIC over the statically-decidable
     *  surface — a LITERAL assigned/initialised into a sign-restricted Numeriek doel — exactly the
     *  enum-domein posture (Tabel 61 rij 7). A computed value's sign is undecidable here and stays
     *  unchecked (documented in docs/grammar-divergences.md). Percentage carries no Teken in the model. */
    private void keurTeken(Datatype doelType, Expr waarde, int line) {
        if (!(doelType instanceof Datatype.Numeriek n) || n.teken() == Datatype.Teken.ONBEPAALD) return;
        if (!(waarde instanceof Expr.Literal lit) || lit.type() == null || !lit.type().isNumeriek()) return;
        java.math.BigDecimal v = Literalen.getal(lit);
        if (v == null) return;
        boolean ok = switch (n.teken()) {
            case NEGATIEF      -> v.signum() < 0;
            case NIET_NEGATIEF -> v.signum() >= 0;
            case POSITIEF      -> v.signum() > 0;
            case ONBEPAALD     -> true;   // unreachable (guarded above)
        };
        if (!ok) model.diagnostics.add(new Diagnostic(Severity.ERROR,
                "waarde " + lit.waarde().trim() + " voldoet niet aan de teken-beperking '"
                        + tekenNaam(n.teken()) + "' van het doel-datatype (§3.3.1)", line));
    }

    /** The §13.3.3.7 source spelling of a Teken. */
    private static String tekenNaam(Datatype.Teken t) {
        return switch (t) {
            case NEGATIEF -> "negatief"; case NIET_NEGATIEF -> "niet-negatief";
            case POSITIEF -> "positief"; case ONBEPAALD -> "";
        };
    }

    /** Tabel 36/43/61 rij 7 + §13.3.4.2: a written enum label must be IN the context domain's declared
     *  value set — an undeclared label is a dead comparison or a foreign-value assignment, silently. The
     *  literal itself stays typed Onbekend (which domain applies IS this context); a null label (the
     *  expression is not a quoted enumeratiewaarde) or a non-enum context makes no claim. */
    private void keurEnumLabel(Datatype context, String label, int line) {
        if (!(context instanceof Datatype.Enumeratie en) || label == null || en.waarden().contains(label)) return;
        model.diagnostics.add(new Diagnostic(Severity.ERROR,
                "'" + label + "' is geen waarde van " + en.naam() + " " + en.waarden() + " (§13.3.4.2)", line));
    }

    /** The enum label when the WHOLE expressie is a single quoted enumeratiewaarde, else null. An
     *  ENUM_LITERAL is one lexer token, so getText() preserves its interior spaces — no need for the
     *  costlier src() stream reconstruction that runs on every comparison operand. */
    private static String enumLabelVan(ExpressieContext e) {
        return e == null ? null : Literalen.enumLabel(e.getText().trim());
    }

    // ---- §13.4.13/.14/.15 condition IR (structure for the transpiler "when" side) ----
    // Built alongside (not instead of) the exhaustive type-check walk below, so it
    // adds structure without changing any diagnostic. Forms not yet structured —
    // kenmerk/objecttype membership tests, period comparisons — become Onopgelost.
    private Conditie bouwVoorwaarde(VoorwaardeDeelContext v) {
        if (v.toplevelSamengesteldeVoorwaarde() != null) return bouwSamengesteld(
                v.toplevelSamengesteldeVoorwaarde().voorwaardeKwantificatie(),
                v.toplevelSamengesteldeVoorwaarde().samengesteldeVoorwaardeOnderdeel(), v.toplevelSamengesteldeVoorwaarde());
        if (v.toplevelElementaireVoorwaarde() != null) {
            ToplevelElementaireVoorwaardeContext c = v.toplevelElementaireVoorwaarde();
            // §8.3.1: after 'indien'/'dat'/'gedurende de tijd dat' the grammar admits ONLY the verb-trailing
            // (vragende) toplevelOperator — the verb-leading register is reserved for samengestelde bullets.
            if (c.expressie() != null && c.toplevelOperator() != null)
                return uitToplevel(bouwer.bouw(c.expressie()), c.toplevelOperator(), bron(c));
            // §8.1.9 / §13.4.15.18: "indien regelversie <X> is gevuurd|inconsistent" as the whole voorwaarde.
            if (c.consistentieVoorwaarde() != null) return bouwRegelversie(c.consistentieVoorwaarde());
        }
        // §13.4.13.1 / §8.4.3: a standalone periodevergelijking as the whole voorwaardendeel
        // ("… van dd. 1-1-2024 tot dd. 8-2-2025" directly after the resultaatdeel, no 'indien').
        if (v.periodevergelijkingEnkelvoudig() != null) return bouwPeriode(v.periodevergelijkingEnkelvoudig());
        return new Conditie.Onopgelost(bron(v));
    }

    /** §13.4.15.68 / §8.4.3: a periodevergelijkingEnkelvoudig (vanaf / van…tot / van…tot en met / tot /
     *  tot en met) to a {@link Conditie.Periode}. Each bound is a §13.4.15.68 &lt;datumexpressie&gt;; a bound
     *  whose resolved type makes a non-datum claim is an ERROR (an Onbekend operand carries no claim and
     *  passes). §5.1.3 fixes the bound semantics: vanaf inclusive, tot exclusive, tot en met inclusive. */
    private Conditie bouwPeriode(PeriodevergelijkingEnkelvoudigContext p) {
        Expr van = null, tot = null;
        if (p.VANAF() != null) {
            van = bouwer.bouw(p.expressie(0));
        } else if (p.VAN() != null) {
            van = bouwer.bouw(p.expressie(0));
            tot = bouwer.bouw(p.expressie(1));
        } else {
            tot = bouwer.bouw(p.expressie(0));
        }
        for (Expr grens : new Expr[]{van, tot})
            if (grens != null && grens.type() != null && !grens.type().isOnbekend() && !grens.type().isDatum())
                model.diagnostics.add(new Diagnostic(Severity.ERROR,
                        "een periodevergelijking (§8.4.3) vereist datum-expressies als grenzen, niet "
                                + grens.type().naam() + ": '" + grens.bron() + "'", p.getStart().getLine()));
        return new Conditie.Periode(van, tot, p.TOT_EN_MET() != null, bron(p));
    }

    private Conditie bouwSamengesteld(VoorwaardeKwantificatieContext k,
                                      SamengesteldeVoorwaardeOnderdeelContext onderdeel, ParserRuleContext heel) {
        // §13.4.14.6: when the indentation-blind grammar ABSORBED a dedented sibling bullet into a deeper compound but
        // the bullet COLUMNS re-nest cleanly, rebuild the correct sibling tree so the compound FIRES (depth-aware
        // re-nesting). herNestOnderdelen returns null when there is no column-absorption (the common case — a
        // byte-identical no-op) OR the columns do not disambiguate (the existing inconsistency-marker path then applies).
        List<Conditie> herNest = herNestOnderdelen(onderdeel);
        List<Conditie> onderdelen;
        if (herNest != null) {
            onderdelen = herNest;
        } else {
            onderdelen = new ArrayList<>();
            if (onderdeel != null)
                for (GenesteVoorwaardeContext g : onderdeel.genesteVoorwaarde()) onderdelen.add(bouwGenest(g));
        }
        Conditie.Kwantor kwantor = kwantorVan(k);
        int drempel = drempelVan(k);
        // A counting quantifier's telwoord is a positive whole number (§13.4.14.4 /
        // §13.4.14.5: één/twee/… or a written getal). A 0, negative, decimal, fraction or
        // unparseable value all reach drempelVan as < 1; flag it rather than let it collapse
        // the rule to always-true (minstens(0) is vacuously true). An over-size N is fine —
        // it is a faithfully unsatisfiable "false", so it is not an error.
        keurDrempel(kwantor, drempel, (k != null ? k : heel).getStart().getLine());
        // §13.4.14.6 the indentation-blind bullet grammar (bullet : (DASH|DOTDOT|BULLET)+, WS skipped) can ABSORB a
        // dedented sibling bullet (a leaf OR another nested compound) into a preceding nested compound's list, so a
        // COUNTING quantifier AND-merges what the spec OR-s/counts -> a fired-WRONG result (the DRL oracle computed a
        // wrong kenmerk/SOM). The grammar discards depth, but ANTLR retains each bullet token's COLUMN and GLYPH: a list
        // whose direct bullets sit at different columns OR carry different glyphs (e.g. a top-level '-' absorbed into a
        // nested '..' list) absorbed an outer-level sibling. Marker such a list (Onopgelost) so BOTH back-ends decline
        // rather than fire a mis-parsed compound — for ANY quantifier (a well-formatted compound is column- AND
        // glyph-consistent, so only a genuinely-ambiguous input trips; the ALLE/DE merge is AND-idempotent but markering
        // it is still safe). A structural depth-2 fallback covers the residual fully-ambiguous case (same glyph AND same
        // column), scoped to COUNTING (a legitimately deep ALLE/DE nesting would otherwise be over-markered). A faithful
        // depth-aware re-nesting (fire correctly) is carded.
        // A re-nested list (herNest != null) has its absorption RESOLVED, so the inconsistency markers below no
        // longer apply; they guard only the un-disambiguatable residual (glyph-only / same-column-same-glyph) that
        // herNestOnderdelen declined.
        if (herNest == null && (bulletsInconsistent(onderdeel)
                || (kwantor != Conditie.Kwantor.ALLE && kwantor != Conditie.Kwantor.DE && ambiguusGenesteBullets(onderdelen))))
            return new Conditie.Onopgelost(bron(heel));
        return new Conditie.Samengesteld(kwantor, drempel, onderdelen, bron(heel));
    }

    /** §13.4.14.4/.5 a counting quantifier's telwoord must be a positive whole number (>= 1): a 0 / negative / decimal /
     *  unparseable value reaches {@link #drempelVan} as &lt; 1 and would collapse a ten-minste/ten-hoogste/precies to
     *  vacuously-true (minstens(0) always holds), so flag it. Shared by {@link #bouwSamengesteld} and the depth-aware
     *  re-nesting {@link #bouwUitKnopen} (which builds inner compounds directly, bypassing bouwSamengesteld). */
    private void keurDrempel(Conditie.Kwantor kwantor, int drempel, int regel) {
        if ((kwantor == Conditie.Kwantor.TEN_MINSTE || kwantor == Conditie.Kwantor.TEN_HOOGSTE
                || kwantor == Conditie.Kwantor.PRECIES) && drempel < 1)
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                    "telwoord van een kwantificatie moet een positief geheel getal (>= 1) zijn", regel));
    }

    // ---- §13.4.14.6 depth-aware bullet re-nesting (column-driven) ----------------------------------------------
    // The bullet grammar is indentation-blind (`bullet : (DASH|DOTDOT|BULLET)+`, WS skipped), so a dedented sibling
    // bullet is ABSORBED as a child of the preceding nested compound. ANTLR retains the discarded indentation on each
    // bullet token (getCharPositionInLine()); when that absorption re-nests cleanly by column, rebuild the intended
    // sibling tree so BOTH back-ends fire the spec-correct OR/count instead of markering an AND-merged mis-parse.

    /** A flattened bullet: its indentation column and the parse node, captured in source order. */
    private record RauweBullet(int kolom, GenesteVoorwaardeContext ctx) {}
    /** A re-nested logical node: a leaf or a compound (its {@code genesteVoorwaarde} ctx) with rebuilt children. */
    private record LogischeKnoop(GenesteVoorwaardeContext ctx, List<LogischeKnoop> kinderen) {}

    /** Depth-first (= source-order) flatten of every {@code genesteVoorwaarde} in the subtree, with its bullet column. */
    private static void platBullets(SamengesteldeVoorwaardeOnderdeelContext onderdeel, List<RauweBullet> uit) {
        for (GenesteVoorwaardeContext g : onderdeel.genesteVoorwaarde()) {
            uit.add(new RauweBullet(g.bullet().getStart().getCharPositionInLine(), g));
            if (g.genesteSamengesteldeVoorwaarde() != null)
                platBullets(g.genesteSamengesteldeVoorwaarde().samengesteldeVoorwaardeOnderdeel(), uit);
        }
    }

    /** True iff some onderdeel in the subtree has direct bullets at non-uniform COLUMNS — the reliable signature of the
     *  indentation-blind grammar absorbing a dedented sibling from an outer level into a deeper list. */
    private static boolean kolommenInconsistentDiep(SamengesteldeVoorwaardeOnderdeelContext onderdeel) {
        List<GenesteVoorwaardeContext> gs = onderdeel.genesteVoorwaarde();
        if (gs == null || gs.isEmpty()) return false;   // a childless nested header (malformed parse, ANTLR error-recovery) -> no absorption
        int kol0 = gs.get(0).bullet().getStart().getCharPositionInLine();
        for (GenesteVoorwaardeContext g : gs) {
            if (g.bullet().getStart().getCharPositionInLine() != kol0) return true;
            if (g.genesteSamengesteldeVoorwaarde() != null
                    && kolommenInconsistentDiep(g.genesteSamengesteldeVoorwaarde().samengesteldeVoorwaardeOnderdeel()))
                return true;
        }
        return false;
    }

    /** Re-nest a flat bullet list by indentation column into a logical sibling tree: a bullet at a deeper column is a
     *  child of the most recent compound header, a dedent returns to the matching ancestor level. Returns null when the
     *  columns do NOT form a clean indentation tree (a column that lines up with no open level, or a compound header
     *  whose next bullet is not deeper) — i.e. genuinely un-disambiguatable, so the caller keeps the marker path. */
    private static List<LogischeKnoop> herNestKolommen(List<RauweBullet> plat) {
        List<LogischeKnoop> wortel = new ArrayList<>();
        List<Integer> kindKolom = new ArrayList<>();                 // the child-column of each open frame
        List<List<LogischeKnoop>> kindLijst = new ArrayList<>();     // the children list of each open frame
        kindKolom.add(plat.get(0).kolom());
        kindLijst.add(wortel);
        for (int i = 0; i < plat.size(); i++) {
            RauweBullet r = plat.get(i);
            while (kindKolom.size() > 1 && r.kolom() < kindKolom.get(kindKolom.size() - 1)) {
                kindKolom.remove(kindKolom.size() - 1);
                kindLijst.remove(kindLijst.size() - 1);
            }
            if (r.kolom() != kindKolom.get(kindKolom.size() - 1)) return null;   // column lines up with no open level
            LogischeKnoop knoop = new LogischeKnoop(r.ctx(), new ArrayList<>());
            kindLijst.get(kindLijst.size() - 1).add(knoop);
            if (r.ctx().genesteSamengesteldeVoorwaarde() != null) {              // a compound header: open a child frame
                if (i + 1 >= plat.size() || plat.get(i + 1).kolom() <= r.kolom()) return null;   // no deeper children
                kindKolom.add(plat.get(i + 1).kolom());
                kindLijst.add(knoop.kinderen());
            }
        }
        return wortel;
    }

    /** Build the onderdelen list from a re-nested logical tree, within the current {@code bouwer} subject (the leaves'
     *  expressies resolve against whichever subject the caller set). A compound knoop reads its own quantifier. */
    private List<Conditie> bouwUitKnopen(List<LogischeKnoop> knopen) {
        List<Conditie> uit = new ArrayList<>();
        for (LogischeKnoop k : knopen) {
            GenesteSamengesteldeVoorwaardeContext c = k.ctx().genesteSamengesteldeVoorwaarde();
            if (c != null) {
                Conditie.Kwantor kw = kwantorVan(c.voorwaardeKwantificatie());
                int drempel = drempelVan(c.voorwaardeKwantificatie());
                keurDrempel(kw, drempel, c.getStart().getLine());   // a degenerate inner telwoord must still be flagged
                uit.add(new Conditie.Samengesteld(kw, drempel, bouwUitKnopen(k.kinderen()), bron(c)));
            }
            else if (k.ctx().elementaireVoorwaarde() != null)
                uit.add(bouwElementair(k.ctx().elementaireVoorwaarde()));
            else
                uit.add(new Conditie.Onopgelost(bron(k.ctx())));
        }
        return uit;
    }

    /** §13.4.14.6: if a bullet list carries a column-disambiguatable absorption, return the correctly re-nested
     *  onderdelen; else null (the caller keeps its existing per-bullet build + inconsistency-marker path). Returns null
     *  for the common no-absorption case (a structural no-op, so well-formatted lists are byte-identical) and for the
     *  un-disambiguatable residual (glyph-only or same-column-same-glyph, which the marker path then declines). */
    private List<Conditie> herNestOnderdelen(SamengesteldeVoorwaardeOnderdeelContext onderdeel) {
        if (onderdeel == null || onderdeel.genesteVoorwaarde().isEmpty()) return null;
        if (!kolommenInconsistentDiep(onderdeel)) return null;       // no absorption -> existing path (no-op)
        List<RauweBullet> plat = new ArrayList<>();
        platBullets(onderdeel, plat);
        List<LogischeKnoop> wortel = herNestKolommen(plat);
        if (wortel == null) return null;                             // columns don't disambiguate -> marker path
        return bouwUitKnopen(wortel);
    }

    /** §13.4.14.6 whether a bullet list's direct bullets are inconsistent — different indentation COLUMNS or different
     *  bullet GLYPHS — the reliable signature of the indentation-blind grammar absorbing a sibling bullet from an outer
     *  level into this list. The lexer skips WS but ANTLR retains each bullet token's column and text, so a well-formatted
     *  compound (siblings at one column with one glyph, e.g. the '-' top / '..' nested convention) reads false; only
     *  a genuinely-ambiguous / mis-nested input reads true. The glyph check catches the un-indented mixed-glyph absorption
     *  the column check cannot (a '-' leaf folded into a '..' list); the residual same-glyph-same-column case is the
     *  ambiguusGenesteBullets fallback's job. */
    private static boolean bulletsInconsistent(SamengesteldeVoorwaardeOnderdeelContext onderdeel) {
        if (onderdeel == null) return false;
        List<GenesteVoorwaardeContext> gs = onderdeel.genesteVoorwaarde();
        if (gs == null || gs.size() < 2) return false;
        int col0 = gs.get(0).bullet().getStart().getCharPositionInLine();
        String glyph0 = gs.get(0).bullet().getText();
        for (GenesteVoorwaardeContext g : gs)
            if (g.bullet().getStart().getCharPositionInLine() != col0 || !g.bullet().getText().equals(glyph0)) return true;
        return false;
    }

    /** §13.4.14.6 structural fallback for the residual fully-ambiguous input the column+glyph check cannot see (same
     *  glyph AND same column): a bullet list carrying the flatten signature of 2+ sibling NESTED compounds — a nested
     *  compound bullet that itself contains a DEEPER compound. A single level of nesting (a nested compound of leaves) is
     *  fine. Scoped to COUNTING by the caller (a deep ALLE/DE merge is AND-idempotent). */
    private static boolean ambiguusGenesteBullets(List<Conditie> onderdelen) {
        return onderdelen.stream().anyMatch(o -> o instanceof Conditie.Samengesteld inner
                && inner.onderdelen().stream().anyMatch(x -> x instanceof Conditie.Samengesteld));
    }

    /** The threshold count N of a ten-minste/ten-hoogste/precies quantifier (§13.4.14.4),
     *  or 0 when the quantifier carries no count (alle / geen / de). */
    private int drempelVan(VoorwaardeKwantificatieContext k) {
        return k == null ? 0 : telwoordWaarde(k.telwoord());
    }

    /** §5.6/§13.4.13.4 a COMPOUND subselectie ("… die aan alle volgende voorwaarden voldoen: - … - …") resolved
     *  against the AGGREGATED ELEMENT type: each bullet binds to the element (not the rule subject), so the resulting
     *  {@link Conditie.Samengesteld} is a filter on the accumulate SOURCE pattern. The bouwer subject is saved and
     *  restored around the bullet walk — aggregatieFilter runs DURING {@code bouwer.bouw}, nested inside the outer rule
     *  resolution, so the rule subject must be put back (in a finally, even on a bullet-resolution throw). */
    private Conditie samengesteldeElementFilter(SamengesteldPredicaatContext sp, Objecttype elem) {
        if (sp == null || elem == null
                || (sp.samengesteldeVoorwaardeOnderdeel() == null && sp.elementaireVoorwaarde() == null)) return null;
        Objecttype vorig = bouwer.subject();          // SAVE the outer rule subject
        try {
            bouwer.setSubject(elem);                   // "zijn X" / "hij" in the bullets resolve against the element
            // §13.4.13.4 the INLINE single-comparison tail ("… die aan de volgende voorwaarde voldoet: zijn
            // leeftijd is kleiner dan …") — one non-bulleted voorwaarde, resolved by the same elementaire
            // machinery a bullet uses and wrapped as a 1-onderdeel compound so every quantifier lowers
            // through the existing Samengesteld path.
            if (sp.samengesteldeVoorwaardeOnderdeel() == null)
                return new Conditie.Samengesteld(kwantorVanKwantificatie(sp.kwantificatie()),
                        sp.kwantificatie() == null ? 0 : telwoordWaarde(sp.kwantificatie().telwoord()),
                        List.of(bouwElementair(sp.elementaireVoorwaarde())), bron(sp));
            // §13.4.14.6 re-nest a column-absorbed bullet list (the SAME depth-aware machinery as a voorwaarde compound)
            List<Conditie> herNest = herNestOnderdelen(sp.samengesteldeVoorwaardeOnderdeel());
            List<Conditie> onderdelen;
            if (herNest != null) {
                onderdelen = herNest;
            } else {
                onderdelen = new ArrayList<>();
                for (GenesteVoorwaardeContext g : sp.samengesteldeVoorwaardeOnderdeel().genesteVoorwaarde())
                    onderdelen.add(bouwGenest(g));     // the SAME bullet machinery as a voorwaarde compound
            }
            Conditie.Kwantor kw = kwantorVanKwantificatie(sp.kwantificatie());
            // §13.4.14.6 IR-integrity guard: a nested-compound bullet with a DEEPER compound child under a
            // COUNTING quantifier stays Onopgelost — the indentation-blind bullet grammar can flatten sibling
            // bullets into a depth-2 child in ways the column re-nesting cannot always rebuild, and a
            // mis-merged bullet under a COUNT changes what is counted (AND-merging what the spec OR-s): a
            // fired-wrong shared value. Under ALLE/DE the merge is semantics-preserving (every bullet must
            // hold anyway), so no guard applies. This is a statement about the PARSE, not about what a
            // back-end can lower (back-end-agnostic resolver): capability verdicts live in the back-ends and are reconciled
            // by the manifest-parity test.
            if (kw != Conditie.Kwantor.ALLE && kw != Conditie.Kwantor.DE
                    && onderdelen.stream().anyMatch(c -> c instanceof Conditie.Samengesteld s
                            && s.onderdelen().stream().anyMatch(x -> x instanceof Conditie.Samengesteld)))
                return new Conditie.Onopgelost(bron(sp));
            return new Conditie.Samengesteld(kw,
                    sp.kwantificatie() == null ? 0 : telwoordWaarde(sp.kwantificatie().telwoord()), onderdelen, bron(sp));
        } finally {
            bouwer.setSubject(vorig);                   // RESTORE
        }
    }

    /** §13.4.14.5 kwantificatie (used by the compound subselectie) -> Kwantor. Distinct from voorwaardeKwantificatie:
     *  it admits 'al' (AL, treated as ALLE) and ALLE has no "( van de )?" tail. A bare DE quantifies the whole set. */
    private static Conditie.Kwantor kwantorVanKwantificatie(KwantificatieContext k) {
        if (k == null || k.ALLE() != null || k.AL() != null) return Conditie.Kwantor.ALLE;
        if (k.GEEN_VAN_DE() != null) return Conditie.Kwantor.GEEN;
        if (k.TEN_MINSTE() != null)  return Conditie.Kwantor.TEN_MINSTE;
        if (k.TEN_HOOGSTE() != null) return Conditie.Kwantor.TEN_HOOGSTE;
        if (k.PRECIES() != null)     return Conditie.Kwantor.PRECIES;
        return Conditie.Kwantor.DE;
    }

    /** A telwoord (§13.4.14.4): a written één/twee/drie/vier or a NUMBER, else 0 (no/unparseable count). */
    private static int telwoordWaarde(TelwoordContext t) {
        if (t == null) return 0;
        if (t.NUMBER() != null) try { return Integer.parseInt(t.NUMBER().getText()); } catch (NumberFormatException e) { return 0; }
        if (t.EEN_TELWOORD() != null)  return 1;
        if (t.TWEE_TELWOORD() != null) return 2;
        if (t.DRIE_TELWOORD() != null) return 3;
        if (t.VIER_TELWOORD() != null) return 4;
        return 0;
    }

    /** §13.4.8 samengestelde consistentie-criteria → a {@link Conditie.Samengesteld} over the criteria. */
    private Conditie bouwCriteria(ConsistentieKwantificatieContext k, SamengesteldCriteriumOnderdeelContext onderdeel, Bron bron) {
        List<Conditie> onderdelen = new ArrayList<>();
        for (GenestCriteriumContext g : onderdeel.genestCriterium()) onderdelen.add(bouwCriterium(g));
        return new Conditie.Samengesteld(kwantorVanConsistentie(k), k == null ? 0 : telwoordWaarde(k.telwoord()), onderdelen, bron);
    }

    private Conditie bouwCriterium(GenestCriteriumContext g) {
        if (g.samengesteldCriterium() != null) {
            SamengesteldCriteriumContext c = g.samengesteldCriterium();
            return bouwCriteria(c.consistentieKwantificatie(), c.samengesteldCriteriumOnderdeel(), bron(c));
        }
        if (g.elementaireVoorwaarde() != null) return bouwElementair(g.elementaireVoorwaarde());
        return new Conditie.Onopgelost(bron(g));
    }

    /** §13.4.14.3 consistentie-kwantor; null (the single "het volgende criterium") is the trivial ALLE. */
    private static Conditie.Kwantor kwantorVanConsistentie(ConsistentieKwantificatieContext k) {
        if (k == null || k.ALLE() != null) return Conditie.Kwantor.ALLE;
        if (k.GEEN_VAN_DE() != null)  return Conditie.Kwantor.GEEN;
        if (k.TEN_MINSTE() != null)   return Conditie.Kwantor.TEN_MINSTE;
        if (k.TEN_HOOGSTE() != null)  return Conditie.Kwantor.TEN_HOOGSTE;
        if (k.PRECIES() != null)      return Conditie.Kwantor.PRECIES;
        return Conditie.Kwantor.ALLE;
    }

    /** Every criterion's left operand is about {@code subject} (a literal/parameter left side is fine):
     *  the rule lowers to one subject pattern, so a criterion over another object type cannot be lifted. */
    private static boolean eenSubject(Conditie c, Objecttype subject) {
        if (c instanceof Conditie.Vergelijking v)
            return !(v.links() instanceof Expr.AttribuutRef a) || a.objecttype() == subject;
        if (c instanceof Conditie.Predicaat p)
            return !(p.operand() instanceof Expr.AttribuutRef a) || a.objecttype() == subject;
        if (c instanceof Conditie.Samengesteld s)
            return s.onderdelen().stream().allMatch(d -> eenSubject(d, subject));
        return true;
    }

    /** The type the criteria are about — the object type of the first criterion's left operand. */
    private static Objecttype subjectVanAssertie(Conditie c) {
        if (c instanceof Conditie.Vergelijking v && v.links() instanceof Expr.AttribuutRef a) return a.objecttype();
        if (c instanceof Conditie.Predicaat p && p.operand() instanceof Expr.AttribuutRef a) return a.objecttype();
        if (c instanceof Conditie.Samengesteld s)
            for (Conditie d : s.onderdelen()) { Objecttype t = subjectVanAssertie(d); if (t != null) return t; }
        return null;
    }

    private Conditie bouwGenest(GenesteVoorwaardeContext g) {
        if (g.genesteSamengesteldeVoorwaarde() != null) {
            GenesteSamengesteldeVoorwaardeContext c = g.genesteSamengesteldeVoorwaarde();
            return bouwSamengesteld(c.voorwaardeKwantificatie(), c.samengesteldeVoorwaardeOnderdeel(), c);
        }
        if (g.elementaireVoorwaarde() != null) return bouwElementair(g.elementaireVoorwaarde());
        return new Conditie.Onopgelost(bron(g));
    }

    /** An §13.4.15 elementaire voorwaarde / consistentie-criterium ("&lt;expressie&gt; &lt;operator&gt;") to a
     *  {@link Conditie}; shared by the samengestelde voorwaarde and the samengestelde consistentieregel. */
    private Conditie bouwElementair(ElementaireVoorwaardeContext c) {
        if (c.expressie() != null && c.nonToplevelOperator() != null)
            return uitNonToplevel(bouwer.bouw(c.expressie()), c.nonToplevelOperator(), bron(c));
        if (c.expressie() != null && c.toplevelOperator() != null)
            return uitToplevel(bouwer.bouw(c.expressie()), c.toplevelOperator(), bron(c));
        // §13.4.15.69 / §8.4.3: the "het is de periode <periodevergelijkingEnkelvoudig>" bullet form.
        if (c.periodevergelijkingElementair() != null)
            return bouwPeriode(c.periodevergelijkingElementair().periodevergelijkingEnkelvoudig());
        // §8.1.9 / §13.4.15.66: the "regelversie <X> is gevuurd|inconsistent" regelpredicaat as a bullet.
        if (c.consistentieVoorwaarde() != null) return bouwRegelversie(c.consistentieVoorwaarde());
        return new Conditie.Onopgelost(bron(c));
    }

    /** §8.1.9 / §13.4.15.66/.18 regelpredicaat to a {@link Conditie.Regelversie}. The reference may point
     *  FORWARD to a rule defined later in the document, so no existence check happens here — the back-ends
     *  verify the name against the model's Inconsistentie producers and decline an unknown reference as an
     *  honest marker (never a silent never-matching exists). The parenthesised versie-aanduiding is parsed
     *  but only carried in bron (see Conditie.Regelversie). */
    private Conditie bouwRegelversie(ConsistentieVoorwaardeContext c) {
        return new Conditie.Regelversie(src(c.vrijeNaam()), c.INCONSISTENT() != null, bron(c));
    }

    /** §8.2 / §13.4.15.70: the optional "(niet) gedurende de/het gehele jaar|maand" whole-period qualifier that
     *  precedes a predicate (verb-split or verb-leading); null when absent. {@code jaar} reads the JAAR vs MAAND
     *  keyword, {@code niet} the leading negation. The transpilers honour it on a tijdsafhankelijk onderwerp. */
    private static Conditie.GeheelPeriode geheelPeriode(GehelePeriodevergelijkingContext g) {
        if (g == null) return null;
        boolean jaar = g.JAAR() != null;   // else MAAND
        // the negation is either the bare NIET prefix (stellende vorm "is niet gedurende …") or the combined
        // NIET_GEDURENDE_*_GEHELE token (vragende vorm "… niet gedurende …", lexed as one token so the onderwerp
        // kaleNaam cannot absorb the leading "niet").
        boolean niet = g.NIET() != null || g.NIET_GEDURENDE_HET_GEHELE() != null || g.NIET_GEDURENDE_DE_GEHELE() != null;
        return new Conditie.GeheelPeriode(jaar, niet);
    }

    /** §8.1.4/§13.4.15.84 the &lt;geheelgetal&gt; digit-count N of "numeriek met exact N cijfers". The NUMBER token is
     *  over-broad (it also matches a §13.2.4 decimaalgetal "9,5"), but the spec requires a §13.2.3 &lt;geheelgetal&gt;
     *  (which DOES admit a leading minus); reject a decimal/rational count with an ERROR rather than silently coercing
     *  it to 0 (an inert always-false predicate). 0 if absent. */
    private int aantalCijfers(org.antlr.v4.runtime.tree.TerminalNode num) {
        if (num == null) return 0;
        String t = num.getText();
        if (!t.matches("-?\\d+")) {
            model.diagnostics.add(new Diagnostic(Severity.ERROR,
                "ongeldig aantal cijfers '" + t + "' bij 'numeriek met exact … cijfers' (verwacht een geheel getal, §8.1.4)", 0));
            return 0;
        }
        return Integer.parseInt(t);
    }

    /** The (operand, negatie) of an elfproef / numeriek-met-exact predicate. The §8.1 Tabel-16 vragende negated form
     *  "&lt;X&gt; niet aan de elfproef voldoet" / "&lt;X&gt; niet numeriek is met exact N cijfers" has its leading
     *  "niet" greedily absorbed into the operand's kaleNaam (NIET is a kaleNaamWoord), so op.NIET() is null; recover
     *  it by stripping the absorbed " niet" off the operand (verbatim-first via {@link #stripAbsorbedNiet}). The
     *  stellende form carries an explicit NIET token, so {@code expliciet} short-circuits the strip. */
    private record ElfOperand(Expr operand, boolean negatie) {}
    private static ElfOperand elfOperand(boolean expliciet, Expr lhs) {
        // The stellende form carries an explicit NIET token; otherwise recover a vragende negator the greedy parse
        // absorbed into the operand (ExpressieBouwer.possessief resolves "zijn <attr> niet" to the BARE attribuut but
        // keeps the full bron, so the absorbed "niet" survives as the bron's trailing word).
        return new ElfOperand(lhs, expliciet || absorbedNegator(lhs));
    }

    /** Whether {@code lhs} is an operand into whose source the greedy parse absorbed a trailing "niet" negator — an
     *  {@link Expr.AttribuutRef} whose bron ends in " niet" but whose (bare) attribuut does NOT end in "niet"
     *  (verbatim-first: an attribuut literally named "… niet" carries its own niet and must not be de-negated). The
     *  elfproef / numeriek-met-exact predicates RECOVER this as their negatie; every OTHER operator cannot honour a
     *  dangling negator, so it must marker rather than silently emit the inverted positive. */
    private static boolean absorbedNegator(Expr lhs) {
        return lhs instanceof Expr.AttribuutRef a && a.bron() != null
                && a.bron().snippet().stripTrailing().toLowerCase().endsWith(" niet")
                && !a.attribuut().toLowerCase().endsWith("niet");
    }

    private Conditie uitToplevel(Expr lhs, ToplevelOperatorContext op, Bron bron) {
        return keurGeheelPeriode(uitToplevelKaal(lhs, op, bron), geheelPeriode(op.gehelePeriodevergelijking()), bron);
    }

    private Conditie uitToplevelKaal(Expr lhs, ToplevelOperatorContext op, Bron bron) {
        Conditie.GeheelPeriode periode = geheelPeriode(op.gehelePeriodevergelijking());   // §8.2 whole-period check
        // A dangling absorbed "niet" silently INVERTS a comparison / leeg-gevuld (the bare-attr resolution dropped the
        // negator) -> marker it. The kenmerkcheck DE-negates it (bindKenmerk) and elfproef/numeriek RECOVER it
        // (elfOperand), so only the comparison + leeg/gevuld operators are guarded here.
        if (absorbedNegator(lhs) && (op.vergelijkingsTekenGesplitst() != null || op.LEEG() != null || op.GEVULD() != null))
            return new Conditie.Onopgelost(bron);
        if (op.vergelijkingsTekenGesplitst() != null && op.expressie() != null)
            return new Conditie.Vergelijking(naarOperator(cmpOf(op.vergelijkingsTekenGesplitst())),
                    lhs, bouwer.bouw(op.expressie()), periode, bron);
        if (op.LEEG() != null)   return new Conditie.Predicaat(Conditie.Predicaatsoort.LEEG, lhs, periode, bron);
        if (op.GEVULD() != null) return new Conditie.Predicaat(Conditie.Predicaatsoort.GEVULD, lhs, periode, bron);
        if (op.AAN_DE_ELFPROEF() != null) {
            ElfOperand e = elfOperand(op.NIET() != null, lhs);   // recover an absorbed vragende "niet"
            return new Conditie.Predicaat(Conditie.Predicaatsoort.ELFPROEF, e.operand(), e.negatie(), periode, bron);
        }
        if (op.NUMERIEK_IS_MET_EXACT() != null || op.NUMERIEK_ZIJN_MET_EXACT() != null) {
            ElfOperand e = elfOperand(op.NIET() != null, lhs);
            return new Conditie.Predicaat(Conditie.Predicaatsoort.NUMERIEK_MET_EXACT, e.operand(), e.negatie(),
                    aantalCijfers(op.NUMBER()), periode, bron);
        }
        Conditie kenmerk = kenmerkPredicaat(lhs, op.kaleNaam(), op.EEN() != null,
                op.HEEFT() != null || op.HEBBEN() != null, periode, bron); // EEN? kaleNaam (is|heeft): §8.1.8
        return kenmerk != null ? kenmerk : new Conditie.Onopgelost(bron); // else objecttype/navigation membership
    }

    private Conditie uitNonToplevel(Expr lhs, NonToplevelOperatorContext op, Bron bron) {
        return keurGeheelPeriode(uitNonToplevelKaal(lhs, op, bron), geheelPeriode(op.gehelePeriodevergelijking()), bron);
    }

    /** §8.2: the "gedurende de/het gehele &lt;periode&gt;" check is only defined when the predicate's
     *  onderwerp is TIJDSAFHANKELIJK. A qualifier over a static onderwerp is a spec error — reject it (an ERROR +
     *  Onopgelost) rather than silently markering a rule that can never fire. {@code niet} is irrelevant here (a
     *  whole-period NEGATION of a static check is just as ill-defined). */
    private Conditie keurGeheelPeriode(Conditie c, Conditie.GeheelPeriode periode, Bron bron) {
        if (periode == null || onderwerpIsTemporeel(c)) return c;
        model.diagnostics.add(new Diagnostic(Severity.ERROR,
            "de check op volledige periode (gedurende de/het gehele jaar/maand) kan alleen op een tijdsafhankelijk "
            + "onderwerp worden toegepast (§8.2): " + bron, 0));
        return new Conditie.Onopgelost(bron);
    }

    /** Whether the onderwerp of a §8.2-qualified predicate is tijdsafhankelijk — a temporal attribuut on either side
     *  of a comparison, the operand of a leeg/gevuld/elfproef/numeriek predicate, or a temporal (own or to-one
     *  navigated) kenmerk. A rolcheck / unstructured form has no tijdsafhankelijk onderwerp. */
    private boolean onderwerpIsTemporeel(Conditie c) {
        if (c instanceof Conditie.Vergelijking v) return exprTemporeel(v.links()) || exprTemporeel(v.rechts());
        if (c instanceof Conditie.Predicaat p)    return exprTemporeel(p.operand());
        if (c instanceof Conditie.Kenmerk k)      return kenmerkTemporeel(k);
        return false;
    }
    /** Whether an operand READS a TIJDSAFHANKELIJK value ANYWHERE — a §13.3.6 tijdlijn attribuut (own or navigated),
     *  a §3.8/§13.3.8 tijdlijn parameter, or such a read nested in arithmetic ("zijn saldo plus 1"), an afronding or a
     *  begrenzing. Mirrors ExpressieBouwer.temporeelAttribuutOperand (the §7.1/§8.4 temporal-operand gate) so the §8.2
     *  rejection only fires when the onderwerp is GENUINELY static — a wrapped/parameter temporal onderwerp is a VALID
     *  §8.2 (the transpiler markers what it cannot yet lower), never a spec error. */
    private static boolean exprTemporeel(Expr e) {
        if (e instanceof Expr.AttribuutRef a)
            return a.objecttype() != null && a.objecttype().attribuutGranulariteit.containsKey(a.attribuut());
        if (e instanceof Expr.ParameterRef p)  return p.parameter().granulariteit() != null;
        if (e instanceof Expr.Binair b)         return exprTemporeel(b.links()) || exprTemporeel(b.rechts());
        if (e instanceof Expr.Afgerond af)      return exprTemporeel(af.basis());
        if (e instanceof Expr.Begrensd bg)      return exprTemporeel(bg.basis())
                || (bg.minimum() != null && exprTemporeel(bg.minimum()))
                || (bg.maximum() != null && exprTemporeel(bg.maximum()));
        if (e instanceof Expr.DimensieCel dc)   return exprTemporeel(dc.attribuut());
        return false;
    }
    private boolean kenmerkTemporeel(Conditie.Kenmerk k) {
        Objecttype subject = bouwer.subject();
        Objecttype houder = k.navigatie() == null ? subject
                : (subject != null ? model.rolDoeltypeNaarSubject(subject, k.navigatie()) : null);
        return houder != null && houder.kenmerkGranulariteit.containsKey(k.kenmerk());
    }

    private Conditie uitNonToplevelKaal(Expr lhs, NonToplevelOperatorContext op, Bron bron) {
        Conditie.GeheelPeriode periode = geheelPeriode(op.gehelePeriodevergelijking());   // §8.2 whole-period check
        // A dangling absorbed "niet" silently INVERTS a comparison / leeg-gevuld -> marker it. The kenmerkcheck
        // DE-negates it and elfproef/numeriek RECOVER it (elfOperand), so only those two operators are guarded here.
        if (absorbedNegator(lhs) && (op.vergelijkingsTeken() != null || op.LEEG() != null || op.GEVULD() != null))
            return new Conditie.Onopgelost(bron);
        if (op.vergelijkingsTeken() != null && op.expressie() != null)
            return new Conditie.Vergelijking(naarOperator(cmpOf(op.vergelijkingsTeken())),
                    lhs, bouwer.bouw(op.expressie()), periode, bron);
        if (op.LEEG() != null)   return new Conditie.Predicaat(Conditie.Predicaatsoort.LEEG, lhs, periode, bron);
        if (op.GEVULD() != null) return new Conditie.Predicaat(Conditie.Predicaatsoort.GEVULD, lhs, periode, bron);
        if (op.AAN_DE_ELFPROEF() != null) {
            ElfOperand e = elfOperand(op.NIET() != null, lhs);   // recover an absorbed vragende "niet"
            return new Conditie.Predicaat(Conditie.Predicaatsoort.ELFPROEF, e.operand(), e.negatie(), periode, bron);
        }
        if (op.NUMERIEK_MET_EXACT() != null) {
            ElfOperand e = elfOperand(op.NIET() != null, lhs);
            return new Conditie.Predicaat(Conditie.Predicaatsoort.NUMERIEK_MET_EXACT, e.operand(), e.negatie(),
                    aantalCijfers(op.NUMBER()), periode, bron);
        }
        // §8.1.8.3 "is niet een <k>": the explicit NIET EEN form (no AAN_DE_ELFPROEF / NUMERIEK_MET_EXACT keyword
        // follows) is the NEGATED zelfstandig kenmerkcheck — bind the positive EEN-form (is -> ZELFSTANDIG), then flip.
        if (op.NIET() != null && op.EEN() != null) {
            Conditie pos = kenmerkPredicaat(lhs, op.kaleNaam(), true, false, periode, bron);
            // Tabel 16 sanctions "is niet een <k>" only for a KENMERK; a rolcheck negates as "is/heeft geen <rol>"
            // (§8.1.7). Lower it the same way (inverse rolcheck), but surface the unsanctioned writing as a WARNING.
            if (pos instanceof Conditie.Rollidmaatschap || pos instanceof Conditie.VerreRollidmaatschap)
                model.diagnostics.add(new Diagnostic(Severity.WARNING,
                    "de schrijfwijze 'is niet een <rolnaam>' is voor een rolcheck niet voorzien; Tabel 16 kent alleen "
                    + "'is/heeft geen <rolnaam>' als ontkenning (§8.1.7): " + bron, 0));
            return negeer(pos, bron);
        }
        Conditie kenmerk = kenmerkPredicaat(lhs, op.kaleNaam(), op.EEN() != null,
                op.HEEFT() != null || op.HEBBEN() != null, periode, bron); // (is|heeft) een? kaleNaam: §8.1.8
        return kenmerk != null ? kenmerk : new Conditie.Onopgelost(bron); // else objecttype/navigation membership
    }

    /** §8.1.7/§8.1.8: "&lt;subject&gt; is/heeft (een)? &lt;naam&gt;" -> a {@link Conditie.Kenmerk} (the
     *  §8.1.8 kenmerkcheck) or a {@link Conditie.Rollidmaatschap} (the §8.1.7 rolcheck), when the operand
     *  denotes the rule subject (or a to-one navigation from it). WITHOUT "een" the name is the subject's
     *  kenmerk (own or navigated). WITH "een" the grammar conflates a kenmerknaam and a rolnaam, so the
     *  resolver disambiguates by lookup, kenmerk-first (Tabel 16): a ZELFSTANDIG kenmerk (§8.1.8.3 "is een
     *  &lt;k&gt;") binds first, otherwise a role the subject fills is the §8.1.7 rolcheck. A bijvoeglijk /
     *  bezittelijk kenmerk is NOT written with "een", so the EEN-form never binds it. A name that resolves to
     *  neither stays Onopgelost (an honest marker), never a silently-true gate. */
    private Conditie kenmerkPredicaat(Expr lhs, KaleNaamContext naamCtx, boolean metEen, boolean heeftWerkwoord,
                                      Conditie.GeheelPeriode periode, Bron bron) {
        Conditie c = kenmerkPredicaatKaal(lhs, naamCtx, metEen, heeftWerkwoord, bron);
        if (periode == null || c == null) return c;
        // §8.2 "is/heeft gedurende het gehele jaar een <kenmerk>" attaches the whole-period qualifier ONLY to a
        // §8.1.8 kenmerkcheck (its onderwerp is a tijdsafhankelijk kenmerk). Over any other membership form (a
        // §8.1.7 rolcheck) the qualifier has no defined meaning -> an honest marker, never silently dropped.
        return c instanceof Conditie.Kenmerk k
                ? new Conditie.Kenmerk(k.kenmerk(), k.navigatie(), k.negatie(), k.eigenaar(), periode, k.bron())
                : new Conditie.Onopgelost(bron);
    }

    /** Flip the negatie of a resolved kenmerk-/rolcheck (the §8.1.8.3 "is niet een &lt;k&gt;" negation). The positive
     *  binding has negatie=false; flipping it yields the "== false" membership. A form that did not structure into a
     *  kenmerk/rolcheck (null, or an Onopgelost) becomes an honest marker, never a silently-true gate. */
    private static Conditie negeer(Conditie c, Bron bron) {
        if (c instanceof Conditie.Kenmerk k)
            return new Conditie.Kenmerk(k.kenmerk(), k.navigatie(), !k.negatie(), k.eigenaar(), k.periode(), k.bron());
        if (c instanceof Conditie.Rollidmaatschap r)
            return new Conditie.Rollidmaatschap(r.rol(), r.anderTeFiller(), r.bron(), !r.negatie());
        if (c instanceof Conditie.VerreRollidmaatschap vr)
            return new Conditie.VerreRollidmaatschap(vr.filler(), vr.backref(), !vr.negatie(), vr.bron());
        return new Conditie.Onopgelost(bron);
    }

    /** §8.1.8 / Tabel 16: the kenmerkcheck NEGATION verb is keyed to the kenmerk SOORT — a bijvoeglijk and a
     *  zelfstandig kenmerk negate with "is"/"zijn" ("is niet &lt;k&gt;", "is geen &lt;k&gt;", "is niet een &lt;k&gt;"),
     *  a bezittelijk one with "heeft"/"hebben" ("heeft (geen) &lt;k&gt;"). The de-negation in {@link #bindKenmerk} is
     *  verb-AGNOSTIC (the "== false" lowering is identical either way), so a cross-soort writing ("heeft geen
     *  &lt;bijvoeglijk&gt;", "is geen &lt;bezittelijk&gt;") slips through with the right meaning but the wrong register.
     *  Surface it as a WARNING — a writing-form lint, never a wrong gate (the lowering is unchanged). Only a de-negated
     *  kenmerk (negatie=true) is checked; the positive form's verb-keying is the EEN-form split's job. */
    private void keurNegatieSoort(Objecttype houder, Conditie kc, boolean heeftWerkwoord) {
        if (houder == null || !(kc instanceof Conditie.Kenmerk k) || !k.negatie()) return;
        Objecttype.KenmerkSoort soort = houder.kenmerkSoort(k.kenmerk());
        if (soort == null) return;
        boolean bezit = soort == Objecttype.KenmerkSoort.BEZITTELIJK;
        if (bezit != heeftWerkwoord)
            model.diagnostics.add(new Diagnostic(Severity.WARNING,
                "het " + soort.name().toLowerCase() + " kenmerk '" + k.kenmerk() + "' wordt ontkend met het werkwoord '"
                + (heeftWerkwoord ? "heeft" : "is") + "', maar Tabel 16 vereist '" + (bezit ? "heeft" : "is")
                + "' bij dit soort kenmerk (§8.1.8): " + k.bron(), 0));
    }

    private Conditie kenmerkPredicaatKaal(Expr lhs, KaleNaamContext naamCtx, boolean metEen, boolean heeftWerkwoord, Bron bron) {
        if (naamCtx == null) return null;
        // §8.1.5 dagsoortcontrole: "<datumexpressie> is een <dagsoort>" (e.g. "de datum van vertrek … is een
        // kerstdag") tests the date against that dagsoort's §9.8 definitie. A dagsoort is neither a kenmerk nor a
        // role — it is keyed only by the operand being a datum and the name matching a declared dagsoort, so it is
        // resolved here BEFORE the kenmerk/rolcheck machinery (the §13.4.15.45 "(een) <dagsoort> is" form). §8.1.5 /
        // §5.15 / Tabel 57 accept ANY datum/tijd operand (Datum-in-dagen OR Datum-en-tijd); the §9.8 dagsoort is
        // day-granular, so a Datum-en-tijd contributes its date part (both back-ends take .toLocalDate()).
        if (metEen && !heeftWerkwoord && lhs != null) {
            String dag = dagsoortNaam(stripArtikel(src(naamCtx)));
            if (dag != null && lhs.type().isDatum())
                return new Conditie.Predicaat(Conditie.Predicaatsoort.DAGSOORT, lhs, dag, null, bron);
            // Tabel 57: the written name IS a declared dagsoort but the operand is NOT datum-typed — a
            // resolver ERROR (was: a silent fall-through into the kenmerk/rol machinery ending as an
            // Onopgelost marker). An Onbekend operand carries no type claim and keeps the fall-through.
            if (dag != null && !lhs.type().isOnbekend())
                return dagsoortOperandFout(lhs, naamCtx, bron);
        }
        // §8.1.5 + Tabel 16 "<datum> is geen <dagsoort>": the NEGATED dagsoortcontrole, kept on the §8.1.5
        // datum-value path — NEVER mis-folded into the §3.5 kenmerkcheck (a dagsoort is no kenmerk; §5.15-based
        // leegwaarde: a leeg/non-dagsoort datum IS "geen <dagsoort>" = WAAR). "geen" is a LOWER_ID (no GEEN token), so
        // where it lands depends on the operand shape: for a §6 date-arithmetic operand ("de startdatum plus 1 dag") it
        // stays on the operator's kaleNaam ("geen kerstdag"); for a plain AttribuutRef the greedy onderwerp absorbs it
        // as a TRAILING word of the operand bron (like "niet"), leaving the bare dagsoort as the kaleNaam.
        if (!heeftWerkwoord && lhs != null) {   // §8.1.5/§5.15/Tabel 57: any datum/tijd operand (date part taken)
            boolean datum = lhs.type().isDatum(), getypt = !lhs.type().isOnbekend();
            String naam0 = stripArtikel(src(naamCtx));
            // (a) "geen <dagsoort>" left on the kaleNaam — a date-arithmetic / non-AttribuutRef datum operand.
            if (naam0 != null && naam0.regionMatches(true, 0, "geen ", 0, 5)) {
                String dag = dagsoortNaam(stripArtikel(naam0.substring(5).trim()));
                if (dag != null && datum) return new Conditie.Predicaat(Conditie.Predicaatsoort.DAGSOORT, lhs, dag, true, 0, null, bron);
                if (dag != null && getypt) return dagsoortOperandFout(lhs, naamCtx, bron);   // Tabel 57 operand gate
            }
            // (b) "geen" absorbed as the operand's trailing word — a plain AttribuutRef (mirror absorbedNegator),
            //     guarded against an attribuut literally named "… geen". The kaleNaam is then the bare dagsoort.
            if (lhs instanceof Expr.AttribuutRef la && la.bron() != null
                    && la.bron().snippet().stripTrailing().toLowerCase().endsWith(" geen") && !la.attribuut().toLowerCase().endsWith("geen")) {
                String dag = dagsoortNaam(naam0);
                if (dag != null && datum) return new Conditie.Predicaat(Conditie.Predicaatsoort.DAGSOORT, la, dag, true, 0, null, bron);
                if (dag != null && getypt) return dagsoortOperandFout(la, naamCtx, bron);   // Tabel 57 operand gate
            }
        }
        Objecttype subject = bouwer.subject();
        if (subject == null) return null;
        String staart = stripArtikel(src(naamCtx));
        String kop = subjectKop(lhs, subject);
        // not the subject itself -> a navigated kenmerk ("zijn reis is duurzaam", or the EEN-form "zijn reis is
        // een belaste reis"). The EEN-form binds only a ZELFSTANDIG kenmerk of the navigated type (the soort guard,
        // §8.1.8.3); a navigated role membership stays out of scope (a marker).
        if (kop == null) return navigatieKenmerk(lhs, staart, heeftWerkwoord, metEen, bron);
        // The greedy onderwerp parse absorbs a multi-word kenmerk's leading words into the operand
        // ("de Meting bereikbaar per" + operator "trein"), so the kenmerk is that absorbed kop plus
        // the operator's kaleNaam; for "hij"/a bare subject the kop is empty and the name is the kaleNaam.
        String naam = kop.isEmpty() ? staart : kop + " " + staart;
        if (!metEen) {
            // §8.1.8 "is/heeft <kenmerk>" first (incl. the §8.1.8.1/.2/.3 "niet"/"geen" negations, which
            // kenmerkConditie de-negates to "== false"). When the name is not a kenmerk it is a §8.1.7 rolcheck —
            // kenmerk-first (the non-"een" form IS how a bijvoeglijk kenmerk is written, so kenmerkConditie already
            // claims those; only a name that is a ROLE but not a kenmerk newly binds). §13.4.15.44 makes the "een"
            // optional ("hij is deelnemer"); "heeft (geen) <rol>" is the §8.1.7 possessive, which rolLidmaatschap
            // resolves to the near-side test or — when the subject owns a to-many filler — the far-side exists-join.
            Conditie kc = kenmerkConditie(subject, naam, null, heeftWerkwoord, bron);
            if (kc != null) { keurNegatieSoort(subject, kc, heeftWerkwoord); return kc; }
            // a leftover "geen <name>" that is a role is the INVERSE rolcheck (the subject does not fill it);
            // otherwise the positive rolcheck.
            if (naam.regionMatches(true, 0, "geen ", 0, 5))
                return rolLidmaatschap(subject, naam.substring(5).trim(), bron, true);
            return rolLidmaatschap(subject, naam, bron);
        }
        // EEN-form: the grammar conflates a kenmerknaam and a rolnaam after "een", so disambiguate by lookup,
        // kenmerk-first (Tabel 16). The verb selects which kenmerk SOORT the EEN-form may bind: "is een <k>" is
        // §8.1.8.3 (a ZELFSTANDIG kenmerk — a bijvoeglijk kenmerk is written "is <k>" without "een"), while
        // "heeft een <k>" is §8.1.8.2 (a BEZITTELIJK kenmerk — the §7.1 verbatim "heeft een recht op
        // belastingvermindering"; the canonical "heeft het <k>" rides the non-EEN path above). Neither soort can
        // bind under the other verb. A name that is no such kenmerk falls through to the §8.1.7 rolcheck "is/heeft
        // een <rolnaam>" (incl. the far-side "heeft een <rol>" exists-join).
        Conditie k = heeftWerkwoord ? bezittelijkKenmerk(subject, naam, bron)
                                    : zelfstandigKenmerk(subject, naam, bron);
        return k != null ? k : rolLidmaatschap(subject, naam, bron);
    }

    /** The declared §3.12 dagsoort whose name matches {@code geschreven} (case-insensitive), or null. The
     *  written §8.1.5 control names the dagsoort in its singular ("is een kerstdag"), which is the form stored
     *  in {@code model.dagsoorten}; the §6 duration UNIT (a plural eenheidsafkorting "kerstdagen") is matched
     *  separately by the transpilers via the declared meervoud. */
    private String dagsoortNaam(String geschreven) {
        if (geschreven == null) return null;
        String g = geschreven.trim();
        for (String d : model.dagsoorten) if (d.equalsIgnoreCase(g)) return d;
        return null;
    }

    /** §8.1.8.3 "&lt;subject&gt; is een &lt;zelfstandig kenmerk&gt;" -> a {@link Conditie.Kenmerk} ("k == true"),
     *  but ONLY for a {@link Objecttype.KenmerkSoort#ZELFSTANDIG} kenmerk: a bijvoeglijk kenmerk ("is &lt;k&gt;")
     *  and a bezittelijk one ("heeft het &lt;k&gt;") are not written with "een", so the EEN-form must not bind
     *  them (that mis-binding was the reason the EEN-form kenmerkcheck was deferred). Positive only — the "is
     *  geen" negation lexes into the kaleNaam with no "een" and reaches the non-EEN path (carded). Null when no
     *  ZELFSTANDIG kenmerk of {@code subject} bears the name, so the caller falls back to the §8.1.7 rolcheck. */
    private Conditie zelfstandigKenmerk(Objecttype subject, String naam, Bron bron) {
        String g = KenmerkMatcher.vind(subject, naam);
        return g != null && subject.kenmerkSoort(g) == Objecttype.KenmerkSoort.ZELFSTANDIG
                ? new Conditie.Kenmerk(g, bron) : null;
    }

    /** §8.1.8.2 "&lt;subject&gt; heeft een &lt;bezittelijk kenmerk&gt;" -> a {@link Conditie.Kenmerk} ("k == true"),
     *  but ONLY for a {@link Objecttype.KenmerkSoort#BEZITTELIJK} kenmerk: the EEN-form heeft binds a possession of
     *  a kenmerk (the §7.1 "heeft een recht op belastingvermindering"), while a possession of a ROLE
     *  ("heeft een deelnemer") is no kenmerk and stays null for the caller's §8.1.7 far-side rolcheck. A
     *  bijvoeglijk/zelfstandig kenmerk uses "is", so the heeft EEN-form must not bind them. Null when no
     *  BEZITTELIJK kenmerk of {@code subject} bears the name. */
    private Conditie bezittelijkKenmerk(Objecttype subject, String naam, Bron bron) {
        String g = KenmerkMatcher.vind(subject, naam);
        return g != null && subject.kenmerkSoort(g) == Objecttype.KenmerkSoort.BEZITTELIJK
                ? new Conditie.Kenmerk(g, bron) : null;
    }

    /** §8.1.7 rolcheck: "&lt;subject&gt; is (een) &lt;rolnaam&gt;" — true when the subject fills that role.
     *  Resolves to a {@link Conditie.Rollidmaatschap} carrying the OTHER end of the feittype (so the
     *  transpiler can null-test the subject's reference to it). Null when no role the subject fills bears
     *  that name (-> Onopgelost marker, never a wrong gate). */
    private Conditie rolLidmaatschap(Objecttype subject, String naam, Bron bron) {
        return rolLidmaatschap(subject, naam, bron, false);
    }
    /** As above, with {@code negatie} for the "is geen &lt;rol&gt;" form (the subject does NOT fill it). When the
     *  subject does not FILL the role (rolSiblingType null) but the role reaches a TO-MANY filler from it, this is
     *  the §8.1.7 FAR-side "&lt;subject&gt; heeft een &lt;rol&gt;": resolve to a {@link Conditie.VerreRollidmaatschap}
     *  carrying that filler and its back-reference field, which the transpiler lowers to an exists-join. */
    private Conditie rolLidmaatschap(Objecttype subject, String naam, Bron bron, boolean negatie) {
        Objecttype ander = model.rolSiblingType(subject, naam);
        if (ander != null) return new Conditie.Rollidmaatschap(naam, ander, bron, negatie);
        Objecttype filler = model.rolDoeltypeNaarSubject(subject, naam);
        if (filler != null && model.naarVeelRol(subject, filler, naam)) {
            // The backref is the subject-side role of the SAME feittype as the named role — found by the written
            // role name (verbindendeRolViaElementnaam), which disambiguates a filler that references the subject
            // through MORE THAN ONE feittype (e.g. a Persoon that fills both "deelnemer" and "bonusdeelnemer" of
            // a Ploeg): the by-pair verbindendeRol is then ambiguous and the far-side would wrongly stay a marker.
            // Fall back to the by-pair lookup for the single-feittype case (both give the same role there).
            String backref = model.verbindendeRolViaElementnaam(subject, naam);
            if (backref == null) backref = model.verbindendeRol(filler, subject);
            if (backref != null) return new Conditie.VerreRollidmaatschap(filler, backref, negatie, bron);
        }
        return null;
    }

    /** Resolve {@code kenmerk} on {@code type} into a {@link Conditie.Kenmerk}, honouring a leading "niet"
     *  or "geen" as NEGATION — but each only after the WHOLE name fails, so a declared "niet-…" kenmerk
     *  still binds positively. {@code navigatie} is the to-one navigation role (null for the subject's own).
     *  {@code heeftWerkwoord} is true when the predicate verb is hebben (§8.1.8: see the "geen" branch).
     *  Null when neither the whole name nor a de-negated form is a declared kenmerk of {@code type}. */
    private static Conditie kenmerkConditie(Objecttype type, String kenmerk, String navigatie, boolean heeftWerkwoord, Bron bron) {
        // Match the written name VERBATIM first. A bijvoeglijk kenmerk may be declared WITH a leading article
        // as part of its name ("is de minimis kenmerk" -> stored "de minimis"): its grammar rule has no
        // separate bepaaldLidwoord? and naamWoordLower admits de/het, so the article is part of the stored
        // name — the false-invariant the first cut tripped on (a blind strip dropped it to a wrong/no match).
        Conditie c = bindKenmerk(type, kenmerk, navigatie, heeftWerkwoord, bron);
        if (c != null) return c;
        // Only if the verbatim name binds NOTHING, retry with a leading de/het/een stripped — recovering the
        // bezittelijk/noch-noch case where the article is the kenmerk's OWN (stored article-stripped) and the
        // greedy onderwerp parse left it on the reconstructed name ("het recht op duurzaamheidskorting").
        // Verbatim-first guarantees a kenmerk whose name really begins with an article binds itself, never a
        // shorter sibling.
        String gestript = stripArtikel(kenmerk);
        return gestript.equals(kenmerk) ? null : bindKenmerk(type, gestript, navigatie, heeftWerkwoord, bron);
    }

    /** Bind one candidate name to a declared kenmerk of {@code type}, honouring a leading "niet" or "geen"
     *  as NEGATION — but each only after the WHOLE name fails, so a kenmerk declared starting with niet/geen
     *  still binds positively (never silently inverted). Null when nothing binds. */
    private static Conditie bindKenmerk(Objecttype type, String naam, String navigatie, boolean heeftWerkwoord, Bron bron) {
        String g = KenmerkMatcher.vind(type, naam);
        if (g != null) return new Conditie.Kenmerk(g, navigatie, false, bron);
        // §8.1.8.1 bijvoeglijk negation "is niet <k>" -> "== false" (verb-agnostic).
        if (naam.regionMatches(true, 0, "niet ", 0, 5)) {
            g = KenmerkMatcher.vind(type, naam.substring(5).trim());
            if (g != null) return new Conditie.Kenmerk(g, navigatie, true, bron);
        }
        // Negation with "geen" -> "== false": the §8.1.8.2 bezittelijk form "heeft geen <k>" AND the §8.1.8.3
        // noch-noch form "is geen <k>" (both lower to the same boolean negation, so the verb need not gate it;
        // a soort distinction governs only positive writing-form validation, not the negated lowering). "geen"
        // carries no token (only GEEN_VAN_DE), so it lexes as LOWER_ID and rides inside the kaleNaam into this
        // reconstructed name. Verbatim-first above means a kenmerk literally named "geen …" still binds positively.
        if (naam.regionMatches(true, 0, "geen ", 0, 5)) {
            g = KenmerkMatcher.vind(type, naam.substring(5).trim());
            if (g != null) return new Conditie.Kenmerk(g, navigatie, true, bron);
        }
        return null;
    }

    /** §8.1.8 navigated: "de &lt;rol&gt; van X is &lt;kenmerk&gt;" — the kenmerk is on the object the rol
     *  reaches, not the subject. Lowered when the rol is a TO-ONE navigation from the subject (so the
     *  property path {@code rol.kenmerk} reads one object); a TO-MANY navigation needs an exists/forall and
     *  stays a marker, as does an unresolvable rol or a kenmerk the navigated type does not declare. The rol
     *  is normalised to its declared name (a plural "de deelnemers" is the same role as "de deelnemer"), so
     *  the to-many test and the emitted reference field are the canonical one. With {@code metEen} the verb selects
     *  the soort, exactly as the subject's own EEN-form ({@link #kenmerkPredicaat}): "is een &lt;k&gt;" binds a
     *  §8.1.8.3 ZELFSTANDIG kenmerk of the navigated type, "heeft een &lt;k&gt;" a §8.1.8.2 BEZITTELIJK one — neither
     *  binds under the other verb, and a navigated role membership via "een" stays out of scope (null). */
    private Conditie navigatieKenmerk(Expr lhs, String staart, boolean heeftWerkwoord, boolean metEen, Bron bron) {
        if (!(lhs instanceof Expr.AttribuutRef a) || a.objecttype() == null) return null;
        Objecttype subject = a.objecttype();                                       // the onderwerp the navigation is from
        Objecttype doel = model.rolDoeltypeNaarSubject(subject, a.attribuut());
        if (doel == null) return null;
        String rol = model.verbindendeRolViaNaam(subject, doel, a.attribuut());     // canonical role / reference field
        if (rol == null) rol = a.attribuut();
        if (model.naarVeelRol(subject, doel, rol)) return null;                     // to-many -> marker (needs exists/forall)
        // The greedy onderwerp parse can absorb a multi-word kenmerk's leading words — or a "niet" — into
        // the operand AFTER the navigated onderwerp. Reconstruct the full kenmerk = that absorbed tail + the
        // operator's kaleNaam, so a multi-word kenmerk binds (and a §8.1.8.1 "niet <kenmerk>" de-negates in
        // kenmerkConditie). navigatieStaart recovers the absorbed tail past the role/type anchor.
        String tail = navigatieStaart(a);
        String kenmerk = tail.isEmpty() ? staart : tail + " " + staart;
        if (metEen) {
            // §8.1.8.2/.3 navigated EEN-form: the verb selects the soort (is -> ZELFSTANDIG, heeft -> BEZITTELIJK),
            // the mirror of the subject's own EEN-form; neither soort binds under the other verb, and a navigated
            // role membership via "een" stays out of scope -> all an honest marker, never a wrong gate.
            String g = KenmerkMatcher.vind(doel, kenmerk);
            Objecttype.KenmerkSoort vereist = heeftWerkwoord
                    ? Objecttype.KenmerkSoort.BEZITTELIJK : Objecttype.KenmerkSoort.ZELFSTANDIG;
            return g != null && doel.kenmerkSoort(g) == vereist
                    ? new Conditie.Kenmerk(g, rol, false, bron) : null;
        }
        Conditie kc = kenmerkConditie(doel, kenmerk, rol, heeftWerkwoord, bron);
        keurNegatieSoort(doel, kc, heeftWerkwoord);
        return kc;
    }

    /** The words the greedy operand parse absorbed AFTER the navigated "de &lt;rol&gt; van &lt;onderwerp&gt;"
     *  (the leading words of a multi-word kenmerk, or a "niet") — empty for a clean navigation. Found as the
     *  operand text following the onderwerp's object-type name. */
    private static String navigatieStaart(Expr.AttribuutRef a) {
        String b = a.bron() == null ? "" : a.bron().snippet();
        // Anchor on the onderwerp TYPE name ("de <rol> van <Type> <kenmerkwoorden>"); for a POSSESSIVE
        // ("zijn <rol> <kenmerkwoorden>") there is no type name, so fall back to the ROLE name. Take the text
        // after the anchor's LAST whole-word occurrence (so a kenmerk word merely containing it does not
        // mis-split). The role fallback recovers a "niet"/"geen" the greedy possessive onderwerp absorbed
        // ("zijn reis niet" -> "niet"), so a navigated §8.1.8 negation de-negates instead of markering.
        for (String anker : new String[]{a.objecttype() != null ? a.objecttype().naam : null, a.attribuut()}) {
            if (anker == null || anker.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)\\b" + java.util.regex.Pattern.quote(anker) + "\\b").matcher(b);
            int eind = -1;
            while (m.find()) eind = m.end();
            if (eind >= 0) return b.substring(eind).trim();
        }
        return "";
    }

    /** When the condition operand denotes the rule subject, the part of a multi-word kenmerk the
     *  greedy onderwerp parse absorbed into the operand (empty for the bezielde "hij" or a bare
     *  subject reference); null when the operand is not the subject. A navigation ("de partner van
     *  …") names a different filler, so it is excluded — its kenmerk would be on another object. */
    private String subjectKop(Expr lhs, Objecttype subject) {
        // In a feitcreatie doel re-resolution (doelRol != null) the bezielde "hij"/bare reference names the
        // ANKER, not the doel (§13.4.7: the doel is written with a definite article, "de <rolC>"), so it must
        // NOT latch onto the doel — reject it. Outside that mode "hij" denotes the single subject as before.
        if (lhs instanceof Expr.Constante c)
            return (doelRol == null && c.naam().equalsIgnoreCase("hij")) ? "" : null;
        String b = lhs.bron() == null ? "" : stripArtikel(lhs.bron().snippet());
        if (b.equalsIgnoreCase(subject.naam)) return "";                              // "de Meting"
        if (b.regionMatches(true, 0, subject.naam + " ", 0, subject.naam.length() + 1)) // "de Meting vaarbaar bij"
            return b.substring(subject.naam.length()).trim();
        // The doel is named by the ROLE it fills ("de deelnemer"), which need not equal its type name
        // ("Persoon"); accept that role too, with the same multi-word-kenmerk kop reconstruction.
        if (doelRol != null) {
            if (b.equalsIgnoreCase(doelRol)) return "";
            if (b.regionMatches(true, 0, doelRol + " ", 0, doelRol.length() + 1))
                return b.substring(doelRol.length()).trim();
        }
        return null;
    }

    private Conditie.Kwantor kwantorVan(VoorwaardeKwantificatieContext k) {
        if (k == null) return Conditie.Kwantor.DE;
        if (k.ALLE() != null)        return Conditie.Kwantor.ALLE;
        if (k.GEEN_VAN_DE() != null) return Conditie.Kwantor.GEEN;
        if (k.TEN_MINSTE() != null)  return Conditie.Kwantor.TEN_MINSTE;
        if (k.TEN_HOOGSTE() != null) return Conditie.Kwantor.TEN_HOOGSTE;
        if (k.PRECIES() != null)     return Conditie.Kwantor.PRECIES;
        return Conditie.Kwantor.DE;
    }

    static Conditie.Operator naarOperator(Typering.CmpOp op) {
        if (op == null) return Conditie.Operator.GELIJK;
        return switch (op) {
            case KLEINER -> Conditie.Operator.KLEINER;
            case KLEINER_OF_GELIJK -> Conditie.Operator.KLEINER_OF_GELIJK;
            case GROTER -> Conditie.Operator.GROTER;
            case GROTER_OF_GELIJK -> Conditie.Operator.GROTER_OF_GELIJK;
            case GELIJK -> Conditie.Operator.GELIJK;
            case ONGELIJK -> Conditie.Operator.ONGELIJK;
            case EERDER -> Conditie.Operator.EERDER;
            case EERDER_OF_GELIJK -> Conditie.Operator.EERDER_OF_GELIJK;
            case LATER -> Conditie.Operator.LATER;
            case LATER_OF_GELIJK -> Conditie.Operator.LATER_OF_GELIJK;
        };
    }

    private static String src(ParserRuleContext c) { return DefinitionResolver.src(c); }
    private static io.github.yhavinga.regelspraak.ir.Bron bron(ParserRuleContext c) { return DefinitionResolver.bron(c); }

    /** The {@code // Bron: <wetsartikel>} statuutannotaties written directly above {@code ctx} — the line
     *  comments on the HIDDEN channel between the previous on-channel token and {@code ctx}'s start, each with
     *  the {@code //} and the {@code Bron:} tag stripped (case-insensitive), in source order. Empty when there
     *  is no stream, no such comment, or {@code ctx} does not belong to the main stream (the §12 beslistabel
     *  desugar re-parses each synthetic regel in its own stream — a token index into a foreign stream must not
     *  be read, and a desugared regel carries no annotations anyway). */
    private List<String> bronAnnotaties(ParserRuleContext ctx) {
        if (tokens == null || ctx == null || ctx.getStart() == null) return List.of();
        int idx = ctx.getStart().getTokenIndex();
        if (idx < 0 || idx >= tokens.size() || tokens.get(idx) != ctx.getStart()) return List.of();
        List<Token> hidden = tokens.getHiddenTokensToLeft(idx);
        if (hidden == null) return List.of();
        List<String> uit = new ArrayList<>();
        for (Token t : hidden) {
            String c = t.getText().replaceFirst("^//+\\s*", "");   // strip the leading // and spaces
            if (c.regionMatches(true, 0, "Bron:", 0, 5)) uit.add(c.substring(5).trim());
        }
        return uit;
    }

    // ---- §5 condition type-checking (comparisons + predicates) ----
    private void voorwaarden(ParseTree t) {
        if (t == null) return;
        if (t instanceof ToplevelElementaireVoorwaardeContext c) checkTopVoorwaarde(c);
        else if (t instanceof ElementaireVoorwaardeContext c) checkElemVoorwaarde(c);
        else if (t instanceof EnkelvoudigeConsistentieRegelContext c) checkConsistentieCriterium(c);
        for (int i = 0; i < t.getChildCount(); i++) voorwaarden(t.getChild(i));
    }

    /** A §13.4.8 single criterion ("&lt;expressie&gt; moet &lt;operator&gt;") is the same
     *  predicate over the same onderwerpexpressie as the matching §5 voorwaarde (§8.3.1),
     *  so its operands get the identical typeringen check — the lifted criterion now
     *  transpiles, which would otherwise let an ill-typed comparison through silently. */
    private void checkConsistentieCriterium(EnkelvoudigeConsistentieRegelContext c) {
        if (c.expressie() == null || c.toplevelOperator() == null) return;
        Datatype lhs = infer(c.expressie());
        ToplevelOperatorContext op = c.toplevelOperator();
        int line = c.getStart().getLine();
        if (op.vergelijkingsTekenGesplitst() != null && op.expressie() != null)
            meldVergelijk(cmpOf(op.vergelijkingsTekenGesplitst()), lhs, infer(op.expressie()),
                    bareGetalLiteraal(c.expressie()), bareGetalLiteraal(op.expressie()), line,
                    enumLabelVan(c.expressie()), enumLabelVan(op.expressie()));
        else if (op.AAN_DE_ELFPROEF() != null)
            meldPredicaat(Typering.UnairePred.ELFPROEF, lhs, line);
        else if (op.NUMERIEK_IS_MET_EXACT() != null || op.NUMERIEK_ZIJN_MET_EXACT() != null)
            meldPredicaat(Typering.UnairePred.NUMERIEK_MET_EXACT, lhs, line);
    }

    // §8.3.1/§13.4.15: a TOPLEVEL voorwaarde (after 'indien'/'dat'/'gedurende de tijd dat') takes ONLY the
    // verb-trailing (vragende) toplevelOperator — the grammar no longer admits the verb-leading register here
    // (that is reserved for samengestelde bullets, checkElemVoorwaarde, which keeps both families). So only the
    // toplevelOperator needs checking; an ill-typed predicate is still caught by checkToplevelOp.
    private void checkTopVoorwaarde(ToplevelElementaireVoorwaardeContext c) {
        if (c.expressie() == null) return;
        Datatype lhs = infer(c.expressie());
        int line = c.getStart().getLine();
        checkToplevelOp(lhs, c.toplevelOperator(), line, bareGetalLiteraal(c.expressie()), enumLabelVan(c.expressie()));
    }

    private void checkElemVoorwaarde(ElementaireVoorwaardeContext c) {
        if (c.expressie() == null) return;
        Datatype lhs = infer(c.expressie());
        int line = c.getStart().getLine();
        boolean lhsLit = bareGetalLiteraal(c.expressie());
        String lhsEnum = enumLabelVan(c.expressie());
        checkNonToplevelOp(lhs, c.nonToplevelOperator(), line, lhsLit, lhsEnum);
        checkToplevelOp(lhs, c.toplevelOperator(), line, lhsLit, lhsEnum);
    }

    /** Type-check a verb-split / trailing operator (vergelijking, elfproef, numeriek-met-exact). */
    private void checkToplevelOp(Datatype lhs, ToplevelOperatorContext op, int line, boolean lhsLit, String lhsEnum) {
        if (op == null) return;
        if (op.vergelijkingsTekenGesplitst() != null && op.expressie() != null)
            meldVergelijk(cmpOf(op.vergelijkingsTekenGesplitst()), lhs, infer(op.expressie()), lhsLit, bareGetalLiteraal(op.expressie()), line,
                    lhsEnum, enumLabelVan(op.expressie()));
        else if (op.AAN_DE_ELFPROEF() != null)
            meldPredicaat(Typering.UnairePred.ELFPROEF, lhs, line);
        else if (op.NUMERIEK_IS_MET_EXACT() != null || op.NUMERIEK_ZIJN_MET_EXACT() != null)
            meldPredicaat(Typering.UnairePred.NUMERIEK_MET_EXACT, lhs, line);
    }

    /** Type-check a verb-leading operator (vergelijking, elfproef, numeriek-met-exact). */
    private void checkNonToplevelOp(Datatype lhs, NonToplevelOperatorContext op, int line, boolean lhsLit, String lhsEnum) {
        if (op == null) return;
        if (op.vergelijkingsTeken() != null && op.expressie() != null)
            meldVergelijk(cmpOf(op.vergelijkingsTeken()), lhs, infer(op.expressie()), lhsLit, bareGetalLiteraal(op.expressie()), line,
                    lhsEnum, enumLabelVan(op.expressie()));
        else if (op.AAN_DE_ELFPROEF() != null)
            meldPredicaat(Typering.UnairePred.ELFPROEF, lhs, line);
        else if (op.NUMERIEK_MET_EXACT() != null)
            meldPredicaat(Typering.UnairePred.NUMERIEK_MET_EXACT, lhs, line);
    }

    private void meldVergelijk(Typering.CmpOp op, Datatype l, Datatype r, boolean lLit, boolean rLit, int line,
                               String lEnum, String rEnum) {
        if (op == null) return;
        // §13.3.4.2 membership: an enum LITERAL on one side is checked against the OTHER side's declared
        // domain (the literal itself is typed Onbekend — its domain IS this context). GELIJK/ONGELIJK only:
        // Tabel 36/43 rij 7 is the enum comparison row; the ordering tables carry no Enumeratie row.
        if (op == Typering.CmpOp.GELIJK || op == Typering.CmpOp.ONGELIJK) {
            keurEnumLabel(l, rEnum, line);
            keurEnumLabel(r, lEnum, line);
        }
        // §13.3.5 / typeringen: a DIMENSIONLESS numeric literal adopts the other operand's unit in a comparison
        // (a shape the spec's own examples use). Promote the literal so the unit rule does not reject a legal
        // literal-vs-unit comparison ("km > 0"); a unitless ATTRIBUTE (not a literal) still triggers the rule.
        // Restricted to a NUMERIEK sibling: a Percentage operand keeps the §5.5 Tabel 41 Numeriek/Percentage
        // family separation (a literal would otherwise be mis-tagged Numeriek('%'), a datatype the spec never forms).
        if (rLit && l.isNumeriek() && l.eenheidOf() != null) r = metEenheid(r, l.eenheidOf());
        else if (lLit && r.isNumeriek() && r.eenheidOf() != null) l = metEenheid(l, r.eenheidOf());
        var res = Typering.vergelijk(op, l, r);   // Onbekend operands -> ok, so no false positive
        if (!res.ok()) model.diagnostics.add(new Diagnostic(Severity.ERROR, "voorwaarde: " + res.fout(), line));
    }

    /** A bare numeric literal ("0", "18", "3,5") with NO trailing eenheid — getText collapses whitespace, so a
     *  unit-qualified literal ("18 jr") becomes "18jr" and is correctly excluded. Side-effect-free (no re-bouw). */
    private static boolean bareGetalLiteraal(ExpressieContext e) {
        return e != null && e.getText().matches("-?\\d+([.,]\\d+)?");
    }

    /** Re-state a numeriek datatype with {@code eenheid} (for promoting a dimensionless literal to a sibling's unit). */
    private static Datatype metEenheid(Datatype t, io.github.yhavinga.regelspraak.model.Eenheid eenheid) {
        return t instanceof Datatype.Numeriek n ? new Datatype.Numeriek(n.geheel(), n.decimalen(), n.teken(), eenheid) : t;
    }
    private void meldPredicaat(Typering.UnairePred p, Datatype operand, int line) {
        var res = Typering.predicaat(p, operand);
        if (!res.ok()) model.diagnostics.add(new Diagnostic(Severity.ERROR, "voorwaarde: " + res.fout(), line));
    }

    /** Tabel 57 (§5.15): the written name resolved to a DECLARED dagsoort but the operand is not datum-typed —
     *  the Typering DAGSOORT ERROR (this is its only live call site on the condition path), and the conditie
     *  stays an Onopgelost marker so neither back-end builds a dagsoort check over a non-date. */
    private Conditie dagsoortOperandFout(Expr lhs, KaleNaamContext naamCtx, Bron bron) {
        meldPredicaat(Typering.UnairePred.DAGSOORT, lhs.type(), naamCtx.getStart().getLine());
        return new Conditie.Onopgelost(bron);
    }

    static Typering.CmpOp cmpOf(VergelijkingsTekenContext c) {
        return cmpOf(c.ONGELIJK() != null, c.GROTER() != null, c.KLEINER() != null,
                     c.LATER() != null, c.EERDER() != null, c.GELIJK() != null, c.OF() != null);
    }
    static Typering.CmpOp cmpOf(VergelijkingsTekenGesplitstContext c) {
        return cmpOf(c.ONGELIJK() != null, c.GROTER() != null, c.KLEINER() != null,
                     c.LATER() != null, c.EERDER() != null, c.GELIJK() != null, c.OF() != null);
    }
    static Typering.CmpOp cmpOf(boolean ong, boolean gr, boolean kl, boolean la, boolean ee, boolean ge, boolean of) {
        if (ong) return Typering.CmpOp.ONGELIJK;
        if (gr)  return of ? Typering.CmpOp.GROTER_OF_GELIJK  : Typering.CmpOp.GROTER;
        if (kl)  return of ? Typering.CmpOp.KLEINER_OF_GELIJK : Typering.CmpOp.KLEINER;
        if (la)  return of ? Typering.CmpOp.LATER_OF_GELIJK   : Typering.CmpOp.LATER;
        if (ee)  return of ? Typering.CmpOp.EERDER_OF_GELIJK  : Typering.CmpOp.EERDER;
        if (ge)  return Typering.CmpOp.GELIJK;
        return null;
    }

    // ---- expression datatype, via the resolved IR ----
    // The condition checks need an operand's datatype; that is now the type the
    // ExpressieBouwer computes for the resolved Expr, so inference and IR can never
    // diverge — they are the same code path.
    private Datatype infer(ExpressieContext e) { return bouwer.bouw(e).type(); }
}
