# playground-rol-geeft-feittype: elke rol in de situatie van de playground noemt haar feittype

*Vastgelegd op 2026-10-15. Volgt op `20261015-playground-geeft-commentaar-en-feittype` ("Punt 2: het feittype van
een rol in de situatie"), dat op uchar wachtte.*

## Aanleiding

De werkbank wil de relaties tussen de objecten van een scenariosituatie tonen, met het feittype per relatie. In s01
van herleving-ww (v2) hangen U1, D1, I4–I8, G1, A1 en P1 via de rol `recht` aan R1, elk via een ander feittype.
`situatie.objecten[].rollen[]` geeft `rol`, `doeltype`, `labels`, `meervoudig` en `regel`, maar niet het feittype,
omdat uchars `ResolvedScenario.RolWaarde` het niet had. De uchar-kaart `scenario-rol-draagt-feittype` geeft
`RolWaarde` het veld `feittype` (het `Feittype` van het model), gevuld door uchars scenario-resolver.

## Wat er gebeurt

- De submodule `uchar` gaat naar de commit van die uchar-kaart.
- `ScenarioGegevens.situatie` zet bij elke rol `feittype`: de naam van het feittype uit het nieuwe veld, in het
  gekozen vocabulaire, met `feittypeCanoniek` ernaast als die naam afwijkt, zoals elke andere naam in de situatie en
  zoals `fixpunt.koppelingen[].feittype`. De bestaande sleutels houden hun vorm.
- `docs/ENGINE.md` noemt de sleutel; `README.md` schrapt het feittype van een rol onder de bekende verliezen.

## Acceptatie

- (a) Op het s01-document (fundament en herleving-ww van v2 plus `scenariodocs/s01.rgs`, zoals de vorige kaart het
  samenstelde) geeft de rol `recht` van U1, D1, I4, G1, A1 en P1 elk het feittype dat `ir.model.factTypes` voor die
  rol en dat type heeft, net als `persoon` van R1 en `dagloon` van M1.
- (b) Nieuw gedrag in een nieuwe testklasse met echte invoer; een bestaand testbestand verandert alleen als het veld
  dat afdwingt, in een eigen commit met de reden in het Result.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `uchar` (de pin), `service/…/http/ScenarioGegevens.java`, een nieuwe testklasse onder
`service/src/test/`, `docs/ENGINE.md`, `README.md`, kaartverplaatsing.

Buiten de kaart: `playground.html` (toont het feittype niet), de werkbank, en de landing en push van de pin: die wachten
tot de uchar-commit op origin staat.

## Result (2026-10-15)

### Wat de kaart opleverde

- **De pin.** `uchar` gaat van `f752a5c9` naar `c7191c58`, de tip van de uchar-branch `card/scenario-rol-draagt-feittype`
  (worktree `wt/scenario-rol-draagt-feittype`). Daartussen: `79f8741b` (alleen PLAN §9), `8c1fe833` kaart, `1050ba83`
  claim, `4c63e449` het veld, `c7191c58` Result en naar `done/`. De commits staan nog niet op origin.
- **Het veld in uchar.** `ResolvedScenario.RolWaarde(String sleutel, Feittype feittype, Objecttype doeltype,
  List<String> doelLabels, boolean meervoudig, int line)`: het `Feittype` van het model waarin uchars
  `ScenarioResolver.rol` de rol vond, zoals `doeltype` het `Objecttype` zelf is.
- **De sleutel.** `ScenarioGegevens.situatie` zet bij elke rol, direct na `rol`, `feittype`: de naam van
  `r.feittype()` via `Naamgeving.feittype`, dus in het gekozen vocabulaire, met `feittypeCanoniek` ernaast als die naam
  afwijkt. `fixpunt.koppelingen[].feittype` gebruikt dezelfde `Naamgeving.feittype`, dus de namen zijn gelijk (punt 3
  van de vorige kaart). In s01 legt geen creatie een koppeling, dus daar is dat niet naast elkaar te zien.
- **Tekst.** `docs/ENGINE.md` noemt `feittype` bij de rollen van de situatie; `README.md` schrapt het feittype van
  een rol onder de bekende verliezen.

### Het JSON-contract (toevoeging)

Per rol in `situatie.objecten[].rollen[]`: `feittype`, en in een vocabulaire waar de naam afwijkt ook
`feittypeCanoniek`. De andere sleutels houden hun vorm. Uit de analyse van s01:

```json
U1 (Uitkeringsduur): {"rol": "recht", "feittype": "RechtOpUitkeringWwBijOntslagLeidtTotDuurUitkeringWw", "doeltype": "RechtOpUitkeringWwBijOntslag", "labels": ["R1"], "meervoudig": false, "regel": 1617}
I4 (Inkomen in-een-kalendermaand): {"rol": "recht", "feittype": "inkomen in-een-kalendermaand", "doeltype": "Recht op uitkering WW", "labels": ["R1"], "meervoudig": false, "regel": 1626}
U1 in ?vocabulaire=cgm: {"rol": "recht", "feittype": "Recht op uitkering WW bij ontslag leidt tot duur uitkering WW", "feittypeCanoniek": "RechtOpUitkeringWwBijOntslagLeidtTotDuurUitkeringWw", "doeltype": "Recht op uitkering WW bij ontslag", "doeltypeCanoniek": "RechtOpUitkeringWwBijOntslag", "labels": ["R1"], "meervoudig": false, "regel": 1617}
```

### Getoetst op s01

Het document is samengesteld zoals de vorige kaart deed: de 13 bestanden van `v2/fundament/rules` en de 20 van
`v2/herleving-ww/rules` (regelspraak-rules `a1c094a`, de submodule van de werkbank) plus `scenariodocs/s01.rgs`, met
`"\n"` ertussen; 1 717 regels, bytegelijk aan het document van de vorige kaart. Getoetst tegen `PLAYGROUND=true
bin/regelspraak serve herleving.dienst.json <fundament> <rules> --poort 18394`, op de pin `4c63e449` en opnieuw op
`c7191c58` (dezelfde JSON).

| object | type | rol | feittype |
|---|---|---|---|
| R1 | RechtOpUitkeringWwBijOntslag | persoon | NatuurlijkPersoonHeeftRechtOpUitkering |
| U1 | Uitkeringsduur | recht | RechtOpUitkeringWwBijOntslagLeidtTotDuurUitkeringWw |
| D1 | Dagloon | recht | RechtOpUitkeringKentDagloon |
| M1 | Maandloon | dagloon | DagloonWordtAfgeleidUitMaandloon |
| I4–I8 | Inkomen in-een-kalendermaand | recht | inkomen in-een-kalendermaand |
| G1 | Eindigingsgrond | recht | RechtOpUitkeringHeeftEindigingsgrondRechtOpUitkering |
| A1 | AanvraagUitkeringWwBijOntslag | recht | AanvraagUitkeringLeidtTotRechtOpUitkering |
| P1 | RechtOpUitkeringPeriode | recht | RechtOpUitkeringHeeftRechtOpUitkeringPeriode |

- Vergeleken met `ir.model.factTypes` van dezelfde analyse: voor alle 24 rollen (12 per scenario, 2 scenario's, beide
  PASS) heeft het genoemde feittype een rol met die naam of dat meervoud en dat `objectType` als `doeltype`, en vult
  het type van het object (of een supertype langs `supertype` in `ir.model.objectTypes`) de andere rol.
- Zonder `feittype` is de JSON gelijk aan de analyse van s01 van de vorige kaart (toolchain `9022926`, uchar
  `f752a5c9`), op alle sleutels. Twee analyses geven bytegelijke JSON.
- In `?vocabulaire=cgm` krijgen de rollen de cgm-naam van hun feittype met `feittypeCanoniek` ernaast; `inkomen
  in-een-kalendermaand` heeft geen cgm-benaming en blijft canoniek, zonder `feittypeCanoniek`.

### Testwijzigingen

- Nieuw: `service/…/http/PlaygroundRolFeittypeTest` (3 tests, via `Playground.analyseer`):
  - een bron waarin de rol `vergunning` in drie feittypen staat: per object het feittype van elke rol, beide kanten
    van een feittype, een wederkerig feittype, elk feittype in `ir.model.factTypes`, geen `feittypeCanoniek` in de
    canonieke keuze, en de volgorde van de sleutels;
  - uchars golden `jutterswet-vocabulaires` met het scenario van de playground: het feittype in `cgm`
    (`VONDST_AANMELDING`, met `feittypeCanoniek`), in `handboek` en canoniek;
  - elke scenario-golden van uchar (30 mappen, `bron.rgs` en `scenario.rgs`): elke rol van elke situatie noemt een
    feittype uit `ir.model.factTypes` met die rol en dat doeltype; 146 rollen, vloer 100.
- Rood zonder de wijziging in `ScenarioGegevens` (uchar op `4c63e449`): 1 failure en 2 errors.
- Geen bestaand testbestand gewijzigd.

### Acceptatie

- (a) Gehaald, zie "Getoetst op s01".
- (b) Eén nieuwe testklasse; geen bestaand testbestand gewijzigd.
- (c) `make gates` groen op `74d10b9` (7 stappen in 8 min 17 s):
  - pin;
  - reactor (5 min 18 s): 1574 tests in uchars backend, 129 in toolchain, 264 in service (was 261, plus de 3
    nieuwe) en 47 in service-quarkus, geen skip;
  - tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, en 4 getuigenissen;
  - replay: 225 goldens met 599 cases (pin 225);
  - manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
  - image `beslisservice:dev`;
  - kind-smoke: H2, H3 en H4 PASS.

### Open

- **Landing en push.** De pin wijst naar `c7191c58`, die alleen op de uchar-branch `card/scenario-rol-draagt-feittype`
  staat. Deze branch landt pas als die uchar-kaart op origin staat. Wordt de uchar-branch bij de landing gerebased,
  dan verandert de commit en moet de pin opnieuw, met `make gates` erna.
- `playground.html` toont het feittype niet; de werkbank leest het contract en wordt elders aangepast.
- `Naamgeving.rolVanaf` zoekt de vocabulairenaam van een rol nog op naam en bereikbaarheid over alle feittypen, en
  laat een naam die meer rollen noemt zoals geschreven. Met het feittype uit uchar kan dat per feittype; dat is hier
  niet veranderd.

### Commits

`3048f01` kaart · `69d7901` claim · `af2f2e0` de pin naar `c7191c58`, `feittype` bij elke rol, de nieuwe testklasse ·
`74d10b9` tekst · deze Result en naar `done/`.
