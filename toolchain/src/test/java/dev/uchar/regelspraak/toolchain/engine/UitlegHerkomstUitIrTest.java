package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.runtime.RegelSpraakRuntime;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A decision service that starts from an IR document explains on the model uchar's IR loader read from that document
 * ({@code BeslisserKern.bouwVanDocument}). The document carries uchar's table of origins (IR-SPEC ch. 2.13, the field
 * {@code origin}) and the loader reads it back, so that model itself says that uchar wrote {@code afwijking opgave X}:
 * in a vocabulary the explanation names the attribute its operands read in that vocabulary, as it does on the
 * frontend's model. The document is the one of {@code UitlegGegenereerdeRegelInVocabulaireTest}.
 */
class UitlegHerkomstUitIrTest {

    private static final String MODEL = """
            Vocabulaire cgm (taal: nl)
            Vocabulaire handboek (taal: nl)

            Objecttype de Vondst (mv: Vondsten)
                de vinddag Datum in dagen;
                de meldingsdatum (cgm: Datum melding vondst) (handboek: dag van de melding) Datum in dagen
                    (mag opgegeven worden);
                is zelf gemeld kenmerk (bijvoeglijk);

            Regel melding
                geldig altijd
                    De meldingsdatum van een Vondst moet berekend worden als de vinddag van de Vondst plus 3 dagen.

            Regel afwijking opgave vinddag
                geldig altijd
                    Een Vondst is zelf gemeld
                    indien zijn opgegeven meldingsdatum gevuld is.
            """;

    @Test
    void op_het_model_uit_het_ir_document_noemt_de_uitleg_de_operand_in_het_vocabulaire() throws Exception {
        RegelSpraakEngine engine = RegelSpraakEngine.compile(MODEL);
        RegelSpraakModel uitIr = IrLoader.loadAll(new ObjectMapper().readTree(UcharBouw.document(engine.model())))
                .model();

        Object v1 = engine.nieuw("Vondst");
        RegelSpraakRuntime.set(v1, Namen.ident("vinddag", false), LocalDate.of(2026, 3, 10));
        RegelSpraakRuntime.set(v1, Namen.ident("opgegeven meldingsdatum", false), LocalDate.of(2026, 3, 12));
        Object v2 = engine.nieuw("Vondst");
        RegelSpraakRuntime.set(v2, Namen.ident("vinddag", false), LocalDate.of(2026, 3, 10));
        try (RegelSpraakEngine.Session s = engine.newSession()) {
            s.insert(v1).insert(v2).fire();
            s.zetTerug();
        }
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("V1", v1);
        beans.put("V2", v2);

        List<String> verwacht = List.of(
                "V1 is zelf gemeld, omdat zijn opgegeven meldingsdatum (12-03-2026) gevuld is",
                "V1 heeft afwijkende opgave Datum melding vondst, omdat zijn opgegeven Datum melding vondst"
                        + " (12-03-2026) gevuld is en zijn berekende Datum melding vondst (13-03-2026) gevuld is en"
                        + " zijn opgegeven Datum melding vondst (12-03-2026) is ongelijk aan zijn berekende Datum"
                        + " melding vondst (13-03-2026)",
                "V2 is niet zelf gemeld, want niet geldt: zijn opgegeven meldingsdatum (onbekend) gevuld is",
                "V2 heeft geen afwijkende opgave Datum melding vondst, want niet geldt: zijn opgegeven Datum melding"
                        + " vondst (onbekend) gevuld is");
        assertEquals(verwacht, uitleg(engine.model(), beans), "op het model van de frontend");
        assertEquals(verwacht, uitleg(uitIr, beans), "op het model uit het IR-document");
    }

    private static List<String> uitleg(RegelSpraakModel model, Map<String, Object> beans) {
        Objecttype vondst = model.objecttypeOpNaam("Vondst");
        Map<String, Objecttype> typen = new LinkedHashMap<>();
        for (String label : beans.keySet()) typen.put(label, vondst);
        return new Verbalisator(model, Map.of(), Naamgeving.van(model, "cgm")).verbaliseer(beans, typen);
    }
}
