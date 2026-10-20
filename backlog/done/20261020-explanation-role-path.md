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

## Result (2026-10-20)

### Oorzaak

De Verbalisator rekent de voorwaarde van een kenmerktoekenning zelf na tegen de teruggeschreven beans, en las een
attribuut alleen op de bean van het onderwerp. Regelnummers op main `21d9956`:
- `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` r. 282: `evalExpr` leest een
  `Expr.AttributeReference` alleen als `ref.navigationRole() == null`, en dan op `bean`, het onderwerp. Een lezing met
  navigatie viel door naar r. 304, `return null` ("navigatie-reads … niet zonder verdere sessie te bepalen").
- r. 355: `renderOperand` schrijft die null als "onbekend"; r. 239–246: `evaluate` maakt van "null gevuld" onwaar,
  zodat `firstFailingConjunct` (r. 209–210) een gevulde operand als de falende voorwaarde aanwees (zinnen 13–14).
- Op het model dat uchars IR-lader las (de scenario-runner, `ScenarioRunner.java` r. 189, en de dienst uit een
  document) heeft een lezing geen `navigationRole`: de lader zet de wandeling in de tabel `navigationBindings` naast
  het model (uchar `IrLoader.java` r. 1481–1493), privé in de transpiler. Daar las r. 282 de lezing als lid van het
  onderwerp; had het onderwerp een lid met die naam, dan stond zijn waarde in de zin. Gemeten met een eigen klein
  model: een maandinkomen met een eigen `grens` van 1,00 liet de uitleg de voorwaarde "de grens van het recht … is
  gevuld" als waar lezen op die 1,00.

### Oplossing

De uitleg leest een operand over een rol op het object dat uchar bereikt, zoals de exec-model-tak de lezing las; de
toolchain schrijft geen navigatie na. Dat vroeg een publieke ingang in uchar, dus een uchar-kaart:
`navigated-read-on-beans` (tak `card/navigated-read-on-beans`, worktree `uchar-dev/wt/navigated-read-on-beans`):
- `TmsTranspiler.navigationBindings(Expr.AttributeReference read)`: de wandeling die de lader naast een lezing van het
  model van een build las;
- `DroolsBackend.reached(model, subject, subjectType, read, walk)`: het object dat de wandeling vanaf een bean bereikt,
  met dezelfde afleiding als `TmsTranspiler.path` (`navigationWalk`, `hasReferenceField`), null waar een link
  ontbreekt; een wandeling die de tak niet leest, weigert zij met de zin van de tak (DROOLS-280, 281, 282).

In de toolchain:
- `Verbaliser.java`: een interface `Walks` (r. 101) zegt waar de wandeling van een lezing vandaan komt; `evalExpr`
  (r. 325) leest elk attribuut op `DroolsBackend.reached(model, bean, subject, ref, walks.of(subject, ref))`, met
  `subject` het objecttype van de regel, nu doorgegeven door `evaluate`, `renderOperand` en `operandProse`. De
  constructors met twee en drie parameters (r. 112, 117) zijn voor een model van de frontend en nemen
  `Walks.written(model)`, de wandeling van `IrEmitter.navigationBindings`; de nieuwe met vier (r. 123) neemt de bron
  mee. Klassedoc: alinea "Lezing over een rol".
- `ScenarioRunner.java` r. 192: de wandelingen uit de build, `build.execModelTranspiler().navigationBindings(read)`.
- `DecisionCore.java`: een veld `walks`; uit bronnen `Walks.written(model)` (r. 265), uit een document de tabel van de
  lader, `loaded.navigationBindings()` (r. 336); `addExplanation` (r. 734) geeft ze mee.
- `README.md`, de alinea over `--explanation`: een operand over een rol staat op het object dat uchar bereikt.

Waarom zo: de waarde die de uitleg toont, is na de terugschrijving die welke de motor afleidde, en welk object een
operand bereikt, is uchars afleiding, niet die van de toolchain. Een navigatie in de Verbalisator zelf (de
referentievelden met uchars losse functies lopen) zou de herrekening van de toolchain uitbreiden en op het model van
de lader niet werken, want daar staat de wandeling privé in de build. Een uitleg die de operandwaarden uit de sessie
of een waarnemer van uchar leest, is een herontwerp van de Verbalisator; het fixpunt geeft geen waarde per operand.

Een waarde die echt leeg is, of een object dat niet bereikt wordt, blijft "(onbekend)". Een weigering van uchar gaat
door (fail fast); dat is niet gemeten op een regelset, want geen gemeten lezing werd geweigerd.

### Takken en commits

uchar, tak `card/navigated-read-on-beans` vanaf main `4f3d2a8e`:
- `ab0ed424` navigated-read-on-beans: kaart
- `d8612bad` navigated-read-on-beans naar doing
- `b8a59b1a` navigated-read-on-beans: wandeling en bereikt object publiek
- `78bae4e3` navigated-read-on-beans naar done, met Result

