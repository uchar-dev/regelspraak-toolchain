package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.transpiler.core.Namen;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card drl-rule-provenance: beside {@code drl} the playground gives {@code drlRules}, per rule of that text and in its
 * order the rule's {@code name} and what uchar says it lowers ({@code TmsTranspiler.originOf}), in the keys of the
 * decider of a {@code GROUND}: {@code rule} for a form, {@code group}, {@code pointer}, {@code position} and
 * {@code variant} for a default, with {@code stack} for every rule of an effective defaults stack and
 * {@code generatedFrom} for a form or group uchar's frontend made; a rule of no form has its name only.
 *
 * <p>The own model: the flat group 'premie' on Jutter (the base and two exceptions at the root; 'premie bij veel
 * vondsten' reads the fold "het aantal vondsten"), the ordinary rule 'strandpremie' on the subtype Strandjutter, which
 * the stack of a Strandjutter takes in as its base (RUNTIME-SEMANTICS ch. 7.10), the group 'waarde' on Vondst with the
 * exception 'zeer zware vondst' nested in the exception group of 'zware vondst', the ordinary rule 'totaalgewicht'
 * with a fold, and the meldingsdatum that may be given, so that the opgave marker makes a group and two rules.
 */
class PlaygroundDrlRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SOURCE = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                de leeftijd Numeriek (geheel getal);
                de premie Numeriek (geheel getal);
                het totaalgewicht Numeriek (geheel getal);

            Objecttype de Strandjutter is een Jutter (mv: Strandjutters)

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal);
                de waarde Numeriek (geheel getal);
                de vinddag Datum in dagen;
                de meldingsdatum Datum in dagen (mag opgegeven worden);

            Feittype JutterHeeftVondst
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel premie
                geldig altijd
                    De premie van een Jutter moet berekend worden als 10.

            Uitzondering op premie: premie bij veel vondsten
                geldig altijd
                    De premie van een Jutter moet berekend worden als 20
                    indien het aantal vondsten van de Jutter groter is dan 2.

            Uitzondering op premie: jeugdpremie
                geldig altijd
                    De premie van een Jutter moet berekend worden als 5
                    indien zijn leeftijd kleiner is dan 18.

            Regel strandpremie
                geldig altijd
                    De premie van een Strandjutter moet berekend worden als 7.

            Regel waarde
                geldig altijd
                    De waarde van een Vondst moet berekend worden als 100.

            Uitzondering op waarde: zware vondst
                geldig altijd
                    De waarde van een Vondst moet berekend worden als 50
                    indien zijn gewicht groter is dan 10.

            Uitzondering op zware vondst: zeer zware vondst
                geldig altijd
                    De waarde van een Vondst moet berekend worden als 25
                    indien zijn gewicht groter is dan 20.

            Regel totaalgewicht
                geldig altijd
                    Het totaalgewicht van een Jutter moet berekend worden als de som van het gewicht van alle vondsten van de Jutter.

            Regel melding
                geldig altijd
                    De meldingsdatum van een Vondst moet berekend worden als de vinddag van de Vondst plus 3 dagen.
            """;

    private static final String DESCRIPTOR = """
            { "service": {"name":"Herkomst van de DRL","version":"1"},
              "entrypoints": [ { "name":"jutters","path":"jutters",
                "scope": {"ruleNames": ["premie", "strandpremie", "waarde", "totaalgewicht", "melding",
                                        "opgave boven berekening meldingsdatum", "afwijking opgave meldingsdatum",
                                        "controle opgave meldingsdatum"]},
                "input": ["Jutter", "Strandjutter", "Vondst"],
                "output": ["Jutter.premie", "Jutter.totaalgewicht", "Vondst.waarde", "Vondst.meldingsdatum"] } ],
              "parameters": {} }""";

    private static final String OPGAVE = "\"generatedFrom\":{\"kind\":\"ATTRIBUUT\",\"owner\":\"Vondst\","
            + "\"name\":\"meldingsdatum\",\"form\":\"opgave-boven-berekening\"}";
    private static final String JUTTER = "\"stack\":\"defaults A:Jutter.premie\"";
    private static final String STRANDJUTTER = "\"stack\":\"defaults A:Strandjutter.premie\"";
    private static final String WAARDE = "\"stack\":\"defaults A:Vondst.waarde\"";
    private static final String MELDING = "\"stack\":\"defaults A:Vondst.meldingsdatum\"";
    private static final String JEUGDPREMIE =
            "\"group\":\"premie\",\"pointer\":\"/defaults/0\",\"position\":1,\"variant\":\"jeugdpremie\"";
    private static final String VEEL_VONDSTEN =
            "\"group\":\"premie\",\"pointer\":\"/defaults/1\",\"position\":2,\"variant\":\"premie bij veel vondsten\"";
    private static final String ZWARE_VONDST =
            "\"group\":\"waarde\",\"pointer\":\"/defaults/0\",\"position\":1,\"variant\":\"zware vondst\"";

    /** Every rule of the DRL text, in its order, with its decider; written from the IR of the model (the groups and
     *  their defaults on their pointers) and from what each rule of uchar's lowering is for, not from the answer. */
    private static final List<String> EXPECTED = List.of(
            "{\"name\":\"strandpremie\",\"rule\":\"strandpremie\"}",
            "{\"name\":\"totaalgewicht\",\"rule\":\"totaalgewicht\"}",
            "{\"name\":\"melding\",\"rule\":\"melding\"}",
            "{\"name\":\"afwijking opgave meldingsdatum\",\"rule\":\"afwijking opgave meldingsdatum\"," + OPGAVE + "}",
            "{\"name\":\"controle opgave meldingsdatum\",\"rule\":\"controle opgave meldingsdatum\"," + OPGAVE + "}",
            // the flat group in the stack of a Jutter
            "{\"name\":\"defaults A:Jutter.premie: default 0\"," + JUTTER + "," + JEUGDPREMIE + "}",
            "{\"name\":\"defaults A:Jutter.premie: default 1\"," + JUTTER + "," + VEEL_VONDSTEN + "}",
            "{\"name\":\"defaults A:Jutter.premie: default 2\"," + JUTTER
                    + ",\"group\":\"premie\",\"pointer\":\"/defaults/2\",\"position\":3,\"variant\":\"premie\"}",
            // the stack of the subtype: the two exceptions of the group on Jutter, and 'strandpremie' as its base
            "{\"name\":\"defaults A:Strandjutter.premie: default 0\"," + STRANDJUTTER + "," + JEUGDPREMIE + "}",
            "{\"name\":\"defaults A:Strandjutter.premie: default 1\"," + STRANDJUTTER + "," + VEEL_VONDSTEN + "}",
            "{\"name\":\"defaults A:Strandjutter.premie: default 2\"," + STRANDJUTTER + ",\"rule\":\"strandpremie\"}",
            // the nested exception, and the reconciliation of the exception group of 'zware vondst' under it
            "{\"name\":\"defaults A:Vondst.waarde: default 0.0.0\"," + WAARDE + ",\"group\":\"waarde\","
                    + "\"pointer\":\"/defaults/0/exceptions/members/0/defaults/0\",\"position\":1,"
                    + "\"variant\":\"zeer zware vondst\"}",
            "{\"name\":\"defaults A:Vondst.waarde: eens 0\"," + WAARDE + "," + ZWARE_VONDST + "}",
            "{\"name\":\"defaults A:Vondst.waarde: terugval 0\"," + WAARDE + "," + ZWARE_VONDST + "}",
            "{\"name\":\"defaults A:Vondst.waarde: default 1\"," + WAARDE
                    + ",\"group\":\"waarde\",\"pointer\":\"/defaults/1\",\"position\":2,\"variant\":\"waarde\"}",
            // a rule of the stack itself: the agreement of the groups at its root, of no default
            "{\"name\":\"defaults A:Vondst.waarde: eens wortel\"," + WAARDE + "}",
            // the group the opgave marker makes: its defaults have no name
            "{\"name\":\"defaults A:Vondst.meldingsdatum: default 0\"," + MELDING + ",\"group\":\"opgave boven"
                    + " berekening meldingsdatum\",\"pointer\":\"/defaults/0\",\"position\":1," + OPGAVE + "}",
            "{\"name\":\"defaults A:Vondst.meldingsdatum: default 1\"," + MELDING + ",\"group\":\"opgave boven"
                    + " berekening meldingsdatum\",\"pointer\":\"/defaults/1\",\"position\":2," + OPGAVE + "}",
            // the contributions to a fold: of the ordinary rule, and of the variant whose condition reads it
            "{\"name\":\"totaalgewicht: bijdrage aan vouw 0\",\"rule\":\"totaalgewicht\"}",
            "{\"name\":\"defaults A:Jutter.premie: bijdrage aan vouw 1\"," + JUTTER + "," + VEEL_VONDSTEN + "}",
            // rules of no form
            "{\"name\":\"stand Vondst.berekende meldingsdatum: uit een afleiding\"}",
            "{\"name\":\"stand Vondst.berekende meldingsdatum: uit de invoer\"}",
            "{\"name\":\"kenmerkstand Vondst.afwijkende opgave meldingsdatum: waar uit de invoer\"}",
            "{\"name\":\"kenmerkstand Vondst.afwijkende opgave meldingsdatum: waar uit een afleiding\"}",
            "{\"name\":\"kenmerkstand Vondst.afwijkende opgave meldingsdatum: onwaar\"}");

    @Test
    void every_rule_of_the_drl_text_names_its_decider() throws Exception {
        ObjectNode a = Playground.analyze(SOURCE);
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.get("resolver").toString());
        assertFalse(a.has("drlError"), a.path("drlError").asText());
        JsonNode rules = a.get("drlRules");
        assertTrue(rules != null && rules.isArray(), "drlRules ontbreekt naast drl");
        List<String> actual = new ArrayList<>();
        for (JsonNode r : rules) actual.add(r.toString());
        List<String> expected = new ArrayList<>();
        for (String e : EXPECTED) expected.add(JSON.readTree(e).toString());
        assertEquals(expected, actual);
        // one entry per rule header of the text, in its order; the header spells the name as a DRL string literal
        List<String> headers = new ArrayList<>();
        for (String line : a.get("drl").asText().split("\n", -1)) if (line.startsWith("rule ")) headers.add(line);
        List<String> fromNames = new ArrayList<>();
        for (JsonNode r : rules) fromNames.add("rule " + Namen.drlString(r.get("name").asText()));
        assertEquals(headers, fromNames);
        // every pointer of a default names a default of that group in the IR of the answer, with that name
        for (JsonNode r : rules) {
            if (!r.has("pointer")) continue;
            JsonNode group = null;
            for (JsonNode g : a.get("ir").get("rules").get("prioritizedDefaults"))
                if (g.get("name").asText().equals(r.get("group").asText())) group = g;
            assertTrue(group != null, r.toString());
            JsonNode d = group.at(r.get("pointer").asText());
            assertTrue(d.isObject(), r.toString());
            assertEquals(d.path("name").asText(null), r.path("variant").asText(null), r.toString());
        }
    }

    @Test
    void both_routes_give_the_same_drl_rules() {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("jutters.rgs", SOURCE),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        JsonNode viaService = Playground.analyze(SOURCE, null, core).get("drlRules");
        assertEquals(Playground.analyze(SOURCE).get("drlRules"), viaService);
        assertEquals(EXPECTED.size(), viaService.size(), viaService.toString());
    }

    @Test
    void without_drl_the_list_is_empty() {
        // resolves cleanly, and uchar's analysis does not lower the rule: no DRL text, so no rule in it
        ObjectNode a = Playground.analyze("""
            Objecttype de Vlucht (mv: Vluchten)
                de heffing Numeriek (niet-negatief geheel getal);
                is belast kenmerk (bijvoeglijk) voor elke dag;
            Regel heffing markert
            geldig altijd
            De heffing van een Vlucht moet berekend worden als het aantal dagen in de maand dat hij belast is.
            """);
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.toString());
        assertEquals("", a.get("drl").asText(), a.toString());
        assertTrue(a.get("drlError").asText().contains("heffing markert"), a.toString());
        assertTrue(a.get("drlRules").isArray(), a.toString());
        assertEquals(0, a.get("drlRules").size(), a.toString());
    }

    @Test
    void a_header_is_read_back_as_the_name_it_escapes() {
        String drl = "function int f() {\n    return 1;\n}\n"
                + "rule \"a \\\"b\\\" c\\\\d\\ne\\rf\"\n    dialect \"mvel\"\nwhen\nthen\nend\n\n"
                + "rule \"gewoon\"\n    dialect \"mvel\"\nwhen\nthen\nend\n\n";
        assertEquals(List.of("a \"b\" c\\d\ne\rf", "gewoon"), Playground.drlRuleNames(drl));
        assertThrows(IllegalStateException.class, () -> Playground.drlRuleNames("rule \"open\n"));
        assertThrows(IllegalStateException.class, () -> Playground.drlRuleNames("rule \"a\\tb\"\n"));
    }
}
