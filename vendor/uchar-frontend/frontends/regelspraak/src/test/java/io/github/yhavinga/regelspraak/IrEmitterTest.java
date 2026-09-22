package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.ir.Bron;
import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The v0 IR emitter also writes the symbolic extension fields the uchar wire admits — the
 * navigation/scope/creation role bindings — each derived from the declared names and emitted
 * only where uniquely derivable. These tests exercise the EMITTED JSON (not just the resolved
 * records): the four families plus the fold-source discipline, so a regression in the wire shape
 * is caught here. Assertions compare against whitespace-collapsed JSON so they are robust to
 * indentation while still pinning the exact field/value sequence.
 */
class IrEmitterTest {

    /** The emitted v0 IR JSON with all whitespace removed — deterministic, so an exact
     *  compacted fragment pins the wire shape without depending on indentation. */
    private static String emitCompact(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        return IrEmitter.emit(m).replaceAll("\\s+", "");
    }

    /** The emitted v0 IR JSON UNTOUCHED. Only a witness about whitespace INSIDE a value may use this
     *  — {@link #emitCompact} would erase the very difference it asserts. */
    private static String emitRaw(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        return IrEmitter.emit(m);
    }

    @Test void navigatiebindingen_op_ketenlezing_subject_uitwaarts() {
        // "het bedrijfsniveau van het bedrijf van zijn afdeling": a §13.4.1 declared chain
        // Werknemer -[afdeling]-> Afdeling -[bedrijf]-> Bedrijf. One binding per hop, subject-
        // outward, each naming the REACHED role by its declared name.
        String src = """
            Objecttype de Werknemer
                de rang Numeriek (geheel getal);
            Objecttype de Afdeling
            Objecttype het Bedrijf
                het bedrijfsniveau Numeriek (geheel getal);
            Feittype aanstelling
                de werknemer Werknemer
                de afdeling Afdeling
            Eén afdeling telt meerdere werknemers
            Feittype onderdeel
                de afdeling Afdeling
                het bedrijf Bedrijf
            Eén bedrijf telt meerdere afdelingen
            Regel rang
                geldig altijd
                    De rang van een Werknemer moet gesteld worden op het bedrijfsniveau van het bedrijf van zijn afdeling.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"navigatieBindingen\":["
                + "{\"node\":\"RolBinding\",\"feittype\":\"aanstelling\",\"rol\":\"afdeling\"},"
                + "{\"node\":\"RolBinding\",\"feittype\":\"onderdeel\",\"rol\":\"bedrijf\"}]"),
                () -> "keten-bindingen (subject-uitwaarts, gedeclareerde namen) ontbreken:\n" + c);
    }

    @Test void navigatiebinding_op_genavigeerde_kenmerkcheck() {
        // "indien zijn afdeling kernafdeling is": a navigated §8.1.8 characteristic check binds
        // the reached role of the one hop.
        String src = """
            Objecttype de Werknemer
                is sleutelkracht kenmerk (bijvoeglijk);
            Objecttype de Afdeling
                is kernafdeling kenmerk (bijvoeglijk);
            Feittype aanstelling
                de werknemer Werknemer
                de afdeling Afdeling
            Eén afdeling telt meerdere werknemers
            Regel sleutelkracht
                geldig altijd
                    Een Werknemer is sleutelkracht indien zijn afdeling kernafdeling is.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"navigatieBinding\":{\"node\":\"RolBinding\",\"feittype\":\"aanstelling\",\"rol\":\"afdeling\"}"),
                () -> "navigatieBinding op de genavigeerde kenmerkcheck ontbreekt:\n" + c);
    }

    @Test void scoperol_op_parent_scoped_uniciteit() {
        // "De X van alle Werknemer van de afdeling moeten uniek zijn": parent-scoped uniqueness;
        // scopeRol names the scope-parent relation (the reference field made symbolic).
        String src = """
            Objecttype de Werknemer
                het personeelsnummer Numeriek (geheel getal);
            Objecttype de Afdeling
            Feittype aanstelling
                de werknemer Werknemer
                de afdeling Afdeling
            Eén afdeling telt meerdere werknemers
            Regel uniek
                geldig altijd
                    De personeelsnummer van alle Werknemer van de afdeling moeten uniek zijn.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"scopeRol\":{\"node\":\"RolBinding\",\"feittype\":\"aanstelling\",\"rol\":\"afdeling\"}"),
                () -> "scopeRol op de parent-scoped uniciteit ontbreekt:\n" + c);
    }

    @Test void feitcreatie_symbolisch_blok_is_heel_of_niet() {
        // A §13.4.7 feitcreatie with one tussenstap: the whole symbolic block (created fact type,
        // both ends, the final join) plus a RolBinding on the intermediate hop.
        String src = """
            Objecttype het Bedrijf
            Objecttype de Afdeling
            Objecttype het Project
            Feittype onderdeel
                de afdeling Afdeling
                het bedrijf Bedrijf
            Eén bedrijf telt meerdere afdelingen
            Feittype uitvoering
                de afdeling Afdeling
                het project (mv: projecten) Project
            Eén afdeling telt meerdere projecten
            Feittype sponsoring
                het sponsorbedrijf Bedrijf
                het sponsorproject (mv: sponsorprojecten) Project
            Eén sponsorbedrijf telt meerdere sponsorprojecten
            Regel sponsoring
                geldig altijd
                    Een sponsorproject van een Bedrijf is een project van de afdeling van het bedrijf.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"viaOuderVeld\":false,"
                + "\"rolBinding\":{\"node\":\"RolBinding\",\"feittype\":\"onderdeel\",\"rol\":\"afdeling\"}"),
                () -> "tussenstap-rolBinding ontbreekt:\n" + c);
        assertTrue(c.contains(
                "\"feittype\":\"sponsoring\",\"ankerRol\":\"sponsorbedrijf\",\"doelRol\":\"sponsorproject\","
                + "\"joinRol\":{\"node\":\"RolBinding\",\"feittype\":\"uitvoering\",\"rol\":\"project\"}"),
                () -> "het symbolische feitcreatieblok ontbreekt of is onvolledig:\n" + c);
    }

    @Test void fold_bron_argument_draagt_geen_navigatiebindingen() {
        // The fold's SOURCE argument routes through bronRol; even though its type (Persoon) differs
        // from the fold's outer subject (Ploeg) — the by-pair shape — it must carry NO
        // navigatieBindingen (IR-SPEC ch. 4.1a; the uchar verifier rejects nav bindings in a fold slot).
        String src = """
            Objecttype de Ploeg
                het totaalbedrag Numeriek (geheel getal);
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype deelname
                de groep Ploeg
                de deelnemer (mv: deelnemers) Persoon
            Eén groep telt meerdere deelnemers
            Regel som
                geldig altijd
                    Het totaalbedrag van een groep moet berekend worden als de som van de leeftijd van alle deelnemers van de groep.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"bronRol\":{\"node\":\"RolBinding\",\"feittype\":\"deelname\",\"rol\":\"deelnemer\"}"),
                () -> "de fold moet zijn bronRol dragen:\n" + c);
        assertFalse(c.contains("navigatieBindingen"),
                () -> "een fold-bron-argument mag GEEN navigatieBindingen dragen:\n" + c);
    }

    @Test void fold_filter_elementlezing_draagt_geen_navigatiebindingen() {
        // The filter is a subselectie over the fold ELEMENT: "die kleiner zijn dan 18" reads the
        // deelnemer's OWN leeftijd — an own read, not a navigation from the outer groep. So the filter
        // read carries no spurious by-pair navigatieBindingen (the emitter walks the filter with the
        // element as subject).
        String src = """
            Objecttype de Ploeg
                het totaalbedrag Numeriek (geheel getal);
            Objecttype de Persoon
                de leeftijd Numeriek (geheel getal);
            Feittype deelname
                de groep Ploeg
                de deelnemer (mv: deelnemers) Persoon
            Eén groep telt meerdere deelnemers
            Regel som
                geldig altijd
                    Het totaalbedrag van een groep moet berekend worden als de som van de leeftijd van alle deelnemers van de groep die kleiner zijn dan 18.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "\"bronRol\":{\"node\":\"RolBinding\",\"feittype\":\"deelname\",\"rol\":\"deelnemer\"}"),
                () -> "de fold moet zijn bronRol dragen:\n" + c);
        assertFalse(c.contains("navigatieBindingen"),
                () -> "een element-eigen filter-lezing mag GEEN navigatieBindingen dragen:\n" + c);
    }

    @Test void unit_literal_lexeme_is_kaal_eenheid_in_type() {
        // uchar WF-081 (IR-SPEC ch. 4.1): a numeric literal's waarde is the BARE number lexeme;
        // the unit rides the literal's datatype ("eenheid"), exactly as attribute datatypes carry
        // it. The source snippet ("5 €") stays in bron. The old form ("waarde":"5 €") is
        // verifier-ill-formed and faults in both uchar executors.
        String src = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €
            Objecttype de Vondst
                de waarde Numeriek (geheel getal) met eenheid €;
                het meldloon Numeriek (geheel getal) met eenheid €;
            Regel meldloon
                geldig altijd
                    Het meldloon van een Vondst moet berekend worden als de waarde van de Vondst plus 5 €.
            """;
        String c = emitCompact(src);
        // the emitter JSON-escapes non-ASCII, so the compacted € is the six-char escape \\u20ac
        assertTrue(c.contains(
                "{\"node\":\"Literal\",\"waarde\":\"5\",\"type\":{\"node\":\"Numeriek\",\"geheel\":true,"
                + "\"decimalen\":0,\"teken\":\"ONBEPAALD\",\"eenheid\":{\"node\":\"Eenheid\",\"afkorting\":\"EUR\","
                + "\"stelsel\":\"Munt\",\"canoniek\":\"EUR\",\"factorNaarCanoniek\":1,\"noemer\":null}},\"bron\":\"5\\u20ac\"}"),
                () -> "de unit-literal moet een kaal lexeme dragen met de eenheid in het type:\n" + c);
        assertFalse(c.contains("\"waarde\":\"5\\u20ac\""),
                () -> "de eenheid mag niet in het literal-lexeme zitten:\n" + c);
    }

    @Test void percentage_literal_lexeme_is_kaal() {
        // Same WF-081 fence for the '%' suffix: "21%" emits waarde "21", type Percentage
        // (whose datatype already carries the %-eenheid).
        String src = """
            Objecttype de Vondst
                het heffingspercentage Percentage (geheel getal) met eenheid %;
            Regel heffingspercentage
                geldig altijd
                    Het heffingspercentage van een Vondst moet gesteld worden op 21%.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains("{\"node\":\"Literal\",\"waarde\":\"21\",\"type\":{\"node\":\"Percentage\""),
                () -> "de percentage-literal moet een kaal lexeme dragen:\n" + c);
        assertFalse(c.contains("\"waarde\":\"21%\""),
                () -> "het %-teken mag niet in het literal-lexeme zitten:\n" + c);
    }

    @Test void datum_literal_lexeme_is_iso_snippet_in_bron() {
        // uchar WF-084 (IR-SPEC ch. 4.1): a date literal's waarde is the BARE ISO lexeme the uchar
        // executors parse (yyyy-mm-dd), NOT the Dutch day-first source snippet ("31-01-2024"). The
        // old form ("waarde":"31-01-2024") was silently misread as year 31 (0036-07-16) by both
        // executors — a differential-blind wrong answer. The source snippet stays in bron.
        String src = """
            Objecttype de Vondst
                de vondstdag Datum in dagen;
                is verjaard kenmerk (bijvoeglijk);
            Regel verjaard
                geldig altijd
                    Een Vondst is verjaard
                    indien zijn vondstdag eerder is dan 31-01-2024.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains(
                "{\"node\":\"Literal\",\"waarde\":\"2024-01-31\",\"type\":{\"node\":\"DatumInDagen\"},\"bron\":\"31-01-2024\"}"),
                () -> "de datum-literal moet het kale ISO-lexeme dragen, de bron-snippet in bron:\n" + c);
        assertFalse(c.contains("\"waarde\":\"31-01-2024\""),
                () -> "de day-first bron-snippet mag niet in het literal-lexeme zitten:\n" + c);
    }

    @Test void datum_literal_dedato_prefix_wordt_iso_lexeme() {
        // The §13.2.19 "dd. " de-dato prefix (and the day-first order) belong to the source snippet,
        // never to the parsed lexeme: "dd. 31-01-2024" emits waarde "2024-01-31", bron unchanged.
        String src = """
            Objecttype de Vondst
                de vondstdag Datum in dagen;
                is verjaard kenmerk (bijvoeglijk);
            Regel verjaard
                geldig altijd
                    Een Vondst is verjaard
                    indien zijn vondstdag eerder is dan dd. 31-01-2024.
            """;
        String c = emitCompact(src);
        assertTrue(c.contains("{\"node\":\"Literal\",\"waarde\":\"2024-01-31\",\"type\":{\"node\":\"DatumInDagen\"}"),
                () -> "de de-dato-literal moet het kale ISO-lexeme dragen:\n" + c);
        assertFalse(c.contains("\"waarde\":\"dd.31-01-2024\"") || c.contains("\"waarde\":\"31-01-2024\""),
                () -> "de bron-snippet (prefix of day-first) mag niet in het literal-lexeme zitten:\n" + c);
    }

    @Test void datumtijd_literal_lexeme_is_iso_snippet_in_bron() {
        // uchar WF-095 (IR-SPEC ch. 4.1, narrowed 2026-09-22): a datum-tijd literal's waarde is the
        // canonical ISO lexeme yyyy-mm-ddThh:mm:ss.fff, the day-first source snippet stays in bron —
        // the same split the date branch makes. The snippet in waarde is refused by the verifier,
        // so this is what keeps the emitter's own output on the wire. The "dd. " prefix belongs to
        // the snippet too.
        String src = """
            Objecttype de Aanmelding
                de meldstip Datum en tijd in millisecondes;
                is tijdig kenmerk (bijvoeglijk);
            Regel tijdig
                geldig altijd
                    Een Aanmelding is tijdig
                    indien zijn meldstip eerder is dan %s.
            """;
        // emitRaw, not emitCompact: the snippet's own space is part of what bron must keep.
        String c = emitRaw(src.formatted("15-03-2026 12:00:00.000"));
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile(
                "\\{\\s*\"node\"\\s*:\\s*\"Literal\",\\s*\"waarde\"\\s*:\\s*\"([^\"]*)\",\\s*"
                + "\"type\"\\s*:\\s*\\{\\s*\"node\"\\s*:\\s*\"DatumTijd\"\\s*\\},\\s*\"bron\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = literal.matcher(c);
        assertTrue(m.find(), () -> "geen DatumTijd-literal in de emissie:\n" + c);
        assertEquals("2026-03-15T12:00:00.000", m.group(1), "waarde draagt het ISO-lexeme");
        assertEquals("15-03-2026 12:00:00.000", m.group(2), "bron draagt de bron-snippet, spatie inbegrepen");
        String d = emitRaw(src.formatted("dd. 5-3-2026 09:07:05.004"));
        java.util.regex.Matcher md = literal.matcher(d);
        assertTrue(md.find(), () -> "geen DatumTijd-literal in de emissie:\n" + d);
        assertEquals("2026-03-05T09:07:05.004", md.group(1),
                "de-dato-prefix en eencijferige dag/maand horen bij de snippet, niet bij het lexeme");
        assertEquals("dd. 5-3-2026 09:07:05.004", md.group(2));
    }

    @Test void datumtijd_literal_buiten_de_kalender_is_een_fout() {
        // The DATE token admits 30-02-2026 12:00:00.000; there is no ISO lexeme for it, so the emitter
        // reports an ERROR (which stops emission) instead of writing a lexeme WF-095 would refuse.
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Aanmelding
                de meldstip Datum en tijd in millisecondes;
                is tijdig kenmerk (bijvoeglijk);
            Regel tijdig
                geldig altijd
                    Een Aanmelding is tijdig
                    indien zijn meldstip eerder is dan 30-02-2026 12:00:00.000.
            """);
        assertEquals(1, m.errors().size(), () -> "verwacht precies een fout, kreeg: " + m.errors());
        assertTrue(m.errors().toString().contains("datum-tijd-literal '30-02-2026 12:00:00.000'"),
                () -> "de fout noemt het lexeme niet: " + m.errors());
    }

    /** A Tekst-attribuut plus one rule that assigns {@code lexeem} to it — the smallest model that
     *  puts a text literal on the wire. The lexeme is inserted RAW, so a test can hand it a source
     *  form the grammar rejects and assert on the diagnostics. */
    private static String tekstBron(String lexeem) {
        return """
            Objecttype de Vondst
                het etiket Tekst;
            Regel etiket
                geldig altijd
                    Het etiket van een Vondst moet gesteld worden op %s.
            """.formatted(lexeem);
    }

    @Test void tekst_literal_lexeme_is_de_geparste_waarde_snippet_in_bron() {
        // uchar, maintainer decision 2026-09-18 (IR-SPEC ch. 4.1): a Tekst literal's waarde is the
        // PARSED string — what stands BETWEEN the delimiters — not the source snippet with its own
        // outer '"' still attached. The old form ("waarde":"\"Flevoland\"") gave v0 two lexeme
        // families for one text value, and since all four consumers compare Tekst verbatim, an
        // emitted golden only played when its case input carried the delimiters too. Third in the
        // bare-lexeme row beside WF-081 (number) and WF-084 (date); bron keeps the source snippet.
        String c = emitCompact(tekstBron("\"Flevoland\""));
        assertTrue(c.contains(
                "{\"node\":\"Literal\",\"waarde\":\"Flevoland\",\"type\":{\"node\":\"Tekst\"},\"bron\":\"\\\"Flevoland\\\"\"}"),
                () -> "de tekst-literal moet de geparste waarde dragen, de bron-snippet in bron:\n" + c);
        assertFalse(c.contains("\"waarde\":\"\\\"Flevoland\\\"\""),
                () -> "de buitenste aanhalingstekens mogen niet in het literal-lexeme zitten:\n" + c);
    }

    @Test void tekst_literal_grammatica_kent_geen_escape_dus_nooit_een_ingebed_aanhalingsteken() {
        // WHY the unquoting is total and lossless, measured at the grammar rather than assumed:
        // tekstWaarde is STRING_START tekstReeksDeel* STRING_END and a literal chunk is STRING_TEXT
        // ~["«»\r\n]+ (RegelSpraakLexer.g4). There is NO escape sequence, so a '"' always CLOSES the
        // string and a chunk can never hold one. Dropping the delimiters therefore removes exactly
        // the delimiters — it can never eat a character the author wrote, and it never unescapes.
        // The consequence this pins: a wire value with an embedded double quote (merk "AA", which
        // corpus/golden/jutterswet-keurmerk carries) is HAND-AUTHORED by construction — this emitter
        // cannot write one. What both back-ends do with such a value stays verbatim; that flank is
        // pinned by LiteralenTest.tekstOfEnumLabel_leest_tekst_verbatim and the Drools dual-branch
        // witnesses, not here.
        RegelSpraakModel m = Resolver.resolve(tekstBron("\"merk \"AA\"\""));
        assertFalse(m.errors().isEmpty(),
                "de grammatica kent geen escape: een tweede aanhalingsteken sluit de string en de rest is syntaxfout");
        assertTrue(m.errors().toString().contains("syntaxfout"),
                () -> "verwacht een syntaxfout, kreeg: " + m.errors());
    }

    @Test void tekst_literal_normaliseert_verder_niets() {
        // "Outer delimiters off, nothing else" — taken from the STRING_TEXT tokens, not from
        // bron.snippet() minus its first and last char. DefinitionResolver.src collapses every
        // whitespace run to one space, so a snippet-derived value silently rewrote a double space;
        // bron still shows that collapse, which is exactly why the two fields differ here. A
        // backslash and a single quote are ordinary characters and stay as written; an empty string
        // stays empty (zero STRING_TEXT chunks, not a two-character lexeme).
        String raw = emitRaw(tekstBron("\"a  b\""));
        assertTrue(raw.contains("\"waarde\": \"a  b\""),
                () -> "de dubbele spatie binnen de delimiters hoort bij de waarde:\n" + raw);
        assertTrue(raw.contains("\"bron\": \"\\\"a b\\\"\""),
                () -> "bron blijft de (whitespace-gevouwen) bronsnippet:\n" + raw);

        String backslash = emitCompact(tekstBron("\"pad\\naar\""));
        assertTrue(backslash.contains("\"waarde\":\"pad\\\\naar\""),
                () -> "een backslash is een gewoon teken, niet een escape-inleiding:\n" + backslash);

        String enkel = emitRaw(tekstBron("\"het 'merk'\""));
        assertTrue(enkel.contains("\"waarde\": \"het 'merk'\""),
                () -> "enkele aanhalingstekens zijn gewone tekens in een Tekst-literal:\n" + enkel);

        String leeg = emitCompact(tekstBron("\"\""));
        assertTrue(leeg.contains("{\"node\":\"Literal\",\"waarde\":\"\",\"type\":{\"node\":\"Tekst\"},\"bron\":\"\\\"\\\"\"}"),
                () -> "de lege tekst is leeg, niet twee aanhalingstekens:\n" + leeg);
    }

    /** A domain plus a Vondst whose {@code oordeel} is of that domain and whose {@code etiket} is Tekst,
     *  with {@code regels} appended — the smallest model that puts an enumeratiewaarde on the wire in
     *  each of the positions that can (and cannot) supply a domain. */
    private static String enumBron(String regels) {
        return """
            Domein Keurklasse is van het type Enumeratie
                'gaaf'
                'beschadigd'
            Objecttype de Vondst
                de staat Keurklasse;
                het oordeel Keurklasse;
                het etiket Tekst;
                is veilbaar kenmerk (bijvoeglijk);
            %s
            """.formatted(regels);
    }

    /** The one enumeratiewaarde shape the wire admits: the BARE label (WF-039 crosses it byte-exactly
     *  against the declared domain) typed with the domain the position names (WF-034 (r) refuses the
     *  unknown sentinel), the delimited source lexeme in bron. */
    private static final String GAAF_GETYPEERD =
            "{\"node\":\"Literal\",\"waarde\":\"gaaf\","
            + "\"type\":{\"node\":\"Enumeratie\",\"domein\":\"Keurklasse\",\"waarden\":[\"gaaf\",\"beschadigd\"]},"
            + "\"bron\":\"'gaaf'\"}";

    @Test void enum_literal_krijgt_zijn_domein_van_het_schrijfdoel() {
        // uchar V8 (spec/proposals/V1-WIREMIGRATIE.md, maintainer decision 2026-09-19): an
        // enumeratiewaarde reaches the wire as the bare label typed with its domain. The old form
        // ("waarde":"'gaaf'", type the unknown sentinel) is doubly ill-formed — WF-039 crosses the
        // label byte-exactly against the declared value list and WF-034 (r) refuses the sentinel —
        // so the frontend could not emit the construct at all and no bron.rgs golden carried one.
        // Derivation rule, first half: the domain comes from the WRITE TARGET, i.e. the declared
        // datatype of the attribute being assigned (§13.3.4.2 / WF-023's keuzetype clause).
        String c = emitCompact(enumBron("""
            Regel oordeel
                geldig altijd
                    Het oordeel van een Vondst moet gesteld worden op 'gaaf'.
            """));
        assertTrue(c.contains(GAAF_GETYPEERD),
                () -> "het schrijfdoel moet het domein van de enumeratiewaarde leveren:\n" + c);
        assertFalse(c.contains("\"waarde\":\"'gaaf'\""),
                () -> "de delimiters mogen niet in het literal-lexeme zitten:\n" + c);
    }

    @Test void enum_literal_krijgt_zijn_domein_van_de_andere_vergelijkingszijde() {
        // Derivation rule, second half: in a comparison the domain comes from the OTHER side — the
        // same context §13.3.4.2's membership check already reads (RuleChecker.meldVergelijk crosses
        // the label against the sibling's domain). Both directions, since the rule is symmetric:
        // the marker is promoted whether it stands left or right of the operator.
        String rechts = emitCompact(enumBron("""
            Regel veilbaarheid
                geldig altijd
                    Een Vondst is veilbaar indien zijn staat gelijk is aan 'gaaf'.
            """));
        assertTrue(rechts.contains(GAAF_GETYPEERD),
                () -> "de linkerzijde moet het domein van de rechter enumeratiewaarde leveren:\n" + rechts);

        String links = emitCompact(enumBron("""
            Regel veilbaarheid
                geldig altijd
                    Een Vondst is veilbaar indien 'gaaf' gelijk is aan zijn staat.
            """));
        assertTrue(links.contains(GAAF_GETYPEERD),
                () -> "de rechterzijde moet het domein van de linker enumeratiewaarde leveren:\n" + links);
    }

    @Test void enum_literal_door_de_letketen_van_een_variabelendeel() {
        // §11/ch. 4.10: a value written through the variabelendeel denotes exactly what the bare literal
        // denotes (ch. 10.6), so it must reach the wire in the same shape — and the whole chain must, not
        // just its value: Let and BinderRef are typed FROM the value, so an unpromoted chain would carry
        // the unknown sentinel at THREE positions WF-034 (r) refuses instead of one. This is also what
        // keeps the variabelendeel diagnostically transparent (VariabeledeelLoweringTest pins that the
        // §13.3.4.2 membership check says the same thing through the chain as beside it).
        String c = emitCompact(enumBron("""
            Regel oordeel
                geldig altijd
                    Het oordeel van een Vondst moet gesteld worden op de tint.
                    Daarbij geldt:
                        de tint is 'gaaf'.
            """));
        String enumType = "{\"node\":\"Enumeratie\",\"domein\":\"Keurklasse\",\"waarden\":[\"gaaf\",\"beschadigd\"]}";
        assertTrue(c.contains("\"node\":\"Let\",\"type\":" + enumType + ",\"binder\":\"tint\",\"value\":"
                        + GAAF_GETYPEERD + ",\"body\":{\"node\":\"BinderRef\",\"name\":\"tint\",\"type\":" + enumType),
                () -> "de hele letketen moet het domein van het schrijfdoel dragen:\n" + c);
    }

    @Test void enum_literal_zonder_domeincontext_blijft_een_luide_marker() {
        // The fail-fast half of the rule: where NEITHER a write target nor a comparison sibling names
        // an Enumeratie, the frontend has no domain to derive and emits the honest marker rather than
        // guessing one. A Tekst target is exactly such a position. The marker is loud — Resolver's
        // marker discipline (2026-09-19) raises a WARNING beside every Onopgelost that survives into
        // the finished model — and it carries the delimited source lexeme, so the author sees which
        // one stayed unresolved. A guessed domain would be worse than a marker: it would put a label
        // on the wire under a domain nobody declared for this position.
        RegelSpraakModel m = Resolver.resolve(enumBron("""
            Regel etiket
                geldig altijd
                    Het etiket van een Vondst moet gesteld worden op 'gaaf'.
            """));
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = IrEmitter.emit(m).replaceAll("\\s+", "");
        assertTrue(c.contains("{\"node\":\"Onopgelost\",\"type\":{\"node\":\"Onbekend\","
                        + "\"reden\":\"nietopgelost\"},\"bron\":\"'gaaf'\"}"),
                () -> "een enumeratiewaarde zonder domeincontext hoort een marker te zijn:\n" + c);
        assertTrue(m.diagnostics.stream().anyMatch(d -> !d.isError()
                        && d.toString().contains("een enumeratiewaarde ontleent haar domein aan de context")),
                () -> "de marker moet luid zijn (WARNING met reden): " + m.diagnostics);
    }

    @Test void enum_literal_label_komt_uit_de_tokentekst_niet_uit_de_gevouwen_snippet() {
        // WHERE the label is read, measured at the grammar: ENUM_LITERAL is ONE lexer token
        // ('\'' ~['\r\n]* '\'', RegelSpraakLexer.g4), so the token text IS the whole source slice and
        // the delimiters are its first and last character — stripping them is total and lossless.
        // DefinitionResolver.src, which every other bron goes through, folds each whitespace run to a
        // single space; the DECLARATION does not (enumeratie() takes the token text), so a folded
        // label would not equal the declared value and WF-039 would refuse a document the frontend
        // itself wrote. Reading the token keeps wire and declaration byte-identical.
        String raw = emitRaw("""
            Domein Kleur is van het type Enumeratie
                'licht  blauw'
                'rood'
            Objecttype de Jutter
                de kleur Kleur;
            Regel kleur
                geldig altijd
                    De kleur van een Jutter moet gesteld worden op 'licht  blauw'.
            """);
        assertTrue(raw.contains("\"waarde\": \"licht  blauw\""),
                () -> "het label moet dezelfde witruimte-run houden als de declaratie:\n" + raw);
        assertTrue(raw.contains("\"bron\": \"'licht  blauw'\""),
                () -> "bron is de tokentekst, niet de gevouwen snippet:\n" + raw);
        assertFalse(raw.contains("licht blauw"),
                () -> "geen enkele positie mag de gevouwen vorm dragen — declaratie en label zouden uiteenlopen:\n" + raw);
    }

    @Test void tijdsevenredig_deel_draagt_basistype_geen_sentinel() {
        // §7.3.2/§13.4.17.58: the pro-rata node carries its BASE's value type on the wire — the
        // uchar spec forbids the unknown-type sentinel inside a structured node (its WF-034), so
        // an onbekend-typed TIJDSEVENREDIG_DEEL cannot enter its corpus.
        String src = """
            Objecttype de Bakenmeester (mv: Bakenmeesters) (bezield)
                het onderhoudstarief Numeriek (getal met 2 decimalen) voor elk jaar;
                de onderhoudsbijdrage Numeriek (getal met 2 decimalen) voor elke maand;
                is dienstdoend kenmerk (bijvoeglijk) voor elke maand;
            Regel onderhoudsbijdrage
                geldig altijd
                    De onderhoudsbijdrage van een Bakenmeester moet gesteld worden op het tijdsevenredig deel per maand van zijn onderhoudstarief gedurende de tijd dat hij dienstdoend is.
            """;
        String c = emitCompact(src);
        int at = c.indexOf("\"kind\":\"TIJDSEVENREDIG_DEEL\"");
        assertTrue(at >= 0, () -> "de pro-rata-node ontbreekt:\n" + c);
        // scope to the node's OWN type field: it sits between the node's dimensieSelectie
        // (the first one after kind — argument refs and the Kenmerk filter carry none) and
        // its bron, so the base argument's type object cannot satisfy the assertion.
        int from = c.indexOf("\"dimensieSelectie\"", at);
        int to = c.indexOf("\"bron\"", from);
        assertTrue(from > at && to > from, () -> "de node-veldvolgorde is onverwacht:\n" + c);
        String ownType = c.substring(from, to);
        assertTrue(ownType.contains("\"type\":{\"node\":\"Numeriek\"") && ownType.contains("\"decimalen\":2"),
                () -> "de pro-rata-node draagt het basistype (Numeriek, 2 decimalen) niet zelf:\n" + ownType);
        assertFalse(ownType.contains("\"node\":\"Onbekend\""),
                () -> "de pro-rata-node mag niet de onbekend-sentinel dragen:\n" + ownType);
    }

    @Test void bron_annotaties_op_de_draad_heel_of_niet() {
        // uchar WF-082 (IR-SPEC ch. 3.1): the // Bron: statuutannotaties above a rule ride the
        // ResolvedRegel as the optional bronAnnotaties array (after geldigheid), in source order;
        // a rule WITHOUT annotations leaves no trace, and a blank annotation is dropped (an
        // empty or blank form is verifier-ill-formed — written whole or not at all).
        String src = """
            Objecttype de Jutter
                de vondstteller Numeriek (geheel getal);
                het drempeloverschot Numeriek (geheel getal);
                het hardheidsoverschot Numeriek (geheel getal);
                de jaarsom Numeriek (geheel getal);
            // Bron: art. 3 Jutterswet
            // Bron: art. 5 Jutterswet
            Regel hardheidsoverschot
                geldig altijd
                    Het hardheidsoverschot van een Jutter moet berekend worden als de vondstteller van de Jutter min 6.
            Regel jaarsom
                geldig altijd
                    De jaarsom van een Jutter moet berekend worden als het drempeloverschot van de Jutter plus het hardheidsoverschot van de Jutter.
            //Bron:
            Regel drempeloverschot
                geldig altijd
                    Het drempeloverschot van een Jutter moet berekend worden als de vondstteller van de Jutter min 12.
            """;
        String c = emitCompact(src);
        // emitCompact strips ALL whitespace, also inside string values.
        assertTrue(c.contains("\"bronAnnotaties\":[\"art.3Jutterswet\",\"art.5Jutterswet\"]}"),
                () -> "beide annotaties moeten in bronvolgorde na geldigheid staan:\n" + c);
        assertEquals(c.indexOf("bronAnnotaties"), c.lastIndexOf("bronAnnotaties"),
                () -> "alleen de geannoteerde regel draagt het veld (geen spoor bij afwezigheid, blanco vervalt):\n" + c);
    }

    /**
     * IR-SPEC ch. 4.10 — {@code IfThenElse} is NEVER EMITTER-PRODUCED, and this pins what that means for this
     * class. No RegelSpraak construct lowers to the node, so the resolver builds none and no golden's re-emission
     * can meet one; the sealed {@code Expr} union nevertheless forces an arm here, and that arm REFUSES rather
     * than serialising. The reason is that an emit path nothing exercises could carry a wrong key order or a
     * wrong field name — the wire spells them {@code condition}/{@code then}/{@code else} — and no gate would
     * ever read those bytes: a loud refusal cannot be silently wrong, a dead serialiser can. Pinned as a TYPE
     * and a reason, so the choice is checked instead of merely commented.
     *
     * <p>The node is grafted onto an otherwise resolver-built model, because the resolver itself will not
     * produce one — which is the claim under test.
     */
    @Test void een_ifthenelse_wordt_geweigerd_want_de_emitter_produceert_hem_nooit() {
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Jutter
                de vondstteller Numeriek (geheel getal);
                de jaarsom Numeriek (geheel getal);
            Regel jaarsom
                geldig altijd
                    De jaarsom van een Jutter moet berekend worden als de vondstteller van de Jutter min 6.
            """);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.resolvedRegels.size());
        // sanity: the resolver produced no such node by itself — "never emitter-produced" starts there.
        ResolvedRegel origineel = m.resolvedRegels.get(0);
        assertFalse(Expr.bevat(origineel.waarde(), x -> x instanceof Expr.IfThenElse),
                "de resolver bouwt de vorm niet");

        Expr keuze = new Expr.IfThenElse(
                new Conditie.Vergelijking(Conditie.Operator.GROTER, origineel.waarde(),
                        new Expr.Literal("0", origineel.waarde().type(), Bron.synthetisch("0")),
                        Bron.synthetisch("groter dan 0")),
                origineel.waarde(), origineel.waarde(), origineel.waarde().type(),
                Bron.synthetisch("als … dan … anders …"));
        m.resolvedRegels.set(0, new ResolvedRegel(origineel.naam(), origineel.soort(), origineel.doel(),
                keuze, origineel.voorwaarde(), origineel.geldigheid()));

        UnsupportedOperationException geweigerd =
                assertThrows(UnsupportedOperationException.class, () -> IrEmitter.emit(m),
                        "de emitter schrijft deze vorm niet; hij weigert haar");
        assertTrue(geweigerd.getMessage().contains("never emitter-produced"),
                () -> "de weigering noemt de reden: " + geweigerd.getMessage());
    }
}
