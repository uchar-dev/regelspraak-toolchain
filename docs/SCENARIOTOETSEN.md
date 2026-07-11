# Scenariotoetsen — analist-toetsen in RegelSpraak-stijl (DOCUMENTED EXTENSION)

v2.3.0 kent GEEN testhoofdstuk; dit is een gedocumenteerde extensie in dezelfde
houding als de §9.10 regelgroep-extensie (zie `grammar-divergences.md`): een
superset achter een EIGEN startregel (`testDocument`), nooit gepresenteerd als
§13. De productie-startregel `regelSpraakDocument` en het productie-parsen zijn
aantoonbaar onaangeroerd (isolatiegate G6). Verwachte WAARDEN komen uitsluitend
uit de spec's eigen worked examples + de TOKA-casus; de bestaande 850-suite is
het orakel voor de runner (gate G1).

Dit document is het BINDENDE contract voor grammatica, resolver, IR en runner.
Wijkt een implementatielaag af (bijv. omdat tokenisatie een vorm afdwingt), dan
wordt dít document in dezelfde commit bijgewerkt — het contract drift nooit.

## Doel

Een regelanalist (of coding agent) schrijft regels ÉN hun verwachte uitkomsten
in één `.rgs`-oppervlak en krijgt machine-checkbaar PASS/FAIL per scenario, een
fired-rules-trace bij FAIL, en een dekkingsoordeel per regel in scope — zonder
Java te schrijven. (Feature-pariteit met ALEF's Testset/Testgeval/testdekking;
de syntaxis is NIET ALEF's maar v2.3.0-stijl: lidwoorden, eenheden verplicht
waar het datatype ze eist, kleine-letter-namen voor attributen/rollen/kenmerken,
hoofdletter-geleide namen voor objecttypen.)

## Oppervlak

Twee nieuwe constructies, alleen bereikbaar via `testDocument`:

```
testDocument : ( definitie | regelGroep | regel | beslistabel
               | situatieDefinitie | scenario )* EOF ;
```

### Herbruikbare situatie (het testinitialisatie-analoog)

```
Situatie Tom met vlucht:
een Vlucht V1
- de afstand tot bestemming is 200 km
- is bereikbaar per trein
een Natuurlijk persoon Tom
- de reis is V1
- is passagier van 25 tot en met 64 jaar
de parameter de korting bij gebruik niet fossiele brandstof is 10,00 EUR
```

- Kop: `Situatie <naam>:` — de `:` sluit de naam af.
- Inhoud: een *situatieblok* (zie onder).

### Scenario

```
Scenario Tom betaalt belasting over zijn vlucht
toetst de regels Kenmerktoekenning belaste reis, belasting op basis van afstand en te betalen belasting
met peildatum 01-01-2024
gebaseerd op de situatie Tom met vlucht

Gegeven is de volgende situatie:
de parameter het lage basistarief eerste schijf is 100,00 EUR

Met als verwacht resultaat:
- V1 is een belaste reis
- de belasting op basis van afstand van Tom is 60,00 EUR
- de te betalen belasting van Tom is 50,00 EUR
- de gegevens zijn consistent
```

**Kop**, in vaste volgorde:
1. `Scenario <scenarionaam>` — naam eindigt bij het `toetst …`-sleutelwoord.
2. Scope, precies één van:
   - `toetst de regel <regelnaam>` — de ENKELE-regelvorm. De regelnaam is een
     vrije tokenreeks die pas eindigt bij het eerstvolgende kopsleutelwoord
     (`met peildatum` / `gebaseerd op de situatie` /
     `Gegeven is de volgende situatie:` / `Met als verwacht resultaat:`). Omdat
     hier GEEN lijst is, mag de naam een kale ` en `, een `,`, of een meerwoord-
     keyword (bv. `in gelijke delen`, `op basis van`) bevatten — dat is precies
     wat het scopen van een §9.7-verdelingsregel of een beslistabelrij mogelijk
     maakt. De naam mag zowel een top-level regel als een resolved-regel noemen:
     een §12-beslistabel desugart naar één resolved-regel per rij, genaamd
     `<tabelnaam> <rijnr>` (bv. `Woonregio factor 1`) zonder eigen top-level
     `regel`-record, dus scopen van zo'n rij gebruikt de resolved-regelnaam.
   - `toetst de regels <regelnaam>, <regelnaam> en <regelnaam>` — de LIJSTvorm,
     met separatoren `,` en `en`; een regelnaam die zelf een kale ` en ` of `,`
     bevat is HIER niet uitdrukbaar (gedocumenteerde beperking, consistent met de
     bestaande EN-splitsing in namen — gebruik dan de enkele-regelvorm hierboven).
   - `toetst de regelgroep <groepnaam>` — zelfde vrije-tokenreeks-naam als de
     enkele-regelvorm.
