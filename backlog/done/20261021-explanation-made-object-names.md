# explanation-made-object-names: de uitleg noemt een gemaakt object bij naam

*Open punt van `explanation-fold-members` (2026-10-21), vastgelegd door de hoofdsessie. Luid, geen fout antwoord: een
uitlegverzoek waarvan een zin een gemaakt object moet noemen, faalt nu als geheel.*

## Aanleiding

De Verbalisator noemt een instantie bij het label dat `verbalise` krijgt: de objecten van de invoer. Een object dat een
objectcreatie maakte, heeft daar geen label; een kwantorlid zonder label faalt luid (`explanation-fold-members`). In het
corpus en op v2 komt het niet voor in een vorm die de uitleg verwoordt (de enige kwantorvouw over gemaakte objecten,
`jutterswet-opvolgerquorum`, staat in de voorwaarde van een objectcreatie), maar een regelset met een regel over
gemaakte objecten krijgt met `?explanation=true` geen antwoord.

## Doel

De uitleg noemt een gemaakt object met de naam die het fixpunt het geeft (`Fixpoint.objects`: creatie en onderwerp),
in één vorm voor de drie routes (`ScenarioRunner`, `DecisionCore`, en de testhelper `ExplanationSession`): uchars
canonieke vorm `«"<creatie>"»(<onderwerp>)#1` of de `#n` van `FixpointGivens`; de kaart kiest en zegt waarom. Na
`explanation-parts`, zodat de soort "onderwerp" daar dezelfde naam draagt.

## Keuze van de vorm (de bouwer, 2026-10-21)

uchars canonieke vorm, `«"<creatie>"»(<onderwerp>)#1`, niet de `#n` van `FixpointGivens`:

1. Het is de naam die uchar het object zelf geeft: de canonieke tekst van de lezingen (RUNTIME-SEMANTICS ch. 5.12, gate
   17), de dump van een sessie, en de volgorde van `Fixpoint.objects`. De uitleg en uchar noemen het object dan gelijk.
2. Hij hangt alleen af van de invoer: de naam van de creatie, het onderwerp, en het nummer, dat 1 is (een creatie maakt
   één object per onderwerp, ch. 5.5). `#n` is de plaats in een volgorde op type, creatie, onderwerp en de waarden van
   alle gemaakte objecten: hij verschuift als een ander gemaakt object een andere waarde krijgt, en zegt alleen iets naast
   het veld `fixpoint`, dat de uitleg niet meelevert.
3. De naam zegt de lezer (en het taalmodel) wat het object is: welke creatie het voor wie maakte.
4. `FixpointGivens` staat in de dienst; de scenario-runner (module `toolchain`) kan zijn `#n` niet lezen. De vorm van uchar
   bouwt de Verbalisator uit `Fixpoint.objects`, één plaats voor de drie routes.

Voorlopige keuzes van de bouwer binnen die vorm (dicht bij de bestaande zinsvormen; de eigenaar kan ze omdraaien):

- **Het onderwerp heet zoals de uitleg het elders noemt**: een object van de invoer bij zijn label zonder aanhalingstekens
  (`«"geef een vangstpenning"»(J1)#1`, niet `("J1")` zoals uchars canonieke tekst), een gemaakt onderwerp bij zijn
  eigen naam (`«"schrijf een kwitantie"»(«"geef een vangstpenning"»(J1)#1)#1`). De naam van de creatie staat tussen
  aanhalingstekens en ontsnapt zoals uchar haar schrijft, canoniek, ook in een ander vocabulaire (zoals een regelnaam in de
  uitleg).
- **Het nummer is altijd 1**; geeft het fixpunt twee objecten van één creatie voor één onderwerp, dan faalt de uitleg
  luid (ch. 5.5 en 5.12 zeggen dat het niet kan).
- **Een gemaakt object is ook een onderwerp van de uitleg**: zijn kenmerktoekenningen en defaults-groepen krijgen zinnen,
  na die van de objecten van de invoer, in de volgorde van het fixpunt. Zijn objecttype is het type waarvan het de bean
  is, zoals in `FixpointGivens`.

## Acceptatie

- (a) Rood eerst: een eigen model met een regel over een gemaakt object en een kwantorvouw over gemaakte objecten, op de
  drie routes.
- (b) Bestaande uitlegtests ongewijzigd groen; op v2 elke zin gelijk.
- (c) `make gates` groen.

May touch: `toolchain/.../engine/Verbaliser.java`, `ScenarioRunner`, `service/.../DecisionCore.java`, de tests van de
uitleg, deze kaart.

## Result (2026-10-21)

