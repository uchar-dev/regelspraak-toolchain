# dienst-scope-defaultsgroepen: de scopetoets bij het starten slaat de defaultsgroepen over, zodat hun uitkomst stil ontbreekt of afwijkt

*Vondst van een regelsetbouwer, 2026-09-30, opnieuw gemeten door de
kaartschrijver op de distributie `f24283cf` (uchar `b0aab696`) met
`bin/regelspraak serve`; reproductie op Jutterswet-materiaal in
`/tmp/v2/probe/kaarten-g1/dienst-scope/` (d0–d6 als .rgs, hun descriptors
`*.dienst.json`, verzoeken-*.json, dienst-meet.py, en per descriptor de
uitvoer van de dienst in `*.out` en `*.err`; alleen lezen). Stil: de dienst
start zonder melding en antwoordt 200, bij een `Uitzondering op` met een
ander getal dan de volle scope geeft. Op verzoek van de eigenaar geschreven
(2026-09-30).*

## Aanleiding

Bij het starten toetst `BeslisserKern.bouw` per entrypoint of de scope
volledig is (`scopeVolledigheid`): voor elke gewone regel in de scope neemt
zij de elementen die de regel leest, en weigert de start
(`SCOPE_ONVOLLEDIG`) als zo'n element alleen buiten de scope geschreven
wordt en niet als `invoerElementen` gedeclareerd is. De producentkant kent
de defaultsgroepen (`ProducentConsument.producentIndex(model,
model.prioritizedDefaults)`), de consumentkant niet: de lus loopt over
`model.resolvedRegels`. Een regel met een `Uitzondering op` of
`Afwijking van` boven zich staat na de lowering niet meer in
`resolvedRegels` maar alleen in `prioritizedDefaults` (uchar, DIVERGENCES
§4), en de opgave-markering maakt een gegenereerde groep (§7). Leest zo'n
groep een attribuut of kenmerk dat alleen een gewone regel buiten de scope
schrijft, dan start de dienst zonder melding en beslist de groep op een
lege lezing. Bij een `Afwijking van` waarvan de basis het attribuut leest,
is de uitkomst `null` waar de volle scope een datum geeft; bij een
`Uitzondering op` waarvan de voorwaarde het leest, beslist de basis, en de
dienst antwoordt 10 waar de volle scope 5 geeft. Dezelfde keten met een
gewone regel als lezer weigert de start wel (R7), en een gewone regel die
de uitkomst van een groep buiten de scope leest ook: de index van de
producenten telt de groep mee.

## Minimale reproductie

Distributie `f24283cf` (uchar `b0aab696`): `bin/regelspraak serve
<descriptor> <bron.rgs> --host 127.0.0.1 --poort 0`, dan `GET /info` en
`POST /<pad>`.

    Eenheidsysteem Tijd
        de dag dg
        de week wk = 7 dg
        de maand mnd
        het jaar jr = 12 mnd

    Objecttype de Jutter (mv: Jutters) (bezield)
        de vinddag Datum in dagen;
        de bergdag Datum in dagen;
        de premie Numeriek (geheel getal);

    Regel bergdag
        geldig altijd
            De bergdag van een Jutter moet berekend worden als zijn vinddag plus 3 dg.

    Regel premie
        geldig altijd
            De premie van een Jutter moet gesteld worden op 10.

    Uitzondering op premie: late berging
        geldig altijd
            De premie van een Jutter moet gesteld worden op 5
            indien zijn bergdag later is dan 01-06-2026.

    Descriptor d2-zonder.dienst.json (d2-met.dienst.json: "regelnamen": ["bergdag", "premie"]):

    {"dienst": {"naam": "Jutterswet scopeproef", "versie": "probe"},
     "entrypoints": [{"naam": "premie", "pad": "premie", "scope": {"regelnamen": ["premie"]},
                      "invoer": ["Jutter"], "uitvoer": ["Jutter.premie"]}],
     "parameters": {}}

    Verzoek: {"instanties": [{"type": "Jutter", "id": "J1", "attributen": {"vinddag": "2026-05-30"}}]}
    (bergdag 02-06-2026, dus premie 5).

    R1  d2-uitzondering.rgs met d2-zonder.dienst.json, zoals hierboven.
        nu: de dienst start ("dienst 'Jutterswet scopeproef' luistert op poort …"); /info: dekking
        [{"soort": "defaults", "naam": "defaults A:Jutter.premie", "verdict": "LOWERED"}], markers [];
        POST /premie 200: {"instanties":[{"id":"J1","type":"Jutter","attributen":{"premie":10}}],
        "inconsistenties":[],"inconsistentieDetails":[]}.
    R2  hetzelfde met d2-met.dienst.json.
        nu: 200 met "premie":5.
    R3  d1-afwijking.rgs: `Regel meldtermijn` (zijn bergdag plus 6 mnd) met `Afwijking van
        meldtermijn: gemelde meldtermijn gaat voor`; scope ["meldtermijn"]; J1 met vinddag 2026-03-10.
        nu: start; 200 met "meldtermijn":null; met bergdag in de scope "meldtermijn":"2026-09-13";
        met een gemelde meldtermijn 2026-12-01 in beide gevallen 2026-12-01.
    R4  d3-opgave.rgs: de meldtermijn `(mag opgegeven worden)`, scope ["opgave boven berekening
        meldtermijn"] (de gegenereerde groep, zonder de verplaatste regel `meldtermijn`).
        nu: start; 200 met "meldtermijn":null (volle scope: "2026-09-13"). Met de verplaatste regel in
        de scope en zonder bergdag weigert de start wel: die regel is een gewone regel.
    R5  d4-kenmerk.rgs: de uitzondering leest het kenmerk `laatgeborgen`, dat een gewone regel buiten
        de scope toekent; J1 met vinddag 2026-06-10.
        nu: start; 200 met "premie":10 (volle scope: 5).
    R6  d5-groep-leest-groep.rgs: de groep van `meldtermijn` leest `bergdag`, die zelf een groep
        schrijft (`Afwijking van bergdag`) die buiten de scope staat.
        nu: start; 200 met "meldtermijn":null.
    R7  controles: d0-gewoon.rgs (de lezer `meldtermijn` is een gewone regel) en
        d6-regel-leest-groep.rgs (een gewone regel leest de uitkomst van de groep van `bergdag`
        buiten de scope).
        nu: de start weigert, exit 1: "dienst start niet: onvolledige regel-scope (buiten-scope
        kenmerk-producent) (1 schending(en)): SCOPE_ONVOLLEDIG [entrypoint:meldtermijn]: regel
        'meldtermijn' in entrypoint 'meldtermijn' leest attribuut 'bergdag' van 'jutter', maar de
        enige producent(en) [bergdag] vallen buiten de scope en het element is niet als
        invoerElement gedeclareerd".

