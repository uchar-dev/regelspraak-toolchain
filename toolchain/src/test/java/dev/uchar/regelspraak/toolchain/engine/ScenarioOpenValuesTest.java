package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios of a rule set that infers nothing from an empty value ({@code Afleiden uit leeg: nee}, uchar's
 * RUNTIME-SEMANTICS ch. 1.5). A scenario that expects a value, leeg or the absence of a characteristic where the outcome is
 * open fails, with uchar's word for it; one that expects it open ("staat niet vast") passes; a situation gives a
 * characteristic as not holding ("- is niet betwist"), which in a document without the property changes nothing and is a
 * warning of uchar's scenario layer. The explanation says what keeps an outcome open.
 */
class ScenarioOpenValuesTest {

    private static final String RULES = """
        Afleiden uit leeg: nee

        Objecttype de Vondst (mv: Vondsten)
            de taxatie Numeriek (geheel getal);
            het gewicht Numeriek (geheel getal);
            het vindloon Numeriek (geheel getal);
            is kostbaar kenmerk (bijvoeglijk);

        Regel kostbaarheid
            geldig altijd
                Een Vondst is kostbaar indien zijn taxatie groter is dan 1000.

        Regel vindloon
            geldig altijd
                Het vindloon van een Vondst moet berekend worden als zijn gewicht plus 10.

        """;

