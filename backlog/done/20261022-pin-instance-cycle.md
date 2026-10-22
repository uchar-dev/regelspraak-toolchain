# pin-instance-cycle: de pin naar uchar met de instantiegeordende kring in de frontend

*Hoofdsessie, 2026-10-22: de laatste pin van het programma bruggen v2 + uitleg van die dag.*

## Aanleiding

uchar main `0866c03f` brengt sinds de pin `88c4624b` (T1):

- `instance-cycle-household-reads`: lezingen binnen het huishouden;
- `check-admits-instance-cycle`: `check` laat een kring over een schakelrelatie toe en noemt een lezing buiten de
  afbakening (RGS-357), plus een snippetfix: een snippet stopt vóór de slotpunt van de zin;
- twee clingo-fixes (CLINGO-030, de binding van een bewaking in een vouwlid);
- twee nieuwe goldens (`jutterswet-vergunningtermijnen`, `jutterswet-vroege-vondsten-zonder-schorsing`), en in 38
  goldens snippets zonder slotpunt.

De werkbank heeft de toelating nodig voor K12 in v2, zodra hww-06 herschreven is (normvraag B2.5).

## Doel

De pin `uchar` → `0866c03f`; de replay-pin en de README-getallen volgen; wat in de toolchain een snippet met slotpunt
pint, volgt de fix. Geen nieuwe functionaliteit.

## Acceptatie

- (a) `make check-pin` PASS; de reactor groen; `check-service-replay` met de herrekende pin; elke aangepaste test en
  zin in de Result, met reden.
- (b) `make gates` groen (hoofdsessie).

May touch: de pin, `scripts/check-service-replay.sh` (de replay-pin), `README.md`, tests die een snippet of zin pinnen
die de pin bewust verandert, deze kaart.

## Result (2026-10-22)

Tak `card/pin-instance-cycle`, vanaf toolchain `34b319f5` (de kaart). Geraakt: de pin `uchar`,
`scripts/check-service-replay.sh` (de replay-pin), `README.md`, en deze kaart. Niet geraakt: geen test (geen test pinde
een snippet of zin met de slotpunt, zie hieronder), geen broncode, de Makefile, `uchar/` buiten de pin.

- `01bc48d0` de kaart naar `doing/`.
- `957b5338` pin uchar `88c4624b` → `0866c03f` (17 commits); replay-pin 241 → 243; README-getallen bijgewerkt.

### De pin

uchar main `0866c03f`. Brengt mee: `instance-cycle-household-reads` (lezingen binnen het huishouden; verifier en Drools
`Cycles`), `check-admits-instance-cycle` (`check` laat de instantiegeordende kring over een schakelrelatie toe en noemt
een lezing buiten de afbakening, RGS-357), de snippetfix uit die kaart (een snippet stopt vóór de slotpunt van de zin;
38 rgs-goldens, de TOKA-dump en zes slices opnieuw uitgegeven), de clingo-fix `23575890` (een bewaking binnen een vouwlid
bindt het onderwerp van de regel) met `0cedb232` (de zin over die fout in de NOTE van `jutterswet-filterwacht`), en de
nieuwe goldens `jutterswet-vergunningtermijnen` en `jutterswet-vroege-vondsten-zonder-schorsing`. `make check-pin`:
PASS, de versies (ANTLR, Drools, Jackson, JUnit) gelijk.

Wat verschoof:

- **De replay-pin.** De set van uchars gate 12 groeit van 271 naar 273 met `jutterswet-vergunningtermijnen` en
  `jutterswet-vroege-vondsten-zonder-schorsing`; beide spelen ook door de dienst: `EXPECTED_REPLAY_PLAYED` 241 → 243,
  met de reden in het script. Eerst gedraaid met de oude pin: FAIL "243 goldens speelden, EXPECTED_REPLAY_PLAYED pint
  241", verder niets rood. `REPLAY_EXCLUDED` ongewijzigd (30). `check-service-replay`: PASS, 273 in de set, 243 goldens,
  653 cases, 3484 zinnen uitleg, 2257 uitgelegde voorwaarden (vloer 1241), 2064 berekende waarden (vloer 1852), 0 keer
  "niet is vast te stellen", 71 s met vier diensten. De vloeren niet verhoogd. `jutterswet-vergunningketen` heeft sinds
  `d9131437` een `source.rgs` en speelt daardoor uit haar bron in plaats van uit het `ir.json` (de regel van
  `scripts/service_replay.py`: de bron als die er is); zij speelt.
