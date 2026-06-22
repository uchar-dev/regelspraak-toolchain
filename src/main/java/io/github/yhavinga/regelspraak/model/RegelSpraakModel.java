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
    public final List<Tijdlijn> tijdlijnen = new ArrayList<>();
    public final Map<String, Dimensie> dimensies = new LinkedHashMap<>();
    public final List<Regel> regels = new ArrayList<>();
    /** Resolved rule IR (typed expressions + bound references) — the transpiler's input. */
    public final List<io.github.yhavinga.regelspraak.ir.ResolvedRegel> resolvedRegels = new ArrayList<>();
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
    public final List<Diagnostic> diagnostics = new ArrayList<>();

    public boolean isOk() { return diagnostics.stream().noneMatch(Diagnostic::isError); }
    public List<Diagnostic> errors() { return diagnostics.stream().filter(Diagnostic::isError).toList(); }

    /**
     * Resolve a subject noun to the object type it denotes (§13.4.1). The noun may
     * name an object type directly (case-insensitive: "de vlucht" → Vlucht) or be a
     * feittype role whose filler is that object type ("een passagier" → Natuurlijk
     * persoon via the role "passagier"). A role reference is often plural
     * ("passagiers"); a simple Dutch +s/+en plural is matched against the singular
     * role name. The phrase is tried whole first, then with leading words dropped,
     * so a multi-word role ("reis met treinmiles") binds before its head noun does.
     * Returns null when nothing matches — the caller then makes no type claim.
     */
    public Objecttype resolveObjecttype(String naam) {
        if (naam == null || naam.isBlank()) return null;
        // The entity is the HEAD noun phrase, before any " van " navigation:
        // in "passagiers van de reis" the attribute owner is the passagier, while
        // "van de reis" only scopes which one. So cut at the first 'van', then within
        // the head try the whole phrase before its tail (a multi-word role like
        // "reis met treinmiles" binds before a leading qualifier is dropped).
        String[] woorden = naam.trim().split("\\s+");
        int eind = woorden.length;
        for (int i = 0; i < woorden.length; i++) if (woorden[i].equalsIgnoreCase("van")) { eind = i; break; }
        for (int start = 0; start < eind; start++) {
            String kandidaat = String.join(" ", java.util.Arrays.copyOfRange(woorden, start, eind));
            Objecttype direct = objecttypeOpNaam(kandidaat);
            if (direct != null) return direct;
            Objecttype viaRol = objecttypeViaRol(kandidaat);
            if (viaRol != null) return viaRol;
        }
        return null;
    }

    /** The object type DIRECTLY named {@code naam} (case-insensitively), or null — a name match
     *  only, never a feittype role. Distinct from {@link #resolveObjecttype}, which also reaches a
     *  type through a role; a feitcreatie back-reference needs the strict form to tell "de vlucht"
     *  (the iterated anker itself) from "de aansluitvlucht" (a different flight of the same type). */
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
     * {@code houder}. So from a passagier (Natuurlijk persoon) to its flight (Vlucht)
     * this is "reis". Returns null when there is no such feittype or more than one
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

    /**
     * The written role name to record on a navigation reference: the {@code noun}
     * itself when it denotes a feittype role (and not an object type by that name),
     * else null. Kept as written so {@link #verbindendeRolViaNaam}'s {@code rolMatcht}
     * can still reconcile a plural ("passagiers") against the declared singular.
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
         *  reference that differs only in case (TOKA writes both "datum-tijd" and
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

    public record Parameter(String naam, Datatype datatype) {}

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

    /** Package-visible role-name match (singular, declared plural, or best-effort plural),
     *  used by {@link io.github.yhavinga.regelspraak.resolver.DefinitionResolver} to map a cardinaliteit
     *  noun onto a role. */
    public static boolean rolNaamMatcht(Rol r, String woord) { return rolMatcht(r, woord); }

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

    /** §13.3.6.10 a named timeline: its naam, its {@link Granulariteit} (so a "voor elke &lt;naam&gt;"
     *  attribute reference can adopt it), and whether `variabelStartpunt` is bound by a §13.4.12
     *  startpuntbepaling. */
    public record Tijdlijn(String naam, boolean variabelStartpunt, Granulariteit granulariteit) {}

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
