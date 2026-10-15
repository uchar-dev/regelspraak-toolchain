# uitleg-gegenereerde-regel-in-vocabulaire: de uitleg van een regel die uchar schreef, noemt zijn operanden in het vocabulaire

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `9204c9a` en uchar `56dc218b`.*

## Aanleiding

uchar `56dc218b` geeft de leden van de opgave-familie een benaming per vocabulaire: `opgegeven X`, `berekende X`,
`afwijkende opgave X`, en de vormen `afwijking opgave X`, `controle opgave X` en `opgave boven berekening X`
(uchar `frontends/regelspraak/grammar/DIVERGENCES.md`, "Gegenereerde elementen", "Designations"). De toolchain pint
die uchar sinds `9204c9a`. Op de werkbank (s01-v01 van herleving-ww, vocabulaire `cgm`) zegt de uitleg nu:

> R1 heeft geen afwijkende opgave Datum einde recht op uitkering, want niet geldt: zijn opgegeven beëindigingsdatum (onbekend) gevuld is

Het kenmerk staat er in `cgm`, de operand in de voorwaarde canoniek; in `cgm` heet die operand "opgegeven Datum einde
recht op uitkering". De Verbalisator laat de brontekst van een operand staan zoals de regel hem schrijft (klassedoc
"Vocabulaire", r. 46–50; `omschrijf`, r. 429–436). Bij een regel van de auteur is dat juist. De regel
`afwijking opgave X` schreef de auteur niet: uchar maakt hem bij de lowering van de markering (DIVERGENCES §7;
`RuleChecker`, r. 2644–2660), met drie voorwaarden over `zijn opgegeven X` en `zijn berekende X`. Elke operand is een
lezing die uchar zelf spelt als `"zijn " + naam` (`RuleChecker.lezing`, r. 2694–2697). Die "brontekst" is uchars
canonieke tekst, geen tekst van een auteur.

## Wat er gebeurt

Bij een kenmerktoekenning die uchar schreef, schrijft de uitleg de naam van een gelezen attribuut in het gekozen
vocabulaire via `Naamgeving`, zoals de namen in de hoofdzin. In `cgm` wordt het "zijn opgegeven Datum einde recht op
uitkering (onbekend) gevuld is". De rest van uchars tekst ("zijn") blijft staan. Bij een regel van de auteur blijft
alles zoals nu.

**Herkennen.** Of uchar de regel maakte, staat in uchars model: `RegelSpraakModel.gegenereerdUit(Benoembaar)` en de
kaart `gegenereerd` (r. 72–78, r. 1361–1369), met de herkomstvorm `OPGAVE_BOVEN_BEREKENING` voor de opgave-familie.
De uitleg leest het daar, op `Benoembaar.vorm(<naam van de regel zonder versie>)`, en leidt het niet af uit de naam.

**Welk model.** De Verbalisator van de ScenarioRunner draait op `build.model()` (r. 187): het model dat uchars
IR-lader uit het IR-document leest. De kaart `gegenereerd` staat niet op de wire ("the emitter writes nothing of it,
so a model the IR loader reads leaves it empty", r. 75), dus daar is ze leeg. Het model van de frontend
(`irModel`, r. 93, een `kopie()` die de kaart meeneemt) heeft haar wel. De Verbalisator krijgt dat model als herkomst
naast het model dat hij uitlegt.

**Alleen de opgave-familie.** Ook een beslistabelrij is een gegenereerde regel (sleutel `beslistabelrij`), en een rij
kan een kenmerktoekenning zijn (`BeslistabelDesugar`, r. 199). Haar tekst komt echter uit de kolomkoppen en cellen van
de auteur, dus die blijft zoals hij geschreven is. De andere vormen van de tabel komen niet als kenmerktoekenning bij
de Verbalisator (`stappen-na-de-verzameling` berekent een lid, `uitzondering-voor-subtype` is een groep,
`startpunt` een attribuut).

**De naam in uchars tekst.** Een lezing die de IR-lader leest, draagt geen onderwerpwoord (`IrLoader`, r. 1436: de
`onderwerpBron` is null); "zijn" staat alleen in de tekst. De uitleg vervangt daarom in uchars tekst de gedeclareerde
naam van het gelezen attribuut door de naam via `Naamgeving.attribuut`. Staat die naam niet in de tekst, dan is dat
een wijziging in uchars lowering, en de uitleg gooit een `IllegalStateException` met de regel en de tekst.

**De dienst.** `BeslisserKern` (r. 614) legt uit op zijn eigen model. Uit bronnen is dat het model van de frontend,
met de kaart; uit een IR-document het geladen model, zonder. Daar blijven de operanden zoals uchar ze schreef. De
consistentie `controle opgave X` legt de Verbalisator niet uit; daar verandert niets.

## Acceptatie

- (a) Een nieuwe testklasse, eerst rood, met een eigen klein document met de opgave-markering en twee vocabulaires,
  waarin de uitleg van de afwijkingsregel de operand in elk vocabulaire noemt, en canoniek zoals nu.
- (b) In datzelfde document een regel van de auteur die hetzelfde gegenereerde attribuut leest; zijn uitleg verandert
  in geen vocabulaire.
- (c) `README.md` (Vocabularies) en de klassedoc van de Verbalisator noemen de uitzondering.
- (d) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbalisator.java`,
`…/engine/ScenarioRunner.java`, een nieuwe testklasse, `README.md`, kaartverplaatsing. Niet `uchar/`. Bestaande
testbestanden alleen na akkoord.

## Raakvlakken

- `20261015-uitleg-volgt-de-kenmerksoort` (done) schrijft het kenmerk in de hoofdzin al via `Naamgeving`; dat blijft.
- De werkbank (`werkbank/static/`) toont de uitleg en verandert niet.