- **README**, sectie "The gates": `EXPECTED_PLAYED` 271 → 273, `EXPECTED_REPLAY_PLAYED` 241 → 243. "About 90 seconds
  … (88 s measured 2026-10-22, 238 goldens)" niet aangepast: een gedateerde meting, en nu 71 s gemeten
  (machineafhankelijk).
- **Niets anders.** De reactor (`mvn -B -o verify`) groen zonder wijziging: backend 1654, toolchain 170 (vloer 120),
  service 374 (vloer 235), service-quarkus 53 (vloer 43), 7:02 min, geen skip. `check-twins`: PASS, ongewijzigd (3
  tweelingen, 18 cases; vocabulairetweeling 2 cases in 34 verzoeken; getuige 4). `make scenario-test`: 32 regels, 29 ok,
  3 uitgesloten, 0 mismatch: `scripts/toka-manifest-exclusions.txt` ongewijzigd.

### De snippetfix in de distributie

Geen test aangepast: geen test van de distributie pint een snippet of uitlegzin met de oude slotpunt. Zo nagegaan:

- de reactor is op de nieuwe pin groen zonder wijziging (hierboven), ook de tests die uit uchars corpus lezen;
- de 80 verschillende oude snippets met slotpunt uit de diff `88c4624b..0866c03f` (`corpus/`, `toka/`), elk met de laatste 30 tekens,
  vast gezocht buiten `uchar/`: alleen treffers in `examples/toka/toka-26`, `toka-38` en `toka-41` (`.rgs`-bron, waar de
  punt de zin sluit; geen pin);
- `[a-z0-9]\. \(` (de vorm "…kalendermaand. (01-08-2026)") en "voorwaarden voldoet" in tests, docs en scripts: geen
  treffer buiten `LICENSE`, `docs/historical/` en `specification/`.

Direct getoond: `bin/regelspraak ir` op `uchar/corpus/golden/jutterswet-ploegaandeel/source.rgs` geeft de snippet
"hij aan alle volgende voorwaarden voldoet: - zijn ploegomvang is ongelijk aan 0 - het aandeel is groter dan 0",
zonder slotpunt, gelijk aan het `ir.json` van de pin.

### Vondsten

Geen bug in de toolchain, geen in uchar.

1. **`Verbaliser.definitions` haalt een slotpunt van de snippet van een Let** (`toolchain/.../engine/Verbaliser.java`
   r. 1261, `replaceAll("\\.$", "")`, sinds `46185bb0`). Op deze pin draagt geen Let-snippet in uchars goldens nog een
   slotpunt (47 Let-snippets, 0 met slotpunt, gemeten met een script over `uchar/corpus/golden/**/ir.json`), want de
   snippetfix dekt `variabeleOnderdeel`. Niet weggehaald: buiten de "May touch", en de regel is onschadelijk.
   Kandidaat voor opruimen.
2. **Handgeschreven goldens dragen nog snippets met slotpunt.** 18 snippets in 9 goldens zonder `source.rgs`
   (`jutterswet-kustregister`, `-nummerregister`, `-strandregister`, `-hoofdberging`, `-uniciteit-drie-bronnen`,
   `-redderkeur`, `-vouwrand`, `at-most-reads-until-settled`, `fold-filter-divides-by-zero`): uniciteitsvormen en
   samengestelde voorwaarden. uchars snippetfix gaf alleen de rgs-goldens opnieuw uit. Geen gate rood: de replay en de
   tests lezen ze zoals ze zijn. Een uitlegzin uit zo'n golden kan nog "… . (" lezen. Voor uchar, niet voor deze kaart.

### Suites

- `check-pin` PASS; reactor groen (1654/170/374/53, 7:02 min); `check-service-replay` PASS (243, pin 243);
  `check-twins` PASS; `scenario-test` 32/29/3/0. Logs in de werkmap (`reactor-1.log`, `replay-1.log` met de oude pin,
  `replay-2.log`, `twins.log`, `scenario.log`).
- Niet gedraaid: `make gates` (image en kind-smoke; de hoofdsessie).

### (b) `make gates` en landing (hoofdsessie, 2026-10-22)

`make gates TAG=pin-instance-cycle CLUSTER=pin-instance-cycle` op `957b5338`: GROEN, 7 stappen in 10m27s (pin PASS;
reactor 1654/170/374/53; twins PASS; replay 243 goldens, 653 cases, pin 243; manifest 29 ok, 3 uitgesloten, 0
mismatch; image `decision-service:pin-instance-cycle`; kind-smoke H2, H3, H4 PASS). De slotpunt die
`Verbaliser.java` r. ±1261 van een Let-snippet haalt, gaat weg zodra uchar de laatste handgeschreven snippets met
slotpunt heeft gelijkgetrokken (fix in uchar loopt).
