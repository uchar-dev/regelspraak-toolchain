# explanation-parts: een gestructureerde uitleg, elke zin ook als reeks stukken met een soort

*Opdracht van de eigenaar 2026-10-21, vastgelegd door de hoofdsessie. Bouwt voort op de rendering uit de lezingen van de
motor (`backlog/done/20261021-explanation-from-readings.md`, `backlog/done/20261021-explanation-fold-members.md`).
Besluitvragen gaan naar een Fable-adviseur (besluit van de eigenaar 2026-10-21); het aanbevolen antwoord is een
gemarkeerd voorlopig besluit in deze kaart en het Result.*

## Aanleiding

De werkbank wil de uitleg kleuren per soort, een voorwaardenlijst onder elkaar zetten en de grondslag apart tonen. Nu
levert de dienst alleen platte zinnen (`explanation`). Besluit van de eigenaar: geen opmaaktags in de tekst, en de
werkbank toont alleen wat de toolchain aanlevert; zij knipt of interpreteert de tekst niet zelf, dus zonder stukken toont
haar Uitleg-tab de zin letterlijk.

## Doel

- De dienst en de playground leveren elke uitlegzin ook als reeks stukken met een soort, in een nieuw veld naast
  `explanation` (Engelse naam, de kaart kiest), met één entry per zin, in het antwoord van de dienst en van
  `/playground/analyse` (ook met `via=service`).
- De platte zin in `explanation` blijft het eval-taalmodel voeden, komt uit dezelfde stukken, en is letterlijk gelijk aan
  nu, zodat de eval-runner niets merkt.
- Soorten (voorstel van de eigenaar; de kaart beslist): onderwerp (label van de instantie), naam (attribuut, kenmerk of
  rol, met de canonieke naam ernaast zodat de werkbank haar naam-popover kan openen), waarde (met eenheid, of onbekend),
  regel (regelnaam), verbinding (omdat, want, gold niet, en, of), voorwaarde (met kwantor, geneste lijst en de leden van
  een kwantorvouw), citaat (regeltekst zoals geschreven) en grondslag.
- De grondslag levert de toolchain zelf ontleed aan: wetsverwijzingen (BWBR, artikel, lid, onderdeel), lemma's,
  CGM-begrippen, afwijkingen en de vrije toelichting apart, zodat de werkbank niet op het scheidingsteken " · " hoeft te
  vertrouwen.
- Contract: OpenAPI (`OpenApiEmitter`) en `ContractNamesInMessagesTest` bij.

## Besluitvraag (naar Fable)

Hoort de grondslag in de uitlegzin zelf of in een eigen veld per zin? Zij zegt waarom de regel bestaat, niet hoe de
casus uitpakt, en het citaat maakt de zinnen voor het taalmodel ongeveer twee keer zo lang. Gemeten op v2 (`na4b/`): 83
van de 171 zinnen dragen ". Grondslag: …", samen 43.634 van de 121.861 tekens (36 %). Een eigen veld verandert de platte
zin voor het taalmodel; dat botst met "letterlijk gelijk aan nu".

## Acceptatie

- (a) Voor de vijf service-smoke-verzoeken van herleving-WW v2
  (`/Users/yeb/Developer/uwv/regelspraak-werkbank/rulesets/regelspraak-rules/v2/herleving-ww/service/*.request.json`,
  gelijk aan de momentopname in `werk/defaults-decider/v2/`, regels `a7a5bdf`) en voor een eigen klein model is de
  aaneengeschakelde tekst van de stukken per zin teken voor teken gelijk aan de zin in `explanation`.
- (b) Elke soort komt in een test voor, een geneste of/en-lijst en een kwantorvouw met haar leden ook.
- (c) `make gates` groen.

May touch: `toolchain/.../engine/Verbaliser.java` en wat de zinnen bouwt, `service/` (antwoord, playground, OpenAPI),
de tests van de uitleg, `ContractNamesInMessagesTest`, `scripts/check-service-replay.sh` (de leg van de uitleg mag de
gelijkheid van stukken en zin toetsen), deze kaart.

Out of scope, alleen als open punt: een zin als "zijn code reden … is gelijk aan A of zijn code reden … is gelijk aan B
…" herhaalt het onderwerp zes keer; die zin korter maken verandert de tekst voor het taalmodel en is een eigen besluit.
