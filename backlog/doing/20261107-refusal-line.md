# refusal-line: de playground geeft de bronregel van een weigering als eigen veld

*Gevraagd door de werkbank-sessie, 2026-11-07; door de eigenaar aan de hoofdsessie gegeven. Opgepakt na de landing
van uchars kaart `currency-signs-and-declared-units` (uchar `fb143a045`) en de pin-bump `561b9ccc3`.*

## Aanleiding

Bij een onopgeloste naam antwoordt `/playground/analyse` met `executable: false`, en `refusal` en `drlError` dragen
de zin van `UcharBuild.Site.sentence()`, bijvoorbeeld "onopgeloste naam 'hij een minderjarig is' in de regel
'bijvoeglijk', bronregel 8". Het getal telt in de bron zoals de playground haar ontving. De werkbank stuurt een
samenvoeging van bestanden. Bij parse- en resolverfouten noemt ze het bestand en de regel daarin al, omdat die fouten
hun `line` als eigen veld dragen (`parse.errors[].line`, `resolver.errors[].line`). Bij een weigering zou ze het getal
uit de zin moeten lezen, en dat wil ze niet.

Nu (`service/.../http/Playground.java`, r. ~221–241): `UcharBuild.UnresolvedName` en `UcharBuild.NotCarried` zetten
`executable: false` en `refusal` als zin; de DRL-weergave zet `drlError` als zin. `UcharBuild.Site` draagt de regel al
als getal (`line`, 0 als geen melding met een regel de naam citeert, of als meldingen op meer dan één regel dat doen).

## Goal

`/playground/analyse` geeft naast `refusal` het veld `refusalLine`: een geheel getal dat vanaf 1 telt in de ontvangen
bron, zoals `line` bij `parse.errors` en `resolver.errors`. Het veld staat er alleen als de weigering een plaats in de
bron heeft. De zinnen in `refusal` en `drlError` blijven zoals ze zijn.

## Besluiten (hoofdsessie, als beslisser voor de toolchain)

- **Onopgeloste naam** (`UnresolvedName`): `refusalLine` is de `line` van de eerste site in de volgorde van de zin
  waarvan `line > 0`. Heeft geen site een regel, dan is er geen `refusalLine`. Bij meer sites noemt de zin ze allemaal,
  en het veld wijst de eerste plaats aan.
- **Weigering van uchars exec-model** (`NotCarried`, "transpiler SKIP: regel: …" of MARKER): wel een regel als
  `construct()` een vorm van de regellaag noemt waarvan het model de bronregel van de declaratie kent (de regel met
  `Regel <naam>`, zoals de playground die al voor declaraties geeft). Een weigering van de loader, een uitgesloten case
  en een weigering zonder construct krijgen er geen. Kan het model de vorm niet eenduidig aan één regel koppelen (twee
  vormen met die naam), dan ook geen veld; nooit een gok.
- `drlError` krijgt geen regelveld: de werkbank vraagt het niet, en de weergave van de DRL-tak is geen oordeel.

## Acceptance

- Toets in `PlaygroundTest`: een bron van twee delen, samengevoegd, met de onopgeloste naam op regel 4 van het tweede deel
  (regel 8 van de samenvoeging), geeft `refusalLine: 8`, en `refusal` blijft gelijk aan de zin van nu.
- Toetsen: een uitvoerbaar model heeft geen `refusalLine`; een onopgeloste naam zonder regel (de site met alleen een
  pointer) heeft geen `refusalLine`; een `NotCarried` op een regel geeft de regel van `Regel <naam>`; een weigering van
  de loader heeft geen `refusalLine`.
- `docs/ENGINE.md` zegt wanneer `refusalLine` er staat en hoe het telt, bij de beschrijving van de playground.
- `make gates` van de toolchain groen.

May touch: `service/src/main/java/dev/uchar/regelspraak/toolchain/service/http/Playground.java`,
`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/UcharBuild.java` (alleen als de regel van een
`NotCarried` daar het best te bepalen is), de toetsen van de playground, `docs/ENGINE.md`, deze kaart.

Out of scope: de zinnen van `refusal` en `drlError`; `bin/regelspraak check` en de dienst (`DecisionCore`); uchar (een
weigering in uchar draagt geen gestructureerde plaats; als dat nodig blijkt, is het een uchar-kaart).

## Na de landing

Een korte tekst voor de werkbank-sessie: het veld, wanneer het er staat, en de commit.
