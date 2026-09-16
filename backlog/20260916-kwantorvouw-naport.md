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

### Metingen

- (c) `mvn -o -B test`: **978 tests, 2 failures, 0 errors, 3 skipped** — NIET
  volledig groen. Beide failures zijn PRE-EXISTENT op HEAD (7bbd0ee) en niet
  door deze kaart veroorzaakt; gemeten met `git stash`, waarna dezelfde twee
  op dezelfde regels faalden. Zonder deze kaart faalden er drie (deze twee
  plus de §8.2-forall). Zie "Openstaand" hieronder.
- (d) `make test`: **exit 0** (expliciet geëchood, niet door een pipe). 553
  tests gevonden, 552 geslaagd, 1 aborted, 0 failed, 34 containers.
- (e) `make uchar-conformance UCHAR_DIR=…`: **PASS** — gevendorde slice
  byte-identiek aan pin 0db8907 (58 bestanden), 47 emitter-goldens
  gereproduceerd. `git status --porcelain vendor SPEC_REV scripts` is leeg.
- (f) Geen bestaande test gewijzigd behalve de restauratie (a).

### Openstaand: twee rode tests die deze kaart niet mag aanraken

Regel (2) van de opdracht verbiedt het wijzigen van bestaande tests buiten de
restauratie, en acceptatie (f) herhaalt dat. Beide onderstaande rode tests
vragen juist een testwijziging — geen van beide is in productiecode te
repareren. Ze vallen dus terug op de beheerder.

1. `ExecModelDifferentialTest#gehele_periode_over_verzameling_niet_lowerbaar_is_symmetrische_marker`
   (r. 7595), geval "niet-scalaire grens (eigen attribuut)". Deze test is bij
   475bbde als DEFINITIEVE WF-096-weigering gepind, maar dat oordeel is
   gemaakt vóór de pin-bump (935deb0) die de Kwantorvouw-promotie binnenhaalde.
   De zin "… van alle passagiers … gedurende het gehele jaar groter is dan de
   drempel van de Vlucht" heeft ÉÉN setoperand en een §8.2-kwalificatie, dus
   de gevendorde resolver promoveert hem nu WEL naar een Kwantorvouw en
   WF-096 zwijgt. Het eerste geval (tijdlijn-vs-tijdlijn) heeft TWEE
   setoperanden, wordt niet gepromoveerd, en houdt zijn WF-096-fout — dat
   geval slaagt nog.
   Na deze kaart is de eerlijke stand voor het tweede geval precies het
   ORIGINELE lichaam van die test: de DRL markeert
   `false /* TODO §8.2 kwantorvouw ALLE over Passagier (een niet-scalaire
   matrixgrens (de eval na de accumulate bindt geen $subject)): … */` en de
   exec slaat over met dezelfde reden — symmetrisch, met de vorm benoemd.
   De minimale juiste pin is dus: geval 1 houdt de WF-096-assertie, geval 2
   krijgt zijn marker/skip-assertie terug.
2. `ExecModelDifferentialTest#phase_AGG_no_orphan_accumulate_on_marker`
   (r. 7826), bron `aggVsAgg`. Volledig buiten het bereik van deze kaart: geen
   setlezing, geen §8.2, geen kwantorvouw. De DRL-tak lowert
   "het aantal passagiers … gelijk is aan het aantal bemanningsleden …"
   inmiddels wél (twee count-accumulates plus één `rsAggVergelijk`-eval, geen
   TODO en geen weesaccumulate), terwijl de test nog een marker eist. De pin
   is achterhaald door een eerdere kaart, niet door deze. De tweede bron
   (`toManyNav`) markeert nog wel correct.

### Commits op de branch

- `74b2123` de kwantorvouw krijgt een eigen vertaalarm in beide takken
- `5ef3a2e` de par. 8.2-forall staat weer end-to-end, en GEEN krijgt zes
  eigen cellen
- de kaart zelf (deze Result) in de commit hierna
