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

/**
 * Card explanation-defaults-computation: the variant that decides in a defaults group on an attribute gets, after its
 * name, the same computation ({@code COMPUTATION}) an ordinary rule gets since card explanation-computed-attributes:
 * the rule text of its value, every value the computation read with the value of its node, and the value before a
 * rounding. Every value comes from the engine's reading of the variant ({@code DroolsBackend.readingsOf}), where the
 * nodes of its value follow those of its condition (uchar's RUNTIME-SEMANTICS ch. 5.12); a variant without a condition
 * has the nodes of its value alone. The variants that did not decide stay as they were. Both starts of the service;
 * the scenario route is pinned in {@code ExplanationDefaultsComputationTest}, with the same model and sentences.
 *
 * <p>The model: on a Jutter the premie, a base without a condition and an exception with an expression of its own; on
 * a Vondst the waarde with the opgave marker (the opgave, the computation 'berekende waarde', the initialisation
 * without a condition); on a Wrakrecht the uitkering, where the subtype's own rule without a condition decides below
 * the inherited exception (ch. 7.10).
 */
class ExplanationDefaultsComputationServiceTest {

    private static final String SOURCE = """
            Parameter het percentage premie : Percentage (getal met 1 decimalen)

            Objecttype de Jutter (mv: Jutters)
                het aantal vondsten Numeriek (geheel getal);
                de bonus Numeriek (getal met 2 decimalen);
                de premie Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                de taxatie Numeriek (getal met 2 decimalen);
                de bergingskosten Numeriek (getal met 2 decimalen);
                de waarde Numeriek (getal met 2 decimalen) (mag opgegeven worden);

            Objecttype het Recht (mv: Rechten)
                de grondslag Numeriek (getal met 2 decimalen);
                de uitkering Numeriek (getal met 2 decimalen);
                is betwist kenmerk (bijvoeglijk);

            Objecttype het Wrakrecht is een Recht (mv: Wrakrechten)
                is gezonken kenmerk (bijvoeglijk);

            // Bron: Jutterswet art. 12
            Regel premie
                geldig altijd
                    De premie van een Jutter moet berekend worden als zijn aantal vondsten maal 10,00.

            Uitzondering op premie: premie uit bonus
                geldig altijd
                    De premie van een Jutter moet berekend worden als (het percentage premie van zijn bonus) naar beneden afgerond op 2 decimalen
                    indien hij ingezetene is.

            Regel waarde
                geldig altijd
                    De waarde van een Vondst moet berekend worden als zijn taxatie plus zijn bergingskosten
                    indien zijn taxatie gevuld is.

            Regel waarde bij onbekende taxatie
                geldig altijd
                    De waarde van een Vondst moet geïnitialiseerd worden op 25,00.

            Regel uitkering
                geldig altijd
                    De uitkering van een Recht moet gesteld worden op zijn grondslag.

            Uitzondering op uitkering: betwiste uitkering
                geldig altijd
                    De uitkering van een Recht moet gesteld worden op 0,00
                    indien hij betwist is.

            Regel uitkering van een wrakrecht
                geldig altijd
                    De uitkering van een Wrakrecht moet berekend worden als zijn grondslag maal 2.
            """;

    private static final String DESCRIPTOR = """
            { "service": {"name":"Berekening van de beslissende variant","version":"1"},
              "entrypoints": [ { "name":"groepen","path":"groepen",
                "scope": {"ruleNames": ["premie", "waarde", "opgave boven berekening waarde", "afwijking opgave waarde",
                                        "controle opgave waarde", "uitkering", "uitkering van een wrakrecht"]},
                "input": ["Jutter", "Vondst", "Wrakrecht"],
                "output": ["Jutter.premie", "Vondst.waarde", "Recht.uitkering"] } ],
              "parameters": { "percentage premie": 12.5 } }""";

    /** J1: the base decides, J2 the exception. V1: the computation decides, V2 the opgave, V3 the initialisation. W1:
     *  the rule of the subtype decides, W2 the exception. */
    private static final String REQUEST = """
            { "instances": [
                { "type": "Jutter", "id": "J1", "attributes": { "aantal vondsten": 3 } },
                { "type": "Jutter", "id": "J2", "attributes": { "aantal vondsten": 3, "bonus": 123.45 },
                  "characteristics": { "ingezetene": true } },
                { "type": "Vondst", "id": "V1", "attributes": { "taxatie": 100.00, "bergingskosten": 10.00 } },
                { "type": "Vondst", "id": "V2", "attributes": { "opgegeven waarde": 80.00 } },
                { "type": "Vondst", "id": "V3" },
                { "type": "Wrakrecht", "id": "W1", "attributes": { "grondslag": 10.00 } },
                { "type": "Wrakrecht", "id": "W2", "attributes": { "grondslag": 10.00 },
                  "characteristics": { "betwist": true } } ] }""";

