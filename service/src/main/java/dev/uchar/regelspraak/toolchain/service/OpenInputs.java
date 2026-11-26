package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.engine.OpenReads;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint.OpenValue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one writer of {@code openInputs}, the input members that keep an open outcome of a strict rule set open now
 * ({@link OpenReads#openInputs}): per member {@code {"kind": "attribute" | "characteristic" | "role", "instance", "name",
 * "forOutputs": [{"instance", "name", "kind"}, ...]}}, the names in the vocabulary of the answer. The decision service and
 * both routes of the playground write the field through this class.
 */
public final class OpenInputs {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private OpenInputs() {}

    /** The {@code openInputs} of an answer: those of the members {@code output} selects of each instance, as the answer gives
     *  them ({@link AnswerBuilder}). {@code types} gives the object type of an instance by its id. */
    static ArrayNode of(OpenReads reads, RegelSpraakModel model, Executor.Outcome outcome, List<String> output,
                        Map<String, ObjectType> types, Naming naming) {
        Set<OpenValue> outcomes = new LinkedHashSet<>();
        outcome.beans().forEach((id, bean) -> {
            if (types.get(id) == null) return;
            for (String sel : output) {
                OutputSelector u = OutputSelector.parse(sel);
                if (u != null && DescriptorLoader.objectMatch(model, types.get(id), u.objectType()))
                    outcomes.add(new OpenValue(bean, u.member(), u.characteristic()));
            }
        });
        return json(reads.openInputs(List.copyOf(outcome.beans().values()), new ArrayList<>(outcomes)), outcome.beans(),
                types, naming);
    }

    /** {@code inputs} as the field writes them, the instances {@code beans} by their ids, each of the type {@code types} gives
     *  its id. */
    public static ArrayNode json(List<OpenReads.OpenInput> inputs, Map<String, Object> beans, Map<String, ObjectType> types,
                                 Naming naming) {
        Map<Object, String> ids = new IdentityHashMap<>();
        beans.forEach((id, bean) -> ids.put(bean, id));
        ArrayNode out = NF.arrayNode();
        for (OpenReads.OpenInput i : inputs) {
            ObjectNode n = out.addObject();
            String id = ids.get(i.input().object());
            n.put("kind", kind(i.input())).put("instance", id).put("name", name(i.input(), types.get(id), naming));
            ArrayNode outputs = n.putArray("forOutputs");
            for (OpenValue o : i.outcomes()) {
                String of = ids.get(o.object());
                outputs.addObject().put("instance", of).put("name", name(o, types.get(of), naming)).put("kind", kind(o));
            }
        }
        return out;
    }

    private static String kind(OpenValue v) {
        return v.role() ? "role" : v.characteristic() ? "characteristic" : "attribute";
    }

    private static String name(OpenValue v, ObjectType type, Naming naming) {
        return v.role() ? naming.role(v.factType(), v.member())
                : v.characteristic() ? naming.characteristic(type, v.member()) : naming.attribute(type, v.member());
    }
}
