package io.github.yhavinga.regelspraak.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resolved IR: the typed model a RegelSpraak document denotes, plus any
 * diagnostics raised while building and type-checking it. This is what the
 * transpiler front-end hands on to the Drools back-end.
 */
public final class RegelSpraakModel {
    public final Map<String, Objecttype> objecttypen = new LinkedHashMap<>();
    public final Map<String, Datatype> domeinen = new LinkedHashMap<>();
    public final Map<String, Parameter> parameters = new LinkedHashMap<>();
    public final Map<String, Eenheidstelsel> eenheidstelsels = new LinkedHashMap<>();
    public final Map<String, Feittype> feittypen = new LinkedHashMap<>();
    public final List<String> dagsoorten = new ArrayList<>();
    /** §3.12 a dagsoort may be the EENHEID of a Numeriek attribute ("5 werkdagen"); maps every unit spelling
     *  (the singular name AND its declared meervoud, lowercased) to the dagsoort's singular name, so the §6
     *  duration paths can recognise a dagsoort unit. */
    public final Map<String, String> dagsoortPerEenheid = new LinkedHashMap<>();
    /** The §3.12 dagsoort whose unit spelling is {@code afkorting} (singular or meervoud, case-insensitive), or
     *  null when the unit is not a dagsoort — the §6.10/§6.11 transpilers' dagsoort-unit recognition. */
    public String dagsoortVoorEenheid(String afkorting) {
        return afkorting == null ? null : dagsoortPerEenheid.get(afkorting.trim().toLowerCase());
    }
    public final List<Tijdlijn> tijdlijnen = new ArrayList<>();
    public final Map<String, Dimensie> dimensies = new LinkedHashMap<>();
    public final List<Regel> regels = new ArrayList<>();
    /** Resolved rule IR (typed expressions + bound references) — the transpiler's input. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedRegel> resolvedRegels = new ArrayList<>();
    /** Resolved §9.8 dagsoortdefinities (dagsoort name + condition over "de dag") — each becomes a
     *  per-date boolean predicate the transpilers compile for §8.1.5 controls and the §6 durations. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedDagsoort> resolvedDagsoorten = new ArrayList<>();
    /** Resolved object-creation rules (§13.4.6) — a separate IR shape (new object + attributes). */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedObjectCreatie> objectCreaties = new ArrayList<>();
    /** Resolved distribution rules (§13.4.10) — source amount spread over recipients. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedVerdeling> verdelingen = new ArrayList<>();
    /** Resolved consistency rules (§13.4.8) — an assertion that must hold, else inconsistent. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedConsistentie> consistenties = new ArrayList<>();
    /** Resolved §8.1.6 uniqueness checks — a key that must be unique across instances, else inconsistent. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedUniciteit> uniciteiten = new ArrayList<>();
    /** Resolved §8.1.6 cross-type "verenigd met" uniqueness — a key unique across the union of types. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedUniciteitUnie> uniciteitUnies = new ArrayList<>();
    /** Resolved §13.4.7 feitcreatie rules — a new feittype instance (a reference field) linking two existing objects. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedFeitCreatie> feitCreaties = new ArrayList<>();
    /** Resolved §9.9/§13.4.12 startpuntbepalingen — the anchor member per (objecttype, variable tijdlijn);
     *  the anchor WRITE itself is an ordinary entry in {@link #resolvedRegels}. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedStartpunt> resolvedStartpunten = new ArrayList<>();
    /** §9.10 Regelgroepen — a DOCUMENTED EXTENSION (see docs/grammar-divergences.md). Each carries its member
     *  RULE NAMES (the bare written names); members otherwise resolve exactly as ungrouped rules. Both back-ends
     *  prefix ONLY the engine-visible rule-name header with "<groep>: " — every data key stays the bare name. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedRegelGroep> regelGroepen = new ArrayList<>();
    /** §4 (PRIORITIZED-DEFAULTS-EVOLUTIE) the PrioritizedDefaults nodes lowered from the uchar-dialect
     *  'Uitzondering op' / 'Uitzonderingen ongeordend' extension — the ONLY path by which the RegelSpraak
     *  frontend produces defaults groups (the flat v0-mirror rules never do). Empty for every document without
     *  the extension, so the emitter omits the {@code prioritizedDefaults} key there (the v0-mirror
     *  byte-identity of all flat goldens). */
    public final List<io.github.yhavinga.regelspraak.ir.PrioritizedDefaults> prioritizedDefaults = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();

    /** §9.10 the group a rule belongs to, given an EMITTED rule name — which is the bare member name, optionally
     *  with a §13.4.2.3 version suffix ("<naam> #k") or a back-end-derived qualifier ("<naam> (onverdeelde rest)").
     *  Matches the LONGEST group member name that is a prefix of {@code geëmitteerdeNaam} at a token boundary (end,
     *  " #", or " ("), so a member "R" never spuriously captures an ungrouped "R2". Null when the rule is ungrouped
     *  — the back-ends then emit the bare header. */
    public io.github.yhavinga.regelspraak.ir.ResolvedRegelGroep regelGroepVan(String geëmitteerdeNaam) {
        if (geëmitteerdeNaam == null) return null;
        io.github.yhavinga.regelspraak.ir.ResolvedRegelGroep beste = null;
        int besteLen = -1;
        for (var g : regelGroepen)
            for (String lid : g.leden())
                if (lid.length() > besteLen && geëmitteerdeNaam.equals(lid)
                        || lid.length() > besteLen && (geëmitteerdeNaam.startsWith(lid + " #") || geëmitteerdeNaam.startsWith(lid + " ("))) {
                    beste = g; besteLen = lid.length();
                }
        return beste;
    }

    /** §9.10 fase-2: whether the EMITTED rule {@code geëmitteerdeNaam} belongs to a {@code is recursief} Regelgroep.
     *  A recursief-group objectcreatie must forward-chain on its own inserts to reach the fixpoint, so both back-ends
     *  drop {@code no-loop} for it — sound because the §13.4.6/§9 idempotence guard is the anti-runaway control. */
    public boolean isRecursiefGroepslid(String geëmitteerdeNaam) {
        var g = regelGroepVan(geëmitteerdeNaam);
        return g != null && g.recursief();
    }

