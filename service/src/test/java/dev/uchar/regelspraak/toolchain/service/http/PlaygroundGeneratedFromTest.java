package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.toolchain.engine.Naming;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import dev.uchar.regelspraak.toolchain.service.TokaSource;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An element uchar made itself carries {@code generatedFrom} in {@code vocabularies.elements}, and a name of the
 * coverage that is no element stands in {@code generated} with its origin: uchar's tables
 * ({@code RegelSpraakModel.generated}, {@code TmsTranspiler.generated}), never a reading of the name. The source
 * below has the shapes of herleving-ww v2: the opgave marker on {@code maxdatum} of a subtype of a subtype and on
 * {@code herlevingsdatum}, a second {@code maxdatum} without one, a base rule on the middle type with a deviation for
 * its subtype, and a written name that begins with "berekende".
 */
class PlaygroundGeneratedFromTest {

    private static final String HERLEVING = """
            Eenheidsysteem Tijd
                de dag dg
                de maand mnd

            Objecttype het RechtOpUitkering (mv: RechtOpUitkerings)
                de eerste werkloosheidsdag Datum in dagen;

            Objecttype het Recht op uitkering WW is een RechtOpUitkering (mv: Rechten op uitkering WW)
                de herlevingstermijn Datum in dagen;

            Objecttype het RechtOpUitkeringWwBijOntslag is een Recht op uitkering WW (mv: RechtOpUitkeringWwBijOntslags)
                de maxdatum Datum in dagen (mag opgegeven worden);
                de berekende einddatum Datum in dagen;
                de einde zelfstandig werk Datum in dagen;

            Objecttype de RechtOpUitkeringPeriode (mv: RechtOpUitkeringPeriodes)
                de herlevingsdatum Datum in dagen (mag opgegeven worden);
                de aanvraagdatum Datum in dagen;

            Objecttype de Uitkeringsduur (mv: Uitkeringsduren)
                de maxdatum Datum in dagen;
                de ingangsdatum Datum in dagen;

            Regel maxdatum
                geldig altijd
                    De maxdatum van een RechtOpUitkeringWwBijOntslag moet berekend worden als zijn eerste werkloosheidsdag plus 6 mnd.

            Regel herlevingsdatum
                geldig altijd
                    De herlevingsdatum van een RechtOpUitkeringPeriode moet gesteld worden op zijn aanvraagdatum.

            Regel maxdatum van de duur
                geldig altijd
                    De maxdatum van een Uitkeringsduur moet berekend worden als zijn ingangsdatum plus 6 mnd.

            Regel herlevingstermijn
                geldig altijd
                    De herlevingstermijn van een Recht op uitkering WW moet berekend worden als zijn eerste werkloosheidsdag plus 12 mnd.

            Afwijking van herlevingstermijn: herlevingstermijn na zelfstandig werk
                geldig altijd
                    De herlevingstermijn van een RechtOpUitkeringWwBijOntslag moet gesteld worden op zijn einde zelfstandig werk
                    indien zijn einde zelfstandig werk gevuld is.

            Regel berekende einddatum
                geldig altijd
                    De berekende einddatum van een RechtOpUitkeringWwBijOntslag moet gesteld worden op zijn maxdatum.
            """;

    private static final String ONTSLAG = "RechtOpUitkeringWwBijOntslag";

    /** The element of {@code vocabularies.elements} with this kind, owner and canonical name. */
    private static JsonNode element(JsonNode analyse, String kind, String owner, String canonical) {
        for (JsonNode e : analyse.get("vocabularies").get("elements"))
            if (e.get("kind").asText().equals(kind) && e.get("canonical").asText().equals(canonical)
                    && (owner == null ? e.get("owner").isNull() : owner.equals(e.get("owner").asText())))
                return e;
        throw new AssertionError("geen element " + kind + " " + owner + "." + canonical);
    }

