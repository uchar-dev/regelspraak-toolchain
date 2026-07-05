package io.github.yhavinga.regelspraak.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A decision request as a raw Jackson tree — the framework-agnostic core object the HTTP layer (fase 3) will
 * hand in. It must express everything a {@link io.github.yhavinga.regelspraak.ir.ResolvedScenario} situatie can: a
 * rekendatum, parameter overrides, labelled object instances with attribuut/kenmerk values, and rol relations
 * between those instances.
 *
 * <p>Shape (decision 4):
 * <pre>{@code
 * {
 *   "rekendatum"?: "yyyy-MM-dd",
 *   "parameters"?: { "<parameternaam>": <waarde>, ... },
 *   "instanties": [
 *     { "type": "<objecttype>", "id": "<label>",
 *       "attributen"?: { "<attribuut>": <waarde>, ... },
 *       "kenmerken"?:  { "<kenmerk>": true|false, ... },
 *       "rollen"?:     { "<rol>": "<id>" | ["<id>", ...], ... } }
 *   ],
 *   "relaties"?: [ { "feittype": "<naam>", "rollen": { "<rol>": "<id>", ... } } ]
 * }
 * }</pre>
 * Rol relations may be given inline per instance ({@code instanties[].rollen}, mirroring the situatie's
 * "de reis is V1") and/or via the top-level {@code relaties} list; both are desugared to the same bean-field
 * writes the {@link io.github.yhavinga.regelspraak.engine.ScenarioRunner} performs.
 */
public record Verzoek(JsonNode boom) {

    public Verzoek {
        if (boom == null) throw new IllegalArgumentException("verzoek-boom is null");
    }

    /** The {@code rekendatum} node, or null when absent. */
    public JsonNode rekendatum() { return boom.get("rekendatum"); }

    /** The {@code parameters} object node, or null when absent. */
    public JsonNode parameters() { return boom.get("parameters"); }

    /** The {@code instanties} array node, or null when absent. */
    public JsonNode instanties() { return boom.get("instanties"); }

    /** The {@code relaties} array node, or null when absent. */
    public JsonNode relaties() { return boom.get("relaties"); }
}
