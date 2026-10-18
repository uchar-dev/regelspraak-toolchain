package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dev.uchar.regelspraak.toolchain.service.AnswerBuilder;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.EvaluationFault;
import dev.uchar.regelspraak.toolchain.service.NoFixpoint;
import dev.uchar.regelspraak.toolchain.service.Violation;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;

/**
 * The Quarkus JAX-RS adapter over {@link DecisionCore}, MIRRORING {@code ServiceHttp}'s HTTP contract exactly:
 * <ul>
 *   <li>{@code POST /<pad>} → {@link DecisionCore#process} → {@code 200} JSON; {@link ServiceError} → {@code 400}
 *       {@code {"error":…,"violations":[{"field":…,"error":…}]}}; {@link DecisionCore.UnknownPath} → {@code 404};
 *       {@link DecisionCore.NonExecutable} → {@code 503} with the MARKER answer; {@link EvaluationFault} and
 *       {@link NoFixpoint} → {@code 422} with the answer {@link AnswerBuilder#evaluationFault} or
 *       {@link AnswerBuilder#noFixpoint} builds, as {@code ServiceHttp} sends it.</li>
 *   <li>{@code GET /info} → the SAME manifest {@code ServiceHttp} serves (via {@link InfoBuilder}).</li>
 * </ul>
 * The query parameters are {@code ServiceHttp}'s: {@code uitleg}, {@code vocabulaire} (the vocabulary of the answer),
 * {@code invoervocabulaire} (the one the request's names are read in), and the detail flags {@code trace},
 * {@code dekking} and {@code fixpunt}, which the core answers when {@code DETAIL} started it with detail
 * ({@link CoreProducer}) and refuses with a 400 otherwise.
 *
 * <p>The request body is read as a raw {@link String} and parsed with a private {@link ObjectMapper} configured
 * {@code USE_BIG_DECIMAL_FOR_FLOATS} (mirror of {@code ServiceHttp.MAPPER}), so no request number is ever a
 * {@code double} — regardless of the ambient Quarkus Jackson config. The response {@link ObjectNode} is written
 * back through the SAME mapper for exact-render symmetry.
 */
@Path("/")
public class DecideResource {

    /** BigDecimal-for-floats: a request/response number is never a double (mirror of {@code ServiceHttp.MAPPER}). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    @Inject
    DecisionCore core;

    // ---------------- POST /<pad> ----------------

    @POST
    @Path("{path}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response process(@PathParam("path") String path, @QueryParam("explanation") String uitleg,
                            @QueryParam("vocabulary") String vocabulary,
                            @QueryParam("inputVocabulary") String invoervocabulaire,
                            @QueryParam("trace") String trace, @QueryParam("coverage") String coverage,
                            @QueryParam("fixpoint") String fixpoint, String body) {
        JsonNode tree;
        if (body == null || body.isEmpty())
            return response(400, errorBody("leeg verzoek-body: verwacht JSON met een 'instanties'-array", "body"));
        try {
            tree = MAPPER.readTree(body);
        } catch (JsonProcessingException malformed) {
            return response(400, errorBody("verzoek-body is geen geldige JSON: " + malformed.getOriginalMessage(),
                    "body"));
        }
        if (tree == null || tree.isNull())
            return response(400, errorBody("verzoek-body is geen geldige JSON (null)", "body"));

        try {
            // ?explanation=true (or a bare ?uitleg) opts into the Verbalisator verhaal, and the detail flags read the same
            // way; any other value = no (mirror of ServiceHttp.isRequested)
            ObjectNode answer = core.process(path, tree, new RequestOptions(isRequested(uitleg), vocabulary,
                    invoervocabulaire, isRequested(trace), isRequested(coverage), isRequested(fixpoint)));
            return response(200, answer);
        } catch (DecisionCore.UnknownPath unknown) {
            // ServiceHttp answers an unknown path from its catch-all route, which names the request path, slash
            // included; the kern's own message names the pad without it
            return response(404, errorBody("onbekend pad: '/" + path + "'", null));
        } catch (DecisionCore.NonExecutable rejected) {
            return response(503, markerBody(rejected));
        } catch (ServiceError serviceError) {
            return response(400, requestErrorBody(serviceError));
        } catch (EvaluationFault evaluationFault) {
            return response(422, AnswerBuilder.evaluationFault(evaluationFault));
        } catch (NoFixpoint none) {
            return response(422, AnswerBuilder.noFixpoint(none));
        }
    }

    /** A request that asks no detail of its session: the resource method without the detail flags. */
    public Response process(String path, String uitleg, String vocabulary, String invoervocabulaire, String body) {
        return process(path, uitleg, vocabulary, invoervocabulaire, null, null, null, body);
    }

    /** A request that names no vocabulary: {@link #process(String, String, String, String, String)} without them. */
    public Response process(String path, String uitleg, String body) {
        return process(path, uitleg, null, null, body);
    }

    /** A query flag as {@code ServiceHttp.isRequested} reads it: {@code true} (case-insensitive) or bare. */
    private static boolean isRequested(String value) {
        return "true".equalsIgnoreCase(value) || (value != null && value.isEmpty());
    }

    // ---------------- GET /info ----------------

    @GET
    @Path("info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response info() {
        return response(200, InfoBuilder.assemble(core));
    }

    // ---------------- error bodies (mirror ServiceHttp) ----------------

    private static ObjectNode errorBody(String notice, String field) {
        ObjectNode n = NF.objectNode();
        n.put("error", notice);
        if (field != null) n.put("field", field);
        return n;
    }

    /** Mirror of {@code ServiceHttp.markerBody}. */
    private static ObjectNode markerBody(DecisionCore.NonExecutable rejected) {
        ObjectNode n = NF.objectNode();
        n.put("status", "MARKER");
        n.put("error", rejected.getMessage());
        n.put("reason", rejected.reason());
        var markers = n.putArray("markers");
        for (String m : rejected.markers()) markers.add(m);
        return n;
    }

    private static ObjectNode requestErrorBody(ServiceError error) {
        ObjectNode n = NF.objectNode();
        n.put("error", "verzoek valideert niet");
        var violations = n.putArray("violations");
        for (Violation s : error.violations()) {
            ObjectNode sn = violations.addObject();
            sn.put("field", s.field());
            sn.put("kind", s.kind().name());
            sn.put("error", s.message());
        }
        return n;
    }

    /** Write {@code body} through the exact-render mapper so no number is a double. */
    private static Response response(int status, JsonNode body) {
        try {
            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON + "; charset=UTF-8")
                    .entity(MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException e) {
            // an ObjectNode always serialises; this is unreachable, but never leak a stacktrace.
            return Response.status(500).entity("{\"error\":\"interne fout\"}").build();
        }
    }
}
