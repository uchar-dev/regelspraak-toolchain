# ground-deviation: de grondslag leest een deviatie met haar lagen en de laag die de regelset volgt

*Voorstel vanuit de werkbank (2026-10-23), door de eigenaar doorgegeven aan de toolchain-sessie; tekst van de werkbank,
ongewijzigd op deze kop na (de slug van de titel in het Engels: `ground-deviation`). Vervolg op `explanation-parts`
en `explanation-ground-source`.*

## Aanleiding

"Afwijking" betekende in het werk aan v2 drie dingen: de dialectvorm `Afwijking van …` van uchar (een variant in een
defaults-groep), een vermelding in het afwijkingenregister van de wetsanalyse (in een `// Bron:`-regel als
`afwijking:` of `afwijking, niet gevolgd:`), en een genummerde keuze van v2 tegen de modelkaart. In de Uitleg-tab van
de werkbank staan de eerste twee naast elkaar. De eigenaar besliste (2026-10-23):

- de dialectvorm houdt haar naam;
- een vermelding in het register heet voortaan een **deviatie** (het register: het deviatieregister);
- de `// Bron:`-regel zegt tussen welke lagen de deviatie zit en welke laag de regelset volgt, in plaats van
  "gevolgd" of "niet gevolgd". De impliciete vorm `afwijking:` (= gevolgd) leidde in hww-06 tot een verkeerde
  vermelding: v2 rekent met maandlonen zoals de wet, maar de regel noemde de BRM-afwijking op daglonen als gevolgd.

Het register noemt bij elke vermelding de lagen (`lagen`: `wet→hb`, `wet→brm`, `hb→brm`, `intern brm`, of meer paren
tegelijk). De nieuwe vorm in de `// Bron:`-regel:

```
deviatie wet/hb, volgt hb: het-overlijden-als-alsof-uitsluitingsgrond
deviatie wet/brm, volgt wet: de-herlevingsgrens-op-daglonen
deviatie binnen brm: de-bezwaartermijn-met-drie-getallen
```

De eerste laag van het paar is die waarvan de tweede afwijkt, zoals de pijl in het register. Een deviatie binnen één
laag heeft geen paar en geen gevolgde laag; de toelichting in dezelfde regel zegt welke regel van die laag v2 volgt.

## Doel

- De grammatica van de grondslag (`engine/Ground`, `docs/ENGINE.md` "The grammar of the ground") leest:
  ```
  deviation = "deviatie " layer "/" layer ", volgt " layer ": " slug
            | "deviatie binnen " layer ": " slug                     (only after the lemma head, as now)
  layer     = 1*( lowercase letter )
  ```
  De laagcodes kiest de wetsanalyse (nu `wet`, `hb`, `brm`); de toolchain kent ze niet en toetst ze niet. De oude
  vormen `afwijking: ` en `afwijking, niet gevolgd: ` vallen weg en worden, zoals elk ander segment, een `NOTE`.
- Het stuk `DEVIATION` krijgt als meta `between` (de lagen van het paar, of de ene laag bij "binnen", als lijst) en
  `follows` (de gevolgde laag; afwezig bij "binnen") in plaats van `followed`. De woorden van de vorm zijn `TEXT`, zoals
  nu. De toolchain kiest de precieze sleutels en zegt waarom.
- De parser blijft totaal en structureel: of de gevolgde laag in het paar zit en of paar en slug in het register
  staan, toetst de lint van de werkbank (`lint-benamingen`), niet de toolchain. Wil de toolchain een `follows` buiten
  het paar toch weigeren of als `NOTE` lezen, dan zegt de kaart waarom.
- `explanation` en de tekst van de stukken blijven bytegelijk; alleen de soort en meta van de stukken veranderen.
  OpenAPI (`ExplanationPart`), `ContractNamesInMessagesTest`, `docs/ENGINE.md` (de tabel van de stukken en de
  grammatica) bij.

## Acceptatie

- (a) Een eigen klein model met de drie vormen hierboven, een `follows` buiten het paar, en de twee oude vormen: de
  stukken zoals de grammatica zegt, de oude vormen als `NOTE`.
