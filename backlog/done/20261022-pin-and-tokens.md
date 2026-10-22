# pin-and-tokens: pin naar uchar met de semantic tokens, en `bin/regelspraak tokens`

*Opdracht van de eigenaar 2026-10-22 (syntax highlighting van de regels in de werkbank), vastgelegd door de
hoofdsessie. Vervolg op de uchar-kaart `semantic-tokens` (uchar `backlog/done/20261022-semantic-tokens.md`).*

## Aanleiding

uchar main draagt sinds 2026-10-22 de semantic tokens: `Resolver.tokens(List<SemanticTokens.SourceFile>)` geeft per
bronbestand spans die de tekst dekken, met een soort (sleutelwoord, commentaar, objecttype, attribuut, rol, parameter,
domeinwaarde, `unresolved`, …), een positie (regel vanaf 1, kolom in UTF-16) en bij een verwijzing de plek van haar
declaratie; de diagnostics met code staan in dezelfde uitvoer (uchar `grammar/DIVERGENCES.md`, "Semantic tokens"). De
werkbank roept de toolchain aan via `bin/regelspraak` en wil de regels daarmee kleuren. Verder brengt de pin de
landingen van die dag mee: de datumconstructie in ref en clingo (EF-032), een vouw in een vouwfilter (brug K13 van de
v2-regelset) en een keten met ten hoogste één deelselectie.

## Doel

- De pin naar uchar main op het moment van de start (minstens `5d8a06de`), met wat de distributie daarvoor moet
  bijwerken (replay-pin, lijsten van de servicegates, de TOKA-manifestuitzonderingen).
- Een subcommando `bin/regelspraak tokens <out.json> <source.rgs...>` dat de uitvoer van `Resolver.tokens` schrijft,
  zoals `ir` het IR schrijft: de bestanden in de volgorde van de aanroep, de uitvoer ook bij ERRORs en syntaxfouten,
  exit 0 als het bestand geschreven is. Usage en `README.md` noemen het. Geen service-endpoint in deze kaart.

## Acceptatie

- (a) Een test in de toolchain die `tokens` op een eigen klein model met twee bestanden draait en de vorm leest (een
  verwijzing over de bestandsgrens, een onopgeloste naam).
- (b) Meting: `bin/regelspraak tokens` op de v2-regelset herleving-WW van de werkbank (fundament en rules, alleen
  lezen) dekt alles, zonder `unresolved`; tijd en telling in de Result.
- (c) `make gates` groen.

## Result (2026-10-22)

Tak `card/pin-and-tokens`, vanaf toolchain main `6b86e79f`, gerebased op `82a88b42` (explanation-ground-source). Geraakt: de pin `uchar`, `bin/regelspraak`, `README.md`,
`scripts/check-service-replay.sh` (de replay-pin), een nieuwe testklasse
`toolchain/src/test/java/dev/uchar/regelspraak/toolchain/cli/TokensCommandTest.java`, en deze kaart. Niet geraakt: de
Verbalisator, `OpenApiEmitter`, de dienst, de Makefile, `uchar/` buiten de pin.

- `203e4561` de kaart naar `doing/`.
- `924ed95c` pin uchar `680e5263` → `5d8a06de` (33 commits); replay-pin 234 → 238; README-getallen bijgewerkt.
- `12a9b8d2` `bin/regelspraak tokens`, de test van (a), usage en README.
- `1e256faa` (hoofdsessie, na review) een uitvoerpad in een map die niet bestaat is bij `ir` en `tokens` een
  gebruiksfout (exit 2, "de map bestaat niet: …") in plaats van een stacktrace van uchar; README: de replay duurt
  ±90 s (88 s gemeten).

### De pin

uchar main `5d8a06de` (de gepushte tip bij de start). Brengt mee: de semantic tokens (`Resolver.tokens`,
`Resolver --tokens`), de datumconstructie in ref en clingo (EF-032), een vouw in een vouwfilter (K13: RGS-350/351/352),
een keten met ten hoogste één deelselectie. `make check-pin`: PASS, de versies (ANTLR, Drools, Jackson, JUnit) gelijk.

Wat verschoof:

- **De replay-pin.** De set van uchars gate 12 groeit van 264 naar 268 met date-with-parts,
  fold-filter-nested-aggregate, fold-filter-inner-fold-via-element-role en jutterswet-schone-maand-na-zware-klacht; alle
  vier spelen ook door de dienst: `EXPECTED_REPLAY_PLAYED` 234 → 238, met de reden in het script. `REPLAY_EXCLUDED`
  ongewijzigd (30). `check-service-replay`: PASS, 238 goldens, 635 cases, 1140 zinnen uitleg, 1301 uitgelegde
  voorwaarden (vloer `EXPECTED_MIN_EXPLAINED_CONDITIONS=1241`, niet verhoogd: een vloer groeit niet mee), 0 keer "niet
  is vast te stellen", 88 s met vier diensten.
