package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenarios of a rule set that infers nothing from an empty value ({@link OpenValuesSource}) in the playground, on the
 * ordinary route and with {@code via=service}: the verdict of a scenario that expects a value where the outcome is open is
 * FAIL, with uchar's word for it; the fixpoint lists what is open, with the input that does not stand; and there is no
 * explanation, with the reason. The situation of a scenario asserts a characteristic or leaves it out, so its request
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

    @Test
    void there_is_no_explanation_and_the_reason_is_given() {
        for (JsonNode results : new JsonNode[]{ordinary, via})
            for (JsonNode s : results) {
                assertEquals(0, s.get("explanation").size(), s.toString());
                assertTrue(s.get("explanationError").asText().contains("Afleiden uit leeg: nee"), s.toString());
            }
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