3. Optioneel: `met peildatum <datumwaarde>` (dd-mm-jjjj, optioneel met tijd).
4. Optioneel, herhaalbaar: `gebaseerd op de situatie <situatienaam>`.

**Situatiesectie** (optioneel als de kop op situaties gebaseerd is):
`Gegeven is de volgende situatie:` gevolgd door een situatieblok.

**Verwachtingsectie** (verplicht): `Met als verwacht resultaat:` gevolgd door
één of meer verwachtingregels (bullets).

Een afsluitende `.` per regel/bullet wordt getolereerd, zoals elders in de
grammatica. Scenario's en situaties mogen vrij gemengd staan tussen definities
en regels; een `Situatie` moet vóór het eerste scenario staan dat ernaar
verwijst (declaratie-vóór-gebruik, resolver-check).

### Situatieblok

Een reeks van, in willekeurige volgorde:

**Instantie** — opener + gebulletteerde toewijzingen:

```
een <objecttypenaam> <LABEL>
- de|het <attribuutnaam> is <waarde>
- de|het <rolnaam> is <LABEL>
- de|het <rolnaam-meervoud> zijn <LABEL> en <LABEL>
- is [een|de|het] <kenmerknaam>            (bijvoeglijk kenmerk)
- heeft [een|de|het] <kenmerknaam>         (bezittelijk kenmerk)
```

- `<LABEL>` is één hoofdletter-geleid woord (`UPPER_ID`: `V1`, `Tom`), uniek
  binnen de samengestelde situatie, en mag niet gelijk zijn aan (het eerste
  woord van) een objecttypenaam — resolver-ERROR.
- `de <naam> is <waarde>` vs. `de <rolnaam> is <LABEL>` wordt door de resolver
  onderscheiden (waarde-alternatief vs. label-alternatief in de parse;
  naamresolutie is post-parse, zoals overal).
- Een to-many-rol krijgt de `zijn`-lijstvorm; een to-one-rol de `is`-vorm
  (arity-check in de resolver tegen het feittype).

**Parameterwaarde** — ongebulletteerd (dat onderscheidt hem van een
instantie-toewijzing):

```
de parameter <parameternaam> is <waarde>
```

**Waarden** (de bestaande literaltokens; niets nieuws):

| datatype | vorm |
|---|---|
| Numeriek | `NUMBER` met eenheid waar de declaratie er één eist: `200 km`, `100,00 EUR`, `3/5`, `25 %` |
| Datum (in dagen) | `01-01-2024` |
| Datum en tijd | `15-06-2024 09:30:00.000` |
| Boolean | `waar` / `onwaar` |
| Enumeratie | `'waarde'` |
| Tekst | `"waarde"` |

Een numerieke waarde in een ANDERE maar verenigbare eenheid dan de declaratie
(bv. `2 uur` op een minuut-attribuut) wordt bij het resolven exact rationaal
omgerekend; een onverenigbare eenheid is een resolve-time ERROR (gate G4).

### Verwachtingregels

