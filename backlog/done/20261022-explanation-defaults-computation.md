# explanation-defaults-computation: de beslissende variant van een defaults-groep op een attribuut krijgt haar berekening

*Uit het Fable-advies van 2026-10-22 over de uitleg van berekende waarden
(`uchar-dev/research/uitleg-berekende-waarden-2026-10-22/fable-advies.md`, §5 T2, beslispunt 5: voorlopig besluit
"ja, apart"). Na `explanation-computed-attributes`.*

## Aanleiding

Na `explanation-computed-attributes` draagt een zin over een berekende waarde haar berekening (groep `COMPUTATION`).
De variant die in een defaults-groep op een attribuut besliste, krijgt die nog niet: de lezing draagt de knopen van
`Default.consequence`, en voor een `opgave boven berekening X`-default is dat de lezing van `berekende X`.

## Doel

De beslissende variant krijgt dezelfde `COMPUTATION` na haar naam. Dit verandert bestaande zinnen (op v2 onder meer
U1.maxdatum, G1.beëindigingsdatum, G1.herlevingsdatum en elke "volgens 'berekende …' (onvoorwaardelijk)"), daarom een
eigen kaart.

## Acceptatie

- (a) Rood eerst op de drie routes met een eigen model.
- (b) De v2-meting vóór en na, met elke veranderde zin in de Result; het antwoord buiten de uitleg bytegelijk.
- (c) `make gates` groen.

## Voorlopige besluiten van de bouwer (2026-10-22)

*Gemarkeerd voorlopig: een keuze die het advies niet uitdrukkelijk beslist, dicht bij het advies en bij T1. De eigenaar
kan haar omdraaien.*

- **W1 Elke beslissende variant op een attribuut**, ook de default `opgegeven X` die de opgavemarkering maakt, krijgt
  haar berekening (v2, intrekking): "R1 heeft als beëindigingsdatum 01-03-2026 volgens 'opgegeven beëindigingsdatum'
  (onvoorwaardelijk): zijn opgegeven beëindigingsdatum (01-03-2026); verdrongen: 'berekende beëindigingsdatum'". De kaart
  noemt "elke 'volgens 'berekende …' (onvoorwaardelijk)'"; het advies (§5 T2) zegt "de variant die … besliste", zonder
  uitzondering. De berekening herhaalt hier de waarde van de zin, zoals bij een gewone regel die één attribuut leest
  (T1); de `NAME` met `canonical=opgegeven X` laat de keten bij de invoer stoppen.

## Result (2026-10-22)

Tak `card/explanation-defaults-computation` in de worktree `wt-tc/explanation-defaults-computation`, vanaf toolchain main
`37b7e11d`, uchar-pin `88c4624b` ongewijzigd. Gebouwd volgens het Fable-advies (`research/uitleg-berekende-waarden-2026-10-22/
fable-advies.md`, §5 T2, beslispunt 5) en de vorm van T1 (`explanation-computed-attributes`); `make gates` en de landing
doet de hoofdsessie. Niets gemerged, niets gepusht.

- `6cc1039f` de kaart naar `doing/`.
- `494d9855` de beslissende variant krijgt haar berekening: de Verbalisator, de twee nieuwe tests, de bestaande tests
  die zulke zinnen pinnen, de OpenAPI-beschrijving van `COMPUTATION` met de vier goldens, `docs/ENGINE.md`, README, en
  voorlopig besluit W1 in de kaart.
- `e9e171fd` een jaar uit een datum zonder duizendtalpunt (een bestaande fout, zie Vondsten), met een getuige.
- `030df2ea` de replay-leg zegt dat de zin van de beslissende variant meetelt als berekening.

Werkmap: `werk/toolchain-2026-10-22/defaults-computation/` (`logs/`, `meting/`).

### Wat er veranderde

