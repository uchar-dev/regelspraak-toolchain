# explanation-ground-source: de grondslag zegt van welke regel of variant hij is

*Voorstel vanuit de werkbank (2026-10-22), door de eigenaar doorgegeven aan de toolchain-sessie; tekst van de werkbank,
ongewijzigd op deze kop na. Vervolg op `explanation-parts` (toolchain `6b5d3750`). De hoofdsessie kiest de eerste weg
(de identiteit van de beslisser in `GROUND`), niet het alternatief via uchar.*

## Aanleiding

De Uitleg-tab van de werkbank rendert elke uitlegzin uit `explanationParts` en zet de groep `GROUND` klein onder de
zin. De eigenaar wil per zin kunnen zien waar die grondslag vandaan komt: uit de `// Bron:`-regels boven welke regel,
in welk bestand en op welke regel. Dat helpt bij het debuggen van een regelset.

De werkbank kan dat nu niet bepalen zonder te raden:

- `GROUND` draagt geen verwijzing naar de beslisser waarvan hij de annotaties is (`Verbaliser.groundOf(Variant)`: een
  regel haar `statuteAnnotations`, een default die van zijn blok, de opgavemarkering geen).
- Een zin over een kenmerktoekenning heeft niet altijd een `RULE`-stuk. Een defaults-zin noemt meer regels: de
  beslissende variant en de varianten die niet golden of verdrongen werden. Uit de stukken is dus niet af te lezen bij
  welke regel de grondslag hoort.
- De grondslag uit de tekst terugzoeken in de bronnen zou de tekst van de toolchain knippen of interpreteren; dat doet
  de werkbank niet (besluit van de eigenaar, 2026-10-21).
- In de IR staan per regel de naam en de `statuteAnnotations`, maar geen bestand of regelnummer. De werkbank heeft wel
  een koppeling van een reeks annotaties naar de plek in de bronnen: `annotatieblokken` in
  `werkbank/tools/lint-benamingen.py`, met dezelfde lezing van een commentaarblok als uchars
  `RuleChecker.statuteAnnotations`. Met de identiteit van de beslisser vindt zij via de IR diens annotaties en daarmee
  het blok, bestand en regelnummer.

## Doel

- `GROUND` krijgt meta die de beslisser aanwijst waarvan hij de annotaties is, in de termen van de IR:
  - een regel: `rule` = de naam van de regel zoals in `rules[].name` van de IR;
  - een default: `group` = de naam van de defaults-groep, `position` = zijn plaats in de groep (1-gebaseerd, zoals de
    uitleg "de variant op plaats n van 'X'" noemt), en `variant` = zijn naam als hij er een heeft;
  - de toolchain kiest de precieze sleutels en zegt waarom.
- De platte zin in `explanation` en de tekst van de stukken veranderen niet (bytegelijk). Zonder `explanationParts=true`
  verandert het antwoord niet.
- OpenAPI (`OpenApiEmitter`, schema `ExplanationPart`) en `ContractNamesInMessagesTest` bij; `docs/ENGINE.md`,
  "The parts of an explanation".

Alternatief, alleen als de toolchain het beter vindt: uchar levert per regel en per default de bronplek van zijn
`// Bron:`-blok (bestand, eerste en laatste regel) in de IR, en `GROUND` geeft die door. Dat raakt IR-SPEC en is groter;
met de identiteit van de beslisser kan de werkbank de plek zelf vinden.

## Acceptatie

- (a) Op de vijf service-verzoeken van herleving-WW v2 en s01: elke `GROUND` draagt de identiteit van zijn beslisser,
  en de `statuteAnnotations` die de IR die beslisser geeft, ontleden met `engine/Ground` tot precies de kinderen van die
  `GROUND`.
- (b) Een eigen klein model met een regel met twee `// Bron:`-regels, een defaults-groep met een naamloze en een benoemde
  variant, en de opgavemarkering (geen `GROUND`).
- (c) `explanation` en de tekst van de stukken bytegelijk aan nu; `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

Een klik op het grondslagblok onder een zin toont "uit de `// Bron:` boven 'herleving art. 21 lid 1',
`hww-12-….rgs` r. 27" en linkt naar die plek op de definitiepagina; de server zoekt de plek op met de koppeling van de
lint.
