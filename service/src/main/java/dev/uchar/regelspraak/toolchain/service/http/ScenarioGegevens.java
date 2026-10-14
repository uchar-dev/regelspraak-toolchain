package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.AttribuutWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Instantie;
import dev.uchar.regelspraak.ir.ResolvedScenario.KenmerkWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.ParameterWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Periode;
import dev.uchar.regelspraak.ir.ResolvedScenario.RolWaarde;
import dev.uchar.regelspraak.ir.ResolvedScenario.Segment;
import dev.uchar.regelspraak.ir.ResolvedScenario.Waarde;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Eenheid;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.CaseSessie;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.toolchain.service.Evaluatiefout;
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

/**
 * The data of one scenario the playground gives beside uchar's report: its situation as uchar's scenario resolver
 * gives it ({@link ResolvedScenario}), the source line of each expectation, and what stands at the fixpoint of the
 * case's own session ({@link CaseSessie.Uitkomst#fixpunt}). It reads uchar's public API and computes nothing itself.
 *
 * <p><b>Names</b> are those of the chosen {@link Naamgeving}; where a name there differs from the canonical one, the
 * canonical name stands beside it under the key with {@code Canoniek} appended ({@code attribuut} and
 * {@code attribuutCanoniek}). <b>A value</b> is a JSON string for a number (the exact lexeme or plain decimal, with its
 * scale), a date, a date-time, a text and an enumeration value (in the chosen vocabulary), a JSON boolean for a truth
 * value and null for leeg; a timeline is {@code tijdlijn}, a list of {@code {van, tot, waarde}}, and a dimensioned
 * value {@code dimensies}, its values by coordinate. {@code eenheid} is the declared unit of a number.
 *
 * <p><b>Order.</b> uchar's {@link Fixpunt} lists have no order, so every list here is sorted: the objects of a
 * situation and of the fixpoint in the order of the situation, the made objects after them; the members of an object
 * in the declaration order of its type, the nearest type of its chain first; the made objects by type, creation,
 * subject and values; links, faults and inconsistencies by their names and the objects they name. An object of the
 * input goes by its label, a made object by {@code #<n>}, its place in that order: a label is a word with a capital,
 * so the two never meet. Two made objects alike in type, creation, subject and every value change places without
 * changing anything that is shown of them, apart from the links they take part in.
 */
final class ScenarioGegevens {

    private ScenarioGegevens() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** A value of the fixpoint in a form no bean of uchar's generator has: the fixpoint is not shown, with this
     *  sentence, rather than shown with the value guessed. */
    static final class OnweergeefbareWaarde extends RuntimeException {
        OnweergeefbareWaarde(String zin) { super(zin); }
    }

    /** The situation of {@code s}: {@code peildatum} (the case's evaluation date, which the session carries as its
     *  {@code rekendatum}; null without one), {@code objecten} with their label, type, attribute values,
     *  characteristics and roles, and {@code parameters}: the values of the chosen parameter set on the peildatum
     *  first, then those of the situation. Every assignment carries its source line ({@code regel}). */
    static ObjectNode situatie(ResolvedScenario s, RegelSpraakModel model, Naamgeving ng) {
        ObjectNode n = NF.objectNode();
        if (s.peildatum() == null) n.putNull("peildatum"); else n.put("peildatum", s.peildatum().toString());
        ArrayNode objecten = n.putArray("objecten");
        for (Instantie i : s.situatie().instanties()) {
            Objecttype ot = i.objecttype();
            ObjectNode on = objecten.addObject();
            on.put("label", i.label());
            naam(on, "objecttype", ng.objecttype(ot.naam), ot.naam);
            ArrayNode attributen = on.putArray("attributen");
            for (AttribuutWaarde a : i.attributen()) {
                ObjectNode an = attributen.addObject();
                naam(an, "attribuut", ng.attribuut(ot, a.attribuut()), a.attribuut());
                Datatype dt = ot.attribuut(a.attribuut());
                if (a.tijdlijn() != null) an.set("tijdlijn", tijdlijn(a.tijdlijn(), dt, ng));
                else waarde(an, a.waarde(), dt, ng);
                eenheid(an, dt);
                an.put("regel", a.line());
            }
            ArrayNode kenmerken = on.putArray("kenmerken");
            for (KenmerkWaarde k : i.kenmerken()) {
                ObjectNode kn = kenmerken.addObject();
                naam(kn, "kenmerk", ng.kenmerk(ot, k.kenmerk()), k.kenmerk());
                if (k.perioden() != null) {
                    ArrayNode perioden = kn.putArray("perioden");
                    for (Periode p : k.perioden())
                        perioden.addObject().put("van", p.van().toString()).put("tot", p.tot().toString());
                }
                kn.put("regel", k.line());
            }
            ArrayNode rollen = on.putArray("rollen");
            for (RolWaarde r : i.rollen()) {
                ObjectNode rn = rollen.addObject();
                naam(rn, "rol", ng.rolVanaf(ot, r.sleutel()), r.sleutel());
                naam(rn, "doeltype", ng.objecttype(r.doeltype().naam), r.doeltype().naam);
                ArrayNode labels = rn.putArray("labels");
                for (String l : r.doelLabels()) labels.add(l);
                rn.put("meervoudig", r.meervoudig());
                rn.put("regel", r.line());
            }
        }
        ArrayNode parameters = n.putArray("parameters");
        for (ParameterWaarde p : s.situatie().parameters()) {
            ObjectNode pn = parameters.addObject();
            naam(pn, "parameter", ng.parameter(p.parameter()), p.parameter());
            Datatype dt = parameterType(model, p.parameter());
            if (p.tijdlijn() != null) pn.set("tijdlijn", tijdlijn(p.tijdlijn(), dt, ng));
            else waarde(pn, p.waarde(), dt, ng);
            eenheid(pn, dt);
            pn.put("regel", p.line());
        }
        return n;
    }

