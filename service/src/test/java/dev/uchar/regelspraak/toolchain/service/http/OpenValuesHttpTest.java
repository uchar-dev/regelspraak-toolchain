package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.Violation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rule set that infers nothing from an empty value through the service ({@link OpenValuesSource}; uchar's
 * RUNTIME-SEMANTICS ch. 1.5): the answer gives every member of {@code output} with its state, filled, settled leeg or
 * open; the request states that a characteristic holds or stands not to hold, and refuses a bare boolean;
 * {@code /info}, the OpenAPI document and {@code fixpoint} say what is open and whose input is complete. The expected
 * states are computed by hand from the rules of the source.
 */
class OpenValuesHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static ServiceHttp service;
    private static ServiceHttp detail;
    private static ServiceHttp document;

    @BeforeAll
    static void start() throws Exception {
        service = ServiceHttp.bind(OpenValuesSource.core(CoreConfig.strict()), "127.0.0.1", 0).start();
        detail = ServiceHttp.bind(OpenValuesSource.core(CoreConfig.strict().withDetail()), "127.0.0.1", 0).start();
        // a uchar-ir-v1 document is served whole, so its one entry point carries every form
        String whole = """
            { "service": {"name":"open-waarden","version":"1"},
              "entrypoints": [ { "name":"vondst","path":"vondst",
                "scope": {"ruleNames": ["kostbaarheid", "vindloon", "uitbetaling", "bewakingstoeslag", "bergloon",
                                        "herkomst onleesbaar", "premie", "totaaltaxatie", "taxatiegrens", "vrachtprijs"]},
                "input": [],
                "output": ["Vondst.taxatie", "Vondst.toeslag", "Vondst.herkomst", "Vondst.vindloon",
                           "Vondst.uitbetaling", "Vondst.bewakingstoeslag", "Vondst.bergloon", "Vondst.premie",
                           "Vondst:kostbaar", "Vondst:betwist", "Vondst:onleesbaar"] } ],
              "parameters": {} }""";
        document = ServiceHttp.bind(DecisionCore.buildFromDocument(whole,
                UcharBuild.document(Resolver.resolve(OpenValuesSource.SOURCE)), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        for (ServiceHttp s : new ServiceHttp[]{service, detail, document}) if (s != null) s.stop(0);
    }

    // ---------------- the answer ----------------

    /** Nothing is given. Only the complete members stand: the toeslag is settled leeg, and onleesbaar stands not to hold,
     *  so the one writer of the herkomst fills nothing and the herkomst is settled leeg. */
    @Test
    void without_input_every_member_that_is_not_complete_is_open() throws Exception {
        assertVondst("""
            {"attributes": {"taxatie": {"state": "open"}, "toeslag": {"state": "empty"}, "herkomst": {"state": "empty"},
              "vindloon": {"state": "open"}, "uitbetaling": {"state": "open"}, "bewakingstoeslag": {"state": "open"},
              "bergloon": {"state": "open"}, "premie": {"state": "open"}},
             "characteristics": {"kostbaar": {"state": "open"}, "betwist": {"state": "open"},
              "onleesbaar": {"state": "holdsNot"}}}""", "");
    }

    /** taxatie 1500: kostbaar, so no bewakingstoeslag and a bergloon of 20; betwist: premie 0; onleesbaar: herkomst 0. */
    @Test
    void a_filled_member_and_a_member_that_is_settled_leeg() throws Exception {
        assertVondst("""
            {"attributes": {"taxatie": {"state": "filled", "value": 1500}, "toeslag": {"state": "filled", "value": 3},
              "herkomst": {"state": "filled", "value": 0}, "vindloon": {"state": "filled", "value": 1510},
              "uitbetaling": {"state": "filled", "value": 1503}, "bewakingstoeslag": {"state": "empty"},
              "bergloon": {"state": "filled", "value": 20}, "premie": {"state": "filled", "value": 0}},
             "characteristics": {"kostbaar": {"state": "holds"}, "betwist": {"state": "holds"},
              "onleesbaar": {"state": "holds"}}}""", """
            "attributes": {"taxatie": 1500, "toeslag": 3},
            "characteristics": {"betwist": {"state": "holds"}, "onleesbaar": {"state": "holds"}}""");
    }

    /** taxatie 50: kostbaar stands not to hold; betwist stated not to hold: premie 10; a herkomst of 3 stands, since its
     *  writer fills nothing. */
    @Test
    void a_characteristic_that_stands_not_to_hold() throws Exception {
        assertVondst("""
            {"attributes": {"taxatie": {"state": "filled", "value": 50}, "toeslag": {"state": "empty"},
              "herkomst": {"state": "filled", "value": 3}, "vindloon": {"state": "filled", "value": 60},
              "uitbetaling": {"state": "filled", "value": 50}, "bewakingstoeslag": {"state": "filled", "value": 5},
              "bergloon": {"state": "empty"}, "premie": {"state": "filled", "value": 10}},
             "characteristics": {"kostbaar": {"state": "holdsNot"}, "betwist": {"state": "holdsNot"},
              "onleesbaar": {"state": "holdsNot"}}}""", """
            "attributes": {"taxatie": 50, "herkomst": 3},
            "characteristics": {"betwist": {"state": "holdsNot"}}""");
    }

    /** The fold over the taxaties of J1: 50 + 70 = 120, and open where that of V2 is not given. */
    @Test
    void a_fold_over_a_set_with_an_open_member_is_open() throws Exception {
        String request = """
            {"instances": [
              {"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1", "V2"]}},
              {"type": "Vondst", "id": "V1", "attributes": {"taxatie": 50}},
              {"type": "Vondst", "id": "V2", "attributes": {%s}}]}""";
        JsonNode filled = ok(post(service, "vondst", request.formatted("\"taxatie\": 70")));
        assertEquals(json("{\"state\": \"filled\", \"value\": 120}"), filled.at("/instances/0/attributes/totaaltaxatie"));
        JsonNode open = ok(post(service, "vondst", request.formatted("")));
        assertEquals(json("{\"state\": \"open\"}"), open.at("/instances/0/attributes/totaaltaxatie"));
    }

    /** The service from the {@code uchar-ir-v1} document answers the same. */
    @Test
    void a_service_from_the_document_answers_with_the_same_states() throws Exception {
        for (String given : List.of("\"attributes\": {\"taxatie\": 1500, \"toeslag\": 3}, \"characteristics\":"
                + " {\"betwist\": {\"state\": \"holds\"}, \"onleesbaar\": {\"state\": \"holds\"}}", "\"characteristics\":"
                + " {\"betwist\": {\"state\": \"holdsNot\"}}, \"attributes\": {\"taxatie\": 50}"))
            assertEquals(vondst(service, given), vondst(document, given, ", \"parameters\": {\"TARIEF\": 2}"));
    }

    /** An open outcome becomes filled when the input it lacks is given; what stood before stands unchanged. */
    @Test
    void an_open_outcome_stands_once_the_missing_input_is_given() throws Exception {
        JsonNode before = vondst(service, "\"characteristics\": {\"betwist\": {\"state\": \"holdsNot\"}}");
        assertEquals("open", before.at("/attributes/vindloon/state").asText());
        assertEquals(json("{\"state\": \"filled\", \"value\": 10}"), before.at("/attributes/premie"));
        JsonNode after = vondst(service, "\"characteristics\": {\"betwist\": {\"state\": \"holdsNot\"}},"
                + " \"attributes\": {\"taxatie\": 50}");
        assertEquals(json("{\"state\": \"filled\", \"value\": 60}"), after.at("/attributes/vindloon"));
        assertEquals(before.at("/attributes/premie"), after.at("/attributes/premie"));
    }

    /** A bergloon of 7 in the input does not stand while its writer is open (no taxatie), stands where the writer fills
     *  nothing (taxatie 50), and gives way to the 20 of the writer (taxatie 1500). */
    @Test
    void an_input_value_beside_an_open_writer_does_not_stand() throws Exception {
        assertEquals(json("{\"state\": \"open\"}"), vondst(service, "\"attributes\": {\"bergloon\": 7}")
                .at("/attributes/bergloon"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 7}"), vondst(service,
                "\"attributes\": {\"bergloon\": 7, \"taxatie\": 50}").at("/attributes/bergloon"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 20}"), vondst(service,
                "\"attributes\": {\"bergloon\": 7, \"taxatie\": 1500}").at("/attributes/bergloon"));
    }

    /** {@code inconsistencies} names what stands: a taxatie of 6000 fails the consistency; without one the consistency does
     *  not decide, and the list is empty. */
    @Test
    void an_undecided_consistency_is_not_named() throws Exception {
        JsonNode failed = ok(post(service, "vondst", request("\"attributes\": {\"taxatie\": 6000}", "")));
        assertEquals(json("[\"taxatiegrens\"]"), failed.get("inconsistencies"));
        assertEquals(json("[{\"rule\": \"taxatiegrens\", \"subject\": \"V1\"}]"), failed.get("inconsistencyDetails"));
        assertEquals(json("[]"), ok(post(service, "vondst", request("", ""))).get("inconsistencies"));
    }

    // ---------------- the request ----------------

    /** kostbaar, which a rule writes: stated to hold it holds whatever the rule does; stated not to hold it is open while
     *  the rule is open, and follows the rule otherwise. */
    @Test
    void a_stated_characteristic_beside_its_writer() throws Exception {
        JsonNode held = vondst(service, "\"characteristics\": {\"kostbaar\": {\"state\": \"holds\"}}");
        assertEquals("holds", held.at("/characteristics/kostbaar/state").asText());
        assertEquals("empty", held.at("/attributes/bewakingstoeslag/state").asText());
        String not = "\"characteristics\": {\"kostbaar\": {\"state\": \"holdsNot\"}}";
        assertEquals("open", vondst(service, not).at("/characteristics/kostbaar/state").asText());
        assertEquals("holdsNot", vondst(service, not + ", \"attributes\": {\"taxatie\": 50}")
                .at("/characteristics/kostbaar/state").asText());
        assertEquals("holds", vondst(service, not + ", \"attributes\": {\"taxatie\": 1500}")
                .at("/characteristics/kostbaar/state").asText());
    }

    /** A null is not given, of a characteristic and of an attribute, as where it is left out. */
    @Test
    void null_is_not_given() throws Exception {
        assertEquals(vondst(service, ""), vondst(service, "\"attributes\": {\"taxatie\": null, \"toeslag\": null},"
                + " \"characteristics\": {\"betwist\": null, \"onleesbaar\": null}"));
    }

    /** A scalar attribute given as {@code {"state": "empty"}} is settled leeg: in {@code output} its state is {@code empty},
     *  {@code openInputs} omits it, a higher deviation testing {@code is gevuld} fails so the base rule decides, a condition
     *  {@code is leeg} holds, and {@code explanation} and {@code explanationParts} verbalise the settled decisions without an
     *  open read; leaving the attribute out or giving {@code null} keeps all three outcomes open. */
    @Test
    void a_settled_empty_attribute_settles_gevuld_and_leeg_while_null_stays_open() throws Exception {
        String source = """
            Afleiden uit leeg: nee

            Objecttype de Vondst (mv: Vondsten)
                de gewenste afhaaldag Numeriek (geheel getal);
                de opslagtermijn Numeriek (geheel getal);
                het gewicht Numeriek (geheel getal) met eenheid kg;
                is direct vrijgegeven kenmerk (bijvoeglijk);

            Regel opslagtermijn
                geldig altijd
                    De opslagtermijn van een Vondst moet gesteld worden op 30.

            Afwijking van opslagtermijn: gewenste afhaaldag opgegeven
                geldig altijd
                    De opslagtermijn van een Vondst moet gesteld worden op zijn gewenste afhaaldag
                    indien zijn gewenste afhaaldag gevuld is.

            Regel directe vrijgave
                geldig altijd
                    Een Vondst is direct vrijgegeven indien zijn gewenste afhaaldag leeg is.
            """;
        String descriptor = """
            { "service": {"name":"afhaaldag","version":"1"},
              "entrypoints": [ { "name":"afhaal","path":"afhaal",
                "scope": {"ruleNames": ["opslagtermijn", "directe vrijgave"]},
                "input": ["Vondst"],
                "output": ["Vondst.gewenste afhaaldag", "Vondst.opslagtermijn", "Vondst.gewicht",
                           "Vondst:direct vrijgegeven"] } ],
              "parameters": {} }""";
        ServiceHttp s = ServiceHttp.bind(DecisionCore.assemble(descriptor, Map.of("afhaal.rgs", source),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            JsonNode settled = ok(post(s, "afhaal?openInputs=true&explanationParts=true", """
                {"instances": [{"type": "Vondst", "id": "V1",
                  "attributes": {"gewenste afhaaldag": {"state": "empty"},
                                 "gewicht": {"value": 15, "unit": "kg"}}}]}"""));
            assertEquals(json("""
                {"gewenste afhaaldag": {"state": "empty"},
                 "opslagtermijn": {"state": "filled", "value": 30},
                 "gewicht": {"state": "filled", "value": 15}}"""),
                    settled.at("/instances/0/attributes"));
            assertEquals(json("{\"direct vrijgegeven\": {\"state\": \"holds\"}}"),
                    settled.at("/instances/0/characteristics"));
            assertEquals(json("[]"), settled.get("openInputs"));
            String explanation = settled.get("explanation").toString();
            assertFalse(explanation.contains("staat niet vast") || explanation.contains("niet gegeven"), explanation);
            assertTrue(explanation.contains("V1 is direct vrijgegeven, omdat zijn gewenste afhaaldag (leeg) leeg is"),
                    explanation);
            assertTrue(explanation.contains("V1 heeft als opslagtermijn 30 volgens de regel 'opslagtermijn'"
                    + " (onvoorwaardelijk): 30; 'gewenste afhaaldag opgegeven' gold niet, want niet geldt:"
                    + " zijn gewenste afhaaldag (leeg) gevuld is"), explanation);
            assertEquals(settled.get("explanation").size(), settled.get("explanationParts").size());
            assertFalse(settled.get("explanationParts").toString().contains("\"open\""),
                    settled.get("explanationParts").toString());

            JsonNode afhaalSchemas = json(get(s, "openapi")).at("/components/schemas");
            assertEquals("kg", afhaalSchemas.at("/Vondst/properties/gewicht/x-unit").asText());
            assertFalse(afhaalSchemas.at("/Vondst/properties/gewicht/oneOf/0").has("x-unit"));

            // Omitted or null: stays open, and openInputs names gewenste afhaaldag (and gewicht, which is in output)
            for (String ungiven : List.of("{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"}]}",
                    "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {\"gewenste afhaaldag\": null}}]}")) {
                JsonNode open = ok(post(s, "afhaal?openInputs=true", ungiven));
                assertEquals("open", open.at("/instances/0/attributes/gewenste afhaaldag/state").asText());
                assertEquals("open", open.at("/instances/0/attributes/opslagtermijn/state").asText());
                assertEquals("open", open.at("/instances/0/characteristics/direct vrijgegeven/state").asText());
                assertEquals("gewenste afhaaldag", open.at("/openInputs/0/name").asText());
                assertEquals(3, open.at("/openInputs/0/forOutputs").size());
            }
        } finally {
            s.stop(0);
        }
    }

    /** Invalid state shapes on a strict attribute: {@code open}, {@code holds}, {@code holdsNot}, {@code {"state": "filled",
     *  "value": ...}} or an unknown state is {@code WRONG_TYPE} naming {@code {"state": "empty"}}, a plain value and leaving
     *  out/null; extra keys or a non-textual state is {@code MALFORMED}; {@code {"state": "empty"}} on a dimensioned or
     *  time-dependent attribute is {@code WRONG_TYPE}; and in an ordinary rule set {@code {"state": "empty"}} is
     *  {@code WRONG_TYPE} while ordinary answers, {@code /info} and {@code /openapi} stay unchanged. */
    @Test
    void invalid_attribute_states_and_ordinary_rule_sets_are_refused() throws Exception {
        for (String wrong : List.of(
                "{\"state\": \"open\"}",
                "{\"state\": \"holds\"}",
                "{\"state\": \"holdsNot\"}",
                "{\"state\": \"filled\", \"value\": 50}",
                "{\"state\": \"filled\"}",
                "{\"state\": \"anders\"}")) {
            JsonNode v = refused("\"attributes\": {\"taxatie\": " + wrong + "}");
            assertEquals("WRONG_TYPE", v.get("kind").asText(), v.toString());
            assertEquals("instances[0].attributes.taxatie", v.get("field").asText(), v.toString());
            assertTrue(v.get("error").asText().contains("{\"state\": \"empty\"}"), v.toString());
            assertTrue(v.get("error").asText().contains("null"), v.toString());
        }
        for (String malformed : List.of(
                "{\"state\": \"empty\", \"extra\": 1}",
                "{\"state\": 123}",
                "{\"state\": null}")) {
            JsonNode v = refused("\"attributes\": {\"taxatie\": " + malformed + "}");
            assertEquals("MALFORMED", v.get("kind").asText(), v.toString());
            assertEquals("instances[0].attributes.taxatie", v.get("field").asText(), v.toString());
        }

        // Dimensioned attribute in a strict rule set refuses {"state": "empty"} over HTTP with WRONG_TYPE
        String dimensionedSource = """
            Afleiden uit leeg: nee

            Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
                1. voorjaar
                2. najaar

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal) gedimensioneerd met het seizoen;
                de taxatie Numeriek (geheel getal);
                het vindloon Numeriek (geheel getal);

            Regel vindloon
                geldig altijd
                    Het vindloon van een Vondst moet berekend worden als zijn taxatie plus 10.
            """;
        String nonScalarDescriptor = """
            { "service": {"name":"niet-scalair","version":"1"},
              "entrypoints": [ { "name":"v","path":"v", "scope": {"ruleNames": ["vindloon"]}, "input": ["Vondst"],
                                 "output": ["Vondst.vindloon"] } ],
              "parameters": {} }""";
        DecisionCore dimensionedCore = DecisionCore.assemble(nonScalarDescriptor,
                Map.of("b.rgs", dimensionedSource), CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        ServiceHttp nonScalar = ServiceHttp.bind(dimensionedCore, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> r = post(nonScalar, "v",
                    "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {\"gewicht\": {\"state\": \"empty\"}}}]}");
            assertEquals(400, r.statusCode(), r.body());
            JsonNode v = json(r.body()).at("/violations/0");
            assertEquals("WRONG_TYPE", v.get("kind").asText(), r.body());
            assertEquals("instances[0].attributes.gewicht", v.get("field").asText(), r.body());
        } finally {
            nonScalar.stop(0);
        }

        // Time-dependent attribute in a strict rule set refuses {"state": "empty"} in RequestMapper with WRONG_TYPE
        // (uchar's Drools branch refuses timeline-member at build time under DROOLS-370, so we test RequestMapper directly)
        var temporalModel = Resolver.resolve("""
            Afleiden uit leeg: nee

            Objecttype de Vondst (mv: Vondsten)
                het strandtarief Numeriek (geheel getal) voor elke maand;
                de taxatie Numeriek (geheel getal);
            """);
        assertTrue(temporalModel.errors().isEmpty(), temporalModel.errors().toString());
        var temporalResult = new dev.uchar.regelspraak.toolchain.service.RequestMapper(
                temporalModel, dimensionedCore.entrypoint("v").build()).map(
                new dev.uchar.regelspraak.toolchain.service.Request(json("""
                    {"instances": [{"type": "Vondst", "id": "V1",
                      "attributes": {"strandtarief": {"state": "empty"}}}]}""")));
        assertEquals(1, temporalResult.violations().size(), temporalResult.violations().toString());
        assertEquals(Violation.Kind.WRONG_TYPE, temporalResult.violations().get(0).kind());
        assertEquals("instances[0].attributes.strandtarief", temporalResult.violations().get(0).field());

        // Ordinary rule set: {"state": "empty"} on an attribute is 400 WRONG_TYPE, and /openapi mentions no EmptyAttributeInput
        ServiceHttp ordinary = ServiceHttp.bind(DecisionCore.assemble(OpenValuesSource.DESCRIPTOR, Map.of("open-waarden.rgs",
                OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "")),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            HttpResponse<String> r = post(ordinary, "vondst",
                    "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {\"taxatie\": {\"state\": \"empty\"}}}]}");
            assertEquals(400, r.statusCode(), r.body());
            assertEquals("WRONG_TYPE", json(r.body()).at("/violations/0/kind").asText(), r.body());
            String openapi = get(ordinary, "openapi");
            assertFalse(openapi.contains("EmptyAttributeInput") || openapi.contains("AttributeState")
                    || openapi.contains("{\"state\": \"empty\"}"), openapi);
            assertFalse(get(ordinary, "info").contains("inferFromEmpty"));
        } finally {
            ordinary.stop(0);
        }
    }

    @Test
    void a_bare_boolean_is_refused_and_the_refusal_names_the_two_forms() throws Exception {
        for (String bare : List.of("true", "false")) {
            JsonNode v = refused("\"characteristics\": {\"betwist\": " + bare + "}");
            assertEquals("WRONG_TYPE", v.get("kind").asText());
            assertEquals("instances[0].characteristics.betwist", v.get("field").asText());
            assertEquals("kenmerk 'betwist' is " + bare + ", en een regelset die uit leeg niets afleidt, vraagt een stand:"
                    + " {\"state\": \"holds\"} waar het geldt, {\"state\": \"holdsNot\"} waar vaststaat dat het niet geldt;"
                    + " waar het niet gegeven is, ontbreekt het of is het null", v.get("error").asText());
        }
    }

    /** Each bare boolean of a request is a violation of its own, also on a characteristic that a rule writes and on one whose
     *  input is complete. */
    @Test
    void every_bare_boolean_of_a_request_is_named() throws Exception {
        HttpResponse<String> r = post(service, "vondst", """
            {"instances": [
              {"type": "Vondst", "id": "V1", "characteristics": {"kostbaar": true, "betwist": {"state": "holds"}}},
              {"type": "Vondst", "id": "V2", "characteristics": {"onleesbaar": true, "betwist": false}}]}""");
        assertEquals(400, r.statusCode(), r.body());
        StringBuilder named = new StringBuilder();
        for (JsonNode v : json(r.body()).get("violations"))
            named.append(v.get("kind").asText()).append(' ').append(v.get("field").asText()).append('\n');
        assertEquals("""
            WRONG_TYPE instances[0].characteristics.kostbaar
            WRONG_TYPE instances[1].characteristics.onleesbaar
            WRONG_TYPE instances[1].characteristics.betwist
            """, named.toString());
    }

    @Test
    void another_value_of_a_characteristic_is_refused() throws Exception {
        assertEquals("WRONG_TYPE", refused("\"characteristics\": {\"betwist\": \"nee\"}").get("kind").asText());
        assertEquals("WRONG_TYPE", refused("\"characteristics\": {\"betwist\": {\"state\": \"open\"}}").get("kind").asText());
        assertEquals("MALFORMED", refused("\"characteristics\": {\"betwist\": {\"state\": \"holds\", \"value\": true}}")
                .get("kind").asText());
        assertEquals("MALFORMED", refused("\"characteristics\": {\"betwist\": {\"periods\": []}}").get("kind").asText());
        // two stands of one characteristic are two values of one declaration
        assertEquals("MALFORMED", refused("\"characteristics\": {\"betwist\": {\"state\": \"holds\"}, \"Betwist\": {\"state\":"
                + " \"holdsNot\"}}")
                .get("kind").asText());
    }

    /** The explanation of what stands; {@code OpenExplanationHttpTest} holds that of what is open. */
    @Test
    void what_stands_is_explained() throws Exception {
        JsonNode answer = ok(post(service, "vondst?explanation=true", request("\"attributes\": {\"taxatie\": 1500},"
                + " \"characteristics\": {\"betwist\": {\"state\": \"holds\"}, \"onleesbaar\": {\"state\": \"holds\"}}", "")));
        assertEquals(json("""
            ["V1 is kostbaar, omdat zijn taxatie (1.500) is groter dan 1.000",
             "V1 heeft als premie 0 volgens 'betwiste premie': 0, omdat het onderwerp betwist is; verdrongen: de regel 'premie' (onvoorwaardelijk, 10)",
             "V1 heeft als vindloon 1.510 volgens de regel 'vindloon' (onvoorwaardelijk): zijn taxatie plus 10, met zijn taxatie (1.500)",
             "V1 heeft als uitbetaling 1.500 volgens de regel 'uitbetaling' (onvoorwaardelijk): zijn taxatie plus zijn toeslag, met zijn taxatie (1.500) en zijn toeslag (leeg)",
             "V1 heeft geen bewakingstoeslag, want geen regel gold; de regel 'bewakingstoeslag' gold niet, want niet geldt: het onderwerp niet kostbaar is",
             "V1 heeft als bergloon 20 volgens de regel 'bergloon': 20, omdat zijn taxatie (1.500) is groter dan 1.000",
             "V1 heeft als herkomst 0 volgens de regel 'herkomst onleesbaar': 0, omdat het onderwerp onleesbaar is",
             "de regel 'vrachtprijs' staat buiten deze berekening"]"""), answer.get("explanation"));
    }

    // ---------------- the fixpoint ----------------

    @Test
    void the_fixpoint_gives_the_open_members_with_the_input_that_does_not_stand() throws Exception {
        JsonNode answer = ok(post(detail, "vondst?fixpoint=true", request("\"attributes\": {\"bergloon\": 7, \"toeslag\": 3},"
                + " \"characteristics\": {\"kostbaar\": {\"state\": \"holdsNot\"}, \"betwist\": {\"state\": \"holds\"}}",
                "")));
        JsonNode o = answer.at("/fixpoint/objects/0");
        // vrachtprijs is an input member here: its rule is outside the scope of this entry point
        assertEquals(json("""
            [{"attribute": "taxatie"}, {"attribute": "vindloon"}, {"attribute": "uitbetaling"},
             {"attribute": "bewakingstoeslag"}, {"attribute": "bergloon", "input": "7"}, {"attribute": "vrachtprijs"},
             {"characteristic": "kostbaar", "input": {"state": "holdsNot"}},
             {"role": "jutter", "factType": "vangst"}]"""),
                o.get("open"), answer.toString());
        assertEquals(json("""
            [{"attribute": "toeslag", "value": "3", "origin": "input"},
             {"attribute": "premie", "value": "0", "origin": "derived"}]"""), o.get("values"), answer.toString());
        assertEquals(json("[{\"characteristic\": \"betwist\", \"origin\": \"input\"}]"), o.get("characteristics"));
    }

    /** kostbaar, which its rule assigns where the taxatie is above 1000, whatever the request says: stated not to hold, the
     *  row of the fixpoint is derived with the input it replaced beside it, in the form {@code open} gives it; stated to hold,
     *  with {@code input: true}; stated not to hold where the rule does not assign it, kostbaar does not hold and is no row. */
    @Test
    void the_fixpoint_gives_beside_a_derived_characteristic_the_state_the_request_gave() throws Exception {
        String given = "\"attributes\": {\"taxatie\": %d}, \"characteristics\": {\"kostbaar\": {\"state\": \"%s\"}}";
        assertEquals(json("""
            [{"characteristic": "kostbaar", "origin": "derived", "input": {"state": "holdsNot"}}]"""),
                characteristics(detail, given.formatted(1500, "holdsNot")));
        assertEquals(json("[{\"characteristic\": \"kostbaar\", \"origin\": \"derived\", \"input\": true}]"),
                characteristics(detail, given.formatted(1500, "holds")));
        assertEquals(json("[]"), characteristics(detail, given.formatted(50, "holdsNot")));
    }

    /** In {@code fixpoint} of a strict rule set, an attribute given as {@code {"state": "empty"}} stands as
     *  {@code "input": {"state": "empty"}} beside a derived value that replaced it and beside an open writer, and produces
     *  no row in {@code values} or {@code open} where it stays settled leeg. */
    @Test
    void the_fixpoint_gives_settled_empty_attribute_input_beside_derived_and_open_and_omits_when_still_empty() throws Exception {
        // 1. Overwritten by a firing rule (herkomst onleesbaar writes 0) AND settled leeg (taxatie empty -> bergloon empty)
        JsonNode derived = ok(post(detail, "vondst?fixpoint=true", request(
                "\"attributes\": {\"taxatie\": {\"state\": \"empty\"}, \"herkomst\": {\"state\": \"empty\"}},"
                        + " \"characteristics\": {\"onleesbaar\": {\"state\": \"holds\"}, \"betwist\": {\"state\": \"holdsNot\"}}",
                ""))).at("/fixpoint/objects/0");
        assertEquals(json("""
            [{"attribute": "herkomst", "value": "0", "origin": "derived", "input": {"state": "empty"}},
             {"attribute": "vindloon", "value": "10", "origin": "derived"},
             {"attribute": "uitbetaling", "value": "0", "origin": "derived"},
             {"attribute": "bewakingstoeslag", "value": "5", "origin": "derived"},
             {"attribute": "premie", "value": "10", "origin": "derived"}]"""), derived.get("values"), derived.toString());
        assertEquals(json("[{\"attribute\": \"vrachtprijs\"}, {\"role\": \"jutter\", \"factType\": \"vangst\"}]"),
                derived.get("open"), derived.toString());

        // Settled-empty to-one role jutter leaves open as well
        JsonNode emptyRole = ok(post(detail, "vondst?fixpoint=true", request(
                "\"attributes\": {\"taxatie\": {\"state\": \"empty\"}, \"herkomst\": {\"state\": \"empty\"}},"
                        + " \"characteristics\": {\"onleesbaar\": {\"state\": \"holds\"}, \"betwist\": {\"state\": \"holdsNot\"}},"
                        + " \"roles\": {\"jutter\": {\"state\": \"empty\"}}",
                ""))).at("/fixpoint/objects/0");
        assertEquals(json("[{\"attribute\": \"vrachtprijs\"}]"), emptyRole.get("open"), emptyRole.toString());

        // 2. Waiting beside an open writer (bergloon empty while taxatie is not given)
        JsonNode waiting = ok(post(detail, "vondst?fixpoint=true", request(
                "\"attributes\": {\"bergloon\": {\"state\": \"empty\"}}", ""))).at("/fixpoint/objects/0");
        assertTrue(waiting.get("open").toString().contains("{\"attribute\":\"bergloon\",\"input\":{\"state\":\"empty\"}}"),
                waiting.toString());
        assertEquals(json("[]"), waiting.get("values"), waiting.toString());
    }

    /** In a rule set that is not strict a characteristic given as {@code false} is one not given: beside the derived kostbaar
     *  stands no input. */
    @Test
    void in_an_ordinary_rule_set_false_beside_a_derived_characteristic_is_no_input() throws Exception {
        ServiceHttp ordinary = ServiceHttp.bind(DecisionCore.assemble(OpenValuesSource.DESCRIPTOR, Map.of("open-waarden.rgs",
                OpenValuesSource.SOURCE.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "")),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            assertEquals(json("[{\"characteristic\": \"kostbaar\", \"origin\": \"derived\"}]"), characteristics(ordinary,
                    "\"attributes\": {\"taxatie\": 1500}, \"characteristics\": {\"kostbaar\": false}"));
        } finally {
            ordinary.stop(0);
        }
    }

    /** The characteristics of V1 at the fixpoint of {@code s} with {@code given}. */
    private static JsonNode characteristics(ServiceHttp s, String given) throws Exception {
        return ok(post(s, "vondst?fixpoint=true", request(given, ""))).at("/fixpoint/objects/0/characteristics");
    }

    /** vrachtprijs reads the taxatie and then the TARIEF, which nothing binds: with a taxatie EF-014, without one the
     *  evaluation stops at the open taxatie and the vrachtprijs is open. The fixpoint of the 422 shows what is open. */
    @Test
    void the_fixpoint_of_a_422_shows_the_open_members() throws Exception {
        HttpResponse<String> r = post(detail, "vracht?fixpoint=true", request("\"attributes\": {\"taxatie\": 5}", ""));
        assertEquals(422, r.statusCode(), r.body());
        JsonNode body = json(r.body());
        assertEquals("EF-014", body.at("/faults/0/code").asText(), r.body());
        JsonNode o = body.at("/fixpoint/objects/0");
        assertTrue(o.get("open").toString().contains("{\"attribute\":\"vindloon\"}"), r.body());
        assertFalse(o.get("open").toString().contains("taxatie\"}"), r.body());
        assertEquals("taxatie", o.at("/values/0/attribute").asText(), r.body());

        JsonNode open = ok(post(detail, "vracht", request("", "")));
        assertEquals("open", open.at("/instances/0/attributes/vrachtprijs/state").asText());
        JsonNode bound = ok(post(detail, "vracht", request("\"attributes\": {\"taxatie\": 5}",
                ", \"parameters\": {\"TARIEF\": 3}")));
        assertEquals(json("{\"state\": \"filled\", \"value\": 15}"), bound.at("/instances/0/attributes/vrachtprijs"));
    }

    // ---------------- /info and the OpenAPI document ----------------

    @Test
    void info_says_that_the_rule_set_infers_nothing_from_empty_and_whose_input_is_complete() throws Exception {
        for (ServiceHttp s : List.of(service, document)) {
            JsonNode info = json(get(s, "info"));
            assertEquals(json("false"), info.get("inferFromEmpty"));
            assertEquals(json("""
                [{"kind": "attribute", "objectType": "Vondst", "name": "toeslag"},
                 {"kind": "characteristic", "objectType": "Vondst", "name": "onleesbaar"}]"""), info.get("completeInput"));
        }
    }

    /** The opgave marker makes two members whose declaration is complete: the {@code opgegeven bergloon} is input, the
     *  {@code berekende bergloon} a rule writes and is not (uchar's golden jutterswet-volledig-gegeven). */
    @Test
    void a_complete_member_that_a_rule_writes_is_no_complete_input() throws Exception {
        Path golden = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-volledig-gegeven");
        String descriptor = """
            { "service": {"name":"volledig","version":"1"}, "entrypoints": [], "parameters": {} }""";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String expected = json("""
            [{"kind": "attribute", "objectType": "Vondst", "name": "toeslag"},
             {"kind": "attribute", "objectType": "Vondst", "name": "opgegeven bergloon"},
             {"kind": "characteristic", "objectType": "Vondst", "name": "drijfhout"}]""").toString();
        assertEquals(expected, DecisionCore.assemble(descriptor, Map.of("b.rgs",
                Files.readString(golden.resolve("source.rgs"))), CoreConfig.strict(), cl).completeInput().toString());
        assertEquals(expected, DecisionCore.buildFromDocument(descriptor, Files.readString(golden.resolve("ir.json")),
                CoreConfig.strict(), cl, null).completeInput().toString());
    }

    @Test
    void the_openapi_document_describes_the_states() throws Exception {
        JsonNode schemas = json(get(service, "openapi")).at("/components/schemas");
        assertEquals("#/components/schemas/AttributeState",
                schemas.at("/Answer/properties/instances/items/properties/attributes/additionalProperties/$ref").asText());
        assertEquals("#/components/schemas/CharacteristicState",
                schemas.at("/Answer/properties/instances/items/properties/characteristics/additionalProperties/$ref").asText());
        assertEquals(json("[\"holds\", \"holdsNot\", \"open\"]"), schemas.at("/CharacteristicState/properties/state/enum"));
        assertEquals(json("[\"filled\"]"), schemas.at("/AttributeState/oneOf/0/properties/state/enum"));
        assertEquals(json("[\"empty\", \"open\"]"), schemas.at("/AttributeState/oneOf/1/properties/state/enum"));
        assertEquals(2, schemas.at("/AttributeState/oneOf").size());
        // a characteristic of a request is a state, and no boolean
        JsonNode input = schemas.get("CharacteristicInput");
        assertEquals(json("""
            {"type": "object", "required": ["state"], "additionalProperties": false, "properties":
              {"state": {"type": "string", "enum": ["holds", "holdsNot"]}}, "nullable": true}"""),
                ((com.fasterxml.jackson.databind.node.ObjectNode) input.deepCopy()).without("description"));
        assertEquals(json("[{\"$ref\": \"#/components/schemas/CharacteristicInput\"}]"),
                schemas.at("/Vondst/properties/betwist/allOf"));
        assertEquals(json("{\"$ref\": \"#/components/schemas/CharacteristicInput\"}"),
                schemas.at("/Request/properties/instances/items/properties/characteristics/additionalProperties"));
        // a scalar attribute of a request is either a plain value of its datatype or EmptyAttributeInput, nullable
        JsonNode emptyInput = schemas.get("EmptyAttributeInput");
        assertEquals(json("""
            {"type": "object", "required": ["state"], "additionalProperties": false, "properties":
              {"state": {"type": "string", "enum": ["empty"]}}}"""),
                ((com.fasterxml.jackson.databind.node.ObjectNode) emptyInput.deepCopy()).without("description"));
        assertEquals(json("[{\"type\": \"integer\"}, {\"$ref\": \"#/components/schemas/EmptyAttributeInput\"}]"),
                schemas.at("/Vondst/properties/taxatie/oneOf"));
        assertTrue(schemas.at("/Vondst/properties/taxatie/nullable").asBoolean());
        assertTrue(schemas.at("/Request/properties/instances/items/properties/attributes/description").asText()
                .contains("{\"state\": \"empty\"}"));
        JsonNode emptyRoleInput = schemas.get("EmptyRoleInput");
        assertEquals(json("""
            {"type": "object", "required": ["state"], "additionalProperties": false, "properties":
              {"state": {"type": "string", "enum": ["empty"]}}}"""),
                ((com.fasterxml.jackson.databind.node.ObjectNode) emptyRoleInput.deepCopy()).without("description"));
        assertTrue(schemas.at("/Request/properties/instances/items/properties/roles/additionalProperties/nullable").asBoolean());
        assertEquals("#/components/schemas/EmptyRoleInput",
                schemas.at("/Request/properties/instances/items/properties/roles/additionalProperties/oneOf/2/$ref").asText());
        assertFalse(schemas.get("Vondst").toString().contains("boolean"), schemas.get("Vondst").toString());
        assertTrue(schemas.at("/Vondst/properties/toeslag/description").asText().contains("Volledig gegeven"));
        // a complete attribute that only an initialisation writes is no leeg of its own: the initialisation is at its turn
        assertTrue(schemas.at("/Vondst/properties/toeslag/description").asText().contains("Schrijft alleen een initialisatie"
                + " het, dan is die aan de beurt"), schemas.at("/Vondst/properties/toeslag/description").asText());
        assertTrue(schemas.at("/Vondst/properties/onleesbaar/description").asText().contains("staat vast dat het niet geldt"),
                schemas.at("/Vondst/properties/onleesbaar/description").asText());
        assertTrue(schemas.at("/Vondst/properties/taxatie/description").asText().contains("niet gegeven"));
        assertTrue(schemas.at("/Info/properties").has("completeInput"));
        assertEquals("string", schemas.at("/Info/properties/completeInput/items/properties/factType/type").asText());
        assertTrue(schemas.at("/Fixpoint/properties/objects/items/properties").has("open"));
        assertTrue(schemas.at("/Fixpoint/properties/objects/items/properties/open/description").asText()
                .contains("factType"), schemas.at("/Fixpoint").toString());
        assertTrue(schemas.at("/Fixpoint/properties/objects/items/properties/values/description").asText()
                .contains("{\"state\": \"empty\"}"), schemas.at("/Fixpoint").toString());
        assertTrue(schemas.at("/Fixpoint/properties/objects/items/properties/open/description").asText()
                .contains("{\"state\": \"empty\"}"), schemas.at("/Fixpoint").toString());
        // beside a derived characteristic the input it replaced, which only a strict rule set gives as a state
        assertTrue(schemas.at("/Fixpoint/properties/objects/items/properties/characteristics/description").asText()
                .contains("input: true als ook de invoer het zette, of {\"state\": \"holdsNot\"} als de invoer zei dat het niet"
                        + " geldt en een regel het toch toekent"), schemas.at("/Fixpoint").toString());
    }

    // ---------------- the start ----------------

    /** A dimensioned attribute has no state per cell in a strict rule set, so the answer does not carry it. */
    @Test
    void a_dimensioned_attribute_in_output_refuses_the_start() {
        String source = """
            Afleiden uit leeg: nee

            Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
                1. voorjaar
                2. najaar

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal) gedimensioneerd met het seizoen;
                de taxatie Numeriek (geheel getal);
                het vindloon Numeriek (geheel getal);

            Regel vindloon
                geldig altijd
                    Het vindloon van een Vondst moet berekend worden als zijn taxatie plus 10.
            """;
        String descriptor = """
            { "service": {"name":"cellen","version":"1"},
              "entrypoints": [ { "name":"v","path":"v", "scope": {"ruleNames": ["vindloon"]}, "input": ["Vondst"],
                                 "output": ["Vondst.gewicht", "Vondst.vindloon"] } ],
              "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor, Map.of("b.rgs", source),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        assertEquals(1, f.violations().size(), f.getMessage());
        assertEquals(Violation.Kind.UNSUPPORTED, f.violations().get(0).kind());
        assertEquals("entrypoints[0].output[0]", f.violations().get(0).field());
    }

    /** A form whose reading uchar's Drools branch does not carry in a strict rule set makes the entry point a marker, with
     *  uchar's sentence. */
    @Test
    void a_form_uchar_does_not_carry_there_is_a_marker() {
        String source = OpenValuesSource.SOURCE + """

            Regel nieuwe vondst
                geldig altijd
                    Een Jutter heeft een vondst met taxatie gelijk aan 5.
            """;
        String descriptor = """
            { "service": {"name":"creatie","version":"1"},
              "entrypoints": [ { "name":"v","path":"v", "scope": {"ruleNames": ["nieuwe vondst"]}, "input": ["Jutter"],
                                 "output": [] } ],
              "parameters": {} }""";
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor, Map.of("b.rgs", source),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        assertTrue(f.violations().stream().anyMatch(v -> v.kind() == Violation.Kind.MARKER
                && v.message().contains("object-creation")), f.getMessage());
    }

    // ---------------- helpers ----------------

    @Test
    void open_and_settled_empty_to_one_roles_and_ef049_over_http() throws Exception {
        ServiceHttp wrakken = ServiceHttp.bind(OpenValuesSource.wrak(CoreConfig.strict().withDetail()), "127.0.0.1", 0).start();
        try {
            // Omitted and null vinder both keep vindpremie open; {"state": "empty"} settles vinder without a Jutter
            for (String rolePart : List.of("", ", \"roles\": {\"vinder\": null}")) {
                JsonNode open = ok(post(wrakken, "wrak?openInputs=true&fixpoint=true",
                        "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"lengte\": 30}" + rolePart + "}]}"));
                assertEquals(json("{\"state\": \"open\"}"), open.at("/instances/0/attributes/vindpremie"));
                assertTrue(open.get("openInputs").toString()
                        .contains("\"kind\":\"role\",\"instance\":\"W1\",\"factType\":\"wrakvondst\",\"name\":\"vinder\""),
                        open.toString());
                assertTrue(open.at("/fixpoint/objects/0/open").toString()
                        .contains("{\"role\":\"vinder\",\"factType\":\"wrakvondst\"}"), open.toString());
            }
            JsonNode settled = ok(post(wrakken, "wrak?openInputs=true&fixpoint=true",
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"lengte\": 30},"
                            + " \"roles\": {\"vinder\": {\"state\": \"empty\"}}}]}"));
            assertEquals(json("{\"state\": \"filled\", \"value\": 30}"), settled.at("/instances/0/attributes/vindpremie"));
            assertFalse(settled.get("openInputs").toString().contains("\"vinder\""), settled.toString());
            assertFalse(settled.at("/fixpoint/objects/0/open").toString().contains("\"role\":\"vinder\""), settled.toString());

            // 422 EF-049 from opposite instance and from top-level relations
            for (String conflictBody : List.of(
                    """
                    {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1"]}},
                                   {"type": "Wrak", "id": "W1", "roles": {"vinder": {"state": "empty"}}}]}""",
                    """
                    {"instances": [{"type": "Jutter", "id": "J1"},
                                   {"type": "Wrak", "id": "W1", "roles": {"vinder": {"state": "empty"}}}],
                     "relations": [{"factType": "wrakvondst", "roles": {"vinder": "J1", "gevonden wrak": "W1"}}]}""")) {
                HttpResponse<String> r = post(wrakken, "wrak", conflictBody);
                assertEquals(422, r.statusCode(), r.body());
                JsonNode body = json(r.body());
                assertEquals("EVALUATION_FAULT", body.get("status").asText(), r.body());
                assertEquals("EF-049", body.at("/faults/0/code").asText(), r.body());
                assertEquals("W1", body.at("/faults/0/params/label").asText(), r.body());
                assertEquals("vinder", body.at("/faults/0/params/role").asText(), r.body());
            }
        } finally {
            wrakken.stop(0);
        }
    }

    /** The answer for V1 with {@code given} on the service from the sources: the attributes and characteristics are those of
     *  {@code expected}, in that order. */
    private static void assertVondst(String expected, String given) throws Exception {
        JsonNode v = vondst(service, given);
        assertEquals(json(expected).get("attributes").toString(), v.get("attributes").toString());
        assertEquals(json(expected).get("characteristics").toString(), v.get("characteristics").toString());
    }

    private static JsonNode vondst(ServiceHttp s, String given) throws Exception {
        return vondst(s, given, "");
    }

    private static JsonNode vondst(ServiceHttp s, String given, String parameters) throws Exception {
        return ok(post(s, "vondst", request(given, parameters))).at("/instances/0");
    }

    private static String request(String given, String parameters) {
        return "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\"" + (given.isBlank() ? "" : ", " + given) + "}]"
                + parameters + "}";
    }

    private static JsonNode refused(String given) throws Exception {
        HttpResponse<String> r = post(service, "vondst", request(given, ""));
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violations = json(r.body()).get("violations");
        assertEquals(1, violations.size(), r.body());
        return violations.get(0);
    }

    private static JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return json(r.body());
    }

    private static HttpResponse<String> post(ServiceHttp s, String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/" + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String get(ServiceHttp s, String path) throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/" + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private static JsonNode json(String text) throws Exception {
        return ServiceHttp.MAPPER.readTree(text);
    }
}
