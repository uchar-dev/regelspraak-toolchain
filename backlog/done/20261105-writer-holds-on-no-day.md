# writer-holds-on-no-day: de uitleg van een schrijver van een tijdlijndoel wiens voorwaarde op geen dag geldt

*Opgesteld door de hoofdsessie op 2026-11-05, uit de open punten van `timeline-stack-in-service` (toolchain `4288f4d4a`). Gevraagd door
de eigenaar.*

## Aanleiding

Een schrijver van een tijdlijndoel (een regel, een default of een initialisatie) wiens voorwaarde per periode op geen enkele dag geldt,
heeft in uchar's lezing de status `HOLDS` met lege perioden (RUNTIME-SEMANTICS ch. 5.12). De uitleg (`Verbaliser`, de tak `HOLDS` rond
r. 1532) zegt dan wat zij zegt van een schrijver die wel gold maar een lege waarde gaf:

- `jutterswet-initialisatie-per-dag`, case3: "…; de regel 'standaardstormloon' gaf geen waarde en besliste niets";
- elders "… omdat hij stormwacht is (altijd leeg)" (gepind in `TimelineHttpTest`, r. ~400).

Dat is niet onjuist, maar het zegt niet wat er gebeurde: de schrijver gold op geen dag. Een lezer kan "gaf geen waarde" (de voorwaarde
gold, de waarde was leeg) zo niet onderscheiden van "gold op geen dag" (de voorwaarde gold nergens).

## Goal

- De uitleg onderscheidt een schrijver van een tijdlijndoel wiens voorwaarde op geen dag geldt: "de regel '…' gold op geen dag, want
  niet geldt: <de voorwaarde>" (de vorm van een scalaire regel die niet gold, met wat de lezing per periode geeft), voor een regel, een
  default en een initialisatie. "gaf geen waarde en besliste niets" blijft voor een schrijver die op een dag gold en leeg gaf.
- Eén plaats in `Verbaliser` die dat beslist uit de lezing (status en perioden), geen tweede pad per soort schrijver.

## Acceptance

- (a) Rood eerst: een test op `jutterswet-initialisatie-per-dag` case3 die "gold op geen dag" verwacht, faalt op main.
- (b) De tests die de oude zin voor dit geval pinnen (onder meer `TimelineHttpTest`), gaan naar de nieuwe: een expliciete gedragswijziging
  van deze kaart, elke test bij naam met oud en nieuw in het Result. Geen andere verwachting verandert.
- (c) `make gates` groen; de werkbank-meting van v2 (uitleg) zegt hoeveel zinnen veranderen.

May touch: `toolchain/`, `service/`, README, `docs/ENGINE.md`, kaartverplaatsing. Een lezing die uchar niet geeft, wordt een vraag, geen
wijziging in uchar.

## Omvang

Klein.

## Result

*Bouwer, 2026-11-05. Branch `card/writer-holds-on-no-day`, niet gemerged, niet gepusht. Logs onder
`werk/writer-holds-on-no-day/logs/` (buiten de repo), scripts onder `werk/writer-holds-on-no-day/scripts/`
(`dump_explanations.py`, `diff_explanations.py`, `mutations.py`, `measure.sh`, `run-tests.sh`), de proef onder
`werk/writer-holds-on-no-day/probes/`. Pin ongewijzigd (uchar `74119a23e`).*

### Commits

| hash | inhoud |
|---|---|
| `3e3f2c33c` | kaart naar `doing/` |
| `91916cd90` | Uitleg: een schrijver die op geen dag gold (`Verbaliser`); `ExplanationHeldOnNoDayTest`, `TimelineStackHttpTest` (case3), `TimelineHttpTest` omgedraaid |
| `ecf7d7032` | README en `docs/ENGINE.md` |
| (deze) | Result |
| (laatste) | kaart naar `done/` |

### Wat er veranderde

- **Eén plaats.** `Verbaliser.ComputationExplanation.heldOnNoDay(Writer)` beslist uit de lezing of een schrijver van
  een tijdlijndoel op geen dag gold: de lezing is `HOLDS` en de knoop van zijn voorwaarde leest een lijst zonder
  perioden (uchars RUNTIME-SEMANTICS ch. 5.12). Een regel en een initialisatie zijn hier één soort schrijver
  (`Writer`), dus één pad. De zin van een tijdlijndoel (`timelineSentence`) leest het op twee plekken uit die ene
  methode: zo'n schrijver is geen beslisser met "gaf geen waarde" maar een `VARIANT`, en die variant zegt
  `; de regel '…' gold op geen dag, want niet geldt: <voorwaarde> (<perioden>)`. De voorwaarde staat zoals een
  voorwaarde per periode overal staat (`heldInPeriods`): haar regeltekst als `QUOTE` met de perioden die de motor
  las als `READING`, `(altijd leeg)` voor geen periode. "gold op geen dag" is een `CONNECTIVE`, zoals "gold niet".
- **De kop.** Besliste geen schrijver een dag en gaf geen schrijver op een dag leeg, dan zegt de kop wat een scalair
  doel zegt waar geen regel gold: `heeft geen X, want geen regel gold` of `houdt als X de invoerwaarde …, want geen
  regel gold`, en geen grondslag van een schrijver.
