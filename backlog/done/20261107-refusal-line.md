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

## Result

*Bouwer, 2026-11-07, op `card/refusal-line` (worktree `wt-tc/refusal-line`, van toolchain main `561b9ccc3`). Pin
ongewijzigd (uchar `fb143a045`). Niet gemerged en niet gepusht: de hoofdsessie landt.*

### Commits

| hash | inhoud |
|---|---|
| `18d3f12a7` | de kaart geclaimd |
| `476b32bf5` | `refusalLine` naast `refusal` (`Playground`), vier toetsen en twee aanscherpingen in `PlaygroundTest`, `docs/ENGINE.md` |
| (deze) | Result |

### Wat er gebouwd is

- `Playground.analyze`, stap 3 (de beslissing van de exec-model-tak):
  - bij `UcharBuild.UnresolvedName`: `refusalLine` is de `line` van de eerste `Site` in `sites()` (de volgorde van de
    zin) met `line > 0`; heeft geen site een regel, dan geen veld;
  - bij `UcharBuild.NotCarried` waarvan de oorzaak een `CaseRunner.Excluded` is (de weigering van de tak, waarvan
    `construct()` de naam uit `transpiler SKIP|MARKER: <soort>: <naam> — …` leest): `refusalLine` is de regel waarop
    `Regel <naam>` staat, via de nieuwe `formLine(construct, model, parseResult)`. Die zoekt onder
    `model.designatable()` de vormen (`Kind.VORM`) met die naam onder uchars naamidentiteit (hoofdletterongevoelig),
    eist er precies één, en neemt de regel van haar declaratie uit `Declarations.of`, dezelfde koppeling waaruit
    `vocabularies.elements[].line` komt. Geen veld bij geen of meer dan één vorm, bij een vorm zonder declaratie (door
    uchars frontend gemaakt) en als de declaraties niet koppelen (`Declarations.Unlinkable`, dat `vocabularies` al als
    `declarationError` meldt);
  - geen veld bij elke andere `NotCarried`: de emitter (ook de verdeling die hij weglaat, waarvan `construct()` een
    naam draagt), de loader, een documentfout, en een `Excluded` die geen vorm noemt (een cyclus, twee regels van één
    naam: `construct()` is dan null).
- `refusal` en `drlError` zijn ongewijzigd; `drlError` krijgt geen regelveld.
- `UcharBuild` is niet veranderd: het model draagt geen bronregel per regel (`ResolvedRule` heeft geen `line`), en de
  koppeling van vorm aan declaratie zit al in de playground (`Declarations`).
- `docs/ENGINE.md`, bij de playground: een eigen punt over `refusalLine` (wanneer het er staat, dat het vanaf 1 telt
  in de ontvangen bron zoals `line` bij `parse.errors` en `resolver.errors`, en wanneer niet), en in de opsomming van
  het antwoord een verwijzing ernaar. De javadoc van `Playground` noemt het veld.

### Toetsen (`PlaygroundTest`, 7 → 11)

- nieuw `an_unresolved_name_in_joined_sources_has_the_line_of_the_join` (de toets van de werkbank): een eerste deel van
  vier regels en een tweede deel met de onopgeloste naam op zijn regel 4, samengevoegd, geven `refusalLine: 8` en
  `refusal` = `onopgeloste naam 'hij een minderjarig is' in de regel 'bijvoeglijk', bronregel 8`;
- nieuw `an_unresolved_name_without_a_line_has_no_refusal_line`: dezelfde naam in twee regels, dus geen site met een
  regel; de zin noemt beide pointers, geen `refusalLine`;
- nieuw `the_refusal_line_is_that_of_the_first_site_with_a_line`: de eerste site heeft geen regel, de derde wel
  (regel 14); `refusalLine: 14`;
- nieuw `a_refusal_of_uchar_loader_has_no_line`: een gehele-periodetoets over een tijdsafhankelijke parameter, die
  uchars loader weigert (DROOLS-028, `loader: …`); geen `refusalLine`;
- aangescherpt `clean_model_yields_drl_text`: een uitvoerbaar model heeft geen `refusalLine`;
- aangescherpt `a_clean_model_that_uchar_does_not_carry_gets_the_uchar_refusal`: `transpiler SKIP: regel: heffing
  markert — …` geeft `refusalLine: 4`, de regel van `Regel heffing markert`;
