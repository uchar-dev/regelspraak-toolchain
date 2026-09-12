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
        assertTrue(c.contains("\"naam\":\"Wrakrecht\",\"supertype\":\"Strandrecht\","),
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