    /** §9.10 fase-1 namespacing: the ENGINE-VISIBLE rule-name header for an emitted rule name — "<groep>: <naam>"
     *  when the rule belongs to a Regelgroep, else the bare name. Called by both back-ends at (and only at) the
     *  point a rule name becomes an engine header; every data key keeps the bare name. */
    public String geëmitteerdeRegelNaam(String geëmitteerdeNaam) {
        var g = regelGroepVan(geëmitteerdeNaam);
        return g == null ? geëmitteerdeNaam : g.naam() + ": " + geëmitteerdeNaam;
    }

    /** Normalise a tijdlijn name for cross-reference matching: collapse internal whitespace (names may wrap
     *  across source lines), lowercase, and strip a single leading lidwoord (the definition writes "de
     *  periode …", a reference "elke periode …" / "een periode …"). */
    public static String normaliseerTijdlijnNaam(String s) {
        String n = s.trim().replaceAll("\\s+", " ").toLowerCase();
        for (String lw : new String[]{"de ", "het ", "een "})
            if (n.startsWith(lw)) return n.substring(lw.length());
        return n;
    }

    /** The FIRST declared tijdlijn whose normalised name matches {@code naam} (the same first-match rule
     *  the §13.3.6.10 attribute adoption uses), or null. */
    public Tijdlijn tijdlijnOpNaam(String naam) {
        if (naam == null) return null;
        String ref = normaliseerTijdlijnNaam(naam);
        return tijdlijnen.stream().filter(tl -> normaliseerTijdlijnNaam(tl.naam()).equals(ref))
                .findFirst().orElse(null);
    }

    /** §3.8 whether a temporal attribute rides a NAMED tijdlijn declared "met variabel startpunt". */
    public boolean heeftVariabelStartpunt(Objecttype ot, String attribuut) {
        String tlNaam = ot.attribuutTijdlijnNaam.get(attribuut);
        Tijdlijn tl = tlNaam == null ? null : tijdlijnOpNaam(tlNaam);
        return tl != null && tl.variabelStartpunt();
    }

    /** §9.9 the startpunt-anchor attribute a variable-startpunt temporal doel buckets at, or null (a
     *  fixed/inline tijdlijn, or no startpuntbepaling resolved for the pair — the back-ends then marker). */
    public String startpuntAnkerLid(Objecttype ot, String attribuut) {
        if (!heeftVariabelStartpunt(ot, attribuut)) return null;
        return startpuntLid(ot, ot.attribuutTijdlijnNaam.get(attribuut));
    }

    /** §9.9 whether {@code lid} is the synthetic startpunt-anchor attribute of {@code objecttype} — the
     *  back-ends key the §6.8 leeg-fout companion on it. */
    public boolean isStartpuntLid(Objecttype objecttype, String lid) {
        return objecttype != null && resolvedStartpunten.stream()
                .anyMatch(sp -> sp.objecttype().equals(objecttype.naam) && sp.lid().equals(lid));
    }

    /** §9.9 the synthetic anchor-attribute name for {@code objecttype} × variable tijdlijn {@code tijdlijnNaam},
     *  or null when no startpuntbepaling was resolved for that pair. */
    public String startpuntLid(Objecttype objecttype, String tijdlijnNaam) {
        if (objecttype == null || tijdlijnNaam == null) return null;
        String ref = normaliseerTijdlijnNaam(tijdlijnNaam);
        return resolvedStartpunten.stream()
                .filter(sp -> sp.objecttype().equals(objecttype.naam) && normaliseerTijdlijnNaam(sp.tijdlijn()).equals(ref))
                .map(io.github.yhavinga.regelspraak.ir.ResolvedStartpunt::lid).findFirst().orElse(null);
    }

    public boolean isOk() { return diagnostics.stream().noneMatch(Diagnostic::isError); }
    public List<Diagnostic> errors() { return diagnostics.stream().filter(Diagnostic::isError).toList(); }

    /** §8.1.9 whether {@code naam} names a rule that INSERTS §9.5 Inconsistentie flag facts — a
     *  consistentie, uniciteit, uniciteit-unie or (via the §9.7.6 Tabel-19 fout channel) verdeling.
     *  The "regelversie X is inconsistent" regelpredicaat lowers to an exists on that channel ONLY for
     *  a known producer; any other name would be a never-matching exists (a silent never-fire), so the
     *  back-ends decline it as an honest marker instead. */
    public boolean inconsistentieProducent(String naam) {
        if (naam == null) return false;
        return consistenties.stream().anyMatch(c -> naam.equals(c.naam()))
                || uniciteiten.stream().anyMatch(u -> naam.equals(u.naam()))
                || uniciteitUnies.stream().anyMatch(u -> naam.equals(u.naam()))
                || verdelingen.stream().anyMatch(v -> naam.equals(v.naam()));
    }

    /** §8.1.9 the rule names referenced by a live "regelversie &lt;X&gt; is gevuurd" predicate anywhere in the
     *  model — walked across every construct that carries a condition (a regel voorwaarde, a verdeling/creatie
     *  voorwaarde, a consistentie assertie). The transpilers emit a {@code RuleFired} marker in the consequence
     *  of a referenced rule ONLY (opt-in emission, no fact-base bloat), and lower the predicate to an
     *  {@code exists RuleFired} only for a name that is both referenced AND an instrumented producer. */
    public java.util.Set<String> gevuurdReferenties() {
        java.util.Set<String> uit = new java.util.LinkedHashSet<>();
        for (var r : resolvedRegels) io.github.yhavinga.regelspraak.ir.Conditie.verzamelGevuurdReferenties(r.voorwaarde(), uit);
        for (var v : verdelingen)    io.github.yhavinga.regelspraak.ir.Conditie.verzamelGevuurdReferenties(v.voorwaarde(), uit);
        for (var oc : objectCreaties) io.github.yhavinga.regelspraak.ir.Conditie.verzamelGevuurdReferenties(oc.voorwaarde(), uit);
        for (var fc : feitCreaties)  io.github.yhavinga.regelspraak.ir.Conditie.verzamelGevuurdReferenties(fc.voorwaarde(), uit);
        for (var c : consistenties)  io.github.yhavinga.regelspraak.ir.Conditie.verzamelGevuurdReferenties(c.assertie(), uit);
        return uit;
    }