## Acceptatie

- (a) Meet de vormruimte: als lezer een groep uit `Uitzondering op`,
  `Afwijking van` en `Uitzonderingen ongeordend` en de gegenereerde groep
  van de opgave-markering; de lezing in de consequentie van een default, in
  zijn rechtvaardiging, als kenmerkcheck, en één en twee rollen ver; als
  producent buiten de scope een gewone regel, een kenmerktoekenning, een
  beslistabel en een andere groep; het element als `invoerElementen`
  gedeclareerd (de start gaat door); `scopeControleUit` (geen toets, zoals
  nu); een groep waarvan elke lezing in de scope geschreven wordt (de start
  gaat door); en de gewone lezer van R7 als vorm die moet blijven weigeren.
- (b) `scopeVolledigheid` toetst ook de defaultsgroepen die de scope
  draagt, met dezelfde drie voorwaarden en een schending die de groep noemt
  zoals de auteur haar schreef (de basisregel, of de gegenereerde groep bij
  haar naam), met de default die leest en het element. De wandeling over wat
  een groep leest, komt uit dezelfde bron als die voor een regel
  (`ProducentConsument` in uchar, pakket `graaf`, gedeeld met de
  regelgraaf), zodat poort en graaf niet uiteenlopen; heeft uchar die
  wandeling voor een groep niet publiek, dan eerst een kaart in uchar, en
  deze kaart wacht daarop. Getuige in de service (`ScopeVolledigheidTest` of
  een tweeling), rood eerst met R1, R3, R4, R5 en R6, met R2, R7 en de
  `invoerElementen`-opt-out als flanken die groen blijven.
- (c) De poorten onveranderd groen: de tweelingen van `check-tweelingen`
  starten en spelen met hun gepinde verwachtingen (hun scope noemt de regels
  en de defaultsgroep); `check-dienst-replay` onveranderd (de replay zet
  `scopeControleUit`); de vloeren in `tools/check-skips.sh` als
  **voorstel** waar de suite groeit.
- (d) `make gates` groen. `README.md`, en `docs/ENGINE.md` als die de
  scopetoets beschrijft: de toets dekt gewone regels en defaultsgroepen, met
  de opt-outs `invoerElementen` en `scopeControleUit`.

May touch: `service/`, `service-quarkus/` (alleen als de adapter de toets
zelf draagt), `tools/check-skips.sh` (**voorstel**), `README.md`,
`docs/ENGINE.md`, kaartverplaatsing. Niet: `uchar/` (een vraag aan uchar is
een kaart daar), `examples/toka*`, `specification/`. NB: niet nodig zolang
de descriptors van de regelset de schrijvende regels in de scope noemen;
wel vóór een volgende descriptor een groep in de scope zet zonder haar
bronnen. Onafhankelijk van de uchar-kaarten, tenzij (b) eerst een publieke
wandeling in uchar vraagt.

## Result (2026-10-01, bouwer)

Tak `card/dienst-scope-defaultsgroepen` in de worktree van de kaart, afgetakt van distributie-main `b559666` (uchar
`b9d3a5e6`, de pin is niet verzet); niet gerebased, niet gepusht, niet gemerged. Gemeten met `bin/regelspraak serve`
van deze tak, met `REGELSPRAAK_JAR` op twee bewaarde jars: "vóór" is de jar van de claim `7dc967e` (code gelijk aan
main), "na" die van `681fc97`, de commit die de dienstcode raakt. Generator, meetscripts, metingen en tabel staan buiten
de repo onder `/tmp/v2/probe/dsc-bouw/`: `tools/` (`maak.py` schrijft de vormen, `meet.py` start de dienst, vraagt
`/info` en post de verzoeken, `alles.sh` meet alle vormen met één jar, `tabel.py`), per vorm
`vormen/<naam>/{bron.rgs,dienst.json,verzoeken.json,meting-voor.txt,meting-na.txt}`, de volledige tabel `tabel.md`, de
uitvoer van de testruns (`rood.log`, `rood-19.log`, `service-suite-*.log`, `poorten-1.log`, `gates-1.log`) en in `probe/`
een Java-probe die uchars `ProducentConsument.vormen` per groep afdrukt. De reproducties zijn uit
`/tmp/v2/probe/kaarten-g1/dienst-scope/` gekopieerd (alleen gelezen).

### De reproductie op deze kop

De kaart mat op `f24283cf` (uchar `b0aab696`); op `b559666` (uchar `b9d3a5e6`) geeft "vóór" dezelfde uitkomsten.