```
- de|het <attribuutnaam> van <LABEL> is <waarde>
- de|het <attribuutnaam> van <LABEL> is leeg
- <LABEL> is [een|de|het] <kenmerknaam>          (kenmerk aanwezig)
- <LABEL> is geen <kenmerknaam>                  (kenmerk afwezig)
- <LABEL> heeft [een|de|het] <kenmerknaam>       (bezittelijk, aanwezig)
- <LABEL> heeft geen <kenmerknaam>               (bezittelijk, afwezig)
- de gegevens zijn inconsistent                  (≥1 Inconsistentie-feit verwacht)
- de gegevens zijn consistent                    (géén Inconsistentie-feit)
```

## Semantiek

- **Compilatie**: het hele document (alle definities + alle regels) compileert
  via de bestaande pijplijn; de scope verandert NIETS aan wat er vuurt — hij
  bepaalt alleen dekking, de markergate en de parametergate. Beide back-ends
  blijven semantisch onaangeroerd.
- **Per scenario**: verse sessie, verse facts. Situatiebronnen mergen in
  volgorde: eerst de `gebaseerd op`-situaties (in schrijfvolgorde), dan de
  inline sectie. Eenzelfde `LABEL` in een latere bron is DEZELFDE instantie
  (extra toewijzingen; een her-toewijzing van hetzelfde attribuut wint later).
  Een latere parameterwaarde overschrijft een eerdere (het
  "forceer parameterwaarde"-analoog).
- **peildatum** zet de `rekendatum`-global. Verwijst een scope-regel naar
  Rekendatum/Rekenjaar en heeft het scenario geen peildatum → resolver-ERROR.
- **Parametergate**: elke parameter waarnaar een scope-regel verwijst moet een
  waarde hebben in de samengestelde situatie → anders resolver-ERROR (nooit een
  fire-time verrassing).
- **Typering**: elke situatie- en verwachtingswaarde type-checkt tegen de
  declaratie met de BESTAANDE Typering-machinerie (datatype, eenheid,
  kenmerk-bestaan, rol-uit-feittype, arity). Elke fout is een resolve-time
  ERROR mét regelnummer.
- **Vergelijking** (exact, als de bestaande suites): numeriek via
  `BigDecimal.compareTo` na exacte eenheidsconversie; datums via `equals`;
  tekst/enumeratie via `equals`; kenmerk/boolean via de boolean-lezing;
  `leeg` ⇔ `null`.
- **Trace**: `AgendaEventListener`-gebaseerd — per vuring regelnaam, de
  gebonden instantie(s) (terugvertaald naar LABELs), en de geproduceerde
  waarde(n). Standaard afgedrukt bij FAIL; altijd opvraagbaar (`--trace`).
- **Dekking per scenario-run**: per regel in scope `getest/gevuurd/niet-geraakt`.

## Runner & CLI

- Runner leeft in `engine/`. Voorkeur was de exec-model-back-end (het shippable
  pad), maar die bleek **invasief onverenigbaar** met een in-memory-bean-loader:
  `ExecModelTranspiler`'s fact-`Class.forName` laat zich nog inert doorvoeren
  (een `ThreadLocal`-classloader om `run()`, ~26 regels, geen gedragsverandering
  zonder argument), maar de LambdaMetafactory-accessors van `runtime.Accessors`
  binden een fact-getter via een `Lookup` op de PARENT-loader — en een
  parent-`Lookup` kan een klasse van een CHILD-loader niet linken, dus een run
  tegen in-memory-beans `NoClassDefFound`t bij de eerste consequence. Dat raakt
  de vertaallogica (Accessors zou een per-owner-`Lookup` moeten krijgen), ver
  boven het "inerte doorvoer"-budget. **Beslissing: het DRL-pad als
  gedocumenteerde fallback** — `DrlTranspiler` + `KieHelper.setClassLoader(cl)`
  (het bewezen TokaVuurTest-patroon), met de `FactModelGenerator`-beans één keer
  per bundel in-memory gecompileerd (`engine.BeanCompiler`) en `ContractValidator`
  ervoor. De differentiële suites pinnen DRL==exec al, dus de runner-oordelen
  zijn óók die van het shippable pad. De ExecModelTranspiler-doorvoer is NIET
  gemergd (fallback ⇒ transpiler onaangeraakt). Eén detail van het DRL-pad: een
  MARKER-regel emitteert een `/* TODO … */` die het MVEL-dialect niet parseert en
  anders de hele DRL-compilatie zou kelderen; de runner **strippt** die regel-
  blokken vóór compilatie (een marker is per definitie inert en vuurt niets), en
  de Dekking-manifest markeert hem alsnog als MARKER → scope-marker leest exit 3.
  Engine-pom: `drools-mvel` is expliciet toegevoegd (de root-pom test-scope't hem;
  het DRL-dialect heeft hem nodig).
