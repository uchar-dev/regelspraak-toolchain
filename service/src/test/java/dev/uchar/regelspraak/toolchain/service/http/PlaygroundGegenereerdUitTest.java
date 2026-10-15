package dev.uchar.regelspraak.toolchain.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;
import dev.uchar.regelspraak.toolchain.engine.UcharBouw;
import dev.uchar.regelspraak.toolchain.service.TokaBron;

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
 * An element uchar made itself carries {@code gegenereerdUit} in {@code vocabulaires.elementen}, and a name of the
 * coverage that is no element stands in {@code gegenereerd} with its origin: uchar's tables
 * ({@code RegelSpraakModel.gegenereerd}, {@code TmsTranspiler.gegenereerd}), never a reading of the name. The source
 * below has the shapes of herleving-ww v2: the opgave marker on {@code maxdatum} of a subtype of a subtype and on
 * {@code herlevingsdatum}, a second {@code maxdatum} without one, a base rule on the middle type with a deviation for
 * its subtype, and a written name that begins with "berekende".
 */
class PlaygroundGegenereerdUitTest {

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

    /** The element of {@code vocabulaires.elementen} with this kind, owner and canonical name. */
    private static JsonNode element(JsonNode analyse, String soort, String eigenaar, String canoniek) {
        for (JsonNode e : analyse.get("vocabulaires").get("elementen"))
            if (e.get("soort").asText().equals(soort) && e.get("canoniek").asText().equals(canoniek)
                    && (eigenaar == null ? e.get("eigenaar").isNull() : eigenaar.equals(e.get("eigenaar").asText())))
                return e;
        throw new AssertionError("geen element " + soort + " " + eigenaar + "." + canoniek);
    }

    private static String uit(JsonNode g) {
        return g.get("soort").asText() + " " + (g.get("eigenaar").isNull() ? "" : g.get("eigenaar").asText() + ".")
                + g.get("naam").asText() + " (" + g.get("vorm").asText() + ")";
    }

    /** Every element as "SOORT eigenaar.naam <- <uit>" for those with {@code gegenereerdUit}, in the list's order. */
    private static List<String> gegenereerdeElementen(JsonNode analyse) {
        List<String> uit = new ArrayList<>();
        for (JsonNode e : analyse.get("vocabulaires").get("elementen")) {
            JsonNode g = e.get("gegenereerdUit");
            if (g == null) continue;
            uit.add(e.get("soort").asText() + " " + (e.get("eigenaar").isNull() ? "" : e.get("eigenaar").asText() + ".")
                    + e.get("canoniek").asText() + " <- " + uit(g));
        }
        return uit;
    }

    private static Map<String, String> gegenereerdeNamen(JsonNode analyse) {
        Map<String, String> uit = new TreeMap<>();
        for (JsonNode g : analyse.get("gegenereerd"))
            uit.put(g.get("naam").asText(), g.get("soort").asText() + " <- " + uit(g.get("gegenereerdUit")));
        return uit;
    }