    /** Puts on {@code vn} where expectation {@code v} of uchar's report stands in the source: {@code regel}, the line
     *  uchar's scenario resolver gives the expectation ({@link ResolvedScenario.Verwachting#line}, the line of its
     *  first bullet), found by the report's position of the expectation in the case, and {@code regeltekst}, the text
     *  of that line as written. uchar gives the line on which an expectation begins and no end, so the line text is
     *  the whole expectation only where one bullet stands on one line. */
    static void plaats(ObjectNode vn, VerwachtingResultaat v, ResolvedScenario s, List<String> bronregels) {
        int i = v.positie() - 1;
        if (i < 0 || i >= s.verwachtingen().size())
            throw new IllegalStateException("uchars rapport geeft verwachting " + v.positie() + " van het scenario '"
                    + s.naam() + "', dat er " + s.verwachtingen().size() + " heeft");
        int regel = s.verwachtingen().get(i).line();
        if (regel < 1 || regel > bronregels.size())
            throw new IllegalStateException("de verwachting " + v.positie() + " van het scenario '" + s.naam()
                    + "' staat volgens uchar op regel " + regel + ", en de bron telt " + bronregels.size() + " regels");
        vn.put("regel", regel);
        vn.put("regeltekst", bronregels.get(regel - 1).strip());
    }

