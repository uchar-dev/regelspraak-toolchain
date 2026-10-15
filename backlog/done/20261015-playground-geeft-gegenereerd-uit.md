# playground-geeft-gegenereerd-uit: per element dat uchar zelf maakte, het element waaruit het komt

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `b2d3ed8` (branch `card/playground-rol-geeft-feittype`, gekeurd
en nog niet geland; deze kaart bouwt erop voort).*

## Aanleiding

De werkbank toont in de objectboom `berekende maxdatum` en `afwijkende opgave maxdatum`, en in de dekking en de trace
`defaults A:RechtOpUitkeringWwBijOntslag.maxdatum`. Die namen staan niet in de bron: uchar maakt ze uit de markering
`(mag opgegeven worden)` en uit de effectieve defaultsstapels. De werkbank wil zulke elementen een eigen kleur en een
uitleg geven en doorverwijzen naar het element waaruit ze komen. Op de naam mag dat niet: "berekende" of " voor " kan
ook in een geschreven naam staan.

uchar legt de herkomst vast op de kaart `gegenereerde-elementen-dragen-herkomst` (branch
`card/gegenereerde-elementen-dragen-herkomst`): `RegelSpraakModel.gegenereerd` en `gegenereerdUit(Benoembaar)` voor
de elementen van het model, en `TmsTranspiler.gegenereerd()` voor de namen in de dekking die geen declaratie noemen
(een effectieve defaultsstapel, een versie `R #k`).

## Wat er gebeurt

De JSON van `Playground.analyseer` wordt alleen uitgebreid; bestaande sleutels houden hun vorm.

1. **De pin** gaat naar de uchar-branch van die kaart.
2. **`vocabulaires.elementen[]`**: een element dat uchar zelf maakte, krijgt `gegenereerdUit: {soort, eigenaar, naam,
   vorm}`: soort, eigenaar en naam van het bronelement (canoniek, zoals `soort`, `eigenaar` en `canoniek` van een
   element) en `vorm`, uchars vaste sleutel (`opgave-boven-berekening`, …).
3. **`gegenereerd`**, naast `dekking` als het document uitvoerbaar is: per naam in de dekking die geen element van
   `vocabulaires.elementen` is, `{naam, soort, gegenereerdUit}`, met `naam` in het gekozen vocabulaire zoals de
   dekking en de trace van een scenario hem schrijven en `naamCanoniek` ernaast waar die afwijkt, en `soort` de soort
   uit de dekking. Een naam in de dekking die geen element is en die uchar niet als gegenereerd noemt, laat de analyse
   falen met een zin die de naam noemt.

De toolchain leidt niets af uit een naam: alles komt uit uchars tabellen.

## Acceptatie

- (a) Op het s01-document van herleving-ww (v2) hebben `opgegeven maxdatum`, `berekende maxdatum` en `afwijkende
  opgave maxdatum` van RechtOpUitkeringWwBijOntslag `gegenereerdUit` naar het attribuut `maxdatum` van dat type, en
  geeft `gegenereerd` voor `defaults A:RechtOpUitkeringWwBijOntslag.maxdatum` hetzelfde attribuut.
- (b) Een element met `gegenereerdUit` heeft geen `regel`; op elke golden van uchar met een `bron.rgs` heeft elk
  element `regel` of `gegenereerdUit` (een uitzondering staat met haar reden in de test), en elke naam in de dekking
  is een element of staat in `gegenereerd`.
- (c) Nieuw gedrag in een nieuwe testklasse; een bestaand testbestand verandert alleen als de uitbreiding het
  afdwingt, in een eigen commit met de reden in het Result.
- (d) `docs/ENGINE.md` noemt de sleutels. `make gates` groen.

## Omvang

Klein. May touch: de submodule-pin `uchar`, `service/` (`Playground`), een nieuwe testklasse, `docs/ENGINE.md`,
kaartverplaatsing. Niet `uchar/`.

## Raakvlakken

De werkbank (`werkbank/static/`) leest dit contract en wordt elders aangepast.

## Result (2026-10-15)

### Wat de kaart opleverde

