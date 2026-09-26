package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Eenheid;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Emits the BUILD-time OpenAPI 3.0.3 contract for a {@link DienstDescriptor} + resolved
 * {@link RegelSpraakModel} — the gate-G1 artifact. The document describes EXACTLY the request shape
 * {@link VerzoekMapper} accepts and the response shape {@link AntwoordBouwer} emits (nothing speculative):
 * one component schema per objecttype, a generic request/response schema referenced from every path, one
 * {@code POST /<pad>} per descriptor entrypoint, and a {@code GET /info} path.
 *
 * <p><b>Determinism is a requirement</b> (the golden byte-pin). Every collection is iterated in its declared
 * order ({@link LinkedHashMap} / list order as the resolver/descriptor built it), and the document is
 * serialised through a FIXED canonical serializer ({@link #schrijf}/{@link #naarBytes}): 2-space indent,
 * {@code \n} line endings, UTF-8, a single trailing newline. The same descriptor+model therefore yields the
 * same bytes forever.
 *
 * <p>The datatype→schema mapping follows the service's own typing surface (the {@link WaardeCoder} rules):
 * Numeriek geheel → integer, Numeriek/Percentage niet-geheel → number, Tekst → string, Booleaan → boolean,
 * DatumInDagen → {string, date}, DatumTijd → {string, date-time}, Enumeratie → {string, enum:[all waarden]}.
 * An eenheid-bearing attribuut documents its unit as {@code x-eenheid} PLUS a mention in the property
 * description. Kenmerken are boolean properties on the same schema (the description says which are kenmerken).
 * Dimensie- and tijdlijn-valued members are REJECTED by the service ({@link VerzoekMapper} NIET_ONDERSTEUND),
 * so no property is emitted for them — the schema description says so.
 */
public final class OpenApiEmitter {

    private OpenApiEmitter() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // ------------------------------------------------------------------ emit

    /** Build the OpenAPI 3.0.3 document for {@code descriptor} over {@code model}. */
    public static ObjectNode emit(DienstDescriptor descriptor, RegelSpraakModel model) {
        ObjectNode doc = NF.objectNode();
        doc.put("openapi", "3.0.3");

        ObjectNode info = doc.putObject("info");
        info.put("title", descriptor.dienst() == null ? "" : nvl(descriptor.dienst().naam()));
        info.put("version", descriptor.dienst() == null ? "" : nvl(descriptor.dienst().versie()));

        // paths — one POST per entrypoint (declared order), then GET /info
        ObjectNode paths = doc.putObject("paths");
        for (DienstDescriptor.Entrypoint ep : eps(descriptor))
            paths.set("/" + nvl(ep.pad()), padItem(ep, model));
        paths.set("/info", infoPad());

        // components.schemas — one per objecttype (declared order), then the generic request/response/fout
        ObjectNode components = doc.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        for (Objecttype ot : model.objecttypen.values())
            schemas.set(componentNaam(ot), objecttypeSchema(ot, model));
        schemas.set("Verzoek", verzoekSchema());
        schemas.set("Antwoord", antwoordSchema());
        schemas.set("Fout", foutSchema());
        schemas.set("Info", infoSchema());

        return doc;
    }

    // ------------------------------------------------------------------ paths

    private static ObjectNode padItem(DienstDescriptor.Entrypoint ep, RegelSpraakModel model) {
        ObjectNode item = NF.objectNode();
        ObjectNode post = item.putObject("post");
        post.put("operationId", Namen.ident(nvl(ep.naam()), false));
        post.put("summary", nvl(ep.naam()));
        post.put("description", operatieBeschrijving(ep, model));

        ObjectNode requestBody = post.putObject("requestBody");
        requestBody.put("required", true);
        jsonInhoud(requestBody.putObject("content"), "#/components/schemas/Verzoek");

        ObjectNode responses = post.putObject("responses");
        antwoord(responses, "200", "beslissing berekend", "#/components/schemas/Antwoord");
        antwoord(responses, "400", "verzoek valideert niet — het antwoord noemt het foutieve veld",
                "#/components/schemas/Fout");
        antwoord(responses, "404", "onbekend pad", "#/components/schemas/Fout");
        antwoord(responses, "503", "uchar draagt dit entrypoint niet: status MARKER, met uchars reden",
                "#/components/schemas/Fout");
        antwoord(responses, "500", "interne fout (geen stacktrace gelekt)", "#/components/schemas/Fout");
        return item;
    }

    private static ObjectNode infoPad() {
        ObjectNode item = NF.objectNode();
        ObjectNode get = item.putObject("get");
        get.put("operationId", "info");
        get.put("summary", "dienst-info");
        get.put("description",
                "De dienst-identiteit, de per-regel Dekking-verdicten, de overgeslagen (marker) regels en de "
                        + "parameter-defaults.");
        ObjectNode responses = get.putObject("responses");
        antwoord(responses, "200", "dienst-info", "#/components/schemas/Info");
        return item;
    }

    /** The per-entrypoint invoer/uitvoer documentation: which objecttypen it accepts and which
     *  objecttype.attribuut / objecttype:kenmerk selectors it returns. */
    private static String operatieBeschrijving(DienstDescriptor.Entrypoint ep, RegelSpraakModel model) {
        StringBuilder b = new StringBuilder();
        b.append("Entrypoint '").append(nvl(ep.naam())).append("'. ");
        List<String> invoer = ep.invoer() == null ? List.of() : ep.invoer();
        b.append("Invoer-objecttypen: ").append(invoer.isEmpty() ? "(geen)" : String.join(", ", invoer)).append(". ");
        List<String> uitvoer = ep.uitvoer() == null ? List.of() : ep.uitvoer();
        b.append("Uitvoer-selectoren: ").append(uitvoer.isEmpty() ? "(geen)" : String.join(", ", uitvoer)).append(".");
        return b.toString();
    }

    private static void antwoord(ObjectNode responses, String code, String beschrijving, String ref) {
        ObjectNode r = responses.putObject(code);
        r.put("description", beschrijving);
        jsonInhoud(r.putObject("content"), ref);
    }

    private static void jsonInhoud(ObjectNode content, String ref) {
        ObjectNode json = content.putObject("application/json");
        json.putObject("schema").put("$ref", ref);
    }

    // ------------------------------------------------------------------ objecttype schema

    /** The component schema for one objecttype: an object with a property per SCALAR attribuut (dimensie/
     *  tijdlijn attributen and tijdlijn-kenmerken are rejected by the service, so none is emitted) and a boolean
     *  property per (non-temporal) kenmerk. The description records the eenheid-bearing attributen, the
     *  kenmerken, and any rejected (dimensie/tijdlijn) member.
     *
     *  <p><b>IR-SPEC ch. 2.2: the member set is CHAIN-CLOSED</b> ({@link Objecttype#attribuutLeden} /
     *  {@link DescriptorLoader#kenmerkLeden}), nearest declaration first. A request instance of a subtype
     *  carries its inherited members exactly like its own ones — {@link VerzoekMapper} accepts them and
     *  {@link AntwoordBouwer} reads them off the same bean — so a document built from the OWN maps would
     *  describe a request shape the service does not have. The schema is FLAT rather than an OpenAPI
     *  {@code allOf} composition over the supertype's component: the document's contract is "exactly the
     *  request shape the mapper accepts", and the mapper has no composition either. This is the opposite
     *  choice from {@link dev.uchar.regelspraak.schema.FactModelGenerator}, which stays per
     *  declaring type BECAUSE the generated bean {@code extends} its supertype's and would hide the
     *  parent's field by redeclaring it; a JSON schema property set has no such mechanism. */
    private static ObjectNode objecttypeSchema(Objecttype ot, RegelSpraakModel model) {
        ObjectNode schema = NF.objectNode();
        schema.put("type", "object");

        List<String> kenmerken = new ArrayList<>();
        List<String> afgewezen = new ArrayList<>();
        ObjectNode props = NF.objectNode();

        // attributen (own first, then inherited — declared order within each)
        for (var e : ot.attribuutLeden().entrySet()) {
            String naam = e.getKey();
            if (!ot.dimensiesVan(naam).isEmpty()) { afgewezen.add(naam + " (dimensie)"); continue; }
            if (temporeelAttribuut(ot, naam))      { afgewezen.add(naam + " (tijdlijn)"); continue; }
            props.set(naam, attribuutSchema(e.getValue()));
        }
        // kenmerken (own first, then inherited) — boolean properties
        for (String k : DescriptorLoader.kenmerkLeden(ot)) {
            if (temporeelKenmerk(ot, k)) { afgewezen.add(k + " (tijdlijn-kenmerk)"); continue; }
            ObjectNode kn = NF.objectNode();
            kn.put("type", "boolean");
            kn.put("description", "kenmerk");
            props.set(k, kn);
            kenmerken.add(k);
        }
        schema.set("properties", props);
        schema.put("description", objecttypeBeschrijving(ot, kenmerken, afgewezen));
        return schema;
    }

    private static String objecttypeBeschrijving(Objecttype ot, List<String> kenmerken, List<String> afgewezen) {
        StringBuilder b = new StringBuilder();
        b.append("Objecttype '").append(ot.naam).append("'.");
        if (!kenmerken.isEmpty())
            b.append(" Kenmerken (boolean-eigenschappen): ").append(String.join(", ", kenmerken)).append(".");
        if (!afgewezen.isEmpty())
            b.append(" Dimensie-/tijdlijn-gewaardeerde leden worden door de dienst-kern niet ondersteund en"
                    + " zijn hier niet als eigenschap opgenomen: ").append(String.join(", ", afgewezen)).append(".");
        return b.toString();
    }

    /** Map a scalar attribuut's datatype onto its JSON-schema fragment, documenting an eenheid via
     *  {@code x-eenheid} + a description mention. */
    private static ObjectNode attribuutSchema(Datatype dt) {
        ObjectNode s = NF.objectNode();
        Eenheid eenheid = null;
        switch (dt) {
            case Datatype.Numeriek n -> {
                s.put("type", n.geheel() ? "integer" : "number");
                eenheid = n.eenheid();
            }
            case Datatype.Percentage p -> {
                s.put("type", p.geheel() ? "integer" : "number");
                eenheid = p.eenheid();
            }
            case Datatype.Tekst t -> s.put("type", "string");
            case Datatype.Booleaan b -> s.put("type", "boolean");
            case Datatype.DatumInDagen d -> { s.put("type", "string"); s.put("format", "date"); }
            case Datatype.DatumTijd d -> { s.put("type", "string"); s.put("format", "date-time"); }
            case Datatype.Enumeratie e -> {
                s.put("type", "string");
                ArrayNode en = s.putArray("enum");
                for (String w : e.waarden()) en.add(w);
            }
            case Datatype.Onbekend o -> s.put("type", "string");
        }
        if (eenheid != null) {
            String weergave = eenheid.weergave();
            s.put("x-eenheid", weergave);
            s.put("description", "eenheid: " + weergave
                    + " (een getal in deze eenheid, of {\"waarde\": n, \"eenheid\": \"" + weergave + "\"})");
        }
        return s;
    }

    private static String componentNaam(Objecttype ot) { return Namen.ident(ot.naam, true); }

    /** §13.3.6 whether attribuut {@code naam} rides a tijdlijn ANYWHERE on {@code ot}'s chain. Reading the own
     *  maps would call an INHERITED timeline scalar and emit a plain property for it — a contract the service
     *  then refuses at request time ({@link VerzoekMapper} NIET_ONDERSTEUND). */
    private static boolean temporeelAttribuut(Objecttype ot, String naam) {
        return ot.granulariteitVan(naam) != null || ot.tijdlijnAantalVan(naam) != null;
    }

    /** The kenmerk twin of {@link #temporeelAttribuut}, chain-closed for the same reason. */
    private static boolean temporeelKenmerk(Objecttype ot, String naam) {
        return ot.kenmerkGranulariteitVan(naam) != null || ot.kenmerkTijdlijnAantalVan(naam) != null;
    }

    // ------------------------------------------------------------------ generic request/response/fout schemas

    /** The generic request schema, honest to {@link VerzoekMapper}: instanties[{type,id,attributen,kenmerken,
     *  rollen}], relaties[{feittype,rollen}], parameters (free-form object), rekendatum (ISO date). */
    private static ObjectNode verzoekSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Een beslissingsverzoek: gelabelde object-instanties met attribuut-/kenmerk-waarden"
                + " en rol-relaties, plus optionele parameter-overrides en rekendatum.");
        ArrayNode req = s.putArray("required");
        req.add("instanties");
        ObjectNode props = s.putObject("properties");

        // peildatum: the ONE date authority (fase 5). It IS the rekendatum — a rule reading Rekendatum and the
        // parameterset-vintage selection follow this same date; 'rekendatum' remains as an alias.
        ObjectNode peildatum = props.putObject("peildatum");
        peildatum.put("type", "string");
        peildatum.put("format", "date");
        peildatum.put("description", "ISO-datum (yyyy-MM-dd); optioneel. De ÉÉN datumautoriteit: dezelfde datum"
                + " selecteert de parameterset-vintage EN is de rekendatum-global. Gelijk aan 'rekendatum'"
                + " (alias); beide meegeven met verschillende waarden is een 400.");

        ObjectNode rekendatum = props.putObject("rekendatum");
        rekendatum.put("type", "string");
        rekendatum.put("format", "date");
        rekendatum.put("description", "ISO-datum (yyyy-MM-dd); optioneel. Alias van 'peildatum' (dezelfde"
                + " datumautoriteit).");

        ObjectNode parameters = props.putObject("parameters");
        parameters.put("type", "object");
        parameters.put("additionalProperties", true);
        parameters.put("description", "parameternaam -> waarde; overschrijft de descriptor-defaults.");

        // instanties[]
        ObjectNode instanties = props.putObject("instanties");
        instanties.put("type", "array");
        ObjectNode inst = instanties.putObject("items");
        inst.put("type", "object");
        ArrayNode instReq = inst.putArray("required");
        instReq.add("type"); instReq.add("id");
        ObjectNode instProps = inst.putObject("properties");
        instProps.putObject("type").put("type", "string");
        instProps.putObject("id").put("type", "string");
        vrijObject(instProps.putObject("attributen"),
                "attribuutnaam -> waarde (per het objecttype-schema).");
        vrijObject(instProps.putObject("kenmerken"),
                "kenmerknaam -> boolean.");
        vrijObject(instProps.putObject("rollen"),
                "rolnaam -> doel-id of lijst van doel-ids.");

        // relaties[]
        ObjectNode relaties = props.putObject("relaties");
        relaties.put("type", "array");
        ObjectNode rel = relaties.putObject("items");
        rel.put("type", "object");
        ArrayNode relReq = rel.putArray("required");
        relReq.add("feittype"); relReq.add("rollen");
        ObjectNode relProps = rel.putObject("properties");
        relProps.putObject("feittype").put("type", "string");
        vrijObject(relProps.putObject("rollen"), "rolnaam -> id of lijst van ids.");
        return s;
    }

    /** The generic response schema, honest to {@link AntwoordBouwer}: instanties[{id,type,attributen,kenmerken}]
     *  + inconsistenties[string] + inconsistentieDetails[{regel,onderwerp}]. */
    private static ObjectNode antwoordSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Het beslissingsantwoord: per instantie de gevraagde uitvoer-attributen/-kenmerken,"
                + " plus de namen van de regels waarvan een consistentietoets faalde.");
        ObjectNode props = s.putObject("properties");

        ObjectNode instanties = props.putObject("instanties");
        instanties.put("type", "array");
        ObjectNode inst = instanties.putObject("items");
        inst.put("type", "object");
        ObjectNode instProps = inst.putObject("properties");
        instProps.putObject("id").put("type", "string");
        instProps.putObject("type").put("type", "string");
        vrijObject(instProps.putObject("attributen"), "attribuutnaam -> waarde (of null bij leeg).");
        vrijObject(instProps.putObject("kenmerken"), "kenmerknaam -> boolean.");

        ObjectNode inc = props.putObject("inconsistenties");
        inc.put("type", "array");
        inc.putObject("items").put("type", "string");
        inc.put("description", "de namen van de regels waarvan een consistentietoets INCONSISTENT opleverde.");

        ObjectNode details = props.putObject("inconsistentieDetails");
        details.put("type", "array");
        ObjectNode detail = details.putObject("items");
        detail.put("type", "object");
        ObjectNode detailProps = detail.putObject("properties");
        detailProps.putObject("regel").put("type", "string");
        detailProps.putObject("onderwerp").put("type", "string").put("nullable", true);
        details.put("description", "dezelfde inconsistenties met hun onderwerp: het id van de instantie waarover de"
                + " toets ging, of null als dat geen instantie van het verzoek is.");
        return s;
    }

    /** The error schema returned as a 4xx/5xx body: {fout, veld?, schendingen?}. */
    private static ObjectNode foutSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Een dienst-fout; noemt waar mogelijk het foutieve veld/pad.");
        ArrayNode req = s.putArray("required");
        req.add("fout");
        ObjectNode props = s.putObject("properties");
        props.putObject("fout").put("type", "string");
        props.putObject("veld").put("type", "string");
        ObjectNode sch = props.putObject("schendingen");
        sch.put("type", "array");
        sch.putObject("items").put("type", "object");
        sch.put("description", "de verzameling schendingen, elk met soort/veld/melding.");
        return s;
    }

    /** The /info response schema (minimal, honest to fase-3 /info). */
    private static ObjectNode infoSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De dienst-info: identiteit, per-regel Dekking, overgeslagen markers, parameters.");
        ObjectNode props = s.putObject("properties");
        props.putObject("dienst").put("type", "object");
        props.putObject("dekking").put("type", "object");
        ObjectNode overgeslagen = props.putObject("overgeslagen");
        overgeslagen.put("type", "array");
        overgeslagen.putObject("items").put("type", "string");
        props.putObject("parameters").put("type", "object");
        // fase 5: the loaded parametersets with their validity ranges, and the period-coverage WARNINGs.
        props.putObject("parametersets").put("type", "array");
        ObjectNode pw = props.putObject("periodeWaarschuwingen");
        pw.put("type", "array");
        pw.putObject("items").put("type", "string");
        return s;
    }

    private static void vrijObject(ObjectNode node, String beschrijving) {
        node.put("type", "object");
        node.put("additionalProperties", true);
        node.put("description", beschrijving);
    }

    // ------------------------------------------------------------------ canonical serialization

    /** Serialise {@code doc} to canonical bytes: 2-space indent, {@code \n} newlines, UTF-8, ONE trailing
     *  newline. Deterministic for a given node tree (Jackson emits object fields in insertion order). */
    public static byte[] naarBytes(ObjectNode doc) {
        JsonFactory factory = new JsonFactory();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (JsonGenerator gen = factory.createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(canonicalePrinter());
            new ObjectMapper().writeTree(gen, doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Jackson's DefaultPrettyPrinter does not add a trailing newline — add exactly one.
        byte[] body = out.toByteArray();
        byte[] met = new byte[body.length + 1];
        System.arraycopy(body, 0, met, 0, body.length);
        met[body.length] = '\n';
        return met;
    }

    /** A FIXED pretty printer: 2-space object/array indent, {@code \n} line separator, a single space after the
     *  {@code ":"} of an object entry — reproducible forever, independent of platform line endings. */
    private static PrettyPrinter canonicalePrinter() {
        // "key": value — a single space AFTER the ':' only (no leading space), and a "\n" newline everywhere.
        com.fasterxml.jackson.core.util.Separators seps =
                com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(com.fasterxml.jackson.core.util.Separators.Spacing.AFTER);
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter(seps);
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indent);
        pp.indentArraysWith(indent);
        return pp;
    }

    /** Emit the document for {@code descriptor}+{@code model} and write its canonical bytes to {@code doel}
     *  (creating parent directories). */
    public static void schrijf(DienstDescriptor descriptor, RegelSpraakModel model, Path doel) {
        try {
            if (doel.getParent() != null) Files.createDirectories(doel.getParent());
            Files.write(doel, naarBytes(emit(descriptor, model)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The kebab-case file name a dienst's document lands under ({@code <kebab(naam)>.json}). */
    public static String bestandsnaam(DienstDescriptor descriptor) {
        String naam = descriptor.dienst() == null ? "dienst" : nvl(descriptor.dienst().naam());
        String kebab = naam.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return (kebab.isEmpty() ? "dienst" : kebab) + ".json";
    }

    private static List<DienstDescriptor.Entrypoint> eps(DienstDescriptor d) {
        return d.entrypoints() == null ? List.of() : d.entrypoints();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
