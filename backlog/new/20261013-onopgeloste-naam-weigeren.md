# onopgeloste-naam-weigeren: `check` en `serve` weigeren een bron met een onopgeloste naam

*Vastgelegd door de hoofdsessie op 2026-10-13, na het besluit van de eigenaar op het open punt van de kaart
`20261010-sessie-via-de-ingang-van-uchar` ("ja, moet inderdaad al bij check of serve geweigerd worden").*

## Aanleiding

Een naam die de resolver van uchar niet aan een declaratie kan binden, laat zij als `Unresolved`-knoop in het IR staan,
met een WARNING. Een voorbeeld: "hij een minderjarig is" bij een bijvoeglijk kenmerk `minderjarig`, waar "hij is
minderjarig" hoort.
- **Vóór de pin op uchar `b536bbc9`** weigerde de oude exec-model-tak zo'n regel bij de bouw. `check` gaf exit 3
  (MARKER), en `serve` startte niet in strikte modus.
- **Sinds die pin** draagt de tak met truth maintenance de regel en faalt die pas bij de evaluatie (WF-044), net als
  ref. `check` geeft exit 0 met een WARNING, de dienst start, en elk verzoek dat de regel bereikt, krijgt een 422
  `REKENFOUT`. Een tikfout in een regel merkt de afnemer zo pas bij een verzoek.

## Wat er gebeurt

`check` en `serve` weigeren een bron, of een IR-document, waarin een `Unresolved`-knoop staat. Ze noemen de naam en de
regel. De weigering hangt aan het IR, niet aan de tekst van een WARNING. Zo geldt ze ook voor een IR-document dat de
dienst als bron krijgt.
- `check`: een exitcode als bij een resolverfout, met de naam, de regel en de plaats.
- `serve`: start niet, ook niet met `--allow-markers`. Een onopgeloste naam is een fout in de bron, geen vorm die uchar
  nog niet draagt.

De semantiek van uchar verandert niet: ref en de backends laten een onopgeloste naam bij de evaluatie falen (WF-044).
Dit is een poort van de distributie.

## Acceptatie

- (a) Rood eerst: `check` en `serve` op een bron met de een-vorm hierboven, en op een IR-document met een
  `Unresolved`-knoop. Eerst exit 0 en een start, daarna een weigering met de naam.
- (b) De getuige in `ScenarioOrakelTest` die de fout bij de evaluatie pint (sinds `ac4c3ac`), en de docs, volgen.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/` (CLI `check`, `UcharBouw` of een eigen IR-toets), `service/` (de start van de dienst),
`service-quarkus/` voor de start, de tests, `README.md` en `docs/ENGINE.md`.
