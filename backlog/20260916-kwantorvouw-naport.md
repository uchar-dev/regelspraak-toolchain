# Naport: de Kwantorvouw-arm in de eigen vertaallaag

Aanleiding: pin `0db8907` — de gevendorde resolver lowert de §8.2-vorm
("de <attribuut> van alle <leden> van de <onderwerp> gedurende het
gehele jaar <vergelijking>") nu naar `Conditie.Kwantorvouw` (kwantor
ALLE|GEEN, elementObjecttype, filter, toets, bronRol|bronPad). De EIGEN
transpilers onder `src/main/java/io/github/yhavinga/regelspraak/`
kennen die node niet, en de tijdelijke weigering-pin in
`phase_GP_ALLE_wholeperiod_forall_over_to_many_is_spec_correct` faalt
sinds de bump precies zoals zijn commentaar aankondigt: de vorm
resolvet weer schoon. Dit is de aangekondigde restauratie-trigger.

Bron van de naport: uchar's `backends/drools/` — `DrlTranspiler`,
`model/ExecModelTranspiler` en `runtime/RegelSpraakRuntime` dragen de
arm daar al, drievoudig gepind (ref ≡ clingo ≡ Drools, golden
jutterswet-ploegpremie, zeven cellen incl. de GEEN-vacuümcel). De
toolchain-runtime heeft `rsGeheelPeriodeAlle` al; `rsGeheelPeriodeGeen`
(uchar-runtime r. 1085-1105) port mee. Scopegrens als in uchar: de
heelperiode-matrix draagt beide takken; een niet-heelperiode-matrix
blijft een luide marker/skip.

## Acceptatie

- (a) `phase_GP_ALLE_wholeperiod_forall_over_to_many_is_spec_correct`
  gerestaureerd naar zijn ORIGINELE end-to-end-lichaam (resolve schoon
  → geen skip → DRL==exec op de vijf absolute verwachtingen in
  `PHASE_GP_ALLE_EXPECTED`). Het origineel staat in de git-historie van
  het testbestand (vóór de commit die de tijdelijke pin zette);
  letterlijk herstellen, niet reconstrueren.
- (b) De GEEN-arm meegeport in beide takken, met ADDITIEVE differentiële
  dekking: de GEEN-dualen van de ALLE-cellen plus de vacuümcel (GEEN
  over lege set → WAAR), handberekend vóór het draaien en getoond in
  het Result.
- (c) `mvn -o -B test` volledig groen (977+ tests, 0 failures).
- (d) `make test` exit 0, EXPLICIET gecheckt (`; echo $?` — nooit door
  een pipe).
- (e) `make uchar-conformance UCHAR_DIR=/Users/yeb/Developer/uchar-dev/uchar`
  PASS; `vendor/`, `SPEC_REV` en `scripts/` onaangeraakt (lege diff).
- (f) Geen bestaande test gewijzigd behalve de gemelde restauratie (a).

## Result

### Wat er gebouwd is

De arm is naar het toolchain-idioom vertaald, niet gekopieerd: uchar's
backend leest de IR van de wire en heeft daar machinerie (`gebondenRolpaar`,
`heeftReferentieVeld`, de §13.3.9-opslagrichtingsvragen) die deze toolchain
niet kent. De join gebruikt daarom de bestaande §8.2-verzamelingsvorm van de
toolchain: `model.verbindendeRol(elem, subject)` levert het beanveld op het
element dat naar het subject wijst. Ontbreekt die rol, dan weigert de arm
luid ("geen verbindende rol tussen X en Y") in plaats van te raden. Gevolg,
bewust: het zelfgetypeerde bereik dat uchar via `bronRol` terugvalt, valt
hier buiten bereik; het `bronRol`-veld wordt nog niet gelezen.

Tweede bewuste afwijking van uchar: daar markeert de DRL-tekst-tak de knoop
en weigert het document bij naam; hier moet die tak hem WEL lowerden, want
acceptatie (a) eist DRL==exec op absolute waarden. Beide takken dragen de arm
dus, met dezelfde weigeringsredenen op dezelfde plaatsen: 16 benoemde
weigeringen in de DRL-tak, 19 in de exec-tak. Die drie extra zijn defensief
en onbereikbaar via de resolver (geen fact-class voor het elementtype, een
niet-geregistreerde global, een grens die meer dan één global leest); de
DRL-tak handelt ze via `waarde()` en Drools' eigen compilatie af. Precies
dezelfde asymmetrie draagt de bestaande §8.2-verzamelingsarm al.

