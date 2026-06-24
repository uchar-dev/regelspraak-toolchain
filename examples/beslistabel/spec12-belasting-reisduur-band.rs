// §12 beslistabel — the reisduur band table (main-spec L4744), Option-A inline form. Two conditie-
// kolommen (an EN-relatie = AND); a cell of "n.v.t." OMITS that condition for the row (so the open
// ends of the first/last band carry only one bound). Self-contained: the spec uses bovengrens/
// percentage PARAMETERS, here inlined as literals so the table resolves on its own.
Objecttype de Passagier (mv: Passagiers) (bezield)
    de reisduur Numeriek (geheel getal);
    de belasting Numeriek (geheel getal);

Beslistabel Belasting op basis van reisduur
  geldig altijd
  |   | de belasting van een Passagier moet gesteld worden op | indien zijn reisduur groter is dan | indien zijn reisduur kleiner of gelijk is aan |
  |---|---|---|---|
  | 1 | 10 | n.v.t. | 60  |
  | 2 | 20 | 60     | 120 |
  | 3 | 30 | 120    | n.v.t. |
