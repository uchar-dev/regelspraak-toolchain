# explanation-parts: een gestructureerde uitleg, elke zin ook als reeks stukken met een soort

*Opdracht van de eigenaar 2026-10-21, vastgelegd door de hoofdsessie. Bouwt voort op de rendering uit de lezingen van de
motor (`backlog/done/20261021-explanation-from-readings.md`, `backlog/done/20261021-explanation-fold-members.md`).
Besluitvragen gaan naar een Fable-adviseur (besluit van de eigenaar 2026-10-21); het aanbevolen antwoord is een
gemarkeerd voorlopig besluit in deze kaart en het Result.*

## Aanleiding

De werkbank wil de uitleg kleuren per soort, een voorwaardenlijst onder elkaar zetten en de grondslag apart tonen. Nu
levert de dienst alleen platte zinnen (`explanation`). Besluit van de eigenaar: geen opmaaktags in de tekst, en de
werkbank toont alleen wat de toolchain aanlevert; zij knipt of interpreteert de tekst niet zelf, dus zonder stukken toont
haar Uitleg-tab de zin letterlijk.

## Doel

- De dienst en de playground leveren elke uitlegzin ook als reeks stukken met een soort, in een nieuw veld naast
  `explanation` (Engelse naam, de kaart kiest), met één entry per zin, in het antwoord van de dienst en van
  `/playground/analyse` (ook met `via=service`).
- De platte zin in `explanation` blijft het eval-taalmodel voeden, komt uit dezelfde stukken, en is letterlijk gelijk aan
  nu, zodat de eval-runner niets merkt.
- Soorten (voorstel van de eigenaar; de kaart beslist): onderwerp (label van de instantie), naam (attribuut, kenmerk of
  rol, met de canonieke naam ernaast zodat de werkbank haar naam-popover kan openen), waarde (met eenheid, of onbekend),
  regel (regelnaam), verbinding (omdat, want, gold niet, en, of), voorwaarde (met kwantor, geneste lijst en de leden van
  een kwantorvouw), citaat (regeltekst zoals geschreven) en grondslag.
- De grondslag levert de toolchain zelf ontleed aan: wetsverwijzingen (BWBR, artikel, lid, onderdeel), lemma's,
  CGM-begrippen, afwijkingen en de vrije toelichting apart, zodat de werkbank niet op het scheidingsteken " · " hoeft te
  vertrouwen.
- Contract: OpenAPI (`OpenApiEmitter`) en `ContractNamesInMessagesTest` bij.

## Besluitvraag (naar Fable)

Hoort de grondslag in de uitlegzin zelf of in een eigen veld per zin? Zij zegt waarom de regel bestaat, niet hoe de
casus uitpakt, en het citaat maakt de zinnen voor het taalmodel ongeveer twee keer zo lang. Gemeten op v2 (`na4b/`): 83
van de 171 zinnen dragen ". Grondslag: …", samen 43.634 van de 121.861 tekens (36 %). Een eigen veld verandert de platte
zin voor het taalmodel; dat botst met "letterlijk gelijk aan nu".

## Voorlopige besluiten (Fable-advies 3, 2026-10-21; de eigenaar kan ze omdraaien)

Het advies staat in `uchar-dev/werk/defaults-decider/fable-advies-3.md`. Aanvulling van de eigenaar bij de vraag: de
primaire afnemer van de uitleg is het taalmodel (opzet: persoonlijke assistent); de kleuren zijn vooral voor de eigenaar, voor
debuggen in de werkbank.

- **De grondslag** krijgt een eigen plaats per zin: het laatste stuk van de zin, soort `GROUND`, ontleed. Deze kaart
  verandert de platte zin niet (bytegelijk). De tekst voor het taalmodel (grondslag inkorten tot de wetsverwijzingen,
  het herhaalde onderwerp van een `of`-lijst één keer) is een eigen kaart, `explanation-for-the-model`, na een meting met
  de eval; de eval-runner kan uit de stukken varianten samenstellen.
