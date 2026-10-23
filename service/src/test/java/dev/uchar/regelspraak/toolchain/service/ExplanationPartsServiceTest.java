package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.Part;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.http.ServiceHttp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card explanation-parts: with {@code explanationParts=true} the decision service gives beside {@code explanation} each
 * of its sentences as its parts, on both routes (from sources and from an IR document). The texts of the leaves of a
 * sentence after each other are the sentence (acceptance (a)); every kind occurs, and so do a nested and/or list, a
 * quantifier fold with its members and a ground with all four of its children (acceptance (b)). Without the flag the
 * answer carries no parts, and the sentences are those without it.
 *
 * <p>The model: a Jutter is ervaren under a nested list (his leeftijd above the parameter minimumleeftijd, and at least
 * one of: more than 10 meldingen, ingezetene), with two {@code // Bron:} annotations; gelukkig when all his vondsten are
 * waardevol; has recht op strandgoed when ervaren; his premie is 100,00 when ervaren, and 50,00 under 18 by an
 * exception with its own annotation, which carries the condition of its base (ch. 7.9); his meldpremie is computed when
 * he is ingezetene (card explanation-computed-attributes: a computation and a writer that did not write, the two kinds
 * that card added). A Vondst is aangemeld when its jutter is ingezetene. J1 meets the list through
 * ingezetene and has one of two vondsten waardevol; J2 is 16 and ervaren from the input; J3 meets neither of the inner
 * list; J4 has no leeftijd.
 */
public class ExplanationPartsServiceTest {

    public static final String SOURCE = """
            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal);
                het aantal meldingen Numeriek (geheel getal);
                de premie Numeriek (getal met 2 decimalen);
                de meldpremie Numeriek (getal met 2 decimalen);
                is ingezetene kenmerk (bijvoeglijk);
                is ervaren kenmerk (bijvoeglijk);
                is gelukkig kenmerk (bijvoeglijk);
                het recht op strandgoed kenmerk (bezittelijk);

            Objecttype de Vondst (mv: Vondsten)
                is waardevol kenmerk (bijvoeglijk);
                is aangemeld kenmerk (bijvoeglijk);

            Feittype vondst van een jutter
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Parameter de minimumleeftijd : Numeriek (geheel getal);

            // Bron: BWBR0004045&artikel=20&lid=1&o=b · "met ingang van de dag waarop hij ervaren is" · lemma's: ervaren-jutter · strandgoed · deviatie wet/hb, volgt wet: oude-jutter
            // Bron: BWBR0004045&artikel=43
            Regel ervaren
                geldig altijd
                    Een Jutter is ervaren
                    indien hij aan alle volgende voorwaarden voldoet:
                    - zijn leeftijd is groter dan de minimumleeftijd
                    - hij voldoet aan ten minste één van de volgende voorwaarden:
                    .. zijn aantal meldingen is groter dan 10
                    .. hij is ingezetene.

            Regel gelukkig
                geldig altijd
                    Een Jutter is gelukkig
                    indien alle vondsten van de Jutter waardevol zijn.

            Regel recht op strandgoed
                geldig altijd
                    Een Jutter heeft recht op strandgoed
                    indien hij ervaren is.

            Regel aangemeld
                geldig altijd
                    Een Vondst is aangemeld
                    indien de jutter van de Vondst ingezetene is.

            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 100,00
                    indien hij ervaren is.

            // Bron: BWBR0004045&artikel=20&lid=2 · lemma's: jeugdpremie · deviatie binnen hb: oude-premie
            Uitzondering op premie: jeugdpremie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 50,00
                    indien zijn leeftijd kleiner is dan 18.

            Regel meldpremie
                geldig altijd
                    De meldpremie van een Jutter moet berekend worden als zijn aantal meldingen maal 2,50
                    indien hij ingezetene is.
            """;

    public static final String DESCRIPTOR = """
            { "service": {"name":"Stukken van de uitleg","version":"1"},
              "entrypoints": [ { "name":"jutters","path":"jutters",
                "scope": {"ruleNames": ["ervaren", "gelukkig", "recht op strandgoed", "aangemeld", "premie",
                                        "meldpremie"]},
                "input": ["Jutter", "Vondst"],
                "output": ["Jutter:ervaren", "Jutter:gelukkig", "Jutter:recht op strandgoed", "Jutter.premie",
                           "Vondst:aangemeld"] } ],
              "parameters": { "minimumleeftijd": 17 } }""";

    static final String REQUEST = """
            { "instances": [
                { "type": "Jutter", "id": "J1", "attributes": { "leeftijd": 30, "aantal meldingen": 3 },
                  "characteristics": { "ingezetene": true } },
                { "type": "Jutter", "id": "J2", "attributes": { "leeftijd": 16, "aantal meldingen": 0 },
                  "characteristics": { "ervaren": true } },
                { "type": "Jutter", "id": "J3", "attributes": { "leeftijd": 30, "aantal meldingen": 3 } },
                { "type": "Jutter", "id": "J4", "attributes": { "aantal meldingen": 3 } },
                { "type": "Vondst", "id": "V1", "characteristics": { "waardevol": true }, "roles": { "jutter": "J1" } },
                { "type": "Vondst", "id": "V2", "roles": { "jutter": "J1" } } ] }""";

    private static final String GROUND = ". Grondslag: BWBR0004045&artikel=20&lid=1&o=b · \"met ingang van de dag waarop"
            + " hij ervaren is\" · lemma's: ervaren-jutter · strandgoed · deviatie wet/hb, volgt wet: oude-jutter;"
            + " BWBR0004045&artikel=43";
    private static final String FOLD = "aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\"";

    static final List<String> EXPECTED = List.of(
            "J1 is ervaren, omdat zijn leeftijd (30) is groter dan de minimumleeftijd (17) en zijn aantal meldingen (3)"
                    + " is groter dan 10 of het onderwerp ingezetene is" + GROUND,
            "J1 is niet gelukkig, want " + FOLD + " is niet voldaan: niet voor V2 (1 van de 2)",
            "J1 heeft recht op strandgoed, omdat het onderwerp ervaren is",
            "J1 heeft als premie 100,00 volgens de regel 'premie': 100,00, omdat het onderwerp ervaren is;"
                    + " 'jeugdpremie' gold niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18",
            "J1 heeft als meldpremie 7,50 volgens de regel 'meldpremie': zijn aantal meldingen maal 2,50, met zijn"
                    + " aantal meldingen (3), omdat het onderwerp ingezetene is",
            "J2 is ervaren, maar de regel 'ervaren' gold niet, want niet geldt: zijn leeftijd (16) is groter dan de"
                    + " minimumleeftijd (17)" + GROUND,
            "J2 is gelukkig, omdat " + FOLD + " is voldaan: er is geen enkel lid",
            "J2 heeft recht op strandgoed, omdat het onderwerp ervaren is",
            "J2 heeft als premie 50,00 volgens 'jeugdpremie': 50,00, omdat het onderwerp ervaren is en zijn leeftijd"
                    + " (16) is kleiner dan 18; verdrongen: de regel 'premie'. Grondslag: BWBR0004045&artikel=20&lid=2"
                    + " · lemma's: jeugdpremie · deviatie binnen hb: oude-premie",
            noMeldpremie("J2"),
            "J3 is niet ervaren, want niet geldt: zijn aantal meldingen (3) is groter dan 10 of het onderwerp"
                    + " ingezetene is" + GROUND,
            "J3 is gelukkig, omdat " + FOLD + " is voldaan: er is geen enkel lid",
            "J3 heeft geen recht op strandgoed, want niet geldt: het onderwerp ervaren is",
            "J3 heeft geen premie, want geen variant besliste; 'jeugdpremie' gold niet, want niet geldt: het onderwerp"
                    + " ervaren is; de regel 'premie' gold niet, want niet geldt: het onderwerp ervaren is",
            noMeldpremie("J3"),
            "J4 is niet ervaren, want niet geldt: zijn leeftijd (onbekend) is groter dan de minimumleeftijd (17)"
                    + GROUND,
            "J4 is gelukkig, omdat " + FOLD + " is voldaan: er is geen enkel lid",
            "J4 heeft geen recht op strandgoed, want niet geldt: het onderwerp ervaren is",
            "J4 heeft geen premie, want geen variant besliste; 'jeugdpremie' gold niet, want niet geldt: het onderwerp"
                    + " ervaren is; de regel 'premie' gold niet, want niet geldt: het onderwerp ervaren is",
            noMeldpremie("J4"),
            "V1 is aangemeld, omdat de jutter ingezetene is",
            "V2 is aangemeld, omdat de jutter ingezetene is");

    /** No rule writes the meldpremie of a Jutter who is not ingezetene. */
    private static String noMeldpremie(String label) {
        return label + " heeft geen meldpremie, want geen regel gold; de regel 'meldpremie' gold niet, want niet geldt:"
                + " het onderwerp ingezetene is";
    }

    private static final String GROUND_PARTS = "GROUND{rule=ervaren}[«. Grondslag: »"
            + " STATUTE{bwb=BWBR0004045, article=20, paragraph=1, part=b}«BWBR0004045&artikel=20&lid=1&o=b» « · »"
            + " NOTE«\"met ingang van de dag waarop hij ervaren is\"» « · lemma's: » LEMMA«ervaren-jutter» « · »"
            + " LEMMA«strandgoed» « · deviatie wet/hb, volgt wet: »"
            + " DEVIATION{between=[\"wet\",\"hb\"], follows=wet}«oude-jutter» «; »"
            + " STATUTE{bwb=BWBR0004045, article=43}«BWBR0004045&artikel=43»]";
    private static final String ERVAREN = "NAME{declaration=KENMERK, owner=Jutter, canonical=ervaren}«ervaren»";
    private static final String LEEFTIJD =
            "QUOTE{declaration=ATTRIBUUT, owner=Jutter, canonical=leeftijd}«zijn leeftijd»";
    private static final String MINIMUMLEEFTIJD =
            "QUOTE{declaration=PARAMETER, owner=null, canonical=minimumleeftijd}«de minimumleeftijd»";

    /** The parts of the sentences that carry the nested list, the fold, a rule that did not hold beside a
     *  characteristic of the input, and a defaults group with the ground of a default. */
    static final Map<Integer, String> PARTS = Map.of(
            0, "SUBJECT«J1» « is » " + ERVAREN + " «, » CONNECTIVE«omdat» « »"
                    + " CONDITION{holds=true, quantifier=all}["
                    + "CONDITION{holds=true}[" + LEEFTIJD + " « (» VALUE«30» «) is groter dan » " + MINIMUMLEEFTIJD
                    + " « (» VALUE«17» «)»]"
                    + " « » CONNECTIVE«en» « »"
                    + " CONDITION{holds=true, quantifier=atLeast, threshold=1}["
                    + "CONDITION{holds=false}[QUOTE{declaration=ATTRIBUUT, owner=Jutter, canonical=aantal meldingen}"
                    + "«zijn aantal meldingen» « (» VALUE«3» «) is groter dan » VALUE«10»]"
                    + " « » CONNECTIVE«of» « »"
                    + " CONDITION{holds=true}[«het onderwerp » NAME{declaration=KENMERK, owner=Jutter,"
                    + " canonical=ingezetene}«ingezetene» « is»]]] " + GROUND_PARTS,
            1, "SUBJECT«J1» « is niet » NAME{declaration=KENMERK, owner=Jutter, canonical=gelukkig}«gelukkig» «, »"
                    + " CONNECTIVE«want» « » CONDITION{holds=false, quantifier=all}[«aan de voorwaarde \"»"
                    + " QUOTE«alle vondsten van de Jutter waardevol zijn» «\" is niet voldaan: »"
                    + " MEMBERS{members=2, holding=1, unjudged=0}[«niet voor » SUBJECT«V2» « (1 van de 2)»]]",
            5, "SUBJECT«J2» « is » " + ERVAREN + " «, » CONNECTIVE«maar» « de regel '» RULE«ervaren» «' »"
                    + " CONNECTIVE«gold niet» «, » CONNECTIVE«want» « » CONNECTIVE«niet geldt» «: »"
                    + " CONDITION{holds=false}[" + LEEFTIJD + " « (» VALUE«16» «) is groter dan » " + MINIMUMLEEFTIJD
                    + " « (» VALUE«17» «)»] " + GROUND_PARTS,
            8, "SUBJECT«J2» « heeft als » NAME{declaration=ATTRIBUUT, owner=Jutter, canonical=premie}«premie» « »"
                    + " VALUE«50,00» « volgens '» RULE«jeugdpremie» «'» COMPUTATION[«: » QUOTE«50,00»] «, »"
                    + " CONNECTIVE«omdat» « »"
                    + " CONDITION{holds=true, quantifier=all}[CONDITION{holds=true}[«het onderwerp » " + ERVAREN
                    + " « is»] « » CONNECTIVE«en» « »"
                    + " CONDITION{holds=true}[" + LEEFTIJD + " « (» VALUE«16» «) is kleiner dan » VALUE«18»]]"
                    + " «; verdrongen: de regel '» RULE«premie» «'»"
                    + " GROUND{group=premie, pointer=/defaults/0, position=1, variant=jeugdpremie}[«. Grondslag: »"
                    + " STATUTE{bwb=BWBR0004045, article=20, paragraph=2}«BWBR0004045&artikel=20&lid=2» « · lemma's: »"
                    + " LEMMA«jeugdpremie» « · deviatie binnen hb: » DEVIATION{between=[\"hb\"]}«oude-premie»]",
            10, "SUBJECT«J3» « is niet » " + ERVAREN + " «, » CONNECTIVE«want» « » CONNECTIVE«niet geldt» «: »"
                    + " CONDITION{holds=false, quantifier=atLeast, threshold=1}["
                    + "CONDITION{holds=false}[QUOTE{declaration=ATTRIBUUT, owner=Jutter, canonical=aantal meldingen}"
                    + "«zijn aantal meldingen» « (» VALUE«3» «) is groter dan » VALUE«10»]"
                    + " « » CONNECTIVE«of» « »"
                    + " CONDITION{holds=false}[«het onderwerp » NAME{declaration=KENMERK, owner=Jutter,"
                    + " canonical=ingezetene}«ingezetene» « is»]] " + GROUND_PARTS,
            15, "SUBJECT«J4» « is niet » " + ERVAREN + " «, » CONNECTIVE«want» « » CONNECTIVE«niet geldt» «: »"
                    + " CONDITION{holds=false}[" + LEEFTIJD + " « (» VALUE{empty=true}«onbekend» «) is groter dan » "
                    + MINIMUMLEEFTIJD + " « (» VALUE«17» «)»] " + GROUND_PARTS,
            20, "SUBJECT«V1» « is » NAME{declaration=KENMERK, owner=Vondst, canonical=aangemeld}«aangemeld» «, »"
                    + " CONNECTIVE«omdat» « » CONDITION{holds=true}[«de » NAME{declaration=ROL,"
                    + " owner=vondst van een jutter, canonical=jutter}«jutter» « » NAME{declaration=KENMERK,"
                    + " owner=Jutter, canonical=ingezetene}«ingezetene» « is»]");

    private static DecisionCore fromSources() {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("bron.rgs", SOURCE), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    private static DecisionCore fromDocument() {
        return DecisionCore.buildFromDocument(DESCRIPTOR, UcharBuild.document(Resolver.resolve(SOURCE)),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader(), null);
    }

    private static ObjectNode answer(DecisionCore core, String query) throws Exception {
        return core.process("jutters", DescriptorLoader.MAPPER.readTree(REQUEST), ServiceHttp.options(query));
    }

    private static List<String> sentences(JsonNode answer) {
        List<String> sink = new ArrayList<>();
        for (JsonNode z : answer.get("explanation")) sink.add(z.asText());
        return sink;
    }

    private static void assertParts(DecisionCore core) throws Exception {
        ObjectNode answer = answer(core, "explanationParts=true");
        assertEquals(EXPECTED, sentences(answer));
        Set<String> kinds = ExplanationPartsCheck.assertPartsAreTheSentences(answer.get("explanation"),
                answer.get("explanationParts"));
        assertEquals(5, ExplanationPartsCheck.assertGroundsNameTheirDeciders(answer.get("explanationParts"),
                DescriptorLoader.MAPPER.readTree(UcharBuild.document(Resolver.resolve(SOURCE)))));
        Set<String> every = new TreeSet<>(Arrays.stream(Part.Kind.values()).map(Part.Kind::name).toList());
        assertEquals(every, kinds, "elke soort komt voor");
        for (Map.Entry<Integer, String> e : new java.util.TreeMap<>(PARTS).entrySet())
            assertEquals(e.getValue(), ExplanationPartsCheck.render(answer.get("explanationParts").get(e.getKey())),
                    "zin " + e.getKey() + ": " + EXPECTED.get(e.getKey()));
    }

    @Test
    void from_sources_every_sentence_is_its_parts() throws Exception {
        assertParts(fromSources());
    }

    @Test
    void from_a_document_every_sentence_is_its_parts() throws Exception {
        assertParts(fromDocument());
    }

    @Test
    void the_parts_come_only_with_their_flag_and_leave_the_answer_as_it_was() throws Exception {
        DecisionCore core = fromSources();
        ObjectNode plain = answer(core, null);
        ObjectNode explained = answer(core, "explanation=true");
        ObjectNode parts = answer(core, "explanationParts");
        assertFalse(plain.has("explanation"));
        assertFalse(plain.has("explanationParts"));
        assertFalse(explained.has("explanationParts"));
        assertEquals(EXPECTED, sentences(explained));
        assertEquals(explained.get("explanation"), parts.get("explanation"));
        List<String> keys = new ArrayList<>();
        parts.fieldNames().forEachRemaining(keys::add);
        assertEquals("explanationParts", keys.get(keys.indexOf("explanation") + 1), keys.toString());
        ObjectNode rest = parts.deepCopy();
        rest.remove("explanationParts");
        assertEquals(explained, rest);
        explained.remove("explanation");
        assertEquals(plain, explained);
    }

    @Test
    void the_query_flag_asks_the_explanation_as_well() {
        assertEquals(new RequestOptions(true, null, null, false, false, false, true),
                ServiceHttp.options("explanationParts=true"));
        assertEquals(new RequestOptions(true, null, null, false, false, false, true),
                ServiceHttp.options("ExplanationParts"));
        assertEquals(new RequestOptions(false, null, null, false, false, false, false),
                ServiceHttp.options("explanationParts=false"));
        assertTrue(ServiceHttp.options("explanation&explanationParts").explanationParts());
    }
}
