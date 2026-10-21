# explanation-for-the-model: de uitlegtekst voor het taalmodel, gemeten met de eval

*Vervolg op `explanation-parts`, uit Fable-advies 3 (2026-10-21, `uchar-dev/werk/defaults-decider/fable-advies-3.md`
§1). De primaire afnemer van de uitleg is het taalmodel (persoonlijke assistent); deze kaart verandert de platte zin pas
na een meting.*

## Aanleiding

Op v2 draagt 83 van de 171 zinnen ". Grondslag: …", 36 % van de tekens; van de grondslag heeft het model vooral de
wetsverwijzingen nodig (Fable mat 438 segmenten: 133 wetsverwijzingen, 41 citaten, 129 lemma's, 21 afwijkingen, 33 vrije
toelichtingen). Een `of`-lijst herhaalt het onderwerp per lid ("de code sz product van de uitkering van het
RechtOpUitkering (onbekend) is gelijk aan …", drie keer).

## Doel

Met de stukken van `explanation-parts` stelt de eval-runner varianten van de tekst samen (vol, alleen wetsverwijzingen,
zonder grondslag, herhaald onderwerp samengetrokken) en meet ze op de eval van de werkbank; de variant die wint, wordt
de platte zin van `explanation`, uit dezelfde stukken, in één keer, met de debugweergave van de werkbank op dezelfde
tekst.

## Acceptatie

- (a) De meting per variant in de Result, met de keuze en haar reden.
- (b) `explanation` is de gekozen variant; de stukken en de zin blijven teken voor teken gelijk.
- (c) `make gates` groen.

## Gesloten (2026-10-21)

Opgegaan in de werkbank-kaart `eval-uitleg-varianten` (besluit van de eigenaar): de varianten worden in de eval-runner
van de werkbank samengesteld uit `explanationParts` en daar gemeten; uchar en de toolchain hoeven er niets voor. Laat de
proef zien dat een optie stukken vraagt die nu niet netjes weg kunnen (een waarde met haar haakjes, de niet-beslissende
varianten van een defaults-groep), of wint een variant die elke afnemer moet krijgen, dan komt dat als nieuwe
toolchain-kaart.
