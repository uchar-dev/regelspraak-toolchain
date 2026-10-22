# explanation-computed-attributes: een uitkomst die een gewone regel berekent, krijgt een zin

*Punt 5 van de werkbank-tekst van 2026-10-22 (`werknotities/20261022-eval-uitleg-varianten/toolchain-tekst-uitlegstukken.md`),
door de eigenaar doorgegeven aan de toolchain-sessie. Het ontwerp gaat eerst naar een Fable-advies
(`uchar-dev/research/uitleg-berekende-waarden-2026-10-22/`); het aanbevolen antwoord wordt een gemarkeerd voorlopig
besluit in deze kaart.*

## Aanleiding

17 van de 50 `output`-elementen van `beoordeel-herleving` (v2-regelset herleving-WW) krijgen op de 18 gemeten
antwoorden nooit een zin, onder meer: van `Eindigingsgrond` de berekende datum aanvang en de berekende einddatum; van
`RechtOpUitkeringWwBijOntslag` de herlevingsgrens, het maandloon, de eerste kalendermaand met overschrijding, de eerste
kalendermaand zonder overschrijding na-de-eindiging, de berekende maxdatum, de datum einde basisuitkering bij herleving,
de datum einde verschoven-periode-uitkeringspercentage 75, de resterende uitkeringsduur, de verlengde maxdatum en de
bezwaartermijn bij intrekking; van `RechtOpUitkeringPeriode` de berekende herlevingsdatum en de maxdatumverschuiving;
`Uitkeringsduur.berekende uitkeringsduur`, `Dagloon.maximeringsfactor` en `Maandloon.berekende bedrag maandloon`.

