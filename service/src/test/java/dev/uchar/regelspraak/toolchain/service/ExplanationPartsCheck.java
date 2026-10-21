package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** For a test: the parts of an explanation held against its sentences, and the parts in a compact form. */
public final class ExplanationPartsCheck {

    private ExplanationPartsCheck() {}

    private static final Set<String> GROUPS = Set.of("CONDITION", "MEMBERS", "GROUND");

    /** Per sentence of {@code explanation} one entry of {@code explanationParts}, whose leaves, their texts after each
     *  other, are the sentence character for character; a leaf has a text and no parts, a group parts and no text, and
     *  the ground is the last part. Returns the kinds that occur. */
    public static Set<String> assertPartsAreTheSentences(JsonNode explanation, JsonNode explanationParts) {
        assertTrue(explanation.isArray(), "explanation is geen lijst: " + explanation);
        assertTrue(explanationParts.isArray(), "explanationParts is geen lijst: " + explanationParts);
        assertEquals(explanation.size(), explanationParts.size(), "een stukkenlijst per zin");
        Set<String> kinds = new TreeSet<>();
        for (int i = 0; i < explanation.size(); i++) {
            JsonNode parts = explanationParts.get(i);
            StringBuilder text = new StringBuilder();
            for (JsonNode p : parts) text.append(leaves(p, kinds));
            assertEquals(explanation.get(i).asText(), text.toString(), "zin " + i);
            for (int j = 0; j < parts.size() - 1; j++)
                assertFalse("GROUND".equals(parts.get(j).path("kind").asText()), "zin " + i + ": de grondslag is niet"
                        + " het laatste stuk");
        }
        return kinds;
    }

    private static String leaves(JsonNode p, Set<String> kinds) {
        String kind = p.path("kind").asText();
        kinds.add(kind);
        if (GROUPS.contains(kind)) {
            assertFalse(p.has("text"), "een groep zonder text: " + p);
            assertTrue(p.path("parts").isArray(), "een groep met parts: " + p);
            StringBuilder b = new StringBuilder();
            for (JsonNode c : p.get("parts")) b.append(leaves(c, kinds));
            return b.toString();
        }
        assertFalse(p.has("parts"), "een blad zonder parts: " + p);
        assertTrue(p.path("text").isTextual(), "een blad met text: " + p);
        return p.get("text").asText();
    }

    /** The parts as {@code KIND{meta}«text»} for a leaf, {@code «text»} for glue and {@code KIND{meta}[...]} for a group,
     *  separated by a space. */
    public static String render(JsonNode parts) {
        List<String> sink = new ArrayList<>();
        for (JsonNode p : parts) sink.add(renderPart(p));
        return String.join(" ", sink);
    }

    private static String renderPart(JsonNode p) {
        String kind = p.get("kind").asText();
        List<String> meta = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = p.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!Set.of("kind", "text", "parts").contains(e.getKey()))
                meta.add(e.getKey() + "=" + (e.getValue().isTextual() ? e.getValue().asText() : e.getValue()));
        }
        String m = meta.isEmpty() ? "" : "{" + String.join(", ", meta) + "}";
        if (GROUPS.contains(kind)) return kind + m + "[" + render(p.get("parts")) + "]";
        if ("TEXT".equals(kind)) return "«" + p.get("text").asText() + "»";
        return kind + m + "«" + p.get("text").asText() + "»";
    }
}
