# marker-on-value-part: een marker in de uitleg is als marker te herkennen

*Opgesteld door de werkbank-sessie op 2026-11-06, na `writer-holds-on-no-day` (toolchain `eb912c57d`, uchar `74119a23e`).*

## Aanleiding

Sinds `absence-markers-in-service` schrijft de uitleg de waarde van een attribuut met afwezigheidsmarkers als haar
payload en een marker bij zijn naam (`docs/ENGINE.md`, "The parts of an explanation", de rij `VALUE`). Het blad `VALUE`
van een marker draagt geen meta: `Verbaliser.render` en `renderRead` maken van de `ConstructorValue` een string
(`c.constructor()`) voordat `value(…)` het blad maakt, en dat blad kent alleen `empty: true` en `timeline: true`. Een
afnemer van `explanationParts` kan een marker daardoor alleen aan zijn tekst herkennen, en die tekst is ook die van een
enumeratiewaarde of een literal met dezelfde spelling (een domein `'onbekend' | 'getaxeerd'`).

De werkbank leest geen tekst: zij kleurt en omkadert per soort stuk en per meta. Zij toont een lege waarde
(`empty: true`) in een gestreept kader, en wil een marker tonen als een gevulde waarde zonder getal: in de kleur van
een waarde, met een doorgetrokken kader, omdat een marker beslist zoals een waarde en leeg niets beslist. In haar
objectboom doet zij dat al met `{"marker": …}` uit de situatie en het fixpunt; in de uitleg kan zij het pas als het blad
het zegt.

## Goal

