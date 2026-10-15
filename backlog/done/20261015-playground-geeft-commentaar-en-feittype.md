# playground-geeft-commentaar-en-feittype: de regel en het commentaar van een declaratie, en het feittype van een rol in de situatie

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `2af1864` (submodule op uchar `f752a5c9`).*

## Aanleiding

De werkbank toont per scenario een objectboom uit de JSON van `Playground.analyseer`
(`service/…/http/Playground.java`, met `ScenarioGegevens.java`). Zij wil twee dingen tonen die die JSON niet geeft:

1. **Bij een klik op een attribuut- of typenaam** een popover met de namen per vocabulaire en het commentaar bij de
   declaratie. De namen staan al in `vocabulaires.elementen`. Het commentaar niet: uchars lexer zet `LINE_COMMENT`
   op het HIDDEN-kanaal (`uchar/frontends/regelspraak/grammar/RegelSpraakLexer.g4:501`), dus het staat niet in het
   model, wel in de tokenstroom van uchars parser. In de v2-regelset van herleving-ww staat boven elk objecttype,
   attribuut en kenmerk een commentaarblok met een `// Bron:`-regel. Een naam kan op meer typen staan (`maxdatum`
   op RechtOpUitkeringWwBijOntslag en op Uitkeringsduur), dus het commentaar hoort bij het paar (eigenaar, naam).
   Een lid van een `Extensie van objecttype X`-blok heeft zijn commentaar in dat blok.
2. **De relaties tussen de objecten van de situatie.** `situatie.objecten[].rollen[]` geeft `rol`, `doeltype`,
   `labels`, `meervoudig` en `regel`, maar niet het feittype waartoe uchar de rol oploste. De rol `recht` komt in
   veel feittypen voor, en de werkbank mag dat niet zelf afleiden.

## Wat er gebeurt

De JSON wordt alleen uitgebreid; bestaande sleutels houden hun vorm.

1. **`vocabulaires.elementen[]`** krijgt per element met een declaratie in de bron `regel` (de bronregel van de
   declaratie) en `commentaar` (de `//`-regels direct erboven, zonder `//` en zonder witruimte aan de randen,
   aaneengesloten tot de eerste niet-commentaarregel of lege regel). Ook zonder gedeclareerd vocabulaire: dan staan
   de elementen er met lege `benamingen`. Regel en commentaar komen uit uchars eigen parse: de parse tree en
   `getHiddenTokensToLeft` op de tokenstroom van uchars lexer, niet uit een zoektocht in de tekst. Een lid uit een
   extensie krijgt de regel en het commentaar van zijn regel in die extensie, met de eigenaar van de extensie. Een
   element dat zich niet eenduidig aan een declaratie laat koppelen, laat de analyse falen met een melding die het
   element noemt.
2. **`situatie.objecten[].rollen[]`** krijgt `feittype`, uit uchars geresolvede scenario of case
   (`ResolvedScenario`, `CaseEmitter`). Geeft uchars publieke API het daar niet, dan komt er geen omweg; het Result
   zegt dan precies wat in uchar ontbreekt.
3. Optioneel: de namen van `fixpunt.koppelingen[].feittype` gelijk aan die van punt 2.

## Acceptatie

- (a) Op het s01-document van herleving-ww (v2) heeft `maxdatum` van RechtOpUitkeringWwBijOntslag en van
  Uitkeringsduur elk een eigen `regel` en `commentaar`; een lid uit een extensie van hww-08 heeft de regel in die
  extensie en Uitkeringsduur als eigenaar.
- (b) Een bron zonder vocabulaire geeft de elementen met lege `benamingen`, met regel en commentaar.
- (c) Een element zonder eenduidige declaratie geeft een melding die het noemt, geen leeg veld.
- (d) Nieuw gedrag is getest in nieuwe testklassen; een bestaand testbestand verandert alleen als de uitbreiding het
  afdwingt, in een eigen commit met de reden in het Result.
- (e) `make gates` groen.

## Omvang

Klein. May touch: `service/` (`Playground`, `ScenarioGegevens`, een nieuwe klasse voor de declaraties), nieuwe
testklassen, `docs/ENGINE.md`, kaartverplaatsing. Niet `uchar/`.

## Raakvlakken

De werkbank (`werkbank/static/`) leest dit contract en wordt elders aangepast; zij is hier alleen gelezen.

## Result (2026-10-15)

### Wat de kaart opleverde

