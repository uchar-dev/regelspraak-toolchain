# openapi-for-callers: het OpenAPI-document bereikbaar, met het commentaar van een declaratie als beschrijving

*Gevraagd door de werkbank-sessie, 2026-11-12, op verzoek van de eigenaar. Gemeten op toolchain `093a2610c` (uchar
`1b42bea65`) met de regelset `v2/herleving-ww` van regelspraak-rules `1e50982`.*

## Aanleiding

Een aanroeper van de dienst kan nergens lezen welke velden een verzoek heeft en wat ze betekenen. `/info` noemt de
entrypoints, de dekking, de parameters en de vocabulaires, maar geen invoervelden. Pas een 400 noemt achteraf een
onbekende naam, met de toegestane waarden.

`OpenApiEmitter.emit(descriptor, model, vocabulary)` schrijft dat contract al: per operatie de invoer-objecttypen en de
uitvoerselectoren, per objecttype de attributen en kenmerken met datatype, eenheid, markers, tijdlijnvorm en dimensies,
de spellingen per vocabulaire (`x-spellings`, `x-valueSpellings`), en de schema's van de 400 en de 422. Geen route en
geen CLI-commando roept de emitter aan; alleen de tests doen dat (`target/openapi/`, de goldens in
`service/src/test/resources/openapi/`). De kaart `20260927-dienst-vocabulaires` noemde een `GET /openapi` of een
`bin/regelspraak openapi` "een eigen kaart" (punt 3 van haar Result); die kaart is er niet gekomen.

