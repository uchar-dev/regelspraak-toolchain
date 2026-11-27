# role-and-existence-expectations-in-service: rol- en bestaansverwachtingen in scenarioloper en replay (geparkeerd)

*Geparkeerd vervolg op `uchar`-kaart `20261126-role-and-existence-expectations.md` (overgebracht uit de werkbank op
2026-11-27). Pas oppakken nadat die kaart in `uchar` op `main` is geland.*

Goal: pin `uchar` met `role-and-existence-expectations` en draag de nieuwe verwachtingssoorten (`roleEmpty`,
`roleCount`, `roleTarget`, `roleOpen` en wat uchar verder vaststelt, zoals `instanceCount`/`createdCount` of een
genavigeerde verwachting met `rolePath`) door de scenarioloper, de playground en `check-service-replay`, zonder eigen
semantiek.

Acceptance (bij oppakken aanscherpen):
- `scripts/service_replay.py` toetst de nieuwe verwachtingssoorten tegen het antwoord van de dienst; nu weigert het elke
  onbekende soort met `NotExpressible` (`verwachtingssoort <kind>`).
- Verschuift het aantal gespeelde of niet-uitdrukbare verwachtingen, dan staat de reden bij de pin
  (`EXPECTED_REPLAY_PLAYED`, `EXPECTED_REPLAY_CASES`).
- Pariteit tussen JDK- en Quarkus-adapter; alle 7 poorten (`make gates`) groen.

May touch:
- `uchar` (submodule-pin)
- `service/` (`AnswerBuilder.java`, `ScenarioData.java`, `SituationToRequest.java`, `playground.html` en tests)
- `service-quarkus/` (pariteitstests)
- `scripts/service_replay.py`, `scripts/check-service-replay.sh`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261126-role-and-existence-expectations-in-service.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek van de nieuwe verwachtingen (hoort in `uchar`-kaart `20261126-role-and-existence-expectations.md`)

## Beslispunt bij ontparkeren

- **Wat het antwoord van de dienst draagt:** `AnswerBuilder` geeft nu geen rollen van invoerlabels en geen gecreëerde
  instanties terug, dus de replay kan rolverwachtingen niet tegen het HTTP-antwoord toetsen.
  - *Optie A:* het antwoord geeft per invoerlabel de rollen terug (in een strikte regelset met de stand `open` of
    `empty`), en eventueel de gecreëerde instanties per ankerrol; gewone antwoorden en OpenAPI-goldens wijzigen dan
    alleen additief.
  - *Optie B:* het antwoord blijft zoals het is; de replay telt rolverwachtingen als niet-uitdrukbaar, met de reden bij
    de pin.
