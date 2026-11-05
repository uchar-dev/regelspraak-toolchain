package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test document with absence markers in the playground (uchar's RUNTIME-SEMANTICS ch. 9.5; a marker as the value of
 * a situation and an expectation since uchar's card {@code absence-markers-residue}): uchar's scenario resolver gives a
 * value of an attribute with markers as a constructor value, a marker without a payload and any other value as the
 * payload of {@code waarde}. The request of {@code ?via=service} gives a marker as {@code {"marker": <naam>}} and a value
 * as its payload, also in a segment and in a cell, as the request of the service takes them; the situation and the
 * fixpoint of the playground give them in that form. On the goldens {@code jutterswet-afwezigheidsmarkers}, {@code -cel}
 * and {@code -creatie}, with their {@code scenario.rgs}, every scenario passes on the ordinary route, as uchar's replay
 * plays the case files the lowering writes, and via the service with the same expectations, situation, fixpoint and
 * explanation.
 */
class PlaygroundAbsenceMarkersTest {

    private static final Path GOLDENS = TokaSource.REPO.resolve("uchar/corpus/golden");

    private static final String[] FORM_LISTS = {"rules", "objectCreations", "allocations", "consistencies",
            "uniquenesses", "factCreations", "prioritizedDefaults"};

    // ---------------- the goldens on both routes ----------------

    @Test
    void the_test_document_of_the_markers_golden_passes_on_both_routes() throws IOException {
        bothRoutesPass("jutterswet-afwezigheidsmarkers");
    }

    @Test
    void the_test_document_of_the_cell_golden_passes_on_both_routes() throws IOException {
        bothRoutesPass("jutterswet-afwezigheidsmarkers-cel");
    }

    @Test
    void the_test_document_of_the_creation_golden_passes_on_both_routes() throws IOException {
        bothRoutesPass("jutterswet-afwezigheidsmarkers-creatie");
    }

    /** Every scenario of the golden's test document passes on the ordinary route, one per case file of the golden, and
     *  via a service over its {@code source.rgs} with the same expectations, situation, fixpoint and explanation. */
    private static void bothRoutesPass(String golden) throws IOException {
        Path dir = GOLDENS.resolve(golden);
        String rgs = Files.readString(dir.resolve("source.rgs"));
        String source = rgs + "\n" + Files.readString(dir.resolve("scenario.rgs"));
        DecisionCore core = DecisionCore.assemble(descriptor(golden, dir), Map.of("source.rgs", rgs),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        long caseFiles;
        try (Stream<Path> s = Files.list(dir)) {
            caseFiles = s.filter(p -> p.getFileName().toString().matches("case.*\\.json")).count();
        }
        JsonNode ordinary = Playground.analyze(source, null).path("scenario").path("results");
        JsonNode service = Playground.analyze(source, null, core).path("scenario").path("results");
        assertEquals(caseFiles, ordinary.size(), ordinary.toString());
        assertEquals(caseFiles, service.size(), service.toString());
        for (int i = 0; i < caseFiles; i++) {
            JsonNode g = ordinary.get(i), d = service.get(i);
            String ctx = golden + " / " + g.path("name").asText();
            assertEquals("PASS", g.path("status").asText(), ctx + "\n" + g);
            assertEquals("PASS", d.path("status").asText(), ctx + "\n" + d);
            assertEquals("markers", d.path("path").asText(), ctx);
            assertEquals(g.path("expectations"), d.path("expectations"), ctx);
            assertEquals(g.path("situation"), d.path("situation"), ctx);
            assertEquals(g.path("fixpoint"), d.path("fixpoint"), ctx);
            assertEquals(g.path("explanation"), d.path("explanation"), ctx);
            assertEquals(g.path("explanationParts"), d.path("explanationParts"), ctx);
        }
    }

    // ---------------- the request ----------------

    /** A marker of the situation is a marker object of the request and a value is its payload, scalar, in a segment and
     *  in a cell. */
    @Test
    void a_marker_in_the_situation_is_a_marker_object_of_the_request() throws IOException {
        List<ResolvedScenario> markers = scenarios("jutterswet-afwezigheidsmarkers");
        assertEquals("{\"marker\":\"onbekend\"}", request(markers, "onbekend in de invoer", "/instances/0/attributes/taxatie"));
        assertEquals("40.00", request(markers, "onbekend verdringt invoer", "/instances/0/attributes/taxatie"));
        assertEquals("{\"timeline\":[{\"from\":\"2025-01-01\",\"to\":\"2026-01-01\",\"value\":10}]}",
                request(markers, "onbekend per maand", "/instances/0/attributes/opslagkosten"));

        List<ResolvedScenario> cell = scenarios("jutterswet-afwezigheidsmarkers-cel");
        assertEquals("{\"cells\":[{\"labels\":[\"najaar\"],\"value\":{\"marker\":\"onbekend\"}},{\"labels\":[\"voorjaar\"],"
                        + "\"value\":{\"marker\":\"onbekend\"}}]}",
                request(cell, "onbekend in de invoer", "/instances/0/attributes/gewicht"));
        assertEquals("{\"cells\":[{\"labels\":[\"voorjaar\"],\"value\":10.00}]}",
                request(cell, "onbekend verdringt invoer", "/instances/0/attributes/gewicht"));

        List<ResolvedScenario> creation = scenarios("jutterswet-afwezigheidsmarkers-creatie");
        assertEquals("{\"marker\":\"onbekend\"}",
                request(creation, "drijfhout verdringt onbekend in de invoer", "/instances/1/attributes/taxatie"));
    }

    /** A marker as the value of a segment of the situation goes as the value of that segment of the request, and the
     *  scenario passes on both routes. */
    @Test
    void a_marker_in_a_segment_of_the_situation_is_carried() throws IOException {
        Path dir = GOLDENS.resolve("jutterswet-afwezigheidsmarkers");
        String rgs = Files.readString(dir.resolve("source.rgs"));
        String source = rgs + "\n" + """
                Scenario niet van toepassing in een segment
                Gegeven is de volgende situatie:
                een Vondst V1
                - de opslagkosten is niet van toepassing van 01-03-2025 tot 01-05-2025
                Met als verwacht resultaat:
                - de opslagkosten van V1 is onbekend van 01-01-2025 tot 01-03-2025
                - de opslagkosten van V1 is niet van toepassing van 01-03-2025 tot 01-05-2025
                - de gegevens zijn consistent
                """;
        var bundle = ScenarioResolver.resolve(source);
        assertTrue(bundle.isOk(), () -> bundle.model().errors().toString());
        assertEquals("{\"timeline\":[{\"from\":\"2025-03-01\",\"to\":\"2025-05-01\",\"value\":{\"marker\":"
                        + "\"niet van toepassing\"}}]}",
                SituationToRequest.assemble(bundle.scenarios().get(0)).at("/instances/0/attributes/opslagkosten")
                        .toString());
        DecisionCore core = DecisionCore.assemble(descriptor("jutterswet-afwezigheidsmarkers", dir),
                Map.of("source.rgs", rgs), CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        JsonNode g = Playground.analyze(source, null).path("scenario").path("results").get(0);
        JsonNode d = Playground.analyze(source, null, core).path("scenario").path("results").get(0);
        assertEquals("PASS", g.path("status").asText(), g.toString());
        assertEquals("PASS", d.path("status").asText(), d.toString());
        assertEquals(g.path("expectations"), d.path("expectations"));
        assertEquals(g.path("situation"), d.path("situation"));
        assertEquals("[{\"from\":\"2025-03-01\",\"to\":\"2025-05-01\",\"value\":{\"marker\":\"niet van toepassing\"}}]",
                g.at("/situation/objects/0/attributes/0/timeline").toString());
    }

    /** A constructor with a payload other than {@code waarde}, which no attribute of uchar's RegelSpraak frontend holds,
     *  and a value without any variant are broken promises of uchar's scenario resolver: refused by name, never sent as
     *  an empty text. */
    @Test
    void a_value_of_no_known_form_is_refused_by_name() throws IOException {
        var other = ResolvedScenario.ScenarioValue.fromConstructor("schatting",
                ResolvedScenario.ScenarioValue.fromNumber("5"));
        var e = assertThrows(IllegalStateException.class, () -> SituationToRequest.valueToJson(other));
        assertTrue(e.getMessage().contains("'schatting'"), e.getMessage());
        var none = new ResolvedScenario.ScenarioValue(null, null, null, null, null, null, null, null);
        var f = assertThrows(IllegalStateException.class, () -> SituationToRequest.valueToJson(none));
        assertTrue(f.getMessage().contains("zonder variant"), f.getMessage());

        // the situation of the playground holds a value to the same forms
        var bundle = bundle("jutterswet-afwezigheidsmarkers");
        ResolvedScenario s = bundle.scenarios().get(0);
        ResolvedScenario.Instance v1 = s.situation().instances().get(0);
        ResolvedScenario broken = new ResolvedScenario(s.name(), s.scope(), s.asOfDate(), new ResolvedScenario.Situation(
                List.of(new ResolvedScenario.Instance(v1.label(), v1.objectType(),
                        List.of(new ResolvedScenario.AttributeValue("taxatie", other, null, null, 1)), List.of(),
                        List.of())), List.of()), s.expectations(), s.line());
        var g = assertThrows(IllegalStateException.class,
                () -> ScenarioData.situation(broken, bundle.model(), Naming.canonical(bundle.model())));
        assertTrue(g.getMessage().contains("'schatting'"), g.getMessage());
    }

    // ---------------- the situation and the fixpoint of the playground ----------------

    /** The situation of the playground gives a marker as {@code {"marker": <naam>}} under {@code value} and a value as
     *  its payload, scalar and per cell, as the request gives them; the fixpoint gives the input marker in that form. */
    @Test
    void the_situation_and_the_fixpoint_give_a_marker_as_a_marker() throws IOException {
        JsonNode markers = ordinary("jutterswet-afwezigheidsmarkers");
        JsonNode onbekend = result(markers, "onbekend in de invoer");
        assertEquals("{\"marker\":\"onbekend\"}", onbekend.at("/situation/objects/0/attributes/0/value").toString());
        assertEquals("\"40.00\"", result(markers, "onbekend verdringt invoer")
                .at("/situation/objects/0/attributes/0/value").toString());
        assertEquals(List.of("taxatie={\"marker\":\"onbekend\"} input"), rows(onbekend, "V1", "taxatie"));

        JsonNode cell = result(ordinary("jutterswet-afwezigheidsmarkers-cel"), "onbekend in de invoer");
        assertEquals("[{\"labels\":[\"najaar\"],\"value\":{\"marker\":\"onbekend\"}},{\"labels\":[\"voorjaar\"],"
                + "\"value\":{\"marker\":\"onbekend\"}}]", cell.at("/situation/objects/0/attributes/0/cells").toString());
        assertEquals(List.of("gewicht[\"voorjaar\"]={\"marker\":\"onbekend\"} input",
                "gewicht[\"najaar\"]={\"marker\":\"onbekend\"} input"), rows(cell, "V1", "gewicht"));
    }

    // ---------------- helpers ----------------

    /** A descriptor with one entry point {@code markers} that carries every form of the golden's {@code ir.json}, as
     *  the replay gate serves a golden. */
    private static String descriptor(String golden, Path dir) throws IOException {
        JsonNode rules = ServiceHttp.MAPPER.readTree(Files.readString(dir.resolve("ir.json"))).path("rules");
        List<String> names = new ArrayList<>();
        for (String list : FORM_LISTS)
            for (JsonNode form : rules.path(list))
                if (form.hasNonNull("name") && !names.contains(form.get("name").asText()))
                    names.add(form.get("name").asText());
        ObjectNode d = ServiceHttp.MAPPER.createObjectNode();
        d.putObject("service").put("name", golden).put("version", "1");
        ObjectNode ep = d.putArray("entrypoints").addObject().put("name", "markers").put("path", "markers");
        ArrayNode scope = ep.putObject("scope").putArray("ruleNames");
        names.forEach(scope::add);
        ep.putArray("input");
        ep.putArray("output");
        ep.put("scopeCheckOptOut", true);
        d.putObject("parameters");
        return d.toString();
    }

    private static ScenarioResolver.ScenarioBundle bundle(String golden) throws IOException {
        Path dir = GOLDENS.resolve(golden);
        var bundle = ScenarioResolver.resolve(Files.readString(dir.resolve("source.rgs")) + "\n"
                + Files.readString(dir.resolve("scenario.rgs")));
        assertTrue(bundle.isOk(), () -> bundle.model().errors().toString());
        return bundle;
    }

    private static List<ResolvedScenario> scenarios(String golden) throws IOException {
        return bundle(golden).scenarios();
    }

    private static String request(List<ResolvedScenario> scenarios, String name, String pointer) {
        ResolvedScenario s = scenarios.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        return SituationToRequest.assemble(s).at(pointer).toString();
    }

    private static JsonNode ordinary(String golden) throws IOException {
        Path dir = GOLDENS.resolve(golden);
        return Playground.analyze(Files.readString(dir.resolve("source.rgs")) + "\n"
                + Files.readString(dir.resolve("scenario.rgs")), null).path("scenario").path("results");
    }

    private static JsonNode result(JsonNode results, String name) {
        for (JsonNode r : results) if (name.equals(r.path("name").asText())) return r;
        throw new AssertionError("geen scenario '" + name + "' in " + results);
    }

    /** The rows of {@code attribute} of object {@code label} in the fixpoint of {@code result}, as
     *  {@code attribute[labels]=value origin}. */
    private static List<String> rows(JsonNode result, String label, String attribute) {
        List<String> out = new ArrayList<>();
        for (JsonNode o : result.at("/fixpoint/objects"))
            if (label.equals(o.path("object").asText()))
                for (JsonNode a : o.path("values"))
                    if (attribute.equals(a.path("attribute").asText()))
                        out.add(attribute + (a.has("labels") ? a.get("labels").toString() : "") + "=" + a.path("value")
                                + " " + a.path("origin").asText());
        return out;
    }
}