- **Achter een eigen vlag:** de eval geeft de hele antwoordbody aan het model (`toolsets.py` van de werkbank); de stukken
  komen daarom alleen met `explanationParts=true`, zodat het model ze niet ongevraagd leest.
- **Vorm:** een ondiepe boom uit één bouwer (`Sentence`/`Part` in `engine/`); de Verbalisator schrijft op de bouwer en
  `text()` is de platte zin, zodat elke bestaande uitlegtest de bytegelijkheid bewijst. Soorten: `SUBJECT`, `NAME`
  (met `declaration`/`owner`/`canonical`, de sleutels van `vocabularies.elements[]`), `QUOTE`, `VALUE`, `RULE`,
  `CONNECTIVE`, `TEXT` (lijm), de groepen `CONDITION` (`holds`, `quantifier`) en `MEMBERS` (m, k), en `GROUND` met
  kinderen `STATUTE` (`bwb`/`article`/`paragraph`/`part`), `LEMMA`, `DEVIATION`, `NOTE`. Geen soort voor een
  CGM-begrip: geen getuige op regelniveau.
- **De ontleder van de grondslag** is totaal: wat geen patroon past, is `NOTE`; de grammatica staat in `docs/ENGINE.md`.
  De grondslag staat op de draad als één string per `// Bron:`-regel; ontleden zonder tekst te parsen kan niet.
  Beperking: een default krijgt zijn annotaties al samengevoegd met "; " (`Default.source`); de oplossing ligt in uchar.
- **Veld:** `explanationParts`, parallel aan `explanation` (zoals `inconsistencies`/`inconsistencyDetails`), schema
  `ExplanationPart` recursief via `$ref`.

## Voorlopige keuzes van de bouwer (2026-10-21; het advies beantwoordt ze niet)

- ". Grondslag: " hoort bij het stuk `GROUND` (als `TEXT`, ook de punt): een zin zonder haar laatste stuk is de zin
  van een regel zonder annotatie.
- `holds` ontbreekt waar de motor de knoop bereikte zonder oordeel (na een lege binder); op v2 komt het niet voor.
- De variant van de opgavemarkering (`'opgegeven X'`) is een `NAME` (attribuut): haar tekst komt uit `Naming`.
- In de regel die uchar schreef (`inVocabulary`) is de tekst om de naam heen `QUOTE`, de naam `NAME`.
- `CONNECTIVE` zijn de acht woorden van het advies en "en" tussen de beslissers van een conflict; "volgens",
  "verdrongen", "besliste niet anders", "niet voor"/"wel voor" zijn `TEXT`.
- Na een afwijking blijft een slug een `LEMMA`; een leeg segment geeft alleen zijn scheidingstekens; `STATUTE` zonder
  lid of onderdeel draagt die sleutels niet, `NAME` draagt `owner` altijd (null bij een parameter).
- `explanationParts=true` vraagt ook `explanation`.

## Acceptatie

- (a) Voor de vijf service-smoke-verzoeken van herleving-WW v2
  (`/Users/yeb/Developer/uwv/regelspraak-werkbank/rulesets/regelspraak-rules/v2/herleving-ww/service/*.request.json`,
  gelijk aan de momentopname in `werk/defaults-decider/v2/`, regels `a7a5bdf`) en voor een eigen klein model is de
  aaneengeschakelde tekst van de stukken per zin teken voor teken gelijk aan de zin in `explanation`.
- (b) Elke soort komt in een test voor, een geneste of/en-lijst en een kwantorvouw met haar leden ook.
- (c) `make gates` groen.

May touch: `toolchain/.../engine/Verbaliser.java` en wat de zinnen bouwt, `service/` (antwoord, playground, OpenAPI),
de tests van de uitleg, `ContractNamesInMessagesTest`, `scripts/check-service-replay.sh` (de leg van de uitleg mag de
gelijkheid van stukken en zin toetsen), deze kaart.