- **README**, sectie "The gates": de getallen stonden nog op `EXPECTED_PLAYED` 255 en `EXPECTED_REPLAY_PLAYED` 230
  (verouderd sinds 2026-10-19); nu 268 en 238. "About 50 seconds" niet aangepast (gemeten nu 88 s; machineafhankelijk).
- **Niets anders.** De reactor (`mvn -B -o verify`) groen zonder wijziging: backend 1649, toolchain 163 (vloer 120),
  service 360 (vloer 235), service-quarkus 53 (vloer 43), 9:18 min. `check-twins`: PASS, ongewijzigd (3 tweelingen, 18
  cases; vocabulairetweeling 2 cases in 34 verzoeken; getuige 4). `make scenario-test`: 32 regels, 29 ok, 3 uitgesloten,
  0 mismatch: `scripts/toka-manifest-exclusions.txt` ongewijzigd.

### `bin/regelspraak tokens`

```
bin/regelspraak [--language nl|en] tokens <out.json> <source.rgs...>
```

Roept uchars `Resolver --tokens` aan (`Resolver.tokensMode` op `Resolver.tokens`), zoals `ir` uchars `--ir` aanroept;
de toolchain voegt alleen de controle van de aanroep toe (`ir`'s patroon: geen uitvoerbestand, een optie als
uitvoerbestand of tussen de bronnen, een bron die niet bestaat → exit 2 met een Nederlandse melding, en niets
geschreven). De bestanden in de volgorde van de aanroep, elk met de naam zoals gegeven; de uitvoer ook bij een ERROR en
een syntaxfout, exit 0 als het bestand geschreven is; de diagnostics ook op stderr, met bestand en regel. `--language
en` geeft uchars meldingen in het Engels (ook in de JSON); de samenvattingsregel op stdout (`tokens: 2 bestand(en), …`)
is uchars eigen regel en blijft Nederlands, zoals bij `ir`. Een uitvoerpad in een map die niet bestaat is sinds `1e256faa`
bij `tokens` en `ir` een gebruiksfout van het script (exit 2, "de map bestaat niet: <map>"); daarvoor stopte uchar
met een stacktrace (NoSuchFileException, exit 1).

Voorbeeld, het model van de test (`boten.rgs` declareert, `regels/havengeld.rgs` heeft de regels):

```
$ bin/regelspraak tokens tokens.json boten.rgs regels/havengeld.rgs
  WARNING (regels/havengeld.rgs:8): onopgelost: de naam 'het liggeldtarief' is geen gedeclareerde parameter, … blijft als marker op de wire staan
tokens: 2 bestand(en), 1 diagnose(s) geschreven naar tokens.json
$ echo $?
0
```

```json
{"files": [{"file": "boten.rgs", "spans": [
   {"line": 4, "column": 7, "endLine": 4, "endColumn": 13, "kind": "attribute", "text": "lengte", "declaration": true}, …]},
 {"file": "regels/havengeld.rgs", "spans": [
   {"line": 4, "column": 20, "endLine": 4, "endColumn": 26, "kind": "attribute", "text": "lengte", "declaredAt": {"file": "boten.rgs", "line": 4, "column": 7}},
   {"line": 4, "column": 44, "endLine": 4, "endColumn": 55, "kind": "parameter", "text": "lengtegrens", "declaredAt": {"file": "boten.rgs", "line": 1, "column": 13}},
   {"line": 8, "column": 81, "endLine": 8, "endColumn": 94, "kind": "unresolved", "text": "liggeldtarief"}, …]}],
 "diagnostics": [{"severity": "WARNING", "code": "RGS-092", "params": {…}, "message": "onopgelost: …", "file": "regels/havengeld.rgs", "line": 8}]}
```

De vorm, de soorten, de positieconventie (regel vanaf 1 per bestand, kolom vanaf 0 in UTF-16, einde exclusief) en de
LSP-tabel staan in uchar `frontends/regelspraak/grammar/DIVERGENCES.md`, "Semantic tokens"; de README verwijst ernaar
en herhaalt ze niet. Geen service-endpoint (buiten de kaart), geen `make tokens`-doel (de kaart vraagt usage en README;
uchars kaart koos ook geen make-doel).

### Acceptatie (a): `TokensCommandTest`

De test draait `bin/regelspraak` zelf (bash, als proces), zodat hij het commando toetst dat een afnemer aanroept en niet
uchars Java-ingang, die uchars eigen suite al toetst. Het script draait op `REGELSPRAAK_JAR`, hier een jar met alleen
een manifest waarvan `Class-Path` het testklassepad van de module is: geen build, geen gedeelde jar. Vier tests:

