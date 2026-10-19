package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line and the comment of each declaration in {@code vocabularies.elements} ({@link Declarations}): read from
 * uchar's parse, keyed by owner and name, with a member of an extension at its line there and the type the extension
 * names as its owner. The list is filled without a declared vocabulary too. An element uchar's frontend wrote has no
 * line; an element that couples to no single declaration gives {@code declarationError}.
 */
class PlaygroundDeclarationsTest {

    private static final String BERGING = """
            // de kop van het bestand, los van wat volgt

            // Bron: het type
            Objecttype de Berging (mv: Bergingen)
                // de maxdatum van de berging
                //
                // Bron: art. 1
                de maxdatum Datum in dagen;
                de opbrengst Numeriek (geheel getal); // achter de opbrengst
                is gesloten kenmerk (bijvoeglijk);

            // Bron: de duur
            Objecttype de Duur (mv: Duren)
                // Bron: art. 2
                de maxdatum Datum in dagen;

            Extensie van objecttype de Duur
                // Bron: art. 3
                het aantal Numeriek (geheel getal);

            // Bron: de soorten
            Domein Soort is van het type Enumeratie
                // de eerste
                'hout'
                'steen'

            // Bron: het tarief
            Parameter het tarief : Numeriek (geheel getal)

            // Bron: het feittype
            Feittype bergingsduur
                // Bron: de rol
                de berging Berging
                de duur Duur
            Eén berging heeft één duur

            // Bron: de regel
            Regel opbrengst van een berging
                geldig altijd
                    De opbrengst van een Berging moet gesteld worden op het tarief.
            """;

    /** Each declaration with its line and the comment lines directly above it; a blank line and a comment behind
     *  the previous member end the comment; the same name on two types has two declarations; a member of an extension
     *  has its line there and the extended type as its owner. Without a vocabulary every element is there, with
     *  empty {@code benamingen}. */
    @Test
    void line_and_comment_per_declaration() {
        JsonNode voc = Playground.analyseer(BERGING).get("vocabularies");
        assertNull(voc.get("declarationError"), voc.toString());
        assertEquals(0, voc.get("declared").size());
        assertEquals(14, voc.get("elements").size(), voc.toString());
        for (JsonNode e : voc.get("elements")) assertEquals(0, e.get("designations").size(), e.toString());

        expected(voc, "OBJECTTYPE", null, "Berging", line(BERGING, "Objecttype de Berging"), "Bron: het type");
        expected(voc, "ATTRIBUUT", "Berging", "maxdatum", line(BERGING, "de maxdatum Datum in dagen;"),
                "de maxdatum van de berging", "", "Bron: art. 1");
        expected(voc, "ATTRIBUUT", "Berging", "opbrengst", line(BERGING, "de opbrengst"));
        expected(voc, "KENMERK", "Berging", "gesloten", line(BERGING, "is gesloten"));
        expected(voc, "OBJECTTYPE", null, "Duur", line(BERGING, "Objecttype de Duur"), "Bron: de duur");
        expected(voc, "ATTRIBUUT", "Duur", "maxdatum", line(BERGING, "// Bron: art. 2") + 1, "Bron: art. 2");
        expected(voc, "ATTRIBUUT", "Duur", "aantal", line(BERGING, "het aantal"), "Bron: art. 3");
        expected(voc, "ENUMERATIEWAARDE", "Soort", "hout", line(BERGING, "'hout'"), "de eerste");
        expected(voc, "ENUMERATIEWAARDE", "Soort", "steen", line(BERGING, "'steen'"));
        expected(voc, "PARAMETER", null, "tarief", line(BERGING, "Parameter het tarief"), "Bron: het tarief");
        expected(voc, "FEITTYPE", null, "bergingsduur", line(BERGING, "Feittype bergingsduur"), "Bron: het feittype");
        expected(voc, "ROL", "bergingsduur", "berging", line(BERGING, "de berging Berging"), "Bron: de rol");
        expected(voc, "ROL", "bergingsduur", "duur", line(BERGING, "de duur Duur"));
        expected(voc, "VORM", null, "opbrengst van een berging", line(BERGING, "Regel opbrengst"), "Bron: de regel");
    }

