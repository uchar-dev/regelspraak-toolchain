package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A MEMBER fold ("de minimale waarde van &lt;attr&gt; van alle &lt;elem&gt; van &lt;onderwerp&gt;") describes its
 * source on the uchar wire with exactly ONE relation: one {@code elementObjecttype} plus one {@code bronRol}
 * (IR-SPEC ch. 4.3). Three written shapes used to be mapped onto that single relation in silence, and all
 * three produced a fold over a DIFFERENT collection than the sentence names:
 *
 * <ol>
 * <li>a NESTED set step — "van alle geborgen vondsten van alle gevoerde boten van de Jutter" — whose outer
 *     step simply disappeared, the fold ranging from the rule subject straight to the innermost element type;
 * <li>an intermediate TO-ONE step — "van alle vondsten van de boot van de Jutter" — losing its step exactly
 *     the same way, and invisible to (3) because the written element name may name the subject-side role
 *     perfectly well;
 * <li>a by-PAIR binding the written name contradicts — {@code verbindendeRolBinding} searches on TYPES only,
 *     so with exactly one fact type joining subject and element it wins whatever role the sentence named.
 * </ol>
 *
 * <p>Measured before this suite, on the two documents the card pins. Without a direct Jutter–Vondst fact type
 * the misbinding was loud downstream (WF-048, "no fact type relates 'Jutter' to 'Vondst'") but named the wrong
 * cause; WITH one it was verifier-PASS and ref, the ASP backend and Drools all answered 5.00 where the sentence
 * says 3.00 — the differential square agreeing over the wrong set, which no gate can see. Dropping the binding
 * at the emitter does not help: a bindingless fold over a uniquely related pair is verifier-clean and every
 * executor re-derives the same wrong relation. So the RESOLUTION declines, and the fold keeps the visible
 * {@code Onopgelost} marker this resolver already uses for a source it cannot bind — carrying the
 * aggregation's OWN result type, so the refused document stays WELL-FORMED and faults observably under
 * WF-044 in every executor (IR-SPEC ch. 2.4: the marker is legal wire content, the fence is behavioural).
 *
 * <p><b>Updated 2026-09-11, the wire half (WF-102).</b> The range is no longer one relation: a fold source
 * may carry a {@code bronPad} of two or more steps, and the flat-union reading over it is normative
 * (IR-SPEC ch. 4.3, RUNTIME-SEMANTICS ch. 3.4b). Shapes (1) and (2) above are therefore EXPRESSIBLE where
 * the written chain RESOLVES, and this suite pins that instead of the refusal on exactly those documents —
 * the card's A, B and F. The refusals are not gone: they hold for every chain that declines (an
 * unresolvable step, an anchor that is not the subject, a walk that lands elsewhere), and each route keeps
 * a witness. Shape (3) is untouched — it is a one-step form with no range to state.
 */
class GenesteVouwbronTest {

    /** The card's chain: Jutter -(bemanning, to-many)-&gt; Boot -(berging, to-many)-&gt; Vondst.gewicht, plus the
     *  DIRECT vondstaanmelding that makes the misbinding silent — the difference between the card's document A
     *  (this model without the third feittype) and document B (this model). */
    static final String MODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het laagste vondstgewicht Numeriek (getal met 2 decimalen) met eenheid kg;
            het vondstaantal Numeriek (niet-negatief geheel getal);

        Objecttype de Boot (mv: Boten)

        Objecttype de Vondst (mv: Vondsten)
            het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg;

        Feittype bemanning
            de schipper Jutter
            de gevoerde boot (mv: gevoerde boten) Boot
        Eén schipper heeft meerdere gevoerde boten

        Feittype berging
            de bergende boot Boot
            de geborgen vondst (mv: geborgen vondsten) Vondst
        Eén bergende boot heeft meerdere geborgen vondsten
        """;

    /** The card's document A: no fact type relates Jutter to Vondst. */
    static final String MODEL_A = MODEL;

    /** The card's document B: the same chain plus a DIRECT Jutter–Vondst fact type, which is what turned the
     *  misbinding from loud-downstream into verifier-clean-and-silently-wrong. */
    static final String MODEL_B = MODEL + """

