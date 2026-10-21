package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The explanation renders a condition from the engine's readings (card explanation-from-readings): one sentence per
 * form family of uchar's card fixpoint-readings, on the scenario route. A composite "alle" that stops at its second
 * member names that member (the third is not reached); an "of" block that holds names the members the engine reached;
 * a count with a filter, a product with an empty operand (0), a comparison with an empty operand ("(onbekend)"), a
 * rescaled comparison (700 m against 0,5 km), a duration and a number literal ("30,00 EUR") carry the values and units
 * the engine read; a quantifier fold over a role and a characteristic check over a role carry its verdict. Until this
 * card the explanation computed these itself and said "niet vast te stellen" for most of them; the decision service
 * gives the same sentences on both its routes ({@code ExplanationReadingsServiceTest}).
 */
class ExplanationReadingsTest {

    static final String MODEL = """
            Eenheidsysteem Tijd
                de maand mnd
                het jaar jr = 12 mnd

            Eenheidsysteem Afstand
                de meter m
                de kilometer km = 1000 m

            Eenheidsysteem Gewicht
                het kilogram (mv: kilogrammen) kg

            Eenheidsysteem Valuta
                de euro (mv: euros) EUR

            Objecttype de Jutter (mv: Jutters)
                de leeftijd Numeriek (geheel getal) met eenheid jr;
                het aantal meldingen Numeriek (geheel getal);
                de afstand tot de kust Numeriek (geheel getal) met eenheid m;
                de geboortedatum Datum in dagen;
                de peildatum Datum in dagen;
                de premie Numeriek (getal met 2 decimalen) met eenheid EUR;
                de bonus Numeriek (getal met 2 decimalen) met eenheid EUR;
                is ingezetene kenmerk (bijvoeglijk);
                is in dienst kenmerk (bijvoeglijk);
                is gerechtigd kenmerk (bijvoeglijk);
                is bijzonder kenmerk (bijvoeglijk);
                is actief kenmerk (bijvoeglijk);
                is gelukkig kenmerk (bijvoeglijk);
                is meldplichtig kenmerk (bijvoeglijk);
                is bonuswaardig kenmerk (bijvoeglijk);
                is kustjutter kenmerk (bijvoeglijk);
                is minderjarig kenmerk (bijvoeglijk);
                is premiewaardig kenmerk (bijvoeglijk);

            Objecttype de Vondst (mv: Vondsten)
                het gewicht Numeriek (geheel getal) met eenheid kg;
                is waardevol kenmerk (bijvoeglijk);
                is aangemeld kenmerk (bijvoeglijk);

            Feittype vondst van een jutter
                de jutter Jutter
                de vondst (mv: vondsten) Vondst
            Eén jutter heeft meerdere vondsten

            Regel gerechtigd
                geldig altijd
                    Een Jutter is gerechtigd
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is ingezetene
                    - hij is in dienst
                    - zijn leeftijd is groter dan 17 jr.

            Regel bijzonder
                geldig altijd
                    Een Jutter is bijzonder
                    indien hij aan ten minste één van de volgende voorwaarden voldoet:
                    - hij is in dienst
                    - zijn aantal meldingen is groter dan 10.

            Regel actief
                geldig altijd
                    Een Jutter is actief
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is ingezetene
                    - het aantal vondsten van de Jutter die aan de volgende voorwaarde voldoen: hij is waardevol is groter dan 0.

            Regel gelukkig
                geldig altijd
                    Een Jutter is gelukkig
                    indien alle vondsten van de Jutter waardevol zijn.

            Regel meldplichtig
                geldig altijd
                    Een Jutter is meldplichtig
                    indien zijn aantal meldingen groter is dan 3.

            Regel bonuswaardig
                geldig altijd
                    Een Jutter is bonuswaardig
                    indien zijn bonus maal 2 groter is dan 10,00 EUR.

            Regel kustjutter
                geldig altijd
                    Een Jutter is kustjutter
                    indien zijn afstand tot de kust kleiner is dan 0,5 km.

            Regel minderjarig
                geldig altijd
                    Een Jutter is minderjarig
                    indien de tijdsduur van zijn geboortedatum tot zijn peildatum in hele jaren kleiner is dan 18 jr.

            Regel aangemeld
                geldig altijd
                    Een Vondst is aangemeld
                    indien de jutter van de Vondst ingezetene is.

            Regel premiewaardig
                geldig altijd
                    Een Jutter is premiewaardig
                    indien zijn premie groter is dan 30,00 EUR.
            """;