Out of scope, alleen als open punt: een zin als "zijn code reden … is gelijk aan A of zijn code reden … is gelijk aan B
…" herhaalt het onderwerp zes keer; die zin korter maken verandert de tekst voor het taalmodel en is een eigen besluit.

## Result (2026-10-21)

Tak `card/explanation-parts` in de worktree `wt-tc/explanation-parts`, vanaf toolchain main `1e0b6bc1`, pin uchar
`4f283d9d` (ongewijzigd). Gebouwd door een Opus-bouwer volgens Fable-advies 3 (§2 ontwerp, §3 JSON-vorm, §4
bouwvolgorde); de hoofdsessie draaide `make gates` en schreef dit Result af uit zijn concept.

- `fc347f72` de kaart naar `doing/`.
- `85faaefc` de uitleg als boom van stukken uit één bouwer: `Part`/`Sentence`/`Ground`, de Verbalisator erop, het
  veld `explanationParts` in dienst, playground en OpenAPI, de replay-leg, de tests en de documentatie.

### Wat er veranderde

- **Eén bouwer** (`toolchain/.../engine/Sentence.java`, `Part.java`). `Part` is één record `(kind, text, meta, parts)`:
  een blad met zijn tekst, of een groep (`CONDITION`, `MEMBERS`, `GROUND`) waarvan de tekst per constructie de
  aaneenschakeling van haar stukken is (de constructor weigert een andere). `Sentence.text()` is de enige weg naar de
  platte zin. De bouwer (`text`, `leaf`, `open`, `close(kind)`, `build`) voegt opeenvolgende lijm samen tot één `TEXT`,
  laat lege lijm weg, en faalt luid op een groep die verkeerd of niet gesloten wordt. Meta draagt alleen teksten,
  booleans, gehele getallen en null.
- **De Verbalisator** (`Verbaliser.java`) schrijft elke zin op de bouwer: de `StringBuilder`s en de methoden die een
  `String` teruggaven (`outcome`, `standing`, `reason`, `notDecided`, `name`, `verbaliseCondition`, `structuredAtom`,
  `unstructured`, `members`, `names`, `firstFailing`, `who`, `operandProse`, `describe`, `inVocabulary`, het gezegde)
  schrijven nu stukken. `verbalise(…)` houdt zijn handtekening en geeft `sentences(…)` als tekst; `sentences(…)` is
  nieuw. `renderValue`, `nlNumber`, `unitWord`, `predicate`, `articleBefore`, de `Read`-, `Variant`- en
  `Turn`-mechaniek en elke luide fout zijn ongewijzigd. `structuredAtom` geeft een schrijver of null, zodat "niet
  geldt: " alleen vóór een gestructureerd atoom komt, zoals voorheen. `Naming.roleFulfilledBy` (nieuw, statisch) geeft
  de rol die het onderwerp vervult als declaratie, zodat een `NAME` van een rolcheck haar sleutels draagt;
  `roleFulfilled` gebruikt haar en noemt dezelfde naam als voorheen.
- **De ontleder van de grondslag** (`Ground.java`), totaal: een segment dat geen patroon past is `NOTE`. De grammatica
  staat in `docs/ENGINE.md`, "The parts of an explanation", met de bron van de conventie (MODELKAART B20).
- **`ScenarioRunner.Explained`** draagt `List<Sentence> sentences`; `explanation()` leidt de strings af, zodat elke
  bestaande aanroep ongewijzigd blijft.
- **De dienst.** `RequestOptions` heeft een zevende boolean `explanationParts`, die de uitleg ook vraagt (de compacte
  constructor zet `explanation` dan aan: één build, één vuring). `ServiceHttp.options` en `DecideResource` (Quarkus,
  een `@QueryParam` erbij) lezen `explanationParts` zoals de andere vlaggen. `DecisionCore.addExplanation` schrijft
  `explanation` en, met de vlag, direct daarna `explanationParts` uit dezelfde zinnen, via één schrijver
  (`service/.../ExplanationParts.java`), die ook beide routes van de playground gebruiken: de gewone route uit
  `Explained.sentences()`, `via=service` uit het antwoord van de dienst (`RequestOptions(true, …, true, true, true,
  true)`, het veld in de lijst `coverage/trace/explanation/explanationParts`). Zonder de vlag is het antwoord bytegelijk
  aan nu. De playground geeft het veld altijd, zoals `explanation`.
