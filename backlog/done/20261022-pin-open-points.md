# pin-open-points: de pin naar uchar met de open punten van K12, en de dode slotpuntregel weg

*Hoofdsessie, 2026-10-22.*

## Aanleiding

uchar main `a6eed482` brengt sinds de pin `0866c03f`:

- de rest van een instantiegeordend deel wordt ook op een kring getoetst (verifier en frontend; negatief
  `wf045-instance-cycle-positive-remainder`);
- in zes handgeschreven goldens snippets zonder slotpunt;
- de golden `jutterswet-termijnoverdracht`.

`Verbaliser.java` (`definitions`) haalt een slotpunt van de snippet van een `Let` af
(`.replaceAll("\\.$", "")`). Sinds de snippetfix van uchar (`check-admits-instance-cycle`) draagt geen snippet die
frontend schrijft de slotpunt van de zin, en op de pin `0866c03f` had geen Let-snippet in het corpus er nog een. De
regel herstelt dus stil iets wat niet meer voorkomt.

## Doel

De pin `uchar` → `a6eed482`; de replay-pin volgt; de afkapping van de slotpunt in `definitions` en de woorden "zonder de
punt erachter" in haar commentaar gaan weg. Verder niets.

## Acceptatie

- (a) `make check-pin` PASS; de reactor groen; `check-service-replay` met de herrekende pin; de uitleg op de replay
  bytegelijk aan die op `98af8f64` voor elke golden die er al was (de afkapping deed niets meer).
- (b) `make gates` groen (hoofdsessie).

May touch: de pin, `scripts/check-service-replay.sh` (de replay-pin), `README.md` (getallen),
`toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbaliser.java` (`definitions`), deze kaart.

## Result (2026-10-22)

Tak `card/pin-open-points`, vanaf toolchain `63f2146f` (de kaart). Geraakt: de pin `uchar`,
`scripts/check-service-replay.sh` (de replay-pin), `README.md`, `Verbaliser.java` (`definitions`), en deze kaart. Niet
geraakt: geen test, de Makefile, `scripts/service_replay.py`, `uchar/` buiten de pin.

- `751f3bba` de kaart naar `doing/`.
- `71b2d726` pin uchar `0866c03f` → `a6eed482` (4 commits); replay-pin 243 → 244; README-getallen bijgewerkt.
- `79c00567` `Verbaliser.definitions`: `.replaceAll("\\.$", "")` en "zonder de punt erachter" in het commentaar weg.

### De pin

uchar main `a6eed482`. Brengt mee: `9169f439` (verifier en frontend: de rest van een instantiegeordend deel die zonder
verschoven randen kringt, is niet geordend; negatief `wf045-instance-cycle-positive-remainder`), `d2736c79` (snippets
zonder slotpunt in zes handgeschreven goldens: `at-most-reads-until-settled`, `fold-filter-divides-by-zero`,
`jutterswet-hoofdberging`, `-redderkeur`, `-uniciteit-drie-bronnen`, `-vouwrand`), `ceaed6ea` (de golden
`jutterswet-termijnoverdracht`, `EXPECTED_PLAYED` 273 → 274) en `a6eed482` (CONSTRUCTS rij 45). `make check-pin`: PASS,
de versies (ANTLR, Drools, Jackson, JUnit) gelijk.

Wat verschoof:

- **De replay-pin.** De set van uchars gate 12 groeit van 273 naar 274 met `jutterswet-termijnoverdracht`; zij speelt
  ook door de dienst (uit haar `source.rgs`, 3 cases): `EXPECTED_REPLAY_PLAYED` 243 → 244, met de reden in het script.
  `REPLAY_EXCLUDED` ongewijzigd (30). `check-service-replay`: PASS, 274 in de set, 244 goldens, 656 cases, 3512 zinnen
  uitleg, 2281 uitgelegde voorwaarden (vloer 1241), 2075 berekende waarden (vloer 1852), 0 keer "niet is vast te
  stellen", 71 s met vier diensten. De vloeren niet verhoogd.
