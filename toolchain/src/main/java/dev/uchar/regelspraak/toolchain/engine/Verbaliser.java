package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.ir.Condition;
import dev.uchar.regelspraak.ir.Default;
import dev.uchar.regelspraak.ir.ExceptionGroup;
import dev.uchar.regelspraak.ir.Expr;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRule;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.Datatype;
import dev.uchar.regelspraak.model.Unit;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.model.RegelSpraakModel.OriginForm;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.RoleBindingRef;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.DefaultsDecision;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <p><b>Waarom uit de eindtoestand en niet uit de vuur-trace:</b> een kenmerk dat op een bean TRUE staat is het
 * enige harde bewijs dát het is toegekend; de trace telt activaties maar bindt geen waarde. De voorwaarde-
 * operanden worden hier daarom (read-only) opnieuw geëvalueerd tegen dezelfde eindtoestand die de regel ook zag —
 * de getallen die de uitleg toont zijn de getallen waarop de conclusie stoelt. Deze verbalisator VERZINT geen
 * semantiek: hij dekt de operand- en conditievormen die de IR expliciet structureert (attribuut/parameter/
 * literal/rekendatum-reads, de reken-operatoren, afronding, begrenzing, vergelijkingen, kenmerk-/rol-lidmaatschap
 * en de samengestelde alle/geen-kwantoren) en meldt een niet-gestructureerde vorm als zodanig, in plaats van te
 * gokken.
 *
 * <p><b>Lezing over een rol.</b> Een attribuut dat een operand over een rolpad leest ("de herlevingsgrens van het
 * recht van het Inkomen"), staat op een ander object dan het onderwerp. Welk object dat is, zegt uchar: de wandeling
 * van de lezing ({@link Walks}) en het object dat die wandeling vanaf de bean van het onderwerp bereikt
 * ({@link DroolsBackend#reached}), zoals de exec-model-tak de lezing las. De waarde is die op dat object, na de
 * terugschrijving de waarde die de motor afleidde; ontbreekt een link, dan is ze leeg en staat er "(onbekend)". Een
 * wandeling die de tak niet leest, weigert uchar met zijn zin, en die weigering gaat door.
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
 * besliste ({@link DroolsBackend#decisionsOf}): de variant die besliste, met haar voorwaarde zoals het IR haar
 * draagt (ch. 7.9 telt de voorwaarden van de omsluitende lagen erbij op); elke variant die vóór haar aan de beurt was
 * met de reden dat ze niet besliste; de varianten erna bij naam, als verdrongen. Beslist niemand, dan zegt de zin dat,
 * met wat het doel houdt; een conflict heet een conflict. Welke variant besliste, rekent de uitleg niet na: zij legt
 * de pointer van de motor op het document van de build ({@link Document}); alleen de reden waarom een variant niet
 * besliste komt uit de eigen evaluatie hieronder. Een variant heet naar haar blok ({@code Default.name}): de basis
 * als "de regel '…'", een variant op het eerste niveau bij haar naam alleen, want de draad legt
 * {@code Uitzondering op} en {@code Afwijking van} daar gelijk vast, en een geneste variant met de keten van haar
 * lagen.
 *
 * <p><b>Bronverwijzing.</b> Een regel draagt de {@code // Bron:}-annotaties die direct boven haar kop staan
 * ({@link ResolvedRule#statuteAnnotations}; op de draad {@code statuteAnnotations}, uchars IR-SPEC ch. 3.1, WF-082),
 * een default de zijne in {@code Default.source}. De zin van een kenmerktoekenning eindigt dan met ". Grondslag:
 * <tekst>", die van een defaults-groep met de bron van de variant die besliste; zonder annotatie staat er geen
 * (geen verzonnen bron).
 */
public final class Verbaliser {

    private final RegelSpraakModel model;
    /** De opgeloste globals die operanden kunnen lezen: parameters (op hun canonieke naam) + "rekendatum". */
    private final Map<String, Object> globals;
    /** De namen waarin het verhaal de declaraties noemt. */
    private final Naming naming;
    /** De wandeling van een lezing van {@link #model}. */
    private final Walks walks;

    /**
     * Waar de wandeling van een lezing vandaan komt (IR-SPEC ch. 4.1a): één rolbinding per hop vanaf het onderwerp
     * van de regel, leeg voor een lid van het onderwerp zelf. Het model dat de frontend resolvede, draagt de
     * geschreven navigatie op de lezing, en de wandeling is die welke uchars emitter ervoor schrijft
     * ({@link #written}). Het model dat uchars IR-lader las, draagt haar niet op de lezing maar in de tabel ernaast:
     * voor het model van een build {@code build.execModelTranspiler().navigationBindings(read)}, voor een model van
     * {@code IrLoader.loadAll} de tabel {@code navigationBindings()} van wat de lader teruggaf.
     */
    @FunctionalInterface
    public interface Walks {
        List<RoleBindingRef> of(ObjectType subject, Expr.AttributeReference read);

        /** De wandeling die uchars emitter schrijft voor een lezing van {@code model}, een model dat de frontend
         *  resolvede. */
        static Walks written(RegelSpraakModel model) {
            return (subject, read) -> IrEmitter.navigationBindings(model, subject, read);
        }
    }

    /** Een verbalisator voor een model dat de frontend resolvede, canoniek. */
    public Verbaliser(RegelSpraakModel model, Map<String, Object> globals) {
        this(model, globals, Naming.canonical(model));
    }

    /** Een verbalisator voor een model dat de frontend resolvede: de wandeling van een lezing staat op de lezing. */
    public Verbaliser(RegelSpraakModel model, Map<String, Object> globals, Naming naming) {
        this(model, globals, naming, Walks.written(model));
    }

    /** Een verbalisator die de wandeling van een lezing uit {@code walks} neemt: voor een model dat uchars IR-lader
     *  las, uit de tabel die de lader ernaast gaf. */
    public Verbaliser(RegelSpraakModel model, Map<String, Object> globals, Naming naming, Walks walks) {
        this.model = model;
        this.globals = globals == null ? Map.of() : globals;
        this.naming = naming;
        this.walks = Objects.requireNonNull(walks, "walks");
    }

    /**
     * Bouw de uitleg van de kenmerktoekenningen voor een gedraaide wereld, zonder de defaults-groepen.
     * {@code instances} mapt een LABEL (het scenario-label of het dienst-verzoek-id) op de bijbehorende, reeds gevuurde
     * bean; {@code typePerLabel} het objecttype per label. Het resultaat is één regel per uitlegzin, in een stabiele
     * volgorde (per label, in modeldeclaratie-volgorde van de regels). Een lege lijst betekent: geen
     * kenmerktoekenning raakte een van deze instanties.
     */
    public List<String> verbalise(Map<String, Object> instances, Map<String, ObjectType> typePerLabel) {
        List<String> sink = new ArrayList<>();
        for (Map.Entry<String, Object> e : instances.entrySet()) {
            String label = e.getKey();
            Object bean = e.getValue();
            ObjectType ot = typePerLabel.get(label);
            if (ot == null || bean == null) continue;
            assignments(label, bean, ot, sink);
        }
        return sink;
    }

    /**
     * {@link #verbalise(Map, Map)} met per label na de kenmerktoekenningen één zin per defaults-groep van zijn type:
     * wat de motor besliste ({@code decisions}, uit {@link DroolsBackend#decisionsOf} van dezelfde sessie, gelezen
     * vóór of na de terugschrijving), op het document van de build gelegd ({@code document}). De groepen staan in de
     * volgorde van het document.
     *
     * @throws IllegalStateException een pointer die niet op {@code document} terug te leggen is, of een beslissing over
     *                               een lid waarvoor het type van het object geen groep heeft
     */
    public List<String> verbalise(Map<String, Object> instances, Map<String, ObjectType> typePerLabel,
                                  Document document, List<DefaultsDecision> decisions) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(decisions, "decisions");
        List<String> sink = new ArrayList<>();
        for (Map.Entry<String, Object> e : instances.entrySet()) {
            String label = e.getKey();
            Object bean = e.getValue();
            ObjectType ot = typePerLabel.get(label);
            if (ot == null || bean == null) continue;
            assignments(label, bean, ot, sink);
            new DefaultsExplanation(document, label, bean, ot, decisions).sentences(sink);
        }
        return sink;
    }

    private void assignments(String label, Object bean, ObjectType ot, List<String> sink) {
        for (ResolvedRule r : model.resolvedRules) {
            if (r.kind() != ResolvedRule.Kind.KENMERKTOEKENNING) continue;
            if (!(r.target() instanceof Expr.AttributeReference ref) || ref.objectType() == null) continue;
            // IR-SPEC ch. 2.2: de regel geldt voor elk objecttype dat het doeltype IS (⊑), niet alleen voor
            // het doeltype zelf — de gegenereerde bean erft native, dus een kenmerktoekenning op het
            // supertype kent het kenmerk óók toe aan een subtype-instantie. Op naamgelijkheid vergelijken
            // laat precies die toekenningen onverklaard: het kenmerk staat AAN en het verhaal zwijgt.
            if (!ot.isA(ref.objectType())) continue;
            String sentence = verbaliseAssignment(label, bean, ref, r);
            if (sentence != null) sink.add(sentence);
        }
    }

    /**
     * Waar de pointers van {@link DroolsBackend#decisionsOf} op terug te leggen zijn: de defaults-groepen en de regels
     * van het document waaruit de build gemaakt is, in de volgorde van het document. Dat is het model waaruit uchars
     * emitter het document schreef (met een scope het model van die scope), of wat uchars lader uit het document las;
     * het moet het model zijn waarvan de verbalisator de lezingen kent ({@link Walks}). De emitter schrijft elke groep,
     * default, uitzonderingsgroep en elk lid op zijn index, en een regel als vorm met haar versies ("naam #i").
     */
    public record Document(List<PrioritizedDefaults> groups, List<ResolvedRule> rules) {
        public Document {
            groups = List.copyOf(groups);
            rules = List.copyOf(rules);
        }

        /** Het document dat uchars emitter van {@code emitted} schrijft. */
        public static Document of(RegelSpraakModel emitted) {
            return new Document(emitted.prioritizedDefaults, emitted.resolvedRules);
        }

        /** Het document zoals uchars lader het las: de groepen staan naast het model. */
        public static Document of(IrLoader.Loaded loaded) {
            return new Document(loaded.prioritizedDefaults(), loaded.model().resolvedRules);
        }
    }

    // ---------------- defaults-groepen ----------------

    private static final Pattern DEFAULT_POINTER = Pattern.compile(
            "/rules/prioritizedDefaults/([0-9]+)/defaults/([0-9]+)((?:/exceptions/members/[0-9]+/defaults/[0-9]+)*)");
    private static final Pattern NESTED_STEP = Pattern.compile("/exceptions/members/([0-9]+)/defaults/([0-9]+)");
    private static final Pattern RULE_POINTER = Pattern.compile("/rules/rules/([0-9]+)/versions/([0-9]+)");
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

        DefaultsExplanation(Document document, String label, Object bean, ObjectType type,
                            List<DefaultsDecision> decisions) {
            this.document = document;
            this.label = label;
            this.bean = bean;
            this.type = type;
            this.decisions = decisions;
        }

        void sentences(List<String> sink) {
            // per doel één stapel (ch. 7.10: per concreet type en doel), in de volgorde van de eerste groep die het
            // doel op de typeketen van het object schrijft
            Map<String, List<PrioritizedDefaults>> perMember = new LinkedHashMap<>();
            for (PrioritizedDefaults g : document.groups()) {
                ObjectType subject = subjectOf(g);
                if (type.isA(subject)) perMember.computeIfAbsent(g.target(), k -> new ArrayList<>()).add(g);
            }
            Map<String, DefaultsDecision> perDecided = new LinkedHashMap<>();
            for (DefaultsDecision d : decisions) {
                if (d.object() != bean) continue;
                if (!perMember.containsKey(d.member()))
                    throw new IllegalStateException("de motor besliste over '" + d.member() + "' van " + label
                            + ", en het document heeft voor " + type.name + " geen defaults-groep op dat lid");
                if (perDecided.put(d.member(), d) != null)
                    throw new IllegalStateException("de motor gaf twee beslissingen over '" + d.member() + "' van "
                            + label + ", waar één stapel er één geeft");
            }
            for (Map.Entry<String, List<PrioritizedDefaults>> e : perMember.entrySet()) {
                DefaultsDecision d = perDecided.get(e.getKey());
                boolean characteristic = e.getValue().get(0).isCharacteristic();
                if (d == null) sink.add(undecided(e.getKey(), characteristic, e.getValue()));
                else if (d.conflict() != null) sink.add(conflicted(d));
                else sink.add(decided(d));
            }
        }

        // ---- de drie zinnen ----

        private String decided(DefaultsDecision d) {
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
            StringBuilder b = new StringBuilder(label).append(' ')
                    .append(outcome(d.member(), d.characteristic(), d.decider().decision()))
                    .append(" volgens ").append(name(decider)).append(reason(decider, d.characteristic()));
            List<String> after = new ArrayList<>();
            for (Turn t : turns) {
                switch (t.role()) {
                    case BEFORE -> b.append("; ").append(name(t.variant())).append(' ')
                            .append(notDecided(t.variant(), d.characteristic(), d.member()));
                    case SIBLING -> b.append("; ").append(name(t.variant())).append(" besliste niet anders");
                    case AFTER -> after.add(name(t.variant()));
                    case DECIDER -> { }
                }
            }
            if (!after.isEmpty()) b.append("; verdrongen: ").append(String.join(", ", after));
            if (d.characteristic() && Boolean.FALSE.equals(d.decider().decision()) && readBoolean(bean, d.member()))
                b.append("; de invoer kende het kenmerk toe, en dat blijft staan");
            String source = groundOf(decider);
            if (source != null) b.append(". Grondslag: ").append(source);
            return b.toString();
        }

        private String conflicted(DefaultsDecision d) {
            DefaultsDecision.Conflict c = d.conflict();
            StringBuilder b = new StringBuilder(label).append(' ').append(standing(d.member(), d.characteristic()))
                    .append(", want er is een conflict");
            if (c.pointer() != null) b.append(" in de uitzonderingen op ").append(carrierName(variant(c.pointer())));
            b.append(": ");
            List<String> parts = new ArrayList<>();
            for (DefaultsDecision.Decider x : c.deciders()) {
                Variant v = variant(x.pointer());
                requireTarget(v, d.member());
                parts.add(name(v) + " besliste '" + outcome(d.member(), d.characteristic(), x.decision()) + "'");
            }
            b.append(String.join(" en ", parts)).append(", dus de groep schrijft niets");
            return b.toString();
        }

        private String undecided(String member, boolean characteristic, List<PrioritizedDefaults> groups) {
            StringBuilder b = new StringBuilder(label).append(' ').append(standing(member, characteristic))
                    .append(", want geen variant besliste");
            // Zonder beslissing geeft de motor de stapel niet. Schrijven twee typen van de keten het doel, dan stelt
            // ch. 7.10 haar samen (een eigen basis vervangt de geërfde, een gewone regel wordt basis), en de uitleg
            // stelt die semantiek niet zelf op; anders is de stapel de groepen van dat ene type, in documentvolgorde.
            if (composed(member, characteristic, groups))
                return b.append("; welke varianten de stapel van een ").append(naming.objectType(type.name))
                        .append(" samenstellen, geeft de motor zonder beslissing niet").toString();
            for (PrioritizedDefaults g : groups) {
                int i = document.groups().indexOf(g);
                List<Turn> turns = new ArrayList<>();
                for (int j = 0; j < g.defaults().size(); j++)
                    all(new Variant("/rules/prioritizedDefaults/" + i + "/defaults/" + j, g.defaults().get(j), null, g,
                            null, -1, j), Role.BEFORE, turns);
                for (Turn t : turns)
                    b.append("; ").append(name(t.variant())).append(' ')
                            .append(notDecided(t.variant(), characteristic, member));
            }
            return b.toString();
        }

        /** Of de stapel van het doel op dit object uit meer dan één type van de keten komt: groepen op twee typen, of
         *  bij een attribuut een gewone regel die het doel op een type van de keten schrijft naast een groep (ch. 7.10
         *  vouwt alleen attribuutschrijvers). */
        private boolean composed(String member, boolean characteristic, List<PrioritizedDefaults> groups) {
            Set<ObjectType> writers = Collections.newSetFromMap(new IdentityHashMap<>());
            for (PrioritizedDefaults g : groups) writers.add(subjectOf(g));
            if (!characteristic)
                for (ResolvedRule r : document.rules())
                    if (r.kind() != ResolvedRule.Kind.KENMERKTOEKENNING
                            && r.target() instanceof Expr.AttributeReference ref && member.equals(ref.attribute())
                            && ref.objectType() != null && type.isA(ref.objectType()))
                        writers.add(ref.objectType());
            return writers.size() > 1;
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
        private String outcome(String member, boolean characteristic, Object decision) {
            if (characteristic) return predicateOf(member, Boolean.TRUE.equals(decision)).mainClause();
            return "heeft als " + naming.attribute(type, member) + " " + render(decision, type.attribute(member));
        }

        /** Wat het doel houdt waar de groep niets schreef: het kenmerk zoals de invoer het gaf, de invoerwaarde of
         *  leeg. */
        private String standing(String member, boolean characteristic) {
            if (characteristic) {
                boolean present = readBoolean(bean, member);
                return predicateOf(member, present).mainClause() + (present ? " uit de invoer" : "");
            }
            Object v = readRaw(bean, Namen.getter(member));
            if (v == null) return "heeft geen " + naming.attribute(type, member);
            return "houdt als " + naming.attribute(type, member) + " de invoerwaarde "
                    + render(v, type.attribute(member));
        }

        private Predicate predicateOf(String member, boolean present) {
            // het IR draagt de doeltekst van een default niet, dus ook niet het lidwoord van een bezittelijk kenmerk
            Predicate p = predicate(type, member, present, null);
            if (p == null)
                throw new IllegalStateException("een defaults-groep beslist over '" + member + "', en " + type.name
                        + " heeft geen kenmerk met die naam: de uitleg kent de soort niet");
            return p;
        }

        /** Waarom de beslisser besliste: haar voorwaarde met de waarden van de operanden. */
        private String reason(Variant v, boolean characteristic) {
            Condition c = conditionOf(v);
            if (c == null && isInitialisation(v))
                return ", omdat " + (characteristic ? "het kenmerk" : "de " + naming.attribute(type, targetOf(v)))
                        + " in de invoer leeg was";
            if (c == null || c instanceof Condition.Compound s && s.quantifier() == Condition.Quantifier.ALLE
                    && s.components().isEmpty()) return " (onvoorwaardelijk)";
            return ", omdat " + verbaliseCondition(c, bean, subjectOf(v), ucharText(v));
        }

        /** Waarom een variant die aan de beurt was niet besliste: een kenmerkvariant waarvan de voorwaarde geldt,
         *  beslist altijd, dus gold haar voorwaarde niet; een attribuutvariant beslist ook niet waar haar waarde leeg
         *  is (ch. 7.1). */
        private String notDecided(Variant v, boolean characteristic, String member) {
            Condition c = conditionOf(v);
            ObjectType subject = subjectOf(v);
            if (c == null && isInitialisation(v))
                return readRaw(bean, Namen.getter(member)) != null
                        ? "gold niet, want de " + naming.attribute(type, member) + " had een invoerwaarde"
                        : "gaf geen waarde";
            if (!characteristic && (c == null || Boolean.TRUE.equals(holds(c, subject)))) return "gaf geen waarde";
            if (c == null)
                return "gold niet, want de conclusie niet is bereikt (mogelijk een regel buiten deze berekening)";
            return "gold niet, want " + firstFailingConjunct(c, bean, subject, ucharText(v));
        }

        /** Of voorwaarde {@code c} in de eindtoestand geldt: een atoom zoals {@link #evaluate} het geeft, een
         *  "alle"-samenstelling als elk deel geldt; null waar dat niet vast te stellen is. */
        private Boolean holds(Condition c, ObjectType subject) {
            if (c instanceof Condition.Compound s && s.quantifier() == Condition.Quantifier.ALLE) {
                Boolean all = Boolean.TRUE;
                for (Condition part : s.components()) {
                    Boolean h = evaluate(part, bean, subject);
                    if (Boolean.FALSE.equals(h)) return false;
                    if (h == null) all = null;
                }
                return all;
            }
            return evaluate(c, bean, subject);
        }

        // ---- namen ----

        /** De naam van een variant: de basis als "de regel '…'", een variant aan de wortel bij haar naam alleen, een
         *  geneste met de keten van haar lagen; zonder naam bij de opgavemarkering haar bron, anders haar plaats. */
        private String name(Variant v) {
            if (v.rule() != null) return "de regel '" + RegelSpraakModel.formName(v.rule().name()) + "'";
            String name = v.d().name();
            if (name == null) {
                if (generatedOpgave(v)) return "'" + naming.attribute(subjectOf(v), v.d().source()) + "'";
                if (v.holder() == null)
                    return "de variant op plaats " + (v.place() + 1) + " van " + groupLabel(v.group());
                return "de variant op plaats " + (v.place() + 1) + " van uitzonderingsgroep " + (v.member() + 1)
                        + " van " + name(v.holder());
            }
            if (v.holder() == null)
                return name.equals(v.group().name()) ? "de regel '" + name + "'" : "'" + name + "'";
            List<String> layers = new ArrayList<>();
            for (Variant h = v.holder(); h != null; h = h.holder())
                if (h.d().name() != null) layers.add("'" + h.d().name() + "'");
            // de groep-default van 'B voor S' draagt de blokken die op het eerste niveau van B voor subtype S staan
            if (layers.isEmpty() && forSubtype(v.group())) return "'" + name + "'";
            String basis = basisOf(v.group());
            if (basis != null && (layers.isEmpty() || !layers.get(layers.size() - 1).equals("'" + basis + "'")))
                layers.add("'" + basis + "'");
            return layers.isEmpty() ? "'" + name + "'"
                    : "'" + name + "' (uitzondering op " + String.join(" op ", layers) + ")";
        }

        /** De drager van uitzonderingsgroepen bij naam. Een groep-default zonder naam aan de wortel maakte de kop
         *  {@code Uitzonderingen ongeordend op B}, of uchar voor de blokken van een subtype onder B: zijn leden zijn
         *  uitzonderingen op de basis. */
        private String carrierName(Variant v) {
            if (v.rule() == null && v.d().name() == null && !v.d().hasOwnContent() && v.holder() == null
                    && basisOf(v.group()) != null) return "de regel '" + basisOf(v.group()) + "'";
            return name(v);
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

        private String groupLabel(PrioritizedDefaults g) {
            return g.name() != null ? "'" + g.name() + "'" : "de groep op " + g.subject() + "." + g.target();
        }

        // ---- het model ----

        /** De variant op {@code pointer} in het document; faalt luid op een pointer die er niet op terug te leggen
         *  is. */
        private Variant variant(String pointer) {
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
            if (rm.matches()) return ruleVariant(pointer, Integer.parseInt(rm.group(1)), Integer.parseInt(rm.group(2)));
            throw unresolved(pointer, "dat is geen pointer naar een default of een regelversie");
        }

        /** De regelversie {@code /rules/rules/k/versions/v}: het document schrijft de regels als vormen, een vorm met
         *  meer versies als de opeenvolgende regels "… #1" tot en met "… #n" van het model, zoals uchar de pointer
         *  maakt. */
        private Variant ruleVariant(String pointer, int form, int version) {
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

        private IllegalStateException unresolved(String pointer, String why) {
            return new IllegalStateException("de uitleg legt de pointer " + pointer + " van de motor niet op het"
                    + " document terug: " + why);
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

        /** De grondslag van de beslisser: de bron die het IR haar geeft. Een default die de opgavemarkering maakte,
         *  draagt in zijn bron zijn naam; een regel draagt haar bron in haar annotaties. */
        private String groundOf(Variant v) {
            if (v.rule() != null) return sourceReference(v.rule());
            if (generatedOpgave(v)) return null;
            String s = v.d().source();
            return s == null || s.isBlank() ? null : s.trim();
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

    /** Of {@code name} de versie na die van {@code previous} noemt, van één vorm. */
    private static boolean nextVersion(String previous, String name) {
        Matcher a = VERSION.matcher(previous), b = VERSION.matcher(name);
        return a.matches() && b.matches() && a.group(1).equals(b.group(1))
                && Integer.parseInt(b.group(2)) == Integer.parseInt(a.group(2)) + 1;
    }

    /** Één kenmerktoekenningsregel voor één instantie: is het kenmerk toegekend → "waarom (voorwaarden)"; is het
     *  met een voorwaarde NIET toegekend → "niet, want <eerste falende conjunct>"; onvoorwaardelijk-afwezig → geen
     *  zin (er valt niets te verantwoorden). */
    private String verbaliseAssignment(String label, Object bean, Expr.AttributeReference ref, ResolvedRule r) {
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
        StringBuilder b = new StringBuilder(label).append(' ').append(predicate.mainClause());
        if (present) {
            if (v != null) b.append(", omdat ").append(verbaliseCondition(v, bean, subject, ucharText));
            else b.append(" (onvoorwaardelijk toegekend)");
        } else {
            b.append(", want ").append(firstFailingConjunct(v, bean, subject, ucharText));
        }
        String source = sourceReference(r);
        if (source != null) b.append(". Grondslag: ").append(source);
        return b.toString();
    }

    /** Of uchar de tekst van regel {@code r} schreef: een regel van de opgave-familie, volgens uchars tabel van
     *  herkomst. Een beslistabelrij maakte uchar ook, maar uit de kolomkoppen en cellen van de auteur; die tekst is van
     *  de auteur. */
    private boolean writtenByUchar(ResolvedRule r) {
        return model.generatedFrom(Designatable.form(RegelSpraakModel.formName(r.name())))
                .map(g -> g.form() == OriginForm.OPGAVE_BOVEN_BEREKENING).orElse(false);
    }

    // ---------------- voorwaarde-verbalisatie ----------------

    /** Een voorwaarde in proza, met de geëvalueerde operandwaarden: WAAROM ze geldt (het toegekende geval); de
     *  niet-toegekende tak gaat via {@link #firstFailingConjunct}. {@code subject} is het objecttype van de regel,
     *  vanwaar een kenmerk- of rolnaam in de voorwaarde gelezen wordt; {@code ucharText} of uchar de regel schreef. */
    private String verbaliseCondition(Condition c, Object bean, ObjectType subject, boolean ucharText) {
        if (c instanceof Condition.Compound s) {
            List<String> parts = new ArrayList<>();
            for (Condition d : s.components()) parts.add(verbaliseCondition(d, bean, subject, ucharText));
            String conjunction = switch (s.quantifier()) {
                case ALLE -> " en ";
                case GEEN -> " en niet ";
                default -> " of ";
            };
            return String.join(conjunction, parts);
        }
        return verbaliseAtom(c, bean, subject, ucharText);
    }

    /** Een enkele (niet-samengestelde) conditie als proza met geëvalueerde operanden. */
    private String verbaliseAtom(Condition c, Object bean, ObjectType subject, boolean ucharText) {
        if (c instanceof Condition.Comparison vg) {
            return operandProse(vg.left(), bean, subject, ucharText) + " " + operatorWords(vg.op()) + " "
                    + operandProse(vg.right(), bean, subject, ucharText);
        }
        if (c instanceof Condition.CharacteristicCheck k) {
            ObjectType holder = k.navigation() == null ? subject
                    : model.roleTargetTypeToSubject(subject, k.navigation());
            Predicate predicate = predicate(holder, k.characteristic(), !k.negation(), summary(c));
            // zonder soort geen werkwoord: dan de voorwaarde zoals de regel haar schrijft, zoals hieronder
            if (predicate == null) return "aan de voorwaarde \"" + summary(c) + "\" is voldaan";
            String who = k.navigation() == null ? "het onderwerp" : "de " + naming.roleFrom(subject, k.navigation());
            return who + " " + predicate.clause();
        }
        if (c instanceof Condition.RoleMembership rl)
            return "het onderwerp " + (rl.negation() ? "geen " : "een ") + naming.roleFulfilled(subject, rl.role())
                    + " is";
        if (c instanceof Condition.Predicate p) {
            String core = switch (p.kind()) {
                case LEEG -> "leeg is";
                case GEVULD -> "gevuld is";
                default -> "voldoet aan " + p.kind().name().toLowerCase(Locale.ROOT);
            };
            return operandProse(p.operand(), bean, subject, ucharText) + " " + core;
        }
        // een nog niet gestructureerde conditievorm: eerlijk de brontekst tonen, geen gok.
        return "aan de voorwaarde \"" + c.source().snippet().trim() + "\" is voldaan";
    }

    /** Voor een "alle volgende voorwaarden"-regel die NIET vuurde: de EERSTE conjunct die (na evaluatie van zijn
     *  operanden tegen de eindtoestand) niet houdt — de reden dat de toekenning uitbleef. Voor een niet-
     *  samengestelde voorwaarde is dat de voorwaarde zelf. Kan een conjunct niet worden geëvalueerd (een
     *  onopgeloste vorm, een niet-leesbaar operand), dan noemt hij die eerlijk als "niet vast te stellen". */
    private String firstFailingConjunct(Condition c, Object bean, ObjectType subject, boolean ucharText) {
        if (c instanceof Condition.Compound s && s.quantifier() == Condition.Quantifier.ALLE) {
            for (Condition d : s.components()) {
                Boolean holds = evaluate(d, bean, subject);
                if (holds == null) return "de voorwaarde \"" + summary(d) + "\" niet is vast te stellen";
                if (!holds) return "niet geldt: " + verbaliseAtom(d, bean, subject, ucharText);
            }
            // alle conjuncten houden maar het kenmerk staat toch niet aan: een producent buiten deze run.
            return "de conclusie niet is bereikt (mogelijk een regel buiten deze berekening)";
        }
        Boolean holds = evaluate(c, bean, subject);
        if (Boolean.FALSE.equals(holds)) return "niet geldt: " + verbaliseAtom(c, bean, subject, ucharText);
        if (holds == null) return "de voorwaarde \"" + summary(c) + "\" niet is vast te stellen";
        return "de conclusie niet is bereikt (mogelijk een regel buiten deze berekening)";
    }

    private static String summary(Condition c) { return c.source() == null ? "?" : c.source().snippet().trim(); }

    // ---------------- read-only conditie-evaluatie ----------------

    /** Evalueer een conditie tegen de eindtoestand. null = niet vast te stellen (onopgeloste vorm / onleesbaar
     *  operand); geen gok. Alleen de vormen die de "eerste falende conjunct" nodig heeft. */
    private Boolean evaluate(Condition c, Object bean, ObjectType subject) {
        if (c instanceof Condition.Comparison vg) {
            Object l = evalExpr(vg.left(), bean, subject);
            Object r = evalExpr(vg.right(), bean, subject);
            if (l == null || r == null) return null;
            return compare(vg.op(), l, r);
        }
        if (c instanceof Condition.CharacteristicCheck k && k.navigation() == null) {
            Object v = readRaw(bean, Namen.getter(k.characteristic()));
            boolean enabled = Boolean.TRUE.equals(v);
            return k.negation() != enabled;   // negatie XOR aanwezig
        }
        if (c instanceof Condition.Predicate p) {
            Object v = evalExpr(p.operand(), bean, subject);
            return switch (p.kind()) {
                case LEEG -> v == null;
                case GEVULD -> v != null;
                default -> null;
            };
        }
        return null;
    }

    private Boolean compare(Condition.Operator op, Object l, Object r) {
        int c;
        if (l instanceof BigDecimal a && r instanceof BigDecimal b) c = a.compareTo(b);
        else if (l instanceof LocalDate a && r instanceof LocalDate b) c = a.compareTo(b);
        else if (l instanceof LocalDateTime a && r instanceof LocalDateTime b) c = a.compareTo(b);
        else if (op == Condition.Operator.GELIJK || op == Condition.Operator.ONGELIJK) {
            boolean equal = l.equals(r);
            return op == Condition.Operator.GELIJK ? equal : !equal;
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
     *  reken-operatoren, afronding en begrenzing. {@code subject} is het objecttype van de regel, waarvandaan een
     *  lezing over een rol wandelt. */
    private Object evalExpr(Expr e, Object bean, ObjectType subject) {
        if (e == null) return null;
        if (e instanceof Expr.Literal lit) return literalValue(lit);
        if (e instanceof Expr.ParameterRef pr) return globals.get(Namen.ident(pr.parameter().name(), false));
        if (e instanceof Expr.Constant ct) {
            if ("Rekendatum".equals(ct.name())) return globals.get("rekendatum");
            return null;
        }
        if (e instanceof Expr.AttributeReference ref) {
            Object holder = DroolsBackend.reached(model, bean, subject, ref, walks.of(subject, ref));
            return holder == null ? null : readRaw(holder, Namen.getter(ref.attribute()));
        }
        if (e instanceof Expr.Binary bin) {
            Object l = evalExpr(bin.left(), bean, subject);
            Object r = evalExpr(bin.right(), bean, subject);
            if (l instanceof BigDecimal a && r instanceof BigDecimal b) return compute(bin.op(), a, b);
            return null;
        }
        if (e instanceof Expr.Rounding rounded) {
            Object base = evalExpr(rounded.base(), bean, subject);
            if (base instanceof BigDecimal a) return a.setScale(rounded.decimals(), roundingMode(rounded.mode()));
            return null;
        }
        if (e instanceof Expr.Clamp bg) {
            Object base = evalExpr(bg.base(), bean, subject);
            if (!(base instanceof BigDecimal a)) return null;
            Object min = evalExpr(bg.minimum(), bean, subject);
            Object max = evalExpr(bg.maximum(), bean, subject);
            if (min instanceof BigDecimal lo && a.compareTo(lo) < 0) a = lo;
            if (max instanceof BigDecimal hi && a.compareTo(hi) > 0) a = hi;
            return a;
        }
        return null;   // aggregaties, tijdsduren, teksreeksen: niet zonder verdere sessie te bepalen
    }

    private static Object compute(Expr.BinOp op, BigDecimal a, BigDecimal b) {
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

    private static java.math.RoundingMode roundingMode(Expr.RoundingMode m) {
        return switch (m) {
            case NAAR_BENEDEN -> java.math.RoundingMode.FLOOR;
            case NAAR_BOVEN -> java.math.RoundingMode.CEILING;
            case REKENKUNDIG -> java.math.RoundingMode.HALF_UP;
            case RICHTING_NUL -> java.math.RoundingMode.DOWN;
            case WEG_VAN_NUL -> java.math.RoundingMode.UP;
        };
    }

    private static Object literalValue(Expr.Literal lit) {
        Datatype t = lit.type();
        String w = lit.valueText();
        try {
            if (t.isNumberLike()) return parseNumber(w);
            if (t instanceof Datatype.BooleanType) return "waar".equalsIgnoreCase(w) || Boolean.parseBoolean(w);
            if (t instanceof Datatype.DateInDays) return LocalDate.parse(w);
            if (t instanceof Datatype.DateTime) return LocalDateTime.parse(w);
        } catch (RuntimeException unparsable) { /* val terug op de ruwe tekst */ }
        return w;
    }

    /** Parse een getal-literal naar {@link BigDecimal}. Een §13.4.17-getalliteral draagt zijn eenheid IN de
     *  waarde-tekst ("100 km", "2.450,00 EUR"): de eenheid staat na een spatie, dus we nemen het eerste token en
     *  vertalen de NL-notatie (punt = duizendtal, komma = decimaal). Gooit bij een niet-numeriek token. */
    private static BigDecimal parseNumber(String w) {
        String token = w.trim().split("\\s+", 2)[0];        // strip de eenheid-suffix ("100 km" -> "100")
        String normalised = token.replace(".", "").replace(",", ".");   // NL -> BigDecimal-notatie
        return new BigDecimal(normalised);
    }

    // ---------------- rendering ----------------

    /** Een operandwaarde in NL-notatie, met eenheid waar de IR die kent; "onbekend" wanneer niet leesbaar. */
    private String renderOperand(Expr e, Object bean, ObjectType subject) {
        Object v = evalExpr(e, bean, subject);
        if (v == null) return "onbekend";
        Datatype.Enumeration enumArray = enumerationOf(e);
        return enumArray != null ? render(v, enumArray) : renderValue(v, e.type());
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
     *  géén verdubbeling van de brontekst; elk ander operand toont zijn omschrijving mét de geëvalueerde waarde
     *  tussen haakjes ("de maandloon nieuw van EOver (3.000,00 EUR)"), zodat de jurist de norm én het feit ziet. */
    private String operandProse(Expr e, Object bean, ObjectType subject, boolean ucharText) {
        if (e instanceof Expr.Literal) return renderOperand(e, bean, subject);
        return describe(e, ucharText) + " (" + renderOperand(e, bean, subject) + ")";
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
    private String describe(Expr e, boolean ucharText) {
        if (e == null) return "de waarde";
        String source = e.source().snippet();
        if (source != null && !source.isBlank()) {
            if (ucharText && e instanceof Expr.AttributeReference ref) return inVocabulary(source.trim(), ref);
            return source.trim();
        }
        if (e instanceof Expr.AttributeReference ref) return "de " + naming.attribute(ref.objectType(), ref.attribute());
        if (e instanceof Expr.ParameterRef pr) return "de " + naming.parameter(pr.parameter().name());
        return "de waarde";
    }

    /** {@code tekst}, uchars spelling van lezing {@code ref} ("zijn opgegeven beëindigingsdatum"), met de naam van het
     *  gelezen attribuut via {@link Naming}: "zijn opgegeven Datum einde recht op uitkering". De lezing die uchars
     *  IR-lader leest draagt het onderwerpwoord niet, dus blijft de tekst de bron en verandert alleen de naam erin: het
     *  laatste voorkomen, want uchar schrijft de lezing als "zijn &lt;naam&gt;". */
    private String inVocabulary(String text, Expr.AttributeReference ref) {
        Matcher m = Pattern.compile("(?iu)" + namePattern(ref.attribute())).matcher(text);
        int begin = -1, end = -1;
        while (m.find()) { begin = m.start(); end = m.end(); }
        if (begin < 0)
            throw new IllegalStateException("uchar schreef de lezing van '" + ref.attribute() + "' als '" + text
                    + "', zonder die naam: de uitleg kan haar niet in het vocabulaire noemen");
        return text.substring(0, begin) + naming.attribute(ref.objectType(), ref.attribute())
                + text.substring(end);
    }

    /** Een kenmerk als gezegde: het werkwoord en wat erbij hoort, in de volgorde van een hoofdzin ("heeft de
     *  uitkeringsduur verstreken") of een bijzin ("de uitkeringsduur verstreken heeft"). */
    private record Predicate(String verb, String namePart) {
        String mainClause() { return verb + " " + namePart; }
        String clause() { return namePart + " " + verb; }
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
        return switch (kind) {
            case BIJVOEGLIJK -> new Predicate("is", present ? name : "niet " + name);
            case ZELFSTANDIG -> new Predicate("is", (present ? "een " : "geen ") + name);
            case BEZITTELIJK -> {
                String article = present ? articleBefore(ruleText, characteristic) : "geen";
                yield new Predicate("heeft", article == null ? name : article + " " + name);
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

    /** De bronverwijzing van een regel: haar niet-lege {@code // Bron:}-annotaties in bronvolgorde, verbonden met
     *  "; ", zoals uchars frontend de annotaties van een defaults-blok in {@code Default.source} schrijft; null
     *  zonder annotatie. Een lege annotatie valt weg omdat het model van de frontend haar houdt en de draad niet
     *  (WF-082): zo geven de route uit bronnen en die uit een IR-document dezelfde tekst. */
    private static String sourceReference(ResolvedRule r) {
        List<String> filled = Objects.requireNonNull(r.statuteAnnotations(),
                        () -> "de regel '" + r.name() + "' draagt geen lijst van bronannotaties")
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return filled.isEmpty() ? null : String.join("; ", filled);
    }

    /** Toon de opgeloste globals in modeldeclaratie-volgorde als bijlage-context (parameters + rekendatum),
     *  handig onder een uitleg zodat de gebruiker de gehanteerde normwaarden ziet. Leeg wanneer er geen zijn. */
    public List<String> globalContext() {
        List<String> sink = new ArrayList<>();
        Map<String, RegelSpraakModel.Parameter> perGlobal = new LinkedHashMap<>();
        for (RegelSpraakModel.Parameter p : model.parameters.values())
            perGlobal.put(Namen.ident(p.name(), false), p);
        for (Map.Entry<String, Object> g : globals.entrySet()) {
            if ("rekendatum".equals(g.getKey())) {
                sink.add("rekendatum: " + renderValue(g.getValue(), new Datatype.DateInDays()));
                continue;
            }
            RegelSpraakModel.Parameter p = perGlobal.get(g.getKey());
            if (p != null) sink.add("de " + naming.parameter(p.name()) + ": " + render(g.getValue(), p.datatype()));
        }
        return sink;
    }
}
