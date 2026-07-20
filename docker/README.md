# docker/

The **beslisservice production image** — `Dockerfile` + `entrypoint.sh` build the
deployable decision service, produced via `make image` (`marker/` holds the tiny
descriptor used to smoke the build).

For the deploy runbook — container contract, Helm probes, the peildatum contract —
see `../docs/ENGINE.md`.

The agent-sandbox launcher is maintained in a separate repository.