- **`DefaultsExplanation`** (`toolchain/.../engine/Verbaliser.java`): in de zin van een defaults-groep op een
  attribuut zet `reason` na de naam van de variant die besliste dezelfde `COMPUTATION` als T1 (`ComputationExplanation
  .computation`): na " (onvoorwaardelijk)" en vóór ", omdat …", zoals de zin van een berekende waarde. De waarde van een
  default is zijn `consequence`, die van een regel die ch. 7.10 in de stapel opnam haar `value`. De lezing is die van de
  variant op haar pointer; de knopen van de waarde volgen op die van haar voorwaarde zoals het IR haar draagt (ch. 5.12):
  offset `positions.subtree(condition).size()`, 0 zonder voorwaarde (de initialisatiedefault, of een regel zonder
  voorwaarde in een stapel, wier lege rechtvaardiging nergens in het document staat), 1 voor de lege `ALLE` van de
  opgavemarkering. Een kenmerk krijgt geen berekening. Elke andere variant (vóór, ernaast, verdrongen), de
  conflictzin en de zin waarin niemand besliste blijven zoals ze waren.
- **`ComputationExplanation.computation`** neemt nu `(root, ucharText, read)` in plaats van een `Writer`, zodat de
  defaults-zin haar kan aanroepen; de `ComputationExplanation` van het object gaat daarvoor mee in de constructor van
  `DefaultsExplanation`. Gedrag van T1 gelijk.
- **OpenAPI** (`OpenApiEmitter`): de beschrijving van `ExplanationPart` noemt bij `COMPUTATION` ook de variant die een
  defaults-groep op een attribuut besliste. De vier OpenAPI-goldens overgeschreven uit `target/openapi/`; het verschil
  is alleen die zin.
- **`docs/ENGINE.md`**: de rij `COMPUTATION` in de tabel en een alinea "The deciding variant of a defaults group".
  **README**, "Contract change": een regel die zegt dat bestaande zinnen veranderen, met een voorbeeld.
- **Replay-leg** (`scripts/check-service-replay.sh`, `scripts/service_replay.py`): alleen tekst; de telling
  `computed` telde de nieuwe zinnen al mee (onderwerp, " heeft als ", een groep `COMPUTATION`), en het commentaar zegt dat
  nu. Vloer `EXPECTED_MIN_EXPLAINED_COMPUTATIONS=1852` ongewijzigd.

### Rood eerst (acceptatie (a))

