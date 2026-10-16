# geen-fixpunt: een run zonder fixpunt is een 422 op de beslisroute en een FAIL per scenario, geen 500

*Vastgelegd op 2026-10-16, uit het open punt "Geen fixpunt" van de kaart `20261015-playground-via-dienst`. Toolchain
`2335f01`, uchar-pin `a3ac41fe`.*

## Aanleiding

Bereikt een run geen fixpunt, dan geeft uchars `DroolsBackend.vuur` een `Vuring` met de zin in `geenFixpunt()`: het
vuurplafond (`max(VLOER, FACTOR × R × N0)`) of het creatiebudget (`TmsTranspiler.CREATIEBUDGET`, RUNTIME-SEMANTICS
ch. 5.5) is bereikt. `Uitvoerder.java:115` werpt dan een `IllegalStateException` met die zin. Gevolg:

- de beslisroute (`POST /<pad>`) geeft een 500 `interne fout`, in JDK en Quarkus; de zin staat alleen in de log, en
  aan het type is het verschil met een interne fout niet te zien;
- `POST /playground/analyse?via=dienst` faalt als geheel (500): alle scenario's van het document verliezen hun
  uitkomst.

uchars rapport (`CaseRunner.rapport`, via `runCase`) maakt van zo'n case een FAIL met diagnose `<case>: <zin>`, zonder
verwachtingen, met de trace en de dekking van wat vuurde, en speelt de andere cases door.

## Wat er gebeurt

1. Een eigen fouttype in de service, `GeenFixpunt`, met uchars zin als boodschap; `Uitvoerder` werpt het waar nu de
   `IllegalStateException` staat. De toolchain beoordeelt de zin niet: plafond en budget gaan dezelfde weg.
2. De beslisroute geeft voor `GeenFixpunt` een 422, naar het voorbeeld van een evaluatiefout:
   `{"status": "GEEN_FIXPUNT", "fout": <uchars zin>}`. Met detailvlaggen krijgt de 422 `trace` en `dekking` van wat
   vuurde, en `fixpuntFout` met de zin voor `fixpunt` (er is geen fixpunt om te tonen). JDK (`DienstHttp`) en Quarkus
   (`BeslisResource`) geven hetzelfde. Het OpenAPI-schema van de 422 beschrijft beide lichamen.
3. `?via=dienst` vangt `GeenFixpunt` per scenario: FAIL met `diagnose` `<case>: <zin>`, zoals uchars rapport, zonder
   verwachtingen, met `trace` en `dekking` uit de 422, `uitlegFout` en `fixpuntFout`. De andere scenario's lopen door.

## Acceptatie

- (a) Het document `uchar/corpus/rood/jutterswet-budgetgrens-over/bron.rgs` (een keten van creaties die het
  creatiebudget overschrijdt) met twee scenario's: Wachter W1 met rang 1 (budget overschreden) en met rang 50
  (fixpunt). `?via=dienst` geeft FAIL voor het eerste en PASS voor het tweede; het FAIL-resultaat heeft dezelfde
  `status`, `diagnose`, `verwachtingen`, `trace` en `dekking` als de gewone route, en de diagnose begint met de naam
  van het casebestand en het fragment dat uchar in `case1.json` (`expectedFault.drools`) pint.
- (b) De beslisroute geeft voor W1 met rang 1 een 422 met `status` `GEEN_FIXPUNT` en uchars zin als `fout`; JDK en
  Quarkus geven dezelfde status en hetzelfde lichaam, zonder en met `trace`, `dekking` en `fixpunt`.
- (c) De kern werpt `GeenFixpunt` (geen `IllegalStateException`); een 500 blijft voor interne fouten.
- (d) De OpenAPI-422 noemt het lichaam van `GeenFixpunt` naast dat van `Evaluatiefout`.
- (e) Eerst rood: de nieuwe tests falen op `2335f01` (500, of een `IllegalStateException` uit de analyse).
- (f) Bestaande tests ongewijzigd. Raakt het nieuwe 422-schema een golden of test, dan gaat die wijziging in een eigen
  commit met "testpatch" in de titel, alleen mechanisch, beschreven in het Result.
- (g) `docs/ENGINE.md` beschrijft de 422 en het FAIL per scenario. `make gates` groen op de eindcommit.

## Omvang

Klein. May touch: `service/` (`Uitvoerder`, nieuw `GeenFixpunt`, `BeslisserKern`, `AntwoordBouwer`, `OpenApiEmitter`,
`http/DienstHttp`, `http/Playground`), `service-quarkus/` (`BeslisResource`), nieuwe testklassen, de OpenAPI-goldens
(testpatch), `docs/ENGINE.md`, kaartverplaatsing. Niet `uchar/`.
