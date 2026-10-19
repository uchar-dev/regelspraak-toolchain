package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.toolchain.engine.Naming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the response JSON tree from a fired {@link Executor.Outcome}, an entry point's descriptor uitvoer
 * list, and the request's instance→objecttype map. Per uitvoer selector ({@code Objecttype.attribuut} /
 * {@code Objecttype:kenmerk}), it reads the value off every request instance that IS one of that objecttype
 * (IR-SPEC ch. 2.2 ⊑, see {@link DescriptorLoader#objectMatch} — a subtype instance is served by a selector
 * written against its supertype, exactly as the supertype's rules fire on it) and renders it:
 * a {@link BigDecimal} as an EXACT JSON number (Jackson renders BigDecimal natively — never a double), a
 * {@code null}/leeg as JSON {@code null}, a kenmerk as a boolean, a date as an ISO string. It also emits the
 * {@code inconsistenties} array — the {@code line} names of every {@code Inconsistentie} fact — because the
 * golden verwachtingen include CONSISTENT/INCONSISTENT, and beside it {@code inconsistentieDetails}, the same facts
 * with their subject: the request id of the instance the failed check is about (null when that is no request
 * instance), since uchar pins an inconsistency on its subject.
 *
 * <p>The answer names the declarations through a {@link Naming}: in a vocabulary, the object type, the attributes
 * and characteristics, an enumeration value and the rule of an inconsistency go by their names there, and a
 * declaration without a designation by its canonical name. The canonical choice writes the names as the descriptor's
 * selectors and the facts spell them, as the answer always did.
 *
 * <p>Response shape:
 * <pre>{@code
 * { "instances": [ { "id": "<label>", "type": "<objecttype>",
 *                     "attributes": { "<attribuut>": <waarde|null>, ... },
 *                     "characteristics":  { "<kenmerk>": true|false, ... } }, ... ],
 *   "inconsistencies": [ "<regelnaam>", ... ],
 *   "inconsistencyDetails": [ { "rule": "<regelnaam>", "subject": "<id>"|null }, ... ] }
 * }</pre>
 *
 * <p>A request on which the rules give an evaluation fault gets no such answer but the one of
 * {@link #evaluationFault}, and a request whose session reached no fixpoint the one of {@link #noFixpoint}; both
 * adapters send them with HTTP 422.
 */
public final class AnswerBuilder {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private final RegelSpraakModel model;
    private final Naming naming;

    public AnswerBuilder(RegelSpraakModel model) { this(model, Naming.canonical(model)); }

    public AnswerBuilder(RegelSpraakModel model, Naming naming) {
        this.model = model;
        this.naming = naming;
    }

    /**
     * @param uitkomst      the fired result (beans by id + inconsistenties)
     * @param uitvoer       the entry point's uitvoer selectors
     * @param typePerLabel  the request's id → objecttype (so a selector matches the right instances)
     */
    public ObjectNode assemble(Executor.Outcome outcome, List<String> output, Map<String, ObjectType> typePerLabel) {
        ObjectNode root = NF.objectNode();
        ArrayNode instances = root.putArray("instances");

        // group selectors per instance id, preserving instance order
        for (var e : outcome.beans().entrySet()) {
            String id = e.getKey();
            Object bean = e.getValue();
            ObjectType ot = typePerLabel.get(id);
            if (ot == null) continue;

            ObjectNode attributes = NF.objectNode();
            ObjectNode characteristics = NF.objectNode();
            for (String sel : output) {
                OutputSelector u = OutputSelector.parse(sel);
                if (u == null) continue;
                if (!DescriptorLoader.objectMatch(model, ot, u.objectType())) continue;
                Object value = RegelSpraakRuntime.get(bean, RequestMapper.prop(u.member()));
                if (u.characteristic()) characteristics.set(naming.characteristic(ot, u.member()), renderCharacteristic(value));
                else attributes.set(naming.attribute(ot, u.member()), rend(value, ot.attribute(u.member())));
            }
            if (attributes.isEmpty() && characteristics.isEmpty()) continue;
            ObjectNode inst = NF.objectNode();
            inst.put("id", id);
            inst.put("type", naming.objectType(ot.name));
            if (!attributes.isEmpty()) inst.set("attributes", attributes);
            if (!characteristics.isEmpty()) inst.set("characteristics", characteristics);
            instances.add(inst);
        }

        ArrayNode inc = root.putArray("inconsistencies");
        ArrayNode details = NF.arrayNode();
        for (Object o : outcome.inconsistencies()) {
            Object line = RegelSpraakRuntime.get(o, "regel");
            String name = line == null ? null : naming.form(line.toString());
            inc.add(name);
            ObjectNode d = details.addObject();
            d.put("rule", name);
            d.put("subject", idOf(outcome.beans(), RegelSpraakRuntime.get(o, "subject")));
        }
        root.set("inconsistencyDetails", details);
        return root;
    }

    /** The answer to a request on which the rules give an evaluation fault, which both adapters send with HTTP 422,
     *  shaped as the MARKER answer and the answer to a request that does not validate:
     *  {@code {"status": "EVALUATION_FAULT", "error": <sentence>, "faults": [{"kind": <soort>, "error": <uchar's
     *  sentence>}, ...]}}, one element per fault in uchar's order. Where uchar's sentence names an object, it names an
     *  object of the request by its id; no stacktrace and no class name enter it. The detail of the session the request
     *  asked ({@link EvaluationFault#detail()}) follows the faults, under the keys of the answer it would have had. */
    public static ObjectNode evaluationFault(EvaluationFault error) {
        ObjectNode n = NF.objectNode();
        n.put("status", "EVALUATION_FAULT");
        n.put("error", error.getMessage());
        ArrayNode errors = n.putArray("faults");
        for (EvaluationFault.Fault f : error.errors()) {
            ObjectNode fn = errors.addObject();
            fn.put("kind", f.kind().name());
            fn.put("error", f.sentence());
        }
        if (error.detail() != null) n.setAll(error.detail());
        return n;
    }

    /** The answer to a request whose session reached no fixpoint, which both adapters send with HTTP 422, shaped as the
     *  answer to an evaluation fault: {@code {"status": "NO_FIXPOINT", "error": <uchar's sentence>}}. The detail of the
     *  session the request asked ({@link NoFixpoint#detail()}) follows the sentence. */
    public static ObjectNode noFixpoint(NoFixpoint none) {
        ObjectNode n = NF.objectNode();
        n.put("status", "NO_FIXPOINT");
        n.put("error", none.getMessage());
        if (none.detail() != null) n.setAll(none.detail());
        return n;
    }

    /** The request id whose bean IS {@code bean}, or null when it is none of the request's instances. */
    private static String idOf(Map<String, Object> beans, Object bean) {
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
        if (v instanceof String s) return NF.textNode(dt instanceof Datatype.Enumeration e ? naming.value(e, s) : s);
        return NF.textNode(v.toString());
    }

    private JsonNode renderCharacteristic(Object v) {
        return NF.booleanNode(Boolean.TRUE.equals(v));
    }

    /** A stable per-request map (id → objecttype) helper for callers that only kept beans. */
    public static Map<String, ObjectType> typePerLabel(Map<String, ObjectType> in) {
        return new LinkedHashMap<>(in);
    }
}
