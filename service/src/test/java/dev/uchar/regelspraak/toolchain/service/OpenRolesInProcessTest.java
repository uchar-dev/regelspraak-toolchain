package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process verification of open versus settled-empty non-reciprocal {@code to-one} roles in strict rule sets
 * ({@code Afleiden uit leeg: nee}): request mapping, {@code 400} violations, {@code 422} {@code EF-049}, vocabularies
 * and {@code ?inputVocabulary=}, {@code /info} {@code completeInput}, {@code ?openInputs=true}, {@code Verbaliser} and
 * {@code explanationParts}, {@code ?fixpoint=true}, OpenAPI emission, and replay of uchar's {@code jutterswet-invoer-rol*}
 * and {@code jutterswet-rol-gevuld-en-leeg} cases without binding loopback sockets.
 */
class OpenRolesInProcessTest {

    private static final RequestOptions ALL = new RequestOptions(true, null, null, true, true, true, true, true);

    private static final String STRICT_ROLES_SOURCE = """
        Afleiden uit leeg: nee

        Vocabulaire handboek (taal: nl)
        Vocabulaire register (taal: nl)

        Objecttype de Juttersploeg (mv: Juttersploegen) (handboek: Bergingsploeg)
            de ploegtoelage (handboek: ploegbonus) Numeriek (geheel getal);

        Objecttype de Keuring (mv: Keuringen)
            het keurcijfer Numeriek (geheel getal);

        Objecttype de Jutter (mv: Jutters) (bezield)
            de bonus (handboek: vindbonus) Numeriek (geheel getal);
            is ingezetene (handboek: kustbewoner) kenmerk (bijvoeglijk);

        Objecttype het Vaartuig (mv: Vaartuigen)
            de lengte Numeriek (geheel getal);
            de havenbonus Numeriek (geheel getal);

        Objecttype het Wrak is een Vaartuig (mv: Wrakken)
            de premie Numeriek (geheel getal);
            de ploegpremie Numeriek (geheel getal);
            de keursom Numeriek (geheel getal);
            is toegewezen kenmerk (bijvoeglijk);
            is vrijgesteld kenmerk (bijvoeglijk);
            is kustwrak kenmerk (bijvoeglijk);
            is uitwijkplichtig kenmerk (bijvoeglijk);

        Objecttype de Haven (mv: Havens)
            het haventarief Numeriek (geheel getal);

        Feittype wrakvondst (handboek: bergingsgeval)
            de vinder (mv: vinders) (handboek: melder) (register: aanmelder) Jutter
            het gevonden wrak (mv: gevonden wrakken) Wrak
            één vinder heeft meerdere gevonden wrakken

        Feittype ploegverband
            de ploeg (handboek: bergingsploegrol) Juttersploeg
            het ploeglid (mv: ploegleden) Jutter
            één ploeg heeft meerdere ploegleden

        Feittype ploegkeuring
            de gekeurde ploeg Juttersploeg
            de keuring (mv: keuringen) Keuring
            één gekeurde ploeg heeft meerdere keuringen

        Feittype thuishaven
            de haven (mv: thuishavens) (handboek: vaste haven) Haven (volledig gegeven)
            het vaartuig (mv: vaartuigen) Vaartuig
            één haven heeft meerdere vaartuigen

        Feittype noodhavenrelatie
            de haven (mv: noodhavens) (handboek: uitwijkhaven) Haven
            het noodwrak (mv: noodwrakken) Wrak
            één haven heeft meerdere noodwrakken

        Wederkerig feittype strandmaatschap
            de strandmaat (mv: strandmaten) Jutter
            één strandmaat heeft één strandmaat

        Regel premie
            geldig altijd
                De premie van een Wrak moet berekend worden als zijn lengte plus de bonus van de vinder van het Wrak.

        Regel ploegpremie
            geldig altijd
                De ploegpremie van een Wrak moet berekend worden als zijn lengte plus de ploegtoelage van de ploeg van de vinder van het Wrak.

        Regel keursom
            geldig altijd
                De keursom van een Wrak moet berekend worden als de som van het keurcijfer van alle keuringen van de ploeg van de vinder van het Wrak.

        Regel havenbonus
            geldig altijd
                De havenbonus van een Vaartuig moet berekend worden als zijn lengte plus het haventarief van de haven van het Vaartuig.

        Regel uitwijkplicht
            geldig altijd
                Een Wrak is uitwijkplichtig indien hij een noodwrak is.

        Regel toegewezen
            geldig altijd
                Een Wrak is toegewezen indien hij een gevonden wrak is.

        Regel vrijgesteld
            geldig altijd
                Een Wrak is vrijgesteld indien hij geen gevonden wrak is.

        Regel kustwrak
            geldig altijd
                Een Wrak is kustwrak indien zijn vinder ingezetene is.
        """;