Een eigen model met Jutterswet-namen, op de drie routes (scenarioroute, dienst uit bronnen, dienst uit het
IR-document): op een Jutter de premie (basis 'premie' zonder voorwaarde, met grondslag, en de uitzondering 'premie uit
bonus' met een eigen expressie, een percentage naar beneden afgerond); op een Vondst de waarde met de opgavemarkering
(opgave, 'berekende waarde' uit de regel 'waarde', en de initialisatie zonder voorwaarde); op een Wrakrecht de uitkering,
waar de eigen regel van het subtype zonder voorwaarde onder de geërfde uitzondering beslist (ch. 7.10). Beslissers: J1
de basis, J2 de uitzondering, V1 'berekende waarde', V2 'opgegeven waarde', V3 de initialisatie, W1 de regel van het
subtype, W2 de uitzondering.

- `ExplanationDefaultsComputationTest` (toolchain, scenarioroute, 1): de 13 zinnen.
- `ExplanationDefaultsComputationServiceTest` (dienst, 2): dezelfde 13 zinnen uit bronnen en uit het IR-document, de
  stukken van vier zinnen gepind (J2: de eigen expressie met parameter en "vóór de afronding"; V1: 'berekende waarde' met
  `NAME{canonical=berekende waarde}` in de berekening; V3: de initialisatie, `QUOTE«25,00»`; W1: de regel van het
  subtype zonder voorwaarde) en de ene grondslag (J1).

Rood: alle drie op de code van `6cc1039f` (`logs/red-2.log`, `logs/red-5.log`); de zinnen zonder berekening waren
verder letterlijk de verwachte, en op de drie routes gelijk (`logs/red-scenario.txt`, `logs/red-service.txt`). Groen na
de bouw (`logs/green-4.log`).

Zin J2, vóór: "J2 heeft als premie 15,43 volgens 'premie uit bonus', omdat het onderwerp ingezetene is; verdrongen: de
regel 'premie'"; na: "J2 heeft als premie 15,43 volgens 'premie uit bonus': (het percentage premie van zijn bonus) naar
beneden afgerond op 2 decimalen, met percentage premie (12,5 %) en zijn bonus (123,45); vóór de afronding 15,43125,
omdat het onderwerp ingezetene is; verdrongen: de regel 'premie'".

### Bestaande tests die veranderden (de kaart verandert wat ze pinden; geen toets verzwakt)

Elke verandering is het invoegen van de berekening na de naam van de beslissende variant, nagerekend per mislukte
toets (`tools/insertions.py` over de surefire-rapporten: 68 invoegingen, 0 andere verschillen, `logs/insertions-1.txt`):

- `ExplanationDefaultsTest`, `ExplanationDefaultsServiceTest`: de premie van J1, J2, J3, J5 (": 100,00", ": zijn bonus
  (20,00)"), de stapel uit erfenis (R1 ": 10", W1 ": 200", W2 ": 99"), en in de dienst de ongeordende groep (K2 ": 30",
  K3 ": 45").
- `ExplanationStatuteAnnotationTest`, `ExplanationStatuteAnnotationServiceTest`: W1 en W2 van de stapel.
- `ExplanationGeneratedRuleInVocabularyTest` (3): de opgave en de berekening van de meldingsdatum, in elk vocabulaire
  ("…: zijn opgegeven Datum melding vondst (12-03-2026)").
- `ExplanationGroundSourceServiceTest`: de premie van J1, J2, J3, S1 en de meldingsdatum van J1, J2, J3, S1; ook de
  naamloze variant ("de variant op plaats 1 van 'premie': 50,00").
- `ExplanationPartsServiceTest`: de premie van J1 en J2, en de gepinde stukken van zin 8 (`COMPUTATION[«: »
  QUOTE«50,00»]` na `RULE«jeugdpremie»`).
- `ExplanationComputationServiceTest`: W1 van de golden `jutterswet-wrakerfbasis` (": zijn grondslag (10,00)").
- De vier OpenAPI-goldens (hierboven).

### Meting (acceptatie (b)) op v2 en s01

`meting/measure.sh` (die van T1, op deze worktree) start de dienst uit een gegeven jar met de v2-momentopname
(`werk/defaults-decider/v2`, regels `a7a5bdf`) en vraagt de zes verzoeken van T1 zonder vlag, met `?explanation=true` en
met `?explanationParts=true`. Vóór: de meting van T1 (`computed/meting/after/`, jar van `46185bb0`, dezelfde code als
`37b7e11d`). Na: jar van `e9e171fd` (`meting/after-2/`); `meting/after/` is de jar van `494d9855`, zonder de jaarfix.

`meting/compare.py` (`compare-2.log`): PASS. Zonder vlag bytegelijk; het antwoord buiten de uitleg gelijk; dezelfde 316
zinnen in dezelfde volgorde; elke veranderde zin is die van vóór met één ingevoegde berekening, als één groep
`COMPUTATION` op zinsniveau direct na de naam van de beslisser, en zonder die groep zijn haar stukken die van vóór;
elke zin gelijk aan haar stukken. **48 zinnen veranderden** (detentie 6, herkrijging 9, inkomen 8, intrekking 3,
restant 10, s01 12), de andere 268 zijn letterlijk gelijk. De tekst groeit van 231.324 naar 240.927 tekens (+4,2 %).

Elke veranderde zin, tot en met de ingevoegde berekening (vet); de rest van de zin is ongewijzigd (volledig vóór/na per
verzoek in `meting/after-2/<verzoek>.changed.txt`):

**detentie**

- R1 heeft als berekende beëindigingsdatum 10-03-2026 volgens de regel 'beëindigingsdatum art. 20 lid 1' (onvoorwaardelijk)**: de eerste van (de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag) en (de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag), met de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag (10-03-2026): over G1 (10-03-2026), het enige lid en de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag (onbekend): er is geen enkel lid**
- R1 heeft als herlevingstermijn 10-09-2026 volgens de regel 'herlevingstermijn art. 21 lid 3 en 5'**: zijn beëindigingsdatum plus de herlevingstermijn-van-zes-maanden, met zijn beëindigingsdatum (10-03-2026) en de herlevingstermijn-van-zes-maanden (6 mnd)**
- R1 heeft als beëindigingsdatum 10-03-2026 volgens 'berekende beëindigingsdatum' (onvoorwaardelijk)**: zijn berekende beëindigingsdatum (10-03-2026)**
- G1 heeft als beëindigingsdatum 10-03-2026 volgens de regel 'eindiging op de grond art. 20 lid 1'**: zijn datum aanvang eindigingsgrond recht op uitkering (10-03-2026)**
- G1 heeft als herlevingsdatum 21-05-2026 volgens de regel 'herleving na het vervallen art. 21 lid 1'**: zijn einddatum-van-een-eindigingsgrond plus 1 dg, met zijn einddatum-van-een-eindigingsgrond (20-05-2026)**
- P1 heeft als herlevingsdatum 21-05-2026 volgens 'berekende herlevingsdatum' (onvoorwaardelijk)**: zijn berekende herlevingsdatum (21-05-2026)**

**herkrijging**

- R1 heeft als berekende beëindigingsdatum 01-06-2025 volgens de regel 'beëindigingsdatum art. 20 lid 1' (onvoorwaardelijk)**: de eerste van (de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag) en (de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag), met de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag (01-06-2025): over G1 (01-06-2025), het enige lid en de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag (onbekend): er is geen enkel lid**
- R1 heeft als herlevingstermijn 28-02-2027 volgens 'herkrijgingstermijn na zelfstandig werk art. 8 lid 3'**: (de datum met jaar: het jaar uit de herkrijgingsmaand, maand: de maand uit de herkrijgingsmaand en dag: 1) plus (de uitkeringsduur van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag naar beneden afgerond op 0 decimalen, met een minimum van de minimale herkrijgingstermijn) min 1 dg, met de eerste van de datum aanvang status zelfstandige van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk' (15-06-2025): over G1 (15-06-2025), het enige lid, de datum met jaar: het jaar uit de herkrijgingsmaand, maand: de maand uit de herkrijgingsmaand en dag: 1 (01-07-2025), het jaar uit de herkrijgingsmaand (2025), de herkrijgingsmaand is (de eerste van de datum aanvang status zelfstandige van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk') plus 1 mnd (15-07-2025), de maand uit de herkrijgingsmaand (7), de uitkeringsduur van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag (20,0 mnd) en de minimale herkrijgingstermijn (18 mnd); vóór de begrenzing 20 mnd; vóór de afronding 20 mnd**
- R1 heeft als beëindigingsdatum 01-06-2025 volgens 'berekende beëindigingsdatum' (onvoorwaardelijk)**: zijn berekende beëindigingsdatum (01-06-2025)**
- R1 heeft als maxdatum 01-09-2026 volgens 'berekende maxdatum' (onvoorwaardelijk)**: zijn berekende maxdatum (01-09-2026)**
- U1 heeft als maxdatum 01-09-2026 volgens de regel 'maxdatum op de eerste werkloosheidsdag art. 42 lid 1'**: de eerste rechtdag van het recht van de Uitkeringsduur plus (zijn uitkeringsduur naar beneden afgerond op 0 decimalen), met de eerste rechtdag van het recht van de Uitkeringsduur (01-01-2025) en zijn uitkeringsduur (20,0 mnd); vóór de afronding 20 mnd**
- U1 heeft als uitkeringsduur 20,0 mnd volgens 'berekende uitkeringsduur' (onvoorwaardelijk)**: zijn berekende uitkeringsduur (20,0 mnd)**
- G1 heeft als beëindigingsdatum 01-06-2025 volgens 'eindiging per kalendermaand art. 20 lid 1 onder c en d'**: de datum met jaar: het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering, maand: de maand uit zijn datum aanvang eindigingsgrond recht op uitkering en dag: 1, met het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering (2025), zijn datum aanvang eindigingsgrond recht op uitkering (15-06-2025) en de maand uit zijn datum aanvang eindigingsgrond recht op uitkering (6)**
- G1 heeft als herlevingsdatum 01-01-2026 volgens 'herleving per kalendermaand art. 21 lid 1 na onderdeel c'**: de datum met jaar: het jaar uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd), maand: de maand uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) en dag: 1, met het jaar uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) (2026), zijn einddatum-van-een-eindigingsgrond (31-12-2025) en de maand uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) (1)**
- P1 heeft als herlevingsdatum 01-01-2026 volgens 'berekende herlevingsdatum' (onvoorwaardelijk)**: zijn berekende herlevingsdatum (01-01-2026)**

