# pin-naming-fixes: de pin volgt uchars hernoemingen en de reviewpunten van de distributie

*Vastgelegd op 2026-10-19. Vervolg op `pin-message-codes`. Toolchain `957f7ec`, uchar-pin `476e0ff83` naar `ec88b2919`.*

## Aanleiding

Sinds de vorige pin hernoemde uchar de per spelling bevroren namen van het kernmodel, javadoc en `toString`, `category` naar
`kind` en de fabrieksmethoden `van(...)` naar `of(...)`; meldingen halen Nederlandse zinsneden uit de catalogus, en een deling
door een numerieke nul geeft in Drools leeg plus een §9.5-feit EF-005 in plaats van een evaluatiefout. Een eindreview van de
distributie vond daarnaast losse punten (bevinding 6 en 13).

## Wat er gebeurt

1. De pin naar `ec88b2919`; `make check-pin`.
2. De eigen modules volgen de nieuwe namen (compileren, plekken die de compiler niet ziet met de hand).
3. Reviewpunten, elk in een eigen commit: `van` wordt `of` (`Violation`, `Naming`, `EvaluationFault`, `FormIndex`); namen van
   uchar in `README.md`, `docs/` en `CLAUDE.md` die niet bestaan, rechtgezet; Engelse plaatshouders in de CLI-hulp en de
   README; de `.pyc`-bestanden uit de index en `__pycache__/` in `.gitignore`; `.git-blame-ignore-revs` aangevuld.
4. De EF-005-wijziging genoemd in de README ("Contract change").

## Acceptatie

- (a) `make gates` groen.
- (b) Een golden die verandert, krijgt een oorzaak in het Result.

## Omvang

May touch: alles in de worktree behalve `uchar/` (alleen de pin), `examples/toka/*.rgs` en `specification/`.

## Result (2026-10-19)

**Pin.** `uchar` staat op `ec88b2919` (main van uchar na `review-fixes`); `make check-pin` groen.

**Commits** (branch `card/pin-naming-fixes`, op `957f7ec`): de kaart en `doing/`, de pin met het volgen (`b23199f32`),
`van` wordt `of` (`5b49e2761`), verwijzingen naar uchar-leden (`8c0b5c6e0`), Engelse plaatshouders (`a970f18c8`),
`.pyc` uit de index en `__pycache__/` genegeerd (`3125bab68`), de blame-lijst (`754e19485`), de getter `getRule`
(`b39174be8`), EF-005 met tests en docs (`d17f62ef4`), de replay-pin 230 (`352f69121`) en dit Result.

**Hoe uchar gevolgd werd.** `javac_follow.py` met de twee `MAPPING.tsv`-bestanden samengevoegd (`W/MAPPING-all.tsv`, plus
rijen die de mappings missen: `RegelSpraakModel.objecttypen/domeinen/feittypen`, `ObjectType.attribuutGranulariteit/
attribuutTijdlijnAantal`) volgde toolchain, service en (met `mvn`-meldingen) service-quarkus tot `compile test-compile`
schoon was: 43 bestanden, 462 regels erbij en eraf. Met de hand: `Firing::regel`, `Objecttype.CharacteristicKind`, `Fault::regel`
en `Edge.van` (waar twee rijen botsten), `Coverage.naam`, `ParameterSet.naam/waarden`, `Expectation.category/regel`,
`ScenarioResult.Firing.regel`, de `{@link}`-verwijzingen in javadoc (`CaseInput#van`, `Parameter#naam`, `DroolsBackend#dekking`,
`ProducerConsumer#vormen/lezingen`, `FactModelGenerator#PAKKET`, ...) en een tekenreeksleesplek die javac niet ziet: de
getter van de gegenereerde `Inconsistentie`-bean heet `getRule` (was `getRegel`), in `AnswerBuilder` (`"rule"`) en `FixpointGivens`
(`"getRegel"`); zonder die fix gaf de dienst een 500 op elk antwoord met een inconsistentie. Python en JS lezen geen namen van
uchar; de gegenereerde klassenamen (`Inconsistentie`, `Segment`) en de getters `getVan/getTot/getWaarde` zijn in uchar gebleven.

