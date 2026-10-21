# explanation-fold-members: de uitleg noemt de leden van een kwantorvouw die de uitkomst dragen

*Vastgelegd door de hoofdsessie op 2026-10-21, open punt van `explanation-from-readings`. Besluit van de eigenaar
2026-10-21: de beslissende leden, met het aantal (keuze uit drie: beslissende leden, alleen aantallen, alle leden met
oordeel). Tweede stap: de pin-bump naar de uchar-kaart `fixpoint-inconsistency-order`.*

## Aanleiding

Sinds `explanation-from-readings` staat een kwantorvouw ("alle vondsten van de Jutter waardevol zijn", "ten minste één
van de eindigingsgronden … de vrijstelling heeft") in de uitleg als `aan de voorwaarde "…" is voldaan` of `… is niet
voldaan`, met het oordeel van de motor (`Verbaliser`, r. ±859 en ±877). Welk lid de uitkomst gaf, staat er niet, terwijl
de lezing het levert: de knoop van de vouw draagt zijn `ConditionReading.Contributor`s, de leden van het bereik die door
het filter komen, elk met het oordeel van de matrix (of een fout). In de vijf v2-verzoeken komt de vorm 10 keer voor
(`werk/defaults-decider/na3/`).

## Doel

Na de bestaande zin van de vouw een dubbele punt en de leden die de uitkomst dragen, met het aantal; m is het aantal
leden (contributors), k het aantal waarvoor de matrix geldt:

| kwantor | voldaan | niet voldaan |
|---|---|---|
| `all` | "voor alle m" | "niet voor <de leden waarvoor zij niet geldt> (m − k van de m)" |
| `none` | "voor geen van de m" | "wel voor <de leden waarvoor zij geldt> (k van de m)" |
| `atLeast`, `atMost`, `exactly` n | "voor k van de m (<de leden waarvoor zij geldt>)" | idem |

Bij k = 0 in een telling: "voor geen van de m (<alle leden>)". Bij m = 0: "(geen enkel lid)". De leden staan bij hun
label, zoals de uitleg een onderwerp al noemt (een gemaakt object zoals de uitleg het noemt), in code-unit-volgorde van
dat label, met "en" voor het laatste; boven vijf de eerste vijf en "en nog k". Het oordeel per lid komt uit de lezing,
ook voor een lid na de stop van ch. 5.10 (besluit van de eigenaar: "ook als de motor na het eerste al wist dat het niet
gold"); een lid met een fout telt niet mee als geldend en wordt niet genoemd, tenzij de vouw zelf op die fout faalt (dan
de bestaande foutzin). Voorbeeld, Jutter met vondsten V1 en V3 waardevol, V2 en V4 niet:

    want aan de voorwaarde "alle vondsten van de Jutter waardevol zijn" is niet voldaan: niet voor V2 en V4 (2 van de 4)
    want aan de voorwaarde "ten minste één van de vondsten … waardevol is" is niet voldaan: voor geen van de 4 (V1, V2, V3 en V4)

**Voorlopig besluit van de hoofdsessie (2026-10-21, naar Fable-advies 2, `werk/defaults-decider/fable-advies-2.md` §3
en §4.3), in de plaats van de regel over een lid met een fout hierboven:**

1. De telling is gelijk aan de lijst: m en k tellen alleen leden met een oordeel (geen fout). Een lid waarvan de matrix
   een fout gaf, heet "niet beoordeeld", nooit "geldt niet". Is er minstens één, dan na de leden één toevoeging "(1 lid
   niet beoordeeld)" of "(n leden niet beoordeeld)". Faalt de vouw zelf op een fout, dan blijft de bestaande foutzin.
   Voorbeeld bij `all` niet voldaan met V2 en V4 false en V5 een fout: "niet voor V2 en V4 (2 van de 4) (1 lid niet
   beoordeeld)".
2. De bron is het oordeel per bijdrager uit de lezing; `reached` lees je niet (Drools schrijft vaak `unordered`).
3. Bekende beperking, in de Result: het oordeel van een lid dat de sessie niet bereikte kan rusten op een deponering die
   de lezing wegliet (een deling door leeg of nul in de matrix); het corpus en v2 hebben die vorm niet.

**Tweede voorlopig besluit van de hoofdsessie (2026-10-21), over twee randgevallen, in de plaats van "Bij m = 0" en van
de telling bij één lid hierboven** (m telt de leden met een oordeel):

4. m = 0: geen haakjes, maar ": er is geen enkel lid" ("… is niet voldaan: er is geen enkel lid").
5. m = 1: geen telling "van de 1", maar het lid bij naam met ", het enige lid": "voor G1, het enige lid" (`all`
   voldaan; telling met k = 1), "niet voor G1, het enige lid" (`all` niet voldaan; `none` voldaan; telling met k = 0),
   "wel voor G1, het enige lid" (`none` niet voldaan). De toevoeging "(n leden niet beoordeeld)" blijft daarachter waar
   ze geldt; telt een fout mee zodat m met oordeel 0 of 1 wordt, dan volgen deze vormen m met oordeel.

**Aanvulling van de hoofdsessie (2026-10-21):** op deze tak ook het toolchain-deel van acceptatie (c) van de uchar-kaart
`fixpoint-inconsistency-order` dat zonder pin-bump kan: `AnswerBuilder` krijgt zijn inconsistenties uit
`fixpoint.inconsistencies()` (gelezen met de ids van het verzoek) in plaats van `Executor.readInconsistencies(session)`,
dezelfde verzameling; `readInconsistencies` vervalt. De leg van de replay vergelijkt nog zonder volgorde tot de pin-bump.

Stap 2, na de landing van de uchar-kaart `fixpoint-inconsistency-order`: de pin naar die landingscommit, en de leg van de
uitleg in `scripts/check-service-replay.sh` vergelijkt de inconsistenties weer in volgorde (uchar-acceptatie (c)).

## Acceptatie

- (a) Rood eerst: per rij van de tabel een zin op de drie routes (scenario-runner, dienst uit bronnen, dienst uit een
  IR-document), plus k = 0, m = 0 en meer dan vijf leden.
- (b) Bestaande uitlegtests ongewijzigd groen, behalve de benoemde pins van een vouw, elk met reden.
- (c) Bewijs op v2: `measure.sh` naar `na4/`; de 10 vouwzinnen van `na3/` met hun leden, verder elke zin gelijk aan
  `na3/`, het antwoord buiten de uitleg gelijk; in `bewijs.md`.
- (d) Stap 2: pin, `make check-pin`, de leg in volgorde, `make gates` groen (door de hoofdsessie).

May touch: `toolchain/.../engine/Verbaliser.java`, de tests van de uitleg, `scripts/check-service-replay.sh` (stap 2),
de pin (stap 2), deze kaart.

Out of scope: de uitleg van een aggregatie met een bereik (som, telling als waarde); een leesbare zin per lid uit de
matrix.

## Result (2026-10-21)

Tak `card/explanation-fold-members` in de worktree `wt-tc/explanation-fold-members`, vanaf toolchain main `86497170`;
stap 1 gebouwd door een Opus-bouwer op pin uchar `15f39170`; stap 2, `make gates` en dit Result door de hoofdsessie.

- `596d42c5` de kaart naar `doing/`.
- `d3c01dc5` de Verbalisator noemt de leden van een kwantorvouw die de uitkomst dragen, met de tests.
- `2ef4a532` de kaart: het voorlopige besluit van de hoofdsessie over een lid met een fout.
- `db6e188d` de randgevallen m = 0 en m = 1 (tweede voorlopig besluit) en de inconsistenties van het antwoord uit het
  fixpunt (aanvulling van de hoofdsessie), met de kaart.
- `1d3495b3` de pin op uchar `4f283d9d` (`fixpoint-inconsistency-order`, `reading-after-refusal`,
  `fold-contributor-verdicts`).
- `2b44f859` de leg van de uitleg vergelijkt de inconsistenties weer in volgorde (`service_replay.py`: `unordered` weg).
- `f4455348` de replay-pin 231 → 232: `jutterswet-keurgemiddelde` (uit `fold-contributor-verdicts`) speelt ook door
  de dienst.

### Wat er veranderde

- **De Verbalisator** (`toolchain/.../engine/Verbaliser.java`). De zin van een vorm die hij niet in woorden zet, staat
  op één plaats (`unstructured`): `aan de voorwaarde "…" is voldaan` of `… is niet voldaan`, en bij een kwantorvouw
  daarachter een dubbele punt en de leden (`members`, `names`). m is het aantal contributors van de knoop van de vouw met
  een oordeel, k het aantal daarvan waarvoor de matrix volgens de lezing geldt. De vormen zoals de kaart ze geeft:

  | kwantor | voldaan | niet voldaan |
  |---|---|---|
  | `all` | `voor alle m` | `niet voor <leden waarvoor zij niet geldt> (m − k van de m)` |
  | `none` | `voor geen van de m` | `wel voor <leden waarvoor zij geldt> (k van de m)` |
  | `atLeast`, `atMost`, `exactly` | `voor k van de m (<leden waarvoor zij geldt>)` | idem |

  k = 0 in een telling: `voor geen van de m (<alle leden>)`. De randgevallen (tweede voorlopig besluit van de
  hoofdsessie 2026-10-21, in de kaart): m = 0 `er is geen enkel lid`; m = 1 geen telling maar het lid bij naam,
  `voor G1, het enige lid` (`all` voldaan, telling met k = 1), `niet voor G1, het enige lid` (`all` niet voldaan,
  `none` voldaan, telling met k = 0), `wel voor G1, het enige lid` (`none` niet voldaan). De leden bij hun label (de
  sleutel van `instances` die `verbalise` krijgt, op identiteit), in code-unit-volgorde (`String::compareTo`; in het
  corpus bijvoorbeeld "niet voor V10 en V9"), met "en" voor het laatste, boven vijf de eerste vijf en "en nog k".
- **Het oordeel per lid** is `Contributor.value` van de lezing, ook voor een lid dat de sessie na de stop van ch. 5.10
  niet bereikte (besluit van de eigenaar). `Contributor.reached` leest de Verbalisator niet.
- **Een lid met een fout** (voorlopig besluit van de hoofdsessie 2026-10-21, naar Fable-advies 2 §3; in de kaart): het
  heeft geen oordeel, telt niet mee in m en k, wordt niet genoemd, en na de leden staat één toevoeging "(1 lid niet
  beoordeeld)" of "(n leden niet beoordeeld)". Faalt de vouw zelf, dan faalt de zin zoals voorheen (`Read.of` op een
  knoop met een fout).
- **Luid, nieuw**: een oordeel van de vouw dat niet past bij k en m volgens haar kwantor (`all`: k = m, `none`: k = 0,
  `atLeast n`: k ≥ n, `atMost n`: k ≤ n, `exactly n`: k = n), want dan spreekt de zin zichzelf tegen; een lid zonder
  oordeel en zonder fout; een lid zonder label (zie "Wat open bleef").
- De bestaande zinsvormen blijven; elke andere zin is ongewijzigd (op v2 gemeten, hieronder).

- **De inconsistenties van het antwoord** (aanvulling van de hoofdsessie, het toolchain-deel van acceptatie (c) van de
  uchar-kaart `fixpoint-inconsistency-order` dat zonder pin-bump kan): `AnswerBuilder` leest ze uit
  `outcome.fixpoint().inconsistencies()`, het fixpunt dat `Executor` met de ids van het verzoek leest, in plaats van uit
  `Executor.readInconsistencies(session)`; dezelfde verzameling, de `Inconsistentie`-feiten van de sessie.
  `readInconsistencies` en het veld `Executor.Outcome.inconsistencies` vervallen. Op de pin `15f39170` is de volgorde nog
  die van het werkgeheugen; uchar sorteert vanaf de landing van die kaart, en de leg van de replay vergelijkt tot de
  pin-bump zonder volgorde. De luide toets van `readInconsistencies` (een bean-classloader zonder `Inconsistentie`) ging
  mee: het fixpunt leest de feiten van de sessie van de build zelf.

Eerste leesbeslissing van de bouwer, vervangen door het tweede voorlopige besluit: m = 0 letterlijk na de dubbele punt,
`is niet voldaan: (geen enkel lid)` (zo gemeten in `na4/`).

### Rood eerst (a)

`ExplanationFoldMembersTest` (scenario-runner) en `ExplanationFoldMembersServiceTest` (dienst uit bronnen en uit een
IR-document), met een eigen model: zes regels op een Jutter, één per kwantor (`alle`, `geen van de`, `ten minste één`,
`ten minste 2`, `ten hoogste 1`, `precies 1`, de laatste drie over "het gewicht … groter is dan 4 kg"), over zeven
Jutters: J1 met vier vondsten (V1 en V3 waardevol, V2 en V4 zwaarder dan 4 kg), J2 met één (V5, beide; m = 1), J3 met
twee die geen van beide zijn, J4 zonder vondsten (m = 0), J5 met zeven, in de invoer buiten de code-unit-volgorde van
hun labels (X20, X3, X10, X1, X12, X2, X11), J6 met twee lichte waardevolle (V8, V9; J6 kwam erbij met de randgevallen,
zodat "voor alle m" met m > 1 gedekt blijft), J7 met één lichte die niet waardevol is (V10; voor "niet voor …, het
enige lid"). Op de code van de kaartbasis met alleen de tests: rood op alle drie de routes
(`t3/logs/red-1.log`, `red-2.log`: scenario 1/1, dienst 2/2 rood; de zinnen gelijk tot "is (niet) voldaan", de leden
ontbreken). Groen na de bouw (`green-1.log`, na de bijsturing `green-7.log`, na de randgevallen `green-8.log`). Zinnen
per rij, op de drie routes gelijk:

- `all`, voldaan: "J6 is gelukkig, omdat aan de voorwaarde "alle vondsten van de Jutter waardevol zijn" is voldaan: voor
  alle 2"
- `all`, niet voldaan: "J1 is niet gelukkig, want aan de voorwaarde "alle vondsten van de Jutter waardevol zijn" is niet
  voldaan: niet voor V2 en V4 (2 van de 4)"
- `none`, voldaan: "J3 is onvermogend, omdat aan de voorwaarde "geen van de vondsten van de Jutter waardevol is" is
  voldaan: voor geen van de 2"
- `none`, niet voldaan: "J1 is niet onvermogend, want … is niet voldaan: wel voor V1 en V3 (2 van de 4)"
- `atLeast 1`, voldaan: "J1 is vermogend, omdat aan de voorwaarde "ten minste één van de vondsten van de Jutter
  waardevol is" is voldaan: voor 2 van de 4 (V1 en V3)"
- `atLeast 2`, voldaan / niet: "J1 is zwaarbeladen, omdat aan de voorwaarde "het gewicht van ten minste 2 van de
  vondsten van de Jutter groter is dan 4 kg" is voldaan: voor 2 van de 4 (V2 en V4)" / "J6 is niet zwaarbeladen, want …
  is niet voldaan: voor geen van de 2 (V8 en V9)"
- `atMost 1`, voldaan / niet: "J3 is lichtbeladen, omdat … is voldaan: voor geen van de 2 (V6 en V7)" / "J1 is niet
  lichtbeladen, want … is niet voldaan: voor 2 van de 4 (V2 en V4)"
- `exactly 1`, voldaan / niet: "J2 is enkelbeladen, omdat … is voldaan: voor V5, het enige lid" / "J1 is niet
  enkelbeladen, want … is niet voldaan: voor 2 van de 4 (V2 en V4)"
- k = 0 in een telling: "J3 is niet vermogend, want … is niet voldaan: voor geen van de 2 (V6 en V7)", ook bij
  `atMost` voldaan: "J3 is lichtbeladen, omdat … is voldaan: voor geen van de 2 (V6 en V7)"
- m = 0: "J4 is gelukkig, omdat … is voldaan: er is geen enkel lid", "J4 is niet vermogend, want … is niet voldaan: er
  is geen enkel lid"
- m = 1: "J2 is gelukkig, omdat … is voldaan: voor V5, het enige lid", "J2 is niet onvermogend, want … is niet voldaan:
  wel voor V5, het enige lid", "J2 is niet zwaarbeladen, want … is niet voldaan: voor V5, het enige lid", "J7 is
  onvermogend, omdat … is voldaan: niet voor V10, het enige lid", "J7 is niet gelukkig, want … is niet voldaan: niet voor
  V10, het enige lid"
- meer dan vijf leden: "J5 is niet gelukkig, want … is niet voldaan: niet voor X1, X10, X11, X12, X2 en nog 2 (7 van de
  7)", "J5 is zwaarbeladen, omdat … is voldaan: voor 7 van de 7 (X1, X10, X11, X12, X2 en nog 2)"

Twee tests erbij op de scenario-route, over een matrix over een tijdlijn ("de dagvergoeding van geen van de bergers van
de Bergingsploeg gedurende het gehele jaar groter is dan 40"), die kan falen, zodat de motor de leden in volgorde leest
en stopt. Beide toetsen eerst op de lezing dat de motor na het eerste lid stopte (`reached` [true, false]); de zin zelf
leest `reached` niet.

- `a_member_after_the_stop_is_named_by_the_verdict_of_its_reading` (besluit van de eigenaar): B1 (50) en B2 (60); de
  motor stopt "geen van de" na B1, de zin noemt B2 uit de lezing: "P1 is niet wisselvallig, want aan de voorwaarde "…" is
  niet voldaan: wel voor B1 en B2 (2 van de 2)".
- `a_member_whose_matrix_faults_after_the_stop_is_not_judged` (voorlopig besluit van de hoofdsessie): B1 zonder
  dagvergoeding (de matrix geldt, de motor stopt), B2 met de dagvergoeding als waarde op de peildatum in plaats van een
  tijdlijn, waarop een matrix die de tijdlijn heel leest in de lezing faalt (`InputFormFault`, `ComputeHelpers.complete`);
  de sessie bereikte B2 niet en heeft geen fout: "P1 is niet wisselvallig, want aan de voorwaarde "…" is niet voldaan:
  wel voor B1, het enige lid (1 lid niet beoordeeld)" (in `na4/`-stand nog "wel voor B1 (1 van de 1) (1 lid niet
  beoordeeld)"). Twee eerdere vormen lukten niet: een tijdlijn die de matrix als één
  waarde leest, weigert de Drools-tak (MARKER, "the time-dependent attribute … at a place that reads or writes one
  value"); een scenario zonder peildatum gaf B2 een oordeel zonder fout.

Beide alleen op de scenario-route: de dienst neemt geen tijdlijn als invoer aan (400 `UNSUPPORTED`; de goldens met een
geordende vouw, `keurploeg`, `ploegpremie`, `reddingsvloot`, `sleepvloot`, staan daarom in `REPLAY_EXCLUDED`).

### Aangepaste tests, met reden (b)

- `ExplanationReadingsTest` en `ExplanationReadingsServiceTest`, de twee zinnen van de kwantorvouw "alle vondsten van de
  Jutter waardevol zijn": `… is niet voldaan` → `… is niet voldaan: niet voor W2 (1 van de 2)` (J1: W1 waardevol, W2
  niet) en `… is voldaan` → `… is voldaan: voor W3, het enige lid` (J2: W3; met de randgevallen, eerst `voor alle 1`).
  De opdracht van de kaart.
- Elke andere uitlegtest ongewijzigd groen.

### Bewijs op v2 (c)

`werk/defaults-decider/bewijs.md`, sectie "Na explanation-fold-members", meting `na4/` (de code van `d3c01dc5`, uchar
`15f39170`); vergelijking per zin `t3/compare-na3-na4.py`, uitvoer `t3/compare-na3-na4.txt`:

- het antwoord buiten de uitleg in alle vijf verzoeken bytegelijk aan `na3/`;
- 171 zinnen, elke zin zonder de leden van een vouw letterlijk die van `na3/` (0 afwijkingen);
- de 10 vouwzinnen met hun leden, alle tien "ten minste één", niet voldaan:
  - "ten minste één van de eindigingsgronden van het Recht op uitkering WW de vrijstelling op grond-van-leeftijd heeft"
    voor R1, vijf keer: `is niet voldaan` → `is niet voldaan: voor geen van de 1 (G1)`;
  - dezelfde vouw voor R2 in restant, zonder eindigingsgrond: `is niet voldaan: (geen enkel lid)`;
  - "ten minste één van de rechten van de samenloop van het Recht op uitkering WW een recht op grond-van-de-wia is",
    vier keer, zonder Samenloop in het verzoek: `is niet voldaan: (geen enkel lid)`.
- "niet beoordeeld" komt op v2 niet voor.

Na het tweede voorlopige besluit: meting `na4b/` (de werkboom op `2ef4a532` met de randgevallen en de inconsistenties
uit het fixpunt, de code van `db6e188d` op een javadoc-afbreking en de test van J7 na), `bewijs.md` subsectie "Na het tweede voorlopige besluit (m = 0, m = 1)",
vergelijking `t3/compare-na3-na4b.txt` (0 afwijkingen). Antwoorden bytegelijk aan `na4/` en `na3/` (v2 heeft geen
inconsistentie); elke andere zin gelijk; de 10 vouwzinnen, `na4/` → `na4b/`:

- eindigingsgronden van R1, vijf keer: `is niet voldaan: voor geen van de 1 (G1)` → `is niet voldaan: niet voor G1, het
  enige lid`;
- eindigingsgronden van R2 (restant) en rechten van de samenloop (vier keer): `is niet voldaan: (geen enkel lid)` → `is
  niet voldaan: er is geen enkel lid`.

Ter controle over het corpus (`t3/fold-sentences.py`, uitvoer `t3/fold-sentences-corpus.txt`): de twaalf gespeelde
goldens met een kwantorvouw door de dienst met uitleg, 266 vouwzinnen, alle vijf kwantoren in beide kolommen, k = 0,
m = 0, een vouw over een pad (`dringende-melding`), geen fout (gemeten op `d3c01dc5`, vóór de randgevallen).

### Suites en de replay

- `mvn -B -o verify` over de hele reactor op `db6e188d`: groen, 1646/151/351/53, skip-pin PASS op alle drie de poorten, 8m36s (`t3/logs/verify-3.log`). Op `2ef4a532`: groen,
  1646/151/351/53 (uchar-backend, toolchain, dienst, Quarkus; toolchain 148 → 151 en dienst 349 → 351: de vijf nieuwe
  tests), skip-pin PASS op alle drie de poorten, 7m21s (`verify-2.log`); tijdens de bijsturing 1646/150/351/53
  (`verify-1.log`).
- `bash scripts/check-service-replay.sh` op `d3c01dc5`: PASS, 231 goldens, 610 cases (pin 231), 1092 zinnen, 1241
  uitgelegde voorwaarden (vloer 1241), "niet is vast te stellen" bij 0 goldens, 58 s (`t3/logs/replay-1.log`). De leden
  voegen geen "omdat" of "want" toe, dus de telling van de voorwaarden is gelijk; een fout in een vouwzin gaf een 500 en
  dus een FAIL. Na de randgevallen en de inconsistenties uit het fixpunt opnieuw: PASS, dezelfde aantallen, 63 s
  (`t3/logs/replay-2.log`); het antwoord met en zonder uitleg gelijk over het corpus, de inconsistenties zonder volgorde.

### Stap 2 (hoofdsessie)

uchar `15f39170` → `4f283d9d`, `make check-pin` groen. Wat de pin meebrengt: het fixpunt geeft `inconsistencies`,
`objects` en `links` in een vaste volgorde (met labels gelezen, zoals `Executor` en `ScenarioRunner` doen); de build met
lezingen sluit een geweigerde vorm af; ch. 5.12 legt het oordeel van elk lid van een kwantorvouw vast (de normatieve bron
van de zinnen van deze kaart, die al uit dat oordeel lazen), en een vouw waarvan het filter of de waarde van een lid
faalt, heeft geen bijdragers meer (de vouw faalt dan zelf, en de zin is de bestaande foutzin). De leg van de uitleg in
`check-service-replay.sh` vergelijkt het antwoord met en zonder uitleg nu ook in de volgorde van de inconsistenties
(uchar-acceptatie (c) van `fixpoint-inconsistency-order`): PASS, 232 goldens, 614 cases, 1096 zinnen, 1245 uitgelegde
voorwaarden (`werk/defaults-decider/t3/replay-3.log`). De eerste `make gates` na de pin stopte bij de replay op de
gelijkheidspin (232 speelden, 231 gepind): de nieuwe golden, bijgezet in `f4455348`.

### Gates

`make gates` op `f4455348` met `TAG/CLUSTER=explanation-fold-members`: GROEN, 7 stappen in 10m26s (reactor
1649/151/351/53; replay 232 goldens, 614 cases, pin 232, de uitleg met de inconsistenties in volgorde; manifest 29 ok,
3 uitgesloten; image `decision-service:explanation-fold-members`; kind-smoke H2–H4 PASS). Log:
`werk/defaults-decider/t3/tc-gates-2.log`.

### Wat open bleef

- **Bekende beperking** (Fable-advies 2 §4.3, voorlopig besluit van de hoofdsessie): het oordeel van een lid dat de sessie
  niet bereikte, kan rusten op een deponering die de lezing wegliet (een deling door leeg of nul in de matrix, sinds
  2026-10-19 een §9.5-soort en geen fout). De uitleg zegt dan "niet voor V3" waar de sessie, had zij V3 bereikt, een
  inconsistentie had gemeld. Het corpus en v2 hebben die vorm niet.
- **Een gemaakt object als lid** heeft in de uitleg geen naam: de uitleg noemt alleen de objecten van de invoer (de
  labels die `verbalise` krijgt), en een lid zonder label faalt luid. De kaart zegt "een gemaakt object zoals de uitleg
  het noemt", maar de uitleg noemt er nu geen. In het corpus en op v2 komt het in een uitgelegde vorm niet voor: de enige
  kwantorvouw over gemaakte objecten (`jutterswet-opvolgerquorum`, "ten minste 1 opvolger van de Wachter …") staat in de
  voorwaarde van een objectcreatie, die de uitleg niet verwoordt; de replay speelt het hele corpus met uitleg zonder fout.
  Een naam vraagt de gemaakte objecten van het fixpunt (`Fixpoint.objects`: creatie en onderwerp) in de Verbalisator,
  dus de drie aanroepers (`ScenarioRunner`, `DecisionCore`, `ExplanationSession`), buiten May touch, en een keuze van de
  vorm: uchars canonieke tekst `«"<creatie>"»(<onderwerp>)#1` of de `#n` van `FixpointGivens`.

Geland 2026-10-21 door de hoofdsessie: fast-forward naar `71a36fc7` (`make gates` groen op `f4455348`, 10m26s, `TAG/CLUSTER=explanation-fold-members`; de Result-commit erna raakt alleen de kaart). Pin uchar `4f283d9d`. Voorlopige besluiten van de hoofdsessie in deze kaart (de eigenaar kan ze omdraaien): een lid met een fout heet "niet beoordeeld" (bevestigd door de eigenaar met het Fable-advies); de vormen bij m = 0 en m = 1.