- aangescherpt `a_name_bound_to_no_declaration_is_not_executable_and_named`: `refusalLine: 6` naast de zin die op
  `bronregel 6` eindigt.

Gericht vooraf: alle `Playground*Test`-klassen, 97 toetsen groen (`werk/refusal-line/logs/playgroundtest-2.log`).

### `make gates`

GROEN, 7 stappen in 6m17s, op `476b32bf5` in de worktree: pin; reactor 2m28s (testgevallen per module 197/508/57);
twins; replay 353 goldens (pin 353), 1000 cases (pin 1000); manifest 32 regels, 29 ok, 3 uitgesloten; image
`decision-service:dev`; kind-smoke H2/H3/H4 PASS. De uitvoer staat in
`/Users/yeb/Developer/uchar-dev/werk/refusal-line/logs/gates.log` en per stap in
`/Users/yeb/Developer/uchar-dev/werk/refusal-line/logs/out-gates/` (kopie van `out/gates/` van de worktree).

### Afwijkingen en kanttekeningen

- **Alleen de weigering van de tak.** De kaart zegt "Weigering van uchars exec-model (`NotCarried`, …)". `construct()`
  is ook gezet bij de verdeling die de emitter weglaat; die is geen vorm van het model (`designatable()` laat een
  verdeling met methode `COMPLEX` weg), maar bij een naamgenoot zou de regel van een andere vorm verschijnen. Daarom
  vraagt de playground de regel alleen bij een `NotCarried` met een `CaseRunner.Excluded` als oorzaak.
- **Grover dan nodig bij ontkoppelde declaraties.** `Declarations.of` werpt `Unlinkable` voor het hele model zodra één
  element niet koppelt (bijvoorbeeld een attribuut dat twee keer in een kop staat). Dan krijgt ook een eenduidige vorm
  geen `refusalLine`. Dat is veilig (geen gok) en valt samen met `declarationError`; preciezer kan alleen door
  `Declarations` per element te laten falen, wat buiten deze kaart valt.
- **Bijvangst in `docs/ENGINE.md`.** De sleutel van de bronregel van een declaratie en van een verwachting stond er
  als `rule`; de code schrijft `line` (sinds `f2d2e8a8a`, de Engelse namen, stond daar `rule`). Twee woorden,
  verbeterd in dezelfde commit, omdat het nieuwe punt naar die `line` verwijst.

### Voor de werkbank-sessie

`POST /playground/analyse` geeft bij `executable: false` naast `refusal` het veld `refusalLine`: een geheel getal dat
vanaf 1 telt in de bron zoals de playground haar ontving, net als `line` bij `parse.errors` en `resolver.errors`. Het
staat er alleen als de weigering een plaats in de bron heeft:

- bij een onopgeloste naam (`onopgeloste naam …`): de regel van de eerste site in de zin die een regel heeft (de zin
  eindigt die site op `, bronregel <n>`); noemt de zin alleen pointers (`op /rules/…`), dan is er geen veld;
- bij een vorm die de exec-model-tak niet verlaagt (`transpiler SKIP: regel: <naam> — …`, of `MARKER`): de regel
  waarop `Regel <naam>` staat, dezelfde als `line` van die vorm in `vocabularies.elements`, als het model precies één
  vorm met die naam kent en die een declaratie in de bron heeft;
- geen veld bij een weigering van de emitter of de loader (`loader: …`), een documentfout, een weigering die geen vorm
  noemt, of als de declaraties niet koppelen (`vocabularies.declarationError`).

De zinnen in `refusal` en `drlError` zijn ongewijzigd; `drlError` krijgt geen regelveld. Commit: toolchain
`476b32bf5` op `card/refusal-line` (de hash op main volgt bij de landing).

### Landing (hoofdsessie, 2026-11-07)

Bij de controle vergeleek `formLine` de naam van een vorm met `equalsIgnoreCase`, terwijl de javadoc uchars
naamidentiteit noemt; die vouwt alleen A–Z. Het vergelijkt nu met `Namen.norm`, zoals `Markers` (eigen commit).
`make gates` daarna groen: 7 stappen in 6m24s (`uchar-dev/werk/refusal-line/logs/gates-landing.log`). Geland op main.