    /** §8.1.9 whether {@code naam} names a rule the back-ends INSTRUMENT with a {@code RuleFired} marker — a
     *  main {@code regel()}-path rule (gelijkstelling / berekening / initialisatie / kenmerktoekenning), the
     *  common case whose consequence both back-ends unconditionally mark. "regelversie X is gevuurd" lowers to
     *  an {@code exists RuleFired} ONLY for such a name (else the exists would never match — a silent never-fire);
     *  a gevuurd-reference to any other kind (a consistentie/uniciteit rule, whose "result" is the Inconsistentie
     *  channel that "is inconsistent" reads, or an unknown name) stays an honest symmetric marker. This gates
     *  ONLY the lowering; emission is additionally gated on the name being in {@link #gevuurdReferenties()}. */
    public boolean gevuurdProducent(String naam) {
        if (naam == null) return false;
        return resolvedRegels.stream().anyMatch(r -> naam.equals(r.naam()) && gevuurdInstrumenteerbaar(r.soort()));
    }

    /** The §8.1.9 main-regel() rule kinds whose consequence carries a {@code RuleFired} insert (the scalar/kenmerk
     *  value writers routed through {@code regel()}); an objectcreatie/feitcreatie/verdeling/consistentie is out
     *  of this instrumented set (its status stays a marker). */
    private static boolean gevuurdInstrumenteerbaar(io.github.yhavinga.regelspraak.ir.ResolvedRegel.Soort s) {
        return s == io.github.yhavinga.regelspraak.ir.ResolvedRegel.Soort.GELIJKSTELLING
                || s == io.github.yhavinga.regelspraak.ir.ResolvedRegel.Soort.BEREKENING
                || s == io.github.yhavinga.regelspraak.ir.ResolvedRegel.Soort.INITIALISATIE
                || s == io.github.yhavinga.regelspraak.ir.ResolvedRegel.Soort.KENMERKTOEKENNING;
    }

    /**
     * Resolve a subject noun to the object type it denotes (§13.4.1). The noun may
     * name an object type directly (case-insensitive: "de groep" → Ploeg) or be a
     * feittype role whose filler is that object type ("een deelnemer" → Persoon
     * via the role "deelnemer"). A role reference is often plural
     * ("deelnemers"); a simple Dutch +s/+en plural is matched against the singular
     * role name. The phrase is tried whole first, then with leading words dropped,
     * so a multi-word role ("groep met punten") binds before its head noun does.
     * Returns null when nothing matches — the caller then makes no type claim.
     */
    public Objecttype resolveObjecttype(String naam) {
        for (String kandidaat : naamKandidaten(naam)) {
            Objecttype direct = objecttypeOpNaam(kandidaat);
            if (direct != null) return direct;
            Objecttype viaRol = objecttypeViaRol(kandidaat);
            if (viaRol != null) return viaRol;
        }
        return null;
    }

    /** The names {@link #resolveObjecttype} tries for {@code naam}, in its order: the HEAD noun phrase
     *  before any " van " navigation ("deelnemers van de groep" names the deelnemer; "van de groep" only
     *  scopes which one), then that head with leading words dropped one at a time (a multi-word role like
     *  "groep met punten" binds before its head noun does). Shared so a caller that must reconcile the
     *  WRITTEN name with what the phrase bound tries exactly the forms the binding came from. */
    public static List<String> naamKandidaten(String naam) {
        if (naam == null || naam.isBlank()) return List.of();
        String[] woorden = naam.trim().split("\\s+");
        int eind = woorden.length;
        for (int i = 0; i < woorden.length; i++) if (woorden[i].equalsIgnoreCase("van")) { eind = i; break; }
        List<String> uit = new ArrayList<>();
        for (int start = 0; start < eind; start++)
            uit.add(String.join(" ", java.util.Arrays.copyOfRange(woorden, start, eind)));
        return uit;
    }

    /** The object type DIRECTLY named {@code naam} (case-insensitively), or null — a name match
     *  only, never a feittype role. Distinct from {@link #resolveObjecttype}, which also reaches a
     *  type through a role; a feitcreatie back-reference needs the strict form to tell "de groep"
     *  (the iterated anker itself) from "de buurgroep" (a different group of the same type). */
    public Objecttype objecttypeOpNaam(String naam) {
        Objecttype exact = objecttypen.get(naam);
        if (exact != null) return exact;
        for (var e : objecttypen.entrySet())
            if (e.getKey().equalsIgnoreCase(naam)) return e.getValue();
        return null;
    }

    private Objecttype objecttypeViaRol(String naam) {
        for (Feittype ft : feittypen.values())
            for (Rol r : ft.rollen)
                if (rolMatcht(r, naam)) return objecttypeOpNaam(r.objecttypeNaam());
        return null;
    }

