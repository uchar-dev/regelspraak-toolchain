package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ExplanationPartsCheck;
import dev.uchar.regelspraak.toolchain.service.ExplanationPartsServiceTest;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card explanation-parts: {@code POST /playground/analyse} gives per scenario {@code explanationParts} beside
 * {@code explanation}, on the ordinary route (uchar's replay) and with {@code via=service} (the service's answer with
 * {@code explanationParts=true}), from the one writer of the service. The model is that of
 * {@code ExplanationPartsServiceTest}; J1 meets the nested list through ingezetene and has one of two vondsten
 * waardevol.
 */
class PlaygroundExplanationPartsTest {

    private static final String SITUATION = """

            Situatie basis:
            een Jutter J1
            - de leeftijd is 30
            - het aantal meldingen is 3
            - is ingezetene
            een Vondst V1
            - is waardevol
            - de jutter is J1
            een Vondst V2
            - de jutter is J1
            de parameter de minimumleeftijd is 17
            Scenario een jutter
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - J1 is ervaren
            - J1 is niet gelukkig
            """;

    @Test
    void both_routes_give_the_parts_of_every_sentence() {
        String document = ExplanationPartsServiceTest.SOURCE + SITUATION;
        DecisionCore core = DecisionCore.assemble(ExplanationPartsServiceTest.DESCRIPTOR,
                Map.of("bron.rgs", ExplanationPartsServiceTest.SOURCE), CoreConfig.strict().withDetail(),
                Thread.currentThread().getContextClassLoader());
        JsonNode ordinary = Playground.analyze(document, null).path("scenario").path("results").get(0);
        JsonNode service = Playground.analyze(document, null, core).path("scenario").path("results").get(0);
        for (JsonNode r : new JsonNode[]{ordinary, service}) {
            assertEquals("PASS", r.path("status").asText(), r::toString);
            assertTrue(r.path("explanation").size() > 0, r::toString);
            Set<String> kinds = ExplanationPartsCheck.assertPartsAreTheSentences(r.get("explanation"),
                    r.get("explanationParts"));
            assertTrue(kinds.containsAll(Set.of("CONDITION", "MEMBERS", "GROUND", "STATUTE", "LEMMA", "DEVIATION",
                    "NOTE")), kinds::toString);
        }
        assertEquals(ordinary.get("explanation"), service.get("explanation"));
        assertEquals(ordinary.get("explanationParts"), service.get("explanationParts"));
    }
}