- `src/main/java/io/github/yhavinga/regelspraak/runtime/RegelSpraakRuntime.java`
  r. 1102-1116: `rsGeheelPeriodeGeen` overgeport uit de uchar-runtime; de
  javadoc-staart aangepast omdat hier BEIDE takken hem aanroepen.
- `src/main/java/io/github/yhavinga/regelspraak/transpiler/DrlTranspiler.java`
  r. 3381-3487: `kwantorvouwVoorwaarde` (collectList-accumulate +
  `eval(rsGeheelPeriodeAlle|rsGeheelPeriodeGeen(...) == true)`), `kvMarker`
  (de benoemde TODO-marker, die via de bestaande `meet()`-scan vanzelf als
  marker geclassificeerd wordt) en `elementTijdlijnLid`. Dispatch op
  r. 3873-3876, vóór de `geheelPeriodeVan`-test: de vouw draagt zelf geen
  §8.2-kwalificatie (die zit op de matrix) en zou daar anders in de scalaire
  paden vallen.
- `src/main/java/io/github/yhavinga/regelspraak/transpiler/model/ExecModelTranspiler.java`
  r. 2312-2458: `kvSkip` (schrijft de reden mét vorm in `overgeslagen` —
  nooit een stille decline), `elementTijdlijnLid` en `kwantorvouwVoorwaarde`
  (dezelfde twee clausules, als Gate met extra's). Dispatch op r. 2482-2486,
  boven de `Samengesteld`-arm: de eerste-orde-zus daarvan.
- `src/main/java/io/github/yhavinga/regelspraak/transpiler/Stratificatie.java`
  r. 154-162 en
  `src/main/java/io/github/yhavinga/regelspraak/graaf/ProducentConsument.java`
  r. 79-88: filter en matrix zijn condities, dus hun kenmerklezingen tellen
  mee voor stratum en producent/consument — met het ELEMENT als subject,
  nooit het subject van de regel.

De NIET-prefix: de matrix is een gewone conditie per bijdrager, dus zijn
"niet gedurende …" ontkent PER BIJDRAGER en niet het verdict. Een genegeerde
matrix klapt daarom de kwantor om (∀c.¬P = ¬∃c.P) in plaats van een derde
runtime-helper te vragen. Beide takken doen dat op dezelfde regel.

Scopegrens als in uchar: alleen de heelperiodematrix (vergelijking / gevuld /
leeg / tijdsafhankelijk kenmerk van het element). Elke andere vorm is een
marker (DRL) en een skip (exec) die de VORM en de reden noemt.

### (b) De handberekende GEEN-cellen

Berekend uit de semantiek van `rsGeheelPeriodeGeen` (waar precies wanneer
GEEN ENKELE bijdrager de heelperiodematrix haalt; lege verzameling is
vacuüm waar) vóór het draaien. De matrix is per passagier
`rsGeheelPeriode(tijdlijn, "JAAR", GROTER, 40)`; die is onwaar zodra het
geraakte kalenderjaar niet over zijn VOLLE lengte voldoet.

1. 50 en 60, heel jaar → beide halen de matrix (50>40, 60>40, volledige
   jaardekking) → er is een bijdrager die hem haalt → **onwaar** (NIET groot).
2. 50 en 30, heel jaar → 50 haalt hem, 30 niet → één bijdrager haalt hem →
   **onwaar**. Dit is de rij die GEEN van `!ALLE` scheidt: ALLE is hier ook
   onwaar, dus een `!rsGeheelPeriodeAlle`-lowering zou hier waar lezen.
3. 30 heel jaar plus een passagier met alleen 2024-01-01..2024-06-01 op 60 →
   30 haalt de matrix niet; de gat-passagier haalt hem evenmin, want 152
   voldoende dagen tegen 366 kalenderdagen in de geraakte jaarbak → geen
   enkele bijdrager haalt hem → **waar** (groot).
4. lege verzameling → de lus loopt niet → **waar** (vacuüm).
5. precies 40, heel jaar → 40 > 40 is onwaar → niemand haalt de matrix →
   **waar**.
6. 30 en 20, heel jaar → niemand haalt de matrix → **waar**.

Gemeten uitkomst na het draaien: alle zes identiek aan de handberekening, in
beide takken (`PHASE_GP_GEEN_EXPECTED` = FALSE, FALSE, TRUE, TRUE, TRUE, TRUE).

### Tests

- (a) `phase_GP_ALLE_wholeperiod_forall_over_to_many_is_spec_correct`
  hersteld naar het lichaam van vóór 475bbde, LETTERLIJK: het
  methodelichaam is byte-voor-byte gelijk aan `git show
  475bbde^:src/it/.../ExecModelDifferentialTest.java` (gedift, identiek).
  `PHASE_GP_ALLE` en `PHASE_GP_ALLE_EXPECTED` onaangeraakt.
- (b) Nieuw en additief:
  `ExecModelDifferentialTest#phase_GP_GEEN_wholeperiod_geen_over_to_many_is_spec_correct`
  met `GP_GEEN_SRC` (de ALLE-zin met alleen de geschreven kwantor gewisseld,
  zodat de twee blokken echt dualen zijn), `PHASE_GP_GEEN` en
  `PHASE_GP_GEEN_EXPECTED`. Toetst absoluut, DRL==exec, en `overgeslagen()`
  leeg — het patroon van `phase_SOMALLE` en `phase_TR`.

### Twee gemandateerde bijstellingen van bestaande pins

Op HEAD (7bbd0ee) stonden DRIE tests rood, niet één. De kaart noemt er één;
de andere twee zijn met `git stash` als pre-existent gemeten. De hoofdsessie
heeft daarop een uitgebreid testmandaat gegeven voor precies deze twee.

**1. `gehele_periode_over_verzameling_niet_lowerbaar_is_symmetrische_marker`**

Oorzaak: pin-bump 935deb0, dezelfde beheerdersdaad die deze kaart triggert.
Bij 475bbde zijn de twee gevallen samen als DEFINITIEVE WF-096-weigering
gepind, maar dat oordeel dateert van vóór de Kwantorvouw-promotie. Sindsdien
splitsen ze: het geval met TWEE setoperanden (tijdlijn-vs-tijdlijn) wordt
niet gepromoveerd en houdt zijn WF-096-fout; het geval met ÉÉN setoperand
resolvet schoon en de weigering verhuist naar de back-ends.

Bijgesteld naar die tweedeling. Geval 1 houdt zijn WF-096-assertie
onveranderd. Geval 2 pint nu: `m.errors()` leeg; de DRL-tekst bevat het
letterlijke fragment
`/* TODO §8.2 kwantorvouw ALLE over Passagier (een niet-scalaire matrixgrens`;
`em.overgeslagen()` bevat zowel `kwantorvouw ALLE over Passagier` als
`niet-scalaire matrixgrens`; en de regel VUURT NIET — een Vlucht met een
passagier die de matrix ruim haalt (500 het hele jaar, grens veel lager)
blijft `isGroot() == false`. Het javadoc legt de tweedeling uit.

Eén fixture-detail, gemeld omdat het de tekst raakt: de grens was
`de drempel van de Vlucht`, een attribuut dat de handgeschreven beanpool niet
draagt. Zodra de test VUURT loopt zij door `drlBase`, en die draait eerst
`ContractValidator` — die viel terecht over de ontbrekende `getDrempel`.
De grens is daarom `de vertrektijd van de Vlucht` geworden: een scalair
eigen attribuut dat de beanpool al heeft en dat even goed
`$subject`-gekwalificeerd is. De beanpool is niet uitgebreid.

**2. `phase_AGG_no_orphan_accumulate_on_marker`**

Gemeten oorzaak, en die is een andere dan ik eerst schreef. De aanname dat
cfe7459 groen was, klopt niet: daar draaide de suite **977 tests, 1 failure**,
en die ene failure was juist deze test. Met een tijdelijke worktree over de
historie teruggelopen (`ProbeAgg`: resolved vorm + DRL-marker per commit):

| commit | rechts-operand | DRL |
|---|---|---|
| 79fd9ff, 4035932, b432478, 475bbde | `Functie(AANTAL, elem=null)` | marker, 0 accumulates |
| **481e11a** | `Functie(AANTAL, elem=Bemanningslid)` | geen marker, 2 accumulates |
| cfe7459, 935deb0, HEAD | idem | idem |

De trigger is dus **pin-bump 481e11a** ("de vouw filtert op zijn elementtype
en de populatienaam bindt") — één ronde eerder dan 935deb0, en NIET een
capaciteitswinst in de eigen vertaallaag: `git diff cfe7459 7bbd0ee` over
`transpiler/` en `runtime/` is leeg. Vóór 481e11a bond het bereik van de
RECHTER vouw niet (`elementObjecttype` null), dus kon de tweede accumulate
niet gebouwd worden en rolde `aggTerug` terug naar een marker. Sinds 481e11a
bindt de populatienaam, lowert de vergelijking, en is de "onlowerbare hoek"
die deze fixture modelleerde verdwenen. De test is sinds 481e11a rood en is
in die ronde niet opgemerkt.

Bijgesteld op twee manieren.

(i) De EIGENSCHAP blijft gepind: een marker mag geen weesaccumulate naast
zich hebben. `aggVsAgg` is uit de lijst gehaald (hij markeert niet meer),
`toManyNav` blijft en markeert nog correct, en er is een DERDE bron
bijgekomen: de §8.2-kwantorvouw met een `$subject`-gekwalificeerde grens.
Dat is geen opvulling maar de juiste bron voor deze eigenschap in de arm die
deze kaart toevoegt — die arm hangt een `collectList`-accumulate op en zou
hem kunnen laten staan; hij doet dat niet, want hij appendt pas nadat elke
guard geslaagd is, en dat is nu gemeten in plaats van beweerd.

(ii) De NIEUWE werkelijkheid van `aggVsAgg` krijgt een eigen absolute pin:
`phase_AGG_CNT_CNT_count_vs_count_is_spec_correct` met `AGG_CNT_CNT_SRC`,
`PHASE_AGG_CNT_CNT` en `PHASE_AGG_CNT_CNT_EXPECTED`. Handberekend vóór het
draaien, uit "het aantal passagiers van de Vlucht gelijk is aan het aantal
bemanningsleden van de Vlucht":

1. 2 passagiers, 2 bemanningsleden → 2 = 2 → **vol**
2. 2 passagiers, 1 bemanningslid → 2 ≠ 1 → **niet vol**
3. 1 passagier, 2 bemanningsleden → 1 ≠ 2 → **niet vol** (de andere kant op,
   want de lowering is niet symmetrisch: links is de eerste accumulate)
4. 0 passagiers, 0 bemanningsleden → een count over een lege verzameling is
   0 en nooit leeg, dus 0 = 0 → **vol**. Dit is de rij die een
   leegwaarde-vormige implementatie fout zou beantwoorden.
5. 0 passagiers, 1 bemanningslid → 0 ≠ 1 → **niet vol**

Gemeten uitkomst: alle vijf identiek aan de handberekening, absoluut,
DRL==exec, `overgeslagen()` leeg.

### Metingen

- (c) `mvn -o -B test`: **979 tests, 0 failures, 0 errors, 3 skipped** —
  BUILD SUCCESS. (Op HEAD waren het er 977 met 3 failures; deze kaart voegt
  twee tests toe en maakt alle drie de rode groen.)
- (d) `make test`: **exit 0** (expliciet geëchood, niet door een pipe). 553
  tests gevonden, 552 geslaagd, 1 aborted, 0 failed, 34 containers.
- (e) `make uchar-conformance UCHAR_DIR=…`: **PASS**, exit 0 — gevendorde
  slice byte-identiek aan pin 0db8907 (58 bestanden), 47 emitter-goldens
  gereproduceerd. `git status --porcelain vendor SPEC_REV scripts` is leeg.
- (f) Geen bestaande test gewijzigd behalve de restauratie (a) en de twee
  hierboven gemandateerde, gemelde bijstellingen.

### Commits op de branch

- `74b2123` de kwantorvouw krijgt een eigen vertaalarm in beide takken
- `5ef3a2e` de par. 8.2-forall staat weer end-to-end, en GEEN krijgt zes
  eigen cellen
- `e6810c3` kaart met Result (de stand vóór het testmandaat)
- `c3144a6` twee pins bijgesteld die hun aanname verloren bij een pin-bump
  (de fixronde: beide bijstellingen plus dit bijgewerkte Result)
