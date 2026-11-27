package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.FactType;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.OriginForm;
import dev.uchar.regelspraak.model.RegelSpraakModel.Role;
import dev.uchar.regelspraak.toolchain.engine.Naming;

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
 * given and with each given input member left out: every open member in the answer has a sentence when a form writes it, and
 * the open outcomes are the disjoint union of (a) the outcomes in {@code openInputs} (each under at least one input member,
 * and giving any of those changes the outcome or the members that keep it open) and (b) the suppressed opgave check members
 * ({@code berekende X}, {@code afwijkende opgave X}) whose marked attribute {@code X} on the same instance is not open; a
 * member that is not open stands under none.
 */
class OpenInputsPropertyTest {

    private static final RequestOptions ASKED = new RequestOptions(true, null, null, false, false, true, false, true);

    private record Counts(int open, int suppressed) {
        Counts plus(Counts other) { return new Counts(open + other.open, suppressed + other.suppressed); }
    }

    @Test
    void the_strict_goldens() throws Exception {
        Counts total = new Counts(0, 0);
        List<String> goldens = List.of(
                "jutterswet-afleiden-uit-leeg",
                "jutterswet-volledig-gegeven",
                "jutterswet-invoer-leeg-strikt",
                "jutterswet-invoer-rol-strikt",
                "jutterswet-invoer-collectie-strikt",
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
                total = total.plus(leavingOneOut(core, "replay",
                        request(DescriptorLoader.MAPPER.readTree(c.toFile()), core.model()), faultOnInput));
        }
        assertTrue(total.open() >= 100, "open uitkomsten gezien: " + total.open());
        assertTrue(total.suppressed() > 0, "onderdrukte opgave-controleleden gezien: " + total.suppressed());
    }