regelspraak-toolchain, tak `card/explanation-role-path` vanaf main `21d9956e`:
- `98c08cfb` explanation-role-path: kaart
- `0afc8ca7` explanation-role-path naar doing
- `9a171403` Uitleg leest een operand over een rol waar uchar hem leest (met de pin naar uchar `78bae4e3`)
- deze Result en de verplaatsing naar `done/`.

### Tests

- Nieuw: `toolchain/src/test/java/dev/uchar/regelspraak/toolchain/engine/ExplanationRolePathTest.java` (2 tests),
  de route van de scenario-runner: een eigen model naar het patroon van s01 (een Maandinkomen leest de
  herlevingsgrens van zijn recht en de maximeringsfactor van het dagloon van zijn recht), met M1 boven de grens, M2
  eronder, M3 bij een recht zonder herlevingsgrens en M4 zonder recht; en dezelfde zinnen als het Maandinkomen een
  eigen `herlevingsgrens` van 1,00 heeft.
- Nieuw: `service/src/test/java/dev/uchar/regelspraak/toolchain/service/ExplanationRolePathServiceTest.java`
  (2 tests): hetzelfde model als verzoek met `?explanation=true`, de dienst uit bronnen en uit het `uchar-ir-v1`-document.
- Rood eerst: met de drie bronbestanden van main en de nieuwe uchar-pin vallen alle vier nieuwe tests; de rest van
  de suites blijft groen (toolchain 141 van 143, service 332 van 334, service-quarkus 53).
- Geen bestaand testbestand gewijzigd. De twee tests die "(onbekend)" pinnen,
  `ExplanationGeneratedRuleInVocabularyTest` (r. 105–172: `zijn opgegeven meldingsdatum` van V2 is leeg) en
  `ExplanationServiceTest` (r. 54–68: een tijdsduur, die de Verbalisator niet uitrekent), gaan niet over een waarde
  die een rol bereikt, en blijven groen zonder wijziging.

### Bewijs op de echte regelset

De dienst van de v2-regelset herleving-ww uit de werkbank-checkout (descriptor `service/herleving.service.json`,
bronnen zoals het `Makefile` van de regelset ze geeft: `fundament/rules/*.rgs` en `rules/*.rgs`, gesorteerd), met
`PLAYGROUND=true DETAIL=true bin/regelspraak serve … --port 18094` uit de worktree. Het s01- en s03-verzoek uit het
bewijsscript van de eval-runner, met `?explanation=true`. Vóór: dezelfde worktree en pin, met de drie bronbestanden
van main. De s01-zinnen van vóór zijn letterlijk gelijk aan de eerdere meting van de werkbank (20 van 20).

| | vóór | na |
|---|---|---|
| s01 zinnen / met "(onbekend)" | 20 / 14 | 20 / 9 |
| s03 zinnen / met "(onbekend)" | 10 / 6 | 10 / 6 |

Alleen zinnen 10–14 van s01 veranderen; de andere 15 van s01 en alle 10 van s03 zijn letterlijk gelijk, en het
antwoord buiten `explanation` (instanties, attributen, kenmerken, inconsistenties) is in beide gelijk.

- [10] vóór: "I4 heeft de herlevingsgrens overschreden, omdat zijn inkomen (2.800,00 EUR) gevuld is en de
  herlevingsgrens van het recht van het Inkomen in-een-kalendermaand (onbekend) gevuld is en zijn inkomen maal de
  maximeringsfactor van het dagloon van het recht van het Inkomen in-een-kalendermaand (onbekend) is groter dan de
  herlevingsgrens van het recht van het Inkomen in-een-kalendermaand (onbekend)"
  na: "I4 heeft de herlevingsgrens overschreden, omdat zijn inkomen (2.800,00 EUR) gevuld is en de herlevingsgrens van
  het recht van het Inkomen in-een-kalendermaand (2.625,00 EUR) gevuld is en zijn inkomen maal de maximeringsfactor van
  het dagloon van het recht van het Inkomen in-een-kalendermaand (2.800,00 EUR) is groter dan de herlevingsgrens van
  het recht van het Inkomen in-een-kalendermaand (2.625,00 EUR)"
- [11] I5 en [12] I6: als [10], letterlijk dezelfde verandering.
- [13] vóór: "I7 heeft geen herlevingsgrens overschreden, want niet geldt: de herlevingsgrens van het recht van het
  Inkomen in-een-kalendermaand (onbekend) gevuld is"
  na: "I7 heeft geen herlevingsgrens overschreden, want niet geldt: zijn inkomen maal de maximeringsfactor van het
  dagloon van het recht van het Inkomen in-een-kalendermaand (1.354,84 EUR) is groter dan de herlevingsgrens van het
  recht van het Inkomen in-een-kalendermaand (2.625,00 EUR)"
