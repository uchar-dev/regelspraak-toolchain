# explanation-computed-attributes: een uitkomst die een gewone regel berekent, krijgt een zin

*Punt 5 van de werkbank-tekst van 2026-10-22 (`werknotities/20261022-eval-uitleg-varianten/toolchain-tekst-uitlegstukken.md`),
door de eigenaar doorgegeven aan de toolchain-sessie. Het ontwerp gaat eerst naar een Fable-advies
(`uchar-dev/research/uitleg-berekende-waarden-2026-10-22/`); het aanbevolen antwoord wordt een gemarkeerd voorlopig
besluit in deze kaart.*

## Aanleiding

17 van de 50 `output`-elementen van `beoordeel-herleving` (v2-regelset herleving-WW) krijgen op de 18 gemeten
antwoorden nooit een zin, onder meer: van `Eindigingsgrond` de berekende datum aanvang en de berekende einddatum; van
`RechtOpUitkeringWwBijOntslag` de herlevingsgrens, het maandloon, de eerste kalendermaand met overschrijding, de eerste
kalendermaand zonder overschrijding na-de-eindiging, de berekende maxdatum, de datum einde basisuitkering bij herleving,
de datum einde verschoven-periode-uitkeringspercentage 75, de resterende uitkeringsduur, de verlengde maxdatum en de
bezwaartermijn bij intrekking; van `RechtOpUitkeringPeriode` de berekende herlevingsdatum en de maxdatumverschuiving;
`Uitkeringsduur.berekende uitkeringsduur`, `Dagloon.maximeringsfactor` en `Maandloon.berekende bedrag maandloon`.

De Verbalisator (`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java`) verantwoordt
kenmerken en defaults-groepen; een attribuut dat een gewone regel berekent, ook met "indien" ('datum einde
basisuitkering bij herleving art. 43 lid 1', 'herlevingsgrens art. 21 lid 2 onder a'), krijgt geen zin. Gevolg: een
keten stopt bij zo'n waarde ("… is groter dan de herlevingsgrens … (2.625,00 EUR)", zonder hoe die grens ontstond), en
vraagt het model naar de maxdatum na de herleving (s01-v02, s02-v03, s03-v02), dan krijgt het met reikwijdte=keten geen
enkele zin.

## Doel

Elke uitkomst die een regel van de regelset schrijft, krijgt een zin: welke regel, de waarde, en waarom (de voorwaarde,
uit de lezing van ch. 5.12), en zo dat de keten verder kan naar de waarden waar de berekening van afhangt. De vorm volgt
uit het Fable-advies (onder meer: volstaan de waarden aan het fixpunt plus de geschreven expressie, of vraagt dit van
uchar een lezing van de waarde-expressie naast die van de voorwaarde).

## Acceptatie

Na het advies aan te scherpen. Minimaal: op de vijf service-verzoeken en s01 krijgt elk van de 17 genoemde
uitkomsten, waar het antwoord haar een waarde geeft, een zin; de bestaande zinnen blijven bytegelijk; `make gates`
groen.

## Voorlopige besluiten (2026-10-22, naar het Fable-advies)

Bron: `uchar-dev/research/uitleg-berekende-waarden-2026-10-22/fable-advies.md` (§2–§7). De eigenaar kan elk besluit
omdraaien.

- **De premisse klopt maar half.** Voor een regel op een attribuut mét voorwaarde legt ch. 5.12 de knopen van de waarde
  al vast (tussenwaarden, bijdragers van een vouw, eenheid per knoop); de Verbalisator leest ze alleen niet. Een vorm
  zonder voorwaarde heeft geen lezing: dat levert uchar-kaart `readings-of-unconditional-forms` (U4); de snippets van
  `percentageOf` repareert uchar in de frontend (U5, mee in `check-admits-instance-cycle`). Deze kaart start na de
  pin-bump die beide meebrengt.
- **B1 Reikwijdte:** elke waarde die een gewone regel schrijft, ook leeg; de keten stopt bij invoer, parameter, opgave
  en een regel buiten de scope.
- **B2 Eén zin per (object, attribuut)** over alle schrijvers, in de vorm van de defaults-zin, met vier vormen: een
  regel gold (A), gold en gaf geen waarde (B), geen regel gold en leeg (C), geen regel gold en een invoerwaarde (D).
- **B3** Waarden alleen uit `readingsOf`; geen bean-lezing voor een berekende waarde, geen rekenaar.
- **B4 Tussenwaarden:** de lezingen, de parameters, de binders, de ingebouwde functies en de vouwen met hun bijdragers,
  elk één keer met waarde, plus "vóór de afronding" en "vóór de begrenzing"; geen andere `Binary`-tussenwaarden.
- **B7 Nieuwe lijm:** "volgens de regel '…':", "met …", "vóór de afronding", "vóór de begrenzing", "want de regel '…' gaf
  geen waarde", "want geen regel gold", "verdrongen: de regel '…'".
- Ontwerp en technische keuzes: §5 T1 en §7 van het advies (`ComputationExplanation` naast `DefaultsExplanation`, groepen
  `COMPUTATION` en `VARIANT` in de stukken, nieuwe zinnen per object na de defaults-zinnen, een tijdlijn-doel als "een
  tijdlijn" zonder berekening). Acceptatie zoals §5 T1 (a)–(e), met de vloer `EXPECTED_MIN_EXPLAINED_COMPUTATIONS` in
  `check-service-replay.sh`.
- `explanation-part-groups` (punt 2, `VARIANT`) en deze kaart delen de groep `VARIANT`: wie eerst bouwt, legt haar vast.
