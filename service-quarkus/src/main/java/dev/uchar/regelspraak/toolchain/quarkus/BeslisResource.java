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

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstFout;
import dev.uchar.regelspraak.toolchain.service.Schending;

/**
 * The Quarkus JAX-RS adapter over {@link BeslisserKern}, MIRRORING {@code DienstHttp}'s HTTP contract exactly:
 * <ul>
 *   <li>{@code POST /<pad>} → {@link BeslisserKern#verwerk} → {@code 200} JSON; {@link DienstFout} → {@code 400}
 *       {@code {"fout":…,"schendingen":[{"veld":…,"fout":…}]}}; {@link BeslisserKern.OnbekendPad} → {@code 404}.</li>
 *   <li>{@code GET /info} → the SAME manifest {@code DienstHttp} serves (via {@link InfoBouwer}).</li>
 * </ul>
 *
 * <p>The request body is read as a raw {@link String} and parsed with a private {@link ObjectMapper} configured
 * {@code USE_BIG_DECIMAL_FOR_FLOATS} (mirror of {@code DienstHttp.MAPPER}), so no request number is ever a
 * {@code double} — regardless of the ambient Quarkus Jackson config. The response {@link ObjectNode} is written
 * back through the SAME mapper for exact-render symmetry.
 */
@Path("/")
public class BeslisResource {

    /** BigDecimal-for-floats: a request/response number is never a double (mirror of {@code DienstHttp.MAPPER}). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    @Inject
    BeslisserKern kern;

    // ---------------- POST /<pad> ----------------

    @POST
    @Path("{pad}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verwerk(@PathParam("pad") String pad, @QueryParam("uitleg") String uitleg, String body) {
        JsonNode boom;
        if (body == null || body.isEmpty())
            return response(400, foutBody("leeg verzoek-body: verwacht JSON met een 'instanties'-array", "body"));
        try {
            boom = MAPPER.readTree(body);
        } catch (JsonProcessingException malformed) {
            return response(400, foutBody("verzoek-body is geen geldige JSON: " + malformed.getOriginalMessage(),
                    "body"));
        }
        if (boom == null || boom.isNull())
            return response(400, foutBody("verzoek-body is geen geldige JSON (null)", "body"));

        try {
            // ?uitleg=true (or a bare ?uitleg) opts into the Verbalisator verhaal; any other value = no (mirror
            // of DienstHttp.vraagtUitleg). Additive + descriptor-independent: no existing caller's contract breaks.
            boolean metUitleg = "true".equalsIgnoreCase(uitleg) || (uitleg != null && uitleg.isEmpty());
            ObjectNode antwoord = kern.verwerk(pad, boom, metUitleg);
            return response(200, antwoord);
        } catch (BeslisserKern.OnbekendPad onbekend) {
            return response(404, foutBody(onbekend.getMessage(), null));
        } catch (DienstFout dienstFout) {
            return response(400, verzoekFoutBody(dienstFout));
        }
    }

    // ---------------- GET /info ----------------

    @GET
    @Path("info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response info() {
        return response(200, InfoBouwer.bouw(kern));
    }

    // ---------------- error bodies (mirror DienstHttp) ----------------

    private static ObjectNode foutBody(String boodschap, String veld) {
        ObjectNode n = NF.objectNode();
        n.put("fout", boodschap);
        if (veld != null) n.put("veld", veld);
        return n;
    }

    private static ObjectNode verzoekFoutBody(DienstFout fout) {
        ObjectNode n = NF.objectNode();
        n.put("fout", "verzoek valideert niet");
        var schendingen = n.putArray("schendingen");
        for (Schending s : fout.schendingen()) {
            ObjectNode sn = schendingen.addObject();
            sn.put("veld", s.veld());
            sn.put("fout", s.bericht());
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
            return Response.status(500).entity("{\"fout\":\"interne fout\"}").build();
        }
    }
}
