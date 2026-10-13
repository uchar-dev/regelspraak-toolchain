# toka-harnas-verwijzingen: het TOKA-harnas verwijst alleen naar documenten, targets en tests die bestaan

*Vastgelegd op 2026-10-13, bij de uitvoering van het besluit van de eigenaar over TOKA (C6-b): alleen de 47
casusfragmenten `examples/toka/*.rgs` blijven onaangeroerd, de rest van `examples/toka*` is een gewoon projectbestand.
Daarmee is open punt 7 van de kaart `20260926-submodule-en-reactor` uitvoerbaar.*

## Aanleiding

Open punt 7 (`backlog/done/20260926-submodule-en-reactor.md:320-322`, citaat): "In de pariteitszone verwijzen
`examples/toka-scenarios/manifest`, `examples/toka-scenarios/scenario-marker-exit3.rgs` en
`examples/toka-reconciliatie/README.md` naar het verwijderde `docs/SCENARIOTOETSEN.md` of het verplaatste
`docs/grammar-divergences.md`; deze kaart mag de zone niet aanraken." Het punt bleef open omdat de zoneregel elke
wijziging in `examples/toka*` verbood. Die regel geldt nu alleen nog voor de 47 fragmenten.

Gemeten op 2026-10-13 (toolchain `27b4789`, uchar-pin `b536bbc9`):

- `docs/SCENARIOTOETSEN.md` bestaat niet. Verwezen vanuit `examples/toka-scenarios/manifest:1` ("gate G2") en
  `examples/toka-scenarios/scenario-marker-exit3.rgs:1` ("Exitcodes").
- `docs/grammar-divergences.md` staat nu als `docs/historisch/grammar-divergences.md` (sectie "TOKA casus omissions",
  r. 1864). Verwezen vanuit `examples/toka-reconciliatie/README.md:4`.

In dezelfde bestanden, buiten open punt 7:

- `make verify-corpus` (`examples/toka-reconciliatie/README.md:17`): de `Makefile` heeft dat target niet.
- `TokaVuurTest` en `TokaDekkingTest` bestaan niet, niet in de toolchain en niet in uchar op de pin. Genoemd in
  `examples/toka-reconciliatie/README.md:18`, `examples/toka-scenarios/manifest:3` en de kopcommentaren van tien
  `.rgs`-bestanden in `examples/toka-scenarios/`: `scenario-model-extensie.rgs` en `scenario-toka-22`, `-23`, `-24`,
  `-31`, `-32`, `-36`, `-37`, `-39` en `-45`.
- `CasusTest` (`examples/toka-reconciliatie/README.md:19`, `scenario-toka-23-marker-bevestigingstijdstip.rgs:7`) staat
  alleen in uchar: `toka/src/test/java/dev/uchar/regelspraak/CasusTest.java`.
- "main-spec" (`examples/toka-reconciliatie/README.md:12`) verwijst naar de niet-gepubliceerde Markdown-omzetting van de
  specificatie.

Samen 13 bestanden: 12 in `examples/toka-scenarios/` en 1 in `examples/toka-reconciliatie/`.

## Wat er gebeurt

Elke verwijzing in deze bestanden wijst naar iets dat bestaat, of verdwijnt. Bestaande doelen zijn onder meer:
- de exitcodes van de scenarioroute: `README.md`, de tabel bij `bin/regelspraak` (`scenario`);
- de scenariolaag: uchar `frontends/regelspraak/grammar/DIVERGENCES.md`, sectie "Scenariotoetsen";
- de TOKA-omissies: `docs/historisch/grammar-divergences.md`, sectie "TOKA casus omissions";
- een test die de casus laadt: de testklasse die dat nu doet, met haar pad.

Beschrijft de zin rond een verwijzing gedrag van de vroegere runner, dan beschrijft hij het huidige gedrag of verdwijnt
hij. Alleen commentaar en README-tekst veranderen; de scenario's, het manifest en `casus-omissies.rgs` geven dezelfde
uitkomst. De 47 fragmenten in `examples/toka/*.rgs` blijven byte-gelijk aan uchar `toka/casus/*.rgs`.

## Acceptatie

- (a) `grep -nE 'docs/SCENARIOTOETSEN|docs/grammar-divergences|verify-corpus|TokaVuurTest|TokaDekkingTest|main-spec'`
  over `examples/toka-scenarios/` en `examples/toka-reconciliatie/` is leeg. Elke andere verwijzing in die bestanden
  naar een bestand, target of testklasse bestaat (gemeten, met de lijst in het Result).
- (b) `cmp` van `examples/toka/*.rgs` met `uchar/toka/casus/*.rgs`: 47 van 47 gelijk.
- (c) `make gates` groen, met dezelfde manifestregel als vóór de kaart (op 2026-10-13: "32 regels, 29 ok, 3
  uitgesloten, 0 mismatch").

## Omvang

Klein. May touch: het commentaar in `examples/toka-scenarios/` en `examples/toka-reconciliatie/README.md`. Niet:
`examples/toka/*.rgs` en `uchar/`.
