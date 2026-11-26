# strict-timelines-in-service: tijdlijnen in strikte regelsets in de beslisservice (geparkeerd)

*Geparkeerd vervolg op `uchar`-kaart `20261126-strict-timelines.md`. Pas oppakken nadat die kaart (of haar relevante deelkaarten) in `uchar` op `main` is geland.*

Goal: pin `uchar` met `strict-timelines` en draag tijdsafhankelijke attributen en kenmerken in een strikte regelset (`Afleiden uit leeg: nee`) door de beslisservice (`JDK` en `Quarkus`), uitleg, `?openInputs=true`, OpenAPI, `?fixpoint=true`, scenarioloper/playground en `check-service-replay`.

Acceptance (bij oppakken aanscherpen):
- **Verzoek & antwoord (`RequestMapper`, `AnswerBuilder`, `OpenValues`):**
  - In een strikt verzoek ondersteunt een tijdsafhankelijk attribuut naast gevulde segmenten ook expliciet vaststaand lege segmenten (bijv. `{"from": ..., "to": ..., "state": "empty"}`), en ondersteunt een tijdsafhankelijk kenmerk expliciet geldende én niet-geldende perioden (`holds` / `holdsNot`); niet-genoemde perioden blijven *open* tenzij het lid `(volledig gegeven)` is.
  - Het strikte antwoord geeft per tijdlijnlid in `output` de vaststaande gevulde, vaststaand lege en open segmenten uit `EndState` terug.
  - Gewone regelsets houden hun bestaande `timeline`- en `periods`-contract byte-identiek.
- **Uitleg (`Verbaliser`) & `?openInputs=true` (`OpenReads`, `OpenInputs`):**
  - Verwoordt per periode waarom een tijdlijnuitkomst of een scalaire vouw over een tijdlijn openstaat (bijv. in welk tijdvak een invoertijdlijn niet gegeven is).
  - `?openInputs=true` noemt het tijdsafhankelijke invoerlid (en desgewenst de open periode(n) waar `ConditionReading` die aanreikt) dat een open uitkomst tegenhoudt.
- **OpenAPI, `fixpoint`, playground & replay:**
  - Strikte OpenAPI-schema's, `?fixpoint=true`, playground-weergave en `scripts/service_replay.py` bijgewerkt; alle 7 poorten (`make gates`) groen.

May touch:
- `uchar` (submodule-pin)
- `toolchain/` (`OpenReads.java`, `Verbaliser.java`)
- `service/` (`OpenValues.java`, `RequestMapper.java`, `AnswerBuilder.java`, `OpenInputs.java`, `FixpointGivens.java`, `OpenApiEmitter.java`, `ScenarioData.java`, `SituationToRequest.java`, `playground.html` en tests)
- `service-quarkus/` (pariteitstests)
- `scripts/service_replay.py`, `scripts/check-service-replay.sh`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261126-strict-timelines-in-service.md` → `doing/` → `done/`)

Out of scope:
- `uchar`-semantiek (hoort in `uchar`-kaart `20261126-strict-timelines.md`)
