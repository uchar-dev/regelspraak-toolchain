# same-conclusion-explanation: de uitleg van een open variant die alleen hetzelfde kan beslissen

*Volgt op uchar's kaart `same-conclusion-open-variant` (besluit 5 daar), in dezelfde pin-bump. Bijgewerkt na de lezing in `werk/neon-review/antwoorden/xhigh-oo-same-conclusion-explanation-card.md`.*

Goal: in een strikte regelset (`Afleiden uit leeg: nee`) leest uchar een default die open is en alleen een constante kan
beslissen die een lagere of neven-default ook vaststaand beslist, als `OPEN`, en die beslissende default als `HOLDS`: de
uitkomst staat vast (RUNTIME-SEMANTICS ch. 1.5, "An open default with a constant"; ch. 5.12). Aan de wortel van een
kenmerkstapel sluit een open "niet …" boven alleen opgevende defaults eveneens vaststaand. De uitleg zegt dat zonder
eigen semantiek, op basis van `DefaultsDecision.open()` uit uchar: de beslissende default is de beslisser met haar
grondslag (`GROUND`); van de open variant zegt de zin dat zij niet vaststaat en alleen hetzelfde kan beslissen (`name(b, v)`
in de bestaande stijl). `openInputs` vraagt geen invoerlid meer dat alleen zo'n onschadelijke variant openhield, en een
voorlopig gefaalde default onder een open constante faalt de uitleg of `openInputs` niet.

