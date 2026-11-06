package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Unit;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.CaseSession;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConstructorValue;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint.DerivedCharacteristic;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint.DerivedValue;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint.MadeLink;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint.MadeObject;
import dev.uchar.regelspraak.transpiler.tms.Fault;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What stands at the fixpoint of a session ({@link Fixpoint}, as uchar's entry reads it with {@code DroolsBackend.fixpointOf})
 * as JSON: the playground gives it per scenario and the decision service per request ({@code ?fixpoint=true}), so both
 * render it here. The input of the session is a list of {@link InputObject}s: the playground makes them of a
 * scenario's situation, the service of the instances of a request ({@link #input}). An object a creation made has no
 * input: in its place stands the value of the making, what the creation put on its bean (rank 2 of uchar's stack,
 * RUNTIME-SEMANTICS ch. 7.8), read before the write-back ({@code CaseSession.making}), and the fixpoint gives it in the
 * form of an input value with the origin {@code creation}.
 *
 * <p><b>Names</b> are those of the chosen {@link Naming}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (plain decimal, with its scale), a date, a
 * date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth value and null for
 * leeg; a timeline is {@code timeline}, a list of {@code {from, to, value}}, an open bound left out. A dimensioned
 * attribute has a row per cell, with the cell's {@code labels} after the attribute, one per dimension in their declared
 * spelling, as uchar's
 * {@link DerivedValue#labels} names a derived cell. {@code unit} is the declared unit of a number.
 *
 * <p><b>Order.</b> uchar's {@link Fixpoint} lists have no order, so every list here is sorted: the objects of the input in
 * the order of the input, the made objects after them; the members of an object in the declaration order of its type,
 * the nearest type of its chain first, the cells of an attribute with the first dimension outermost, each in the declared
 * order of its labels; the made objects by type, creation, subject and values; links, faults and
 * inconsistencies by their names and the objects they name. An object of the input goes by its label, a made object by
 * {@code #<n>}, its place in that order. Two made objects alike in type, creation, subject and every value change places
 * without changing anything that is shown of them, apart from the links they take part in.
 *
 * <p>No class name of a bean enters the JSON: an object goes by its label and its type by its name. uchar's
 * {@link MadeObject} names no type, so the type of a made object is found here as the type whose generated bean
 * class it is.
 */
public final class FixpointGivens {

    private FixpointGivens() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** A value of the fixpoint in a form no bean of uchar's generator has: the fixpoint is not shown, with this
     *  sentence, rather than shown with the value guessed. */
    public static final class UnrenderableValue extends RuntimeException {
        UnrenderableValue(String sentence) { super(sentence); }
    }

    /**
     * An object of the input of a session: its label, its object type, its bean in the session, and what the input gave
     * it. {@code attributes} holds per attribute the input value as a node with {@code value} (and
     * {@code valueCanonical} for an enumeration value named otherwise), {@code timeline}, or for a dimensioned attribute
     * {@code cells}, a list of nodes with {@code labels} (as uchar keys a cell) and {@code value}, keyed
     * case-insensitively; {@code characteristics} the characteristics the input set, case-insensitively.
     */
    public record InputObject(String label, ObjectType type, Object bean, Map<String, ObjectNode> attributes,
                               Set<String> characteristics) {}

    /**
     * The input objects of a request, read off its beans before the session fires: per instance in the order of
     * {@code beans}, the attributes that hold a value and the characteristics that hold. The beans then hold the input
     * alone; the write-back after the fire puts the derived values on the same beans. A to-many or dimensioned member
     * the request did not fill (an empty list or map) is no input value; of a dimensioned member the filled cells are,
     * in the order of {@code model}'s labels.
     */
    public static List<InputObject> input(Map<String, Object> beans, Map<String, ObjectType> typePerLabel,
                                            RegelSpraakModel model, Naming ng) {
        List<InputObject> sink = new ArrayList<>();
        for (var e : beans.entrySet()) {
            ObjectType ot = typePerLabel.get(e.getKey());
            if (ot == null)
                throw new IllegalStateException("het verzoek heeft een object '" + e.getKey() + "' zonder objecttype");
            Object bean = e.getValue();
            Map<String, ObjectNode> attributes = attributes(ot, a -> RegelSpraakRuntime.get(bean, RequestMapper.prop(a)),
                    model, ng);
            Set<String> characteristics = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (ObjectType t : ot.chain())
                for (String k : t.characteristics)
                    if (Boolean.TRUE.equals(RegelSpraakRuntime.get(bean, RequestMapper.prop(k)))) characteristics.add(k);
            sink.add(new InputObject(e.getKey(), ot, bean, attributes, characteristics));
        }
        return sink;
    }

    /** The attribute values of an object of type {@code ot} as input nodes, keyed case-insensitively: per attribute of
     *  the type and its supertypes that {@code value} gives a value, a {@code value} (with {@code valueCanonical} for an
     *  enumeration value named otherwise) or a {@code timeline}, or for a dimensioned attribute its filled
     *  {@code cells}, in the order of {@code model}'s labels. Null, an empty list and an empty map are no value. */
    private static Map<String, ObjectNode> attributes(ObjectType ot, Function<String, Object> value,
                                                       RegelSpraakModel model, Naming ng) {
        Map<String, ObjectNode> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ObjectType t : ot.chain())
            for (String a : t.attributes.keySet()) {
                Object v = value.apply(a);
                if (v == null || v instanceof List<?> l && l.isEmpty() || v instanceof Map<?, ?> m && m.isEmpty())
                    continue;
                ObjectNode n = NF.objectNode();
                if (!ot.dimensionsOf(a).isEmpty()) {
                    ArrayNode cells = n.putArray("cells");
                    for (List<String> labels : AnswerBuilder.cellsOf(model, ot.dimensionsOf(a))) {
                        Object w = cellsOf(v, a).get(labels);
                        if (w == null) continue;
                        ObjectNode cell = cells.addObject();
                        labels(cell, labels);
                        java(cell, w, ot.attribute(a), ng);
                    }
                } else {
                    java(n, v, ot.attribute(a), ng);
                }
                attributes.put(a, n);
            }
        return attributes;
    }

    /**
     * What stands at fixpoint {@code f} of a session with input {@code input}: {@code objects}, every object of the
     * input and every made object, each with its attribute values and characteristics and their {@code origin} (an
     * input value: {@code input}; on a made object the value of the making, what its creation put on its bean, read
     * before the write-back and given in {@code making} by the identity of the object: {@code creation}; one a rule, a
     * default or an allocation derived: {@code derived}, with the input value or the value of the making it replaced
     * beside it as {@code input}, and on a made object {@code inputOrigin} {@code creation});
     * {@code madeObjects} with their creation and subject; the {@code links} the creations laid; the
     * evaluation {@code errors} with their kind and uchar's sentence; and the {@code inconsistencies}.
     *
     * @throws UnrenderableValue a value in a form this view does not show
     */
    public static ObjectNode fixpoint(Fixpoint f, Map<Object, Map<String, Object>> making, List<InputObject> input,
                                      RegelSpraakModel model, Naming ng) {
        Map<Object, String> ref = new IdentityHashMap<>();
        Map<Object, ObjectType> type = new IdentityHashMap<>();
        Map<Object, InputObject> entered = new IdentityHashMap<>();
        List<Object> inputObjects = new ArrayList<>();
        for (InputObject i : input) {
            if (ref.put(i.bean(), i.label()) != null)
                throw new IllegalStateException("het object '" + i.label() + "' staat twee keer in de invoer");
            type.put(i.bean(), i.type());
            entered.put(i.bean(), i);
            inputObjects.add(i.bean());
        }

        Map<String, ObjectType> typePerClass = new HashMap<>();
        for (ObjectType ot : model.objectTypes.values())
            typePerClass.put(FactModelGenerator.PACKAGE + "." + Namen.ident(ot.name, true), ot);
        Map<Object, MadeObject> made = new IdentityHashMap<>();
        for (MadeObject g : f.objects()) {
            ObjectType ot = typePerClass.get(g.object().getClass().getName());
            if (ot == null)
                throw new IllegalStateException("het gemaakte object " + g.object().getClass().getName()
                        + " is van geen objecttype van het model");
            type.put(g.object(), ot);
            made.put(g.object(), g);
        }

        // per object and attribute the derived values by the labels of their cell, an attribute without dimensions under
        // no labels
        Map<Object, Map<String, Map<List<String>, DerivedValue>>> values = new IdentityHashMap<>();
        for (DerivedValue w : f.values())
            if (values.computeIfAbsent(known(w.object(), type), o -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                    .computeIfAbsent(w.attribute(), a -> new HashMap<>())
                    .put(w.labels() == null ? List.of() : w.labels(), w) != null)
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + w.attribute() + "'"
                        + (w.labels() == null ? "" : " in de cel " + w.labels()) + " twee keer");
        Map<Object, Set<String>> characteristics = new IdentityHashMap<>();
        for (DerivedCharacteristic k : f.characteristics())
            characteristics.computeIfAbsent(known(k.object(), type),
                    o -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(k.characteristic());

        // the members of every object first: a made object is ordered by its values too
        Map<Object, ArrayNode[]> members = new IdentityHashMap<>();
        for (Object o : type.keySet()) {
            InputObject in = entered.get(o);
            Map<String, ObjectNode> given = in != null ? in.attributes() : attributes(type.get(o), madeValues(o, making),
                    model, ng);
            members.put(o, new ArrayNode[]{
                    valuesOf(type.get(o), given, values.getOrDefault(o, Map.of()), made.containsKey(o), model, ng),
                    characteristicsOf(type.get(o), in == null ? Set.of() : in.characteristics(),
                            characteristics.getOrDefault(o, Set.of()), ng)});
        }
        List<Object> madeObjects = new ArrayList<>(made.keySet());
        Map<Object, String> key = new IdentityHashMap<>();
        for (Object o : madeObjects) key(o, ref, made, type, members, key, new ArrayList<>());
        madeObjects.sort(Comparator.comparing(key::get));
        for (int i = 0; i < madeObjects.size(); i++) ref.put(madeObjects.get(i), "#" + (i + 1));

        ObjectNode n = NF.objectNode();
        ArrayNode objects = n.putArray("objects");
        List<Object> all = new ArrayList<>(inputObjects);
        all.addAll(madeObjects);
        for (Object o : all) {
            ObjectNode on = objects.addObject();
            on.put("object", ref.get(o));
            name(on, "objectType", ng.objectType(type.get(o).name), type.get(o).name);
            on.set("values", members.get(o)[0]);
            on.set("characteristics", members.get(o)[1]);
        }
        ArrayNode madeNode = n.putArray("madeObjects");
        for (Object o : madeObjects) {
            MadeObject g = made.get(o);
            ObjectNode gn = madeNode.addObject();
            gn.put("object", ref.get(o));
            name(gn, "objectType", ng.objectType(type.get(o).name), type.get(o).name);
            name(gn, "creation", ng.form(g.creation()), g.creation());
            gn.put("subject", refOf(g.subject(), ref));
        }
        List<MadeLink> links = new ArrayList<>(f.links());
        links.sort(Comparator.comparing(MadeLink::factType).thenComparing(MadeLink::firstRole)
                .thenComparing(k -> refOf(k.first(), ref)).thenComparing(MadeLink::secondRole)
                .thenComparing(k -> refOf(k.second(), ref)));
        ArrayNode linkNode = n.putArray("links");
        for (MadeLink k : links) {
            ObjectNode kn = linkNode.addObject();
            name(kn, "factType", ng.factType(k.factType()), k.factType());
            name(kn, "firstRole", ng.role(k.factType(), k.firstRole()), k.firstRole());
            kn.put("first", refOf(k.first(), ref));
            name(kn, "secondRole", ng.role(k.factType(), k.secondRole()), k.secondRole());
            kn.put("second", refOf(k.second(), ref));
        }
        List<Fault> errors = new ArrayList<>(f.faults());
        errors.sort(Comparator.comparing(Fault::rule).thenComparing(Fault::message)
                .thenComparing(x -> refOf(x.subject(), ref)));
        ArrayNode faultNode = n.putArray("faults");
        for (Fault x : errors) {
            ObjectNode fn = faultNode.addObject();
            name(fn, "rule", ng.form(x.rule()), x.rule());
            fn.put("subject", refOf(x.subject(), ref));
            fn.put("kind", EvaluationFault.kind(x.fault()).name());
            fn.put("sentence", x.message());
        }
        List<String[]> inconsistencies = new ArrayList<>();
        for (Object o : f.inconsistencies()) {
            Object line = CaseSession.read(o, "getRule");
            Object subject = CaseSession.read(o, "getSubject");
            inconsistencies.add(new String[]{line == null ? null : line.toString(),
                    subject == null ? null : refOf(subject, ref)});
        }
        Comparator<String> emptyFirst = Comparator.nullsFirst(Comparator.naturalOrder());
        inconsistencies.sort(Comparator.<String[], String>comparing(x -> x[0], emptyFirst)
                .thenComparing(x -> x[1], emptyFirst));
        ArrayNode incNode = n.putArray("inconsistencies");
        for (String[] x : inconsistencies) {
            ObjectNode in = incNode.addObject();
            if (x[0] == null) in.putNull("rule"); else name(in, "rule", ng.form(x[0]), x[0]);
            if (x[1] == null) in.putNull("subject"); else in.put("subject", x[1]);
        }
        return n;
    }

    /** The attribute values of an object of type {@code ot} at the fixpoint, in declaration order: a derived value
     *  from the fixpoint, with what {@code entered} gives the member beside it as {@code input}, else what
     *  {@code entered} gives it: the input of an object of the input, the value of the making of a made object
     *  ({@code made}), which has {@code creation} as its origin and as the {@code inputOrigin} beside a derived value.
     *  A member with none of these is no row. A dimensioned attribute has a row per cell, by the same rule, in the order
     *  of {@code model}'s labels ({@link #cellRows}). */
    private static ArrayNode valuesOf(ObjectType ot, Map<String, ObjectNode> entered,
                                        Map<String, Map<List<String>, DerivedValue>> derived, boolean made,
                                        RegelSpraakModel model, Naming ng) {
        List<String> declared = new ArrayList<>();
        for (ObjectType t : ot.chain()) declared.addAll(t.attributes.keySet());
        for (String a : derived.keySet())
            if (declared.stream().noneMatch(a::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + a + "', dat het objecttype '"
                        + ot.name + "' niet declareert");
        ArrayNode sink = NF.arrayNode();
        for (String a : declared) {
            Datatype dt = ot.attribute(a);
            Map<List<String>, DerivedValue> cells = derived.getOrDefault(a, Map.of());
            if (!ot.dimensionsOf(a).isEmpty()) {
                cellRows(sink, ot, a, entered.get(a), cells, made, model, ng);
                continue;
            }
            if (cells.keySet().stream().anyMatch(labels -> !labels.isEmpty()))
                throw new IllegalStateException("het fixpunt geeft een cel van het attribuut '" + a + "', dat geen"
                        + " dimensies heeft");
            DerivedValue w = cells.get(List.of());
            ObjectNode in = entered.get(a);
            if (w == null && in == null) continue;
            ObjectNode rn = sink.addObject();
            name(rn, "attribute", ng.attribute(ot, a), a);
            if (w != null) {
                java(rn, w.value(), dt, ng);
                rn.put("origin", "derived");
                if (in != null) beside(rn, in.has("timeline") ? in.get("timeline") : in.get("value"), made);
            } else {
                rn.setAll(in);
                rn.put("origin", made ? "creation" : "input");
            }
            unit(rn, dt);
        }
        return sink;
    }

    /** The rows of the dimensioned attribute {@code a}: per cell of the attribute, the first dimension outermost, its
     *  derived value ({@code derived}, by its labels), with the value {@code in} gives the cell beside it, else that
     *  value ({@code in}, the input node with its {@code cells}: the input, or on a made object the value of the
     *  making); a cell with none of these is no row. A derived value of a cell the attribute does not have is a broken
     *  promise of uchar's. */
    private static void cellRows(ArrayNode sink, ObjectType ot, String a, ObjectNode in,
                                  Map<List<String>, DerivedValue> derived, boolean made, RegelSpraakModel model,
                                  Naming ng) {
        Datatype dt = ot.attribute(a);
        List<List<String>> all = AnswerBuilder.cellsOf(model, ot.dimensionsOf(a));
        for (List<String> labels : derived.keySet())
            if (!all.contains(labels))
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + a + "' in de cel " + labels
                        + ", die het attribuut niet heeft");
        Map<List<String>, ObjectNode> entered = new HashMap<>();
        if (in != null)
            for (JsonNode cell : in.get("cells")) {
                List<String> labels = new ArrayList<>();
                cell.get("labels").forEach(l -> labels.add(l.asText()));
                entered.put(List.copyOf(labels), (ObjectNode) cell);
            }
        for (List<String> labels : all) {
            DerivedValue w = derived.get(labels);
            ObjectNode cellIn = entered.get(labels);
            if (w == null && cellIn == null) continue;
            ObjectNode rn = sink.addObject();
            name(rn, "attribute", ng.attribute(ot, a), a);
            labels(rn, labels);
            if (w != null) {
                java(rn, w.value(), dt, ng);
                rn.put("origin", "derived");
                if (cellIn != null) beside(rn, cellIn.get("value"), made);
            } else {
                cellIn.properties().forEach(e -> { if (!e.getKey().equals("labels")) rn.set(e.getKey(), e.getValue()); });
                rn.put("origin", made ? "creation" : "input");
            }
            unit(rn, dt);
        }
    }

    /** Beside a derived value in row {@code rn}, the value it replaced, {@code given}, as {@code input}: on a made
     *  object ({@code made}) the value of the making, which {@code inputOrigin} names. */
    private static void beside(ObjectNode rn, JsonNode given, boolean made) {
        rn.set("input", given);
        if (made) rn.put("inputOrigin", "creation");
    }

    /** The value of the making of made object {@code o} per attribute ({@code making}); every made object has an entry,
     *  empty where its creation gave nothing. */
    private static Function<String, Object> madeValues(Object o, Map<Object, Map<String, Object>> making) {
        Map<String, Object> values = making.get(o);
        if (values == null)
            throw new IllegalStateException("het gemaakte object " + o.getClass().getName() + " heeft geen bij de"
                    + " objectcreatie toegekende waarden van vóór de terugzetting");
        return values::get;
    }

    /** The map of cells a bean holds for the dimensioned attribute {@code a}: uchar's map of the filled cells by their
     *  key, empty where the bean has none. */
    private static Map<?, ?> cellsOf(Object member, String a) {
        if (member == null) return Map.of();
        if (!(member instanceof Map<?, ?> m))
            throw new IllegalStateException("het gedimensioneerde attribuut '" + a + "' staat op de bean als "
                    + member.getClass().getName() + ", niet als uchars kaart van cellen");
        return m;
    }

    /** The labels of a cell under {@code labels}. */
    private static void labels(ObjectNode n, List<String> labels) {
        ArrayNode ln = n.putArray("labels");
        labels.forEach(ln::add);
    }

    /** The characteristics of an object of type {@code ot} that hold at the fixpoint, in declaration order: derived
     *  ({@code derived}, with {@code invoer: true} when the input set it too) or set by the input ({@code input}). */
    private static ArrayNode characteristicsOf(ObjectType ot, Set<String> entered, Set<String> derived, Naming ng) {
        List<String> declared = new ArrayList<>();
        for (ObjectType t : ot.chain()) declared.addAll(t.characteristics);
        for (String k : derived)
            if (declared.stream().noneMatch(k::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het kenmerk '" + k + "', dat het objecttype '"
                        + ot.name + "' niet declareert");
        ArrayNode sink = NF.arrayNode();
        for (String k : declared) {
            boolean a = derived.contains(k), i = entered.contains(k);
            if (!a && !i) continue;
            ObjectNode kn = sink.addObject();
            name(kn, "characteristic", ng.characteristic(ot, k), k);
            kn.put("origin", a ? "derived" : "input");
            if (a && i) kn.put("input", true);
        }
        return sink;
    }

    /** The sort key of made object {@code o}: its type, its creation, the key of its subject (a label, or the key of
     *  the made object that is its subject) and its values. */
    private static String key(Object o, Map<Object, String> ref, Map<Object, MadeObject> made,
                                  Map<Object, ObjectType> type, Map<Object, ArrayNode[]> members,
                                  Map<Object, String> key, List<Object> path) {
        String label = ref.get(o);
        if (label != null) return label;
        String known = key.get(o);
        if (known != null) return known;
        if (path.stream().anyMatch(p -> p == o))
            throw new IllegalStateException("de gemaakte objecten zijn elkaars onderwerp in een kring");
        path.add(o);
        MadeObject g = made.get(o);
        String subject = made.containsKey(g.subject()) || ref.containsKey(g.subject())
                ? key(g.subject(), ref, made, type, members, key, path)
                : refOf(g.subject(), ref);
        String k = String.join("\u0000", type.get(o).name, g.creation(), subject, members.get(o)[0].toString(),
                members.get(o)[1].toString());
        key.put(o, k);
        path.remove(path.size() - 1);
        return k;
    }

    /** {@code o} itself when it is an object of the input or a made object, which every object the fixpoint names
     *  is. */
    private static Object known(Object o, Map<Object, ObjectType> type) {
        if (!type.containsKey(o))
            throw new IllegalStateException("het fixpunt noemt een object dat geen invoer en geen gemaakt object is: "
                    + o.getClass().getName());
        return o;
    }

    private static String refOf(Object o, Map<Object, String> ref) {
        String r = ref.get(o);
        if (r == null)
            throw new IllegalStateException("het fixpunt noemt een object dat geen invoer en geen gemaakt object is: "
                    + o.getClass().getName());
        return r;
    }

    /** Puts {@code gekozen} under {@code key}, and {@code canonical} beside it when it differs. */
    public static void name(ObjectNode n, String key, String chosen, String canonical) {
        n.put(key, chosen);
        if (!chosen.equals(canonical)) n.put(key + "Canonical", canonical);
    }

    /** A value in the form its bean field takes it: under {@code value}, a timeline under {@code timeline}, a segment
     *  without {@code from} or {@code to} where its bound is open (null on the bean, left out in uchar's case). The
     *  value of a cell is one of these; a dimensioned attribute has a row per cell ({@link #cellRows}). */
    private static void java(ObjectNode n, Object v, Datatype dt, Naming ng) {
        if (v instanceof List<?> segments) {
            ArrayNode tl = n.putArray("timeline");
            for (Object seg : segments) {
                ObjectNode sn = tl.addObject();
                Object from = CaseSession.read(seg, "getVan"), to = CaseSession.read(seg, "getTot");
                if (from != null) sn.set("from", scalar(from, null));
                if (to != null) sn.set("to", scalar(to, null));
                sn.set("value", scalar(CaseSession.read(seg, "getWaarde"), dt));
            }
        } else if (v instanceof String s && dt instanceof Datatype.Enumeration) {
            enumValue(n, s, dt, ng);
        } else {
            n.set("value", scalar(v, dt));
        }
    }

    /** A scalar value as the fixpoint shows it; uchar's constructor value of an attribute with absence markers in the
     *  form of the answer ({@link Markers}): {@code waarde(…)} as its payload, a marker as {@code {"marker": <naam>}}. */
    private static JsonNode scalar(Object v, Datatype dt) {
        if (v == null) return NF.nullNode();
        if (v instanceof ConstructorValue c)
            return c.payload() != null ? scalar(c.payload(), dt) : NF.objectNode().put(Markers.KEY, c.constructor());
        if (v instanceof BigDecimal b) return NF.textNode(b.toPlainString());
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof String s) return NF.textNode(s);
        throw new UnrenderableValue("een waarde van de vorm " + v.getClass().getName() + " ("
                + (dt == null ? "zonder datatype" : dt.name()) + ") toont het fixpunt niet");
    }

    /** An enumeration value under {@code waarde}, in the chosen vocabulary, with its canonical spelling beside it. */
    public static void enumValue(ObjectNode n, String value, Datatype dt, Naming ng) {
        if (dt instanceof Datatype.Enumeration e) name(n, "value", ng.value(e, value), value);
        else n.put("value", value);
    }

    /** The declared unit of a number under {@code unit}, when it has one. */
    public static void unit(ObjectNode n, Datatype dt) {
        Unit e = dt == null ? null : dt.unitOf();
        if (e != null) n.put("unit", e.rendering());
    }
}
