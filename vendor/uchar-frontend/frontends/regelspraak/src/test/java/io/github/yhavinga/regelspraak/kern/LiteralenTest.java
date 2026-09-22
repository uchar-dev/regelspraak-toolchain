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

    /* Until 2026-09-23 these pins read the day-first source snippet through this reader ("dd. 25-12-2024",
     * "dd. 8-9-2024", "dd. 25-12-2024 10:30:00.500") and held the snippet and the ISO lexeme to the same
     * moment. The reader is the WIRE reader the back-ends share: the emitter writes ISO into waarde (WF-084,
     * WF-095 since card p5-datumtijd-lexeem), and the frontend reads its own source snippet with its own
     * reader in ExpressieBouwer, which the emitter tests pin. So the snippet pins turned into refusals
     * (cards datumtijd-lezers-versmalling, datum-lexeem-hele-match). */
    @Test void datums_en_datumtijden_parsen_het_iso_lexeem_met_milli_naar_nano_schaal() {
        assertEquals(LocalDate.of(2024, 12, 25), Literalen.parseDatum("2024-12-25"));
        assertEquals(LocalDate.of(2024, 9, 8), Literalen.parseDatum("2024-09-08"));
        assertNull(Literalen.parseDatum("geen datum"));
        assertEquals(LocalDateTime.of(2024, 12, 25, 10, 30, 0, 500_000_000),
                Literalen.parseDatumTijd("2024-12-25T10:30:00.500"), "IR-SPEC ch. 4.1, met fractie");
        assertEquals(LocalDateTime.of(2024, 12, 25, 10, 30, 0),
                Literalen.parseDatumTijd("2024-12-25T10:30:00"), "fractie is optioneel");
        assertNull(Literalen.parseDatumTijd("2024-12-25"), "zonder tijddeel geen datumtijd");
    }

    @Test void het_bronsnippet_is_geen_wire_lexeem() {
        assertNull(Literalen.parseDatum("dd. 25-12-2024"), "dag-eerst met dd.-voorvoegsel");
        assertNull(Literalen.parseDatum("25-12-2024"), "dag-eerst zonder voorvoegsel (WF-084 weigert het)");
        assertNull(Literalen.parseDatum("dd. 8-9-2024"), "enkelcijferige dag/maand");
        assertNull(Literalen.parseDatumTijd("dd. 25-12-2024 10:30:00.500"), "datumtijd-snippet met voorvoegsel");
        assertNull(Literalen.parseDatumTijd("25-12-2024 10:30:00.500"), "datumtijd-snippet zonder voorvoegsel");
        assertNull(Literalen.parseDatum("dd. 2024-12-25"), "het dd.-voorvoegsel voor een ISO-datum is geen lexeem");
        assertNull(Literalen.parseDatumTijd("dd. 2024-12-25T10:30:00.500"), "idem voor een ISO-datumtijd");
    }

    @Test void datum_matcht_het_hele_lexeem() {
        assertNull(Literalen.parseDatum("2024-01-2026"), "werd met find() stil 2024-01-20");
        assertNull(Literalen.parseDatum("x 2024-01-31 y"), "het lexeem is de hele waarde");
        assertNull(Literalen.parseDatum("2024-1-31"), "vaste veldbreedtes");
        assertNull(Literalen.parseDatum("2024-02-30"), "de kalender beslist");
    }

    @Test void datumtijd_weigert_wat_buiten_het_lexeem_valt() {
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

    @Test void tekstOfEnumLabel_leest_tekst_verbatim() {
        // IR-SPEC ch. 4.1: waarde IS the value. Ref reads it verbatim and a case input carries it verbatim, so
        // whatever this reader removes is compared against a string no other executor ever sees.
        assertEquals("Flevoland", Literalen.tekstOfEnumLabel(tekst("Flevoland")), "een rauw lexeem is zijn eigen waarde");
        assertEquals("\"Flevoland\"", Literalen.tekstOfEnumLabel(tekst("\"Flevoland\"")),
                "buitenste aanhalingstekens horen bij de waarde — Tekst heeft geen delimiters om te strippen");
        assertEquals("merk \"AA\"", Literalen.tekstOfEnumLabel(tekst("merk \"AA\"")),
                "een ingebed aanhalingsteken overleeft (de keurmerk-golden)");
    }

    @Test void tekstOfEnumLabel_normaliseert_niet_stilletjes() {
        // The mutation witness of the pair above: a reader that strips quotes folds these two DIFFERENT wire values
        // onto one string, and an equality then fires on a document that does not license it.
        assertNotEquals(Literalen.tekstOfEnumLabel(tekst("Flevoland")),
                Literalen.tekstOfEnumLabel(tekst("\"Flevoland\"")),
                "rauw en quote-dragend zijn twee waarden, niet een");
    }

    @Test void tekstOfEnumLabel_stript_alleen_de_enumdelimiters() {
        assertEquals("eerste klas", Literalen.tekstOfEnumLabel(num("'eerste klas'")), "een enum rijdt op elk niet-Tekst type");
        assertEquals("zg. \"digitale\" kaart", Literalen.tekstOfEnumLabel(num("'zg. \"digitale\" kaart'")),
                "ook hier blijven ingebedde tekens staan");
        assertNull(Literalen.tekstOfEnumLabel(num("42")));
    }

    @Test void tekstOfEnumLabel_leest_een_enumeratiegetypeerde_literal_verbatim() {
        // WF-039 kruist deze waarde byte-exact tegen het gedeclareerde domein, dus de wire-vorm IS het kale
        // label — de delimiters zijn er al af als de emitter klaar is. De enum-arm hierboven door enumLabel
        // laten lopen gaf op elk conform document null, en dat is hoe beide Drools-takken ertoe kwamen elke
        // enumgelijkheid en elke enumtoekenning te weigeren (kaart drools-enumlabel-lezer, 2026-09-20).
        assertEquals("wrakgoed", Literalen.tekstOfEnumLabel(enumLit("wrakgoed", "drijfgoed", "wrakgoed")));
        assertEquals("licht blauw", Literalen.tekstOfEnumLabel(enumLit("licht blauw", "licht blauw")),
                "een label met een spatie is één label");
        // En verbatim betekent verbatim: een gedelimiteerde waarde op een Enumeratie-type is ill-formed op de
        // wire (WF-039 weigert haar), dus deze lezer repareert haar niet alsnog stilletjes tot een label dat
        // de verifier nooit gezien heeft.
        assertEquals("'wrakgoed'", Literalen.tekstOfEnumLabel(enumLit("'wrakgoed'", "wrakgoed")),
                "de lezer verzint geen label bij een waarde die de verifier al afkeurt");
    }

    private static Expr.Literal enumLit(String w, String... waarden) {
        return new Expr.Literal(w, new Datatype.Enumeratie("Vondstklasse", java.util.List.of(waarden)),
                Bron.synthetisch("'" + w + "'"));
    }

    @Test void waar_leest_de_boolean_literalen() {
        assertTrue(Literalen.waar("waar"));
        assertFalse(Literalen.waar("onwaar"));
    }
}
