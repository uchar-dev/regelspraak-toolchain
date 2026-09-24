package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.6: a "die/dat" SUBSELECTION the resolution does not consume is refused, not ignored.
 *
 * <p>The sister of {@link SetlezingZonderVouwTest}, and the same shape of argument. A set reading
 * outside a fold source has no one-value meaning, so the set fence refuses it; a SUBSELECTION outside a fold
 * RANGE has no wire form — {@code filter} belongs to the instance fold (IR-SPEC ch. 4.3) and to the
 * quantifier fold (ch. 4.5) and nowhere else — so it is refused here.
 *
 * <p>What makes it an ERROR and not a WARNING: there is no reading of the emitted document under which
 * the omitted subselectie is harmless. "de leeftijd van de Jutter DIE BEVOEGD IS" names WHICH jutter
 * the sentence is about; dropping it answers a different question, verifier-clean, and every executor
 * then agrees on the wrong answer. A warning would leave that document on disk.
 *
 * <p>This makes the stillness loud; it does not build the semantics. What a subselection MEANS on a
 * scalar navigation is a spec question — a named extension point with a loud refusal, the posture the
 * counting quantors held on the quantifier fold until they were built (2026-09-16, WF-108 (a)).
 */
class DeelselectieZonderVouwTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            de leeftijd Numeriek (geheel getal);
            het vindersloon Numeriek (geheel getal);
            is bevoegd kenmerk (bijvoeglijk);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (geheel getal);
            is waardevol kenmerk (bijvoeglijk);

        Feittype vondstaanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten
        """;

    private static List<Diagnostic> weigering(RegelSpraakModel m) {
        return m.diagnostics.stream()
                .filter(d -> d.message().startsWith("de 'die/dat'-deelselectie '")).toList();
    }

    @Test void een_deelselectie_op_een_gewone_lezing_wordt_geweigerd() {
        RegelSpraakModel m = Resolver.resolve(MODEL + """
            Regel vindersloon van een jutter
                geldig altijd
                    Het vindersloon van een Jutter moet gesteld worden op 10
                    indien de leeftijd van de Jutter die bevoegd is groter is dan 5.
            """);
        assertEquals(1, weigering(m).size(), () -> "de bijzin hoort geweigerd te worden: " + m.diagnostics);
        assertEquals(Severity.ERROR, weigering(m).get(0).severity(),
                "een WAARSCHUWING zou het document dóórlaten, en dat document beantwoordt een andere vraag");
        assertTrue(weigering(m).get(0).message().contains("bevoegd is"),
                () -> "de weigering hoort de bijzin te citeren: " + m.diagnostics);
        assertFalse(m.errors().isEmpty(), "een fout blokkeert de emissie — dat is het hele punt");
    }

    @Test void de_vouwbron_houdt_haar_eigen_deelselectie() {
        // The regression pin: the instance fold CONSUMES its subselectie (§5.6, into Functie.filter),
        // so the new refusal must not see it. Without this the fix would refuse every filtered
        // aggregation in the corpus.
        RegelSpraakModel m = Resolver.resolve(MODEL + """
            Regel vindersloon van een jutter
                geldig altijd
                    Het vindersloon van een Jutter moet berekend worden als
                    het aantal vondsten van de Jutter die waardevol zijn.
            """);
        assertTrue(weigering(m).isEmpty(), () -> "een verbruikte bijzin hoort niet geweigerd te worden: " + m.diagnostics);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void de_kwantorvouw_houdt_haar_eigen_deelselectie() {
        // The second consumer, added by the same card: the §8.2 quantifier fold. Same pin, other route.
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Jutter (mv: Jutters) (bezield)
                het maatgevend gewicht Numeriek (geheel getal);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht (mv: gewichten) Numeriek (geheel getal) voor elke maand;
                is waardevol kenmerk (bijvoeglijk);

            Feittype vondstaanmelding
                de aanmelder Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmelder heeft meerdere vondsten

            Regel maatgevend gewicht
                geldig altijd
                    Het maatgevend gewicht van een Jutter moet gesteld worden op 10
                    indien het gewicht van alle vondsten van de Jutter die waardevol zijn
                    gedurende het gehele jaar groter is dan 5.
            """);
        assertTrue(weigering(m).isEmpty(), () -> "een verbruikte bijzin hoort niet geweigerd te worden: " + m.diagnostics);
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
    }

    @Test void een_geweigerde_vouw_deelselectie_wordt_niet_TWEE_keer_gemeld() {
        // The quantifier fold's own refusal (the WARNING that names the axis) already accounts for the
        // bijzin, so this check must stay silent on it — one sentence, one refusal, and the precise one.
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Jutter (mv: Jutters) (bezield)
                het maatgevend gewicht Numeriek (geheel getal);
                is bevoegd kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht (mv: gewichten) Numeriek (geheel getal) voor elke maand;

            Feittype vondstaanmelding
                de aanmelder Jutter
                de vondst (mv: vondsten) Vondst
            Eén aanmelder heeft meerdere vondsten

            Regel maatgevend gewicht
                geldig altijd
                    Het maatgevend gewicht van een Jutter moet gesteld worden op 10
                    indien het gewicht van alle vondsten van de Jutter die bevoegd zijn
                    gedurende het gehele jaar groter is dan 5.
            """);
        assertTrue(weigering(m).isEmpty(),
                () -> "de vouw meldde de bijzin al; een tweede melding zegt hetzelfde met een vagere reden: "
                        + m.diagnostics);
        assertEquals(1, m.diagnostics.stream().filter(d -> d.message().contains("deelselectie")).count(),
                () -> "precies één melding over de bijzin: " + m.diagnostics);
    }
}