1. twee bestanden (het tweede in een submap, om "de naam zoals gegeven" te tonen): exit 0; `files` in de volgorde en met
   de namen van de aanroep; de declaratie `lengte` in `boten.rgs` (regel 4, kolom 7, `declaration: true`); de
   verwijzing in het tweede bestand met `declaredAt` naar die plek over de bestandsgrens; de parameter idem; de
   onopgeloste naam `liggeldtarief` als `unresolved` zonder `declaredAt`, en een diagnose met `RGS-`-code op
   `regels/havengeld.rgs:8`;
2. een syntaxfout in het tweede bestand: exit 0, beide bestanden in de uitvoer, een ERROR in `diagnostics` en op stderr;
3. verkeerd gebruik: exit 2 (geen uitvoer, geen bron, een bron die niet bestaat, een optie als uitvoer of tussen de
   bronnen, sinds `1e256faa` ook een uitvoerpad in een map die niet bestaat, met de melding), en er is niets geschreven;
4. de usage noemt `tokens <out.json> <source.rgs...>`.

Rood eerst (na de pin, vóór het script): 1, 2 en 4 rood met "onbekende opdracht: tokens" (exit 2). Test 3 was ook vóór
de wijziging groen, want een onbekende opdracht is ook exit 2; hij bewaakt het verkeerd gebruik van het nieuwe commando,
geen roodgetuige.

### Meting (b): v2-regelset herleving-WW

`bin/regelspraak tokens <out> fundament/rules/*.rgs herleving-ww/rules/*.rgs`, gedraaid in
`regelspraak-werkbank/rulesets/regelspraak-rules/v2` (werkbank `0c18170`, alleen gelezen, uitvoer in de werkmap):
33 bestanden, exit 0, 0 diagnostics.

- **Dekking**, nagerekend met een eigen script tegen de brontekst (`measure_tokens.py` in de werkmap, buiten de
  repository): elk teken dat geen witruimte is ligt in precies één span, geen overlap, de tekst van elke span is de
  brontekst op die positie in UTF-16-kolommen, en elke `declaredAt` wijst naar een span met `declaration: true` van
  dezelfde soort: 0 afwijkingen.
- **`unresolved` 0**, `word` 0, 0 verwijzingen zonder `declaredAt`, 4 `generatedFrom`.
- **Telling**: 6442 spans; 1160 verwijzingen, waarvan 985 over een bestandsgrens. Per soort (declaraties / verwijzingen):
  `attribute` 52 / 363, `objectType` 18 / 270, `role` 28 / 179, `enumerationValue` 44 / 75, `vocabulary` 2 / 97,
  `namedForm` 67 / 23, `characteristic` 16 / 59, `unit` 5 / 61, `variable` 10 / 15, `parameter` 7 / 13, `factType` 14 /
  0, `domain` 5 / 5, `unitSystem` 2 / 0. Lexicaal: `keyword` 3326, `punctuation` 807, `comment` 677, `designation` 99,
  `number` 85, `relationDescription` 14. Gelijk aan uchars meting (c) in `semantic-tokens` via de Java-ingang: de
  distributie voegt niets toe en laat niets weg.
- **Tijd**: 1,49 / 1,59 / 1,53 s wandklok per aanroep, inclusief de start van de JVM, op de jar van de reactor; de drie
  uitvoerbestanden bytegelijk (938 271 bytes).

### Suites

- Reactor na de pin: groen (hierboven). Toolchain-module na het commando: 167 tests (163 + 4), skip-pin PASS (vloer
  120). Volledige reactor op `24398e32`: groen, backend 1649, toolchain 167 (vloer 120), service 360 (vloer 235), service-quarkus 53 (vloer 43), 8:39 min, geen skip.
- `check-pin`, `check-service-replay`, `check-twins`, `scenario-test` groen op de pin (hierboven). Niet gedraaid: `make
  gates` (image en kind-smoke; de hoofdsessie).

### Vondsten

Geen bug in de toolchain, geen nieuwe in uchar. Bij de review van de hoofdsessie: een uitvoerpad in een map die niet
bestaat gaf bij `ir` en `tokens` een stacktrace van uchar; nu een gebruiksfout van het script (`1e256faa`). De README
noemde bij de replay "About 50 seconds"; nu "About 90 seconds" met de meting.

### (c) `make gates`

`make gates TAG=pin-and-tokens CLUSTER=pin-and-tokens` op `1e256faa` (hoofdsessie, 2026-10-22 13:46–13:59): GROEN,
7 stappen in 12m56s. Pin PASS; reactor 1649/167/364/53; twins PASS; replay 238 goldens, 635 cases (pin 238); manifest
32 regels, 29 ok, 3 uitgesloten, 0 mismatch; image `decision-service:pin-and-tokens`; kind-smoke H2, H3, H4 PASS.
