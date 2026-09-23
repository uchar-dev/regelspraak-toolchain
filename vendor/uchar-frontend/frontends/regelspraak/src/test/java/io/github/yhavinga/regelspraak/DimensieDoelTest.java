package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rule that assigns one CELL of a dimensioned attribute. The wire has no cell assignment: a rule's target is an
 * attribute reference (WF-022 refuses any other node there), and the dimension-cell node is a read. Measured on
 * the frontend before this fix (d4a25506): "De premie van een Jutter in voorjaar moet gesteld worden op 10" put the
 * label into the subject text and wrote the WHOLE attribute premie — verifier PASS on a different document than
 * the one written; "De premie in voorjaar van een Jutter …" was refused, but as an undeclared attribute, without
 * naming the dimension. Both are refused now with the cell named. Reading a cell stays what it was.
 */
class DimensieDoelTest {

    static final String MODEL = """
        Dimensie het seizoen, bestaande uit de seizoenen (na het attribuut met voorzetsel in):
            1. voorjaar
            2. najaar

        Objecttype de Jutter (mv: Jutters) (bezield)
            de premie Numeriek (geheel getal) gedimensioneerd met seizoen;
            de bonus Numeriek (geheel getal);

        """;

    private static String regel(String resultaat) {
        return MODEL + """
            Regel doel
                geldig altijd
                    %s
            """.formatted(resultaat);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "De premie van een Jutter in voorjaar moet gesteld worden op 10.",
            "De premie in voorjaar van een Jutter moet gesteld worden op 10."
    })
    void een_celtoekenning_weigert_met_de_dimensie(String resultaat) {
        RegelSpraakModel m = Resolver.resolve(regel(resultaat));
        List<Diagnostic> fouten = m.errors();
        assertEquals(1, fouten.size(), () -> "verwachtte precies één fout, kreeg: " + m.diagnostics);
        String t = fouten.get(0).message();
        assertTrue(t.contains("cel van het gedimensioneerde attribuut 'premie' (dimensie seizoen, label 'voorjaar')"), t);
        assertTrue(t.contains("WF-022"), t);
    }

    @Test void een_cel_lezen_blijft_schoon() {
        RegelSpraakModel m = Resolver.resolve(regel(
                "De bonus van een Jutter moet gesteld worden op zijn premie in voorjaar."));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }
}
