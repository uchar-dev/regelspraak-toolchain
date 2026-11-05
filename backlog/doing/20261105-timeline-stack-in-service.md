# timeline-stack-in-service: pin naar uchar 74119a23e, de stapel per dag in de dienst en de uitleg

*Opgesteld door de hoofdsessie op 2026-11-05, na uchar's kaarten `reading-and-uniqueness-residue` en
`timeline-initialisation-allocation-creation` (uchar `9df9df9ab`, `85d90f25d`, `74119a23e`).*

## Aanleiding

Sinds uchar `timeline-initialisation-allocation-creation` lezen alle uitvoerders, ook beide Drools-branches, de schrijvers van een
tijdsafhankelijk attribuut per dag in één stapel: regels, dan de invoer of de waarde bij het maken, dan de initialisatie, die de dagen vult
die de rest leeg laat, over alle tijd. Een verdeling met een tijdsafhankelijk attribuut is niet welgevormd (WF-128; in de frontend RGS-378,
in Drools DROOLS-315 als contract). Nieuwe Drools-code DROOLS-316 (een waarde op een tijdlijndoel die geen periode-rekenkunde of constructor
van een literal is); DROOLS-118 is `RETIRED`. Uit `reading-and-uniqueness-residue`: EF-016 noemt een verdeling bij haar naam, en een lezing
per periode draagt de fout die zij doorgeeft.

## Goal

- De pin naar uchar `74119a23e`.
- De dienst en de uitleg dragen de stapel per dag: een initialisatie en een waarde bij het maken op een tijdsafhankelijk attribuut in het
  antwoord, en in de uitleg per periode wie besliste (een regel, de invoer, de waarde bij het maken, de initialisatie). Wat uchar's
  `periodDecisionsOf` daarvoor geeft, lees je; een API die ontbreekt, wordt een vraag.
- De nieuwe codes (DROOLS-315, DROOLS-316) en de gepensioneerde (DROOLS-118) in wat de dienst met een weigering doet.

## Acceptance

- (a) Rood eerst: de nieuwe goldens van uchar (`jutterswet-initialisatie-per-dag`, `jutterswet-creatie-per-dag`,
  `jutterswet-afwezigheidsmarkers-initialisatie`) door de replay en met uitleg: vóór de bouw een uitsluiting, een fout of "niet vast te
  stellen", daarna de pinnen van uchar en een uitleg per periode.
- (b) De replay speelt die goldens (uit `REPLAY_EXCLUDED`, `EXPECTED_REPLAY_PLAYED` precies met het aantal dat speelt), of noemt ze met reden.
- (c) `make gates` groen; README en `docs/ENGINE.md` als huidige stand.

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/check-service-replay.sh` (lijst en pin), `scripts/service_replay.py`,
README, `docs/ENGINE.md`, kaartverplaatsing. Een API die uchar mist, wordt een vraag, geen wijziging in uchar.

## Omvang

Klein tot middel.

## Result
