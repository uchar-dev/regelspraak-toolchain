# pin-message-codes: de pin volgt uchar met meldingscodes, catalogi en nieuwe paden

*Vastgelegd op 2026-10-19. Hoort bij de uchar-stappen 7 (meldingscodes en catalogi) en 8 (paden). Toolchain `cc9b4f9`,
uchar-pin `e2343945` naar `476e0ff83`.*

## Aanleiding

Sinds de vorige pin kregen uchars meldingen codes en komt hun tekst uit een catalogus: de Drools-backend geeft standaard
Engels, de frontend Nederlands, gekozen met `uchar.messages.locale` of een `Locale` per aanroep. Verder heten
`corpus/negatief` nu `corpus/negative`, `corpus/rood` `corpus/red` en `bron.*` `source.*`, en zijn de case-slugs hernoemd.

## Wat er gebeurt

1. De pin naar `476e0ff83`; `make check-pin`.
2. De eigen modules compileren tegen de nieuwe API en herstellen wat breekt.
3. De paden in de eigen scripts en tests (`bron.rgs` naar `source.rgs`, `corpus/rood` naar `corpus/red`).
4. De standaardlocale `nl` op één plek in de distributie, met de CLI-vlag en `Accept-Language` als overschrijving.

## Acceptatie

- (a) `make gates` groen.
- (b) Onder `nl` blijft de meldingstekst voor gebruikers gelijk, behalve de zinnen die uchar bewust vertaalde.
- (c) De JSON- en OpenAPI-goldens blijven gelijk; een afwijking krijgt een oorzaak in het Result.

## Omvang

May touch: alles in de worktree behalve `uchar/` (alleen de pin), `examples/toka/*.rgs` en `specification/`.

## Result (2026-10-19)

**Pin.** `uchar` staat op `476e0ff83` (main van uchar na de stappen 7 en 8); `make check-pin` groen.

**Commits** (branch `card/pin-message-codes`, op `cc9b4f9`): kaart en `doing/`, de pin, `ContractValidator` geeft `Reason`
terug (`FactModelCliTest`), de paden, de standaardtaal, en twee commits voor de NoFixpoint-tests.

**Wat gevolgd werd.**
- Compilatie: één breuk. `ContractValidator.validate` geeft `List<Reason>` in plaats van `List<String>`; alleen
  `FactModelCliTest` las het.
- Paden: `bron.rgs` is `source.rgs` in `scripts/{check-twins.sh,check-service-replay.sh,service_replay.py,vocabulary_replay.py}`
  en in 12 Java-tests (`resolve("source.rgs")`, `uchar/corpus/golden/.../source.rgs`); `uchar/corpus/rood/` is
  `uchar/corpus/red/` in `NoFixpointTest` en `NoFixpointParityTest`. De sleutel `"bron.rgs"` in `Map.of(...)` van
  `DecisionCore.assemble` (eigen API) en de plaatsaanduiding `<bron.rgs...>` in README, `bin/regelspraak` en
  `docs/ENGINE.md` zijn zo gelaten. Geen golden- of case-slug in de distributie was hernoemd.
- De NoFixpoint-tests lazen `expectedFault.drools` als tekst; uchar pint nu `{code, params}`. Zij toetsen de code
  `EF-015` en het begin `creation budget exhausted: ` van de zin. In de Quarkus-variant was `asText()` van een object
  stil een lege string (de `startsWith` was dus waar); dat is nu een echte controle.
- Standaardlocale: `MessageLanguage` (toolchain, `engine`) zet `uchar.messages.locale` op `nl` tenzij het al gezet is.
  Zij wordt aangeroepen door `RegelSpraakCli`, `ScenarioRunnerCli`, `ServiceMain` en een statische initialisatie van
  `UcharBuild` (voor Quarkus en tests); zo krijgen frontend en Drools samen Nederlands. Overschrijven: `bin/regelspraak
  --language en <opdracht>` (JVM-eigenschap) en, in de playground van de service, `Accept-Language` (`en`), per
  verzoek via `Diagnostic.message(Locale)` en `UcharBuild.NotCarried.message(Locale)`.
- Beperking, bewust: `Accept-Language` geldt alleen voor wat de playground van uchar per aanroep rendert
  (resolverdiagnostics en de weigering van de build). Evaluatiefouten en de meldingen bij het opstarten (marker-
  schendingen) renderen in de taal van het proces, omdat uchar ze bij klasse-initialisatie of bij het bouwen vastlegt.
  De zinnen die de distributie zelf schrijft blijven Nederlands.

**`make gates`** groen in 9m35s op de eindstand: pin; reactor 1.622 / 140 / 332 / 53 testgevallen (1.619 plus drie die
uchar toevoegde); twins (3 tweelingen, 18 cases, vocabulairetweeling 2 cases, 4 getuigenissen); replay (225 goldens,
601 cases, pin 225); manifest (29 ok, 3 uitgesloten, 0 mismatch); image; kind-smoke (H2, H3, H4 PASS). Twee runs ervoor
vielen rood op `NoFixpointTest` (de bovenstaande pin van de rode case, eerst met een verkeerd JSON-pad).

**Meldingen onder `nl`.** Geen test of golden wijzigde in tekst, afgezien van het NoFixpoint-fragment hierboven (de
rode case pint geen zin meer). De JSON- en OpenAPI-goldens zijn niet aangeraakt en blijven gelijk. Bewust veranderd door
uchar: zinnen van de Drools-backend die eerder Engels waren en nu in de Nederlandse catalogus staan (behalve de ruim
twintig die uchar voorlopig Engels hield omdat zijn tests ze pinnen); de lijst staat in het Result van uchars kaart
`message-catalogues`. Een volledige vergelijking van elke zin voor en na de pin is niet gedraaid; de gelijkheid rust op
de ongewijzigde tests.
