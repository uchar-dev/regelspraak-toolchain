# explanation-fold-members: de uitleg noemt de leden van een kwantorvouw die de uitkomst dragen

*Vastgelegd door de hoofdsessie op 2026-10-21, open punt van `explanation-from-readings`. Besluit van de eigenaar
2026-10-21: de beslissende leden, met het aantal (keuze uit drie: beslissende leden, alleen aantallen, alle leden met
oordeel). Tweede stap: de pin-bump naar de uchar-kaart `fixpoint-inconsistency-order`.*

## Aanleiding

Sinds `explanation-from-readings` staat een kwantorvouw ("alle vondsten van de Jutter waardevol zijn", "ten minste één
van de eindigingsgronden … de vrijstelling heeft") in de uitleg als `aan de voorwaarde "…" is voldaan` of `… is niet
voldaan`, met het oordeel van de motor (`Verbaliser`, r. ±859 en ±877). Welk lid de uitkomst gaf, staat er niet, terwijl
de lezing het levert: de knoop van de vouw draagt zijn `ConditionReading.Contributor`s, de leden van het bereik die door
het filter komen, elk met het oordeel van de matrix (of een fout). In de vijf v2-verzoeken komt de vorm 10 keer voor
(`werk/defaults-decider/na3/`).

## Doel

Na de bestaande zin van de vouw een dubbele punt en de leden die de uitkomst dragen, met het aantal; m is het aantal
leden (contributors), k het aantal waarvoor de matrix geldt:

| kwantor | voldaan | niet voldaan |
|---|---|---|
| `all` | "voor alle m" | "niet voor <de leden waarvoor zij niet geldt> (m − k van de m)" |
| `none` | "voor geen van de m" | "wel voor <de leden waarvoor zij geldt> (k van de m)" |
| `atLeast`, `atMost`, `exactly` n | "voor k van de m (<de leden waarvoor zij geldt>)" | idem |

Bij k = 0 in een telling: "voor geen van de m (<alle leden>)". Bij m = 0: "(geen enkel lid)". De leden staan bij hun
label, zoals de uitleg een onderwerp al noemt (een gemaakt object zoals de uitleg het noemt), in code-unit-volgorde van
dat label, met "en" voor het laatste; boven vijf de eerste vijf en "en nog k". Het oordeel per lid komt uit de lezing,
ook voor een lid na de stop van ch. 5.10 (besluit van de eigenaar: "ook als de motor na het eerste al wist dat het niet
gold"); een lid met een fout telt niet mee als geldend en wordt niet genoemd, tenzij de vouw zelf op die fout faalt (dan
de bestaande foutzin). Voorbeeld, Jutter met vondsten V1 en V3 waardevol, V2 en V4 niet:

    want aan de voorwaarde "alle vondsten van de Jutter waardevol zijn" is niet voldaan: niet voor V2 en V4 (2 van de 4)
    want aan de voorwaarde "ten minste één van de vondsten … waardevol is" is niet voldaan: voor geen van de 4 (V1, V2, V3 en V4)

Stap 2, na de landing van de uchar-kaart `fixpoint-inconsistency-order`: de pin naar die landingscommit, en de leg van de
uitleg in `scripts/check-service-replay.sh` vergelijkt de inconsistenties weer in volgorde (uchar-acceptatie (c)).

## Acceptatie

- (a) Rood eerst: per rij van de tabel een zin op de drie routes (scenario-runner, dienst uit bronnen, dienst uit een
  IR-document), plus k = 0, m = 0 en meer dan vijf leden.
- (b) Bestaande uitlegtests ongewijzigd groen, behalve de benoemde pins van een vouw, elk met reden.
- (c) Bewijs op v2: `measure.sh` naar `na4/`; de 10 vouwzinnen van `na3/` met hun leden, verder elke zin gelijk aan
  `na3/`, het antwoord buiten de uitleg gelijk; in `bewijs.md`.
- (d) Stap 2: pin, `make check-pin`, de leg in volgorde, `make gates` groen (door de hoofdsessie).

May touch: `toolchain/.../engine/Verbaliser.java`, de tests van de uitleg, `scripts/check-service-replay.sh` (stap 2),
de pin (stap 2), deze kaart.

Out of scope: de uitleg van een aggregatie met een bereik (som, telling als waarde); een leesbare zin per lid uit de
matrix.
