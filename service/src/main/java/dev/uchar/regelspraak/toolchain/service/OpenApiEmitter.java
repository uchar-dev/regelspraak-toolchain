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

import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Unit;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Namen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Emits the BUILD-time OpenAPI 3.0.3 contract for a {@link ServiceDescriptor} + resolved
 * {@link RegelSpraakModel} — the gate-G1 artifact. The document describes EXACTLY the request shape
 * {@link RequestMapper} accepts and the response shape {@link AnswerBuilder} emits (nothing speculative):
 * one component schema per objecttype, a generic request/response schema referenced from every path, one
 * {@code POST /<pad>} per descriptor entrypoint, and a {@code GET /info} path.
 *
 * <p><b>Determinism is a requirement</b> (the golden byte-pin). Every collection is iterated in its declared
 * order ({@link LinkedHashMap} / list order as the resolver/descriptor built it), and the document is
 * serialised through a FIXED canonical serializer ({@link #write}/{@link #toBytes}): 2-space indent,
 * {@code \n} line endings, UTF-8, a single trailing newline. The same descriptor+model therefore yields the
 * same bytes forever.
 *
 * <p>The datatype→schema mapping follows the service's own typing surface (the {@link ValueCoder} rules):
 * Numeriek geheel → integer, Numeriek/Percentage niet-geheel → number, Tekst → string, Booleaan → boolean,
 * DatumInDagen → {string, date}, DatumTijd → {string, date-time}, Enumeratie → {string, enum:[all waarden]}.
 * An eenheid-bearing attribuut documents its unit as {@code x-eenheid} PLUS a mention in the property
 * description. Kenmerken are boolean properties on the same schema (the description says which are kenmerken).
 * Dimensie- and tijdlijn-valued members are REJECTED by the service ({@link RequestMapper} NIET_ONDERSTEUND),
 * so no property is emitted for them — the schema description says so.
 *
 * <p><b>Per vocabulary.</b> {@link #emit(ServiceDescriptor, RegelSpraakModel, String)} writes the document of one
 * vocabulary the model declares, or of the canonical names: the object types, properties, enumeration values and
 * selectors go by their names in it ({@link Naming}). The service reads every other spelling too
 * ({@link NameReader}), so for a model with vocabularies each object type, property and entry point lists its other
 * spellings — the canonical one and those of the other vocabularies — in {@code x-spellingen}, each enumeration its
 * values' in {@code x-waardespellingen}, and says in its description that the service accepts them; the operations
 * document the query parameters {@code vocabulaire} and {@code invoervocabulaire}, and the info block names the
 * document's vocabulary in {@code x-vocabulaire}. A model without vocabularies gets none of this, so its document is
 * the one it always was.
 *
 * <p><b>What a request asks beside its body.</b> Every operation documents the flag {@code uitleg} and the detail flags
 * {@code trace}, {@code dekking} and {@code fixpunt}, which a service gives only when it was started with detail
 * ({@link CoreConfig#detail()}) and otherwise refuses with a 400; the fields they add stand in {@code Answer} and, for
 * the detail, in {@code EvaluationFault}, with the components {@code Trace}, {@code Dekking} and {@code Fixpunt}.
 *
 * <p><b>The 422.</b> A request on which the rules give no answer gets one of two bodies: {@code EvaluationFault}, the faults
 * that stand at the fixpoint, or {@code NoFixpoint}, a session that reached no fixpoint, with uchar's sentence.
 */
public final class OpenApiEmitter {

    private OpenApiEmitter() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    // ------------------------------------------------------------------ emit

    /** Build the OpenAPI 3.0.3 document for {@code descriptor} over {@code model}, in the canonical names. */
    public static ObjectNode emit(ServiceDescriptor descriptor, RegelSpraakModel model) {
        return emit(descriptor, model, null);
    }

    /** Build the document in {@code vocabulaire}: one the model declares, or null or {@link Naming#CANONICAL} for the
     *  canonical names. Another vocabulary is a {@link Naming.VocabularyError}. */
    public static ObjectNode emit(ServiceDescriptor descriptor, RegelSpraakModel model, String vocabulary) {
        Spellings sp = new Spellings(model, Naming.van(model, vocabulary));
        ObjectNode doc = NF.objectNode();
        doc.put("openapi", "3.0.3");

        ObjectNode info = doc.putObject("info");
        info.put("title", descriptor.service() == null ? "" : nvl(descriptor.service().name()));
        info.put("version", descriptor.service() == null ? "" : nvl(descriptor.service().version()));
        if (sp.enabled()) {
            info.put("description", sp.documentDescription());
            info.put("x-vocabulaire", sp.in.choice());
        }

        // paths — one POST per entrypoint (declared order), then GET /info
        ObjectNode paths = doc.putObject("paths");
        for (ServiceDescriptor.Entrypoint ep : eps(descriptor))
            paths.set("/" + nvl(ep.path()), pathItem(ep, model, sp));
        paths.set("/info", infoPath());

        // components.schemas — one per objecttype (declared order), then the generic request/response/fout
        ObjectNode components = doc.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        for (Objecttype ot : model.objecttypen.values())
            schemas.set(componentName(ot, sp.in), objectTypeSchema(ot, sp));
        schemas.set("Verzoek", requestSchema());
        schemas.set("Antwoord", answerSchema());
        schemas.set("Fout", errorSchema());
        schemas.set("Evaluatiefout", evaluationFaultSchema());
        schemas.set("Info", infoSchema(sp));
        schemas.set("Trace", traceSchema());
        schemas.set("Dekking", coverageSchema());
        schemas.set("Fixpunt", fixpointSchema());
        schemas.set("GeenFixpunt", noFixpointSchema());

        return doc;
    }

    // ------------------------------------------------------------------ the other spellings

    /** The names of the document ({@code in}) and, for a model with vocabularies, the other spellings of each
     *  declaration: its canonical name and its effective name in every other vocabulary, the names the service reads
     *  as well. */
    private static final class Spellings {
        private final RegelSpraakModel model;
        private final Naming in;
        private final List<Naming> other = new ArrayList<>();

        Spellings(RegelSpraakModel model, Naming in) {
            this.model = model;
            this.in = in;
            if (model.vocabularies.isEmpty()) return;
            if (!in.isCanonical()) other.add(Naming.canonical(model));
            for (RegelSpraakModel.Vocabulary v : model.vocabularies)
                if (!v.naam().equals(in.choice())) other.add(Naming.van(model, v.naam()));
        }

        /** Whether the document lists other spellings: the model declares vocabularies. */
        boolean enabled() { return !model.vocabularies.isEmpty(); }

        /** {@code {<keuze>: <naam>}} of declaration {@code b} in the other spellings. */
        ObjectNode van(Designatable b) {
            ObjectNode sink = NF.objectNode();
            for (Naming n : other) sink.put(n.choice(), n.name(b));
            return sink;
        }

        /** The sentence a description ends with: the other spellings, which the service accepts as well. */
        static String sentence(ObjectNode spellings) {
            List<String> parts = new ArrayList<>();
            spellings.fields().forEachRemaining(e -> parts.add("'" + e.getValue().asText() + "' (" + e.getKey() + ")"));
            return "De dienst neemt ook " + enumeration(parts) + " aan.";
        }

        /** The info block's description: whose names the document carries, and what else the service reads. */
        String documentDescription() {
            List<String> vocabularies = model.vocabularies.stream().map(v -> "'" + v.naam() + "'").toList();
            return (in.isCanonical() ? "De namen in dit document zijn de canonieke namen."
                    : "De namen in dit document zijn die van het vocabulaire '" + in.choice() + "'.")
                    + " De dienst neemt in een verzoek elke naam aan: canoniek of als benaming in "
                    + (vocabularies.size() == 1 ? "het vocabulaire " : "de vocabulaires ") + enumeration(vocabularies)
                    + ", ook gemengd; x-spellingen en x-waardespellingen noemen ze."
                    + " Een naam die zo twee declaraties noemt is een 400 (DUBBELZINNIGE_NAAM), tenzij de queryparameter"
                    + " invoervocabulaire het vocabulaire vastzet; de queryparameter vocabulaire kiest de namen van het"
                    + " antwoord. Een gewijzigde benaming is een wijziging van dit contract.";
        }

        private static String enumeration(List<String> parts) {
            if (parts.size() == 1) return parts.get(0);
            return String.join(", ", parts.subList(0, parts.size() - 1)) + " en " + parts.get(parts.size() - 1);
        }

        /** The choices of the query parameters: the canonical names and every vocabulary. */
        List<String> choices() {
            List<String> sink = new ArrayList<>();
            sink.add(Naming.CANONICAL);
            for (RegelSpraakModel.Vocabulary v : model.vocabularies) sink.add(v.naam());
            return sink;
        }
    }

    // ------------------------------------------------------------------ paths

    private static ObjectNode pathItem(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model, Spellings sp) {
        ObjectNode item = NF.objectNode();
        ObjectNode post = item.putObject("post");
        post.put("operationId", Namen.ident(nvl(ep.name()), false));
        post.put("summary", nvl(ep.name()));
        post.put("description", operationDescription(ep, model, sp));
        ArrayNode parameters = NF.arrayNode();
        if (sp.enabled()) {
            ObjectNode spellings = post.putObject("x-spellingen");
            for (Naming n : sp.other) {
                ObjectNode s = spellings.putObject(n.choice());
                ArrayNode input = s.putArray("invoer");
                for (String i : input(ep, model, n)) input.add(i);
                ArrayNode output = s.putArray("uitvoer");
                for (String u : output(ep, model, n)) output.add(u);
            }
            parameters.add(queryParameter("vocabulaire", "Het vocabulaire waarin het antwoord de declaraties noemt;"
                    + " zonder: " + (ep.vocabulary() == null ? "de canonieke namen"
                    : "'" + ep.vocabulary() + "', de standaard van dit entrypoint") + ". Een declaratie zonder"
                    + " benaming in het gekozen vocabulaire draagt haar canonieke naam; GET /info noemt die declaraties"
                    + " per vocabulaire. Een onbekend vocabulaire is een 400 (ONBEKEND_VOCABULAIRE).", sp.choices()));
            parameters.add(queryParameter("invoervocabulaire", "Zet het vocabulaire vast waarin de namen van het"
                    + " verzoek gelezen worden; zonder wordt elke naam gelezen als canonieke naam of als benaming in"
                    + " elk vocabulaire.", sp.choices()));
        }
        parameters.add(flagParameter("uitleg", "true: het antwoord krijgt uitleg, een zin per toegekend of onthouden"
                + " kenmerk van een instantie van het verzoek, met de waarden waarop hij rust."));
        parameters.add(flagParameter("trace", "true: het antwoord krijgt trace (schema Trace): per vorm die vuurde de"
                + " ids van het verzoek die haar regels bonden en hoe vaak ze vuurden." + DETAIL));
        parameters.add(flagParameter("dekking", "true: het antwoord krijgt dekking (schema Dekking): per vorm van dit"
                + " entrypoint of een regel ervan vuurde." + DETAIL));
        parameters.add(flagParameter("fixpunt", "true: het antwoord krijgt fixpunt (schema Fixpunt): wat aan het"
                + " fixpunt staat, met de instanties van het verzoek als invoer." + DETAIL));
        post.set("parameters", parameters);

        ObjectNode requestBody = post.putObject("requestBody");
        requestBody.put("required", true);
        jsonContent(requestBody.putObject("content"), "#/components/schemas/Verzoek");

        ObjectNode responses = post.putObject("responses");
        answer(responses, "200", "beslissing berekend", "#/components/schemas/Antwoord");
        answer(responses, "400", "verzoek valideert niet — het antwoord noemt het foutieve veld",
                "#/components/schemas/Fout");
        answer(responses, "404", "onbekend pad", "#/components/schemas/Fout");
        ObjectNode unanswered = responses.putObject("422");
        unanswered.put("description", "de regels geven op deze invoer een evaluatiefout: status EVALUATIEFOUT, per fout"
                + " de soort en uchars zin; of de sessie bereikt geen fixpunt: status GEEN_FIXPUNT, met uchars zin");
        ArrayNode oneOf = unanswered.putObject("content").putObject("application/json").putObject("schema")
                .putArray("oneOf");
        oneOf.addObject().put("$ref", "#/components/schemas/Evaluatiefout");
        oneOf.addObject().put("$ref", "#/components/schemas/GeenFixpunt");
        answer(responses, "503", "uchar draagt dit entrypoint niet: status MARKER, met uchars reden",
                "#/components/schemas/Fout");
        answer(responses, "500", "interne fout (geen stacktrace gelekt)", "#/components/schemas/Fout");
        return item;
    }

    private static ObjectNode infoPath() {
        ObjectNode item = NF.objectNode();
        ObjectNode get = item.putObject("get");
        get.put("operationId", "info");
        get.put("summary", "dienst-info");
        get.put("description",
                "De dienst-identiteit, de per-regel Dekking-verdicten, de overgeslagen (marker) regels en de "
                        + "parameter-defaults.");
        ObjectNode responses = get.putObject("responses");
        answer(responses, "200", "dienst-info", "#/components/schemas/Info");
        return item;
    }

    /** The per-entrypoint invoer/uitvoer documentation: which objecttypen it accepts and which
     *  objectType.attribute / objecttype:kenmerk selectors it returns, in the document's names. */
    private static String operationDescription(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model, Spellings sp) {
        StringBuilder b = new StringBuilder();
        b.append("Entrypoint '").append(nvl(ep.name())).append("'. ");
        List<String> input = input(ep, model, sp.in);
        b.append("Invoer-objecttypen: ").append(input.isEmpty() ? "(geen)" : String.join(", ", input)).append(". ");
        List<String> output = output(ep, model, sp.in);
        b.append("Uitvoer-selectoren: ").append(output.isEmpty() ? "(geen)" : String.join(", ", output)).append(".");
        if (sp.enabled()) b.append(" De dienst neemt ook de spellingen van x-spellingen aan.");
        return b.toString();
    }

    /** The invoer object types of {@code ep} in the names of {@code n}; canonically as the descriptor writes them. */
    private static List<String> input(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model, Naming n) {
        List<String> sink = new ArrayList<>();
        for (String inv : ep.input() == null ? List.<String>of() : ep.input()) {
            Objecttype ot = DescriptorLoader.objectType(model, inv);
            sink.add(n.isCanonical() || ot == null ? inv : n.objectType(ot.naam));
        }
        return sink;
    }

    /** The uitvoer selectors of {@code ep} in the names of {@code n}; canonically as the descriptor writes them. */
    private static List<String> output(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model, Naming n) {
        List<String> sink = new ArrayList<>();
        for (String sel : ep.output() == null ? List.<String>of() : ep.output()) {
            OutputSelector u = OutputSelector.parse(sel);
            Objecttype ot = u == null ? null : DescriptorLoader.objectType(model, u.objectType());
            if (n.isCanonical() || ot == null) { sink.add(sel); continue; }
            sink.add(n.objectType(ot.naam) + (u.characteristic() ? ":" + n.characteristic(ot, u.member()) : "." + n.attribute(ot, u.member())));
        }
        return sink;
    }

    /** What every detail flag says: the service gives it only when started with detail, and a 422 gets it too. */
    private static final String DETAIL = " De dienst geeft dit alleen als hij met DETAIL gestart is; anders is de"
            + " vlag een 400 (NIET_ONDERSTEUND, met de naam van de vlag als veld). Een 422 krijgt het veld ook.";

    /** A query flag: {@code true} (case-insensitive) or the bare name sets it, any other value leaves it off. */
    private static ObjectNode flagParameter(String name, String description) {
        ObjectNode p = NF.objectNode();
        p.put("name", name);
        p.put("in", "query");
        p.put("required", false);
        p.put("description", description);
        p.putObject("schema").put("type", "boolean");
        return p;
    }

    private static ObjectNode queryParameter(String name, String description, List<String> choices) {
        ObjectNode p = NF.objectNode();
        p.put("name", name);
        p.put("in", "query");
        p.put("required", false);
        p.put("description", description);
        ObjectNode schema = p.putObject("schema");
        schema.put("type", "string");
        ArrayNode enumArray = schema.putArray("enum");
        for (String k : choices) enumArray.add(k);
        return p;
    }

    private static void answer(ObjectNode responses, String code, String description, String ref) {
        ObjectNode r = responses.putObject(code);
        r.put("description", description);
        jsonContent(r.putObject("content"), ref);
    }

    private static void jsonContent(ObjectNode content, String ref) {
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
     *  {@link DescriptorLoader#characteristicMembers}), nearest declaration first. A request instance of a subtype
     *  carries its inherited members exactly like its own ones — {@link RequestMapper} accepts them and
     *  {@link AnswerBuilder} reads them off the same bean — so a document built from the OWN maps would
     *  describe a request shape the service does not have. The schema is FLAT rather than an OpenAPI
     *  {@code allOf} composition over the supertype's component: the document's contract is "exactly the
     *  request shape the mapper accepts", and the mapper has no composition either. This is the opposite
     *  choice from {@link dev.uchar.regelspraak.schema.FactModelGenerator}, which stays per
     *  declaring type BECAUSE the generated bean {@code extends} its supertype's and would hide the
     *  parent's field by redeclaring it; a JSON schema property set has no such mechanism. */
    private static ObjectNode objectTypeSchema(Objecttype ot, Spellings sp) {
        ObjectNode schema = NF.objectNode();
        schema.put("type", "object");

        List<String> characteristics = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        ObjectNode props = NF.objectNode();

        // attributen (own first, then inherited — declared order within each)
        for (var e : ot.attributeMembers().entrySet()) {
            String name = sp.in.attribute(ot, e.getKey());
            if (!ot.dimensionsOf(e.getKey()).isEmpty()) { rejected.add(name + " (dimensie)"); continue; }
            if (temporalAttribute(ot, e.getKey()))      { rejected.add(name + " (tijdlijn)"); continue; }
            ObjectNode attribute = attributeSchema(e.getValue(), sp);
            if (sp.enabled()) spellings(attribute, sp.van(Naming.attributeOf(ot, e.getKey())));
            props.set(name, attribute);
        }
        // kenmerken (own first, then inherited) — boolean properties
        for (String k : DescriptorLoader.characteristicMembers(ot)) {
            String name = sp.in.characteristic(ot, k);
            if (temporalCharacteristic(ot, k)) { rejected.add(name + " (tijdlijn-kenmerk)"); continue; }
            ObjectNode kn = NF.objectNode();
            kn.put("type", "boolean");
            kn.put("description", "kenmerk");
            if (sp.enabled()) spellings(kn, sp.van(Naming.characteristicOf(ot, k)));
            props.set(name, kn);
            characteristics.add(name);
        }
        schema.set("properties", props);
        schema.put("description", objectTypeDescription(ot, sp.in, characteristics, rejected));
        if (sp.enabled()) spellings(schema, sp.van(Designatable.objectType(ot.naam)));
        return schema;
    }

    /** {@code node} lists {@code spellings} in {@code x-spellingen}, and its description says the service accepts
     *  them (a description of its own is kept in front). */
    private static void spellings(ObjectNode node, ObjectNode spellings) {
        String sentence = Spellings.sentence(spellings);
        String own = node.has("description") ? node.get("description").asText() : "";
        node.put("description", own.isEmpty() ? sentence : own + (own.endsWith(".") ? " " : ". ") + sentence);
        node.set("x-spellingen", spellings);
    }

    private static String objectTypeDescription(Objecttype ot, Naming in, List<String> characteristics,
                                                 List<String> rejected) {
        StringBuilder b = new StringBuilder();
        b.append("Objecttype '").append(in.objectType(ot.naam)).append("'.");
        if (!characteristics.isEmpty())
            b.append(" Kenmerken (boolean-eigenschappen): ").append(String.join(", ", characteristics)).append(".");
        if (!rejected.isEmpty())
            b.append(" Dimensie-/tijdlijn-gewaardeerde leden worden door de dienst-kern niet ondersteund en"
                    + " zijn hier niet als eigenschap opgenomen: ").append(String.join(", ", rejected)).append(".");
        return b.toString();
    }

    /** Map a scalar attribuut's datatype onto its JSON-schema fragment, documenting an eenheid via
     *  {@code x-eenheid} + a description mention; an enumeration's values in the document's names, and for a model
     *  with vocabularies their other spellings in {@code x-waardespellingen}. */
    private static ObjectNode attributeSchema(Datatype dt, Spellings sp) {
        ObjectNode s = NF.objectNode();
        Unit unit = null;
        switch (dt) {
            case Datatype.Numeriek n -> {
                s.put("type", n.geheel() ? "integer" : "number");
                unit = n.eenheid();
            }
            case Datatype.Percentage p -> {
                s.put("type", p.geheel() ? "integer" : "number");
                unit = p.eenheid();
            }
            case Datatype.Tekst t -> s.put("type", "string");
            case Datatype.BooleanType b -> s.put("type", "boolean");
            case Datatype.DatumInDagen d -> { s.put("type", "string"); s.put("format", "date"); }
            case Datatype.DateTime d -> { s.put("type", "string"); s.put("format", "date-time"); }
            case Datatype.Enumeratie e -> {
                s.put("type", "string");
                ArrayNode enumArray = s.putArray("enum");
                for (String w : e.waarden()) enumArray.add(sp.in.value(e, w));
                if (sp.enabled()) {
                    ObjectNode values = s.putObject("x-waardespellingen");
                    for (String w : e.waarden())
                        values.set(sp.in.value(e, w), sp.van(Designatable.enumerationValue(e.domain(), w)));
                }
            }
            case Datatype.Unknown o -> s.put("type", "string");
        }
        if (unit != null) {
            String rendering = unit.rendering();
            s.put("x-eenheid", rendering);
            s.put("description", "eenheid: " + rendering
                    + " (een getal in deze eenheid, of {\"waarde\": n, \"eenheid\": \"" + rendering + "\"})");
        }
        return s;
    }

    private static String componentName(Objecttype ot, Naming in) { return Namen.ident(in.objectType(ot.naam), true); }

    /** §13.3.6 whether attribuut {@code naam} rides a tijdlijn ANYWHERE on {@code ot}'s chain. Reading the own
     *  maps would call an INHERITED timeline scalar and emit a plain property for it — a contract the service
     *  then refuses at request time ({@link RequestMapper} NIET_ONDERSTEUND). */
    private static boolean temporalAttribute(Objecttype ot, String name) {
        return ot.granularityOf(name) != null || ot.timelineCountOf(name) != null;
    }

    /** The kenmerk twin of {@link #temporalAttribute}, chain-closed for the same reason. */
    private static boolean temporalCharacteristic(Objecttype ot, String name) {
        return ot.characteristicGranularityOf(name) != null || ot.characteristicTimelineCountOf(name) != null;
    }

    // ------------------------------------------------------------------ generic request/response/fout schemas

    /** The generic request schema, honest to {@link RequestMapper}: instanties[{type,id,attributen,kenmerken,
     *  rollen}], relaties[{feittype,rollen}], parameters (free-form object), rekendatum (ISO date). */
    private static ObjectNode requestSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Een beslissingsverzoek: gelabelde object-instanties met attribuut-/kenmerk-waarden"
                + " en rol-relaties, plus optionele parameter-overrides en rekendatum.");
        ArrayNode req = s.putArray("required");
        req.add("instanties");
        ObjectNode props = s.putObject("properties");

        // peildatum: the ONE date authority (fase 5). It IS the rekendatum — a rule reading Rekendatum and the
        // parameterset-vintage selection follow this same date; 'rekendatum' remains as an alias.
        ObjectNode asOfDate = props.putObject("peildatum");
        asOfDate.put("type", "string");
        asOfDate.put("format", "date");
        asOfDate.put("description", "ISO-datum (yyyy-MM-dd); optioneel. De ÉÉN datumautoriteit: dezelfde datum"
                + " selecteert de parameterset-vintage EN is de rekendatum-global. Gelijk aan 'rekendatum'"
                + " (alias); beide meegeven met verschillende waarden is een 400.");

        ObjectNode evaluationDate = props.putObject("rekendatum");
        evaluationDate.put("type", "string");
        evaluationDate.put("format", "date");
        evaluationDate.put("description", "ISO-datum (yyyy-MM-dd); optioneel. Alias van 'peildatum' (dezelfde"
                + " datumautoriteit).");

        ObjectNode parameters = props.putObject("parameters");
        parameters.put("type", "object");
        parameters.put("additionalProperties", true);
        parameters.put("description", "parameternaam -> waarde; overschrijft de descriptor-defaults.");

        // instanties[]
        ObjectNode instances = props.putObject("instanties");
        instances.put("type", "array");
        ObjectNode inst = instances.putObject("items");
        inst.put("type", "object");
        ArrayNode instReq = inst.putArray("required");
        instReq.add("type"); instReq.add("id");
        ObjectNode instProps = inst.putObject("properties");
        instProps.putObject("type").put("type", "string");
        instProps.putObject("id").put("type", "string");
        freeObject(instProps.putObject("attributen"),
                "attribuutnaam -> waarde (per het objecttype-schema).");
        freeObject(instProps.putObject("kenmerken"),
                "kenmerknaam -> boolean.");
        freeObject(instProps.putObject("rollen"),
                "rolnaam -> doel-id of lijst van doel-ids.");

        // relaties[]
        ObjectNode relations = props.putObject("relaties");
        relations.put("type", "array");
        ObjectNode rel = relations.putObject("items");
        rel.put("type", "object");
        ArrayNode relReq = rel.putArray("required");
        relReq.add("feittype"); relReq.add("rollen");
        ObjectNode relProps = rel.putObject("properties");
        relProps.putObject("feittype").put("type", "string");
        freeObject(relProps.putObject("rollen"), "rolnaam -> id of lijst van ids.");
        return s;
    }

    /** The generic response schema, honest to {@link AnswerBuilder}: instanties[{id,type,attributen,kenmerken}]
     *  + inconsistenties[string] + inconsistentieDetails[{regel,onderwerp}]. */
    private static ObjectNode answerSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Het beslissingsantwoord: per instantie de gevraagde uitvoer-attributen/-kenmerken,"
                + " plus de namen van de regels waarvan een consistentietoets faalde.");
        ObjectNode props = s.putObject("properties");

        ObjectNode instances = props.putObject("instanties");
        instances.put("type", "array");
        ObjectNode inst = instances.putObject("items");
        inst.put("type", "object");
        ObjectNode instProps = inst.putObject("properties");
        instProps.putObject("id").put("type", "string");
        instProps.putObject("type").put("type", "string");
        freeObject(instProps.putObject("attributen"), "attribuutnaam -> waarde (of null bij leeg).");
        freeObject(instProps.putObject("kenmerken"), "kenmerknaam -> boolean.");

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

        ObjectNode uitleg = props.putObject("uitleg");
        uitleg.put("type", "array");
        uitleg.putObject("items").put("type", "string");
        uitleg.put("description", "met de vlag uitleg: een zin per toegekend of onthouden kenmerk van een instantie van"
                + " het verzoek, met de waarden waarop hij rust.");
        detailFields(props);
        return s;
    }

    /** The fields the detail flags add to an answer and to a 422, in the order the service writes them. */
    private static void detailFields(ObjectNode props) {
        props.putObject("trace").put("$ref", "#/components/schemas/Trace");
        props.putObject("dekking").put("$ref", "#/components/schemas/Dekking");
        props.putObject("fixpunt").put("$ref", "#/components/schemas/Fixpunt");
        ObjectNode error = props.putObject("fixpuntFout");
        error.put("type", "string");
        error.put("description", "met de vlag fixpunt, in plaats van fixpunt: waarom een waarde van het fixpunt niet"
                + " getoond kan worden.");
    }

    /** The firings of a request's session, as uchar's waarnemer records them. */
    private static ObjectNode traceSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "array");
        s.put("description", "met de vlag trace: per vorm die vuurde, in de volgorde waarin een regel ervan het eerst"
                + " vuurde, de ids van het verzoek die haar regels bonden en hoe vaak ze vuurden. Een vorm heet in het"
                + " vocabulaire van het antwoord.");
        ObjectNode item = s.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("regel").put("type", "string");
        ObjectNode labels = props.putObject("labels");
        labels.put("type", "array");
        labels.putObject("items").put("type", "string");
        props.putObject("aantal").put("type", "integer");
        return s;
    }

    /** Per form of the entry point whether a rule of it fired, with uchar's kinds. */
    private static ObjectNode coverageSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "array");
        s.put("description", "met de vlag dekking: per vorm van het entrypoint, in de volgorde van zijn manifest, of"
                + " een regel ervan vuurde. Een vorm heet in het vocabulaire van het antwoord.");
        ObjectNode item = s.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("regel").put("type", "string");
        ObjectNode kind = props.putObject("soort");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (ScenarioResult.CoverageKind k : ScenarioResult.CoverageKind.values()) kinds.add(k.name());
        return s;
    }

    /** What stands at the fixpoint of a request's session ({@link FixpointGivens}). */
    private static ObjectNode fixpointSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "met de vlag fixpunt: wat aan het fixpunt van de sessie staat. Een instantie van het"
                + " verzoek heet naar haar id, een gemaakt object #<n>. Een naam staat in het vocabulaire van het"
                + " antwoord; waar de canonieke naam verschilt, staat die ernaast onder <sleutel>Canoniek. Een waarde is"
                + " een tekst (een getal met zijn schaal, een datum, een enumeratiewaarde), een boolean of null (leeg);"
                + " een tijdlijn staat onder tijdlijn, een gedimensioneerde waarde onder dimensies.");
        ObjectNode props = s.putObject("properties");
        ObjectNode objects = list(props, "objecten", "elke instantie van het verzoek, in de volgorde van het"
                + " verzoek, dan elk gemaakt object, met zijn waarden en kenmerken in de declaratievolgorde van zijn"
                + " type.");
        ObjectNode objectProps = objects.putObject("properties");
        objectProps.putObject("object").put("type", "string");
        objectProps.putObject("objecttype").put("type", "string");
        ObjectNode values = list(objectProps, "waarden", "attribuut, waarde (of tijdlijn of dimensies), herkomst"
                + " (invoer, afgeleid of creatie), invoer (bij een afgeleide waarde de invoerwaarde die zij verving) en"
                + " eenheid.");
        values.put("additionalProperties", true);
        ObjectNode characteristics = list(objectProps, "kenmerken", "elk kenmerk dat geldt: kenmerk, herkomst (invoer of"
                + " afgeleid), en invoer: true als ook de invoer het zette.");
        characteristics.put("additionalProperties", true);
        ObjectNode made = list(props, "gemaakteObjecten", "de objecten die een objectcreatie maakte.");
        ObjectNode madeProps = made.putObject("properties");
        for (String v : new String[]{"object", "objecttype", "creatie", "onderwerp"})
            madeProps.putObject(v).put("type", "string");
        ObjectNode links = list(props, "koppelingen", "de koppelingen die een creatie legde.");
        ObjectNode linkProps = links.putObject("properties");
        for (String v : new String[]{"feittype", "eersteRol", "eerste", "tweedeRol", "tweede"})
            linkProps.putObject(v).put("type", "string");
        ObjectNode errors = list(props, "fouten", "de evaluatiefouten die aan het fixpunt staan, elk met de vorm die"
                + " haar gaf, het object waarover zij gaat, haar soort en uchars zin.");
        ObjectNode faultProps = errors.putObject("properties");
        faultProps.putObject("regel").put("type", "string");
        faultProps.putObject("onderwerp").put("type", "string");
        ObjectNode kind = faultProps.putObject("soort");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (EvaluationFault.Kind k : EvaluationFault.Kind.values()) kinds.add(k.name());
        faultProps.putObject("zin").put("type", "string");
        ObjectNode inc = list(props, "inconsistenties", "de gefaalde consistentietoetsen met hun regel en onderwerp,"
                + " elk null als er geen is.");
        ObjectNode incProps = inc.putObject("properties");
        incProps.putObject("regel").put("type", "string").put("nullable", true);
        incProps.putObject("onderwerp").put("type", "string").put("nullable", true);
        return s;
    }

    /** An array property {@code naam} of {@code props} with {@code description}; returns its item schema, an object. */
    private static ObjectNode list(ObjectNode props, String name, String description) {
        ObjectNode l = props.putObject(name);
        l.put("type", "array");
        l.put("description", description);
        ObjectNode item = l.putObject("items");
        item.put("type", "object");
        return item;
    }

    /** The error schema returned as a 4xx/5xx body: {fout, veld?, schendingen?}. */
    private static ObjectNode errorSchema() {
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

    /** The 422 body, honest to {@link AnswerBuilder#evaluationFault}: {status, fout, fouten[{soort, fout}]}, with the
     *  kinds of {@link EvaluationFault.Kind}. */
    private static ObjectNode evaluationFaultSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De regels geven op deze invoer geen antwoord: aan het fixpunt staan een of meer"
                + " evaluatiefouten, elk met haar soort en uchars zin.");
        ArrayNode req = s.putArray("required");
        req.add("status"); req.add("fout"); req.add("fouten");
        ObjectNode props = s.putObject("properties");
        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("EVALUATIEFOUT");
        ObjectNode error = props.putObject("fout");
        error.put("type", "string");
        error.put("description", "zegt dat de regels op deze invoer een evaluatiefout geven, of hoeveel.");
        ObjectNode errors = props.putObject("fouten");
        errors.put("type", "array");
        errors.put("description", "een element per fout, in uchars volgorde.");
        ObjectNode item = errors.putObject("items");
        item.put("type", "object");
        ArrayNode itemReq = item.putArray("required");
        itemReq.add("soort"); itemReq.add("fout");
        ObjectNode itemProps = item.putObject("properties");
        ObjectNode kind = itemProps.putObject("soort");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (EvaluationFault.Kind k : EvaluationFault.Kind.values()) kinds.add(k.name());
        kind.put("description", "DELING_DOOR_NUL: een deling door een numerieke nul; REKENFOUT: een fout van een"
                + " berekening of van de rekendatum; SCHRIJVERSCONFLICT: twee schrijvers van één attribuut die"
                + " verschillen; INVOERVORMFOUT: een tijdlijninvoer in de andere conventie, die geen verzoek bereikt,"
                + " want de dienst weigert een tijdlijn (400); ANDERE: een fout van een ander type die aan het fixpunt"
                + " staat, zoals een feitcreatie die een naar-één-rol een tweede vulling geeft.");
        ObjectNode sentence = itemProps.putObject("fout");
        sentence.put("type", "string");
        sentence.put("description", "uchars zin; een object van het verzoek heet daarin naar zijn id.");
        detailFields(props);
        return s;
    }

    /** The 422 body of a session without a fixpoint, honest to {@link AnswerBuilder#noFixpoint}: {status, fout}, and
     *  the detail a request may ask, where the fixpoint gives way to the reason there is none. */
    private static ObjectNode noFixpointSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De regels geven op deze invoer geen antwoord: de sessie bereikte geen fixpunt, want uchars"
                + " vuurplafond of creatiebudget is bereikt. Er staat dan niets om te lezen.");
        ArrayNode req = s.putArray("required");
        req.add("status"); req.add("fout");
        ObjectNode props = s.putObject("properties");
        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("GEEN_FIXPUNT");
        ObjectNode error = props.putObject("fout");
        error.put("type", "string");
        error.put("description", "uchars zin: welk plafond of budget bereikt is.");
        props.putObject("trace").put("$ref", "#/components/schemas/Trace");
        props.putObject("dekking").put("$ref", "#/components/schemas/Dekking");
        ObjectNode fixpointError = props.putObject("fixpuntFout");
        fixpointError.put("type", "string");
        fixpointError.put("description", "met de vlag fixpunt: uchars zin, want er is geen fixpunt om te tonen.");
        return s;
    }

    /** The /info response schema (minimal, honest to fase-3 /info); with vocabularies, their completeness. */
    private static ObjectNode infoSchema(Spellings sp) {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De dienst-info: identiteit, per-regel Dekking, overgeslagen markers, parameters.");
        ObjectNode props = s.putObject("properties");
        props.putObject("dienst").put("type", "object");
        props.putObject("dekking").put("type", "object");
        ObjectNode skipped = props.putObject("overgeslagen");
        skipped.put("type", "array");
        skipped.putObject("items").put("type", "string");
        props.putObject("parameters").put("type", "object");
        // fase 5: the loaded parametersets with their validity ranges, and the period-coverage WARNINGs.
        props.putObject("parametersets").put("type", "array");
        ObjectNode pw = props.putObject("periodeWaarschuwingen");
        pw.put("type", "array");
        pw.putObject("items").put("type", "string");
        if (sp.enabled()) {
            ObjectNode v = props.putObject("vocabulaires");
            v.put("type", "array");
            v.putObject("items").put("type", "object");
            v.put("description", "per vocabulaire: naam, standaard, taal, of het volledig is, en de declaraties"
                    + " zonder benaming (die draagt een antwoord in dat vocabulaire bij hun canonieke naam).");
        }
        return s;
    }

    private static void freeObject(ObjectNode node, String description) {
        node.put("type", "object");
        node.put("additionalProperties", true);
        node.put("description", description);
    }

    // ------------------------------------------------------------------ canonical serialization

    /** Serialise {@code doc} to canonical bytes: 2-space indent, {@code \n} newlines, UTF-8, ONE trailing
     *  newline. Deterministic for a given node tree (Jackson emits object fields in insertion order). */
    public static byte[] toBytes(ObjectNode doc) {
        JsonFactory factory = new JsonFactory();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (JsonGenerator gen = factory.createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(canonicalPrinter());
            new ObjectMapper().writeTree(gen, doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Jackson's DefaultPrettyPrinter does not add a trailing newline — add exactly one.
        byte[] body = out.toByteArray();
        byte[] extended = new byte[body.length + 1];
        System.arraycopy(body, 0, extended, 0, body.length);
        extended[body.length] = '\n';
        return extended;
    }

    /** A FIXED pretty printer: 2-space object/array indent, {@code \n} line separator, a single space after the
     *  {@code ":"} of an object entry — reproducible forever, independent of platform line endings. */
    private static PrettyPrinter canonicalPrinter() {
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
    public static void write(ServiceDescriptor descriptor, RegelSpraakModel model, Path target) {
        write(descriptor, model, null, target);
    }

    /** {@link #write(ServiceDescriptor, RegelSpraakModel, Path)} for the document in {@code vocabulaire}. */
    public static void write(ServiceDescriptor descriptor, RegelSpraakModel model, String vocabulary, Path target) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.write(target, toBytes(emit(descriptor, model, vocabulary)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The kebab-case file name a dienst's document lands under ({@code <kebab(naam)>.json}). */
    public static String fileName(ServiceDescriptor descriptor) {
        return fileName(descriptor, null);
    }

    /** The file name of the document in {@code vocabulaire}: {@code <kebab(naam)>.<vocabulaire>.json}, or
     *  {@link #fileName(ServiceDescriptor)} for the canonical one (null or {@link Naming#CANONICAL}). */
    public static String fileName(ServiceDescriptor descriptor, String vocabulary) {
        String name = descriptor.service() == null ? "dienst" : nvl(descriptor.service().name());
        String kebab = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        String vocab = vocabulary == null || vocabulary.equalsIgnoreCase(Naming.CANONICAL) ? ""
                : "." + vocabulary.toLowerCase();
        return (kebab.isEmpty() ? "dienst" : kebab) + vocab + ".json";
    }

    private static List<ServiceDescriptor.Entrypoint> eps(ServiceDescriptor d) {
        return d.entrypoints() == null ? List.of() : d.entrypoints();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