    private static String sink(JsonNode g) {
        return g.get("kind").asText() + " " + (g.get("owner").isNull() ? "" : g.get("owner").asText() + ".")
                + g.get("name").asText() + " (" + g.get("form").asText() + ")";
    }

    /** Every element as "SOORT owner.name <- <uit>" for those with {@code generatedFrom}, in the list's order. */
    private static List<String> generatedElements(JsonNode analyse) {
        List<String> sink = new ArrayList<>();
        for (JsonNode e : analyse.get("vocabularies").get("elements")) {
            JsonNode g = e.get("generatedFrom");
            if (g == null) continue;
            sink.add(e.get("kind").asText() + " " + (e.get("owner").isNull() ? "" : e.get("owner").asText() + ".")
                    + e.get("canonical").asText() + " <- " + sink(g));
        }
        return sink;
    }

    private static Map<String, String> generatedNames(JsonNode analyse) {
        Map<String, String> sink = new TreeMap<>();
        for (JsonNode g : analyse.get("generated"))
            sink.put(g.get("name").asText(), g.get("kind").asText() + " <- " + sink(g.get("generatedFrom")));
        return sink;
    }

    @Test void the_elements_of_the_marking_and_the_subtype_group_say_where_they_come_from() {
        ObjectNode a = Playground.analyseer(HERLEVING);
        assertTrue(a.get("resolver").get("ok").asBoolean(), () -> a.get("resolver").toString());
        String maxdatum = "ATTRIBUUT " + ONTSLAG + ".maxdatum (opgave-boven-berekening)";
        String herleving = "ATTRIBUUT RechtOpUitkeringPeriode.herlevingsdatum (opgave-boven-berekening)";
        assertEquals(List.of(
                "ATTRIBUUT " + ONTSLAG + ".opgegeven maxdatum <- " + maxdatum,
                "ATTRIBUUT " + ONTSLAG + ".berekende maxdatum <- " + maxdatum,
                "KENMERK " + ONTSLAG + ".afwijkende opgave maxdatum <- " + maxdatum,
                "ATTRIBUUT RechtOpUitkeringPeriode.opgegeven herlevingsdatum <- " + herleving,
                "ATTRIBUUT RechtOpUitkeringPeriode.berekende herlevingsdatum <- " + herleving,
                "KENMERK RechtOpUitkeringPeriode.afwijkende opgave herlevingsdatum <- " + herleving,
                "VORM afwijking opgave maxdatum <- " + maxdatum,
                "VORM afwijking opgave herlevingsdatum <- " + herleving,
                "VORM controle opgave maxdatum <- " + maxdatum,
                "VORM controle opgave herlevingsdatum <- " + herleving,
                "VORM herlevingstermijn voor " + ONTSLAG + " <- VORM herlevingstermijn (uitzondering-voor-subtype)",
                "VORM opgave boven berekening maxdatum <- " + maxdatum,
                "VORM opgave boven berekening herlevingsdatum <- " + herleving),
                generatedElements(a));
        // the key and its order: the source element's kind, owner and name, then the form's key
        JsonNode computed = element(a, "ATTRIBUUT", ONTSLAG, "berekende maxdatum");
        assertEquals(List.of("kind", "owner", "name", "form"), fieldNames(computed.get("generatedFrom")));
        assertNull(computed.get("line"), "een gegenereerd element heeft geen declaratie");
        assertTrue(element(a, "VORM", null, "herlevingstermijn voor " + ONTSLAG).get("generatedFrom")
                .get("owner").isNull());
        // written names: the marked attribute, the other maxdatum, a name with "berekende", the base of the group
        for (JsonNode e : List.of(element(a, "ATTRIBUUT", ONTSLAG, "maxdatum"),
                element(a, "ATTRIBUUT", "Uitkeringsduur", "maxdatum"),
                element(a, "ATTRIBUUT", ONTSLAG, "berekende einddatum"),
                element(a, "VORM", null, "berekende einddatum"),
                element(a, "VORM", null, "herlevingstermijn"))) {
            assertNull(e.get("generatedFrom"), e::toString);
            assertNotNull(e.get("line"), e::toString);
        }
    }

