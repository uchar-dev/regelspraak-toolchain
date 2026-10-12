# sessie-via-de-ingang-van-uchar: de pin naar de omschakeling van uchar, en elke sessie vult, vuurt en leest via `DroolsBackend`

*Vastgelegd bij de landing van `20261008-tms-omschakeling` in uchar, uit het `Result:` van die kaart. Deze kaart landt
na die kaart; de pin mag niet bewegen zonder haar. Op 2026-10-12 in deze repository gezet en bijgewerkt naar uchar
main `b536bbc9`, met de landing van `20261010-objectcreatie-zonder-connectingrole-binding` erbij. Regelnummers op
`f9fc44c` (submodule op uchar `0a378588`). De eigenaar besloot op 2026-10-11 dat de pin pas na de demo van 2026-10-12
beweegt.*

**Aanleiding.** Sinds `20261008-tms-omschakeling` leidt `DroolsBackend.Branch.EXEC_MODEL` af met truth maintenance:
een afgeleide waarde is een logisch feit in de sessie, en de beans dragen alleen invoer tot
`DroolsBackend.zetAfgeleideWaarden`. De distributie vuurt vandaag zelf, één keer per verse sessie, en leest daarna de
beans en de `Inconsistentie`-feiten. Zo doet `Uitvoerder.vuur` het
(`service/src/main/java/dev/uchar/regelspraak/toolchain/service/Uitvoerder.java` r. 62–74):

```java
KieSession sessie = kieBase.newKieSession();
// … globals: dagsoorten, defaults, parametersets, verzoek, rekendatum
for (Object bean : wereld.beans().values()) sessie.insert(bean);
int n = sessie.fireAllRules(100_000);
if (n >= 100_000) throw new IllegalStateException("regel-herberekening bereikt geen fixpunt …");
List<Object> inc = leesInconsistenties(sessie);
return new Uitkomst(wereld.beans(), n, inc);
```

Na een pinbump zonder deze kaart geeft elk antwoord van de dienst, van `RegelSpraakEngine` en van de scenario-uitleg
de invoer terug zonder de afgeleide waarden, en compileert `RegelSpraakEngine` niet meer.

**Wat er gebeurt.**
1. **De pin** naar uchar main `b536bbc9` (2026-10-12): de landing van `20261008-tms-omschakeling` (`ac8bc71a`) en die
   van `20261010-objectcreatie-zonder-connectingrole-binding` (`e2144945`).
2. **`Uitvoerder.vuur`** krijgt de `Build` in plaats van KieBase, beanLoader en dagsoortglobals; daarvoor draagt
   `BeslisserKern.Entrypoint` (r. 97, gebouwd op r. 225 en r. 301) de `Build`. De globals blijven zoals nu en gaan
   vóór de eerste `vuur` op de sessie. Daarna:

   ```java
   DroolsBackend.voegIn(build, sessie, new Invoer().beans(wereld.beans().values()));
   DroolsBackend.Vuring v = DroolsBackend.vuur(build, sessie);
   if (v.geenFixpunt() != null) throw new IllegalStateException(v.geenFixpunt());
   Map<Object, String> label = new IdentityHashMap<>();
   wereld.beans().forEach((l, bean) -> label.put(bean, l));
   DroolsBackend.zetAfgeleideWaarden(build, sessie, label::get);  // werpt een staande fout, zie 4
   List<Object> inc = leesInconsistenties(sessie);                // ongewijzigd, of DroolsBackend.lees(…).inconsistenties()
   return new Uitkomst(wereld.beans(), v.vuringen(), inc);
   ```

   De labelfunctie geeft het label van een invoerbean en null voor een gemaakt object; een fout noemt haar object dan
   zoals ref ("V, een Vondst"). Een gemaakt object is na het terugzetten via de referentievelden te bereiken.
3. **`CaseSessie.Speler.speel`** (`toolchain/.../engine/CaseSessie.java` r. 84–103) gaat op dezelfde manier: `voegIn`,
   `vuur`, `zetAfgeleideWaarden(build, sessie, label)` met de labels van `labelNaarBean`. De volgorde van een lijst
   naar veel is standaard die van de beans; wil de uitleg de volgorde van uchars replay (eerst de geschreven leden,
   dan de gespiegelde op label), dan geeft de speler `Invoer.lijst(houder, rol van de houder, leden)`. Die volgorde
   bepaalt alleen welk lid een fout noemt. De javadoc van `CaseSessie` zegt dat de invoerstap van de replay geen
   publieke API is; dat is hij nu wel (`voegIn`, `Invoer`).
