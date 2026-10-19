# review2-fixes: meldingen, sidecar, gemiste namen, documentatie en mapnamen na de tweede eindreview

*Vastgelegd op 2026-10-19. Vervolg op `pin-naming-fixes`. Toolchain `efe75646b`, uchar-pin `ec88b2919` (blijft).*

## Aanleiding

Een tweede eindreview van het programma "Engelse namen" vond in de distributie meldingen, OpenAPI-tekst en een
deployscript die de oude JSON-sleutels noemen (H1, H2), gemiste Nederlandse identifiers (M3), documentatie die niet
bestaande namen of uchar-hashes noemt (M5, M7) en Nederlandse mapnamen (L11).

## Wat er gebeurt

1. H1: elke melding, exceptietekst en OpenAPI-beschrijving die een sleutel, queryparameter, veld of Helm-waarde noemt,
   noemt de naam die de code leest; een test die de oude namen uit "Contract change" in de string-literals zoekt, of de
   reden waarom dat niet kan.
2. H2: `scripts/as-of-date-vintage-check.sh` leest de sleutels van de loader en faalt als `parameterSets` ontbreekt;
   "Contract change" noemt de sidecar.
3. Gemiste namen: `Playground.analyseer`, `ValueCoder.decodeer`, `ParameterSetLoader.decodeerInterval` en `wpad`,
   `Verbaliser.evalueer`, `Declarations.van`, `Spellings.van` en wat een script verder vindt; één hernoemcommit, in
   `.git-blame-ignore-revs`.
4. Documentatie: `CLAUDE.md` (`FLOORS`, de kaart `pin-message-codes`), testcommentaar met oude uchar-testnamen,
   `bron.rgs` en "the uitleg" in de README, uchar-hashes vervangen door kaartnamen.
5. Mapnamen: `docs/historical/`, `examples/toka-reconciliation/`, de twin `vocabulaire-dubbelzinnig` Engels.

## Acceptatie

- (a) `make gates` groen.
- (b) Een golden die verandert, krijgt een oorzaak in het Result.

## Omvang

May touch: alles in de worktree behalve `uchar/`, `examples/toka/*.rgs` en `specification/`.

## Result (2026-10-19)

**H1, meldingen en OpenAPI-tekst** (`e546151b1`). Een script las alle string-literals van `service/`, `service-quarkus/` en
`toolchain/` (250 literals met een oud woord, bijna allemaal gewoon Nederlands proza); 41 noemden een sleutel, queryparameter
of veld en zijn rechtgezet, in 10 bronbestanden: `'instances'-array`, `instances/attributes/characteristics/roles/relations/
factType ontbreekt` in `RequestMapper`, queryparameter `inputVocabulary`/`vocabulary` in `NameReader` en `OpenApiEmitter`,
`asOfDate is verplicht` en "Alias van 'asOfDate'", `explanation`/`coverage`/`fixpoint` bij de vlaggen, `kind/field/message`,
`x-spellings`/`x-valueSpellings`, in `ParameterSetLoader` sleutel `'parameterSets'`, `.name ontbreekt`, `{value, from?, to?}`,
`veld 'value'`, en `'value'`/`'unit'` in `ValueCoder`. Elke nieuwe naam is gecontroleerd tegen de code die hem leest. Ook de
Makefile-hulp (`?coverage`, `?fixpoint`), `NOTES.txt` en `values.yaml` ("asOfDate is verplicht"), en `vocabulary_replay.py` en de
getuige noemen de nieuwe tekst. Nieuwe test `ContractNamesInMessagesTest` (toolchain): leest de literals van de drie modules
en weigert de oude namen alleen in de vormen die een sleutel noemen (tussen aanhalingstekens of backticks, als begin van een
objectvorm, als onderwerp van "ontbreekt/moet", na "queryparameter"); geen uitzonderingenlijst, geen valse treffers op de
huidige bronnen. Vijf bestaande tests pinden de oude tekst en volgen (`NameReaderTest`, `VocabularyHttpTest`,
`VocabularyParityTest`, `AsOfDateSessionTest`; geen asserts veranderd, alleen de verwachte tekst).
Veranderde goldens: `service/src/test/resources/openapi/{toka-belastingdienst,jutterswet-vocabulaires,
jutterswet-vocabulaires.handboek,jutterswet-vocabulaires.cgm}.golden.json`, alleen beschrijvingstekst (211, 27, 27 en 27
plaatsen): oorzaak de OpenAPI-beschrijvingen die nu de Engelse sleutelnamen noemen.

