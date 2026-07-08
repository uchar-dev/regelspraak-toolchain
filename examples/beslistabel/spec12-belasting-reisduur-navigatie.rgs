// §12 reisduur band table (main-spec L4744) on the TOKA model — the flagship §12 table, proving the full
// machinery: TWO conditie-kolommen (EN-relatie) over a NAVIGATED attribute ("de reisduur per trein van zijn
// reis", §5.5 onderwerpketen through the "Vlucht van natuurlijke personen" feittype), n.v.t. on the open band
// ends, PARAMETER refs in the conditie cells, an afronding conclusie expression, and "geldig vanaf 2018".
// NB: the spec's exact conclusie is "het percentage <schijf> van zijn belasting op basis van afstand …" — the
// §13.4.17.12 percentage function WITHOUT '%' is a KNOWN general grammar/resolver limitation (beslistabel-
// transparent: it markers identically in a hand-written rule), carded separately. This fixture keeps the spec
// STRUCTURE and uses the afronding of the own attribuut as the value. The conditie headers ARE spec-exact: the
// "in minuten" appositive unit qualifier (main-spec L4744) now lowers — the resolver strips an IDENTITY-unit
// "in <eenheid>" suffix on an attribuut read (the named unit IS the attribute's own unit; §3.7/§5.1 restatement is
// implicit), so "de reisduur per trein in minuten van zijn reis" reads "reisduur per trein" and both back-ends
// lower it identically. NB: "reisduur per trein" is declared HERE "met eenheid minuut" so "in minuten" is the
// attribute's native unit (the strip is identity-only — a non-native convertible "in jaren" would marker, never
// silently re-magnitude); the spec's attribuut table (L1215) leaves it unitless, but the §12 comparison is against
// the minuut-typed "bovengrens reisduur … schijf" parameters, so a minuut unit is the type-consistent reading.
Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
Objecttype de Vlucht (mv: Vluchten)
    de reisduur per trein Numeriek (geheel getal) met eenheid minuut;
Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
    de belasting op basis van afstand Bedrag;
    de belasting op basis van reisduur Bedrag;
Feittype Vlucht van natuurlijke personen
    de reis Vlucht
    de passagier Natuurlijk persoon
Eén reis betreft de verplaatsing van meerdere passagiers
Parameter de bovengrens reisduur eerste schijf : Numeriek (geheel getal) met eenheid minuut
Parameter de bovengrens reisduur tweede schijf : Numeriek (geheel getal) met eenheid minuut

Beslistabel Belasting op basis van reisduur
  geldig vanaf 2018
  |   | de belasting op basis van reisduur van een Natuurlijk persoon moet gesteld worden op | indien de reisduur per trein in minuten van zijn reis groter is dan | indien de reisduur per trein in minuten van zijn reis kleiner of gelijk is aan |
  |---|---|---|---|
  | 1 | zijn belasting op basis van afstand naar beneden afgerond op 0 decimalen | n.v.t. | de bovengrens reisduur eerste schijf |
  | 2 | zijn belasting op basis van afstand naar beneden afgerond op 0 decimalen | de bovengrens reisduur eerste schijf | de bovengrens reisduur tweede schijf |
  | 3 | zijn belasting op basis van afstand naar beneden afgerond op 0 decimalen | de bovengrens reisduur tweede schijf | n.v.t. |
