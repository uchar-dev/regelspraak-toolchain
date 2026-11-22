package dev.uchar.regelspraak.toolchain.service;

import java.util.Map;

/**
 * Test fixture: a rule set of the Jutterswet that infers nothing from an empty value ({@code Afleiden uit leeg: nee}), with
 * a rule, a characteristic, a defaults group, a fold over a set, an attribute with an absence marker, a consistency, and
 * two members whose input is complete. The entry point {@code vracht} reads a parameter nothing binds unless the request
 * does.
 */
public final class OpenValuesSource {

    private OpenValuesSource() {}

    public static final String SOURCE = """
        Afleiden uit leeg: nee

        Parameter het TARIEF : Numeriek (geheel getal)

        Objecttype de Jutter (mv: Jutters) (bezield)
            de totaaltaxatie Numeriek (geheel getal);

        Objecttype de Vondst (mv: Vondsten)
            de taxatie Numeriek (geheel getal);
            de toeslag Numeriek (geheel getal) (volledig gegeven);
            de herkomst Numeriek (geheel getal) (of onbekend);
            het vindloon Numeriek (geheel getal);
            de uitbetaling Numeriek (geheel getal);
            de bewakingstoeslag Numeriek (geheel getal);
            het bergloon Numeriek (geheel getal);
            de premie Numeriek (geheel getal);
            de vrachtprijs Numeriek (geheel getal);
            is kostbaar kenmerk (bijvoeglijk);
            is betwist kenmerk (bijvoeglijk);
            is onleesbaar kenmerk (bijvoeglijk) (volledig gegeven);

        Feittype vangst
            de jutter Jutter
            de vondst (mv: vondsten) Vondst
            één jutter heeft meerdere vondsten

        Regel kostbaarheid
            geldig altijd
                Een Vondst is kostbaar indien zijn taxatie groter is dan 1000.

        Regel vindloon
            geldig altijd
                Het vindloon van een Vondst moet berekend worden als zijn taxatie plus 10.

        Regel uitbetaling
            geldig altijd
                De uitbetaling van een Vondst moet berekend worden als zijn taxatie plus zijn toeslag.

        Regel bewakingstoeslag
            geldig altijd
                De bewakingstoeslag van een Vondst moet gesteld worden op 5 indien hij niet kostbaar is.

        Regel bergloon
            geldig altijd
                Het bergloon van een Vondst moet gesteld worden op 20 indien zijn taxatie groter is dan 1000.

        Regel herkomst onbekend
            geldig altijd
                De herkomst van een Vondst moet gesteld worden op onbekend indien hij onleesbaar is.

        Regel premie
            geldig altijd
                De premie van een Vondst moet gesteld worden op 10.

        Afwijking van premie: betwiste premie
            geldig altijd
                De premie van een Vondst moet gesteld worden op 0 indien hij betwist is.

        Regel totaaltaxatie
            geldig altijd
                De totaaltaxatie van een Jutter moet berekend worden als de som van de taxatie van alle vondsten van de Jutter.

        Regel taxatiegrens
            geldig altijd
                De taxatie van een Vondst moet kleiner zijn dan 5000.

        Regel vrachtprijs
            geldig altijd
                De vrachtprijs van een Vondst moet berekend worden als zijn taxatie maal het TARIEF.
        """;

    public static final String DESCRIPTOR = """
        { "service": {"name":"open-waarden","version":"1"},
          "entrypoints": [
            { "name":"vondst","path":"vondst",
              "scope": {"ruleNames": ["kostbaarheid", "vindloon", "uitbetaling", "bewakingstoeslag", "bergloon",
                                      "herkomst onbekend", "premie", "totaaltaxatie", "taxatiegrens"]},
              "input": [],
              "output": ["Vondst.taxatie", "Vondst.toeslag", "Vondst.herkomst", "Vondst.vindloon", "Vondst.uitbetaling",
                         "Vondst.bewakingstoeslag", "Vondst.bergloon", "Vondst.premie", "Vondst:kostbaar",
                         "Vondst:betwist", "Vondst:onleesbaar", "Jutter.totaaltaxatie"] },
            { "name":"vracht","path":"vracht", "scope": {"ruleNames": ["vrachtprijs"]},
              "input": ["Vondst"], "output": ["Vondst.vrachtprijs"] } ],
          "parameters": {} }""";

    /** The core of {@link #DESCRIPTOR} over {@link #SOURCE}. */
    public static DecisionCore core(CoreConfig config) {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("open-waarden.rgs", SOURCE), config,
                Thread.currentThread().getContextClassLoader());
    }
}
