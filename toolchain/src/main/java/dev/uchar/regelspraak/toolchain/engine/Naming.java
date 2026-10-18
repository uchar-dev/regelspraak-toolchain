package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.FactType;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Role;
import dev.uchar.regelspraak.model.RegelSpraakModel.Vocabulary;
import dev.uchar.regelspraak.transpiler.core.Namen;

import java.util.List;

/**
 * The names one reader sees of the declarations of a model. In a vocabulary the model declares, a declaration goes by
 * its effective name there (IR-SPEC ch. 2.12): its designation in that vocabulary, and its canonical name where it has
 * none. In the canonical choice every name stays as the caller holds it, so an output built through the canonical
 * choice is the output built without this class. Only declarations are renamed: the rule text quoted in an
 * explanation, an expectation or a diagnostic stays as written.
 *
 * <p>A name that is no declaration of the model, such as a rule the backend adds itself, has no designation and keeps
 * its spelling, exactly as a declaration without a designation does. The decision service lists per vocabulary the
 * declarations without a designation ({@code /info}), so a consumer never has to guess where that happens.
 */
public final class Naming {

    /** The choice of the canonical names. It is a reserved word: a model that declares a vocabulary of this name
     *  cannot have that vocabulary chosen, and the decision service refuses to start on it ({@link #reserved}). */
    public static final String CANONICAL = "canonical";

    private final RegelSpraakModel model;
    /** The chosen vocabulary in its declared spelling; null for the canonical choice. */
    private final String vocabulary;

    private Naming(RegelSpraakModel model, String vocabulary) {
        this.model = model;
        this.vocabulary = vocabulary;
    }

    /** The canonical choice over {@code model}. */
    public static Naming canonical(RegelSpraakModel model) {
        return new Naming(model, null);
    }

    /** The naming {@code choice} names: {@link #CANONICAL} (and null) for the canonical names, otherwise a vocabulary the
     *  model declares, compared case-insensitively as uchar compares vocabulary names (WF-116). A vocabulary the model
     *  does not declare, and a model that declares one named {@link #CANONICAL}, is a {@link VocabularyError}. */
    public static Naming van(RegelSpraakModel model, String choice) {
        String reserved = reserved(model);
        if (reserved != null) throw new VocabularyError(reserved);
        if (choice == null || choice.equalsIgnoreCase(CANONICAL)) return canonical(model);
        Vocabulary v = model.vocabulary(choice);
        if (v == null) throw new VocabularyError(unknown(choice, model));
        return new Naming(model, v.naam());
    }

    /** The sentence that refuses a model declaring a vocabulary named {@link #CANONICAL}, or null when it declares none:
     *  that word chooses the canonical names, so such a vocabulary could never be chosen. */
    public static String reserved(RegelSpraakModel model) {
        Vocabulary v = model.vocabulary(CANONICAL);
        return v == null ? null : "het model declareert een vocabulaire '" + v.naam() + "', en '" + CANONICAL
                + "' kiest de canonieke namen; geef het vocabulaire een andere naam";
    }

    /** The sentence for a vocabulary {@code choice} the model does not declare, naming the ones it does. */
    private static String unknown(String choice, RegelSpraakModel model) {
        List<String> names = model.vocabularies.stream().map(v -> "'" + v.naam() + "'").toList();
        String declared = names.isEmpty() ? "geen vocabulaire"
                : names.size() == 1 ? names.get(0)
                : String.join(", ", names.subList(0, names.size() - 1)) + " en " + names.get(names.size() - 1);
        return "het vocabulaire '" + choice + "' is niet gedeclareerd; het model declareert " + declared
                + ", en '" + CANONICAL + "' kiest de canonieke namen";
    }

    /** A vocabulary that cannot be chosen: one the model does not declare, or the reserved {@link #CANONICAL}. */
    public static final class VocabularyError extends IllegalArgumentException {
        VocabularyError(String sentence) { super(sentence); }
    }

    /** Whether this is the canonical choice. */
    public boolean isCanonical() { return vocabulary == null; }

    /** The choice as a request or a report names it: the vocabulary in its declared spelling, or {@link #CANONICAL}. */
    public String choice() { return vocabulary == null ? CANONICAL : vocabulary; }

    /** The name of declaration {@code b}: its designation in the chosen vocabulary, or {@code b.name()}. */
    public String name(Designatable b) {
        return vocabulary == null ? b.naam() : model.benaming(vocabulary, b).orElse(b.naam());
    }

    /** The designation of declaration {@code b} in the chosen vocabulary, or null where it has none there and in the
     *  canonical choice: what tells a designation from a canonical name that {@link #name} gives in its place. */
    public String designation(Designatable b) {
        return vocabulary == null ? null : model.benaming(vocabulary, b).orElse(null);
    }

    public String objectType(String name) { return designate(Designatable.objectType(name), name); }

    public String parameter(String name) { return designate(Designatable.parameter(name), name); }

