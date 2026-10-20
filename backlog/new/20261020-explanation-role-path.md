# explanation-role-path: de uitleg noemt de waarde die een operand over een rol bereikt

*Vastgelegd op 2026-10-20, na de meting van de eval-runner op de werkbank. Regelnummers op toolchain `21d9956` en uchar
`60979bf2`.*

## Aanleiding

De werkbank stuurt scenario s01 van de v2-regelset herleving-ww (descriptor `service/herleving.service.json`) naar de
dienst met `?explanation=true`. Van de twintig uitlegzinnen bevatten er veertien "(onbekend)". Negen daarvan zijn
terecht: de waarde is leeg of niet opgegeven. Vijf zijn fout, want het antwoord leidt de waarde wel af (`herlevingsgrens`
van R1 is 2625.0, `maximeringsfactor` van D1 is 1):

> I4 heeft de herlevingsgrens overschreden, omdat zijn inkomen (2.800,00 EUR) gevuld is en de herlevingsgrens van het
> recht van het Inkomen in-een-kalendermaand (onbekend) gevuld is en zijn inkomen maal de maximeringsfactor van het
> dagloon van het recht van het Inkomen in-een-kalendermaand (onbekend) is groter dan de herlevingsgrens van het recht
> van het Inkomen in-een-kalendermaand (onbekend)

(ook I5 en I6), en

> I7 heeft geen herlevingsgrens overschreden, want niet geldt: de herlevingsgrens van het recht van het Inkomen
> in-een-kalendermaand (onbekend) gevuld is

(ook I8). De eerste zin spreekt zichzelf tegen; de tweede noemt een voorwaarde die wel geldt, en niet de vergelijking
die faalt. De eval-runner geeft de uitleg als motivering door aan een taalmodel, dus zo'n zin doet echte schade.
Toolchain `97d25bb` gaf letterlijk dezelfde zinnen; de fout is ouder dan de Engelse hernoeming.

Alle vijf lezen een attribuut over een rolpad vanaf de instantie. De Verbalisator rekent de voorwaarden zelf na tegen
de teruggeschreven beans, en leest een attribuut alleen op het onderwerp:
`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` r. 282
(`ref.navigationRole() == null`) en r. 304 (`return null` voor "navigatie-reads"). `renderOperand` (r. 355) maakt
van die null "onbekend", en `evaluate` (r. 227) maakt van "leeg is gevuld" onwaar.

Gemeten met een eigen klein model is er een tweede fout, op de route van de scenario-runner (`bin/regelspraak scenario
--explanation` en de playground van de werkbank): die legt uit op het model dat uchars IR-lader las, en daar draagt
een lezing haar navigatie niet. De wandeling staat in de tabel `navigationBindings` naast het model (uchar
`IrLoader`, r. 1481–1493), privé in de transpiler. De Verbalisator leest zo'n lezing daar als een lid van het onderwerp
zelf; heeft het onderwerp een lid met dezelfde naam, dan staat de waarde van het onderwerp in de zin. Hetzelfde geldt
voor de dienst die uit een `uchar-ir-v1`-document start.

## Wat er gebeurt

De uitleg toont bij een operand over een rol de waarde op het object dat de operand bereikt: na de terugschrijving de
waarde die de motor afleidde. Welk object dat is, zegt uchar, zoals zijn exec-model-tak de lezing las; de toolchain
schrijft geen navigatie na. Daarvoor vraagt de kaart uchar-kaart `navigated-read-on-beans` (tak
`card/navigated-read-on-beans`): `TmsTranspiler.navigationBindings(read)` geeft de wandeling van een lezing van het
model van een build, en `DroolsBackend.reached(model, subject, subjectType, read, walk)` het object dat de wandeling
vanaf een bean bereikt, null waar een link ontbreekt. De wandeling komt per herkomst van het model:
- een model dat de frontend resolvede (de dienst uit bronnen, `RegelSpraakEngine`): `IrEmitter.navigationBindings`,
  de wandeling die uchars emitter op de draad schrijft;
- het model van een build (de scenario-runner): `TmsTranspiler.navigationBindings`;
- een model van `IrLoader.loadAll` (de dienst uit een document): de tabel `navigationBindings()` die de lader teruggaf.

Een waarde die echt leeg is, of een object dat niet bereikt wordt, blijft "(onbekend)". Een wandeling die uchar
weigert, gaat met uchars zin door (fail fast).

## Acceptatie

- (a) Rood eerst: een nieuwe test met een eigen klein model naar het patroon van s01 (een maandinkomen leest de
  herlevingsgrens van zijn recht en de maximeringsfactor van het dagloon van zijn recht) pint de zinnen op de route van
  de scenario-runner, met een geval waarin de waarde leeg is en een zonder recht, en een variant waarin het onderwerp
  een lid met dezelfde naam heeft. Dezelfde zinnen in de dienst, uit bronnen en uit het document.
- (b) Bestaande tests veranderen alleen waar het gedrag door deze kaart verandert; geen verwachting wordt losser.
- (c) Bewijs op de echte regelset: de dienst van de v2-regelset op een eigen poort, het s01- en het s03-verzoek met
  `?explanation=true`; de zinnen van I4 tot en met I8 noemen de afgeleide waarden, de andere zinnen blijven gelijk.
- (d) `make gates` groen op de eindstand van de kaarttak.

## Omvang

Middelgroot, over twee repo's. Eerst de uchar-kaart `navigated-read-on-beans`; deze kaart pint haar tak lokaal. May
touch: `toolchain/…/engine/Verbaliser.java`, `…/engine/ScenarioRunner.java`, `service/…/DecisionCore.java`, nieuwe
testklassen, `README.md`, de pin van `uchar/`, kaartverplaatsing. Niet `uchar/` zelf.

## Raakvlakken

- `20261012-verbalisator-getalliteral` (new): de eenheid van een waarde in de uitleg. Op de route van de lader draagt
  een lezing geen type, en staat een waarde zonder eenheid; dat blijft voor die kaart.
- Een kenmerkcheck over een rol (`Condition.CharacteristicCheck` met navigatie) valt buiten deze kaart; zie het Result.
