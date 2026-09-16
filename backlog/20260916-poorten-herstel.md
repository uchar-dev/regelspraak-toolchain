# poorten-herstel: de twee pre-existente rode poorten en de scenariotelling

Aanleiding: de naport-lens (2026-09-16) mat de volledige poortenset en
vond, byte-identiek rood op basis 7bbd0ee en dus los van alle recente
kaarten:

1. `make verify-corpus` — exit 2, `corpus: 54 passed, 1 failed`, één
   rgs-bron onder `UNEXPECTED FAILURES`.
2. `service/` `mvn -o test` — 84 tests, 1 failure, 17 errors; de lens zag
   als eerste diagnose een resolverfout "min: als één operand een eenheid
   heeft, moeten beide er een hebben" op een bron in de suite.
3. `make scenario-test` meldt 29/29 waar `CLAUDE.md` 30/30 als poort
   noemt.

De maintainer wil de poortenset hersteld. Eerst meten, dan pas bewegen:
elk van de drie krijgt een oorzaakmeting (welke bron, sinds welke commit
— `git log`/bisect over de poortuitvoer) vóór er iets wijzigt.

## Acceptatie

- (a) Oorzaakmeting per punt in het Result: de falende bron bij naam, de
  commit of pin-bump die hem rood maakte, en het oordeel of de bron, de
  verwachting of de telling moet bewegen. Fail fast: een terechte
  diagnose (een werkelijk inconsistente bron) wordt NIET weggewerkt met
  een resolverversoepeling; dan beweegt de bron of de verwachting, met
  reden.
- (b) `make verify-corpus` exit 0.
- (c) `service/` `mvn -o test` groen (0 failures, 0 errors), of — als een
  falend geval een terecht geweigerde bron blijkt — een reden-dragende,
  bij naam genoemde uitzondering in de suite zelf, nooit een stille skip.
- (d) `make scenario-test` en de `CLAUDE.md`-telling stemmen overeen;
  welke van de twee beweegt volgt uit de meting (een verdwenen test
  herstellen wint van de telling verlagen, tenzij de test bewust
  verwijderd blijkt — dan de telling met bronvermelding).
- (e) De overige poorten blijven groen: `mvn -o -B test` (980+/0F),
  `make test` exit 0 (expliciet), `make uchar-conformance` PASS,
  `make facts`, `make verify-negatief-corpus`, `make engine`.
- (f) `vendor/`, `SPEC_REV`, `scripts/` alleen aanraken als de meting
  het dwingt, met de reden in het Result.
- (g) Wijziging van bestaande tests alleen met gemelde reden per geval.

## Result

Alle drie de punten gemeten vóór er iets wijzigde. Twee oorzaken, geen
drie: punt 2 en punt 3 komen uit dezelfde herpin.

Na de lens is deze ronde op zes punten gecorrigeerd: de toeschrijving
bij punt 1 was fout en is vervangen door de meting die haar weersprak,
de eerste fix opende een nieuwe stilte op het andere been, en het
Dockerfile-recept droeg de oorspronkelijke oorzaak nog. De correcties
staan hieronder verwerkt, niet los eronder geplakt.

### Oorzaakmeting

**Punt 1 — `make verify-corpus`.** De falende "bron" is geen bron: het is
het glob-patroon `examples/spec-main/*.rgs` zelf. Die map materialiseert
alleen wanneer de externe, niet-meegeleverde specificatie lokaal
aanwezig is; zonder die specificatie laat bash het patroon ongemoeid in
de woordenlijst staan, `Parse` struikelt over een bestand dat er nooit
was, en de lus telt dat als één UNEXPECTED FAILURE — vandaar de lege
diagnose achter `*.rgs ::` en het totaal van 55 waar er 54 fragmenten
zijn.

