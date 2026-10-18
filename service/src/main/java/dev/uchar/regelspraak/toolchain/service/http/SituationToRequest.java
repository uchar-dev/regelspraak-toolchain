package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.ir.ResolvedScenario.AttributeValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.Instance;
import dev.uchar.regelspraak.ir.ResolvedScenario.CharacteristicValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.ParameterValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.RoleValue;
import dev.uchar.regelspraak.ir.ResolvedScenario.ScenarioValue;

import java.math.BigDecimal;

/**
 * MECHANICALLY derive the request body of the service from a resolved scenario's merged
 * {@link ResolvedScenario.Situatie}. Nothing here is hand-typed — every value, label, type, rol target,
 * parameter and the peildatum is read straight off uchar's scenario resolver, so the "one truth, two transports"
 * guarantee holds: the same {@link dev.uchar.regelspraak.resolver.ScenarioResolver} output that the scenario
 * runner lowers to a case file is the request the golden harness posts and the playground's {@code via=dienst}
 * hands to the core ({@link Playground}). Each instance keeps its label as its {@code id}, so an expectation of the
 * case names the instance by an id of the request.
 *
 * <p>The situatie value is ALREADY normalised by the resolver to the target attribuut/parameter's declared unit
 * (2 uur → 120, 90 minuut → 90) as an exact case lexeme, so a numeric value emits as a bare EXACT JSON number (a
 * {@code BigDecimal} node, never a double) with no eenheid object; a date/datetime as its ISO string; a
 * boolean/tekst/enum as its plain JSON scalar. Those are exactly the forms
 * {@link dev.uchar.regelspraak.toolchain.service.ValueCoder} accepts (a plain number is read in the declared unit),
 * so no request value is ever approximated. A timeline value, a time-dependent kenmerk and a fraction that has no
 * exact decimal form have no request form, and fail here by name ({@link NonPortable}).
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
            for (ParameterValue p : situation.parameters()) {
                if (p.tijdlijn() != null)
                    throw new NonPortable("parameter '" + p.parameter() + "' heeft een tijdlijn; het "
                            + "verzoek van de dienst draagt geen tijdlijnwaarde");
                params.set(p.parameter(), valueToJson(p.value()));
            }
        }

        // instanties — one per labelled instance, in first-seen order.
        ArrayNode instances = root.putArray("instanties");
        for (Instance i : situation.instances()) {
            ObjectNode inst = instances.addObject();
            inst.put("type", i.objectType().naam);
            inst.put("id", i.label());

            if (!i.attributen().isEmpty()) {
                ObjectNode attributes = inst.putObject("attributen");
                for (AttributeValue a : i.attributen()) {
                    if (a.tijdlijn() != null)
                        throw new NonPortable("attribuut '" + a.attribute() + "' van " + i.label()
                                + " heeft een tijdlijn; het verzoek van de dienst draagt geen tijdlijnwaarde");
                    attributes.set(a.attribute(), valueToJson(a.value()));
                }
            }
            if (!i.kenmerken().isEmpty()) {
                ObjectNode characteristics = inst.putObject("kenmerken");
                for (CharacteristicValue k : i.kenmerken()) {
                    if (k.periods() != null)
                        throw new NonPortable("kenmerk '" + k.characteristic() + "' van " + i.label()
                                + " is tijdsafhankelijk; het verzoek van de dienst draagt geen perioden");
                    characteristics.put(k.characteristic(), Boolean.TRUE);   // a situatie only ever ASSERTS a kenmerk
                }
            }
            if (!i.rollen().isEmpty()) {
                ObjectNode roles = inst.putObject("rollen");
                for (RoleValue r : i.rollen()) {
                    if (r.meervoudig()) {
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

    /** The already-normalised value → its JSON form the request mapper accepts. A number lexeme → exact number;
     *  LocalDate/LocalDateTime → ISO string; Boolean → boolean; String/enum → string. */
    static com.fasterxml.jackson.databind.JsonNode valueToJson(ScenarioValue w) {
        if (w.number() != null) return NF.numberNode(number(w.number()));   // EXACT BigDecimal, no double
        if (w.date() != null) return NF.textNode(w.date().toString()); // yyyy-MM-dd
        if (w.dateTime() != null) return NF.textNode(w.dateTime().toString());
        if (w.bool() != null) return NF.booleanNode(w.bool());
        if (w.enumValue() != null) return NF.textNode(w.enumValue());  // enum literal
        return NF.textNode(w.tekst());
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
