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
 * §8.2 / IR-SPEC ch. 4.5 (WF-108): the official RegelSpraak sentence
 *
 * <pre>… indien de &lt;attribuut&gt; van ALLE &lt;leden&gt; van de &lt;Onderwerp&gt; gedurende het gehele jaar
 * groter is dan &lt;grens&gt;.</pre>
 *
 * <p>It parses and resolves, but until this card it built a {@code Vergelijking} whose left operand was
 * an {@code AttribuutRef} with {@code set: true} — a collection in a slot that takes one value — so
 * WF-096 raised an ERROR and the document never reached disk, let alone a backend. A sanctioned
 * sentence this frontend could read and never emit.
 *
 * <p>The IR has since grown {@link io.github.yhavinga.regelspraak.ir.Conditie.Kwantorvouw}, which is
 * that sentence's home: first-order quantification over the members of a linked instance set, with the
 * range spelled exactly as the instance fold spells it. The resolver now LOWERS to it, and the set
 * reading is not excused but REMOVED — the quantified operand comes out as an element-own
 * {@code set: false} read, so WF-096 finds nothing left to name.
 *
 * <p>The scope is exactly the §8.2-QUALIFIED condition with one set operand. The other measured WF-096
 * producer routes are unchanged, and {@link SetlezingZonderVouwTest} — untouched — is the proof; the
 * two routes most likely to be widened by accident (an unqualified voorwaarde, a counting quantor) are
 * pinned here as well, beside the shapes that decline to lower on purpose — three when this file was
 * written, four since the die/dat subselection joined them (see {@link KwantorvouwFilterTest}).
 */
class KwantorvouwTest {

    /** One Jutter to many Vondsten, the Vondst's gewicht on a §13.3.6 month timeline — §8.2's
     *  whole-period qualifier is only defined over a tijdsafhankelijk onderwerp, so the quantified
     *  attribute must carry one for the sentence to be well-formed at all. */
    static final String MODEL = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Objecttype de Jutter (mv: Jutters) (bezield)
            het maatgevend gewicht Numeriek (getal met 2 decimalen) met eenheid kg;
            het drempelgewicht Numeriek (getal met 2 decimalen) met eenheid kg;

        Objecttype de Vondst (mv: Vondsten)
            het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg voor elke maand;