De Verbalisator (`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java`) verantwoordt
kenmerken en defaults-groepen; een attribuut dat een gewone regel berekent, ook met "indien" ('datum einde
basisuitkering bij herleving art. 43 lid 1', 'herlevingsgrens art. 21 lid 2 onder a'), krijgt geen zin. Gevolg: een
keten stopt bij zo'n waarde ("… is groter dan de herlevingsgrens … (2.625,00 EUR)", zonder hoe die grens ontstond), en
vraagt het model naar de maxdatum na de herleving (s01-v02, s02-v03, s03-v02), dan krijgt het met reikwijdte=keten geen
enkele zin.

## Doel

Elke uitkomst die een regel van de regelset schrijft, krijgt een zin: welke regel, de waarde, en waarom (de voorwaarde,
uit de lezing van ch. 5.12), en zo dat de keten verder kan naar de waarden waar de berekening van afhangt. De vorm volgt
uit het Fable-advies (onder meer: volstaan de waarden aan het fixpunt plus de geschreven expressie, of vraagt dit van
uchar een lezing van de waarde-expressie naast die van de voorwaarde).

## Acceptatie

Na het advies aan te scherpen. Minimaal: op de vijf service-verzoeken en s01 krijgt elk van de 17 genoemde
uitkomsten, waar het antwoord haar een waarde geeft, een zin; de bestaande zinnen blijven bytegelijk; `make gates`
groen.

## Voorlopige besluiten (2026-10-22, naar het Fable-advies)

Bron: `uchar-dev/research/uitleg-berekende-waarden-2026-10-22/fable-advies.md` (§2–§7). De eigenaar kan elk besluit
omdraaien.

- **De premisse klopt maar half.** Voor een regel op een attribuut mét voorwaarde legt ch. 5.12 de knopen van de waarde
  al vast (tussenwaarden, bijdragers van een vouw, eenheid per knoop); de Verbalisator leest ze alleen niet. Een vorm
  zonder voorwaarde heeft geen lezing: dat levert uchar-kaart `readings-of-unconditional-forms` (U4); de snippets van
  `percentageOf` repareert uchar in de frontend (U5, mee in `check-admits-instance-cycle`). Deze kaart start na de
  pin-bump die beide meebrengt.
- **B1 Reikwijdte:** elke waarde die een gewone regel schrijft, ook leeg; de keten stopt bij invoer, parameter, opgave
  en een regel buiten de scope.
- **B2 Eén zin per (object, attribuut)** over alle schrijvers, in de vorm van de defaults-zin, met vier vormen: een
  regel gold (A), gold en gaf geen waarde (B), geen regel gold en leeg (C), geen regel gold en een invoerwaarde (D).
- **B3** Waarden alleen uit `readingsOf`; geen bean-lezing voor een berekende waarde, geen rekenaar.
- **B4 Tussenwaarden:** de lezingen, de parameters, de binders, de ingebouwde functies en de vouwen met hun bijdragers,
  elk één keer met waarde, plus "vóór de afronding" en "vóór de begrenzing"; geen andere `Binary`-tussenwaarden.
- **B7 Nieuwe lijm:** "volgens de regel '…':", "met …", "vóór de afronding", "vóór de begrenzing", "want de regel '…' gaf
  geen waarde", "want geen regel gold", "verdrongen: de regel '…'".
- Ontwerp en technische keuzes: §5 T1 en §7 van het advies (`ComputationExplanation` naast `DefaultsExplanation`, groepen
  `COMPUTATION` en `VARIANT` in de stukken, nieuwe zinnen per object na de defaults-zinnen, een tijdlijn-doel als "een
  tijdlijn" zonder berekening). Acceptatie zoals §5 T1 (a)–(e), met de vloer `EXPECTED_MIN_EXPLAINED_COMPUTATIONS` in
  `check-service-replay.sh`.
- `explanation-part-groups` (punt 2, `VARIANT`) en deze kaart delen de groep `VARIANT`: wie eerst bouwt, legt haar vast.

## Voorlopige besluiten van de bouwer (2026-10-22)

*Gemarkeerd voorlopig: keuzes die het advies niet beslist, dicht bij het advies en bij de zinnen die er al zijn. De
eigenaar kan elk besluit omdraaien.*

- **V1 Een initialisatie zonder voorwaarde die schreef** krijgt als reden ", omdat de X in de invoer leeg was", de
  woorden van de zin van een initialisatie in een stapel (`DefaultsExplanation.reason`), niet " (onvoorwaardelijk)":
  zij schrijft alleen op een leeg doel.
- **V2 Een schrijver die gold en geen waarde gaf**, naast een die wel schreef, staat als `VARIANT` (`decided: false`)
  met "; de regel '…' gaf geen waarde", de woorden van de defaults-zin. Gelden twee regels met dezelfde waarde: "…,
  en volgens de regel '…'…"; geven twee geen waarde: "…, en de regel '…' gaf geen waarde…". De grondslag is die van
  de eerste.
- **V3 Vorm B zonder voorwaarde** houdt " (onvoorwaardelijk)" na "gaf geen waarde", zoals vorm A het na de regelnaam
  zet.
- **V4 De waarde van vorm A** is die van de wortelknoop van de lezing, met de decimalen van het gedeclareerde doel
  (zoals de defaults-zin "heeft als premie 100,00" schrijft) en de eenheid van de knoop, of zonder die die van het
  doel. De waarde vóór een afronding of begrenzing staat met al haar decimalen (de decimalen van het type van de
  knoop zouden haar al afronden): "vóór de afronding 3.045 EUR", "vóór de afronding 15,43125".
- **V5 De leden van een vouw** staan na "over", ook bij het ene lid ("over P1 (122 dg), het enige lid"; het advies
  schrijft één keer "voor"). De ": " vóór de leden staat buiten `MEMBERS`, zoals bij een kwantorvouw nu; de kaart
  `explanation-part-groups` (punt 1) verplaatst haar voor beide.
- **V6 Een `Daarbij geldt`-binder** staat in de "met"-lijst met de tekst van zijn definitie, zonder de punt erachter
  ("de grondpremie is de vondstwaarde van de Jutter gedeeld door 10 (27,345)"), zoals het advies vraagt. De
  regeltekst van een waarde die een `Let` is, is die van haar lichaam: de brontekst van de `Let` zelf is de definitie
  van haar binder.
- **V7 Een tijdlijn-doel** zonder schrijver met een invoerwaarde: "houdt als X de invoerwaarde een tijdlijn".
- **V8 Een regel op een supertype** waarvan de motor op een object geen lezing gaf, terwijl het type van dat object
  een stapel op hetzelfde doel heeft (ch. 7.10, golden `jutterswet-wrakerfbasis`), is daar geen schrijver: de stapel
  beslist het doel. Elke andere ontbrekende lezing faalt luid.
- **V9 Een aggregatie over haar argumenten** ("de laatste van (A) en (B)") is geen vouw over een verzameling: zij staat
  als regeltekst met haar argumenten in de "met"-lijst, zoals een ingebouwde functie.

## Result (2026-10-22)

Tak `card/explanation-computed-attributes` in de worktree `wt-tc/explanation-computed-attributes`, vanaf toolchain main
`167588a7`. Gebouwd door een Opus-bouwer volgens het Fable-advies (`research/uitleg-berekende-waarden-2026-10-22/
fable-advies.md`, §2–§7) en de voorlopige besluiten B1–B7 van de kaart; `make gates` en de landing doet de hoofdsessie.
Niets gemerged, niets gepusht.

- `fe897331` de kaart naar `doing/`.
- `d12a1099` pin uchar `5d8a06de` → `88c4624b`; replay-pin 238 → 241; README-getallen.
- `46185bb0` elke berekende waarde krijgt een zin: `ComputationExplanation` in de Verbalisator, de groepen
  `COMPUTATION` en `VARIANT`, `Document.outside` ook voor gewone regels, OpenAPI, `docs/ENGINE.md`, README, de
  replay-leg met de vloer, de tests, en de voorlopige besluiten V1–V9 van de bouwer in de kaart.

Werkmap: `werk/toolchain-2026-10-22/computed/` (`logs/`, `meting/`).

### De pin

uchar main `88c4624b` (33 commits na `5d8a06de`). Brengt mee: `readings-of-unconditional-forms` (U4: een vorm zonder
voorwaarde die een waarde berekent heeft de lezing van haar waarde, ch. 5.12), `percentage-of-snippets` (U5: elke
operand van `het percentage X van Y` draagt haar eigen snippet), `cycle-across-instances`, `third-condition-level` en
twee clingo-fixes (CLINGO-030). `make check-pin`: PASS, versies gelijk.

- **Replay-pin.** De set van gate 12 groeit van 268 naar 271 met `jutterswet-vergunningketen`,
  `jutterswet-vrijstelling-drie-niveaus` en `jutterswet-premietabel-twee-ofcellen`; alle drie spelen ook door de
  dienst: `EXPECTED_REPLAY_PLAYED` 238 → 241, met de reden in het script. `REPLAY_EXCLUDED` ongewijzigd (30).
- **README**, "The gates": `EXPECTED_PLAYED` 268 → 271, `EXPECTED_REPLAY_PLAYED` 238 → 241.
- **Niets anders.** Zonder eigen code: `mvn -B -o verify` groen (backend 1652, toolchain 167, service 364,
  service-quarkus 53; 8:33 min; `logs/verify-pin.log`); `check-service-replay` PASS (241 goldens, 647 cases, 1147
  zinnen, 1308 uitgelegde voorwaarden, 68 s); `check-twins` PASS (ongewijzigd); `make scenario-test` 32 regels, 29 ok,
  3 uitgesloten, 0 mismatch (`scripts/toka-manifest-exclusions.txt` ongewijzigd). Niets brak door de pin.

### Wat er veranderde

- **`ComputationExplanation`** (`toolchain/.../engine/Verbaliser.java`), naast `DefaultsExplanation`: per object en per
  attribuut dat de gewone regels van het document (gelijkstelling, berekening, initialisatie) op zijn typeketen
  schrijven één zin over alle schrijvers, ná de defaults-zinnen van dat object. Een regel die ch. 7.10 in een stapel
  van het object opnam (zijn pointer in `DefaultsDecision.rootChains`) telt niet mee (B2). De vier vormen van B2:
  A "heeft als X W volgens de regel 'R'<COMPUTATION>, omdat …" of " (onvoorwaardelijk)<COMPUTATION>"; B "heeft geen X,
  want de regel 'R' gaf geen waarde<COMPUTATION>, omdat …"; C "heeft geen X, want geen regel gold"; D "houdt als X de
  invoerwaarde W, want geen regel gold". Daarna elke andere schrijver als `VARIANT` ("; de regel '…' gold niet, want
  niet geldt: …", "… want zij valt op de rekendatum buiten haar geldigheid", "; de regel '…' gaf geen waarde",
  "; verdrongen: de regel '…'"), dan de grondslag van de regel die gold.
- **De waarden alleen uit `readingsOf` (B3).** De lezing van de regelversie op `positions.versionOf(r)`; de knopen van de
  waarde op `positions.subtree(r.value())`, genummerd vanaf het aantal knopen van de voorwaarde (0 zonder voorwaarde).
  Alleen vorm D leest de bean (de invoerwaarde, zoals `standing`). Een ontbrekende lezing, een fout op een knoop of
  twee regels die gelden met verschillende waarden falen luid.
- **De berekening (`COMPUTATION`, B4).** ": " + de regeltekst van de waarde (`QUOTE`) + ", met " de knopen die iets lezen
  of samenvatten (`AttributeReference`, `ParameterReference`, `DimensionCellRead`, `BinderRef`, `BuiltinCall`,
  `Aggregation`, `Fold`), in pre-orde, elke (brontekst, declaratie) één keer, elk met de waarde van zijn knoop en
  zonder waarde waar de motor hem niet bereikte; dan "; vóór de afronding W" per `Rounding` en "; vóór de begrenzing W"
  per `Clamp`. Leest de waarde één declaratie of vouwt zij over een verzameling, dan is die knoop de regeltekst zelf,
  met haar waarde, en vervalt "met". Een vouw over een verzameling krijgt haar bijdragers als `MEMBERS` (meta alleen
  `members`): "over I4 (01-04-2026), I5 (…) en I7 (…) (4 leden)", een telling zonder waarde per lid, m = 0 "er is geen
  enkel lid", m = 1 "…, het enige lid", boven vijf "en nog k". Haar filter staat, als het één kenmerkcheck of één
  lezing is, als `QUOTE` met de sleutels van die declaratie; een samenstelling als `QUOTE` per lid (§2.1 van het
  advies). Een tijdlijn-doel krijgt "een tijdlijn" (`VALUE` met `timeline: true`) en geen berekening.
- **`Part.Kind`**: groepen `COMPUTATION` en `VARIANT` (meta `decided: false`, `displaced: true` bij "verdrongen");
  `VALUE` kan `timeline: true` dragen. `explanation-part-groups` vindt `VARIANT` nu vastgelegd.
- **`Document.outside`** (`DecisionCore.outsideScope`): ook de gewone regels buiten de scope, zodat ook daar de ene zin
  "de regel '…' staat buiten deze berekening" komt (B1: de keten stopt bij een regel buiten de scope).
- **Gedeeld, gedrag gelijk**: `attributeStanding` (wat het doel houdt; `DefaultsExplanation.standing` roept haar aan),
  `read(…, root, offset)` naast `read(…, Condition)`, `Read.has`, `renderRead` onder `renderOperand`. De bestaande zinnen
  en hun stukken zijn bytegelijk (de meting hieronder en elke bestaande uitlegtest).
- **OpenAPI** (`OpenApiEmitter`): de beschrijving van `ExplanationPart` noemt `COMPUTATION`, `VARIANT`, `timeline`, en
  `members` alleen bij een vouw in een berekening; eigenschappen `timeline`, `decided`, `displaced` (boolean). De vier
  OpenAPI-goldens overgeschreven uit `target/openapi/`; het verschil is alleen toevoeging (beschrijving, twee
  enum-waarden, drie eigenschappen). `ContractNamesInMessagesTest` groen, ongewijzigd.
- **`docs/ENGINE.md`**, "The parts of an explanation": de twee groepen in de tabel en een alinea "The sentence of a
  computed value". **README**: "Contract change" (een regel) en "Known losses" (de uitleg legt nu ook berekende waarden
  uit).
