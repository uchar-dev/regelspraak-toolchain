package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

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
 * The line and the comment of each declaration in {@code vocabulaires.elementen} ({@link Declaraties}): read from
 * uchar's parse, keyed by owner and name, with a member of an extension at its line there and the type the extension
 * names as its owner. The list is filled without a declared vocabulary too. An element uchar's frontend wrote has no
 * line; an element that couples to no single declaration gives {@code declaratieFout}.
 */
class PlaygroundDeclaratiesTest {

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
    void regel_en_commentaar_per_declaratie() {
        JsonNode voc = Playground.analyseer(BERGING).get("vocabulaires");
        assertNull(voc.get("declaratieFout"), voc.toString());
        assertEquals(0, voc.get("gedeclareerd").size());
        assertEquals(14, voc.get("elementen").size(), voc.toString());
        for (JsonNode e : voc.get("elementen")) assertEquals(0, e.get("benamingen").size(), e.toString());

        verwacht(voc, "OBJECTTYPE", null, "Berging", regel(BERGING, "Objecttype de Berging"), "Bron: het type");
        verwacht(voc, "ATTRIBUUT", "Berging", "maxdatum", regel(BERGING, "de maxdatum Datum in dagen;"),
                "de maxdatum van de berging", "", "Bron: art. 1");
        verwacht(voc, "ATTRIBUUT", "Berging", "opbrengst", regel(BERGING, "de opbrengst"));
        verwacht(voc, "KENMERK", "Berging", "gesloten", regel(BERGING, "is gesloten"));
        verwacht(voc, "OBJECTTYPE", null, "Duur", regel(BERGING, "Objecttype de Duur"), "Bron: de duur");
        verwacht(voc, "ATTRIBUUT", "Duur", "maxdatum", regel(BERGING, "// Bron: art. 2") + 1, "Bron: art. 2");
        verwacht(voc, "ATTRIBUUT", "Duur", "aantal", regel(BERGING, "het aantal"), "Bron: art. 3");
        verwacht(voc, "ENUMERATIEWAARDE", "Soort", "hout", regel(BERGING, "'hout'"), "de eerste");
        verwacht(voc, "ENUMERATIEWAARDE", "Soort", "steen", regel(BERGING, "'steen'"));
        verwacht(voc, "PARAMETER", null, "tarief", regel(BERGING, "Parameter het tarief"), "Bron: het tarief");
        verwacht(voc, "FEITTYPE", null, "bergingsduur", regel(BERGING, "Feittype bergingsduur"), "Bron: het feittype");
        verwacht(voc, "ROL", "bergingsduur", "berging", regel(BERGING, "de berging Berging"), "Bron: de rol");
        verwacht(voc, "ROL", "bergingsduur", "duur", regel(BERGING, "de duur Duur"));
        verwacht(voc, "VORM", null, "opbrengst van een berging", regel(BERGING, "Regel opbrengst"), "Bron: de regel");
    }

    /** A member an extension repeats word for word keeps the declaration the type carries, the one in its head,
     *  also when the extension comes first; a new member of the extension has its own line. */
    @Test
    void een_herhaling_in_een_extensie_houdt_de_kop() {
        String naKop = """
                Objecttype de Berging (mv: Bergingen)
                    // kop
                    de opbrengst Numeriek (geheel getal);
                Extensie van objecttype de Berging
                    // herhaling
                    de opbrengst Numeriek (geheel getal);
                    // nieuw
                    het aantal Numeriek (geheel getal);
                """;
        JsonNode voc = Playground.analyseer(naKop).get("vocabulaires");
        assertNotNull(voc, "uchar aanvaardt de letterlijke herhaling");
        verwacht(voc, "ATTRIBUUT", "Berging", "opbrengst", 3, "kop");
        verwacht(voc, "ATTRIBUUT", "Berging", "aantal", 8, "nieuw");

        String voorKop = """
                Extensie van objecttype de Berging
                    // eerst
                    de opbrengst Numeriek (geheel getal);
                Objecttype de Berging (mv: Bergingen)
                    // kop
                    de opbrengst Numeriek (geheel getal);
                """;
        JsonNode voor = Playground.analyseer(voorKop).get("vocabulaires");
        assertNotNull(voor, "uchar aanvaardt de letterlijke herhaling");
        verwacht(voor, "ATTRIBUUT", "Berging", "opbrengst", 6, "kop");
    }