    private static List<ScenarioRunner.Explained> play(String scenarios) {
        return ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(RULES + scenarios));
    }

    @Test
    void an_expectation_on_an_open_outcome_fails_with_uchars_word() {
        List<ScenarioRunner.Explained> played = play("""
            Scenario waarde verwacht
            Gegeven is de volgende situatie:
            een Vondst V1
            Met als verwacht resultaat:
            - het vindloon van V1 is 10
            Scenario leeg verwacht
            Gegeven is de volgende situatie:
            een Vondst V1
            Met als verwacht resultaat:
            - het vindloon van V1 is leeg
            Scenario geen kenmerk verwacht
            Gegeven is de volgende situatie:
            een Vondst V1
            Met als verwacht resultaat:
            - V1 is niet kostbaar
            """);
        assertEquals(3, played.size());
        for (ScenarioRunner.Explained p : played) {
            assertEquals(Status.FAIL, p.result().status(), p.result()::diagnostic);
            assertEquals(1, p.result().expectations().size());
            assertEquals("openstaand", p.result().expectations().get(0).actual(), p.result()::diagnostic);
            assertTrue(p.result().diagnostic().contains("openstaand"), p.result()::diagnostic);
        }
    }

    @Test
    void what_stands_is_compared_and_explained_as_ever() {
        ScenarioRunner.Explained p = play("""
            Scenario gegeven
            Gegeven is de volgende situatie:
            een Vondst V1
            - de taxatie is 1500
            - het gewicht is 5
            Met als verwacht resultaat:
            - het vindloon van V1 is 15
            - V1 is kostbaar
            """).get(0);
        assertEquals(Status.PASS, p.result().status(), p.result()::diagnostic);
        assertEquals(List.of("V1 is kostbaar, omdat zijn taxatie (1.500) is groter dan 1.000",
                "V1 heeft als vindloon 15 volgens de regel 'vindloon' (onvoorwaardelijk): zijn gewicht plus 10, met zijn gewicht"
                        + " (5)"), p.explanation());
        assertNull(p.explanationError());
        assertEquals(0, p.session().standing().objects().get(0).open().size());
        assertEquals(List.of(), p.openInputs());
    }

    @Test
    void the_explanation_says_what_keeps_an_outcome_open() {
        ScenarioRunner.Explained p = play("""
            Scenario zonder gewicht
            Gegeven is de volgende situatie:
            een Vondst V1
            - de taxatie is 1500
            Met als verwacht resultaat:
            - V1 is kostbaar
            """).get(0);
        assertEquals(Status.PASS, p.result().status(), p.result()::diagnostic);
        assertEquals(List.of("V1 is kostbaar, omdat zijn taxatie (1.500) is groter dan 1.000",
                "V1: zijn vindloon staat niet vast, want zijn gewicht is niet gegeven; voor de regel 'vindloon' staat niet vast:"
                        + " zijn gewicht plus 10, met zijn gewicht (niet gegeven)"), p.explanation());
        assertEquals(1, p.openInputs().size());
        assertEquals("gewicht", p.openInputs().get(0).input().member());
        assertEquals(List.of("vindloon"), p.openInputs().get(0).outcomes().stream().map(o -> o.member()).toList());
    }

    /** A complete toeslag that only an initialisation writes, which reads the gewicht: without a gewicht the toeslag is open by
     *  what its initialisation reads, so it is an outcome under the gewicht, beside the vindloon, and no input of its own. */
    @Test
    void a_complete_member_whose_initialisation_reads_an_open_member_is_an_outcome() {
        ScenarioRunner.Explained p = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(RULES.replace(
                "het vindloon Numeriek (geheel getal);",
                "het vindloon Numeriek (geheel getal);\n    de toeslag Numeriek (geheel getal) (volledig gegeven);") + """
            Regel basistoeslag
                geldig altijd
                    De toeslag van een Vondst moet geïnitialiseerd worden op zijn gewicht.

            Scenario zonder gewicht
            Gegeven is de volgende situatie:
            een Vondst V1
            - de taxatie is 1500
            Met als verwacht resultaat:
            - V1 is kostbaar
            """)).get(0);
        assertEquals(Status.PASS, p.result().status(), p.result()::diagnostic);
        assertTrue(p.explanation().contains("V1: zijn toeslag staat niet vast, want zijn gewicht is niet gegeven; voor de regel"
                + " 'basistoeslag' staat niet vast: zijn gewicht (niet gegeven)"), p.explanation()::toString);
        assertEquals(1, p.openInputs().size());
        assertEquals("gewicht", p.openInputs().get(0).input().member());
        assertEquals(List.of("vindloon", "toeslag"), p.openInputs().get(0).outcomes().stream().map(o -> o.member()).toList());
    }

    private static final String PREMIE = """
        Objecttype de Vondst (mv: Vondsten)
            de taxatie Numeriek (geheel getal);
            de premie Numeriek (geheel getal);
            is betwist kenmerk (bijvoeglijk);

        Regel premie
            geldig altijd
                De premie van een Vondst moet gesteld worden op 5 indien hij niet betwist is.

        Scenario niet betwist
        Gegeven is de volgende situatie:
        een Vondst V1
        - is niet betwist
        Met als verwacht resultaat:
        - de premie van V1 is 5
        - V1 is niet betwist
        """;

    /** A situation gives betwist as not holding, so the premie stands; without it the premie and betwist are open, as the
     *  second scenario expects. */
    @Test
    void a_scenario_gives_a_characteristic_as_not_holding_and_expects_an_open_outcome() {
        List<ScenarioRunner.Explained> played = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(
                "Afleiden uit leeg: nee\n\n" + PREMIE + """

                Scenario betwist open
                Gegeven is de volgende situatie:
                een Vondst V1
                Met als verwacht resultaat:
                - de premie van V1 staat niet vast
                - of V1 betwist is, staat niet vast
                """));
        assertEquals(2, played.size());
        for (ScenarioRunner.Explained p : played) {
            assertEquals(Status.PASS, p.result().status(), p.result()::diagnostic);
            assertEquals(2, p.result().expectations().size(), p.result()::diagnostic);
        }
        assertEquals(List.of("V1 heeft als premie 5 volgens de regel 'premie': 5, omdat het onderwerp niet betwist is"),
                played.get(0).explanation());
        assertEquals(List.of("V1: zijn premie staat niet vast, want het is niet gegeven of hij betwist is; voor de regel"
                + " 'premie' staat niet vast: het onderwerp niet betwist is (niet gegeven)"), played.get(1).explanation());
    }

    /** In a document without the property "- is niet betwist" changes nothing: the scenario plays as without it, and the
     *  scenario command prints uchar's warning on stderr. */
    @Test
    void a_characteristic_given_as_not_holding_in_an_ordinary_document_is_a_warning() throws IOException {
        assertEquals(List.of("WARNING regel 13: scenario: 'is niet betwist' verandert in een document zonder 'Afleiden uit"
                + " leeg: nee' niets: daar geldt een kenmerk dat de situatie niet noemt al niet; de zin telt alleen in een"
                + " document met 'Afleiden uit leeg: nee'"), ScenarioRunner.warnings(ScenarioResolver.resolve(PREMIE)));
        Path doc = Files.createTempFile("premie-", ".rgs");
        try {
            Files.writeString(doc, PREMIE, StandardCharsets.UTF_8);
            PrintStream out = System.out, err = System.err;
            ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
            int code;
            try {
                code = ScenarioRunnerCli.run(new String[]{doc.toString()});
            } finally {
                System.setOut(out);
                System.setErr(err);
            }
            assertEquals(0, code, o.toString(StandardCharsets.UTF_8));
            assertEquals("PASS  niet betwist\n", o.toString(StandardCharsets.UTF_8));
            assertTrue(e.toString(StandardCharsets.UTF_8).contains("  WARNING regel 13: scenario: 'is niet betwist'"
                    + " verandert in een document zonder 'Afleiden uit leeg: nee' niets"), e.toString(StandardCharsets.UTF_8));
        } finally {
            Files.delete(doc);
        }
    }

    /** A fold that can be open over a relation both of whose roles are to-many: uchar plays the document without readings and
     *  refuses it with them, so the scenario has its verdict, and uchar's sentence stands in the place of the explanation. */
    @Test
    void a_document_that_uchar_refuses_with_readings_is_played_without_an_explanation() {
        List<ScenarioRunner.Explained> played = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve("""
            Afleiden uit leeg: nee

            Objecttype de Jutter (mv: Jutters) (bezield)
                de hulpsom Numeriek (geheel getal);

            Objecttype het Wrak (mv: Wrakken)
                de lading Numeriek (geheel getal);

            Feittype hulp
                de helper (mv: helpers) Jutter
                het geholpen wrak (mv: geholpen wrakken) Wrak
                meerdere helpers bergen meerdere geholpen wrakken

            Regel hulpsom
                geldig altijd
                    De hulpsom van een Jutter moet berekend worden als de som van de lading van alle geholpen wrakken van de Jutter.

            Scenario geen wrak
            Gegeven is de volgende situatie:
            een Jutter J1
            Met als verwacht resultaat:
            - de hulpsom van J1 is leeg
            """));
        assertEquals(1, played.size());
        assertEquals(Status.PASS, played.get(0).result().status(), played.get(0).result()::diagnostic);
        assertEquals(List.of(), played.get(0).explanation());
        assertTrue(played.get(0).explanationError().contains("een vouw over 'hulp', een relatie waarvan beide rollen meervoudig"
                + " zijn"), played.get(0)::explanationError);
        assertEquals(played.get(0).explanationError(), played.get(0).sessionError());
    }

    @Test
    void open_and_settled_empty_to_one_roles_in_scenarios_and_explanations() {
        String rules = """
            Afleiden uit leeg: nee

            Objecttype de Jutter (mv: Jutters) (bezield)
                de bonus Numeriek (geheel getal);
                is ingezetene kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                de taxatie Numeriek (geheel getal);
                het eindloon Numeriek (geheel getal);
                is toegewezen kenmerk (bijvoeglijk);
                is kustvondst kenmerk (bijvoeglijk);

            Feittype vinding
                de vinder Jutter
                de vondst (mv: vondsten) Vondst
                één vinder heeft meerdere vondsten

            Regel eindloon
                geldig altijd
                    Het eindloon van een Vondst moet berekend worden als zijn taxatie plus de bonus van de vinder van de Vondst.

            Regel toegewezen
                geldig altijd
                    Een Vondst is toegewezen indien hij een vondst is.

            Regel kustvondst
                geldig altijd
                    Een Vondst is kustvondst indien zijn vinder ingezetene is.
            """;
        List<ScenarioRunner.Explained> played = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(rules + """

            Scenario zonder vinder
            Gegeven is de volgende situatie:
            een Vondst V1
            - de taxatie is 50
            Met als verwacht resultaat:
            - het eindloon van V1 staat niet vast
            - of V1 toegewezen is, staat niet vast
            - of V1 kustvondst is, staat niet vast

            Scenario met lege vinder
            Gegeven is de volgende situatie:
            een Vondst V1
            - de taxatie is 50
            - de vinder is leeg
            Met als verwacht resultaat:
            - het eindloon van V1 is 50
            - V1 is niet toegewezen
            - V1 is niet kustvondst
            """));
        assertEquals(2, played.size());
        ScenarioRunner.Explained open = played.get(0);
        assertEquals(Status.PASS, open.result().status(), open.result()::diagnostic);
        assertTrue(open.explanation().contains("V1: zijn eindloon staat niet vast, want zijn vinder is niet gegeven; voor de"
                + " regel 'eindloon' staat niet vast: zijn taxatie plus de bonus van de vinder van de Vondst, met zijn taxatie"
                + " (50) en de bonus van de vinder van de Vondst (staat niet vast)"), open.explanation()::toString);
        assertTrue(open.explanation().contains("V1: of hij toegewezen is, staat niet vast, want zijn vinder is niet gegeven;"
                + " voor de regel 'toegewezen' staat niet vast: het onderwerp een vondst is (staat niet vast)"),
                open.explanation()::toString);
        assertTrue(open.explanation().contains("V1: of hij kustvondst is, staat niet vast, want zijn vinder is niet gegeven;"
                + " voor de regel 'kustvondst' staat niet vast: zijn vinder ingezetene is (staat niet vast)"),
                open.explanation()::toString);
        assertEquals(1, open.openInputs().size(), open.openInputs()::toString);
        assertTrue(open.openInputs().get(0).input().role());
        assertEquals("vinder", open.openInputs().get(0).input().member());
        assertEquals("vinding", open.openInputs().get(0).input().factType());
        assertEquals(List.of("eindloon", "toegewezen", "kustvondst"),
                open.openInputs().get(0).outcomes().stream().map(o -> o.member()).toList());

        ScenarioRunner.Explained settled = played.get(1);
        assertEquals(Status.PASS, settled.result().status(), settled.result()::diagnostic);
        assertEquals(List.of(), settled.openInputs());
    }
}
