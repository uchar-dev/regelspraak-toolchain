package dev.uchar.regelspraak.toolchain.quarkus;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenApiEmitter;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the Quarkus adapter's {@code GET /openapi} sends the status and the bytes the JDK {@code ServiceHttp} sends
 * over the same {@link DecisionCore}, without a vocabulary, with {@code canonical}, with a vocabulary of the model and
 * with an unknown one (the 400 {@code UNKNOWN_VOCABULARY}), and that the 200 is the document of
 * {@link OpenApiEmitter#emit}. The core serves the service's own Jutterswet material with comments above its
 * declarations. A plain JUnit test, like {@link InfoParityTest}: the resource is called directly with the core set, and
 * {@code ServiceHttp} is asked over HTTP on an ephemeral port.
 */
class OpenApiParityTest {

    private static final Path DIR = TokaSource.REPO.resolve("service/src/test/resources/comments");

    private static DecisionCore core;
    private static ServiceHttp service;

    @BeforeAll
    static void start() throws Exception {
        core = DecisionCore.assemble(DIR.resolve("jutterswet-commentaar.service.json"),
                List.of(DIR.resolve("jutterswet-commentaar.rgs")), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    @Test
    void without_a_vocabulary_the_canonical_document() throws Exception {
        byte[] body = compare(null, 200);
        assertArrayEquals(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(), core.model())), body);
    }

    @Test
    void canonical_the_canonical_document() throws Exception {
        assertArrayEquals(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(), core.model())),
                compare("canonical", 200));
    }

    @Test
    void a_vocabulary_its_document() throws Exception {
        assertArrayEquals(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(), core.model(), "handboek")),
                compare("handboek", 200));
    }

    @Test
    void an_unknown_vocabulary_400_unknown_vocabulary() throws Exception {
        String body = new String(compare("cgm", 400), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"kind\":\"UNKNOWN_VOCABULARY\""), body);
    }

    /** The same {@code GET /openapi} to both adapters over the same core: the same status ({@code expected}) and the
     *  same bytes, which it returns. */
    private static byte[] compare(String vocabulary, int expected) throws Exception {
        DecideResource resource = new DecideResource();
        resource.core = core;
        Response quarkus = resource.openapi(vocabulary);

        HttpResponse<byte[]> jdk = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + service.port() + "/openapi"
                                + (vocabulary == null ? "" : "?vocabulary=" + vocabulary))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(expected, jdk.statusCode());
        assertEquals(expected, quarkus.getStatus());
        assertArrayEquals(jdk.body(), (byte[]) quarkus.getEntity(),
                "het document van de Quarkus-adapter wijkt af van dat van ServiceHttp");
        return jdk.body();
    }
}
