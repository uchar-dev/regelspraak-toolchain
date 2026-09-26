package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.Geldigheid;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.model.Parameterset.WaardeInterval;
import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstDescriptor;
import dev.uchar.regelspraak.transpiler.kern.Dekking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The {@code /info} manifest builder. It REPRODUCES, byte-for-byte, the JSON shape that
 * {@code DienstHttp.infoBody()} serves (that method is package-private in the service module, so it cannot be
 * called across the module boundary; the shape is mirrored here instead and pinned by
 * {@code InfoParityTest} against the reference). Shape: {@code dienst} identity, one {@code entrypoints[]} entry
 * per entry point carrying its {@code pad}, {@code uitvoerbaar} (and uchar's {@code weigering} when not), its
 * {@code dekking} verdicts per form and its {@code markers}, the global
 * {@code overgeslagen} marker list, the descriptor {@code parameters} defaults verbatim, the loaded
 * {@code parametersets}, and the {@code periodeWaarschuwingen}.
 */
final class InfoBouwer {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private InfoBouwer() {}

    /** Mirror of {@code DienstHttp.infoBody()} over the shared kern. */
    static ObjectNode bouw(BeslisserKern kern) {
        DienstDescriptor descriptor = kern.descriptor();
        ObjectNode root = NF.objectNode();

        ObjectNode dienst = root.putObject("dienst");
        if (descriptor.dienst() != null) {
            dienst.put("naam", descriptor.dienst().naam());
            dienst.put("versie", descriptor.dienst().versie());
        }

        ArrayNode entrypoints = root.putArray("entrypoints");
        ArrayNode overgeslagen = NF.arrayNode();
        for (BeslisserKern.Entrypoint ep : kern.entrypoints().values()) {
            ObjectNode eNode = entrypoints.addObject();
            eNode.put("naam", ep.descriptor().naam());
            eNode.put("pad", ep.descriptor().pad());
            eNode.put("uitvoerbaar", ep.uitvoerbaar());
            if (!ep.uitvoerbaar()) eNode.put("weigering", ep.weigering());

            ArrayNode dekking = eNode.putArray("dekking");
            for (Dekking d : ep.dekking()) {
                ObjectNode dn = dekking.addObject();
                dn.put("soort", d.soort());
                dn.put("naam", d.naam());
                dn.put("verdict", d.verdict().name());
                if (d.reden() != null && !d.reden().isEmpty()) dn.put("reden", d.reden());
            }

            ArrayNode markers = eNode.putArray("markers");
            for (String m : ep.markers()) {
                markers.add(m);
                overgeslagen.add(m);
            }
        }
        root.set("entrypoints", entrypoints);
        root.set("overgeslagen", overgeslagen);

        ObjectNode parameters = root.putObject("parameters");
        Map<String, JsonNode> params = descriptor.parameters();
        if (params != null)
            for (var e : params.entrySet()) parameters.set(e.getKey(), e.getValue());

        ArrayNode parametersets = root.putArray("parametersets");
        for (Parameterset set : kern.parametersets()) {
            ObjectNode sn = parametersets.addObject();
            sn.put("naam", set.naam());
            ObjectNode waarden = sn.putObject("waarden");
            for (var e : set.waarden().entrySet()) {
                ArrayNode intervallen = waarden.putArray(e.getKey());
                for (WaardeInterval wi : e.getValue()) {
                    ObjectNode iv = intervallen.addObject();
                    Geldigheid g = wi.geldigheid();
                    if (g.vanaf() != null) iv.put("vanaf", g.vanaf().toString()); else iv.putNull("vanaf");
                    if (g.tot() != null) iv.put("tot", g.tot().toString()); else iv.putNull("tot");
                    iv.set("waarde", rendWaarde(wi.waarde()));
                }
            }
        }

        ArrayNode waarschuwingen = root.putArray("periodeWaarschuwingen");
        for (String w : kern.periodeWaarschuwingen()) waarschuwingen.add(w);

        return root;
    }

    /** WaardeCoder-symmetric exact JSON node (mirror of {@code DienstHttp.rendWaarde}). */
    private static JsonNode rendWaarde(Object v) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.numberNode(b);
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof String s) return NF.textNode(s);
        return NF.textNode(v.toString());
    }
}
