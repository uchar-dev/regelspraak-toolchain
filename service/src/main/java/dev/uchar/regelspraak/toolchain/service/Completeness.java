package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;

import java.util.List;

/**
 * Per vocabulary of a model, whether it is complete and which declarations have no designation in it: the
 * declarations an answer in that vocabulary names by their canonical name. uchar's WF-119 makes only the default
 * vocabulary complete, so for every other one a consumer reads the fallback here instead of guessing it. The
 * declarations are those uchar can designate ({@link RegelSpraakModel#designatable}), in its order.
 */
public final class Completeness {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private Completeness() {}

    /** The declarations of {@code model} without a designation in vocabulary {@code vocabulaire}. */
    public static List<Designatable> withoutDesignation(RegelSpraakModel model, String vocabulary) {
        return model.designatable().stream().filter(b -> model.designation(vocabulary, b).isEmpty()).toList();
    }

    /** The {@code vocabulaires} block of {@code /info}: per vocabulary its {@code naam}, {@code standaard},
     *  {@code taal} (when declared), whether it is {@code volledig}, and {@code withoutDesignation}, each declaration as
     *  {@code {soort, eigenaar?, naam}} by its canonical name. */
    public static ArrayNode info(RegelSpraakModel model) {
        ArrayNode sink = NF.arrayNode();
        for (RegelSpraakModel.Vocabulary v : model.vocabularies) {
            ObjectNode vn = sink.addObject();
            vn.put("name", v.name());
            vn.put("default", v.isDefault());
            if (v.language() != null) vn.put("language", v.language());
            List<Designatable> without = withoutDesignation(model, v.name());
            vn.put("complete", without.isEmpty());
            ArrayNode list = vn.putArray("withoutDesignation");
            for (Designatable b : without) {
                ObjectNode bn = list.addObject();
                bn.put("kind", b.kind().name());
                if (b.owner() != null) bn.put("owner", b.owner());
                bn.put("name", b.name());
            }
        }
        return sink;
    }
}
