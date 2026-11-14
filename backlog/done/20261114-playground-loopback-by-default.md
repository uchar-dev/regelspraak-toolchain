# The playground binds the loopback address by default

*Asked by the owner, 2026-11-14.*

## Aanleiding

`bin/regelspraak playground` binds `0.0.0.0`, every interface, unless `--host` says otherwise: `ServiceMain` parses
the playground's arguments with the same default as `serve` (`String host = "0.0.0.0"`). The playground is a
development workbench that compiles whatever source a page posts; started on a laptop it is reachable from the whole
network without anyone asking for that. The owner's decision: the playground listens on `127.0.0.1` by default, and
`--host 0.0.0.0` is the explicit way to open it to other machines.

`serve` keeps `0.0.0.0`: the container and the chart rely on it, and the decision service is meant to be reached.

## Goal

- `bin/regelspraak playground [--host H] [--port N] [<descriptor.service.json> <source.rgs...> | <document.json>] …`
  binds `127.0.0.1` when `--host` is absent, with and without a descriptor (with a descriptor the served entry points
  share the playground's address).
- `serve` unchanged: default `0.0.0.0`.
- The help of `bin/regelspraak`, `ServiceMain`'s javadoc, README and `docs/ENGINE.md` state the two defaults.

## Acceptance

- Tests: `playground` without `--host` binds the loopback address only (with and without a descriptor); `--host
  0.0.0.0` binds every interface; `serve` without `--host` still binds every interface.
- `make gates` green.

Result (2026-11-14):
- `ServiceMain` parses the arguments of `playground` with the default host `PLAYGROUND_HOST` (`127.0.0.1`) and those
  of `serve` with `SERVICE_HOST` (`0.0.0.0`); `--host` overrides both. The start of the server is one method per
  command (`startService`, `startPlayground`), which `main` and `playground` call and the tests too;
  `ServiceHttp.address()` gives the address actually bound.
- Witnesses in `PlaygroundCommandTest`: `the_playground_binds_the_loopback_address_unless_host_names_another`
  (without `--host` the bound address is loopback, without and with a descriptor; with `--host 0.0.0.0` it is the
  wildcard address, without and with a descriptor) and `serve_binds_every_interface_by_default`. The two parse
  assertions that expected `0.0.0.0` for `playground` now expect `127.0.0.1`, and `--host 0.0.0.0` is parsed as
  given. By hand: `bin/regelspraak playground --port 0` listens on `127.0.0.1:<port>` (`lsof`), with
  `--host 0.0.0.0` on `*:<port>`.
- The help of `bin/regelspraak`, the javadoc of `ServiceMain`, the command table of README and `docs/ENGINE.md`
  (`serve` and the workbench) state the two defaults. README's list "Contract change" gets no entry: it tells what
  changed, and the docs state the current defaults only.
- `make gates` green: pin, reactor (206/573/62), twins, replay (373/1042), manifest (29 ok, 3 excluded), image,
  kind smoke (H2, H3, H4).