    /** A member an extension repeats word for word keeps the declaration the type carries, the one in its head,
     *  also when the extension comes first; a new member of the extension has its own line. */
    @Test
    void a_repetition_in_an_extension_keeps_the_head() {
        String afterHead = """
                Objecttype de Berging (mv: Bergingen)
                    // kop
                    de opbrengst Numeriek (geheel getal);
                Extensie van objecttype de Berging
                    // herhaling
                    de opbrengst Numeriek (geheel getal);
                    // nieuw
                    het aantal Numeriek (geheel getal);
                """;
        JsonNode voc = Playground.analyseer(afterHead).get("vocabularies");
        assertNotNull(voc, "uchar aanvaardt de letterlijke herhaling");
        expected(voc, "ATTRIBUUT", "Berging", "opbrengst", 3, "kop");
        expected(voc, "ATTRIBUUT", "Berging", "aantal", 8, "nieuw");

        String beforeHead = """
                Extensie van objecttype de Berging
                    // eerst
                    de opbrengst Numeriek (geheel getal);
                Objecttype de Berging (mv: Bergingen)
                    // kop
                    de opbrengst Numeriek (geheel getal);
                """;
        JsonNode before = Playground.analyseer(beforeHead).get("vocabularies");
        assertNotNull(before, "uchar aanvaardt de letterlijke herhaling");
        expected(before, "ATTRIBUUT", "Berging", "opbrengst", 6, "kop");
    }

    /** uchar's golden {@code jutterswet-opgave-bedrag}: the marked attribute and the rules have their line and their
     *  {@code // Bron:} comment; the attributes, the characteristic and the forms the opgave marker adds are written
     *  by uchar's frontend and have none. */
    @Test
    void what_the_frontend_wrote_has_no_line() throws IOException {
        String source = golden("jutterswet-opgave-bedrag");
        JsonNode voc = Playground.analyseer(source).get("vocabularies");
        assertNull(voc.get("declarationError"), voc.toString());
        expected(voc, "ATTRIBUUT", "Vondst", "waarde", 7);
        expected(voc, "VORM", null, "waarde", 12, "Bron: art. 3k lid 1");
        expected(voc, "VORM", null, "verzekerde waarde", 29, "Bron: art. 3k lid 5");
        for (String[] g : List.of(new String[]{"ATTRIBUUT", "Vondst", "opgegeven waarde"},
                new String[]{"ATTRIBUUT", "Vondst", "berekende waarde"},
                new String[]{"KENMERK", "Vondst", "afwijkende opgave waarde"},
                new String[]{"VORM", null, "afwijking opgave waarde"})) {
            JsonNode e = element(voc, g[0], g[1], g[2]);
            assertFalse(e.has("line"), e.toString());
            assertFalse(e.has("comment"), e.toString());
        }
    }

    /** uchar resolves an attribute declared twice in one head, and says not which declaration the model carries: the
     *  view names the element and both lines, and no element carries a line. */
    @Test
    void a_duplicate_member_gives_declaration_error() {
        String source = """
                Objecttype de Berging (mv: Bergingen)
                    de opbrengst Numeriek (geheel getal);
                    de opbrengst Numeriek (geheel getal);
                """;
        JsonNode voc = Playground.analyseer(source).get("vocabularies");
        assertNotNull(voc, "uchar resolvet de bron");
        assertEquals("Het attribuut 'opbrengst' van 'Berging' heeft declaraties op de regels [2, 3], en uchar geeft"
                + " niet aan welke daarvan het model draagt", voc.get("declarationError").asText());
        assertEquals(2, voc.get("elements").size());
        for (JsonNode e : voc.get("elements")) assertFalse(e.has("line"), e.toString());
    }

    /** A scenario source goes through the scenario entry rule of uchar's parser: uchar's golden
     *  {@code jutterswet-vocabulaires} with the playground's scenario, in {@code cgm}. The designations stay as they
     *  were; a rule in a rule group and the group itself have their lines. */
    @Test
    void a_scenario_source_with_vocabularies() throws IOException {
        String source = golden("jutterswet-vocabulaires") + "\n"
                + Files.readString(Path.of("src/test/resources/vocabularies/jutterswet-vocabulaires.scenario.rgs"));
        JsonNode voc = Playground.analyseer(source, "cgm").get("vocabularies");
        assertNull(voc.get("declarationError"), voc.toString());
        JsonNode jutter = expected(voc, "OBJECTTYPE", null, "Jutter", 11);
        assertEquals("JUTTER", jutter.at("/designations/cgm").asText());
        assertEquals("strandjutter", jutter.at("/designations/handboek").asText());
        expected(voc, "ENUMERATIEWAARDE", "Vondstsoort", "barnsteen", 6);
        expected(voc, "ROL", "vondstaanmelding", "vondst", 25);
        expected(voc, "REGELGROEP", null, "premieberekening", 28);
        expected(voc, "VORM", null, "premie van een vondst", 29);
    }

