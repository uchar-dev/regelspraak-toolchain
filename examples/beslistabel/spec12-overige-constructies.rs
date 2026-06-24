// §12 Tier-2 constructs (Oracle checklist) that desugar transparently and lower in both back-ends — each is an
// ordinary rule-fragment. Two tables cover: A-7 value-with-unit cell (18 jr), B-1 leeg/gevuld conditie header
// (one-sided predicate, cell "waar"), B-2 datum-ordering header (date-literal cell), B-5 bijvoeglijk kenmerkcheck
// conditie ("hij <kenmerk> is", cell "waar"), and B-9 begrenzing conclusie ("… met een maximum van …").
Objecttype de Passagier (mv: Passagiers) (bezield)
    de korting Numeriek (geheel getal);
    de leeftijd Numeriek (geheel getal) met eenheid jr;
    de basis Numeriek (geheel getal);
    de inschrijfdatum Datum in dagen;
    is minderjarig kenmerk (bijvoeglijk);

Beslistabel Kortingsregeling
  geldig altijd
  |   | de korting van een Passagier moet gesteld worden op | indien zijn leeftijd leeg is | indien zijn basis groter is dan |
  |---|---|---|---|
  | 1 | 0 | waar | n.v.t. |
  | 2 | zijn basis met een maximum van 100 | n.v.t. | 0 |

Beslistabel Toeslagregeling
  geldig altijd
  |   | de korting van een Passagier moet gesteld worden op | indien zijn leeftijd kleiner is dan | indien zijn inschrijfdatum eerder is dan | indien hij minderjarig is |
  |---|---|---|---|---|
  | 1 | 10 | 18 jr  | n.v.t.   | waar   |
  | 2 | 5  | n.v.t. | 1-1-2020 | n.v.t. |
