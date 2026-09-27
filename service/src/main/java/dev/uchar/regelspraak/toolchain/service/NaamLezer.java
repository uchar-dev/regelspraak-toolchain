package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;
import dev.uchar.regelspraak.model.RegelSpraakModel.Feittype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Rol;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.transpiler.kern.Namen;

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
 * canonical name — is {@link Dubbelzinnig}: the service does not guess, and its sentence names both readings and asks
 * for the input vocabulary. With the input vocabulary pinned, a name is read only as an effective name in that
 * vocabulary, or, pinned to {@link Naamgeving#CANONIEK}, only as a canonical name; that resolves the ambiguity.
 */
final class NaamLezer {

    /** The source of a canonical reading, beside the vocabularies. */
    private static final String CANONIEK = Naamgeving.CANONIEK;

    private final RegelSpraakModel model;
    /** The pinned input vocabulary in its declared spelling, {@link #CANONIEK}, or null: every reading. */
    private final String vastgezet;

    /** @param vastgezet the input vocabulary the request pins, already checked against the model
     *                   ({@link Naamgeving#van}), or null to read every name liberally */
    NaamLezer(RegelSpraakModel model, String vastgezet) {
        this.model = model;
        this.vastgezet = vastgezet == null ? null : Naamgeving.van(model, vastgezet).keuze();
    }

    /** The outcome of reading one name. */
    sealed interface Lezing permits Gevonden, Dubbelzinnig, Onbekend {}

    /** The name read as {@code declaratie}. {@code naam} is the name the service goes on with: the written name where
     *  it reads canonically, so a canonical request is read exactly as before, and the declaration's canonical name
     *  where only a designation reads it. */
    record Gevonden(Benoembaar declaratie, String naam) implements Lezing {}

    /** The name reads as more than one declaration; {@code zin} names each reading and asks for the vocabulary. */
    record Dubbelzinnig(String zin) implements Lezing {}

    /** No declaration of its kind and scope carries the name. */
    record Onbekend() implements Lezing {}

    Lezing objecttype(String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen()) {
            Objecttype ot = DescriptorLoader.objecttype(model, naam);
            if (ot != null) t.add(new Treffer(Benoembaar.objecttype(ot.naam), CANONIEK));
        }
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.OBJECTTYPE, null, naam));
        return oordeel(naam, t);
    }

    Lezing attribuut(Objecttype ot, String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen()) canoniek(t, Naamgeving.attribuutVan(ot, naam));
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.ATTRIBUUT, ot.naam, naam));
        return oordeel(naam, t);
    }

    Lezing kenmerk(Objecttype ot, String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen()) canoniek(t, Naamgeving.kenmerkVan(ot, naam));
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.KENMERK, ot.naam, naam));
        return oordeel(naam, t);
    }

    Lezing parameter(String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen()) {
            RegelSpraakModel.Parameter p = DescriptorLoader.parameter(model, naam);
            if (p != null) t.add(new Treffer(Benoembaar.parameter(p.naam()), CANONIEK));
        }
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.PARAMETER, null, naam));
        return oordeel(naam, t);
    }

    Lezing feittype(String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen()) {
            Feittype ft = model.feittypen.get(naam);
            if (ft == null)
                for (Feittype f : model.feittypen.values()) if (f.naam.equalsIgnoreCase(naam)) { ft = f; break; }
            if (ft != null) t.add(new Treffer(Benoembaar.feittype(ft.naam), CANONIEK));
        }
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.FEITTYPE, null, naam));
        return oordeel(naam, t);
    }

    /** A role of fact type {@code ft}, as a relation of the request names it: by its declared name. */
    Lezing rolIn(Feittype ft, String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen())
            for (Rol r : ft.rollen)
                if (r.naam().equalsIgnoreCase(naam)) t.add(new Treffer(Benoembaar.rol(ft.naam, r.naam()), CANONIEK));
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.ROL, ft.naam, naam));
        return oordeel(naam, t);
    }

    /** A role written at an instance of {@code ot}: a far role of a fact type {@code ot} takes part in, canonically by
     *  its name, declared plural or best-effort plural, as uchar reads a written role. */
    Lezing rolVanaf(Objecttype ot, String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen())
            for (Feittype ft : model.feittypen.values())
                for (Rol r : ft.rollen)
                    if (RegelSpraakModel.rolNaamMatcht(r, naam) && Naamgeving.bereikbaar(model, ft, r, ot)
                            && model.objecttypeOpNaam(r.objecttypeNaam()) != null)
                        t.add(new Treffer(Benoembaar.rol(ft.naam, r.naam()), CANONIEK));
        for (Feittype ft : model.feittypen.values())
            vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.ROL, ft.naam, naam)
                    .filter(b -> bereikbaar(ft, b.naam(), ot)));
        return oordeel(naam, t);
    }

    /** A value of enumeration {@code e}: canonically compared as uchar compares declared names ({@link Namen#norm}). */
    Lezing waarde(Datatype.Enumeratie e, String naam) {
        List<Treffer> t = new ArrayList<>();
        if (canoniekLezen())
            for (String w : e.waarden())
                if (Namen.norm(w).equals(Namen.norm(naam)))
                    t.add(new Treffer(Benoembaar.enumeratiewaarde(e.domein(), w), CANONIEK));
        vocabulaires(t, v -> model.benoemd(v, Benoembaar.Soort.ENUMERATIEWAARDE, e.domein(), naam));
        return oordeel(naam, t);
    }

    /** The clause an unknown name's refusal ends with when the input vocabulary is pinned: where the name was read. */
    String vastgezetZin() {
        return vastgezet == null ? "" : " (invoervocabulaire '" + vastgezet + "')";
    }

    /** The pinned input vocabulary in its declared spelling, {@link Naamgeving#CANONIEK}, or null. */
    String vastgezet() { return vastgezet; }

    /** Whether the input vocabulary is pinned to a declared vocabulary: a canonical name then reads nothing. */
    boolean vastInVocabulaire() {
        return vastgezet != null && !vastgezet.equals(CANONIEK);
    }

    /** The values of {@code e} as the pinned vocabulary names them (only meaningful with {@link #vastInVocabulaire}). */
    List<String> waarden(Datatype.Enumeratie e) {
        Naamgeving n = Naamgeving.van(model, vastgezet);
        return e.waarden().stream().map(w -> n.waarde(e, w)).toList();
    }

    /** A description of declaration {@code b} for a sentence, by its canonical name. */
    static String beschrijving(Benoembaar b) {
        return switch (b.soort()) {
            case OBJECTTYPE -> "het objecttype '" + b.naam() + "'";
            case ATTRIBUUT -> "het attribuut '" + b.naam() + "' van '" + b.eigenaar() + "'";
            case KENMERK -> "het kenmerk '" + b.naam() + "' van '" + b.eigenaar() + "'";
            case FEITTYPE -> "het feittype '" + b.naam() + "'";
            case ROL -> "de rol '" + b.naam() + "' van feittype '" + b.eigenaar() + "'";
            case ENUMERATIEWAARDE -> "de waarde '" + b.naam() + "' van domein '" + b.eigenaar() + "'";
            case PARAMETER -> "de parameter '" + b.naam() + "'";
            case VORM -> "de regel '" + b.naam() + "'";
            case REGELGROEP -> "de regelgroep '" + b.naam() + "'";
        };
    }

    /** Whether two elements name one declaration: the same kind, owner and name under uchar's name identity. */
    static boolean zelfde(Benoembaar a, Benoembaar b) {
        return a.soort() == b.soort() && a.naam().equalsIgnoreCase(b.naam())
                && (a.eigenaar() == null ? b.eigenaar() == null
                    : b.eigenaar() != null && a.eigenaar().equalsIgnoreCase(b.eigenaar()));
    }

    // ---------------------------------------------------------------- the reading

    /** One reading of a name: the declaration and where it was read, {@link #CANONIEK} or a vocabulary. */
    private record Treffer(Benoembaar declaratie, String bron) {}

    private boolean canoniekLezen() {
        return vastgezet == null || vastgezet.equals(CANONIEK);
    }

    private void canoniek(List<Treffer> t, Benoembaar b) {
        if (b != null) t.add(new Treffer(b, CANONIEK));
    }

    /** The reading of every vocabulary the name may be read in: the pinned one, or all the model declares. */
    private void vocabulaires(List<Treffer> t, Function<String, Optional<Benoembaar>> lees) {
        if (vastgezet != null && vastgezet.equals(CANONIEK)) return;
        for (RegelSpraakModel.Vocabulaire v : model.vocabulaires)
            if (vastgezet == null || v.naam().equals(vastgezet))
                lees.apply(v.naam()).ifPresent(b -> t.add(new Treffer(b, v.naam())));
    }

    private boolean bereikbaar(Feittype ft, String rol, Objecttype ot) {
        for (Rol r : ft.rollen) if (r.naam().equals(rol)) return Naamgeving.bereikbaar(model, ft, r, ot);
        return false;
    }

    /** The readings of {@code naam} judged. Several canonical readings and nothing else is the refusal uchar's own
     *  reading of a written role gives (a name two reachable roles answer to names neither), so it stays unknown. */
    private Lezing oordeel(String naam, List<Treffer> treffers) {
        List<Benoembaar> declaraties = new ArrayList<>();
        List<List<String>> bronnen = new ArrayList<>();
        for (Treffer t : treffers) {
            int i = 0;
            while (i < declaraties.size() && !zelfde(declaraties.get(i), t.declaratie())) i++;
            if (i == declaraties.size()) { declaraties.add(t.declaratie()); bronnen.add(new ArrayList<>()); }
            if (!bronnen.get(i).contains(t.bron())) bronnen.get(i).add(t.bron());
        }
        if (declaraties.isEmpty()) return new Onbekend();
        if (declaraties.size() == 1) {
            Benoembaar b = declaraties.get(0);
            return new Gevonden(b, bronnen.get(0).contains(CANONIEK) ? naam : b.naam());
        }
        if (treffers.stream().allMatch(t -> t.bron().equals(CANONIEK))) return new Onbekend();
        List<String> lezingen = new ArrayList<>();
        List<String> keuzes = new ArrayList<>();
        for (int i = 0; i < declaraties.size(); i++) {
            lezingen.add(waar(bronnen.get(i)) + " " + beschrijving(declaraties.get(i)));
            for (String bron : bronnen.get(i)) if (!keuzes.contains(bron)) keuzes.add(bron);
        }
        return new Dubbelzinnig("de naam '" + naam + "' is dubbelzinnig: " + String.join(", ", lezingen)
                + "; zet het vocabulaire erbij met de queryparameter invoervocabulaire (" + opsomming(keuzes, "of") + ")");
    }

    /** Where a declaration was read: "als canonieke naam", "in het vocabulaire 'v'", or both. */
    private static String waar(List<String> bronnen) {
        List<String> vocabulaires = bronnen.stream().filter(b -> !b.equals(CANONIEK)).map(b -> "'" + b + "'").toList();
        String inVocabulaire = vocabulaires.isEmpty() ? null
                : (vocabulaires.size() == 1 ? "in het vocabulaire " : "in de vocabulaires ") + opsomming(vocabulaires, "en");
        if (!bronnen.contains(CANONIEK)) return inVocabulaire;
        return inVocabulaire == null ? "als canonieke naam" : "als canonieke naam en " + inVocabulaire;
    }

    private static String opsomming(List<String> delen, String voegwoord) {
        if (delen.size() == 1) return delen.get(0);
        return String.join(", ", delen.subList(0, delen.size() - 1)) + " " + voegwoord + " " + delen.get(delen.size() - 1);
    }
}
