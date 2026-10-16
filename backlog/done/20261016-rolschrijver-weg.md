# rolschrijver-weg: de invoer van een sessie komt uit uchars CaseInvoer, de kopie van het rolschrijven verdwijnt

*Vastgelegd op 2026-10-16. Kaart G van de reeks "beslisservice via dienst", na uchar-kaart F
(`backlog/done/20261016-case-invoer-publiek.md` in uchar, `9159c9bd`). Toolchain `38df9cb`, uchar-pin `9159c9bd`
(de eerste commit van deze kaart).*

## Aanleiding

De distributie maakt de beans van een invoer zelf, op twee plaatsen:

- `CaseSessie` (de sessie van de uitleg in de playground) leest een `uchar-case-v0`-case met een eigen lezer: beans,
  attributen, kenmerken, en de rollen via `RolSchrijver`; tijdlijnen, emmerscalairs en kenmerktijdlijnen weigert zij
  (`NietSpeelbaar`).
- `VerzoekMapper` (de dienst) maakt de beans van een verzoek met `maakBean`, zet attributen en kenmerken met
  `RegelSpraakRuntime.set`, de inline rollen via `RolSchrijver` en de `relaties` met `writeRefAls1op1`.

`RolSchrijver` is een kopie van de invoerstap van uchars replay (RUNTIME-SEMANTICS ch. 5.7a: het zusterveld, het verre
veld van een 1:1-relatie, de spiegel van een wederkerige rol, de lijstvolgorde). Sinds `9159c9bd` is die stap publiek:
`CaseInvoer.van(build, case)` met `beans()`, `typen()`, `invoer()`, en de weigeringen `CaseInvoer.Rolbotsing` (WF-013)
en `CaseInvoer.Rolweigering`, elk met `label()`, `rol()`, `doel()`. Het invoerdeel van `uchar-case-v0` is het enige
invoercontract.

## Wat er gebeurt

1. `CaseSessie` leest de case met `CaseInvoer.van(build, c)`; een `CaseRunner.Excluded` wordt `NietSpeelbaar`. De
   parameters en de rekendatum zet zij als globals zoals nu; de beans gaan in met
   `DroolsBackend.voegIn(build, sessie, invoer.invoer())`; labelfunctie, beans en typen komen uit `CaseInvoer`.
2. `VerzoekMapper` houdt haar validatie (400 met veldpad) en schrijft het gevalideerde verzoek als case
   `{"asOfDate", "input": {"instances": [...]}}` met de ids als labels: attributen als casewaarden, kenmerken die
   `true` zijn als `characteristics`, inline rollen en `relaties` als `roles` (een relatie op de instantie waarvan de
   rol naar het andere eind naar-één is). Een tabel (id, rolsleutel, doel-id) → veldpad onthoudt waar elke link in het
   verzoek stond. Dan `CaseInvoer.van(ep.build(), case)`: een `Rolbotsing` wordt `VERKEERD_TYPE`, een `Rolweigering`
   `NIET_ONDERSTEUND`, op het pad uit de tabel. `Wereld` draagt de `CaseInvoer`; `Uitvoerder` geeft
   `wereld.invoer().invoer()` aan `voegIn`; `BeslisserKern` geeft `ep.build()` aan de mapper.
3. Weg: `RolSchrijver` en wat daardoor ongebruikt wordt (`Wereld.lijsten`, `maakBean`, `writeRefAls1op1`,
   `heeftSetter`, de eigen lezers van `CaseSessie`).

## Acceptatie

- (a) `RolSchrijver.java` bestaat niet meer; geen eigen code maakt een bean van een invoer of schrijft een rol.
- (b) Gedrag gelijk voor dienst en playground: `make gates` groen (tweelingen, replay van de goldens, pariteit
  JDK/Quarkus, concurrency, `GoldenOverHttpTest`), en een meting buiten de gates: een dienst met
  `PLAYGROUND=true DETAIL=true` op de v2-descriptor van herleving-ww, met de jar van `38df9cb` en die van de eindcommit,
  vergelijkt per eval-document de antwoorden van `/playground/analyse?vocabulaire=cgm` en `…&via=dienst` (status,
  verwachtingen, trace, dekking, uitleg, fixpunt) en de vijf `dienst/*.request.json` op hun pad, met en zonder
  detailvlaggen. Elk verschil is benoemd en verklaard; scenario's die `CaseSessie` nu speelt en eerst weigerde
  (tijdlijnen, emmerscalairs) zijn bij naam gemeld.
- (c) Een tegenstrijdige 1:1-invoer (twee verschillende doelen op een naar-één rol, ook via het verre veld) geeft
  400 `VERKEERD_TYPE` op het veldpad van de link in het verzoek: nieuwe tests.
