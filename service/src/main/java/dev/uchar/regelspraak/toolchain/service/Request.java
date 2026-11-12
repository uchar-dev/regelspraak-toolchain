package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A decision request as a raw Jackson tree, as an adapter hands it to the core. It expresses what a situation of a
 * scenario can: an evaluation date, parameter overrides, labelled object instances with attribute and characteristic
 * values, and role links between those instances.
 *
 * <p>Shape ({@link RequestMapper} refuses any other key at these three levels):
 * <pre>{@code
 * {
 *   "asOfDate"?: "yyyy-MM-dd",   "rekendatum"?: "yyyy-MM-dd",   (one date; both given, they must be equal)
 *   "parameters"?: { "<parameter>": <value>, ... },
 *   "instances": [
 *     { "type": "<object type>", "id": "<label>",
 *       "attributes"?: { "<attribute>": <value>, ... },
 *       "characteristics"?: { "<characteristic>": true|false, ... },
 *       "roles"?: { "<role>": "<id>" | ["<id>", ...], ... } }
 *   ],
 *   "relations"?: [ { "factType": "<name>", "roles": { "<role>": "<id>" | ["<id>", ...], ... } } ]
 * }
 * }</pre>
 * Role links may be given inline per instance ({@code instances[].roles}, as a case file's {@code roles}) and through
 * the top-level {@code relations}; both become the {@code roles} of a case, which uchar's input step reads.
 */
public record Request(JsonNode tree) {

    public Request {
        if (tree == null) throw new IllegalArgumentException("verzoek-boom is null");
    }

    /** The {@code rekendatum} node, or null when absent: the same date as {@link #asOfDate}, under the name of the
     *  RegelSpraak global; {@link RequestMapper} unifies the two. */
    public JsonNode evaluationDate() { return tree.get("rekendatum"); }

    /** The {@code asOfDate} node, or null when absent: the one evaluation date of the request. */
    public JsonNode asOfDate() { return tree.get("asOfDate"); }

    /** The {@code parameters} object node, or null when absent. */
    public JsonNode parameters() { return tree.get("parameters"); }

    /** The {@code instances} array node, or null when absent. */
    public JsonNode instances() { return tree.get("instances"); }

    /** The {@code relations} array node, or null when absent. */
    public JsonNode relations() { return tree.get("relations"); }
}
