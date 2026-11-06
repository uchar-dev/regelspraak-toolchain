# creation-value-wording: de uitleg noemt de waarde uit een objectcreatie met de woorden van de specificatie

*Opgesteld door de werkbank-sessie op 2026-11-06, na `writer-holds-on-no-day` (toolchain `eb912c57d`). Besluit van de
eigenaar, 2026-11-06.*

## Aanleiding

Sinds `timeline-stack-in-service` noemt de uitleg de waarde die een objectcreatie een attribuut van het gemaakte object
geeft "de waarde bij het maken" (`Verbaliser.standingValue` en `emptyBefore`), naast "de invoerwaarde" en "in de
invoer leeg was" op een object uit het verzoek. Het woord is een vertaling van uchars "the value of its making"
(RUNTIME-SEMANTICS ch. 7.8); waarom juist deze woorden, zegt geen kaart. Een lezer zonder die achtergrond begrijpt
"bij het maken" niet, ook de werkbank niet toen zij het in haar objectboom overnam.

Een onderzoek naar het woordgebruik (werkbank, 2026-11-06) vond geen standaardwoord voor deze waarde. De
RegelSpraak-specificatie v2.3.0 noemt het construct ObjectCreatie (§4.3, §9.3, §13.4.6) en elk lid een
waardetoekenning; beginwaarde, startwaarde, initiële waarde, standaardwaarde en defaultwaarde komen er niet in voor.
ALEF zegt "Objectcreatie", kort "creatie", en "Tijdens creatie kan aan attributen een waarde worden toegekend"
(`ALEF/docs/regels/Actie_Objectcreatie.md`). In BRM, de handboeken en ALEF is een begin- of standaardwaarde een
terugvalwaarde voor een leeg gegeven: de rol van de initialisatie, die in dezelfde zin kan staan. "Aanmaken"
onderscheidt de creatie niet, want ook een object uit de invoer wordt aangemaakt (§3.2, het onderschrift van figuur 2).

Besluit van de eigenaar (2026-11-06): de uitleg bouwt de naam uit de twee woorden die wel standaard zijn, het construct
en het werkwoord. "De waarde bij het maken" wordt "de bij de objectcreatie toegekende waarde", en "bij het maken leeg
was" wordt "bij de objectcreatie leeg was".

## Goal

- De uitleg schrijft op een object dat een objectcreatie maakte:
  - `"…; de bij de objectcreatie toegekende waarde 10 van 01-01-2025 tot 01-03-2025 bleef staan"`, waar zij `"de waarde
    bij het maken 10 …"` schreef;
  - `"… houdt als meldwaarde de bij de objectcreatie toegekende waarde 7, want …"`, waar zij `"de waarde bij het maken
    7"` schreef;
  - `"…, omdat de aanvulloon bij de objectcreatie leeg was"`, waar zij `"bij het maken leeg was"` schreef;
  in elke zinsvorm waarin `standingValue` of `emptyBefore` staat: een doel met één waarde, een cel, per periode en de
  zin van een defaults-groep. Op een object uit het verzoek blijven `de invoerwaarde` en `in de invoer leeg was`.
- De playground schrijft de `input` van een gemaakt object als `(creatie …)`, naast `(invoer …)` op een instantie van
  het verzoek, zoals de objectboom van de werkbank; de OpenAPI-beschrijving van `inputOrigin` zegt "creation naast de bij
  de objectcreatie toegekende waarde".
- De Engelse identifiers (`CaseSession.making`, `standingValue`) en de JSON-waarden (`origin: "creation"`,
  `inputOrigin: "creation"`) blijven; de werkbank leest die waarden.
- README, `docs/ENGINE.md` en het commentaar in `scripts/check-service-replay.sh` als huidige stand, zonder "where it
  said …". Een plain `git grep 'bij het maken'` buiten `backlog/done/` vond op `eb912c57d` 34 regels in 12 bestanden;
  tel ook de zinsdelen die over een regeleinde lopen.

## Acceptance

- (a) Rood eerst: de tests die "bij het maken" pinnen (`ExplanationStackPerDayTest`, `TimelineStackHttpTest`) gaan naar
  de nieuwe tekst, en falen op main. Het is een expliciete gedragswijziging van deze kaart: noem in het Result elke
  test bij naam met oud en nieuw. Geen andere verwachting verandert.
- (b) `git grep 'bij het maken'` buiten `backlog/done/` vindt niets meer, ook niet over een regeleinde.
- (c) Over de goldens die de replay speelt verschilt de uitleg alleen in deze zinsdelen; het antwoord en het
  `fixpoint` zijn byte-gelijk.
- (d) De OpenAPI-goldens die de beschrijving van `inputOrigin` dragen, gaan mee.
- (e) `make gates` groen.

May touch: `toolchain/` (`Verbaliser`), `service/` (`OpenApiEmitter`, `playground.html`, tests en OpenAPI-goldens),
`scripts/check-service-replay.sh`, README, `docs/ENGINE.md`, kaartverplaatsing.

