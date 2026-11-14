package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bin/regelspraak playground} ({@code ServiceMain --playground}). Without a descriptor it analyses the source a
 * request posts; it has no served model, so {@code ?via=service} is a 400 that says why, the page says the same, and
 * the routes of a service are not there. With a descriptor and its source it serves that model's entry points beside
 * it, and {@code ?via=service} computes through them. {@code serve} binds no playground, whatever its environment.
 * The playground binds the loopback address unless {@code --host} names another; {@code serve} every interface.
 */
class PlaygroundCommandTest {

    private static final String SOURCE = """
            Objecttype de Persoon (mv: Personen)
                de leeftijd Numeriek (geheel getal);
                is minderjarig kenmerk (bijvoeglijk);

            Regel bepaal minderjarigheid
            geldig altijd
            Een Persoon is minderjarig indien de leeftijd van de Persoon kleiner is dan 18.

            Scenario een kind
            Gegeven is de volgende situatie:
            een Persoon P
            - de leeftijd is 12
            Met als verwacht resultaat:
            - P is minderjarig
            """;

    private static ServiceHttp playground;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        ServiceMain.Args args = ServiceMain.parsePlaygroundArgs(new String[]{});
        assertEquals(new ServiceMain.Args(null, List.of(), false, false, "127.0.0.1", 8080), args);
        playground = ServiceHttp.playground("127.0.0.1", 0).start();
    }

    @AfterAll
    static void stop() {
        if (playground != null) playground.stop(0);
    }

    @Test
    void it_takes_a_descriptor_and_its_sources_or_neither() {
        assertEquals(new ServiceMain.Args(null, List.of(), false, false, "127.0.0.1", 0),
                ServiceMain.parsePlaygroundArgs(new String[]{"--host", "127.0.0.1", "--port", "0"}));
        assertEquals(new ServiceMain.Args(Path.of("d.service.json"), List.of(Path.of("x.rgs")), true, true,
                        "127.0.0.1", 8080),
                ServiceMain.parsePlaygroundArgs(new String[]{"d.service.json", "x.rgs", "--allow-markers", "--detail"}));
        assertEquals(new ServiceMain.Args(null, List.of(), false, false, "0.0.0.0", 8080),
                ServiceMain.parsePlaygroundArgs(new String[]{"--host", "0.0.0.0"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceMain.parsePlaygroundArgs(new String[]{"d.service.json"}));
        // --detail and --allow-markers set the service, which a playground without a descriptor does not have
        assertThrows(IllegalArgumentException.class, () -> ServiceMain.parsePlaygroundArgs(new String[]{"--detail"}));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceMain.parsePlaygroundArgs(new String[]{"--allow-markers"}));
    }

    @Test
    void it_analyses_a_source() throws Exception {
        HttpResponse<String> r = post("/playground/analyse", SOURCE);
        assertEquals(200, r.statusCode(), r.body());
        JsonNode answer = ServiceHttp.MAPPER.readTree(r.body());
        assertTrue(answer.get("executable").asBoolean(), r.body());
        assertEquals("PASS", answer.at("/scenario/results/0/status").asText(), r.body());
    }

    @Test
    void it_offers_no_comparison_with_a_service_and_says_why() throws Exception {
        HttpResponse<String> r = post("/playground/analyse?via=service", SOURCE);
        assertEquals(400, r.statusCode(), r.body());
        JsonNode violation = ServiceHttp.MAPPER.readTree(r.body()).at("/violations/0");
        assertEquals("via", violation.get("field").asText(), r.body());
        assertEquals(ServiceHttp.NO_SERVICE, violation.get("error").asText(), r.body());
        HttpResponse<String> page = get("/playground");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<span class=\"hint\" id=\"via\">" + ServiceHttp.NO_SERVICE + "</span>"),
                page.body());
    }

    @Test
    void it_has_no_routes_of_a_service() throws Exception {
        assertEquals(404, get("/info").statusCode());
        assertEquals(404, get("/openapi").statusCode());
    }

    /** The page the playground beside a service sends as it is keeps the place empty: it has a service. */
    @Test
    void the_page_beside_a_service_keeps_the_place_empty() throws Exception {
        try (var in = ServiceHttp.class.getResourceAsStream("playground.html")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains(ServiceHttp.VIA_PLACE));
        }
    }

    // ---------------- the commands in a JVM of their own, with their environment ----------------

    private static final String MODEL = """
            Objecttype de Vlucht (mv: Vluchten)
                de afstand Numeriek (geheel getal);
                de heffing Numeriek (geheel getal);

            Regel heffing
            geldig altijd
            De heffing van een Vlucht moet berekend worden als de afstand van de Vlucht maal 2.
            """;
    private static final String MODEL_SCENARIO = """

            Scenario een vlucht
            Gegeven is de volgende situatie:
            een Vlucht V
            - de afstand is 5
            Met als verwacht resultaat:
            - de heffing van V is 10
            """;
    private static final String MODEL_DESCRIPTOR = """
            { "service": {"name":"vlucht","version":"1"},
              "entrypoints": [ { "name":"heffing","path":"heffing",
                "scope": {"ruleNames": ["heffing"]},
                "input": ["Vlucht"], "output": ["Vlucht.heffing"] } ],
              "parameters": {} }""";

    /** {@code serve} binds no playground route, also with {@code PLAYGROUND=true} in its environment: the production
     *  service carries no development route behind a switch. */
    @Test
    @Timeout(180)
    void serve_binds_no_playground_whatever_the_environment(@TempDir Path dir) throws Exception {
        Path descriptor = Files.writeString(dir.resolve("vlucht.service.json"), MODEL_DESCRIPTOR);
        Path source = Files.writeString(dir.resolve("vlucht.rgs"), MODEL);
        try (Launched serve = Launched.of(Map.of("PLAYGROUND", "true"), descriptor.toString(), source.toString(),
                "--host", "127.0.0.1", "--port", "0")) {
            assertEquals(200, serve.get("/info").statusCode());
            assertEquals(404, serve.get("/playground").statusCode());
            assertEquals(404, serve.post("/playground/analyse", MODEL + MODEL_SCENARIO).statusCode());
        }
    }

    /** {@code playground} with a descriptor and its source serves the playground and that model's entry points in one
     *  process, so {@code ?via=service} computes the scenario through the served model. */
    @Test
    @Timeout(180)
    void playground_with_a_descriptor_answers_via_service(@TempDir Path dir) throws Exception {
        Path descriptor = Files.writeString(dir.resolve("vlucht.service.json"), MODEL_DESCRIPTOR);
        Path source = Files.writeString(dir.resolve("vlucht.rgs"), MODEL);
        try (Launched playground = Launched.of(Map.of(), "--playground", descriptor.toString(), source.toString(),
                "--detail", "--host", "127.0.0.1", "--port", "0")) {
            HttpResponse<String> r = playground.post("/playground/analyse?via=service", MODEL + MODEL_SCENARIO);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode result = ServiceHttp.MAPPER.readTree(r.body()).at("/scenario/results/0");
            assertEquals("PASS", result.path("status").asText(), r.body());
            assertEquals("heffing", result.path("path").asText(), r.body());
            HttpResponse<String> answer = playground.post("/heffing",
                    "{\"instances\":[{\"type\":\"Vlucht\",\"id\":\"V\",\"attributes\":{\"afstand\":5}}]}");
            assertEquals(200, answer.statusCode(), answer.body());
            assertEquals(10, ServiceHttp.MAPPER.readTree(answer.body()).at("/instances/0/attributes/heffing").asInt(),
                    answer.body());
            assertEquals(200, playground.get("/info").statusCode());
            HttpResponse<String> page = playground.get("/playground");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains(ServiceHttp.VIA_PLACE), "met een dienst blijft de plaats leeg");
        }
    }

    /** The playground compiles whatever source a page posts: without {@code --host} it binds the loopback address
     *  alone, with and without a descriptor, whose entry points share its address; {@code --host 0.0.0.0} binds every
     *  interface. */
    @Test
    @Timeout(180)
    void the_playground_binds_the_loopback_address_unless_host_names_another(@TempDir Path dir) throws Exception {
        Path descriptor = Files.writeString(dir.resolve("vlucht.service.json"), MODEL_DESCRIPTOR);
        Path source = Files.writeString(dir.resolve("vlucht.rgs"), MODEL);
        ServiceMain.Args beside = ServiceMain.parsePlaygroundArgs(new String[]{
                descriptor.toString(), source.toString(), "--port", "0"});
        DecisionCore core = ServiceMain.assemble(beside);

        InetAddress alone = boundAddress(null, ServiceMain.parsePlaygroundArgs(new String[]{"--port", "0"}));
        assertTrue(alone.isLoopbackAddress(), "playground zonder descriptor bindt " + alone);
        InetAddress withService = boundAddress(core, beside);
        assertTrue(withService.isLoopbackAddress(), "playground met descriptor bindt " + withService);

        InetAddress openAlone = boundAddress(null, ServiceMain.parsePlaygroundArgs(new String[]{
                "--host", "0.0.0.0", "--port", "0"}));
        assertTrue(openAlone.isAnyLocalAddress(), "playground --host 0.0.0.0 bindt " + openAlone);
        InetAddress openWithService = boundAddress(core, ServiceMain.parsePlaygroundArgs(new String[]{
                descriptor.toString(), source.toString(), "--host", "0.0.0.0", "--port", "0"}));
        assertTrue(openWithService.isAnyLocalAddress(), "playground met descriptor --host 0.0.0.0 bindt "
                + openWithService);
    }

    /** {@code serve} binds every interface without {@code --host}: the container and the chart reach it there. */
    @Test
    @Timeout(180)
    void serve_binds_every_interface_by_default(@TempDir Path dir) throws Exception {
        Path descriptor = Files.writeString(dir.resolve("vlucht.service.json"), MODEL_DESCRIPTOR);
        Path source = Files.writeString(dir.resolve("vlucht.rgs"), MODEL);
        ServiceMain.Args args = ServiceMain.parseArgs(new String[]{descriptor.toString(), source.toString(),
                "--port", "0"});
        ServiceHttp service = ServiceMain.startService(ServiceMain.assemble(args), args);
        try {
            assertTrue(service.address().getAddress().isAnyLocalAddress(), "serve bindt " + service.address());
        } finally {
            service.stop(0);
        }
    }

    /** The address {@link ServiceMain#startPlayground} binds for {@code core} and {@code args}; stopped again. */
    private static InetAddress boundAddress(DecisionCore core, ServiceMain.Args args) throws IOException {
        ServiceHttp started = ServiceMain.startPlayground(core, args);
        try {
            return started.address().getAddress();
        } finally {
            started.stop(0);
        }
    }

    /** {@code ServiceMain} with {@code args} in a JVM of its own, the variables of {@code env} added to the
     *  environment this one has without {@code PLAYGROUND} and {@code DETAIL}; started once it printed the port it
     *  listens on, stopped on close. */
    private record Launched(Process process, int port) implements AutoCloseable {

        static Launched of(Map<String, String> env, String... args) throws Exception {
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), ServiceMain.class.getName()));
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().remove("PLAYGROUND");
            builder.environment().remove("DETAIL");
            builder.environment().putAll(env);
            Process process = builder.start();
            CompletableFuture<Integer> port = new CompletableFuture<>();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                Pattern listening = Pattern.compile("luistert op poort (\\d+):");
                try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = out.readLine()) != null; ) {
                        synchronized (output) { output.append(line).append('\n'); }
                        Matcher m = listening.matcher(line);
                        if (m.find()) port.complete(Integer.parseInt(m.group(1)));
                    }
                } catch (IOException e) {
                    port.completeExceptionally(e);
                }
                synchronized (output) {
                    port.completeExceptionally(new AssertionError("ServiceMain " + String.join(" ", args)
                            + " luistert niet; uitvoer:\n" + output));
                }
            });
            reader.setDaemon(true);
            reader.start();
            try {
                return new Launched(process, port.get(150, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                process.destroyForcibly();
                throw (Exception) e.getCause();
            } catch (TimeoutException e) {
                process.destroyForcibly();
                throw new AssertionError("ServiceMain " + String.join(" ", args) + " luistert na 150 s nog niet");
            }
        }

        HttpResponse<String> get(String path) throws Exception {
            return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .header("Content-Type", "text/plain; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + playground.port() + path))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + playground.port() + path)).GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