- **Blijft.** "gaf geen waarde" (beslisser) en "gaf geen waarde en besliste niets" (variant) voor een schrijver die op
  een dag gold en daar leeg gaf (`jutterswet-initialisatie-per-dag` case3, `kustloon` en `havenloon`); een schrijver
  zonder voorwaarde; alles op een scalair doel; de zinnen van een defaults-groep. Het antwoord verandert niet.
- **Gemeten over uchars goldens** (`logs/explanations-old.txt` met de jar van main `a65ce4704`, `logs/explanations-final.txt`
  met die van `ecf7d7032`, `logs/10-corpus-diff-final.log`): 30 van de 5145 zinnen veranderen, in acht goldens
  (`jutterswet-afwezigheidsmarkers` 12, `-afwezigheidsmarkers-kopie` 4, `-initialisatie-per-dag` 1, `-loon-per-periode`
  4, `-opgave-per-periode` 4, `-pachtvenster` 2, `-wachtdagen-twee-schrijvers` 1, `-wachtmaanden` 2); elk is dit geval.
  Voorbeelden: `"P2 heeft geen zomerdagen, want de regel 'zomerdagen van een strandpachter' gaf geen waarde, omdat vanaf
  de ingangsdatum van de Strandpachter tot 1 september 2025 (altijd leeg)"` → `"P2 heeft geen zomerdagen, want geen
  regel gold; de regel 'zomerdagen van een strandpachter' gold op geen dag, want niet geldt: vanaf de ingangsdatum van
  de Strandpachter tot 1 september 2025 (altijd leeg)"`; `"…; de regel 'wachtdagen in een volle maand' gaf geen waarde
  en besliste niets"` → `"…; de regel 'wachtdagen in een volle maand' gold op geen dag, want niet geldt: hij gedurende
  de gehele maand wachtplichtig is (altijd leeg)"`. In de replay-poort: 5043 zinnen (gelijk), uitgelegde voorwaarden
  3461 → 3477 (de variant telt nu een "want").
- **De werkbank-meting van v2 (uitleg)** (`logs/werkbank-v2/`, `explain_diff.py` van de meting van pin 504522cc,
  alleen gelezen in de werkbank, oude jar main `a65ce4704`, nieuwe `ecf7d7032`): over de acht scenariodocumenten van
  `v2/herleving-ww` (8886 regels uitvoer per jar) verandert **geen regel**, elk document exit 0 → 0. Eigen
  gevolgtrekking: v2 heeft geen tijdsafhankelijk attribuut of kenmerk (meting van pin 504522cc), dus geen schrijver van
  een tijdlijndoel.

### Rood eerst (letterlijk; `logs/03-red.log`, main `a65ce4704` met de nieuwe tests)

