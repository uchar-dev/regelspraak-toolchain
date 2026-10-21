package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The explanation of a quantifier fold names the members that carry its outcome, with their number (card
 * explanation-fold-members), on both routes of the decision service: from sources and from an IR document. The model,
 * the objects and the sentences are those of {@code ExplanationFoldMembersTest} (the scenario route): one rule per
 * quantifier, over a Jutter with two of four vondsten waardevol, one with one, one with two that are neither, one
 * without vondsten, one with seven, one with two light ones that are waardevol and one with one light one that is
 * not.
 */
class ExplanationFoldMembersServiceTest {

    private static final String SOURCE = """
            Eenheidsysteem Gewicht
                het kilogram (mv: kilogrammen) kg

            Objecttype de Jutter (mv: Jutters)
                is gelukkig kenmerk (bijvoeglijk);
                is onvermogend kenmerk (bijvoeglijk);
                is vermogend kenmerk (bijvoeglijk);
                is zwaarbeladen kenmerk (bijvoeglijk);
                is lichtbeladen kenmerk (bijvoeglijk);
                is enkelbeladen kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal) met eenheid kg;
                is waardevol kenmerk (bijvoeglijk);

            Feittype vondst van een jutter
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel gelukkig
                geldig altijd
                    Een Jutter is gelukkig
                    indien alle vondsten van de Jutter waardevol zijn.

            Regel onvermogend
                geldig altijd
                    Een Jutter is onvermogend
                    indien geen van de vondsten van de Jutter waardevol is.

            Regel vermogend
                geldig altijd
                    Een Jutter is vermogend
                    indien ten minste één van de vondsten van de Jutter waardevol is.

            Regel zwaarbeladen
                geldig altijd
                    Een Jutter is zwaarbeladen
                    indien het gewicht van ten minste 2 van de vondsten van de Jutter groter is dan 4 kg.

            Regel lichtbeladen
                geldig altijd
                    Een Jutter is lichtbeladen
                    indien het gewicht van ten hoogste 1 van de vondsten van de Jutter groter is dan 4 kg.

            Regel enkelbeladen
                geldig altijd
                    Een Jutter is enkelbeladen
                    indien het gewicht van precies 1 van de vondsten van de Jutter groter is dan 4 kg.
            """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Leden van een kwantorvouw","version":"1"},
          "entrypoints": [ { "name":"jutters","path":"jutters",
            "scope": {"ruleNames": ["gelukkig", "onvermogend", "vermogend", "zwaarbeladen", "lichtbeladen", "enkelbeladen"]},
            "input": ["Jutter", "Vondst"],
            "output": ["Jutter:gelukkig", "Jutter:onvermogend", "Jutter:vermogend", "Jutter:zwaarbeladen", "Jutter:lichtbeladen", "Jutter:enkelbeladen"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
            { "instances": [
                { "type": "Jutter", "id": "J1" },
                { "type": "Jutter", "id": "J2" },
                { "type": "Jutter", "id": "J3" },
                { "type": "Jutter", "id": "J4" },
                { "type": "Jutter", "id": "J5" },
                { "type": "Jutter", "id": "J6" },
                { "type": "Jutter", "id": "J7" },
                { "type": "Vondst", "id": "V1", "attributes": { "gewicht": 3 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "V2", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "V3", "attributes": { "gewicht": 2 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "V4", "attributes": { "gewicht": 6 }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "V5", "attributes": { "gewicht": 5 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J2" } },
                { "type": "Vondst", "id": "V6", "attributes": { "gewicht": 3 }, "roles": { "jutter": "J3" } },
                { "type": "Vondst", "id": "V7", "attributes": { "gewicht": 2 }, "roles": { "jutter": "J3" } },
                { "type": "Vondst", "id": "X20", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "X3", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "X10", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "X1", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "X12", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "X2", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "X11", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J5" } },
                { "type": "Vondst", "id": "V8", "attributes": { "gewicht": 1 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J6" } },
                { "type": "Vondst", "id": "V9", "attributes": { "gewicht": 2 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J6" } },
                { "type": "Vondst", "id": "V10", "attributes": { "gewicht": 1 }, "roles": { "jutter": "J7" } } ] }""";

    private static final String ALL = "aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\"";
    private static final String NONE = "aan de voorwaarde \"geen van de vondsten van de Jutter waardevol is\"";
    private static final String AT_LEAST_ONE =
            "aan de voorwaarde \"ten minste één van de vondsten van de Jutter waardevol is\"";
    private static final String AT_LEAST =
            "aan de voorwaarde \"het gewicht van ten minste 2 van de vondsten van de Jutter groter is dan 4 kg\"";
    private static final String AT_MOST =
            "aan de voorwaarde \"het gewicht van ten hoogste 1 van de vondsten van de Jutter groter is dan 4 kg\"";
    private static final String EXACTLY =
            "aan de voorwaarde \"het gewicht van precies 1 van de vondsten van de Jutter groter is dan 4 kg\"";
    private static final String SEVEN = "X1, X10, X11, X12, X2 en nog 2";

    static final List<String> EXPECTED = List.of(
            "J1 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor V2 en V4 (2 van de 4)",
            "J1 is niet onvermogend, want " + NONE + " is niet voldaan: wel voor V1 en V3 (2 van de 4)",
            "J1 is vermogend, omdat " + AT_LEAST_ONE + " is voldaan: voor 2 van de 4 (V1 en V3)",
            "J1 is zwaarbeladen, omdat " + AT_LEAST + " is voldaan: voor 2 van de 4 (V2 en V4)",
            "J1 is niet lichtbeladen, want " + AT_MOST + " is niet voldaan: voor 2 van de 4 (V2 en V4)",
            "J1 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor 2 van de 4 (V2 en V4)",
            "J2 is gelukkig, omdat " + ALL + " is voldaan: voor V5, het enige lid",
            "J2 is niet onvermogend, want " + NONE + " is niet voldaan: wel voor V5, het enige lid",
            "J2 is vermogend, omdat " + AT_LEAST_ONE + " is voldaan: voor V5, het enige lid",
            "J2 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: voor V5, het enige lid",
            "J2 is lichtbeladen, omdat " + AT_MOST + " is voldaan: voor V5, het enige lid",
            "J2 is enkelbeladen, omdat " + EXACTLY + " is voldaan: voor V5, het enige lid",
            "J3 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor V6 en V7 (2 van de 2)",
            "J3 is onvermogend, omdat " + NONE + " is voldaan: voor geen van de 2",
            "J3 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: voor geen van de 2 (V6 en V7)",
            "J3 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: voor geen van de 2 (V6 en V7)",
            "J3 is lichtbeladen, omdat " + AT_MOST + " is voldaan: voor geen van de 2 (V6 en V7)",
            "J3 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor geen van de 2 (V6 en V7)",
            "J4 is gelukkig, omdat " + ALL + " is voldaan: er is geen enkel lid",
            "J4 is onvermogend, omdat " + NONE + " is voldaan: er is geen enkel lid",
            "J4 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: er is geen enkel lid",
            "J4 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: er is geen enkel lid",
            "J4 is lichtbeladen, omdat " + AT_MOST + " is voldaan: er is geen enkel lid",
            "J4 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: er is geen enkel lid",
            "J5 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor " + SEVEN + " (7 van de 7)",
            "J5 is onvermogend, omdat " + NONE + " is voldaan: voor geen van de 7",
            "J5 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: voor geen van de 7 (" + SEVEN + ")",
            "J5 is zwaarbeladen, omdat " + AT_LEAST + " is voldaan: voor 7 van de 7 (" + SEVEN + ")",
            "J5 is niet lichtbeladen, want " + AT_MOST + " is niet voldaan: voor 7 van de 7 (" + SEVEN + ")",
            "J5 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor 7 van de 7 (" + SEVEN + ")",
            "J6 is gelukkig, omdat " + ALL + " is voldaan: voor alle 2",
            "J6 is niet onvermogend, want " + NONE + " is niet voldaan: wel voor V8 en V9 (2 van de 2)",
            "J6 is vermogend, omdat " + AT_LEAST_ONE + " is voldaan: voor 2 van de 2 (V8 en V9)",
            "J6 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: voor geen van de 2 (V8 en V9)",
            "J6 is lichtbeladen, omdat " + AT_MOST + " is voldaan: voor geen van de 2 (V8 en V9)",
            "J6 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: voor geen van de 2 (V8 en V9)",
            "J7 is niet gelukkig, want " + ALL + " is niet voldaan: niet voor V10, het enige lid",
            "J7 is onvermogend, omdat " + NONE + " is voldaan: niet voor V10, het enige lid",
            "J7 is niet vermogend, want " + AT_LEAST_ONE + " is niet voldaan: niet voor V10, het enige lid",
            "J7 is niet zwaarbeladen, want " + AT_LEAST + " is niet voldaan: niet voor V10, het enige lid",
            "J7 is lichtbeladen, omdat " + AT_MOST + " is voldaan: niet voor V10, het enige lid",
            "J7 is niet enkelbeladen, want " + EXACTLY + " is niet voldaan: niet voor V10, het enige lid");

    private static DecisionCore fromSources() {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument() {
        return DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static List<String> explanation(DecisionCore core) throws Exception {
        JsonNode answer = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST), true);
        List<String> sink = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sink.add(z.asText());
        return sink;
    }

    @Test
    void from_sources_the_sentence_of_a_quantifier_fold_names_its_members() throws Exception {
        assertEquals(EXPECTED, explanation(fromSources()));
    }

    @Test
    void from_a_document_the_sentence_of_a_quantifier_fold_names_its_members() throws Exception {
        assertEquals(EXPECTED, explanation(fromDocument()));
    }
}