- **OpenAPI** (`OpenApiEmitter`): de vlag `explanationParts` per operatie, het veld `explanationParts` in `Answer`
  (array van arrays van `$ref`), het schema `ExplanationPart` (recursief via `parts`, `kind` als enum van
  `Part.Kind`, de optionele sleutels als eigenschappen, per soort beschreven). `ContractNamesInMessagesTest` groen.
- **De replay-leg** (`scripts/service_replay.py`, `check-service-replay.sh`): het tweede verzoek per case vraagt
  `?explanation=true&explanationParts=true`; het antwoord buiten de twee velden moet dat zonder zijn, per zin moeten de
  teksten van de bladeren van haar stukken achter elkaar de zin zijn, elk stuk een bekende soort, een blad met `text`
  en zonder `parts`, een groep andersom, de grondslag het laatste stuk; anders FAIL met de golden en de zin. Vloeren en
  pins ongewijzigd.
- **Documentatie**: `docs/ENGINE.md` (de vlag bij de dienst en de playground, de sectie "The parts of an explanation"
  met de tabel van de soorten en de grammatica), README ("Contract change": het nieuwe veld; "Known losses": de
  grondslag van een default).

### De soorten zoals gebouwd

| soort | blad/groep | tekst | meta |
|---|---|---|---|
| `SUBJECT` | blad | het label van een instantie: het onderwerp, of een lid van een vouw | — |
| `NAME` | blad | een gedeclareerde naam via `Naming`: het naamdeel van een gezegde, een attribuut ("heeft als premie"), een rol in een wandeling, een parameter of attribuut zonder brontekst, de variant van de opgavemarkering | `declaration` (`KENMERK`, `ATTRIBUUT`, `ROL`, `PARAMETER`), `owner` (null bij een parameter), `canonical`; geen meta waar de declaratie niet bekend is |
| `QUOTE` | blad | regeltekst zoals geschreven: een operand, de tekst van een vouw | de drie sleutels van `NAME` waar het operand een attribuut of parameter leest |
| `VALUE` | blad | een waarde met eenheid, een literal | `empty: true` bij een lege |
| `RULE` | blad | regel- of variantnaam zonder aanhalingstekens | — |
| `CONNECTIVE` | blad | omdat, want, maar, en, of, en niet, niet geldt, gold niet | — |
| `TEXT` | blad | lijm | — |
| `CONDITION` | groep | een atoom, een samenstelling (haar leden met `CONNECTIVE` ertussen), een vouw | `holds`; bij een samenstelling of vouw `quantifier` (`all`, `none`, `atLeast`, `atMost`, `exactly`, `the`) en `threshold` bij een telling |
| `MEMBERS` | groep | de leden na de dubbele punt, met "(n van de m)" en "(k leden niet beoordeeld)" | `members`, `holding`, `unjudged` |
| `GROUND` | groep | ". Grondslag: " als `TEXT`, dan de annotaties ontleed; altijd het laatste stuk | — |
| `STATUTE` | blad | het anker letterlijk | `bwb`, `article`, en `paragraph`, `part` waar het ze noemt |
| `LEMMA` | blad | de slug | — |
| `DEVIATION` | blad | de slug | `followed` |
| `NOTE` | blad | elk ander segment | — |

Gemeten op v2 (de vijf verzoeken, 171 zinnen): 454 voorwaarden, alle met `holds`; 163 `NAME` op een attribuut, 138 op
een kenmerk, 11 op een rol; 288 `QUOTE` met een declaratie (282 attribuut, 6 parameter), 109 zonder (berekende
operanden, de tekst van een vouw).

### De JSON-vorm van één v2-zin (restant, zin 3)

