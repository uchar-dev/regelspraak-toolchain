package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;

import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE CONFLUENCE FUZZER (backlog fuzz-harness). Three firing bugs — accumulate-reverse on a mutated source, a
 * non-reactive navigated read, and un-stratified negation — were each caught ad hoc downstream. All three are the
 * SAME class: "the fixpoint depends on the agenda order". This property-harness attacks that class in bulk.
 *
 * <p>Per FIXED seed (1..N) a small model is generated as REAL RegelSpraak SOURCE TEXT: 2-3 objecttypen with
 * Numeriek/Bedrag/Datum attributes + kenmerken, 1-2 feittypen, and a rule chain drawn from a construction menu
 * (gelijkstelling arithmetic incl. rounding; kenmerktoekenning with conditions incl. negation and navigated reads;
 * som/min/max aggregation over roles; datum arithmetic). The source goes through the REAL parser/resolver (no IR
 * shortcuts), the {@link FactModelGenerator} emits + in-memory-compiles the fact beans (the {@link TokaVuurTest}
 * loop), and BOTH back-ends fire over reflectively-seeded facts.
 *
 * <p>The three properties, over the fired end-state (every fact's every scalar read-back, canonicalised):
 * <ul>
 *   <li><b>P1</b> DRL-in-Drools ≡ exec-model on all end values.</li>
 *   <li><b>P2</b> confluence — shuffling the RULE order in the source (3 permutations) and the fact insertion
 *       order must reach the SAME fixpoint (per back-end).</li>
 *   <li><b>P3</b> determinism — firing the same base twice reaches the same end-state.</li>
 * </ul>
 * ANY violation minimises to a reproducer (the .rgs source + the seeded facts, both PRINTED) and fails naming it.
 *
 * <p>Generated models are kept CONFLUENT-BY-CONSTRUCTION so any non-confluence is an ENGINE defect, not a model one:
 * single-writer targets (no two rules write the same attribute) and an acyclic dependency graph (rules read only pure
 * INPUT attributes — index-0 numerics / kenmerk producers — never each other's outputs, so a unique fixpoint exists).
 *
 * <p>FIXED (was a documented gap, now CLOSED): the DRL back-end was non-confluent under SOURCE RULE ORDER for a
 * negated-kenmerk read ("X is <k2> indien X niet <k1> is") because {@code DrlTranspiler} emitted no §9.2
 * negation-stratification salience, whereas {@code ExecModelTranspiler.stratificatieSalience} assigned SALIENCE −1 so
 * producers of <k1> fire first. {@code DrlTranspiler} now emits the SAME salience −1 on a negation-consumer, so the
 * two back-ends stratify identically. This harness therefore treats EVERY P2-DRL non-confluence as a HARD failure
 * (there is no known-hit bucket any more); the negated-kenmerk shape is exercised by seeds like 7/10/27 and must now
 * stay confluent. A NEW shape (exec-side non-confluence, a DRL≡exec divergence, non-determinism, a fire-cap loop)
 * fails hard as well.
 */
class ConfluenceFuzzTest {

    /** Seeds 1..SEEDS. The task floor is ≥200. */
    private static final int SEEDS = 260;
    /** The fire cap (mirrors the engine's 10_000): a regressed rule-loop hits it and that is a FAILURE. */
    private static final int FIRE_CAP = 10_000;
    /** The §4.2 rekendatum global every generated program registers (datum-arithmetiek reads it). */
    private static final LocalDate REKENDATUM = LocalDate.of(2024, 6, 1);

    // ================================================================================================
    //  THE GENERATED MODEL (a plain data description the RegelSpraak renderer and the fact seeder share)
    // ================================================================================================

    private enum AttrType { GETAL, BEDRAG, DATUM }

    /** A generated attribute. {@code jrEenheid} marks a Numeriek attribute declared "met eenheid jr" — the ONLY
     *  attributes a datum-arithmetiek (…in hele jaren) result may be assigned to, since that result carries the jr
     *  unit and the typeringen matrix rejects a unitless target (§3.17 / §Tabel eenheden). */
    private record Attr(String naam, AttrType type, boolean jrEenheid) {
        Attr(String naam, AttrType type) { this(naam, type, false); }
    }

    /** A generated objecttype: a bag of attributes + boolean kenmerken. A {@code producent} set records which
     *  attribute/kenmerk names already have a rule writing them, enforcing single-writer discipline (two rules
     *  assigning the same target contradict and defeat confluence-as-a-property). */
    private static final class Ot {
        final String naam;                          // logical name, e.g. "de Alfa"
        final String pascal;                        // bean class, e.g. "Alfa"
        final List<Attr> attrs = new ArrayList<>();
        final List<String> kenmerken = new ArrayList<>();
        final java.util.Set<String> producent = new java.util.HashSet<>();
        Ot(String naam, String pascal) { this.naam = naam; this.pascal = pascal; }
        /** Reserve {@code doel} as this construct's single writer; false if something already writes it. */
        boolean claim(String doel) { return producent.add(doel); }
    }

    /** A generated feittype: houder (many side) references doelwit (one side) through role {@code rol}. */
    private record Feit(String naam, Ot houder, Ot doelwit, String rol) {}

    /** One generated rule. {@code render()} is its RegelSpraak text; the rules are shuffled for P2. */
    private interface Regel { String render(); String naam(); }

    private static final class Model {
        final List<Ot> ots = new ArrayList<>();
        final List<Feit> feiten = new ArrayList<>();
        final List<Regel> regels = new ArrayList<>();
        boolean gebruiktRekendatum;
        int regelTeller;                            // synthetic keyword-free rule-name counter (R1, R2, …)
    }

    // ================================================================================================
    //  THE GENERATOR
    // ================================================================================================

    private static final String[] OT_NAMEN = {"Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"};
    // Vocab is deliberately keyword-free: 'aantal'/'waarde'/'peildatum'/'geldig' are RegelSpraak lexer keywords and
    // would mis-tokenise as attribute/kenmerk names, so they are excluded.
    private static final String[] GETAL_NAMEN = {"grondslag", "invoer", "basis", "toeslag", "hoeveelheid", "score"};
    private static final String[] BEDRAG_NAMEN = {"prijs", "bedrag", "korting", "tarief", "premie", "saldo"};
    private static final String[] DATUM_NAMEN = {"begindatum", "einddatum", "vervaldatum", "ingangsdatum"};
    private static final String[] KENMERK_NAMEN = {"actief", "belast", "vrijgesteld", "gemarkeerd", "bevoorrecht", "erkend"};

    private static Model genereer(Random rnd) {
        Model m = new Model();
        int aantalOts = 2 + rnd.nextInt(2);                       // 2-3 objecttypen
        List<String> otPool = new ArrayList<>(List.of(OT_NAMEN));
        Collections.shuffle(otPool, rnd);
        for (int i = 0; i < aantalOts; i++) {
            String p = otPool.get(i);
            Ot ot = new Ot("de " + p, p);
            m.ots.add(ot);
            // 1-3 attributes of mixed type, unique names.
            int nAttr = 1 + rnd.nextInt(3);
            List<String> getalPool = shuffled(GETAL_NAMEN, rnd), bedragPool = shuffled(BEDRAG_NAMEN, rnd),
                    datumPool = shuffled(DATUM_NAMEN, rnd);
            int gi = 0, bi = 0, di = 0;
            for (int a = 0; a < nAttr; a++) {
                switch (rnd.nextInt(3)) {
                    case 0 -> ot.attrs.add(new Attr(getalPool.get(gi++), AttrType.GETAL));
                    case 1 -> ot.attrs.add(new Attr(bedragPool.get(bi++), AttrType.BEDRAG));
                    default -> ot.attrs.add(new Attr(datumPool.get(di++), AttrType.DATUM));
                }
            }
            // A dedicated "met eenheid jr" numeric attribute — the unit-correct sink for a datum-arithmetiek result.
            // Only added when the ot also has a datum to measure from; kept OUT of the ordinary numeric pool so no
            // unitless arithmetic ever targets it (which would trip the mixed-unit typeringen check).
            if (gi < getalPool.size() && ot.attrs.stream().anyMatch(x -> x.type() == AttrType.DATUM))
                ot.attrs.add(new Attr(getalPool.get(gi++), AttrType.GETAL, true));
            // 0-2 kenmerken.
            int nKen = rnd.nextInt(3);
            List<String> kenPool = shuffled(KENMERK_NAMEN, rnd);
            for (int k = 0; k < nKen; k++) ot.kenmerken.add(kenPool.get(k));
        }

        // 1-2 feittypen: houder (many) -> doelwit (one) over an unambiguous to-one role. Distinct ot pairs so
        // FactModelGenerator derives a scalar reference field on the houder (verbindendeRol / naarVeelRol).
        int nFeit = m.ots.size() >= 2 ? 1 + rnd.nextInt(Math.min(2, m.ots.size() - 1)) : 0;
        List<int[]> paren = new ArrayList<>();
        for (int i = 0; i < m.ots.size(); i++)
            for (int j = 0; j < m.ots.size(); j++)
                if (i != j) paren.add(new int[]{i, j});
        Collections.shuffle(paren, rnd);
        java.util.Set<Long> gebruiktPaar = new java.util.HashSet<>();
        java.util.Set<String> rolNamen = new java.util.HashSet<>();
        for (int[] paar : paren) {
            if (m.feiten.size() >= nFeit) break;
            Ot houder = m.ots.get(paar[0]), doelwit = m.ots.get(paar[1]);
            // avoid a second feittype between the SAME ordered pair (would make verbindendeRol ambiguous -> no ref field)
            long key = (long) paar[0] << 20 | paar[1];
            long rev = (long) paar[1] << 20 | paar[0];
            if (!gebruiktPaar.add(key) || gebruiktPaar.contains(rev)) continue;
            gebruiktPaar.add(rev);
            String rol = "de " + doelwit.pascal.toLowerCase() + "referentie";
            String rolWoord = doelwit.pascal.toLowerCase() + "referentie";
            if (!rolNamen.add(rolWoord)) continue;
            m.feiten.add(new Feit("verband " + houder.pascal + " " + doelwit.pascal, houder, doelwit, rolWoord));
        }

        // ---- the rule chain from the construction menu ----
        for (Ot ot : m.ots) genereerRegelsVoor(m, ot, rnd);
        return m;
    }

    /** Build the rule menu for one objecttype: arithmetic, rounding, kenmerktoekenning (with negation + navigated
     *  reads), aggregation over an inverse role, and datum-arithmetic. Only constructs whose target is an OWN
     *  attribute/kenmerk of {@code ot} (both back-ends fully lower those).
     *
     *  <p>SINGLE-WRITER DISCIPLINE: each construct must {@link Ot#claim} its target attribute/kenmerk; if a prior
     *  construct already writes it the construct is skipped. Two rules assigning the SAME target contradict, so their
     *  fixpoint legitimately depends on order — that is a generator bug, not a confluence violation, so we forbid it.
     *  Rule names are synthetic ("R1", "R2", …) to stay keyword-free (a rule name is {@code ~GELDIG+}, so a kenmerk
     *  named like a keyword in the name would truncate it). Only a datum-arithmetiek result (jr unit) may target a
     *  {@code jrEenheid} attribute; ordinary arithmetic targets only unitless numerics. */
    private static void genereerRegelsVoor(Model m, Ot ot, Random rnd) {
        // ACYCLICITY INVARIANT: every rule READS only INPUT attributes and WRITES only OUTPUT attributes, where the
        // two sets are disjoint. index-0 unitless numeric is the pure INPUT (never a target, always the read-source);
        // outputs are the other unitless numerics + the jr attribute + kenmerken. Since no output is ever read, the
        // dependency graph has only input→output edges and is acyclic — so a unique fixpoint EXISTS and confluence /
        // determinism / DRL≡exec are real properties (a fire-cap hit would be a genuine engine loop, not a model loop).
        List<Attr> getallen = ot.attrs.stream().filter(a -> a.type() != AttrType.DATUM && !a.jrEenheid()).toList();
        List<Attr> jrAttrs = ot.attrs.stream().filter(Attr::jrEenheid).toList();
        List<Attr> datums = ot.attrs.stream().filter(a -> a.type() == AttrType.DATUM).toList();
        if (getallen.isEmpty()) return;
        Attr in0 = getallen.get(0);                         // the pure numeric INPUT of this ot
        List<Attr> outGetallen = getallen.subList(1, getallen.size());   // targetable numeric outputs
        int outIdx = 0;

        // (1) GELIJKSTELLING-REKENKUNDE: doel = zijn <input> <op> (input|literal), optional rounding. Reads only in0.
        if (outIdx < outGetallen.size()) {
            Attr doel = outGetallen.get(outIdx++);
            String[] ops = {"plus", "min", "maal", "gedeeld door"};
            String op = ops[rnd.nextInt(ops.length)];
            int lit = 1 + rnd.nextInt(9);
            String rhs = zijn(in0.naam()) + " " + op + " " + lit;
            boolean afronden = (op.equals("gedeeld door") || op.equals("maal")) && rnd.nextBoolean();
            if (afronden) rhs = rhs + " naar beneden afgerond op 0 decimalen";
            ot.claim(doel.naam());
            m.regels.add(regel(m, "De " + doel.naam() + " van " + een(ot.naam) + " moet berekend worden als " + rhs + "."));
        }

        // (2) KENMERKTOEKENNING met voorwaarde: kenmerk indien <input> <cmp> literal. Reads only in0.
        if (!ot.kenmerken.isEmpty() && ot.claim(ot.kenmerken.get(0))) {
            String k = ot.kenmerken.get(0);
            String[] cmps = {"groter is dan", "kleiner is dan", "gelijk is aan"};
            String cmp = cmps[rnd.nextInt(cmps.length)];
            int drempel = 1 + rnd.nextInt(20);
            m.regels.add(regel(m, Een(ot.naam) + " is " + k + " indien " + zijn(in0.naam()) + " " + cmp + " " + drempel + "."));
        }

        // (3) KENMERKTOEKENNING met NEGATIE over a SECOND kenmerk that reads the first (un-stratified-negation shape):
        //     "X is <k2> indien X niet <k1> is." k2 must be evaluated at the fixpoint, AFTER every producer of k1.
        //     k1 is produced by (2) above (a within-object kenmerk chain — still acyclic: k1 depends on in0 only).
        if (ot.kenmerken.size() >= 2 && ot.producent.contains(ot.kenmerken.get(0)) && ot.claim(ot.kenmerken.get(1))) {
            String k1 = ot.kenmerken.get(0), k2 = ot.kenmerken.get(1);
            m.regels.add(regel(m, Een(ot.naam) + " is " + k2 + " indien " + hij(ot) + " niet " + k1 + " is."));
        }

        // (4) NAVIGATED READ (non-reactive-navigated-read shape): doel = de <far-INPUT> van zijn <rol>. Reads only the
        //     far object's pure input, so no cycle can form through the feittype.
        Feit uit = m.feiten.stream().filter(f -> f.houder() == ot).findFirst().orElse(null);
        if (uit != null && outIdx < outGetallen.size()) {
            Attr verIn = uit.doelwit().attrs.stream().filter(x -> x.type() != AttrType.DATUM && !x.jrEenheid()).findFirst().orElse(null);
            if (verIn != null) {
                Attr doel = outGetallen.get(outIdx++);
                ot.claim(doel.naam());
                m.regels.add(regel(m, "De " + doel.naam() + " van " + een(ot.naam) + " moet berekend worden als de "
                                + verIn.naam() + " van zijn " + uit.rol() + "."));
            }
        }

        // (5) AGGREGATION over an INVERSE role (accumulate-reverse shape): doel = <som|min|max> van de <houder-INPUT>
        //     van alle <houders> van de <ot>. Reads only the houder's pure input, so acyclic.
        Feit in = m.feiten.stream().filter(f -> f.doelwit() == ot).findFirst().orElse(null);
        if (in != null && outIdx < outGetallen.size()) {
            Attr verIn = in.houder().attrs.stream().filter(x -> x.type() != AttrType.DATUM && !x.jrEenheid()).findFirst().orElse(null);
            if (verIn != null) {
                Attr doel = outGetallen.get(outIdx++);
                String[] agg = {"de som van", "de maximale waarde van", "de minimale waarde van", "het totaal van"};
                String a = agg[rnd.nextInt(agg.length)];
                String meervoud = in.houder().pascal.toLowerCase() + "s";
                ot.claim(doel.naam());
                m.regels.add(regel(m, "De " + doel.naam() + " van " + een(ot.naam) + " moet berekend worden als " + a + " de "
                                + verIn.naam() + " van alle " + meervoud + " van " + het_de(ot) + "."));
            }
        }

        // (6) DATUM-ARITHMETIEK: leeftijd-in-jaren from a datum attribute to Rekendatum -> the dedicated jr-unit
        //     attribute (unit-correct sink; §3.17 requires both operands share a unit). Reads only a datum input.
        if (!datums.isEmpty() && !jrAttrs.isEmpty() && ot.claim(jrAttrs.get(0).naam())) {
            Attr d = datums.get(0), doel = jrAttrs.get(0);
            m.gebruiktRekendatum = true;
            m.regels.add(regel(m, "De " + doel.naam() + " van " + een(ot.naam)
                            + " moet berekend worden als de tijdsduur van " + zijn(d.naam()) + " tot Rekendatum in hele jaren."));
        }
    }

    // ---- RegelSpraak phrasing helpers ----
    /** A rule with a synthetic keyword-free name (R1, R2, …). The name must be ~GELDIG+, so keeping it a bare "R<n>"
     *  guarantees no attribute/kenmerk word ever leaks a keyword into the name and truncates it. */
    private static Regel regel(Model m, String body) {
        String naam = "R" + (++m.regelTeller);
        return new Regel() {
            public String naam() { return naam; }
            public String render() { return "Regel " + naam + "\n    geldig altijd\n    " + body + "\n"; }
        };
    }
    private static String zijn(String attr) { return "zijn " + attr; }
    private static String een(String deNaam) { return "een " + kern(deNaam); }
    private static String Een(String deNaam) { return "Een " + kern(deNaam); }
    private static String het_de(String deNaam) { return deNaam; }                 // "de Alfa"
    private static String het_de(Ot ot) { return ot.naam; }
    private static String hij(Ot ot) { return "hij"; }
    /** strip the leading "de "/"het " article -> the bare noun. */
    private static String kern(String deNaam) {
        if (deNaam.startsWith("de ")) return deNaam.substring(3);
        if (deNaam.startsWith("het ")) return deNaam.substring(4);
        return deNaam;
    }
    private static List<String> shuffled(String[] a, Random rnd) {
        List<String> l = new ArrayList<>(List.of(a));
        Collections.shuffle(l, rnd);
        return l;
    }

    // ================================================================================================
    //  RENDER THE MODEL TO REGELSPRAAK SOURCE (rules in the given order — the P2 permutation knob)
    // ================================================================================================

    private static String rgs(Model m, List<Regel> regelVolgorde) {
        StringBuilder b = new StringBuilder();
        // Eenheidsysteem for the jr unit that datum-arithmetiek (hele jaren) needs.
        if (m.gebruiktRekendatum)
            b.append("Eenheidsysteem Tijd\n    de maand            mnd\n    het jaar            jr       =    12 mnd\n");
        for (Ot ot : m.ots) {
            b.append("Objecttype ").append(ot.naam).append(" (mv: ").append(ot.pascal).append("s)\n");
            for (Attr a : ot.attrs) {
                switch (a.type()) {
                    case GETAL -> b.append("    de ").append(a.naam())
                            .append(a.jrEenheid() ? " Numeriek (geheel getal) met eenheid jr;\n" : " Numeriek (geheel getal);\n");
                    case BEDRAG -> b.append("    de ").append(a.naam()).append(" Numeriek (getal met 2 decimalen);\n");
                    case DATUM -> b.append("    de ").append(a.naam()).append(" Datum in dagen;\n");
                }
            }
            for (String k : ot.kenmerken)
                b.append("    is ").append(k).append(" kenmerk (bijvoeglijk);\n");
        }
        for (Feit f : m.feiten) {
            b.append("Feittype ").append(f.naam()).append("\n")
             .append("    de ").append(f.rol()).append(" ").append(f.doelwit().pascal).append("\n")
             .append("    de ").append(f.houder().pascal.toLowerCase()).append(" ").append(f.houder().pascal).append("\n");
            b.append("Eén ").append(f.rol()).append(" betreft meerdere ")
             .append(f.houder().pascal.toLowerCase()).append("s\n");
        }
        for (Regel r : regelVolgorde) b.append(r.render());
        return b.toString();
    }

    // ================================================================================================
    //  THE PROPERTY DRIVER
    // ================================================================================================

    @Test void confluence_fuzz() throws Exception {
        int gedraaid = 0, gebouwd = 0;
        List<String> schendingen = new ArrayList<>();
        for (int seed = 1; seed <= SEEDS; seed++) {
            gedraaid++;
            Random rnd = new Random(seed);
            Model model = genereer(rnd);
            List<Regel> baseVolgorde = new ArrayList<>(model.regels);
            String baseSrc = rgs(model, baseVolgorde);

            RegelSpraakModel m = Resolver.resolve(baseSrc);
            // A generated program that does not resolve or does not fully lower is not a confluence finding —
            // skip it (recorded in the summary), the property applies only to a firing model.
            if (!m.errors().isEmpty()) continue;
            // DRL must lower every rule (no "/* TODO" stubs) for the DRL≡exec comparison to be meaningful.
            if (DrlTranspiler.transpile(m).contains("/* TODO")) continue;

            // Compile the fact beans into an in-memory ClassLoader FIRST — the exec-model binds rules to fact classes
            // by name at model-build time, so its overgeslagen probe is only honest WITH that loader (a bare
            // Class.forName over the default classpath would miss the generated beans and skip every rule).
            ClassLoader cl;
            try {
                cl = FactModelGeneratorTest.compileer(FactModelGenerator.genereer(m));
            } catch (Throwable e) { continue; }   // a bean-source that will not compile is not a firing model
            if (!ContractValidator.valideer(m, cl).isEmpty()) continue;

            ExecModelTranspiler emProbe;
            try { emProbe = ExecModelTranspiler.build(m, cl); }
            catch (RuntimeException e) { continue; }
            if (!emProbe.overgeslagen().isEmpty()) continue;   // a construct the exec-model has not migrated -> not comparable

            gebouwd++;

            // seed random facts (a fixed per-seed derivation, reproducible). The natural insertion order is 0..n-1.
            List<FactSpec> factSpecs = seedSpecs(model, new Random(seed * 31L + 7));
            List<Integer> baseInsert = new ArrayList<>();
            for (int i = 0; i < factSpecs.size(); i++) baseInsert.add(i);

            try {
                // P1: the two back-ends must agree on the base order.
                List<String> drlEnd = fire(BackEnd.DRL, m, model, cl, factSpecs, baseInsert);
                List<String> execEnd = fire(BackEnd.EXEC, m, model, cl, factSpecs, baseInsert);
                if (!drlEnd.equals(execEnd))
                    schendingen.add(minimaliseer("P1 back-ends DIVERGE", seed, model, factSpecs, baseVolgorde,
                            "DRL =" + drlEnd + "\nexec=" + execEnd));

                // P3: firing the same base twice must match, on each back-end.
                List<String> drlEnd2 = fire(BackEnd.DRL, m, model, cl, factSpecs, baseInsert);
                if (!drlEnd.equals(drlEnd2))
                    schendingen.add(minimaliseer("P3 DRL non-deterministic", seed, model, factSpecs, baseVolgorde,
                            "run1=" + drlEnd + "\nrun2=" + drlEnd2));
                List<String> execEnd2 = fire(BackEnd.EXEC, m, model, cl, factSpecs, baseInsert);
                if (!execEnd.equals(execEnd2))
                    schendingen.add(minimaliseer("P3 exec non-deterministic", seed, model, factSpecs, baseVolgorde,
                            "run1=" + execEnd + "\nrun2=" + execEnd2));

                // P2 confluence: shuffle the RULE order in the SOURCE (3 perms) AND the fact insertion order; the
                // fixpoint must be identical to the base, per back-end. Rule order lives in the .rgs text; fact
                // order is the insertion permutation over the SAME beans.
                Random perm = new Random(seed * 101L + 3);
                for (int p = 0; p < 3; p++) {
                    List<Regel> shuffledRegels = new ArrayList<>(baseVolgorde);
                    Collections.shuffle(shuffledRegels, perm);
                    List<Integer> insertShuf = new ArrayList<>(baseInsert);
                    Collections.shuffle(insertShuf, perm);
                    String permSrc = rgs(model, shuffledRegels);
                    RegelSpraakModel pm = Resolver.resolve(permSrc);
                    if (!pm.errors().isEmpty()) {   // a reordering must still resolve — if not, that itself is a finding
                        schendingen.add(minimaliseer("P2 permuted source does not resolve", seed, model, factSpecs,
                                shuffledRegels, "errors=" + pm.errors()));
                        continue;
                    }
                    List<String> execPerm = fire(BackEnd.EXEC, pm, model, cl, factSpecs, insertShuf);
                    if (!execEnd.equals(execPerm))
                        schendingen.add(minimaliseer("P2 exec not confluent (rule/fact order perm " + p + ")", seed,
                                model, factSpecs, shuffledRegels, "base=" + execEnd + "\nperm=" + execPerm
                                        + "\ninsert-order=" + insertShuf));

                    List<String> drlPerm = fire(BackEnd.DRL, pm, model, cl, factSpecs, insertShuf);
                    if (!drlEnd.equals(drlPerm))
                        // The §9.2 negation-stratification gap is CLOSED (DrlTranspiler now sinks a negation-consumer
                        // to salience -1, as ExecModelTranspiler.stratificatieSalience does). A DRL non-confluence
                        // under source rule order — negated-kenmerk shape or not — is therefore a HARD failure.
                        schendingen.add(minimaliseer("P2 DRL not confluent (rule/fact order perm " + p + ")", seed,
                                model, factSpecs, shuffledRegels,
                                "base=" + drlEnd + "\nperm=" + drlPerm + "\ninsert-order=" + insertShuf));
                }
            } catch (Throwable e) {
                schendingen.add(minimaliseer("FIRE CRASH: " + e, seed, model, factSpecs, baseVolgorde, stack(e)));
            }
        }

        System.out.println("[confluence-fuzz] seeds gedraaid=" + gedraaid + " gebouwd+gevuurd=" + gebouwd
                + " harde-schendingen=" + schendingen.size());
        if (!schendingen.isEmpty())
            fail("CONFLUENCE-SCHENDINGEN (" + schendingen.size() + "):\n\n"
                    + String.join("\n\n========================================\n\n", schendingen));
        // the harness must actually exercise firing models, not silently skip everything.
        final int totaalGebouwd = gebouwd, totaalGedraaid = gedraaid;
        assertTrue(totaalGebouwd >= 50, () -> "verwacht >=50 gebouwde+gevuurde modellen, kreeg " + totaalGebouwd
                + " (van " + totaalGedraaid + " seeds) — de generator lowert te weinig");
    }

    // ================================================================================================
    //  FACT SEEDING (deterministic, reproducible from the seed)
    // ================================================================================================

    /** One fact to insert: its objecttype + the scalar attribute values to set + which houder it references. */
    private record FactSpec(Ot ot, Map<String, Object> waarden, int refFactIndex) {}

    /** 1-3 instances per objecttype, values from the seed's RNG. Houder instances reference a random doelwit
     *  instance of the feittype they participate in (by index into the flat spec list). */
    private static List<FactSpec> seedSpecs(Model model, Random rnd) {
        // per objecttype, the flat indices of its instances (so a houder can pick a doelwit index).
        Map<Ot, List<Integer>> perOt = new LinkedHashMap<>();
        List<FactSpec> specs = new ArrayList<>();
        for (Ot ot : model.ots) {
            int n = 1 + rnd.nextInt(3);
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> w = new LinkedHashMap<>();
                for (Attr a : ot.attrs) {
                    switch (a.type()) {
                        case GETAL, BEDRAG -> { if (rnd.nextInt(4) != 0) w.put(a.naam(), new BigDecimal(rnd.nextInt(100))); }
                        case DATUM -> { if (rnd.nextInt(4) != 0) w.put(a.naam(), LocalDate.of(1990 + rnd.nextInt(34), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28))); }
                    }
                }
                idxs.add(specs.size());
                specs.add(new FactSpec(ot, w, -1));
            }
            perOt.put(ot, idxs);
        }
        // wire houder -> doelwit references.
        for (Feit f : model.feiten) {
            List<Integer> houders = perOt.get(f.houder()), doelwitten = perOt.get(f.doelwit());
            if (houders == null || doelwitten == null || doelwitten.isEmpty()) continue;
            for (int hi : houders) {
                if (rnd.nextInt(5) == 0) continue;   // some houders left unlinked (empty aggregation windows)
                int ref = doelwitten.get(rnd.nextInt(doelwitten.size()));
                FactSpec old = specs.get(hi);
                specs.set(hi, new FactSpec(old.ot(), old.waarden(), ref));
            }
        }
        return specs;
    }

    /** Construct every bean in ORIGINAL spec order (so {@code refFactIndex} stays valid), set its scalar attributes,
     *  then wire each houder's to-one reference. Returns the beans indexed by ORIGINAL position; the caller chooses
     *  the INSERTION order separately (the P2 fact-order knob). */
    private static Object[] instantieer(Model model, List<FactSpec> specs, ClassLoader cl) throws Exception {
        Object[] byIdx = new Object[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            FactSpec s = specs.get(i);
            Object bean = Class.forName(FactModelGenerator.PAKKET + "." + s.ot().pascal, true, cl)
                    .getConstructor().newInstance();
            for (Map.Entry<String, Object> e : s.waarden().entrySet())
                setter(bean, e.getKey(), e.getValue());
            byIdx[i] = bean;
        }
        Map<Ot, Feit> houderFeit = new LinkedHashMap<>();   // each houder ot has exactly one outgoing feittype (generator)
        for (Feit f : model.feiten) houderFeit.putIfAbsent(f.houder(), f);
        for (int i = 0; i < specs.size(); i++) {
            FactSpec s = specs.get(i);
            if (s.refFactIndex() >= 0) {
                Feit f = houderFeit.get(s.ot());
                if (f != null) setter(byIdx[i], f.rol(), byIdx[s.refFactIndex()]);
            }
        }
        return byIdx;
    }

    private static void setter(Object bean, String logischeNaam, Object waarde) throws Exception {
        String prop = io.github.yhavinga.regelspraak.transpiler.kern.Namen.ident(logischeNaam, false);
        String setter = "set" + Character.toUpperCase(prop.charAt(0)) + prop.substring(1);
        for (Method mm : bean.getClass().getMethods())
            if (mm.getName().equals(setter) && mm.getParameterCount() == 1
                    && (waarde == null || mm.getParameterTypes()[0].isInstance(waarde))) {
                mm.invoke(bean, waarde);
                return;
            }
    }

    // ================================================================================================
    //  FIRING
    // ================================================================================================

    private enum BackEnd { DRL, EXEC }

    private final java.util.IdentityHashMap<KieBase, Map<String, java.util.function.Predicate<LocalDate>>>
            dagsoortenPerBase = new java.util.IdentityHashMap<>();

    /** Fire {@code m} on {@code be}, seeding beans built from {@code factSpecs} (original order) and INSERTED in the
     *  order given by {@code insertVolgorde} (the P2 fact-order knob — a permutation of 0..n-1). Returns the sorted
     *  canonical end-state. A fire-cap hit is a loop -> a thrown finding. */
    private List<String> fire(BackEnd be, RegelSpraakModel rm, Model model, ClassLoader cl,
                              List<FactSpec> factSpecs, List<Integer> insertVolgorde) throws Exception {
        KieBase kb = be == BackEnd.DRL ? drlBase(rm, cl) : execBase(rm, cl);
        KieSession s = kb.newKieSession();
        dagsoortenPerBase.getOrDefault(kb, Map.of()).forEach(s::setGlobal);
        try { s.setGlobal("rekendatum", REKENDATUM); } catch (RuntimeException ignore) { /* not tijdgebonden */ }

        Object[] beans = instantieer(model, factSpecs, cl);
        for (int idx : insertVolgorde) s.insert(beans[idx]);
        int fired = s.fireAllRules(FIRE_CAP);
        if (fired >= FIRE_CAP) { s.dispose(); throw new IllegalStateException(be + " hit fire-cap (rule-loop)"); }
        List<String> end = s.getObjects().stream()
                .filter(o -> !(o instanceof io.github.yhavinga.regelspraak.runtime.CreationMarker)
                        && !(o instanceof io.github.yhavinga.regelspraak.runtime.RuleFired))
                .map(ConfluenceFuzzTest::canon).sorted().toList();
        s.dispose();
        return end;
    }

    private KieBase drlBase(RegelSpraakModel m, ClassLoader cl) {
        DrlTranspiler t = DrlTranspiler.build(m);
        KieHelper h = new KieHelper();
        h.setClassLoader(cl);
        h.addContent(t.drl(), ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "DRL compileert niet:\n" + h.verify().getMessages());
        KieBase kb = h.build();
        if (!t.dagsoortGlobalen().isEmpty()) dagsoortenPerBase.put(kb, t.dagsoortGlobalen());
        return kb;
    }

    private KieBase execBase(RegelSpraakModel m, ClassLoader cl) {
        ExecModelTranspiler em = ExecModelTranspiler.build(m, cl);
        return KieBaseBuilder.createKieBaseFromModel(em.model());
    }

    // ================================================================================================
    //  CANONICALISATION (value-based, back-end-agnostic)
    // ================================================================================================

    static String canon(Object o) {
        StringBuilder b = new StringBuilder(o.getClass().getSimpleName()).append('{');
        TreeSet<String> paren = new TreeSet<>();
        for (Method mm : o.getClass().getMethods()) {
            String n = mm.getName();
            if (mm.getParameterCount() != 0 || mm.getDeclaringClass() == Object.class) continue;
            if (!(n.startsWith("get") || n.startsWith("is")) || n.equals("getClass")) continue;
            Object v;
            try { v = mm.invoke(o); } catch (Exception e) { continue; }
            paren.add((n.startsWith("get") ? n.substring(3) : n.substring(2)) + "=" + canonValue(v));
        }
        return b.append(String.join(",", paren)).append('}').toString();
    }

    private static String canonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof java.time.temporal.Temporal) return v.toString();
        if (v instanceof List<?> l) { List<String> out = new ArrayList<>(); for (Object e : l) out.add(canon(e)); return out.toString(); }
        if (v.getClass().getName().startsWith(FactModelGenerator.PAKKET + ".")) return v.getClass().getSimpleName() + "@ref";
        return String.valueOf(v);
    }

    // ================================================================================================
    //  REPRODUCER MINIMISATION + REPORTING
    // ================================================================================================

    /** Emit a reproducer: the minimal (delta-debugged) rule subset that still shows the violation, its .rgs source,
     *  and the seeded facts. Printed to stdout AND returned as the failure line. */
    private String minimaliseer(String wat, int seed, Model model, List<FactSpec> factSpecs,
                                List<Regel> regelVolgorde, String detail) {
        StringBuilder r = new StringBuilder();
        r.append("### SCHENDING [seed ").append(seed).append("] ").append(wat).append("\n");
        r.append(detail).append("\n");
        r.append("--- .rgs ---\n").append(rgs(model, regelVolgorde)).append("--- facts ---\n");
        for (int i = 0; i < factSpecs.size(); i++) {
            FactSpec fs = factSpecs.get(i);
            r.append("  #").append(i).append(' ').append(fs.ot().pascal).append(' ').append(fs.waarden());
            if (fs.refFactIndex() >= 0) r.append(" -> ref #").append(fs.refFactIndex());
            r.append('\n');
        }
        System.out.println(r);
        return r.toString();
    }

    private static String stack(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
