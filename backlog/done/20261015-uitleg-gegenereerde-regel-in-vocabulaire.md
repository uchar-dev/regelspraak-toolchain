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

## Result (2026-10-15)

### Wat de kaart opleverde

Regelnummers van de toolchain op `270d9be`.

- **Herkennen** (`Verbalisator.doorUcharGeschreven`, r. 158): een kenmerktoekenning is door uchar geschreven als
  `herkomst.gegenereerdUit(Benoembaar.vorm(RegelSpraakModel.vormnaam(r.naam())))` de herkomstvorm
  `OPGAVE_BOVEN_BEREKENING` geeft. De uitleg leest dat in uchars tabel, niet in de naam: de testklasse heeft een regel
  van de auteur die `afwijking opgave vinddag` heet en `zijn opgegeven meldingsdatum` leest, en die houdt zijn tekst.
- **Welk model.** De Verbalisator heeft een vierde constructorparameter `herkomst` (r. 93): het model waarvan de tabel
  van herkomst zegt welke regels uchar schreef. De ScenarioRunner geeft daar `irModel` (het model van de frontend,
  r. 166 en 191), want `build.model()` komt van uchars IR-lader en heeft de tabel leeg. De constructors met twee en
  drie parameters nemen het uitgelegde model zelf als herkomst; daardoor werkt het ook in `BeslisserKern` als de dienst
  uit bronnen start (dat model komt van `Resolver.resolve`). Getoetst met `RegelSpraakEngine`, die ook op het model van
  de frontend uitlegt.
