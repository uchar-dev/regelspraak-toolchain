package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the response JSON tree from a fired {@link Uitvoerder.Uitkomst}, an entry point's descriptor uitvoer
 * list, and the request's instance→objecttype map. Per uitvoer selector ({@code Objecttype.attribuut} /
 * {@code Objecttype:kenmerk}), it reads the value off every request instance that IS one of that objecttype
 * (IR-SPEC ch. 2.2 ⊑, see {@link DescriptorLoader#objectMatch} — a subtype instance is served by a selector
 * written against its supertype, exactly as the supertype's rules fire on it) and renders it:
 * a {@link BigDecimal} as an EXACT JSON number (Jackson renders BigDecimal natively — never a double), a
 * {@code null}/leeg as JSON {@code null}, a kenmerk as a boolean, a date as an ISO string. It also emits the
 * {@code inconsistenties} array — the {@code regel} names of every {@code Inconsistentie} fact — because the
 * golden verwachtingen include CONSISTENT/INCONSISTENT, and beside it {@code inconsistentieDetails}, the same facts
 * with their subject: the request id of the instance the failed check is about (null when that is no request
 * instance), since uchar pins an inconsistency on its subject.
 *
 * <p>The answer names the declarations through a {@link Naamgeving}: in a vocabulary, the object type, the attributes
 * and characteristics, an enumeration value and the rule of an inconsistency go by their names there, and a
 * declaration without a designation by its canonical name. The canonical choice writes the names as the descriptor's
 * selectors and the facts spell them, as the answer always did.
 *
 * <p>Response shape:
 * <pre>{@code
 * { "instanties": [ { "id": "<label>", "type": "<objecttype>",
 *                     "attributen": { "<attribuut>": <waarde|null>, ... },
 *                     "kenmerken":  { "<kenmerk>": true|false, ... } }, ... ],
 *   "inconsistenties": [ "<regelnaam>", ... ],
 *   "inconsistentieDetails": [ { "regel": "<regelnaam>", "onderwerp": "<id>"|null }, ... ] }
 * }</pre>
 */
public final class AntwoordBouwer {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private final RegelSpraakModel model;
    private final Naamgeving naamgeving;

    public AntwoordBouwer(RegelSpraakModel model) { this(model, Naamgeving.canoniek(model)); }

    public AntwoordBouwer(RegelSpraakModel model, Naamgeving naamgeving) {
        this.model = model;
        this.naamgeving = naamgeving;
    }

    /**
     * @param uitkomst      the fired result (beans by id + inconsistenties)
     * @param uitvoer       the entry point's uitvoer selectors
     * @param typePerLabel  the request's id → objecttype (so a selector matches the right instances)
     */
    public ObjectNode bouw(Uitvoerder.Uitkomst uitkomst, List<String> uitvoer, Map<String, Objecttype> typePerLabel) {
        ObjectNode root = NF.objectNode();
        ArrayNode instanties = root.putArray("instanties");

        // group selectors per instance id, preserving instance order
        for (var e : uitkomst.beans().entrySet()) {
            String id = e.getKey();
            Object bean = e.getValue();
            Objecttype ot = typePerLabel.get(id);
            if (ot == null) continue;

            ObjectNode attributen = NF.objectNode();
            ObjectNode kenmerken = NF.objectNode();
            for (String sel : uitvoer) {
                Uitvoerselector u = Uitvoerselector.ontleed(sel);
                if (u == null) continue;
                if (!DescriptorLoader.objectMatch(model, ot, u.objecttype())) continue;
                Object waarde = RegelSpraakRuntime.get(bean, VerzoekMapper.prop(u.lid()));
                if (u.kenmerk()) kenmerken.set(naamgeving.kenmerk(ot, u.lid()), rendKenmerk(waarde));
                else attributen.set(naamgeving.attribuut(ot, u.lid()), rend(waarde, ot.attribuut(u.lid())));
            }
            if (attributen.isEmpty() && kenmerken.isEmpty()) continue;
            ObjectNode inst = NF.objectNode();
            inst.put("id", id);
            inst.put("type", naamgeving.objecttype(ot.naam));
            if (!attributen.isEmpty()) inst.set("attributen", attributen);
            if (!kenmerken.isEmpty()) inst.set("kenmerken", kenmerken);
            instanties.add(inst);
        }

        ArrayNode inc = root.putArray("inconsistenties");
        ArrayNode details = NF.arrayNode();
        for (Object o : uitkomst.inconsistenties()) {
            Object regel = RegelSpraakRuntime.get(o, "regel");
            String naam = regel == null ? null : naamgeving.vorm(regel.toString());
            inc.add(naam);
            ObjectNode d = details.addObject();
            d.put("regel", naam);
            d.put("onderwerp", idVan(uitkomst.beans(), RegelSpraakRuntime.get(o, "subject")));
        }
        root.set("inconsistentieDetails", details);
        return root;
    }

    /** The request id whose bean IS {@code bean}, or null when it is none of the request's instances. */
    private static String idVan(Map<String, Object> beans, Object bean) {
        if (bean == null) return null;
        for (var e : beans.entrySet()) if (e.getValue() == bean) return e.getKey();
        return null;
    }

    /** Render an attribute value of datatype {@code dt} as an EXACT JSON node: BigDecimal as a number (no double),
     *  date as ISO string, boolean as boolean, tekst as string, an enumeration value by its name in the chosen
     *  vocabulary, null as JSON null. */
    private JsonNode rend(Object v, Datatype dt) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.numberNode(b);          // EXACT (no double)
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof String s) return NF.textNode(dt instanceof Datatype.Enumeratie e ? naamgeving.waarde(e, s) : s);
        return NF.textNode(v.toString());
    }

    private JsonNode rendKenmerk(Object v) {
        return NF.booleanNode(Boolean.TRUE.equals(v));
    }

    /** A stable per-request map (id → objecttype) helper for callers that only kept beans. */
    public static Map<String, Objecttype> typePerLabel(Map<String, Objecttype> in) {
        return new LinkedHashMap<>(in);
    }
}
