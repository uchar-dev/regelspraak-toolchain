package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@link Part.Kind#VALUE} that writes an absence marker (uchar's RUNTIME-SEMANTICS ch. 9.5) carries {@code marker},
 * its name in the declared spelling, on uchar's golden jutterswet-afwezigheidsmarkers: in the head of a sentence, as
 * a value a condition or a computation read, and as the literal of a comparison. The leaf of a payload carries none,
 * and neither does the leaf of an enumeration value spelled as a marker: the text alone does not tell them apart. A
 * timeline is one leaf with all its periods and carries none either.
 */
class ExplanationMarkerPartsTest {

    private static final Path GOLDEN = Path.of("../uchar/corpus/golden/jutterswet-afwezigheidsmarkers");
    private static final Set<String> MARKERS = Set.of("onbekend", "niet van toepassing");

    /** One VALUE leaf, with the sentence it stands in. */
    private record Leaf(String sentence, Part part) {
        Object marker() { return part.meta().get("marker"); }
    }

    @Test
    void every_value_that_writes_a_marker_carries_its_name_and_no_other_value_does() throws IOException {
        List<Leaf> leaves = leaves(golden());
        int markers = 0;
        for (Leaf l : leaves) {
            boolean timeline = Boolean.TRUE.equals(l.part().meta().get("timeline"));
            if (!timeline && MARKERS.contains(l.part().text())) {
                assertEquals(l.part().text(), l.marker(), l.sentence());
                markers++;
            } else {
                assertFalse(l.part().meta().containsKey("marker"), l.part() + " in " + l.sentence());
            }
        }
        assertTrue(markers > 0, "the golden writes no marker as a VALUE");
    }

    @Test
    void the_head_a_reading_and_a_literal_carry_the_marker() throws IOException {
        List<Leaf> leaves = leaves(golden());
        // the head of a sentence: the value a rule wrote
        assertEquals("onbekend", first(leaves, "V1 heeft als taxatie onbekend volgens", "onbekend").marker());
        assertEquals("niet van toepassing",
                first(leaves, "V1 heeft als taxatie niet van toepassing volgens", "niet van toepassing").marker());
        // a value a condition read, between brackets
        Leaf read = first(leaves, "zijn taxatie (niet van toepassing) een waarde heeft", "niet van toepassing");
        assertEquals("niet van toepassing", read.marker());
        // the literal of a comparison
        Leaf literal = first(leaves, "is gelijk aan onbekend", "onbekend");
        assertEquals("onbekend", literal.marker());
        // the payload of a value of the attribute
        Leaf payload = first(leaves, "V1 heeft als taxatie 300,00 volgens", "300,00");
        assertNull(payload.marker(), payload.part().toString());
    }

    @Test
    void an_enumeration_value_spelled_as_a_marker_carries_none() {
        String source = """
                Domein Taxatiestand is van het type Enumeratie
                    'onbekend'
                    'getaxeerd'

                Objecttype de Vondst (mv: Vondsten)
                    de stand Taxatiestand;
                    is twijfelachtig kenmerk (bijvoeglijk);

                Regel stand onbekend
                    geldig altijd
                        De stand van een Vondst moet gesteld worden op 'onbekend'.

                Regel twijfel
                    geldig altijd
                        Een Vondst is twijfelachtig indien zijn stand gelijk is aan 'onbekend'.

                Scenario stand
                Gegeven is de volgende situatie:
                een Vondst V1
                Met als verwacht resultaat:
                - de stand van V1 is 'onbekend'
                - V1 is twijfelachtig
                """;
        List<Leaf> leaves = leaves(run(source));
        List<Leaf> onbekend = leaves.stream().filter(l -> l.part().text().equals("onbekend")).toList();
        assertTrue(onbekend.size() >= 2, leaves.toString());
        for (Leaf l : onbekend) assertFalse(l.part().meta().containsKey("marker"), l.part() + " in " + l.sentence());
    }

    private static List<ScenarioRunner.Explained> golden() throws IOException {
        return run(Files.readString(GOLDEN.resolve("source.rgs")) + "\n" + Files.readString(GOLDEN.resolve("scenario.rgs")));
    }

    private static List<ScenarioRunner.Explained> run(String document) {
        var bundle = ScenarioResolver.resolve(document);
        assertTrue(bundle.isOk(), () -> bundle.model().errors().toString());
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(bundle);
        for (ScenarioRunner.Explained e : u) {
            assertEquals(Status.PASS, e.result().status(), () -> e.result().diagnostic() + " " + e.result().expectations());
            assertNull(e.explanationError(), e.explanationError());
        }
        return u;
    }

    private static List<Leaf> leaves(List<ScenarioRunner.Explained> runs) {
        List<Leaf> sink = new ArrayList<>();
        for (ScenarioRunner.Explained e : runs)
            for (Sentence s : e.sentences())
                for (Part p : s.parts()) collect(s.text(), p, sink);
        return sink;
    }

    private static void collect(String sentence, Part p, List<Leaf> sink) {
        if (p.kind() == Part.Kind.VALUE) sink.add(new Leaf(sentence, p));
        for (Part q : p.parts()) collect(sentence, q, sink);
    }

    /** The first VALUE with text {@code text} in a sentence that contains {@code fragment}. */
    private static Leaf first(List<Leaf> leaves, String fragment, String text) {
        for (Leaf l : leaves)
            if (l.sentence().contains(fragment) && l.part().text().equals(text)) return l;
        throw new AssertionError("no VALUE '" + text + "' in a sentence with '" + fragment + "': "
                + leaves.stream().map(Leaf::sentence).distinct().toList());
    }
}
