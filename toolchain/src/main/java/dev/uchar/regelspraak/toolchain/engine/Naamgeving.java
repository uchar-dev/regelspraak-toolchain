package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;
import dev.uchar.regelspraak.model.RegelSpraakModel.Feittype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Rol;
import dev.uchar.regelspraak.model.RegelSpraakModel.Vocabulaire;
import dev.uchar.regelspraak.transpiler.kern.Namen;

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
public final class Naamgeving {

    /** The choice of the canonical names. It is a reserved word: a model that declares a vocabulary of this name
     *  cannot have that vocabulary chosen, and the decision service refuses to start on it ({@link #gereserveerd}). */
    public static final String CANONIEK = "canoniek";

    private final RegelSpraakModel model;
    /** The chosen vocabulary in its declared spelling; null for the canonical choice. */
    private final String vocabulaire;

    private Naamgeving(RegelSpraakModel model, String vocabulaire) {
        this.model = model;
        this.vocabulaire = vocabulaire;
    }

    /** The canonical choice over {@code model}. */
    public static Naamgeving canoniek(RegelSpraakModel model) {
        return new Naamgeving(model, null);
    }

    /** The naming {@code keuze} names: {@link #CANONIEK} (and null) for the canonical names, otherwise a vocabulary the
     *  model declares, compared case-insensitively as uchar compares vocabulary names (WF-116). A vocabulary the model
     *  does not declare, and a model that declares one named {@link #CANONIEK}, is a {@link Vocabulairefout}. */
    public static Naamgeving van(RegelSpraakModel model, String keuze) {
        String gereserveerd = gereserveerd(model);
        if (gereserveerd != null) throw new Vocabulairefout(gereserveerd);
        if (keuze == null || keuze.equalsIgnoreCase(CANONIEK)) return canoniek(model);
        Vocabulaire v = model.vocabulaire(keuze);
        if (v == null) throw new Vocabulairefout(onbekend(keuze, model));
        return new Naamgeving(model, v.naam());
    }

    /** The sentence that refuses a model declaring a vocabulary named {@link #CANONIEK}, or null when it declares none:
     *  that word chooses the canonical names, so such a vocabulary could never be chosen. */
    public static String gereserveerd(RegelSpraakModel model) {
        Vocabulaire v = model.vocabulaire(CANONIEK);
        return v == null ? null : "het model declareert een vocabulaire '" + v.naam() + "', en '" + CANONIEK
                + "' kiest de canonieke namen; geef het vocabulaire een andere naam";
    }

    /** The sentence for a vocabulary {@code keuze} the model does not declare, naming the ones it does. */
    public static String onbekend(String keuze, RegelSpraakModel model) {
        List<String> namen = model.vocabulaires.stream().map(v -> "'" + v.naam() + "'").toList();
        String gedeclareerd = namen.isEmpty() ? "geen vocabulaire"
                : namen.size() == 1 ? namen.get(0)
                : String.join(", ", namen.subList(0, namen.size() - 1)) + " en " + namen.get(namen.size() - 1);
        return "het vocabulaire '" + keuze + "' is niet gedeclareerd; het model declareert " + gedeclareerd
                + ", en '" + CANONIEK + "' kiest de canonieke namen";
    }

    /** A vocabulary that cannot be chosen: one the model does not declare, or the reserved {@link #CANONIEK}. */
    public static final class Vocabulairefout extends IllegalArgumentException {
        Vocabulairefout(String zin) { super(zin); }
    }

    /** Whether this is the canonical choice. */
    public boolean isCanoniek() { return vocabulaire == null; }

    /** The choice as a request or a report names it: the vocabulary in its declared spelling, or {@link #CANONIEK}. */
    public String keuze() { return vocabulaire == null ? CANONIEK : vocabulaire; }

    /** The name of declaration {@code b}: its designation in the chosen vocabulary, or {@code b.naam()}. */
    public String naam(Benoembaar b) {
        return vocabulaire == null ? b.naam() : model.benaming(vocabulaire, b).orElse(b.naam());
    }

    public String objecttype(String naam) { return benoem(Benoembaar.objecttype(naam), naam); }

    public String parameter(String naam) { return benoem(Benoembaar.parameter(naam), naam); }

    public String feittype(String naam) { return benoem(Benoembaar.feittype(naam), naam); }

    public String rol(String feittype, String rol) { return benoem(Benoembaar.rol(feittype, rol), rol); }

    public String regelgroep(String naam) { return benoem(Benoembaar.regelgroep(naam), naam); }

