# open-inputs-skip-settled-opgave-checks: openInputs slaat opgave-controleleden over zodra het doelattribuut vaststaat

*Gemeld vanuit de werkbank, 2026-11-25; uitgewerkt tegen `regelspraak-toolchain` `fadd713a5` (`uchar` `4d6bbe6be`).*

Goal: `?openInputs=true` (en de `openInputs` van de playground, zowel met als zonder `?via=service`) meldt geen invoerleden voor een gegenereerd opgave-controlelid (`berekende X` of `afwijkende opgave X`, met `generatedFrom` = `OriginForm.OPGAVE_BOVEN_BEREKENING` op doelattribuut `X`) wanneer dat doelattribuut `X` zelf op dezelfde instantie al vaststaat (niet open is, bijv. omdat `opgegeven X` gegeven is). De uitvoer (`instances` met `"state": "open"` op het controlelid), `inconsistencies` en `explanation` blijven ongewijzigd; ook een andere echte open uitkomst die via een regel van `berekende X` of `afwijkende opgave X` afhangt, blijft haar open invoer gewoon melden. De toolchain leidt dit uitsluitend af uit uchars `RegelSpraakModel.generatedFrom` en `EndState`, nooit uit de naam.

Acceptance:
- Jutterswet-tests in `service` (`OpenInputsHttpTest`, `PlaygroundOpenValuesTest` en `OpenInputsPropertyTest`) en eventueel `toolchain` dekken alle volgende gevallen:
  1. **Opgave gegeven, berekening open (`opgegeven kadegeld = 100`, `lengte` niet gegeven):** met `"Wrak.kadegeld"`, `"Wrak.berekende kadegeld"` en `"Wrak:afwijkende opgave kadegeld"` in `output`:
     - `instances` geeft `kadegeld` als `{"state": "filled", "value": 100}`, en `berekende kadegeld` en `afwijkende opgave kadegeld` als `{"state": "open"}`;
     - `explanation` blijft de open zinnen voor `berekende kadegeld` en `afwijkende opgave kadegeld` geven;
     - `openInputs` is `[]` (noemt `lengte` niet, omdat `kadegeld` zelf al vaststaat).
  2. **Opgave niet gegeven, berekening open (`opgegeven kadegeld` en `lengte` niet gegeven):**
     - `kadegeld` zelf staat open (`"state": "open"`);
     - `openInputs` noemt `W1 lengte` (met `W1 kadegeld` — en `W1 berekende kadegeld` voor zover in `output` — in `forOutputs`).
  3. **Opgave gegeven, berekening vaststaand en afwijkend (`opgegeven kadegeld = 100`, `lengte = 10`):**
     - `kadegeld = 100`, `berekende kadegeld = 30`, `afwijkende opgave kadegeld = holds`, `inconsistencies` bevat `"controle opgave kadegeld"`, `openInputs` is `[]`.
  4. **Geërfd attribuut (subtype):** doelattribuut `X` met `(mag opgegeven worden)` gedeclareerd op supertype, instantie van subtype, `opgegeven X` gegeven en berekening open → `berekende X` en `afwijkende opgave X` worden ook voor het subtype overgeslagen in `openInputs`.
  5. **Echte uitkomst die zelf `berekende X` of `afwijkende opgave X` leest:** een niet-gegenereerde uitkomst `Y` in `output` waarvan een regel `berekende X` of `afwijkende opgave X` leest (terwijl `opgegeven X` gegeven is en `X` vaststaat) → `Y` staat open en `openInputs` volgt wél door `berekende X`/`afwijkende opgave X` heen naar het ontbrekende invoerlid, met `Y` in `forOutputs` (en zonder `berekende X`/`afwijkende opgave X` in `forOutputs`).
  6. **Geen `X` in `output`, wél `berekende X` in `output`:** is `opgegeven X` niet gegeven (`X` open op de instantie), dan noemt `openInputs` het ontbrekende invoerlid voor `berekende X`; is `opgegeven X` wél gegeven (`X` vast op de instantie), dan slaat `openInputs` `berekende X` over.
  7. **Playground (beide routes):** `PlaygroundOpenValuesTest` toetst op een scenario met `(mag opgegeven worden)` en een gegeven opgave dat `openInputs` zowel zonder als met `?via=service` de invoerleden die alleen het opgave-controlelid openhouden weglaat, terwijl de uitleg ze nog noemt.
  8. **Property-test (`OpenInputsPropertyTest`):** werkt de invariant bij zodat open uitkomsten precies de vereniging zijn van (a) de uitkomsten in `openInputs` (elk veranderbaar door elk genoemd invoerlid) en (b) de onderdrukte opgave-controleleden waarvan het doelattribuut op dezelfde instantie niet `"open"` is (en toetst expliciet dat geen enkel lid uit (b) in `openInputs` staat, inclusief op een regelset met `(mag opgegeven worden)` én `berekende X`/`afwijkende opgave X` in `output`).