**inkomen**

- R1 heeft als berekende beëindigingsdatum 01-04-2026 volgens de regel 'beëindigingsdatum art. 20 lid 1' (onvoorwaardelijk)**: de eerste van (de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag) en (de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag), met de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag (01-04-2026): over G1 (01-04-2026), het enige lid en de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag (onbekend): er is geen enkel lid**
- R1 heeft als beëindigingsdatum 01-04-2026 volgens 'berekende beëindigingsdatum' (onvoorwaardelijk)**: zijn berekende beëindigingsdatum (01-04-2026)**
- M1 heeft als bedrag maandloon 3.480,00 EUR volgens 'berekende bedrag maandloon' (onvoorwaardelijk)**: zijn berekende bedrag maandloon (3.480,00 EUR)**
- G1 heeft als beëindigingsdatum 01-04-2026 volgens 'eindiging per kalendermaand art. 20 lid 1 onder c en d'**: de datum met jaar: het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering, maand: de maand uit zijn datum aanvang eindigingsgrond recht op uitkering en dag: 1, met het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering (2026), zijn datum aanvang eindigingsgrond recht op uitkering (01-04-2026) en de maand uit zijn datum aanvang eindigingsgrond recht op uitkering (4)**
- G1 heeft als herlevingsdatum 01-08-2026 volgens 'herleving per kalendermaand art. 21 lid 1 na onderdeel c'**: de datum met jaar: het jaar uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd), maand: de maand uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) en dag: 1, met het jaar uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) (2026), zijn einddatum-van-een-eindigingsgrond (31-07-2026) en de maand uit (zijn einddatum-van-een-eindigingsgrond plus 1 mnd) (8)**
- G1 heeft als datum aanvang eindigingsgrond recht op uitkering 01-04-2026 volgens 'berekende datum aanvang eindigingsgrond recht op uitkering' (onvoorwaardelijk)**: zijn berekende datum aanvang eindigingsgrond recht op uitkering (01-04-2026)**
- G1 heeft als einddatum-van-een-eindigingsgrond 31-07-2026 volgens 'berekende einddatum-van-een-eindigingsgrond' (onvoorwaardelijk)**: zijn berekende einddatum-van-een-eindigingsgrond (31-07-2026)**
- P1 heeft als herlevingsdatum 01-08-2026 volgens 'berekende herlevingsdatum' (onvoorwaardelijk)**: zijn berekende herlevingsdatum (01-08-2026)**

