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
`werk/writer-holds-on-no-day/probes/`. Pin ongewijzigd (uchar `74119a23e`). Het open punt van de eerste ronde (een
initialisatie die alleen gold op dagen die al een waarde hadden) is op verzoek van de hoofdsessie in deze kaart
opgelost (tweede ronde).*

### Commits

| hash | inhoud |
|---|---|
| `3e3f2c33c` | kaart naar `doing/` |
| `91916cd90` | Uitleg: een schrijver die op geen dag gold (`Verbaliser`); `ExplanationHeldOnNoDayTest`, `TimelineStackHttpTest` (case3), `TimelineHttpTest` omgedraaid |
| `ecf7d7032` | README en `docs/ENGINE.md` |
| `07ae7ab72` | Result (eerste ronde) |
| `cbb2b3b06` | kaart naar `done/` |
| `41517bd86` | Uitleg: een initialisatie die alleen gold op dagen met een waarde (`Verbaliser.undecided`); `ExplanationHeldOnNoDayTest` +1 |
| `5c2e4cf43` | README en `docs/ENGINE.md` |
| (deze, de laatste) | Result bijgewerkt; de kaart staat in `done/` |

### Wat er veranderde

- **Eén plaats.** `Verbaliser.ComputationExplanation.undecided(Writer)` zegt waarom een schrijver van een attribuut met
  de lezing `HOLDS` geen dag besliste, in drie uitkomsten: `HELD_ON_NO_DAY` (de knoop van zijn voorwaarde per periode
  leest geen periode, uchars RUNTIME-SEMANTICS ch. 5.12), `HELD_ON_DAYS_WITH_A_VALUE` (een initialisatie wier
  voorwaarde alleen geldt op dagen die al een waarde hadden: elke dag van haar perioden ligt in een span van
  `DroolsBackend.periodDecisionsOf` met een regel of een invoer, ch. 7.8) en `GAVE_NO_VALUE` (anders: hij gold op een
  dag die hij kon beslissen en gaf daar leeg; ook een voorwaarde die een poort is en een regel zonder voorwaarde). Een
  regel en een initialisatie zijn één soort schrijver (`Writer`), dus één pad. De zin van een tijdlijndoel leest het
  in de kop (wie geen dag besliste maar gold), in die kop zelf en in de variant; de zin van een scalair doel leest het
  in de variant en krijgt er altijd `GAVE_NO_VALUE`.
- **De zinnen.** `HELD_ON_NO_DAY`: een `VARIANT` `; de regel '…' gold op geen dag, want niet geldt: <voorwaarde>
  (<perioden>)`, en de kop `want geen regel gold` waar geen ander gold. `HELD_ON_DAYS_WITH_A_VALUE`: `de regel '…' gold
  alleen op dagen die al een waarde hadden: <voorwaarde> (<perioden>)`, als variant na `; ` en in de kop na `want `
  waar geen regel een dag besliste en de invoer staat. De voorwaarde staat zoals een voorwaarde per periode overal staat
  (`heldInPeriods`): haar regeltekst als `QUOTE` met de perioden van de lezing als `READING`, `(altijd leeg)` voor geen
  periode. "gold op geen dag" en "gold alleen op dagen die al een waarde hadden" zijn `CONNECTIVE`s, zoals "gold niet".
- **Blijft.** "gaf geen waarde" (kop) en "gaf geen waarde en besliste niets" (variant) voor een schrijver die op een lege
  dag gold en daar leeg gaf (`jutterswet-initialisatie-per-dag` case3, `kustloon` en `havenloon`; `standaardhavenloon`
  in de test); een gewone regel, die voor geen schrijver en geen invoer wijkt; een schrijver zonder voorwaarde; alles op
  een scalair doel; de zinnen van een defaults-groep. Het antwoord verandert niet.
