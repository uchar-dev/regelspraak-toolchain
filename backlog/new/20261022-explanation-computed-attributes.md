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
