# playground-rol-geeft-feittype: elke rol in de situatie van de playground noemt haar feittype

*Vastgelegd op 2026-10-15. Volgt op `20261015-playground-geeft-commentaar-en-feittype` ("Punt 2: het feittype van
een rol in de situatie"), dat op uchar wachtte.*

## Aanleiding

De werkbank wil de relaties tussen de objecten van een scenariosituatie tonen, met het feittype per relatie. In s01
van herleving-ww (v2) hangen U1, D1, I4–I8, G1, A1 en P1 via de rol `recht` aan R1, elk via een ander feittype.
`situatie.objecten[].rollen[]` geeft `rol`, `doeltype`, `labels`, `meervoudig` en `regel`, maar niet het feittype,
omdat uchars `ResolvedScenario.RolWaarde` het niet had. De uchar-kaart `scenario-rol-draagt-feittype` geeft
`RolWaarde` het veld `feittype` (het `Feittype` van het model), gevuld door uchars scenario-resolver.

## Wat er gebeurt

- De submodule `uchar` gaat naar de commit van die uchar-kaart.
- `ScenarioGegevens.situatie` zet bij elke rol `feittype`: de naam van het feittype uit het nieuwe veld, in het
  gekozen vocabulaire, met `feittypeCanoniek` ernaast als die naam afwijkt, zoals elke andere naam in de situatie en
  zoals `fixpunt.koppelingen[].feittype`. De bestaande sleutels houden hun vorm.
- `docs/ENGINE.md` noemt de sleutel; `README.md` schrapt het feittype van een rol onder de bekende verliezen.

## Acceptatie

- (a) Op het s01-document (fundament en herleving-ww van v2 plus `scenariodocs/s01.rgs`, zoals de vorige kaart het
  samenstelde) geeft de rol `recht` van U1, D1, I4, G1, A1 en P1 elk het feittype dat `ir.model.factTypes` voor die
  rol en dat type heeft, net als `persoon` van R1 en `dagloon` van M1.
- (b) Nieuw gedrag in een nieuwe testklasse met echte invoer; een bestaand testbestand verandert alleen als het veld
  dat afdwingt, in een eigen commit met de reden in het Result.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `uchar` (de pin), `service/…/http/ScenarioGegevens.java`, een nieuwe testklasse onder
`service/src/test/`, `docs/ENGINE.md`, `README.md`, kaartverplaatsing.

Buiten de kaart: `playground.html` (toont het feittype niet), de werkbank, en de landing en push van de pin: die wachten
tot de uchar-commit op origin staat.