    /** Every golden of uchar's corpus with a source couples: no element or declaration is left over, every object
     *  type, fact type, role, parameter, enumeration value and rule group has its declaration (uchar's frontend writes
     *  none of these in the corpus), and the line of each declaration carries the name it declares. */
    @Test
    void every_golden_links() throws IOException {
        Set<Designatable.Kind> always = EnumSet.of(Designatable.Kind.OBJECTTYPE, Designatable.Kind.FEITTYPE,
                Designatable.Kind.ROL, Designatable.Kind.PARAMETER, Designatable.Kind.ENUMERATIEWAARDE,
                Designatable.Kind.REGELGROEP);
        List<Path> sources;
        try (Stream<Path> s = Files.list(TokaSource.REPO.resolve("uchar/corpus/golden"))) {
            sources = s.map(p -> p.resolve("source.rgs")).filter(Files::isRegularFile).sorted().toList();
        }
        assertTrue(sources.size() > 100, "het corpus telt " + sources.size() + " bronnen");
        int linked = 0;
        List<String> errors = new ArrayList<>();
        for (Path p : sources) {
            String source = Files.readString(p);
            boolean scenario = Playground.containsScenario(source);
            Playground.ParseResult parseResult = Playground.parse(source, scenario);
            RegelSpraakModel model = scenario
                    ? ScenarioResolver.resolve(source).model() : Resolver.resolve(source);
            if (!model.isOk()) continue;
            Map<Designatable, Declarations.Declaration> d;
            try {
                d = Declarations.van(parseResult.document(), parseResult.tokens(), model);
            } catch (Declarations.Unlinkable o) {
                errors.add(p.getParent().getFileName() + ": " + o.getMessage());
                continue;
            }
            List<String> lines = ScenarioData.lines(source);
            for (Designatable b : model.designatable()) {
                Declarations.Declaration x = d.get(b);
                if (x == null) {
                    if (always.contains(b.kind()))
                        errors.add(p.getParent().getFileName() + ": " + b + " heeft geen declaratie");
                    continue;
                }
                linked++;
                String text = lines.get(x.line() - 1).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (!text.contains(b.name().toLowerCase(Locale.ROOT)))
                    errors.add(p.getParent().getFileName() + ": " + b + " op regel " + x.line() + ": " + text);
            }
        }
        assertEquals(List.of(), errors);
        assertTrue(linked > 1000, linked + " gekoppelde elementen");
    }

    // ---------------- helpers ----------------

    private static String golden(String name) throws IOException {
        return Files.readString(TokaSource.REPO.resolve("uchar/corpus/golden/" + name + "/source.rgs"));
    }

    /** The 1-based line of {@code source} that begins, after its indentation, with {@code begin}. */
    private static int line(String source, String begin) {
        List<String> lines = ScenarioData.lines(source);
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).strip().startsWith(begin)) return i + 1;
        throw new AssertionError("geen regel begint met '" + begin + "'");
    }

    private static JsonNode element(JsonNode voc, String kind, String owner, String canonical) {
        for (JsonNode e : voc.get("elements"))
            if (e.get("kind").asText().equals(kind) && e.get("canonical").asText().equals(canonical)
                    && (owner == null ? e.get("owner").isNull() : owner.equals(e.get("owner").asText())))
                return e;
        throw new AssertionError("geen element " + kind + " " + owner + " " + canonical + " in " + voc);
    }

    /** Asserts the element's {@code line} and {@code comment}, and returns it. */
    private static JsonNode expected(JsonNode voc, String kind, String owner, String canonical, int line,
                                     String... comment) {
        JsonNode e = element(voc, kind, owner, canonical);
        assertEquals(line, e.path("line").asInt(-1), e.toString());
        List<String> wasRead = new ArrayList<>();
        for (JsonNode c : e.get("comment")) wasRead.add(c.asText());
        assertEquals(List.of(comment), wasRead, e.toString());
        return e;
    }
}
