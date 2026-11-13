# A playground of its own: `bin/regelspraak playground`

*Asked by the owner, 2026-11-13.*

## Aanleiding

The playground can only be started as a route of the decision service (`PLAYGROUND=true bin/regelspraak serve
<descriptor> <source.rgs>`), although it analyses the source the user enters in the page and uses the served model
only for `?via=service` (the comparison of a scenario with the answer of the served entry points). A descriptor
without entrypoints may start since 2026-11-12, with a warning.

## Goal

`bin/regelspraak playground [--host H] [--port N]` starts the playground without a descriptor and without a source;
`?via=service` is then not offered (the page says why). `serve` with `PLAYGROUND=true` stays for the comparison with a
served model. README, `docs/ENGINE.md` and `bin/regelspraak --help` describe the command.

## Acceptance

- A test that starts `playground` without arguments, analyses a source through `/playground/analyse` and gets no
  `via=service`; the existing playground tests stay green.
- `make gates` green.

Result (2026-11-13):
- `bin/regelspraak playground [--host H] [--port N]` starts the playground without a descriptor and without a source:
  `GET /playground` and `POST /playground/analyse` only; `?via=service` is a 400 `UNSUPPORTED` with its reason, which the
  page shows. README, `docs/ENGINE.md` and `--help` describe it. Witness `PlaygroundCommandTest`.
- `make gates` green.