    @Test void a_stack_in_the_coverage_is_in_generated_with_the_member_like_declared() {
        ObjectNode a = Playground.analyseer(HERLEVING);
        assertTrue(a.get("executable").asBoolean(), () -> a.path("refusal").asText());
        assertEquals(Map.of(
                "defaults A:Recht op uitkering WW.herlevingstermijn",
                "defaults <- ATTRIBUUT Recht op uitkering WW.herlevingstermijn (defaultsstapel)",
                "defaults A:" + ONTSLAG + ".herlevingstermijn",
                "defaults <- ATTRIBUUT Recht op uitkering WW.herlevingstermijn (defaultsstapel)",
                "defaults A:" + ONTSLAG + ".maxdatum",
                "defaults <- ATTRIBUUT " + ONTSLAG + ".maxdatum (defaultsstapel)",
                "defaults A:RechtOpUitkeringPeriode.herlevingsdatum",
                "defaults <- ATTRIBUUT RechtOpUitkeringPeriode.herlevingsdatum (defaultsstapel)"),
                generatedNames(a));
        assertEquals(List.of("name", "kind", "generatedFrom"), fieldNames(a.get("generated").get(0)));
        // every name of the coverage is an element or stands in gegenereerd
        Set<String> forms = new HashSet<>();
        for (JsonNode e : a.get("vocabularies").get("elements"))
            if (e.get("kind").asText().equals("VORM")) forms.add(e.get("canonical").asText());
        Set<String> generated = generatedNames(a).keySet();
        for (JsonNode d : a.get("coverage"))
            assertTrue(forms.contains(d.get("name").asText()) ^ generated.contains(d.get("name").asText()),
                    d::toString);
    }

    @Test void a_version_named_in_generated_like_in_the_coverage_of_a_scenario() {
        String source = """
                Vocabulaire cgm (standaard) (taal: nl)

                Objecttype de Jutter (mv: Jutters) (bezield) (cgm: JUTTER)
                    het gewicht (cgm: GEWICHT) Numeriek (geheel getal);

                Regel gewicht (cgm: R_GEWICHT)
                    geldig vanaf 01-01-2026
                        Het gewicht van een Jutter moet gesteld worden op 3.
                    geldig t/m 31-12-2025
                        Het gewicht van een Jutter moet gesteld worden op 2.
                """;
        ObjectNode canonical = Playground.analyseer(source);
        assertEquals(Map.of("gewicht #1", "regel <- VORM gewicht (versie)", "gewicht #2",
                "regel <- VORM gewicht (versie)"), generatedNames(canonical));
        assertNull(canonical.get("generated").get(0).get("nameCanonical"));
        ObjectNode cgm = Playground.analyseer(source, "cgm");
        List<String> names = new ArrayList<>(), canonicalNames = new ArrayList<>();
        for (JsonNode g : cgm.get("generated")) {
            names.add(g.get("name").asText());
            canonicalNames.add(g.get("nameCanonical").asText());
        }
        assertEquals(List.of("R_GEWICHT #1", "R_GEWICHT #2"), names);
        assertEquals(List.of("gewicht #1", "gewicht #2"), canonicalNames);
        // the origin stays canonical, as the owner and name of an element do
        assertEquals("VORM gewicht (versie)", sink(cgm.get("generated").get(1).get("generatedFrom")));
    }

    /**
     * Over every golden of uchar with a {@code source.rgs}: an element has a declaration ({@code line}) or an origin
     * ({@code generatedFrom}), never both, and every name of the coverage of a golden the exec-model branch builds
     * is an element or stands in {@code generated}.
     */
    /** Floors of the sweep below, measured 2026-10-15 on uchar's corpus at the pin of this card: elements, elements with
     *  an origin, goldens the exec-model branch builds, and effective stacks in their coverage. */
    private static final int MIN_ELEMENTS = 1648, MIN_GENERATED = 71, MIN_BUILT = 122, MIN_STACKS = 39;

