# empty-as-leeg-in-explanation: de uitleg noemt een lege waarde overal "leeg"

*Opgesteld door de hoofdsessie op 2026-11-05, na `absence-markers-in-service` (toolchain `b52176d0d`). Besluit van de eigenaar,
2026-11-05.*

## Aanleiding

De uitleg (`Verbaliser`) schrijft een lege waarde op een attribuut zonder markers als "(onbekend)" ("met zijn schatting (onbekend)"), en
sinds `absence-markers-in-service` op een attribuut met markers als "(leeg)", omdat `onbekend` daar een marker is: een gevulde waarde die
beslist. In één uitleg staan zo "zijn schatting (onbekend)" (leeg) en "zijn taxatie (onbekend)" (de marker) naast elkaar.

Besluit van de eigenaar (2026-11-05): leeg heet in de uitleg overal "leeg", zoals RegelSpraak zelf ("is leeg"); "onbekend" is alleen de
marker. Gemeten op werkbank-v2 (`uchar-dev/werk/pin-d208bbfa/werkbank-meting.md`): 5 122 van de 8 790 uitlegzinnen schrijven een lege
waarde als "(onbekend)", samen 7 809 keer; 187 zinnen worden "(leeg) leeg is".

## Goal

- De uitleg schrijft een lege waarde overal als "leeg", in de kop, als gelezen waarde, per periode en in de playground en het detail
  waar die de uitleg tonen. Een marker `onbekend` blijft "onbekend".
- Waar een tekst een lege waarde als "onbekend" noemt in een andere zin dan die van de uitleg (de dienst, de CLI), meet je dat en neem
  je het mee als het dezelfde lege waarde is; het antwoord (`null`) verandert niet.
- README en `docs/ENGINE.md` als huidige stand, zonder "where it read …" of "where it was …".

## Acceptance

- (a) Rood eerst: een test die op een lege waarde "(leeg)" verwacht, faalt op main.
- (b) De tests die "(onbekend)" voor een lege waarde pinnen, gaan naar "(leeg)": een expliciete gedragswijziging van deze kaart, met elke
  test bij naam en oud en nieuw in het Result. Geen andere verwachting verandert.
- (c) `make gates` groen.

May touch: `service/`, `service-quarkus/`, `toolchain/`, README, `docs/ENGINE.md`, kaartverplaatsing.

## Omvang

Klein.

## Result

*Bouwer, 2026-11-05. Niet gemerged, niet gepusht. Logs onder `werk/empty-as-leeg/logs/`, het script dat de pinnen omzette
`werk/empty-as-leeg/scripts/flip_pins.py` (buiten de repo).*

### Commits

| hash | inhoud |
|---|---|
| `c9dbd2365` | kaart naar `doing/` |
| `603e7867b` | `Verbaliser`: één woord voor leeg (`EMPTY`), het pad per sum type weg; 28 tests van "(onbekend)" naar "(leeg)"; nieuwe test `AbsenceMarkerHttpTest.an_empty_value_is_leeg_on_an_attribute_without_markers_too` |
| `54345d16d` | README en `docs/ENGINE.md` |
| (deze) | Result |
| (laatste) | kaart naar `done/` |

### Wat er veranderde

- **Uitleg** (`Verbaliser`). Het woord voor een lege waarde staat op één plaats, de constante `EMPTY = "leeg"`, die
  `renderRead` (een waarde die de motor las), `renderValue` en `renderPeriods` ("altijd leeg") gebruiken. De tak
  `typeOf(e) instanceof Datatype.SumType ? "leeg" : "onbekend"` in `renderRead` is weg: leeg is "leeg" op elk
  attribuut, met en zonder markers; "onbekend" is alleen de marker. Gedrag verandert alleen in `renderRead`, op een
  operand zonder sum type (`renderValue` en `renderPeriods` schreven al "leeg"). Daarmee ook: de `VALUE` met `empty:
  true` in `explanationParts`, de playground en het detail, die dezelfde zinnen tonen. Het antwoord (`null`) is
  ongewijzigd.
- **Andere teksten** (gemeten, `git grep` over `toolchain/`, `service/`, `service-quarkus/`, `bin/`, `scripts/`): geen
  eigen tekst buiten de `Verbaliser` noemt een lege waarde "onbekend". De playground schrijft al `leeg`
  (`playground.html`), de OpenAPI "null (leeg)", uchars `CaseRunner` (de CLI-uitvoer van `scenario`) "verwacht leeg,
  werkelijk …". Niets meegenomen.
