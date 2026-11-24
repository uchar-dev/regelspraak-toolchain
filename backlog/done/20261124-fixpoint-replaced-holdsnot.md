# fixpoint-replaced-holdsnot: het fixpunt toont een vervangen holdsNot-invoer naast de afgeleide rij

*Gemeld vanuit de werkbank, 2026-11-24 (een tweede lezer daar vond het). Gebouwd op 2026-11-24, zie `Result`; landt in dezelfde
toolchain-landing als de pin met uchar's `absent-characteristic-property`, zodat de werkbank één pin krijgt. Vóór de bouw
gelezen door een extern model (xhigh), de punten onderaan verwerkt.*

Goal: geeft een strikt verzoek een kenmerk als `{"state": "holdsNot"}` en kent een regel het kenmerk toch toe, dan toont
de `fixpoint` die vervangen invoer naast de afgeleide rij, zoals hij dat doet voor een kenmerk dat als geldend werd
gegeven (`"input": true`): met `"input": {"state": "holdsNot"}`, de vorm die dezelfde invoer al in `objects[].open` heeft.

Acceptance: een test (HTTP, strikte Jutterswet-regelset) met een kenmerk dat het verzoek als `holdsNot` geeft en een
regel toekent: de rij heeft `"origin": "derived"` en de vervangen invoer; een kenmerk dat als `holds` werd gegeven en een
regel toekent, blijft zoals het is; OpenAPI, README en `docs/ENGINE.md` beschrijven de vorm; `make gates` groen.

May touch: `service/.../FixpointGivens.java` (rond regel 516–521: `entered` kent alleen de geldende kenmerken; rond 260
krijgt `characteristicsOf` `in.absent()` niet mee), `OpenApiEmitter` (de strikte tak), `playground.html` (rond 461), de
tests, README ("Contract change"), `docs/ENGINE.md`, deze kaart.

Out of scope: uchar (de semantiek: een regel die het kenmerk toekent, beslist boven de invoer, RUNTIME-SEMANTICS ch.
1.5); de eindstand van uchar.

## Aanleiding

`FixpointGivens` schrijft per kenmerk een rij met `origin` (`derived` of `input`) en zet `"input": true` waar het kenmerk
afgeleid is én als geldend werd gegeven (`if (a && i) kn.put("input", true)`). Een kenmerk dat het verzoek als `holdsNot`
gaf, staat niet in `entered`; kent een regel het toe, dan toont de rij alleen `derived`, en de lezer van het fixpunt ziet
niet dat de invoer het tegendeel zei.

## Uit de lezing vóór de bouw (extern model, nagerekend) — eisen

- **De vorm ligt vast:** `"input": {"state": "holdsNot"}`, zoals dezelfde invoer al in `objects[].open` staat
  (`FixpointGivens` rond 370, `docs/ENGINE.md` rond 649–653). Het `[…]` in ENGINE.md rond 300–303 is de segmentenlijst van
  een tijdlijnattribuut, geen voorbeeld voor deze rij. `characteristics[].input` is in een strikte regelset dus `true` of
  `{"state": "holdsNot"}`: noem dat als contractwijziging in de README.
- **Alleen in een strikte regelset** (`OpenValues.in(model)`). In een gewone regelset zijn `false` en weglaten gelijk
  (`RequestMapper` laat `false` weg), en `ScenarioData` vult `InputObject.absent` bij "- is niet X" zonder die toets
  (rond 163–170): lees `absent` in `characteristicsOf` alleen in een strikte regelset, anders lekt het naar de gewone
  playground-route. Een tijdsafhankelijk kenmerk blijft buiten schot (strikt draagt geen tijdlijnen; gewoon heeft een
  eigen tak).
- **OpenAPI** alleen in de strikte tak van de beschrijving van `Fixpoint.characteristics` (`OpenApiEmitter` rond
  1034–1044): de zes niet-strikte goldens blijven gelijk; een assertie in `OpenValuesHttpTest`.
- **Playground:** `playground.html` rond 461 schrijft bij elke truthy `input` "(ook invoer)"; bij `{"state": "holdsNot"}`
  hoort "(invoer geldt niet)", zoals de rij `open` rond 468.
- **Randgevallen:** een `holdsNot` die geen regel vervangt, staat niet in `characteristics`; werp een
  `IllegalStateException` als een kenmerk tegelijk geldend en `holdsNot` is gegeven; maak `absent` hoofdletterongevoelig
  (`TreeSet<>(String.CASE_INSENSITIVE_ORDER)`, zoals `characteristics` rond 106).
- **Tests:** naast de HTTP-test een negatieve test voor een gewone regelset (`false` over HTTP, "- is niet X" in de
  playground), en een playground-test over beide routes (gewoon en `?via=service`). `docs/ENGINE.md` noemt ook het
  bestaande `"input": true` (de algemene fixpunt-sectie rond 293–308 doet dat nu niet).

## Result

*Bouwer, 2026-11-24. Op de branch `card/quantifier-filter-in-explanation` (één landing met die kaart, opdracht
`werk/quantifier-filter-in-explanation/OPDRACHT-2.md`); niet gemerged of gepusht. De pin van uchar staat nog op
`2e6319e69`; de pin-bump naar uchar met `absent-characteristic-property` doet de hoofdsessie.*