    private static final String STRICT_ROLES_DESCRIPTOR = """
        { "service": {"name":"strikte-rollen","version":"1"},
          "entrypoints": [
            { "name":"wrak","path":"wrak",
              "scope": {"ruleNames": ["premie", "ploegpremie", "keursom", "havenbonus", "uitwijkplicht",
                                      "toegewezen", "vrijgesteld", "kustwrak"]},
              "input": [],
              "output": ["Wrak.premie", "Wrak.ploegpremie", "Wrak.keursom", "Wrak.havenbonus",
                         "Wrak:toegewezen", "Wrak:vrijgesteld", "Wrak:kustwrak", "Wrak:uitwijkplichtig"] } ],
          "parameters": {} }""";

    private static DecisionCore strictCore() {
        return DecisionCore.assemble(STRICT_ROLES_DESCRIPTOR, Map.of("rollen.rgs", STRICT_ROLES_SOURCE),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore ordinaryCore() {
        String source = STRICT_ROLES_SOURCE.replace("Afleiden uit leeg: nee", "").replace(" (volledig gegeven)", "");
        return DecisionCore.assemble(STRICT_ROLES_DESCRIPTOR, Map.of("rollen.rgs", source),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
    }

    @Test
    void request_forms_open_empty_null_linked_and_complete_role() throws Exception {
        DecisionCore core = strictCore();

        // 1. Omitted and null vinder: premie, ploegpremie, keursom, toegewezen, vrijgesteld, kustwrak are open;
        //    thuishaven is (volledig gegeven) so havenbonus stands at 20 while uitwijkplichtig is open!
        for (String rolesJson : List.of("", ", \"roles\": {\"vinder\": null, \"noodhavens\": null}")) {
            ObjectNode ans = process(core, "wrak", "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\","
                    + " \"attributes\": {\"lengte\": 20}" + rolesJson + "}]}", ALL);
            JsonNode w1 = ans.at("/instances/0");
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/attributes/premie"));
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/attributes/ploegpremie"));
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/attributes/keursom"));
            assertEquals(json("{\"state\": \"filled\", \"value\": 20}"), w1.at("/attributes/havenbonus"));
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/characteristics/toegewezen"));
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/characteristics/vrijgesteld"));
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/characteristics/kustwrak"));
            assertEquals(json("{\"state\": \"open\"}"), w1.at("/characteristics/uitwijkplichtig"));

            // Deterministic order of openInputs on W1: vinder (wrakvondst) before haven (noodhavenrelatie), thuishaven omitted
            assertEquals(json("""
                [{"kind": "role", "instance": "W1", "name": "vinder", "forOutputs": [
                    {"instance": "W1", "name": "premie", "kind": "attribute"},
                    {"instance": "W1", "name": "ploegpremie", "kind": "attribute"},
                    {"instance": "W1", "name": "keursom", "kind": "attribute"},
                    {"instance": "W1", "name": "toegewezen", "kind": "characteristic"},
                    {"instance": "W1", "name": "vrijgesteld", "kind": "characteristic"},
                    {"instance": "W1", "name": "kustwrak", "kind": "characteristic"}]},
                 {"kind": "role", "instance": "W1", "name": "haven", "forOutputs": [
                    {"instance": "W1", "name": "uitwijkplichtig", "kind": "characteristic"}]}]"""),
                    ans.get("openInputs"), ans.toString());

            // Fixpoint open roles: vinder and noodhavenrelatie's haven (not complete thuishaven)
            assertEquals(json("""
                [{"attribute": "premie"}, {"attribute": "ploegpremie"}, {"attribute": "keursom"},
                 {"characteristic": "toegewezen"}, {"characteristic": "vrijgesteld"},
                 {"characteristic": "kustwrak"}, {"characteristic": "uitwijkplichtig"},
                 {"role": "vinder"}, {"role": "haven"}]"""),
                    ans.at("/fixpoint/objects/0/open"), ans.toString());
        }

        // 2. Settled empty {"state": "empty"} on vinder and noodhavens: everything settles, openInputs is []
        ObjectNode settled = process(core, "wrak", """
            {"instances": [{"type": "Wrak", "id": "W1", "attributes": {"lengte": 20},
                            "roles": {"vinder": {"state": "empty"}, "noodhavens": {"state": "empty"}}}]}""", ALL);
        JsonNode w1 = settled.at("/instances/0");
        assertEquals(json("{\"state\": \"filled\", \"value\": 20}"), w1.at("/attributes/premie"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 20}"), w1.at("/attributes/ploegpremie"));
        assertEquals(json("{\"state\": \"empty\"}"), w1.at("/attributes/keursom"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 20}"), w1.at("/attributes/havenbonus"));
        assertEquals(json("{\"state\": \"holdsNot\"}"), w1.at("/characteristics/toegewezen"));
        assertEquals(json("{\"state\": \"holds\"}"), w1.at("/characteristics/vrijgesteld"));
        assertEquals(json("{\"state\": \"holdsNot\"}"), w1.at("/characteristics/kustwrak"));
        assertEquals(json("{\"state\": \"holdsNot\"}"), w1.at("/characteristics/uitwijkplichtig"));
        assertEquals(json("[]"), settled.get("openInputs"));
        assertEquals(json("[]"), settled.at("/fixpoint/objects/0/open"));

        // 3. Linked via 1-step (W1 -> J1), with 2nd step ploeg on J1 open: openInputs points to J1's bonus, ingezetene and ploeg!
        ObjectNode secondHopOpen = process(core, "wrak", """
            {"instances": [{"type": "Jutter", "id": "J1"},
                           {"type": "Wrak", "id": "W1", "attributes": {"lengte": 20},
                            "roles": {"vinder": ["J1"], "uitwijkhaven": {"state": "empty"}}}]}""", ALL);
        assertEquals(json("""
            [{"kind": "attribute", "instance": "J1", "name": "bonus", "forOutputs": [
                {"instance": "W1", "name": "premie", "kind": "attribute"}]},
             {"kind": "characteristic", "instance": "J1", "name": "ingezetene", "forOutputs": [
                {"instance": "W1", "name": "kustwrak", "kind": "characteristic"}]},
             {"kind": "role", "instance": "J1", "name": "ploeg", "forOutputs": [
                {"instance": "W1", "name": "ploegpremie", "kind": "attribute"},
                {"instance": "W1", "name": "keursom", "kind": "attribute"}]}]"""),
                secondHopOpen.get("openInputs"), secondHopOpen.toString());
        assertTrue(secondHopOpen.get("explanation").toString()
                .contains("W1: zijn ploegpremie staat niet vast, want van J1 is zijn ploeg niet gegeven"),
                secondHopOpen.get("explanation").toString());
        assertTrue(secondHopOpen.get("explanation").toString()
                .contains("W1: zijn keursom staat niet vast, want van J1 is zijn ploeg niet gegeven"),
                secondHopOpen.get("explanation").toString());
    }

    @Test
    void vocabularies_and_same_canonical_role_name_across_fact_types() throws Exception {
        DecisionCore core = strictCore();

        // Pinned inputVocabulary=handboek with designation "melder" and "uitwijkhaven", output vocabulary=handboek
        RequestOptions handboek = new RequestOptions(true, "handboek", "handboek", false, false, true, false, true);
        ObjectNode openAns = process(core, "wrak", """
            {"instances": [{"type": "Wrak", "id": "W1", "attributes": {"lengte": 20},
                            "roles": {"melder": null}}]}""", handboek);
        // "vinder" -> "melder", and noodhavenrelatie's "haven" -> "uitwijkhaven" (NOT thuishaven's "vaste haven"!)
        assertEquals(json("""
            [{"kind": "role", "instance": "W1", "name": "melder", "forOutputs": [
                {"instance": "W1", "name": "premie", "kind": "attribute"},
                {"instance": "W1", "name": "ploegpremie", "kind": "attribute"},
                {"instance": "W1", "name": "keursom", "kind": "attribute"},
                {"instance": "W1", "name": "toegewezen", "kind": "characteristic"},
                {"instance": "W1", "name": "vrijgesteld", "kind": "characteristic"},
                {"instance": "W1", "name": "kustwrak", "kind": "characteristic"}]},
             {"kind": "role", "instance": "W1", "name": "uitwijkhaven", "forOutputs": [
                {"instance": "W1", "name": "uitwijkplichtig", "kind": "characteristic"}]}]"""),
                openAns.get("openInputs"), openAns.toString());
        assertTrue(openAns.get("explanation").toString().contains("want zijn melder is niet gegeven"),
                openAns.get("explanation").toString());
        assertTrue(openAns.get("explanation").toString().contains("want zijn uitwijkhaven is niet gegeven"),
                openAns.get("explanation").toString());
        assertTrue(openAns.at("/fixpoint/objects/0/open").toString()
                .contains("{\"role\":\"uitwijkhaven\",\"roleCanonical\":\"haven\"}"),
                openAns.at("/fixpoint/objects/0/open").toString());

        // Settled empty via vocabulary designation "uitwijkhaven" settles uitwijkplichtig while leaving melder open
        ObjectNode settledNoodhaven = process(core, "wrak", """
            {"instances": [{"type": "Wrak", "id": "W1", "attributes": {"lengte": 20},
                            "roles": {"uitwijkhaven": {"state": "empty"}}}]}""", handboek);
        assertEquals(json("{\"state\": \"holdsNot\"}"),
                settledNoodhaven.at("/instances/0/characteristics/uitwijkplichtig"));
        assertEquals(1, settledNoodhaven.get("openInputs").size(), settledNoodhaven.get("openInputs").toString());
        assertEquals("melder", settledNoodhaven.at("/openInputs/0/name").asText());
    }

    @Test
    void violations_400_and_evaluation_fault_ef049() throws Exception {
        DecisionCore strict = strictCore();
        DecisionCore ordinary = ordinaryCore();

        // 1. {"state": "empty"} in an ordinary rule set -> 400 WRONG_TYPE
        ServiceError ordErr = assertThrows(ServiceError.class, () -> ordinary.process("wrak", json("""
            {"instances": [{"type": "Wrak", "id": "W1", "roles": {"vinder": {"state": "empty"}}}]}""")));
        assertEquals(1, ordErr.violations().size());
        assertEquals(Violation.Kind.WRONG_TYPE, ordErr.violations().get(0).kind());
        assertEquals("instances[0].roles.vinder", ordErr.violations().get(0).field());

        // 2. {"state": "empty"} on a to-many role -> 400 WRONG_TYPE
        ServiceError manyErr = assertThrows(ServiceError.class, () -> strict.process("wrak", json("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": {"state": "empty"}}}]}""")));
        assertEquals(Violation.Kind.WRONG_TYPE, manyErr.violations().get(0).kind());
        assertEquals("instances[0].roles.gevonden wrakken", manyErr.violations().get(0).field());

        // 3. {"state": "empty"} on a reciprocal role -> 400 WRONG_TYPE
        ServiceError recErr = assertThrows(ServiceError.class, () -> strict.process("wrak", json("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"strandmaat": {"state": "empty"}}}]}""")));
        assertEquals(Violation.Kind.WRONG_TYPE, recErr.violations().get(0).kind());
        assertEquals("instances[0].roles.strandmaat", recErr.violations().get(0).field());

        // 4. {"state": "empty"} in top-level relations -> 400 WRONG_TYPE (both strict and ordinary)
        for (DecisionCore c : List.of(strict, ordinary)) {
            ServiceError relErr = assertThrows(ServiceError.class, () -> c.process("wrak", json("""
                {"instances": [{"type": "Jutter", "id": "J1"}, {"type": "Wrak", "id": "W1"}],
                 "relations": [{"factType": "wrakvondst",
                                "roles": {"vinder": {"state": "empty"}, "gevonden wrak": "W1"}}]}""")));
            assertEquals(Violation.Kind.WRONG_TYPE, relErr.violations().get(0).kind(), relErr.violations().toString());
            assertEquals("relations[0].roles.vinder", relErr.violations().get(0).field(), relErr.violations().toString());
        }

        // 5. Other states / malformed objects on a strict to-one role
        for (String[] bad : new String[][]{
                {"{\"state\": \"open\"}", "WRONG_TYPE"},
                {"{\"state\": \"filled\", \"value\": \"J1\"}", "WRONG_TYPE"},
                {"{\"state\": \"holds\"}", "WRONG_TYPE"},
                {"{\"state\": \"holdsNot\"}", "WRONG_TYPE"},
                {"{\"state\": \"anders\"}", "WRONG_TYPE"},
                {"{\"state\": 42}", "MALFORMED"},
                {"{\"state\": \"empty\", \"extra\": true}", "MALFORMED"},
                {"{}", "MALFORMED"}}) {
            ServiceError e = assertThrows(ServiceError.class, () -> strict.process("wrak", json(
                    "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"roles\": {\"vinder\": " + bad[0] + "}}]}")));
            assertEquals(Violation.Kind.valueOf(bad[1]), e.violations().get(0).kind(), bad[0] + ": " + e.violations());
            assertEquals("instances[0].roles.vinder", e.violations().get(0).field());
        }

        // 6. Duplicate spellings of the same role on one instance -> 400 MALFORMED
        for (String dupRoles : List.of(
                "{\"vinder\": \"J1\", \"melder\": {\"state\": \"empty\"}}",
                "{\"vinder\": {\"state\": \"empty\"}, \"vinders\": \"J1\"}",
                "{\"melder\": {\"state\": \"empty\"}, \"aanmelder\": {\"state\": \"empty\"}}")) {
            ServiceError dupErr = assertThrows(ServiceError.class, () -> strict.process("wrak", json(
                    "{\"instances\": [{\"type\": \"Jutter\", \"id\": \"J1\"},"
                            + " {\"type\": \"Wrak\", \"id\": \"W1\", \"roles\": " + dupRoles + "}]}")));
            assertEquals(Violation.Kind.MALFORMED, dupErr.violations().get(0).kind(), dupErr.violations().toString());
            assertTrue(dupErr.violations().get(0).message().contains("staat twee keer in deze instantie"),
                    dupErr.violations().toString());
        }

        // 7. Ambiguous role "haven" on Wrak (without inputVocabulary, singular "haven" matches both thuishaven and noodhavenrelatie)
        ServiceError ambErr = assertThrows(ServiceError.class, () -> strict.process("wrak", json("""
            {"instances": [{"type": "Wrak", "id": "W1", "roles": {"haven": {"state": "empty"}}}]}""")));
        assertEquals(Violation.Kind.AMBIGUOUS_NAME, ambErr.violations().get(0).kind(), ambErr.violations().toString());

        // 8. 422 EVALUATION_FAULT EF-049 when linked from opposite instance or top-level relations AND {"state": "empty"}
        for (String conflictReq : List.of(
                """
                {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1"]}},
                               {"type": "Wrak", "id": "W1", "roles": {"melder": {"state": "empty"}}}]}""",
                """
                {"instances": [{"type": "Jutter", "id": "J1"},
                               {"type": "Wrak", "id": "W1", "roles": {"vinder": {"state": "empty"}}}],
                 "relations": [{"factType": "wrakvondst", "roles": {"vinder": "J1", "gevonden wrak": "W1"}}]}""")) {
            EvaluationFault fault = assertThrows(EvaluationFault.class,
                    () -> strict.process("wrak", json(conflictReq)));
            assertEquals(1, fault.errors().size());
            assertEquals("EF-049", fault.errors().get(0).code());
            assertEquals(Map.of("label", "W1", "role", "vinder"), fault.errors().get(0).params());
            ObjectNode faultJson = AnswerBuilder.evaluationFault(fault);
            assertEquals("EVALUATION_FAULT", faultJson.get("status").asText());
            assertEquals("EF-049", faultJson.at("/faults/0/code").asText());
            assertEquals("W1", faultJson.at("/faults/0/params/label").asText());
        }
    }

    @Test
    void info_complete_input_and_explanation_parts() throws Exception {
        DecisionCore fromSource = strictCore();
        String irJson = UcharBuild.document(Resolver.resolve(STRICT_ROLES_SOURCE));
        DecisionCore fromDoc = DecisionCore.buildFromDocument(STRICT_ROLES_DESCRIPTOR, irJson,
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader(), null);
        JsonNode expectedComplete = json("""
            [{"kind": "role", "objectType": "Vaartuig", "name": "haven"}]""");
        assertEquals(expectedComplete, fromSource.completeInput());
        assertEquals(expectedComplete, fromDoc.completeInput());

        // Explanation parts on open role: navigated read has object: null on QUOTE, role has declaration=ROL without object
        ObjectNode ans = fromSource.process("wrak", json("""
            {"instances": [{"type": "Wrak", "id": "W1", "attributes": {"lengte": 20}}]}"""), ALL);
        ExplanationPartsCheck.assertPartsAreTheSentences(ans.get("explanation"), ans.get("explanationParts"));
        for (JsonNode sentence : ans.get("explanation")) {
            assertFalse(sentence.asText().contains("onbekend"), sentence.asText());
        }
        for (JsonNode parts : ans.get("explanationParts")) {
            String stripped = ExplanationPartsCheck.withoutOmissibleParts(parts);
            assertFalse(stripped.endsWith(": ") || stripped.endsWith("; ") || stripped.contains("()"), stripped);
        }
        String renderedPremie = ExplanationPartsCheck.render(sentenceStarting(ans, "W1: zijn premie"));
        assertTrue(renderedPremie.contains("NAME{declaration=ROL, owner=wrakvondst, canonical=vinder}«vinder»"),
                renderedPremie);
        assertTrue(renderedPremie.contains("QUOTE{declaration=ATTRIBUUT, owner=Jutter, canonical=bonus, object=null}"
                + "«de bonus van de vinder van het Wrak»"
                + " READING{open={\"object\":\"W1\",\"canonical\":\"vinder\",\"kind\":\"role\"}}[« (staat niet vast)»]"),
                renderedPremie);

        String renderedKustwrak = ExplanationPartsCheck.render(sentenceStarting(ans, "W1: of hij kustwrak"));
        assertTrue(renderedKustwrak.contains("NAME{declaration=KENMERK, owner=Jutter, canonical=ingezetene, object=null}"
                + "«ingezetene» « is»"
                + " READING{open={\"object\":\"W1\",\"canonical\":\"vinder\",\"kind\":\"role\"}}[« (staat niet vast)»]"),
                renderedKustwrak);
    }

    @Test
    void uchar_role_goldens_and_red_case_play_through_decision_core() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (String golden : List.of("jutterswet-invoer-rol", "jutterswet-invoer-rol-strikt")) {
            Path dir = TokaSource.REPO.resolve("uchar/corpus/golden").resolve(golden);
            String source = Files.readString(dir.resolve("source.rgs"));
            JsonNode doc = DescriptorLoader.MAPPER.readTree(UcharBuild.document(Resolver.resolve(source)));
            DecisionCore core = DecisionCore.assemble(replayDescriptor(doc), Map.of("s.rgs", source),
                    CoreConfig.strict().withDetail(), cl);
            boolean strict = !doc.path("inferFromEmpty").asBoolean(true);
            List<Path> cases;
            try (Stream<Path> s = Files.list(dir)) {
                cases = s.filter(p -> p.getFileName().toString().matches("case.*\\.json")).sorted().toList();
            }
            assertFalse(cases.isEmpty(), golden);
            for (Path caseFile : cases) {
                JsonNode c = DescriptorLoader.MAPPER.readTree(Files.readString(caseFile, StandardCharsets.UTF_8));
                RequestOptions opts = strict
                        ? new RequestOptions(true, null, null, false, false, true, true, true)
                        : new RequestOptions(true, null, null, false, false, true, true, false);
                DecisionCore.Assessment assessment = core.assess("replay", caseToRequest(c, strict), opts, c.get("expected"));
                assertTrue(assessment.comparison().expectations().stream().allMatch(e -> e.ok()),
                        () -> golden + "/" + caseFile.getFileName() + ": " + assessment.comparison().expectations());
                ExplanationPartsCheck.assertPartsAreTheSentences(
                        assessment.answer().get("explanation"), assessment.answer().get("explanationParts"));
            }
        }

        // Red case jutterswet-rol-gevuld-en-leeg/case2-tegeninstantie.json -> 422 EF-049
        Path redDir = TokaSource.REPO.resolve("uchar/corpus/red/jutterswet-rol-gevuld-en-leeg");
        String redIr = Files.readString(redDir.resolve("ir.json"), StandardCharsets.UTF_8);
        JsonNode redDoc = DescriptorLoader.MAPPER.readTree(redIr);
        DecisionCore redCore = DecisionCore.buildFromDocument(replayDescriptor(redDoc), redIr,
                CoreConfig.strict(), cl, null);
        JsonNode case2 = DescriptorLoader.MAPPER.readTree(
                Files.readString(redDir.resolve("case2-tegeninstantie.json"), StandardCharsets.UTF_8));
        EvaluationFault fault = assertThrows(EvaluationFault.class,
                () -> redCore.process("replay", caseToRequest(case2, true)));
        assertEquals("EF-049", fault.errors().get(0).code());
        assertEquals(Map.of("label", "V2", "role", "vinder"), fault.errors().get(0).params());
    }

