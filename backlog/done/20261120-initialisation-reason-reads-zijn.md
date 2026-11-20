# initialisation-reason-reads-zijn: de zin van een initialisatie zegt "zijn X", niet "de X"

*Opgesteld door de hoofdsessie op 2026-11-20, op besluit van de eigenaar, na open punt 1 van de kaart
`marker-as-opgave-in-service`.*

## Aanleiding

De uitleg van een initialisatie zegt waarom zij besliste, en schreef daarbij altijd "de" vóór het attribuut: "V heeft
als bergloon onbekend volgens 'bergloon zonder gewicht': onbekend, omdat de bergloon in de invoer leeg was", waar de
bron `het bergloon` declareert. `Verbaliser` schreef het lidwoord letterlijk op twee plekken (de initialisatie als
default van een groep in `reason`, en de initialisatie van een gewone regel). uchars model bewaart het lidwoord van een
objecttype en van een rol, niet dat van een attribuut, dus de toolchain kan het goede lidwoord niet opvragen.

## Goal

De zin luidt "omdat zijn <attribuut> in de invoer leeg was" en, op een object dat een objectcreatie maakte, "omdat zijn
<attribuut> bij de objectcreatie leeg was". De uitleg schrijft een lezing al zo ("zijn opgegeven teruggavedatum"), en
"zijn" klopt voor elk woord. Het gedeclareerde lidwoord tonen ("het bergloon") vraagt dat uchar het bewaart en is deze
kaart niet.

## Acceptance

- De twee plekken in `Verbaliser` schrijven "zijn"; elke test die de zin vastlegde, legt de nieuwe zin vast.
- README en `docs/ENGINE.md` als huidige stand.
- `make gates` groen.

May touch: `toolchain/`, `service/`, README, `docs/ENGINE.md`, kaartverplaatsing.

Out of scope: het kenmerk ("omdat het kenmerk … leeg was" blijft), en de terugval "de <attribuut>" en "de <parameter>"
voor een lezing zonder brontekst.

## Omvang

Klein.

## Result

*Hoofdsessie, 2026-11-20.*

- **Code** (`366f76a78`): `Verbaliser` schrijft "zijn " waar hij "de " schreef, op de twee plekken die de zin maken:
  de initialisatie als default van een groep (`reason`) en de initialisatie van een gewone regel. `emptyBefore` blijft
  de enige bron van "in de invoer leeg was" en "bij de objectcreatie leeg was".
- **Tests die de kaart omdraait**: twintig vastgelegde zinnen in zeven klassen, elk van "omdat de X …" naar "omdat zijn
  X …": `ExplanationComputationTest` (1), `ExplanationDefaultsComputationTest` (1), `ExplanationStackPerDayTest` (4),
  `ExplanationComputationServiceTest` (2), `ExplanationDefaultsComputationServiceTest` (2, waarvan één in de delen:
  `« de »` → `« zijn »`), `TimelineStackHttpTest` (9) en `TimelineHttpTest` (1). De meeste zinnen staan in de testcode
  over twee regels verdeeld; een patroon dat die verdeling toelaat, vindt na de wijziging geen oude zin meer in
  `toolchain/`, `service/`, `service-quarkus/`, README, `docs/`, `scripts/`, `examples/` en `tools/`.
- **Tekst**: README (twee voorbeeldzinnen en een regel in de lijst van contractwijzigingen) en `docs/ENGINE.md` (de
  vorm `omdat zijn <NAME> …` op drie plekken en twee voorbeeldzinnen).
- **Niet veranderd**: "omdat het kenmerk … leeg was", en de terugval "de <attribuut>" en "de <parameter>" voor een
  lezing zonder brontekst.
- **Voor de werkbank**: de zin verandert in elke uitleg van een initialisatie, ook zonder markers; het staat in
  `werk/marker-as-opgave-in-service/WERKBANK.md`.

`make gates` op `366f76a78`, groen (`werk/marker-as-opgave-in-service/logs/gates-3.log`): pin, reactor (206/585/62
testgevallen), twins, replay (374 goldens, 1057 cases), manifest, image en kind-smoke; `gates: GROEN — 7 stappen in
7m15s`. De commit hierna raakt alleen deze kaart.