    /** uchar's golden {@code jutterswet-opgave-bedrag}: the marked attribute and the rules have their line and their
     *  {@code // Bron:} comment; the attributes, the characteristic and the forms the opgave marker adds are written
     *  by uchar's frontend and have none. */
    @Test
    void wat_de_frontend_schreef_heeft_geen_regel() throws IOException {
        String bron = golden("jutterswet-opgave-bedrag");
        JsonNode voc = Playground.analyseer(bron).get("vocabulaires");
        assertNull(voc.get("declaratieFout"), voc.toString());
        verwacht(voc, "ATTRIBUUT", "Vondst", "waarde", 7);
        verwacht(voc, "VORM", null, "waarde", 12, "Bron: art. 3k lid 1");
        verwacht(voc, "VORM", null, "verzekerde waarde", 29, "Bron: art. 3k lid 5");
        for (String[] g : List.of(new String[]{"ATTRIBUUT", "Vondst", "opgegeven waarde"},
                new String[]{"ATTRIBUUT", "Vondst", "berekende waarde"},
                new String[]{"KENMERK", "Vondst", "afwijkende opgave waarde"},
                new String[]{"VORM", null, "afwijking opgave waarde"})) {
            JsonNode e = element(voc, g[0], g[1], g[2]);
            assertFalse(e.has("regel"), e.toString());
            assertFalse(e.has("commentaar"), e.toString());
        }
    }

    /** uchar resolves an attribute declared twice in one head, and says not which declaration the model carries: the
     *  view names the element and both lines, and no element carries a line. */
    @Test
    void een_dubbel_lid_geeft_declaratieFout() {
        String bron = """
                Objecttype de Berging (mv: Bergingen)
                    de opbrengst Numeriek (geheel getal);
                    de opbrengst Numeriek (geheel getal);
                """;
        JsonNode voc = Playground.analyseer(bron).get("vocabulaires");
        assertNotNull(voc, "uchar resolvet de bron");
        assertEquals("Het attribuut 'opbrengst' van 'Berging' heeft declaraties op de regels [2, 3], en uchar geeft"
                + " niet aan welke daarvan het model draagt", voc.get("declaratieFout").asText());
        assertEquals(2, voc.get("elementen").size());
        for (JsonNode e : voc.get("elementen")) assertFalse(e.has("regel"), e.toString());
    }

    /** A scenario source goes through the scenario entry rule of uchar's parser: uchar's golden
     *  {@code jutterswet-vocabulaires} with the playground's scenario, in {@code cgm}. The designations stay as they
     *  were; a rule in a rule group and the group itself have their lines. */
    @Test
    void een_scenariobron_met_vocabulaires() throws IOException {
        String bron = golden("jutterswet-vocabulaires") + "\n"
                + Files.readString(Path.of("src/test/resources/vocabulaires/jutterswet-vocabulaires.scenario.rgs"));
        JsonNode voc = Playground.analyseer(bron, "cgm").get("vocabulaires");
        assertNull(voc.get("declaratieFout"), voc.toString());
        JsonNode jutter = verwacht(voc, "OBJECTTYPE", null, "Jutter", 11);
        assertEquals("JUTTER", jutter.at("/benamingen/cgm").asText());
        assertEquals("strandjutter", jutter.at("/benamingen/handboek").asText());
        verwacht(voc, "ENUMERATIEWAARDE", "Vondstsoort", "barnsteen", 6);
        verwacht(voc, "ROL", "vondstaanmelding", "vondst", 25);
        verwacht(voc, "REGELGROEP", null, "premieberekening", 28);
        verwacht(voc, "VORM", null, "premie van een vondst", 29);
    }

