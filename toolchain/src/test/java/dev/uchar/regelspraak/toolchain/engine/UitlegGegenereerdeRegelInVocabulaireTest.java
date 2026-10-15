package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.model.RegelSpraakModel.GegenereerdUit;
import dev.uchar.regelspraak.model.RegelSpraakModel.Herkomstvorm;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The rule {@code afwijking opgave X} is uchar's, not the author's: the opgave marker generates it (uchar's
 * DIVERGENCES §7), and the text of its operands is uchar's canonical spelling, "zijn opgegeven X". In a vocabulary the
 * explanation names the attribute such an operand reads in that vocabulary, as it names the characteristic of the
 * main clause. A rule the author wrote keeps the text of its operands in every vocabulary, also where it reads the
 * same generated attribute and where its name looks like a generated one: what uchar generated is read in uchar's
 * table of origins ({@code RegelSpraakModel.gegenereerdUit}), not from a name.
 */
class UitlegGegenereerdeRegelInVocabulaireTest {

    private static final String MODEL = """
            Vocabulaire cgm (taal: nl)
            Vocabulaire handboek (taal: nl)

            Objecttype de Vondst (mv: Vondsten)
                de vinddag Datum in dagen;
                de meldingsdatum (cgm: Datum melding vondst) (handboek: dag van de melding) Datum in dagen
                    (mag opgegeven worden);
                is zelf gemeld kenmerk (bijvoeglijk);

            Regel melding
                geldig altijd
                    De meldingsdatum van een Vondst moet berekend worden als de vinddag van de Vondst plus 3 dagen.

            Regel afwijking opgave vinddag
                geldig altijd
                    Een Vondst is zelf gemeld
                    indien zijn opgegeven meldingsdatum gevuld is.
            """;

    private static final String DOCUMENT = MODEL + """

            Scenario opgave
            Gegeven is de volgende situatie:
            een Vondst V1
            - de vinddag is 10-03-2026
            - de opgegeven meldingsdatum is 12-03-2026
            een Vondst V2
            - de vinddag is 10-03-2026
            Met als verwacht resultaat:
            - V1 heeft afwijkende opgave meldingsdatum
            - V1 is zelf gemeld
            - V2 heeft geen afwijkende opgave meldingsdatum
            - V2 is niet zelf gemeld
            - de gegevens zijn inconsistent volgens "controle opgave meldingsdatum" voor V1
            """;

    /** A row of a decision table is a rule uchar made too, from the author's column headers and cells. */
    private static final String BESLISTABEL = """
            Vocabulaire cgm (taal: nl)

            Objecttype de Vondst (mv: Vondsten)
                het gewicht (cgm: Gewicht vondst) Numeriek (geheel getal);
                is zwaar kenmerk (bijvoeglijk);

            Beslistabel zware vondst
                geldig altijd
            | | een Vondst is zwaar | indien zijn gewicht groter is dan |
            |---|---|---|
            | 1 | waar | 10 |

            Scenario zwaar
            Gegeven is de volgende situatie:
            een Vondst V1
            - het gewicht is 12
            Met als verwacht resultaat:
            - V1 is zwaar
            """;

    private static List<String> uitleg(String vocabulaire) {
        return uitleg(ScenarioResolver.resolve(DOCUMENT), vocabulaire);
    }

