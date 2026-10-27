package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card explanation-computed-attributes: every value an ordinary rule of the document writes on an object of the
 * session gets one sentence, per object and attribute over all its writers, after the sentences of the defaults
 * groups: which rule held, the value, how the rule computed it (the rule text, every value the computation read or
 * summed with the value of its node, the members of a fold with what each contributed, the value before a rounding and
 * before a bound), why it held, and why the other writers did not write. The four forms of the advice (B2): a rule
 * held and gave a value (A), a rule held and gave none (B), no rule held and the target is empty (C), no rule held and
 * the target keeps an input value (D). Every value comes from the engine's readings ({@code DroolsBackend.readingsOf}):
 * the reading of a rule on an attribute carries the nodes of its value, and since uchar's card
 * readings-of-unconditional-forms also where the rule has no condition. Both starts of the service; the scenario route
 * is pinned in {@code ExplanationComputationTest}, with the same model and the same sentences.
 *
 * <p>The model: on a Vondst the vindersloon (a percentage of its waarde, rounded down, with a condition and a ground),
 * the wachttijd (a duration), the jutterstartdag (a value through a role), and the characteristic waardevol; on a
 * Jutter the meldtermijn (a date plus a duration), the eerste waardevolle vinddag (the first of a fold whose filter is a
 * characteristic), the vondstwaarde (a sum over a role), the premie (a binder of {@code Daarbij geldt}, a parameter and
 * a maximum, with a condition and a ground), the bonus (a rule with a condition and an initialisation, displaced or at
 * its turn), the toelage (a read of an attribute nothing writes, which holds and gives no value), and the havengeld (a
 * rule of two versions, the first outside its validity).
 */
class ExplanationComputationServiceTest {

    static final String SOURCE = """
            Parameter het percentage vindersloon : Percentage (getal met 1 decimalen)
            Parameter het premiemaximum : Numeriek (getal met 2 decimalen)

            Objecttype de Jutter (mv: Jutters) (bezield)
                de startdag Datum in dagen;
                de meldtermijn Datum in dagen;
                de eerste waardevolle vinddag Datum in dagen;
                de vondstwaarde Numeriek (getal met 2 decimalen);
                de premie Numeriek (getal met 2 decimalen);
                de bonus Numeriek (getal met 2 decimalen);
                het tarief Numeriek (getal met 2 decimalen);
                de toelage Numeriek (getal met 2 decimalen);
                het havengeld Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                de vinddag Datum in dagen;
                de meldingsdag Datum in dagen;
                de waarde Numeriek (getal met 2 decimalen);
                het vindersloon Numeriek (getal met 2 decimalen);
                de wachttijd Numeriek (geheel getal) met eenheid dagen;
                de jutterstartdag Datum in dagen;
                is waardevol kenmerk (bijvoeglijk);

            Feittype JutterHeeftVondst
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel waardevol
                geldig altijd
                    Een Vondst is waardevol
                    indien zijn waarde groter is dan 100,00.

            // Bron: Jutterswet art. 10
            Regel vindersloon
                geldig altijd
                    Het vindersloon van een Vondst moet berekend worden als (het percentage vindersloon van zijn waarde) naar beneden afgerond op 2 decimalen
                    indien zijn waarde groter is dan 0.

            Regel wachttijd
                geldig altijd
                    De wachttijd van een Vondst moet berekend worden als de tijdsduur van zijn vinddag tot zijn meldingsdag in hele dagen.

            Regel jutterstartdag
                geldig altijd
                    De jutterstartdag van een Vondst moet gesteld worden op de startdag van de jutter van de Vondst.

            Regel meldtermijn
                geldig altijd
                    De meldtermijn van een Jutter moet berekend worden als zijn startdag plus 14 dagen.

            Regel eerste waardevolle vinddag
                geldig altijd
                    De eerste waardevolle vinddag van een Jutter moet berekend worden als de eerste van de vinddag van alle vondsten van de Jutter die waardevol zijn.

            Regel vondstwaarde
                geldig altijd
                    De vondstwaarde van een Jutter moet berekend worden als de som van de waarde van alle vondsten van de Jutter.

            // Bron: Jutterswet art. 11
            Regel premie
                geldig altijd
                    De premie van een Jutter moet berekend worden als de grondpremie maal 2, met een maximum van het premiemaximum
                    indien hij ingezetene is.
                    Daarbij geldt:
                        de grondpremie is de vondstwaarde van de Jutter gedeeld door 10.

            Regel bonus voor ingezetenen
                geldig altijd
                    De bonus van een Jutter moet gesteld worden op 25,00
                    indien hij ingezetene is.

            Regel bonus zonder voorwaarde
                geldig altijd
                    De bonus van een Jutter moet geïnitialiseerd worden op 5,00.

            Regel toelage
                geldig altijd
                    De toelage van een Jutter moet gesteld worden op zijn tarief
                    indien hij ingezetene is.

            Regel havengeld
                geldig vanaf 2015 t/m 2020
                    Het havengeld van een Jutter moet gesteld worden op 1,00.
                geldig vanaf 2021
                    Het havengeld van een Jutter moet gesteld worden op 2,00.
            """;

