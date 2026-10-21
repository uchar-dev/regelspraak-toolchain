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
