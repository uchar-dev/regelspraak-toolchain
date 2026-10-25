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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * where chains stand side by side. Card drl-rule-functions-fields: every rule has its {@code piece} of {@code drl}
 * ({@code TmsTranspiler.drlPieces}): the places of the rule with its functions, in UTF-16 code units of the {@code drl}
 * of the same answer, and per function its name and places.
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
        for (JsonNode r : rules) actual.add(withoutPiece(r).toString());
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

    /** The entry without the {@code piece} that card drl-rule-functions-fields adds: what the pins of the decider, the
     *  role and the fold above it are about. */
    private static JsonNode withoutPiece(JsonNode entry) {
        ObjectNode copy = entry.deepCopy();
        copy.remove("piece");
        return copy;
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
        List<String> errors = new ArrayList<>();
        int built = 0, roles = 0, folds = 0;
        for (Path p : goldenDocuments()) {
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

    /** The {@code ir.json} of every golden of uchar, sorted. */
    private static List<Path> goldenDocuments() throws java.io.IOException {
        try (Stream<Path> s = Files.walk(TokaSource.REPO.resolve("uchar/corpus/golden"))) {
            return s.filter(p -> p.getFileName().toString().equals("ir.json")).sorted().toList();
        }
    }

    /** A declaration {@code function <type> <name>(}; a name carries the name of its rule, so any letter or digit. */
    private static final Pattern FUNCTION_DECLARATION = Pattern.compile("^function [^(\n]*? ([\\p{L}\\p{N}_$]+)\\(");

    /** The names of the functions {@code drl} declares, in the order of the text. */
    private static List<String> declaredFunctions(String drl) {
        List<String> out = new ArrayList<>();
        for (String line : drl.split("\n", -1)) {
            Matcher m = FUNCTION_DECLARATION.matcher(line);
            if (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /** A call of one of the functions {@code declared}: its name, not after a letter, a digit, {@code _}, {@code $} or a
     *  dot, and an opening parenthesis. The names come from the declarations of the text, never from a pattern of the
     *  name, which is uchar's to choose; the longest first, so a name that begins another is not read in its place. */
    private static Pattern callOf(List<String> declared) {
        if (declared.isEmpty()) return Pattern.compile("(?!)");
        List<String> longestFirst = new ArrayList<>(declared);
        longestFirst.sort(java.util.Comparator.comparingInt(String::length).reversed());
        StringBuilder alternatives = new StringBuilder();
        for (String n : longestFirst) alternatives.append(alternatives.isEmpty() ? "" : "|").append(Pattern.quote(n));
        return Pattern.compile("(?<![\\p{L}\\p{N}_$.])(" + alternatives + ")\\(");
    }

    /**
     * The {@code piece} of every entry of {@code rules} (the {@code drlRules} of an answer, as JSON) against a reading
     * of its {@code drl} by this test, which does not consult uchar's rendering. The text is read by lines, and a place
     * is an index of {@code String}, a UTF-16 code unit, as {@code JSON.parse} and {@code slice} count in a browser.
     * The faults, none for a text whose pieces follow it: one piece per header {@code rule "…"} in its order; the
     * first begins where the header of the text (package, imports, globals) ends, each where the previous one ends,
     * the last ends with the text; {@code ruleStart} is on the header of the rule, and the piece ends after its
     * {@code end} and the empty line; its functions are the declarations {@code function …} of the text, each once,
     * from {@code start} to {@code ruleStart} without a gap, each beginning with the comment line {@code // …} above
     * its declaration where it has one and ending after its closing brace and an empty line; and a call of a function
     * in the piece is a call of a function of that piece.
     */
    static List<String> piecesFollowTheText(String drl, JsonNode rules) {
        List<String> faults = new ArrayList<>();
        List<Integer> headers = new ArrayList<>(), declarations = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        int head = drl.length();
        boolean inHead = true;
        for (int at = 0; at < drl.length(); ) {
            int eol = drl.indexOf('\n', at);
            String line = drl.substring(at, eol < 0 ? drl.length() : eol);
            if (inHead && !(line.isEmpty() || line.startsWith("package ") || line.startsWith("import ")
                    || line.startsWith("global "))) {
                head = at;
                inHead = false;
            }
            if (line.startsWith("rule \"")) headers.add(at);
            Matcher m = FUNCTION_DECLARATION.matcher(line);
            if (m.find()) {
                declarations.add(at);
                declared.add(m.group(1));
            }
            at = eol < 0 ? drl.length() : eol + 1;
        }
        if (rules.size() != headers.size()) {
            faults.add(rules.size() + " vermeldingen, " + headers.size() + " koppen in de tekst");
            return faults;
        }
        Pattern call = callOf(declared);
        int previousEnd = head, functions = 0;
        for (int i = 0; i < rules.size(); i++) {
            String name = rules.get(i).get("name").asText();
            String where = "'" + name + "': ";
            JsonNode p = rules.get(i).path("piece");
            if (!p.isObject()) {
                faults.add(where + "geen piece");
                continue;
            }
            int start = p.path("start").asInt(-1), ruleStart = p.path("ruleStart").asInt(-1),
                    end = p.path("end").asInt(-1);
            if (start != previousEnd)
                faults.add(where + "begint op " + start + ", het vorige stuk eindigt op " + previousEnd);
            if (ruleStart != headers.get(i))
                faults.add(where + "ruleStart " + ruleStart + ", de kop staat op " + headers.get(i));
            if (!drl.startsWith("rule " + Namen.drlString(name) + "\n", ruleStart))
                faults.add(where + "op ruleStart staat de kop van de regel niet");
            if (!drl.startsWith("end\n\n", end - 5)) faults.add(where + "het stuk eindigt niet na `end` en een lege regel");
            previousEnd = end;
            Set<String> own = new java.util.HashSet<>();
            int at = start;
            for (JsonNode f : p.path("functions")) {
                String fname = f.path("name").asText();
                int fs = f.path("start").asInt(-1), decl = f.path("declaration").asInt(-1), fe = f.path("end").asInt(-1);
                String fwhere = where + "functie " + fname + ": ";
                boolean known = functions < declared.size();
                if (!known || !fname.equals(declared.get(functions)) || decl != declarations.get(functions))
                    faults.add(fwhere + "declaratie op " + decl + ", de tekst heeft "
                            + (known ? declared.get(functions) + " op " + declarations.get(functions) : "er geen"));
                functions++;
                if (fs != at) faults.add(fwhere + "begint op " + fs + ", waar de vorige eindigt is " + at);
                int lineBefore = decl <= 1 ? 0 : drl.lastIndexOf('\n', decl - 2) + 1;
                boolean commented = lineBefore < decl && drl.startsWith("// ", lineBefore);
                if (fs != (commented ? lineBefore : decl))
                    faults.add(fwhere + "begint op " + fs + ", het commentaar " + (commented ? "begint op " + lineBefore
                            : "ontbreekt, de declaratie staat op " + decl));
                if (!drl.startsWith("}\n\n", fe - 3)) faults.add(fwhere + "eindigt niet na de accolade en een lege regel");
                at = fe;
                own.add(fname);
            }
            if (at != ruleStart) faults.add(where + "de functies eindigen op " + at + ", de regel begint op " + ruleStart);
            for (Matcher m = call.matcher(drl.substring(Math.max(start, 0), Math.max(start, end))); m.find(); )
                if (!own.contains(m.group(1))) faults.add(where + "roept " + m.group(1) + " van een ander stuk");
        }
        if (previousEnd != drl.length())
            faults.add("het laatste stuk eindigt op " + previousEnd + ", de tekst op " + drl.length());
        if (functions != declared.size())
            faults.add("de tekst declareert " + declared.size() + " functies, de stukken noemen er " + functions);
        return faults;
    }

    private static final List<String> PIECE_KEYS = List.of("start", "ruleStart", "end", "functions");
    private static final List<String> FUNCTION_KEYS = List.of("name", "start", "declaration", "end");

    private static JsonNode named(JsonNode rules, String name) {
        for (JsonNode r : rules) if (r.get("name").asText().equals(name)) return r;
        throw new AssertionError("geen vermelding '" + name + "' in " + rules);
    }

    private static List<String> calls(Pattern call, String code, String self) {
        List<String> out = new ArrayList<>();
        for (Matcher m = call.matcher(code); m.find(); )
            if (!m.group(1).equals(self) && !out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** The table of the piece of {@code entry}: per function its name, its comment (the sentence at the places of the
     *  function, {@code -} for none) and the functions it calls, then the functions the rule calls, read off the text
     *  by this test. */
    private static List<String> table(String drl, JsonNode entry) {
        JsonNode piece = entry.get("piece");
        Pattern call = callOf(declaredFunctions(drl));
        List<String> out = new ArrayList<>();
        for (JsonNode f : piece.get("functions")) {
            String comment = drl.substring(f.get("start").asInt(), f.get("declaration").asInt());
            out.add(f.get("name").asText() + " | " + (comment.isEmpty() ? "-" : comment.strip().substring(3))
                    + " | roept " + calls(call, drl.substring(f.get("declaration").asInt(), f.get("end").asInt()),
                            f.get("name").asText()));
        }
        out.add("regel | roept " + calls(call, drl.substring(piece.get("ruleStart").asInt(), piece.get("end").asInt()),
                null));
        return out;
    }

    @Test
    void every_rule_of_the_drl_text_has_its_piece_with_its_functions() throws Exception {
        // the answer as the adapter writes it and a browser reads it: JSON text and back
        JsonNode a = JSON.readTree(JSON.writeValueAsString(Playground.analyze(SOURCE)));
        assertFalse(a.has("drlError"), a.path("drlError").asText());
        String drl = a.get("drl").asText();
        JsonNode rules = a.get("drlRules");
        assertEquals(List.of(), piecesFollowTheText(drl, rules));
        // the piece is the last key of an entry, and it and its functions have uchar's keys in uchar's order
        int functions = 0;
        for (JsonNode r : rules) {
            List<String> keys = fieldNames(r);
            assertEquals("piece", keys.get(keys.size() - 1), r.get("name").asText());
            assertEquals(PIECE_KEYS, fieldNames(r.get("piece")), r.get("name").asText());
            for (JsonNode f : r.get("piece").get("functions")) {
                assertEquals(FUNCTION_KEYS, fieldNames(f), r.get("name").asText());
                functions++;
            }
        }
        assertEquals(10, functions);
        // a rule without functions is its rule alone, to its `end` and the empty line after it
        JsonNode bare = named(rules, "kenmerkstand Vondst.afwijkende opgave meldingsdatum: onwaar").get("piece");
        assertEquals(0, bare.get("functions").size());
        assertEquals(bare.get("start").asInt(), bare.get("ruleStart").asInt());
        assertTrue(drl.substring(bare.get("start").asInt(), bare.get("end").asInt()).startsWith(
                "rule \"kenmerkstand Vondst.afwijkende opgave meldingsdatum: onwaar\"\n"));
        assertEquals(drl.length(), bare.get("end").asInt());
        // a value that computes something is a function, with its sentence, and the rule calls it; a value that
        // computes nothing, the bound value of an agreement, is no function: the rule carries it
        assertEquals(List.of(
                "waarde_melding | de vinddag van de Vondst plus 3 dagen | roept []",
                "regel | roept [waarde_melding]"), table(drl, named(rules, "melding")));
        assertEquals(List.of("regel | roept []"), table(drl, named(rules, "defaults A:Vondst.waarde: eens 0")));
        // a composite condition: the test carries its composite, one function; the sentence is that of the condition
        // the exception 'zeer zware vondst' shares with the one it is nested in; its value, a constant, is no function
        String both = "zijn gewicht groter is dan 10 en zijn gewicht groter is dan 20";
        assertEquals(List.of(
                "toets_defaultsAVondstWaardeDefault000 | " + both + " | roept []",
                "regel | roept [toets_defaultsAVondstWaardeDefault000]"),
                table(drl, named(rules, "defaults A:Vondst.waarde: default 0.0.0")));
        // the fall-back of the default of an exception group, between its agreement and the next default
        assertEquals(List.of(
                "toets_defaultsAVondstWaardeTerugval0 | zijn gewicht groter is dan 10 | roept []",
                "regel | roept [toets_defaultsAVondstWaardeTerugval0]"),
                table(drl, named(rules, "defaults A:Vondst.waarde: terugval 0")));
        JsonNode agreement = named(rules, "defaults A:Vondst.waarde: eens 0").get("piece");
        JsonNode fallback = named(rules, "defaults A:Vondst.waarde: terugval 0").get("piece");
        JsonNode next = named(rules, "defaults A:Vondst.waarde: default 1").get("piece");
        assertEquals(agreement.get("end"), fallback.get("start"));
        assertEquals(fallback.get("end"), next.get("start"));
        // the same pieces in a vocabulary and through the service: the places are those of the one text
        assertEquals(Playground.analyze(SOURCE_CGM).get("drlRules"), Playground.analyze(SOURCE_CGM, "cgm").get("drlRules"));
    }

    @Test
    void a_place_counts_utf16_code_units() throws Exception {
        // the rule 'melding' is named with a character outside the Basic Multilingual Plane, two code units and one
        // code point, so each place after it is one more in code units than in code points
        String astral = "\uD835\uDD18";
        RegelSpraakModel model = Resolver.resolve(SOURCE);
        JsonNode ir = JSON.readTree(UcharBuild.document(model).replace("\"melding\"", "\"melding " + astral + "\""));
        DroolsBackend.Build build = UcharBuild.assemble(ir, DroolsBackend.Branch.DRL_TEXT);
        assertTrue(build.drl().contains("rule \"melding " + astral + "\"\n"), "de naam staat niet in de tekst");
        ObjectNode answer = JSON.createObjectNode();
        answer.put("drl", build.drl());
        answer.set("drlRules", Playground.drlRules(build, ir, build.model()));
        JsonNode read = JSON.readTree(JSON.writeValueAsString(answer));
        assertEquals(build.drl(), read.get("drl").asText());
        assertEquals(List.of(), piecesFollowTheText(read.get("drl").asText(), read.get("drlRules")));
        int after = named(read.get("drlRules"), "afwijking opgave meldingsdatum").get("piece").get("ruleStart").asInt();
        // the name stands in the text more than once before that place (the name of a rule is also used in the rule
        // itself); each occurrence is one code point and two code units
        String before = build.drl().substring(0, after);
        int occurrences = before.split(java.util.regex.Pattern.quote(astral), -1).length - 1;
        assertTrue(occurrences > 0, before);
        assertEquals(after - occurrences, before.codePointCount(0, after));
    }

    @Test
    void pieces_that_do_not_fit_the_text_are_a_fault() {
        String drl = "package p;\n\nrule \"a\"\nend\n\nrule \"b\"\nend\n\n";
        int a = drl.indexOf("rule \"a\""), b = drl.indexOf("rule \"b\""), last = drl.length();
        List<String> names = List.of("a", "b");
        TmsTranspiler.DrlPiece pa = new TmsTranspiler.DrlPiece("a", a, a, b, List.of());
        TmsTranspiler.DrlPiece pb = new TmsTranspiler.DrlPiece("b", b, b, last, List.of());
        Playground.checkPieces(drl, names, List.of(pa, pb));
        // one piece fewer than the text has headers
        assertTrue(assertThrows(IllegalStateException.class, () -> Playground.checkPieces(drl, names, List.of(pa)))
                .getMessage().contains("1 stuk"));
        // a piece of another rule than the header it lies on, in the same order
        assertTrue(assertThrows(IllegalStateException.class, () -> Playground.checkPieces(drl, names, List.of(pa,
                new TmsTranspiler.DrlPiece("c", b, b, last, List.of())))).getMessage().contains("'c'"));
        // a gap between two pieces, and a text that goes on after the last
        assertTrue(assertThrows(IllegalStateException.class, () -> Playground.checkPieces(drl, names, List.of(
                new TmsTranspiler.DrlPiece("a", a, a, b - 1, List.of()), pb))).getMessage().contains("eindigt"));
        assertTrue(assertThrows(IllegalStateException.class, () -> Playground.checkPieces(drl, names, List.of(pa,
                new TmsTranspiler.DrlPiece("b", b, b, last - 1, List.of())))).getMessage().contains("tekst"));
        // a rule start that is not on its header
        assertTrue(assertThrows(IllegalStateException.class, () -> Playground.checkPieces(drl, names, List.of(
                new TmsTranspiler.DrlPiece("a", a, a, b + 1, List.of()),
                new TmsTranspiler.DrlPiece("b", b + 1, b + 1, last, List.of())))).getMessage().contains("kop"));
    }

    /** Floors of the sweep below, measured 2026-10-24 on uchar's corpus at the pin of card drl-rule-functions-fields:
     *  goldens the DRL-text branch builds, their pieces (a piece per rule) and their functions (uchar's own witness
     *  counts the same). The functions measured 2026-10-25 at the pin of card drl-value-functions-pin: 1871; uchar
     *  writes a function only where it tests or computes something. */
    private static final int MIN_PIECES = 2124, MIN_FUNCTIONS = 1871;

    /** Over every golden of uchar that the DRL-text branch builds, the pieces follow the text ({@link
     *  #piecesFollowTheText}), the places that of the one text of the answer. */
    @Test
    void over_the_corpus_every_piece_follows_the_text() throws Exception {
        List<String> errors = new ArrayList<>();
        int built = 0, pieces = 0, functions = 0;
        for (Path p : goldenDocuments()) {
            JsonNode ir = JSON.readTree(p.toFile());
            DroolsBackend.Build build;
            try {
                build = UcharBuild.assemble(ir, DroolsBackend.Branch.DRL_TEXT);
            } catch (UcharBuild.NotCarried n) {
                continue;
            }
            built++;
            JsonNode rules = Playground.drlRules(build, ir, build.model());
            String golden = TokaSource.REPO.resolve("uchar/corpus/golden").relativize(p.getParent()).toString();
            for (String fault : piecesFollowTheText(build.drl(), rules)) errors.add(golden + ": " + fault);
            for (JsonNode r : rules) {
                pieces++;
                functions += r.get("piece").get("functions").size();
            }
        }
        assertEquals(List.of(), errors);
        int b = built, n = pieces, f = functions;
        assertTrue(b >= MIN_BUILT && n >= MIN_PIECES && f >= MIN_FUNCTIONS, () -> b + " goldens gebouwd, " + n
                + " stukken, " + f + " functies; de vloeren " + List.of(MIN_BUILT, MIN_PIECES, MIN_FUNCTIONS));
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
