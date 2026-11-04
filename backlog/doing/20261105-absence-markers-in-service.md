# absence-markers-in-service: pin naar uchar d208bbfa5, en de markers onbekend en niet van toepassing in verzoek, antwoord en uitleg

*Opgesteld door de hoofdsessie op 2026-11-05, na uchar's kaart `absence-markers` (stap A `d9c218ce1`, stap B `994462df1`, register
`d208bbfa5`).*

## Aanleiding

Sinds uchar `absence-markers` kan een attribuut in RegelSpraak de markers `onbekend` en `niet van toepassing` dragen:
`(of onbekend)`, `(of niet van toepassing)` of `(of onbekend of niet van toepassing)` achter de declaratie (besluiten van de eigenaar,
2026-11-04, `uchar/spec/proposals/FLAVOURS-OF-EMPTY.md`). Een marker is een gevulde waarde: hij beslist zoals een getal (de invoer, de
stapel van schrijvers, EF-016, per dag op een tijdlijn); leeg blijft de enige afwezigheid. In de IR verlaagt zo'n attribuut naar een sum
type met de constructors `waarde` (het gedeclareerde datatype als payload), `onbekend` en `niet van toepassing`; een uchar-case schrijft
`{"constructor": "waarde", "payload": {"number": "40.00"}}` en `{"constructor": "onbekend"}`. Beide Drools-branches dragen het sinds stap B:
een constructorwaarde is een `ConstructorValue` op de bean, in een feit en in een segment, en `Period.value`, `Segment.getWaarde()` en
`PeriodDecisions.Span.input` zijn `Object`.

De dienst kent deze waarden nog niet: het verzoek heeft geen vorm voor een marker, het antwoord en de uitleg weten niet hoe ze een
constructorwaarde schrijven, en de OpenAPI beschrijft ze niet.

## Goal

- De pin naar uchar `d208bbfa5`.
- Het verzoek: op een attribuut met markers is een gewone waarde (getal, tekst, datum, of een tijdlijn daarvan) de waarde `waarde(…)`, en
  een marker `{"marker": "onbekend"}` of `{"marker": "niet van toepassing"}`, ook als `value` van een segment van een tijdlijn. Een marker
  die het attribuut niet declareert, of een marker op een attribuut zonder markers, is een fout met naam (400).
- Het antwoord schrijft een attribuut met markers in dezelfde vorm: de payload van `waarde` als gewone waarde, een marker als
  `{"marker": …}`.
- De uitleg noemt een marker bij zijn naam ("V1 heeft als taxatie onbekend volgens 'taxatie onbekend'"), ook per periode.
- De OpenAPI beschrijft de vorm per attribuut met markers (`oneOf` van het payloadtype en het markerobject met een `enum` van de
  gedeclareerde markers).

## Acceptance

- (a) Rood eerst: een verzoek met een marker op uchar's golden `jutterswet-afwezigheidsmarkers` geeft vóór de bouw een fout of een
  verkeerd antwoord, en daarna de pinnen van uchar; de replay van `check-service-replay.sh` speelt die golden (uit `REPLAY_EXCLUDED`,
  `EXPECTED_REPLAY_PLAYED` omhoog met het aantal goldens dat nu speelt).
- (b) De uitleg van de golden zonder "niet vast te stellen", met een test die eerst rood is.
- (c) Een test per foutsoort van het verzoek (onbekende marker, marker zonder markers).
- (d) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), README, `docs/ENGINE.md`,
kaartverplaatsing. Een API die uchar mist, wordt een kaart in uchar, niet hier.

## Omvang

Middel.

## Result

*Bouwer, 2026-11-05. Niet gemerged, niet gepusht. Logs onder `werk/absence-markers-in-service/logs/`, het mutatiescript
`werk/absence-markers-in-service/scripts/mutations.py` (buiten de repo).*

### Commits

