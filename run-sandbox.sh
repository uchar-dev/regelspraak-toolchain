#!/usr/bin/env bash
# Run Claude Code in a throwaway container that can see ONLY this repo.
#
#   ./run-sandbox.sh            # start Claude (bypass permissions, inside the box)
#   ./run-sandbox.sh --continue # resume the most recent in-container session
#   ./run-sandbox.sh --resume   # pick a past in-container session to resume
#   ./run-sandbox.sh --build    # rebuild the image first, then start
#   ./run-sandbox.sh --shell    # drop into bash (e.g. to run `claude /login` by hand)
#
# Sessions live in the container's volume, not your host ~/.claude, so resume
# from here -- `claude --resume <id>` on the host will not find them.
#
# Isolation: the repo is bind-mounted at /work and -- apart from the knowledge
# bridge mailbox at /work/.bridge (see ../regelspraak-bridge/POLICY.md) -- nothing
# else on the host is mounted, so ~/.ssh, ~/.aws, other projects and the host
# shell are invisible to the container. The prior parser/transpiler are NEVER
# mounted; the bridge is the only contact surface, and POLICY.md governs it.
#
# Login persists: the container user's whole HOME (/home/claude) is a named Docker
# volume, so BOTH the OAuth token (~/.claude/.credentials.json) and the onboarding
# state (~/.claude.json) survive across runs. Log in once on the first start; the
# named volume is NOT deleted by --rm.
#
# Safety: never add `-v /var/run/docker.sock` or mount host secrets here. Under
# --dangerously-skip-permissions, code in /work can read the login token in the
# volume, so only run repos you trust.
set -euo pipefail

IMAGE=rsr-sandbox
VOLUME=rsr-claude-home
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # project root (this script lives here)

# Knowledge-bridge mailbox shared with the container at /work/.bridge. It lives
# OUTSIDE the repo (default: sibling ../regelspraak-bridge) so it never pollutes
# the tree; the prior parser/transpiler are never mounted -- POLICY.md in it
# governs what may cross. Override the host location with RSR_BRIDGE.
BRIDGE="${RSR_BRIDGE:-$(cd "$ROOT/.." && pwd)/regelspraak-bridge}"
mkdir -p "$BRIDGE"

build() { docker build -t "$IMAGE" "$ROOT/docker"; }

cmd=()
case "${1:-}" in
  --build) build; shift ;;
  -c|--continue) cmd=(claude --continue --dangerously-skip-permissions); shift ;;
  -r|--resume) cmd=(claude --resume --dangerously-skip-permissions); shift ;;
  --shell) cmd=(bash); shift ;;
  -h|--help) echo "usage: ./$(basename "$0") [--build | --continue | --resume | --shell]"; exit 0 ;;
esac

# Build on first use if the image is missing.
docker image inspect "$IMAGE" >/dev/null 2>&1 || build

exec docker run --rm -it \
  -v "$VOLUME:/home/claude" \
  -v "$ROOT:/work" \
  -v "$BRIDGE:/work/.bridge" \
  "$IMAGE" ${cmd[@]+"${cmd[@]}"}