- **Schrijven** (`inVocabulaire`, r. 473, vanuit `omschrijf`, r. 457): in uchars tekst van een lezing ("zijn opgegeven
  beëindigingsdatum") vervangt de uitleg het laatste voorkomen van de gedeclareerde attribuutnaam, als hele woorden en
  hoofdletterongevoelig, door `Naamgeving.attribuut`. Staat de naam er niet in, dan gooit hij een
  `IllegalStateException` met de naam en de tekst. Een operand die geen lezing van een attribuut is, houdt zijn tekst;
  de regel van de opgave-familie heeft er geen.
- **Beslistabelrij.** uchar noemt een rij ook gegenereerd (`beslistabelrij`), maar haar tekst komt uit de kolomkoppen
  en cellen van de auteur. Zij valt buiten de filter en houdt haar tekst: "V1 is zwaar, omdat zijn gewicht (12) is
  groter dan 10", ook in `cgm`, waar `gewicht` "Gewicht vondst" heet. Zonder de filter zei de uitleg "zijn Gewicht
  vondst (12)"; dat is nagegaan door de filter tijdelijk weg te halen (de test werd rood) en terug te zetten.
- **Gedeeld patroon.** `lidwoordVoor` en `inVocabulaire` zoeken een gedeclareerde naam in een tekst op dezelfde manier;
  dat patroon staat nu in `naamPatroon` (r. 529) en `GRENS`, zonder verandering van `lidwoordVoor`.
- **Tekst.** De klassedoc van de Verbalisator ("Vocabulaire") en `README.md` (Vocabularies) noemen de uitzondering en
  waarom een beslistabelrij er niet onder valt.

### Getoetst op echte invoer

Het s01-document van de werkbank (checkout op `dff7184`): de bronnen van `/api/eval` plus het scenariodocument van de
replay, samengevoegd zoals `evalDelen` het doet, via `POST /api/analyse?vocabulaire=…`. Vóór op de draaiende werkbank
(toolchain `9204c9a`, poort 8089), na op een tweede werkbank op deze branch (`270d9be`, poort 18489, analyzer 18493).
In s01-v01 en s01-v02 zijn status (PASS), aantal zinnen (20) en `uitlegFout` (geen) gelijk. Er veranderen alleen
zinnen van de regel `afwijking opgave X`, en daarin alleen de operand in de bijzin; s01-v02 verandert gelijk aan
s01-v01.

`cgm`, 4 van de 20 zinnen:

| vóór | na |
|---|---|
| R1 heeft geen afwijkende opgave Datum einde recht op uitkering, want niet geldt: zijn opgegeven beëindigingsdatum (onbekend) gevuld is | … want niet geldt: zijn opgegeven Datum einde recht op uitkering (onbekend) gevuld is |
| R1 heeft geen afwijkende opgave Datum einde basisuitkering WW bij ontslag, want niet geldt: zijn opgegeven maxdatum (onbekend) gevuld is | … want niet geldt: zijn opgegeven Datum einde basisuitkering WW bij ontslag (onbekend) gevuld is |
| G1 heeft geen afwijkende opgave Datum einde eindigingsgrond recht op uitkering, want niet geldt: zijn opgegeven einddatum-van-een-eindigingsgrond (onbekend) gevuld is | … want niet geldt: zijn opgegeven Datum einde eindigingsgrond recht op uitkering (onbekend) gevuld is |
| P1 heeft geen afwijkende opgave Datum herleving recht op uitkering, want niet geldt: zijn opgegeven herlevingsdatum (onbekend) gevuld is | … want niet geldt: zijn opgegeven Datum herleving recht op uitkering (onbekend) gevuld is |

`handboek`, 2 van de 20 zinnen:

| vóór | na |
|---|---|
| R1 heeft geen afwijkende opgave ingangsdatum van de eindiging, want niet geldt: zijn opgegeven beëindigingsdatum (onbekend) gevuld is | … want niet geldt: zijn opgegeven ingangsdatum van de eindiging (onbekend) gevuld is |
| P1 heeft geen afwijkende opgave dag van herleving, want niet geldt: zijn opgegeven herlevingsdatum (onbekend) gevuld is | … want niet geldt: zijn opgegeven dag van herleving (onbekend) gevuld is |

Canoniek: 0 van de 20. De overige afwijkingszinnen (in `cgm` U1 `uitkeringsduur`, M1 `bedrag maandloon`, G1
`datum aanvang eindigingsgrond recht op uitkering`; in `handboek` daarnaast `maxdatum` en
`einddatum-van-een-eindigingsgrond`) blijven canoniek, in het kenmerk en in de operand: het attribuut heeft in dat
vocabulaire geen benaming.

### Testwijzigingen

- Nieuw: `toolchain/…/engine/UitlegGegenereerdeRegelInVocabulaireTest` (5 tests), met een eigen document: `Vocabulaire
  cgm` en `Vocabulaire handboek`, een `meldingsdatum` met een benaming in beide en `(mag opgegeven worden)`, een
  berekening ervan, en de regel van de auteur `afwijking opgave vinddag` die `zijn opgegeven meldingsdatum` leest; een
  scenario met een Vondst met een afwijkende opgave en een zonder. Gepind: alle vier zinnen canoniek, in `cgm` en in
  `handboek`; de beslistabelrij in `cgm` (eigen klein document); en de uitleg met de drie-parameterconstructor op het
  model van de frontend.
- Rood zonder de wijziging: 3 van de 4 eerst geschreven tests (`cgm`, `handboek`, het model van de
  frontend); de canonieke test pint het huidige gedrag. De beslistabeltest kwam erbij na de wijziging en is rood
  zonder de filter op de herkomstvorm.
- Geen bestaand testbestand veranderd.

### Poorten

`make gates` op `270d9be`: groen, 7 stappen in 9 min 7 s.
- pin: uchar op `56dc218b`;
- reactor (5 min 42 s): 1578 tests in uchars backend, 139 in toolchain (was 134, plus de 5 nieuwe), 269 in service
  en 47 in service-quarkus;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS.

### Open

- Een dienst die uit een IR-document start (`BeslisserKern`, het pad met `IrLoader.loadAll`), heeft de tabel van
  herkomst niet: de wire draagt haar niet. Daar staan de operanden van `afwijking opgave X` er zoals uchar ze schrijft.
  De tabel op de wire zetten is een vraag voor uchar.
- Een citaat uit een regel die uchar schreef ("de voorwaarde \"…\" niet is vast te stellen") blijft canoniek. Bij
  `afwijking opgave X` komt dat niet voor: een `gevuld`-voorwaarde is altijd te evalueren, en de vergelijking komt pas
  aan de beurt als beide operanden gevuld zijn.
- De werkbank: `make werkbank` schrijft de analyzer naar het vaste pad `/tmp/werkbank-analyzer.log`. Een tweede
  werkbank kapt daarmee het log van de eerste af (de analyzer op 8092, PID 84819, schrijft naar hetzelfde bestand).
  Het Makefile van de werkbank is niet aangepast; de checkout is alleen-lezen voor deze kaart.

### Commits

`2b134d8` kaart · `75bbd4b` claim · `270d9be` de Verbalisator, de ScenarioRunner, de nieuwe testklasse en de README ·
deze Result en de verplaatsing naar `done/`.
