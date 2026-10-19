package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.FactType;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.Role;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Namen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads the names of a request. A name — an object type, an attribute, a characteristic, a fact type, a role, a
 * parameter, an enumeration value — may be its declaration's canonical name or its designation in any vocabulary the
 * model declares (IR-SPEC ch. 2.12), mixed within one request, compared case-insensitively, and it is read within the
 * scope of its kind, the scopes of uchar's WF-118: the attributes and the characteristics of an object type together
 * with the ones it inherits, the roles of a fact type, the values of the enumeration's domain, the other kinds
 * model-wide. A role written at an instance names no fact type, so it is read among the roles of the fact types the
 * instance's type takes part in. The canonical reading is the one the service always had; a designation is read
 * through uchar's reading entrance ({@link RegelSpraakModel#benoemd}).
 *
 * <p>One declaration found is the reading. Two different ones — in two vocabularies, or a designation in one against a
 * canonical name — is {@link Ambiguous}: the service does not guess, and its sentence names both readings and asks
 * for the input vocabulary. With the input vocabulary pinned, a name is read only as an effective name in that
 * vocabulary, or, pinned to {@link Naming#CANONICAL}, only as a canonical name; that resolves the ambiguity.
 */
final class NameReader {

    /** The source of a canonical reading, beside the vocabularies. */
    private static final String CANONICAL = Naming.CANONICAL;

    private final RegelSpraakModel model;
    /** The pinned input vocabulary in its declared spelling, {@link #CANONICAL}, or null: every reading. */
    private final String pinned;

    /** @param vastgezet the input vocabulary the request pins, already checked against the model
     *                   ({@link Naming#van}), or null to read every name liberally */
    NameReader(RegelSpraakModel model, String pinned) {
        this.model = model;
        this.pinned = pinned == null ? null : Naming.van(model, pinned).choice();
    }

    /** The outcome of reading one name. */
    sealed interface Reading permits Found, Ambiguous, Unknown {}

    /** The name read as {@code declaration}. {@code naam} is the name the service goes on with: the written name where
     *  it reads canonically, so a canonical request is read exactly as before, and the declaration's canonical name
     *  where only a designation reads it. */
    record Found(Designatable declaration, String name) implements Reading {}

    /** The name reads as more than one declaration; {@code sentence} names each reading and asks for the vocabulary. */
    record Ambiguous(String sentence) implements Reading {}

    /** No declaration of its kind and scope carries the name. */
    record Unknown() implements Reading {}

    Reading objectType(String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead()) {
            ObjectType ot = DescriptorLoader.objectType(model, name);
            if (ot != null) t.add(new Hit(Designatable.objectType(ot.name), CANONICAL));
        }
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.OBJECTTYPE, null, name));
        return verdict(name, t);
    }

    Reading attribute(ObjectType ot, String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead()) canonical(t, Naming.attributeOf(ot, name));
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.ATTRIBUUT, ot.name, name));
        return verdict(name, t);
    }

    Reading characteristic(ObjectType ot, String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead()) canonical(t, Naming.characteristicOf(ot, name));
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.KENMERK, ot.name, name));
        return verdict(name, t);
    }

    Reading parameter(String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead()) {
            RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, name);
            if (p != null) t.add(new Hit(Designatable.parameter(p.name()), CANONICAL));
        }
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.PARAMETER, null, name));
        return verdict(name, t);
    }

    Reading factType(String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead()) {
            FactType ft = model.factTypes.get(name);
            if (ft == null)
                for (FactType f : model.factTypes.values()) if (f.name.equalsIgnoreCase(name)) { ft = f; break; }
            if (ft != null) t.add(new Hit(Designatable.factType(ft.name), CANONICAL));
        }
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.FEITTYPE, null, name));
        return verdict(name, t);
    }

    /** A role of fact type {@code ft}, as a relation of the request names it: by its declared name. */
    Reading roleIn(FactType ft, String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead())
            for (Role r : ft.roles)
                if (r.name().equalsIgnoreCase(name)) t.add(new Hit(Designatable.role(ft.name, r.name()), CANONICAL));
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.ROL, ft.name, name));
        return verdict(name, t);
    }

    /** A role written at an instance of {@code ot}: a far role of a fact type {@code ot} takes part in, canonically by
     *  its name, declared plural or best-effort plural, as uchar reads a written role. */
    Reading roleFrom(ObjectType ot, String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead())
            for (FactType ft : model.factTypes.values())
                for (Role r : ft.roles)
                    if (RegelSpraakModel.roleNameMatches(r, name) && Naming.reachable(model, ft, r, ot)
                            && model.objectTypeByName(r.objectTypeName()) != null)
                        t.add(new Hit(Designatable.role(ft.name, r.name()), CANONICAL));
        for (FactType ft : model.factTypes.values())
            vocabularies(t, v -> model.namedElement(v, Designatable.Kind.ROL, ft.name, name)
                    .filter(b -> reachable(ft, b.name(), ot)));
        return verdict(name, t);
    }

    /** A value of enumeration {@code e}: canonically compared as uchar compares declared names ({@link Namen#norm}). */
    Reading value(Datatype.Enumeration e, String name) {
        List<Hit> t = new ArrayList<>();
        if (canonicalRead())
            for (String w : e.values())
                if (Namen.norm(w).equals(Namen.norm(name)))
                    t.add(new Hit(Designatable.enumerationValue(e.domain(), w), CANONICAL));
        vocabularies(t, v -> model.namedElement(v, Designatable.Kind.ENUMERATIEWAARDE, e.domain(), name));
        return verdict(name, t);
    }

    /** The clause an unknown name's refusal ends with when the input vocabulary is pinned: where the name was read. */
    String pinnedClause() {
        return pinned == null ? "" : " (invoervocabulaire '" + pinned + "')";
    }

    /** The pinned input vocabulary in its declared spelling, {@link Naming#CANONICAL}, or null. */
    String pinned() { return pinned; }

    /** Whether the input vocabulary is pinned to a declared vocabulary: a canonical name then reads nothing. */
    boolean pinnedInVocabulary() {
        return pinned != null && !pinned.equals(CANONICAL);
    }

    /** The values of {@code e} as the pinned vocabulary names them (only meaningful with {@link #pinnedInVocabulary}). */
    List<String> values(Datatype.Enumeration e) {
        Naming n = Naming.van(model, pinned);
        return e.values().stream().map(w -> n.value(e, w)).toList();
    }

    /** A description of declaration {@code b} for a sentence, by its canonical name. */
    static String description(Designatable b) {
        return switch (b.kind()) {
            case OBJECTTYPE -> "het objecttype '" + b.name() + "'";
            case ATTRIBUUT -> "het attribuut '" + b.name() + "' van '" + b.owner() + "'";
            case KENMERK -> "het kenmerk '" + b.name() + "' van '" + b.owner() + "'";
            case FEITTYPE -> "het feittype '" + b.name() + "'";
            case ROL -> "de rol '" + b.name() + "' van feittype '" + b.owner() + "'";
            case ENUMERATIEWAARDE -> "de waarde '" + b.name() + "' van domein '" + b.owner() + "'";
            case PARAMETER -> "de parameter '" + b.name() + "'";
            case VORM -> "de regel '" + b.name() + "'";
            case REGELGROEP -> "de regelgroep '" + b.name() + "'";
        };
    }

    /** Whether two elements name one declaration: the same kind, owner and name under uchar's name identity. */
    static boolean same(Designatable a, Designatable b) {
        return a.kind() == b.kind() && a.name().equalsIgnoreCase(b.name())
                && (a.owner() == null ? b.owner() == null
                    : b.owner() != null && a.owner().equalsIgnoreCase(b.owner()));
    }

    // ---------------------------------------------------------------- the reading

    /** One reading of a name: the declaration and where it was read, {@link #CANONICAL} or a vocabulary. */
    private record Hit(Designatable declaration, String source) {}

    private boolean canonicalRead() {
        return pinned == null || pinned.equals(CANONICAL);
    }

    private void canonical(List<Hit> t, Designatable b) {
        if (b != null) t.add(new Hit(b, CANONICAL));
    }

    /** The reading of every vocabulary the name may be read in: the pinned one, or all the model declares. */
    private void vocabularies(List<Hit> t, Function<String, Optional<Designatable>> read) {
        if (pinned != null && pinned.equals(CANONICAL)) return;
        for (RegelSpraakModel.Vocabulary v : model.vocabularies)
            if (pinned == null || v.name().equals(pinned))
                read.apply(v.name()).ifPresent(b -> t.add(new Hit(b, v.name())));
    }

    private boolean reachable(FactType ft, String role, ObjectType ot) {
        for (Role r : ft.roles) if (r.name().equals(role)) return Naming.reachable(model, ft, r, ot);
        return false;
    }

    /** The readings of {@code naam} judged. Several canonical readings and nothing else is the refusal uchar's own
     *  reading of a written role gives (a name two reachable roles answer to names neither), so it stays unknown. */
    private Reading verdict(String name, List<Hit> hits) {
        List<Designatable> declarations = new ArrayList<>();
        List<List<String>> sources = new ArrayList<>();
        for (Hit t : hits) {
            int i = 0;
            while (i < declarations.size() && !same(declarations.get(i), t.declaration())) i++;
            if (i == declarations.size()) { declarations.add(t.declaration()); sources.add(new ArrayList<>()); }
            if (!sources.get(i).contains(t.source())) sources.get(i).add(t.source());
        }
        if (declarations.isEmpty()) return new Unknown();
        if (declarations.size() == 1) {
            Designatable b = declarations.get(0);
            return new Found(b, sources.get(0).contains(CANONICAL) ? name : b.name());
        }
        if (hits.stream().allMatch(t -> t.source().equals(CANONICAL))) return new Unknown();
        List<String> reads = new ArrayList<>();
        List<String> choices = new ArrayList<>();
        for (int i = 0; i < declarations.size(); i++) {
            reads.add(where(sources.get(i)) + " " + description(declarations.get(i)));
            for (String source : sources.get(i)) if (!choices.contains(source)) choices.add(source);
        }
        return new Ambiguous("de naam '" + name + "' is dubbelzinnig: " + String.join(", ", reads)
                + "; zet het vocabulaire erbij met de queryparameter invoervocabulaire (" + enumeration(choices, "of") + ")");
    }

    /** Where a declaration was read: "als canonieke naam", "in het vocabulaire 'v'", or both. */
    private static String where(List<String> sources) {
        List<String> vocabularies = sources.stream().filter(b -> !b.equals(CANONICAL)).map(b -> "'" + b + "'").toList();
        String inVocabulary = vocabularies.isEmpty() ? null
                : (vocabularies.size() == 1 ? "in het vocabulaire " : "in de vocabulaires ") + enumeration(vocabularies, "en");
        if (!sources.contains(CANONICAL)) return inVocabulary;
        return inVocabulary == null ? "als canonieke naam" : "als canonieke naam en " + inVocabulary;
    }

    private static String enumeration(List<String> parts, String conjunction) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " " + conjunction + " " + parts.get(parts.size() - 1);
    }
}