    @Test
    void canonically_colliding_role_without_unambiguous_plural_is_400_unsupported() throws Exception {
        String source = """
            Afleiden uit leeg: nee

            Vocabulaire handboek (taal: nl)

            Objecttype de Haven (mv: Havens)

            Objecttype het Vaartuig (mv: Vaartuigen)
                is aangemeld kenmerk (bijvoeglijk);

            Objecttype het Wrak is een Vaartuig (mv: Wrakken)
                is uitgeweken kenmerk (bijvoeglijk);
                is doorgestuurd kenmerk (bijvoeglijk);

            Feittype thuishaven
                de haven (handboek: vaste haven) Haven
                het vaartuig (mv: vaartuigen) Vaartuig
                één haven heeft meerdere vaartuigen

            Feittype noodhavenrelatie
                de haven (handboek: uitwijkhaven) Haven
                het noodwrak (mv: noodwrakken) Wrak
                één haven heeft meerdere noodwrakken

            Feittype reservehavenrelatie
                de haven (mv: havens) (handboek: reservehaven) Haven
                het reservewrak (mv: reservewrakken) Wrak
                één haven heeft meerdere reservewrakken

            Feittype havengroeprelatie
                de havens (handboek: havengroep) Haven
                het groepswrak (mv: groepswrakken) Wrak
                één havens heeft meerdere groepswrakken

            Regel aanmelding
                geldig altijd
                    Een Vaartuig is aangemeld indien hij een vaartuig is.

            Regel uitwijking
                geldig altijd
                    Een Wrak is uitgeweken indien hij een noodwrak is.

            Regel doorsturing
                geldig altijd
                    Een Wrak is doorgestuurd indien hij een reservewrak is.
            """;
        String descriptor = """
            { "service": {"name":"botsende-rol","version":"1"},
              "entrypoints": [
                { "name":"wrak","path":"wrak",
                  "scope": {"ruleNames": ["aanmelding", "uitwijking", "doorsturing"]},
                  "input": [],
                  "output": ["Vaartuig:aangemeld", "Wrak:uitgeweken", "Wrak:doorgestuurd"] } ],
              "parameters": {} }""";
        DecisionCore core = DecisionCore.assemble(descriptor, Map.of("botsend.rgs", source),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());

        // 1. On Wrak, "uitwijkhaven" has no plural and shares singular "haven" with thuishaven:
        //    both {"state": "empty"} and a linked id are 400 UNSUPPORTED on instances[...].roles.uitwijkhaven (never 500!)
        ServiceError emptyErr = assertThrows(ServiceError.class, () -> core.process("wrak", json("""
            {"instances": [{"type": "Wrak", "id": "W1", "roles": {"uitwijkhaven": {"state": "empty"}}}]}""")));
        assertEquals(1, emptyErr.violations().size(), emptyErr.violations().toString());
        assertEquals(Violation.Kind.UNSUPPORTED, emptyErr.violations().get(0).kind(), emptyErr.violations().toString());
        assertEquals("instances[0].roles.uitwijkhaven", emptyErr.violations().get(0).field());

        ServiceError linkErr = assertThrows(ServiceError.class, () -> core.process("wrak", json("""
            {"instances": [{"type": "Haven", "id": "H1"},
                           {"type": "Wrak", "id": "W1", "roles": {"uitwijkhaven": "H1"}}]}""")));
        assertEquals(1, linkErr.violations().size(), linkErr.violations().toString());
        assertEquals(Violation.Kind.UNSUPPORTED, linkErr.violations().get(0).kind(), linkErr.violations().toString());
        assertEquals("instances[1].roles.uitwijkhaven", linkErr.violations().get(0).field());

        // 2. Plural "havens" on reservehavenrelatie collides with singular "havens" on havengroeprelatie -> 400 UNSUPPORTED
        ServiceError pluralCollisionErr = assertThrows(ServiceError.class, () -> core.process("wrak", json("""
            {"instances": [{"type": "Wrak", "id": "W1", "roles": {"reservehaven": {"state": "empty"}}}]}""")));
        assertEquals(1, pluralCollisionErr.violations().size(), pluralCollisionErr.violations().toString());
        assertEquals(Violation.Kind.UNSUPPORTED, pluralCollisionErr.violations().get(0).kind());
        assertEquals("instances[0].roles.reservehaven", pluralCollisionErr.violations().get(0).field());

        // 3. On supertype Vaartuig (which does NOT participate in noodhavenrelatie), "vaste haven" resolves uniquely to "haven" -> 200
        ObjectNode vaartuigOk = process(core, "wrak", """
            {"instances": [{"type": "Vaartuig", "id": "V1", "roles": {"vaste haven": {"state": "empty"}}}]}""",
                new RequestOptions(false, null, "handboek", false, false, false, false, false));
        assertEquals(json("{\"state\": \"holdsNot\"}"), vaartuigOk.at("/instances/0/characteristics/aangemeld"));
    }

