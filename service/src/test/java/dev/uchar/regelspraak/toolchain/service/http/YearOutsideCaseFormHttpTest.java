package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.Violation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A date with a year beyond 0000-9999 reads as an ISO date ({@code +10000-01-01}), but the {@code uchar-case-v0} lexeme
 * carries four digits and no sign, and uchar's input step refuses it when it reads the request written as a case. The
 * service gives that refusal as a 400 {@code WRONG_TYPE} on the field of the request that holds the date, with
 * uchar's sentence, and not as the 500 of an exception it did not expect.
 */
class YearOutsideCaseFormHttpTest {

    /** uchar's golden {@code jutterswet-vondstverjaring}. */
    private static final String SOURCE = """
        Parameter de peildag : Datum in dagen;
        Parameter de havenheffing : Numeriek (geheel getal) voor elke maand;

        Objecttype de Vondst (mv: Vondsten)
            de vondstdag Datum in dagen;
            is verjaard kenmerk (bijvoeglijk);

        Regel Kenmerktoekenning verjaard
            geldig altijd
                Een Vondst is verjaard
                indien zijn vondstdag eerder is dan 31-01-2024.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"verjaring","version":"1"},
          "entrypoints": [ { "name":"verjaring","path":"verjaring",
            "scope": {"ruleNames": ["Kenmerktoekenning verjaard"]},
            "input": ["Vondst"], "output": ["Vondst:verjaard"] } ],
          "parameters": {} }""";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static ServiceHttp service;

    @BeforeAll
    static void start() throws Exception {
        service = ServiceHttp.bind(DecisionCore.assemble(DESCRIPTOR, Map.of("verjaring.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    @Test
    void a_date_within_the_case_form_computes() throws Exception {
        HttpResponse<String> resp = post("""
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"vondstdag":"2024-01-15"}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
    }

    @Test
    void an_attribute_with_year_10000_is_400_on_its_field() throws Exception {
        assertYearOutsideCaseForm(post("""
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"vondstdag":"2024-01-15"}},
              {"type":"Vondst","id":"V2","attributes":{"vondstdag":"+10000-01-01"}}]}"""),
                "instances[1].attributes.vondstdag", "+10000-01-01");
    }

    @Test
    void a_negative_year_is_400_on_its_field() throws Exception {
        assertYearOutsideCaseForm(post("""
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"vondstdag":"-0001-01-01"}}]}"""),
                "instances[0].attributes.vondstdag", "-0001-01-01");
    }

    /** uchar reads the evaluation date before any input, so it is the field named, also beside a second such date. */
    @Test
    void an_as_of_date_with_year_10000_is_400_on_as_of_date() throws Exception {
        assertYearOutsideCaseForm(post("""
            {"asOfDate":"+10000-01-01",
             "instances":[{"type":"Vondst","id":"V1","attributes":{"vondstdag":"+10000-01-01"}}]}"""),
                "asOfDate", "+10000-01-01");
    }

    @Test
    void an_evaluation_date_with_year_10000_is_400_on_evaluation_date() throws Exception {
        assertYearOutsideCaseForm(post("""
            {"rekendatum":"+10000-01-01","instances":[{"type":"Vondst","id":"V1"}]}"""),
                "rekendatum", "+10000-01-01");
    }

    /** A parameter the service binds itself, and a bound of its timeline, are held to the lexeme a case holds them to,
     *  in the same sentence. */
    @Test
    void a_parameter_with_year_10000_is_400_on_its_field() throws Exception {
        assertYearOutsideCaseForm(post("""
            {"parameters":{"peildag":"+10000-01-01"},"instances":[{"type":"Vondst","id":"V1"}]}"""),
                "parameters.peildag", "+10000-01-01");
        assertYearOutsideCaseForm(post("""
            {"parameters":{"havenheffing":{"timeline":[{"to":"+10000-01-01","value":3}]}},
             "instances":[{"type":"Vondst","id":"V1"}]}"""),
                "parameters.havenheffing.timeline[0].to", "+10000-01-01");
    }

    /** A descriptor default is held to the lexeme as a request is: the service does not start. */
    @Test
    void a_parameter_default_with_year_10000_refuses_the_start() {
        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(
                DESCRIPTOR.replace("\"parameters\": {}", "\"parameters\": {\"peildag\": \"-0001-01-01\"}"),
                Map.of("verjaring.rgs", SOURCE), CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        assertEquals(List.of("parameters.peildag"), f.violations().stream().map(Violation::field).toList(),
                f.violations().toString());
        assertTrue(f.violations().get(0).message().startsWith("malformed date '-0001-01-01'"), f.getMessage());
    }

    /** One violation, WRONG_TYPE on {@code field}, in uchar's sentence about the date lexeme {@code lexeme}. */
    private static void assertYearOutsideCaseForm(HttpResponse<String> resp, String field, String lexeme)
            throws Exception {
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = ServiceHttp.MAPPER.readTree(resp.body());
        assertEquals(1, body.path("violations").size(), resp.body());
        assertEquals("WRONG_TYPE", body.at("/violations/0/kind").asText(), resp.body());
        assertEquals(field, body.at("/violations/0/field").asText(), resp.body());
        String sentence = body.at("/violations/0/error").asText();
        assertTrue(sentence.startsWith("malformed date '" + lexeme + "'"), sentence);
    }

    private static HttpResponse<String> post(String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/verjaring"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
