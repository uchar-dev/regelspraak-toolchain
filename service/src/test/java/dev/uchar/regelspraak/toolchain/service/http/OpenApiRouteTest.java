package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.OpenApiEmitter;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code GET /openapi} on the JDK adapter, and {@code ServiceMain --openapi}, the Java side of
 * {@code bin/regelspraak openapi}. The service is the own Jutterswet material with comments above its declarations and
 * the vocabulary {@code handboek} ({@code src/test/resources/comments/}): the route sends the canonical bytes of
 * {@link OpenApiEmitter#emit} over the core's descriptor and model, also the byte-pinned golden of
 * {@link dev.uchar.regelspraak.toolchain.service.OpenApiCommentTest}, in the vocabulary the query names; an unknown
 * vocabulary is a 400 {@code UNKNOWN_VOCABULARY}; and the command writes the bytes the route sends.
 */
class OpenApiRouteTest {

    private static final Path DIR = TokaSource.REPO.resolve("service/src/test/resources/comments");
    private static final Path DESCRIPTOR = DIR.resolve("jutterswet-commentaar.service.json");
    private static final Path SOURCE = DIR.resolve("jutterswet-commentaar.rgs");

    private static DecisionCore core;
    private static ServiceHttp service;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        core = DecisionCore.assemble(DESCRIPTOR, List.of(SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
        service = ServiceHttp.bind(core, "127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (service != null) service.stop(0);
    }

    private static HttpResponse<byte[]> get(String pathAndQuery) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + service.port() + pathAndQuery))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void without_a_vocabulary_the_route_sends_the_canonical_document() throws Exception {
        HttpResponse<byte[]> response = get("/openapi");
        assertEquals(200, response.statusCode());
        assertEquals("application/json; charset=UTF-8", response.headers().firstValue("Content-Type").orElse(""));
        assertArrayEquals(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(), core.model())),
                response.body());
        assertArrayEquals(Files.readAllBytes(TokaSource.REPO.resolve(
                "service/src/test/resources/openapi/jutterswet-commentaar.golden.json")), response.body());
    }

    @Test
    void canonical_chooses_the_canonical_document() throws Exception {
        assertArrayEquals(get("/openapi").body(), get("/openapi?vocabulary=canonical").body());
    }

    @Test
    void a_vocabulary_chooses_its_document() throws Exception {
        HttpResponse<byte[]> response = get("/openapi?vocabulary=handboek");
        assertEquals(200, response.statusCode());
        assertArrayEquals(OpenApiEmitter.toBytes(OpenApiEmitter.emit(core.descriptor(), core.model(), "handboek")),
                response.body());
    }

    @Test
    void an_unknown_vocabulary_is_a_400_unknown_vocabulary() throws Exception {
        HttpResponse<byte[]> response = get("/openapi?vocabulary=cgm");
        assertEquals(400, response.statusCode());
        JsonNode body = ServiceHttp.MAPPER.readTree(response.body());
        assertEquals("verzoek valideert niet", body.path("error").asText(), body.toString());
        assertEquals(1, body.path("violations").size(), body.toString());
        assertEquals("UNKNOWN_VOCABULARY", body.at("/violations/0/kind").asText(), body.toString());
        assertEquals("vocabulary", body.at("/violations/0/field").asText(), body.toString());
    }

    @Test
    void another_method_is_a_405() throws Exception {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/openapi"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode(), response.body());
    }

    /** {@code bin/regelspraak openapi} runs {@code ServiceMain --openapi}: it writes byte for byte what the route
     *  sends, canonically and in a vocabulary. */
    @Test
    void the_command_writes_the_bytes_the_route_sends(@TempDir Path dir) throws Exception {
        Path canonical = dir.resolve("canonical.json");
        assertEquals(0, ServiceMain.openapi(new String[]{canonical.toString(), DESCRIPTOR.toString(),
                SOURCE.toString()}));
        assertArrayEquals(get("/openapi").body(), Files.readAllBytes(canonical));
        Path handbook = dir.resolve("handboek.json");
        assertEquals(0, ServiceMain.openapi(new String[]{"--vocabulary", "handboek", handbook.toString(),
                DESCRIPTOR.toString(), SOURCE.toString()}));
        assertArrayEquals(get("/openapi?vocabulary=handboek").body(), Files.readAllBytes(handbook));
    }

    /** An unknown vocabulary is a wrong invocation of the command: exit 2, and no file. */
    @Test
    void the_command_refuses_an_unknown_vocabulary(@TempDir Path dir) {
        Path out = dir.resolve("cgm.json");
        assertEquals(ServiceMain.EXIT_USAGE, ServiceMain.openapi(new String[]{"--vocabulary", "cgm", out.toString(),
                DESCRIPTOR.toString(), SOURCE.toString()}));
        assertFalse(Files.exists(out));
    }

    @Test
    void the_command_reads_its_arguments() {
        ServiceMain.OpenapiArgs args = ServiceMain.parseOpenapiArgs(new String[]{"uit.json", "--vocabulary", "handboek",
                "d.service.json", "a.rgs", "b.rgs"});
        assertEquals(Path.of("uit.json"), args.out());
        assertEquals("handboek", args.vocabulary());
        assertEquals(Path.of("d.service.json"), args.descriptor());
        assertEquals(List.of(Path.of("a.rgs"), Path.of("b.rgs")), args.sources());
        assertEquals("geen bron opgegeven: .rgs-bestanden of één uchar-ir-v1-document",
                assertThrows(IllegalArgumentException.class,
                        () -> ServiceMain.parseOpenapiArgs(new String[]{"uit.json", "d.service.json"})).getMessage());
    }
}