De beschrijvingen in het document zeggen de vorm, niet de betekenis ("Objecttype 'X'. Kenmerken
(boolean-eigenschappen): …", "eenheid: …"). De betekenis staat in het commentaar boven een declaratie. De playground
leest dat al: `service/.../http/Declarations.java` neemt de regelcommentaren links van het eerste token van de
declaratie uit de tokenstroom van uchars parser (`getHiddenTokensToLeft`), en `/playground/analyse` geeft ze als
`comment` per element van `vocabularies.elements`. Het OpenAPI-document neemt ze niet mee, en het startpad van `serve`
houdt geen `ParseResult` vast: alleen `Playground` heeft de tokenstroom.

Gemeten op `v2/herleving-ww` (fundament en regelset, 33 bronnen), uit `vocabularies.elements` van de playground: 264
elementen, 168 met commentaar. Per soort: objecttype 18 van 18, attribuut 51 van 65, kenmerk 16 van 23, parameter 7 van
7, feittype 14 van 14, enumeratiewaarde 19 van 44, vorm 43 van 65, rol 0 van 28. Mediaan 1 regel, ten hoogste 6. Elk
van de 168 heeft een `Bron:`-regel met het CGM-element, de wetsartikelen (BWBR-ankers) en de lemma's, soms met een zin
over de betekenis. Het attribuut `aow-datum` van `NatuurlijkPersoon`:

```
Bron: regelset-intern (CGM: geen element; de dag waarop de persoon de pensioengerechtigde leeftijd bereikt, BRM
TP_10000101 "datum waarop een aow leeftijd wordt bereikt"; de leeftijdstabel van AOW art. 7a ligt buiten de scope, dus
invoer) · BWBR0004045&artikel=19&lid=1&o=i · BWBR0019305&artikel=7 · lemma's: aow-datum · bereiken-van-de-aow-leeftijd
```

Directe aanleiding: de descriptor van `v2/herleving-ww` had een `toelichting` bij de dienst en bij het entrypoint, voor
wie de dienst aanroept. De dienst gaf die tekst nooit door, en sinds `3b931461` weigert hij de sleutel;
regelspraak-rules `1e50982` zette de tekst in de README van de regelset.

## Goal

1. **Het document is bereikbaar.**
   - `GET /openapi` op de dienst geeft het document van `OpenApiEmitter.emit`; `?vocabulary=<naam>` of
     `?vocabulary=canonical` kiest de vocabulaire zoals bij een entrypoint, zonder parameter het canonieke document dat
     `emit(descriptor, model)` nu geeft. Een onbekende vocabulaire is een 400 met `UNKNOWN_VOCABULARY`, zoals bij een
     entrypoint.
   - De JDK- en de Quarkus-adapter geven hetzelfde antwoord (één HTTP-contract, een parity-test zoals
     `ErrorParityTest`).
   - `openapi` staat in `RESERVED_PATHS` naast `info` en `playground`: een entrypoint met dat pad start niet.
   - `bin/regelspraak openapi [--vocabulary <naam>] <out.json> <descriptor.service.json> <source.rgs...>` schrijft
     hetzelfde document zonder draaiende dienst.
2. **Het commentaar boven een declaratie is de `description`.** Bij een objecttype in zijn schema, bij een attribuut en
   een kenmerk in zijn property, bij een parameter in de beschrijving van `parameters`. De gegenereerde zin blijft en
   komt erachter, zoals `spellings` (r. 404) een eigen beschrijving al vooraan houdt. De regels van het commentaar
   worden met een spatie samengevoegd. Een declaratie zonder commentaar houdt de beschrijving van nu.
   - Het commentaar komt uit uchar: `RegelSpraakModel.declaration(Designatable)` geeft per element de bronregel en de
     regelcommentaren boven de declaratie (uchar `c24de6c58`; de playground leest het al zo). Het startpad van `serve`
     en de CLI houden het opgeloste model vast en lezen het daaruit; geen eigen tokenstroom.
   - Bij `serve <document.json>` (een IR-document als bron) is er geen tokenstroom en dus geen commentaar; het document
     is dan zoals nu, en `docs/ENGINE.md` zegt dat.
   - Enumeratiewaarden, feittypen, rollen en vormen vallen buiten deze kaart.

## Besluiten (2026-11-13, de eigenaar liet de kaart bouwen met de voorkeuren hieronder)

- **Letterlijk.** Het commentaar komt letterlijk in de `description`, met de `Bron:`-regels: een aanroeper krijgt de
  herkomst (CGM-element, wetsartikel, lemma) en waar die er staat ook de betekenis.
- **Uit uchars model, niet uit de IR.** `model.declaration` levert het commentaar bij een bron; bij `serve
  <document.json>` is er geen commentaar en `docs/ENGINE.md` zegt dat. Een uchar-kaart voor commentaar in de IR pas als
  een IR-gestarte dienst het nodig heeft.
- **Geen `description` in de descriptor** op deze kaart; dat is een eigen kaart als de eigenaar haar wil.

## Acceptance

- HTTP-tests op beide adapters: `GET /openapi` gelijk aan het document van `emit`, met en zonder `?vocabulary=`, en een
  400 bij een onbekende vocabulaire.
- Een descriptor met een entrypoint op pad `openapi` start niet, met dezelfde schending als bij `info`.
- `bin/regelspraak openapi` schrijft byte voor byte het document van de route.
- Een golden met commentaar boven een objecttype, een attribuut, een kenmerk en een parameter: hun `description` begint
  met dat commentaar. De bestaande goldens veranderen alleen in `description`, en alleen waar hun bronnen commentaar
  boven een declaratie hebben.
- README en `docs/ENGINE.md` beschrijven de route, het commando en de herkomst van `description`.
- Een meting op `v2/herleving-ww` van regelspraak-rules: `GET /openapi` geeft bij `beoordeel-herleving` voor
  `aow-datum` het commentaar hierboven.
- `make gates` groen.

Result (2026-11-13):
- `GET /openapi` on both adapters through one function (`ServiceHttp.openapi`), with `?vocabulary=<naam>`/`canonical`
  and a 400 `UNKNOWN_VOCABULARY`; `openapi` is a reserved path; `bin/regelspraak openapi` writes the same bytes
  (`OpenApiRouteTest`, `OpenApiParityTest`).
- The comment above an object type, an attribute and a characteristic opens its `description` letterlijk, the
  generated sentence follows; a parameter's comment opens its line in the description of `parameters`
  (`OpenApiCommentTest`, goldens `jutterswet-commentaar` canonical and handboek). A service started on an IR
  document has no comment. The existing OpenAPI goldens did not change (their sources have no comment above a
  declaration).
- Measured on `v2/herleving-ww` (regelspraak-rules `1e50982`, in the werkbank's submodule): the description of
  `aow-datum` at `beoordeel-herleving` starts with its `Bron:` comment; route and command byte-equal.
- Provisional: parameters get no property of their own; the route sends the emitter's indented bytes so the command
  matches it; the document does not describe `GET /openapi` itself; `openapi` builds as `serve` with markers allowed.
- `make gates` green (replay 373/1042).
