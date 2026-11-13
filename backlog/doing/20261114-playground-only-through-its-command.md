# The playground only through `bin/regelspraak playground`

*Asked by the owner, 2026-11-14.*

## Aanleiding

Since the card `playground-command` the playground starts on its own with `bin/regelspraak playground`. The only reason
left for `PLAYGROUND=true bin/regelspraak serve <descriptor> <source.rgs…>` is `?via=service`: the comparison of a
scenario with the answer of served entry points, which needs the playground and the served model in one process. That
keeps development routes in the production service behind an environment variable.

## Goal

- `bin/regelspraak playground [--host H] [--port N] [<descriptor.service.json> <source.rgs...> | <document.json>]`:
  without a descriptor the playground as now; with one it also serves that model's entry points in the same process,
  so `?via=service` works (with `DETAIL=true`/`--detail` as `via=service` needs today).
- `serve` binds no playground route, whatever the environment; `PLAYGROUND` goes from `ServiceMain`, `ServiceHttp`,
  `bin/regelspraak`, the Makefile, README and `docs/ENGINE.md`. The Quarkus adapter has no playground and stays as it
  is.
- A text for the workbench session (in the builder's working folder): the new way to start the playground, with and
  without a model.

## Acceptance

- Tests: `serve` with `PLAYGROUND=true` in its environment answers 404 on `/playground`; `playground` with a descriptor
  answers `?via=service` as `serve` with `PLAYGROUND=true` did (the existing via-service tests move to it); without a
  descriptor as in `PlaygroundCommandTest`.
- `make gates` green.