- [14] vóór: als [13] voor I8. na: "I8 heeft geen herlevingsgrens overschreden, want niet geldt: zijn inkomen maal de
  maximeringsfactor van het dagloon van het recht van het Inkomen in-een-kalendermaand (0,00 EUR) is groter dan de
  herlevingsgrens van het recht van het Inkomen in-een-kalendermaand (2.625,00 EUR)"

De negen overige "(onbekend)" in s01 gaan over een waarde die leeg of niet opgegeven is: `opgegeven …` (zinnen 6, 7,
18, 19, 20), `berekende …` (8, 9), `datum einde verschoven-periode-uitkeringspercentage 75` (2), en in zin 17 "de
aow-datum van de persoon van het recht van de Eindigingsgrond". Die laatste leest ook over een rolpad (twee hoppen);
de AOW-datum is invoer (hww-14, r. 20) die het s01-verzoek niet geeft, dus leeg op elk object dat de lezing kan
bereiken.

### Poorten

Toolchain: `make gates` op `9a171403` met `TAG=explanation-role-path CLUSTER=explanation-role-path` (eigen image en
cluster, zodat die van een ander niet geraakt worden): groen, 7 stappen in 9m27s. De commits daarna raken alleen
`backlog/`.
- 1 pin: uchar op `78bae4e3`, schoon, versies gelijk;
- 2 reactor (6m13s): 1630 tests in uchars backend, 143 in toolchain (141 plus de 2 nieuwe), 334 in service (332 plus
  de 2 nieuwe), 53 in service-quarkus;
- 3 tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- 4 replay: 230 goldens, 606 cases (pin 230);
- 5 manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- 6 image `decision-service:explanation-role-path`;
- 7 kind-smoke: H2, H3 en H4 PASS. Cluster weg na de run; de drie images met deze tag zijn daarna verwijderd.
Een eerste run viel bij stap 6 op een ongeldige tag die ik meegaf (`TAG=beslisservice:…`); stap 1–5 waren daar ook
groen.

uchar: alle 16 gates groen op `b8a59b1a`, 1277 s; de commit daarna raakt alleen `backlog/` (gate 1 daarop opnieuw
groen). De cijfers staan in de uchar-kaart.

### Open

- **Kenmerkcheck over een rol op het model van de lader.** `Verbaliser.evaluate` (r. 275) leest een
  `Condition.CharacteristicCheck` zonder `navigation()` op het onderwerp. Op het model van de lader (scenario-runner,
  dienst uit een document) heeft een check over een rol geen `navigation()`; zijn binding staat in dezelfde tabel van
  de lader. Zo'n check wordt dus op het onderwerp gelezen: een ontkende check ("heeft geen …") kan als waar gelden
  terwijl het bereikte object het kenmerk heeft. `ExplanationFollowsCharacteristicKindTest` r. 159–162 beschrijft het
  al voor de tekst. Op het model van de frontend geeft dezelfde check "niet vast te stellen". Oplossen vraagt dezelfde
  twee stappen voor `Condition.CharacteristicCheck` (uchar: de wandeling van de check publiek; de emitter leidt haar
  af in een private methode), plus de toolchain. Niet gedaan in deze kaart.
- **De Verbalisator rekent zelf.** Wat de motor met een lege waarde in een berekening doet (leeg telt als 0, zoals
  bij een recht zonder maandloon de herlevingsgrens 0,00 wordt), doet de uitleg niet na: een product met een lege
  operand is daar "niet vast te stellen". Gezien bij het ontwerp van de test, buiten s01 en s03. Dit blijft het open
  punt van de tweede rekenaar.
- **Eenheid en schaal op het model van de lader.** Daar draagt een lezing geen type; een waarde staat zonder eenheid en
  op haar eigen schaal ("2.800" voor 2800.00). Kaart `20261012-verbalisator-getalliteral`.
- **Weigering van uchar.** De dienst legt alle kenmerktoekenningen van het model uit, ook die buiten de scope van het
  entrypoint, waarvan geen build de wandeling toetste. Weigert uchar zo'n wandeling, dan faalt de uitleg met uchars
  zin. Niet waargenomen.

### Landen

Eerst uchar: `card/navigated-read-on-beans` fast-forward op uchars main, zolang main op `4f3d2a8e` staat; dan blijft
de tip `78bae4e3`, de commit die deze tak pint. Schuift uchars main eerst nog (de kaartnamen), dan de uchar-tak
rebasen, de pin hier naar de nieuwe tip zetten en `make gates` opnieuw draaien. Daarna deze tak fast-forward op
toolchain-main (rebasen als main verschoof). De werkbank volgt met een toolchain-pin.
