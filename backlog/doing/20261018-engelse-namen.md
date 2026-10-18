# engelse-namen: de code volgt de nieuwe namen van uchar, en haar eigen namen worden Engels

*Vastgelegd op 2026-10-18. Hoort bij uchar-kaart `rename-public-api` (stap 6 van het programma "Engelse namen",
`backlog/doing/20261018-rename-public-api.md` in uchar) en landt daarna. Toolchain `97d25bb`, uchar-pin `9159c9bd`.*

## Aanleiding

uchar schrijft sinds 2026-10-17 alle identifiers in het Engels (`CLAUDE.md` van uchar, Conventions). De distributie las daarvan 359
namen van `backends/drools` en `frontends/regelspraak` en hield ze daarmee Nederlands (`frozen/*.tsv`). Haar eigen code heeft 1.484
Nederlandse namen (13.405 plekken) in `service`, `service-quarkus`, `toolchain`, `scripts`, `tools` en `docker`:
`BeslisserKern`, `Verbalisator`, `NaamLezer`, `FixpuntGegevens`, 406 testzinnen. De regel in `CLAUDE.md` ("the domain vocabulary in
Dutch (`BeslisserKern`, `Verbalisator`)") vervalt; de conventie van uchar geldt hier ook.

## Wat er gebeurt

1. uchars kaart `rename-public-api` landt op uchars `main`; de nieuwe `main` komt in de submodule (`git -C uchar fetch`, geen push).
2. Eigen namen, op de oude pin, met het gereedschap van uchar (`scripts/lib/naming/` van uchars `main`, aangeroepen op de boom van de
   distributie): tabel `scripts/lib/naming/tables/` van de distributie, bevroren lijst `frozen/toolchain.tsv` (wat zichtbaar blijft, zie
   hieronder), `apply --files-only`, `apply` met beslislijst (`triage`, Haiku, `merge-decisions`). Eerst, in een eigen commit, een
   `@JsonProperty("<oude naam>")` op de tien niet-geannoteerde componenten van `DienstDescriptor`, zodat het descriptorformaat niet meer aan
   de componentnamen hangt.
3. Pin naar de nieuwe uchar; `javac_follow.py` (de uchar-tabel als bron) volgt de compileerfouten van de reactor tot javac schoon is. Het
   gereedschap kan geen namen hernoemen die in de submodule gedeclareerd zijn (`git ls-files` ziet de submodule als één item, en een
   lid dat via een waarde gelezen wordt telt als "buiten de zone"); het script vervangt de oude naam op precies de plek die javac meldt.
4. Namen in tekst die javac niet ziet (commentaar, javadoc, README, `docs/ENGINE.md`, de ene `Class.forName` in `GoldenOverQuarkusTest`):
   een grep per oude naam uit de tabel, per hit een beslissing.
5. `CLAUDE.md`: de laatste regel van "Working" verwijst naar de conventie van uchar of neemt haar regels over; de Engelse identifiers gelden
   ook voor bestands- en mapnamen.

Blijft staan: alles wat buiten de code zichtbaar is. JSON-sleutels van de HTTP-API en haar OpenAPI-beschrijving, subcommando's en
vlaggen van `bin/regelspraak`, Make-targets en -variabelen, meldingen, bestandsformaten, Helm- en Dockerconfiguratie (`docker/`:
`DESCRIPTOR`, `BRONNEN_MANIFEST`, `POORT`, `ALLOW_MARKERS`), de enumconstanten die zij zelf als JSON of OpenAPI schrijft
(`Schending.Soort`, `Evaluatiefout.Soort`), `REPLAY_WERKERS`, `bronnenManifest` (Helm), `examples/toka/*.rgs` en de domeininhoud van
testdocumenten. De volledige lijst met waar elke naam zichtbaar is en een voorstel per naam staat in het Result van uchars kaart.

## Acceptatie

- (a) `inventory service service-quarkus toolchain scripts tools docker` meldt alleen bevroren namen.
- (b) `make poorten` groen: check-pin, `mvn -B -o verify` met hetzelfde aantal testgevallen als op `97d25bb` (1.619 / 140 / 332 / 53
  in de laatste meting, plus wat uchar toevoegde), tweelingen, replay, manifest. `make gates` (image en kind-smoke) als Docker en kind er zijn;
  zo niet, dan staat dat in het Result.
- (c) Geen verandering buiten de code: de OpenAPI-goldens, de JSON-goldens en de uitlegtests ongewijzigd (een gewijzigd golden is
  een gevonden zichtbare naam en gaat in de lijst, niet in het golden).
- (d) De pin staat op de nieuwe `main` van uchar (`make check-pin`).

## Omvang

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/`, `tools/`, `bin/regelspraak`, `docs/`, `README.md`, `CLAUDE.md`, de
uchar-pin, deze kaart. Niet `uchar/`. Geen verandering van de zichtbare namen hierboven.

## Result (deel 1: de eigen namen op de oude pin, 2026-10-18)

Stap 6 is niet af: uchars kaart `rename-public-api` staat nog niet op main van uchar. De pin staat daarom nog op `9159c9bd`.
Een volgende sessie doet de pin-bump, `javac_follow.py` en `make poorten` / `make gates` op de eindstand.

**Commits** (branch `card/engelse-namen`, op `915d183`): `a773957` DienstDescriptor met `@JsonProperty` op elke component;
`2cd2293` tabel en bevroren lijst (`tools/naming/`); `fec7922` bestanden en mappen (111, alleen `git mv`); `5f619f1` beslislijst;
`43e73e7` de inhoud (staat in `.git-blame-ignore-revs`); `f0f27f1` grenzen met uchar en de buitenwereld; `4cc6003` `CLAUDE.md` en
README.

**Aantallen.** Tabel 1.362 rijen (1.161 translation, 201 improved, waarvan 7 met een eigen scope). Bevroren lijst 154 namen. Beslislijst
17.841 rijen: 11.0k door `triage --default-keep`, 6.8k rest (69 change in README, `docs/`, `pom.xml`), 14 correcties (paden en
een klassenaam die een string leest), samen 269 change. 12.5k identifiers in 124 inhoudsbestanden. `inventory` meldt nog 7 namen, allemaal in
shellscripts waar een zichtbare naam staat (`--poort`, `check-dienst-replay`, `vocabulaire-dubbelzinnig`, `--getuige`, de melding
"niet gezet") plus `Fout` van uchar; ze staan niet in de bevroren lijst omdat die op naam voor de hele zone werkt en dan de Javanamen
`dienst`, `poort` en `niet` zou vasthouden. Acceptatie (a) is daarmee op die 7 na gehaald.

**Gates.** `make poorten` groen op de eindstand van deze commits: check-pin, `mvn -B -o verify` met 1.619 / 140 / 332 / 53 testgevallen
(gelijk aan de meting op `97d25bb`), tweelingen (3 tweelingen, 18 cases), replay, manifest (29 ok, 3 uitgesloten, 0 mismatch). Docker en
`kind` staan op de machine; `make gates` is niet gedraaid. De OpenAPI-goldens en JSON-goldens zijn niet gewijzigd (alleen
`git mv` van fixturebestanden), acceptatie (c).

**Wat de bouw aan de tabel en het gereedschap deed.** Woordenlijst van de kopie van het gereedschap (`W/build-tc/tool`): `dienst`
service, `verwerk` en `verwerken` process, `gegevens` data, `kern` core, `ontleding` parse, `poort` port, `aan` enabled, plus de hele
woorden `betekenis`, `achtervoegsel`, `opgetreden`, `gouden`, `gerapporteerd`, `volledigheid`, `verenigbare`, `statussen`, `ingehouden`
en `zichtbare`. De paden in de kopie wijzen naar `tools/naming/` in plaats van `scripts/lib/naming/`. De 155 gemelde rijen kregen een
naam van de sessie; 201 rijen zijn in totaal `improved`, ook de rijen waarvan de woorden niet op de woordenlijst passen. De rij `Fout`
is met de hand naar scope `Evaluatiefout.Fout` gezet, omdat de distributie en uchar beide een `Fout` hebben.
Het gereedschap hernoemt wat het niet kan typeren: de leesplekken van uchar-leden achter een lambda-parameter (`s.naam()`,
`.vormen()`), het pakket `kern` in de imports, enum-constanten in een `case` (`ALLE`, `KENMERK`, bevroren), vlaggen en bestandsnamen in
shell, en een methodenaam die een string leest (`getDeclaredMethod("bouw")`). Dat herstelt `f0f27f1`; de pin-bump zet het pakket
op `core`.

**Blijft zichtbaar (de vraag aan de eigenaar: moet de API ook Engels?).** JSON-sleutels van HTTP-API en OpenAPI (`dienst`, `soort`,
`dekking`, `fixpunt`, `uitleg`, `regel`, ...) en de schemanamen (`Evaluatiefout`, `GeenFixpunt`, `Verzoek`, `Antwoord`),
`Schending.Soort` en `Evaluatiefout.Soort`, subcommando's en vlaggen (`--poort`, `--uitsluitingen`, `--getuige`), Make-targets,
`POORT`, `BRONNEN_MANIFEST`, `bronnenManifest`, `REPLAY_WERKERS`, de extensie `.dienst.json`, de scriptnamen `check-*.sh`, de domeinnamen in testmateriaal
(`jutterswet-*`, `toka-*`, `vocabulaire-dubbelzinnig`, `strandmelding`). De 154 namen staan in `tools/naming/frozen/toolchain.tsv`, waarvan
ruim honderd namen van uchar die hier alleen in tekst staan en met de pin-bump meegaan.

**Python, JavaScript en `bin/regelspraak` blijven buiten deze stap** (OPEN 6): `scripts/dienst_replay.py`,
`scripts/vocabulaire_replay.py`, `tools/csv_naar_beslistabel.py` en `playground.html` zijn niet gelezen door het gereedschap en dus niet
hernoemd; in `bin/regelspraak` is alleen de klasse `ServiceMain` gevolgd.

**Voor de volgende sessie.** (1) De pin-bump en `javac_follow.py` met `W/build-uchar/MAPPING.tsv`; de uchar-namen die in de bevroren lijst
staan (de rijen zonder declaratie hier) en de imports van `kern` volgen daarmee. (2) `tools/naming/` gaat weg na het programma. (3) De
javadoc-`{@code ...}` met een JSON-sleutel is door `triage` (code-markup) soms vertaald; dat is commentaar, geen gedrag.


## Result (deel 2: pin-bump, volgen van uchar en de zichtbare API, 2026-10-18)

**Pin.** `uchar` staat op `e2343945` (main van uchar met `rename-public-api`); `make check-pin` groen, de versies in de ouder-pom
zijn gelijk. Commits op `card/engelse-namen`: `cc0cf44` pin en imports (`graaf` is `graph`, `kern` is `core`, `transpiler.kern` is
`transpiler.core`) en de plekken die javac meldde; `8240cce` namen van uchar in javadoc, README en `docs/ENGINE.md`; daarna de
zichtbare API (4b): `3c8f5b0` JSON-sleutels, queryparameters, enumconstanten en goldens, `9a48874` configuratie (env, Helm, Docker),
`a34239f` Python, `a57a5dc` de laatste Nederlandse Java-identifiers (`uitleg`, `houder`, `invoervocabulaire`), `45a62c5` CLI, Make,
scripts en docs, `a9c639c` een veldpad van de getuige.

**Hoe uchar gevolgd werd.** `javac_follow.py` (kopie in `/tmp/tc2/jf.py`, drie verbeteringen: het type uit `location.1` en uit
`invalid.mref`, en de naam vóór de kolom bij `doesnt.exist`; de rij kiest op het nieuwe type, via de typerijen van de tabel) volgde
toolchain, service en service-quarkus, main en test, tot javac schoon was. Met de hand: `Parameterset` was door de eerste
bouwer als `Parameterset` blijven staan (uchar heet `ParameterSet`); de rij `Vocabulary.taal` ontbreekt in `MAPPING.tsv` (nu
`language`); `RuleGraph.Node::eindOutput`; de leesplekken achter een uitgesloten regel (`RegelSpraakRuntime.get(..., "regel")`).
Reflectie op uchar-namen: alleen `ResolvedScenario`/`assemble` in `GoldenOverQuarkusTest`, die klopt. Wat de eerste bouwer
had laten liggen en wat nu klopt: `getResource("negatief")` wees naar de hernoemde map `negative` (een verouderde map in
`target/` verborg dat), `ChainSource` noemde `keten/`, `kind-smoke.sh` gaf `-Ddienst.url` aan een test die `service.url` leest en
las `VERWACHT` terwijl het script `EXPECTED` zette, en de uitleg/de tekst van `ScenarioOracleTest` zocht `Rekenfout:` in een
diagnose die uchar nu `ComputeFault:` noemt.

**De zichtbare API (4b).** Alle JSON-sleutels van verzoek, antwoord, `/info`, playground en OpenAPI (tabel in de README, alinea
"Contract change"), de queryparameters (`explanation`, `vocabulary`, `inputVocabulary`, `coverage`, `fixpoint`, `?via=service`),
de schemanamen, de statuswaarden (`EVALUATION_FAULT`, `NO_FIXPOINT`), `Violation.Kind` (`UNKNOWN_FIELD`, `WRONG_TYPE`,
`UNSUPPORTED`, ...), `EvaluationFault.Kind` (`DIVISION_BY_ZERO`, `COMPUTE_FAULT`, ...), de herkomst (`derived`, `input`,
`creation`), de pseudovocabulaire `canonical`, `FIRED`/`NOT_FIRED` en de `RuleGraph.NodeKind`-waarden (`RULE`, `ATTRIBUTE`, ...)
in de JSON. De descriptor is `<naam>.service.json` (ook `examples/toka/toka.service.json`), het getuigebestand `.witness.json`.
Env: `PORT`, `SOURCES_MANIFEST` (Helm `sourcesManifest`), `REPLAY_WORKERS`, `SERVICE_URL`; chart en image `decision-service`, de Helm-waarden
`port` en `parameterSets`. CLI: `graph`, `--port`, `--explanation`, `--vocabulary`, `--exclusions`, `--parameter-sets`, `--witness`,
`--workers`; Make: `gates-no-docker` (was `poorten`; `gates` bestond al), `check-twins`, `check-service-replay`, `graph`;
bestanden `scripts/twins/`, `service_replay.py`, `vocabulary_replay.py`, `as-of-date-vintage-check.sh`, `toka-manifest-exclusions.txt`,
`csv_to_beslistabel.py`. `playground.html` is herschreven met Engelse identifiers (UI-tekst blijft Nederlands) en leest de nieuwe
sleutels; de Python-scripts via een tokenizer met woordenlijst. De vier OpenAPI-goldens zijn opnieuw gegenereerd door de emitter
(regel- en bytetelling gelijk; alleen de namen verschillen). De tests volgen met een woordenlijst op sleutelplekken; domeinwoorden
die samenvallen met een sleutel (`soort`, `aantal`, `waarde` als attribuutnaam) zijn per geval hersteld.

**Gates.** `make gates-no-docker` groen op de eindstand (de eerste volledige run viel op twee eigen fouten: een shellvariabele die ik
in `tools/check-skips.sh` half had hernoemd en een veldpad in het getuigebestand); daarna `make gates` groen in 8m34s: pin, reactor
1.619 / 140 / 332 / 53 testgevallen (gelijk aan de meting op `97d25bb`), twins (3 + 2 cases, 34 verzoeken, 4 getuigenissen),
replay (225 goldens, 599 cases), manifest (29 ok, 3 uitgesloten, 0 mismatch), image en kind-smoke (H2, H3, H4 PASS).

**Bewust zo gelaten, en de vragen aan de eigenaar.**
1. `rekendatum` blijft het verzoeksleutel en de global (de oorsprong is RegelSpraak); `peildatum` is `asOfDate`. Wil de eigenaar `evaluationDate`?
2. De waarden van `coverage[].kind` in `/info` (`regel`, `defaults`) komen van uchar (`DroolsBackend.coverage`) en blijven Nederlands tot uchar ze Engels maakt.
3. Meldingen noemen nog de oude namen in hun tekst (bijvoorbeeld "queryparameter invoervocabulaire", "dienst.naam ontbreekt" is wel gevolgd);
   dat is stap 7. De OpenAPI-beschrijvingen zijn proza en noemen de sleutels nu in hun nieuwe spelling waar ik het zag.
4. `docs/historisch/` is ongewijzigd. `tools/naming/frozen/toolchain.tsv` noemt nog namen die nu Engels zijn; de map gaat na het programma weg.
5. De Helm-chart en het image heten `decision-service`; wie een eigen values-bestand heeft, moet `poort`, `bronnenManifest` en `parametersets` omzetten.
