package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundle;
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
 * says what keeps an outcome open, and {@code openInputs} what can be given. A scenario that expects an outcome to stay open
 * ("staat niet vast") passes where it is open. The situation of a scenario gives a characteristic as holding or as not
 * holding ("- is niet betwist"), so its request gives it as {@code holds} or {@code holdsNot}; in a rule set that is not
 * strict such a sentence changes nothing, and the playground shows uchar's warning among those of the resolver.
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

    private static final String BETWIST = """

        Scenario betwiste vondst
        toetst de regel premie
        Gegeven is de volgende situatie:
        een Vondst V1
        - is betwist
        Met als verwacht resultaat:
        - de premie van V1 is 0
        - V1 is betwist
        """;

    /** A characteristic that the situation asserts holds in the request of the service: betwist decides the premie. */
    @Test
    void a_characteristic_of_the_situation_holds_via_the_service() {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.SOURCE + BETWIST;
        for (JsonNode s : new JsonNode[]{Playground.analyze(source).at("/scenario/results/0"),
                Playground.analyze(source, null, core, null, false).at("/scenario/results/0")}) {
            assertEquals("PASS", s.at("/status").asText(), s.toString());
            assertTrue(s.get("explanation").toString().contains("V1 heeft als premie 0 volgens 'betwiste premie': 0, omdat het"
                    + " onderwerp betwist is"), s.toString());
        }
    }

    /** The request of that situation: the state of the characteristic, and in a rule set that is not strict its boolean. */
    @Test
    void the_request_of_a_situation_gives_a_characteristic_as_its_state() {
        String source = OpenValuesSource.SOURCE + BETWIST;
        for (String[] c : new String[][]{{source, "{\"betwist\":{\"state\":\"holds\"}}"},
                {source.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", ""), "{\"betwist\":true}"}}) {
            ScenarioBundle bundle = ScenarioResolver.resolve(c[0]);
            assertTrue(bundle.isOk(), () -> bundle.model().errors().toString());
            assertEquals(c[1], SituationToRequest.assemble(bundle.scenarios().get(0), bundle.model())
                    .at("/instances/0/characteristics").toString());
        }
    }

    private static final String NOT_BETWIST = """

        Scenario onbetwiste vondst
        toetst de regel premie
        Gegeven is de volgende situatie:
        een Vondst V1
        - is niet betwist
        Met als verwacht resultaat:
        - de premie van V1 is 10
        - V1 is niet betwist
        """;

    /** A characteristic the situation gives as not holding stands not to hold on both routes, so the base decides the premie;
     *  the situation carries it with {@code holds: false}, and the request as {@code holdsNot}, or {@code false} in a rule
     *  set that is not strict. */
    @Test
    void a_characteristic_the_situation_gives_as_not_holding() {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.SOURCE + NOT_BETWIST;
        for (JsonNode s : new JsonNode[]{Playground.analyze(source).at("/scenario/results/0"),
                Playground.analyze(source, null, core, null, false).at("/scenario/results/0")}) {
            assertEquals("PASS", s.at("/status").asText(), s.toString());
            assertEquals(false, s.at("/situation/objects/0/characteristics/0/holds").asBoolean(true), s.toString());
        }
        String ordinary = source.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        for (String[] c : new String[][]{{source, "{\"betwist\":{\"state\":\"holdsNot\"}}"}, {ordinary, "{\"betwist\":false}"}}) {
            ScenarioBundle bundle = ScenarioResolver.resolve(c[0]);
            assertTrue(bundle.isOk(), () -> bundle.model().errors().toString());
            assertEquals(c[1], SituationToRequest.assemble(bundle.scenarios().get(0), bundle.model())
                    .at("/instances/0/characteristics").toString());
        }
    }

    /** In a rule set that is not strict "- is niet betwist" changes nothing: the playground gives uchar's warning beside the
     *  diagnostics of the model, and none in a strict one. */
    @Test
    void a_characteristic_given_as_not_holding_in_an_ordinary_rule_set_is_a_warning() {
        String source = OpenValuesSource.SOURCE + NOT_BETWIST;
        JsonNode warnings = Playground.analyze(source.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", ""))
                .at("/resolver/warnings");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.at("/0/message").asText().contains("'is niet betwist' verandert in een document zonder 'Afleiden uit"
                + " leeg: nee' niets"), warnings.toString());
        assertEquals(ScenarioData.lines(source).indexOf("- is niet betwist") + 1, warnings.at("/0/line").asInt(),
                warnings.toString());
        assertEquals(0, Playground.analyze(source).at("/resolver/warnings").size());
    }

    private static final String EXPECTED_OPEN = """

        Scenario open verwacht
        toetst de regels kostbaarheid, vindloon en bergloon
        Gegeven is de volgende situatie:
        een Vondst V1
        - het bergloon is 7
        Met als verwacht resultaat:
        - het vindloon van V1 staat niet vast
        - het bergloon van V1 staat niet vast
        - of V1 kostbaar is, staat niet vast
        """;

    /** An expectation that an outcome stays open passes on both routes where it is open. */
    @Test
    void an_expectation_that_an_outcome_stays_open_passes() {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.SOURCE + EXPECTED_OPEN;
        for (JsonNode s : new JsonNode[]{Playground.analyze(source).at("/scenario/results/0"),
                Playground.analyze(source, null, core, null, false).at("/scenario/results/0")}) {
            assertEquals("PASS", s.at("/status").asText(), s.toString());
            assertEquals(3, s.get("expectations").size(), s.toString());
        }
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

    private static final String ASSIGNED = """

        Scenario kostbaar ondanks de invoer
        toetst de regel kostbaarheid
        Gegeven is de volgende situatie:
        een Vondst V1
        - de taxatie is 1500
        - is niet kostbaar
        Met als verwacht resultaat:
        - V1 is kostbaar
        """;

    /** The situation gives kostbaar as not holding, and its rule assigns it: on both routes the row of the fixpoint is derived,
     *  with the input it replaced beside it, which the page shows as "(invoer geldt niet)". */
    @Test
    void the_fixpoint_gives_beside_a_derived_characteristic_that_the_situation_gave_as_not_holding() {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.SOURCE + ASSIGNED;
        for (JsonNode s : new JsonNode[]{Playground.analyze(source).at("/scenario/results/0"),
                Playground.analyze(source, null, core, null, false).at("/scenario/results/0")}) {
            assertEquals("PASS", s.at("/status").asText(), s.toString());
            assertEquals("[{\"characteristic\":\"kostbaar\",\"origin\":\"derived\",\"input\":{\"state\":\"holdsNot\"}}]",
                    s.at("/fixpoint/objects/0/characteristics").toString(), s.toString());
        }
    }

    /** In a rule set that is not strict "- is niet kostbaar" changes nothing, also in the fixpoint: beside the derived kostbaar
     *  stands no input. */
    @Test
    void in_an_ordinary_rule_set_the_fixpoint_gives_no_input_beside_a_derived_characteristic_given_as_not_holding() {
        JsonNode s = Playground.analyze(OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "")
                .replace(" (volledig gegeven)", "") + ASSIGNED).at("/scenario/results/0");
        assertEquals("PASS", s.at("/status").asText(), s.toString());
        assertEquals("[{\"characteristic\":\"kostbaar\",\"origin\":\"derived\"}]",
                s.at("/fixpoint/objects/0/characteristics").toString(), s.toString());
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

    private static final String OPGAVE_RULES = """
        Afleiden uit leeg: nee

        Objecttype het Wrak (mv: Wrakken)
            de lengte Numeriek (geheel getal);
            het kadegeld Numeriek (geheel getal) (mag opgegeven worden);

        Regel kadegeld
            geldig altijd
                Het kadegeld van een Wrak moet berekend worden als zijn lengte maal 3.
        """;

    private static final String OPGAVE_SCENARIOS = """

        Scenario opgave gegeven zonder lengte
        Gegeven is de volgende situatie:
        een Wrak W1
        - het opgegeven kadegeld is 100
        Met als verwacht resultaat:
        - het kadegeld van W1 is 100
        - het berekende kadegeld van W1 staat niet vast
        - of W1 afwijkende opgave kadegeld heeft, staat niet vast

        Scenario opgave niet gegeven zonder lengte
        Gegeven is de volgende situatie:
        een Wrak W1
        Met als verwacht resultaat:
        - het kadegeld van W1 staat niet vast
        - het berekende kadegeld van W1 staat niet vast
        """;

    private static final String OPGAVE_DESCRIPTOR = """
        { "service": {"name":"opgave","version":"1"},
          "entrypoints": [
            { "name":"kadegeld","path":"kadegeld",
              "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "controle opgave kadegeld"]},
              "input": ["Wrak"],
              "output": ["Wrak.kadegeld", "Wrak.berekende kadegeld", "Wrak:afwijkende opgave kadegeld"] } ],
          "parameters": {} }""";

    /** On both routes of the playground, giving {@code opgegeven kadegeld} settles {@code kadegeld}: {@code openInputs} omits
     *  {@code lengte}, which only keeps the opgave check members open, while {@code explanation} still names both open check
     *  members. Without {@code opgegeven kadegeld}, {@code openInputs} reports {@code lengte} on both routes. */
    @Test
    void an_opgave_check_whose_marked_attribute_stands_is_omitted_from_open_inputs() throws Exception {
        DecisionCore core = DecisionCore.assemble(OPGAVE_DESCRIPTOR, java.util.Map.of("opgave.rgs", OPGAVE_RULES),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        String source = OPGAVE_RULES + OPGAVE_SCENARIOS;
        JsonNode expectedOpen = ServiceHttp.MAPPER.readTree("""
            [{"kind": "attribute", "instance": "W1", "name": "lengte", "forOutputs": [
                {"instance": "W1", "name": "kadegeld", "kind": "attribute"},
                {"instance": "W1", "name": "berekende kadegeld", "kind": "attribute"}]}]""");
        for (JsonNode results : new JsonNode[]{
                Playground.analyze(source).at("/scenario/results"),
                Playground.analyze(source, null, core, null, false).at("/scenario/results")}) {
            assertEquals("PASS", results.at("/0/status").asText(), results.toString());
            assertEquals(0, results.at("/0/openInputs").size(), results.at("/0").toString());
            String explanation = results.at("/0/explanation").toString();
            assertTrue(explanation.contains("W1: zijn berekende kadegeld staat niet vast, want zijn lengte is niet gegeven"),
                    explanation);
            assertTrue(explanation.contains("W1: of hij afwijkende opgave kadegeld heeft, staat niet vast, want zijn berekende"
                    + " kadegeld staat niet vast"), explanation);

            assertEquals("PASS", results.at("/1/status").asText(), results.toString());
            assertEquals(expectedOpen, results.at("/1/openInputs"), results.at("/1").toString());
        }
    }

    private static final String EMPTY_ATTRIBUTE_SCENARIOS = """

        Scenario herkomst leeg bij onleesbaarheid
        toetst de regels kostbaarheid, vindloon, bergloon en herkomst onleesbaar
        Gegeven is de volgende situatie:
        een Vondst V1
        - de taxatie is leeg
        - de herkomst is leeg
        - is onleesbaar
        Met als verwacht resultaat:
        - de taxatie van V1 is leeg
        - het vindloon van V1 is 10
        - het bergloon van V1 is leeg
        - de herkomst van V1 is 0
        - V1 is niet kostbaar

        Scenario bergloon leeg naast open schrijver
        toetst de regels kostbaarheid, vindloon, bergloon en herkomst onleesbaar
        Gegeven is de volgende situatie:
        een Vondst V1
        - het bergloon is leeg
        Met als verwacht resultaat:
        - het bergloon van V1 staat niet vast
        """;

    /** A situation with {@code - de X is leeg} passes on both routes, omits {@code X} from {@code openInputs}, writes
     *  {@code {"state": "empty"}} in {@code SituationToRequest} of a strict rule set and leaves it out in an ordinary one,
     *  records {@code "input": {"state": "empty"}} in {@code fixpoint} on derived and open rows of a strict rule set (and no
     *  {@code input} in an ordinary rule set, where uchar's RGS-473 warning appears), and {@code playground.html} renders
     *  {@code (invoer leeg)} with valid inline JavaScript. */
    @Test
    void an_attribute_the_situation_states_to_be_leeg() throws Exception {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.SOURCE + EMPTY_ATTRIBUTE_SCENARIOS;
        for (JsonNode analysis : new JsonNode[]{
                Playground.analyze(source),
                Playground.analyze(source, null, core, null, false)}) {
            JsonNode results = analysis.at("/scenario/results");
            assertEquals(2, results.size(), analysis::toString);
            JsonNode first = results.get(0);
            assertEquals("PASS", first.get("status").asText(), first.toString());
            assertTrue(first.at("/situation/objects/0/attributes/0/value").isNull(), first.toString());
            assertEquals("{\"attribute\":\"herkomst\",\"value\":\"0\",\"origin\":\"derived\",\"input\":{\"state\":\"empty\"}}",
                    first.at("/fixpoint/objects/0/values/0").toString(), first.toString());
            for (JsonNode in : first.get("openInputs"))
                assertFalse(in.get("name").asText().equals("taxatie"), first.get("openInputs").toString());

            JsonNode second = results.get(1);
            assertEquals("PASS", second.get("status").asText(), second.toString());
            assertTrue(second.at("/fixpoint/objects/0/open").toString()
                    .contains("{\"attribute\":\"bergloon\",\"input\":{\"state\":\"empty\"}}"), second.toString());
        }

        // SituationToRequest: {"state": "empty"} in strict mode, left out in ordinary mode
        ScenarioBundle strictBundle = ScenarioResolver.resolve(source);
        assertTrue(strictBundle.isOk());
        assertEquals("{\"taxatie\":{\"state\":\"empty\"},\"herkomst\":{\"state\":\"empty\"}}",
                SituationToRequest.assemble(strictBundle.scenarios().get(0), strictBundle.model())
                        .at("/instances/0/attributes").toString());

        String ordinarySource = (OpenValuesSource.SOURCE + """

            Scenario herkomst leeg in gewone regelset
            toetst de regel herkomst onleesbaar
            Gegeven is de volgende situatie:
            een Vondst V1
            - de herkomst is leeg
            - is onleesbaar
            Met als verwacht resultaat:
            - de herkomst van V1 is 0
            """).replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        ScenarioBundle ordinaryBundle = ScenarioResolver.resolve(ordinarySource);
        assertTrue(ordinaryBundle.isOk());
        assertTrue(SituationToRequest.assemble(ordinaryBundle.scenarios().get(0), ordinaryBundle.model())
                .at("/instances/0/attributes").isMissingNode());
        JsonNode ordinaryAnalysis = Playground.analyze(ordinarySource);
        assertEquals("PASS", ordinaryAnalysis.at("/scenario/results/0/status").asText(), ordinaryAnalysis.toString());
        assertEquals("[{\"attribute\":\"herkomst\",\"value\":\"0\",\"origin\":\"derived\"}]",
                ordinaryAnalysis.at("/scenario/results/0/fixpoint/objects/0/values").toString(), ordinaryAnalysis.toString());
        assertEquals(1, ordinaryAnalysis.at("/resolver/warnings").size(), ordinaryAnalysis.toString());
        assertTrue(ordinaryAnalysis.at("/resolver/warnings/0/message").asText().contains("herkomst"),
                ordinaryAnalysis.at("/resolver/warnings").toString());

        // playground.html renders "(invoer leeg)" for input.state === "empty" on values and open, and omits unit on null
        String html;
        try (var in = Playground.class.getResourceAsStream("playground.html")) {
            html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(html.contains("w.input && w.input.state === \"empty\" ? \"leeg\""), "values row mist (invoer leeg)");
        assertTrue(html.contains("v.input && v.input.state === \"empty\" ? \"leeg\""), "open row mist (invoer leeg)");
        assertTrue(html.contains("o.unit && (o.timeline || o.cells || o.value != null)"), "valueHtml plakt unit achter leeg");
        assertTrue(html.contains("r.labels.length ?"), "situationHtml mist lege rolweergave");
        assertTrue(html.contains("\"characteristic\" in v ? \"characteristic\" : \"role\""), "fixpointHtml mist open rol");
    }

    @Test
    void a_to_one_role_the_situation_states_to_be_leeg() throws Exception {
        DecisionCore wrakCore = OpenValuesSource.wrak(CoreConfig.strict().withDetail());
        String source = OpenValuesSource.WRAK_SOURCE + """

            Scenario wrak zonder vinder en met lege vinder
            toetst de regel vindpremie
            Gegeven is de volgende situatie:
            een Wrak W1
            - de lengte is 30
            een Wrak W2
            - de lengte is 30
            - de vinder is leeg
            Met als verwacht resultaat:
            - de vindpremie van W1 staat niet vast
            - de vindpremie van W2 is 30
            """;
        for (JsonNode analysis : new JsonNode[]{
                Playground.analyze(source),
                Playground.analyze(source, null, wrakCore, null, false)}) {
            JsonNode result = analysis.at("/scenario/results/0");
            assertEquals("PASS", result.get("status").asText(), result.toString());
            JsonNode w2Roles = result.at("/situation/objects/1/roles");
            assertEquals(1, w2Roles.size(), w2Roles.toString());
            assertEquals("vinder", w2Roles.at("/0/role").asText());
            assertEquals("wrakvondst", w2Roles.at("/0/factType").asText());
            assertEquals("Jutter", w2Roles.at("/0/targetType").asText());
            assertEquals(0, w2Roles.at("/0/labels").size());
            assertFalse(w2Roles.at("/0/multiple").asBoolean());
            assertTrue(result.at("/fixpoint/objects/0/open").toString().contains("{\"role\":\"vinder\"}"),
                    result.at("/fixpoint/objects/0/open").toString());
            assertFalse(result.at("/fixpoint/objects/1/open").toString().contains("{\"role\":\"vinder\"}"),
                    result.at("/fixpoint/objects/1/open").toString());
            assertEquals(ServiceHttp.MAPPER.readTree("""
                [{"kind": "role", "instance": "W1", "name": "vinder", "forOutputs": [
                    {"instance": "W1", "name": "vindpremie", "kind": "attribute"}]}]"""),
                    forOutput(result.get("openInputs"), "W1", "vindpremie"));
        }

        ScenarioBundle strictBundle = ScenarioResolver.resolve(source);
        assertTrue(strictBundle.isOk());
        JsonNode strictReq = SituationToRequest.assemble(strictBundle.scenarios().get(0), strictBundle.model());
        assertTrue(strictReq.at("/instances/0/roles").isMissingNode());
        assertEquals("{\"vinder\":{\"state\":\"empty\"}}", strictReq.at("/instances/1/roles").toString());

        String ordinarySource = (OpenValuesSource.WRAK_SOURCE + """

            Scenario wrak met lege vinder in gewone regelset
            toetst de regel vindpremie
            Gegeven is de volgende situatie:
            een Wrak W2
            - de lengte is 30
            - de vinder is leeg
            Met als verwacht resultaat:
            - de vindpremie van W2 is 30
            """).replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        ScenarioBundle ordinaryBundle = ScenarioResolver.resolve(ordinarySource);
        assertTrue(ordinaryBundle.isOk());
        assertTrue(SituationToRequest.assemble(ordinaryBundle.scenarios().get(0), ordinaryBundle.model())
                .at("/instances/0/roles").isMissingNode());
        JsonNode ordinaryAnalysis = Playground.analyze(ordinarySource);
        assertEquals("PASS", ordinaryAnalysis.at("/scenario/results/0/status").asText(), ordinaryAnalysis.toString());
        assertEquals(1, ordinaryAnalysis.at("/resolver/warnings").size(), ordinaryAnalysis.toString());
        assertTrue(ordinaryAnalysis.at("/resolver/warnings/0/message").asText().contains("vinder"),
                ordinaryAnalysis.at("/resolver/warnings").toString());
    }

    private static JsonNode forOutput(JsonNode inputs, String instance, String output) {
        var out = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode i : inputs) {
            var filtered = ServiceHttp.MAPPER.createArrayNode();
            for (JsonNode o : i.get("forOutputs"))
                if (o.get("instance").asText().equals(instance) && o.get("name").asText().equals(output)) filtered.add(o);
            if (!filtered.isEmpty()) {
                var copy = (com.fasterxml.jackson.databind.node.ObjectNode) i.deepCopy();
                copy.set("forOutputs", filtered);
                out.add(copy);
            }
        }
        return out;
    }
}
