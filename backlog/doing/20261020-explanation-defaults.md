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