    private static final String PREMIE = "zijn aantal vondsten maal 10,00, met zijn aantal vondsten";
    private static final String UIT_BONUS = "(het percentage premie van zijn bonus) naar beneden afgerond op 2"
            + " decimalen, met percentage premie (12,5 %) en zijn bonus";

    /** The sentences, those of the scenario route ({@code ExplanationDefaultsComputationTest}). */
    static final List<String> EXPECTED = List.of(
            "J1 heeft als premie 30,00 volgens de regel 'premie' (onvoorwaardelijk): " + PREMIE + " (3); 'premie uit"
                    + " bonus' gold niet, want niet geldt: het onderwerp ingezetene is. Grondslag: Jutterswet art. 12",
            "J2 heeft als premie 15,43 volgens 'premie uit bonus': " + UIT_BONUS + " (123,45); vóór de afronding"
                    + " 15,43125, omdat het onderwerp ingezetene is; verdrongen: de regel 'premie'",
            "V1 heeft geen afwijkende opgave waarde, want niet geldt: zijn opgegeven waarde (onbekend) gevuld is",
            "V1 heeft als waarde 110,00 volgens 'berekende waarde' (onvoorwaardelijk): zijn berekende waarde (110,00);"
                    + " 'opgegeven waarde' gaf geen waarde; verdrongen: 'waarde bij onbekende taxatie'",
            "V1 heeft als berekende waarde 110,00 volgens de regel 'waarde': zijn taxatie plus zijn bergingskosten, met"
                    + " zijn taxatie (100,00) en zijn bergingskosten (10,00), omdat zijn taxatie (100,00) gevuld is",
            "V2 heeft geen afwijkende opgave waarde, want niet geldt: zijn berekende waarde (onbekend) gevuld is",
            "V2 heeft als waarde 80,00 volgens 'opgegeven waarde' (onvoorwaardelijk): zijn opgegeven waarde (80,00);"
                    + " verdrongen: 'berekende waarde', 'waarde bij onbekende taxatie'",
            "V2 heeft geen berekende waarde, want geen regel gold; de regel 'waarde' gold niet, want niet geldt: zijn"
                    + " taxatie (onbekend) gevuld is",
            "V3 heeft geen afwijkende opgave waarde, want niet geldt: zijn opgegeven waarde (onbekend) gevuld is",
            "V3 heeft als waarde 25,00 volgens 'waarde bij onbekende taxatie': 25,00, omdat de waarde in de invoer leeg"
                    + " was; 'opgegeven waarde' gaf geen waarde; 'berekende waarde' gaf geen waarde",
            "V3 heeft geen berekende waarde, want geen regel gold; de regel 'waarde' gold niet, want niet geldt: zijn"
                    + " taxatie (onbekend) gevuld is",
            "W1 heeft als uitkering 20,00 volgens de regel 'uitkering van een wrakrecht' (onvoorwaardelijk): zijn"
                    + " grondslag maal 2, met zijn grondslag (10,00); 'betwiste uitkering' gold niet, want niet geldt:"
                    + " het onderwerp betwist is",
            "W2 heeft als uitkering 0,00 volgens 'betwiste uitkering': 0,00, omdat het onderwerp betwist is;"
                    + " verdrongen: de regel 'uitkering van een wrakrecht'");

