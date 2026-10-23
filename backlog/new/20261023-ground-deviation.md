# ground-deviation: de grondslag leest een deviatie met haar lagen en de laag die de regelset volgt

*Voorstel vanuit de werkbank (2026-10-23), door de eigenaar doorgegeven aan de toolchain-sessie; tekst van de werkbank,
ongewijzigd op deze kop na (de slug van de titel in het Engels: `ground-deviation`). Vervolg op `explanation-parts`
en `explanation-ground-source`.*

## Aanleiding

"Afwijking" betekende in het werk aan v2 drie dingen: de dialectvorm `Afwijking van …` van uchar (een variant in een
defaults-groep), een vermelding in het afwijkingenregister van de wetsanalyse (in een `// Bron:`-regel als
`afwijking:` of `afwijking, niet gevolgd:`), en een genummerde keuze van v2 tegen de modelkaart. In de Uitleg-tab van
de werkbank staan de eerste twee naast elkaar. De eigenaar besliste (2026-10-23):

- de dialectvorm houdt haar naam;
- een vermelding in het register heet voortaan een **deviatie** (het register: het deviatieregister);
- de `// Bron:`-regel zegt tussen welke lagen de deviatie zit en welke laag de regelset volgt, in plaats van
  "gevolgd" of "niet gevolgd". De impliciete vorm `afwijking:` (= gevolgd) leidde in hww-06 tot een verkeerde
  vermelding: v2 rekent met maandlonen zoals de wet, maar de regel noemde de BRM-afwijking op daglonen als gevolgd.

Het register noemt bij elke vermelding de lagen (`lagen`: `wet→hb`, `wet→brm`, `hb→brm`, `intern brm`, of meer paren
tegelijk). De nieuwe vorm in de `// Bron:`-regel:

```
deviatie wet/hb, volgt hb: het-overlijden-als-alsof-uitsluitingsgrond
deviatie wet/brm, volgt wet: de-herlevingsgrens-op-daglonen
deviatie binnen brm: de-bezwaartermijn-met-drie-getallen
```

De eerste laag van het paar is die waarvan de tweede afwijkt, zoals de pijl in het register. Een deviatie binnen één
laag heeft geen paar en geen gevolgde laag; de toelichting in dezelfde regel zegt welke regel van die laag v2 volgt.

## Doel

- De grammatica van de grondslag (`engine/Ground`, `docs/ENGINE.md` "The grammar of the ground") leest:
  ```
  deviation = "deviatie " layer "/" layer ", volgt " layer ": " slug
            | "deviatie binnen " layer ": " slug                     (only after the lemma head, as now)
  layer     = 1*( lowercase letter )
  ```
  De laagcodes kiest de wetsanalyse (nu `wet`, `hb`, `brm`); de toolchain kent ze niet en toetst ze niet. De oude
  vormen `afwijking: ` en `afwijking, niet gevolgd: ` vallen weg en worden, zoals elk ander segment, een `NOTE`.
- Het stuk `DEVIATION` krijgt als meta `between` (de lagen van het paar, of de ene laag bij "binnen", als lijst) en
  `follows` (de gevolgde laag; afwezig bij "binnen") in plaats van `followed`. De woorden van de vorm zijn `TEXT`, zoals
  nu. De toolchain kiest de precieze sleutels en zegt waarom.
- De parser blijft totaal en structureel: of de gevolgde laag in het paar zit en of paar en slug in het register
  staan, toetst de lint van de werkbank (`lint-benamingen`), niet de toolchain. Wil de toolchain een `follows` buiten
  het paar toch weigeren of als `NOTE` lezen, dan zegt de kaart waarom.
- `explanation` en de tekst van de stukken blijven bytegelijk; alleen de soort en meta van de stukken veranderen.
  OpenAPI (`ExplanationPart`), `ContractNamesInMessagesTest`, `docs/ENGINE.md` (de tabel van de stukken en de
  grammatica) bij.

## Acceptatie

- (a) Een eigen klein model met de drie vormen hierboven, een `follows` buiten het paar, en de twee oude vormen: de
  stukken zoals de grammatica zegt, de oude vormen als `NOTE`.
- (b) Op de vijf service-verzoeken van herleving-WW v2 en s01 (regels nog met de oude vormen): `explanation` bytegelijk;
  elk `DEVIATION`-stuk van nu is een `NOTE` geworden, met dezelfde tekst; de telling in de Result.
- (c) `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

De pin-bump samen met de omzetting: het deviatieregister in de wetsanalyse, de 23 vermeldingen in de Bron-regels van
v2 naar de nieuwe vorm, B20 in de modelkaart, de lint (paar in het register, gevolgde laag in het paar), `ontleedBron`,
de Uitleg-tab en de naam-popover. Tot die pin-bump ziet de werkbank de oude vormen als `DEVIATION`, zoals nu.
