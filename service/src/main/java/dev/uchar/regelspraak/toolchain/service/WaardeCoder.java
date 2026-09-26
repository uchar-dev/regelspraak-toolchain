package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Eenheid;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Decodes ONE JSON value into the Java value the fact bean setter (and the exec-model bridge) expects for a
 * given {@link Datatype}, collecting a typed {@link Schending} (with its field path) on any mismatch instead
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
 *   <li><b>Enumeratie</b> → the {@link String} literal, which must be one of the domein's waarden.</li>
 *   <li><b>Datum-in-dagen</b> → {@link LocalDate} (ISO-8601 {@code yyyy-MM-dd}).</li>
 *   <li><b>Datum-en-tijd</b> → {@link LocalDateTime} (ISO-8601 {@code yyyy-MM-ddThh:mm:ss[.SSS]}).</li>
 * </ul>
 * A JSON {@code null} decodes to a Java {@code null} (an explicit leegwaarde) for any datatype.
 */
public final class WaardeCoder {

    private WaardeCoder() {}

    /** The decoded value plus the violations produced while decoding (empty on success). A failed decode
     *  returns {@code waarde == null} AND a non-empty {@link #schendingen}. */
    public record Resultaat(Object waarde, List<Schending> schendingen) {
        static Resultaat ok(Object w) { return new Resultaat(w, List.of()); }
        static Resultaat fout(Schending s) { return new Resultaat(null, List.of(s)); }
    }

    /**
     * Decode {@code node} as a value of {@code dt}. {@code veld} is the request field path used in any violation.
     */
    public static Resultaat decodeer(JsonNode node, Datatype dt, String veld) {
        if (node == null || node.isNull()) return Resultaat.ok(null);   // explicit leegwaarde

        if (dt instanceof Datatype.Numeriek n)   return numeriek(node, veld, n.eenheid());
        if (dt instanceof Datatype.Percentage p) return numeriek(node, veld, procentEenheid(p.eenheid()));
        if (dt instanceof Datatype.Booleaan)     return booleaan(node, veld);
        if (dt instanceof Datatype.Tekst)        return tekst(node, veld);
        if (dt instanceof Datatype.Enumeratie e) return enumeratie(node, veld, e);
        if (dt instanceof Datatype.DatumInDagen) return datum(node, veld);
        if (dt instanceof Datatype.DatumTijd)    return datumTijd(node, veld);

        return Resultaat.fout(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                "datatype " + dt + " wordt niet ondersteund door de dienst-kern"));
    }

    // ---- numeric (+ eenheid) ----

    private static Resultaat numeriek(JsonNode node, String veld, Eenheid declared) {
        // an eenheid-bearing value may arrive as {"waarde": n, "eenheid": "afk"}
        if (node.isObject()) {
            JsonNode w = node.get("waarde");
            JsonNode e = node.get("eenheid");
            if (w == null)
                return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                        "eenheid-object mist 'waarde'"));
            if (declared == null)
                return Resultaat.fout(Schending.van(Schending.Soort.VERKEERDE_EENHEID, veld,
                        "attribuut heeft geen eenheid, maar de waarde geeft er wel een op"));
            if (e == null || !e.isTextual())
                return Resultaat.fout(Schending.van(Schending.Soort.VERKEERDE_EENHEID, veld,
                        "eenheid-object mist een tekstuele 'eenheid'"));
            String gevraagd = e.asText();
            if (!gevraagd.equals(declared.weergave()))
                return Resultaat.fout(Schending.van(Schending.Soort.VERKEERDE_EENHEID, veld,
                        "eenheid '" + gevraagd + "' komt niet overeen met de gedeclareerde eenheid '"
                                + declared.weergave() + "'"));
            return getal(w, veld);
        }
        return getal(node, veld);
    }

    private static Resultaat getal(JsonNode node, String veld) {
        if (node.isNumber()) return Resultaat.ok(node.decimalValue());   // BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS)
        if (node.isTextual()) {
            try { return Resultaat.ok(new BigDecimal(node.asText())); }
            catch (NumberFormatException nfe) { /* fall through */ }
        }
        return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                "verwacht een getal, kreeg " + omschrijf(node)));
    }

    /** A Percentage's eenheid is always '%' (optionally a rate); a plain number in a request is the percentage
     *  value itself, so we only enforce a supplied eenheid object against the declared weergave. */
    private static Eenheid procentEenheid(Eenheid e) { return e; }

    // ---- boolean / tekst / enum ----

    private static Resultaat booleaan(JsonNode node, String veld) {
        if (node.isBoolean()) return Resultaat.ok(node.booleanValue());
        return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                "verwacht een boolean, kreeg " + omschrijf(node)));
    }

    private static Resultaat tekst(JsonNode node, String veld) {
        if (node.isTextual()) return Resultaat.ok(node.asText());
        return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                "verwacht tekst, kreeg " + omschrijf(node)));
    }

    private static Resultaat enumeratie(JsonNode node, String veld, Datatype.Enumeratie e) {
        if (!node.isTextual())
            return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "verwacht een enumeratie-waarde (tekst), kreeg " + omschrijf(node)));
        String v = node.asText();
        if (!e.waarden().contains(v))
            return Resultaat.fout(Schending.van(Schending.Soort.ONBEKENDE_ENUM_WAARDE, veld,
                    "'" + v + "' is geen waarde van domein '" + e.domein() + "' (toegestaan: " + e.waarden() + ")"));
        return Resultaat.ok(v);
    }

    // ---- dates ----

    private static Resultaat datum(JsonNode node, String veld) {
        if (!node.isTextual())
            return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "verwacht een ISO-datum (yyyy-MM-dd), kreeg " + omschrijf(node)));
        try { return Resultaat.ok(LocalDate.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "'" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)"));
        }
    }

    private static Resultaat datumTijd(JsonNode node, String veld) {
        if (!node.isTextual())
            return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "verwacht een ISO-datumtijd (yyyy-MM-ddThh:mm:ss), kreeg " + omschrijf(node)));
        try { return Resultaat.ok(LocalDateTime.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return Resultaat.fout(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "'" + node.asText() + "' is geen geldige ISO-datumtijd (yyyy-MM-ddThh:mm:ss[.SSS])"));
        }
    }

    private static String omschrijf(JsonNode n) {
        return n.getNodeType().toString().toLowerCase() + " (" + n.toString() + ")";
    }
}