    @Test
    void the_rule_sets_of_the_tests() throws Exception {
        DecisionCore core = OpenValuesSource.core(CoreConfig.strict().withDetail());
        Counts total = leavingOneOut(core, "vondst", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": {"state": "closed", "items": ["V1", "V2"]}}},
              {"type": "Vondst", "id": "V1", "attributes": {"taxatie": 50, "toeslag": 3, "herkomst": 4},
               "characteristics": {"betwist": {"state": "holdsNot"}, "onleesbaar": {"state": "holds"}}},
              {"type": "Vondst", "id": "V2", "attributes": {"taxatie": 1500},
               "characteristics": {"betwist": {"state": "holds"}}}]}""");
        core = OpenValuesSource.wrak(CoreConfig.strict().withDetail());
        total = total.plus(leavingOneOut(core, "wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "attributes": {"bonus": 4},
                            "roles": {"gevonden wrakken": {"state": "closed", "items": ["W1", "W2", "W3"]}}},
              {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 30},
               "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holds"},
                                   "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holds"}}},
              {"type": "Wrak", "id": "W2", "attributes": {"diepte": 5, "lading": 150, "lengte": 2},
               "characteristics": {"gemeld": {"state": "holdsNot"}, "betwist": {"state": "holdsNot"},
                                   "beschadigd": {"state": "holds"}, "gemerkt": {"state": "holds"}}},
              {"type": "Wrak", "id": "W3", "attributes": {"diepte": 12, "lading": 8, "lengte": 0, "voorschot": 7},
               "characteristics": {"gemeld": {"state": "holdsNot"}, "betwist": {"state": "holdsNot"},
                                   "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holdsNot"}}}]}"""));
        total = total.plus(leavingOneOut(core, "wrak", """
            {"instances": [{"type": "Jutter", "id": "J1", "attributes": {"bonus": 4},
                            "roles": {"gevonden wrakken": {"state": "open", "items": ["W1"]}}},
              {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 30},
               "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holdsNot"},
                                   "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holds"}}}]}"""));
        // nothing given at all, and one wreck that is
        total = total.plus(holds(core, "wrak", read("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": {"state": "closed", "items": ["W1", "W2"]}}},
              {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5}}, {"type": "Wrak", "id": "W2"}]}""")));
        total = total.plus(holds(core, "wrak", read("{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}")));

        // WRAK_SOURCE with berekende kadegeld and afwijkende opgave kadegeld in output, both with and without Wrak.kadegeld
        for (String replacement : List.of(
                "\"Wrak.kadegeld\", \"Wrak.berekende kadegeld\", \"Wrak:afwijkende opgave kadegeld\"",
                "\"Wrak.berekende kadegeld\", \"Wrak:afwijkende opgave kadegeld\"")) {
            String descriptor = OpenValuesSource.WRAK_DESCRIPTOR.replace("\"Wrak.kadegeld\"", replacement);
            DecisionCore wrakOpgave = DecisionCore.assemble(descriptor, Map.of("wrakken.rgs", OpenValuesSource.WRAK_SOURCE),
                    CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
            total = total.plus(leavingOneOut(wrakOpgave, "wrak", """
                {"instances": [{"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 10,
                                "opgegeven kadegeld": 100},
                   "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holdsNot"},
                                       "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holdsNot"}}}]}"""));
            total = total.plus(holds(wrakOpgave, "wrak", read("{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}")));
        }

        assertTrue(total.open() >= 100, "open uitkomsten gezien: " + total.open());
        assertTrue(total.suppressed() > 0, "onderdrukte opgave-controleleden gezien: " + total.suppressed());
    }

    /** The property on {@code request} and on it without each member it gives. */
    private static Counts leavingOneOut(DecisionCore core, String path, String request) throws Exception {
        return leavingOneOut(core, path, request, false);
    }

    private static Counts leavingOneOut(DecisionCore core, String path, String request, boolean faultOnInput) throws Exception {
        JsonNode full = read(request);
        Counts checked = holds(core, path, full, faultOnInput);
        for (int i = 0; i < full.get("instances").size(); i++)
            for (String list : List.of("attributes", "characteristics", "roles")) {
                JsonNode members = full.get("instances").get(i).get(list);
                if (members == null) continue;
                for (String name : (Iterable<String>) members::fieldNames) {
                    JsonNode less = full.deepCopy();
                    ((ObjectNode) less.get("instances").get(i).get(list)).remove(name);
                    checked = checked.plus(holds(core, path, less, faultOnInput));
                }
            }
        return checked;
    }

    /** The property on one request; returns the number of open and suppressed outcomes of its answer. */
    private static Counts holds(DecisionCore core, String path, JsonNode request) throws Exception {
        return holds(core, path, request, false);
    }

    private static Counts holds(DecisionCore core, String path, JsonNode request, boolean faultOnInput) throws Exception {
        ObjectNode answer = core.process(path, request, ASKED);
        Map<String, Set<String>> inputs = inputsPerOutcome(answer);
        Map<String, String> allStates = outcomes(answer);
        Set<String> open = new TreeSet<>(), suppressed = new TreeSet<>();
        allStates.forEach((outcome, state) -> {
            if (!state.equals("open")) return;
            open.add(outcome);
            String[] member = outcome.split("\\|");
            ObjectType type = core.model().objectTypeByName(type(request, member[0]));
            Designatable decl = member[1].equals("characteristic")
                    ? Naming.characteristicOf(type, member[2])
                    : Naming.attributeOf(type, member[2]);
            core.model().generatedFrom(decl)
                    .filter(g -> g.form() == OriginForm.OPGAVE_BOVEN_BEREKENING
                            && g.source().kind() == Designatable.Kind.ATTRIBUUT
                            && hasAttributeOnChain(type, g.source())
                            && !attributeOpenInFixpoint(answer, member[0], g.source().name()))
                    .ifPresent(g -> suppressed.add(outcome));
        });
        for (String s : suppressed)
            assertFalse(inputs.containsKey(s), () -> s + " is onderdrukt maar staat in openInputs: " + request);
        Set<String> expectedOpen = new TreeSet<>(inputs.keySet());
        expectedOpen.addAll(suppressed);
        assertEquals(open, expectedOpen, request.toString());
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
            if (suppressed.contains(outcome)) continue;
            for (String input : inputs.get(outcome)) {
                ObjectNode after;
                try {
                    after = core.process(path, with(core.model(), request, input, "holdsNot"), ASKED);
                } catch (EvaluationFault fault) {
                    if (!faultOnInput) throw fault;
                    // a provisional fault uncovered by giving the input changes the outcome; giving "holds" settles it
                    after = core.process(path, with(core.model(), request, input, "holds"), ASKED);
                }
                String state = outcomes(after).get(outcome);
                if (state.equals("open"))
                    assertNotEquals(inputs.get(outcome), inputsPerOutcome(after).get(outcome),
                            () -> input + " gegeven verandert niets aan " + outcome + " van " + request);
            }
        }
        return new Counts(open.size(), suppressed.size());
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
        if ("role".equals(member.get("kind").asText())) {
            assertTrue(member.hasNonNull("factType") && !member.get("factType").asText().isBlank(), member.toString());
            return member.get("instance").asText() + "|role|" + member.get("factType").asText() + "|"
                    + member.get("name").asText();
        }
        assertFalse(member.has("factType"), member.toString());
        return member.get("instance").asText() + "|" + member.get("kind").asText() + "|" + member.get("name").asText();
    }

    /** {@code request} with {@code input} given: 3 for an attribute, {@code charState} for a characteristic, and for an open
     *  role either closing an existing {@code "open"} collection on that role slot or setting {@code {"state": "empty"}}. */
    private static JsonNode with(RegelSpraakModel model, JsonNode request, String input, String charState) {
        String[] member = input.split("\\|");
        ObjectNode out = request.deepCopy();
        NameReader reader = new NameReader(model, null);
        for (JsonNode i : out.get("instances"))
            if (i.get("id").asText().equals(member[0])) {
                if (member[1].equals("attribute")) {
                    ((ObjectNode) i).withObject("/attributes").put(member[2], 3);
                } else if (member[1].equals("characteristic")) {
                    ((ObjectNode) i).withObject("/characteristics").putObject(member[2]).put("state", charState);
                } else {
                    ObjectType ot = model.objectTypeByName(i.get("type").asText());
                    ObjectNode roles = ((ObjectNode) i).withObject("/roles");
                    String existingKey = null;
                    for (String k : (Iterable<String>) roles::fieldNames) {
                        if (reader.roleFrom(ot, k) instanceof NameReader.Found g
                                && DescriptorLoader.sameName(g.declaration().owner(), member[2])
                                && DescriptorLoader.sameName(g.declaration().name(), member[3])) {
                            existingKey = k;
                            break;
                        }
                    }
                    if (existingKey != null && roles.get(existingKey).isObject()
                            && OpenValues.OPEN.equals(roles.get(existingKey).path("state").asText())) {
                        ((ObjectNode) roles.get(existingKey)).put("state", OpenValues.CLOSED);
                    } else {
                        roles.set(existingKey != null ? existingKey : member[3], OpenValues.state(OpenValues.EMPTY));
                    }
                }
            }
        return out;
    }

    private static String type(JsonNode request, String instance) {
        for (JsonNode i : request.get("instances")) if (i.get("id").asText().equals(instance)) return i.get("type").asText();
        throw new AssertionError(instance);
    }

    private record ResolvedOppositeRole(FactType factType, int roleIndex, Role role) {}

    private static ResolvedOppositeRole resolveOppositeRole(RegelSpraakModel model, ObjectType ot, String key) {
        if (ot == null) return null;
        NameReader reader = new NameReader(model, Naming.CANONICAL);
        if (!(reader.roleFrom(ot, key) instanceof NameReader.Found g)) return null;
        for (FactType ft : model.factTypes.values()) {
            if (!DescriptorLoader.sameName(ft.name, g.declaration().owner())) continue;
            for (int idx = 0; idx < ft.roles.size(); idx++) {
                Role r = ft.roles.get(idx);
                if (DescriptorLoader.sameName(r.name(), g.declaration().name()) && Naming.reachable(model, ft, r, ot))
                    return new ResolvedOppositeRole(ft, idx, r);
            }
        }
        return null;
    }

    /** A case of a strict golden as a request. */
    private static String request(JsonNode c, RegelSpraakModel model) {
        ObjectNode request = DescriptorLoader.MAPPER.createObjectNode();
        ArrayNode instances = request.putArray("instances");
        for (JsonNode i : c.at("/input/instances")) {
            ObjectType ot = model.objectTypeByName(i.get("objectType").asText());
            ObjectNode n = instances.addObject().put("type", i.get("objectType").asText()).put("id", i.get("label").asText());
            ObjectNode attributes = n.putObject("attributes"), characteristics = n.putObject("characteristics");
            i.path("attributes").fields().forEachRemaining(e -> attributes.put(e.getKey(),
                    new java.math.BigDecimal(e.getValue().get("number").asText())));
            for (JsonNode a : i.path("attributesEmpty")) attributes.putObject(a.asText()).put("state", "empty");
            for (JsonNode k : i.path("characteristics")) characteristics.putObject(k.asText()).put("state", "holds");
            for (JsonNode k : i.path("characteristicsAbsent")) characteristics.putObject(k.asText()).put("state", "holdsNot");
            ObjectNode roles = DescriptorLoader.MAPPER.createObjectNode();
            Set<FixpointGivens.EmptyRole> closedSlots = new java.util.HashSet<>();
            for (JsonNode cr : i.path("rolesClosed")) {
                ResolvedOppositeRole hit = resolveOppositeRole(model, ot, cr.asText());
                if (hit != null)
                    closedSlots.add(new FixpointGivens.EmptyRole(hit.factType().name, hit.roleIndex()));
            }
            Set<FixpointGivens.EmptyRole> handledClosed = new java.util.HashSet<>();
            i.path("roles").fields().forEachRemaining(e -> {
                ArrayNode targets = DescriptorLoader.MAPPER.createArrayNode();
                e.getValue().forEach(t -> targets.add(t.asText()));
                ResolvedOppositeRole hit = resolveOppositeRole(model, ot, e.getKey());
                if (hit != null && !hit.factType().reciprocal && hit.role().many()) {
                    FixpointGivens.EmptyRole slot = new FixpointGivens.EmptyRole(hit.factType().name, hit.roleIndex());
                    if (hit.role().complete()) {
                        handledClosed.add(slot);
                        roles.set(e.getKey(), targets);
                    } else if (closedSlots.contains(slot)) {
                        handledClosed.add(slot);
                        roles.set(e.getKey(), OpenValues.collection(OpenValues.CLOSED, targets));
                    } else {
                        roles.set(e.getKey(), OpenValues.collection(OpenValues.OPEN, targets));
                    }
                } else {
                    roles.set(e.getKey(), targets);
                }
            });
            for (JsonNode r : i.path("rolesEmpty")) roles.putObject(r.asText()).put("state", "empty");
            for (JsonNode cr : i.path("rolesClosed")) {
                ResolvedOppositeRole hit = resolveOppositeRole(model, ot, cr.asText());
                FixpointGivens.EmptyRole slot = hit != null
                        ? new FixpointGivens.EmptyRole(hit.factType().name, hit.roleIndex()) : null;
                if (slot == null || handledClosed.add(slot))
                    roles.putObject(cr.asText()).put("state", "empty");
            }
            if (!roles.isEmpty()) n.set("roles", roles);
        }
        return request.toString();
    }

    private static boolean hasAttributeOnChain(ObjectType type, Designatable attribute) {
        if (type == null || attribute.owner() == null || attribute.name() == null) return false;
        Designatable found = DescriptorLoader.attribute(type, attribute.name());
        return found != null && DescriptorLoader.sameName(found.owner(), attribute.owner());
    }

    private static boolean attributeOpenInFixpoint(JsonNode answer, String instance, String attribute) {
        for (JsonNode o : answer.at("/fixpoint/objects"))
            if (o.get("object").asText().equals(instance)) {
                for (JsonNode m : o.get("open"))
                    if (m.has("attribute") && DescriptorLoader.sameName(m.get("attribute").asText(), attribute)) return true;
                return false;
            }
        throw new AssertionError(instance);
    }

    /** A service of a golden directory ({@code source.rgs}, or {@code ir.json} where the golden has no source). */
    private static DecisionCore core(Path dir) throws Exception {
        Path source = dir.resolve("source.rgs");
        if (Files.exists(source)) return core(Files.readString(source));
        String documentJson = Files.readString(dir.resolve("ir.json"));
        JsonNode document = DescriptorLoader.MAPPER.readTree(documentJson);
        return DecisionCore.buildFromDocument(descriptor(document), documentJson, CoreConfig.strict().withDetail(),
                Thread.currentThread().getContextClassLoader(), null);
    }

    /** A service of {@code source} with every form in its scope and every member in its output. */
    private static DecisionCore core(String source) throws Exception {
        JsonNode document = DescriptorLoader.MAPPER.readTree(dev.uchar.regelspraak.toolchain.engine.UcharBuild.document(
                dev.uchar.regelspraak.resolver.Resolver.resolve(source)));
        return DecisionCore.assemble(descriptor(document), Map.of("b.rgs", source), CoreConfig.strict().withDetail(),
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
