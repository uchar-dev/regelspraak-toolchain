// §12 beslistabel — a conditie cell whose value carries an eenheid DIFFERENT from the context attribute's
// declared unit (main-spec §4/§13.3.5): the attribuut "afstand" is declared in km, the cell writes "1000 m".
// The desugared "zijn afstand groter is dan 1000 m" is an ordinary §4 cross-unit comparison — km and m are
// verenigbaar (both reduce to the afstand-stelsel canoniek m), so the resolver accepts it (no eenheidsfout)
// and BOTH back-ends restate exactly (1000 m = 1 km) rather than comparing raw magnitudes. The desugar adds
// no gap: a hand-written rule with the same comparison lowers identically (DRL ≡ exec-model).
Eenheidsysteem afstand
  de meter (mv: meters)             m
  de kilometer (mv: kilometers)     km      =    1000 m
Objecttype de Passagier (mv: Passagiers) (bezield)
    de afstand Numeriek (geheel getal) met eenheid km;
    de korting Numeriek (geheel getal);

Beslistabel Kortingsregeling
  geldig altijd
  |   | de korting van een Passagier moet gesteld worden op | indien zijn afstand groter is dan |
  |---|---|---|
  | 1 | 10 | 1000 m |
