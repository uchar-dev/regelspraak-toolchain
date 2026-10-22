# pin-instance-cycle: de pin naar uchar met de instantiegeordende kring in de frontend

*Hoofdsessie, 2026-10-22: de laatste pin van het programma bruggen v2 + uitleg van die dag.*

## Aanleiding

uchar main `0866c03f` brengt sinds de pin `88c4624b` (T1):

- `instance-cycle-household-reads`: lezingen binnen het huishouden;
- `check-admits-instance-cycle`: `check` laat een kring over een schakelrelatie toe en noemt een lezing buiten de
  afbakening (RGS-357), plus een snippetfix: een snippet stopt vóór de slotpunt van de zin;
- twee clingo-fixes (CLINGO-030, de binding van een bewaking in een vouwlid);
- drie nieuwe goldens (`jutterswet-vergunningtermijnen`, `jutterswet-vroege-vondsten-zonder-schorsing` en de
  snippets van 38 goldens).

De werkbank heeft de toelating nodig voor K12 in v2, zodra hww-06 herschreven is (normvraag B2.5).

## Doel

De pin `uchar` → `0866c03f`; de replay-pin en de README-getallen volgen; wat in de toolchain een snippet met slotpunt
pint, volgt de fix. Geen nieuwe functionaliteit.

## Acceptatie

- (a) `make check-pin` PASS; de reactor groen; `check-service-replay` met de herrekende pin; elke aangepaste test en
  zin in de Result, met reden.
- (b) `make gates` groen (hoofdsessie).

May touch: de pin, `scripts/check-service-replay.sh` (de replay-pin), `README.md`, tests die een snippet of zin pinnen
die de pin bewust verandert, deze kaart.
