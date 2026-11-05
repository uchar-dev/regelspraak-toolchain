# fixpoint-creation-value: het fixpunt toont op een gemaakt object de waarde bij het maken

*Opgesteld door de hoofdsessie op 2026-11-05, uit de open punten van `timeline-stack-in-service` (toolchain `4288f4d4a`). Een fout die
in die landing had moeten meegaan.*

## Aanleiding

Sinds uchar `timeline-initialisation-allocation-creation` is de waarde die een objectcreatie op een attribuut zet, per dag de invoer van
het gemaakte object (rang 2 van de stapel): een regel die op een dag beslist gaat ervoor, op de andere dagen blijft de waarde bij het maken
staan. Het antwoord van de dienst geeft dat goed, en de uitleg noemt het "de waarde bij het maken". Het fixpunt (`?fixpoint=true`, in detail
en playground; `FixpointGivens`) geeft op een gemaakt object alleen de afgeleide tijdlijn, zonder de waarde bij het maken die op een deel
van de dagen bleef staan; op een object uit het verzoek geeft het daar `input` (r. ~295) en `origin`.

## Goal

- Het fixpunt geeft op een attribuut van een gemaakt object de waarde bij het maken waar zij stond, in de vorm die het voor de invoer van
  een object uit het verzoek geeft (`input`, per segment op een tijdlijn), met een `origin` die zegt dat het de waarde van het maken is
  (de bestaande herkomst `creation` waar die past). Scalair en op een tijdlijn, en op een cel als die vorm daar bestaat.
- Wat uchar daarvoor geeft (`Fixpoint.objects`, `PeriodDecisions.Span.input`) lees je; een API die ontbreekt, wordt een vraag.

## Acceptance

- (a) Rood eerst: `?fixpoint=true` op uchar's golden `jutterswet-creatie-per-dag` (een gemaakt object met een waarde bij het maken naast een
  regel op een deel van het jaar) mist op main de waarde bij het maken; een test die haar verwacht, faalt.
- (b) Geen andere verwachting verandert; een bestaande pin die de oude vorm vastlegt, gaat mee met oud en nieuw in het Result.
- (c) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, README, `docs/ENGINE.md`, kaartverplaatsing.

## Omvang

Klein.

## Result
