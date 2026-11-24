# quantifier-filter-in-explanation: de uitleg van een kwantorvouw met een open filter, exact

*Opdracht van de eigenaar, 2026-11-24: vervolgpunt van `one-empty-in-service`. Volgt op uchar's kaart
`contributor-filter-verdict` (zelfde pin-bump). Vóór de bouw gelezen door een extern model (xhigh), de punten hieronder
verwerkt. Nog niet begonnen.*

Goal: de uitleg van een kwantorvouw waarvan het filter op een lid een open waarde leest, noemt de leden en dragers weer,
met per lid of zijn filter vaststaat, uit wat uchar's lezing zegt (`ConditionReading.Contributor` met het filteroordeel),
zonder eigen oordeel van de toolchain.

Acceptance: `OpenReads.reads` (de benadering van het filter over knopen en koppelingen) vervalt; de twee gevallen van de
review van `one-empty-in-service` ("alle" met V1 filter waar/matrix waar en V2 filter "A of B" met A waar en B
open/matrix onwaar; "ten minste 2") en een lid met een onbepaald filter geven een ware uitleg met leden, in
`OpenExplanationFormsHttpTest` en `OpenExplanationHttpTest`; de OpenAPI-beschrijving van `MEMBERS`, README en
`docs/ENGINE.md` zeggen het; `make gates` groen op de nieuwe pin.

May touch: `toolchain/.../engine/OpenReads.java`, `Verbaliser.java`, `Part.java`, `service/.../OpenApiEmitter.java`, de
tests en openapi-goldens die het raakt, README, `docs/ENGINE.md`, de pin `uchar`, deze kaart.

Out of scope: de lezing van een vouw over een set; andere uitlegvormen.

## Aanleiding

Sinds `one-empty-in-service` noemt de uitleg bij een open filter alleen het oordeel van de vouw, omdat de toolchain zonder
het filteroordeel per lid een onware zin kon schrijven (": er zijn 2 leden", de verkeerde dragers). uchar's kaart
`contributor-filter-verdict` geeft dat oordeel mee; daarmee kan de uitleg weer exact zijn.

## Uit de lezing vóór de bouw (extern model, nagerekend)

- **Het principe van de zinnen.** Een lid met een onbepaald filter is een lid waarvan niet vaststaat of het meetelt. De
  uitleg noemt het apart ("niet vast of V2 meetelt") en beweert nergens een totaal of een aantal dat niet vaststaat:
  geen "voor alle 2" als V2 een onbepaald filter heeft, geen "het enige lid", geen "1 van de 2", geen "er zijn 2
  leden". Een drager van het oordeel is alleen een lid met een vaststaand waar filter (bij "alle" die niet geldt: een
  lid met een vaststaand filter en een onware matrix). Bouw dat voor alle vijf kwantoren, in een vaststaande en een
  onbepaalde vouw, ook met filter én matrix onbepaald, in `members`, `carriers` en `openMembers` van `Verbaliser`.
- **`MEMBERS`.** Leg vast wat `members`, `holding` en `unsettled` tellen als een lid een onbepaald filter heeft (alleen
  leden met een vaststaand filter tellen in `members`), en geef het aantal leden met een onbepaald filter een eigen veld
  (bijvoorbeeld `filterUnsettled`): schema, OpenAPI en de goldens die het raakt.
- **Opruimen.** Met `OpenReads.reads` worden ook de velden `links` en `order` en de hulpmethoden `read`, `add`,
  `overASet`, `range`, `reached`, `walk` en `once` dood: weg.
- **Tests** naast de twee reviewgevallen: een vaststaande "alle" met een lid met onbepaald filter en ware matrix (geen
  "voor alle 2"); een "alle" die niet geldt met V1 (filter waar, matrix onwaar) en V2 (filter onbepaald, matrix onwaar):
  alleen V1 draagt; een vaststaand onwaar samengesteld filter met een open deel (het lid verschijnt niet).
