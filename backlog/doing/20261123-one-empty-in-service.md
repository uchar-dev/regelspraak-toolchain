# one-empty-in-service: geen markers en geen sluitingen in dienst, uitleg en playground

Goal: de pin naar uchar `e25f201ef` (de kaarten `strict-scenarios` en `one-empty`, beide delen) in één pin-bump. Dienst,
uitleg, OpenAPI, scenario en playground dragen geen absence markers meer (een markerobject in een verzoek is een gewone
400 `WRONG_TYPE`); in een strikte regelset geen uitkomst door een sluiting meer in de uitleg, geen melding van een
consistentie "moet gevuld zijn" over een open lid, geen bovengrens van `openInputs` voor een sluiting in een vouw, en een
lid met alleen een initialisatie als invoerlid in `openInputs`. De scenariolaag leest `holds()` van een kenmerk
(`characteristicsAbsent` als `{"state": "holdsNot"}`), `attributeOpen` en `characteristicOpen` als verwachting, en toont de
waarschuwingen van `ScenarioBundle.warnings` (RGS-469) naast de diagnostiek van het model.

Acceptance: `make check-pin` op `e25f201ef`; `make gates-no-docker` groen (check-pin, de reactor met de suites van
`toolchain`, `service` en `service-quarkus` en hun skip-pins, `check-twins`, `check-service-replay` met de vloeren die met
de goldens van uchar meebewegen, oud en nieuw in de commitboodschap, en `scenario-test`); `make gates` groen (de
hoofdsessie, vóór de landing); `git grep -i -E 'absence.?marker|UNKNOWN_MARKER|onbekend|niet van toepassing|Markers\b'`
in `service`, `service-quarkus`, `toolchain`, `tools`, `scripts`, `README.md` en `docs/` vindt niets behalve waar een
tekst het besluit noemt, en het `MARKER` van `scenario`/`serve` en `--allow-markers` (een ander begrip).

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh`, `scripts/service_replay.py`,
`tools/check-skips.sh` (de vloeren van de suites), `README.md`, `docs/ENGINE.md`, de submodule `uchar` (pin), deze kaart.

Out of scope: uchar zelf (nooit bewerkt; een vraag over betekenis is een kaart daar); de werkbank en haar regelset (zij
pint de toolchain zelf en zet om bij haar eigen bump); de website; het `MARKER` van `scenario` en `serve` en de vlag
`--allow-markers`; `docs/historical/`.

*Volgt op uchar's kaart `one-empty` (twee delen). Deel 1 is een besluit van de eigenaar (2026-11-23): de markers `onbekend`
en `niet van toepassing` vervallen. Deel 2 volgt op uchar's deel 2 (een strikt document op zuivere Kleene), ook besloten op
2026-11-23. Nog niet begonnen; beide delen en uchar's `strict-scenarios` landen in één pin-bump. Bijgewerkt na een tweede review
(extern model, 2026-11-23).*

## Deel 1 — de markers

### Wat vervalt

- **Verzoek:** `{"marker": "onbekend"}` en `{"marker": "niet van toepassing"}` als waarde van een attribuut, van een segment
  van zijn tijdlijn, van een cel en van `opgegeven X` (`RequestMapper`, `TimelineInput`); de foutsoort `UNKNOWN_MARKER` en de
  zin "heeft geen markers" van `WRONG_TYPE` (`Violation`). Een verzoek met een markerobject wordt een gewone 400
  `WRONG_TYPE`: een wijziging van het contract die de README noemt.
- **Antwoord en detail:** een attribuut met markers in het antwoord en in de `fixpoint` (`AnswerBuilder`, `FixpointGivens`),
  in een strikte regelset `{"state": "marker", …}`, en de markers in `openInputs` (`OpenValues`, `OpenValuesSource`).
  Wat overblijft: in een gewone regelset een waarde of `null`; in een strikte `filled`, `empty` en `open` voor een attribuut
  en `holds`, `holdsNot` en `open` voor een kenmerk.
- **Start:** de uitzondering in `DescriptorLoader` voor een attribuut met markers; een sum type in `output` weigert de start
  dan altijd (`UNSUPPORTED`). `Markers` vervalt.
- **Uitleg:** de marker als waarde (`Verbaliser`), `marker` op het `VALUE`-deel (`Part`, `explanationParts`), "een waarde
  heeft", en `onbekend` als woord van de uitleg.
- **OpenAPI:** de `oneOf` van datatype en markerobject met haar `enum`, `UNKNOWN_MARKER` in het foutschema, `marker` op een
  deel van de uitleg en `{"state": "marker"}` (`OpenApiEmitter`); de zes openapi-goldens.
- **Scenario en playground:** de marker als waarde van een situatie en een verwachting (`ScenarioData`,
  `SituationToRequest`), `?via=service` met een marker, `playground.html`.
- **Tests, scripts en docs:** `AbsenceMarkerHttpTest`, `AbsenceMarkerCellHttpTest`, `MarkerAsOpgaveHttpTest`,
  `PlaygroundAbsenceMarkersTest`, `MarkersTest`, `OpenApiAbsenceMarkersTest`, `ExplanationMarkerPartsTest`, en de
  markerdelen van `OpenValuesHttpTest`, `OpenExplanationFormsHttpTest`, `PlaygroundViaServiceTest`, `ServiceHttpTest` en
  `TimelineStackHttpTest` (een tijdlijnstapel zonder marker blijft gedekt); `scripts/check-service-replay.sh` en
  `scripts/service_replay.py`, met de vloeren die met de goldens dalen; de secties over markers in `README.md` en
  `docs/ENGINE.md`, ook in "Empty is leeg" en "A strict rule set".

Het woord `MARKER` van `scenario` en `serve` (een ingang die uchar niet draagt) en de vlag `--allow-markers` blijven: dat is
een ander begrip.

### Acceptatie

- Geen absence marker meer in `service`, `service-quarkus`, `toolchain`, `tools`, `scripts`, `README.md` en `docs/`, behalve
  waar een tekst het besluit noemt.
- De werkbank pint de toolchain zelf en zet de regelset die `niet van toepassing` gebruikt om bij haar eigen bump (uchar's
  kaart, "Volgorde").

## Deel 2 — geen sluitingen in een strikte regelset

- **Uitleg:** de zinnen voor een uitkomst die door een sluiting vaststaat (`docs/ENGINE.md`, "A strict rule set": "wordt
  zijn taxatie gegeven, dan kan dit veranderen", "komt zijn vindloon vast te staan, …", "omdat zijn taxatie niet gegeven is",
  "A closure in the value of an outcome is named where it is certain …") en de initialisatie onder een open default
  vervallen; een toets op leeg of gevuld over een open lid heet onbepaald, zoals elke andere atomaire voorwaarde. Een
  consistentie "moet gevuld zijn" over een open lid meldt niets in `inconsistencies`; de uitleg zegt dat zij niet vaststaat.
- **`openInputs`:** de bovengrens voor een sluiting in een vouw (`OpenValues`, `OpenReads`; `docs/ENGINE.md`, "Of a closure
  inside a fold …") vervalt: een open lid in een vouw is nu gewoon een open lezing.
- **Scenario en playground:** met uchar's kaart `strict-scenarios` (zelfde pin): `characteristicsAbsent` als
  `{"state": "holdsNot"}`, en `attributeOpen` en `characteristicOpen` als verwachting (`ScenarioData`, `SituationToRequest`,
  `playground.html`); "The scenarios" in `docs/ENGINE.md` ("A test document has no sentence that expects an open outcome")
  en de README volgen.
- **Tests:** `OpenExplanationFormsHttpTest`, `OpenValuesHttpTest` en de verwante tests die deze zinnen en `openInputs`
  vastpinnen.

## Voor beide delen

- `make gates` groen op de nieuwe pin.
- Geen `Co-Authored-By` of andere vermelding van een assistent in commits.

Result (2026-11-23):

| commit | wat |
|---|---|
| `ad64071ce` | claim |
| `51c65d313` | pin uchar `e25f201ef` (alleen de submodule; de toolchain compileert daar nog niet) |
| `66df66fec` | dienst, uitleg, OpenAPI, scenario en playground zonder markers en zonder sluitingen; de scenariolaag van `strict-scenarios` |
| `cb0f92442` | replay: de gelijkheden op de nieuwe pin |
| `d2da55b2f` | uitleg: de weergave van een constructorwaarde, die alleen de markers gebruikten, vervalt |
| `97ec6da2b` | dit Result |
| `fd39874c3` | herstel na de externe review: open invoer achter een initialisatie, het filter van een kwantorvouw, OpenAPI, README en ENGINE |

- **Deel 1, de markers.** `Markers` en de foutsoort `UNKNOWN_MARKER` vervallen. `RequestMapper` en `TimelineInput` lezen
  een waarde, ook een segment en een cel, alleen als waarde van het datatype: een markerobject `{"marker": "onbekend"}` is
  de 400 `WRONG_TYPE` van een waarde van de verkeerde vorm (voor een getal "eenheid-object mist 'value'"). `AnswerBuilder`
  en `FixpointGivens` hebben geen markervorm meer, `OpenValues` geen stand `marker`, en `DescriptorLoader` weigert elk sum
  type in `output` (`UNSUPPORTED`). `OpenApiEmitter`: geen `oneOf` met het markerobject, geen `UNKNOWN_MARKER` in het
  foutschema, geen `marker` op een stuk van de uitleg; de zes openapi-goldens verschillen alleen daarin (6 regels elk).
  `Verbaliser` en `Part`: geen `marker` op `VALUE`, geen `PayloadAccess`, geen "een waarde heeft"/"geen waarde heeft", en
  geen eigen weergave van een constructorwaarde of een `Construct` als literal (alleen de markers bereikten die: uchars
  frontend bouwt geen sum type meer en geen golden met een sum type speelt in Drools). `ScenarioData`,
  `SituationToRequest` en `playground.html` zonder constructorwaarden.
- **Deel 2, geen sluitingen.** `OpenReads` verliest `closedBy`, `closedOver` en de bovengrens voor een sluiting in een vouw
  (`inFolds`, `inFold`): een open lid in een vouw is een open lezing die uchar noemt. `OpenReads.written` telt een
  initialisatie niet als schrijver (een regel, of een defaults-groep met alleen haar initialisatiedefault en zonder
  uitzonderingsgroep), zoals uchars `OpenableMembers`; daardoor noemt `openInputs` een lid dat alleen een initialisatie
  schrijft, en staat het in `completeInput` van `/info` waar het `(volledig gegeven)` is. `OpenReads.of(object, lid)` geeft
  voor zo'n open invoerlid het lid zelf als oorzaak. `Verbaliser`: geen "wordt … gegeven, dan kan dit veranderen", geen
  "omdat zijn taxatie niet gegeven is", geen `closure` op `CONDITION`; een toets op leeg of gevuld over een open lid is
  onbepaald zoals elke andere voorwaarde. Een initialisatie die niet aan de beurt is omdat haar doel open is: `; niet aan
  de beurt: de regel 'loodsgeld basis'` (een losse regel), of de initialisatiedefault van een stapel waarvan het doel een
  open invoerlid is, bij haar naam, met `displaced: true`; onder een open default leest uchar de initialisatie van de
  stapel met haar knopen en staat zij er met haar uitkomst (`de regel 'basisvindloon' (onvoorwaardelijk, 25)`); een
  lid met alleen een initialisatie: `V1: zijn vindloon staat niet vast, want zijn vindloon is niet gegeven; niet aan de
  beurt: de regel 'basisvindloon'`. Een consistentie "moet gevuld zijn" over een open lid staat niet in
  `inconsistencies`; de uitleg zegt dat zij niet vaststaat.
- **Het filter van een kwantorvouw.** uchars bijdrager geeft het oordeel van de matrix, niet of het filter op dat lid
  vaststaat. Leest het filter op een lid een open lid (`OpenReads.reads`: de knopen van het filter over de koppelingen van
  uchars eindtoestand, elke tak van een keuze en elke binderwaarde meegeteld, zonder evaluatie), dan geeft de uitleg van
  die vouw alleen haar oordeel, zonder leden en zonder `MEMBERS`; anders noemt zij de leden naar het oordeel van de
  matrix, met "niet vast voor" waar die onbepaald is. Zo besloten na de review (hieronder); de eerste versie benaderde het
  oordeel per lid met de lezingen van het filter.
- **De scenariolaag.** `ScenarioData` en `SituationToRequest` lezen `CharacteristicValue.holds()`: een niet geldend
  kenmerk staat in de situatie met `holds: false` (de pagina: "onwaar"), in het verzoek als `{"state": "holdsNot"}` (strikt)
  of `false`, en in de invoer van het fixpunt als niet geldend. `attributeOpen` en `characteristicOpen` lopen door uchars
  replay op beide routes; de pagina kent ze als soort met een label. `ScenarioRunner.warnings` en `scenario` geven de
  waarschuwingen van `ScenarioBundle.warnings` op stderr (`  WARNING regel 13: scenario: 'is niet betwist' verandert …`);
  de playground zet ze bij `resolver.warnings`.
- **Tests.** Vervallen met de markers: `AbsenceMarkerHttpTest` (18), `AbsenceMarkerCellHttpTest` (7),
  `MarkerAsOpgaveHttpTest` (10), `PlaygroundAbsenceMarkersTest` (8), `OpenApiAbsenceMarkersTest` (3),
  `ExplanationMarkerPartsTest` (3), `MarkersTest.the_source_and_the_document_give_the_same_markers`,
  `OpenValuesHttpTest.a_marker_of_the_request_is_a_filled_value`, `OpenExplanationFormsHttpTest.a_marker_is_a_value`, en in
  `TimelineStackHttpTest` de twee tests van de golden met een initialisatie op een marker (de tijdlijnstapel blijft gedekt
  door `jutterswet-initialisatie-per-dag` en `-creatie-per-dag`). `MarkersTest.another_sum_type_…` werd
  `SumTypeOutputTest` (een sum type in `output` weigert de start; het OpenAPI-document heeft er geen vorm voor), op een eigen
  document met de meldpositie van `jutterswet-meldplichtspoor`. `OpenValuesSource`: de herkomst zonder marker (0 waar de
  vondst onleesbaar is). Gedrag dat de kaart verandert, tests bijgewerkt en waar de naam de sluiting noemde hernoemd:
  `OpenExplanationHttpTest` (`a_test_on_leeg_over_an_open_member_is_undetermined`, `a_test_on_gevuld_over_an_open_member`,
  `a_fold_with_an_open_member_in_a_value_and_in_a_condition`,
  `an_initialisation_below_an_open_default_and_beside_an_open_rule_is_not_at_its_turn`,
  `an_initialisation_beside_an_input_does_not_decide`, `a_sum_whose_filter_tests_an_open_member`, en in
  `no_sentence_calls_what_is_open_leeg_or_unknown` telt het woord van de toets "leeg is" na de lezing niet als een lege
  waarde), `OpenExplanationFormsHttpTest` (`an_initialisation_below_an_exception_group_with_an_open_member`,
  `a_quantifier_fold_whose_filter_tests_an_open_member`, `a_fold_names_no_member_the_filter_leaves_out`,
  `a_binder_over_an_open_read_and_one_over_a_fold_with_an_open_filter`), `OpenInputsHttpTest`
  (`an_outcome_that_a_test_on_gevuld_or_a_fold_leaves_open`: een vouw noemt haar eerste open lid; geen `closure` in de
  OpenAPI), `OpenValuesHttpTest` (herkomst, de stand `marker` weg uit `AttributeState`), `TimelineStackHttpTest` (de tekst
  van DROOLS-316 zoals uchar hem nu schrijft). Nieuw: `RequestMapperTest.a_marker_object_is_wrong_type`,
  `OpenExplanationFormsHttpTest.a_member_that_only_an_initialisation_writes_is_input` en
  `.a_consistency_on_gevuld_over_an_open_member_reports_nothing`, `ScenarioOpenValuesTest` (een strikt scenario met "- is
  niet betwist" en "staat niet vast"; de waarschuwing in een gewoon document via `scenario`), `PlaygroundOpenValuesTest`
  (het niet geldende kenmerk op beide routes en in het verzoek, de waarschuwing bij de resolver, een verwachting "staat niet
  vast" op beide routes). Testgevallen per module 212/676/68 → 211/631/68 (berekend uit de vervallen en nieuwe tests).
- **Vloeren** (`scripts/check-service-replay.sh`, gelijkheden): `EXPECTED_REPLAY_PLAYED` 377 → 376,
  `EXPECTED_REPLAY_CASES` 1072 → 1050, `EXPECTED_RED_FAULTS` 69 → 67 (de zes goldens met markers, 39 cases, en de rode map
  `jutterswet-afwezigheidsmarkers-botsing`, 2 cases, vervielen; vijf nieuwe goldens met 17 cases spelen). De lijsten van
  uitsluitingen en weigeringen bleven gelijk; de ondergrenzen van de uitleg en `FLOORS` van `tools/check-skips.sh`
  bleven staan.
- **Contractwijzigingen** (README, "Contract change"): een markerobject in een verzoek is een gewone 400 `WRONG_TYPE`;
  `UNKNOWN_MARKER`, de stand `marker`, het markerobject in OpenAPI en de sleutels `marker` (`VALUE`) en `closure`
  (`CONDITION`) van `explanationParts` vervallen. In een strikte regelset: een uitkomst die een toets op leeg of gevuld of
  een initialisatie besliste, is open, met de zin van een open uitkomst; een consistentie "moet gevuld zijn" over een open
  lid staat niet in `inconsistencies`; `openInputs` noemt een lid met alleen een initialisatie en van een vouw haar eerste
  open lid; `completeInput` noemt een volledig gegeven lid met alleen een initialisatie. `?via=service` geeft een kenmerk
  dat de situatie als niet geldend geeft als `holdsNot` of `false`.
- **Herstel na de externe review** (2026-11-24; review in `werk/neon-review/antwoorden/xhigh-y-tc-one-empty.md`, besluiten
  van de hoofdsessie in `werk/one-empty-in-service/FIX.md`), in `fd39874c3`:
  - *Een volledig gegeven lid met alleen een initialisatie* (fout antwoord in `openInputs`). Zonder waarde is het
    vaststaand leeg boven de initialisatie, die dan aan de beurt is; leest zij een open lid, dan staat het lid open door
    die lezing. `OpenReads.follow` en `derived` en de oorzaak in `Verbaliser` (`cause`, `openReading`) behandelden elk
    invoerlid als open door zijn eigen invoer. Nu zegt `OpenReads.notGiven` dat: een invoerlid dat geen vorm openliet.
    `openInputs` volgt de lezing van de initialisatie (`betwist`, `taxatie`, niet het lid zelf), de route zonder
    `?via=service` neemt zo'n lid als uitkomst, en de uitleg zegt "zijn vindloon staat niet vast" en "(staat niet vast)".
    Tests: `OpenExplanationFormsHttpTest.a_complete_member_whose_initialisation_reads_an_open_member` (een voorwaardelijke
    en een lezende initialisatie, uitleg en `openInputs`) en
    `ScenarioOpenValuesTest.a_complete_member_whose_initialisation_reads_an_open_member_is_an_outcome`.
  - *Het filter van een kwantorvouw* (de eigen keuze van de vorige bouwer). De benadering van het filteroordeel schreef
    onware zinnen: bij "alle" met een filter "A of B" (A waar, B open) ": er zijn 2 leden", bij "ten minste 2" de
    verkeerde dragers. Besluit: leest het filter op een lid een open waarde, dan noemt de uitleg alleen het oordeel van de
    vouw (hierboven); `stands` vervalt. `OpenReads.reads` leest nu ook de filters van een telling over een vereniging (zij
    gaf daar een leeg bereik), en een vouw over een verzameling zonder bereik faalt bij naam. Tests:
    `OpenExplanationFormsHttpTest.a_quantifier_fold_whose_filter_reads_an_open_member_and_is_decided` (de twee gevallen
    van de review) en `.a_quantifier_fold_whose_filter_counts_a_union_with_an_open_member`. Gedrag dat het besluit
    verandert: `OpenExplanationFormsHttpTest.a_quantifier_fold_whose_filter_tests_an_open_member` en
    `OpenExplanationHttpTest.a_quantifier_fold_with_an_open_filter` eindigen nu op "is voldaan", zonder leden.
  - *OpenAPI.* De zin bij een volledig gegeven attribuut: "staat vast dat het leeg is. Schrijft alleen een initialisatie
    het, dan is die aan de beurt: het krijgt haar waarde, is leeg waar zij niets geeft, en staat niet vast waar zij een
    open waarde leest."; bij een kenmerk "staat vast dat het niet geldt". De zes openapi-goldens zijn niet strikt en
    veranderen niet; `OpenValuesHttpTest` pint de twee zinnen.
  - *README:* de getallen bij de gates (`EXPECTED_PLAYED` 385, `EXPECTED_REPLAY_PLAYED` 376, `EXPECTED_REPLAY_CASES` 1050,
    `EXPECTED_RED_FAULTS` 67), de replay vraagt de uitleg van een strikte golden op en toetst haar, en de contractsectie
    noemt `holds: false` in `situation.objects[].characteristics[]`, de initialisatie van een volledig gegeven lid in
    `openInputs` en de vouw zonder `MEMBERS`.
  - *`docs/ENGINE.md`:* wat `OpenReads` doet (geen evaluatie; het filter over knopen en koppelingen, ruim gelezen); de
    initialisatie van een stapel onder een open default met haar knopen en uitkomst, alleen haar naam bij een losse
    initialisatieregel of een open invoerlid; het volledig gegeven lid achter een initialisatie.
  - Testgevallen per module 211/631/68 → 212/634/68.
- **Gates** (`make gates-no-docker` op `fd39874c3`, log in `werk/one-empty-in-service/fix-gates1.log`):

  ```
  gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
  gates: 2 reactor    GROEN   2m46s  testgevallen per module 212/634/68
  gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
  gates: 4 replay     GROEN   2m14s  376 goldens (pin 376), 1050 cases (pin 1050)
  gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
  gates: GROEN — 5 stappen in 5m00s; de uitvoer staat in out/gates/
  ```

  De replay daarbij: 6132 zinnen, 4092 uitgelegde voorwaarden (vloer 1241), 3415 berekende waarden (vloer 1852), 6367
  gelezen waarden aan hun zin (vloer 4248); rode leg 58 mappen, 67 cases met hun foutsoort, 14 geweigerd met reden. De
  volle `make gates` (image en kind) draait de hoofdsessie vóór de landing.
- **Open.**
  - Vervolgpunt, een kaart in uchar: het filteroordeel per lid op `ConditionReading.Contributor` (uchars
    `SessionReaders` heeft `MemberTest.filter()` in handen en geeft het niet mee). Dan kan `OpenReads.reads` weg en noemt
    de uitleg ook bij een filter dat een open lid leest de leden en dragers van een kwantorvouw.
  - Een waarde van een sum type (alleen uit een `uchar-ir-v1`-document van een andere producer, dat Drools nu nergens
    speelt) staat in de uitleg als de tekst van uchars constructorwaarde, de terugval van `renderValue` zoals vóór de
    markers; het fixpunt weigert haar bij naam (`UnrenderableValue`), het antwoord bij de start.
  - De werkbank pint de toolchain zelf en zet haar regelset om bij haar eigen bump; daarna de website (uchars kaart,
    "Volgorde").
