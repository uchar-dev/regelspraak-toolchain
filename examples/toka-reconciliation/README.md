# TOKA casus-reconciliatie (NOT part of the spec corpus)

The TOKA casus's own rules reference five members its object model never declares
(see docs/historical/grammar-divergences.md, "TOKA casus omissions"):

| Referenced by | Written reference | Closest declared name |
|---|---|---|
| toka-32…37 (verdelingen) | `de treinmiles` (recipient) | `de treinmiles op basis van evenredige verdeling` |
| toka-35/37 (maximum-bullet) | `maximaal aantal te ontvangen treinmiles` | `de maximaal te ontvangen treinmiles bij evenredige verdeling volgens rangorde` |
| toka-41/45 (voorwaarden) | kenmerk `passagier jonger dan 18 jaar` | only the 18-24 / 25-64 / 65+ brackets exist |
| toka-22 (eerste van) | `de daadwerkelijke datum-tijd van vertrek` | only `de verwachte datum-tijd van vertrek` exists |
| toka-43/44 (startpuntbepaling; the same sentences are normative text of §9.9 of the RegelSpraak specification) | `de datum van vertrek` (of `de reis`) | none — the published Vlucht objecttype declares NO `datum van vertrek` attribute at all, so the written `de datum van vertrek van de reis` (attribute + subject back-reference) has nothing to bind against |

`casus-omissies.rgs` supplements exactly these members via the casus's own Extensie
construct so the casus's rules can resolve and fire. It lives OUTSIDE `examples/toka/`
on purpose: that directory holds the 47 definition fragments copied verbatim from the casus,
byte-identical to uchar's `toka/casus/*.rgs`; this reconciliation is ours. It is loaded
beside the fragments wherever the rules must resolve: every line of
`examples/toka-scenarios/manifest` (`make scenario-test`), the TOKA model foundation of
`make facts` and `FactModelCliTest`
(`toolchain/src/test/java/dev/uchar/regelspraak/toolchain/cli/FactModelCliTest.java`), and the
service's TOKA set (`service/src/test/java/dev/uchar/regelspraak/toolchain/service/TokaSource.java`).
uchar's `CasusTest` (`uchar/toka/src/test/java/dev/uchar/regelspraak/CasusTest.java`) does NOT
load it: it pins that uchar's copy of the fragments resolves with exactly its two known errors
(toka-23 and toka-45).
