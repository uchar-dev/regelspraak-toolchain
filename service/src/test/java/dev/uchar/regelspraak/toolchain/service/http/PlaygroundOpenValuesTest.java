package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenarios of a rule set that infers nothing from an empty value ({@link OpenValuesSource}) in the playground, on the
 * ordinary route and with {@code via=service}: the verdict of a scenario that expects a value where the outcome is open is
 * FAIL, with uchar's word for it; the fixpoint lists what is open, with the input that does not stand; the explanation
 * says what keeps an outcome open, and {@code openInputs} what can be given. The situation of a scenario asserts a characteristic or leaves it out, so its request
 * states none that does not hold.
 */
class PlaygroundOpenValuesTest {

    private static final String SCENARIOS = """

        Scenario alles gegeven
        toetst de regels kostbaarheid, vindloon en bergloon
        Gegeven is de volgende situatie:
        een Vondst V1
        - de taxatie is 50
        - het bergloon is 7
        Met als verwacht resultaat:
        - het vindloon van V1 is 60
        - het bergloon van V1 is 7
        - V1 is niet kostbaar

        Scenario zonder taxatie
        toetst de regels kostbaarheid, vindloon en bergloon
        Gegeven is de volgende situatie:
        een Vondst V1
        - het bergloon is 7
        Met als verwacht resultaat:
        - het vindloon van V1 is 60
        - het bergloon van V1 is 7
        - V1 is niet kostbaar
        """;

    private static JsonNode ordinary;
    private static JsonNode via;

    @BeforeAll
    static void analyse() {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.SOURCE + SCENARIOS;
        ordinary = Playground.analyze(source).at("/scenario/results");
        via = Playground.analyze(source, null, core, null, false).at("/scenario/results");
        assertEquals(2, ordinary.size());
        assertEquals(2, via.size());
    }

    @Test
    void what_stands_passes() {
        for (JsonNode results : new JsonNode[]{ordinary, via})
            assertEquals("PASS", results.at("/0/status").asText(), results.toString());
        assertEquals("vondst", via.at("/0/path").asText());
    }

    @Test
    void an_expectation_on_an_open_outcome_fails_with_uchars_word() {
        for (JsonNode results : new JsonNode[]{ordinary, via}) {
            JsonNode s = results.get(1);
            assertEquals("FAIL", s.get("status").asText(), s.toString());
            assertEquals(3, s.get("expectations").size());
            for (JsonNode e : s.get("expectations")) {
                assertEquals("openstaand", e.get("actual").asText(), s.toString());
                assertEquals(false, e.get("ok").asBoolean());
            }
            assertTrue(s.get("diagnosis").asText().contains("openstaand"), s.toString());
        }
        assertEquals(ordinary.at("/1/expectations"), via.at("/1/expectations"));
        assertEquals(ordinary.at("/1/diagnosis"), via.at("/1/diagnosis"));
    }

    /** The sentences of the service and of the ordinary route are the same on the forms both run. */
    @Test
    void the_explanation_says_what_keeps_an_outcome_open() {
        for (JsonNode results : new JsonNode[]{ordinary, via}) {
            String open = results.at("/1/explanation").toString();
            for (String sentence : new String[]{
                    "V1: of hij kostbaar is, staat niet vast, want zijn taxatie is niet gegeven; voor de regel 'kostbaarheid' staat"
                            + " niet vast: zijn taxatie (niet gegeven) is groter dan 1000",
                    "V1: zijn vindloon staat niet vast, want zijn taxatie is niet gegeven; voor de regel 'vindloon' staat niet vast:"
                            + " zijn taxatie plus 10, met zijn taxatie (niet gegeven)",
                    "V1: zijn bergloon staat niet vast, want zijn taxatie is niet gegeven; de invoerwaarde 7 geldt pas als vaststaat"
                            + " dat geen regel een waarde geeft; voor de regel 'bergloon' staat niet vast: zijn taxatie (niet gegeven)"
                            + " is groter dan 1000"})
                assertTrue(open.contains("\"" + sentence + "\""), open);
            assertFalse(results.get(1).has("explanationError"), results.toString());
            assertTrue(results.at("/0/explanation").toString().contains("\"V1 heeft als vindloon 60 volgens de regel 'vindloon'"
                    + " (onvoorwaardelijk): zijn taxatie plus 10, met zijn taxatie (50)\""), results.toString());
        }
    }

    /** On the ordinary route every derived member of the situation is an outcome, and via the service the output of the entry
     *  point: there the taxatie and betwist are outcomes themselves and betwist keeps the premie open. */
    @Test
    void the_open_inputs_of_a_scenario() throws Exception {
        assertEquals(ServiceHttp.MAPPER.readTree("""
            [{"kind": "attribute", "instance": "V1", "name": "taxatie", "forOutputs": [
                {"instance": "V1", "name": "vindloon", "kind": "attribute"},
                {"instance": "V1", "name": "bergloon", "kind": "attribute"},
                {"instance": "V1", "name": "kostbaar", "kind": "characteristic"}]}]"""), ordinary.at("/1/openInputs"));
        assertEquals(0, ordinary.at("/0/openInputs").size(), ordinary.toString());
        assertEquals("taxatie", via.at("/1/openInputs/0/name").asText());
        assertEquals("betwist", via.at("/1/openInputs/1/name").asText());
        assertEquals("[{\"instance\":\"V1\",\"name\":\"premie\",\"kind\":\"attribute\"},{\"instance\":\"V1\","
                + "\"name\":\"betwist\",\"kind\":\"characteristic\"}]", via.at("/1/openInputs/1/forOutputs").toString());
    }

    /** A scenario of a rule set that infers from empty has no open inputs. */
    @Test
    void an_ordinary_rule_set_has_no_open_inputs() {
        JsonNode results = Playground.analyze(OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "")
                .replace(" (volledig gegeven)", "") + SCENARIOS).at("/scenario/results");
        for (JsonNode s : results) assertFalse(s.has("openInputs"), s.toString());
    }

    /** The bergloon of 7 is input beside a writer that is open, so it is no row of the values. */
    @Test
    void the_fixpoint_lists_the_open_members() {
        for (JsonNode results : new JsonNode[]{ordinary, via}) {
            JsonNode o = results.at("/1/fixpoint/objects/0");
            assertTrue(o.get("open").toString().contains("{\"attribute\":\"bergloon\",\"input\":\"7\"}"), o.toString());
            assertTrue(o.get("open").toString().contains("{\"characteristic\":\"kostbaar\"}"), o.toString());
            assertEquals(0, o.get("values").size(), o.toString());
            assertEquals(0, o.get("characteristics").size(), o.toString());
        }
        JsonNode standing = ordinary.at("/0/fixpoint/objects/0");
        assertTrue(standing.get("values").toString().contains("\"attribute\":\"bergloon\",\"value\":\"7\""),
                standing.toString());
    }
}
