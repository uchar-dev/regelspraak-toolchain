package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;

import java.util.List;

/**
 * Per vocabulary of a model, whether it is complete and which declarations have no designation in it: the
 * declarations an answer in that vocabulary names by their canonical name. uchar's WF-119 makes only the default
 * vocabulary complete, so for every other one a consumer reads the fallback here instead of guessing it. The
 * declarations are those uchar can designate ({@link RegelSpraakModel#benoembaar}), in its order.
 */
public final class Volledigheid {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private Volledigheid() {}

    /** The declarations of {@code model} without a designation in vocabulary {@code vocabulaire}. */
    public static List<Benoembaar> zonderBenaming(RegelSpraakModel model, String vocabulaire) {
        return model.benoembaar().stream().filter(b -> model.benaming(vocabulaire, b).isEmpty()).toList();
    }

    /** The {@code vocabulaires} block of {@code /info}: per vocabulary its {@code naam}, {@code standaard},
     *  {@code taal} (when declared), whether it is {@code volledig}, and {@code zonderBenaming}, each declaration as
     *  {@code {soort, eigenaar?, naam}} by its canonical name. */
    public static ArrayNode info(RegelSpraakModel model) {
        ArrayNode uit = NF.arrayNode();
        for (RegelSpraakModel.Vocabulaire v : model.vocabulaires) {
            ObjectNode vn = uit.addObject();
            vn.put("naam", v.naam());
            vn.put("standaard", v.standaard());
            if (v.taal() != null) vn.put("taal", v.taal());
            List<Benoembaar> zonder = zonderBenaming(model, v.naam());
            vn.put("volledig", zonder.isEmpty());
            ArrayNode lijst = vn.putArray("zonderBenaming");
            for (Benoembaar b : zonder) {
                ObjectNode bn = lijst.addObject();
                bn.put("soort", b.soort().name());
                if (b.eigenaar() != null) bn.put("eigenaar", b.eigenaar());
                bn.put("naam", b.naam());
            }
        }
        return uit;
    }
}
