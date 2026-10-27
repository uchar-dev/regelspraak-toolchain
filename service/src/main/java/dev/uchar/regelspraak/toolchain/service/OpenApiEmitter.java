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
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.engine.Part;
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
 * Dimensie- and tijdlijn-valued members are REJECTED by the service ({@link RequestMapper} UNSUPPORTED),
 * so no property is emitted for them — the schema description says so.
 *
 * <p><b>Per vocabulary.</b> {@link #emit(ServiceDescriptor, RegelSpraakModel, String)} writes the document of one
 * vocabulary the model declares, or of the canonical names: the object types, properties, enumeration values and
 * selectors go by their names in it ({@link Naming}). The service reads every other spelling too
 * ({@link NameReader}), so for a model with vocabularies each object type, property and entry point lists its other
 * spellings — the canonical one and those of the other vocabularies — in {@code x-spellings}, each enumeration its
 * values' in {@code x-valueSpellings}, and says in its description that the service accepts them; the operations
 * document the query parameters {@code vocabulaire} and {@code inputVocabulary}, and the info block names the
 * document's vocabulary in {@code x-vocabulaire}. A model without vocabularies gets none of this, so its document is
 * the one it always was.
 *
 * <p><b>What a request asks beside its body.</b> Every operation documents the flags {@code explanation} and
 * {@code explanationParts} and the detail flags {@code trace}, {@code dekking} and {@code fixpunt}, which a service
 * gives only when it was started with detail ({@link CoreConfig#detail()}) and otherwise refuses with a 400; the fields
 * they add stand in {@code Answer} and, for the detail, in {@code EvaluationFault}, with the components
 * {@code ExplanationPart}, {@code Trace}, {@code Dekking} and {@code Fixpunt}.
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
        Spellings sp = new Spellings(model, Naming.of(model, vocabulary));
        ObjectNode doc = NF.objectNode();
        doc.put("openapi", "3.0.3");

        ObjectNode info = doc.putObject("info");
        info.put("title", descriptor.service() == null ? "" : nvl(descriptor.service().name()));
        info.put("version", descriptor.service() == null ? "" : nvl(descriptor.service().version()));
        if (sp.enabled()) {
            info.put("description", sp.documentDescription());
            info.put("x-vocabulary", sp.in.choice());
        }

        // paths — one POST per entrypoint (declared order), then GET /info
        ObjectNode paths = doc.putObject("paths");
        for (ServiceDescriptor.Entrypoint ep : eps(descriptor))
            paths.set("/" + nvl(ep.path()), pathItem(ep, model, sp));
        paths.set("/info", infoPath());

        // components.schemas — one per objecttype (declared order), then the generic request/response/fout
        ObjectNode components = doc.putObject("components");
        ObjectNode schemas = components.putObject("schemas");
        for (ObjectType ot : model.objectTypes.values())
            schemas.set(componentName(ot, sp.in), objectTypeSchema(ot, sp));
        schemas.set("Request", requestSchema());
        schemas.set("Answer", answerSchema());
        schemas.set("Error", errorSchema());
        schemas.set("EvaluationFault", evaluationFaultSchema());
        schemas.set("Info", infoSchema(sp));
        schemas.set("ExplanationPart", explanationPartSchema());
        schemas.set("Trace", traceSchema());
        schemas.set("Coverage", coverageSchema());
        schemas.set("Fixpoint", fixpointSchema());
        schemas.set("NoFixpoint", noFixpointSchema());

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
                if (!v.name().equals(in.choice())) other.add(Naming.of(model, v.name()));
        }

        /** Whether the document lists other spellings: the model declares vocabularies. */
        boolean enabled() { return !model.vocabularies.isEmpty(); }

        /** {@code {<keuze>: <naam>}} of declaration {@code b} in the other spellings. */
        ObjectNode of(Designatable b) {
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
            List<String> vocabularies = model.vocabularies.stream().map(v -> "'" + v.name() + "'").toList();
            return (in.isCanonical() ? "De namen in dit document zijn de canonieke namen."
                    : "De namen in dit document zijn die van het vocabulaire '" + in.choice() + "'.")
                    + " De dienst neemt in een verzoek elke naam aan: canoniek of als benaming in "
                    + (vocabularies.size() == 1 ? "het vocabulaire " : "de vocabulaires ") + enumeration(vocabularies)
                    + ", ook gemengd; x-spellings en x-valueSpellings noemen ze."
                    + " Een naam die zo twee declaraties noemt is een 400 (AMBIGUOUS_NAME), tenzij de queryparameter"
                    + " inputVocabulary het vocabulaire vastzet; de queryparameter vocabulary kiest de namen van het"
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
            for (RegelSpraakModel.Vocabulary v : model.vocabularies) sink.add(v.name());
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
            ObjectNode spellings = post.putObject("x-spellings");
            for (Naming n : sp.other) {
                ObjectNode s = spellings.putObject(n.choice());
                ArrayNode input = s.putArray("input");
                for (String i : input(ep, model, n)) input.add(i);
                ArrayNode output = s.putArray("output");
                for (String u : output(ep, model, n)) output.add(u);
            }
            parameters.add(queryParameter("vocabulary", "Het vocabulaire waarin het antwoord de declaraties noemt;"
                    + " zonder: " + (ep.vocabulary() == null ? "de canonieke namen"
                    : "'" + ep.vocabulary() + "', de standaard van dit entrypoint") + ". Een declaratie zonder"
                    + " benaming in het gekozen vocabulaire draagt haar canonieke naam; GET /info noemt die declaraties"
                    + " per vocabulaire. Een onbekend vocabulaire is een 400 (UNKNOWN_VOCABULARY).", sp.choices()));
            parameters.add(queryParameter("inputVocabulary", "Zet het vocabulaire vast waarin de namen van het"
                    + " verzoek gelezen worden; zonder wordt elke naam gelezen als canonieke naam of als benaming in"
                    + " elk vocabulaire.", sp.choices()));
        }
        parameters.add(flagParameter("explanation", "true: het antwoord krijgt explanation, een zin per toegekend of onthouden"
                + " kenmerk van een instantie van het verzoek, met de waarden waarop hij rust."));
        parameters.add(flagParameter("explanationParts", "true: het antwoord krijgt explanation en daarnaast"
                + " explanationParts (schema ExplanationPart): per zin van explanation de stukken waaruit zij bestaat,"
                + " elk met zijn kind; de teksten van de bladeren achter elkaar zijn de zin."));
        parameters.add(flagParameter("trace", "true: het antwoord krijgt trace (schema Trace): per vorm die vuurde de"
                + " ids van het verzoek die haar regels bonden en hoe vaak ze vuurden." + DETAIL));
        parameters.add(flagParameter("coverage", "true: het antwoord krijgt coverage (schema Coverage): per vorm van dit"
                + " entrypoint of een regel ervan vuurde." + DETAIL));
        parameters.add(flagParameter("fixpoint", "true: het antwoord krijgt fixpoint (schema Fixpoint): wat aan het"
                + " fixpunt staat, met de instanties van het verzoek als invoer." + DETAIL));
        post.set("parameters", parameters);

        ObjectNode requestBody = post.putObject("requestBody");
        requestBody.put("required", true);
        jsonContent(requestBody.putObject("content"), "#/components/schemas/Request");

        ObjectNode responses = post.putObject("responses");
        answer(responses, "200", "beslissing berekend", "#/components/schemas/Answer");
        answer(responses, "400", "verzoek valideert niet — het antwoord noemt het foutieve veld",
                "#/components/schemas/Error");
        answer(responses, "404", "onbekend pad", "#/components/schemas/Error");
        ObjectNode unanswered = responses.putObject("422");
        unanswered.put("description", "de regels geven op deze invoer een evaluatiefout: status EVALUATION_FAULT, per fout"
                + " kind en error (uchars zin); of de sessie bereikt geen fixpunt: status NO_FIXPOINT, met uchars zin");
        ArrayNode oneOf = unanswered.putObject("content").putObject("application/json").putObject("schema")
                .putArray("oneOf");
        oneOf.addObject().put("$ref", "#/components/schemas/EvaluationFault");
        oneOf.addObject().put("$ref", "#/components/schemas/NoFixpoint");
        answer(responses, "503", "uchar draagt dit entrypoint niet: status MARKER, met uchars reden",
                "#/components/schemas/Error");
        answer(responses, "500", "interne fout (geen stacktrace gelekt)", "#/components/schemas/Error");
        return item;
    }

    private static ObjectNode infoPath() {
        ObjectNode item = NF.objectNode();
        ObjectNode get = item.putObject("get");
        get.put("operationId", "info");
        get.put("summary", "dienst-info");
        get.put("description",
                "De dienst-identiteit, de per-regel Coverage-verdicten, de overgeslagen (marker) regels en de "
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
        if (sp.enabled()) b.append(" De dienst neemt ook de spellingen van x-spellings aan.");
        return b.toString();
    }

    /** The invoer object types of {@code ep} in the names of {@code n}; canonically as the descriptor writes them. */
    private static List<String> input(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model, Naming n) {
        List<String> sink = new ArrayList<>();
        for (String inv : ep.input() == null ? List.<String>of() : ep.input()) {
            ObjectType ot = DescriptorLoader.objectType(model, inv);
            sink.add(n.isCanonical() || ot == null ? inv : n.objectType(ot.name));
        }
        return sink;
    }

    /** The uitvoer selectors of {@code ep} in the names of {@code n}; canonically as the descriptor writes them. */
    private static List<String> output(ServiceDescriptor.Entrypoint ep, RegelSpraakModel model, Naming n) {
        List<String> sink = new ArrayList<>();
        for (String sel : ep.output() == null ? List.<String>of() : ep.output()) {
            OutputSelector u = OutputSelector.parse(sel);
            ObjectType ot = u == null ? null : DescriptorLoader.objectType(model, u.objectType());
            if (n.isCanonical() || ot == null) { sink.add(sel); continue; }
            sink.add(n.objectType(ot.name) + (u.characteristic() ? ":" + n.characteristic(ot, u.member()) : "." + n.attribute(ot, u.member())));
        }
        return sink;
    }

    /** What every detail flag says: the service gives it only when started with detail, and a 422 gets it too. */
    private static final String DETAIL = " De dienst geeft dit alleen als hij met DETAIL gestart is; anders is de"
            + " vlag een 400 (UNSUPPORTED, met de naam van de vlag als field). Een 422 krijgt het veld ook.";

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
     *  <p><b>IR-SPEC ch. 2.2: the member set is CHAIN-CLOSED</b> ({@link Objecttype#attributeMembers} /
     *  {@link DescriptorLoader#characteristicMembers}), nearest declaration first. A request instance of a subtype
     *  carries its inherited members exactly like its own ones — {@link RequestMapper} accepts them and
     *  {@link AnswerBuilder} reads them off the same bean — so a document built from the OWN maps would
     *  describe a request shape the service does not have. The schema is FLAT rather than an OpenAPI
     *  {@code allOf} composition over the supertype's component: the document's contract is "exactly the
     *  request shape the mapper accepts", and the mapper has no composition either. This is the opposite
     *  choice from {@link dev.uchar.regelspraak.schema.FactModelGenerator}, which stays per
     *  declaring type BECAUSE the generated bean {@code extends} its supertype's and would hide the
     *  parent's field by redeclaring it; a JSON schema property set has no such mechanism. */
    private static ObjectNode objectTypeSchema(ObjectType ot, Spellings sp) {
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
            if (sp.enabled()) spellings(attribute, sp.of(Naming.attributeOf(ot, e.getKey())));
            props.set(name, attribute);
        }
        // kenmerken (own first, then inherited) — boolean properties
        for (String k : DescriptorLoader.characteristicMembers(ot)) {
            String name = sp.in.characteristic(ot, k);
            if (temporalCharacteristic(ot, k)) { rejected.add(name + " (tijdlijn-kenmerk)"); continue; }
            ObjectNode kn = NF.objectNode();
            kn.put("type", "boolean");
            kn.put("description", "characteristic");
            if (sp.enabled()) spellings(kn, sp.of(Naming.characteristicOf(ot, k)));
            props.set(name, kn);
            characteristics.add(name);
        }
        schema.set("properties", props);
        schema.put("description", objectTypeDescription(ot, sp.in, characteristics, rejected));
        if (sp.enabled()) spellings(schema, sp.of(Designatable.objectType(ot.name)));
        return schema;
    }

    /** {@code node} lists {@code spellings} in {@code x-spellings}, and its description says the service accepts
     *  them (a description of its own is kept in front). */
    private static void spellings(ObjectNode node, ObjectNode spellings) {
        String sentence = Spellings.sentence(spellings);
        String own = node.has("description") ? node.get("description").asText() : "";
        node.put("description", own.isEmpty() ? sentence : own + (own.endsWith(".") ? " " : ". ") + sentence);
        node.set("x-spellings", spellings);
    }

    private static String objectTypeDescription(ObjectType ot, Naming in, List<String> characteristics,
                                                 List<String> rejected) {
        StringBuilder b = new StringBuilder();
        b.append("Objecttype '").append(in.objectType(ot.name)).append("'.");
        if (!characteristics.isEmpty())
            b.append(" Kenmerken (boolean-eigenschappen): ").append(String.join(", ", characteristics)).append(".");
        if (!rejected.isEmpty())
            b.append(" Dimensie-/tijdlijn-gewaardeerde leden worden door de dienst-kern niet ondersteund en"
                    + " zijn hier niet als eigenschap opgenomen: ").append(String.join(", ", rejected)).append(".");
        return b.toString();
    }

    /** Map a scalar attribuut's datatype onto its JSON-schema fragment, documenting an eenheid via
     *  {@code x-eenheid} + a description mention; an enumeration's values in the document's names, and for a model
     *  with vocabularies their other spellings in {@code x-valueSpellings}. */
    private static ObjectNode attributeSchema(Datatype dt, Spellings sp) {
        ObjectNode s = NF.objectNode();
        Unit unit = null;
        switch (dt) {
            case Datatype.Numeric n -> {
                s.put("type", n.wholeNumber() ? "integer" : "number");
                unit = n.unit();
            }
            case Datatype.Percentage p -> {
                s.put("type", p.wholeNumber() ? "integer" : "number");
                unit = p.unit();
            }
            case Datatype.Text t -> s.put("type", "string");
            case Datatype.BooleanType b -> s.put("type", "boolean");
            case Datatype.DateInDays d -> { s.put("type", "string"); s.put("format", "date"); }
            case Datatype.DateTime d -> { s.put("type", "string"); s.put("format", "date-time"); }
            case Datatype.Enumeration e -> {
                s.put("type", "string");
                ArrayNode enumArray = s.putArray("enum");
                for (String w : e.values()) enumArray.add(sp.in.value(e, w));
                if (sp.enabled()) {
                    ObjectNode values = s.putObject("x-valueSpellings");
                    for (String w : e.values())
                        values.set(sp.in.value(e, w), sp.of(Designatable.enumerationValue(e.domain(), w)));
                }
            }
            case Datatype.Unknown o -> s.put("type", "string");
        }
        if (unit != null) {
            String rendering = unit.rendering();
            s.put("x-unit", rendering);
            s.put("description", "eenheid: " + rendering
                    + " (een getal in deze eenheid, of {\"value\": n, \"unit\": \"" + rendering + "\"})");
        }
        return s;
    }

    private static String componentName(ObjectType ot, Naming in) { return Namen.ident(in.objectType(ot.name), true); }

    /** §13.3.6 whether attribuut {@code naam} rides a tijdlijn ANYWHERE on {@code ot}'s chain. Reading the own
     *  maps would call an INHERITED timeline scalar and emit a plain property for it — a contract the service
     *  then refuses at request time ({@link RequestMapper} UNSUPPORTED). */
    private static boolean temporalAttribute(ObjectType ot, String name) {
        return ot.granularityOf(name) != null || ot.timelineCountOf(name) != null;
    }

    /** The kenmerk twin of {@link #temporalAttribute}, chain-closed for the same reason. */
    private static boolean temporalCharacteristic(ObjectType ot, String name) {
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
        req.add("instances");
        ObjectNode props = s.putObject("properties");

        // peildatum: the ONE date authority (fase 5). It IS the rekendatum — a rule reading Rekendatum and the
        // parameterset-vintage selection follow this same date; 'rekendatum' remains as an alias.
        ObjectNode asOfDate = props.putObject("asOfDate");
        asOfDate.put("type", "string");
        asOfDate.put("format", "date");
        asOfDate.put("description", "ISO-datum (yyyy-MM-dd); optioneel. De ÉÉN datumautoriteit: dezelfde datum"
                + " selecteert de parameterset-vintage EN is de rekendatum-global. Gelijk aan 'rekendatum'"
                + " (alias); beide meegeven met verschillende waarden is een 400.");

        ObjectNode evaluationDate = props.putObject("rekendatum");
        evaluationDate.put("type", "string");
        evaluationDate.put("format", "date");
        evaluationDate.put("description", "ISO-datum (yyyy-MM-dd); optioneel. Alias van 'asOfDate' (dezelfde"
                + " datumautoriteit).");

        ObjectNode parameters = props.putObject("parameters");
        parameters.put("type", "object");
        parameters.put("additionalProperties", true);
        parameters.put("description", "parameternaam -> waarde; overschrijft de descriptor-defaults.");

        // instanties[]
        ObjectNode instances = props.putObject("instances");
        instances.put("type", "array");
        ObjectNode inst = instances.putObject("items");
        inst.put("type", "object");
        ArrayNode instReq = inst.putArray("required");
        instReq.add("type"); instReq.add("id");
        ObjectNode instProps = inst.putObject("properties");
        instProps.putObject("type").put("type", "string");
        instProps.putObject("id").put("type", "string");
        freeObject(instProps.putObject("attributes"),
                "attribuutnaam -> waarde (per het objecttype-schema).");
        freeObject(instProps.putObject("characteristics"),
                "kenmerknaam -> boolean.");
        freeObject(instProps.putObject("roles"),
                "rolnaam -> doel-id of lijst van doel-ids.");

        // relaties[]
        ObjectNode relations = props.putObject("relations");
        relations.put("type", "array");
        ObjectNode rel = relations.putObject("items");
        rel.put("type", "object");
        ArrayNode relReq = rel.putArray("required");
        relReq.add("factType"); relReq.add("roles");
        ObjectNode relProps = rel.putObject("properties");
        relProps.putObject("factType").put("type", "string");
        freeObject(relProps.putObject("roles"), "rolnaam -> id of lijst van ids.");
        return s;
    }

    /** The generic response schema, honest to {@link AnswerBuilder}: instances[{id,type,attributes,characteristics}]
     *  + inconsistencies[string] + inconsistencyDetails[{rule,subject}]. */
    private static ObjectNode answerSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Het beslissingsantwoord: per instantie de gevraagde uitvoer-attributen/-kenmerken,"
                + " plus de namen van de regels waarvan een consistentietoets faalde.");
        ObjectNode props = s.putObject("properties");

        ObjectNode instances = props.putObject("instances");
        instances.put("type", "array");
        ObjectNode inst = instances.putObject("items");
        inst.put("type", "object");
        ObjectNode instProps = inst.putObject("properties");
        instProps.putObject("id").put("type", "string");
        instProps.putObject("type").put("type", "string");
        freeObject(instProps.putObject("attributes"), "attribuutnaam -> waarde (of null bij leeg).");
        freeObject(instProps.putObject("characteristics"), "kenmerknaam -> boolean.");

        ObjectNode inc = props.putObject("inconsistencies");
        inc.put("type", "array");
        inc.putObject("items").put("type", "string");
        inc.put("description", "de namen van de regels waarvan een consistentietoets INCONSISTENT opleverde.");

        ObjectNode details = props.putObject("inconsistencyDetails");
        details.put("type", "array");
        ObjectNode detail = details.putObject("items");
        detail.put("type", "object");
        ObjectNode detailProps = detail.putObject("properties");
        detailProps.putObject("rule").put("type", "string");
        detailProps.putObject("subject").put("type", "string").put("nullable", true);
        details.put("description", "dezelfde inconsistenties met hun subject: het id van de instantie waarover de"
                + " toets ging, of null als dat geen instantie van het verzoek is.");

        ObjectNode explanation = props.putObject("explanation");
        explanation.put("type", "array");
        explanation.putObject("items").put("type", "string");
        explanation.put("description", "met de vlag explanation: een zin per toegekend of onthouden kenmerk van een instantie van"
                + " het verzoek, met de waarden waarop hij rust.");
        ObjectNode explanationParts = props.putObject("explanationParts");
        explanationParts.put("type", "array");
        ObjectNode sentence = explanationParts.putObject("items");
        sentence.put("type", "array");
        sentence.putObject("items").put("$ref", "#/components/schemas/ExplanationPart");
        explanationParts.put("description", "met de vlag explanationParts: per zin van explanation, in dezelfde"
                + " volgorde, de stukken aan haar wortel; de teksten van de bladeren achter elkaar zijn de zin.");
        detailFields(props);
        return s;
    }

    /** The fields the detail flags add to an answer and to a 422, in the order the service writes them. */
    private static void detailFields(ObjectNode props) {
        props.putObject("trace").put("$ref", "#/components/schemas/Trace");
        props.putObject("coverage").put("$ref", "#/components/schemas/Coverage");
        props.putObject("fixpoint").put("$ref", "#/components/schemas/Fixpoint");
        ObjectNode error = props.putObject("fixpointError");
        error.put("type", "string");
        error.put("description", "met de vlag fixpoint, in plaats van fixpoint: waarom een waarde van het fixpunt niet"
                + " getoond kan worden.");
    }

    /** One part of a sentence of the explanation ({@link ExplanationParts}): a leaf or a group, recursive through
     *  {@code parts}. */
    private static ObjectNode explanationPartSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Een stuk van een uitlegzin: een blad met text, of een groep (CONDITION, MEMBERS,"
                + " COMPUTATION, VARIANT, READING, GROUND) met parts, waarvan de tekst die van haar stukken achter elkaar"
                + " is. Een groep MEMBERS, VARIANT met decided: false en READING draagt haar eigen lijm, zodat de zin"
                + " zonder zo'n groep een zin blijft. Per kind: SUBJECT het"
                + " label van een instantie, het onderwerp of een lid van een kwantorvouw; NAME een gedeclareerde naam"
                + " in het vocabulaire van het antwoord, met declaration (ATTRIBUUT, KENMERK, ROL of PARAMETER), owner"
                + " en canonical, de sleutels kind, owner en canonical van een element van vocabularies, en bij een"
                + " attribuut of kenmerk waarvan de zin de waarde van één object noemt object: het label van dat object"
                + " zoals SUBJECT het noemt (de kopnaam van een zin het onderwerp, een lezing het object waarop de motor"
                + " haar las, ook over een rol), null waar een link op de rol ontbreekt; geen object bij een parameter,"
                + " een rol, de tekst en het filter van een vouw over een verzameling (per lid gelezen; de leden staan"
                + " in MEMBERS) en een lezing die de motor niet bereikte; QUOTE regeltekst zoals geschreven, met"
                + " dezelfde drie waar het operand één declaratie leest, en object zoals bij NAME; VALUE een"
                + " waarde met haar eenheid, met empty: true bij een lege en timeline: true bij 'een tijdlijn', de waarde"
                + " van een tijdlijn-doel; RULE de naam van een regel of van een variant"
                + " van een defaults-groep; CONNECTIVE een woord dat de logica tussen de delen draagt (omdat, want,"
                + " maar, en, of, en niet, niet geldt, gold niet); TEXT lijm; CONDITION een voorwaarde, met holds, het"
                + " oordeel van de motor (ontbreekt waar de motor de knoop zonder oordeel bereikte), en bij een"
                + " samenstelling of een kwantorvouw quantifier (all, none, atLeast, atMost, exactly, the) met threshold"
                + " bij een telling; MEMBERS de leden van een kwantorvouw die haar uitkomst dragen, met de dubbele punt"
                + " ervoor, met members (m),"
                + " holding (k) en unjudged, of de leden van een vouw in een berekening met wat elk bijdroeg, met alleen"
                + " members; COMPUTATION hoe de regel van een berekende waarde, of de variant die een defaults-groep op"
                + " een attribuut besliste, de waarde berekende, na haar naam en met de dubbele punt: de regeltekst van"
                + " haar waarde, elke waarde die de berekening las of vouwde, en de"
                + " waarde vóór een afronding en vóór een begrenzing; VARIANT een schrijver die de waarde van de zin"
                + " niet schreef, met de scheiding ervoor ('; ', of ', ' tussen verdrongen varianten), met decided: false,"
                + " en displaced: true bij een variant van een defaults-groep na de beslisser en bij een initialisatie"
                + " die niet aan de beurt was: in de zin van een defaults-groep elke variant behalve de beslisser, in die"
                + " van een berekende waarde elke schrijver behalve die schreef; READING de waarde die de motor las voor"
                + " het operand of het lid ervoor, met haar haakjes: ' (', VALUE, ')'; GROUND de grondslag, het laatste stuk van de zin, met de punt en"
                + " 'Grondslag: ' als TEXT ervoor, en met de beslisser waarvan het de statuteAnnotations zijn, in de"
                + " termen van het IR: bij een regel rule, de naam van haar vorm in rules.rules; bij een default group,"
                + " de naam van zijn groep in rules.prioritizedDefaults (null waar het document de groep geen naam"
                + " geeft), pointer, de JSON Pointer van de default in die groep (/defaults/1, of dieper"
                + " /defaults/0/exceptions/members/0/defaults/0), position, zijn plaats in zijn keten (1-gebaseerd, zoals"
                + " de zin 'de variant op plaats n' telt), en variant, zijn naam, waar hij er een heeft; STATUTE een"
                + " Juriconnect-anker, met bwb, article en, waar het ze"
                + " noemt, paragraph en part; LEMMA een lemma; DEVIATION een deviatie, met between, de lagen"
                + " waartussen zij ligt in de volgorde van het register (de eerste is de laag waarvan de tweede"
                + " afwijkt), of de ene laag waarbinnen zij ligt, en follows, de laag die de regelset volgt (ontbreekt"
                + " bij een deviatie binnen één laag); NOTE elk ander segment van een annotatie.");
        s.putArray("required").add("kind");
        ObjectNode props = s.putObject("properties");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (Part.Kind k : Part.Kind.values()) kinds.add(k.name());
        props.putObject("text").put("type", "string");
        ObjectNode parts = props.putObject("parts");
        parts.put("type", "array");
        parts.putObject("items").put("$ref", "#/components/schemas/ExplanationPart");
        for (String key : new String[]{"declaration", "canonical", "quantifier", "bwb", "article", "paragraph", "part",
                "rule", "pointer", "variant", "follows"})
            props.putObject(key).put("type", "string");
        ObjectNode between = props.putObject("between");
        between.put("type", "array");
        between.putObject("items").put("type", "string");
        for (String key : new String[]{"owner", "group", "object"})
            props.putObject(key).put("type", "string").put("nullable", true);
        for (String key : new String[]{"empty", "timeline", "holds", "decided", "displaced"})
            props.putObject(key).put("type", "boolean");
        for (String key : new String[]{"threshold", "members", "holding", "unjudged", "position"})
            props.putObject(key).put("type", "integer");
        return s;
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
        props.putObject("rule").put("type", "string");
        ObjectNode labels = props.putObject("labels");
        labels.put("type", "array");
        labels.putObject("items").put("type", "string");
        props.putObject("count").put("type", "integer");
        return s;
    }

    /** Per form of the entry point whether a rule of it fired, with uchar's kinds. */
    private static ObjectNode coverageSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "array");
        s.put("description", "met de vlag coverage: per vorm van het entrypoint, in de volgorde van zijn manifest, of"
                + " een regel ervan vuurde. Een vorm heet in het vocabulaire van het antwoord.");
        ObjectNode item = s.putObject("items");
        item.put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("rule").put("type", "string");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (ScenarioResult.CoverageKind k : ScenarioResult.CoverageKind.values()) kinds.add(k.name());
        return s;
    }

    /** What stands at the fixpoint of a request's session ({@link FixpointGivens}). */
    private static ObjectNode fixpointSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "met de vlag fixpoint: wat aan het fixpunt van de sessie staat. Een instantie van het"
                + " verzoek heet naar haar id, een gemaakt object #<n>. Een naam staat in het vocabulaire van het"
                + " antwoord; waar de canonieke naam verschilt, staat die ernaast onder <key>Canonical. Een waarde is"
                + " een tekst (een getal met zijn schaal, een datum, een enumeratiewaarde), een boolean of null (leeg);"
                + " een tijdlijn staat onder timeline, een gedimensioneerde waarde onder dimensions.");
        ObjectNode props = s.putObject("properties");
        ObjectNode objects = list(props, "objects", "elke instantie van het verzoek, in de volgorde van het"
                + " verzoek, dan elk gemaakt object, met zijn waarden en kenmerken in de declaratievolgorde van zijn"
                + " type.");
        ObjectNode objectProps = objects.putObject("properties");
        objectProps.putObject("object").put("type", "string");
        objectProps.putObject("objectType").put("type", "string");
        ObjectNode values = list(objectProps, "values", "attribute, value (of timeline of dimensions), origin"
                + " (input, derived of creation), input (bij een afgeleide waarde de invoerwaarde die zij verving) en"
                + " unit.");
        values.put("additionalProperties", true);
        ObjectNode characteristics = list(objectProps, "characteristics", "elk kenmerk dat geldt: characteristic, origin (input of"
                + " derived), en input: true als ook de invoer het zette.");
        characteristics.put("additionalProperties", true);
        ObjectNode made = list(props, "madeObjects", "de objecten die een objectcreatie maakte.");
        ObjectNode madeProps = made.putObject("properties");
        for (String v : new String[]{"object", "objectType", "creation", "subject"})
            madeProps.putObject(v).put("type", "string");
        ObjectNode links = list(props, "links", "de koppelingen die een creatie legde.");
        ObjectNode linkProps = links.putObject("properties");
        for (String v : new String[]{"factType", "firstRole", "first", "secondRole", "second"})
            linkProps.putObject(v).put("type", "string");
        ObjectNode errors = list(props, "faults", "de evaluatiefouten die aan het fixpunt staan, elk met de vorm die"
                + " haar gaf, het object waarover zij gaat, haar kind en uchars zin.");
        ObjectNode faultProps = errors.putObject("properties");
        faultProps.putObject("rule").put("type", "string");
        faultProps.putObject("subject").put("type", "string");
        ObjectNode kind = faultProps.putObject("kind");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (EvaluationFault.Kind k : EvaluationFault.Kind.values()) kinds.add(k.name());
        faultProps.putObject("sentence").put("type", "string");
        ObjectNode inc = list(props, "inconsistencies", "de gefaalde consistentietoetsen met hun rule en subject,"
                + " elk null als er geen is.");
        ObjectNode incProps = inc.putObject("properties");
        incProps.putObject("rule").put("type", "string").put("nullable", true);
        incProps.putObject("subject").put("type", "string").put("nullable", true);
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

    /** The error schema returned as a 4xx/5xx body: {error, field?, violations?}. */
    private static ObjectNode errorSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "Een dienst-fout; noemt waar mogelijk het foutieve veld/pad.");
        ArrayNode req = s.putArray("required");
        req.add("error");
        ObjectNode props = s.putObject("properties");
        props.putObject("error").put("type", "string");
        props.putObject("field").put("type", "string");
        ObjectNode violationsSchema = props.putObject("violations");
        violationsSchema.put("type", "array");
        violationsSchema.putObject("items").put("type", "object");
        violationsSchema.put("description", "de verzameling schendingen, elk met kind/field/message.");
        return s;
    }

    /** The 422 body, honest to {@link AnswerBuilder#evaluationFault}: {status, error, faults[{kind, error}]}, with the
     *  kinds of {@link EvaluationFault.Kind}. */
    private static ObjectNode evaluationFaultSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De regels geven op deze invoer geen antwoord: aan het fixpunt staan een of meer"
                + " evaluatiefouten, elk met haar kind en uchars zin.");
        ArrayNode req = s.putArray("required");
        req.add("status"); req.add("error"); req.add("faults");
        ObjectNode props = s.putObject("properties");
        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("EVALUATION_FAULT");
        ObjectNode error = props.putObject("error");
        error.put("type", "string");
        error.put("description", "zegt dat de regels op deze invoer een evaluatiefout geven, of hoeveel.");
        ObjectNode errors = props.putObject("faults");
        errors.put("type", "array");
        errors.put("description", "een element per fout, in uchars volgorde.");
        ObjectNode item = errors.putObject("items");
        item.put("type", "object");
        ArrayNode itemReq = item.putArray("required");
        itemReq.add("kind"); itemReq.add("error");
        ObjectNode itemProps = item.putObject("properties");
        ObjectNode kind = itemProps.putObject("kind");
        kind.put("type", "string");
        ArrayNode kinds = kind.putArray("enum");
        for (EvaluationFault.Kind k : EvaluationFault.Kind.values()) kinds.add(k.name());
        kind.put("description", "DIVISION_BY_ZERO: een deling door een numerieke nul; COMPUTE_FAULT: een fout van een"
                + " berekening of van de rekendatum; WRITERS_CONFLICT: twee schrijvers van één attribuut die"
                + " verschillen; INPUT_FORM_FAULT: een tijdlijninvoer in de andere conventie, die geen verzoek bereikt,"
                + " want de dienst weigert een tijdlijn (400); OTHER: een fout van een ander type die aan het fixpunt"
                + " staat, zoals een feitcreatie die een naar-één-rol een tweede vulling geeft.");
        ObjectNode sentence = itemProps.putObject("error");
        sentence.put("type", "string");
        sentence.put("description", "uchars zin; een object van het verzoek heet daarin naar zijn id.");
        detailFields(props);
        return s;
    }

    /** The 422 body of a session without a fixpoint, honest to {@link AnswerBuilder#noFixpoint}: {status, error}, and
     *  the detail a request may ask, where the fixpoint gives way to the reason there is none. */
    private static ObjectNode noFixpointSchema() {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De regels geven op deze invoer geen antwoord: de sessie bereikte geen fixpunt, want uchars"
                + " vuurplafond of creatiebudget is bereikt. Er staat dan niets om te lezen.");
        ArrayNode req = s.putArray("required");
        req.add("status"); req.add("error");
        ObjectNode props = s.putObject("properties");
        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.putArray("enum").add("NO_FIXPOINT");
        ObjectNode error = props.putObject("error");
        error.put("type", "string");
        error.put("description", "uchars zin: welk plafond of budget bereikt is.");
        props.putObject("trace").put("$ref", "#/components/schemas/Trace");
        props.putObject("coverage").put("$ref", "#/components/schemas/Coverage");
        ObjectNode fixpointError = props.putObject("fixpointError");
        fixpointError.put("type", "string");
        fixpointError.put("description", "met de vlag fixpoint: uchars zin, want er is geen fixpunt om te tonen.");
        return s;
    }

    /** The /info response schema (minimal, honest to fase-3 /info); with vocabularies, their completeness. */
    private static ObjectNode infoSchema(Spellings sp) {
        ObjectNode s = NF.objectNode();
        s.put("type", "object");
        s.put("description", "De dienst-info: identiteit, per-regel coverage, overgeslagen markers, parameters.");
        ObjectNode props = s.putObject("properties");
        props.putObject("service").put("type", "object");
        props.putObject("coverage").put("type", "object");
        ObjectNode skipped = props.putObject("skipped");
        skipped.put("type", "array");
        skipped.putObject("items").put("type", "string");
        props.putObject("parameters").put("type", "object");
        // fase 5: the loaded parametersets with their validity ranges, and the period-coverage WARNINGs.
        props.putObject("parameterSets").put("type", "array");
        ObjectNode pw = props.putObject("periodWarnings");
        pw.put("type", "array");
        pw.putObject("items").put("type", "string");
        if (sp.enabled()) {
            ObjectNode v = props.putObject("vocabularies");
            v.put("type", "array");
            v.putObject("items").put("type", "object");
            v.put("description", "per vocabulaire: name, default, language, complete, en de declaraties"
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
        String name = descriptor.service() == null ? "service" : nvl(descriptor.service().name());
        String kebab = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        String vocab = vocabulary == null || vocabulary.equalsIgnoreCase(Naming.CANONICAL) ? ""
                : "." + vocabulary.toLowerCase();
        return (kebab.isEmpty() ? "service" : kebab) + vocab + ".json";
    }

    private static List<ServiceDescriptor.Entrypoint> eps(ServiceDescriptor d) {
        return d.entrypoints() == null ? List.of() : d.entrypoints();
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
