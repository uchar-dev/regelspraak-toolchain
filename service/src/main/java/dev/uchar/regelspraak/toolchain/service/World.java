package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.cases.CaseInput;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * The mapped request world: uchar's reading of the request as the input of a case ({@link CaseInput}: the labelled
 * beans, what {@code insert} needs beside them, and the parameters it binds on a session) and the evaluation date —
 * everything a session needs to fire. Produced by {@link RequestMapper} and consumed by {@link Executor}.
 *
 * @param input          the beans of the request by id and the parameters, read by uchar's input step from the case the
 *                       mapper wrote
 * @param evaluationDate the §4.2 rekendatum, or null when the request set none
 */
public record World(CaseInput input, LocalDate evaluationDate) {

    public World {
        Objects.requireNonNull(input, "input");
    }

    /** The beans by id, in the order of the request. */
    public Map<String, Object> beans() {
        return input.beans();
    }
}
