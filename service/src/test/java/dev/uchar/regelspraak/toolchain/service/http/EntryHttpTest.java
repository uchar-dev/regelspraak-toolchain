package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.CaseInput;
import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.transpiler.tms.ComputeFault;
import dev.uchar.regelspraak.toolchain.service.AnswerBuilder;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.EvaluationFault;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.Executor;
import dev.uchar.regelspraak.toolchain.service.World;
import dev.uchar.regelspraak.transpiler.tms.EvaluationFaults;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service fills, fires and reads every session through uchar's entry ({@code DroolsBackend.insert}, {@code fire},
 * {@code fixpointOf}, {@code setDerivedValues}): the answer carries the value a rule derived, and every evaluation fault
 * that stands at the fixpoint is a 422 that gives per fault its kind and uchar's sentence, never a stacktrace. Own
 * material: one model of finds with an entry point per rule set, so each request meets the fault it is about and no
 * other, one of a fold over a to-many role, whose fault names the member the order of the list puts first, as in
 * uchar's replay, the model of uchar's witness of a fact creation that gives a to-one role a second filling, a fault of
 * another type, and that of a reciprocal role written on a subtype of its filler.
 */
class EntryHttpTest {

    private static final String SOURCE = """
        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);
            het aantal delen Numeriek (geheel getal);
            het deelgewicht Numeriek (getal met 2 decimalen);
            het vindersloon Numeriek (getal met 2 decimalen);
            het vondstjaar Numeriek (geheel getal);

        Regel deelgewicht van een vondst
            geldig altijd
                Het deelgewicht van een Vondst moet berekend worden als zijn gewicht gedeeld door zijn aantal delen.

        Regel loon voor een zware vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 25,00
                indien zijn gewicht groter is dan 5,00.

        Regel loon voor een grote vondst
            geldig altijd
                Het vindersloon van een Vondst moet gesteld worden op 10,00
                indien zijn gewicht groter is dan 3,00.

        Regel vondstjaar van een vondst
            geldig altijd
                Het vondstjaar van een Vondst moet gesteld worden op Rekenjaar.
        """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"vondsten","version":"1"},
          "entrypoints": [
            { "name":"deelgewicht","path":"deelgewicht",
              "scope": {"ruleNames": ["deelgewicht van een vondst"]},
              "input": ["Vondst"], "output": ["Vondst.deelgewicht"] },
            { "name":"vindersloon","path":"vindersloon",
              "scope": {"ruleNames": ["loon voor een zware vondst", "loon voor een grote vondst"]},
              "input": ["Vondst"], "output": ["Vondst.vindersloon"] },
            { "name":"vondstjaar","path":"vondstjaar",
              "scope": {"ruleNames": ["vondstjaar van een vondst"]},
              "input": ["Vondst"], "output": ["Vondst.vondstjaar"] },
            { "name":"alles","path":"alles",
              "scope": {"ruleNames": ["deelgewicht van een vondst", "loon voor een zware vondst",
                                       "loon voor een grote vondst", "vondstjaar van een vondst"]},
              "input": ["Vondst"], "output": ["Vondst.deelgewicht", "Vondst.vindersloon", "Vondst.vondstjaar"] } ],
          "parameters": {} }""";

    /** An own total without a clip over an attribute on a timeline, which reads the asOfDate-bucket scalar. */
    private static final String RETENTION_SOURCE = """
        Eenheidsysteem Munt
            de euro (mv: euros) EUR €

        Eenheidsysteem Bewaartijd
            de maand mnd

        Objecttype de Jutter (mv: Jutters) (bezield)
            de bewaarvergoeding per maand Numeriek (geheel getal) met eenheid €/mnd voor elke maand;
            het bewaargeld Numeriek (geheel getal) met eenheid €;

        Regel bewaargeld van een jutter
            geldig altijd
                Het bewaargeld van een Jutter moet berekend worden als het totaal van zijn bewaarvergoeding per maand.
        """;

    private static final String BEWARING_DESCRIPTOR = """
        { "service": {"name":"bewaring","version":"1"},
          "entrypoints": [ { "name":"bewaargeld","path":"bewaargeld",
            "scope": {"ruleNames": ["bewaargeld van een jutter"]},
            "input": ["Jutter"], "output": ["Jutter.bewaargeld"] } ],
          "parameters": {} }""";

    /** A fold over a to-many role whose filter adds a member's wachtdagen to its meldtijdstip: where the step is out of
     *  range for two members, the evaluation stops at the first in the order of the list (RUNTIME-SEMANTICS ch. 5.11).
     *  A division by zero no longer serves: uchar answers it with an empty value and a §9.5 fact, not a fault. */
    private static final String FOLD_SOURCE = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de zware vondsten Numeriek (geheel getal);

        Objecttype de Vondst (mv: Vondsten)
            het meldtijdstip Datum en tijd in millisecondes;
            de grens Datum en tijd in millisecondes;
            de wachtdagen Numeriek (geheel getal) met eenheid dagen;

        Feittype aanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten

        Regel zware vondsten
            geldig altijd
                De zware vondsten van een Jutter moet berekend worden als het aantal vondsten van de Jutter
                die aan de volgende voorwaarde voldoen: zijn meldtijdstip plus zijn wachtdagen is later dan zijn grens.
        """;

    private static final String FOLD_DESCRIPTOR = """
        { "service": {"name":"vouw","version":"1"},
          "entrypoints": [ { "name":"zware vondsten","path":"zware-vondsten",
            "scope": {"ruleNames": ["zware vondsten"]},
            "input": ["Jutter"], "output": ["Jutter.zware vondsten"] } ],
          "parameters": {} }""";

    /** The model of uchar's witness of a second filling ({@code TmsCreatieWitnessTest}, document D): the fact creation
     *  makes every geworven jutter of the kustwacht of a Haven a havenjutter of that Haven, and a Jutter has one haven.
     *  The ligplaatsdeel of a Haven without ligplaatsen reads the rekenjaar, which the request has no evaluation date
     *  for: the other fault of the mixed request. */
    private static final String FILLING_SOURCE = """
        Objecttype de Kustwacht (mv: Kustwachten)

        Objecttype de Haven (mv: Havens)
            het jutteraantal Numeriek (geheel getal);
            de ligplaatsen Numeriek (geheel getal);
            het ligplaatsdeel Numeriek (geheel getal);

        Objecttype de Jutter (mv: Jutters) (bezield)

        Feittype bewaking
            de kustwacht Kustwacht
            de bewaakte haven (mv: bewaakte havens) Haven
        Eén kustwacht bewaakt meerdere bewaakte havens

        Feittype werving
            de werver Kustwacht
            de geworven jutter (mv: geworven jutters) Jutter
        Eén werver heeft meerdere geworven jutters

        Feittype havenlid
            de haven Haven
            de havenjutter (mv: havenjutters) Jutter
        Eén haven heeft meerdere havenjutters

        Regel jutteraantal van een haven
            geldig altijd
                Het jutteraantal van een Haven moet berekend worden als het aantal havenjutters van de Haven.

        Regel werving van havenjutters
            geldig altijd
                Een havenjutter van een Haven is een geworven jutter van de kustwacht van de Haven.

        Regel ligplaatsdeel van een haven
            geldig altijd
                Het ligplaatsdeel van een Haven moet gesteld worden op Rekenjaar
                indien zijn ligplaatsen gelijk is aan 0.
        """;

    private static final String FILLING_DESCRIPTOR = """
        { "service": {"name":"vulling","version":"1"},
          "entrypoints": [
            { "name":"vulling","path":"vulling",
              "scope": {"ruleNames": ["jutteraantal van een haven", "werving van havenjutters"]},
              "input": ["Kustwacht", "Haven", "Jutter"], "output": ["Haven.jutteraantal"] },
            { "name":"mengsel","path":"mengsel",
              "scope": {"ruleNames": ["jutteraantal van een haven", "werving van havenjutters",
                                       "ligplaatsdeel van een haven"]},
              "input": ["Kustwacht", "Haven", "Jutter"], "output": ["Haven.jutteraantal", "Haven.ligplaatsdeel"] } ],
          "parameters": {} }""";

    /** uchar's case D2: K1 guards H1 and H2 and recruits J1, so J1 would be a havenjutter of both. */
    private static final String FILLING = """
        {"instances":[{"type":"Kustwacht","id":"K1",
          "roles":{"bewaakte havens":["H1","H2"],"geworven jutters":["J1"]}},
          {"type":"Haven","id":"H1"},{"type":"Haven","id":"H2"},{"type":"Jutter","id":"J1"}]}""";

    /** D2, with H1 at 0 ligplaatsen and H2 at 4. */
    private static final String MENGSEL = """
        {"instances":[{"type":"Kustwacht","id":"K1",
          "roles":{"bewaakte havens":["H1","H2"],"geworven jutters":["J1"]}},
          {"type":"Haven","id":"H1","attributes":{"ligplaatsen":0}},
          {"type":"Haven","id":"H2","attributes":{"ligplaatsen":4}},{"type":"Jutter","id":"J1"}]}""";

    /** The model of uchar's {@code SubtypeRolvullerWitnessTest.de_wederkerige_rol_spiegelt_ook_voor_een_subtype},
     *  without the rest of its golden: a reciprocal role filled by Vondst, and Wrakvondst a subtype of Vondst. */
    private static final String MAAT_SOURCE = """
        Objecttype de Vondst (mv: Vondsten)
            de waarde Numeriek (geheel getal);
            de maatwaarde Numeriek (geheel getal);

        Objecttype de Wrakvondst is een Vondst (mv: Wrakvondsten)

        Wederkerig feittype vondstpaar
            de maat (mv: maten) Vondst
        Eén maat heeft één maat

        Regel maatwaarde van een vondst
            geldig altijd
                De maatwaarde van een Vondst moet gesteld worden op de waarde van de maat van de Vondst.
        """;

    private static final String MAAT_DESCRIPTOR = """
        { "service": {"name":"maat","version":"1"},
          "entrypoints": [ { "name":"maatwaarde","path":"maatwaarde",
            "scope": {"ruleNames": ["maatwaarde van een vondst"]},
            "input": ["Vondst", "Wrakvondst"], "output": ["Vondst.maatwaarde"] } ],
          "parameters": {} }""";

    /** The case of uchar's witness: W, a Wrakvondst, writes the one link of the reciprocal role. */
    private static final String MAAT_CASE = """
        { "format": "uchar-case-v0", "asOfDate": null,
          "input": { "instances": [
              { "label": "V", "objectType": "Vondst", "attributes": { "waarde": { "number": "2" } },
                "roles": {}, "characteristics": [] },
              { "label": "W", "objectType": "Wrakvondst", "attributes": { "waarde": { "number": "5" } },
                "roles": { "maat": [ "V" ] }, "characteristics": [] } ], "parameters": {} },
          "expected": [
              { "kind": "attributeValue", "label": "V", "attribute": "maatwaarde", "value": { "number": "5" } },
              { "kind": "attributeValue", "label": "W", "attribute": "maatwaarde", "value": { "number": "2" } } ] }""";

    private static ServiceHttp service;
    private static ServiceHttp foldService;
    private static ServiceHttp fillingService;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("vondsten.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        DecisionCore fold = DecisionCore.assemble(FOLD_DESCRIPTOR, Map.of("vouw.rgs", FOLD_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        foldService = ServiceHttp.bind(fold, "127.0.0.1", 0).start();
        DecisionCore filling = DecisionCore.assemble(FILLING_DESCRIPTOR, Map.of("vulling.rgs", FILLING_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        fillingService = ServiceHttp.bind(filling, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (foldService != null) foldService.stop(0);
        if (fillingService != null) fillingService.stop(0);
    }

    // ---------------- (c) an answer with a derived value ----------------

    @Test
    void the_answer_gives_the_derived_value() throws Exception {
        HttpResponse<String> resp = post("/deelgewicht", """
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"gewicht":6.00,"aantal delen":3}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode deelgewicht = find(tree(resp.body()), "V1", "deelgewicht");
        assertNotNull(deelgewicht, resp.body());
        assertTrue(deelgewicht.isNumber(), resp.body());
        assertEquals(0, new BigDecimal("2.00").compareTo(deelgewicht.decimalValue()), resp.body());
    }

    // ---------------- (d) an evaluation fault per kind: 422 ----------------

    /** A division by a numeric zero is no evaluation fault: uchar gives an empty value and a §9.5 fact of kind EF-005
     *  (RUNTIME-SEMANTICS ch. 1.4, point 4), so the answer is a 200 with the value null and the rule among the
     *  inconsistencies. */
    @Test
    void a_division_by_zero_is_200_with_an_empty_value_and_an_inconsistency() throws Exception {
        HttpResponse<String> resp = post("/deelgewicht", """
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"gewicht":6.00,"aantal delen":0}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode answer = tree(resp.body());
        assertTrue(find(answer, "V1", "deelgewicht").isNull(), resp.body());
        assertEquals("deelgewicht van een vondst", answer.at("/inconsistencies/0").asText(), resp.body());
        assertEquals(1, answer.get("inconsistencies").size(), resp.body());
    }

    @Test
    void a_missing_evaluation_date_is_422_compute_fault() throws Exception {
        JsonNode body = evaluationFault(post("/vondstjaar", """
            {"instances":[{"type":"Vondst","id":"V1","attributes":{}}]}"""));
        assertEquals(List.of("COMPUTE_FAULT"), kinds(body), body.toString());
        assertTrue(body.at("/faults/0/error").asText().contains("vondstjaar van een vondst")
                && body.at("/faults/0/error").asText().contains("evaluation date"), body.toString());
    }

    @Test
    void two_writers_that_differ_are_422_writers_conflict_with_the_id_from_the_request() throws Exception {
        JsonNode body = evaluationFault(post("/vindersloon", """
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"gewicht":6.00}}]}"""));
        assertEquals(List.of("WRITERS_CONFLICT"), kinds(body), body.toString());
        String sentence = body.at("/faults/0/error").asText();
        assertTrue(sentence.contains("\"loon voor een zware vondst\" schrijft 25") && sentence.contains("\"loon voor een grote"
                + " vondst\" schrijft 10") && sentence.contains("in het attribuut vindersloon van V1, een Vondst"), sentence);
    }

    @Test
    void two_errors_at_once_are_a_422_with_one_element_per_error() throws Exception {
        JsonNode body = evaluationFault(post("/alles", """
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"gewicht":6.00,"aantal delen":3}}]}"""));
        assertEquals("de regels geven op deze invoer 2 evaluatiefouten", body.get("error").asText(), body.toString());
        assertEquals(List.of("WRITERS_CONFLICT", "COMPUTE_FAULT"), kinds(body), body.toString());
        assertTrue(body.at("/faults/0/error").asText().contains("van V1, een Vondst"), body.toString());
        assertTrue(body.at("/faults/1/error").asText().startsWith("fout in de regel \"vondstjaar van een vondst\""),
                body.toString());
    }

    /** No request reaches an {@code InputFormFault}: the HTTP route answers a request with a timeline with 400
     *  UNSUPPORTED, which this test asserts first. The translation of that fault into a 422 is therefore tested at
     *  the level of the core: the service's executor ({@link Executor}) gets a world whose bean holds a full timeline
     *  where the rule takes the asOfDate-bucket scalar, and {@link AnswerBuilder#evaluationFault} builds from the fault
     *  the body both adapters send with the 422. This is the strongest witness of this kind that exists. */
    @Test
    void a_timeline_gives_over_http_400_and_the_core_translates_an_input_form_error_to_the_422() throws Exception {
        DecisionCore core = DecisionCore.assemble(BEWARING_DESCRIPTOR, Map.of("bewaring.rgs", RETENTION_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        ServiceHttp bewaring = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(bewaring, "/bewaargeld", """
                {"asOfDate":"2026-03-15",
                 "instances":[{"type":"Jutter","id":"J1","attributes":{"bewaarvergoeding per maand":10}}]}""");
            assertEquals(400, resp.statusCode(), resp.body());
            assertEquals("UNSUPPORTED", tree(resp.body()).at("/violations/0/kind").asText(), resp.body());
        } finally {
            bewaring.stop(0);
        }

        DecisionCore.Entrypoint ep = core.entrypoint("bewaargeld");
        CaseInput inputSection = CaseInput.of(ep.build(), tree("""
            {"asOfDate":"2026-03-15","input":{"instances":[{"label":"J1","objectType":"Jutter","attributes":
              {"bewaarvergoeding per maand":{"timeline":[{"from":"2026-01-01","to":"2027-01-01","value":{"number":"10"}}]}}}]}}"""));
        EvaluationFault error = assertThrows(EvaluationFault.class, () -> new Executor(ep.build(), ep.defaultGlobals())
                .fire(new World(inputSection, Map.of(), LocalDate.of(2026, 3, 15))));
        JsonNode body = AnswerBuilder.evaluationFault(error);
        assertEquals("EVALUATION_FAULT", body.path("status").asText(), body.toString());
        assertEquals(List.of("INPUT_FORM_FAULT"), kinds(body), body.toString());
        String sentence = body.at("/faults/0/error").asText();
        assertTrue(sentence.startsWith("fout in de regel \"bewaargeld van een jutter\"") && sentence.contains("\"J1\"")
                && sentence.contains("supplies a full timeline"), sentence);
    }

    // ---------------- a fault at the fixpoint of another type: OTHER ----------------

    /** uchar's case D2 ({@code TmsCreatieWitnessTest.een_tweede_vulling_van_een_naar_een_rol_is_een_fout}): the fact
     *  creation gives J1 two havens, and uchar puts that fault at the fixpoint with a plain
     *  {@code IllegalStateException}, outside the four types its README names. It is an evaluation fault by its place
     *  there, so the 422 gives it as OTHER, with the sentence uchar's replay gives on the same input. */
    @Test
    void a_second_filling_from_a_to_a_role_is_422_other(@TempDir Path map) throws Exception {
        List<String> replay = replayFaults(map, FILLING_SOURCE, FILLING);
        assertEquals(1, replay.size(), replay::toString);
        assertTrue(replay.get(0).startsWith("de naar-één-rol \"haven\" van een Jutter heeft twee verschillende"
                + " vullingen"), replay::toString);
        JsonNode body = evaluationFault(post(fillingService, "/vulling", FILLING));
        assertEquals("de regels geven op deze invoer een evaluatiefout", body.get("error").asText(), body.toString());
        assertEquals(List.of("OTHER"), kinds(body), body.toString());
        assertEquals(replay, sentences(body), body.toString());
    }

    /** The second filling of D2 beside a read of the rekenjaar without an evaluation date (H1 has 0 ligplaatsen): the
     *  422 has an element for each, in the order of uchar's faults, which is by the name of the form ("havenlid", the
     *  fact type of the filling, before "ligplaatsdeel van een haven") and not by kind. */
    @Test
    void a_second_filling_beside_a_compute_fault_is_a_422_with_both_in_the_uchar_order(@TempDir Path map)
            throws Exception {
        List<String> replay = replayFaults(map, FILLING_SOURCE, MENGSEL);
        assertEquals(2, replay.size(), replay::toString);
        JsonNode body = evaluationFault(post(fillingService, "/mengsel", MENGSEL));
        assertEquals("de regels geven op deze invoer 2 evaluatiefouten", body.get("error").asText(), body.toString());
        assertEquals(List.of("OTHER", "COMPUTE_FAULT"), kinds(body), body.toString());
        assertEquals(replay, sentences(body), body.toString());
    }

    // ---------------- the order of a to-many list: the member a fault of a fold names ----------------

    /** J1 writes its vondsten as V1, V3, V2, while the instances come as V1, V2, V3; the date step is out of range for V3
     *  (18446744073709551618 days) and for V2 (18446744073709551617). uchar's replay of that input stops at V3, the first in the written order, and so does
     *  the service; the order of the instances would name V2's count. */
    @Test
    void a_list_under_the_houder_names_the_failing_member_in_the_order_of_the_request(@TempDir Path map)
            throws Exception {
        assertFoldFaultAsReplay(map, """
            {"instances":[{"type":"Jutter","id":"J1","roles":{"vondsten":["V1","V3","V2"]}},
              {"type":"Vondst","id":"V1","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":2}},
              {"type":"Vondst","id":"V2","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551617}},
              {"type":"Vondst","id":"V3","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551618}}]}""", null);
    }

    /** V9 (18446744073709551617 days) and V10 (18446744073709551618) each name J1 as their aanmelder, in that order: the replay mirrors those links into
     *  J1's list by label in byte order (RUNTIME-SEMANTICS ch. 5.7a), V10 before V9, so the count of V10, where the
     *  order of the instances gives V9's. */
    @Test
    void a_mirrored_list_names_the_failing_member_in_the_order_of_the_ids(@TempDir Path map) throws Exception {
        assertFoldFaultAsReplay(map, """
            {"instances":[{"type":"Jutter","id":"J1"},
              {"type":"Vondst","id":"V9","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551617},"roles":{"aanmelder":"J1"}},
              {"type":"Vondst","id":"V10","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551618},"roles":{"aanmelder":"J1"}}]}""",
                null);
    }

    /** A relation of {@code relaties} sets the member's own field toward the holder, as a role the member writes does,
     *  so its member joins the list as a mirrored one: J1 writes V1, and the relations link V9 (18446744073709551617 days) and V10 (18446744073709551618),
     *  which gives the order V1, V10, V9 and V10's count, as the replay gives on the case where V9 and V10 each write their
     *  aanmelder. A list of the written members alone would leave V9 and V10 out, which uchar's entry refuses. */
    @Test
    void a_member_from_relations_is_in_the_list_as_a_mirrored_member(@TempDir Path map) throws Exception {
        assertFoldFaultAsReplay(map, """
            {"instances":[{"type":"Jutter","id":"J1","roles":{"vondsten":["V1"]}},
              {"type":"Vondst","id":"V1","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":2}},
              {"type":"Vondst","id":"V9","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551617}},
              {"type":"Vondst","id":"V10","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551618}}],
             "relations":[{"factType":"aanmelding","roles":{"aanmelder":"J1","vondst":"V9"}},
                         {"factType":"aanmelding","roles":{"aanmelder":"J1","vondst":"V10"}}]}""", """
            {"instances":[{"type":"Jutter","id":"J1","roles":{"vondsten":["V1"]}},
              {"type":"Vondst","id":"V1","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":2}},
              {"type":"Vondst","id":"V9","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551617},"roles":{"aanmelder":"J1"}},
              {"type":"Vondst","id":"V10","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":18446744073709551618},"roles":{"aanmelder":"J1"}}]}""");
    }

    /** V1 names J1 as its aanmelder by its own role, and a relation of {@code relaties} links it to J2: the to-one field
     *  aanmelder gets a second, different target. The service refuses that with 400 WRONG_TYPE on the role of the
     *  relation that names J2, as it refuses a second target among the inline roles (uchar's {@code CaseInput}). Before, the
     *  relation wrote over the first link and the list of J1 kept V1, which uchar's entry refused (500). */
    @Test
    void a_second_other_target_via_relations_is_400_wrong_type() throws Exception {
        HttpResponse<String> resp = post(foldService, "/zware-vondsten", """
            {"instances":[{"type":"Jutter","id":"J1"},{"type":"Jutter","id":"J2"},
              {"type":"Vondst","id":"V1","attributes":{"meldtijdstip":"2024-01-01T00:00:00","grens":"2024-06-01T00:00:00","wachtdagen":2},"roles":{"aanmelder":"J1"}}],
             "relations":[{"factType":"aanmelding","roles":{"aanmelder":"J2","vondst":"V1"}}]}""");
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals(1, body.path("violations").size(), resp.body());
        assertEquals("WRONG_TYPE", body.at("/violations/0/kind").asText(), resp.body());
        assertEquals("relations[0].roles.aanmelder", body.at("/violations/0/field").asText(), resp.body());
        assertEquals("to-one rol 'aanmelder' krijgt een tweede, ander doel — ref weigert die case bij het laden (WF-013)",
                body.at("/violations/0/error").asText(), resp.body());
    }

    // ---------------- a reciprocal role written on a subtype of its filler ----------------

    /** W, a Wrakvondst, names V as its maat, and one link of a reciprocal role holds both ways (§13.3.9.2): uchar's
     *  CaseInput asks the reciprocal role along the chain of W's types, as uchar's replay does, so V reads W's waarde.
     *  Asked of W's own type alone, the reverse leg stayed unwritten and V's maatwaarde empty, in a 200, where uchar's
     *  replay of the same case plays with 5. */
    @Test
    void a_reciprocal_role_on_a_subtype_applies_in_both_directions(@TempDir Path map) throws Exception {
        RegelSpraakModel model = Resolver.resolve(MAAT_SOURCE);
        assertEquals(List.of(), model.errors());
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(model), StandardCharsets.UTF_8);
        Path testCase = map.resolve("case.json");
        Files.writeString(testCase, MAAT_CASE, StandardCharsets.UTF_8);
        CaseRunner.Result replay = CaseRunner.runGolden(ir, List.of(testCase));
        assertEquals("PLAYED", replay.status(), replay::toString);

        DecisionCore core = DecisionCore.assemble(MAAT_DESCRIPTOR, Map.of("maat.rgs", MAAT_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        ServiceHttp maat = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(maat, "/maatwaarde", """
                {"instances":[{"type":"Vondst","id":"V","attributes":{"waarde":2}},
                  {"type":"Wrakvondst","id":"W","attributes":{"waarde":5},"roles":{"maat":"V"}}]}""");
            assertEquals(200, resp.statusCode(), resp.body());
            JsonNode answer = tree(resp.body());
            assertEquals("5", String.valueOf(find(answer, "V", "maatwaarde")), resp.body());
            assertEquals("2", String.valueOf(find(answer, "W", "maatwaarde")), resp.body());
        } finally {
            maat.stop(0);
        }
    }

    // ---------------- helpers ----------------

    /** The 422 of {@code verzoek} on the fold names the fault uchar's replay names on the same input written as a case
     *  ({@code asCase} where the request carries {@code relaties}, which a case does not have; else {@code verzoek}),
     *  and that fault is the date step of V10 (or V3): the replay names the member the order of its list puts first. */
    private static void assertFoldFaultAsReplay(Path map, String request, String asCase) throws Exception {
        String replay = replayFault(map, asCase != null ? asCase : request);
        assertTrue(replay.contains("\"zware vondsten\"") && replay.contains("18446744073709551618"), replay);
        JsonNode body = evaluationFault(post(foldService, "/zware-vondsten", request));
        assertEquals(List.of("COMPUTE_FAULT"), kinds(body), body.toString());
        assertEquals(replay, body.at("/faults/0/error").asText(), body.toString());
    }

    /** The sentence of the fault uchar's replay ({@code CaseRunner.runGolden}) throws on {@code verzoek} as a case of the
     *  fold's document. */
    private static String replayFault(Path map, String request) throws Exception {
        RuntimeException error = replay(map, FOLD_SOURCE, request);
        assertInstanceOf(ComputeFault.class, error, error::toString);
        return error.getMessage();
    }

    /** The sentences of the faults uchar's replay throws on {@code verzoek} as a case of the document of {@code source},
     *  in its order: one fault as its own exception, two or more as {@code EvaluationFaults}. */
    private static List<String> replayFaults(Path map, String source, String request) throws Exception {
        RuntimeException error = replay(map, source, request);
        if (error instanceof EvaluationFaults more) return more.faults().stream().map(Throwable::getMessage).toList();
        return List.of(error.getMessage());
    }

    /** What uchar's replay ({@code CaseRunner.runGolden}) throws on {@code verzoek} as a case of the document of
     *  {@code source}: the reverse of how the replay gate writes a case as a request, an instance per instance with its
     *  numbers and its roles, in the order of the request. */
    private static RuntimeException replay(Path map, String source, String request) throws Exception {
        RegelSpraakModel model = Resolver.resolve(source);
        assertEquals(List.of(), model.errors());
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(model), StandardCharsets.UTF_8);
        ObjectNode testCase = ServiceHttp.MAPPER.createObjectNode();
        testCase.put("format", "uchar-case-v0");
        testCase.putNull("asOfDate");
        ObjectNode input = testCase.putObject("input");
        ArrayNode instances = input.putArray("instances");
        for (JsonNode i : tree(request).get("instances")) {
            ObjectNode instance = instances.addObject();
            instance.put("label", i.get("id").asText());
            instance.put("objectType", i.get("type").asText());
            ObjectNode attributes = instance.putObject("attributes");
            for (Map.Entry<String, JsonNode> a : i.path("attributes").properties())
                if (a.getValue().isTextual()) attributes.putObject(a.getKey()).put("dateTime", a.getValue().asText());
                else attributes.putObject(a.getKey()).put("number", a.getValue().asText());
            ObjectNode roles = instance.putObject("roles");
            for (Map.Entry<String, JsonNode> r : i.path("roles").properties()) {
                ArrayNode targets = roles.putArray(r.getKey());
                if (r.getValue().isArray()) r.getValue().forEach(targets::add);
                else targets.add(r.getValue());
            }
            instance.putArray("characteristics");
        }
        input.putObject("parameters");
        testCase.putArray("expected").addObject().put("kind", "consistent");
        Path casePath = map.resolve("case.json");
        Files.writeString(casePath, testCase.toString(), StandardCharsets.UTF_8);
        return assertThrows(RuntimeException.class, () -> CaseRunner.runGolden(ir, List.of(casePath)));
    }

    /** The body of a 422: status EVALUATION_FAULT, a sentence, and per fault its kind and uchar's sentence, without a
     *  stacktrace. */
    private static JsonNode evaluationFault(HttpResponse<String> resp) throws Exception {
        assertEquals(422, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals("EVALUATION_FAULT", body.path("status").asText(), resp.body());
        assertTrue(body.path("faults").isArray() && !body.path("faults").isEmpty(), resp.body());
        assertEquals(List.of("error", "faults", "status"), fields(body), resp.body());
        for (JsonNode f : body.get("faults")) assertEquals(List.of("error", "kind"), fields(f), resp.body());
        assertFalse(resp.body().contains("Exception") || resp.body().contains("\tat "), "geen stacktrace: " + resp.body());
        return body;
    }

    private static List<String> kinds(JsonNode body) {
        return body.get("faults").valueStream().map(f -> f.get("kind").asText()).toList();
    }

    private static List<String> sentences(JsonNode body) {
        return body.get("faults").valueStream().map(f -> f.get("error").asText()).toList();
    }

    private static List<String> fields(JsonNode n) {
        List<String> sink = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink.stream().sorted().toList();
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return post(service, path, body);
    }

    private static HttpResponse<String> post(ServiceHttp to, String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }

    private static JsonNode find(JsonNode answer, String id, String attribute) {
        for (JsonNode inst : answer.get("instances"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributes").get(attribute);
        return null;
    }
}
