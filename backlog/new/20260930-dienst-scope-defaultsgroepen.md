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
