# docker/

This directory serves two unrelated roles; keep them straight.

1. **The agent sandbox image** — `Dockerfile` + `entrypoint.sh` + `claude-config/`
   build the container that *becomes* the coding agent's environment. It is
   launched from the host by `../run-sandbox.sh` (or `make run-sandbox`). Never
   build or run it from inside the sandbox itself — there is no Docker in there and
   the nesting is meaningless (see `../CLAUDE.md`).

2. **The beslisservice production image** — `service.Dockerfile` +
   `service-entrypoint.sh` build the deployable decision service, produced via
   `make image` (`marker/` holds the tiny descriptor used to smoke the build).

For the deploy runbook — container contract, Helm probes, the peildatum contract —
see `../docs/ENGINE.md`.
