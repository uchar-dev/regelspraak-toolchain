# explanation-part-groups: de lijm van een weglaatbaar stuk in het stuk, en de varianten van een defaults-groep als groep

*Tekst van de werkbank (2026-10-22, `werknotities/20261022-eval-uitleg-varianten/toolchain-tekst-uitlegstukken.md`,
punten 1–4), door de eigenaar doorgegeven aan de toolchain-sessie. Punt 5 van die tekst (uitkomsten zonder zin) is
de kaart `explanation-computed-attributes`.*

## Wacht op

Niets meer. De eigenaar haalde de wachttijd eraf (2026-10-27): de proef met de oude eval-runner komt er niet; de
definitieve runner stelt zijn weergave samen uit de stukken. Punt 2 bouwt op de identiteit van de beslisser uit
`explanation-ground-source`.

## Aanleiding

De eval-runner van de werkbank stelt uitlegvarianten samen uit `explanationParts` (toolchain `826d3d9f`) en knipt geen
tekst. Gemeten op de vijf service-verzoeken en s01 (209 zinnen, 148.945 tekens):

1. **De lijm vóór `MEMBERS`.** De dubbele punt vóór de leden van een kwantorvouw staat in de lijm ervoor; zonder de groep
   eindigt de voorwaarde op ": " (s01, zin 3: `… "\" is niet voldaan: "`, dan `MEMBERS`). Voorstel, zoals
   `". Grondslag: "` in `GROUND`: `": "` als eerste `TEXT` in `MEMBERS`. Op v2: 12 `MEMBERS`-groepen, alle na
   `'" is niet voldaan: '`.
2. **De niet-beslissende varianten van een defaults-groep staan niet als groep.** Elke "'X' gold niet, want …" staat plat
   op zinsniveau tussen "; '" en de volgende "; " of de grondslag (s01, zin 8). Op v2 staat 26 % van de tekst (38.849
   tekens) in zulke takken, in 65 van de 209 zinnen. Voorstel: per variant een groep (bijv. `VARIANT`, met `decided`
   true/false en voor een verdrongen variant `displaced`), met de scheiding "; " erin.
3. **Een waarde met haar haakjes.** `TEXT " ("`, `VALUE`, `TEXT ") gevuld is"`: zonder de `VALUE` blijft "()" staan.
   Voorstel: de haakjes in een groep met de waarde.