- **Gemeten over uchars goldens** (`logs/explanations-old.txt` met de jar van main `a65ce4704`; na de eerste ronde
  `logs/explanations-step1.txt` met die van `ecf7d7032`; na de tweede `logs/explanations-final.txt` met die van
  `41517bd86`; `logs/10-corpus-diff-final.log`): 30 van de 5145 zinnen veranderen, in acht goldens
  (`jutterswet-afwezigheidsmarkers` 12, `-afwezigheidsmarkers-kopie` 4, `-initialisatie-per-dag` 1, `-loon-per-periode`
  4, `-opgave-per-periode` 4, `-pachtvenster` 2, `-wachtdagen-twee-schrijvers` 1, `-wachtmaanden` 2), alle 30 "gold op
  geen dag". De tweede ronde verandert er geen (`explanations-step1.txt` = `explanations-final.txt`, byte voor byte):
  geen golden heeft een initialisatie die alleen gold op dagen met een waarde, ook `jutterswet-initialisatie-per-dag`
  niet (in case2 laten de regels en de invoer `standaardstormloon` dagen binnen de stormwacht), en de fixtures van
  `ref/test/fixtures/writers-stack/` evenmin (0 van 34 zinnen anders, `logs/15-fixtures-diff.log`); de getuige is eigen
  materiaal. Voorbeelden: `"P2 heeft geen zomerdagen, want de regel 'zomerdagen van een strandpachter' gaf geen waarde,
  omdat vanaf de ingangsdatum van de Strandpachter tot 1 september 2025 (altijd leeg)"` → `"P2 heeft geen zomerdagen,
  want geen regel gold; de regel 'zomerdagen van een strandpachter' gold op geen dag, want niet geldt: vanaf de
  ingangsdatum van de Strandpachter tot 1 september 2025 (altijd leeg)"`; `"…; de regel 'wachtdagen in een volle maand'
  gaf geen waarde en besliste niets"` → `"…; de regel 'wachtdagen in een volle maand' gold op geen dag, want niet geldt:
  hij gedurende de gehele maand wachtplichtig is (altijd leeg)"`. In de replay-poort: 5043 zinnen (gelijk), uitgelegde
  voorwaarden 3461 → 3477 (de variant telt nu een "want").
- **De werkbank-meting van v2 (uitleg)** (`logs/werkbank-v2/`, `explain_diff.py` van de meting van pin 504522cc,
  alleen gelezen in de werkbank, oude jar main `a65ce4704`, nieuwe `41517bd86`; dezelfde uitkomst met `ecf7d7032`): over
  de acht scenariodocumenten van `v2/herleving-ww` (8886 regels uitvoer per jar) verandert **geen regel**, elk document
  exit 0 → 0. Eigen gevolgtrekking: v2 heeft geen tijdsafhankelijk attribuut of kenmerk (meting van pin 504522cc), dus
  geen schrijver van een tijdlijndoel.

### Rood eerst (letterlijk)

Eerste ronde (`logs/03-red.log`, main `a65ce4704` met de nieuwe tests):

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

Tweede ronde (`logs/11-red-days-with-a-value.log`, `cbb2b3b06` met de nieuwe test):

- `ExplanationHeldOnNoDayTest.an_initialisation_whose_condition_holds_only_on_days_with_a_value_says_so`, 1 failure
  (de twee van de eerste ronde groen): `but was: <[J1 heeft als stormloon 20 van 01-03-2025 tot 01-06-2025 volgens de
  regel 'stormloon', omdat van dd. 01-03-2025 tot dd. 01-06-2025 (van 01-03-2025 tot 01-06-2025); de regel
  'standaardstormloon' gaf geen waarde en besliste niets, J1 houdt als kustloon de invoerwaarde 7 van 01-01-2025 tot
  01-01-2026, want de regel 'standaardkustloon' gaf geen waarde en besliste niets, omdat hij stormwacht is (van
  01-03-2025 tot 01-06-2025), J1 heeft als havenloon 20 van 01-03-2025 tot 01-04-2025 volgens de regel 'havenloon',
  omdat … (van 01-03-2025 tot 01-04-2025); de regel 'standaardhavenloon' gaf geen waarde en besliste niets]>`; de
  derde zin (`standaardhavenloon`, leeg op een lege dag) was al de verwachte.

### Mutaties (niet gecommit; tweede ronde over de code van `41517bd86`: `logs/13-mutations/`, `logs/13-mutations.log`; de eerste ronde in `logs/08-mutations/`)