    private static final String RULES = "\"waardevol\", \"vindersloon\", \"wachttijd\", \"jutterstartdag\","
            + " \"meldtermijn\", \"eerste waardevolle vinddag\", \"vondstwaarde\", \"premie\", \"bonus voor ingezetenen\","
            + " \"bonus zonder voorwaarde\", \"toelage\", \"havengeld\"";

    private static final String DESCRIPTOR = """
            { "service": {"name":"Uitleg van berekende waarden","version":"1"},
              "entrypoints": [ { "name":"jutters","path":"jutters",
                "scope": {"ruleNames": [%s]},
                "input": ["Jutter", "Vondst"],
                "output": ["Jutter.meldtermijn", "Jutter.premie", "Jutter.bonus", "Vondst.vindersloon"] } ],
              "parameters": { "percentage vindersloon": 12.5, "premiemaximum": 50.00 } }""";

    /** J1 ingezetene with two waardevolle vondsten; J2 not, with one vondst of waarde 0; J3 not, without vondsten and
     *  startdag, with an input bonus and toelage. */
    private static final String REQUEST = """
            { "asOfDate": "2026-06-01",
              "instances": [
                { "type": "Jutter", "id": "J1", "attributes": { "startdag": "2026-01-05" },
                  "characteristics": { "ingezetene": true } },
                { "type": "Jutter", "id": "J2", "attributes": { "startdag": "2026-02-01" } },
                { "type": "Jutter", "id": "J3", "attributes": { "bonus": 7.50, "toelage": 3.00 } },
                { "type": "Vondst", "id": "V1", "roles": { "jutter": "J1" },
                  "attributes": { "waarde": 123.45, "vinddag": "2026-02-01", "meldingsdag": "2026-02-11" } },
                { "type": "Vondst", "id": "V2", "roles": { "jutter": "J1" },
                  "attributes": { "waarde": 150.00, "vinddag": "2026-03-01", "meldingsdag": "2026-03-04" } },
                { "type": "Vondst", "id": "V3", "roles": { "jutter": "J2" },
                  "attributes": { "waarde": 0.00, "vinddag": "2026-02-10", "meldingsdag": "2026-02-10" } } ] }""";

    private static final String FIRST = "de eerste van de vinddag van alle vondsten van de Jutter die waardevol zijn";
    private static final String SUM = "de som van de waarde van alle vondsten van de Jutter";
    private static final String PREMIE = "de grondpremie maal 2, met een maximum van het premiemaximum";
    private static final String HAVENGELD = "volgens de regel 'havengeld' (onvoorwaardelijk): 2,00; de regel"
            + " 'havengeld' gold niet, want zij valt op de rekendatum buiten haar geldigheid";
    private static final String VINDERSLOON = "(het percentage vindersloon van zijn waarde) naar beneden afgerond op 2"
            + " decimalen";

