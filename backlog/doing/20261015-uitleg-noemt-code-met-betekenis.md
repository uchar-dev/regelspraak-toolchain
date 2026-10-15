# uitleg-noemt-code-met-betekenis: een enumeratiewaarde met een afwijkende benaming staat in de uitleg met haar waarde

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `333c37c`.*

## Aanleiding

De werkbank toont per scenario de uitleg in het vocabulaire dat de gebruiker kiest. In het vocabulaire `cgm` van
herleving-ww (v2) heeft een enumeratiewaarde een code als benaming. Op s01 staat canoniek "… zijn code reden
beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Hoogte inkomsten uit
verzekerde arbeid of …", en in `cgm` "… (01) is gelijk aan 01 of … 02 …". De lezer ziet dan alleen de code; de zin wordt
er in `cgm` slechter op dan canoniek.

`Verbalisator.render` (r. 330–334) geeft voor een enumeratiewaarde `Naamgeving.waarde`: de benaming in het gekozen
vocabulaire, of de waarde zelf. Dat geldt voor de waarde tussen haakjes en voor een literal als vergelijkingswaarde
(`renderOperand`, r. 323–328).

## Wat er gebeurt

Noemt de uitleg een enumeratiewaarde in een vocabulaire waarin die waarde een benaming heeft die verschilt van de
gedeclareerde waarde, dan schrijft de Verbalisator "<benaming> · <gedeclareerde waarde>", bijvoorbeeld "01 · Hoogte
inkomsten uit verzekerde arbeid". Dat geldt overal waar de uitleg een enumeratiewaarde noemt, ook als
vergelijkingswaarde. Is de benaming gelijk aan de waarde zoals uchar gedeclareerde namen vergelijkt (`Namen.norm`), of
is er geen benaming, dan blijft de zin zoals nu. De canonieke keuze verandert niet.

Alleen de uitleg verandert. Het antwoord van de dienst, de OpenAPI-documenten en het lezen van een verzoek houden de
benaming alleen. De toolchain leidt niets af: de benaming en de gedeclareerde waarde zijn die van uchar.

## Acceptatie

- (a) Een nieuwe testklasse met een eigen klein document: een domein met benamingen `(cgm: 01)`, een waarde met een
  benaming die alleen in hoofdletters verschilt, en een waarde zonder benaming; de zinnen in `cgm` en canoniek gepind,
  de waarde tussen haakjes en de vergelijkingswaarde.
- (b) `README.md` noemt het, bij de vocabulaires.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbalisator.java`, een nieuwe
testklasse, `README.md`, kaartverplaatsing. Niet `uchar/`.

## Raakvlakken

- `20261012-verbalisator-getalliteral` gaat over de notatie van een getalliteral en de eenheid, niet over
  enumeratiewaarden. Die kaart blijft staan.
- De werkbank (`werkbank/static/`) toont de uitleg en wordt elders aangepast.