- `README.md` (onder `Contract change`) en `docs/ENGINE.md` (onder `?openInputs=true`) documenteren dat `openInputs` gegenereerde opgave-controleleden (`berekende X`, `afwijkende opgave X`) overslaat wanneer het gemarkeerde attribuut `X` op die instantie al vaststaat.
- `make gates` is 7/7 groen (tijdens bouw minimaal `make gates-no-docker` 5/5 groen; bij landing volledige `make gates` 7/7 groen).

May touch:
- `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/OpenReads.java`
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/OpenValuesSource.java` (of een gerichte bron in de test)
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/OpenInputsHttpTest.java`
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/PlaygroundOpenValuesTest.java`
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/OpenInputsPropertyTest.java`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261125-open-inputs-skip-settled-opgave-checks.md` → `doing/` → `done/`)

Out of scope:
- `uchar/` (geen semantiekwijziging, geen pin-bump)
- `AnswerBuilder.java` (`instances` houdt `"state": "open"` op `berekende X` en `afwijkende opgave X` wanneer zij in `output` staan)
- `Verbaliser.java` (`explanation` en `explanationParts` blijven ongewijzigd)
- `OpenApiEmitter.java` (geen schemawijziging op `openInputs`)
- de werkbank en `regelspraak-rules`

## Aanleiding en plekken

Een attribuut met `(mag opgegeven worden)` laat uchars frontend drie leden genereren met `OriginForm.OPGAVE_BOVEN_BEREKENING` en bron `Designatable.attribute(owner, X)`: `opgegeven X`, `berekende X` en het kenmerk `afwijkende opgave X`, plus de groep `opgave boven berekening X`, de regel `afwijking opgave X` en de consistentieregel `controle opgave X` (dialect §7).

Wanneer een dienst `berekende X` en `afwijkende opgave X` in `output` opneemt (zodat een afnemer of assistent bij een *aantoonbare* afwijking naast `inconsistencies` de berekende waarde ziet) — en altijd op de playground zonder `?via=service` — ontstaat in een strikte regelset (`Afleiden uit leeg: nee`) ruis in `openInputs` zodra `opgegeven X` gegeven is en de invoer van de berekening ontbreekt:
- In `v2/herleving-ww` scenario `s01` is `U1 opgegeven uitkeringsduur = 18 mnd` gegeven. `U1 uitkeringsduur` staat daarmee vast op `18,0 mnd` (en `U1 maxdatum` en `R1 maxdatum` op `30-06-2026`).
- Toch meldt `openInputs` vijf ontbrekende `U1`-invoerleden (`aantal-jaar-feitelijk arbeidsverleden`, `… t m 2015`, `… fictief`, `jareneis`, `indicatie ao …`), elk met uitsluitend `forOutputs: [U1 berekende uitkeringsduur, U1 afwijkende opgave uitkeringsduur]`.
- Geen van die vijf velden kan de uitkomst `U1 uitkeringsduur` of enige vervolgbeslissing nog veranderen: ze houden alleen de optionele opgave-afwijkingscontrole open.

Beide routes van `openInputs` lopen door één enkele methode:
- Beslisservice en playground `?via=service`: `DecisionCore.java:861` → `OpenInputs.of(...)` (`service/src/main/java/dev/uchar/regelspraak/toolchain/service/OpenInputs.java:34-47`) → `reads.openInputs(instances, outcomes)`.
- Playground zonder `?via=service`: `ScenarioRunner.java:259` → `reads.openInputs(instances, reads.derived(instances))` → `Playground.java:353`.

### Ontwerp en randgevallen

- **Eén plek (`OpenReads.openInputs`, `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/OpenReads.java:256-267`):**
  Vóór het volgen van een open `outcome` in de lus over `outcomes`: controleer met een helper `settledOpgaveCheck(outcome)`:
  1. Bepaal de gedeclareerde `Designatable` van `outcome` via `ObjectType type = typeOf(outcome.object())` en `outcome.characteristic() ? Naming.characteristicOf(type, outcome.member()) : Naming.attributeOf(type, outcome.member())` (loopt de supertypeketen op, dus werkt ook op subtypen).
  2. Vraag `model.generatedFrom(decl)` op (zoals `Verbaliser.java:1654` en `3312` dat al doen voor vormen — zonder op namen te matchen).
  3. Is `generatedFrom` aanwezig met `g.form() == OriginForm.OPGAVE_BOVEN_BEREKENING` en `g.source().kind() == Designatable.Kind.ATTRIBUUT`, controleer dan of het doelattribuut `g.source().name()` op `outcome.object()` al vaststaat: `open(outcome.object(), g.source().name(), false) == null`.
  4. Zo ja: sla `outcome` over (`continue`).
- **Niet filteren binnen `follow(...)` (`OpenReads.java:293-301`):**
  Als een andere, niet-onderdrukte uitkomst `Y` zelf openstaat omdat een regel `berekende X` of `afwijkende opgave X` leest, loopt `follow` gewoon door `berekende X` / `afwijkende opgave X` naar de onderliggende invoerleden en meldt die voor `Y`.
- **`opgegeven X` zelf:** `opgegeven X` is `(volledig gegeven)` en daardoor nooit open (tenzij een initialisatie haar openlaat, wat voor `opgegeven X` niet gebeurt); als extra zuiverheid geldt de controle voor elk lid met `OriginForm.OPGAVE_BOVEN_BEREKENING` waarvan het doelattribuut niet open is (`opgegeven X` is sowieso niet open).
- **Consistenties:** `controle opgave X` is een consistentieregel, geen lid in `outcomes`, en raakt `openInputs` al niet.
- **Bestaande tests en `OpenInputsPropertyTest`:**
  - `OpenValuesSource.WRAK_DESCRIPTOR` (`OpenValuesSource.java:304-309`) heeft nu alleen `"Wrak.kadegeld"` in `output`, niet `berekende kadegeld` of `afwijkende opgave kadegeld`; geen bestaande test in `OpenInputsHttpTest` breekt.
  - `OpenInputsPropertyTest.holds` (`OpenInputsPropertyTest.java:129`) controleert `assertEquals(open, inputs.keySet())`. Als een opgave-regelset (zoals een golden of `WRAK_SOURCE` met `berekende kadegeld` / `afwijkende opgave kadegeld` in `output`) meeloopt, moeten onderdrukte opgave-controleleden daar expliciet buiten `inputs.keySet()` vallen en juist op afwezigheid in `inputs.keySet()` getoetst worden.
  - Replay-goldens (`scripts/check-service-replay.sh`): raken `openInputs` niet (of alleen ten goede als een golden `(mag opgegeven worden)` gebruikt); `make gates` toetst alle 7 poorten.

## Result (2026-11-25)

- **`openInputs` zonder eigen semantiek of naammatching (`OpenReads`):**
  - `OpenReads.openInputs` slaat een open uitkomst over (`settledOpgaveCheck`) wanneer `model.generatedFrom(decl)` een herkomst `OriginForm.OPGAVE_BOVEN_BEREKENING` met bron `Designatable.Kind.ATTRIBUUT` geeft, dat bronattribuut `X` daadwerkelijk op de overervingsketen van het object ligt (`hasAttribute` onder `Namen.norm`), én `X` zelf op dat object al vaststaat (`open(object, X, false) == null` op `EndState`).
  - Beide routes van `openInputs` — de beslisdienst (`?openInputs=true` en `?via=service` via `OpenInputs.of`) en de gewone playground/scenarioloper (`ScenarioRunner` via `reads.derived`) — lopen door deze ene plek.
  - `follow(...)` filtert niet: leest een andere open uitkomst `Y` zelf `berekende X` of `afwijkende opgave X`, dan volgt `openInputs` nog altijd door het controlelid heen naar het ontbrekende invoerlid voor `Y` (zonder `berekende X`/`afwijkende opgave X` in `forOutputs`). `instances`, `inconsistencies` en `explanation` blijven ongewijzigd.
- **Tests (Jutterswet) en documentatie:**
  - `OpenInputsHttpTest`: alle zes HTTP-gevallen (opgave gegeven met open berekening en behoud van uitleg op zowel `.rgs` als `uchar-ir-v1`; opgave niet gegeven; afwijkende berekening met `controle opgave kadegeld`; geërfd gemarkeerd attribuut op subtype `Wrak`; echte uitkomsten die `berekende kadegeld` of `afwijkende opgave kadegeld` lezen, inclusief een door de auteur gedeclareerde `berekende kadeboete` zonder `generatedFrom` en een vocabulaire `?vocabulary=havenboek`; een entrypoint zonder `X` in `output`), plus een `uchar-ir-v1`-getuige waarin `origin.from` naar een attribuut buiten de overervingsketen wijst (afwezig én overschaduwd door een gelijknamig eigen attribuut).
  - `PlaygroundOpenValuesTest`: beide routes van de playground (gewoon en `?via=service`) op scenario's met `(mag opgegeven worden)` met en zonder gegeven opgave.
  - `OpenInputsPropertyTest`: de invariant toetst via `fixpoint` dat open uitkomsten precies de disjuncte vereniging zijn van (a) `inputs.keySet()` en (b) de onderdrukte opgave-controleleden waarvan het doelattribuut op de keten van de instantie ligt en niet open is, dat geen lid van (b) in `openInputs` staat, en dat elk geschreven open lid (ook uit (b)) een open uitlegzin houdt — inclusief `WRAK_SOURCE` met `berekende kadegeld` en `afwijkende opgave kadegeld` in `output` (met én zonder `Wrak.kadegeld`), en `total.suppressed() > 0` op zowel de strikte goldens (`jutterswet-volledig-gegeven`) als de testregelsets.
  - `README.md` (`Contract change`) en `docs/ENGINE.md` (`?openInputs=true`) bijgewerkt.
- **Buiten scope gelaten:** `uchar/` (pin ongewijzigd op `4d6bbe6be`), `AnswerBuilder.java`, `Verbaliser.java`, `OpenApiEmitter.java`, de werkbank en `regelspraak-rules`.
- **Poorten (`make gates`, 7/7 GROEN in 7m56s):**
  - 1 `pin`: GROEN (`4d6bbe6be` op `origin/main`, versies gelijk)
  - 2 `reactor`: GROEN (`215/658/68`, 0 skips)
  - 3 `twins`: GROEN (3 tweelingen 18 cases, vocabulairetweeling 2 cases / 34 verzoeken, 4 getuigenissen)
  - 4 `replay`: GROEN (387/387 goldens, 1102/1102 cases, 6286 zinnen, 4334 voorwaarden, 3478 berekeningen, 6546 koppelingen, 0 onbepaalde goldens; rode leg 68/68 fouten)
  - 5 `manifest`: GROEN (32 regels, 29 ok, 3 uitgesloten, 0 mismatch)
  - 6 `image`: GROEN (`decision-service:dev`)
  - 7 `kind-smoke`: GROEN (H2 PASS, H3 PASS, H4 PASS)