- (d) `IngangHttpTest` (r. ~370, `new RolSchrijver.Lijsten()`) gaat mee in een eigen commit met "testpatch" in de
  titel (akkoord maintainer). Moet een andere bestaande test veranderen, dan stopt de kaart met een patch.
- (e) `docs/ENGINE.md` en de README noemen `RolSchrijver` en het eigen rolschrijven niet meer.

## Omvang

May touch: `toolchain/…/engine/CaseSessie.java`, `toolchain/…/engine/RolSchrijver.java` (weg), `service/…/VerzoekMapper.java`,
`Wereld.java`, `Uitvoerder.java`, `BeslisserKern.java`, `Verzoek.java` (javadoc), `IngangHttpTest.java` (testpatch),
nieuwe testklassen, `docs/ENGINE.md`, `README.md`, de uchar-pin, deze kaart. Niet `uchar/`.

## Result (2026-10-16)

### Wat de kaart opleverde

- **Weg**: `toolchain/…/engine/RolSchrijver.java` (191 regels), de eigen beanlezer van `CaseSessie` (`beanVoor`,
  `zetAttributenEnKenmerken`, `zetRollen`, `spiegelWederkerigeRollen`, `invoer`, `argType`, `roep`), en in
  `VerzoekMapper` `maakBean`, `writeRefAls1op1`, `heeftSetter` en het zetten met `RegelSpraakRuntime.set`;
  `Wereld.lijsten`. Main-code samen: 221 regels erbij, 478 eraf.
- **`CaseSessie`** leest de case met `CaseInvoer.van(build, c)`; een `CaseRunner.Excluded` wordt `NietSpeelbaar`.
  Parameters (met de eigen waardelezer) en `rekendatum` gaan als globals op de sessie zoals voorheen; de invoer met
  `DroolsBackend.voegIn(build, sessie, invoer.invoer())`; labels, beans en typen komen uit `CaseInvoer`.
- **`VerzoekMapper`** valideert zoals voorheen en schrijft het verzoek als case: attributen als casewaarden
  (`number` met `BigDecimal.toString`, `date`, `dateTime` met seconden en hele milliseconden, `boolean`, `text`,
  `enumeration`), kenmerken die `true` zijn als `characteristics`, inline `rollen` onder hun canonieke naam als
  `roles`, en elke relatie als één link op het eind waarvan de rol naar het andere eind naar-één is (waar geen van
  beide dat is, op het eerste eind; uchar weigert hem dan). Een tabel (id, rolsleutel, doel-id) → veldpad geeft:
  `Rolbotsing` → 400 `VERKEERD_TYPE`, `Rolweigering` → 400 `NIET_ONDERSTEUND`, elk op het pad van de link en met
  uchars zin; een andere `Excluded` → `NIET_ONDERSTEUND` op `instanties`. `CaseInvoer.van` draait ook na een
  validatiefout, op wat valideerde, zodat een botsing naast de andere schendingen in dezelfde 400 staat.
- **Datum buiten de casevorm** (`909b142`): een datum of datum-tijd met een jaar buiten 0000–9999
  (`+10000-01-01`, `-0001-01-01`) leest Java, het lexeem van `uchar-case-v0` niet. De mapper noteert zulke velden in
  uchars leesvolgorde (peildatum, dan attributen) en maakt van uchars weigering een 400 `VERKEERD_TYPE` op het eerste,
  met uchars zin. Op `d87950f` was dat een 500 (gemeten: `{"fout":"interne fout"}`); op `38df9cb` een 200.
- **`Wereld(CaseInvoer, globals, rekendatum)`**, `beans()` uit `CaseInvoer`; `Uitvoerder` geeft
  `wereld.invoer().invoer()` aan `voegIn`; `BeslisserKern` geeft `ep.build()` aan de mapper.
- **Tekst**: `docs/ENGINE.md` (de dienst maakt geen bean meer; het pad van een geweigerde link; de datum buiten de
  casevorm); javadoc van `Verzoek`, `Wereld`, `Uitvoerder`, `CaseSessie`, `VerzoekMapper`. De README noemde
  `RolSchrijver` niet.

### Acceptatie

