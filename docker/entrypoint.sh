#!/usr/bin/env bash
# Seed the container user's Claude config from the image's baked defaults, then run
# whatever command was requested (so it still composes with run-sandbox.sh's
# --shell / --continue / --resume overrides).
#
# Each file is written ONLY when absent: a fresh volume gets configured on first
# run, but once the volume holds a settings.json / statusline.sh the entrypoint
# leaves it alone, so in-session /config changes and manual edits in the volume
# persist. Credentials and session history are never touched. To re-apply an
# edited default from docker/claude-config/, delete that file in the volume (or
# the whole volume) and start again.
set -e
mkdir -p "$HOME/.claude"
[ -f "$HOME/.claude/settings.json" ]  || cp            /opt/claude-config/settings.json  "$HOME/.claude/settings.json"
[ -f "$HOME/.claude/statusline.sh" ]  || install -m 0755 /opt/claude-config/statusline.sh "$HOME/.claude/statusline.sh"
exec "$@"
