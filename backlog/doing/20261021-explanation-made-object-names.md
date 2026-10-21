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

## Acceptatie

- (a) Rood eerst: een eigen model met een regel over een gemaakt object en een kwantorvouw over gemaakte objecten, op de
  drie routes.
- (b) Bestaande uitlegtests ongewijzigd groen; op v2 elke zin gelijk.
- (c) `make gates` groen.

May touch: `toolchain/.../engine/Verbaliser.java`, `ScenarioRunner`, `service/.../DecisionCore.java`, de tests van de
uitleg, deze kaart.