"R1 is geen recht op grond-van-de-wia, want niet geldt: de code sz product van de uitkering van het RechtOpUitkering
(onbekend) is gelijk aan Wet Werk en Inkomen naar Arbeidsvermogen of … . Grondslag: BWBR0004045&artikel=21&lid=2&o=b ·
"een recht op uitkering op grond van de Wet werk en inkomen naar arbeidsvermogen"; herkend aan de code SZ product van de
uitkering van het recht · lemma's: recht-op-grond-van-de-wia · uitkering"

```json
[
 {"kind": "SUBJECT", "text": "R1"},
 {"kind": "TEXT", "text": " is geen "},
 {"kind": "NAME", "text": "recht op grond-van-de-wia", "declaration": "KENMERK", "owner": "RechtOpUitkering",
  "canonical": "recht op grond-van-de-wia"},
 {"kind": "TEXT", "text": ", "}, {"kind": "CONNECTIVE", "text": "want"}, {"kind": "TEXT", "text": " "},
 {"kind": "CONNECTIVE", "text": "niet geldt"}, {"kind": "TEXT", "text": ": "},
 {"kind": "CONDITION", "holds": false, "quantifier": "atLeast", "threshold": 1, "parts": [
   {"kind": "CONDITION", "holds": false, "parts": [
     {"kind": "QUOTE", "text": "de code sz product van de uitkering van het RechtOpUitkering", "declaration": "ATTRIBUUT",
      "owner": "Uitkering", "canonical": "code sz product"},
     {"kind": "TEXT", "text": " ("}, {"kind": "VALUE", "text": "onbekend", "empty": true},
     {"kind": "TEXT", "text": ") is gelijk aan "}, {"kind": "VALUE", "text": "Wet Werk en Inkomen naar Arbeidsvermogen"}]},
   {"kind": "TEXT", "text": " "}, {"kind": "CONNECTIVE", "text": "of"}, {"kind": "TEXT", "text": " "},
   {"kind": "CONDITION", "holds": false, "parts": ["…"]},
   "…"]},
 {"kind": "GROUND", "parts": [
   {"kind": "TEXT", "text": ". Grondslag: "},
   {"kind": "STATUTE", "text": "BWBR0004045&artikel=21&lid=2&o=b", "bwb": "BWBR0004045", "article": "21",
    "paragraph": "2", "part": "b"},
   {"kind": "TEXT", "text": " · "},
   {"kind": "NOTE", "text": "\"een recht op uitkering op grond van de Wet werk en inkomen naar arbeidsvermogen\"; herkend aan de code SZ product van de uitkering van het recht"},
   {"kind": "TEXT", "text": " · lemma's: "}, {"kind": "LEMMA", "text": "recht-op-grond-van-de-wia"},
   {"kind": "TEXT", "text": " · "}, {"kind": "LEMMA", "text": "uitkering"}]}
]
```

De stukken van alle vijf verzoeken staan in `werk/defaults-decider/t4/parts/<verzoek>.parts.json`
(`{"explanation": [...], "explanationParts": [...]}`), als voorbeeld voor de werkbank.

### Bytegelijkheid

- **v2, de zin** (stap 1): `measure.sh` → `na5/`: `*.explanation.txt` en `*.answer.json` van de vijf verzoeken
  bytegelijk aan `na4b/` (`cmp`), 171 zinnen. Elke bestaande uitlegtest ongewijzigd groen.
- **v2, de stukken** (acceptatie (a)): `t4/parts.sh` vraagt de vijf verzoeken met `?explanationParts=true`; het
  antwoord buiten de twee velden is gelijk aan `na5/*.answer.json`, `explanation` aan `na5/*.explanation.txt`, en per
  zin is de aaneengeschakelde tekst van de stukken de zin (171 van 171), de grondslag het laatste stuk (83 zinnen met
  `GROUND`, het getal van de kaart).