| mutatie | rood |
|---|---|
| N1 een voorwaarde zonder periode geeft `GAVE_NO_VALUE` | 4: twee van `ExplanationHeldOnNoDayTest`, `TimelineStackHttpTest` (case3), `TimelineHttpTest` (J4) |
| N2 elke voorwaarde per periode telt als "op geen dag" | 3: twee van `ExplanationHeldOnNoDayTest` (`want geen regel gold; de regel 'havenloon' gold op geen dag, want niet geldt: hij kustwacht is (van 01-03-2025 tot 01-06-2025)`), `TimelineStackHttpTest` (case3) |
| N3 de kop leest `HELD_ON_NO_DAY` niet (zo'n schrijver weer in de kop) | 2: `ExplanationHeldOnNoDayTest`, `TimelineHttpTest` |
| N4 de variant leest `undecided` niet (altijd "gaf geen waarde en besliste niets") | 5: de drie van `ExplanationHeldOnNoDayTest`, `TimelineStackHttpTest`, `TimelineHttpTest` |
| N5 zonder de toets op een voorwaarde | 2: `ExplanationStackPerDayTest` (`IllegalArgumentException: geen knoop van dit document: null`), `TimelineHttpTest` borgdeel (500 `interne fout`) |
| N6 nooit `HELD_ON_DAYS_WITH_A_VALUE` | 1: `ExplanationHeldOnNoDayTest` (dagen met een waarde) |
| N7 ook een gewone regel kan alleen op dagen met een waarde gelden | 1: `TimelineStackHttpTest` case3 (`de regel 'kustloon' gold alleen op dagen die al een waarde hadden: van dd. 01-03-2025 tot dd. 01-09-2025 (…)`: die dagen vulde de initialisatie, omdat de regel leeg gaf) |
| N8 een span met invoer telt als leeg | 1: `ExplanationHeldOnNoDayTest` (`want de regel 'standaardkustloon' gaf geen waarde en besliste niets`) |
| N9 een span met regels telt als leeg | 1: `ExplanationHeldOnNoDayTest` (`; de regel 'standaardstormloon' gaf geen waarde en besliste niets`) |
| N10 elke span telt, ook buiten de perioden van de voorwaarde | 1: `ExplanationHeldOnNoDayTest` (dagen met een waarde) |
| N11 de kop leest `HELD_ON_DAYS_WITH_A_VALUE` niet | 1: `ExplanationHeldOnNoDayTest` (`standaardkustloon`) |
| N12 de variant zegt bij `HELD_ON_DAYS_WITH_A_VALUE` "gaf geen waarde en besliste niets" | 1: `ExplanationHeldOnNoDayTest` (`standaardstormloon`) |

### Omgedraaide tests (oud → nieuw; de gedragswijziging van deze kaart)

Gemeten: de drie suites op de wijziging zonder omgedraaide test (`logs/04-suites-after-change.log`) gaven één failure;
geen andere test pinde de oude zin. De tweede ronde deed geen bestaande test rood (`logs/12-suites-days-with-a-value.log`:
191/486/54, 0 failures).

1. `TimelineHttpTest.the_explanation_of_a_defaults_group_that_decides_per_day_names_each_period` (uchars golden
   `jutterswet-opgave-per-periode` case4, de laatste zin): `"J4 heeft geen berekende stormtoelage, want de regel
   'stormtoelage' gaf geen waarde, omdat hij stormwacht is (altijd leeg)"` → `"J4 heeft geen berekende stormtoelage,
   want geen regel gold; de regel 'stormtoelage' gold op geen dag, want niet geldt: hij stormwacht is (altijd leeg)"`.
   De andere vijf zinnen van die test zijn gelijk; de javadoc noemt de regel erbij.

Nieuw: `ExplanationHeldOnNoDayTest` (3, eigen materiaal: een regel alleen naast de invoer, een initialisatie naast een
regel die besliste, een regel die op een dag gold en leeg gaf naast een die op geen dag gold, een initialisatie alleen,
de stukken van de variant; een initialisatie wier stormwachtdagen een regel vulde, een wier dagen de invoer vulde, en een
die op een lege dag leeg gaf) en `TimelineStackHttpTest` +1 (case3, elke zin). Testgevallen per module 188/485/54 →
191/486/54; de vloeren van `tools/check-skips.sh` onaangeroerd.

### Voorlopige besluiten

- VB-1: de voorwaarde in "niet geldt: …" en na "gold alleen op dagen die al een waarde hadden: " is de hele voorwaarde
  met de perioden van de lezing (`heldInPeriods`), niet het lid waar de motor stopte (`firstFailing`): een voorwaarde
  per periode heeft per knoop perioden en geen oordeel waar de motor stopte, en een samenstelling kan op geen dag
  gelden terwijl elk lid op een dag geldt.
- VB-2: geen periode staat als `(altijd leeg)`, de weergave van een lezing zonder periode overal in de uitleg; geen
  eigen woord ("op geen dag") in de haakjes, want dat staat al in "gold op geen dag".
- VB-3: waar geen schrijver een dag besliste en geen gold, zegt de kop "want geen regel gold", zonder grondslag, zoals
  op een scalair doel; de schrijver die op geen dag gold is daarna een `VARIANT` (`decided: false`). Een initialisatie
  die alleen gold op dagen met een waarde gold wel: zij staat in de kop (`want de regel '…' gold alleen op dagen die al
  een waarde hadden: …`), met haar grondslag, zoals een schrijver die gold en leeg gaf.
- VB-4: "gold op geen dag" en "gold alleen op dagen die al een waarde hadden" zijn `CONNECTIVE`s, zoals "gold niet"
  (`docs/ENGINE.md`, de tabel van de stukken).
- VB-5: een default. Een default van een groep op een tijdlijndoel die geen dag besliste, noemt de uitleg niet: de
  zinnen per periode noemen alleen wie besliste, een conflict of de invoer (kaart `explanation-per-period`); een
  default op een scalair doel heeft een poort en geen perioden (uchars ch. 3.10). De ene plaats staat dus in de zinnen
  over een attribuut dat gewone regels schrijven, waar regel en initialisatie één pad delen; voor de default is er geen
  zin om te veranderen.
- VB-6: de woorden "gold alleen op dagen die al een waarde hadden", niet "… die al beslist waren": de dagen die een
  initialisatie niet vult, zijn die van een regel én die van de invoer (of de waarde bij het maken), en de uitleg noemt
  de invoer nergens een beslisser ("bleef staan"); een initialisatie gold zo op dagen waarop het doel niet leeg was, haar
  rechtvaardiging (ch. 7.3).
- VB-7: alleen een initialisatie kan alleen op dagen met een waarde gelden: een gewone regel wijkt voor geen schrijver en
  geen invoer (ch. 3.11), dus een gewone regel die gold en geen dag besliste, gaf leeg (N7 bewaakt het). Een
  initialisatie die zo geen dag besliste blijft `decided: false` zonder `displaced`: haar lezing is `HOLDS`, niet
  `DISPLACED` (dat is zij waar de rest haar over alle tijd geen dag laat, `; verdrongen: …`).
- VB-8: de bron van "al een waarde" is `periodDecisionsOf` (een span met een regel of een `input`), de bron van de
  perioden de lezing; de uitleg toetst niet daarnaast dat de knopen van haar waarde ontbreken (uchars stap B: haar
  waarde wordt alleen gelezen waar haar perioden een vrije dag houden).

### Vragen

Geen. uchars lezing en `periodDecisionsOf` volstonden: de status en de perioden van de knoop van de voorwaarde, en per
span de regels en de invoer.

### make gates

Eén run aan het eind van de tweede ronde in de worktree op `5c2e4cf43` (`werk/writer-holds-on-no-day/logs/gates-2.log`);
de commit daarna raakt alleen deze kaart. De run van de eerste ronde op `ecf7d7032` (`gates-1.log`) was ook groen
(190/486/54).

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m13s  testgevallen per module 191/486/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m25s  338 goldens, 964 cases (pin 338)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     38s  decision-service:dev
gates: 7 kind-smoke GROEN   1m19s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m35s; de uitvoer staat in out/gates/
```