- **README**, sectie "The gates": `EXPECTED_PLAYED` 273 → 274, `EXPECTED_REPLAY_PLAYED` 243 → 244. De gedateerde meting
  "88 s measured 2026-10-22, 238 goldens" niet aangepast (machineafhankelijk; nu 71 s).
- **Niets anders.** Reactor, tweelingen en TOKA-manifest groen zonder wijziging (zie Suites).

### De slotpuntregel en de bytegelijkheid van de uitleg

De afkapping deed niets meer. Op `a6eed482` dragen de goldens 47 Let-snippets (28 verschillende), geen ervan met
slotpunt; `uchar/toka/` heeft er geen. Gemeten met `tools/let_snippets.py` in de werkmap.

Toets: drie metingen van de replay-uitleg, elk met de set van gate 12 en `--explanation` door de dienst, zoals
`check-service-replay` ze draait. `tools/dump_replay.py` in de werkmap omhult `scripts/service_replay.py` ongewijzigd en
schrijft per golden en case `explanation` en `explanationParts` van het antwoord weg (`meting/<label>/<golden>/<case>`);
`tools/compare.py` vergelijkt per casebestand byte voor byte.

| meting | pin | `Verbaliser` | goldens gespeeld | cases | zinnen |
|---|---|---|---|---|---|
| `before` | `0866c03f` | met afkapping (`751f3bba`, code van `98af8f64`) | 243 | 653 | 3484 |
| `mid` | `a6eed482` | met afkapping | 244 | 656 | 3512 |
| `after` | `a6eed482` | zonder afkapping | 244 | 656 | 3512 |

- `before` ↔ `after`: 653 casebestanden gelijk, 0 verschillend; alleen in `after` de drie cases van
  `jutterswet-termijnoverdracht`. Dat is acceptatie (a): de uitleg is bytegelijk voor elke golden die er al was.
- `mid` ↔ `after` (alleen de wijziging in `Verbaliser`): 656 gelijk, 0 verschillend, ook voor de nieuwe golden.
- `before` ↔ `mid` (alleen de pin): 653 gelijk, 0 verschillend. De zes goldens met de nieuwe snippets spelen alle zes
  (11 cases), maar hun gewijzigde snippets (samengestelde voorwaarden, uniciteit) staan niet in hun uitleg.

Het pad is in de meting bereikt: 21 van de 28 Let-snippets komen als `QUOTE`-stuk in de uitleg voor (126 stukken in 18
goldens, o.a. `anchor/jutterswet-tariefstelling` en de `synth3-*`-goldens), dus via `definitions`.

Gevolg buiten het corpus: een `ir.json` met een handgeschreven Let-snippet met slotpunt krijgt die punt nu in zijn
uitlegzin; de frontend schrijft zo'n snippet sinds uchars snippetfix niet meer.

### Suites

- `check-pin` PASS; reactor (`mvn -B -o verify`) groen: backend 1654, toolchain 170, service 374, service-quarkus 53,
  geen skip, 6:48 min; `check-service-replay` PASS (244, pin 244); `check-twins` PASS, ongewijzigd (3 tweelingen, 18
  cases; vocabulairetweeling 2 cases in 34 verzoeken; getuige 4); `make scenario-test` 32 regels, 29 ok, 3 uitgesloten,
  0 mismatch. Logs in de werkmap: `reactor.log`, `replay.log`, `twins.log`, `scenario.log`, `meting/`.
- Niet gedraaid: `make gates` (image en kind-smoke; de hoofdsessie).

### (b) `make gates` en landing (hoofdsessie, 2026-10-22)

`make gates TAG=pin-open-points CLUSTER=pin-open-points` op `79c00567`: GROEN, 7 stappen in 10m32s (pin PASS; reactor
1654/170/374/53; twins PASS; replay 244 goldens, 656 cases, pin 244; manifest 29 ok, 3 uitgesloten, 0 mismatch; image
`decision-service:pin-open-points`; kind-smoke H2, H3, H4 PASS).
