// §12 Woonregio — the spec-faithful modelling: woonprovincie is an ENUMERATIE domein (§3.4.2), so the
// conditie cells are §13.2.14 enumeratiewaarden ('Friesland' — SINGLE-quoted, the spec's own notation),
// not double-quoted Tekst. A §5.7 "of"-series of them desugars to a "tenminste één" OR-list of value
// equalities, lowered (in both back-ends) as string equality on the enum label.
Domein Provincie is van het type Enumeratie
    'Friesland'
    'Groningen'
    'Drenthe'
    'Zeeland'
    'Limburg'
    'Noord-Brabant'
    'Gelderland'
    'Overijssel'
    'Flevoland'
    'Noord-Holland'
    'Zuid-Holland'
    'Utrecht'
Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
    de woonregio factor Numeriek (geheel getal);
    de woonprovincie Provincie;

Beslistabel Woonregio factor
  geldig altijd
  |   | de woonregio factor van een Natuurlijk persoon moet gesteld worden op | indien zijn woonprovincie gelijk is aan |
  |---|---|---|
  | 1 | 1 | 'Friesland', 'Groningen', 'Drenthe', 'Zeeland' of 'Limburg' |
  | 2 | 2 | 'Noord-Brabant', 'Gelderland', 'Overijssel' of 'Flevoland' |
  | 3 | 3 | 'Noord-Holland', 'Zuid-Holland' of 'Utrecht' |