- **Het corpus**: `check-service-replay.sh` PASS (`t4/logs/replay-1.log`): 232 goldens, 614 cases (pin
  232), 1096 zinnen, elk gelijk aan haar stukken, 1245 uitgelegde voorwaarden (vloer 1241), 80 s. De leg bijt: een
  proef met zes mutanten (`t4/replay_parts_probe.py`) op een nagemaakt antwoord (een tekst die afwijkt, een groep met `text`, een onbekende soort,
  een grondslag die niet de laatste is, een stukkenlijst te veel, het veld weg) faalt alle zes als `Deviation`.

### Tests

Nieuw:
- `GroundTest` (6): elk segmentpatroon (een anker met en zonder lid en onderdeel, een artikel als "7:668a", een citaat,
  de lemmakop, een lemma, een afwijking gevolgd en niet gevolgd), een bijna-anker en een slug of afwijking vóór de
  lemmakop als `NOTE`, twee annotaties met "; " als lijm, een `Default.source` waarvan "; " in een segment blijft, een
  leeg segment, een groep zonder meta.
- `SentenceTest` (4): de tekst is die van de bladeren, lijm voegt samen, een null-eigenaar blijft, een verkeerde of
  ontbrekende `close`, lijm als blad, een blad met stukken, een groep met een andere tekst en meta van een ander type
  falen luid.
- `ExplanationPartsServiceTest` (4), acceptatie (a) en (b) op de dienst-route, uit bronnen en uit een IR-document, met
  een eigen model (een Jutter is ervaren onder een geneste lijst: zijn leeftijd boven de parameter minimumleeftijd, en
  ten minste één van: meer dan 10 meldingen, ingezetene; twee `// Bron:`-annotaties met alle vier de kinderen; een
  kwantorvouw over zijn vondsten; een defaults-groep met een uitzondering met eigen annotatie; een kenmerkcheck over
  een rol). Per zin van elk antwoord toetst de helper `ExplanationPartsCheck` de aaneenschakeling tegen de zin, en de
  test toetst dat elke soort voorkomt en pint de stukken van zeven zinnen: de geneste of/en-lijst (geldt en geldt
  niet), de vouw met haar leden, "maar de regel 'ervaren' gold niet", een lege waarde, de defaults-zin met de grondslag
  van een default, de rol. Verder: zonder vlag geen veld, met `explanation=true` alleen geen stukken, met
  `explanationParts` beide en verder bytegelijk; de queryvlag.
- `PlaygroundExplanationPartsTest` (1): `/playground/analyse` geeft per scenario `explanationParts` op de gewone route
  en met `via=service`, gelijk aan elkaar en aan de zinnen.

Aangepast, met reden (geen toets verzwakt):
- `DetailHttpTest` (twee plaatsen) en de Quarkus-tests `DetailParityTest`, `NoFixpointParityTest`: de handtekening van
  `RequestOptions` (zeven componenten) en van `DecideResource.process` (een parameter `explanationParts`) groeide; de
  aanroepen geven `false` of `null` erbij, de toetsen zijn gelijk.
- De vier OpenAPI-goldens (`service/src/test/resources/openapi/*.golden.json`): overgeschreven uit `target/openapi/`;
  het verschil is alleen toevoeging (de vlag per operatie, het veld in `Answer`, het schema `ExplanationPart`).

Suites: `mvn -B -o verify` over de hele reactor op de code van `85faaefc` (vóór de commit, dezelfde bomen): BUILD
SUCCESS, 1649 (Drools) / 161 (toolchain, was 151: +10) / 356 (dienst, was 351: +5) / 53 (Quarkus), skip-pin PASS op
de drie poorten (`t4/logs/verify-1.log`).

### Voorlopige keuzes van de bouwer (het advies beantwoordt ze niet; dicht bij advies en zinsvormen)

1. **". Grondslag: " hoort bij het stuk `GROUND`** (als `TEXT`, ook de punt). Zo is een zin zonder haar laatste stuk
   precies de zin van een regel zonder annotatie, en hoeft de werkbank geen punt weg te knippen.
