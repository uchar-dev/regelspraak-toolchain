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
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.Violation;
import dev.uchar.regelspraak.toolchain.service.World;
import dev.uchar.regelspraak.transpiler.tms.EvaluationFaults;
import dev.uchar.regelspraak.transpiler.tms.WriteBoundaryFault;

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
 * another type, that of a reciprocal role written on a subtype of its filler, and an allocation whose share does not
 * fit its target.
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
                Het deelgewicht van een Vondst moet berekend worden als zijn gewicht gedeeld door zijn aantal delen rekenkundig afgerond op 2 decimalen.

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

    /** The model of uchar's witness of a second filling ({@code TmsCreationWitnessTest}, document D): the fact creation
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

    /** The model of uchar's {@code SubtypeRoleFillerWitnessTest.the_reciprocal_role_also_mirrors_for_a_subtype},
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

    /** A gewicht and a vondstfactor of two decimals each, for the input fence (EF-004). */
    private static final String PRECISION_SOURCE = """
        Parameter de vondstfactor : Numeriek (getal met 2 decimalen)

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);
            het gewogen gewicht Numeriek (getal met 4 decimalen);

        Regel gewogen gewicht van een vondst
            geldig altijd
                Het gewogen gewicht van een Vondst moet berekend worden als zijn gewicht maal de vondstfactor.
        """;

    private static final String PRECISION_DESCRIPTOR = """
        { "service": {"name":"precisie","version":"1"},
          "entrypoints": [ { "name":"gewogen","path":"gewogen",
            "scope": {"ruleNames": ["gewogen gewicht van een vondst"]},
            "input": ["Vondst"], "output": ["Vondst.gewogen gewicht"] } ],
          "parameters": {} }""";

    /** The vondstgewicht of a Jutter per season (uchar's golden jutterswet-seizoenboeking): a rule writes the cell najaar
     *  from the cell voorjaar, and the jaargewicht is the sum over every season. */
    private static final String CELL_SOURCE = """
        Eenheidsysteem Gewicht
            het kilogram (mv: kilogrammen) kg

        Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
            1. voorjaar
            2. zomer
            3. najaar

        Objecttype de Jutter (mv: Jutters) (bezield)
            het vondstgewicht Numeriek (niet-negatief getal met 2 decimalen) met eenheid kg gedimensioneerd met seizoen;
            het jaargewicht Numeriek (niet-negatief getal met 2 decimalen) met eenheid kg;

        Regel najaarsgewicht van een jutter
            geldig altijd
                Het vondstgewicht in najaar van een Jutter moet berekend worden als zijn vondstgewicht in voorjaar maal 2.

        Regel jaargewicht van een jutter
            geldig altijd
                Het jaargewicht van een Jutter moet berekend worden als de som van zijn vondstgewicht over alle seizoenen.
        """;

    private static final String CELL_DESCRIPTOR = """
        { "service": {"name":"seizoen","version":"1"},
          "entrypoints": [ { "name":"seizoen","path":"seizoen",
            "scope": {"ruleNames": ["najaarsgewicht van een jutter", "jaargewicht van een jutter"]},
            "input": ["Jutter"], "output": ["Jutter.vondstgewicht", "Jutter.jaargewicht"] } ],
          "parameters": {} }""";

    /** uchar's red case jutterswet-verdeling-past-niet: the veilingopbrengst of a Strandveiling in equal shares over its
     *  veilingjutters, into a whole veilingdeel, without a rounding. uchar's precision gate bounds the share of an
     *  allocation by its pot alone (RUNTIME-SEMANTICS ch. 2.4), so a share that does not fit is a fault of the session:
     *  every other write whose value the typing does not guarantee to fit, the gate refuses before any session. */
    private static final String ALLOCATION_SOURCE = """
        Objecttype de Strandveiling (mv: Strandveilingen)
            de veilingopbrengst Numeriek (geheel getal);
            het veilingrestant Numeriek (geheel getal);

        Objecttype de Jutter (mv: Jutters) (bezield)
            het veilingdeel (mv: veilingdelen) Numeriek (geheel getal);

        Feittype veilingdeelname
            de strandveiling Strandveiling
            de veilingjutter (mv: veilingjutters) Jutter
        Eén strandveiling heeft meerdere veilingjutters

        Regel verdeel de veilingopbrengst van een strandveiling
            geldig altijd
                De veilingopbrengst van een strandveiling wordt verdeeld over de veilingdelen van alle veilingjutters van de strandveiling, waarbij wordt verdeeld:
                - in gelijke delen.
                Als onverdeelde rest blijft het veilingrestant van de strandveiling over.
        """;

    private static final String ALLOCATION_DESCRIPTOR = """
        { "service": {"name":"veiling","version":"1"},
          "entrypoints": [ { "name":"veiling","path":"veiling",
            "scope": {"ruleNames": ["verdeel de veilingopbrengst van een strandveiling"]},
            "input": ["Strandveiling", "Jutter"], "output": ["Jutter.veilingdeel", "Strandveiling.veilingrestant"] } ],
          "parameters": {} }""";

    private static ServiceHttp service;
    private static ServiceHttp allocationService;
    private static ServiceHttp foldService;
    private static ServiceHttp fillingService;
    private static ServiceHttp precisionService;
    private static ServiceHttp cellService;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("vondsten.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        DecisionCore allocation = DecisionCore.assemble(ALLOCATION_DESCRIPTOR, Map.of("veiling.rgs", ALLOCATION_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        allocationService = ServiceHttp.bind(allocation, "127.0.0.1", 0).start();
        DecisionCore fold = DecisionCore.assemble(FOLD_DESCRIPTOR, Map.of("vouw.rgs", FOLD_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        foldService = ServiceHttp.bind(fold, "127.0.0.1", 0).start();
        DecisionCore filling = DecisionCore.assemble(FILLING_DESCRIPTOR, Map.of("vulling.rgs", FILLING_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        fillingService = ServiceHttp.bind(filling, "127.0.0.1", 0).start();
        DecisionCore precision = DecisionCore.assemble(PRECISION_DESCRIPTOR, Map.of("precisie.rgs", PRECISION_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        precisionService = ServiceHttp.bind(precision, "127.0.0.1", 0).start();
        DecisionCore cell = DecisionCore.assemble(CELL_DESCRIPTOR, Map.of("seizoen.rgs", CELL_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        cellService = ServiceHttp.bind(cell, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
        if (allocationService != null) allocationService.stop(0);
        if (foldService != null) foldService.stop(0);
        if (fillingService != null) fillingService.stop(0);
        if (precisionService != null) precisionService.stop(0);
        if (cellService != null) cellService.stop(0);
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

    /** 1001 in equal shares over two veilingjutters is 1001/2 each, which the whole veilingdeel does not hold: uchar
     *  puts a {@code WriteBoundaryFault} (EF-006, RUNTIME-SEMANTICS ch. 2.4) at the fixpoint, and the 422 gives it as
     *  WRITE_BOUNDARY_FAULT with the sentence uchar's replay gives on the same input. */
    @Test
    void a_value_that_does_not_fit_its_target_is_422_write_boundary_fault(@TempDir Path map) throws Exception {
        String request = """
            {"instances":[{"type":"Strandveiling","id":"V1","attributes":{"veilingopbrengst":1001},
              "roles":{"veilingjutters":["J1","J2"]}},{"type":"Jutter","id":"J1"},{"type":"Jutter","id":"J2"}]}""";
        RuntimeException replay = replay(map, ALLOCATION_SOURCE, request);
        WriteBoundaryFault fault = assertInstanceOf(WriteBoundaryFault.class, replay, replay::toString);
        assertEquals("EF-006", fault.code());
        assertEquals(Map.of("place", "J1.veilingdeel", "value", "1001/2", "decimals", "0"), fault.params());
        JsonNode body = evaluationFault(post(allocationService, "/veiling", request));
        assertEquals(List.of("WRITE_BOUNDARY_FAULT"), kinds(body), body.toString());
        assertEquals(List.of(fault.getMessage()), sentences(body), body.toString());
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

    /** The subject's own total of a timeline takes the asOfDate-bucket scalar (uchar's RUNTIME-SEMANTICS ch. 8.4), which
     *  the request does not carry: a scalar on the timeline attribute is a 400 UNSUPPORTED, which this test asserts
     *  first. A full timeline there is the other convention, an {@code InputFormFault} at the fixpoint, and the service
     *  answers it with the 422 of the kind INPUT_FORM_FAULT and uchar's sentence. The translation is also tested at the
     *  level of the core: the service's executor ({@link Executor}) gets a world whose bean holds that full timeline,
     *  and {@link AnswerBuilder#evaluationFault} builds from the fault the body both adapters send with the 422. */
    @Test
    void a_scalar_gives_over_http_400_and_a_full_timeline_where_the_rule_takes_the_bucket_scalar_the_422()
            throws Exception {
        DecisionCore core = DecisionCore.assemble(BEWARING_DESCRIPTOR, Map.of("bewaring.rgs", RETENTION_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        ServiceHttp bewaring = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = post(bewaring, "/bewaargeld", """
                {"asOfDate":"2026-03-15",
                 "instances":[{"type":"Jutter","id":"J1","attributes":{"bewaarvergoeding per maand":10}}]}""");
            assertEquals(400, resp.statusCode(), resp.body());
            assertEquals("UNSUPPORTED", tree(resp.body()).at("/violations/0/kind").asText(), resp.body());
            HttpResponse<String> full = post(bewaring, "/bewaargeld", """
                {"asOfDate":"2026-03-15",
                 "instances":[{"type":"Jutter","id":"J1","attributes":{"bewaarvergoeding per maand":
                   {"timeline":[{"from":"2026-01-01","to":"2027-01-01","value":10}]}}}]}""");
            assertEquals(422, full.statusCode(), full.body());
            JsonNode answer = tree(full.body());
            assertEquals(List.of("INPUT_FORM_FAULT"), kinds(answer), full.body());
            String said = answer.at("/faults/0/error").asText();
            assertTrue(said.startsWith("fout in de regel \"bewaargeld van een jutter\"") && said.contains("\"J1\"")
                    && said.contains("supplies a full timeline"), said);
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

    // ---------------- a value the declaration does not hold: INPUT_PRECISION_FAULT ----------------

    /** 1,005 for a gewicht of two decimals: uchar's input step ({@code CaseInput.of}) refuses it as EF-004
     *  (RUNTIME-SEMANTICS ch. 2.4, 5.7 step 3) before any session, as uchar's replay of the same input does, and the
     *  422 gives it as INPUT_PRECISION_FAULT with the sentence of that replay. */
    @Test
    void an_attribute_value_that_does_not_fit_is_422_input_precision_fault(@TempDir Path map) throws Exception {
        String request = """
            {"instances":[{"type":"Vondst","id":"V1","attributes":{"gewicht":1.005}}]}""";
        CaseRunner.InputPrecisionFault replay = assertInstanceOf(CaseRunner.InputPrecisionFault.class,
                replay(map, PRECISION_SOURCE, request));
        assertEquals("EF-004", replay.code());
        assertEquals(Map.of("place", "V1.gewicht", "value", "1.005", "decimals", "2"), replay.params());
        JsonNode body = evaluationFault(post(precisionService, "/gewogen", request));
        assertEquals(List.of("INPUT_PRECISION_FAULT"), kinds(body), body.toString());
        assertEquals(List.of(replay.getMessage()), sentences(body), body.toString());
    }

    /** 0,505 for a vondstfactor of two decimals: the service binds a parameter of the request itself and holds it to
     *  uchar's input fence ({@code CaseRunner.inputFits}), as uchar's replay holds a parameter of a case, and the 422
     *  gives the sentence of that replay. */
    @Test
    void a_parameter_value_that_does_not_fit_is_422_input_precision_fault(@TempDir Path map) throws Exception {
        CaseRunner.InputPrecisionFault replay = parameterReplay(map);
        JsonNode body = evaluationFault(post(precisionService, "/gewogen", """
            {"parameters":{"vondstfactor":0.505},
             "instances":[{"type":"Vondst","id":"V1","attributes":{"gewicht":2.00}}]}"""));
        assertEquals(List.of("INPUT_PRECISION_FAULT"), kinds(body), body.toString());
        assertEquals(List.of(replay.getMessage()), sentences(body), body.toString());
    }

    /** The same 0,505 as the default of the descriptor: the descriptor does not load, with a violation on the
     *  parameter that carries the sentence of uchar's replay, so no request meets the value. */
    @Test
    void a_parameter_default_that_does_not_fit_fails_the_descriptor(@TempDir Path map) throws Exception {
        CaseRunner.InputPrecisionFault replay = parameterReplay(map);
        String descriptor = PRECISION_DESCRIPTOR.replace("\"parameters\": {}",
                "\"parameters\": {\"vondstfactor\": 0.505}");
        ServiceError error = assertThrows(ServiceError.class, () -> DecisionCore.assemble(descriptor,
                Map.of("precisie.rgs", PRECISION_SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        assertEquals(List.of(Violation.of(Violation.Kind.WRONG_TYPE, "parameters.vondstfactor", replay.getMessage())),
                error.violations(), error::getMessage);
    }

    /** What uchar's replay throws on a case of the precision model whose vondstfactor is 0,505. */
    private static CaseRunner.InputPrecisionFault parameterReplay(Path map) throws Exception {
        RegelSpraakModel model = Resolver.resolve(PRECISION_SOURCE);
        assertEquals(List.of(), model.errors());
        Path ir = map.resolve("ir.json");
        Files.writeString(ir, IrEmitter.emit(model), StandardCharsets.UTF_8);
        Path testCase = map.resolve("case.json");
        Files.writeString(testCase, """
            { "format": "uchar-case-v0", "asOfDate": null,
              "input": { "instances": [ { "label": "V1", "objectType": "Vondst",
                  "attributes": { "gewicht": { "number": "2.00" } }, "roles": {}, "characteristics": [] } ],
                "parameters": { "vondstfactor": { "number": "0.505" } } },
              "expected": [ { "kind": "consistent" } ] }""", StandardCharsets.UTF_8);
        CaseRunner.InputPrecisionFault replay = assertThrows(CaseRunner.InputPrecisionFault.class,
                () -> CaseRunner.runGolden(ir, List.of(testCase)));
        assertEquals(Map.of("place", "parameter vondstfactor", "value", "0.505", "decimals", "2"), replay.params());
        return replay;
    }

    // ---------------- a dimensioned attribute: its values are its cells ----------------

    /** The request gives the cells voorjaar and zomer of the vondstgewicht in the form of uchar's case
     *  ({@code cells}, a list of {@code {labels, value}}); the rules read them: the jaargewicht is 1,50 + 2,00 and the
     *  najaar the rule writes, 3,00. A label compares as a name, so {@code Zomer} names the cell zomer. */
    @Test
    void the_cells_of_the_request_are_the_input_of_the_rules() throws Exception {
        HttpResponse<String> resp = post(cellService, "/seizoen", """
            {"instances":[{"type":"Jutter","id":"J1","attributes":{"vondstgewicht":{"cells":[
              {"labels":["voorjaar"],"value":1.50},{"labels":["Zomer"],"value":2.00}]}}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode jaargewicht = find(tree(resp.body()), "J1", "jaargewicht");
        assertNotNull(jaargewicht, resp.body());
        assertEquals(0, new BigDecimal("6.50").compareTo(jaargewicht.decimalValue()), resp.body());
    }

    /** The answer gives a dimensioned attribute as its cells, not as one value: every cell of the attribute in the
     *  declared order of its labels, each label in its declared spelling, the empty zomer as null and the najaar the rule
     *  derived from the voorjaar of the request. */
    @Test
    void a_derived_cell_is_answered_among_the_cells_of_its_attribute() throws Exception {
        HttpResponse<String> resp = post(cellService, "/seizoen", """
            {"instances":[{"type":"Jutter","id":"J1","attributes":{"vondstgewicht":{"cells":[
              {"labels":["Voorjaar"],"value":1.50}]}}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals(tree("""
            {"cells":[{"labels":["voorjaar"],"value":1.50},{"labels":["zomer"],"value":null},
                      {"labels":["najaar"],"value":3.00}]}"""), find(tree(resp.body()), "J1", "vondstgewicht"),
                resp.body());
    }

    /** Labels that name no cell of the attribute: uchar's input step refuses them as EF-034 before any session, as
     *  uchar's replay of the same input does, and the 422 gives them as CELL_FAULT with the sentence of that replay. */
    @Test
    void labels_that_name_no_cell_are_422_cell_fault(@TempDir Path map) throws Exception {
        assertCellFault(map, """
            {"instances":[{"type":"Jutter","id":"J1","attributes":{"vondstgewicht":{"cells":[
              {"labels":["voorjaar"],"value":1.50},{"labels":["winter"],"value":2.00}]}}}]}""",
                "J1.vondstgewicht: de labels [winter] noemen geen cel");
    }

    /** One cell given twice, here in two spellings of its label: uchar's input step refuses it as EF-035, and the 422
     *  gives it as CELL_FAULT with the sentence of uchar's replay. */
    @Test
    void a_cell_given_twice_is_422_cell_fault(@TempDir Path map) throws Exception {
        assertCellFault(map, """
            {"instances":[{"type":"Jutter","id":"J1","attributes":{"vondstgewicht":{"cells":[
              {"labels":["voorjaar"],"value":1.50},{"labels":["Voorjaar"],"value":2.00}]}}}]}""",
                "J1.vondstgewicht: de cel [Voorjaar] staat twee keer in de invoer");
    }

    /** 1,005 in a cell of two decimals: uchar's input fence refuses it as EF-004 with the cell as its place, and the
     *  422 gives it as INPUT_PRECISION_FAULT with the sentence of uchar's replay. */
    @Test
    void a_cell_value_that_does_not_fit_is_422_input_precision_fault(@TempDir Path map) throws Exception {
        String request = """
            {"instances":[{"type":"Jutter","id":"J1","attributes":{"vondstgewicht":{"cells":[
              {"labels":["voorjaar"],"value":1.005}]}}}]}""";
        CaseRunner.InputPrecisionFault replay = assertInstanceOf(CaseRunner.InputPrecisionFault.class,
                replay(map, CELL_SOURCE, request));
        assertEquals(Map.of("place", "J1.vondstgewicht[voorjaar]", "value", "1.005", "decimals", "2"), replay.params());
        JsonNode body = evaluationFault(post(cellService, "/seizoen", request));
        assertEquals(List.of("INPUT_PRECISION_FAULT"), kinds(body), body.toString());
        assertEquals(List.of(replay.getMessage()), sentences(body), body.toString());
    }

    /** A request that does not give a dimensioned attribute as cells, or gives cells to an attribute without
     *  dimensions, or a cell without labels, does not validate: a 400 naming each field. */
    @Test
    void a_dimensioned_attribute_takes_cells_and_nothing_else() throws Exception {
        HttpResponse<String> resp = post(cellService, "/seizoen", """
            {"instances":[{"type":"Jutter","id":"J1","attributes":{"vondstgewicht":1.50}},
              {"type":"Jutter","id":"J2","attributes":{"jaargewicht":{"cells":[{"labels":["voorjaar"],"value":1.50}]}}},
              {"type":"Jutter","id":"J3","attributes":{"vondstgewicht":{"cells":[{"value":1.50}]}}}]}""");
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode violations = tree(resp.body()).get("violations");
        assertEquals(List.of("WRONG_TYPE instances[0].attributes.vondstgewicht",
                        "WRONG_TYPE instances[1].attributes.jaargewicht",
                        "MALFORMED instances[2].attributes.vondstgewicht.cells[0].labels"),
                violations.valueStream().map(v -> v.get("kind").asText() + " " + v.get("field").asText()).toList(),
                resp.body());
    }

    /** The 422 of {@code request} on the cell model gives one CELL_FAULT with the sentence uchar's replay throws on the
     *  same input, which begins with {@code begin}. */
    private static void assertCellFault(Path map, String request, String begin) throws Exception {
        RuntimeException replay = assertInstanceOf(IllegalArgumentException.class, replay(map, CELL_SOURCE, request));
        assertTrue(replay.getMessage().startsWith(begin), replay::toString);
        JsonNode body = evaluationFault(post(cellService, "/seizoen", request));
        assertEquals(List.of("CELL_FAULT"), kinds(body), body.toString());
        assertEquals(List.of(replay.getMessage()), sentences(body), body.toString());
    }

    // ---------------- a fault at the fixpoint of another type: OTHER ----------------

    /** uchar's case D2 ({@code TmsCreationWitnessTest.a_second_filling_of_a_to_one_role_is_a_fault}): the fact
     *  creation gives J1 two havens, and uchar puts that fault at the fixpoint with a plain
     *  {@code IllegalStateException}, outside the five types its README names. It is an evaluation fault by its place
     *  there, so the 422 gives it as OTHER, with the sentence uchar's replay gives on the same input. */
    @Test
    void a_second_filling_from_a_to_a_role_is_422_other(@TempDir Path map) throws Exception {
        List<String> replay = replayFaults(map, FILLING_SOURCE, FILLING);
        assertEquals(1, replay.size(), replay::toString);
        assertTrue(replay.get(0).startsWith("de naar-één-rol \"haven\" van \"J1\" heeft twee verschillende"
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
        assertEquals("enkelvoudige rol 'aanmelder' krijgt een tweede, ander doel — ref weigert die case bij het laden (WF-013)",
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
                else if (a.getValue().has("cells")) {
                    ArrayNode cells = attributes.putObject(a.getKey()).putArray("cells");
                    for (JsonNode c : a.getValue().get("cells")) {
                        ObjectNode cell = cells.addObject();
                        cell.set("labels", c.get("labels"));
                        cell.putObject("value").put("number", c.get("value").asText());
                    }
                }
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