| | vóór (`7dc967e`) | na (`681fc97`) |
|---|---|---|
| R1 d2, scope `premie` | start; J1 `premie` 10 (volle scope 5) | exit 1: `SCOPE_ONVOLLEDIG [entrypoint:premie]: defaults-groep van regel 'premie' in entrypoint 'premie' leest attribuut 'bergdag' van 'jutter', maar de enige producent(en) [bergdag] vallen buiten de scope en het element is niet als invoerElement gedeclareerd` |
| R2 d2, scope `bergdag`, `premie` | start; J1 5, J2 10 | gelijk |
| R3 d1, scope `meldtermijn` | start; J1 `meldtermijn` null (volle scope 2026-09-13); J2 met melding 2026-12-01 | exit 1: `defaults-groep van regel 'meldtermijn' … leest attribuut 'bergdag' van 'jutter', maar de enige producent(en) [bergdag] …` |
| R4 d3, scope `opgave boven berekening meldtermijn` | start; J1 null (volle scope 2026-09-13); J2 met opgave 2026-12-01 | exit 1: `gegenereerde defaults-groep 'opgave boven berekening meldtermijn' in entrypoint 'meldtermijn' leest attribuut 'berekende meldtermijn' van 'jutter', maar de enige producent(en) [meldtermijn] …` |
| R4, met de verplaatste regel en zonder `bergdag` | exit 1: `regel 'meldtermijn' … leest attribuut 'bergdag' …` | gelijk |
| R5 d4, scope `premie` | start; J1 10 (volle scope 5) | exit 1: `defaults-groep van regel 'premie' … leest kenmerk 'laatgeborgen' van 'jutter', maar de enige producent(en) [laatgeborgen] …` |
| R6 d5, scope `meldtermijn` | start; J1 null (volle scope 2026-09-13) | exit 1: `defaults-groep van regel 'meldtermijn' … leest attribuut 'bergdag' …, maar de enige producent(en) [bergdag] …` |
| R7 d0 en d6, scope `meldtermijn` | exit 1, de zin van de kaart (`regel 'meldtermijn' in entrypoint 'meldtermijn' leest attribuut 'bergdag' van 'jutter', …`) | gelijk, letterlijk |

### Oorzaak

1. `BeslisserKern.scopeVolledigheid` liep alleen over `model.resolvedRegels` en vroeg per regel
   `ProducentConsument.verbruikt(r, navigatie)`. Een regel met een `Uitzondering op`, `Afwijking van` of
   `Uitzonderingen ongeordend` boven zich staat na de lowering niet meer in `resolvedRegels` maar in een defaultsgroep
   (DIVERGENCES §4), en de opgave-markering (§7) en de eigen uitzondering van een subtype (`<basis> voor <subtype>`,
   uchar `b9d3a5e6`) maken een gegenereerde groep. De producentkant telde die groepen wel mee
   (`producentIndex(model, model.prioritizedDefaults)`), de consumentkant niet: wat een groep las, toetste niemand.
2. Vondst in dezelfde methode: de toets draaide vóór de build, op de regels van het hele model met de scope als
   filter. uchars wandeling gooit een `IllegalStateException` op een genavigeerde kenmerkcheck die de emitter weigert
   (`bronNavigatie`: "reaches no unique object type"), dus een gewone regel met zo'n check in de scope liet de start
   eindigen in een stapelspoor (x02 in (a)), waar een groep met dezelfde check een marker met uchars zin werd.

### Fix en de gekozen weg

`BeslisserKern` (+42, −24 regels) en de javadoc van `scopeControleUit` in `DienstDescriptor`; `uchar/` is niet
aangeraakt.

- `bouw` maakt per entrypoint het gescoopte model één keer (`CaseEmitter.metScope`, hetzelfde model dat de build
  krijgt) en roept de toets aan na een geslaagde build. Een marker beantwoordt geen verzoek, dus wat zijn vormen lezen
  beslist niets; en de navigatie die uchars wandeling niet kan volgen, heeft de emitter dan al benoemd geweigerd.
- `scopeVolledigheid` leest de vormen van het gescoopte model met uchars
  `ProducentConsument.vormen(gescoopt, gescoopt.prioritizedDefaults, bronNavigatie(gescoopt))`, de wandeling die
  `RegelGraaf.bouw` tekent: van een regel de voorwaarde en de waarde, van een groep de rechtvaardiging en de consequentie
  van elke default, geneste uitzonderingsgroepen inbegrepen. Voor elke vorm van soort `REGEL` of `DEFAULTSGROEP` gelden
  dezelfde drie voorwaarden als voorheen (er is een schrijver, geen schrijver staat in de scope, het element is geen
  `invoerElementen`). De schrijvers in de scope zijn de namen van de vormen van het gescoopte model, de namen waaronder
  de producentindex ze sleutelt; de dedup per element blijft.
- De schending noemt de lezer: een regel als voorheen (`regel 'X'`, letterlijk dezelfde zin); een groep waarvan de naam
  een regel is die de auteur schreef (`model.regels`: de basisregel van een boom, of het lid van een blok
  `Uitzonderingen ongeordend:` zonder drager) als `defaults-groep van regel 'X'`; een groep die uchar een naam geeft
  (`opgave boven berekening X`, `X voor S`) als `gegenereerde defaults-groep 'X'`. Daarna het entrypoint, het element en
  de schrijvers buiten de scope, zoals bij een regel.

Verworpen:

1. De default die leest noemen door uchars `vormen` per default te voeden met een synthetische groep van één default:
   dat gebruikt de publieke wandeling met een lijst die niet de groepenlijst van het model is, een omweg om een
   ontbrekende methode heen. Gevraagd aan uchar (hieronder).
