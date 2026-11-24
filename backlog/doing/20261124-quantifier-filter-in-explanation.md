# quantifier-filter-in-explanation: de uitleg van een kwantorvouw met een open filter, exact

*Opdracht van de eigenaar, 2026-11-24: vervolgpunt van `one-empty-in-service`. Volgt op uchar's kaart
`contributor-filter-verdict` (zelfde pin-bump). Vóór de bouw gelezen door een extern model (xhigh), de punten hieronder
verwerkt. Gebouwd op 2026-11-24, daarna een herstelronde na een review door een extern model (xhigh) en een besluit van de
eigenaar; zie `Result`.*

Goal: de uitleg van een kwantorvouw waarvan het filter op een lid een open waarde leest, noemt de leden en dragers weer,
met per lid of zijn filter vaststaat, uit wat uchar's lezing zegt (`ConditionReading.Contributor` met het filteroordeel),
zonder eigen oordeel van de toolchain.

Acceptance: `OpenReads.reads` (de benadering van het filter over knopen en koppelingen) vervalt; de twee gevallen van de
review van `one-empty-in-service` ("alle" met V1 filter waar/matrix waar en V2 filter "A of B" met A waar en B
open/matrix onwaar; "ten minste 2") en een lid met een onbepaald filter geven een ware uitleg met leden, in
`OpenExplanationFormsHttpTest` en `OpenExplanationHttpTest`; de OpenAPI-beschrijving van `MEMBERS`, README en
`docs/ENGINE.md` zeggen het; `make gates` groen op de nieuwe pin.

May touch: `toolchain/.../engine/OpenReads.java`, `Verbaliser.java`, `Part.java`, `service/.../OpenApiEmitter.java`, de
tests en openapi-goldens die het raakt, README, `docs/ENGINE.md`, de pin `uchar`, `scripts/check-service-replay.sh` (de
gelijkheden van de replay op de nieuwe pin), deze kaart.

Out of scope: de lezing van een vouw over een set; andere uitlegvormen.

## Aanleiding

Sinds `one-empty-in-service` noemt de uitleg bij een open filter alleen het oordeel van de vouw, omdat de toolchain zonder
het filteroordeel per lid een onware zin kon schrijven (": er zijn 2 leden", de verkeerde dragers). uchar's kaart
`contributor-filter-verdict` geeft dat oordeel mee; daarmee kan de uitleg weer exact zijn.

## Uit de lezing vóór de bouw (extern model, nagerekend)

- **Het principe van de zinnen.** Een lid met een onbepaald filter is een lid waarvan niet vaststaat of het meetelt. De
  uitleg noemt het apart ("niet vast of V2 meetelt") en beweert nergens een totaal of een aantal dat niet vaststaat:
  geen "voor alle 2" als V2 een onbepaald filter heeft, geen "het enige lid", geen "1 van de 2", geen "er zijn 2
  leden". Een drager van het oordeel is alleen een lid met een vaststaand waar filter (bij "alle" die niet geldt: een
  lid met een vaststaand filter en een onware matrix). Bouw dat voor alle vijf kwantoren, in een vaststaande en een
  onbepaalde vouw, ook met filter én matrix onbepaald, in `members`, `carriers` en `openMembers` van `Verbaliser`.
- **`MEMBERS`.** Leg vast wat `members`, `holding` en `unsettled` tellen als een lid een onbepaald filter heeft (alleen
  leden met een vaststaand filter tellen in `members`), en geef het aantal leden met een onbepaald filter een eigen veld
  (bijvoorbeeld `filterUnsettled`): schema, OpenAPI en de goldens die het raakt.
- **Opruimen.** Met `OpenReads.reads` worden ook de velden `links` en `order` en de hulpmethoden `read`, `add`,
  `overASet`, `range`, `reached`, `walk` en `once` dood: weg.
- **Tests** naast de twee reviewgevallen: een vaststaande "alle" met een lid met onbepaald filter en ware matrix (geen
  "voor alle 2"); een "alle" die niet geldt met V1 (filter waar, matrix onwaar) en V2 (filter onbepaald, matrix onwaar):
  alleen V1 draagt; een vaststaand onwaar samengesteld filter met een open deel (het lid verschijnt niet).

## Result

*Bouwer, 2026-11-24. Branch `card/quantifier-filter-in-explanation`, gerebased op toolchain-main `d3ce49ed4`; niet gemerged
of gepusht. Twee rondes: de bouw, en een herstelronde na de review van een extern model (xhigh,
`werk/neon-review/antwoorden/xhigh-dd-qfie.md`, door de hoofdsessie nagerekend) met een besluit van de eigenaar
(opdracht `werk/quantifier-filter-in-explanation/OPDRACHT-2.md`).*