- (b) Op de vijf service-verzoeken van herleving-WW v2 en s01 (regels nog met de oude vormen): `explanation` bytegelijk;
  elk `DEVIATION`-stuk van nu is een `NOTE` geworden, met dezelfde tekst; de telling in de Result.
- (c) `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

De pin-bump samen met de omzetting: het deviatieregister in de wetsanalyse, de 23 vermeldingen in de Bron-regels van
v2 naar de nieuwe vorm, B20 in de modelkaart, de lint (paar in het register, gevolgde laag in het paar), `ontleedBron`,
de Uitleg-tab en de naam-popover. Tot die pin-bump ziet de werkbank de oude vormen als `DEVIATION`, zoals nu.

## Result (2026-10-23)

Tak `card/ground-deviation` in de worktree `wt-tc/ground-deviation`, vanaf toolchain main `23f560b2`, pin uchar
`a6eed482` (ongewijzigd). Gebouwd en geland door de hoofdsessie; werkmap `werk/toolchain-2026-10-23/ground-deviation/`.

- `f6cc3eb0` de kaart naar `doing/`.
- `c7bab59f` de grondslag leest een deviatie met haar lagen: `engine/Ground`, `Part`, `ExplanationParts`, OpenAPI,
  `docs/ENGINE.md`, de tests.

### De sleutels

| segment (na de lemmakop) | stukken |
|---|---|
| `deviatie wet/hb, volgt hb: x` | `TEXT` "deviatie wet/hb, volgt hb: ", `DEVIATION` "x" met `between: ["wet", "hb"]`, `follows: "hb"` |
| `deviatie binnen brm: x` | `TEXT` "deviatie binnen brm: ", `DEVIATION` "x" met `between: ["brm"]` |
| `afwijking: x`, `afwijking, niet gevolgd: x` | `NOTE` met het hele segment |

Waarom zo:

1. **`between` is één sleutel voor beide vormen, een lijst.** Een lezer vindt de lagen van elke deviatie op één plek
   en ziet aan de lengte welke vorm het is; het register noemt de lagen per vermelding ook als één veld (`lagen`). Twee
   sleutels voor het paar en een derde voor "binnen" zouden drie plaatsen geven voor één gegeven. De volgorde is die van
   het register: de eerste laag is die waarvan de tweede afwijkt.
2. **`follows` ontbreekt bij "binnen"** (geen null), zoals `variant`, `paragraph` en `part` ontbreken waar de bron ze
   niet noemt. Volgorde `between`, `follows`: eerst waar de deviatie zit, dan wat de regelset kiest.
3. **Meta draagt nu ook een lijst van teksten.** `Part` nam alleen teksten, booleans, gehele getallen en null; het
   neemt nu ook een lijst van teksten (onveranderlijk gekopieerd) en weigert elke andere lijst luid, zoals eerder elk
   ander type. `ExplanationParts` schrijft haar als JSON-array; OpenAPI (`ExplanationPart`): `between` een array van
   strings, `follows` een string, `followed` weg.
4. **De parser blijft totaal en structureel.** Een laag is `\p{Ll}+`, zoals de grammatica van de kaart; de toolchain
   kent de codes niet. Een `follows` buiten het paar is een `DEVIATION` zoals geschreven (`deviatie hb/brm, volgt wet:
   x` geeft `between: ["hb", "brm"]`, `follows: "wet"`); een toets daarop zou een deel van de lint van de werkbank naar
   de toolchain halen, en de kaart legt die toets bij de lint. Zo ook `binnen` als eerste laag van een paar
   (`deviatie binnen/hb, volgt hb: x`).
5. **Alleen na de lemmakop**, zoals nu; ervoor is de nieuwe vorm een `NOTE`. Buiten de grammatica en dus `NOTE`: de
   oude vormen, een laag met een hoofdletter, een paar zonder `, volgt`, één laag met `, volgt`, `binnen` met een
   gevolgde laag, een vorm zonder slug.

### Rood eerst (a)

- `GroundTest`: `every_segment_pattern` met de twee nieuwe vormen in plaats van de oude;
  `a_deviation_names_its_layers_and_the_parser_does_not_judge_them` (een paar, een `follows` buiten het paar, `binnen`
  als laag); `the_old_forms_and_a_deviation_off_the_grammar_are_notes` (de twee oude vormen en vijf vormen buiten de
  grammatica, alle zeven `NOTE`). In `a_near_anchor_…` staat de deviatie vóór de lemmakop nu in de nieuwe vorm (nog
  steeds een `NOTE`).
- `GroundDeviationServiceTest` (nieuw): een eigen model, de regel 'gevestigd' met vier `// Bron:`-regels (de drie
  vormen van de kaart, een `follows` buiten het paar, de twee oude vormen) en de groep 'premie', waarvan de
  uitzondering 'jeugdpremie' een deviatie in de annotatie van haar blok draagt. De test pint de twee zinnen, de hele
  `GROUND` van beide (met `between` als JSON-array), en dat elke grondslag zijn beslisser in het document vindt.

