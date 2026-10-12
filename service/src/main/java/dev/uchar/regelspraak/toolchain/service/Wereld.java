package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.toolchain.engine.RolSchrijver;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mapped request world: the labelled beans to insert, the order of their to-many lists, the parameter globals to
 * set, and the rekendatum — everything a session needs to fire. It is a plain value object with no engine coupling,
 * produced by {@link VerzoekMapper} and consumed by {@link Uitvoerder}.
 *
 * @param beans      label (id) → fact bean, in first-seen order
 * @param globals    global name (already the engine's camelCase form) → value (BigDecimal / LocalDate / …)
 * @param rekendatum the §4.2 rekendatum, or null when the request set none
 * @param lijsten    the to-many lists the roles and relations of the request fill, in the order of uchar's replay
 */
public record Wereld(Map<String, Object> beans, Map<String, Object> globals, LocalDate rekendatum,
                     RolSchrijver.Lijsten lijsten) {

    public Wereld {
        beans = beans == null ? new LinkedHashMap<>() : beans;
        globals = globals == null ? new LinkedHashMap<>() : globals;
    }
}
