package io.github.yhavinga.regelspraak.kern;

import io.github.yhavinga.regelspraak.ir.Bron;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.Datatype;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** The single literal reader both back-ends share (single shared implementation) — what a literal MEANS lives here once. */
class LiteralenTest {

    private static Expr.Literal num(String w) { return new Expr.Literal(w, new Datatype.Numeriek(false, 0, null, null), Bron.synthetisch(w)); }
    private static Expr.Literal tekst(String w) { return new Expr.Literal(w, new Datatype.Tekst(), Bron.synthetisch(w)); }

    @Test void datums_en_datumtijden_parsen_met_milli_naar_nano_schaal() {
        assertEquals(LocalDate.of(2024, 12, 25), Literalen.parseDatum("dd. 25-12-2024"));
        assertEquals(LocalDate.of(2024, 9, 8), Literalen.parseDatum("dd. 8-9-2024"), "enkelcijferige dag/maand");
        assertNull(Literalen.parseDatum("geen datum"));
        assertEquals(LocalDateTime.of(2024, 12, 25, 10, 30, 0, 500_000_000),
                Literalen.parseDatumTijd("dd. 25-12-2024 10:30:00.500"));
        assertNull(Literalen.parseDatumTijd("dd. 25-12-2024"), "zonder tijddeel geen datumtijd");
    }

    @Test void datumtijd_leest_het_canonieke_iso_lexeem_gelijk_aan_het_snippet() {
        assertEquals(LocalDateTime.of(2024, 12, 25, 10, 30, 0, 500_000_000),
                Literalen.parseDatumTijd("2024-12-25T10:30:00.500"), "IR-SPEC ch. 4.1, met fractie");
        assertEquals(LocalDateTime.of(2024, 12, 25, 10, 30, 0),
                Literalen.parseDatumTijd("2024-12-25T10:30:00"), "fractie is optioneel");
        assertEquals(Literalen.parseDatumTijd("dd. 25-12-2024 10:30:00.500"),
                Literalen.parseDatumTijd("2024-12-25T10:30:00.500"), "beide lexemen benoemen hetzelfde moment");
    }

    @Test void datumtijd_weigert_wat_buiten_beide_lexemen_valt() {
        assertNull(Literalen.parseDatumTijd("2024-12-25T10:30"), "seconden verplicht");
        assertNull(Literalen.parseDatumTijd("2024-12-25T10:30:00Z"), "geen zone-suffix");
        assertNull(Literalen.parseDatumTijd("2024-12-25T10:30:00+01:00"), "geen offset");
        assertNull(Literalen.parseDatumTijd("2024-12-25 10:30:00"), "T, geen spatie, in het canonieke lexeem");
        assertNull(Literalen.parseDatumTijd("2024-1-25T9:30:00"), "canoniek heeft vaste veldbreedtes");
        assertNull(Literalen.parseDatumTijd("2024-12-25T10:30:00.1"), "fractie exact drie cijfers");
        assertNull(Literalen.parseDatumTijd("2024-12-25T10:30:00.1234"), "nooit stil afkappen");
        assertNull(Literalen.parseDatumTijd("x 2024-12-25T10:30:00 y"), "het lexeem is de hele waarde");
    }

    @Test void getal_leest_rationalen_exact_of_op_DECIMAL64() {
        assertEquals(new BigDecimal("0.5"), Literalen.getal(num("1/2")), "eindigend quotient EXACT");
        assertEquals(new BigDecimal("0.3333333333333333"), Literalen.getal(num("1/3")), "niet-eindigend op DECIMAL64");
        assertEquals(0, new BigDecimal("42").compareTo(Literalen.getal(num("42"))));
        assertNull(Literalen.getal(num("geen getal")));
    }

    @Test void enumLabel_stript_alleen_de_delimiters_en_houdt_ingebedde_tekens() {
        assertEquals("eerste klas", Literalen.enumLabel("'eerste klas'"));
        assertEquals("typografisch", Literalen.enumLabel("’typografisch‘"));
        // escaping by construction: an embedded double quote is PART of the label — kept, never stripped
        assertEquals("zg. \"digitale\" kaart", Literalen.enumLabel("'zg. \"digitale\" kaart'"));
        assertNull(Literalen.enumLabel("geen enum"));
    }

    @Test void tekstOfEnumLabel_dekt_tekst_en_enumeratiewaarde() {
        assertEquals("Amsterdam", Literalen.tekstOfEnumLabel(tekst("\"Amsterdam\"")));
        assertEquals("eerste klas", Literalen.tekstOfEnumLabel(num("'eerste klas'")), "een enum rijdt op elk niet-Tekst type");
        assertNull(Literalen.tekstOfEnumLabel(num("42")));
    }

    @Test void waar_leest_de_boolean_literalen() {
        assertTrue(Literalen.waar("waar"));
        assertFalse(Literalen.waar("onwaar"));
    }
}
