# naming-cleanup: de hernoemtabellen gaan weg

De kaart `engelse-namen` zette haar tabel, beslislijst en bevroren lijst in `tools/naming/`, met in de kop dat ze
na het programma "Engelse namen" weggaan. Het programma is af (in uchar: kaart `naming-cleanup`); de historie
bewaart de bestanden. Geen script of test leest ze.

Groen: `make gates`.

## Result (2026-10-19)

`7b5f35b` haalt `tools/naming/` weg (tabel, beslislijst en bevroren lijst van de kaart `engelse-namen`). `make gates`
groen op `7b5f35b`: 7 stappen in 8m55s, reactor 1622/140/332/53, replay 225 goldens, kind-smoke H2-H4.
