package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every answer of a strict rule set holds, over the cases of uchar's strict goldens and of {@link OpenValuesSource}, as
 * given and with each given input member left out: a member that is open in the answer has a sentence when a form writes it,
 * stands in {@code openInputs} under at least one input member, and giving any of those changes the outcome or the members
 * that keep it open; a member that is not open stands under none.
 */
class OpenInputsPropertyTest {

    private static final RequestOptions ASKED = new RequestOptions(true, null, null, false, false, false, false, true);

    @Test
    void the_strict_goldens() throws Exception {
        int checked = 0;
        List<String> goldens = List.of(
                "jutterswet-afleiden-uit-leeg",
                "jutterswet-volledig-gegeven",
                "jutterswet-zelfde-conclusie-waarde",
                "jutterswet-zelfde-conclusie-kenmerk",
                "jutterswet-zelfde-conclusie-ongeordend",
                "jutterswet-zelfde-conclusie-genest",
                "jutterswet-zelfde-conclusie-deponering",
                "jutterswet-zelfde-conclusie-initialisatie",
                "jutterswet-zelfde-conclusie-fout");
        for (String golden : goldens) {
            Path dir = TokaSource.REPO.resolve("uchar/corpus/golden").resolve(golden);
            DecisionCore core = core(dir);
            List<Path> cases;
            try (Stream<Path> files = Files.list(dir)) {
                cases = files.filter(f -> f.getFileName().toString().matches("case.*\\.json")).sorted().toList();
            }
            assertFalse(cases.isEmpty(), golden);
            boolean faultOnInput = golden.equals("jutterswet-zelfde-conclusie-fout");
            for (Path c : cases)
                checked += leavingOneOut(core, "replay", request(DescriptorLoader.MAPPER.readTree(c.toFile())), faultOnInput);
        }
        assertTrue(checked >= 100, "open uitkomsten gezien: " + checked);
    }

