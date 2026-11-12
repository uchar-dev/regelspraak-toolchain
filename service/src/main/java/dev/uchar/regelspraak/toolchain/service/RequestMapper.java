package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.uchar.regelspraak.cases.CaseInput;
import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.FactType;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.Role;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConstructorValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@link Request} JSON tree onto a {@link World} of fact beans + globals + rekendatum, validating
 * every field against the resolved model and collecting typed {@link Violation}s (each carrying its FIELD
 * PATH). The beans are not made here: the validated request is written as the input of a {@code uchar-case-v0} case,
 * {@code {"asOfDate", "input": {"instances": [...]}}} with the ids of the request as labels, and uchar's input step
 * reads that case ({@link CaseInput#of}), the one its replay reads every case with, so the service puts in the facts
 * the replay puts in on the same input. A role link uchar refuses names the link the case wrote; a table of the links
 * (id, role key, target id) gives the field of the request that wrote it: a second, different target on a to-one role
 * (WF-013, {@link CaseInput.RoleCollision}) is a {@link Violation.Kind#WRONG_TYPE}, a link the bean model cannot hold
 * ({@link CaseInput.RoleRefusal}) a {@link Violation.Kind#UNSUPPORTED}, each with uchar's sentence. On any
 * violation it does not throw: it returns the violations, beside the world uchar read from the fields that validated
 * (none when uchar refused a link), so a caller (fase 3) can turn the full set into one 400 naming every bad field.
 *
 * <p>A number its declaration does not hold is no violation but uchar's evaluation fault EF-004 on the input, which
 * uchar's input fence raises ({@link CaseRunner#inputFits}): for an attribute uchar's input step raises it and stops at
 * the first, for a parameter, which the service binds itself, the mapper asks the fence. The result returns those
 * faults, in that order, for the caller to answer as an evaluation fault.
 *
 * <p>A dimensioned attribute takes its input per cell, in the form of uchar's case: {@code {"cells": [{"labels":
 * [...], "value": ...}, ...]}}, one label per dimension in their declared order, each value as the attribute's datatype
 * takes it; a null value is an empty cell. uchar's input step reads the cells (RUNTIME-SEMANTICS ch. 5.8c): labels that
 * name no cell (EF-034) and a cell given twice (EF-035) it refuses before any session, as it refuses EF-004 per cell,
 * and the result returns them as faults of the kind {@link EvaluationFault.Kind#CELL_FAULT}.
 *
 * <p>A time-dependent parameter takes its timeline ({@link TimelineInput}): uchar's branch reads it whole, as a
 * {@code List<Period>} global, and a segment may leave out {@code from} or {@code to}, an open bound. A scalar on such a
 * parameter and a timeline on another are violations.
 *
 * <p>A time-dependent attribute takes its timeline in the same form, and a time-dependent characteristic the periods in
 * which it holds, {@code {"periods": [{"from", "to"}, ...]}} ({@link TimelineInput}); the mapper writes them into the
 * case as uchar's case gives them, a timeline under {@code attributes} and the periods under
 * {@code characteristicTimelines}, and uchar's input step reads them. A timeline on an attribute without one, periods
 * on a characteristic without them and a boolean on one with them are violations of the type. A scalar on a
 * time-dependent attribute is a form the service does not carry ({@link Violation.Kind#UNSUPPORTED}): uchar's case
 * reads it as the value of the period of the evaluation date (the asOfDate-bucket convention, RUNTIME-SEMANTICS
 * ch. 8.4), a convention of its replay that the request does not take over. A timeline on a grid of more than one
 * period or with a variable start uchar's case reader does not carry, and neither does the service.
 *
 * <p>An attribute with absence markers ({@link Markers}, uchar's RUNTIME-SEMANTICS ch. 9.5) takes a value of its
 * datatype, which is the value {@code waarde(…)}, or a marker, {@code {"marker": "onbekend"}} or {@code {"marker": "niet
 * van toepassing"}}, also as the value of a segment of its timeline; the mapper writes them into the case as uchar's case
 * gives a value of the sum type such an attribute holds, {@code {"constructor": "waarde", "payload": <case value>}} and
 * {@code {"constructor": "onbekend"}}. A marker the attribute does not declare is a
 * {@link Violation.Kind#UNKNOWN_MARKER}, a marker on an attribute without markers a {@link Violation.Kind#WRONG_TYPE}.
 *
 * <p>An unknown objecttype/attribuut/kenmerk/parameter/rol, an ill-typed value, an eenheid mismatch, an unknown
 * enumeratie-waarde, a dimensioned attribute without cells and cells on an attribute without dimensions are all
 * violations.
 *
 * <p>Every name is read by a {@link NameReader}: canonical or a designation in any vocabulary of the model, unless
 * the request pins its input vocabulary, under the name equality of uchar's case reader, and the case carries the
 * declared spelling of each object type, attribute, characteristic and role it read. A name that reads as two declarations is a
 * {@link Violation.Kind#AMBIGUOUS_NAME}; two names of one object that read as one declaration are
 * {@link Violation.Kind#MALFORMED}, since the service would otherwise keep one of the two values without saying so.
 * A violation names the field as the request spells it, and a declaration by its canonical name.
 */
public final class RequestMapper {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    /** The keys of the request, of an instance and of a relation; another key is refused with its path. */
    private static final List<String> REQUEST_KEYS = List.of("asOfDate", "rekendatum", "parameters", "instances",
            "relations");
    private static final List<String> INSTANCE_KEYS = List.of("type", "id", "attributes", "characteristics", "roles");
    private static final List<String> RELATION_KEYS = List.of("factType", "roles");
    /** The {@code dateTime} lexeme of {@code uchar-case-v0}: the seconds always written, a fraction of whole
     *  milliseconds only where there is one. */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final RegelSpraakModel model;
    private final DroolsBackend.Build build;
    private final NameReader reader;

    /** @param model the resolved model the request is validated against
     *  @param build uchar's build of the entry point, whose input step reads the request ({@link CaseInput#of}) */
    public RequestMapper(RegelSpraakModel model, DroolsBackend.Build build) {
        this(model, build, new NameReader(model, null));
    }

    /** As {@link #RequestMapper(RegelSpraakModel, DroolsBackend.Build)}, reading the names with {@code reader}. */
    RequestMapper(RegelSpraakModel model, DroolsBackend.Build build, NameReader reader) {
        this.model = model;
        this.build = java.util.Objects.requireNonNull(build, "build");
        this.reader = reader;
    }

    /** The mapped world, the id→objecttype map (for the response builder), the violations produced while
     *  mapping (empty on success), and the values uchar's input refused before any session (EF-004, EF-034, EF-035;
     *  empty on success). The world is null when uchar refused the input. */
    public record Result(World world, Map<String, ObjectType> typePerLabel, List<Violation> violations,
                         List<EvaluationFault.InputFault> inputFaults) {}

    /** A role link the mapper writes into the case: under the {@code roles} of instance {@code id}, the key
     *  {@code role}, naming {@code doel}; uchar's {@link CaseInput.RoleError} names a refused link the same way. */
    private record Link(String id, String role, String target) {}

    /** What the mapper wrote into the case that uchar's input step refuses with an {@link IllegalArgumentException} of
     *  no public type: a date or date-time on {@code field} whose year the case lexeme does not carry (four digits, no
     *  sign), a violation of that field. */
    private record CaseRefusal(String field) {}

    public Result map(Request request) {
        List<Violation> s = new ArrayList<>();
        List<EvaluationFault.InputFault> inputFaults = new ArrayList<>();
        Map<String, Object> globals = new LinkedHashMap<>();
        Map<String, ObjectType> typePerLabel = new LinkedHashMap<>();
        // the instances of the case by id, in the order of the request, and the field of the request per link
        Map<String, ObjectNode> instances = new LinkedHashMap<>();
        Map<Link, String> paths = new LinkedHashMap<>();
        // the years uchar's input step will refuse, in the order it reads the case: the evaluation date first, then the
        // attributes in the order of the case; it stops at the first
        List<CaseRefusal> refusals = new ArrayList<>();
        unknownKeys(request.tree(), REQUEST_KEYS, "", s);

        // 1. peildatum / rekendatum — ONE date authority (fase 5). 'peildatum' is the preferred name, 'rekendatum'
        //    the existing alias: the two are the SAME notion. Both present and UNEQUAL is a violation naming BOTH
        //    fields and BOTH values (a caller must never give two conflicting dates); otherwise the date is the one
        //    present (peildatum wins when both are given and equal). The unified date drives World.evaluationDate —
        //    which the vintage-selection ALSO reads, so a rule reading Rekendatum and the vintage follow ONE date.
        LocalDate asOfDate = readDate(request.asOfDate(), "asOfDate", s);
        LocalDate evaluationDate = readDate(request.evaluationDate(), "rekendatum", s);
        LocalDate date;
        if (asOfDate != null && evaluationDate != null && !asOfDate.equals(evaluationDate)) {
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, "asOfDate",
                    "asOfDate " + asOfDate + " en rekendatum " + evaluationDate + " zijn dezelfde datumautoriteit"
                            + " maar verschillen — geef er één (of gelijke waarden)"));
            date = asOfDate;
        } else {
            date = asOfDate != null ? asOfDate : evaluationDate;   // peildatum wins when both equal
        }
        if (date != null && !inCaseForm(date.getYear()))
            refusals.add(new CaseRefusal(asOfDate != null ? "asOfDate" : "rekendatum"));

        // 2. parameters
        JsonNode params = request.parameters();
        if (params != null && !params.isNull()) {
            if (!params.isObject())
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, "parameters", "parameters moet een object zijn"));
            else {
                Duplicate duplicate = new Duplicate("de parameters");
                params.fields().forEachRemaining(e -> {
                    String name = e.getKey();
                    String field = "parameters." + name;
                    Read g = read(reader.parameter(name), name, field, duplicate, s);
                    if (g.refused()) return;
                    RegelSpraakModel.Parameter p = g.name() == null ? null : DescriptorLoader.parameter(model, g.name());
                    if (p == null) {
                        s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field,
                                "onbekende parameter '" + name + "'" + reader.pinnedClause()));
                        return;
                    }
                    Violation form = TimelineInput.formViolation(p, e.getValue(), field);
                    if (form != null) {
                        s.add(form);
                        return;
                    }
                    Object decoded;
                    try {
                        decoded = TimelineInput.timeDependent(p)
                                ? TimelineInput.decode(p, e.getValue(), field, (v, f, sink) -> scalar(p, v, f, sink), s)
                                : scalar(p, e.getValue(), field, s);
                    } catch (CaseRunner.InputPrecisionFault f) {
                        inputFaults.add(new EvaluationFault.InputFault(EvaluationFault.Kind.INPUT_PRECISION_FAULT, f));
                        return;
                    }
                    if (decoded != null) globals.put(globalName(p.name()), decoded);
                });
            }
        }

        // 3. instanties (case instances + attributen + kenmerken)
        JsonNode insts = request.instances();
        if (insts == null || insts.isNull()) {
            s.add(Violation.of(Violation.Kind.MALFORMED, "instances", "instances ontbreekt"));
        } else if (!insts.isArray()) {
            s.add(Violation.of(Violation.Kind.MALFORMED, "instances", "instances moet een array zijn"));
        } else {
            for (int i = 0; i < insts.size(); i++) {
                JsonNode in = insts.get(i);
                String path = "instances[" + i + "]";
                unknownKeys(in, INSTANCE_KEYS, path, s);
                JsonNode typeN = in.get("type");
                JsonNode idN = in.get("id");
                if (typeN == null || !typeN.isTextual()) {
                    s.add(Violation.of(Violation.Kind.MALFORMED, path + ".type", "type ontbreekt of is geen tekst"));
                    continue;
                }
                if (idN == null || !idN.isTextual()) {
                    s.add(Violation.of(Violation.Kind.MALFORMED, path + ".id", "id ontbreekt of is geen tekst"));
                    continue;
                }
                String id = idN.asText();
                Read type = read(reader.objectType(typeN.asText()), typeN.asText(), path + ".type", null, s);
                if (type.refused()) continue;
                ObjectType ot = type.name() == null ? null : DescriptorLoader.objectType(model, type.name());
                if (ot == null) {
                    s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, path + ".type",
                            "onbekend objecttype '" + typeN.asText() + "'" + reader.pinnedClause()));
                    continue;
                }
                if (instances.containsKey(id)) {
                    s.add(Violation.of(Violation.Kind.MALFORMED, path + ".id",
                            "dubbel id '" + id + "'"));
                    continue;
                }
                ObjectNode instance = NF.objectNode();
                instance.put("label", id);
                instance.put("objectType", ot.name);
                instances.put(id, instance);
                typePerLabel.put(id, ot);
                setAttributes(ot, instance, in.get("attributes"), path + ".attributes", refusals, s);
                setCharacteristics(ot, instance, in.get("characteristics"), path + ".characteristics", refusals, s);
            }
        }

        // 4. rollen — inline per instance, then top-level relaties (both after all instances exist)
        if (insts != null && insts.isArray())
            for (int i = 0; i < insts.size(); i++) {
                JsonNode in = insts.get(i);
                JsonNode idN = in.get("id");
                if (idN == null || !idN.isTextual()) continue;
                String id = idN.asText();
                ObjectType ot = typePerLabel.get(id);
                if (ot == null) continue;
                setRoles(ot, id, typePerLabel, in.get("roles"), "instances[" + i + "].roles", instances, paths, s);
            }
        setRelations(request.relations(), typePerLabel, instances, paths, s);

        // 5. uchar reads the case: the beans, the roles both ways (RUNTIME-SEMANTICS ch. 5.7a), the lists
        ObjectNode c = NF.objectNode();
        if (date != null) c.put("asOfDate", date.toString());
        ArrayNode cInstances = c.putObject("input").putArray("instances");
        instances.values().forEach(cInstances::add);
        CaseInput input = null;
        try {
            input = CaseInput.of(build, c);
        } catch (CaseInput.RoleCollision b) {
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, path(paths, b), b.getMessage()));
        } catch (CaseInput.RoleRefusal w) {
            s.add(Violation.of(Violation.Kind.UNSUPPORTED, path(paths, w), w.getMessage()));
        } catch (CaseRunner.Excluded x) {
            // a form uchar's input step does not carry that the validation above let through: no link names it
            s.add(Violation.of(Violation.Kind.UNSUPPORTED, "instances", x.getMessage()));
        } catch (CaseRunner.InputPrecisionFault f) {
            inputFaults.add(new EvaluationFault.InputFault(EvaluationFault.Kind.INPUT_PRECISION_FAULT, f));
        } catch (CaseInput.CellFault f) {
            inputFaults.add(new EvaluationFault.InputFault(EvaluationFault.Kind.CELL_FAULT, f));
        } catch (IllegalArgumentException f) {
            // after the validation above uchar's input step can refuse a year the refusals list, and it stops at the
            // first it reads; anything else is a defect of the case the mapper wrote, and fails here
            if (refusals.isEmpty()) throw f;
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, refusals.get(0).field(), f.getMessage()));
        }
        return new Result(input == null ? null : new World(input, globals, date), typePerLabel, s, inputFaults);
    }

    /** The field of the request that wrote the link uchar refused. A link the mapper did not write is a broken promise
     *  of the input step, and fails here. */
    private static String path(Map<Link, String> paths, CaseInput.RoleError f) {
        String path = paths.get(new Link(f.label(), f.role(), f.target()));
        if (path == null)
            throw new IllegalStateException("uchars invoerstap weigerde de link " + f.label() + " -" + f.role() + "-> "
                    + f.target() + ", die het verzoek niet schreef: " + ((RuntimeException) f).getMessage());
        return path;
    }

    /** Adds a {@link Violation.Kind#MALFORMED} on every key of {@code object} outside {@code known}, on its path below
     *  {@code path} (empty: the top of the request): the service would otherwise answer as if the key were not
     *  there, an old name ({@code peildatum}, {@code attributen}) included. */
    private static void unknownKeys(JsonNode object, List<String> known, String path, List<Violation> s) {
        if (object == null || !object.isObject()) return;
        object.fieldNames().forEachRemaining(key -> {
            if (!known.contains(key))
                s.add(Violation.of(Violation.Kind.MALFORMED, path.isEmpty() ? key : path + "." + key,
                        "onbekende sleutel '" + key + "'" + (path.isEmpty() ? " in het verzoek" : " in " + path)
                                + "; de sleutels zijn " + String.join(", ", known)));
        });
    }

    /** Parse an ISO-datum node (yyyy-MM-dd) into a {@link LocalDate}, or {@code null} when absent/null. A
     *  non-textual or unparseable value adds a {@link Violation.Kind#WRONG_TYPE} on {@code field}. Shared by
     *  the {@code asOfDate} and {@code evaluationDate} legs of the one date authority (fase 5). */
    private static LocalDate readDate(JsonNode node, String field, List<Violation> s) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) {
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, field + " moet een ISO-datum (yyyy-MM-dd) zijn"));
            return null;
        }
        try { return LocalDate.parse(node.asText()); }
        catch (DateTimeParseException e) {
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, field,
                    "'" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)"));
            return null;
        }
    }

    // ---- the case instance ----

    /** Whether the case lexeme of a date carries year {@code jaar}: four digits, no sign. */
    private static boolean inCaseForm(int year) {
        return year >= 0 && year <= 9999;
    }

    /** The {@code uchar-case-v0} value of {@code w}, a value {@link ValueCoder} decoded for {@code dt}: what uchar's
     *  case reader reads back as that same Java value. Null after adding the violation of a value the case form cannot
     *  carry: a date-time with a fraction finer than a millisecond. A date or date-time whose year the lexeme does not
     *  carry is written as {@link LocalDate#toString} writes it, for uchar's reader to refuse, and {@code field} is added
     *  to {@code refusals}. */
    private static JsonNode caseValue(Object w, Datatype dt, String field, List<CaseRefusal> refusals,
                                       List<Violation> s) {
        if (w == null) return NF.nullNode();
        ObjectNode n = NF.objectNode();
        // the plain form is the case lexeme; ValueCoder refused the negative scale it could not write as given
        if (w instanceof BigDecimal b) return n.put("number", b.toPlainString());
        if (w instanceof LocalDate d) {
            if (!inCaseForm(d.getYear())) refusals.add(new CaseRefusal(field));
            return n.put("date", d.toString());
        }
        if (w instanceof Boolean b) return n.put("boolean", b);
        if (w instanceof String text) return n.put(dt instanceof Datatype.Enumeration ? "enumeration" : "text", text);
        if (w instanceof LocalDateTime dt2) {
            if (dt2.getNano() % 1_000_000 != 0) {
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "'" + dt2 + "' heeft een fractie van een"
                        + " seconde fijner dan een milliseconde; de dienst draagt hele milliseconden"));
                return null;
            }
            if (!inCaseForm(dt2.getYear())) refusals.add(new CaseRefusal(field));
            String lexeme = DATE_TIME.format(dt2);
            if (dt2.getNano() != 0) lexeme += String.format(".%03d", dt2.getNano() / 1_000_000);
            return n.put("dateTime", lexeme);
        }
        throw new IllegalStateException("WaardeCoder gaf voor '" + field + "' een " + w.getClass().getSimpleName()
                + ", waarvoor de case geen vorm heeft");
    }

    private static ArrayNode listIn(ObjectNode n, String field) {
        JsonNode l = n.get(field);
        return l instanceof ArrayNode a ? a : n.putArray(field);
    }

    private static ObjectNode objectIn(ObjectNode n, String field) {
        JsonNode o = n.get(field);
        return o instanceof ObjectNode on ? on : n.putObject(field);
    }

    private void setAttributes(ObjectType ot, ObjectNode instance, JsonNode attrs, String path,
                               List<CaseRefusal> refusals, List<Violation> s) {
        if (attrs == null || attrs.isNull()) return;
        if (!attrs.isObject()) { s.add(Violation.of(Violation.Kind.MALFORMED, path, "attributes moet een object zijn")); return; }
        Duplicate duplicate = new Duplicate("deze instantie");
        attrs.fields().forEachRemaining(e -> {
            String name = e.getKey();
            String field = path + "." + name;
            Read g = read(reader.attribute(ot, name), name, field, duplicate, s);
            if (g.refused()) return;
            String canonical = g.name();
            Datatype dt = canonical == null ? null : ot.attribute(canonical);
            if (dt == null) {
                s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field,
                        "onbekend attribuut '" + name + "' op objecttype '" + ot.name + "'" + reader.pinnedClause()));
                return;
            }
            Markers markers = Markers.of(ot, canonical);
            if (!ot.dimensionsOf(canonical).isEmpty()) {
                JsonNode cells = cells(ot, canonical, name, dt, markers, e.getValue(), field, refusals, s);
                if (cells != null) objectIn(instance, "attributes").set(canonical, cells);
                return;
            }
            if (isCells(e.getValue())) {
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "attribuut '" + name + "' heeft geen dimensies;"
                        + " cells is de invoer van een gedimensioneerd attribuut"));
                return;
            }
            if (isTemporal(ot, canonical)) {
                JsonNode timeline = timeline(ot, canonical, name, dt, markers, e.getValue(), field, refusals, s);
                if (timeline != null) objectIn(instance, "attributes").set(canonical, timeline);
                return;
            }
            if (TimelineInput.isTimeline(e.getValue())) {
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "attribuut '" + name + "' is niet"
                        + " tijdsafhankelijk: zijn waarde is één waarde, geen tijdlijn"));
                return;
            }
            JsonNode caseValue = attributeValue(e.getValue(), dt, markers, name, field, refusals, s);
            if (caseValue != null) objectIn(instance, "attributes").set(canonical, caseValue);
        });
    }

    /**
     * The case value of the value {@code node} of an attribute of datatype {@code dt} ({@code name} as the request
     * writes it) on {@code field}, or null after adding its violations. On an attribute with {@code markers} a marker
     * object is the constructor of that marker, and any other value the constructor {@code waarde} with that value, a
     * value of the payload datatype, as its payload: the form in which uchar's case gives a value of the sum type such an
     * attribute holds (uchar's RUNTIME-SEMANTICS ch. 9.4, 9.5); null stays null, leeg. A marker object on an attribute
     * without markers is a violation.
     */
    private JsonNode attributeValue(JsonNode node, Datatype dt, Markers markers, String name, String field,
                                    List<CaseRefusal> refusals, List<Violation> s) {
        if (Markers.isMarker(node)) {
            if (markers == null) {
                s.add(Markers.without(name, field));
                return null;
            }
            String marker = markers.read(node, name, field, s);
            return marker == null ? null : NF.objectNode().put("constructor", marker);
        }
        Datatype payload = markers == null ? dt : markers.payload();
        JsonNode value = value(node, payload, field, s);
        if (value == null) return null;
        ValueCoder.Result r = ValueCoder.decode(value, payload, field);
        s.addAll(r.violations());
        if (!r.violations().isEmpty()) return null;
        JsonNode caseValue = caseValue(r.value(), payload, field, refusals, s);
        if (markers == null || caseValue == null || caseValue.isNull()) return caseValue;
        ObjectNode constructed = NF.objectNode().put("constructor", RegelSpraakModel.VALUE_CONSTRUCTOR);
        constructed.set("payload", caseValue);
        return constructed;
    }

    /** The value {@code node} of a segment of a timeline of attribute {@code name} on {@code field}, as uchar's branch
     *  holds it: a value of datatype {@code dt}, or on an attribute with {@code markers} uchar's constructor value of a
     *  marker or of the value as the payload of {@code waarde}; null after adding its violations. */
    private Object segmentValue(JsonNode node, Datatype dt, Markers markers, String name, String field,
                                List<Violation> s) {
        if (Markers.isMarker(node)) {
            if (markers == null) {
                s.add(Markers.without(name, field));
                return null;
            }
            String marker = markers.read(node, name, field, s);
            return marker == null ? null : new ConstructorValue(marker, null);
        }
        Datatype payload = markers == null ? dt : markers.payload();
        JsonNode read = value(node, payload, field, s);
        if (read == null) return null;
        ValueCoder.Result r = ValueCoder.decode(read, payload, field);
        s.addAll(r.violations());
        if (!r.violations().isEmpty()) return null;
        return markers == null ? r.value() : new ConstructorValue(RegelSpraakModel.VALUE_CONSTRUCTOR, r.value());
    }

    /** The case value of a segment value {@link TimelineInput#segments} let through: a number, a text, or uchar's
     *  constructor value of a marker or of a number. */
    private static JsonNode segmentCaseValue(Object v) {
        if (v instanceof String t) return NF.objectNode().put("text", t);
        if (!(v instanceof ConstructorValue c)) return NF.objectNode().put("number", ((BigDecimal) v).toPlainString());
        ObjectNode n = NF.objectNode().put("constructor", c.constructor());
        if (c.payload() != null)
            n.set("payload", NF.objectNode().put("number", ((BigDecimal) c.payload()).toPlainString()));
        return n;
    }

    /** The scalar {@code node} of parameter {@code p} on {@code field}, decoded by its datatype, or null after adding
     *  the violations to {@code s}.
     *
     *  @throws CaseRunner.InputPrecisionFault a number with more decimals than {@code p} declares (EF-004) */
    private Object scalar(RegelSpraakModel.Parameter p, JsonNode node, String field, List<Violation> s) {
        JsonNode value = value(node, p.datatype(), field, s);
        if (value == null) return null;
        ValueCoder.Result r = ValueCoder.decode(value, p.datatype(), field);
        s.addAll(r.violations());
        if (!r.violations().isEmpty()) return null;
        if (r.value() instanceof BigDecimal b)
            CaseRunner.inputFits(NF.objectNode().put("number", b.toPlainString()), p.datatype(), "parameter " + p.name());
        return r.value();
    }

    /**
     * The timeline of the time-dependent attribute {@code attribute} of {@code ot} ({@code name} as the request writes
     * it) as the case writes it, {@code {"timeline": [{"from", "to", "value": <case value>}, ...]}}, an open bound left
     * out; a null node for null, an empty timeline as an empty scalar is an empty value; null after adding the
     * violations of a value that is no timeline uchar's case reader carries. On an attribute with {@code markers} the value
     * of a segment is a marker or a value of the payload datatype ({@link #segmentValue}), written as uchar's case writes
     * a constructor value. A bound whose year the case lexeme does not
     * carry is written as {@link LocalDate#toString} writes it, for uchar's reader to refuse, and its field is added to
     * {@code refusals}, once the timeline has no violation.
     */
    private JsonNode timeline(ObjectType ot, String attribute, String name, Datatype dt, Markers markers, JsonNode value,
                              String field, List<CaseRefusal> refusals, List<Violation> s) {
        if (value == null || value.isNull()) return NF.nullNode();
        RegelSpraakModel.Granularity g = ot.granularityOf(attribute);
        String grid = g == null ? "" : " (" + TimelineInput.perGrid(g) + ")";
        if (!TimelineInput.isTimeline(value)) {
            s.add(Violation.of(Violation.Kind.UNSUPPORTED, field, "attribuut '" + name + "' is tijdsafhankelijk" + grid
                    + ": zijn invoer is een tijdlijn, " + TimelineInput.TIMELINE_FORM + "; een scalaire waarde, die"
                    + " uchars replay in de periode van de rekendatum legt, draagt de dienst niet"));
            return null;
        }
        Integer count = ot.timelineCountOf(attribute);
        if (g == null || count != null && count != 1 || model.hasVariableStart(ot, attribute)) {
            s.add(Violation.of(Violation.Kind.UNSUPPORTED, field, "attribuut '" + name + "' ligt op "
                    + (model.hasVariableStart(ot, attribute) ? "een tijdlijn met een variabel startpunt"
                            : "een raster van meer dan één periode") + ", waarop uchars case-lezer geen tijdlijn draagt"));
            return null;
        }
        List<TimelineInput.Segment> segments = TimelineInput.segments(new TimelineInput.Grid("attribuut '" + name + "'",
                g, ot.fixedStartPointOf(attribute), markers == null ? dt : markers.payload()), value, field,
                (v, f, sink) -> segmentValue(v, dt, markers, name, f, sink), s);
        if (segments == null) return null;
        ArrayNode out = NF.arrayNode();
        for (int k = 0; k < segments.size(); k++) {
            TimelineInput.Segment segment = segments.get(k);
            ObjectNode n = out.addObject();
            String at = field + ".timeline[" + k + "]";
            if (segment.from() != null) n.put("from", bound(segment.from(), at + ".from", refusals));
            if (segment.to() != null) n.put("to", bound(segment.to(), at + ".to", refusals));
            n.set("value", segmentCaseValue(segment.value()));
        }
        ObjectNode n = NF.objectNode();
        n.set("timeline", out);
        return n;
    }

    /** {@code d} as the case writes a bound on {@code field}; a year the case lexeme does not carry adds the field to
     *  {@code refusals}. */
    private static String bound(LocalDate d, String field, List<CaseRefusal> refusals) {
        if (!inCaseForm(d.getYear())) refusals.add(new CaseRefusal(field));
        return d.toString();
    }

    /** Whether {@code value} is the input of a dimensioned attribute: an object with {@code cells} and nothing else. */
    private static boolean isCells(JsonNode value) {
        return value != null && value.isObject() && value.size() == 1 && value.has("cells");
    }

    /**
     * The cells of the dimensioned attribute {@code attribute} of {@code ot} ({@code name} as the request writes it) as
     * the case writes them, {@code {"cells": [{"labels": [...], "value": <case value>}, ...]}}, the labels as the
     * request writes them, in the order of the request; null after adding the violations of a value that is no list of
     * cells. Labels that name no cell of the attribute or one cell twice are no violation: uchar's input step refuses
     * them (EF-034, EF-035, {@link CaseInput.CellFault}), before the values of the cells.
     */
    private JsonNode cells(ObjectType ot, String attribute, String name, Datatype dt, Markers markers, JsonNode value,
                           String field, List<CaseRefusal> refusals, List<Violation> s) {
        List<String> dimensions = ot.dimensionsOf(attribute);
        if (!isCells(value)) {
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "attribuut '" + name + "' is gedimensioneerd met "
                    + String.join(", ", dimensions) + ": zijn invoer is {\"cells\": [{\"labels\": [...], \"value\": ...}]},"
                    + " één label per dimensie, in die volgorde"));
            return null;
        }
        JsonNode cells = value.get("cells");
        if (!cells.isArray()) {
            s.add(Violation.of(Violation.Kind.MALFORMED, field + ".cells", "cells moet een array zijn"));
            return null;
        }
        int violations = s.size();
        List<CaseRefusal> ofValues = new ArrayList<>();
        ArrayNode out = NF.arrayNode();
        for (int k = 0; k < cells.size(); k++) {
            JsonNode c = cells.get(k);
            String cellField = field + ".cells[" + k + "]";
            if (!c.isObject()) {
                s.add(Violation.of(Violation.Kind.MALFORMED, cellField, "een cel is een object {labels, value}"));
                continue;
            }
            List<String> other = new ArrayList<>();
            c.fieldNames().forEachRemaining(f -> { if (!f.equals("labels") && !f.equals("value")) other.add(f); });
            if (!other.isEmpty()) {
                s.add(Violation.of(Violation.Kind.MALFORMED, cellField, "een cel heeft alleen labels en value, niet "
                        + String.join(", ", other)));
                continue;
            }
            JsonNode labels = c.get("labels");
            if (labels == null || !labels.isArray() || labels.valueStream().anyMatch(l -> !l.isTextual())) {
                s.add(Violation.of(Violation.Kind.MALFORMED, cellField + ".labels", "labels ontbreekt of is geen array"
                        + " van teksten"));
                continue;
            }
            if (!c.has("value")) {
                s.add(Violation.of(Violation.Kind.MALFORMED, cellField + ".value", "value ontbreekt"));
                continue;
            }
            JsonNode caseValue = attributeValue(c.get("value"), dt, markers, name, cellField + ".value", ofValues, s);
            if (caseValue == null) continue;
            ObjectNode cell = out.addObject();
            cell.set("labels", labels);
            cell.set("value", caseValue);
        }
        if (s.size() > violations) return null;
        refusals.addAll(ofValues);
        ObjectNode n = NF.objectNode();
        n.set("cells", out);
        return n;
    }

    private void setCharacteristics(ObjectType ot, ObjectNode instance, JsonNode characteristics, String path,
                                    List<CaseRefusal> refusals, List<Violation> s) {
        if (characteristics == null || characteristics.isNull()) return;
        if (!characteristics.isObject()) { s.add(Violation.of(Violation.Kind.MALFORMED, path, "characteristics moet een object zijn")); return; }
        Duplicate duplicate = new Duplicate("deze instantie");
        characteristics.fields().forEachRemaining(e -> {
            String name = e.getKey();
            String field = path + "." + name;
            Read g = read(reader.characteristic(ot, name), name, field, duplicate, s);
            if (g.refused()) return;
            String canonical = g.name();
            if (canonical == null || !DescriptorLoader.hasCharacteristic(ot, canonical)) {
                s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field,
                        "onbekend kenmerk '" + name + "' op objecttype '" + ot.name + "'" + reader.pinnedClause()));
                return;
            }
            JsonNode v = e.getValue();
            if (isTemporalCharacteristic(ot, canonical)) {
                if (!TimelineInput.isPeriods(v)) {
                    RegelSpraakModel.Granularity grid = ot.characteristicGranularityOf(canonical);
                    s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "kenmerk '" + name + "' is tijdsafhankelijk"
                            + (grid == null ? "" : " (" + TimelineInput.perGrid(grid) + ")")
                            + ": zijn invoer zijn de perioden waarin het geldt, " + TimelineInput.PERIODS_FORM));
                    return;
                }
                List<TimelineInput.Window> periods = TimelineInput.periods("kenmerk '" + name + "'", v, field, s);
                if (periods == null) return;
                ArrayNode out = objectIn(instance, "characteristicTimelines").putArray(canonical);
                for (int k = 0; k < periods.size(); k++) {
                    String at = field + ".periods[" + k + "]";
                    out.addObject().put("from", bound(periods.get(k).from(), at + ".from", refusals))
                            .put("to", bound(periods.get(k).to(), at + ".to", refusals));
                }
                return;
            }
            if (TimelineInput.isPeriods(v)) {
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "kenmerk '" + name + "' is niet tijdsafhankelijk:"
                        + " zijn waarde is een boolean, geen perioden"));
                return;
            }
            if (!v.isBoolean()) {
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "kenmerk '" + name + "' moet een boolean zijn"));
                return;
            }
            // a case names the characteristics an instance has; one it does not name, it has not
            if (v.booleanValue()) listIn(instance, "characteristics").add(canonical);
        });
    }

    // ---- names and values read by the NameReader ----

    /** A name read for the mapping: {@code name} to go on with — the declaration's canonical name in its declared
     *  spelling ({@link NameReader.Found}), null where nothing reads it (the caller refuses it with its own sentence)
     *  — or {@code refused} when its violation has been added. */
    private record Read(String name, boolean refused) {
        static final Read REFUSED = new Read(null, true);
    }

    /** {@code reading} for the mapping, adding the violation of an ambiguous name, and of a name that reads as a
     *  declaration another name of the same object ({@code duplicate}; null: nothing to compare with) already read. */
    private Read read(NameReader.Reading reading, String name, String field, Duplicate duplicate, List<Violation> s) {
        if (reading instanceof NameReader.Ambiguous d) {
            s.add(Violation.of(Violation.Kind.AMBIGUOUS_NAME, field, d.sentence()));
            return Read.REFUSED;
        }
        if (!(reading instanceof NameReader.Found g)) return new Read(null, false);
        if (duplicate != null) {
            String earlier = duplicate.earlier(g.declaration(), name);
            if (earlier != null) {
                s.add(Violation.of(Violation.Kind.MALFORMED, field, NameReader.description(g.declaration())
                        + " staat twee keer in " + duplicate.where() + ": als '" + earlier + "' en als '" + name + "'"));
                return Read.REFUSED;
            }
        }
        return new Read(g.name(), false);
    }

    /** The names one object of the request has read so far, by declaration: a second name of the same declaration is
     *  a second value for it. */
    private static final class Duplicate {
        private final String where;
        private final List<RegelSpraakModel.Designatable> declarations = new ArrayList<>();
        private final List<String> names = new ArrayList<>();

        Duplicate(String where) { this.where = where; }

        String where() { return where; }

        /** The name that already read {@code b}, or null after recording {@code naam} for it. */
        String earlier(RegelSpraakModel.Designatable b, String name) {
            for (int i = 0; i < declarations.size(); i++)
                if (NameReader.same(declarations.get(i), b)) return names.get(i);
            declarations.add(b);
            names.add(name);
            return null;
        }
    }

    /** An enumeration value read by the {@link NameReader}, as the node {@link ValueCoder} decodes: a designation
     *  becomes the value it names, a canonical spelling stays as written (the rules compare it as uchar compares
     *  declared names). Null after adding the violation of an ambiguous value, or of a value the pinned input
     *  vocabulary does not name; any other datatype, and an unknown value, go to the coder as they are. */
    private JsonNode value(JsonNode node, Datatype dt, String field, List<Violation> s) {
        if (!(dt instanceof Datatype.Enumeration e) || node == null || !node.isTextual()) return node;
        NameReader.Reading reading = reader.value(e, node.asText());
        if (reading instanceof NameReader.Ambiguous d) {
            s.add(Violation.of(Violation.Kind.AMBIGUOUS_NAME, field, d.sentence()));
            return null;
        }
        if (reading instanceof NameReader.Found g) return TextNode.valueOf(g.name());
        if (reader.pinnedInVocabulary()) {
            s.add(Violation.of(Violation.Kind.UNKNOWN_ENUM_VALUE, field, "'" + node.asText()
                    + "' is geen waarde van domein '" + e.domain() + "' (toegestaan met inputVocabulary '"
                    + reader.pinned() + "': " + reader.values(e) + ")"));
            return null;
        }
        return node;
    }

    // ---- rollen (inline) ----

    /** The inline roles of one instance, as the {@code roles} of its case instance under the canonical role name: a
     *  request instance with {@code roles} is the case instance with {@code roles}, so uchar's input step sets the
     *  facts the replay sets. A role name the model does not know is refused before anything is written, and so is an
     *  id whose objecttype does not fill the role (the role's type or a subtype of it): the input step falls back on the
     *  type pair, and would write such a link into another relation between the two types, or refuse it as a form the
     *  service does not carry. */
    private void setRoles(ObjectType ot, String id, Map<String, ObjectType> typePerLabel, JsonNode roles, String path,
                           Map<String, ObjectNode> instances, Map<Link, String> paths, List<Violation> s) {
        if (roles == null || roles.isNull()) return;
        if (!roles.isObject()) { s.add(Violation.of(Violation.Kind.MALFORMED, path, "roles moet een object zijn")); return; }
        Duplicate duplicate = new Duplicate("deze instantie");
        roles.fields().forEachRemaining(e -> {
            String role = e.getKey();
            String field = path + "." + role;
            Read wasRead = read(reader.roleFrom(ot, role), role, field, duplicate, s);
            if (wasRead.refused()) return;
            String canonical = wasRead.name();
            ObjectType targetType = canonical == null ? null : model.roleTargetTypeToSubject(ot, canonical);
            if (targetType == null) {
                s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field,
                        "onbekende rol '" + role + "' vanaf objecttype '" + ot.name + "'" + reader.pinnedClause()));
                return;
            }
            List<String> targets = ids(e.getValue(), role, field, s);
            if (targets == null) return;
            for (String label : targets) {
                ObjectType labelType = typePerLabel.get(label);
                if (labelType == null) {
                    s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field,
                            "rol '" + role + "' verwijst naar onbekend id '" + label + "'"));
                    continue;
                }
                if (!labelType.isA(targetType)) {
                    s.add(wrongType(field, role, targetType, label, labelType));
                    continue;
                }
                writeLink(instances, paths, id, canonical, label, field);
            }
        });
    }

    /** Writes the link {@code id} -{@code role}-> {@code doel} into the case, and records {@code field} as the field of
     *  the request that wrote it; a link written twice keeps the first field. */
    private static void writeLink(Map<String, ObjectNode> instances, Map<Link, String> paths, String id, String role,
                                    String target, String field) {
        listIn(objectIn(instances.get(id), "roles"), role).add(target);
        paths.putIfAbsent(new Link(id, role, target), field);
    }

    private static Violation wrongType(String field, String role, ObjectType targetType, String id, ObjectType type) {
        return Violation.of(Violation.Kind.WRONG_TYPE, field, "rol '" + role + "' verwacht een instantie van"
                + " objecttype '" + targetType.name + "', maar id '" + id + "' is van objecttype '" + type.name + "'");
    }

    // ---- relaties (top-level) — desugar to a role link of the case ----

    /** Each relation as one role link of the case, on the end whose role toward the other end is to-one, keyed by the
     *  role the other end fills: the link a case writes on that instance. uchar's input step establishes the relation
     *  both ways from it (RUNTIME-SEMANTICS ch. 5.7a): the far field of a 1:1 relation, the reverse leg of a reciprocal
     *  one. Where neither direction is to-one the link goes on the first end, and the input step reads it as one link
     *  of the fact type, from whichever end the case writes it ({@code Input.link}). */
    private void setRelations(JsonNode relations, Map<String, ObjectType> typePerLabel, Map<String, ObjectNode> instances,
                             Map<Link, String> paths, List<Violation> s) {
        if (relations == null || relations.isNull()) return;
        if (!relations.isArray()) { s.add(Violation.of(Violation.Kind.MALFORMED, "relations", "relations moet een array zijn")); return; }
        for (int i = 0; i < relations.size(); i++) {
            JsonNode rel = relations.get(i);
            String path = "relations[" + i + "]";
            unknownKeys(rel, RELATION_KEYS, path, s);
            JsonNode ftN = rel.get("factType");
            JsonNode rolesN = rel.get("roles");
            if (ftN == null || !ftN.isTextual()) {
                s.add(Violation.of(Violation.Kind.MALFORMED, path + ".factType", "factType ontbreekt of is geen tekst"));
                continue;
            }
            Read factType = read(reader.factType(ftN.asText()), ftN.asText(), path + ".factType", null, s);
            if (factType.refused()) continue;
            FactType ft = factType.name() == null ? null : factType(factType.name());
            if (ft == null) {
                s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, path + ".factType",
                        "onbekend feittype '" + ftN.asText() + "'" + reader.pinnedClause()));
                continue;
            }
            if (rolesN == null || !rolesN.isObject()) {
                s.add(Violation.of(Violation.Kind.MALFORMED, path + ".roles", "roles ontbreekt of is geen object"));
                continue;
            }
            // a violation names a role's field as the request wrote it: the declared name a role is read as -> the key
            Map<String, String> written = new java.util.HashMap<>();
            rolesN = rolesRead(ft, rolesN, path + ".roles", written, s);
            if (rolesN == null) continue;
            java.util.function.UnaryOperator<String> field = role -> path + ".roles." + written.getOrDefault(role, role);
            // a binary fact type has two roles; a reciprocal one (RegelSpraak §13.3.9.2) relates an object type to itself
            // through one role, which then names the two ids in a list
            List<Role> roles = ft.roles;
            if (roles.size() != 2 && !(ft.reciprocal && roles.size() == 1)) {
                s.add(Violation.of(Violation.Kind.UNSUPPORTED, path,
                        "alleen binaire (of wederkerige) feittypen worden ondersteund door de dienst-kern (feittype '"
                                + ft.name + "')"));
                continue;
            }
            Map<String, List<String>> ids = new LinkedHashMap<>();
            for (Role r : roles) {
                JsonNode v = rolesN.get(r.name());
                List<String> read = v == null ? null : ids(v, r.name(), field.apply(r.name()), s);
                if (read != null) ids.put(r.name(), read);
            }
            if (ids.size() != rolesN.size()) continue;
            for (String[] link : links(ids, roles, ft, path, s))
                relationLink(ft, link[0], link[1], link[2], link[3], typePerLabel, field, instances, paths, s);
        }
    }

    /** The ends of the links a relation names, each {@code {roleA, idA, roleB, idB}}, from the ids per role: the two
     *  ids of the one role of a reciprocal fact type, or else one id per role, where one role may name a list and
     *  gives a link per id of it; an empty list on it gives none. Empty after adding the violation of another count. */
    private static List<String[]> links(Map<String, List<String>> ids, List<Role> roles, FactType ft, String path,
                                        List<Violation> s) {
        List<String[]> links = new ArrayList<>();
        if (roles.size() == 1) {
            List<String> both = ids.get(roles.get(0).name());
            if (both != null && both.size() == 2)
                links.add(new String[]{roles.get(0).name(), both.get(0), roles.get(0).name(), both.get(1)});
        } else if (ids.size() == 2) {
            String roleA = roles.get(0).name(), roleB = roles.get(1).name();
            List<String> as = ids.get(roleA), bs = ids.get(roleB);
            if (as.size() > 1 && bs.size() > 1) {
                s.add(Violation.of(Violation.Kind.MALFORMED, path + ".roles", "hoogstens één rol van een relatie noemt"
                        + " een lijst van meer dan één id (feittype '" + ft.name + "')"));
                return List.of();
            }
            for (String a : as) for (String b : bs) links.add(new String[]{roleA, a, roleB, b});
            if (as.isEmpty() || bs.isEmpty()) return links;
        }
        if (links.isEmpty())
            s.add(Violation.of(Violation.Kind.MALFORMED, path + ".roles",
                    "relatie moet precies twee rol-eindpunten noemen (feittype '" + ft.name + "')"));
        return links;
    }

    /** One link of a relation of {@code ft} between {@code idA} in {@code roleA} and {@code idB} in {@code roleB},
     *  written into the case on the end whose role toward the other is to-one ({@link #setRelations}). */
    private void relationLink(FactType ft, String roleA, String idA, String roleB, String idB,
                              Map<String, ObjectType> typePerLabel, java.util.function.UnaryOperator<String> field,
                              Map<String, ObjectNode> instances, Map<Link, String> paths, List<Violation> s) {
        ObjectType otA = typePerLabel.get(idA), otB = typePerLabel.get(idB);
        if (otA == null) { s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field.apply(roleA), "onbekend id '" + idA + "'")); return; }
        if (otB == null) { s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, field.apply(roleB), "onbekend id '" + idB + "'")); return; }
        // the input step falls back on the type pair: an id that does not fill its role would be written into
        // another relation between the two types, as on an inline rol
        boolean fillsA = fillsRole(ft, roleA, idA, otA, field.apply(roleA), s),
                fillsB = fillsRole(ft, roleB, idB, otB, field.apply(roleB), s);
        if (!fillsA || !fillsB) return;
        // the link goes where its field is: A holds B in a to-one field named after B's role, or else B holds A.
        // The role NAME disambiguates when the same objecttype pair is joined by more than one feittype.
        if (!model.toManyRole(otA, otB, roleB) || model.toManyRole(otB, otA, roleA))
            writeLink(instances, paths, idA, roleB, idB, field.apply(roleB));
        else
            writeLink(instances, paths, idB, roleA, idA, field.apply(roleA));
    }

    /** The ids role {@code role} names on {@code field}: one id, or a list of ids; null after adding the violation of a
     *  value that is neither (null, an object, a number, a list with another element), which would otherwise be
     *  read as the id it prints as. */
    private static List<String> ids(JsonNode value, String role, String field, List<Violation> s) {
        if (value.isTextual()) return List.of(value.asText());
        if (value.isArray() && value.valueStream().allMatch(JsonNode::isTextual))
            return value.valueStream().map(JsonNode::asText).toList();
        s.add(Violation.of(Violation.Kind.MALFORMED, field, "rol '" + role + "' noemt een id (tekst) of een lijst ids,"
                + " niet " + value));
        return null;
    }

    /** The roles of a relation keyed by their declared names, which {@link #setRelations} looks them up by: each written
     *  name read as a role of {@code ft}, and in {@code written} the key the request wrote for that declared name; null
     *  after adding the violation of an ambiguous or a repeated role, or of a name no role of {@code ft} answers to. */
    private JsonNode rolesRead(FactType ft, JsonNode rolesN, String path, Map<String, String> written,
                               List<Violation> s) {
        com.fasterxml.jackson.databind.node.ObjectNode wasRead =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        Duplicate duplicate = new Duplicate("de relatie");
        boolean error = false;
        for (var it = rolesN.fields(); it.hasNext(); ) {
            var e = it.next();
            NameReader.Reading reading = reader.roleIn(ft, e.getKey());
            if (read(reading, e.getKey(), path + "." + e.getKey(), duplicate, s).refused()) { error = true; continue; }
            if (!(reading instanceof NameReader.Found g)) {
                s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, path + "." + e.getKey(), "onbekende rol '"
                        + e.getKey() + "' in feittype '" + ft.name + "'" + reader.pinnedClause()));
                error = true;
                continue;
            }
            String declared = g.declaration().name();
            wasRead.set(declared, e.getValue());
            written.put(declared, e.getKey());
        }
        return error ? null : wasRead;
    }

    /** Whether {@code id} (of objecttype {@code type}) fills the role {@code role} of {@code ft}, its declared type or a
     *  subtype of it; if not, a {@link Violation.Kind#WRONG_TYPE} on {@code field}, that role's field. */
    private boolean fillsRole(FactType ft, String role, String id, ObjectType type, String field, List<Violation> s) {
        for (Role r : ft.roles) {
            if (!r.name().equals(role)) continue;
            ObjectType targetType = model.objectTypeByName(r.objectTypeName());
            if (targetType == null || type.isA(targetType)) return true;
            s.add(wrongType(field, role, targetType, id, type));
            return false;
        }
        return true;
    }

    private FactType factType(String name) {
        FactType ft = model.factTypes.get(name);
        if (ft != null) return ft;
        for (FactType f : model.factTypes.values()) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Whether attribute {@code naam} on {@code ot} is a §13.3.6 tijdlijn (temporal, {@code List<Segment>}) —
     *  asked over the WHOLE chain (IR-SPEC ch. 2.2), not the own maps. An inherited timeline read as a scalar
     *  is not a missing binding but a wrong judgement: the mapper would accept a scalar and write it as a scalar of
     *  the case, which uchar's input step reads as the asOfDate-bucket scalar, a convention the service does not
     *  carry, instead of a violation naming the field.
     *  The model's chain walk is case-INSENSITIVE, exactly as this scan was. */
    static boolean isTemporal(ObjectType ot, String name) {
        return ot.granularityOf(name) != null || ot.timelineCountOf(name) != null;
    }

    /** The kenmerk analogue of {@link #isTemporal} — a §13.3.6.7 tijdlijn kenmerk (a {@code List<Segment>}
     *  field, its true periods), which takes its periods; chain-closed for the same reason. */
    static boolean isTemporalCharacteristic(ObjectType ot, String name) {
        return ot.characteristicGranularityOf(name) != null || ot.characteristicTimelineCountOf(name) != null;
    }

    /** The parameter GLOBAL name the exec-model branch registers — {@link Namen#ident}(naam,false), the
     *  camelCase form (the formula uchar's {@code TmsTranspiler} names a parameter's global with, and the name uchar's
     *  entry asks a consumer to set; recomputed here because the transpiler keeps that step private). */
    static String globalName(String name) { return Namen.ident(name, false); }

    /** The bean property name for a RegelSpraak name — {@link Namen#ident}(naam,false), the SAME
     *  camelCase form {@link FactModelGenerator} names the field/getter/setter with (non-letter/digit runs
     *  collapsed, so a hyphenated "datum-tijd …" maps to "dateTime…"). Handing this to
     *  {@link RegelSpraakRuntime#set}/{@link RegelSpraakRuntime#get} (which prepend set/get + PascalCase over
     *  whitespace-free input) resolves to exactly the generated accessor — reusing the bridge without a naming
     *  divergence. */
    static String prop(String name) { return Namen.ident(name, false); }
}
