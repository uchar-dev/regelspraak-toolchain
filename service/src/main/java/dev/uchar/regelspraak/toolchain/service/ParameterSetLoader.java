package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.ir.Validity;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.ParameterSet.ValueInterval;
import dev.uchar.regelspraak.model.RegelSpraakModel;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Leest een parameterset-sidecar ({@code <naam>.parameters.json}) en valideert hem tegen het GERESOLVEERDE model —
 * dezelfde Jackson/JSON-story als de dienst-descriptor. De JSON-shape is:
 * <pre>
 * { "parameterSets": [
 *     { "name": "...",
 *       "values": { "&lt;parameternaam&gt;": [
 *           { "value": &lt;json&gt;, "from": "yyyy-MM-dd"?, "to": "yyyy-MM-dd"? }, ... ] } } ] }
 * </pre>
 * {@code from}/{@code tot} zijn optioneel/{@code null} = open (de {@link Geldigheid}-shape); beide weggelaten = geldig
 * altijd ({@link Geldigheid#ALTIJD}).
 *
 * <p>Fouten worden VERZAMELEND gerapporteerd (alle eerst, dan één {@link ParameterSetError}), house-style: elke fout
 * noemt de overtreder + locatie. Malformed JSON mag direct één throw zijn. Gedekte fouten: onbekende parameternaam,
 * verkeerd getypeerde waarde, verkeerde eenheid, ongeldige/omgekeerde from/to, en overlap (via
 * {@link ParameterSet#overlapErrors}, ná decode). Een tijdsafhankelijke parameter ({@code voor elke maand}) staat er niet
 * in: een set geeft de jaargang die de peildatum kiest, en uchars regels lezen zo'n parameter heel, als tijdlijn.
 *
 * <p>Een parameternaam en een waarde lezen zoals de descriptor-defaults ze lezen: de naam zoals
 * {@link DescriptorLoader#parameter} hem vindt, genormaliseerd naar de CANONIEKE {@link RegelSpraakModel.Parameter#name()},
 * en de waarde zoals {@link RequestMapper#parameterSetValue} hem leest (een enumeratiewaarde via de {@link NameReader},
 * de rest via {@link ValueCoder}; JSON {@code null} = expliciete leegwaarde).
 */
public final class ParameterSetLoader {

    /** ObjectMapper spiegelt DescriptorLoader.MAPPER: BigDecimal-voor-floats, onbekende velden negeren. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ParameterSetLoader() {}

    /** Alle verzamelde parameterset-fouten in één worp; {@link #errors()} noemt elke overtreder + locatie. */
    public static final class ParameterSetError extends RuntimeException {
        private final List<String> errors;
        ParameterSetError(String message, List<String> errors) {
            super(message + ": " + String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }
        public List<String> errors() { return errors; }
    }

    /** Parse + valideer parametersets uit hun JSON-tekst tegen {@code model}. */
    public static List<ParameterSet> load(String json, RegelSpraakModel model) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ParameterSetError("parameterSets zijn geen geldige JSON", List.of(e.getMessage()));
        }
        if (root == null || !root.isObject())
            throw new ParameterSetError("parameterSets zijn geen geldige JSON",
                    List.of("verwacht een JSON-object met sleutel 'parameterSets'"));

        List<String> errors = new ArrayList<>();
        List<ParameterSet> sets = new ArrayList<>();

        JsonNode arr = root.get("parameterSets");
        if (arr == null || !arr.isArray()) {
            errors.add("veld 'parameterSets' ontbreekt of is geen lijst");
            throw new ParameterSetError("parameterSets valideren niet tegen het model", errors);
        }

        for (int i = 0; i < arr.size(); i++) {
            JsonNode setNode = arr.get(i);
            String path = "parameterSets[" + i + "]";
            JsonNode nameNode = setNode.get("name");
            String setName = nameNode != null && nameNode.isTextual() ? nameNode.asText() : ("set#" + i);
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank())
                errors.add(path + ".name ontbreekt");

            java.util.Map<String, List<ValueInterval>> values = new java.util.LinkedHashMap<>();
            JsonNode valuesNode = setNode.get("values");
            if (valuesNode != null && valuesNode.isObject()) {
                var fields = valuesNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String paramName = entry.getKey();
                    String valuesPath = path + ".values." + paramName;
                    RegelSpraakModel.Parameter param = DescriptorLoader.parameter(model, paramName);
                    if (param == null) {
                        errors.add(valuesPath + ": onbekende parameter '" + paramName + "'");
                        continue;
                    }
                    // a set gives the vintage an asOfDate chooses; the rules read a time-dependent parameter whole, as
                    // its timeline, which a request or the descriptor gives
                    if (param.granularity() != null) {
                        errors.add(valuesPath + ": parameter '" + param.name() + "' is tijdsafhankelijk; de regels"
                                + " lezen zijn hele tijdlijn, en een parameterset geeft één waarde op de peildatum."
                                + " Geef de tijdlijn in het verzoek of als default in de descriptor");
                        continue;
                    }
                    String canonical = param.name();   // normaliseer naar de canonieke model-naam
                    List<ValueInterval> intervals =
                            values.computeIfAbsent(canonical, k -> new ArrayList<>());
                    JsonNode listNode = entry.getValue();
                    if (listNode == null || !listNode.isArray()) {
                        errors.add(valuesPath + ": verwacht een lijst intervallen");
                        continue;
                    }
                    for (int j = 0; j < listNode.size(); j++) {
                        ValueInterval wi = decodeInterval(listNode.get(j), model, param, valuesPath + "[" + j + "]", errors);
                        if (wi != null) intervals.add(wi);
                    }
                }
            }
            sets.add(new ParameterSet(setName, values));
        }

        // overlap wordt PAS ná decode over de UNIE per parameter gecheckt.
        errors.addAll(ParameterSet.overlapErrors(sets));

        if (!errors.isEmpty())
            throw new ParameterSetError("parameterSets valideren niet tegen het model", errors);
        return sets;
    }

    /** Decodeer één {@code {value, from?, to?}}-interval; {@code null} bij een fout (die aan {@code errors} is
     *  toegevoegd). */
    private static ValueInterval decodeInterval(JsonNode node, RegelSpraakModel model, RegelSpraakModel.Parameter param,
                                                   String path, List<String> errors) {
        if (node == null || !node.isObject()) {
            errors.add(path + ": verwacht een object {value, from?, to?}");
            return null;
        }
        boolean ok = true;

        LocalDate from = dateOrNull(node.get("from"), path + ".from", errors);
        if (node.has("from") && !node.get("from").isNull() && from == null) ok = false;
        LocalDate to = dateOrNull(node.get("to"), path + ".to", errors);
        if (node.has("to") && !node.get("to").isNull() && to == null) ok = false;
        if (from != null && to != null && from.isAfter(to)) {
            errors.add(path + ": from " + from + " ligt na to " + to);
            ok = false;
        }

        if (!node.has("value")) {
            errors.add(path + ": veld 'value' ontbreekt");
            ok = false;
        }
        Object value = null;
        if (node.has("value")) {
            List<Violation> violations = new ArrayList<>();
            value = RequestMapper.parameterSetValue(model, param, node.get("value"), path + ".value", violations);
            for (Violation v : violations) errors.add(v.field() + ": " + v.message());
            if (!violations.isEmpty()) ok = false;
        }

        if (!ok) return null;
        return new ValueInterval(value, new Validity(from, to));
    }

    private static LocalDate dateOrNull(JsonNode node, String path, List<String> errors) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) { errors.add(path + ": verwacht een ISO-datum (yyyy-MM-dd)"); return null; }
        try { return LocalDate.parse(node.asText()); }
        catch (DateTimeParseException e) {
            errors.add(path + ": '" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)");
            return null;
        }
    }
}
