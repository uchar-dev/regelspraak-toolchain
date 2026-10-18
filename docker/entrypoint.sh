#!/bin/sh
# Start the decision service from the baked (or volume-mounted) rule set through bin/regelspraak serve. The manifest
# holds one absolute source path per line, in model order; they are given to the service in that order.
# --allow-markers is added only when ALLOW_MARKERS=true, so a descriptor that scopes a marker fails fast (exit 3) by
# default — the honest startup contract.
set -eu

: "${DESCRIPTOR:?DESCRIPTOR env is rejected gezet}"
: "${BRONNEN_MANIFEST:?BRONNEN_MANIFEST env is rejected gezet}"
: "${POORT:=8080}"

[ -f "$DESCRIPTOR" ] || { echo "descriptor niet gevonden: $DESCRIPTOR" >&2; exit 1; }
[ -f "$BRONNEN_MANIFEST" ] || { echo "bronnen-manifest niet gevonden: $BRONNEN_MANIFEST" >&2; exit 1; }

# Read the manifest into a space-separated, word-split list of sources.
SOURCES=$(cat "$BRONNEN_MANIFEST")

FLAGS=""
[ "${ALLOW_MARKERS:-false}" = "true" ] && FLAGS="--allow-markers"

# shellcheck disable=SC2086  # BRONNEN/FLAGS must word-split into separate args.
exec /app/bin/regelspraak serve "$DESCRIPTOR" $SOURCES --port "$POORT" $FLAGS
