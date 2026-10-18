#!/usr/bin/env bash
# Advisory pre-install check for the peildatum-contract (card 20260706-07:23).
#
# Reads a parameterset sidecar (<naam>.parameters.json, the ParameterSetLoader shape) and WARNs — never fails —
# when it carries MORE THAN ONE vintage for any parameter while the operator declared multiVintage=false. That is
# a declared/actual MISMATCH: the deploy ships multi-vintage data without the operator acknowledging that every
# request must carry `peildatum`. The RUNTIME remains the enforcement authority (it 400s a missing peildatum);
# this only surfaces the mismatch at deploy time so it is not silent.
#
# Usage:  as-of-date-vintage-check.sh <parametersets.json> <declared-multiVintage: true|false>
# Exit:   always 0 (advisory). A real problem is a WARN on stderr, not a block.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <parametersets.json> <declared-multiVintage true|false>" >&2
  exit 2   # a usage error is a caller mistake, distinct from the advisory 0.
fi

SETS_FILE="$1"
DECLARED="$2"

if [ ! -f "$SETS_FILE" ]; then
  echo "as-of-date-vintage-check: geen parametersets-bestand op '$SETS_FILE' — niets te controleren." >&2
  exit 0   # no sidecar mounted = the single-value default; nothing to warn about.
fi

# Count the MAX number of validity-dated values any single parameter carries across all sets. >1 == multi-vintage.
# JSON shape: { "parametersets": [ { "waarden": { "<param>": [ {vanaf,tot,waarde}, ... ] } } ] }
SETS_FILE="$SETS_FILE" DECLARED="$DECLARED" python3 - <<'PY'
import json, os, sys

sets_file = os.environ["SETS_FILE"]
declared = os.environ["DECLARED"].strip().lower() == "true"

try:
    doc = json.load(open(sets_file))
except (OSError, ValueError) as e:
    # A malformed sidecar is the runtime's job to reject loudly; here we only note we could not inspect it.
    print(f"as-of-date-vintage-check: kon '{sets_file}' niet lezen als JSON ({e}) — sla advisory over.", file=sys.stderr)
    sys.exit(0)

multi = {}   # parameter -> vintage count, only where count > 1
for pset in doc.get("parametersets", []):
    for param, vintages in (pset.get("waarden") or {}).items():
        n = len(vintages) if isinstance(vintages, list) else 1
        multi[param] = max(multi.get(param, 0), n)

multi_params = sorted(p for p, n in multi.items() if n > 1)

if multi_params and not declared:
    print("=========================================================================", file=sys.stderr)
    print("WAARSCHUWING (peildatum-contract): de parametersets zijn MULTI-VINTAGE", file=sys.stderr)
    print(f"  ({', '.join(multi_params)} dragen >1 geldigheidsversie), maar de deploy", file=sys.stderr)
    print("  declareert parametersets.multiVintage=false. ELK verzoek MOET dan tóch een", file=sys.stderr)
    print("  `peildatum` meesturen (de runtime 400't 'peildatum verplicht' zonder). Zet", file=sys.stderr)
    print("  --set parametersets.multiVintage=true zodat de NOTES de operator dit vertellen.", file=sys.stderr)
    print("=========================================================================", file=sys.stderr)
elif multi_params and declared:
    print(f"as-of-date-vintage-check: multi-vintage bevestigd ({', '.join(multi_params)}) — "
          "declaratie klopt, geen mismatch.", file=sys.stderr)
else:
    print("as-of-date-vintage-check: geen multi-vintage parameter gevonden — single-value pad, ok.", file=sys.stderr)
PY

exit 0
