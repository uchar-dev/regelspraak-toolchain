package io.github.yhavinga.regelspraak.service.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.model.Parameterset.WaardeInterval;
import io.github.yhavinga.regelspraak.service.BeslisserKern;
import io.github.yhavinga.regelspraak.service.DienstDescriptor;
import io.github.yhavinga.regelspraak.service.DienstFout;
import io.github.yhavinga.regelspraak.service.Schending;
import io.github.yhavinga.regelspraak.transpiler.kern.Dekking;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The THIN HTTP adapter over the framework-agnostic {@link BeslisserKern}. It owns no RegelSpraak meaning: it
 * only marshals JSON in/out and maps the core's typed outcomes onto HTTP status codes. A host-side Quarkus wrap
 * later can reuse the very same {@link BeslisserKern} unchanged — this class is disposable plumbing.
 *
 * <h2>Routes</h2>
 * <ul>
 *   <li>{@code POST /<pad>} for every entry point: the body is read as JSON (BigDecimal-for-floats so no request
 *       number is ever a double), handed to {@link BeslisserKern#verwerk}, and the response tree is written back
 *       with {@code 200} and {@code Content-Type: application/json; charset=UTF-8}.</li>
 *   <li>{@code GET /info}: the service manifest — dienst identity, one entry per entry point carrying its path,
 *       its per-regel {@code dekking} verdicts and its {@code markers}, the list of ALL {@code overgeslagen}
 *       (marker) rule names across the service, and the descriptor {@code parameters} defaults verbatim.</li>
 * </ul>
 *
 * <h2>Errors — always a JSON body, NEVER a leaked stacktrace</h2>
 * <ul>
 *   <li>{@link DienstFout} from {@code verwerk} → {@code 400} {@code {"fout": <boodschap>, "schendingen":
 *       [{"veld": <pad>, "fout": <detail>}, ...]}} — every schending names its {@code veld}.</li>
 *   <li>malformed / empty request body JSON → {@code 400} {@code {"fout": "...", "veld": "body"}}.</li>
 *   <li>{@link BeslisserKern.OnbekendPad} → {@code 404} naming the pad.</li>
 *   <li>wrong method on a known path → {@code 405} naming the method.</li>
 *   <li>ANY other exception → {@code 500} {@code {"fout": "interne fout"}} — never a stacktrace or an exception
 *       message in the body; the stack is logged to {@code stderr} server-side.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * The server executor is {@link Executors#newVirtualThreadPerTaskExecutor()} (Java 21 virtual threads). The core
 * is thread-safe BY CONSTRUCTION — each entry point's {@link org.kie.api.KieBase} is built once at startup and
 * immutable thereafter, and {@code verwerk} opens a fresh {@code KieSession} per request over the shared,
 * read-only bean classloader — so an unbounded virtual-thread-per-request executor is safe. G7 (50 concurrent
 * POSTs, no cross-session fact leakage) lands in fase 6; this adapter is already ready for it.
 */
public final class DienstHttp {

    /** BigDecimal-for-floats: a request/response number is never a double (the exact-render discipline). */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);
    private static final String JSON = "application/json; charset=UTF-8";

    private final BeslisserKern kern;
    private final Verwerker verwerker;
    private final HttpServer server;

    private DienstHttp(BeslisserKern kern, Verwerker verwerker, HttpServer server) {
        this.kern = kern;
        this.verwerker = verwerker;
        this.server = server;
    }

    /**
     * Bind an HTTP server for {@code kern} on {@code host}:{@code poort} (poort 0 = an ephemeral OS-assigned
     * port, ideal for tests) and register the routes. Call {@link #start()} to begin serving.
     */
    public static DienstHttp bind(BeslisserKern kern, String host, int poort) throws IOException {
        return bindMet(kern, kern::verwerk, host, poort);
    }

    /**
     * Test seam: bind with an EXPLICIT verwerk delegate over the real kern's routes, so a test can inject a spy
     * that throws to exercise the 500-never-leaks path. Production ({@link #bind(BeslisserKern, String, int)})
     * uses {@code kern::verwerk}. The routes still come from the real kern (so /info and the path set are
     * unchanged); only the POST handling is delegated.
     */
    static DienstHttp bindMet(BeslisserKern kern, Verwerker verwerker, String host, int poort) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, poort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        DienstHttp dienst = new DienstHttp(kern, verwerker, server);
        // one POST route per entry point path, plus /info
        for (String pad : kern.entrypoints().keySet())
            server.createContext("/" + pad, dienst::verwerkPost);
        server.createContext("/info", dienst::verwerkInfo);
        // catch-all: any path not matched by a more-specific context (longest-prefix wins) -> JSON 404 naming it
        server.createContext("/", dienst::verwerkOnbekend);
        return dienst;
    }

    /** The POST-handling delegate — {@code (pad, boom) -> ObjectNode}, satisfied by {@code kern::verwerk}. */
    @FunctionalInterface
    interface Verwerker {
        ObjectNode verwerk(String pad, JsonNode verzoekBoom);
    }

    public DienstHttp start() { server.start(); return this; }

    /** Stop the server, allowing at most {@code delaySeconds} for in-flight exchanges to finish. */
    public void stop(int delaySeconds) { server.stop(delaySeconds); }

    /** The actually-bound port (resolved even when bound with poort 0). */
    public int poort() { return server.getAddress().getPort(); }

    // ---------------- POST /<pad> ----------------

    private void verwerkPost(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                stuur(ex, 405, foutBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '" + ex.getRequestURI().getPath() + "' (gebruik POST)", null));
                return;
            }
            String pad = ex.getRequestURI().getPath();
            if (pad.startsWith("/")) pad = pad.substring(1);

            byte[] body = ex.getRequestBody().readAllBytes();
            JsonNode boom;
            if (body.length == 0) {
                stuur(ex, 400, foutBody("leeg verzoek-body: verwacht JSON met een 'instanties'-array", "body"));
                return;
            }
            try {
                boom = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
            } catch (JsonProcessingException malformed) {
                stuur(ex, 400, foutBody("verzoek-body is geen geldige JSON: " + malformed.getOriginalMessage(),
                        "body"));
                return;
            }
            if (boom == null || boom.isNull()) {
                stuur(ex, 400, foutBody("verzoek-body is geen geldige JSON (null)", "body"));
                return;
            }

            ObjectNode antwoord = verwerker.verwerk(pad, boom);
            stuur(ex, 200, antwoord);
        } catch (BeslisserKern.OnbekendPad onbekend) {
            stuur(ex, 404, foutBody(onbekend.getMessage(), null));
        } catch (DienstFout dienstFout) {
            stuur(ex, 400, verzoekFoutBody(dienstFout));
        } catch (Exception intern) {
            // NEVER leak a stacktrace or the exception message to the client; log it server-side.
            intern.printStackTrace(System.err);
            stuur(ex, 500, foutBody("interne fout", null));
        }
    }

    // ---------------- GET /info ----------------

    private void verwerkInfo(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                stuur(ex, 405, foutBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '/info' (gebruik GET)", null));
                return;
            }
            stuur(ex, 200, infoBody());
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            stuur(ex, 500, foutBody("interne fout", null));
        }
    }

    // ---------------- catch-all (unknown path) ----------------

    /** Any path not served by a more-specific context → {@code 404} JSON naming the pad. */
    private void verwerkOnbekend(HttpExchange ex) throws IOException {
        try {
            String pad = ex.getRequestURI().getPath();
            stuur(ex, 404, foutBody("onbekend pad: '" + pad + "'", null));
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            stuur(ex, 500, foutBody("interne fout", null));
        }
    }

    /** Build the {@code /info} manifest from the kern's descriptor + compiled entry points. */
    ObjectNode infoBody() {
        DienstDescriptor descriptor = kern.descriptor();
        ObjectNode root = NF.objectNode();

        ObjectNode dienst = root.putObject("dienst");
        if (descriptor.dienst() != null) {
            dienst.put("naam", descriptor.dienst().naam());
            dienst.put("versie", descriptor.dienst().versie());
        }

        ArrayNode entrypoints = root.putArray("entrypoints");
        ArrayNode overgeslagen = NF.arrayNode();   // ALL marker rule names across the service
        for (BeslisserKern.Entrypoint ep : kern.entrypoints().values()) {
            ObjectNode eNode = entrypoints.addObject();
            eNode.put("naam", ep.descriptor().naam());
            eNode.put("pad", ep.descriptor().pad());

            ArrayNode dekking = eNode.putArray("dekking");
            for (Dekking d : ep.dekking()) {
                ObjectNode dn = dekking.addObject();
                dn.put("soort", d.soort());
                dn.put("naam", d.naam());
                dn.put("verdict", d.verdict().name());
                if (d.reden() != null && !d.reden().isEmpty()) dn.put("reden", d.reden());
            }

            ArrayNode markers = eNode.putArray("markers");
            for (String m : ep.markers()) {
                markers.add(m);
                overgeslagen.add(m);
            }
        }
        root.set("entrypoints", entrypoints);
        root.set("overgeslagen", overgeslagen);

        // parameters: the descriptor defaults, verbatim JSON (raw JsonNode values, never re-rendered as double)
        ObjectNode parameters = root.putObject("parameters");
        Map<String, JsonNode> params = descriptor.parameters();
        if (params != null)
            for (var e : params.entrySet()) parameters.set(e.getKey(), e.getValue());

        // parametersets (fase 5): each loaded set with its per-parameter validity ranges + the versioned value.
        ArrayNode parametersets = root.putArray("parametersets");
        for (Parameterset set : kern.parametersets()) {
            ObjectNode sn = parametersets.addObject();
            sn.put("naam", set.naam());
            ObjectNode waarden = sn.putObject("waarden");
            for (var e : set.waarden().entrySet()) {
                ArrayNode intervallen = waarden.putArray(e.getKey());
                for (WaardeInterval wi : e.getValue()) {
                    ObjectNode iv = intervallen.addObject();
                    Geldigheid g = wi.geldigheid();
                    if (g.vanaf() != null) iv.put("vanaf", g.vanaf().toString()); else iv.putNull("vanaf");
                    if (g.tot() != null) iv.put("tot", g.tot().toString()); else iv.putNull("tot");
                    iv.set("waarde", rendWaarde(wi.waarde()));
                }
            }
        }

        // periodeWaarschuwingen (gate G3): the rendered regel×parameter period-coverage WARNING lines.
        ArrayNode waarschuwingen = root.putArray("periodeWaarschuwingen");
        for (String w : kern.periodeWaarschuwingen()) waarschuwingen.add(w);

        return root;
    }

    /** Render a parameterset value as an EXACT JSON node for /info (WaardeCoder-symmetric with
     *  {@code AntwoordBouwer.rend}): BigDecimal as a number (never a double), date/datetime as an ISO string,
     *  boolean as boolean, tekst/enum as string, explicit leegwaarde as JSON null. */
    private JsonNode rendWaarde(Object v) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.numberNode(b);
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof String s) return NF.textNode(s);
        return NF.textNode(v.toString());
    }

    // ---------------- error bodies ----------------

    /** {@code {"fout": <boodschap>, "veld"?: <veld>}}. */
    static ObjectNode foutBody(String boodschap, String veld) {
        ObjectNode n = NF.objectNode();
        n.put("fout", boodschap);
        if (veld != null) n.put("veld", veld);
        return n;
    }

    /** {@code {"fout": <boodschap>, "schendingen": [{"veld": <pad>, "fout": <detail>}, ...]}} — every schending
     *  NAMES its veld. Built from a request-mapping {@link DienstFout}. */
    static ObjectNode verzoekFoutBody(DienstFout fout) {
        ObjectNode n = NF.objectNode();
        // the constructed message already concatenates the schendingen; use its context head only would lose the
        // count, so keep the full message — it names nothing secret (all service-internal diagnostics).
        n.put("fout", "verzoek valideert niet");
        ArrayNode schendingen = n.putArray("schendingen");
        for (Schending s : fout.schendingen()) {
            ObjectNode sn = schendingen.addObject();
            sn.put("veld", s.veld());
            sn.put("fout", s.bericht());
        }
        return n;
    }

    // ---------------- write ----------------

    private void stuur(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] uit = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", JSON);
        ex.sendResponseHeaders(status, uit.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(uit);
        }
    }
}
