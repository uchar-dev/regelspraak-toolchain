package dev.uchar.regelspraak.toolchain.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parser of the ground ({@link Ground}): each segment pattern of the werkbank's convention, a near-anchor, a slug
 * and a deviation before the lemma head as a note, the layers of a deviation unjudged, the old forms of a deviation
 * and a deviation off the grammar as notes, two annotations of a rule with "; " between them, and one annotation whose
 * "; " stays inside a segment. The texts of the parts after each other are always the annotations joined with "; ".
 */
class GroundTest {

    /** The parts of {@code annotations} as {@code KIND«text»{meta}}, glue as {@code «text»}. */
    private static List<String> parse(String... annotations) {
        Sentence.Builder b = Sentence.builder();
        Ground.write(b, List.of(annotations));
        Sentence s = b.build();
        assertEquals(String.join("; ", annotations), s.text());
        List<String> sink = new ArrayList<>();
        for (Part p : s.parts()) {
            String meta = p.meta().isEmpty() ? "" : p.meta().toString();
            sink.add(p.kind() == Part.Kind.TEXT ? "«" + p.text() + "»" : p.kind() + "«" + p.text() + "»" + meta);
        }
        return sink;
    }

    @Test
    void every_segment_pattern() {
        assertEquals(List.of(
                "STATUTE«BWBR0004045&artikel=20&lid=1&o=b»{bwb=BWBR0004045, article=20, paragraph=1, part=b}",
                "« · »",
                "STATUTE«BWBR0016001&artikel=1»{bwb=BWBR0016001, article=1}",
                "« · »",
                "STATUTE«BWBR0004045&artikel=7:668a&lid=2»{bwb=BWBR0004045, article=7:668a, paragraph=2}",
                "« · »",
                "NOTE«\"met ingang van de dag waarop de … uitkeringsduur is verstreken\": eindigt het recht (B12)»",
                "« · lemma's: »",
                "LEMMA«uitkeringsduur-verstreken»",
                "« · »",
                "LEMMA«maxdatum»",
                "« · deviatie wet/hb, volgt hb: »",
                "DEVIATION«de-26-wekeneis»{between=[wet, hb], follows=hb}",
                "« · deviatie binnen brm: »",
                "DEVIATION«de-25-wekendrempel»{between=[brm]}"),
                parse("BWBR0004045&artikel=20&lid=1&o=b · BWBR0016001&artikel=1 · BWBR0004045&artikel=7:668a&lid=2"
                        + " · \"met ingang van de dag waarop de … uitkeringsduur is verstreken\": eindigt het recht (B12)"
                        + " · lemma's: uitkeringsduur-verstreken · maxdatum · deviatie wet/hb, volgt hb: de-26-wekeneis"
                        + " · deviatie binnen brm: de-25-wekendrempel"));
    }

    @Test
    void a_deviation_names_its_layers_and_the_parser_does_not_judge_them() {
        // whether the followed layer is one of the pair is the werkbank's lint, not the parser's
        assertEquals(List.of(
                "« · deviatie wet/brm, volgt wet: »",
                "DEVIATION«de-herlevingsgrens-op-daglonen»{between=[wet, brm], follows=wet}",
                "« · deviatie hb/brm, volgt wet: »",
                "DEVIATION«een-laag-buiten-het-paar»{between=[hb, brm], follows=wet}",
                "« · deviatie binnen/hb, volgt hb: »",
                "DEVIATION«binnen-als-laag»{between=[binnen, hb], follows=hb}"),
                parse("lemma's: herleving · deviatie wet/brm, volgt wet: de-herlevingsgrens-op-daglonen"
                        + " · deviatie hb/brm, volgt wet: een-laag-buiten-het-paar"
                        + " · deviatie binnen/hb, volgt hb: binnen-als-laag").subList(2, 8));
    }