    /** The computation of the exception with its own expression (J2), of the computation default the opgave marker
     *  makes, which reads 'berekende waarde' and names it with the keys of its declaration (V1), of the initialisation
     *  without a condition (V3), and of the subtype's rule without a condition in a stack (W1). */
    private static final Map<Integer, String> PARTS = Map.of(
            1, "SUBJECT«J2» « heeft als » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=premie,"
                    + " object=J2}«premie» « » VALUE«15,43» « volgens '» RULE«premie uit bonus» «'» COMPUTATION[«: »"
                    + " QUOTE«(het percentage premie van zijn bonus) naar beneden afgerond op 2 decimalen» «, met »"
                    + " QUOTE{declaration=PARAMETER, owner=null, canonical=percentage premie}«percentage premie» « (»"
                    + " VALUE«12,5 %» «) en » QUOTE{declaration=ATTRIBUUT, owner=Jutter, canonical=bonus,"
                    + " object=J2}«zijn bonus» « (» VALUE«123,45» «); vóór de afronding » VALUE«15,43125»] «, »"
                    + " CONNECTIVE«omdat» « » CONDITION{holds=true}[«het onderwerp » NAME{declaration=KENMERK,"
                    + " owner=Jutter, canonical=ingezetene, object=J2}«ingezetene» « is»] «; verdrongen: de regel '»"
                    + " RULE«premie» «'»",
            3, "SUBJECT«V1» « heeft als » NAME{declaration=ATTRIBUUT, owner=Vondst, canonical=waarde,"
                    + " object=V1}«waarde» « » VALUE«110,00» « volgens '» NAME{declaration=ATTRIBUUT, owner=Vondst,"
                    + " canonical=berekende waarde}«berekende waarde» «' (onvoorwaardelijk)» COMPUTATION[«: »"
                    + " QUOTE«zijn » NAME{declaration=ATTRIBUUT, owner=Vondst, canonical=berekende waarde,"
                    + " object=V1}«berekende waarde» « (» VALUE«110,00» «)»] «; '» NAME{declaration=ATTRIBUUT,"
                    + " owner=Vondst, canonical=opgegeven waarde}«opgegeven waarde» «' gaf geen waarde; verdrongen: '»"
                    + " RULE«waarde bij onbekende taxatie» «'»",
            9, "SUBJECT«V3» « heeft als » NAME{declaration=ATTRIBUUT, owner=Vondst, canonical=waarde,"
                    + " object=V3}«waarde» « » VALUE«25,00» « volgens '» RULE«waarde bij onbekende taxatie» «'»"
                    + " COMPUTATION[«: » QUOTE«25,00»] «, » CONNECTIVE«omdat» « de » NAME{declaration=ATTRIBUUT,"
                    + " owner=Vondst, canonical=waarde, object=V3}«waarde» « in de invoer leeg was; '»"
                    + " NAME{declaration=ATTRIBUUT, owner=Vondst, canonical=opgegeven waarde}«opgegeven waarde» «' gaf"
                    + " geen waarde; '» NAME{declaration=ATTRIBUUT, owner=Vondst, canonical=berekende waarde}«berekende"
                    + " waarde» «' gaf geen waarde»",
            11, "SUBJECT«W1» « heeft als » NAME{declaration=ATTRIBUUT, owner=Recht, canonical=uitkering,"
                    + " object=W1}«uitkering» « » VALUE«20,00» « volgens de regel '» RULE«uitkering van een wrakrecht»"
                    + " «' (onvoorwaardelijk)» COMPUTATION[«: » QUOTE«zijn grondslag maal 2» «, met »"
                    + " QUOTE{declaration=ATTRIBUUT, owner=Recht, canonical=grondslag, object=W1}«zijn grondslag» « (»"
                    + " VALUE«10,00» «)»] «; '» RULE«betwiste uitkering» «' » CONNECTIVE«gold niet» «, »"
                    + " CONNECTIVE«want» « » CONNECTIVE«niet geldt» «: » CONDITION{holds=false}[«het onderwerp »"
                    + " NAME{declaration=KENMERK, owner=Recht, canonical=betwist, object=W1}«betwist» « is»]");

    private static String document() {
        return UcharBuild.document(Resolver.resolve(SOURCE));
    }

    private static DecisionCore fromSources() {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument() {
        return DecisionCore.buildFromDocument(DESCRIPTOR, document(), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader(), null);
    }

    private static void assertExplained(DecisionCore core) throws Exception {
        ObjectNode answer = core.process("groepen", DescriptorLoader.MAPPER.readTree(REQUEST),
                ServiceHttp.options("explanationParts=true"));
        List<String> sentences = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sentences.add(z.asText());
        ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"), answer.get("explanationParts"));
        assertEquals(EXPECTED, sentences);
        JsonNode parts = answer.get("explanationParts");
        for (Map.Entry<Integer, String> e : new TreeMap<>(PARTS).entrySet())
            assertEquals(e.getValue(), ExplanationPartsCheck.render(parts.get(e.getKey())), "zin " + e.getKey());
        assertEquals(1, ExplanationPartsCheck.assertGroundsNameTheirDeciders(parts,
                DescriptorLoader.MAPPER.readTree(document())));
    }

    @Test
    void from_sources_the_deciding_variant_gets_its_computation() throws Exception {
        assertExplained(fromSources());
    }

    @Test
    void from_a_document_the_deciding_variant_gets_its_computation() throws Exception {
        assertExplained(fromDocument());
    }
}
