# writer-holds-on-no-day: de uitleg van een schrijver van een tijdlijndoel wiens voorwaarde op geen dag geldt

*Opgesteld door de hoofdsessie op 2026-11-05, uit de open punten van `timeline-stack-in-service` (toolchain `4288f4d4a`). Gevraagd door
de eigenaar.*

## Aanleiding

Een schrijver van een tijdlijndoel (een regel, een default of een initialisatie) wiens voorwaarde per periode op geen enkele dag geldt,
heeft in uchar's lezing de status `HOLDS` met lege perioden (RUNTIME-SEMANTICS ch. 5.12). De uitleg (`Verbaliser`, de tak `HOLDS` rond
r. 1532) zegt dan wat zij zegt van een schrijver die wel gold maar een lege waarde gaf:

- `jutterswet-initialisatie-per-dag`, case3: "…; de regel 'standaardstormloon' gaf geen waarde en besliste niets";
- elders "… omdat hij stormwacht is (altijd leeg)" (gepind in `TimelineHttpTest`, r. ~400).

Dat is niet onjuist, maar het zegt niet wat er gebeurde: de schrijver gold op geen dag. Een lezer kan "gaf geen waarde" (de voorwaarde
gold, de waarde was leeg) zo niet onderscheiden van "gold op geen dag" (de voorwaarde gold nergens).

## Goal

- De uitleg onderscheidt een schrijver van een tijdlijndoel wiens voorwaarde op geen dag geldt: "de regel '…' gold op geen dag, want
  niet geldt: <de voorwaarde>" (de vorm van een scalaire regel die niet gold, met wat de lezing per periode geeft), voor een regel, een
  default en een initialisatie. "gaf geen waarde en besliste niets" blijft voor een schrijver die op een dag gold en leeg gaf.
- Eén plaats in `Verbaliser` die dat beslist uit de lezing (status en perioden), geen tweede pad per soort schrijver.

## Acceptance

- (a) Rood eerst: een test op `jutterswet-initialisatie-per-dag` case3 die "gold op geen dag" verwacht, faalt op main.
- (b) De tests die de oude zin voor dit geval pinnen (onder meer `TimelineHttpTest`), gaan naar de nieuwe: een expliciete gedragswijziging
  van deze kaart, elke test bij naam met oud en nieuw in het Result. Geen andere verwachting verandert.
- (c) `make gates` groen; de werkbank-meting van v2 (uitleg) zegt hoeveel zinnen veranderen.

May touch: `toolchain/`, `service/`, README, `docs/ENGINE.md`, kaartverplaatsing. Een lezing die uchar niet geeft, wordt een vraag, geen
wijziging in uchar.

## Omvang

Klein.

## Result