    @Test
    void the_old_forms_and_a_deviation_off_the_grammar_are_notes() {
        assertEquals(List.of(
                "« · »", "NOTE«afwijking: de-26-wekeneis»",
                "« · »", "NOTE«afwijking, niet gevolgd: de-25-wekendrempel»",
                "« · »", "NOTE«deviatie Wet/hb, volgt hb: een-hoofdletter»",
                "« · »", "NOTE«deviatie wet/hb: geen-gevolgde-laag»",
                "« · »", "NOTE«deviatie wet, volgt wet: geen-paar»",
                "« · »", "NOTE«deviatie binnen brm, volgt brm: binnen-met-gevolgde-laag»",
                "« · »", "NOTE«deviatie binnen brm: »"),
                parse("lemma's: wekeneis · afwijking: de-26-wekeneis · afwijking, niet gevolgd: de-25-wekendrempel"
                        + " · deviatie Wet/hb, volgt hb: een-hoofdletter · deviatie wet/hb: geen-gevolgde-laag"
                        + " · deviatie wet, volgt wet: geen-paar"
                        + " · deviatie binnen brm, volgt brm: binnen-met-gevolgde-laag · deviatie binnen brm: ")
                        .subList(2, 16));
    }

    @Test
    void a_near_anchor_and_a_slug_before_the_lemma_head_are_notes() {
        assertEquals(List.of(
                "NOTE«BWBR000404&artikel=20»",
                "« · »",
                "NOTE«BWBR0004045&artikel=20&o=b&lid=1»",
                "« · »",
                "NOTE«regelset-intern (CGM: geen element)»",
                "« · »",
                "NOTE«maxdatum»",
                "« · »",
                "NOTE«deviatie wet/hb, volgt hb: de-26-wekeneis»",
                "« · lemma's: »",
                "LEMMA«wekeneis»",
                "« · »",
                "NOTE«knoop 118»"),
                parse("BWBR000404&artikel=20 · BWBR0004045&artikel=20&o=b&lid=1 · regelset-intern (CGM: geen element)"
                        + " · maxdatum · deviatie wet/hb, volgt hb: de-26-wekeneis · lemma's: wekeneis · knoop 118"));
    }

    @Test
    void two_annotations_of_a_rule_have_their_separator_as_glue() {
        assertEquals(List.of(
                "STATUTE«BWBR0004045&artikel=21&lid=5»{bwb=BWBR0004045, article=21, paragraph=5}",
                "« · lemma's: »",
                "LEMMA«herlevingstermijn»",
                "«; »",
                "STATUTE«BWBR0016001&artikel=1»{bwb=BWBR0016001, article=1}"),
                parse("BWBR0004045&artikel=21&lid=5 · lemma's: herlevingstermijn", "BWBR0016001&artikel=1"));
    }

    @Test
    void a_semicolon_within_one_annotation_stays_in_a_segment() {
        // the parser never splits an annotation on "; ": rules and defaults carry their annotations as a list
        assertEquals(List.of(
                "STATUTE«BWBR0004045&artikel=20&lid=2»{bwb=BWBR0004045, article=20, paragraph=2}",
                "« · lemma's: »",
                "LEMMA«jeugdpremie»",
                "« · »",
                "NOTE«oude-premie; BWBR0004045&artikel=43»"),
                parse("BWBR0004045&artikel=20&lid=2 · lemma's: jeugdpremie · oude-premie; BWBR0004045&artikel=43"));
    }

    @Test
    void an_annotation_without_a_pattern_is_one_note_and_an_empty_segment_is_glue_only() {
        assertEquals(List.of("NOTE«Jutterswet art. 4 lid 1»"), parse("Jutterswet art. 4 lid 1"));
        assertEquals(List.of("NOTE«a»", "« ·  · »", "NOTE«b»"), parse("a ·  · b"));
    }

    @Test
    void a_group_of_the_ground_carries_no_meta_and_its_leaves_carry_only_theirs() {
        Sentence.Builder b = Sentence.builder();
        b.open(Part.Kind.GROUND, Map.of());
        Ground.write(b, List.of("BWBR0004045&artikel=43 · lemma's: maxdatum"));
        b.close(Part.Kind.GROUND);
        Part ground = b.build().parts().get(0);
        assertEquals(Part.Kind.GROUND, ground.kind());
        assertEquals(Map.of(), ground.meta());
        assertEquals("BWBR0004045&artikel=43 · lemma's: maxdatum", ground.text());
    }
}