**Reviewpunten.** Fabrieksmethoden `Violation`, `Naming`, `EvaluationFault`, `FormIndex` zijn `of`. `docs/ENGINE.md` noemde
`DroolsBackend.lees` en `.vergelijk` (nu `fixpointOf`, `compare`); de zoektocht naar meer `Type.lid`-verwijzingen (een
hulpscript dat elke verwijzing naar een uchar-klasse tegen haar bron hield) vond er nog dertien in javadoc, rechtgezet.
De plaatshouders in `bin/regelspraak`, `README.md`, `docs/ENGINE.md`, `ServiceMain` en `scripts/vocabulary_replay.py` zijn Engels
(`<source.rgs...>`, `<out.json>`, `<dir>`, `<name>`, `<list>`, `<outdir>`, `<command>`); `usage:` in plaats van `gebruik:`; de
`DienstMain` in de gebruiksmelding van `ServiceMain` is `ServiceMain`; "the uitleg" is "the explanation". De drie `.pyc`-bestanden
zijn uit de index. De blame-lijst heeft `cc0cf44`, `a34239f`, `a57a5dc`, `8240cce`, `9a48874` (elk symmetrisch: 1109, 722, 205,
99 en 131 regels erbij en eraf) en `5b49e27` (134/134). Niet aangeraakt: `Declarations.van` (eigen, niet in de opdracht).

**EF-005.** Een deling door een numerieke nul geeft in Drools leeg plus een §9.5-feit EF-005 in plaats van een evaluatiefout.
In de dienst is dat een 200 met het attribuut `null` en de regel onder `inconsistencies` (in plaats van 422 `DIVISION_BY_ZERO`);
de code EF-005 staat niet in de JSON. De README ("Contract change") en `docs/ENGINE.md` noemen het. Tests die een evaluatiefout uit
een deling haalden, gebruiken nu een fout die blijft; de wijziging van deze toetsen is bewust gedaan (de kaart eist dat de tests volgen):
- `RegelSpraakEngineTest` (volgorde van een lijst): het vouwfilter telt een datumstap buiten bereik (`2^64+1`, `2^64+2` dagen);
  `ScenarioRunnerSessionTest`: twee schrijvers van de premie die verschillen (de wortel zonder afronding is vanuit de bron niet te
  schrijven, een parameter zonder waarde of een geldigheidsvenster zonder peildatum weigert de resolver al);
- `EntryHttpTest`: de deling is nu een 200 met een inconsistentie; de vouwtests gebruiken de datumstap (de helper `replay` zet een
  tekstwaarde als `dateTime`); "twee fouten tegelijk" is WRITERS_CONFLICT + COMPUTE_FAULT (rekenjaar zonder rekendatum, in de
  volgorde van uchar), de tweede vulling naast COMPUTE_FAULT;
- `DetailHttpTest`, `PlaygroundViaServiceTest`, `PlaygroundScenarioDataTest`, `DetailParityTest`: het model krijgt de regel
  "helpersdeel bij veel helpers" (7 vanaf 5 helpers) naast de deling; de foutcase is 5 helpers (twee schrijvers) in plaats van 0, met
  de dekking, het aantal vormen en de meldingstekst van de scope daarop; `ErrorParityTest` krijgt het rekenjaar als tweede fout.
Geen JSON- of OpenAPI-golden veranderde (de OpenAPI-goldens zijn byte-gelijk, de wijziging van de dienst zit alleen in antwoorden die
een test zelf schrijft). De replay-poort pint nu 230 spelers (was 225): de vijf goldens van uchars `zero-division-channel`
(`division-by-numeric-zero`, `division-abs-by-numeric-zero`, `filled-count-unguarded-division`, `at-most-reads-until-settled`,
`fold-filter-divides-by-zero`) spelen door de dienst.

**`make gates`** groen in 9m28s op de eindstand (352f69121): pin; reactor 1.624 / 140 / 332 / 53 testgevallen; twins (3 tweelingen, 18
cases, vocabulairetweeling 2 cases, 4 getuigenissen); replay (230 goldens, 606 cases, pin 230); manifest (29 ok, 3 uitgesloten,
0 mismatch); image; kind-smoke (H2, H3, H4 PASS). De eerste run viel rood op de replay-pin (230 speelden tegen 225).