- **De replay-leg** (`scripts/service_replay.py`, `check-service-replay.sh`): de soorten `COMPUTATION` en `VARIANT`
  zijn bekend en groepen; een vierde telling per golden, de zinnen van vorm A (onderwerp, " heeft als ", een groep
  `COMPUTATION`); de vloer `EXPECTED_MIN_EXPLAINED_COMPUTATIONS=1852`, gemeten, met de reden. De bestaande toetsen
  (stukken = zin, grondslag laatste, 0 keer "niet is vast te stellen") lopen over de nieuwe zinnen mee.

### Rood eerst (acceptatie (a))

Nieuw, op een eigen model met Jutterswet-namen (rekenkundig met afronding en `percentage van` met grondslag, datum ±
duur, tijdsduur, lezing via een rol, `eerste` met een kenmerk als filter, `som` over een rol, een `Daarbij geldt`-binder
met een parameter en een maximum, een gelijkstelling met voorwaarde naast een initialisatie (verdrongen en aan de
beurt), een lezing van een attribuut dat niemand schrijft (gold en gaf geen waarde), een regel van twee versies waarvan
één buiten haar geldigheid; vormen A–D allemaal):

- `ExplanationComputationTest` (toolchain, de scenarioroute, 2): de 33 zinnen van het model, en een tijdlijn-doel (een
  dagentelling per maand: "een tijdlijn", geen berekening).
