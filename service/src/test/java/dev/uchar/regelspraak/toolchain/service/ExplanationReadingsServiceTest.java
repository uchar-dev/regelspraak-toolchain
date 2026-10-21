package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explanation of the decision service renders a condition from the engine's readings (card
 * explanation-from-readings), on both routes: from sources and from an IR document. The model and the sentences are
 * those of {@code ExplanationReadingsTest} (the scenario route), one per form family of uchar's card fixpoint-readings.
 * An attribution rule outside the scope of an entry point has no rule in its build and so no reading: the explanation
 * says so in one sentence. An entry point whose descriptor sets {@code explanation} to false builds no build with
 * readings and refuses a request for an explanation; the answer of a request with an explanation is that of one
 * without.
 */
class ExplanationReadingsServiceTest {

    private static final String SOURCE = """
            Eenheidsysteem Tijd
                de maand mnd
                het jaar jr = 12 mnd

            Eenheidsysteem Afstand
                de meter m
                de kilometer km = 1000 m

            Eenheidsysteem Gewicht
                het kilogram (mv: kilogrammen) kg

            Eenheidsysteem Valuta
                de euro (mv: euros) EUR

            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                het aantal meldingen Numeriek (geheel getal);
                de afstand tot de kust Numeriek (geheel getal) met eenheid m;
                de geboortedatum Datum in dagen;
                de peildatum Datum in dagen;
                de premie Numeriek (getal met 2 decimalen) met eenheid EUR;
                de bonus Numeriek (getal met 2 decimalen) met eenheid EUR;
                is ingezetene kenmerk (bijvoeglijk);
                is in dienst kenmerk (bijvoeglijk);
                is gerechtigd kenmerk (bijvoeglijk);
                is bijzonder kenmerk (bijvoeglijk);
                is actief kenmerk (bijvoeglijk);
                is gelukkig kenmerk (bijvoeglijk);
                is meldplichtig kenmerk (bijvoeglijk);
                is bonuswaardig kenmerk (bijvoeglijk);
                is kustjutter kenmerk (bijvoeglijk);
                is minderjarig kenmerk (bijvoeglijk);
                is premiewaardig kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal) met eenheid kg;
                is waardevol kenmerk (bijvoeglijk);
                is aangemeld kenmerk (bijvoeglijk);

            Feittype vondst van een jutter
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel gerechtigd
                geldig altijd
                    Een Jutter is gerechtigd
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is ingezetene
                    - hij is in dienst
                    - zijn leeftijd is groter dan 17 jr.

            Regel bijzonder
                geldig altijd
                    Een Jutter is bijzonder
                    indien hij aan ten minste één van de volgende voorwaarden voldoet:
                    - hij is in dienst
                    - zijn aantal meldingen is groter dan 10.

            Regel actief
                geldig altijd
                    Een Jutter is actief
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is ingezetene
                    - het aantal vondsten van de Jutter die aan de volgende voorwaarde voldoen: hij is waardevol is groter dan 0.

            Regel gelukkig
                geldig altijd
                    Een Jutter is gelukkig
                    indien alle vondsten van de Jutter waardevol zijn.

            Regel meldplichtig
                geldig altijd
                    Een Jutter is meldplichtig
                    indien zijn aantal meldingen groter is dan 3.

            Regel bonuswaardig
                geldig altijd
                    Een Jutter is bonuswaardig
                    indien zijn bonus maal 2 groter is dan 10,00 EUR.

            Regel kustjutter
                geldig altijd
                    Een Jutter is kustjutter
                    indien zijn afstand tot de kust kleiner is dan 0,5 km.

            Regel minderjarig
                geldig altijd
                    Een Jutter is minderjarig
                    indien de tijdsduur van zijn geboortedatum tot zijn peildatum in hele jaren kleiner is dan 18 jr.

            Regel aangemeld
                geldig altijd
                    Een Vondst is aangemeld
                    indien de jutter van de Vondst ingezetene is.

            Regel premiewaardig
                geldig altijd
                    Een Jutter is premiewaardig
                    indien zijn premie groter is dan 30,00 EUR.
            """;

    private static final String DESCRIPTOR = """
        { "service": {"name":"Uitleg uit de lezingen","version":"1"},
          "entrypoints": [ { "name":"jutters","path":"jutters",
            "scope": {"ruleNames": ["gerechtigd", "bijzonder", "actief", "gelukkig", "meldplichtig", "bonuswaardig", "kustjutter", "minderjarig", "aangemeld", "premiewaardig"]},
            "input": ["Jutter", "Vondst"],
            "output": ["Jutter:gerechtigd", "Jutter:bijzonder", "Jutter:actief", "Jutter:gelukkig", "Jutter:meldplichtig", "Jutter:bonuswaardig", "Jutter:kustjutter", "Jutter:minderjarig", "Jutter:premiewaardig", "Vondst:aangemeld"] } ],
          "parameters": {} }""";

