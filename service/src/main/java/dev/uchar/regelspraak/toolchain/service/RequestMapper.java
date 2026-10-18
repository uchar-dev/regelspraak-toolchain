package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import dev.uchar.regelspraak.cases.CaseInvoer;
import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Feittype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.model.RegelSpraakModel.Rol;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@link Verzoek} JSON tree onto a {@link Wereld} of fact beans + globals + rekendatum, validating
 * every field against the resolved model and collecting typed {@link Schending}s (each carrying its FIELD
 * PATH). The beans are not made here: the validated request is written as the input of a {@code uchar-case-v0} case,
 * {@code {"asOfDate", "input": {"instances": [...]}}} with the ids of the request as labels, and uchar's input step
 * reads that case ({@link CaseInvoer#van}), the one its replay reads every case with, so the service puts in the facts
 * the replay puts in on the same input. A role link uchar refuses names the link the case wrote; a table of the links
 * (id, role key, target id) gives the field of the request that wrote it: a second, different target on a to-one role
 * (WF-013, {@link CaseInvoer.Rolbotsing}) is a {@link Schending.Soort#VERKEERD_TYPE}, a link the bean model cannot hold
 * ({@link CaseInvoer.Rolweigering}) a {@link Schending.Soort#NIET_ONDERSTEUND}, each with uchar's sentence. On any
 * violation it does not throw: it returns the violations, beside the world uchar read from the fields that validated
 * (none when uchar refused a link), so a caller (fase 3) can turn the full set into one 400 naming every bad field.
 *
 * <p>Unsupported by the service core (a clear violation, never a silent accept): a dimensie- or
 * tijdlijn-valued attribute (no golden situatie uses one). An unknown objecttype/attribuut/kenmerk/parameter/
 * rol, an ill-typed value, an eenheid mismatch, and an unknown enumeratie-waarde are all violations.
 *
 * <p>Every name is read by a {@link NaamLezer}: canonical or a designation in any vocabulary of the model, unless
 * the request pins its input vocabulary. A name that reads as two declarations is a
 * {@link Schending.Soort#DUBBELZINNIGE_NAAM}; two names of one object that read as one declaration are
 * {@link Schending.Soort#MALFORMED}, since the service would otherwise keep one of the two values without saying so.
 * A violation names the field as the request spells it, and a declaration by its canonical name.
 */
public final class VerzoekMapper {

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;
    /** The {@code dateTime} lexeme of {@code uchar-case-v0}: the seconds always written, a fraction of whole
     *  milliseconds only where there is one. */
    private static final DateTimeFormatter DATUM_TIJD = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private final RegelSpraakModel model;
    private final DroolsBackend.Build build;
    private final NaamLezer lezer;

    /** @param model the resolved model the request is validated against
     *  @param build uchar's build of the entry point, whose input step reads the request ({@link CaseInvoer#van}) */
    public VerzoekMapper(RegelSpraakModel model, DroolsBackend.Build build) {
        this(model, build, new NaamLezer(model, null));
    }

    /** As {@link #VerzoekMapper(RegelSpraakModel, DroolsBackend.Build)}, reading the names with {@code lezer}. */
    VerzoekMapper(RegelSpraakModel model, DroolsBackend.Build build, NaamLezer lezer) {
        this.model = model;
        this.build = java.util.Objects.requireNonNull(build, "build");
        this.lezer = lezer;
    }

    /** The mapped world, the id→objecttype map (for the response builder), and the violations produced while
     *  mapping (empty on success). The world is null when uchar refused the input. */
    public record Resultaat(Wereld wereld, Map<String, Objecttype> typePerLabel, List<Schending> schendingen) {}

    /** A role link the mapper writes into the case: under the {@code roles} of instance {@code id}, the key
     *  {@code rol}, naming {@code doel}; uchar's {@link CaseInvoer.Rolfout} names a refused link the same way. */
    private record Link(String id, String rol, String doel) {}

    public Resultaat map(Verzoek verzoek) {
        List<Schending> s = new ArrayList<>();
        Map<String, Object> globals = new LinkedHashMap<>();
        Map<String, Objecttype> typePerLabel = new LinkedHashMap<>();
        // the instances of the case by id, in the order of the request, and the field of the request per link
        Map<String, ObjectNode> instanties = new LinkedHashMap<>();
        Map<Link, String> paden = new LinkedHashMap<>();
        // the fields whose date or date-time has a year the case lexeme does not carry (four digits, no sign), in the
        // order uchar's input step reads them: the evaluation date first, then the attributes in the order of the case
        List<String> jaarBuitenCasevorm = new ArrayList<>();

        // 1. peildatum / rekendatum — ONE date authority (fase 5). 'peildatum' is the preferred name, 'rekendatum'
        //    the existing alias: the two are the SAME notion. Both present and UNEQUAL is a violation naming BOTH
        //    fields and BOTH values (a caller must never give two conflicting dates); otherwise the date is the one
        //    present (peildatum wins when both are given and equal). The unified date drives Wereld.rekendatum —
        //    which the vintage-selection ALSO reads, so a rule reading Rekendatum and the vintage follow ONE date.
        LocalDate peildatum = leesDatum(verzoek.peildatum(), "peildatum", s);
        LocalDate rekendatum = leesDatum(verzoek.rekendatum(), "rekendatum", s);
        LocalDate datum;
        if (peildatum != null && rekendatum != null && !peildatum.equals(rekendatum)) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, "peildatum",
                    "peildatum " + peildatum + " en rekendatum " + rekendatum + " zijn dezelfde datumautoriteit"
                            + " maar verschillen — geef er één (of gelijke waarden)"));
            datum = peildatum;
        } else {
            datum = peildatum != null ? peildatum : rekendatum;   // peildatum wins when both equal
        }
        if (datum != null && !inCasevorm(datum.getYear())) jaarBuitenCasevorm.add(peildatum != null ? "peildatum" : "rekendatum");

        // 2. parameters
        JsonNode params = verzoek.parameters();
        if (params != null && !params.isNull()) {
            if (!params.isObject())
                s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, "parameters", "parameters moet een object zijn"));
            else {
                Dubbel dubbel = new Dubbel("de parameters");
                params.fields().forEachRemaining(e -> {
                    String naam = e.getKey();
                    String veld = "parameters." + naam;
                    Gelezen g = lees(lezer.parameter(naam), naam, veld, dubbel, s);
                    if (g.geweigerd()) return;
                    RegelSpraakModel.Parameter p = g.naam() == null ? null : DescriptorLoader.parameter(model, g.naam());
                    if (p == null) {
                        s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                                "onbekende parameter '" + naam + "'" + lezer.vastgezetZin()));
                        return;
                    }
                    JsonNode waarde = waarde(e.getValue(), p.datatype(), veld, s);
                    if (waarde == null) return;
                    WaardeCoder.Resultaat r = WaardeCoder.decodeer(waarde, p.datatype(), veld);
                    s.addAll(r.schendingen());
                    if (r.schendingen().isEmpty())
                        globals.put(globalNaam(p.naam()), r.waarde());
                });
            }
        }

        // 3. instanties (case instances + attributen + kenmerken)
        JsonNode insts = verzoek.instanties();
        if (insts == null || insts.isNull()) {
            s.add(Schending.van(Schending.Soort.MALFORMED, "instanties", "instanties ontbreekt"));
        } else if (!insts.isArray()) {
            s.add(Schending.van(Schending.Soort.MALFORMED, "instanties", "instanties moet een array zijn"));
        } else {
            for (int i = 0; i < insts.size(); i++) {
                JsonNode in = insts.get(i);
                String pad = "instanties[" + i + "]";
                JsonNode typeN = in.get("type");
                JsonNode idN = in.get("id");
                if (typeN == null || !typeN.isTextual()) {
                    s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".type", "type ontbreekt of is geen tekst"));
                    continue;
                }
                if (idN == null || !idN.isTextual()) {
                    s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".id", "id ontbreekt of is geen tekst"));
                    continue;
                }
                String id = idN.asText();
                Gelezen type = lees(lezer.objecttype(typeN.asText()), typeN.asText(), pad + ".type", null, s);
                if (type.geweigerd()) continue;
                Objecttype ot = type.naam() == null ? null : DescriptorLoader.objecttype(model, type.naam());
                if (ot == null) {
                    s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".type",
                            "onbekend objecttype '" + typeN.asText() + "'" + lezer.vastgezetZin()));
                    continue;
                }
                if (instanties.containsKey(id)) {
                    s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".id",
                            "dubbel id '" + id + "'"));
                    continue;
                }
                ObjectNode instantie = NF.objectNode();
                instantie.put("label", id);
                instantie.put("objectType", ot.naam);
                instanties.put(id, instantie);
                typePerLabel.put(id, ot);
                zetAttributen(ot, instantie, in.get("attributen"), pad + ".attributen", jaarBuitenCasevorm, s);
                zetKenmerken(ot, instantie, in.get("kenmerken"), pad + ".kenmerken", s);
            }
        }

        // 4. rollen — inline per instance, then top-level relaties (both after all instances exist)
        if (insts != null && insts.isArray())
            for (int i = 0; i < insts.size(); i++) {
                JsonNode in = insts.get(i);
                JsonNode idN = in.get("id");
                if (idN == null || !idN.isTextual()) continue;
                String id = idN.asText();
                Objecttype ot = typePerLabel.get(id);
                if (ot == null) continue;
                zetRollen(ot, id, typePerLabel, in.get("rollen"), "instanties[" + i + "].rollen", instanties, paden, s);
            }
        zetRelaties(verzoek.relaties(), typePerLabel, instanties, paden, s);

        // 5. uchar reads the case: the beans, the roles both ways (RUNTIME-SEMANTICS ch. 5.7a), the lists
        ObjectNode c = NF.objectNode();
        if (datum != null) c.put("asOfDate", datum.toString());
        ArrayNode cInstanties = c.putObject("input").putArray("instances");
        instanties.values().forEach(cInstanties::add);
        CaseInvoer invoer = null;
        try {
            invoer = CaseInvoer.van(build, c);
        } catch (CaseInvoer.Rolbotsing b) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, pad(paden, b), b.getMessage()));
        } catch (CaseInvoer.Rolweigering w) {
            s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, pad(paden, w), w.getMessage()));
        } catch (CaseRunner.Excluded x) {
            // a form uchar's input step does not carry that the validation above let through: no link names it
            s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, "instanties", x.getMessage()));
        } catch (IllegalArgumentException f) {
            // after the validation above the one defect of the case uchar's input step can find is a date or date-time
            // lexeme outside its form, a year beyond 0000-9999; it stops at the first it reads
            if (jaarBuitenCasevorm.isEmpty()) throw f;
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, jaarBuitenCasevorm.get(0), f.getMessage()));
        }
        return new Resultaat(invoer == null ? null : new Wereld(invoer, globals, datum), typePerLabel, s);
    }

    /** The field of the request that wrote the link uchar refused. A link the mapper did not write is a broken promise
     *  of the input step, and fails here. */
    private static String pad(Map<Link, String> paden, CaseInvoer.Rolfout f) {
        String pad = paden.get(new Link(f.label(), f.rol(), f.doel()));
        if (pad == null)
            throw new IllegalStateException("uchars invoerstap weigerde de link " + f.label() + " -" + f.rol() + "-> "
                    + f.doel() + ", die het verzoek niet schreef: " + ((RuntimeException) f).getMessage());
        return pad;
    }

    /** Parse an ISO-datum node (yyyy-MM-dd) into a {@link LocalDate}, or {@code null} when absent/null. A
     *  non-textual or unparseable value adds a {@link Schending.Soort#VERKEERD_TYPE} on {@code veld}. Shared by
     *  the {@code peildatum} and {@code rekendatum} legs of the one date authority (fase 5). */
    private static LocalDate leesDatum(JsonNode node, String veld, List<Schending> s) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld, veld + " moet een ISO-datum (yyyy-MM-dd) zijn"));
            return null;
        }
        try { return LocalDate.parse(node.asText()); }
        catch (DateTimeParseException e) {
            s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld,
                    "'" + node.asText() + "' is geen geldige ISO-datum (yyyy-MM-dd)"));
            return null;
        }
    }

    // ---- the case instance ----

    /** Whether the case lexeme of a date carries year {@code jaar}: four digits, no sign. */
    private static boolean inCasevorm(int jaar) {
        return jaar >= 0 && jaar <= 9999;
    }

    /** The {@code uchar-case-v0} value of {@code w}, a value {@link WaardeCoder} decoded for {@code dt}: what uchar's
     *  case reader reads back as that same Java value. Null after adding the violation of a value the case form cannot
     *  carry: a date-time with a fraction finer than a millisecond. A date or date-time whose year the lexeme does not
     *  carry is written as {@link LocalDate#toString} writes it, for uchar's reader to refuse, and {@code veld} is added
     *  to {@code jaarBuitenCasevorm}. */
    private static JsonNode caseWaarde(Object w, Datatype dt, String veld, List<String> jaarBuitenCasevorm,
                                       List<Schending> s) {
        if (w == null) return NF.nullNode();
        ObjectNode n = NF.objectNode();
        // toString, not toPlainString: new BigDecimal(toString()) gives back the same unscaled value and scale
        if (w instanceof BigDecimal b) return n.put("number", b.toString());
        if (w instanceof LocalDate d) {
            if (!inCasevorm(d.getYear())) jaarBuitenCasevorm.add(veld);
            return n.put("date", d.toString());
        }
        if (w instanceof Boolean b) return n.put("boolean", b);
        if (w instanceof String tekst) return n.put(dt instanceof Datatype.Enumeratie ? "enumeration" : "text", tekst);
        if (w instanceof LocalDateTime dt2) {
            if (dt2.getNano() % 1_000_000 != 0) {
                s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld, "'" + dt2 + "' heeft een fractie van een"
                        + " seconde fijner dan een milliseconde; de dienst draagt hele milliseconden"));
                return null;
            }
            if (!inCasevorm(dt2.getYear())) jaarBuitenCasevorm.add(veld);
            String lexeem = DATUM_TIJD.format(dt2);
            if (dt2.getNano() != 0) lexeem += String.format(".%03d", dt2.getNano() / 1_000_000);
            return n.put("dateTime", lexeem);
        }
        throw new IllegalStateException("WaardeCoder gaf voor '" + veld + "' een " + w.getClass().getSimpleName()
                + ", waarvoor de case geen vorm heeft");
    }

    private static ArrayNode lijstIn(ObjectNode n, String veld) {
        JsonNode l = n.get(veld);
        return l instanceof ArrayNode a ? a : n.putArray(veld);
    }

    private static ObjectNode objectIn(ObjectNode n, String veld) {
        JsonNode o = n.get(veld);
        return o instanceof ObjectNode on ? on : n.putObject(veld);
    }

    private void zetAttributen(Objecttype ot, ObjectNode instantie, JsonNode attrs, String pad,
                               List<String> jaarBuitenCasevorm, List<Schending> s) {
        if (attrs == null || attrs.isNull()) return;
        if (!attrs.isObject()) { s.add(Schending.van(Schending.Soort.MALFORMED, pad, "attributen moet een object zijn")); return; }
        Dubbel dubbel = new Dubbel("deze instantie");
        attrs.fields().forEachRemaining(e -> {
            String naam = e.getKey();
            String veld = pad + "." + naam;
            Gelezen g = lees(lezer.attribuut(ot, naam), naam, veld, dubbel, s);
            if (g.geweigerd()) return;
            String canoniek = g.naam();
            Datatype dt = canoniek == null ? null : ot.attribuut(canoniek);
            if (dt == null) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                        "onbekend attribuut '" + naam + "' op objecttype '" + ot.naam + "'" + lezer.vastgezetZin()));
                return;
            }
            if (!ot.dimensiesVan(canoniek).isEmpty()) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                        "dimensie-gewaardeerd attribuut '" + naam + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            if (isTemporeel(ot, canoniek)) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                        "tijdlijn-gewaardeerd attribuut '" + naam + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            JsonNode waarde = waarde(e.getValue(), dt, veld, s);
            if (waarde == null) return;
            WaardeCoder.Resultaat r = WaardeCoder.decodeer(waarde, dt, veld);
            s.addAll(r.schendingen());
            if (!r.schendingen().isEmpty()) return;
            JsonNode caseWaarde = caseWaarde(r.waarde(), dt, veld, jaarBuitenCasevorm, s);
            if (caseWaarde != null) objectIn(instantie, "attributes").set(canoniek, caseWaarde);
        });
    }

    private void zetKenmerken(Objecttype ot, ObjectNode instantie, JsonNode kenm, String pad, List<Schending> s) {
        if (kenm == null || kenm.isNull()) return;
        if (!kenm.isObject()) { s.add(Schending.van(Schending.Soort.MALFORMED, pad, "kenmerken moet een object zijn")); return; }
        Dubbel dubbel = new Dubbel("deze instantie");
        kenm.fields().forEachRemaining(e -> {
            String naam = e.getKey();
            String veld = pad + "." + naam;
            Gelezen g = lees(lezer.kenmerk(ot, naam), naam, veld, dubbel, s);
            if (g.geweigerd()) return;
            String canoniek = g.naam();
            if (canoniek == null || !DescriptorLoader.heeftKenmerk(ot, canoniek)) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                        "onbekend kenmerk '" + naam + "' op objecttype '" + ot.naam + "'" + lezer.vastgezetZin()));
                return;
            }
            if (isTemporeelKenmerk(ot, canoniek)) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, veld,
                        "tijdlijn-gewaardeerd kenmerk '" + naam + "' wordt niet ondersteund door de dienst-kern"));
                return;
            }
            JsonNode v = e.getValue();
            if (!v.isBoolean()) {
                s.add(Schending.van(Schending.Soort.VERKEERD_TYPE, veld, "kenmerk '" + naam + "' moet een boolean zijn"));
                return;
            }
            // a case names the characteristics an instance has; one it does not name, it has not
            if (v.booleanValue()) lijstIn(instantie, "characteristics").add(canoniek);
        });
    }

    // ---- names and values read by the NaamLezer ----

    /** A name read for the mapping: {@code naam} to go on with — the written name where it reads canonically, the
     *  declaration's canonical name where only a designation reads it, null where nothing reads it (the caller
     *  refuses it with its own sentence) — or {@code geweigerd} when its violation has been added. */
    private record Gelezen(String naam, boolean geweigerd) {
        static final Gelezen GEWEIGERD = new Gelezen(null, true);
    }

    /** {@code lezing} for the mapping, adding the violation of an ambiguous name, and of a name that reads as a
     *  declaration another name of the same object ({@code dubbel}; null: nothing to compare with) already read. */
    private Gelezen lees(NaamLezer.Lezing lezing, String naam, String veld, Dubbel dubbel, List<Schending> s) {
        if (lezing instanceof NaamLezer.Dubbelzinnig d) {
            s.add(Schending.van(Schending.Soort.DUBBELZINNIGE_NAAM, veld, d.zin()));
            return Gelezen.GEWEIGERD;
        }
        if (!(lezing instanceof NaamLezer.Gevonden g)) return new Gelezen(null, false);
        if (dubbel != null) {
            String eerder = dubbel.eerder(g.declaratie(), naam);
            if (eerder != null) {
                s.add(Schending.van(Schending.Soort.MALFORMED, veld, NaamLezer.beschrijving(g.declaratie())
                        + " staat twee keer in " + dubbel.waar() + ": als '" + eerder + "' en als '" + naam + "'"));
                return Gelezen.GEWEIGERD;
            }
        }
        return new Gelezen(g.naam(), false);
    }

    /** The names one object of the request has read so far, by declaration: a second name of the same declaration is
     *  a second value for it. */
    private static final class Dubbel {
        private final String waar;
        private final List<RegelSpraakModel.Benoembaar> declaraties = new ArrayList<>();
        private final List<String> namen = new ArrayList<>();

        Dubbel(String waar) { this.waar = waar; }

        String waar() { return waar; }

        /** The name that already read {@code b}, or null after recording {@code naam} for it. */
        String eerder(RegelSpraakModel.Benoembaar b, String naam) {
            for (int i = 0; i < declaraties.size(); i++)
                if (NaamLezer.zelfde(declaraties.get(i), b)) return namen.get(i);
            declaraties.add(b);
            namen.add(naam);
            return null;
        }
    }

    /** An enumeration value read by the {@link NaamLezer}, as the node {@link WaardeCoder} decodes: a designation
     *  becomes the value it names, a canonical spelling stays as written (the rules compare it as uchar compares
     *  declared names). Null after adding the violation of an ambiguous value, or of a value the pinned input
     *  vocabulary does not name; any other datatype, and an unknown value, go to the coder as they are. */
    private JsonNode waarde(JsonNode node, Datatype dt, String veld, List<Schending> s) {
        if (!(dt instanceof Datatype.Enumeratie e) || node == null || !node.isTextual()) return node;
        NaamLezer.Lezing lezing = lezer.waarde(e, node.asText());
        if (lezing instanceof NaamLezer.Dubbelzinnig d) {
            s.add(Schending.van(Schending.Soort.DUBBELZINNIGE_NAAM, veld, d.zin()));
            return null;
        }
        if (lezing instanceof NaamLezer.Gevonden g) return TextNode.valueOf(g.naam());
        if (lezer.vastInVocabulaire()) {
            s.add(Schending.van(Schending.Soort.ONBEKENDE_ENUM_WAARDE, veld, "'" + node.asText()
                    + "' is geen waarde van domein '" + e.domein() + "' (toegestaan in het invoervocabulaire '"
                    + lezer.vastgezet() + "': " + lezer.waarden(e) + ")"));
            return null;
        }
        return node;
    }

    // ---- rollen (inline) ----

    /** The inline roles of one instance, as the {@code roles} of its case instance under the canonical role name: a
     *  request instance with {@code rollen} is the case instance with {@code roles}, so uchar's input step sets the
     *  facts the replay sets. A role name the model does not know is refused before anything is written, and so is an
     *  id whose objecttype does not fill the role (the role's type or a subtype of it): the input step falls back on the
     *  type pair, and would write such a link into another relation between the two types, or refuse it as a form the
     *  service does not carry. */
    private void zetRollen(Objecttype ot, String id, Map<String, Objecttype> typePerLabel, JsonNode rollen, String pad,
                           Map<String, ObjectNode> instanties, Map<Link, String> paden, List<Schending> s) {
        if (rollen == null || rollen.isNull()) return;
        if (!rollen.isObject()) { s.add(Schending.van(Schending.Soort.MALFORMED, pad, "rollen moet een object zijn")); return; }
        Dubbel dubbel = new Dubbel("deze instantie");
        rollen.fields().forEachRemaining(e -> {
            String rol = e.getKey();
            String veld = pad + "." + rol;
            Gelezen gelezen = lees(lezer.rolVanaf(ot, rol), rol, veld, dubbel, s);
            if (gelezen.geweigerd()) return;
            String canoniek = gelezen.naam();
            Objecttype doeltype = canoniek == null ? null : model.rolDoeltypeNaarSubject(ot, canoniek);
            if (doeltype == null) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                        "onbekende rol '" + rol + "' vanaf objecttype '" + ot.naam + "'" + lezer.vastgezetZin()));
                return;
            }
            List<String> doelen = new ArrayList<>();
            JsonNode v = e.getValue();
            if (v.isArray()) v.forEach(n -> doelen.add(n.asText()));
            else doelen.add(v.asText());
            for (String label : doelen) {
                Objecttype labelType = typePerLabel.get(label);
                if (labelType == null) {
                    s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, veld,
                            "rol '" + rol + "' verwijst naar onbekend id '" + label + "'"));
                    continue;
                }
                if (!labelType.isEen(doeltype)) {
                    s.add(verkeerdType(veld, rol, doeltype, label, labelType));
                    continue;
                }
                schrijfLink(instanties, paden, id, canoniek, label, veld);
            }
        });
    }

    /** Writes the link {@code id} -{@code rol}-> {@code doel} into the case, and records {@code veld} as the field of
     *  the request that wrote it; a link written twice keeps the first field. */
    private static void schrijfLink(Map<String, ObjectNode> instanties, Map<Link, String> paden, String id, String rol,
                                    String doel, String veld) {
        lijstIn(objectIn(instanties.get(id), "roles"), rol).add(doel);
        paden.putIfAbsent(new Link(id, rol, doel), veld);
    }

    private static Schending verkeerdType(String veld, String rol, Objecttype doeltype, String id, Objecttype type) {
        return Schending.van(Schending.Soort.VERKEERD_TYPE, veld, "rol '" + rol + "' verwacht een instantie van"
                + " objecttype '" + doeltype.naam + "', maar id '" + id + "' is van objecttype '" + type.naam + "'");
    }

    // ---- relaties (top-level) — desugar to a role link of the case ----

    /** Each relation as one role link of the case, on the end whose role toward the other end is to-one, keyed by the
     *  role the other end fills: the link a case writes on that instance. uchar's input step establishes the relation
     *  both ways from it (RUNTIME-SEMANTICS ch. 5.7a): the far field of a 1:1 relation, the reverse leg of a reciprocal
     *  one. Where neither direction is to-one the link goes on the first end, and the input step refuses it. */
    private void zetRelaties(JsonNode relaties, Map<String, Objecttype> typePerLabel, Map<String, ObjectNode> instanties,
                             Map<Link, String> paden, List<Schending> s) {
        if (relaties == null || relaties.isNull()) return;
        if (!relaties.isArray()) { s.add(Schending.van(Schending.Soort.MALFORMED, "relaties", "relaties moet een array zijn")); return; }
        for (int i = 0; i < relaties.size(); i++) {
            JsonNode rel = relaties.get(i);
            String pad = "relaties[" + i + "]";
            JsonNode ftN = rel.get("feittype");
            JsonNode rollenN = rel.get("rollen");
            if (ftN == null || !ftN.isTextual()) {
                s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".feittype", "feittype ontbreekt of is geen tekst"));
                continue;
            }
            Gelezen feittype = lees(lezer.feittype(ftN.asText()), ftN.asText(), pad + ".feittype", null, s);
            if (feittype.geweigerd()) continue;
            Feittype ft = feittype.naam() == null ? null : feittype(feittype.naam());
            if (ft == null) {
                s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".feittype",
                        "onbekend feittype '" + ftN.asText() + "'" + lezer.vastgezetZin()));
                continue;
            }
            if (rollenN == null || !rollenN.isObject()) {
                s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".rollen", "rollen ontbreekt of is geen object"));
                continue;
            }
            rollenN = rollenGelezen(ft, rollenN, pad + ".rollen", s);
            if (rollenN == null) continue;
            // resolve each named role to its id. A binary feittype has two roles; a §13.3.9.2 WEDERKERIG feittype
            // relates an objecttype to itself through ONE role entry — the request then names that one role TWICE
            // (two ids under the same role name in a JSON array), or gives an array value.
            List<Rol> rollen = ft.rollen;
            if (rollen.size() != 2 && !(ft.wederkerig && rollen.size() == 1)) {
                s.add(Schending.van(Schending.Soort.NIET_ONDERSTEUND, pad,
                        "alleen binaire (of wederkerige) feittypen worden ondersteund door de dienst-kern (feittype '"
                                + ft.naam + "')"));
                continue;
            }
            // collect the (roleName, id) endpoints: two entries even for a wederkerig 1-role feittype (its role
            // appears once but with an array of two ids, or is repeated).
            List<String[]> eindpunten = eindpunten(rollenN, rollen, ft.wederkerig);
            if (eindpunten.size() != 2) {
                s.add(Schending.van(Schending.Soort.MALFORMED, pad + ".rollen",
                        "relatie moet precies twee rol-eindpunten noemen (feittype '" + ft.naam + "')"));
                continue;
            }
            String rolA = eindpunten.get(0)[0], idA = eindpunten.get(0)[1];
            String rolB = eindpunten.get(1)[0], idB = eindpunten.get(1)[1];
            Objecttype otA = typePerLabel.get(idA), otB = typePerLabel.get(idB);
            if (otA == null) { s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".rollen." + rolA, "onbekend id '" + idA + "'")); continue; }
            if (otB == null) { s.add(Schending.van(Schending.Soort.ONBEKEND_VELD, pad + ".rollen." + rolB, "onbekend id '" + idB + "'")); continue; }
            // the input step falls back on the type pair: an id that does not fill its role would be written into
            // another relation between the two types, as on an inline rol
            boolean vultA = vultRol(ft, rolA, idA, otA, pad, s), vultB = vultRol(ft, rolB, idB, otB, pad, s);
            if (!vultA || !vultB) continue;
            // the link goes where its field is: A holds B in a to-one field named after B's role, or else B holds A.
            // The role NAME disambiguates when the same objecttype pair is joined by more than one feittype.
            if (!model.naarVeelRol(otA, otB, rolB) || model.naarVeelRol(otB, otA, rolA))
                schrijfLink(instanties, paden, idA, rolB, idB, pad + ".rollen." + rolB);
            else
                schrijfLink(instanties, paden, idB, rolA, idA, pad + ".rollen." + rolA);
        }
    }

    /** The roles of a relation keyed by their declared names, which {@link #eindpunten} looks them up by: each written
     *  name read as a role of {@code ft}. A name no role of {@code ft} answers to stays as written, so the endpoint
     *  count refuses the relation as before; null after adding the violation of an ambiguous or a repeated role. */
    private JsonNode rollenGelezen(Feittype ft, JsonNode rollenN, String pad, List<Schending> s) {
        com.fasterxml.jackson.databind.node.ObjectNode gelezen =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        Dubbel dubbel = new Dubbel("de relatie");
        boolean fout = false;
        for (var it = rollenN.fields(); it.hasNext(); ) {
            var e = it.next();
            NaamLezer.Lezing lezing = lezer.rolIn(ft, e.getKey());
            if (lees(lezing, e.getKey(), pad + "." + e.getKey(), dubbel, s).geweigerd()) { fout = true; continue; }
            gelezen.set(lezing instanceof NaamLezer.Gevonden g ? g.declaratie().naam() : e.getKey(), e.getValue());
        }
        return fout ? null : gelezen;
    }

    /** Whether {@code id} (of objecttype {@code type}) fills the role {@code rol} of {@code ft}, its declared type or a
     *  subtype of it; if not, a {@link Schending.Soort#VERKEERD_TYPE} on that role's field. */
    private boolean vultRol(Feittype ft, String rol, String id, Objecttype type, String pad, List<Schending> s) {
        for (Rol r : ft.rollen) {
            if (!r.naam().equals(rol)) continue;
            Objecttype doeltype = model.objecttypeOpNaam(r.objecttypeNaam());
            if (doeltype == null || type.isEen(doeltype)) return true;
            s.add(verkeerdType(pad + ".rollen." + rol, rol, doeltype, id, type));
            return false;
        }
        return true;
    }

    /** Collect exactly the two (roleName, id) endpoints of a relation. For a normal binary feittype that is one
     *  id per role name; for a WEDERKERIG 1-role feittype the single role name carries an array of two ids (or
     *  is listed twice via a JSON array), both under the same role name. */
    private static List<String[]> eindpunten(JsonNode rollenN, List<Rol> rollen, boolean wederkerig) {
        List<String[]> uit = new ArrayList<>();
        if (wederkerig && rollen.size() == 1) {
            String rol = rollen.get(0).naam();
            JsonNode v = rollenN.get(rol);
            if (v == null) v = rollenN.elements().hasNext() ? rollenN.elements().next() : null;
            if (v != null && v.isArray())
                v.forEach(n -> uit.add(new String[]{rol, n.asText()}));
            else if (v != null) uit.add(new String[]{rol, v.asText()});
        } else {
            for (Rol r : rollen) {
                JsonNode v = rollenN.get(r.naam());
                if (v != null && !v.isNull()) uit.add(new String[]{r.naam(), v.asText()});
            }
        }
        return uit;
    }

    private Feittype feittype(String naam) {
        Feittype ft = model.feittypen.get(naam);
        if (ft != null) return ft;
        for (Feittype f : model.feittypen.values()) if (f.naam.equalsIgnoreCase(naam)) return f;
        return null;
    }

    /** Whether attribute {@code naam} on {@code ot} is a §13.3.6 tijdlijn (temporal, {@code List<Segment>}) —
     *  asked over the WHOLE chain (IR-SPEC ch. 2.2), not the own maps. An inherited timeline read as a scalar
     *  is not a missing binding but a wrong judgement: the mapper would accept the value and write it as a scalar of
     *  the case, which uchar's input step reads as the asOfDate-bucket scalar of a timeline the service does not
     *  carry, instead of a violation naming the field.
     *  The model's chain walk is case-INSENSITIVE, exactly as this scan was. */
    private static boolean isTemporeel(Objecttype ot, String naam) {
        return ot.granulariteitVan(naam) != null || ot.tijdlijnAantalVan(naam) != null;
    }

    /** The kenmerk analogue of {@link #isTemporeel} — a §13.3.6.7 tijdlijn kenmerk (a {@code List<Segment>}
     *  field), which the service core does not support; chain-closed for the same reason. */
    private static boolean isTemporeelKenmerk(Objecttype ot, String naam) {
        return ot.kenmerkGranulariteitVan(naam) != null || ot.kenmerkTijdlijnAantalVan(naam) != null;
    }

    /** The parameter GLOBAL name the exec-model branch registers — {@link Namen#ident}(naam,false), the
     *  camelCase form (the formula uchar's {@code TmsTranspiler} names a parameter's global with, and the name uchar's
     *  entry asks a consumer to set; recomputed here because the transpiler keeps that step private). */
    static String globalNaam(String naam) { return Namen.ident(naam, false); }

    /** The bean property name for a RegelSpraak name — {@link Namen#ident}(naam,false), the SAME
     *  camelCase form {@link FactModelGenerator} names the field/getter/setter with (non-letter/digit runs
     *  collapsed, so a hyphenated "datum-tijd …" maps to "datumTijd…"). Handing this to
     *  {@link RegelSpraakRuntime#set}/{@link RegelSpraakRuntime#get} (which prepend set/get + PascalCase over
     *  whitespace-free input) resolves to exactly the generated accessor — reusing the bridge without a naming
     *  divergence. */
    static String prop(String naam) { return Namen.ident(naam, false); }
}