- `ExplanationComputationServiceTest` (dienst, 7): dezelfde 33 zinnen uit bronnen en uit het IR-document, met de
  stukken van zes zinnen gepind (de vouw met filter en leden, de verdrongen initialisatie, B, C, D, de afronding met
  grondslag) en de beslissers van de 3 grondslagen; een regel buiten de scope; een `aantal` met filter (boven vijf, en
  het enige lid); een aggregatie over haar argumenten ("de laatste van (A) en (B)"); een regel op een supertype waar
  een stapel van het subtype beslist (uchars golden `jutterswet-wrakerfbasis`); 24 groepen `COMPUTATION` en 12
  `VARIANT`, elk met "; " vooraan.

Rood: alle negen op de hoofdcode van de pin-commit `d12a1099` (`logs/red-pin-code-2.log`: 2 van 2 en 7 van 7 rood; de
scenarioroute gaf alleen de drie kenmerkzinnen). Groen na de bouw (`logs/green-7.log`).

### Bestaande tests die veranderden (de kaart verandert wat ze pinden; geen toets verzwakt)

Elke verandering is het invoegen van de nieuwe zinnen; de oude staan er letterlijk en in hun volgorde in (per test
nagelezen als deelrij):

- `ExplanationGeneratedRuleInVocabularyTest` (3): per vondst de zin van 'melding' over de berekende meldingsdatum, in
  elk vocabulaire.
