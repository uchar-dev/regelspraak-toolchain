# explanation-defaults: de uitleg verwoordt ook de defaults-groepen

*Vastgelegd door de hoofdsessie op 2026-10-20, op verzoek van de werkbank-sessie via de eigenaar. Herkomst: de punten 2
en 3 en acceptatie (b) van uchar `backlog/done/20260923-regelspraak-java-defaults-in-scenario-en-werkbank.md` ("de
uitleg noemt de beslissende default en de verdrongen defaults; een `onwaar`-besluit wordt benoemd"); die kaart verwijst
hierheen. Regelnummers op toolchain `d4fa00e5` en uchar `78bae4e3` (de pin).*

## Aanleiding

`Verbaliser.verbalise()` (`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` r. 143–144;
r. 102–103 op `21d9956`) loopt alleen over `model.resolvedRules` en slaat elke regel over die geen `KENMERKTOEKENNING`
is. Een defaults-groep (`Uitzondering op`, `Afwijking van`; in het IR `prioritizedDefaults`) staat in het model in een
eigen lijst, `RegelSpraakModel.prioritizedDefaults`, en komt in de uitleg dus nergens voor.

In de regelset herleving-WW v2 van de werkbank bepalen 19 defaults-groepen de hoofduitkomsten: de herleving, de
herleefbaarheid, de aanmerking, de beëindigingsdatum en de herlevingsdatum. Vindplaatsen onder
`/Users/yeb/Developer/uwv/regelspraak-werkbank/rulesets/regelspraak-rules/v2/herleving-ww/rules/`, onder meer
`hww-05-herleving.rgs` r. 40–100, `hww-06-samenloop.rgs` r. 21, `hww-09-verschuiving.rgs` r. 67, `hww-12-wia.rgs` r. 35,
`hww-02-grondkenmerken.rgs` r. 48 en `hww-20-aanmerking.rgs` r. 31. Gemeten met de vijf verzoeken van `make
service-smoke` (`../service/{detentie,herkrijging,inkomen,intrekking,restant}.request.json`, descriptor
`herleving.service.json`): elk antwoord zet minstens één van `indicatie herleving recht op uitkering`, `indicatie kan
herleefd worden` en `indicatie komt in aanmerking voor herleving` op true, en geen uitlegzin noemt ze. De eval-runner
van de werkbank vraagt `?explanation=true` en geeft de uitleg als motivering aan een taalmodel
(`eval/runner/mappings/herleving_ww.md` r. 202). Een uitleg die de beslissende regels weglaat, raakt zo elk antwoord van
de eval. Inhoud gaat daarom boven opmaak: de zinnen moeten kloppen en volledig zijn; een JSON-structuur van de uitleg
hoort niet bij deze kaart.

## Wat de semantiek vastlegt (uchar `spec/RUNTIME-SEMANTICS.md` ch. 7.1, 7.2 en 7.9)

- Een groep is één schrijver met één besluit, van boven naar beneden: de **hoogste variant die beslist** levert het
  besluit, en elke lagere variant is verdrongen.
- **Kenmerk als doel:** een variant waarvan de voorwaarde geldt, beslist altijd: toekennen of niet toekennen. Een
  besluit tot niet toekennen ("is niet …", "heeft geen …") verdringt de lagere varianten; de groep leidt dan niets af.
  Dat is het `onwaar`-besluit, dat in de eindtoestand niet te onderscheiden is van "niets besloten".
- **Attribuut als doel:** een variant beslist als haar voorwaarde geldt én haar waarde gevuld is; een variant met een
  lege waarde valt door naar de volgende.
- Beslist geen variant, dan schrijft de groep niets: het doel houdt zijn invoerwaarde, of blijft leeg.
- Twee groepen van een gedeclareerd ongeordende groep die verschillend beslissen: geen schrijving, en één feit op het
  §9.5-kanaal (ch. 7.2).
- Geneste uitzonderingen (ch. 7.9): de frontend maakt van elke laag een zelfstandige voorwaarde, met de voorwaarde van de
  omsluitende laag erbij.

Daaruit volgt welke feiten de uitleg kan noemen: welke variant besliste en of dat een `onwaar`-besluit was; dat een
hogere variant niet besliste, omdat haar voorwaarde niet gold (of, bij een attribuut, omdat haar waarde leeg was); en
dat een lagere variant verdrongen werd. Hoeveel daarvan de uitleg zegt, volgt niet uit de semantiek.

## Ontwerpvraag (vóór de bouw aan de eigenaar voorleggen, met voorstel)

Hoe verwoordt de uitleg de varianten die niet beslisten? Voorstel van de hoofdsessie, bij het vastleggen:
- per waarde die een groep besliste, één zin in de vorm van een kenmerktoekenning: het doel, de variant die besliste
  (bij naam), en "omdat" met haar voorwaarden en hun waarden;
- per hogere variant die niet besliste één bijzin "… gold niet, want <eerste falende voorwaarde>" (bij een attribuut ook
  "… gaf geen waarde"); dat beantwoordt de vraag waarom de uitzondering niet gold;
- de lagere varianten alleen bij naam, als verdrongen;
- beslist geen variant, dan één zin die dat zegt en noemt wat het doel houdt;
- een conflict in een ongeordende groep als conflict benoemd.
Bij geneste uitzonderingen noemt de zin de voorwaarde zoals de auteur haar schreef, of de opgetelde voorwaarde van
ch. 7.9: ook dat is een keuze voor de eigenaar.

## Besluiten van de eigenaar (2026-10-21)

Voorgelegd door de hoofdsessie, gekozen zoals aanbevolen:

1. **Varianten die niet beslisten.** Per besliste waarde de beslisser met "omdat" en haar voorwaarden; elke variant die
   vóór de beslisser aan de beurt was met "gold niet, want <eerste falende voorwaarde>" (bij een attribuut "gaf geen
   waarde"); de varianten erna alleen bij naam, als verdrongen. Beslist niemand: dat zeggen, met wat het doel houdt.
   Een conflict heet een conflict.
2. **Geneste lagen.** De opgetelde voorwaarde zoals het IR haar draagt (ch. 7.9), met de keten van lagen bij naam.
3. **Naam op de draad.** De naam van een variant haalde IR en model niet; uchar schrijft haar nu als `Default.name`
   (uchar-kaart `defaults-decider`, die ook de ingang `DroolsBackend.decisionsOf` levert: welke variant besliste, het
   onwaar-besluit inbegrepen). Deze kaart volgt met de pin-bump.

Verwoording binnen die besluiten (hoofdsessie): een `Uitzondering op B` en een `Afwijking van B` op het eerste niveau
legt de draad gelijk vast (uchar `frontends/regelspraak/grammar/DIVERGENCES.md` §4, "Lowering"), dus daar noemt de
uitleg alleen de naam, zonder soortwoord; "de regel" alleen voor de basis, "uitzondering op …" voor een geneste laag.

## Doel

Voor elke waarde die een defaults-groep beslist, noemt de uitleg de variant die besliste en de voorwaarden waarop dat
gebeurde, in dezelfde vorm als bij een kenmerktoekenning. Een `onwaar`-besluit wordt als besluit benoemd en niet
verzwegen. De varianten die niet beslisten volgens het besluit over de ontwerpvraag.

Bouw voort op `explanation-role-path` (`backlog/done/20261020-explanation-role-path.md`): waarden komen van wat de
motor afleidde, gelezen via uchars publieke API (`DroolsBackend.reached` en verwanten). Welke variant besliste, komt
ook van de motor en niet uit een herrekening door de Verbalisator; de eigen herrekening van de Verbalisator wordt niet
uitgebreid. Kandidaten om eerst te meten: `DroolsBackend.Observer` (afgevuurde vormen) en wat de TMS-lowering per
variant afleidt; let op dat onder truth maintenance een afgevuurde regel later kan zijn ingetrokken, zodat alleen de
eindtoestand telt. Heeft dat een nieuwe publieke ingang in uchar nodig, dan komt daar een eigen kaarttak voor, die eerst
landt; deze kaart volgt dan met haar pin.

## Acceptatie

- (a) Rood eerst, met een eigen klein model (Nederlandse domeinnamen) met een `Uitzondering op` én een `Afwijking
  van`, waarin een lagere variant beslist en een `onwaar`-besluit voorkomt, op alle drie de routes: de dienst uit
  bronnen, de dienst uit een IR-document (`service/src/main/java/dev/uchar/regelspraak/toolchain/service/DecisionCore.java`)
  en de scenario-runner (`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/ScenarioRunner.java`).
- (b) Het doel hierboven, met de keuze van de eigenaar over de ontwerpvraag.
- (c) De bestaande uitlegzinnen voor kenmerktoekenningen blijven letterlijk gelijk; de bestaande tests ongewijzigd
  groen.
- (d) Bewijs op v2: de uitleg van de vijf service-smoke-verzoeken vóór en na, met de nieuwe zinnen voor de drie
  herlevingskenmerken; het antwoord buiten de uitleg blijft gelijk.
- (e) `make gates` groen op de eindstand.

Werk op een kaarttak in een worktree en land pas na akkoord van de eigenaar; pushen doet de eigenaar.

May touch: `toolchain/` (de Verbalisator en wat hem aanroept), `service/` (alleen doorgeven), hun tests, de kaart.
uchar alleen via een eigen kaarttak (hierboven).

## Open punten, niet in deze kaart

- **Graaf en dekking.** De playground-graaf en de dekking tonen de defaults-groepen ook niet (punt 2 van de uchar-kaart
  `20260923-regelspraak-java-defaults-in-scenario-en-werkbank`). Niet bouwen in deze kaart; vastgelegd zodat het niet
  verloren gaat.
- **Leeg als 0 in een berekening.** In een berekening van de motor telt een lege waarde als 0 (bij een recht zonder
  maandloon wordt de herlevingsgrens 0,00); de Verbalisator rekent dat niet na en zegt "niet vast te stellen". Uit de
  open punten van `explanation-role-path` ("De Verbalisator rekent zelf"); het open punt van de tweede rekenaar.

## Result (2026-10-21)

Tak `card/explanation-defaults` in de worktree `wt-tc/explanation-defaults`, vanaf toolchain main `34ace840`,
ongemerged en ongepusht. De kaart blijft in `doing/`.

- `9c97ae13` de pin op uchar `36d57244`.
- `c43c6dc3` de uitleg van de defaults-groepen, met de tests.
- dit Result, in de commit na `c43c6dc3`.

### De pin

uchar `d9c0b9a5` → `36d57244`, de landing van uchar-kaart `defaults-decider` (`backlog/done/20261021-defaults-decider.md`
in uchar): de kaarttak tot `072e9def` plus één documentatiecommit (de kaart naar `done/`, CONSTRUCTS rij 43, CLAUDE.md
gate 13). Eerst gepind op `072e9def`, na de landing in dezelfde commit naar `36d57244` verschoven; de code is gelijk.
`make check-pin` groen. Niets verschoof, gemeten op `072e9def`: de reactor 1636/143/334/53 tests (de uchar-backend 1632 →
1636, de getuigen van `decisionsOf`), de tweelingen PASS, de replay-poort 231 goldens en 610 cases door de dienst uit de
261 van uchars gate 12 (`EXPECTED_REPLAY_PLAYED` blijft 231: uchar voegde aan gate 12 geen golden toe, de nieuwe rode case
speelt in de replay niet mee), het TOKA-manifest 29 ok en 3 uitgesloten.

### Wat er veranderde

- **De Verbalisator** (`toolchain/.../engine/Verbaliser.java`): `verbalise(instances, typePerLabel, document,
  decisions)` geeft per label na de kenmerktoekenningen één zin per defaults-groep van het type van het object (per doel
  één stapel, in de volgorde van het document). Wie besliste, komt uit `DroolsBackend.decisionsOf`; de pointer wordt
  teruggelegd op `Verbaliser.Document`, de groepen en regels van het document van de build, en faalt luid waar dat niet
  kan (een onbekende vorm, een index buiten bereik, een beslisser zonder eigen inhoud, een doel dat niet klopt). "Vóór" en
  "na" volgen uit `rootChains` en de boom: in een keten de varianten erboven en eronder, van een default met
  uitzonderingsgroepen eerst de leden en dan de eigen inhoud als terugval (ch. 7.9). Een lid van een ongeordende groep na
  het lid dat besliste, heet "besliste niet anders": de motor noemt bij gelijke besluiten alleen het eerste lid. De
  bestaande `verbalise(instances, typePerLabel)` geeft de kenmerktoekenningen alleen, zoals voorheen.
- **De drie routes geven de beslissingen door.** De dienst: `Executor.fire(…, withDecisions)` leest `decisionsOf` na de
  terugschrijving als de uitleg gevraagd is (`Outcome.decisions`); `DecisionCore.Entrypoint.document` is uit bronnen het
  model van de scope (`CaseEmitter.withScope`, waarvan de build het document maakt), uit een IR-document wat uchars
  lader las. De scenario-runner: `CaseSession.Outcome.decisions`, na de terugschrijving gelezen; de uitleg leest het
  IR-document daar zelf nog eens met `IrLoader.loadAll`, want de build geeft de groepen die de lader naast het model
  teruggaf niet uit, en de lezingen in die groepen moeten de wandeling van dezelfde lading hebben; de typen van de labels
  worden op naam op dat model gelegd.
- **Gemeten: `decisionsOf` leest na `setDerivedValues`.** `requireFixpoint` weigert de sessie niet: de terugschrijving zet
  haar `WrittenBack`-merk met de bewaking uit en legt de relaties daarna opnieuw vast. Alle acht nieuwe tests lezen de
  beslissingen na de terugschrijving, op de drie routes. Een evaluatiefout gaat vóór: de dienst gooit haar zoals voorheen,
  en de scenario-runner geeft dan geen uitleg en leest geen beslissingen.
- **Aangepaste test, met reden:** `ExplanationGeneratedRuleInVocabularyTest` pinde de hele uitleg van een model met de
  opgavemarkering; die krijgt nu de zin van de groep `opgave boven berekening meldingsdatum` erbij (drie tests, canoniek,
  cgm en handboek). De oude zinnen staan er letterlijk en in dezelfde volgorde in; geen verwachting verdween.

### Rood eerst (a)

`ExplanationDefaultsTest` (scenario-runner) en `ExplanationDefaultsServiceTest` (dienst uit bronnen en uit een
IR-document), met een eigen model: de basis `penning` met de `Uitzondering op` `uitsluiting bij dienstverband` (een
onwaar-besluit, met `// Bron:`), daarin genest `herstel bij veel vondsten`, en de `Afwijking van` `jeugdpenning`; en een
attribuutgroep `premie` met een uitzondering die geen waarde kan geven. Op de oude Verbalisator (de pin-commit met alleen de
tests erbij) faalden ze alle vijf met `but was: <[]>`: geen zin, want alle regels van
het model zijn defaults-groepen (`werk/defaults-decider/tc-logs/red-3.log`). Na de bouw groen
(`tc-logs/green-1.log`). Daarna kwamen erbij, groen vanaf hun eerste run: een ongeordend blok met een conflict (dienst,
beide routes) en een samengestelde stapel door overerving met een gewone regel als basis van het subtype (alle drie de
routes), die de pointer naar een regelversie op het model terugleggen.

### Voorbeeldzinnen per vorm (uit de tests)

- Een lagere variant, de basis, beslist: "J1 heeft recht op de penning volgens de regel 'penning', omdat het onderwerp
  ingezetene is; 'jeugdpenning' gold niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18; 'herstel bij veel
  vondsten' (uitzondering op 'uitsluiting bij dienstverband' op 'penning') gold niet, want niet geldt: het onderwerp in
  dienst is; 'uitsluiting bij dienstverband' gold niet, want niet geldt: het onderwerp in dienst is"
- Een onwaar-besluit: "J2 heeft geen recht op de penning volgens 'uitsluiting bij dienstverband', omdat het onderwerp
  ingezetene is en het onderwerp in dienst is; 'jeugdpenning' gold niet, want niet geldt: zijn leeftijd (30) is kleiner
  dan 18; 'herstel bij veel vondsten' (uitzondering op 'uitsluiting bij dienstverband' op 'penning') gold niet, want niet
  geldt: zijn aantal vondsten (5) is groter dan 10; verdrongen: de regel 'penning'. Grondslag: Jutterswet art. 3"
- Een geneste uitzondering beslist: "J3 heeft recht op de penning volgens 'herstel bij veel vondsten' (uitzondering op
  'uitsluiting bij dienstverband' op 'penning'), omdat het onderwerp ingezetene is en het onderwerp in dienst is en zijn
  aantal vondsten (12) is groter dan 10; 'jeugdpenning' gold niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18;
  verdrongen: 'uitsluiting bij dienstverband', de regel 'penning'"
- De hoogste variant, een `Afwijking van`, beslist: "J4 heeft recht op de penning volgens 'jeugdpenning', omdat zijn
  leeftijd (16) is kleiner dan 18; verdrongen: 'herstel bij veel vondsten' (uitzondering op 'uitsluiting bij
  dienstverband' op 'penning'), 'uitsluiting bij dienstverband', de regel 'penning'"
- Een attribuut, een variant die gold maar geen waarde gaf: "J1 heeft als premie 100,00 volgens de regel 'premie', omdat
  zijn leeftijd (30) is groter dan 17; 'premie uit bonus' gaf geen waarde"
- Niemand besliste, een kenmerk: "J5 heeft geen recht op de penning, want geen variant besliste; 'jeugdpenning' gold
  niet, want niet geldt: zijn leeftijd (30) is kleiner dan 18; 'herstel bij veel vondsten' (uitzondering op 'uitsluiting
  bij dienstverband' op 'penning') gold niet, want niet geldt: het onderwerp ingezetene is; 'uitsluiting bij
  dienstverband' gold niet, want niet geldt: het onderwerp ingezetene is; de regel 'penning' gold niet, want niet geldt:
  het onderwerp ingezetene is"
- Niemand besliste, een attribuut met een invoerwaarde: "J6 houdt als premie de invoerwaarde 7,50, want geen variant
  besliste; 'premie uit bonus' gold niet, want niet geldt: zijn leeftijd (16) is groter dan 17; de regel 'premie' gold
  niet, want niet geldt: zijn leeftijd (16) is groter dan 17"; zonder invoer: "J4 heeft geen premie, want geen variant
  besliste; …"
- Een conflict (ch. 7.9, een ongeordend blok op een basis): "K1 heeft geen vergoeding, want er is een conflict in de
  uitzonderingen op de regel 'vergoeding': 'stormvergoeding' (uitzondering op 'vergoeding') besliste 'heeft als
  vergoeding 30' en 'vloedvergoeding' (uitzondering op 'vergoeding') besliste 'heeft als vergoeding 45', dus de groep
  schrijft niets"; zonder conflict: "K2 heeft als vergoeding 30 volgens 'stormvergoeding' (uitzondering op 'vergoeding'),
  omdat het onderwerp ingezetene is en het onderwerp getroffen door storm is; 'vloedvergoeding' (uitzondering op
  'vergoeding') besliste niet anders; verdrongen: de regel 'vergoeding'"
- Overerving (ch. 7.10), de gewone regel van het subtype als basis (pointer `/rules/rules/k/versions/v`): "W1 heeft als
  uitkering 200 volgens de regel 'uitkering van een wrakrecht', omdat het onderwerp gezonken is; 'betwiste uitkering'
  gold niet, want niet geldt: het onderwerp erkend is"; en "W2 heeft als uitkering 99 volgens 'betwiste uitkering', omdat
  het onderwerp erkend is en het onderwerp betwist is; verdrongen: de regel 'uitkering van een wrakrecht'"
- Een samengestelde stapel waarop niemand besliste: "W3 heeft geen uitkering, want geen variant besliste; welke varianten
  de stapel van een Wrakrecht samenstellen, geeft de motor zonder beslissing niet"
- De opgavemarkering, in vocabulaire cgm: "V1 heeft als Datum melding vondst 12-03-2026 volgens 'opgegeven Datum melding
  vondst' (onvoorwaardelijk); verdrongen: 'berekende Datum melding vondst'"

### Bewijs op v2 (d)

`/Users/yeb/Developer/uchar-dev/werk/defaults-decider/bewijs.md`, metingen `voor/` (toolchain `34ace840`) en `na/`
(`c43c6dc3`, uchar `36d57244`). In alle vijf verzoeken is het antwoord buiten de uitleg gelijk, staan alle oude zinnen
letterlijk en in dezelfde volgorde terug (13/13, 14/14, 20/20, 13/13, 21/21), en komen er 15, 17, 16, 15 en 27 zinnen
bij. Vóór noemde geen zin een van de drie herlevingskenmerken. Na, letterlijk tot aan de Grondslag; elke zin met een
beslisser gaat verder met ". Grondslag: " en de `// Bron:`-annotatie van het blok dat besliste, die in `bewijs.md`
letterlijk staat:

- **detentie**
  - "R1 heeft indicatie herleving recht op uitkering volgens de regel 'herleving art. 21 lid 1', omdat zijn
    beëindigingsdatum (10-03-2026) gevuld is en zijn herlevingsdatum (21-05-2026) gevuld is; 'nieuw recht op grond van
    de WIA art. 21 lid 2 onder b' gold niet, want de voorwaarde "ten minste één van de rechten van de samenloop van
    het Recht op uitkering WW een recht op grond-van-de-wia is" niet is vast te stellen; 'uitkeringsduur verstreken
    art. 20 lid 1 onder b' gold niet, want niet geldt: het onderwerp de uitkeringsduur verstreken heeft; 'nieuw recht
    boven de herlevingsgrens art. 21 lid 2 onder a' gold niet, want niet geldt: zijn herlevingsgrens (onbekend) gevuld
    is; 'herlevingstermijn verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold niet, want de voorwaarde "de laatste
    van (zijn herlevingsdatum) en (de einddag zelfstandig werk) is later dan zijn herlevingstermijn" niet is vast te
    stellen. Grondslag: …"
  - "G1 heeft indicatie kan herleefd worden volgens de regel 'indicatie kan herleefd worden art. 21 lid 1', omdat zijn
    code reden beëindiging recht op uitkering ww (Detentie) is gelijk aan Hoogte inkomsten uit verzekerde arbeid of
    zijn code reden beëindiging recht op uitkering ww (Detentie) is gelijk aan Urenverlies van minder dan 5 of zijn
    code reden beëindiging recht op uitkering ww (Detentie) is gelijk aan Omvang zelfstandig werk of zijn code reden
    beëindiging recht op uitkering ww (Detentie) is gelijk aan Omvang niet verzekeringsplichtig werk of zijn code
    reden beëindiging recht op uitkering ww (Detentie) is gelijk aan Niet beschikbaar voor arbeid of zijn code reden
    beëindiging recht op uitkering ww (Detentie) is gelijk aan Omvang zelfstandig werk na startperiode of zijn code
    reden beëindiging recht op uitkering ww (Detentie) is gelijk aan Mogelijke uitzetting ogv vreemdelingenwet of zijn
    code reden beëindiging recht op uitkering ww (Detentie) is gelijk aan Detentie of zijn code reden beëindiging
    recht op uitkering ww (Detentie) is gelijk aan Verblijf buitenland anders dan vakantie of zijn code reden
    beëindiging recht op uitkering ww (Detentie) is gelijk aan Voortvluchtigheid of zijn code reden beëindiging recht
    op uitkering ww (Detentie) is gelijk aan Vakantie of zijn code reden beëindiging recht op uitkering ww (Detentie)
    is gelijk aan Werkstaking of uitsluiting of zijn code reden beëindiging recht op uitkering ww (Detentie) is gelijk
    aan Uitreiziger of zijn code reden beeindiging recht op uitkering (onbekend) is gelijk aan Gemoedsbezwaardheid;
    'intrekking binnen de bezwaartermijn art. 22a lid 1' gold niet, want de voorwaarde "de aanvraag om uitkering van
    de aanvraag uitkering van het recht van de Eindigingsgrond is eerder dan de bezwaartermijn bij intrekking van het
    recht van de Eindigingsgrond" niet is vast te stellen. Grondslag: …"
  - "R1 heeft indicatie komt in aanmerking voor herleving volgens de regel 'indicatie komt in aanmerking voor
    herleving art. 21 lid 1', omdat zijn beëindigingsdatum (10-03-2026) gevuld is en het aantal eindigingsgronden van
    het RechtOpUitkering die aan de volgende voorwaarde voldoen: hij heeft de indicatie kan herleefd worden (onbekend)
    is groter dan 0 en het aantal eindigingsgronden van het RechtOpUitkering die aan de volgende voorwaarde voldoen:
    hij heeft geen indicatie kan herleefd worden (onbekend) is gelijk aan 0; 'aanmerking na een uitsluitingsgrond art.
    21 lid 1' gold niet, want de voorwaarde "het aantal uitsluitingsgronden van het RechtOpUitkering die aan de
    volgende voorwaarde voldoen: zijn beëindigingsdatum is gevuld is groter dan 0" niet is vast te stellen. Grondslag:
    …"
- **herkrijging**
  - "R1 heeft indicatie herleving recht op uitkering volgens de regel 'herleving art. 21 lid 1', omdat zijn
    beëindigingsdatum (01-06-2025) gevuld is en zijn herlevingsdatum (01-01-2026) gevuld is; 'nieuw recht op grond van
    de WIA art. 21 lid 2 onder b' gold niet, want de voorwaarde "ten minste één van de rechten van de samenloop van
    het Recht op uitkering WW een recht op grond-van-de-wia is" niet is vast te stellen; 'uitkeringsduur verstreken
    art. 20 lid 1 onder b' gold niet, want niet geldt: het onderwerp de uitkeringsduur verstreken heeft; 'nieuw recht
    boven de herlevingsgrens art. 21 lid 2 onder a' gold niet, want niet geldt: zijn herlevingsgrens (onbekend) gevuld
    is; 'herlevingstermijn verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold niet, want de voorwaarde "de laatste
    van (zijn herlevingsdatum) en (de einddag zelfstandig werk) is later dan zijn herlevingstermijn" niet is vast te
    stellen. Grondslag: …"
  - "G1 heeft indicatie kan herleefd worden volgens de regel 'indicatie kan herleefd worden art. 21 lid 1', omdat zijn
    code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Hoogte inkomsten uit
    verzekerde arbeid of zijn code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan
    Urenverlies van minder dan 5 of zijn code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is
    gelijk aan Omvang zelfstandig werk of zijn code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk)
    is gelijk aan Omvang niet verzekeringsplichtig werk of zijn code reden beëindiging recht op uitkering ww (Omvang
    zelfstandig werk) is gelijk aan Niet beschikbaar voor arbeid of zijn code reden beëindiging recht op uitkering ww
    (Omvang zelfstandig werk) is gelijk aan Omvang zelfstandig werk na startperiode of zijn code reden beëindiging
    recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Mogelijke uitzetting ogv vreemdelingenwet of zijn
    code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Detentie of zijn code reden
    beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Verblijf buitenland anders dan vakantie
    of zijn code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Voortvluchtigheid of
    zijn code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Vakantie of zijn code
    reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Werkstaking of uitsluiting of zijn
    code reden beëindiging recht op uitkering ww (Omvang zelfstandig werk) is gelijk aan Uitreiziger of zijn code
    reden beeindiging recht op uitkering (onbekend) is gelijk aan Gemoedsbezwaardheid; 'intrekking binnen de
    bezwaartermijn art. 22a lid 1' gold niet, want de voorwaarde "de aanvraag om uitkering van de aanvraag uitkering
    van het recht van de Eindigingsgrond is eerder dan de bezwaartermijn bij intrekking van het recht van de
    Eindigingsgrond" niet is vast te stellen. Grondslag: …"
  - "R1 heeft indicatie komt in aanmerking voor herleving volgens de regel 'indicatie komt in aanmerking voor
    herleving art. 21 lid 1', omdat zijn beëindigingsdatum (01-06-2025) gevuld is en het aantal eindigingsgronden van
    het RechtOpUitkering die aan de volgende voorwaarde voldoen: hij heeft de indicatie kan herleefd worden (onbekend)
    is groter dan 0 en het aantal eindigingsgronden van het RechtOpUitkering die aan de volgende voorwaarde voldoen:
    hij heeft geen indicatie kan herleefd worden (onbekend) is gelijk aan 0; 'aanmerking na een uitsluitingsgrond art.
    21 lid 1' gold niet, want de voorwaarde "het aantal uitsluitingsgronden van het RechtOpUitkering die aan de
    volgende voorwaarde voldoen: zijn beëindigingsdatum is gevuld is groter dan 0" niet is vast te stellen. Grondslag:
    …"
- **inkomen**
  - "R1 heeft indicatie herleving recht op uitkering volgens de regel 'herleving art. 21 lid 1', omdat zijn
    beëindigingsdatum (01-04-2026) gevuld is en zijn herlevingsdatum (01-08-2026) gevuld is; 'nieuw recht op grond van
    de WIA art. 21 lid 2 onder b' gold niet, want de voorwaarde "ten minste één van de rechten van de samenloop van
    het Recht op uitkering WW een recht op grond-van-de-wia is" niet is vast te stellen; 'uitkeringsduur verstreken
    art. 20 lid 1 onder b' gold niet, want niet geldt: het onderwerp de uitkeringsduur verstreken heeft; 'nieuw recht
    boven de herlevingsgrens art. 21 lid 2 onder a' gold niet, want de voorwaarde "(de maximale waarde van het
    maandloon van alle rechten van de samenloop van het Recht op uitkering WW die aan de volgende voorwaarde voldoen:
    zijn beëindigingsdatum is leeg) is groter dan zijn herlevingsgrens" niet is vast te stellen; 'herlevingstermijn
    verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold niet, want niet geldt: zijn herlevingstermijn (onbekend)
    gevuld is. Grondslag: …"
  - "G1 heeft indicatie kan herleefd worden volgens de regel 'indicatie kan herleefd worden art. 21 lid 1', omdat zijn
    code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Hoogte
    inkomsten uit verzekerde arbeid of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit
    verzekerde arbeid) is gelijk aan Urenverlies van minder dan 5 of zijn code reden beëindiging recht op uitkering ww
    (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Omvang zelfstandig werk of zijn code reden beëindiging
    recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Omvang niet verzekeringsplichtig werk
    of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Niet
    beschikbaar voor arbeid of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde
    arbeid) is gelijk aan Omvang zelfstandig werk na startperiode of zijn code reden beëindiging recht op uitkering ww
    (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Mogelijke uitzetting ogv vreemdelingenwet of zijn code
    reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Detentie of zijn
    code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Verblijf
    buitenland anders dan vakantie of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit
    verzekerde arbeid) is gelijk aan Voortvluchtigheid of zijn code reden beëindiging recht op uitkering ww (Hoogte
    inkomsten uit verzekerde arbeid) is gelijk aan Vakantie of zijn code reden beëindiging recht op uitkering ww
    (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Werkstaking of uitsluiting of zijn code reden beëindiging
    recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Uitreiziger of zijn code reden
    beeindiging recht op uitkering (onbekend) is gelijk aan Gemoedsbezwaardheid; 'intrekking binnen de bezwaartermijn
    art. 22a lid 1' gold niet, want de voorwaarde "de aanvraag om uitkering van de aanvraag uitkering van het recht
    van de Eindigingsgrond is eerder dan de bezwaartermijn bij intrekking van het recht van de Eindigingsgrond" niet
    is vast te stellen. Grondslag: …"
  - "R1 heeft indicatie komt in aanmerking voor herleving volgens de regel 'indicatie komt in aanmerking voor
    herleving art. 21 lid 1', omdat zijn beëindigingsdatum (01-04-2026) gevuld is en het aantal eindigingsgronden van
    het RechtOpUitkering die aan de volgende voorwaarde voldoen: hij heeft de indicatie kan herleefd worden (onbekend)
    is groter dan 0 en het aantal eindigingsgronden van het RechtOpUitkering die aan de volgende voorwaarde voldoen:
    hij heeft geen indicatie kan herleefd worden (onbekend) is gelijk aan 0; 'aanmerking na een uitsluitingsgrond art.
    21 lid 1' gold niet, want de voorwaarde "het aantal uitsluitingsgronden van het RechtOpUitkering die aan de
    volgende voorwaarde voldoen: zijn beëindigingsdatum is gevuld is groter dan 0" niet is vast te stellen. Grondslag:
    …"
- **intrekking**
  - "R1 heeft indicatie herleving recht op uitkering volgens de regel 'herleving art. 21 lid 1', omdat zijn
    beëindigingsdatum (01-03-2026) gevuld is en zijn herlevingsdatum (01-06-2026) gevuld is; 'nieuw recht op grond van
    de WIA art. 21 lid 2 onder b' gold niet, want de voorwaarde "ten minste één van de rechten van de samenloop van
    het Recht op uitkering WW een recht op grond-van-de-wia is" niet is vast te stellen; 'uitkeringsduur verstreken
    art. 20 lid 1 onder b' gold niet, want niet geldt: het onderwerp de uitkeringsduur verstreken heeft; 'nieuw recht
    boven de herlevingsgrens art. 21 lid 2 onder a' gold niet, want niet geldt: zijn herlevingsgrens (onbekend) gevuld
    is; 'herlevingstermijn verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold niet, want niet geldt: zijn
    herlevingstermijn (onbekend) gevuld is. Grondslag: …"
  - "G1 heeft indicatie kan herleefd worden volgens 'intrekking binnen de bezwaartermijn art. 22a lid 1', omdat de
    aanvraag om uitkering van de aanvraag uitkering van het recht van de Eindigingsgrond (15-06-2026) ligt vóór de
    bezwaartermijn bij intrekking van het recht van de Eindigingsgrond (28-07-2026) en zijn code reden beëindiging
    recht op uitkering ww (Geen inkomstenopgave ingeleverd) is gelijk aan Geen inkomstenopgave ingeleverd of zijn code
    reden beëindiging recht op uitkering ww (Geen inkomstenopgave ingeleverd) is gelijk aan Verplichting
    informatieverstrekking niet nagekomen of zijn code reden beëindiging recht op uitkering ww (Geen inkomstenopgave
    ingeleverd) is gelijk aan Onvoldoende medewerking re-integratie; verdrongen: de regel 'indicatie kan herleefd
    worden art. 21 lid 1'. Grondslag: …"
  - "R1 heeft indicatie komt in aanmerking voor herleving volgens de regel 'indicatie komt in aanmerking voor
    herleving art. 21 lid 1', omdat zijn beëindigingsdatum (01-03-2026) gevuld is en het aantal eindigingsgronden van
    het RechtOpUitkering die aan de volgende voorwaarde voldoen: hij heeft de indicatie kan herleefd worden (onbekend)
    is groter dan 0 en het aantal eindigingsgronden van het RechtOpUitkering die aan de volgende voorwaarde voldoen:
    hij heeft geen indicatie kan herleefd worden (onbekend) is gelijk aan 0; 'aanmerking na een uitsluitingsgrond art.
    21 lid 1' gold niet, want de voorwaarde "het aantal uitsluitingsgronden van het RechtOpUitkering die aan de
    volgende voorwaarde voldoen: zijn beëindigingsdatum is gevuld is groter dan 0" niet is vast te stellen. Grondslag:
    …"
- **restant**
  - "R1 heeft geen indicatie herleving recht op uitkering, want geen variant besliste; 'nieuw recht op grond van de
    WIA art. 21 lid 2 onder b' gold niet, want niet geldt: zijn herlevingsdatum (onbekend) gevuld is; 'uitkeringsduur
    verstreken art. 20 lid 1 onder b' gold niet, want niet geldt: zijn herlevingsdatum (onbekend) gevuld is; 'nieuw
    recht boven de herlevingsgrens art. 21 lid 2 onder a' gold niet, want niet geldt: zijn herlevingsdatum (onbekend)
    gevuld is; 'herlevingstermijn verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold niet, want niet geldt: zijn
    herlevingsdatum (onbekend) gevuld is; de regel 'herleving art. 21 lid 1' gold niet, want niet geldt: zijn
    herlevingsdatum (onbekend) gevuld is"
  - "R2 heeft geen indicatie herleving recht op uitkering, want geen variant besliste; 'nieuw recht op grond van de
    WIA art. 21 lid 2 onder b' gold niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is;
    'uitkeringsduur verstreken art. 20 lid 1 onder b' gold niet, want niet geldt: zijn beëindigingsdatum (onbekend)
    gevuld is; 'nieuw recht boven de herlevingsgrens art. 21 lid 2 onder a' gold niet, want niet geldt: zijn
    beëindigingsdatum (onbekend) gevuld is; 'herlevingstermijn verstreken art. 21 lid 3 en 4 en art. 8 lid 3' gold
    niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is; de regel 'herleving art. 21 lid 1' gold niet,
    want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is"
  - "G1 heeft indicatie kan herleefd worden volgens de regel 'indicatie kan herleefd worden art. 21 lid 1', omdat zijn
    code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Hoogte
    inkomsten uit verzekerde arbeid of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit
    verzekerde arbeid) is gelijk aan Urenverlies van minder dan 5 of zijn code reden beëindiging recht op uitkering ww
    (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Omvang zelfstandig werk of zijn code reden beëindiging
    recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Omvang niet verzekeringsplichtig werk
    of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Niet
    beschikbaar voor arbeid of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde
    arbeid) is gelijk aan Omvang zelfstandig werk na startperiode of zijn code reden beëindiging recht op uitkering ww
    (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Mogelijke uitzetting ogv vreemdelingenwet of zijn code
    reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Detentie of zijn
    code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Verblijf
    buitenland anders dan vakantie of zijn code reden beëindiging recht op uitkering ww (Hoogte inkomsten uit
    verzekerde arbeid) is gelijk aan Voortvluchtigheid of zijn code reden beëindiging recht op uitkering ww (Hoogte
    inkomsten uit verzekerde arbeid) is gelijk aan Vakantie of zijn code reden beëindiging recht op uitkering ww
    (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Werkstaking of uitsluiting of zijn code reden beëindiging
    recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Uitreiziger of zijn code reden
    beeindiging recht op uitkering (onbekend) is gelijk aan Gemoedsbezwaardheid; 'intrekking binnen de bezwaartermijn
    art. 22a lid 1' gold niet, want de voorwaarde "de aanvraag om uitkering van de aanvraag uitkering van het recht
    van de Eindigingsgrond is eerder dan de bezwaartermijn bij intrekking van het recht van de Eindigingsgrond" niet
    is vast te stellen. Grondslag: …"
  - "R1 heeft indicatie komt in aanmerking voor herleving volgens de regel 'indicatie komt in aanmerking voor
    herleving art. 21 lid 1', omdat zijn beëindigingsdatum (01-07-2025) gevuld is en het aantal eindigingsgronden van
    het RechtOpUitkering die aan de volgende voorwaarde voldoen: hij heeft de indicatie kan herleefd worden (onbekend)
    is groter dan 0 en het aantal eindigingsgronden van het RechtOpUitkering die aan de volgende voorwaarde voldoen:
    hij heeft geen indicatie kan herleefd worden (onbekend) is gelijk aan 0; 'aanmerking na een uitsluitingsgrond art.
    21 lid 1' gold niet, want de voorwaarde "het aantal uitsluitingsgronden van het RechtOpUitkering die aan de
    volgende voorwaarde voldoen: zijn beëindigingsdatum is gevuld is groter dan 0" niet is vast te stellen. Grondslag:
    …"
  - "R2 heeft geen indicatie komt in aanmerking voor herleving, want geen variant besliste; 'aanmerking na een
    uitsluitingsgrond art. 21 lid 1' gold niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is; de regel
    'indicatie komt in aanmerking voor herleving art. 21 lid 1' gold niet, want niet geldt: zijn beëindigingsdatum
    (onbekend) gevuld is"

Nagelezen tegen `v2/` (in `bewijs.md`): de beslissers en de volgorde kloppen met de bronnen (het later geschreven blok
hoger, de restrictieve `Uitzondering op` met de voorwaarden van de basis ervoor), en elke "gold niet, want" noemt de
eerste voorwaarde die in de eindtoestand niet geldt, of zegt dat ze niet vast te stellen is.

### make gates (e)

`TAG=explanation-defaults CLUSTER=explanation-defaults make gates` op `c43c6dc3`
(`werk/defaults-decider/tc-gates-1.log`): groen, 7 stappen in 9m15s.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit; de versies zijn gelijk
gates: 2 reactor    GROEN   5m53s  testgevallen per module 1636/145/340/53
gates: 3 twins      GROEN      9s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m10s  231 goldens, 610 cases (pin 231)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     39s  decision-service:explanation-defaults
gates: 7 kind-smoke GROEN   1m22s  H2 PASS, H3 PASS, H4 PASS
```

De reactor telt 145 toolchain-tests (was 143, `ExplanationDefaultsTest`) en 340 dienst-tests (was 334,
`ExplanationDefaultsServiceTest`). Het kind-cluster is na de run weg; de images `decision-service:explanation-defaults`,
`-h4a` en `-h4b` staan nog in docker.

### Wat open bleef

- **Een samengestelde stapel zonder besluit.** `decisionsOf` geeft geen record waar niemand besliste, dus ook niet
  welke varianten de stapel van het object samenstellen. Komt de stapel uit één type, dan zijn dat de groepen van dat type
  in documentvolgorde, en noemt de zin ze. Schrijven twee typen van de keten het doel (ch. 7.10: een eigen basis vervangt
  de geërfde, een gewone regel wordt basis), dan noemt de zin geen varianten in plaats van een geërfde basis te noemen die
  niet meedeed. In v2 raakt dat de herlevingstermijn van een RechtOpUitkeringWwBijOntslag in inkomen, intrekking en
  restant. Om ook daar de varianten te noemen, is een uchar-kaart nodig: bijvoorbeeld een record zonder beslisser en zonder
  conflict, met de `rootChains`, voor elke stapel op elk object.
- **Het lidwoord van een bezittelijk kenmerk.** "R1 heeft indicatie herleving recht op uitkering": het IR draagt de
  doeltekst van een default niet, en daarmee het lidwoord niet dat een kenmerktoekenning uit haar regeltekst haalt.
- **De scenario-runner leest het document twee keer.** De build geeft de groepen die uchars lader naast het model teruggaf
  niet uit; een uitgang daarvoor op `DroolsBackend.Build` zou de tweede lading overbodig maken.
- **De geldigheid van een groep.** Een groep met een geldigheidsvenster dat de rekendatum niet dekt, staat in de zin
  "geen variant besliste" met de redenen van haar varianten; de uitleg leest het venster niet. Alle groepen van v2 zijn
  `geldig altijd`.
- **Uit de kaart, niet in deze kaart:** graaf en dekking tonen de groepen nog niet; leeg als 0 in een berekening rekent de
  Verbalisator niet na.

### Vondsten buiten de kaart

- `Verbaliser.sourceReference(ResolvedRule)` geeft altijd null, terwijl het model de `// Bron:`-annotaties van een regel
  draagt (`ResolvedRule.statuteAnnotations`, op de draad `statuteAnnotations`, ook in uchars goldens); de klasse-javadoc
  zegt nog dat de grammatica ze weggooit. Een kenmerktoekenning krijgt daardoor geen Grondslag, een default wel. Niet
  veranderd: de zinnen van de kenmerktoekenningen blijven letterlijk gelijk (acceptatie (c)).

### Aanvulling T1, 2026-10-21

Besluit van de eigenaar (2026-10-21): de Grondslag bij een kenmerktoekenning wordt gefixt, ook al veranderen daarmee
uitlegzinnen; deel T1 van `werk/defaults-decider/fable-advies.md` §6. Commit `0f7d874b`, op de pin `36d57244`.

- **Wat er veranderde.** `Verbaliser.sourceReference(ResolvedRule)` gaf altijd null; nu leest hij
  `ResolvedRule.statuteAnnotations` en geeft de niet-lege annotaties in bronvolgorde, verbonden met "; ", de vorm waarin
  uchars frontend de annotaties van een defaults-blok in `Default.source` schrijft (`RuleChecker.annotationText`). De
  zin van een kenmerktoekenning met een `// Bron:` eindigt zo met ". Grondslag: <tekst>", toegekend en niet toegekend.
  Een lege annotatie (`//Bron:`) valt weg: het model van de frontend houdt haar als lege tekst, de emitter schrijft haar
  niet op de draad (WF-082; gelezen in `RuleChecker.statuteAnnotations` en `IrEmitter.annotations`), en zo geven de
  dienst uit bronnen en die uit een IR-document dezelfde zin. De klasse-javadoc over de grammatica die commentaar
  weggooit is vervangen door een korte juiste alinea. Een gevolg in de defaults-zinnen, zonder dat hun code veranderde:
  `groundOf` riep `sourceReference` al aan voor een beslisser die een gewone regel is, de basis van een subtype (ch.
  7.10), dus die zin krijgt nu ook de Grondslag van die regel; alleen de javadoc van `groundOf` ("een regel draagt in
  het model geen bron") is bijgewerkt. `decisionsOf`-afhandeling en de defaults-zinnen verder ongemoeid.
- **Rood eerst.** `ExplanationStatuteAnnotationTest` (scenario-runner, twee tests) en
  `ExplanationStatuteAnnotationServiceTest` (dienst uit bronnen en uit een IR-document, vier tests), met een eigen model:
  `meerderjarigheid` met één annotatie, `recht op strandgoed` met twee, `aanmelding` met een lege, voor een jutter die
  alles krijgt en een die niets krijgt; en het overervingsmodel van `ExplanationDefaultsTest` met een `// Bron:` op de
  gewone regel van het subtype. Op `0c84d34e` faalden alle zes met alleen de ontbrekende Grondslag
  (`werk/defaults-decider/tc-logs/t1-red-1.log`); na de fix groen. Vóór en na:
  - "J2 heeft geen recht op strandgoed, want niet geldt: het onderwerp ingezetene is"
  - "J2 heeft geen recht op strandgoed, want niet geldt: het onderwerp ingezetene is. Grondslag: Jutterswet art. 4 lid
    1; Jutterswet art. 4 lid 3"
  - "W1 heeft als uitkering 200 volgens de regel 'uitkering van een wrakrecht', omdat het onderwerp gezonken is;
    'betwiste uitkering' gold niet, want niet geldt: het onderwerp erkend is", na met ". Grondslag: Jutterswet art. 9"
- **Aangepaste tests: geen.** Geen bestaande test pinde een hele uitlegzin van een regel met een `// Bron:`; de enige
  annotaties in de bestaande uitlegtests staan op defaults-blokken (`ExplanationDefaults*`), en die zinnen bleven gelijk.
- **Suites.** `mvn -B -o verify` groen (`werk/defaults-decider/tc-logs/t1-verify-1.log`): 1636/147/344/53, was
  1636/145/340/53; skip-pin PASS. `make gates` en de v2-meting niet gedraaid: die volgen na de pin-bump van
  `undecided-stack-record`. Op v2 zullen de zinnen van kenmerktoekenningen met een `// Bron:` de Grondslag krijgen; dat
  is nog niet gemeten.

### Aanvulling, pin `26a2fb85` (2026-10-21)

De laatste stap van de kaart: de pin op uchar-kaart `undecided-stack-record`, de uitleg bij een stapel waarop niemand
besliste, het bewijs op v2 en `make gates`. Tak `card/explanation-defaults`, ongemerged en ongepusht.

- `30800c0b` de pin op uchar `26a2fb85`.
- `80644b32` de uitleg zonder besluit uit de `rootChains`, met de aangepaste overervingstests.
- deze aanvulling, in de commit na `80644b32`.

#### De pin

uchar `36d57244` → `26a2fb85`, main, de landing van uchar-kaart `undecided-stack-record`
(`backlog/done/20261021-undecided-stack-record.md` in uchar): `decisionsOf` geeft voor elk object van de sessie en elke
stapel van zijn type een record, ook zonder beslisser en zonder conflict, en dan met de `rootChains` van de stapel; de
lowering en de semantiek bleven gelijk. `make check-pin` groen.

Wat de pin alleen verschuift (`werk/defaults-decider/tc-logs/verify-pin-2.log`, en `tc-logs/red-4.log` met
`-Dmaven.test.failure.ignore=true`, zodat ook de dienst draait): de uchar-backend 1636 tests, groen en evenveel als op
`36d57244`; de toolchain 147 tests met 2 fouten, de dienst 344 met 5, Quarkus 53 groen. Alle zeven zijn dezelfde
`NullPointerException` in `Verbaliser$DefaultsExplanation.decided` (`d.decider().pointer()`): `ExplanationDefaultsTest`
(2), `ExplanationDefaultsServiceTest` (de penning en de overerving, uit bronnen en uit een document: 4) en
`PlaygroundRoleFactTypeTest.every_scenario_golden`, dat elke scenario-golden van uchar met uitleg door de playground
speelt. De reden: tot de pin betekende een ontbrekend record "niemand besliste", sinds de pin is dat een record zonder
beslisser, en de Verbalisator las daar een beslisser. De replay-poort en de tweelingen vragen geen uitleg
(`scripts/check-service-replay.sh` en `scripts/check-twins.sh` noemen `explanation` niet) en uchar veranderde geen
uitkomst (gate 12 van uchar speelt 261, ongewijzigd); in `make gates` hieronder staan ze gelijk aan de vorige run.

#### Wat er veranderde

`Verbaliser.DefaultsExplanation` (`toolchain/.../engine/Verbaliser.java`):

- **Elke stapel komt uit `decisionsOf`.** `sentences` geeft per record van het object één zin: een conflict, een besluit,
  of, zonder beslisser en zonder conflict, "geen variant besliste". Het document geeft alleen de volgorde van de zinnen,
  zoals voorheen die van de eerste groep die het doel op de typeketen van het object schrijft. Nieuw en luid: een groep
  op de typeketen van het object zonder record over haar doel ("de motor gaf geen beslissing over '…' van …, en het
  document heeft voor … een defaults-groep op dat lid: elk object van de sessie heeft er één per stapel van zijn type").
  De twee bestaande controles blijven (een record over een lid zonder groep, twee records over één lid); de eerste zegt
  nu "de motor gaf een beslissing over" in plaats van "de motor besliste over", want een record is geen besluit meer.
- **De zin zonder besluit.** `undecided(d)` neemt de varianten en hun volgorde uit `d.rootChains()`, legt elke pointer op
  het document zoals bij een besluit (`variant`, `requireTarget`) en geeft per variant, de leden van een default vóór zijn
  eigen inhoud (ch. 7.9), "gold niet, want …" of "gaf geen waarde", met de eigen evaluatie van voorheen. Een stapel
  zonder varianten faalt luid. Weg: `composed(…)`, de afleiding van de stapel uit de groepen van het document en de zin
  "welke varianten de stapel van een … samenstellen, geeft de motor zonder beslissing niet".
- De javadoc van de klasse en van `verbalise(…, document, decisions)` zegt dat; drie ongebruikte imports zijn weg.

Dienst, scenario-runner en `CaseSession` bleven gelijk: ze gaven de hele lijst van `decisionsOf` al door.

**Aangepaste tests, met reden.** `ExplanationDefaultsTest.INHERITANCE_EXPECTED` en
`ExplanationDefaultsServiceTest.INHERITANCE_EXPECTED` pinden voor W3, een erkend Wrakrecht waarop niemand beslist, de
zin "W3 heeft geen uitkering, want geen variant besliste; welke varianten de stapel van een Wrakrecht samenstellen, geeft
de motor zonder beslissing niet". Die zin vervalt bewust (besluit van de eigenaar: zeg met wat het doel houdt, en per
variant "gold niet, want …"). Nu: "W3 heeft geen uitkering, want geen variant besliste; 'betwiste uitkering' gold niet,
want niet geldt: het onderwerp betwist is; de regel 'uitkering van een wrakrecht' gold niet, want niet geldt: het
onderwerp gezonken is": de stapel van een Wrakrecht, zonder de geërfde basis 'uitkering', die niet meedoet. De javadoc
bij de situatie zegt dat nu. Geen andere verwachting veranderde: de zinnen zonder besluit van J4, J5 en J6 (een stapel
uit één type) komen letterlijk zo uit het record.

**Rood eerst.** Op de pin-commit met alleen de nieuwe W3-verwachting (`tc-logs/red-4.log`) faalden de drie routes: de
scenario-runner (2), de dienst uit bronnen en uit een IR-document (4), alle met de `NullPointerException` hierboven, en
`PlaygroundRoleFactTypeTest`. Na de code groen: `mvn -B -o verify` (`tc-logs/verify-build-3.log`) 1636/147/344/53, gelijk
aan de telling na T1, skip-pin PASS in de drie eigen modules (vloeren 120, 235 en 43); `PlaygroundRoleFactTypeTest` speelt
elke scenario-golden van uchar zonder dat de nieuwe controle op een ontbrekend record afgaat.

#### Bewijs op v2

`werk/defaults-decider/bewijs.md`, sectie "Na T1 en undecided-stack-record", meting `na2/` (toolchain `80644b32`, uchar
`26a2fb85`, 2026-10-21 13:53):

- Het antwoord buiten de uitleg is in alle vijf verzoeken gelijk in `voor/`, `na/` en `na2/`.
- Alle 81 oude zinnen (13, 14, 20, 13, 21) staan terug, in dezelfde volgorde; 30 letterlijk, en 51 letterlijk met
  ". Grondslag: …" erachter (8, 8, 14, 8 en 13 per verzoek): de kenmerktoekenningen waarvan de regel een `// Bron:` draagt
  (T1).
- Van `na/` naar `na2/` veranderden alleen die 51 en de vier zinnen over de herlevingstermijn hieronder; per verzoek
  evenveel zinnen (28, 31, 36, 28, 48) op dezelfde plaatsen. De 17 zinnen over de drie herlevingskenmerken zijn
  letterlijk die van `na/`; `bewijs.md` geeft ze opnieuw letterlijk.
- De vier zinnen over de herlevingstermijn van een RechtOpUitkeringWwBijOntslag waarop niemand besliste (een stapel uit
  de basis op Recht op uitkering WW en de vijf blokken van `hww-13` op het subtype, ch. 7.10). Vóór, in `na/` vier keer
  op het label na gelijk:
  "R1 heeft geen herlevingstermijn, want geen variant besliste; welke varianten de stapel van een
  RechtOpUitkeringWwBijOntslag samenstellen, geeft de motor zonder beslissing niet"
  Na, letterlijk uit `na2/`:
  - **inkomen, R1**: "R1 heeft geen herlevingstermijn, want geen variant besliste; 'resterende uitkeringsduur art. 21
    lid 4' gold niet, want de voorwaarde "de aanvraag uitkering van het RechtOpUitkeringWwBijOntslag heeft de
    werkzaamheden-als-politiek ambtsdrager" niet is vast te stellen; 'halve maand na de startperiode art. 8 lid 3'
    (uitzondering op 'herkrijgingstermijn na de startperiode art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3 en 5')
    gold niet, want de voorwaarde "het aantal eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende
    voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk na
    startperiode' is groter dan 0" niet is vast te stellen; 'herkrijgingstermijn na de startperiode art. 8 lid 3' gold
    niet, want de voorwaarde "het aantal eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende
    voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk na
    startperiode' is groter dan 0" niet is vast te stellen; 'halve maand na zelfstandig werk art. 8 lid 3' (uitzondering
    op 'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3 en 5') gold niet, want
    niet geldt: de uitkeringsduur van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag (onbekend) gevuld is;
    'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' gold niet, want niet geldt: de uitkeringsduur van de
    uitkeringsduur van het RechtOpUitkeringWwBijOntslag (onbekend) gevuld is; de regel 'herlevingstermijn art. 21 lid 3
    en 5' gold niet, want de voorwaarde "het aantal eindigingsgronden van het Recht op uitkering WW die aan de volgende
    voorwaarde voldoen: hij heeft de zesmaandentermijn is groter dan 0" niet is vast te stellen"
  - **intrekking, R1**: "R1 heeft geen herlevingstermijn, want geen variant besliste; 'resterende uitkeringsduur art. 21
    lid 4' gold niet, want de voorwaarde "de aanvraag uitkering van het RechtOpUitkeringWwBijOntslag heeft de
    werkzaamheden-als-politiek ambtsdrager" niet is vast te stellen; 'halve maand na de startperiode art. 8 lid 3'
    (uitzondering op 'herkrijgingstermijn na de startperiode art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3 en 5')
    gold niet, want de voorwaarde "het aantal eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende
    voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk na
    startperiode' is groter dan 0" niet is vast te stellen; 'herkrijgingstermijn na de startperiode art. 8 lid 3' gold
    niet, want de voorwaarde "het aantal eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende
    voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk na
    startperiode' is groter dan 0" niet is vast te stellen; 'halve maand na zelfstandig werk art. 8 lid 3' (uitzondering
    op 'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3 en 5') gold niet, want
    niet geldt: de uitkeringsduur van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag (onbekend) gevuld is;
    'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' gold niet, want niet geldt: de uitkeringsduur van de
    uitkeringsduur van het RechtOpUitkeringWwBijOntslag (onbekend) gevuld is; de regel 'herlevingstermijn art. 21 lid 3
    en 5' gold niet, want de voorwaarde "het aantal eindigingsgronden van het Recht op uitkering WW die aan de volgende
    voorwaarde voldoen: hij heeft de zesmaandentermijn is groter dan 0" niet is vast te stellen"
  - **restant, R1**: "R1 heeft geen herlevingstermijn, want geen variant besliste; 'resterende uitkeringsduur art. 21
    lid 4' gold niet, want de voorwaarde "de aanvraag uitkering van het RechtOpUitkeringWwBijOntslag heeft de
    werkzaamheden-als-politiek ambtsdrager" niet is vast te stellen; 'halve maand na de startperiode art. 8 lid 3'
    (uitzondering op 'herkrijgingstermijn na de startperiode art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3 en 5')
    gold niet, want de voorwaarde "het aantal eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende
    voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk na
    startperiode' is groter dan 0" niet is vast te stellen; 'herkrijgingstermijn na de startperiode art. 8 lid 3' gold
    niet, want de voorwaarde "het aantal eindigingsgronden van het RechtOpUitkeringWwBijOntslag die aan de volgende
    voorwaarde voldoen: zijn code reden beëindiging recht op uitkering ww is gelijk aan 'Omvang zelfstandig werk na
    startperiode' is groter dan 0" niet is vast te stellen; 'halve maand na zelfstandig werk art. 8 lid 3' (uitzondering
    op 'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3 en 5') gold niet, want
    niet geldt: de uitkeringsduur van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag naar beneden afgerond op 0
    decimalen (12 mnd) is kleiner dan de uitkeringsduur van de uitkeringsduur van het RechtOpUitkeringWwBijOntslag (12,0
    mnd); 'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' gaf geen waarde; de regel 'herlevingstermijn art. 21
    lid 3 en 5' gold niet, want de voorwaarde "het aantal eindigingsgronden van het Recht op uitkering WW die aan de
    volgende voorwaarde voldoen: hij heeft de zesmaandentermijn is groter dan 0" niet is vast te stellen"
  - **restant, R2**: "R2 heeft geen herlevingstermijn, want geen variant besliste; 'resterende uitkeringsduur art. 21
    lid 4' gold niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is; 'halve maand na de startperiode art.
    8 lid 3' (uitzondering op 'herkrijgingstermijn na de startperiode art. 8 lid 3' op 'herlevingstermijn art. 21 lid 3
    en 5') gold niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is; 'herkrijgingstermijn na de
    startperiode art. 8 lid 3' gold niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is; 'halve maand na
    zelfstandig werk art. 8 lid 3' (uitzondering op 'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' op
    'herlevingstermijn art. 21 lid 3 en 5') gold niet, want niet geldt: zijn beëindigingsdatum (onbekend) gevuld is;
    'herkrijgingstermijn na zelfstandig werk art. 8 lid 3' gold niet, want niet geldt: zijn beëindigingsdatum (onbekend)
    gevuld is; de regel 'herlevingstermijn art. 21 lid 3 en 5' gold niet, want niet geldt: zijn beëindigingsdatum
    (onbekend) gevuld is"
