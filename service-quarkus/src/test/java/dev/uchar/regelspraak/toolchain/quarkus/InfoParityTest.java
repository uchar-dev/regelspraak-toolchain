package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * Pins that the Quarkus adapter's {@code GET /info} body ({@link InfoBuilder#assemble}) is IDENTICAL to the one the
 * zero-dep {@code ServiceHttp} serves — over the SAME {@link DecisionCore}. {@code ServiceHttp.infoBody()} is
 * package-private in the service module, so {@link InfoBuilder} reproduces its shape by hand; this test is the
 * anti-drift net that catches a divergence in EITHER mirror (if {@code ServiceHttp.infoBody()} gains a field and
 * {@link InfoBuilder} does not, the trees stop matching and this fails).
 *
 * <p>A PLAIN JUnit test (NOT {@code @QuarkusTest}) on purpose: it needs no Quarkus augmentation — it builds one
 * kern, feeds it to both the in-process {@link ServiceHttp} (bound on an ephemeral port) and {@link InfoBuilder},
 * and deep-compares the two JSON trees. No value is re-typed; the whole /info body comes from the shared kern.
 */
class InfoParityTest {

    /** BigDecimal-for-floats so the parse of ServiceHttp's /info matches InfoBuilder's exact-BigDecimal nodes. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    @Test
    void quarkus_info_body_matches_service_http() throws Exception {
        DecisionCore core = DecisionCore.assemble(TokaSource.descriptorJson(), TokaSource.sourcePerFile(),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());

        // the Quarkus adapter's /info body, built directly.
        ObjectNode quarkusInfo = InfoBuilder.assemble(core);

        // the zero-dep ServiceHttp's /info body over the SAME kern, fetched over HTTP.
        ServiceHttp service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + "/info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            JsonNode serviceHttpInfo = MAPPER.readTree(resp.body());
            assertEquals(serviceHttpInfo, quarkusInfo,
                    "de Quarkus /info body wijkt af van DienstHttp /info — de InfoBouwer-spiegel is gedreven");
        } finally {
            service.stop(0);
        }
    }
}