    public String factType(String name) { return designate(Designatable.factType(name), name); }

    /** Role {@code role} of fact type {@code factType}, both as the model declares them. */
    public String role(String factType, String role) { return designate(Designatable.role(factType, role), role); }

    /** The role written {@code written} (its name, declared plural or best-effort plural) that {@code subject}
     *  reaches: the far role of a fact type {@code subject} takes part in. When the written name names no such role,
     *  or more than one, it stays as written. */
    public String roleFrom(Objecttype subject, String written) {
        if (vocabulary == null || subject == null || written == null) return written;
        Designatable found = null;
        for (FactType ft : model.feittypen.values())
            for (Role r : ft.rollen)
                if (RegelSpraakModel.roleNameMatches(r, written) && reachable(model, ft, r, subject)) {
                    if (found != null) return written;
                    found = Designatable.role(ft.naam, r.naam());
                }
        return designate(found, written);
    }

    /** The role written {@code written} that {@code subject} itself fills (a role check, "is een …"); as written
     *  when it names no such role, or more than one. */
    public String roleFulfilled(Objecttype subject, String written) {
        if (vocabulary == null || subject == null || written == null) return written;
        Designatable found = null;
        for (FactType ft : model.feittypen.values())
            for (Role r : ft.rollen)
                if (RegelSpraakModel.roleNameMatches(r, written) && fills(model, r, subject)) {
                    if (found != null) return written;
                    found = Designatable.role(ft.naam, r.naam());
                }
        return designate(found, written);
    }

    /** Attribute {@code naam} of {@code holder}, looked up on the type of its chain that declares it. */
    public String attribute(Objecttype holder, String name) { return designate(attributeOf(holder, name), name); }

    /** Characteristic {@code naam} of {@code holder}, looked up on the type of its chain that declares it. */
    public String characteristic(Objecttype holder, String name) { return designate(characteristicOf(holder, name), name); }

    /** A named form ({@link Benoembaar.Soort#VORM}) by a model entry's name: a version suffix ({@code " #2"}) stays
     *  behind the designation, as it stood behind the form name. */
    public String form(String name) {
        if (vocabulary == null || name == null) return name;
        String base = RegelSpraakModel.formName(name);
        return model.benaming(vocabulary, Designatable.form(base))
                .map(b -> b + name.substring(base.length())).orElse(name);
    }

    /** A value of enumeration {@code e}, spelled as a request or a rule spells it: the declared value it names, compared
     *  as uchar compares declared names ({@link Namen#norm}), and that value's designation. */
    public String value(Datatype.Enumeratie e, String value) {
        if (vocabulary == null || value == null) return value;
        for (String w : e.waarden())
            if (Namen.norm(w).equals(Namen.norm(value)))
                return model.benaming(vocabulary, Designatable.enumerationValue(e.domain(), w)).orElse(value);
        return value;
    }

    private String designate(Designatable b, String name) {
        if (vocabulary == null || b == null || name == null) return name;
        return model.benaming(vocabulary, b).orElse(name);
    }

    /** Attribute {@code naam} of {@code holder} as a declaration: its declared spelling, owned by the type of the chain
     *  that declares it (IR-SPEC ch. 2.2, the owner a designation is written on), or null when none does. */
    public static Designatable attributeOf(Objecttype holder, String name) {
        if (holder == null || name == null) return null;
        for (Objecttype t : holder.chain())
            for (String a : t.attributen.keySet())
                if (a.equalsIgnoreCase(name)) return Designatable.attribute(t.naam, a);
        return null;
    }

    /** Whether {@code subject} reaches role {@code r} of {@code ft}: it fills another role of {@code ft}, or, in a
     *  reciprocal fact type, the one role itself. A role's declared filler bounds from above (IR-SPEC ch. 2.6), so a
     *  subtype of it fills the role too. */
    public static boolean reachable(RegelSpraakModel model, FactType ft, Role r, Objecttype subject) {
        if (ft.reciprocal) return fills(model, r, subject);
        for (Role other : ft.rollen) if (other != r && fills(model, other, subject)) return true;
        return false;
    }

    /** Whether {@code ot} fills role {@code r}: it is the role's declared filler or a subtype of it (IR-SPEC ch. 2.6). */
    public static boolean fills(RegelSpraakModel model, Role r, Objecttype ot) {
        Objecttype filler = model.objectTypeByName(r.objecttypeNaam());
        return filler != null ? ot.isA(filler) : r.objecttypeNaam().equalsIgnoreCase(ot.naam);
    }

    /** The characteristic twin of {@link #attributeOf}. */
    public static Designatable characteristicOf(Objecttype holder, String name) {
        if (holder == null || name == null) return null;
        for (Objecttype t : holder.chain())
            for (String k : t.kenmerken)
                if (k.equalsIgnoreCase(name)) return Designatable.characteristic(t.naam, k);
        return null;
    }
}