**Geen triggercommit.** De eerste ronde schreef dit toe aan `b85dff3`
(2026-07-20, de redistributiegrens); die toeschrijving is door meting
weerlegd en hier gecorrigeerd. De poort zelf gedraaid op oudere punten:
op `b85dff3~1` exit 1 met `54 passed, 1 failed (of 55)` en `*.rgs ::`,
en op `a983f1e` (2026-06-17, de oudste commit die het script raakt)
exit 1 met dezelfde telling en `*.rs ::` — de extensie van vóór de
hernoeming. `git ls-tree` over `specification/` op `a983f1e`,
`906b9c2`, `b85dff3~1` en `b85dff3` laat telkens uitsluitend
`RegelSpraak-spec-for-ANTLR-v2.3.0.md` en `conversie-bevindingen.md`
zien: de volledige specificatie is NOOIT getrackt geweest, en
`examples/spec-main/` stond al op `b85dff3~1` in `.gitignore`. In elke
schone checkout bestond die map dus nooit en was de poort altijd al
rood. `b85dff3` voegde alleen het `if -f` rond de extractiestap toe en
liet de lus eromheen ongemoeid — het maakte de fout niet, het ging er
langs. Oordeel: een defect van het HARNAS vanaf de invoering van het
script. Bron en verwachting zijn allebei in orde.

**Punt 2 — `service/` `mvn -o test`.** Twee oorzaken, geen zeventien.

*Oorzaak 2a (16 van de 17 errors plus de ene failure).* Eén bronbestand
in de geserveerde TOKA-set resolvet niet: drie resolverfouten "min: als
één operand een eenheid heeft, moeten beide er een hebben". Dat is het
tweede casus-quirkbestand, dat de javadoc van
`service/src/test/java/io/github/yhavinga/regelspraak/service/TokaBron.java`
nu bij naam uitsluit; het scenariomanifest markeert hem al sinds
2026-08-29 als exit-1. `DescriptorLoaderTest`'s `@BeforeAll` valt om op
`MODEL.errors().isEmpty()` (de ene failure), en elke test die een
`BeslisserKern` bouwt krijgt `DienstFout: descriptor/model-validatie
faalt (3 schending(en))` — precies diezelfde drie resolverfouten, als
MALFORMED doorgegeven. Eén oorzaak, zestien errors.

Triggercommit `e87964a` (2026-08-29), de SPEC_REV-herpin voor de
besluiten 15c/16Y/17b. Gemeten met wegwerp-worktrees over de poort zelf,
niet uit de commitboodschap afgeleid: dezelfde bronset door `make ir`
geeft op de ouder `3f847d8` exit 0 en nul eenheidsfouten, op `e87964a`
zelf exit 2 met drie, en op de kaartbasis `2eaa7c5` opnieuw exit 2 met
drie. Die commit spiegelde de weigering al in het manifest, in de
casus-quirklijst en in de vuurgetuige — maar niet in de dienstfixture.
Oordeel: de diagnose is TERECHT (de bron is dimensioneel inconsistent
zoals geschreven; de spiegelrepo houdt dezelfde holdout aan), dus geen
resolverversoepeling. De VERWACHTING beweegt: de bron is geen
geserveerde regel meer.

*Oorzaak 2b (de zeventiende error).* `PlaygroundTest` viel om op
`NoSuchMethodError: java.lang.String ...ir.Expr.bron()`. Geen
repodefect: `service/` bouwt zijn bovenliggende modules niet zelf en
las een `regelspraak-engine`-artefact van 16 juli uit de lokale
Maven-repository, gecompileerd tegen een `Expr` waarvan `bron()` nog
`String` teruggaf. Na de gedocumenteerde keten
(`mvn -o install -DskipTests` in de root, dan in `engine/`) is die error
weg: 17 errors werden 16, `PlaygroundTest` 4/4 groen. Geen wijziging
nodig, wel het vermelden waard — wie `service/` los draait meet anders
dan de keten.

**Punt 3 — `make scenario-test` 29/29 versus `CLAUDE.md` 30/30.**
Zelfde triggercommit `e87964a`. Die commit zette één manifestregel van
`0` naar `1` met vier regels commentaar erboven (de eenheidsinconsistente
casus), en paste `ScenarioRunnerCli` aan met de opmerking "29
exit-0-scenario's sinds besluit 16Y". Dat is een AANTOONBAAR BEWUSTE
verwijdering, geen verdwenen toets: de weigering is het besluit. De
poortenlijst in `CLAUDE.md` bleef als enige achter. Oordeel: de TELLING
beweegt, met bronvermelding.

### Wijzigingen

