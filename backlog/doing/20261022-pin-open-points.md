# pin-open-points: de pin naar uchar met de open punten van K12, en de dode slotpuntregel weg

*Hoofdsessie, 2026-10-22.*

## Aanleiding

uchar main `a6eed482` brengt sinds de pin `0866c03f`:

- de rest van een instantiegeordend deel wordt ook op een kring getoetst (verifier en frontend; negatief
  `wf045-instance-cycle-positive-remainder`);
- in zes handgeschreven goldens snippets zonder slotpunt;
- de golden `jutterswet-termijnoverdracht`.

`Verbaliser.java` (`definitions`) haalt een slotpunt van de snippet van een `Let` af
(`.replaceAll("\\.$", "")`). Sinds de snippetfix van uchar (`check-admits-instance-cycle`) draagt geen snippet die
frontend schrijft de slotpunt van de zin, en op de pin `0866c03f` had geen Let-snippet in het corpus er nog een. De
regel herstelt dus stil iets wat niet meer voorkomt.

## Doel

De pin `uchar` → `a6eed482`; de replay-pin volgt; de afkapping van de slotpunt in `definitions` en de woorden "zonder de
punt erachter" in haar commentaar gaan weg. Verder niets.

## Acceptatie

- (a) `make check-pin` PASS; de reactor groen; `check-service-replay` met de herrekende pin; de uitleg op de replay
  bytegelijk aan die op `98af8f64` voor elke golden die er al was (de afkapping deed niets meer).
- (b) `make gates` groen (hoofdsessie).

May touch: de pin, `scripts/check-service-replay.sh` (de replay-pin), `README.md` (getallen),
`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` (`definitions`), deze kaart.
