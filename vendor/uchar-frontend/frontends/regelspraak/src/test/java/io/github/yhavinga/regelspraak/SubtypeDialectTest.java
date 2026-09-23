package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The uchar-dialect {@code is een} extension (SUBTYPING §9.2): the head form
 * {@code Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)} declares the single direct
 * supertype the kernel carries as {@code Objecttype.supertype} (IR-SPEC ch. 2.2).
 *
 * <p>These tests pin BOTH sides. The refusal fixtures are the frontend's share of the four-rule
 * family — WF-103 (the parent is a declared object type), WF-104 (acyclic, self-reference
 * included), WF-105 (no shadowing along the chain) and WF-106 (at most one direct supertype). WF-106
 * is the one that can have NO verifier check and NO corpus/negatief: on the wire a second parent is
 * unwritable, so the rule bounds the language ABOVE the wire and this fixture is one of its two
 * witnesses (IR-SPEC ch. 2.2, SUBTYPING §3.1). The happy-path tests pin the parse position, the
 * order-free document-wide resolution, the emitted wire key, and the additivity invariant — a
 * document without the extension emits no {@code supertype} at all.
 */
class SubtypeDialectTest {

    /** The emitted v0 IR JSON with all whitespace removed — an exact compacted fragment pins the wire shape. */
    private static String emitCompact(RegelSpraakModel m) {
        return IrEmitter.emit(m).replaceAll("\\s+", "");
    }

    /** The {@code resolvedRegels} array of a compacted emission — the part a declaration SPLIT (one flat
     *  type versus a subtype and its parent) leaves untouched, so two documents that mean the same rule
     *  compare equal here even though their objecttypen sections differ. */
    private static String regeldeel(String compact) {
        int van = compact.indexOf("\"resolvedRegels\":");
        int tot = compact.indexOf("\"resolvedDagsoorten\":");
        assertTrue(van >= 0 && tot > van, () -> "geen resolvedRegels-sectie in de emissie:\n" + compact);
        return compact.substring(van, tot);
    }

    /** The single error text, asserting exactly one error was raised (so a test pins the intended refusal, not
     *  some unrelated diagnostic). */
    private static String enigeFout(RegelSpraakModel m) {
        assertEquals(1, m.errors().size(), () -> "verwachtte precies één fout, kreeg: " + m.errors());
        return m.errors().get(0).message();
    }

    private static final String KETEN = """
        Objecttype het Strandrecht (mv: Strandrechten)
            de grondslag Numeriek (niet-negatief getal met 2 decimalen);
            is betwist kenmerk (bijvoeglijk);

        Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
            de bergingsdiepte Numeriek (niet-negatief geheel getal);
        """;

    // -------------------------------------------------------------------- rood-eerst: de weigeringen

