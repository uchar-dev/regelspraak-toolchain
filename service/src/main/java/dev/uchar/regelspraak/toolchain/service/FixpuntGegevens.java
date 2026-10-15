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
import dev.uchar.regelspraak.toolchain.engine.CaseSessie;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.transpiler.kern.Namen;
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
 * render it here. The input of the session is a list of {@link Invoerobject}s: the playground makes them of a
 * scenario's situation, the service of the instances of a request ({@link #invoer}).
 *
 * <p><b>Names</b> are those of the chosen {@link Naamgeving}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (plain decimal, with its scale), a date, a
 * date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth value and null for
 * leeg; a timeline is {@code tijdlijn}, a list of {@code {van, tot, waarde}}, and a dimensioned value
 * {@code dimensies}, its values by coordinate. {@code eenheid} is the declared unit of a number.
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
public final class FixpuntGegevens {

    private FixpuntGegevens() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** A value of the fixpoint in a form no bean of uchar's generator has: the fixpoint is not shown, with this
     *  sentence, rather than shown with the value guessed. */
    public static final class OnweergeefbareWaarde extends RuntimeException {
        OnweergeefbareWaarde(String zin) { super(zin); }
    }

    /**
     * An object of the input of a session: its label, its object type, its bean in the session, and what the input gave
     * it. {@code attributen} holds per attribute the input value as a node with {@code waarde} (and
     * {@code waardeCanoniek} for an enumeration value named otherwise) or {@code tijdlijn}, keyed case-insensitively;
     * {@code kenmerken} the characteristics the input set, case-insensitively.
     */
    public record Invoerobject(String label, Objecttype type, Object bean, Map<String, ObjectNode> attributen,
                               Set<String> kenmerken) {}

