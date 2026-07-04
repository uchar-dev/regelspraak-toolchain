// Golden marker scenario — de exit-3 MARKER-lezing (docs/SCENARIOTOETSEN.md "Exitcodes":
// een scope-regel die de transpiler niet lowert is accepted-but-inert en mag NOOIT als PASS
// lezen; de runner stript het marker-DRL-blok vóór compilatie en de dekking markeert de
// scope-regel als MARKER -> status MARKER -> exit 3). Zelfstandig (geen TOKA-bestanden nodig):
// een berekening met "het absolute tijdsevenredig deel per maand" lowert schoon tot een
// marker (geen resolve-fout, anders dan toka-23), dus de bundel resolvet OK en de runner
// bereikt de MARKER-status. Spiegelt ScenarioRunnerTest.marker_scope, als golden-CLI-oefening.
Objecttype de Vlucht (mv: Vluchten)
    de heffing Numeriek (getal met 2 decimalen) met eenheid EUR;
Regel heffing markert
geldig altijd
De heffing van een Vlucht moet berekend worden als het absolute tijdsevenredig deel per maand van 10,00 EUR.
Situatie basis-marker:
een Vlucht V1
- de heffing is 5,00 EUR
Scenario markeroefening leest exit 3
toetst de regel heffing markert
gebaseerd op de situatie basis-marker
Met als verwacht resultaat:
- de heffing van V1 is 5,00 EUR
