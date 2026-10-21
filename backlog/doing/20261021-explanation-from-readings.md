# explanation-from-readings: de uitleg rendert uit de lezingen van de motor

*Vastgelegd door de hoofdsessie op 2026-10-21, kaart T2 uit het Fable-advies over de waarden in de uitleg
(`research/uitleg-lezingen-2026-10-21/fable-advies-2026-10-21.md` in `uchar-dev/research`, §3, §4, §6, §7). Besluit van de
eigenaar 2026-10-21: weg C, de motor levert de waarden. Volgt op uchar-kaart `fixpoint-readings` met een pin-bump. Sluit
`backlog/new/20261012-verbalisator-getalliteral.md`.*

## Doel

De Verbalisator rendert de voorwaarden uit `DroolsBackend.readingsOf`; zijn eigen evaluatie (`evaluate`, `evalExpr`,
`compare`, `compute`, `holds`, `firstFailingConjunct`, `literalValue`/`parseNumber`) verdwijnt. De zinsvormen blijven:
"omdat <voorwaarde met waarden>", "gold niet, want niet geldt: <het lid waar de motor stopte>", "(onbekend)" voor een
lege waarde (besluit: "(onbekend)" blijft; "niet vast te stellen" verdwijnt voor wat de motor draagt). Een vorm buiten
de scope van een entrypoint krijgt één zin "<regel> staat buiten deze berekening". De dienst bouwt de uitlegbuild bij de
start, achter een descriptorvlag die standaard aan staat. Een kenmerkcheck over een rol op het model van de lader krijgt
haar tekst via een tweede methode op `Walks`.

Twee builds (besluit van de eigenaar, 2026-10-21): de productiebuild zonder reading rules, de uitlegbuild met. Alleen
als het de code niet ingewikkelder maakt (eigenaar, 2026-10-21): een verzoek met `explanation=true` vuurt één keer, in
de uitlegbuild, en geeft daaruit zowel antwoord als lezingen (getuige (a) van `fixpoint-readings` bewijst dat de
productiefeiten daar gelijk zijn); en de scenario-runner leest de sessie die uchars replay doorgeeft in plaats van een
case een tweede keer te spelen. Maakt een van beide de code ingewikkelder, dan niet doen en de reden in de Result
zetten.

## Acceptatie

- (a) Rood eerst: per vormfamilie van `fixpoint-readings` een zin, op de drie routes.
- (b) Elke bestaande uitlegtest ongewijzigd groen, behalve de benoemde pins (een operand die de motor uitrekent en de
  uitleg nu "(onbekend)" of "niet vast te stellen" noemt; een getalliteral in de notatie en eenheid van het model), elk
  in de Result.
- (c) Bewijs op v2 vóór/na: "niet is vast te stellen" van 80 naar 0 op de vormen die de motor draagt, "(onbekend)" alleen
  waar de waarde leeg is (per overgebleven geval de reden), elke oude zin letterlijk terug behalve de benoemde groepen,
  het antwoord buiten de uitleg gelijk.
- (d) In `scripts/check-service-replay.sh` een leg die over de gespeelde goldens met `explanation=true` telt dat "niet is
  vast te stellen" niet meer voorkomt (redendragende lijst voor wat de motor niet draagt, vloer op het aantal
  uitgelegde voorwaarden).
- (e) `make gates` groen.

May touch: `toolchain/` (Verbalisator, runner, sessie), `service/` (doorgeven en de build-optie), tests, README, de pin,
de kaart.
