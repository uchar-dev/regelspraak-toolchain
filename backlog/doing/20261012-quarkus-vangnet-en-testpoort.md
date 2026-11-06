# quarkus-vangnet-en-testpoort: de Quarkus-adapter vangt een onverwachte fout zoals de JDK-dienst, en zijn tests binden geen vaste poort

*Vastgelegd door de hoofdsessie bij de kaart `20261010-sessie-via-de-ingang-van-uchar` (2026-10-12), uit de bijvangst
van stap 1 en de nulmeting van die kaart.*

## Aanleiding

1. **Geen vangnet.** `DecideResource` (`service-quarkus`) vangt `UnknownPath`, `NonExecutable`, `ServiceError` en sinds
   die kaart `EvaluationFault`. Een andere exceptie, zoals een sessie die het plafond haalt (`Firing.noFixpoint()`), gaat
   naar de standaardafhandeling van Quarkus. De JDK-dienst geeft dan `500 {"error":"interne fout"}` en logt de
   stacktrace (`ServiceHttp`). `ErrorParityTest` toetst de 500 niet. Dit bestond al vóór die kaart.
2. **Vaste testpoort.** De Quarkus-tests binden standaard poort 8081. Op 2026-10-12 hield een preview-server van een
   werkbanksessie die poort bezet, en faalde `mvn -B -o verify` op `HealthTest`:
   `QuarkusBindException: Port(s) already bound: 8081`. De gates draaiden daarna met `-Dquarkus.http.test-port=0`.

## Wat er gebeurt

- `DecideResource` geeft voor een onverwachte exceptie hetzelfde antwoord als `ServiceHttp`: 500 met "interne fout",
  zonder stacktrace naar de afnemer, en met de stacktrace in de log. `ErrorParityTest` toetst het.
- `service-quarkus` zet `quarkus.http.test-port=0` in een testconfiguratie, zodat de tests een vrije poort nemen.

## Acceptatie

- (a) Rood eerst: een getuige voor de 500 in beide adapters.
- (b) `mvn -B -o verify` groen terwijl poort 8081 bezet is.
- (c) `make gates` groen.

## Omvang

Klein. May touch: `service-quarkus/` (main en test), `service/` alleen als de pariteitstest dat vraagt.

## Result

*Gebouwd op 2026-11-06 in de branch `card/scope-check-and-cleanup`. Logs in `uchar-dev/werk/toolchain-cleanup/logs/`.*

`DecideResource` vangt na de bekende soorten elke andere `RuntimeException`: 500 met `{"error":"interne fout"}`,
zonder bericht of klassenaam, en de stacktrace in de log van Quarkus (`org.jboss.logging.Logger`, al aanwezig via
Quarkus); `GET /info` evenzo, zoals `ServiceHttp`. De naad voor de test is de pakket-private methode
`DecideResource.decide`, die `core.process` aanroept. `application.properties` zet `quarkus.http.test-port=0`.

- (a) Rood eerst: `ErrorParityTest.an_unexpected_fault_500_without_its_message` (een `DecideResource` waarvan `decide`
  een `IllegalStateException("geheim")` gooit, naast `ServiceHttp` met dezelfde fout via de bestaande naad
  `bindWith`, door de nieuwe testhulp `ExplodingService` in de test-jar van `service`). Op de oude code vloog de
  exceptie uit de resource (`logs/c3-red.log`); erna geven beide adapters 500 met hetzelfde lichaam
  `{"error":"interne fout"}` (`logs/c3-green-parity.log`, met de logregel "interne fout bij POST /bepaal-leeftijd").
  De JDK-adapter had zijn getuige al (`ServiceHttpTest._500_never_leaks_a_stacktrace_or_secret`).
- (b) Met `python3 -m http.server 8081 --bind 127.0.0.1` als bezetter: zonder de instelling faalt `HealthTest` met
  `QuarkusBindException: Port(s) already bound: 8081` (`logs/c3-red-port.log`); met de instelling is de hele reactor
  groen terwijl de poort bezet is (`logs/c3-reactor-port-held.log`: toolchain 197, service 501, service-quarkus 55,
  geen skip). Een bezetter op `*:8081` (IPv6) hinderde de oude instelling niet; de proef bezet daarom 127.0.0.1.
- (c) `make gates`: zie het slot van deze Result.

Docs: `docs/ENGINE.md` noemt de 500 van beide adapters, met de stacktrace in de log.