    /** The role written {@code geschreven} (its name, declared plural or best-effort plural) that {@code onderwerp}
     *  reaches: the far role of a fact type {@code onderwerp} takes part in. When the written name names no such role,
     *  or more than one, it stays as written. */
    public String rolVanaf(Objecttype onderwerp, String geschreven) {
        if (vocabulaire == null || onderwerp == null || geschreven == null) return geschreven;
        Benoembaar gevonden = null;
        for (Feittype ft : model.feittypen.values())
            for (Rol r : ft.rollen)
                if (RegelSpraakModel.rolNaamMatcht(r, geschreven) && bereikbaar(model, ft, r, onderwerp)) {
                    if (gevonden != null) return geschreven;
                    gevonden = Benoembaar.rol(ft.naam, r.naam());
                }
        return benoem(gevonden, geschreven);
    }

    /** The role written {@code geschreven} that {@code onderwerp} itself fills (a role check, "is een …"); as written
     *  when it names no such role, or more than one. */
    public String rolVervuld(Objecttype onderwerp, String geschreven) {
        if (vocabulaire == null || onderwerp == null || geschreven == null) return geschreven;
        Benoembaar gevonden = null;
        for (Feittype ft : model.feittypen.values())
            for (Rol r : ft.rollen)
                if (RegelSpraakModel.rolNaamMatcht(r, geschreven) && vult(model, r, onderwerp)) {
                    if (gevonden != null) return geschreven;
                    gevonden = Benoembaar.rol(ft.naam, r.naam());
                }
        return benoem(gevonden, geschreven);
    }

    /** Attribute {@code naam} of {@code houder}, looked up on the type of its chain that declares it. */
    public String attribuut(Objecttype houder, String naam) { return benoem(attribuutVan(houder, naam), naam); }

    /** Characteristic {@code naam} of {@code houder}, looked up on the type of its chain that declares it. */
    public String kenmerk(Objecttype houder, String naam) { return benoem(kenmerkVan(houder, naam), naam); }

    /** A named form ({@link Benoembaar.Soort#VORM}) by a model entry's name: a version suffix ({@code " #2"}) stays
     *  behind the designation, as it stood behind the form name. */
    public String vorm(String naam) {
        if (vocabulaire == null || naam == null) return naam;
        String basis = RegelSpraakModel.vormnaam(naam);
        return model.benaming(vocabulaire, Benoembaar.vorm(basis))
                .map(b -> b + naam.substring(basis.length())).orElse(naam);
    }

    /** A value of enumeration {@code e}, spelled as a request or a rule spells it: the declared value it names, compared
     *  as uchar compares declared names ({@link Namen#norm}), and that value's designation. */
    public String waarde(Datatype.Enumeratie e, String waarde) {
        if (vocabulaire == null || waarde == null) return waarde;
        for (String w : e.waarden())
            if (Namen.norm(w).equals(Namen.norm(waarde)))
                return model.benaming(vocabulaire, Benoembaar.enumeratiewaarde(e.domein(), w)).orElse(waarde);
        return waarde;
    }

    private String benoem(Benoembaar b, String naam) {
        if (vocabulaire == null || b == null || naam == null) return naam;
        return model.benaming(vocabulaire, b).orElse(naam);
    }

    /** Attribute {@code naam} of {@code houder} as a declaration: its declared spelling, owned by the type of the chain
     *  that declares it (IR-SPEC ch. 2.2, the owner a designation is written on), or null when none does. */
    public static Benoembaar attribuutVan(Objecttype houder, String naam) {
        if (houder == null || naam == null) return null;
        for (Objecttype t : houder.keten())
            for (String a : t.attributen.keySet())
                if (a.equalsIgnoreCase(naam)) return Benoembaar.attribuut(t.naam, a);
        return null;
    }

    /** Whether {@code onderwerp} reaches role {@code r} of {@code ft}: it fills another role of {@code ft}, or, in a
     *  reciprocal fact type, the one role itself. A role's declared filler bounds from above (IR-SPEC ch. 2.6), so a
     *  subtype of it fills the role too. */
    public static boolean bereikbaar(RegelSpraakModel model, Feittype ft, Rol r, Objecttype onderwerp) {
        if (ft.wederkerig) return vult(model, r, onderwerp);
        for (Rol ander : ft.rollen) if (ander != r && vult(model, ander, onderwerp)) return true;
        return false;
    }

    /** Whether {@code ot} fills role {@code r}: it is the role's declared filler or a subtype of it (IR-SPEC ch. 2.6). */
    public static boolean vult(RegelSpraakModel model, Rol r, Objecttype ot) {
        Objecttype filler = model.objecttypeOpNaam(r.objecttypeNaam());
        return filler != null ? ot.isEen(filler) : r.objecttypeNaam().equalsIgnoreCase(ot.naam);
    }

    /** The characteristic twin of {@link #attribuutVan}. */
    public static Benoembaar kenmerkVan(Objecttype houder, String naam) {
        if (houder == null || naam == null) return null;
        for (Objecttype t : houder.keten())
            for (String k : t.kenmerken)
                if (k.equalsIgnoreCase(naam)) return Benoembaar.kenmerk(t.naam, k);
        return null;
    }
}
