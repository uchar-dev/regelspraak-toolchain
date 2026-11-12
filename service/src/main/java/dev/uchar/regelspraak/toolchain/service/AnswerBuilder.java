package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ComputeHelpers;
import dev.uchar.regelspraak.transpiler.tms.ConstructorValue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the response JSON tree from a fired {@link Executor.Outcome}, an entry point's descriptor uitvoer
 * list, and the request's instance→objecttype map. Per uitvoer selector ({@code Objecttype.attribuut} /
 * {@code Objecttype:kenmerk}), it reads the value of every request instance that IS one of that objecttype
 * (IR-SPEC ch. 2.2 ⊑, see {@link DescriptorLoader#objectMatch} — a subtype instance is served by a selector
 * written against its supertype, exactly as the supertype's rules fire on it) in uchar's end state of the session
 * ({@link Executor.Outcome#endState}, {@code DroolsBackend.endStateOf}): the derived value where a rule derived one,
 * the input elsewhere, and none where the member is leeg. It renders it:
 * a {@link BigDecimal} as an EXACT JSON number (Jackson renders BigDecimal natively — never a double), a
 * {@code null}/leeg as JSON {@code null}, a kenmerk as a boolean, a date as an ISO string. It also emits the
 * {@code inconsistencies} array — the rule of every §9.5 fact of the end state ({@link EndState#channel}), one per
 * rule and subject however often the rule deposited (RUNTIME-SEMANTICS ch. 5.6) — because the golden verwachtingen
 * include CONSISTENT/INCONSISTENT, and beside it {@code inconsistencyDetails}, the same facts with their subject: the
 * request id of the instance the failed check is about (null when that is no request instance), since uchar pins an
 * inconsistency on its subject.
 *
 * <p>The answer names the declarations through a {@link Naming}: in a vocabulary, the object type, the attributes
 * and characteristics, an enumeration value and the rule of an inconsistency go by their names there, and a
 * declaration without a designation by its canonical name. The canonical choice writes the names as the descriptor's
 * selectors and the facts spell them, as the answer always did.
 *
 * <p>A dimensioned attribute is answered as its cells ({@link #cells}), every cell with its labels and its value. An
 * attribute on a timeline is answered as its segments ({@link #timeline}), in the form of uchar's case: a segment
 * without {@code from} begins before every day and one without {@code to} runs past every day (an open bound, uchar's
 * RUNTIME-SEMANTICS ch. 3.1), as uchar's write-back puts a null bound on the bean.
 *
 * <p>An attribute with absence markers ({@link Markers}, uchar's RUNTIME-SEMANTICS ch. 9.5) is answered in the form of
 * the request: its value {@code waarde(…)} as the payload, a marker as {@code {"marker": "onbekend"}}, also as the value
 * of a segment or a cell.
 *
 * <p>Response shape:
 * <pre>{@code
 * { "instances": [ { "id": "<label>", "type": "<objecttype>",
 *                     "attributes": { "<attribuut>": <waarde|null>,
 *                                     "<gedimensioneerd attribuut>": { "cells": [ { "labels": [...],
 *                                                                                   "value": <waarde|null> }, ... ] },
 *                                     "<attribuut op een tijdlijn>": { "timeline": [ { "from"?: "<datum>",
 *                                                                        "to"?: "<datum>", "value": <waarde> }, ... ] },
 *                                     ... },
 *                     "characteristics":  { "<kenmerk>": true|false, ... } }, ... ],
 *   "inconsistencies": [ "<regelnaam>", ... ],
 *   "inconsistencyDetails": [ { "rule": "<regelnaam>", "subject": "<id>"|null }, ... ] }
 * }</pre>
 *
 * <p>A request on which the rules give an evaluation fault gets no such answer but the one of
 * {@link #evaluationFault}, a request whose session reached no fixpoint the one of {@link #noFixpoint}, and a request
 * on which uchar's branch reaches a limit the one of {@link #limit}; both adapters send them with HTTP 422.
 */
public final class AnswerBuilder {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private final RegelSpraakModel model;
    private final Naming naming;

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

        Map<String, EndState.EndObject> byLabel = new HashMap<>();
        for (EndState.EndObject o : outcome.endState().objects()) if (o.label() != null) byLabel.put(o.label(), o);
        // group selectors per instance id, preserving instance order
        for (String id : outcome.beans().keySet()) {
            ObjectType ot = typePerLabel.get(id);
            if (ot == null) continue;
            EndState.EndObject object = byLabel.get(id);
            if (object == null)
                throw new IllegalStateException("uchars eindtoestand noemt het object '" + id + "' van het verzoek niet");

            ObjectNode attributes = NF.objectNode();
            ObjectNode characteristics = NF.objectNode();
            for (String sel : output) {
                OutputSelector u = OutputSelector.parse(sel);
                if (u == null) continue;
                if (!DescriptorLoader.objectMatch(model, ot, u.objectType())) continue;
                if (u.characteristic()) {
                    EndState.Member k = member(object, true, u.member());
                    characteristics.set(naming.characteristic(ot, u.member()),
                            NF.booleanNode(k != null && Boolean.TRUE.equals(k.value())));
                } else if (!ot.dimensionsOf(u.member()).isEmpty())
                    attributes.set(naming.attribute(ot, u.member()), cells(object, ot, u.member()));
                else {
                    EndState.Member a = member(object, false, u.member());
                    Object value = a == null ? null : a.value();
                    attributes.set(naming.attribute(ot, u.member()), value instanceof List<?> segments
                            ? timeline(segments, ot, u.member()) : rend(value, ot, u.member()));
                }
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
        for (EndState.ChannelFact c : outcome.endState().channel()) {
            String name = c.rule() == null ? null : naming.form(c.rule());
            inc.add(name);
            ObjectNode d = details.addObject();
            d.put("rule", name);
            d.put("subject", idOf(outcome.beans(), c.subject()));
        }
        root.set("inconsistencyDetails", details);
        return root;
    }

    /** The answer to a request on which the rules give an evaluation fault, which both adapters send with HTTP 422,
     *  shaped as the MARKER answer and the answer to a request that does not validate:
     *  {@code {"status": "EVALUATION_FAULT", "error": <sentence>, "faults": [{"code": <EF-###>, "params": {<name>:
     *  <text>, ...}, "error": <uchar's sentence>}, ...]}}, one element per fault in uchar's order, its code and
     *  parameters the fault kind uchar's fault API names (null and empty where it names none). Where uchar's sentence names an object, it names an
     *  object of the request by its id; no stacktrace and no class name enter it. The detail of the session the request
     *  asked ({@link EvaluationFault#detail()}) follows the faults, under the keys of the answer it would have had. */
    public static ObjectNode evaluationFault(EvaluationFault error) {
        ObjectNode n = NF.objectNode();
        n.put("status", "EVALUATION_FAULT");
        n.put("error", error.getMessage());
        ArrayNode errors = n.putArray("faults");
        for (EvaluationFault.Fault f : error.errors()) {
            ObjectNode fn = errors.addObject();
            code(fn, f);
            fn.put("error", f.sentence());
        }
        if (error.detail() != null) n.setAll(error.detail());
        return n;
    }

    /** Puts the fault kind of {@code f} on {@code n}: {@code code}, null where uchar names no kind, and {@code params},
     *  an object of strings. */
    static void code(ObjectNode n, EvaluationFault.Fault f) {
        code(n, f.code(), f.params());
    }

    private static void code(ObjectNode n, String code, Map<String, String> params) {
        n.put("code", code);
        ObjectNode p = n.putObject("params");
        params.forEach(p::put);
    }

    /** The answer to a request whose session reached no fixpoint, which both adapters send with HTTP 422, shaped as the
     *  answer to an evaluation fault: {@code {"status": "NO_FIXPOINT", "error": <uchar's sentence>, "code": <EF-015 or
     *  null>, "params": {...}}}, the fault kind uchar names for the end of the run (EF-015 at the creation budget, none
     *  at the cap). The detail of the session the request asked ({@link NoFixpoint#detail()}) follows. */
    public static ObjectNode noFixpoint(NoFixpoint none) {
        ObjectNode n = NF.objectNode();
        n.put("status", "NO_FIXPOINT");
        n.put("error", none.getMessage());
        code(n, none.code(), none.params());
        if (none.detail() != null) n.setAll(none.detail());
        return n;
    }

    /** The answer to a request on which uchar's Drools branch reaches a limit at the fixpoint ({@link Limit}), which
     *  both adapters send with HTTP 422: {@code {"status": "LIMIT", "error": <sentence>, "code": <uchar's register
     *  code>, "reason": <uchar's sentence>}}. The detail of the session the request asked ({@link Limit#detail()})
     *  follows the reason. */
    public static ObjectNode limit(Limit limit) {
        ObjectNode n = NF.objectNode();
        n.put("status", "LIMIT");
        n.put("error", "uchars Drools-tak houdt een waarde van deze invoer niet: een grens van de tak, geen fout van de"
                + " regels of van de invoer");
        n.put("code", limit.code());
        n.put("reason", limit.getMessage());
        if (limit.detail() != null) n.setAll(limit.detail());
        return n;
    }

    /** The request id whose bean IS {@code bean}, or null when it is none of the request's instances. */
    private static String idOf(Map<String, Object> beans, Object bean) {
        if (bean == null) return null;
        for (var e : beans.entrySet()) if (e.getValue() == bean) return e.getKey();
        return null;
    }

    /** The member {@code name} of {@code object} in the end state, an attribute without a cell or a characteristic
     *  ({@code characteristic}); null where it holds no value. */
    private static EndState.Member member(EndState.EndObject object, boolean characteristic, String name) {
        String key = Namen.norm(name);
        for (EndState.Member m : object.members())
            if (m.characteristic() == characteristic && m.cell() == null && Namen.norm(m.name()).equals(key)) return m;
        return null;
    }

    /** Render a value of attribute {@code attribute} of {@code ot} as {@link #rend(Object, Datatype)} renders a value
     *  of its datatype. The end state holds a derived number of the unbounded form that does not terminate as the
     *  runtime's exact rational; the answer gives it as the decimal a bean holds ({@link ComputeHelpers#collapse}), the
     *  form a timeline and the request carry. A value of an attribute with absence markers ({@link Markers}) is uchar's
     *  constructor value: the value {@code waarde(…)} as its payload, a marker as {@code {"marker": <naam>}}, as the
     *  request gives them. The descriptor refuses another sum type in {@code output} ({@link DescriptorLoader}), so a
     *  constructor value on an attribute without markers is a broken promise, and fails. */
    private JsonNode rend(Object exact, ObjectType ot, String attribute) {
        Object v = ComputeHelpers.collapse(exact);
        if (!(v instanceof ConstructorValue c)) return rend(v, ot.attribute(attribute));
        Markers markers = Markers.of(ot, attribute);
        if (markers == null)
            throw new IllegalStateException("het attribuut '" + attribute + "' van " + ot.name + " heeft de waarde " + c
                    + " van een sum type, die het antwoord alleen geeft voor een attribuut met markers");
        return c.payload() != null ? rend(c.payload(), markers.payload())
                : NF.objectNode().put(Markers.KEY, c.constructor());
    }

    /** Render an attribute value of datatype {@code dt} as an EXACT JSON node: BigDecimal as a number (no double),
     *  date as ISO string, boolean as boolean, tekst as string, an enumeration value by its name in the chosen
     *  vocabulary, null as JSON null. */
    private JsonNode rend(Object v, Datatype dt) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.numberNode(b);          // EXACT (no double)
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(ValueCoder.dateTimeLexeme(d));
        if (v instanceof String s) return NF.textNode(dt instanceof Datatype.Enumeration e ? naming.value(e, s) : s);
        return NF.textNode(v.toString());
    }

    /** The value of the dimensioned attribute {@code attribute} of {@code ot} as its cells, {@code {"cells":
     *  [{"labels": [...], "value": <waarde|null>}, ...]}}: every cell of the attribute, its labels in their declared
     *  spelling, the first dimension outermost and each in the declared order of its labels, null for an empty cell.
     *  The end state lists the cells of {@code object} that hold a value, by their key
     *  ({@link FactModelGenerator#cellKey}); a cell it does not list is empty. */
    private JsonNode cells(EndState.EndObject object, ObjectType ot, String attribute) {
        String key = Namen.norm(attribute);
        Map<List<String>, Object> filled = new HashMap<>();
        for (EndState.Member m : object.members())
            if (!m.characteristic() && m.cell() != null && Namen.norm(m.name()).equals(key)) filled.put(m.cell(), m.value());
        ArrayNode cells = NF.arrayNode();
        for (List<String> labels : cellsOf(model, ot.dimensionsOf(attribute))) {
            ObjectNode cell = cells.addObject();
            ArrayNode ln = cell.putArray("labels");
            labels.forEach(ln::add);
            cell.set("value", rend(filled.get(labels), ot, attribute));
        }
        ObjectNode n = NF.objectNode();
        n.set("cells", cells);
        return n;
    }

    /** The value of the attribute {@code attribute} of {@code ot} on a timeline as its segments, {@code {"timeline":
     *  [{"from": <datum>, "to": <datum>, "value": <waarde|null>}, ...]}} in the order the end state gives them, the
     *  bean model's segments ({@code Period.beans}), a null bound left out, as uchar's case leaves out an open bound.
     *  A timeline attribute that holds no value is null, as an empty value (uchar's RUNTIME-SEMANTICS ch. 3.1: such a
     *  timeline reads leeg on every day). */
    private JsonNode timeline(List<?> segments, ObjectType ot, String attribute) {
        ArrayNode out = NF.arrayNode();
        for (Object segment : segments) {
            ObjectNode sn = out.addObject();
            Object from = RegelSpraakRuntime.get(segment, "van"), to = RegelSpraakRuntime.get(segment, "tot");
            if (from != null) sn.put("from", from.toString());
            if (to != null) sn.put("to", to.toString());
            sn.set("value", rend(RegelSpraakRuntime.get(segment, "waarde"), ot, attribute));
        }
        ObjectNode n = NF.objectNode();
        n.set("timeline", out);
        return n;
    }

    /** Every cell of an attribute with {@code dimensions}, as uchar keys a cell ({@link FactModelGenerator#cellKey}):
     *  one label per dimension, in its declared spelling; the first dimension outermost, each in its declared order. */
    static List<List<String>> cellsOf(RegelSpraakModel model, List<String> dimensions) {
        List<List<String>> cells = List.of(List.of());
        for (String d : dimensions) {
            RegelSpraakModel.Dimension dimension = model.dimensionByName(d);
            if (dimension == null)
                throw new IllegalStateException("de dimensie '" + d + "' van een gedimensioneerd attribuut is niet"
                        + " gedeclareerd");
            List<List<String>> longer = new ArrayList<>();
            for (List<String> cell : cells)
                for (String label : dimension.values()) {
                    List<String> next = new ArrayList<>(cell);
                    next.add(label);
                    longer.add(List.copyOf(next));
                }
            cells = longer;
        }
        return cells;
    }
}
