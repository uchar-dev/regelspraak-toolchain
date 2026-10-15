package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.ir.Conditie;
import dev.uchar.regelspraak.ir.Expr;
import dev.uchar.regelspraak.ir.ResolvedRegel;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Eenheid;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;
import dev.uchar.regelspraak.model.RegelSpraakModel.Herkomstvorm;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p><b>Vocabulaire.</b> Met een {@link Naamgeving} noemt het verhaal de gedeclareerde namen — objecttypen,
 * attributen, kenmerken, rollen, parameters en enumeratiewaarden — in het gekozen vocabulaire; de brontekst van een
 * operand is regeltekst en blijft zoals hij geschreven is. De uitzondering is een regel die uchar zelf schreef, de
 * regel {@code afwijking opgave X} van de opgave-markering (uchars DIVERGENCES §7): zijn tekst is uchars canonieke
 * spelling, geen tekst van een auteur, en daarin staat de naam van een gelezen attribuut in het gekozen vocabulaire
 * ("zijn opgegeven Datum einde recht op uitkering"). Welke regel dat is, staat in uchars tabel van herkomst
 * ({@link RegelSpraakModel#gegenereerdUit}), niet in de naam. Een enumeratiewaarde waarvan de benaming afwijkt van de
 * gedeclareerde waarde staat er als "benaming · waarde" ("01 · Hoogte inkomsten uit verzekerde arbeid"). Zonder
 * naamgeving is het verhaal canoniek.
 *
 * <p><b>Kenmerksoort.</b> Een kenmerk staat er met het werkwoord van zijn soort (§3.5): bijvoeglijk "is (niet)
 * waardevol", bezittelijk "heeft de uitkeringsduur verstreken" of "heeft geen uitkeringsduur verstreken",
 * zelfstandig "is een recht op grond-van-de-wia" of "is geen recht op grond-van-de-wia"; ook in een voorwaarde. Het
 * lidwoord bij een aanwezig bezittelijk kenmerk is dat van de regeltekst, want het model draagt dat van de declaratie
 * niet.
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
    /** De namen waarin het verhaal de declaraties noemt. */
    private final Naamgeving naamgeving;
    /** Het model waarvan de tabel van herkomst ({@link RegelSpraakModel#gegenereerd}) zegt welke regels uchar
     *  schreef. */
    private final RegelSpraakModel herkomst;

    public Verbalisator(RegelSpraakModel model, Map<String, Object> globals) {
        this(model, globals, Naamgeving.canoniek(model));
    }

    public Verbalisator(RegelSpraakModel model, Map<String, Object> globals, Naamgeving naamgeving) {
        this(model, globals, naamgeving, model);
    }

    /** {@code herkomst} is het model waarvan de tabel van herkomst zegt welke regels uchar schreef: dat van de
     *  frontend. De wire draagt die tabel niet, dus een model dat uchars IR-lader leest heeft haar leeg; wie op zo'n
     *  model uitlegt, geeft hier het model van de frontend. */
    public Verbalisator(RegelSpraakModel model, Map<String, Object> globals, Naamgeving naamgeving,
                        RegelSpraakModel herkomst) {
        this.model = model;
        this.globals = globals == null ? Map.of() : globals;
        this.naamgeving = naamgeving;
        this.herkomst = herkomst;
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
        Objecttype onderwerp = ref.objecttype();
        if (!aanwezig && v == null) return null;   // onvoorwaardelijk maar afwezig: niets te verantwoorden
        // de conclusie van de regel ("Een … heeft de uitkeringsduur verstreken") draagt het lidwoord
        Gezegde gezegde = gezegde(onderwerp, kenmerk, aanwezig, ref.bron() == null ? null : ref.bron().snippet());
        if (gezegde == null)
            throw new IllegalStateException("de kenmerktoekenning '" + r.naam() + "' kent '" + kenmerk + "' toe, en "
                    + onderwerp.naam + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
        boolean uchartekst = doorUcharGeschreven(r);
        StringBuilder b = new StringBuilder(label).append(' ').append(gezegde.hoofdzin());
        if (aanwezig) {
            if (v != null) b.append(", omdat ").append(verbaliseerVoorwaarde(v, bean, onderwerp, uchartekst));
            else b.append(" (onvoorwaardelijk toegekend)");
        } else {
            b.append(", want ").append(eersteFalendeConjunct(v, bean, onderwerp, uchartekst));
        }
        String bron = bronVerwijzing(r);
        if (bron != null) b.append(". Grondslag: ").append(bron);
        return b.toString();
    }

    /** Of uchar de tekst van regel {@code r} schreef: een regel van de opgave-familie, volgens uchars tabel van
     *  herkomst. Een beslistabelrij maakte uchar ook, maar uit de kolomkoppen en cellen van de auteur; die tekst is van
     *  de auteur. */
    private boolean doorUcharGeschreven(ResolvedRegel r) {
        return herkomst.gegenereerdUit(Benoembaar.vorm(RegelSpraakModel.vormnaam(r.naam())))
                .map(g -> g.vorm() == Herkomstvorm.OPGAVE_BOVEN_BEREKENING).orElse(false);
    }

    // ---------------- voorwaarde-verbalisatie ----------------

    /** Een voorwaarde in proza, met de geëvalueerde operandwaarden: WAAROM ze geldt (het toegekende geval); de
     *  niet-toegekende tak gaat via {@link #eersteFalendeConjunct}. {@code onderwerp} is het objecttype van de regel,
     *  vanwaar een kenmerk- of rolnaam in de voorwaarde gelezen wordt; {@code uchartekst} of uchar de regel schreef. */
    private String verbaliseerVoorwaarde(Conditie c, Object bean, Objecttype onderwerp, boolean uchartekst) {
        if (c instanceof Conditie.Samengesteld s) {
            List<String> delen = new ArrayList<>();
            for (Conditie d : s.onderdelen()) delen.add(verbaliseerVoorwaarde(d, bean, onderwerp, uchartekst));
            String voegwoord = switch (s.kwantor()) {
                case ALLE -> " en ";
                case GEEN -> " en niet ";
                default -> " of ";
            };
            return String.join(voegwoord, delen);
        }
        return verbaliseerAtoom(c, bean, onderwerp, uchartekst);
    }

    /** Een enkele (niet-samengestelde) conditie als proza met geëvalueerde operanden. */
    private String verbaliseerAtoom(Conditie c, Object bean, Objecttype onderwerp, boolean uchartekst) {
        if (c instanceof Conditie.Vergelijking vg) {
            return operandProza(vg.links(), bean, uchartekst) + " " + operatorWoorden(vg.op()) + " "
                    + operandProza(vg.rechts(), bean, uchartekst);
        }
        if (c instanceof Conditie.Kenmerk k) {
            Objecttype houder = k.navigatie() == null ? onderwerp
                    : model.rolDoeltypeNaarSubject(onderwerp, k.navigatie());
            Gezegde gezegde = gezegde(houder, k.kenmerk(), !k.negatie(), samenvatting(c));
            // zonder soort geen werkwoord: dan de voorwaarde zoals de regel haar schrijft, zoals hieronder
            if (gezegde == null) return "aan de voorwaarde \"" + samenvatting(c) + "\" is voldaan";
            String wie = k.navigatie() == null ? "het onderwerp" : "de " + naamgeving.rolVanaf(onderwerp, k.navigatie());
            return wie + " " + gezegde.bijzin();
        }
        if (c instanceof Conditie.Rollidmaatschap rl)
            return "het onderwerp " + (rl.negatie() ? "geen " : "een ") + naamgeving.rolVervuld(onderwerp, rl.rol())
                    + " is";
        if (c instanceof Conditie.Predicaat p) {
            String kern = switch (p.soort()) {
                case LEEG -> "leeg is";
                case GEVULD -> "gevuld is";
                default -> "voldoet aan " + p.soort().name().toLowerCase(Locale.ROOT);
            };
            return operandProza(p.operand(), bean, uchartekst) + " " + kern;
        }
        // een nog niet gestructureerde conditievorm: eerlijk de brontekst tonen, geen gok.
        return "aan de voorwaarde \"" + c.bron().snippet().trim() + "\" is voldaan";
    }

    /** Voor een "alle volgende voorwaarden"-regel die NIET vuurde: de EERSTE conjunct die (na evaluatie van zijn
     *  operanden tegen de eindtoestand) niet houdt — de reden dat de toekenning uitbleef. Voor een niet-
     *  samengestelde voorwaarde is dat de voorwaarde zelf. Kan een conjunct niet worden geëvalueerd (een
     *  onopgeloste vorm, een niet-leesbaar operand), dan noemt hij die eerlijk als "niet vast te stellen". */
    private String eersteFalendeConjunct(Conditie c, Object bean, Objecttype onderwerp, boolean uchartekst) {
        if (c instanceof Conditie.Samengesteld s && s.kwantor() == Conditie.Kwantor.ALLE) {
            for (Conditie d : s.onderdelen()) {
                Boolean houdt = evalueer(d, bean);
                if (houdt == null) return "de voorwaarde \"" + samenvatting(d) + "\" niet is vast te stellen";
                if (!houdt) return "niet geldt: " + verbaliseerAtoom(d, bean, onderwerp, uchartekst);
            }
            // alle conjuncten houden maar het kenmerk staat toch niet aan: een producent buiten deze run.
            return "de conclusie niet is bereikt (mogelijk een regel buiten deze berekening)";
        }
        Boolean houdt = evalueer(c, bean);
        if (Boolean.FALSE.equals(houdt)) return "niet geldt: " + verbaliseerAtoom(c, bean, onderwerp, uchartekst);
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
        Datatype.Enumeratie en = enumeratieVan(e);
        return en != null ? render(v, en) : renderWaarde(v, e.type());
    }

    /** {@link #renderWaarde}, met een enumeratiewaarde in het gekozen vocabulaire: een waarde is een declaratie. */
    private String render(Object v, Datatype type) {
        if (v instanceof String s && type instanceof Datatype.Enumeratie e) return enumeratiewaarde(e, s);
        return renderWaarde(v, type);
    }

    /** Een enumeratiewaarde in het gekozen vocabulaire. Wijkt haar benaming daar af van de gedeclareerde waarde,
     *  zoals een code ("01"), dan volgt die waarde erachter: de code alleen zegt de lezer niet waar ze voor staat.
     *  Gelijk zoals uchar gedeclareerde namen vergelijkt ({@link Namen#norm}), of zonder benaming: de naam alleen. */
    private String enumeratiewaarde(Datatype.Enumeratie e, String waarde) {
        String genoemd = naamgeving.waarde(e, waarde);
        for (String w : e.waarden())
            if (Namen.norm(w).equals(Namen.norm(waarde)) && !Namen.norm(genoemd).equals(Namen.norm(w)))
                return genoemd + " · " + w;
        return genoemd;
    }

    /** Het enumeratietype van een operand: dat van de expressie, of voor een attribuut- of parameterread dat van de
     *  declaratie (een read uit uchars lader draagt zelf geen type); null voor elk ander operand. */
    private static Datatype.Enumeratie enumeratieVan(Expr e) {
        if (e.type() instanceof Datatype.Enumeratie en) return en;
        if (e instanceof Expr.AttribuutRef ref && ref.objecttype() != null
                && ref.objecttype().attribuut(ref.attribuut()) instanceof Datatype.Enumeratie en) return en;
        if (e instanceof Expr.ParameterRef pr && pr.parameter().datatype() instanceof Datatype.Enumeratie en) return en;
        return null;
    }

    /** Een operand als leesbaar deel van een vergelijking: een LITERAL toont zijn waarde één keer ("100 km") —
     *  géén verdubbeling van de brontekst; elk ander operand toont zijn omschrijving mét de geëvalueerde waarde
     *  tussen haakjes ("de maandloon nieuw van EOver (3.000,00 EUR)"), zodat de jurist de norm én het feit ziet. */
    private String operandProza(Expr e, Object bean, boolean uchartekst) {
        if (e instanceof Expr.Literal) return renderOperand(e, bean);
        return omschrijf(e, uchartekst) + " (" + renderOperand(e, bean) + ")";
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

    /** Een korte omschrijving van WAT een operand is, uit zijn brontekst (bv. "de maandloon nieuw van EOver"); die is
     *  regeltekst en blijft zoals hij geschreven is, behalve de naam van een gelezen attribuut in een regel die uchar
     *  schreef ({@code uchartekst}). Zonder brontekst noemt hij de declaratie. */
    private String omschrijf(Expr e, boolean uchartekst) {
        if (e == null) return "de waarde";
        String bron = e.bron().snippet();
        if (bron != null && !bron.isBlank()) {
            if (uchartekst && e instanceof Expr.AttribuutRef ref) return inVocabulaire(bron.trim(), ref);
            return bron.trim();
        }
        if (e instanceof Expr.AttribuutRef ref) return "de " + naamgeving.attribuut(ref.objecttype(), ref.attribuut());
        if (e instanceof Expr.ParameterRef pr) return "de " + naamgeving.parameter(pr.parameter().naam());
        return "de waarde";
    }

    /** {@code tekst}, uchars spelling van lezing {@code ref} ("zijn opgegeven beëindigingsdatum"), met de naam van het
     *  gelezen attribuut via {@link Naamgeving}: "zijn opgegeven Datum einde recht op uitkering". De lezing die uchars
     *  IR-lader leest draagt het onderwerpwoord niet, dus blijft de tekst de bron en verandert alleen de naam erin: het
     *  laatste voorkomen, want uchar schrijft de lezing als "zijn &lt;naam&gt;". */
    private String inVocabulaire(String tekst, Expr.AttribuutRef ref) {
        Matcher m = Pattern.compile("(?iu)" + naamPatroon(ref.attribuut())).matcher(tekst);
        int begin = -1, eind = -1;
        while (m.find()) { begin = m.start(); eind = m.end(); }
        if (begin < 0)
            throw new IllegalStateException("uchar schreef de lezing van '" + ref.attribuut() + "' als '" + tekst
                    + "', zonder die naam: de uitleg kan haar niet in het vocabulaire noemen");
        return tekst.substring(0, begin) + naamgeving.attribuut(ref.objecttype(), ref.attribuut())
                + tekst.substring(eind);
    }

    /** Een kenmerk als gezegde: het werkwoord en wat erbij hoort, in de volgorde van een hoofdzin ("heeft de
     *  uitkeringsduur verstreken") of een bijzin ("de uitkeringsduur verstreken heeft"). */
    private record Gezegde(String werkwoord, String naamdeel) {
        String hoofdzin() { return werkwoord + " " + naamdeel; }
        String bijzin() { return naamdeel + " " + werkwoord; }
    }

    /** Kenmerk {@code kenmerk} van {@code houder} als gezegde, met het werkwoord dat §3.5 zijn soort geeft en de
     *  ontkenning die Tabel 16 erbij schrijft: bijvoeglijk "is (niet) X", bezittelijk "heeft &lt;lidwoord&gt; X" of
     *  "heeft geen X", zelfstandig "is een X" of "is geen X". De soort is die van de declaratie, ook in een ander
     *  vocabulaire; de naam is die van {@link Naamgeving}. Het model draagt het lidwoord van een declaratie niet, dus
     *  komt het bij een aanwezig bezittelijk kenmerk uit {@code regeltekst}, de tekst van de regel die het kenmerk
     *  noemt. Null wanneer {@code houder} geen kenmerk {@code kenmerk} kent. */
    private Gezegde gezegde(Objecttype houder, String kenmerk, boolean aanwezig, String regeltekst) {
        Objecttype.KenmerkSoort soort = houder == null ? null : houder.kenmerkSoort(kenmerk);
        if (soort == null) return null;
        String naam = naamgeving.kenmerk(houder, kenmerk);
        return switch (soort) {
            case BIJVOEGLIJK -> new Gezegde("is", aanwezig ? naam : "niet " + naam);
            case ZELFSTANDIG -> new Gezegde("is", (aanwezig ? "een " : "geen ") + naam);
            case BEZITTELIJK -> {
                String lidwoord = aanwezig ? lidwoordVoor(regeltekst, kenmerk) : "geen";
                yield new Gezegde("heeft", lidwoord == null ? naam : lidwoord + " " + naam);
            }
        };
    }

    /** Het lidwoord ("de", "het" of "een") dat {@code regeltekst} direct vóór het laatste voorkomen van de
     *  kenmerknaam schrijft. Het laatste, omdat het gezegde achter het onderwerp staat en het onderwerp dezelfde
     *  woorden kan bevatten ("Een Recht op uitkering WW heeft recht op uitkering"). Null wanneer de tekst daar geen
     *  lidwoord schrijft of de naam niet bevat. */
    private static String lidwoordVoor(String regeltekst, String kenmerk) {
        if (regeltekst == null || kenmerk == null || kenmerk.isBlank()) return null;
        Matcher m = Pattern.compile("(?iu)(?:(?<!" + GRENS + ")(de|het|een)\\s+)?" + naamPatroon(kenmerk))
                .matcher(regeltekst);
        String lidwoord = null;
        while (m.find()) lidwoord = m.group(1);
        return lidwoord == null ? null : lidwoord.toLowerCase(Locale.ROOT);
    }

    /** Een teken dat een woord voortzet: een naam in een tekst staat niet direct naast zo'n teken. */
    private static final String GRENS = "[\\p{L}\\p{N}-]";

    /** De gedeclareerde naam {@code naam} als patroon in een tekst: zijn woorden met witruimte ertussen, als hele
     *  woorden. */
    private static String naamPatroon(String naam) {
        String woorden = String.join("\\s+", Arrays.stream(naam.trim().split("\\s+")).map(Pattern::quote).toList());
        return "(?<!" + GRENS + ")" + woorden + "(?!" + GRENS + ")";
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
            if (p != null) uit.add("de " + naamgeving.parameter(p.naam()) + ": " + render(g.getValue(), p.datatype()));
        }
        return uit;
    }
}
