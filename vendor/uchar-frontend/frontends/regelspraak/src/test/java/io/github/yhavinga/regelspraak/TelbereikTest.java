package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The COUNT's element word and the range that follows from it — "het aantal &lt;X&gt; van &lt;onderwerp&gt;",
 * the v0 row-1 wart, whose source the emitter derives through {@code elementRolBinding} on the WRITTEN
 * name (IrEmitter.bronRolBinding, the arm keyed on the unknown sentinel).
 *
 * <p>Two things meet here and this suite pins both.
 *
 * <ol>
 * <li><b>The element word may name a POPULATION.</b> Until 2026-09-16 it was looked up as the SINGULAR
 *     of an object type or as a role name, so a written plural denoting a declared type resolved to
 *     nothing: the fold went out with {@code elementObjecttype: null} and the uchar verifier refused the
 *     document under WF-034. The population-plural lookup (ExpressieBouwer.aantalElement, the
 *     {@code typeViaMeervoud} fallback) closed that.
 * <li><b>No role is called that, so the fold goes out BINDINGLESS.</b> That is not a defect: a
 *     {@code bronRol} whose role name the sentence does not write is a guess about WHICH collection is
 *     counted, and the wire has a second, honest spelling for the case where the sentence names no
 *     relation — no field at all, the source derived by every consumer from the TYPE PAIR (IR-SPEC
 *     ch. 4.3). WF-048 is what keeps that derivation sound: it is well-formed only while exactly one
 *     fact type relates the two types.
 * </ol>
 *
 * <p>The ambiguous flank therefore lands on the verifier and not here: with a second fact type between
 * the same pair the frontend still binds nothing — it never guesses, which is the behaviour pinned below
 * — and the document fails <b>WF-034 and WF-048 at once</b>, one at the source argument
 * ({@code 'Munten' is neither a member of 'Jutter' nor a declared role, and the type pair
 * 'Jutter'/'Munt' does not derive a unique connecting role (2 candidates)}) and one at the fold
 * ({@code bindingless instance fold: 2 fact types relate 'Jutter' to 'Munt'}). That verdict is a corpus
 * artifact, {@code corpus/negatief/wf048-telling-meerduidig}, emitted from exactly the source below; the
 * accepting twin is {@code corpus/golden/jutterswet-muntpeiling}, which plays the same sentence through
 * ref, clingo and Drools.
 */
class TelbereikTest {

    /** One fact type between Jutter and Munt, and its far role is NOT called "munt": so the written
     *  "Munten" can only be the object type's own declared plural. */
    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het muntaantal Numeriek (niet-negatief geheel getal);

        Objecttype de Munt (mv: Munten)

        Feittype muntvondst
            de vinder Jutter
            de gevonden munt (mv: gevonden munten) Munt
        Eén vinder vindt meerdere gevonden munten
        """;

    /** The same model with a SECOND Jutter–Munt fact type: the type pair stops being unique. */
    static final String MEERDUIDIG_MODEL = MODEL + """

        Feittype muntbewaring
            de bewaarder Jutter
            de bewaarde munt (mv: bewaarde munten) Munt
        Eén bewaarder bewaart meerdere bewaarde munten
        """;

    /** A second object type declaring the SAME plural — the population name binds nothing. */
    static final String GEDEELD_MEERVOUD_MODEL = MODEL + """

        Objecttype het Muntstuk (mv: Munten)
        """;

    /** The COLLISION: one written word that is the object type's declared plural AND a role's declared
     *  plural at the same time. Both readings reach the same type, so nothing is ambiguous about the
     *  POPULATION; the question is only whether the relation is named, and it is. */
    static final String BOTSEND_MEERVOUD_MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het muntaantal Numeriek (niet-negatief geheel getal);

        Objecttype de Munt (mv: Munten)

        Feittype muntvondst
            de vinder Jutter
            de munt (mv: Munten) Munt
        Eén vinder vindt meerdere Munten
        """;

    /** No fact type relates Jutter to Munt at all — the type pair has ZERO candidates, not two. */
    static final String ONVERBONDEN_MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het muntaantal Numeriek (niet-negatief geheel getal);

        Objecttype de Munt (mv: Munten)

        Objecttype de Schelp (mv: Schelpen)

        Feittype schelpvondst
            de vinder Jutter
            de gevonden schelp (mv: gevonden schelpen) Schelp
        Eén vinder vindt meerdere gevonden schelpen
        """;

    static final String TYPEMEERVOUD_REGEL = """