    /**
     * The input objects of a request, read off its beans before the session fires: per instance in the order of
     * {@code beans}, the attributes that hold a value and the characteristics that hold. The beans then hold the input
     * alone; the write-back after the fire puts the derived values on the same beans. A to-many or dimensioned member
     * the request did not fill (an empty list or map) is no input value.
     */
    public static List<Invoerobject> invoer(Map<String, Object> beans, Map<String, Objecttype> typePerLabel,
                                            Naamgeving ng) {
        List<Invoerobject> uit = new ArrayList<>();
        for (var e : beans.entrySet()) {
            Objecttype ot = typePerLabel.get(e.getKey());
            if (ot == null)
                throw new IllegalStateException("het verzoek heeft een object '" + e.getKey() + "' zonder objecttype");
            Object bean = e.getValue();
            Map<String, ObjectNode> attributen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Objecttype t : ot.keten())
                for (String a : t.attributen.keySet()) {
                    Object v = RegelSpraakRuntime.get(bean, VerzoekMapper.prop(a));
                    if (v == null || v instanceof List<?> l && l.isEmpty() || v instanceof Map<?, ?> m && m.isEmpty())
                        continue;
                    ObjectNode n = NF.objectNode();
                    java(n, v, ot.attribuut(a), ng);
                    attributen.put(a, n);
                }
            Set<String> kenmerken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Objecttype t : ot.keten())
                for (String k : t.kenmerken)
                    if (Boolean.TRUE.equals(RegelSpraakRuntime.get(bean, VerzoekMapper.prop(k)))) kenmerken.add(k);
            uit.add(new Invoerobject(e.getKey(), ot, bean, attributen, kenmerken));
        }
        return uit;
    }

    /**
     * What stands at fixpoint {@code f} of a session with input {@code invoer}: {@code objecten}, every object of the
     * input and every made object, each with its attribute values and characteristics and their {@code herkomst} (an
     * input value: {@code invoer}; one a rule, a default or an allocation derived: {@code afgeleid}, with the input value
     * it replaced beside it as {@code invoer}; the initialisation of a made object: {@code creatie});
     * {@code gemaakteObjecten} with their creation and subject; the {@code koppelingen} the creations laid; the
     * evaluation {@code fouten} with their kind and uchar's sentence; and the {@code inconsistenties}.
     *
     * @throws OnweergeefbareWaarde a value in a form this view does not show
     */
    public static ObjectNode fixpunt(Fixpunt f, List<Invoerobject> invoer, RegelSpraakModel model, Naamgeving ng) {
        Map<Object, String> ref = new IdentityHashMap<>();
        Map<Object, Objecttype> type = new IdentityHashMap<>();
        Map<Object, Invoerobject> ingevoerd = new IdentityHashMap<>();
        List<Object> invoerObjecten = new ArrayList<>();
        for (Invoerobject i : invoer) {
            if (ref.put(i.bean(), i.label()) != null)
                throw new IllegalStateException("het object '" + i.label() + "' staat twee keer in de invoer");
            type.put(i.bean(), i.type());
            ingevoerd.put(i.bean(), i);
            invoerObjecten.add(i.bean());
        }

        Map<String, Objecttype> typePerKlasse = new HashMap<>();
        for (Objecttype ot : model.objecttypen.values())
            typePerKlasse.put(FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true), ot);
        Map<Object, GemaaktObject> gemaakt = new IdentityHashMap<>();
        for (GemaaktObject g : f.objecten()) {
            Objecttype ot = typePerKlasse.get(g.object().getClass().getName());
            if (ot == null)
                throw new IllegalStateException("het gemaakte object " + g.object().getClass().getName()
                        + " is van geen objecttype van het model");
            type.put(g.object(), ot);
            gemaakt.put(g.object(), g);
        }

        Map<Object, Map<String, AfgeleideWaarde>> waarden = new IdentityHashMap<>();
        for (AfgeleideWaarde w : f.waarden())
            if (waarden.computeIfAbsent(bekend(w.object(), type), o -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                    .put(w.attribuut(), w) != null)
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + w.attribuut() + "' twee keer");
        Map<Object, Set<String>> kenmerken = new IdentityHashMap<>();
        for (AfgeleidKenmerk k : f.kenmerken())
            kenmerken.computeIfAbsent(bekend(k.object(), type),
                    o -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(k.kenmerk());

        // the members of every object first: a made object is ordered by its values too
        Map<Object, ArrayNode[]> leden = new IdentityHashMap<>();
        for (Object o : type.keySet()) {
            Invoerobject in = ingevoerd.get(o);
            leden.put(o, new ArrayNode[]{
                    waardenVan(o, type.get(o), in == null ? Map.of() : in.attributen(),
                            waarden.getOrDefault(o, Map.of()), gemaakt.containsKey(o), ng),
                    kenmerkenVan(type.get(o), in == null ? Set.of() : in.kenmerken(),
                            kenmerken.getOrDefault(o, Set.of()), ng)});
        }
        List<Object> gemaakteObjecten = new ArrayList<>(gemaakt.keySet());
        Map<Object, String> sleutel = new IdentityHashMap<>();
        for (Object o : gemaakteObjecten) sleutel(o, ref, gemaakt, type, leden, sleutel, new ArrayList<>());
        gemaakteObjecten.sort(Comparator.comparing(sleutel::get));
        for (int i = 0; i < gemaakteObjecten.size(); i++) ref.put(gemaakteObjecten.get(i), "#" + (i + 1));

        ObjectNode n = NF.objectNode();
        ArrayNode objecten = n.putArray("objecten");
        List<Object> alle = new ArrayList<>(invoerObjecten);
        alle.addAll(gemaakteObjecten);
        for (Object o : alle) {
            ObjectNode on = objecten.addObject();
            on.put("object", ref.get(o));
            naam(on, "objecttype", ng.objecttype(type.get(o).naam), type.get(o).naam);
            on.set("waarden", leden.get(o)[0]);
            on.set("kenmerken", leden.get(o)[1]);
        }
        ArrayNode gemaakteNode = n.putArray("gemaakteObjecten");
        for (Object o : gemaakteObjecten) {
            GemaaktObject g = gemaakt.get(o);
            ObjectNode gn = gemaakteNode.addObject();
            gn.put("object", ref.get(o));
            naam(gn, "objecttype", ng.objecttype(type.get(o).naam), type.get(o).naam);
            naam(gn, "creatie", ng.vorm(g.creatie()), g.creatie());
            gn.put("onderwerp", refVan(g.onderwerp(), ref));
        }
        List<GemaakteKoppeling> koppelingen = new ArrayList<>(f.koppelingen());
        koppelingen.sort(Comparator.comparing(GemaakteKoppeling::feittype).thenComparing(GemaakteKoppeling::eersteRol)
                .thenComparing(k -> refVan(k.eerste(), ref)).thenComparing(GemaakteKoppeling::tweedeRol)
                .thenComparing(k -> refVan(k.tweede(), ref)));
        ArrayNode koppelingNode = n.putArray("koppelingen");
        for (GemaakteKoppeling k : koppelingen) {
            ObjectNode kn = koppelingNode.addObject();
            naam(kn, "feittype", ng.feittype(k.feittype()), k.feittype());
            naam(kn, "eersteRol", ng.rol(k.feittype(), k.eersteRol()), k.eersteRol());
            kn.put("eerste", refVan(k.eerste(), ref));
            naam(kn, "tweedeRol", ng.rol(k.feittype(), k.tweedeRol()), k.tweedeRol());
            kn.put("tweede", refVan(k.tweede(), ref));
        }
        List<Fout> fouten = new ArrayList<>(f.fouten());
        fouten.sort(Comparator.comparing(Fout::regel).thenComparing(Fout::melding)
                .thenComparing(x -> refVan(x.onderwerp(), ref)));
        ArrayNode foutNode = n.putArray("fouten");
        for (Fout x : fouten) {
            ObjectNode fn = foutNode.addObject();
            naam(fn, "regel", ng.vorm(x.regel()), x.regel());
            fn.put("onderwerp", refVan(x.onderwerp(), ref));
            fn.put("soort", Evaluatiefout.soort(x.fout()).name());
            fn.put("zin", x.melding());
        }
        List<String[]> inconsistenties = new ArrayList<>();
        for (Object o : f.inconsistenties()) {
            Object regel = CaseSessie.lees(o, "getRegel");
            Object onderwerp = CaseSessie.lees(o, "getSubject");
            inconsistenties.add(new String[]{regel == null ? null : regel.toString(),
                    onderwerp == null ? null : refVan(onderwerp, ref)});
        }
        Comparator<String> leegEerst = Comparator.nullsFirst(Comparator.naturalOrder());
        inconsistenties.sort(Comparator.<String[], String>comparing(x -> x[0], leegEerst)
                .thenComparing(x -> x[1], leegEerst));
        ArrayNode incNode = n.putArray("inconsistenties");
        for (String[] x : inconsistenties) {
            ObjectNode in = incNode.addObject();
            if (x[0] == null) in.putNull("regel"); else naam(in, "regel", ng.vorm(x[0]), x[0]);
            if (x[1] == null) in.putNull("onderwerp"); else in.put("onderwerp", x[1]);
        }
        return n;
    }

    /** The attribute values of object {@code o} of type {@code ot} at the fixpoint, in declaration order: a derived
     *  value from the fixpoint, else an input value, else, on a made object, the value its creation initialised on the
     *  bean. A member with none of these is no row; a timeline counts as none when empty. */
    private static ArrayNode waardenVan(Object o, Objecttype ot, Map<String, ObjectNode> ingevoerd,
                                        Map<String, AfgeleideWaarde> afgeleid, boolean gemaakt, Naamgeving ng) {
        List<String> gedeclareerd = new ArrayList<>();
        for (Objecttype t : ot.keten()) gedeclareerd.addAll(t.attributen.keySet());
        for (String a : afgeleid.keySet())
            if (gedeclareerd.stream().noneMatch(a::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het attribuut '" + a + "', dat het objecttype '"
                        + ot.naam + "' niet declareert");
        ArrayNode uit = NF.arrayNode();
        for (String a : gedeclareerd) {
            Datatype dt = ot.attribuut(a);
            AfgeleideWaarde w = afgeleid.get(a);
            ObjectNode in = ingevoerd.get(a);
            Object opBean = gemaakt && w == null ? CaseSessie.lees(o, Namen.getter(a)) : null;
            if (opBean instanceof List<?> l && l.isEmpty()) opBean = null;
            if (w == null && in == null && opBean == null) continue;
            ObjectNode rn = uit.addObject();
            naam(rn, "attribuut", ng.attribuut(ot, a), a);
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
            eenheid(rn, dt);
        }
        return uit;
    }

    /** The characteristics of an object of type {@code ot} that hold at the fixpoint, in declaration order: derived
     *  ({@code afgeleid}, with {@code invoer: true} when the input set it too) or set by the input ({@code invoer}). */
    private static ArrayNode kenmerkenVan(Objecttype ot, Set<String> ingevoerd, Set<String> afgeleid, Naamgeving ng) {
        List<String> gedeclareerd = new ArrayList<>();
        for (Objecttype t : ot.keten()) gedeclareerd.addAll(t.kenmerken);
        for (String k : afgeleid)
            if (gedeclareerd.stream().noneMatch(k::equalsIgnoreCase))
                throw new IllegalStateException("het fixpunt geeft het kenmerk '" + k + "', dat het objecttype '"
                        + ot.naam + "' niet declareert");
        ArrayNode uit = NF.arrayNode();
        for (String k : gedeclareerd) {
            boolean a = afgeleid.contains(k), i = ingevoerd.contains(k);
            if (!a && !i) continue;
            ObjectNode kn = uit.addObject();
            naam(kn, "kenmerk", ng.kenmerk(ot, k), k);
            kn.put("herkomst", a ? "afgeleid" : "invoer");
            if (a && i) kn.put("invoer", true);
        }
        return uit;
    }

    /** The sort key of made object {@code o}: its type, its creation, the key of its subject (a label, or the key of
     *  the made object that is its subject) and its values. */
    private static String sleutel(Object o, Map<Object, String> ref, Map<Object, GemaaktObject> gemaakt,
                                  Map<Object, Objecttype> type, Map<Object, ArrayNode[]> leden,
                                  Map<Object, String> sleutel, List<Object> pad) {
        String label = ref.get(o);
        if (label != null) return label;
        String bekend = sleutel.get(o);
        if (bekend != null) return bekend;
        if (pad.stream().anyMatch(p -> p == o))
            throw new IllegalStateException("de gemaakte objecten zijn elkaars onderwerp in een kring");
        pad.add(o);
        GemaaktObject g = gemaakt.get(o);
        String onderwerp = gemaakt.containsKey(g.onderwerp()) || ref.containsKey(g.onderwerp())
                ? sleutel(g.onderwerp(), ref, gemaakt, type, leden, sleutel, pad)
                : refVan(g.onderwerp(), ref);
        String k = String.join("\u0000", type.get(o).naam, g.creatie(), onderwerp, leden.get(o)[0].toString(),
                leden.get(o)[1].toString());
        sleutel.put(o, k);
        pad.remove(pad.size() - 1);
        return k;
    }

    /** {@code o} itself when it is an object of the input or a made object, which every object the fixpoint names
     *  is. */
    private static Object bekend(Object o, Map<Object, Objecttype> type) {
        if (!type.containsKey(o))
            throw new IllegalStateException("het fixpunt noemt een object dat geen invoer en geen gemaakt object is: "
                    + o.getClass().getName());
        return o;
    }

    private static String refVan(Object o, Map<Object, String> ref) {
        String r = ref.get(o);
        if (r == null)
            throw new IllegalStateException("het fixpunt noemt een object dat geen invoer en geen gemaakt object is: "
                    + o.getClass().getName());
        return r;
    }

    /** Puts {@code gekozen} under {@code sleutel}, and {@code canoniek} beside it when it differs. */
    public static void naam(ObjectNode n, String sleutel, String gekozen, String canoniek) {
        n.put(sleutel, gekozen);
        if (!gekozen.equals(canoniek)) n.put(sleutel + "Canoniek", canoniek);
    }

    /** A value in the form its bean field takes it: under {@code waarde}, a timeline under {@code tijdlijn} and a
     *  dimensioned value under {@code dimensies}. */
    private static void java(ObjectNode n, Object v, Datatype dt, Naamgeving ng) {
        if (v instanceof List<?> segmenten) {
            ArrayNode tl = n.putArray("tijdlijn");
            for (Object seg : segmenten) {
                ObjectNode sn = tl.addObject();
                sn.set("van", scalair(CaseSessie.lees(seg, "getVan"), null));
                sn.set("tot", scalair(CaseSessie.lees(seg, "getTot"), null));
                sn.set("waarde", scalair(CaseSessie.lees(seg, "getWaarde"), dt));
            }
        } else if (v instanceof Map<?, ?> perCoordinaat) {
            ObjectNode d = n.putObject("dimensies");
            new TreeMap<>(perCoordinaat).forEach((c, w) -> d.set(String.valueOf(c), scalair(w, dt)));
        } else if (v instanceof String s && dt instanceof Datatype.Enumeratie) {
            enumWaarde(n, s, dt, ng);
        } else {
            n.set("waarde", scalair(v, dt));
        }
    }

    private static JsonNode scalair(Object v, Datatype dt) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.textNode(b.toPlainString());
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof String s) return NF.textNode(s);
        throw new OnweergeefbareWaarde("een waarde van de vorm " + v.getClass().getName() + " ("
                + (dt == null ? "zonder datatype" : dt.naam()) + ") toont het fixpunt niet");
    }

    /** An enumeration value under {@code waarde}, in the chosen vocabulary, with its canonical spelling beside it. */
    public static void enumWaarde(ObjectNode n, String waarde, Datatype dt, Naamgeving ng) {
        if (dt instanceof Datatype.Enumeratie e) naam(n, "waarde", ng.waarde(e, waarde), waarde);
        else n.put("waarde", waarde);
    }

    /** The declared unit of a number under {@code eenheid}, when it has one. */
    public static void eenheid(ObjectNode n, Datatype dt) {
        Eenheid e = dt == null ? null : dt.eenheidOf();
        if (e != null) n.put("eenheid", e.weergave());
    }
}
