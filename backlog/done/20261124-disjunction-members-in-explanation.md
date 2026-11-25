# disjunction-members-in-explanation: een geldende "ten minste één van" noemt alleen het lid dat geldt

*Gemeld vanuit de werkbank, 2026-11-24; uitgewerkt tegen `regelspraak-toolchain` `fadd713a5` (`uchar` `4d6bbe6be`).*

Goal: wanneer een samenstelling met `"ten minste één van de volgende voorwaarden"` (`Condition.Quantifier.TEN_MINSTE` met drempel 1) geldt, noemt de uitleg alleen het bereikte lid (of de bereikte leden) dat volgens uchars lezing geldt (`read.holds(d)`), in plaats van alle bereikte bepaalde leden met `"of"` ertussen. Daarmee verdwijnen de bevestigend afgedrukte niet-geldende leden vóór het beslissende lid (waaronder kwantorvouwen als `"is voldaan: er is geen enkel lid"` en niet-geldende kenmerktoetsen zonder waarde tussen haakjes), de voorrangsambiguïteit `"A en B of C"` bij een geneste disjunctie in een `"alle"`-lijst, en het verschil met een eerder onbepaald lid in een strikt document dat al werd weggelaten. De toolchain voegt geen eigen semantiek toe en leest uitsluitend uchars `ConditionReading`.

Acceptance:
- Jutterswet-tests in `toolchain` (unit/scenario, `VerbaliserTest` of eigen testklasse) en `service` (HTTP met `?explanation=true&explanationParts=true`, incl. `OpenExplanationFormsHttpTest` of `OpenExplanationHttpTest`) dekken alle volgende gevallen met exacte zinnen en `explanationParts`:
  1. **Eerste lid geldt:** `"ten minste één van"` met lid 1 waar → uitleg noemt alleen lid 1 (ongewijzigd).
  2. **Later lid geldt na niet-geldende leden:** `"ten minste één van"` met eerdere niet-geldende leden van elk soort — een kwantorvouw (`"ten minste één van zijn vinders is betwist"` met 0 leden én met 1 niet-betwiste vinder), een kenmerktoets (`"hij is drijfhout"`), een vergelijking (`"zijn gewicht is groter dan 1000"`), een `gevuld`/`leeg`-predicaat, en een geneste niet-geldende `"alle"`-samenstelling — gevolgd door een geldend lid → de platte uitleg noemt uitsluitend het geldende lid; geen enkel niet-geldend lid staat bevestigend in de zin, en nergens staat `"is voldaan: er is geen enkel lid"` of `"is voldaan: niet voor …"`.
  3. **Strikt document (`Afleiden uit leeg: nee`):** lid 1 is open (niet gegeven), lid 2 geldt niet (`holdsNot` of vergelijking onwaar), lid 3 geldt → de uitleg noemt uitsluitend lid 3, zonder het open en zonder het niet-geldende lid (in lijn met `OpenExplanationHttpTest.a_composite_that_another_member_decides`).
  4. **Geneste disjunctie:** een geldende `"ten minste één van"` binnen `"alle"` (waaronder de bijgewerkte pin in `ExplanationPartsServiceTest`, zin 0 voor `J1`: `"J1 is ervaren, omdat zijn leeftijd (30) is groter dan de minimumleeftijd (17) en het onderwerp ingezetene is"`) én binnen een telling (`"ten minste twee van de volgende"`), waarbij het tweede lid van de disjunctie geldt → alleen het geldende lid, zonder `"A en B of C"`.
  5. **Niet-geldende `"ten minste één van"`:** blijft ongewijzigd (`"want niet geldt: A of B"`, ook in `ExplanationReadingsTest`, `ExplanationReadingsServiceTest` en `ExplanationPartsServiceTest` zin 10 met `CONNECTIVE«of»` en `holds: false` op elk lid).
  6. **Stukken en weglaatbaarheid (`explanationParts`):** de buitenste groep `CONDITION{holds=true, quantifier=atLeast, threshold=1}` blijft staan rond het geldende lid (zoals nu al wanneer lid 1 geldt of wanneer lid 1 open was); `ExplanationPartsCheck.assertPartsAreTheSentences` en `ExplanationPartsCheck.withoutOmissibleParts` slagen; elke `Part.Kind` blijft gedekt in `ExplanationPartsServiceTest`.
