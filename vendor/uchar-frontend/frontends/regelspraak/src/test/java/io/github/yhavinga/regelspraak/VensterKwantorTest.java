package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WF-112 (maintainer decision 2026-09-23), producer side: a time window never sits under 'geen van de' or a
 * counting quantifier — a composite member at any depth, or the filter or test of such a quantifier fold. The
 * window gates the RULE, and no reading is specified for a verdict that is complemented or counted. Measured on
 * the frontend before this fence (78edd45d): the window as a bullet under 'geen van de', 'ten minste één van de',
 * 'ten hoogste één van de' and 'precies één van de', and under a 'geen van de' sub-compound of an 'alle'
 * compound, each reached disk with {@code diagnostics: []}, and the verifier refused every one under WF-112.
 * Under 'alle' the window is admitted and stays clean.
 */
class VensterKwantorTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de peildatum Datum in dagen;
            de premie Numeriek (geheel getal);
            is gemeld kenmerk (bijvoeglijk);

        """;

    private static String onder(String kwantor) {
        return MODEL + """
            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 10
                    indien hij aan %s volgende voorwaarden voldoet:
                    - het is de periode vanaf dd. 01-01-2024
                    - hij is gemeld.
            """.formatted(kwantor);
    }

    private static Diagnostic enigeFout(RegelSpraakModel m) {
        List<Diagnostic> fouten = m.errors();
        assertEquals(1, fouten.size(), () -> "verwachtte precies één fout, kreeg: " + m.diagnostics);
        return fouten.get(0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "geen van de            | geen van de",
            "ten minste één van de  | ten minste 1",
            "ten hoogste één van de | ten hoogste 1",
            "precies één van de     | precies 1"
    })
    void een_venster_onder_geen_of_een_tellende_kwantor_weigert(String kwantor, String woorden) {
        RegelSpraakModel m = Resolver.resolve(onder(kwantor));
        Diagnostic d = enigeFout(m);
        assertTrue(d.message().contains("WF-112") && d.message().contains("'" + woorden + "'"), d.message());
        assertFalse(m.isOk(), "een ERROR houdt het document van de schijf");
    }

    @Test void een_venster_onder_geen_binnen_alle_weigert() {
        String src = MODEL + """
            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 10
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is gemeld
                    - er wordt voldaan aan geen van de volgende voorwaarden:
                        • het is de periode vanaf dd. 01-01-2024
                        • zijn peildatum is gevuld.
            """;
        Diagnostic d = enigeFout(Resolver.resolve(src));
        assertTrue(d.message().contains("WF-112") && d.message().contains("'geen van de'"), d.message());
    }

    @Test void een_venster_onder_alle_blijft_schoon() {
        RegelSpraakModel m = Resolver.resolve(onder("alle"));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }

    @Test void een_venster_als_eigen_voorwaardendeel_blijft_schoon() {
        String src = MODEL + """
            Regel premie
                geldig altijd
                    De premie van een Jutter moet gesteld worden op 10
                    vanaf dd. 01-01-2024.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }
}
