# toka-harnas-verwijzingen: het TOKA-harnas verwijst alleen naar documenten, targets en tests die bestaan

*Vastgelegd op 2026-10-13, bij de uitvoering van het besluit van de eigenaar over TOKA (C6-b): alleen de 47
casusfragmenten `examples/toka/*.rgs` blijven onaangeroerd, de rest van `examples/toka*` is een gewoon projectbestand.
Daarmee is open punt 7 van de kaart `20260926-submodule-en-reactor` uitvoerbaar.*

## Aanleiding

Open punt 7 (`backlog/done/20260926-submodule-en-reactor.md:320-322`, citaat): "In de pariteitszone verwijzen
`examples/toka-scenarios/manifest`, `examples/toka-scenarios/scenario-marker-exit3.rgs` en
`examples/toka-reconciliation/README.md` naar het verwijderde `docs/SCENARIOTOETSEN.md` of het verplaatste
`docs/grammar-divergences.md`; deze kaart mag de zone niet aanraken." Het punt bleef open omdat de zoneregel elke
wijziging in `examples/toka*` verbood. Die regel geldt nu alleen nog voor de 47 fragmenten.

Gemeten op 2026-10-13 (toolchain `27b4789`, uchar-pin `b536bbc9`):

- `docs/SCENARIOTOETSEN.md` bestaat niet. Verwezen vanuit `examples/toka-scenarios/manifest:1` ("gate G2") en
  `examples/toka-scenarios/scenario-marker-exit3.rgs:1` ("Exitcodes").
- `docs/grammar-divergences.md` staat nu als `docs/historical/grammar-divergences.md` (sectie "TOKA casus omissions",
  r. 1864). Verwezen vanuit `examples/toka-reconciliation/README.md:4`.

In dezelfde bestanden, buiten open punt 7:

- `make verify-corpus` (`examples/toka-reconciliation/README.md:17`): de `Makefile` heeft dat target niet.
- `TokaVuurTest` en `TokaDekkingTest` bestaan niet, niet in de toolchain en niet in uchar op de pin. Genoemd in
  `examples/toka-reconciliation/README.md:18`, `examples/toka-scenarios/manifest:3` en de kopcommentaren van tien
  `.rgs`-bestanden in `examples/toka-scenarios/`: `scenario-model-extensie.rgs` en `scenario-toka-22`, `-23`, `-24`,
  `-31`, `-32`, `-36`, `-37`, `-39` en `-45`.
- `CasusTest` (`examples/toka-reconciliation/README.md:19`, `scenario-toka-23-marker-bevestigingstijdstip.rgs:7`) staat
  alleen in uchar: `toka/src/test/java/dev/uchar/regelspraak/CasusTest.java`.
- "main-spec" (`examples/toka-reconciliation/README.md:12`) verwijst naar de niet-gepubliceerde Markdown-omzetting van de
  specificatie.

Samen 13 bestanden: 12 in `examples/toka-scenarios/` en 1 in `examples/toka-reconciliation/`.

## Wat er gebeurt

Elke verwijzing in deze bestanden wijst naar iets dat bestaat, of verdwijnt. Bestaande doelen zijn onder meer:
- de exitcodes van de scenarioroute: `README.md`, de tabel bij `bin/regelspraak` (`scenario`);
- de scenariolaag: uchar `frontends/regelspraak/grammar/DIVERGENCES.md`, sectie "Scenariotoetsen";
- de TOKA-omissies: `docs/historical/grammar-divergences.md`, sectie "TOKA casus omissions";
- een test die de casus laadt: de testklasse die dat nu doet, met haar pad.

Beschrijft de zin rond een verwijzing gedrag van de vroegere runner, dan beschrijft hij het huidige gedrag of verdwijnt
hij. Alleen commentaar en README-tekst veranderen; de scenario's, het manifest en `casus-omissies.rgs` geven dezelfde
uitkomst. De 47 fragmenten in `examples/toka/*.rgs` blijven byte-gelijk aan uchar `toka/casus/*.rgs`.

## Acceptatie

- (a) `grep -nE 'docs/SCENARIOTOETSEN|docs/grammar-divergences|verify-corpus|TokaVuurTest|TokaDekkingTest|main-spec'`
  over `examples/toka-scenarios/` en `examples/toka-reconciliation/` is leeg. Elke andere verwijzing in die bestanden
  naar een bestand, target of testklasse bestaat (gemeten, met de lijst in het Result).
