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

## Result

Gebouwd op `card/dienst-vocabulaires` (basis main 0fc3ff4, claim deb6ac6); de submodule `uchar/` staat
ongewijzigd op 43caed71. U1 tot en met U6 zijn gebouwd, U7 niet (alleen benoemd, hieronder).

### Gekozen namen en vormen, met hun grond

- **Parameters.** Twee queryparameters naast het bestaande `uitleg`: `vocabulaire` kiest het vocabulaire van het
  antwoord, `invoervocabulaire` zet het vocabulaire vast waarin de namen van het verzoek gelezen worden. Geen headers.
  Grond: de dienst draagt zijn verzoekopties al in de query; zo lezen de JDK-dienst en de Quarkus-adapter ze op één
  manier, en staan ze in de URL en in de OpenAPI. Een parameter die twee keer staat, telt de eerste keer, zoals bij
  `uitleg`; een lege waarde is een onbekend vocabulaire.
- **`canoniek`.** Het woord `canoniek` kiest de canonieke namen, in beide parameters, in de descriptor en op de
  scenario-CLI. Grond: een vocabulairenaam is een woord (WF-116), dus elk woord kan met een gedeclareerd vocabulaire
  botsen. Een model dat een vocabulaire `canoniek` declareert, start daarom niet (MALFORMED op veld `vocabulaires`:
  "het model declareert een vocabulaire 'canoniek', en 'canoniek' kiest de canonieke namen; geef het vocabulaire een
  andere naam"), in plaats van dat een van beide stil voorrang krijgt.
- **Soorten.** `DUBBELZINNIGE_NAAM` (een naam die twee declaraties noemt) en `ONBEKEND_VOCABULAIRE` (een vocabulaire
  dat het model niet declareert, in een verzoek en in de descriptor).
- **Extensies.** `x-spellingen` op elk objecttype, elke eigenschap en elk entrypoint; `x-waardespellingen` per
  enumeratie, per waarde; `x-vocabulaire` in `info`. Grond: in lijn met het bestaande `x-eenheid`; een waarde is een
  kale string in `enum`, dus haar spellingen staan naast de lijst, zoals `valueDesignations` in uchars wire.
- **/info.** Alleen bij een model met vocabulaires een blok `vocabulaires`: per vocabulaire `naam`, `standaard`,
  `taal` (als gedeclareerd), `volledig` en `zonderBenaming`, elke declaratie als `{soort, eigenaar?, naam}` bij haar
  canonieke naam, in de volgorde van uchars `benoembaar()`. Een entrypoint met een standaardvocabulaire noemt het
  (`vocabulaire`). Zonder vocabulaires verandert `/info` niet.
- **Weigerzinnen** noemen declaraties bij hun canonieke naam (die wijst één declaratie aan, in elk vocabulaire); de
  spelling van de afnemer staat in `veld` en tussen aanhalingstekens.
- **Vastgezet invoervocabulaire** leest strikt de effectieve namen van dat vocabulaire (U1): een canonieke naam van
  een declaratie met een benaming daar leest niet, en ook het canonieke meervoud van een rol niet. Een onbekende
  naam houdt de bestaande zin met de toevoeging ` (invoervocabulaire 'cgm')`; een onbekende enumeratiewaarde noemt de
  waarden in dat vocabulaire.
- **Twee spellingen van één declaratie in één object** (de attributen, kenmerken of rollen van één instantie, de
  parameters, de rollen van één relatie) zijn twee waarden voor één declaratie: 400 `MALFORMED`, "het attribuut
  'gewicht' van 'Vondst' staat twee keer in deze instantie: als 'gewicht' en als 'GEWICHT'". Dat is nieuw: tot nu
  nam de dienst bij twee spellingen die alleen in hoofdletters verschilden stil de laatste.
- **Rollen.** Een rol aan een instantie wordt gelezen onder de rollen van de feittypen waaraan het objecttype
  deelneemt (bereikbaarheid volgens IR-SPEC ch. 2.6); een rol in `relaties` binnen het genoemde feittype, canoniek nu
  hoofdletterongevoelig op de gedeclareerde naam (tot nu exact).
- **Canonieke uitvoer ongewijzigd.** Sleutels zoals de descriptor ze schrijft, een enumeratiewaarde in de spelling
  van het verzoek (zoals uchars replay die van een case houdt); een waarde die als benaming binnenkwam, staat er als
  de gedeclareerde waarde. De bestaande poorten en de TOKA-golden van de OpenAPI zijn onveranderd groen.
- **OpenAPI-levering.** Er was geen route en geen CLI-pad dat het document levert; het ontstaat alleen in
  `OpenApiEmitter`. Daar zit de keuze: `emit(descriptor, model, vocabulaire)`, `schrijf(descriptor, model,
  vocabulaire, doel)` en `bestandsnaam(descriptor, vocabulaire)` (`<dienst>.<vocabulaire>.json`). Geen nieuwe route.
- **Playground.** Queryparameter `vocabulaire` op `POST /playground/analyse` en een invoerveld `vocabulaire` op de
  pagina. De Mermaid-graaf is uchars weergave en blijft canoniek; `graafJson` draagt het vocabulaire.
- **Scenario-CLI.** `bin/regelspraak scenario [--trace] [--uitleg] [--vocabulaire <naam>] <bron.rgs...>`; een
  onbekend vocabulaire is exit 2; met `--manifest` is `--vocabulaire` geweigerd (exit 2), zoals `--uitleg`.
- **Code.** Uitvoernamen: `Naamgeving` (toolchain, `engine`), over uchars `benaming`/`effectieveNaam`. Invoer:
  `NaamLezer` (service), over uchars `benoemd` voor de benamingen en de bestaande canonieke opzoekingen voor de
  canonieke lezing. `VerzoekOpties` draagt `uitleg`, `vocabulaire`, `invoervocabulaire`; `Volledigheid` bouwt het
  `/info`-blok voor beide adapters.

### Voorbeeldverzoeken (echte uitvoer)

De vocabulairetweeling: `bin/regelspraak serve scripts/tweelingen/jutterswet-vocabulaires.dienst.json
uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs --poort 18741`. `liberaal.json` schrijft elke naam in het
handboek (`"premie per kilogram"`, `"strandjutter"`, `"gemelde vondst"`, `"soort vondst": "amber"`, ...).

Liberaal gelezen, canoniek geantwoord (de benaming `amber` staat er als de gedeclareerde waarde):

```
$ curl -s -X POST -H Content-Type:application/json --data @liberaal.json 'http://127.0.0.1:18741/premie'
{"instanties":[{"id":"J1","type":"Jutter","attributen":{"vondstbedrag":12.60,"aantal waardevolle vondsten":1},"kenmerken":{"premiewaardig":true}},{"id":"V1","type":"Vondst","attributen":{"soort":"barnsteen","premie":9.00},"kenmerken":{"waardevol":true}},{"id":"V2","type":"Vondst","attributen":{"soort":"wrakhout","premie":3.60},"kenmerken":{"waardevol":false}}],"inconsistenties":[],"inconsistentieDetails":[]}
```

Gemengd (`"PREMIE_TARIEF"`, `"Jutter"`, `"NAAM"`, `"vondsten"`, `"VONDST"`, `"soort vondst": "BARNSTEEN"`,
`"VONDST_SOORT": "wrakhout"` en een relatie `"aanmelding van een vondst"` met `"AANMELDER"` en `"gemelde vondst"`),
geantwoord in het handboek:

```
$ curl -s -X POST -H Content-Type:application/json --data @gemengd.json 'http://127.0.0.1:18741/premie?vocabulaire=handboek'
{"instanties":[{"id":"J1","type":"strandjutter","attributen":{"totale premie":12.60,"aantal vondsten van waarde":1},"kenmerken":{"komt in aanmerking voor premie":true}},{"id":"V1","type":"aangespoeld voorwerp","attributen":{"soort vondst":"amber","premie voor de vondst":9.00},"kenmerken":{"van waarde":true}},{"id":"V2","type":"aangespoeld voorwerp","attributen":{"soort vondst":"hout van een wrak","premie voor de vondst":3.60},"kenmerken":{"van waarde":false}}],"inconsistenties":[],"inconsistentieDetails":[]}
```

Vastgezet op het handboek, geantwoord in cgm, met uitleg:

```
$ curl -s -X POST -H Content-Type:application/json --data @liberaal.json 'http://127.0.0.1:18741/premie?invoervocabulaire=handboek&vocabulaire=cgm&uitleg=true'
{"instanties":[{"id":"J1","type":"JUTTER","attributen":{"VONDST_BEDRAG":12.60,"AANTAL_WAARDEVOL":1},"kenmerken":{"PREMIE_WAARDIG":true}},{"id":"V1","type":"VONDST","attributen":{"VONDST_SOORT":"BARNSTEEN","PREMIE":9.00},"kenmerken":{"WAARDEVOL":true}},{"id":"V2","type":"VONDST","attributen":{"VONDST_SOORT":"WRAKHOUT","PREMIE":3.60},"kenmerken":{"WAARDEVOL":false}}],"inconsistenties":[],"inconsistentieDetails":[],"uitleg":["J1 is PREMIE_WAARDIG, omdat zijn vondstbedrag (12,60) is groter dan 10","V1 is WAARDEVOL, omdat zijn soort (BARNSTEEN) is gelijk aan BARNSTEEN","V2 is niet WAARDEVOL, want niet geldt: zijn soort (WRAKHOUT) is gelijk aan BARNSTEEN"]}
```

Vastgezet op cgm, met handboeknamen (elke weigering zegt waar gelezen is):

```
$ curl -s -X POST -H Content-Type:application/json --data @liberaal.json 'http://127.0.0.1:18741/premie?invoervocabulaire=cgm'
{"fout":"verzoek valideert niet","schendingen":[{"veld":"parameters.premie per kilogram","soort":"ONBEKEND_VELD","fout":"onbekende parameter 'premie per kilogram' (invoervocabulaire 'cgm')"},{"veld":"instanties[0].type","soort":"ONBEKEND_VELD","fout":"onbekend objecttype 'strandjutter' (invoervocabulaire 'cgm')"},{"veld":"instanties[1].type","soort":"ONBEKEND_VELD","fout":"onbekend objecttype 'aangespoeld voorwerp' (invoervocabulaire 'cgm')"},{"veld":"instanties[2].type","soort":"ONBEKEND_VELD","fout":"onbekend objecttype 'aangespoeld voorwerp' (invoervocabulaire 'cgm')"}]}
```

Het standaardvocabulaire van het tweede entrypoint (descriptor `"vocabulaire": "cgm"`), zonder parameter:

```
$ curl -s -X POST -H Content-Type:application/json --data @liberaal.json 'http://127.0.0.1:18741/premie-cgm'
{"instanties":[{"id":"J1","type":"JUTTER","attributen":{"VONDST_BEDRAG":12.60,"AANTAL_WAARDEVOL":1},"kenmerken":{"PREMIE_WAARDIG":true}},{"id":"V1","type":"VONDST","attributen":{"VONDST_SOORT":"BARNSTEEN","PREMIE":9.00},"kenmerken":{"WAARDEVOL":true}},{"id":"V2","type":"VONDST","attributen":{"VONDST_SOORT":"WRAKHOUT","PREMIE":3.60},"kenmerken":{"WAARDEVOL":false}}],"inconsistenties":[],"inconsistentieDetails":[]}
```

Een onbekend vocabulaire:

```
$ curl -s -X POST -H Content-Type:application/json --data @liberaal.json 'http://127.0.0.1:18741/premie?vocabulaire=wetboek'
{"fout":"verzoek valideert niet","schendingen":[{"veld":"vocabulaire","soort":"ONBEKEND_VOCABULAIRE","fout":"het vocabulaire 'wetboek' is niet gedeclareerd; het model declareert 'handboek' en 'cgm', en 'canoniek' kiest de canonieke namen"}]}
```

Dubbelzinnig: de getuige `scripts/tweelingen/vocabulaire-dubbelzinnig.rgs` (eigen materiaal: `bedrag` is in het
handboek het vondstbedrag, in cgm het premiebedrag), op poort 18742; daarna de uitweg:

```
$ curl -s -X POST -H Content-Type:application/json --data '{"instanties":[{"type":"Jutter","id":"J1","attributen":{"bedrag":"10.00","premiebedrag":"2.50"}}]}' 'http://127.0.0.1:18742/uitkering'
{"fout":"verzoek valideert niet","schendingen":[{"veld":"instanties[0].attributen.bedrag","soort":"DUBBELZINNIGE_NAAM","fout":"de naam 'bedrag' is dubbelzinnig: in het vocabulaire 'handboek' het attribuut 'vondstbedrag' van 'Jutter', in het vocabulaire 'cgm' het attribuut 'premiebedrag' van 'Jutter'; zet het vocabulaire erbij met de queryparameter invoervocabulaire (handboek of cgm)"}]}

$ curl -s -X POST -H Content-Type:application/json --data '{"instanties":[{"type":"Jutter","id":"J1","attributen":{"bedrag":"10.00","premiebedrag":"2.50"}}]}' 'http://127.0.0.1:18742/uitkering?invoervocabulaire=handboek&vocabulaire=cgm'
{"instanties":[{"id":"J1","type":"Jutter","attributen":{"UITKERING":12.50}}],"inconsistenties":[],"inconsistentieDetails":[]}
```

Een benaming tegenover een canonieke naam (JUnit, `NaamLezerTest`, eigen materiaal): "de naam 'toeslag' is
dubbelzinnig: als canonieke naam en in het vocabulaire 'cgm' het attribuut 'toeslag' van 'Jutter', in het
vocabulaire 'handboek' het attribuut 'basisbedrag' van 'Jutter'; zet het vocabulaire erbij met de queryparameter
invoervocabulaire (canoniek, cgm of handboek)".

`/info` van de tweeling (twee volledige vocabulaires) en van de getuige (twee onvolledige):

```
$ curl -s 'http://127.0.0.1:18741/info'     # uittreksel: entrypoints[1] en vocabulaires
{"entrypoint1": {"naam": "premie in het cgm", "pad": "premie-cgm", "vocabulaire": "cgm"}, "vocabulaires": [{"naam": "handboek", "standaard": false, "taal": "nl", "volledig": true, "zonderBenaming": []}, {"naam": "cgm", "standaard": true, "taal": "nl", "volledig": true, "zonderBenaming": []}]}

$ curl -s 'http://127.0.0.1:18742/info'     # uittreksel: vocabulaires
[{"naam": "handboek", "standaard": false, "taal": "nl", "volledig": false, "zonderBenaming": [{"soort": "OBJECTTYPE", "naam": "Jutter"}, {"soort": "ATTRIBUUT", "eigenaar": "Jutter", "naam": "premiebedrag"}, {"soort": "ATTRIBUUT", "eigenaar": "Jutter", "naam": "uitkering"}, {"soort": "VORM", "naam": "uitkering van een jutter"}]}, {"naam": "cgm", "standaard": false, "taal": "nl", "volledig": false, "zonderBenaming": [{"soort": "OBJECTTYPE", "naam": "Jutter"}, {"soort": "ATTRIBUUT", "eigenaar": "Jutter", "naam": "vondstbedrag"}, {"soort": "VORM", "naam": "uitkering van een jutter"}]}]
```

### Descriptor-uitbreiding

Eén optioneel veld per entrypoint, `vocabulaire`: het vocabulaire van het antwoord als het verzoek er geen kiest
(een gedeclareerd vocabulaire of `canoniek`; zonder: canoniek). Een onbekend vocabulaire weigert de start
(`ONBEKEND_VOCABULAIRE` op `entrypoints[i].vocabulaire`, exit 1). De overige namen in de descriptor (scope, invoer,
uitvoer, invoerElementen, parameters) blijven canonieke namen. Uit `scripts/tweelingen/jutterswet-vocabulaires.dienst.json`:

```json
{ "naam": "premie in het cgm", "pad": "premie-cgm",
  "scope": { "regelgroepen": ["premieberekening"] },
  "invoer": ["Jutter", "Vondst"],
  "uitvoer": ["Vondst.soort", "Vondst.premie", "Vondst:waardevol", "Jutter.vondstbedrag",
              "Jutter.aantal waardevolle vondsten", "Jutter:premiewaardig"],
  "vocabulaire": "cgm" }
```

### OpenAPI

Drie nieuwe goldens uit uchars `jutterswet-vocabulaires/bron.rgs` met die descriptor, byte-gepind in
`service/src/test/resources/openapi/`: `jutterswet-vocabulaires.golden.json` (canoniek),
`jutterswet-vocabulaires.handboek.golden.json` en `jutterswet-vocabulaires.cgm.golden.json`; de TOKA-golden is
byte-gelijk gebleven (model zonder vocabulaires, geen extensie). Uittreksel uit het cgm-document:

```json
"VONDST_SOORT": {
  "type": "string",
  "enum": ["WRAKHOUT", "BARNSTEEN", "FLESSENPOST"],
  "x-waardespellingen": {
    "WRAKHOUT": { "canoniek": "wrakhout", "handboek": "hout van een wrak" },
    "BARNSTEEN": { "canoniek": "barnsteen", "handboek": "amber" },
    "FLESSENPOST": { "canoniek": "flessenpost", "handboek": "bericht in een fles" }
  },
  "description": "De dienst neemt ook 'soort' (canoniek) en 'soort vondst' (handboek) aan.",
  "x-spellingen": { "canoniek": "soort", "handboek": "soort vondst" }
}
```

en per entrypoint, naast de queryparameters `vocabulaire` en `invoervocabulaire` (enum `canoniek`, `handboek`,
`cgm`), de invoer en uitvoer in de andere spellingen:

```json
"x-spellingen": { "handboek": { "invoer": ["strandjutter", "aangespoeld voorwerp"],
  "uitvoer": ["aangespoeld voorwerp.soort vondst", "aangespoeld voorwerp.premie voor de vondst",
              "aangespoeld voorwerp:van waarde", "strandjutter.totale premie",
              "strandjutter.aantal vondsten van waarde", "strandjutter:komt in aanmerking voor premie"] } }
```

`info.x-vocabulaire` noemt het vocabulaire van het document; `info.description` zegt welke namen de dienst ook
aanneemt, dat een dubbelzinnige naam een 400 is tenzij het invoervocabulaire vastligt, en dat een gewijzigde
benaming een wijziging van het contract is.

### Verwoording

Verbalisator, uitleg, trace en dekking noemen objecttypen, attributen, kenmerken, rollen, parameters, regels en
enumeratiewaarden in het gekozen vocabulaire; de brontekst van een operand is regeltekst en blijft canoniek. Een
enumeratiewaarde van een attribuutread krijgt haar type van de declaratie (een read uit uchars lader draagt zelf
geen type). Scenarioroute in het handboek:

```
$ bin/regelspraak scenario --trace --uitleg --vocabulaire handboek uchar/corpus/golden/jutterswet-vocabulaires/bron.rgs scenario.rgs
PASS  premie per vondst
    trace (8 vuring(en)):
      premie per vondst @ [V1, V2] x3
      premie per strandjutter @ [J1] x2
      vondst van waarde @ [V1] x1
      telling van de waardevolle vondsten @ [J1] x1
      toets op premie @ [J1] x1
    uitleg:
      J1 is komt in aanmerking voor premie, omdat zijn vondstbedrag (12,60) is groter dan 10
      V1 is van waarde, omdat zijn soort (amber) is gelijk aan amber
      V2 is niet van waarde, want niet geldt: zijn soort (hout van een wrak) is gelijk aan amber
```

(`scenario.rgs` is een eigen scenarioblok bij de golden, ook in `toolchain/src/test/resources/vocabulaires/`.) De
playground geeft in cgm onder meer de graafknopen `R_PREMIE_VONDST` en `VONDST_BEDRAG · JUTTER`
(`PlaygroundVocabulaireTest`).

### Poort en vloer

`scripts/check-tweelingen.sh` speelt na de drie dialect-tweelingen de vocabulairetweeling via
`scripts/vocabulaire_replay.py`: elke case van `jutterswet-vocabulaires` in vier spellingen (canoniek, handboek,
cgm, gemengd) met het antwoord in drie vocabulaires, per spelling het vastgezette invoervocabulaire, het
standaardvocabulaire van het tweede entrypoint, de weigering van een handboekspelling onder `invoervocabulaire=cgm`,
en `/info` (het aantal declaraties zonder benaming per vocabulaire, onafhankelijk geteld uit het `ir.json`). De
vertaaltafel is het `ir.json` van de golden, niet de code van de dienst; een mutatieproef (een benaming in een kopie
van het `ir.json` veranderd) laat de driver falen met plek en verwachting ("case1.json, spelling canoniek,
vocabulaire handboek: verwachting 1 (attributeValue V1 premie voor het voorwerp): het antwoord noemt het attribuut
niet"). Daarna de getuige: vier getuigenissen
(dubbelzinnig met beide lezingen, vastgezet in twee uitvoervocabulaires, onbekend vocabulaire). Pins: de cases van
de vocabulairetweeling als gelijkheid (2), vloeren `EXPECTED_MIN_VOCABULAIRE_VERZOEKEN=34` en
`EXPECTED_MIN_GETUIGENISSEN=4` (gemeten 2026-09-27). Uitvoer:

```
check-tweelingen: PASS — 3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
```

`check-dienst-replay` is onveranderd: 190 goldens, 498 cases (pin 190), 27 uitgesloten bij naam.

### Tellingen

- `mvn -B -o verify`: uchar-backend 846, toolchain 120 (was 113; vloer 110 → 120), service 210 (was 184; vloer
  180 → 210), service-quarkus 43 (was 40; vloer 33 → 43); nul overgeslagen. Nieuwe suites: `NaamgevingTest` (5),
  `VocabulaireScenarioTest` (2), `NaamLezerTest` (10), `VocabulaireAntwoordTest` (6), `VocabulaireHttpTest` (3),
  `OpenApiVocabulaireTest` (5), `PlaygroundVocabulaireTest` (2), `VocabulaireParityTest` (3).
- `make poorten`: groen in 2m06s — pin (uchar 43caed71), reactor 846/120/210/43, tweelingen (hierboven), replay 190
  goldens / 498 cases (pin 190), manifest 32 regels, 29 ok, 3 uitgesloten, 0 mismatch.
- `make gates` (Docker draaide): groen, 7 stappen in 3m44s op 94d963d — de vijf van `make poorten` met dezelfde
  tellingen, image `beslisservice:dev` (46s), kind-smoke H2 PASS, H3 PASS, H4 PASS (51s).

### Open punten

1. **uchars `RegelGraaf` geeft per knoop geen elementsleutel.** De playground zoekt bij een elementknoop de
   declaratie via het label (`"<lid> · <type>"`, genormaliseerd) onder de attributen en kenmerken van dat type. Een
   invoerknoop waarvan een attribuut en een kenmerk met dezelfde naam verschillende benamingen hebben, is niet te
   beslissen: `graafJson` vervalt dan en `graafFout` noemt de knoop. Kaart voor uchar: de sleutel per knoop publiek
   maken, of `mermaid()` een naamfunctie geven (dan kan ook de Mermaid-graaf in een vocabulaire).
2. **De Mermaid-graaf** van de playground en van `bin/regelspraak graaf` blijft canoniek (uchars weergave); zie 1.
3. **Geen route of CLI voor de OpenAPI-documenten**, voor en na deze kaart: ze ontstaan via de API van de emitter (de
   tests schrijven ze naar `target/openapi/`). Een `GET /openapi?vocabulaire=` of een `bin/regelspraak openapi` is
   een eigen kaart.
4. **Zinsbouw van de uitleg.** De zinnen van de verbalisator zijn canoniek gebouwd ("X is <kenmerk>"); een benaming
   in een andere woordvorm leest stroef: "J1 is komt in aanmerking voor premie".
5. **Bereikbaarheid van een rol** (`Naamgeving.bereikbaar`, `vult`) past IR-SPEC ch. 2.6 toe via de publieke structuur
   van het model, zoals de dienst al deed (`VerzoekMapper.vultRol`); uchar heeft dezelfde regel privé
   (`feittypeRelateert`, `rolHeeftObjecttype`). Een publieke methode in uchar maakt deze tweede formulering overbodig.
6. **`benoemd` maakt geen onderscheid tussen geen en twee treffers.** De lezer vertrouwt voor één vocabulaire op
   WF-118 (een verifier-schoon document heeft er per vocabulaire hooguit één).
7. **Buiten "Mag raken" aangeraakt, alleen tekst:** `bin/regelspraak` (de gebruikstekst van `scenario` noemt
   `--vocabulaire`) en `Makefile` (de helptekst van `check-tweelingen`); `tools/check-skips.sh` (de vloeren) volgens de
   opdracht. Terugdraaien kan zonder gedragsverschil.
8. **U7, niet in deze kaart:** een deprecatiestatus per benaming en meertaligheid (uchar); de lint tegen de
   begrippenkaart (werkbank); de dienst die de Parameterset uit het document leest.

### Normvragen (met het standaardantwoord dat de bestaande dienst volgt)

- Leest de descriptor (scope, invoer, uitvoer, parameters) ook benamingen? Standaard: nee, de descriptor is het
  contract van de dienstbeheerder met uchars model en blijft canoniek; alleen `vocabulaire` is nieuw.
- Noemen weigerzinnen de declaraties in het uitvoervocabulaire? Standaard: nee, canoniek zoals tot nu, met de
  spelling van de afnemer in `veld`.
- Zet de canonieke uitvoer een enumeratiewaarde in de gedeclareerde spelling? Standaard: nee, de spelling van het
  verzoek blijft (zoals uchars replay die van een case houdt); in een vocabulaire staat de benaming.
- Leest een vastgezet vocabulaire ook canonieke meervouden en andere spellingen? Standaard: nee, strikt de
  effectieve namen (de letter van U1).
- Wordt `/info` zelf in een vocabulaire geleverd? Standaard: nee, `/info` is het manifest en blijft canoniek, met het
  blok `vocabulaires` erbij.

### Commits op de branch

```
0cee1f6 Naamgeving: verwoording en scenarioroute in een gekozen vocabulaire
6c034be Dienst: namen liberaal lezen, antwoorden in het gekozen vocabulaire
5cb1e54 HTTP: vocabulaire als queryparameter, /info met volledigheid
c487bb2 OpenAPI: één document per vocabulaire met x-spellingen
1697407 Poort: de vocabulairetweeling en een dubbelzinnigheidsgetuige
c3cc62a Test: een uchar-ir-v1-document als bron draagt de vocabulaires
038e2a8 Vloeren: de suites gegroeid met de vocabulaires
0e4dc23 README en ENGINE: de vocabulaires aan de rand van de dienst
94d963d Naamgeving: alleen de namen die een uitvoer noemt
```

en de commit van dit Result.
