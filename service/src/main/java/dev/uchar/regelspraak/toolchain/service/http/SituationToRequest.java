package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.AttributeValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.Cell;
import dev.uchar.regelspraak.ir.ResolvedScenario.Instance;
import dev.uchar.regelspraak.ir.ResolvedScenario.CharacteristicValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.ParameterValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.RoleValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.ScenarioValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.Segment;
import dev.uchar.regelspraak.ir.ResolvedScenario.TimeWindow;

import java.math.BigDecimal;
import java.util.List;

/**
 * MECHANICALLY derive the request body of the service from a resolved scenario's merged
 * {@link ResolvedScenario.Situation}. Nothing here is hand-typed — every value, label, type, rol target,
 * parameter and the peildatum is read straight off uchar's scenario resolver, so the "one truth, two transports"
 * guarantee holds: the same {@link dev.uchar.regelspraak.resolver.ScenarioResolver} output that the scenario
 * runner lowers to a case file is the request the golden harness posts and the playground's {@code via=service}
 * hands to the core ({@link Playground}). Each instance keeps its label as its {@code id}, so an expectation of the
 * case names the instance by an id of the request.
 *
 * <p>The situatie value is ALREADY normalised by the resolver to the target attribuut/parameter's declared unit
 * (2 uur → 120, 90 minuut → 90) as an exact case lexeme, so a numeric value emits as a bare EXACT JSON number (a
 * {@code BigDecimal} node, never a double) with no eenheid object; a date/datetime as its ISO string; a
 * boolean/tekst/enum as its plain JSON scalar. Those are exactly the forms
 * {@link dev.uchar.regelspraak.toolchain.service.ValueCoder} accepts (a plain number is read in the declared unit),
 * so no request value is ever approximated. The cells of a dimensioned attribute go as {@code cells}, each with its
 * labels and its value in that form, the timeline of a time-dependent parameter or attribute as {@code timeline}, each
 * segment with its bounds and its value in that form, and a time-dependent kenmerk as the {@code periods} in which it
 * holds. A fraction that has no exact decimal form has no request form, and fails here by name ({@link NonPortable}).
 *
 * <p>The response shape produced matches {@link dev.uchar.regelspraak.toolchain.service.Request}:
 * {@code instanties[]{type,id,attributen,kenmerken,rollen}}, {@code parameters}, {@code rekendatum}.
 */
final class SituationToRequest {

    /** Exact BigDecimal rendering: a JSON number is a BigDecimal node, never a double. */
    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private SituationToRequest() {}

    /** A value of the situation that a request of the service cannot carry, named in the sentence. */
    static final class NonPortable extends IllegalArgumentException {
        NonPortable(String sentence) { super(sentence); }
        NonPortable(String sentence, Throwable cause) { super(sentence, cause); }
    }

    /** Build the request tree from a resolved scenario's situatie + peildatum. */
    static ObjectNode assemble(ResolvedScenario scenario) {
        ResolvedScenario.Situation situation = scenario.situation();
        ObjectNode root = NF.objectNode();

        // rekendatum (peildatum) — the mapper reads it as an ISO date.
        if (scenario.asOfDate() != null) root.put("rekendatum", scenario.asOfDate().toString());

        // parameters — the last-wins merged parameter values, keyed by declared parameter name.
        if (!situation.parameters().isEmpty()) {
            ObjectNode params = root.putObject("parameters");
            for (ParameterValue p : situation.parameters())
                params.set(p.parameter(), p.timeline() != null ? timelineToJson(p.timeline()) : valueToJson(p.value()));
        }

        // instanties — one per labelled instance, in first-seen order.
        ArrayNode instances = root.putArray("instances");
        for (Instance i : situation.instances()) {
            ObjectNode inst = instances.addObject();
            inst.put("type", i.objectType().name);
            inst.put("id", i.label());

            if (!i.attributes().isEmpty()) {
                ObjectNode attributes = inst.putObject("attributes");
                for (AttributeValue a : i.attributes())
                    attributes.set(a.attribute(), a.timeline() != null ? timelineToJson(a.timeline())
                            : a.cells() != null ? cellsToJson(a.cells()) : valueToJson(a.value()));
            }
            if (!i.characteristics().isEmpty()) {
                ObjectNode characteristics = inst.putObject("characteristics");
                for (CharacteristicValue k : i.characteristics()) {
                    if (k.periods() != null) {
                        ArrayNode periods = characteristics.putObject(k.characteristic()).putArray("periods");
                        for (TimeWindow p : k.periods())
                            periods.addObject().put("from", p.van().toString()).put("to", p.until().toString());
                        continue;
                    }
                    characteristics.put(k.characteristic(), Boolean.TRUE);   // a situatie only ever ASSERTS a kenmerk
                }
            }
            if (!i.roles().isEmpty()) {
                ObjectNode roles = inst.putObject("roles");
                for (RoleValue r : i.roles()) {
                    if (r.many()) {
                        ArrayNode targets = roles.putArray(r.key());
                        for (String label : r.targetLabels()) targets.add(label);
                    } else {
                        roles.put(r.key(), r.targetLabels().get(0));
                    }
                }
            }
        }
        return root;
    }