4. **Het herhaalde onderwerp van een `of`-lijst** (open punt van `explanation-parts`): drie `CONDITION`-leden met dezelfde
   `QUOTE` ("de code sz product van de uitkering van het RechtOpUitkering (onbekend) is gelijk aan … of de code sz product
   … "). Eén keer noemen is een andere zin; dat kan alleen de toolchain.

## Doel

Een afnemer kan elk weglaatbaar stuk weglaten zonder tekst te knippen: de lijm hoort bij het stuk (1, 3), een variant is
een groep met haar status (2); de verwoording van een `of`-lijst met één onderwerp (4) als het de stukken niet complexer
maakt. De platte zin in `explanation` blijft bytegelijk, behalve waar punt 4 bewust een andere zin geeft (dan in de
Result met voor en na). OpenAPI en `ContractNamesInMessagesTest` bij; `docs/ENGINE.md`, "The parts of an explanation".

## Acceptatie

- (a) Op de vijf service-verzoeken en s01: zonder elke `MEMBERS`, `VARIANT` met `decided: false` of waarde-groep blijft
  een grammaticale zin over (een test die de stukken weglaat en de rest naast een verwachte zin legt).
- (b) `make gates` groen.

## Result (2026-10-27)

Tak `card/explanation-part-groups` in `wt-tc/explanation-read-objects`, op toolchain main `cff58238` (deel 1 van
`explanation-read-objects-and-displaced`), uchar-pin `c6a45d89` ongewijzigd. Werkmap
`werk/toolchain-2026-10-27/read-objects/` (`meting/reduce.py`, `tools/repin2.py`, `logs/`).

**Wat er veranderde** (`Verbaliser`, `Part`):

1. **De lijm vóór `MEMBERS`.** De dubbele punt is de eerste `TEXT` van `MEMBERS`, bij de kwantorvouw in een voorwaarde
   ("is niet voldaan" + `MEMBERS[": niet voor V2 (1 van de 2)"]`) en bij de vouw in een berekening.
2. **De varianten van een defaults-groep als groep.** Elke variant die niet besliste is een `VARIANT {decided: false}`
   met haar scheiding erin: vóór de beslisser ("; 'jeugdpenning' gold niet, want …"), naast haar in een ongeordende
   groep ("; 'vloedvergoeding' … besliste niet anders"), en in de zin waarin niemand besliste. Een verdrongen variant
   is `VARIANT {decided: false, displaced: true}`: de eerste met "; verdrongen: ", elke volgende met ", ", zodat deel 2
   van `explanation-read-objects-and-displaced` per variant haar uitkomst kan toevoegen. De beslisser en de zin van een
   conflict hebben geen `VARIANT`.
3. **Een waarde met haar haakjes.** Een nieuwe groep `READING`: " (", de `VALUE`, ")", de waarde die de motor las voor
   het operand of het lid ervoor (in een voorwaarde, in een berekening, per lid van een vouw in een berekening). De lijm
   erna (" gevuld is") staat erbuiten. Een waarde zonder haakjes (de kop, "vóór de afronding", een literal) blijft een
   `VALUE`.
4. **Het herhaalde onderwerp van een `of`-lijst: niet gebouwd.** Eén keer noemen verandert de platte zin, en vraagt een
   stuk voor het gedeelde onderwerp buiten de `CONDITION`-leden, terwijl elk lid nu zijn eigen operand met waarde
   draagt; dat maakt de stukken complexer, de voorwaarde die de kaart aan punt 4 stelt.

OpenAPI (de beschrijving van `ExplanationPart`, de soort `READING` in de enum; de vier goldens overgeschreven, alleen
die twee verschillen), `docs/ENGINE.md` (de inleiding van "The parts of an explanation", de rijen `MEMBERS`, `VARIANT`,
`READING`), README ("Contract change"), de javadoc van `Part` en `Verbaliser`. `ContractNamesInMessagesTest` groen
ongewijzigd.

**Getuigen (acceptatie (a)).**

- `ExplanationPartGroupsServiceTest` (dienst, uit bronnen en uit het IR-document): op een eigen model elke zin zonder
  `MEMBERS`, `VARIANT {decided: false}` en `READING` naast de verwachte zin: een variant vóór de beslisser, twee
  verdrongen varianten, een zuster in een ongeordende groep, "geen variant besliste", de leden van een kwantorvouw en
  van een vouw in een berekening, en de gelezen waarden. Op de code van `cff58238` blijven de waarden met hun haakjes,
  de dubbele punt en de varianten staan.
- Replay-leg (`scripts/service_replay.py`, `omissible_glue`): op elke golden van gate 12, per zin, heeft de lijm die
  zonder die stukken overblijft geen lege haakjes, geen haakje zonder waarde, geen dubbele punt zonder vervolg en geen
  dubbele scheiding (en aan het eind van de zin geen dubbele punt of scheiding); anders faalt de golden. Op de stukken
  van `cff58238` faalt de toets op 265 van de 309 zinnen van de zes verzoeken, op die van de tak op 0.
- Bestaande tests die stukken pinnen (de kaart verandert wat ze pinden; geen toets verzwakt): elk verschil is een
  groep om bestaande bladeren, nagerekend met een tijdelijke dump van (oud, nieuw) per gepinde weergave
  (`tools/repin2.py`; 15 pinnen, 7 automatisch, 8 met constanten met de hand): `ExplanationPartsServiceTest`,
  `ExplanationComputationServiceTest`, `ExplanationDefaultsComputationServiceTest`, `ExplanationMadeObjectsServiceTest`;
  `ExplanationPartsCheck` kent `READING` als groep.

**Meting op de vijf service-verzoeken van v2/herleving-ww en s01** (`meting/reduce.py`, jar van de tak tegen die van
deel 1): zonder vlag, met `?explanation=true` en de platte zinnen bytegelijk; de bladeren van elke zin gelijk (alleen
groepen erbij). 309 zinnen: 837 `READING`, 67 `MEMBERS`, 268 `VARIANT {decided: false}` waarvan 18 verdrongen. Zonder
die stukken blijft van 242.145 tekens 173.478 over (28,4 % weglaatbaar), en elke zin is een zin; voorbeeld s01 zin 47:
"G1 heeft als herlevingsdatum 01-08-2025 volgens 'gewenste maand in het herlevingsverzoek art. 21 lid 1 na onderdeel
c': de eerste kalendermaand zonder overschrijding-vanaf-de-gewenste-maand van het recht van de Eindigingsgrond, omdat
het onderwerp de indicatie kan herleefd worden heeft en … . Grondslag: …".

**Gates (acceptatie (b)).** `make gates` op `81dba69e` groen, 7 stappen in 4m58s (`logs/gates-pg1.log`): reactor
173/392/53 testgevallen, replay 249 goldens en 673 cases, met de lijmtoets en 4248 koppelingen van deel 1, twins,
manifest, image, kind-smoke.
