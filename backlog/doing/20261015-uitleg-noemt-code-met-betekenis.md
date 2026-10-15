# uitleg-noemt-code-met-betekenis: een enumeratiewaarde met een afwijkende benaming staat in de uitleg met haar waarde

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `333c37c`.*

## Aanleiding

De werkbank toont per scenario de uitleg in het vocabulaire dat de gebruiker kiest. In het vocabulaire `cgm` van
herleving-ww (v2) heeft een enumeratiewaarde een code als benaming. Op s01 staat canoniek "… zijn code reden
beëindiging recht op uitkering ww (Hoogte inkomsten uit verzekerde arbeid) is gelijk aan Hoogte inkomsten uit
verzekerde arbeid of …", en in `cgm` "… (01) is gelijk aan 01 of … 02 …". De lezer ziet dan alleen de code; de zin wordt
er in `cgm` slechter op dan canoniek.

`Verbalisator.render` (r. 330–334) geeft voor een enumeratiewaarde `Naamgeving.waarde`: de benaming in het gekozen
vocabulaire, of de waarde zelf. Dat geldt voor de waarde tussen haakjes en voor een literal als vergelijkingswaarde
(`renderOperand`, r. 323–328).

## Wat er gebeurt

Noemt de uitleg een enumeratiewaarde in een vocabulaire waarin die waarde een benaming heeft die verschilt van de
gedeclareerde waarde, dan schrijft de Verbalisator "<benaming> · <gedeclareerde waarde>", bijvoorbeeld "01 · Hoogte
inkomsten uit verzekerde arbeid". Dat geldt overal waar de uitleg een enumeratiewaarde noemt, ook als
vergelijkingswaarde. Is de benaming gelijk aan de waarde zoals uchar gedeclareerde namen vergelijkt (`Namen.norm`), of
is er geen benaming, dan blijft de zin zoals nu. De canonieke keuze verandert niet.

Alleen de uitleg verandert. Het antwoord van de dienst, de OpenAPI-documenten en het lezen van een verzoek houden de
benaming alleen. De toolchain leidt niets af: de benaming en de gedeclareerde waarde zijn die van uchar.

## Acceptatie

- (a) Een nieuwe testklasse met een eigen klein document: een domein met benamingen `(cgm: 01)`, een waarde met een
  benaming die alleen in hoofdletters verschilt, en een waarde zonder benaming; de zinnen in `cgm` en canoniek gepind,
  de waarde tussen haakjes en de vergelijkingswaarde.