        Feittype vondstaanmelding
            de aanmelder Jutter
            de aangemelde vondst (mv: aangemelde vondsten) Vondst
        Eén aanmelder heeft meerdere aangemelde vondsten
        """;

    /** The card's sentence: a fold whose set source nests a second set step. Hand expectation FROM THE
     *  SENTENCE is 3.00 (the minimum over both boats' vondsten); the silent one-step reading answered 5.00. */
    static final String KETENREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle geborgen vondsten van alle gevoerde boten van de Jutter.
        """;

    /** The same chain with a to-ONE step in the middle — the nested set step is not the first one. */
    static final String GEMENGDE_KETENREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle geborgen vondsten van de bergende boot van alle gevoerde boten van de Jutter.
        """;

    /** ONE set step, no nesting: the written element name names a role of *berging*, but the only fact type
     *  relating Jutter to Vondst is *vondstaanmelding*. The by-pair search bound its role regardless. */
    static final String BOTSENDE_EENSTAPSREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle geborgen vondsten van de Jutter.
        """;

    /** Counter-proof: the same one-step fold written with the name the fact type declares. */
    static final String CORRECTE_EENSTAPSREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle aangemelde vondsten van de Jutter.
        """;

    /** Counter-proof: a written name that IS the element object type's own name claims no role at all, so the
     *  by-pair derivation stays the honest fallback. */
    static final String OBJECTTYPENAAMREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle Vondst van de Jutter.
        """;

    /** The to-ONE flank: the chain reaches its elements through a Boot, and the written element name names
     *  the role of the DIRECT Jutter–Vondst fact type perfectly well — so only the anchor gives it away. */
    static final String FLANKMODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het laagste vondstgewicht Numeriek (getal met 2 decimalen) met eenheid kg;

        Objecttype de Boot (mv: Boten)

        Objecttype de Vondst (mv: Vondsten)
            het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg;

        Feittype bemanning
            de schipper Jutter
            de boot Boot
        Eén schipper heeft één boot

        Feittype bootvangst
            de vangende boot Boot
            de vondst (mv: vondsten) Vondst
        Eén vangende boot heeft meerdere vondsten

        Feittype jutterbezit
            de bezitter Jutter
            de vondst (mv: vondsten) Vondst
        Eén bezitter heeft meerdere vondsten
        """;

    static final String TUSSENSTAPREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle vondsten van de boot van de Jutter.
        """;

    /** Counter-proof on the SAME model: without the intermediate step the fold binds and emits. */
    static final String DIRECTE_REGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle vondsten van de Jutter.
        """;

    /** Counter-proof: an anchor that names a ROLE resolves to no object type, so the anchor rule leaves it
     *  alone — that is the mother card's literal-anchor question and belongs to the wire, not here. */
    static final String ROLANKERREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle aangemelde vondsten van de aanmelder.
        """;

    /** Counter-proof: the §13.4.17.47 telling, whose element word sits in the ATTRIBUTE position and binds by
     *  the written name already (the Onbekend-typed wart, a different derivation). */
    static final String TELREGEL = """

        Regel vondstaantal
            geldig altijd
                Het vondstaantal van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter.
        """;

    /** Counter-proof: the same nested chain with NO fold around it — the WF-096 setlezing position, which is
     *  another card's decision and must keep its own diagnosis. */
    static final String SETLEZING_ZONDER_VOUW = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als het gewicht van alle geborgen vondsten van alle gevoerde boten van de Jutter.
        """;

    /** A chain whose anchor names an object type that is NOT the rule subject — one step, so no path can
     *  carry it, and the anchor rule refuses it exactly as before. Route (b)'s surviving witness. */
    static final String VREEMD_ANKERREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle geborgen vondsten van de Boot.
        """;

