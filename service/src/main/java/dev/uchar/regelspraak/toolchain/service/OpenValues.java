package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.ir.Expr;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRule;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.transpiler.core.Namen;

import java.util.ArrayList;
import java.util.List;

/**
 * The contract of a strict rule set, one that infers nothing from an empty value (the dialect's {@code Afleiden uit leeg:
 * nee}, the document's {@code inferFromEmpty: false}; uchar's RUNTIME-SEMANTICS ch. 1.5). A member of an object is filled
 * there, settled leeg, or open: neither a value nor leeg stands. So the answer gives every member with its state
 * ({@link #state(String)} and its sisters) and no null or boolean, and a request can state that a characteristic stands
 * not to hold ({@link #characteristic}). {@link #in} is the one place where the service asks whether a rule set is strict;
 * every other rule set is read and answered without this class.
 *
 * <p>An absence marker ({@link Markers}) is a filled value, with a state of its own so that it is not taken for the value
 * of the datatype.
 */
public final class OpenValues {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** The key of the state of a member, in a request and in an answer. */
    static final String STATE = "state";

    static final String FILLED = "filled", MARKER = "marker", EMPTY = "empty", OPEN = "open", HOLDS = "holds",
            HOLDS_NOT = "holdsNot";

    private OpenValues() {}

    /** Whether the rule set of {@code model} is strict. */
    public static boolean in(RegelSpraakModel model) { return !model.inferFromEmpty(); }

    /** Fails where a creation made an object in a session of a strict rule set: uchar carries no creation there. */
    static void refuseMade(RegelSpraakModel model, EndState standing) {
        if (!in(model)) return;
        for (EndState.EndObject o : standing.objects())
            if (o.creation() != null)
                throw new IllegalStateException("de creatie '" + o.creation() + "' maakte een object van " + o.type()
                        + ", en uchar draagt geen creatie in een regelset die uit leeg niets afleidt");
    }

    /** The input members of {@code model} whose declaration says that their input is complete ({@code (volledig gegeven)},
     *  uchar's RUNTIME-SEMANTICS ch. 1.5), as {@code /info} lists them: {@code {"kind": "attribute" | "characteristic",
     *  "objectType", "name"}}, per object type its attributes and then its characteristics, as declared. A member that a
     *  rule or one of {@code groups}, the defaults groups of the document, writes on the objects of the type that declares
     *  it, such as the {@code berekende X} of the opgave marker, is no input. */
    static ArrayNode completeInput(RegelSpraakModel model, List<PrioritizedDefaults> groups) {
        ArrayNode out = NF.arrayNode();
        for (ObjectType ot : model.objectTypes.values()) {
            for (String a : ot.attributes.keySet())
                if (ot.completeAttributes.contains(a) && !written(model, groups, ot, a, false))
                    out.addObject().put("kind", "attribute").put("objectType", ot.name).put("name", a);
            for (String k : ot.characteristics)
                if (ot.completeCharacteristics.contains(k) && !written(model, groups, ot, k, true))
                    out.addObject().put("kind", "characteristic").put("objectType", ot.name).put("name", k);
        }
        return out;
    }

    /** Whether a rule or a defaults group writes member {@code name}, a characteristic where {@code characteristic}, on the
     *  objects of {@code type}: one on {@code type} or on a type above it (ch. 1.5, "An input member"). */
    private static boolean written(RegelSpraakModel model, List<PrioritizedDefaults> groups, ObjectType type, String name,
                                   boolean characteristic) {
        for (ResolvedRule r : model.resolvedRules)
            if (r.target() instanceof Expr.AttributeReference a && a.objectType() != null && a.attribute() != null
                    && (r.kind() == ResolvedRule.Kind.KENMERKTOEKENNING) == characteristic
                    && Namen.norm(a.attribute()).equals(Namen.norm(name)) && type.isA(a.objectType()))
                return true;
        for (PrioritizedDefaults g : groups)
            if (g.isCharacteristic() == characteristic && g.target() != null
                    && Namen.norm(g.target()).equals(Namen.norm(name))
                    && type.isA(model.objectTypeByName(g.subject())))
                return true;
        return false;
    }

    /** A member in the state {@code state}, without a value. */
    static ObjectNode state(String state) { return NF.objectNode().put(STATE, state); }

    /** An attribute filled with {@code value}. */
    static ObjectNode filled(JsonNode value) {
        ObjectNode n = state(FILLED);
        n.set("value", value);
        return n;
    }

    /** An attribute that holds the absence marker {@code marker}. */
    static ObjectNode marker(String marker) { return state(MARKER).put(Markers.KEY, marker); }

    /**
     * What a request says of a characteristic ({@code name} as it writes it) on {@code field}: true where it holds
     * ({@code true}, {@code {"state": "holds"}}), false where it stands not to hold ({@code {"state": "holdsNot"}}), and
     * null where it is not given (null) or after adding the violation of another value. A bare {@code false} is one: a
     * form or a DTO fills a boolean nobody answered with it, and the rules would infer from it.
     */
    static Boolean characteristic(JsonNode value, String name, String field, List<Violation> sink) {
        if (value.isNull()) return null;
        if (value.isBoolean() && value.booleanValue()) return true;
        if (value.isObject()) {
            List<String> other = new ArrayList<>();
            value.fieldNames().forEachRemaining(f -> { if (!f.equals(STATE)) other.add(f); });
            if (!other.isEmpty() || !value.has(STATE) || !value.get(STATE).isTextual()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van kenmerk '" + name + "' is " + FORMS
                        + (other.isEmpty() ? "" : ", zonder " + String.join(", ", other))));
                return null;
            }
            String state = value.get(STATE).asText();
            if (state.equals(HOLDS)) return true;
            if (state.equals(HOLDS_NOT)) return false;
        }
        sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "kenmerk '" + name + "' is " + value + ", en een regelset"
                + " die uit leeg niets afleidt, vraagt een stand: " + FORMS + "; waar het niet gegeven is, ontbreekt het of"
                + " is het null"));
        return null;
    }

    private static final String FORMS = "{\"state\": \"holds\"} (of true) waar het geldt, {\"state\": \"holdsNot\"} waar"
            + " vaststaat dat het niet geldt";
}
