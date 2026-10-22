package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card explanation-ground-source: the {@code GROUND} of a sentence names the decider whose {@code // Bron:}
 * annotations it carries, in the terms of the IR, so a reader finds that decider and its annotations in the document
 * without reading the text: a rule by {@code rule}, the name of its form; a default by {@code group}, the name of its
 * defaults group, {@code pointer}, the default in that group, {@code position}, its place in its chain as the sentence
 * counts it, and {@code variant}, its name where it has one. The sentence and the text of its parts stay as they
 * were. Both routes of the service, from sources and from an IR document, and a document whose two exceptions lost
 * their names ({@code Default.name} carries no semantics, IR-SPEC ch. 6.1), so that the sentence names them by their
 * place.
 *
 * <p>The model: 'gevestigd', a rule of two versions with two {@code // Bron:} lines, of which the first version falls
 * outside the rekendatum; the defaults group 'premie' with the base 'premie', the exception 'jeugdpremie' under 18 and
 * the exception 'kinderpremie' on 'jeugdpremie' under 12, nested in the exception group of 'jeugdpremie', each with
 * its own annotation; 'strandpremie', a plain rule on the subtype Strandjutter that replaces the base of the group
 * for a Strandjutter (RUNTIME-SEMANTICS ch. 7.10), so a rule decides in a defaults sentence; and the meldingsdatum,
 * which may be given, so that the opgave marker makes a group and a rule that carry no annotation, also where the
 * rule that computes the meldingsdatum carries one: their sentences have no ground. Since card
 * explanation-computed-attributes that rule has a sentence of its own, on the berekende meldingsdatum it writes, with
 * its ground.
 */
class ExplanationGroundSourceServiceTest {

    private static final String SOURCE = """
            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal);
                de premie Numeriek (getal met 2 decimalen);
                de vinddag Datum in dagen;
                de meldingsdatum Datum in dagen (mag opgegeven worden);
                is ingezetene kenmerk (bijvoeglijk);
                is gevestigd kenmerk (bijvoeglijk);

            Objecttype de Strandjutter is een Jutter (mv: Strandjutters)
                is vergund kenmerk (bijvoeglijk);

            // Bron: BWBR0004045&artikel=2&lid=1 · lemma's: gevestigde-jutter
            // Bron: BWBR0004045&artikel=2&lid=3 · "woont aan de kust; ook op een eiland"
            Regel gevestigd
                geldig vanaf 2020 t/m 2024
                    Een Jutter is gevestigd
                    indien hij ingezetene is.
                geldig vanaf 2025
                    Een Jutter is gevestigd
                    indien hij ingezetene is.

            // Bron: BWBR0004045&artikel=5&lid=1
            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 100,00.

            // Bron: BWBR0004045&artikel=5&lid=2 · lemma's: jeugdpremie
            Uitzondering op premie: jeugdpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 50,00
                    indien zijn leeftijd kleiner is dan 18.

            // Bron: BWBR0004045&artikel=5&lid=3
            Uitzondering op jeugdpremie: kinderpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 25,00
                    indien zijn leeftijd kleiner is dan 12.

            // Bron: BWBR0004045&artikel=7
            Regel melding
                geldig altijd
                    De meldingsdatum van een Jutter moet berekend worden als de vinddag van de Jutter plus 3 dagen.

            // Bron: BWBR0004045&artikel=8
            Regel strandpremie
                geldig altijd
                    De premie van een Strandjutter moet gesteld worden op 70,00.
            """;

    private static final String DESCRIPTOR = """
            { "service": {"name":"Herkomst van de grondslag","version":"1"},
              "entrypoints": [ { "name":"jutters","path":"jutters",
                "scope": {"ruleNames": ["gevestigd", "premie", "strandpremie", "melding",
                                        "opgave boven berekening meldingsdatum", "afwijking opgave meldingsdatum",
                                        "controle opgave meldingsdatum"]},
                "input": ["Jutter", "Strandjutter"],
                "output": ["Jutter:gevestigd", "Jutter.premie", "Jutter.meldingsdatum"] } ],
              "parameters": {} }""";

    private static final String REQUEST = """
            { "asOfDate": "2026-01-01",
              "instances": [
                { "type": "Jutter", "id": "J1", "characteristics": { "ingezetene": true },
                  "attributes": { "leeftijd": 30, "vinddag": "2026-03-10", "opgegeven meldingsdatum": "2026-03-11" } },
                { "type": "Jutter", "id": "J2", "attributes": { "leeftijd": 16, "vinddag": "2026-03-10" } },
                { "type": "Jutter", "id": "J3", "attributes": { "leeftijd": 8, "vinddag": "2026-03-10" } },
                { "type": "Strandjutter", "id": "S1", "attributes": { "leeftijd": 30, "vinddag": "2026-03-10" } } ] }""";

    private static final String GEVESTIGD = ". Grondslag: BWBR0004045&artikel=2&lid=1 · lemma's: gevestigde-jutter;"
            + " BWBR0004045&artikel=2&lid=3 · \"woont aan de kust; ook op een eiland\"";
    private static final String KINDER = "'kinderpremie' (uitzondering op 'jeugdpremie' op 'premie')";
    private static final String JEUGD = "'jeugdpremie'";

    private static final List<String> EXPECTED = List.of(
            "J1 is gevestigd, maar de regel 'gevestigd' gold niet, want de regel valt op de rekendatum buiten haar"
                    + " geldigheid" + GEVESTIGD,
            "J1 is gevestigd, omdat het onderwerp ingezetene is" + GEVESTIGD,
            "J1 heeft afwijkende opgave meldingsdatum, omdat zijn opgegeven meldingsdatum (11-03-2026) gevuld is en"
                    + " zijn berekende meldingsdatum (13-03-2026) gevuld is en zijn opgegeven meldingsdatum (11-03-2026)"
                    + " is ongelijk aan zijn berekende meldingsdatum (13-03-2026)",
            "J1 heeft als premie 100,00 volgens de regel 'premie' (onvoorwaardelijk): 100,00; " + KINDER + " gold"
                    + " niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18; " + JEUGD + " gold niet, want niet"
                    + " geldt: zijn leeftijd (30) is kleiner dan 18. Grondslag: BWBR0004045&artikel=5&lid=1",
            "J1 heeft als meldingsdatum 11-03-2026 volgens 'opgegeven meldingsdatum' (onvoorwaardelijk): zijn"
                    + " opgegeven meldingsdatum (11-03-2026); verdrongen: 'berekende meldingsdatum'",
            melding("J1"),
            "J2 is niet gevestigd, want de regel valt op de rekendatum buiten haar geldigheid" + GEVESTIGD,
            "J2 is niet gevestigd, want niet geldt: het onderwerp ingezetene is" + GEVESTIGD,
            notDiffering("J2"),
            "J2 heeft als premie 50,00 volgens " + JEUGD + ": 50,00, omdat zijn leeftijd (16) is kleiner dan 18; "
                    + KINDER + " gold niet, want niet geldt: zijn leeftijd (16) is kleiner dan 12; verdrongen: de regel"
                    + " 'premie'. Grondslag: BWBR0004045&artikel=5&lid=2 · lemma's: jeugdpremie",
            "J2 heeft als meldingsdatum 13-03-2026 volgens 'berekende meldingsdatum' (onvoorwaardelijk): zijn"
                    + " berekende meldingsdatum (13-03-2026); 'opgegeven meldingsdatum' gaf geen waarde",
            melding("J2"),
            "J3 is niet gevestigd, want de regel valt op de rekendatum buiten haar geldigheid" + GEVESTIGD,
            "J3 is niet gevestigd, want niet geldt: het onderwerp ingezetene is" + GEVESTIGD,
            notDiffering("J3"),
            "J3 heeft als premie 25,00 volgens " + KINDER + ": 25,00, omdat zijn leeftijd (8) is kleiner dan 18 en"
                    + " zijn leeftijd (8) is kleiner dan 12; verdrongen: " + JEUGD + ", de regel 'premie'. Grondslag:"
                    + " BWBR0004045&artikel=5&lid=3",
            "J3 heeft als meldingsdatum 13-03-2026 volgens 'berekende meldingsdatum' (onvoorwaardelijk): zijn"
                    + " berekende meldingsdatum (13-03-2026); 'opgegeven meldingsdatum' gaf geen waarde",
            melding("J3"),
            "S1 is niet gevestigd, want de regel valt op de rekendatum buiten haar geldigheid" + GEVESTIGD,
            "S1 is niet gevestigd, want niet geldt: het onderwerp ingezetene is" + GEVESTIGD,
            notDiffering("S1"),
            "S1 heeft als premie 70,00 volgens de regel 'strandpremie' (onvoorwaardelijk): 70,00; " + KINDER
                    + " gold niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18; " + JEUGD + " gold niet, want"
                    + " niet geldt: zijn leeftijd (30) is kleiner dan 18. Grondslag: BWBR0004045&artikel=8",
            "S1 heeft als meldingsdatum 13-03-2026 volgens 'berekende meldingsdatum' (onvoorwaardelijk): zijn"
                    + " berekende meldingsdatum (13-03-2026); 'opgegeven meldingsdatum' gaf geen waarde",
            melding("S1"));

    /** The sentence of the berekende meldingsdatum the rule 'melding' computes, with its ground. */
    private static String melding(String label) {
        return label + " heeft als berekende meldingsdatum 13-03-2026 volgens de regel 'melding' (onvoorwaardelijk): de"
                + " vinddag van de Jutter plus 3 dagen, met de vinddag van de Jutter (10-03-2026). Grondslag:"
                + " BWBR0004045&artikel=7";
    }

    /** The sentence of the rule the opgave marker makes, on an object without a given meldingsdatum. */
    private static String notDiffering(String label) {
        return label + " heeft geen afwijkende opgave meldingsdatum, want niet geldt: zijn opgegeven meldingsdatum"
                + " (onbekend) gevuld is";
    }

    /** The meta of the ground of each sentence, "" where it has none: the two versions of 'gevestigd' are one form,
     *  the rule and the group the opgave marker makes have none, the rule 'strandpremie' decides for S1, and the
     *  computed berekende meldingsdatum has the ground of the rule 'melding'. */
    private static final String RULE_GEVESTIGD = "{rule=gevestigd}";
    private static final String RULE_MELDING = "{rule=melding}";
    private static final String BASE = "{group=premie, pointer=/defaults/1, position=2, variant=premie}";
    private static final String NESTED = "/defaults/0/exceptions/members/0/defaults/0";

    private static List<String> grounds(String jeugdpremie, String kinderpremie) {
        return List.of(RULE_GEVESTIGD, RULE_GEVESTIGD, "", BASE, "", RULE_MELDING,
                RULE_GEVESTIGD, RULE_GEVESTIGD, "", jeugdpremie, "", RULE_MELDING,
                RULE_GEVESTIGD, RULE_GEVESTIGD, "", kinderpremie, "", RULE_MELDING,
                RULE_GEVESTIGD, RULE_GEVESTIGD, "", "{rule=strandpremie}", "", RULE_MELDING);
    }

    private static final List<String> NAMED_GROUNDS = grounds(
            "{group=premie, pointer=/defaults/0, position=1, variant=jeugdpremie}",
            "{group=premie, pointer=" + NESTED + ", position=1, variant=kinderpremie}");

    /** In the document without the names of the two exceptions: the sentence names them by their place. */
    private static final String KINDER_PLACE =
            "de variant op plaats 1 van uitzonderingsgroep 1 van de variant op plaats 1 van 'premie'";
    private static final String JEUGD_PLACE = "de variant op plaats 1 van 'premie'";

    private static final List<String> NAMELESS_EXPECTED = EXPECTED.stream()
            .map(s -> s.replace(KINDER, KINDER_PLACE).replace(JEUGD, JEUGD_PLACE)).toList();

    private static final List<String> NAMELESS_GROUNDS = grounds(
            "{group=premie, pointer=/defaults/0, position=1}",
            "{group=premie, pointer=" + NESTED + ", position=1}");

    /** The whole ground of the two sentences on a nested default and on a rule in a defaults sentence. */
    private static final Map<Integer, String> GROUND_PARTS = Map.of(
            15, "GROUND{group=premie, pointer=" + NESTED + ", position=1, variant=kinderpremie}[«. Grondslag: »"
                    + " STATUTE{bwb=BWBR0004045, article=5, paragraph=3}«BWBR0004045&artikel=5&lid=3»]",
            21, "GROUND{rule=strandpremie}[«. Grondslag: » STATUTE{bwb=BWBR0004045, article=8}«BWBR0004045&artikel=8»]");

    private static String document() {
        return UcharBuild.document(Resolver.resolve(SOURCE));
    }

    /** The document of the source without the names of 'jeugdpremie' and 'kinderpremie'. */
    private static String namelessDocument() throws Exception {
        JsonNode tree = DescriptorLoader.MAPPER.readTree(document());
        JsonNode jeugdpremie = tree.at("/rules/prioritizedDefaults/0/defaults/0");
        JsonNode kinderpremie = jeugdpremie.at("/exceptions/members/0/defaults/0");
        assertEquals("jeugdpremie", jeugdpremie.path("name").asText());
        assertEquals("kinderpremie", kinderpremie.path("name").asText());
        ((ObjectNode) jeugdpremie).remove("name");
        ((ObjectNode) kinderpremie).remove("name");
        return DescriptorLoader.MAPPER.writeValueAsString(tree);
    }

    private static DecisionCore fromSources() {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument(String document) {
        return DecisionCore.buildFromDocument(DESCRIPTOR, document, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
    }

    private static void assertGrounds(DecisionCore core, String document, List<String> expected, List<String> grounds)
            throws Exception {
        ObjectNode answer = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        List<String> sentences = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sentences.add(z.asText());
        assertEquals(expected, sentences);
        JsonNode parts = answer.get("explanationParts");
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), parts);
        List<String> meta = new ArrayList<>();
        for (JsonNode p : parts) meta.add(groundMeta(p));
        assertEquals(grounds, meta);
        assertEquals(16, ExplanationPartsCheck.assertGroundsNameTheirDeciders(parts,
                DescriptorLoader.MAPPER.readTree(document)));
    }

    /** The meta of the ground of a sentence as {@link ExplanationPartsCheck#render} writes it, "" without a ground. */
    private static String groundMeta(JsonNode parts) {
        JsonNode last = parts.get(parts.size() - 1);
        if (!"GROUND".equals(last.path("kind").asText())) return "";
        String rendered = ExplanationPartsCheck.render(DescriptorLoader.MAPPER.createArrayNode().add(last));
        return rendered.substring("GROUND".length(), rendered.indexOf('['));
    }

    @Test
    void from_sources_the_ground_names_its_decider() throws Exception {
        assertGrounds(fromSources(), document(), EXPECTED, NAMED_GROUNDS);
    }

    @Test
    void from_a_document_the_ground_names_its_decider() throws Exception {
        assertGrounds(fromDocument(document()), document(), EXPECTED, NAMED_GROUNDS);
    }

    @Test
    void a_default_without_a_name_is_named_by_its_group_and_place() throws Exception {
        String nameless = namelessDocument();
        assertGrounds(fromDocument(nameless), nameless, NAMELESS_EXPECTED, NAMELESS_GROUNDS);
    }

    @Test
    void the_ground_of_a_nested_default_and_of_a_rule_in_a_defaults_sentence() throws Exception {
        JsonNode parts = fromSources().process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true")).get("explanationParts");
        for (Map.Entry<Integer, String> e : new TreeMap<>(GROUND_PARTS).entrySet()) {
            JsonNode sentence = parts.get(e.getKey());
            JsonNode ground = DescriptorLoader.MAPPER.createArrayNode().add(sentence.get(sentence.size() - 1));
            assertEquals(e.getValue(), ExplanationPartsCheck.render(ground), "zin " + e.getKey());
        }
    }
}
