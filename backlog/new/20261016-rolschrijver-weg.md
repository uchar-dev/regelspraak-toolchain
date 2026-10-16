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