## Omvang

Klein.

## Result

*Hoofdsessie, 2026-11-06, direct op `main` van de toolchain, samen met `marker-on-value-part`. Pin ongewijzigd (uchar
`74119a23e`). Scripts en uitkomsten van de vergelijking onder `werk/creation-value-and-marker/` (buiten de repo):
`scripts/dump_replay.py` (de replay-driver van de poort, met `--detail` en per verzoek met uitleg een tweede met
`fixpoint=true`, die elk antwoord wegschrijft), `scripts/compare.py`, `scripts/set.sh` (de set van de replay-poort),
`logs/compare-old-new.txt`.*

### Commits

| hash | inhoud |
|---|---|
| `dd985d80e` | beide kaarten naar `doing/` |
| `4285649a5` | Uitleg: de bij de objectcreatie toegekende waarde (`Verbaliser`, playground, `OpenApiEmitter`, `FixpointGivens`, tests, OpenAPI-goldens, README, `docs/ENGINE.md`, `scripts/check-service-replay.sh`) |
| `ad0a4f20f` | bijvangst: het fixpunt geeft een tijdsafhankelijk kenmerk met zijn perioden (hieronder) |
| (deze) | Result; de kaart naar `done/` |

### Wat er veranderde

- `Verbaliser.standingValue` schrijft op een gemaakt object `de bij de objectcreatie toegekende waarde`,
  `Verbaliser.emptyBefore` ` bij de objectcreatie leeg was`; op een object uit het verzoek blijven `de invoerwaarde`
  en ` in de invoer leeg was`. Beide methoden zijn de enige bron van die woorden, dus elke zinsvorm volgt: een doel met
  één waarde, een cel, per periode en de zin van een defaults-groep.
- De playground schrijft de `input` van een gemaakt object als `(creatie …)`. De OpenAPI-beschrijving van `values`
  zegt nu `origin (input, derived of creation: op een gemaakt object de bij de objectcreatie toegekende waarde), input
  (…, op een gemaakt object de bij de objectcreatie toegekende waarde), inputOrigin (creation naast de bij de
  objectcreatie toegekende waarde)`. De eigen melding in `FixpointGivens.madeValues` zegt `heeft geen bij de
  objectcreatie toegekende waarden van vóór de terugzetting`.
- Blijft: de identifiers (`CaseSession.making`, `standingValue`, `madeValues`), de JSON-waarden `origin: "creation"`
  en `inputOrigin: "creation"`, en het Engelse proza "the value of the making" in README, `docs/ENGINE.md` en het
  commentaar: dat is uchars term (RUNTIME-SEMANTICS ch. 7.8), en de kaart vraagt alleen het Nederlands. Zie het open
  punt.

### Acceptatie

- **(a) Rood eerst, en de gewijzigde tests bij naam.** Op `dd985d80e` (main, de oude code) met de nieuwe
  verwachtingen faalden over de volledige suites van `toolchain` (191) en `service` (493) precies zes tests, en geen
  andere:
  - `ExplanationStackPerDayTest.on_a_made_object_the_value_of_the_making_stands_per_period_and_the_initialisation_fills_what_it_left`:
    `de waarde bij het maken 10` (drie keer) → `de bij de objectcreatie toegekende waarde 10`, `omdat de aanvulloon bij
    het maken leeg was` → `omdat de aanvulloon bij de objectcreatie leeg was`;
  - `ExplanationStackPerDayTest.on_a_made_object_with_one_value_the_value_of_the_making_is_named_so`: `de waarde bij
    het maken 7`, `4` en `9` → `de bij de objectcreatie toegekende waarde 7`, `4` en `9`, `omdat de opslag bij het
    maken leeg was` → `omdat de opslag bij de objectcreatie leeg was`;
  - `ExplanationStackPerDayTest.on_a_made_object_a_cell_keeps_the_value_of_the_making`: `houdt als meldwaarde in
    najaar de waarde bij het maken 7` → `… de bij de objectcreatie toegekende waarde 7`;
  - `ExplanationStackPerDayTest.a_defaults_group_on_a_made_object_names_the_value_of_the_making_in_the_period_no_variant_decided`:
    `houdt als toelage de waarde bij het maken 10` → `… de bij de objectcreatie toegekende waarde 10`;
  - `ExplanationStackPerDayTest.the_initialisation_of_a_stack_on_a_made_object_names_the_making`: `omdat de toelage
    bij het maken leeg was` → `omdat de toelage bij de objectcreatie leeg was`; in het testdocument heet het scenario
    `Scenario zonder toelage bij de objectcreatie` (was `… bij het maken`), zodat (b) ook daar niets vindt;
  - `TimelineStackHttpTest.the_explanation_of_a_made_object_names_the_value_of_the_making_per_period`: de drie zinnen
    van het gemaakte object, dezelfde twee vervangingen.
  De namen van de testmethoden blijven (Engelse identifiers).
