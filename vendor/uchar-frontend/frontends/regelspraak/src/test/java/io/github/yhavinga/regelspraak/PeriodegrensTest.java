package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WF-111 (maintainer decision 2026-09-23), producer side: a time window's bound is a date literal or an own
 * scalar date attribute of the subject, both of day precision. §13.4.15.68 lets a periodevergelijking take any
 * date expression, and the frontend built every one of them into a window — measured on the frontend before
 * this fence (1c71ecf3): a parameter, a navigated read, a datum-tijd attribute, a computed date, a
 * time-dependent attribute, a datum-tijd literal and an attribute of another object type all reached disk with
 * {@code diagnostics: []}, and the verifier refused each one (WF-111, and WF-053 for the supertype-named read).
 *
 * <p><b>Own means: written on the subject.</b> This frontend writes an own read — an inherited attribute
 * included — with the SUBJECT as its object type ("zijn peildatum" in a Klipjutter rule is an
 * {@code AttribuutRef} on Klipjutter). A read naming any other type, a supertype of the subject included, goes
 * out as a navigation (by-pair bindings) or as an off-subject read without one, and the verifier refuses both.
 * So the fence compares object types by identity, as the Drools gate {@code Tijdrekening.periodeGrensLowerbaar}
 * does, and for this frontend's output that gives exactly the verifier's verdict.
 */
class PeriodegrensTest {

    static final String MODEL = """
        Parameter de peilparameter : Datum in dagen

        Objecttype de Jutter (mv: Jutters) (bezield)
            de peildatum Datum in dagen;
            de knipdatum Datum in dagen voor elke maand;
            het meldmoment Datum en tijd in millisecondes;
            de premie Numeriek (geheel getal);

        Objecttype de Klipjutter is een Jutter (mv: Klipjutters) (bezield)
            de klipdatum Datum in dagen;

        Objecttype het Strandvak (mv: Strandvakken)
            de opendatum Datum in dagen;

        Feittype vaktoewijzing
            de vakhouder Jutter
            het strandvak Strandvak
        Eén vakhouder heeft één strandvak
        """;

    private static String regel(String subject, String venster) {
        return """

            Regel premie
                geldig altijd
                    De premie van een %s moet gesteld worden op 10
                    %s.
            """.formatted(subject, venster);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Jutter     | vanaf de peilparameter                   | de parameter 'peilparameter'",
            "Jutter     | vanaf de opendatum van het strandvak van de Jutter | een genavigeerde of verzamelende lezing van 'opendatum'",
            "Jutter     | vanaf de opendatum van zijn strandvak    | een ander objecttype dan het onderwerp 'Jutter'",
            "Jutter     | vanaf zijn meldmoment                    | het datum-tijd-attribuut 'meldmoment'",
            "Jutter     | vanaf zijn peildatum plus 1 dg           | een berekende datumexpressie",
            "Jutter     | vanaf zijn knipdatum                     | het tijdsafhankelijke attribuut 'knipdatum'",
            "Jutter     | vanaf dd. 1-7-2026 08:30:00.000          | een datum-tijd",
            "Jutter     | van dd. 01-01-2024 tot de peilparameter  | de tot-grens",
            "Jutter     | vanaf de klipdatum van de Klipjutter     | een subtype van het onderwerp 'Jutter'",
            "Klipjutter | vanaf de peildatum van de Jutter         | een supertype van het onderwerp 'Klipjutter'"
    })
    void een_grens_buiten_de_twee_vormen_weigert(String subject, String venster, String fragment) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regel(subject, venster));
        List<Diagnostic> fouten = m.errors();
        assertEquals(1, fouten.size(), () -> "verwachtte precies één fout voor '" + venster + "', kreeg: " + m.diagnostics);
        String t = fouten.get(0).message();
        assertTrue(t.contains("WF-111") && t.contains("§8.4.3"), t);
        assertTrue(t.contains(fragment), () -> "de melding noemt '" + fragment + "' niet: " + t);
        // an ERROR keeps the document off disk: Resolver.irModus writes nothing
        assertFalse(m.isOk());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Jutter     | vanaf dd. 01-01-2024",
            "Jutter     | vanaf zijn peildatum",
            "Jutter     | tot en met zijn peildatum",
            "Jutter     | van dd. 01-01-2024 tot zijn peildatum",
            "Klipjutter | vanaf zijn peildatum",
            "Klipjutter | vanaf de peildatum van de Klipjutter",
            "Klipjutter | vanaf zijn klipdatum"
    })
    void de_twee_vormen_blijven_schoon(String subject, String venster) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regel(subject, venster));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek voor '" + venster + "': " + m.diagnostics);
    }
}