- `ExplanationRolePathTest`, `ExplanationRolePathServiceTest`: de herlevingsgrens van R1 (met "vóór de afronding"), de
  maximeringsfactor van D1 en vorm C voor R2, vooraan.
- `VocabularyScenarioTest`: vier zinnen erbij (som met leden, telling met filter, twee keer maal); de canonieke controle
  leest "V1 is waardevol" nu op index 3.
- `ScenarioRunnerTest` (2): de heffing en de retourafstand.
- `ExplanationGroundSourceServiceTest`: per jutter de zin van 'melding' met haar grondslag (`{rule=melding}`); de
  telling van de grondslagen 12 → 16 en de indices van de gepinde grondslagen 13 → 15, 18 → 21.
- `PlaygroundTest`: de korting van P1 erbij.
- `ExplanationPartsServiceTest`: toetst dat elke soort voorkomt; om die toets op volle sterkte te houden kreeg het
  model een regel 'meldpremie' (een berekening met voorwaarde), dus vier zinnen erbij en de indices van de gepinde
  stukken 4/7/8/12/16 → 5/8/10/15/20.
- `ExplanationPartsCheck` (testhulp): de groepen `COMPUTATION` en `VARIANT`.
- De vier OpenAPI-goldens (hierboven).

### Meting (acceptatie (c)) op v2 en s01

