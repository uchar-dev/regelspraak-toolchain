# quarkus-vangnet-en-testpoort: de Quarkus-adapter vangt een onverwachte fout zoals de JDK-dienst, en zijn tests binden geen vaste poort

*Vastgelegd door de hoofdsessie bij de kaart `20261010-sessie-via-de-ingang-van-uchar` (2026-10-12), uit de bijvangst
van stap 1 en de nulmeting van die kaart.*

## Aanleiding

1. **Geen vangnet.** `DecideResource` (`service-quarkus`) vangt `UnknownPath`, `NonExecutable`, `ServiceError` en sinds
   die kaart `EvaluationFault`. Een andere exceptie, zoals een sessie die het plafond haalt (`Firing.noFixpoint()`), gaat
   naar de standaardafhandeling van Quarkus. De JDK-dienst geeft dan `500 {"fout":"interne fout"}` en logt de
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
- (c) `make poorten` groen.

## Omvang

Klein. May touch: `service-quarkus/` (main en test), `service/` alleen als de pariteitstest dat vraagt.
