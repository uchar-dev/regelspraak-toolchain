// §12 Tier-2 constructs (Oracle checklist B-1/B-9): a §8.1.2 leeg/gevuld CONDITIE header (a one-sided predicate,
// cell "waar" = the check holds) and a §6.1.4 begrenzing CONCLUSIE value ("… met een maximum van …"). Both are
// ordinary rule-fragments, so they desugar transparently and lower in both back-ends.
Objecttype de Passagier (mv: Passagiers) (bezield)
    de korting Numeriek (geheel getal);
    de leeftijd Numeriek (geheel getal);
    de basis Numeriek (geheel getal);

Beslistabel Kortingsregeling
  geldig altijd
  |   | de korting van een Passagier moet gesteld worden op | indien zijn leeftijd leeg is | indien zijn basis groter is dan |
  |---|---|---|---|
  | 1 | 0 | waar | n.v.t. |
  | 2 | zijn basis met een maximum van 100 | n.v.t. | 0 |