    private static List<String> uitleg(ScenarioBundel bundel, String vocabulaire) {
        List<ScenarioRunner.Uitgelegd> u = ScenarioRunner.runMetUitleg(bundel, vocabulaire);
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).resultaat().status(), () -> u.get(0).resultaat().diagnose());
        assertNull(u.get(0).uitlegFout(), u.get(0)::uitlegFout);
        return u.get(0).uitleg();
    }

    /** The author's rule, the same in every vocabulary: it reads the generated attribute in the author's words. */
    private static final String V1_AUTEUR =
            "V1 is zelf gemeld, omdat zijn opgegeven meldingsdatum (12-03-2026) gevuld is";
    private static final String V2_AUTEUR =
            "V2 is niet zelf gemeld, want niet geldt: zijn opgegeven meldingsdatum (onbekend) gevuld is";

    @Test
    void canoniek_staat_uchars_tekst_er_zoals_uchar_hem_schrijft() {
        assertEquals(List.of(
                V1_AUTEUR,
                "V1 heeft afwijkende opgave meldingsdatum, omdat zijn opgegeven meldingsdatum (12-03-2026) gevuld is"
                        + " en zijn berekende meldingsdatum (13-03-2026) gevuld is en zijn opgegeven meldingsdatum"
                        + " (12-03-2026) is ongelijk aan zijn berekende meldingsdatum (13-03-2026)",
                V2_AUTEUR,
                "V2 heeft geen afwijkende opgave meldingsdatum, want niet geldt: zijn opgegeven meldingsdatum"
                        + " (onbekend) gevuld is"),
                uitleg(Naamgeving.CANONIEK));
    }

    @Test
    void in_cgm_noemt_de_gegenereerde_regel_de_operanden_in_cgm() {
        assertEquals(List.of(
                V1_AUTEUR,
                "V1 heeft afwijkende opgave Datum melding vondst, omdat zijn opgegeven Datum melding vondst"
                        + " (12-03-2026) gevuld is en zijn berekende Datum melding vondst (13-03-2026) gevuld is en"
                        + " zijn opgegeven Datum melding vondst (12-03-2026) is ongelijk aan zijn berekende Datum"
                        + " melding vondst (13-03-2026)",
                V2_AUTEUR,
                "V2 heeft geen afwijkende opgave Datum melding vondst, want niet geldt: zijn opgegeven Datum melding"
                        + " vondst (onbekend) gevuld is"),
                uitleg("cgm"));
    }

    @Test
    void in_handboek_noemt_de_gegenereerde_regel_de_operanden_in_handboek() {
        assertEquals(List.of(
                V1_AUTEUR,
                "V1 heeft afwijkende opgave dag van de melding, omdat zijn opgegeven dag van de melding (12-03-2026)"
                        + " gevuld is en zijn berekende dag van de melding (13-03-2026) gevuld is en zijn opgegeven"
                        + " dag van de melding (12-03-2026) is ongelijk aan zijn berekende dag van de melding"
                        + " (13-03-2026)",
                V2_AUTEUR,
                "V2 heeft geen afwijkende opgave dag van de melding, want niet geldt: zijn opgegeven dag van de"
                        + " melding (onbekend) gevuld is"),
                uitleg("handboek"));
    }

    /** uchar generated the row's rule, and the text of its operand is the author's column header: it stays as written,
     *  in a vocabulary as well. */
    @Test
    void een_beslistabelrij_houdt_de_tekst_van_de_auteur() {
        ScenarioBundel bundel = ScenarioResolver.resolve(BESLISTABEL);
        assertEquals(List.of(Herkomstvorm.BESLISTABELRIJ),
                bundel.model().gegenereerd.values().stream().map(GegenereerdUit::vorm).toList());
        assertEquals(List.of("V1 is zwaar, omdat zijn gewicht (12) is groter dan 10"), uitleg(bundel, "cgm"));
    }

    /** The decision service explains on the model it resolved from its sources, which carries uchar's table of
     *  origins itself, so the verbaliser reads the table there without being handed it. */
    @Test
    void op_het_model_van_de_frontend_leest_de_uitleg_de_herkomst_daar() {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(MODEL);
        Object v2 = engine.nieuw("Vondst");
        RegelSpraakRuntime.set(v2, Namen.ident("vinddag", false), LocalDate.of(2026, 3, 10));
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(v2).fire();
            s.zetTerug();
        }
        assertEquals(List.of(
                "V2 is niet zelf gemeld, want niet geldt: zijn opgegeven meldingsdatum (onbekend) gevuld is",
                "V2 heeft geen afwijkende opgave Datum melding vondst, want niet geldt: zijn opgegeven Datum melding"
                        + " vondst (onbekend) gevuld is"),
                new Verbalisator(engine.model(), Map.of(), Naamgeving.van(engine.model(), "cgm"))
                        .verbaliseer(Map.of("V2", v2), Map.of("V2", engine.model().objecttypeOpNaam("Vondst"))));
    }
}
