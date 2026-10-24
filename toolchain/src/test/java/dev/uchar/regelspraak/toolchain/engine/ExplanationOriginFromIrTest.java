package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.core.Namen;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A decision service that starts from an IR document explains on the model uchar's IR loader read from that document
 * ({@code DecisionCore.buildFromDocument}). The document carries uchar's table of origins (IR-SPEC ch. 2.13, the field
 * {@code origin}) and the loader reads it back, so that model itself says that uchar wrote {@code afwijking opgave X}:
 * in a vocabulary the explanation names the attribute its operands read in that vocabulary, as it does on the
 * frontend's model. The document is the one of {@code ExplanationGeneratedRuleInVocabularyTest}.
 */
class ExplanationOriginFromIrTest {

    private static final String MODEL = """
            Vocabulaire cgm (taal: nl)
            Vocabulaire handboek (taal: nl)

            Objecttype de Vondst (mv: Vondsten)
                de vinddag Datum in dagen;
                de meldingsdatum (cgm: Datum melding vondst) (handboek: dag van de melding) Datum in dagen
                    (mag opgegeven worden);
                is eigenhandig gemeld kenmerk (bijvoeglijk);

            Regel melding
                geldig altijd
                    De meldingsdatum van een Vondst moet berekend worden als de vinddag van de Vondst plus 3 dagen.

            Regel afwijking opgave vinddag
                geldig altijd
                    Een Vondst is eigenhandig gemeld
                    indien zijn opgegeven meldingsdatum gevuld is.
            """;

    @Test
    void on_the_model_from_the_ir_document_names_the_uitleg_the_operand_in_the_vocabulary() throws Exception {
        ExplanationSession engine = ExplanationSession.of(MODEL);

        Object v1 = engine.newInstance("Vondst");
        RegelSpraakRuntime.set(v1, Namen.ident("vinddag", false), LocalDate.of(2026, 3, 10));
        RegelSpraakRuntime.set(v1, Namen.ident("opgegeven meldingsdatum", false), LocalDate.of(2026, 3, 12));
        Object v2 = engine.newInstance("Vondst");
        RegelSpraakRuntime.set(v2, Namen.ident("vinddag", false), LocalDate.of(2026, 3, 10));
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("V1", v1);
        beans.put("V2", v2);

        List<String> expected = List.of(
                "V1 is eigenhandig gemeld, omdat zijn opgegeven meldingsdatum (12-03-2026) gevuld is",
                "V1 heeft afwijkende opgave Datum melding vondst, omdat zijn opgegeven Datum melding vondst"
                        + " (12-03-2026) gevuld is en zijn berekende Datum melding vondst (13-03-2026) gevuld is en"
                        + " zijn opgegeven Datum melding vondst (12-03-2026) is ongelijk aan zijn berekende Datum"
                        + " melding vondst (13-03-2026)",
                "V2 is niet eigenhandig gemeld, want niet geldt: zijn opgegeven meldingsdatum (onbekend) gevuld is",
                "V2 heeft geen afwijkende opgave Datum melding vondst, want niet geldt: zijn opgegeven Datum melding"
                        + " vondst (onbekend) gevuld is");
        // since card explanation-from-readings every route explains on the model uchar's loader reads from the
        // document, with the readings of the engine; the frontend's model is no longer a route of the explanation
        assertEquals(expected, engine.explain("cgm", "Vondst", beans), "op het model uit het IR-document");
    }
}
