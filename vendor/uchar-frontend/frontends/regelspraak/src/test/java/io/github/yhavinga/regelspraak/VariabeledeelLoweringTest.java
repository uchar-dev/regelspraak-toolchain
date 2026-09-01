package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §13.4.2.10 the variabelendeel ("Daarbij geldt:") lowered to the IR-SPEC ch. 4.10 {@code Let} cluster.
 *
 * <p>Before this, the resolver INLINED every variable: it substituted the resolved definition subtree at each
 * use site, so sharing was lost and the block never reached the wire. Now a variable the RESULT part reads
 * becomes one {@code Let} carrying that definition once, with a {@code BinderRef} at every read. Two lowering
 * facts follow from the wire's shape rather than from the node, and both are pinned here: a variable read only
 * in the VOORWAARDE keeps the substitution route (a rule's condition and its value are separate wire fields, so
 * no Let can span them), and a variable no read reaches gets no Let at all (under call-by-need it would be a
 * guaranteed no-op).
 *
 * <p>Assertions run over the EMITTED JSON with whitespace collapsed, so they pin the wire shape itself.
 */
class VariabeledeelLoweringTest {

    private static String emitCompact(String src) {
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        return IrEmitter.emit(m).replaceAll("\\s+", "");
    }

    /** How often {@code naald} occurs in {@code hooi}. */
    private static int tel(String hooi, String naald) {
        int n = 0;
        for (int i = hooi.indexOf(naald); i >= 0; i = hooi.indexOf(naald, i + 1)) n++;
        return n;
    }

    /** Every diagnostic the resolver raises, as "SEVERITY: message" — the resolver-check witnesses below assert
     *  on these rather than on the wire, because what the lowering broke there was the REPORTING, not the IR. */
    private static java.util.List<String> meldingen(String src) {
        return Resolver.resolve(src).diagnostics.stream()
                .map(d -> d.severity() + ": " + d.message()).toList();
    }

    /** Whether some diagnostic contains {@code fragment}. */
    private static boolean meldt(java.util.List<String> ds, String fragment) {
        return ds.stream().anyMatch(m -> m.contains(fragment));
    }

    @Test void een_variabele_die_het_resultaat_tweemaal_leest_wordt_een_let_met_twee_binderrefs() {
        // The point of the lowering: the definition is carried ONCE and read twice, instead of the
        // definition subtree being duplicated at both use sites (the v0 inlining).
        String src = """
            Objecttype de Berging
                het wraktal Numeriek (geheel getal);
                het spoedtal Numeriek (geheel getal);
                het loon Numeriek (geheel getal);
            Regel loon
                geldig altijd
                    Het loon van een Berging moet gesteld worden op het grondloon plus het grondloon maal het spoedtal van de Berging.
                    Daarbij geldt:
                        het grondloon is het wraktal van de Berging maal 40.
            """;
        String c = emitCompact(src);
        assertEquals(1, tel(c, "\"node\":\"Let\""), () -> "precies één Let verwacht:\n" + c);
        assertTrue(c.contains("\"node\":\"Let\",\"type\":"), () -> "Let-veldvolgorde (node,type,binder,…):\n" + c);
        assertTrue(c.contains("\"binder\":\"grondloon\""), () -> "binder-naam ontbreekt:\n" + c);
        assertEquals(2, tel(c, "\"node\":\"BinderRef\",\"name\":\"grondloon\""),
                () -> "twee lezingen verwacht, geen duplicatie van de definitie:\n" + c);
        // the definition itself occurs once — inside the Let's value, nowhere else.
        assertEquals(1, tel(c, "\"attribuut\":\"wraktal\""), () -> "de definitie is gedupliceerd:\n" + c);
    }

