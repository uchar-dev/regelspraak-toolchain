package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

import java.time.LocalDate;
import java.util.List;

/**
 * A fully resolved scenariotoets — a DOCUMENTED EXTENSION (docs/SCENARIOTOETSEN.md), NOT a §13 construct
 * (v2.3.0 has no test chapter). It is the resolver's typed IR for one {@code Scenario} block: what to run
 * ({@link #scope}), against which world ({@link #situatie}, the merged situation), and what to check
 * ({@link #verwachtingen}). The runner (a later layer, {@code engine/}) consumes this — it never re-reads
 * the parse tree.
 *
 * <p>Every value in the situatie and the expectations is NORMALISED at resolve time: a numeric value is an
 * exact {@link java.math.BigDecimal} after rational unit conversion to the GEDECLAREERDE unit of the target
 * attribuut/parameter (so "2 uur" on a minute attribute is stored as {@code 120}); a date is a
 * {@link LocalDate} / {@link java.time.LocalDateTime}; a boolean is a {@link Boolean}; text a {@link String};
 * an enumeration the bare literal (quotes stripped). This keeps the runner's comparison a plain
 * {@code equals}/{@code compareTo} with no re-interpretation.
 *
 * @param naam          the scenario name as written (whitespace-normalised)
 * @param scope         which rules/regelgroep the scenario tests (§ "Scope")
 * @param peildatum     the §4.2 rekendatum global as a date/datetime, or null when the header set none
 * @param situatie      the merged situation (gebaseerd-op sources in order, then inline) — the fresh facts
 * @param verwachtingen the expected results (bullets), in written order
 * @param line          the 1-based source line of the {@code Scenario} header (for diagnostics)
 */
public record ResolvedScenario(String naam, Scope scope, java.time.temporal.Temporal peildatum,
                               Situatie situatie, List<Verwachting> verwachtingen, int line) {

    /** The rules a scenario tests. {@code soort} distinguishes a single rule, a rule list, or a regelgroep;
     *  {@code regels} holds each referenced rule name AS WRITTEN, each paired with whether the resolver found
     *  it in the model ({@link ScopeRegel#bestaat}). For a {@link ScopeSoort#REGELGROEP} scope the single
     *  entry is the group name; {@code groepLeden} then lists the member rule names (empty for a rule/rule-list
     *  scope). */
    public record Scope(ScopeSoort soort, List<ScopeRegel> regels, List<String> groepLeden) {}
    public enum ScopeSoort { ENKELE_REGEL, MEERDERE_REGELS, REGELGROEP }
    /** One scope reference: the name as written and whether a matching rule (or, for a regelgroep, group)
     *  exists in the model. A non-existent reference is an ERROR the resolver already raised; {@code bestaat}
     *  lets the runner skip it without re-checking. */
    public record ScopeRegel(String naam, boolean bestaat) {}

    /** The merged, resolved situation: the object instances (by LABEL, in first-seen order) and the parameter
     *  values (later value wins, § "Semantiek"). */
    public record Situatie(List<Instantie> instanties, List<ParameterWaarde> parameters) {
        /** The instantie carrying LABEL {@code label}, or null. */
        public Instantie instantie(String label) {
            return instanties.stream().filter(i -> i.label().equals(label)).findFirst().orElse(null);
        }
    }

    /** One object instance in the situatie: its LABEL, its resolved type, and its attribuut/rol/kenmerk
     *  assignments (a re-assignment of the same attribuut later in the merge order has already replaced the
     *  earlier one, so each attribuut/rol appears once — the LAST written value). */
    public record Instantie(String label, Objecttype objecttype,
                            List<AttribuutWaarde> attributen, List<RolWaarde> rollen,
                            List<KenmerkWaarde> kenmerken) {}

    /** An attribuut assignment: the declared attribute name, its declared datatype, and the normalised value. */
    public record AttribuutWaarde(String attribuut, Datatype type, Waarde waarde, int line) {}

    /** A rol assignment: the declared role name, the target LABEL(s) it points at (one for a to-one, several
     *  for a to-many {@code zijn}-list), and whether the role is to-many. */
    public record RolWaarde(String rol, List<String> doelLabels, boolean meervoudig, int line) {}

    /** A kenmerk assignment on an instance ("is/heeft [een] <kenmerk>"): the declared kenmerk name and whether
     *  it is set present (a situatie only ever ASSERTS a kenmerk; absence is the default). */
    public record KenmerkWaarde(String kenmerk, boolean bezittelijk, int line) {}

    /** A parameter value in the merged situatie: the declared parameter name, its datatype, and the normalised
     *  value (converted to the parameter's declared unit). */
    public record ParameterWaarde(String parameter, Datatype type, Waarde waarde, int line) {}

    /** One expected result (a verwachting bullet). {@code soort} selects which of the fields is meaningful. */
    public record Verwachting(VerwachtingSoort soort, String label, String attribuut, String kenmerk,
                              boolean bezittelijk, Waarde verwachteWaarde, int line, String bron) {}
    public enum VerwachtingSoort {
        ATTRIBUUT_WAARDE,   // de <attr> van <LABEL> is <waarde>
        ATTRIBUUT_LEEG,     // de <attr> van <LABEL> is leeg
        KENMERK_AANWEZIG,   // <LABEL> is/heeft [een] <kenmerk>
        KENMERK_AFWEZIG,    // <LABEL> is/heeft geen <kenmerk>
        CONSISTENT,         // de gegevens zijn consistent
        INCONSISTENT        // de gegevens zijn inconsistent
    }

    /** A normalised value — exactly one of the fields is non-null. A numeric value is the exact BigDecimal
     *  AFTER unit conversion to the target's declared unit; a date is a LocalDate, a datetime a LocalDateTime;
     *  a boolean/text/enum its Java value (enum = the bare literal). {@link #LEEG} models an unresolvable value
     *  slot (never produced for a situatie assignment, which always carries a concrete value). */
    public record Waarde(java.math.BigDecimal getal, LocalDate datum, java.time.LocalDateTime datumTijd,
                         Boolean bool, String tekst, String enumWaarde, boolean leeg) {
        public static final Waarde LEEG = new Waarde(null, null, null, null, null, null, true);
        public static Waarde vanGetal(java.math.BigDecimal g) { return new Waarde(g, null, null, null, null, null, false); }
        public static Waarde vanDatum(LocalDate d) { return new Waarde(null, d, null, null, null, null, false); }
        public static Waarde vanDatumTijd(java.time.LocalDateTime d) { return new Waarde(null, null, d, null, null, null, false); }
        public static Waarde vanBoolean(boolean b) { return new Waarde(null, null, null, b, null, null, false); }
        public static Waarde vanTekst(String t) { return new Waarde(null, null, null, null, t, null, false); }
        public static Waarde vanEnum(String e) { return new Waarde(null, null, null, null, null, e, false); }
    }
}
