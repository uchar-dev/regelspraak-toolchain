package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.7 the CONCATENATION of subject expressions under "het aantal" — the producer route for the
 * wire's {@code InstanceSetUnion} (uchar IR-SPEC ch. 4.3, WF-110; semantics RUNTIME-SEMANTICS
 * ch. 3.4d; maintainer decision 2026-09-20).
 *
 * <p><b>Red-first, measured on the pre-card frontend (commit 5b42e5b9).</b> Both golden sources of
 * this card — {@code corpus/golden/jutterswet-vondstunie} and {@code …-keurunie} — were refused by
 * {@code tellingAriteitFout} with the WF-037 arity ERROR and reached no disk at all. What changed is
 * not that refusal but its SCOPE: a count over an operand list is still ill-formed on the wire, and
 * a concatenation of SUBJECT expressions is no longer such a list — it is one union node, one
 * argument.
 *
 * <p>The sister suite {@link AantalOpgesomdTest} pins the half that did NOT move: a count over
 * VALUE operands stays refused, in the words it has carried since 2026-09-20. This suite pins the
 * half that opened, and the three edges where it does not.
 */
class AantalUnieTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal strandvondsten Numeriek (niet-negatief geheel getal);
            de gewogen vangst Numeriek (getal met 2 decimalen);
            de gewogen bijvangst Numeriek (getal met 2 decimalen);

        Objecttype de Vondst (mv: Vondsten)
            is gemerkt kenmerk (bijvoeglijk);
            is gekeurd kenmerk (bijvoeglijk);
            het gewicht Numeriek (geheel getal);

        Objecttype de Kavel (mv: Kavels)

        Feittype vondstaanmelding
            de aanmelder Jutter
            de aangemelde vondst (mv: aangemelde vondsten) Vondst
        Eén aanmelder meldt meerdere aangemelde vondsten

        Feittype vondstberging
            de berger Jutter
            de geborgen vondst (mv: geborgen vondsten) Vondst
        Eén berger bergt meerdere geborgen vondsten

        Feittype kaveltoewijzing
            de kavelhouder Jutter
            de duinkavel (mv: duinkavels) Kavel
        Eén kavelhouder houdt meerdere duinkavels
        """;

    /** Two selections over DIFFERENT relations — the shape no OR-filter can spell, and the one where
     *  deduplication stops being automatic and becomes an operation. */
    static final String TWEE_RELATIES = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter en geborgen vondsten van de Jutter.
        """;

    /** Two selections over ONE relation with their own characteristic subselections — the shape
     *  §5.7's own worked example spells and the one the v0 toolchain lowers. */
    static final String TWEE_KENMERKEN = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter die gemerkt zijn en aangemelde vondsten van de Jutter die gekeurd zijn.
        """;

    /** A filtered selection beside an UNFILTERED one — where the v0 toolchain leaves a marker,
     *  because its lowering ORs the operands' filter terms and has no term to OR for this one. */
    static final String KAAL_NAAST_GEFILTERD = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter die gemerkt zijn en aangemelde vondsten van de Jutter.
        """;

    /** Three selections through the comma spelling (§5.7: commas between all but the last two). */
    static final String KOMMA_DRIETAL = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter die gemerkt zijn, aangemelde vondsten van de Jutter die gekeurd zijn en geborgen vondsten van de Jutter.
        """;

    /** The first edge: two POPULATIONS. WF-110 (d) refuses it, and so does the toolchain — there on
     *  the mechanism ground that one accumulate has one source pattern, here because a count answers
     *  a cardinality over one declared type. */
    static final String ONGELIJKE_ELEMENTTYPES = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter en duinkavels van de Jutter.
        """;

    /** The second edge: a "die/dat" that is not a characteristic of the element. A silently dropped
     *  filter would count too many, and the union would ABSORB the error — an unfiltered selection
     *  is a superset of every filtered one over the same relation — so it is refused by name. */
    static final String ONOPLOSBARE_DEELSELECTIE = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter die groter zijn dan 5 en aangemelde vondsten van de Jutter die gekeurd zijn.
        """;

    /** The counter-proof that must NOT move: VALUE operands keep the refusal of 2026-09-20. */
    static final String WAARDE_OPERANDEN = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal de gewogen vangst van de Jutter en de gewogen bijvangst van de Jutter.
        """;

    /** The other counter-proof: v0's own one-argument instance count keeps its shape. */
    static final String GEWONE_TELLING = """

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal aangemelde vondsten van de Jutter.
        """;

    /** A SECOND model, and the only difference that matters: the element role declares NO {@code (mv:)}
     *  plural, the §13.3.9.1 cardinality sentence writes one ("meerdere vondsten"), and the selections
     *  navigate through the SUBJECT'S OWN role name ("van de vinder") rather than through the object
     *  type. That is the shape the toolchain's own concatenation fixtures spell, and it is where the
     *  by-name binding runs out: measured 2026-09-22, the model registers the cardinality sentence's
     *  plural as MULTIPLICITY and drops the spelling, so {@code elementRolBinding} — which binds only a
     *  declared singular or a declared plural, never a guessed one — finds nothing.
     *
     *  <p>Registering that plural instead was measured and rejected: it would move a golden's bytes and
     *  destroy {@code jutterswet-kaveltelling}, the WF-034 witness whose whole point is that such a
     *  plural does NOT bind and the type pair carries the fold. So the union derives the relation from
     *  the TYPE PAIR — what the one-selection count has always leaned on — and writes it out
     *  explicitly. WF-110 (e) is unmoved: it forbids the WIRE from omitting the relation, not the
     *  producer from deriving the one it writes. */
    static final String KARDINALITEITSMODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal strandvondsten Numeriek (niet-negatief geheel getal);

        Objecttype de Vondst (mv: Vondsten)
            is gemerkt kenmerk (bijvoeglijk);
            is gekeurd kenmerk (bijvoeglijk);

        Feittype vondstmelding
            de vinder Jutter
            de vondst Vondst
        Eén vinder meldt meerdere vondsten

        Regel aantal strandvondsten
            geldig altijd
                Het aantal strandvondsten van een Jutter moet berekend worden als het aantal vondsten van de vinder die gemerkt zijn en vondsten van de vinder die gekeurd zijn.
        """;

    @Test
    void het_kardinaliteitsmeervoud_met_eigen_rolnaam_bindt_via_het_typenpaar() {
        RegelSpraakModel m = Resolver.resolve(KARDINALITEITSMODEL);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        Expr.Functie f = assertInstanceOf(Expr.Functie.class, m.resolvedRegels.get(0).waarde());
        assertEquals(1, f.argumenten().size(), "de unie is ÉÉN argument");
        Expr.InstanceSetUnion u = assertInstanceOf(Expr.InstanceSetUnion.class, f.argumenten().get(0));
        assertEquals(2, u.takken().size());
        // the derived relation is written out per selection — the wire never omits it (WF-110 (e))
        for (Expr.InstanceSetUnion.Tak t : u.takken()) {
            assertNotNull(t.bronRol(), "elke selectie noemt haar relatie op de wire");
            assertEquals("vondstmelding", t.bronRol().feittype());
            assertEquals("vondst", t.bronRol().rol(), "de wire noemt de GEDECLAREERDE enkelvoudsrol");
            assertEquals("Vondst", t.elementType().naam);
        }
        assertEquals("gemerkt", assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Kenmerk.class,
                u.takken().get(0).filter()).kenmerk());
        assertEquals("gekeurd", assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Kenmerk.class,
                u.takken().get(1).filter()).kenmerk());
    }

    /** The narrowing the fallback must NOT have loosened: a by-NAME binding still refuses a guessed
     *  plural. Here two fact types relate Jutter to Vondst, so the type pair is ambiguous and there is
     *  nothing to fall back to — the selection is refused by name, exactly as before. Without this row
     *  the fix could have been "accept the best-effort plural", which would bind a spelling the model
     *  never defined. */
    @Test
    void een_dubbelzinnig_typenpaar_valt_nog_steeds_op_de_benoemde_weigering() {
        List<Diagnostic> ds = Resolver.resolve(KARDINALITEITSMODEL.replace(
                "Regel aantal strandvondsten",
                """
                Feittype vondstberging
                    de berger Jutter
                    de geborgen vondst Vondst
                Eén berger bergt meerdere geborgen vondsten

                Regel aantal strandvondsten""")).errors();
        assertEquals(1, ds.size(), () -> "verwacht één fout, kreeg: " + ds);
        assertTrue(ds.get(0).message().contains("niet eenduidig af te leiden"),
                () -> "de melding noemt de grond niet: " + ds.get(0).message());
        assertTrue(ds.get(0).message().contains("WF-110 (e)"), () -> ds.get(0).message());
    }

    private static RegelSpraakModel schoon(String regels) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regels);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        return m;
    }

    private static Expr.InstanceSetUnion unieVan(RegelSpraakModel m) {
        Expr.Functie f = assertInstanceOf(Expr.Functie.class, m.resolvedRegels.get(0).waarde());
        assertEquals(Expr.FunctieKind.AANTAL, f.kind());
        assertEquals(1, f.argumenten().size(), "de unie is ÉÉN argument, niet een operandenlijst");
        assertNull(f.elementObjecttype(), "de telling boven een unie noemt geen eigen bron (WF-110 (c))");
        assertNull(f.filter(), "de selecties dragen hun eigen filter (WF-110 (c))");
        return assertInstanceOf(Expr.InstanceSetUnion.class, f.argumenten().get(0));
    }

    private static List<Diagnostic> fouten(String regels) {
        return Resolver.resolve(MODEL + regels).errors();
    }

    @Test
    void de_concatenatie_over_twee_relaties_wordt_een_unie() {
        Expr.InstanceSetUnion u = unieVan(schoon(TWEE_RELATIES));
        assertEquals(2, u.takken().size());
        assertEquals("Vondst", u.takken().get(0).elementType().naam);
        assertEquals("Vondst", u.takken().get(1).elementType().naam);
        // the two selections walk DIFFERENT relations: this is the shape the OR-filter form cannot
        // express, and the reason the union node exists rather than a composite filter
        assertEquals("vondstaanmelding", u.takken().get(0).bronRol().feittype());
        assertEquals("aangemelde vondst", u.takken().get(0).bronRol().rol());
        assertEquals("vondstberging", u.takken().get(1).bronRol().feittype());
        assertEquals("geborgen vondst", u.takken().get(1).bronRol().rol());
        assertNull(u.takken().get(0).filter());
        assertNull(u.takken().get(1).filter());
    }

    @Test
    void de_concatenatie_over_kenmerkfilters_draagt_haar_filters_per_tak() {
        Expr.InstanceSetUnion u = unieVan(schoon(TWEE_KENMERKEN));
        assertEquals(2, u.takken().size());
        // one relation, two filters — and the filters sit on the SELECTIONS, never OR-ed onto the
        // count, which is what keeps one written form on one wire form
        assertEquals("vondstaanmelding", u.takken().get(0).bronRol().feittype());
        assertEquals("vondstaanmelding", u.takken().get(1).bronRol().feittype());
        assertEquals("gemerkt",
                assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Kenmerk.class,
                        u.takken().get(0).filter()).kenmerk());
        assertEquals("gekeurd",
                assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Kenmerk.class,
                        u.takken().get(1).filter()).kenmerk());
    }

    @Test
    void een_kale_selectie_naast_een_gefilterde_blijft_gedragen() {
        // the DIVERGENCE from the v0 toolchain, deliberately: its marker there is a property of the
        // OR-filter lowering, not of the operation, and our wire carries the selections themselves
        Expr.InstanceSetUnion u = unieVan(schoon(KAAL_NAAST_GEFILTERD));
        assertEquals(2, u.takken().size());
        assertNotNull(u.takken().get(0).filter(), "de eerste selectie draagt haar kenmerkfilter");
        assertNull(u.takken().get(1).filter(), "een kale selectie is hier de gewone vorm");
    }

    @Test
    void de_komma_spelling_bouwt_drie_takken() {
        Expr.InstanceSetUnion u = unieVan(schoon(KOMMA_DRIETAL));
        assertEquals(3, u.takken().size(), "de komma-spelling verloor een selectie");
        assertEquals("vondstberging", u.takken().get(2).bronRol().feittype());
    }

    @Test
    void de_unie_bereikt_de_wire_met_haar_eigen_knoopnaam() {
        String json = IrEmitter.emit(schoon(TWEE_RELATIES));
        assertTrue(json.contains("\"node\": \"InstanceSetUnion\""), () -> "geen unie op de wire:\n" + json);
        assertTrue(json.contains("\"node\": \"InstanceSetBranch\""), () -> "geen selectie op de wire:\n" + json);
        assertTrue(json.contains("\"elementObjecttype\": null"),
                () -> "de telling draagt nog een eigen elementtype:\n" + json);
        // the node denotes INSTANCES and carries no datatype: a "type" key on the union would be an
        // annotation nobody derives, and the verifier's type prologue exempts it for exactly that
        int begin = json.indexOf("\"node\": \"InstanceSetUnion\"");
        int einde = json.indexOf("\"branches\"", begin);
        assertTrue(begin >= 0 && einde > begin, () -> "geen unie-kop op de wire:\n" + json);
        assertFalse(json.substring(begin, einde).contains("\"type\""),
                () -> "de unie draagt een type:\n" + json.substring(begin, einde));
    }

    @Test
    void ongelijke_elementtypes_worden_benoemd_geweigerd() {
        List<Diagnostic> ds = fouten(ONGELIJKE_ELEMENTTYPES);
        assertEquals(1, ds.size(), () -> "verwacht één fout, kreeg: " + ds);
        assertEquals(Severity.ERROR, ds.get(0).severity());
        String msg = ds.get(0).message();
        assertTrue(msg.contains("verschillende populaties"), () -> "de melding noemt de grond niet: " + msg);
        assertTrue(msg.contains("'Vondst'") && msg.contains("'Kavel'"),
                () -> "de melding noemt de twee populaties niet: " + msg);
        assertTrue(msg.contains("WF-110 (d)"), () -> "de melding citeert de regel niet: " + msg);
    }

    @Test
    void een_onoplosbare_deelselectie_wordt_benoemd_geweigerd() {
        List<Diagnostic> ds = fouten(ONOPLOSBARE_DEELSELECTIE);
        assertEquals(1, ds.size(), () -> "verwacht één fout, kreeg: " + ds);
        String msg = ds.get(0).message();
        assertTrue(msg.contains("lost niet op naar een kenmerk"),
                () -> "de melding noemt de grond niet: " + msg);
        assertTrue(msg.contains("selectie 1"), () -> "de melding wijst de selectie niet aan: " + msg);
    }

    @Test
    void de_waarde_operanden_houden_hun_eigen_weigering() {
        // the half that did not move: the head of the refusal is the one of 2026-09-20, word for word
        List<Diagnostic> ds = fouten(WAARDE_OPERANDEN);
        assertEquals(1, ds.size(), () -> "verwacht één fout, kreeg: " + ds);
        String msg = ds.get(0).message();
        assertTrue(msg.contains("noemt 2 operanden"), () -> "de melding telt de operanden niet: " + msg);
        assertTrue(msg.contains("geen opgesomde vorm"), () -> "de melding noemt de vorm niet: " + msg);
        assertTrue(msg.contains("WF-037"), () -> "de melding citeert de ariteitsregel niet: " + msg);
        // and the tail corrects what the head would mislead about on its own: the source language
        // DOES spell a concatenation, it just is not this sentence's
        assertTrue(msg.contains("niet elke operand is een rolselectie"),
                () -> "de melding zegt niet waarom de unie-route hier niet opent: " + msg);
    }

    @Test
    void de_gewone_telling_blijft_schoon() {
        Expr.Functie f = assertInstanceOf(Expr.Functie.class, schoon(GEWONE_TELLING).resolvedRegels.get(0).waarde());
        assertEquals(Expr.FunctieKind.AANTAL, f.kind());
        assertEquals(1, f.argumenten().size());
        assertNotNull(f.elementObjecttype(), "de eenargumentstelling verloor haar elementtype");
        assertFalse(f.argumenten().get(0) instanceof Expr.InstanceSetUnion,
                "een enkele selectie is geen unie — die vorm heeft haar eigen spelling (WF-110 (b))");
    }

    /** WF-110 (a) restricts the UNION NODE to one position; the COUNT over it is an ordinary whole
     *  number and stands wherever any other count stands — a `voorwaarde` included. The clause was
     *  readable as forbidding that, so it is pinned here and by the golden
     *  `jutterswet-unievoorwaarde` rather than left to a reading. */
    static final String IN_VOORWAARDE = """

        Regel ruim voorzien
            geldig altijd
                Een Jutter is ruim voorzien indien het aantal aangemelde vondsten van de Jutter en geborgen vondsten van de Jutter groter is dan 2.
        """;

    @Test
    void de_unie_telling_staat_ook_in_een_voorwaarde() {
        RegelSpraakModel m = schoon(IN_VOORWAARDE);
        var v = assertInstanceOf(io.github.yhavinga.regelspraak.ir.Conditie.Vergelijking.class,
                m.resolvedRegels.get(0).voorwaarde());
        Expr.Functie tel = assertInstanceOf(Expr.Functie.class, v.links());
        assertEquals(Expr.FunctieKind.AANTAL, tel.kind());
        assertEquals(2, assertInstanceOf(Expr.InstanceSetUnion.class, tel.argumenten().get(0)).takken().size());
    }

    /** A refused concatenation used to raise a "die/dat wordt nog niet omgezet" WARNING even when no
     *  operand wrote a subselectie at all — a diagnosis about something the sentence does not carry.
     *  The guard asks the question only where there is one; a sentence WITH an unresolvable
     *  subselectie keeps both its ERROR and its WARNING, which is the other half of the same pin. */
    @Test
    void een_geweigerde_unie_zonder_deelselectie_krijgt_geen_deelselectie_waarschuwing() {
        List<Diagnostic> ds = Resolver.resolve(MODEL + ONGELIJKE_ELEMENTTYPES).diagnostics;
        assertEquals(1, ds.size(), () -> "verwacht alleen de benoemde ERROR, kreeg: " + ds);
        assertEquals(Severity.ERROR, ds.get(0).severity());
        List<Diagnostic> metDie = Resolver.resolve(MODEL + ONOPLOSBARE_DEELSELECTIE).diagnostics;
        assertEquals(2, metDie.size(), () -> "een echte deelselectie hoort haar WARNING te houden: " + metDie);
        // the COUNT alone would pass on any second diagnosis whatever, so pin which one it is: the
        // surviving non-error must be the die/dat WARNING and nothing else
        List<Diagnostic> waarschuwingen = metDie.stream()
                .filter(d -> d.severity() == Severity.WARNING).toList();
        assertEquals(1, waarschuwingen.size(), () -> "verwacht precies één WARNING: " + metDie);
        assertTrue(waarschuwingen.get(0).message().contains("'die/dat'-subselectie op een aggregatie-operand"),
                () -> "de overblijvende WARNING is niet de deelselectie-melding: " + waarschuwingen.get(0));
        assertEquals(1, metDie.stream().filter(d -> d.severity() == Severity.ERROR).count(),
                () -> "verwacht precies één ERROR: " + metDie);
    }

    @Test
    void een_geweigerde_unie_bereikt_de_wire_niet() {
        RegelSpraakModel m = Resolver.resolve(MODEL + ONGELIJKE_ELEMENTTYPES);
        assertFalse(m.isOk(), "de geweigerde unie levert geen fout op");
        // the DIAGNOSTIC names the node — it says which route did not open — so the test asks after
        // the node's own wire spelling, not after the word; the ERROR is set aside for that reading
        // ({@link Emissie}), since the emitter refuses the model itself (WF-113 (a))
        assertFalse(Emissie.zonderFouten(m).contains("\"node\": \"InstanceSetUnion\""),
                "een geweigerde unie hoort geen knoop op de wire achter te laten");
    }
}
