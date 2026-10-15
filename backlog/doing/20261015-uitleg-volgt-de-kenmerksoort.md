# uitleg-volgt-de-kenmerksoort: de uitleg schrijft een kenmerk met het werkwoord van zijn soort

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `6b14d1b`.*

## Aanleiding

De werkbank toont per scenario de uitleg van de Verbalisator. Op s01 van herleving-ww (v2) staat daar "R1 is niet
uitkeringsduur verstreken, want …". RegelSpraak geeft elk van de drie kenmerksoorten een eigen werkwoord (§3.5):

- bezittelijk krijgt "heeft": `de uitkeringsduur verstreken kenmerk (bezittelijk);` (regelspraak-rules,
  `v2/herleving-ww/rules/hww-09-verschuiving.rgs` r. 31), met de regel "Een RechtOpUitkeringWwBijOntslag heeft de
  uitkeringsduur verstreken" (r. 58); de scenario's schrijven "R1 heeft de uitkeringsduur verstreken" en "R1 heeft
  geen uitkeringsduur verstreken";
- zelfstandig krijgt "is een": `het recht op grond-van-de-wia kenmerk;` (`hww-12-wia.rgs` r. 13), met de regel "Een
  RechtOpUitkering is een recht op grond-van-de-wia" (r. 22);
- bijvoeglijk krijgt "is": `is waardevol … kenmerk (bijvoeglijk);` in de golden `jutterswet-vocabulaires` van uchar.

uchar volgt dat: het model draagt de soort in `Objecttype.kenmerkSoort(naam)`, en `ScenarioResolver.werkwoordPast`
weigert "is" bij een bezittelijk kenmerk. De Verbalisator schrijft daarentegen altijd "X is <kenmerk>" en "X is niet
<kenmerk>" (`verbaliseerToekenning`, r. 112 en 117), en een voorwaarde over een kenmerk als "het onderwerp (niet)
<kenmerk> is" (`verbaliseerAtoom`, r. 150–157), ook als falende conjunct na "want niet geldt:".

## Wat er gebeurt

Waar de uitleg een kenmerk als gezegde schrijft, volgt het werkwoord de soort van de declaratie:

| soort | aanwezig | afwezig |
|---|---|---|
| bijvoeglijk | V1 is waardevol | V1 is niet waardevol |
| bezittelijk | R1 heeft de uitkeringsduur verstreken | R1 heeft geen uitkeringsduur verstreken |
| zelfstandig | R1 is een recht op grond-van-de-wia | R1 is geen recht op grond-van-de-wia |

Dat geldt voor de zin over een toegekend of niet toegekend kenmerk (hoofdzin) en voor een voorwaarde over een kenmerk
van het onderwerp of van een rol (bijzin: "het onderwerp de uitkeringsduur verstreken heeft", "de samenloop geen
wia-recht heeft"), dus ook voor de falende conjunct. Wat de uitleg tussen aanhalingstekens uit de regel citeert,
blijft zoals het geschreven is.

Het lidwoord bij een aanwezig bezittelijk kenmerk: het model draagt het lidwoord van de declaratie niet. Een
`Characteristic` in de IR heeft `name`, `kind`, `timeline` en `designations` (uchar `spec/IR-SPEC.md` ch. 2.2), en
`DefinitionResolver.kenmerkNaam` neemt de naam zonder `bepaaldLidwoord`. De uitleg neemt daarom het lidwoord dat de
regeltekst zelf vóór de kenmerknaam schrijft: voor de hoofdzin de conclusie van de regel ("… heeft de uitkeringsduur
verstreken"), voor een voorwaarde de tekst van die voorwaarde. Schrijft de regeltekst er geen, dan de naam zonder
lidwoord. De Result verantwoordt die keuze.

De soort is die van de declaratie, ook in een ander vocabulaire; de naam is de benaming via `Naamgeving`, zoals nu.
Is de soort van een kenmerk in een voorwaarde niet vast te stellen, dan citeert de uitleg de voorwaarde, zoals bij
elke vorm die de Verbalisator niet structureert.

Alleen de uitleg verandert. De toolchain leidt de soort niet af: die is van uchar.

## Acceptatie

- (a) Een nieuwe testklasse met een eigen klein document met de drie soorten: per soort een toegekend en een niet
  toegekend kenmerk, een kenmerk als voorwaarde (aanwezig en ontkend) en als falende conjunct, canoniek en in een
  vocabulaire.
- (b) `README.md` noemt de vorm, bij de vocabulaires en de uitleg.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbalisator.java`, een nieuwe
testklasse, `README.md`, kaartverplaatsing. Niet `uchar/`. Bestaande testbestanden alleen na akkoord.

## Raakvlakken

- `20261012-verbalisator-getalliteral` gaat over de notatie van een getalliteral en de eenheid, niet over het
  werkwoord. Die kaart blijft staan.
- `20261015-uitleg-noemt-code-met-betekenis` (done) zet een code met haar waarde in de uitleg; dat blijft zo.
- De werkbank (`werkbank/static/`) toont de uitleg en wordt elders aangepast.