        Feittype vondstaanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten
        """;

    /** The §8.2 sentence itself, with {@code %s} for the written quantifier. */
    static final String ZIN = """
        Regel maatgevend gewicht
            geldig altijd
                Het maatgevend gewicht van een Jutter moet gesteld worden op 10 kg
                indien het gewicht van %s vondsten van de Jutter gedurende het gehele jaar groter is dan 5 kg.
        """;

    private static RegelSpraakModel resolveer(String regels) {
        return Resolver.resolve(MODEL + regels);
    }

    private static String emitCompact(RegelSpraakModel m) {
        return IrEmitter.emit(m).replaceAll("\\s+", "");
    }

    /** The WF-096 refusal itself, matched on its own sentence rather than on the rule id: the
     *  counting-quantor WARNING cites WF-096 too — saying which fence it leaves standing is the point of
     *  it — so an id filter would count two different diagnostics as one kind. */
    private static List<Diagnostic> wf096(RegelSpraakModel m) {
        return m.diagnostics.stream()
                .filter(d -> d.message().startsWith("setlezing buiten een vouwbron")).toList();
    }

    /** The emitted voorwaarde of the single rule, compacted — the slice every assertion below reads. */
    private static String voorwaarde(RegelSpraakModel m) {
        String c = emitCompact(m);
        int van = c.indexOf("\"voorwaarde\":");
        assertTrue(van >= 0, () -> "geen voorwaarde in de emissie:\n" + c);
        return c.substring(van);
    }

    @Test void de_alle_zin_lost_op_zonder_enige_diagnose() {
        // The whole point of the card: a sanctioned sentence that produced an ERROR now produces a
        // document. Zero diagnostics, not "no WF-096" — a lowering that traded one complaint for
        // another would have reclaimed nothing.
        RegelSpraakModel m = resolveer(ZIN.formatted("alle"));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }

    @Test void de_alle_zin_wordt_een_kwantorvouw_over_het_elementtype() {
        RegelSpraakModel m = resolveer(ZIN.formatted("alle"));
        String v = voorwaarde(m);
        assertTrue(v.contains("\"node\":\"Kwantorvouw\""),
                () -> "de §8.2-zin hoort een Kwantorvouw te worden:\n" + v);
        assertTrue(v.contains("\"kwantor\":\"ALLE\""), () -> "verkeerde kwantor:\n" + v);
        // the quantified range is the MEMBER type, not the rule subject
        assertTrue(v.contains("\"elementObjecttype\":\"Vondst\""),
                () -> "de vouw hoort over Vondst te kwantificeren:\n" + v);
        // this sentence writes NO die/dat subselectie, so the range field stays empty. (Since the
        // diedat-filter card a written subselectie DOES reach the wire; KwantorvouwFilterTest pins that
        // side, and this line is the regression pin for the unfiltered sentence.)
        assertTrue(v.contains("\"filter\":null"), () -> "zonder geschreven deelselectie geen filter:\n" + v);
    }

    @Test void de_kwantorvouw_draagt_de_verbindende_rol_als_bereik() {
        // WF-048's concern, on this node: a quantifier bound to no relation quantifies over a set the
        // sentence never named. The range is the connecting role, spelled exactly as the instance
        // fold's bronRol — one search in the model serves both, so the two can never disagree.
        String v = voorwaarde(resolveer(ZIN.formatted("alle")));
        assertTrue(v.contains("\"bronRol\":{\"node\":\"RolBinding\",\"feittype\":\"vondstaanmelding\",\"rol\":\"vondst\"}"),
                () -> "de vouw hoort het verbindende feittype+rol als bereik te dragen:\n" + v);
        // one-step range: bronRol XOR bronPad (WF-108 (c)), so no path beside the binding
        assertFalse(v.contains("\"bronPad\""),
                () -> "een bereik van één stap hoort geen bronPad te schrijven:\n" + v);
    }

    @Test void de_toets_is_een_eigen_lezing_van_het_element_met_de_periode() {
        // The matrix is evaluated PER CONTRIBUTOR with the element as subject, so the quantified operand
        // comes back as an OWN read of the element type — set false, no navigation. The §8.2 qualifier
        // stays on it: per member, "gedurende het gehele jaar" is what that member must satisfy.
        String v = voorwaarde(resolveer(ZIN.formatted("alle")));
        assertTrue(v.contains("\"toets\":{\"node\":\"Vergelijking\",\"op\":\"GROTER\","
                        + "\"links\":{\"node\":\"AttribuutRef\",\"attribuut\":\"gewicht\",\"objecttype\":\"Vondst\""),
                () -> "de toets hoort het attribuut op het ELEMENTtype te lezen:\n" + v);
        assertTrue(v.contains("\"set\":false"),
                () -> "de gekwantificeerde operand hoort als enkelvoudige lezing terug te komen:\n" + v);
        assertFalse(v.contains("\"set\":true"),
                () -> "na de verlaging hoort er geen setlezing meer in de boom te staan:\n" + v);
        assertTrue(v.contains("\"periode\":{\"node\":\"GeheelPeriode\",\"jaar\":true,\"niet\":false}"),
                () -> "de §8.2-kwalificatie hoort op de toets te blijven staan:\n" + v);
    }

    @Test void de_toets_draagt_geen_navigatiebindingen() {
        // The element-subject emission, and the reason it is not cosmetic: emitted from the OUTER
        // subject, an element-own read is an off-subject type and would pick up a by-pair
        // navigatieBindingen — a navigation from the Jutter, inside a matrix whose subject is the
        // Vondst. Exactly the trap Functie.filter already avoids the same way.
        String v = voorwaarde(resolveer(ZIN.formatted("alle")));
        assertFalse(v.contains("\"navigatieBindingen\""),
                () -> "de toets wordt met het ELEMENT als subject geëmitteerd en hoort dus geen "
                        + "navigatiebindingen te dragen:\n" + v);
        assertTrue(v.contains("\"navigatieRol\":null"), () -> "de eigen lezing navigeert niet:\n" + v);
    }

    @Test void de_geen_van_de_schrijfwijze_wordt_de_geen_kwantor() {
        // The reason the node records the WRITTEN quantor at all: `set` is true for "alle" and for
        // "geen van de" alike, so a consumer reading the flag as a forall would answer ALLE here.
        RegelSpraakModel m = resolveer(ZIN.formatted("geen van de"));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        String v = voorwaarde(m);
        assertTrue(v.contains("\"kwantor\":\"GEEN\""),
                () -> "'geen van de' hoort de GEEN-kwantor te schrijven, niet ALLE:\n" + v);
    }

    /**
     * <b>Deze rij is op 2026-09-16 omgedraaid</b> (kaart tellende-kwantoren). Tot dan waren de drie
     * tellende kwantoren het benoemde uitbreidingspunt van WF-108 (a) en pinde zij precies de weigering:
     * WF-096 bleef staan en een WARNING noemde de geweigerde kwantor. Zij zijn nu gebouwd, dus de
     * aanname onder die rij is weg. De bewering die zij droeg — "ten minste 2 van de" mag NOOIT als
     * forall op de wire belanden — staat er nog, en wordt nu sterker gemeten: niet dat er geen vouw komt,
     * maar dat er een vouw komt die de GESCHREVEN kwantor en het GESCHREVEN telwoord draagt.
     */
    @Test void de_tellende_kwantoren_worden_de_telvouw_met_hun_eigen_telwoord() {
        record Rij(String geschreven, String kwantor, int drempel) {}
        for (Rij r : new Rij[]{new Rij("ten minste 2 van de", "TEN_MINSTE", 2),
                               new Rij("ten hoogste 1 van de", "TEN_HOOGSTE", 1),
                               new Rij("precies 3 van de", "PRECIES", 3)}) {
            RegelSpraakModel m = resolveer(ZIN.formatted(r.geschreven()));
            assertTrue(m.diagnostics.isEmpty(),
                    () -> r.geschreven() + " — onverwachte diagnostiek: " + m.diagnostics);
            String v = voorwaarde(m);
            assertTrue(v.startsWith("\"voorwaarde\":{\"node\":\"Kwantorvouw\",\"kwantor\":\""
                            + r.kwantor() + "\",\"drempel\":" + r.drempel() + ","),
                    () -> r.geschreven() + " hoort de kwantor EN het telwoord te dragen:\n" + v);
        }
    }

    /**
     * De drempel staat er PRECIES onder een tellende kwantor (WF-108 (e)): onder ALLE en GEEN schrijft
     * de emitter hem NIET, anders staat er een getal op de wire dat het kwantorveld tegenspreekt.
     */
    @Test void alle_en_geen_dragen_geen_drempel_op_de_wire() {
        for (String kw : new String[]{"alle", "geen van de"})
            assertFalse(voorwaarde(resolveer(ZIN.formatted(kw))).contains("\"drempel\""),
                    kw + " leest geen drempel, dus de wire hoort er geen te dragen");
    }

    /**
     * Een telwoord onder 1 wordt NIET gelowerd: WF-041 weigert zo'n drempel op de wire, dus emitteren zou
     * de weigering alleen verplaatsen. De WF-096-fout blijft staan en een WARNING noemt het telwoord.
     */
    @Test void een_telwoord_onder_een_wordt_niet_verlaagd_en_houdt_de_wf096_fout() {
        RegelSpraakModel m = resolveer(ZIN.formatted("ten minste 0 van de"));
        assertEquals(1, wf096(m).size(), () -> "de setlezing hoort te blijven staan: " + m.diagnostics);
        assertEquals(Severity.ERROR, wf096(m).get(0).severity());
        List<Diagnostic> telwoord = m.diagnostics.stream()
                .filter(d -> d.message().contains("telwoord van de kwantor")).toList();
        assertEquals(1, telwoord.size(), () -> "de geweigerde drempel hoort benoemd te worden: " + m.diagnostics);
        assertEquals(Severity.WARNING, telwoord.get(0).severity());
        assertFalse(emitCompact(m).contains("Kwantorvouw"),
                "een drempel onder 1 hoort niet op de wire te belanden");
    }

    @Test void een_ongekwalificeerde_setlezing_in_een_voorwaarde_houdt_de_wf096_fout() {
        // The route this card deliberately does NOT touch: the same operand in the same slot, without
        // the §8.2 qualifier. There is no quantifier there to give the collection a home, so it stays
        // a collection in a one-value slot, and the fence holds verbatim.
        RegelSpraakModel m = resolveer("""
            Regel maatgevend gewicht
                geldig altijd
                    Het maatgevend gewicht van een Jutter moet gesteld worden op 10 kg
                    indien het gewicht van alle vondsten van de Jutter gelijk is aan 5 kg.
            """);
        assertEquals(1, wf096(m).size(), () -> "de ongekwalificeerde setlezing hoort te blijven: " + m.diagnostics);
        assertEquals(Severity.ERROR, wf096(m).get(0).severity());
        assertEquals("setlezing buiten een vouwbron: 'het gewicht van alle vondsten van de Jutter' noemt de waarde "
                + "van elk lid van de verzameling — alleen toegestaan als bron van één vouw (som, totaal, "
                + "minimum, maximum, eerste, laatste, aantal); hier wordt één waarde verwacht "
                + "(WF-096, IR-SPEC ch. 4.3)",
                wf096(m).get(0).message());
    }

    @Test void een_onbindbaar_bereik_wordt_niet_verlaagd_en_houdt_de_wf096_fout() {
        // Fail fast over a silent misbinding: with no feittype connecting Jutter and Vondst the range
        // does not resolve, and a Kwantorvouw without a range quantifies over nothing the sentence
        // named. The §8.2 qualifier itself is fine here (the attribute is temporal), so the ONLY
        // complaint is the one that was there before — the refusal is intact, not replaced.
        RegelSpraakModel m = Resolver.resolve("""
            Eenheidsysteem Gewicht
                het kilogram (mv: kilogrammen) kg