**intrekking**

- R1 heeft als beëindigingsdatum 01-03-2026 volgens 'opgegeven beëindigingsdatum' (onvoorwaardelijk)**: zijn opgegeven beëindigingsdatum (01-03-2026)**
- G1 heeft als herlevingsdatum 01-06-2026 volgens de regel 'herleving na het vervallen art. 21 lid 1'**: zijn einddatum-van-een-eindigingsgrond plus 1 dg, met zijn einddatum-van-een-eindigingsgrond (31-05-2026)**
- P1 heeft als herlevingsdatum 01-06-2026 volgens 'berekende herlevingsdatum' (onvoorwaardelijk)**: zijn berekende herlevingsdatum (01-06-2026)**

**restant**

- R1 heeft als berekende beëindigingsdatum 01-07-2025 volgens de regel 'beëindigingsdatum art. 20 lid 1' (onvoorwaardelijk)**: de eerste van (de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag) en (de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag), met de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag (01-07-2025): over G1 (01-07-2025), het enige lid en de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag (onbekend): er is geen enkel lid**
- R1 heeft als beëindigingsdatum 01-07-2025 volgens 'berekende beëindigingsdatum' (onvoorwaardelijk)**: zijn berekende beëindigingsdatum (01-07-2025)**
- R1 heeft als maxdatum 01-01-2026 volgens 'berekende maxdatum' (onvoorwaardelijk)**: zijn berekende maxdatum (01-01-2026)**
- U1 heeft als maxdatum 01-01-2026 volgens de regel 'maxdatum op de eerste werkloosheidsdag art. 42 lid 1'**: de eerste rechtdag van het recht van de Uitkeringsduur plus (zijn uitkeringsduur naar beneden afgerond op 0 decimalen), met de eerste rechtdag van het recht van de Uitkeringsduur (01-01-2025) en zijn uitkeringsduur (12,0 mnd); vóór de afronding 12 mnd**
- U1 heeft als uitkeringsduur 12,0 mnd volgens 'berekende uitkeringsduur' (onvoorwaardelijk)**: zijn berekende uitkeringsduur (12,0 mnd)**
- G1 heeft als beëindigingsdatum 01-07-2025 volgens 'eindiging per kalendermaand art. 20 lid 1 onder c en d'**: de datum met jaar: het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering, maand: de maand uit zijn datum aanvang eindigingsgrond recht op uitkering en dag: 1, met het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering (2025), zijn datum aanvang eindigingsgrond recht op uitkering (15-07-2025) en de maand uit zijn datum aanvang eindigingsgrond recht op uitkering (7)**
- R2 heeft als duurverlenging bij-een-nieuw recht 184 dg volgens de regel 'duurverlenging onder a art. 42b lid 1'**: de resterende uitkeringsduur van het eerdere recht van het RechtOpUitkeringWwBijOntslag (184 dg)**
- R2 heeft als maxdatum 01-06-2026 volgens 'berekende maxdatum' (onvoorwaardelijk)**: zijn berekende maxdatum (01-06-2026)**
- U2 heeft als maxdatum 01-06-2026 volgens de regel 'maxdatum op de eerste werkloosheidsdag art. 42 lid 1'**: de eerste rechtdag van het recht van de Uitkeringsduur plus (zijn uitkeringsduur naar beneden afgerond op 0 decimalen), met de eerste rechtdag van het recht van de Uitkeringsduur (01-03-2026) en zijn uitkeringsduur (3,0 mnd); vóór de afronding 3 mnd**
- U2 heeft als uitkeringsduur 3,0 mnd volgens 'berekende uitkeringsduur' (onvoorwaardelijk)**: zijn berekende uitkeringsduur (3,0 mnd)**

