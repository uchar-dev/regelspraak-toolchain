package io.github.yhavinga.regelspraak.quarkus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.yhavinga.regelspraak.service.BeslisserKern;
import io.github.yhavinga.regelspraak.service.KernConfig;
import io.github.yhavinga.regelspraak.service.TokaBron;
import io.github.yhavinga.regelspraak.service.http.DienstHttp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins that the Quarkus adapter's {@code GET /info} body ({@link InfoBouwer#bouw}) is IDENTICAL to the one the
 * zero-dep {@code DienstHttp} serves — over the SAME {@link BeslisserKern}. {@code DienstHttp.infoBody()} is
 * package-private in the service module, so {@link InfoBouwer} reproduces its shape by hand; this test is the
 * anti-drift net that catches a divergence in EITHER mirror (if {@code DienstHttp.infoBody()} gains a field and
 * {@link InfoBouwer} does not, the trees stop matching and this fails).
 *
 * <p>A PLAIN JUnit test (NOT {@code @QuarkusTest}) on purpose: it needs no Quarkus augmentation — it builds one
 * kern, feeds it to both the in-process {@link DienstHttp} (bound on an ephemeral port) and {@link InfoBouwer},
 * and deep-compares the two JSON trees. No value is re-typed; the whole /info body comes from the shared kern.
 */
class InfoParityTest {

    /** BigDecimal-for-floats so the parse of DienstHttp's /info matches InfoBouwer's exact-BigDecimal nodes. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    @Test
    void quarkus_info_body_matches_diensthttp() throws Exception {
        BeslisserKern kern = BeslisserKern.bouw(TokaBron.descriptorJson(), TokaBron.bronPerBestand(),
                KernConfig.strikt(), Thread.currentThread().getContextClassLoader());

        // the Quarkus adapter's /info body, built directly.
        ObjectNode quarkusInfo = InfoBouwer.bouw(kern);

        // the zero-dep DienstHttp's /info body over the SAME kern, fetched over HTTP.
        DienstHttp dienst = DienstHttp.bind(kern, "127.0.0.1", 0).start();
        try {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + dienst.poort() + "/info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            JsonNode dienstHttpInfo = MAPPER.readTree(resp.body());
            assertEquals(dienstHttpInfo, quarkusInfo,
                    "de Quarkus /info body wijkt af van DienstHttp /info — de InfoBouwer-spiegel is gedreven");
        } finally {
            dienst.stop(0);
        }
    }
}