1. `tools/verify_corpus.sh` — `nullglob` rond de corpuslijst, zodat een
   niet-bestaand pad verdwijnt in plaats van als fragment te tellen.
   Commit `f2cae8d`.

   **Die fix alleen was niet af, en de lens ving dat.** `nullglob` maakt
   ELK been stil, ook het been dat niet optioneel is:
   `examples/spec-antlr/` wordt geëxtraheerd uit het GETRACKTE
   `specification/v2.3.0/RegelSpraak-spec-for-ANTLR-v2.3.0.md`, en die
   extractie had geen `if -f` en geen leegtecontrole. Zelf nagemeten
   door dat bestand tijdelijk weg te halen: op `f2cae8d` meldde de
   poort STIL GROEN — exit 0, `47 passed, 0 failed (of 47)` — waar zeven
   fragmenten onzichtbaar verdwenen. Commit `f82991d` geeft dat been
   dezelfde behandeling en zet er een totaalvloer bovenop:
   - ontbrekend getrackt specbestand → exit 2, "dat bestand is getrackt,
     geen externe bron" (gemeten);
   - extractie levert nul snippets (bestand leeggemaakt) → exit 2
     (gemeten);
   - `EXPECTED_MIN_SNIPPETS=54`, met datum en reden in het script → één
     `examples/toka/`-fragment weggehaald geeft exit 2, "53 fragmenten
     gevonden, vloer is 54" (gemeten).

   De vloer staat bewust op 54 en niet hoger: dat is 47 getrackte
   fragmenten onder `examples/toka/` plus 7 uit de getrackte
   ANTLR-mapping, samen haalbaar in ELKE schone checkout.
   `examples/spec-main/` telt er niet in mee — dat been hangt aan de
   externe, niet-meegeleverde specificatie en zou de vloer voor iedereen
   zonder die specificatie onhaalbaar maken. Dat onderscheid staat in
   het script zelf. (De opdracht noemde 47 als vloerwaarde; de meting
   wijst 54 aan — 47 is het `toka/`-been alleen, de 7 antlr-fragmenten
   horen er nog bij en zijn net zo goed getrackt. De intentie
   — haalbaar zonder de externe spec — is aangehouden.)
2. `TokaBron` sluit het tweede quirkbestand uit, met reden en met
   verwijzing naar de drie harnassen die dezelfde holdout al aanhielden
   — precies zoals het eerste quirkbestand er al buiten stond.
   `examples/toka/toka.dienst.json` verliest het bijbehorende
   entrypoint: een regel die niet resolvet kun je niet serveren. Het
   attribuut zelf blijft bestaan en blijft `invoerElement` van de regel
   die erop rekent. De gepinde tellingen in `DescriptorLoaderTest`,
   `BeslisserKernTest`, `GoldenOverHttpTest`, `DienstHttpTest`,
   `GoldenOverQuarkusTest`, `TokaTestProfile` en de javadoc van
   `BeslisserKern`/`Uitvoerder` gaan van 30 naar 29; de OpenAPI-golden
   verliest dat ene pad (59 regels, verder byte-identiek).
   `RegelBestandIndexTest` toetst de AFWEZIGHEID in plaats van de test
   te schrappen, zodat een stille herintroductie alsnog faalt. Ook
   `docs/ENGINE.md` landde hier. Commit `9024a1d`.
3. `CLAUDE.md` poort 4 van 30/30 naar 29/29 met de reden en de datum
   erbij, en poort 6 van `service ≥ 146, service-quarkus ≥ 34` naar
   `≥ 145, ≥ 33` — dezelfde holdout kost elke module precies één
   dynamische golden-toets, en een vloer boven HEAD is een permanent
   rode poort. `docs/SCENARIOTOETSEN.md` G2 volgt. Commit `c055f1a`.
4. **`docker/Dockerfile` bakte de oorzaak nog in.** Het `RUN`-recept dat
   het bronmanifest schrijft filterde alleen `toka-23-`, dus het
   gebakken manifest droeg het tweede quirkbestand nog — precies de
   bronset die `BeslisserKern.bouw` laat omvallen. Statisch nagemeten
   zoals de lens deed, door de bestandenlijst van het recept na te
   bouwen en door `make ir` te halen: het oude recept levert 48
   bestanden en exit 2 met drie eenheidsfouten, het nieuwe 47 bestanden
   en exit 0 met nul. Het nieuwe recept is bovendien regel-voor-regel
   identiek aan `TokaBron.bronBestanden()`. Filter nu
   `grep -v -e 'toka-23-' -e 'toka-45-'`, met een commentaarregel die
   het verband met `TokaBron` en het gevolg (geen poortbinding, dus
   `HEALTHCHECK` faalt) benoemt. Pre-existent op de basis, maar het is
   dezelfde oorzaak die deze kaart overal elders wegnam. Commit
   `3fc0f01`.