Acceptance: HTTP-tests op strikte Jutterswet-regelsets (de vormen van uchar's goldens `jutterswet-zelfde-conclusie-*`):
- een stapel op een kenmerk (met beslisser én sluiting aan de wortel: open "niet …" boven alleen opgevende defaults),
  zowel op zichzelf als naast een losse open supertype-regel en naast invoer `{"state": "holds"}`: bij de besliste en
  de aan de wortel gesloten stapel staat het invoerlid van de open variant **niet** in `openInputs`, en naast een open
  supertype-regel noemt de kop van de uitleg de oorzaak van de supertype-regel;
- een stapel op een attribuut met een constante, en een initialisatie-default onder een open constante (uitleg noemt de
  initialisatie als beslisser met `omdat zijn … in de invoer niet gegeven was` en de open variant apart);
- een ongeordende groep in beide volgordes — open lid vóór de beslisser (`Role.BEFORE`) én **ná** de beslisser
  (`Role.SIBLING`) — én de 3-leden-open-casus `{OPEN 0, HOLDS 0, OPEN 5}` (`havenloon` case 5): `openInputs`, de kop en
  `heldBack` noemen alleen het blokkerende lid (`gemeld`), niet het onschadelijke (`betwist`);
- geneste uitzonderingsgroepen in twee vormen: (a) open uitzonderingsgroep boven een *fall-back* met dezelfde conclusie,
  en (b) ongeordende geneste uitzonderingsgroepen (ook met open lid ná de beslisser);
- een voorlopige fout onder een open constante (`jutterswet-zelfde-conclusie-fout`, zonder `asOfDate`): 200 met open
  uitkomst, `?explanation=true&explanationParts=true` en `?openInputs=true` geven geen 500, de voorlopig gefaalde variant
  staat als `VARIANT{decided=false, unreadable=true}` zonder sessiefout, en `openInputs` noemt alleen `betwist`;
- een rode tegencasus (een open variant die iets anders kan beslissen) houdt de uitkomst open en noemt de lagere die
  `HOLDS` leest **niet** als beslisser;
- `explanationParts` en `GROUND`: de nieuwe zin staat in `Part.Kind.VARIANT` met `decided: false` inclusief scheiding
  `"; "` (zonder weglaatbare stukken blijft de zin een zin); bij vaststaande uitkomst draagt `GROUND`
  `group`/`pointer`/`position`/`variant` van de beslisser (niet van de open variant), en bij wortelsluiting staat er geen
  `GROUND` van een beslisser;
- `OpenInputsPropertyTest` neemt de nieuwe `jutterswet-zelfde-conclusie-*` goldens mee;
- README, `docs/ENGINE.md` en de `VARIANT`-omschrijving in `OpenApiEmitter` beschrijven het;
- de pin `uchar` op de uchar met `same-conclusion-open-variant`, de replay-gelijkheden in `scripts/check-service-replay.sh`
  bijgewerkt (`EXPECTED_REPLAY_PLAYED`, `EXPECTED_REPLAY_CASES`, `EXPECTED_RED_FAULTS`), en `make gates` groen.

May touch: `toolchain/.../engine/Verbaliser.java`, `OpenReads.java`, `ScenarioRunner.java`,
`service/.../DecisionCore.java`, `OpenApiEmitter.java` (omschrijving `VARIANT`), de tests (incl. meeschuivende pinnen in
`OpenExplanationHttpTest`, `OpenExplanationFormsHttpTest`, `OpenInputsHttpTest` en nieuwe goldens in
`OpenInputsPropertyTest`), `scripts/check-service-replay.sh` (de gelijkheden), README, `docs/ENGINE.md`, de pin `uchar`,
deze kaart.

Out of scope: de semantiek (uchar); `Part.java` (geen nieuwe `Part.Kind` nodig); de werkbank.

## Aanleiding en plekken

- **Geen eigen semantiek — `DefaultsDecision.open()`:** uchar stap 3 levert `DefaultsDecision.open()` (de JSON-pointers
  van de `OPEN`-defaults die de stapel openhouden, in stapelvolgorde; leeg bij beslisser, conflict, opgeven of
  kenmerk-wortelsluiting; exclusief voorlopig gefaalde defaults en exclusief onschadelijke `OPEN_ONLY(v)`-leden naast
  `DECIDED(v)` in ongeordende groepen).
- **`OpenReads` + aanroepers (`Verbaliser`, `DecisionCore`, `ScenarioRunner`):** constructor neemt
  `List<DefaultsDecision> decisions` aan; een `ConditionReading` met `status == OPEN` die tot een stapel van `r.object()`
  behoort (gematcht via `d.rootChains()` + `/exceptions/...`-prefix, inclusief volgens ch. 7.10 gevouwen `/rules/rules/...`)
  komt alleen in `open` als `d.open().contains(r.form())`. Losse regels en consistenties blijven ongewijzigd.
- **`Verbaliser`:**
  - `decided`: zowel in `Role.BEFORE` als in `case SIBLING` krijgt een `OPEN`-variant de nieuwe zin in `Part.Kind.VARIANT`
    (`decided: false`, met `"; "`-lijm en `name(b, v)`, bv. `"; " + name(b, v) + " staat niet vast, maar kan alleen
    hetzelfde beslissen"`).
  - `undecided`, `leftOpen`, `firstOpen`: `firstOpen` kiest de eerste variant in `d.open()` (dus `null` bij
    kenmerk-wortelsluiting, zodat `openHead` een losse open supertype-regel kiest, en bij `havenloon` case 5 het
    blokkerende lid `'havenloon bij melding'`).
  - `notDecidedVariants`:
    1. null-guard op `r` (onvoorwaardelijke kenmerkvariant zonder `ConditionReading` → `heldBack` bij `firstOpen != null`);
    2. voorlopig gefaalde `OPEN`-variant: controleer `fault != null` op de **wortelknoop** van voorwaarde of waarde (niet
       `anyMatch` over geneste knopen, om `WV-5.12-open-past-undetermined` te bewaren) → `VARIANT{decided=false,
       unreadable=true}` zonder `notStanding`;
    3. `OPEN`-variant niet in `d.open()`: als `d.open().isEmpty()` (vaststaande stapel, kenmerk-wortelsluiting) de nieuwe
       same-conclusion-zin; als `!d.open().isEmpty()` (open stapel, `havenloon` case 5) gewone `openVariant` zodat er geen
       loshangend `"hetzelfde"` staat terwijl `firstOpen` `'havenloon bij melding'` is.
- **`coverage` en `trace`:** zijn per vorm; de stapel is één vorm. Zeg in het `Result:` expliciet of daar iets verandert.

## Result (2026-11-24)

- **Pin `uchar`:** bijgewerkt naar `4d6bbe6be` (kaart `same-conclusion-open-variant`, met vijf-uitkomsten-stapel,
  `DefaultsDecision.open()`, en de zeven `jutterswet-zelfde-conclusie-*` goldens).
- **Uitleg en `openInputs` zonder eigen semantiek:**
  - `OpenReads` neemt `List<DefaultsDecision>` aan (aangeroepen vanuit `Verbaliser`, `DecisionCore` en `ScenarioRunner`)
    en neemt voor `OPEN`-lezingen van een defaults-stapel alleen vormen op die in `DefaultsDecision.open()` staan
    (losse regels en consistenties ongewijzigd).
  - `Verbaliser`: in `decided` (`Role.BEFORE` en `case SIBLING`) en in `notDecidedVariants` bij lege `d.open()` krijgt
    een open variant de zin `"; " + name(b, v) + " staat niet vast, maar kan alleen hetzelfde beslissen"` in
    `Part.Kind.VARIANT{decided=false}`; `firstOpen` kiest uit `d.open()` (dus `null` bij wortelsluiting zodat een losse
    open supertype-regel de kop draagt, en het blokkerende lid bij 3-leden-groepen zoals `havenloon` case 5); een
    voorlopig gefaalde variant (fout op de wortelknoop van voorwaarde of waarde) wordt `VARIANT{decided=false,
    unreadable=true}` met naam alleen; null-guard op onvoorwaardelijke kenmerkvariant zonder `ConditionReading`.
- **Documentatie en OpenAPI:** `README.md`, `docs/ENGINE.md` en de `VARIANT`-omschrijving in `OpenApiEmitter` bijgewerkt.
- **Tests en replay-pinnen:**
  - `SameConclusionExplanationHttpTest` (7/7 groen) dekt attribuutstapel + rode tegencasus, kenmerkstapel (beslist,
    wortelsluiting, naast open supertype-regel en naast invoer `holds`), ongeordende groepen in beide volgordes (`BEFORE`
    en `SIBLING`) + 3-leden-open-casus, geneste uitzonderingsgroepen in beide vormen, voorlopige fout onder open
    constante (`jutterswet-zelfde-conclusie-fout`, 200 met `unreadable: true`), initialisatie-default onder open constante
    (ch. 7.10-subtypestapel én `jutterswet-zelfde-conclusie-initialisatie/ir.json`), `GROUND`, `explanationParts` en
    `withoutOmissibleParts`.
  - `OpenInputsPropertyTest` uitgebreid met alle zeven `jutterswet-zelfde-conclusie-*` goldens (inclusief `ir.json`-only
    `-initialisatie` en `-fout`).
  - Meeschuivende pinnen bijgewerkt in `OpenExplanationHttpTest`, `OpenExplanationFormsHttpTest` en `OpenInputsHttpTest`.
  - `scripts/check-service-replay.sh`: `EXPECTED_REPLAY_PLAYED=387`, `EXPECTED_REPLAY_CASES=1102`,
    `EXPECTED_RED_FAULTS=68`.
- **`coverage` en `trace`:** `coverage` is ongewijzigd (per vorm `FIRED`/`NOT_FIRED`; een defaults-stapel is één vorm).
  Ook `trace` rapporteert nog altijd één regel per stapelvorm met zijn `labels`; alleen `count` kan bij een strikte
  stapel met een open constante hoger uitvallen omdat lagere defaults onder een open constante nu voorlopig meevuren
  binnen diezelfde vorm (gewone documenten en strikte stapels zonder open constante zijn ongewijzigd).
- **Poorten (`make gates`, 7/7 GROEN in 7m15s):**
  - 1 `pin`: GROEN (`4d6bbe6be` op `origin/main`, versies gelijk)
  - 2 `reactor`: GROEN (`212/653/68`, 0 skips)
  - 3 `twins`: GROEN (3 tweelingen 18 cases, vocabulairetweeling 2 cases / 34 verzoeken, 4 getuigenissen)
  - 4 `replay`: GROEN (387/387 goldens, 1102/1102 cases, 6286 zinnen, 4334 voorwaarden, 3478 berekeningen, 6546
    koppelingen, 0 onbepaalde goldens; rode leg 68/68 fouten)
  - 5 `manifest`: GROEN (32 regels, 29 ok, 3 uitgesloten, 0 mismatch)
  - 6 `image`: GROEN (`decision-service:dev`)
  - 7 `kind-smoke`: GROEN (H2 PASS, H3 PASS, H4 PASS)
