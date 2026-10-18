package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request whose one-to-one relation gets two different targets is refused by uchar's input step
 * ({@code CaseInput.RoleCollision}, WF-013), which reads the request written as a case; the service gives it as a 400
 * {@code VERKEERD_TYPE} on the field of the request that wrote the colliding link, with uchar's sentence. The collision
 * is checked on each field a link of a 1:1 relation writes: the instance's own field, the far field (RUNTIME-SEMANTICS
 * ch. 5.7a), the far field through {@code relaties}, and the reverse leg of a reciprocal relation (§13.3.9.2).
 */
class RoleCollisionHttpTest {

    /** uchar's golden {@code jutterswet-kavelgrens}: a self-typed one-to-one relation. */
    private static final String KAVEL_SOURCE = """
        Objecttype het Duinkavel (mv: Duinkavels)
            de kavelbreedte Numeriek (niet-negatief geheel getal);
            de noordbuurbreedte Numeriek (niet-negatief geheel getal);
            de zuidbuurbreedte Numeriek (niet-negatief geheel getal);

        Feittype kavelgrens
            de noordbuur (mv: noordburen) Duinkavel
            de zuidbuur (mv: zuidburen) Duinkavel
        Eén noordbuur heeft één zuidbuur

        Regel noordbuurbreedte
            geldig altijd
                De noordbuurbreedte van een Duinkavel moet berekend worden als de kavelbreedte van de noordbuur van het Duinkavel.

        Regel zuidbuurbreedte
            geldig altijd
                De zuidbuurbreedte van een Duinkavel moet berekend worden als de kavelbreedte van de zuidbuur van het Duinkavel.
        """;

    private static final String KAVEL_DESCRIPTOR = """
        { "dienst": {"naam":"kavel","versie":"1"},
          "entrypoints": [ { "naam":"buurbreedte","pad":"buurbreedte",
            "scope": {"regelnamen": ["noordbuurbreedte", "zuidbuurbreedte"]},
            "invoer": ["Duinkavel"], "uitvoer": ["Duinkavel.noordbuurbreedte", "Duinkavel.zuidbuurbreedte"] } ],
          "parameters": {} }""";

    /** A reciprocal one-to-one relation. */
    private static final String MAAT_SOURCE = """
        Objecttype de Vondst (mv: Vondsten)
            de waarde Numeriek (geheel getal);
            de maatwaarde Numeriek (geheel getal);

        Wederkerig feittype vondstpaar
            de maat (mv: maten) Vondst
        Eén maat heeft één maat

        Regel maatwaarde van een vondst
            geldig altijd
                De maatwaarde van een Vondst moet gesteld worden op de waarde van de maat van de Vondst.
        """;

    private static final String MAAT_DESCRIPTOR = """
        { "dienst": {"naam":"maat","versie":"1"},
          "entrypoints": [ { "naam":"maatwaarde","pad":"maatwaarde",
            "scope": {"regelnamen": ["maatwaarde van een vondst"]},
            "invoer": ["Vondst"], "uitvoer": ["Vondst.maatwaarde"] } ],
          "parameters": {} }""";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static ServiceHttp kavel;
    private static ServiceHttp maat;

