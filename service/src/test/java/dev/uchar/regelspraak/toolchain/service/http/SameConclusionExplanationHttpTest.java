package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ExplanationPartsCheck;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explanation and {@code openInputs} of an open default that can only decide the same conclusion as a lower or sibling
 * default, or an open negation at the root of a characteristic stack (card {@code same-conclusion-explanation}; uchar's
 * RUNTIME-SEMANTICS ch. 1.5 and 5.12). Every test asserts literal sentences, {@code explanationParts}, {@code GROUND},
 * {@code withoutOmissibleParts} and {@code openInputs}.
 */
class SameConclusionExplanationHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final String MODEL = """
        Afleiden uit leeg: nee

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (geheel getal);
            het vindloon Numeriek (geheel getal);
            het strandloon Numeriek (geheel getal);
            het bergloon Numeriek (geheel getal);
            het havenloon Numeriek (geheel getal);
            het rekenloon Numeriek (geheel getal);
            het werkloon Numeriek (geheel getal);
            is betwist kenmerk (bijvoeglijk);
            is beschadigd kenmerk (bijvoeglijk);
            is drijfhout kenmerk (bijvoeglijk);
            is gemeld kenmerk (bijvoeglijk);
            is beloonbaar kenmerk (bijvoeglijk);
            is verkoopbaar kenmerk (bijvoeglijk);

        Objecttype de Wrakvondst is een Vondst (mv: Wrakvondsten)
            de diepte Numeriek (geheel getal);

        """;

    /** Value stack with the same constant decides through the lower default; its GROUND names the decider (not the open
     *  variant) and openInputs omits betwist. The red countercase (strandloon: open 0 above holding 5) stays open, holds back
     *  the lower default instead of naming it decider, and asks for betwist. */
    @Test
    void attribute_stack_same_conclusion_and_red_countercase() throws Exception {
        String source = MODEL + """
            // Bron: jci1.3:c:BWBR0005291&artikel=1
            Regel vindloon
                geldig altijd
                    Het vindloon van een Vondst moet berekend worden als zijn gewicht maal 2.

            // Bron: jci1.3:c:BWBR0005291&artikel=2
            Afwijking van vindloon: beschadigd vindloon
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.

            // Bron: jci1.3:c:BWBR0005291&artikel=3
            Afwijking van vindloon: betwist vindloon
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 0 indien hij betwist is.

            Regel strandloon
                geldig altijd
                    Het strandloon van een Vondst moet berekend worden als zijn gewicht maal 2.

            Afwijking van strandloon: beschadigd strandloon
                geldig altijd
                    Het strandloon van een Vondst moet gesteld worden op 5 indien hij beschadigd is.

            Afwijking van strandloon: betwist strandloon
                geldig altijd
                    Het strandloon van een Vondst moet gesteld worden op 0 indien hij betwist is.
            """;
        JsonNode answer = ask(source, vondst("\"gewicht\": 10", "\"beschadigd\": {\"state\": \"holds\"}"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 0}"), answer.at("/instances/0/attributes/vindloon"));
        assertEquals("open", answer.at("/instances/0/attributes/strandloon/state").asText());

        String decided = "V1 heeft als vindloon 0 volgens 'beschadigd vindloon': 0, omdat het onderwerp beschadigd is;"
                + " 'betwist vindloon' staat niet vast, maar kan alleen hetzelfde beslissen; verdrongen: de regel 'vindloon'"
                + " (onvoorwaardelijk, 20). Grondslag: jci1.3:c:BWBR0005291&artikel=2";
        assertSentence(decided, answer);

        JsonNode parts = partsOf(answer, "V1 heeft als vindloon 0");
        JsonNode openVariant = findVariant(parts, "betwist vindloon");
        assertNotNull(openVariant, parts.toString());
        assertFalse(openVariant.get("decided").asBoolean());
        assertFalse(openVariant.has("displaced"));
        assertEquals("VARIANT{decided=false}[«; '» RULE«betwist vindloon» «' » CONNECTIVE«staat niet vast» «, »"
                + " CONNECTIVE«maar» « kan alleen hetzelfde beslissen»]",
                ExplanationPartsCheck.render(ServiceHttp.MAPPER.createArrayNode().add(openVariant)));

        JsonNode ground = find(parts, "GROUND");
        assertNotNull(ground, parts.toString());
        assertEquals("vindloon", ground.get("group").asText());
        assertEquals("/defaults/1", ground.get("pointer").asText());
        assertEquals(2, ground.get("position").asInt());
        assertEquals("beschadigd vindloon", ground.get("variant").asText());

        // red countercase: open 0 above holding 5 stays open and names beschadigd strandloon as held back, never decider
        assertSentence("V1: zijn strandloon staat niet vast, want het is niet gegeven of hij betwist is; voor 'betwist"
                + " strandloon' staat niet vast: het onderwerp betwist is (niet gegeven); 'beschadigd strandloon' gaf 5,"
                + " maar beslist niet zolang 'betwist strandloon' niet vaststaat; niet aan de beurt: de regel 'strandloon'"
                + " (onvoorwaardelijk, 20)", answer);
        assertEquals(List.of("V1 strandloon", "V1 betwist"), forOutputs(answer, "betwist"));
    }

    /** Characteristic stack: decided by a lower negative default (with GROUND of the decider) and closed at the root (no
     *  GROUND), both omitting betwist from openInputs, and root closing beside an input {"state": "holds"}. */
    @Test
    void characteristic_stack_decided_and_root_closing_and_beside_input() throws Exception {
        String source = MODEL + """
            // Bron: jci1.3:c:BWBR0005291&artikel=10
            Regel beloonbaarheid
                geldig altijd
                    Een Vondst is beloonbaar indien zijn gewicht groter is dan 2.

            // Bron: jci1.3:c:BWBR0005291&artikel=11
            Afwijking van beloonbaarheid: drijfhout niet beloonbaar
                geldig altijd
                    Een Vondst is niet beloonbaar indien hij drijfhout is.

            // Bron: jci1.3:c:BWBR0005291&artikel=12
            Afwijking van beloonbaarheid: betwist niet beloonbaar
                geldig altijd
                    Een Vondst is niet beloonbaar indien hij betwist is.
            """;

        // 1. decided by lower negative default under open negative default
        JsonNode decided = ask(source, vondst("\"gewicht\": 10", "\"drijfhout\": {\"state\": \"holds\"}"));
        assertEquals("holdsNot", decided.at("/instances/0/characteristics/beloonbaar/state").asText());
        assertSentence("V1 is niet beloonbaar volgens 'drijfhout niet beloonbaar', omdat het onderwerp drijfhout is;"
                + " 'betwist niet beloonbaar' staat niet vast, maar kan alleen hetzelfde beslissen; verdrongen: de regel"
                + " 'beloonbaarheid' (gold, toekennen). Grondslag: jci1.3:c:BWBR0005291&artikel=11", decided);
        JsonNode ground = find(partsOf(decided, "V1 is niet beloonbaar volgens"), "GROUND");
        assertNotNull(ground);
        assertEquals("beloonbaarheid", ground.get("group").asText());
        assertEquals("/defaults/1", ground.get("pointer").asText());
        assertEquals(2, ground.get("position").asInt());
        assertEquals("drijfhout niet beloonbaar", ground.get("variant").asText());
        assertFalse(forOutputs(decided, "betwist").contains("V1 beloonbaar"), decided.get("openInputs").toString());

        // 2. root closing: open negative default above only giving-way defaults; no GROUND, not in openInputs
        JsonNode rootClosed = ask(source, vondst("\"gewicht\": 1", "\"drijfhout\": {\"state\": \"holdsNot\"}"));
        assertEquals("holdsNot", rootClosed.at("/instances/0/characteristics/beloonbaar/state").asText());
        assertSentence("V1 is niet beloonbaar, want geen variant besliste; 'betwist niet beloonbaar' staat niet vast, maar kan"
                + " alleen hetzelfde beslissen; 'drijfhout niet beloonbaar' gold niet, want niet geldt: het onderwerp"
                + " drijfhout is; de regel 'beloonbaarheid' gold niet, want niet geldt: zijn gewicht (1) is groter dan 2",
                rootClosed);
        assertNull(find(partsOf(rootClosed, "V1 is niet beloonbaar, want"), "GROUND"));
        assertFalse(forOutputs(rootClosed, "betwist").contains("V1 beloonbaar"), rootClosed.get("openInputs").toString());

        // 3. root closing beside input {"state": "holds"}: input holds, no GROUND, not in openInputs
        JsonNode withInput = ask(source, vondst("\"gewicht\": 1",
                "\"drijfhout\": {\"state\": \"holdsNot\"}, \"beloonbaar\": {\"state\": \"holds\"}"));
        assertEquals("holds", withInput.at("/instances/0/characteristics/beloonbaar/state").asText());
        assertSentence("V1 is beloonbaar uit de invoer, want geen variant besliste; 'betwist niet beloonbaar' staat niet vast,"
                + " maar kan alleen hetzelfde beslissen; 'drijfhout niet beloonbaar' gold niet, want niet geldt: het onderwerp"
                + " drijfhout is; de regel 'beloonbaarheid' gold niet, want niet geldt: zijn gewicht (1) is groter dan 2",
                withInput);
        assertNull(find(partsOf(withInput, "V1 is beloonbaar uit de invoer"), "GROUND"));
        assertFalse(forOutputs(withInput, "betwist").contains("V1 beloonbaar"), withInput.get("openInputs").toString());
    }

    /** Subtype characteristic stack (both decided and root-closing) beside an open loose rule on the supertype: the head and
     *  openInputs name the supertype rule's open input (gewicht), never betwist. */
    @Test
    void characteristic_stack_beside_open_supertype_rule() throws Exception {
        String source = MODEL + """
            Regel verkoopbaar
                geldig altijd
                    Een Vondst is verkoopbaar indien zijn gewicht groter is dan 2.

            Regel wrakverkoopbaar
                geldig altijd
                    Een Wrakvondst is verkoopbaar indien zijn diepte groter is dan 2.

            // Bron: jci1.3:c:BWBR0005291&artikel=21
            Afwijking van wrakverkoopbaar: drijfhout wrak
                geldig altijd
                    Een Wrakvondst is niet verkoopbaar indien hij drijfhout is.

            // Bron: jci1.3:c:BWBR0005291&artikel=22
            Afwijking van wrakverkoopbaar: betwist wrak
                geldig altijd
                    Een Wrakvondst is niet verkoopbaar indien hij betwist is.
            """;

        // 1. decided negative beside open supertype rule: head names gewicht, GROUND names drijfhout wrak, betwist absent
        JsonNode decided = ask(source, """
            {"instances": [{"type": "Wrakvondst", "id": "W1", "attributes": {"diepte": 5},
              "characteristics": {"drijfhout": {"state": "holds"}}}]}""");
        assertEquals("open", decided.at("/instances/0/characteristics/verkoopbaar/state").asText());
        assertSentence("W1: of hij verkoopbaar is, staat niet vast, want zijn gewicht is niet gegeven; 'drijfhout wrak'"
                + " besliste 'is niet verkoopbaar', omdat het onderwerp drijfhout is; 'betwist wrak' staat niet vast, maar kan"
                + " alleen hetzelfde beslissen; verdrongen: de regel 'wrakverkoopbaar' (gold, toekennen). Grondslag:"
                + " jci1.3:c:BWBR0005291&artikel=21", decided);
        assertEquals(List.of("W1 gewicht", "W1 verkoopbaar"), forOutputs(decided, "gewicht"));
        assertFalse(forOutputs(decided, "betwist").contains("W1 verkoopbaar"), decided.get("openInputs").toString());

        // 2. root closing beside open supertype rule: head names gewicht, no GROUND, betwist absent from verkoopbaar
        JsonNode rootClosed = ask(source, """
            {"instances": [{"type": "Wrakvondst", "id": "W1", "attributes": {"diepte": 1},
              "characteristics": {"drijfhout": {"state": "holdsNot"}}}]}""");
        assertEquals("open", rootClosed.at("/instances/0/characteristics/verkoopbaar/state").asText());
        assertSentence("W1: of hij verkoopbaar is, staat niet vast, want zijn gewicht is niet gegeven; 'betwist wrak' staat niet"
                + " vast, maar kan alleen hetzelfde beslissen; 'drijfhout wrak' gold niet, want niet geldt: het onderwerp"
                + " drijfhout is; de regel 'wrakverkoopbaar' gold niet, want niet geldt: zijn diepte (1) is groter dan 2",
                rootClosed);
        assertNull(find(partsOf(rootClosed, "W1: of hij verkoopbaar is"), "GROUND"));
        assertEquals(List.of("W1 gewicht", "W1 verkoopbaar"), forOutputs(rootClosed, "gewicht"));
        assertFalse(forOutputs(rootClosed, "betwist").contains("W1 verkoopbaar"), rootClosed.get("openInputs").toString());
    }

    /** Unordered group in both orders (open member before decider: Role.BEFORE; open member after decider: Role.SIBLING) and
     *  the 3-member open case {OPEN 0, HOLDS 0, OPEN 5} where only the blocking member (gemeld) appears in openInputs, head
     *  and heldBack. */
    @Test
    void unordered_group_both_orders_and_three_member_open_case() throws Exception {
        String source = MODEL + """
            Uitzonderingen ongeordend:
            // Bron: jci1.3:c:BWBR0005291&artikel=31
            Regel bergloon bij betwisting
                geldig altijd
                    Het bergloon van een Vondst moet gesteld worden op 0 indien hij betwist is.
            // Bron: jci1.3:c:BWBR0005291&artikel=32
            Regel bergloon bij schade
                geldig altijd
                    Het bergloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.

            Uitzonderingen ongeordend:
            // Bron: jci1.3:c:BWBR0005291&artikel=33
            Regel vindloon bij schade
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.
            // Bron: jci1.3:c:BWBR0005291&artikel=34
            Regel vindloon bij betwisting
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 0 indien hij betwist is.

            Uitzonderingen ongeordend:
            Regel havenloon bij betwisting
                geldig altijd
                    Het havenloon van een Vondst moet gesteld worden op 0 indien hij betwist is.
            Regel havenloon bij schade
                geldig altijd
                    Het havenloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.
            Regel havenloon bij melding
                geldig altijd
                    Het havenloon van een Vondst moet gesteld worden op 5 indien hij gemeld is.
            """;
        JsonNode answer = ask(source, vondst("", "\"beschadigd\": {\"state\": \"holds\"}"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 0}"), answer.at("/instances/0/attributes/bergloon"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 0}"), answer.at("/instances/0/attributes/vindloon"));
        assertEquals("open", answer.at("/instances/0/attributes/havenloon/state").asText());

        // Order 1: open member before decider (Role.BEFORE)
        assertSentence("V1 heeft als bergloon 0 volgens de regel 'bergloon bij schade': 0, omdat het onderwerp beschadigd"
                + " is; de regel 'bergloon bij betwisting' staat niet vast, maar kan alleen hetzelfde beslissen. Grondslag:"
                + " jci1.3:c:BWBR0005291&artikel=32", answer);
        JsonNode bergGround = find(partsOf(answer, "V1 heeft als bergloon 0"), "GROUND");
        assertEquals("bergloon bij schade", bergGround.get("variant").asText());

        // Order 2: open member AFTER decider (Role.SIBLING) — same-conclusion sentence, NOT "besliste niet anders"
        assertSentence("V1 heeft als vindloon 0 volgens de regel 'vindloon bij schade': 0, omdat het onderwerp beschadigd"
                + " is; de regel 'vindloon bij betwisting' staat niet vast, maar kan alleen hetzelfde beslissen. Grondslag:"
                + " jci1.3:c:BWBR0005291&artikel=33", answer);
        JsonNode vindGround = find(partsOf(answer, "V1 heeft als vindloon 0"), "GROUND");
        assertEquals("vindloon bij schade", vindGround.get("variant").asText());

        // 3-member open case {OPEN 0, HOLDS 0, OPEN 5}: head, heldBack and openInputs name only gemeld, not betwist
        assertSentence("V1: zijn havenloon staat niet vast, want het is niet gegeven of hij gemeld is; voor de regel"
                + " 'havenloon bij betwisting' staat niet vast: het onderwerp betwist is (niet gegeven); de regel"
                + " 'havenloon bij schade' gaf 0, maar beslist niet zolang de regel 'havenloon bij melding' niet vaststaat;"
                + " voor de regel 'havenloon bij melding' staat niet vast: het onderwerp gemeld is (niet gegeven)", answer);
        assertEquals(List.of("V1 havenloon", "V1 gemeld"), forOutputs(answer, "gemeld"));
        assertEquals(List.of("V1 betwist"), forOutputs(answer, "betwist"));
    }

    /** Nested exception groups in both forms: (a) open exception above a fall-back with the same conclusion; (b) unordered
     *  nested exception groups with the open member both before and after the decider. */
    @Test
    void nested_exception_groups_both_forms() throws Exception {
        String source = MODEL + """
            Regel strandloon
                geldig altijd
                    Het strandloon van een Vondst moet berekend worden als zijn gewicht maal 2.

            Regel vindloon
                geldig altijd
                    Het vindloon van een Vondst moet berekend worden als zijn gewicht maal 2.

            Regel bergloon
                geldig altijd
                    Het bergloon van een Vondst moet berekend worden als zijn gewicht maal 2.

            Afwijking van strandloon: strandloon bij schade
                geldig altijd
                    Het strandloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.

            Uitzondering op strandloon bij schade: strandloon bij betwisting
                geldig altijd
                    Het strandloon van een Vondst moet gesteld worden op 0 indien hij betwist is.

            Uitzonderingen ongeordend op vindloon:
            Regel vindloon bij betwisting
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 0 indien hij betwist is.
            Regel vindloon bij schade
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.

            Uitzonderingen ongeordend op bergloon:
            Regel bergloon bij schade
                geldig altijd
                    Het bergloon van een Vondst moet gesteld worden op 0 indien hij beschadigd is.
            Regel bergloon bij betwisting
                geldig altijd
                    Het bergloon van een Vondst moet gesteld worden op 0 indien hij betwist is.
            """;
        JsonNode answer = ask(source, vondst("\"gewicht\": 10", "\"beschadigd\": {\"state\": \"holds\"}"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 0}"), answer.at("/instances/0/attributes/strandloon"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 0}"), answer.at("/instances/0/attributes/vindloon"));
        assertEquals(json("{\"state\": \"filled\", \"value\": 0}"), answer.at("/instances/0/attributes/bergloon"));

        // (a) open exception above fall-back with the same conclusion
        assertSentence("V1 heeft als strandloon 0 volgens 'strandloon bij schade': 0, omdat het onderwerp beschadigd is;"
                + " 'strandloon bij betwisting' (uitzondering op 'strandloon bij schade' op 'strandloon') staat niet vast,"
                + " maar kan alleen hetzelfde beslissen; verdrongen: de regel 'strandloon' (onvoorwaardelijk, 20)", answer);

        // (b1) unordered nested exception group, open member before decider
        assertSentence("V1 heeft als vindloon 0 volgens 'vindloon bij schade' (uitzondering op 'vindloon'): 0, omdat het"
                + " onderwerp beschadigd is; 'vindloon bij betwisting' (uitzondering op 'vindloon') staat niet vast, maar kan"
                + " alleen hetzelfde beslissen; verdrongen: de regel 'vindloon' (onvoorwaardelijk, 20)", answer);

        // (b2) unordered nested exception group, open member AFTER decider (Role.SIBLING)
        assertSentence("V1 heeft als bergloon 0 volgens 'bergloon bij schade' (uitzondering op 'bergloon'): 0, omdat het"
                + " onderwerp beschadigd is; 'bergloon bij betwisting' (uitzondering op 'bergloon') staat niet vast, maar kan"
                + " alleen hetzelfde beslissen; verdrongen: de regel 'bergloon' (onvoorwaardelijk, 20)", answer);

        assertEquals(List.of("V1 betwist"), forOutputs(answer, "betwist"));
    }

    /** Provisional fault under an open constant (jutterswet-zelfde-conclusie-fout without asOfDate): HTTP 200 with open
     *  outcomes, no 500 on explanation/explanationParts/openInputs, provisionally faulted variants appear as
     *  VARIANT{decided=false, unreadable=true} with name alone, and openInputs names only betwist. */
    @Test
    void provisional_fault_under_open_constant() throws Exception {
        String source = Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-zelfde-conclusie-fout/source.rgs"));
        JsonNode answer = ask(source, vondst("", ""));
        assertEquals("open", answer.at("/instances/0/attributes/rekenloon/state").asText());
        assertEquals("open", answer.at("/instances/0/attributes/werkloon/state").asText());

        assertSentence("V1: zijn rekenloon staat niet vast, want het is niet gegeven of hij betwist is; voor 'betwist"
                + " rekenloon' staat niet vast: het onderwerp betwist is (niet gegeven); de regel 'rekenloon'", answer);
        assertSentence("V1: zijn werkloon staat niet vast, want het is niet gegeven of hij betwist is; voor 'betwist werkloon'"
                + " staat niet vast: het onderwerp betwist is (niet gegeven); de regel 'werkloon'", answer);

        for (String member : List.of("rekenloon", "werkloon")) {
            JsonNode parts = partsOf(answer, "V1: zijn " + member + " staat niet vast");
            JsonNode faulted = findVariant(parts, member);
            assertNotNull(faulted, parts.toString());
            assertFalse(faulted.get("decided").asBoolean());
            assertTrue(faulted.get("unreadable").asBoolean());
            assertFalse(faulted.has("displaced"));
            assertEquals("VARIANT{decided=false, unreadable=true}[«; de regel '» RULE«" + member + "» «'»]",
                    ExplanationPartsCheck.render(ServiceHttp.MAPPER.createArrayNode().add(faulted)));
        }
        assertEquals(List.of("V1 rekenloon", "V1 werkloon", "V1 betwist"), forOutputs(answer, "betwist"));
        assertEquals(1, answer.get("openInputs").size(), answer.get("openInputs").toString());
    }

    /** Initialisation default below an open constant with the same value: both through an inherited supertype initialisation
     *  folded into a subtype stack (ch. 7.10, pure RegelSpraak) and through uchar's jutterswet-zelfde-conclusie-initialisatie
     *  IR document (where a loose rule beside an initialisation stays open). */
    @Test
    void initialisation_default_under_open_constant() throws Exception {
        // 1. pure RegelSpraak: subtype defaults stack above inherited supertype initialisation default (ch. 7.10)
        String source = MODEL + """
            Regel basisvindloon
                geldig altijd
                    Het vindloon van een Vondst moet geïnitialiseerd worden op 25.

            Regel wrakvindloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op 10 indien zijn diepte groter is dan 20.

            Afwijking van wrakvindloon: betwist wrakvindloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op 25 indien hij betwist is.
            """;
        JsonNode subtypeAnswer = ask(source, "{\"instances\": [{\"type\": \"Wrakvondst\", \"id\": \"W1\","
                + " \"attributes\": {\"diepte\": 5}}]}");
        assertEquals(json("{\"state\": \"filled\", \"value\": 25}"), subtypeAnswer.at("/instances/0/attributes/vindloon"));
        assertSentence("W1 heeft als vindloon 25 volgens de regel 'basisvindloon': 25, omdat zijn vindloon in de invoer niet"
                + " gegeven was; 'betwist wrakvindloon' staat niet vast, maar kan alleen hetzelfde beslissen; de regel"
                + " 'wrakvindloon' gold niet, want niet geldt: zijn diepte (5) is groter dan 20", subtypeAnswer);
        assertFalse(forOutputs(subtypeAnswer, "betwist").contains("W1 vindloon"), subtypeAnswer.get("openInputs").toString());

        // 2. uchar's jutterswet-zelfde-conclusie-initialisatie IR document (stack with initialisation default vs loose rule +
        //    loose initialisation)
        Path ir = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-zelfde-conclusie-initialisatie/ir.json");
        String documentJson = Files.readString(ir);
        JsonNode document = json(documentJson);
        DecisionCore core = DecisionCore.buildFromDocument(descriptor(document), documentJson, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
        JsonNode irAnswer = post(core, vondst("", ""));
        assertEquals(json("{\"state\": \"filled\", \"value\": 5}"), irAnswer.at("/instances/0/attributes/strandloon"));
        assertEquals("open", irAnswer.at("/instances/0/attributes/meldloon/state").asText());
        assertSentence("V1 heeft als strandloon 5 volgens 'strandloon aanvankelijk': 5, omdat zijn strandloon in de invoer"
                + " niet gegeven was; 'betwist strandloon' staat niet vast, maar kan alleen hetzelfde beslissen", irAnswer);
        assertEquals(List.of("V1 meldloon", "V1 betwist"), forOutputs(irAnswer, "betwist"));
    }

    // ---------------- helpers ----------------

    private static String vondst(String attributes, String characteristics) {
        return "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {" + attributes + "},"
                + " \"characteristics\": {" + characteristics + "}}]}";
    }

    private static JsonNode ask(String source, String request) throws Exception {
        JsonNode document = json(UcharBuild.document(Resolver.resolve(source)));
        DecisionCore core = DecisionCore.assemble(descriptor(document), Map.of("b.rgs", source), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        return post(core, request);
    }

    private static String descriptor(JsonNode document) {
        ObjectNode descriptor = ServiceHttp.MAPPER.createObjectNode();
        descriptor.putObject("service").put("name", "zelfde-conclusie").put("version", "1");
        ObjectNode ep = descriptor.putArray("entrypoints").addObject().put("name", "zelfde").put("path", "zelfde");
        ArrayNode names = ep.putObject("scope").putArray("ruleNames");
        for (String list : List.of("rules", "prioritizedDefaults", "consistencies"))
            for (JsonNode f : document.at("/rules/" + list)) names.add(f.get("name").asText());
        ep.putArray("input");
        ArrayNode output = ep.putArray("output");
        for (JsonNode t : document.at("/model/objectTypes")) {
            for (JsonNode a : t.get("attributes")) output.add(t.get("name").asText() + "." + a.get("name").asText());
            for (JsonNode k : t.path("characteristics")) output.add(t.get("name").asText() + ":" + k.get("name").asText());
        }
        descriptor.putObject("parameters");
        return descriptor.toString();
    }

    private static JsonNode post(DecisionCore core, String request) throws Exception {
        ServiceHttp service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port()
                            + "/zelfde?explanation=true&explanationParts=true&openInputs=true"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode answer = json(r.body());
            ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
            for (JsonNode parts : answer.get("explanationParts")) {
                String without = ExplanationPartsCheck.withoutOmissibleParts(parts);
                assertFalse(without.endsWith(": ") || without.endsWith("; ") || without.contains("()"), without);
            }
            return answer;
        } finally {
            service.stop(0);
        }
    }

    private static List<String> forOutputs(JsonNode answer, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : answer.get("openInputs"))
            if (i.get("name").asText().equals(name))
                for (JsonNode o : i.get("forOutputs")) out.add(o.get("instance").asText() + " " + o.get("name").asText());
        return out;
    }

    private static void assertSentence(String expected, JsonNode answer) {
        List<String> texts = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) texts.add(z.asText());
        assertEquals(1, texts.stream().filter(expected::equals).count(),
                () -> expected + "\nniet in\n" + String.join("\n", texts));
    }

    private static JsonNode partsOf(JsonNode answer, String prefix) {
        for (int i = 0; i < answer.get("explanation").size(); i++)
            if (answer.get("explanation").get(i).asText().startsWith(prefix))
                return answer.get("explanationParts").get(i);
        throw new AssertionError("geen zin begint met " + prefix + "\nin\n" + answer.get("explanation"));
    }

    private static JsonNode find(JsonNode parts, String kind) {
        for (JsonNode p : parts) {
            if (p.get("kind").asText().equals(kind)) return p;
            if (!p.has("parts")) continue;
            JsonNode found = find(p.get("parts"), kind);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonNode findVariant(JsonNode parts, String ruleName) {
        for (JsonNode p : parts) {
            if (p.get("kind").asText().equals("VARIANT") && hasRule(p, ruleName)) return p;
            if (!p.has("parts")) continue;
            JsonNode found = findVariant(p.get("parts"), ruleName);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasRule(JsonNode node, String ruleName) {
        if (node.get("kind").asText().equals("RULE") && ruleName.equals(node.get("text").asText())) return true;
        if (!node.has("parts")) return false;
        for (JsonNode child : node.get("parts")) if (hasRule(child, ruleName)) return true;
        return false;
    }

    private static JsonNode json(String text) throws Exception {
        return ServiceHttp.MAPPER.readTree(text);
    }
}