    @Test void twee_is_een_clausules_weigeren_onder_wf106() {
        // The second direct parent. WF-106 has no verifier check and no negatief — a bare-string
        // `supertype` cannot WRITE two parents — so this fixture is one of its two witnesses.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Zeerecht (mv: Zeerechten)
                de diepgang Numeriek (niet-negatief geheel getal);

            Objecttype het Wrakrecht is een Strandrecht is een Zeerecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "twee 'is een'-clausules op één kop horen luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-106"), () -> "de melding citeert WF-106 niet: " + fout);
        assertTrue(fout.contains("Strandrecht") && fout.contains("Zeerecht"),
                () -> "de melding noemt de twee ouders niet: " + fout);
        assertTrue(fout.contains("RechtOpUitkeringWazo") && fout.contains("RechtUitkeringZw"),
                () -> "de melding noemt de twee bekende meervoudige-overervingsgevallen niet: " + fout);
    }

    @Test void is_een_a_en_b_weigert_onder_wf106() {
        // The same refusal through the other spelling the sentence admits: one clause, two names.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Zeerecht (mv: Zeerechten)
                de diepgang Numeriek (niet-negatief geheel getal);

            Objecttype het Wrakrecht is een Strandrecht en een Zeerecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "'is een A en B' hoort luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-106"), () -> "de melding citeert WF-106 niet: " + fout);
        assertTrue(fout.contains("RechtOpUitkeringWazo") && fout.contains("RechtUitkeringZw"),
                () -> "de melding noemt de twee bekende meervoudige-overervingsgevallen niet: " + fout);
    }

    @Test void zelfverwijzing_weigert_onder_wf104() {
        String src = """
            Objecttype het Wrakrecht is een Wrakrecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "'X is een X' hoort luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-104"), () -> "de melding citeert WF-104 niet: " + fout);
        assertTrue(fout.contains("Wrakrecht"), () -> "de melding noemt het objecttype niet: " + fout);
    }

    @Test void supertypecyclus_weigert_onder_wf104() {
        // The depth-1 cycle above falls independently of this two-step one: a reader that
        // special-cases the self-edge passes the first and not the second.
        String src = """
            Objecttype het Strandrecht is een Wrakrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een supertypecyclus hoort luid te weigeren");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("WF-104")),
                () -> "geen enkele melding citeert WF-104: " + m.errors());
    }

    @Test void onbekend_supertype_weigert_onder_wf103() {
        String src = """
            Objecttype het Wrakrecht is een Zeerecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een ongedeclareerd supertype hoort luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-103"), () -> "de melding citeert WF-103 niet: " + fout);
        assertTrue(fout.contains("Zeerecht"), () -> "de melding noemt de onbekende naam niet: " + fout);
    }

    @Test void supertype_dat_een_domein_is_weigert_onder_wf103() {
        // A name the document DOES declare, but not as an object type: the reference resolves to
        // nothing a chain can walk, and saying "onbekend" would send the author looking for a typo.
        String src = """
            Domein Bergingsbedrag is van het type Numeriek (niet-negatief getal met 2 decimalen)

            Objecttype het Wrakrecht is een Bergingsbedrag (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een supertype dat een domein noemt hoort luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-103"), () -> "de melding citeert WF-103 niet: " + fout);
        assertTrue(fout.contains("domein"), () -> "de melding benoemt de soort van de naam niet: " + fout);
    }

    @Test void geshadowd_attribuut_weigert_onder_wf105() {
        // The frontend refuses BEFORE the verifier does: it has the chain at hand.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                de grondslag Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een geshadowd attribuut hoort luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-105"), () -> "de melding citeert WF-105 niet: " + fout);
        assertTrue(fout.contains("grondslag") && fout.contains("Strandrecht"),
                () -> "de melding noemt het lid of de declarant niet: " + fout);
    }

    @Test void geshadowd_kenmerk_weigert_onder_wf105() {
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                is betwist kenmerk (bijvoeglijk);

            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                is betwist kenmerk (bijvoeglijk);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "een geshadowd kenmerk hoort luid te weigeren");
        String fout = enigeFout(m);
        assertTrue(fout.contains("WF-105"), () -> "de melding citeert WF-105 niet: " + fout);
        assertTrue(fout.contains("betwist"), () -> "de melding noemt het kenmerk niet: " + fout);
    }

    // -------------------------------------------------------------------- de kopvorm

    @Test void de_kopvorm_bindt_het_supertype() {
        RegelSpraakModel m = Resolver.resolve(KETEN);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        var wrak = m.objecttypen.get("Wrakrecht");
        assertNotNull(wrak);
        assertSame(m.objecttypen.get("Strandrecht"), wrak.supertype);
        // the annotations after the clause still land: (mv: …) is read, not swallowed by the parent name
        assertEquals("Wrakrechten", wrak.meervoud);
        assertFalse(wrak.bezield);
        // the member maps stay OWN; the chain is what closes them
        assertEquals(1, wrak.attributen.size(), "een subtype declareert alleen zijn eigen leden");
        assertNotNull(wrak.attribuut("grondslag"), "een geërfd attribuut lost op via de keten");
        assertTrue(wrak.heeftLid("betwist"), "een geërfd kenmerk lost op via de keten");
        assertTrue(wrak.isEen(m.objecttypen.get("Strandrecht")));
    }

    @Test void de_kopvorm_draagt_ook_bezield_na_de_meervoudsgroep() {
        // The clause sits between the name and the (mv: …) group, so BOTH existing head annotations
        // must still be reachable behind it — the grammar already fixes their mutual order.
        String src = """
            Objecttype de Kustbewoner (mv: Kustbewoners) (bezield)
                de leeftijd Numeriek (niet-negatief geheel getal);

            Objecttype de Strandvoogd is een Kustbewoner (mv: Strandvoogden) (bezield)
                het ambtsjaar Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        var voogd = m.objecttypen.get("Strandvoogd");
        assertEquals("Kustbewoner", voogd.supertype.naam);
        assertEquals("Strandvoogden", voogd.meervoud);
        assertTrue(voogd.bezield, "(bezield) achter de mv-groep hoort nog steeds te binden");
    }

    @Test void een_voorwaartse_verwijzing_is_geen_fout() {
        // Resolution is document-wide and order-free: only an undeclared name refuses.
        String src = """
            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);

            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "een voorwaartse verwijzing hoort geen fout te zijn: " + m.errors());
        assertEquals("Strandrecht", m.objecttypen.get("Wrakrecht").supertype.naam);
    }

    @Test void een_doel_op_twee_niveaus_is_geen_weigering() {
        // Explicit NON-refusal (SUBTYPING §9.2): the kernel's group inheritance governs, and the
        // kernel witnesses are the semantic referee.
        String src = KETEN + """
            Regel uitkering van een strandrecht
                geldig altijd
                    De grondslag van een Strandrecht moet gesteld worden op 10,00.

            Regel uitkering van een wrakrecht
                geldig altijd
                    De grondslag van een Wrakrecht moet gesteld worden op 20,00.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(),
                () -> "een doel dat op twee niveaus regels draagt is geen fout: " + m.errors());
        assertEquals(2, m.resolvedRegels.size());
    }

    // -------------------------------------------------------------------- de wire

    @Test void het_supertype_gaat_de_wire_op_direct_na_naam() {
        RegelSpraakModel m = Resolver.resolve(KETEN);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"name\":\"Wrakrecht\",\"supertype\":\"Strandrecht\","),
                () -> "supertype hoort direct na naam op de wire te staan (schema-sleutelvolgorde):\n" + c);
    }

    @Test void een_worteltype_emitteert_geen_supertype_sleutel() {
        // Additivity / the § Method's "whole or not at all": a flat document emits no supertype key,
        // so the v0-mirror byte-identity of every flat golden holds.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertFalse(emitCompact(m).contains("supertype"),
                "een document zonder de extensie mag de supertype-sleutel niet emitteren");
    }

    @Test void het_lidwoord_voor_de_supertypenaam_hoort_bij_de_frase() {
        // "is een <Naam>" — the article is the language's own onbepaaldLidwoord, read exactly where
        // the language reads one elsewhere; a bare "is <Naam>" is not the head form and must not bind.
        // ANTLR can only call that "no viable alternative", which points at the wrong thing, so the
        // frontend names the fix beside it.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Wrakrecht is Strandrecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "'is <Naam>' zonder lidwoord is de kopvorm niet en hoort te weigeren");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("mist het lidwoord")
                        && d.message().contains("is een Strandrecht")),
                () -> "geen gerichte hint over het ontbrekende lidwoord: " + m.errors());
    }

    @Test void de_clausule_achter_de_meervoudsgroep_wijst_naar_zijn_plek() {
        // The other common authoring slip: the head annotations have a fixed mutual order and the
        // extension inserts one more element, so the author has to be told WHERE rather than that
        // something is unparseable.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Wrakrecht (mv: Wrakrechten) is een Strandrecht
                de bergingsdiepte Numeriek (niet-negatief geheel getal);
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "de clausule achter de mv-groep is de kopvorm niet en hoort te weigeren");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("achter de (mv: …)-groep")),
                () -> "geen gerichte hint over de annotatievolgorde: " + m.errors());
    }

    @Test void een_correcte_kop_krijgt_geen_kopvormhint() {
        // The hints are raised only alongside a real syntax error, and the head walk stops at the
        // first token no head can contain — so ordinary rule text, where "is een" is plain Dutch,
        // never draws one. The syntax error here is elsewhere in the document on purpose.
        String src = """
            Objecttype de Dag (mv: Dagen)
                is schouwdag kenmerk (bijvoeglijk);

            Objecttype het Wrakrecht is een Dag (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);

            Regel onzin
                geldig altijd
                    Een Dag is een schouwdag indien @@@.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.isOk(), "het document draagt met opzet een echte syntaxfout");
        assertTrue(m.errors().stream().noneMatch(d -> d.message().startsWith("kopvorm:")),
                () -> "een correcte kop (en gewone regeltekst) hoort geen kopvormhint te krijgen: " + m.errors());
    }

    // -------------------------------------------------------------------- de onvoorwaardelijke basis

    @Test void een_basisregel_zonder_indien_krijgt_de_altijd_rechtvaardiging() {
        // Surfaced by this card's golden and pinned here rather than in DefaultsExtensieTest, whose
        // fixtures all carry an 'indien': until now no lifted base rule was UNCONDITIONAL. WF-050
        // demands a justification on every non-initialisation default, so a §13.4.2 rule without a
        // condition — the ch. 7.8 standing norm — lowers to the vacuously true one, which is exactly
        // how the hand-written prioritized-defaults anchors spell "altijd". Emitting null there is
        // verifier-rejected, so this is the difference between a golden and a red gate 3.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de grondslag Numeriek (niet-negatief getal met 2 decimalen);
                de uitkering Numeriek (niet-negatief getal met 2 decimalen);
                is betwist kenmerk (bijvoeglijk);

            Regel uitkering van een strandrecht
                geldig altijd
                    De uitkering van een Strandrecht moet gesteld worden op zijn grondslag.

            Uitzondering op uitkering van een strandrecht: uitkering van een betwist strandrecht
                geldig altijd
                    De uitkering van een Strandrecht moet gesteld worden op 99,00
                    indien het Strandrecht betwist is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.prioritizedDefaults.size());
        var basis = m.prioritizedDefaults.get(0).defaults().get(1);
        var altijd = assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Samengesteld.class,
                basis.voorwaarde(), "de onvoorwaardelijke basis hoort een rechtvaardiging te dragen (WF-050)");
        assertEquals(io.github.yhavinga.regelspraak.ir.Conditie.Kwantor.ALLE, altijd.kwantor());
        assertTrue(altijd.onderdelen().isEmpty(), "'altijd' is een ALLE-groep zonder onderdelen");
        assertTrue(emitCompact(m).contains(
                        "\"voorwaarde\":{\"node\":\"Samengesteld\",\"kwantor\":\"ALLE\",\"drempel\":0,"
                        + "\"onderdelen\":[],\"bron\":\"altijd\"}"),
                () -> "de wire draagt de altijd-rechtvaardiging niet:\n" + emitCompact(m));
    }

    // -------------------------------------------------------------------- de rolbinding-consequentie

    @Test void een_subtype_dat_het_typepaar_dubbel_verbindt_houdt_zijn_bronRol() {
        // SUBTYPING §3.4 / IR-SPEC ch. 2.2: WF-048's uniqueness is computed over the CHAIN-CLOSED
        // type-pair set, so adding a subtype can turn a previously well-formed BINDINGLESS fold into
        // a loud refusal. A role's declared filler is an UPPER bound, so once Wrakrecht joins the
        // chain a Wrakrecht fills BOTH the Strandrecht role and its own — the pair
        // Kustgemeente x Wrakrecht is doubly connected and the by-pair search goes silent. The
        // frontend is ahead of it: the written element name still names exactly one role, so the fold
        // keeps a bronRol and never goes out bindingless.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de uitkering Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);

            Objecttype de Kustgemeente (mv: Kustgemeenten) (bezield)
                het uitkeringstotaal Numeriek (niet-negatief getal met 2 decimalen);

            Feittype strandrechtregistratie
                de registrerende kustgemeente Kustgemeente
                het geregistreerd strandrecht (mv: geregistreerde strandrechten) Strandrecht
            Eén registrerende kustgemeente registreert meerdere geregistreerde strandrechten

            Feittype wrakberging
                de bergende kustgemeente Kustgemeente
                het geborgen wrakrecht (mv: geborgen wrakrechten) Wrakrecht
            Eén bergende kustgemeente bergt meerdere geborgen wrakrechten

            Regel uitkeringstotaal van een kustgemeente
                geldig altijd
                    Het uitkeringstotaal van een Kustgemeente moet berekend worden als de som van de uitkering van alle geborgen wrakrechten van de Kustgemeente of 0,00 als die er niet zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        // the by-PAIR search is now ambiguous — both fact types relate the chain-closed pair
        assertNull(m.verbindendeRolBinding(m.objecttypen.get("Kustgemeente"), m.objecttypen.get("Wrakrecht")),
                "het typepaar hoort door het subtype dubbel verbonden te zijn");
        String c = emitCompact(m);
        assertTrue(c.contains("\"bronRol\":{\"node\":\"RolBinding\",\"feittype\":\"wrakberging\","
                        + "\"rol\":\"geborgenwrakrecht\"}"),
                () -> "de vouwbron hoort op de GESCHREVEN rol te binden in plaats van bindingloos de wire op te "
                        + "gaan (WF-048 zou die dan weigeren):\n" + c);
    }

    // ------------------------------------------------- de ketenresolutie in de resolverlaag (§3.2)
    // The model has carried the chain-aware accessors since the kernel; these pin that the RESOLVER
    // reads through them. Each one is a member of an ANCESTOR used from a SUBTYPE subject — the shape
    // that binds nowhere when a reader consults the own declaration maps only.
    //
    // Measured on the unrepaired base, because it decides what these tests are FOR: five of the six
    // places fail LOUDLY. The wire is well fenced — a name that no declaration carries is refused by
    // WF-034, a gedurende condition that did not resolve degrades to `Onopgelost` and is refused by
    // WF-055, an `Onopgelost` reached in evaluation faults with WF-044, and three of the places raise
    // an outright frontend ERROR on a document that is not wrong. So the damage is a correct document
    // made unusable, not a wrong number slipping through — with ONE exception, plek 6 below, which
    // emits a member name no declaration carries, verifier-clean and diagnostics-free.

    @Test void een_verdeling_naar_een_subtype_bindt_het_geerfde_doelattribuut() {
        // Plek 1, MeervoudMatcher.vindAttribuut. The sentence writes the PLURAL "bergingsaandelen";
        // the declaration — singular, with its own "(mv: …)" — sits on Berger, one hop up. Reading the
        // subtype's own maps binds nothing, so the written plural stayed on the wire as `doelAttribuut`,
        // where no declaration carries it and WF-034 refuses the document. This is the p18 refusal the
        // previous card traced here.
        String src = """
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (niet-negatief geheel getal);
                de onverdeelde rest Numeriek (niet-negatief geheel getal);

            Objecttype de Berger (mv: Bergers) (bezield)
                het bergingsaandeel (mv: bergingsaandelen) Numeriek (niet-negatief geheel getal);

            Objecttype de Hoofdberger is een Berger (mv: Hoofdbergers) (bezield)
                de rang Numeriek (niet-negatief geheel getal);

            Feittype hoofdbergingsdeelname
                de hoofdberging Berging
                de hoofdberger (mv: hoofdbergers) Hoofdberger
            Eén hoofdberging heeft meerdere hoofdbergers

            Regel verdeel de opbrengst van een berging
                geldig altijd
                    De opbrengst van een berging wordt verdeeld over de bergingsaandelen van alle hoofdbergers van de berging, waarbij wordt verdeeld:
                    - in gelijke delen,
                    - afgerond op 0 decimalen naar beneden.
                    Als onverdeelde rest blijft de onverdeelde rest van de berging over.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"doelType\":\"Hoofdberger\",\"doelAttribuut\":\"bergingsaandeel\""),
                () -> "de verdeling hoort het GEDECLAREERDE enkelvoud van de voorouder op de wire te zetten:\n" + c);
        assertFalse(c.contains("bergingsaandelen\","),
                () -> "de geschreven meervoudsvorm hoort nergens als lidnaam op de wire te staan:\n" + c);
    }

    @Test void een_geerfd_kenmerk_bindt_in_een_predicaat() {
        // Plek 2, KenmerkMatcher. An inherited kenmerk bound in NO predicaat form — across all eleven
        // call sites — and the condition fell through to an Onopgelost marker. That marker is LOUD, not
        // silent: reaching it in evaluation faults with WF-044, so the rule does not quietly never fire,
        // it takes the document down. The soort check right after the binding (kenmerkSoort) was already
        // chain-aware, so binding and soort now see one and the same declarant.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                is betwist kenmerk (bijvoeglijk);
                de aanmelding kenmerk;

            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                de uitkering Numeriek (niet-negatief getal met 2 decimalen);

            Regel uitkering van een betwist wrakrecht
                geldig altijd
                    De uitkering van een Wrakrecht moet gesteld worden op 99,00
                    indien het Wrakrecht betwist is.

            Regel uitkering van een aangemeld wrakrecht
                geldig altijd
                    De uitkering van een Wrakrecht moet gesteld worden op 88,00
                    indien het Wrakrecht een aanmelding is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"node\":\"Kenmerk\",\"kenmerk\":\"betwist\""),
                () -> "een geërfd bijvoeglijk kenmerk hoort te binden in plaats van een marker te blijven:\n" + c);
        // the EEN-form reads the §3.5 soort of the SAME declarant the binding found
        assertTrue(c.contains("\"node\":\"Kenmerk\",\"kenmerk\":\"aanmelding\""),
                () -> "een geërfd zelfstandig kenmerk hoort in de een-vorm te binden:\n" + c);
        assertFalse(c.contains("Onopgelost"), () -> "geen enkele voorwaarde hoort een marker te blijven:\n" + c);
    }

    @Test void een_objectcreatie_zet_een_geerfd_kenmerk() {
        // Plek 3. The attribute branch of maakToekenning already resolved through the chain; the kenmerk
        // branch read the own list and ended on an ERROR ("is geen attribuut of kenmerk van") where a
        // binding belongs — the two branches of one sentence disagreeing about what the type has.
        String src = """
            Objecttype de Berging (mv: Bergingen)
                de opbrengst Numeriek (niet-negatief geheel getal);

            Objecttype de Berger (mv: Bergers) (bezield)
                is betwist kenmerk (bijvoeglijk);

            Objecttype de Hoofdberger is een Berger (mv: Hoofdbergers) (bezield)
                de rang Numeriek (niet-negatief geheel getal);

            Feittype hoofdbergingsdeelname
                de hoofdberging Berging
                de hoofdberger (mv: hoofdbergers) Hoofdberger
            Eén hoofdberging heeft meerdere hoofdbergers

            Regel benoem een hoofdberger
                geldig altijd
                    Een Berging heeft een hoofdberger met betwist gelijk aan waar.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"node\":\"Toekenning\",\"attribuut\":\"betwist\","),
                () -> "een geërfd kenmerk hoort als toekenning te binden, niet als fout:\n" + c);
        assertTrue(c.contains("\"kenmerk\":true"),
                () -> "de toekenning hoort als KENMERK te binden (een boolean setter, geen attribuut):\n" + c);
    }

    @Test void een_geerfde_tijdlijn_klemt_het_totaal() {
        // Plek 4: the temporality family had NO chain accessor at all, so a subtype subject reading an
        // ancestor's timeline attribute answered "not temporal". The §7.1 gedurende clip then does not
        // silently disappear — it DEGRADES to an `Onopgelost` filter plus a WARNING, and WF-055 refuses
        // that on the wire. The cost is a correct document made unusable, which is why the repair is
        // owed. Both the operand (attribuutGranulariteit) and the condition (kenmerkGranulariteit) are
        // inherited here, so the two halves are pinned at once.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de wachtvergoeding per maand Numeriek (geheel getal) voor elke maand;
                is wachtplichtig kenmerk (bijvoeglijk) voor elke maand;

            Objecttype de Klipjutter is een Jutter (mv: Klipjutters) (bezield)
                de wachtsom Numeriek (geheel getal);

            Regel wachtsom van een klipjutter
                geldig altijd
                    De wachtsom van een Klipjutter moet gesteld worden op (het totaal van zijn wachtvergoeding per maand gedurende de tijd dat hij wachtplichtig is).
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"filter\":{\"node\":\"Kenmerk\",\"kenmerk\":\"wachtplichtig\""),
                () -> "de gedurende-klem hoort gebonden op de wire te staan in plaats van als Onopgelost, "
                        + "dat WF-055 weigert:\n" + c);
    }

    @Test void een_geerfde_tijdlijn_draagt_de_hele_periode_check() {
        // Plek 4, the loud half — the same blindness, the other direction: §8.2's whole-period qualifier
        // is only defined on a tijdsafhankelijk onderwerp, so a subject read as non-temporal turned a
        // perfectly good rule into an ERROR. Both arms of onderwerpIsTemporeel are inherited here.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de wachtvergoeding per maand Numeriek (geheel getal) voor elke maand;
                is wachtplichtig kenmerk (bijvoeglijk) voor elke maand;

            Objecttype de Klipjutter is een Jutter (mv: Klipjutters) (bezield)
                de wachtsom Numeriek (geheel getal);
                de borgsom Numeriek (geheel getal);

            Regel wachtsom van een klipjutter
                geldig altijd
                    De wachtsom van een Klipjutter moet gesteld worden op 10
                    indien zijn wachtvergoeding per maand gedurende het gehele jaar groter is dan 5.

            Regel borgsom van een klipjutter
                geldig altijd
                    De borgsom van een Klipjutter moet gesteld worden op 20
                    indien hij gedurende het gehele jaar wachtplichtig is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(),
                () -> "een geërfde tijdlijn hoort de §8.2-controle gewoon te dragen: " + m.errors());
        assertFalse(emitCompact(m).contains("Onopgelost"),
                "geen van beide §8.2-voorwaarden hoort een marker te worden");
    }

    @Test void een_geerfd_temporeel_attribuut_met_gewone_eenheid_is_geen_tabel30_fout() {
        // Plek 4 again, and the third failure mode of one blindness: §4.20 Tabel 30 demands a rate unit
        // for "het totaal van", EXCEPT when the operand is a timeline attribute — its per-tijdseenheid
        // dimension lives in the declaration, not in the unit. Read as non-temporal, the exception lapses
        // and a correct document draws a spurious type error.
        String src = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €

            Objecttype de Jutter (mv: Jutters) (bezield)
                de maandvergoeding Numeriek (geheel getal) met eenheid € voor elke maand;

            Objecttype de Klipjutter is een Jutter (mv: Klipjutters) (bezield)
                de wachtsom Numeriek (geheel getal) met eenheid €;

            Regel wachtsom van een klipjutter
                geldig altijd
                    De wachtsom van een Klipjutter moet gesteld worden op het totaal van zijn maandvergoeding.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(),
                () -> "een geërfd tijdlijnattribuut met een gewone eenheid is de Tabel-30-fout niet: " + m.errors());
    }

    @Test void een_geerfde_tijdreeks_ontsnapt_niet_aan_de_statische_wacht() {
        // Plek 4, the escape: §8.4 admits a NON-temporal comparison bullet beside the temporal ones
        // precisely because it "heeft geen verschillende waarden op verschillende momenten".
        // `toeslag per maand` is inherited and IS a timeline, so the bullet reads a List<Segment>;
        // judged non-temporal it slipped past the guard into a scalar comparison term. The escape is
        // real IN THE RESOLVER and stops there: WF-055 narrows composite gedurende members to
        // characteristic checks, so the base's Samengesteld filter is refused on the wire too — with a
        // different reason than the honest marker the repair produces. Both are refusals; this test
        // pins that the guard itself no longer lets a timeline through. The operand and the first
        // bullet are the subtype's OWN, so nothing but that guard is under test.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de toeslag per maand Numeriek (geheel getal) voor elke maand;

            Objecttype de Klipjutter is een Jutter (mv: Klipjutters) (bezield)
                de wachtvergoeding per maand Numeriek (geheel getal) voor elke maand;
                is wachtplichtig kenmerk (bijvoeglijk) voor elke maand;
                de tegenwachtsom Numeriek (geheel getal);

            Regel tegenwachtsom van een klipjutter
                geldig altijd
                    De tegenwachtsom van een Klipjutter moet gesteld worden op (het totaal van zijn wachtvergoeding per maand gedurende de tijd dat hij aan alle volgende voorwaarden voldoet:
                    - hij wachtplichtig is
                    - zijn toeslag per maand groter is dan 10).
            """;
        // the FLAT equivalent — one type carrying all four members — is the reference outcome: the
        // frontend must treat the subtype subject exactly as it treats this.
        String vlak = """
            Objecttype de Klipjutter (mv: Klipjutters) (bezield)
                de toeslag per maand Numeriek (geheel getal) voor elke maand;
                de wachtvergoeding per maand Numeriek (geheel getal) voor elke maand;
                is wachtplichtig kenmerk (bijvoeglijk) voor elke maand;
                de tegenwachtsom Numeriek (geheel getal);

            Regel tegenwachtsom van een klipjutter
                geldig altijd
                    De tegenwachtsom van een Klipjutter moet gesteld worden op (het totaal van zijn wachtvergoeding per maand gedurende de tijd dat hij aan alle volgende voorwaarden voldoet:
                    - hij wachtplichtig is
                    - zijn toeslag per maand groter is dan 10).
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertFalse(c.contains("\"filter\":{\"node\":\"Samengesteld\""),
                () -> "een tijdreekslezing hoort niet als statische vergelijking in de samengestelde "
                        + "gedurende-conditie te belanden:\n" + c);
        assertTrue(c.contains("\"filter\":{\"node\":\"Onopgelost\""),
                () -> "de niet-lowerbare samengestelde conditie hoort een eerlijke marker te zijn:\n" + c);
        assertEquals(regeldeel(emitCompact(Resolver.resolve(vlak))), regeldeel(c),
                "een subtype-subject met geërfde tijdlijnleden hoort exact als het vlakke equivalent te "
                        + "worden behandeld");
    }

    @Test void een_geerfd_gedimensioneerd_attribuut_leest_zijn_cel() {
        // Plek 5, dimensieCel. `dimensiesVan` existed as a chain accessor and was used two lines later
        // for the fold; the cell READER iterated the own map, so a §3.6 single-cell read of an inherited
        // gedimensioneerd attribuut resolved to nothing and stayed a marker.
        String src = """
            Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
                1. voorjaar
                2. najaar

            Objecttype de Jutter (mv: Jutters) (bezield)
                het vondstgewicht Numeriek (niet-negatief getal met 2 decimalen) gedimensioneerd met seizoen;

            Objecttype de Strandjutter is een Jutter (mv: Strandjutters) (bezield)
                het voorjaarsgewicht Numeriek (niet-negatief getal met 2 decimalen);

            Regel voorjaarsgewicht van een strandjutter
                geldig altijd
                    Het voorjaarsgewicht van een Strandjutter moet berekend worden als zijn vondstgewicht in voorjaar.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"node\":\"DimensieCel\""),
                () -> "een cellezing van een geërfd gedimensioneerd attribuut hoort te resolven:\n" + c);
        assertTrue(c.contains("\"labels\":[\"voorjaar\"]"),
                () -> "de cel hoort haar gedeclareerde label te dragen:\n" + c);
    }

    @Test void een_geerfd_gedimensioneerd_attribuut_gaat_gedeclareerd_de_wire_op() {
        // Plek 6, and the ONLY one of the six whose failure is silent. The §5.8.5 interval fold
        // reconstructs the attribute it folds, and it took the declared spelling from the own map:
        // for an inherited attribute that lookup misses and the WRITTEN form ("Betaalde belasting",
        // as the sentence capitalises it) goes onto the wire. Zero diagnostics, and the verifier
        // passes it — no WF rule holds an AttribuutRef name against the declarations, so nothing
        // downstream catches a member name that no declaration carries. The other five fail loudly.
        String src = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar

            Objecttype de Persoon (mv: Personen)
                de betaalde belasting Numeriek (geheel getal) gedimensioneerd met jaardimensie;

            Objecttype de Ondernemer is een Persoon (mv: Ondernemers)
                het totaal Numeriek (geheel getal);

            Regel tel
                geldig altijd
                    Het totaal van een Ondernemer moet berekend worden als de som van zijn Betaalde belasting over de jaardimensies vanaf vorig jaar t/m huidig jaar.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"attribuut\":\"betaaldebelasting\""),
                () -> "de vouw hoort de GEDECLAREERDE spelling te dragen:\n" + c);
        assertFalse(c.contains("\"attribuut\":\"Betaaldebelasting\""),
                () -> "de geschreven spelling op de wire is een lidnaam die geen declaratie draagt — en "
                        + "geen enkele WF-regel houdt een AttribuutRef-naam tegen de declaraties:\n" + c);
    }

    @Test void twee_geerfde_tijdlijnsleutels_weigeren_de_uniciteit() {
        // Plek 4, telTemporeel. §8.1.6 ever-uniqueness admits at most ONE temporal key component; a
        // second needs the same-moment tuple join no reading pins, so the whole rule stays a WARNING.
        // Counting the temporal components on the SUBJECT'S OWN maps calls two inherited timelines
        // static and admits a key with no defined semantics.
        //
        // This one is NOT red on the untouched base, and the reason is worth naming: there plek 1
        // stops the sentence one step earlier (no written form of an inherited attribute binds, so
        // the source resolves to nothing at all) and this check is never reached. Repairing plek 1
        // is what makes the count load-bearing, so both directions are pinned here at once — with
        // one temporal key the rule must RESOLVE, with two it must be REFUSED. A chain-blind count
        // would resolve both.
        String eenTijdlijn = """
            Objecttype de Wachter (mv: Wachters) (bezield)
                het wachtnummer Numeriek (geheel getal);
                het borgnummer Numeriek (geheel getal) voor elke maand;

            Objecttype de Klipwachter is een Wachter (mv: Klipwachters) (bezield)
                de post Numeriek (niet-negatief geheel getal);

            Regel wachtsleutel
                geldig altijd
                    De concatenatie van wachtnummer en borgnummer van alle Klipwachters moeten uniek zijn.
            """;
        RegelSpraakModel een = Resolver.resolve(eenTijdlijn);
        assertEquals(1, een.uniciteiten.size(),
                () -> "één geërfde tijdlijncomponent is toegestaan en hoort te resolven: " + een.diagnostics);
        assertEquals("Klipwachter", een.uniciteiten.get(0).subject().naam);

        String tweeTijdlijnen = eenTijdlijn.replace(
                "het wachtnummer Numeriek (geheel getal);",
                "het wachtnummer Numeriek (geheel getal) voor elke maand;");
        RegelSpraakModel twee = Resolver.resolve(tweeTijdlijnen);
        assertTrue(twee.uniciteiten.isEmpty() && twee.uniciteitUnies.isEmpty(),
                () -> "twee GEËRFDE tijdlijncomponenten horen de §8.1.6-ooituniciteit te laten weigeren: "
                        + twee.uniciteiten + twee.uniciteitUnies);
    }

    @Test void een_genavigeerde_tijdsevenredige_basis_leest_de_keten_van_het_doeltype() {
        // Plek 4, the navigated §7.3.2 base. The operand is a to-one navigated timeline attribute of
        // the RELATED type; that type's facets were read on its own maps, so a related SUBTYPE whose
        // timeline attribute is declared one hop up failed the operand gate and the clip degraded to
        // an Onopgelost the wire refuses (WF-055) — again unusable, not silently wrong.
        String src = """
            Objecttype de Reis (mv: Reizen)
                het reistarief Numeriek (geheel getal) voor elk jaar;

            Objecttype de Zeereis is een Reis (mv: Zeereizen)
                de diepgang Numeriek (niet-negatief geheel getal);

            Objecttype de Jutter (mv: Jutters) (bezield)
                is varend kenmerk (bijvoeglijk) voor elke maand;
                de reisbijdrage Numeriek (geheel getal) voor elke maand;

            Feittype reisdeelname
                de reizende jutter Jutter
                de vaartocht Zeereis
            Eén reizende jutter maakt één vaartocht

            Regel reisbijdrage van een jutter
                geldig altijd
                    De reisbijdrage van een Jutter moet gesteld worden op het tijdsevenredig deel per maand van het reistarief van de vaartocht gedurende de tijd dat hij varend is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"kind\":\"TIJDSEVENREDIG_DEEL\""), () -> "geen tijdsevenredige vouw:\n" + c);
        assertTrue(c.contains("\"attribuut\":\"reistarief\",\"objecttype\":\"Zeereis\""),
                () -> "de basis hoort het geërfde tijdlijnattribuut van het genavigeerde subtype te "
                        + "lezen:\n" + c);
        // the operand gate is what this pins: only a single-period timeline base admits the clip, so a
        // bound filter proves the navigated type's facets were read through its chain
        assertTrue(c.contains("\"filter\":{\"node\":\"Kenmerk\",\"kenmerk\":\"varend\""),
                () -> "een geërfde tijdlijn achter een to-one navigatie hoort de klem te dragen in plaats "
                        + "van een marker te worden:\n" + c);
    }

    @Test void een_genavigeerd_geerfd_tijdlijnkenmerk_klemt_het_tijdsevenredig_deel() {
        // Plek 4, gedurendeNavigatieKenmerk. Same navigated shape, now on the CONDITION side: the
        // kenmerk must be a single-period timeline kenmerk of the navigated type, read on its own
        // maps. An inherited one answered "not temporal" and the clip fell away.
        String src = """
            Objecttype de Reis (mv: Reizen)
                is gestremd kenmerk (bijvoeglijk) voor elke maand;

            Objecttype de Zeereis is een Reis (mv: Zeereizen)
                de diepgang Numeriek (niet-negatief geheel getal);

            Objecttype de Jutter (mv: Jutters) (bezield)
                het jaartarief Numeriek (geheel getal) voor elk jaar;
                de reisbijdrage Numeriek (geheel getal) voor elke maand;

            Feittype reisdeelname
                de reizende jutter Jutter
                de vaartocht Zeereis
            Eén reizende jutter maakt één vaartocht

            Regel reisbijdrage van een jutter
                geldig altijd
                    De reisbijdrage van een Jutter moet gesteld worden op het tijdsevenredig deel per maand van zijn jaartarief gedurende de tijd dat zijn vaartocht gestremd is.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"filter\":{\"node\":\"Kenmerk\",\"kenmerk\":\"gestremd\",\"navigatie\":\"vaartocht\""),
                () -> "de genavigeerde klem hoort te binden op het geërfde tijdlijnkenmerk:\n" + c);
    }

    @Test void een_setlezing_over_een_geerfde_tijdlijn_blijft_de_tijdvouw() {
        // Plek 4, setLezing. A set read over a NON-timeline attribute is the pure instance fold; over
        // a TIMELINE attribute it is the §7.1 per-element fold over time, and the §4.20 rate strip
        // must keep applying to it. Read on the element's own maps, an inherited timeline made the
        // fold look like the plain one — the strip lapsed and the unit came out wrong.
        String src = """
            Eenheidsysteem Munt
                de euro (mv: euros) EUR €

            Objecttype de Jutter (mv: Jutters) (bezield)
                de maandloon Numeriek (geheel getal) met eenheid € voor elke maand;

            Objecttype de Klipjutter is een Jutter (mv: Klipjutters) (bezield)
                de post Numeriek (niet-negatief geheel getal);

            Objecttype de Wacht (mv: Wachten)
                de loonsom Numeriek (geheel getal) met eenheid €;

            Feittype wachtbezetting
                de wacht Wacht
                de klipjutter (mv: klipjutters) Klipjutter
            Eén wacht heeft meerdere klipjutters

            Regel loonsom van een wacht
                geldig altijd
                    De loonsom van een Wacht moet berekend worden als het totaal van de maandloon van alle klipjutters van de Wacht.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        // The §4.20 strip drops the /tijdseenheid from the RESULT: over a timeline operand the fold
        // integrates over time, so a plain-EUR target is the right one and no unit error is raised.
        assertTrue(m.errors().isEmpty(),
                () -> "een setlezing over een GEËRFDE tijdlijn is de gewone instantievouw niet: " + m.errors());
        assertTrue(emitCompact(m).contains("\"kind\":\"TOTAAL\""), "de vouw hoort een totaal te blijven");
    }

    @Test void een_naam_die_een_rol_in_een_ander_feittype_treft_bindt_niet() {
        // The other side of the fallback, and the one that decides whether it may exist at all. The name
        // candidates are formed by DROPPING LEADING WORDS, so "alle geborgen wrakrechten" is also tried as
        // "wrakrechten" — which here names a role in a DIFFERENT fact type, filled by the supertype. An
        // upper-bound filler test would wave that through (Wrakrecht ⊑ Strandrecht) and the fold would go
        // out bound to a relation the sentence never named: verifier-clean, and quietly folding the wrong
        // set. The binding therefore demands the role declare the element type ITSELF, and where it does
        // not the fold stays bindingless — WF-048 then refuses it loudly, which is the outcome to want.
        String src = """
            Objecttype het Strandrecht (mv: Strandrechten)
                de uitkering Numeriek (niet-negatief getal met 2 decimalen);

            Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)
                de bergingsdiepte Numeriek (niet-negatief geheel getal);

            Objecttype de Kustgemeente (mv: Kustgemeenten) (bezield)
                het uitkeringstotaal Numeriek (niet-negatief getal met 2 decimalen);

            Feittype wrakberging
                de bergende kustgemeente Kustgemeente
                het geborgen wrakrecht Wrakrecht
            Eén bergende kustgemeente bergt meerdere geborgen wrakrechten

            Feittype strandbeheer
                de beherende kustgemeente Kustgemeente
                het wrakrecht (mv: wrakrechten) Strandrecht
            Eén beherende kustgemeente beheert meerdere wrakrechten

            Regel uitkeringstotaal van een kustgemeente
                geldig altijd
                    Het uitkeringstotaal van een Kustgemeente moet berekend worden als de som van de uitkering van alle geborgen wrakrechten van de Kustgemeente of 0,00 als die er niet zijn.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertFalse(c.contains("\"feittype\":\"strandbeheer\""),
                () -> "de vouwbron bindt op een relatie die de zin niet noemt — stil de verkeerde verzameling:\n" + c);
        assertFalse(c.contains("\"bronRol\""),
                () -> "zonder een rol die het elementtype ZELF draagt hoort de vouwbron bindingloos te blijven, "
                        + "zodat WF-048 haar luid weigert:\n" + c);
    }
}
