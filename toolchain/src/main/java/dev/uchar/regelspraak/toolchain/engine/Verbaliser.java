package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.ir.Condition;
import dev.uchar.regelspraak.ir.Default;
import dev.uchar.regelspraak.ir.ExceptionGroup;
import dev.uchar.regelspraak.ir.Expr;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedAllocation;
import dev.uchar.regelspraak.ir.ResolvedFactCreation;
import dev.uchar.regelspraak.ir.ResolvedObjectCreation;
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
import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.loader.NodePositions;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.ConstructorValue;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;
import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.transpiler.tms.Period;
import dev.uchar.regelspraak.transpiler.tms.PeriodArithmetic;
import dev.uchar.regelspraak.transpiler.tms.PeriodDecisions;
import dev.uchar.regelspraak.transpiler.tms.Span;

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
 * <p><b>De waarden komen van de motor.</b> Of een kenmerk is toegekend en wat een lid houdt, staat in uchars lezing van
 * het fixpunt ({@link EndState}, {@code DroolsBackend.standingOf}): per lid de waarde, of iets haar afleidde, en wat de
 * invoer of de objectcreatie gaf. Hoe
 * de voorwaarde van een vorm uitkwam en wat elke knoop ervan vond, leest de uitleg uit de lezingen van een build met
 * lezingen ({@link DroolsBackend#readingsOf}: per vorm, op haar pointer, en object de status en per knoop bereikt of
 * niet, oordeel of waarde met eenheid). De uitleg rekent niets na: zij legt elke lezing op de knopen van het document
 * van de build ({@link Document}, dat uchars lader met de posities van de knopen las) en zet ze in zinnen. Een lege
 * waarde staat er als "(leeg)"; een lid of operand dat de motor niet bereikte, noemt de zin niet, of zonder
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
 * <p><b>Per periode.</b> Een stapel op een tijdsafhankelijk attribuut beslist per dag (ch. 7.11), en de motor noemt per
 * object de perioden met één uitkomst ({@link DroolsBackend#periodDecisionsOf}). Zo'n groep krijgt een zin per periode
 * waarin een variant besliste, met haar waarde in die periode, hoe zij die berekende en waarom zij gold, en de
 * grondslag van die variant; een zin per periode met een conflict; en een zin per periode waarin geen variant besliste
 * en de invoer bleef staan. Een periode zonder beslisser en zonder invoer heeft geen zin; besliste niemand op enige dag
 * en staat er geen invoer, dan zegt één zin dat. De andere varianten noemt de zin per periode niet.
 *
 * <p><b>Berekende waarden.</b> Een attribuut dat de gewone regels van het document op een object schrijven
 * (gelijkstelling, berekening, initialisatie; niet een regel die ch. 7.10 in een stapel opnam, die staat in de zin van
 * haar stapel) krijgt per object één zin over al zijn schrijvers samen ({@link ComputationExplanation}): de regel die
 * gold met de waarde, hoe zij die berekende (de regeltekst van haar waarde, met elke waarde die de berekening las of
 * vouwde, de leden van een vouw met wat elk bijdroeg, en de waarde vóór een afronding en vóór een begrenzing) en
 * waarom zij gold; daarna elke andere schrijver met de reden dat zij niet schreef. Alle waarden komen uit de lezing van
 * de regel ({@link DroolsBackend#readingsOf}), die sinds uchars kaart readings-of-unconditional-forms ook een regel
 * zonder voorwaarde heeft; wat het doel houdt waar geen regel schreef, komt uit uchars lezing van het fixpunt: de
 * invoerwaarde, of "de afgeleide waarde" waar iets anders dan de regels van de zin haar afleidde. Een tijdsafhankelijk
 * attribuut noemt per regel de perioden die zij besliste, met hun waarde, en de perioden waarin de invoer bleef staan,
 * uit {@link DroolsBackend#periodDecisionsOf}, en per periode die zij besliste haar operanden met hun tijdlijn in die
 * periode. Een vouw over de tijdlijnen van leden noemt elk lid met wat het bijdroeg: zijn tijdlijn, of bij een totaal
 * zijn totaal met de tijdlijn waaruit het kwam.
 *
 * <p><b>Bronverwijzing.</b> Een regel draagt de {@code // Bron:}-annotaties die direct boven haar kop staan
 * ({@link ResolvedRule#statuteAnnotations}; op de draad {@code statuteAnnotations}, uchars IR-SPEC ch. 3.1, WF-082),
 * een default die van zijn blok in {@link Default#statuteAnnotations} (ch. 6.1). De zin van een kenmerktoekenning
 * eindigt dan met ". Grondslag: <tekst>", die van een defaults-groep met de bron van de variant die besliste; zonder
 * annotatie staat er geen (geen verzonnen bron). Het stuk van de grondslag noemt die beslisser in de termen van het IR
 * (een regel bij haar vorm, een default bij zijn groep en zijn pointer daarin), zodat een lezer zijn annotaties en hun
 * plek in de bronnen vindt zonder de zin te lezen.
 *
 * <p><b>Gemaakte objecten.</b> Een object dat een objectcreatie van de sessie maakte ({@link EndState.EndObject#creation}) heeft
 * geen label; de uitleg noemt het met de naam die uchars canonieke tekst het geeft (RUNTIME-SEMANTICS ch. 5.12),
 * {@code «"<creatie>"»(<onderwerp>)#1}, met het onderwerp zoals de uitleg het noemt: een object van de invoer bij zijn
 * label, een gemaakt object bij deze naam ("«"schrijf een kwitantie"»(«"geef een vangstpenning"»(J1)#1)#1"). Zo'n
 * object krijgt zijn zinnen na de objecten van de invoer, in de volgorde van uchars lezing van de sessie, en heet zo ook als lid van
 * een kwantorvouw. Het heeft geen invoer: waar een object van de invoer zijn invoerwaarde houdt, houdt het de waarde
 * die de creatie het gaf (uchars RUNTIME-SEMANTICS ch. 7.8), en de zin noemt die "de bij de objectcreatie toegekende
 * waarde", ook per periode; een initialisatie besliste daar omdat het doel "bij de objectcreatie leeg was". De woorden
 * zijn die van de RegelSpraak-specificatie: het construct heet objectcreatie, en elk lid ervan een waardetoekenning.
 *
 * <p><b>Verdelingen en creaties.</b> Een verdeling schrijft het aandeel van elke ontvanger die haar koppeling met haar
 * bron verbindt en de onverdeelde rest van die bron; zij is een schrijver van dezelfde rang als een gewone regel op
 * hetzelfde doel (uchars RUNTIME-SEMANTICS ch. 7.8), dus staan aandeel en rest in de zin van een berekende waarde, met
 * de verdeling als schrijver en hoe zij verdeelde als berekening ("W verdeelde zijn meldgeld (10) in gelijke delen, …,
 * over M1 (3), M2 (3) en M3 (3) (3 leden)"). Een objectcreatie en een feitcreatie krijgen een zin op hun onderwerp,
 * zoals een gewone regel: wat zij maakten of legden, en waarom; of waarom niet ({@link #objectCreation},
 * {@link #factCreation}).
 *
 * <p><b>Stukken.</b> Elke zin schrijft de verbalisator op één {@link Sentence.Builder}, als een ondiepe boom van
 * {@link Part}s met een soort: het onderwerp, de namen met hun declaratie, de regeltekst, de waarden, de regelnamen, de
 * verbindingswoorden en de lijm, een voorwaarde als groep met het oordeel van de motor op haar knoop, de leden van een
 * kwantorvouw als groep met hun telling, een variant of schrijver die niet besliste als groep, een gelezen waarde met haar
 * haakjes als groep, en de grondslag als laatste stuk, ontleed ({@link Ground}). Een groep die een lezer mag weglaten
 * ({@link Part.Kind#MEMBERS}, {@link Part.Kind#VARIANT}, {@link Part.Kind#READING}) draagt haar eigen lijm, zodat de zin
 * zonder haar een zin blijft. De platte zin is
 * {@link Sentence#text()}, de tekst van de stukken achter elkaar: {@link #verbalise} geeft die, {@link #sentences} de
 * zinnen zelf. Een naam van een attribuut of kenmerk waarvan de zin de waarde op één object noemt, zegt van welk object
 * ({@link On}): de kop van een zin het onderwerp, een lezing het object waarop de motor haar las, ook over een rol; zo
 * vindt een lezer bij elke gelezen waarde de zin over (object, lid).
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
    /** De objecten die de creaties van de sessie maakten, in de volgorde van uchars lezing van de sessie. */
    private final List<EndState.EndObject> made;
    /** Elk object van de sessie zoals uchar het aan het fixpunt las, met de leden die een waarde houden. */
    private final Map<Object, EndState.EndObject> ends = new IdentityHashMap<>();
    /** Elke koppeling van de sessie aan het fixpunt, ook die een creatie legde. */
    private final List<EndState.Link> links;

    /**
     * Een verbalisator voor één sessie van een build met lezingen: {@code document} is het document van die build zoals
     * uchars lader het las, met de posities van zijn knopen ({@link Document#of}); {@code readings} wat
     * {@link DroolsBackend#readingsOf} van de sessie gaf; {@code standing} wat {@code DroolsBackend.standingOf} van
     * dezelfde sessie gaf, met de labels van de invoer gelezen, waarvan de uitleg de gemaakte objecten leest;
     * {@code naming} de namen, op het model van {@code document}.
     *
     * @throws IllegalStateException twee lezingen van één vorm op één object
     */
    public Verbaliser(Document document, Naming naming, List<ConditionReading> readings, EndState standing) {
        this.document = Objects.requireNonNull(document, "document");
        this.model = document.model();
        this.naming = Objects.requireNonNull(naming, "naming");
        this.made = Objects.requireNonNull(standing, "standing").objects().stream()
                .filter(o -> o.creation() != null).toList();
        for (EndState.EndObject o : standing.objects()) ends.put(o.object(), o);
        this.links = standing.links();
        for (ConditionReading r : readings)
            if (this.readings.computeIfAbsent(r.form(), k -> new IdentityHashMap<>()).put(r.object(), r) != null)
                throw new IllegalStateException("de motor gaf twee lezingen van " + r.form() + " op één object");
    }

    /**
     * Bouw de uitleg voor een gedraaide wereld. {@code instances} mapt een LABEL (het scenario-label of het
     * dienst-verzoek-id) op de bijbehorende, gevuurde en teruggeschreven bean; {@code typePerLabel} het objecttype per
     * label, op het model van het document. Per label eerst één zin per kenmerktoekenning van zijn type, in de volgorde
     * van het document, dan de zinnen per defaults-groep van zijn type: wat de motor besliste ({@code decisions}, uit
     * {@link DroolsBackend#decisionsOf} van dezelfde sessie, één zin), of op een tijdsafhankelijk attribuut wat zij per
     * periode besliste ({@code periodDecisions}, uit {@link DroolsBackend#periodDecisionsOf}, een zin per periode), dan
     * één zin per attribuut dat de gewone regels van het document op zijn type schrijven
     * ({@link ComputationExplanation}). Na de labels volgen zo de objecten die een creatie van de sessie maakte, bij
     * hun naam ({@link #madeName}), in de volgorde van het fixpunt, elk met het objecttype waarvan het de bean is.
     * Daarna één zin per regel buiten de scope ({@link Document#outside}) op het type van een label of een gemaakt
     * object.
     *
     * @throws IllegalStateException een pointer die niet op het document terug te leggen is, een beslissing over
     *                               een lid waarvoor het type van het object geen groep heeft, een groep op de
     *                               typeketen van het object zonder beslissing over haar doel, een lezing die
     *                               ontbreekt of niet past bij wat de zin zegt, of een gemaakt object dat de uitleg
     *                               niet kan noemen ({@link #madeName})
     */
    public List<String> verbalise(Map<String, Object> instances, Map<String, ObjectType> typePerLabel,
                                  List<DefaultsDecision> decisions, List<PeriodDecisions> periodDecisions) {
        return texts(sentences(instances, typePerLabel, decisions, periodDecisions));
    }

    /** {@link #verbalise(Map, Map, List, List)} als zinnen met hun stukken. */
    public List<Sentence> sentences(Map<String, Object> instances, Map<String, ObjectType> typePerLabel,
                                    List<DefaultsDecision> decisions, List<PeriodDecisions> periodDecisions) {
        Objects.requireNonNull(decisions, "decisions");
        Objects.requireNonNull(periodDecisions, "periodDecisions");
        Subjects subjects = subjects(instances, typePerLabel);
        List<Sentence> sink = new ArrayList<>();
        Set<String> outside = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : subjects.beans().entrySet()) {
            String label = e.getKey();
            Object bean = e.getValue();
            ObjectType ot = subjects.types().get(label);
            if (ot == null || bean == null) continue;
            assignments(label, bean, ot, sink);
            ComputationExplanation computations = new ComputationExplanation(label, bean, ot, decisions,
                    periodDecisions);
            new DefaultsExplanation(document, label, bean, ot, decisions, periodDecisions, computations).sentences(sink);
            computations.sentences(sink);
            creations(label, bean, ot, sink);
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
        Map<Object, EndState.EndObject> byObject = new IdentityHashMap<>();
        Map<Object, Set<String>> creationsPerSubject = new IdentityHashMap<>();
        for (EndState.EndObject g : made) {
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
        for (EndState.EndObject g : made) {
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
    private String madeName(Object o, Map<Object, EndState.EndObject> byObject, List<Object> path) {
        String known = labels.get(o);
        if (known != null) return known;
        EndState.EndObject g = byObject.get(o);
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

    /** Het objecttype van gemaakt object {@code o}, dat {@code name} heet: het type van het model van de uitleg
     *  waarvan het de gegenereerde bean is. */
    private ObjectType madeType(String name, Object o) {
        String bean = o.getClass().getName();
        for (ObjectType ot : model.objectTypes.values())
            if (bean.equals(FactModelGenerator.PACKAGE + "." + Namen.ident(ot.name, true))) return ot;
        throw new IllegalStateException("het gemaakte object " + name + " is een " + bean + ", de bean van geen"
                + " objecttype van het model van de uitleg");
    }

    /** De zinnen over de objectcreaties en de feitcreaties van het document op één object, als onderwerp, in de
     *  volgorde van het document ({@link #objectCreation}, {@link #factCreation}). Van de versies van één vorm, die
     *  elk een eigen geldigheid hebben, spreekt een versie buiten haar geldigheid alleen waar geen andere versie
     *  spreekt, en dan één keer: anders zei de zin dat de vorm niets maakte naast de versie die wel maakte. */
    private void creations(String label, Object bean, ObjectType ot, List<Sentence> sink) {
        Map<String, List<Creation>> perForm = new LinkedHashMap<>();
        for (ResolvedObjectCreation c : model.objectCreations)
            if (c.subject() != null && ot.isA(c.subject()))
                perForm.computeIfAbsent("o" + RegelSpraakModel.formName(c.name()), k -> new ArrayList<>())
                        .add(objectCreation(label, bean, c));
        for (ResolvedFactCreation c : model.factCreations)
            if (c.anchor() != null && ot.isA(c.anchor()))
                perForm.computeIfAbsent("f" + RegelSpraakModel.formName(c.name()), k -> new ArrayList<>())
                        .add(factCreation(label, bean, c));
        for (List<Creation> versions : perForm.values()) {
            boolean spoken = versions.stream().anyMatch(v -> v.sentence() != null && !v.outside());
            boolean outside = false;
            for (Creation v : versions) {
                if (v.sentence() == null || v.outside() && (spoken || outside)) continue;
                outside |= v.outside();
                sink.add(v.sentence());
            }
        }
    }

    /** De zin van één versie van een creatie op één object, of null; {@code outside} waar die versie buiten haar
     *  geldigheid valt. */
    private record Creation(Sentence sentence, boolean outside) {}

    /**
     * Eén objectcreatie voor één onderwerp, zoals een gewone regel haar zin krijgt. Maakte zij een object voor het
     * onderwerp ({@link EndState.EndObject#creation}): "J1 heeft een Penning «"…"»(J1)#1 volgens de regel '…'", met
     * " (onvoorwaardelijk)" of waarom zij gold, en als {@link Part.Kind#COMPUTATION} haar waardetoekenningen met de
     * waarde die elk het gemaakte object gaf ("met als bedrag zijn vangst (30 EUR)"). Maakte zij er geen: "J2 heeft
     * geen Penning volgens de regel '…', want" met de reden uit haar lezing (haar voorwaarde gold niet, zij valt buiten
     * haar geldigheid, of de koppeling van het onderwerp stond al in de invoer). Een objectcreatie zonder voorwaarde
     * die niets maakte, heeft geen lezing en geen zin, zoals een kenmerktoekenning zonder voorwaarde die niet toekent.
     */
    private Creation objectCreation(String label, Object bean, ResolvedObjectCreation c) {
        String pointer = document.positions().versionOf(c);
        if (pointer == null)
            throw new IllegalStateException("de objectcreatie '" + c.name() + "' staat niet als versie in het document"
                    + " van de uitleg");
        if (c.createdType() == null)
            throw new IllegalStateException("de objectcreatie '" + c.name() + "' noemt het objecttype dat zij maakt niet");
        List<EndState.EndObject> mine = made.stream()
                .filter(o -> o.subject() == bean && c.name().equals(o.creation())).toList();
        if (mine.size() > 1)
            throw new IllegalStateException("het fixpunt geeft twee objecten van de creatie '" + c.name() + "' voor "
                    + label + ", en een creatie maakt één object per onderwerp (RUNTIME-SEMANTICS ch. 5.5)");
        Condition condition = c.condition();
        ConditionReading reading = condition == null ? null : reading(pointer, bean, label);
        if (reading != null && reading.status() == ConditionReading.Status.FAULTS) throw faulted(pointer, label);
        Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
        String rule = RegelSpraakModel.formName(c.name());
        if (!mine.isEmpty()) {
            if (reading != null && reading.status() != ConditionReading.Status.HOLDS)
                throw new IllegalStateException("de objectcreatie '" + c.name() + "' maakte een object voor " + label
                        + ", en haar lezing daar is " + reading.status());
            EndState.EndObject o = mine.get(0);
            b.text("heeft een ");
            objectTypeName(b, c.createdType());
            b.text(" ").leaf(Part.Kind.SUBJECT, nameOf(o.object())).text(" volgens de regel '")
                    .leaf(Part.Kind.RULE, rule).text("'");
            if (condition == null) b.text(" (onvoorwaardelijk)");
            memberAssignments(b, c, o);
            if (condition != null) {
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                verbaliseCondition(b, condition, read(pointer, bean, label, condition), c.subject(), false);
            }
            return new Creation(b.build(), false);
        }
        if (reading == null) return new Creation(null, false);
        b.text("heeft geen ");
        objectTypeName(b, c.createdType());
        b.text(" volgens de regel '").leaf(Part.Kind.RULE, rule).text("', ").leaf(Part.Kind.CONNECTIVE, "want")
                .text(" ");
        switch (reading.status()) {
            case FAILS -> firstFailing(b, condition, read(pointer, bean, label, condition), c.subject(), false);
            case OUTSIDE_VALIDITY -> b.text("de regel valt op de rekendatum buiten haar geldigheid");
            // the 1:1 shape creates nothing for a subject the input already links (ch. 5.5)
            case DISPLACED -> b.text("de koppeling stond al in de invoer");
            case HOLDS -> throw new IllegalStateException("de voorwaarde van de objectcreatie '" + c.name() + "' geldt"
                    + " op " + label + ", en het fixpunt noemt geen object dat zij voor " + label + " maakte");
            case FAULTS -> throw faulted(pointer, label);
        }
        return new Creation(b.build(), reading.status() == ConditionReading.Status.OUTSIDE_VALIDITY);
    }

    /** De waardetoekenningen van objectcreatie {@code c} als groep {@link Part.Kind#COMPUTATION}, met de dubbele punt
     *  ervoor: per lid "als" de naam en de regeltekst van de waarde met de waarde die het gemaakte object {@code o}
     *  ervan kreeg ({@link EndState.Member#given}), een literal alleen als waarde. Een waardetoekenning wordt bij de
     *  creatie gelezen, niet aan het fixpunt, dus noemt de zin geen operand ervan. */
    private void memberAssignments(Sentence.Builder b, ResolvedObjectCreation c, EndState.EndObject o) {
        if (c.assignments().isEmpty()) return;
        String name = nameOf(o.object());
        ObjectType created = typeOf(o.object());
        b.open(Part.Kind.COMPUTATION, Map.of()).text(": met");
        for (int i = 0; i < c.assignments().size(); i++) {
            ResolvedObjectCreation.MemberAssignment x = c.assignments().get(i);
            b.text(i == 0 ? " als " : i == c.assignments().size() - 1 ? " en als " : ", als ");
            List<String> cell = x.labels() == null ? null
                    : FactModelGenerator.cellKey(model, created.dimensionsOf(x.attribute()), x.labels());
            if (x.characteristic())
                b.leaf(Part.Kind.NAME, naming.characteristic(created, x.attribute()),
                        declaration(Naming.characteristicOf(created, x.attribute()), new On(name)));
            else if (cell != null) cellName(b, created, x.attribute(), cell, new On(name));
            else attributeName(b, created, x.attribute(), new On(name));
            b.text(" ");
            EndState.Member m = member(o.object(), x.attribute(), x.characteristic(), cell);
            Object given = given(m);
            String text = x.characteristic() ? (given == null ? "onwaar" : "waar")
                    : renderHeld(given, created.attribute(x.attribute()));
            // a literal, a marker or a value of a literal is its own rule text: once, as the value
            boolean literal = x.value() instanceof Expr.Literal
                    || x.value() instanceof Expr.Construct k && (k.argument() == null || k.argument() instanceof Expr.Literal)
                    || x.value().source() == null || x.value().source().snippet() == null
                    || x.value().source().snippet().isBlank();
            if (literal) {
                value(b, text, given == null && !x.characteristic(), given);
                continue;
            }
            b.leaf(Part.Kind.QUOTE, x.value().source().snippet().trim());
            reading(b, text, given == null && !x.characteristic(), given);
        }
        b.close(Part.Kind.COMPUTATION);
    }

    /** Een waarde die een lid houdt: een tijdlijn als haar perioden, een andere als {@link #render}. */
    private String renderHeld(Object v, Datatype type) {
        if (v instanceof List<?> segments) return renderPeriods(Period.list(segments), null, x -> render(x, type));
        return render(v, type);
    }

    /** Objecttype {@code t} in het vocabulaire, met de sleutels van zijn declaratie. */
    private void objectTypeName(Sentence.Builder b, ObjectType t) {
        declaredName(b, naming.objectType(t.name), Designatable.objectType(t.name));
    }

    /**
     * Eén feitcreatie voor één anker, zoals een gewone regel haar zin krijgt. Legde zij koppelingen van haar feittype
     * vanaf het anker ({@link EndState.Link#made}): "H1 heeft S1 en S2 als winterschuit volgens de regel '…'", met
     * " (onvoorwaardelijk)" of waarom zij gold. Legde zij er geen: "H2 heeft geen winterschuit volgens de regel '…',
     * want" met de reden uit haar lezing (haar voorwaarde gold niet, of zij valt buiten haar geldigheid). Een
     * voorwaarde op het doel heeft geen lezing op het anker (uchars RUNTIME-SEMANTICS ch. 5.12): dan noemt de zin haar
     * niet, en zonder koppeling heeft de feitcreatie dan geen zin, zoals een zonder voorwaarde die niets legde.
     */
    private Creation factCreation(String label, Object bean, ResolvedFactCreation c) {
        String pointer = document.positions().versionOf(c);
        if (pointer == null)
            throw new IllegalStateException("de feitcreatie '" + c.name() + "' staat niet als versie in het document"
                    + " van de uitleg");
        if (c.factType() == null || c.anchorRole() == null || c.targetRole() == null)
            throw new IllegalStateException("de feitcreatie '" + c.name() + "' noemt haar feittype en rollen niet");
        String factType = Namen.norm(c.factType()), anchorRole = Namen.norm(c.anchorRole());
        List<String> targets = new ArrayList<>();
        for (EndState.Link k : links) {
            if (!k.made() || !Namen.norm(k.factType()).equals(factType)) continue;
            if (k.first() == bean && Namen.norm(k.firstRole()).equals(anchorRole)) targets.add(nameOf(k.second()));
            else if (k.second() == bean && Namen.norm(k.secondRole()).equals(anchorRole)) targets.add(nameOf(k.first()));
        }
        targets.sort(String::compareTo);
        Condition condition = c.condition();
        ConditionReading reading = condition == null ? null : readings.getOrDefault(pointer, Map.of()).get(bean);
        if (reading != null && reading.status() == ConditionReading.Status.FAULTS) throw faulted(pointer, label);
        String rule = RegelSpraakModel.formName(c.name());
        Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
        if (!targets.isEmpty()) {
            if (reading != null && reading.status() != ConditionReading.Status.HOLDS)
                throw new IllegalStateException("de feitcreatie '" + c.name() + "' legde koppelingen vanaf " + label
                        + ", en haar lezing daar is " + reading.status());
            b.text("heeft ");
            int shown = Math.min(targets.size(), 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) b.text(i == shown - 1 && targets.size() <= 5 ? " en " : ", ");
                b.leaf(Part.Kind.SUBJECT, targets.get(i));
            }
            if (targets.size() > 5) b.text(" en nog " + (targets.size() - 5));
            b.text(" als ");
            declaredName(b, naming.role(c.factType(), c.targetRole()), Designatable.role(c.factType(), c.targetRole()));
            b.text(" volgens de regel '").leaf(Part.Kind.RULE, rule).text("'");
            if (condition == null) b.text(" (onvoorwaardelijk)");
            else if (reading != null) {
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                verbaliseCondition(b, condition, read(pointer, bean, label, condition), c.anchor(), false);
            }
            return new Creation(b.build(), false);
        }
        if (reading == null || reading.status() == ConditionReading.Status.HOLDS) return new Creation(null, false);
        b.text("heeft geen ");
        declaredName(b, naming.role(c.factType(), c.targetRole()), Designatable.role(c.factType(), c.targetRole()));
        b.text(" volgens de regel '").leaf(Part.Kind.RULE, rule).text("', ").leaf(Part.Kind.CONNECTIVE, "want")
                .text(" ");
        switch (reading.status()) {
            case FAILS -> firstFailing(b, condition, read(pointer, bean, label, condition), c.anchor(), false);
            case OUTSIDE_VALIDITY -> b.text("de regel valt op de rekendatum buiten haar geldigheid");
            default -> throw new IllegalStateException("de motor noemt de feitcreatie '" + c.name() + "' op " + label
                    + " " + reading.status() + ", en een feitcreatie wacht niet op haar beurt");
        }
        return new Creation(b.build(), reading.status() == ConditionReading.Status.OUTSIDE_VALIDITY);
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

    /** Een variant van een stapel: een default van een groep van het document, of een gewone regel die de stapel van
     *  een subtype als basis opneemt (ch. 7.10). {@code holder} is de default die haar in een uitzonderingsgroep
     *  draagt, null aan de wortel; {@code member} de index van die uitzonderingsgroep, {@code place} haar index in haar
     *  keten. {@code replaced}: een vorm-a-basis die de eigen basis van een subtype verving en in diens stapel alleen
     *  met haar uitzonderingsgroepen staat ({@link DefaultsExplanation#rootChain}). */
    private record Variant(String pointer, Default d, ResolvedRule rule, PrioritizedDefaults group, Variant holder,
                           int member, int place, boolean replaced) {

        boolean hasOwnContent() { return rule != null || !replaced && d.hasOwnContent(); }

        Variant asReplaced() { return new Variant(pointer, d, rule, group, holder, member, place, true); }

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
                            null, group, this, m, l, false));
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
                    g, null, -1, j, false);
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
        if (RULE_POINTER.matcher(pointer).matches()) {
            // the loader recorded the pointer of every rule version it read
            for (ResolvedRule r : document.rules())
                if (pointer.equals(document.positions().versionOf(r)))
                    return new Variant(pointer, null, r, null, null, -1, 0, false);
            throw unresolved(pointer, "het document heeft geen regelversie op die pointer");
        }
        throw unresolved(pointer, "dat is geen pointer naar een default of een regelversie");
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
        /** Wat de stapels op een tijdsafhankelijk attribuut per periode besloten; een record met wortelketens is dat
         *  van een stapel, een zonder dat van gewone regels. */
        private final List<PeriodDecisions> periodDecisions;
        /** De berekeningen van hetzelfde object: de beslisser van een attribuut krijgt de berekening van een gewone
         *  regel. */
        private final ComputationExplanation computations;

        DefaultsExplanation(Document document, String label, Object bean, ObjectType type,
                            List<DefaultsDecision> decisions, List<PeriodDecisions> periodDecisions,
                            ComputationExplanation computations) {
            this.document = document;
            this.label = label;
            this.bean = bean;
            this.type = type;
            this.decisions = decisions;
            this.periodDecisions = periodDecisions;
            this.computations = computations;
        }

        void sentences(List<Sentence> sink) {
            // Elke stapel komt uit de motor: elk object van de sessie heeft per stapel van zijn type één record, ook
            // waar niemand besliste (ch. 7.10: per concreet type en doel één stapel), uit decisionsOf, of uit
            // periodDecisionsOf voor een stapel per dag (ch. 7.11). Het document geeft alleen de volgorde, die van de
            // eerste groep die het doel op de typeketen van het object schrijft.
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
            Map<String, PeriodDecisions> perDay = new LinkedHashMap<>();
            for (PeriodDecisions p : periodDecisions) {
                if (p.object() != bean || p.rootChains().isEmpty()) continue;
                if (!members.contains(p.member()))
                    throw new IllegalStateException("de motor gaf beslissingen per periode van een stapel over '"
                            + p.member() + "' van " + label + ", en het document heeft voor " + type.name
                            + " geen defaults-groep op dat lid");
                if (perDay.put(p.member(), p) != null || perMember.containsKey(p.member()))
                    throw new IllegalStateException("de motor gaf over '" + p.member() + "' van " + label + " twee"
                            + " records van zijn stapel, waar één stapel er één geeft");
            }
            for (String member : members) {
                PeriodDecisions p = perDay.get(member);
                if (p != null) {
                    perPeriod(p, sink);
                    continue;
                }
                DefaultsDecision d = perMember.get(member);
                if (d == null)
                    throw new IllegalStateException("de motor gaf geen beslissing over '" + member + "' van " + label
                            + ", ook niet per periode, en het document heeft voor " + type.name + " een defaults-groep"
                            + " op dat lid: elk object van de sessie heeft er één per stapel van zijn type");
                if (d.conflict() != null) sink.add(conflicted(d));
                else if (d.decider() != null) sink.add(decided(d));
                else sink.add(undecided(d));
            }
        }

        // ---- een stapel per dag (ch. 7.11): een zin per periode ----

        /** De zinnen van stapel-record {@code p}: één per periode met een beslisser, een conflict of een invoer die
         *  bleef staan, in de volgorde van hun dagen; een periode zonder beslisser en zonder invoer heeft geen zin.
         *  Heeft geen periode een zin, dan zegt één zin dat geen variant besliste. */
        private void perPeriod(PeriodDecisions p, List<Sentence> sink) {
            int before = sink.size();
            for (PeriodDecisions.Span s : p.spans()) {
                if (s.decider() != null) sink.add(decidedIn(p, s));
                else if (s.conflict() != null) sink.add(conflictedIn(p, s));
                else if (s.input() != null) {
                    Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
                    standingIn(b, p.member(), s);
                    sink.add(b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" geen variant besliste").build());
                }
            }
            if (sink.size() > before) return;
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" heeft geen ");
            attributeName(b, type, p.member(), new On(label));
            sink.add(b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" geen variant besliste").build());
        }

        /** Periode {@code s}, waarin een variant de stapel besliste: haar waarde in die periode, haar naam, hoe zij de
         *  waarde berekende en waarom zij gold ({@link #reason}), en haar grondslag. */
        private Sentence decidedIn(PeriodDecisions p, PeriodDecisions.Span s) {
            String pointer = s.decider().pointer();
            if (p.rootChains().stream().flatMap(List::stream)
                    .noneMatch(root -> pointer.equals(root) || pointer.startsWith(root + "/")))
                throw new IllegalStateException("de beslisser " + pointer + " van '" + p.member() + "' van " + label
                        + " staat in geen keten aan de wortel van zijn stapel: " + p.rootChains());
            Variant decider = variant(pointer);
            if (!decider.hasOwnContent())
                throw new IllegalStateException("de beslisser " + pointer + " van '" + p.member() + "' van " + label
                        + " is een groep-default zonder eigen besluit of waarde");
            requireTarget(decider, p.member());
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" heeft als ");
            attributeName(b, type, p.member(), new On(label));
            b.text(" ");
            periodValue(b, s.decider().decision(), s, p.member());
            b.text(" volgens ");
            name(b, decider);
            reason(b, decider, false);
            ground(b, groundOf(decider), deciderOf(decider));
            return b.build();
        }

        /** Periode {@code s}, waarin de leden die de stapel verzoent verschillend besloten (ch. 7.2, 7.9): wat het doel
         *  in die periode houdt, en het conflict zoals {@link #conflicted} het noemt. */
        private Sentence conflictedIn(PeriodDecisions p, PeriodDecisions.Span s) {
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            standingIn(b, p.member(), s);
            conflict(b, s.conflict(), p.member(), false);
            return b.build();
        }

        /** Wat {@code member} houdt in periode {@code s}, waarin geen variant schreef: de invoerwaarde met de periode
         *  ({@link #standingValue}), of geen waarde. */
        private void standingIn(Sentence.Builder b, String member, PeriodDecisions.Span s) {
            if (s.input() == null) {
                b.text("heeft geen ");
                attributeName(b, type, member, new On(label));
                b.text(" " + periodText(s.from(), s.to()));
                return;
            }
            b.text("houdt als ");
            attributeName(b, type, member, new On(label));
            b.text(" " + standingValue(bean) + " ");
            periodValue(b, s.input(), s, member);
        }

        /** Waarde {@code v} van {@code member} in periode {@code s}, zoals RegelSpraak een periode schrijft: de waarde
         *  van een tijdlijndoel. */
        private void periodValue(Sentence.Builder b, Object v, PeriodDecisions.Span s, String member) {
            b.leaf(Part.Kind.VALUE, inPeriod(render(v, type.attribute(member)), s.from(), s.to()),
                    Part.meta("timeline", true));
        }

        // ---- de drie zinnen ----

        private Sentence decided(DefaultsDecision d) {
            List<List<Variant>> chains = new ArrayList<>();
            for (List<String> chain : d.rootChains()) chains.add(rootChain(chain));
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
                    case BEFORE -> notDecidedVariant(b, t.variant(), d);
                    case SIBLING -> {
                        b.open(Part.Kind.VARIANT, Part.meta("decided", false)).text("; ");
                        name(b, t.variant());
                        b.text(" besliste niet anders").close(Part.Kind.VARIANT);
                    }
                    case AFTER -> after.add(t.variant());
                    case DECIDER -> { }
                }
            }
            for (int i = 0; i < after.size(); i++)
                displacedVariant(b, after.get(i), i == 0 ? "; verdrongen: " : ", ", d);
            if (d.characteristic() && Boolean.FALSE.equals(d.decider().decision())
                    && given(member(bean, d.member(), true, null)) != null)
                b.text("; de invoer kende het kenmerk toe, en dat blijft staan");
            ground(b, groundOf(decider), deciderOf(decider));
            return b.build();
        }

        private Sentence conflicted(DefaultsDecision d) {
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            standing(b, d.member(), d.characteristic());
            conflict(b, d.conflict(), d.member(), d.characteristic());
            return b.build();
        }

        /** Conflict {@code c} na wat het doel houdt: waar het stond en wat elk lid besliste. */
        private void conflict(Sentence.Builder b, DefaultsDecision.Conflict c, String member, boolean characteristic) {
            b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" er is een conflict");
            if (c.pointer() != null) {
                b.text(" in de uitzonderingen op ");
                carrierName(b, variant(c.pointer()));
            }
            b.text(": ");
            boolean first = true;
            for (DefaultsDecision.Decider x : c.deciders()) {
                Variant v = variant(x.pointer());
                requireTarget(v, member);
                if (!first) b.text(" ").leaf(Part.Kind.CONNECTIVE, "en").text(" ");
                first = false;
                name(b, v);
                b.text(" besliste '");
                outcome(b, member, characteristic, x.decision());
                b.text("'");
            }
            b.text(", dus de groep schrijft niets");
        }

        /** Niemand besliste: wat het doel houdt, en per variant van de stapel waarom ze niet besliste. De varianten en
         *  hun volgorde zijn die van het record ({@link DefaultsDecision#rootChains}), ook waar ch. 7.10 de stapel uit
         *  twee declaraties samenstelt. */
        private Sentence undecided(DefaultsDecision d) {
            List<Turn> turns = new ArrayList<>();
            for (List<String> chain : d.rootChains())
                for (Variant v : rootChain(chain)) {
                    requireTarget(v, d.member());
                    all(v, Role.BEFORE, turns);
                }
            if (turns.isEmpty())
                throw new IllegalStateException("de motor gaf over '" + d.member() + "' van " + label
                        + " een stapel zonder varianten: " + d.rootChains());
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            standing(b, d.member(), d.characteristic());
            b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" geen variant besliste");
            for (Turn t : turns) notDecidedVariant(b, t.variant(), d);
            return b.build();
        }

        /**
         * Een verdrongen variant, als groep met haar scheiding {@code separator} ervoor: haar naam en wat zij vond, uit
         * uchars lezing van een verdrongen default (RUNTIME-SEMANTICS ch. 5.12: status {@code DISPLACED}, met de knopen
         * van haar voorwaarde en, waar die geldt, van haar waarde, op de fixpuntwereld). Bij een attribuut
         * "(gold, &lt;waarde&gt;)", "(gold, gaf geen waarde)" of "(gold niet)", bij een kenmerk "(gold, toekennen)" of
         * "(gold, niet toekennen)" naar het besluit van de variant; zonder voorwaarde "(onvoorwaardelijk, …)". De groep
         * draagt {@code holds} (niet zonder voorwaarde) en bij een kenmerk {@code decision}. Een fout op een knoop van
         * de lezing had de sessie niet (de variant was niet aan de beurt): dan de naam alleen, met
         * {@code unreadable}. Een variant buiten haar geldigheid: "(buiten haar geldigheid)". Een initialisatie
         * waarvan de invoer het doel vulde, heeft geen knopen: "(gold niet)".
         */
        private void displacedVariant(Sentence.Builder b, Variant v, String separator, DefaultsDecision d) {
            ConditionReading r = reading(v.pointer(), bean, label);
            Condition c = conditionOf(v);
            boolean unconditional = c == null || c instanceof Condition.Compound s
                    && s.quantifier() == Condition.Quantifier.ALLE && s.components().isEmpty();
            boolean characteristic = d.characteristic();
            Boolean decision = characteristic ? (v.rule() != null ? Boolean.TRUE : v.d().decision()) : null;
            Map<String, Object> meta = Part.meta("decided", false, "displaced", true);
            switch (r.status()) {
                case OUTSIDE_VALIDITY -> {
                    b.open(Part.Kind.VARIANT, meta).text(separator);
                    name(b, v);
                    b.text(" (buiten haar geldigheid)").close(Part.Kind.VARIANT);
                    return;
                }
                case FAULTS -> throw faulted(v.pointer(), label);
                case HOLDS, FAILS -> throw new IllegalStateException("de variant " + v.pointer() + " van '"
                        + d.member() + "' van " + label + " heeft de lezing " + r.status() + ", en de beslissing"
                        + " noemt haar na de beslisser, als verdrongen");
                case DISPLACED -> { }
            }
            // een initialisatie waarvan de invoer het doel vulde, las de motor niet: zij gold niet, zoals vóór de
            // beslisser ("de X had een invoerwaarde")
            if (r.nodes().isEmpty() && isInitialisation(v)) {
                meta.put("holds", false);
                b.open(Part.Kind.VARIANT, meta).text(separator);
                name(b, v);
                b.text(" (gold niet)").close(Part.Kind.VARIANT);
                return;
            }
            // een kenmerkdefault zonder rechtvaardiging heeft niets te lezen: zijn besluit staat in het document
            if (r.nodes().isEmpty() && !(characteristic && c == null))
                throw new IllegalStateException("de motor gaf van de verdrongen variant " + v.pointer() + " van '"
                        + d.member() + "' van " + label + " een lezing zonder knopen, en een verdrongen default heeft"
                        + " de knopen van zijn voorwaarde (RUNTIME-SEMANTICS ch. 5.12)");
            if (r.nodes().stream().anyMatch(n -> n.fault() != null)) {
                meta.put("unreadable", true);
                b.open(Part.Kind.VARIANT, meta).text(separator);
                name(b, v);
                b.close(Part.Kind.VARIANT);
                return;
            }
            Boolean holds = unconditional ? null : read(v.pointer(), bean, label, c).holds(c);
            if (holds != null) meta.put("holds", holds);
            if (decision != null && !Boolean.FALSE.equals(holds)) meta.put("decision", decision);
            b.open(Part.Kind.VARIANT, meta).text(separator);
            name(b, v);
            if (Boolean.FALSE.equals(holds)) {
                b.text(" (gold niet)").close(Part.Kind.VARIANT);
                return;
            }
            b.text(unconditional ? " (onvoorwaardelijk, " : " (gold, ");
            if (characteristic) b.text(decision ? "toekennen" : "niet toekennen");
            else displacedValue(b, v, c, d.member());
            b.text(")").close(Part.Kind.VARIANT);
        }

        /** De waarde die verdrongen variant {@code v} van een attribuut gaf, uit haar lezing: de knopen van haar waarde
         *  volgen op die van haar voorwaarde {@code c}; "gaf geen waarde" waar zij leeg was. */
        private void displacedValue(Sentence.Builder b, Variant v, Condition c, String member) {
            Expr value = v.rule() != null ? v.rule().value() : v.d().consequence();
            int offset = c == null ? 0 : document.positions().subtree(c).size();
            Object found = read(v.pointer(), bean, label, value, offset).of(value).value();
            if (found == null) b.text("gaf geen waarde");
            else value(b, render(found, type.attribute(member)), false, found);
        }

        /** Een variant die aan de beurt was en niet besliste, als groep met de "; " ervoor: haar naam en de reden. */
        private void notDecidedVariant(Sentence.Builder b, Variant v, DefaultsDecision d) {
            b.open(Part.Kind.VARIANT, Part.meta("decided", false)).text("; ");
            name(b, v);
            b.text(" ");
            notDecided(b, v, d.characteristic(), d.member());
            b.close(Part.Kind.VARIANT);
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
                predicateOf(member, Boolean.TRUE.equals(decision)).mainClause(b, new On(label));
                return;
            }
            b.text("heeft als ");
            attributeName(b, type, member, new On(label));
            b.text(" ");
            value(b, render(decision, type.attribute(member)), decision == null, decision);
        }

        /** Wat het doel houdt waar de groep niets schreef: het kenmerk zoals de invoer het gaf, de invoerwaarde of
         *  leeg. */
        private void standing(Sentence.Builder b, String member, boolean characteristic) {
            if (characteristic) {
                EndState.Member m = member(bean, member, true, null);
                predicateOf(member, m != null).mainClause(b, new On(label));
                if (m == null) return;
                // the group wrote nothing here, so a characteristic that holds came from the input
                if (given(m) == null)
                    throw new IllegalStateException("het kenmerk '" + member + "' van " + label + " staat aan het"
                            + " fixpunt zonder invoer, en de groep die het schrijft besliste niets");
                b.text(" uit de invoer");
                return;
            }
            attributeStanding(b, bean, label, type, member);
        }

        private Predicate predicateOf(String member, boolean present) {
            // het IR draagt de doeltekst van een default niet, dus ook niet het lidwoord van een bezittelijk kenmerk
            Predicate p = predicate(type, member, present, null);
            if (p == null)
                throw new IllegalStateException("een defaults-groep beslist over '" + member + "', en " + type.name
                        + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
            return p;
        }

        /** Waarom de beslisser besliste: haar voorwaarde met de waarden van de operanden, of een voorwaarde per periode
         *  met de perioden waarin zij geldt ({@link #heldInPeriods}). Bij een attribuut staat daarvoor hoe zij haar
         *  waarde berekende ({@link #computation}), na " (onvoorwaardelijk)" waar zij geen voorwaarde heeft, zoals in de
         *  zin van een gewone regel. */
        private void reason(Sentence.Builder b, Variant v, boolean characteristic) {
            Condition c = conditionOf(v);
            if (c == null && isInitialisation(v)) {
                computation(b, v, characteristic);
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                if (characteristic) b.text("het kenmerk");
                else {
                    b.text("de ");
                    attributeName(b, type, targetOf(v), new On(label));
                }
                b.text(emptyBefore(bean));
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
            if (!heldInPeriods(b, c, read)) verbaliseCondition(b, c, read, subjectOf(v), ucharText(v));
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
            EndState.Member m = member(bean, member, false, null);
            if (m == null) {
                b.text("gaf geen waarde");
                return;
            }
            didNotHoldBecause(b).text(" de ");
            attributeName(b, type, member, new On(label));
            b.text(m.derived() ? " had al een waarde" : " had een invoerwaarde");
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

        /** De varianten van een keten aan de wortel van een stapel. Vervangt de eigen basis van een subtype een geërfde
         *  vorm-a-basis, dan blijven diens uitzonderingsgroepen als groep-default boven de eigen basis staan, zonder
         *  eigen voorwaarde en gevolg (ch. 7.10, blok 3), en de motor noemt die groep-default met de pointer van de
         *  vervangen basis. Een basis staat in haar keten anders alleen boven initialisaties, dus een basis met een
         *  andere variant dan een initialisatie onder zich is zo'n groep-default. */
        private List<Variant> rootChain(List<String> chain) {
            List<Variant> out = new ArrayList<>();
            for (String pointer : chain) out.add(variant(pointer));
            for (int i = 0; i < out.size(); i++) {
                Variant v = out.get(i);
                if (v.rule() == null && v.d().exceptions() != null && v.d() == baseOf(v.group())
                        && out.subList(i + 1, out.size()).stream().anyMatch(x -> !isInitialisation(x)))
                    out.set(i, v.asReplaced());
            }
            return out;
        }

        /** De basis van groep {@code g} in het document (ch. 7.10): de laatste default die geen initialisatie is, als
         *  hij zelf iets beslist; een ongeordende groep heeft er geen. */
        private Default baseOf(PrioritizedDefaults g) {
            if (g.unordered()) return null;
            Default last = null;
            for (Default x : g.defaults()) if (!x.initialisation()) last = x;
            return last != null && last.hasOwnContent() ? last : null;
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

    /** Een schrijver van een attribuut op één object: een versie van een gewone regel, haar doel, de cel van het doel
     *  ({@code labels}, zoals uchar een cel sleutelt, {@link FactModelGenerator#cellKey}; null voor een attribuut zonder
     *  dimensies), haar pointer in het document en haar lezing op het object. */
    private record Writer(ResolvedRule rule, Expr.AttributeReference target, List<String> labels, String pointer,
                          ConditionReading reading) {}

    /** Een verdeling als schrijver van lid {@code member} van één object: haar onverdeelde rest op haar bron, of het
     *  aandeel van een ontvanger; {@code source} het object waarvan zij het bedrag verdeelde en waarop haar voorwaarde
     *  gelezen wordt, {@code pointer} haar versie in het document. */
    private record Allocated(ResolvedAllocation allocation, String pointer, Object source, String member) {}

    /**
     * De zinnen over de attributen die de gewone regels en de verdelingen van het document op één object schrijven: een
     * gelijkstelling, een berekening en een initialisatie, op het type van het object of een supertype ervan (IR-SPEC
     * ch. 2.2), en het aandeel of de rest van een verdeling ({@link #allocations}). Een
     * regel die ch. 7.10 in een stapel van het object opnam, telt niet mee: zij staat in de zin van haar stapel (haar
     * pointer staat in {@link DefaultsDecision#rootChains}). Een regel op een supertype die de motor op het object niet
     * las, terwijl het type van het object een stapel op haar doel heeft, telt ook niet mee: die stapel beslist het doel
     * op dat type (ch. 7.10), en de regel loopt er niet. Per attribuut één zin over al zijn schrijvers, in de volgorde
     * van het document, in een van vier vormen:
     *
     * <ul>
     *   <li>een regel gold en gaf een waarde: "heeft als X W volgens de regel 'R'", haar berekening en waarom zij gold;</li>
     *   <li>een regel gold en gaf geen waarde (RUNTIME-SEMANTICS ch. 1.2g): wat het doel houdt, "want de regel 'R' gaf
     *       geen waarde", haar berekening en waarom zij gold; houdt het doel een invoerwaarde, dan "gaf geen waarde en
     *       besliste niets", want een lege waarde beslist niets (ch. 3.11);</li>
     *   <li>geen regel gold en het doel is leeg: "heeft geen X, want geen regel gold";</li>
     *   <li>geen regel gold en het doel houdt een waarde: "houdt als X de invoerwaarde W, want geen regel
     *       gold", of "de afgeleide waarde W" waar iets anders dan de regels van de zin haar afleidde.</li>
     * </ul>
     *
     * <p>Daarna elke andere schrijver als groep {@link Part.Kind#VARIANT}, met de reden dat zij niet schreef: haar
     * voorwaarde gold niet (het lid waar de motor stopte), zij valt buiten haar geldigheid, zij gold op geen dag (haar
     * voorwaarde per periode, met de perioden die de motor las: geen), zij is een initialisatie die alleen gold op
     * dagen die al een waarde hadden (haar voorwaarde met die perioden), zij gold en gaf geen waarde en besliste dus
     * niets naast de regel die schreef, of zij is een initialisatie die niet aan de beurt was ("verdrongen"). Dan de
     * grondslag van de regel die gold.
     *
     * <p>De berekening ({@link Part.Kind#COMPUTATION}) is de regeltekst van de waarde, met elke knoop die iets leest of
     * samenvat ({@link #OPERAND_KINDS}) met de waarde die de motor er las, elke brontekst één keer; leest de waarde één
     * declaratie of vouwt zij over een verzameling, dan is die knoop de regeltekst zelf, met zijn waarde, en de
     * regeltekst van een {@code Let} is die van haar lichaam, want haar brontekst is de definitie van haar binder; een
     * binder staat er met de tekst van zijn definitie. Een vouw over een verzameling noemt haar leden met wat elk
     * bijdroeg ({@link Part.Kind#MEMBERS}; bij een totaal over de tijdlijnen van de leden ook de tijdlijn die elk lid
     * aan het fixpunt houdt), en haar filter, als dat één kenmerkcheck of één lezing is, als regeltekst met
     * de sleutels van die declaratie. Na de waarden de waarde vóór elke afronding en vóór elke begrenzing. Een knoop die
     * de motor niet bereikte (de tak van een keuze) staat er zonder waarde. Elke waarde komt uit de lezing van de regel:
     * de knopen van haar waarde volgen in de nummering op die van haar voorwaarde (ch. 5.12), en een regel zonder
     * voorwaarde heeft alleen die van haar waarde. Wat het doel houdt waar geen regel schreef, staat in uchars lezing van het
     * fixpunt: de invoerwaarde, of de afgeleide waarde waar iets anders haar afleidde.
     *
     * <p>Een regel op een tijdlijn-doel schreef de perioden die zij besliste, met hun waarde ({@code timeline: true} op
     * de waarde), en haar berekening met per periode die zij besliste haar operanden ({@link #timelineComputation}); de
     * zin noemt ook de perioden waarin de invoer bleef staan ({@link #timelineSentence}).
     *
     * <p>Een gedimensioneerd attribuut wordt per cel geschreven (uchars WF-125), dus elke cel die een regel schrijft
     * krijgt haar eigen zin, over de schrijvers van die cel, met de cel als naam ({@link #cellName}).
     */
    private final class ComputationExplanation {
        private final String label;
        private final Object bean;
        private final ObjectType type;
        /** De pointers van de varianten van de stapels van dit object. */
        private final Set<String> inStacks = new HashSet<>();
        /** De doelen van de stapels van dit object, in kleine letters. */
        private final Set<String> stackTargets = new HashSet<>();
        /** Per tijdsafhankelijk attribuut dat gewone regels op dit object schrijven, wie per periode besliste. */
        private final Map<String, PeriodDecisions> byRules = new HashMap<>();

        ComputationExplanation(String label, Object bean, ObjectType type, List<DefaultsDecision> decisions,
                               List<PeriodDecisions> periodDecisions) {
            this.label = label;
            this.bean = bean;
            this.type = type;
            for (DefaultsDecision d : decisions) {
                if (d.object() != bean) continue;
                for (List<String> chain : d.rootChains()) inStacks.addAll(chain);
                stackTargets.add(d.member().toLowerCase(Locale.ROOT));
            }
            for (PeriodDecisions p : periodDecisions) {
                if (p.object() != bean) continue;
                if (p.rootChains().isEmpty()) {
                    if (byRules.put(p.member(), p) != null)
                        throw new IllegalStateException("de motor gaf over '" + p.member() + "' van " + label
                                + " twee records van zijn regels per periode, waar één doel er één heeft");
                    continue;
                }
                for (List<String> chain : p.rootChains()) inStacks.addAll(chain);
                stackTargets.add(p.member().toLowerCase(Locale.ROOT));
            }
        }

        void sentences(List<Sentence> sink) {
            Map<String, List<Writer>> perTarget = new LinkedHashMap<>();
            for (ResolvedRule r : document.rules()) {
                if (r.kind() == ResolvedRule.Kind.KENMERKTOEKENNING) continue;
                Expr.AttributeReference ref;
                List<String> labels = null;
                if (r.target() instanceof Expr.AttributeReference a) ref = a;
                else if (r.target() instanceof Expr.DimensionCellRead c) {
                    ref = c.attribute();
                    labels = cellKey(ref, c.labels());
                } else continue;
                if (ref.objectType() == null) continue;
                if (!type.isA(ref.objectType())) continue;
                String pointer = document.positions().versionOf(r);
                if (pointer == null)
                    throw new IllegalStateException("de regel '" + r.name() + "' staat niet als versie in het document"
                            + " van de uitleg: haar lezing heeft geen vorm");
                if (inStacks.contains(pointer)) continue;
                String target = ref.attribute().toLowerCase(Locale.ROOT);
                if (stackTargets.contains(target) && !readings.getOrDefault(pointer, Map.of()).containsKey(bean))
                    continue;
                perTarget.computeIfAbsent(labels == null ? target : target + labels, k -> new ArrayList<>())
                        .add(new Writer(r, ref, labels, pointer, reading(pointer, bean, label)));
            }
            Map<String, List<Allocated>> allocated = allocations();
            for (String target : allocated.keySet()) perTarget.putIfAbsent(target, new ArrayList<>());
            for (Map.Entry<String, List<Writer>> e : perTarget.entrySet()) {
                List<Writer> writers = e.getValue();
                List<Allocated> allocations = allocated.getOrDefault(e.getKey(), List.of());
                String member = writers.isEmpty() ? allocations.get(0).member() : writers.get(0).target().attribute();
                if (type.granularityOf(member) == null) {
                    sink.add(sentence(member, writers.isEmpty() ? null : writers.get(0).labels(), writers,
                            allocations));
                    continue;
                }
                if (!allocations.isEmpty())
                    throw new IllegalStateException("de verdeling '" + allocations.get(0).allocation().name()
                            + "' schrijft het tijdsafhankelijke attribuut '" + member + "', en de uitleg kent een"
                            + " verdeling alleen op een attribuut met één waarde");
                sink.add(timelineSentence(writers));
            }
        }

        /** De verdelingen van het document die een attribuut van dit object schrijven, per doel in kleine letters: op een
         *  ontvanger het aandeel, met de bron waarmee de koppeling van de verdeling het verbindt; op een bron de
         *  onverdeelde rest. Een ontvanger zonder bron krijgt van een verdeling niets, en een doel dat een stapel
         *  van dit object beslist, staat in de zin van die stapel. */
        private Map<String, List<Allocated>> allocations() {
            Map<String, List<Allocated>> out = new LinkedHashMap<>();
            for (ResolvedAllocation a : model.allocations) {
                String pointer = document.positions().versionOf(a);
                if (pointer == null)
                    throw new IllegalStateException("de verdeling '" + a.name() + "' staat niet als versie in het"
                            + " document van de uitleg");
                if (a.remainderAttribute() != null && type.isA(a.sourceType()))
                    add(out, new Allocated(a, pointer, bean, a.remainderAttribute()));
                if (!type.isA(a.targetType())) continue;
                List<Object> sources = joined(a, bean, false);
                if (sources.size() > 1)
                    throw new IllegalStateException(label + " ontvangt van de verdeling '" + a.name() + "' uit "
                            + sources.size() + " bronnen, en de uitleg kent een aandeel uit één bron");
                if (sources.size() == 1) add(out, new Allocated(a, pointer, sources.get(0), a.targetAttribute()));
            }
            return out;
        }

        private void add(Map<String, List<Allocated>> out, Allocated a) {
            String target = a.member().toLowerCase(Locale.ROOT);
            if (stackTargets.contains(target)) return;
            out.computeIfAbsent(target, k -> new ArrayList<>()).add(a);
        }

        /** De zin over attribuut {@code member} zonder tijdlijn, of zijn cel {@code cell} (IR-SPEC §3.2.1: een
         *  gedimensioneerd attribuut heeft geen tijdlijn), over de gewone regels {@code writers} en de verdelingen
         *  {@code allocations} die het schrijven: schrijvers van één rang (uchars RUNTIME-SEMANTICS ch. 7.8). */
        private Sentence sentence(String member, List<String> cell, List<Writer> writers, List<Allocated> allocations) {
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
                            + "' gelden op " + label + " met verschillende waarden voor '" + member + "'"
                            + (cell == null ? "" : " in de cel " + cell) + " (" + written + " en " + v + "), en de"
                            + " uitleg volgt een sessie zonder fout");
                if (wrote.isEmpty()) written = v;
                wrote.add(w);
            }
            if (cell != null && !allocations.isEmpty())
                throw new IllegalStateException("de verdeling '" + allocations.get(0).allocation().name() + "' schrijft"
                        + " de cel " + cell + " van '" + member + "', en een verdeling schrijft geen cel");
            EndState.Member standing = cell == null ? member(bean, member, false, null) : null;
            List<Allocated> allocationWrote = new ArrayList<>(), allocationEmpty = new ArrayList<>(),
                    allocationNot = new ArrayList<>();
            for (Allocated a : allocations) {
                ConditionReading.Status status = status(a);
                if (status == ConditionReading.Status.FAILS || status == ConditionReading.Status.OUTSIDE_VALIDITY)
                    allocationNot.add(a);
                else (wrote(a, standing) ? allocationWrote : allocationEmpty).add(a);
            }
            if (allocationWrote.size() > 1)
                throw new IllegalStateException("de verdelingen '" + allocationWrote.get(0).allocation().name() + "' en '"
                        + allocationWrote.get(1).allocation().name() + "' schrijven beide '" + member + "' van " + label
                        + ", en de uitleg volgt een sessie zonder fout");
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            List<Writer> deciders = wrote.isEmpty() && allocationWrote.isEmpty() ? empty : wrote;
            if (!wrote.isEmpty() || !allocationWrote.isEmpty()) {
                b.text("heeft als ");
                targetName(b, member, cell);
                b.text(" ");
                if (!wrote.isEmpty()) value(b, written(wrote.get(0), member), false, writtenValue(wrote.get(0)));
                else value(b, render(standing.value(), type.attribute(member)), false, standing.value());
                for (int i = 0; i < wrote.size(); i++) {
                    b.text(i == 0 ? " volgens " : ", en volgens ");
                    decider(b, wrote.get(i), member, null, false, false);
                }
                for (Allocated a : allocationWrote) {
                    b.text(wrote.isEmpty() ? " volgens " : ", en volgens ");
                    allocationDecider(b, a, false, false);
                }
                allocationNot.addAll(allocationEmpty);
            } else {
                boolean stands = cell == null ? attributeStanding(b, bean, label, type, member)
                        : cellStanding(b, member, cell);
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" ");
                if (empty.isEmpty() && allocationEmpty.isEmpty()) b.text("geen regel gold");
                int i = 0;
                for (Writer w : empty) {
                    if (i++ > 0) b.text(", en ");
                    decider(b, w, member, null, true, stands);
                }
                for (Allocated a : allocationEmpty) {
                    if (i++ > 0) b.text(", en ");
                    allocationDecider(b, a, true, stands);
                }
            }
            for (Writer w : writers) if (!deciders.contains(w)) variant(b, w);
            for (Allocated a : allocationNot) allocationVariant(b, a);
            if (!deciders.isEmpty())
                ground(b, sourceAnnotations(deciders.get(0).rule()), ruleDecider(deciders.get(0).rule()));
            return b.build();
        }

        /** Hoe verdeling {@code a} uitkwam op haar bron: de status van haar lezing daar, of null zonder voorwaarde,
         *  want een verdeling zonder voorwaarde heeft geen lezing (uchars RUNTIME-SEMANTICS ch. 5.12). */
        private ConditionReading.Status status(Allocated a) {
            if (a.allocation().condition() == null) return null;
            ConditionReading.Status status = reading(a.pointer(), a.source(), nameOf(a.source())).status();
            if (status == ConditionReading.Status.FAULTS) throw faulted(a.pointer(), nameOf(a.source()));
            if (status == ConditionReading.Status.DISPLACED)
                throw new IllegalStateException("de motor noemt de verdeling '" + a.allocation().name() + "' op "
                        + nameOf(a.source()) + " niet aan de beurt, en een verdeling wacht niet op haar beurt");
            return status;
        }

        /** Of verdeling {@code a}, die op haar bron gold of geen voorwaarde heeft, het lid van de zin schreef: het lid
         *  {@code standing} houdt een afgeleide waarde en haar bron een bedrag om te verdelen. Een gewone regel die
         *  hetzelfde lid schreef, schreef dezelfde waarde, want twee waarden zijn de fout van ch. 5.7. */
        private boolean wrote(Allocated a, EndState.Member standing) {
            return standing != null && standing.derived()
                    && member(a.source(), a.allocation().sourceAttribute(), false, null) != null;
        }

        /** Verdeling {@code a} als schrijver: haar naam, met {@code gaveNothing} dat zij geen waarde gaf en met
         *  {@code inputStands} dat zij daarom niets besliste, haar berekening ({@link #allocationComputation}) en waarom
         *  zij gold, gelezen op haar bron; zoals {@link #decider} een gewone regel noemt. */
        private void allocationDecider(Sentence.Builder b, Allocated a, boolean gaveNothing, boolean inputStands) {
            ResolvedAllocation v = a.allocation();
            b.text("de regel '").leaf(Part.Kind.RULE, RegelSpraakModel.formName(v.name())).text("'");
            if (gaveNothing) b.text(" gaf geen waarde");
            if (v.condition() == null) b.text(" (onvoorwaardelijk)");
            if (gaveNothing && inputStands) b.text(" en besliste niets");
            allocationComputation(b, a);
            if (v.condition() == null) return;
            b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
            verbaliseCondition(b, v.condition(), read(a.pointer(), a.source(), nameOf(a.source()), v.condition()),
                    v.sourceType(), false);
        }

        /** Verdeling {@code a} als schrijver die het lid van de zin niet schreef, als groep met de "; " ervoor: haar
         *  voorwaarde gold niet op haar bron, zij valt buiten haar geldigheid, of zij gaf hier geen waarde. */
        private void allocationVariant(Sentence.Builder b, Allocated a) {
            ResolvedAllocation v = a.allocation();
            b.open(Part.Kind.VARIANT, Part.meta("decided", false)).text("; de regel '")
                    .leaf(Part.Kind.RULE, RegelSpraakModel.formName(v.name())).text("' ");
            ConditionReading.Status status = status(a);
            if (status == ConditionReading.Status.FAILS) {
                didNotHoldBecause(b).text(" ");
                firstFailing(b, v.condition(), read(a.pointer(), a.source(), nameOf(a.source()), v.condition()),
                        v.sourceType(), false);
            } else if (status == ConditionReading.Status.OUTSIDE_VALIDITY) {
                didNotHoldBecause(b).text(" zij valt op de rekendatum buiten haar geldigheid");
            } else {
                b.text("gaf geen waarde en besliste niets");
            }
            b.close(Part.Kind.VARIANT);
        }

        /**
         * Hoe verdeling {@code a} verdeelde, als groep {@link Part.Kind#COMPUTATION} met de dubbele punt ervoor: haar
         * bron met het bedrag dat zij verdeelde ("W verdeelde zijn meldgeld (10)"), de methode en de afronding zoals
         * RegelSpraak ze schrijft (§9.7), en als groep {@link Part.Kind#MEMBERS} de ontvangers die de koppeling van de
         * verdeling met de bron verbindt, elk met het aandeel dat het houdt. Het IR draagt de tekst van een verdeling
         * niet, dus komen de woorden uit haar velden.
         */
        private void allocationComputation(Sentence.Builder b, Allocated a) {
            ResolvedAllocation v = a.allocation();
            String source = nameOf(a.source());
            b.open(Part.Kind.COMPUTATION, Map.of()).text(": ").leaf(Part.Kind.SUBJECT, source).text(" verdeelde zijn ");
            attributeName(b, v.sourceType(), v.sourceAttribute(), new On(source));
            EndState.Member amount = member(a.source(), v.sourceAttribute(), false, null);
            Object total = amount == null ? null : amount.value();
            reading(b, render(total, v.sourceType().attribute(v.sourceAttribute())), total == null, total);
            switch (v.method()) {
                case GELIJKE_DELEN -> b.text(" in gelijke delen");
                case NAAR_RATO -> {
                    b.text(" naar rato van hun ");
                    attributeName(b, v.targetType(), v.ratioAttribute());
                }
                case OP_VOLGORDE -> {
                    b.text(" op volgorde van " + (v.descending() ? "afnemende " : "toenemende "));
                    attributeName(b, v.targetType(), v.orderingAttribute());
                    b.text(", bij een even groot criterium ");
                    if (v.ratioAttribute() == null) b.text("in gelijke delen");
                    else {
                        b.text("naar rato van hun ");
                        attributeName(b, v.targetType(), v.ratioAttribute());
                    }
                }
                case COMPLEX -> throw new IllegalStateException("de verdeling '" + v.name() + "' heeft een methode die"
                        + " uchar niet draagt");
            }
            if (v.maximumAttribute() != null) {
                b.text(", met een maximum van hun ");
                attributeName(b, v.targetType(), v.maximumAttribute());
            }
            if (v.roundingDecimals() != null)
                b.text(", afgerond op " + v.roundingDecimals() + " decimalen naar beneden");
            List<Object> recipients = joined(v, a.source(), true);
            List<String> names = new ArrayList<>();
            Map<String, Object> shares = new HashMap<>();
            for (Object r : recipients) {
                String name = nameOf(r);
                names.add(name);
                EndState.Member share = member(r, v.targetAttribute(), false, null);
                shares.put(name, share == null ? null : share.value());
            }
            names.sort(String::compareTo);
            int m = names.size();
            b.open(Part.Kind.MEMBERS, Part.meta("members", m)).text(", over ");
            if (m == 0) b.text("geen enkel lid");
            int shown = Math.min(m, 5);
            for (int i = 0; i < shown; i++) {
                if (i > 0) b.text(i == shown - 1 && m <= 5 ? " en " : ", ");
                Object share = shares.get(names.get(i));
                b.leaf(Part.Kind.SUBJECT, names.get(i));
                reading(b, render(share, v.targetType().attribute(v.targetAttribute())), share == null, share);
            }
            if (m > 5) b.text(" en nog " + (m - 5));
            if (m > 0) b.text(m == 1 ? ", het enige lid" : " (" + m + " leden)");
            b.close(Part.Kind.MEMBERS);
            b.close(Part.Kind.COMPUTATION);
        }

        /**
         * De zin over een tijdsafhankelijk attribuut dat de gewone regels schrijven (RUNTIME-SEMANTICS ch. 3.11): elke
         * schrijver beslist de dagen waarop zijn tijdlijn een waarde leest, en op de andere dagen blijft de invoer
         * staan. Wie welke dagen besliste, komt van de motor ({@link DroolsBackend#periodDecisionsOf}): per regel die
         * besliste de perioden met hun waarde, "volgens de regel '…'" en waarom zij gold, daarna de perioden waarin de
         * invoer bleef staan. Besliste geen regel een dag, dan wat het doel houdt, zoals bij een attribuut zonder
         * tijdlijn. Waarom een regel die gold geen dag besliste, zegt {@link #undecided}: zij gold op geen dag, dan gold
         * zij niet; een initialisatie gold alleen op dagen die al een waarde hadden; of zij gaf geen waarde. Twee regels
         * die een dag verschillend beslissen geven EF-016 (ch. 5.7), een sessie zonder antwoord.
         */
        private Sentence timelineSentence(List<Writer> writers) {
            String member = writers.get(0).target().attribute();
            PeriodDecisions p = byRules.get(member);
            if (p == null)
                throw new IllegalStateException("de motor gaf geen beslissingen per periode over '" + member + "' van "
                        + label + ", een tijdsafhankelijk attribuut dat de regels van het document schrijven");
            Datatype datatype = type.attribute(member);
            Map<String, List<String>> periods = new LinkedHashMap<>();
            Map<String, List<Period>> decided = new HashMap<>();
            List<String> input = new ArrayList<>();
            for (PeriodDecisions.Span s : p.spans()) {
                for (DefaultsDecision.Decider r : s.rules()) {
                    periods.computeIfAbsent(r.pointer(), k -> new ArrayList<>())
                            .add(inPeriod(render(r.decision(), datatype), s.from(), s.to()));
                    decided.computeIfAbsent(r.pointer(), k -> new ArrayList<>()).add(new Period(s.from(), s.to(),
                            (Object) null));
                }
                if (s.input() != null) input.add(inPeriod(render(s.input(), datatype), s.from(), s.to()));
            }
            List<Writer> wrote = new ArrayList<>(), empty = new ArrayList<>();
            for (Writer w : writers) {
                ConditionReading.Status status = w.reading().status();
                if (status == ConditionReading.Status.FAULTS) throw faulted(w.pointer(), label);
                if (periods.containsKey(w.pointer())) {
                    if (status != ConditionReading.Status.HOLDS)
                        throw new IllegalStateException("de motor noemt de regel '" + formName(w) + "' beslisser van '"
                                + member + "' van " + label + " op " + periods.get(w.pointer()) + ", en haar lezing is "
                                + status);
                    wrote.add(w);
                } else if (status == ConditionReading.Status.HOLDS && undecided(w) != Undecided.HELD_ON_NO_DAY)
                    empty.add(w);
            }
            if (wrote.size() != periods.size())
                throw new IllegalStateException("de motor noemt als beslissers van '" + member + "' van " + label + " "
                        + periods.keySet() + ", en het document schrijft het doel met "
                        + writers.stream().map(Writer::pointer).toList());
            Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
            List<Writer> deciders = wrote.isEmpty() ? empty : wrote;
            if (!wrote.isEmpty()) {
                b.text("heeft als ");
                targetName(b, member, null);
                b.text(" ");
                for (int i = 0; i < wrote.size(); i++) {
                    if (i > 0) b.text(", en ");
                    b.leaf(Part.Kind.VALUE, joined(periods.get(wrote.get(i).pointer())), Part.meta("timeline", true));
                    b.text(" volgens ");
                    decider(b, wrote.get(i), member, decided.get(wrote.get(i).pointer()), false, false);
                }
                if (!input.isEmpty())
                    b.text("; " + standingValue(bean) + " ")
                            .leaf(Part.Kind.VALUE, joined(input), Part.meta("timeline", true)).text(" bleef staan");
            } else {
                boolean stands = timelineStanding(b, member);
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "want").text(" ");
                if (empty.isEmpty()) b.text("geen regel gold");
                for (int i = 0; i < empty.size(); i++) {
                    if (i > 0) b.text(", en ");
                    Writer w = empty.get(i);
                    if (undecided(w) == Undecided.HELD_ON_DAYS_WITH_A_VALUE) {
                        b.text("de regel '").leaf(Part.Kind.RULE, formName(w)).text("' ");
                        onlyOnDaysWithAValue(b, w);
                    } else decider(b, w, member, List.of(), true, stands);
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
            String number = nlExact(n.value(), target);
            if (number == null) return render(n.value(), target);
            String unit = n.unit() != null ? n.unit().rendering() : unitWord(target);
            return unit == null ? number : number + " " + unit;
        }

        /** De waarde die regel {@code w} schreef, zoals de motor haar las: die van {@link #written}, als waarde. */
        private Object writtenValue(Writer w) {
            return valueRead(w).of(w.rule().value()).value();
        }

        /** Wat tijdsafhankelijk attribuut {@code member} houdt waar geen regel een dag besliste: leeg, of de waarde
         *  die het aan het fixpunt houdt als haar perioden ({@link #standingWord}). Geeft of er een waarde staat. */
        private boolean timelineStanding(Sentence.Builder b, String member) {
            EndState.Member m = member(bean, member, false, null);
            if (m == null) return attributeStanding(b, bean, label, type, member);
            if (!(m.value() instanceof List<?> segments))
                throw new IllegalStateException("het tijdsafhankelijke attribuut '" + member + "' staat op " + label
                        + " als " + m.value().getClass().getName() + ", niet als lijst segmenten");
            Datatype datatype = type.attribute(member);
            b.text("houdt als ");
            attributeName(b, type, member, new On(label));
            b.text(" " + standingWord(bean, m) + " ").leaf(Part.Kind.VALUE,
                    renderPeriods(Period.list(segments), null, x -> render(x, datatype)), Part.meta("timeline", true));
            return true;
        }

        /** Wat cel {@code cell} van {@code member} houdt waar geen regel een waarde schreef: leeg of de waarde die zij
         *  aan het fixpunt houdt ({@link #standingWord}). Geeft of er een waarde staat. */
        private boolean cellStanding(Sentence.Builder b, String member, List<String> cell) {
            EndState.Member m = member(bean, member, false, cell);
            b.text(m == null ? "heeft geen " : "houdt als ");
            cellName(b, type, member, cell, new On(label));
            if (m != null) {
                b.text(" " + standingWord(bean, m) + " ");
                value(b, render(m.value(), type.attribute(member)), false, m.value());
            }
            return m != null;
        }

        /** Het doel van de zin: attribuut {@code member}, of zijn cel {@code cell}, op het object van de zin. */
        private void targetName(Sentence.Builder b, String member, List<String> cell) {
            if (cell == null) attributeName(b, type, member, new On(label));
            else cellName(b, type, member, cell, new On(label));
        }

        /** Een regel die gold: haar naam, haar berekening en waarom zij gold; met {@code gaveNothing} dat zij geen waarde
         *  gaf, en met {@code inputStands} ook dat zij daarom niets besliste, waar het doel zijn invoerwaarde houdt
         *  (uchars RUNTIME-SEMANTICS ch. 3.11: een lege waarde beslist niets). Een initialisatie zonder voorwaarde gold
         *  omdat het doel in de invoer leeg was ({@link #emptyBefore}), zoals de zin van een initialisatie in een stapel
         *  het zegt; op een tijdlijndoel beslist zij zo de dagen die de regels en de invoer leeg laten (ch. 7.8).
         *  {@code decided} zijn op een tijdlijndoel de perioden die de regel besliste, waarin de zin haar berekening
         *  noemt ({@link #timelineComputation}), en null op een doel zonder tijdlijn. */
        private void decider(Sentence.Builder b, Writer w, String member, List<Period> decided, boolean gaveNothing,
                             boolean inputStands) {
            boolean timeline = decided != null;
            ResolvedRule r = w.rule();
            b.text("de regel '").leaf(Part.Kind.RULE, formName(w)).text("'");
            if (gaveNothing) b.text(" gaf geen waarde");
            Condition c = r.condition();
            boolean initialisation = r.kind() == ResolvedRule.Kind.INITIALISATIE;
            if (c == null && !initialisation) b.text(" (onvoorwaardelijk)");
            if (gaveNothing && inputStands) b.text(" en besliste niets");
            if (timeline) timelineComputation(b, w, decided);
            else computation(b, r.value(), writtenByUchar(r), valueRead(w));
            if (c != null) {
                Read conditionRead = read(w.pointer(), bean, label, c);
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" ");
                if (!(timeline && heldInPeriods(b, c, conditionRead)))
                    verbaliseCondition(b, c, conditionRead, w.target().objectType(), writtenByUchar(r));
            } else if (initialisation) {
                b.text(", ").leaf(Part.Kind.CONNECTIVE, "omdat").text(" de ");
                targetName(b, member, w.labels());
                b.text(emptyBefore(bean));
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
                    case HOLDS -> {
                        switch (undecided(w)) {
                            case HELD_ON_NO_DAY -> {
                                b.leaf(Part.Kind.CONNECTIVE, "gold op geen dag").text(", ")
                                        .leaf(Part.Kind.CONNECTIVE, "want").text(" ");
                                notHolds(b);
                                heldInPeriods(b, c, read(w.pointer(), bean, label, c));
                            }
                            case HELD_ON_DAYS_WITH_A_VALUE -> onlyOnDaysWithAValue(b, w);
                            // a leeg decides nothing (ch. 3.11)
                            case GAVE_NO_VALUE -> b.text("gaf geen waarde en besliste niets");
                        }
                    }
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

        /** Waarom schrijver {@code w}, een regel of een initialisatie met de lezing {@code HOLDS}, geen dag besliste. */
        private enum Undecided {
            /** Zijn voorwaarde per periode leest geen periode: hij gold nergens. */
            HELD_ON_NO_DAY,
            /** Een initialisatie wier voorwaarde alleen geldt op dagen die een andere schrijver of de invoer vulde. */
            HELD_ON_DAYS_WITH_A_VALUE,
            /** Hij gold op een dag die hij had kunnen beslissen, en zijn waarde was daar leeg. */
            GAVE_NO_VALUE
        }

        /**
         * Waarom schrijver {@code w} met de lezing {@code HOLDS} geen dag besliste: de ene plaats die het beslist, voor
         * elke schrijver die de zinnen over een attribuut noemen. Uit de lezing (ch. 5.12) de perioden van zijn
         * voorwaarde, een voorwaarde per periode van een tijdlijndoel (uchars RUNTIME-SEMANTICS ch. 3.10): geen periode,
         * dan gold hij nergens. Een initialisatie vult alleen de dagen die de andere schrijvers en de invoer leeg laten
         * (ch. 7.8); liggen alle dagen van haar perioden in spans van {@link DroolsBackend#periodDecisionsOf} met een
         * regel of een invoer, dan gold zij alleen op dagen die al een waarde hadden. Anders, ook voor elke andere
         * regel, die voor geen schrijver of invoer wijkt, en voor een voorwaarde die een poort is: zijn waarde was leeg
         * waar hij gold.
         */
        private Undecided undecided(Writer w) {
            Condition c = w.rule().condition();
            if (c == null || !(read(w.pointer(), bean, label, c).of(c).value() instanceof List<?> periods))
                return Undecided.GAVE_NO_VALUE;
            if (periods.isEmpty()) return Undecided.HELD_ON_NO_DAY;
            if (w.rule().kind() != ResolvedRule.Kind.INITIALISATIE) return Undecided.GAVE_NO_VALUE;
            String member = w.target().attribute();
            PeriodDecisions p = byRules.get(member);
            if (p == null)
                throw new IllegalStateException("de motor gaf geen beslissingen per periode over '" + member + "' van "
                        + label + ", en de initialisatie '" + formName(w) + "' leest haar voorwaarde per periode");
            for (Object o : periods) {
                if (!(o instanceof Period q))
                    throw new IllegalStateException("de motor las de voorwaarde van '" + formName(w) + "' op " + label
                            + " als een periode van de vorm " + o.getClass().getName() + ", die de uitleg niet kent");
                for (PeriodDecisions.Span s : p.spans())
                    if (s.from().isBefore(q.to()) && q.from().isBefore(s.to()) && s.rules().isEmpty()
                            && s.input() == null)
                        return Undecided.GAVE_NO_VALUE;
            }
            return Undecided.HELD_ON_DAYS_WITH_A_VALUE;
        }

        /** "gold alleen op dagen die al een waarde hadden: " en de voorwaarde per periode van {@code w}, met de perioden
         *  die de motor las. */
        private void onlyOnDaysWithAValue(Sentence.Builder b, Writer w) {
            Condition c = w.rule().condition();
            b.leaf(Part.Kind.CONNECTIVE, "gold alleen op dagen die al een waarde hadden").text(": ");
            heldInPeriods(b, c, read(w.pointer(), bean, label, c));
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
            // een lezing, parameter of vouw over een verzameling of over cellen is de regeltekst zelf, met haar waarde;
            // een ingebouwde functie of een aggregatie over haar argumenten noemt wat zij las
            if (single(root, read)) {
                operand(b, root, read, ucharText, definitions);
            } else {
                // de brontekst van een Let is de definitie van haar binder; de berekening is haar lichaam
                Expr body = root;
                while (body instanceof Expr.Let l) body = l.body();
                describe(b, body, ucharText);
            }
            List<Expr> operands = operands(root, nodes, read);
            for (int i = 0; i < operands.size(); i++) {
                b.text(i == 0 ? ", met " : i == operands.size() - 1 ? " en " : ", ");
                operand(b, operands.get(i), read, ucharText, definitions);
            }
            beforeRounding(b, nodes, read);
            b.close(Part.Kind.COMPUTATION);
        }

        /** Of de waarde {@code root} één declaratie leest of over een verzameling of cellen vouwt: dan is die knoop
         *  de regeltekst zelf, met zijn waarde. */
        private boolean single(Expr root, Read read) {
            return READ_KINDS.contains(kindOf(root)) || overASet(root, read) || overCells(root);
        }

        /** De knopen onder {@code root} die de berekening als operand noemt ({@link #OPERAND_KINDS}), met een eigen
         *  lezing, elke brontekst één keer, in de volgorde van het document. */
        private List<Expr> operands(Expr root, List<Object> nodes, Read read) {
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
            return operands;
        }

        /**
         * Hoe een regel op een tijdlijndoel haar waarde berekende, met de dubbele punt ervoor, per periode die zij
         * besliste ({@code decided}, de perioden van {@link DroolsBackend#periodDecisionsOf}): de regeltekst, dan de
         * operanden die de motor als één waarde las één keer, zoals {@link #computation}, en per periode de operanden
         * die hij als tijdlijn las of niet bereikte, elk met zijn tijdlijn in die periode ("tot 01-04-2024 met zijn
         * strandloon (100 van 01-01-2024 tot 01-04-2024) en zijn stormtoeslag (leeg); van …"). De lezing van de regel
         * draagt elke knoop onder de wortel als de tijdlijn die hij las of berekende (RUNTIME-SEMANTICS ch. 5.12); de
         * zin snijdt die alleen op de perioden. Zonder besliste periode, waar de waarde (of het lichaam van haar
         * {@code Let}) één declaratie of binder leest of over een verzameling of cellen vouwt, of zonder operand die
         * een tijdlijn las, is het de berekening van {@link #computation}.
         */
        private void timelineComputation(Sentence.Builder b, Writer w, List<Period> decided) {
            Expr root = w.rule().value();
            Read read = valueRead(w);
            boolean ucharText = writtenByUchar(w.rule());
            List<Object> nodes = document.positions().subtree(root);
            List<Expr> operands = operands(root, nodes, read);
            // een operand die de motor niet bereikte, heeft in geen periode een waarde en staat bij die per periode
            List<Expr> once = new ArrayList<>(), perPeriod = new ArrayList<>();
            for (Expr e : operands)
                (read.reached(e) && !(read.of(e).value() instanceof List<?>) ? once : perPeriod).add(e);
            // de brontekst van een Let is de definitie van haar binder; de berekening is haar lichaam, en leest dat
            // alleen de binder, dan heeft elke knoop de tijdlijn die de regel schrijft (ch. 5.12)
            Expr body = root;
            while (body instanceof Expr.Let l) body = l.body();
            if (decided.isEmpty() || single(body, read) || perPeriod.stream().noneMatch(read::reached)) {
                computation(b, root, ucharText, read);
                return;
            }
            Map<Object, String> definitions = definitions(nodes);
            b.open(Part.Kind.COMPUTATION, Map.of()).text(": ");
            describe(b, body, ucharText);
            for (int i = 0; i < once.size(); i++) {
                b.text(i == 0 ? ", met " : i == once.size() - 1 ? " en " : ", ");
                operand(b, once.get(i), read, ucharText, definitions);
            }
            for (int p = 0; p < decided.size(); p++) {
                Period in = decided.get(p);
                b.text(p == 0 && once.isEmpty() ? ", " : "; ").text(periodText(in.from(), in.to()));
                for (int i = 0; i < perPeriod.size(); i++) {
                    b.text(i == 0 ? " met " : i == perPeriod.size() - 1 ? " en " : ", ");
                    operandIn(b, perPeriod.get(i), read, ucharText, definitions, in);
                }
            }
            beforeRounding(b, nodes, read);
            b.close(Part.Kind.COMPUTATION);
        }

        /** Een operand die de motor als tijdlijn las, met die tijdlijn in periode {@code in} ({@link #within}): leeg
         *  waar zij daar geen waarde heeft; een vouw over een verzameling met haar leden in die periode. Een operand
         *  die de motor niet bereikte, staat er zonder waarde. */
        private void operandIn(Sentence.Builder b, Expr e, Read read, boolean ucharText,
                               Map<Object, String> definitions, Period in) {
            Expr.FunctionCall fold = overASet(e, read) ? (Expr.FunctionCall) e : null;
            if (fold != null) foldText(b, fold);
            else if (definitions.containsKey(e)) b.leaf(Part.Kind.QUOTE, definitions.get(e));
            else describe(b, e, ucharText, readOn(e, read));
            if (!read.reached(e)) return;
            ConditionReading.NodeReading n = read.of(e);
            Object v = within((List<?>) n.value(), in);
            reading(b, renderRead(e, v, n.unit()), v == null, v);
            if (fold != null) contributors(b, fold, n, in);
        }

        /** Na de waarden de waarde vóór elke afronding en vóór elke begrenzing die de motor bereikte. */
        private void beforeRounding(Sentence.Builder b, List<Object> nodes, Read read) {
            for (Object n : nodes) {
                if (!read.has(n) || !read.reached(n)) continue;
                if (n instanceof Expr.Rounding r) {
                    b.text("; vóór de afronding ");
                    value(b, unrounded(r.base(), read), read.of(r.base()).value() == null, read.of(r.base()).value());
                } else if (n instanceof Expr.Clamp c) {
                    b.text("; vóór de begrenzing ");
                    value(b, unrounded(c.base(), read), read.of(c.base()).value() == null, read.of(c.base()).value());
                }
            }
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
            String number = nlExact(n.value() instanceof BigDecimal d ? d.stripTrailingZeros() : n.value(), null);
            if (number == null) return renderOperand(e, read);
            String unit = n.unit() != null ? n.unit().rendering()
                    : (e.type() instanceof Datatype.Percentage || typeOf(e) instanceof Datatype.Percentage) ? "%" : null;
            return unit == null ? number : number + " " + unit;
        }

        /** Een knoop die iets leest of samenvat, met de waarde die de motor er las; een vouw over een verzameling met
         *  haar leden, een binder met de tekst van haar definitie ({@code definitions}). Zonder waarde waar de motor
         *  de knoop niet bereikte. */
        private void operand(Sentence.Builder b, Expr e, Read read, boolean ucharText, Map<Object, String> definitions) {
            Expr.FunctionCall fold = overASet(e, read) ? (Expr.FunctionCall) e : null;
            if (fold != null) foldText(b, fold);
            else if (definitions.containsKey(e)) b.leaf(Part.Kind.QUOTE, definitions.get(e));
            else describe(b, e, ucharText, readOn(e, read));
            if (!read.reached(e)) return;
            ConditionReading.NodeReading n = read.of(e);
            reading(b, renderOperand(e, read), n.value() == null, n.value());
            if (fold != null) contributors(b, fold, n);
        }

        /** Of {@code e} een vouw over een verzameling is (RUNTIME-SEMANTICS ch. 3.4): een aggregatie met één argument,
         *  de verzameling, zonder dimensieselectie; haar knoop draagt haar bijdragers. Een aggregatie over haar
         *  argumenten ("de laatste van A en B") is er geen, en het eigen totaal van een tijdlijn van het onderwerp
         *  ({@link #ownTotal}) evenmin. */
        private boolean overASet(Expr e, Read read) {
            return e instanceof Expr.FunctionCall f && "Aggregation".equals(kindOf(e)) && f.dimensionSelections() == null
                    && !ownTotal(f) && (f.arguments().size() == 1 || read.has(e) && !read.of(e).contributors().isEmpty());
        }

        /** Of {@code f} het eigen totaal van een tijdlijn is (RUNTIME-SEMANTICS ch. 3.6): het totaal van één attribuut
         *  op een tijdlijn van het onderwerp zelf, zonder rol. Het telt de perioden van die tijdlijn en heeft geen leden;
         *  het attribuut eronder is een eigen operand, met de tijdlijn die de motor las. Een totaal met een bereik
         *  (IR-SPEC ch. 4.3), dat uchars lader naast het model legt ({@code Loaded.roleBindings},
         *  {@code Loaded.sourcePaths}), loopt over de tijdlijnen van zijn leden en is een vouw over een verzameling. */
        private boolean ownTotal(Expr.FunctionCall f) {
            if (f.kind() != Expr.FunctionKind.TOTAAL || f.arguments().size() != 1
                    || document.loaded().roleBindings().containsKey(f) || document.loaded().sourcePaths().containsKey(f)
                    || !(f.arguments().get(0) instanceof Expr.AttributeReference ref)) return false;
            boolean ownMember = !ref.set() && (ref.navigationPath() == null || ref.navigationPath().isEmpty())
                    && ref.navigationRole() == null;
            return ownMember && ref.objectType() != null && ref.objectType().granularityOf(ref.attribute()) != null;
        }

        /** Of {@code e} een vouw over de cellen van een gedimensioneerd attribuut is (RUNTIME-SEMANTICS ch. 5.8c): een
         *  aggregatie met een dimensieselectie. Haar knoop draagt haar waarde en geen bijdragers; de verwijzing eronder
         *  heeft geen eigen lezing. */
        private static boolean overCells(Expr e) {
            return e instanceof Expr.FunctionCall f && f.dimensionSelections() != null;
        }

        /** Wat een operand de zin noemt, één keer: zijn brontekst en de declaratie die het leest. */
        private String operandKey(Expr e) {
            String text = e.source() == null || e.source().snippet() == null ? "" : e.source().snippet().trim();
            Designatable d = declarationOf(e);
            return text + "\u0000" + (d == null ? "" : d.kind() + "\u0000" + d.owner() + "\u0000" + d.name());
        }

        /** De regeltekst van vouw {@code f}, met de declaratie van het attribuut dat zij leest, bij een cel van elk lid
         *  met die cel onder {@code labels}; staat haar filter erin en is dat één kenmerkcheck of één lezing, dan is het
         *  filter een eigen stuk met de declaratie die het leest, en een samenstelling een stuk per lid. */
        private void foldText(Sentence.Builder b, Expr.FunctionCall f) {
            String text = f.source().snippet().trim();
            // no object: the fold reads its source on every member, and the members stand in MEMBERS
            Map<String, Object> source = f.arguments().size() == 1 ? keys(f.arguments().get(0), null) : Map.of();
            Condition filter = f.filter();
            if (filter == null) {
                b.leaf(Part.Kind.QUOTE, text, source);
                return;
            }
            String filterText = summary(filter);
            int at = text.lastIndexOf(filterText);
            if (at < 0)
                throw new IllegalStateException("de tekst van het filter \"" + filterText + "\" staat niet in de tekst"
                        + " van de vouw \"" + text + "\"");
            if (at > 0) b.leaf(Part.Kind.QUOTE, text.substring(0, at), source);
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
                return Naming.characteristicOf(walk.isEmpty() ? checkedType(k, element) : holderOf(walk),
                        k.characteristic());
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
         *  zonder waarde per lid. Boven vijf de eerste vijf en "en nog k". De bijdragers van een telling zijn de leden
         *  die zij telt; die van elke andere vouw de leden met een waarde, want een lid met een lege waarde draagt niets
         *  bij (uchars RUNTIME-SEMANTICS ch. 5.12), dus zonder bijdrager zegt de zin niet dat er geen lid is. */
        private void contributors(Sentence.Builder b, Expr.FunctionCall f, ConditionReading.NodeReading n) {
            contributors(b, f, n, null);
        }

        /** {@link #contributors(Sentence.Builder, Expr.FunctionCall, ConditionReading.NodeReading)}, met {@code in} de
         *  periode waarin de zin de vouw noemt, of null voor al haar dagen: in een periode draagt een lid zijn tijdlijn
         *  in die periode bij, en een lid wiens tijdlijn daar leeg is niets (RUNTIME-SEMANTICS ch. 3.5). Een totaal
         *  zonder filter over een tijdlijn van zijn leden noemt bij elk lid naast zijn totaal de tijdlijn die het
         *  aan het fixpunt houdt, waaruit dat totaal kwam. */
        private void contributors(Sentence.Builder b, Expr.FunctionCall f, ConditionReading.NodeReading n, Period in) {
            boolean count = f.kind() == Expr.FunctionKind.AANTAL || f.kind() == Expr.FunctionKind.AANTAL_GEVULDE;
            String withValue = f.kind() == Expr.FunctionKind.AANTAL ? "" : " met een waarde";
            String totalled = in == null && f.kind() == Expr.FunctionKind.TOTAAL && f.filter() == null
                    && f.arguments().size() == 1 && f.arguments().get(0) instanceof Expr.AttributeReference ref
                    && ref.objectType() != null && ref.objectType().granularityOf(ref.attribute()) != null
                    ? ref.attribute() : null;
            List<Map.Entry<String, Object>> members = new ArrayList<>();
            Map<String, Object> timelines = new HashMap<>();
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
                Object value = c.value();
                if (in != null && value instanceof List<?> timeline) {
                    value = within(timeline, in);
                    if (value == null) continue;
                }
                members.add(new java.util.AbstractMap.SimpleImmutableEntry<>(name, value));
                if (totalled != null) {
                    EndState.Member held = member(c.member(), totalled, false, null);
                    if (held != null) timelines.put(name, held.value());
                }
            }
            members.sort(Map.Entry.comparingByKey());
            int m = members.size();
            b.open(Part.Kind.MEMBERS, Part.meta("members", m)).text(": ");
            if (m == 0) {
                b.text(withValue.isEmpty() ? "er is geen enkel lid" : "geen lid droeg een waarde bij");
            } else {
                b.text("over ");
                int shown = Math.min(m, 5);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) b.text(i == shown - 1 && m <= 5 ? " en " : ", ");
                    Map.Entry<String, Object> member = members.get(i);
                    b.leaf(Part.Kind.SUBJECT, member.getKey());
                    if (count) continue;
                    Object v = member.getValue(), held = timelines.get(member.getKey());
                    if (held == null) {
                        reading(b, renderRead(f, v, n.unit()), v == null, v);
                        continue;
                    }
                    if (!(held instanceof List<?> segments))
                        throw new IllegalStateException("het tijdsafhankelijke attribuut '" + totalled + "' staat op "
                                + member.getKey() + " als " + held.getClass().getName() + ", niet als lijst segmenten");
                    Datatype datatype = ((Expr.AttributeReference) f.arguments().get(0)).objectType()
                            .attribute(totalled);
                    b.open(Part.Kind.READING, Map.of()).text(" (");
                    value(b, renderRead(f, v, n.unit()), v == null, v);
                    b.text(" uit ").leaf(Part.Kind.VALUE, renderPeriods(Period.list(segments), null,
                            x -> render(x, datatype)), Part.meta("timeline", true));
                    b.text(")").close(Part.Kind.READING);
                }
                if (m > 5) b.text(" en nog " + (m - 5));
                b.text(m == 1 ? ", het enige lid" + withValue : " (" + m + " leden" + withValue + ")");
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

    /** Één kenmerktoekenningsregel voor één instantie: is het kenmerk toegekend → "waarom (voorwaarden)"; is het
     *  met een voorwaarde NIET toegekend → "niet, want <het lid waar de motor stopte>"; onvoorwaardelijk-afwezig →
     *  geen zin (er valt niets te verantwoorden). Staat het kenmerk aan terwijl de voorwaarde van deze regel niet gold,
     *  dan kwam het uit de invoer of van een andere schrijver, en zegt de zin dat deze regel niet gold. */
    private Sentence verbaliseAssignment(String label, Object bean, Expr.AttributeReference ref, ResolvedRule r) {
        String characteristic = ref.attribute();
        ObjectType subject = ref.objectType();
        if (subject.characteristicGranularityOf(characteristic) != null
                || subject.characteristicTimelineCountOf(characteristic) != null)
            return periodAssignment(label, bean, ref, r);
        boolean present = member(bean, characteristic, true, null) != null;
        Condition v = r.condition();
        if (!present && v == null) return null;   // onvoorwaardelijk maar afwezig: niets te verantwoorden
        // de conclusie van de regel ("Een … heeft de uitkeringsduur verstreken") draagt het lidwoord
        Predicate predicate = predicate(subject, characteristic, present, ref.source() == null ? null : ref.source().snippet());
        if (predicate == null)
            throw new IllegalStateException("de kenmerktoekenning '" + r.name() + "' kent '" + characteristic + "' toe, en "
                    + subject.name + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
        boolean ucharText = writtenByUchar(r);
        Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
        predicate.mainClause(b, new On(label));
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

    /** Eén kenmerktoekenning op een tijdsafhankelijk kenmerk voor één instantie (RUNTIME-SEMANTICS ch. 3.10, 3.11): de
     *  regel kent het kenmerk toe in de perioden waarin haar voorwaarde geldt, en de bean houdt die ware perioden. De
     *  zin noemt ze zoals RegelSpraak een periode schrijft, met de regel: {@code "A1 is rentedragend van 16-11-2025 tot
     *  13-03-2026 volgens de regel 'rentedragend'"}, zonder de voorwaarde.
     *  Zonder ware periode geldt het kenmerk op geen dag: {@code "… is niet rentedragend volgens de
     *  regel '…'"}. */
    private Sentence periodAssignment(String label, Object bean, Expr.AttributeReference ref, ResolvedRule r) {
        String characteristic = ref.attribute();
        EndState.Member m = member(bean, characteristic, true, null);
        Object held = m == null ? null : m.value();
        if (held != null && !(held instanceof List<?>))
            throw new IllegalStateException("het tijdsafhankelijke kenmerk '" + characteristic + "' staat op " + label
                    + " als " + held.getClass().getName() + ", niet als lijst perioden");
        List<?> periods = held == null ? List.of() : (List<?>) held;
        boolean present = !periods.isEmpty();
        Predicate predicate = predicate(ref.objectType(), characteristic, present,
                ref.source() == null ? null : ref.source().snippet());
        if (predicate == null)
            throw new IllegalStateException("de kenmerktoekenning '" + r.name() + "' kent '" + characteristic + "' toe, en "
                    + ref.objectType().name + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
        Sentence.Builder b = Sentence.builder().leaf(Part.Kind.SUBJECT, label).text(" ");
        predicate.mainClause(b, new On(label));
        if (present)
            b.text(" ").leaf(Part.Kind.VALUE, renderPeriods(Period.list(periods), null, null), Part.meta("timeline", true));
        b.text(" volgens de regel '").leaf(Part.Kind.RULE, RegelSpraakModel.formName(r.name())).text("'");
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

    /** Van welk object een gedeclareerde naam de waarde noemt: {@code label} zijn naam zoals {@code SUBJECT} het noemt,
     *  null waar een link op de wandeling van de lezing ontbreekt en de lezing dus van geen object is
     *  (RUNTIME-SEMANTICS ch. 5.8 regels 1 en 3). Een naam zonder {@code On} noemt geen waarde van één object: een
     *  parameter, een rol, de tekst of het filter van een vouw over een verzameling, die per lid gelezen worden, een
     *  lezing die de motor niet bereikte. */
    private record On(String label) {}

    /** Een gedeclareerde naam zoals {@link Naming} haar noemt, met de sleutels van zijn declaratie {@code d}, de
     *  sleutels van {@code vocabularies.elements[]}; zonder sleutels waar de declaratie niet bekend is. */
    private static void declaredName(Sentence.Builder b, String text, Designatable d) {
        b.leaf(Part.Kind.NAME, text, declaration(d));
    }

    private static Map<String, Object> declaration(Designatable d) {
        return declaration(d, null);
    }

    /** De sleutels van declaratie {@code d}, en met {@code on} de sleutel {@code object}: van welk object de zin de
     *  waarde van die declaratie noemt. */
    private static Map<String, Object> declaration(Designatable d, On on) {
        if (d == null) return Map.of();
        Map<String, Object> meta = Part.meta("declaration", d.kind().name(), "owner", d.owner(), "canonical", d.name());
        if (on != null) meta.put("object", on.label());
        return meta;
    }

    /** Cel {@code cell} van attribuut {@code member} van {@code holder}, zoals RegelSpraak een cel schrijft (§3.6): de
     *  labels van een dimensie zonder voorzetsel vóór de naam, de andere erachter, elk na het voorzetsel van zijn
     *  dimensie, in de gedeclareerde volgorde van de dimensies ("vondstgewicht in najaar", "aanvoer op zuid uit zee").
     *  De naam draagt de sleutels van het attribuut, het object ({@link On}) en de cel onder {@code labels}, zoals uchar
     *  haar sleutelt ({@link FactModelGenerator#cellKey}); de labels en hun voorzetsels zijn lijm. */
    private void cellName(Sentence.Builder b, ObjectType holder, String member, List<String> cell, On on) {
        List<String> dimensions = holder.dimensionsOf(member);
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        for (int i = 0; i < dimensions.size(); i++) {
            RegelSpraakModel.Dimension d = model.dimensionByName(dimensions.get(i));
            if (d == null)
                throw new IllegalStateException("de dimensie '" + dimensions.get(i) + "' van '" + member + "' is niet"
                        + " gedeclareerd");
            if (d.preposition() == null) before.append(cell.get(i)).append(' ');
            else after.append(' ').append(d.preposition()).append(' ').append(cell.get(i));
        }
        if (!before.isEmpty()) b.text(before.toString());
        Map<String, Object> meta = declaration(Naming.attributeOf(holder, member), on);
        meta.put("labels", cell);
        b.leaf(Part.Kind.NAME, naming.attribute(holder, member), meta);
        if (!after.isEmpty()) b.text(after.toString());
    }

    /** Cel {@code labels} van attribuut {@code ref} zoals uchar haar sleutelt ({@link FactModelGenerator#cellKey}): één
     *  label per dimensie, in de gedeclareerde spelling. Labels die geen cel noemen weigert uchars WF-040. */
    private List<String> cellKey(Expr.AttributeReference ref, List<String> labels) {
        List<String> key = FactModelGenerator.cellKey(model, ref.objectType().dimensionsOf(ref.attribute()), labels);
        if (key == null)
            throw new IllegalStateException("de labels " + labels + " noemen geen cel van '" + ref.attribute() + "' van "
                    + ref.objectType().name + ", wat uchars WF-040 weigert");
        return key;
    }

    /** Attribuut {@code member} van {@code holder} in het vocabulaire, als naam van een variant: geen waarde. */
    private void attributeName(Sentence.Builder b, ObjectType holder, String member) {
        attributeName(b, holder, member, null);
    }

    /** Attribuut {@code member} van {@code holder} in het vocabulaire, met het object waarvan de zin zijn waarde noemt
     *  ({@link On}). */
    private void attributeName(Sentence.Builder b, ObjectType holder, String member, On on) {
        b.leaf(Part.Kind.NAME, naming.attribute(holder, member), declaration(Naming.attributeOf(holder, member), on));
    }

    /** Een waarde in de opmaak van de zin; {@code empty} bij een lege, en {@code marker} waar {@code raw}, de waarde
     *  zoals de motor haar gaf, een afwezigheidsmarker is: de tekst alleen onderscheidt een marker niet van een
     *  enumeratiewaarde of een literal met dezelfde spelling. */
    private static void value(Sentence.Builder b, String text, boolean empty, Object raw) {
        Map<String, Object> meta = empty ? Part.meta("empty", true) : new LinkedHashMap<>();
        String marker = markerOf(raw);
        if (marker != null) meta.put("marker", marker);
        b.leaf(Part.Kind.VALUE, text, meta);
    }

    /** De naam van de afwezigheidsmarker die {@code raw} is, in de gedeclareerde spelling: een constructorwaarde zonder
     *  payload (uchars RUNTIME-SEMANTICS ch. 9.5); null voor elke andere waarde, ook de waarde {@code waarde(…)}. */
    private static String markerOf(Object raw) {
        return raw instanceof ConstructorValue c && c.payload() == null ? c.constructor() : null;
    }

    /** De waarde die de motor las voor wat er direct voor staat, tussen haakjes, als groep: " (", de waarde, ")";
     *  {@code raw} zoals bij {@link #value}. */
    private static void reading(Sentence.Builder b, String text, boolean empty, Object raw) {
        b.open(Part.Kind.READING, Map.of()).text(" (");
        value(b, text, empty, raw);
        b.text(")").close(Part.Kind.READING);
    }

    /** Wat attribuut {@code member} van {@code bean}, van objecttype {@code type} en met naam {@code label}, houdt waar
     *  niemand die de zin noemt het schreef: leeg, of de waarde die het aan het fixpunt houdt
     *  ({@link #standingWord}). Geeft of er een waarde staat. */
    private boolean attributeStanding(Sentence.Builder b, Object bean, String label, ObjectType type, String member) {
        EndState.Member m = member(bean, member, false, null);
        if (m == null) {
            b.text("heeft geen ");
            attributeName(b, type, member, new On(label));
            return false;
        }
        b.text("houdt als ");
        attributeName(b, type, member, new On(label));
        b.text(" " + standingWord(bean, m) + " ");
        value(b, render(m.value(), type.attribute(member)), false, m.value());
        return true;
    }

    /** Lid {@code name} van {@code bean} zoals uchar het aan het fixpunt las ({@link EndState.Member}): een kenmerk of
     *  een attribuut, bij een gedimensioneerd attribuut de cel {@code cell}; null waar het geen waarde houdt. De
     *  waarde is de afgeleide waar iets haar afleidde, anders wat de invoer of de objectcreatie gaf. */
    private EndState.Member member(Object bean, String name, boolean characteristic, List<String> cell) {
        EndState.EndObject o = ends.get(bean);
        if (o == null)
            throw new IllegalStateException("uchars lezing van het fixpunt noemt het object met lid '" + name + "' niet:"
                    + " de uitleg leest de standing van dezelfde sessie");
        String key = Namen.norm(name);
        for (EndState.Member m : o.members())
            if (m.characteristic() == characteristic && Namen.norm(m.name()).equals(key)
                    && Objects.equals(m.cell(), cell)) return m;
        return null;
    }

    /** De objecten die de koppeling van verdeling {@code a} ({@code connectingRole}, de rol van de ontvanger) met
     *  {@code o} verbindt, in de volgorde van uchars lezing van de koppelingen: met {@code recipients} de ontvangers
     *  van bron {@code o}, anders de bronnen van ontvanger {@code o}. */
    private List<Object> joined(ResolvedAllocation a, Object o, boolean recipients) {
        RoleBindingRef join = document.loaded().roleBindings().get(a);
        if (join == null)
            throw new IllegalStateException("de verdeling '" + a.name() + "' heeft in het document geen koppeling naar"
                    + " haar ontvangers");
        String factType = Namen.norm(join.factType()), role = Namen.norm(join.role());
        List<Object> out = new ArrayList<>();
        for (EndState.Link k : links) {
            if (!Namen.norm(k.factType()).equals(factType)) continue;
            boolean secondReceives = Namen.norm(k.secondRole()).equals(role);
            Object recipient = secondReceives ? k.second() : k.first(), source = secondReceives ? k.first() : k.second();
            if (!secondReceives && !Namen.norm(k.firstRole()).equals(role)) continue;
            Object mine = recipients ? source : recipient, other = recipients ? recipient : source;
            ObjectType expected = recipients ? a.targetType() : a.sourceType();
            if (mine == o && typeOf(other).isA(expected) && !out.contains(other)) out.add(other);
        }
        return out;
    }

    /** Het objecttype van object {@code o} van de sessie, zoals uchars lezing van het fixpunt het noemt. */
    private ObjectType typeOf(Object o) {
        EndState.EndObject e = ends.get(o);
        ObjectType t = e == null ? null : model.objectTypeByName(e.type());
        if (t == null)
            throw new IllegalStateException("uchars lezing van het fixpunt noemt een object zonder objecttype van het"
                    + " model van de uitleg");
        return t;
    }

    /** De naam van object {@code o} van de sessie ({@link #labels}). */
    private String nameOf(Object o) {
        String name = labels.get(o);
        if (name == null)
            throw new IllegalStateException("een " + o.getClass().getSimpleName() + " van de sessie heeft geen naam: de"
                    + " uitleg noemt de objecten van de invoer en de gemaakte objecten van het fixpunt");
        return name;
    }

    /** Wat de invoer of de objectcreatie lid {@code m} gaf; null zonder lid of waar zij het niets gaf. */
    private static Object given(EndState.Member m) {
        return m == null ? null : m.given();
    }

    /** Hoe de zin de waarde {@code m} noemt die op {@code bean} staat waar geen schrijver die de zin noemt besliste: een
     *  waarde die iets anders afleidde "de afgeleide waarde", anders die van {@link #standingValue}. */
    private String standingWord(Object bean, EndState.Member m) {
        return m.derived() ? "de afgeleide waarde" : standingValue(bean);
    }

    /** Of {@code bean} een object is dat een creatie van de sessie maakte ({@link EndState.EndObject#creation}). */
    private boolean madeObject(Object bean) {
        for (EndState.EndObject g : made) if (g.object() == bean) return true;
        return false;
    }

    /** Hoe de zin de waarde noemt die op {@code bean} staat waar geen schrijver besliste. Een verzoek geeft een gemaakt
     *  object niets: wat daar de plaats van de invoer heeft, is de lidwaarde die de creatie het gaf (uchars
     *  RUNTIME-SEMANTICS ch. 7.8, rang 2 van de stapel), dus "de bij de objectcreatie toegekende waarde"; anders "de
     *  invoerwaarde". */
    private String standingValue(Object bean) {
        return madeObject(bean) ? "de bij de objectcreatie toegekende waarde" : "de invoerwaarde";
    }

    /** Waar het doel van een initialisatie op {@code bean} leeg was, met de spatie ervoor: op een gemaakt object bij de
     *  objectcreatie ({@link #standingValue}), anders in de invoer. */
    private String emptyBefore(Object bean) {
        return madeObject(bean) ? " bij de objectcreatie leeg was" : " in de invoer leeg was";
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
        if (c instanceof Condition.Compound s && counting(s)) {
            countingCompound(b, s, read, subject, ucharText);
            return;
        }
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
                    // "geen van de volgende" ontkent elk lid, ook het eerste: "niet A en niet B"
                    else if (s.quantifier() == Condition.Quantifier.GEEN) b.leaf(Part.Kind.CONNECTIVE, "niet").text(" ");
                    first = false;
                    verbaliseCondition(b, d, read, subject, ucharText);
                }
            b.close(Part.Kind.CONDITION);
            return;
        }
        verbaliseAtom(b, c, read, subject, ucharText);
    }

    /** Of samenstelling {@code s} een telling is waarvan de uitleg de drempel noemt. Ten minste 1 leest als "A of B",
     *  wat precies die drempel zegt. */
    private static boolean counting(Condition.Compound s) {
        return switch (s.quantifier()) {
            case TEN_MINSTE -> s.threshold() > 1;
            case TEN_HOOGSTE, PRECIES -> true;
            case ALLE, GEEN, DE -> false;
        };
    }

    /**
     * Een samenstelling met een telling: de drempel ("ten hoogste 1 van de 2 voorwaarden geldt") en daarna elk lid dat
     * de motor bereikte met zijn eigen oordeel, "A" of "niet A", verbonden met "en". Een telling kan gelden op leden die
     * niet gelden (ten hoogste: de rest kan de drempel niet meer overschrijden, RUNTIME-SEMANTICS ch. 5.10); een lid
     * zonder "niet" zou zeggen dat het geldt. "A of B" zou bij ten minste 2 zeggen dat één lid volstaat.
     */
    private void countingCompound(Sentence.Builder b, Condition.Compound s, Read read, ObjectType subject,
                                  boolean ucharText) {
        String phrase = switch (s.quantifier()) {
            case TEN_MINSTE -> "ten minste";
            case TEN_HOOGSTE -> "ten hoogste";
            case PRECIES -> "precies";
            default -> throw new IllegalStateException("de kwantor " + s.quantifier() + " is geen telling");
        };
        b.open(Part.Kind.CONDITION, conditionMeta(s, read));
        b.leaf(Part.Kind.CONNECTIVE, phrase + " " + s.threshold() + " van de " + s.components().size()
                + " voorwaarden " + (s.threshold() == 1 ? "geldt" : "gelden"));
        boolean first = true;
        for (Condition d : s.components())
            if (read.reached(d)) {
                if (!(read.of(d).value() instanceof Boolean holds))
                    throw new IllegalStateException("de motor gaf voor een lid van de samenstelling \"" + summary(s)
                            + "\" van " + read.form() + " op " + read.label() + " geen oordeel, maar "
                            + read.of(d).value());
                // zonder bereikt lid (ten hoogste d van n met d >= n) staat de drempel alleen
                b.text(first ? ": " : " ");
                if (!first) b.leaf(Part.Kind.CONNECTIVE, "en").text(" ");
                first = false;
                if (!holds) b.leaf(Part.Kind.CONNECTIVE, "niet").text(" ");
                verbaliseCondition(b, d, read, subject, ucharText);
            }
        b.close(Part.Kind.CONDITION);
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
            ObjectType holder = walk.isEmpty() ? checkedType(k, subject) : holderOf(walk);
            Predicate predicate = predicate(holder, k.characteristic(), !k.negation(), summary(c));
            // zonder soort geen werkwoord: dan de voorwaarde zoals de regel haar schrijft
            if (predicate == null) return null;
            return b -> {
                who(b, walk);
                b.text(" ");
                predicate.clause(b, objectOn(k, k.characteristic(), read));
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
            // de toets of een attribuut met afwezigheidsmarkers een waarde heeft, zoals het dialect hem schrijft
            boolean value = p.operand() instanceof Expr.PayloadAccess access
                    && Namen.norm(access.constructor()).equals(Namen.norm(RegelSpraakModel.VALUE_CONSTRUCTOR));
            String core = switch (p.kind()) {
                case LEEG -> value ? "geen waarde heeft" : "leeg is";
                case GEVULD -> value ? "een waarde heeft" : "gevuld is";
                case NUMERIEK_MET_EXACT -> (p.negation() ? "niet " : "") + "numeriek is met exact " + p.digits()
                        + " cijfers";
                // a day-kind check outside a day-kind definition is no form uchar carries
                case DAGSOORT -> throw new IllegalStateException("de voorwaarde \"" + summary(c) + "\" toetst een"
                        + " dagsoort buiten een dagsoortdefinitie, wat uchar niet draagt");
            };
            // the whole-period form (§8.2) stands before the predicate, as the rule writes it
            String period = p.period() == null ? ""
                    : (p.period().negated() ? "niet " : "") + "gedurende "
                    + (p.period().year() ? "het gehele jaar " : "de gehele maand ");
            return b -> {
                operandProse(b, p.operand(), read, ucharText);
                b.text(" " + period + core);
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
            if (c instanceof Condition.QuantifierFold q) members(g, q, read);
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
        b.open(Part.Kind.MEMBERS, Part.meta("members", m, "holding", k, "unjudged", unjudged)).text(": ");
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

    /** Het type waarop kenmerkcheck {@code k} zonder wandeling op {@code subject} het kenmerk leest: haar host waar die
     *  strikt onder het onderwerp ligt (een kenmerk dat alleen een subtype declareert; RUNTIME-SEMANTICS ch. 5.8b
     *  regel 4), anders het onderwerp. */
    private static ObjectType checkedType(Condition.CharacteristicCheck k, ObjectType subject) {
        ObjectType host = k.owner();
        return host != null && host != subject && host.isA(subject) ? host : subject;
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

    /** Het woord voor een lege waarde, op elk operand en elk attribuut, zoals RegelSpraak het schrijft ("is leeg"): een
     *  attribuut met afwezigheidsmarkers kan de waarde "onbekend" houden (RUNTIME-SEMANTICS ch. 9.5), dus dat woord is
     *  de marker en nooit leeg. */
    private static final String EMPTY = "leeg";

    /** De waarde van een operand zoals de motor haar las, in NL-notatie met haar eenheid; {@link #EMPTY} voor een lege
     *  waarde. Een getal staat met de decimalen van zijn type, of van de declaratie van het gelezen lid; een
     *  getalliteral met de decimalen die zijn lexeem schrijft ("30.00" als "30,00"), want het type van een literal noemt
     *  ze niet. Een constructorwaarde staat als haar payload of, zonder payload, bij de naam van haar constructor
     *  ({@link #render}). */
    private String renderOperand(Expr e, Read read) {
        ConditionReading.NodeReading n = read.of(e);
        return renderRead(e, n.value(), n.unit());
    }

    /** {@link #renderOperand} van waarde {@code v} met eenheid {@code u}, die de motor op knoop {@code e} las, of een
     *  lid van een vouw op die knoop bijdroeg. */
    private String renderRead(Expr e, Object v, Unit u) {
        if (v == null) return EMPTY;
        if (v instanceof List<?> timeline) return renderTimeline(e, timeline, u);
        if (v instanceof ConstructorValue c) {
            if (c.payload() == null) return c.constructor();
            Datatype payload = payloadType(typeOf(e), c.constructor());
            String number = nlExact(c.payload(), payload);
            if (number == null) return render(c.payload(), payload);
            String unit = u != null ? u.rendering() : unitWord(payload);
            return unit == null ? number : number + " " + unit;
        }
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
        String unit = u != null ? u.rendering()
                : (e.type() instanceof Datatype.Percentage || typeOf(e) instanceof Datatype.Percentage) ? "%" : null;
        String number = nlExact(v, type);
        if (number == null) return renderValue(v, type);
        return unit == null ? number : number + " " + unit;
    }

    /** Een tijdlijn die de motor op knoop {@code e} las (RUNTIME-SEMANTICS ch. 5.12: een lijst perioden, de waarden
     *  van rekenen per periode exact), als RegelSpraak een periode schrijft ({@link #renderPeriods}), elke waarde zoals
     *  {@link #renderRead} haar schrijft. */
    private String renderTimeline(Expr e, List<?> timeline, Unit u) {
        return renderPeriods(timeline, e, value -> renderRead(e, value, u));
    }

    /** De perioden {@code timeline} als RegelSpraak een periode schrijft: per segment zijn waarde met {@code van <datum>
     *  tot <datum>}, {@code tot <datum>} waar het open begint, {@code vanaf <datum>} waar het open eindigt, en
     *  {@code altijd} voor een segment over alle tijd; een periode zonder waarde, zoals een periode waarin een kenmerk
     *  of een voorwaarde geldt, alleen als periode, en zonder {@code value} elke periode zo. Een tijdlijn zonder segment
     *  is leeg op elke dag: "altijd leeg". {@code e} is de knoop die de motor las, voor de zin van een fout. */
    private static String renderPeriods(List<?> timeline, Expr e, java.util.function.Function<Object, String> value) {
        if (timeline.isEmpty()) return "altijd " + EMPTY;
        List<String> segments = new ArrayList<>();
        for (Object s : timeline) {
            LocalDate from, to;
            Object v;
            if (s instanceof Period p) { from = p.from(); to = p.to(); v = p.value(); }
            else if (s instanceof PeriodArithmetic.Exact x) { from = x.from(); to = x.to(); v = x.value(); }
            else throw new IllegalStateException("de motor las" + (e == null ? "" : " op \"" + snippet(e) + "\"")
                    + " een tijdlijn met een segment van de vorm " + s.getClass().getName() + ", dat de uitleg niet kent");
            segments.add(v == null || value == null ? periodText(from, to) : inPeriod(value.apply(v), from, to));
        }
        return joined(segments);
    }

    /** De periode {@code [from, to)} zoals RegelSpraak haar schrijft: {@code van <datum> tot <datum>}, {@code tot
     *  <datum>} waar zij open begint, {@code vanaf <datum>} waar zij open eindigt, {@code altijd} over alle tijd. */
    private static String periodText(LocalDate from, LocalDate to) {
        boolean openFrom = from.equals(Period.OPEN_FROM), openTo = to.equals(Period.OPEN_TO);
        return openFrom && openTo ? "altijd" : openFrom ? "tot " + renderValue(to, null)
                : openTo ? "vanaf " + renderValue(from, null)
                : "van " + renderValue(from, null) + " tot " + renderValue(to, null);
    }

    /** Waarde {@code value} in de periode {@code [from, to)}: de waarde vóór de periode, over alle tijd erna
     *  ("altijd 0"). */
    private static String inPeriod(String value, LocalDate from, LocalDate to) {
        boolean always = from.equals(Period.OPEN_FROM) && to.equals(Period.OPEN_TO);
        return always ? periodText(from, to) + " " + value : value + " " + periodText(from, to);
    }

    /** Tijdlijn {@code timeline}, zoals de motor haar las, in periode {@code in}: null waar zij daar op geen dag een
     *  waarde heeft, de ene waarde waar één segment de hele periode vult, en anders haar segmenten met een waarde in
     *  die periode, afgesneden op haar grenzen. */
    private static Object within(List<?> timeline, Period in) {
        List<Span<?>> out = new ArrayList<>();
        for (Object s : timeline) {
            if (!(s instanceof Span<?> span))
                throw new IllegalStateException("de motor las een tijdlijn met een segment van de vorm "
                        + s.getClass().getName() + ", dat de uitleg niet kent");
            if (span.value() == null) continue;
            LocalDate from = span.from().isAfter(in.from()) ? span.from() : in.from();
            LocalDate to = span.to().isBefore(in.to()) ? span.to() : in.to();
            if (from.isBefore(to)) out.add(span.between(from, to));
        }
        if (out.isEmpty()) return null;
        Span<?> first = out.get(0);
        return out.size() == 1 && first.from().equals(in.from()) && first.to().equals(in.to()) ? first.value() : out;
    }

    /** Segmenten achter elkaar, met "en" voor het laatste. */
    private static String joined(List<String> segments) {
        return segments.size() == 1 ? segments.get(0)
                : String.join(", ", segments.subList(0, segments.size() - 1)) + " en " + segments.get(segments.size() - 1);
    }

    /** Een voorwaarde per periode (RUNTIME-SEMANTICS ch. 3.10, 5.12) geldt in perioden, die de motor las: haar tekst met
     *  die perioden. Geeft of {@code c} er zo een is, en schrijft anders niets. */
    private static boolean heldInPeriods(Sentence.Builder b, Condition c, Read read) {
        if (!(read.has(c) && read.reached(c) && read.of(c).value() instanceof List<?> periods)) return false;
        b.leaf(Part.Kind.QUOTE, summary(c));
        reading(b, renderPeriods(periods, null, null), false, null);
        return true;
    }

    private static String snippet(Expr e) {
        return e.source() == null || e.source().snippet() == null ? "" : e.source().snippet().trim();
    }

    /** Het type van een operand: dat van de expressie, of voor een attribuut-, cel- of parameterlezing dat van de
     *  declaratie (een lezing uit uchars lader draagt zelf geen type); null voor elk ander operand. */
    private static Datatype typeOf(Expr e) {
        if (e.type() != null) return e.type();
        if (e instanceof Expr.DimensionCellRead c) return typeOf(c.attribute());
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
        // de lezer kent de waarde en de afwezigheidsmarker, niet uchars constructorwaarde (RUNTIME-SEMANTICS ch. 9.5)
        if (v instanceof ConstructorValue c)
            return c.payload() != null ? render(c.payload(), payloadType(type, c.constructor())) : c.constructor();
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
        if (e instanceof Expr.DimensionCellRead c) return enumerationOf(c.attribute());
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
        // de lezing van de waarde van een attribuut met afwezigheidsmarkers is leeg op een marker: de zin noemt het
        // attribuut met wat het hield, de waarde of de marker (uchars RUNTIME-SEMANTICS ch. 9.5)
        if (e instanceof Expr.PayloadAccess p && read.has(p.base())) {
            operandProse(b, p.base(), read, ucharText);
            return;
        }
        if (!read.reached(e)) {
            describe(b, e, ucharText);
            return;
        }
        if (e instanceof Expr.Literal || e instanceof Expr.Construct c && c.argument() == null) {
            value(b, renderOperand(e, read), read.of(e).value() == null, read.of(e).value());
            return;
        }
        describe(b, e, ucharText, readOn(e, read));
        reading(b, renderOperand(e, read), read.of(e).value() == null, read.of(e).value());
    }

    /** Een Java-waarde in helder Nederlands: getallen met punt-duizend/komma-decimaal en de eenheid uit het type;
     *  datums als dd-mm-jjjj; booleans als ja/nee. */
    static String renderValue(Object v, Datatype type) {
        if (v == null) return EMPTY;
        String number = nlExact(v, type);
        if (number != null) {
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

    /** Het datatype van de payload van constructor {@code constructor} van sum type {@code type}, zoals uchar
     *  constructornamen vergelijkt ({@link Namen#norm}); voor een ander type dat type zelf. */
    private static Datatype payloadType(Datatype type, String constructor) {
        if (!(type instanceof Datatype.SumType sum)) return type;
        for (Datatype.Constructor c : sum.constructors())
            if (Namen.norm(c.name()).equals(Namen.norm(constructor))) return c.payload();
        throw new IllegalStateException("de motor las de constructor '" + constructor + "', die het sum type '"
                + sum.domain() + "' niet declareert");
    }

    /** Een getal zoals de motor het las, in NL-notatie: een decimaal zoals {@link #nlNumber} het schrijft, of een
     *  waarde die niet eindigt als haar breuk, teller en noemer elk in NL-notatie, zoals uchars lezing haar schrijft
     *  ({@code t/n}, RUNTIME-SEMANTICS ch. 5.12): de motor rekent exact (ch. 2.1), en elke decimaal ervan zou een
     *  afronding zijn die geen regel schrijft. Null voor een waarde die geen getal is. */
    private static String nlExact(Object v, Datatype type) {
        if (v instanceof BigDecimal b) return nlNumber(b, type);
        if (v instanceof Breuk q)
            return nlNumber(new BigDecimal(q.teller()), null) + "/" + nlNumber(new BigDecimal(q.noemer()), null);
        return null;
    }

    /** Getal in NL-notatie: punt als duizendtal-scheider, komma als decimaalteken. Een percentage of getal met
     *  gedeclareerde decimalen wordt aangevuld tot die decimalen en nooit erop afgerond: een waarde die de motor met
     *  meer decimalen las, staat er met al haar decimalen, zonder nullen achteraan voorbij de gedeclareerde. Zonder
     *  gedeclareerde decimalen, ook in de onbegrensde vorm ({@code getal}), de natuurlijke schaal van de waarde. */
    static String nlNumber(BigDecimal b, Datatype type) {
        BigDecimal w = b;
        int decimals = -1;
        if (type != null && type.unbounded()) decimals = -1;
        else if (type instanceof Datatype.Numeric n && !n.wholeNumber()) decimals = n.decimals();
        else if (type instanceof Datatype.Percentage p && !p.wholeNumber()) decimals = p.decimals();
        else if (type instanceof Datatype.Numeric n && n.wholeNumber()) decimals = 0;
        // een afronding hier zette een waarde in de zin die de motor niet vergeleek
        if (decimals >= 0 && w.scale() > decimals) w = w.stripTrailingZeros();
        if (decimals >= 0 && w.scale() < decimals) w = w.setScale(decimals);
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
        // nul heeft geen teken, ook waar zij als "-0.00" binnenkomt
        String sign = w.signum() < 0 ? "-" : "";
        return sign + grouped + (fraction.isEmpty() ? "" : "," + fraction);
    }

    /** Het eenheid-woord voor een type (EUR, jr, %, …), of null wanneer het type er geen draagt. */
    private static String unitWord(Datatype type) {
        if (type instanceof Datatype.Percentage) return "%";
        Unit e = type == null ? null : type.unitOf();
        return e == null ? null : e.rendering();
    }

    /** Een korte omschrijving van WAT een operand is, uit zijn brontekst (bv. "de maandloon nieuw van EOver"); die is
     *  regeltekst en blijft zoals hij geschreven is, behalve de naam van een gelezen attribuut in een regel die uchar
     *  schreef ({@code ucharText}). Zonder brontekst noemt hij de declaratie. */
    private void describe(Sentence.Builder b, Expr e, boolean ucharText) {
        describe(b, e, ucharText, null);
    }

    /** {@link #describe(Sentence.Builder, Expr, boolean)}, met het object waarop de motor de waarde las die de zin
     *  erachter noemt ({@link #readOn}), op het stuk met de sleutels van de declaratie. */
    private void describe(Sentence.Builder b, Expr e, boolean ucharText, On on) {
        if (e == null) {
            b.text("de waarde");
            return;
        }
        String source = e.source().snippet();
        if (source != null && !source.isBlank()) {
            if (ucharText && e instanceof Expr.AttributeReference ref) inVocabulary(b, source.trim(), ref, on);
            else b.leaf(Part.Kind.QUOTE, source.trim(), keys(e, on));
            return;
        }
        if (e instanceof Expr.AttributeReference ref) {
            b.text("de ");
            attributeName(b, ref.objectType(), ref.attribute(), on);
        } else if (e instanceof Expr.ParameterRef pr) {
            b.text("de ");
            declaredName(b, naming.parameter(pr.parameter().name()), Designatable.parameter(pr.parameter().name()));
        } else {
            b.text("de waarde");
        }
    }

    /** Van welk object operand {@code e} de waarde las die de zin noemt: bij een lezing van een attribuut het object
     *  waarop de motor haar las ({@link #objectOn}); null waar de zin geen gelezen waarde van een attribuut noemt: een
     *  ander operand, of een dat de motor niet bereikte. */
    private On readOn(Expr e, Read read) {
        Expr.AttributeReference ref = e instanceof Expr.AttributeReference a ? a
                : e instanceof Expr.DimensionCellRead c ? c.attribute() : null;
        if (ref == null || declarationOf(e) == null || !read.has(e) || !read.reached(e)) return null;
        return objectOn(e, ref.attribute(), read);
    }

    /** De sleutels van wat operand {@code e} leest ({@link #declaration(Designatable, On)}), bij een cel met de cel
     *  onder {@code labels}, zoals uchar haar sleutelt. */
    private Map<String, Object> keys(Expr e, On on) {
        Map<String, Object> meta = declaration(declarationOf(e), on);
        if (e instanceof Expr.DimensionCellRead c && !meta.isEmpty()) meta.put("labels", cellKey(c.attribute(), c.labels()));
        return meta;
    }

    /** Het object waarop de motor knoop {@code node}, een lezing van lid {@code member}, las: dat van de lezing
     *  (uchars RUNTIME-SEMANTICS ch. 5.12, "The object of a read"), bij een eigen lid het object van de lezing, over een
     *  rol het object dat de wandeling bereikte; zonder object waar een link op de wandeling ontbreekt. */
    private On objectOn(Object node, String member, Read read) {
        Object o = read.of(node).object();
        return new On(o == null ? null : nameOf(o, member, read));
    }

    /** De naam van object {@code o}, waarop {@code read} lid {@code member} las ({@link #labels}). */
    private String nameOf(Object o, String member, Read read) {
        String name = labels.get(o);
        if (name == null)
            throw new IllegalStateException("de lezing van '" + member + "' in " + read.form() + " op " + read.label()
                    + " staat op een " + o.getClass().getSimpleName() + " zonder naam: de uitleg noemt de objecten van"
                    + " de invoer en de gemaakte objecten van het fixpunt");
        return name;
    }

    /** De declaratie die operand {@code e} leest, waar het één leest: een attribuut, ook in een cel, of een parameter;
     *  anders null. */
    private static Designatable declarationOf(Expr e) {
        if (e instanceof Expr.AttributeReference ref) return Naming.attributeOf(ref.objectType(), ref.attribute());
        if (e instanceof Expr.DimensionCellRead c)
            return Naming.attributeOf(c.attribute().objectType(), c.attribute().attribute());
        if (e instanceof Expr.ParameterRef pr) return Designatable.parameter(pr.parameter().name());
        return null;
    }

    /** {@code tekst}, uchars spelling van lezing {@code ref} ("zijn opgegeven beëindigingsdatum"), met de naam van het
     *  gelezen attribuut via {@link Naming}: "zijn opgegeven Datum einde recht op uitkering". De lezing die uchars
     *  IR-lader leest draagt het onderwerpwoord niet, dus blijft de tekst de bron en verandert alleen de naam erin: het
     *  laatste voorkomen, want uchar schrijft de lezing als "zijn &lt;naam&gt;". */
    private void inVocabulary(Sentence.Builder b, String text, Expr.AttributeReference ref, On on) {
        Matcher m = Pattern.compile("(?iu)" + namePattern(ref.attribute())).matcher(text);
        int begin = -1, end = -1;
        while (m.find()) { begin = m.start(); end = m.end(); }
        if (begin < 0)
            throw new IllegalStateException("uchar schreef de lezing van '" + ref.attribute() + "' als '" + text
                    + "', zonder die naam: de uitleg kan haar niet in het vocabulaire noemen");
        // de tekst om de naam heen is uchars regeltekst
        if (begin > 0) b.leaf(Part.Kind.QUOTE, text.substring(0, begin));
        attributeName(b, ref.objectType(), ref.attribute(), on);
        if (end < text.length()) b.leaf(Part.Kind.QUOTE, text.substring(end));
    }

    /** Een kenmerk als gezegde: het werkwoord, wat vóór de naam staat (de ontkenning of het lidwoord) en de naam van
     *  declaratie {@code declaration}, in de volgorde van een hoofdzin ("heeft de uitkeringsduur verstreken") of een
     *  bijzin ("de uitkeringsduur verstreken heeft"). */
    private record Predicate(String verb, String before, String name, Designatable declaration) {
        /** De hoofdzin, met {@code on} het object waarvan de zin het kenmerk noemt. */
        void mainClause(Sentence.Builder b, On on) {
            b.text(verb + " " + before);
            b.leaf(Part.Kind.NAME, name, Verbaliser.declaration(declaration, on));
        }

        /** De bijzin, met {@code on} het object waarop de motor het kenmerk las. */
        void clause(Sentence.Builder b, On on) {
            b.text(before);
            b.leaf(Part.Kind.NAME, name, Verbaliser.declaration(declaration, on));
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