**H2, de sidecar** (`203e7bcb9`). `as-of-date-vintage-check.sh` leest `parameterSets`/
`values`, faalt met exit 1 en de oude sleutels in de melding als `parameterSets` ontbreekt of geen lijst is (getest met een
oude en een nieuwe sidecar: 1, waarschuwing, bevestiging), adviseert `--set parameterSets.multiVintage=true`. "Contract change"
heeft de sidecar als eigen punt (`parametersets, naam, waarden, waarde, vanaf, tot, eenheid` zijn `parameterSets, name,
values, value, from, to, unit`).

**Gemiste namen**, één hernoemcommit (volle hash in `.git-blame-ignore-revs`): `Playground.analyseer`→`analyze` (16 bestanden
met aanroepen, ook tests), `ValueCoder.decode`, `ParameterSetLoader.decodeInterval` en `valuesPath`, `Verbaliser.evaluate`,
`Declarations.of`, `OpenApiEmitter.Spellings.of`, `sch`→`violationsSchema`, de shellfunctie `is_excluded`. Een script over alle
identifiers van Java, Python, JS en shell (woorden die niet in het Engelse woordenboek staan, daarna met de hand) vond verder
alleen RegelSpraak-, grammatica-, uchar-namen (`Namen`, `Breuk.DelingDoorNul`, `Segment.van()`, `kaleNaam`, enumconstanten
als `GELIJK`) en Nederlandse testmethodenamen, die blijven (testbestanden).

**Documentatie.** `CLAUDE.md`: `FLOORS`, en de zin over de meldingen (uchars meldingen hebben codes en catalogi, de eigen
meldingen houden hun zinnen, een melding die een sleutel noemt noemt de Engelse). Testcommentaar in `EntryHttpTest`,
`ScenarioRunnerTest` en `ErrorParityTest`: `TmsCreationWitnessTest` (met `a_second_filling_of_a_to_one_role_is_a_fault`),
`SubtypeRoleFillerWitnessTest.the_reciprocal_role_also_mirrors_for_a_subtype`, gecontroleerd tegen `uchar/`. README:
`source.rgs` (2x), "the explanation". M7: de uchar-hashes (`ec88b2919`, `c70dcad6` en 23 `uchar <hash>` in de commentaren van
`check-service-replay.sh`, `check-twins.sh`, `toka-manifest-exclusions.txt`) zijn kaartnamen (`zero-division-channel`,
`scenariolaag-in-uchar`) of vervallen (de datum blijft). Niet aangeraakt: oude namen van niet-bestaande uchar-tests in
`docs/historical/` en in de toka-scenario-commentaren (historische tekst).

**Mapnamen.** Eén `git mv`-commit: `docs/historical/`, `examples/toka-reconciliation/` (`casus-omissies.rgs` blijft),
`scripts/twins/ambiguous-vocabulary.{rgs,service.json,witness.json}`; daarna de verwijzingen in `Makefile`, `NOTICE`,
`README.md`, `Dockerfile`, `check-twins.sh`, `examples/toka-scenarios/manifest` en twee tests (`TokaSource`, `FactModelCliTest`).

**`make gates`**: GROEN, 7 stappen in 9m19s (1624/141/332/53 testgevallen, tweelingen 18 cases, replay 230 goldens en 606
cases, manifest 29 ok en 3 uitgesloten, image, kind-smoke). Een eerdere run was rood op `AsOfDateSessionTest` (pinde "peildatum"),
hersteld. `uchar/` staat nog op `ec88b2919`.
