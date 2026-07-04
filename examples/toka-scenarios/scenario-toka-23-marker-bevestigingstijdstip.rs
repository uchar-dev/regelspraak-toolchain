// Golden TOKA scenario — toka-23 "Bevestigingstijdstip vlucht", de EERLIJKE MARKER.
// Bron: TokaDekkingTest (toka-23 is de enige niet-gelowerde TOKA-regel). §11 "Daarbij
// geldt" inlinet A = uiterste boekingstijdstip PLUS bevestigingsinterval — een Datum-tijd
// PLUS een Datum-tijd (de casus declareert 'de bevestigingsinterval' als Datum en tijd in
// millisecondes, toka-07). §4.1 Tabel 2 VERBIEDT datum-tijd + datum-tijd, dus dit is een
// resolve-time TYPE-ERROR — precies de fout die toka-23's LAATSTE-fold tot een marker Frag
// degradeert in de transpiler, en die de enige verwachte CasusTest-fout is (TokaDekkingTest).
//
// GEVOLG voor de runner: de bundel resolvet NIET (één ERROR), dus de CLI weigert de
// ill-getypeerde bundel en leest EXIT 1 (resolve-error met diagnostiek) — NIET exit 3.
// Dat is de eerlijke uitkomst: accepted input, maar de scope-regel zelf is spec-illegaal
// getypeerd, en de runner mag zo'n bundel nooit stil laten passeren. De exit-3 MARKER-lezing
// (een scope-regel die schoon tot een marker lowert ZONDER resolve-fout) wordt geoefend door
// scenario-marker-exit3.rs en door ScenarioRunnerTest.marker_scope. Het manifest verwacht
// voor dit scenario dus exit 1 (zie examples/toka-scenarios/manifest, met deze reden).
Situatie basis-23:
een Vlucht V1
- het uiterste boekingstijdstip is 15-06-2024 12:00:00.000
de parameter de eerste boekingsdatum is 01-01-2024
de parameter de bevestigingsinterval is 01-01-1970 00:05:00.000
Scenario bevestigingstijdstip is spec-illegaal getypeerd
toetst de regel Bevestigingstijdstip vlucht
gebaseerd op de situatie basis-23
Met als verwacht resultaat:
- het bevestigingstijdstip van V1 is leeg