    @Test void every_golden_has_per_element_a_declaration_or_an_origin() throws IOException {
        List<Path> sources;
        try (Stream<Path> s = Files.list(TokaSource.REPO.resolve("uchar/corpus/golden"))) {
            sources = s.map(p -> p.resolve("source.rgs")).filter(Files::isRegularFile).sorted().toList();
        }
        List<String> errors = new ArrayList<>();
        int elements = 0, generated = 0, built = 0, stacks = 0;
        for (Path p : sources) {
            String source = Files.readString(p);
            String golden = p.getParent().getFileName().toString();
            boolean scenario = Playground.containsScenario(source);
            RegelSpraakModel model = scenario ? ScenarioResolver.resolve(source).model() : Resolver.resolve(source);
            if (!model.isOk()) continue;
            Naming naming = Naming.of(model, null);
            JsonNode voc = Playground.vocabularies(model, naming, Playground.parse(source, scenario));
            if (voc.has("declarationError")) errors.add(golden + ": " + voc.get("declarationError").asText());
            Set<String> forms = new HashSet<>();
            for (JsonNode e : voc.get("elements")) {
                elements++;
                boolean line = e.has("line"), sink = e.has("generatedFrom");
                if (sink) generated++;
                if (e.get("kind").asText().equals("VORM")) forms.add(e.get("canonical").asText());
                if (line == sink) errors.add(golden + ": " + e + (line ? " heeft beide" : " heeft geen van beide"));
            }
            DroolsBackend.Build build;
            try {
                build = UcharBuild.assemble(model, DroolsBackend.Branch.EXEC_MODEL);
            } catch (UcharBuild.NotCarried n) {
                continue;
            }
            built++;
            JsonNode names = Playground.generated(build, model, naming);
            Set<String> named = new HashSet<>();
            for (JsonNode g : names) {
                named.add(g.get("name").asText());
                if (g.get("kind").asText().equals("defaults")) stacks++;
            }
            for (var d : build.coverage())
                if (forms.contains(d.name()) == named.contains(d.name()))
                    errors.add(golden + ": de dekkingnaam '" + d.name() + "' is "
                            + (forms.contains(d.name()) ? "een element en gegenereerd" : "geen element en niet gegenereerd"));
        }
        assertEquals(List.of(), errors);
        int n = elements, g = generated, b = built, s = stacks;
        assertTrue(n >= MIN_ELEMENTS && g >= MIN_GENERATED && b >= MIN_BUILT && s >= MIN_STACKS,
                () -> n + " elementen, " + g + " gegenereerd, " + b + " goldens gebouwd, " + s + " stapels; de vloeren "
                        + List.of(MIN_ELEMENTS, MIN_GENERATED, MIN_BUILT, MIN_STACKS));
    }

    private static List<String> fieldNames(JsonNode n) {
        List<String> sink = new ArrayList<>();
        n.fieldNames().forEachRemaining(sink::add);
        return sink;
    }

    @Test void without_generated_names_no_origin() {
        ObjectNode a = Playground.analyseer(HERLEVING.replace(" (mag opgegeven worden)", "")
                .replace("""
                        Afwijking van herlevingstermijn: herlevingstermijn na zelfstandig werk
                            geldig altijd
                                De herlevingstermijn van een RechtOpUitkeringWwBijOntslag moet gesteld worden op zijn einde zelfstandig werk
                                indien zijn einde zelfstandig werk gevuld is.
                        """, ""));
        assertTrue(a.get("resolver").get("ok").asBoolean(), () -> a.get("resolver").toString());
        assertEquals(List.of(), generatedElements(a));
        assertFalse(a.get("generated").elements().hasNext(), () -> a.get("generated").toString());
    }
}
