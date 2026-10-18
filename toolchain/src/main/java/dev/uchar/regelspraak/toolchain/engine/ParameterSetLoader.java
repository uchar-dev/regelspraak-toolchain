package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.ir.Validity;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Unit;
import dev.uchar.regelspraak.model.ParameterSet;
import dev.uchar.regelspraak.model.ParameterSet.ValueInterval;
import dev.uchar.regelspraak.model.RegelSpraakModel;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Leest een parameterset-sidecar ({@code <naam>.parameters.json}) en valideert hem tegen het GERESOLVEERDE model —
 * dezelfde Jackson/JSON-story als de dienst-descriptor. De JSON-shape is:
 * <pre>
 * { "parametersets": [
 *     { "naam": "...",
 *       "waarden": { "&lt;parameternaam&gt;": [
 *           { "waarde": &lt;json&gt;, "vanaf": "yyyy-MM-dd"?, "tot": "yyyy-MM-dd"? }, ... ] } } ] }
 * </pre>
 * {@code from}/{@code tot} zijn optioneel/{@code null} = open (de {@link Geldigheid}-shape); beide weggelaten = geldig
 * altijd ({@link Geldigheid#ALTIJD}).
 *
 * <p>Fouten worden VERZAMELEND gerapporteerd (alle eerst, dan één {@link ParameterSetError}), house-style: elke fout
 * noemt de overtreder + locatie. Malformed JSON mag direct één throw zijn. Gedekte fouten: onbekende parameternaam,
 * verkeerd getypeerde waarde, verkeerde eenheid, ongeldige/omgekeerde vanaf/tot, en overlap (via
 * {@link ParameterSet#overlapErrors}, ná decode).
 *
 * <p>De waarde-decodering spiegelt {@code dev.uchar.regelspraak.toolchain.service.ValueCoder} (Numeriek/Percentage → BigDecimal
 * met eenheid-objectvorm {@code {"waarde": n, "eenheid": "<afk>"}} waarvan de eenheid EXACT de gedeclareerde
 * {@link Eenheid#weergave()} moet zijn; Booleaan → Boolean; Tekst → String; Enumeratie → String in {@code values()};
 * DatumInDagen → {@link LocalDate}; DatumTijd → {@link LocalDateTime}; JSON {@code null} = expliciete leegwaarde).
 * ValueCoder zelf leeft in de service-module (die de engine niet mag importeren) en retourneert een service-type
 * Schending; deze lader dupliceert de decode met String-fouten.
 *
 * <p>De parameter-lookup dupliceert bewust de 8-regels case-insensitieve tolerantie van
 * {@code dev.uchar.regelspraak.toolchain.service.DescriptorLoader#parameter(RegelSpraakModel, String)} (exacte voorkeur, dan
 * case-insensitief), omdat de engine de service-module niet mag importeren. Keys worden genormaliseerd naar de
 * CANONIEKE {@link RegelSpraakModel.Parameter#naam()} zodat consumers global-namen kunnen afleiden.
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

    /**
     * De RUWE parameternamen (zoals opgeschreven) onder elke {@code parametersets[i].values}, PUUR structureel —
     * zonder een model, vóór de typed {@link #load}. De CLI/service heeft deze namen nodig om
     * {@link dev.uchar.regelspraak.resolver.ScenarioResolver#resolve(String, java.util.Set)} met de externe parameters
     * te draaien VÓÓR het model bestaat; daarna volgt de volledige typed laad tegen {@code bundle.model()}. Malformed
     * JSON of een ontbrekende/ongeldige {@code parametersets}-lijst is een {@link ParameterSetError}. De namen zijn
     * ruw (niet gecanoniseerd — dat kan pas met een model); de resolver normaliseert ze zelf.
     */
    public static List<String> parameterNames(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ParameterSetError("parametersets zijn geen geldige JSON", List.of(e.getMessage()));
        }
        if (root == null || !root.isObject())
            throw new ParameterSetError("parametersets zijn geen geldige JSON",
                    List.of("verwacht een JSON-object met sleutel 'parametersets'"));
        JsonNode arr = root.get("parametersets");
        if (arr == null || !arr.isArray())
            throw new ParameterSetError("parametersets valideren niet",
                    List.of("veld 'parametersets' ontbreekt of is geen lijst"));
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode setNode : arr) {
            JsonNode valuesNode = setNode.get("waarden");
            if (valuesNode != null && valuesNode.isObject()) {
                var fields = valuesNode.fieldNames();
                while (fields.hasNext()) names.add(fields.next());
            }
        }
        return new ArrayList<>(names);
    }

    /** Parse + valideer een parameterset-bestand tegen {@code model}. */
    public static List<ParameterSet> load(Path file, RegelSpraakModel model) throws IOException {
        return load(Files.readString(file), model);
    }

    /** Parse + valideer parametersets uit hun JSON-tekst tegen {@code model}. */
    public static List<ParameterSet> load(String json, RegelSpraakModel model) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ParameterSetError("parametersets zijn geen geldige JSON", List.of(e.getMessage()));
        }
        if (root == null || !root.isObject())
            throw new ParameterSetError("parametersets zijn geen geldige JSON",
                    List.of("verwacht een JSON-object met sleutel 'parametersets'"));

        List<String> errors = new ArrayList<>();
        List<ParameterSet> sets = new ArrayList<>();

        JsonNode arr = root.get("parametersets");
        if (arr == null || !arr.isArray()) {
            errors.add("veld 'parametersets' ontbreekt of is geen lijst");
            throw new ParameterSetError("parametersets valideren niet tegen het model", errors);
        }

        for (int i = 0; i < arr.size(); i++) {
            JsonNode setNode = arr.get(i);
            String path = "parametersets[" + i + "]";
            JsonNode nameNode = setNode.get("naam");
            String setName = nameNode != null && nameNode.isTextual() ? nameNode.asText() : ("set#" + i);
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank())
                errors.add(path + ".naam ontbreekt");

            java.util.Map<String, List<ValueInterval>> values = new java.util.LinkedHashMap<>();
            JsonNode valuesNode = setNode.get("waarden");
            if (valuesNode != null && valuesNode.isObject()) {
                var fields = valuesNode.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String paramName = entry.getKey();
                    String wpad = path + ".waarden." + paramName;
                    RegelSpraakModel.Parameter param = parameter(model, paramName);
                    if (param == null) {
                        errors.add(wpad + ": onbekende parameter '" + paramName + "'");
                        continue;
                    }
                    String canonical = param.naam();   // normaliseer naar de canonieke model-naam
                    List<ValueInterval> intervals =
                            values.computeIfAbsent(canonical, k -> new ArrayList<>());
                    JsonNode listNode = entry.getValue();
                    if (listNode == null || !listNode.isArray()) {
                        errors.add(wpad + ": verwacht een lijst intervallen");
                        continue;
                    }
                    for (int j = 0; j < listNode.size(); j++) {
                        ValueInterval wi = decodeerInterval(listNode.get(j), param, wpad + "[" + j + "]", errors);
                        if (wi != null) intervals.add(wi);
                    }
                }
            }
            sets.add(new ParameterSet(setName, values));
        }

        // overlap wordt PAS ná decode over de UNIE per parameter gecheckt.
        errors.addAll(ParameterSet.overlapErrors(sets));

        if (!errors.isEmpty())
            throw new ParameterSetError("parametersets valideren niet tegen het model", errors);
        return sets;
    }

    /** Decodeer één {@code {waarde, vanaf?, tot?}}-interval; {@code null} bij een fout (die aan {@code errors} is
     *  toegevoegd). */
    private static ValueInterval decodeerInterval(JsonNode node, RegelSpraakModel.Parameter param,
                                                   String path, List<String> errors) {
        if (node == null || !node.isObject()) {
            errors.add(path + ": verwacht een object {waarde, vanaf?, tot?}");
            return null;
        }
        boolean ok = true;

        LocalDate from = dateOrNull(node.get("vanaf"), path + ".vanaf", errors);
        if (node.has("vanaf") && !node.get("vanaf").isNull() && from == null) ok = false;
        LocalDate to = dateOrNull(node.get("tot"), path + ".tot", errors);
        if (node.has("tot") && !node.get("tot").isNull() && to == null) ok = false;
        if (from != null && to != null && from.isAfter(to)) {
            errors.add(path + ": vanaf " + from + " ligt na tot " + to);
            ok = false;
        }

        if (!node.has("waarde")) {
            errors.add(path + ": veld 'waarde' ontbreekt");
            ok = false;
        }
        Object value = null;
        if (node.has("waarde")) {
            DecodeResult dr = decodeValue(node.get("waarde"), param.datatype(), path + ".waarde");
            if (dr.error != null) { errors.add(dr.error); ok = false; } else value = dr.value;
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

    // ---- waarde-decodering, spiegelt service/ValueCoder (met String-fouten i.p.v. Schending) ----

    /** De gedecodeerde waarde OF een String-fout (nooit beide). */
    private record DecodeResult(Object value, String error) {
        static DecodeResult ok(Object w) { return new DecodeResult(w, null); }
        static DecodeResult error(String f) { return new DecodeResult(null, f); }
    }

    private static DecodeResult decodeValue(JsonNode node, Datatype dt, String field) {
        if (node == null || node.isNull()) return DecodeResult.ok(null);   // expliciete leegwaarde

        if (dt instanceof Datatype.Numeriek n)   return numeric(node, field, n.eenheid());
        if (dt instanceof Datatype.Percentage p) return numeric(node, field, p.eenheid());
        if (dt instanceof Datatype.BooleanType)     return decodeBoolean(node, field);
        if (dt instanceof Datatype.Tekst)        return text(node, field);
        if (dt instanceof Datatype.Enumeratie e) return enumeration(node, field, e);
        if (dt instanceof Datatype.DatumInDagen) return date(node, field);
        if (dt instanceof Datatype.DateTime)    return dateTime(node, field);

        return DecodeResult.error(field + ": datatype " + dt + " wordt niet ondersteund");
    }

    private static DecodeResult numeric(JsonNode node, String field, Unit declared) {
        if (node.isObject()) {
            JsonNode w = node.get("waarde");
            JsonNode e = node.get("eenheid");
            if (w == null)
                return DecodeResult.error(field + ": eenheid-object mist 'waarde'");
            if (declared == null)
                return DecodeResult.error(field + ": parameter heeft geen eenheid, maar de waarde geeft er wel een op");
            if (e == null || !e.isTextual())
                return DecodeResult.error(field + ": eenheid-object mist een tekstuele 'eenheid'");
            String requested = e.asText();
            if (!requested.equals(declared.rendering()))
                return DecodeResult.error(field + ": eenheid '" + requested + "' komt niet overeen met de gedeclareerde"
                        + " eenheid '" + declared.rendering() + "'");
            return number(w, field);
        }
        return number(node, field);
    }

    private static DecodeResult number(JsonNode node, String field) {
        if (node.isNumber()) return DecodeResult.ok(node.decimalValue());
        if (node.isTextual()) {
            try { return DecodeResult.ok(new BigDecimal(node.asText())); }
            catch (NumberFormatException nfe) { /* val door */ }
        }
        return DecodeResult.error(field + ": verwacht een getal, kreeg " + describe(node));
    }

    private static DecodeResult decodeBoolean(JsonNode node, String field) {
        if (node.isBoolean()) return DecodeResult.ok(node.booleanValue());
        return DecodeResult.error(field + ": verwacht een boolean, kreeg " + describe(node));
    }

    private static DecodeResult text(JsonNode node, String field) {
        if (node.isTextual()) return DecodeResult.ok(node.asText());
        return DecodeResult.error(field + ": verwacht tekst, kreeg " + describe(node));
    }

    private static DecodeResult enumeration(JsonNode node, String field, Datatype.Enumeratie e) {
        if (!node.isTextual())
            return DecodeResult.error(field + ": verwacht een enumeratie-waarde (tekst), kreeg " + describe(node));
        String v = node.asText();
        if (!e.waarden().contains(v))
            return DecodeResult.error(field + ": '" + v + "' is geen waarde van domein '" + e.domain()
                    + "' (toegestaan: " + e.waarden() + ")");
        return DecodeResult.ok(v);
    }

    private static DecodeResult date(JsonNode node, String field) {
        if (!node.isTextual())
            return DecodeResult.error(field + ": verwacht een ISO-datum (yyyy-MM-dd), kreeg " + describe(node));
        try { return DecodeResult.ok(LocalDate.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return DecodeResult.error(field + ": '" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)");
        }
    }

    private static DecodeResult dateTime(JsonNode node, String field) {
        if (!node.isTextual())
            return DecodeResult.error(field + ": verwacht een ISO-datumtijd (yyyy-MM-ddThh:mm:ss), kreeg "
                    + describe(node));
        try { return DecodeResult.ok(LocalDateTime.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return DecodeResult.error(field + ": '" + node.asText()
                    + "' is geen geldige ISO-datumtijd (yyyy-MM-ddThh:mm:ss[.SSS])");
        }
    }

    private static String describe(JsonNode n) {
        return n.getNodeType().toString().toLowerCase() + " (" + n.toString() + ")";
    }

    // ---- parameter-lookup: case-insensitieve tolerantie, gedupliceerd uit service/DescriptorLoader#parameter ----

    /** Zoek een parameter op naam: exacte voorkeur, dan case-insensitief (spiegelt
     *  {@code DescriptorLoader.parameter}; de engine mag de service-module niet importeren). */
    private static RegelSpraakModel.Parameter parameter(RegelSpraakModel model, String name) {
        if (name == null) return null;
        RegelSpraakModel.Parameter p = model.parameters.get(name);
        if (p != null) return p;
        for (RegelSpraakModel.Parameter q : model.parameters.values())
            if (q.naam().equalsIgnoreCase(name)) return q;
        return null;
    }
}
