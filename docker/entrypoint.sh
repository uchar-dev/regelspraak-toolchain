#!/bin/sh
# Launch DienstMain from the baked (or volume-mounted) rule set. The manifest holds
# one absolute .rgs path per line in bronbestanden() order; we feed them as positional
# bronnen. --allow-markers is added only when ALLOW_MARKERS=true, so a marker-scoping
# descriptor fails fast (exit 3) by default — the honest startup contract.
set -eu

: "${DESCRIPTOR:?DESCRIPTOR env is niet gezet}"
: "${BRONNEN_MANIFEST:?BRONNEN_MANIFEST env is niet gezet}"
: "${POORT:=8080}"

[ -f "$DESCRIPTOR" ] || { echo "descriptor niet gevonden: $DESCRIPTOR" >&2; exit 1; }
[ -f "$BRONNEN_MANIFEST" ] || { echo "bronnen-manifest niet gevonden: $BRONNEN_MANIFEST" >&2; exit 1; }

# Read the manifest into a space-separated, word-split list of bronbestanden.
BRONNEN=$(cat "$BRONNEN_MANIFEST")

FLAGS=""
[ "${ALLOW_MARKERS:-false}" = "true" ] && FLAGS="--allow-markers"

# shellcheck disable=SC2086  # BRONNEN/FLAGS must word-split into separate args.
exec java -cp '/app/lib/*:/app/service.jar' dev.uchar.regelspraak.toolchain.service.http.DienstMain \
    "$DESCRIPTOR" $BRONNEN --poort "$POORT" $FLAGS