    /**
     * What stands at the fixpoint of {@code sessie}, the case of scenario {@code s}: {@code objecten}, every object of
     * the input and every made object, each with its attribute values and characteristics and their {@code herkomst}
     * (an input value: {@code invoer}; one a rule, a default or an allocation derived: {@code afgeleid}, with the input
     * value it replaced beside it as {@code invoer}; the initialisation of a made object: {@code creatie});
     * {@code gemaakteObjecten} with their creation and subject; the {@code koppelingen} the creations laid; the
     * evaluation {@code fouten} with their kind and uchar's sentence; and the {@code inconsistenties}.
     *
     * @throws OnweergeefbareWaarde a value in a form this view does not show
     */
    static ObjectNode fixpunt(CaseSessie.Uitkomst sessie, ResolvedScenario s, RegelSpraakModel model, Naamgeving ng) {
        Fixpunt f = sessie.fixpunt();
        Map<Object, String> ref = new IdentityHashMap<>();
        Map<Object, Objecttype> type = new IdentityHashMap<>();
        Map<Object, Instantie> invoer = new IdentityHashMap<>();
        List<Object> invoerObjecten = new ArrayList<>();
        for (Instantie i : s.situatie().instanties()) {
            Object bean = sessie.beans().get(i.label());
            if (bean == null)
                throw new IllegalStateException("de sessie van het scenario '" + s.naam() + "' heeft geen object '"
                        + i.label() + "' van de situatie");
            ref.put(bean, i.label());
            type.put(bean, i.objecttype());
            invoer.put(bean, i);
            invoerObjecten.add(bean);
        }
        if (sessie.beans().size() != invoerObjecten.size())
            throw new IllegalStateException("de sessie van het scenario '" + s.naam() + "' heeft de objecten "
                    + sessie.beans().keySet() + ", de situatie " + invoerObjecten.size());

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
                    o -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(k.kenmerk());

        // the members of every object first: a made object is ordered by its values too
        Map<Object, ArrayNode[]> leden = new IdentityHashMap<>();
        for (Object o : type.keySet())
            leden.put(o, new ArrayNode[]{
                    waardenVan(o, type.get(o), invoer.get(o), waarden.getOrDefault(o, Map.of()),
                            gemaakt.containsKey(o), ng),
                    kenmerkenVan(type.get(o), invoer.get(o), kenmerken.getOrDefault(o, Set.of()), ng)});
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
     *  value from the fixpoint, else an input value from the situation, else, on a made object, the value its
     *  creation initialised on the bean. A member with none of these is no row; a timeline counts as none when
     *  empty. */
    private static ArrayNode waardenVan(Object o, Objecttype ot, Instantie invoer,
                                        Map<String, AfgeleideWaarde> afgeleid, boolean gemaakt, Naamgeving ng) {
        Map<String, AttribuutWaarde> ingevoerd = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (invoer != null) for (AttribuutWaarde a : invoer.attributen()) ingevoerd.put(a.attribuut(), a);
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
            AttribuutWaarde in = ingevoerd.get(a);
            Object opBean = gemaakt && w == null ? CaseSessie.lees(o, Namen.getter(a)) : null;
            if (opBean instanceof List<?> l && l.isEmpty()) opBean = null;
            if (w == null && in == null && opBean == null) continue;
            ObjectNode rn = uit.addObject();
            naam(rn, "attribuut", ng.attribuut(ot, a), a);
            if (w != null) {
                java(rn, w.waarde(), dt, ng);
                rn.put("herkomst", "afgeleid");
                if (in != null) rn.set("invoer", invoerwaarde(in, dt, ng));
            } else if (in != null) {
                if (in.tijdlijn() != null) rn.set("tijdlijn", tijdlijn(in.tijdlijn(), dt, ng));
                else waarde(rn, in.waarde(), dt, ng);
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
    private static ArrayNode kenmerkenVan(Objecttype ot, Instantie invoer, Set<String> afgeleid, Naamgeving ng) {
        Set<String> ingevoerd = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (invoer != null)
            for (KenmerkWaarde k : invoer.kenmerken()) if (k.perioden() == null) ingevoerd.add(k.kenmerk());
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
    private static void naam(ObjectNode n, String sleutel, String gekozen, String canoniek) {
        n.put(sleutel, gekozen);
        if (!gekozen.equals(canoniek)) n.put(sleutel + "Canoniek", canoniek);
    }

    /** A value of the situation under {@code waarde}: the case value as uchar's resolver normalised it. */
    private static void waarde(ObjectNode n, Waarde w, Datatype dt, Naamgeving ng) {
        if (w.getal() != null) n.put("waarde", w.getal());
        else if (w.datum() != null) n.put("waarde", w.datum().toString());
        else if (w.datumTijd() != null) n.put("waarde", w.datumTijd().toString());
        else if (w.bool() != null) n.put("waarde", w.bool());
        else if (w.tekst() != null) n.put("waarde", w.tekst());
        else if (w.enumWaarde() != null) enumWaarde(n, w.enumWaarde(), dt, ng);
        else throw new IllegalStateException("uchars resolver gaf een waarde zonder variant");
    }

    /** The input value {@code in} as a node of its own: its value, or its timeline. */
    private static JsonNode invoerwaarde(AttribuutWaarde in, Datatype dt, Naamgeving ng) {
        if (in.tijdlijn() != null) return tijdlijn(in.tijdlijn(), dt, ng);
        ObjectNode n = NF.objectNode();
        waarde(n, in.waarde(), dt, ng);
        return n.get("waarde");
    }

    private static ArrayNode tijdlijn(List<Segment> segmenten, Datatype dt, Naamgeving ng) {
        ArrayNode uit = NF.arrayNode();
        for (Segment seg : segmenten) {
            ObjectNode sn = uit.addObject();
            sn.put("van", seg.van().toString());
            sn.put("tot", seg.tot().toString());
            waarde(sn, seg.waarde(), dt, ng);
        }
        return uit;
    }

    /** A value of the fixpoint, in the form its bean field takes it: under {@code waarde}, a timeline under
     *  {@code tijdlijn} and a dimensioned value under {@code dimensies}. */
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
                + (dt == null ? "zonder datatype" : dt.naam()) + ") toont de playground niet");
    }

    private static void enumWaarde(ObjectNode n, String waarde, Datatype dt, Naamgeving ng) {
        if (dt instanceof Datatype.Enumeratie e) naam(n, "waarde", ng.waarde(e, waarde), waarde);
        else n.put("waarde", waarde);
    }

    private static void eenheid(ObjectNode n, Datatype dt) {
        Eenheid e = dt == null ? null : dt.eenheidOf();
        if (e != null) n.put("eenheid", e.weergave());
    }

    private static Datatype parameterType(RegelSpraakModel model, String naam) {
        for (RegelSpraakModel.Parameter p : model.parameters.values())
            if (p.naam().equalsIgnoreCase(naam)) return p.datatype();
        throw new IllegalStateException("de situatie geeft de parameter '" + naam + "', die het model niet declareert");
    }

    /** The source split in lines as uchar's lexer counts them: a line ends at a line feed. */
    static List<String> regels(String bron) {
        return List.of(bron.split("\n", -1));
    }
}