- Elk blad `VALUE` dat een marker schrijft, draagt `marker: "<naam>"`, de naam in de gedeclareerde spelling: in de kop
  van een zin ("V1 heeft als taxatie onbekend volgens …"), als waarde die een voorwaarde of berekening las ("zijn
  taxatie (niet van toepassing) een waarde heeft"), op een cel ("V1 heeft als gewicht in voorjaar onbekend …"), en als
  literal in een vergelijking ("is gelijk aan onbekend") waar die een eigen blad `VALUE` is (de rij `VALUE` noemt "a
  literal"); staat zij daar in een `QUOTE` van de regeltekst, zeg dat in het Result en laat het zo.
- Een blad met de payload van zo'n attribuut ("300,00") en elk ander blad `VALUE` draagt geen `marker`.
- De tekst verandert niet: `explanation` en de tekst van elk stuk blijven byte-gelijk.
- Een tijdlijn (`timeline: true`) is één blad met al haar perioden in de tekst ("onbekend van 01-01-2025 tot
  01-03-2025, …"); daar een periode met een marker te markeren vraagt een andere vorm van `VALUE` (per periode een
  stuk). Dat hoort niet bij deze kaart. Tel in het Result hoe vaak een tijdlijn met een marker voorkomt in uchar's
  goldens die de replay speelt, zodat de werkbank kan beslissen of zij die vorm wil vragen.
- README en `docs/ENGINE.md` als huidige stand: de rij `VALUE` noemt `marker`.

## Acceptance

- (a) Rood eerst: een test die op de uitleg van uchar's golden `jutterswet-afwezigheidsmarkers` per blad `VALUE` met
  een marker `marker` verwacht, en op het blad van een payload en van een enumeratiewaarde geen `marker`, faalt op main.
- (b) Over de goldens die de replay speelt is `explanation` byte-gelijk met main, en elk stuk heeft dezelfde `kind` en
  `text`; alleen `marker` komt erbij.
- (c) `make gates` groen.

May touch: `toolchain/` (`Verbaliser`, `Part`), `service/` waar de stukken naar JSON gaan, README, `docs/ENGINE.md`,
kaartverplaatsing.

## Omvang

Klein.

## Result

*Hoofdsessie, 2026-11-06, direct op `main` van de toolchain, samen met `creation-value-wording` (zie het Result daar
voor de vergelijkingsrun en de bijvangst in het fixpunt). Pin ongewijzigd (uchar `74119a23e`).*

### Commits

| hash | inhoud |
|---|---|
| `dd985d80e` | beide kaarten naar `doing/` |
| `99ac2e411` | Uitleg: een marker draagt `marker` op zijn `VALUE` (`Verbaliser`, `Part`, `OpenApiEmitter`, `ExplanationMarkerPartsTest`, OpenAPI-goldens, README, `docs/ENGINE.md`) |
| (deze) | Result; de kaart naar `done/` |

### Wat er veranderde

- `Verbaliser.value` en `Verbaliser.reading` nemen naast de tekst de waarde zoals de motor haar gaf; `markerOf` maakt
  daaruit `marker`, de naam van de constructor, waar die waarde een `ConstructorValue` zonder payload is (uchars
  RUNTIME-SEMANTICS ch. 9.5). De beslissing leest dus de waarde, nooit de tekst. Elke plaats die een scalair blad
  `VALUE` maakt, geeft die waarde door: de kop van een zin (`writtenValue`, de waarde die de regel schreef, ook op een
  cel), de beslissing van een defaults-groep, de waarde van een verdrongen variant, de invoerwaarde of de bij de
  objectcreatie toegekende waarde die bleef staan (scalair en per cel), een gelezen operand (`READING`), de literal van
  een vergelijking, de bijdrage van een lid van een vouw, en de waarde vóór een afronding of begrenzing.
- De literal van een vergelijking (`"is gelijk aan onbekend"`) is een eigen blad `VALUE`, geen `QUOTE`:
  `operandProse` schrijft een `Construct` zonder argument als zijn waarde, één keer. Hij draagt dus `marker`.
- Geen `marker`: het blad van een payload (`"300,00"`, de waarde `waarde(…)`), een enumeratiewaarde met dezelfde
  spelling, en elk blad met `timeline: true` (`periodValue`, de zinnen van een tijdlijndoel, `heldInPeriods`).
- `Part.Kind.VALUE`, de beschrijving van `ExplanationPart` in de OpenAPI en zijn property `marker` (string), de rij
  `VALUE` in `docs/ENGINE.md` en de README noemen `marker`.

### Acceptatie

- **(a) Rood eerst.** `ExplanationMarkerPartsTest` (drie tests, op uchars golden `jutterswet-afwezigheidsmarkers`,
  `source.rgs` met `scenario.rgs`): elk blad `VALUE` buiten een tijdlijn met de tekst van een marker draagt die naam
  als `marker` en geen ander blad draagt er een; de kop (`V1 heeft als taxatie onbekend volgens …`), een gelezen waarde
  (`zijn taxatie (niet van toepassing) een waarde heeft`) en de literal (`is gelijk aan onbekend`) dragen hem, de
  payload `300,00` niet; en een eigen document met het domein `'onbekend' | 'getaxeerd'` geeft het blad van de
  enumeratiewaarde `onbekend` geen `marker`. Op `4285649a5` (zonder de verandering) faalden de eerste twee; de derde
  slaagde daar ook, zoals hoort.
- **(b)** Over de 347 goldens van de replay-poort (`werk/creation-value-and-marker/`, zie het Result van
  `creation-value-wording`): `explanation` is byte-gelijk op de zinsdelen van die kaart na, elk stuk heeft dezelfde
  `kind` en `text` (op diezelfde zinsdelen na), en alleen `marker` komt erbij: 76 bladen in drie goldens
  (`jutterswet-afwezigheidsmarkers` 51, `-afwezigheidsmarkers-cel` 12, `-afwezigheidsmarkers-creatie` 13), elk een
  `VALUE` zonder `timeline` met als tekst de marker. Er is geen blad `VALUE` buiten een tijdlijn met de tekst van een
  marker zonder `marker`.
- **(c)** `make gates`: zie onder.

### Tijdlijnen met een marker (voor de werkbank)

Over de uitleg van de replay-set (één keer per casebestand, zonder `fixpoint`): 27 bladen `VALUE` met `timeline: true`
hebben een periode met een marker in hun tekst (`onbekend van 01-01-2025 tot 01-03-2025, …`), in drie goldens:
`jutterswet-afwezigheidsmarkers` 14, `jutterswet-afwezigheidsmarkers-kopie` 8 en
`jutterswet-afwezigheidsmarkers-initialisatie` 5. Gemeten met de tekst (een periode die met `onbekend` of `niet van
toepassing` begint, gevolgd door `van`, `vanaf` of `tot`); in deze drie goldens is geen enumeratiewaarde zo gespeld.

### Gates

`make gates` op `ad0a4f20f`, 2026-11-06:

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m23s  testgevallen per module 194/496/54
gates: 3 twins      GROEN      4s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m51s  338 goldens, 964 cases (pin 338)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     35s  decision-service:dev
gates: 7 kind-smoke GROEN   1m16s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 6m05s; de uitvoer staat in out/gates/
```
