package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.uchar.regelspraak.ir.Validity;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.ParameterSet.ValueInterval;
import dev.uchar.regelspraak.toolchain.service.AnswerBuilder;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.EvaluationFault;
import dev.uchar.regelspraak.toolchain.service.Limit;
import dev.uchar.regelspraak.toolchain.service.NoFixpoint;
import dev.uchar.regelspraak.toolchain.service.OpenApiEmitter;
import dev.uchar.regelspraak.toolchain.service.Violation;
import dev.uchar.regelspraak.toolchain.service.ValueCoder;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;
import dev.uchar.regelspraak.toolchain.service.Completeness;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Coverage;

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
 * The HTTP contract of the decision service over the framework-agnostic {@link DecisionCore}, and its adapter on the
 * JDK {@link HttpServer}. It owns no RegelSpraak meaning: it marshals JSON in and out and maps the core's typed
 * outcomes onto HTTP status codes. The Quarkus adapter sends the same answers through {@link #post}, {@link #info}
 * and {@link #openapi}, so the two cannot drift.
 *
 * <h2>Routes</h2>
 * <ul>
 *   <li>{@code POST /<path>} for every entry point: the body is read as JSON (BigDecimal-for-floats so no request
 *       number is ever a double), handed to {@link DecisionCore#process}, and the response tree is written back
 *       with {@code 200} and {@code Content-Type: application/json; charset=UTF-8}. The query carries what the
 *       request asks beside its body ({@link #options}): {@code explanation}, {@code explanationParts},
 *       {@code vocabulary} (the vocabulary of the answer), {@code inputVocabulary} (the one the request's names are
 *       read in), and the detail flags {@code trace}, {@code coverage} and {@code fixpoint}, which a core started with
 *       detail answers ({@link dev.uchar.regelspraak.toolchain.service.CoreConfig#detail()}) and any other refuses
 *       with a 400.</li>
 *   <li>{@code GET /info}: the service manifest ({@link #info}) — the service identity, one entry per entry point
 *       carrying its path, whether it is {@code executable}, its {@code coverage} (the exec-model branch's verdict per
 *       form of its document, the defaults groups included), its {@code markers} and, when uchar does not carry it,
 *       uchar's sentence as {@code refusal}; the list of all {@code skipped} (marker) names across the service, the
 *       descriptor {@code parameters} defaults verbatim, the {@code parameterSets} and the {@code periodWarnings}. A
 *       model with vocabularies adds {@code vocabularies} ({@link Completeness}), and an entry point with a default
 *       vocabulary names it.</li>
 *   <li>{@code GET /openapi}: the OpenAPI document of the service ({@link #openapi}), the one
 *       {@link OpenApiEmitter#emit} writes over the core's descriptor and model, in {@link OpenApiEmitter#toBytes}'s
 *       canonical bytes, so that {@code bin/regelspraak openapi} writes the same file. {@code ?vocabulary=<name>}
 *       or {@code ?vocabulary=canonical} chooses the names, as at an entry point; without it the canonical names. An
 *       unknown vocabulary is a {@code 400} {@code UNKNOWN_VOCABULARY}.</li>
 * </ul>
 *
 * <h2>Errors — always a JSON body, never a leaked stacktrace</h2>
 * <ul>
 *   <li>{@link ServiceError} from {@code process} → {@code 400} {@code {"error": …, "violations": [{"field": <path>,
 *       "kind": <kind>, "error": <detail>}, ...]}} — every violation names its {@code field}.</li>
 *   <li>malformed / empty request body JSON → {@code 400} {@code {"error": "...", "field": "body"}}.</li>
 *   <li>{@link DecisionCore.UnknownPath} → {@code 404} naming the path.</li>
 *   <li>{@link DecisionCore.NonExecutable} → {@code 503}, the MARKER answer: {@code {"status": "MARKER",
 *       "error": …, "reason": <uchar's sentence>, "markers": […]}}. An entry point uchar does not carry has no
 *       KieBase, and an answer without the construct would be an answer to another rule set.</li>
 *   <li>{@link EvaluationFault} → {@code 422}: the rules give an evaluation fault on this input, which stands at the
 *       fixpoint ({@link AnswerBuilder#evaluationFault}): {@code {"status": "EVALUATION_FAULT", "error": …, "faults":
 *       [{"code": <EF-###>, "params": {…}, "error": <uchar's sentence>}, …]}}.</li>
 *   <li>{@link NoFixpoint} → {@code 422}: the session of the request reached no fixpoint, at uchar's fire cap or its
 *       creation budget ({@link AnswerBuilder#noFixpoint}): {@code {"status": "NO_FIXPOINT", "error": <uchar's
 *       sentence>, "code": <EF-015 or null>, "params": {…}}}.</li>
 *   <li>{@link Limit} → {@code 422}: uchar's Drools branch reaches a limit at the fixpoint, a value it cannot hold
 *       ({@link AnswerBuilder#limit}): {@code {"status": "LIMIT", "error": …, "code": <uchar's register code>,
 *       "reason": <uchar's sentence>}}.</li>
 *   <li>wrong method on a known path → {@code 405} naming the method.</li>
 *   <li>any other exception → {@code 500} {@code {"error": "interne fout"}} — never a stacktrace or an exception
 *       message in the body; the stack is logged to {@code stderr} server-side.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * The server executor is {@link Executors#newVirtualThreadPerTaskExecutor()} (Java 21 virtual threads). The core
 * is thread-safe by construction — each entry point's {@link org.kie.api.KieBase} is built once at startup and
 * immutable thereafter, and {@code process} opens a fresh {@code KieSession} per request over the entry point's
 * read-only bean classloader — so an unbounded virtual-thread-per-request executor is safe.
 */
public final class ServiceHttp {

    /** BigDecimal-for-floats: a request/response number is never a double (the exact-render discipline). A key written
     *  twice in one object is a parse error, where the parser would keep the last value without saying so. Both
     *  adapters read and write with it. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true))
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
        return routes(core, processor, host, port, false);
    }

    /**
     * Bind the playground beside the service of {@code core} on {@code host}:{@code port}: the routes of
     * {@link #bind(DecisionCore, String, int)} and {@code GET /playground} and {@code POST /playground/analyse}, so
     * that {@code ?via=service} computes a scenario through the entry points this process serves
     * ({@code bin/regelspraak playground} with a descriptor). Call {@link #start()} to begin serving.
     */
    public static ServiceHttp playground(DecisionCore core, String host, int port) throws IOException {
        return routes(core, core::process, host, port, true);
    }

    /**
     * Bind the playground alone on {@code host}:{@code port}, without a service: {@code GET /playground} and
     * {@code POST /playground/analyse}, every other path a 404. It analyses the source each request posts and has no
     * served model, so {@code ?via=service} is a 400 that says why, and the page says the same
     * ({@code bin/regelspraak playground}). Call {@link #start()} to begin serving.
     */
    public static ServiceHttp playground(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        ServiceHttp service = new ServiceHttp(null, null, server);
        server.createContext("/playground/analyse", service::processPlaygroundAnalysis);
        server.createContext("/playground", service::processPlaygroundPage);
        server.createContext("/", service::processUnknown);
        return service;
    }

    /** Why a playground without a service computes no scenario through one: the 400 of {@code ?via=service} and the
     *  line on the page say it. */
    static final String NO_SERVICE = "deze playground is zonder dienst gestart (bin/regelspraak playground) en"
            + " vergelijkt geen scenario met de antwoorden van een dienst; start daarvoor bin/regelspraak playground"
            + " met een descriptor, zijn bron en --detail";

    /** The routes of the service of {@code core}, and with {@code playground} those of the playground beside them;
     *  {@code serve} never binds the playground, so a production service carries no development route. */
    private static ServiceHttp routes(DecisionCore core, Processor processor, String host, int port, boolean playground)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        ServiceHttp service = new ServiceHttp(core, processor, server);
        // one POST route per entry point path, plus /info
        for (String path : core.entrypoints().keySet())
            server.createContext("/" + path, service::processPost);
        server.createContext("/info", service::processInfo);
        server.createContext("/openapi", service::processOpenapi);
        // without the playground its paths 404 through the catch-all below
        if (playground) {
            server.createContext("/playground/analyse", service::processPlaygroundAnalysis);
            server.createContext("/playground", service::processPlaygroundPage);
        }
        // catch-all: any path not matched by a more-specific context (longest-prefix wins) -> JSON 404 naming it
        server.createContext("/", service::processUnknown);
        return service;
    }

    /** The POST-handling delegate, satisfied by {@code core::process}; a test hands in one that throws, to see the 500
     *  that never leaks. */
    @FunctionalInterface
    public interface Processor {
        ObjectNode process(String path, JsonNode requestTree, RequestOptions options);
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
            Answer answer = post(core, processor, path, options(ex.getRequestURI().getRawQuery()),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(ex, answer.status(), answer.body());
        } catch (Exception intern) {
            // NEVER leak a stacktrace or the exception message to the client; log it server-side.
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    /** What an adapter sends for a request: the HTTP status and the body. */
    public record Answer(int status, JsonNode body) {}

    /**
     * The answer to a {@code POST} of {@code body} to the entry point at {@code path}, asking {@code options}: the
     * status and body both adapters send. {@code core} judges the path and the options first
     * ({@link DecisionCore#admit}), before the body is read; then an empty body, one that is no JSON and JSON
     * {@code null} are a 400 on {@code body}; otherwise {@code processor} answers. What the two throw maps as the class
     * comment says; any other exception leaves here, for the adapter to log and answer with a 500.
     */
    public static Answer post(DecisionCore core, Processor processor, String path, RequestOptions options,
                              String body) {
        try {
            core.admit(path, options);
            if (body == null || body.isEmpty())
                return new Answer(400, errorBody("leeg verzoek-body: verwacht JSON met een 'instances'-array", "body"));
            JsonNode tree;
            try {
                tree = MAPPER.readTree(body);
            } catch (JsonProcessingException malformed) {
                return new Answer(400, errorBody("verzoek-body is geen geldige JSON: " + malformed.getOriginalMessage(),
                        "body"));
            }
            if (tree == null || tree.isNull())
                return new Answer(400, errorBody("verzoek-body is geen geldige JSON (null)", "body"));
            return new Answer(200, processor.process(path, tree, options));
        } catch (DecisionCore.UnknownPath unknown) {
            return new Answer(404, errorBody("onbekend pad: '/" + path + "'", null));
        } catch (DecisionCore.NonExecutable rejected) {
            return new Answer(503, markerBody(rejected));
        } catch (ServiceError serviceError) {
            return new Answer(400, requestErrorBody(serviceError));
        } catch (EvaluationFault evaluationFault) {
            return new Answer(422, AnswerBuilder.evaluationFault(evaluationFault));
        } catch (NoFixpoint none) {
            return new Answer(422, AnswerBuilder.noFixpoint(none));
        } catch (Limit limit) {
            return new Answer(422, AnswerBuilder.limit(limit));
        }
    }

    /** Whether the query string opts into the uitleg field: {@code explanation=true} (case-insensitive) or a bare
     *  {@code explanation}. Any other value (incl. {@code explanation=false}) means no — the response is byte-identical to
     *  a request without the flag. Null/empty query → false. Public so a test can pin the parse (the Quarkus
     *  adapter carries the equivalent inline). */
    public static boolean requiresExplanation(String rawQuery) {
        return isRequested(rawQuery, "explanation");
    }

    /** Whether the query string sets the flag {@code naam} as {@link #requiresExplanation} reads {@code explanation}: the first
     *  parameter of that name (compared case-insensitively) with the value {@code true} (case-insensitive), or bare. */
    public static boolean isRequested(String rawQuery, String name) {
        return isSet(queryValue(rawQuery, name));
    }

    /** Whether the value of a flag in the query sets it: {@code true} (case-insensitive) or bare (the empty text); null,
     *  the flag absent, does not. */
    public static boolean isSet(String value) {
        return value != null && (value.isEmpty() || "true".equalsIgnoreCase(value));
    }

    /** What the query of a request asks beside its body: {@link #requiresExplanation}, the values of {@code vocabulaire} and
     *  {@code inputVocabulary} ({@link #queryValue}), the detail flags {@code trace}, {@code dekking} and
     *  {@code fixpunt}, and {@code explanationParts} ({@link #isRequested}). The Quarkus adapter reads the same seven
     *  parameters. */
    public static RequestOptions options(String rawQuery) {
        return new RequestOptions(requiresExplanation(rawQuery), queryValue(rawQuery, "vocabulary"),
                queryValue(rawQuery, "inputVocabulary"), isRequested(rawQuery, "trace"), isRequested(rawQuery, "coverage"),
                isRequested(rawQuery, "fixpoint"), isRequested(rawQuery, "explanationParts"));
    }

    /** The decoded value of the first query parameter {@code naam} (its name compared case-insensitively, as
     *  {@code explanation}), the empty text for a bare {@code naam}, or null when the query does not carry it. */
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
            send(ex, 200, info(core));
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    // ---------------- GET /openapi ----------------

    private void processOpenapi(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, errorBody("methode '" + ex.getRequestMethod()
                        + "' niet toegestaan op '/openapi' (gebruik GET)", null));
                return;
            }
            Document document = openapi(core, queryValue(ex.getRequestURI().getRawQuery(), "vocabulary"));
            ex.getResponseHeaders().set("Content-Type", JSON);
            ex.sendResponseHeaders(document.status(), document.body().length);
            try (OutputStream os = ex.getResponseBody()) { os.write(document.body()); }
        } catch (Exception intern) {
            intern.printStackTrace(System.err);
            send(ex, 500, errorBody("interne fout", null));
        }
    }

    /** What an adapter sends for {@code GET /openapi}: the HTTP status and the bytes of the body. */
    public record Document(int status, byte[] body) {}

    /**
     * The answer to {@code GET /openapi} on {@code core} in {@code vocabulary} (null, the query without it, or
     * {@code canonical} for the canonical names): {@code 200} with the canonical bytes of {@link OpenApiEmitter#emit}
     * over the core's descriptor and model, or {@code 400} {@code UNKNOWN_VOCABULARY} on {@code vocabulary} when the
     * model declares no such vocabulary. Both adapters and {@code bin/regelspraak openapi} send these bytes.
     */
    public static Document openapi(DecisionCore core, String vocabulary) {
        try {
            return new Document(200, OpenApiEmitter.toBytes(
                    OpenApiEmitter.emit(core.descriptor(), core.model(), vocabulary)));
        } catch (Naming.VocabularyError unknown) {
            try {
                return new Document(400, MAPPER.writeValueAsBytes(requestErrorBody(new ServiceError(
                        "verzoek valideert niet", List.of(Violation.of(Violation.Kind.UNKNOWN_VOCABULARY,
                                "vocabulary", unknown.getMessage()))))));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("een foutlichaam laat zich altijd schrijven", e);
            }
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

    // ---------------- the playground (bin/regelspraak playground) ----------------

    /** Per-request compile budget for the playground (ms). A learner's snippet is tiny; a runaway analysis (e.g. a
     *  pathological grammar input) is capped so the dev workbench never wedges a thread indefinitely. */
    static final long PLAYGROUND_TIMEOUT_MS = 15_000;

    /** {@code POST /playground/analyse}: the request body is the raw RegelSpraak source (text/plain). It is analyzed
     *  compile-per-request under a timeout with NO persistence, and the {@link Playground} answer tree is written
     *  back as JSON. Errors at the RegelSpraak level (parse/resolve/scenario) are part of that tree, not HTTP
     *  errors; only a timeout (503) or an unexpected internal fault (500) map to a status.
     *
     *  <p>{@code ?via=service} has this service's core compute the scenarios ({@link Playground#analyze(String,
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
            String vocabulary = queryValue(ex.getRequestURI().getRawQuery(), "vocabulary");
            String via = queryValue(ex.getRequestURI().getRawQuery(), "via");
            if (via != null && !"service".equalsIgnoreCase(via)) {
                send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(Violation.of(
                        Violation.Kind.MALFORMED, "via", "via kent alleen de waarde 'service', niet '" + via + "'")))));
                return;
            }
            if (via != null && core == null) {
                send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(Violation.of(
                        Violation.Kind.UNSUPPORTED, "via", NO_SERVICE)))));
                return;
            }
            if (via != null && !core.detail()) {
                send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(Violation.of(
                        Violation.Kind.UNSUPPORTED, "via", "de dienst is zonder DETAIL gestart en rekent geen"
                                + " scenario's via de dienst; start hem met DETAIL=true of --detail")))));
                return;
            }
            DecisionCore service = via == null ? null : core;
            java.util.Locale locale = dev.uchar.regelspraak.toolchain.engine.MessageLanguage
                    .ofAcceptLanguage(ex.getRequestHeaders().getFirst("Accept-Language"));
            // Compile-per-request under a hard timeout, on a throwaway single-use executor (no shared state).
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<ObjectNode> f = exec.submit(() -> Playground.analyze(source, vocabulary, service, locale));
                send(ex, 200, f.get(PLAYGROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                send(ex, 503, errorBody("analyse overschreed de tijdslimiet ("
                        + PLAYGROUND_TIMEOUT_MS + " ms)", null));
            } catch (java.util.concurrent.ExecutionException ee) {
                if (ee.getCause() instanceof ServiceError df) {
                    // via=service on a source that is not the service's own
                    send(ex, 400, requestErrorBody(df));
                    return;
                }
                if (ee.getCause() instanceof Naming.VocabularyError vf) {
                    // the source's model declares no such vocabulary: the request asked what cannot be answered
                    send(ex, 400, requestErrorBody(new ServiceError("verzoek valideert niet", List.of(
                            Violation.of(Violation.Kind.UNKNOWN_VOCABULARY, "vocabulary", vf.getMessage())))));
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

    /** The place on the page where a playground without a service says why it offers no comparison with one. */
    static final String VIA_PLACE = "<span class=\"hint\" id=\"via\"></span>";

    /** The page of a playground without a service: {@link #NO_SERVICE} at {@link #VIA_PLACE}. */
    private static byte[] withoutService(byte[] html) {
        String page = new String(html, StandardCharsets.UTF_8);
        if (!page.contains(VIA_PLACE))
            throw new IllegalStateException("playground.html heeft geen plaats " + VIA_PLACE);
        return page.replace(VIA_PLACE, "<span class=\"hint\" id=\"via\">" + NO_SERVICE + "</span>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** {@code GET /playground}: serve the one self-contained HTML page (a classpath resource, no build tooling); a
     *  playground without a service ({@link #playground}) says on it why it offers no {@code ?via=service}. */
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
            if (core == null) html = withoutService(html);
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

    /** The {@code /info} manifest of {@code core}, from its descriptor and compiled entry points; both adapters serve
     *  it. */
    public static ObjectNode info(DecisionCore core) {
        ServiceDescriptor descriptor = core.descriptor();
        ObjectNode root = NF.objectNode();

        ObjectNode service = root.putObject("service");
        if (descriptor.service() != null) {
            service.put("name", descriptor.service().name());
            service.put("version", descriptor.service().version());
        }

        ArrayNode entrypoints = root.putArray("entrypoints");
        ArrayNode skipped = NF.arrayNode();   // ALL marker rule names across the service
        for (DecisionCore.Entrypoint ep : core.entrypoints().values()) {
            ObjectNode eNode = entrypoints.addObject();
            eNode.put("name", ep.descriptor().name());
            eNode.put("path", ep.descriptor().path());
            if (ep.descriptor().vocabulary() != null) eNode.put("vocabulary", ep.descriptor().vocabulary());
            eNode.put("executable", ep.executable());
            if (!ep.executable()) eNode.put("refusal", ep.refusal());

            ArrayNode coverage = eNode.putArray("coverage");
            for (Coverage d : ep.coverage()) {
                ObjectNode dn = coverage.addObject();
                dn.put("kind", d.category());
                dn.put("name", d.name());
                dn.put("verdict", d.verdict().name());
                if (d.reason() != null && !d.reason().isEmpty()) dn.put("reason", d.reason());
            }

            ArrayNode markers = eNode.putArray("markers");
            for (String m : ep.markers()) {
                markers.add(m);
                skipped.add(m);
            }
        }
        root.set("entrypoints", entrypoints);
        root.set("skipped", skipped);

        // parameters: the descriptor defaults, verbatim JSON (raw JsonNode values, never re-rendered as double)
        ObjectNode parameters = root.putObject("parameters");
        Map<String, JsonNode> params = descriptor.parameters();
        if (params != null)
            for (var e : params.entrySet()) parameters.set(e.getKey(), e.getValue());

        // parametersets (fase 5): each loaded set with its per-parameter validity ranges + the versioned value.
        ArrayNode parameterSets = root.putArray("parameterSets");
        for (ParameterSet set : core.parameterSets()) {
            ObjectNode sn = parameterSets.addObject();
            sn.put("name", set.name());
            ObjectNode values = sn.putObject("values");
            for (var e : set.values().entrySet()) {
                ArrayNode intervals = values.putArray(e.getKey());
                for (ValueInterval wi : e.getValue()) {
                    ObjectNode iv = intervals.addObject();
                    Validity g = wi.validity();
                    if (g.from() != null) iv.put("from", g.from().toString()); else iv.putNull("from");
                    if (g.until() != null) iv.put("to", g.until().toString()); else iv.putNull("to");
                    iv.set("value", renderValue(wi.value()));
                }
            }
        }

        // periodWarnings (gate G3): the rendered regel×parameter period-coverage WARNING lines.
        ArrayNode warnings = root.putArray("periodWarnings");
        for (String w : core.periodWarnings()) warnings.add(w);

        // the vocabularies, each with the declarations an answer in it names canonically; absent without any
        if (!core.model().vocabularies.isEmpty()) root.set("vocabularies", Completeness.info(core.model()));

        return root;
    }

    /** Render a parameterset value as an EXACT JSON node for /info (ValueCoder-symmetric with
     *  {@code AnswerBuilder.rend}): BigDecimal as a number (never a double), a date as an ISO string, a date-time in its case lexeme
     *  ({@link ValueCoder#dateTimeLexeme}),
     *  boolean as boolean, tekst/enum as string, explicit leegwaarde as JSON null. */
    private static JsonNode renderValue(Object v) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.numberNode(b);
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(ValueCoder.dateTimeLexeme(d));
        if (v instanceof String s) return NF.textNode(s);
        return NF.textNode(v.toString());
    }

    // ---------------- error bodies ----------------

    /** {@code {"error": <boodschap>, "field"?: <veld>}}. */
    public static ObjectNode errorBody(String notice, String field) {
        ObjectNode n = NF.objectNode();
        n.put("error", notice);
        if (field != null) n.put("field", field);
        return n;
    }

    /** The MARKER answer to a request at an entry point uchar does not carry: {@code {"status": "MARKER", "error":
     *  <boodschap>, "reason": <uchar's sentence>, "markers": [<construct>...]}}. */
    private static ObjectNode markerBody(DecisionCore.NonExecutable rejected) {
        ObjectNode n = NF.objectNode();
        n.put("status", "MARKER");
        n.put("error", rejected.getMessage());
        n.put("reason", rejected.reason());
        ArrayNode markers = n.putArray("markers");
        for (String m : rejected.markers()) markers.add(m);
        return n;
    }

    /** {@code {"error": <boodschap>, "violations": [{"field": <pad>, "kind": <soort>, "error": <detail>}, ...]}} —
     *  every schending NAMES its veld, and its soort tells a caller a request error from a request form the service
     *  does not carry ({@code UNSUPPORTED}). Built from a request-mapping {@link ServiceError}. */
    private static ObjectNode requestErrorBody(ServiceError error) {
        ObjectNode n = NF.objectNode();
        n.put("error", "verzoek valideert niet");
        ArrayNode violations = n.putArray("violations");
        for (Violation s : error.violations()) {
            ObjectNode sn = violations.addObject();
            sn.put("field", s.field());
            sn.put("kind", s.kind().name());
            sn.put("error", s.message());
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
