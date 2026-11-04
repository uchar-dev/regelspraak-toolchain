package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.transpiler.core.Namen;

import java.util.ArrayList;
import java.util.List;

/**
 * The absence markers of an attribute (uchar's RUNTIME-SEMANTICS ch. 9.5, the dialect's {@code (of onbekend of niet van
 * toepassing)}): the datatype of its value and the markers it declares, in their declared order. Such an attribute holds
 * uchar's sum type with the constructor {@code waarde}, whose payload is the value, and a nullary constructor per
 * marker; uchar's branch puts a {@code ConstructorValue} on the bean. The request gives the value as a value of the
 * payload datatype and a marker as {@code {"marker": <naam>}}, and the answer gives them back in that form.
 *
 * <p>The model of a RegelSpraak source keeps the markers beside the attribute ({@link ObjectType#markersOf}), its
 * datatype the payload's; the model uchar's loader reads from a {@code uchar-ir-v1} document gives the attribute the sum
 * type itself, which is an attribute with markers where its first constructor is {@code waarde} with a payload and the
 * others are markers without one.
 *
 * @param payload  the datatype of the value
 * @param declared the markers the attribute declares
 */
record Markers(Datatype payload, List<String> declared) {

    /** The key of the marker object of the request and the answer. */
    static final String KEY = "marker";

    /** The markers of attribute {@code attribute} of {@code ot}, or null for an attribute without them. */
    static Markers of(ObjectType ot, String attribute) {
        List<String> declared = ot.markersOf(attribute);
        if (declared != null) return new Markers(ot.attribute(attribute), List.copyOf(declared));
        if (!(ot.attribute(attribute) instanceof Datatype.SumType sum)) return null;
        List<Datatype.Constructor> constructors = sum.constructors();
        if (constructors.size() < 2 || !same(constructors.get(0).name(), RegelSpraakModel.VALUE_CONSTRUCTOR)
                || constructors.get(0).payload() == null) return null;
        List<String> markers = new ArrayList<>();
        for (Datatype.Constructor c : constructors.subList(1, constructors.size())) {
            if (c.payload() != null || RegelSpraakModel.ABSENCE_MARKERS.stream().noneMatch(m -> same(m, c.name())))
                return null;
            markers.add(c.name());
        }
        return new Markers(constructors.get(0).payload(), List.copyOf(markers));
    }

    /** Whether {@code value} is given as a marker: an object with {@code marker}. */
    static boolean isMarker(JsonNode value) {
        return value != null && value.isObject() && value.has(KEY);
    }

    /** The declared marker {@code name} names, as uchar compares declared names ({@link Namen#norm}), or null. */
    String named(String name) {
        for (String m : declared) if (same(m, name)) return m;
        return null;
    }

    private static boolean same(String a, String b) {
        return Namen.norm(a).equals(Namen.norm(b));
    }

    /**
     * The declared marker the marker object {@code value} on {@code field} of attribute {@code attribute} names, or null
     * after adding the violation of an object that is no marker object ({@link Violation.Kind#MALFORMED}) or of a marker
     * the attribute does not declare ({@link Violation.Kind#UNKNOWN_MARKER}).
     */
    String read(JsonNode value, String attribute, String field, List<Violation> sink) {
        List<String> other = new ArrayList<>();
        value.fieldNames().forEachRemaining(f -> { if (!f.equals(KEY)) other.add(f); });
        if (!other.isEmpty() || !value.get(KEY).isTextual()) {
            sink.add(Violation.of(Violation.Kind.MALFORMED, field, "een marker is {\"marker\": <naam>}"
                    + (other.isEmpty() ? ", met een tekst als naam" : ", zonder " + String.join(", ", other))));
            return null;
        }
        String name = value.get(KEY).asText();
        String m = named(name);
        if (m == null)
            sink.add(Violation.of(Violation.Kind.UNKNOWN_MARKER, field, "'" + name + "' is geen marker van attribuut '"
                    + attribute + "' (toegestaan: " + String.join(", ", declared) + ")"));
        return m;
    }

    /** The violation of a marker object on {@code field} of attribute {@code attribute}, which declares no markers. */
    static Violation without(String attribute, String field) {
        return Violation.of(Violation.Kind.WRONG_TYPE, field, "attribuut '" + attribute + "' heeft geen markers;"
                + " {\"marker\": <naam>} is de invoer van een attribuut met markers");
    }
}