**s01**

- R1 heeft als berekende beëindigingsdatum 01-04-2025 volgens de regel 'beëindigingsdatum art. 20 lid 1' (onvoorwaardelijk)**: de eerste van (de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag) en (de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag), met de eerste van de beëindigingsdatum van alle eindigingsgronden van het RechtOpUitkeringWwBijOntslag (01-04-2025): over G1 (01-04-2025), het enige lid en de eerste van de beëindigingsdatum van alle uitsluitingsgronden van het RechtOpUitkeringWwBijOntslag (onbekend): er is geen enkel lid**
- R1 heeft als eerste kalendermaand zonder overschrijding-vanaf-de-gewenste-maand 01-08-2025 volgens 'gewenste maand zonder overschrijding art. 21 lid 1'**: de datum met jaar: het jaar uit de datum gewenste aanvang uitkering van de aanvraag uitkering van het Recht op uitkering WW, maand: de maand uit de datum gewenste aanvang uitkering van de aanvraag uitkering van het Recht op uitkering WW en dag: 1, met het jaar uit de datum gewenste aanvang uitkering van de aanvraag uitkering van het Recht op uitkering WW (2025), de datum gewenste aanvang uitkering van de aanvraag uitkering van het Recht op uitkering WW (01-08-2025) en de maand uit de datum gewenste aanvang uitkering van de aanvraag uitkering van het Recht op uitkering WW (8)**
- R1 heeft als beëindigingsdatum 01-04-2025 volgens 'berekende beëindigingsdatum' (onvoorwaardelijk)**: zijn berekende beëindigingsdatum (01-04-2025)**
- R1 heeft als maxdatum 01-07-2026 volgens 'berekende maxdatum' (onvoorwaardelijk)**: zijn berekende maxdatum (01-07-2026)**
- U1 heeft als maxdatum 01-07-2026 volgens de regel 'maxdatum op de eerste werkloosheidsdag art. 42 lid 1'**: de eerste rechtdag van het recht van de Uitkeringsduur plus (zijn uitkeringsduur naar beneden afgerond op 0 decimalen), met de eerste rechtdag van het recht van de Uitkeringsduur (01-01-2025) en zijn uitkeringsduur (18,0 mnd); vóór de afronding 18 mnd**
- U1 heeft als uitkeringsduur 18,0 mnd volgens 'opgegeven uitkeringsduur' (onvoorwaardelijk)**: zijn opgegeven uitkeringsduur (18,0 mnd)**
- M1 heeft als bedrag maandloon 3.000,00 EUR volgens 'opgegeven bedrag maandloon' (onvoorwaardelijk)**: zijn opgegeven bedrag maandloon (3.000,00 EUR)**
- G1 heeft als beëindigingsdatum 01-04-2025 volgens 'eindiging per kalendermaand art. 20 lid 1 onder c en d'**: de datum met jaar: het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering, maand: de maand uit zijn datum aanvang eindigingsgrond recht op uitkering en dag: 1, met het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering (2025), zijn datum aanvang eindigingsgrond recht op uitkering (01-04-2025) en de maand uit zijn datum aanvang eindigingsgrond recht op uitkering (4)**
- G1 heeft als herlevingsdatum 01-08-2025 volgens 'gewenste maand in het herlevingsverzoek art. 21 lid 1 na onderdeel c'**: de eerste kalendermaand zonder overschrijding-vanaf-de-gewenste-maand van het recht van de Eindigingsgrond (01-08-2025)**
- G1 heeft als datum aanvang eindigingsgrond recht op uitkering 01-04-2025 volgens 'berekende datum aanvang eindigingsgrond recht op uitkering' (onvoorwaardelijk)**: zijn berekende datum aanvang eindigingsgrond recht op uitkering (01-04-2025)**
- G1 heeft als einddatum-van-een-eindigingsgrond 30-06-2025 volgens 'berekende einddatum-van-een-eindigingsgrond' (onvoorwaardelijk)**: zijn berekende einddatum-van-een-eindigingsgrond (30-06-2025)**
- P1 heeft als herlevingsdatum 01-08-2025 volgens 'berekende herlevingsdatum' (onvoorwaardelijk)**: zijn berekende herlevingsdatum (01-08-2025)**

