package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Unit;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.toolchain.engine.CaseSession;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.tms.ComputeHelpers;
import dev.uchar.regelspraak.transpiler.tms.ConstructorValue;
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
 * What stands at the fixpoint of a session as JSON, read off uchar's record of what the session holds there
 * ({@link EndState}, through {@code DroolsBackend.standingOf}, also where an evaluation fault stands): the playground
 * gives it per scenario and the decision service per request ({@code ?fixpoint=true}), so both render it here. That
 * record gives every object with its type and the creation that made it, per member its value, whether a rule derived
 * it and what its bean field holds without the write-back ({@link EndState.Member#given}), the links, the faults and
 * the §9.5 facts. The input of the session is a list
 * of {@link InputObject}s: the playground makes them of a scenario's situation, the service of what the session holds
 * of the objects of a request ({@link #inputOf}). An object a creation made has no input: in its place stands the value
 * the creation assigned, what the creation put on its bean (rank 2 of uchar's stack, RUNTIME-SEMANTICS ch. 7.8), its
 * {@code given} in uchar's reading, and the fixpoint gives it in the form of an input value with the origin
 * {@code creation}.
 *
 * <p><b>Names</b> are those of the chosen {@link Naming}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (plain decimal, with its scale), a date, a
 * date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth value and null for
 * leeg; a timeline is {@code timeline}, a list of {@code {from, to, value}}, an open bound left out. A dimensioned
 * attribute has a row per cell, with the cell's {@code labels} after the attribute, one per dimension in their declared
 * spelling, as uchar's {@link EndState.Member#cell} names a cell. {@code unit} is the declared unit of a number.
 *
 * <p><b>Order.</b> Every list here is sorted: the objects of the input in
 * the order of the input, the made objects after them; the members of an object in the declaration order of its type,
 * the nearest type of its chain first, the cells of an attribute with the first dimension outermost, each in the declared
 * order of its labels; the made objects by type, creation, subject and values; links, faults and
 * inconsistencies by their names and the objects they name. An object of the input goes by its label, a made object by
 * {@code #<n>}, its place in that order. Two made objects alike in type, creation, subject and every value change places
 * without changing anything that is shown of them, apart from the links they take part in.
 *
 * <p>No class name of a bean enters the JSON: an object goes by its label and its type by its name, the one uchar's
 * reading gives it ({@link EndState.EndObject#type}).
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
     * An object of the input of a session: its label and what the input gave it. {@code attributes} holds per attribute
     * the input value as a node with {@code value} (and {@code valueCanonical} for an enumeration value named
     * otherwise), {@code timeline}, or for a dimensioned attribute {@code cells}, a list of nodes with {@code labels}
     * (as uchar keys a cell) and {@code value}, keyed case-insensitively; {@code characteristics} the characteristics
     * the input set, case-insensitively, and {@code periods} per time-dependent characteristic the input gave periods
     * those periods, as {@code from}, {@code to} nodes ({@link #periodNodes}), keyed case-insensitively. Its object in
     * the session is the one uchar's reading labels so ({@link EndState.EndObject#label}).
     */
    public record InputObject(String label, Map<String, ObjectNode> attributes, Set<String> characteristics,
                               Map<String, ArrayNode> periods) {}

    /**
     * The input objects of a request, in the order of {@code labels}, as uchar's reading {@code standing} gives what
     * their bean fields hold without the write-back ({@link EndState.Member#given}): the attributes that hold a value
     * and the characteristics that hold. Of a dimensioned member the filled cells are, in the order of {@code model}'s
     * labels.
     */
    public static List<InputObject> inputOf(EndState standing, List<String> labels, RegelSpraakModel model, Naming ng) {
        Map<String, EndState.EndObject> labelled = new HashMap<>();
        for (EndState.EndObject o : standing.objects()) if (o.label() != null) labelled.put(o.label(), o);
        List<InputObject> sink = new ArrayList<>();
        for (String label : labels) {
            EndState.EndObject o = labelled.get(label);
            if (o == null)
                throw new IllegalStateException("uchars lezing van de sessie heeft geen object '" + label + "'");
            Set<String> characteristics = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            Map<String, ArrayNode> periods = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (EndState.Member m : o.members()) {
                if (!m.characteristic()) continue;
                if (Boolean.TRUE.equals(m.given())) characteristics.add(m.name());
                // a time-dependent characteristic holds its true periods on the bean, as uchar's segments
                else if (m.given() instanceof List<?> segments) periods.put(m.name(), periodNodes(segments));
            }
            sink.add(new InputObject(label, attributes(typeOf(o, model), given(o), model, ng), characteristics,
                    periods));
        }
        return sink;
    }

    /** What the bean fields of {@code o} hold without the write-back per attribute, keyed case-insensitively
     *  ({@link EndState.Member#given}): the value, or of a dimensioned attribute its filled cells by their labels. */
    private static Function<String, Object> given(EndState.EndObject o) {
        Map<String, Object> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (EndState.Member m : o.members()) {
            if (m.characteristic() || m.given() == null) continue;
            if (m.cell() == null) values.put(m.name(), m.given());
            else {
                @SuppressWarnings("unchecked")
                Map<List<String>, Object> cells = (Map<List<String>, Object>) values.computeIfAbsent(m.name(),
                        a -> new HashMap<List<String>, Object>());
                cells.put(m.cell(), m.given());
            }
        }
        return values::get;
    }

    /** The object type of {@code o} in {@code model}, by the name uchar's reading gives it. */
    private static ObjectType typeOf(EndState.EndObject o, RegelSpraakModel model) {
        ObjectType ot = model.objectTypeByName(o.type());
        if (ot == null)
            throw new IllegalStateException("uchars lezing van de sessie geeft een object van het type '" + o.type()
                    + "', dat het model niet declareert");
        return ot;
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
     * What stands at the fixpoint of a session with input {@code input}, as uchar reads the session there in
     * {@code standing} ({@code DroolsBackend.standingOf}): {@code objects}, every object of the
     * input and every made object, each with its attribute values and characteristics and their {@code origin} (an
     * input value: {@code input}; on a made object the value the creation assigned, what its bean field holds without
     * the write-back in {@code standing}: {@code creation}; one a rule, a default or an allocation derived:
     * {@code derived}, with the input value or the value the creation assigned, which it replaced beside it as
     * {@code input}, and on a made object {@code inputOrigin} {@code creation}); {@code madeObjects} with their
     * creation and subject; the {@code links} the creations laid; the evaluation {@code faults} with the code and
     * parameters of their kind and uchar's sentence; and the {@code inconsistencies}, one per rule and subject
     * (RUNTIME-SEMANTICS ch. 5.6).
     *
     * @throws UnrenderableValue a value in a form this view does not show
     */
    public static ObjectNode fixpoint(EndState standing, List<InputObject> input, RegelSpraakModel model, Naming ng) {
        // every object of the session, those of the input by their label and the made ones, with its type
        Map<Object, ObjectType> type = new IdentityHashMap<>();
        Map<Object, EndState.EndObject> held = new IdentityHashMap<>();
        Map<String, Object> labelled = new HashMap<>();
        for (EndState.EndObject o : standing.objects()) {
            type.put(o.object(), typeOf(o, model));
            held.put(o.object(), o);
            if (o.label() != null) labelled.put(o.label(), o.object());
        }
        Map<Object, String> ref = new IdentityHashMap<>();
        Map<Object, InputObject> entered = new IdentityHashMap<>();
        List<Object> inputObjects = new ArrayList<>();
        for (InputObject i : input) {
            Object bean = labelled.get(i.label());
            if (bean == null)
                throw new IllegalStateException("het object '" + i.label() + "' van de invoer staat niet in uchars"
                        + " lezing van de sessie");
            if (ref.put(bean, i.label()) != null)
                throw new IllegalStateException("het object '" + i.label() + "' staat twee keer in de invoer");
            entered.put(bean, i);
            inputObjects.add(bean);
        }
        if (labelled.size() != inputObjects.size())
            throw new IllegalStateException("uchars lezing van de sessie heeft de objecten " + labelled.keySet()
                    + ", de invoer " + inputObjects.size());

        // every object without a label is one a creation made (uchar's reading names no other)
        Map<Object, EndState.EndObject> made = new IdentityHashMap<>();
        for (EndState.EndObject o : standing.objects()) {
            if (o.label() != null) continue;
            if (o.creation() == null)
                throw new IllegalStateException("uchars lezing van de sessie geeft een object van " + o.type()
                        + " zonder label en zonder creatie");
            made.put(o.object(), o);
        }

        // per object and attribute the derived values by the labels of their cell, an attribute without dimensions under
        // no labels, each in the decimal form a bean holds (ComputeHelpers.collapse)
        Map<Object, Map<String, Map<List<String>, Object>>> values = new IdentityHashMap<>();
        // a time-dependent characteristic a rule writes within a window: its true periods as segments without a value
        Map<Object, Map<String, ArrayNode>> periods = new IdentityHashMap<>();
        Map<Object, Set<String>> characteristics = new IdentityHashMap<>();
        for (EndState.EndObject h : standing.objects())
            for (EndState.Member m : h.members()) {
                if (!m.derived()) continue;
                Object o = h.object();
                if (m.characteristic() && m.value() instanceof List<?> segments) {
                    if (periods.computeIfAbsent(o, x -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                            .put(m.name(), periodNodes(segments)) != null)
                        throw new IllegalStateException("uchars lezing geeft het kenmerk '" + m.name() + "' twee keer");
                } else if (m.characteristic()) {
                    characteristics.computeIfAbsent(o, x -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(m.name());
                } else if (values.computeIfAbsent(o, x -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                        .computeIfAbsent(m.name(), a -> new HashMap<>())
                        .put(m.cell() == null ? List.of() : m.cell(), ComputeHelpers.collapse(m.value())) != null)
                    throw new IllegalStateException("uchars lezing geeft het attribuut '" + m.name() + "'"
                            + (m.cell() == null ? "" : " in de cel " + m.cell()) + " twee keer");
            }

        // the members of every object first: a made object is ordered by its values too
        Map<Object, ArrayNode[]> members = new IdentityHashMap<>();
        for (Object o : type.keySet()) {
            InputObject in = entered.get(o);
            Map<String, ObjectNode> given = in != null ? in.attributes()
                    : attributes(type.get(o), given(held.get(o)), model, ng);
            members.put(o, new ArrayNode[]{
                    valuesOf(type.get(o), given, values.getOrDefault(o, Map.of()), made.containsKey(o), model, ng),
                    characteristicsOf(type.get(o), in == null ? Set.of() : in.characteristics(),
                            in == null ? Map.of() : in.periods(), characteristics.getOrDefault(o, Set.of()),
                            periods.getOrDefault(o, Map.of()), ng)});
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
            EndState.EndObject g = made.get(o);
            ObjectNode gn = madeNode.addObject();
            gn.put("object", ref.get(o));
            name(gn, "objectType", ng.objectType(type.get(o).name), type.get(o).name);
            name(gn, "creation", ng.form(g.creation()), g.creation());
            gn.put("subject", refOf(g.subject(), ref));
        }
        List<EndState.Link> links = new ArrayList<>();
        for (EndState.Link k : standing.links()) if (k.made()) links.add(k);
        links.sort(Comparator.comparing(EndState.Link::factType).thenComparing(EndState.Link::firstRole)
                .thenComparing(k -> refOf(k.first(), ref)).thenComparing(EndState.Link::secondRole)
                .thenComparing(k -> refOf(k.second(), ref)));
        ArrayNode linkNode = n.putArray("links");
        for (EndState.Link k : links) {
            ObjectNode kn = linkNode.addObject();
            name(kn, "factType", ng.factType(k.factType()), k.factType());
            name(kn, "firstRole", ng.role(k.factType(), k.firstRole()), k.firstRole());
            kn.put("first", refOf(k.first(), ref));
            name(kn, "secondRole", ng.role(k.factType(), k.secondRole()), k.secondRole());
            kn.put("second", refOf(k.second(), ref));
        }
        List<Fault> errors = new ArrayList<>(standing.faults());
        errors.sort(Comparator.comparing(Fault::rule).thenComparing(Fault::message)
                .thenComparing(x -> refOf(x.subject(), ref)));
        ArrayNode faultNode = n.putArray("faults");
        for (Fault x : errors) {
            ObjectNode fn = faultNode.addObject();
            name(fn, "rule", ng.form(x.rule()), x.rule());
            fn.put("subject", refOf(x.subject(), ref));
            AnswerBuilder.code(fn, EvaluationFault.Fault.of(x.fault(), x.message()));
            fn.put("sentence", x.message());
        }
        List<String[]> inconsistencies = new ArrayList<>();
        for (EndState.ChannelFact c : standing.channel())
            inconsistencies.add(new String[]{c.rule(), c.subject() == null ? null : refOf(c.subject(), ref)});
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
     *  ({@code derived}), with what {@code entered} gives the member beside it as {@code input}, else what
     *  {@code entered} gives it: the input of an object of the input, the value the creation assigned to a made object
     *  ({@code made}), which has {@code creation} as its origin and as the {@code inputOrigin} beside a derived value.
     *  A member with none of these is no row. A dimensioned attribute has a row per cell, by the same rule, in the order
     *  of {@code model}'s labels ({@link #cellRows}). */
    private static ArrayNode valuesOf(ObjectType ot, Map<String, ObjectNode> entered,
                                        Map<String, Map<List<String>, Object>> derived, boolean made,
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
            Map<List<String>, Object> cells = derived.getOrDefault(a, Map.of());
            if (!ot.dimensionsOf(a).isEmpty()) {
                cellRows(sink, ot, a, entered.get(a), cells, made, model, ng);
                continue;
            }
            if (cells.keySet().stream().anyMatch(labels -> !labels.isEmpty()))
                throw new IllegalStateException("het fixpunt geeft een cel van het attribuut '" + a + "', dat geen"
                        + " dimensies heeft");
            boolean isDerived = cells.containsKey(List.of());
            ObjectNode in = entered.get(a);
            if (!isDerived && in == null) continue;
            ObjectNode rn = sink.addObject();
            name(rn, "attribute", ng.attribute(ot, a), a);
            if (isDerived) {
                java(rn, cells.get(List.of()), dt, ng);
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
     *  value ({@code in}, the input node with its {@code cells}: the input, or on a made object the value the
     *  creation assigned); a cell with none of these is no row. A derived value of a cell the attribute does not have is a broken
     *  promise of uchar's. */
    private static void cellRows(ArrayNode sink, ObjectType ot, String a, ObjectNode in,
                                  Map<List<String>, Object> derived, boolean made, RegelSpraakModel model,
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
            boolean isDerived = derived.containsKey(labels);
            ObjectNode cellIn = entered.get(labels);
            if (!isDerived && cellIn == null) continue;
            ObjectNode rn = sink.addObject();
            name(rn, "attribute", ng.attribute(ot, a), a);
            labels(rn, labels);
            if (isDerived) {
                java(rn, derived.get(labels), dt, ng);
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
     *  object ({@code made}) the value the creation assigned, which {@code inputOrigin} names. */
    private static void beside(ObjectNode rn, JsonNode given, boolean made) {
        rn.set("input", given);
        if (made) rn.put("inputOrigin", "creation");
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
     *  ({@code derived}, with {@code input: true} when the input set it too) or set by the input ({@code input}). A
     *  time-dependent characteristic holds in {@code periods}: those a rule wrote ({@code derivedPeriods}), or else
     *  those of the input ({@code enteredPeriods}); without a period it is no row. uchar gives no input periods on a
     *  characteristic a rule writes (its fault EF-041), so a row never has both. */
    private static ArrayNode characteristicsOf(ObjectType ot, Set<String> entered, Map<String, ArrayNode> enteredPeriods,
                                               Set<String> derived, Map<String, ArrayNode> derivedPeriods, Naming ng) {
        List<String> declared = new ArrayList<>();
        for (ObjectType t : ot.chain()) declared.addAll(t.characteristics);
        for (String k : derived)
            if (declared.stream().noneMatch(k::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het kenmerk '" + k + "', dat het objecttype '"
                        + ot.name + "' niet declareert");
        ArrayNode sink = NF.arrayNode();
        for (String k : declared) {
            ArrayNode dp = derivedPeriods.get(k), ip = enteredPeriods.get(k);
            if (dp != null || ip != null) {
                boolean written = dp != null && !dp.isEmpty();
                if (written && ip != null)
                    throw new IllegalStateException("het fixpunt geeft het tijdsafhankelijke kenmerk '" + k + "' van "
                            + ot.name + " perioden van een regel naast perioden van de invoer");
                if (!written && (ip == null || ip.isEmpty())) continue;
                ObjectNode kn = sink.addObject();
                name(kn, "characteristic", ng.characteristic(ot, k), k);
                kn.put("origin", written ? "derived" : "input");
                kn.set("periods", written ? dp : ip);
                continue;
            }
            boolean a = derived.contains(k), i = entered.contains(k);
            if (!a && !i) continue;
            ObjectNode kn = sink.addObject();
            name(kn, "characteristic", ng.characteristic(ot, k), k);
            kn.put("origin", a ? "derived" : "input");
            if (a && i) kn.put("input", true);
        }
        return sink;
    }

    /** The true periods of a time-dependent characteristic, uchar's segments without a value, as {@code from},
     *  {@code to} nodes as the request gives them; an open bound is left out, as in a segment ({@link #java}). */
    public static ArrayNode periodNodes(List<?> segments) {
        ArrayNode sink = NF.arrayNode();
        for (Object seg : segments) {
            ObjectNode p = sink.addObject();
            Object from = CaseSession.read(seg, "getVan"), to = CaseSession.read(seg, "getTot");
            if (from != null) p.set("from", scalar(from, null));
            if (to != null) p.set("to", scalar(to, null));
        }
        return sink;
    }

    /** The sort key of made object {@code o}: its type, its creation, the key of its subject (a label, or the key of
     *  the made object that is its subject) and its values. */
    private static String key(Object o, Map<Object, String> ref, Map<Object, EndState.EndObject> made,
                                  Map<Object, ObjectType> type, Map<Object, ArrayNode[]> members,
                                  Map<Object, String> key, List<Object> path) {
        String label = ref.get(o);
        if (label != null) return label;
        String known = key.get(o);
        if (known != null) return known;
        if (path.stream().anyMatch(p -> p == o))
            throw new IllegalStateException("de gemaakte objecten zijn elkaars onderwerp in een kring");
        path.add(o);
        EndState.EndObject g = made.get(o);
        String subject = made.containsKey(g.subject()) || ref.containsKey(g.subject())
                ? key(g.subject(), ref, made, type, members, key, path)
                : refOf(g.subject(), ref);
        String k = String.join("\u0000", type.get(o).name, g.creation(), subject, members.get(o)[0].toString(),
                members.get(o)[1].toString());
        key.put(o, k);
        path.remove(path.size() - 1);
        return k;
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
        if (v instanceof LocalDateTime d) return NF.textNode(ValueCoder.dateTimeLexeme(d));
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