| commit | wat |
|---|---|
| `fc5539de3` | claim |
| `dd45c8c89` | pin uchar `2e6319e69` (alleen de submodule; de toolchain compileert er ongewijzigd tegen) |
| `e45182386` | replay: de gelijkheden op de nieuwe pin (`EXPECTED_REPLAY_PLAYED` 376 → 377, `EXPECTED_REPLAY_CASES` 1050 → 1053: `jutterswet-zwaarkeurder-strikt` met drie cases), en de getallen in README |
| `9a812ae6a` | de uitleg: `OpenReads.reads` weg, `Verbaliser` leest het filteroordeel per lid, `MEMBERS` met `filterUnsettled`, OpenAPI, README, ENGINE, tests |
| `bff082427` | Result van de bouw |
| `c020a1d3d` | herstelronde: het oordeel van de matrix bij een lid met een onbepaald filter, de groepen van een telling, `kleene` weg, de validatie in `split`, tests, README, ENGINE, OpenAPI |
| (deze) | Result bijgewerkt |

- **Opgeruimd.** `OpenReads.reads` met de velden `links` en `order` en de hulpmethoden `read`, `add`, `overASet`,
  `range` (drie), `reached`, `walk` en `once`; de klassejavadoc en ENGINE zeggen het niet meer. `OpenReadsTest` vervalt:
  het toetste alleen `reads` (op een eindtoestand met de hand). In de herstelronde ook `Verbaliser.kleene` (hieronder).
