package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.Violation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stack of a timeline target per day through the service (uchar's RUNTIME-SEMANTICS ch. 7.8): the writers decide
 * their days, the input, the request's or on a made object the value of the making, stands on the other days, and the
 * initialisation decides the days that leaves leeg, over all time. uchar's goldens jutterswet-initialisatie-per-dag,
 * jutterswet-creatie-per-dag and jutterswet-afwezigheidsmarkers-initialisatie give their pinned timelines through the
 * service, and the explanation names per period who decided: a rule, the input, the value of the making or the
 * initialisation. The fixpoint of the detail and of the playground gives on a made object the value of the making
 * beside the derived timeline, as it gives the input of an object of the request, with {@code inputOrigin}
 * {@code creation}. What uchar's Drools branch refuses on a timeline target reaches the service as its refusal: the value
 * of an initialisation that is neither period arithmetic nor the constructor of a literal (DROOLS-316) is a marker, and
 * an allocation that names a time-dependent attribute is refused by the frontend (RGS-378) and, in a document, by the
 * branch as not well-formed (DROOLS-315, WF-128).
 */
class TimelineStackHttpTest {

    private static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden");
    private static final Path INITIALISATION = GOLDEN.resolve("jutterswet-initialisatie-per-dag");
    private static final Path CREATION = GOLDEN.resolve("jutterswet-creatie-per-dag");
    private static final Path MARKERS = GOLDEN.resolve("jutterswet-afwezigheidsmarkers-initialisatie");
    /** uchar's document of an allocation over a time-dependent bergingsaandeel (ref's fixture of WF-128). */
    private static final Path ALLOCATION = TokaSource.REPO.resolve("uchar/ref/test/fixtures/writers-stack/timeline-allocation");

    private static final String INITIALISATION_DESCRIPTOR = """
        { "service": {"name":"lonen","version":"1"},
          "entrypoints": [ { "name":"lonen","path":"lonen",
            "scope": {"ruleNames": ["standaardstrandloon", "voorjaarsloon", "standaardvoorjaarsloon",
                                    "standaardzomerloon", "zomerloon", "standaardwachtloon", "kustloon",
                                    "standaardkustloon", "standaardhavenloon", "havenloon", "stormloon",
                                    "stormloon in de herfst", "standaardstormloon"]},
            "input": ["Jutter"],
            "output": ["Jutter.strandloon", "Jutter.voorjaarsloon", "Jutter.zomerloon", "Jutter.wachtloon",
                       "Jutter.kustloon", "Jutter.havenloon", "Jutter.stormloon"] } ],
          "parameters": {} }""";

    private static final String CREATION_DESCRIPTOR = """
        { "service": {"name":"meldingen","version":"1"},
          "entrypoints": [ { "name":"meldingen","path":"meldingen",
            "scope": {"ruleNames": ["meld de jutter", "seizoenswaarde", "seizoensloon", "standaardaanvulloon",
                                    "meldwaardetotaal", "meldloontotaal", "seizoenswaardetotaal",
                                    "seizoensloontotaal", "aanvulloontotaal"]},
            "input": ["Jutter"],
            "output": ["Jutter.meldwaardetotaal", "Jutter.meldloontotaal", "Jutter.seizoenswaardetotaal",
                       "Jutter.seizoensloontotaal", "Jutter.aanvulloontotaal"] } ],
          "parameters": {} }""";

    private static final String MARKERS_DESCRIPTOR = """
        { "service": {"name":"opslag","version":"1"},
          "entrypoints": [ { "name":"opslag","path":"opslag",
            "scope": {"ruleNames": ["opslag in het voorjaar", "opslag onbekend", "keuring onbekend"]},
            "input": ["Vondst"], "output": ["Vondst.opslagkosten", "Vondst.keurkosten"] } ],
          "parameters": {} }""";

    private static ServiceHttp initialisation;
    private static ServiceHttp creation;
    private static ServiceHttp creationDetail;
    private static ServiceHttp markers;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        initialisation = ServiceHttp.bind(DecisionCore.assemble(INITIALISATION_DESCRIPTOR,
                Map.of("source.rgs", Files.readString(INITIALISATION.resolve("source.rgs"))), CoreConfig.strict(), cl),
                "127.0.0.1", 0).start();
        creation = ServiceHttp.bind(DecisionCore.assemble(CREATION_DESCRIPTOR,
                Map.of("source.rgs", Files.readString(CREATION.resolve("source.rgs"))), CoreConfig.strict(), cl),
                "127.0.0.1", 0).start();
        creationDetail = ServiceHttp.bind(DecisionCore.assemble(CREATION_DESCRIPTOR,
                Map.of("source.rgs", Files.readString(CREATION.resolve("source.rgs"))), CoreConfig.strict().withDetail(),
                cl), "127.0.0.1", 0).start();
        markers = ServiceHttp.bind(DecisionCore.assemble(MARKERS_DESCRIPTOR,
                Map.of("source.rgs", Files.readString(MARKERS.resolve("source.rgs"))), CoreConfig.strict(), cl),
                "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (initialisation != null) initialisation.stop(0);
        if (creation != null) creation.stop(0);
        if (creationDetail != null) creationDetail.stop(0);
        if (markers != null) markers.stop(0);
    }

    // ---------------- the answer: uchar's pins ----------------

    /** case2 of jutterswet-initialisatie-per-dag: an input beside the rules and the initialisations, a stormwacht
     *  over 2025 for the conditional initialisation of the stormloon. */
    private static final String INITIALISATION_CASE2 = """
        {"instances":[{"type":"Jutter","id":"J2",
          "attributes":{
            "voorjaarsloon":{"timeline":[{"from":"2025-01-01","to":"2025-04-01","value":10}]},
            "wachtloon":{"timeline":[{"from":"2025-03-01","to":"2025-06-01","value":10}]},
            "stormloon":{"timeline":[{"from":"2024-01-01","to":"2025-02-01","value":7}]}},
          "characteristics":{"stormwacht":{"periods":[{"from":"2025-01-01","to":"2026-01-01"}]}}}]}""";

    @Test
    void an_initialisation_per_day_beside_the_rules_and_the_input_gives_uchars_pins() throws Exception {
        HttpResponse<String> r = post(initialisation, "lonen", "", INITIALISATION_CASE2);
        assertEquals(200, r.statusCode(), r.body());
        assertPins(INITIALISATION.resolve("case2-invoer.json"), "J2", tree(r.body()));
    }

    @Test
    void the_value_of_the_making_and_the_initialisation_of_a_made_object_give_uchars_pins() throws Exception {
        HttpResponse<String> r = post(creation, "meldingen", "", CREATION_CASE1);
        assertEquals(200, r.statusCode(), r.body());
        assertPins(CREATION.resolve("case1-strandloon-tot-juli.json"), "J1", tree(r.body()));
    }

    @Test
    void an_initialisation_on_a_marker_beside_a_rule_and_the_input_gives_uchars_pins() throws Exception {
        HttpResponse<String> r = post(markers, "opslag", "", MARKERS_CASE2);
        assertEquals(200, r.statusCode(), r.body());
        assertPins(MARKERS.resolve("case2-invoer.json"), "V2", tree(r.body()));
    }

    // ---------------- the explanation: who decided per period ----------------

    /** The voorjaarsloon: the rule from March to May, the initialisation before January and from June, the input in
     *  January and February; the stormloon: the input until February, the two rules, and the initialisation on the
     *  days of the stormwacht they leave. The answer beside the explanation is the answer without it. */
    @Test
    void the_explanation_names_the_rule_the_initialisation_and_the_input_per_period() throws Exception {
        HttpResponse<String> r = post(initialisation, "lonen", "explanation=true", INITIALISATION_CASE2);
        assertEquals(200, r.statusCode(), r.body());
        List<String> sentences = sentences(r.body());
        assertTrue(sentences.contains("J2 heeft als voorjaarsloon 20 van 01-03-2025 tot 01-06-2025 volgens de regel"
                + " 'voorjaarsloon', omdat van dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025), en 5"
                + " tot 01-01-2025 en 5 vanaf 01-06-2025 volgens de regel 'standaardvoorjaarsloon', omdat de"
                + " voorjaarsloon in de invoer leeg was; de invoerwaarde 10 van 01-01-2025 tot 01-03-2025 bleef"
                + " staan"), r.body());
        assertTrue(sentences.contains("J2 heeft als stormloon 20 van 01-03-2025 tot 01-06-2025 volgens de regel"
                + " 'stormloon', omdat van dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025), en 30 van"
                + " 01-09-2025 tot 01-11-2025 volgens de regel 'stormloon in de herfst', omdat van dd. 01-09-2025 tot"
                + " dd. 01-11-2025 (van 01-09-2025 tot 01-11-2025), en 5 van 01-02-2025 tot 01-03-2025, 5 van"
                + " 01-06-2025 tot 01-09-2025 en 5 van 01-11-2025 tot 01-01-2026 volgens de regel 'standaardstormloon',"
                + " omdat hij stormwacht is (van 01-01-2025 tot 01-01-2026); de invoerwaarde 7 van 01-01-2024 tot"
                + " 01-02-2025 bleef staan"), r.body());
        assertFalse(r.body().contains("niet is vast te stellen"), r.body());
        JsonNode with = tree(r.body());
        ((com.fasterxml.jackson.databind.node.ObjectNode) with).remove("explanation");
        assertEquals(tree(post(initialisation, "lonen", "", INITIALISATION_CASE2).body()), with, r.body());
    }

    /** case3 of jutterswet-initialisatie-per-dag: J3 without input and never stormwacht. The initialisation of the
     *  stormloon, under stormwacht, held on no day: uchar reads it {@code HOLDS} with a condition that holds in no
     *  period, and the explanation names it as a rule that did not hold. The rule of the kustloon held from March to
     *  August and gave the empty basisloon there: it gave no value. Every sentence of the case, in its order. */
    @Test
    void the_explanation_names_a_writer_that_held_on_no_day_apart_from_one_that_gave_no_value() throws Exception {
        HttpResponse<String> r = post(initialisation, "lonen", "explanation=true", """
            {"instances":[{"type":"Jutter","id":"J3"}]}""");
        assertEquals(200, r.statusCode(), r.body());
        assertPins(INITIALISATION.resolve("case3-zonder-stormwacht.json"), "J3", tree(r.body()));
        assertEquals(List.of(
                "J3 heeft als strandloon altijd 5 volgens de regel 'standaardstrandloon', omdat de strandloon in de"
                        + " invoer leeg was",
                "J3 heeft als voorjaarsloon 20 van 01-03-2025 tot 01-06-2025 volgens de regel 'voorjaarsloon', omdat van"
                        + " dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025), en 5 tot 01-03-2025 en 5"
                        + " vanaf 01-06-2025 volgens de regel 'standaardvoorjaarsloon', omdat de voorjaarsloon in de invoer"
                        + " leeg was",
                "J3 heeft als zomerloon 5 tot 01-03-2025 en 5 vanaf 01-06-2025 volgens de regel 'standaardzomerloon',"
                        + " omdat de zomerloon in de invoer leeg was, en 20 van 01-03-2025 tot 01-06-2025 volgens de regel"
                        + " 'zomerloon', omdat van dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025)",
                "J3 heeft als wachtloon altijd 5 volgens de regel 'standaardwachtloon', omdat de wachtloon in de invoer"
                        + " leeg was",
                "J3 heeft als kustloon altijd 5 volgens de regel 'standaardkustloon', omdat de kustloon in de invoer"
                        + " leeg was; de regel 'kustloon' gaf geen waarde en besliste niets",
                "J3 heeft als havenloon altijd 5 volgens de regel 'standaardhavenloon', omdat de havenloon in de invoer"
                        + " leeg was; de regel 'havenloon' gaf geen waarde en besliste niets",
                "J3 heeft als stormloon 20 van 01-03-2025 tot 01-06-2025 volgens de regel 'stormloon', omdat van dd."
                        + " 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025), en 30 van 01-09-2025 tot"
                        + " 01-11-2025 volgens de regel 'stormloon in de herfst', omdat van dd. 01-09-2025 tot dd."
                        + " 01-11-2025 (van 01-09-2025 tot 01-11-2025); de regel 'standaardstormloon' gold op geen dag,"
                        + " want niet geldt: hij stormwacht is (altijd leeg)"),
                sentences(r.body()), r.body());
    }

    /** case1 of jutterswet-creatie-per-dag: J1 has a strandloon of 10 from January to June, which the creation gives
     *  the made melding as its seizoensloon and aanvulloon. */
    private static final String CREATION_CASE1 = """
        {"instances":[{"type":"Jutter","id":"J1",
          "attributes":{"strandloon":{"timeline":[{"from":"2025-01-01","to":"2025-07-01","value":10}]}}}]}""";

    /** The made melding has no input: where no rule decided a day, the value of the making stands, and the
     *  initialisation fills the days the making left empty. */
    @Test
    void the_explanation_of_a_made_object_names_the_value_of_the_making_per_period() throws Exception {
        HttpResponse<String> r = post(creation, "meldingen", "explanation=true", CREATION_CASE1);
        assertEquals(200, r.statusCode(), r.body());
        String made = "«\"meld de jutter\"»(J1)#1";
        List<String> sentences = sentences(r.body());
        assertEquals(List.of(
                made + " heeft als seizoenswaarde 20 van 01-03-2025 tot 01-09-2025 volgens de regel 'seizoenswaarde',"
                        + " omdat van dd. 01-03-2025 tot dd. 01-09-2025 (van 01-03-2025 tot 01-09-2025); de waarde bij het"
                        + " maken 7 tot 01-03-2025 en 7 vanaf 01-09-2025 bleef staan",
                made + " heeft als seizoensloon 20 van 01-03-2025 tot 01-09-2025 volgens de regel 'seizoensloon', omdat"
                        + " van dd. 01-03-2025 tot dd. 01-09-2025 (van 01-03-2025 tot 01-09-2025); de waarde bij het maken"
                        + " 10 van 01-01-2025 tot 01-03-2025 bleef staan",
                made + " heeft als aanvulloon 5 tot 01-01-2025 en 5 vanaf 01-07-2025 volgens de regel"
                        + " 'standaardaanvulloon', omdat de aanvulloon bij het maken leeg was; de waarde bij het maken 10"
                        + " van 01-01-2025 tot 01-07-2025 bleef staan"),
                sentences.stream().filter(s -> s.startsWith(made)).toList(), r.body());
    }

    // ---------------- the fixpoint: the value of the making beside the derived timeline ----------------

    /** What the creation gave the made melding of case1: the meldwaarde, the seizoenswaarde 7 on every day, the
     *  meldloon, the seizoensloon and the aanvulloon J1's strandloon, 10 from January to June. */
    private static final String MADE_CASE1 = """
        [{"attribute":"meldwaarde","timeline":[{"value":"7"}],"origin":"creation"},
         {"attribute":"meldloon","timeline":[{"from":"2025-01-01","to":"2025-07-01","value":"10"}],"origin":"creation"},
         {"attribute":"seizoenswaarde",
          "timeline":[{"to":"2025-03-01","value":"7"},{"from":"2025-03-01","to":"2025-09-01","value":"20"},
                      {"from":"2025-09-01","value":"7"}],
          "origin":"derived","input":[{"value":"7"}],"inputOrigin":"creation"},
         {"attribute":"seizoensloon",
          "timeline":[{"from":"2025-01-01","to":"2025-03-01","value":"10"},{"from":"2025-03-01","to":"2025-09-01","value":"20"}],
          "origin":"derived","input":[{"from":"2025-01-01","to":"2025-07-01","value":"10"}],"inputOrigin":"creation"},
         {"attribute":"aanvulloon",
          "timeline":[{"to":"2025-01-01","value":"5"},{"from":"2025-01-01","to":"2025-07-01","value":"10"},
                      {"from":"2025-07-01","value":"5"}],
          "origin":"derived","input":[{"from":"2025-01-01","to":"2025-07-01","value":"10"}],"inputOrigin":"creation"}]""";

    /** The made melding has no input: beside a timeline the rules derived stands what the creation gave it, as the
     *  input stands beside it on an object of the request, with {@code inputOrigin} {@code creation}; a member no rule
     *  writes keeps the origin {@code creation}. J1, an object of the request, keeps the form it had. */
    @Test
    void the_fixpoint_of_a_made_object_gives_the_value_of_the_making_beside_the_derived_timeline() throws Exception {
        HttpResponse<String> r = post(creationDetail, "meldingen", "fixpoint=true", CREATION_CASE1);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode objects = tree(r.body()).at("/fixpoint/objects");
        assertEquals("#1", objects.at("/1/object").asText(), r.body());
        assertEquals(tree(MADE_CASE1), objects.at("/1/values"), r.body());
        assertEquals(tree("""
                {"attribute":"strandloon","timeline":[{"from":"2025-01-01","to":"2025-07-01","value":"10"}],
                 "origin":"input"}"""), objects.at("/0/values/0"), r.body());
    }

    /** case2: J2 has no strandloon, so the creation gives the meldloon, the seizoensloon and the aanvulloon no value:
     *  the meldloon has no row, and beside the derived seizoensloon and aanvulloon stands no input. */
    @Test
    void without_a_value_of_the_making_the_fixpoint_gives_no_input() throws Exception {
        HttpResponse<String> r = post(creationDetail, "meldingen", "fixpoint=true", """
                {"instances":[{"type":"Jutter","id":"J2"}]}""");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(tree("""
                [{"attribute":"meldwaarde","timeline":[{"value":"7"}],"origin":"creation"},
                 {"attribute":"seizoenswaarde",
                  "timeline":[{"to":"2025-03-01","value":"7"},{"from":"2025-03-01","to":"2025-09-01","value":"20"},
                              {"from":"2025-09-01","value":"7"}],
                  "origin":"derived","input":[{"value":"7"}],"inputOrigin":"creation"},
                 {"attribute":"seizoensloon","timeline":[{"from":"2025-03-01","to":"2025-09-01","value":"20"}],
                  "origin":"derived"},
                 {"attribute":"aanvulloon","timeline":[{"value":"5"}],"origin":"derived"}]"""),
                tree(r.body()).at("/fixpoint/objects/1/values"), r.body());
    }

    /** The playground gives the scenario of case1 the same fixpoint: uchar's replay hands its session over before the
     *  write-back, where the value of the making is read. */
    @Test
    void the_playground_gives_the_value_of_the_making_in_the_same_fixpoint() throws Exception {
        String source = Files.readString(CREATION.resolve("source.rgs")) + """

                Scenario strandloon tot juli
                Gegeven is de volgende situatie:
                een Jutter J1
                - het strandloon is 10 van 01-01-2025 tot 01-07-2025
                Met als verwacht resultaat:
                - het seizoensloontotaal van J1 is 10 van 01-01-2025 tot 01-03-2025
                - het seizoensloontotaal van J1 is 20 van 01-03-2025 tot 01-09-2025
                """;
        JsonNode playground = Playground.analyze(source).at("/scenario/results/0");
        assertEquals(tree(MADE_CASE1), playground.at("/fixpoint/objects/1/values"), playground.toString());
        HttpResponse<String> r = post(creationDetail, "meldingen", "fixpoint=true", CREATION_CASE1);
        assertEquals(playground.get("fixpoint"), tree(r.body()).get("fixpoint"), r.body());
    }

    /** case2 of jutterswet-afwezigheidsmarkers-initialisatie: the input niet van toepassing in January and February
     *  beside the rule of the spring and the initialisation on onbekend; a keurkosten of 4 over 2025 that the
     *  conditional initialisation, which does not hold, leaves. */
    private static final String MARKERS_CASE2 = """
        {"instances":[{"type":"Vondst","id":"V2",
          "attributes":{
            "opslagkosten":{"timeline":[{"from":"2025-01-01","to":"2025-04-01","value":{"marker":"niet van toepassing"}}]},
            "keurkosten":{"timeline":[{"from":"2025-01-01","to":"2026-01-01","value":4}]}}}]}""";

    @Test
    void the_explanation_names_an_initialisation_on_a_marker_per_period() throws Exception {
        HttpResponse<String> r = post(markers, "opslag", "explanation=true", MARKERS_CASE2);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(List.of(
                "V2 heeft als opslagkosten 10 van 01-03-2025 tot 01-06-2025 volgens de regel 'opslag in het voorjaar',"
                        + " omdat van dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025), en onbekend tot"
                        + " 01-01-2025 en onbekend vanaf 01-06-2025 volgens de regel 'opslag onbekend', omdat de"
                        + " opslagkosten in de invoer leeg was; de invoerwaarde niet van toepassing van 01-01-2025 tot"
                        + " 01-03-2025 bleef staan",
                "V2 houdt als keurkosten de invoerwaarde 4 van 01-01-2025 tot 01-01-2026, want geen regel gold; de"
                        + " regel 'keuring onbekend' gold niet, want niet geldt: het onderwerp ongekeurd is"),
                sentences(r.body()), r.body());
    }

    // ---------------- what uchar refuses on a timeline target ----------------

    /** An initialisation of a time-dependent attribute whose value is the time-proportional part: uchar's branch
     *  writes an initialisation per day only as period arithmetic or the constructor of a literal (DROOLS-316). */
    private static final String PROPORTIONAL = """
        Objecttype de Bakenmeester (mv: Bakenmeesters) (bezield)
            het onderhoudstarief Numeriek (getal met 2 decimalen) voor elk jaar;
            de onderhoudsbijdrage Numeriek (getal met 2 decimalen) voor elke maand;
            is dienstdoend kenmerk (bijvoeglijk) voor elke maand;

        Regel standaardbijdrage
            geldig altijd
                De onderhoudsbijdrage van een Bakenmeester moet geïnitialiseerd worden op het tijdsevenredig deel per maand van zijn onderhoudstarief gedurende de tijd dat hij dienstdoend is.
        """;

    private static final String PROPORTIONAL_DESCRIPTOR = """
        { "service": {"name":"baken","version":"1"},
          "entrypoints": [ { "name":"bijdrage","path":"bijdrage",
            "scope": {"ruleNames": ["standaardbijdrage"]},
            "input": ["Bakenmeester"], "output": ["Bakenmeester.onderhoudsbijdrage"] } ],
          "parameters": {} }""";

    @Test
    void an_initialisation_whose_value_the_branch_does_not_write_per_day_is_a_marker_naming_the_rule() {
        ServiceError e = assertThrows(ServiceError.class, () -> DecisionCore.assemble(PROPORTIONAL_DESCRIPTOR,
                Map.of("baken.rgs", PROPORTIONAL), CoreConfig.strict(), Thread.currentThread().getContextClassLoader()));
        assertEquals(1, e.violations().size(), e.violations().toString());
        Violation v = e.violations().get(0);
        assertEquals(Violation.Kind.MARKER, v.kind(), e.violations().toString());
        assertEquals("entrypoint:bijdrage", v.field(), e.violations().toString());
        assertEquals("regel 'standaardbijdrage' in de scope van entrypoint 'bijdrage' is een marker (niet gedragen):"
                + " transpiler SKIP: regel: standaardbijdrage — de waarde van een initialisatie op het"
                + " tijdsafhankelijke attribuut 'onderhoudsbijdrage' van Bakenmeester is geen rekenen per periode van"
                + " ch. 3.4 en 3.5 en geen constructor van een literal ('het tijdsevenredig deel per maand van zijn"
                + " onderhoudstarief gedurende de tijd dat hij dienstdoend is'): deze tak schrijft haar per dag alleen als een van die twee (RUNTIME-SEMANTICS"
                + " ch. 3.12 rij 10, 7.8)", v.message(), e.violations().toString());
    }

    /** With markers allowed the service starts, and a request to the entry point is refused with uchar's sentence. */
    @Test
    void with_markers_allowed_a_request_to_that_entry_point_is_503_with_the_refusal() throws Exception {
        ServiceHttp s = ServiceHttp.bind(DecisionCore.assemble(PROPORTIONAL_DESCRIPTOR,
                Map.of("baken.rgs", PROPORTIONAL), CoreConfig.withMarkers(),
                Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        try {
            HttpResponse<String> r = post(s, "bijdrage", "", """
                {"instances":[{"type":"Bakenmeester","id":"B1"}]}""");
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("de waarde van een initialisatie op het tijdsafhankelijke attribuut"
                    + " 'onderhoudsbijdrage' van Bakenmeester is geen rekenen per periode"), r.body());
        } finally {
            s.stop(0);
        }
    }

    private static final String ALLOCATION_DESCRIPTOR = """
        { "service": {"name":"berging","version":"1"},
          "entrypoints": [ { "name":"berging","path":"berging",
            "scope": {"ruleNames": ["verdeel de opbrengst van een berging"]},
            "input": ["Berging", "Berger"], "output": ["Berger.bergingsaandeel"] } ],
          "parameters": {} }""";

    /** From RegelSpraak the frontend refuses the allocation (RGS-378), so the service does not start. */
    @Test
    void an_allocation_over_a_time_dependent_attribute_refuses_the_start_with_the_frontends_error() throws Exception {
        ServiceError e = assertThrows(ServiceError.class, () -> DecisionCore.assemble(ALLOCATION_DESCRIPTOR,
                Map.of("berging.rgs", Files.readString(ALLOCATION.resolve("source.rgs"))), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        assertEquals(1, e.violations().size(), e.violations().toString());
        Violation v = e.violations().get(0);
        assertEquals(Violation.Kind.MALFORMED, v.kind(), e.violations().toString());
        assertEquals("resolve-fout: verdeling 'verdeel de opbrengst van een berging' noemt het tijdsafhankelijke"
                + " attribuut 'bergingsaandeel' van Berger: een verdeling verdeelt één bedrag op één moment, en een"
                + " lezing ervan per dag is niet gedefinieerd (WF-128)", v.message(), e.violations().toString());
    }

    /** From a document, which the service does not verify, uchar's branch refuses the allocation as not well-formed
     *  (DROOLS-315, WF-128), and the entry point is not carried. */
    @Test
    void an_allocation_over_a_time_dependent_attribute_in_a_document_is_refused_by_the_branch_naming_wf_128()
            throws Exception {
        ServiceError e = assertThrows(ServiceError.class, () -> DecisionCore.buildFromDocument(ALLOCATION_DESCRIPTOR,
                Files.readString(ALLOCATION.resolve("ir.json")), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null));
        assertEquals(1, e.violations().size(), e.violations().toString());
        Violation v = e.violations().get(0);
        assertEquals(Violation.Kind.MARKER, v.kind(), e.violations().toString());
        assertEquals("regel 'verdeel de opbrengst van een berging' in de scope van entrypoint 'berging' is een marker"
                + " (niet gedragen): transpiler SKIP: verdeling: verdeel de opbrengst van een berging — de verdeling"
                + " \"verdeel de opbrengst van een berging\" noemt in haar veld targetAttribute het tijdsafhankelijke"
                + " attribuut 'bergingsaandeel' van Berger: een verdeling verdeelt één bedrag op één moment, en het"
                + " document is niet welgevormd (IR-SPEC WF-128)", v.message(), e.violations().toString());
    }

    // ---------------- helpers ----------------

    /** Every attributeTimeline expectation of uchar's case file {@code caseFile} on {@code id} is the timeline of that
     *  attribute in {@code answer}, compared in canonical form (adjacent equal segments joined, a number without
     *  trailing zeros, a marker by its name, an open bound empty), as uchar's replay compares a timeline. */
    private static void assertPins(Path caseFile, String id, JsonNode answer) throws Exception {
        JsonNode c = tree(Files.readString(caseFile));
        int compared = 0;
        for (JsonNode e : c.get("expected")) {
            if (!"attributeTimeline".equals(e.path("kind").asText())) continue;
            String label = e.get("label").asText();
            assertEquals(id, label, caseFile.toString());
            String attribute = e.get("attribute").asText();
            JsonNode given = attribute(answer, id, attribute);
            assertTrue(given != null && given.has("timeline"), attribute + ": " + answer);
            assertEquals(canonical(e.get("timeline"), true), canonical(given.get("timeline"), false),
                    attribute + ": " + answer);
            compared++;
        }
        assertTrue(compared > 0, "geen tijdlijnverwachting in " + caseFile);
    }

    /** The segments of a timeline in canonical form; {@code ucharCase} reads a value as uchar's case writes it
     *  ({@code {"number": …}}, {@code {"constructor": …, "payload": …}}), otherwise as the answer writes it (a number, a
     *  marker as {@code {"marker": …}}). */
    private static List<String> canonical(JsonNode timeline, boolean ucharCase) {
        List<String[]> joined = new ArrayList<>();
        for (JsonNode s : timeline) {
            String from = s.has("from") ? s.get("from").asText() : "", to = s.has("to") ? s.get("to").asText() : "";
            String value = ucharCase ? caseValue(s.get("value")) : answerValue(s.get("value"));
            String[] last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && last[1].equals(from) && !from.isEmpty() && last[2].equals(value)) last[1] = to;
            else joined.add(new String[]{from, to, value});
        }
        return joined.stream().map(s -> s[0] + ".." + s[1] + "=" + s[2]).toList();
    }

    private static String caseValue(JsonNode v) {
        if (v.has("number")) return new java.math.BigDecimal(v.get("number").asText()).stripTrailingZeros().toPlainString();
        if (v.has("constructor")) return "waarde".equals(v.get("constructor").asText())
                ? caseValue(v.get("payload")) : "marker:" + v.get("constructor").asText();
        throw new IllegalStateException("een waarde van uchars case die deze test niet leest: " + v);
    }

    private static String answerValue(JsonNode v) {
        if (v.isNumber()) return v.decimalValue().stripTrailingZeros().toPlainString();
        if (v.has("marker")) return "marker:" + v.get("marker").asText();
        throw new IllegalStateException("een waarde van het antwoord die deze test niet leest: " + v);
    }

    private static List<String> sentences(String body) throws Exception {
        List<String> sentences = new ArrayList<>();
        tree(body).get("explanation").forEach(s -> sentences.add(s.asText()));
        return sentences;
    }

    private static JsonNode attribute(JsonNode answer, String id, String attribute) {
        for (JsonNode inst : answer.get("instances"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributes").get(attribute);
        return null;
    }

    private static HttpResponse<String> post(ServiceHttp to, String path, String query, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + to.port() + "/" + path
                                + (query.isEmpty() ? "" : "?" + query)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String json) throws Exception {
        return ServiceHttp.MAPPER.readTree(json);
    }
}
