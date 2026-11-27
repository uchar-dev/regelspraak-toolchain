package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenValuesSource;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process parity between {@link DecideResource} and {@link ServiceHttp}'s status/body mappers on open and settled-empty
 * non-reciprocal {@code to-one} roles, {@code 400} role-state violations, {@code 422} {@code EF-049}, {@code /info} and
 * {@code /openapi}.
 */
class OpenRolesQuarkusResourceTest {

    @Test
    void quarkus_resource_and_service_http_match_on_open_empty_400_and_422_ef049() throws Exception {
        DecisionCore core = OpenValuesSource.wrak(CoreConfig.strict().withDetail());
        DecideResource resource = new DecideResource();
        resource.core = core;

        // 1. Open role on W1 -> 200 with openInputs kind=role and open role in fixpoint (with factType)
        String openReq = "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"lengte\": 30}}]}";
        JsonNode openBody = comparePost(core, resource, "wrak", openReq, null, "true", "true", "true", 200);
        assertTrue(openBody.get("openInputs").toString()
                .contains("\"kind\":\"role\",\"instance\":\"W1\",\"factType\":\"wrakvondst\",\"name\":\"vinder\""),
                openBody.toString());
        assertTrue(openBody.get("explanation").toString().contains("want zijn vinder is niet gegeven"), openBody.toString());
        assertTrue(openBody.at("/fixpoint/objects/0/open").toString()
                .contains("{\"role\":\"vinder\",\"factType\":\"wrakvondst\"}"), openBody.toString());

        // 2. Settled-empty role on W1 -> 200, vindpremie = 30, vinder absent from openInputs and fixpoint.open
        String emptyReq = "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"attributes\": {\"lengte\": 30},"
                + " \"roles\": {\"vinder\": {\"state\": \"empty\"}}}]}";
        JsonNode emptyBody = comparePost(core, resource, "wrak", emptyReq, null, "true", "true", "true", 200);
        assertEquals("{\"state\":\"filled\",\"value\":30}", emptyBody.at("/instances/0/attributes/vindpremie").toString());
        assertFalse(emptyBody.get("openInputs").toString().contains("\"vinder\""), emptyBody.toString());
        assertFalse(emptyBody.at("/fixpoint/objects/0/open").toString().contains("\"vinder\""), emptyBody.toString());

        // 3. Invalid role state -> 400 WRONG_TYPE
        String wrongTypeReq = "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\","
                + " \"roles\": {\"vinder\": {\"state\": \"open\"}}}]}";
        JsonNode wrongBody = comparePost(core, resource, "wrak", wrongTypeReq, null, null, null, null, 400);
        assertEquals("WRONG_TYPE", wrongBody.at("/violations/0/kind").asText(), wrongBody.toString());
        assertEquals("instances[0].roles.vinder", wrongBody.at("/violations/0/field").asText(), wrongBody.toString());

        // 4. Opposite-instance link + rolesEmpty -> 422 EVALUATION_FAULT EF-049
        String ef049Req = """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"gevonden wrakken": ["W1"]}},
                           {"type": "Wrak", "id": "W1", "roles": {"vinder": {"state": "empty"}}}]}""";
        JsonNode ef049Body = comparePost(core, resource, "wrak", ef049Req, null, null, null, null, 422);
        assertEquals("EVALUATION_FAULT", ef049Body.get("status").asText(), ef049Body.toString());
        assertEquals("EF-049", ef049Body.at("/faults/0/code").asText(), ef049Body.toString());
        assertEquals("W1", ef049Body.at("/faults/0/params/label").asText(), ef049Body.toString());
        assertEquals("vinder", ef049Body.at("/faults/0/params/role").asText(), ef049Body.toString());

        // 5. /info and /openapi
        Response infoResp = resource.info();
        assertEquals(200, infoResp.getStatus());
        assertEquals(ServiceHttp.info(core), ServiceHttp.MAPPER.readTree((String) infoResp.getEntity()));

        Response oaResp = resource.openapi(null);
        assertEquals(200, oaResp.getStatus());
        ServiceHttp.Document jdkDoc = ServiceHttp.openapi(core, null);
        assertEquals(200, jdkDoc.status());
        assertArrayEquals(jdkDoc.body(), (byte[]) oaResp.getEntity());
        String oaText = new String(jdkDoc.body(), StandardCharsets.UTF_8);
        assertTrue(oaText.contains("EmptyRoleInput"), oaText);

        // 6. Canonically colliding role without plural via vocabulary designation -> 400 UNSUPPORTED (never 500),
        //    and vocabulary parity on open roles + /info completeInput with factType
        DecisionCore collidingCore = DecisionCore.assemble("""
            { "service": {"name":"botsend","version":"1"},
              "entrypoints": [ { "name":"wrak","path":"wrak",
                                 "scope": {"ruleNames": ["aanmelding", "uitwijking"]},
                                 "input": [], "output": ["Vaartuig:aangemeld", "Wrak:uitgeweken"] } ],
              "parameters": {} }""",
                java.util.Map.of("botsend.rgs", """
                    Afleiden uit leeg: nee
                    Vocabulaire handboek (taal: nl)
                    Objecttype de Haven (mv: Havens)
                    Objecttype het Vaartuig (mv: Vaartuigen)
                        is aangemeld kenmerk (bijvoeglijk);
                    Objecttype het Wrak is een Vaartuig (mv: Wrakken)
                        is uitgeweken kenmerk (bijvoeglijk);
                    Feittype thuishaven (handboek: vaste havenrelatie)
                        de haven (handboek: vaste haven) Haven (volledig gegeven)
                        het vaartuig (mv: vaartuigen) Vaartuig
                        één haven heeft meerdere vaartuigen
                    Feittype noodhavenrelatie (handboek: uitwijkrelatie)
                        de haven (handboek: uitwijkhaven) Haven
                        het noodwrak (mv: noodwrakken) Wrak
                        één haven heeft meerdere noodwrakken
                    Regel aanmelding
                        geldig altijd
                            Een Vaartuig is aangemeld indien hij een vaartuig is.
                    Regel uitwijking
                        geldig altijd
                            Een Wrak is uitgeweken indien hij een noodwrak is.
                    """),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        DecideResource collidingResource = new DecideResource();
        collidingResource.core = collidingCore;
        JsonNode unsupported = comparePost(collidingCore, collidingResource, "wrak",
                "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\", \"roles\": {\"uitwijkhaven\": {\"state\": \"empty\"}}}]}",
                null, null, null, null, 400);
        assertEquals("UNSUPPORTED", unsupported.at("/violations/0/kind").asText(), unsupported.toString());
        assertEquals("instances[0].roles.uitwijkhaven", unsupported.at("/violations/0/field").asText(), unsupported.toString());

        JsonNode vocabOpen = comparePost(collidingCore, collidingResource, "wrak",
                "{\"instances\": [{\"type\": \"Wrak\", \"id\": \"W1\"}]}",
                "handboek", "true", "true", "true", 200);
        assertEquals(ServiceHttp.MAPPER.readTree("""
            [{"kind": "role", "instance": "W1", "factType": "uitwijkrelatie", "name": "uitwijkhaven",
              "forOutputs": [{"instance": "W1", "name": "uitgeweken", "kind": "characteristic"}]}]"""),
                vocabOpen.get("openInputs"));
        assertTrue(vocabOpen.at("/fixpoint/objects/0/open").toString().contains(
                "{\"role\":\"uitwijkhaven\",\"roleCanonical\":\"haven\","
                        + "\"factType\":\"uitwijkrelatie\",\"factTypeCanonical\":\"noodhavenrelatie\"}"),
                vocabOpen.at("/fixpoint/objects/0/open").toString());

        Response collidingInfoResp = collidingResource.info();
        assertEquals(200, collidingInfoResp.getStatus());
        JsonNode collidingInfo = ServiceHttp.MAPPER.readTree((String) collidingInfoResp.getEntity());
        assertEquals(ServiceHttp.info(collidingCore), collidingInfo);
        assertEquals(ServiceHttp.MAPPER.readTree("""
            [{"kind": "role", "objectType": "Vaartuig", "factType": "thuishaven", "name": "haven"}]"""),
                collidingInfo.get("completeInput"));

        Response vocabOaResp = collidingResource.openapi("handboek");
        assertEquals(200, vocabOaResp.getStatus());
        assertArrayEquals(ServiceHttp.openapi(collidingCore, "handboek").body(), (byte[]) vocabOaResp.getEntity());
    }

    private static JsonNode comparePost(DecisionCore core, DecideResource resource, String path, String request,
                                        String vocabulary, String explanationParts, String openInputs, String fixpoint,
                                        int expectedStatus)
            throws Exception {
        Response quarkus = resource.process(path, null, vocabulary, null, null, null, fixpoint, explanationParts,
                openInputs, request);
        RequestOptions opts = new RequestOptions(false, vocabulary, null, false, false,
                ServiceHttp.isSet(fixpoint), ServiceHttp.isSet(explanationParts), ServiceHttp.isSet(openInputs));
        ServiceHttp.Answer jdk = ServiceHttp.post(core, core::process, path, opts, request);
        assertEquals(expectedStatus, jdk.status(), String.valueOf(jdk.body()));
        assertEquals(expectedStatus, quarkus.getStatus(), String.valueOf(quarkus.getEntity()));
        assertEquals(ServiceHttp.MAPPER.writeValueAsString(jdk.body()), (String) quarkus.getEntity());
        return ServiceHttp.MAPPER.readTree((String) quarkus.getEntity());
    }
}
