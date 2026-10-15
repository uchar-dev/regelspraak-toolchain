# uitleg-volgt-de-kenmerksoort: de uitleg schrijft een kenmerk met het werkwoord van zijn soort

*Vastgelegd op 2026-10-15. Regelnummers op toolchain `6b14d1b`.*

## Aanleiding

De werkbank toont per scenario de uitleg van de Verbalisator. Op s01 van herleving-ww (v2) staat daar "R1 is niet
uitkeringsduur verstreken, want …". RegelSpraak geeft elk van de drie kenmerksoorten een eigen werkwoord (§3.5):

- bezittelijk krijgt "heeft": `de uitkeringsduur verstreken kenmerk (bezittelijk);` (regelspraak-rules,
  `v2/herleving-ww/rules/hww-09-verschuiving.rgs` r. 31), met de regel "Een RechtOpUitkeringWwBijOntslag heeft de
  uitkeringsduur verstreken" (r. 58); de scenario's schrijven "R1 heeft de uitkeringsduur verstreken" en "R1 heeft
  geen uitkeringsduur verstreken";
- zelfstandig krijgt "is een": `het recht op grond-van-de-wia kenmerk;` (`hww-12-wia.rgs` r. 13), met de regel "Een
  RechtOpUitkering is een recht op grond-van-de-wia" (r. 22);
- bijvoeglijk krijgt "is": `is waardevol … kenmerk (bijvoeglijk);` in de golden `jutterswet-vocabulaires` van uchar.

uchar volgt dat: het model draagt de soort in `Objecttype.kenmerkSoort(naam)`, en `ScenarioResolver.werkwoordPast`
weigert "is" bij een bezittelijk kenmerk. De Verbalisator schrijft daarentegen altijd "X is <kenmerk>" en "X is niet
<kenmerk>" (`verbaliseerToekenning`, r. 112 en 117), en een voorwaarde over een kenmerk als "het onderwerp (niet)
<kenmerk> is" (`verbaliseerAtoom`, r. 150–157), ook als falende conjunct na "want niet geldt:".

## Wat er gebeurt

Waar de uitleg een kenmerk als gezegde schrijft, volgt het werkwoord de soort van de declaratie:

| soort | aanwezig | afwezig |
|---|---|---|
| bijvoeglijk | V1 is waardevol | V1 is niet waardevol |
| bezittelijk | R1 heeft de uitkeringsduur verstreken | R1 heeft geen uitkeringsduur verstreken |
| zelfstandig | R1 is een recht op grond-van-de-wia | R1 is geen recht op grond-van-de-wia |