2. Een eigen wandeling over `Default` en `ExceptionGroup` in de distributie: een tweede wandeling naast die van de graaf,
   precies wat de kaart uitsluit.
3. De toets ook over objectcreaties, verdelingen, consistenties, uniciteiten en feitcreaties (gemeten: een consistentie
   in de scope oordeelt stil op een lege lezing, x01d). Proef op de tak, niet gecommit: `examples/toka/toka.dienst.json`
   start dan niet meer, 7 schendingen in 5 entrypoints, alle zeven bij een verdeling, en zes testklassen van de dienst
   worden rood. De descriptor ligt in de pariteitszone; open punt 2.
4. De toets vóór de build laten en de `IllegalStateException` vangen: dan moet de dienst uchars uitzondering duiden,
   terwijl na de build de marker de weigering al met uchars zin noemt.

### (a) Vormruimte, vóór en na

51 documenten (`maak.py`): de reproducties van de kaart en per vorm een tweeling met de schrijver in de scope ("-met",
de volle uitkomst). Vinddag 30-05-2026 geeft bergdag 02-06-2026 (premie 5), vinddag 01-03-2026 bergdag 04-03-2026
(premie 10); vinddag 10-03-2026 geeft meldtermijn 13-09-2026. Model van de rollen: Vondst, Jutter, Haven, feittypen
JutterHeeftVondst en HavenHeeftJutter. De volledige tabel met de letterlijke uitvoer is `tabel.md`.

| Vorm | lezer, lezing, schrijver buiten de scope | scope | vóór | na |
|---|---|---|---|---|
| v01 | `Uitzonderingen ongeordend op premie:`, rechtvaardiging van een lid, gewone regel | `premie` | start; J1 10 (vol 5), J2 10 | exit 1: `defaults-groep van regel 'premie'`, `attribuut 'bergdag'`, `[bergdag]` |
| v02 | `Uitzonderingen ongeordend:` zonder drager (twee zustergroepen), rechtvaardiging | `premie bij late berging`, `premie bij vroege berging` | start; J1 null (vol 5), J2 null (vol 10) | exit 1: `defaults-groep van regel 'premie bij late berging'`, `[bergdag]` |
| v03 | `Uitzondering op premie` voor Strandjutter (groep `premie voor Strandjutter`) | `premie`, `premie voor Strandjutter` | start; S1 10 (vol 7) | exit 1: `gegenereerde defaults-groep 'premie voor Strandjutter'`, `[bergdag]` |
| v03 alleen subtype | idem | `premie voor Strandjutter` | start; S1 null | exit 1, dezelfde zin |
| v04 | geneste `Uitzondering op late berging`, kenmerkcheck, kenmerktoekenning | `bergdag`, `premie` | start; J1 5 (vol 8), J2 5 | exit 1: `kenmerk 'stormberger'`, `[stormberger]` |
| v05 | `Uitzondering op`, rechtvaardiging, beslistabel | `premie` | start; 10, 10 (vol 5, 10) | exit 1: `attribuut 'bergklasse'`, `[bergklasse 1]` |
| v06 | groep op Vondst, attribuut één rol ver | `premie van een vondst` | start; 10, 10 (vol 5, 10) | exit 1: `defaults-groep van regel 'premie van een vondst'`, `attribuut 'bergdag' van 'jutter'`, `[bergdag]` |
| v07 | attribuut twee rollen ver (`de openingsdag van de haven van de jutter van de Vondst`) | idem | start; 10, 10 (vol 5, 10) | exit 1: `attribuut 'openingsdag' van 'haven'`, `[openingsdag]` |
| v08 | kenmerkcheck één rol ver (`zijn jutter laatgeborgen is`) | idem | start; 10, 10 (vol 5, 10) | exit 1: `kenmerk 'laatgeborgen' van 'jutter'`, `[laatgeborgen]` |
| v09b | één rol ver als gevuld-predicaat | idem | start; 10, 10 (vol 5, 5) | exit 1: `[bergdag]` |
| v09 | kenmerkcheck twee rollen ver (`de haven van de jutter van de Vondst gesloten is`) | idem, ook met `sluiting` | exit 3, MARKER: `rechtvaardiging niet vertaald, een onopgeloste voorwaarde` | gelijk |
| v09c | idem bezittelijk (`de haven van zijn jutter gesloten is`) | idem, ook met `sluiting` | exit 3, MARKER: `emitter: IrEmitter: de kenmerkcheck … navigeert via 'haven', en vanaf 'Vondst' bindt die stap geen unieke rol` | gelijk |
| x02 | die lezing in een gewone regel | `premie van een vondst` | exit 1, `IllegalStateException` uit `ProducentConsument.bronNavigatie` (stapelspoor) | exit 3, MARKER met dezelfde emitterzin als v09c |
| v10 | `Afwijking van`, consequentie van de afwijking (`zijn bergloon`) | `premie` | start; 10, 10 (vol 7, 7) | exit 1: `attribuut 'bergloon'`, `[bergloon]` |
| o1 | R1 met `invoerElementen: ["Jutter.bergdag"]` | `premie` | start; zonder bergdag in het verzoek 10, 10 | gelijk: start (de getuige: met bergdag 02-06-2026 premie 5) |
| o2 | R1 met `scopeControleUit: true` | `premie` | start; 10, 10 | gelijk: geen toets |
| v03 alleen basis | v03 | `premie` | start; S1 10 (vol 7) | gelijk; open punt 3 |
| v03e | de groep van het subtype zonder de groep van haar basis | `bergdag`, `premie voor Strandjutter` | start; S1 7, S2 null (vol 10) | gelijk; open punt 3 |
| x01b, x01d | een consistentie leest `bergdag` (buiten de kaart) | `bergdagcontrole` | start; J1 en J2 inconsistent (x01d vol: consistent) | gelijk; open punt 2 |
| x01, x01c | consistenties die de exectak niet draagt | `bergdagcontrole` | exit 3, MARKER | gelijk |