- `TimelineStackHttpTest.the_explanation_names_a_writer_that_held_on_no_day_apart_from_one_that_gave_no_value`
  (`jutterswet-initialisatie-per-dag` case3 door de dienst; de pinnen van uchar gaven groen): `expected: <[… J3 heeft
  als stormloon … (van 01-09-2025 tot 01-11-2025); de regel 'standaardstormloon' gold op geen dag, want niet geldt: hij
  stormwacht is (altijd leeg)]> but was: <[… (van 01-09-2025 tot 01-11-2025); de regel 'standaardstormloon' gaf geen
  waarde en besliste niets]>`; de zes andere zinnen van de case, met `kustloon` en `havenloon` ("gaf geen waarde en
  besliste niets"), waren al gelijk.
- `ExplanationHeldOnNoDayTest`, 2 tests, 2 failures: `but was: <[J1 houdt als stormloon de invoerwaarde 7 van
  01-01-2025 tot 01-03-2025, want de regel 'stormloon' gaf geen waarde en besliste niets, omdat hij stormwacht is
  (altijd leeg), J1 heeft als kustloon 30 … ; de regel 'standaardkustloon' gaf geen waarde en besliste niets, J1 heeft
  geen havenloon, want de regel 'havenloon' gaf geen waarde, omdat hij kustwacht is (van 01-03-2025 tot 01-06-2025), en
  de regel 'havenloon in de storm' gaf geen waarde, omdat hij stormwacht is (altijd leeg), J1 heeft geen wachtloon,
  want de regel 'standaardwachtloon' gaf geen waarde, omdat hij stormwacht is (altijd leeg)]>`; de stukken: `but was:
  <[TEXT:; de regel ', RULE:standaardkustloon, TEXT:' gaf geen waarde en besliste niets]>`.

### Mutaties (niet gecommit; `logs/08-mutations/`, `logs/08-mutations.log`)

| mutatie | rood |
|---|---|
| N1 `heldOnNoDay` altijd onwaar | 4: beide van `ExplanationHeldOnNoDayTest`, `TimelineStackHttpTest` (case3), `TimelineHttpTest` (J4) |
| N2 ook een voorwaarde mét perioden telt (zonder `isEmpty`) | 2: `ExplanationHeldOnNoDayTest` (`want geen regel gold; de regel 'havenloon' gold op geen dag, want niet geldt: hij kustwacht is (van 01-03-2025 tot 01-06-2025)`), `TimelineStackHttpTest` (case3, `kustloon`/`havenloon`) |
| N3 de kop leest haar niet (zo'n schrijver weer beslisser) | 2: `ExplanationHeldOnNoDayTest`, `TimelineHttpTest` |
| N4 de variant leest haar niet ("gaf geen waarde en besliste niets") | 4: beide van `ExplanationHeldOnNoDayTest`, `TimelineStackHttpTest`, `TimelineHttpTest` |
| N5 zonder de toets op een voorwaarde | 2: `ExplanationStackPerDayTest` (`IllegalArgumentException: geen knoop van dit document: null`), `TimelineHttpTest` borgdeel (500 `interne fout`) |

### Omgedraaide tests (oud → nieuw; de gedragswijziging van deze kaart)

Gemeten: de drie suites op de wijziging zonder omgedraaide test (`logs/04-suites-after-change.log`) gaven één failure;
geen andere test pinde de oude zin.

1. `TimelineHttpTest.the_explanation_of_a_defaults_group_that_decides_per_day_names_each_period` (uchars golden
   `jutterswet-opgave-per-periode` case4, de laatste zin): `"J4 heeft geen berekende stormtoelage, want de regel
   'stormtoelage' gaf geen waarde, omdat hij stormwacht is (altijd leeg)"` → `"J4 heeft geen berekende stormtoelage,
   want geen regel gold; de regel 'stormtoelage' gold op geen dag, want niet geldt: hij stormwacht is (altijd leeg)"`.
   De andere vijf zinnen van die test zijn gelijk; de javadoc noemt de regel erbij.

Nieuw: `ExplanationHeldOnNoDayTest` (2, eigen materiaal: een regel alleen naast de invoer, een initialisatie naast een
regel die besliste, een regel die op een dag gold en leeg gaf naast een die op geen dag gold, een initialisatie alleen;
de stukken van de variant) en `TimelineStackHttpTest` +1 (case3, elke zin). Testgevallen per module 188/485/54 →
190/486/54; de vloeren van `tools/check-skips.sh` onaangeroerd.

### Voorlopige besluiten

- VB-1: de voorwaarde in "niet geldt: …" is de hele voorwaarde met de perioden van de lezing (`heldInPeriods`), niet
  het lid waar de motor stopte (`firstFailing`): een voorwaarde per periode heeft per knoop perioden en geen oordeel
  waar de motor stopte, en een samenstelling kan op geen dag gelden terwijl elk lid op een dag geldt.
- VB-2: geen periode staat als `(altijd leeg)`, de weergave van een lezing zonder periode overal in de uitleg; geen
  eigen woord ("op geen dag") in de haakjes, want dat staat al in "gold op geen dag".
- VB-3: waar geen schrijver een dag besliste en geen op een dag leeg gaf, zegt de kop "want geen regel gold", zonder
  grondslag, zoals op een scalair doel waar geen regel gold; de schrijver die op geen dag gold is daarna een `VARIANT`
  (`decided: false`).
- VB-4: "gold op geen dag" is een `CONNECTIVE`, zoals "gold niet" (`docs/ENGINE.md`, de tabel van de stukken).
- VB-5: een default. Een default van een groep op een tijdlijndoel die op geen dag gold, noemt de uitleg niet: de zinnen
  per periode noemen alleen wie besliste, een conflict of de invoer (kaart `explanation-per-period`); een default op een
  scalair doel heeft een poort en geen perioden (uchars ch. 3.10). De ene plaats staat dus in de zin van de schrijvers
  van een tijdlijndoel buiten een stapel, waar regel en initialisatie één pad delen; voor de default is er geen zin om
  te veranderen.

### Vragen

Geen. uchars lezing volstond: de status en de perioden van de knoop van de voorwaarde.

### Open punten

- Een initialisatie wier voorwaarde op een dag geldt, maar op geen dag die de regels en de invoer vrijlaten, heeft de
  lezing `HOLDS` met perioden en zonder de knopen van haar waarde (uchars stap B: "waar de periodes van haar voorwaarde
  een vrije dag houden, haar waarde"). De uitleg zegt dan `"; de regel 'standaardstormloon' gaf geen waarde en besliste
  niets"` (proef `probes/no-free-day.rgs`: stormwacht en de regel beide van maart tot juni). Dat is geen "op geen dag
  gegolden"; een eigen zin ("gold alleen op dagen die anderen beslisten") is een eigen kaart.

### make gates

Eén run aan het eind in de worktree op `ecf7d7032` (`werk/writer-holds-on-no-day/logs/gates-1.log`); de commits
daarna raken alleen deze kaart.

```
gates: 1 pin        GROEN      1s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m15s  testgevallen per module 190/486/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m38s  338 goldens, 964 cases (pin 338)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     43s  decision-service:dev
gates: 7 kind-smoke GROEN   1m21s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m58s; de uitvoer staat in out/gates/
```