    @Test void een_variabele_die_alleen_de_voorwaarde_leest_krijgt_geen_let_en_blijft_gesubstitueerd() {
        // A rule's `voorwaarde` is a Conditie and its `waarde` an Expr: no Let node spans the two wire
        // fields, so the condition read keeps the v0 substitution. And no DEAD Let is emitted around the
        // value for it — under ch. 10.6 an unread binder evaluates nothing, so it would be pure noise.
        String src = """
            Objecttype de Jutter
                de leeftijd Numeriek (geheel getal);
                is jong kenmerk (bijvoeglijk);
            Regel jong
                geldig altijd
                    Een Jutter is jong
                    indien de grens groter is dan de leeftijd van de Jutter.
                    Daarbij geldt:
                        de grens is 30.
            """;
        String c = emitCompact(src);
        assertFalse(c.contains("\"node\":\"Let\""), () -> "geen Let verwacht om een voorwaarde-variabele:\n" + c);
        assertFalse(c.contains("\"node\":\"BinderRef\""), () -> "geen BinderRef buiten een Let:\n" + c);
        // the definition IS there — substituted into the condition's left operand.
        assertTrue(c.contains("\"op\":\"GROTER\",\"links\":{\"node\":\"Literal\",\"waarde\":\"30\""),
                () -> "de voorwaarde-substitutie ontbreekt:\n" + c);
    }

    @Test void een_variabele_die_niemand_leest_krijgt_geen_dode_let() {
        String src = """
            Objecttype de Jutter
                de rang Numeriek (geheel getal);
            Regel rang
                geldig altijd
                    De rang van een Jutter moet gesteld worden op 7.
                    Daarbij geldt:
                        de ongebruikte is 3.
            """;
        String c = emitCompact(src);
        assertFalse(c.contains("\"node\":\"Let\""), () -> "een nooit gelezen variabele hoort niet op de wire:\n" + c);
    }

    @Test void twee_variabelen_nesten_in_declaratievolgorde_en_de_latere_leest_de_eerdere_als_binder() {
        // §11.1 rule 1: declaration order. The FIRST declared variable is the OUTERMOST Let, so a later
        // definition's BinderRef to an earlier one is in scope (WF-064) and the earlier binder is never
        // referenced inside its own value (WF-076).
        String src = """
            Objecttype de Jutter
                de grondslag Numeriek (geheel getal);
                de heffing Numeriek (geheel getal);
            Regel heffing
                geldig altijd
                    De heffing van een Jutter moet gesteld worden op het totaal plus het totaal.
                    Daarbij geldt:
                        de basis is de grondslag van de Jutter maal 2.
                        het totaal is de basis plus 5.
            """;
        String c = emitCompact(src);
        assertEquals(2, tel(c, "\"node\":\"Let\""), () -> "twee Lets verwacht:\n" + c);
        // outermost = first declared: "basis" opens, and "totaal" sits inside its body.
        int basis = c.indexOf("\"binder\":\"basis\"");
        int totaal = c.indexOf("\"binder\":\"totaal\"");
        assertTrue(basis >= 0 && totaal > basis, () -> "declaratievolgorde: basis moet de buitenste Let zijn:\n" + c);
        assertEquals(1, tel(c, "\"node\":\"BinderRef\",\"name\":\"basis\""),
                () -> "de latere definitie moet de eerdere als binder lezen:\n" + c);
        assertEquals(2, tel(c, "\"node\":\"BinderRef\",\"name\":\"totaal\""), () -> "twee lezingen verwacht:\n" + c);
    }

    @Test void een_variabele_die_beide_delen_leest_wordt_in_de_voorwaarde_gesubstitueerd_en_in_de_waarde_gebonden() {
        // The mixed case: one variable, both routes at once, and the two never interfere — the condition
        // keeps the substituted subtree, the value keeps the Let.
        String src = """
            Objecttype de Jutter
                de grondslag Numeriek (geheel getal);
                de heffing Numeriek (geheel getal);
            Regel heffing
                geldig altijd
                    De heffing van een Jutter moet gesteld worden op de basis plus 1
                    indien de basis groter is dan 10.
                    Daarbij geldt:
                        de basis is de grondslag van de Jutter maal 2.
            """;
        String c = emitCompact(src);
        assertEquals(1, tel(c, "\"node\":\"Let\""), () -> "één Let om de waarde:\n" + c);
        assertEquals(1, tel(c, "\"node\":\"BinderRef\",\"name\":\"basis\""),
                () -> "de waarde-lezing is een BinderRef:\n" + c);
        // the condition carries the SUBSTITUTED definition (a MAAL over the attribute), not a BinderRef.
        assertTrue(c.contains("\"bron\":\"debasisgroterisdan10\""), () -> "de voorwaarde ontbreekt:\n" + c);
        assertEquals(2, tel(c, "\"attribuut\":\"grondslag\""),
                () -> "één definitie in de Let-value, één gesubstitueerd in de voorwaarde:\n" + c);
    }