`meting/measure.sh` start de dienst uit een gegeven jar met de v2-momentopname (`werk/defaults-decider/v2`, regels
`a7a5bdf`) en vraagt de vijf service-verzoeken en s01 zonder vlag, met `?explanation=true` en met
`?explanationParts=true`. Vóór: jar van de pin-commit `d12a1099` (`meting/before/`); na: jar van `46185bb0`
(`meting/after/`).

- **Bytegelijk** (`meting/compare.py`, `compare.log`): zonder vlag bytegelijk; het antwoord buiten de uitleg gelijk; de
  209 zinnen van vóór letterlijk terug, in hun volgorde, met dezelfde stukken; elke zin gelijk aan haar stukken. PASS.
- **Nieuw**: 107 zinnen (detentie 15, herkrijging 16, inkomen 17, intrekking 15, restant 26, s01 18): 50 van vorm A, 17
  B, 40 C, 0 D (geen verzoek geeft een invoerwaarde op een berekend attribuut); geen regel buiten de scope op een object
  van de verzoeken. De tekst groeit van 148.945 naar 231.324 tekens (+55 %).
- **De 17 uitkomsten** (`meting/outcomes.py`, `outcomes.log`): op de zes verzoeken staan ze op 100 objecten; elk van de
  100 heeft één kopzin, met een waarde (vorm A) waar het antwoord er een geeft (45) en vorm B of C waar niet (55). Alle
  17 komen voor; per verzoek:

| uitkomst | detentie | herkrijging | inkomen | intrekking | restant | s01 |
|---|---|---|---|---|---|---|
| G.berekende datum aanvang eindigingsgrond recht op uitkering | C | C | A | C | B | A |
| G.berekende einddatum-van-een-eindigingsgrond | C | C | A | C | C | A |
| R.herlevingsgrens | C | C | A | C | C, C | A |
| R.maandloon | B | B | A | B | B, B | A |
| R.eerste kalendermaand met overschrijding | B | B | A | B | B, B | A |
| R.eerste kalendermaand zonder overschrijding na-de-eindiging | B | B | A | B | B, B | A |
| R.berekende maxdatum | C | A | C | C | A, A | A |
| R.datum einde basisuitkering bij herleving | A | A | A | A | C, C | A |
| R.datum einde verschoven-periode-uitkeringspercentage 75 | C | C | C | C | C, C | C |
| R.resterende uitkeringsduur | A | A | A | A | A, C | A |
| R.verlengde maxdatum | C | C | C | C | C, A | C |
| R.bezwaartermijn bij intrekking | C | C | C | A | C, C | C |
| P.berekende herlevingsdatum | A | A | A | A | – | A |
| P.maxdatumverschuiving | A | A | A | A | – | A |
| Uitkeringsduur.berekende uitkeringsduur | – | A | – | – | A, A | C |
| Dagloon.maximeringsfactor | – | – | A | – | – | A |
| Maandloon.berekende bedrag maandloon | – | – | A | – | – | C |

(R = RechtOpUitkeringWwBijOntslag, G = Eindigingsgrond, P = RechtOpUitkeringPeriode; "–": geen object van dat type in
het verzoek; restant heeft twee rechten en twee uitkeringsduren.)

**Een zin per vorm** (de grondslag ingekort tot "…"):