Telling over de 51 documenten (`cmp` van `meting-voor.txt` en `meting-na.txt`): 16 die een groep dragen die leest wat
alleen buiten de scope geschreven wordt (R1, R3, R4, R5, R6, v01, v02, v03 tweemaal, v04, v05, v06, v07, v08, v09b, v10)
startten vóór alle stil met een ander antwoord dan de volle scope en weigeren na alle met exit 1 en de groep, het element
en de schrijver; 20 flanken (18 tweelingen met de schrijver in de scope, en o1, o2) starten vóór en na met byte-gelijke
metingen; 3 gewone
lezers (R7 tweemaal en R4 zonder bergdag) weigeren vóór en na met dezelfde zin; 7 markers blijven exit 3; x02 gaat van
een stapelspoor naar een marker; 4 starten vóór en na en zijn open punten (v03 alleen basis, v03e, x01b, x01d).

uchars wandeling per groep (`probe/Probe.java`, `ProducentConsument.vormen` op het hele model): de leesverzamelingen
kloppen voor de consequentie (R3, v10), de rechtvaardiging (R1, v01, v02), de kenmerkcheck (R5, v04, v08), één en twee
rollen ver (v06, v07); een kenmerkcheck twee rollen ver schrijft de frontend niet als vorm die uchar draagt (v09
`Onopgelost`, v09c door de emitter geweigerd).

### (b) Getuige

`ScopeDefaultsgroepTest` (dienst, nieuwe klasse naast `ScopeVolledigheidTest`, 19 tests): `r1_…`, `r3_…`, `r4_…`, `r5_…`
en `r6_…` pinnen de hele zin van de schending; de flanken `r2_met_de_schrijver_in_de_scope_start_de_dienst_en_beslist_de_uitzondering`
(J1 5, J2 10), `r7_een_gewone_regel_als_lezer_weigert_als_voorheen` (d0 en d6, de zin letterlijk),
`het_invoerElement_laat_de_aanroeper_de_lezing_van_de_groep_leveren` (met bergdag 02-06-2026 premie 5, met 31-05-2026
premie 10) en `scopeControleUit_laat_de_groep_ongetoetst_starten`; de vormruimte als
`een_lid_van_uitzonderingen_ongeordend_op_een_basisregel`, `uitzonderingen_ongeordend_zonder_drager_noemt_de_groep_van_het_lid`,
`de_eigen_uitzondering_van_een_subtype_is_een_gegenereerde_groep` (met de schrijver in de scope S1 7),
`een_geneste_uitzondering_leest_een_kenmerk_van_buiten_de_scope`, `de_producent_buiten_de_scope_is_een_beslistabel`,
`de_consequentie_van_een_afwijking_leest_van_buiten_de_scope`, `een_lezing_een_rol_ver`, `een_lezing_twee_rollen_ver`,
`een_kenmerkcheck_een_rol_ver` en `een_lezing_die_uchar_niet_draagt_is_een_marker_en_geen_stapelspoor`.

Rood eerst: de klasse van `4b06846` (9 tests) tegen de kern van de claim: `Tests run: 9, Failures: 5`, de vijf
R-rijen met `Expected …DienstFout to be thrown, but nothing was thrown.`, de vier flanken groen. De klasse van `681fc97`
tegen de kern van de claim: `Tests run: 19, Failures: 15` (veertien groepsrijen "nothing was thrown", de markerrij
`expected: <…DienstFout> but was: <java.lang.IllegalStateException>`), de vier flanken groen. Na de fix: 19 groen; de
dienstsuite 229 tests (210 op de claim). Geen bestaande test is gewijzigd; `ScopeVolledigheidTest` (3) en
`ScopeKetenTest` (9) blijven groen.

### (c) Poorten

`make poorten` op `5c3c2c5` (code gelijk aan `1dae08b` op de vloer na): check-pin `PASS`; de reactor
`1020/121/229/43` testgevallen (vloeren 120, 210, 43); check-tweelingen `PASS — 3 tweelingen, 18 cases via HTTP met de
gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4
getuigenissen` (de scopes van de tweelingen noemen de regels en de defaultsgroep, en elke groep leest daar alleen wat in
de scope geschreven wordt of invoer is); check-dienst-replay `PASS`, `208 goldens, 553 cases (pin 208)`, onveranderd
(de replay zet `scopeControleUit`); manifest `32 regels, 29 ok, 3 uitgesloten, 0 mismatch`. Vloervoorstel:
`service|229` in `tools/check-skips.sh` (`1dae08b`, aparte commit); de toolchain telt 121 bij vloer 120 en is door deze
kaart niet gegroeid.

### (d) Gates en docs

`make gates` op `1dae08b`:

```
gates: 1 pin        GROEN      1s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit; de versies zijn gelijk
gates: 2 reactor    GROEN   1m02s  testgevallen per module 1020/121/229/43
gates: 3 tweelingen GROEN      8s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m00s  208 goldens, 553 cases (pin 208)
gates: 5 manifest   GROEN      2s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     34s  beslisservice:dev
gates: 7 kind-smoke GROEN     49s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 3m36s; de uitvoer staat in out/gates/
```