Twee voorbeelden, volledig:

- U1.maxdatum (restant), vóór: "U1 heeft als maxdatum 01-01-2026 volgens de regel 'maxdatum op de eerste
  werkloosheidsdag art. 42 lid 1', omdat de eerste rechtdag van het recht van de Uitkeringsduur (01-01-2025) gevuld is
  en …"; na: "… art. 42 lid 1': de eerste rechtdag van het recht van de Uitkeringsduur plus (zijn uitkeringsduur naar
  beneden afgerond op 0 decimalen), met de eerste rechtdag van het recht van de Uitkeringsduur (01-01-2025) en zijn
  uitkeringsduur (12,0 mnd); vóór de afronding 12 mnd, omdat de eerste rechtdag … en …" (de vraag van het advies §1.1:
  hoe 01-01-2026 uit 01-01-2025 en 12,0 mnd volgt).
- R1.maxdatum (s01), vóór: "R1 heeft als maxdatum 01-07-2026 volgens 'berekende maxdatum' (onvoorwaardelijk);
  'opgegeven maxdatum' gaf geen waarde"; na: "R1 heeft als maxdatum 01-07-2026 volgens 'berekende maxdatum'
  (onvoorwaardelijk): zijn berekende maxdatum (01-07-2026); 'opgegeven maxdatum' gaf geen waarde", met
  `NAME{canonical=berekende maxdatum}` in de berekening, zodat de keten bij de zin van de berekende maxdatum komt.