- `README.md` (onder `Contract change`) en `docs/ENGINE.md` beschrijven dat een geldende `"ten minste één van"` alleen het dragende lid noemt (en hoe de platte zin daarmee verandert).
- `make gates` is 7/7 groen (tijdens bouw minimaal `make gates-no-docker` 5/5 groen; bij landing volledige `make gates` 7/7 groen, met in `Result:` het aantal gewijzigde zinnen/koppelingen over de replay-goldens).

May touch:
- `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java`
- `toolchain/src/test/java/dev/uchar/regelspraak/toolchain/engine/VerbaliserTest.java` (of een nieuwe gerichte testklasse in dezelfde package)
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/ExplanationPartsServiceTest.java`
- `service/src/test/java/dev/uchar/regelspraak/toolchain/service/http/OpenExplanationHttpTest.java` en/of `OpenExplanationFormsHttpTest.java`
- `README.md`, `docs/ENGINE.md`
- deze kaart (`backlog/new/20261124-disjunction-members-in-explanation.md` → `doing/` → `done/`)

Out of scope:
- `uchar/` (geen semantiekwijziging, geen pin-bump)
- `Part.java` en `OpenApiEmitter.java` (geen nieuwe `Part.Kind`, geen nieuwe meta-sleutels of schemawijziging)
- de weergave van een niet-geldende `QuantifierFold` binnen `GEEN` of een telling (`countingCompound`, bv. `"niet aan de voorwaarde \"…\" is voldaan: …"`), of van een niet-geldende `"ten minste één van"` onder `firstFailing`
- de werkbank

## Aanleiding en plekken

Een regel met `"ten minste één van de volgende voorwaarden"` (drempel 1) stopt bij evaluatie zodra één lid geldt (uchars `RUNTIME-SEMANTICS` ch. 5.10 en ch. 1.5). Wanneer niet het eerste maar een later lid geldt, heeft uchars lezing `reached: true` en `value: false` op de eerdere niet-geldende leden en `value: true` op het beslissende lid.

In `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` (op `fadd713a5`):
- `counting(Condition.Compound s)` (r. 3357–3363) stuurt alleen `TEN_MINSTE` met `threshold > 1`, `TEN_HOOGSTE` en `PRECIES` naar `countingCompound` (r. 3371–3390). `TEN_MINSTE` met drempel 1 gaat door de gewone `Condition.Compound`-tak in `verbaliseCondition` (r. 3327–3345) met voegwoord `"of"`.
- `carries(Condition.Compound s, Condition d, Read read)` (r. 3351–3353) neemt `s` al aan als parameter maar gebruikt `s` niet: het toetst enkel `read.reached(d) && !read.of(d).undetermined()`.
- Daardoor worden eerdere niet-geldende leden van een geldende `TEN_MINSTE(1)` meegenomen en door `verbaliseCondition` → `verbaliseAtom` (r. 3476–3480) bevestigend uitgeschreven:
  - `unstructured(b, c, read, "voldaan")` (r. 3479, 3491–3497) zet `"voldaan"` vast op een niet-geldende `QuantifierFold`, met als resultaat bv. `"omdat aan de voorwaarde \"ten minste één van zijn vinders is betwist\" is voldaan: er is geen enkel lid of zijn gewicht (1200) is groter dan 1000"`;
  - `structuredAtom` (r. 3426–3474) drukt een niet-geldende kenmerktoets bevestigend af (`"of het onderwerp drijfhout is"`) zonder waarde tussen haakjes, en een niet-geldende vergelijking of predicaat als schijnbaar geldend alternatief;
  - een geneste niet-geldende `ALLE`-samenstelling vóór het beslissende lid wordt eveneens bevestigend uitgeschreven;
  - bij een disjunctie genest in een `"alle"`-lijst ontstaat voorrangsambiguïteit (zoals nu al in `ExplanationPartsServiceTest` r. 134–135: `"omdat zijn leeftijd (30) is groter dan de minimumleeftijd (17) en zijn aantal meldingen (3) is groter dan 10 of het onderwerp ingezetene is"`, terwijl `aantal meldingen (3)` `holds: false` heeft).

### Ontwerp en randgevallen

- **Alleen het dragende lid bij een geldende `TEN_MINSTE` met drempel 1:** in `carries(s, d, read)` draagt een lid `d` van een `TEN_MINSTE` met `s.threshold() == 1` wanneer `read.holds(s)` waar is alleen als `read.reached(d) && read.holds(d)`.
  - Dit is volledig symmetrisch met drie bestaande keuzes in `Verbaliser`:
    1. wanneer lid 1 geldt, stopt de motor direct en noemt de zin al alleen lid 1 (`ExplanationReadingsTest` r. 177, `OpenExplanationFormsHttpTest` r. 623);
    2. wanneer lid 1 in een strikt document onbepaald is en lid 2 geldt, laat `carries` lid 1 al weg en noemt de zin alleen lid 2 (`OpenExplanationHttpTest.a_composite_that_another_member_decides` r. 205; `docs/ENGINE.md` r. 695: *"a member of a composite that is undetermined beside one that decides has no word"*);
    3. het is exact duaal aan `firstFailing` (r. 3782–3790) bij `"alle"`, dat alleen het eerste niet-geldende lid noemt en de eerdere geldende leden weglaat.
  - Het vermijdt ook een verwarrende ontkenning met `"en"` (zoals `"omdat niet het onderwerp drijfhout is en zijn gewicht (1200) is groter dan 1000"`), wat ten onrechte zou suggereren dat niet-drijfhout zijn een voorwaarde was.
- **Fail-fast wacht:** als `verbaliseCondition` voor een geldende `TEN_MINSTE` met drempel 1 na de lus over `s.components()` geen enkel lid heeft geschreven (`first` blijft `true`), werp een `IllegalStateException` (zoals `openCondition` r. 2949 en `firstFailing` r. 3788 al doen), zodat er nooit een lege `CONDITION`-groep met `", omdat "` kan ontstaan.
- **Niet-geldende `TEN_MINSTE` met drempel 1 (`read.holds(s) == false`):** `firstFailing` (r. 3791–3795) schrijft `"niet geldt: "` en roept `verbaliseCondition` aan; dan is `read.holds(s)` onwaar, dus `carries` behoudt alle bereikte bepaalde leden (in een strikt document zijn per Kleene bij `atLeast 1` die vaststaand onwaar is alle `n` leden bepaald onwaar, `f >= n`). `"want niet geldt: A of B"` blijft exact zoals het was (inclusief `ExplanationReadingsTest` r. 168 en `ExplanationPartsServiceTest` r. 150).
- **Geneste `TEN_MINSTE(1)` binnen `GEEN` of `countingCompound`:** wanneer de geneste disjunctie zelf `holds: true` heeft (bv. een geldend lid van `"ten minste 2 van de 3"`, of het lid waardoor `"geen van de volgende"` faalt), toont zij alleen haar eigen geldende lid; wanneer zij `holds: false` heeft, toont zij onder `"niet "` al haar niet-geldende leden met `"of"` (ongewijzigd).
- **Replay-vloeren (`scripts/check-service-replay.sh`):** doordat niet-geldende eerdere leden van een geldende disjunctie wegvallen, kan het totale aantal gelezen waarden met schrijver (`read_links`) over de 387 goldens licht dalen; de gepinde vloer (`EXPECTED_MIN_READ_LINKS`, 4248 t.o.v. 6546 op `fadd713a5`) heeft ruime marge, en `EXPECTED_REPLAY_PLAYED`/`EXPECTED_REPLAY_CASES`/`EXPECTED_RED_FAULTS` veranderen niet.

## Result (2026-11-25)

- **Uitleg zonder eigen semantiek (`Verbaliser`):**
  - `Verbaliser.carries` neemt bij een geldende `Condition.Compound` met `Quantifier.TEN_MINSTE` en `threshold == 1` (`read.holds(s) == true`) uitsluitend bereikte leden op waarvoor `read.holds(d)` geldt (naast het bestaande uitsluiten van onbereikte en onbepaalde leden), uitsluitend uit uchars `ConditionReading`.
  - Fail-fast-wacht in `verbaliseCondition`: werpt een `IllegalStateException` wanneer een geldende `TEN_MINSTE(1)` geen enkel bereikt geldend lid zou hebben geschreven (`first` blijft `true`), zodat er nooit een lege `CONDITION`-groep achter `", omdat "` kan ontstaan.
  - Een niet-geldende `TEN_MINSTE(1)` (`"want niet geldt: A of B"`), alle overige tellingen (`TEN_MINSTE` met drempel > 1, `TEN_HOOGSTE`, `PRECIES`), `ALLE`, `GEEN` en de buitenste groep `CONDITION{holds=true, quantifier=atLeast, threshold=1}` rond het geldende lid blijven ongewijzigd.
- **Tests (Jutterswet) en documentatie:**
  - `VerbaliserTest`: scenario- en unittests voor geldende `"ten minste één van"` met eerste lid waar, later lid waar na alle soorten niet-geldende leden (kwantorvouw met 0 leden én met 1 niet-betwiste vinder, kenmerktoets, vergelijking, `gevuld`- en `leeg`-predicaten, geneste niet-geldende `"alle"`), strikte disjunctie met open én niet-geldend lid vóór het beslissende lid, geneste disjunctie in `"alle"` en in `"ten minste twee van de volgende"`, niet-geldende `"ten minste één van"`, de buitenste `CONDITION`-groep en de fail-fast-wacht.
  - `ExplanationPartsServiceTest`: pin van zin 0 en `PARTS.get(0)` voor `J1` bijgewerkt (`"J1 is ervaren, omdat zijn leeftijd (30) is groter dan de minimumleeftijd (17) en het onderwerp ingezetene is"`), zin 10 (`CONNECTIVE«of»`, `holds=false`) ongewijzigd, alle `Part.Kind`-soorten gedekt en `withoutOmissibleParts` gecontroleerd.
  - `OpenExplanationFormsHttpTest` en `OpenExplanationHttpTest`: HTTP-tests met `?explanation=true&explanationParts=true` voor alle zes acceptatiegevallen inclusief gepinde `ExplanationPartsCheck.render`-bomen en `withoutOmissibleParts`.
  - `README.md` (`Contract change`) en `docs/ENGINE.md` (`The parts of an explanation`) bijgewerkt.
- **Buiten scope gelaten:** `uchar/` (pin ongewijzigd op `4d6bbe6be`), `Part.java`, `OpenApiEmitter.java`, niet-geldende `QuantifierFold` onder `GEEN`/`countingCompound` en de werkbank.
- **Verschuiving over de 387 replay-goldens (1102 cases):**
  - **15 van de 6286 zinnen gewijzigd**, verspreid over 8 goldens (`jutterswet-keurcriteria`, `jutterswet-paasvondst`, `jutterswet-premiestaffel`, `jutterswet-premietabel-twee-ofcellen`, `jutterswet-rubriektabel`, `jutterswet-rubriektabel-rgs`, `jutterswet-vrijstelling-drie-niveaus`, `jutterswet-wachtdeling`);
  - **0 gewijzigde koppelingen** (`read_links` blijft 6546, ruim boven vloer 4248; in alle 15 gewijzigde zinnen lazen de weggevallen eerdere leden alleen invoerattributen of vouwen zonder attribuutschrijver).
- **Poorten (`make gates`, 7/7 GROEN in 7m14s):**
  - 1 `pin`: GROEN (`4d6bbe6be` op `origin/main`, versies gelijk)
  - 2 `reactor`: GROEN (`215/654/68`, 0 skips)
  - 3 `twins`: GROEN (3 tweelingen 18 cases, vocabulairetweeling 2 cases / 34 verzoeken, 4 getuigenissen)
  - 4 `replay`: GROEN (387/387 goldens, 1102/1102 cases, 6286 zinnen, 4334 voorwaarden, 3478 berekeningen, 6546 koppelingen, 0 onbepaalde goldens; rode leg 68/68 fouten)
  - 5 `manifest`: GROEN (32 regels, 29 ok, 3 uitgesloten, 0 mismatch)
  - 6 `image`: GROEN (`decision-service:dev`)
  - 7 `kind-smoke`: GROEN (H2 PASS, H3 PASS, H4 PASS)