    @Test void de_elementen_van_de_markering_en_de_subtypegroep_zeggen_waaruit_ze_komen() {
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
                gegenereerdeElementen(a));
        // the key and its order: the source element's kind, owner and name, then the form's key
        JsonNode berekend = element(a, "ATTRIBUUT", ONTSLAG, "berekende maxdatum");
        assertEquals(List.of("soort", "eigenaar", "naam", "vorm"), veldnamen(berekend.get("gegenereerdUit")));
        assertNull(berekend.get("regel"), "een gegenereerd element heeft geen declaratie");
        assertTrue(element(a, "VORM", null, "herlevingstermijn voor " + ONTSLAG).get("gegenereerdUit")
                .get("eigenaar").isNull());
        // written names: the marked attribute, the other maxdatum, a name with "berekende", the base of the group
        for (JsonNode e : List.of(element(a, "ATTRIBUUT", ONTSLAG, "maxdatum"),
                element(a, "ATTRIBUUT", "Uitkeringsduur", "maxdatum"),
                element(a, "ATTRIBUUT", ONTSLAG, "berekende einddatum"),
                element(a, "VORM", null, "berekende einddatum"),
                element(a, "VORM", null, "herlevingstermijn"))) {
            assertNull(e.get("gegenereerdUit"), e::toString);
            assertNotNull(e.get("regel"), e::toString);
        }
    }

    @Test void een_stapel_in_de_dekking_staat_in_gegenereerd_met_het_lid_zoals_gedeclareerd() {
        ObjectNode a = Playground.analyseer(HERLEVING);
        assertTrue(a.get("uitvoerbaar").asBoolean(), () -> a.path("weigering").asText());
        assertEquals(Map.of(
                "defaults A:Recht op uitkering WW.herlevingstermijn",
                "defaults <- ATTRIBUUT Recht op uitkering WW.herlevingstermijn (defaultsstapel)",
                "defaults A:" + ONTSLAG + ".herlevingstermijn",
                "defaults <- ATTRIBUUT Recht op uitkering WW.herlevingstermijn (defaultsstapel)",
                "defaults A:" + ONTSLAG + ".maxdatum",
                "defaults <- ATTRIBUUT " + ONTSLAG + ".maxdatum (defaultsstapel)",
                "defaults A:RechtOpUitkeringPeriode.herlevingsdatum",
                "defaults <- ATTRIBUUT RechtOpUitkeringPeriode.herlevingsdatum (defaultsstapel)"),
                gegenereerdeNamen(a));
        assertEquals(List.of("naam", "soort", "gegenereerdUit"), veldnamen(a.get("gegenereerd").get(0)));
        // every name of the coverage is an element or stands in gegenereerd
        Set<String> vormen = new HashSet<>();
        for (JsonNode e : a.get("vocabulaires").get("elementen"))
            if (e.get("soort").asText().equals("VORM")) vormen.add(e.get("canoniek").asText());
        Set<String> gegenereerd = gegenereerdeNamen(a).keySet();
        for (JsonNode d : a.get("dekking"))
            assertTrue(vormen.contains(d.get("naam").asText()) ^ gegenereerd.contains(d.get("naam").asText()),
                    d::toString);
    }

    @Test void een_versie_heet_in_gegenereerd_zoals_in_de_dekking_van_een_scenario() {
        String bron = """
                Vocabulaire cgm (standaard) (taal: nl)

                Objecttype de Jutter (mv: Jutters) (bezield) (cgm: JUTTER)
                    het gewicht (cgm: GEWICHT) Numeriek (geheel getal);

                Regel gewicht (cgm: R_GEWICHT)
                    geldig vanaf 01-01-2026
                        Het gewicht van een Jutter moet gesteld worden op 3.
                    geldig t/m 31-12-2025
                        Het gewicht van een Jutter moet gesteld worden op 2.
                """;
        ObjectNode canoniek = Playground.analyseer(bron);
        assertEquals(Map.of("gewicht #1", "regel <- VORM gewicht (versie)", "gewicht #2",
                "regel <- VORM gewicht (versie)"), gegenereerdeNamen(canoniek));
        assertNull(canoniek.get("gegenereerd").get(0).get("naamCanoniek"));
        ObjectNode cgm = Playground.analyseer(bron, "cgm");
        List<String> namen = new ArrayList<>(), canoniekeNamen = new ArrayList<>();
        for (JsonNode g : cgm.get("gegenereerd")) {
            namen.add(g.get("naam").asText());
            canoniekeNamen.add(g.get("naamCanoniek").asText());
        }
        assertEquals(List.of("R_GEWICHT #1", "R_GEWICHT #2"), namen);
        assertEquals(List.of("gewicht #1", "gewicht #2"), canoniekeNamen);
        // the origin stays canonical, as the owner and name of an element do
        assertEquals("VORM gewicht (versie)", uit(cgm.get("gegenereerd").get(1).get("gegenereerdUit")));
    }

    /**
     * Over every golden of uchar with a {@code bron.rgs}: an element has a declaration ({@code regel}) or an origin
     * ({@code gegenereerdUit}), never both, and every name of the coverage of a golden the exec-model branch builds
     * is an element or stands in {@code gegenereerd}.
     */
    /** Floors of the sweep below, measured 2026-10-15 on uchar's corpus at the pin of this card: elements, elements with
     *  an origin, goldens the exec-model branch builds, and effective stacks in their coverage. */
    private static final int MIN_ELEMENTEN = 1648, MIN_GEGENEREERD = 71, MIN_GEBOUWD = 122, MIN_STAPELS = 39;

    @Test void elke_golden_heeft_per_element_een_declaratie_of_een_herkomst() throws IOException {
        List<Path> bronnen;
        try (Stream<Path> s = Files.list(TokaBron.REPO.resolve("uchar/corpus/golden"))) {
            bronnen = s.map(p -> p.resolve("bron.rgs")).filter(Files::isRegularFile).sorted().toList();
        }
        List<String> fouten = new ArrayList<>();
        int elementen = 0, gegenereerd = 0, gebouwd = 0, stapels = 0;
        for (Path p : bronnen) {
            String bron = Files.readString(p);
            String golden = p.getParent().getFileName().toString();
            boolean scenario = Playground.bevatScenario(bron);
            RegelSpraakModel model = scenario ? ScenarioResolver.resolve(bron).model() : Resolver.resolve(bron);
            if (!model.isOk()) continue;
            Naamgeving naamgeving = Naamgeving.van(model, null);
            JsonNode voc = Playground.vocabulaires(model, naamgeving, Playground.parse(bron, scenario));
            if (voc.has("declaratieFout")) fouten.add(golden + ": " + voc.get("declaratieFout").asText());
            Set<String> vormen = new HashSet<>();
            for (JsonNode e : voc.get("elementen")) {
                elementen++;
                boolean regel = e.has("regel"), uit = e.has("gegenereerdUit");
                if (uit) gegenereerd++;
                if (e.get("soort").asText().equals("VORM")) vormen.add(e.get("canoniek").asText());
                if (regel == uit) fouten.add(golden + ": " + e + (regel ? " heeft beide" : " heeft geen van beide"));
            }
            DroolsBackend.Build build;
            try {
                build = UcharBouw.bouw(model, DroolsBackend.Branch.EXEC_MODEL);
            } catch (UcharBouw.NietGedragen n) {
                continue;
            }
            gebouwd++;
            JsonNode namen = Playground.gegenereerd(build, model, naamgeving);
            Set<String> genoemd = new HashSet<>();
            for (JsonNode g : namen) {
                genoemd.add(g.get("naam").asText());
                if (g.get("soort").asText().equals("defaults")) stapels++;
            }
            for (var d : build.dekking())
                if (vormen.contains(d.naam()) == genoemd.contains(d.naam()))
                    fouten.add(golden + ": de dekkingnaam '" + d.naam() + "' is "
                            + (vormen.contains(d.naam()) ? "een element en gegenereerd" : "geen element en niet gegenereerd"));
        }
        assertEquals(List.of(), fouten);
        int n = elementen, g = gegenereerd, b = gebouwd, s = stapels;
        assertTrue(n >= MIN_ELEMENTEN && g >= MIN_GEGENEREERD && b >= MIN_GEBOUWD && s >= MIN_STAPELS,
                () -> n + " elementen, " + g + " gegenereerd, " + b + " goldens gebouwd, " + s + " stapels; de vloeren "
                        + List.of(MIN_ELEMENTEN, MIN_GEGENEREERD, MIN_GEBOUWD, MIN_STAPELS));
    }

    private static List<String> veldnamen(JsonNode n) {
        List<String> uit = new ArrayList<>();
        n.fieldNames().forEachRemaining(uit::add);
        return uit;
    }

    @Test void zonder_gegenereerde_namen_geen_herkomst() {
        ObjectNode a = Playground.analyseer(HERLEVING.replace(" (mag opgegeven worden)", "")
                .replace("""
                        Afwijking van herlevingstermijn: herlevingstermijn na zelfstandig werk
                            geldig altijd
                                De herlevingstermijn van een RechtOpUitkeringWwBijOntslag moet gesteld worden op zijn einde zelfstandig werk
                                indien zijn einde zelfstandig werk gevuld is.
                        """, ""));
        assertTrue(a.get("resolver").get("ok").asBoolean(), () -> a.get("resolver").toString());
        assertEquals(List.of(), gegenereerdeElementen(a));
        assertFalse(a.get("gegenereerd").elements().hasNext(), () -> a.get("gegenereerd").toString());
    }
}
