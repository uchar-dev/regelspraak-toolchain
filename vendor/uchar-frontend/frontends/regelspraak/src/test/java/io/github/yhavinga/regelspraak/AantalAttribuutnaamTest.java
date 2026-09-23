package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "het aantal &lt;naam&gt; van &lt;onderwerp&gt;" where "aantal &lt;naam&gt;" is a DECLARED
 * ATTRIBUTE of the subject's type. The source language derives the sentence two ways (§13.4.17.3
 * admits both a getalaggregatie and an attribuutvanonderwerp) and gives no syntactic tie-break; the
 * grammar takes the count, and the count has no instance source (WF-037). The frontend does NOT
 * reinterpret it as the attribute: the attribute reading is written without the article
 * (§13.3.2.6), and that is where it is read. What this pins is that the refusal names the collision
 * and the spelling that reads the attribute, instead of the generic "telling zonder bron" that
 * leaves the author guessing why a declared name is refused.
 */
class AantalAttribuutnaamTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal gewogen posten Numeriek (niet-negatief geheel getal);
            de gewogen vangst Numeriek (getal met 2 decimalen);
            de score Numeriek (niet-negatief geheel getal);
        """;

    private static String regel(String expressie) {
        return """

            Regel score
                geldig altijd
                    De score van een Jutter moet berekend worden als %s.
            """.formatted(expressie);
    }

    private static List<Diagnostic> fouten(RegelSpraakModel m) {
        return m.diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "het aantal gewogen posten van de Jutter",
            "het aantal gewogen posten van een Jutter",
            "(het aantal gewogen posten van de Jutter)"
    })
    void de_botsing_met_een_gedeclareerd_attribuut_wordt_benoemd(String expressie) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regel(expressie));
        List<Diagnostic> ds = fouten(m);
        assertEquals(1, ds.size(), () -> "verwacht één fout voor '" + expressie + "', kreeg: " + m.diagnostics);
        String zin = ds.get(0).message();
        assertTrue(zin.contains("'aantal gewogen posten' is een gedeclareerd attribuut van Jutter"), zin);
        assertTrue(zin.contains("WF-037"), zin);
        // the advice names the spelling that reads the attribute: the same words without "het"
        String bedoeld = expressie.replace("(", "").replace(")", "").substring("het ".length());
        assertTrue(zin.contains("schrijf dan '" + bedoeld + "'"), zin);
    }

    @Test void het_aangeraden_lidwoordloze_spelling_leest_het_attribuut() {
        // the advice must be true: the article-less form resolves clean to the declared attribute
        RegelSpraakModel m = Resolver.resolve(MODEL + regel("aantal gewogen posten van de Jutter"));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        Expr.AttribuutRef a = assertInstanceOf(Expr.AttribuutRef.class, m.resolvedRegels.get(0).waarde());
        assertEquals("aantal gewogen posten", a.attribuut());
    }

    @Test void zonder_aantal_attribuut_blijft_de_algemene_zin() {
        // "gewogen vangst" is declared, "aantal gewogen vangst" is not: no collision to name
        RegelSpraakModel m = Resolver.resolve(MODEL + regel("het aantal gewogen vangst van de Jutter"));
        List<Diagnostic> ds = fouten(m);
        assertEquals(1, ds.size(), () -> "kreeg: " + m.diagnostics);
        assertTrue(ds.get(0).message().startsWith("telling zonder bron: "), ds.get(0).message());
        assertFalse(ds.get(0).message().contains("gedeclareerd attribuut"), ds.get(0).message());
    }
}
