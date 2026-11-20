# marker-as-opgave-in-service: pin naar uchar 2e54dd254, en een marker als opgave in verzoek, antwoord, uitleg en OpenAPI

*Opgesteld door de hoofdsessie op 2026-11-20, op verzoek van de eigenaar, na uchar's kaart `absence-markers-with-opgave`
(landing `2e54dd254`).*

## Aanleiding

Sinds uchar `2e54dd254` draagt een attribuut in RegelSpraak een afwezigheidsmarkering en de opgavemarkering samen, de
afwezigheidsmarkering eerst:

    de teruggavedatum Datum in dagen (of onbekend of niet van toepassing) (mag opgegeven worden);
    de opslagkosten Numeriek (geheel getal) voor elke maand (of onbekend) (mag opgegeven worden);

`opgegeven X` en `berekende X` dragen dan de markers en het sum type van X, over het ene domein van X
(`uchar/frontends/regelspraak/grammar/DIVERGENCES.md` §7 en "Afwezigheidsmarkeringen"; `uchar/spec/RUNTIME-SEMANTICS.md`
ch. 9.5, "An opgave above a computation"). Besluiten van de eigenaar van 2026-11-20: een opgave mag zelf een marker
zijn, voor elke marker die X declareert; een opgave die verschilt van een gevulde berekening is een afwijkende opgave,
ook waar een van de twee een marker is (een datum tegenover een marker, een marker tegenover een datum, twee
verschillende markers); twee gelijke markers wijken niet af, en een opgave naast een lege berekening geeft geen melding.
De groep `opgave boven berekening X` geeft het beslissende lid ongewijzigd door, een marker inbegrepen. De golden
`jutterswet-opgave-afwezigheidsmarkers` legt het vast in vijftien cases, scalair en per maand, met een berekende datum,
een berekend getal en een initialisatie onder de opgave. In dezelfde landing: een getal dat in een andere eenheid in
een attribuut met markers wordt geschreven, staat er in de gedeclareerde eenheid (ch. 9.1).

De werkbank vraagt dit voor de herlevingsdatum van v2/herleving-ww: een regel schrijft `niet van toepassing` als de
herleving is uitgesloten, en een medewerker kan een datum of een marker opgeven. Daarvoor moet de dienst een marker als
opgave ontvangen, haar teruggeven en uitleggen.

Gelezen vóór de bouw, niet gemeten: `Markers.of` herkent een attribuut met markers aan `ObjectType.markersOf`, of aan
een sum type met `waarde` voorop; na de pin dragen de twee gegenereerde leden die markers, dus verzoek, antwoord en
OpenAPI volgen misschien zonder codewijziging. De uitleg (`Verbaliser`) van de default die beslist en van de regel
`afwijking opgave X` met een marker aan een van beide kanten is het onzekere deel, net als de replay.

## Goal

- De pin naar uchar `2e54dd254`.
- **Verzoek.** `opgegeven X` neemt op een attribuut met markers een gewone waarde of `{"marker": <naam>}`, ook als
  `value` van een segment van een tijdlijn. Een marker die X niet declareert en een marker op een `opgegeven X` zonder
  markers zijn de bestaande fouten met naam (400).
- **Antwoord.** X, `berekende X` en, waar het antwoord haar toont, `opgegeven X` in de bestaande vorm voor een attribuut
  met markers.
- **Uitleg.** De zin van de default die beslist ("opgegeven waarde", "berekende waarde", de initialisatie) en de zin
  van `afwijkende opgave X` noemen een marker bij zijn naam, ook waar de opgave een marker is en de berekening een datum
  of omgekeerd.
- **OpenAPI.** `opgegeven X` krijgt de vorm van een attribuut met markers (`oneOf` van het payloadtype en het
  markerobject met de gedeclareerde markers).
- **Replay en scenario.** `scripts/check-service-replay.sh` speelt de nieuwe golden door de dienst, en
  `bin/regelspraak scenario` speelt haar `scenario.rgs`.

## Acceptance

- (a) Meet eerst, op de nieuwe pin: wat doen verzoek, antwoord, uitleg, OpenAPI, de replay en het scenariocommando met
  de golden `uchar/corpus/golden/jutterswet-opgave-afwezigheidsmarkers`? Wat fout is of ontbreekt, staat eerst rood in
  een test.
- (b) Een HTTP-test op de bron van de golden: een opgegeven marker boven een berekende datum en een opgegeven datum
  boven een berekende marker, elk met de afwijking in het antwoord en in de uitleg; dezelfde marker opgegeven en
  berekend zonder afwijking; een opgegeven marker per maand.
- (c) `check-service-replay.sh`: de golden speelt en de gepinde aantallen volgen; een vorm die de dienst bij naam
  weigert, staat met reden in de lijst.
- (d) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `uchar` (alleen de pin), `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh`
(lijsten en pins), `tools/check-skips.sh` (floors), README, `docs/`, kaartverplaatsing.

Out of scope: uchar zelf. Een fout in uchar of een API die uchar mist, komt met een proef in het Result; de hoofdsessie
lost dat in uchar op. De werkbank (regelspraak-rules, de eval-dataset) valt er ook buiten.

## Omvang

Klein tot middel; de meting van (a) bepaalt het.