Op de kaartbasis met alleen de tests: `GroundTest` 3 van 8 rood, `ExplanationPartsServiceTest` 2 van 4 en
`PlaygroundExplanationPartsTest` rood (het gedeelde model draagt de nieuwe vormen, de basis leest ze als `NOTE`, dus
mist `DEVIATION`) (`logs/red.log`); `GroundDeviationServiceTest`: rood op de stukken van `GROUND` met de zinnen gelijk (eerst rood op een ontbrekende scope in de descriptor; na de toevoeging opnieuw op de kaartbasis gedraaid, met de main-bronnen van `f6cc3eb0`: `logs/red-service.log`). Groen na de wijziging (`logs/green-1.log`).

Aangepast, met reden (deze kaart verandert wat ze pinden; geen toets verzwakt):

- `ExplanationPartsServiceTest`: de twee annotaties van zijn model in de nieuwe vorm (`deviatie wet/hb, volgt wet:
  oude-jutter`, `deviatie binnen hb: oude-premie`) en de zinnen en stukken die ze pinden; zo houden die test en
  `PlaygroundExplanationPartsTest`, die hetzelfde model leest en elke soort eist, een `DEVIATION`.
- De vier OpenAPI-goldens, overgeschreven uit `target/openapi/`: alleen de beschrijving van `ExplanationPart`, `between`
  en `follows` erbij, `followed` weg.

### Meting (b) op v2 en s01

Meetmap `meting/`: `measure.sh` en de zes verzoeken van de vorige kaart (`explanation-ground-source`), de momentopname
`werk/defaults-decider/v2/` (regels `a7a5bdf`, met de oude vormen: 27 segmenten, alle na de lemmakop). Jar vóór
(`before-jar/`, code van `23f560b2`) en na (`after-jar/`, `c7bab59f`); `compare.py`, `compare.log`.

- Zonder vlag en met `explanation=true`: per verzoek bytegelijk.
- Met `explanationParts=true`: `explanation` bytegelijk; het antwoord na is gelijk aan dat van vóór waarin elke
  `DEVIATION`, met de lijm ervoor die op "afwijking: " of "afwijking, niet gevolgd: " eindigt, één `NOTE` is van die
  woorden en de slug; elke zin is haar stukken.
- 316 zinnen; 63 `DEVIATION`-stukken vóór (41 `followed: true`, 22 `followed: false`; detentie 11, herkrijging 11,
  inkomen 11, intrekking 7, restant 11, s01 12), 63 `NOTE` geworden, 0 `DEVIATION` na. PASS.

### Gates

`make gates` op `c7bab59f` met `TAG/CLUSTER=ground-deviation`: GROEN, 7 stappen in 11m19s (reactor 1654/172/375/53: toolchain +9 t.o.v. 163, dienst +1 nieuwe klasse; replay 244 goldens, 656 cases, pin 244; manifest 29 ok, 3 uitgesloten; image en kind-smoke groen; `logs/gates.log`). De landingscommit voegt een regel toe aan `README.md` (de lijst met contractwijzigingen).

### Wat open bleef

- De werkbank: de pin-bump met de omzetting (de paragraaf "Daarna" van de kaart). Tot die bump ziet de werkbank de oude
  vormen als `DEVIATION`; na de bump zijn ze een `NOTE` tot de Bron-regels van v2 zijn omgezet.