Tak `card/explanation-made-object-names` in de worktree `wt-tc/explanation-made-object-names`, vanaf toolchain main
`6b5d3750`; stap 1 op pin uchar `4f283d9d`, stap 2 de pin op `680e5263`. Gebouwd door een Opus-bouwer; de hoofdsessie draaide
`make gates` en schreef dit Result af uit zijn concept.

- `ff6f642c` de kaart naar `doing/`.
- `381ea945` de uitleg noemt een gemaakt object bij zijn naam, met de tests, de keuze van de vorm in de kaart en de
  documentatie.
- `20c63b5f` stap 2: de pin op uchar `680e5263` (`clingo-day-kind-walk-after-fold-stop`,
  `default-statute-annotations`), de grondslag van een default uit `Default.statuteAnnotations`, de replay-pin 232 → 234.

### De vorm van de naam

uchars canonieke vorm, `«"<creatie>"»(<onderwerp>)#1`, niet de `#n` van `FixpointGivens`. Waarom (ook in de kaart,
"Keuze van de vorm"): het is de naam die uchar het object zelf geeft (de canonieke tekst van de lezingen,
RUNTIME-SEMANTICS ch. 5.12, de dump, de volgorde van `Fixpoint.objects`); hij hangt alleen af van de invoer (creatie,
onderwerp, nummer 1), waar `#n` verschuift met de waarden van alle gemaakte objecten en alleen naast het veld `fixpoint`
iets zegt; hij zegt de lezer welke creatie het object voor wie maakte; en de Verbalisator bouwt hem uit
`Fixpoint.objects`, op één plaats voor de drie routes, waar `#n` in de dienst staat en de scenario-runner hem niet kan
lezen.

Voorlopige keuzes van de bouwer binnen die vorm (in de kaart; de eigenaar kan ze omdraaien):

1. Het onderwerp heet zoals de uitleg het elders noemt: een object van de invoer bij zijn label zonder aanhalingstekens
   (`«"geef een vangstpenning"»(J1)#1`; uchars canonieke tekst schrijft `("J1")`), een gemaakt onderwerp bij zijn eigen
   naam (`«"schrijf een kwitantie"»(«"geef een vangstpenning"»(J1)#1)#1`). De naam van de creatie staat tussen
   aanhalingstekens, ontsnapt zoals uchar haar schrijft, en canoniek, ook in een ander vocabulaire, zoals een regelnaam in
   de uitleg.
2. Het nummer is altijd 1: twee objecten van één creatie voor één onderwerp in het fixpunt is een luide fout (ch. 5.5 en
   5.12: een creatie maakt één object per onderwerp; uchars `CreationStamp` en `ref` houden dat ook).
3. Een gemaakt object is ook een onderwerp van de uitleg: zijn kenmerktoekenningen en defaults-groepen krijgen zinnen, na
   die van de objecten van de invoer, in de volgorde van het fixpunt; zijn objecttype is het type waarvan het de bean is,
   zoals in `FixpointGivens`.

### Wat er veranderde

- **De Verbalisator** (`toolchain/.../engine/Verbaliser.java`) krijgt het fixpunt van de sessie in zijn constructor
  (`Verbaliser(Document, Naming, List<ConditionReading>, Fixpoint)`, de enige constructor). `subjects` zet de objecten
  van de invoer onder hun label en daarna elk object van `Fixpoint.objects` onder zijn naam (`madeName`), met zijn
  objecttype (`madeType`); beide `sentences`-methoden lopen over die lijst. De naam van een gemaakt object staat in
  dezelfde tabel als de labels, dus noemt de zin van een kwantorvouw zo'n lid bij die naam, en de soort `SUBJECT` van
  `explanationParts` draagt dezelfde tekst. Luid, nieuw: een onderwerp dat geen object van de invoer en geen gemaakt
  object is, gemaakte objecten die elkaars onderwerp zijn in een kring, een gemaakt object dat ook in de invoer staat of
  twee keer in het fixpunt, twee objecten van één creatie voor één onderwerp, een naam gelijk aan een label van de
  invoer, een bean van geen objecttype van het model. De melding van een lid zonder naam noemt nu beide soorten objecten.
- **De drie routes** geven het fixpunt dat zij al lazen: `ScenarioRunner` (`h.fixpoint()`, met de labels van de case
  gelezen vóór de terugschrijving), `DecisionCore.addExplanation` (`outcome.fixpoint()`, met de ids van het verzoek), en
  de testhelper `ExplanationSession`, die het fixpunt nu met de labels van zijn beans leest vóór `setDerivedValues`.