5. Drie achtergebleven 30-tellingen binnen dezelfde sweep:
   `scripts/kind-smoke.sh` (de drie onvoorwaardelijke H2-echo's — poort
   (f) staat `scripts/` toe nu de meting het dwingt),
   `ScenarioRunnerCli`'s javadoc "all 30 exit-0 lines" (die naast code
   stond die 29 zegt), en `TokaBron`'s javadoc "no toka-23" bij
   `model()`. Commit `1c8f2ee`.

Bestaande tests gewijzigd, met reden per geval: alle bovengenoemde
30→29-pins (de descriptor telt één entrypoint minder) en
`RegelBestandIndexTest` (van aanwezigheids- naar afwezigheidstoets).
`vendor/` en `SPEC_REV` zijn niet aangeraakt; `scripts/kind-smoke.sh`
wel, met de reden hierboven.

### Poortenstand na afloop

| poort | uitkomst | exit |
|---|---|---|
| `make verify-corpus` | 54 passed, 0 failed, 0 known-defect (of 54) | 0 |
| `service/` `mvn -o -B test` | 145 tests, 0F/0E/0S | 0 |
| `service-quarkus/` `mvn -o -B test` | 33 tests, 0F/0E/0S | 0 |
| `make scenario-test` | golden TOKA 29/29 PASS, marker-exit-3 ok | 0 |
| `mvn -o -B test` (root) | 980 tests, 0F/0E, 3 skipped | 0 |
| `make test` | 552 tests, 0 failed, 1 aborted, 34 containers | 0 |
| `make engine` | 115 tests, 0F/0E | 0 |
| `make facts` | PASS, 5 klassen gegenereerd + gecompileerd | 0 |
| `make verify-negatief-corpus` | 52 snippets, 41 rejected, 11 as expected (1 `[GAT]`), 4 strict cross-checks | 0 |
| `make uchar-conformance` | PASS, 58 bestanden byte-identiek, 47 goldens gereproduceerd | 0 |

### Afwijkingen en open punten

- Assumptie-bewaakte stiltes, alle pre-existent en alle van dezelfde
  klasse: 3 skips in `mvn -o -B test` (twee op de afwezige externe
  specificatiefixtures onder `spec-main`, één op een niet-gezette
  `-Duchar.dir`/`UCHAR_DIR`) én 1 abort in `make test`
  (`DrlTest.corpus_drl_bevat_geen_enkele_function_definitie`, "Assumption
  failed: extracted corpus absent"). Vier toetsen die zichzelf
  uitschakelen zonder dat de poortsamenvatting erover valt. Buiten scope
  van deze kaart, hier genoteerd — het is dezelfde soort stilte die punt
  1 en punt 2 van deze fixronde wél dichtzetten.
- `service/` `mvn -o test` meet alleen juist ná de gedocumenteerde
  installatieketen. Niets aan de poort zelf gewijzigd; wie hem los
  draait op een oude lokale Maven-repository ziet nog steeds een
  `NoSuchMethodError` die niets met deze repo te maken heeft.
- Het gebakken container-image: de eerste ronde noteerde hier het
  verkeerde gevolg ("het entrypoint verdwijnt uit het image"). Het
  werkelijke gevolg van het ongewijzigde Dockerfile-recept was dat het
  image NIET OPKOMT — `BeslisserKern.bouw` valt om op de niet-resolvende
  bronset, `DienstMain` bindt de poort pas ná het compileren van elke
  gescoopte regel, dus de `HEALTHCHECK` haalt het nooit. Dat is met
  wijziging 4 verholpen en statisch nagemeten. `make image` en
  `make kind-smoke` zijn hostpoorten en zijn hier niet gedraaid; de
  controle liep via het nagebouwde recept door `make ir`.
- De kaart is niet naar `backlog/done/` verplaatst; dat is de landende
  hand van de beheerder.
