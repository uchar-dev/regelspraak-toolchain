package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.cases.CaseInvoer;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The mapped request world: uchar's reading of the request as the input of a case ({@link CaseInvoer}: the labelled
 * beans and what {@code voegIn} needs beside them), the parameter globals to set, and the rekendatum — everything a
 * session needs to fire. Produced by {@link VerzoekMapper} and consumed by {@link Uitvoerder}.
 *
 * @param invoer     the beans of the request by id, read by uchar's input step from the case the mapper wrote
 * @param globals    global name (already the engine's camelCase form) → value (BigDecimal / LocalDate / …)
 * @param rekendatum the §4.2 rekendatum, or null when the request set none
 */
public record Wereld(CaseInvoer invoer, Map<String, Object> globals, LocalDate rekendatum) {

    public Wereld {
        Objects.requireNonNull(invoer, "invoer");
        globals = globals == null ? new LinkedHashMap<>() : globals;
    }

    /** The beans by id, in the order of the request. */
    public Map<String, Object> beans() {
        return invoer.beans();
    }
}
