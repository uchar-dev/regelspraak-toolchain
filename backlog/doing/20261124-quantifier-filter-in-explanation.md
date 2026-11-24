# quantifier-filter-in-explanation: de uitleg van een kwantorvouw met een open filter, exact

*Opdracht van de eigenaar, 2026-11-24: vervolgpunt van `one-empty-in-service`. Volgt op uchar's kaart
`contributor-filter-verdict` (zelfde pin-bump). Vóór de bouw gelezen door een extern model (xhigh), de punten hieronder
verwerkt. Nog niet begonnen.*

Goal: de uitleg van een kwantorvouw waarvan het filter op een lid een open waarde leest, noemt de leden en dragers weer,
met per lid of zijn filter vaststaat, uit wat uchar's lezing zegt (`ConditionReading.Contributor` met het filteroordeel),
zonder eigen oordeel van de toolchain.

Acceptance: `OpenReads.reads` (de benadering van het filter over knopen en koppelingen) vervalt; de twee gevallen van de
review van `one-empty-in-service` ("alle" met V1 filter waar/matrix waar en V2 filter "A of B" met A waar en B
open/matrix onwaar; "ten minste 2") en een lid met een onbepaald filter geven een ware uitleg met leden, in
`OpenExplanationFormsHttpTest` en `OpenExplanationHttpTest`; de OpenAPI-beschrijving van `MEMBERS`, README en
`docs/ENGINE.md` zeggen het; `make gates` groen op de nieuwe pin.

May touch: `toolchain/.../engine/OpenReads.java`, `Verbaliser.java`, `Part.java`, `service/.../OpenApiEmitter.java`, de
tests en openapi-goldens die het raakt, README, `docs/ENGINE.md`, de pin `uchar`, deze kaart.

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

*Bouwer, 2026-11-24. Branch `card/quantifier-filter-in-explanation` vanaf `cd94690b1`; niet gemerged of gepusht.*

| commit | wat |
|---|---|
| `c86e7fac9` | claim |
| `81521eda6` | pin uchar `2e6319e69` (alleen de submodule; de toolchain compileert er ongewijzigd tegen) |
| `acf04ea2a` | replay: de gelijkheden op de nieuwe pin (`EXPECTED_REPLAY_PLAYED` 376 → 377, `EXPECTED_REPLAY_CASES` 1050 → 1053: `jutterswet-zwaarkeurder-strikt` met drie cases), en de getallen in README |
| `783afffe9` | de uitleg: `OpenReads.reads` weg, `Verbaliser` leest het filteroordeel per lid, `MEMBERS` met `filterUnsettled`, OpenAPI, README, ENGINE, tests |

- **Opgeruimd.** `OpenReads.reads` met de velden `links` en `order` en de hulpmethoden `read`, `add`, `overASet`,
  `range` (drie), `reached`, `walk` en `once`; de klassejavadoc en ENGINE zeggen het niet meer. `OpenReadsTest` vervalt:
  het toetste alleen `reads` (op een eindtoestand met de hand).
