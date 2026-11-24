# fixpoint-replaced-holdsnot: het fixpunt toont een vervangen holdsNot-invoer naast de afgeleide rij

*Gemeld vanuit de werkbank, 2026-11-24 (een tweede lezer daar vond het). Nog niet begonnen; landt in dezelfde
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
