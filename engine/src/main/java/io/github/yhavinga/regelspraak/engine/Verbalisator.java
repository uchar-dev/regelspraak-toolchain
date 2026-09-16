package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.Eenheid;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.transpiler.kern.Namen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * De UITLEG-generator: verantwoordt een gedraaide beslissing in helder Nederlands, voor de jurist en de burger —
 * niet de programmeur. Uit de gedraaide fact-toestand (de grondwaarheid: een kenmerk staat AAN of niet) plus de
 * getypeerde IR ({@link RegelSpraakModel#resolvedRegels}) bouwt hij per gelabelde instantie een verhaal:
 *
 * <ul>
 *   <li>welke kenmerken zijn TOEGEKEND en WAAROM — de voorwaarden met hun geëvalueerde operandwaarden
 *       ("het maandloon nieuw van EOver (1.750,01 EUR) is groter dan de drempelwaarde (1.750,00 EUR)");</li>
 *   <li>welke kenmerktoekenningsregels met een voorwaarde NIET tot toekenning leidden, en aan welke voorwaarde
 *       dat lag — de EERSTE falende conjunct van een "alle volgende voorwaarden"-regel;</li>
 *   <li>afgesloten met een bronverwijzing wanneer de IR die als optionele regelbron-annotatie draagt.</li>
 * </ul>
 *
 * <p><b>Waarom uit de eindtoestand en niet uit de vuur-trace:</b> een kenmerk dat op een bean TRUE staat is het
 * enige harde bewijs dát het is toegekend; de trace telt activaties maar bindt geen waarde. De voorwaarde-
 * operanden worden hier daarom (read-only) opnieuw geëvalueerd tegen dezelfde eindtoestand die de regel ook zag —
 * de getallen die de uitleg toont zijn de getallen waarop de conclusie stoelt. Deze verbalisator VERZINT geen
 * semantiek: hij dekt de operand- en conditievormen die de IR expliciet structureert (attribuut/parameter/
 * literal/rekendatum-reads, de reken-operatoren, afronding, begrenzing, vergelijkingen, kenmerk-/rol-lidmaatschap
 * en de samengestelde alle/geen-kwantoren) en meldt een niet-gestructureerde vorm als zodanig, in plaats van te
 * gokken.
 *
 * <p><b>Bronverwijzing.</b> Kaart-fase (1) vroeg de {@code // Bron:}-commentaren uit de {@code .rgs} mee de IR in
 * te tillen als optionele regelbron-annotatie. In de huidige grammatica geldt
 * {@code LINE_COMMENT : '//' ~[\r\n]* -> skip} — commentaren worden weggegooid, niet naar een hidden channel
 * gerouteerd — dus de annotatie is er vandaag NIET. De verbalisator is daar eerlijk over: hij drukt een
 * bronverwijzing af zodra {@link ResolvedRegel} er een draagt en laat de regel anders weg (geen verzonnen bron).
 */
public final class Verbalisator {

    private final RegelSpraakModel model;
    /** De opgeloste globals die operanden kunnen lezen: parameters (op hun canonieke naam) + "rekendatum". */
    private final Map<String, Object> globals;

    public Verbalisator(RegelSpraakModel model, Map<String, Object> globals) {
        this.model = model;
        this.globals = globals == null ? Map.of() : globals;
    }

    /**
     * Bouw de uitleg voor een gedraaide wereld. {@code instanties} mapt een LABEL (het scenario-label of het
     * dienst-verzoek-id) op de bijbehorende, reeds gevuurde bean; {@code typePerLabel} het objecttype per label.
     * Het resultaat is één regel per uitlegzin, in een stabiele volgorde (per label, in modeldeclaratie-volgorde
     * van de regels). Een lege lijst betekent: geen kenmerktoekenning raakte een van deze instanties.
     */
    public List<String> verbaliseer(Map<String, Object> instanties, Map<String, Objecttype> typePerLabel) {
        List<String> uit = new ArrayList<>();
        for (Map.Entry<String, Object> e : instanties.entrySet()) {
            String label = e.getKey();
            Object bean = e.getValue();
            Objecttype ot = typePerLabel.get(label);
            if (ot == null || bean == null) continue;
            for (ResolvedRegel r : model.resolvedRegels) {
                if (r.soort() != ResolvedRegel.Soort.KENMERKTOEKENNING) continue;
                if (!(r.doel() instanceof Expr.AttribuutRef ref) || ref.objecttype() == null) continue;
                // IR-SPEC ch. 2.2: de regel geldt voor elk objecttype dat het doeltype IS (⊑), niet alleen voor
                // het doeltype zelf — de gegenereerde bean erft native, dus een kenmerktoekenning op het
                // supertype kent het kenmerk óók toe aan een subtype-instantie. Op naamgelijkheid vergelijken
                // laat precies die toekenningen onverklaard: het kenmerk staat AAN en het verhaal zwijgt.
                if (!ot.isEen(ref.objecttype())) continue;
                String zin = verbaliseerToekenning(label, bean, ref, r);
                if (zin != null) uit.add(zin);
            }
        }
        return uit;
    }

    /** Één kenmerktoekenningsregel voor één instantie: is het kenmerk toegekend → "waarom (voorwaarden)"; is het
     *  met een voorwaarde NIET toegekend → "niet, want <eerste falende conjunct>"; onvoorwaardelijk-afwezig → geen
     *  zin (er valt niets te verantwoorden). */
    private String verbaliseerToekenning(String label, Object bean, Expr.AttribuutRef ref, ResolvedRegel r) {
        String kenmerk = ref.attribuut();
        boolean aanwezig = leesBoolean(bean, kenmerk);
        Conditie v = r.voorwaarde();
        StringBuilder b = new StringBuilder();
        if (aanwezig) {
            b.append(label).append(" is ").append(kenmerk);
            if (v != null) b.append(", omdat ").append(verbaliseerVoorwaarde(v, bean, true));
            else b.append(" (onvoorwaardelijk toegekend)");
        } else {
            if (v == null) return null;   // onvoorwaardelijk maar afwezig: niets te verantwoorden
            b.append(label).append(" is niet ").append(kenmerk).append(", want ")
             .append(eersteFalendeConjunct(v, bean));
        }
        String bron = bronVerwijzing(r);
        if (bron != null) b.append(". Grondslag: ").append(bron);
        return b.toString();
    }

    // ---------------- voorwaarde-verbalisatie ----------------

    /** Een voorwaarde in proza, met de geëvalueerde operandwaarden. {@code positief} = we verklaren WAAROM ze
     *  geldt (het toegekende geval); false zou de ontkenning verklaren, maar de niet-toegekende tak gaat via
     *  {@link #eersteFalendeConjunct}. */
    private String verbaliseerVoorwaarde(Conditie c, Object bean, boolean positief) {
        if (c instanceof Conditie.Samengesteld s) {
            List<String> delen = new ArrayList<>();
            for (Conditie d : s.onderdelen()) delen.add(verbaliseerVoorwaarde(d, bean, positief));
            String voegwoord = switch (s.kwantor()) {
                case ALLE -> " en ";
                case GEEN -> " en niet ";
                default -> " of ";
            };
            return String.join(voegwoord, delen);
        }
        return verbaliseerAtoom(c, bean);
    }

    /** Een enkele (niet-samengestelde) conditie als proza met geëvalueerde operanden. */
    private String verbaliseerAtoom(Conditie c, Object bean) {
        if (c instanceof Conditie.Vergelijking vg) {
            return operandProza(vg.links(), bean) + " " + operatorWoorden(vg.op()) + " "
                    + operandProza(vg.rechts(), bean);
        }
        if (c instanceof Conditie.Kenmerk k) {
            String wie = k.navigatie() == null ? "het onderwerp" : "de " + k.navigatie();
            return wie + (k.negatie() ? " niet " : " ") + k.kenmerk() + " is";
        }
        if (c instanceof Conditie.Rollidmaatschap rl)
            return "het onderwerp " + (rl.negatie() ? "geen " : "een ") + rl.rol() + " is";
        if (c instanceof Conditie.Predicaat p) {
            String kern = switch (p.soort()) {
                case LEEG -> "leeg is";
                case GEVULD -> "gevuld is";
                default -> "voldoet aan " + p.soort().name().toLowerCase(Locale.ROOT);
            };
            return operandProza(p.operand(), bean) + " " + kern;
        }
        // een nog niet gestructureerde conditievorm: eerlijk de brontekst tonen, geen gok.
        return "aan de voorwaarde \"" + c.bron().snippet().trim() + "\" is voldaan";
    }

    /** Voor een "alle volgende voorwaarden"-regel die NIET vuurde: de EERSTE conjunct die (na evaluatie van zijn
     *  operanden tegen de eindtoestand) niet houdt — de reden dat de toekenning uitbleef. Voor een niet-
     *  samengestelde voorwaarde is dat de voorwaarde zelf. Kan een conjunct niet worden geëvalueerd (een
     *  onopgeloste vorm, een niet-leesbaar operand), dan noemt hij die eerlijk als "niet vast te stellen". */
    private String eersteFalendeConjunct(Conditie c, Object bean) {
        if (c instanceof Conditie.Samengesteld s && s.kwantor() == Conditie.Kwantor.ALLE) {
            for (Conditie d : s.onderdelen()) {
                Boolean houdt = evalueer(d, bean);
                if (houdt == null) return "de voorwaarde \"" + samenvatting(d) + "\" niet is vast te stellen";
                if (!houdt) return "niet geldt: " + verbaliseerAtoom(d, bean);
            }
            // alle conjuncten houden maar het kenmerk staat toch niet aan: een producent buiten deze run.
            return "de conclusie niet is bereikt (mogelijk een regel buiten deze berekening)";
        }
        Boolean houdt = evalueer(c, bean);
        if (Boolean.FALSE.equals(houdt)) return "niet geldt: " + verbaliseerAtoom(c, bean);
        if (houdt == null) return "de voorwaarde \"" + samenvatting(c) + "\" niet is vast te stellen";
        return "de conclusie niet is bereikt (mogelijk een regel buiten deze berekening)";
    }

    private static String samenvatting(Conditie c) { return c.bron() == null ? "?" : c.bron().snippet().trim(); }

    // ---------------- read-only conditie-evaluatie ----------------

    /** Evalueer een conditie tegen de eindtoestand. null = niet vast te stellen (onopgeloste vorm / onleesbaar
     *  operand); geen gok. Alleen de vormen die de "eerste falende conjunct" nodig heeft. */
    private Boolean evalueer(Conditie c, Object bean) {
        if (c instanceof Conditie.Vergelijking vg) {
            Object l = evalExpr(vg.links(), bean);
            Object r = evalExpr(vg.rechts(), bean);
            if (l == null || r == null) return null;
            return vergelijk(vg.op(), l, r);
        }
        if (c instanceof Conditie.Kenmerk k && k.navigatie() == null) {
            Object v = leesRuw(bean, Namen.getter(k.kenmerk()));
            boolean aan = Boolean.TRUE.equals(v);
            return k.negatie() != aan;   // negatie XOR aanwezig
        }
        if (c instanceof Conditie.Predicaat p) {
            Object v = evalExpr(p.operand(), bean);
            return switch (p.soort()) {
                case LEEG -> v == null;
                case GEVULD -> v != null;
                default -> null;
            };
        }
        return null;
    }

    private Boolean vergelijk(Conditie.Operator op, Object l, Object r) {
        int c;
        if (l instanceof BigDecimal a && r instanceof BigDecimal b) c = a.compareTo(b);
        else if (l instanceof LocalDate a && r instanceof LocalDate b) c = a.compareTo(b);
        else if (l instanceof LocalDateTime a && r instanceof LocalDateTime b) c = a.compareTo(b);
        else if (op == Conditie.Operator.GELIJK || op == Conditie.Operator.ONGELIJK) {
            boolean gelijk = l.equals(r);
            return op == Conditie.Operator.GELIJK ? gelijk : !gelijk;
        } else return null;   // ongelijksoortige operanden voor een ordinale vergelijking
        return switch (op) {
            case KLEINER, EERDER -> c < 0;
            case KLEINER_OF_GELIJK, EERDER_OF_GELIJK -> c <= 0;
            case GROTER, LATER -> c > 0;
            case GROTER_OF_GELIJK, LATER_OF_GELIJK -> c >= 0;
            case GELIJK -> c == 0;
            case ONGELIJK -> c != 0;
        };
    }

    // ---------------- expressie-evaluatie (read-only) ----------------

    /** De waarde van een operand-expressie in de eindtoestand, of null als ze niet (zonder gok) te bepalen is —
     *  precies de vormen die de IR structureert: literalen, attribuut-/parameter-reads, de rekenconstanten, de
     *  reken-operatoren, afronding en begrenzing. */
    private Object evalExpr(Expr e, Object bean) {
        if (e == null) return null;
        if (e instanceof Expr.Literal lit) return literalWaarde(lit);
        if (e instanceof Expr.ParameterRef pr) return globals.get(Namen.ident(pr.parameter().naam(), false));
        if (e instanceof Expr.Constante ct) {
            if ("Rekendatum".equals(ct.naam())) return globals.get("rekendatum");
            return null;
        }
        if (e instanceof Expr.AttribuutRef ref && ref.navigatieRol() == null)
            return leesRuw(bean, Namen.getter(ref.attribuut()));
        if (e instanceof Expr.Binair bin) {
            Object l = evalExpr(bin.links(), bean);
            Object r = evalExpr(bin.rechts(), bean);
            if (l instanceof BigDecimal a && r instanceof BigDecimal b) return rekenen(bin.op(), a, b);
            return null;
        }
        if (e instanceof Expr.Afgerond af) {
            Object basis = evalExpr(af.basis(), bean);
            if (basis instanceof BigDecimal a) return a.setScale(af.decimalen(), afrondModus(af.modus()));
            return null;
        }
        if (e instanceof Expr.Begrensd bg) {
            Object basis = evalExpr(bg.basis(), bean);
            if (!(basis instanceof BigDecimal a)) return null;
            Object min = evalExpr(bg.minimum(), bean);
            Object max = evalExpr(bg.maximum(), bean);
            if (min instanceof BigDecimal lo && a.compareTo(lo) < 0) a = lo;
            if (max instanceof BigDecimal hi && a.compareTo(hi) > 0) a = hi;
            return a;
        }
        return null;   // navigatie-reads, aggregaties, teksreeksen: niet zonder verdere sessie te bepalen
    }

    private static Object rekenen(Expr.BinOp op, BigDecimal a, BigDecimal b) {
        return switch (op) {
            case PLUS -> a.add(b);
            case MIN, VERMINDERD_MET -> a.subtract(b);
            case MAAL -> a.multiply(b);
            case PERCENTAGE_VAN -> a.multiply(b).movePointLeft(2);
            case GEDEELD_DOOR, GEDEELD_DOOR_ABS ->
                    b.signum() == 0 ? null : a.divide(b, 10, java.math.RoundingMode.HALF_UP);
            default -> null;   // TOT_DE_MACHT: geen exacte BigDecimal-macht hier
        };
    }

    private static java.math.RoundingMode afrondModus(Expr.AfrondModus m) {
        return switch (m) {
            case NAAR_BENEDEN -> java.math.RoundingMode.FLOOR;
            case NAAR_BOVEN -> java.math.RoundingMode.CEILING;
            case REKENKUNDIG -> java.math.RoundingMode.HALF_UP;
            case RICHTING_NUL -> java.math.RoundingMode.DOWN;
            case WEG_VAN_NUL -> java.math.RoundingMode.UP;
        };
    }

    private static Object literalWaarde(Expr.Literal lit) {
        Datatype t = lit.type();
        String w = lit.waarde();
        try {
            if (t.isGetalachtig()) return parseGetal(w);
            if (t instanceof Datatype.Booleaan) return "waar".equalsIgnoreCase(w) || Boolean.parseBoolean(w);
            if (t instanceof Datatype.DatumInDagen) return LocalDate.parse(w);
            if (t instanceof Datatype.DatumTijd) return LocalDateTime.parse(w);
        } catch (RuntimeException onparsebaar) { /* val terug op de ruwe tekst */ }
        return w;
    }

    /** Parse een getal-literal naar {@link BigDecimal}. Een §13.4.17-getalliteral draagt zijn eenheid IN de
     *  waarde-tekst ("100 km", "2.450,00 EUR"): de eenheid staat na een spatie, dus we nemen het eerste token en
     *  vertalen de NL-notatie (punt = duizendtal, komma = decimaal). Gooit bij een niet-numeriek token. */
    private static BigDecimal parseGetal(String w) {
        String token = w.trim().split("\\s+", 2)[0];        // strip de eenheid-suffix ("100 km" -> "100")
        String genormaliseerd = token.replace(".", "").replace(",", ".");   // NL -> BigDecimal-notatie
        return new BigDecimal(genormaliseerd);
    }

    // ---------------- rendering ----------------

    /** Een operandwaarde in NL-notatie, met eenheid waar de IR die kent; "onbekend" wanneer niet leesbaar. */
    private String renderOperand(Expr e, Object bean) {
        Object v = evalExpr(e, bean);
        if (v == null) return "onbekend";
        return renderWaarde(v, e.type());
    }

    /** Een operand als leesbaar deel van een vergelijking: een LITERAL toont zijn waarde één keer ("100 km") —
     *  géén verdubbeling van de brontekst; elk ander operand toont zijn omschrijving mét de geëvalueerde waarde
     *  tussen haakjes ("de maandloon nieuw van EOver (3.000,00 EUR)"), zodat de jurist de norm én het feit ziet. */
    private String operandProza(Expr e, Object bean) {
        if (e instanceof Expr.Literal) return renderOperand(e, bean);
        return omschrijf(e) + " (" + renderOperand(e, bean) + ")";
    }

    /** Een Java-waarde in helder Nederlands: getallen met punt-duizend/komma-decimaal en de eenheid uit het type;
     *  datums als dd-mm-jjjj; booleans als ja/nee. */
    static String renderWaarde(Object v, Datatype type) {
        if (v == null) return "leeg";
        if (v instanceof BigDecimal b) {
            String getal = nlGetal(b, type);
            String eenheid = eenheidWoord(type);
            return eenheid == null ? getal : getal + " " + eenheid;
        }
        if (v instanceof LocalDate d)
            return String.format(Locale.ROOT, "%02d-%02d-%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
        if (v instanceof LocalDateTime dt)
            return String.format(Locale.ROOT, "%02d-%02d-%04d %02d:%02d", dt.getDayOfMonth(), dt.getMonthValue(),
                    dt.getYear(), dt.getHour(), dt.getMinute());
        if (v instanceof Boolean b) return b ? "ja" : "nee";
        return v.toString();
    }

    /** Getal in NL-notatie: punt als duizendtal-scheider, komma als decimaalteken. Percentages en getallen met
     *  vaste decimalen behouden hun schaal; anders wordt de natuurlijke schaal van de waarde getoond. */
    private static String nlGetal(BigDecimal b, Datatype type) {
        BigDecimal w = b;
        int decimalen = -1;
        if (type instanceof Datatype.Numeriek n && !n.geheel()) decimalen = n.decimalen();
        else if (type instanceof Datatype.Percentage p && !p.geheel()) decimalen = p.decimalen();
        else if (type instanceof Datatype.Numeriek n && n.geheel()) decimalen = 0;
        if (decimalen >= 0) w = w.setScale(decimalen, java.math.RoundingMode.HALF_UP);
        String plain = w.abs().toPlainString();
        String heel = plain, fractie = "";
        int punt = plain.indexOf('.');
        if (punt >= 0) { heel = plain.substring(0, punt); fractie = plain.substring(punt + 1); }
        StringBuilder gegroepeerd = new StringBuilder();
        int n = heel.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) gegroepeerd.append('.');
            gegroepeerd.append(heel.charAt(i));
        }
        String teken = b.signum() < 0 ? "-" : "";
        return teken + gegroepeerd + (fractie.isEmpty() ? "" : "," + fractie);
    }

    /** Het eenheid-woord voor een type (EUR, jr, %, …), of null wanneer het type er geen draagt. */
    private static String eenheidWoord(Datatype type) {
        if (type instanceof Datatype.Percentage) return "%";
        Eenheid e = type == null ? null : type.eenheidOf();
        return e == null ? null : e.afkorting();
    }

    /** Een korte omschrijving van WAT een operand is, uit zijn brontekst (bv. "de maandloon nieuw van EOver"). */
    private static String omschrijf(Expr e) {
        if (e == null) return "de waarde";
        String bron = e.bron().snippet();
        if (bron != null && !bron.isBlank()) return bron.trim();
        if (e instanceof Expr.AttribuutRef ref) return "de " + ref.attribuut();
        if (e instanceof Expr.ParameterRef pr) return "de " + pr.parameter().naam();
        return "de waarde";
    }

    private static String operatorWoorden(Conditie.Operator op) {
        return switch (op) {
            case KLEINER -> "is kleiner dan";
            case KLEINER_OF_GELIJK -> "is kleiner dan of gelijk aan";
            case GROTER -> "is groter dan";
            case GROTER_OF_GELIJK -> "is groter dan of gelijk aan";
            case GELIJK -> "is gelijk aan";
            case ONGELIJK -> "is ongelijk aan";
            case EERDER -> "ligt vóór";
            case EERDER_OF_GELIJK -> "ligt op of vóór";
            case LATER -> "ligt na";
            case LATER_OF_GELIJK -> "ligt op of na";
        };
    }

    // ---------------- bean-reads ----------------

    private static boolean leesBoolean(Object bean, String kenmerk) {
        return Boolean.TRUE.equals(leesRuw(bean, Namen.getter(kenmerk)));
    }

    /** Een getter-read op een bean; probeert getX en isX (kenmerken lezen soms als isX). null bij afwezigheid. */
    private static Object leesRuw(Object bean, String getter) {
        for (String m : new String[]{ getter, "is" + getter.substring(3) }) {
            try { return bean.getClass().getMethod(m).invoke(bean); }
            catch (NoSuchMethodException ignore) { /* probeer de andere vorm */ }
            catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    /** De bronverwijzing van een regel wanneer de IR er een draagt als optionele regelbron-annotatie; anders null
     *  (de grammatica gooit {@code // Bron:}-commentaren vandaag weg — zie de klasse-javadoc). Het model draagt de
     *  annotatie nog niet, dus dit levert vandaag altijd null; de haak zit klaar zodra de annotatie mee de IR in
     *  komt. */
    private String bronVerwijzing(ResolvedRegel r) {
        return null;
    }

    /** Toon de opgeloste globals in modeldeclaratie-volgorde als bijlage-context (parameters + rekendatum),
     *  handig onder een uitleg zodat de gebruiker de gehanteerde normwaarden ziet. Leeg wanneer er geen zijn. */
    public List<String> globalContext() {
        List<String> uit = new ArrayList<>();
        Map<String, RegelSpraakModel.Parameter> perGlobal = new LinkedHashMap<>();
        for (RegelSpraakModel.Parameter p : model.parameters.values())
            perGlobal.put(Namen.ident(p.naam(), false), p);
        for (Map.Entry<String, Object> g : globals.entrySet()) {
            if ("rekendatum".equals(g.getKey())) {
                uit.add("rekendatum: " + renderWaarde(g.getValue(), new Datatype.DatumInDagen()));
                continue;
            }
            RegelSpraakModel.Parameter p = perGlobal.get(g.getKey());
            if (p != null) uit.add("de " + p.naam() + ": " + renderWaarde(g.getValue(), p.datatype()));
        }
        return uit;
    }
}