- **De pin.** `uchar` gaat van `c7191c58` naar `99f614b8`, de tip van de uchar-branch
  `card/gegenereerde-elementen-dragen-herkomst` (worktree `wt/gegenereerde-elementen-dragen-herkomst`). Daartussen:
  `9e5558a5` kaart, `290eb5b2` claim, `e90ab2d4` de tabel, `99f614b8` Result en naar `done/`. De commits staan nog niet op
  origin, en die van `card/scenario-rol-draagt-feittype` eronder ook niet.
- **`vocabulaires.elementen[]`.** Een element waarvoor uchars `RegelSpraakModel.gegenereerdUit` een herkomst geeft,
  krijgt achter de bestaande sleutels `gegenereerdUit: {soort, eigenaar, naam, vorm}`: soort, eigenaar en naam van
  het bronelement, canoniek zoals `soort`, `eigenaar` en `canoniek` van een element, en `vorm`, uchars vaste sleutel.
- **`gegenereerd`**, naast `dekking` als het document uitvoerbaar is (`Playground.gegenereerd`): per dekkingnaam die
  in uchars `TmsTranspiler.gegenereerd()` staat `{naam, soort, gegenereerdUit}`, in de volgorde van de dekking. `naam`
  is `Naamgeving.vorm` van uchars naam, zoals de dekking en de trace van een scenario hem schrijven, met
  `naamCanoniek` ernaast waar die afwijkt (alleen bij een versie in een vocabulaire). Een dekkingnaam die geen vorm
  van het model is en die uchar niet gegenereerd noemt, geeft een `IllegalStateException` met de naam (een 500 met de
  zin in de log): dan klopt uchars tabel niet.
- **Tekst.** `docs/ENGINE.md` noemt beide sleutels, de vormsleutels en dat niets uit een naam wordt gelezen.

### Het JSON-contract (toevoegingen)

Per element van `vocabulaires.elementen` dat uchar zelf maakte: `gegenereerdUit`. Per dekkingnaam die geen element
is: een ingang in `gegenereerd`. Uit de analyse van s01, ingekort (`commentaar` afgekapt):

```json
"vocabulaires": {"elementen": [
  {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "canoniek": "maxdatum",
   "benamingen": {"cgm": "Datum einde basisuitkering WW bij ontslag", "handboek": null},
   "regel": 127, "commentaar": ["De maxdatum van de lopende cyclus, met de verschuivingen van eerdere cycli (O3). …", "…"]},
  {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "canoniek": "opgegeven maxdatum",
   "benamingen": {"cgm": null, "handboek": null},
   "gegenereerdUit": {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "naam": "maxdatum",
                      "vorm": "opgave-boven-berekening"}},
  {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "canoniek": "berekende maxdatum",
   "benamingen": {"cgm": null, "handboek": null},
   "gegenereerdUit": {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "naam": "maxdatum",
                      "vorm": "opgave-boven-berekening"}},
  {"soort": "KENMERK", "eigenaar": "RechtOpUitkeringWwBijOntslag", "canoniek": "afwijkende opgave maxdatum",
   "benamingen": {"cgm": null, "handboek": null},
   "gegenereerdUit": {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "naam": "maxdatum",
                      "vorm": "opgave-boven-berekening"}},
  {"soort": "VORM", "eigenaar": null,
   "canoniek": "herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag",
   "benamingen": {"cgm": null, "handboek": null},
   "gegenereerdUit": {"soort": "VORM", "eigenaar": null, "naam": "herlevingstermijn art. 21 lid 3 en 5",
                      "vorm": "uitzondering-voor-subtype"}}]},
"dekking": [{"soort": "defaults", "naam": "defaults A:RechtOpUitkeringWwBijOntslag.maxdatum", "verdict": "LOWERED"}],
"gegenereerd": [
  {"naam": "defaults A:RechtOpUitkeringWwBijOntslag.maxdatum", "soort": "defaults",
   "gegenereerdUit": {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "naam": "maxdatum",
                      "vorm": "defaultsstapel"}},
  {"naam": "defaults A:RechtOpUitkeringWwBijOntslag.beëindigingsdatum", "soort": "defaults",
   "gegenereerdUit": {"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkering", "naam": "beëindigingsdatum",
                      "vorm": "defaultsstapel"}}]
```