- **De zinnen.** `members` leest per bijdrager `undetermined` en `filterUndetermined` van uchars lezing en oordeelt zelf
  niet. Een lid met een onbepaald filter staat er apart, "niet vast of V2 meetelt" (meervoud "niet vast of V2 en V3
  meetellen"), en nergens anders; staat zo'n lid er, dan noemt de zin geen totaal en geen aantal. Zonder zo'n lid blijven
  de zinnen zoals ze waren (ook "(1 van de 3)", "er zijn 2 leden", "het enige lid"). Per geval, met het filter "zijn taxatie
  is gevuld" (V1 met taxatie, V2 zonder):
  - vouw staat vast (`carriers`): de dragers onder de leden met een vaststaand filter, naar de kwantor zoals tot nu toe
    (bij "alle" die niet geldt de leden waarvoor de matrix niet geldt). "alle" geldt: `voor V1, niet vast of V2 meetelt`;
    "alle" geldt niet: `niet voor V1, niet vast of V2 meetelt`; "geen van de" geldt: `niet voor V1, niet vast of V2
    meetelt`, geldt niet: `wel voor V1, niet vast of V2 meetelt`; "ten minste twee" geldt: `voor V1 en V2, niet vast of V3
    meetelt`; "ten hoogste één" geldt: `niet voor V1, niet vast of V2 meetelt`, geldt niet: `voor V1 en V2, niet vast of V3
    meetelt`; "precies één" geldt: `voor V1, niet vast of V2 meetelt`, geldt niet: `voor V1 en V2, niet vast of V3
    meetelt`. Draagt geen lid met een vaststaand filter de uitkomst (te weinig leden), dan noemt de zin de leden waarvoor de
    matrix geldt: "ten minste twee" geldt niet met V1 (20) en V2 (5, filter onbepaald): `voor V1, niet vast of V2 meetelt`;
    is ook die lijst leeg, dan alleen het aparte stuk.
  - vouw staat niet vast (`openMembers`): de leden met een vaststaand filter naar de matrix, dan het aparte stuk: `wel voor
    V1, niet vast of V2 meetelt`, `wel voor V1, niet vast voor V2, niet vast of V3 meetelt` ("ten minste twee", V2 zonder
    gewicht), `niet vast of V1 meetelt` ("ten minste één", filter én matrix onbepaald op het enige lid).
  - filter én matrix onbepaald op één lid: het lid staat alleen in het aparte stuk (`niet voor V1, niet vast of V2
    meetelt` bij "alle" die niet geldt, `wel voor V1, niet vast of V2 meetelt` bij "alle" die niet vaststaat).
  - een vaststaand onwaar samengesteld filter met een open deel: het lid is geen bijdrager en verschijnt niet (`voor V1,
    het enige lid`); een waar samengesteld filter met een open deel ("A of B", A waar) staat vast, en de zin is die van elk
    ander document (de twee reviewgevallen: `niet voor V2 (1 van de 2)`, `voor V1 en V2 (2 van de 3)`).
  - de nieuwe golden op de scenarioroute (`bin/regelspraak scenario --explanation`): casus 1 `wel voor V1, niet vast of V2
    en V3 meetellen`, casus 2 `niet voor V1, niet vast of V2 meetelt`, casus 3 `wel voor V2, niet vast of V1 meetelt`.
- **Een toets op de zin.** De drie strikte paden (`carriers`, `openMembers`, en via `carriers` het nieuwe geval) rekenen
  het oordeel van de vouw na uit de lezing van haar leden met de tabel van Kleene (ch. 1.5: bij "alle" is een lid waar waar
  zijn filter onwaar of zijn matrix waar is, anders waar waar beide waar zijn; een lid met een matrixfout staat na de stop
  en telt niet) en falen met een `IllegalStateException` waar dat oordeel van dat van de motor verschilt, zoals `members`
  dat al deed zonder open leden. `openMembers` telt een lid met een matrixfout nu als niet beoordeeld, waar het onder
  "niet voor" viel; in een onbepaalde vouw komt dat niet voor (een fout na een onbepaald lid is voorlopig opgevangen tot
  onbepaald), dus geen zin verandert.
- **`MEMBERS`.** Van een kwantorvouw in een strikte regelset waarvan het oordeel of dat van een lid niet vaststaat:
  `members`, `holding`, `unjudged`, `unsettled` en nieuw `filterUnsettled`. Een lid met een onbepaald filter telt alleen in
  `filterUnsettled`; de andere vier tellen de leden met een vaststaand filter (`members` = waar of onwaar, `holding` =
  waar, `unjudged` = fout, `unsettled` = matrix onbepaald). Waar alles vaststaat blijven de drie sleutels van tot nu toe.
  `Part`, de strikte beschrijving en het schema in `OpenApiEmitter` (`filterUnsettled`, integer), README en ENGINE zeggen
  het. Geen openapi-golden verandert: geen van de zes is van een strikte regelset, en alleen de strikte beschrijving
  veranderde.
- **Tests.** Nieuw: `OpenExplanationFormsHttpTest.every_quantifier_beside_an_undetermined_filter` (vijf kwantoren, elk
  geldt / geldt niet / staat niet vast, filter én matrix onbepaald, te weinig leden, geen lid met vaststaand filter; 20
  zinnen) en `.a_member_whose_filter_is_false_with_an_open_part_does_not_appear`;
  `OpenExplanationHttpTest.a_quantifier_fold_that_holds_beside_an_open_filter` (geen "voor alle 2", met `MEMBERS`);
  `OpenInputsHttpTest.the_openapi_document_has_the_flag_and_the_field` toetst ook `filterUnsettled`. Gedrag dat de kaart
  verandert, in de tests gevolgd: `OpenExplanationFormsHttpTest.a_quantifier_fold_whose_filter_tests_an_open_member` (drie
  zinnen met leden waar ze eindigden op "is voldaan"), `.a_quantifier_fold_whose_filter_reads_an_open_member_and_is_decided`
  (de twee reviewgevallen met leden), `.a_quantifier_fold_whose_filter_counts_a_union_with_an_open_member` (`wel voor J1,
  niet vast of J2 meetelt`); `OpenExplanationHttpTest.a_quantifier_fold_with_an_open_filter` (`wel voor W1, niet vast of W2
  meetelt`, met `MEMBERS{members=1, holding=1, unjudged=0, unsettled=0, filterUnsettled=1}`) en
  `.a_quantifier_fold_that_is_undetermined` (de pin van `MEMBERS` krijgt `filterUnsettled=0`). Vervallen: `OpenReadsTest`
  (zie boven). Testgevallen per module 213/634/68 → 212/637/68; geen vloer veranderd.
- **Gates** (`make gates-no-docker` op de stand van `783afffe9`, logs in `werk/quantifier-filter-in-explanation/`):

  ```
  gates: 1 pin        GROEN      0s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk
  gates: 2 reactor    GROEN   2m43s  testgevallen per module 212/637/68
  gates: 3 twins      GROEN      4s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
  gates: 4 replay     GROEN   1m48s  377 goldens (pin 377), 1053 cases (pin 1053)
  gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
  gates: GROEN — 5 stappen in 4m31s; de uitvoer staat in out/gates/
  ```

  De replay daarbij: 6135 zinnen, 4095 uitgelegde voorwaarden (vloer 1241), 3415 berekende waarden (vloer 1852), 6370
  gelezen waarden aan hun zin (vloer 4248); rode leg 58 mappen, 67 cases met hun foutsoort, 14 geweigerd met reden. De volle
  `make gates` (image en kind) draait de hoofdsessie.
- **Open.**
  1. *Voorlopig besluit van de bouwer:* van een lid met een onbepaald filter noemt de zin niet het oordeel van zijn matrix.
     Bij "alle" die geldt staat er `voor V1, niet vast of V2 meetelt`, ook al geldt de matrix voor V2 (daarom staat de vouw
     vast); bij "ten minste twee" die niet geldt `voor V1, niet vast of V2 meetelt`, ook al geldt zij voor V2 niet. Wie het
     wil weten, leest het uit de lezing; een zin als "niet vast of V2 meetelt (wel voor V2)" kan erbij als de eigenaar dat
     vraagt.
  2. *Voorlopig besluit van de bouwer:* waar geen lid met een vaststaand filter de uitkomst draagt, noemt de zin de leden
     waarvoor de matrix geldt (zoals een telling zonder open leden de leden noemt waarvoor zij geldt), niet "er zijn 2
     leden": dat totaal staat niet vast.
  3. De ongeordende kwantorvouw van Drools (uchars open punt 5 van `contributor-filter-verdict`) raakt de uitleg niet: een
     vouw waarvan het filter onbepaald kan zijn, leest Drools in volgorde, en `filterUndetermined` komt er dus altijd mee.
