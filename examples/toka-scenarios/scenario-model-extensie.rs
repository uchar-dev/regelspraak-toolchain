// Golden-scenario model-extensie — the casus-omission the golden set needs on top of
// examples/toka-reconciliatie/casus-omissies.rs. Kept SEPARATE from the reconciliatie
// file so TokaVuurTest / TokaDekkingTest stay byte-identical (they never load this).
//
// toka-26 "Hoogseizoen" assigns the kenmerk "in het hoogseizoen" to a Vlucht, but the
// casus object model (toka-02) never declares it — exactly the kind of undeclared member
// examples/toka-reconciliatie supplies for other rules. Without the declaration the
// FactModelGenerator bean has no isInHetHoogseizoen field and the lowered DRL cannot bind.
Extensie van objecttype de Vlucht
    is in het hoogseizoen kenmerk (bijvoeglijk);
