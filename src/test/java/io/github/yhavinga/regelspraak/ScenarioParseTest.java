package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GRAMMAR-LAYER smoke test for the scenariotoetsen extension (docs/SCENARIOTOETSEN.md,
 * the '§Scenariotoetsen' divergence). Jar-only: it drives the ANTLR lexer/parser
 * directly (no resolver, no Drools), asserting a clean parse under {@code testDocument}
 * — no lexer/parser syntax error, no error node, whole token stream consumed — exactly
 * the discipline tools/Parse.java enforces for the corpus verifier.
 *
 * Covered: (a) the two verbatim examples from the contract + representative single-form
 * scenarios/situaties; (b) NEGATIVE — a scenario block is rejected under the production
 * {@code regelSpraakDocument}; (c) a TOKA-shaped scope list with multi-word rule names.
 */
class ScenarioParseTest {

    // ---- parse harness (mirrors tools/Parse.java's pass/fail semantics) ----

    private record ParseUitkomst(int syntaxFouten, int errorNodes, boolean allesGeconsumeerd,
                                  List<String> meldingen) {
        boolean schoon() { return syntaxFouten == 0 && errorNodes == 0 && allesGeconsumeerd; }
    }

    /** Parse {@code bron} starting at the given rule; report syntax errors, error nodes, and full consumption. */
    private static ParseUitkomst parse(String bron, Function<RegelSpraak, ParseTree> startRegel) {
        List<String> meldingen = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                              String msg, RecognitionException e) {
                meldingen.add("regel " + line + ":" + col + " " + msg);
            }
        };
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(bron));
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RegelSpraak parser = new RegelSpraak(tokens);
        parser.removeErrorListeners(); parser.addErrorListener(listener);

        ParseTree tree = startRegel.apply(parser);
        int errorNodes = telErrorNodes(tree);
        boolean allesGeconsumeerd = parser.getInputStream().LA(1) == Token.EOF;
        return new ParseUitkomst(meldingen.size(), errorNodes, allesGeconsumeerd, meldingen);
    }

    private static int telErrorNodes(ParseTree t) {
        if (t instanceof ErrorNode) return 1;
        int c = 0;
        for (int i = 0; i < t.getChildCount(); i++) c += telErrorNodes(t.getChild(i));
        return c;
    }

    private static void assertSchoon(String naam, String bron) {
        ParseUitkomst u = parse(bron, RegelSpraak::testDocument);
        assertTrue(u.schoon(), () -> naam + " zou schoon onder testDocument moeten parseren, maar: "
                + "syntaxfouten=" + u.syntaxFouten() + " errorNodes=" + u.errorNodes()
                + " allesGeconsumeerd=" + u.allesGeconsumeerd() + " meldingen=" + u.meldingen());
    }

    // ---- fixtures ----

    /** The verbatim herbruikbare situatie from docs/SCENARIOTOETSEN.md. */
    private static final String SITUATIE_UIT_CONTRACT =
            "Situatie Tom met vlucht:\n"
          + "een Vlucht V1\n"
          + "- de afstand tot bestemming is 200 km\n"
          + "- is bereikbaar per trein\n"
          + "een Natuurlijk persoon Tom\n"
          + "- de reis is V1\n"
          + "- is passagier van 25 tot en met 64 jaar\n"
          + "de parameter de korting bij gebruik niet fossiele brandstof is 10,00 EUR\n";

    /** The verbatim scenario from docs/SCENARIOTOETSEN.md (needs the situatie above to precede it in one doc). */
    private static final String SCENARIO_UIT_CONTRACT =
            "Scenario Tom betaalt belasting over zijn vlucht\n"
          + "toetst de regels Kenmerktoekenning belaste reis, belasting op basis van afstand en te betalen belasting\n"
          + "met peildatum 01-01-2024\n"
          + "gebaseerd op de situatie Tom met vlucht\n\n"
          + "Gegeven is de volgende situatie:\n"
          + "de parameter het lage basistarief eerste schijf is 100,00 EUR\n\n"
          + "Met als verwacht resultaat:\n"
          + "- V1 is een belaste reis\n"
          + "- de belasting op basis van afstand van Tom is 60,00 EUR\n"
          + "- de te betalen belasting van Tom is 50,00 EUR\n"
          + "- de gegevens zijn consistent\n";

    // ---- (a) representative scenario/situatie texts parse cleanly ----

    @Test void voorbeeld_1_situatie_uit_het_contract_parseert() {
        assertSchoon("contract-situatie", SITUATIE_UIT_CONTRACT);
    }

    @Test void voorbeeld_2_scenario_uit_het_contract_parseert() {
        // The scenario references the situatie 'Tom met vlucht', so the situatie must precede it in the document.
        assertSchoon("contract-scenario", SITUATIE_UIT_CONTRACT + "\n" + SCENARIO_UIT_CONTRACT);
    }

    @Test void scope_enkele_regel_met_peildatum_en_leeg_verwachting() {
        assertSchoon("enkele-regel",
                "Scenario losse check\n"
              + "toetst de regel te betalen belasting\n"
              + "met peildatum 15-06-2024 09:30:00.000\n"
              + "Met als verwacht resultaat:\n"
              + "- de leeftijd van Tom is leeg\n"
              + "- Tom is geen minderjarige\n"
              + "- Tom heeft geen recht op korting\n");
    }

    @Test void scope_regelgroep_met_boolean_en_kenmerk_verwachtingen() {
        assertSchoon("regelgroep-scope",
                "Scenario groepcheck\n"
              + "toetst de regelgroep Belastingberekening\n"
              + "Met als verwacht resultaat:\n"
              + "- de actief van V1 is waar\n"
              + "- V1 is een belaste reis\n"
              + "- V1 heeft het recht op korting\n"
              + "- de gegevens zijn inconsistent\n");
    }

    @Test void situatieblok_met_to_many_rol_datumtijd_tekst_en_enum() {
        assertSchoon("rijk-situatieblok",
                "Situatie basis:\n"
              + "een Vlucht V1\n"
              + "- de passagiers zijn Tom en Anne en Piet\n"
              + "- de reserveringsdatum is 15-06-2024 09:30:00.000\n"
              + "- heeft een recht op korting\n"
              + "een Natuurlijk persoon Tom\n"
              + "- de naam is \"Tom Jansen\"\n"
              + "- de status is 'actief'\n"
              + "- is meerderjarig\n");
    }

    @Test void scenario_en_situatie_vrij_gemengd_tussen_definities_en_regels() {
        assertSchoon("gemengd",
                "Objecttype de Vlucht (mv: Vluchten)\n"
              + "    de afstand tot bestemming Numeriek (positief geheel getal) met eenheid km;\n"
              + "    is belaste reis kenmerk;\n\n"
              + "Situatie s1:\n"
              + "een Vlucht V1\n"
              + "- de afstand tot bestemming is 200 km\n\n"
              + "Regel afstandsregel\n"
              + "geldig altijd\n"
              + "De afstand tot bestemming van een Vlucht moet berekend worden als 200 km.\n\n"
              + "Scenario check\n"
              + "toetst de regel afstandsregel\n"
              + "gebaseerd op de situatie s1\n"
              + "Met als verwacht resultaat:\n"
              + "- de afstand tot bestemming van V1 is 200 km\n");
    }

    // ---- (c) TOKA-shaped scope list with multi-word rule names ----

    @Test void toets_de_regels_met_multiwoord_regelnamen_in_de_scope_lijst() {
        assertSchoon("multiwoord-scope",
                "Scenario TOKA belasting\n"
              + "toetst de regels Kenmerktoekenning belaste reis, belasting op basis van afstand en te betalen belasting\n"
              + "Met als verwacht resultaat:\n"
              + "- de te betalen belasting van Tom is 50,00 EUR\n");
    }

    // ---- (b) NEGATIVE: a scenario block is rejected under the production document rule ----

    @Test void scenarioblok_parseert_NIET_onder_regelSpraakDocument() {
        String scenario =
                "Scenario Test\n"
              + "toetst de regel belasting\n"
              + "Met als verwacht resultaat:\n"
              + "- de gegevens zijn consistent\n";
        ParseUitkomst prod = parse(scenario, RegelSpraak::regelSpraakDocument);
        assertFalse(prod.schoon(),
                () -> "een scenarioblok MOET onder de productie-startregel regelSpraakDocument falen (isolatie G6)");
        // sanity: the very same block IS clean under the extension start rule
        ParseUitkomst test = parse(scenario, RegelSpraak::testDocument);
        assertTrue(test.schoon(),
                () -> "hetzelfde blok hoort schoon onder testDocument te parseren: " + test.meldingen());
    }
}
