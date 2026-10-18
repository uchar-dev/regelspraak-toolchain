package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Eenheid;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.CaseSession;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt.AfgeleidKenmerk;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt.AfgeleideWaarde;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt.GemaakteKoppeling;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt.GemaaktObject;
import dev.uchar.regelspraak.transpiler.tms.Fout;

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

/**
 * What stands at the fixpoint of a session ({@link Fixpunt}, as uchar's entry reads it with {@code DroolsBackend.lees})
 * as JSON: the playground gives it per scenario and the decision service per request ({@code ?fixpunt=true}), so both
 * render it here. The input of the session is a list of {@link InputObject}s: the playground makes them of a
 * scenario's situation, the service of the instances of a request ({@link #input}).
 *
 * <p><b>Names</b> are those of the chosen {@link Naming}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (plain decimal, with its scale), a date, a
 * date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth value and null for
 * leeg; a timeline is {@code tijdlijn}, a list of {@code {van, tot, waarde}}, and a dimensioned value
 * {@code dimensies}, its values by coordinate. {@code unit} is the declared unit of a number.
 *
 * <p><b>Order.</b> uchar's {@link Fixpunt} lists have no order, so every list here is sorted: the objects of the input in
 * the order of the input, the made objects after them; the members of an object in the declaration order of its type,
 * the nearest type of its chain first; the made objects by type, creation, subject and values; links, faults and
 * inconsistencies by their names and the objects they name. An object of the input goes by its label, a made object by
 * {@code #<n>}, its place in that order. Two made objects alike in type, creation, subject and every value change places
 * without changing anything that is shown of them, apart from the links they take part in.
 *
 * <p>No class name of a bean enters the JSON: an object goes by its label and its type by its name. uchar's
 * {@link GemaaktObject} names no type, so the type of a made object is found here as the type whose generated bean
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
     * it. {@code attributes} holds per attribute the input value as a node with {@code waarde} (and
     * {@code waardeCanoniek} for an enumeration value named otherwise) or {@code tijdlijn}, keyed case-insensitively;
     * {@code characteristics} the characteristics the input set, case-insensitively.
     */
    public record InputObject(String label, Objecttype type, Object bean, Map<String, ObjectNode> attributes,
                               Set<String> characteristics) {}

    /**
     * The input objects of a request, read off its beans before the session fires: per instance in the order of
     * {@code beans}, the attributes that hold a value and the characteristics that hold. The beans then hold the input
     * alone; the write-back after the fire puts the derived values on the same beans. A to-many or dimensioned member
     * the request did not fill (an empty list or map) is no input value.
     */
    public static List<InputObject> input(Map<String, Object> beans, Map<String, Objecttype> typePerLabel,
                                            Naming ng) {
        List<InputObject> sink = new ArrayList<>();
        for (var e : beans.entrySet()) {
            Objecttype ot = typePerLabel.get(e.getKey());
            if (ot == null)
                throw new IllegalStateException("het verzoek heeft een object '" + e.getKey() + "' zonder objecttype");
            Object bean = e.getValue();
            Map<String, ObjectNode> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Objecttype t : ot.keten())
                for (String a : t.attributen.keySet()) {
                    Object v = RegelSpraakRuntime.get(bean, RequestMapper.prop(a));
                    if (v == null || v instanceof List<?> l && l.isEmpty() || v instanceof Map<?, ?> m && m.isEmpty())
                        continue;
                    ObjectNode n = NF.objectNode();
                    java(n, v, ot.attribuut(a), ng);
                    attributes.put(a, n);
                }
            Set<String> characteristics = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Objecttype t : ot.keten())
                for (String k : t.kenmerken)
                    if (Boolean.TRUE.equals(RegelSpraakRuntime.get(bean, RequestMapper.prop(k)))) characteristics.add(k);
            sink.add(new InputObject(e.getKey(), ot, bean, attributes, characteristics));
        }
        return sink;
    }

    /**
     * What stands at fixpoint {@code f} of a session with input {@code input}: {@code objects}, every object of the
     * input and every made object, each with its attribute values and characteristics and their {@code herkomst} (an
     * input value: {@code input}; one a rule, a default or an allocation derived: {@code derived}, with the input value
     * it replaced beside it as {@code input}; the initialisation of a made object: {@code creatie});
     * {@code madeObjects} with their creation and subject; the {@code links} the creations laid; the
     * evaluation {@code errors} with their kind and uchar's sentence; and the {@code inconsistencies}.
     *
     * @throws UnrenderableValue a value in a form this view does not show
     */
    public static ObjectNode fixpoint(Fixpunt f, List<InputObject> input, RegelSpraakModel model, Naming ng) {
        Map<Object, String> ref = new IdentityHashMap<>();
        Map<Object, Objecttype> type = new IdentityHashMap<>();
        Map<Object, InputObject> entered = new IdentityHashMap<>();
        List<Object> inputObjects = new ArrayList<>();
        for (InputObject i : input) {
            if (ref.put(i.bean(), i.label()) != null)
                throw new IllegalStateException("het object '" + i.label() + "' staat twee keer in de invoer");
            type.put(i.bean(), i.type());
            entered.put(i.bean(), i);
            inputObjects.add(i.bean());
        }

        Map<String, Objecttype> typePerClass = new HashMap<>();
        for (Objecttype ot : model.objecttypen.values())
            typePerClass.put(FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true), ot);
        Map<Object, GemaaktObject> made = new IdentityHashMap<>();
        for (GemaaktObject g : f.objecten()) {
            Objecttype ot = typePerClass.get(g.object().getClass().getName());
            if (ot == null)
                throw new IllegalStateException("het gemaakte object " + g.object().getClass().getName()
                        + " is van geen objecttype van het model");
            type.put(g.object(), ot);
            made.put(g.object(), g);
        }

        Map<Object, Map<String, AfgeleideWaarde>> values = new IdentityHashMap<>();
        for (AfgeleideWaarde w : f.waarden())
            if (values.computeIfAbsent(known(w.object(), type), o -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                    .put(w.attribuut(), w) != null)
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + w.attribuut() + "' twee keer");
        Map<Object, Set<String>> characteristics = new IdentityHashMap<>();
        for (AfgeleidKenmerk k : f.kenmerken())
            characteristics.computeIfAbsent(known(k.object(), type),
                    o -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(k.kenmerk());

        // the members of every object first: a made object is ordered by its values too
        Map<Object, ArrayNode[]> members = new IdentityHashMap<>();
        for (Object o : type.keySet()) {
            InputObject in = entered.get(o);
            members.put(o, new ArrayNode[]{
                    valuesOf(o, type.get(o), in == null ? Map.of() : in.attributes(),
                            values.getOrDefault(o, Map.of()), made.containsKey(o), ng),
                    characteristicsOf(type.get(o), in == null ? Set.of() : in.characteristics(),
                            characteristics.getOrDefault(o, Set.of()), ng)});
        }
        List<Object> madeObjects = new ArrayList<>(made.keySet());
        Map<Object, String> key = new IdentityHashMap<>();
        for (Object o : madeObjects) key(o, ref, made, type, members, key, new ArrayList<>());
        madeObjects.sort(Comparator.comparing(key::get));
        for (int i = 0; i < madeObjects.size(); i++) ref.put(madeObjects.get(i), "#" + (i + 1));

        ObjectNode n = NF.objectNode();
        ArrayNode objects = n.putArray("objecten");
        List<Object> all = new ArrayList<>(inputObjects);
        all.addAll(madeObjects);
        for (Object o : all) {
            ObjectNode on = objects.addObject();
            on.put("object", ref.get(o));
            name(on, "objecttype", ng.objectType(type.get(o).naam), type.get(o).naam);
            on.set("waarden", members.get(o)[0]);
            on.set("kenmerken", members.get(o)[1]);
        }
        ArrayNode madeNode = n.putArray("gemaakteObjecten");
        for (Object o : madeObjects) {
            GemaaktObject g = made.get(o);
            ObjectNode gn = madeNode.addObject();
            gn.put("object", ref.get(o));
            name(gn, "objecttype", ng.objectType(type.get(o).naam), type.get(o).naam);
            name(gn, "creatie", ng.form(g.creatie()), g.creatie());
            gn.put("onderwerp", refOf(g.onderwerp(), ref));
        }
        List<GemaakteKoppeling> links = new ArrayList<>(f.koppelingen());
        links.sort(Comparator.comparing(GemaakteKoppeling::feittype).thenComparing(GemaakteKoppeling::eersteRol)
                .thenComparing(k -> refOf(k.eerste(), ref)).thenComparing(GemaakteKoppeling::tweedeRol)
                .thenComparing(k -> refOf(k.tweede(), ref)));
        ArrayNode linkNode = n.putArray("koppelingen");
        for (GemaakteKoppeling k : links) {
            ObjectNode kn = linkNode.addObject();
            name(kn, "feittype", ng.factType(k.feittype()), k.feittype());
            name(kn, "eersteRol", ng.role(k.feittype(), k.eersteRol()), k.eersteRol());
            kn.put("eerste", refOf(k.eerste(), ref));
            name(kn, "tweedeRol", ng.role(k.feittype(), k.tweedeRol()), k.tweedeRol());
            kn.put("tweede", refOf(k.tweede(), ref));
        }
        List<Fout> errors = new ArrayList<>(f.fouten());
        errors.sort(Comparator.comparing(Fout::regel).thenComparing(Fout::melding)
                .thenComparing(x -> refOf(x.onderwerp(), ref)));
        ArrayNode faultNode = n.putArray("fouten");
        for (Fout x : errors) {
            ObjectNode fn = faultNode.addObject();
            name(fn, "regel", ng.form(x.regel()), x.regel());
            fn.put("onderwerp", refOf(x.onderwerp(), ref));
            fn.put("soort", EvaluationFault.kind(x.fout()).name());
            fn.put("zin", x.melding());
        }
        List<String[]> inconsistencies = new ArrayList<>();
        for (Object o : f.inconsistenties()) {
            Object line = CaseSession.read(o, "getRegel");
            Object subject = CaseSession.read(o, "getSubject");
            inconsistencies.add(new String[]{line == null ? null : line.toString(),
                    subject == null ? null : refOf(subject, ref)});
        }
        Comparator<String> emptyFirst = Comparator.nullsFirst(Comparator.naturalOrder());
        inconsistencies.sort(Comparator.<String[], String>comparing(x -> x[0], emptyFirst)
                .thenComparing(x -> x[1], emptyFirst));
        ArrayNode incNode = n.putArray("inconsistenties");
        for (String[] x : inconsistencies) {
            ObjectNode in = incNode.addObject();
            if (x[0] == null) in.putNull("regel"); else name(in, "regel", ng.form(x[0]), x[0]);
            if (x[1] == null) in.putNull("onderwerp"); else in.put("onderwerp", x[1]);
        }
        return n;
    }

    /** The attribute values of object {@code o} of type {@code ot} at the fixpoint, in declaration order: a derived
     *  value from the fixpoint, else an input value, else, on a made object, the value its creation initialised on the
     *  bean. A member with none of these is no row; a timeline counts as none when empty. */
    private static ArrayNode valuesOf(Object o, Objecttype ot, Map<String, ObjectNode> entered,
                                        Map<String, AfgeleideWaarde> derived, boolean made, Naming ng) {
        List<String> declared = new ArrayList<>();
        for (Objecttype t : ot.keten()) declared.addAll(t.attributen.keySet());
        for (String a : derived.keySet())
            if (declared.stream().noneMatch(a::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + a + "', dat het objecttype '"
                        + ot.naam + "' niet declareert");
        ArrayNode sink = NF.arrayNode();
        for (String a : declared) {
            Datatype dt = ot.attribuut(a);
            AfgeleideWaarde w = derived.get(a);
            ObjectNode in = entered.get(a);
            Object opBean = made && w == null ? CaseSession.read(o, Namen.getter(a)) : null;
            if (opBean instanceof List<?> l && l.isEmpty()) opBean = null;
            if (w == null && in == null && opBean == null) continue;
            ObjectNode rn = sink.addObject();
            name(rn, "attribuut", ng.attribute(ot, a), a);
            if (w != null) {
                java(rn, w.waarde(), dt, ng);
                rn.put("herkomst", "afgeleid");
                if (in != null) rn.set("invoer", in.has("tijdlijn") ? in.get("tijdlijn") : in.get("waarde"));
            } else if (in != null) {
                rn.setAll(in);
                rn.put("herkomst", "invoer");
            } else {
                java(rn, opBean, dt, ng);
                rn.put("herkomst", "creatie");
            }
            unit(rn, dt);
        }
        return sink;
    }

    /** The characteristics of an object of type {@code ot} that hold at the fixpoint, in declaration order: derived
     *  ({@code derived}, with {@code invoer: true} when the input set it too) or set by the input ({@code input}). */
    private static ArrayNode characteristicsOf(Objecttype ot, Set<String> entered, Set<String> derived, Naming ng) {
        List<String> declared = new ArrayList<>();
        for (Objecttype t : ot.keten()) declared.addAll(t.kenmerken);
        for (String k : derived)
            if (declared.stream().noneMatch(k::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het kenmerk '" + k + "', dat het objecttype '"
                        + ot.naam + "' niet declareert");
        ArrayNode sink = NF.arrayNode();
        for (String k : declared) {
            boolean a = derived.contains(k), i = entered.contains(k);
            if (!a && !i) continue;
            ObjectNode kn = sink.addObject();
            name(kn, "kenmerk", ng.characteristic(ot, k), k);
            kn.put("herkomst", a ? "afgeleid" : "invoer");
            if (a && i) kn.put("invoer", true);
        }
        return sink;
    }

    /** The sort key of made object {@code o}: its type, its creation, the key of its subject (a label, or the key of
     *  the made object that is its subject) and its values. */
    private static String key(Object o, Map<Object, String> ref, Map<Object, GemaaktObject> made,
                                  Map<Object, Objecttype> type, Map<Object, ArrayNode[]> members,
                                  Map<Object, String> key, List<Object> path) {
        String label = ref.get(o);
        if (label != null) return label;
        String known = key.get(o);
        if (known != null) return known;
        if (path.stream().anyMatch(p -> p == o))
            throw new IllegalStateException("de gemaakte objecten zijn elkaars onderwerp in een kring");
        path.add(o);
        GemaaktObject g = made.get(o);
        String subject = made.containsKey(g.onderwerp()) || ref.containsKey(g.onderwerp())
                ? key(g.onderwerp(), ref, made, type, members, key, path)
                : refOf(g.onderwerp(), ref);
        String k = String.join("\u0000", type.get(o).naam, g.creatie(), subject, members.get(o)[0].toString(),
                members.get(o)[1].toString());
        key.put(o, k);
        path.remove(path.size() - 1);
        return k;
    }

    /** {@code o} itself when it is an object of the input or a made object, which every object the fixpoint names
     *  is. */
    private static Object known(Object o, Map<Object, Objecttype> type) {
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
        if (!chosen.equals(canonical)) n.put(key + "Canoniek", canonical);
    }

    /** A value in the form its bean field takes it: under {@code waarde}, a timeline under {@code tijdlijn} and a
     *  dimensioned value under {@code dimensies}. */
    private static void java(ObjectNode n, Object v, Datatype dt, Naming ng) {
        if (v instanceof List<?> segments) {
            ArrayNode tl = n.putArray("tijdlijn");
            for (Object seg : segments) {
                ObjectNode sn = tl.addObject();
                sn.set("van", scalar(CaseSession.read(seg, "getVan"), null));
                sn.set("tot", scalar(CaseSession.read(seg, "getTot"), null));
                sn.set("waarde", scalar(CaseSession.read(seg, "getWaarde"), dt));
            }
        } else if (v instanceof Map<?, ?> perCoordinate) {
            ObjectNode d = n.putObject("dimensies");
            new TreeMap<>(perCoordinate).forEach((c, w) -> d.set(String.valueOf(c), scalar(w, dt)));
        } else if (v instanceof String s && dt instanceof Datatype.Enumeratie) {
            enumValue(n, s, dt, ng);
        } else {
            n.set("waarde", scalar(v, dt));
        }
    }

    private static JsonNode scalar(Object v, Datatype dt) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.textNode(b.toPlainString());
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof String s) return NF.textNode(s);
        throw new UnrenderableValue("een waarde van de vorm " + v.getClass().getName() + " ("
                + (dt == null ? "zonder datatype" : dt.naam()) + ") toont het fixpunt niet");
    }

    /** An enumeration value under {@code waarde}, in the chosen vocabulary, with its canonical spelling beside it. */
    public static void enumValue(ObjectNode n, String value, Datatype dt, Naming ng) {
        if (dt instanceof Datatype.Enumeratie e) name(n, "waarde", ng.value(e, value), value);
        else n.put("waarde", value);
    }

    /** The declared unit of a number under {@code unit}, when it has one. */
    public static void unit(ObjectNode n, Datatype dt) {
        Eenheid e = dt == null ? null : dt.eenheidOf();
        if (e != null) n.put("eenheid", e.weergave());
    }
}
