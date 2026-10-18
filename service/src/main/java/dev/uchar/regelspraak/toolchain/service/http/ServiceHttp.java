package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.uchar.regelspraak.ir.Geldigheid;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.model.Parameterset.WaardeInterval;
import dev.uchar.regelspraak.toolchain.service.AnswerBuilder;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.EvaluationFault;
import dev.uchar.regelspraak.toolchain.service.NoFixpoint;
import dev.uchar.regelspraak.toolchain.service.Violation;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;
import dev.uchar.regelspraak.toolchain.service.Completeness;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.kern.Dekking;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The THIN HTTP adapter over the framework-agnostic {@link DecisionCore}. It owns no RegelSpraak meaning: it
 * only marshals JSON in/out and maps the core's typed outcomes onto HTTP status codes. A host-side Quarkus wrap
 * later can reuse the very same {@link DecisionCore} unchanged — this class is disposable plumbing.
 *
 * <h2>Routes</h2>
 * <ul>
 *   <li>{@code POST /<pad>} for every entry point: the body is read as JSON (BigDecimal-for-floats so no request
 *       number is ever a double), handed to {@link DecisionCore#process}, and the response tree is written back
 *       with {@code 200} and {@code Content-Type: application/json; charset=UTF-8}. The query carries what the
 *       request asks beside its body ({@link #options}): {@code uitleg}, {@code vocabulaire} (the vocabulary of the
 *       answer), {@code invoervocabulaire} (the one the request's names are read in), and the detail flags
 *       {@code trace}, {@code dekking} and {@code fixpunt}, which a core started with detail answers
 *       ({@link dev.uchar.regelspraak.toolchain.service.CoreConfig#detail()}) and any other refuses with a 400.</li>
 *   <li>{@code GET /info}: the service manifest — dienst identity, one entry per entry point carrying its path,
 *       whether it is {@code uitvoerbaar}, its {@code dekking} (the exec-model branch's verdict per form of its
 *       document, the defaults groups included), its {@code markers} and, when uchar does not carry it, uchar's
 *       sentence as {@code weigering}; the list of ALL {@code skipped} (marker) names across the service, and
 *       the descriptor {@code parameters} defaults verbatim. A model with vocabularies adds {@code vocabulaires}
 *       ({@link Completeness}), and an entry point with a default vocabulary names it.</li>
 * </ul>
 *
 * <h2>Errors — always a JSON body, NEVER a leaked stacktrace</h2>
 * <ul>
 *   <li>{@link ServiceError} from {@code process} → {@code 400} {@code {"fout": <boodschap>, "schendingen":
 *       [{"veld": <pad>, "fout": <detail>}, ...]}} — every schending names its {@code field}.</li>
 *   <li>malformed / empty request body JSON → {@code 400} {@code {"fout": "...", "veld": "body"}}.</li>
 *   <li>{@link DecisionCore.UnknownPath} → {@code 404} naming the pad.</li>
 *   <li>{@link DecisionCore.NonExecutable} → {@code 503}, the MARKER answer: {@code {"status": "MARKER",
 *       "fout": …, "reden": <uchar's sentence>, "markers": […]}}. An entry point uchar does not carry has no
 *       KieBase, and an answer without the construct would be an answer to another rule set.</li>
 *   <li>{@link EvaluationFault} → {@code 422}: the rules give an evaluation fault on this input, which stands at the
 *       fixpoint ({@link AnswerBuilder#evaluationFault}): {@code {"status": "EVALUATIEFOUT", "fout": …, "fouten":
 *       [{"soort": …, "fout": <uchar's sentence>}, …]}}.</li>
 *   <li>{@link NoFixpoint} → {@code 422}: the session of the request reached no fixpoint, at uchar's fire cap or its
 *       creation budget ({@link AnswerBuilder#noFixpoint}): {@code {"status": "GEEN_FIXPUNT", "fout": <uchar's
 *       sentence>}}.</li>
 *   <li>wrong method on a known path → {@code 405} naming the method.</li>
 *   <li>ANY other exception → {@code 500} {@code {"fout": "interne fout"}} — never a stacktrace or an exception
 *       message in the body; the stack is logged to {@code stderr} server-side.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * The server executor is {@link Executors#newVirtualThreadPerTaskExecutor()} (Java 21 virtual threads). The core
 * is thread-safe BY CONSTRUCTION — each entry point's {@link org.kie.api.KieBase} is built once at startup and
 * immutable thereafter, and {@code process} opens a fresh {@code KieSession} per request over the entry point's
 * read-only bean classloader — so an unbounded virtual-thread-per-request executor is safe. G7 (50 concurrent
 * POSTs, no cross-session fact leakage) lands in fase 6; this adapter is already ready for it.
 */
public final class ServiceHttp {

    /** BigDecimal-for-floats: a request/response number is never a double (the exact-render discipline). */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);
    private static final String JSON = "application/json; charset=UTF-8";

    private final DecisionCore core;
    private final Processor processor;
    private final HttpServer server;

    private ServiceHttp(DecisionCore core, Processor processor, HttpServer server) {
        this.core = core;
        this.processor = processor;
        this.server = server;
    }

    /**
     * Bind an HTTP server for {@code core} on {@code host}:{@code port} (poort 0 = an ephemeral OS-assigned
     * port, ideal for tests) and register the routes. Call {@link #start()} to begin serving.
     */
    public static ServiceHttp bind(DecisionCore core, String host, int port) throws IOException {
        return bindWith(core, core::process, host, port);
    }

    /**
     * Test seam: bind with an EXPLICIT verwerk delegate over the real kern's routes, so a test can inject a spy
     * that throws to exercise the 500-never-leaks path. Production ({@link #bind(DecisionCore, String, int)})
     * uses {@code core::process}. The routes still come from the real kern (so /info and the path set are
     * unchanged); only the POST handling is delegated.
     */
    static ServiceHttp bindWith(DecisionCore core, Processor processor, String host, int port) throws IOException {
        return bindWith(core, processor, host, port, playgroundEnabled());
    }

    /**
     * Test seam: bind with an EXPLICIT {@code playground} flag so a test can force the dev-workbench routes on
     * without setting a process env var. Production always derives the flag from {@link #playgroundEnabled()}.
     */
    static ServiceHttp bindWith(DecisionCore core, Processor processor, String host, int port, boolean playground)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        ServiceHttp service = new ServiceHttp(core, processor, server);
        // one POST route per entry point path, plus /info
        for (String path : core.entrypoints().keySet())
            server.createContext("/" + path, service::processPost);
        server.createContext("/info", service::processInfo);
        // DEV-ONLY playground: register the workbench routes ONLY when PLAYGROUND=true (default off). A production
        // deploy leaves them entirely unbound — the paths then 404 via the catch-all below.
        if (playground) {
            server.createContext("/playground/analyse", service::processPlaygroundAnalysis);
            server.createContext("/playground", service::processPlaygroundPage);
        }
        // catch-all: any path not matched by a more-specific context (longest-prefix wins) -> JSON 404 naming it
        server.createContext("/", service::processUnknown);
        return service;
    }

    /** The POST-handling delegate — {@code (pad, boom) -> ObjectNode}, satisfied by {@code core::process}. The
     *  {@code ?uitleg=true} path does NOT go through this delegate: it calls {@link DecisionCore#process} with the
     *  uitleg flag directly (the delegate is the 500-never-leaks test seam, uitleg-independent). */
    @FunctionalInterface
    interface Processor {
        ObjectNode process(String path, JsonNode requestTree);
    }

    public ServiceHttp start() { server.start(); return this; }

    /** Stop the server, allowing at most {@code delaySeconds} for in-flight exchanges to finish. */
    public void stop(int delaySeconds) { server.stop(delaySeconds); }

    /** The actually-bound port (resolved even when bound with poort 0). */
    public int port() { return server.getAddress().getPort(); }

    // ---------------- POST /<pad> ----------------

    private void processPost(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, errorBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '" + ex.getRequestURI().getPath() + "' (gebruik POST)", null));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/")) path = path.substring(1);

            byte[] body = ex.getRequestBody().readAllBytes();
            JsonNode tree;
            if (body.length == 0) {
                send(ex, 400, errorBody("leeg verzoek-body: verwacht JSON met een 'instanties'-array", "body"));
                return;
            }
            try {
                tree = MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
            } catch (JsonProcessingException malformed) {
                send(ex, 400, errorBody("verzoek-body is geen geldige JSON: " + malformed.getOriginalMessage(),
                        "body"));
                return;
            }
            if (tree == null || tree.isNull()) {
                send(ex, 400, errorBody("verzoek-body is geen geldige JSON (null)", "body"));
                return;
            }

            // a request that asks something beside its body bypasses the test-seam delegate and asks the core itself;
            // the plain path stays byte-identical through the delegate (core::process in production).
            RequestOptions options = options(ex.getRequestURI().getRawQuery());
            ObjectNode answer = options.equals(RequestOptions.DEFAULT)
                    ? processor.process(path, tree)
                    : core.process(path, tree, options);
            send(ex, 200, answer);
        } catch (DecisionCore.UnknownPath unknown) {
            send(ex, 404, errorBody(unknown.getMessage(), null));
        } catch (DecisionCore.NonExecutable rejected) {
            send(ex, 503, markerBody(rejected));
        } catch (ServiceError serviceError) {
            send(ex, 400, requestErrorBody(serviceError));
        } catch (EvaluationFault evaluationFault) {
            send(ex, 422, AnswerBuilder.evaluationFault(evaluationFault));
        } catch (NoFixpoint none) {
            send(ex, 422, AnswerBuilder.noFixpoint(none));
        } catch (Exception intern) {
            // NEVER leak a stacktrace or the exception message to the client; log it server-side.
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    /** Whether the query string opts into the uitleg field: {@code uitleg=true} (case-insensitive) or a bare
     *  {@code uitleg}. Any other value (incl. {@code uitleg=false}) means no — the response is byte-identical to
     *  a request without the flag. Null/empty query → false. Public so a test can pin the parse (the Quarkus
     *  adapter carries the equivalent inline). */
    public static boolean requiresUitleg(String rawQuery) {
        return isRequested(rawQuery, "uitleg");
    }

    /** Whether the query string sets the flag {@code naam} as {@link #requiresUitleg} reads {@code uitleg}: the first
     *  parameter of that name (compared case-insensitively) with the value {@code true} (case-insensitive), or bare. */
    public static boolean isRequested(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) return false;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (!name.equalsIgnoreCase(key)) continue;
            String value = eq < 0 ? "true" : part.substring(eq + 1);
            return "true".equalsIgnoreCase(value) || value.isEmpty();
        }
        return false;
    }

    /** What the query of a request asks beside its body: {@link #requiresUitleg}, the values of {@code vocabulaire} and
     *  {@code invoervocabulaire} ({@link #queryValue}), and the detail flags {@code trace}, {@code dekking} and
     *  {@code fixpunt} ({@link #isRequested}). The Quarkus adapter reads the same six parameters. */
    public static RequestOptions options(String rawQuery) {
        return new RequestOptions(requiresUitleg(rawQuery), queryValue(rawQuery, "vocabulaire"),
                queryValue(rawQuery, "invoervocabulaire"), isRequested(rawQuery, "trace"), isRequested(rawQuery, "dekking"),
                isRequested(rawQuery, "fixpunt"));
    }

    /** The decoded value of the first query parameter {@code naam} (its name compared case-insensitively, as
     *  {@code uitleg}), the empty text for a bare {@code naam}, or null when the query does not carry it. */
    public static String queryValue(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            if (!name.equalsIgnoreCase(key)) continue;
            return eq < 0 ? "" : java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    // ---------------- GET /info ----------------

    private void processInfo(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, errorBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '/info' (gebruik GET)", null));
                return;
            }
            send(ex, 200, infoBody());
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    // ---------------- catch-all (unknown path) ----------------

    /** Any path not served by a more-specific context → {@code 404} JSON naming the pad. */
    private void processUnknown(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            send(ex, 404, errorBody("onbekend pad: '" + path + "'", null));
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    // ---------------- DEV-ONLY playground (env PLAYGROUND=true) ----------------

    /** Per-request compile budget for the playground (ms). A learner's snippet is tiny; a runaway analysis (e.g. a
     *  pathological grammar input) is capped so the dev workbench never wedges a thread indefinitely. */
    static final long PLAYGROUND_TIMEOUT_MS = 15_000;

    /** The workbench is bound ONLY when env {@code PLAYGROUND=true} — a dev switch, default OFF, so a production
     *  service never exposes the compile-per-request surface. Read once at bind time, never persisted. */
    static boolean playgroundEnabled() {
        return "true".equalsIgnoreCase(System.getenv("PLAYGROUND"));
    }

    /** {@code POST /playground/analyse}: the request body is the raw RegelSpraak source (text/plain). It is analyzed
     *  compile-per-request under a timeout with NO persistence, and the {@link Playground} answer tree is written
     *  back as JSON. Errors at the RegelSpraak level (parse/resolve/scenario) are part of that tree, not HTTP
     *  errors; only a timeout (503) or an unexpected internal fault (500) map to a status.
     *
     *  <p>{@code ?via=dienst} has this service's core compute the scenarios ({@link Playground#analyseer(String,
     *  String, DecisionCore)}). It shows the session of each, so a core started without detail answers it with a 400
     *  that names {@code DETAIL}, before any analysis; a source that is not the service's own is a 400 as well.
     *  Another value of {@code via} is a 400. */
    private void processPlaygroundAnalysis(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, errorBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '/playground/analyse' (gebruik POST)", null));
                return;
            }
            String source = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String vocabulary = queryValue(ex.getRequestURI().getRawQuery(), "vocabulaire");
            String via = queryValue(ex.getRequestURI().getRawQuery(), "via");
            if (via != null && !"dienst".equalsIgnoreCase(via)) {
                send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(Violation.van(
                        Violation.Kind.MALFORMED, "via", "via kent alleen de waarde 'dienst', niet '" + via + "'")))));
                return;
            }
            if (via != null && !core.detail()) {
                send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(Violation.van(
                        Violation.Kind.NIET_ONDERSTEUND, "via", "de dienst is zonder DETAIL gestart en rekent geen"
                                + " scenario's via de dienst; start hem met DETAIL=true of --detail")))));
                return;
            }
            DecisionCore service = via == null ? null : core;
            // Compile-per-request under a hard timeout, on a throwaway single-use executor (no shared state).
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<ObjectNode> f = exec.submit(() -> Playground.analyseer(source, vocabulary, service));
                send(ex, 200, f.get(PLAYGROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                send(ex, 503, errorBody("analyse overschreed de tijdslimiet ("
                        + PLAYGROUND_TIMEOUT_MS + " ms)", null));
            } catch (java.util.concurrent.ExecutionException ee) {
                if (ee.getCause() instanceof ServiceError df) {
                    // via=dienst on a source that is not the service's own
                    send(ex, 400, requestErrorBody(df));
                    return;
                }
                if (ee.getCause() instanceof Naming.VocabularyError vf) {
                    // the source's model declares no such vocabulary: the request asked what cannot be answered
                    send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(
                            Violation.van(Violation.Kind.ONBEKEND_VOCABULAIRE, "vocabulaire", vf.getMessage())))));
                    return;
                }
                ee.getCause().printStackTrace(System.err);
                send(ex, 500, errorBody("interne fout", null));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                send(ex, 500, errorBody("interne fout", null));
            } finally {
                exec.shutdownNow();
            }
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    /** {@code GET /playground}: serve the one self-contained HTML page (a classpath resource, no build tooling). */
    private void processPlaygroundPage(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, errorBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '/playground' (gebruik GET)", null));
                return;
            }
            byte[] html;
            try (InputStream in = ServiceHttp.class.getResourceAsStream("playground.html")) {
                if (in == null) { send(ex, 500, errorBody("playground.html niet gevonden", null)); return; }
                html = in.readAllBytes();
            }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            // Lock the dev workbench to its own inline JS/CSS plus the pinned, SRI-checked Mermaid CDN origin, so a
            // compromised or tampered CDN path cannot inject anything the page does not already allow.
            ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'unsafe-inline' https://cdn.jsdelivr.net; "
                + "style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");
            ex.sendResponseHeaders(200, html.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(html); }
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    /** Build the {@code /info} manifest from the kern's descriptor + compiled entry points. */
    ObjectNode infoBody() {
        ServiceDescriptor descriptor = core.descriptor();
        ObjectNode root = NF.objectNode();

        ObjectNode service = root.putObject("dienst");
        if (descriptor.service() != null) {
            service.put("naam", descriptor.service().name());
            service.put("versie", descriptor.service().version());
        }

        ArrayNode entrypoints = root.putArray("entrypoints");
        ArrayNode skipped = NF.arrayNode();   // ALL marker rule names across the service
        for (DecisionCore.Entrypoint ep : core.entrypoints().values()) {
            ObjectNode eNode = entrypoints.addObject();
            eNode.put("naam", ep.descriptor().name());
            eNode.put("pad", ep.descriptor().path());
            if (ep.descriptor().vocabulary() != null) eNode.put("vocabulaire", ep.descriptor().vocabulary());
            eNode.put("uitvoerbaar", ep.executable());
            if (!ep.executable()) eNode.put("weigering", ep.refusal());

            ArrayNode coverage = eNode.putArray("dekking");
            for (Dekking d : ep.coverage()) {
                ObjectNode dn = coverage.addObject();
                dn.put("soort", d.soort());
                dn.put("naam", d.naam());
                dn.put("verdict", d.verdict().name());
                if (d.reden() != null && !d.reden().isEmpty()) dn.put("reden", d.reden());
            }

            ArrayNode markers = eNode.putArray("markers");
            for (String m : ep.markers()) {
                markers.add(m);
                skipped.add(m);
            }
        }
        root.set("entrypoints", entrypoints);
        root.set("overgeslagen", skipped);

        // parameters: the descriptor defaults, verbatim JSON (raw JsonNode values, never re-rendered as double)
        ObjectNode parameters = root.putObject("parameters");
        Map<String, JsonNode> params = descriptor.parameters();
        if (params != null)
            for (var e : params.entrySet()) parameters.set(e.getKey(), e.getValue());

        // parametersets (fase 5): each loaded set with its per-parameter validity ranges + the versioned value.
        ArrayNode parameterSets = root.putArray("parametersets");
        for (Parameterset set : core.parameterSets()) {
            ObjectNode sn = parameterSets.addObject();
            sn.put("naam", set.naam());
            ObjectNode values = sn.putObject("waarden");
            for (var e : set.waarden().entrySet()) {
                ArrayNode intervals = values.putArray(e.getKey());
                for (WaardeInterval wi : e.getValue()) {
                    ObjectNode iv = intervals.addObject();
                    Geldigheid g = wi.geldigheid();
                    if (g.vanaf() != null) iv.put("vanaf", g.vanaf().toString()); else iv.putNull("vanaf");
                    if (g.tot() != null) iv.put("tot", g.tot().toString()); else iv.putNull("tot");
                    iv.set("waarde", renderValue(wi.waarde()));
                }
            }
        }

        // periodWarnings (gate G3): the rendered regel×parameter period-coverage WARNING lines.
        ArrayNode warnings = root.putArray("periodeWaarschuwingen");
        for (String w : core.periodWarnings()) warnings.add(w);

        // the vocabularies, each with the declarations an answer in it names canonically; absent without any
        if (!core.model().vocabulaires.isEmpty()) root.set("vocabulaires", Completeness.info(core.model()));

        return root;
    }

    /** Render a parameterset value as an EXACT JSON node for /info (ValueCoder-symmetric with
     *  {@code AnswerBuilder.rend}): BigDecimal as a number (never a double), date/datetime as an ISO string,
     *  boolean as boolean, tekst/enum as string, explicit leegwaarde as JSON null. */
    private JsonNode renderValue(Object v) {
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
    static ObjectNode errorBody(String notice, String field) {
        ObjectNode n = NF.objectNode();
        n.put("fout", notice);
        if (field != null) n.put("veld", field);
        return n;
    }

    /** The MARKER answer to a request at an entry point uchar does not carry: {@code {"status": "MARKER", "fout":
     *  <boodschap>, "reden": <uchar's sentence>, "markers": [<construct>...]}}. */
    static ObjectNode markerBody(DecisionCore.NonExecutable rejected) {
        ObjectNode n = NF.objectNode();
        n.put("status", "MARKER");
        n.put("fout", rejected.getMessage());
        n.put("reden", rejected.reason());
        ArrayNode markers = n.putArray("markers");
        for (String m : rejected.markers()) markers.add(m);
        return n;
    }

    /** {@code {"fout": <boodschap>, "schendingen": [{"veld": <pad>, "soort": <soort>, "fout": <detail>}, ...]}} —
     *  every schending NAMES its veld, and its soort tells a caller a request error from a request form the service
     *  does not carry ({@code NIET_ONDERSTEUND}). Built from a request-mapping {@link ServiceError}. */
    static ObjectNode requestErrorBody(ServiceError error) {
        ObjectNode n = NF.objectNode();
        // the constructed message already concatenates the schendingen; use its context head only would lose the
        // count, so keep the full message — it names nothing secret (all service-internal diagnostics).
        n.put("fout", "verzoek valideert niet");
        ArrayNode violations = n.putArray("schendingen");
        for (Violation s : error.violations()) {
            ObjectNode sn = violations.addObject();
            sn.put("veld", s.field());
            sn.put("soort", s.kind().name());
            sn.put("fout", s.message());
        }
        return n;
    }

    // ---------------- write ----------------

    private void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] sink = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", JSON);
        ex.sendResponseHeaders(status, sink.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(sink);
        }
    }
}