    static final List<String> EXPECTED = List.of(
            "J1 heeft als meldtermijn 19-01-2026 volgens de regel 'meldtermijn' (onvoorwaardelijk): zijn startdag plus"
                    + " 14 dagen, met zijn startdag (05-01-2026)",
            "J1 heeft als eerste waardevolle vinddag 01-02-2026 volgens de regel 'eerste waardevolle vinddag'"
                    + " (onvoorwaardelijk): " + FIRST + " (01-02-2026): over V1 (01-02-2026) en V2 (01-03-2026)"
                    + " (2 leden)",
            "J1 heeft als vondstwaarde 273,45 volgens de regel 'vondstwaarde' (onvoorwaardelijk): " + SUM + " (273,45):"
                    + " over V1 (123,45) en V2 (150,00) (2 leden)",
            "J1 heeft als premie 50,00 volgens de regel 'premie': " + PREMIE + ", met de vondstwaarde van de Jutter"
                    + " (273,45), de grondpremie is de vondstwaarde van de Jutter gedeeld door 10 (27,345) en het"
                    + " premiemaximum (50,00); vóór de begrenzing 54,69, omdat"
                    + " het onderwerp ingezetene is. Grondslag: Jutterswet art. 11",
            "J1 heeft als bonus 25,00 volgens de regel 'bonus voor ingezetenen': 25,00, omdat het onderwerp ingezetene"
                    + " is; verdrongen: de regel 'bonus zonder voorwaarde'",
            "J1 heeft geen toelage, want de regel 'toelage' gaf geen waarde: zijn tarief (onbekend), omdat het"
                    + " onderwerp ingezetene is",
            "J1 heeft als havengeld 2,00 " + HAVENGELD,
            "J2 heeft als meldtermijn 15-02-2026 volgens de regel 'meldtermijn' (onvoorwaardelijk): zijn startdag plus"
                    + " 14 dagen, met zijn startdag (01-02-2026)",
            "J2 heeft geen eerste waardevolle vinddag, want de regel 'eerste waardevolle vinddag' gaf geen waarde"
                    + " (onvoorwaardelijk): " + FIRST + " (onbekend): er is geen enkel lid",
            "J2 heeft als vondstwaarde 0,00 volgens de regel 'vondstwaarde' (onvoorwaardelijk): " + SUM + " (0,00):"
                    + " over V3 (0,00), het enige lid",
            "J2 heeft geen premie, want geen regel gold; de regel 'premie' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J2 heeft als bonus 5,00 volgens de regel 'bonus zonder voorwaarde': 5,00, omdat de bonus in de invoer leeg"
                    + " was; de regel 'bonus voor ingezetenen' gold niet, want niet geldt: het onderwerp ingezetene is",
            "J2 heeft geen toelage, want geen regel gold; de regel 'toelage' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J2 heeft als havengeld 2,00 " + HAVENGELD,
            "J3 heeft geen meldtermijn, want de regel 'meldtermijn' gaf geen waarde (onvoorwaardelijk): zijn startdag"
                    + " plus 14 dagen, met zijn startdag (onbekend)",
            "J3 heeft geen eerste waardevolle vinddag, want de regel 'eerste waardevolle vinddag' gaf geen waarde"
                    + " (onvoorwaardelijk): " + FIRST + " (onbekend): er is geen enkel lid",
            "J3 heeft geen vondstwaarde, want de regel 'vondstwaarde' gaf geen waarde (onvoorwaardelijk): " + SUM
                    + " (onbekend): er is geen enkel lid",
            "J3 heeft geen premie, want geen regel gold; de regel 'premie' gold niet, want niet geldt: het onderwerp"
                    + " ingezetene is",
            "J3 houdt als bonus de invoerwaarde 7,50, want geen regel gold; de regel 'bonus voor ingezetenen' gold"
                    + " niet, want niet geldt: het onderwerp ingezetene is; verdrongen: de regel 'bonus zonder"
                    + " voorwaarde'",
            "J3 houdt als toelage de invoerwaarde 3,00, want geen regel gold; de regel 'toelage' gold niet, want niet"
                    + " geldt: het onderwerp ingezetene is",
            "J3 heeft als havengeld 2,00 " + HAVENGELD,
            "V1 is waardevol, omdat zijn waarde (123,45) is groter dan 100,00",
            "V1 heeft als vindersloon 15,43 volgens de regel 'vindersloon': " + VINDERSLOON + ", met percentage"
                    + " vindersloon (12,5 %) en zijn waarde (123,45); vóór de afronding 15,43125, omdat zijn waarde"
                    + " (123,45) is groter dan 0. Grondslag: Jutterswet art. 10",
            "V1 heeft als wachttijd 10 dag volgens de regel 'wachttijd' (onvoorwaardelijk): de tijdsduur van zijn vinddag"
                    + " tot zijn meldingsdag in hele dagen, met zijn vinddag (01-02-2026) en zijn meldingsdag"
                    + " (11-02-2026)",
            "V1 heeft als jutterstartdag 05-01-2026 volgens de regel 'jutterstartdag' (onvoorwaardelijk): de startdag van"
                    + " de jutter van de Vondst (05-01-2026)",
            "V2 is waardevol, omdat zijn waarde (150,00) is groter dan 100,00",
            "V2 heeft als vindersloon 18,75 volgens de regel 'vindersloon': " + VINDERSLOON + ", met percentage"
                    + " vindersloon (12,5 %) en zijn waarde (150,00); vóór de afronding 18,75, omdat zijn waarde"
                    + " (150,00) is groter dan 0. Grondslag: Jutterswet art. 10",
            "V2 heeft als wachttijd 3 dag volgens de regel 'wachttijd' (onvoorwaardelijk): de tijdsduur van zijn vinddag"
                    + " tot zijn meldingsdag in hele dagen, met zijn vinddag (01-03-2026) en zijn meldingsdag"
                    + " (04-03-2026)",
            "V2 heeft als jutterstartdag 05-01-2026 volgens de regel 'jutterstartdag' (onvoorwaardelijk): de startdag van"
                    + " de jutter van de Vondst (05-01-2026)",
            "V3 is niet waardevol, want niet geldt: zijn waarde (0,00) is groter dan 100,00",
            "V3 heeft geen vindersloon, want geen regel gold; de regel 'vindersloon' gold niet, want niet geldt: zijn"
                    + " waarde (0,00) is groter dan 0",
            "V3 heeft als wachttijd 0 dag volgens de regel 'wachttijd' (onvoorwaardelijk): de tijdsduur van zijn vinddag"
                    + " tot zijn meldingsdag in hele dagen, met zijn vinddag (10-02-2026) en zijn meldingsdag"
                    + " (10-02-2026)",
            "V3 heeft als jutterstartdag 01-02-2026 volgens de regel 'jutterstartdag' (onvoorwaardelijk): de startdag van"
                    + " de jutter van de Vondst (01-02-2026)");