4. **Fouten.** Een evaluatiefout komt niet meer uit `fireAllRules` maar uit `zetAfgeleideWaarden`, dat niets schrijft
   zolang er een fout staat: één fout als haar eigen exceptie, `Breuk.DelingDoorNul` (een `ArithmeticException`),
   `Rekenfout` (een `RuntimeException`), `Schrijversconflict` (een `IllegalStateException`) of `Invoervormfout` (een
   `RuntimeException`); twee of meer als `Evaluatiefouten`, met `fouten()`. `lees(…).fouten()` geeft ze als gegevens.
   `AntwoordBouwer` en de foutafhandeling van de dienst beelden deze typen af.
5. **De naam van een vuring** (besluit 21 van uchar). `CaseSessie` telt een vuring onder
   `ProducentConsument.basisNaam(regelnaam)` (r. 184), en `ScenarioRunner` r. 166 vergelijkt dat spoor met dat van
   uchars `CaseRunner.rapport`; bij een verschil houdt de uitleg haar antwoord in. Uchar noemt een vuring sinds de
   omschakeling naar `TmsTranspiler.vormVan(regelnaam)`, met alleen het versie-achtervoegsel eraf
   (`RegelSpraakModel.vormnaam`), en geeft een regel zonder vorm (de standen van de verlaging) geen rij. `CaseSessie`
   volgt dat, of de uitleg wijkt op elk document af. De dekking van het rapport vergelijkt sinds `1df4affc` op de
   exacte vormnaam, dus per versie. `Playground` (r. 222) toont het spoor van uchar al met `naamgeving.vorm(…)` en
   verwacht vormnamen.
6. **`RegelSpraakEngine.Session`** (`toolchain/.../engine/RegelSpraakEngine.java` r. 155–206) krijgt de `Build`:
   - `insert` en `insertAll` (r. 164–165) verzamelen de beans tot de eerste `fire()`; die roept `voegIn` en `vuur`
     aan, een volgende `fire()` alleen `vuur`. `voegIn` vult alleen een verse sessie.
   - Een nieuwe `update(bean)` (`session.update(session.getFactHandle(bean), bean)`) laat een afnemer tussen twee
     fires een attribuut of kenmerk van een bean uit `voegIn` veranderen. De `Session` roept `update` aan na elke
     wijziging die zij doorgeeft: een attribuut of kenmerk dat zonder `update` verandert, ziet de ingang niet, en
     `lees` geeft dan het oude fixpunt (zie het contract, punt 5). Een nieuw, verwijderd of anders verbonden object,
     ook met `update`, een andere global, een kale `fireAllRules` en elke bewerking van de agenda vragen een verse
     sessie; `vuur`, `lees` en `zetAfgeleideWaarden` weigeren ze met naam.
   - Een nieuwe `lees()` geeft het `Fixpunt` van de laatste `fire()`; een nieuwe `zetTerug()` roept
     `zetAfgeleideWaarden` aan, waarna `fire()` de sessie weigert.
   - `fire()` geeft `Vuring.vuringen()` en werpt bij `Vuring.geenFixpunt()`.
   - `facts(…)` (r. 189–202) leest het werkgeheugen: afgeleide waarden staan daar als logische feiten, niet op de
     beans; de `Inconsistentie`-feiten blijven daar te lezen.
   - `setGlobal` (r. 166) hoort vóór de eerste `fire()`: een global staat vast vanaf de eerste `vuur`, en `vuur`,
     `lees` en `zetAfgeleideWaarden` weigeren een sessie waarvan een global daarna veranderde.
7. **`RegelSpraakEngine.overgeslagen()`** (r. 148) leest `build.execModelTranspiler().overgeslagen()`; het type is nu
   `TmsTranspiler`, dat geen `overgeslagen()` heeft, dus dit compileert niet meer. De methode valt weg, of geeft de
   niet-verlaagde regels van `build.dekking()` terug, die altijd leeg zijn omdat de build elke andere regel weigert.
   Aanroepers: `RegelSpraakEngineTest` r. 34 en `PeildatumSessieTest` r. 55 en 113–114.
8. **De drie literalen `fireAllRules(100_000)`** (`Uitvoerder` r. 69, `CaseSessie` r. 103, `RegelSpraakEngine`
   r. 178) en hun zinnen over een kringverwijzing verdwijnen. Het plafond is `max(10 000, 4 × R × N0)`, met R de
   regels van de KieBase en N0 de feiten vlak vóór de fire, bij elke creatiefase opnieuw uitgerekend; zonder `vuur`
   vuren de creaties niet. Een kring die WF-045/046 verbiedt, weigert uchar bij de bouw, met
   "exec-model: kring in de afhankelijkheden …"; `UcharBouw.nietGelowerd` herkent die zin niet als construct, dus
   `NietGedragen` heeft dan geen construct.