            Objecttype de Jutter (mv: Jutters) (bezield)
                het maatgevend gewicht Numeriek (getal met 2 decimalen) met eenheid kg;

            Objecttype de Vondst (mv: Vondsten)
                het gewicht (mv: gewichten) Numeriek (getal met 2 decimalen) met eenheid kg voor elke maand;
            """ + ZIN.formatted("alle"));
        assertEquals(1, wf096(m).size(), () -> "een onbindbaar bereik hoort de weigering te houden: " + m.diagnostics);
        assertEquals(1, m.diagnostics.size(), () -> "en verder niets te melden: " + m.diagnostics);
        assertFalse(emitCompact(m).contains("Kwantorvouw"),
                "een vouw zonder bereik hoort niet op de wire te belanden");
    }

    @Test void de_geschreven_kwantor_blijft_frontend_intern() {
        // Step 1 of the lowering hangs the written quantifier on the AttribuutRef so ALLE and GEEN are
        // distinguishable downstream. It is resolver input only: the reference node has no such wire
        // field, and a legitimate fold (a set read that KEEPS set: true) must not start writing one.
        RegelSpraakModel m = resolveer("""
            Regel maatgevend gewicht
                geldig altijd
                    Het maatgevend gewicht van een Jutter moet gesteld worden op de som van het gewicht van alle vondsten van de Jutter.
            """);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        String c = emitCompact(m);
        assertTrue(c.contains("\"set\":true"), () -> "de vouwbron hoort haar setlezing te houden:\n" + c);
        assertFalse(c.contains("\"kwantor\""),
                () -> "de geschreven kwantor is frontend-intern en hoort niet op de wire te staan:\n" + c);
    }
}