- (b) `README.md` noemt het, bij de vocabulaires.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbalisator.java`, een nieuwe
testklasse, `README.md`, kaartverplaatsing. Niet `uchar/`.

## Raakvlakken

- `20261012-verbalisator-getalliteral` gaat over de notatie van een getalliteral en de eenheid, niet over
  enumeratiewaarden. Die kaart blijft staan.
- De werkbank (`werkbank/static/`) toont de uitleg en wordt elders aangepast.

## Result (2026-10-15)

De kaart blijft in `doing/`: acceptatie (c) wacht op een akkoord over twee bestaande testbestanden (zie "Open").

### Wat de kaart opleverde

- **`Verbalisator.enumeratiewaarde`**, aangeroepen uit `render`: de naam uit `Naamgeving.waarde`, en als die volgens
  `Namen.norm` verschilt van de gedeclareerde waarde, die waarde erachter met " · ". Dat geldt voor de waarde tussen
  haakjes en voor de vergelijkingswaarde, want beide lopen via `renderOperand`. `Naamgeving`, het antwoord van de dienst
  (`AntwoordBouwer`), de OpenAPI-documenten en het lezen van een verzoek veranderen niet.
  Een waarde die geen gedeclareerde waarde van het domein is, staat er zoals ze is, zonder " · ".
- **Tekst.** `README.md` (Vocabularies) en de klassedoc van de Verbalisator noemen de vorm.

### Getoetst op echte invoer

Het s01-document van de werkbank (de v2-bronnen van regelspraak-rules `a1c094a` plus het scenariodocument van de
replay), via de analyzer van een werkbank op deze branch, s01-v01 in `cgm`. Voor (toolchain `333c37c`):

    G1 is kalendermaandsystematiek, omdat zijn code reden beëindiging recht op uitkering ww (01) is gelijk aan 01 of
    zijn code reden beëindiging recht op uitkering ww (01) is gelijk aan 02 of … is gelijk aan 08

Na (`c62d2ac`):

    G1 is kalendermaandsystematiek, omdat zijn code reden beëindiging recht op uitkering ww (01 · Hoogte inkomsten uit
    verzekerde arbeid) is gelijk aan 01 · Hoogte inkomsten uit verzekerde arbeid of zijn code reden beëindiging recht
    op uitkering ww (01 · Hoogte inkomsten uit verzekerde arbeid) is gelijk aan 02 · Urenverlies van minder dan 5 of …
    is gelijk aan 08 · Omvang zelfstandig werk na startperiode

In `cgm` wijken nog steeds 2 van de 20 zinnen af van canoniek, dezelfde twee. Canoniek en `handboek` geven op s01 dezelfde
uitleg als voor de wijziging.

### Testwijzigingen

- Nieuw: `toolchain/…/engine/UitlegCodeMetBetekenisTest` (2 tests, via `ScenarioRunner.runMetUitleg`), met een eigen
  document: een domein met `'Hoogte inkomsten uit verzekerde arbeid' (cgm: 01)`, `'Overlijden' (cgm: OVERLIJDEN)` en
  `'Onbekend'` zonder benaming. In `cgm` staan de zinnen "(01 · Hoogte inkomsten uit verzekerde arbeid) is gelijk aan
  01 · Hoogte inkomsten uit verzekerde arbeid", "(OVERLIJDEN)" en "(Onbekend)"; canoniek staan de zinnen zoals ze
  waren.
- Rood zonder de wijziging: "(01) is gelijk aan 01"; de canonieke test was al groen.
- Geen bestaand testbestand gewijzigd (zie "Open").

### Poorten

De zeven stappen van `make gates` zijn los gedraaid, de reactor met `-Dmaven.test.failure.ignore=true`, omdat
`make gates` bij de reactor stopt:
- pin: uchar op `99f614b8`;
- reactor (5 min 31 s): 1578 tests in uchars backend, 131 in toolchain (was 129, plus de 2 nieuwe; vloer 120), 269 in
  service (vloer 235) en 47 in service-quarkus, geen skip. **Rood: 2 tests**, beide op de oude handboekzin (zie
  "Open"); verder niets;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS, het cluster daarna verwijderd.

### Open

- Twee bestaande tests pinnen de handboekzin van de golden `jutterswet-vocabulaires`, waar `barnsteen` de benaming
  `amber` heeft en `wrakhout` de benaming `hout van een wrak`. Met deze kaart wordt die zin "zijn soort (amber ·
  barnsteen) is gelijk aan amber · barnsteen" en "zijn soort (hout van een wrak · wrakhout) is gelijk aan amber ·
  barnsteen":
  - `toolchain/…/engine/VocabulaireScenarioTest`, `de_uitleg_noemt_de_declaraties_in_het_gekozen_vocabulaire`, r. 43–44;
  - `service/…/VocabulaireAntwoordTest`, `het_antwoord_in_het_handboek`, r. 56.
  Een bestaand testbestand verandert pas na akkoord van de eigenaar; daarom zijn ze niet aangepast. De werkelijke
  uitvoer in het rapport van de reactor is letterlijk de nieuwe zin. Na akkoord: die drie regels aanpassen in een eigen
  commit, de reactor opnieuw, en de kaart naar `done/`.

### Commits

`0f47abd` kaart · `21ce408` claim · `c62d2ac` de Verbalisator, de nieuwe testklasse en de README · deze Result.
