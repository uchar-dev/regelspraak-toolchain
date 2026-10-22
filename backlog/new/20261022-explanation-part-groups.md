# explanation-part-groups: de lijm van een weglaatbaar stuk in het stuk, en de varianten van een defaults-groep als groep

*Tekst van de werkbank (2026-10-22, `werknotities/20261022-eval-uitleg-varianten/toolchain-tekst-uitlegstukken.md`,
punten 1–4), door de eigenaar doorgegeven aan de toolchain-sessie. Punt 5 van die tekst (uitkomsten zonder zin) is
de kaart `explanation-computed-attributes`.*

## Wacht op

De werkbank: "Pas bouwen als de proef van de werkbank laat zien dat de optie iets oplevert" (de eval van de
uitlegvarianten, werkbank-kaart `20261021-eval-uitleg-varianten`). Punt 2 bouwt op de identiteit van de beslisser uit
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
