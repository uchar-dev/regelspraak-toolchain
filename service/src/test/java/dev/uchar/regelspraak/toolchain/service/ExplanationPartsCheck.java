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

    private static final Set<String> GROUPS = Set.of("CONDITION", "MEMBERS", "COMPUTATION", "VARIANT", "READING", "GROUND");

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

    /** Every {@code GROUND} of {@code explanationParts} names its decider in the terms of the IR {@code document}: a rule
     *  by {@code rule}, the name of its form in {@code rules.rules}; a default by {@code group}, the name of its group in
     *  {@code rules.prioritizedDefaults}, {@code pointer}, the default in that group, {@code position}, its place in its
     *  chain, 1-based, and {@code variant}, its name, where it has one. The {@code statuteAnnotations} the document
     *  gives that decider are the text of the ground after ". Grondslag: ", joined by "; ". Returns the number of
     *  grounds. */
    public static int assertGroundsNameTheirDeciders(JsonNode explanationParts, JsonNode document) {
        int grounds = 0;
        for (int i = 0; i < explanationParts.size(); i++) {
            JsonNode parts = explanationParts.get(i);
            JsonNode last = parts.get(parts.size() - 1);
            if (!"GROUND".equals(last.path("kind").asText())) continue;
            grounds++;
            Set<String> keys = new TreeSet<>();
            last.fieldNames().forEachRemaining(keys::add);
            keys.removeAll(Set.of("kind", "parts"));
            JsonNode decider;
            if (last.has("rule")) {
                assertEquals(Set.of("rule"), keys, "zin " + i + ": een regel draagt alleen rule");
                decider = named(document.at("/rules/rules"), last.get("rule").asText(), "regel", i);
            } else {
                assertTrue(keys.equals(Set.of("group", "pointer", "position"))
                        || keys.equals(Set.of("group", "pointer", "position", "variant")),
                        "zin " + i + ": de grondslag noemt geen regel en geen default: " + keys);
                JsonNode group = named(document.at("/rules/prioritizedDefaults"), last.get("group").asText(),
                        "defaults-groep", i);
                String pointer = last.get("pointer").asText();
                decider = group.at(pointer);
                assertTrue(decider.isObject(), "zin " + i + ": " + pointer + " wijst in de groep geen default aan");
                assertTrue(pointer.matches("(/defaults/[0-9]+/exceptions/members/[0-9]+)*/defaults/[0-9]+"),
                        "zin " + i + ": " + pointer + " is geen pad van een default in zijn groep");
                int index = Integer.parseInt(pointer.substring(pointer.lastIndexOf('/') + 1));
                assertEquals(index + 1, last.get("position").asInt(), "zin " + i + ": de plaats in de keten");
                assertEquals(decider.path("name").asText(null), last.path("variant").asText(null),
                        "zin " + i + ": de naam van de variant");
            }
            List<String> annotations = new ArrayList<>();
            for (JsonNode a : decider.path("statuteAnnotations")) annotations.add(a.asText());
            assertFalse(annotations.isEmpty(), "zin " + i + ": de beslisser heeft geen statuteAnnotations");
            StringBuilder text = new StringBuilder();
            for (JsonNode c : last.get("parts")) text.append(leaves(c, new TreeSet<>()));
            assertEquals(". Grondslag: " + String.join("; ", annotations), text.toString(), "zin " + i);
        }
        return grounds;
    }

    private static JsonNode named(JsonNode list, String name, String what, int sentence) {
        JsonNode found = null;
        for (JsonNode n : list) {
            if (!name.equals(n.path("name").asText(null))) continue;
            assertTrue(found == null, "zin " + sentence + ": twee keer " + what + " '" + name + "'");
            found = n;
        }
        assertTrue(found != null, "zin " + sentence + ": geen " + what + " '" + name + "' in het document");
        return found;
    }

    /** The sentence of {@code parts} without every part a consumer may leave out with its glue (card
     *  explanation-part-groups): every {@code MEMBERS}, every {@code VARIANT} with {@code decided: false} and every
     *  {@code READING}. */
    public static String withoutOmissibleParts(JsonNode parts) {
        StringBuilder b = new StringBuilder();
        for (JsonNode p : parts) {
            String kind = p.path("kind").asText();
            if (kind.equals("MEMBERS") || kind.equals("READING")
                    || kind.equals("VARIANT") && p.path("decided").isBoolean() && !p.get("decided").asBoolean()) continue;
            b.append(GROUPS.contains(kind) ? withoutOmissibleParts(p.get("parts")) : p.get("text").asText());
        }
        return b.toString();
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