- Javadoc van `Part.Kind.SUBJECT` en de rij `SUBJECT` in `docs/ENGINE.md` ("The parts of an explanation"): het label
  van een instantie of de naam van een gemaakt object. `docs/ENGINE.md` en `Part.java` staan niet in May touch; het zijn
  twee regels documentatie van de soort die nu ook een gemaakt object noemt. De beschrijving van `SUBJECT` in de OpenAPI
  ("het label van een instantie") bleef, zodat de vier OpenAPI-goldens niet veranderen.

### Rood eerst (a)

Eigen model (`ExplanationMadeObjectsTest`, scenario-route en `ExplanationSession`; `ExplanationMadeObjectsServiceTest`,
dienst uit bronnen en uit een IR-document): een Jutter krijgt een penning met zijn vangst als bedrag
("geef een vangstpenning", vangst > 0 €) en een van 5 € boven een vangst van 20 € ("geef een bonuspenning"); een
penning boven 10 € krijgt een kwitantie ("schrijf een kwitantie", een creatie waarvan het onderwerp zelf gemaakt kan
zijn); regels "waardevolle penning" en "geldige kwitantie" op de gemaakte objecten; "beloond" (alle penningen
waardevol) en "rijk" (ten minste één) op de Jutter, een kwantorvouw over gemaakte objecten. J1 vangst 30 €, J2 vangst
8 € met de penning P1 van de invoer (15 €).

