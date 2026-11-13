package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An object creation and a fact creation get a sentence on their subject, as an ordinary rule does: what the creation
 * made, by the rule, unconditionally or why it held, and for an object creation the value each waardetoekenning gave
 * the made object; where it made nothing, why. On uchar's goldens jutterswet-premiepot (an object creation and a fact
 * creation on the objects it made) and jutterswet-dubbelverbinding (two fact creations with a condition, the first with
 * a validity window).
 */
class ExplanationCreationTest {

    private static final Path GOLDEN = Path.of("../uchar/corpus/golden");

    @Test
    void an_object_creation_and_a_fact_creation_say_what_they_made() throws IOException {
        List<ScenarioRunner.Explained> u = golden("jutterswet-premiepot");
        String pot = " volgens de regel 'premiepot' (onvoorwaardelijk): met als pot het aantal havenjutters van de Haven"
                + " maal de premie per havenjutter";
        assertEquals(List.of(
                "H1 heeft een Premiepot «\"premiepot\"»(H1)#1" + pot + " (100)",
                "H2 heeft een Premiepot «\"premiepot\"»(H2)#1" + pot + " (50)",
                "H3 heeft een Premiepot «\"premiepot\"»(H3)#1" + pot + " (0)",
                "«\"premiepot\"»(H1)#1 heeft J1 en J2 als premiejutter volgens de regel 'premiejutters'"
                        + " (onvoorwaardelijk)",
                "«\"premiepot\"»(H2)#1 heeft J3 als premiejutter volgens de regel 'premiejutters' (onvoorwaardelijk)"),
                creations(u.get(0), "'premiepot'", "'premiejutters'"));
    }

    /** The parts of an object creation: the made object is a SUBJECT, the created type a NAME with the keys of its
     *  declaration, the rule a RULE, and the waardetoekenningen a COMPUTATION, each value as the made object read it. */
    @Test
    void an_object_creation_has_the_parts_of_a_computed_value() throws IOException {
        Sentence h1 = golden("jutterswet-premiepot").get(0).sentences().stream()
                .filter(s -> s.text().startsWith("H1 heeft een Premiepot")).findFirst().orElseThrow();
        assertEquals(List.of(Part.Kind.SUBJECT, Part.Kind.TEXT, Part.Kind.NAME, Part.Kind.TEXT, Part.Kind.SUBJECT,
                        Part.Kind.TEXT, Part.Kind.RULE, Part.Kind.TEXT, Part.Kind.COMPUTATION),
                h1.parts().stream().map(Part::kind).toList(), h1.text());
        Map<String, Object> type = h1.parts().get(2).meta();
        assertEquals("OBJECTTYPE", type.get("declaration"), h1.toString());
        assertEquals("Premiepot", type.get("canonical"), h1.toString());
        Part computation = h1.parts().get(8);
        assertEquals(List.of(Part.Kind.TEXT, Part.Kind.NAME, Part.Kind.TEXT, Part.Kind.QUOTE, Part.Kind.READING),
                computation.parts().stream().map(Part::kind).toList(), computation.toString());
        assertEquals("«\"premiepot\"»(H1)#1", computation.parts().get(1).meta().get("object"), computation.toString());
    }

    @Test
    void a_fact_creation_says_why_it_held_or_did_not() throws IOException {
        List<ScenarioRunner.Explained> u = golden("jutterswet-dubbelverbinding");
        String winter = "'winterberging door de haven'", keur = "'keurberging door de haven'";
        assertEquals(List.of(
                "H1 heeft S1 en S2 als winterschuit volgens de regel " + winter + ", omdat het onderwerp veilig is",
                "H1 heeft S1 en S2 als keurschuit volgens de regel " + keur + ", omdat het kadegeld van de Haven (40) is"
                        + " groter dan 30",
                "H2 heeft S3 als winterschuit volgens de regel " + winter + ", omdat het onderwerp veilig is",
                "H2 heeft geen keurschuit volgens de regel " + keur + ", want niet geldt: het kadegeld van de Haven"
                        + " (25) is groter dan 30",
                "H3 heeft geen winterschuit volgens de regel " + winter + ", want niet geldt: het onderwerp veilig is"),
                creations(u.get(0), winter, keur));
        assertEquals(List.of(
                "H1 heeft geen winterschuit volgens de regel " + winter + ", want de regel valt op de rekendatum buiten"
                        + " haar geldigheid",
                "H2 heeft geen winterschuit volgens de regel " + winter + ", want de regel valt op de rekendatum buiten"
                        + " haar geldigheid",
                "H2 heeft geen keurschuit volgens de regel " + keur + ", want niet geldt: het kadegeld van de Haven"
                        + " (25) is groter dan 30",
                "H3 heeft geen winterschuit volgens de regel " + winter + ", want de regel valt op de rekendatum buiten"
                        + " haar geldigheid"),
                creations(u.get(1), winter, keur));
    }

    /** The sentences of {@code e} that name one of {@code rules}. */
    private static List<String> creations(ScenarioRunner.Explained e, String... rules) {
        return e.explanation().stream().filter(t -> java.util.Arrays.stream(rules).anyMatch(t::contains)).toList();
    }

    private static List<ScenarioRunner.Explained> golden(String name) throws IOException {
        Path golden = GOLDEN.resolve(name);
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(
                Files.readString(golden.resolve("source.rgs")) + "\n" + Files.readString(golden.resolve("scenario.rgs"))));
        for (ScenarioRunner.Explained e : u) {
            assertEquals(Status.PASS, e.result().status(), () -> e.result().diagnostic());
            assertNull(e.explanationError(), e::explanationError);
        }
        return u;
    }
}