- A (inkomen, de vraag van s01-v02, s02-v03, s03-v02): "R1 heeft als datum einde basisuitkering bij herleving
  03-05-2027 volgens de regel 'datum einde basisuitkering bij herleving art. 43 lid 1': zijn maxdatum plus de maximale
  waarde van de maxdatumverschuiving van alle perioden van het RechtOpUitkeringWwBijOntslag, met zijn maxdatum
  (01-01-2027) en de maximale waarde van de maxdatumverschuiving van alle perioden van het RechtOpUitkeringWwBijOntslag
  (122 dg): over P1 (122 dg), het enige lid, omdat het onderwerp de indicatie herleving recht op uitkering heeft en zijn
  maxdatum (01-01-2027) gevuld is en het aantal perioden van het RechtOpUitkeringWwBijOntslag die aan de volgende
  voorwaarde voldoen: zijn maxdatumverschuiving is gevuld (1) is groter dan 0 en zijn beëindigingsdatum (01-04-2026)
  ligt vóór zijn maxdatum (01-01-2027) en zijn niet-meetellende ziektedagen (onbekend) leeg is. Grondslag: …"
- B (detentie): "R1 heeft geen maandloon, want de regel 'maandloon van het recht art. 1b lid 2' gaf geen waarde
  (onvoorwaardelijk): het bedrag maandloon van het maandloon van het dagloon van het Recht op uitkering WW (onbekend).
  Grondslag: …"
- C (inkomen): "R1 heeft geen berekende maxdatum, want geen regel gold; de regel 'maxdatum van het recht art. 42 lid 1'
  gold niet, want niet geldt: de maxdatum van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag (onbekend) gevuld
  is"
- D (komt op v2 niet voor; het eigen model): "J3 houdt als bonus de invoerwaarde 7,50, want geen regel gold; de regel
  'bonus voor ingezetenen' gold niet, want niet geldt: het onderwerp ingezetene is; verdrongen: de regel 'bonus zonder
  voorwaarde'"
- `percentageOf`, de herlevingsgrens (inkomen; dankzij U5 elke operand één keer met haar eigen tekst): "R1 heeft als
  herlevingsgrens 3.045,00 EUR volgens de regel 'herlevingsgrens art. 21 lid 2 onder a': (het percentage
  herlevingsgrens van het bedrag maandloon van het maandloon van het dagloon van het Recht op uitkering WW) naar beneden
  afgerond op 2 decimalen, met percentage herlevingsgrens (87,5 %) en het bedrag maandloon van het maandloon van het
  dagloon van het Recht op uitkering WW (3.480,00 EUR); vóór de afronding 3.045 EUR, omdat het bedrag maandloon van het
  maandloon van het dagloon van het Recht op uitkering WW (3.480,00 EUR) gevuld is. Grondslag: …"

De vouw met een kenmerk als filter (inkomen): "… volgens de regel 'eerste kalendermaand met overschrijding art. 20 lid
1 onder c' (onvoorwaardelijk): de eerste van de kalendermaand van alle inkomens van het Recht op uitkering WW die aan de
volgende voorwaarde voldoen: hij heeft de herlevingsgrens overschreden (01-04-2026): over I4 (01-04-2026), I5
(01-05-2026), I6 (01-06-2026) en I7 (01-07-2026) (4 leden)", met het filter als `QUOTE` met `declaration=KENMERK`,
`canonical=herlevingsgrens overschreden`, zodat de keten bij de zinnen van I3–I8 komt.

### De vloer (acceptatie (d))

`check-service-replay` op `46185bb0` (`logs/replay-t1-4.log`): PASS, 241 goldens, 647 cases (pin 241), 3443 zinnen
(was 1147), elk gelijk aan haar stukken, 2209 uitgelegde voorwaarden (vloer 1241, niet verhoogd), 1852 zinnen van vorm A
(vloer `EXPECTED_MIN_EXPLAINED_COMPUTATIONS=1852`), 0 keer "niet is vast te stellen", 70 s met vier diensten. De eerste
run met de nieuwe zinnen gaf twee goldens een 500 (`jutterswet-diepwrakbasis`, `jutterswet-wrakerfbasis`,
`logs/replay-t1-1.log`): een regel op een supertype waar een stapel van het subtype het doel beslist, en waarvan de
motor daar dus geen lezing gaf. Voorlopig besluit V8 en de getuige
`a_rule_of_a_supertype_does_not_run_where_a_stack_of_the_subtype_decides`.