Dat geldt voor de zin over een toegekend of niet toegekend kenmerk (hoofdzin) en voor een voorwaarde over een kenmerk
van het onderwerp of van een rol (bijzin: "het onderwerp de uitkeringsduur verstreken heeft", "de samenloop geen
wia-recht heeft"), dus ook voor de falende conjunct. Wat de uitleg tussen aanhalingstekens uit de regel citeert,
blijft zoals het geschreven is.

Het lidwoord bij een aanwezig bezittelijk kenmerk: het model draagt het lidwoord van de declaratie niet. Een
`Characteristic` in de IR heeft `name`, `kind`, `timeline` en `designations` (uchar `spec/IR-SPEC.md` ch. 2.2), en
`DefinitionResolver.kenmerkNaam` neemt de naam zonder `bepaaldLidwoord`. De uitleg neemt daarom het lidwoord dat de
regeltekst zelf vóór de kenmerknaam schrijft: voor de hoofdzin de conclusie van de regel ("… heeft de uitkeringsduur
verstreken"), voor een voorwaarde de tekst van die voorwaarde. Schrijft de regeltekst er geen, dan de naam zonder
lidwoord. De Result verantwoordt die keuze.

De soort is die van de declaratie, ook in een ander vocabulaire; de naam is de benaming via `Naamgeving`, zoals nu.
Is de soort van een kenmerk in een voorwaarde niet vast te stellen, dan citeert de uitleg de voorwaarde, zoals bij
elke vorm die de Verbalisator niet structureert.

Alleen de uitleg verandert. De toolchain leidt de soort niet af: die is van uchar.

## Acceptatie

- (a) Een nieuwe testklasse met een eigen klein document met de drie soorten: per soort een toegekend en een niet
  toegekend kenmerk, een kenmerk als voorwaarde (aanwezig en ontkend) en als falende conjunct, canoniek en in een
  vocabulaire.
- (b) `README.md` noemt de vorm, bij de vocabulaires en de uitleg.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `toolchain/src/main/java/dev/uchar/regelspraak/toolchain/engine/Verbalisator.java`, een nieuwe
testklasse, `README.md`, kaartverplaatsing. Niet `uchar/`. Bestaande testbestanden alleen na akkoord.

## Raakvlakken

- `20261012-verbalisator-getalliteral` gaat over de notatie van een getalliteral en de eenheid, niet over het
  werkwoord. Die kaart blijft staan.
- `20261015-uitleg-noemt-code-met-betekenis` (done) zet een code met haar waarde in de uitleg; dat blijft zo.
- De werkbank (`werkbank/static/`) toont de uitleg en wordt elders aangepast.

## Result (2026-10-15)

### Wat de kaart opleverde

Regelnummers van de toolchain op `62cbd01`.

- **`Verbalisator.gezegde`** (r. 451): een kenmerk als gezegde, met het werkwoord van zijn soort uit
  `Objecttype.kenmerkSoort` en de naam uit `Naamgeving.kenmerk`. `verbaliseerToekenning` (r. 113) zet het in de
  hoofdzin ("R1 heeft geen uitkeringsduur verstreken, want …"), `verbaliseerAtoom` (r. 161) in de bijzin van een
  voorwaarde over een kenmerk ("het onderwerp het verzoek heeft"), en daarmee ook in de falende conjunct na "want niet
  geldt:". Een citaat uit de regel ("de voorwaarde \"…\" niet is vast te stellen") blijft zoals het was.
- **Het lidwoord** (`lidwoordVoor`, r. 469): bij een aanwezig bezittelijk kenmerk het woord "de", "het" of "een" dat
  de regeltekst direct vóór het laatste voorkomen van de kenmerknaam schrijft, in kleine letters; schrijft de tekst
  daar geen lidwoord, dan geen. Voor de hoofdzin is die tekst de conclusie van de regel (de bron van het doel: "Een
  RechtOpUitkeringWwBijOntslag heeft de uitkeringsduur verstreken"), voor een voorwaarde de tekst van die voorwaarde.
  Het laatste voorkomen, omdat het onderwerp dezelfde woorden kan bevatten ("Een Recht op uitkering heeft recht op
  uitkering" geeft "heeft recht op uitkering").
- **Waarom het lidwoord uit de regeltekst komt.** Het model draagt het lidwoord van de declaratie niet: een
  `Characteristic` in de IR heeft alleen `name`, `kind`, `timeline` en `designations` (uchar `spec/IR-SPEC.md`
  ch. 2.2; `IrLoader` r. 962), en `DefinitionResolver.kenmerkNaam` (r. 570) neemt `lowerNaam` zonder het
  `bepaaldLidwoord` van de grammatica. uchar vormt zelf geen bevestigende bezittelijke zin met een gekozen
  lidwoord (alleen de javadoc van `Objecttype.kenmerkSoorten` noemt "heeft het <k>"): `ScenarioResolver.werkwoordPast`
  zegt "schrijf 'heeft <kenmerk>'" (r. 714), `RuleChecker.ontkenningsspelling` "heeft geen X", en de scenariolezer
  accepteert de, het, een of geen lidwoord (`zonderLidwoord`). De regelteksten
  verschillen: herleving-ww schrijft "heeft de …" in dertien conclusies en "Een Samenloop heeft het wia-recht" in
  één; TOKA declareert `het recht op duurzaamheidskorting kenmerk (bezittelijk);` (`examples/toka/toka-01-…`, r. 6)
  en schrijft "Een passagier heeft recht op duurzaamheidskorting" (`toka-40-…`, r. 3); uchars eigen tabel in
  `frontends/regelspraak/grammar/DIVERGENCES.md` (r. 148) declareert `het recht op de penning` en schrijft "Een
  Jutter heeft recht op de penning". Een vast lidwoord is in een van die gevallen fout, en ook het lidwoord van de
  declaratie zou bij TOKA "heeft het recht op duurzaamheidskorting" geven waar de regel geen lidwoord schrijft. Het
  lidwoord van de regeltekst geeft in alle drie de vorm van de regel. In een vocabulaire blijft het lidwoord dat van de
  regeltekst, ook voor een benaming; het model kent per benaming geen lidwoord.
- **Zonder soort.** Kent het type geen kenmerk met die naam in een voorwaarde, dan citeert de uitleg de voorwaarde
  ("aan de voorwaarde \"…\" is voldaan"), zoals bij elke vorm die de Verbalisator niet structureert. Bij een
  kenmerktoekenning is het doel altijd een kenmerk van het type; ontbreekt het, dan gooit `verbaliseerToekenning` een
  `IllegalStateException` met de regel en de naam.
- **Bijvangst, een voorwaarde over een rol.** De uitleg draait op het model uit het IR-document (`UcharBouw`), en daar
  staat de rolbinding van een kenmerkcheck over een rol naast de conditie, niet in `Conditie.Kenmerk.navigatie`
  (`IrLoader`, case `CharacteristicCheck`). Vóór deze kaart zei de uitleg dan "het onderwerp wia-recht is", met het
  verkeerde onderwerp. Nu is het kenmerk geen kenmerk van het onderwerp, heeft het geen soort, en staat de voorwaarde
  er als citaat: "aan de voorwaarde \"de samenloop van het Recht een wia-recht heeft\" is voldaan".
- **Tekst.** `README.md` (Vocabularies) en de klassedoc van de Verbalisator noemen de vorm en de herkomst van het
  lidwoord.

### Getoetst op echte invoer

Het s01-document van de werkbank (checkout op `66a4108`, regelspraak-rules `e7d825f`; de v2-bronnen plus het
scenariodocument van de replay), via `/api/analyse` zoals de Uitleg-tab het vraagt: vóór op de draaiende werkbank
(toolchain `6b14d1b`, poort 8089), na op een tweede werkbank op deze branch (`62cbd01`, poort 18489). In s01-v01 en
s01-v02 veranderen 20 van de 20 zinnen, alleen tot de eerste komma; de rest van elke zin is gelijk. s01-v01, canoniek:

| vóór | na |
|---|---|
| R1 is niet uitkeringsduur verstreken | R1 heeft geen uitkeringsduur verstreken |
| R1 is niet indicatie verschuiving einddatum uitkeringspercentage 75 | R1 heeft geen indicatie verschuiving einddatum uitkeringspercentage 75 |
| R1 is niet recht op grond-van-de-wia | R1 is geen recht op grond-van-de-wia |
| R1 is niet herlevingstermijn buiten toepassing | R1 heeft geen herlevingstermijn buiten toepassing |
| R1 is niet wekeneis | R1 heeft geen wekeneis |
| R1 is niet afwijkende opgave beëindigingsdatum | R1 heeft geen afwijkende opgave beëindigingsdatum |
| R1 is niet afwijkende opgave maxdatum | R1 heeft geen afwijkende opgave maxdatum |
| U1 is niet afwijkende opgave uitkeringsduur | U1 heeft geen afwijkende opgave uitkeringsduur |
| M1 is niet afwijkende opgave bedrag maandloon | M1 heeft geen afwijkende opgave bedrag maandloon |
| I4 is herlevingsgrens overschreden | I4 heeft de herlevingsgrens overschreden |
| I5 is herlevingsgrens overschreden | I5 heeft de herlevingsgrens overschreden |
| I6 is herlevingsgrens overschreden | I6 heeft de herlevingsgrens overschreden |
| I7 is niet herlevingsgrens overschreden | I7 heeft geen herlevingsgrens overschreden |
| I8 is niet herlevingsgrens overschreden | I8 heeft geen herlevingsgrens overschreden |
| G1 is kalendermaandsystematiek | G1 heeft de kalendermaandsystematiek |
| G1 is niet zesmaandentermijn | G1 heeft geen zesmaandentermijn |
| G1 is niet vrijstelling op grond-van-leeftijd | G1 heeft geen vrijstelling op grond-van-leeftijd |
| G1 is niet afwijkende opgave datum aanvang eindigingsgrond recht op uitkering | G1 heeft geen afwijkende opgave datum aanvang eindigingsgrond recht op uitkering |
| G1 is niet afwijkende opgave einddatum-van-een-eindigingsgrond | G1 heeft geen afwijkende opgave einddatum-van-een-eindigingsgrond |
| P1 is niet afwijkende opgave herlevingsdatum | P1 heeft geen afwijkende opgave herlevingsdatum |

In `cgm` dezelfde twintig, met "R1 heeft geen Indicatie voldaan aan wekeneis" voor de wekeneis. In `cgm` wijken nog
steeds 2 van de 20 zinnen af van canoniek, dezelfde twee (de wekeneis en de codes van G1). Op s01 staat geen
voorwaarde over een kenmerk in eigen woorden; die staan er als citaat, en dat verandert niet.

### Testwijzigingen

- Nieuw: `toolchain/…/engine/UitlegVolgtDeKenmerksoortTest` (3 tests, via `ScenarioRunner.runMetUitleg`), met een
  eigen document: per soort een afgeleid kenmerk (`de uitkeringsduur verstreken`, `het recht op grond-van-de-wia`,
  `is herleefbaar`) en een invoerkenmerk in de voorwaarden (`het verzoek`, `de lopende uitkering`, `is geschorst`),
  aanwezig en ontkend, met benamingen in `cgm`, en `het recht op uitkering` met de conclusie "Een Recht op uitkering
  heeft recht op uitkering". Gepind: alle twaalf zinnen canoniek en in `cgm`, en de voorwaarde over een rol als citaat.
- Rood zonder de wijziging: 3 van de 3.
- Geen bestaand testbestand veranderd. Geen bestaande test legt de oude vorm vast: de uitlegzinnen die ze pinnen gaan
  over bijvoeglijke kenmerken (`is belast`, `is minderjarig`, `is waardevol`, `is beëindigd door inkomsten`).

### Poorten

`make gates` op `62cbd01`: groen, 7 stappen in 8 min 50 s.
- pin: uchar op `99f614b8`;
- reactor (5 min 30 s): 1578 tests in uchars backend, 134 in toolchain (was 131, plus de 3 nieuwe; vloer 120), 269 in
  service en 47 in service-quarkus;
- tweelingen: 3 tweelingen met 18 cases, de vocabulairetweeling met 2 cases in 34 verzoeken, 4 getuigenissen;
- replay: 225 goldens met 599 cases (pin 225);
- manifest: 32 regels, 29 ok, 3 uitgesloten, 0 mismatch;
- image `beslisservice:dev`;
- kind-smoke: H2, H3 en H4 PASS.

### Open

- De golden `jutterswet-afrekening` van uchar declareert `is zwaar kenmerk;` zonder `(bijvoeglijk)`, en uchar leest
  een kenmerk zonder soortaanduiding als zelfstandig (`DefinitionResolver.kenmerkSoortVan`). De regel schrijft "Een
  Vondst is zwaar"; de uitleg zegt nu "V2 is een zwaar" en "V1 is geen zwaar". TOKA schrijft bij dezelfde vorm
  (`is belaste reis kenmerk;`) "Een Vlucht is een belaste reis", dus daar klopt de lezing. De declaratie van de
  golden of de lezing van uchar is een vraag voor uchar, niet voor de toolchain.
- Een kenmerkcheck over een rol in het IR-model: de Verbalisator ziet de rolbinding niet. De bijzin staat nu als
  citaat, maar `evalueer` leest zo'n kenmerk voor de falende conjunct nog op de bean van het onderwerp.

### Commits

`b634de7` kaart · `7932611` claim · `62cbd01` de Verbalisator, de nieuwe testklasse en de README · deze Result en de
verplaatsing naar `done/`.
