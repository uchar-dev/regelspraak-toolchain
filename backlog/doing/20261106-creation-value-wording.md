# creation-value-wording: de uitleg noemt de waarde uit een objectcreatie met de woorden van de specificatie

*Opgesteld door de werkbank-sessie op 2026-11-06, na `writer-holds-on-no-day` (toolchain `eb912c57d`). Besluit van de
eigenaar, 2026-11-06.*

## Aanleiding

Sinds `timeline-stack-in-service` noemt de uitleg de waarde die een objectcreatie een attribuut van het gemaakte object
geeft "de waarde bij het maken" (`Verbaliser.standingValue` en `emptyBefore`), naast "de invoerwaarde" en "in de
invoer leeg was" op een object uit het verzoek. Het woord is een vertaling van uchars "the value of its making"
(RUNTIME-SEMANTICS ch. 7.8); waarom juist deze woorden, zegt geen kaart. Een lezer zonder die achtergrond begrijpt
"bij het maken" niet, ook de werkbank niet toen zij het in haar objectboom overnam.

Een onderzoek naar het woordgebruik (werkbank, 2026-11-06) vond geen standaardwoord voor deze waarde. De
RegelSpraak-specificatie v2.3.0 noemt het construct ObjectCreatie (§4.3, §9.3, §13.4.6) en elk lid een
waardetoekenning; beginwaarde, startwaarde, initiële waarde, standaardwaarde en defaultwaarde komen er niet in voor.
ALEF zegt "Objectcreatie", kort "creatie", en "Tijdens creatie kan aan attributen een waarde worden toegekend"
(`ALEF/docs/regels/Actie_Objectcreatie.md`). In BRM, de handboeken en ALEF is een begin- of standaardwaarde een
terugvalwaarde voor een leeg gegeven: de rol van de initialisatie, die in dezelfde zin kan staan. "Aanmaken"
onderscheidt de creatie niet, want ook een object uit de invoer wordt aangemaakt (§3.2, het onderschrift van figuur 2).

Besluit van de eigenaar (2026-11-06): de uitleg bouwt de naam uit de twee woorden die wel standaard zijn, het construct
en het werkwoord. "De waarde bij het maken" wordt "de bij de objectcreatie toegekende waarde", en "bij het maken leeg
was" wordt "bij de objectcreatie leeg was".

## Goal

- De uitleg schrijft op een object dat een objectcreatie maakte:
  - `"…; de bij de objectcreatie toegekende waarde 10 van 01-01-2025 tot 01-03-2025 bleef staan"`, waar zij `"de waarde
    bij het maken 10 …"` schreef;
  - `"… houdt als meldwaarde de bij de objectcreatie toegekende waarde 7, want …"`, waar zij `"de waarde bij het maken
    7"` schreef;
  - `"…, omdat de aanvulloon bij de objectcreatie leeg was"`, waar zij `"bij het maken leeg was"` schreef;
  in elke zinsvorm waarin `standingValue` of `emptyBefore` staat: een doel met één waarde, een cel, per periode en de
  zin van een defaults-groep. Op een object uit het verzoek blijven `de invoerwaarde` en `in de invoer leeg was`.
- De playground schrijft de `input` van een gemaakt object als `(creatie …)`, naast `(invoer …)` op een instantie van
  het verzoek, zoals de objectboom van de werkbank; de OpenAPI-beschrijving van `inputOrigin` zegt "creation naast de bij
  de objectcreatie toegekende waarde".
- De Engelse identifiers (`CaseSession.making`, `standingValue`) en de JSON-waarden (`origin: "creation"`,
  `inputOrigin: "creation"`) blijven; de werkbank leest die waarden.
- README, `docs/ENGINE.md` en het commentaar in `scripts/check-service-replay.sh` als huidige stand, zonder "where it
  said …". Een plain `git grep 'bij het maken'` buiten `backlog/done/` vond op `eb912c57d` 34 regels in 12 bestanden;
  tel ook de zinsdelen die over een regeleinde lopen.

## Acceptance

- (a) Rood eerst: de tests die "bij het maken" pinnen (`ExplanationStackPerDayTest`, `TimelineStackHttpTest`) gaan naar
  de nieuwe tekst, en falen op main. Het is een expliciete gedragswijziging van deze kaart: noem in het Result elke
  test bij naam met oud en nieuw. Geen andere verwachting verandert.
- (b) `git grep 'bij het maken'` buiten `backlog/done/` vindt niets meer, ook niet over een regeleinde.
- (c) Over de goldens die de replay speelt verschilt de uitleg alleen in deze zinsdelen; het antwoord en het
  `fixpoint` zijn byte-gelijk.
- (d) De OpenAPI-goldens die de beschrijving van `inputOrigin` dragen, gaan mee.
- (e) `make gates` groen.

May touch: `toolchain/` (`Verbaliser`), `service/` (`OpenApiEmitter`, `playground.html`, tests en OpenAPI-goldens),
`scripts/check-service-replay.sh`, README, `docs/ENGINE.md`, kaartverplaatsing.

## Omvang

Klein.

## Result