    private static final String REQUEST = """
            { "instances": [
                { "type": "Jutter", "id": "J1", "attributes": { "leeftijd": 30, "afstand tot de kust": 700,
                    "geboortedatum": "1996-01-01", "peildatum": "2026-06-01", "premie": "60.00" },
                  "characteristics": { "ingezetene": true } },
                { "type": "Jutter", "id": "J2", "attributes": { "leeftijd": 16, "aantal meldingen": 12,
                    "afstand tot de kust": 300, "geboortedatum": "2010-01-01", "peildatum": "2026-06-01",
                    "premie": "20.00", "bonus": "7.50" },
                  "characteristics": { "in dienst": true } },
                { "type": "Vondst", "id": "W1", "attributes": { "gewicht": 3 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "W2", "attributes": { "gewicht": 5 }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "W3", "attributes": { "gewicht": 2 },
                  "characteristics": { "waardevol": true }, "roles": { "jutter": "J2" } } ] }""";

    static final List<String> EXPECTED = List.of(
            "J1 is niet gerechtigd, want niet geldt: het onderwerp in dienst is",
            "J1 is niet bijzonder, want niet geldt: het onderwerp in dienst is of zijn aantal meldingen (onbekend) is groter dan 10",
            "J1 is actief, omdat het onderwerp ingezetene is en het aantal vondsten van de Jutter die aan de volgende voorwaarde voldoen: hij is waardevol (1) is groter dan 0",
            "J1 is niet gelukkig, want aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is niet voldaan",
            "J1 is niet meldplichtig, want niet geldt: zijn aantal meldingen (onbekend) is groter dan 3",
            "J1 is niet bonuswaardig, want niet geldt: zijn bonus maal 2 (0,00 EUR) is groter dan 10,00 EUR",
            "J1 is niet kustjutter, want niet geldt: zijn afstand tot de kust (700 m) is kleiner dan 0,5 km",
            "J1 is niet minderjarig, want niet geldt: de tijdsduur van zijn geboortedatum tot zijn peildatum in hele jaren (30 jr) is kleiner dan 18 jr",
            "J1 is premiewaardig, omdat zijn premie (60,00 EUR) is groter dan 30,00 EUR",
            "J2 is niet gerechtigd, want niet geldt: het onderwerp ingezetene is",
            "J2 is bijzonder, omdat het onderwerp in dienst is",
            "J2 is niet actief, want niet geldt: het onderwerp ingezetene is",
            "J2 is gelukkig, omdat aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is voldaan",
            "J2 is meldplichtig, omdat zijn aantal meldingen (12) is groter dan 3",
            "J2 is bonuswaardig, omdat zijn bonus maal 2 (15,00 EUR) is groter dan 10,00 EUR",
            "J2 is kustjutter, omdat zijn afstand tot de kust (300 m) is kleiner dan 0,5 km",
            "J2 is minderjarig, omdat de tijdsduur van zijn geboortedatum tot zijn peildatum in hele jaren (16 jr) is kleiner dan 18 jr",
            "J2 is niet premiewaardig, want niet geldt: zijn premie (20,00 EUR) is groter dan 30,00 EUR",
            "W1 is aangemeld, omdat de jutter ingezetene is",
            "W2 is aangemeld, omdat de jutter ingezetene is",
            "W3 is niet aangemeld, want niet geldt: de jutter ingezetene is");

    private static DecisionCore fromSources(String descriptor) {
        return DecisionCore.assemble(descriptor, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument(String descriptor) {
        return DecisionCore.buildFromDocument(descriptor, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static List<String> explanation(DecisionCore core) throws Exception {
        JsonNode answer = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST), true);
        List<String> sink = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sink.add(z.asText());
        return sink;
    }

    @Test
    void from_sources_every_form_family_is_explained_from_the_readings() throws Exception {
        assertEquals(EXPECTED, explanation(fromSources(DESCRIPTOR)));
    }

    @Test
    void from_a_document_every_form_family_is_explained_from_the_readings() throws Exception {
        assertEquals(EXPECTED, explanation(fromDocument(DESCRIPTOR)));
    }

    /** Without the rule 'gelukkig' in the scope: its sentences give way to one that it is outside the computation. */
    @Test
    void a_rule_outside_the_scope_is_named_once_as_outside_the_computation() throws Exception {
        List<String> expected = new ArrayList<>();
        for (String z : EXPECTED) if (!z.contains("gelukkig")) expected.add(z);
        expected.add("de regel 'gelukkig' staat buiten deze berekening");
        assertEquals(expected, explanation(fromSources(DESCRIPTOR.replace("\"gelukkig\", ", "")
                .replace("\"Jutter:gelukkig\", ", ""))));
    }

    /** The answer outside the explanation is that of a request without one: the build with readings answers it. */
    @Test
    void the_answer_with_an_explanation_is_the_answer_without() throws Exception {
        for (DecisionCore core : List.of(fromSources(DESCRIPTOR), fromDocument(DESCRIPTOR))) {
            JsonNode with = core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST), true).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) with).remove("explanation");
            assertEquals(core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST)), with);
        }
    }

    /** An entry point that does not explain refuses the request for an explanation and answers the one without. */
    @Test
    void an_entry_point_without_explanation_refuses_it() throws Exception {
        String descriptor = DESCRIPTOR.replace("\"path\":\"jutters\",", "\"path\":\"jutters\", \"explanation\": false,");
        for (DecisionCore core : List.of(fromSources(descriptor), fromDocument(descriptor))) {
            assertFalse(core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST)).has("explanation"));
            ServiceError e = org.junit.jupiter.api.Assertions.assertThrows(ServiceError.class,
                    () -> core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST), true));
            assertTrue(e.violations().get(0).toString().contains("explanation"), e.violations()::toString);
        }
    }
}