### Replay en suites

- `check-service-replay` op `e9e171fd` (`logs/replay-1.log`): PASS, 241 goldens, 647 cases (pin 241), 30 uitgesloten,
  3443 zinnen (gelijk), elk gelijk aan haar stukken, 2209 uitgelegde voorwaarden (gelijk; vloer 1241), **2046
  berekende waarden** (was 1852; vloer 1852, niet verhoogd): de 194 erbij zijn zinnen van een defaults-groep op een
  attribuut, die nu de berekening van hun beslisser dragen. 0 keer "niet is vast te stellen". 74 s met vier diensten.
- `check-twins` PASS (3 tweelingen, 18 cases; ongewijzigd), `make scenario-test` 32 regels, 29 ok, 3 uitgesloten, 0
  mismatch (`logs/twins-1.log`, `logs/scenario-1.log`).
- `mvn -B -o verify` over de hele reactor op `494d9855` (`logs/verify-1.log`): BUILD SUCCESS, 1652 (Drools) / 170
  (toolchain, was 169: +1) / 373 (dienst, was 371: +2) / 53 (Quarkus), skip-pin PASS op de drie poorten, 7:57 min. Na de
  jaarfix nog eens op `030df2ea` (`logs/verify-2.log`): BUILD SUCCESS, 1652 / 170 / 374 (dienst +3: twee nieuwe tests en
  de getuige van de jaarfix) / 53, skip-pin PASS, 7:12 min.
- Niet gedraaid: `make gates` (de hoofdsessie).

### Vondsten

- **Toolchain, bestaand, gefixt** (`e9e171fd`): de waarde van `het jaar uit X` stond er als getal met duizendtalpunt
  ("het jaar uit zijn datum aanvang eindigingsgrond recht op uitkering (2.025)"): `renderRead` schreef elk getal met
  duizendtalpunt. Op v2 kwam dat pas naar voren in de berekening van de beslissende variant (acht keer, in "de datum met
  jaar: …"). Nu "(2025)". Alleen de uitkomst van die functie: een gedeclareerd attribuut
  met een jaartal als getal (een "startjaar" Numeriek) blijft een getal met punt, want de uitleg weet niet dat het een
  jaar is. Getuige `ExplanationComputationServiceTest.a_year_out_of_a_date_is_written_as_a_year`, rood op `494d9855`
  (`logs/red-year.log`).
- uchar: niets gevonden.

### Voorlopig besluit

W1 in de kaart (hierboven): ook de default `opgegeven X` krijgt als beslisser haar berekening (op v2 drie zinnen:
intrekking R1.beëindigingsdatum, s01 U1.uitkeringsduur en M1.bedrag maandloon).

### Wat open bleef

- `make gates` en de landing (hoofdsessie).
- De keten van de eval (`toolsets.keten` op de werkbank): niet gemeten, zoals bij T1; de `NAME` met `canonical=berekende
  X` staat nu in de berekening van elke 'berekende X'-beslisser.
- Een defaults-groep op een tijdlijn-doel krijgt geen eigen behandeling (T1 schrijft daar "een tijdlijn" zonder
  berekening): geen van de 61 groepen van uchars `corpus/golden` en `corpus/red` staat op een tijdlijn-attribuut, dus
  er is geen geval om tegen te bouwen.

### (c) `make gates` en landing (hoofdsessie, 2026-10-22)

`make gates TAG=defaults-computation CLUSTER=defaults-computation` op `030df2ea`: GROEN, 7 stappen in 11m53s (pin PASS;
reactor 1652/170/374/53; twins PASS; replay 241 goldens, 647 cases, pin 241; manifest 29 ok, 3 uitgesloten, 0
mismatch; image `decision-service:defaults-computation`; kind-smoke H2, H3, H4 PASS). Review: elke veranderde zin is
alleen de ingevoegde berekening na de naam van de beslisser; de jaarfix ("(2025)" in plaats van "(2.025)") is een
bestaande bug met getuige en gaat mee; W1 blijft staan.