        Regel muntaantal van een jutter
            geldig altijd
                Het muntaantal van een Jutter moet berekend worden als het aantal Munten van de Jutter.
        """;

    static final String ROLNAAM_REGEL = """

        Regel muntaantal van een jutter
            geldig altijd
                Het muntaantal van een Jutter moet berekend worden als het aantal gevonden munten van de Jutter.
        """;

    /** An element word that names neither a declared type nor a role. */
    static final String VREEMDE_REGEL = """

        Regel muntaantal van een jutter
            geldig altijd
                Het muntaantal van een Jutter moet berekend worden als het aantal schelpen van de Jutter.
        """;

    // ---------------- the population name ----------------

    /** The arm this suite exists for: the count's element word is the object type's declared plural, and
     *  it resolves. Before the population-plural lookup this emitted {@code "elementObjecttype": null}. */
    @Test
    void een_telling_op_een_typemeervoud_bindt_het_objecttype() {
        String c = ir(MODEL + TYPEMEERVOUD_REGEL);
        assertTrue(c.contains("\"kind\": \"AANTAL\""), () -> "de telling hoort geëmitteerd te worden:\n" + c);
        assertTrue(c.contains("\"elementObjecttype\": \"Munt\""),
                () -> "het geschreven meervoud moet het objecttype binden:\n" + c);
    }

    /** And it goes out BINDINGLESS: no role carries that name, and a binding the sentence does not write
     *  would claim a relation it never named. The type pair is what the consumers derive from. */
    @Test
    void een_telling_op_een_typemeervoud_zet_geen_bronrol_op_de_wire() {
        String c = ir(MODEL + TYPEMEERVOUD_REGEL);
        assertFalse(c.contains("\"bronRol\""),
                () -> "de wire mag geen rolbinding dragen die de zin niet schrijft:\n" + c);
        assertFalse(c.contains("\"bronPad\""),
                () -> "een eenstaps bereik is nooit een pad:\n" + c);
        assertFalse(c.contains("\"node\": \"Onopgelost\""),
                () -> "de telling is opgelost, alleen bindingloos:\n" + c);
    }

    /** Counter-proof on the same model: written as the role the fact type DECLARES, the same count binds
     *  and the wire carries the binding. The two spellings sit side by side in
     *  {@code corpus/golden/jutterswet-muntpeiling}, pinned to the same numbers. */
    @Test
    void een_telling_op_de_geschreven_rolnaam_houdt_haar_bronrol() {
        String c = ir(MODEL + ROLNAAM_REGEL);
        assertTrue(c.contains("\"bronRol\""), () -> "de geschreven rolnaam hoort te binden:\n" + c);
        assertTrue(c.contains("\"feittype\": \"muntvondst\""), () -> "de binding noemt haar feittype:\n" + c);
        assertTrue(c.contains("\"rol\": \"gevonden munt\""), () -> "de binding noemt de gedeclareerde rol:\n" + c);
    }

    // ---------------- the ambiguous flank ----------------

    /** Two fact types between the same pair. The frontend does not guess one: it binds nothing, exactly as
     *  on the unique model, and the document goes to the verifier to be refused (WF-034 + WF-048). A
     *  {@code bronRol} appearing here would be the silent wrong answer this arm exists to avoid. */
    @Test
    void de_meerduidige_telling_gokt_geen_relatie() {
        String c = ir(MEERDUIDIG_MODEL + TYPEMEERVOUD_REGEL);
        assertTrue(c.contains("\"elementObjecttype\": \"Munt\""),
                () -> "het elementtype blijft gebonden; de meerduidigheid zit in de RELATIE:\n" + c);
        assertFalse(c.contains("\"bronRol\""),
                () -> "bij twee verbindende feittypen mag er geen gegokte binding op de wire staan:\n" + c);
        assertFalse(c.contains("\"rol\": \"gevonden munt\"") || c.contains("\"rol\": \"bewaarde munt\""),
                () -> "geen van beide relaties mag stilletjes gekozen worden:\n" + c);
    }

    // ---------------- the boundaries of the lookup ----------------

    /** The population lookup is ambiguity-safe: two declared types sharing one plural bind NEITHER, so the
     *  fold loses its element type instead of a type being picked. Since 2026-09-23 a count without an
     *  element type is refused HERE as well as on the wire (WF-037's count-source arm), so the document
     *  no longer reaches the verifier; the model still shows that no type was bound. */
    @Test
    void een_gedeeld_typemeervoud_bindt_geen_van_beide() {
        String c = irMetBronloosFout(GEDEELD_MEERVOUD_MODEL + TYPEMEERVOUD_REGEL);
        assertTrue(c.contains("\"elementObjecttype\": null"),
                () -> "een gedeeld meervoud mag geen van beide types binden:\n" + c);
    }

    /** And it invents nothing: a word that names neither a declared type nor a role leaves the fold
     *  without an element type — refused by WF-034 on the wire and, since 2026-09-23, by the
     *  count-source ERROR here, which the population lookup must not paper over either. */
    @Test
    void een_woord_dat_niets_benoemt_levert_geen_elementtype() {
        String c = irMetBronloosFout(MODEL + VREEMDE_REGEL);
        assertTrue(c.contains("\"elementObjecttype\": null"),
                () -> "een onbekend elementwoord mag geen type binden:\n" + c);
    }

    /** The COLLISION, and the order that decides it: when one written word is BOTH the object type's
     *  declared plural and a role's declared plural, the population lookup is not what answers — the
     *  role does, and the fold goes out BOUND. Both readings reach `Munt`, so no population question
     *  is open; what would be lost by binding nothing is the relation the sentence does name, and the
     *  document would then rest on a type-pair derivation it does not need. Not a new behaviour — the
     *  population lookup is a fallback and runs only where the role reading finds nothing — but the
     *  precedence had no test, so a reordering would have been invisible here. */
    @Test
    void een_woord_dat_ook_een_rolmeervoud_is_bindt_de_relatie() {
        String c = ir(BOTSEND_MEERVOUD_MODEL + TYPEMEERVOUD_REGEL);
        assertTrue(c.contains("\"elementObjecttype\": \"Munt\""),
                () -> "beide lezingen bereiken Munt, dus het elementtype staat vast:\n" + c);
        assertTrue(c.contains("\"bronRol\""),
                () -> "een woord dat een rol NOEMT hoort die rol te binden:\n" + c);
        assertTrue(c.contains("\"rol\": \"munt\""),
                () -> "de binding noemt de gedeclareerde rol:\n" + c);
    }

    /** The ZERO-candidate flank of the same fallback. Two fact types make the type pair ambiguous; NONE
     *  makes it empty, and the frontend's answer is identical in both — the element type binds, no
     *  relation is invented, the fold goes out bindingless — so the difference is the verifier's to
     *  state and it states it: {@code WF-034 … the type pair 'Jutter'/'Munt' does not derive a unique
     *  connecting role (0 candidates)} beside {@code WF-048 … bindingless instance fold: no fact type
     *  relates 'Jutter' to 'Munt'}. Measured 2026-09-16 on exactly this source. It stays a JUnit
     *  witness rather than a second negatief case: the refusing ARM is the one
     *  {@code corpus/negatief/wf048-telling-meerduidig} already declares, and a second document would
     *  pin the same two clauses over a different candidate count, whereas what has no witness at all
     *  is the FRONTEND's half — that an unconnected pair produces a resolved, bindingless fold and not
     *  a marker or a silence. */
    @Test
    void een_onverbonden_typepaar_levert_dezelfde_bindingloze_vouw() {
        String c = ir(ONVERBONDEN_MODEL + TYPEMEERVOUD_REGEL);
        assertTrue(c.contains("\"kind\": \"AANTAL\""), () -> "de telling hoort geëmitteerd te worden:\n" + c);
        assertTrue(c.contains("\"elementObjecttype\": \"Munt\""),
                () -> "het elementtype bindt ook zonder verbindend feittype:\n" + c);
        assertFalse(c.contains("\"bronRol\""),
                () -> "er is geen relatie, dus er mag er ook geen geëmitteerd worden:\n" + c);
        assertFalse(c.contains("\"node\": \"Onopgelost\""),
                () -> "de telling is opgelost; de weigering is de verifier's:\n" + c);
    }

    /** The raw emission, keys spelled as the wire spells them — whitespace is NOT stripped, because
     *  every name this suite asserts on ("gevonden munt") carries a space of its own. */
    private static String ir(String bron) {
        RegelSpraakModel m = Resolver.resolve(bron);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        return IrEmitter.emit(m);
    }

    /** A count that binds no element type: exactly one ERROR, the count-source refusal, and the model
     *  emitted anyway so the caller can read what was (not) bound. */
    private static String irMetBronloosFout(String bron) {
        RegelSpraakModel m = Resolver.resolve(bron);
        assertEquals(1, m.errors().size(), () -> "verwacht één fout, kreeg: " + m.errors());
        assertTrue(m.errors().get(0).message().startsWith("telling zonder bron"),
                () -> "verwacht de bronloze-tellingfout, kreeg: " + m.errors());
        return IrEmitter.emit(m);
    }
}
