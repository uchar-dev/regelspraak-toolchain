# vendor/uchar-frontend — the RegelSpraak frontend, consumed from uchar

This directory is a **byte-identical vendored copy** of the TOKA-clean slice
`frontends/regelspraak/**` of the **uchar** spec repository. Since the
dependency inversion (LADDER-EVOLUTIE §6 / Q5, ratified 2026-08-29) this
vendored frontend is the **only** RegelSpraak frontend the toolchain compiles:
the toolchain's own `{grammar,resolver,ir,model,emit}` copies were deleted and
the transpiler / runtime / schema / graaf packages now build against these
types.

## Provenance — the single pin

- **Source repository:** uchar (private).
- **Source path:** `frontends/regelspraak/**` (the whole slice, tests and
  Makefile included).
- **Source commit:** `ff19a78be6f26643233e60373d2074f55194f85f`
  — this is exactly the value in `/SPEC_REV` at the repo root, the **single
  pin**. There is no second pin.

The copy lives under `frontends/regelspraak/` here so the mapping to the
source path is one-to-one: `vendor/uchar-frontend/frontends/regelspraak/<p>`
mirrors uchar's `frontends/regelspraak/<p>`.

## Drift gate

`scripts/uchar-conformance.sh` (the toolchain's SPEC_REV drift gate) verifies,
on every run, that every file here is **byte-identical** to the pinned source
blob (`git -C <uchar> show $SPEC_REV:frontends/regelspraak/<p>`), and that the
file set matches the pin exactly (no missing, no extra). It then runs the
emitter-golden byte-identity smoke over the pinned corpus. Do **not** edit
files under `frontends/regelspraak/` here by hand — changes flow spec-first in
uchar, then land here as a pin bump (re-vendor + re-pin SPEC_REV together).

## What the toolchain build consumes

- **Grammar:** `frontends/regelspraak/grammar/{RegelSpraakLexer,RegelSpraak}.g4`
  — generated into `gen/` by `make parser`.
- **Java source root:** `frontends/regelspraak/src/main/java` — added as a
  compile source root by the Makefile (`make resolver`) and by `pom.xml`
  (build-helper `add-source`), the same way uchar's `backends/drools/pom.xml`
  wires the frontend IR records as a foreign source root.

The vendored `src/test/java` tree is part of the pinned snapshot (and is
covered by the drift gate) but is **not** compiled by the toolchain: those are
the frontend's own gate-10 tests in uchar. The vendored `Makefile` is the
frontend's stand-alone jar-only build; the toolchain does not invoke it.

## Load-bearing package-private entry points

The toolchain-local `resolver/ScenarioResolver.java` deliberately lives in the
same `resolver` package as the vendored frontend (a split package across the
two source roots) and calls two **package-private** frontend classes:
`RuleChecker` (`new RuleChecker(model).check(...)`) and
`KenmerkMatcher.vind(...)`. A uchar-side refactor of these non-public classes
is legitimate there but surfaces here only as a compile failure at the next
re-vendor — loud, but not explained by the byte-drift gate. Treat them as
consumed API when evolving the frontend.
