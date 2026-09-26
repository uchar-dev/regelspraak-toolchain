package dev.uchar.regelspraak.toolchain.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mapped request world: the labelled beans to insert, the parameter globals to set, and the rekendatum —
 * everything a session needs to fire. It is a plain value object with no engine coupling, produced by
 * {@link VerzoekMapper} and consumed by {@link Uitvoerder}.
 *
 * @param beans      label (id) → fact bean, in first-seen order
 * @param globals    global name (already the engine's camelCase form) → value (BigDecimal / LocalDate / …)
 * @param rekendatum the §4.2 rekendatum, or null when the request set none
 */
public record Wereld(Map<String, Object> beans, Map<String, Object> globals, LocalDate rekendatum) {

    public Wereld {
        beans = beans == null ? new LinkedHashMap<>() : beans;
        globals = globals == null ? new LinkedHashMap<>() : globals;
    }
}