Op de code van de kaartbasis met alleen de tests: rood op alle vier (`t5/logs/red-1.log`, `red-2.log`), met de luide
fout van `explanation-fold-members`: "een lid van de kwantorvouw "alle penningen van de Jutter waardevol zijn" van
/rules/rules/2/versions/0 op J1 heeft geen label: …". Groen na de bouw (`t5/logs/targeted-1.log`). De zinnen, op de
drie routes gelijk (de `ExplanationSession` zonder P1):

    J1 is niet beloond, want aan de voorwaarde "alle penningen van de Jutter waardevol zijn" is niet voldaan: niet voor «"geef een bonuspenning"»(J1)#1 (1 van de 2)
    J1 is rijk, omdat aan de voorwaarde "ten minste één van de penningen van de Jutter waardevol is" is voldaan: voor 1 van de 2 («"geef een vangstpenning"»(J1)#1)
    J2 is niet beloond, want … is niet voldaan: niet voor «"geef een vangstpenning"»(J2)#1 (1 van de 2)
    J2 is rijk, omdat … is voldaan: voor 1 van de 2 (P1)
    P1 is waardevol, omdat zijn bedrag (15 EUR) is groter dan 10 EUR
    «"geef een bonuspenning"»(J1)#1 is niet waardevol, want niet geldt: zijn bedrag (5 EUR) is groter dan 10 EUR
    «"geef een vangstpenning"»(J1)#1 is waardevol, omdat zijn bedrag (30 EUR) is groter dan 10 EUR
    «"geef een vangstpenning"»(J2)#1 is niet waardevol, want niet geldt: zijn bedrag (8 EUR) is groter dan 10 EUR
    «"schrijf een kwitantie"»(P1)#1 is geldig, omdat zijn bedrag (15 EUR) is groter dan 0 EUR
    «"schrijf een kwitantie"»(«"geef een vangstpenning"»(J1)#1)#1 is geldig, omdat zijn bedrag (30 EUR) is groter dan 0 EUR

De stukken (dienst, gepind in de test, in de compacte vorm van `ExplanationPartsCheck.render`):

    zin 1: SUBJECT«J1» « is niet » NAME{declaration=KENMERK, owner=Jutter, canonical=beloond}«beloond» «, » CONNECTIVE«want» « »
           CONDITION{holds=false, quantifier=all}[«aan de voorwaarde "» QUOTE«alle penningen van de Jutter waardevol zijn»
           «" is niet voldaan: » MEMBERS{members=2, holding=1, unjudged=0}[«niet voor » SUBJECT««"geef een bonuspenning"»(J1)#1» « (1 van de 2)»]]
    zin 10: SUBJECT««"schrijf een kwitantie"»(«"geef een vangstpenning"»(J1)#1)#1» « is » NAME{declaration=KENMERK, owner=Kwitantie,
           canonical=geldig}«geldig» «, » CONNECTIVE«omdat» « » CONDITION{holds=true}[QUOTE{declaration=ATTRIBUUT, owner=Kwitantie,
           canonical=bedrag}«zijn bedrag» « (» VALUE«30 EUR» «) is groter dan » VALUE«0 EUR»]

In de `ExplanationSession` (J1 en J2 zonder P1) heeft J2 één penning: "niet voor «"geef een vangstpenning"»(J2)#1, het
enige lid".

### Bestaande tests (b)

Geen bestaande test aangepast; elke uitlegtest ongewijzigd groen. De drie aanroepen van de constructor (`ScenarioRunner`,
`DecisionCore`, `ExplanationSession`) geven het fixpunt erbij.

### Bewijs op v2

`measure.sh` naar `na6/` (werkboom op `ff6f642c` met de wijziging ongecommit, uchar `4f283d9d`): de vijf
`*.explanation.txt` en `*.answer.json` bytegelijk aan `na5/` (`cmp`), 171 zinnen. v2 heeft geen objectcreatie.

### Het corpus

`bash scripts/check-service-replay.sh` (`t5/logs/replay-1.log`): PASS, 232 goldens, 614 cases (pin 232), 1102 zinnen,
elk gelijk aan haar stukken, 1251 uitgelegde voorwaarden (vloer 1241), 68 s. Was 1096 en 1245: zes zinnen erbij, alle
zes over een gemaakt object, in twee Catala-goldens (`t5/made-sentences.py` over de 22 goldens met een objectcreatie,
`t5/logs/made-sentences-1.log`):

    jutterswet-hardheidsaftrek-catala, vier cases, een defaults-groep op het gemaakte object, bijvoorbeeld:
    «"richt rekenkamer op"»(B1)#1 heeft als aftrek 200 volgens de variant op plaats 1 van 'aftrek', omdat de rekenkamer_houder hardheid is; verdrongen: de variant op plaats 2 van 'aftrek'. Grondslag: context-override aftrek
    jutterswet-overzeelezing-catala, twee cases, een kenmerktoekenning:
    «"richt eigenaar op"»(V1)#1 is overzees, omdat de registratiezone (200) is groter dan 100
    «"richt eigenaar op"»(V1)#1 is niet overzees, want niet geldt: de registratiezone (50) is groter dan 100

Een kwantorvouw met een gemaakt lid in een uitgelegde vorm heeft het corpus niet (de enige,
`jutterswet-opvolgerquorum`, staat in de voorwaarde van een creatie); de eigen tests dekken die.

### Suites

`mvn -B -o verify` over de hele reactor op de code van `381ea945` (`t5/logs/verify-1.log`): BUILD SUCCESS,
1649 (Drools) / 163 (toolchain, was 161: +2) / 358 (dienst, was 356: +2) / 53 (Quarkus), skip-pin PASS op de drie
poorten, 7m41s. De replay (hierboven) en `na6/` draaiden op dezelfde code vóór twee javadoc-regels.

### Stap 2: de grondslag van een default als lijst

uchar `4f283d9d` → `680e5263`, `make check-pin` groen. Wat de pin meebrengt: een `Default` draagt de `// Bron:`-annotaties
van zijn blok als lijst `statuteAnnotations` (getrimd, niet leeg, in bronvolgorde) en een default uit een blok heeft geen
`source` meer (de opgavemarkering houdt in `source` de naam van haar attribuut); de goldens `jutterswet-keurtermijn` en
`jutterswet-tweeledige-grondslag`.

- **`Verbaliser.groundOf`**: een regel haar annotaties, de opgavemarkering geen, een default `statuteAnnotations`. De groep
  (`PrioritizedDefaults.source`) leest de uitleg niet als grondslag, zoals voorheen. `Ground.write` schreef de entries van
  een lijst al apart met "; " als lijm, dus de stukken scheiden nu twee annotaties van een default; de platte zin is
  dezelfde.
- **Rood eerst**: `ExplanationDefaultStatuteAnnotationsServiceTest` (dienst uit bronnen en uit een IR-document), een
  basisregel met één annotatie en een uitzondering met twee, waarvan de tweede een "; " in een citaat draagt. Op de nieuwe
  pin met de oude `groundOf` rood op beide routes (`t5/logs/red-3.log`): de grondslag van beide defaults verdween zonder
  melding, want `source` is leeg. Na de wijziging groen, met de stukken van de grondslag:

      GROUND[«. Grondslag: » STATUTE{bwb=BWBR0004045, article=3, paragraph=1}«BWBR0004045&artikel=3&lid=1» « · lemma's: »
             LEMMA«uitsluiting» «; » STATUTE{bwb=BWBR0004045, article=3, paragraph=2}«BWBR0004045&artikel=3&lid=2» « · »
             NOTE«"met ingang van de dag van de dienst; ook bij uitzending"»]

  Zo ook de nieuwe golden door de dienst (`t5/ground-parts.py`, `t5/logs/ground-parts-1.log`): "… Grondslag: Jutterswet
  art. 3; Jutterswet art. 3b; een vondst op een stormvloeddag telt tweemaal" →
  `GROUND[«. Grondslag: » NOTE«Jutterswet art. 3» «; » NOTE«Jutterswet art. 3b; een vondst op een stormvloeddag telt tweemaal»]`.
- **De tekst over `Default.source`** bijgewerkt: javadoc van `Ground` (de alinea over de samengevoegde string weg) en van
  `Verbaliser` (bronverwijzing, `groundOf`, `sourceAnnotations`), `docs/ENGINE.md` ("The parts of an explanation"), README
  ("Known losses": de beperking van de grondslag van een default vervalt), en twee testteksten.
- **`DecisionCore.site`** valt terug op `Default.source` waar het deel van een default geen brontekst heeft. Na de pin vult
  die terugval alleen nog de defaults van de opgavemarkering (wat haar javadoc al zei); voor een blok-default zonder
  brontekst gaf zij voorheen de samengevoegde annotaties, nu geen tekst. Geen test pint dat geval (`ScopeDefaultsGroupTest`
  pint alleen sites met brontekst, groen); code ongewijzigd.
- **Aangepaste tests**: `GroundTest.the_source_of_a_default_is_one_annotation_whose_semicolon_stays_in_a_segment` heet
  `a_semicolon_within_one_annotation_stays_in_a_segment`, met een nieuw commentaar; de toets is dezelfde (een "; " binnen
  één annotatie blijft in een segment), alleen de reden in de naam (`Default.source`) bestaat niet meer. De javadoc van
  `ExplanationStatuteAnnotationTest` noemt `Default.statuteAnnotations`. Geen toets veranderd.
- **v2** (`na7/`, toolchain `381ea945` met stap 2 ongecommit, uchar `680e5263`): `*.explanation.txt` en `*.answer.json`
  bytegelijk aan `na6/`; de stukken (`t4/parts.sh` naar `t5/parts7/`) gelijk aan die van `explanation-parts`
  (`t4/parts/`, als JSON vergeleken), 171 zinnen, elk gelijk aan haar stukken. v2 heeft één annotatie per blok.
- **De replay** (`t5/logs/replay-3.log`): eerst FAIL op de gelijkheidspin (234 speelden, 232 gepind; `replay-2.log`), de
  twee nieuwe goldens; `EXPECTED_REPLAY_PLAYED` 232 → 234 met een redenregel. Daarna PASS: 264 in de set van gate 12, 234
  goldens, 622 cases (pin 234), 1113 zinnen (1102 + 4 van `keurtermijn` + 7 van `tweeledige-grondslag`), elk gelijk aan
  haar stukken, 1274 uitgelegde voorwaarden (vloer 1241), 68 s.
- **Suites** (`t5/logs/verify-2.log`, de code van `20c63b5f`): BUILD SUCCESS, 1649 / 163 / 360 (dienst +2) / 53,
  skip-pin PASS op de drie poorten, 6m51s.

### Gates

`make gates` op `20c63b5f` met `TAG/CLUSTER=explanation-made-object-names`: GROEN, 7 stappen in 10m10s (reactor
1649/163/360/53; replay 234 goldens, 622 cases, pin 234; manifest 29 ok, 3 uitgesloten; image en kind-smoke H2–H4
PASS). Log: `werk/defaults-decider/t5/tc-gates.log`.

### Wat open bleef

- `SUBJECT` draagt geen meta voor een gemaakt object (`creation`, `subject`); de werkbank kan de naam niet ontleden zonder
  de tekst te lezen. Niet gevraagd; een kleine uitbreiding van `explanationParts` en de OpenAPI als de werkbank het nodig
  heeft.
- De beschrijving van `SUBJECT` in de OpenAPI noemt alleen "het label van een instantie".
- `DecisionCore.site` valt voor een default uit een blok niet meer terug op de samengevoegde annotaties (die stonden
  daar als plaats van een melding); zo'n melding noemt nu geen plaats. Geen test pint het.
- De naam van een gemaakt onderwerp kan genest raken (`«"…"»(«"…"»(J1)#1)#1`): eenduidig, voor een taalmodel niet
  mooi; mee te nemen in `explanation-for-the-model`.

Geland 2026-10-21 door de hoofdsessie: fast-forward naar `caea9ca0` (`make gates` groen op `20c63b5f`, 10m10s, `TAG/CLUSTER=explanation-made-object-names`; de Result-commit erna raakt alleen de kaart). Pin uchar `680e5263` (met `default-statute-annotations`). Voorlopige keuzes van de bouwer staan hierboven; de eigenaar kan ze omdraaien.