- **Regel en commentaar per declaratie.** Een nieuwe klasse `Declaraties` (`service/…/http/Declaraties.java`) leest
  uit de parse die de playground al maakte voor de syntaxfouten (`Playground.parse`, nu met de boom en de
  tokenstroom in het record `Ontleding`) per declaratie de bronregel en het commentaar erboven. Het commentaar komt
  uit `getHiddenTokensToLeft` op het eerste token van de declaratie, zoals uchars `RuleChecker.bronAnnotaties` de
  `// Bron:`-regels boven een regel leest. Het blok loopt van de regel direct boven de declaratie omhoog, zolang elke
  regel een commentaarregel is. Een lege regel of een commentaar achter het vorige token (op diens regel) beëindigt
  het.
- **De koppeling aan het model.** De declaraties zijn die van uchars benamingenpas (`resolver/Benamingen`, pas 1):
  de kop van een objecttype, de attributen en kenmerken in een kop en in een extensie, de waarden van een
  enumeratiedomein, een parameter, een feittype met zijn rollen, een regel en een regelgroep. Elke declaratie krijgt
  de sleutel die die pas geeft: soort, eigenaar en naam. Een lid van een extensie heeft als eigenaar het type dat de
  extensie noemt. De naam is het bronstuk van de naamcontext met samengevoegde witruimte, zoals uchars
  `DefinitionResolver.src`; die methode is niet publiek, dus `Declaraties` heeft dezelfde regel code. Sleutels worden
  vergeleken zoals uchar namen vergelijkt: de soort gelijk, eigenaar en naam hoofdletterongevoelig.
  - Een element zonder declaratie heeft uchars frontend zelf geschreven: de attributen `opgegeven X` en `berekende X`,
    het kenmerk `afwijkende opgave X` en de vormen bij een opgave, de rijen van een beslistabel, een defaultsgroep, of
    een objecttype dat alleen een extensie noemt. Zo'n element krijgt geen `regel` en geen `commentaar`.
  - Een attribuut of kenmerk met meer declaraties krijgt de declaratie die het type draagt, zoals uchar die kiest
    (`DefinitionResolver.bestaande`): die in de kop, anders de eerste in documentvolgorde. Een extensie mag een lid
    alleen letterlijk herhalen; dat toetst uchar zelf.
  - Elk ander element met meer dan één declaratie, twee declaraties van één lid in een kop, en een declaratie zonder
    element (behalve een regel die geen vorm wordt) geven `Declaraties.Onkoppelbaar`. De playground zet dan
    `vocabulaires.declaratieFout` met de zin, en geen element krijgt `regel` of `commentaar`. Een 500 zou de hele
    analyse kosten en de zin alleen in de serverlog zetten; `declaratieFout` volgt `graafFout` en `fixpuntFout`.
- **De lijst is altijd gevuld.** `vocabulaires.elementen` staat er ook zonder gedeclareerd vocabulaire, met lege
  `benamingen`. `playground.html` toetst op `gedeclareerd` en verandert daardoor niet.
- **Tekst.** `docs/ENGINE.md` noemt de nieuwe sleutels en dat de lijst altijd gevuld is. `README.md` noemt onder de
  bekende verliezen dat een rol in de situatie geen feittype heeft.

### Het JSON-contract (toevoegingen)

Per element van `vocabulaires.elementen`, als het een declaratie in de bron heeft:
- `regel`: de bronregel van de declaratie (in de geanalyseerde bron, zoals `regel` elders);
- `commentaar`: de `//`-regels direct erboven, elk zonder `//` en gestript, in bronvolgorde; `[]` zonder commentaar.

Op `vocabulaires`, alleen als de koppeling faalt: `declaratieFout` met de zin.

Ingekort, uit de analyse van s01 (`POST /playground/analyse`):

```json
{"soort": "ATTRIBUUT", "eigenaar": "RechtOpUitkeringWwBijOntslag", "canoniek": "maxdatum",
 "benamingen": {"cgm": "Datum einde basisuitkering WW bij ontslag", "handboek": null},
 "regel": 127,
 "commentaar": ["De maxdatum van de lopende cyclus, met de verschuivingen van eerdere cycli (O3). Een opgave gaat",
                "…",
                "Bron: cgm:attribute:DatEBasisuitkeringWwBijOntslag@2026-08-13 (gelijk) · BWBR0004045&artikel=20&lid=1&o=b · …"]}
{"soort": "ATTRIBUUT", "eigenaar": "Uitkeringsduur", "canoniek": "maxdatum",
 "benamingen": {"cgm": "Datum einde duur WW-uitkering", "handboek": null},
 "regel": 239,
 "commentaar": ["De oorspronkelijke maxdatum, de eerste dag zonder recht (O1); het koppelvlak rekent om naar de",
                "laatste dag met recht van de BRM (B31, C14).",
                "Bron: cgm:attribute:DatEDuurWwUitkering@2026-08-13 (gelijk) · BWBR0004045&artikel=42&lid=1 · lemma's: maxdatum"]}
```

