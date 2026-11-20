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
