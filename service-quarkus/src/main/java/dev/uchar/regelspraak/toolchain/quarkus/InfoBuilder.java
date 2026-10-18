package dev.uchar.regelspraak.toolchain.quarkus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.ir.Validity;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.ParameterSet.ValueInterval;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceDescriptor;
import dev.uchar.regelspraak.toolchain.service.Completeness;
import dev.uchar.regelspraak.transpiler.core.Coverage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The {@code /info} manifest builder. It REPRODUCES, byte-for-byte, the JSON shape that
 * {@code ServiceHttp.infoBody()} serves (that method is package-private in the service module, so it cannot be
 * called across the module boundary; the shape is mirrored here instead and pinned by
 * {@code InfoParityTest} against the reference). Shape: {@code service} identity, one {@code entrypoints[]} entry
 * per entry point carrying its {@code pad}, {@code uitvoerbaar} (and uchar's {@code weigering} when not), its
 * {@code dekking} verdicts per form and its {@code markers}, the global
 * {@code skipped} marker list, the descriptor {@code parameters} defaults verbatim, the loaded
 * {@code parameterSets}, and the {@code periodWarnings}; an entry point's default {@code vocabulaire} and, for a
 * model with vocabularies, the {@code vocabulaires} block ({@link Completeness}).
 */
final class InfoBuilder {

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    private InfoBuilder() {}

    /** Mirror of {@code ServiceHttp.infoBody()} over the shared kern. */
    static ObjectNode assemble(DecisionCore core) {
        ServiceDescriptor descriptor = core.descriptor();
        ObjectNode root = NF.objectNode();

        ObjectNode service = root.putObject("dienst");
        if (descriptor.service() != null) {
            service.put("naam", descriptor.service().name());
            service.put("versie", descriptor.service().version());
        }

        ArrayNode entrypoints = root.putArray("entrypoints");
        ArrayNode skipped = NF.arrayNode();
        for (DecisionCore.Entrypoint ep : core.entrypoints().values()) {
            ObjectNode eNode = entrypoints.addObject();
            eNode.put("naam", ep.descriptor().name());
            eNode.put("pad", ep.descriptor().path());
            if (ep.descriptor().vocabulary() != null) eNode.put("vocabulaire", ep.descriptor().vocabulary());
            eNode.put("uitvoerbaar", ep.executable());
            if (!ep.executable()) eNode.put("weigering", ep.refusal());

            ArrayNode coverage = eNode.putArray("dekking");
            for (Coverage d : ep.coverage()) {
                ObjectNode dn = coverage.addObject();
                dn.put("soort", d.category());
                dn.put("naam", d.naam());
                dn.put("verdict", d.verdict().name());
                if (d.reason() != null && !d.reason().isEmpty()) dn.put("reden", d.reason());
            }

            ArrayNode markers = eNode.putArray("markers");
            for (String m : ep.markers()) {
                markers.add(m);
                skipped.add(m);
            }
        }
        root.set("entrypoints", entrypoints);
        root.set("overgeslagen", skipped);

        ObjectNode parameters = root.putObject("parameters");
        Map<String, JsonNode> params = descriptor.parameters();
        if (params != null)
            for (var e : params.entrySet()) parameters.set(e.getKey(), e.getValue());

        ArrayNode parameterSets = root.putArray("parametersets");
        for (ParameterSet set : core.parameterSets()) {
            ObjectNode sn = parameterSets.addObject();
            sn.put("naam", set.naam());
            ObjectNode values = sn.putObject("waarden");
            for (var e : set.waarden().entrySet()) {
                ArrayNode intervals = values.putArray(e.getKey());
                for (ValueInterval wi : e.getValue()) {
                    ObjectNode iv = intervals.addObject();
                    Validity g = wi.validity();
                    if (g.from() != null) iv.put("vanaf", g.from().toString()); else iv.putNull("vanaf");
                    if (g.until() != null) iv.put("tot", g.until().toString()); else iv.putNull("tot");
                    iv.set("waarde", renderValue(wi.value()));
                }
            }
        }

        ArrayNode warnings = root.putArray("periodeWaarschuwingen");
        for (String w : core.periodWarnings()) warnings.add(w);

        if (!core.model().vocabularies.isEmpty()) root.set("vocabulaires", Completeness.info(core.model()));

        return root;
    }

    /** ValueCoder-symmetric exact JSON node (mirror of {@code ServiceHttp.renderValue}). */
    private static JsonNode renderValue(Object v) {
        if (v == null) return NF.nullNode();
        if (v instanceof BigDecimal b) return NF.numberNode(b);
        if (v instanceof Boolean b) return NF.booleanNode(b);
        if (v instanceof LocalDate d) return NF.textNode(d.toString());
        if (v instanceof LocalDateTime d) return NF.textNode(d.toString());
        if (v instanceof String s) return NF.textNode(s);
        return NF.textNode(v.toString());
    }
}