| commit | wat |
|---|---|
| `4c7709f0d` | claim |
| `fded5afb5` | de rij van het fixpunt met de vervangen `holdsNot`-invoer: `FixpointGivens`, OpenAPI, playground, README, ENGINE, tests |
| (deze) | Result |

- **`FixpointGivens`.** `characteristicsOf` krijgt de kenmerken die de invoer als niet geldend gaf (`InputObject.absent`)
  en schrijft naast een afgeleid kenmerk dat daarbij hoort `"input": {"state": "holdsNot"}`
  (`OpenValues.state(OpenValues.HOLDS_NOT)`, dezelfde vorm als `objects[].open`); naast een kenmerk dat ook als geldend
  gegeven werd, blijft het `"input": true`. `fixpoint` geeft `absent` alleen door in een strikte regelset
  (`OpenValues.in(model)`, één keer gelezen, ook voor `open`): in een gewone regelset zijn `false` en weglaten gelijk, en
  `ScenarioData` vult `absent` bij "- is niet X" ook daar, zonder die toets. Een `holdsNot` die geen regel vervangt, is
  geen rij: het kenmerk geldt niet. Een kenmerk dat tegelijk als geldend en als niet geldend gegeven is, faalt met een
  `IllegalStateException` met het kenmerk en het objecttype. `inputOf` legt `absent` hoofdletterongevoelig vast
  (`TreeSet<>(String.CASE_INSENSITIVE_ORDER)`, zoals de kenmerken); `ScenarioData` deed dat al. Een tijdsafhankelijk kenmerk
  heeft zijn eigen tak, vóór deze, en verandert niet.
- **OpenAPI.** Alleen de strikte tak van de beschrijving van `Fixpoint…characteristics` zegt het nu ("input: true als ook
  de invoer het zette, of {"state": "holdsNot"} als de invoer zei dat het niet geldt en een regel het toch toekent"); de
  gewone beschrijving is byte voor byte gelijk, en geen van de zes openapi-goldens verandert.
- **Playground.** `playground.html` schrijft naast een afgeleid kenmerk met `input.state === "holdsNot"` "(invoer geldt
  niet)", zoals de rij `open`; bij `input: true` blijft het "(ook invoer)". De JavaScript van de pagina is met `node
  --check` gecontroleerd.
- **README** ("Contract change": "A characteristic given as not holding beside its rule", met de twee vormen van `input`)
  en **`docs/ENGINE.md`**: de algemene fixpuntsectie noemt nu ook het bestaande `"input": true` van een kenmerk, de strikte
  sectie de nieuwe rij, de playgroundsectie de twee aanduidingen.
- **Tests.** `OpenValuesHttpTest.the_fixpoint_gives_beside_a_derived_characteristic_the_state_the_request_gave` (strikt,
  HTTP: kostbaar `holdsNot` met taxatie 1500 → `{"characteristic": "kostbaar", "origin": "derived", "input": {"state":
  "holdsNot"}}`; `holds` met 1500 → `"input": true` zoals tot nu toe; `holdsNot` met 50 → geen rij);
  `.in_an_ordinary_rule_set_false_beside_a_derived_characteristic_is_no_input` (dezelfde regelset zonder `Afleiden uit
  leeg: nee`, `"kostbaar": false` → de rij zonder `input`); in `.the_openapi_document_describes_the_states` een assertie op
  de strikte beschrijving; `PlaygroundOpenValuesTest.the_fixpoint_gives_beside_a_derived_characteristic_that_the_situation_gave_as_not_holding`
  (gewoon en `?via=service`: "- is niet kostbaar" met taxatie 1500, PASS, de rij met `{"state": "holdsNot"}`) en
  `.in_an_ordinary_rule_set_the_fixpoint_gives_no_input_beside_a_derived_characteristic_given_as_not_holding` (de gewone
  route van een gewone regelset: de rij zonder `input`). Geen bestaande test veranderd.
- **Gates**: dezelfde run als die van `quantifier-filter-in-explanation` (die kaart, `Result`, "Gates"):

  ```
  gates: 1 pin        GROEN      1s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
  gates: 2 reactor    GROEN   2m48s  testgevallen per module 212/645/68
  gates: 3 twins      GROEN      4s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
  gates: 4 replay     GROEN   1m51s  377 goldens (pin 377), 1053 cases (pin 1053)
  gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
  gates: GROEN — 5 stappen in 4m40s; de uitvoer staat in out/gates/
  ```

- **Open.** Geen. De hoofdletterongevoeligheid van `absent` heeft geen eigen test: `RequestMapper` schrijft de canonieke
  naam in `characteristicsAbsent`, dus de vergelijking is een vangnet.

**Landing (hoofdsessie, 2026-11-24).** Een extern model (xhigh) las het werk: groen, alle eisen gevolgd; de javadoc van
`FixpointGivens.InputObject` noemt nu ook bij `absent` dat het hoofdletterongevoelig is. Geland samen met
`quantifier-filter-in-explanation` op pin uchar `62c393c94`; `make gates` op `3710c4b4d`: 7 stappen groen in 7m06s.
