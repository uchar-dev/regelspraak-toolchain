package dev.uchar.regelspraak.toolchain.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The builder of a sentence of the explanation ({@link Sentence.Builder}): the text of the sentence and of each group is
 * the concatenation of its leaves, consecutive glue is one {@link Part.Kind#TEXT}, and a group that is closed wrongly,
 * left open, or that a part claims to have another text, fails loudly.
 */
class SentenceTest {

    @Test
    void the_text_is_the_concatenation_of_the_leaves_and_glue_merges() {
        Sentence s = Sentence.builder().leaf(Part.Kind.SUBJECT, "J1").text(" is ").text("niet ")
                .leaf(Part.Kind.NAME, "ervaren", Part.meta("declaration", "KENMERK", "owner", "Jutter",
                        "canonical", "ervaren"))
                .text(", ").leaf(Part.Kind.CONNECTIVE, "want").text("").text(" ")
                .open(Part.Kind.CONDITION, Part.meta("holds", false))
                .leaf(Part.Kind.QUOTE, "zijn leeftijd").text(" (").leaf(Part.Kind.VALUE, "16").text(") is groter dan ")
                .leaf(Part.Kind.VALUE, "17")
                .close(Part.Kind.CONDITION)
                .build();
        assertEquals("J1 is niet ervaren, want zijn leeftijd (16) is groter dan 17", s.text());
        assertEquals(List.of(Part.Kind.SUBJECT, Part.Kind.TEXT, Part.Kind.NAME, Part.Kind.TEXT, Part.Kind.CONNECTIVE,
                Part.Kind.TEXT, Part.Kind.CONDITION), s.parts().stream().map(Part::kind).toList());
        assertEquals(" is niet ", s.parts().get(1).text());
        Part condition = s.parts().get(6);
        assertEquals("zijn leeftijd (16) is groter dan 17", condition.text());
        assertEquals(Map.of("holds", false), condition.meta());
        assertEquals(5, condition.parts().size());
    }

    @Test
    void a_null_owner_is_kept() {
        Part p = Sentence.builder().leaf(Part.Kind.NAME, "minimumleeftijd",
                Part.meta("declaration", "PARAMETER", "owner", null, "canonical", "minimumleeftijd")).build().parts().get(0);
        assertEquals("{declaration=PARAMETER, owner=null, canonical=minimumleeftijd}", p.meta().toString());
    }

    @Test
    void a_wrong_close_an_open_group_and_glue_as_a_leaf_fail() {
        assertThrows(IllegalStateException.class, () -> Sentence.builder().open(Part.Kind.CONDITION, Map.of())
                .close(Part.Kind.MEMBERS));
        assertThrows(IllegalStateException.class, () -> Sentence.builder().close(Part.Kind.GROUND));
        assertThrows(IllegalStateException.class, () -> Sentence.builder().open(Part.Kind.GROUND, Map.of()).build());
        assertThrows(IllegalArgumentException.class, () -> Sentence.builder().leaf(Part.Kind.TEXT, "x"));
        assertThrows(IllegalArgumentException.class, () -> Sentence.builder().open(Part.Kind.NAME, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Sentence.builder().leaf(Part.Kind.GROUND, "x"));
    }

    @Test
    void a_group_whose_text_is_not_that_of_its_parts_or_a_leaf_with_parts_is_refused() {
        List<Part> leaves = List.of(Part.leaf(Part.Kind.TEXT, "a", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Part(Part.Kind.CONDITION, "b", Map.of(), leaves));
        assertThrows(IllegalArgumentException.class, () -> new Part(Part.Kind.NOTE, "a", Map.of(), leaves));
        assertThrows(IllegalArgumentException.class, () -> Part.leaf(Part.Kind.VALUE, "1", Map.of("n", 1L)));
    }
}