De trace van s01-v01 noemt `{"regel": "defaults A:RechtOpUitkeringWwBijOntslag.maxdatum", "labels": ["R1"], "aantal":
3}`; die naam staat letterlijk in `gegenereerd`. Een versie in een vocabulaire (uit de test, niet in s01):
`{"naam": "R_GEWICHT #2", "naamCanoniek": "gewicht #2", "soort": "regel", "gegenereerdUit": {"soort": "VORM",
"eigenaar": null, "naam": "gewicht", "vorm": "versie"}}`.

### Getoetst op echte invoer

Het s01-document is samengesteld zoals de vorige kaarten deden: de 13 bestanden van `v2/fundament/rules` en de 20 van
`v2/herleving-ww/rules` (regelspraak-rules `a1c094a`), gesorteerd, plus `scenariodocs/s01.rgs`, met `"\n"` ertussen;
1 717 regels, bytegelijk aan het document van de vorige kaart. Getoetst tegen `PLAYGROUND=true bin/regelspraak serve
herleving.dienst.json <fundament> <rules> --poort 18494`, met de pin op `e90ab2d4` (de code van `99f614b8`; die
commit raakt alleen uchars `backlog/`).
- 268 elementen: 225 met `regel`, 43 met `gegenereerdUit`, geen met beide of geen van beide. De 43 zijn de zes
  elementen van elk van de zeven markeringen (`beëindigingsdatum` op RechtOpUitkering, `maxdatum` op
  RechtOpUitkeringWwBijOntslag, `herlevingsdatum` op RechtOpUitkeringPeriode, `datum aanvang eindigingsgrond recht op
  uitkering` en `einddatum-van-een-eindigingsgrond` op Eindigingsgrond, `uitkeringsduur` op Uitkeringsduur, `bedrag
  maandloon` op Maandloon) en de groep `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag` uit
  `herlevingstermijn art. 21 lid 3 en 5`. Dat zijn dezelfde 43 die op de vorige kaart geen `regel` hadden.
- `uitvoerbaar`, 74 namen in de dekking: 48 vormen van het model en 26 stapels in `gegenereerd`, elk met het lid
  zoals gedeclareerd. Een stapel op een subtype noemt het type dat het lid declareert, bijvoorbeeld `defaults
  A:RechtOpUitkeringWwBijOntslag.beëindigingsdatum` uit RechtOpUitkering, en `defaults
  A:RechtOpUitkeringWwBijOntslag.berekende beëindigingsdatum` uit het gegenereerde `berekende beëindigingsdatum` van
  RechtOpUitkering (dat zelf weer `gegenereerdUit` heeft).
- s01-v01 en s01-v02 PASS, elk 36 regels in de trace en 74 in de dekking.
- Zonder `gegenereerdUit` en `gegenereerd` is de JSON gelijk aan de analyse van s01 van de vorige kaart (toolchain
  `74d10b9`, uchar `c7191c58`): elke sleutel van de wortel heeft dezelfde waarde, en de wortel heeft dezelfde sleutels
  in dezelfde volgorde, canoniek en in `?vocabulaire=cgm`. Twee
  analyses geven bytegelijke JSON. In `cgm` blijft `gegenereerdUit` canoniek en hebben de stapels hun naam.

### Testwijzigingen

