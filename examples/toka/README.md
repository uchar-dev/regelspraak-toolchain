# TOKA casus — test fixtures

The `.rgs` files in this directory and in `../toka-scenarios/` exercise the
toolchain against the **TOKA** casus — a fictitious worked example from the
RegelSpraak v2.3.0 specification, authored and published by the
**Belastingdienst** (Dutch Tax & Customs Administration) as a training case.

The TOKA data is explicitly fictitious ("volledig verzonnen", per the spec's own
disclaimer) and has no relation to any real case.

These fixtures are a mechanical split of the TOKA object/parameter/rule
definitions (verbatim from the casus) plus this project's own scenario-test
harness (the `Situatie`/`Scenario`/`Met-als-verwacht` wrappers). The TOKA casus
document itself is © Belastingdienst and is not redistributed here — see
`specification/v2.3.0/README.md` for where to obtain it.
