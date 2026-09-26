package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The regelnaam → bronbestand index (decision 2b): given the ordered list of {@code .rgs} source files that
 * make up a service, work out which rules each file DECLARES, and which files declare NONE (the PRELUDE —
 * objecttypen, feittypen, domeinen, parameters, eenheidstelsels, …). The core then compiles one
 * {@link org.kie.api.KieBase} per entry point (the DRL recipe) over {@code prelude + scoped-rule-files},
 * so an entry point fires ONLY its scoped rules and upstream rules cannot overwrite request-given intermediate
 * inputs (exact G2 reproduction). File granularity is the unit: a file is either prelude or a rule file, and a
 * rule name maps to the ONE file that declares it (the TOKA casus keeps one construct per file, verified
 * empirically).
 *
 * <p>Determining a file's declared rules uses ONLY the public {@link Resolver} API: resolve
 * {@code prelude + file} and read the rule names the model exposes, then subtract the prelude's own rules.
 * "Rule names" is the union of (a) the ENGINE-visible, coverage-tracked rules — the resolved regels (incl.
 * beslistabel rows and versioned rules), the §9.7 verdelingen, §9.5 consistenties, §9.6 uniciteiten, and
 * §13.4.6 objectcreaties, what {@link dev.uchar.regelspraak.toolchain.engine.RegelSpraakEngine#dekking()} later reports —
 * and (b) the RAW declarations in {@link RegelSpraakModel#regels}, which additionally carry §13.4.7 feitcreatie
 * names that {@code isValueWriter} excludes from every resolved collection. The raw list is the SUPERSET a
 * scope may name; see {@link #regelnamen(RegelSpraakModel)}.
 */
public final class RegelBestandIndex {

    private final List<String> preludeBestanden;                 // files declaring no rules (in order)
    private final Map<String, String> regelNaarBestand;          // rule name -> declaring file
    private final Map<String, List<String>> regelsPerBestand;    // file -> rule names it declares (in order)

    private RegelBestandIndex(List<String> preludeBestanden,
                              Map<String, String> regelNaarBestand,
                              Map<String, List<String>> regelsPerBestand) {
        this.preludeBestanden = preludeBestanden;
        this.regelNaarBestand = regelNaarBestand;
        this.regelsPerBestand = regelsPerBestand;
    }

    /**
     * Build the index from the ordered source-text list (one entry per {@code .rgs} file, its full text). The
     * caller supplies file text keyed by a stable file id (its path); order matters, because a rule file may
     * depend on prelude declarations earlier in the list.
     *
     * <p>A file is classified as a RULE FILE iff its text carries a rule-defining construct header (a line
     * starting, after leading whitespace, with {@code Regel } or {@code Beslistabel }); every other file is
     * PRELUDE (objecttypen, feittypen, domeinen, parameters, eenheidstelsels, tijdlijnen, …). This textual
     * classification is deterministic and, crucially, avoids crediting a prelude file with a downstream rule
     * that merely DEPENDS on it: a diff-based classifier ("rules with f" minus "rules without f") would wrongly
     * attribute a rule to every prelude file it needs, because removing that prelude also removes the rule. To
     * get a rule file's DECLARED names we resolve {@code allPrelude + thisRuleFile} (prelude carries no rules,
     * so the resolved rules are exactly this file's) — dependencies are satisfied, ownership is unambiguous.
     */
    public static RegelBestandIndex bouw(Map<String, String> bestandTekst) {
        List<String> ids = new ArrayList<>(bestandTekst.keySet());
        List<String> prelude = new ArrayList<>();
        List<String> ruleFiles = new ArrayList<>();
        for (String id : ids) (declareertRegel(bestandTekst.get(id)) ? ruleFiles : prelude).add(id);

        StringBuilder preludeTekst = new StringBuilder();
        for (String id : prelude) preludeTekst.append(bestandTekst.get(id)).append('\n');

        Map<String, List<String>> perBestand = new LinkedHashMap<>();
        Map<String, String> naarBestand = new LinkedHashMap<>();
        for (String id : prelude) perBestand.put(id, List.of());

        for (String id : ruleFiles) {
            Set<String> namen = regelnamen(preludeTekst + bestandTekst.get(id) + "\n");
            List<String> eigen = new ArrayList<>(namen);
            perBestand.put(id, eigen);
            for (String r : eigen) {
                if (naarBestand.containsKey(r))
                    throw new IllegalStateException("regel '" + r + "' wordt door meer dan één bestand gedeclareerd: "
                            + naarBestand.get(r) + " en " + id + " — de dienst-kern verwacht één construct per bestand");
                naarBestand.put(r, id);
            }
        }
        return new RegelBestandIndex(prelude, naarBestand, perBestand);
    }

    /** Whether a source text declares a rule — a line beginning (after leading whitespace) with a
     *  rule-defining keyword. {@code Regel} covers §9.3 regels; {@code Beslistabel} the §9.11 decision tables.
     *  Comment ({@code //}) and blank lines never count. */
    static boolean declareertRegel(String tekst) {
        for (String r : tekst.split("\n", -1)) {
            String t = r.strip();
            if (t.startsWith("//") || t.isEmpty()) continue;
            if (t.startsWith("Regel ") || t.startsWith("Beslistabel ")) return true;
        }
        return false;
    }

    /** The engine-visible rule names a source resolves to (resolved regels + verdelingen + consistenties +
     *  uniciteiten + objectcreaties). A resolve error leaves the set as-far-as-resolved — the descriptor
     *  loader validates the whole model separately and reports resolve errors there. */
    static Set<String> regelnamen(String source) {
        RegelSpraakModel m = Resolver.resolve(source);
        return regelnamen(m);
    }

    /**
     * The nameable rule names of an already-resolved model — the set a descriptor scope and this index may name.
     *
     * <p>The RESOLVED collections (resolvedRegels + §9.7 verdelingen + §9.5 consistenties + §9.6 uniciteiten +
     * §13.4.6 objectcreaties) are the engine-visible, coverage-tracked rules — what {@code engine.dekking()}
     * later reports. But they are a STRICT SUBSET of what an author may scope: a §13.4.7 FEITCREATIE
     * ("passagier met recht op treinmiles") is a role-relationship rule that {@code RegelSpraakModel#isValueWriter}
     * excludes from every resolved collection (it writes no scalar/kenmerk value), so it appears in NONE of them —
     * yet it IS a declared, scopable rule. It survives only in the RAW declaration list {@link RegelSpraakModel#regels}
     * (top-level regels + the verdeling/consistentie/objectcreatie/feitcreatie constructs). We take the union of
     * the two, exactly mirroring the scenario front-end's {@code ScenarioResolver.regelBestaat} (which scopes
     * "toetst de regel X" against {@code model.regels} ∪ {@code resolvedRegels}), so a golden scenario's scope and
     * a descriptor entrypoint agree on which names exist. A feitcreatie so named yields ZERO dekking entries — it
     * is accepted but emits no DRL, and is NOT a marker (see {@link BeslisserKern}).
     */
    public static Set<String> regelnamen(RegelSpraakModel m) {
        Set<String> namen = new LinkedHashSet<>();
        for (var r : m.resolvedRegels) namen.add(basisNaam(r.naam()));
        for (var v : m.verdelingen) if (v.naam() != null) namen.add(basisNaam(v.naam()));
        for (var c : m.consistenties) if (c.naam() != null) namen.add(basisNaam(c.naam()));
        for (var u : m.uniciteiten) if (u.naam() != null) namen.add(basisNaam(u.naam()));
        for (var oc : m.objectCreaties) if (oc.naam() != null) namen.add(basisNaam(oc.naam()));
        // raw declarations as a SUPERSET: a feitcreatie (and any construct isValueWriter excludes from the
        // resolved collections) is nameable, so a scope over it validates and its file indexes.
        for (var r : m.regels) if (r.naam() != null) namen.add(basisNaam(r.naam()));
        return namen;
    }

    /** Strip a §13.4.2.3 " #k" version suffix (and a beslistabel multi-conclusie " (n)" trailing suffix) off a
     *  rule name so a multi-version / multi-column rule indexes under one base name — the name an author writes
     *  in a scope and a descriptor, and the name both the index and {@code engine.dekking()} must agree on. */
    static String basisNaam(String naam) {
        if (naam == null) return "";
        String n = naam;
        int hash = n.indexOf(" #");
        if (hash > 0) n = n.substring(0, hash);
        n = n.replaceFirst("\\s*\\(\\d+\\)\\s*$", "");   // drop a trailing " (2)" multi-conclusie suffix
        return n.trim();
    }

    public List<String> preludeBestanden() { return preludeBestanden; }
    public Map<String, String> regelNaarBestand() { return regelNaarBestand; }
    public Map<String, List<String>> regelsPerBestand() { return regelsPerBestand; }

    /** The declaring file for a rule name, or null when no file declares it. */
    public String bestandVoorRegel(String regelnaam) { return regelNaarBestand.get(basisNaam(regelnaam)); }
}
