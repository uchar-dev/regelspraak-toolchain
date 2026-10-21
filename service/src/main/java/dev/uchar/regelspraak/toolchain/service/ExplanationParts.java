package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.engine.Part;
import dev.uchar.regelspraak.toolchain.engine.Sentence;

import java.util.List;
import java.util.Map;

/**
 * The one writer of {@code explanationParts}: per sentence of {@code explanation}, in the same order, the array of its
 * parts at the root ({@link Sentence#parts()}). A part is {@code {"kind", "text"}} for a leaf and {@code {"kind",
 * "parts"}} for a group, with its meta between them, so the texts of the leaves after each other are the sentence. The
 * decision service and both routes of the playground write the field through this class.
 */
public final class ExplanationParts {

    private ExplanationParts() {}

    private static final JsonNodeFactory NF = JsonNodeFactory.withExactBigDecimals(true);

    public static ArrayNode json(List<Sentence> sentences) {
        ArrayNode sink = NF.arrayNode();
        for (Sentence s : sentences) {
            ArrayNode parts = sink.addArray();
            for (Part p : s.parts()) parts.add(json(p));
        }
        return sink;
    }

    private static ObjectNode json(Part p) {
        ObjectNode n = NF.objectNode();
        n.put("kind", p.kind().name());
        if (!p.kind().isGroup()) n.put("text", p.text());
        for (Map.Entry<String, Object> e : p.meta().entrySet()) {
            Object v = e.getValue();
            if (v == null) n.putNull(e.getKey());
            else if (v instanceof String t) n.put(e.getKey(), t);
            else if (v instanceof Boolean b) n.put(e.getKey(), b);
            else if (v instanceof Integer i) n.put(e.getKey(), i);
            else throw new IllegalStateException("het stuk " + p.kind() + " draagt bij " + e.getKey() + " een "
                    + v.getClass().getSimpleName());
        }
        if (p.kind().isGroup()) {
            ArrayNode parts = n.putArray("parts");
            for (Part c : p.parts()) parts.add(json(c));
        }
        return n;
    }
}