    /** The timeline of a time-dependent parameter or attribute as the request gives it, {@code {"timeline": [{"from", "to",
     *  "value"}, ...]}}: each segment with its bounds, a bound left out where it is open, and its value as
     *  {@link #valueToJson} writes it. */
    private static ObjectNode timelineToJson(List<Segment> timeline) {
        ObjectNode n = NF.objectNode();
        ArrayNode list = n.putArray("timeline");
        for (Segment s : timeline) {
            ObjectNode segment = list.addObject();
            if (s.van() != null) segment.put("from", s.van().toString());
            if (s.until() != null) segment.put("to", s.until().toString());
            segment.set("value", valueToJson(s.value()));
        }
        return n;
    }

    /** The cells of a dimensioned attribute as the request gives them, {@code {"cells": [{"labels", "value"}, ...]}}:
     *  the labels as uchar's scenario resolver gives them, one per dimension in their declared order, each value as
     *  {@link #valueToJson} writes it. */
    private static ObjectNode cellsToJson(List<Cell> cells) {
        ObjectNode n = NF.objectNode();
        ArrayNode list = n.putArray("cells");
        for (Cell c : cells) {
            ObjectNode cell = list.addObject();
            ArrayNode labels = cell.putArray("labels");
            c.labels().forEach(labels::add);
            cell.set("value", valueToJson(c.value()));
        }
        return n;
    }

    /** The already-normalised value → its JSON form the request mapper accepts. A number lexeme → exact number;
     *  LocalDate/LocalDateTime → ISO string; Boolean → boolean; String/enum → string. */
    static com.fasterxml.jackson.databind.JsonNode valueToJson(ScenarioValue w) {
        if (w.number() != null) return NF.numberNode(number(w.number()));   // EXACT BigDecimal, no double
        if (w.date() != null) return NF.textNode(w.date().toString()); // yyyy-MM-dd
        if (w.dateTime() != null) return NF.textNode(w.dateTime().toString());
        if (w.bool() != null) return NF.booleanNode(w.bool());
        if (w.enumValue() != null) return NF.textNode(w.enumValue());  // enum literal
        return NF.textNode(w.text());
    }

    /** A case number lexeme as an exact BigDecimal: a decimal with a dot, or a reduced fraction {@code p/q} whose
     *  quotient terminates. A fraction that does not terminate has no exact decimal, and no request carries it. */
    static BigDecimal number(String lexeme) {
        int streep = lexeme.indexOf('/');
        if (streep < 0) return new BigDecimal(lexeme);
        BigDecimal numerator = new BigDecimal(lexeme.substring(0, streep));
        BigDecimal denominator = new BigDecimal(lexeme.substring(streep + 1));
        try {
            return numerator.divide(denominator);
        } catch (ArithmeticException e) {
            throw new NonPortable("de breuk " + lexeme + " heeft geen exacte decimale vorm", e);
        }
    }
}
