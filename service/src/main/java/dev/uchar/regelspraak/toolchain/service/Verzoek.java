package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A decision request as a raw Jackson tree — the framework-agnostic core object the HTTP layer (fase 3) will
 * hand in. It must express everything a {@link dev.uchar.regelspraak.ir.ResolvedScenario} situatie can: a
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
 * "de reis is V1" and a case file's {@code roles}) and/or via the top-level {@code relaties} list; the inline ones
 * are written as uchar's replay writes a case's roles ({@link dev.uchar.regelspraak.toolchain.engine.RolSchrijver}).
 */
public record Verzoek(JsonNode boom) {

    public Verzoek {
        if (boom == null) throw new IllegalArgumentException("verzoek-boom is null");
    }

    /** The {@code rekendatum} node, or null when absent. */
    public JsonNode rekendatum() { return boom.get("rekendatum"); }

    /** The {@code peildatum} node, or null when absent (fase 5). {@code peildatum} is the PREFERRED name for the
     *  one date authority; {@code rekendatum} is the existing alias — {@link VerzoekMapper} unifies the two. */
    public JsonNode peildatum() { return boom.get("peildatum"); }

    /** The {@code parameters} object node, or null when absent. */
    public JsonNode parameters() { return boom.get("parameters"); }

    /** The {@code instanties} array node, or null when absent. */
    public JsonNode instanties() { return boom.get("instanties"); }

    /** The {@code relaties} array node, or null when absent. */
    public JsonNode relaties() { return boom.get("relaties"); }
}
