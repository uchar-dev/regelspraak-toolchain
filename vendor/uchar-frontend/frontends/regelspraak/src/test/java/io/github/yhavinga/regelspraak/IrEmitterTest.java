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
