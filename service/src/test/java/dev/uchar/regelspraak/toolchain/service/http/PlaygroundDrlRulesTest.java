package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.TokaSource;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.TmsTranspiler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Card drl-rule-provenance: beside {@code drl} the playground gives {@code drlRules}, per rule of that text and in its
 * order the rule's {@code name} and what uchar says it lowers ({@code TmsTranspiler.originOf}), in the keys of the
 * decider of a {@code GROUND}: {@code rule} for a form, {@code group}, {@code pointer}, {@code position} and
 * {@code variant} for a default, with {@code stack} for every rule of an effective defaults stack and
 * {@code generatedFrom} for a form or group uchar's frontend made; a rule of no form has its name only. Card
 * drl-rule-part-fields: a rule with a form also has its {@code role} in the lowering, uchar's key
 * ({@code RuleOrigin.Role.key()}), and a rule of a fold the {@code fold}, uchar's pointer of the fold's node without
 * the place of its group or form in the document, as {@code pointer} lies in its group; every stack of
 * {@code generated} has the {@code type} it runs for ({@code TmsTranspiler.stacks}), named in the vocabulary of
 * the answer, with {@code typeCanonical} beside it where that differs, and its {@code composition}
 * ({@code TmsTranspiler.Stack.composition}): its chains, each its defaults highest first in the keys of the decider
 * of a {@code GROUND}, a default with exception groups with those below it in the same form, and {@code unordered}
 * where chains stand side by side.
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

    private static final String OWN = "\"role\":\"own\"";
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

    /** Every rule of the DRL text, in its order, with its role and its decider; written from the IR of the model (the
     *  groups and their defaults on their pointers, the two Aggregation nodes, at {@code /rules/rules/1/versions/0/value}
     *  and {@code /rules/prioritizedDefaults/0/defaults/1/justification/left}) and from what each rule of uchar's
     *  lowering is for, not from the answer. */
    private static final List<String> EXPECTED = List.of(
            "{\"name\":\"strandpremie\"," + OWN + ",\"rule\":\"strandpremie\"}",
            "{\"name\":\"totaalgewicht\"," + OWN + ",\"rule\":\"totaalgewicht\"}",
            "{\"name\":\"melding\"," + OWN + ",\"rule\":\"melding\"}",
            "{\"name\":\"afwijking opgave meldingsdatum\"," + OWN + ",\"rule\":\"afwijking opgave meldingsdatum\","
                    + OPGAVE + "}",
            "{\"name\":\"controle opgave meldingsdatum\"," + OWN + ",\"rule\":\"controle opgave meldingsdatum\","
                    + OPGAVE + "}",
            // the flat group in the stack of a Jutter
            "{\"name\":\"defaults A:Jutter.premie: default 0\"," + OWN + "," + JUTTER + "," + JEUGDPREMIE + "}",
            "{\"name\":\"defaults A:Jutter.premie: default 1\"," + OWN + "," + JUTTER + "," + VEEL_VONDSTEN + "}",
            "{\"name\":\"defaults A:Jutter.premie: default 2\"," + OWN + "," + JUTTER
                    + ",\"group\":\"premie\",\"pointer\":\"/defaults/2\",\"position\":3,\"variant\":\"premie\"}",
            // the stack of the subtype: the two exceptions of the group on Jutter, and 'strandpremie' as its base
            "{\"name\":\"defaults A:Strandjutter.premie: default 0\"," + OWN + "," + STRANDJUTTER + "," + JEUGDPREMIE
                    + "}",
            "{\"name\":\"defaults A:Strandjutter.premie: default 1\"," + OWN + "," + STRANDJUTTER + ","
                    + VEEL_VONDSTEN + "}",
            "{\"name\":\"defaults A:Strandjutter.premie: default 2\"," + OWN + "," + STRANDJUTTER
                    + ",\"rule\":\"strandpremie\"}",
            // the nested exception, and the reconciliation of the exception group of 'zware vondst' under it: the
            // agreement of its members, and the decision of 'zware vondst' itself where that group does not settle
            "{\"name\":\"defaults A:Vondst.waarde: default 0.0.0\"," + OWN + "," + WAARDE + ",\"group\":\"waarde\","
                    + "\"pointer\":\"/defaults/0/exceptions/members/0/defaults/0\",\"position\":1,"
                    + "\"variant\":\"zeer zware vondst\"}",
            "{\"name\":\"defaults A:Vondst.waarde: eens 0\",\"role\":\"reconcile-agreement\"," + WAARDE + ","
                    + ZWARE_VONDST + "}",
            "{\"name\":\"defaults A:Vondst.waarde: terugval 0\",\"role\":\"fallback\"," + WAARDE + ","
                    + ZWARE_VONDST + "}",
            "{\"name\":\"defaults A:Vondst.waarde: default 1\"," + OWN + "," + WAARDE
                    + ",\"group\":\"waarde\",\"pointer\":\"/defaults/1\",\"position\":2,\"variant\":\"waarde\"}",
            // a rule of the stack itself: the agreement of the groups at its root, of no default
            "{\"name\":\"defaults A:Vondst.waarde: eens wortel\",\"role\":\"reconcile-agreement\"," + WAARDE + "}",
            // the group the opgave marker makes: its defaults have no name
            "{\"name\":\"defaults A:Vondst.meldingsdatum: default 0\"," + OWN + "," + MELDING
                    + ",\"group\":\"opgave boven berekening meldingsdatum\",\"pointer\":\"/defaults/0\",\"position\":1,"
                    + OPGAVE + "}",
            "{\"name\":\"defaults A:Vondst.meldingsdatum: default 1\"," + OWN + "," + MELDING
                    + ",\"group\":\"opgave boven berekening meldingsdatum\",\"pointer\":\"/defaults/1\",\"position\":2,"
                    + OPGAVE + "}",
            // the contributions to a fold: of the ordinary rule, and of the variant whose condition reads it, each
            // with the fold in its form or its group, as the pointer of a default lies in its group
            "{\"name\":\"totaalgewicht: bijdrage aan vouw 0\",\"role\":\"fold-contribution\","
                    + "\"rule\":\"totaalgewicht\",\"fold\":\"/versions/0/value\"}",
            "{\"name\":\"defaults A:Jutter.premie: bijdrage aan vouw 1\",\"role\":\"fold-contribution\"," + JUTTER
                    + "," + VEEL_VONDSTEN + ",\"fold\":\"/defaults/1/justification/left\"}",
            // rules of no form: no role
            "{\"name\":\"stand Vondst.berekende meldingsdatum: uit een afleiding\"}",
            "{\"name\":\"stand Vondst.berekende meldingsdatum: uit de invoer\"}",
            "{\"name\":\"kenmerkstand Vondst.afwijkende opgave meldingsdatum: waar uit de invoer\"}",
            "{\"name\":\"kenmerkstand Vondst.afwijkende opgave meldingsdatum: waar uit een afleiding\"}",
            "{\"name\":\"kenmerkstand Vondst.afwijkende opgave meldingsdatum: onwaar\"}");

    /** The text of each fold of the model, the {@code source.snippet} of its node. */
    private static final Map<String, String> FOLD_TEXT = Map.of(
            "totaalgewicht: bijdrage aan vouw 0", "de som van het gewicht van alle vondsten van de Jutter",
            "defaults A:Jutter.premie: bijdrage aan vouw 1", "het aantal vondsten van de Jutter");

    private static String stack(String name, String type, String typeCanonical, String owner, String member,
                                String composition) {
        return "{\"name\":\"" + name + "\",\"kind\":\"defaults\",\"type\":\"" + type + "\""
                + (typeCanonical == null ? "" : ",\"typeCanonical\":\"" + typeCanonical + "\"")
                + ",\"generatedFrom\":{\"kind\":\"ATTRIBUUT\",\"owner\":\"" + owner + "\",\"name\":\"" + member
                + "\",\"form\":\"defaultsstapel\"},\"composition\":" + composition + "}";
    }

    /** A composition: whether its chains stand side by side, and the chains, each its defaults highest first. */
    private static String composition(boolean unordered, String... chains) {
        return "{\"unordered\":" + unordered + ",\"chains\":[" + String.join(",", chains) + "]}";
    }

    private static String chain(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    /** A default of a chain, in the keys of its decider, with its exception groups below it where it has them. */
    private static String entry(String decider, String exceptions) {
        return "{" + decider + (exceptions == null ? "" : ",\"exceptions\":" + exceptions) + "}";
    }

    private static final String PREMIE =
            "\"group\":\"premie\",\"pointer\":\"/defaults/2\",\"position\":3,\"variant\":\"premie\"";
    private static final String STRANDPREMIE = "\"rule\":\"strandpremie\"";
    private static final String WAARDE_BASE =
            "\"group\":\"waarde\",\"pointer\":\"/defaults/1\",\"position\":2,\"variant\":\"waarde\"";
    private static final String ZEER_ZWARE_VONDST = "\"group\":\"waarde\","
            + "\"pointer\":\"/defaults/0/exceptions/members/0/defaults/0\",\"position\":1,\"variant\":\"zeer zware vondst\"";
    private static final String OPGAVE_GROUP = "\"group\":\"opgave boven berekening meldingsdatum\"";

    /** The compositions, written from the model and RUNTIME-SEMANTICS ch. 7.9 and 7.10: the flat group of a Jutter;
     *  for a Strandjutter the two inherited exceptions above its own base, the ordinary rule 'strandpremie'; the
     *  group 'waarde' with 'zeer zware vondst' in the exception group of 'zware vondst'; the group of the opgave
     *  marker. One chain each, so none unordered. */
    private static final String JUTTER_COMPOSITION = composition(false,
            chain(entry(JEUGDPREMIE, null), entry(VEEL_VONDSTEN, null), entry(PREMIE, null)));
    private static final String STRANDJUTTER_COMPOSITION = composition(false,
            chain(entry(JEUGDPREMIE, null), entry(VEEL_VONDSTEN, null), entry(STRANDPREMIE, null)));
    private static final String WAARDE_COMPOSITION = composition(false,
            chain(entry(ZWARE_VONDST, composition(false, chain(entry(ZEER_ZWARE_VONDST, null)))),
                    entry(WAARDE_BASE, null)));
    private static final String MELDING_COMPOSITION = composition(false,
            chain(entry(OPGAVE_GROUP + ",\"pointer\":\"/defaults/0\",\"position\":1," + OPGAVE, null),
                    entry(OPGAVE_GROUP + ",\"pointer\":\"/defaults/1\",\"position\":2," + OPGAVE, null)));

    /** Every stack of {@code generated} with the type it runs for and its composition: the stack of a Strandjutter
     *  decides the member declared on Jutter, for a Strandjutter. Written from the model, sorted. */
    private static final List<String> EXPECTED_STACKS = List.of(
            stack("defaults A:Jutter.premie", "Jutter", null, "Jutter", "premie", JUTTER_COMPOSITION),
            stack("defaults A:Strandjutter.premie", "Strandjutter", null, "Jutter", "premie",
                    STRANDJUTTER_COMPOSITION),
            stack("defaults A:Vondst.meldingsdatum", "Vondst", null, "Vondst", "meldingsdatum", MELDING_COMPOSITION),
            stack("defaults A:Vondst.waarde", "Vondst", null, "Vondst", "waarde", WAARDE_COMPOSITION));

    /** The own model with the shape of herleving-ww v2 for a Strandjutter: instead of its own base 'strandpremie'
     *  its own exception 'seniorpremie', an Afwijking that uchar's frontend puts in a group of its own,
     *  'premie voor Strandjutter', as the exception group of the one default of that group (uchar's frontend refuses
     *  an Afwijking of a rule beside an own base rule of the subtype); and the member 'vindersloon' of a Vondst with
     *  two unordered groups at its root. */
    private static final String SOURCE_COMPOSED = SOURCE
            .replace("    de meldingsdatum Datum in dagen (mag opgegeven worden);\n",
                    "    de meldingsdatum Datum in dagen (mag opgegeven worden);\n    de vindersloon Numeriek (geheel getal);\n")
            .replace("""
                    Regel strandpremie
                        geldig altijd
                            De premie van een Strandjutter moet berekend worden als 7.

                    """, "")
            + """

            Afwijking van premie: seniorpremie
                geldig altijd
                    De premie van een Strandjutter moet gesteld worden op 15
                    indien zijn leeftijd groter is dan 65.

            Uitzonderingen ongeordend:
            Regel vindersloon bij zware vondst
                geldig altijd
                    De vindersloon van een Vondst moet gesteld worden op 5
                    indien zijn gewicht groter is dan 10.

            Regel vindersloon bij lichte vondst
                geldig altijd
                    De vindersloon van een Vondst moet gesteld worden op 1
                    indien zijn gewicht kleiner is dan 2.
            """;

    /** The origin of the group uchar's frontend makes for the Afwijking. */
    private static final String SUBTYPE_GROUP = "\"generatedFrom\":{\"kind\":\"VORM\",\"owner\":null,"
            + "\"name\":\"premie\",\"form\":\"uitzondering-voor-subtype\"}";
    private static final String SENIOR_GROUP = "\"group\":\"premie voor Strandjutter\"";

    /** The stacks of {@code SOURCE_COMPOSED}, written from the model and ch. 7.10: for a Strandjutter its own exception
     *  (the default of 'premie voor Strandjutter', which has no content of its own and carries 'seniorpremie' in its
     *  exception group) above the inherited exceptions and the inherited base; 'vindersloon' with its two groups side
     *  by side. The other stacks as in the own model. Sorted. */
    private static final List<String> EXPECTED_COMPOSED_STACKS = List.of(
            stack("defaults A:Jutter.premie", "Jutter", null, "Jutter", "premie", JUTTER_COMPOSITION),
            stack("defaults A:Strandjutter.premie", "Strandjutter", null, "Jutter", "premie", composition(false,
                    chain(entry(SENIOR_GROUP + ",\"pointer\":\"/defaults/0\",\"position\":1," + SUBTYPE_GROUP,
                                    composition(false, chain(entry(SENIOR_GROUP + ",\"pointer\":"
                                            + "\"/defaults/0/exceptions/members/0/defaults/0\",\"position\":1,"
                                            + "\"variant\":\"seniorpremie\"," + SUBTYPE_GROUP, null)))),
                            entry(JEUGDPREMIE, null), entry(VEEL_VONDSTEN, null), entry(PREMIE, null)))),
            stack("defaults A:Vondst.meldingsdatum", "Vondst", null, "Vondst", "meldingsdatum", MELDING_COMPOSITION),
            stack("defaults A:Vondst.vindersloon", "Vondst", null, "Vondst", "vindersloon", composition(true,
                    chain(entry("\"group\":\"vindersloon bij zware vondst\",\"pointer\":\"/defaults/0\","
                            + "\"position\":1,\"variant\":\"vindersloon bij zware vondst\"", null)),
                    chain(entry("\"group\":\"vindersloon bij lichte vondst\",\"pointer\":\"/defaults/0\","
                            + "\"position\":1,\"variant\":\"vindersloon bij lichte vondst\"", null)))),
            stack("defaults A:Vondst.waarde", "Vondst", null, "Vondst", "waarde", WAARDE_COMPOSITION));

    /** The model with a vocabulary in which the subtype has a designation and Jutter and Vondst have none. */
    private static final String SOURCE_CGM = "Vocabulaire cgm (taal: nl)\n\n" + SOURCE.replace(
            "Objecttype de Strandjutter is een Jutter (mv: Strandjutters)",
            "Objecttype de Strandjutter is een Jutter (mv: Strandjutters) (cgm: STRANDJUTTER)");

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
        // every pointer of a default names a default of that group in the IR of the answer, with that name; every
        // fold names a node of its group or form there, with the text of the fold
        int folds = 0;
        for (JsonNode r : rules) {
            if (r.has("pointer")) {
                JsonNode d = holder(a.get("ir"), r).at(r.get("pointer").asText());
                assertTrue(d.isObject(), r.toString());
                assertEquals(d.path("name").asText(null), r.path("variant").asText(null), r.toString());
            }
            if (!r.has("fold")) continue;
            folds++;
            JsonNode fold = holder(a.get("ir"), r).at(r.get("fold").asText());
            assertEquals("Aggregation", fold.path("node").asText(), r.toString());
            assertEquals(FOLD_TEXT.get(r.get("name").asText()), fold.path("source").path("snippet").asText(),
                    r.toString());
        }
        assertEquals(FOLD_TEXT.size(), folds);
    }

    /** The group {@code group} of entry {@code r} names in {@code ir}, or else the form its {@code rule} names, in
     *  whichever list of the rule layer it stands: what the pointer and the fold of the entry lie in. */
    private static JsonNode holder(JsonNode ir, JsonNode r) {
        List<JsonNode> found = new ArrayList<>();
        if (r.has("group")) {
            for (JsonNode g : ir.get("rules").get("prioritizedDefaults"))
                if (g.path("name").asText().equals(r.get("group").asText())) found.add(g);
        } else {
            for (Iterator<JsonNode> lists = ir.get("rules").elements(); lists.hasNext(); )
                for (JsonNode f : lists.next())
                    if (f.path("name").asText().equals(r.get("rule").asText())) found.add(f);
        }
        assertEquals(1, found.size(), () -> "het IR heeft " + found.size() + " groep(en) of vorm(en) van " + r);
        return found.get(0);
    }

    private static List<String> sortedStacks(JsonNode generated) {
        List<String> sink = new ArrayList<>();
        for (JsonNode g : generated) if (g.get("kind").asText().equals("defaults")) sink.add(g.toString());
        java.util.Collections.sort(sink);
        return sink;
    }

    private static List<String> normalised(List<String> json) throws Exception {
        List<String> sink = new ArrayList<>();
        for (String e : json) sink.add(JSON.readTree(e).toString());
        return sink;
    }

    @Test
    void every_stack_of_generated_names_the_type_it_runs_for_and_its_composition() throws Exception {
        ObjectNode a = Playground.analyze(SOURCE);
        assertTrue(a.get("executable").asBoolean(), a.path("refusal").asText());
        assertEquals(normalised(EXPECTED_STACKS), sortedStacks(a.get("generated")));
        compositionsLieInTheIr(a);
        // in a vocabulary the type is named there, with its canonical name beside it where that differs; the
        // composition stays canonical, its keys are those of the IR
        ObjectNode cgm = Playground.analyze(SOURCE_CGM, "cgm");
        assertTrue(cgm.get("resolver").get("ok").asBoolean(), cgm.get("resolver").toString());
        assertEquals(normalised(List.of(
                stack("defaults A:Jutter.premie", "Jutter", null, "Jutter", "premie", JUTTER_COMPOSITION),
                stack("defaults A:Strandjutter.premie", "STRANDJUTTER", "Strandjutter", "Jutter", "premie",
                        STRANDJUTTER_COMPOSITION),
                stack("defaults A:Vondst.meldingsdatum", "Vondst", null, "Vondst", "meldingsdatum",
                        MELDING_COMPOSITION),
                stack("defaults A:Vondst.waarde", "Vondst", null, "Vondst", "waarde", WAARDE_COMPOSITION))),
                sortedStacks(cgm.get("generated")));
    }

    @Test
    void the_stack_of_a_subtype_has_its_own_exception_above_the_inherited_ones() throws Exception {
        assertFalse(SOURCE_COMPOSED.contains("strandpremie"), SOURCE_COMPOSED);
        ObjectNode a = Playground.analyze(SOURCE_COMPOSED);
        assertTrue(a.get("resolver").get("ok").asBoolean(), a.get("resolver").toString());
        assertTrue(a.get("executable").asBoolean(), a.path("refusal").asText());
        assertEquals(normalised(EXPECTED_COMPOSED_STACKS), sortedStacks(a.get("generated")));
        assertEquals(List.of("name", "kind", "type", "generatedFrom", "composition"),
                fieldNames(a.get("generated").get(0)));
        compositionsLieInTheIr(a);
    }

    /** Every default of every composition of {@code a} names, by its keys, a default of its group in the IR of the
     *  answer with the name {@code variant} gives, or the form of its {@code rule}: found as a reader finds it. */
    private static void compositionsLieInTheIr(JsonNode a) {
        int defaults = 0;
        for (JsonNode g : a.get("generated")) {
            if (!g.get("kind").asText().equals("defaults")) continue;
            List<JsonNode> entries = new ArrayList<>();
            entries(g.get("composition"), entries);
            for (JsonNode e : entries) {
                defaults++;
                JsonNode holder = holder(a.get("ir"), e);
                if (e.has("rule")) continue;
                JsonNode d = holder.at(e.get("pointer").asText());
                assertTrue(d.isObject() && d.path("node").asText().equals("Default"), e.toString());
                assertEquals(d.path("name").asText(null), e.path("variant").asText(null), e.toString());
                assertEquals(d.has("exceptions"), e.has("exceptions"), e.toString());
            }
        }
        assertTrue(defaults > 0, a.get("generated").toString());
    }

    private static void entries(JsonNode composition, List<JsonNode> sink) {
        assertEquals(composition.get("chains").size() > 1, composition.get("unordered").asBoolean(),
                composition.toString());
        for (JsonNode chain : composition.get("chains"))
            for (JsonNode e : chain) {
                sink.add(e);
                if (e.has("exceptions")) entries(e.get("exceptions"), sink);
            }
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> sink = new ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink;
    }

    @Test
    void the_type_and_composition_of_a_stack_come_from_the_analysis_without_a_drl_text() throws Exception {
        // generated reads the build of the exec-model branch, which has no DRL text: the type and the composition
        // do not depend on the DRL, so an answer with drlError beside a built exec-model branch has them as well
        RegelSpraakModel model = Resolver.resolve(SOURCE);
        DroolsBackend.Build build = UcharBuild.assemble(model, DroolsBackend.Branch.EXEC_MODEL);
        assertNull(build.drl());
        JsonNode ir = JSON.readTree(UcharBuild.document(model));
        JsonNode generated = Playground.generated(build, ir, model, Naming.canonical(model));
        assertEquals(normalised(EXPECTED_STACKS), sortedStacks(generated));
        assertEquals(Playground.analyze(SOURCE).get("generated"), generated);
    }

    @Test
    void both_routes_give_the_same_drl_rules() {
        DecisionCore core = DecisionCore.assemble(DESCRIPTOR, Map.of("jutters.rgs", SOURCE),
                CoreConfig.strict().withDetail(), Thread.currentThread().getContextClassLoader());
        ObjectNode viaService = Playground.analyze(SOURCE, null, core);
        ObjectNode own = Playground.analyze(SOURCE);
        assertEquals(own.get("drlRules"), viaService.get("drlRules"));
        assertEquals(EXPECTED.size(), viaService.get("drlRules").size(), viaService.get("drlRules").toString());
        assertEquals(own.get("generated"), viaService.get("generated"));
    }

    @Test
    void without_drl_the_list_is_empty() {
        // resolves cleanly, and uchar's analysis does not lower the rule: no DRL text, so no rule in it. The refusal
        // is the analysis', so the exec-model branch refuses too, and there is no generated either
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
        assertFalse(a.get("executable").asBoolean(), a.toString());
        assertFalse(a.has("generated"), a.toString());
    }

    /** The keys {@code docs/ENGINE.md} lists for {@code role}, uchar's {@code RuleOrigin.Role.key()}: a pin of uchar
     *  that adds a role fails here, so that the list there is brought along. */
    @Test
    void the_roles_are_the_keys_of_uchar() {
        List<String> keys = new ArrayList<>();
        for (TmsTranspiler.RuleOrigin.Role r : TmsTranspiler.RuleOrigin.Role.values()) keys.add(r.key());
        assertEquals(List.of("own", "fallback", "condition-fault", "condition-deposit", "no-evaluation-date",
                "reading", "reconcile-pass-on", "reconcile-difference", "reconcile-agreement", "fold-contribution",
                "fold-filter-fault", "fold-member-fault", "fold-filter-deposit", "uniqueness-key",
                "uniqueness-key-form-fault", "uniqueness-channel", "allocation-recipient"), keys);
    }

    /** Floors of the sweep below, measured 2026-10-24 on uchar's corpus at the pin of this card: goldens the
     *  DRL-text branch builds, entries with a role, entries with a fold. */
    private static final int MIN_BUILT = 282, MIN_ROLES = 1437, MIN_FOLDS = 291;

    /**
     * Over every golden of uchar ({@code ir.json}, the document uchar's emitter writes) that the DRL-text branch
     * builds: an entry has a role exactly where it has a form ({@code rule} or {@code stack}), a fold exactly where its
     * role is one of a fold, and the fold names an {@code Aggregation} or {@code QuantifierFold} in the group or form
     * of the entry, found by name as a reader of the answer finds it.
     */
    @Test
    void over_the_corpus_every_fold_lies_in_the_group_or_form_of_its_entry() throws Exception {
        List<Path> goldens;
        try (Stream<Path> s = Files.walk(TokaSource.REPO.resolve("uchar/corpus/golden"))) {
            goldens = s.filter(p -> p.getFileName().toString().equals("ir.json")).sorted().toList();
        }
        List<String> errors = new ArrayList<>();
        int built = 0, roles = 0, folds = 0;
        for (Path p : goldens) {
            JsonNode ir = JSON.readTree(p.toFile());
            DroolsBackend.Build build;
            try {
                build = UcharBuild.assemble(ir, DroolsBackend.Branch.DRL_TEXT);
            } catch (UcharBuild.NotCarried n) {
                continue;
            }
            built++;
            String golden = TokaSource.REPO.resolve("uchar/corpus/golden").relativize(p.getParent()).toString();
            for (JsonNode r : Playground.drlRules(build, ir, build.model())) {
                boolean form = r.has("rule") || r.has("stack");
                String role = r.path("role").asText(null);
                if (form != (role != null)) errors.add(golden + ": " + r + (form ? " zonder rol" : " met een rol"));
                if (role == null) continue;
                roles++;
                if (role.startsWith("fold-") != r.has("fold")) errors.add(golden + ": " + r + " rol en vouw");
                if (!r.has("fold")) continue;
                folds++;
                JsonNode node = holder(ir, r).at(r.get("fold").asText());
                if (!Set.of("Aggregation", "QuantifierFold").contains(node.path("node").asText()))
                    errors.add(golden + ": " + r + " noemt " + node.path("node").asText("geen knoop"));
            }
        }
        assertEquals(List.of(), errors);
        int b = built, n = roles, f = folds;
        assertTrue(b >= MIN_BUILT && n >= MIN_ROLES && f >= MIN_FOLDS, () -> b + " goldens gebouwd, " + n
                + " vermeldingen met een rol, " + f + " met een vouw; de vloeren "
                + List.of(MIN_BUILT, MIN_ROLES, MIN_FOLDS));
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