- **(b)** `git grep 'bij het maken'` buiten `backlog/done/` vindt niets; een zoekopdracht over regeleinden heen
  (`bij`, `het` en `maken` gescheiden door witruimte, `+ "`, `*`, `//` of `#`; dezelfde zoekopdracht vond op main de
  gesplitste plekken in `Verbaliser`, `FixpointGivens`, `OpenApiEmitter` en `docs/ENGINE.md`) vindt ook niets.
- **(c)** De 347 goldens van de replay-poort (de set van uchars gate 12; 338 spelen, 9 uitgesloten, gelijk met de jar
  van `dd985d80e` en die van `99ac2e411`), 2913 verzoeken: elk antwoord en elk `fixpoint` byte-gelijk, en de uitleg
  verschilt alleen in de twee zinsdelen: 10 van de 5075 zinnen, in drie goldens (`jutterswet-creatie-per-dag`,
  `jutterswet-invoer-naast-objectcreatie`, `jutterswet-afwezigheidsmarkers-creatie`). Voorbeelden: `«"meld de
  vangst"»(J1)#1 houdt als waarde de bij de objectcreatie toegekende waarde 7, want de regel 'waarde uit toeslag' gaf
  geen waarde …`; `«"onbekende vondst"»(J1)#1 houdt als taxatie de bij de objectcreatie toegekende waarde onbekend,
  want geen regel gold; …`; `«"meld de jutter"»(J2)#1 heeft als aanvulloon altijd 5 volgens de regel
  'standaardaanvulloon', omdat de aanvulloon bij de objectcreatie leeg was`.
- **(d)** De vier OpenAPI-goldens (`jutterswet-vocabulaires` canoniek, `handboek`, `cgm`, `toka-belastingdienst`)
  dragen de nieuwe beschrijving van `values`.
- **(e)** `make gates`: zie onder.

### Bijvangst: het fixpunt en een tijdsafhankelijk kenmerk (`ad0a4f20f`)

De vergelijkingsrun vroeg ook `?fixpoint=true` en vond een bestaande fout, op main net zo als met de nieuwe code:
op `jutterswet-verzuimrente-30-360-afgerond` en `time-window-timeline-characteristic` gaf de detailmodus een 500
(`IllegalStateException: het fixpunt geeft het attribuut 'rentedragend', dat het objecttype 'Afdracht' niet
declareert`). uchar zet een tijdsafhankelijk kenmerk dat een regel binnen een venster schrijft onder de afgeleide
waarden, als zijn ware perioden (`Fixpoint.DerivedValue`, RUNTIME-SEMANTICS ch. 3.10, 3.11), en `FixpointGivens`
kende dat niet. Daarnaast liet het fixpunt de perioden van de invoer van zo'n kenmerk stil weg
(`anchor/jutterswet-dubbelwacht`: J1 heeft `borgplichtig` in de invoer, het fixpunt gaf `characteristics: []`).
Gerepareerd: een tijdsafhankelijk kenmerk krijgt een rij met `periods`, `[{"from", "to"}]` zoals het verzoek ze geeft,
de perioden die een regel schreef (`origin: "derived"`) of anders die van de invoer (`origin: "input"`);
`FixpointGivens.InputObject` draagt de invoerperioden, ook vanuit een scenario (`ScenarioData`). uchar weigert
invoerperioden op een kenmerk dat een regel schrijft (`CHARACTERISTIC_PERIODS_FAULT`), en dan schrijft de regel niets;
een rij met beide faalt daarom luid in plaats van een vorm te krijgen. Getuige `FixpointCharacteristicPeriodsHttpTest`
(drie tests; de eerste was rood met de 500, de tweede met de ontbrekende rij). Over de replay-set, jar van `99ac2e411`
tegen die van `ad0a4f20f`: de vier 500's worden 200, 63 fixpunten in 26 goldens krijgen alleen rijen met `periods`
erbij, geen rij verdwijnt en niets anders verandert. De playground toont de perioden zoals bij de situatie; de
OpenAPI-beschrijving van `characteristics`, README en `docs/ENGINE.md` noemen ze (de OpenAPI-goldens gingen mee).

### Open punt

1. Het Engelse "the value of its making" komt uit uchar (RUNTIME-SEMANTICS ch. 7.8; 54 regels in spec en code
   gebruiken "making" in die zin) en staat hier nog in README, `docs/ENGINE.md` en commentaar. Of uchar het vervangt, ligt bij de
   eigenaar (vraag van 2026-11-06); volgt uchar, dan gaat het Engelse proza hier mee bij de pin-bump.

### Gates

`make gates` op `ad0a4f20f`, 2026-11-06:

```
gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
gates: 2 reactor    GROEN   2m23s  testgevallen per module 194/496/54
gates: 3 twins      GROEN      4s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m51s  338 goldens, 964 cases (pin 338)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     35s  decision-service:dev
gates: 7 kind-smoke GROEN   1m16s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 6m05s; de uitvoer staat in out/gates/
```