    private static final String SITUATION = """

            Situatie basis:
            een Jutter J1
            - de leeftijd is 30 jr
            - de afstand tot de kust is 700 m
            - de geboortedatum is 01-01-1996
            - de peildatum is 01-06-2026
            - de premie is 60,00 EUR
            - is ingezetene
            een Jutter J2
            - de leeftijd is 16 jr
            - het aantal meldingen is 12
            - de afstand tot de kust is 300 m
            - de geboortedatum is 01-01-2010
            - de peildatum is 01-06-2026
            - de premie is 20,00 EUR
            - de bonus is 7,50 EUR
            - is in dienst
            een Vondst W1
            - het gewicht is 3 kg
            - is waardevol
            - de jutter is J1
            een Vondst W2
            - het gewicht is 5 kg
            - de jutter is J1
            een Vondst W3
            - het gewicht is 2 kg
            - is waardevol
            - de jutter is J2
            Scenario twee jutters
            met peildatum 01-06-2026
            gebaseerd op de situatie basis
            Met als verwacht resultaat:
            - J1 is niet gerechtigd
            - J2 is bijzonder
            """;

    static final List<String> EXPECTED = List.of(
            "J1 is niet gerechtigd, want niet geldt: het onderwerp in dienst is",
            "J1 is niet bijzonder, want niet geldt: het onderwerp in dienst is of zijn aantal meldingen (onbekend) is groter dan 10",
            "J1 is actief, omdat het onderwerp ingezetene is en het aantal vondsten van de Jutter die aan de volgende voorwaarde voldoen: hij is waardevol (1) is groter dan 0",
            "J1 is niet gelukkig, want aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is niet voldaan: niet voor W2 (1 van de 2)",
            "J1 is niet meldplichtig, want niet geldt: zijn aantal meldingen (onbekend) is groter dan 3",
            "J1 is niet bonuswaardig, want niet geldt: zijn bonus maal 2 (0,00 EUR) is groter dan 10,00 EUR",
            "J1 is niet kustjutter, want niet geldt: zijn afstand tot de kust (700 m) is kleiner dan 0,5 km",
            "J1 is niet minderjarig, want niet geldt: de tijdsduur van zijn geboortedatum tot zijn peildatum in hele jaren (30 jr) is kleiner dan 18 jr",
            "J1 is premiewaardig, omdat zijn premie (60,00 EUR) is groter dan 30,00 EUR",
            "J2 is niet gerechtigd, want niet geldt: het onderwerp ingezetene is",
            "J2 is bijzonder, omdat het onderwerp in dienst is",
            "J2 is niet actief, want niet geldt: het onderwerp ingezetene is",
            "J2 is gelukkig, omdat aan de voorwaarde \"alle vondsten van de Jutter waardevol zijn\" is voldaan: voor W3, het enige lid",
            "J2 is meldplichtig, omdat zijn aantal meldingen (12) is groter dan 3",
            "J2 is bonuswaardig, omdat zijn bonus maal 2 (15,00 EUR) is groter dan 10,00 EUR",
            "J2 is kustjutter, omdat zijn afstand tot de kust (300 m) is kleiner dan 0,5 km",
            "J2 is minderjarig, omdat de tijdsduur van zijn geboortedatum tot zijn peildatum in hele jaren (16 jr) is kleiner dan 18 jr",
            "J2 is niet premiewaardig, want niet geldt: zijn premie (20,00 EUR) is groter dan 30,00 EUR",
            "W1 is aangemeld, omdat de jutter ingezetene is",
            "W2 is aangemeld, omdat de jutter ingezetene is",
            "W3 is niet aangemeld, want niet geldt: de jutter ingezetene is");

    @Test
    void every_form_family_is_explained_from_the_readings_of_the_engine() {
        List<ScenarioRunner.Explained> u = ScenarioRunner.runWithExplanation(ScenarioResolver.resolve(MODEL + SITUATION));
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).result().status(), () -> u.get(0).result().diagnostic());
        assertNull(u.get(0).explanationError(), u.get(0)::explanationError);
        assertEquals(EXPECTED, u.get(0).explanation());
    }
}