    /** The parts of six sentences: a fold whose filter is a characteristic, with its members (A); a displaced
     *  initialisation (A); an input value with a writer that did not hold and a displaced initialisation (D); a rule
     *  that held and gave no value (B); no writer held (C); a rounding with its value before it and a ground (A). */
    private static final Map<Integer, String> PARTS = Map.of(
            1, "SUBJECT«J1» « heeft als » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=eerste waardevolle"
                    + " vinddag, object=J1}«eerste waardevolle vinddag» « » VALUE«01-02-2026» « volgens de regel '»"
                    + " RULE«eerste waardevolle vinddag» «' (onvoorwaardelijk)» COMPUTATION[«: »"
                    + " QUOTE{declaration=ATTRIBUUT, owner=Vondst, canonical=vinddag}«de eerste van de vinddag van alle"
                    + " vondsten van de Jutter die » QUOTE{declaration=KENMERK, owner=Vondst,"
                    + " canonical=waardevol}«waardevol zijn» « (» VALUE«01-02-2026» «): » MEMBERS{members=2}[«over »"
                    + " SUBJECT«V1» « (» VALUE«01-02-2026» «) en » SUBJECT«V2» « (» VALUE«01-03-2026» «) (2 leden)»]]",
            4, "SUBJECT«J1» « heeft als » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=bonus, object=J1}«bonus»"
                    + " « » VALUE«25,00» « volgens de regel '» RULE«bonus voor ingezetenen» «'» COMPUTATION[«: »"
                    + " QUOTE«25,00»] «, » CONNECTIVE«omdat» « » CONDITION{holds=true}[«het onderwerp »"
                    + " NAME{declaration=KENMERK, owner=Jutter, canonical=ingezetene, object=J1}«ingezetene» « is»]"
                    + " VARIANT{decided=false, displaced=true}[«; verdrongen: de regel '» RULE«bonus zonder voorwaarde»"
                    + " «'»]",
            5, "SUBJECT«J1» « heeft geen » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=toelage,"
                    + " object=J1}«toelage» «, » CONNECTIVE«want» « de regel '» RULE«toelage» «' gaf geen waarde»"
                    + " COMPUTATION[«: » QUOTE{declaration=ATTRIBUUT, owner=Jutter, canonical=tarief, object=J1}«zijn"
                    + " tarief» « (» VALUE{empty=true}«onbekend» «)»] «, » CONNECTIVE«omdat» « »"
                    + " CONDITION{holds=true}[«het onderwerp » NAME{declaration=KENMERK, owner=Jutter,"
                    + " canonical=ingezetene, object=J1}«ingezetene» « is»]",
            10, "SUBJECT«J2» « heeft geen » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=premie,"
                    + " object=J2}«premie» «, » CONNECTIVE«want» « geen regel gold» VARIANT{decided=false}[«; de regel"
                    + " '» RULE«premie» «' » CONNECTIVE«gold niet» «, » CONNECTIVE«want» « » CONNECTIVE«niet geldt» «:"
                    + " » CONDITION{holds=false}[«het onderwerp » NAME{declaration=KENMERK, owner=Jutter,"
                    + " canonical=ingezetene, object=J2}«ingezetene» « is»]]",
            18, "SUBJECT«J3» « houdt als » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=bonus, object=J3}«bonus»"
                    + " « de invoerwaarde » VALUE«7,50» «, » CONNECTIVE«want» « geen regel gold»"
                    + " VARIANT{decided=false}[«; de regel '» RULE«bonus voor ingezetenen» «' » CONNECTIVE«gold niet»"
                    + " «, » CONNECTIVE«want» « » CONNECTIVE«niet geldt» «: » CONDITION{holds=false}[«het onderwerp »"
                    + " NAME{declaration=KENMERK, owner=Jutter, canonical=ingezetene, object=J3}«ingezetene» « is»]]"
                    + " VARIANT{decided=false, displaced=true}[«; verdrongen: de regel '» RULE«bonus zonder voorwaarde»"
                    + " «'»]",
            22, "SUBJECT«V1» « heeft als » NAME{declaration=ATTRIBUUT, owner=Vondst, canonical=vindersloon,"
                    + " object=V1}«vindersloon»"
                    + " « » VALUE«15,43» « volgens de regel '» RULE«vindersloon» «'» COMPUTATION[«: » QUOTE«" + VINDERSLOON
                    + "» «, met » QUOTE{declaration=PARAMETER, owner=null, canonical=percentage vindersloon}«percentage"
                    + " vindersloon» « (» VALUE«12,5 %» «) en » QUOTE{declaration=ATTRIBUUT, owner=Vondst,"
                    + " canonical=waarde, object=V1}«zijn waarde» « (» VALUE«123,45» «); vóór de afronding »"
                    + " VALUE«15,43125»] «, »"
                    + " CONNECTIVE«omdat» « » CONDITION{holds=true}[QUOTE{declaration=ATTRIBUUT, owner=Vondst,"
                    + " canonical=waarde, object=V1}«zijn waarde» « (» VALUE«123,45» «) is groter dan » VALUE«0»]"
                    + " GROUND{rule=vindersloon}[«. Grondslag: » NOTE«Jutterswet art. 10»]");