    /** Every golden of uchar's corpus with a source couples: no element or declaration is left over, every object
     *  type, fact type, role, parameter, enumeration value and rule group has its declaration (uchar's frontend writes
     *  none of these in the corpus), and the line of each declaration carries the name it declares. */
    @Test
    void elke_golden_koppelt() throws IOException {
        Set<Benoembaar.Soort> altijd = EnumSet.of(Benoembaar.Soort.OBJECTTYPE, Benoembaar.Soort.FEITTYPE,
                Benoembaar.Soort.ROL, Benoembaar.Soort.PARAMETER, Benoembaar.Soort.ENUMERATIEWAARDE,
                Benoembaar.Soort.REGELGROEP);
        List<Path> bronnen;
        try (Stream<Path> s = Files.list(TokaBron.REPO.resolve("uchar/corpus/golden"))) {
            bronnen = s.map(p -> p.resolve("bron.rgs")).filter(Files::isRegularFile).sorted().toList();
        }
        assertTrue(bronnen.size() > 100, "het corpus telt " + bronnen.size() + " bronnen");
        int gekoppeld = 0;
        List<String> fouten = new ArrayList<>();
        for (Path p : bronnen) {
            String bron = Files.readString(p);
            boolean scenario = Playground.bevatScenario(bron);
            Playground.Ontleding ontleding = Playground.parse(bron, scenario);
            RegelSpraakModel model = scenario
                    ? ScenarioResolver.resolve(bron).model() : Resolver.resolve(bron);
            if (!model.isOk()) continue;
            Map<Benoembaar, Declaraties.Declaratie> d;
            try {
                d = Declaraties.van(ontleding.document(), ontleding.tokens(), model);
            } catch (Declaraties.Onkoppelbaar o) {
                fouten.add(p.getParent().getFileName() + ": " + o.getMessage());
                continue;
            }
            List<String> regels = ScenarioGegevens.regels(bron);
            for (Benoembaar b : model.benoembaar()) {
                Declaraties.Declaratie x = d.get(b);
                if (x == null) {
                    if (altijd.contains(b.soort()))
                        fouten.add(p.getParent().getFileName() + ": " + b + " heeft geen declaratie");
                    continue;
                }
                gekoppeld++;
                String tekst = regels.get(x.regel() - 1).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (!tekst.contains(b.naam().toLowerCase(Locale.ROOT)))
                    fouten.add(p.getParent().getFileName() + ": " + b + " op regel " + x.regel() + ": " + tekst);
            }
        }
        assertEquals(List.of(), fouten);
        assertTrue(gekoppeld > 1000, gekoppeld + " gekoppelde elementen");
    }

    // ---------------- helpers ----------------

    private static String golden(String naam) throws IOException {
        return Files.readString(TokaBron.REPO.resolve("uchar/corpus/golden/" + naam + "/bron.rgs"));
    }

    /** The 1-based line of {@code bron} that begins, after its indentation, with {@code begin}. */
    private static int regel(String bron, String begin) {
        List<String> regels = ScenarioGegevens.regels(bron);
        for (int i = 0; i < regels.size(); i++) if (regels.get(i).strip().startsWith(begin)) return i + 1;
        throw new AssertionError("geen regel begint met '" + begin + "'");
    }

    private static JsonNode element(JsonNode voc, String soort, String eigenaar, String canoniek) {
        for (JsonNode e : voc.get("elementen"))
            if (e.get("soort").asText().equals(soort) && e.get("canoniek").asText().equals(canoniek)
                    && (eigenaar == null ? e.get("eigenaar").isNull() : eigenaar.equals(e.get("eigenaar").asText())))
                return e;
        throw new AssertionError("geen element " + soort + " " + eigenaar + " " + canoniek + " in " + voc);
    }

    /** Asserts the element's {@code regel} and {@code commentaar}, and returns it. */
    private static JsonNode verwacht(JsonNode voc, String soort, String eigenaar, String canoniek, int regel,
                                     String... commentaar) {
        JsonNode e = element(voc, soort, eigenaar, canoniek);
        assertEquals(regel, e.path("regel").asInt(-1), e.toString());
        List<String> gelezen = new ArrayList<>();
        for (JsonNode c : e.get("commentaar")) gelezen.add(c.asText());
        assertEquals(List.of(commentaar), gelezen, e.toString());
        return e;
    }
}
