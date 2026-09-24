package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.ResolvedScenario;
import io.github.yhavinga.regelspraak.ir.ResolvedScenario.*;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 typegate + positieve resolver-tests voor de scenariotoetsen-extensie (docs/SCENARIOTOETSEN.md).
 * Jar-only: drijft {@link ScenarioResolver#resolve} rechtstreeks (geen Drools). De typegate eist dat elk
 * slecht-getypeerd scenario een resolver-ERROR met het JUISTE regelnummer oplevert (assert op regelnummer,
 * niet alleen op aanwezigheid). Het regelnummer wordt DYNAMISCH afgeleid uit de bron (het regelnummer van de
 * regel die de aanstootgevende tekst bevat) i.p.v. hardgecodeerd — robuust bij tekstwijzigingen. De positieve
 * tests pinnen: het contract-voorbeeld resolvet schoon; merge-semantiek (gebaseerd-op + inline, her-toewijzing
 * wint, parameter-override wint); exacte eenheidsconversie (2 u op een minuut-attribuut wordt exact 120).
 */
class ScenarioTypeGateTest {

    /** A self-contained, spec-shaped model preamble: a Tijd unit system (for the u→minuut conversion), an
     *  afstand system, two object types (a km attribuut, a minuut attribuut, a Tekst attribuut, a kenmerk;
     *  a leeftijd/kenmerk on the person), a feittype whose "reis" role is to-one and "passagier" to-many, and
     *  a EUR parameter. Resolves clean on its own (asserted by {@link #model_preamble_is_schoon}). */
    private static final String MODEL =
            "Eenheidsysteem Tijd\n"
          + "    de seconde          s        =    1/60 minuut\n"
          + "    de minuut           minuut   =    1/60 u\n"
          + "    het uur             u        =    1/24 dg\n"
          + "    de dag              dg\n"
          + "Eenheidsysteem afstand\n"
          + "    de kilometer (mv: kilometers)     km      =    1000 m\n"
          + "    de meter (mv: meters)             m\n"
          + "Objecttype de Vlucht (mv: Vluchten)\n"
          + "    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;\n"
          + "    de duur Numeriek (geheel getal) met eenheid minuut;\n"
          + "    de naam Tekst;\n"
          + "    is belaste reis kenmerk;\n"
          + "Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)\n"
          + "    de leeftijd Numeriek (geheel getal) met eenheid jr;\n"
          + "    is minderjarige kenmerk;\n"
          + "Feittype reisrelatie\n"
          + "    de reis Vlucht\n"
          + "    de passagier Natuurlijk persoon\n"
          + "Eén reis betreft meerdere passagiers\n"
          + "Parameter het basistarief : Numeriek (getal met 2 decimalen) met eenheid EUR;\n";

    private static String bron(String scenarioTekst) { return MODEL + scenarioTekst; }

    private static ScenarioBundel resolve(String scenarioTekst) {
        return ScenarioResolver.resolve(bron(scenarioTekst));
    }

    /** The 1-based source line of the first line CONTAINING {@code merker} in {@code src}. */
    private static int regelVan(String src, String merker) {
        String[] regels = src.split("\n", -1);
        for (int i = 0; i < regels.length; i++) if (regels[i].contains(merker)) return i + 1;
        throw new AssertionError("merker '" + merker + "' niet in bron gevonden");
    }

    /** Assert an ERROR whose message contains {@code fragment} sits on the line that contains {@code lijnMerker}. */
    private static void assertFoutOpLijn(ScenarioBundel b, String src, String lijnMerker, String fragment) {
        int regel = regelVan(src, lijnMerker);
        List<Diagnostic> passend = b.model().errors().stream()
                .filter(d -> d.line() == regel && d.message().contains(fragment)).toList();
        assertFalse(passend.isEmpty(),
                () -> "verwachtte een ERROR met '" + fragment + "' op regel " + regel + " (regel met '" + lijnMerker
                        + "'), maar de diagnostics zijn: " + b.model().errors());
    }

    @Test void model_preamble_is_schoon() {
        // Zonder scenario's mag het model geen resolver-fouten geven (de preamble is het fundament van de gate).
        ScenarioBundel b = ScenarioResolver.resolve(MODEL);
        assertTrue(b.isOk(), () -> "de MODEL-preamble zou schoon moeten resolven: " + b.model().errors());
    }

    // ==================================================================
    // G4 typegate — ≥ 6 slecht-getypeerde scenario's, elk een ERROR met regelnummer
    // ==================================================================

    @Test void eenheids_mismatch_onverenigbaar() {
        String sc = "Situatie s:\neen Vlucht V1\n- de afstand tot bestemming is 200 EUR\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "200 EUR", "afstand tot bestemming");
        assertFalse(b.isOk());
    }

    @Test void datatype_mismatch_tekst_op_numeriek() {
        String sc = "Situatie s:\neen Vlucht V1\n- de afstand tot bestemming is \"tweehonderd\"\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "tweehonderd", "afstand tot bestemming");
        assertFalse(b.isOk());
    }

    @Test void onbekend_attribuut() {
        String sc = "Situatie s:\neen Vlucht V1\n- de wingspan is 40 m\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "wingspan", "onbekend attribuut 'wingspan'");
    }

    @Test void onbekend_objecttype() {
        String sc = "Situatie s:\neen Ruimteschip R1\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "Ruimteschip", "onbekend objecttype 'Ruimteschip'");
    }

    /** De rol-doelcheck loopt over de SAMENGESTELDE situatie, dus pas wanneer een scenario de situatie
     *  gebruikt (een ongebruikte Situatie mag naar labels wijzen die een latere bron aanlevert). */
    private static String metScenario(String situatie) {
        return situatie
              + "Scenario x\ntoetst de regel bestaande regel\ngebaseerd op de situatie s\n"
              + "Met als verwacht resultaat:\n- Tom is minderjarige\n";
    }

    private static final String REGEL_BESTAAND =
            "Regel bestaande regel\ngeldig altijd\n"
          + "    Een Natuurlijk persoon is minderjarige indien zijn leeftijd kleiner is dan 18 jr.\n";

    @Test void rol_verwijst_naar_niet_gedeclareerd_label() {
        // 'de reis is V9' terwijl V9 nergens in de samengestelde situatie bestaat — moet een resolve-time
        // ERROR zijn (nooit een fire-time verrassing), ook al is de rol zelf legaal.
        String sc = metScenario("Situatie s:\neen Natuurlijk persoon Tom\n- de leeftijd is 12 jr\n- de reis is V9\n");
        ScenarioBundel b = ScenarioResolver.resolve(MODEL + REGEL_BESTAAND + sc);
        assertFoutOpLijn(b, MODEL + REGEL_BESTAAND + sc, "de reis is V9", "verwijst naar label 'V9'");
        assertFalse(b.isOk());
    }

    @Test void rol_doeltype_mismatch() {
        // 'de reis is Piet' waar Piet een Natuurlijk persoon is maar de rol een Vlucht verwacht.
        String sc = metScenario("Situatie s:\neen Natuurlijk persoon Piet\n- de leeftijd is 30 jr\n"
              + "een Natuurlijk persoon Tom\n- de leeftijd is 12 jr\n- de reis is Piet\n");
        ScenarioBundel b = ScenarioResolver.resolve(MODEL + REGEL_BESTAAND + sc);
        assertFoutOpLijn(b, MODEL + REGEL_BESTAAND + sc, "de reis is Piet", "verwacht een Vlucht");
        assertFalse(b.isOk());
    }

    @Test void rol_naar_inline_instantie_over_bronnen_heen_is_legaal() {
        // Cross-source: de gebaseerd-op-situatie wijst met een rol naar een label dat pas de inline sectie
        // declareert — de check loopt over de SAMENGESTELDE situatie, dus dit resolvet schoon.
        String sc = "Situatie s:\neen Natuurlijk persoon Tom\n- de reis is V1\n"
              + "Scenario x\ntoetst de regel bestaande regel\ngebaseerd op de situatie s\n"
              + "Gegeven is de volgende situatie:\neen Vlucht V1\n- de afstand tot bestemming is 200 km\n"
              + "Met als verwacht resultaat:\n- de afstand tot bestemming van V1 is 200 km\n";
        String vol = MODEL
              + "Regel bestaande regel\ngeldig altijd\n"
              + "    De afstand tot bestemming van een Vlucht moet gesteld worden op 200 km.\n" + sc;
        ScenarioBundel b = ScenarioResolver.resolve(vol);
        assertTrue(b.isOk(), () -> "cross-source rol-referentie hoort schoon te resolven: " + b.model().errors());
    }

    @Test void onbekende_scope_regel() {
        String sc = "Situatie s:\neen Vlucht V1\n- de afstand tot bestemming is 200 km\n"
              + "Scenario x\ntoetst de regel niet bestaande regel\ngebaseerd op de situatie s\n"
              + "Met als verwacht resultaat:\n- de afstand tot bestemming van V1 is 200 km\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "toetst de regel niet bestaande regel", "onbekende regel 'niet bestaande regel'");
    }

    @Test void onbekende_parameter() {
        String sc = "Situatie s:\nde parameter het onbekende tarief is 10,00 EUR\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "het onbekende tarief", "onbekende parameter 'het onbekende tarief'");
    }

    @Test void onbekend_kenmerk() {
        String sc = "Situatie s:\neen Vlucht V1\n- is supersonisch\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "supersonisch", "onbekend kenmerk 'supersonisch'");
    }

    @Test void rol_arity_to_one_met_zijn_lijst() {
        String sc = "Situatie s:\neen Natuurlijk persoon P1\n- de reis zijn V1 en V2\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "de reis zijn", "naar-één-rol");
    }

    @Test void label_gelijk_aan_objecttypenaam() {
        String sc = "Situatie s:\neen Vlucht Vlucht\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "een Vlucht Vlucht", "botst");
    }

    @Test void dubbel_gedeclareerde_situatie() {
        String sc = "Situatie s:\neen Vlucht V1\n- de afstand tot bestemming is 200 km\n"
              + "Situatie s:\neen Vlucht V2\n- de afstand tot bestemming is 100 km\n";
        // de TWEEDE 'Situatie s:' is de dubbele — vind de laatste regel die 'Situatie s:' bevat
        ScenarioBundel b = resolve(sc);
        String[] regels = bron(sc).split("\n", -1);
        int tweede = -1;
        for (int i = 0; i < regels.length; i++) if (regels[i].contains("Situatie s:")) tweede = i + 1;
        int tweedeF = tweede;
        List<Diagnostic> passend = b.model().errors().stream()
                .filter(d -> d.line() == tweedeF && d.message().contains("meer dan eens gedeclareerd")).toList();
        assertFalse(passend.isEmpty(), () -> "dubbele situatie op regel " + tweedeF + " verwacht: " + b.model().errors());
    }

    @Test void scenario_zonder_situatiebron() {
        String sc = "Scenario x\ntoetst de regelgroep g\nMet als verwacht resultaat:\n- de gegevens zijn consistent\n";
        ScenarioBundel b = resolve(sc);
        assertFoutOpLijn(b, bron(sc), "Scenario x", "geen enkele situatiebron");
    }

    @Test void defaults_blok_is_een_benoemde_error_tot_de_defaults_kaart() {
        // Sinds uchar e5c728b9 parseert testDocument de defaults-blokken van het uchar-dialect, maar de scenarioroute
        // neemt ze niet mee in het model. Tot dd798ba draaide dit scenario daardoor zonder melding op een model zonder
        // die blokken (0 fouten, geen prioritizedDefaults). Elk blok is nu een benoemde ERROR op zijn eigen regel, tot
        // de uchar-kaart regelspraak-java-defaults-in-scenario-en-werkbank de defaults in de scenarioroute brengt.
        String regels = """
            Objecttype de Jutter (mv: Jutters) (bezield)
                is bergingsbevoegd kenmerk (bijvoeglijk);
                is noodweer verklaard kenmerk (bijvoeglijk);
                het aantal geborgen wrakken Numeriek (niet-negatief geheel getal);
                is kustwacht kenmerk (bijvoeglijk);
                is havenmeester kenmerk (bijvoeglijk);
                het wachtgeld Numeriek (geheel getal);
            Regel bergingsbevoegd bij quotum
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien zijn aantal geborgen wrakken groter of gelijk is aan 20.
            Uitzondering op bergingsbevoegd bij quotum: bergingsbevoegd bij noodweer
                geldig altijd
                    Een Jutter is bergingsbevoegd
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is noodweer verklaard
                    - zijn aantal geborgen wrakken is groter of gelijk aan 8.
            Uitzonderingen ongeordend:
            Regel wachtgeld voor kustwachters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 30
                    indien hij kustwacht is.
            Regel wachtgeld voor havenmeesters
                geldig altijd
                    Het wachtgeld van een Jutter moet gesteld worden op 45
                    indien hij havenmeester is.
            """;
        RegelSpraakModel productie = Resolver.resolve(regels);
        assertTrue(productie.errors().isEmpty(), () -> "de productieroute moet de regels foutloos resolven: " + productie.errors());
        assertFalse(productie.prioritizedDefaults.isEmpty(), "de productieroute draagt de defaults-blokken");

        String src = regels + """
            Situatie twintig wrakken:
            een Jutter J
            - het aantal geborgen wrakken is 20
            Scenario quotum
            toetst de regel bergingsbevoegd bij quotum
            gebaseerd op de situatie twintig wrakken
            Met als verwacht resultaat:
            - J is bergingsbevoegd
            """;
        ScenarioBundel b = ScenarioResolver.resolve(src);
        assertEquals(2, b.model().errors().size(), () -> "precies één ERROR per defaults-blok verwacht: " + b.model().errors());
        assertFoutOpLijn(b, src, "Uitzondering op bergingsbevoegd bij quotum",
                "het blok 'Uitzondering op bergingsbevoegd bij quotum: bergingsbevoegd bij noodweer'");
        assertFoutOpLijn(b, src, "Uitzonderingen ongeordend:", "het blok 'Uitzonderingen ongeordend:'");
        assertTrue(b.model().errors().stream()
                        .allMatch(d -> d.message().contains("regelspraak-java-defaults-in-scenario-en-werkbank")),
                () -> "elke weigering noemt de kaart: " + b.model().errors());
    }

    // ==================================================================
    // Positieve tests
    // ==================================================================

    @Test void contract_voorbeeld_resolvet_schoon() {
        String model =
                "Objecttype de Vlucht (mv: Vluchten)\n"
              + "    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;\n"
              + "    is belaste reis kenmerk;\n"
              + "Objecttype de Natuurlijk persoon (mv: Natuurlijke personen)\n"
              + "    de belasting op basis van afstand Numeriek (getal met 2 decimalen) met eenheid EUR;\n"
              + "    de te betalen belasting Numeriek (getal met 2 decimalen) met eenheid EUR;\n"
              + "Feittype reisrelatie\n"
              + "    de reis Vlucht\n"
              + "    de passagier Natuurlijk persoon\n"
              + "Eén reis betreft meerdere passagiers\n"
              + "Parameter het lage basistarief eerste schijf : Numeriek (getal met 2 decimalen) met eenheid EUR;\n"
              + "Regel Kenmerktoekenning belaste reis\n"
              + "geldig altijd\n"
              + "Een Vlucht is een belaste reis indien de afstand tot bestemming van de Vlucht groter is dan 100 km.\n"
              + "Regel belasting op basis van afstand\n"
              + "geldig altijd\n"
              + "De belasting op basis van afstand van een Natuurlijk persoon moet berekend worden als 60,00 EUR.\n"
              + "Regel te betalen belasting\n"
              + "geldig altijd\n"
              + "De te betalen belasting van een Natuurlijk persoon moet berekend worden als 50,00 EUR.\n";
        String situatie =
                "Situatie Tom met vlucht:\n"
              + "een Vlucht V1\n"
              + "- de afstand tot bestemming is 200 km\n"
              + "- is belaste reis\n"
              + "een Natuurlijk persoon Tom\n"
              + "- de reis is V1\n";
        String scenario =
                "Scenario Tom betaalt belasting over zijn vlucht\n"
              + "toetst de regels Kenmerktoekenning belaste reis, belasting op basis van afstand en te betalen belasting\n"
              + "gebaseerd op de situatie Tom met vlucht\n"
              + "Gegeven is de volgende situatie:\n"
              + "de parameter het lage basistarief eerste schijf is 100,00 EUR\n"
              + "Met als verwacht resultaat:\n"
              + "- V1 is een belaste reis\n"
              + "- de belasting op basis van afstand van Tom is 60,00 EUR\n"
              + "- de te betalen belasting van Tom is 50,00 EUR\n"
              + "- de gegevens zijn consistent\n";
        ScenarioBundel b = ScenarioResolver.resolve(model + situatie + scenario);
        assertTrue(b.isOk(), () -> "contract-voorbeeld zou schoon moeten resolven, maar: " + b.model().errors());
        assertEquals(1, b.scenarios().size());
        ResolvedScenario s = b.scenarios().get(0);
        assertEquals(ScopeSoort.MEERDERE_REGELS, s.scope().soort());
        assertEquals(3, s.scope().regels().size());
        assertTrue(s.scope().regels().stream().allMatch(ScopeRegel::bestaat), "alle scope-regels bestaan");
        assertNotNull(s.situatie().instantie("V1"));
        assertNotNull(s.situatie().instantie("Tom"));
        assertEquals(1, s.situatie().parameters().size());
        assertEquals(4, s.verwachtingen().size());
    }

    @Test void merge_semantiek_hertoewijzing_en_parameter_override() {
        String sc =
                "Situatie basis:\n"
              + "een Vlucht V1\n"
              + "- de afstand tot bestemming is 200 km\n"
              + "de parameter het basistarief is 100,00 EUR\n"
              + "Regel r\n"
              + "geldig altijd\n"
              + "De afstand tot bestemming van een Vlucht moet berekend worden als 200 km.\n"
              + "Scenario x\n"
              + "toetst de regel r\n"
              + "gebaseerd op de situatie basis\n"
              + "Gegeven is de volgende situatie:\n"
              + "een Vlucht V1\n"
              + "- de afstand tot bestemming is 350 km\n"           // her-toewijzing wint later
              + "de parameter het basistarief is 250,00 EUR\n"      // parameter-override wint later
              + "Met als verwacht resultaat:\n"
              + "- de afstand tot bestemming van V1 is 350 km\n";
        ScenarioBundel b = resolve(sc);
        assertTrue(b.isOk(), () -> "merge zou schoon moeten resolven: " + b.model().errors());
        ResolvedScenario s = b.scenarios().get(0);
        Instantie v1 = s.situatie().instantie("V1");
        assertNotNull(v1);
        assertEquals(1, v1.attributen().size(), "V1 is één instantie na de merge (zelfde LABEL)");
        assertEquals(0, new BigDecimal("350").compareTo(v1.attributen().get(0).waarde().getal()),
                "de latere her-toewijzing (350) wint");
        assertEquals(1, s.situatie().parameters().size(), "één parameter na override");
        assertEquals(0, new BigDecimal("250.00").compareTo(s.situatie().parameters().get(0).waarde().getal()),
                "de latere parameterwaarde (250) wint");
    }

    @Test void eenheidsconversie_2_uur_wordt_exact_120_minuut() {
        String sc =
                "Situatie basis:\n"
              + "een Vlucht V1\n"
              + "- de duur is 2 u\n"                                // 2 u op een minuut-attribuut
              + "Regel r\n"
              + "geldig altijd\n"
              + "De duur van een Vlucht moet berekend worden als 120 minuut.\n"
              + "Scenario x\n"
              + "toetst de regel r\n"
              + "gebaseerd op de situatie basis\n"
              + "Met als verwacht resultaat:\n"
              + "- de duur van V1 is 120 minuut\n";
        ScenarioBundel b = resolve(sc);
        assertTrue(b.isOk(), () -> "eenheidsconversie zou schoon moeten resolven: " + b.model().errors());
        ResolvedScenario s = b.scenarios().get(0);
        Instantie v1 = s.situatie().instantie("V1");
        assertEquals(0, new BigDecimal("120").compareTo(v1.attributen().get(0).waarde().getal()),
                "2 u wordt exact 120 minuut");
        assertEquals(0, new BigDecimal("120").compareTo(s.verwachtingen().get(0).verwachteWaarde().getal()));
    }

    @Test void peildatum_gate_ontbrekende_peildatum() {
        String sc =
                "Situatie basis:\n"
              + "een Vlucht V1\n"
              + "- de afstand tot bestemming is 200 km\n"
              + "Regel tijdregel\n"
              + "geldig vanaf 01-01-2020\n"
              + "De afstand tot bestemming van een Vlucht moet berekend worden als 200 km.\n"
              + "Scenario x\n"                                      // GEEN peildatum
              + "toetst de regel tijdregel\n"
              + "gebaseerd op de situatie basis\n"
              + "Met als verwacht resultaat:\n"
              + "- de afstand tot bestemming van V1 is 200 km\n";
        ScenarioBundel b = resolve(sc);
        assertTrue(b.model().errors().stream().anyMatch(d -> d.message().contains("Rekendatum")
                        && d.message().contains("geen peildatum")),
                () -> "peildatum-gate zou moeten vuren: " + b.model().errors());
    }

    @Test void parameter_gate_scope_regel_zonder_parameterwaarde() {
        String sc =
                "Situatie basis:\n"
              + "een Vlucht V1\n"
              + "- de afstand tot bestemming is 200 km\n"          // GEEN parameterwaarde
              + "Regel tariefregel\n"
              + "geldig altijd\n"
              + "De duur van een Vlucht moet berekend worden als het basistarief.\n"
              + "Scenario x\n"
              + "toetst de regel tariefregel\n"
              + "gebaseerd op de situatie basis\n"
              + "Met als verwacht resultaat:\n"
              + "- de duur van V1 is 100 minuut\n";
        ScenarioBundel b = resolve(sc);
        assertTrue(b.model().errors().stream().anyMatch(d -> d.message().contains("basistarief")
                        && d.message().contains("geen waarde")),
                () -> "parametergate zou moeten vuren: " + b.model().errors());
    }
}