    @Test void een_variabele_schaduwt_een_gelijknamige_parameter_ook_op_de_binder_route() {
        // §11: a variable "geldt alleen binnen de regel" and shadows a global parameter of the same name.
        // The result read must therefore bind the VARIABLE (a BinderRef), never the parameter.
        String src = """
            Parameter het tarief : Numeriek (geheel getal);
            Objecttype de Jutter
                de heffing Numeriek (geheel getal);
            Regel heffing
                geldig altijd
                    De heffing van een Jutter moet gesteld worden op het tarief plus het tarief.
                    Daarbij geldt:
                        het tarief is 3.
            """;
        String c = emitCompact(src);
        assertEquals(2, tel(c, "\"node\":\"BinderRef\",\"name\":\"tarief\""),
                () -> "de variabele moet de parameter schaduwen:\n" + c);
        assertFalse(c.contains("\"node\":\"ParameterRef\",\"parameter\":\"tarief\""),
                () -> "geen parameterlezing binnen de regel:\n" + c);
    }

    @Test void de_let_draagt_het_bodytype_wf076() {
        // WF-076: the Let's type IS its body's type. Here the body is a Datum-valued read, so the Let must
        // carry DatumInDagen — not the (Numeriek) type of its own value.
        String src = """
            Objecttype de Vondst
                de vonddatum Datum in dagen;
                het jaartal Numeriek (geheel getal);
            Regel jaartal
                geldig altijd
                    Het jaartal van een Vondst moet gesteld worden op (het jaar uit (de datum)).
                    Daarbij geldt:
                        de datum is de vonddatum van de Vondst.
            """;
        String c = emitCompact(src);
        // the VALUE is a Datum read, the BODY a Numeriek year extraction: the Let must carry the body's type.
        assertTrue(c.contains("\"binder\":\"datum\",\"value\":{\"node\":\"AttribuutRef\",\"attribuut\":\"vonddatum\""),
                () -> "de Let-value is de datum-lezing:\n" + c);
        assertTrue(c.contains("\"node\":\"Let\",\"type\":{\"node\":\"Numeriek\",\"geheel\":true"),
                () -> "WF-076: het Let-type is het body-type (Numeriek), niet dat van zijn value (Datum):\n" + c);
    }