    @Test
    void undetermined_quantifier_fold_without_contributors_writes_reading_and_no_empty_members() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Case A: 3-step range from Wrak (keuringen van de ploeg van de vinder), step 1 (vinder) open on W1 -> 0 contributors
        String wrakFoldSource = STRICT_ROLES_SOURCE.replace("is uitwijkplichtig kenmerk (bijvoeglijk);",
                "is uitwijkplichtig kenmerk (bijvoeglijk);\n            is goedgekeurd kenmerk (bijvoeglijk);") + """
            Regel goedkeuring
                geldig altijd
                    Een Wrak is goedgekeurd indien het keurcijfer van alle keuringen van de ploeg van de vinder van het Wrak groter is dan 5.
            """;
        String wrakFoldDescriptor = """
            { "service": {"name":"wrak-vouw","version":"1"},
              "entrypoints": [
                { "name":"wrak","path":"wrak",
                  "scope": {"ruleNames": ["goedkeuring"]},
                  "input": [],
                  "output": ["Wrak:goedgekeurd"] } ],
              "parameters": {} }""";
        DecisionCore wrakFoldCore = DecisionCore.assemble(wrakFoldDescriptor, Map.of("vouw.rgs", wrakFoldSource),
                CoreConfig.strict(), cl);
        ObjectNode wrakAns = wrakFoldCore.process("wrak", json("""
            {"instances": [{"type": "Wrak", "id": "W1"}]}"""),
                new RequestOptions(true, null, null, false, false, false, true, true));
        ExplanationPartsCheck.assertPartsAreTheSentences(wrakAns.get("explanation"), wrakAns.get("explanationParts"));
        String expectedWrakSentence = "W1: of hij goedgekeurd is, staat niet vast, want zijn vinder is niet gegeven;"
                + " voor de regel 'goedkeuring' staat niet vast: aan de voorwaarde"
                + " \"het keurcijfer van alle keuringen van de ploeg van de vinder van het Wrak groter is dan 5\""
                + " is voldaan (staat niet vast)";
        assertSentence(expectedWrakSentence, wrakAns);
        JsonNode wrakParts = sentenceStarting(wrakAns, "W1: of hij goedgekeurd");
        assertFalse(wrakParts.toString().contains("\"MEMBERS\""), wrakParts.toString());
        assertEquals("W1: of hij goedgekeurd is, staat niet vast, want zijn vinder is niet gegeven",
                ExplanationPartsCheck.withoutOmissibleParts(wrakParts));
        assertTrue(ExplanationPartsCheck.render(wrakParts).contains(
                "QUOTE«het keurcijfer van alle keuringen van de ploeg van de vinder van het Wrak groter is dan 5»"
                        + " «\" is voldaan»"
                        + " READING{open={\"object\":\"W1\",\"canonical\":\"vinder\",\"kind\":\"role\"}}[« (staat niet vast)»]"),
                ExplanationPartsCheck.render(wrakParts));

