package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ExplanationPartsCheck;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explanation of a strict rule set in forms that meet each other: an exception group above an initialisation that a subtype
 * inherits, a characteristic the request gives beside a stack that is open, a rule beside the stack of a subtype, and a
 * quantifier fold whose filter tests an open member. Every test has a rule set of its own; the sentences are written by
 * hand.
 */
class OpenExplanationFormsHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static final String VONDST = """
        Afleiden uit leeg: nee

        Objecttype de Jutter (mv: Jutters) (bezield)
            is kundig kenmerk (bijvoeglijk);
            is keurder kenmerk (bijvoeglijk);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (geheel getal);
            de taxatie Numeriek (geheel getal);
            het keurgewicht Numeriek (geheel getal);
            het vindloon Numeriek (geheel getal);
            is betwist kenmerk (bijvoeglijk);
            is beschadigd kenmerk (bijvoeglijk);
            is verkoopbaar kenmerk (bijvoeglijk);
            is alleen kenmerk (bijvoeglijk);

        Objecttype de Wrakvondst is een Vondst (mv: Wrakvondsten)
            de diepte Numeriek (geheel getal);

        Feittype vangst
            de jutter Jutter
            de vondst (mv: vondsten) Vondst
            één jutter heeft meerdere vondsten

        Parameter het TARIEF : Numeriek (geheel getal)

        """;

    /** One member of the exception group gave 0 and did not decide beside one that is open, so the default is open, and below it
     *  its content and the initialisation are not at their turn. */
    @Test
    void an_initialisation_below_an_exception_group_with_an_open_member() throws Exception {
        JsonNode answer = ask(VONDST + """
            Regel basisvindloon
                geldig altijd
                    Het vindloon van een Vondst moet geïnitialiseerd worden op 25.

            Regel vindloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op zijn gewicht maal 2.

            Uitzonderingen ongeordend op vindloon:
            Regel betwist vindloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op 0 indien hij betwist is.

            Regel beschadigd vindloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op 5 indien hij beschadigd is.
            """, "{\"instances\": [{\"type\": \"Wrakvondst\", \"id\": \"W1\", \"attributes\": {\"gewicht\": 50},"
                + " \"characteristics\": {\"betwist\": {\"state\": \"holds\"}}}]}");
        assertSentence("W1: zijn vindloon staat niet vast, want het is niet gegeven of hij beschadigd is; 'betwist vindloon'"
                + " (uitzondering op 'vindloon') gaf 0, maar beslist niet zolang 'beschadigd vindloon' (uitzondering op"
                + " 'vindloon') niet vaststaat; voor 'beschadigd vindloon' (uitzondering op 'vindloon') staat niet vast: het"
                + " onderwerp beschadigd is (niet gegeven); niet aan de beurt: de regel 'vindloon' (onvoorwaardelijk, 100), de"
                + " regel 'basisvindloon' (onvoorwaardelijk, 25)", answer);
    }

    /** The request gives the characteristic, so it holds, and nobody decided: one exception would not attribute it, beside one
     *  that is open. */
    @Test
    void a_given_characteristic_beside_an_open_stack() throws Exception {
        JsonNode answer = ask(VONDST + """
            Regel verkoopbaar
                geldig altijd
                    Een Vondst is verkoopbaar indien zijn gewicht groter is dan 2.

            Uitzonderingen ongeordend op verkoopbaar:
            Regel onverkoopbaar
                geldig altijd
                    Een Vondst is niet verkoopbaar indien hij betwist is.

            Regel toch verkoopbaar
                geldig altijd
                    Een Vondst is verkoopbaar indien hij beschadigd is.
            """, "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {\"gewicht\": 50},"
                + " \"characteristics\": {\"betwist\": {\"state\": \"holds\"}, \"verkoopbaar\": {\"state\": \"holds\"}}}]}");
        assertEquals("holds", answer.at("/instances/0/characteristics/verkoopbaar/state").asText());
        assertSentence("V1 is verkoopbaar uit de invoer, want geen variant besliste; 'onverkoopbaar' (uitzondering op"
                + " 'verkoopbaar') gold (niet toekennen), maar beslist niet zolang 'toch verkoopbaar' (uitzondering op"
                + " 'verkoopbaar') niet vaststaat; voor 'toch verkoopbaar' (uitzondering op 'verkoopbaar') staat niet vast: het"
                + " onderwerp beschadigd is (niet gegeven); niet aan de beurt: de regel 'verkoopbaar' (gold, toekennen)", answer);
    }

    /** A rule on the supertype attributes the characteristic beside the stack of the subtype: W1 is open by the rule where the
     *  stack gives way, W2 where it decided not to attribute, and W3 holds by the rule where the stack gives way. */
    @Test
    void a_rule_beside_the_stack_of_a_subtype() throws Exception {
        JsonNode answer = ask(VONDST + WRAKVERKOOP, """
            {"instances": [{"type": "Wrakvondst", "id": "W1", "attributes": {"diepte": 1}},
              {"type": "Wrakvondst", "id": "W2", "attributes": {"diepte": 5},
               "characteristics": {"betwist": {"state": "holds"}}},
              {"type": "Wrakvondst", "id": "W3", "attributes": {"diepte": 1, "gewicht": 5}}]}""");
        assertSentence("W1: of hij verkoopbaar is, staat niet vast, want zijn gewicht is niet gegeven; 'betwist wrak' gold niet,"
                + " want niet geldt: zijn diepte (1) is groter dan 2; de regel 'wrakverkoopbaar' gold niet, want niet geldt: zijn"
                + " diepte (1) is groter dan 2", answer);
        assertSentence("W2: of hij verkoopbaar is, staat niet vast, want zijn gewicht is niet gegeven; 'betwist wrak' besliste 'is"
                + " niet verkoopbaar', omdat zijn diepte (5) is groter dan 2 en het onderwerp betwist is; verdrongen: de regel"
                + " 'wrakverkoopbaar' (gold, toekennen)", answer);
        assertSentence("W3 is verkoopbaar volgens een regel buiten de groep, want geen variant besliste; 'betwist wrak' gold niet,"
                + " want niet geldt: zijn diepte (1) is groter dan 2; de regel 'wrakverkoopbaar' gold niet, want niet geldt: zijn"
                + " diepte (1) is groter dan 2", answer);
    }

    /** The same rule beside the same stack in a rule set that is not strict. */
    @Test
    void a_rule_beside_the_stack_of_a_subtype_in_an_ordinary_rule_set() throws Exception {
        JsonNode answer = ask(VONDST.replace("Afleiden uit leeg: nee", "") + WRAKVERKOOP, "{\"instances\": [{\"type\":"
                + " \"Wrakvondst\", \"id\": \"W3\", \"attributes\": {\"diepte\": 1, \"gewicht\": 5}}]}");
        assertSentence("W3 is verkoopbaar volgens een regel buiten de groep, want geen variant besliste; 'betwist wrak' gold niet,"
                + " want niet geldt: zijn diepte (1) is groter dan 2; de regel 'wrakverkoopbaar' gold niet, want niet geldt: zijn"
                + " diepte (1) is groter dan 2", answer);
    }

    // ---------------- an initialisation alone, and a consistency on gevuld ----------------

    /** A member that only an initialisation writes is an input member with a default value: open where the request does not give
     *  it, and then its own open input; the value the request gives; and where it is complete and not given, the value of the
     *  initialisation, which is at its turn there. */
    @Test
    void a_member_that_only_an_initialisation_writes_is_input() throws Exception {
        String rules = """
            Regel basisvindloon
                geldig altijd
                    Het vindloon van een Vondst moet geïnitialiseerd worden op 25.
            """;
        JsonNode open = ask(VONDST + rules, vondst("", ""), "&openInputs=true");
        assertEquals("open", open.at("/instances/0/attributes/vindloon/state").asText(), open.toString());
        assertSentence("V1: zijn vindloon staat niet vast, want zijn vindloon is niet gegeven; niet aan de beurt: de regel"
                + " 'basisvindloon'", open);
        List<String> vindloon = new ArrayList<>();
        for (JsonNode i : open.get("openInputs"))
            if (i.get("name").asText().equals("vindloon"))
                for (JsonNode o : i.get("forOutputs")) vindloon.add(o.get("instance").asText() + " " + o.get("name").asText());
        assertEquals(List.of("V1 vindloon"), vindloon, open.get("openInputs").toString());

        JsonNode given = ask(VONDST + rules, vondst("\"vindloon\": 7", ""));
        assertEquals(json("{\"state\": \"filled\", \"value\": 7}"), given.at("/instances/0/attributes/vindloon"));

        String complete = VONDST.replace("het vindloon Numeriek (geheel getal);",
                "het vindloon Numeriek (geheel getal) (volledig gegeven);") + rules;
        JsonNode initialised = ask(complete, vondst("", ""));
        assertEquals(json("{\"state\": \"filled\", \"value\": 25}"), initialised.at("/instances/0/attributes/vindloon"));
        assertSentence("V1 heeft als vindloon 25 volgens de regel 'basisvindloon': 25, omdat zijn vindloon in de invoer niet"
                + " gegeven was", initialised);
        DecisionCore core = DecisionCore.assemble(descriptor(json(UcharBuild.document(Resolver.resolve(complete)))),
                java.util.Map.of("b.rgs", complete), CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
        assertEquals(json("[{\"kind\": \"attribute\", \"objectType\": \"Vondst\", \"name\": \"vindloon\"}]"),
                core.completeInput());
    }

    /** A complete vindloon that only an initialisation writes is settled leeg above it where the request does not give it, so
     *  the initialisation is at its turn there; where its condition or its value reads an open member, the vindloon is open by
     *  that read and not by its own input. The open input is what the initialisation reads, and the keurgewicht, which reads
     *  the vindloon, says that the vindloon does not stand. */
    @Test
    void a_complete_member_whose_initialisation_reads_an_open_member() throws Exception {
        String complete = VONDST.replace("het vindloon Numeriek (geheel getal);",
                "het vindloon Numeriek (geheel getal) (volledig gegeven);");
        String keurgewicht = """

            Regel keurgewicht
                geldig altijd
                    Het keurgewicht van een Vondst moet berekend worden als zijn vindloon plus 10.
            """;
        String reader = "V1: zijn keurgewicht staat niet vast, want zijn vindloon staat niet vast; voor de regel 'keurgewicht'"
                + " staat niet vast: zijn vindloon plus 10, met zijn vindloon (staat niet vast)";

        JsonNode conditional = ask(complete + """
            Regel basisvindloon
                geldig altijd
                    Het vindloon van een Vondst moet geïnitialiseerd worden op 25 indien hij betwist is.
            """ + keurgewicht, vondst("", ""), "&openInputs=true");
        assertEquals("open", conditional.at("/instances/0/attributes/vindloon/state").asText(), conditional.toString());
        assertSentence("V1: zijn vindloon staat niet vast, want het is niet gegeven of hij betwist is; voor de regel"
                + " 'basisvindloon' staat niet vast: het onderwerp betwist is (niet gegeven)", conditional);
        assertSentence(reader, conditional);
        assertEquals(List.of(), forOutputs(conditional, "vindloon"), conditional.get("openInputs").toString());
        assertEquals(List.of("V1 keurgewicht", "V1 vindloon", "V1 betwist"), forOutputs(conditional, "betwist"),
                conditional.get("openInputs").toString());

        JsonNode reading = ask(complete + """
            Regel basisvindloon
                geldig altijd
                    Het vindloon van een Vondst moet geïnitialiseerd worden op zijn taxatie.
            """ + keurgewicht, vondst("", ""), "&openInputs=true");
        assertEquals("open", reading.at("/instances/0/attributes/vindloon/state").asText(), reading.toString());
        assertSentence("V1: zijn vindloon staat niet vast, want zijn taxatie is niet gegeven; voor de regel 'basisvindloon'"
                + " staat niet vast: zijn taxatie (niet gegeven)", reading);
        assertSentence(reader, reading);
        assertEquals(List.of(), forOutputs(reading, "vindloon"), reading.get("openInputs").toString());
        assertEquals(List.of("V1 taxatie", "V1 keurgewicht", "V1 vindloon"), forOutputs(reading, "taxatie"),
                reading.get("openInputs").toString());
    }

    /** A consistency "moet gevuld zijn" over a taxatie that is not given does not decide: it is not among the inconsistencies, and
     *  the explanation says that it does not stand. Over a complete taxatie without a value it is inconsistent. */
    @Test
    void a_consistency_on_gevuld_over_an_open_member_reports_nothing() throws Exception {
        String rules = """
            Regel taxatieplicht
                geldig altijd
                    De taxatie van een Vondst moet gevuld zijn.
            """;
        JsonNode open = ask(VONDST + rules, vondst("", ""));
        assertEquals(json("[]"), open.get("inconsistencies"), open.toString());
        assertSentence("of V1 voldoet aan de regel 'taxatieplicht', staat niet vast, want zijn taxatie is niet gegeven; het staat"
                + " niet vast: De taxatie van een Vondst (niet gegeven) gevuld is", open);
        assertEquals(json("[]"), ask(VONDST + rules, vondst("\"taxatie\": 5", "")).get("inconsistencies"));
        String complete = VONDST.replace("de taxatie Numeriek (geheel getal);",
                "de taxatie Numeriek (geheel getal) (volledig gegeven);") + rules;
        assertEquals(json("[\"taxatieplicht\"]"), ask(complete, vondst("", "")).get("inconsistencies"));
    }

    /** The filter tests the taxatie on gevuld or leeg, so it is undetermined on a member without one: the explanation of the
     *  fold gives its verdict and names no member, and the head names the taxatie that keeps it open. */
    @Test
    void a_quantifier_fold_whose_filter_tests_an_open_member() throws Exception {
        String rules = """
            Regel kundig
                geldig altijd
                    Een Jutter is kundig indien het gewicht van %s vondsten van de Jutter die aan de volgende voorwaarde voldoen: zijn taxatie is %s groter is dan 10.
            """;
        // V2 of gewicht 5 fails the matrix, and whether it counts is open
        assertFold("J1: of hij kundig is, staat niet vast, want van V2 is zijn taxatie niet gegeven;", "is voldaan",
                ask(VONDST + rules.formatted("alle", "gevuld"), jutter("\"gewicht\": 20, \"taxatie\": 5", "\"gewicht\": 5")));
        // V2 of gewicht 50 holds the matrix, and would be the second where it counts
        assertFold("J1: of hij kundig is, staat niet vast, want van V2 is zijn taxatie niet gegeven;", "is voldaan",
                ask(VONDST + rules.formatted("ten hoogste één van de", "gevuld"),
                        jutter("\"gewicht\": 20, \"taxatie\": 5", "\"gewicht\": 50")));
        // V1 has neither, and V2 holds the matrix where it counts
        assertFold("J1: of hij kundig is, staat niet vast, want van V1 is zijn taxatie niet gegeven;", "is voldaan",
                ask(VONDST + rules.formatted("ten minste één van de", "leeg"), jutter("", "\"gewicht\": 50")));
    }

    /** The filter "betwist of beschadigd" holds on a Vondst that is betwist, also where beschadigd is open, and uchar's reading
     *  gives per member the verdict of the matrix and not whether its filter stands. Where the filter reads an open member of
     *  a member, the explanation of the fold gives its verdict alone: "alle" fails on V2, and "ten minste twee" holds by V1
     *  and V2, and neither names a member. */
    @Test
    void a_quantifier_fold_whose_filter_reads_an_open_member_and_is_decided() throws Exception {
        String rules = """
            Regel kundig
                geldig altijd
                    Een Jutter is kundig indien het gewicht van %s vondsten van de Jutter die aan ten minste één van de volgende voorwaarden voldoen:
                        - hij betwist is
                        - hij beschadigd is
                    groter is dan 10.
            """;
        String betwist = "\"betwist\": {\"state\": \"holds\"}";
        JsonNode all = ask(VONDST + rules.formatted("alle"), """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1", "V2"]}},
              {"type": "Vondst", "id": "V1", "attributes": {"gewicht": 20},
               "characteristics": {%s, "beschadigd": {"state": "holds"}}},
              {"type": "Vondst", "id": "V2", "attributes": {"gewicht": 5}, "characteristics": {%s}}]}"""
                .formatted(betwist, betwist));
        assertFold("J1 is niet kundig, want", "is niet voldaan", all);
        JsonNode atLeast = ask(VONDST + rules.formatted("ten minste twee van de"), """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1", "V2", "V3"]}},
              {"type": "Vondst", "id": "V1", "attributes": {"gewicht": 20}, "characteristics": {%s}},
              {"type": "Vondst", "id": "V2", "attributes": {"gewicht": 20},
               "characteristics": {%s, "beschadigd": {"state": "holdsNot"}}},
              {"type": "Vondst", "id": "V3", "characteristics": {%s}}]}""".formatted(betwist, betwist, betwist));
        assertFold("J1 is kundig, omdat", "is voldaan", atLeast);
    }

    /** The filter counts a union whose first selection tests betwist: on J2 it reads the open betwist of V2, so the fold gives
     *  its verdict and names no member. */
    @Test
    void a_quantifier_fold_whose_filter_counts_a_union_with_an_open_member() throws Exception {
        assertFold("H1: of hij druk is, staat niet vast, want", "is voldaan", ask("""
            Afleiden uit leeg: nee

            Objecttype de Haven (mv: Havens)
                is druk kenmerk (bijvoeglijk);

            Objecttype de Jutter (mv: Jutters) (bezield)
                de leeftijd Numeriek (geheel getal);

            Objecttype de Vondst (mv: Vondsten)
                is betwist kenmerk (bijvoeglijk);

            Feittype havenregister
                de haven Haven
                de jutter (mv: jutters) Jutter
                één haven heeft meerdere jutters

            Feittype vondstaanmelding
                de aanmelder Jutter
                de aangemelde vondst (mv: aangemelde vondsten) Vondst
                één aanmelder meldt meerdere aangemelde vondsten

            Feittype vondstberging
                de berger Jutter
                de geborgen vondst (mv: geborgen vondsten) Vondst
                één berger bergt meerdere geborgen vondsten

            Regel druk
                geldig altijd
                    Een Haven is druk indien de leeftijd van alle jutters van de Haven die aan de volgende voorwaarde voldoen: het aantal aangemelde vondsten van de Jutter die betwist zijn en geborgen vondsten van de Jutter is groter dan 0 groter is dan 10.
            """, """
            {"instances": [{"type": "Haven", "id": "H1", "roles": {"jutters": ["J1", "J2"]}},
              {"type": "Jutter", "id": "J1", "attributes": {"leeftijd": 20}, "roles": {"aangemelde vondsten": ["V1"]}},
              {"type": "Jutter", "id": "J2", "attributes": {"leeftijd": 5}, "roles": {"aangemelde vondsten": ["V2"]}},
              {"type": "Vondst", "id": "V1", "characteristics": {"betwist": {"state": "holds"}}},
              {"type": "Vondst", "id": "V2"}]}"""));
    }

    /** The test of a fold over the others reads nothing of the Vondst itself, which the filter leaves out. */
    @Test
    void a_fold_names_no_member_the_filter_leaves_out() throws Exception {
        assertSentence("V1: of hij alleen is, staat niet vast, want van V2 is zijn taxatie niet gegeven; voor de regel 'alleen'"
                + " staat niet vast: aan de voorwaarde \"de taxatie van geen van de vondsten van de jutter van de Vondst die niet"
                + " de Vondst zelf zijn gevuld is\" is voldaan: niet vast voor V2", ask(VONDST + """
                Regel alleen
                    geldig altijd
                        Een Vondst is alleen indien de taxatie van geen van de vondsten van de jutter van de Vondst die niet de Vondst zelf zijn gevuld is.
                """, jutter("", "")));
    }

    /** A matrix that divides by a parameter nothing binds: V1 is leeg and decides the fold, V2 comes after the stop and faults, and
     *  V3 is open. V2 is no member for which it does not hold. */
    @Test
    void a_member_after_the_stop_whose_matrix_faults_is_not_judged() throws Exception {
        ObjectNode document = (ObjectNode) json(UcharBuild.document(Resolver.resolve(VONDST + """
            Regel keurgewicht
                geldig altijd
                    Het keurgewicht van een Vondst moet gesteld worden op 5 indien hij betwist is.

            Regel kundig
                geldig altijd
                    Een Jutter is kundig indien het keurgewicht van alle vondsten van de Jutter groter is dan het TARIEF.
            """)));
        for (JsonNode r : document.at("/rules/rules")) {
            JsonNode test = r.at("/versions/0/condition/test");
            if (test.isMissingNode()) continue;
            ObjectNode quotient = ((ObjectNode) test).putObject("left");
            quotient.put("node", "Binary").put("op", "dividedBy");
            quotient.set("left", json("""
                {"node": "AttributeReference", "attribute": "keurgewicht", "subjectType": "Vondst",
                 "source": {"snippet": "het keurgewicht van alle vondsten van de Jutter"}}"""));
            quotient.set("right", test.get("right"));
            quotient.set("type", json("{\"node\": \"Number\", \"whole\": false, \"decimals\": 0, \"sign\": \"unspecified\"}"));
            quotient.putObject("source").put("snippet", "keurgewicht gedeeld door TARIEF");
            ((ObjectNode) test).set("right", json("""
                {"node": "Literal", "value": {"number": "3"}, "type": {"node": "Number", "whole": true, "decimals": 0,
                 "sign": "unspecified"}, "source": {"snippet": "3"}}"""));
        }
        JsonNode answer = post(DecisionCore.buildFromDocument(descriptor(document), document.toString(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null), """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1", "V2", "V3"]}},
              {"type": "Vondst", "id": "V1", "characteristics": {"betwist": {"state": "holdsNot"}}},
              {"type": "Vondst", "id": "V2", "characteristics": {"betwist": {"state": "holds"}}},
              {"type": "Vondst", "id": "V3"}]}""", "");
        assertFold("J1 is niet kundig, want", "is niet voldaan: niet voor V1 (1 van de 2) (1 lid niet beoordeeld)", answer);
    }

    // ---------------- the quantifiers ----------------

    /** V1 of gewicht 20, V2 of 5 and V3 open, or fewer: what each quantifier says where it stands and where it does not. */
    @Test
    void every_quantifier_decided_and_undetermined() throws Exception {
        String three = "\"gewicht\": 20|\"gewicht\": 5|";
        assertQuantifier("geen van de", three, "J1 is niet kundig, want", "is niet voldaan: wel voor V1 (1 van de 3)");
        assertQuantifier("ten minste één van de", three, "J1 is kundig, omdat", "is voldaan: voor V1 (1 van de 3)");
        assertQuantifier("ten hoogste één van de", "\"gewicht\": 20|\"gewicht\": 30|", "J1 is niet kundig, want",
                "is niet voldaan: voor V1 en V2 (2 van de 3)");
        assertQuantifier("precies één van de", "\"gewicht\": 20|\"gewicht\": 30|", "J1 is niet kundig, want",
                "is niet voldaan: voor V1 en V2 (2 van de 3)");
        assertQuantifier("ten minste twee van de", "\"gewicht\": 5|\"gewicht\": 5|", "J1 is niet kundig, want",
                "is niet voldaan: niet voor V1 en V2 (2 van de 3)");
        // too few members, whatever the open one has
        assertQuantifier("ten minste twee van de", "", "J1 is niet kundig, want", "is niet voldaan: er is 1 lid");
        assertQuantifier("ten hoogste twee van de", "|", "J1 is kundig, omdat", "is voldaan: er zijn 2 leden");
        for (String quantifier : List.of("ten minste twee van de", "ten hoogste één van de", "precies één van de"))
            assertQuantifier(quantifier, three, "J1: of hij kundig is, staat niet vast, want van V3 is zijn gewicht niet gegeven;",
                    "is voldaan: wel voor V1, niet voor V2, niet vast voor V3");
        assertQuantifier("geen van de", "\"gewicht\": 5|", "J1: of hij kundig is, staat niet vast, want van V2 is zijn gewicht niet"
                + " gegeven;", "is voldaan: niet voor V1, niet vast voor V2");
    }

    /** {@code vondsten}: the attributes of V1, V2, ..., with a bar between them. */
    private static void assertQuantifier(String quantifier, String vondsten, String begin, String end) throws Exception {
        String[] given = vondsten.split("\\|", -1);
        StringBuilder roles = new StringBuilder(), instances = new StringBuilder();
        for (int i = 1; i <= given.length; i++) {
            roles.append(i == 1 ? "" : ", ").append("\"V").append(i).append('"');
            instances.append(", {\"type\": \"Vondst\", \"id\": \"V").append(i).append("\", \"attributes\": {").append(given[i - 1])
                    .append("}}");
        }
        List<String> texts = texts(ask(VONDST + """
            Regel kundig
                geldig altijd
                    Een Jutter is kundig indien het gewicht van %s vondsten van de Jutter groter is dan 10.
            """.formatted(quantifier), "{\"instances\": [{\"type\": \"Jutter\", \"id\": \"J1\", \"roles\": {\"vondsten\": ["
                + roles + "]}}" + instances + "]}"));
        assertEquals(1, texts.size(), texts.toString());
        assertTrue(texts.get(0).startsWith(begin) && texts.get(0).endsWith("\" " + end), texts.get(0) + "\nis niet\n" + begin
                + " … " + end);
    }

    // ---------------- composites, binders, ground, vocabulary ----------------

    @Test
    void a_nested_composite_names_the_member_that_keeps_it_open() throws Exception {
        String source = VONDST + """
            Regel verkoop
                geldig altijd
                    Een Vondst is verkoopbaar indien hij aan alle volgende voorwaarden voldoet:
                    - hij is betwist
                    - hij voldoet aan ten minste één van de volgende voorwaarden:
                      .. zijn gewicht is groter dan 10
                      .. zijn taxatie is groter dan 100.
            """;
        assertSentence("V1: of hij verkoopbaar is, staat niet vast, want zijn taxatie is niet gegeven; voor de regel 'verkoop' staat"
                + " niet vast: zijn taxatie (niet gegeven) is groter dan 100",
                ask(source, vondst("\"gewicht\": 5", "\"betwist\": {\"state\": \"holds\"}")));
        assertSentence("V1 is verkoopbaar, omdat het onderwerp betwist is en zijn gewicht (20) is groter dan 10",
                ask(source, vondst("\"gewicht\": 20", "\"betwist\": {\"state\": \"holds\"}")));
    }

    @Test
    void a_binder_over_an_open_read_and_one_over_a_fold_with_an_open_filter() throws Exception {
        assertSentence("V1: zijn vindloon staat niet vast, want zijn taxatie is niet gegeven; voor de regel 'vindloon' staat niet"
                + " vast: de meerwaarde maal 3, met zijn gewicht (5), zijn taxatie (niet gegeven) en de meerwaarde is zijn gewicht"
                + " plus zijn taxatie (staat niet vast)", ask(VONDST + """
                Regel vindloon
                    geldig altijd
                        Het vindloon van een Vondst moet berekend worden als de meerwaarde maal 3
                        Daarbij geldt:
                            de meerwaarde is zijn gewicht plus zijn taxatie.
                """, vondst("\"gewicht\": 5", "")));
        String fold = "de som van het gewicht van alle vondsten van de jutter van de Vondst die aan de volgende voorwaarde voldoen:"
                + " zijn taxatie is gevuld";
        assertSentence("V1: zijn vindloon staat niet vast, want zijn taxatie is niet gegeven; voor de regel 'vindloon' staat niet"
                + " vast: de meerwaarde plus 1, met " + fold + " (staat niet vast) en de meerwaarde is " + fold
                + " (staat niet vast)", ask(VONDST + """
                Regel vindloon
                    geldig altijd
                        Het vindloon van een Vondst moet berekend worden als de meerwaarde plus 1
                        Daarbij geldt:
                            de meerwaarde is de som van het gewicht van alle vondsten van de jutter van de Vondst die aan de volgende voorwaarde voldoen: zijn taxatie is gevuld.
                """, jutter("\"gewicht\": 5", "\"gewicht\": 7")));
    }

    /** Two rules on one target: the one wrote, and what the other, whose condition holds, gives does not stand. */
    @Test
    void what_a_rule_beside_the_writer_gives() throws Exception {
        assertSentence("V1 heeft als vindloon 8 volgens de regel 'vindloon' (onvoorwaardelijk): zijn gewicht (8); wat de regel"
                + " 'betwist vindloon' geeft, staat niet vast", ask(VONDST + """
                Regel vindloon
                    geldig altijd
                        Het vindloon van een Vondst moet gesteld worden op zijn gewicht.

                Regel betwist vindloon
                    geldig altijd
                        Het vindloon van een Vondst moet gesteld worden op zijn taxatie indien hij betwist is.
                """, vondst("\"gewicht\": 8", "\"betwist\": {\"state\": \"holds\"}")));
    }

    @Test
    void the_stack_of_a_subtype() throws Exception {
        String source = VONDST + """
            Regel vindloon
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op zijn gewicht.

            Regel wrakloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op zijn gewicht maal 2.

            Afwijking van wrakloon: diep wrakloon
                geldig altijd
                    Het vindloon van een Wrakvondst moet gesteld worden op 55 indien zijn diepte groter is dan 20.
            """;
        assertSentence("W1: zijn vindloon staat niet vast, want zijn diepte is niet gegeven; voor 'diep wrakloon' staat niet vast: zijn"
                + " diepte (niet gegeven) is groter dan 20; niet aan de beurt: de regel 'wrakloon' (onvoorwaardelijk, 16)",
                ask(source, "{\"instances\": [{\"type\": \"Wrakvondst\", \"id\": \"W1\", \"attributes\": {\"gewicht\": 8}}]}"));
        assertSentence("V1 heeft als vindloon 8 volgens de regel 'vindloon' (onvoorwaardelijk): zijn gewicht (8)",
                ask(source, vondst("\"gewicht\": 8", "")));
    }

    /** The ground of the rule that leaves a characteristic open, and the names of the vocabulary of the answer in the head, the cause
     *  and openInputs. */
    @Test
    void the_ground_and_the_vocabulary_of_an_open_outcome() throws Exception {
        String source = VONDST.replace("Afleiden uit leeg: nee", "Afleiden uit leeg: nee\n\nVocabulaire register (taal: nl)")
                .replace("de taxatie Numeriek", "de taxatie (register: getaxeerde waarde) Numeriek")
                .replace("is verkoopbaar kenmerk", "is verkoopbaar (register: te gelde te maken) kenmerk") + """
            // Bron: jci1.3:c:BWBR0005291&artikel=5
            Regel verkoop
                geldig altijd
                    Een Vondst is verkoopbaar indien zijn taxatie groter is dan 10.

            Regel vindloon
                geldig altijd
                    Het vindloon van een Vondst moet gesteld worden op 3 indien hij niet verkoopbaar is.
            """;
        String tail = " voor de regel 'verkoop' staat niet vast: zijn taxatie (niet gegeven) is groter dan 10. Grondslag:"
                + " jci1.3:c:BWBR0005291&artikel=5";
        assertSentence("V1: of hij verkoopbaar is, staat niet vast, want zijn taxatie is niet gegeven;" + tail,
                ask(source, vondst("", "")));
        JsonNode register = ask(source, vondst("", ""), "&vocabulary=register&openInputs=true");
        assertSentence("V1: of hij te gelde te maken is, staat niet vast, want zijn getaxeerde waarde is niet gegeven;" + tail, register);
        assertSentence("V1: zijn vindloon staat niet vast, want het staat niet vast of hij te gelde te maken is; voor de regel"
                + " 'vindloon' staat niet vast: het onderwerp niet te gelde te maken is (staat niet vast)", register);
        List<String> taxatie = new ArrayList<>();
        for (JsonNode i : register.get("openInputs"))
            if (i.get("name").asText().equals("getaxeerde waarde"))
                for (JsonNode o : i.get("forOutputs")) taxatie.add(o.get("name").asText());
        assertEquals(List.of("getaxeerde waarde", "vindloon", "te gelde te maken"), taxatie, register.get("openInputs").toString());
    }

    // ---------------- helpers ----------------

    private static String vondst(String attributes, String characteristics) {
        return "{\"instances\": [{\"type\": \"Vondst\", \"id\": \"V1\", \"attributes\": {" + attributes + "},"
                + " \"characteristics\": {" + characteristics + "}}]}";
    }

    private static final String WRAKVERKOOP = """
        Regel verkoopbaar
            geldig altijd
                Een Vondst is verkoopbaar indien zijn gewicht groter is dan 2.

        Regel wrakverkoopbaar
            geldig altijd
                Een Wrakvondst is verkoopbaar indien zijn diepte groter is dan 2.

        Uitzondering op wrakverkoopbaar: betwist wrak
            geldig altijd
                Een Wrakvondst is niet verkoopbaar indien hij betwist is.
        """;

    private static String jutter(String v1, String v2) {
        return """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": ["V1", "V2"]}},
              {"type": "Vondst", "id": "V1", "attributes": {%s}}, {"type": "Vondst", "id": "V2", "attributes": {%s}}]}"""
                .formatted(v1, v2);
    }

    /** "V1 vindloon", … : the outcomes that {@code openInputs} of {@code answer} gives under the input member {@code name}. */
    private static List<String> forOutputs(JsonNode answer, String name) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : answer.get("openInputs"))
            if (i.get("name").asText().equals(name))
                for (JsonNode o : i.get("forOutputs")) out.add(o.get("instance").asText() + " " + o.get("name").asText());
        return out;
    }

    private static JsonNode ask(String source, String request) throws Exception {
        return ask(source, request, "");
    }

    private static JsonNode ask(String source, String request, String query) throws Exception {
        JsonNode document = json(UcharBuild.document(Resolver.resolve(source)));
        return post(DecisionCore.assemble(descriptor(document), java.util.Map.of("b.rgs", source), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()), request, query);
    }

    /** A service with every form of {@code document} in its scope and every member in its output. */
    private static String descriptor(JsonNode document) {
        ObjectNode descriptor = ServiceHttp.MAPPER.createObjectNode();
        descriptor.putObject("service").put("name", "vormen").put("version", "1");
        ObjectNode ep = descriptor.putArray("entrypoints").addObject().put("name", "vormen").put("path", "vormen");
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

    private static JsonNode post(DecisionCore core, String request, String query) throws Exception {
        ServiceHttp service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port()
                            + "/vormen?explanationParts=true" + query)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode answer = json(r.body());
            ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
            return answer;
        } finally {
            service.stop(0);
        }
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

    /** The one sentence about J1 that begins with {@code begin}, whose text of the fold is left out. */
    private static void assertFold(String begin, String end, JsonNode answer) {
        List<String> texts = texts(answer).stream().filter(t -> t.startsWith(begin)).toList();
        assertEquals(1, texts.size(), answer.get("explanation").toString());
        assertTrue(texts.get(0).endsWith("\" " + end), texts.get(0) + "\neindigt niet op\n" + end);
    }

    private static JsonNode json(String text) throws Exception {
        return ServiceHttp.MAPPER.readTree(text);
    }
}
