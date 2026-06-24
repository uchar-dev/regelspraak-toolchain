// §12 beslistabel — a conditie-kolom on a KENMERK plus reisduur bands (main-spec L4766, the mixed
// 4-column table). "waar" in a kenmerk conditie-kolom asserts the kenmerk holds; "n.v.t." omits it.
Objecttype de Passagier (mv: Passagiers) (bezield)
    de reisduur Numeriek (geheel getal);
    de belasting Numeriek (geheel getal);
    het recht op duurzaamheidskorting kenmerk (bezittelijk);

Beslistabel Belasting met kortingvoorwaarde
  geldig altijd
  |   | de belasting van een Passagier moet gesteld worden op | indien hij een recht op duurzaamheidskorting heeft | indien zijn reisduur groter is dan | indien zijn reisduur kleiner of gelijk is aan |
  |---|---|---|---|---|
  | 1 | 10 | waar   | n.v.t. | 60  |
  | 2 | 20 | waar   | 60     | 120 |
  | 3 | 30 | n.v.t. | 120    | n.v.t. |