De skip-pin in stap 2: `229 testgevallen (vloer 229)`. Docs (`ac83800`): `docs/ENGINE.md` beschreef de scopetoets
niet en krijgt een alinea na die over de scope (wat de toets leest via `ProducentConsument.vormen`, de drie voorwaarden,
`SCOPE_ONVOLLEDIG` en exit 1, hoe hij een regel, een groep van een regel en een gegenereerde groep noemt, de opt-outs
`invoerElementen` en `scopeControleUit`, en welke vormen hij niet toetst); de `README.md` een zin bij de beslisdienst.
Vondst in dezelfde README (`5c3c2c5`): de replay-telling stond nog op 189 bij uchars 216; de scripts pinnen 208 bij 235,
met dezelfde 27 uitsluitingen.

### Wat uchar moet leveren

De kaart vraagt een schending "met de default die leest". De wandeling over wat een groep leest is in uchar publiek op
het niveau van de groep (`ProducentConsument.vormen(model, defaults, navigatie)` geeft per `DEFAULTSGROEP` één
verzameling `leest`, gemeten correct voor consequentie, rechtvaardiging, kenmerkcheck, één en twee rollen ver), en de
toets is daarmee gebouwd. Per default is zij niet publiek: `Lezer.dflt` is privé en voegt alles samen. Nodig, in
`dev.uchar.regelspraak.graaf.ProducentConsument`:

```java
/** Wat elke default van groep {@code groep} leest, in de volgorde van de groep (hoogste prioriteit eerst) en per
 *  default eerst de default zelf, dan de defaults van zijn uitzonderingsgroepen (pre-order), elk tegen het onderwerp
 *  van de groep, met dezelfde Lezer als vormen(): de vereniging van alle rechtvaardiging- en consequentie-
 *  verzamelingen is precies Vorm.leest() van deze groep. */
public static List<DefaultLezing> lezingen(RegelSpraakModel model, PrioritizedDefaults groep, Navigatie navigatie);

/** pad: de plaats in de boom, [i] voor de i-de default van de keten, [i, g, j] voor de j-de default van de g-de
 *  uitzonderingsgroep van default i, enzovoort; rechtvaardiging: wat d.voorwaarde() leest; consequentie: wat
 *  d.consequentie() leest. */
public record DefaultLezing(List<Integer> pad, Default dflt, Set<ElementSleutel> rechtvaardiging,
                            Set<ElementSleutel> consequentie) {}
```

Te dekken lezingen: een attribuut en een kenmerkcheck in de rechtvaardiging, een attribuut in de consequentie (ook van
een uitzondering die geen basis is), één en twee rollen ver, in een geneste uitzonderingsgroep, in een lid van
`Uitzonderingen ongeordend op` en in de groep-default van `<basis> voor <subtype>`. Met deze methode noemt de dienst de
default bij het deel dat leest en zijn tekst zoals de auteur die schreef (`Bron.snippet()` van de voorwaarde of de
consequentie, bijvoorbeeld "in de rechtvaardiging 'zijn bergdag later is dan 01-06-2026'"). Wil uchar de default bij de
naam van zijn blok laten noemen ("late berging"), dan moet `Default` die naam dragen; dat is een keuze over de IR en
geen voorwaarde voor de methode.

### Afwijkingen van de kaart

1. De schending noemt de groep, het element en de schrijvers, niet de default die leest: zie "Wat uchar moet
   leveren". De kaart zegt bij een ontbrekende wandeling "eerst een kaart in uchar"; volgens de opdracht is uchar niet
   aangeraakt en de toets gebouwd met de wandeling die de graaf wel biedt.
