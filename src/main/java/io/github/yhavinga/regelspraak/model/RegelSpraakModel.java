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

    private Objecttype objecttypeOpNaam(String naam) {
        Objecttype exact = objecttypen.get(naam);
        if (exact != null) return exact;
        for (var e : objecttypen.entrySet())
            if (e.getKey().equalsIgnoreCase(naam)) return e.getValue();
        return null;
    }

    private Objecttype objecttypeViaRol(String naam) {
        for (Feittype ft : feittypen.values())
            for (Rol r : ft.rollen)
                if (rolMatcht(r.naam(), naam)) return objecttypeOpNaam(r.objecttypeNaam());
        return null;
    }

    private static boolean rolMatcht(String rolNaam, String naam) {
        return rolNaam.equalsIgnoreCase(naam)
                || naam.equalsIgnoreCase(rolNaam + "s")    // passagier → passagiers
                || naam.equalsIgnoreCase(rolNaam + "en");  // partner → partners is +s; persoon → personen
    }

    /** Resolve a unit abbreviation to the Eenheid declared in some system. */
    public Eenheid resolveEenheid(String afkorting) {
        for (Eenheidstelsel s : eenheidstelsels.values()) {
            Eenheid e = s.eenheden.get(afkorting);
            if (e != null) return e;
        }
        return new Eenheid(afkorting, null); // unknown system; still usable by name
    }

    /** An object type: its attributes (name -> datatype) and kenmerk names. */
    public static final class Objecttype {
        public final String naam;
        public boolean bezield;
        public final Map<String, Datatype> attributen = new LinkedHashMap<>();
        public final List<String> kenmerken = new ArrayList<>();
        public Objecttype(String naam) { this.naam = naam; }
    }

    public record Parameter(String naam, Datatype datatype) {}

    public static final class Feittype {
        public final String naam;
        public final List<Rol> rollen = new ArrayList<>();
        public boolean wederkerig;
        public Feittype(String naam) { this.naam = naam; }
    }
    public record Rol(String naam, String objecttypeNaam) {}

    public static final class Eenheidstelsel {
        public final String naam;
        public final Map<String, Eenheid> eenheden = new LinkedHashMap<>();
        public Eenheidstelsel(String naam) { this.naam = naam; }
    }

    /** §13.3.6.10 a named timeline; `variabelStartpunt` is bound by a §13.4.12 startpuntbepaling. */
    public record Tijdlijn(String naam, boolean variabelStartpunt) {}

    /** §13.3.7 a dimension and its ordered label values. */
    public record Dimensie(String naam, String naamMeervoud, List<String> waarden) {}

    /** A rule shell (name + the parse subtree); type-checking annotates diagnostics. */
    public record Regel(String naam, String soort) {}

    public enum Severity { ERROR, WARNING, INFO }
    public record Diagnostic(Severity severity, String message, int line) {
        public boolean isError() { return severity == Severity.ERROR; }
        @Override public String toString() { return severity + " (line " + line + "): " + message; }
    }
}