- (b) `cmp` van `examples/toka/*.rgs` met `uchar/toka/casus/*.rgs`: 47 van 47 gelijk.
- (c) `make gates` groen, met dezelfde manifestregel als vóór de kaart (op 2026-10-13: "32 regels, 29 ok, 3
  uitgesloten, 0 mismatch").

## Omvang

Klein. May touch: het commentaar in `examples/toka-scenarios/` en `examples/toka-reconciliation/README.md`. Niet:
`examples/toka/*.rgs` en `uchar/`.

## Result

*Gebouwd op 2026-11-06 in de branch `card/scope-check-and-cleanup`. Logs in `uchar-dev/werk/toolchain-cleanup/logs/`.*

Alleen commentaar en README-tekst in de 13 bestanden (12 in `examples/toka-scenarios/`, `examples/toka-reconciliation/README.md`).
De verwijzingen wijzen nu naar wat bestaat: `make scenario-test` als stap 5 van `make gates` (README "The gates") en
`scripts/toka-manifest-exclusions.txt` voor het manifest; de exitcodes van de scenarioroute in de tabel bij
`bin/regelspraak` (`scenario`) in `README.md`; de scenariolaag in uchar `frontends/regelspraak/grammar/DIVERGENCES.md`,
"Scenariotoetsen"; de omissies in `docs/historical/grammar-divergences.md`, "TOKA casus omissions"; de klassen die de
reconciliatie laden met hun pad (`FactModelCliTest`, `TokaSource`, naast `make facts` en het manifest); uchars
`CasusTest` met zijn pad in de submodule; `ScenarioRunnerTest.marker_scope` met zijn pad. Een "Bron: TokaVuurTest.…"
werd de beschrijving van de berekening zelf, of de paragraaf van de RegelSpraak-specificatie die er al naast stond
("main-spec" werd "de RegelSpraak-specificatie").

Zinnen over de vroegere runner beschrijven nu het gemeten gedrag (`uchar-dev/werk/toolchain-cleanup/c6/`):
`scenario-marker-exit3.rgs` krijgt MARKER met exit 3 omdat uchars backend de regel weigert ("transpiler SKIP: regel:
heffing markert — een BuiltinCall dayCount …", `marker-exit3-before.log`), niet omdat een runner een DRL-blok stript;
toka-23 resolvet niet ("date ± date is ill-formed", exit 1, `toka23-before.log`) en is een van de twee fouten die
uchars `CasusTest` verwacht (naast toka-45; de tekst zei "de enige"); het manifest zegt dat een scope het document van
het scenario tot de vormen van die scope beperkt (de zin "Alle geladen regels compileren en vuren; de scope bepaalt
alleen dekking/marker" was de vroegere runner); de model-extensie noemt wat er zonder haar gebeurt, gemeten:
"het objecttype 'Vlucht' kent geen kenmerk 'in het hoogseizoen'", exit 1 (`toka26-without-extension.log`).

- (a) `grep -nE 'docs/SCENARIOTOETSEN|docs/grammar-divergences|verify-corpus|TokaVuurTest|TokaDekkingTest|main-spec'`
  over beide mappen: leeg. Elke andere verwijzing in alle bestanden van beide mappen naar een bestand, target of
  testklasse bestaat (`logs/c6-references.log`): `bin/regelspraak`, `docs/historical/grammar-divergences.md`,
  `examples/toka`, `examples/toka-reconciliation`, `examples/toka-reconciliation/casus-omissies.rgs`,
  `examples/toka-scenarios/manifest`, `scripts/toka-manifest-exclusions.txt`,
  `service/src/test/java/dev/uchar/regelspraak/toolchain/service/TokaSource.java`,
  `toolchain/src/test/java/dev/uchar/regelspraak/toolchain/cli/FactModelCliTest.java`,
  `toolchain/src/test/java/dev/uchar/regelspraak/toolchain/engine/ScenarioRunnerTest.java` (met `marker_scope`),
  `uchar/frontends/regelspraak/grammar/DIVERGENCES.md`, `uchar/toka/src/test/java/dev/uchar/regelspraak/CasusTest.java`,
  uchars `toka/casus/*.rgs`, en de targets `make facts`, `make gates`, `make scenario-test`. (De vier regels MISSING in
  dat log zijn vangsten van het meetscript zelf: `X.java` gelezen als methode `java`, en uchars pad zonder `uchar/`.)
- (b) `cmp` van `examples/toka/*.rgs` met `uchar/toka/casus/*.rgs`: 47 van 47 gelijk (`logs/c6-cmp.log`).
- (c) `make gates` GROEN op 21ade915f, de eindstand van de branch (`uchar-dev/werk/toolchain-cleanup/logs/gates-final.log`,
  7 stappen in 6m26s): pin; reactor 197/502/57 testgevallen; twins 3 tweelingen, 18 cases; replay 338 goldens (pin 338),
  968 cases (pin 968); manifest 32 regels, 29 ok, 3 uitgesloten, 0 mismatch; image; kind-smoke H2, H3, H4 PASS.