9. **Tests die zelf vuren en de beans lezen**: `NegatieStratificatieTest` r. 99–100 (`fireAllRules(10_000)`),
   `ScenarioOrakelTest` r. 87–92, `PeildatumSessieTest` r. 60, 66, 119, 134 en 143, `VerbalisatorTest` r. 39. Zij
   gaan via de ingang, of via de nieuwe `Session`.
10. **Weigerzinnen.** Het voorvoegsel van een weigering per vorm is weer `transpiler SKIP:` en past op het patroon
    van `UcharBouw` (r. 40–41). Het commentaar in `VerzoekMapper` r. 582 noemt `ExecModelTranspiler.globalNaam`; de
    nieuwe tak noemt een parameterglobal met dezelfde formule (`Namen.ident(naam, false)`), dus alleen het commentaar
    is verouderd.

**Het contract** (uchar `backends/drools/README.md`, "The entry for consumers"):
1. Een verse `KieSession` van `build.kieBase()`, met `build.beanLoader()` als context class loader terwijl de beans
   gemaakt worden en de sessie vuurt; de globals gaan erop vóór de eerste `vuur` en staan daarna vast.
2. `DroolsBackend.voegIn(build, sessie, invoer)`, met een `Invoer` die de beans draagt (`bean`, `beans`), en waar
   nodig `emmerscalair(bean, attribuut)` voor een invoertijdlijn als emmerscalair en `lijst(houder, rol, leden)` voor
   de volgorde van een lijst. `voegIn` stelt zelf de `Emmerinvoer`- en `Lijstplaats`-feiten, vult alleen een verse
   sessie, weigert een opgave die niet past vóór het iets invoegt, en weigert een referentieveld naar een object dat
   niet als bean in de invoer staat.
3. `DroolsBackend.vuur(build, sessie)` vuurt tot het fixpunt onder het plafond, een model met creatie per stratum in
   generaties, en geeft een `Vuring(vuringen, plafond, geenFixpunt)`. Een model met creatie vuurt per sessie één keer.
4. `DroolsBackend.lees(build, sessie[, label])` geeft het `Fixpunt` (waarden, kenmerken, gemaakte objecten,
   koppelingen, fouten, inconsistenties) zonder iets te schrijven, of aan het eind
   `DroolsBackend.zetAfgeleideWaarden(build, sessie[, label])`, dat het fixpunt eenmalig op de beans zet, met de
   gemaakte koppelingen op de referentievelden, en de sessie afsluit. Beide lezen alleen een sessie waarvan de laatste
   `vuur` een fixpunt bereikte en waarin sindsdien niets buiten `vuur` om gebeurde.
5. Tussen twee fires verandert een afnemer alleen een attribuut of kenmerk van een bean uit `voegIn`, met `update`.
   Een bewaking op de sessie (`Bewaking`) weigert al het andere met naam: een relatie die verandert, ook met `update`,
   een global die verandert, een kale `fireAllRules` en een bewerking van de agenda. Zij leest daarvoor de agenda van
   Drools (`org.drools.core`), omdat `Agenda.clear` geen gebeurtenis geeft: de distributie raakt de agenda niet aan.
   **Plicht van de afnemer: na elke wijziging van een attribuut of kenmerk `update` aanroepen.** Een waarde die
   zonder `update` verandert, ziet de ingang niet: de volgende `vuur` vuurt niets en `lees` geeft het oude fixpunt
   (eindlens van `20261008-tms-omschakeling`, vondst 1, op het schaalmodel: beloning 1, wachtsom 270 en W1
   consistent, waar een verse sessie 80, 349 en W1 inconsistent geeft). De README zegt dat sinds `cd356720`, in de
   lijst "What a consumer can forget".
6. Een emmerscalair staat op de bean als het ene segment van de periode van de rekendatum. `voegIn` toetst dat het
   attribuut op een tijdlijn ligt, niet de vorm van die tijdlijn: een emmerscalair op een lege of langere tijdlijn,
   zo opgegeven of tussen twee fires zo gezet met `update`, laat de volgende `vuur` stoppen met een
   `IllegalStateException` in Drools' `ConsequenceException` (README, dezelfde lijst, sinds `cd356720`).

**Gedrag dat verandert** (besluiten 9 en 12 van uchar; de lijsten staan in het `Result:` van
`20261008-tms-omschakeling`, "Het dekkingsverlies"):
- Vormen die de oude exec-model-tak droeg en de nieuwe met naam weigert, geen ervan met een golden: een
  dagsoortpredicaat als voorwaarde; een deling of wortel in een default; een deling of wortel in een
  ledeninitialisatie van een objectcreatie; een objectcreatie zonder
  `connectingRole`-binding waarvan het typenpaar de relatie alleen via een supertype bereikt (uchar-kaart
  `20261011-creatie-zonder-binding-via-supertype`; de gewone vorm zonder binding, die de frontend schrijft als de zin
  het type noemt in plaats van een rol, draagt de tak sinds `20261010-objectcreatie-zonder-connectingrole-binding`
  weer); "is gevuurd" in een voorwaarde; een macht met een
  niet-literale exponent; een som over tijdlijnen als waarde van een tijdlijndoel; een uniciteitssleutel met twee
  tijdsafhankelijke delen; de producenten op een raster met variabel startpunt. Een regelset met zo'n vorm bouwt niet
  meer (`UcharBouw.NietGedragen` met de weigerzin).
