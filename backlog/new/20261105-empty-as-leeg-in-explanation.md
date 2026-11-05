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