### Suites

`mvn -B -o verify` over de hele reactor op de code van `46185bb0` (vóór de commit, dezelfde bomen;
`logs/verify-t1.log`): BUILD SUCCESS, 1652 (Drools) / 169 (toolchain, was 167: +2) / 371 (dienst, was 364: +7) / 53
(Quarkus), skip-pin PASS op de drie poorten, 7:48 min. `check-service-replay` PASS (hierboven), `check-twins` PASS
(ongewijzigd), `make scenario-test` 32 regels, 29 ok, 3 uitgesloten, 0 mismatch. Niet gedraaid: `make gates` (de
hoofdsessie).

### Vondsten

- Geen bestaande bug in de toolchain gevonden.
- uchar, alleen genoteerd: (1) de snippet van een `Let` (een `Daarbij geldt`) is de definitie van haar binder ("de
  grondpremie is de vondstwaarde van de Jutter gedeeld door 10."), niet de waarde; de uitleg quote daarom het lichaam
  (V6). (2) De snippet van een vouw waarvan het filter een opsomming is, eindigt op de punt van de regel ("… van het
  Inkomen in-een-kalendermaand."), zodat de zin "…kalendermaand. (01-08-2026)" schrijft. Geen van beide is een fout in
  de semantiek; een snippet-kaart in uchar kan ze rechtzetten.

### Wat open bleef

- **De keten van de eval** (advies §5 T1 (c)): "de keten van s01-v02, s02-v03 en s03-v02 niet leeg, gemeten met
  `toolsets.keten`" is niet gemeten: de tak `eval-uitleg-varianten` van de werkbank heeft geen worktree meer. Wel staat
  op s01 de kopzin van `datum einde basisuitkering bij herleving` (vorm A) met haar operanden als `QUOTE` met
  `canonical`; de werkbank meet de keten.
- **De lengte**: de uitleg op v2 groeit 55 % (148.945 → 231.324 tekens). Voor het zichtvenster van de eval
  (`reikwijdte=keten`, of `COMPUTATION`/`VARIANT` weglaten) is dat aan de werkbank (advies §4).
- **T2** (`explanation-defaults-computation`): de beslissende variant van een defaults-groep op een attribuut krijgt
  nog geen `COMPUTATION`; dat verandert bestaande zinnen en is een eigen kaart.
- **`explanation-part-groups`** (punt 1): de ": " vóór `MEMBERS` staat ook in de nieuwe zinnen buiten de groep (V5).
- Vorm D komt op v2 niet voor; het eigen model en de corpus-replay dekken hem.

### (c) `make gates` en landing (hoofdsessie, 2026-10-22)

`make gates TAG=computed CLUSTER=computed` op `46185bb0`: GROEN, 7 stappen in 11m57s (pin PASS; reactor
1652/169/371/53; twins PASS; replay 241 goldens, 647 cases, pin 241; manifest 29 ok, 3 uitgesloten, 0 mismatch; image
`decision-service:computed`; kind-smoke H2, H3, H4 PASS). Review: de voorlopige besluiten V1–V9 liggen dicht bij het
advies en de bestaande zinnen; geen omgedraaid. De uchar-vondst "de snippet van een vouw met een bulletlijst als filter
eindigt op de slotpunt van de regel" gaat als fix mee in de uchar-tak `check-admits-instance-cycle` en komt met de
volgende pin. Voor de werkbank: de uitleg op v2 groeit 55 % (148 945 → 231 324 tekens), van belang voor het
kijkvenster van de eval; de ketenproef van het advies (s01-v02, s02-v03, s03-v02 met `toolsets.keten`) is aan de
werkbank.
