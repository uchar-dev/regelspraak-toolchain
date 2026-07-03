package io.github.yhavinga.regelspraak.transpiler.kern;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SHARED §9.8 dagsoortdefinitie compiler (goal-M1): both back-ends get their per-date predicate from
 * here — the exec model captures it in lambdas, the DRL back-end supplies it as a {@code rsDagsoort_<Naam>}
 * session global. These pins are the compiler's own unit contract (ABSOLUTE values, not back-end parity).
 */
class DagsoortenTest {

    private static final String KERSTDAG = """
        Dagsoort de kerstdag (mv: kerstdagen)
        Regel Kerstdag
            geldig altijd
                Een dag is een kerstdag
                indien de dag aan alle volgende voorwaarden voldoet:
                - de maand uit (de dag) is gelijk aan 12
                - de dag voldoet aan ten minste één van de volgende voorwaarden:
                    .. de dag uit (de dag) is gelijk aan 25
                    .. de dag uit (de dag) is gelijk aan 26.
        """;

    @Test void kerstdag_predikaat_kent_alleen_25_en_26_december() {
        RegelSpraakModel m = Resolver.resolve(KERSTDAG);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        Predicate<LocalDate> p = new Dagsoorten(m).predikaat("kerstdag");
        assertNotNull(p, "de definitie is een puur datum-predicaat en moet compileren");
        assertTrue(p.test(LocalDate.of(2024, 12, 25)));
        assertTrue(p.test(LocalDate.of(2024, 12, 26)));
        assertFalse(p.test(LocalDate.of(2024, 12, 24)));
        assertFalse(p.test(LocalDate.of(2024, 11, 25)), "juiste dag, foute maand");
    }

    @Test void cross_dagsoort_referentie_composeert_en_paasdag_computus_rekent() {
        RegelSpraakModel m = Resolver.resolve(KERSTDAG + """
            Dagsoort de paasdag (mv: paasdagen)
            Dagsoort de feestdag (mv: feestdagen)
            Regel Paasdag
                geldig altijd
                    Een dag is een paasdag
                    indien de dag gelijk is aan de eerste paasdag van het jaar uit (de dag).
            Regel Feestdag
                geldig altijd
                    Een dag is een feestdag
                    indien de dag aan ten minste één van de volgende voorwaarden voldoet:
                    - de dag een kerstdag is
                    - de dag een paasdag is.
            """);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        Predicate<LocalDate> feest = new Dagsoorten(m).predikaat("feestdag");
        assertNotNull(feest, "een cross-dagsoort definitie moet compileren (deferred lookup)");
        assertTrue(feest.test(LocalDate.of(2024, 12, 25)), "kerstdag is een feestdag");
        assertTrue(feest.test(LocalDate.of(2024, 3, 31)), "eerste paasdag 2024 is een feestdag");
        assertFalse(feest.test(LocalDate.of(2024, 6, 15)));
    }

    @Test void onbekende_dagsoort_en_niet_puur_datumpredicaat_geven_null() {
        RegelSpraakModel m = Resolver.resolve(KERSTDAG);
        Dagsoorten d = new Dagsoorten(m);
        assertNull(d.predikaat("werkdag"), "geen definitie -> null (de aanroepende back-end markert)");
    }

    @Test void literalen_lezen_datum_en_rationaal_getal() {
        assertEquals(LocalDate.of(2024, 12, 25), Literalen.parseDatum("dd. 25-12-2024"));
        assertNull(Literalen.parseDatum("geen datum"));
        assertEquals(java.time.LocalDateTime.of(2024, 12, 25, 10, 30, 0, 500_000_000),
                Literalen.parseDatumTijd("dd. 25-12-2024 10:30:00.500"),
                "de millisecondes schalen x1e6 naar nanoOfSecond");
        Expr.Literal derde = new Expr.Literal("1/3", new Datatype.Numeriek(false, 0, null, null), "1/3");
        assertEquals(new BigDecimal("0.3333333333333333"), Literalen.getal(derde),
                "een niet-eindigende rationaal leest op DECIMAL64 (16 significante cijfers)");
        Expr.Literal half = new Expr.Literal("1/2", new Datatype.Numeriek(false, 0, null, null), "1/2");
        assertEquals(new BigDecimal("0.5"), Literalen.getal(half), "een eindigend quotiënt leest EXACT");
    }
}
