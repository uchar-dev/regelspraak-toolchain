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
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Namen;

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
 * <p>Unsupported by the service core (a clear violation, never a silent accept): a dimensie- or
 * tijdlijn-valued attribute (no golden situatie uses one). An unknown objecttype/attribuut/kenmerk/parameter/
 * rol, an ill-typed value, an eenheid mismatch, and an unknown enumeratie-waarde are all violations.
 *
 * <p>Every name is read by a {@link NameReader}: canonical or a designation in any vocabulary of the model, unless
 * the request pins its input vocabulary. A name that reads as two declarations is a
 * {@link Violation.Kind#AMBIGUOUS_NAME}; two names of one object that read as one declaration are
 * {@link Violation.Kind#MALFORMED}, since the service would otherwise keep one of the two values without saying so.
 * A violation names the field as the request spells it, and a declaration by its canonical name.
 */
public final class RequestMapper {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
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

    /** The mapped world, the id→objecttype map (for the response builder), and the violations produced while
     *  mapping (empty on success). The world is null when uchar refused the input. */
    public record Result(World world, Map<String, ObjectType> typePerLabel, List<Violation> violations) {}

    /** A role link the mapper writes into the case: under the {@code roles} of instance {@code id}, the key
     *  {@code role}, naming {@code doel}; uchar's {@link CaseInput.RoleError} names a refused link the same way. */
    private record Link(String id, String role, String target) {}

    public Result map(Request request) {
        List<Violation> s = new ArrayList<>();
        Map<String, Object> globals = new LinkedHashMap<>();
        Map<String, ObjectType> typePerLabel = new LinkedHashMap<>();
        // the instances of the case by id, in the order of the request, and the field of the request per link
        Map<String, ObjectNode> instances = new LinkedHashMap<>();
        Map<Link, String> paths = new LinkedHashMap<>();
        // the fields whose date or date-time has a year the case lexeme does not carry (four digits, no sign), in the
        // order uchar's input step reads them: the evaluation date first, then the attributes in the order of the case
        List<String> yearOutsideCaseForm = new ArrayList<>();

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
        if (date != null && !inCaseForm(date.getYear())) yearOutsideCaseForm.add(asOfDate != null ? "asOfDate" : "rekendatum");

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
                    JsonNode value = value(e.getValue(), p.datatype(), field, s);
                    if (value == null) return;
                    ValueCoder.Result r = ValueCoder.decode(value, p.datatype(), field);
                    s.addAll(r.violations());
                    if (r.violations().isEmpty())
                        globals.put(globalName(p.name()), r.value());
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
                setAttributes(ot, instance, in.get("attributes"), path + ".attributes", yearOutsideCaseForm, s);
                setCharacteristics(ot, instance, in.get("characteristics"), path + ".characteristics", s);
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
        } catch (IllegalArgumentException f) {
            // after the validation above the one defect of the case uchar's input step can find is a date or date-time
            // lexeme outside its form, a year beyond 0000-9999; it stops at the first it reads
            if (yearOutsideCaseForm.isEmpty()) throw f;
            s.add(Violation.of(Violation.Kind.WRONG_TYPE, yearOutsideCaseForm.get(0), f.getMessage()));
        }
        return new Result(input == null ? null : new World(input, globals, date), typePerLabel, s);
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
     *  to {@code yearOutsideCaseForm}. */
    private static JsonNode caseValue(Object w, Datatype dt, String field, List<String> yearOutsideCaseForm,
                                       List<Violation> s) {
        if (w == null) return NF.nullNode();
        ObjectNode n = NF.objectNode();
        // toString, not toPlainString: new BigDecimal(toString()) gives back the same unscaled value and scale
        if (w instanceof BigDecimal b) return n.put("number", b.toString());
        if (w instanceof LocalDate d) {
            if (!inCaseForm(d.getYear())) yearOutsideCaseForm.add(field);
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
            if (!inCaseForm(dt2.getYear())) yearOutsideCaseForm.add(field);
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
                               List<String> yearOutsideCaseForm, List<Violation> s) {
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
            if (!ot.dimensionsOf(canonical).isEmpty()) {
                s.add(Violation.of(Violation.Kind.UNSUPPORTED, field,
                        "dimensie-gewaardeerd attribuut '" + name + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            if (isTemporal(ot, canonical)) {
                s.add(Violation.of(Violation.Kind.UNSUPPORTED, field,
                        "tijdlijn-gewaardeerd attribuut '" + name + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            JsonNode value = value(e.getValue(), dt, field, s);
            if (value == null) return;
            ValueCoder.Result r = ValueCoder.decode(value, dt, field);
            s.addAll(r.violations());
            if (!r.violations().isEmpty()) return;
            JsonNode caseValue = caseValue(r.value(), dt, field, yearOutsideCaseForm, s);
            if (caseValue != null) objectIn(instance, "attributes").set(canonical, caseValue);
        });
    }

    private void setCharacteristics(ObjectType ot, ObjectNode instance, JsonNode characteristics, String path, List<Violation> s) {
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
            if (isTemporalCharacteristic(ot, canonical)) {
                s.add(Violation.of(Violation.Kind.UNSUPPORTED, field,
                        "tijdlijn-gewaardeerd kenmerk '" + name + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            JsonNode v = e.getValue();
            if (!v.isBoolean()) {
                s.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "kenmerk '" + name + "' moet een boolean zijn"));
                return;
            }
            // a case names the characteristics an instance has; one it does not name, it has not
            if (v.booleanValue()) listIn(instance, "characteristics").add(canonical);
        });
    }

    // ---- names and values read by the NameReader ----

    /** A name read for the mapping: {@code naam} to go on with — the written name where it reads canonically, the
     *  declaration's canonical name where only a designation reads it, null where nothing reads it (the caller
     *  refuses it with its own sentence) — or {@code geweigerd} when its violation has been added. */
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
            List<String> targets = new ArrayList<>();
            JsonNode v = e.getValue();
            if (v.isArray()) v.forEach(n -> targets.add(n.asText()));
            else targets.add(v.asText());
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
     *  one. Where neither direction is to-one the link goes on the first end, and the input step refuses it. */
    private void setRelations(JsonNode relations, Map<String, ObjectType> typePerLabel, Map<String, ObjectNode> instances,
                             Map<Link, String> paths, List<Violation> s) {
        if (relations == null || relations.isNull()) return;
        if (!relations.isArray()) { s.add(Violation.of(Violation.Kind.MALFORMED, "relations", "relations moet een array zijn")); return; }
        for (int i = 0; i < relations.size(); i++) {
            JsonNode rel = relations.get(i);
            String path = "relations[" + i + "]";
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
            rolesN = rolesRead(ft, rolesN, path + ".roles", s);
            if (rolesN == null) continue;
            // resolve each named role to its id. A binary feittype has two roles; a §13.3.9.2 WEDERKERIG feittype
            // relates an objecttype to itself through ONE role entry — the request then names that one role TWICE
            // (two ids under the same role name in a JSON array), or gives an array value.
            List<Role> roles = ft.roles;
            if (roles.size() != 2 && !(ft.reciprocal && roles.size() == 1)) {
                s.add(Violation.of(Violation.Kind.UNSUPPORTED, path,
                        "alleen binaire (of wederkerige) feittypen worden ondersteund door de dienst-kern (feittype '"
                                + ft.name + "')"));
                continue;
            }
            // collect the (roleName, id) endpoints: two entries even for a wederkerig 1-role feittype (its role
            // appears once but with an array of two ids, or is repeated).
            List<String[]> endPoints = endPoints(rolesN, roles, ft.reciprocal);
            if (endPoints.size() != 2) {
                s.add(Violation.of(Violation.Kind.MALFORMED, path + ".roles",
                        "relatie moet precies twee rol-eindpunten noemen (feittype '" + ft.name + "')"));
                continue;
            }
            String roleA = endPoints.get(0)[0], idA = endPoints.get(0)[1];
            String roleB = endPoints.get(1)[0], idB = endPoints.get(1)[1];
            ObjectType otA = typePerLabel.get(idA), otB = typePerLabel.get(idB);
            if (otA == null) { s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, path + ".roles." + roleA, "onbekend id '" + idA + "'")); continue; }
            if (otB == null) { s.add(Violation.of(Violation.Kind.UNKNOWN_FIELD, path + ".roles." + roleB, "onbekend id '" + idB + "'")); continue; }
            // the input step falls back on the type pair: an id that does not fill its role would be written into
            // another relation between the two types, as on an inline rol
            boolean fillsA = fillsRole(ft, roleA, idA, otA, path, s), fillsB = fillsRole(ft, roleB, idB, otB, path, s);
            if (!fillsA || !fillsB) continue;
            // the link goes where its field is: A holds B in a to-one field named after B's role, or else B holds A.
            // The role NAME disambiguates when the same objecttype pair is joined by more than one feittype.
            if (!model.toManyRole(otA, otB, roleB) || model.toManyRole(otB, otA, roleA))
                writeLink(instances, paths, idA, roleB, idB, path + ".roles." + roleB);
            else
                writeLink(instances, paths, idB, roleA, idA, path + ".roles." + roleA);
        }
    }

    /** The roles of a relation keyed by their declared names, which {@link #endPoints} looks them up by: each written
     *  name read as a role of {@code ft}. A name no role of {@code ft} answers to stays as written, so the endpoint
     *  count refuses the relation as before; null after adding the violation of an ambiguous or a repeated role. */
    private JsonNode rolesRead(FactType ft, JsonNode rolesN, String path, List<Violation> s) {
        com.fasterxml.jackson.databind.node.ObjectNode wasRead =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        Duplicate duplicate = new Duplicate("de relatie");
        boolean error = false;
        for (var it = rolesN.fields(); it.hasNext(); ) {
            var e = it.next();
            NameReader.Reading reading = reader.roleIn(ft, e.getKey());
            if (read(reading, e.getKey(), path + "." + e.getKey(), duplicate, s).refused()) { error = true; continue; }
            wasRead.set(reading instanceof NameReader.Found g ? g.declaration().name() : e.getKey(), e.getValue());
        }
        return error ? null : wasRead;
    }

    /** Whether {@code id} (of objecttype {@code type}) fills the role {@code role} of {@code ft}, its declared type or a
     *  subtype of it; if not, a {@link Violation.Kind#WRONG_TYPE} on that role's field. */
    private boolean fillsRole(FactType ft, String role, String id, ObjectType type, String path, List<Violation> s) {
        for (Role r : ft.roles) {
            if (!r.name().equals(role)) continue;
            ObjectType targetType = model.objectTypeByName(r.objectTypeName());
            if (targetType == null || type.isA(targetType)) return true;
            s.add(wrongType(path + ".roles." + role, role, targetType, id, type));
            return false;
        }
        return true;
    }

    /** Collect exactly the two (roleName, id) endpoints of a relation. For a normal binary feittype that is one
     *  id per role name; for a WEDERKERIG 1-role feittype the single role name carries an array of two ids (or
     *  is listed twice via a JSON array), both under the same role name. */
    private static List<String[]> endPoints(JsonNode rolesN, List<Role> roles, boolean reciprocal) {
        List<String[]> sink = new ArrayList<>();
        if (reciprocal && roles.size() == 1) {
            String role = roles.get(0).name();
            JsonNode v = rolesN.get(role);
            if (v == null) v = rolesN.elements().hasNext() ? rolesN.elements().next() : null;
            if (v != null && v.isArray())
                v.forEach(n -> sink.add(new String[]{role, n.asText()}));
            else if (v != null) sink.add(new String[]{role, v.asText()});
        } else {
            for (Role r : roles) {
                JsonNode v = rolesN.get(r.name());
                if (v != null && !v.isNull()) sink.add(new String[]{r.name(), v.asText()});
            }
        }
        return sink;
    }

    private FactType factType(String name) {
        FactType ft = model.factTypes.get(name);
        if (ft != null) return ft;
        for (FactType f : model.factTypes.values()) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Whether attribute {@code naam} on {@code ot} is a §13.3.6 tijdlijn (temporal, {@code List<Segment>}) —
     *  asked over the WHOLE chain (IR-SPEC ch. 2.2), not the own maps. An inherited timeline read as a scalar
     *  is not a missing binding but a wrong judgement: the mapper would accept the value and write it as a scalar of
     *  the case, which uchar's input step reads as the asOfDate-bucket scalar of a timeline the service does not
     *  carry, instead of a violation naming the field.
     *  The model's chain walk is case-INSENSITIVE, exactly as this scan was. */
    private static boolean isTemporal(ObjectType ot, String name) {
        return ot.granularityOf(name) != null || ot.timelineCountOf(name) != null;
    }

    /** The kenmerk analogue of {@link #isTemporal} — a §13.3.6.7 tijdlijn kenmerk (a {@code List<Segment>}
     *  field), which the service core does not support; chain-closed for the same reason. */
    private static boolean isTemporalCharacteristic(ObjectType ot, String name) {
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
