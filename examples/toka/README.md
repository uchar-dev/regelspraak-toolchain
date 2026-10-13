# TOKA casus — test fixtures

The `.rgs` files in this directory and in `../toka-scenarios/` exercise the
toolchain against the **TOKA** casus — a fictitious worked example from the
RegelSpraak v2.3.0 specification, authored and published by the
**Belastingdienst** (Dutch Tax & Customs Administration) as a training case.

The TOKA data is explicitly fictitious ("volledig verzonnen", per the spec's own
disclaimer) and has no relation to any real case.

These fixtures are a mechanical split of the TOKA object/parameter/rule
definitions (verbatim from the casus) plus this project's own scenario-test
harness (the `Situatie`/`Scenario`/`Met-als-verwacht` wrappers).

> **TOKA casus.** Source: *RegelSpraak specificatie* v2.3.0 (4-12-2025),
> Bijlage 1, "Treinen Op Korte Afstand" (TOKA, a fictitious act from the
> Belastingdienst's ALEF course), published by the Belastingdienst at
> https://wendbarewetsuitvoering.pleio.nl/page/view/291f5846-cdfc-42d9-9f31-a2158fe59128/specificaties-regelspraak.
> Rights notice of the source: "© 2025 Belastingdienst / Alle rechten voorbehouden".
> Status: the Belastingdienst indicated to the maintainer on 2026-10-13 that
> TOKA is meant to be releasable. It is probably under EUPL-1.2, like ALEF
> (https://github.com/belastingdienst/ALEF) and the repository
> MinBZK/regels.overheid.nl, which carries a 2022 version of the course casus.
> Written confirmation: not yet received.
> The licence of this repository (Apache-2.0) does not cover this material.

Copied verbatim from the casus: the 47 `.rgs` files in this directory,
byte-identical to uchar's `toka/casus/*.rgs`; they are not edited, so that the
two stay in parity. Derived from the casus or this project's own work: this
README, the service descriptor `toka.dienst.json`, the scenarios in
`../toka-scenarios/`, the reconciliation in `../toka-reconciliatie/`, the
decision-table examples `../beslistabel/spec12-*.rgs`, and the tests and test
resources that load the casus.