- CLI: `io.github.yhavinga.regelspraak.engine.ScenarioRunner <file.rgs>…` — meerdere
  bestanden worden in volgorde geconcateneerd (het TokaVuurTest-patroon:
  objectmodel + regels + scenariobestand). Per scenario `PASS`/`FAIL` naar
  stdout; bij FAIL per verwachting verwacht/werkelijk + de trace.
- **Exitcodes**: `1` zodra een scenario FAALT; anders `3` zodra een regel in
  scope een MARKER is (Dekking niet-LOWERED — accepted-but-inert mag nooit als
  PASS lezen); anders `0`. Resolve-errors: `1` met de diagnostics.
- `--manifest <bestand>`: draait ELKE manifestregel in ÉÉN JVM (i.p.v. één JVM
  per regel). Per regel `<verwachte-exit> <scenario.rgs> <companion.rgs…>`; de
  bron wordt in dezelfde volgorde geconcateneerd, per regel dezelfde
  `OK (exit N)` / `MISMATCH …`-uitvoer + de golden-samenvatting als de shell-loop,
  en dezelfde per-regel exit-semantiek (1 FAIL/resolve-fout, 3 MARKER, anders 0).
  De gedeelde TOKA-fundering + fact-beans worden via de content-gekeyde
  `BeanCompiler`-cache hergebruikt, dus de 31 regels compileren de beans één keer
  i.p.v. per regel. Overall exit: `0` als de golden set slaagt, anders `1`.
- `make scenario-test FILES="…"` draait de CLI; `make scenario-test` zonder
  argument draait de golden TOKA-set via `--manifest examples/toka-scenarios/manifest`
  (één JVM).

## Gates (definition of done — zie de backlogcard van 2026-07-04)

- **G1** `ScenarioOrakelTest`: ≥ 40 triples uit de bestaande executiesuites
  reproduceren IDENTIEKE waarden via het scenariopad (Java-API == runner).
  Geleverd: 64 triples (engine-suite 88/88).
- **G2** golden TOKA: 30/30 vurende manifestregels PASS via `make scenario-test`
  (`examples/toka-scenarios/manifest`, één scenario per gelowerde manifestregel,
  scope = precies één regel). De exit-3 MARKER-lezing wordt geoefend door
  `scenario-marker-exit3.rgs` (een schoon markerende regel, exit 3). De TOKA-
  markerregel toka-23 ("Bevestigingstijdstip vlucht") is BOVENDIEN spec-illegaal
  getypeerd — A = datum-tijd PLUS datum-tijd, door §4.1 Tabel 2 verboden — dus
  een bundel met toka-23 resolvet NIET en de CLI leest exit 1 (resolve-fout), niet
  exit 3: de runner mag een ill-getypeerde bundel nooit stil laten passeren. Het
  manifest verwacht daarom exit 1 voor toka-23 en exit 3 voor de schone marker.
- **G3** mutatiegate: ≥ 10 bewust-foute verwachtingen FALEN elk met een
  diagnose die regel + afwijkende waarde noemt (RED-first).
- **G4** typegate: ≥ 6 slecht-getypeerde scenario's zijn resolver-ERRORS met
  regelnummer.
- **G5** geen regressie: `mvn -o clean verify` ≥ 850 groen, `make test`-vloer
  ongewijzigd, corpus 192-0-1, engine 4/4, DrlTest-byte-pins onaangeroerd.
- **G6** isolatie: elk corpusbestand parseert uitkomst-identiek pre/post
  (CorpusVuurTest-partities 59/54/79/9 ongewijzigd).