- **Javadoc in tests**: `ExplanationReadingsTest` ("a comparison with an empty operand ("(leeg)")"),
  `ExplanationRolePathTest` en `ExplanationRolePathServiceTest` ("the operand stands as "(leeg)"", waar "…"(onbekend)"
  stays" stond); hun zin over de stand vóór de kaart `explanation-role-path` houdt "(onbekend)", want dat was die stand.
- **README** ("Contract change", item Absence markers): de uitleg zonder "where it wrote", "where it read", "where it
  was", "where it stood"; een nieuw item "Empty is leeg"; "Running": een lege waarde staat als "(leeg)" op elk
  attribuut. **`docs/ENGINE.md`**: de `VALUE`-rij (`empty: true` … "leeg", op elk attribuut) en de alinea Absence
  markers (`"zijn taxatie (leeg) is gelijk aan onbekend"`).

### Rood eerst (letterlijk)

De tests omgezet en de nieuwe test toegevoegd, op de code van main (`logs/01-red-tests.log`): toolchain `Tests run: 182,
Failures: 11`, service `Tests run: 461, Failures: 18`; elk van de 29 is een omgedraaide test of de nieuwe. De nieuwe:

```
AbsenceMarkerHttpTest.an_empty_value_is_leeg_on_an_attribute_without_markers_too
geen zin bevat "zijn schatting (leeg)":
V1 is niet kostbaar, want niet geldt: zijn taxatie (leeg) een waarde heeft
V1 heeft geen taxatie, want de regel 'taxatie uit schatting' gaf geen waarde (onvoorwaardelijk): zijn schatting, met
zijn schatting (onbekend); de regel 'taxatie onbekend' gold niet, …
```

Daarna is de verwachting de hele bijzin met "(leeg)" (`"V1 heeft geen taxatie, … met zijn schatting (leeg); de regel
'taxatie onbekend' gold niet"`) en geen "(onbekend)" in de uitleg; beide falen op main om hetzelfde woord. Na de bouw
(`logs/02-tests.log`): 182/461, 0 failures, skip-pin PASS.

### Omgedraaide tests (gedragswijziging van deze kaart, besluit van de eigenaar)

In elke test wordt elke gepinde "(onbekend)" van een lege waarde "(leeg)", en in de twee parts-pinnen
`VALUE{empty=true}«onbekend»` `VALUE{empty=true}«leeg»`. Niets anders in de verwachtingen veranderde (50 regels, alle
van die vorm). Oud → nieuw per test, de gepinde stukken:

| test | oud | nieuw |
|---|---|---|
| `ExplanationComputationTest.every_computed_value_gets_a_sentence` | `zijn tarief (onbekend)`, `met zijn startdag (onbekend)`, `<FIRST> (onbekend): er is geen enkel lid` (2×), `<SUM> (onbekend): er is geen enkel lid` | dezelfde met `(leeg)` |
| `ExplanationComputationTest.a_rule_that_gives_no_value_decides_nothing_and_the_input_stands` | `zijn opgave (onbekend)` (4×) | `zijn opgave (leeg)` |
| `ExplanationDefaultsComputationTest.the_deciding_variant_gets_its_computation` | `zijn opgegeven waarde (onbekend) gevuld is` (2×), `zijn berekende waarde (onbekend) gevuld is`, `zijn taxatie (onbekend) gevuld is` (2×) | dezelfde met `(leeg)` |
| `ExplanationGeneratedRuleInVocabularyTest.canonical_uchar_text_is_there_as_uchar_writes_it` | `zijn opgegeven meldingsdatum (onbekend) gevuld is` (2×) | `… (leeg) gevuld is` |
| `ExplanationGeneratedRuleInVocabularyTest.in_cgm_names_the_generated_rule_the_operands_in_cgm` | `zijn opgegeven meldingsdatum (onbekend) gevuld is`, `zijn opgegeven Datum melding vondst (onbekend) gevuld is` | `… (leeg) gevuld is` |
| `ExplanationGeneratedRuleInVocabularyTest.in_handbook_names_the_generated_rule_the_operands_in_handbook` | `zijn opgegeven meldingsdatum (onbekend) gevuld is`, `zijn opgegeven dag van de melding (onbekend) gevuld is` | `… (leeg) gevuld is` |
| `ExplanationGeneratedRuleInVocabularyTest.on_the_model_of_the_frontend_reads_the_uitleg_the_origin_there` | `zijn opgegeven meldingsdatum (onbekend) gevuld is`, `zijn opgegeven Datum melding vondst (onbekend) gevuld is` | `… (leeg) gevuld is` |
| `ExplanationOriginFromIrTest.on_the_model_from_the_ir_document_names_the_uitleg_the_operand_in_the_vocabulary` | idem | `… (leeg) gevuld is` |
| `ExplanationReadingsTest.every_form_family_is_explained_from_the_readings_of_the_engine` | `zijn aantal meldingen (onbekend) is groter dan 10`, `… (onbekend) is groter dan 3` | `… (leeg) is groter dan …` |
| `ExplanationRolePathTest.an_operand_over_a_role_names_the_value_the_engine_derived_on_the_object_it_reaches` | `zijn maandloon (onbekend) gevuld is`, `de herlevingsgrens van het recht van het Maandinkomen (onbekend) gevuld is` (2×) | `… (leeg) gevuld is` |
| `ExplanationRolePathTest.an_operand_over_a_role_does_not_read_the_member_of_the_same_name_on_the_subject` | idem | `… (leeg) gevuld is` |
| `ExplanationComputationServiceTest.from_sources_every_computed_value_gets_a_sentence`, `.from_a_document_every_computed_value_gets_a_sentence` | als `ExplanationComputationTest.every_computed_value_gets_a_sentence`, en het part `READING[« (» VALUE{empty=true}«onbekend» «)»]` van `zijn tarief` | `(leeg)`, `VALUE{empty=true}«leeg»` |
| `ExplanationComputationServiceTest.a_rule_outside_the_scope_is_named_as_outside_the_computation` | de zinnen van `EXPECTED`, als `ExplanationComputationTest.every_computed_value_gets_a_sentence` | `(leeg)` |
| `ExplanationComputationServiceTest.a_rule_that_gives_no_value_decides_nothing_and_the_input_stands` | `zijn opgave (onbekend)` (4×) | `zijn opgave (leeg)` |
| `ExplanationDefaultsComputationServiceTest.from_sources_the_deciding_variant_gets_its_computation`, `.from_a_document_the_deciding_variant_gets_its_computation` | als `ExplanationDefaultsComputationTest` | `(leeg)` |
| `ExplanationGroundSourceServiceTest.from_sources_the_ground_names_its_decider`, `.from_a_document_the_ground_names_its_decider`, `.a_default_without_a_name_is_named_by_its_group_and_place` | `zijn opgegeven meldingsdatum (onbekend) gevuld is` (helper `notDiffering`) | `… (leeg) gevuld is` |
| `ExplanationPartsServiceTest.from_sources_every_sentence_is_its_parts`, `.from_a_document_every_sentence_is_its_parts` | `zijn leeftijd (onbekend) is groter dan de minimumleeftijd (17)`, `READING[« (» VALUE{empty=true}«onbekend» «)»]` | `zijn leeftijd (leeg) …`, `VALUE{empty=true}«leeg»` |
| `ExplanationPartsServiceTest.the_parts_come_only_with_their_flag_and_leave_the_answer_as_it_was` | `zijn leeftijd (onbekend) is groter dan de minimumleeftijd (17)` | `zijn leeftijd (leeg) …` |
| `ExplanationReadingsServiceTest.from_sources_every_form_family_is_explained_from_the_readings`, `.from_a_document_every_form_family_is_explained_from_the_readings`, `.a_rule_outside_the_scope_is_named_once_as_outside_the_computation` | als `ExplanationReadingsTest` | `(leeg)` |
| `ExplanationRolePathServiceTest.from_sources_an_operand_over_a_role_names_the_value_the_engine_derived`, `.from_a_document_an_operand_over_a_role_names_the_value_the_engine_derived` | als `ExplanationRolePathTest` | `(leeg)` |

28 tests: 11 in `toolchain`, 17 in `service`. Ongewijzigd: `AbsenceMarkerHttpTest.the_explanation_tells_an_empty_value_from_the_marker_onbekend`
(pinde al "(leeg)" en houdt `assertFalse(… "(onbekend) is gelijk aan onbekend")`, de marker).

### Pins

- Suites: toolchain 182, service 460 → 461 (de nieuwe test), service-quarkus 54; de vloeren van `tools/check-skips.sh`
  blijven. Replay 331 goldens, 943 cases (pin 331), tweelingen en manifest: ongewijzigd.

### Vragen

1. **"(leeg) leeg is".** Een toets op leeg met de lezing ernaast leest nu "zijn X (leeg) leeg is" (op werkbank-v2 187
   zinnen, volgens de kaart), en "zijn X (leeg) gevuld is" waar het leeg is. Een marker in een vergelijking staat één
   keer; moet de lezing naast een toets op leeg of gevuld ook wegvallen? Niet gedaan: geen verwachting van deze kaart.
2. **"where it was" elders in de README.** De lijst "Contract change" beschrijft bij andere items (en bij het verzoek en
   het antwoord van het item Absence markers) nog de oude vorm met "where it was" (onder meer regels 142, 146, 175,
   186, 190, 225, 228). Alleen de uitleg van het item Absence markers is nu huidige stand. Die lijst als geheel
   herschrijven: een eigen kaart?

### make gates

Eén run op `54345d16d` (`logs/gates-1.log`); de commits daarna raken alleen deze kaart.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m13s  testgevallen per module 182/461/54
gates: 3 twins      GROEN      3s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m21s  331 goldens, 943 cases (pin 331)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     37s  decision-service:dev
gates: 7 kind-smoke GROEN   1m18s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 5m29s; de uitvoer staat in out/gates/
```