    private static String descriptor(String rules) {
        return DESCRIPTOR.formatted(rules);
    }

    private static String document() {
        return UcharBuild.document(Resolver.resolve(SOURCE));
    }

    private static DecisionCore fromSources(String source, String descriptor) {
        return DecisionCore.assemble(descriptor, Map.of("bron.rgs", source), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument() {
        return DecisionCore.buildFromDocument(descriptor(RULES), document(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
    }

    private static ObjectNode answer(DecisionCore core, String request) throws Exception {
        return core.process("jutters", DescriptorLoader.MAPPER.readTree(request),
                ServiceHttp.options("explanationParts=true"));
    }

    private static List<String> sentences(ObjectNode answer) {
        List<String> sentences = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sentences.add(z.asText());
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        return sentences;
    }

    private static void assertExplained(DecisionCore core) throws Exception {
        ObjectNode answer = answer(core, REQUEST);
        assertEquals(EXPECTED, sentences(answer));
        JsonNode parts = answer.get("explanationParts");
        for (Map.Entry<Integer, String> e : new TreeMap<>(PARTS).entrySet())
            assertEquals(e.getValue(), ExplanationPartsCheck.render(parts.get(e.getKey())), "zin " + e.getKey());
        assertEquals(3, ExplanationPartsCheck.assertGroundsNameTheirDeciders(parts,
                DescriptorLoader.MAPPER.readTree(document())));
    }

    @Test
    void from_sources_every_computed_value_gets_a_sentence() throws Exception {
        assertExplained(fromSources(SOURCE, descriptor(RULES)));
    }

    @Test
    void from_a_document_every_computed_value_gets_a_sentence() throws Exception {
        assertExplained(fromDocument());
    }

    /** A rule outside the scope of the entry point has no rule in its build: the chain stops at its one sentence, and
     *  the value it would write gets no sentence of its own. */
    @Test
    void a_rule_outside_the_scope_is_named_as_outside_the_computation() throws Exception {
        String source = SOURCE + """

                Objecttype de Haven (mv: Havens)
                    het liggeld Numeriek (getal met 2 decimalen);

                Regel liggeld
                    geldig altijd
                        Het liggeld van een Haven moet gesteld worden op 3,00.
                """;
        String descriptor = descriptor(RULES).replace("\"input\": [\"Jutter\", \"Vondst\"]",
                "\"input\": [\"Jutter\", \"Vondst\", \"Haven\"]");
        int end = REQUEST.lastIndexOf(" ] }");
        String request = REQUEST.substring(0, end) + ",\n    { \"type\": \"Haven\", \"id\": \"H1\" }"
                + REQUEST.substring(end);
        List<String> sentences = sentences(answer(fromSources(source, descriptor), request));
        List<String> expected = new ArrayList<>(EXPECTED);
        expected.add("de regel 'liggeld' staat buiten deze berekening");
        assertEquals(expected, sentences);
    }

    /** The members of a fold over a set: a count names them without a value, and above five the first five and how
     *  many more; one member is the only one. */
    @Test
    void a_count_names_its_members_and_above_five_how_many_more() throws Exception {
        String source = """
                Objecttype de Jutter (mv: Jutters) (bezield)
                    het aantal zware vondsten Numeriek (geheel getal);

                Objecttype de Vondst (mv: Vondsten)
                    is zwaar kenmerk (bijvoeglijk);

                Feittype JutterHeeftVondst
                    de jutter Jutter
                    de vondst (mv: vondsten) Vondst
                Eén jutter heeft meerdere vondsten

                Regel zware vondsten
                    geldig altijd
                        Het aantal zware vondsten van een Jutter moet berekend worden als het aantal vondsten van de Jutter die zwaar zijn.
                """;
        String descriptor = """
                { "service": {"name":"Leden van een telling","version":"1"},
                  "entrypoints": [ { "name":"jutters","path":"jutters", "scope": {"ruleNames": ["zware vondsten"]},
                    "input": ["Jutter", "Vondst"], "output": ["Jutter.aantal zware vondsten"] } ],
                  "parameters": {} }""";
        StringBuilder request = new StringBuilder("""
                { "instances": [ { "type": "Jutter", "id": "J1" }, { "type": "Jutter", "id": "J2" }""");
        for (String v : new String[]{"X7", "X1", "X12", "X2", "X3", "X11", "X4"})
            request.append(", { \"type\": \"Vondst\", \"id\": \"").append(v).append("\", \"roles\": { \"jutter\": \"J1\" },")
                    .append(" \"characteristics\": { \"zwaar\": ").append(!v.equals("X4")).append(" } }");
        request.append(", { \"type\": \"Vondst\", \"id\": \"Y1\", \"roles\": { \"jutter\": \"J2\" },")
                .append(" \"characteristics\": { \"zwaar\": true } } ] }");
        List<String> sentences = sentences(answer(fromSources(source, descriptor), request.toString()));
        String quote = " volgens de regel 'zware vondsten' (onvoorwaardelijk): het aantal vondsten van de Jutter die"
                + " zwaar zijn";
        assertEquals(List.of(
                "J1 heeft als aantal zware vondsten 6" + quote + " (6): over X1, X11, X12, X2, X3 en nog 1 (6 leden)",
                "J2 heeft als aantal zware vondsten 1" + quote + " (1): over Y1, het enige lid"), sentences);
    }

    /** An aggregation over its arguments ("de laatste van A en B") is no fold over a set: the sentence quotes it and
     *  names each argument with its value, as it does for a built-in function. */
    @Test
    void an_aggregation_over_its_arguments_names_each_argument() throws Exception {
        String source = """
                Objecttype de Jutter (mv: Jutters) (bezield)
                    de startdag Datum in dagen;
                    de vrijdag Datum in dagen;
                    de peildag Datum in dagen;

                Regel peildag
                    geldig altijd
                        De peildag van een Jutter moet berekend worden als de laatste van (zijn startdag) en (zijn vrijdag).
                """;
        String descriptor = """
                { "service": {"name":"Laatste van twee","version":"1"},
                  "entrypoints": [ { "name":"jutters","path":"jutters", "scope": {"ruleNames": ["peildag"]},
                    "input": ["Jutter"], "output": ["Jutter.peildag"] } ],
                  "parameters": {} }""";
        String request = """
                { "instances": [ { "type": "Jutter", "id": "J1",
                    "attributes": { "startdag": "2026-03-01", "vrijdag": "2026-03-10" } } ] }""";
        assertEquals(List.of("J1 heeft als peildag 10-03-2026 volgens de regel 'peildag' (onvoorwaardelijk): de laatste"
                        + " van (zijn startdag) en (zijn vrijdag), met zijn startdag (01-03-2026) en zijn vrijdag"
                        + " (10-03-2026)"),
                sentences(answer(fromSources(source, descriptor), request)));
    }

    /** The year a computation reads out of a date is a year: "het jaar uit zijn startdag (2026)", without the
     *  thousands point a number gets ("2.026"). */
    @Test
    void a_year_out_of_a_date_is_written_as_a_year() throws Exception {
        String source = """
                Objecttype de Jutter (mv: Jutters) (bezield)
                    de startdag Datum in dagen;
                    de nieuwjaarsdag Datum in dagen;

                Regel nieuwjaarsdag
                    geldig altijd
                        De nieuwjaarsdag van een Jutter moet berekend worden als de datum met jaar: het jaar uit zijn startdag, maand: 1 en dag: 1.
                """;
        String descriptor = """
                { "service": {"name":"Jaar uit een datum","version":"1"},
                  "entrypoints": [ { "name":"jutters","path":"jutters", "scope": {"ruleNames": ["nieuwjaarsdag"]},
                    "input": ["Jutter"], "output": ["Jutter.nieuwjaarsdag"] } ],
                  "parameters": {} }""";
        String request = """
                { "instances": [ { "type": "Jutter", "id": "J1", "attributes": { "startdag": "2026-03-05" } } ] }""";
        assertEquals(List.of("J1 heeft als nieuwjaarsdag 01-01-2026 volgens de regel 'nieuwjaarsdag' (onvoorwaardelijk):"
                        + " de datum met jaar: het jaar uit zijn startdag, maand: 1 en dag: 1, met het jaar uit zijn"
                        + " startdag (2026) en zijn startdag (05-03-2026)"),
                sentences(answer(fromSources(source, descriptor), request)));
    }

    /** uchar's golden jutterswet-wrakerfbasis: an ordinary rule on Strandrecht, and on its subtype Wrakrecht a defaults
     *  group on the same target. The stack of a Wrakrecht decides its uitkering (RUNTIME-SEMANTICS ch. 7.10) and the
     *  rule of the supertype does not run there: the engine gives no reading of it on W1, and the sentence of W1 is
     *  the one of its stack alone. */
    @Test
    void a_rule_of_a_supertype_does_not_run_where_a_stack_of_the_subtype_decides() throws Exception {
        String document = java.nio.file.Files.readString(
                java.nio.file.Path.of("../uchar/corpus/golden/jutterswet-wrakerfbasis/ir.json"));
        String descriptor = """
                { "service": {"name":"Stapel op een subtype","version":"1"},
                  "entrypoints": [ { "name":"jutters","path":"jutters",
                    "scope": {"ruleNames": ["uitkering van een strandrecht", "uitkering van een wrakrecht"]},
                    "input": ["Strandrecht", "Wrakrecht"], "output": ["Strandrecht.uitkering"] } ],
                  "parameters": {} }""";
        String request = """
                { "instances": [ { "type": "Strandrecht", "id": "S1", "attributes": { "grondslag": 10.00 } },
                                 { "type": "Wrakrecht", "id": "W1", "attributes": { "grondslag": 10.00 } } ] }""";
        DecisionCore core = DecisionCore.buildFromDocument(descriptor, document, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
        assertEquals(List.of(
                "S1 heeft als uitkering 20,00 volgens de regel 'uitkering van een strandrecht' (onvoorwaardelijk): zijn"
                        + " grondslag maal 2, met zijn grondslag (10,00)",
                "W1 heeft als uitkering 10,00 volgens de variant op plaats 2 van 'uitkering van een wrakrecht'"
                        + " (onvoorwaardelijk): zijn grondslag (10,00); de variant op plaats 1 van 'uitkering van een"
                        + " wrakrecht' gold niet, want niet geldt: het onderwerp betwist is"),
                sentences(answer(core, request)));
    }

    /** Every sentence of form A carries the computation as a group, and every writer that did not write stands in a
     *  group of its own: without them the sentence is still a sentence. */
    @Test
    void the_computation_and_the_variants_are_groups() throws Exception {
        JsonNode parts = answer(fromSources(SOURCE, descriptor(RULES)), REQUEST).get("explanationParts");
        int computations = 0, variants = 0;
        for (JsonNode sentence : parts)
            for (JsonNode p : sentence) {
                if ("COMPUTATION".equals(p.path("kind").asText())) computations++;
                if ("VARIANT".equals(p.path("kind").asText())) {
                    variants++;
                    assertTrue(p.get("parts").get(0).path("text").asText().startsWith("; "), p.toString());
                }
            }
        assertEquals(24, computations);
        assertEquals(12, variants);
    }
}
