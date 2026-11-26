package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.FactType;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.Role;
import dev.uchar.regelspraak.model.RegelSpraakModel.RoleBindingRef;
import dev.uchar.regelspraak.toolchain.engine.OpenReads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The contract of a strict rule set, one that infers nothing from what is open (the dialect's {@code Afleiden uit leeg:
 * nee}, the document's {@code inferFromEmpty: false}; uchar's RUNTIME-SEMANTICS ch. 1.5). A member of an object is filled
 * there, settled leeg, or open: neither a value nor leeg stands. So the answer gives every member with its state
 * ({@link #state(String)} and its sisters) and no null or boolean, and a request gives a characteristic as its state, that
 * it holds or stands not to hold ({@link #characteristic}), a scalar attribute either as its value or as
 * {@code {"state": "empty"}} where it is settled leeg ({@link #attributeState}), and a non-reciprocal to-one role either as
 * its target id or as {@code {"state": "empty"}} where it stands to have no linked object ({@link #roleState}).
 * {@link #in} is the one place where the
 * service asks whether a rule set is strict; every other rule set is read and answered without this class.
 */
public final class OpenValues {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    /** The key of the state of a member, in a request and in an answer. */
    static final String STATE = "state";

    static final String FILLED = "filled", OPEN = "open";

    /** The state of a scalar attribute that stands to have no value. */
    public static final String EMPTY = "empty";

    /** The states of a characteristic that holds, and of one that stands not to hold. */
    public static final String HOLDS = "holds", HOLDS_NOT = "holdsNot";

    /** What {@link #attributeState} read of a scalar attribute in a request of a strict rule set. */
    enum AttributeInput { VALUE, EMPTY, INVALID }

    /** What {@link #roleState} read of a non-reciprocal to-one role in a request of a strict rule set. */
    enum RoleInput { LINK, EMPTY, INVALID }

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
     *  uchar's RUNTIME-SEMANTICS ch. 1.5), as {@code /info} lists them: {@code {"kind": "attribute" | "characteristic" |
     *  "role", "objectType", "name"}}, per object type its attributes, then its characteristics, then its non-reciprocal
     *  to-one roles, as declared. A member that a form other than an initialisation writes on the objects of the type that
     *  declares it ({@link OpenReads#written} / {@link OpenReads#roleWritten}, with {@code groups} and {@code roleBindings}),
     *  such as the {@code berekende X} of the opgave marker, is no input. */
    static ArrayNode completeInput(RegelSpraakModel model, List<PrioritizedDefaults> groups) {
        return completeInput(model, groups, Map.of());
    }

    static ArrayNode completeInput(RegelSpraakModel model, List<PrioritizedDefaults> groups,
                                   Map<Object, RoleBindingRef> roleBindings) {
        ArrayNode out = NF.arrayNode();
        for (ObjectType ot : model.objectTypes.values()) {
            for (String a : ot.attributes.keySet())
                if (ot.completeAttributes.contains(a) && !OpenReads.written(model, groups, ot, a, false))
                    out.addObject().put("kind", "attribute").put("objectType", ot.name).put("name", a);
            for (String k : ot.characteristics)
                if (ot.completeCharacteristics.contains(k) && !OpenReads.written(model, groups, ot, k, true))
                    out.addObject().put("kind", "characteristic").put("objectType", ot.name).put("name", k);
            for (FactType ft : model.factTypes.values()) {
                if (ft.reciprocal || ft.roles.size() != 2) continue;
                for (int farIdx = 0; farIdx < 2; farIdx++) {
                    Role far = ft.roles.get(farIdx), near = ft.roles.get(1 - farIdx);
                    if (!far.many() && far.complete() && far.name() != null
                            && near.objectTypeName().equalsIgnoreCase(ot.name)
                            && !OpenReads.roleWritten(model, roleBindings, ot, ft.name, farIdx))
                        out.addObject().put("kind", "role").put("objectType", ot.name).put("name", far.name());
                }
            }
        }
        return out;
    }

    /** A member in the state {@code state}, without a value. */
    public static ObjectNode state(String state) { return NF.objectNode().put(STATE, state); }

    /** An attribute filled with {@code value}. */
    static ObjectNode filled(JsonNode value) {
        ObjectNode n = state(FILLED);
        n.set("value", value);
        return n;
    }

    /**
     * What a request says of a characteristic ({@code name} as it writes it) on {@code field}: true where it holds
     * ({@code {"state": "holds"}}), false where it stands not to hold ({@code {"state": "holdsNot"}}), and null where it is
     * not given (null) or after adding the violation of another value. A bare boolean is one: a form or a DTO fills a
     * boolean nobody answered with {@code false}, and the rules would infer from it; a client that could write {@code true}
     * would meet that only at its first {@code false}.
     */
    static Boolean characteristic(JsonNode value, String name, String field, List<Violation> sink) {
        if (value.isNull()) return null;
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

    /**
     * What a request of a strict rule set says of a scalar attribute ({@code name} as it writes it) on {@code field} when
     * {@code value} carries a {@code state}: {@link AttributeInput#EMPTY} for {@code {"state": "empty"}} (settled leeg,
     * written under the case's {@code attributesEmpty}), {@link AttributeInput#INVALID} after adding a violation for any
     * other state object ({@code WRONG_TYPE} for {@code open}, {@code holds}, {@code holdsNot}, {@code {"state": "filled",
     * "value": ...}} or an unknown state; {@code MALFORMED} where {@code state} is not text or extra keys stand beside it),
     * and {@link AttributeInput#VALUE} when {@code value} is no {@code state} object (a plain value, null, or a unit object).
     */
    static AttributeInput attributeState(JsonNode value, String name, String field, List<Violation> sink) {
        if (value == null || !value.isObject() || !value.has(STATE)) return AttributeInput.VALUE;
        List<String> other = new ArrayList<>();
        value.fieldNames().forEachRemaining(f -> { if (!f.equals(STATE)) other.add(f); });
        JsonNode stateNode = value.get(STATE);
        if (!stateNode.isTextual()) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van attribuut '" + name
                    + "' in een verzoek is {\"state\": \"empty\"}"
                    + (other.isEmpty() ? "" : ", zonder " + String.join(", ", other))));
            return AttributeInput.INVALID;
        }
        String state = stateNode.asText();
        if (state.equals(EMPTY)) {
            if (!other.isEmpty()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van attribuut '" + name
                        + "' in een verzoek is {\"state\": \"empty\"}, zonder " + String.join(", ", other)));
                return AttributeInput.INVALID;
            }
            return AttributeInput.EMPTY;
        }
        if (!other.isEmpty() && !(state.equals(FILLED) && other.equals(List.of("value")))) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van attribuut '" + name
                    + "' in een verzoek is {\"state\": \"empty\"}, zonder " + String.join(", ", other)));
            return AttributeInput.INVALID;
        }
        sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "attribuut '" + name + "' is " + value
                + ", en in een regelset die uit leeg niets afleidt krijgt een scalair attribuut een gewone waarde,"
                + " of {\"state\": \"empty\"} waar vaststaat dat het leeg is; waar het niet gegeven is, ontbreekt het"
                + " of is het null"));
        return AttributeInput.INVALID;
    }

    /**
     * What a request of a strict rule set says of a non-reciprocal to-one role ({@code name} as it writes it) on
     * {@code field} when {@code value} carries a {@code state}: {@link RoleInput#EMPTY} for {@code {"state": "empty"}}
     * (settled without a link, written under the case's {@code rolesEmpty}), {@link RoleInput#INVALID} after adding a
     * violation for any other state object ({@code WRONG_TYPE} for {@code open}, {@code holds}, {@code holdsNot},
     * {@code {"state": "filled", "value": ...}} or an unknown state; {@code MALFORMED} where {@code state} is not text or
     * extra keys stand beside it), and {@link RoleInput#LINK} when {@code value} is no {@code state} object.
     */
    static RoleInput roleState(JsonNode value, String name, String field, List<Violation> sink) {
        if (value == null || !value.isObject() || !value.has(STATE)) return RoleInput.LINK;
        List<String> other = new ArrayList<>();
        value.fieldNames().forEachRemaining(f -> { if (!f.equals(STATE)) other.add(f); });
        JsonNode stateNode = value.get(STATE);
        if (!stateNode.isTextual()) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van rol '" + name
                    + "' in een verzoek is {\"state\": \"empty\"}"
                    + (other.isEmpty() ? "" : ", zonder " + String.join(", ", other))));
            return RoleInput.INVALID;
        }
        String state = stateNode.asText();
        if (state.equals(EMPTY)) {
            if (!other.isEmpty()) {
                sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van rol '" + name
                        + "' in een verzoek is {\"state\": \"empty\"}, zonder " + String.join(", ", other)));
                return RoleInput.INVALID;
            }
            return RoleInput.EMPTY;
        }
        if (!other.isEmpty() && !(state.equals(FILLED) && other.equals(List.of("value")))) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "de stand van rol '" + name
                    + "' in een verzoek is {\"state\": \"empty\"}, zonder " + String.join(", ", other)));
            return RoleInput.INVALID;
        }
        sink.add(Violation.of(Violation.Kind.WRONG_TYPE, field, "rol '" + name + "' is " + value
                + ", en in een regelset die uit leeg niets afleidt krijgt een niet-wederkerige naar-één-rol een"
                + " doel-id, of {\"state\": \"empty\"} waar vaststaat dat er geen gekoppeld object is; waar zij niet"
                + " gegeven is, ontbreekt zij of is zij null"));
        return RoleInput.INVALID;
    }

    /** The two states a request gives of a characteristic, as a sentence names them. */
    static final String FORMS = "{\"state\": \"holds\"} waar het geldt, {\"state\": \"holdsNot\"} waar vaststaat dat het"
            + " niet geldt";
}
