# markers-in-test-documents: pin naar uchar 9c275fd8b, en een marker uit een toetsdocument in de playground en de dienst

*Opgesteld door de hoofdsessie op 2026-11-05, na uchar's kaart `absence-markers-residue` (uchar `fc347f1f3`, `9c275fd8b`).*

## Aanleiding

Sinds uchar `absence-markers-residue` schrijft een toetsdocument een marker als waarde ("de taxatie van V1 is onbekend"), in een situatie
en een verwachting; uchar's `ResolvedScenario.ScenarioValue` heeft daarvoor de componenten `constructor` en `payload`. Verder: de frontend
bouwt de knopen van het sum type nu in zijn model, een marker staat ook op een cel en in een objectcreatie, en WF-062 leest het argument
van een `Construct` als een toekenning.

Gemeten in uchar (Result van stap A van die kaart): `SituationToRequest.valueToJson` valt voor een constructorwaarde door naar
`NF.textNode(w.text())` met `w.text()` null, zonder fout, en `ScenarioData.value` gooit "uchars resolver gaf een waarde zonder variant".
Een toetsdocument met een marker gaat zo stil fout door de playground (`?via=service`) of faalt.

## Goal

- De pin naar uchar `9c275fd8b`.
- `SituationToRequest` en `ScenarioData` lezen een constructorwaarde: een marker wordt `{"marker": …}` in het verzoek, een waarde van een
  attribuut met markers de payload, ook in een cel en een segment; een vorm die de dienst niet kent, is een fout met naam, nooit stil.
- De playground speelt een toetsdocument met markers via de dienst, met dezelfde uitkomst als `--cases`.
- Een marker op een cel in verzoek, antwoord en uitleg, als uchar haar nu draagt.

## Acceptance

- (a) Rood eerst: het toetsdocument van uchar's golden `jutterswet-afwezigheidsmarkers` (`scenario.rgs`) via de playground of de dienst
  geeft vóór de bouw een fout of een verkeerde uitkomst, daarna de uitkomst van `--cases`.
- (b) De replay van `check-service-replay.sh` speelt de nieuwe goldens van uchar die de dienst kan dragen
  (`jutterswet-afwezigheidsmarkers-cel`, `-creatie`, `-kopie`), of noemt ze in `REPLAY_EXCLUDED` met hun reden.
- (c) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), `scripts/service_replay.py`,
README, `docs/ENGINE.md`, kaartverplaatsing. Een API die uchar mist, wordt een vraag, geen wijziging in uchar.

## Omvang

Klein tot middel.

## Result