    /**
     * The role name to navigate from {@code houder} to {@code doelwit} — the
     * reference field on a {@code houder} fact that points at a {@code doelwit},
     * which is the role {@code doelwit} plays in a feittype that also relates a
     * {@code houder}. So from a deelnemer (Persoon) to its group (Ploeg)
     * this is "groep". Returns null when there is no such feittype or more than one
     * (ambiguous) — the caller then emits a marker rather than a wrong join. This is
     * the by-pair fallback used when the source did not name the role; prefer
     * {@link #verbindendeRolViaNaam} whenever the written role is available.
     */
    public String verbindendeRol(Objecttype houder, Objecttype doelwit) {
        if (houder == null || doelwit == null) return null;
        String gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol doel : ft.rollen)
                if (rolHeeftObjecttype(doel, doelwit) && feittypeRelateert(ft, doel, houder)) {
                    gevonden = doel.naam();
                    n++;
                }
        return n == 1 ? gevonden : null;
    }

    /**
     * Like {@link #verbindendeRol} but disambiguated by the role name the author
     * wrote: only a feittype role whose NAME matches {@code geschrevenRol} (singular,
     * declared plural, or a best-effort plural) and whose object type is
     * {@code doelwit} counts. This resolves the case the by-pair search cannot — two
     * feittypen over the same object-type pair, or one object type playing two roles —
     * because the source names exactly one. Matching by name first, object type
     * second, is the §13.4.1 reading: the written role IS the navigation. Returns null
     * when no role, or more than one, matches (still ambiguous).
     *
     * A DECLARED role name (singular or declared plural) is tried before the best-effort
     * synthesized plural, so a reference that exactly names one role is not defeated by
     * another role whose +s/+en plural happens to collide (§3.1 footnote 2: a synthesized
     * plural is outside the language and must never override a declared name).
     */
    public String verbindendeRolViaNaam(Objecttype houder, Objecttype doelwit, String geschrevenRol) {
        if (doelwit == null || geschrevenRol == null) return null;
        String exact = zoekVerbindendeRol(houder, doelwit, geschrevenRol, true);
        return exact != null ? exact : zoekVerbindendeRol(houder, doelwit, geschrevenRol, false);
    }

    /** The unique role of {@code doelwit} that {@code geschrevenRol} names in a feittype
     *  relating {@code houder}, or null when none or more than one matches. With
     *  {@code exactOnly} only declared role names count; otherwise the best-effort plural
     *  is also allowed (used only when no declared name matched). */
    private String zoekVerbindendeRol(Objecttype houder, Objecttype doelwit, String geschrevenRol, boolean exactOnly) {
        String gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol doel : ft.rollen) {
                boolean naamMatcht = exactOnly ? rolMatchtExact(doel, geschrevenRol) : rolMatcht(doel, geschrevenRol);
                if (naamMatcht && rolHeeftObjecttype(doel, doelwit) && feittypeRelateert(ft, doel, houder)) {
                    gevonden = doel.naam();
                    n++;
                }
            }
        return n == 1 ? gevonden : null;
    }

    /** Does {@code ft} relate {@code houder} through its other end of {@code doelRol}?
     *  For a normal feittype that means a DISTINCT role filled by {@code houder}; for a
     *  wederkerig feittype (§13.3.9.2) the single role relates the object type to
     *  itself, so it is enough that that role's own type is {@code houder}. A null
     *  {@code houder} imposes no constraint. */
    private boolean feittypeRelateert(Feittype ft, Rol doelRol, Objecttype houder) {
        if (houder == null) return true;
        if (ft.wederkerig) return rolHeeftObjecttype(doelRol, houder);
        return ft.rollen.stream().anyMatch(h -> h != doelRol && rolHeeftObjecttype(h, houder));
    }

    /** The object type a role named {@code geschrevenRol} reaches FROM {@code subject}:
     *  the unique type of a role that {@code geschrevenRol} names in a feittype relating
     *  {@code subject}. This is the §13.4.1.2/.3 rolnaam reading of a navigation filler —
     *  used to override a same-named objecttype that does not itself join the subject.
     *  Returns null when no role, or more than one (ambiguous), matches. */
    public Objecttype rolDoeltypeNaarSubject(Objecttype subject, String geschrevenRol) {
        if (geschrevenRol == null) return null;
        Objecttype gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol r : ft.rollen)
                if (rolMatcht(r, geschrevenRol) && feittypeRelateert(ft, r, subject)) {
                    Objecttype t = objecttypeOpNaam(r.objecttypeNaam());
                    if (t != null) { gevonden = t; n++; }
                }
        return n == 1 ? gevonden : null;
    }

    /** §8.1.7 rolcheck "{@code subject} is een &lt;geschrevenRol&gt;": the object type at the OTHER end
     *  of the feittype whose role {@code geschrevenRol} the {@code subject} FILLS — so the subject
     *  participates in that role iff its reference to that other end is set. The mirror of
     *  {@link #rolDoeltypeNaarSubject} (which navigates FROM the subject TO a role's filler; here the
     *  subject IS the filler). Returns null when no role named {@code geschrevenRol} is filled by the
     *  subject, when it is ambiguous (more than one feittype/sibling matches), or when the feittype is
     *  wederkerig (no distinct sibling to join — carded). */
    public Objecttype rolSiblingType(Objecttype subject, String geschrevenRol) {
        if (subject == null || geschrevenRol == null) return null;
        Objecttype gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            if (!ft.wederkerig)
                for (Rol r : ft.rollen)
                    if (rolMatcht(r, geschrevenRol) && rolHeeftObjecttype(r, subject))   // subject fills role r
                        for (Rol ander : ft.rollen)
                            if (ander != r) {
                                Objecttype t = objecttypeOpNaam(ander.objecttypeNaam());
                                if (t != null) { gevonden = t; n++; }
                            }
        return n == 1 ? gevonden : null;
    }

    /**
     * The subject-side join field for an aggregation whose ELEMENT role is named
     * {@code geschrevenRol} — the field on the element fact that points back at the
     * subject (the role {@code subject} plays in the feittype where {@code geschrevenRol}
     * names the element role). This disambiguates by the WRITTEN element name what the
     * by-pair {@link #verbindendeRol} cannot: when the (element, subject) pair is joined
     * by more than one feittype, the count names exactly one element role, so the join is
     * unique. Returns null when no role, or more than one, matches — the caller then keeps
     * the by-pair fallback (single-feittype case) or emits a marker.
     */
    public String verbindendeRolViaElementnaam(Objecttype subject, String geschrevenRol) {
        if (subject == null || geschrevenRol == null) return null;
        String gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol elemRol : ft.rollen)
                if (rolMatcht(elemRol, geschrevenRol) && feittypeRelateert(ft, elemRol, subject))
                    for (Rol subjRol : ft.rollen)
                        if (subjRol != elemRol && rolHeeftObjecttype(subjRol, subject)) { gevonden = subjRol.naam(); n++; }
        return n == 1 ? gevonden : null;
    }

    /** A symbolic role binding: the fact type plus the DECLARED (singular) role name —
     *  the pair the IR emitter's {@code RolBinding} extension fields carry (a plural
     *  never binds; the wire names the declared role). */
    public record RolBindingRef(String feittype, String rol) {}

    /**
     * Like {@link #verbindendeRol}, but returning the fact type together with the bound
     * role — the pair the IR emitter's symbolic role-binding extension needs. The search
     * and its uniqueness contract are IDENTICAL to {@link #verbindendeRol} (same counting,
     * so "unique" means the same thing on both paths); on top of that, a winner from a
     * wederkerig feittype is withheld — the two-role binding form does not cover the
     * single-role reciprocal shape, so it stays bindingless there rather than mis-bound.
     */
    public RolBindingRef verbindendeRolBinding(Objecttype houder, Objecttype doelwit) {
        if (houder == null || doelwit == null) return null;
        RolBindingRef gevonden = null;
        boolean wederkerig = false;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol doel : ft.rollen)
                if (rolHeeftObjecttype(doel, doelwit) && feittypeRelateert(ft, doel, houder)) {
                    gevonden = new RolBindingRef(ft.naam, doel.naam());
                    wederkerig = ft.wederkerig;
                    n++;
                }
        return n == 1 && !wederkerig ? gevonden : null;
    }

    /**
     * Whether {@code geschreven} NAMES the role {@code rb} binds — its declared singular, its declared
     * plural, or (only when no plural is declared) the best-effort plural. The written-name side of a
     * binding the by-PAIR search produced: {@link #verbindendeRolBinding} never reads the source, so a
     * pair that is unique still says nothing about what the author wrote, and a binding whose role the
     * sentence does not name is a guess about WHICH collection is folded, not a resolution of it.
     * False for an unknown fact type or a role the fact type does not carry.
     */
    public boolean rolGenoemdAls(RolBindingRef rb, String geschreven) {
        if (rb == null || geschreven == null) return false;
        Feittype ft = feittypen.get(rb.feittype());
        if (ft == null) return false;
        for (Rol r : ft.rollen)
            if (r.naam().equals(rb.rol())) return rolMatcht(r, geschreven);
        return false;
    }

    /**
     * Like {@link #verbindendeRolViaNaam} but returning the fact type together with the reached
     * (far) role — the pair the IR emitter's {@code navigatieBindingen} extension carries for a
     * by-name hop. Only a DECLARED role name (singular or declared plural) binds: the best-effort
     * plural is deliberately NOT mirrored (a binding names what the author declared, the same
     * narrowing {@link #elementRolBinding} makes and the uchar verifier's by-name derivation runs),
     * so a guessed plural stays bindingless rather than bound to a spelling the model never defined.
     * Null when no role, or more than one, matches.
     */
    public RolBindingRef verbindendeRolViaNaamBinding(Objecttype houder, Objecttype doelwit, String geschrevenRol) {
        if (doelwit == null || geschrevenRol == null) return null;
        RolBindingRef gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol doel : ft.rollen)
                if (rolMatchtExact(doel, geschrevenRol) && rolHeeftObjecttype(doel, doelwit)
                        && feittypeRelateert(ft, doel, houder)) {
                    gevonden = new RolBindingRef(ft.naam, doel.naam());
                    n++;
                }
        return n == 1 ? gevonden : null;
    }

    /**
     * The ELEMENT-role binding of an instance fold, disambiguated by the written element
     * name — the {@link #verbindendeRolViaElementnaam} search capturing the fact type and
     * the ELEMENT role instead of the subject's back-reference, with one deliberate
     * narrowing: only a DECLARED name (singular or declared plural) matches, never the
     * best-effort plural — a binding must name what the author declared, so a guessed
     * plural stays bindingless rather than bound to a spelling the model never defined.
     * Null when no role, or more than one, matches.
     */
    public RolBindingRef elementRolBinding(Objecttype subject, String geschrevenRol) {
        ElementBindingRef eb = elementBinding(subject, geschrevenRol);
        return eb == null ? null : new RolBindingRef(eb.feittype(), eb.elementRol());
    }

    /** A symbolic navigation binding capturing BOTH ends of one element-name hop: the fact type,
     *  the role the {@code subject} fills (the near/back-reference side), and the ELEMENT role
     *  {@code geschrevenRol} names (the far side) — the {@link #verbindendeRolViaElementnaam} search
     *  returning the whole pair, so a §13.4.7 feitcreatie can materialise its symbolic hops
     *  (created relation, join, tussenstap) at resolution time. Only a DECLARED role name binds
     *  (never the best-effort plural). Null when no role, or more than one, matches. */
    public record ElementBindingRef(String feittype, String subjectRol, String elementRol) {}

    public ElementBindingRef elementBinding(Objecttype subject, String geschrevenRol) {
        if (subject == null || geschrevenRol == null) return null;
        ElementBindingRef gevonden = null;
        int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol elemRol : ft.rollen)
                if (rolMatchtExact(elemRol, geschrevenRol) && feittypeRelateert(ft, elemRol, subject))
                    for (Rol subjRol : ft.rollen)
                        if (subjRol != elemRol && rolHeeftObjecttype(subjRol, subject)) {
                            gevonden = new ElementBindingRef(ft.naam, subjRol.naam(), elemRol.naam());
                            n++;
                        }
        return n == 1 ? gevonden : null;
    }

    /**
     * The written role name to record on a navigation reference: the {@code noun}
     * itself when it denotes a feittype role (and not an object type by that name),
     * else null. Kept as written so {@link #verbindendeRolViaNaam}'s {@code rolMatcht}
     * can still reconcile a plural ("deelnemers") against the declared singular.
     */
    public String navigatieRolNaam(String noun) {
        if (noun == null || noun.isBlank()) return null;
        if (objecttypeOpNaam(noun) != null) return null; // names an object type directly — not navigation
        for (Feittype ft : feittypen.values())
            for (Rol r : ft.rollen)
                if (rolMatcht(r, noun)) return noun;
        return null;
    }

    private boolean rolHeeftObjecttype(Rol r, Objecttype ot) {
        return objecttypeOpNaam(r.objecttypeNaam()) == ot || r.objecttypeNaam().equalsIgnoreCase(ot.naam);
    }

    private static boolean rolMatcht(Rol r, String naam) {
        return rolMatchtExact(r, naam) || rolMatchtBestEffort(r, naam);
    }

    /** A role name the model DECLARES: the singular role name or its declared (mv:)
     *  plural. These are the forms the language actually defines. */
    private static boolean rolMatchtExact(Rol r, String naam) {
        return r.naam().equalsIgnoreCase(naam)
                || (r.meervoud() != null && r.meervoud().equalsIgnoreCase(naam));
    }

    /** A best-effort plural match used ONLY when no plural is declared: RegelSpraak
     *  defines no plural morphology — §3.1 footnote 2 states derivation is ALEF-specific,
     *  OUTSIDE the language — so this must never defeat a declared form, and callers that
     *  disambiguate by name (see {@link #verbindendeRolViaNaam}) try the exact forms
     *  first. (See docs/grammar-divergences.md.) */
    private static boolean rolMatchtBestEffort(Rol r, String naam) {
        if (r.meervoud() != null) return false;
        if (naam.equalsIgnoreCase(r.naam() + "s") || naam.equalsIgnoreCase(r.naam() + "en")) return true;
        String kopEnkelvoud = naam.replaceFirst("^(\\S+?)(?:s|en)(\\s)", "$1$2");
        return kopEnkelvoud.equalsIgnoreCase(r.naam());
    }

    /**
     * Resolve a unit spelling to the Eenheid declared in some system. A unit's
     * abbreviation ("jr"), word ("jaar") and plural ("jaren") all denote the same
     * unit, so the spelling is matched as an abbreviation and, normalised to its
     * singular time-unit word, as an alias of a declared unit. Resolving "jaren" and
     * "jr" to the same system makes them inter-convertible (same-system rule), which
     * is what lets a duration "in hele jaren" assign to an attribute "met eenheid jr".
     */
    public Eenheid resolveEenheid(String spelling) {
        String norm = normaliseerTijdseenheid(spelling);
        for (Eenheidstelsel s : eenheidstelsels.values()) {
            Eenheid e = s.eenheden.get(spelling);
            if (e == null) e = s.aliassen.get(norm);
            if (e != null) return e;
        }
        return new Eenheid(norm, null); // unknown system; normalised so plural/singular still unify
    }

    /** Singular form of a built-in time unit (the lexer's nine units), else unchanged. */
    public static String normaliseerTijdseenheid(String s) {
        return switch (s == null ? "" : s.toLowerCase()) {
            case "jaren" -> "jaar";           case "maanden" -> "maand";
            case "dagen" -> "dag";            case "weken" -> "week";
            case "kwartalen" -> "kwartaal";   case "uren" -> "uur";
            case "minuten" -> "minuut";       case "seconden" -> "seconde";
            case "milliseconden" -> "milliseconde";
            default -> s;
        };
    }

    /** An object type: its attributes (name -> datatype) and kenmerk names. */
    public static final class Objecttype {
        public final String naam;
        public boolean bezield;
        /** The declared "(mv: …)" plural (§13.2.29), or null. Used to resolve a plural-form reference
         *  ("alle Natuurlijke personen" in a §8.1.6 uniciteitscontrole) back to this type by its exact
         *  declared spelling, without guessing irregular noun/adjective plural morphology. */
        public String meervoud;
        public final Map<String, Datatype> attributen = new LinkedHashMap<>();
        /** An attribute's declared "(mv: …)" plural (§3.3 naamwoord, footnote 2), keyed by its singular
         *  naam — the spec-sanctioned source for the plural a §8.1.6 uniciteitscontrole writes, so it is
         *  matched exactly (incl. irregular plurals) ahead of any synthesised form. */
        public final Map<String, String> attribuutMeervoud = new LinkedHashMap<>();
        /** §13.3.7 the dimensions an attribute is "gedimensioneerd met", in declared order, keyed by its
         *  naam — empty/absent for a scalar attribute. A gedimensioneerd attribuut holds one value per
         *  combination of one label from each of its dimensions (§3.6: "vier mogelijke waardes"), so a
         *  reference must pin every dimension and an aggregation ranges over a dimension's label set. */
        public final Map<String, List<String>> attribuutDimensies = new LinkedHashMap<>();
        /** §13.3.6 the granularity of a TEMPORAL attribute's tijdlijn ("de premie Bedrag voor elk jaar" -&gt;
         *  JAAR), keyed by its naam — absent for an ordinary single-value attribute. A temporal attribute
         *  holds one value per period of this granularity (§5.1); recording the declaration here is the
         *  IR-shape foundation for the Phase-7 value model that will lower the periods (knip-merge,
         *  tijdsevenredig, gedurende). The startpunt is not yet captured. */
        public final Map<String, Granulariteit> attribuutGranulariteit = new LinkedHashMap<>();
        /** §13.3.6.7 the period COUNT of a MULTI-period tijdlijn ("voor elke 5 maanden" -&gt; 5), keyed by the
         *  attribute naam — absent (count 1) for a single-period "voor elke maand". Each tijdvak then spans
         *  {@code aantal} units of {@link #attribuutGranulariteit}, so a temporal fold must divide by it. */
        public final Map<String, Integer> attribuutTijdlijnAantal = new LinkedHashMap<>();
        /** §13.3.6.9 the NAMED tijdlijn an attribute rides ("voor elke &lt;tijdlijnnaam&gt;"), keyed by the
         *  attribute naam — absent for the inline forms ("voor elke maand"). Recorded so the §9.9 machinery
         *  can see whether that tijdlijn has a variabel startpunt and find its anchor. */
        public final Map<String, String> attribuutTijdlijnNaam = new LinkedHashMap<>();
        public final List<String> kenmerken = new ArrayList<>();
        /** §13.3.6 the granularity of a temporal kenmerk's tijdlijn ("is duurzaam kenmerk voor elke dag"),
         *  keyed by its naam — the kenmerk analogue of {@link #attribuutGranulariteit}. */
        public final Map<String, Granulariteit> kenmerkGranulariteit = new LinkedHashMap<>();
        /** §13.3.6.7 the kenmerk analogue of {@link #attribuutTijdlijnAantal}. */
        public final Map<String, Integer> kenmerkTijdlijnAantal = new LinkedHashMap<>();
        /** §3.5 the soort of each kenmerk, keyed by its naam — drives the §8.1.8 predicate form: a
         *  BIJVOEGLIJK kenmerk is written "is &lt;k&gt;", a BEZITTELIJK one "heeft het &lt;k&gt;", a
         *  ZELFSTANDIG one "is een &lt;k&gt;" (so only a ZELFSTANDIG kenmerk binds the EEN-form). */
        public final Map<String, KenmerkSoort> kenmerkSoorten = new LinkedHashMap<>();
        public Objecttype(String naam) { this.naam = naam; }

        /** §3.5 kenmerk soort: bijvoeglijk ("is &lt;k&gt;"), bezittelijk ("heeft het &lt;k&gt;") or
         *  zelfstandig/noch-noch ("is een &lt;k&gt;"). An untagged declaration is zelfstandig. */
        public enum KenmerkSoort { BIJVOEGLIJK, BEZITTELIJK, ZELFSTANDIG }

        /** The declared datatype of attribute {@code naam}, matched case-INsensitively —
         *  object-type names already resolve this way (see {@link #objecttypeOpNaam}), so a
         *  reference that differs only in case (casus source writes both "datum-tijd" and
         *  "Datum-tijd") binds the same attribute. Null when this type declares no such
         *  attribute. */
        public Datatype attribuut(String naam) {
            Datatype exact = attributen.get(naam);
            if (exact != null) return exact;
            for (var e : attributen.entrySet())
                if (e.getKey().equalsIgnoreCase(naam)) return e.getValue();
            return null;
        }

        /** The §13.3.7 dimensions of attribute {@code naam} (case-insensitively), in declared order,
         *  or an empty list when the attribute is scalar or undeclared. */
        public List<String> dimensiesVan(String naam) {
            List<String> exact = attribuutDimensies.get(naam);
            if (exact != null) return exact;
            for (var e : attribuutDimensies.entrySet())
                if (e.getKey().equalsIgnoreCase(naam)) return e.getValue();
            return List.of();
        }

        /** Does this type declare a member named {@code naam} (an attribute or a kenmerk),
         *  case-insensitively? Used to decide whether a navigation can emit a getter for it
         *  or must stay an honest marker. */
        public boolean heeftLid(String naam) {
            return attribuut(naam) != null || kenmerken.stream().anyMatch(k -> k.equalsIgnoreCase(naam));
        }

        /** The §3.5 soort of kenmerk {@code naam} (case-insensitively), or null when no such kenmerk is
         *  declared. An untagged kenmerk is {@link KenmerkSoort#ZELFSTANDIG}. */
        public KenmerkSoort kenmerkSoort(String naam) {
            KenmerkSoort exact = kenmerkSoorten.get(naam);
            if (exact != null) return exact;
            for (var e : kenmerkSoorten.entrySet())
                if (e.getKey().equalsIgnoreCase(naam)) return e.getValue();
            return null;
        }
    }

    /** §13.3.8 a declared parameter. {@code granulariteit} is its §13.3.6 tijdlijn ("kan jaarlijks wijzigen", written
     *  "voor elk jaar") when the parameter is tijdsafhankelijk — its runtime value is then a timeline (a
     *  {@code List<Segment>}) rather than a scalar; null for an ordinary scalar parameter. */
    public record Parameter(String naam, Datatype datatype, Granulariteit granulariteit) {
        public Parameter(String naam, Datatype datatype) { this(naam, datatype, null); }
    }

    public static final class Feittype {
        public final String naam;
        public final List<Rol> rollen = new ArrayList<>();
        public boolean wederkerig;
        public Feittype(String naam) { this.naam = naam; }
    }
    /** A feittype role. `meervoud` is the plural the model DECLARES via "(mv: …)"
     *  (§13.3.9.1), or null when none is declared — the resolver matches plural
     *  references against the declared form, never a synthesised one. `meervoudig` is the
     *  role's cardinality from the feittype's cardinaliteit line (§13.3.9.1): true when this
     *  role is the "meerdere" side (a navigation TO it yields many), false when "één". */
    public record Rol(String naam, String objecttypeNaam, String meervoud, boolean meervoudig) {
        public Rol(String naam, String objecttypeNaam) { this(naam, objecttypeNaam, null, false); }
        public Rol(String naam, String objecttypeNaam, String meervoud) { this(naam, objecttypeNaam, meervoud, false); }
    }

    /** Whether navigating from {@code houder} to {@code doelwit} through the role named
     *  {@code rolNaam} crosses a TO-MANY (meerdere) role — i.e. the field on {@code houder}
     *  would be a collection, so a scalar getter chain is wrong and the read needs an
     *  aggregation (§13.3.9.1 cardinaliteit). Finds the unique {@code doelwit} role that
     *  {@code rolNaam} names in a feittype relating {@code houder} and returns its
     *  cardinality; false when single, unknown, or not uniquely found (fail-open: never
     *  refuse a navigation we cannot prove is to-many). */
    public boolean naarVeelRol(Objecttype houder, Objecttype doelwit, String rolNaam) {
        if (doelwit == null || rolNaam == null) return false;
        Rol gevonden = null; int n = 0;
        for (Feittype ft : feittypen.values())
            for (Rol r : ft.rollen)
                if (r.naam().equalsIgnoreCase(rolNaam) && rolHeeftObjecttype(r, doelwit) && feittypeRelateert(ft, r, houder)) {
                    gevonden = r; n++;
                }
        return n == 1 && gevonden.meervoudig();
    }

    /** §13.4.6/§9 whether {@code subject} can relate to MORE THAN ONE instance of the created type
     *  {@code nieuw} — the cardinality that decides an objectcreatie's idempotence guard shape: a 1:1
     *  target ({@code false}) can be keyed on the LINKED-INSTANCE's absence (a fact-base-derived check on
     *  the created type itself); a to-many target ({@code true}) needs a per-rule per-subject control fact
     *  (a linked instance already existing is legitimate). Reads the cardinality of {@code nieuw}'s own role
     *  in the unique feittype relating the pair — {@link #verbindendeRol}(subject, nieuw) names that role,
     *  {@link #naarVeelRol} reads its meervoudig. Fail-open to to-many ({@code true}) when the role cannot be
     *  uniquely resolved: the control-fact guard is always correct (at-most-once), only less tight than the
     *  1:1 instance check, so an unprovable pair is never wrongly treated as 1:1. */
    public boolean subjectNaarVeelCreatie(Objecttype subject, Objecttype nieuw) {
        if (subject == null || nieuw == null) return true;   // no cardinality knowledge -> the safe (control-fact) path
        String nieuwRol = verbindendeRol(subject, nieuw);    // the role nieuw plays, from the subject's side
        if (nieuwRol == null) return true;                   // ambiguous / self-pair -> control-fact path
        return naarVeelRol(subject, nieuw, nieuwRol);
    }

    /** Package-visible role-name match (singular, declared plural, or best-effort plural),
     *  used by {@link io.github.yhavinga.regelspraak.resolver.DefinitionResolver} to map a cardinaliteit
     *  noun onto a role. */
    public static boolean rolNaamMatcht(Rol r, String woord) { return rolMatcht(r, woord); }

    /** One resolved hop of a §13.4.1 multi-hop navigation chain ("de X van de Y van de Z"):
     *  the DECLARED role name to navigate through from the holder, and the object type it reaches. */
    public record KetenHop(String rol, Objecttype doel) {}

    /** Resolve one written chain filler (a role name or an objecttype name, article already stripped)
     *  into a to-ONE hop from {@code houder} — or null when it does not resolve uniquely or crosses a
     *  to-many role, in which case the caller declines the WHOLE chain (an honest marker, never a wrong
     *  join). A filler that names an objecttype directly takes the by-pair role (§13.4.1.2/.3: the
     *  objecttype reading), any other filler the by-name role reading. */
    public KetenHop ketenHop(Objecttype houder, String geschreven) {
        if (houder == null || geschreven == null) return null;
        Objecttype alsType = objecttypeOpNaam(geschreven);   // STRICT name match — a role-named filler must take the role reading
        String rol; Objecttype doel;
        if (alsType != null) { doel = alsType; rol = verbindendeRol(houder, alsType); }
        else {
            doel = rolDoeltypeNaarSubject(houder, geschreven);
            rol = doel == null ? null : verbindendeRolViaNaam(houder, doel, geschreven);
        }
        if (rol == null || doel == null || naarVeelRol(houder, doel, rol)) return null;
        return new KetenHop(rol, doel);
    }

    /** Re-walk a RESOLVED navigation chain (declared role names, subject-outward) from {@code van},
     *  returning the object type reached per hop (size == pad size) — or null when any hop does not
     *  resolve to-one from there. The transpilers re-validate a chain against their actual pattern
     *  subject with this before emitting getters, so a chain resolved against one subject can never
     *  silently lower against another. */
    public List<Objecttype> volgKeten(Objecttype van, List<String> pad) {
        List<Objecttype> bereikt = new ArrayList<>();
        Objecttype cur = van;
        for (String rol : pad) {
            if (cur == null) return null;
            Objecttype doel = rolDoeltypeNaarSubject(cur, rol);
            if (doel == null || naarVeelRol(cur, doel, rol)) return null;
            bereikt.add(doel);
            cur = doel;
        }
        return bereikt;
    }

    public static final class Eenheidstelsel {
        public final String naam;
        public final Map<String, Eenheid> eenheden = new LinkedHashMap<>(); // abbreviation -> unit
        public final Map<String, Eenheid> aliassen = new LinkedHashMap<>(); // unit word / plural (normalised) -> unit
        public Eenheidstelsel(String naam) { this.naam = naam; }
    }

    /** §13.3.6.2/§13.3.6.10 the period unit of a tijdlijn — the granularity at which a temporal value
     *  changes (one value per dag / week / maand / kwartaal / jaar). Both the inline attribute form
     *  ("voor elk jaar") and a named {@link Tijdlijn} definition ("heeft tijdvakken van een jaar")
     *  reduce to one of these. */
    public enum Granulariteit { DAG, WEEK, MAAND, KWARTAAL, JAAR }

    /** §13.3.6.10 a named timeline: its naam, its {@link Granulariteit} and period {@code aantal} (so a "voor
     *  elke &lt;naam&gt;" attribute reference adopts BOTH — "heeft tijdvakken van 5 maanden" is granularity MAAND,
     *  count 5), and whether `variabelStartpunt` is bound by a §13.4.12 startpuntbepaling. */
    public record Tijdlijn(String naam, boolean variabelStartpunt, Granulariteit granulariteit, int aantal) {}

    /** §13.3.7 a dimension and its ordered label values. {@code voorzetsel} is the §3.6 read preposition
     *  for the suffix form ("na het attribuut met voorzetsel <vz>": a cell reads "&lt;attribuut&gt; &lt;vz&gt;
     *  &lt;label&gt;"), or null for the prefix form ("voor het attribuut zonder voorzetsel": "&lt;label&gt;
     *  &lt;attribuut&gt;"). */
    public record Dimensie(String naam, String naamMeervoud, String voorzetsel, List<String> waarden) {}

    /** The §13.3.7 dimension named {@code naam} (singular) case-insensitively, or null. */
    public Dimensie dimensieOpNaam(String naam) {
        if (naam == null) return null;
        Dimensie exact = dimensies.get(naam);
        if (exact != null) return exact;
        for (Dimensie d : dimensies.values())
            if (d.naam().equalsIgnoreCase(naam)) return d;
        return null;
    }

    /** The §13.3.7 dimension whose PLURAL name (dimensienaammeervoud) is {@code meervoud},
     *  case-insensitively, or null — the §13.4.17.52-.54 over-forms reference a dimension by its
     *  plural ("over alle jaardimensies", "over de jaardimensies vanaf … t/m …"). */
    public Dimensie dimensieOpMeervoud(String meervoud) {
        if (meervoud == null) return null;
        for (Dimensie d : dimensies.values())
            if (d.naamMeervoud() != null && d.naamMeervoud().equalsIgnoreCase(meervoud)) return d;
        return null;
    }

    /** A rule shell (name + the parse subtree); type-checking annotates diagnostics. */
    public record Regel(String naam, String soort) {}

    public enum Severity { ERROR, WARNING, INFO }
    public record Diagnostic(Severity severity, String message, int line) {
        public boolean isError() { return severity == Severity.ERROR; }
        @Override public String toString() { return severity + " (line " + line + "): " + message; }
    }
}
