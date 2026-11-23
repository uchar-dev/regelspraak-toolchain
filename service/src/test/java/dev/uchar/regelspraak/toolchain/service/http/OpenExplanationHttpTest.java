package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.ExplanationPartsCheck;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explanation of a strict rule set ({@link OpenValuesSource#WRAK_SOURCE}; uchar's RUNTIME-SEMANTICS ch. 5.12, "Open values"): what an
 * open outcome says and what keeps it open, and what an outcome that stands says beside something open, literally, with
 * its parts. The sentences are written by hand from the rules of the source.
 */
class OpenExplanationHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static ServiceHttp service;

    @BeforeAll
    static void start() throws Exception {
        service = ServiceHttp.bind(OpenValuesSource.wrak(CoreConfig.strict()), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    // ---------------- a computed value ----------------

    @Test
    void a_value_that_reads_an_open_input() throws Exception {
        assertSentence("W1: zijn ladingwaarde staat niet vast, want zijn lading is niet gegeven; voor de regel"
                + " 'ladingwaarde' staat niet vast: zijn lading maal 2, met zijn lading (niet gegeven)", wrak(""));
    }

    @Test
    void a_value_that_reads_an_open_derived_member() throws Exception {
        assertSentence("W1: zijn wrakloon staat niet vast, want zijn ladingwaarde staat niet vast; voor de regel 'wrakloon'"
                + " staat niet vast: zijn ladingwaarde plus zijn lengte, met zijn ladingwaarde (staat niet vast)", wrak(""));
    }

    /** What stands before the open read is named with its value, and what comes after it is not. */
    @Test
    void the_operands_up_to_the_open_one() throws Exception {
        assertSentence("W1: zijn wrakloon staat niet vast, want zijn lengte is niet gegeven; voor de regel 'wrakloon' staat"
                + " niet vast: zijn ladingwaarde plus zijn lengte, met zijn ladingwaarde (10) en zijn lengte (niet gegeven)",
                wrak("\"attributes\": {\"lading\": 5}"));
        // no "vóór de afronding" of a value that was not computed
        assertSentence("W1: zijn diepteklasse staat niet vast, want zijn diepte is niet gegeven; voor de regel 'diepteklasse'"
                + " staat niet vast: zijn diepte gedeeld door 10 rekenkundig afgerond op 0 decimalen, met zijn diepte (niet"
                + " gegeven)", wrak(""));
    }

    @Test
    void a_member_of_another_object() throws Exception {
        assertSentence("W1: zijn vindpremie staat niet vast, want van J1 is zijn bonus niet gegeven; voor de regel 'vindpremie'"
                + " staat niet vast: zijn lengte plus de bonus van de vinder van het Wrak, met zijn lengte (30) en de bonus van"
                + " de vinder van het Wrak (van J1 niet gegeven)", post("""
                {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1"]}},
                               {"type": "Wrak", "id": "W1", "attributes": {"lengte": 30}}]}"""));
    }

    /** Two rules on one target, both with a condition that is undetermined; the value 7 of the request waits for them. */
    @Test
    void an_undetermined_condition_and_an_input_that_does_not_stand() throws Exception {
        assertSentence("W1: zijn voorschot staat niet vast, want zijn diepte is niet gegeven; de invoerwaarde 7 geldt pas als"
                + " vaststaat dat geen regel een waarde geeft; voor de regel 'voorschot' staat niet vast: zijn diepte (niet"
                + " gegeven) is groter dan 10; voor de regel 'voorschot gemeld' staat niet vast: het onderwerp gemeld is (niet"
                + " gegeven)", wrak("\"attributes\": {\"voorschot\": 7}"));
        assertSentence("W1: zijn voorschot staat niet vast, want het is niet gegeven of hij gemeld is; de regel 'voorschot' gold"
                + " niet, want niet geldt: zijn diepte (5) is groter dan 10; voor de regel 'voorschot gemeld' staat niet vast:"
                + " het onderwerp gemeld is (niet gegeven)", wrak("\"attributes\": {\"diepte\": 5}"));
    }

    /** The filter of the sum tests the diepte that is not given on gevuld, so the sum is open. */
    @Test
    void a_sum_whose_filter_tests_an_open_member() throws Exception {
        assertSentence("J1: zijn gepeilde ladingsom staat niet vast, want van W1 is zijn diepte niet gegeven; voor de regel"
                + " 'gepeilde ladingsom' staat niet vast: de som van de lading van alle gevonden wrakken van de Jutter die aan"
                + " de volgende voorwaarde voldoen: zijn diepte is gevuld (staat niet vast)",
                jutter("\"lading\": 5", "\"lading\": 7", "\"lading\": 9"));
    }

    // ---------------- a characteristic ----------------

    @Test
    void an_open_characteristic_and_its_negation() throws Exception {
        JsonNode answer = wrak("\"attributes\": {\"lading\": 5}");
        assertSentence("W1: of hij gevaarlijk is, staat niet vast, want zijn diepte is niet gegeven; voor de regel 'gevaar' staat"
                + " niet vast: zijn diepte (niet gegeven) is groter dan 10", answer);
        // the other rule did not hold, and the cause is that of the rule that leaves it open
        assertSentence("W1: of hij gevaarlijk is, staat niet vast, want zijn diepte is niet gegeven; de regel 'gevaar door"
                + " lading' gold niet, want niet geldt: zijn lading (5) is groter dan 100", answer);
        assertSentence("W1: of hij stil is, staat niet vast, want het staat niet vast of hij gevaarlijk is; voor de regel 'stilte'"
                + " staat niet vast: het onderwerp niet gevaarlijk is (staat niet vast)", answer);
    }

    // ---------------- a defaults group ----------------

    @Test
    void an_open_default_and_the_defaults_below_it() throws Exception {
        assertSentence("W1: zijn wrakpremie staat niet vast, want het is niet gegeven of hij betwist is; voor 'betwiste"
                + " wrakpremie' staat niet vast: het onderwerp betwist is (niet gegeven); niet aan de beurt: 'gemerkte"
                + " wrakpremie' (gold, 5), de regel 'wrakpremie' (onvoorwaardelijk, 10)",
                wrak("\"attributes\": {\"lading\": 5}, \"characteristics\": {\"gemerkt\": {\"state\": \"holds\"}}"));
        // a displaced default whose reading does not stand is named alone
        assertSentence("W1: zijn wrakpremie staat niet vast, want het is niet gegeven of hij betwist is; voor 'betwiste"
                + " wrakpremie' staat niet vast: het onderwerp betwist is (niet gegeven); niet aan de beurt: 'gemerkte"
                + " wrakpremie', de regel 'wrakpremie' (onvoorwaardelijk, 10)", wrak(""));
    }

    @Test
    void a_default_above_the_open_one_and_a_default_whose_value_is_open() throws Exception {
        assertSentence("W1: zijn wrakpremie staat niet vast, want zijn lading is niet gegeven; 'betwiste wrakpremie' gold niet,"
                + " want niet geldt: het onderwerp betwist is; voor 'gemerkte wrakpremie' staat niet vast: zijn lading (niet"
                + " gegeven); niet aan de beurt: de regel 'wrakpremie' (onvoorwaardelijk, 10)", wrak("\"characteristics\":"
                + " {\"betwist\": {\"state\": \"holdsNot\"}, \"gemerkt\": {\"state\": \"holds\"}}"));
    }

    @Test
    void an_open_stack_on_a_characteristic() throws Exception {
        assertSentence("W1: of hij beloonbaar is, staat niet vast, want het is niet gegeven of hij betwist is; voor 'betwiste"
                + " beloonbaarheid' staat niet vast: het onderwerp betwist is (niet gegeven); niet aan de beurt: de regel"
                + " 'beloonbaarheid' (gold, toekennen)", wrak("\"attributes\": {\"lengte\": 30}"));
    }

    /** Below the open default of the opgave and beside the open rule the initialisation is not at its turn, so the target is
     *  open. */
    @Test
    void an_initialisation_below_an_open_default_and_beside_an_open_rule_is_not_at_its_turn() throws Exception {
        JsonNode answer = wrak("");
        assertSentence("W1: zijn kadegeld staat niet vast, want zijn berekende kadegeld staat niet vast; 'opgegeven kadegeld' gaf"
                + " geen waarde; voor 'berekende kadegeld' staat niet vast: zijn berekende kadegeld (staat niet vast); niet aan"
                + " de beurt: 'kadegeld basis' (onvoorwaardelijk, 25)", answer);
        assertSentence("W1: zijn loodsgeld staat niet vast, want het is niet gegeven of hij gemeld is; voor de regel 'loodsgeld'"
                + " staat niet vast: het onderwerp gemeld is (niet gegeven); niet aan de beurt: de regel 'loodsgeld basis'",
                answer);
        assertEquals("VARIANT{decided=false, displaced=true}[«; niet aan de beurt: de regel '» RULE«loodsgeld basis» «'»]",
                ExplanationPartsCheck.render(ServiceHttp.MAPPER.createArrayNode().add(group(sentence(answer,
                        "W1: zijn loodsgeld"), "VARIANT", "displaced"))));
    }

    @Test
    void an_initialisation_beside_an_input_does_not_decide() throws Exception {
        assertSentence("W1: zijn loodsgeld staat niet vast, want het is niet gegeven of hij gemeld is; de invoerwaarde 8 geldt pas"
                + " als vaststaat dat geen regel een waarde geeft; voor de regel 'loodsgeld' staat niet vast: het onderwerp"
                + " gemeld is (niet gegeven); niet aan de beurt: de regel 'loodsgeld basis'",
                wrak("\"attributes\": {\"loodsgeld\": 8}"));
    }

    @Test
    void an_exception_that_decides_beside_one_that_is_open() throws Exception {
        assertSentence("W1: zijn bergingsloon staat niet vast, want het is niet gegeven of hij beschadigd is; 'betwist"
                + " bergingsloon' (uitzondering op 'bergingsloon') gaf 0, maar beslist niet zolang 'beschadigd bergingsloon'"
                + " (uitzondering op 'bergingsloon') niet vaststaat; voor 'beschadigd bergingsloon' (uitzondering op"
                + " 'bergingsloon') staat niet vast: het onderwerp beschadigd is (niet gegeven); niet aan de beurt: de regel"
                + " 'bergingsloon' (gold, 100)",
                wrak("\"attributes\": {\"lengte\": 30}, \"characteristics\": {\"betwist\": {\"state\": \"holds\"}}"));
    }

    // ---------------- a composite ----------------

    @Test
    void a_composite_that_is_undetermined_names_the_member_that_keeps_it_so() throws Exception {
        JsonNode answer = wrak("\"attributes\": {\"lengte\": 5}, \"characteristics\": {\"betwist\": {\"state\": \"holds\"}}");
        assertSentence("W1: of hij gevaarlijk is, staat niet vast, want zijn diepte is niet gegeven; voor de regel 'gevaar' staat"
                + " niet vast: zijn diepte (niet gegeven) is groter dan 10", answer);
        assertSentence("W1: of hij vrij is, staat niet vast, want zijn diepte is niet gegeven; voor de regel 'vrijdom' staat niet"
                + " vast: ten minste 2 van de 3 voorwaarden gelden: zijn diepte (niet gegeven) is groter dan 10", answer);
        assertEquals("«; voor de regel '» RULE«vrijdom» «' » CONNECTIVE«staat niet vast» «: » CONDITION{undetermined=true,"
                + " quantifier=atLeast, threshold=2}[CONNECTIVE«ten minste 2 van de 3 voorwaarden gelden» «: »"
                + " CONDITION{undetermined=true}[QUOTE{declaration=ATTRIBUUT, owner=Wrak, canonical=diepte, object=W1}"
                + "«zijn diepte» READING{open={\"object\":\"W1\",\"canonical\":\"diepte\",\"kind\":\"attribute\"}}"
                + "[« (niet gegeven)»] « is groter dan » QUOTE«10»]]",
                ExplanationPartsCheck.render(group(sentence(answer, "W1: of hij vrij is"), "VARIANT").get("parts")));
    }

    /** The member that decides is named, and not a word of the member that is open. */
    @Test
    void a_composite_that_another_member_decides() throws Exception {
        JsonNode answer = wrak("\"attributes\": {\"lengte\": 30}, \"characteristics\": {\"betwist\": {\"state\": \"holds\"}}");
        assertSentence("W1 is gevaarlijk, omdat zijn lengte (30) is groter dan 20", answer);
        assertSentence("W1 is vrij, omdat ten minste 2 van de 3 voorwaarden gelden: het onderwerp betwist is en zijn lengte (30)"
                + " is groter dan 20", answer);
        assertSentence("W1 is niet keuringsplichtig, want niet geldt: zijn lengte (5) is groter dan 20",
                wrak("\"attributes\": {\"lengte\": 5}"));
    }

    // ---------------- folds ----------------

    @Test
    void a_fold_over_a_set_with_an_open_member() throws Exception {
        assertSentence("J1: zijn ladingsom staat niet vast, want van W2 is zijn lading niet gegeven; voor de regel 'ladingsom'"
                + " staat niet vast: de som van de lading van alle gevonden wrakken van de Jutter (staat niet vast)",
                jutter("\"lading\": 5", "", "\"lading\": 9"));
    }

    @Test
    void a_quantifier_fold_that_is_undetermined() throws Exception {
        JsonNode answer = jutter("\"diepte\": 20", "\"diepte\": 15", "");
        assertSentence("J1: of hij kundig is, staat niet vast, want van W3 is zijn diepte niet gegeven; voor de regel 'kundigheid'"
                + " staat niet vast: aan de voorwaarde \"de diepte van alle gevonden wrakken van de Jutter groter is dan 10\" is"
                + " voldaan: wel voor W1 en W2, niet vast voor W3", answer);
        assertEquals("MEMBERS{members=2, holding=2, unjudged=0, unsettled=1}[«: wel voor » SUBJECT«W1» « en » SUBJECT«W2»"
                + " «, niet vast voor » SUBJECT«W3»]", ExplanationPartsCheck.render(ServiceHttp.MAPPER.createArrayNode()
                .add(group(sentence(answer, "J1: of hij kundig is"), "MEMBERS"))));
    }

    /** W2 of diepte 5 decides; W3 is open, and is one of the three. */
    @Test
    void a_quantifier_fold_that_a_member_decides() throws Exception {
        assertSentence("J1 is niet kundig, want aan de voorwaarde \"de diepte van alle gevonden wrakken van de Jutter groter is dan"
                + " 10\" is niet voldaan: niet voor W2 (1 van de 3)", jutter("\"diepte\": 20", "\"diepte\": 5", ""));
    }

    /** It is open whether W2 counts (gemerkt): the filter reads an open member, so the fold gives its verdict and names no
     *  member, and the head says what keeps it open. */
    @Test
    void a_quantifier_fold_with_an_open_filter() throws Exception {
        assertSentence("J1: of hij keurder is, staat niet vast, want het is van W2 niet gegeven of hij gemerkt is; voor de regel"
                + " 'keurderschap' staat niet vast: aan de voorwaarde \"de diepte van alle gevonden wrakken van de Jutter die"
                + " gemerkt zijn groter is dan 10\" is voldaan", post("""
                {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1", "W2"]}},
                  {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20}, "characteristics": {"gemerkt": {"state": "holds"}}},
                  {"type": "Wrak", "id": "W2", "attributes": {"diepte": 5}}]}"""));
    }

    // ---------------- the tests on leeg and gevuld ----------------

    /** A test on leeg or gevuld over an open member is undetermined, as every other condition over an open value. */
    @Test
    void a_test_on_leeg_over_an_open_member_is_undetermined() throws Exception {
        JsonNode answer = wrak("");
        assertSentence("W1: of hij ongepeild is, staat niet vast, want zijn diepte is niet gegeven; voor de regel 'ongepeild' staat"
                + " niet vast: zijn diepte (niet gegeven) leeg is", answer);
        assertSentence("W1: of hij ongewaardeerd is, staat niet vast, want zijn ladingwaarde staat niet vast; voor de regel"
                + " 'ongewaardeerd' staat niet vast: zijn ladingwaarde (staat niet vast) leeg is", answer);
        assertSentence("W1: of hij zeldzaam is, staat niet vast, want zijn diepte is niet gegeven; voor de regel 'zeldzaam' staat"
                + " niet vast: niet zijn diepte (niet gegeven) gevuld is", answer);
        assertEquals("CONDITION{undetermined=true}[QUOTE{declaration=ATTRIBUUT, owner=Wrak, canonical=diepte, object=W1}«zijn"
                + " diepte» READING{open={\"object\":\"W1\",\"canonical\":\"diepte\",\"kind\":\"attribute\"}}"
                + "[« (niet gegeven)»] « leeg is»]", ExplanationPartsCheck.render(ServiceHttp.MAPPER.createArrayNode()
                .add(group(sentence(answer, "W1: of hij ongepeild is"), "CONDITION"))));
    }

    @Test
    void a_test_on_gevuld_over_an_open_member() throws Exception {
        JsonNode answer = wrak("");
        assertSentence("W1: of hij gepeild is, staat niet vast, want zijn diepte is niet gegeven; voor de regel 'gepeild' staat niet"
                + " vast: zijn diepte (niet gegeven) gevuld is", answer);
        JsonNode condition = group(sentence(answer, "W1: of hij gepeild is"), "CONDITION");
        assertNull(condition.get("holds"));
        assertEquals(true, condition.get("undetermined").asBoolean());
        assertNull(condition.get("closure"));
    }

    /** The filled-value count and the sum with a filter on gevuld break off at the diepte of W3, so they are open, and so is the
     *  comparison that reads the count. */
    @Test
    void a_fold_with_an_open_member_in_a_value_and_in_a_condition() throws Exception {
        JsonNode answer = jutter("\"diepte\": 20, \"lading\": 5", "\"diepte\": 15, \"lading\": 7", "");
        assertSentence("J1: zijn aantal gepeilde wrakken staat niet vast, want van W3 is zijn diepte niet gegeven; voor de regel"
                + " 'aantal gepeild' staat niet vast: het aantal gevulde waarden van de diepte van alle gevonden wrakken van de"
                + " Jutter (staat niet vast)", answer);
        assertSentence("J1: zijn gepeilde ladingsom staat niet vast, want van W3 is zijn diepte niet gegeven; voor de regel"
                + " 'gepeilde ladingsom' staat niet vast: de som van de lading van alle gevonden wrakken van de Jutter die aan de"
                + " volgende voorwaarde voldoen: zijn diepte is gevuld (staat niet vast)", answer);
        assertSentence("J1: of hij volledig gepeild is, staat niet vast, want van W3 is zijn diepte niet gegeven; voor de regel"
                + " 'volledige peiling' staat niet vast: het aantal gevulde waarden van de diepte van alle gevonden wrakken van"
                + " de Jutter (staat niet vast) is gelijk aan 3", answer);
    }

    // ---------------- consistency, and what stands ----------------

    @Test
    void a_consistency_that_does_not_decide() throws Exception {
        assertSentence("of W1 voldoet aan de regel 'dieptegrens', staat niet vast, want zijn diepte is niet gegeven; het staat niet"
                + " vast: De diepte van een Wrak (niet gegeven) is kleiner dan 500", wrak(""));
        for (String diepte : List.of("5", "600"))
            assertFalse(texts(wrak("\"attributes\": {\"diepte\": " + diepte + "}")).toString().contains("dieptegrens"));
    }

    /** The rule that wrote decides, and so does the rule that attributes; the other rule is open and changes nothing of it. */
    @Test
    void an_outcome_that_stands_beside_an_open_writer() throws Exception {
        JsonNode answer = wrak("\"attributes\": {\"diepte\": 20}");
        assertSentence("W1 heeft als voorschot 3 volgens de regel 'voorschot': 3, omdat zijn diepte (20) is groter dan 10; of de"
                + " regel 'voorschot gemeld' geldt, staat niet vast", answer);
        assertSentence("W1 is gevaarlijk, omdat zijn diepte (20) is groter dan 10", answer);
        assertSentence("W1 is gevaarlijk, maar of de regel 'gevaar door lading' geldt, staat niet vast", answer);
    }

    /** Everything is given: no sentence speaks of what does not stand. */
    @Test
    void with_everything_given_nothing_is_open() throws Exception {
        for (String text : texts(post(everything()))) {
            assertFalse(text.contains("staat niet vast") || text.contains("niet gegeven") || text.contains("dan kan dit"), text);
        }
    }

    // ---------------- the parts ----------------

    @Test
    void the_parts_of_an_open_outcome() throws Exception {
        assertEquals("SUBJECT«W1» «: zijn » NAME{declaration=ATTRIBUUT, owner=Wrak, canonical=ladingwaarde, object=W1}«ladingwaarde»"
                + " « staat niet vast, » CONNECTIVE«want» « zijn » NAME{declaration=ATTRIBUUT, owner=Wrak, canonical=lading,"
                + " object=W1}«lading» « is niet gegeven» VARIANT{decided=false}[«; voor de regel '» RULE«ladingwaarde» «' »"
                + " CONNECTIVE«staat niet vast» COMPUTATION[«: » QUOTE«zijn lading maal 2» «, met » QUOTE{declaration=ATTRIBUUT,"
                + " owner=Wrak, canonical=lading, object=W1}«zijn lading»"
                + " READING{open={\"object\":\"W1\",\"canonical\":\"lading\",\"kind\":\"attribute\"}}[« (niet gegeven)»]]]",
                ExplanationPartsCheck.render(sentence(wrak(""), "W1: zijn ladingwaarde")));
        assertEquals("CONDITION{undetermined=true}[«het onderwerp niet » NAME{declaration=KENMERK, owner=Wrak,"
                + " canonical=gevaarlijk, object=W1}«gevaarlijk» « is» READING{open={\"object\":\"W1\",\"canonical\":"
                + "\"gevaarlijk\",\"kind\":\"characteristic\"}}[« (staat niet vast)»]]",
                ExplanationPartsCheck.render(ServiceHttp.MAPPER.createArrayNode()
                        .add(group(sentence(wrak(""), "W1: of hij stil is"), "CONDITION"))));
        assertEquals(json("{\"object\": \"J1\", \"canonical\": \"bonus\", \"kind\": \"attribute\"}"), group(sentence(post("""
                {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1"]}},
                               {"type": "Wrak", "id": "W1", "attributes": {"lengte": 30}}]}"""), "W1: zijn vindpremie"),
                "READING", "open").get("open"));
        assertEquals(true, group(sentence(wrak(""), "W1: zijn wrakpremie"), "VARIANT", "undetermined").get("displaced").asBoolean());
    }

    /** The parts of every sentence are the sentence; without the groups a reader may leave out it is a sentence still, and
     *  that of an open outcome is its head; its first name is the member, as of every sentence over a member. */
    @Test
    void the_parts_are_the_sentences_and_the_head_stands_alone() throws Exception {
        List<String> heads = new ArrayList<>();
        for (JsonNode answer : List.of(wrak(""), jutter("\"diepte\": 20", "", ""), post(everything()))) {
            ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
            for (JsonNode parts : answer.get("explanationParts")) {
                String without = ExplanationPartsCheck.withoutOmissibleParts(parts);
                assertFalse(without.endsWith(": ") || without.endsWith("; ") || without.contains("()"), without);
                heads.add(without);
                if (!"SUBJECT".equals(parts.get(0).get("kind").asText())
                        || !parts.get(1).get("text").asText().startsWith(":")) continue;
                assertEquals("NAME", parts.get(2).get("kind").asText(), parts.toString());
                assertEquals(parts.get(0).get("text"), parts.get(2).get("object"), parts.toString());
            }
        }
        for (String head : List.of("W1: zijn voorschot staat niet vast, want zijn diepte is niet gegeven",
                "W1: of hij stil is, staat niet vast, want het staat niet vast of hij gevaarlijk is",
                "J1: zijn ladingsom staat niet vast, want van W1 is zijn lading niet gegeven",
                "W1: zijn wrakpremie staat niet vast, want het is niet gegeven of hij betwist is"))
            assertTrue(heads.contains(head), head + " niet in " + heads);
    }

    /** No sentence calls an open value leeg or onbekend: the word stands of what is settled leeg alone, the opgegeven kadegeld,
     *  whose input is complete, and as the word of a test on leeg after the reading of its operand, which is the rule's. */
    @Test
    void no_sentence_calls_what_is_open_leeg_or_unknown() throws Exception {
        for (JsonNode answer : List.of(wrak(""), jutter("", "", ""), wrak("\"attributes\": {\"lengte\": 5}")))
            for (String text : texts(answer)) {
                assertFalse(text.contains("onbekend"), text);
                assertFalse(text.replace("zijn opgegeven kadegeld (leeg)", "").replace("het Wrak (leeg)", "")
                        .replace(") leeg is", ")").contains("leeg"), text);
            }
    }

    // ---------------- helpers ----------------

    private static String everything() {
        return """
            {"instances": [{"type": "Jutter", "id": "J1", "attributes": {"bonus": 4}, "roles": {"gevonden wrakken": ["W1"]}},
              {"type": "Wrak", "id": "W1", "attributes": {"diepte": 20, "lading": 5, "lengte": 30},
               "characteristics": {"gemeld": {"state": "holds"}, "betwist": {"state": "holdsNot"},
                                   "beschadigd": {"state": "holdsNot"}, "gemerkt": {"state": "holdsNot"}}}]}""";
    }

    private static JsonNode wrak(String given) throws Exception {
        return post("{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"" + (given.isEmpty() ? "" : ", " + given) + "}]}");
    }

    /** Jutter J1 who found W1, W2 and W3 with the given attributes. */
    private static JsonNode jutter(String w1, String w2, String w3) throws Exception {
        return post("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1", "W2", "W3"]}},
              {"type": "Wrak", "id": "W1", "attributes": {%s}}, {"type": "Wrak", "id": "W2", "attributes": {%s}},
              {"type": "Wrak", "id": "W3", "attributes": {%s}}]}""".formatted(w1, w2, w3));
    }

    private static List<String> texts(JsonNode answer) {
        List<String> out = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) out.add(z.asText());
        return out;
    }

    private static void assertSentence(String expected, JsonNode answer) {
        List<String> texts = texts(answer);
        assertEquals(1, texts.stream().filter(expected::equals).count(), () -> expected + "\nniet in\n"
                + String.join("\n", texts));
    }

    /** The parts of the sentence that begins with {@code begin}. */
    private static JsonNode sentence(JsonNode answer, String begin) {
        for (int i = 0; i < answer.get("explanation").size(); i++)
            if (answer.get("explanation").get(i).asText().startsWith(begin)) return answer.get("explanationParts").get(i);
        throw new AssertionError("geen zin begint met " + begin);
    }

    private static JsonNode group(JsonNode parts, String kind) {
        JsonNode found = find(parts, kind, null);
        assertNotNull(found, () -> "geen " + kind + " in " + parts);
        return found;
    }

    /** The first group of kind {@code kind} with the key {@code key}. */
    private static JsonNode group(JsonNode parts, String kind, String key) {
        JsonNode found = find(parts, kind, key);
        assertNotNull(found, () -> "geen " + kind + " met " + key + " in " + parts);
        return found;
    }

    private static JsonNode find(JsonNode parts, String kind, String key) {
        for (JsonNode p : parts) {
            if (p.get("kind").asText().equals(kind) && (key == null || p.has(key))) return p;
            if (!p.has("parts")) continue;
            JsonNode found = find(p.get("parts"), kind, key);
            if (found != null) return found;
        }
        return null;
    }

    private static JsonNode post(String body) throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port()
                        + "/wrak?explanationParts=true")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode answer = json(r.body());
        // the answer without an explanation is the same beside it
        HttpResponse<String> plain = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port()
                        + "/wrak")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(json(plain.body()), ((com.fasterxml.jackson.databind.node.ObjectNode) answer.deepCopy())
                .without(List.of("explanation", "explanationParts")));
        return answer;
    }

    private static JsonNode json(String text) throws Exception {
        return ServiceHttp.MAPPER.readTree(text);
    }
}
