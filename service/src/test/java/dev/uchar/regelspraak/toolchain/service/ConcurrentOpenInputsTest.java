package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests that ask {@code openInputs} of a strict rule set side by side get the answers they get one after another, also
 * the first requests of a fresh core, which are the first to read what the core built for the explanation: per round a
 * fresh core of {@link OpenValuesSource}, and {@link #THREADS} requests that start at one barrier. Measured on uchar
 * {@code 62c393c94} and {@code f9919e9e3}, whose positions of a document made their pre-order at the first read: a request
 * that came in while another made it found no open member in the reading of a form it followed, a 500, in 36 to 45 of the
 * 200 rounds of a run.
 */
class ConcurrentOpenInputsTest {

    private static final RequestOptions OPEN_INPUTS = new RequestOptions(false, null, null, false, false, false, false, true);
    private static final int THREADS = 16, ROUNDS = 200;

    /** Requests of the entry point {@code vondst}, each with members that stay open. */
    private static final List<String> REQUESTS = List.of("""
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": {"state": "closed", "items": ["V1", "V2"]}}},
              {"type": "Vondst", "id": "V1", "attributes": {"taxatie": 50, "toeslag": 3}},
              {"type": "Vondst", "id": "V2", "characteristics": {"betwist": {"state": "holds"}}}]}""", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": {"state": "closed", "items": ["V1", "V2"]}}},
              {"type": "Vondst", "id": "V1", "attributes": {"toeslag": 3}},
              {"type": "Vondst", "id": "V2", "attributes": {"taxatie": 1500}}]}""", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": {"state": "closed", "items": ["V1"]}}}, {"type": "Vondst", "id": "V1"}]}""", """
            {"instances": [{"type": "Jutter", "id": "J1", "roles": {"vondsten": {"state": "open", "items": ["V1"]}}},
              {"type": "Vondst", "id": "V1", "attributes": {"taxatie": 50}}]}""", """
            {"instances": [{"type": "Vondst", "id": "V1", "attributes": {"taxatie": 2000},
               "characteristics": {"onleesbaar": {"state": "holdsNot"}}}]}""");

    @Test
    void requests_at_once_on_a_fresh_core_get_the_answers_one_after_another() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        for (String r : REQUESTS) requests.add(DescriptorLoader.MAPPER.readTree(r));
        DecisionCore alone = OpenValuesSource.core(CoreConfig.strict());
        List<ObjectNode> expected = new ArrayList<>();
        for (JsonNode r : requests) expected.add(alone.process("vondst", r, OPEN_INPUTS));
        for (ObjectNode e : expected) assertTrue(e.get("openInputs").size() > 0, "geen open invoer in " + e);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        int differing = 0;
        List<String> first = new ArrayList<>();
        try {
            for (int round = 0; round < ROUNDS; round++) {
                DecisionCore fresh = OpenValuesSource.core(CoreConfig.strict());
                CyclicBarrier start = new CyclicBarrier(THREADS);
                List<Future<ObjectNode>> answers = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    JsonNode request = requests.get(t % requests.size());
                    answers.add(pool.submit(() -> {
                        start.await(30, TimeUnit.SECONDS);
                        return fresh.process("vondst", request, OPEN_INPUTS);
                    }));
                }
                boolean differs = false;
                for (int t = 0; t < THREADS; t++) {
                    String wrong;
                    try {
                        ObjectNode answer = answers.get(t).get(60, TimeUnit.SECONDS);
                        wrong = answer.equals(expected.get(t % requests.size())) ? null : "een ander antwoord: " + answer;
                    } catch (ExecutionException e) {
                        wrong = String.valueOf(e.getCause());
                    }
                    if (wrong != null) {
                        differs = true;
                        if (first.size() < 3) first.add("ronde " + round + ", verzoek " + t % requests.size() + ": " + wrong);
                    }
                }
                if (differs) differing++;
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, differing, "rondes van " + ROUNDS + " waarin een verzoek tegelijk iets anders gaf dan na elkaar: "
                + first);
    }
}