    /** MODEL_B plus a Jutter-to-Jutter relation, so a role anchor can be a FAR role: "de strandmaat" is a
     *  role REACHABLE FROM the subject, hence another instance, which ch. 4.1a resolves as a hop. */
    static final String ROLANKERMODEL = MODEL_B + """

        Feittype vriendschap
            de jutter Jutter
            de strandmaat (mv: strandmaten) Jutter
        Eén jutter heeft meerdere strandmaten
        """;

    static final String VERRE_ROLANKERREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het gewicht van alle aangemelde vondsten van de strandmaat.
        """;

    /** MODEL_B plus one more to-many relation, for the DEPTH witness: three written steps. */
    static final String DIEPTEMODEL = MODEL_B + """

        Objecttype de Kist (mv: Kisten)
            het inhoudsgewicht Numeriek (getal met 2 decimalen) met eenheid kg;

        Feittype inpakking
            de verpakte vondst Vondst
            de ingepakte kist (mv: ingepakte kisten) Kist
        Eén verpakte vondst heeft meerdere ingepakte kisten
        """;

    static final String DRIESTAPSREGEL = """

        Regel laagste vondstgewicht
            geldig altijd
                Het laagste vondstgewicht van een Jutter moet berekend worden als de minimale waarde van het inhoudsgewicht van alle ingepakte kisten van alle geborgen vondsten van alle gevoerde boten van de Jutter.
        """;

    static final String NEST_MELDING =
            "geneste verzamelingsstap in een vouwbron: 'alle geborgen vondsten van alle gevoerde boten van de "
            + "Jutter' stapelt de verzameling 'alle gevoerde boten van de Jutter' onder een tweede verzameling, "
            + "en een vouwbron draagt precies één relatie (IR-SPEC ch. 4.3: één elementObjecttype plus één "
            + "bronRol) — de buitenste stap zou spoorloos wegvallen, dus de aggregatie blijft onopgelost "
            + "(§13.4.17)";

    static final String TUSSENSTAP_MELDING =
            "tussenstap in een vouwbron: 'alle vondsten van de boot van de Jutter' bereikt de elementen via "
            + "Boot, maar een vouwbron draagt precies één relatie vanaf het regelonderwerp Jutter (IR-SPEC "
            + "ch. 4.3: één elementObjecttype plus één bronRol) — de tussenstap zou spoorloos wegvallen, dus "
            + "de aggregatie blijft onopgelost (§13.4.1/§13.4.17)";

    static final String NAAM_MELDING =
            "vouwbron bindt niet op de geschreven naam: de zin noemt 'geborgen vondsten', maar het enige "
            + "feittype dat Jutter met Vondst verbindt is 'vondstaanmelding' met de rol 'aangemelde vondst' — "
            + "de type-paar-afleiding zou een andere verzameling vouwen dan de zin noemt, dus de aggregatie "
            + "blijft onopgelost (§13.4.1/§13.4.17)";

    private static List<Diagnostic> vouwbronDiagnoses(String bron) {
        RegelSpraakModel m = Resolver.resolve(bron);
        return m.diagnostics.stream()
                .filter(d -> d.message().startsWith("geneste verzamelingsstap in een vouwbron")
                        || d.message().startsWith("tussenstap in een vouwbron")
                        || d.message().startsWith("rolanker in een vouwbron")
                        || d.message().startsWith("vouwbron bindt niet op de geschreven naam"))
                .toList();
    }

    private static String ir(String bron) {
        RegelSpraakModel m = Resolver.resolve(bron);
        assertTrue(m.isOk(), () -> "model draagt fouten: " + m.diagnostics);
        return IrEmitter.emit(m);
    }

    // ---------------- (a) the nested set step ----------------

    /** Document A: no direct relation. UNTIL 2026-09-11 this was a refusal, because the wire could not
     *  state the range; with WF-102 it states it, so the sentence resolves and the fold is emitted. */
    @Test
    void geneste_ketenzin_zonder_directe_relatie_wordt_een_bronpad() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_A + KETENREGEL));
        String json = ir(MODEL_A + KETENREGEL);
        assertTrue(json.contains("\"kind\": \"MINIMUM\""), "de vouw hoort nu gewoon geëmitteerd te worden");
        assertBronPad(json, "bemanning", "gevoerde boot", "berging", "geborgen vondst");
    }

    /** Document B — the acute flank. With a direct Jutter–Vondst fact type this document WAS verifier-clean
     *  and answered 5.00 where the sentence says 3.00, in all three executors at once. The by-pair binding
     *  must now be gone from the wire entirely: the path says which relations are walked, so there is
     *  nothing left for a type search to guess. */
    @Test
    void geneste_ketenzin_met_een_direct_feittype_wordt_ook_een_bronpad() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_B + KETENREGEL));
        String json = ir(MODEL_B + KETENREGEL);
        assertBronPad(json, "bemanning", "gevoerde boot", "berging", "geborgen vondst");
        assertFalse(json.contains("\"bronRol\""), "bronPad en bronRol sluiten elkaar uit (WF-102 (a))");
        assertFalse(json.contains("\"rol\": \"aangemelde vondst\""),
                "de wire mag geen rolbinding dragen die de zin niet schrijft");
    }

    /** The DEPTH decision, pinned: three written steps produce three bindings. A cap at two would refuse
     *  this sentence, and the corpus anchor executes the same shape. */
    @Test
    void een_driestaps_keten_draagt_drie_stappen() {
        assertEquals(List.of(), vouwbronDiagnoses(DIEPTEMODEL + DRIESTAPSREGEL));
        assertBronPad(ir(DIEPTEMODEL + DRIESTAPSREGEL),
                "bemanning", "gevoerde boot", "berging", "geborgen vondst", "inpakking", "ingepakte kist");
    }

    /** The nested set step is not always the FIRST step of the written chain: the greedy kaleNaam decides
     *  where the chain splits, so the walk must run the whole van-chain. */
    @Test
    void een_gemengde_keten_met_een_toone_stap_wordt_ook_benoemd() {
        List<Diagnostic> ds = vouwbronDiagnoses(MODEL_B + GEMENGDE_KETENREGEL);
        assertEquals(1, ds.size(), () -> "verwacht één vouwbron-diagnose, kreeg " + ds);
        assertTrue(ds.get(0).message().contains("'alle gevoerde boten van de Jutter' onder een tweede verzameling"),
                () -> "de melding moet de geneste stap noemen: " + ds.get(0).message());
    }

    /** A chain that DECLINES still produces the marker, and the refusal machinery is therefore alive: the
     *  mixed chain's middle step resolves to nothing, so no path can be stated and the old refusal stands. */
    @Test
    void een_onoplosbare_keten_blijft_een_markering() {
        String json = ir(MODEL_B + GEMENGDE_KETENREGEL);
        assertFalse(json.contains("\"bronPad\""), "een onoplosbare keten mag geen half pad op de wire zetten");
        assertFalse(json.contains("\"bronRol\""), "een geneste keten mag geen bronRol op de wire zetten");
        assertFalse(json.contains("\"kind\": \"MINIMUM\""), "de vouw mag niet geëmitteerd worden");
        assertTrue(json.contains("\"node\": \"Onopgelost\""), "de vouw moet een zichtbare markering worden");
    }

    // ---------------- (b) the intermediate to-ONE step ----------------

    /** One set step with a to-ONE step under it. The fold source names one relation from the RULE SUBJECT, so
     *  "van de boot" disappeared exactly as a nested set step does — and the by-pair binding was RIGHT about
     *  the role name, so nothing else saw it: measured `diagnostics: []`, verifier PASS, and 5.00 where the
     *  sentence means 3.00. */
    /** Document F, and the decision the acute flank left open: a to-ONE step is an ORDINARY step of the
     *  range (RUNTIME-SEMANTICS ch. 3.4b — multiplicity is a declaration fact and does not enter the
     *  reading), so the sentence is expressible and the path says so. The direct <em>jutterbezit</em>
     *  relation, which the by-pair search used to bind here, must be absent from the wire. */
    @Test
    void een_toone_tussenstap_wordt_een_stap_op_het_pad() {
        assertEquals(List.of(), vouwbronDiagnoses(FLANKMODEL + TUSSENSTAPREGEL));
        String json = ir(FLANKMODEL + TUSSENSTAPREGEL);
        assertTrue(json.contains("\"kind\": \"MINIMUM\""), "de vouw hoort nu gewoon geëmitteerd te worden");
        assertBronPad(json, "bemanning", "boot", "bootvangst", "vondst");
        assertFalse(json.contains("\"feittype\": \"jutterbezit\""),
                "de type-paar-afleiding mag niet meer binden waar de zin een keten schrijft");
    }

    /** Route (b) keeps its witness on the shape a path cannot rescue: ONE step whose anchor names an
     *  object type that is not the rule subject. There is no chain to resolve, so the anchor rule refuses
     *  it exactly as it did. */
    @Test
    void een_anker_dat_het_onderwerp_niet_is_blijft_geweigerd() {
        List<Diagnostic> ds = vouwbronDiagnoses(MODEL_B + VREEMD_ANKERREGEL);
        assertEquals(1, ds.size(), () -> "verwacht één vouwbron-diagnose, kreeg " + ds);
        assertTrue(ds.get(0).message().startsWith("tussenstap in een vouwbron"),
                () -> "verwacht de ankerregel, kreeg " + ds.get(0).message());
    }

    /** Same model, same written element name, NO intermediate step: the fold binds and emits. This is what
     *  keeps the anchor rule off the ordinary one-step form. */
    @Test
    void zonder_tussenstap_bindt_dezelfde_zin_gewoon() {
        assertEquals(List.of(), vouwbronDiagnoses(FLANKMODEL + DIRECTE_REGEL));
        String json = ir(FLANKMODEL + DIRECTE_REGEL);
        assertTrue(json.contains("\"kind\": \"MINIMUM\""), "de vouw moet gewoon geëmitteerd worden");
        assertTrue(json.contains("\"feittype\": \"jutterbezit\""), "de directe relatie hoort op de wire");
    }

    /** The role-anchor question the acute flank left to this card, DECIDED (2026-09-11) and decided by the
     *  order ch. 4.1a already fixes rather than by a new test. "van de aanmelder" has ZERO far hits from the
     *  subject and the subject type fills a role of that name, so it denotes the SUBJECT: the range starts
     *  there, it is one step, and the wire spelling is the unchanged {@code bronRol}. The outcome is
     *  byte-identical to before the decision, which is the point — the decision moves no document. */
    @Test
    void een_zelfdenoterend_rolanker_blijft_een_eenstaps_binding() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_B + ROLANKERREGEL));
        String json = ir(MODEL_B + ROLANKERREGEL);
        assertTrue(json.contains("\"rol\": \"aangemelde vondst\""),
                "een zelfdenoterend anker IS het onderwerp, dus de eenstaps-binding blijft staan");
        assertFalse(json.contains("\"bronPad\""), "één relatie blijft één relatie: geen pad");
    }

    /** The other half of the same decision: a role REACHABLE FROM the subject denotes ANOTHER instance, so
     *  it is a hop and not an anchor. With only one written step there is no path to put it on, and binding
     *  the fold to the subject's own relation would fold the wrong set in silence — so it is refused. */
    @Test
    void een_verre_rol_als_anker_wordt_geweigerd() {
        List<Diagnostic> ds = vouwbronDiagnoses(ROLANKERMODEL + VERRE_ROLANKERREGEL);
        assertEquals(1, ds.size(), () -> "verwacht één vouwbron-diagnose, kreeg " + ds);
        assertTrue(ds.get(0).message().startsWith("rolanker in een vouwbron"),
                () -> "verwacht de rolanker-melding, kreeg " + ds.get(0).message());
        assertTrue(ds.get(0).message().contains("strandmaat"), "de melding noemt de rol");
    }

    // ---------------- (c) the by-pair binding the written name contradicts ----------------

    /** ONE step, no nesting: the sentence writes "geborgen vondsten", the by-pair search bound the role
     *  "aangemelde vondst" of vondstaanmelding. Verifier-clean and silently wrong before this check. */
    @Test
    void eenstaps_vouw_met_een_botsende_geschreven_naam_wordt_benoemd() {
        List<Diagnostic> ds = vouwbronDiagnoses(MODEL_B + BOTSENDE_EENSTAPSREGEL);
        assertEquals(1, ds.size(), () -> "verwacht één vouwbron-diagnose, kreeg " + ds);
        assertEquals(NAAM_MELDING, ds.get(0).message());
    }

    @Test
    void de_botsende_eenstaps_vouwbron_wordt_niet_gevouwen() {
        String json = ir(MODEL_B + BOTSENDE_EENSTAPSREGEL);
        assertFalse(json.contains("\"rol\": \"aangemelde vondst\""),
                "de wire mag geen rolbinding dragen die de zin niet schrijft");
        assertTrue(json.contains("\"node\": \"Onopgelost\""), "de vouw moet een zichtbare markering worden");
    }

    /** Without the direct fact type there is nothing to bind silently, so the by-pair check stays out of it
     *  and the document keeps its existing downstream failure (WF-048). */
    @Test
    void een_eenstaps_vouw_zonder_enige_relatie_blijft_stroomafwaarts() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_A + BOTSENDE_EENSTAPSREGEL));
    }

    // ---------------- counter-proofs ----------------

    /** The one form this card must not touch: a correct one-step fold whose written name names the role the
     *  fact type declares. It emits exactly as before, bronRol and all. */
    @Test
    void een_correcte_eenstaps_vouw_blijft_ongewijzigd_emitteren() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_B + CORRECTE_EENSTAPSREGEL));
        String json = ir(MODEL_B + CORRECTE_EENSTAPSREGEL);
        assertTrue(json.contains("\"kind\": \"MINIMUM\""), "de vouw moet gewoon geëmitteerd worden");
        assertTrue(json.contains("\"rol\": \"aangemelde vondst\""), "de bronRol hoort op de wire");
        assertFalse(json.contains("\"node\": \"Onopgelost\""), "geen markering op een correcte vouw");
    }

    /** A written name that IS the element object type's own name claims no role, so the by-pair derivation
     *  remains the honest fallback and the fold keeps its binding. */
    @Test
    void een_geschreven_objecttypenaam_houdt_de_typepaar_afleiding() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_B + OBJECTTYPENAAMREGEL));
        assertTrue(ir(MODEL_B + OBJECTTYPENAAMREGEL).contains("\"rol\": \"aangemelde vondst\""),
                "de by-pair afleiding blijft staan waar de zin geen rol noemt");
    }

    /** The §13.4.17.47 telling binds through the WRITTEN element name already (elementRolBinding), a different
     *  derivation on a different sentence shape. The nested telvorm is explicitly another card. */
    @Test
    void de_telvorm_blijft_via_de_geschreven_elementnaam_binden() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_B + TELREGEL));
        String json = ir(MODEL_B + TELREGEL);
        assertTrue(json.contains("\"kind\": \"AANTAL\""), "de telling moet gewoon geëmitteerd worden");
        assertTrue(json.contains("\"rol\": \"aangemelde vondst\""), "de telling bindt via de geschreven naam");
    }

    /** The same nested chain with no fold around it is the WF-096 position, decided by another card: it must
     *  keep ITS diagnosis and must not pick up this one. */
    @Test
    void een_setlezing_buiten_een_vouw_houdt_haar_eigen_diagnose() {
        assertEquals(List.of(), vouwbronDiagnoses(MODEL_B + SETLEZING_ZONDER_VOUW));
        RegelSpraakModel m = Resolver.resolve(MODEL_B + SETLEZING_ZONDER_VOUW);
        assertEquals(1, m.diagnostics.size(), () -> "verwacht enkel de setlezing-diagnose, kreeg " + m.diagnostics);
        assertTrue(m.diagnostics.get(0).message().contains("WF-096"),
                () -> "verwacht de WF-096-melding, kreeg " + m.diagnostics.get(0));
    }

    // ---------------- the severity, and the wire ----------------

    /** The level is the one knob this check has, pinned so a promotion is a visible edit. WARNING keeps the
     *  document on disk with its own defect stated; ERROR would make {@code Resolver.irModus} write nothing
     *  at all. Either way no wrong answer survives — the marker is not a fold. */
    @Test
    void de_diagnose_is_een_waarschuwing_en_geen_fout() {
        List<Diagnostic> ds = vouwbronDiagnoses(MODEL_B + GEMENGDE_KETENREGEL);
        assertEquals(1, ds.size());
        assertEquals(Severity.WARNING, ds.get(0).severity());
        assertTrue(Resolver.resolve(MODEL_B + GEMENGDE_KETENREGEL).isOk(),
                "een WARNING mag de emissie niet blokkeren");
    }

    /** The marker carries the aggregation's OWN result type, never the unknown sentinel. That is what keeps
     *  the refused document WELL-FORMED — IR-SPEC ch. 2.4 makes Onopgelost a marker with a reason, not a type,
     *  and WF-044's fence is behavioural — so the failure lands as a loud evaluation fault in every executor
     *  instead of as an ill-formed document. Measured on all three routes. */
    @Test
    void de_markering_draagt_het_eigen_resultaattype() {
        for (String bron : List.of(MODEL_B + GEMENGDE_KETENREGEL, MODEL_B + BOTSENDE_EENSTAPSREGEL,
                MODEL_B + VREEMD_ANKERREGEL)) {
            String json = ir(bron);
            assertTrue(json.contains("\"node\": \"Onopgelost\""), "verwacht een markering");
            assertFalse(json.contains("\"node\": \"Onbekend\""),
                    "de markering mag het onbekend-sentinel niet dragen (WF-034 zou het document afkeuren)");
            assertTrue(json.contains("\"afkorting\": \"kg\""), "het eigen resultaattype hoort erop te staan");
        }
    }

    /** The emitter writes every diagnostic, so the document itself states what is wrong with it. */
    @Test
    void de_emissie_draagt_de_diagnose_op_de_wire() {
        String json = ir(MODEL_B + GEMENGDE_KETENREGEL);
        assertTrue(json.contains("\"severity\": \"WARNING\""), "de diagnose hoort op de wire");
        assertTrue(json.contains("geneste verzamelingsstap in een vouwbron"),
                "de wire moet de reden dragen, niet alleen een niveau");
    }

    /** The emitted {@code bronPad}, step by step and in order: the pairs are given subject-outwards, and
     *  the assertion is on the ORDER because a range read backwards walks a different chain. */
    private static void assertBronPad(String json, String... feittypeEnRol) {
        assertTrue(json.contains("\"bronPad\""), () -> "verwacht een bronPad op de wire:\n" + json);
        int vanaf = json.indexOf("\"bronPad\"");
        for (int i = 0; i < feittypeEnRol.length; i += 2) {
            String stap = "\"feittype\": \"" + feittypeEnRol[i] + "\"";
            String rolnaam = feittypeEnRol[i + 1];
            int hier = json.indexOf(stap, vanaf);
            assertTrue(hier >= 0, () -> "verwacht stap " + stap + " in het pad:\n" + json);
            int rol = json.indexOf("\"rol\": \"" + rolnaam + "\"", hier);
            assertTrue(rol > hier, () -> "stap " + stap + " moet rol '" + rolnaam + "' binden");
            vanaf = rol;
        }
    }
}