- Gedrag dat alleen de oude exec-model-tak had en dat verdwijnt (punten 13 en 18 van die kaart): een versie met een
  begrensde geldigheid zonder rekendatum, een niet herrekende terugvalwaarde in een andere eenheid, een benaderde
  irrationale wortel, een deling door leeg die op drie plekken niet deponeerde, "5.50" en "true" in een
  tekstsamenstelling, de verdeling met een later afgeleide pot, een lege pot, twee versies en de twee punten van
  `rsVerdeelOpVolgorde`, een rekenende regel naast een verdeling, een tarief zonder gedeclareerde noemer, de
  asOfDate-emmerscalair als segment, "(on)gelijk aan" in een knip, en een `ClassCastException` bij een
  gedimensioneerd doel. Waar de oude tak antwoordde en ref faalt, faalt of weigert de nieuwe nu ook.
- Meer goldens spelen: uchars gate 12 telt in de exec-model-tak 250 (`b536bbc9`) waar de destructieve tak 242 speelde,
  waaronder `anchor/jutterswet-penninguitgifte` en `jutterswet-penningbesluit` (sinds `e2144945`). De set van
  `check-dienst-replay` komt uit uchars gate 12 en verschuift mee.
- Een leeg startpuntanker: de oude takken gaven via een begeleider "inconsistent", ref, clingo en de nieuwe tak geven
  consistent.
- Een factor 1/24: 216,00 sl wordt 9.00000000000000072 tj waar de oude tak 9.000000000000001 gaf (CONSTRUCTS rij 26).
- Een kring die WF-045/046 verbiedt: geweigerd bij de bouw, waar de oude tak tot het plafond vuurde.

**Meting voor de dienst** (punt 7 en 9 van die kaart): op de 242 goldens kost vuren plus terugzetten per golden in de
mediaan 2,61 keer zoveel (24 µs tegen 10 µs). Op het schaalmodel met N = 10 000 Wachters (110 001 invoerfeiten) kost
vuren plus terugzetten op de landing 3 209,3 ms, waar de oude tak 213 613,8 ms nodig had; een tweede fire na één
wijziging kost daar 16,4 ms (16 vuringen) tegen 1 864,2 ms voor het vuren van een verse sessie.

**Acceptatie.**
- (a) De pin staat op de landing van `20261008-tms-omschakeling`; `make check-pin` groen.
- (b) Geen `fireAllRules` meer in `service/src/main` en `toolchain/src/main`; elke sessie gaat via `voegIn`, `vuur`
  en `zetAfgeleideWaarden` of `lees`.
- (c) Rood eerst, per vuurplek een getuige die na de pinbump zonder deze kaart faalt en erna een afgeleide waarde
  leest: een antwoord van de dienst, een `Session` die twee keer vuurt met een `update` ertussen, en een uitleg van de
  scenario-runner die zonder afwijkend spoor verbaliseert.
- (d) Een getuige per fouttype van punt 4 in het antwoord van de dienst.
- (e) `make poorten` groen (de pin, de reactor met zijn vloeren, `check-tweelingen`, `check-dienst-replay` en
  `scenario-test`), en `make gates` vóór de landing. Het gepinde aantal en de lijst van `check-dienst-replay` volgen de
  nieuwe tak; elke golden die van kanaal wisselt, staat met zijn meting en reden in het Result. Een vloer die zakt, zakt
  in dezelfde commit als de verwijdering, met de reden.

**Omvang.** Middel. May touch: de submodule `uchar` (alleen de pin), `service/`, `service-quarkus/` (alleen als de
adapter de foutafhandeling draagt), `toolchain/`, hun tests, kaartverplaatsing.

**Raakvlakken.** Uchar `20261010-objectcreatie-zonder-connectingrole-binding` (geland, `e2144945`, in deze pin) haalde
de zwaarste post van het dekkingsverlies terug, op het supertypepad na
(`20261011-creatie-zonder-binding-via-supertype`); uchar `20261006-bronvolgorde-restpunten` (e) noemt de zinnen over de
resolver-WARNING bij de literalen van punt 8, die met deze kaart verdwijnen; uchar
`20261008-drl-teksttak-op-truth-maintenance` verandert de DRL-teksttak, die de distributie alleen toont (CLI `drl`,
`Playground`).
