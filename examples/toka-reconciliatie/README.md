# TOKA casus-reconciliatie (NOT part of the spec corpus)

The TOKA casus's own rules reference four members its object model never declares
(see docs/grammar-divergences.md, "TOKA casus omissions"):

| Referenced by | Written reference | Closest declared name |
|---|---|---|
| toka-32…37 (verdelingen) | `de treinmiles` (recipient) | `de treinmiles op basis van evenredige verdeling` |
| toka-35/37 (maximum-bullet) | `maximaal aantal te ontvangen treinmiles` | `de maximaal te ontvangen treinmiles bij evenredige verdeling volgens rangorde` |
| toka-41/45 (voorwaarden) | kenmerk `passagier jonger dan 18 jaar` | only the 18-24 / 25-64 / 65+ brackets exist |
| toka-22 (eerste van) | `de daadwerkelijke datum-tijd van vertrek` | only `de verwachte datum-tijd van vertrek` exists |

`casus-omissies.rs` supplements exactly these members via the casus's own Extensie
construct so the casus's rules can resolve and fire. It lives OUTSIDE `examples/toka/`
on purpose: that directory is the faithful spec extraction and is parsed by
`make verify-corpus`; this reconciliation is ours, loaded additionally by the
TOKA tests (CasusTest / TokaDekkingTest / the end-to-end firing test).
