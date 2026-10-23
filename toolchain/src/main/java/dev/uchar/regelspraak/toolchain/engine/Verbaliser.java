package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.ir.Condition;
import dev.uchar.regelspraak.ir.Default;
import dev.uchar.regelspraak.ir.ExceptionGroup;
import dev.uchar.regelspraak.ir.Expr;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRule;
import dev.uchar.regelspraak.ir.Spelling;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Unit;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.OriginForm;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.RoleBindingRef;
import dev.uchar.regelspraak.loader.NodePositions;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * De UITLEG-generator: verantwoordt een gedraaide beslissing in helder Nederlands, voor de jurist en de burger —
 * niet de programmeur. Uit de gedraaide fact-toestand (de grondwaarheid: een kenmerk staat AAN of niet) plus de
 * getypeerde IR ({@link RegelSpraakModel#resolvedRules}) bouwt hij per gelabelde instantie een verhaal:
 *
 * <ul>
 *   <li>welke kenmerken zijn TOEGEKEND en WAAROM — de voorwaarden met hun geëvalueerde operandwaarden
 *       ("het maandloon nieuw van EOver (1.750,01 EUR) is groter dan de drempelwaarde (1.750,00 EUR)");</li>
 *   <li>welke kenmerktoekenningsregels met een voorwaarde NIET tot toekenning leidden, en aan welke voorwaarde
 *       dat lag — de EERSTE falende conjunct van een "alle volgende voorwaarden"-regel;</li>
 *   <li>afgesloten met een bronverwijzing wanneer de IR die als optionele regelbron-annotatie draagt.</li>
 * </ul>
 *
 * <p><b>De waarden komen van de motor.</b> Of een kenmerk is toegekend, staat op de bean na de terugschrijving. Hoe
 * de voorwaarde van een vorm uitkwam en wat elke knoop ervan vond, leest de uitleg uit de lezingen van een build met
 * lezingen ({@link DroolsBackend#readingsOf}: per vorm, op haar pointer, en object de status en per knoop bereikt of
 * niet, oordeel of waarde met eenheid). De uitleg rekent niets na: zij legt elke lezing op de knopen van het document
 * van de build ({@link Document}, dat uchars lader met de posities van de knopen las) en zet ze in zinnen. Een lege
 * waarde staat er als "(onbekend)"; een lid of operand dat de motor niet bereikte, noemt de zin niet, of zonder
 * waarde. Bij een voorwaarde die niet gold noemt de zin het lid waar de motor stopte. Een kwantorvouw staat er met haar
 * brontekst en het oordeel van de motor, en daarachter de leden die die uitkomst dragen, bij hun naam en met hun
 * aantal ("is niet voldaan: niet voor V2 en V4 (2 van de 4)"). Een lezing die ontbreekt, of een
 * fout of een vorm die niet aan de beurt was waar de zin een oordeel nodig heeft, faalt luid: de uitleg volgt een
 * sessie zonder evaluatiefout.
 *
 * <p><b>Over een rol.</b> Een operand over een rolpad ("de herlevingsgrens van het recht van het Inkomen") heeft de
 * waarde die de motor op het bereikte object las. Een kenmerkcheck over een rol ("de jutter van de Vondst ingezetene
 * is") heet naar de wandeling die de lader naast de check gaf ({@code Loaded.navigationBindings}).
 *
 * <p><b>Vocabulaire.</b> Met een {@link Naming} noemt het verhaal de gedeclareerde namen — objecttypen,
 * attributen, kenmerken, rollen, parameters en enumeratiewaarden — in het gekozen vocabulaire; de brontekst van een
 * operand is regeltekst en blijft zoals hij geschreven is. De uitzondering is een regel die uchar zelf schreef, de
 * regel {@code afwijking opgave X} van de opgave-markering (uchars DIVERGENCES §7): zijn tekst is uchars canonieke
 * spelling, geen tekst van een auteur, en daarin staat de naam van een gelezen attribuut in het gekozen vocabulaire
 * ("zijn opgegeven Datum einde recht op uitkering"). Welke regel dat is, staat in uchars tabel van herkomst
 * ({@link RegelSpraakModel#generatedFrom}) van het uitgelegde model, niet in de naam. Ook een model dat uchars
 * IR-lader leest, draagt die tabel: het IR-document schrijft haar als veld {@code origin} (IR-SPEC ch. 2.13). Een
 * enumeratiewaarde waarvan de benaming afwijkt van de gedeclareerde waarde staat er als "benaming · waarde" ("01 ·
 * Hoogte inkomsten uit verzekerde arbeid"). Zonder naamgeving is het verhaal canoniek.
 *
 * <p><b>Kenmerksoort.</b> Een kenmerk staat er met het werkwoord van zijn soort (§3.5): bijvoeglijk "is (niet)
 * waardevol", bezittelijk "heeft de uitkeringsduur verstreken" of "heeft geen uitkeringsduur verstreken",
 * zelfstandig "is een recht op grond-van-de-wia" of "is geen recht op grond-van-de-wia"; ook in een voorwaarde. Het
 * lidwoord bij een aanwezig bezittelijk kenmerk is dat van de regeltekst, want het model draagt dat van de declaratie
 * niet.
 *
 * <p><b>Defaults-groepen.</b> Een groep ({@code Uitzondering op}, {@code Afwijking van}, {@code Uitzonderingen
 * ongeordend}, de opgavemarkering; uchars RUNTIME-SEMANTICS ch. 7) krijgt per object één zin, uit wat de motor
 * besliste ({@link DroolsBackend#decisionsOf}): de variant die besliste, bij een attribuut met hoe zij haar waarde
 * berekende, zoals de zin van een berekende waarde hieronder, en met haar voorwaarde zoals het IR haar draagt (ch. 7.9
 * telt de voorwaarden van de omsluitende lagen erbij op); elke variant die vóór haar aan de beurt was
 * met de reden dat ze niet besliste; de varianten erna bij naam, als verdrongen. Beslist niemand, dan zegt de zin dat,
 * met wat het doel houdt en per variant van de stapel de reden dat ze niet besliste; een conflict heet een conflict.
 * Elke stapel en haar varianten komen uit de motor, ook waar niemand besliste. Welke variant besliste, rekent de
 * uitleg niet na: zij legt de pointer van de motor op het document van de build ({@link Document}); alleen de reden
 * waarom een variant niet besliste komt uit de eigen evaluatie hieronder. Een variant heet naar haar blok
 * ({@code Default.name}): de basis als "de regel '…'", een variant op het eerste niveau bij haar naam alleen, want de
 * draad legt {@code Uitzondering op} en {@code Afwijking van} daar gelijk vast, en een geneste variant met de keten
 * van haar lagen.
 *
 * <p><b>Berekende waarden.</b> Een attribuut dat de gewone regels van het document op een object schrijven
 * (gelijkstelling, berekening, initialisatie; niet een regel die ch. 7.10 in een stapel opnam, die staat in de zin van
 * haar stapel) krijgt per object één zin over al zijn schrijvers samen ({@link ComputationExplanation}): de regel die
 * gold met de waarde, hoe zij die berekende (de regeltekst van haar waarde, met elke waarde die de berekening las of
 * vouwde, de leden van een vouw met wat elk bijdroeg, en de waarde vóór een afronding en vóór een begrenzing) en
 * waarom zij gold; daarna elke andere schrijver met de reden dat zij niet schreef. Alle waarden komen uit de lezing van
 * de regel ({@link DroolsBackend#readingsOf}), die sinds uchars kaart readings-of-unconditional-forms ook een regel
 * zonder voorwaarde heeft; alleen een invoerwaarde, waar geen regel schreef, staat op de bean.
 *
 * <p><b>Bronverwijzing.</b> Een regel draagt de {@code // Bron:}-annotaties die direct boven haar kop staan
 * ({@link ResolvedRule#statuteAnnotations}; op de draad {@code statuteAnnotations}, uchars IR-SPEC ch. 3.1, WF-082),
 * een default die van zijn blok in {@link Default#statuteAnnotations} (ch. 6.1). De zin van een kenmerktoekenning
 * eindigt dan met ". Grondslag: <tekst>", die van een defaults-groep met de bron van de variant die besliste; zonder
 * annotatie staat er geen (geen verzonnen bron). Het stuk van de grondslag noemt die beslisser in de termen van het IR
 * (een regel bij haar vorm, een default bij zijn groep en zijn pointer daarin), zodat een lezer zijn annotaties en hun
 * plek in de bronnen vindt zonder de zin te lezen.
 *
 * <p><b>Gemaakte objecten.</b> Een object dat een objectcreatie van de sessie maakte ({@link Fixpoint#objects}) heeft
 * geen label; de uitleg noemt het met de naam die uchars canonieke tekst het geeft (RUNTIME-SEMANTICS ch. 5.12),
 * {@code «"<creatie>"»(<onderwerp>)#1}, met het onderwerp zoals de uitleg het noemt: een object van de invoer bij zijn
 * label, een gemaakt object bij deze naam ("«"schrijf een kwitantie"»(«"geef een vangstpenning"»(J1)#1)#1"). Zo'n
 * object krijgt zijn zinnen na de objecten van de invoer, in de volgorde van het fixpunt, en heet zo ook als lid van
 * een kwantorvouw.
 *
 * <p><b>Stukken.</b> Elke zin schrijft de verbalisator op één {@link Sentence.Builder}, als een ondiepe boom van
 * {@link Part}s met een soort: het onderwerp, de namen met hun declaratie, de regeltekst, de waarden, de regelnamen, de
 * verbindingswoorden en de lijm, een voorwaarde als groep met het oordeel van de motor op haar knoop, de leden van een
 * kwantorvouw als groep met hun telling, en de grondslag als laatste stuk, ontleed ({@link Ground}). De platte zin is
 * {@link Sentence#text()}, de tekst van de stukken achter elkaar: {@link #verbalise} geeft die, {@link #sentences} de
 * zinnen zelf.
 */
public final class Verbaliser {

    private final RegelSpraakModel model;
    /** De namen waarin het verhaal de declaraties noemt. */
    private final Naming naming;
    /** Het document van de build, met de posities van zijn knopen. */
    private final Document document;
    /** De lezingen van de sessie, per vorm en object. */
    private final Map<String, Map<Object, ConditionReading>> readings = new HashMap<>();
    /** De naam van elk object dat de uitleg verantwoordt: het label dat {@code verbalise} kreeg, of de naam van een
     *  gemaakt object ({@link #madeName}); de zin van een kwantorvouw noemt haar leden zo. */
    private final Map<Object, String> labels = new IdentityHashMap<>();
    /** De objecten die de creaties van de sessie maakten, in de volgorde van het fixpunt. */
    private final List<Fixpoint.MadeObject> made;

    /**
     * Een verbalisator voor één sessie van een build met lezingen: {@code document} is het document van die build zoals
     * uchars lader het las, met de posities van zijn knopen ({@link Document#of}); {@code readings} wat
     * {@link DroolsBackend#readingsOf} van de sessie gaf; {@code fixpoint} wat {@code DroolsBackend.fixpointOf} van
     * dezelfde sessie gaf, met de labels van de invoer gelezen, waarvan de uitleg de gemaakte objecten leest;
     * {@code naming} de namen, op het model van {@code document}.
     *
     * @throws IllegalStateException twee lezingen van één vorm op één object
     */
    public Verbaliser(Document document, Naming naming, List<ConditionReading> readings, Fixpoint fixpoint) {
        this.document = Objects.requireNonNull(document, "document");
        this.model = document.model();
        this.naming = Objects.requireNonNull(naming, "naming");
        this.made = Objects.requireNonNull(fixpoint, "fixpoint").objects();
        for (ConditionReading r : readings)
            if (this.readings.computeIfAbsent(r.form(), k -> new IdentityHashMap<>()).put(r.object(), r) != null)
                throw new IllegalStateException("de motor gaf twee lezingen van " + r.form() + " op één object");
    }

    /** {@link #verbalise(Map, Map, List)} zonder de defaults-groepen en zonder de vormen buiten de scope: de
     *  kenmerktoekenningen alleen. */
    public List<String> verbalise(Map<String, Object> instances, Map<String, ObjectType> typePerLabel) {
        return texts(sentences(instances, typePerLabel));
    }

    /** {@link #verbalise(Map, Map)} als zinnen met hun stukken. */
    public List<Sentence> sentences(Map<String, Object> instances, Map<String, ObjectType> typePerLabel) {
        Subjects subjects = subjects(instances, typePerLabel);
        List<Sentence> sink = new ArrayList<>();
        for (Map.Entry<String, Object> e : subjects.beans().entrySet()) {
            ObjectType ot = subjects.types().get(e.getKey());
            if (ot == null || e.getValue() == null) continue;
            assignments(e.getKey(), e.getValue(), ot, sink);
        }
        return sink;
    }

    /**
     * Bouw de uitleg voor een gedraaide wereld. {@code instances} mapt een LABEL (het scenario-label of het
     * dienst-verzoek-id) op de bijbehorende, gevuurde en teruggeschreven bean; {@code typePerLabel} het objecttype per
     * label, op het model van het document. Per label eerst één zin per kenmerktoekenning van zijn type, in de volgorde
     * van het document, dan één zin per defaults-groep van zijn type: wat de motor besliste ({@code decisions}, uit
     * {@link DroolsBackend#decisionsOf} van dezelfde sessie), dan één zin per attribuut dat de gewone regels van het
     * document op zijn type schrijven ({@link ComputationExplanation}). Na de labels volgen zo de objecten die een
     * creatie van de sessie maakte, bij hun naam ({@link #madeName}), in de volgorde van het fixpunt, elk met het
     * objecttype waarvan het de bean is. Daarna één zin per regel buiten de scope ({@link Document#outside}) op het
     * type van een label of een gemaakt object.
     *
     * @throws IllegalStateException een pointer die niet op het document terug te leggen is, een beslissing over
     *                               een lid waarvoor het type van het object geen groep heeft, een groep op de
     *                               typeketen van het object zonder beslissing over haar doel, een lezing die
     *                               ontbreekt of niet past bij wat de zin zegt, of een gemaakt object dat de uitleg
     *                               niet kan noemen ({@link #madeName})
     */
    public List<String> verbalise(Map<String, Object> instances, Map<String, ObjectType> typePerLabel,
                                  List<DefaultsDecision> decisions) {
        return texts(sentences(instances, typePerLabel, decisions));
    }

    /** {@link #verbalise(Map, Map, List)} als zinnen met hun stukken. */
    public List<Sentence> sentences(Map<String, Object> instances, Map<String, ObjectType> typePerLabel,
                                    List<DefaultsDecision> decisions) {
        Objects.requireNonNull(decisions, "decisions");
        Subjects subjects = subjects(instances, typePerLabel);
        List<Sentence> sink = new ArrayList<>();
        Set<String> outside = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : subjects.beans().entrySet()) {
            String label = e.getKey();
            Object bean = e.getValue();
            ObjectType ot = subjects.types().get(label);
            if (ot == null || bean == null) continue;
            assignments(label, bean, ot, sink);
            ComputationExplanation computations = new ComputationExplanation(label, bean, ot, decisions);
            new DefaultsExplanation(document, label, bean, ot, decisions, computations).sentences(sink);
            computations.sentences(sink);
            document.outside().forEach((rule, type) -> {
                for (ObjectType t : ot.chain()) if (t.name.equalsIgnoreCase(type)) outside.add(rule);
            });
        }
        for (String rule : outside)
            sink.add(Sentence.builder().text("de regel '").leaf(Part.Kind.RULE, rule)
                    .text("' staat buiten deze berekening").build());
        return sink;
    }

    private static List<String> texts(List<Sentence> sentences) {
        return sentences.stream().map(Sentence::text).toList();
    }

    /** Wie de uitleg verantwoordt, bij naam, met het objecttype van elk. */
    private record Subjects(Map<String, Object> beans, Map<String, ObjectType> types) {}

    /** De objecten van de invoer onder hun label, in de volgorde van {@code instances}, en daarna elk object dat een
     *  creatie van de sessie maakte onder zijn naam ({@link #madeName}), in de volgorde van het fixpunt, met het
     *  objecttype waarvan het de bean is. Zet ook {@link #labels}. */
    private Subjects subjects(Map<String, Object> instances, Map<String, ObjectType> typePerLabel) {
        labels.clear();
        instances.forEach((label, bean) -> {
            if (bean != null && labels.put(bean, label) != null)
                throw new IllegalStateException("het object van " + label + " staat onder twee labels");
        });
        Map<Object, Fixpoint.MadeObject> byObject = new IdentityHashMap<>();
        Map<Object, Set<String>> creationsPerSubject = new IdentityHashMap<>();
        for (Fixpoint.MadeObject g : made) {
            if (labels.containsKey(g.object()))
                throw new IllegalStateException("het fixpunt noemt " + labels.get(g.object()) + " van de invoer een"
                        + " gemaakt object, van de creatie '" + g.creation() + "'");
            if (byObject.put(g.object(), g) != null)
                throw new IllegalStateException("het fixpunt noemt een object van de creatie '" + g.creation()
                        + "' twee keer");
            // de naam draagt #1: een creatie maakt één object per onderwerp (RUNTIME-SEMANTICS ch. 5.5 en 5.12)
            if (!creationsPerSubject.computeIfAbsent(g.subject(), s -> new HashSet<>()).add(g.creation()))
                throw new IllegalStateException("het fixpunt geeft twee objecten van de creatie '" + g.creation()
                        + "' voor één onderwerp, en een creatie maakt één object per onderwerp (RUNTIME-SEMANTICS"
                        + " ch. 5.5)");
        }
        Map<String, Object> beans = new LinkedHashMap<>(instances);
        Map<String, ObjectType> types = new HashMap<>(typePerLabel);
        for (Fixpoint.MadeObject g : made) {
            String name = madeName(g.object(), byObject, new ArrayList<>());
            if (beans.put(name, g.object()) != null)
                throw new IllegalStateException("het gemaakte object " + name + " heeft de naam van een object van de"
                        + " invoer");
            types.put(name, madeType(name, g.object()));
        }
        return new Subjects(beans, types);
    }

    /**
     * De naam van gemaakt object {@code o} zoals uchars canonieke tekst haar schrijft (RUNTIME-SEMANTICS ch. 5.12):
     * {@code «"<creatie>"»(<onderwerp>)#1}, de naam van de creatie tussen aanhalingstekens en ontsnapt zoals uchar
     * haar schrijft, het object waarvoor zij het maakte en het nummer onder de objecten die zij daarvoor maakte, dat 1
     * is. Het onderwerp heet zoals de uitleg het elders noemt: een object van de invoer bij zijn label, zonder
     * aanhalingstekens, een gemaakt object bij zijn eigen naam. {@code byObject} geeft de gemaakte objecten van het
     * fixpunt; {@code path} de objecten waarvan de naam op deze wacht.
     *
     * @throws IllegalStateException een onderwerp dat geen object van de invoer en geen gemaakt object is, of gemaakte
     *                               objecten die elkaars onderwerp zijn in een kring
     */
    private String madeName(Object o, Map<Object, Fixpoint.MadeObject> byObject, List<Object> path) {
        String known = labels.get(o);
        if (known != null) return known;
        Fixpoint.MadeObject g = byObject.get(o);
        if (g == null)
            throw new IllegalStateException("het fixpunt noemt als onderwerp van een gemaakt object een "
                    + o.getClass().getSimpleName() + " dat geen object van de invoer en geen gemaakt object is");
        if (path.stream().anyMatch(p -> p == o))
            throw new IllegalStateException("de gemaakte objecten van de creatie '" + g.creation() + "' zijn elkaars"
                    + " onderwerp in een kring");
        path.add(o);
        String name = "«" + quoted(g.creation()) + "»(" + madeName(g.subject(), byObject, path) + ")#1";
        path.remove(path.size() - 1);
        labels.put(o, name);
        return name;
    }

    /** {@code s} tussen dubbele aanhalingstekens, met een backslash, een aanhalingsteken en een regeleinde ontsnapt,
     *  zoals uchars canonieke tekst een naam schrijft. */
    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                + "\"";
    }

    /** Het objecttype van gemaakt object {@code o}, dat {@code name} heet: het type waarvan het de gegenereerde bean
     *  is, want uchars {@link Fixpoint.MadeObject} noemt geen type. */
    private ObjectType madeType(String name, Object o) {
        String bean = o.getClass().getName();
        for (ObjectType ot : model.objectTypes.values())
            if (bean.equals(FactModelGenerator.PACKAGE + "." + Namen.ident(ot.name, true))) return ot;
        throw new IllegalStateException("het gemaakte object " + name + " is een " + bean + ", de bean van geen"
                + " objecttype van het model van de uitleg");
    }

    private void assignments(String label, Object bean, ObjectType ot, List<Sentence> sink) {
        for (ResolvedRule r : model.resolvedRules) {
            if (r.kind() != ResolvedRule.Kind.KENMERKTOEKENNING) continue;
            if (!(r.target() instanceof Expr.AttributeReference ref) || ref.objectType() == null) continue;
            // IR-SPEC ch. 2.2: de regel geldt voor elk objecttype dat het doeltype IS (⊑), niet alleen voor
            // het doeltype zelf — de gegenereerde bean erft native, dus een kenmerktoekenning op het
            // supertype kent het kenmerk óók toe aan een subtype-instantie. Op naamgelijkheid vergelijken
            // laat precies die toekenningen onverklaard: het kenmerk staat AAN en het verhaal zwijgt.
            if (!ot.isA(ref.objectType())) continue;
            Sentence sentence = verbaliseAssignment(label, bean, ref, r);
            if (sentence != null) sink.add(sentence);
        }
    }

    /**
     * Het document waaruit de build gemaakt is, zoals uchars lader het las ({@link IrLoader#loadWithPositions}): het
     * model, de defaults-groepen ernaast, waarop de pointers van {@link DroolsBackend#decisionsOf} en de vormen van de
     * lezingen terug te leggen zijn, de wandeling van elke lezing en kenmerkcheck over een rol, en de posities van de
     * knopen, waarop de nummers van de lezingen liggen. De emitter schrijft elke groep, default, uitzonderingsgroep en
     * elk lid op zijn index, en een regel als vorm met haar versies ("naam #i"). {@code outside} noemt de regels van de
     * bron die een kenmerk of een attribuut toekennen en die de scope van een entrypoint niet draagt, met het objecttype
     * van hun doel: zij hebben in de build geen regel en dus geen lezing.
     */
    public record Document(IrLoader.Loaded loaded, NodePositions positions, Map<String, String> outside) {
        public Document {
            Objects.requireNonNull(loaded, "loaded");
            Objects.requireNonNull(positions, "positions");
            outside = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outside));
        }

        /** Het document zoals de lader het met posities las, zonder vormen buiten een scope. */
        public static Document of(IrLoader.Positioned positioned) {
            return new Document(positioned.loaded(), positioned.positions(), Map.of());
        }

        /** Dit document met de regels buiten de scope: vormnaam op het objecttype van hun doel. */
        public Document withOutside(Map<String, String> rules) {
            return new Document(loaded, positions, rules);
        }

        public RegelSpraakModel model() { return loaded.model(); }

        public List<PrioritizedDefaults> groups() { return loaded.prioritizedDefaults(); }

        public List<ResolvedRule> rules() { return loaded.model().resolvedRules; }
    }

    // ---------------- defaults-groepen ----------------

    private static final Pattern DEFAULT_POINTER = Pattern.compile(
            "/rules/prioritizedDefaults/([0-9]+)/defaults/([0-9]+)((?:/exceptions/members/[0-9]+/defaults/[0-9]+)*)");
    private static final Pattern NESTED_STEP = Pattern.compile("/exceptions/members/([0-9]+)/defaults/([0-9]+)");
    private static final Pattern RULE_POINTER = Pattern.compile("/rules/rules/([0-9]+)/versions/([0-9]+)");
    /** De pointer van een default, met als groep 1 zijn pointer in zijn groep. */
    private static final Pattern IN_GROUP = Pattern.compile(
            "/rules/prioritizedDefaults/[0-9]+(/defaults/[0-9]+(?:/exceptions/members/[0-9]+/defaults/[0-9]+)*)");
    private static final Pattern VERSION = Pattern.compile("([\\s\\S]*) #([0-9]+)");

    /** Een variant van een stapel: een default van een groep van het document, of een gewone regel die de stapel van
     *  een subtype als basis opneemt (ch. 7.10). {@code holder} is de default die haar in een uitzonderingsgroep
     *  draagt, null aan de wortel; {@code member} de index van die uitzonderingsgroep, {@code place} haar index in haar
     *  keten. */
    private record Variant(String pointer, Default d, ResolvedRule rule, PrioritizedDefaults group, Variant holder,
                           int member, int place) {

        boolean hasOwnContent() { return rule != null || d.hasOwnContent(); }

        /** De varianten die deze default in zijn uitzonderingsgroepen draagt, per groep, in de volgorde van de
         *  draad. */
        List<List<Variant>> members() {
            List<List<Variant>> sink = new ArrayList<>();
            if (rule != null || d.exceptions() == null) return sink;
            List<ExceptionGroup> groups = d.exceptions().members();
            for (int m = 0; m < groups.size(); m++) {
                List<Variant> chain = new ArrayList<>();
                List<Default> defaults = groups.get(m).defaults();
                for (int l = 0; l < defaults.size(); l++)
                    chain.add(new Variant(pointer + "/exceptions/members/" + m + "/defaults/" + l, defaults.get(l),
                            null, group, this, m, l));
                sink.add(chain);
            }
            return sink;
        }

        boolean unordered() { return d != null && d.exceptions() != null && d.exceptions().unordered(); }

        /** Of de default op {@code p} deze variant is of onder haar staat. */
        boolean holds(String p) { return p.equals(pointer) || p.startsWith(pointer + "/"); }
    }

    /**
     * De beslisser op {@code pointer} in de sleutels van een {@code GROUND} ({@link #deciderOf}): een default of de
     * versie van een gewone regel die de stapel van een subtype opneemt (ch. 7.10), zoals de motor ze noemt
     * ({@link DroolsBackend#decisionsOf}, {@code TmsTranspiler.originOf}). {@code document} is het document waarvan de
     * motor de pointer gaf. Zo noemen de uitleg en de playground ({@code drlRules}) een beslisser op één manier.
     *
     * @throws IllegalStateException een pointer die niet op {@code document} terug te leggen is
     */
    public static Map<String, Object> decider(Document document, String pointer) {
        return deciderOf(variantAt(document, pointer));
    }

    /** De variant op {@code pointer} in {@code document}; faalt luid op een pointer die er niet op terug te leggen
     *  is. */
    private static Variant variantAt(Document document, String pointer) {
        Matcher dm = DEFAULT_POINTER.matcher(pointer);
        if (dm.matches()) {
            int i = Integer.parseInt(dm.group(1)), j = Integer.parseInt(dm.group(2));
            if (i >= document.groups().size()) throw unresolved(pointer, "het document heeft "
                    + document.groups().size() + " defaults-groep(en)");
            PrioritizedDefaults g = document.groups().get(i);
            if (j >= g.defaults().size()) throw unresolved(pointer, "de groep '" + g.name() + "' heeft "
                    + g.defaults().size() + " default(s)");
            Variant v = new Variant("/rules/prioritizedDefaults/" + i + "/defaults/" + j, g.defaults().get(j), null,
                    g, null, -1, j);
            Matcher step = NESTED_STEP.matcher(dm.group(3));
            while (step.find()) {
                int m = Integer.parseInt(step.group(1)), l = Integer.parseInt(step.group(2));
                List<List<Variant>> members = v.members();
                if (m >= members.size() || l >= members.get(m).size())
                    throw unresolved(pointer, "bij " + v.pointer() + " staat geen uitzonderingsgroep " + m
                            + " met een default " + l);
                v = members.get(m).get(l);
            }
            return v;
        }
        Matcher rm = RULE_POINTER.matcher(pointer);
        if (rm.matches())
            return ruleVariant(document, pointer, Integer.parseInt(rm.group(1)), Integer.parseInt(rm.group(2)));
        throw unresolved(pointer, "dat is geen pointer naar een default of een regelversie");
    }

    /** De regelversie {@code /rules/rules/k/versions/v}: het document schrijft de regels als vormen, een vorm met
     *  meer versies als de opeenvolgende regels "… #1" tot en met "… #n" van het model, zoals uchar de pointer
     *  maakt. */
    private static Variant ruleVariant(Document document, String pointer, int form, int version) {
        int k = -1, w = 0;
        String previous = null;
        for (ResolvedRule r : document.rules()) {
            if (r.name() == null)
                throw unresolved(pointer,
                        "een regel zonder naam laat open bij welke vorm van het document hij hoort");
            if (previous != null && nextVersion(previous, r.name())) w++;
            else { k++; w = 0; }
            previous = r.name();
            if (k == form && w == version) return new Variant(pointer, null, r, null, null, -1, 0);
            if (k > form) break;
        }
        throw unresolved(pointer, "het document heeft geen vorm " + form + " met een versie " + version);
    }

    private static IllegalStateException unresolved(String pointer, String why) {
        return new IllegalStateException("de pointer " + pointer + " van de motor is niet op het document terug te"
                + " leggen: " + why);
    }

    /** De beslisser waarvan de grondslag de annotaties is, in de termen van het IR, zodat een lezer hem en zijn
     *  annotaties in het document vindt zonder de zin te lezen: een regel bij de naam van haar vorm
     *  ({@link #ruleDecider}), een default bij de naam van zijn groep ({@code group}, null waar het document de groep
     *  geen naam geeft), zijn pointer in die groep ({@code pointer}: die van de motor zonder de plaats van de groep
     *  in het document, want een scope verschuift die en de naam niet), zijn plaats in zijn keten ({@code position},
     *  1-gebaseerd, zoals de zin hem noemt) en zijn naam ({@code variant}) waar hij er een heeft. */
    private static Map<String, Object> deciderOf(Variant v) {
        if (v.rule() != null) return ruleDecider(v.rule());
        Matcher m = IN_GROUP.matcher(v.pointer());
        if (!m.matches())
            throw new IllegalStateException("de default " + v.pointer() + " heeft geen pointer in een defaults-groep");
        Map<String, Object> meta = Part.meta("group", v.group().name(), "pointer", m.group(1),
                "position", v.place() + 1);
        if (v.d().name() != null) meta.put("variant", v.d().name());
        return meta;
    }

    /** Wat een variant van een beslissing was: vóór de beslisser aan de beurt en niet beslist, de beslisser, erna en
     *  verdrongen, of een lid van een ongeordende groep na het lid dat besliste, dat niet anders besliste (het kan
     *  hetzelfde besloten hebben of niets: de motor noemt alleen het eerste). */
    private enum Role { BEFORE, DECIDER, AFTER, SIBLING }

    private record Turn(Variant variant, Role role) {}

    /** De zinnen over de defaults-groepen van één object. */
    private final class DefaultsExplanation {
        private final Document document;
        private final String label;
        private final Object bean;
        private final ObjectType type;
        private final List<DefaultsDecision> decisions;
        /** De berekeningen van hetzelfde object: de beslisser van een attribuut krijgt de berekening van een gewone
         *  regel. */
        private final ComputationExplanation computations;

        DefaultsExplanation(Document document, String label, Object bean, ObjectType type,
                            List<DefaultsDecision> decisions, ComputationExplanation computations) {
            this.document = document;
            this.label = label;
            this.bean = bean;
            this.type = type;
            this.decisions = decisions;
            this.computations = computations;
        }

        void sentences(List<Sentence> sink) {
            // Elke stapel komt uit decisionsOf: elk object van de sessie heeft per stapel van zijn type één record,
            // ook waar niemand besliste (ch. 7.10: per concreet type en doel één stapel). Het document geeft alleen
            // de volgorde, die van de eerste groep die het doel op de typeketen van het object schrijft.
            List<String> members = new ArrayList<>();
            for (PrioritizedDefaults g : document.groups())
                if (type.isA(subjectOf(g)) && !members.contains(g.target())) members.add(g.target());
            Map<String, DefaultsDecision> perMember = new LinkedHashMap<>();
            for (DefaultsDecision d : decisions) {
                if (d.object() != bean) continue;
                if (!members.contains(d.member()))
                    throw new IllegalStateException("de motor gaf een beslissing over '" + d.member() + "' van "
                            + label + ", en het document heeft voor " + type.name + " geen defaults-groep op dat lid");
                if (perMember.put(d.member(), d) != null)
                    throw new IllegalStateException("de motor gaf twee beslissingen over '" + d.member() + "' van "
                            + label + ", waar één stapel er één geeft");
            }
            for (String member : members) {
                DefaultsDecision d = perMember.get(member);
                if (d == null)
                    throw new IllegalStateException("de motor gaf geen beslissing over '" + member + "' van " + label
                            + ", en het document heeft voor " + type.name + " een defaults-groep op dat lid: elk"
                            + " object van de sessie heeft er één per stapel van zijn type");
                if (d.conflict() != null) sink.add(conflicted(d));
                else if (d.decider() != null) sink.add(decided(d));
                else sink.add(undecided(d));
            }
        }

        // ---- de drie zinnen ----

        private Sentence decided(DefaultsDecision d) {
            List<List<Variant>> chains = new ArrayList<>();
            for (List<String> chain : d.rootChains()) chains.add(chain.stream().map(this::variant).toList());
            String pointer = d.decider().pointer();
            List<Turn> turns = new ArrayList<>();
            walkRoot(chains, pointer, turns);
            Variant decider = turns.stream().filter(t -> t.role() == Role.DECIDER).findFirst()
                    .orElseThrow(() -> new IllegalStateException("de beslisser " + pointer + " van '" + d.member()
                            + "' van " + label + " heeft geen eigen besluit of waarde in het document"))
                    .variant();
            if (!decider.hasOwnContent())
                throw new IllegalStateException("de beslisser " + pointer + " van '" + d.member() + "' van " + label
                        + " is een groep-default zonder eigen besluit of waarde");
            requireTarget(decider, d.member());
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            outcome(b, d.member(), d.characteristic(), d.decider().decision());
            b.text(" volgens ");
            name(b, decider);
            reason(b, decider, d.characteristic());
            List<Variant> after = new ArrayList<>();
            for (Turn t : turns) {
                switch (t.role()) {
                    case BEFORE -> {
                        b.text("; ");
                        name(b, t.variant());
                        b.text(" ");
                        notDecided(b, t.variant(), d.characteristic(), d.member());
                    }
                    case SIBLING -> {
                        b.text("; ");
                        name(b, t.variant());
                        b.text(" besliste niet anders");
                    }
                    case AFTER -> after.add(t.variant());
                    case DECIDER -> { }
                }
            }
            for (int i = 0; i < after.size(); i++) {
                b.text(i == 0 ? "; verdrongen: " : ", ");
                name(b, after.get(i));
            }
            if (d.characteristic() && Boolean.FALSE.equals(d.decider().decision()) && readBoolean(bean, d.member()))
                b.text("; de invoer kende het kenmerk toe, en dat blijft staan");
            ground(b, groundOf(decider), deciderOf(decider));
            return b.build();
        }

        private Sentence conflicted(DefaultsDecision d) {
            DefaultsDecision.Conflict c = d.conflict();
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            standing(b, d.member(), d.characteristic());
            b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" er is een conflict");
            if (c.pointer() != null) {
                b.text(" in de uitzonderingen op ");
                carrierName(b, variant(c.pointer()));
            }
            b.text(": ");
            boolean first = true;
            for (DefaultsDecision.Decider x : c.deciders()) {
                Variant v = variant(x.pointer());
                requireTarget(v, d.member());
                if (!first) b.text(" ").leaf(Part.Kind.CONNECTIVE, "en").text(" ");
                first = false;
                name(b, v);
                b.text(" besliste '");
                outcome(b, d.member(), d.characteristic(), x.decision());
                b.text("'");
            }
            b.text(", dus de groep schrijft niets");
            return b.build();
        }

        /** Niemand besliste: wat het doel houdt, en per variant van de stapel waarom ze niet besliste. De varianten en
         *  hun volgorde zijn die van het record ({@link DefaultsDecision#rootChains}), ook waar ch. 7.10 de stapel uit
         *  twee declaraties samenstelt. */
        private Sentence undecided(DefaultsDecision d) {
            List<Turn> turns = new ArrayList<>();
            for (List<String> chain : d.rootChains())
                for (String pointer : chain) {
                    Variant v = variant(pointer);
                    requireTarget(v, d.member());
                    all(v, Role.BEFORE, turns);
                }
            if (turns.isEmpty())
                throw new IllegalStateException("de motor gaf over '" + d.member() + "' van " + label
                        + " een stapel zonder varianten: " + d.rootChains());
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            standing(b, d.member(), d.characteristic());
            b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" geen variant besliste");
            for (Turn t : turns) {
                b.text("; ");
                name(b, t.variant());
                b.text(" ");
                notDecided(b, t.variant(), d.characteristic(), d.member());
            }
            return b.build();
        }

        // ---- volgorde: wie was vóór de beslisser aan de beurt, wie erna ----

        /** De wortel: één keten, of bij een ongeordende wortel (ch. 7.2) één per groep, die naast elkaar
         *  beslissen. */
        private void walkRoot(List<List<Variant>> chains, String decider, List<Turn> sink) {
            int at = -1;
            for (int c = 0; c < chains.size() && at < 0; c++)
                for (Variant v : chains.get(c)) if (v.holds(decider)) { at = c; break; }
            if (at < 0) throw new IllegalStateException("de beslisser " + decider + " van " + label
                    + " staat in geen keten aan de wortel van zijn stapel");
            for (int c = 0; c < chains.size(); c++) {
                if (c == at) walkChain(chains.get(c), decider, sink);
                else for (Variant v : chains.get(c)) all(v, c < at ? Role.BEFORE : Role.SIBLING, sink);
            }
        }

        /** Een keten (ch. 7.1): de varianten erboven beslisten niet, die eronder zijn verdrongen. */
        private void walkChain(List<Variant> chain, String decider, List<Turn> sink) {
            int at = -1;
            for (int j = 0; j < chain.size(); j++) if (chain.get(j).holds(decider)) at = j;
            for (int j = 0; j < chain.size(); j++) {
                if (j < at) all(chain.get(j), Role.BEFORE, sink);
                else if (j == at) walkVariant(chain.get(j), decider, sink);
                else all(chain.get(j), Role.AFTER, sink);
            }
        }

        /** Een default met uitzonderingsgroepen (ch. 7.9): de leden eerst, de eigen inhoud is de terugval. */
        private void walkVariant(Variant v, String decider, List<Turn> sink) {
            List<List<Variant>> members = v.members();
            if (v.pointer().equals(decider)) {
                for (List<Variant> chain : members) for (Variant x : chain) all(x, Role.BEFORE, sink);
                sink.add(new Turn(v, Role.DECIDER));
                return;
            }
            int at = -1;
            for (int m = 0; m < members.size() && at < 0; m++)
                for (Variant x : members.get(m)) if (x.holds(decider)) { at = m; break; }
            for (int m = 0; m < members.size(); m++) {
                if (m == at) walkChain(members.get(m), decider, sink);
                else for (Variant x : members.get(m))
                    all(x, m < at ? Role.BEFORE : v.unordered() ? Role.SIBLING : Role.AFTER, sink);
            }
            if (v.hasOwnContent()) sink.add(new Turn(v, Role.AFTER));
        }

        /** {@code v} en wat het draagt, in de volgorde waarin het aan de beurt komt, met één rol. */
        private void all(Variant v, Role role, List<Turn> sink) {
            for (List<Variant> chain : v.members()) for (Variant x : chain) all(x, role, sink);
            if (v.hasOwnContent()) sink.add(new Turn(v, role));
        }

        // ---- de delen van een zin ----

        /** Het doel met zijn waarde of besluit: bij een kenmerk de hoofdzin, ook de ontkende, bij een attribuut de
         *  waarde in de opmaak van een operand. */
        private void outcome(Sentence.Builder b, String member, boolean characteristic, Object decision) {
            if (characteristic) {
                predicateOf(member, Boolean.TRUE.equals(decision)).mainClause(b);
                return;
            }
            b.text("heeft als ");
            attributeName(b, type, member);
            b.text(" ");
            value(b, render(decision, type.attribute(member)), decision == null);
        }

        /** Wat het doel houdt waar de groep niets schreef: het kenmerk zoals de invoer het gaf, de invoerwaarde of
         *  leeg. */
        private void standing(Sentence.Builder b, String member, boolean characteristic) {
            if (characteristic) {
                boolean present = readBoolean(bean, member);
                predicateOf(member, present).mainClause(b);
                if (present) b.text(" uit de invoer");
                return;
            }
            attributeStanding(b, bean, type, member);
        }

        private Predicate predicateOf(String member, boolean present) {
            // het IR draagt de doeltekst van een default niet, dus ook niet het lidwoord van een bezittelijk kenmerk
            Predicate p = predicate(type, member, present, null);
            if (p == null)
                throw new IllegalStateException("een defaults-groep beslist over '" + member + "', en " + type.name
                        + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
            return p;
        }

        /** Waarom de beslisser besliste: haar voorwaarde met de waarden van de operanden. Bij een attribuut staat
         *  daarvoor hoe zij haar waarde berekende ({@link #computation}), na " (onvoorwaardelijk)" waar zij geen
         *  voorwaarde heeft, zoals in de zin van een gewone regel. */
        private void reason(Sentence.Builder b, Variant v, boolean characteristic) {
            Condition c = conditionOf(v);
            if (c == null && isInitialisation(v)) {
                computation(b, v, characteristic);
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                if (characteristic) b.text("het kenmerk");
                else {
                    b.text("de ");
                    attributeName(b, type, targetOf(v));
                }
                b.text(" in de invoer leeg was");
                return;
            }
            if (c == null || c instanceof Condition.Compound s && s.quantifier() == Condition.Quantifier.ALLE
                    && s.components().isEmpty()) {
                b.text(" (onvoorwaardelijk)");
                computation(b, v, characteristic);
                return;
            }
            ConditionReading.Status status = reading(v.pointer(), bean, label).status();
            Read read = read(v.pointer(), bean, label, c);
            if (status != ConditionReading.Status.HOLDS)
                throw new IllegalStateException("de beslisser " + v.pointer() + " van " + label + " heeft de lezing "
                        + status + ", en een variant die besliste heeft een voorwaarde die geldt");
            computation(b, v, characteristic);
            b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
            verbaliseCondition(b, c, read, subjectOf(v), ucharText(v));
        }

        /** Hoe de beslisser van een attribuut haar waarde berekende, zoals de zin van een gewone regel het zegt
         *  ({@link ComputationExplanation#computation}): de waarde van een default is zijn consequentie, die van een
         *  regel die ch. 7.10 in de stapel opnam haar waarde. In de lezing van de beslisser volgen de knopen van die
         *  waarde op die van haar voorwaarde zoals het IR haar draagt (ch. 5.12); zonder voorwaarde, ook waar ch. 7.10
         *  haar de lege rechtvaardiging geeft die nergens in het document staat, heeft zij alleen die van haar waarde.
         *  Een kenmerk heeft geen waarde om te berekenen. */
        private void computation(Sentence.Builder b, Variant v, boolean characteristic) {
            if (characteristic) return;
            Expr value = v.rule() != null ? v.rule().value() : v.d().consequence();
            Condition c = conditionOf(v);
            int offset = c == null ? 0 : document.positions().subtree(c).size();
            computations.computation(b, value, ucharText(v), read(v.pointer(), bean, label, value, offset));
        }

        /** Waarom een variant die aan de beurt was niet besliste: een kenmerkvariant waarvan de voorwaarde geldt,
         *  beslist altijd, dus gold haar voorwaarde niet; een attribuutvariant beslist ook niet waar haar waarde leeg
         *  is (ch. 7.1). */
        private void notDecided(Sentence.Builder b, Variant v, boolean characteristic, String member) {
            Condition c = conditionOf(v);
            ObjectType subject = subjectOf(v);
            if (c == null && isInitialisation(v)) {
                initialisationNotDecided(b, member);
                return;
            }
            if (c == null) {
                if (!characteristic) {
                    b.text("gaf geen waarde");
                    return;
                }
                throw new IllegalStateException("de variant " + v.pointer() + " van '" + member + "' van " + label
                        + " heeft geen voorwaarde, en een kenmerkvariant zonder voorwaarde beslist altijd");
            }
            ConditionReading.Status status = reading(v.pointer(), bean, label).status();
            switch (status) {
                case HOLDS -> {
                    if (!characteristic) {
                        b.text("gaf geen waarde");
                        return;
                    }
                    throw new IllegalStateException("de variant " + v.pointer() + " van '" + member + "' van " + label
                            + " heeft een voorwaarde die geldt en besliste niet, en een kenmerkvariant waarvan de"
                            + " voorwaarde geldt, beslist altijd");
                }
                case FAILS -> {
                    Read read = read(v.pointer(), bean, label, c);
                    didNotHoldBecause(b).text(" ");
                    firstFailing(b, c, read, subject, ucharText(v));
                }
                case OUTSIDE_VALIDITY ->
                        didNotHoldBecause(b).text(" zij valt op de rekendatum buiten haar geldigheid");
                case DISPLACED -> {
                    // een initialisatie wacht op haar beurt: het doel heeft een waarde van de invoer
                    if (isInitialisation(v)) {
                        initialisationNotDecided(b, member);
                        return;
                    }
                    throw new IllegalStateException("de variant " + v.pointer() + " van '" + member + "' van " + label
                            + " was volgens de motor niet aan de beurt, en de beslissing noemt haar vóór de beslisser");
                }
                case FAULTS -> throw faulted(v.pointer(), label);
            }
        }

        private void initialisationNotDecided(Sentence.Builder b, String member) {
            if (readRaw(bean, Namen.getter(member)) == null) {
                b.text("gaf geen waarde");
                return;
            }
            didNotHoldBecause(b).text(" de ");
            attributeName(b, type, member);
            b.text(" had een invoerwaarde");
        }

        // ---- namen ----

        /** De naam van een variant: de basis als "de regel '…'", een variant aan de wortel bij haar naam alleen, een
         *  geneste met de keten van haar lagen; zonder naam bij de opgavemarkering haar bron, anders haar plaats. */
        private void name(Sentence.Builder b, Variant v) {
            if (v.rule() != null) {
                b.text("de regel '").leaf(Part.Kind.RULE, RegelSpraakModel.formName(v.rule().name())).text("'");
                return;
            }
            String name = v.d().name();
            if (name == null) {
                if (generatedOpgave(v)) {
                    // de opgavemarkering noemt de default naar het attribuut van zijn bron, in het vocabulaire
                    b.text("'");
                    attributeName(b, subjectOf(v), v.d().source());
                    b.text("'");
                } else if (v.holder() == null) {
                    b.text("de variant op plaats " + (v.place() + 1) + " van ");
                    groupLabel(b, v.group());
                } else {
                    b.text("de variant op plaats " + (v.place() + 1) + " van uitzonderingsgroep " + (v.member() + 1)
                            + " van ");
                    name(b, v.holder());
                }
                return;
            }
            if (v.holder() == null) {
                b.text(name.equals(v.group().name()) ? "de regel '" : "'").leaf(Part.Kind.RULE, name).text("'");
                return;
            }
            List<String> layers = new ArrayList<>();
            for (Variant h = v.holder(); h != null; h = h.holder())
                if (h.d().name() != null) layers.add(h.d().name());
            // de groep-default van 'B voor S' draagt de blokken die op het eerste niveau van B voor subtype S staan
            String basis = layers.isEmpty() && forSubtype(v.group()) ? null : basisOf(v.group());
            if (basis != null && (layers.isEmpty() || !layers.get(layers.size() - 1).equals(basis))) layers.add(basis);
            b.text("'").leaf(Part.Kind.RULE, name).text("'");
            if (layers.isEmpty()) return;
            b.text(" (uitzondering op ");
            for (int i = 0; i < layers.size(); i++) {
                if (i > 0) b.text(" op ");
                b.text("'").leaf(Part.Kind.RULE, layers.get(i)).text("'");
            }
            b.text(")");
        }

        /** De drager van uitzonderingsgroepen bij naam. Een groep-default zonder naam aan de wortel maakte de kop
         *  {@code Uitzonderingen ongeordend op B}, of uchar voor de blokken van een subtype onder B: zijn leden zijn
         *  uitzonderingen op de basis. */
        private void carrierName(Sentence.Builder b, Variant v) {
            if (v.rule() == null && v.d().name() == null && !v.d().hasOwnContent() && v.holder() == null
                    && basisOf(v.group()) != null) {
                b.text("de regel '").leaf(Part.Kind.RULE, basisOf(v.group())).text("'");
                return;
            }
            name(b, v);
        }

        /** De naam van de basis van een groep: de groepsnaam, en bij de groep {@code B voor S} die uchar maakte voor de
         *  blokken van een subtype S onder basisregel B, de naam van B. */
        private String basisOf(PrioritizedDefaults g) {
            if (g.name() == null) return null;
            return model.generatedFrom(Designatable.form(g.name()))
                    .filter(f -> f.form() == OriginForm.EXCEPTION_FOR_SUBTYPE)
                    .map(f -> f.source().name()).orElse(g.name());
        }

        private boolean forSubtype(PrioritizedDefaults g) {
            return g.name() != null && model.generatedFrom(Designatable.form(g.name()))
                    .map(f -> f.form() == OriginForm.EXCEPTION_FOR_SUBTYPE).orElse(false);
        }

        private void groupLabel(Sentence.Builder b, PrioritizedDefaults g) {
            if (g.name() != null) b.text("'").leaf(Part.Kind.RULE, g.name()).text("'");
            else b.text("de groep op " + g.subject() + "." + g.target());
        }

        // ---- het model ----

        /** De variant op {@code pointer} in het document ({@link #variantAt}). */
        private Variant variant(String pointer) {
            return variantAt(document, pointer);
        }

        private void requireTarget(Variant v, String member) {
            String target = targetOf(v);
            if (!member.equals(target))
                throw new IllegalStateException("de pointer " + v.pointer() + " van de motor wijst naar een variant op"
                        + " '" + target + "', en de beslissing gaat over '" + member + "' van " + label);
        }

        private String targetOf(Variant v) {
            if (v.rule() == null) return v.group().target();
            if (v.rule().target() instanceof Expr.AttributeReference ref) return ref.attribute();
            throw new IllegalStateException("de regel '" + v.rule().name() + "' op " + v.pointer()
                    + " schrijft geen attribuut of kenmerk");
        }

        private Condition conditionOf(Variant v) { return v.rule() != null ? v.rule().condition() : v.d().condition(); }

        private boolean isInitialisation(Variant v) {
            return v.rule() != null ? v.rule().kind() == ResolvedRule.Kind.INITIALISATIE : v.d().initialisation();
        }

        private ObjectType subjectOf(Variant v) {
            if (v.rule() == null) return subjectOf(v.group());
            if (v.rule().target() instanceof Expr.AttributeReference ref && ref.objectType() != null)
                return ref.objectType();
            throw new IllegalStateException("de regel '" + v.rule().name() + "' op " + v.pointer()
                    + " noemt het objecttype van haar doel niet");
        }

        private ObjectType subjectOf(PrioritizedDefaults g) {
            ObjectType t = model.objectTypeByName(g.subject());
            if (t == null)
                throw new IllegalStateException("de defaults-groep '" + g.name() + "' staat op " + g.subject()
                        + ", en het model van de uitleg kent dat objecttype niet");
            return t;
        }

        /** De grondslag van de beslisser: de annotaties die het IR haar geeft, leeg zonder. Een regel en een default
         *  dragen ze als lijst ({@code statuteAnnotations}), een default die van zijn blok; de groep zelf
         *  ({@code PrioritizedDefaults.source}) is geen grondslag van een default. De opgavemarkering maakt defaults
         *  zonder blok en zonder annotaties. */
        private List<String> groundOf(Variant v) {
            if (v.rule() != null) return sourceAnnotations(v.rule());
            if (generatedOpgave(v)) return List.of();
            return v.d().statuteAnnotations();
        }

        /** Of de opgavemarkering deze default maakte: een default zonder naam in de groep {@code opgave boven
         *  berekening X}, volgens uchars tabel van herkomst; zijn tekst is van uchar. */
        private boolean generatedOpgave(Variant v) {
            return v.rule() == null && v.d().name() == null && v.group().name() != null
                    && model.generatedFrom(Designatable.form(v.group().name()))
                    .map(g -> g.form() == OriginForm.OPGAVE_BOVEN_BEREKENING).orElse(false);
        }

        private boolean ucharText(Variant v) {
            return v.rule() != null ? writtenByUchar(v.rule()) : generatedOpgave(v);
        }
    }

    // ---------------- berekende waarden ----------------

    /** De soorten knopen, zoals de draad ze spelt, die in een berekening iets lezen of samenvatten: de zin noemt elk met
     *  de waarde van zijn knoop. Een {@code Literal} en een {@code Binary} niet: die staan in de regeltekst. */
    private static final Set<String> OPERAND_KINDS = Set.of("AttributeReference", "ParameterReference",
            "DimensionCellRead", "BinderRef", "BuiltinCall", "Aggregation", "Fold");

    /** Van {@link #OPERAND_KINDS} de soorten die één waarde lezen, en een gelijste vouw: als wortel van een waarde zijn
     *  zij de regeltekst zelf, met hun waarde, zonder "met". */
    private static final Set<String> READ_KINDS = Set.of("AttributeReference", "ParameterReference",
            "DimensionCellRead", "BinderRef", "Fold");

    /** Een schrijver van een attribuut op één object: een versie van een gewone regel, haar doel, haar pointer in het
     *  document en haar lezing op het object. */
    private record Writer(ResolvedRule rule, Expr.AttributeReference target, String pointer, ConditionReading reading) {}

    /**
     * De zinnen over de attributen die de gewone regels van het document op één object schrijven: een gelijkstelling,
     * een berekening en een initialisatie, op het type van het object of een supertype ervan (IR-SPEC ch. 2.2). Een
     * regel die ch. 7.10 in een stapel van het object opnam, telt niet mee: zij staat in de zin van haar stapel (haar
     * pointer staat in {@link DefaultsDecision#rootChains}). Een regel op een supertype die de motor op het object niet
     * las, terwijl het type van het object een stapel op haar doel heeft, telt ook niet mee: die stapel beslist het doel
     * op dat type (ch. 7.10), en de regel loopt er niet. Per attribuut één zin over al zijn schrijvers, in de volgorde
     * van het document, in een van vier vormen:
     *
     * <ul>
     *   <li>een regel gold en gaf een waarde: "heeft als X W volgens de regel 'R'", haar berekening en waarom zij gold;</li>
     *   <li>een regel gold en gaf geen waarde (RUNTIME-SEMANTICS ch. 1.2g): wat het doel houdt, "want de regel 'R' gaf
     *       geen waarde", haar berekening en waarom zij gold;</li>
     *   <li>geen regel gold en het doel is leeg: "heeft geen X, want geen regel gold";</li>
     *   <li>geen regel gold en het doel houdt een invoerwaarde: "houdt als X de invoerwaarde W, want geen regel
     *       gold".</li>
     * </ul>
     *
     * <p>Daarna elke andere schrijver als groep {@link Part.Kind#VARIANT}, met de reden dat zij niet schreef: haar
     * voorwaarde gold niet (het lid waar de motor stopte), zij valt buiten haar geldigheid, zij gold en gaf geen waarde,
     * of zij is een initialisatie die niet aan de beurt was ("verdrongen"). Dan de grondslag van de regel die gold.
     *
     * <p>De berekening ({@link Part.Kind#COMPUTATION}) is de regeltekst van de waarde, met elke knoop die iets leest of
     * samenvat ({@link #OPERAND_KINDS}) met de waarde die de motor er las, elke brontekst één keer; leest de waarde één
     * declaratie of vouwt zij over een verzameling, dan is die knoop de regeltekst zelf, met zijn waarde, en de
     * regeltekst van een {@code Let} is die van haar lichaam, want haar brontekst is de definitie van haar binder; een
     * binder staat er met de tekst van zijn definitie. Een vouw over een verzameling noemt haar leden met wat elk
     * bijdroeg ({@link Part.Kind#MEMBERS}), en haar filter, als dat één kenmerkcheck of één lezing is, als regeltekst met
     * de sleutels van die declaratie. Na de waarden de waarde vóór elke afronding en vóór elke begrenzing. Een knoop die
     * de motor niet bereikte (de tak van een keuze) staat er zonder waarde. Elke waarde komt uit de lezing van de regel:
     * de knopen van haar waarde volgen in de nummering op die van haar voorwaarde (ch. 5.12), en een regel zonder
     * voorwaarde heeft alleen die van haar waarde. Alleen de invoerwaarde, waar geen regel schreef, staat op de bean.
     *
     * <p>Een regel op een tijdlijn-doel schreef "een tijdlijn" ({@code timeline: true} op de waarde), zonder berekening:
     * de zin noemt de perioden niet.
     */
    private final class ComputationExplanation {
        private final String label;
        private final Object bean;
        private final ObjectType type;
        /** De pointers van de varianten van de stapels van dit object. */
        private final Set<String> inStacks = new HashSet<>();
        /** De doelen van de stapels van dit object, in kleine letters. */
        private final Set<String> stackTargets = new HashSet<>();

        ComputationExplanation(String label, Object bean, ObjectType type, List<DefaultsDecision> decisions) {
            this.label = label;
            this.bean = bean;
            this.type = type;
            for (DefaultsDecision d : decisions) {
                if (d.object() != bean) continue;
                for (List<String> chain : d.rootChains()) inStacks.addAll(chain);
                stackTargets.add(d.member().toLowerCase(Locale.ROOT));
            }
        }

        void sentences(List<Sentence> sink) {
            Map<String, List<Writer>> perTarget = new LinkedHashMap<>();
            for (ResolvedRule r : document.rules()) {
                if (r.kind() == ResolvedRule.Kind.KENMERKTOEKENNING) continue;
                if (!(r.target() instanceof Expr.AttributeReference ref) || ref.objectType() == null) continue;
                if (!type.isA(ref.objectType())) continue;
                String pointer = document.positions().versionOf(r);
                if (pointer == null)
                    throw new IllegalStateException("de regel '" + r.name() + "' staat niet als versie in het document"
                            + " van de uitleg: haar lezing heeft geen vorm");
                if (inStacks.contains(pointer)) continue;
                String target = ref.attribute().toLowerCase(Locale.ROOT);
                if (stackTargets.contains(target) && !readings.getOrDefault(pointer, Map.of()).containsKey(bean))
                    continue;
                perTarget.computeIfAbsent(target, k -> new ArrayList<>())
                        .add(new Writer(r, ref, pointer, reading(pointer, bean, label)));
            }
            for (List<Writer> writers : perTarget.values()) sink.add(sentence(writers));
        }

        private Sentence sentence(List<Writer> writers) {
            String member = writers.get(0).target().attribute();
            boolean timeline = type.granularityOf(member) != null;
            List<Writer> wrote = new ArrayList<>(), empty = new ArrayList<>();
            Object written = null;
            for (Writer w : writers) {
                ConditionReading.Status status = w.reading().status();
                if (status == ConditionReading.Status.FAULTS) throw faulted(w.pointer(), label);
                if (status != ConditionReading.Status.HOLDS) continue;
                Object v = valueRead(w).of(w.rule().value()).value();
                if (v == null || v instanceof List<?> periods && periods.isEmpty()) {
                    empty.add(w);
                    continue;
                }
                if (!wrote.isEmpty() && !same(written, v))
                    throw new IllegalStateException("de regels '" + formName(wrote.get(0)) + "' en '" + formName(w)
                            + "' gelden op " + label + " met verschillende waarden voor '" + member + "' (" + written
                            + " en " + v + "), en de uitleg volgt een sessie zonder fout");
                if (wrote.isEmpty()) written = v;
                wrote.add(w);
            }
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            List<Writer> deciders = wrote.isEmpty() ? empty : wrote;
            if (!wrote.isEmpty()) {
                b.text("heeft als ");
                attributeName(b, type, member);
                b.text(" ");
                Writer first = wrote.get(0);
                if (timeline) b.leaf(Part.Kind.VALUE, "een tijdlijn", Part.meta("timeline", true));
                else value(b, written(first, member), false);
                for (int i = 0; i < wrote.size(); i++) {
                    b.text(i == 0 ? " volgens " : ", en volgens ");
                    decider(b, wrote.get(i), member, timeline, false);
                }
            } else {
                standing(b, member, timeline);
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" ");
                if (empty.isEmpty()) b.text("geen regel gold");
                for (int i = 0; i < empty.size(); i++) {
                    if (i > 0) b.text(", en ");
                    decider(b, empty.get(i), member, timeline, true);
                }
            }
            for (Writer w : writers) if (!deciders.contains(w)) variant(b, w);
            if (!deciders.isEmpty())
                ground(b, sourceAnnotations(deciders.get(0).rule()), ruleDecider(deciders.get(0).rule()));
            return b.build();
        }

        /** De waarde die regel {@code w} op {@code member} schreef, zoals de motor haar las: een getal met de decimalen
         *  van het doel, zoals de zin van een defaults-groep het noemt, en met de eenheid van de knoop, of zonder die met
         *  die van het doel. */
        private String written(Writer w, String member) {
            ConditionReading.NodeReading n = valueRead(w).of(w.rule().value());
            Datatype target = type.attribute(member);
            if (!(n.value() instanceof BigDecimal d)) return render(n.value(), target);
            String unit = n.unit() != null ? n.unit().abbreviation() : unitWord(target);
            String number = nlNumber(d, target);
            return unit == null ? number : number + " " + unit;
        }

        /** Wat het doel houdt waar geen regel een waarde schreef: leeg of de invoerwaarde; van een tijdlijn-doel "een
         *  tijdlijn". */
        private void standing(Sentence.Builder b, String member, boolean timeline) {
            Object v = readRaw(bean, Namen.getter(member));
            if (!timeline || v == null) {
                attributeStanding(b, bean, type, member);
                return;
            }
            b.text("houdt als ");
            attributeName(b, type, member);
            b.text(" de invoerwaarde ").leaf(Part.Kind.VALUE, "een tijdlijn", Part.meta("timeline", true));
        }

        /** Een regel die gold: haar naam, haar berekening en waarom zij gold; met {@code gaveNothing} dat zij geen waarde
         *  gaf. Een initialisatie zonder voorwaarde gold omdat het doel in de invoer leeg was, zoals de zin van een
         *  initialisatie in een stapel het zegt. */
        private void decider(Sentence.Builder b, Writer w, String member, boolean timeline, boolean gaveNothing) {
            ResolvedRule r = w.rule();
            b.text("de regel '").leaf(Part.Kind.RULE, formName(w)).text("'");
            if (gaveNothing) b.text(" gaf geen waarde");
            Condition c = r.condition();
            boolean initialisation = r.kind() == ResolvedRule.Kind.INITIALISATIE;
            if (c == null && !initialisation) b.text(" (onvoorwaardelijk)");
            if (!timeline) computation(b, r.value(), writtenByUchar(r), valueRead(w));
            if (c != null) {
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                verbaliseCondition(b, c, read(w.pointer(), bean, label, c), w.target().objectType(),
                        writtenByUchar(r));
            } else if (initialisation) {
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" de ");
                attributeName(b, type, member);
                b.text(" in de invoer leeg was");
            }
        }

        /** Een schrijver die niet schreef, als groep met de "; " ervoor. */
        private void variant(Sentence.Builder b, Writer w) {
            ConditionReading.Status status = w.reading().status();
            boolean displaced = status == ConditionReading.Status.DISPLACED;
            if (displaced && w.rule().kind() != ResolvedRule.Kind.INITIALISATIE)
                throw new IllegalStateException("de motor noemt '" + w.rule().name() + "' op " + label + " niet aan de"
                        + " beurt, en alleen een initialisatie buiten een stapel wacht op haar beurt");
            b.open(Part.Kind.VARIANT, displaced ? Part.meta("decided", false, "displaced", true)
                    : Part.meta("decided", false));
            if (displaced) {
                b.text("; verdrongen: de regel '").leaf(Part.Kind.RULE, formName(w)).text("'");
            } else {
                b.text("; de regel '").leaf(Part.Kind.RULE, formName(w)).text("' ");
                Condition c = w.rule().condition();
                switch (status) {
                    case HOLDS -> b.text("gaf geen waarde");
                    case FAILS -> {
                        if (c == null)
                            throw new IllegalStateException("de motor noemt de voorwaarde van '" + w.rule().name()
                                    + "' op " + label + " onwaar, en de regel heeft er geen");
                        didNotHoldBecause(b).text(" ");
                        firstFailing(b, c, read(w.pointer(), bean, label, c), w.target().objectType(),
                                writtenByUchar(w.rule()));
                    }
                    case OUTSIDE_VALIDITY ->
                            didNotHoldBecause(b).text(" zij valt op de rekendatum buiten haar geldigheid");
                    default -> throw new IllegalStateException("de lezing " + status + " van '" + w.rule().name()
                            + "' op " + label + " heeft geen reden in de zin");
                }
            }
            b.close(Part.Kind.VARIANT);
        }

        /** De lezing van regel {@code w} op de knopen van haar waarde, die op die van haar voorwaarde volgen. */
        private Read valueRead(Writer w) {
            Condition c = w.rule().condition();
            int offset = c == null ? 0 : document.positions().subtree(c).size();
            return read(w.pointer(), bean, label, w.rule().value(), offset);
        }

        /** Hoe een vorm waarde {@code root} berekende, met de dubbele punt ervoor; {@code read} is haar lezing op de
         *  knopen van die waarde, {@code ucharText} of uchar de tekst van de vorm schreef. */
        private void computation(Sentence.Builder b, Expr root, boolean ucharText, Read read) {
            List<Object> nodes = document.positions().subtree(root);
            b.open(Part.Kind.COMPUTATION, Map.of()).text(": ");
            Map<Object, String> definitions = definitions(nodes);
            String rootKind = kindOf(root);
            // een lezing, parameter of vouw over een verzameling is de regeltekst zelf, met haar waarde; een ingebouwde
            // functie of een aggregatie over haar argumenten noemt wat zij las
            boolean single = READ_KINDS.contains(rootKind) || overASet(root, read);
            if (single) {
                operand(b, root, read, ucharText, definitions);
            } else {
                // de brontekst van een Let is de definitie van haar binder; de berekening is haar lichaam
                Expr body = root;
                while (body instanceof Expr.Let l) body = l.body();
                describe(b, body, ucharText);
            }
            Set<Object> covered = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            if (root instanceof Expr.DimensionCellRead) covered.addAll(nodes);
            Set<String> seen = new HashSet<>();
            List<Expr> operands = new ArrayList<>();
            for (Object n : nodes) {
                if (n == root || covered.contains(n)) continue;
                if (!(n instanceof Expr e) || !OPERAND_KINDS.contains(kindOf(n)) || !read.has(n)) continue;
                // een dimensiecel is de lezing; het attribuut eronder is geen eigen operand
                if (e instanceof Expr.DimensionCellRead) covered.addAll(document.positions().subtree(e));
                if (seen.add(operandKey(e))) operands.add(e);
            }
            for (int i = 0; i < operands.size(); i++) {
                b.text(i == 0 ? ", met " : i == operands.size() - 1 ? " en " : ", ");
                operand(b, operands.get(i), read, ucharText, definitions);
            }
            for (Object n : nodes) {
                if (!read.has(n) || !read.reached(n)) continue;
                if (n instanceof Expr.Rounding r) {
                    b.text("; vóór de afronding ");
                    value(b, unrounded(r.base(), read), read.of(r.base()).value() == null);
                } else if (n instanceof Expr.Clamp c) {
                    b.text("; vóór de begrenzing ");
                    value(b, unrounded(c.base(), read), read.of(c.base()).value() == null);
                }
            }
            b.close(Part.Kind.COMPUTATION);
        }

        /** Per binder-lezing onder {@code nodes} de tekst van de definitie van haar binder ({@code Daarbij geldt}): de
         *  brontekst van de dichtstbijzijnde Let erboven met die binder. */
        private Map<Object, String> definitions(List<Object> nodes) {
            Map<Object, String> sink = new IdentityHashMap<>();
            for (Object n : nodes) {
                if (!(n instanceof Expr.BinderRef ref)) continue;
                String at = document.positions().of(n).pointer(), found = null;
                int depth = -1;
                for (Object m : nodes) {
                    if (!(m instanceof Expr.Let l) || !l.binder().equals(ref.name())) continue;
                    String p = document.positions().of(m).pointer();
                    if (at.startsWith(p + "/") && p.length() > depth && l.source() != null
                            && l.source().snippet() != null && !l.source().snippet().isBlank()) {
                        depth = p.length();
                        found = l.source().snippet().trim();
                    }
                }
                if (found != null) sink.put(n, found);
            }
            return sink;
        }

        /** De waarde van knoop {@code e} vóór een afronding of een begrenzing, met al haar decimalen: die van het type
         *  van de knoop zouden haar al afronden. */
        private String unrounded(Expr e, Read read) {
            ConditionReading.NodeReading n = read.of(e);
            if (!(n.value() instanceof BigDecimal d)) return renderOperand(e, read);
            String unit = n.unit() != null ? n.unit().abbreviation()
                    : (e.type() instanceof Datatype.Percentage || typeOf(e) instanceof Datatype.Percentage) ? "%" : null;
            String number = nlNumber(d.stripTrailingZeros(), null);
            return unit == null ? number : number + " " + unit;
        }

        /** Een knoop die iets leest of samenvat, met de waarde die de motor er las; een vouw over een verzameling met
         *  haar leden, een binder met de tekst van haar definitie ({@code definitions}). Zonder waarde waar de motor
         *  de knoop niet bereikte. */
        private void operand(Sentence.Builder b, Expr e, Read read, boolean ucharText, Map<Object, String> definitions) {
            Expr.FunctionCall fold = overASet(e, read) ? (Expr.FunctionCall) e : null;
            if (fold != null) foldText(b, fold);
            else if (definitions.containsKey(e)) b.leaf(Part.Kind.QUOTE, definitions.get(e));
            else describe(b, e, ucharText);
            if (!read.reached(e)) return;
            ConditionReading.NodeReading n = read.of(e);
            b.text(" (");
            value(b, renderOperand(e, read), n.value() == null);
            b.text(")");
            if (fold != null) {
                b.text(": ");
                contributors(b, fold, n);
            }
        }

        /** Of {@code e} een vouw over een verzameling is (RUNTIME-SEMANTICS ch. 3.4): een aggregatie met één argument,
         *  de verzameling, zonder dimensieselectie; haar knoop draagt haar bijdragers. Een aggregatie over haar
         *  argumenten ("de laatste van A en B") is er geen. */
        private boolean overASet(Expr e, Read read) {
            return e instanceof Expr.FunctionCall f && "Aggregation".equals(kindOf(e)) && f.dimensionSelection() == null
                    && (f.arguments().size() == 1 || read.has(e) && !read.of(e).contributors().isEmpty());
        }

        /** Wat een operand de zin noemt, één keer: zijn brontekst en de declaratie die het leest. */
        private String operandKey(Expr e) {
            String text = e.source() == null || e.source().snippet() == null ? "" : e.source().snippet().trim();
            Designatable d = declarationOf(e);
            return text + "\u0000" + (d == null ? "" : d.kind() + "\u0000" + d.owner() + "\u0000" + d.name());
        }

        /** De regeltekst van vouw {@code f}, met de declaratie van het attribuut dat zij leest; staat haar filter erin en
         *  is dat één kenmerkcheck of één lezing, dan is het filter een eigen stuk met de declaratie die het leest, en een
         *  samenstelling een stuk per lid. */
        private void foldText(Sentence.Builder b, Expr.FunctionCall f) {
            String text = f.source().snippet().trim();
            Designatable read = f.arguments().size() == 1 ? declarationOf(f.arguments().get(0)) : null;
            Condition filter = f.filter();
            if (filter == null) {
                b.leaf(Part.Kind.QUOTE, text, declaration(read));
                return;
            }
            String filterText = summary(filter);
            int at = text.lastIndexOf(filterText);
            if (at < 0)
                throw new IllegalStateException("de tekst van het filter \"" + filterText + "\" staat niet in de tekst"
                        + " van de vouw \"" + text + "\"");
            quote(b, text.substring(0, at), read);
            if (filter instanceof Condition.Compound s) {
                int from = 0;
                for (Condition d : s.components()) {
                    String part = summary(d);
                    int p = filterText.indexOf(part, from);
                    if (p < 0)
                        throw new IllegalStateException("de tekst van het lid \"" + part + "\" staat niet in de tekst van"
                                + " het filter \"" + filterText + "\"");
                    quote(b, filterText.substring(from, p), null);
                    quote(b, part, filterDeclaration(d, f.elementObjectType()));
                    from = p + part.length();
                }
                quote(b, filterText.substring(from), null);
            } else {
                quote(b, filterText, filterDeclaration(filter, f.elementObjectType()));
            }
            quote(b, text.substring(at + filterText.length()), null);
        }

        private void quote(Sentence.Builder b, String text, Designatable d) {
            if (!text.isEmpty()) b.leaf(Part.Kind.QUOTE, text, declaration(d));
        }

        /** De declaratie die filterlid {@code c} van een vouw over {@code element} leest: het kenmerk van een
         *  kenmerkcheck, of het ene attribuut of de ene parameter van een vergelijking of een gezegde; anders null. */
        private Designatable filterDeclaration(Condition c, ObjectType element) {
            if (c instanceof Condition.CharacteristicCheck k) {
                List<RoleBindingRef> walk = walkOf(k);
                return Naming.characteristicOf(walk.isEmpty() ? element : holderOf(walk), k.characteristic());
            }
            List<Expr> operands = c instanceof Condition.Comparison v ? List.of(v.left(), v.right())
                    : c instanceof Condition.Predicate p ? List.of(p.operand()) : List.of();
            Set<String> keys = new LinkedHashSet<>();
            Designatable found = null;
            for (Expr e : operands) {
                Designatable d = declarationOf(e);
                if (d != null && keys.add(d.kind() + "\u0000" + d.owner() + "\u0000" + d.name())) found = d;
            }
            return keys.size() == 1 ? found : null;
        }

        /** De leden van vouw {@code f} met wat elk bijdroeg, bij hun naam in de code-unit-volgorde daarvan; een telling
         *  zonder waarde per lid. Boven vijf de eerste vijf en "en nog k". */
        private void contributors(Sentence.Builder b, Expr.FunctionCall f, ConditionReading.NodeReading n) {
            boolean count = f.kind() == Expr.FunctionKind.AANTAL || f.kind() == Expr.FunctionKind.AANTAL_GEVULDE;
            List<Map.Entry<String, Object>> members = new ArrayList<>();
            for (ConditionReading.Contributor c : n.contributors()) {
                if (c.fault() != null)
                    throw new IllegalStateException("een lid van de vouw \"" + f.source().snippet().trim() + "\" op "
                            + label + " gaf een evaluatiefout, en de uitleg volgt een sessie zonder fout: "
                            + c.fault().getMessage());
                String name = labels.get(c.member());
                if (name == null)
                    throw new IllegalStateException("een lid van de vouw \"" + f.source().snippet().trim() + "\" op "
                            + label + " heeft geen naam: de uitleg noemt de objecten van de invoer en de gemaakte"
                            + " objecten van het fixpunt, en dit " + c.member().getClass().getSimpleName()
                            + " is geen van beide");
                members.add(new java.util.AbstractMap.SimpleImmutableEntry<>(name, c.value()));
            }
            members.sort(Map.Entry.comparingByKey());
            int m = members.size();
            b.open(Part.Kind.MEMBERS, Part.meta("members", m));
            if (m == 0) {
                b.text("er is geen enkel lid");
            } else {
                b.text("over ");
                int shown = Math.min(m, 5);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) b.text(i == shown - 1 && m <= 5 ? " en " : ", ");
                    Map.Entry<String, Object> member = members.get(i);
                    b.leaf(Part.Kind.SUBJECT, member.getKey());
                    if (count) continue;
                    b.text(" (");
                    value(b, renderRead(f, member.getValue(), n.unit()), member.getValue() == null);
                    b.text(")");
                }
                if (m > 5) b.text(" en nog " + (m - 5));
                b.text(m == 1 ? ", het enige lid" : " (" + m + " leden)");
            }
            b.close(Part.Kind.MEMBERS);
        }

        /** De soort van knoop {@code node} zoals de draad haar spelt. */
        private String kindOf(Object node) {
            NodePositions.Position p = document.positions().of(node);
            if (p == null)
                throw new IllegalStateException("de knoop " + node + " staat niet in het document van de uitleg");
            return p.kind();
        }

        private String formName(Writer w) { return RegelSpraakModel.formName(w.rule().name()); }

        /** Of twee waarden die twee regels schreven dezelfde zijn; een getal op zijn waarde. */
        private boolean same(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
            return Objects.equals(a, b);
        }
    }

    /** Of {@code name} de versie na die van {@code previous} noemt, van één vorm. */
    private static boolean nextVersion(String previous, String name) {
        Matcher a = VERSION.matcher(previous), b = VERSION.matcher(name);
        return a.matches() && b.matches() && a.group(1).equals(b.group(1))
                && Integer.parseInt(b.group(2)) == Integer.parseInt(a.group(2)) + 1;
    }

    /** Één kenmerktoekenningsregel voor één instantie: is het kenmerk toegekend → "waarom (voorwaarden)"; is het
     *  met een voorwaarde NIET toegekend → "niet, want <het lid waar de motor stopte>"; onvoorwaardelijk-afwezig →
     *  geen zin (er valt niets te verantwoorden). Staat het kenmerk aan terwijl de voorwaarde van deze regel niet gold,
     *  dan kwam het uit de invoer of van een andere schrijver, en zegt de zin dat deze regel niet gold. */
    private Sentence verbaliseAssignment(String label, Object bean, Expr.AttributeReference ref, ResolvedRule r) {
        String characteristic = ref.attribute();
        boolean present = readBoolean(bean, characteristic);
        Condition v = r.condition();
        ObjectType subject = ref.objectType();
        if (!present && v == null) return null;   // onvoorwaardelijk maar afwezig: niets te verantwoorden
        // de conclusie van de regel ("Een … heeft de uitkeringsduur verstreken") draagt het lidwoord
        Predicate predicate = predicate(subject, characteristic, present, ref.source() == null ? null : ref.source().snippet());
        if (predicate == null)
            throw new IllegalStateException("de kenmerktoekenning '" + r.name() + "' kent '" + characteristic + "' toe, en "
                    + subject.name + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
        boolean ucharText = writtenByUchar(r);
        Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
        predicate.mainClause(b);
        if (v == null) {
            b.text(" (onvoorwaardelijk toegekend)");
        } else {
            String pointer = document.positions().versionOf(r);
            if (pointer == null)
                throw new IllegalStateException("de regel '" + r.name() + "' staat niet als versie in het document van"
                        + " de uitleg: haar lezing heeft geen vorm");
            ConditionReading.Status status = reading(pointer, bean, label).status();
            switch (status) {
                case HOLDS -> {
                    if (!present)
                        throw new IllegalStateException("de voorwaarde van '" + r.name() + "' geldt volgens de motor op "
                                + label + ", en het kenmerk '" + characteristic + "' staat niet");
                    Read read = read(pointer, bean, label, v);
                    b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                    verbaliseCondition(b, v, read, subject, ucharText);
                }
                case FAILS -> {
                    Read read = read(pointer, bean, label, v);
                    but(b, present, r);
                    firstFailing(b, v, read, subject, ucharText);
                }
                case OUTSIDE_VALIDITY -> {
                    but(b, present, r);
                    b.text("de regel valt op de rekendatum buiten haar geldigheid");
                }
                case DISPLACED -> throw new IllegalStateException("de motor noemt '" + r.name() + "' op " + label
                        + " niet aan de beurt, en een kenmerktoekenning wacht niet op haar beurt");
                case FAULTS -> throw faulted(pointer, label);
            }
        }
        ground(b, sourceAnnotations(r), ruleDecider(r));
        return b.build();
    }

    /** Een regel als beslisser van een grondslag: de naam van haar vorm, {@code rules.rules[].name} op de draad, die
     *  de annotaties draagt voor al haar versies (IR-SPEC ch. 3.1); het model noemt een versie "… #i". */
    private static Map<String, Object> ruleDecider(ResolvedRule r) {
        return Part.meta("rule", RegelSpraakModel.formName(r.name()));
    }

    /** Het begin van de reden waarom de voorwaarde van {@code r} niet gold: bij een kenmerk dat toch staat, dat deze
     *  regel niet gold. */
    private static void but(Sentence.Builder b, boolean present, ResolvedRule r) {
        b.text(", ");
        if (present) {
            b.leaf(Part.Kind.CONNECTIVE, "maar").text(" de regel '")
                    .leaf(Part.Kind.RULE, RegelSpraakModel.formName(r.name())).text("' ");
            didNotHoldBecause(b);
        } else {
            b.leaf(Part.Kind.CONNECTIVE, "want");
        }
        b.text(" ");
    }

    /** "gold niet, want". */
    private static Sentence.Builder didNotHoldBecause(Sentence.Builder b) {
        return b.leaf(Part.Kind.CONNECTIVE, "gold niet").text(", ").leaf(Part.Kind.CONNECTIVE, "want");
    }

    /** De grondslag als laatste stuk van de zin, met de punt en "Grondslag: " ervoor, zodat de zin zonder dat stuk
     *  dezelfde is als een zin zonder annotatie, en met {@code decider}, wie de annotaties draagt, als meta; niets
     *  zonder annotatie. */
    private static void ground(Sentence.Builder b, List<String> annotations, Map<String, Object> decider) {
        if (annotations.isEmpty()) return;
        b.open(Part.Kind.GROUND, decider).text(". Grondslag: ");
        Ground.write(b, annotations);
        b.close(Part.Kind.GROUND);
    }

    /** Een gedeclareerde naam zoals {@link Naming} haar noemt, met de sleutels van zijn declaratie {@code d}, de
     *  sleutels van {@code vocabularies.elements[]}; zonder sleutels waar de declaratie niet bekend is. */
    private static void declaredName(Sentence.Builder b, String text, Designatable d) {
        b.leaf(Part.Kind.NAME, text, declaration(d));
    }

    private static Map<String, Object> declaration(Designatable d) {
        return d == null ? Map.of() : Part.meta("declaration", d.kind().name(), "owner", d.owner(), "canonical", d.name());
    }

    /** Attribuut {@code member} van {@code holder} in het vocabulaire. */
    private void attributeName(Sentence.Builder b, ObjectType holder, String member) {
        declaredName(b, naming.attribute(holder, member), Naming.attributeOf(holder, member));
    }

    /** Een waarde in de opmaak van de zin; {@code empty} bij een lege. */
    private static void value(Sentence.Builder b, String text, boolean empty) {
        b.leaf(Part.Kind.VALUE, text, empty ? Part.meta("empty", true) : Map.of());
    }

    /** Wat attribuut {@code member} van {@code bean}, van objecttype {@code type}, houdt waar niemand het schreef:
     *  leeg, of de invoerwaarde, van de bean gelezen. */
    private void attributeStanding(Sentence.Builder b, Object bean, ObjectType type, String member) {
        Object v = readRaw(bean, Namen.getter(member));
        if (v == null) {
            b.text("heeft geen ");
            attributeName(b, type, member);
            return;
        }
        b.text("houdt als ");
        attributeName(b, type, member);
        b.text(" de invoerwaarde ");
        value(b, render(v, type.attribute(member)), false);
    }

    /** De lezing van vorm {@code form} op {@code bean}; faalt luid waar de motor er geen gaf. */
    private ConditionReading reading(String form, Object bean, String label) {
        ConditionReading r = readings.getOrDefault(form, Map.of()).get(bean);
        if (r == null)
            throw new IllegalStateException("de motor gaf geen lezing van " + form + " op " + label
                    + ": de uitleg leest de lezingen van een build met lezingen, van elke vorm met een voorwaarde of"
                    + " een berekende waarde op elk object waarop zij loopt");
        return r;
    }

    /** De lezing van vorm {@code form} op {@code bean} met de nummers van de knopen van voorwaarde {@code c}. */
    private Read read(String form, Object bean, String label, Condition c) {
        return read(form, bean, label, c, 0);
    }

    /** De lezing van vorm {@code form} op {@code bean} met de nummers van de knopen onder {@code root}, een voorwaarde
     *  of een waarde van het document, vanaf {@code offset}: de knopen van de waarde van een vorm volgen op die van haar
     *  voorwaarde (RUNTIME-SEMANTICS ch. 5.12). */
    private Read read(String form, Object bean, String label, Object root, int offset) {
        ConditionReading r = reading(form, bean, label);
        Map<Object, Integer> numbers = new IdentityHashMap<>();
        List<Object> nodes = document.positions().subtree(root);
        for (int i = 0; i < nodes.size(); i++) numbers.put(nodes.get(i), offset + i);
        Map<Integer, ConditionReading.NodeReading> perNumber = new HashMap<>();
        for (ConditionReading.NodeReading n : r.nodes()) perNumber.put(n.node(), n);
        return new Read(form, label, numbers, perNumber);
    }

    private static IllegalStateException faulted(String form, String label) {
        return new IllegalStateException("de motor gaf voor " + form + " op " + label + " een evaluatiefout, en de"
                + " uitleg volgt een sessie zonder fout");
    }

    /** De lezing van één vorm op één object, op de knopen van haar voorwaarde gelegd. Een knoop zonder eigen lezing
     *  staat in het filter, de matrix of de bron van een vouw: de zin noemt hem alleen in de tekst van de vouw. */
    private record Read(String form, String label, Map<Object, Integer> numbers,
                        Map<Integer, ConditionReading.NodeReading> perNumber) {

        /** De lezing van knoop {@code node}; faalt luid waar de knoop niet tot de voorwaarde hoort of de motor er
         *  geen lezing van gaf. */
        ConditionReading.NodeReading of(Object node) {
            Integer number = numbers.get(node);
            if (number == null)
                throw new IllegalStateException("de knoop " + node + " hoort niet bij de voorwaarde van " + form);
            ConditionReading.NodeReading n = perNumber.get(number);
            if (n == null)
                throw new IllegalStateException("de motor gaf voor " + form + " op " + label + " geen lezing van knoop "
                        + number + ", en de zin noemt hem");
            if (n.fault() != null)
                throw new IllegalStateException("knoop " + number + " van " + form + " op " + label + " gaf een"
                        + " evaluatiefout, en de uitleg volgt een sessie zonder fout: " + n.fault().getMessage());
            return n;
        }

        boolean reached(Object node) { return of(node).reached(); }

        /** Of de motor een lezing van knoop {@code node} gaf: niet van een knoop in het filter, de matrix of de bron van
         *  een vouw, die per lid van de vouw gelezen wordt. */
        boolean has(Object node) {
            Integer number = numbers.get(node);
            return number != null && perNumber.containsKey(number);
        }

        boolean holds(Condition c) { return Boolean.TRUE.equals(of(c).value()); }
    }

    /** Of uchar de tekst van regel {@code r} schreef: een regel van de opgave-familie, volgens uchars tabel van
     *  herkomst. Een beslistabelrij maakte uchar ook, maar uit de kolomkoppen en cellen van de auteur; die tekst is van
     *  de auteur. */
    private boolean writtenByUchar(ResolvedRule r) {
        return model.generatedFrom(Designatable.form(RegelSpraakModel.formName(r.name())))
                .map(g -> g.form() == OriginForm.OPGAVE_BOVEN_BEREKENING).orElse(false);
    }

    // ---------------- voorwaarde-verbalisatie ----------------

    /** Een voorwaarde in proza, met de waarden die de motor las: WAAROM ze geldt. Van een samenstelling alleen de
     *  leden die de motor bereikte. {@code subject} is het objecttype van de regel, vanwaar een kenmerk- of rolnaam in
     *  de voorwaarde gelezen wordt; {@code ucharText} of uchar de regel schreef. Elke voorwaarde is een groep
     *  ({@link #conditionMeta}). */
    private void verbaliseCondition(Sentence.Builder b, Condition c, Read read, ObjectType subject, boolean ucharText) {
        if (c instanceof Condition.Compound s) {
            String conjunction = switch (s.quantifier()) {
                case ALLE -> "en";
                case GEEN -> "en niet";
                default -> "of";
            };
            b.open(Part.Kind.CONDITION, conditionMeta(c, read));
            boolean first = true;
            for (Condition d : s.components())
                if (read.reached(d)) {
                    if (!first) b.text(" ").leaf(Part.Kind.CONNECTIVE, conjunction).text(" ");
                    first = false;
                    verbaliseCondition(b, d, read, subject, ucharText);
                }
            b.close(Part.Kind.CONDITION);
            return;
        }
        verbaliseAtom(b, c, read, subject, ucharText);
    }

    /** Wat de groep van voorwaarde {@code c} draagt: {@code holds}, het oordeel van de motor op haar knoop (geen
     *  sleutel waar de motor de knoop bereikte zonder oordeel, na een lege binder), en bij een samenstelling of een
     *  kwantorvouw {@code quantifier} in de spelling van de draad, met {@code threshold} bij een telling. */
    private static Map<String, Object> conditionMeta(Condition c, Read read) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (read.of(c).value() instanceof Boolean holds) meta.put("holds", holds);
        Condition.Quantifier q = null;
        int threshold = 0;
        if (c instanceof Condition.Compound s) { q = s.quantifier(); threshold = s.threshold(); }
        if (c instanceof Condition.QuantifierFold f) { q = f.quantifier(); threshold = f.threshold(); }
        if (q != null) {
            meta.put("quantifier", Spelling.QUANTIFIER.spelling(q));
            if (q == Condition.Quantifier.TEN_MINSTE || q == Condition.Quantifier.TEN_HOOGSTE
                    || q == Condition.Quantifier.PRECIES) meta.put("threshold", threshold);
        }
        return meta;
    }

    /** Een enkele (niet-samengestelde) conditie als proza met de waarden van haar operanden, als schrijver van haar
     *  stukken; null voor een vorm die de uitleg niet in woorden zet, die {@link #unstructured} met haar brontekst
     *  noemt. */
    private Consumer<Sentence.Builder> structuredAtom(Condition c, Read read, ObjectType subject, boolean ucharText) {
        if (c instanceof Condition.Comparison vg) {
            return b -> {
                operandProse(b, vg.left(), read, ucharText);
                b.text(" " + operatorWords(vg.op()) + " ");
                operandProse(b, vg.right(), read, ucharText);
            };
        }
        if (c instanceof Condition.CharacteristicCheck k) {
            List<RoleBindingRef> walk = walkOf(k);
            ObjectType holder = walk.isEmpty() ? subject : holderOf(walk);
            Predicate predicate = predicate(holder, k.characteristic(), !k.negation(), summary(c));
            // zonder soort geen werkwoord: dan de voorwaarde zoals de regel haar schrijft
            if (predicate == null) return null;
            return b -> {
                who(b, walk);
                b.text(" ");
                predicate.clause(b);
            };
        }
        if (c instanceof Condition.RoleMembership rl)
            return b -> {
                b.text("het onderwerp " + (rl.negation() ? "geen " : "een "));
                declaredName(b, naming.roleFulfilled(subject, rl.role()),
                        Naming.roleFulfilledBy(model, subject, rl.role()));
                b.text(" is");
            };
        if (c instanceof Condition.Predicate p) {
            String core = switch (p.kind()) {
                case LEEG -> "leeg is";
                case GEVULD -> "gevuld is";
                default -> "voldoet aan " + p.kind().name().toLowerCase(Locale.ROOT);
            };
            return b -> {
                operandProse(b, p.operand(), read, ucharText);
                b.text(" " + core);
            };
        }
        return null;
    }

    private void verbaliseAtom(Sentence.Builder b, Condition c, Read read, ObjectType subject, boolean ucharText) {
        Consumer<Sentence.Builder> structured = structuredAtom(c, read, subject, ucharText);
        if (structured != null) condition(b, c, read, structured);
        else unstructured(b, c, read, "voldaan");
    }

    /** Voorwaarde {@code c} als groep, met wat {@code content} erin schrijft. */
    private static void condition(Sentence.Builder b, Condition c, Read read, Consumer<Sentence.Builder> content) {
        b.open(Part.Kind.CONDITION, conditionMeta(c, read));
        content.accept(b);
        b.close(Part.Kind.CONDITION);
    }

    /** Een vorm die de uitleg niet in woorden zet, met haar brontekst en {@code verdict} ("voldaan" of "niet
     *  voldaan"); een kwantorvouw met de leden die haar uitkomst dragen. */
    private void unstructured(Sentence.Builder b, Condition c, Read read, String verdict) {
        condition(b, c, read, g -> {
            g.text("aan de voorwaarde \"").leaf(Part.Kind.QUOTE, summary(c)).text("\" is " + verdict);
            if (c instanceof Condition.QuantifierFold q) {
                g.text(": ");
                members(g, q, read);
            }
        });
    }

    /**
     * De leden van kwantorvouw {@code q} die haar uitkomst dragen, met hun aantal: m leden met een oordeel (de
     * contributors van haar knoop zonder fout), waarvan de matrix voor k geldt. Bij "alle" die niet geldt de leden
     * waarvoor de matrix niet geldt, bij "geen van de" die niet geldt de leden waarvoor zij geldt, bij een telling de
     * leden waarvoor zij geldt en bij k = 0 alle leden; bij m = 0 "er is geen enkel lid", bij m = 1 dat lid bij naam
     * ("niet voor G1, het enige lid"). Het oordeel per lid is dat van de lezing, ook voor een lid na de
     * stop van ch. 5.10 (besluit van de eigenaar 2026-10-21); de positie ({@code reached}) leest de zin niet. Een lid
     * waarvan de matrix een fout gaf, heeft geen oordeel: het telt niet mee en staat er achteraan als "niet beoordeeld"
     * (voorlopig besluit van de hoofdsessie 2026-10-21). Faalde de vouw zelf op een fout, dan draagt haar knoop die
     * fout en faalt {@link Read#of} al.
     */
    private void members(Sentence.Builder b, Condition.QuantifierFold q, Read read) {
        ConditionReading.NodeReading n = read.of(q);
        if (!(n.value() instanceof Boolean holds))
            throw new IllegalStateException("de motor gaf voor de kwantorvouw \"" + summary(q) + "\" van "
                    + read.form() + " op " + read.label() + " geen oordeel, maar " + n.value());
        List<Object> holding = new ArrayList<>(), failing = new ArrayList<>();
        int unjudged = 0;
        for (ConditionReading.Contributor c : n.contributors()) {
            if (c.fault() != null) { unjudged++; continue; }
            if (!(c.value() instanceof Boolean verdict))
                throw new IllegalStateException("de motor gaf voor een lid van de kwantorvouw \"" + summary(q)
                        + "\" van " + read.form() + " op " + read.label() + " geen oordeel en geen fout");
            (verdict ? holding : failing).add(c.member());
        }
        int k = holding.size(), m = k + failing.size();
        // de zin noemt de leden bij het oordeel van de vouw; spreken die twee elkaar tegen, dan liegt de zin
        boolean expected = switch (q.quantifier()) {
            case ALLE -> k == m;
            case GEEN -> k == 0;
            case TEN_MINSTE -> k >= q.threshold();
            case TEN_HOOGSTE -> k <= q.threshold();
            case PRECIES -> k == q.threshold();
            case DE -> throw new IllegalStateException("de kwantorvouw \"" + summary(q) + "\" van " + read.form()
                    + " heeft de kwantor 'de', die een kwantorvouw niet draagt (WF-108 (a))");
        };
        if (expected != holds)
            throw new IllegalStateException("de kwantorvouw \"" + summary(q) + "\" van " + read.form() + " op "
                    + read.label() + " geldt volgens de motor " + (holds ? "wel" : "niet") + ", en haar matrix geldt"
                    + " volgens de lezing voor " + k + " van de " + m + " leden");
        b.open(Part.Kind.MEMBERS, Part.meta("members", m, "holding", k, "unjudged", unjudged));
        // m = 0 en m = 1 zonder telling (voorlopig besluit van de hoofdsessie 2026-10-21): "voor 1 van de 1" telt niets
        if (m == 0) {
            b.text("er is geen enkel lid");
        } else if (m == 1) {
            b.text(k == 0 ? "niet voor " : q.quantifier() == Condition.Quantifier.GEEN ? "wel voor " : "voor ");
            names(b, k == 0 ? failing : holding, q, read);
            b.text(", het enige lid");
        } else {
            switch (q.quantifier()) {
                case ALLE -> {
                    if (holds) b.text("voor alle " + m);
                    else {
                        b.text("niet voor ");
                        names(b, failing, q, read);
                        b.text(" (" + (m - k) + " van de " + m + ")");
                    }
                }
                case GEEN -> {
                    if (holds) b.text("voor geen van de " + m);
                    else {
                        b.text("wel voor ");
                        names(b, holding, q, read);
                        b.text(" (" + k + " van de " + m + ")");
                    }
                }
                default -> {
                    if (k == 0) {
                        b.text("voor geen van de " + m + " (");
                        names(b, failing, q, read);
                    } else {
                        b.text("voor " + k + " van de " + m + " (");
                        names(b, holding, q, read);
                    }
                    b.text(")");
                }
            }
        }
        if (unjudged > 0) b.text(" (" + unjudged + (unjudged == 1 ? " lid" : " leden") + " niet beoordeeld)");
        b.close(Part.Kind.MEMBERS);
    }

    /** De leden {@code members} bij hun naam ({@link #labels}), in de code-unit-volgorde daarvan, met "en" voor het
     *  laatste; boven vijf de eerste vijf en "en nog k". */
    private void names(Sentence.Builder b, List<Object> members, Condition.QuantifierFold q, Read read) {
        List<String> names = new ArrayList<>();
        for (Object member : members) {
            String label = labels.get(member);
            if (label == null)
                throw new IllegalStateException("een lid van de kwantorvouw \"" + summary(q) + "\" van " + read.form()
                        + " op " + read.label() + " heeft geen naam: de uitleg noemt de objecten van de invoer en de"
                        + " gemaakte objecten van het fixpunt, en dit " + member.getClass().getSimpleName()
                        + " is geen van beide");
            names.add(label);
        }
        names.sort(String::compareTo);
        int shown = Math.min(names.size(), 5);
        for (int i = 0; i < shown; i++) {
            if (i > 0) b.text(i == shown - 1 && names.size() <= 5 ? " en " : ", ");
            b.leaf(Part.Kind.SUBJECT, names.get(i));
        }
        if (names.size() > 5) b.text(" en nog " + (names.size() - 5));
    }

    /** Waarom een voorwaarde die volgens de motor niet geldt, niet gold: van een "alle"-samenstelling het lid waar de
     *  motor stopte, het eerste dat niet gold; van elke andere voorwaarde zijzelf, met wat de motor bereikte. */
    private void firstFailing(Sentence.Builder b, Condition c, Read read, ObjectType subject, boolean ucharText) {
        if (read.holds(c))
            throw new IllegalStateException("de voorwaarde van " + read.form() + " geldt op " + read.label()
                    + ", en de zin zegt waarom ze niet gold");
        if (c instanceof Condition.Compound s && s.quantifier() == Condition.Quantifier.ALLE) {
            for (Condition d : s.components())
                if (read.reached(d) && !read.holds(d)) {
                    firstFailing(b, d, read, subject, ucharText);
                    return;
                }
            throw new IllegalStateException("de samenstelling van " + read.form() + " op " + read.label()
                    + " gold niet, en de motor bereikte geen lid dat niet gold");
        }
        if (c instanceof Condition.Compound) {
            notHolds(b);
            verbaliseCondition(b, c, read, subject, ucharText);
            return;
        }
        Consumer<Sentence.Builder> structured = structuredAtom(c, read, subject, ucharText);
        if (structured != null) {
            notHolds(b);
            condition(b, c, read, structured);
        } else {
            unstructured(b, c, read, "niet voldaan");
        }
    }

    /** "niet geldt: ". */
    private static void notHolds(Sentence.Builder b) {
        b.leaf(Part.Kind.CONNECTIVE, "niet geldt").text(": ");
    }

    /** De wandeling van een kenmerkcheck over een rol, zoals de lader haar naast de check gaf; leeg voor een kenmerk
     *  van het onderwerp zelf. */
    private List<RoleBindingRef> walkOf(Condition.CharacteristicCheck k) {
        List<RoleBindingRef> walk = document.loaded().navigationBindings().get(k);
        return walk == null ? List.of() : walk;
    }

    /** Het objecttype dat de laatste stap van {@code walk} bereikt: de vuller van haar rol. */
    private ObjectType holderOf(List<RoleBindingRef> walk) {
        RoleBindingRef last = walk.get(walk.size() - 1);
        RegelSpraakModel.FactType ft = model.factTypes.get(last.factType());
        if (ft != null)
            for (RegelSpraakModel.Role r : ft.roles)
                if (r.name().equals(last.role())) {
                    ObjectType t = model.objectTypeByName(r.objectTypeName());
                    if (t != null) return t;
                }
        throw new IllegalStateException("de wandeling " + walk + " eindigt op een rol die het model niet kent");
    }

    /** Wie een kenmerkcheck draagt: het onderwerp, of de rollen van de wandeling, de laatste eerst. */
    private void who(Sentence.Builder b, List<RoleBindingRef> walk) {
        if (walk.isEmpty()) {
            b.text("het onderwerp");
            return;
        }
        for (int i = walk.size() - 1; i >= 0; i--) {
            RoleBindingRef step = walk.get(i);
            b.text(i == walk.size() - 1 ? "de " : " van de ");
            declaredName(b, naming.role(step.factType(), step.role()), Designatable.role(step.factType(), step.role()));
        }
    }

    private static String summary(Condition c) { return c.source() == null ? "?" : c.source().snippet().trim(); }

    // ---------------- rendering ----------------

    /** De waarde van een operand zoals de motor haar las, in NL-notatie met haar eenheid; "onbekend" voor een lege
     *  waarde. Een getal staat met de decimalen van zijn type, of van de declaratie van het gelezen lid; een getalliteral
     *  met de decimalen die zijn lexeem schrijft ("30.00" als "30,00"), want het type van een literal noemt ze niet. */
    private String renderOperand(Expr e, Read read) {
        ConditionReading.NodeReading n = read.of(e);
        return renderRead(e, n.value(), n.unit());
    }

    /** {@link #renderOperand} van waarde {@code v} met eenheid {@code u}, die de motor op knoop {@code e} las, of een
     *  lid van een vouw op die knoop bijdroeg. */
    private String renderRead(Expr e, Object v, Unit u) {
        if (v == null) return "onbekend";
        Datatype.Enumeration enumArray = enumerationOf(e);
        if (enumArray != null) return render(v, enumArray);
        // een jaartal schrijft geen duizendtalpunt: "2026", niet "2.026"
        if (e instanceof Expr.FunctionCall f && f.kind() == Expr.FunctionKind.JAAR_UIT && v instanceof BigDecimal y)
            return y.toPlainString();
        Datatype type = typeOf(e);
        if (e instanceof Expr.Literal lit && v instanceof BigDecimal b) {
            int scale = lexemeScale(lit.valueText());
            if (b.scale() < scale) v = b.setScale(scale);
            type = null;
        }
        String unit = u != null ? u.abbreviation()
                : (e.type() instanceof Datatype.Percentage || typeOf(e) instanceof Datatype.Percentage) ? "%" : null;
        if (!(v instanceof BigDecimal b)) return renderValue(v, type);
        String number = nlNumber(b, type);
        return unit == null ? number : number + " " + unit;
    }

    /** Het type van een operand: dat van de expressie, of voor een attribuut- of parameterlezing dat van de declaratie
     *  (een lezing uit uchars lader draagt zelf geen type); null voor elk ander operand. */
    private static Datatype typeOf(Expr e) {
        if (e.type() != null) return e.type();
        if (e instanceof Expr.AttributeReference ref && ref.objectType() != null)
            return ref.objectType().attribute(ref.attribute());
        if (e instanceof Expr.ParameterRef pr) return pr.parameter().datatype();
        return null;
    }

    /** Het aantal decimalen dat een getal-lexeem van de draad schrijft ("30.00": 2); 0 voor een geheel getal of een
     *  breuk. */
    private static int lexemeScale(String lexeme) {
        if (lexeme == null) return 0;
        Matcher m = Pattern.compile("-?[0-9]+\\.([0-9]+)").matcher(lexeme.trim());
        return m.matches() ? m.group(1).length() : 0;
    }

    /** {@link #renderValue}, met een enumeratiewaarde in het gekozen vocabulaire: een waarde is een declaratie. */
    private String render(Object v, Datatype type) {
        if (v instanceof String s && type instanceof Datatype.Enumeration e) return enumerationValue(e, s);
        return renderValue(v, type);
    }

    /** Een enumeratiewaarde in het gekozen vocabulaire. Wijkt haar benaming daar af van de gedeclareerde waarde,
     *  zoals een code ("01"), dan volgt die waarde erachter: de code alleen zegt de lezer niet waar ze voor staat.
     *  Gelijk zoals uchar gedeclareerde namen vergelijkt ({@link Namen#norm}), of zonder benaming: de naam alleen. */
    private String enumerationValue(Datatype.Enumeration e, String value) {
        String named = naming.value(e, value);
        for (String w : e.values())
            if (Namen.norm(w).equals(Namen.norm(value)) && !Namen.norm(named).equals(Namen.norm(w)))
                return named + " · " + w;
        return named;
    }

    /** Het enumeratietype van een operand: dat van de expressie, of voor een attribuut- of parameterread dat van de
     *  declaratie (een read uit uchars lader draagt zelf geen type); null voor elk ander operand. */
    private static Datatype.Enumeration enumerationOf(Expr e) {
        if (e.type() instanceof Datatype.Enumeration enumArray) return enumArray;
        if (e instanceof Expr.AttributeReference ref && ref.objectType() != null
                && ref.objectType().attribute(ref.attribute()) instanceof Datatype.Enumeration enumArray) return enumArray;
        if (e instanceof Expr.ParameterRef pr && pr.parameter().datatype() instanceof Datatype.Enumeration enumArray) return enumArray;
        return null;
    }

    /** Een operand als leesbaar deel van een vergelijking: een LITERAL toont zijn waarde één keer ("100 km") —
     *  géén verdubbeling van de brontekst; elk ander operand toont zijn omschrijving mét de waarde die de motor las
     *  tussen haakjes ("de maandloon nieuw van EOver (3.000,00 EUR)"), zodat de jurist de norm én het feit ziet. Een
     *  operand dat de motor niet bereikte, staat er zonder waarde. */
    private void operandProse(Sentence.Builder b, Expr e, Read read, boolean ucharText) {
        if (!read.reached(e)) {
            describe(b, e, ucharText);
            return;
        }
        if (e instanceof Expr.Literal) {
            value(b, renderOperand(e, read), read.of(e).value() == null);
            return;
        }
        describe(b, e, ucharText);
        b.text(" (");
        value(b, renderOperand(e, read), read.of(e).value() == null);
        b.text(")");
    }

    /** Een Java-waarde in helder Nederlands: getallen met punt-duizend/komma-decimaal en de eenheid uit het type;
     *  datums als dd-mm-jjjj; booleans als ja/nee. */
    static String renderValue(Object v, Datatype type) {
        if (v == null) return "leeg";
        if (v instanceof BigDecimal b) {
            String number = nlNumber(b, type);
            String unit = unitWord(type);
            return unit == null ? number : number + " " + unit;
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
    private static String nlNumber(BigDecimal b, Datatype type) {
        BigDecimal w = b;
        int decimals = -1;
        if (type instanceof Datatype.Numeric n && !n.wholeNumber()) decimals = n.decimals();
        else if (type instanceof Datatype.Percentage p && !p.wholeNumber()) decimals = p.decimals();
        else if (type instanceof Datatype.Numeric n && n.wholeNumber()) decimals = 0;
        if (decimals >= 0) w = w.setScale(decimals, java.math.RoundingMode.HALF_UP);
        String plain = w.abs().toPlainString();
        String whole = plain, fraction = "";
        int point = plain.indexOf('.');
        if (point >= 0) { whole = plain.substring(0, point); fraction = plain.substring(point + 1); }
        StringBuilder grouped = new StringBuilder();
        int n = whole.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % 3 == 0) grouped.append('.');
            grouped.append(whole.charAt(i));
        }
        String sign = b.signum() < 0 ? "-" : "";
        return sign + grouped + (fraction.isEmpty() ? "" : "," + fraction);
    }

    /** Het eenheid-woord voor een type (EUR, jr, %, …), of null wanneer het type er geen draagt. */
    private static String unitWord(Datatype type) {
        if (type instanceof Datatype.Percentage) return "%";
        Unit e = type == null ? null : type.unitOf();
        return e == null ? null : e.abbreviation();
    }

    /** Een korte omschrijving van WAT een operand is, uit zijn brontekst (bv. "de maandloon nieuw van EOver"); die is
     *  regeltekst en blijft zoals hij geschreven is, behalve de naam van een gelezen attribuut in een regel die uchar
     *  schreef ({@code ucharText}). Zonder brontekst noemt hij de declaratie. */
    private void describe(Sentence.Builder b, Expr e, boolean ucharText) {
        if (e == null) {
            b.text("de waarde");
            return;
        }
        String source = e.source().snippet();
        if (source != null && !source.isBlank()) {
            if (ucharText && e instanceof Expr.AttributeReference ref) inVocabulary(b, source.trim(), ref);
            else b.leaf(Part.Kind.QUOTE, source.trim(), declaration(declarationOf(e)));
            return;
        }
        if (e instanceof Expr.AttributeReference ref) {
            b.text("de ");
            attributeName(b, ref.objectType(), ref.attribute());
        } else if (e instanceof Expr.ParameterRef pr) {
            b.text("de ");
            declaredName(b, naming.parameter(pr.parameter().name()), Designatable.parameter(pr.parameter().name()));
        } else {
            b.text("de waarde");
        }
    }

    /** De declaratie die operand {@code e} leest, waar het één leest: een attribuut of een parameter; anders null. */
    private static Designatable declarationOf(Expr e) {
        if (e instanceof Expr.AttributeReference ref) return Naming.attributeOf(ref.objectType(), ref.attribute());
        if (e instanceof Expr.ParameterRef pr) return Designatable.parameter(pr.parameter().name());
        return null;
    }

    /** {@code tekst}, uchars spelling van lezing {@code ref} ("zijn opgegeven beëindigingsdatum"), met de naam van het
     *  gelezen attribuut via {@link Naming}: "zijn opgegeven Datum einde recht op uitkering". De lezing die uchars
     *  IR-lader leest draagt het onderwerpwoord niet, dus blijft de tekst de bron en verandert alleen de naam erin: het
     *  laatste voorkomen, want uchar schrijft de lezing als "zijn &lt;naam&gt;". */
    private void inVocabulary(Sentence.Builder b, String text, Expr.AttributeReference ref) {
        Matcher m = Pattern.compile("(?iu)" + namePattern(ref.attribute())).matcher(text);
        int begin = -1, end = -1;
        while (m.find()) { begin = m.start(); end = m.end(); }
        if (begin < 0)
            throw new IllegalStateException("uchar schreef de lezing van '" + ref.attribute() + "' als '" + text
                    + "', zonder die naam: de uitleg kan haar niet in het vocabulaire noemen");
        // de tekst om de naam heen is uchars regeltekst
        if (begin > 0) b.leaf(Part.Kind.QUOTE, text.substring(0, begin));
        attributeName(b, ref.objectType(), ref.attribute());
        if (end < text.length()) b.leaf(Part.Kind.QUOTE, text.substring(end));
    }

    /** Een kenmerk als gezegde: het werkwoord, wat vóór de naam staat (de ontkenning of het lidwoord) en de naam van
     *  declaratie {@code declaration}, in de volgorde van een hoofdzin ("heeft de uitkeringsduur verstreken") of een
     *  bijzin ("de uitkeringsduur verstreken heeft"). */
    private record Predicate(String verb, String before, String name, Designatable declaration) {
        void mainClause(Sentence.Builder b) {
            b.text(verb + " " + before);
            declaredName(b, name, declaration);
        }

        void clause(Sentence.Builder b) {
            b.text(before);
            declaredName(b, name, declaration);
            b.text(" " + verb);
        }
    }

    /** Kenmerk {@code characteristic} van {@code holder} als gezegde, met het werkwoord dat §3.5 zijn soort geeft en de
     *  ontkenning die Tabel 16 erbij schrijft: bijvoeglijk "is (niet) X", bezittelijk "heeft &lt;lidwoord&gt; X" of
     *  "heeft geen X", zelfstandig "is een X" of "is geen X". De soort is die van de declaratie, ook in een ander
     *  vocabulaire; de naam is die van {@link Naming}. Het model draagt het lidwoord van een declaratie niet, dus
     *  komt het bij een aanwezig bezittelijk kenmerk uit {@code ruleText}, de tekst van de regel die het kenmerk
     *  noemt. Null wanneer {@code holder} geen kenmerk {@code characteristic} kent. */
    private Predicate predicate(ObjectType holder, String characteristic, boolean present, String ruleText) {
        ObjectType.CharacteristicKind kind = holder == null ? null : holder.characteristicKind(characteristic);
        if (kind == null) return null;
        String name = naming.characteristic(holder, characteristic);
        Designatable declaration = Naming.characteristicOf(holder, characteristic);
        return switch (kind) {
            case BIJVOEGLIJK -> new Predicate("is", present ? "" : "niet ", name, declaration);
            case ZELFSTANDIG -> new Predicate("is", present ? "een " : "geen ", name, declaration);
            case BEZITTELIJK -> {
                String article = present ? articleBefore(ruleText, characteristic) : "geen";
                yield new Predicate("heeft", article == null ? "" : article + " ", name, declaration);
            }
        };
    }

    /** Het lidwoord ("de", "het" of "een") dat {@code ruleText} direct vóór het laatste voorkomen van de
     *  kenmerknaam schrijft. Het laatste, omdat het gezegde achter het onderwerp staat en het onderwerp dezelfde
     *  woorden kan bevatten ("Een Recht op uitkering WW heeft recht op uitkering"). Null wanneer de tekst daar geen
     *  lidwoord schrijft of de naam niet bevat. */
    private static String articleBefore(String ruleText, String characteristic) {
        if (ruleText == null || characteristic == null || characteristic.isBlank()) return null;
        Matcher m = Pattern.compile("(?iu)(?:(?<!" + BOUNDARY + ")(de|het|een)\\s+)?" + namePattern(characteristic))
                .matcher(ruleText);
        String article = null;
        while (m.find()) article = m.group(1);
        return article == null ? null : article.toLowerCase(Locale.ROOT);
    }

    /** Een teken dat een woord voortzet: een naam in een tekst staat niet direct naast zo'n teken. */
    private static final String BOUNDARY = "[\\p{L}\\p{N}-]";

    /** De gedeclareerde naam {@code naam} als patroon in een tekst: zijn woorden met witruimte ertussen, als hele
     *  woorden. */
    private static String namePattern(String name) {
        String words = String.join("\\s+", Arrays.stream(name.trim().split("\\s+")).map(Pattern::quote).toList());
        return "(?<!" + BOUNDARY + ")" + words + "(?!" + BOUNDARY + ")";
    }

    private static String operatorWords(Condition.Operator op) {
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

    private static boolean readBoolean(Object bean, String characteristic) {
        return Boolean.TRUE.equals(readRaw(bean, Namen.getter(characteristic)));
    }

    /** Een getter-read op een bean; probeert getX en isX (kenmerken lezen soms als isX). null bij afwezigheid. */
    private static Object readRaw(Object bean, String getter) {
        for (String m : new String[]{ getter, "is" + getter.substring(3) }) {
            try { return bean.getClass().getMethod(m).invoke(bean); }
            catch (NoSuchMethodException ignore) { /* probeer de andere vorm */ }
            catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    /** De bronverwijzing van een regel: haar niet-lege {@code // Bron:}-annotaties in bronvolgorde, getrimd; de zin
     *  verbindt ze met "; " ({@link Ground}); leeg zonder annotatie. Een lege annotatie valt weg omdat het model van de
     *  frontend haar houdt en de draad niet (WF-082): zo geven de route uit bronnen en die uit een IR-document dezelfde
     *  tekst. Die van een default geeft uchar al getrimd en niet leeg ({@link Default#statuteAnnotations}). */
    private static List<String> sourceAnnotations(ResolvedRule r) {
        return Objects.requireNonNull(r.statuteAnnotations(),
                        () -> "de regel '" + r.name() + "' draagt geen lijst van bronannotaties")
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