- Nagelezen tegen `v2/`: dezelfde varianten in dezelfde volgorde als de beslissende zinnen over die stapel in detentie en
  herkrijging, de geërfde basis als laatste. In restant R1 gold `herkrijgingstermijn na zelfstandig werk`, maar gaf ze
  geen waarde: haar herkrijgingsmaand leest een grond met code Omvang zelfstandig werk, en de grond van restant heeft
  een andere code.

#### make gates

`TAG=explanation-defaults CLUSTER=explanation-defaults make gates` op `80644b32`
(`werk/defaults-decider/tc-gates-2.log`): groen, 7 stappen in 9m44s.

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit; de versies zijn gelijk
gates: 2 reactor    GROEN   6m11s  testgevallen per module 1636/147/344/53
gates: 3 twins      GROEN      9s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m14s  231 goldens, 610 cases (pin 231)
gates: 5 manifest   GROEN      1s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     49s  decision-service:explanation-defaults
gates: 7 kind-smoke GROEN   1m20s  H2 PASS, H3 PASS, H4 PASS
```

Gelijk aan de run op `c43c6dc3` (`tc-gates-1.log`) op de tellingen na die T1 en deze kaart verschoven: de reactor
1636/147/344/53 (was 1636/145/340/53; de zes tests van T1 erbij), de tweelingen, de replay-poort (231 goldens, 610
cases, pin 231) en het manifest (29 ok, 3 uitgesloten) zijn gelijk. Het kind-cluster is na de run weg; de images
`decision-service:explanation-defaults`, `-h4a` en `-h4b` staan nog in docker.

#### Wat open bleef

- **"Niet vast te stellen".** De reden waarom een variant niet besliste, komt nog uit de eigen evaluatie van de
  Verbalisator; tellingen, "de laatste van", kwantoren en vergelijkingen over rollen zijn daar niet vast te stellen, ook
  in de vier herlevingstermijnzinnen. De lezingen uit uchar zijn de uchar-kaarten `fixpoint-readings` (U2) en
  `ref-trace-and-readings-gate` (U3), in `backlog/new/` van uchar.
- **Het lidwoord van een bezittelijk kenmerk**, **de scenario-runner leest het document twee keer** en **de geldigheid
  van een groep**: ongewijzigd, zoals in "Wat open bleef" hierboven.
- **Uit de kaart, niet in deze kaart:** graaf en dekking tonen de groepen nog niet; leeg als 0 in een berekening rekent de
  Verbalisator niet na.

Het punt "Een samengestelde stapel zonder besluit" uit "Wat open bleef" hierboven is opgelost.

Geland 2026-10-21 door de hoofdsessie: fast-forward naar `427d1966` (`make gates` groen op die stand, 9m44s, `TAG/CLUSTER=explanation-defaults`). In dezelfde commit de vervolgkaart `explanation-from-readings` (T2 uit het Fable-advies: de uitleg rendert uit de lezingen van de motor) in `backlog/new/`; zij volgt op de uchar-kaart `fixpoint-readings`.
