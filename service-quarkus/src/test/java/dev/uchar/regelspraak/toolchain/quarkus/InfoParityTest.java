package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that the Quarkus adapter's {@code GET /info} body is the one the JDK {@code ServiceHttp} serves over the same
 * {@link DecisionCore}: the resource is called directly with the core set, the JDK adapter over HTTP on an ephemeral
 * port, and the two JSON trees are compared. A plain JUnit test, not {@code @QuarkusTest}: it needs no Quarkus
 * augmentation.
 */
class InfoParityTest {

    /** BigDecimal-for-floats, as both adapters write their numbers. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    @Test
    void quarkus_info_body_matches_service_http() throws Exception {
        DecisionCore core = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());

        // the Quarkus adapter's /info body, from the resource called directly.
        DecideResource resource = new DecideResource();
        resource.core = core;
        JsonNode quarkusInfo = MAPPER.readTree((String) resource.info().getEntity());

        // the zero-dep ServiceHttp's /info body over the SAME kern, fetched over HTTP.
        ServiceHttp service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            JsonNode serviceHttpInfo = MAPPER.readTree(resp.body());
            assertEquals(serviceHttpInfo, quarkusInfo,
                    "de Quarkus /info body wijkt af van ServiceHttp /info");
        } finally {
            service.stop(0);
        }
    }
}