| hash | inhoud |
|---|---|
| `8149e8b93` | kaart naar `doing/` |
| `78a2d1e42` | Pin uchar `d208bbfa5`; een tak voor `Datatype.SumType` in het OpenAPI-schema (die faalt: nog geen vorm); de golden in `REPLAY_EXCLUDED` |
| `fffc85d0e` | dienst: verzoek, antwoord, fixpunt, uitleg en OpenAPI; `Markers`, `UNKNOWN_MARKER`; tests `AbsenceMarkerHttpTest`, `OpenApiAbsenceMarkersTest`, `MarkersTest` |
| `4bb2c64a9` | replay: de driver kent constructorwaarden, de golden speelt, `EXPECTED_REPLAY_PLAYED` 331 |
| `e03f474ab` | README en `docs/ENGINE.md` |
| (deze) | Result |
| (laatste) | kaart naar `done/` |

### Wat er veranderde

- **Verzoek** (`RequestMapper`, `Markers`, `TimelineInput`). Op een attribuut met markers is een waarde van het datatype
  de waarde `waarde(…)` en `{"marker": "onbekend"}` of `{"marker": "niet van toepassing"}` een marker, scalair en als
  `value` van een segment. De mapper schrijft ze in de case zoals uchars case ze geeft: `{"constructor": "waarde",
  "payload": {"number": "40.00"}}`, `{"constructor": "onbekend"}`; een segment draagt een getal of uchars
  `ConstructorValue`. Een marker die het attribuut niet declareert: 400 `UNKNOWN_MARKER` (nieuwe soort) met de
  gedeclareerde markers; een marker op een attribuut zonder markers: 400 `WRONG_TYPE` ("attribuut 'schatting' heeft
  geen markers; …"); een markerobject met een andere sleutel: 400 `MALFORMED`. `Markers` leest de markers van het model
  van een bron (`ObjectType.markersOf`) en van het model van een IR-document (het sum type met `waarde` en markers).
- **Antwoord** (`AnswerBuilder`) en **fixpunt** (`FixpointGivens`, detail en playground): de waarde als haar payload, een
  marker als `{"marker": …}`, ook per segment.
- **Uitleg** (`Verbaliser`): de waarde als getal met de decimalen en eenheid van het datatype, een marker bij naam, als
  kop, als gelezen waarde en per periode; een `PayloadAccess` in een voorwaarde noemt het attribuut met wat het hield
  ("zijn taxatie (niet van toepassing) een waarde heeft"), een marker in een vergelijking staat één keer ("is gelijk aan
  onbekend"), en leeg op een operand van een sum type is "leeg". De golden heeft 67 zinnen, 0 keer "niet is vast te
  stellen".
- **OpenAPI** (`OpenApiEmitter`): de waarde van een attribuut met markers, ook van een segment of een cel, is `oneOf` het
  schema van het datatype en `{"type": "object", "required": ["marker"], "additionalProperties": false, "properties":
  {"marker": {"type": "string", "enum": [<gedeclareerde markers>]}}}`. Het TOKA-document (byte-pin) is ongewijzigd.
- **Start** (`DescriptorLoader`): een attribuut van een ander sum type in `output` weigert de start (`UNSUPPORTED`).

### Rood eerst (letterlijk)

- Replay op de pin (`logs/03-replay-golden-pin.log`): `jutterswet-afwezigheidsmarkers EXCLUDE waardevorm
  ['constructor', 'payload'] (V1.opslagkosten[0])`.
- Proef met de oude dienst (`logs/probe-pin.txt`; na de bouw `logs/probe-after.txt`): marker in het verzoek `400 … "kind":"WRONG_TYPE","error":
  "eenheid-object mist 'value'"`; een gewone waarde op `taxatie` `500 {"error":"interne fout"}` (uchars
  `constructorFits`: `… is a constructor value, not {"number":"4E+1"}`); antwoord `"taxatie":"waarde(300)"`; uitleg
  `"V1 heeft als taxatie waarde(300) volgens …"` en `"zijn taxatie (onbekend) gevuld is"` bij een taxatie niet van
  toepassing.
- De nieuwe tests vóór de bouw (`logs/05-red-tests.log`): `Tests run: 18, Failures: 17, Errors: 1`, onder meer
  `a_marker_in_the_request_stands_and_the_rules_read_it … "kind":"WRONG_TYPE","error":"eenheid-object mist 'value'" ==>
  expected: <200> but was: <400>`; `a_plain_value_of_the_request_is_the_value_of_the_attribute … {"error":"interne
  fout"} ==> expected: <200> but was: <500>`; `a_marker_in_the_request_reads_the_same_on_a_service_from_the_document …
  "kind":"UNSUPPORTED","error":"datatype SumType[domain=taxatie van een Vondst, …`; `the_explanation_writes_a_value_as_its_payload
  … geen zin bevat "V1 heeft als taxatie 300,00 volgens de regel 'taxatie uit schatting'"`;
  `the_explanation_names_a_marker_per_period … ==> expected: <200> but was: <500>`;
  `an_attribute_with_markers_is_one_of_its_datatype_and_the_marker_object … expected: <2> but was: <0>`;
  `the_model_of_the_document_gives_the_same_contract … IllegalState het somtype 'taxatie van een Vondst' heeft in het
  contract van de dienst geen vorm` (de tekst van de pin-commit vóór de woordkeus "sum type").

### Mutaties (niet gecommit; `logs/mutations/`, `logs/11-mutations.log`, `logs/12-mutations-verbaliser.log`)

| mutatie | rood |
|---|---|
| M1 een onbekende marker aanvaard | `a_marker_the_attribute_does_not_declare_is_400_unknown_marker`, idem `_in_a_segment_` |
| M2 een marker op een attribuut zonder markers niet geweigerd | `a_marker_on_an_attribute_without_markers_is_400_wrong_type` |
| M3 een markerobject met een andere sleutel aanvaard | `a_marker_object_with_another_key_is_400_malformed` |
| M4 een segment met een constructorwaarde geweigerd | `a_marker_as_the_value_of_a_segment_stands_on_its_days`, `a_timeline_with_markers_is_answered_per_segment`, `the_explanation_names_a_marker_per_period` |
| M5 een gewone waarde zonder `waarde(…)` in de case | `a_plain_value_of_the_request_is_the_value_of_the_attribute`, `a_marker_a_rule_writes_displaces_a_value_of_the_request` |
| M6 een marker in het antwoord als tekst | zes tests van het antwoord |
| M7 een ander sum type in `output` niet geweigerd | `another_sum_type_is_no_attribute_with_markers_and_has_no_form` |
| M8 leeg op een attribuut met markers als "onbekend" | `the_explanation_tells_an_empty_value_from_the_marker_onbekend` |
| M9 de `PayloadAccess` niet naar haar basis | `the_explanation_names_a_marker_by_its_name` |
| M10 de waardetoets als "gevuld is" | `the_explanation_names_a_marker_by_its_name`, `the_explanation_writes_a_value_as_its_payload` |
| M11 `render` zonder de constructorwaarde | eerst **groen** (de tak stond in `renderValue`, waar `render` en `renderRead` al vóór afvingen); na het samentrekken op `render`: `the_explanation_names_a_marker_per_period`, `the_explanation_writes_a_value_as_its_payload` |
| M12 een marker in een vergelijking met haar lezing | `the_explanation_names_a_marker_by_its_name` (na het aanscherpen van de verwachting tot "…; de regel 'vindloon naar taxatie'") |
| M13 een marker in het fixpunt als tekst | `the_fixpoint_gives_a_marker_as_a_marker` |
| M14 het OpenAPI-schema zonder `oneOf` | beide tests van `OpenApiAbsenceMarkersTest` |
| M15 elk sum type een attribuut met markers | `another_sum_type_is_no_attribute_with_markers_and_has_no_form` |
| M16 een marker op een tijdlijn zonder markers niet geweigerd | `a_marker_in_a_segment_of_a_timeline_without_markers_is_400_wrong_type` |
| M17 `renderRead` zonder de constructorwaarde | `the_explanation_writes_a_value_as_its_payload` |
| M18 de payload met het datatype van het sum type | `the_explanation_writes_a_value_as_its_payload` |

Zonder getuige: de `IllegalStateException` in `AnswerBuilder` voor een constructorwaarde op een attribuut zonder markers;
de start weigert zo'n attribuut in `output` al (M7).

### Pins (oud → nieuw)

- `check-service-replay.sh`: de set van gate 12 338 → 339 (pin); `EXPECTED_REPLAY_PLAYED` 330 → 330 (pin, de golden in
  `REPLAY_EXCLUDED`, 8 → 9) → 331 (deze kaart, de golden eruit, 9 → 8); 933 → 943 cases. De vloeren blijven
  (`EXPECTED_MIN_EXPLAINED_CONDITIONS` 1241, `…_COMPUTATIONS` 1852, `EXPECTED_MIN_READ_LINKS` 4248; gemeten 3348,
  2805, 5821, was 3235, 2785, 5696).
- Suites: toolchain 182 (ongewijzigd), service 439 → 460, service-quarkus 54; de vloeren van `tools/check-skips.sh`
  blijven. Tweelingen 10 + 4 + 4 en 2, manifest 32 regels: ongewijzigd.
- Gedrag dat verandert zonder bestaande test: een markerobject op een attribuut zonder markers blijft `WRONG_TYPE`, met
  de zin "attribuut '…' heeft geen markers; …" waar het "eenheid-object mist 'value'" (getal) of "verwacht tekst, kreeg
  object" was. Geen bestaande test of verwachting veranderde.

### Voorlopige besluiten

- VB-1 (foutsoorten): een onbekende marker is een nieuwe soort `UNKNOWN_MARKER`, zoals `UNKNOWN_ENUM_VALUE`; een marker
  op een attribuut zonder markers `WRONG_TYPE`, zoals `cells` zonder dimensies en een tijdlijn zonder tijdlijn; een
  markerobject met een andere sleutel `MALFORMED`.
- VB-2 (naam): een markernaam vergelijkt als naam (`Namen.norm`) en gaat in zijn gedeclareerde spelling naar de case en
  het antwoord.
- VB-3 (leeg in de uitleg): leeg op een operand van een sum type is "leeg", want "onbekend" is daar een marker; elders
  blijft leeg "onbekend" (het contract van de uitleg). Zie vraag 1.
- VB-4 (de lezing van de waarde): een `PayloadAccess` in een voorwaarde noemt haar basis met wat die hield, en de toets
  heet "een waarde heeft" / "geen waarde heeft", de woorden van het dialect; een marker in een vergelijking staat één
  keer, als een literal.
- VB-5 (fixpunt): het fixpunt van het detail en de playground geeft een marker in de vorm van het antwoord, waar het
  `fixpointError` "een waarde van de vorm … toont het fixpunt niet" gaf.
- VB-6 (ander sum type): een sum type dat niet de vorm van markers heeft (alleen een IR-document van een andere
  producent, wiens `Match` uchars Drools-tak weigert) heeft geen vorm in de dienst: een waarde erop blijft de 400
  `UNSUPPORTED` van `ValueCoder`, in `output` weigert het de start, en de OpenAPI-emitter faalt erop.
- VB-7 (cellen): de cel van een gedimensioneerd attribuut met markers gaat door dezelfde functie; geen getuige, want uchars
  frontend herkent geen marker die een regel op een cel schrijft (gemeten: `Het gewicht in voorjaar van een Vondst :=
  onbekend :: onbekend(niet opgelost)`, een WARNING `onopgelost`).
- VB-8 (driver): `scripts/service_replay.py` veranderde mee: hij schrijft de verzoeken van de gate die de kaart noemt.

### Vragen

1. **Leeg als "onbekend".** In de uitleg staat leeg op een attribuut zonder markers als "onbekend" (README, het
   contract), ook in een model met markers: in deze golden "met zijn schatting (onbekend)" naast "zijn taxatie
   (onbekend)", de marker. Leeg overal "leeg" maken is een contractwijziging van elke uitleg. Doen?
2. **API die uchar mist (geen wijziging in uchar):** uchars scenariolaag heeft geen vorm voor een marker in een situatie
   of een verwachting (`ResolvedScenario.ScenarioValue`), dus een toetsdocument geeft er geen en de playground
   `?via=service` draagt er geen; en de frontend herkent een marker op een cel niet (VB-7). Een uchar-kaart?

### make gates

Eén run op `e03f474ab` (`logs/gates-1.log`); de commits daarna raken alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m05s  testgevallen per module 182/460/54
gates: 3 twins      GROEN      4s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m22s  331 goldens, 943 cases (pin 331)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     33s  decision-service:dev
gates: 7 kind-smoke GROEN   1m19s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m19s; de uitvoer staat in out/gates/
```