    @BeforeAll
    static void start() throws Exception {
        kavel = ServiceHttp.bind(DecisionCore.assemble(KAVEL_DESCRIPTOR, Map.of("kavel.rgs", KAVEL_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
        maat = ServiceHttp.bind(DecisionCore.assemble(MAAT_DESCRIPTOR, Map.of("maat.rgs", MAAT_SOURCE),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader()), "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (kavel != null) kavel.stop(0);
        if (maat != null) maat.stop(0);
    }

    /** One link reaches both fields: K2 reads K1 as its noordbuur without a link of its own. */
    @Test
    void a_link_of_a_one_to_one_relation_applies_both_ways() throws Exception {
        HttpResponse<String> resp = post(kavel, "/buurbreedte", """
            {"instanties":[{"type":"Duinkavel","id":"K1","attributen":{"kavelbreedte":10},"rollen":{"zuidbuur":"K2"}},
              {"type":"Duinkavel","id":"K2","attributen":{"kavelbreedte":20}}]}""");
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("10", String.valueOf(find(tree(resp.body()), "K2", "noordbuurbreedte")), resp.body());
        assertEquals("20", String.valueOf(find(tree(resp.body()), "K1", "zuidbuurbreedte")), resp.body());
    }

    /** K1 names two zuidburen: its own to-one field gets a second target, on the link to K3. */
    @Test
    void two_targets_on_its_own_field() throws Exception {
        assertCollision(post(kavel, "/buurbreedte", """
            {"instanties":[{"type":"Duinkavel","id":"K1","rollen":{"zuidbuur":["K2","K3"]}},
              {"type":"Duinkavel","id":"K2"},{"type":"Duinkavel","id":"K3"}]}"""),
                "instanties[0].rollen.zuidbuur", "zuidbuur");
    }

    /** K1 and K3 both name K2 as their zuidbuur: K2's noordbuur field, the far field of the one-to-one relation, gets
     *  a second target by K3's link. */
    @Test
    void two_targets_on_the_far_field() throws Exception {
        assertCollision(post(kavel, "/buurbreedte", """
            {"instanties":[{"type":"Duinkavel","id":"K1","rollen":{"zuidbuur":"K2"}},
              {"type":"Duinkavel","id":"K2"},{"type":"Duinkavel","id":"K3","rollen":{"zuidbuur":"K2"}}]}"""),
                "instanties[2].rollen.zuidbuur", "noordbuur");
    }

    /** As above, with the second link a relation of {@code relaties}: the violation names the role of the relation. */
    @Test
    void two_targets_on_the_far_field_via_relations() throws Exception {
        assertCollision(post(kavel, "/buurbreedte", """
            {"instanties":[{"type":"Duinkavel","id":"K1","rollen":{"zuidbuur":"K2"}},
              {"type":"Duinkavel","id":"K2"},{"type":"Duinkavel","id":"K3"}],
             "relaties":[{"feittype":"kavelgrens","rollen":{"noordbuur":"K3","zuidbuur":"K2"}}]}"""),
                "relaties[0].rollen.zuidbuur", "noordbuur");
    }

    /** V1 and V3 both name V2 as their maat: the reverse leg of V3's link finds V2 bound to V1. */
    @Test
    void two_targets_on_the_reversed_leg_of_a_reciprocal_relation() throws Exception {
        assertCollision(post(maat, "/maatwaarde", """
            {"instanties":[{"type":"Vondst","id":"V1","rollen":{"maat":"V2"}},
              {"type":"Vondst","id":"V2"},{"type":"Vondst","id":"V3","rollen":{"maat":"V2"}}]}"""),
                "instanties[2].rollen.maat", "maat");
    }

    /** One violation, VERKEERD_TYPE on {@code field}, in uchar's sentence of a to-one role {@code role} with a second
     *  target. */
    private static void assertCollision(HttpResponse<String> resp, String field, String role) throws Exception {
        assertEquals(400, resp.statusCode(), resp.body());
        JsonNode body = tree(resp.body());
        assertEquals(1, body.path("schendingen").size(), resp.body());
        assertEquals("VERKEERD_TYPE", body.at("/schendingen/0/soort").asText(), resp.body());
        assertEquals(field, body.at("/schendingen/0/veld").asText(), resp.body());
        String sentence = body.at("/schendingen/0/fout").asText();
        assertTrue(sentence.contains("'" + role + "'") && sentence.contains("WF-013"), sentence);
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
        for (JsonNode inst : answer.get("instanties"))
            if (id.equals(inst.path("id").asText())) return inst.path("attributen").get(attribute);
        return null;
    }
}
