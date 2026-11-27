package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ?openInputs=true} of a strict rule set ({@link OpenValuesSource}): the input members that keep an open outcome of
 * {@code output} open now, each once, with the outcomes it keeps open. The lists are written by hand from the rules.
 */
class OpenInputsHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static ServiceHttp service;
    private static ServiceHttp wrakken;

    @BeforeAll
    static void start() throws Exception {
        service = ServiceHttp.bind(OpenValuesSource.core(CoreConfig.strict()), "127.0.0.1", 0).start();
        wrakken = ServiceHttp.bind(OpenValuesSource.wrak(CoreConfig.strict()), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (ServiceHttp s : new ServiceHttp[]{service, wrakken}) if (s != null) s.stop(0);
    }

    /** vindloon, uitbetaling and bergloon read the taxatie, and bewakingstoeslag the characteristic kostbaar that a rule
     *  derives from it; taxatie and betwist are in {@code output} themselves. One member for many outcomes stands once. */
    @Test
    void an_outcome_open_by_an_input_and_by_a_derived_member() throws Exception {
        assertEquals(json("""
            [{"kind": "attribute", "instance": "V1", "name": "taxatie", "forOutputs": [
                {"instance": "V1", "name": "taxatie", "kind": "attribute"},
                {"instance": "V1", "name": "vindloon", "kind": "attribute"},
                {"instance": "V1", "name": "uitbetaling", "kind": "attribute"},
                {"instance": "V1", "name": "bewakingstoeslag", "kind": "attribute"},
                {"instance": "V1", "name": "bergloon", "kind": "attribute"},
                {"instance": "V1", "name": "kostbaar", "kind": "characteristic"}]},
             {"kind": "characteristic", "instance": "V1", "name": "betwist", "forOutputs": [
                {"instance": "V1", "name": "premie", "kind": "attribute"},
                {"instance": "V1", "name": "betwist", "kind": "characteristic"}]}]"""),
                open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}"));
    }

    /** With the taxatie given only betwist is left; with both given nothing is, and the list is there and empty. */
    @Test
    void what_is_given_leaves_the_list() throws Exception {
        JsonNode left = open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {\"taxatie\":"
                + " 50}}]}");
        assertEquals(json("[\"betwist\"]"), names(left));
        assertEquals(json("[]"), open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\":"
                + " {\"taxatie\": 50}, \"characteristics\": {\"betwist\": {\"state\": \"holdsNot\"}}}]}"));
        // Settled leeg on taxatie also leaves openInputs, and settles gepeild/ongepeild on a wreck
        assertEquals(json("[\"betwist\"]"), names(open("vondst", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\","
                + " \"attributes\": {\"taxatie\": {\"state\": \"empty\"}}}]}")));
        JsonNode emptyDepth = wrak("\"attributes\": {\"diepte\": {\"state\": \"empty\"}}");
        assertEquals(json("[]"), namesFor(emptyDepth, "W1", "gepeild"));
        assertEquals(json("[]"), namesFor(emptyDepth, "W1", "ongepeild"));
        assertFalse(names(emptyDepth).toString().contains("\"diepte\""), emptyDepth.toString());
    }

    /** wrakloon reads the ladingwaarde and then the lengte: without a lading it stops at the ladingwaarde, which the lading
     *  keeps open; once the lading is given, the lengte is the next question. */
    @Test
    void the_next_question_after_the_first_answer() throws Exception {
        assertEquals(json("[\"lading\"]"), namesFor(wrak(""), "W1", "wrakloon"));
        assertEquals(json("[\"lengte\"]"), namesFor(wrak("\"attributes\": {\"lading\": 5}"), "W1", "wrakloon"));
        assertEquals(json("[]"), namesFor(wrak("\"attributes\": {\"lading\": 5, \"lengte\": 3}"), "W1", "wrakloon"));
    }

    /** The sum over three wrecks breaks off at the first without a lading, W2; given that, at W3. */
    @Test
    void a_fold_over_three_objects() throws Exception {
        assertEquals(json("[\"W2 lading\"]"), membersFor(jutter("\"lading\": 5", "", ""), "J1", "ladingsom"));
        assertEquals(json("[\"W3 lading\"]"), membersFor(jutter("\"lading\": 5", "\"lading\": 7", ""), "J1", "ladingsom"));
        assertEquals(json("[]"), membersFor(jutter("\"lading\": 5", "\"lading\": 7", "\"lading\": 9"), "J1", "ladingsom"));
    }

    /** A composite is read past its first undetermined member, so all of its open members are named; one that is decided
     *  has none. */
    @Test
    void a_composite_names_every_member_that_is_undetermined() throws Exception {
        assertEquals(json("[\"diepte\", \"lengte\"]"), namesFor(wrak("\"attributes\": {\"lading\": 5}"), "W1", "gevaarlijk"));
        assertEquals(json("[\"diepte\"]"), namesFor(wrak("\"attributes\": {\"lading\": 5, \"lengte\": 5}"), "W1", "gevaarlijk"));
        assertEquals(json("[]"), namesFor(wrak("\"attributes\": {\"lengte\": 30}"), "W1", "gevaarlijk"));
        // an open constant default lets the next default read provisionally: gemerkt=holds with lading=0 would decide 0
        assertEquals(json("[\"betwist\", \"gemerkt\"]"), namesFor(wrak(""), "W1", "wrakpremie"));
        // two exceptions without an order between them, both open
        assertEquals(json("[\"betwist\", \"beschadigd\"]"), namesFor(wrak("\"attributes\": {\"lengte\": 30}"), "W1",
                "bergingsloon"));
    }

    /** gepeild and ongepeild are open by the test on gevuld and on leeg over the diepte; volledig gepeild, the aantal gepeilde
     *  wrakken and the gepeilde ladingsom by their fold, which breaks off at the diepte of the first wreck without one. */
    @Test
    void an_outcome_that_a_test_on_gevuld_or_a_fold_leaves_open() throws Exception {
        assertEquals(json("[\"diepte\"]"), namesFor(wrak(""), "W1", "gepeild"));
        assertEquals(json("[\"diepte\"]"), namesFor(wrak(""), "W1", "ongepeild"));
        JsonNode open = jutter("\"lading\": 5", "\"lading\": 7, \"diepte\": 3", "");
        assertEquals(json("[\"W1 diepte\"]"), membersFor(open, "J1", "volledig gepeild"));
        assertEquals(json("[\"W1 diepte\"]"), membersFor(open, "J1", "aantal gepeilde wrakken"));
        assertEquals(json("[\"W1 diepte\"]"), membersFor(jutter("\"lading\": 5", "", ""), "J1", "gepeilde ladingsom"));
    }

    /** A read over a role: when vinder is not given, vindpremie asks the role vinder on W1; when vinder is settled empty,
     *  vindpremie stands at 30; when vinder links to J1 without a bonus, vindpremie asks J1's bonus. */
    @Test
    void a_member_of_another_instance() throws Exception {
        assertEquals(json("""
            [{"kind": "role", "instance": "W1", "factType": "wrakvondst", "name": "vinder", "forOutputs": [
                {"instance": "W1", "name": "vindpremie", "kind": "attribute"}]}]"""),
                forOutput(wrak("\"attributes\": {\"lengte\": 30}"), "W1", "vindpremie"));
        assertEquals(json("[]"), forOutput(wrak("\"attributes\": {\"lengte\": 30}, \"roles\": {\"vinder\": {\"state\": \"empty\"}}"),
                "W1", "vindpremie"));
        assertEquals(json("[\"J1 bonus\"]"), membersFor(open("wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": {"state": "closed", "items": ["W1"]}}},
                           {"type": "Wrak", "id": "W1", "attributes": {"lengte": 30}}]}"""), "W1", "vindpremie"));
    }

    @Test
    void an_open_to_many_role_is_reported_with_fact_type_and_name() throws Exception {
        for (String request : List.of(
                """
                {"instances": [{"type": "Jutter", "id": "J1"},
                               {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 30},
                                "roles": {"vinder": {"state": "empty"}},
                                "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holdsNot"},
                                                    "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holds"}}}]}""",
                """
                {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": {"state": "open", "items": ["W1"]}}},
                               {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5},
                                "characteristics": {"gemerkt": {"state": "holds"}}}]}""")) {
            JsonNode inputs = open("wrak", request);
            assertEquals(json("""
                [{"kind": "role", "instance": "J1", "factType": "wrakvondst", "name": "gevonden wrak", "forOutputs": [
                    {"instance": "J1", "name": "ladingsom", "kind": "attribute"}]}]"""),
                    forOutput(inputs, "J1", "ladingsom"));
            assertEquals(json("""
                [{"kind": "role", "instance": "J1", "factType": "wrakvondst", "name": "gevonden wrak", "forOutputs": [
                    {"instance": "J1", "name": "kundig", "kind": "characteristic"}]}]"""),
                    forOutput(inputs, "J1", "kundig"));
        }
        assertEquals(json("[]"), open("wrak", """
                {"instances": [{"type": "Jutter", "id": "J1",
                                "roles": {"gevonden wrakken": {"state": "empty"}}, "attributes": {"bonus": 4}},
                               {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 30},
                                "roles": {"vinder": {"state": "empty"}},
                                "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holdsNot"},
                                                    "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holdsNot"}}}]}"""));
    }

    /** It stands beside the explanation and without it, and the answer is the same otherwise. */
    @Test
    void the_answer_is_the_same_beside_it() throws Exception {
        String request = "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}";
        JsonNode plain = ok(post("wrak", request));
        JsonNode with = ok(post("wrak?openInputs=true", request));
        JsonNode both = ok(post("wrak?openInputs=true&explanation=true", request));
        assertEquals(plain, ((ObjectNode) with.deepCopy()).without(List.of("openInputs")));
        assertEquals(with.get("openInputs"), both.get("openInputs"));
        assertEquals(plain, ((ObjectNode) both.deepCopy()).without(List.of("openInputs", "explanation")));
        assertFalse(plain.has("openInputs"));
    }

    @Test
    void the_openapi_document_has_the_flag_and_the_field() throws Exception {
        JsonNode doc = json(get("openapi"));
        assertTrue(doc.at("/paths/~1vondst/post/parameters").toString().contains("\"name\":\"openInputs\""));
        JsonNode item = doc.at("/components/schemas/Answer/properties/openInputs/items/properties");
        assertEquals(json("[\"attribute\", \"characteristic\", \"role\"]"), item.at("/kind/enum"));
        assertEquals("string", item.at("/factType/type").asText());
        assertEquals(json("[\"attribute\", \"characteristic\"]"), item.at("/forOutputs/items/properties/kind/enum"));
        assertTrue(item.at("/forOutputs/items/properties/factType").isMissingNode());
        JsonNode part = doc.at("/components/schemas/ExplanationPart/properties");
        assertEquals("boolean", part.at("/undetermined/type").asText());
        assertTrue(part.at("/closure").isMissingNode(), part.toString());
        assertEquals("integer", part.at("/unsettled/type").asText());
        assertEquals("integer", part.at("/filterUnsettled/type").asText());
        assertTrue(part.at("/open/properties").has("canonical"));
        assertEquals(json("[\"attribute\", \"characteristic\", \"role\"]"), part.at("/open/properties/kind/enum"));
    }

    // ---------------- opgave check members ----------------

    private static final String OPGAVE_SOURCE = """
        Afleiden uit leeg: nee

        Vocabulaire havenboek (taal: nl)

        Objecttype het Vaartuig (mv: Vaartuigen)
            de lengte (havenboek: scheepslengte) Numeriek (geheel getal);
            het kadegeld (havenboek: liggeld) Numeriek (geheel getal) (mag opgegeven worden);
            de kadeboete Numeriek (geheel getal);
            de berekende kadeboete Numeriek (geheel getal);

        Objecttype het Wrak is een Vaartuig (mv: Wrakken)
            de naverheffing (havenboek: naheffing) Numeriek (geheel getal);
            is herkeuringsplichtig kenmerk (bijvoeglijk);

        Regel kadegeld
            geldig altijd
                Het kadegeld van een Vaartuig moet berekend worden als zijn lengte maal 3.

        Regel berekende kadeboete
            geldig altijd
                De berekende kadeboete van een Vaartuig moet berekend worden als zijn lengte maal 2.

        Regel naverheffing
            geldig altijd
                De naverheffing van een Wrak moet berekend worden als zijn berekende kadegeld plus 10.

        Regel herkeuringsplicht
            geldig altijd
                Een Wrak is herkeuringsplichtig indien hij afwijkende opgave kadegeld heeft.
        """;

    private static final String OPGAVE_DESCRIPTOR = """
        { "service": {"name":"opgave-controle","version":"1"},
          "entrypoints": [
            { "name":"vaartuig","path":"vaartuig",
              "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "controle opgave kadegeld"]},
              "input": ["Vaartuig"],
              "output": ["Vaartuig.kadegeld", "Vaartuig.berekende kadegeld",
                         "Vaartuig:afwijkende opgave kadegeld"] },
            { "name":"subtype","path":"subtype",
              "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "controle opgave kadegeld"]},
              "input": ["Wrak"],
              "output": ["Wrak.kadegeld", "Wrak.berekende kadegeld", "Wrak:afwijkende opgave kadegeld"] },
            { "name":"zonder-doel","path":"zonder-doel",
              "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "controle opgave kadegeld"]},
              "input": ["Vaartuig"],
              "output": ["Vaartuig.berekende kadegeld", "Vaartuig:afwijkende opgave kadegeld"] },
            { "name":"doorwerking-attribuut","path":"doorwerking-attribuut",
              "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "controle opgave kadegeld", "naverheffing"]},
              "input": ["Wrak"],
              "output": ["Wrak.kadegeld", "Wrak.berekende kadegeld", "Wrak:afwijkende opgave kadegeld",
                         "Wrak.naverheffing"] },
            { "name":"doorwerking-kenmerk","path":"doorwerking-kenmerk",
              "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "controle opgave kadegeld", "herkeuringsplicht",
                                      "berekende kadeboete"]},
              "input": ["Wrak"],
              "output": ["Wrak.kadegeld", "Wrak.berekende kadegeld", "Wrak:afwijkende opgave kadegeld",
                         "Wrak:herkeuringsplichtig", "Wrak.kadeboete", "Wrak.berekende kadeboete"] } ],
          "parameters": {} }""";

    /** Cases 1, 2 and 3 on both the RegelSpraak source and the IR document: once {@code opgegeven kadegeld} settles
     *  {@code kadegeld}, {@code berekende kadegeld} and {@code afwijkende opgave kadegeld} stay {@code open} in
     *  {@code instances} and keep their open sentences in {@code explanation}, while {@code openInputs} omits {@code lengte};
     *  without {@code opgegeven kadegeld} it asks {@code lengte}; with a deviating {@code lengte} the consistency stands. */
    @Test
    void opgave_check_members_when_the_marked_attribute_stands_or_is_open() throws Exception {
        String wholeScopeDescriptor = """
            { "service": {"name":"opgave-controle","version":"1"},
              "entrypoints": [
                { "name":"vaartuig","path":"vaartuig",
                  "scope": {"ruleNames": ["kadegeld", "opgave boven berekening kadegeld",
                                          "afwijking opgave kadegeld", "controle opgave kadegeld",
                                          "berekende kadeboete", "naverheffing", "herkeuringsplicht"]},
                  "input": ["Vaartuig"],
                  "output": ["Vaartuig.kadegeld", "Vaartuig.berekende kadegeld",
                             "Vaartuig:afwijkende opgave kadegeld"] } ],
              "parameters": {} }""";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String document = dev.uchar.regelspraak.toolchain.engine.UcharBuild.document(
                dev.uchar.regelspraak.resolver.Resolver.resolve(OPGAVE_SOURCE));
        for (DecisionCore core : List.of(
                DecisionCore.assemble(OPGAVE_DESCRIPTOR, Map.of("opgave.rgs", OPGAVE_SOURCE), CoreConfig.strict(), cl),
                DecisionCore.buildFromDocument(wholeScopeDescriptor, document, CoreConfig.strict(), cl, null))) {
            ServiceHttp opgave = ServiceHttp.bind(core, "127.0.0.1", 0).start();
            try {
                // 1. opgave given, computation open
                JsonNode settled = ok(post(opgave, "vaartuig?openInputs=true&explanation=true",
                        "{\"instances\": [{\"type\": \"Vaartuig\", \"id\": \"W1\", \"attributes\": {\"opgegeven kadegeld\": 100}}]}"));
                assertEquals(json("{\"state\": \"filled\", \"value\": 100}"),
                        settled.at("/instances/0/attributes/kadegeld"));
                assertEquals(json("{\"state\": \"open\"}"),
                        settled.at("/instances/0/attributes/berekende kadegeld"));
                assertEquals(json("{\"state\": \"open\"}"),
                        settled.at("/instances/0/characteristics/afwijkende opgave kadegeld"));
                assertEquals(json("[]"), settled.get("inconsistencies"));
                String explanation = settled.get("explanation").toString();
                assertTrue(explanation.contains("W1: zijn berekende kadegeld staat niet vast, want zijn lengte is niet gegeven"),
                        explanation);
                assertTrue(explanation.contains("W1: of hij afwijkende opgave kadegeld heeft, staat niet vast, want zijn"
                        + " berekende kadegeld staat niet vast"), explanation);
                assertEquals(json("[]"), settled.get("openInputs"));

                // 2. opgave not given, computation open
                JsonNode ungiven = ok(post(opgave, "vaartuig?openInputs=true",
                        "{\"instances\": [{\"type\": \"Vaartuig\", \"id\": \"W1\"}]}"));
                assertEquals(json("{\"state\": \"open\"}"), ungiven.at("/instances/0/attributes/kadegeld"));
                assertEquals(json("{\"state\": \"open\"}"), ungiven.at("/instances/0/attributes/berekende kadegeld"));
                assertEquals(json("{\"state\": \"holdsNot\"}"),
                        ungiven.at("/instances/0/characteristics/afwijkende opgave kadegeld"));
                assertEquals(json("""
                    [{"kind": "attribute", "instance": "W1", "name": "lengte", "forOutputs": [
                        {"instance": "W1", "name": "kadegeld", "kind": "attribute"},
                        {"instance": "W1", "name": "berekende kadegeld", "kind": "attribute"}]}]"""),
                        ungiven.get("openInputs"));

                // 3. opgave given, computation settled and deviating
                JsonNode deviating = ok(post(opgave, "vaartuig?openInputs=true",
                        "{\"instances\": [{\"type\": \"Vaartuig\", \"id\": \"W1\", \"attributes\":"
                                + " {\"opgegeven kadegeld\": 100, \"lengte\": 10}}]}"));
                assertEquals(json("{\"state\": \"filled\", \"value\": 100}"),
                        deviating.at("/instances/0/attributes/kadegeld"));
                assertEquals(json("{\"state\": \"filled\", \"value\": 30}"),
                        deviating.at("/instances/0/attributes/berekende kadegeld"));
                assertEquals(json("{\"state\": \"holds\"}"),
                        deviating.at("/instances/0/characteristics/afwijkende opgave kadegeld"));
                assertEquals(json("[\"controle opgave kadegeld\"]"), deviating.get("inconsistencies"));
                assertEquals(json("[]"), deviating.get("openInputs"));
            } finally {
                opgave.stop(0);
            }
        }
    }

    /** Cases 4, 5 and 6: an inherited marked attribute on a subtype, non-generated outcomes that read {@code berekende X} or
     *  {@code afwijkende opgave X} (plus an author-declared {@code berekende kadeboete} without {@code generatedFrom}), and an
     *  entry point without {@code X} in {@code output}. */
    @Test
    void opgave_checks_on_subtypes_through_genuine_outcomes_and_without_target_in_output() throws Exception {
        ServiceHttp opgave = ServiceHttp.bind(DecisionCore.assemble(OPGAVE_DESCRIPTOR, Map.of("opgave.rgs", OPGAVE_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            // 4. inherited marked attribute on subtype Wrak
            JsonNode subtypeSettled = ok(post(opgave, "subtype?openInputs=true",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"opgegeven kadegeld\": 100}}]}"));
            assertEquals(json("{\"state\": \"filled\", \"value\": 100}"),
                    subtypeSettled.at("/instances/0/attributes/kadegeld"));
            assertEquals(json("{\"state\": \"open\"}"),
                    subtypeSettled.at("/instances/0/attributes/berekende kadegeld"));
            assertEquals(json("{\"state\": \"open\"}"),
                    subtypeSettled.at("/instances/0/characteristics/afwijkende opgave kadegeld"));
            assertEquals(json("[]"), subtypeSettled.get("openInputs"));

            JsonNode subtypeOpen = ok(post(opgave, "subtype?openInputs=true",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}"));
            assertEquals(json("[\"W1 lengte\"]"), membersFor(subtypeOpen.get("openInputs"), "W1", "kadegeld"));
            assertEquals(json("[\"W1 lengte\"]"), membersFor(subtypeOpen.get("openInputs"), "W1", "berekende kadegeld"));

            // 5a. genuine outcome reading berekende kadegeld (canonical and in a vocabulary)
            JsonNode viaComputed = ok(post(opgave, "doorwerking-attribuut?openInputs=true",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"opgegeven kadegeld\": 100}}]}"));
            assertEquals(json("{\"state\": \"open\"}"), viaComputed.at("/instances/0/attributes/naverheffing"));
            assertEquals(json("""
                [{"kind": "attribute", "instance": "W1", "name": "lengte", "forOutputs": [
                    {"instance": "W1", "name": "naverheffing", "kind": "attribute"}]}]"""),
                    viaComputed.get("openInputs"));
            JsonNode viaComputedVocab = ok(post(opgave, "doorwerking-attribuut?openInputs=true&vocabulary=havenboek",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"opgegeven kadegeld\": 100}}]}"));
            assertEquals(json("""
                [{"kind": "attribute", "instance": "W1", "name": "scheepslengte", "forOutputs": [
                    {"instance": "W1", "name": "naheffing", "kind": "attribute"}]}]"""),
                    viaComputedVocab.get("openInputs"));
            JsonNode openVocab = ok(post(opgave, "vaartuig?openInputs=true&vocabulary=havenboek",
                    "{\"instances\": [{\"type\": \"Vaartuig\", \"id\": \"W1\"}]}"));
            assertEquals(json("""
                [{"kind": "attribute", "instance": "W1", "name": "scheepslengte", "forOutputs": [
                    {"instance": "W1", "name": "liggeld", "kind": "attribute"},
                    {"instance": "W1", "name": "berekende liggeld", "kind": "attribute"}]}]"""),
                    openVocab.get("openInputs"));

            // 5b. genuine outcome reading afwijkende opgave kadegeld, and author-declared 'berekende kadeboete'
            JsonNode viaDeviating = ok(post(opgave, "doorwerking-kenmerk?openInputs=true",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\":"
                            + " {\"opgegeven kadegeld\": 100, \"kadeboete\": 50}}]}"));
            assertEquals(json("{\"state\": \"open\"}"),
                    viaDeviating.at("/instances/0/characteristics/herkeuringsplichtig"));
            assertEquals(json("{\"state\": \"filled\", \"value\": 50}"),
                    viaDeviating.at("/instances/0/attributes/kadeboete"));
            assertEquals(json("{\"state\": \"open\"}"),
                    viaDeviating.at("/instances/0/attributes/berekende kadeboete"));
            assertEquals(json("""
                [{"kind": "attribute", "instance": "W1", "name": "lengte", "forOutputs": [
                    {"instance": "W1", "name": "herkeuringsplichtig", "kind": "characteristic"},
                    {"instance": "W1", "name": "berekende kadeboete", "kind": "attribute"}]}]"""),
                    viaDeviating.get("openInputs"));

            // 6. X not in output, berekende X and afwijkende opgave X in output
            JsonNode withoutTargetOpen = ok(post(opgave, "zonder-doel?openInputs=true",
                    "{\"instances\": [{\"type\": \"Vaartuig\", \"id\": \"W1\"}]}"));
            assertFalse(withoutTargetOpen.at("/instances/0/attributes").has("kadegeld"));
            assertEquals(json("""
                [{"kind": "attribute", "instance": "W1", "name": "lengte", "forOutputs": [
                    {"instance": "W1", "name": "berekende kadegeld", "kind": "attribute"}]}]"""),
                    withoutTargetOpen.get("openInputs"));

            JsonNode withoutTargetSettled = ok(post(opgave, "zonder-doel?openInputs=true",
                    "{\"instances\": [{\"type\": \"Vaartuig\", \"id\": \"W1\", \"attributes\": {\"opgegeven kadegeld\": 100}}]}"));
            assertFalse(withoutTargetSettled.at("/instances/0/attributes").has("kadegeld"));
            assertEquals(json("{\"state\": \"open\"}"),
                    withoutTargetSettled.at("/instances/0/attributes/berekende kadegeld"));
            assertEquals(json("{\"state\": \"open\"}"),
                    withoutTargetSettled.at("/instances/0/characteristics/afwijkende opgave kadegeld"));
            assertEquals(json("[]"), withoutTargetSettled.get("openInputs"));
        } finally {
            opgave.stop(0);
        }
    }

    /** In an IR document whose {@code origin.from} names an attribute of an unrelated object type (either absent on the
     *  subject's chain, or shadowed by an own same-named settled attribute), {@code settledOpgaveCheck} does not suppress the
     *  open outcome. */
    @Test
    void an_ir_origin_naming_an_attribute_not_on_the_instance_chain_is_not_suppressed() throws Exception {
        String source = """
            Afleiden uit leeg: nee

            Objecttype het Vaartuig (mv: Vaartuigen)
                het kadegeld Numeriek (geheel getal);

            Objecttype de Boei (mv: Boeien)
                de hoogte Numeriek (geheel getal);
                het kadegeld Numeriek (geheel getal);
                de boeitaks Numeriek (geheel getal);

            Regel boeitaks
                geldig altijd
                    De boeitaks van een Boei moet berekend worden als zijn hoogte maal 2.
            """;
        String descriptor = """
            { "service": {"name":"vreemde-oorsprong","version":"1"},
              "entrypoints": [
                { "name":"boei","path":"boei",
                  "scope": {"ruleNames": ["boeitaks"]},
                  "input": ["Boei"],
                  "output": ["Boei.boeitaks"] } ],
              "parameters": {} }""";
        ObjectNode base = (ObjectNode) json(dev.uchar.regelspraak.toolchain.engine.UcharBuild.document(
                dev.uchar.regelspraak.resolver.Resolver.resolve(source)));
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String foreignAttr : List.of("onbestaand", "kadegeld")) {
            ObjectNode doc = base.deepCopy();
            for (JsonNode ot : doc.at("/model/objectTypes"))
                if (ot.get("name").asText().equals("Boei"))
                    for (JsonNode a : ot.get("attributes"))
                        if (a.get("name").asText().equals("boeitaks")) {
                            ObjectNode origin = ((ObjectNode) a).putObject("origin");
                            origin.put("form", "inputAboveComputation");
                            origin.putObject("from").put("kind", "attribute").put("owner", "Vaartuig").put("name", foreignAttr);
                        }
            ServiceHttp foreign = ServiceHttp.bind(
                    DecisionCore.buildFromDocument(descriptor, doc.toString(), CoreConfig.strict(), cl, null),
                    "127.0.0.1", 0).start();
            try {
                JsonNode answer = ok(post(foreign, "boei?openInputs=true",
                        "{\"instances\": [{\"type\": \"Boei\", \"id\": \"B1\", \"attributes\": {\"kadegeld\": 10}}]}"));
                assertEquals(json("""
                    [{"kind": "attribute", "instance": "B1", "name": "hoogte", "forOutputs": [
                        {"instance": "B1", "name": "boeitaks", "kind": "attribute"}]}]"""),
                        answer.get("openInputs"), foreignAttr);
            } finally {
                foreign.stop(0);
            }
        }
    }

    // ---------------- an entry point that does not give it ----------------

    /** The rule set that infers from empty has no open outcome: a 400 on openInputs, and none of it in its OpenAPI document. */
    @Test
    void an_ordinary_rule_set_refuses_it() throws Exception {
        String source = OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        ServiceHttp ordinary = ServiceHttp.bind(DecisionCore.assemble(OpenValuesSource.DESCRIPTOR, Map.of("b.rgs", source),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            JsonNode v = refused(ordinary, "vondst?openInputs=true", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}");
            assertEquals("openInputs", v.get("field").asText());
            assertEquals("openInputs noemt wat een open uitkomst openhoudt, en alleen een regelset die uit leeg niets afleidt"
                    + " (Afleiden uit leeg: nee) heeft open uitkomsten", v.get("error").asText());
            HttpResponse<String> doc = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ordinary.port()
                    + "/openapi")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(doc.body().contains("openInputs") || doc.body().contains("undetermined"));
        } finally {
            ordinary.stop(0);
        }
    }

    @Test
    void an_entry_point_that_does_not_explain_refuses_it() throws Exception {
        String descriptor = """
            { "service": {"name":"zonder","version":"1"},
              "entrypoints": [ { "name":"vracht","path":"vracht", "scope": {"ruleNames": ["vrachtprijs"]},
                                 "input": ["Vondst"], "output": ["Vondst.vrachtprijs"], "explanation": false } ],
              "parameters": {} }""";
        ServiceHttp without = ServiceHttp.bind(DecisionCore.assemble(descriptor, Map.of("b.rgs", OpenValuesSource.SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            JsonNode v = refused(without, "vracht?openInputs=true", "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}");
            assertEquals("openInputs", v.get("field").asText());
            assertEquals("het entrypoint 'vracht' geeft geen openInputs: de descriptor zet explanation op false",
                    v.get("error").asText());
        } finally {
            without.stop(0);
        }
    }

    /** A fold that can be open over a relation both of whose roles are to-many: uchar carries the document without readings
     *  and refuses it with them, so the entry point answers and refuses what the readings give, with uchar's sentence. */
    @Test
    void a_document_that_uchar_refuses_with_readings_answers_without_them() throws Exception {
        String source = """
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
            """;
        String descriptor = """
            { "service": {"name":"hulp","version":"1"},
              "entrypoints": [ { "name":"hulp","path":"hulp", "scope": {"ruleNames": ["hulpsom"]}, "input": ["Jutter"],
                                 "output": ["Jutter.hulpsom"] } ],
              "parameters": {} }""";
        String request = """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"geholpen wrakken": {"state": "closed", "items": ["W1", "W2"]}}},
              {"type": "Wrak", "id": "W1", "attributes": {"lading": 5}}, {"type": "Wrak", "id": "W2"%s}]}""";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String document = dev.uchar.regelspraak.toolchain.engine.UcharBuild.document(
                dev.uchar.regelspraak.resolver.Resolver.resolve(source));
        for (DecisionCore core : List.of(
                DecisionCore.assemble(descriptor, Map.of("b.rgs", source), CoreConfig.strict(), cl),
                DecisionCore.buildFromDocument(descriptor, document, CoreConfig.strict(), cl, null))) {
            ServiceHttp hulp = ServiceHttp.bind(core, "127.0.0.1", 0).start();
            try {
                assertEquals("open", ok(post(hulp, "hulp", request.formatted(""))).at("/instances/0/attributes/hulpsom/state")
                        .asText());
                assertEquals(json("{\"state\": \"filled\", \"value\": 12}"), ok(post(hulp, "hulp",
                        request.formatted(", \"attributes\": {\"lading\": 7}"))).at("/instances/0/attributes/hulpsom"));
                for (String flag : List.of("explanation", "openInputs")) {
                    JsonNode v = refused(hulp, "hulp?" + flag + "=true", request.formatted(""));
                    assertEquals(flag, v.get("field").asText());
                    assertTrue(v.get("error").asText().contains("een vouw over 'hulp', een relatie waarvan beide rollen"
                            + " meervoudig zijn"), v.toString());
                }
            } finally {
                hulp.stop(0);
            }
        }
    }

    // ---------------- helpers ----------------

    private static JsonNode wrak(String given) throws Exception {
        return open("wrak", "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"" + (given.isEmpty() ? "" : ", " + given) + "}]}");
    }

    private static JsonNode jutter(String w1, String w2, String w3) throws Exception {
        return open("wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": {"state": "closed", "items": ["W1", "W2", "W3"]}}},
              {"type": "Wrak", "id": "W1", "attributes": {%s}}, {"type": "Wrak", "id": "W2", "attributes": {%s}},
              {"type": "Wrak", "id": "W3", "attributes": {%s}}]}""".formatted(w1, w2, w3));
    }

    private static JsonNode open(String path, String request) throws Exception {
        return ok(post(path + "?openInputs=true", request)).get("openInputs");
    }

    private static JsonNode names(JsonNode inputs) {
        var out = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode i : inputs) out.add(i.get("name").asText());
        return out;
    }

    /** The names of the inputs that keep {@code output} of {@code instance} open. */
    private static JsonNode namesFor(JsonNode inputs, String instance, String output) {
        return of(inputs, instance, output, false);
    }

    private static JsonNode membersFor(JsonNode inputs, String instance, String output) {
        return of(inputs, instance, output, true);
    }

    private static JsonNode forOutput(JsonNode inputs, String instance, String output) {
        var out = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode i : inputs) {
            var filtered = ServiceHttp.MAPPER.createArrayNode();
            for (JsonNode o : i.get("forOutputs"))
                if (o.get("instance").asText().equals(instance) && o.get("name").asText().equals(output)) filtered.add(o);
            if (!filtered.isEmpty()) {
                ObjectNode copy = i.deepCopy();
                copy.set("forOutputs", filtered);
                out.add(copy);
            }
        }
        return out;
    }

    private static JsonNode of(JsonNode inputs, String instance, String output, boolean withInstance) {
        var out = ServiceHttp.MAPPER.createArrayNode();
        for (JsonNode i : inputs)
            for (JsonNode o : i.get("forOutputs"))
                if (o.get("instance").asText().equals(instance) && o.get("name").asText().equals(output))
                    out.add((withInstance ? i.get("instance").asText() + " " : "") + i.get("name").asText());
        return out;
    }

    private static JsonNode refused(ServiceHttp s, String path, String request) throws Exception {
        HttpResponse<String> r = post(s, path, request);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violations = json(r.body()).get("violations");
        assertEquals(1, violations.size(), r.body());
        assertEquals("UNSUPPORTED", violations.get(0).get("kind").asText(), r.body());
        return violations.get(0);
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return json(r.body());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return post(path.startsWith("wrak") ? wrakken : service, path, body);
    }

    private static HttpResponse<String> post(ServiceHttp s, String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/" + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static JsonNode json(String text) throws Exception {
        return ServiceHttp.MAPPER.readTree(text);
    }
}