- Nieuw: `service/…/http/PlaygroundGegenereerdUitTest` (5 tests, via `Playground.analyseer` en de pakketfuncties):
  - een bron met de vormen van herleving-ww v2: de markering op `maxdatum` van RechtOpUitkeringWwBijOntslag (een
    subtype van een subtype) en op `herlevingsdatum` van RechtOpUitkeringPeriode, een tweede `maxdatum` op
    Uitkeringsduur zonder markering, een basisregel `herlevingstermijn` op Recht op uitkering WW met een `Afwijking van`
    voor het subtype, en een geschreven attribuut en regel `berekende einddatum`: de dertien gegenereerde elementen met
    hun herkomst, de volgorde van de sleutels, geen `regel` op een gegenereerd element, en geen `gegenereerdUit` op de
    geschreven namen;
  - op dezelfde bron de vier stapels in `gegenereerd`, waarvan `defaults A:RechtOpUitkeringWwBijOntslag.herlevingstermijn`
    uit het lid zoals gedeclareerd op Recht op uitkering WW, en elke dekkingnaam precies één keer een element of
    gegenereerd;
  - een regel met twee versies in een vocabulaire `cgm`: `R_GEWICHT #1` en `#2` met `naamCanoniek` `gewicht #1` en
    `#2`, de herkomst canoniek;
  - elke golden van uchar met een `bron.rgs` (125; 122 bouwt de exec-model-tak): elk element heeft `regel` of
    `gegenereerdUit` en nooit beide, en elke dekkingnaam is een element of gegenereerd; vloeren 1648 elementen, 71
    gegenereerd, 122 gebouwd, 39 stapels (gemeten 2026-10-15);
  - zonder markering en zonder afwijking geen herkomst en een lege `gegenereerd`.
- Rood zonder de wijziging in `Playground` (uchar op `e90ab2d4`): de klasse compileert niet (`cannot find symbol:
  method gegenereerd(Build, RegelSpraakModel, Naamgeving)`).
- Geen bestaand testbestand gewijzigd.

De v2-regelset zelf staat niet in de test: hij staat in een andere repository dan deze publieke distributie. De test
heeft daarom een eigen bron met de vormen van v2, en de echte v2-regelset is getoetst tegen de draaiende playground
(hieronder).

### Acceptatie

- (a) Gehaald op s01, zie "Getoetst op echte invoer".
- (b) `elke_golden_heeft_per_element_een_declaratie_of_een_herkomst`; op s01 ook (268 elementen, 225 met `regel`, 43
  met `gegenereerdUit`, geen met beide of geen van beide).
- (c) Eén nieuwe testklasse; geen bestaand testbestand gewijzigd.
- (d) `docs/ENGINE.md` noemt de sleutels. `make gates` groen op `fa59b9b` (7 stappen in 8 min 31 s):
  - pin (uchar op `99f614b8`);
  - reactor (5 min 28 s): 1578 tests in uchars backend (was 1574, plus de 4 van uchars kaart), 129 in toolchain, 269
    in service (was 264, plus de 5 nieuwe; vloer 235) en 47 in service-quarkus, geen skip;
  - tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, en 4 getuigenissen;
  - replay: 225 goldens met 599 cases (pin 225);
  - manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
  - image `beslisservice:dev`;
  - kind-smoke: H2, H3 en H4 PASS.

### Open

- Bij een scenario met een scope op een document dat als geheel niet uitvoerbaar is, speelt de scenariorunner een
  kleiner document, maar staat er geen `gegenereerd` (die volgt de dekking van het hele document). De namen van de
  dekking en de trace van zo'n scenario zijn dan niet op te zoeken. `ScenarioRunner.Uitgelegd` geeft de build van het
  scenario niet door; dat zou een volgende kaart zijn.
- De stapel staat in `gegenereerd` met het lid als bron; het type waarop de stapel staat (`RechtOpUitkeringWwBijOntslag`
  in `defaults A:RechtOpUitkeringWwBijOntslag.beëindigingsdatum`, met het lid op RechtOpUitkering) staat alleen in de
  naam. Wil de werkbank het tonen, dan vraagt dat een sleutel `eigenaar` op de ingang, uit uchars `Stapel`.
- De namen in het fixpunt die geen vorm zijn (de sleutel van een defaultsconflict, de relatie bij een tweede vulling)
  hebben geen herkomst; uchar geeft er geen.

### Commits

`26dd067` kaart · `4a11fdf` claim · `8dcea4b` pin · `44dd8a8` `gegenereerdUit` en `gegenereerd`, met de nieuwe
testklasse · `fa59b9b` tekst · deze Result en naar `done/`.