    @Test void een_variabele_in_een_structuurgevoelige_positie_blijft_gesubstitueerd() {
        // ch. 4.10 boundary (c). The RHS of a §5.6 subselectie comparison is resolved on the SHAPE of the read
        // subtree — only a Literal or a ParameterRef may stand there, because an attribute would mis-bind
        // against the aggregated element. A BinderRef is an opaque leaf, so lowering the read would fail that
        // shape test and degrade the whole aggregation to a Conditie.Onopgelost marker: a WRONG ANSWER, not a
        // lost optimisation. The variable is therefore substituted AT THAT USE SITE, exactly as v0 did.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het drempelgewicht Numeriek (geheel getal);
            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
            Feittype aanmelding
                de aanmeldende jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmeldende jutter telt meerdere vondsten
            Regel drempelgewicht
                geldig altijd
                    Het drempelgewicht van een Jutter moet berekend worden als de som van het gewicht van alle vondsten van de Jutter die groter zijn dan de drempel of 0 als die er niet zijn.
                    Daarbij geldt:
                        de drempel is 5.
            """;
        String c = emitCompact(src);
        // the filter SURVIVES as a comparison against the substituted literal …
        assertTrue(c.contains("\"filter\":{\"node\":\"Vergelijking\",\"op\":\"GROTER\""),
                () -> "de subselectie mag niet degraderen tot een marker:\n" + c);
        assertTrue(c.contains("\"rechts\":{\"node\":\"Literal\",\"waarde\":\"5\""),
                () -> "het rechterlid moet de gesubstitueerde definitie zijn:\n" + c);
        // … and precisely because it did, there is no read left to bind: no Let, no BinderRef.
        assertFalse(c.contains("\"node\":\"Let\""), () -> "geen Let om een enkel structuurgevoelig gebruik:\n" + c);
        assertFalse(c.contains("\"node\":\"BinderRef\""), () -> "geen BinderRef in een structuurgevoelige positie:\n" + c);
    }

    @Test void een_binder_die_alleen_via_een_geneste_let_body_gelezen_wordt_krijgt_zijn_let() {
        // The Let arm of Expr.bevat is load-bearing on BOTH sides, and this pins the BODY half (the value half
        // is pinned by the declaratievolgorde test above, where "basis" is reachable only through the inner
        // Let's VALUE). Here "basis" and "totaal" are read ONLY in the outermost body, so by the time the chain
        // is built inside-out they are reachable only THROUGH an already-built Let's body. A scan that stopped
        // at a Let (or descended only into its value) reports "not read", drops those Lets, and emits a wire
        // whose BinderRefs resolve to nothing — a WF-064 violation the verifier then rejects.
        String src = """
            Objecttype de Jutter
                de grondslag Numeriek (geheel getal);
                de heffing Numeriek (geheel getal);
            Regel heffing
                geldig altijd
                    De heffing van een Jutter moet gesteld worden op de basis plus het totaal plus de opslag.
                    Daarbij geldt:
                        de basis is de grondslag van de Jutter maal 2.
                        het totaal is de basis plus 5.
                        de opslag is 7.
            """;
        String c = emitCompact(src);
        assertEquals(3, tel(c, "\"node\":\"Let\""), () -> "drie gelezen variabelen, drie Lets:\n" + c);
        int basis = c.indexOf("\"binder\":\"basis\"");
        int totaal = c.indexOf("\"binder\":\"totaal\"");
        int opslag = c.indexOf("\"binder\":\"opslag\"");
        assertTrue(basis >= 0 && totaal > basis && opslag > totaal,
                () -> "declaratievolgorde: basis ⊃ totaal ⊃ opslag:\n" + c);
        // every BinderRef the wire carries names one of the three binders — nothing is left unbound (WF-064).
        assertEquals(4, tel(c, "\"node\":\"BinderRef\""), () -> "vier lezingen verwacht (3 in de body, 1 in een value):\n" + c);
    }

    @Test void lidwoordvarianten_van_een_verwijzing_binden_op_een_binder() {
        // §11 "Mogelijkheid van lidwoord bij naam variabele": "het grondloon" and the bare "grondloon"
        // are ONE variable, so both reads carry the same binder name (the declared name minus the article).
        String src = """
            Objecttype de Berging
                het wraktal Numeriek (geheel getal);
                het loon Numeriek (geheel getal);
            Regel loon
                geldig altijd
                    Het loon van een Berging moet gesteld worden op grondloon plus het grondloon.
                    Daarbij geldt:
                        het grondloon is het wraktal van de Berging maal 40.
            """;
        String c = emitCompact(src);
        assertEquals(1, tel(c, "\"node\":\"Let\""), () -> "één variabele, één Let:\n" + c);
        assertEquals(2, tel(c, "\"node\":\"BinderRef\",\"name\":\"grondloon\""),
                () -> "beide schrijfwijzen binden op dezelfde binder:\n" + c);
    }

    // ---- the resolver's own checks under a Let root ----------------------------------------------------
    // Three RuleChecker inspections read a rule value's SHAPE. Before the Let arm / the chain peel they saw a
    // Let root, matched nothing, and reported NOTHING — silently, and in the teken case behind a wire that is
    // verifier-clean, so no gate would have caught it. Each test below is paired with its un-lowered twin: the
    // lowering changed the wire, so it must not change what is statically decidable about the same rule.

    @Test void een_kringverwijzing_door_een_variabelendeel_heen_wordt_nog_steeds_gemeld() {
        // keurKringverwijzingen builds its read-graph with verzamelEigenReads, which walks the value expression.
        // Under the lowering the whole value is a Let CHAIN, so the edge "Jutter.a reads Jutter.b" lives inside a
        // Let VALUE — invisible to a walk that stops at the Let, and the run-away cycle goes unwarned.
        String kring = """
            Objecttype de Jutter
                de a Numeriek (geheel getal);
                de b Numeriek (geheel getal);
            Regel a
                geldig altijd
                    De a van een Jutter moet gesteld worden op de hulp plus 1.
                    Daarbij geldt:
                        de hulp is de b van de Jutter.
            Regel b
                geldig altijd
                    De b van een Jutter moet gesteld worden op de a van de Jutter plus 1.
            """;
        assertTrue(meldt(meldingen(kring), "kringverwijzing tussen berekeningen: Jutter.a -> Jutter.b -> Jutter.a"),
                () -> "de kring loopt door een variabelendeel en moet gemeld worden: " + meldingen(kring));
    }

    @Test void de_tekenbeperking_ziet_door_de_letketen_heen() {
        // §3.3.1 is resolver-static over a LITERAL value; `Daarbij geldt: de keuze is -49` denotes exactly that
        // literal (ch. 10.6: the chain's body is a single read of the binder), so it must fail the same way the
        // bare -49 does. This is the case with NO other guard: the emitted wire is verifier-clean.
        String viaVariabele = """
            Objecttype de Jutter
                de keuzewaarde Numeriek (niet-negatief geheel getal);
            Regel keuze
                geldig altijd
                    De keuzewaarde van een Jutter moet gesteld worden op de keuze.
                    Daarbij geldt:
                        de keuze is -49.
            """;
        String kaal = """
            Objecttype de Jutter
                de keuzewaarde Numeriek (niet-negatief geheel getal);
            Regel keuze
                geldig altijd
                    De keuzewaarde van een Jutter moet gesteld worden op -49.
            """;
        assertEquals(meldingen(kaal), meldingen(viaVariabele),
                () -> "het variabelendeel mag de teken-beperking niet wegnemen: " + meldingen(viaVariabele));
        assertTrue(meldt(meldingen(viaVariabele), "ERROR: waarde -49 voldoet niet aan de teken-beperking 'niet-negatief'"),
                () -> "§3.3.1 ongemeld: " + meldingen(viaVariabele));
    }

    @Test void de_enumdomeincontrole_ziet_door_de_letketen_heen() {
        // §13.3.4.2 likewise: the membership check runs on the value's literal, and the doel supplies the domain.
        String viaVariabele = """
            Domein Kleur is van het type Enumeratie
                'rood'
                'groen'
            Objecttype de Jutter
                de kleur Kleur;
            Regel kleur
                geldig altijd
                    De kleur van een Jutter moet gesteld worden op de tint.
                    Daarbij geldt:
                        de tint is 'paars'.
            """;
        String kaal = """
            Domein Kleur is van het type Enumeratie
                'rood'
                'groen'
            Objecttype de Jutter
                de kleur Kleur;
            Regel kleur
                geldig altijd
                    De kleur van een Jutter moet gesteld worden op 'paars'.
            """;
        assertEquals(meldingen(kaal), meldingen(viaVariabele),
                () -> "het variabelendeel mag de domeincontrole niet wegnemen: " + meldingen(viaVariabele));
        assertTrue(meldt(meldingen(viaVariabele), "ERROR: 'paars' is geen waarde van Enumeratie Kleur"),
                () -> "§13.3.4.2 ongemeld: " + meldingen(viaVariabele));
    }

    // ---- boundary (c): the whole construct, and the prefix the two passes share -------------------------

    @Test void een_voorwaartse_verwijzing_blijft_in_beide_passes_onopgelost() {
        // The pass-2 guard: pass 2 substitutes from a growing PREFIX of the inlined definitions, not from the
        // (by then FULLY populated) pass-1 map. Definition 1's subselectie names `de tweede`, which is declared
        // AFTER it — pass 1 leaves it unbound and the filter degrades to a marker, so pass 2 must too. Reading
        // the full map instead would let the forward reference bind in the Let VALUE while pass 1's WARNING
        // still stands: two passes disagreeing about the same source text.
        String vooruit = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het drempelgewicht Numeriek (geheel getal);
            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
            Feittype aanmelding
                de aanmeldende jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmeldende jutter telt meerdere vondsten
            Regel drempelgewicht
                geldig altijd
                    Het drempelgewicht van een Jutter moet berekend worden als de eerste plus 0.
                    Daarbij geldt:
                        de eerste is de som van het gewicht van alle vondsten van de Jutter die groter zijn dan de tweede of 0 als die er niet zijn.
                        de tweede is 5.
            """;
        String c = emitCompact(vooruit);
        assertEquals(1, tel(c, "\"node\":\"Let\""), () -> "de gelezen variabele krijgt haar Let:\n" + c);
        assertTrue(c.contains("\"filter\":{\"node\":\"Onopgelost\",\"bron\":\"groterzijndandetweede\"}"),
                () -> "de voorwaartse verwijzing moet ook in pass 2 onopgelost blijven:\n" + c);
        assertTrue(meldt(meldingen(vooruit), "wordt nog niet omgezet"),
                () -> "pass 1 meldt de degradatie: " + meldingen(vooruit));
        // The mirror: declared BEFORE the use, the same reference IS in the prefix and the filter resolves —
        // so the assertion above pins the prefix, not a blanket refusal to substitute in pass 2.
        String achteruit = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                het drempelgewicht Numeriek (geheel getal);
            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
            Feittype aanmelding
                de aanmeldende jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmeldende jutter telt meerdere vondsten
            Regel drempelgewicht
                geldig altijd
                    Het drempelgewicht van een Jutter moet berekend worden als de tweede plus 0.
                    Daarbij geldt:
                        de eerste is 5.
                        de tweede is de som van het gewicht van alle vondsten van de Jutter die groter zijn dan de eerste of 0 als die er niet zijn.
            """;
        String d = emitCompact(achteruit);
        assertTrue(d.contains("\"filter\":{\"node\":\"Vergelijking\",\"op\":\"GROTER\""),
                () -> "een achterwaartse verwijzing zit WEL in het prefix:\n" + d);
        assertTrue(d.contains("\"rechts\":{\"node\":\"Literal\",\"waarde\":\"5\""),
                () -> "het rechterlid is de gesubstitueerde eerdere definitie:\n" + d);
    }

    @Test void de_grens_is_het_hele_aggregatieconstruct_ook_zijn_zuivere_waardeposities() {
        // ch. 4.10 boundary (c) is a CONSTRUCT boundary, not a list of positions: the resolver builds an
        // aggregation (and a §7 temporal operator) wholly in the inlined scope, so every variable read anywhere
        // under it is substituted — including a §5.8.2 leegwaarde fallback, which is a pure VALUE position that
        // decides nothing about shape. That is deliberate (one scope, no half-inlined subtree) and it is why
        // the tracked delta's leeg-barrier does not reach inside an aggregation subtree.
        //
        // Measured through ref (this exact wire, one Jutter with no vondsten and an absent bonusgrond):
        // `totaal` = 1 — the substituted read coalesces leeg to 0 in the `plus`, v0-style. The Let-wrapped
        // variant of the SAME IR gives leeg. The barrier claim would have promised the latter.
        String src = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de bonusgrond Numeriek (geheel getal);
                het totaal Numeriek (geheel getal);
            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
            Feittype aanmelding
                de aanmeldende jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmeldende jutter telt meerdere vondsten
            Regel totaal
                geldig altijd
                    Het totaal van een Jutter moet berekend worden als de som van het gewicht van alle vondsten van de Jutter of de bonus plus 1 als die er niet zijn.
                    Daarbij geldt:
                        de bonus is de bonusgrond van de Jutter.
            """;
        String c = emitCompact(src);
        assertEquals(0, tel(c, "\"node\":\"Let\""), () -> "geen Let: het enige gebruik zit onder de aggregatie:\n" + c);
        assertEquals(0, tel(c, "\"node\":\"BinderRef\""), () -> "geen BinderRef onder een aggregatie:\n" + c);
        assertTrue(c.contains("\"leegwaarde\":{\"node\":\"Binair\",\"op\":\"PLUS\",\"links\":{\"node\":\"AttribuutRef\",\"attribuut\":\"bonusgrond\""),
                () -> "de leegwaarde draagt de gesubstitueerde definitie:\n" + c);
    }
}
