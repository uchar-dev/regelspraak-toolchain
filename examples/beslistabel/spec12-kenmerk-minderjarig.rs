// §12 beslistabel — a kenmerktoekenning conclusie (main-spec L4762: "| waar | …"). The conclusie
// header is the §9.2 kenmerk assertion; a cell of "waar" asserts it. Option-A inline form.
Objecttype de Passagier (mv: Passagiers) (bezield)
    de leeftijd Numeriek (geheel getal) met eenheid jr;
    is minderjarig kenmerk (bijvoeglijk);

Beslistabel Minderjarigheid
  geldig altijd
  |   | een passagier is minderjarig | indien zijn leeftijd kleiner is dan |
  |---|---|---|
  | 1 | waar | 18 jr |
