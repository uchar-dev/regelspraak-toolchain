# pin-subject-anchor: de pin naar uchar met het vormanker (`zelf`)

*Hoofdsessie, 2026-10-24, op verzoek van de eigenaar: de werkbank heeft het vormanker nodig voor hww-06.*

## Aanleiding

uchar main `57d0bb6a` brengt sinds de pin `f22c9ff5` de kaart `subselection-reads-subject`: een knoop in een filter of
een kwantortoets kan het onderwerp van zijn vorm lezen (`anchor: "form"`, `IdentityCheck`; IR-SPEC ch. 4.1a, WF-122), in
RegelSpraak "de X zelf"; de lowering over de weg terug is weg (RGS-348 en RGS-352 vervallen), drie goldens kregen het
anker, en er zijn vijf spelende goldens bij (`EXPECTED_PLAYED` van uchars gate 12: 274 → 279).

## Doel

De pin `uchar` → `57d0bb6a`; de replay-pin en de README-getallen volgen. Verder niets.

## Acceptatie

- (a) `make check-pin` PASS; `check-service-replay` met de herrekende pin, elke nieuwe speler en elke nieuwe uitsluiting
  met reden.
- (b) `make gates` groen.

May touch: de pin, `scripts/check-service-replay.sh` (de replay-pin, de lijst van uitsluitingen), `README.md`
(getallen), deze kaart.

## Result (2026-10-24)

Tak `card/pin-subject-anchor`, vanaf toolchain `ca496609` (de kaart). Hoofdsessie.

- `56e62153` de kaart naar `doing/`.
- Pin uchar `f22c9ff5` → `0321f96c`, replay-pin 244 → 249, README-getallen (`EXPECTED_PLAYED` 279,
  `EXPECTED_REPLAY_PLAYED` 249).
- Twee testmodellen: het kenmerk "zelf gemeld" heet "eigenhandig gemeld".

### Wat de pin onderweg vond

- **De uitleg gaf een interne fout op twee nieuwe goldens.** Op de eerste pin (`57d0bb6a`) gaf de dienst met
  `?explanation=true` een 500 op `anchor-forms` en `anchor-hub-own-hops`: `Verbaliser.foldText` zoekt de tekst van het
  filter in die van de vouw, en van elk lid in die van het filter, zoals de frontend ze schrijft, en die twee goldens
  hebben handgeschreven IR waarin de snippet van een vouw vóór haar filter stopt en drie samenstellingen alleen hun kop
  dragen. Hersteld in uchar, niet in de toolchain: `0321f96c` geeft de drie handgeschreven anker-goldens snippets in de
  vorm van de frontend (de volledige gate-set van uchar groen, 17 van 17), zoals uchar eerder de slotpunten in
  handgeschreven snippets rechtzette. Daarna spelen alle vijf de goldens van het vormanker door de dienst, met uitleg.
  Eén ouder document met een filtersnippet buiten de vouw, `synth2-clip-cond-over-prorata` (een tijdsfilter), raakt de
  uitleg niet en bleef staan.
- **De prijs van het sleutelwoord `zelf`.** Twee testmodellen van de toolchain declareerden het kenmerk "zelf gemeld";
  sinds uchars sleutelwoord parset die naam niet meer (de prijs die bij besluit B3 aanvaard is, uchar PLAN §9 punt 8).
  Hernoemd; de beweringen zijn dezelfde. In de regelsets van de werkbank staat "zelf" alleen in commentaar en
  scenarionamen.

### Gates

`check-service-replay`: 249 goldens, 673 cases, 3682 zinnen uitleg, elk gelijk aan haar stukken; de vloeren niet
verhoogd. `make gates` op de laatste commit met `TAG/CLUSTER=pin-subject-anchor`: GROEN, 7 stappen in 10m45s (reactor
1667/173/379/53, kind-smoke H2–H4 PASS). De eerste run was rood bij de reactor (de twee testmodellen hierboven).
