# dienst-vocabulaires: één dienst die elke benaming leest en antwoordt in het vocabulaire dat de afnemer kiest

Uchar heeft benoemde vocabulaires met een benaming per declaratie (kaart
`vocabulaires-benamingen`, geland 2026-09-27 op uchar 43caed71, hier
gepind sinds 62b7948): een zijtafel zonder semantiek, `Vocabulaire <naam>
(standaard) (taal: <code>)` en `(<vocabulaire>: <benaming>)` als staart van
een naamgroep, met de regels WF-116 tot en met WF-120 — de effectieve
namen (benaming waar die er is, anders de canonieke naam) zijn per
vocabulaire uniek binnen de reikwijdte van de canonieke namen, zodat de weg
terug van een naam naar de declaratie per vocabulaire één antwoord heeft.
De Drools-lader draagt de tafel in `RegelSpraakModel` met een publieke
leesingang: `vocabulaire(naam)`, `benaming(vocabulaire, Benoembaar)`,
`effectieveNaam(vocabulaire, Benoembaar)`, de omkering `benoemd(vocabulaire,
soort, eigenaar, naam)` en `benoembaar()`. Een benaming is tekst (spaties
inbegrepen); uchar kent geen soort code/term.

Deze kaart gebruikt die tafel aan de rand: op de JSON-grens van de dienst,
in de OpenAPI en in de verwoording. Maintainersbesluiten (Yeb, 2026-09-27):
er is ÉÉN dienst, geen dienst per vocabulaire; de invoer is liberaal; het
uitvoervocabulaire wordt per verzoek gekozen; de OpenAPI is een schoon
document per vocabulaire met de andere spellingen als `x-`-extensie.

## U-besluiten (hoofdsessie, 2026-09-27)

- U1 Invoer liberaal. Elke naam in een verzoek — objecttype-label,
  attribuut, kenmerk, rol, parameter, enumeratiewaarde — mag canoniek zijn
  of een benaming uit welk gedeclareerd vocabulaire dan ook, ook gemengd
  binnen één verzoek. De lezer lost een naam op binnen zijn reikwijdte
  (dezelfde als WF-118: attributen en kenmerken binnen het objecttype
  inclusief de geërfde, rollen binnen het feittype, waarden binnen het
  domein, de rest globaal), hoofdletterongevoelig, via de leesingang van
  het model: canoniek én elk vocabulaire. Eén declaratie gevonden = die.
  Twee verschillende declaraties gevonden (in verschillende vocabulaires,
  of een benaming tegenover een canonieke naam) = dubbelzinnig: een 400
  met een eigen soort en een zin die beide lezingen noemt (declaratie en
  vocabulaire) en vraagt het vocabulaire erbij te zetten; nooit gegokt.
  Geen declaratie gevonden = de bestaande weigering voor een onbekend veld.
  Optioneel kan het verzoek het invoervocabulaire vastzetten (queryparameter
  of header, de bouwer kiest de namen en documenteert ze); dan worden namen
  alleen als effectieve naam in dat vocabulaire gelezen — de uitweg bij
  dubbelzinnigheid.
- U2 Uitvoer gekozen. Een optionele parameter per verzoek kiest het
  vocabulaire van het antwoord: veldnamen, rolnamen, parameternamen,
  enumeratiewaarden, en de regel- en groepsnamen in schendingen, uitleg,
  trace en `graafJson`; zonder parameter geldt de standaard die de
  descriptor per entrypoint noemt (optioneel), anders canoniek. Een
  onbekend vocabulaire is een 400 met een eigen soort en een zin die de
  gedeclareerde vocabulaires noemt. Een declaratie zonder benaming in het
  gekozen vocabulaire draagt haar canonieke naam; `/info` noemt per
  vocabulaire of het volledig is en welke declaraties er geen benaming
  hebben (uchars WF-119 garandeert volledigheid alleen voor het
  standaardvocabulaire), zodat een afnemer die terugval niet hoeft te
  raden.
- U3 OpenAPI. Waar het document vandaag ontstaat (`OpenApiEmitter`, en de
  route of het CLI-pad dat het levert), kan het vocabulaire gekozen worden
  (parameter, zonder = canoniek): één schoon document per vocabulaire uit
  dezelfde emitter, met de effectieve namen van dat vocabulaire als
  eigenschapsnamen en enumeratiewaarden. Elke eigenschap, waarde en
  entrypoint noemt in een `x-`-extensie (de bouwer kiest de naam) zijn
  andere spellingen — canoniek en de overige vocabulaires — en de
  beschrijving zegt dat de dienst die ook aanneemt (U1). Een model zonder
  vocabulaires krijgt geen extensie: de bestaande golden
  (`service/src/test/resources/openapi/`) blijft byte-gelijk. Nieuwe
  goldens uit uchars `corpus/golden/jutterswet-vocabulaires/bron.rgs`
  voor canoniek, `handboek` en `cgm`. Geen liberaal "alle"-document.
- U4 Verwoording. De verbalisator, de uitleg, de trace en `graafJson` van
  de playground en de scenarioroute (`--vocabulaire` op de scenario-CLI)
  gebruiken het gekozen vocabulaire voor namen van objecttypen,
  attributen, kenmerken, rollen, regels, groepen en enumeratiewaarden; de
  regeltekst zelf blijft canoniek.
- U5 Poorten. Een tweeling uit uchars corpus met vocabulaires
  (`jutterswet-vocabulaires`) via HTTP: dezelfde cases in canonieke,
  `handboek`-, `cgm`- en gemengde spelling met dezelfde gepinde
  verwachtingen, het antwoord in elk gekozen vocabulaire, en een
  dubbelzinnigheidsgetuige (een naam die in twee vocabulaires naar
  verschillende declaraties wijst → 400 met beide lezingen); een vloer;
  de bestaande poorten onveranderd groen; `make gates`.
- U6 README (en `docs/ENGINE.md` waar de dienst beschreven staat): hoe de
  dienst namen leest (liberaal, dubbelzinnigheidsweigering, vastzetten),
  hoe een afnemer het uitvoervocabulaire kiest, wat volledig betekent, de
  OpenAPI per vocabulaire met de extensie, en dat een gewijzigde benaming
  een contractwijziging is.
- U7 Niet in deze kaart, alleen benoemen: een deprecatiestatus per
  benaming en meertaligheid (uchar, later); de lint tegen de begrippenkaart
  (werkbank); de dienst die de Parameterset uit het document leest.

Mag raken: `service/`, `service-quarkus/`, `toolchain/`, `scripts/`,
`README.md`, `docs/ENGINE.md`, `CLAUDE.md`. Niet: `uchar/` (een vraag aan
uchar is een kaart daar), `examples/toka*`, `specification/`.