### Punt 2: het feittype van een rol in de situatie

Niet gebouwd. uchars publieke API geeft het feittype van een rol in een opgeloste situatie niet:
- `ResolvedScenario.RolWaarde(String sleutel, Objecttype doeltype, List<String> doelLabels, boolean meervoudig, int
  line)` (`uchar/frontends/regelspraak/src/main/java/dev/uchar/regelspraak/ir/ResolvedScenario.java:67`) heeft geen
  veld voor het feittype.
- `ScenarioResolver.rol` (`…/resolver/ScenarioResolver.java:722`) heeft het feittype wel in handen: de lus over
  `model.feittypen.values()` (r. 727) vindt de rol per feittype. Maar de treffer bewaart alleen de `Rol`
  (`treffers.add(new Rol[]{b})`, r. 730, 731, 734), en de `RolWaarde` wordt zonder feittype gebouwd (r. 758).
- De case die `CaseEmitter` schrijft (`…/emit/CaseEmitter.java:107`) zet een rol als `roles: {<rolnaam>: [labels]}`.
  Het schema zegt hetzelfde (`uchar/spec/schema/uchar-case-v0.schema.json`, `$defs/instance/properties/roles`: "Role
  name (as declared) to the labels of the linked instances").
- De backend lost de sleutel bij het inlezen zelf op (`CaseRunner.zetRollen`, `…/cases/CaseRunner.java:699`), naar
  een rolveld van de bean en niet naar een feittype, en geeft dat niet naar buiten.
- Het model heeft publieke zoekers die `RolBindingRef(feittype, rol)` geven, zoals `verbindendeRolViaNaamBinding`
  (`RegelSpraakModel.java:601`). Dat is een andere zoektocht: op houder- en doeltype en de exact gedeclareerde naam,
  niet op het type van het object en de naam of het meervoud zoals `ScenarioResolver.rol` zoekt. Die gebruiken zou de
  omweg zijn die de kaart uitsluit.

Wat uchar nodig heeft: een veld `feittype` (de `Feittype.naam` van de treffer) op `ResolvedScenario.RolWaarde`,
gevuld in `ScenarioResolver.rol`. Het case-formaat hoeft daarvoor niet te veranderen. De rol `recht` staat in s01 nu
zo in de situatie:

```json
U1 (Uitkeringsduur):                 {"rol": "recht", "doeltype": "RechtOpUitkeringWwBijOntslag", "labels": ["R1"], "meervoudig": false, "regel": 1617}
I4 (Inkomen in-een-kalendermaand):   {"rol": "recht", "doeltype": "Recht op uitkering WW", "labels": ["R1"], "meervoudig": false, "regel": 1626}
```

13 van de 14 feittypen van v2 hebben een rol `het recht`.

Punt 3 vervalt daarmee. `fixpunt.koppelingen` geeft alleen de koppelingen die een creatie legde, en in s01 zijn dat
er 0.

### Getoetst op echte invoer

Het s01-document is samengesteld zoals de vorige kaart deed: fundament en herleving-ww van v2 plus
`scenariodocs/s01.rgs`, met `"\n"` ertussen (1 717 regels). Hetzelfde recept geeft voor s04 de tekst van
`s04-volledig.rgs` uit de scratchpad, op een afsluitende regelovergang na. Getoetst tegen `PLAYGROUND=true
bin/regelspraak serve herleving.dienst.json <fundament> <rules> --poort 18294`.
- s01: 268 elementen, waarvan 225 met `regel` en 172 met niet-leeg commentaar; geen `declaratieFout`. Per soort
  (totaal/met regel): OBJECTTYPE 18/18, ATTRIBUUT 66/52, KENMERK 24/17, FEITTYPE 14/14, ROL 28/28, PARAMETER 7/7,
  ENUMERATIEWAARDE 44/44, VORM 67/45. De 43 zonder regel zijn de leden en vormen van de zeven opgaven en de
  defaultsgroep `herlevingstermijn art. 21 lid 3 en 5 voor RechtOpUitkeringWwBijOntslag`.
- `maxdatum` van RechtOpUitkeringWwBijOntslag op regel 127 en van Uitkeringsduur op regel 239, elk met het eigen
  blok (zie boven). Het extensielid `uitkeringsduur` uit hww-08 staat op regel 875, met Uitkeringsduur als eigenaar
  en de drie commentaarregels uit de extensie. De regels zijn nagekeken in de samengestelde bron.
- Twee analyses van s01 geven bytegelijke JSON. In `?vocabulaire=cgm` zijn `regel` en `commentaar` gelijk aan die
  in de canonieke keuze.
- s04: `s04-v01` PASS, `s04-v02` FAIL, zoals eerder. Zonder `regel` en `commentaar` is de JSON gelijk aan de analyse
  van s04 van vanochtend in de scratchpad, op alle sleutels van de wortel.
- Een kop die hetzelfde attribuut twee keer declareert, resolvet in uchar zonder melding (`bin/regelspraak check`
  geeft geen ERROR of WARNING). De playground geeft dan `declaratieFout`: "Het attribuut 'opbrengst' van 'Berging'
  heeft declaraties op de regels [2, 3], en uchar geeft niet aan welke daarvan het model draagt".
- De koppeling gaf geen fout op de 125 goldens van uchar met een `bron.rgs` (alle 125 resolven), en op de 27 van de
  33 TOKA-bronnen die resolven (de 32 regels van het manifest, elk samengevoegd, en de 47 fragmenten samen). Zonder
  declaratie bleven alleen opgave-leden en -vormen, defaultsgroepen en de rijen van een beslistabel (TOKA:
  `Woonregio factor 1` tot `3`).

### Testwijzigingen

- Nieuw: `service/…/http/PlaygroundDeclaratiesTest` (6 tests):
  - regel en commentaar per soort, met een lege `//`-regel in het blok, een lege regel en een commentaar achter het
    vorige lid als grens, `maxdatum` op twee typen, een extensielid, en lege `benamingen` zonder vocabulaire;
  - een letterlijke herhaling in een extensie houdt de regel van de kop, ook als de extensie eerst staat;
  - op uchars golden `jutterswet-opgave-bedrag` hebben de opgave-leden en -vormen geen regel, het gemarkeerde
    attribuut en de regels wel, met hun `// Bron:`;
  - een dubbel lid in één kop geeft `declaratieFout`;
  - een scenariobron (golden `jutterswet-vocabulaires` met het scenario van de playground) in `cgm`;
  - elke golden van uchar koppelt, elk objecttype, feittype, rol, parameter, enumeratiewaarde en elke regelgroep heeft
    een declaratie, en de regel van elke declaratie bevat haar naam.
  Twee tijdelijke mutaties (de grens bij een commentaar achter het vorige lid weg, de voorkeur voor de kop weg) lieten
  de eerste twee tests falen.
- Gewijzigd, in een eigen commit (`6fd9b7e`): `PlaygroundScenarioGegevensTest.zonder_vocabulaire_geen_elementen`
  heet nu `zonder_vocabulaire_lege_benamingen`. De reden: de toets legde vast dat `elementen` zonder vocabulaire leeg
  is, en de kaart vraagt dat de lijst dan gevuld is. Zij verwacht nu de 8 declaraties van haar bron, elk met lege
  `benamingen`. In `f698c24` alleen faalt deze ene toets.

### Acceptatie

- (a) Gehaald op s01, zie "Getoetst op echte invoer".
- (b) `PlaygroundDeclaratiesTest.regel_en_commentaar_per_declaratie` en de gewijzigde toets zonder vocabulaire.
- (c) `PlaygroundDeclaratiesTest.een_dubbel_lid_geeft_declaratieFout`.
- (d) Eén nieuwe testklasse; één bestaande toets gewijzigd in een eigen commit, met de reden hierboven.
- (e) `make gates` groen op `9022926` (7 stappen in 8 min 26 s):
  - pin;
  - reactor (5 min 15 s): 1574 tests in uchars backend, 129 in toolchain, 261 in service (was 255, plus de 6 nieuwe;
    vloer 235) en 47 in service-quarkus, geen skip;
  - tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, en 4 getuigenissen;
  - replay: 225 goldens met 599 cases (pin 225);
  - manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
  - image `beslisservice:dev`;
  - kind-smoke: H2, H3 en H4 PASS.

### Open

- Punt 2 wacht op uchar: een veld `feittype` op `ResolvedScenario.RolWaarde` (zie boven). Daarna is het in de
  toolchain één sleutel in `ScenarioGegevens.situatie`, en kan punt 3 getoetst worden.
- `Declaraties` herhaalt twee dingen uit uchars resolver die niet publiek zijn: de naam van een declaratie
  (`DefinitionResolver.src`) en de keuze bij een herhaald lid (`DefinitionResolver.bestaande`). Verandert uchar een
  van beide, dan faalt `elke_golden_koppelt` of geeft de playground `declaratieFout`; er komt geen stil leeg veld.
- uchar resolvet een kop die hetzelfde attribuut twee keer declareert zonder melding. Dat is een vraag voor uchar,
  geen wijziging hier.

### Commits

`500a90b` kaart · `2b6515a` claim · `f698c24` regel en commentaar bij elke declaratie, met de nieuwe testklasse ·
`6fd9b7e` de toets zonder vocabulaire · `9022926` tekst · deze Result en naar `done/`.