2. De getuige is een nieuwe klasse `ScopeDefaultsgroepTest` naast `ScopeVolledigheidTest` (de kaart: "of een
   tweeling"); geen bestaand testbestand is gewijzigd.
3. De toets draait na de build in plaats van ervoor. Gevolg buiten de kaart: een entrypoint dat een marker is, wordt niet
   meer op zijn scope getoetst, zodat het met `--allow-markers` als marker start (503 op elk verzoek) in plaats van de
   start te weigeren om zijn scope; strikt weigert de start in beide gevallen. Een gewone regel met een navigatie die de
   emitter weigert, geeft nu een marker in plaats van een stapelspoor.
4. De kop van de fout blijft `dienst start niet: onvolledige regel-scope (buiten-scope kenmerk-producent)`, de zin die
   R7 citeert, ook nu hij groepen en attributen dekt.
5. De README-telling van de replay (vondst, eigen commit).

### Open punten

1. De default die leest in de schending: wacht op `ProducentConsument.lezingen` in uchar (hierboven).
2. Naar de eigenaar (besluit van de hoofdsessie). De overige vormen van de scope (objectcreatie, verdeling, consistentie, uniciteit, feitcreatie) toetst de dienst
   niet. Gemeten: x01d, een consistentie in de scope die `bergdag` leest, start stil en meldt J1 en J2 inconsistent waar
   de volle scope geen inconsistentie geeft. De toets uitbreiden is één `case` meer, maar
   `examples/toka/toka.dienst.json` start dan niet (7 schendingen in 5 entrypoints, alle bij een verdeling) en die
   descriptor ligt in de pariteitszone: een keuze van de eigenaar (`invoerElementen` daar, of een uitzondering voor
   verdelingen, of de toets beperkt laten).
3. Wat een scopenaam draagt bij een eigen uitzondering van een subtype. `premie` draagt `premie voor Strandjutter`
   niet (v03 alleen basis: de dienst start en antwoordt S1 10 waar de volle scope 7 geeft), en een scope met
   `premie voor Strandjutter` zonder `premie` start en mist de geërfde basis (v03e: S2 null waar de volle scope 10
   geeft). Geen van beide is een lezing van buiten de scope; het gaat over welke vormen een naam draagt. Afgehandeld
   in deze ronde op besluit van de hoofdsessie: zie "Vervolg open punt 3".
4. Waargenomen, geen actie hier: uchars resolver neemt `indien de haven van zijn jutter gesloten is` (twee rollen, één
   genoemd) zonder diagnose aan; de emitter weigert hem (`check` exit 3), dus het blijft luid.
5. Uit het vervolg: de dienst leest een scope nu ruimer dan uchars scenariolaag. Een scenario met de scope `premie`
   bouwt in uchar het document zonder `premie voor Strandjutter`, een entrypoint met dezelfde scope met. Een vraag
   aan uchar of zijn scenarioresolver (`CaseEmitter.metScope` en wat de scope daar vult) dezelfde stapel meeneemt,
   zodat een scenario en een entrypoint met dezelfde namen weer hetzelfde document bouwen.

### Commits

- `7dc967e` claim: dienst-scope-defaultsgroepen
- `4b06846` Getuige: een defaultsgroep in de scope leest van buiten (rood)
- `681fc97` Dienst: de scopetoets leest ook de defaultsgroepen in de scope
- `ac83800` README en ENGINE: de scopetoets dekt regels en defaultsgroepen
- `5c3c2c5` README: replay-telling 208 bij uchars 235
- `1dae08b` Vloeren: de dienst gegroeid met de getuige van de defaultsgroepen (voorstel)
- `5f64103` Result van de kaart dienst-scope-defaultsgroepen
- vervolg: zie "Vervolg open punt 3"

### Vervolg open punt 3 (besluit van de hoofdsessie, 2026-10-01)

Besluit: de scope-resolutie van de dienst behandelt de gegenereerde groep `<basis> voor <subtype>` als onlosmakelijk
van haar basis, omdat RUNTIME-SEMANTICS ch. 7.10 de stapel per concreet type samenstelt uit de eigen groep van het
subtype en de geërfde basis. Een scope die de basisregel noemt, draagt elke groep `<basis> voor <subtype>` die uchar
daaruit maakte; een scope die zo'n groep noemt, draagt haar basis en de andere subtypegroepen van die basis; wie de
gegenereerde naam toch noemt, maakt geen fout. Open punt 1 blijft wachten op uchar, open punt 2 gaat naar de eigenaar.

**Gebouwd** (`82b9089`, `VormIndex` +82, −5). `VormIndex.scope` voegt aan elke defaultsgroep die de scope draagt de
groepen van haar stapel toe; `BeslisserKern` krijgt die vormen via `vormen.scope(ep.scope())`, zowel voor de build
als voor de scopetoets (en bij een `uchar-ir-v1`-document voor de vraag of de scope elke vorm draagt). De stapel komt uit
de IR, niet uit de naam: een groep op een subtype zonder eigen basis wordt verbonden met elke groep van het
dichtstbijzijnde supertype dat hetzelfde doel schrijft, en een groep daar zonder basis reikt op haar beurt verder
omhoog. "Eigen basis" leest zoals uchars samenstelling (`basisVan` in de exectak) hem leest: de laagste gewone
default beslist zelf iets, en een groep-default (`Default.heeftEigenInhoud()` onwaar) is geen basis. Een groep met
eigen basis reikt niet omhoog, want zij is de basis van haar stapel (een eigen basisregel op het subtype blijft dus een
eigen vorm die de auteur noemt); een ongeordende groep ook niet, want uchars samenstelling vouwt die niet over een
geërfde stapel. Het doel is de element-identiteit van `ProducentConsument.elementVan`, zodat een lid op het supertype en
op een subtype één doel zijn, zoals de exectak (`effectieveStapels`, op soort en doel langs de supertypeketen) ze
samenvoegt.

Waarom niet de naam: de bron is `RuleChecker.subtypeGroepNaam` (privé, `wortel + " voor " + sub.naam`). uchar biedt
geen sleutel die de groep aan haar basis koppelt, behalve die naam en de prozatekst van `bron` ("eigen uitzonderingen
van Strandjutter; de basis 'premie' is geërfd van Jutter"). De naam parsen gaat mis bij een basisregel waarvan de naam
zelf " voor " bevat, en een formule die de naam nabouwt, vindt stil niets meer als uchar haar verandert; de structuur
is wat ch. 7.10 samenstelt en wat bij de build gebruikt wordt.

**De scopetoets erbij.** De toets loopt over het gescoopte model en leest dus ook wat de meegenomen groepen lezen: een
scope `premie` toetst nu wat de eigen uitzondering van de Strandjutter leest, en een scope met alleen de subtypegroep
toetst wat de basis leest.

**Vormruimte, vóór en na.** Vóór is de jar van `681fc97` (de code van `1dae08b`), na die van `82b9089`; zeven nieuwe
documenten (`tools/vervolg.py`, `vormen/w*`). Het model `erflijn.rgs`: basis `premie` op Jutter (10), eigen
uitzonderingen van Strandjutter (7), Barnsteenjutter, een subtype van Strandjutter (9), en Wrakjutter (6), elk
`indien zijn vinddag later is dan 28-05-2026`; per type één instantie met vinddag 30-05-2026 (J1, S1, B1, W1) en één met
01-03-2026 (J2, S2, B2, W2).

| Vorm | scope | vóór | na |
|---|---|---|---|
| w1 | `premie` | J1 10, S1 10, B1 10, W1 10; vroeg alle 10 | J1 10, S1 7, B1 9, W1 6; vroeg alle 10 |
| w2 | `premie voor Strandjutter` | J1 null, S1 7, B1 7, W1 null; vroeg alle null | gelijk aan w1 na |
| w3 | `premie voor Barnsteenjutter` | B1 9, de rest null | gelijk aan w1 na |
| w4 | alle vier de groepen | J1 10, S1 7, B1 9, W1 6; vroeg alle 10 | gelijk |
| w5 (v03: de uitzondering van de Strandjutter leest `bergdag`) | `premie` | start; S1 10 | exit 1: `gegenereerde defaults-groep 'premie voor Strandjutter' in entrypoint 'premie' leest attribuut 'bergdag' van 'jutter', maar de enige producent(en) [bergdag] …` |
| w6 (de basis leest `bergdag`, een `Afwijking van` voor de Strandjutter leest `vinddag`) | `premie voor Strandjutter` | start; J1 null, S1 7 | exit 1: `defaults-groep van regel 'premie' in entrypoint 'premie' leest attribuut 'bergdag' van 'jutter', maar de enige producent(en) [bergdag] …` |
| w6 met `bergdag` | `bergdag`, `premie voor Strandjutter` | start; J1 null, S1 7 | start; J1 10, S1 7 |
| v03 alleen basis | `premie` | start; S1 10 (vol 7) | exit 1, dezelfde zin als w5 |
| v03e | `bergdag`, `premie voor Strandjutter` | start; S1 7, S2 null (vol 10) | start; S1 7, S2 10 |

De andere 49 documenten van (a) geven met de jar van `82b9089` byte-gelijke metingen aan die van `681fc97` (`cmp`).

**Getuige.** Vijf rijen in `ScopeDefaultsgroepTest` (nu 24 tests): `de_basisregel_draagt_de_groepen_van_haar_subtypen`
(scope `premie`), `een_groep_van_een_subtype_draagt_haar_basis_en_de_andere_groepen_van_die_basis` (scope
`premie voor Strandjutter`, en `premie voor Barnsteenjutter`; de gewone Jutter krijgt 10), beide met de uitkomst van het
hele model; de flank `wie_de_gegenereerde_groepen_toch_noemt_krijgt_hetzelfde`; en voor de scopetoets
`de_basisregel_draagt_ook_wat_de_groep_van_het_subtype_leest` en `de_groep_van_het_subtype_draagt_ook_wat_haar_basis_leest`.
Rood eerst: `56c21b5` tegen de kern van `1dae08b`, `Tests run: 24, Failures: 4`:

```
de_basisregel_draagt_de_groepen_van_haar_subtypen expected: <{B2=10, S2=10, B1=9, S1=7, W2=10, W1=6, J2=10, J1=10}> but was: <{J1=10, J2=10, S1=10, S2=10, B1=10, B2=10, W1=10, W2=10}>
een_groep_van_een_subtype_draagt_haar_basis_en_de_andere_groepen_van_die_basis expected: <{…}> but was: <{J1=null, J2=null, S1=7, S2=null, B1=7, B2=null, W1=null, W2=null}>
de_basisregel_draagt_ook_wat_de_groep_van_het_subtype_leest … Expected …DienstFout to be thrown, but nothing was thrown.
de_groep_van_het_subtype_draagt_ook_wat_haar_basis_leest … Expected …DienstFout to be thrown, but nothing was thrown.
```

De flank en de 19 eerdere rijen groen. Na `82b9089`: 24 van 24 groen; `VormIndexTest` (4), `ScopeKetenTest` (9) en
`ScopeVolledigheidTest` (3) groen; de dienstsuite 234 tests.

**Docs** (`794359a`): één zin in de `README.md` bij de beslisdienst en één in `docs/ENGINE.md` bij de scope (die zin
zegt ook dat uchars scenariolaag zich aan de namen houdt). **Vloervoorstel** (`a91fe7d`): `service|234`, in plaats van
het voorstel 229.

**Poorten en gates** op `a91fe7d`: `make poorten` groen (check-pin `PASS`; reactor `1020/121/234/43`, skip-pin
`234 testgevallen (vloer 234)`; check-tweelingen `3 tweelingen, 18 cases …, 2 cases in 34 verzoeken; getuige: 4
getuigenissen`; replay `208 goldens, 553 cases (pin 208)`; manifest `32 regels, 29 ok, 3 uitgesloten, 0 mismatch`).
`make gates`:

```
gates: 1 pin        GROEN      1s  PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit; de versies zijn gelijk
gates: 2 reactor    GROEN   1m02s  testgevallen per module 1020/121/234/43
gates: 3 tweelingen GROEN      8s  3 tweelingen, 18 cases via HTTP met de gepinde verwachtingen; vocabulairetweeling jutterswet-vocabulaires: 2 cases in 34 verzoeken; getuige: 4 getuigenissen
gates: 4 replay     GROEN   1m01s  208 goldens, 553 cases (pin 208)
gates: 5 manifest   GROEN      1s  32 regels, 29 ok, 3 uitgesloten, 0 mismatch
gates: 6 image      GROEN     32s  beslisservice:dev
gates: 7 kind-smoke GROEN     49s  H2 PASS, H3 PASS, H4 PASS
gates: GROEN — 7 stappen in 3m35s; de uitvoer staat in out/gates/
```

**Afwijking.** De dienst leest een scope nu ruimer dan uchars scenariolaag (open punt 5); `VormIndex` zegt dat in
zijn javadoc.

**Commits van het vervolg.**

- `56c21b5` Getuige: een subtypegroep hoort bij haar basis (rood)
- `82b9089` Dienst: een scope draagt de subtypegroepen met hun basis mee
- `794359a` README en ENGINE: een subtypegroep gaat met haar basis mee
- `a91fe7d` Vloeren: de dienst 234 met de subtypegroepen
- de commit van dit vervolg: Result van de kaart dienst-scope-defaultsgroepen (2)
