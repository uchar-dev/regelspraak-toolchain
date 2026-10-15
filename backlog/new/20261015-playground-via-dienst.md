# playground-via-dienst: `POST /playground/analyse?via=dienst` laat de scenario's rekenen door de dienst

*Vastgelegd op 2026-10-15, kaart D van de reeks "beslisservice via dienst" (besluit van de maintainer, werknotitie
`werkbank/werknotities/20261015-beslisservice-en-intake/beslisservice-verkenning.md` en het advies daarnaast,
`fable-advies-beslisservice-architectuur.md` §3 "Eenvoudiger alternatief"). Toolchain `91caaf8`, uchar-pin naar
`a3ac41fe` (de vergelijking publiek, uchar-kaart `vergelijking-publiek`).*

## Aanleiding

Op de eval-pagina van de werkbank moet PASS betekenen: de beslisservice geeft dit antwoord. Nu rekent de playground
een scenario zelf, met uchars scenariolaag over het hele document. De dienst rekent anders: alleen de vormen van de
scope van een ingangspunt, met de verzoekmapping en de parameters van de descriptor. Kaart C (`91caaf8`) gaf de kern
`trace`, `dekking` en `fixpunt` achter `DETAIL`; uchar `a3ac41fe` gaf `DroolsBackend.vergelijk(build, sessie, label,
expected)`, het oordeel over een teruggezette sessie met de labels van de afnemer.

## Wat er gebeurt

`POST /playground/analyse?via=dienst` geeft hetzelfde antwoord als zonder de vlag (parse, resolver, IR, oordeel,
DRL, graaf, vocabulaires), alleen `scenario.resultaten[]` komt van de dienst waarmee het proces draait. Per scenario:

1. Het document wordt opgelost zoals nu. Het scenario wordt een dienstverzoek met `SituatieNaarVerzoek`, dat van
   `service/src/test/…/http/` naar main gaat.
2. De scope van het scenario wordt het `pad` van een ingangspunt van de descriptor van de dienst (descriptorlogica,
   zoals `GoldenOverHttpTest.padVoorScope`). Geen ingangspunt: het scenario krijgt een zichtbare weigering, geen
   terugval op de playground-route.
3. De dienst rekent: `kern.verwerk` met uitleg, trace, dekking en fixpunt. Het oordeel komt van uchar:
   `DroolsBackend.vergelijk` met de `expected` van de case die uchars frontend van het scenario maakt, op de
   teruggezette sessie van het verzoek, met de ids van het verzoek als labels. De kern krijgt daarvoor een interne
   ingang die de verwachtingen meeneemt; geen nieuwe HTTP-route, en geen kopie van de vergelijking.
4. Een evaluatiefout (422) is FAIL met een diagnose zoals uchars rapport. Een situatie die het verzoek niet draagt
   (tijdlijn, dimensie: `NIET_ONDERSTEUND`) is MARKER met die zin.
5. Elk resultaat noemt het `pad` waarop de dienst rekende. De werkbank hoeft niets aan haar leesvorm te veranderen.
6. `via=dienst` rekent met de bronnen van de dienst. Het hele verzoek krijgt een 400 met een zin als de geposte
   bronnen niet die van de dienst zijn; de toets bewijst dat het model gelijk is (het IR-document van de geposte
   bronnen zonder scenario's tegen dat van de dienst), geen naamvergelijking. Zonder `DETAIL` geeft `via=dienst` een
   400 die `DETAIL` noemt.

## Acceptatie

- (a) Op documenten binnen de scope van een ingangspunt geeft `via=dienst` hetzelfde oordeel (status en per
  verwachting `ok`) als de gewone route, met `pad` per resultaat; de vorm van het resultaat is die van de gewone
  route.
- (b) Een document met andere bronnen dan die van de dienst: 400 met een zin; ook bij een gelijk aantal declaraties
  met dezelfde namen.
- (c) Een scenario waarvoor geen ingangspunt bestaat: een zichtbare weigering in dat resultaat.
- (d) Een dienst zonder `DETAIL`: 400 die `DETAIL` noemt.
- (e) Een evaluatiefout is FAIL met diagnose; een situatie met een tijdlijn is MARKER met de zin.
- (f) Meting op de eval-documenten van de werkbank (`GET /api/eval`), met een eigen dienst op de v2-descriptor van
  herleving-ww (`PLAYGROUND=true DETAIL=true`): per scenario het oordeel van de gewone route naast dat van
  `via=dienst`; verschillen verklaard.
- (g) Bestaande tests ongewijzigd; alleen een importpad mag, in een eigen commit "testpatch" met reden.
- (h) `docs/ENGINE.md` beschrijft `via=dienst`. `make gates` groen.

## Omvang

Middel. May touch: `service/` (`BeslisserKern`, `Uitvoerder`, `Evaluatiefout`, `http/Playground`, `http/DienstHttp`,
`http/SituatieNaarVerzoek` van test naar main, nieuwe testklassen), `service/pom.xml` (commentaar over de test-jar),
`docs/ENGINE.md`, `README.md`, kaartverplaatsing. Niet `uchar/`, niet de Quarkus-adapter (die heeft geen playground).

## Raakvlakken

Kaart E (werkbank) zet de queryparameter en start de dienst met `DETAIL`.
