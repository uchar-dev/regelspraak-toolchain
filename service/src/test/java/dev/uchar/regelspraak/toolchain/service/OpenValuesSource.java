package dev.uchar.regelspraak.toolchain.service;

import java.util.Map;

/**
 * Test fixture: a rule set of the Jutterswet that infers nothing from an empty value ({@code Afleiden uit leeg: nee}), with
 * a rule, a characteristic, a defaults group, a fold over a set, an attribute with an absence marker, a consistency, and
 * two members whose input is complete. The entry point {@code vracht} reads a parameter nothing binds unless the request
 * does. {@link #WRAK_SOURCE} is a second such rule set, with the forms whose explanation the tests read: rules on one
 * attribute, an initialisation, composites, the tests on leeg and gevuld, stacks with and without an order, an opgave, folds
 * and quantifier folds, and a read over a role.
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

    public static final String WRAK_SOURCE = """
        Afleiden uit leeg: nee

        Objecttype de Jutter (mv: Jutters) (bezield)
            de bonus Numeriek (geheel getal);
            de ladingsom Numeriek (geheel getal);
            het aantal gepeilde wrakken Numeriek (geheel getal);
            de gepeilde ladingsom Numeriek (geheel getal);
            is kundig kenmerk (bijvoeglijk);
            is keurder kenmerk (bijvoeglijk);
            is volledig gepeild kenmerk (bijvoeglijk);

        Objecttype het Wrak (mv: Wrakken)
            de diepte Numeriek (geheel getal);
            de lading Numeriek (geheel getal);
            de lengte Numeriek (geheel getal);
            de ladingwaarde Numeriek (geheel getal);
            het wrakloon Numeriek (geheel getal);
            de vindpremie Numeriek (geheel getal);
            het voorschot Numeriek (geheel getal);
            het loodsgeld Numeriek (geheel getal);
            de wrakpremie Numeriek (geheel getal);
            het bergingsloon Numeriek (geheel getal);
            het kadegeld Numeriek (geheel getal) (mag opgegeven worden);
            de diepteklasse Numeriek (geheel getal);
            is gemeld kenmerk (bijvoeglijk);
            is betwist kenmerk (bijvoeglijk);
            is beschadigd kenmerk (bijvoeglijk);
            is gemerkt kenmerk (bijvoeglijk);
            is gevaarlijk kenmerk (bijvoeglijk);
            is keuringsplichtig kenmerk (bijvoeglijk);
            is vrij kenmerk (bijvoeglijk);
            is ongepeild kenmerk (bijvoeglijk);
            is gepeild kenmerk (bijvoeglijk);
            is ongewaardeerd kenmerk (bijvoeglijk);
            is zeldzaam kenmerk (bijvoeglijk);
            is stil kenmerk (bijvoeglijk);
            is beloonbaar kenmerk (bijvoeglijk);

        Feittype wrakvondst
            de vinder Jutter
            het gevonden wrak (mv: gevonden wrakken) Wrak
            één vinder heeft meerdere gevonden wrakken

        Regel gevaar
            geldig altijd
                Een Wrak is gevaarlijk indien hij aan ten minste één van de volgende voorwaarden voldoet:
                - zijn diepte is groter dan 10
                - zijn lengte is groter dan 20.

        Regel gevaar door lading
            geldig altijd
                Een Wrak is gevaarlijk indien zijn lading groter is dan 100.

        Regel keuringsplicht
            geldig altijd
                Een Wrak is keuringsplichtig indien hij aan alle volgende voorwaarden voldoet:
                - zijn diepte is groter dan 10
                - zijn lengte is groter dan 20.

        Regel vrijdom
            geldig altijd
                Een Wrak is vrij indien hij aan ten minste twee van de volgende voorwaarden voldoet:
                - hij is betwist
                - zijn diepte is groter dan 10
                - zijn lengte is groter dan 20.

        Regel ongepeild
            geldig altijd
                Een Wrak is ongepeild indien zijn diepte leeg is.

        Regel gepeild
            geldig altijd
                Een Wrak is gepeild indien zijn diepte gevuld is.

        Regel ongewaardeerd
            geldig altijd
                Een Wrak is ongewaardeerd indien zijn ladingwaarde leeg is.

        Regel zeldzaam
            geldig altijd
                Een Wrak is zeldzaam indien hij aan geen van de volgende voorwaarden voldoet:
                - zijn diepte is gevuld.

        Regel stilte
            geldig altijd
                Een Wrak is stil indien hij niet gevaarlijk is.

        Regel ladingwaarde
            geldig altijd
                De ladingwaarde van een Wrak moet berekend worden als zijn lading maal 2.

        Regel wrakloon
            geldig altijd
                Het wrakloon van een Wrak moet berekend worden als zijn ladingwaarde plus zijn lengte.

        Regel vindpremie
            geldig altijd
                De vindpremie van een Wrak moet berekend worden als zijn lengte plus de bonus van de vinder van het Wrak.

        Regel voorschot
            geldig altijd
                Het voorschot van een Wrak moet gesteld worden op 3 indien zijn diepte groter is dan 10.

        Regel voorschot gemeld
            geldig altijd
                Het voorschot van een Wrak moet gesteld worden op 3 indien hij gemeld is.

        Regel loodsgeld
            geldig altijd
                Het loodsgeld van een Wrak moet berekend worden als zijn lengte maal 2 indien hij gemeld is.

        Regel loodsgeld basis
            geldig altijd
                Het loodsgeld van een Wrak moet geïnitialiseerd worden op 25.

        Regel wrakpremie
            geldig altijd
                De wrakpremie van een Wrak moet gesteld worden op 10.

        Afwijking van wrakpremie: gemerkte wrakpremie
            geldig altijd
                De wrakpremie van een Wrak moet gesteld worden op zijn lading indien hij gemerkt is.

        Afwijking van wrakpremie: betwiste wrakpremie
            geldig altijd
                De wrakpremie van een Wrak moet gesteld worden op 0 indien hij betwist is.

        Regel beloonbaarheid
            geldig altijd
                Een Wrak is beloonbaar indien zijn lengte groter is dan 2.

        Uitzondering op beloonbaarheid: betwiste beloonbaarheid
            geldig altijd
                Een Wrak is niet beloonbaar indien hij betwist is.

        Regel kadegeld
            geldig altijd
                Het kadegeld van een Wrak moet berekend worden als zijn lengte maal 3.

        Regel kadegeld basis
            geldig altijd
                Het kadegeld van een Wrak moet geïnitialiseerd worden op 25.

        Regel diepteklasse
            geldig altijd
                De diepteklasse van een Wrak moet berekend worden als zijn diepte gedeeld door 10 rekenkundig afgerond op 0 decimalen.

        Regel dieptegrens
            geldig altijd
                De diepte van een Wrak moet kleiner zijn dan 500.

        Regel ladingsom
            geldig altijd
                De ladingsom van een Jutter moet berekend worden als de som van de lading van alle gevonden wrakken van de Jutter.

        Regel aantal gepeild
            geldig altijd
                Het aantal gepeilde wrakken van een Jutter moet berekend worden als het aantal gevulde waarden van de diepte van alle gevonden wrakken van de Jutter.

        Regel gepeilde ladingsom
            geldig altijd
                De gepeilde ladingsom van een Jutter moet berekend worden als de som van de lading van alle gevonden wrakken van de Jutter die aan de volgende voorwaarde voldoen: zijn diepte is gevuld.

        Regel kundigheid
            geldig altijd
                Een Jutter is kundig indien de diepte van alle gevonden wrakken van de Jutter groter is dan 10.

        Regel keurderschap
            geldig altijd
                Een Jutter is keurder indien de diepte van alle gevonden wrakken van de Jutter die gemerkt zijn groter is dan 10.

        Regel volledige peiling
            geldig altijd
                Een Jutter is volledig gepeild indien het aantal gevulde waarden van de diepte van alle gevonden wrakken van de Jutter gelijk is aan 3.

        Regel bergingsloon
            geldig altijd
                Het bergingsloon van een Wrak moet gesteld worden op 100 indien zijn lengte groter is dan 1.

        Uitzonderingen ongeordend op bergingsloon:
        Regel betwist bergingsloon
            geldig altijd
                Het bergingsloon van een Wrak moet gesteld worden op 0 indien hij betwist is.

        Regel beschadigd bergingsloon
            geldig altijd
                Het bergingsloon van een Wrak moet gesteld worden op 5 indien hij beschadigd is.
        """;

    public static final String WRAK_DESCRIPTOR = """
        { "service": {"name":"wrakken","version":"1"},
          "entrypoints": [
            { "name":"wrak","path":"wrak",
              "scope": {"ruleNames": ["gevaar", "gevaar door lading", "keuringsplicht", "vrijdom", "ongepeild", "gepeild",
                                      "ongewaardeerd", "zeldzaam", "stilte", "ladingwaarde", "wrakloon", "vindpremie",
                                      "voorschot", "voorschot gemeld", "loodsgeld", "loodsgeld basis", "wrakpremie",
                                      "bergingsloon", "beloonbaarheid", "kadegeld", "opgave boven berekening kadegeld",
                                      "afwijking opgave kadegeld", "ladingsom", "aantal gepeild", "gepeilde ladingsom",
                                      "kundigheid", "keurderschap", "volledige peiling", "diepteklasse", "dieptegrens"]},
              "input": ["Wrak"],
              "output": ["Wrak:gevaarlijk", "Wrak:keuringsplichtig", "Wrak:vrij", "Wrak:ongepeild", "Wrak:gepeild",
                         "Wrak:ongewaardeerd", "Wrak:zeldzaam", "Wrak:stil", "Wrak:beloonbaar", "Wrak.ladingwaarde",
                         "Wrak.wrakloon", "Wrak.vindpremie", "Wrak.voorschot", "Wrak.loodsgeld", "Wrak.wrakpremie",
                         "Wrak.bergingsloon", "Wrak.kadegeld", "Wrak.diepteklasse", "Jutter.ladingsom",
                         "Jutter.aantal gepeilde wrakken", "Jutter.gepeilde ladingsom", "Jutter:kundig", "Jutter:keurder",
                         "Jutter:volledig gepeild"] } ],
          "parameters": {} }""";

    /** The core of {@link #DESCRIPTOR} over {@link #SOURCE}. */
    public static DecisionCore core(CoreConfig config) {
        return DecisionCore.assemble(DESCRIPTOR, Map.of("open-waarden.rgs", SOURCE), config,
                Thread.currentThread().getContextClassLoader());
    }

    /** The core of {@link #WRAK_DESCRIPTOR} over {@link #WRAK_SOURCE}. */
    public static DecisionCore wrak(CoreConfig config) {
        return DecisionCore.assemble(WRAK_DESCRIPTOR, Map.of("wrakken.rgs", WRAK_SOURCE), config,
                Thread.currentThread().getContextClassLoader());
    }
}
