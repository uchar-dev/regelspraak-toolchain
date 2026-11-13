# number-scale-in-answer: een getal in het antwoord in de decimalen van zijn type

*Gevonden bij de site-update van uchar.dev op 2026-11-07 (toolchain `732cd328c`); door de eigenaar als kaart gevraagd.*

## Aanleiding

De dienst schrijft een getal in het antwoord met de schaal van de `BigDecimal` op de bean
(`service/.../AnswerBuilder.java` r. 209, `NF.numberNode(b)`), niet in de decimalen van het gedeclareerde type. Gemeten
op 2026-11-07 met `bin/regelspraak serve`:

- `jutterswet.service.json` met `service/src/test/resources/chain/jutterswet-strandvondsten.rgs`: `het bergloon Numeriek
  (niet-negatief getal met 2 decimalen)`, berekend als `vondstwaarde maal 0,25`, staat er als `"bergloon": 60.00`;
- de bronnen van uchars goldens `jutterswet-afwezigheidsmarkers` (`het vindloon Numeriek (getal met 2 decimalen)`) en
  `jutterswet-precisiepoort` (`het kwart Numeriek (getal met 2 decimalen)`, berekend als `zijn opbrengst gedeeld door
  4`): `"vindloon": 25` en `"kwart": 25`.

Twee attributen van hetzelfde type krijgen dus een verschillende schaal, afhankelijk van de berekening die de waarde
opleverde. Voor JSON zijn `25` en `25.00` hetzelfde getal, maar een afnemer die het antwoord met `BigDecimal` leest
(`equals` vergelijkt de schaal), of op tekst vergelijkt of toont, ziet het verschil. De tests vergelijken numeriek
(`assertNumber` met `compareTo`), dus geen test legt de schaal vast. README en `docs/ENGINE.md` beloven niets over de
schaal.

uchar schrijft een getal in tekst al in de decimalen van zijn type: RUNTIME-SEMANTICS ch. 1.2, "Text coercion" ("a
number of a finite bound writes the decimals of its type").

## Goal

Een getal van een type met een eindig aantal decimalen staat in het antwoord in precies die decimalen (`25.00`), in
`instances`, in tijdlijnen en cellen, en in `fixpoint`. Een getal van de onbegrensde vorm (`Numeriek (getal)`) blijft
zoals het nu is: exact als de waarde eindigt, anders de vorm die `docs/ENGINE.md` beschrijft.

## Besluit (2026-11-13, de eigenaar)

De decimalen van het type, gelijk aan uchars tekstvorm (RUNTIME-SEMANTICS ch. 1.2, "Text coercion").

## Acceptance

- Een HTTP-test die de schaal vastlegt (tekstvergelijking van het getal of `BigDecimal.equals`) voor een attribuut met
  2 decimalen dat via een vermenigvuldiging met een literaal (`maal 0,25`) en via een andere berekening een waarde
  krijgt; beide `…00`-vormen.
- Dezelfde toets voor een tijdlijnsegment en een cel.
- `docs/ENGINE.md` en de OpenAPI-beschrijving zeggen hoe een getal in het antwoord staat.
- `make gates` groen.

Result (2026-11-13):
- A number of a type with a fixed number of decimals stands in the answer in exactly those decimals, in `instances`,
  timeline segments, cells, the payload of an attribute with absence markers, and `fixpoint`
  (`AnswerBuilder.inDecimalsOf`, `setScale` with `UNNECESSARY`: a value that does not fit fails loudly, never rounds;
  uchar refuses such values earlier, EF-006/EF-004). A number of the unbounded form keeps its own scale. Witness
  `NumberScaleHttpTest` (`maal 0,25` → `60.00`, `gedeeld door 4` → `25.00`, input `240` → `240.00`, segments and cells
  in two decimals, an unbounded `25` stays `25`). The playground's fixpoint follows; the explanation already did.
- Over 429 goldens 181 of 1054 answers change in form only (425 numbers in 71 goldens), no value and no status;
  `docs/ENGINE.md`, README ("Contract change") and the OpenAPI descriptions say how a number stands. Not changed:
  `/info` shows parameter-set values as written.
- `make gates` green (replay 373/1042, red leg 69).