- (a) Gehaald.
- (b) Gehaald. `make gates` groen op `909b142` (de commit daarna raakt alleen `backlog/`): 1 pin; 2 reactor
  1619/140/332/53 testgevallen (op `38df9cb` 1601/140/322/53: uchar plus de 18 tests van kaart F, service plus de 10
  nieuwe); 3 tweelingen, 18 cases, vocabulairetweeling 2 cases in 34 verzoeken; 4 replay 225 goldens, 599 cases (pin
  225); 5 manifest 29 ok, 3 uitgesloten, 0 mismatch; 6 image; 7 kind-smoke H2, H3, H4 PASS. `GoldenOverHttpTest`
  (30), `ConcurrencyTest`, `DetailConcurrencyTest` en de Quarkus-pariteitstests groen in de reactor.
  Meting buiten de gates: v2-dienst herleving-ww (`PLAYGROUND=true DETAIL=true`, bronnen als `make serve`), jar van
  `38df9cb` tegen die van `da1a916` en van `909b142`. Eval-documenten s01–s07 (s08–s37 zonder document) op
  `/playground/analyse?vocabulaire=cgm` en `…&via=dienst`, en de vijf `dienst/*.request.json` op
  `/beoordeel-herleving` kaal, met `trace`, `dekking`, `fixpunt`, en daarbij `uitleg` en `vocabulaire=cgm`: 29
  antwoorden, alle bytegelijk (status, verwachtingen, trace, dekking, uitleg, fixpunt). Daarnaast
  `bin/regelspraak scenario --trace --uitleg` over uchars 30 scenario-goldens en de 32 regels van het TOKA-manifest:
  61 van 62 gelijk. Het verschil is de verbetering die kaart F aankondigde: `jutterswet-dagentelling-rgs`, scenario's
  voorbeeld en jaargrens (een kenmerk met perioden) en input blijft (een tijdsafhankelijk attribuut), kregen
  "de uitleg speelt deze case niet" en krijgen nu uitleg en een `fixpunt` met de tijdlijn. Geen v2- of TOKA-scenario
  raakt tijdlijnen of emmerscalairs.
- (c) Gehaald. `RolbotsingHttpTest` (5): één 1:1-link geldt beide kanten op (200); twee doelen op het eigen veld
  (`instanties[0].rollen.zuidbuur`), op het verre veld (`instanties[2].rollen.zuidbuur`), op het verre veld via
  `relaties` (`relaties[0].rollen.zuidbuur`), op de omgekeerde poot van een wederkerige relatie
  (`instanties[2].rollen.maat`), elk 400 `VERKEERD_TYPE` met uchars zin (WF-013). Tegen de jar van `38df9cb`: de
  meldingen waren de eigen zinnen, het pad via `relaties` was `relaties[0].rollen.noordbuur`, dat van de wederkerige
  botsing `instanties`. `JaarBuitenCasevormHttpTest` (5): een gewone datum 200; jaar 10000 en jaar -1 op een
  attribuut, en op `peildatum` en `rekendatum`, 400 `VERKEERD_TYPE` op dat veld.
- (d) Gehaald, met een tweede testpatch na akkoord van de maintainer: `da1a916` (`IngangHttpTest` r. 370) en
  `4a231d1`: `TokaBron.build()` in plaats van `beanLoader()` (de declaraties van TOKA, een scope zonder vormen; het
  hele model bouwt niet, WF-026), de constructor van `VerzoekMapperTest`, `RelatiesTest`, `RolDoeltypeTest`, uchars zin
  in `IngangHttpTest` bij een tweede doel via `relaties`, en commentaar dat de rolschrijver noemde.
- (e) Gehaald.

### Open

- Het veldpad van een botsing op het verre veld via `relaties` noemt de rol die het doel van de link noemt
  (`relaties[0].rollen.zuidbuur`), waar het eerder de rol was die het tweede doel noemt (`…rollen.noordbuur`). Een
  relatie is één link, en `CaseInvoer.Rolfout` zegt niet welk van de twee velden botste. Kandidaat voor een uchar-kaart:
  de weigering noemt ook het veld.
- Niet gemeten, uit de code: een relatie waarvan beide richtingen naar-veel zijn, werd stil overgeslagen en is nu
  `NIET_ONDERSTEUND`; een relatie zonder bepaalbaar referentieveld krijgt `relaties[i].rollen.<rol>` in plaats van
  `relaties[i]`; welke link een botsing tussen een inline rol en een relatie noemt, volgt nu de volgorde van de
  instanties; een datum-tijd met een fractie fijner dan een milliseconde is nu 400 `VERKEERD_TYPE`; in `CaseSessie`
  is een `Rolbotsing` geen `NietSpeelbaar` meer, de uitleg zegt dan "speelde de case evenmin uit".
- De secties "Wat kaart G kan schrappen" en "Aanroep vanuit CaseSessie/VerzoekMapper" die de opdracht noemde, staan
  niet in uchars kaart `case-invoer-publiek` op `9159c9bd`; gewerkt van de hoofdtekst en de code.