        // Case B: 2-step range (alle bewaarplaatsen van alle vondsten van de Jutter), J1 has only V2 whose bewaarplaats is open -> 0 contributors
        Path dir = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-invoer-rol-strikt");
        String source = Files.readString(dir.resolve("source.rgs"));
        JsonNode doc = DescriptorLoader.MAPPER.readTree(UcharBuild.document(Resolver.resolve(source)));
        DecisionCore jutterCore = DecisionCore.assemble(replayDescriptor(doc), Map.of("s.rgs", source),
                CoreConfig.strict(), cl);
        ObjectNode jutterAns = jutterCore.process("replay", json("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V2"]}},
                           {"type": "Vondst", "id": "V2"}]}"""),
                new RequestOptions(true, null, null, false, false, false, true, true));
        ExplanationPartsCheck.assertPartsAreTheSentences(jutterAns.get("explanation"), jutterAns.get("explanationParts"));
        String expectedJutterSentence = "J1: of hij ruim bewaard is, staat niet vast, want van V2 is zijn bewaarplaats niet gegeven;"
                + " voor de regel 'ruim bewaard' staat niet vast: aan de voorwaarde"
                + " \"de capaciteit van alle bewaarplaatsen van alle vondsten van de Jutter groter is dan 5\""
                + " is voldaan (staat niet vast)";
        assertSentence(expectedJutterSentence, jutterAns);
        JsonNode jutterParts = sentenceStarting(jutterAns, "J1: of hij ruim bewaard");
        assertFalse(jutterParts.toString().contains("\"MEMBERS\""), jutterParts.toString());
        assertEquals("J1: of hij ruim bewaard is, staat niet vast, want van V2 is zijn bewaarplaats niet gegeven",
                ExplanationPartsCheck.withoutOmissibleParts(jutterParts));
        assertTrue(ExplanationPartsCheck.render(jutterParts).contains(
                "QUOTE«de capaciteit van alle bewaarplaatsen van alle vondsten van de Jutter groter is dan 5»"
                        + " «\" is voldaan»"
                        + " READING{open={\"object\":\"V2\",\"canonical\":\"bewaarplaats\",\"kind\":\"role\"}}[« (staat niet vast)»]"),
                ExplanationPartsCheck.render(jutterParts));
    }

    private static void assertSentence(String expected, JsonNode answer) {
        for (JsonNode z : answer.get("explanation"))
            if (expected.equals(z.asText())) return;
        throw new AssertionError(expected + "\nniet in\n" + answer.get("explanation"));
    }

    private static JsonNode sentenceStarting(JsonNode answer, String prefix) {
        JsonNode texts = answer.get("explanation");
        for (int i = 0; i < texts.size(); i++)
            if (texts.get(i).asText().startsWith(prefix)) return answer.get("explanationParts").get(i);
        throw new AssertionError("geen zin met '" + prefix + "' in " + texts);
    }

    private static ObjectNode caseToRequest(JsonNode c, boolean strict) {
        ObjectNode req = DescriptorLoader.MAPPER.createObjectNode();
        if (c.hasNonNull("asOfDate")) req.put("rekendatum", c.get("asOfDate").asText());
        ArrayNode instances = req.putArray("instances");
        for (JsonNode i : c.at("/input/instances")) {
            ObjectNode inst = instances.addObject()
                    .put("type", i.get("objectType").asText())
                    .put("id", i.get("label").asText());
            ObjectNode attrs = DescriptorLoader.MAPPER.createObjectNode();
            i.path("attributes").fields().forEachRemaining(e -> {
                JsonNode w = e.getValue();
                if (w.has("number")) attrs.put(e.getKey(), new java.math.BigDecimal(w.get("number").asText()));
                else if (w.has("text")) attrs.put(e.getKey(), w.get("text").asText());
                else if (w.has("boolean")) attrs.put(e.getKey(), w.get("boolean").asBoolean());
                else if (w.has("date")) attrs.put(e.getKey(), w.get("date").asText());
                else if (w.has("enumeration")) attrs.put(e.getKey(), w.get("enumeration").asText());
            });
            if (strict)
                for (JsonNode a : i.path("attributesEmpty")) attrs.putObject(a.asText()).put("state", "empty");
            if (!attrs.isEmpty()) inst.set("attributes", attrs);

            ObjectNode chars = DescriptorLoader.MAPPER.createObjectNode();
            for (JsonNode k : i.path("characteristics")) {
                if (strict) chars.putObject(k.asText()).put("state", "holds");
                else chars.put(k.asText(), true);
            }
            for (JsonNode k : i.path("characteristicsAbsent")) {
                if (strict) chars.putObject(k.asText()).put("state", "holdsNot");
                else chars.put(k.asText(), false);
            }
            if (!chars.isEmpty()) inst.set("characteristics", chars);

            ObjectNode roles = DescriptorLoader.MAPPER.createObjectNode();
            i.path("roles").fields().forEachRemaining(e -> {
                ArrayNode targets = roles.putArray(e.getKey());
                e.getValue().forEach(t -> targets.add(t.asText()));
            });
            if (strict)
                for (JsonNode r : i.path("rolesEmpty")) roles.putObject(r.asText()).put("state", "empty");
            if (!roles.isEmpty()) inst.set("roles", roles);
        }
        return req;
    }

    private static String replayDescriptor(JsonNode document) {
        List<String> forms = new ArrayList<>(), output = new ArrayList<>();
        for (String list : List.of("rules", "prioritizedDefaults"))
            for (JsonNode f : document.at("/rules/" + list))
                if (f.hasNonNull("name") && !forms.contains(f.get("name").asText())) forms.add(f.get("name").asText());
        for (JsonNode t : document.at("/model/objectTypes")) {
            for (JsonNode a : t.path("attributes")) output.add(t.get("name").asText() + "." + a.get("name").asText());
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

    private static ObjectNode process(DecisionCore core, String path, String reqJson, RequestOptions opts)
            throws Exception {
        ObjectNode raw = core.process(path, json(reqJson), opts);
        return (ObjectNode) DescriptorLoader.MAPPER.readTree(DescriptorLoader.MAPPER.writeValueAsString(raw));
    }

    private static JsonNode json(String text) throws Exception {
        return DescriptorLoader.MAPPER.readTree(text);
    }
}