2. **`holds` ontbreekt** waar de motor de knoop bereikte zonder oordeel (na een lege binder, ch. 10.6); het advies
   noemt `read.of(c).value()`. Op v2 en in de tests komt het niet voor.
3. **De variant van de opgavemarkering** (`'opgegeven X'`) is een `NAME` (attribuut), geen `RULE`: haar tekst komt
   uit `Naming` en verandert met het vocabulaire, dus de werkbank heeft de canonieke naam nodig.
4. **`inVocabulary`** (de regel die uchar schreef): de tekst om de naam heen is `QUOTE`, de naam `NAME`.
5. **Verbindingswoorden**: alleen de acht van het advies, plus "en" tussen de beslissers van een conflict. "volgens",
   "besliste niet anders", "verdrongen", "niet voor"/"wel voor" bij de leden zijn `TEXT`.
6. **Na een afwijking** blijft een slug een `LEMMA` (v2 heeft geen lemma na een afwijking en geen afwijking vóór de
   lemmakop); een leeg segment geeft alleen zijn scheidingstekens.
7. **`STATUTE`** zonder lid of onderdeel draagt die sleutels niet (geen null); `NAME` draagt `owner` altijd, null bij een
   parameter, zoals `vocabularies.elements[]`.
8. **`explanationParts=true` vraagt ook `explanation`** (`RequestOptions` zet het aan); zonder geen 400.

### Gates

`make gates` op `85faaefc` met `TAG/CLUSTER=explanation-parts`: GROEN, 7 stappen in 12m10s (reactor 1649/161/356/53;
replay 232 goldens, 614 cases, pin 232, elke zin gelijk aan haar stukken; manifest 29 ok, 3 uitgesloten; image
`decision-service:explanation-parts`; kind-smoke H2–H4 PASS). Log: `werk/defaults-decider/t4/tc-gates.log`.

### Wat open bleef

- **Het herhaalde onderwerp** (open punt van de kaart): "de code sz product van de uitkering van het RechtOpUitkering
  (onbekend) is gelijk aan A of de code sz product … is gelijk aan B of …" herhaalt het onderwerp per lid; de zin
  korter maken verandert de tekst voor het taalmodel en is een eigen besluit (kaart `explanation-for-the-model`, na een
  meting met de eval). De stukken laten de eval-runner varianten samenstellen: de leden van een `CONDITION` met
  `quantifier` en hun `QUOTE`'s zijn per lid te herkennen.
- **De grondslag van een default** is één annotatie: uchar voegt de annotaties van een defaults-blok al samen met "; "
  (`Default.source`, `RuleChecker.annotationText`), en "; " komt ook binnen een toelichting voor, dus de stukken kunnen
  twee annotaties van een default niet scheiden. De oplossing ligt in uchar (`Default.source` als lijst), niet in tekst
  splitsen hier. v2 heeft één annotatieregel per blok.
- **De tekst voor het taalmodel** (grondslag inkorten tot de wetsverwijzingen, het herhaalde onderwerp één keer): kaart
  `explanation-for-the-model`, na de eval-meting; deze kaart verandert de zin niet.
- Geen soort voor een CGM-begrip: geen getuige op regelniveau (advies §2.2).
- De grondslag van een default: uchar-kaart `default-statute-annotations` (een default draagt zijn annotaties als lijst,
  ondoorzichtig voor uchar) is in aanbouw; daarna leest de ontleder de lijst, in de toolchain-ronde met
  `explanation-made-object-names`.
- De tekst voor het taalmodel: kaart `explanation-for-the-model`, na een meting met de eval van de werkbank.

Geland 2026-10-21 door de hoofdsessie: fast-forward naar `d8475cd8` (`make gates` groen op `85faaefc`, 12m10s, `TAG/CLUSTER=explanation-parts`; de Result-commit erna raakt alleen de kaart). Pin uchar `4f283d9d`. Voorlopige besluiten (Fable-advies 3 en de bouwer) staan hierboven; de eigenaar kan ze omdraaien.
