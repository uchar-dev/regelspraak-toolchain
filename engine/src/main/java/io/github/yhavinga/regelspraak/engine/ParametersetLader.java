package io.github.yhavinga.regelspraak.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.model.Parameterset.WaardeInterval;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;

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
 * {@code vanaf}/{@code tot} zijn optioneel/{@code null} = open (de {@link Geldigheid}-shape); beide weggelaten = geldig
 * altijd ({@link Geldigheid#ALTIJD}).
 *
 * <p>Fouten worden VERZAMELEND gerapporteerd (alle eerst, dan één {@link ParametersetFout}), house-style: elke fout
 * noemt de overtreder + locatie. Malformed JSON mag direct één throw zijn. Gedekte fouten: onbekende parameternaam,
 * verkeerd getypeerde waarde, verkeerde eenheid, ongeldige/omgekeerde vanaf/tot, en overlap (via
 * {@link Parameterset#overlapFouten}, ná decode).
 *
 * <p>De waarde-decodering spiegelt {@code io.github.yhavinga.regelspraak.service.WaardeCoder} (Numeriek/Percentage → BigDecimal
 * met eenheid-objectvorm {@code {"waarde": n, "eenheid": "<afk>"}} waarvan de eenheid EXACT de gedeclareerde
 * {@link Eenheid#weergave()} moet zijn; Booleaan → Boolean; Tekst → String; Enumeratie → String in {@code waarden()};
 * DatumInDagen → {@link LocalDate}; DatumTijd → {@link LocalDateTime}; JSON {@code null} = expliciete leegwaarde).
 * WaardeCoder zelf leeft in de service-module (die de engine niet mag importeren) en retourneert een service-type
 * Schending; deze lader dupliceert de decode met String-fouten.
 *
 * <p>De parameter-lookup dupliceert bewust de 8-regels case-insensitieve tolerantie van
 * {@code io.github.yhavinga.regelspraak.service.DescriptorLoader#parameter(RegelSpraakModel, String)} (exacte voorkeur, dan
 * case-insensitief), omdat de engine de service-module niet mag importeren. Keys worden genormaliseerd naar de
 * CANONIEKE {@link RegelSpraakModel.Parameter#naam()} zodat consumers global-namen kunnen afleiden.
 */
public final class ParametersetLader {

    /** ObjectMapper spiegelt DescriptorLoader.MAPPER: BigDecimal-voor-floats, onbekende velden negeren. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ParametersetLader() {}

    /** Alle verzamelde parameterset-fouten in één worp; {@link #fouten()} noemt elke overtreder + locatie. */
    public static final class ParametersetFout extends RuntimeException {
        private final List<String> fouten;
        ParametersetFout(String bericht, List<String> fouten) {
            super(bericht + ": " + String.join("; ", fouten));
            this.fouten = List.copyOf(fouten);
        }
        public List<String> fouten() { return fouten; }
    }

    /**
     * De RUWE parameternamen (zoals opgeschreven) onder elke {@code parametersets[i].waarden}, PUUR structureel —
     * zonder een model, vóór de typed {@link #laad}. De CLI/service heeft deze namen nodig om
     * {@link io.github.yhavinga.regelspraak.resolver.ScenarioResolver#resolve(String, java.util.Set)} met de externe parameters
     * te draaien VÓÓR het model bestaat; daarna volgt de volledige typed laad tegen {@code bundel.model()}. Malformed
     * JSON of een ontbrekende/ongeldige {@code parametersets}-lijst is een {@link ParametersetFout}. De namen zijn
     * ruw (niet gecanoniseerd — dat kan pas met een model); de resolver normaliseert ze zelf.
     */
    public static List<String> parameterNamen(String json) {
        JsonNode wortel;
        try {
            wortel = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ParametersetFout("parametersets zijn geen geldige JSON", List.of(e.getMessage()));
        }
        if (wortel == null || !wortel.isObject())
            throw new ParametersetFout("parametersets zijn geen geldige JSON",
                    List.of("verwacht een JSON-object met sleutel 'parametersets'"));
        JsonNode arr = wortel.get("parametersets");
        if (arr == null || !arr.isArray())
            throw new ParametersetFout("parametersets valideren niet",
                    List.of("veld 'parametersets' ontbreekt of is geen lijst"));
        java.util.LinkedHashSet<String> namen = new java.util.LinkedHashSet<>();
        for (JsonNode setNode : arr) {
            JsonNode waardenNode = setNode.get("waarden");
            if (waardenNode != null && waardenNode.isObject()) {
                var velden = waardenNode.fieldNames();
                while (velden.hasNext()) namen.add(velden.next());
            }
        }
        return new ArrayList<>(namen);
    }

    /** Parse + valideer een parameterset-bestand tegen {@code model}. */
    public static List<Parameterset> laad(Path bestand, RegelSpraakModel model) throws IOException {
        return laad(Files.readString(bestand), model);
    }

    /** Parse + valideer parametersets uit hun JSON-tekst tegen {@code model}. */
    public static List<Parameterset> laad(String json, RegelSpraakModel model) {
        JsonNode wortel;
        try {
            wortel = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ParametersetFout("parametersets zijn geen geldige JSON", List.of(e.getMessage()));
        }
        if (wortel == null || !wortel.isObject())
            throw new ParametersetFout("parametersets zijn geen geldige JSON",
                    List.of("verwacht een JSON-object met sleutel 'parametersets'"));

        List<String> fouten = new ArrayList<>();
        List<Parameterset> sets = new ArrayList<>();

        JsonNode arr = wortel.get("parametersets");
        if (arr == null || !arr.isArray()) {
            fouten.add("veld 'parametersets' ontbreekt of is geen lijst");
            throw new ParametersetFout("parametersets valideren niet tegen het model", fouten);
        }

        for (int i = 0; i < arr.size(); i++) {
            JsonNode setNode = arr.get(i);
            String pad = "parametersets[" + i + "]";
            JsonNode naamNode = setNode.get("naam");
            String setNaam = naamNode != null && naamNode.isTextual() ? naamNode.asText() : ("set#" + i);
            if (naamNode == null || !naamNode.isTextual() || naamNode.asText().isBlank())
                fouten.add(pad + ".naam ontbreekt");

            java.util.Map<String, List<WaardeInterval>> waarden = new java.util.LinkedHashMap<>();
            JsonNode waardenNode = setNode.get("waarden");
            if (waardenNode != null && waardenNode.isObject()) {
                var velden = waardenNode.fields();
                while (velden.hasNext()) {
                    var entry = velden.next();
                    String paramNaam = entry.getKey();
                    String wpad = pad + ".waarden." + paramNaam;
                    RegelSpraakModel.Parameter param = parameter(model, paramNaam);
                    if (param == null) {
                        fouten.add(wpad + ": onbekende parameter '" + paramNaam + "'");
                        continue;
                    }
                    String canoniek = param.naam();   // normaliseer naar de canonieke model-naam
                    List<WaardeInterval> intervallen =
                            waarden.computeIfAbsent(canoniek, k -> new ArrayList<>());
                    JsonNode lijstNode = entry.getValue();
                    if (lijstNode == null || !lijstNode.isArray()) {
                        fouten.add(wpad + ": verwacht een lijst intervallen");
                        continue;
                    }
                    for (int j = 0; j < lijstNode.size(); j++) {
                        WaardeInterval wi = decodeerInterval(lijstNode.get(j), param, wpad + "[" + j + "]", fouten);
                        if (wi != null) intervallen.add(wi);
                    }
                }
            }
            sets.add(new Parameterset(setNaam, waarden));
        }

        // overlap wordt PAS ná decode over de UNIE per parameter gecheckt.
        fouten.addAll(Parameterset.overlapFouten(sets));

        if (!fouten.isEmpty())
            throw new ParametersetFout("parametersets valideren niet tegen het model", fouten);
        return sets;
    }

    /** Decodeer één {@code {waarde, vanaf?, tot?}}-interval; {@code null} bij een fout (die aan {@code fouten} is
     *  toegevoegd). */
    private static WaardeInterval decodeerInterval(JsonNode node, RegelSpraakModel.Parameter param,
                                                   String pad, List<String> fouten) {
        if (node == null || !node.isObject()) {
            fouten.add(pad + ": verwacht een object {waarde, vanaf?, tot?}");
            return null;
        }
        boolean ok = true;

        LocalDate vanaf = datumOfNull(node.get("vanaf"), pad + ".vanaf", fouten);
        if (node.has("vanaf") && !node.get("vanaf").isNull() && vanaf == null) ok = false;
        LocalDate tot = datumOfNull(node.get("tot"), pad + ".tot", fouten);
        if (node.has("tot") && !node.get("tot").isNull() && tot == null) ok = false;
        if (vanaf != null && tot != null && vanaf.isAfter(tot)) {
            fouten.add(pad + ": vanaf " + vanaf + " ligt na tot " + tot);
            ok = false;
        }

        if (!node.has("waarde")) {
            fouten.add(pad + ": veld 'waarde' ontbreekt");
            ok = false;
        }
        Object waarde = null;
        if (node.has("waarde")) {
            DecodeResultaat dr = decodeerWaarde(node.get("waarde"), param.datatype(), pad + ".waarde");
            if (dr.fout != null) { fouten.add(dr.fout); ok = false; } else waarde = dr.waarde;
        }

        if (!ok) return null;
        return new WaardeInterval(waarde, new Geldigheid(vanaf, tot));
    }

    private static LocalDate datumOfNull(JsonNode node, String pad, List<String> fouten) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) { fouten.add(pad + ": verwacht een ISO-datum (yyyy-MM-dd)"); return null; }
        try { return LocalDate.parse(node.asText()); }
        catch (DateTimeParseException e) {
            fouten.add(pad + ": '" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)");
            return null;
        }
    }

    // ---- waarde-decodering, spiegelt service/WaardeCoder (met String-fouten i.p.v. Schending) ----

    /** De gedecodeerde waarde OF een String-fout (nooit beide). */
    private record DecodeResultaat(Object waarde, String fout) {
        static DecodeResultaat ok(Object w) { return new DecodeResultaat(w, null); }
        static DecodeResultaat fout(String f) { return new DecodeResultaat(null, f); }
    }

    private static DecodeResultaat decodeerWaarde(JsonNode node, Datatype dt, String veld) {
        if (node == null || node.isNull()) return DecodeResultaat.ok(null);   // expliciete leegwaarde

        if (dt instanceof Datatype.Numeriek n)   return numeriek(node, veld, n.eenheid());
        if (dt instanceof Datatype.Percentage p) return numeriek(node, veld, p.eenheid());
        if (dt instanceof Datatype.Booleaan)     return booleaan(node, veld);
        if (dt instanceof Datatype.Tekst)        return tekst(node, veld);
        if (dt instanceof Datatype.Enumeratie e) return enumeratie(node, veld, e);
        if (dt instanceof Datatype.DatumInDagen) return datum(node, veld);
        if (dt instanceof Datatype.DatumTijd)    return datumTijd(node, veld);

        return DecodeResultaat.fout(veld + ": datatype " + dt + " wordt niet ondersteund");
    }

    private static DecodeResultaat numeriek(JsonNode node, String veld, Eenheid declared) {
        if (node.isObject()) {
            JsonNode w = node.get("waarde");
            JsonNode e = node.get("eenheid");
            if (w == null)
                return DecodeResultaat.fout(veld + ": eenheid-object mist 'waarde'");
            if (declared == null)
                return DecodeResultaat.fout(veld + ": parameter heeft geen eenheid, maar de waarde geeft er wel een op");
            if (e == null || !e.isTextual())
                return DecodeResultaat.fout(veld + ": eenheid-object mist een tekstuele 'eenheid'");
            String gevraagd = e.asText();
            if (!gevraagd.equals(declared.weergave()))
                return DecodeResultaat.fout(veld + ": eenheid '" + gevraagd + "' komt niet overeen met de gedeclareerde"
                        + " eenheid '" + declared.weergave() + "'");
            return getal(w, veld);
        }
        return getal(node, veld);
    }

    private static DecodeResultaat getal(JsonNode node, String veld) {
        if (node.isNumber()) return DecodeResultaat.ok(node.decimalValue());
        if (node.isTextual()) {
            try { return DecodeResultaat.ok(new BigDecimal(node.asText())); }
            catch (NumberFormatException nfe) { /* val door */ }
        }
        return DecodeResultaat.fout(veld + ": verwacht een getal, kreeg " + omschrijf(node));
    }

    private static DecodeResultaat booleaan(JsonNode node, String veld) {
        if (node.isBoolean()) return DecodeResultaat.ok(node.booleanValue());
        return DecodeResultaat.fout(veld + ": verwacht een boolean, kreeg " + omschrijf(node));
    }

    private static DecodeResultaat tekst(JsonNode node, String veld) {
        if (node.isTextual()) return DecodeResultaat.ok(node.asText());
        return DecodeResultaat.fout(veld + ": verwacht tekst, kreeg " + omschrijf(node));
    }

    private static DecodeResultaat enumeratie(JsonNode node, String veld, Datatype.Enumeratie e) {
        if (!node.isTextual())
            return DecodeResultaat.fout(veld + ": verwacht een enumeratie-waarde (tekst), kreeg " + omschrijf(node));
        String v = node.asText();
        if (!e.waarden().contains(v))
            return DecodeResultaat.fout(veld + ": '" + v + "' is geen waarde van domein '" + e.domein()
                    + "' (toegestaan: " + e.waarden() + ")");
        return DecodeResultaat.ok(v);
    }

    private static DecodeResultaat datum(JsonNode node, String veld) {
        if (!node.isTextual())
            return DecodeResultaat.fout(veld + ": verwacht een ISO-datum (yyyy-MM-dd), kreeg " + omschrijf(node));
        try { return DecodeResultaat.ok(LocalDate.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return DecodeResultaat.fout(veld + ": '" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)");
        }
    }

    private static DecodeResultaat datumTijd(JsonNode node, String veld) {
        if (!node.isTextual())
            return DecodeResultaat.fout(veld + ": verwacht een ISO-datumtijd (yyyy-MM-ddThh:mm:ss), kreeg "
                    + omschrijf(node));
        try { return DecodeResultaat.ok(LocalDateTime.parse(node.asText())); }
        catch (DateTimeParseException ex) {
            return DecodeResultaat.fout(veld + ": '" + node.asText()
                    + "' is geen geldige ISO-datumtijd (yyyy-MM-ddThh:mm:ss[.SSS])");
        }
    }

    private static String omschrijf(JsonNode n) {
        return n.getNodeType().toString().toLowerCase() + " (" + n.toString() + ")";
    }

    // ---- parameter-lookup: case-insensitieve tolerantie, gedupliceerd uit service/DescriptorLoader#parameter ----

    /** Zoek een parameter op naam: exacte voorkeur, dan case-insensitief (spiegelt
     *  {@code DescriptorLoader.parameter}; de engine mag de service-module niet importeren). */
    private static RegelSpraakModel.Parameter parameter(RegelSpraakModel model, String naam) {
        if (naam == null) return null;
        RegelSpraakModel.Parameter p = model.parameters.get(naam);
        if (p != null) return p;
        for (RegelSpraakModel.Parameter q : model.parameters.values())
            if (q.naam().equalsIgnoreCase(naam)) return q;
        return null;
    }
}