- **De zinnen.** `members` leest per bijdrager `undetermined`, `filterUndetermined`, de matrixwaarde, de fout en `reached`
  van uchars lezing en oordeelt zelf niet. Een lid met een onbepaald filter staat er apart, "niet vast of V2 meetelt"
  (meervoud "niet vast of V2 en V3 meetellen"), en nergens anders; staat zo'n lid er, dan noemt de zin geen totaal en geen
  aantal. Erachter staat tussen haakjes het oordeel van de matrix op zo'n lid (besluit van de eigenaar, Open 1): "(wel voor
  V2)", "(niet voor V2)", per groep bij meer leden, "(wel voor V2, niet voor V3)", "(niet voor V2 en V3)"; een lid met een
  onbepaalde matrix, een matrixfout of een matrix die de evaluatie niet bereikte (na de stop van ch. 5.10) staat er niet
  tussen, en staat er geen lid tussen, dan vallen de haakjes weg. Zonder lid met een onbepaald filter blijven de zinnen
  zoals ze waren (ook "(1 van de 3)", "er zijn 2 leden", "het enige lid"). Per geval, met het filter "zijn taxatie is
  gevuld" en de matrix "het gewicht groter dan 10" (V1 met taxatie, V2 zonder):
  - vouw staat vast (`carriers`), "alle" en "geen van de": de dragers onder de leden met een vaststaand filter, naar de
    kwantor zoals tot nu toe. "alle" geldt: `voor V1, niet vast of V2 meetelt (wel voor V2)`; geldt niet: `niet voor V1,
    niet vast of V2 meetelt` (V1 stopte de vouw vóór V2); "geen van de" geldt: `niet voor V1, niet vast of V2 meetelt (niet
    voor V2)`, geldt niet: `wel voor V1, niet vast of V2 meetelt`. Zonder lid met een vaststaand filter: `niet vast of V1
    meetelt (wel voor V1)` ("alle" geldt), `niet vast of V1 meetelt (niet voor V1)` ("geen van de" geldt).
  - vouw staat vast, een telling ("ten minste", "ten hoogste", "precies"): elke groep van de leden met een vaststaand
    filter die leden heeft, waarvoor de matrix geldt ("wel voor", of "voor" waar die groep alleen staat), niet geldt ("niet
    voor") en onbepaald is ("niet vast voor"), zodat geen van die leden wegvalt. "ten minste twee" geldt: `voor V1 en V2,
    niet vast of V3 meetelt`; geldt niet: `voor V1, niet vast of V2 meetelt (niet voor V2)`; "ten minste drie" geldt niet met
    V1 (20), V2 (5) en V3 (5, zonder taxatie): `wel voor V1, niet voor V2, niet vast of V3 meetelt` (het geval van de review;
    V1 en V2 stopten de vouw); "ten hoogste één" geldt met V2 zonder gewicht: `niet voor V1, niet vast voor V2, niet vast of
    V3 meetelt (niet voor V3)`; "ten hoogste één" over één lid staat vast vóór dat lid: `niet vast of V1 meetelt`.
  - vouw staat niet vast (`openMembers`): de leden met een vaststaand filter naar de matrix, dan het aparte stuk: `wel voor
    V1, niet vast of V2 meetelt (wel voor V2)` ("ten minste twee", V2 van 30), `wel voor V1, niet vast voor V2, niet vast of
    V3 meetelt (wel voor V3)`, `niet vast of V1 meetelt` (filter én matrix onbepaald op het enige lid).
  - filter én matrix onbepaald op één lid: het lid staat alleen in het aparte stuk, zonder haakjes.
  - een vaststaand onwaar samengesteld filter met een open deel: het lid is geen bijdrager en verschijnt niet (`voor V1,
    het enige lid`); een waar samengesteld filter met een open deel ("A of B", A waar) staat vast, en de zin is die van elk
    ander document (de twee reviewgevallen: `niet voor V2 (1 van de 2)`, `voor V1 en V2 (2 van de 3)`).
  - de nieuwe golden op de scenarioroute (`bin/regelspraak scenario --explanation`, log
    `werk/quantifier-filter-in-explanation/scenario-zwaarkeurder-2.log`): casus 1 `wel voor V1, niet vast of V2 en V3
    meetellen (wel voor V3, niet voor V2)`, casus 2 `niet voor V1, niet vast of V2 meetelt` (V2 na de stop), casus 3 `wel
    voor V2, niet vast of V1 meetelt` (de matrix van V1 onbepaald), zoals het uitgewerkte voorbeeld van uchars ch. 5.12.
- **Herstelronde.**
  1. Het oordeel van de matrix bij een lid met een onbepaald filter, naar het besluit van de eigenaar (Open 1; `filterOpen`
     in `Verbaliser`). `MEMBERS` krijgt geen nieuw veld.
  2. `kleene(...)` is weg: de toolchain rekent het oordeel van de vouw niet na met de tabel van Kleene (geen eigen
     semantiek; gate 17 van uchar houdt ref en Drools gelijk). De controle `verdict(q, k, m, read) != holds` op het pad zonder
     open leden staat er zoals ze was. Dat de knoop van een vaststaande vouw een `Boolean` geeft, toetst `members` nu vóór de
     keuze voor `carriers`, met dezelfde melding; `split` deed het daarvoor in de Kleene-controle.
  3. `carriers` bij een telling naast een lid met een onbepaald filter: elke groep die leden heeft (De zinnen). Dit vervangt
     open punt 2 van de bouw: daar gaf "ten minste 3" die faalt met V1 (filter waar, matrix waar), V2 (filter waar, matrix
     onwaar) en V3 (filter open, matrix onwaar) `niet voor V2, …`, zonder V1. Ik nam de groep met een onbepaalde matrix
     ("niet vast voor") mee om dezelfde reden: ook zo'n lid heeft een vaststaand filter. Bij "alle" en "geen van de" blijven
     de dragers alleen staan; de terugval van punt 2 naar de leden waarvoor de matrix geldt deed daar niets (bij een lege
     lijst dragers is ook die lijst leeg) en is weg.
  4. `split` valideert de matrix van elk lid, ook van een lid met een onbepaald filter: een `Boolean`, onbepaald of een
     fout, anders een `IllegalStateException` met de naam van het lid (niet stil als onbepaald behandeld, geen
     `ClassCastException`). `split` is daarvoor een instantiemethode (de namen van de leden).
  5. Tests (hieronder); README: het citaat van een vouw die niet vaststaat heeft zijn kop ("… staat niet vast: … is
     voldaan: …"); deze kaart: kopregel, `scripts/check-service-replay.sh` in `May touch`, de commits in de tabel na de
     rebase.
- **`MEMBERS`.** Van een kwantorvouw in een strikte regelset waarvan het oordeel of dat van een lid niet vaststaat:
  `members`, `holding`, `unjudged`, `unsettled` en nieuw `filterUnsettled`. Een lid met een onbepaald filter telt alleen in
  `filterUnsettled`; de andere vier tellen de leden met een vaststaand filter (`members` = waar of onwaar, `holding` =
  waar, `unjudged` = fout, `unsettled` = matrix onbepaald). Waar alles vaststaat blijven de drie sleutels van tot nu toe.
  `Part`, de strikte beschrijving en het schema in `OpenApiEmitter` (`filterUnsettled`, integer), README en ENGINE zeggen
  het. Geen openapi-golden verandert: geen van de zes is van een strikte regelset, en alleen de strikte beschrijving
  veranderde (in de herstelronde ook met "(wel voor V2)").
- **Tests.** Uit de bouw: `OpenExplanationFormsHttpTest.every_quantifier_beside_an_undetermined_filter`,
  `.a_member_whose_filter_is_false_with_an_open_part_does_not_appear`,
  `OpenExplanationHttpTest.a_quantifier_fold_that_holds_beside_an_open_filter`, en `filterUnsettled` in
  `OpenInputsHttpTest.the_openapi_document_has_the_flag_and_the_field`; gevolgd bij de bouw:
  `OpenExplanationFormsHttpTest.a_quantifier_fold_whose_filter_tests_an_open_member`,
  `.a_quantifier_fold_whose_filter_reads_an_open_member_and_is_decided`,
  `.a_quantifier_fold_whose_filter_counts_a_union_with_an_open_member`,
  `OpenExplanationHttpTest.a_quantifier_fold_with_an_open_filter`, `.a_quantifier_fold_that_is_undetermined`; vervallen
  `OpenReadsTest`. In de herstelronde nieuw in `OpenExplanationFormsHttpTest` (de gaten van review-punt 2):
  `a_fold_that_stands_without_a_member_whose_filter_stands` (vijf kwantoren, waaronder "ten hoogste één" die vóór het enige
  lid vaststaat), `a_count_that_stands_names_the_members_for_which_the_matrix_holds_and_those_for_which_it_does_not` (het
  reviewgeval "ten minste drie", en "ten minste twee", "ten hoogste één", "precies twee" met beide groepen),
  `a_fold_that_stands_beside_an_undetermined_matrix_and_an_undetermined_filter` (met `MEMBERS{… unsettled=1,
  filterUnsettled=1}`) en `a_fault_after_the_stop_beside_a_member_whose_filter_is_undetermined` (een onbereikte matrixfout
  naast twee leden met een onbepaald filter, een met een fout en een onbereikt, met `MEMBERS{… unjudged=1,
  filterUnsettled=2}`); de matrixdeling van `a_member_after_the_stop_whose_matrix_faults_is_not_judged` staat daarvoor in de
  hulpmethode `divideTheTestByTheTarief`. Gevolgd naar het besluit van de eigenaar (de zinnen krijgen "(wel/niet voor …)"):
  `every_quantifier_beside_an_undetermined_filter` (in elk van de vijf kwantoren, met drie zinnen erbij voor de meervouden
  en de kop van V3), `a_quantifier_fold_whose_filter_tests_an_open_member`,
  `a_quantifier_fold_whose_filter_counts_a_union_with_an_open_member`, en in `OpenExplanationHttpTest`
  `a_quantifier_fold_with_an_open_filter` en `a_quantifier_fold_that_holds_beside_an_open_filter` (zin en `MEMBERS`). In
  `every_quantifier_beside_an_undetermined_filter` krijgt "ten hoogste één" die geldt geen haakjes: na V1 kan het ene lid
  dat overblijft de drempel niet meer overschrijden, dus V1 stopt de vouw vóór V2.
- **Gates** (`make gates-no-docker` op de stand van `fded5afb5`, beide kaarten, log
  `werk/quantifier-filter-in-explanation/gates-2.log`):

  ```
  gates: 1 pin        GROEN      1s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
  gates: 2 reactor    GROEN   2m48s  testgevallen per module 212/645/68
  gates: 3 twins      GROEN      4s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
  gates: 4 replay     GROEN   1m51s  377 goldens (pin 377), 1053 cases (pin 1053)
  gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
  gates: GROEN — 5 stappen in 4m40s; de uitvoer staat in out/gates/
  ```

  De replay daarbij: 6135 zinnen, 4095 uitgelegde voorwaarden (vloer 1241), 3415 berekende waarden (vloer 1852), 6370
  gelezen waarden aan hun zin (vloer 4248); rode leg 58 mappen, 67 cases met hun foutsoort, 14 geweigerd met reden.
  Testgevallen per module 212/637/68 → 212/645/68 (de acht nieuwe tests van beide kaarten); geen vloer veranderd. De
  volle `make gates` (image en kind) draait de hoofdsessie na de pin-bump.
- **Open.**
  1. **Besluit van de eigenaar (2026-11-24)**, was een voorlopig besluit van de bouwer: de uitleg noemt bij een lid met een
     onbepaald filter ook het oordeel van zijn matrix, uit uchars lezing (`ConditionReading.Contributor`), zonder eigen
     oordeel. Na "niet vast of V2 meetelt" volgt "(wel voor V2)" als de matrix voor V2 geldt, "(niet voor V2)" als zij niet
     geldt; is de matrix onbepaald, of is er een fout of een onbereikt lid, dan komt er niets bij. Meervoud naar de groepen:
     "niet vast of V2 en V3 meetellen (wel voor V2, niet voor V3)". `MEMBERS` krijgt geen nieuw veld. README en
     `docs/ENGINE.md` zeggen het.
  2. *Vervallen:* het voorlopige besluit over de dragers waar geen lid met een vaststaand filter de uitkomst draagt, is
     vervangen door herstelpunt 3.
  3. De ongeordende kwantorvouw van Drools (uchars open punt 5 van `contributor-filter-verdict`) raakt de uitleg niet: een
     vouw waarvan het filter onbepaald kan zijn, leest Drools in volgorde, en `filterUndetermined` komt er dus altijd mee.
