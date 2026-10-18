package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Eenheid;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Decodes ONE JSON value into the Java value the fact bean setter (and the exec-model bridge) expects for a
 * given {@link Datatype}, collecting a typed {@link Violation} (with its field path) on any mismatch instead
 * of throwing. This is the single place the request's JSON meets the model's typing matrix
 * (typeringen v2.3.0), so every rule about numbers, dates, booleans, tekst, enumeraties and eenheden lives
 * here and nowhere else.
 *
 * <p>Coercions (per the pinned decision 4):
 * <ul>
 *   <li><b>Numeriek / Percentage</b> → {@link BigDecimal} (a JSON number is already a BigDecimal because the
 *       mapper runs with {@code USE_BIG_DECIMAL_FOR_FLOATS}; a double NEVER appears). An eenheid-bearing
 *       numeriek accepts a plain number (read in the declared eenheid) OR an object
 *       {@code {"waarde": n, "eenheid": "<afk>"}} whose eenheid must EQUAL the declared unit's weergave.</li>
 *   <li><b>Booleaan</b> → {@link Boolean}.</li>
 *   <li><b>Tekst</b> → {@link String}.</li>
 *   <li><b>Enumeratie</b> → the {@link String} literal as the request spells it, which must name one of the
 *       domein's waarden. A waarde is a declared name, compared as uchar compares declared names (its
 *       {@code Namen.norm}: spacing collapsed, A-Z lowercased), so "Gaaf" names the declared "gaaf"; the spelling
 *       of the request is kept, as uchar's replay keeps a case's spelling, and the rules compare it alike.</li>
 *   <li><b>Datum-in-dagen</b> → {@link LocalDate} (ISO-8601 {@code yyyy-MM-dd}).</li>
 *   <li><b>Datum-en-tijd</b> → {@link LocalDateTime} (ISO-8601 {@code yyyy-MM-ddThh:mm:ss[.SSS]}).</li>
 * </ul>
 * A JSON {@code null} decodes to a Java {@code null} (an explicit leegwaarde) for any datatype.
 */
public final class ValueCoder {

    private ValueCoder() {}

    /** The decoded value plus the violations produced while decoding (empty on success). A failed decode
     *  returns {@code waarde == null} AND a non-empty {@link #violations}. */
    public record Result(Object value, List<Violation> violations) {
        static Result ok(Object w) { return new Result(w, List.of()); }
        static Result error(Violation s) { return new Result(null, List.of(s)); }
    }

    /**
     * Decode {@code node} as a value of {@code dt}. {@code field} is the request field path used in any violation.
     */
    public static Result decodeer(JsonNode node, Datatype dt, String field) {
        if (node == null || node.isNull()) return Result.ok(null);   // explicit leegwaarde

        if (dt instanceof Datatype.Numeriek n)   return numeric(node, field, n.eenheid());
        if (dt instanceof Datatype.Percentage p) return numeric(node, field, percentUnit(p.eenheid()));
        if (dt instanceof Datatype.Booleaan)     return decodeBoolean(node, field);
        if (dt instanceof Datatype.Tekst)        return text(node, field);
        if (dt instanceof Datatype.Enumeratie e) return enumeration(node, field, e);
        if (dt instanceof Datatype.DatumInDagen) return date(node, field);
        if (dt instanceof Datatype.DatumTijd)    return dateTime(node, field);

        return Result.error(Violation.van(Violation.Kind.NIET_ONDERSTEUND, field,
                "datatype " + dt + " wordt niet ondersteund door de dienst-kern"));
    }

    // ---- numeric (+ eenheid) ----

    private static Result numeric(JsonNode node, String field, Eenheid declared) {
        // an eenheid-bearing value may arrive as {"waarde": n, "eenheid": "afk"}
        if (node.isObject()) {
            JsonNode w = node.get("waarde");
            JsonNode e = node.get("eenheid");
            if (w == null)
                return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                        "eenheid-object mist 'waarde'"));
            if (declared == null)
                return Result.error(Violation.van(Violation.Kind.VERKEERDE_EENHEID, field,
                        "attribuut heeft geen eenheid, maar de waarde geeft er wel een op"));
            if (e == null || !e.isTextual())
                return Result.error(Violation.van(Violation.Kind.VERKEERDE_EENHEID, field,
                        "eenheid-object mist een tekstuele 'eenheid'"));
            String requested = e.asText();
            if (!requested.equals(declared.weergave()))
                return Result.error(Violation.van(Violation.Kind.VERKEERDE_EENHEID, field,
                        "eenheid '" + requested + "' komt niet overeen met de gedeclareerde eenheid '"
                                + declared.weergave() + "'"));
            return number(w, field);
        }
        return number(node, field);
    }

    private static Result number(JsonNode node, String field) {
        if (node.isNumber()) return Result.ok(node.decimalValue());   // BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS)
        if (node.isTextual()) {
            try { return Result.ok(new BigDecimal(node.asText())); }
            catch (NumberFormatException nfe) { /* fall through */ }
        }
        return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                "verwacht een getal, kreeg " + describe(node)));
    }

    /** A Percentage's eenheid is always '%' (optionally a rate); a plain number in a request is the percentage
     *  value itself, so we only enforce a supplied eenheid object against the declared weergave. */
    private static Eenheid percentUnit(Eenheid e) { return e; }

    // ---- boolean / tekst / enum ----

    private static Result decodeBoolean(JsonNode node, String field) {
        if (node.isBoolean()) return Result.ok(node.booleanValue());
        return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                "verwacht een boolean, kreeg " + describe(node)));
    }

    private static Result text(JsonNode node, String field) {
        if (node.isTextual()) return Result.ok(node.asText());
        return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                "verwacht tekst, kreeg " + describe(node)));
    }

    private static Result enumeration(JsonNode node, String field, Datatype.Enumeratie e) {
        if (!node.isTextual())
            return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                    "verwacht een enumeratie-waarde (tekst), kreeg " + describe(node)));
        String v = node.asText();
        if (e.waarden().stream().noneMatch(w -> Namen.norm(w).equals(Namen.norm(v))))
            return Result.error(Violation.van(Violation.Kind.ONBEKENDE_ENUM_WAARDE, field,
                    "'" + v + "' is geen waarde van domein '" + e.domein() + "' (toegestaan: " + e.waarden() + ")"));
        return Result.ok(v);
    }

    // ---- dates ----

    private static Result date(JsonNode node, String field) {
        if (!node.isTextual())
            return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                    "verwacht een ISO-datum (yyyy-MM-dd), kreeg " + describe(node)));
        try { return Result.ok(LocalDate.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                    "'" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)"));
        }
    }

    private static Result dateTime(JsonNode node, String field) {
        if (!node.isTextual())
            return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                    "verwacht een ISO-datumtijd (yyyy-MM-ddThh:mm:ss), kreeg " + describe(node)));
        try { return Result.ok(LocalDateTime.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return Result.error(Violation.van(Violation.Kind.VERKEERD_TYPE, field,
                    "'" + node.asText() + "' is geen geldige ISO-datumtijd (yyyy-MM-ddThh:mm:ss[.SSS])"));
        }
    }

    private static String describe(JsonNode n) {
        return n.getNodeType().toString().toLowerCase() + " (" + n.toString() + ")";
    }
}