    @Test
    void the_rule_sets_of_the_tests() throws Exception {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict());
        int checked = leavingOneOut(core, "vondst", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1", "V2"]}},
              {"type": "Vondst", "id": "V1", "attributes": {"taxatie": 50, "toeslag": 3, "herkomst": 4},
               "characteristics": {"betwist": {"state": "holdsNot"}, "onleesbaar": {"state": "holds"}}},
              {"type": "Vondst", "id": "V2", "attributes": {"taxatie": 1500},
               "characteristics": {"betwist": {"state": "holds"}}}]}""");
        core = OpenValuesSource.wrak(CoreConfig.strict());
        checked += leavingOneOut(core, "wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "attributes": {"bonus": 4},
                            "roles": {"gevonden wrakken": ["W1", "W2", "W3"]}},
              {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 30},
               "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holds"},
                                   "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holds"}}},
              {"type": "Wrak", "id": "W2", "attributes": {"diepte": 5, "lading": 150, "lengte": 2},
               "characteristics": {"gemeld": {"state": "holdsNot"}, "betwist": {"state": "holdsNot"},
                                   "beschadigd": {"state": "holds"}, "gemerkt": {"state": "holds"}}},
              {"type": "Wrak", "id": "W3", "attributes": {"diepte": 12, "lading": 8, "lengte": 0, "voorschot": 7},
               "characteristics": {"gemeld": {"state": "holdsNot"}, "betwist": {"state": "holdsNot"},
                                   "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holdsNot"}}}]}""");
        // nothing given at all, and one wreck that is
        checked += holds(core, "wrak", read("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1", "W2"]}},
              {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5}}, {"type": "Wrak", "id": "W2"}]}"""));
        checked += holds(core, "wrak", read("{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}"));
        assertTrue(checked >= 100, "open uitkomsten gezien: " + checked);
    }

    /** The property on {@code request} and on it without each member it gives. */
    private static int leavingOneOut(DecisionCore core, String path, String request) throws Exception {
        return leavingOneOut(core, path, request, false);
    }

    private static int leavingOneOut(DecisionCore core, String path, String request, boolean faultOnInput) throws Exception {
        JsonNode full = read(request);
        int checked = holds(core, path, full, faultOnInput);
        for (int i = 0; i < full.get("instances").size(); i++)
            for (String list : List.of("attributes", "characteristics")) {
                JsonNode members = full.get("instances").get(i).get(list);
                if (members == null) continue;
                for (String name : (Iterable<String>) members::fieldNames) {
                    JsonNode less = full.deepCopy();
                    ((ObjectNode) less.get("instances").get(i).get(list)).remove(name);
                    checked += holds(core, path, less, faultOnInput);
                }
            }
        return checked;
    }

    /** The property on one request; returns the number of open outcomes of its answer. */
    private static int holds(DecisionCore core, String path, JsonNode request) throws Exception {
        return holds(core, path, request, false);
    }

    private static int holds(DecisionCore core, String path, JsonNode request, boolean faultOnInput) throws Exception {
        ObjectNode answer = core.process(path, request, ASKED);
        Map<String, Set<String>> inputs = inputsPerOutcome(answer);
        Set<String> open = new TreeSet<>(), states = new TreeSet<>();
        outcomes(answer).forEach((outcome, state) -> {
            states.add(outcome);
            if (state.equals("open")) open.add(outcome);
        });
        assertEquals(open, inputs.keySet(), request.toString());
        for (String outcome : open) {
            String[] member = outcome.split("\\|");
            String instance = member[0], kind = member[1], name = member[2];
            ObjectType type = core.model().objectTypeByName(type(request, instance));
            if (dev.uchar.regelspraak.toolchain.engine.OpenReads.written(
                    core.entrypoint(path).document().model(), core.entrypoint(path).document().groups(),
                    core.entrypoint(path).document().model().objectTypeByName(type.name), name,
                    kind.equals("characteristic"))) {
                String head = Pattern.quote(instance) + (kind.equals("attribute")
                        ? ": zijn " + Pattern.quote(name) + " staat niet vast, want .*"
                        : ": of hij " + Pattern.quote(name) + " (is|heeft), staat niet vast, want .*");
                boolean spoken = false;
                for (JsonNode z : answer.get("explanation")) spoken |= z.asText().matches(head);
                assertTrue(spoken, head + "\nniet in " + answer.get("explanation"));
            }
            for (String input : inputs.get(outcome)) {
                ObjectNode after;
                try {
                    after = core.process(path, with(request, input, "holdsNot"), ASKED);
                } catch (EvaluationFault fault) {
                    if (!faultOnInput) throw fault;
                    // a provisional fault uncovered by giving the input changes the outcome; giving "holds" settles it
                    after = core.process(path, with(request, input, "holds"), ASKED);
                }
                String state = outcomes(after).get(outcome);
                if (state.equals("open"))
                    assertNotEquals(inputs.get(outcome), inputsPerOutcome(after).get(outcome),
                            () -> input + " gegeven verandert niets aan " + outcome + " van " + request);
            }
        }
        return open.size();
    }

    /** {@code instance|kind|name} of every outcome of {@code answer}, with its state. */
    private static Map<String, String> outcomes(JsonNode answer) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode i : answer.get("instances"))
            for (String list : List.of("attributes", "characteristics"))
                if (i.has(list))
                    i.get(list).fields().forEachRemaining(e -> out.put(i.get("id").asText() + "|"
                            + list.substring(0, list.length() - 1) + "|" + e.getKey(), e.getValue().get("state").asText()));
        return out;
    }

    /** Per outcome the input members under which {@code openInputs} names it. */
    private static Map<String, Set<String>> inputsPerOutcome(JsonNode answer) {
        Map<String, Set<String>> out = new java.util.TreeMap<>();
        Set<String> seen = new TreeSet<>();
        for (JsonNode i : answer.get("openInputs")) {
            String input = key(i);
            assertTrue(seen.add(input), input + " staat er twee keer");
            assertFalse(i.get("forOutputs").isEmpty(), i.toString());
            for (JsonNode o : i.get("forOutputs")) assertTrue(out.computeIfAbsent(key(o), k -> new TreeSet<>()).add(input));
        }
        return out;
    }

    private static String key(JsonNode member) {
        return member.get("instance").asText() + "|" + member.get("kind").asText() + "|" + member.get("name").asText();
    }

    /** {@code request} with {@code input} given: 3 for an attribute, {@code charState} for a characteristic. */
    private static JsonNode with(JsonNode request, String input, String charState) {
        String[] member = input.split("\\|");
        ObjectNode out = request.deepCopy();
        for (JsonNode i : out.get("instances"))
            if (i.get("id").asText().equals(member[0])) {
                boolean attribute = member[1].equals("attribute");
                ObjectNode list = ((ObjectNode) i).withObject(attribute ? "/attributes" : "/characteristics");
                if (attribute) list.put(member[2], 3);
                else list.putObject(member[2]).put("state", charState);
            }
        return out;
    }

    private static String type(JsonNode request, String instance) {
        for (JsonNode i : request.get("instances")) if (i.get("id").asText().equals(instance)) return i.get("type").asText();
        throw new AssertionError(instance);
    }

    /** A case of a strict golden as a request. */
    private static String request(JsonNode c) {
        ObjectNode request = DescriptorLoader.MAPPER.createObjectNode();
        ArrayNode instances = request.putArray("instances");
        for (JsonNode i : c.at("/input/instances")) {
            ObjectNode n = instances.addObject().put("type", i.get("objectType").asText()).put("id", i.get("label").asText());
            ObjectNode attributes = n.putObject("attributes"), characteristics = n.putObject("characteristics");
            i.get("attributes").fields().forEachRemaining(e -> attributes.put(e.getKey(),
                    new java.math.BigDecimal(e.getValue().get("number").asText())));
            for (JsonNode k : i.get("characteristics")) characteristics.putObject(k.asText()).put("state", "holds");
            for (JsonNode k : i.path("characteristicsAbsent")) characteristics.putObject(k.asText()).put("state", "holdsNot");
        }
        return request.toString();
    }

    /** A service of a golden directory ({@code source.rgs}, or {@code ir.json} where the golden has no source). */
    private static DecisionCore core(Path dir) throws Exception {
        Path source = dir.resolve("source.rgs");
        if (Files.exists(source)) return core(Files.readString(source));
        String documentJson = Files.readString(dir.resolve("ir.json"));
        JsonNode document = DescriptorLoader.MAPPER.readTree(documentJson);
        return DecisionCore.buildFromDocument(descriptor(document), documentJson, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
    }

    /** A service of {@code source} with every form in its scope and every member in its output. */
    private static DecisionCore core(String source) throws Exception {
        JsonNode document = DescriptorLoader.MAPPER.readTree(dev.uchar.regelspraak.toolchain.engine.UcharBuild.document(
                dev.uchar.regelspraak.resolver.Resolver.resolve(source)));
        return DecisionCore.assemble(descriptor(document), Map.of("b.rgs", source), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static String descriptor(JsonNode document) {
        List<String> forms = new ArrayList<>(), output = new ArrayList<>();
        for (String list : List.of("rules", "prioritizedDefaults"))
            for (JsonNode f : document.at("/rules/" + list)) forms.add(f.get("name").asText());
        for (JsonNode t : document.at("/model/objectTypes")) {
            for (JsonNode a : t.get("attributes")) output.add(t.get("name").asText() + "." + a.get("name").asText());
            for (JsonNode k : t.path("characteristics")) output.add(t.get("name").asText() + ":" + k.get("name").asText());
        }
        ObjectNode descriptor = DescriptorLoader.MAPPER.createObjectNode();
        descriptor.putObject("service").put("name", "replay").put("version", "1");
        ObjectNode ep = descriptor.putArray("entrypoints").addObject().put("name", "replay").put("path", "replay");
        ArrayNode names = ep.putObject("scope").putArray("ruleNames");
        forms.forEach(names::add);
        ep.putArray("input");
        ArrayNode members = ep.putArray("output");
        output.forEach(members::add);
        descriptor.putObject("parameters");
        return descriptor.toString();
    }

    private static JsonNode read(String text) throws Exception {
        return DescriptorLoader.MAPPER.readTree(text);
    }
}
