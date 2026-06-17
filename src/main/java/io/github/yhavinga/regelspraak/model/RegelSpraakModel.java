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
    public final List<Diagnostic> diagnostics = new ArrayList<>();

    public boolean isOk() { return diagnostics.stream().noneMatch(Diagnostic::isError); }
    public List<Diagnostic> errors() { return diagnostics.stream().filter(Diagnostic::isError).toList(); }

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
