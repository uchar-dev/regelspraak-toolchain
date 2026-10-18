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
 *   "instances": [
 *     { "type": "<objecttype>", "id": "<label>",
 *       "attributes"?: { "<attribuut>": <waarde>, ... },
 *       "characteristics"?:  { "<kenmerk>": true|false, ... },
 *       "roles"?:     { "<rol>": "<id>" | ["<id>", ...], ... } }
 *   ],
 *   "relations"?: [ { "factType": "<naam>", "roles": { "<rol>": "<id>", ... } } ]
 * }
 * }</pre>
 * Rol relations may be given inline per instance ({@code instances[].rollen}, mirroring the situatie's
 * "de reis is V1" and a case file's {@code roles}) and/or via the top-level {@code relations} list; both become the
 * {@code roles} of a case, which uchar's input step reads ({@link RequestMapper}).
 */
public record Request(JsonNode tree) {

    public Request {
        if (tree == null) throw new IllegalArgumentException("verzoek-boom is null");
    }

    /** The {@code evaluationDate} node, or null when absent. */
    public JsonNode evaluationDate() { return tree.get("rekendatum"); }

    /** The {@code asOfDate} node, or null when absent (fase 5). {@code asOfDate} is the PREFERRED name for the
     *  one date authority; {@code evaluationDate} is the existing alias — {@link RequestMapper} unifies the two. */
    public JsonNode asOfDate() { return tree.get("asOfDate"); }

    /** The {@code parameters} object node, or null when absent. */
    public JsonNode parameters() { return tree.get("parameters"); }

    /** The {@code instances} array node, or null when absent. */
    public JsonNode instances() { return tree.get("instances"); }

    /** The {@code relations} array node, or null when absent. */
    public JsonNode relations() { return tree.get("relations"); }
}
