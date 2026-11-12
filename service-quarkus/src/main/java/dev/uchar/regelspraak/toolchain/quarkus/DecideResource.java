package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

import org.jboss.logging.Logger;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.RequestOptions;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

/**
 * The Quarkus JAX-RS adapter over {@link DecisionCore}. The answers are those of the JDK adapter: {@code POST /<path>}
 * sends what {@link ServiceHttp#post} gives and {@code GET /info} the manifest of {@link ServiceHttp#info}, so the HTTP
 * contract has one implementation. Any exception those do not map is a {@code 500} {@code {"error":"interne fout"}},
 * its stacktrace in the log and never in the answer.
 *
 * <p>The query parameters are {@code ServiceHttp}'s, read as it reads them ({@link ServiceHttp#isSet}). The body is
 * taken as a raw {@link String} and parsed with {@link ServiceHttp#MAPPER}, so no request number is ever a
 * {@code double}, whatever the ambient Quarkus Jackson config; the answer is written back through the same mapper.
 */
@Path("/")
public class DecideResource {

    private static final Logger LOG = Logger.getLogger(DecideResource.class);

    @Inject
    DecisionCore core;

    // ---------------- POST /<path> ----------------

    @POST
    @Path("{path}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response process(@PathParam("path") String path, @QueryParam("explanation") String explanation,
                            @QueryParam("vocabulary") String vocabulary,
                            @QueryParam("inputVocabulary") String inputVocabulary,
                            @QueryParam("trace") String trace, @QueryParam("coverage") String coverage,
                            @QueryParam("fixpoint") String fixpoint,
                            @QueryParam("explanationParts") String explanationParts, String body) {
        RequestOptions options = new RequestOptions(ServiceHttp.isSet(explanation), vocabulary, inputVocabulary,
                ServiceHttp.isSet(trace), ServiceHttp.isSet(coverage), ServiceHttp.isSet(fixpoint),
                ServiceHttp.isSet(explanationParts));
        try {
            ServiceHttp.Answer answer = ServiceHttp.post(core, this::decide, path, options, body);
            return response(answer.status(), answer.body());
        } catch (RuntimeException intern) {
            LOG.error("interne fout bij POST /" + path, intern);
            return response(500, ServiceHttp.errorBody("interne fout", null));
        }
    }

    /** The core's answer to one request; a test overrides it to throw what the core never should. */
    ObjectNode decide(String path, JsonNode tree, RequestOptions options) {
        return core.process(path, tree, options);
    }

    /** A request that asks no detail of its session: the resource method without the detail flags. */
    public Response process(String path, String explanation, String vocabulary, String inputVocabulary, String body) {
        return process(path, explanation, vocabulary, inputVocabulary, null, null, null, null, body);
    }

    /** A request that names no vocabulary: {@link #process(String, String, String, String, String)} without them. */
    public Response process(String path, String explanation, String body) {
        return process(path, explanation, null, null, body);
    }

    // ---------------- GET /info ----------------

    @GET
    @Path("info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response info() {
        try {
            return response(200, ServiceHttp.info(core));
        } catch (RuntimeException intern) {
            LOG.error("interne fout bij GET /info", intern);
            return response(500, ServiceHttp.errorBody("interne fout", null));
        }
    }

    /** Write {@code body} through the exact-render mapper so no number is a double. */
    private static Response response(int status, JsonNode body) {
        try {
            return Response.status(status)
                    .type(MediaType.APPLICATION_JSON + "; charset=UTF-8")
                    .entity(ServiceHttp.MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException e) {
            // an ObjectNode always serialises; this is unreachable, but never leak a stacktrace.
            return Response.status(500).entity("{\"error\":\"interne fout\"}").build();
        }
    }
}
