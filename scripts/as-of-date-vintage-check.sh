#!/usr/bin/env bash
# Advisory pre-install check for the asOfDate contract (card 20260706-07:23).
#
# Reads a parameterset sidecar (<name>.parameters.json, the ParameterSetLoader shape) and WARNs when it carries
# MORE THAN ONE vintage for any parameter while the operator declared multiVintage=false. That is
# a declared/actual MISMATCH: the deploy ships multi-vintage data without the operator acknowledging that every
# request must carry `asOfDate`. The RUNTIME remains the enforcement authority (it 400s a missing asOfDate);
# this only surfaces the mismatch at deploy time so it is not silent.
# A sidecar without the key `parameterSets` is not the shape the loader reads: that FAILS (exit 1), because an
# old-shape sidecar (`parametersets`, `waarden`) would otherwise be reported as single-value.
#
# Usage:  as-of-date-vintage-check.sh <parameter-sets.json> <declared-multiVintage: true|false>
# Exit:   0 (advisory: a mismatch is a WARN on stderr, not a block); 1 when `parameterSets` is missing or no list.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <parameter-sets.json> <declared-multiVintage true|false>" >&2
  exit 2   # a usage error is a caller mistake, distinct from the advisory 0.
fi

SETS_FILE="$1"
DECLARED="$2"

if [ ! -f "$SETS_FILE" ]; then
  echo "as-of-date-vintage-check: geen parameterSets-bestand op '$SETS_FILE' — niets te controleren." >&2
  exit 0   # no sidecar mounted = the single-value default; nothing to warn about.
fi

# Count the MAX number of validity-dated values any single parameter carries across all sets. >1 == multi-vintage.
# JSON shape: { "parameterSets": [ { "name": ..., "values": { "<param>": [ {from,to,value}, ... ] } } ] }
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
sets = doc.get("parameterSets") if isinstance(doc, dict) else None
if not isinstance(sets, list):
    print(f"as-of-date-vintage-check: '{sets_file}' heeft geen lijst onder de sleutel 'parameterSets' — dat is de"
          " vorm die de loader leest (oude sidecars: parametersets/naam/waarden/waarde/vanaf/tot/eenheid)", file=sys.stderr)
    sys.exit(1)
for pset in sets:
    for param, vintages in (pset.get("values") or {}).items():
        n = len(vintages) if isinstance(vintages, list) else 1
        multi[param] = max(multi.get(param, 0), n)

multi_params = sorted(p for p, n in multi.items() if n > 1)

if multi_params and not declared:
    print("=========================================================================", file=sys.stderr)
    print("WAARSCHUWING (peildatum-contract): de parameterSets zijn MULTI-VINTAGE", file=sys.stderr)
    print(f"  ({', '.join(multi_params)} dragen >1 geldigheidsversie), maar de deploy", file=sys.stderr)
    print("  declareert parameterSets.multiVintage=false. ELK verzoek MOET dan tóch een", file=sys.stderr)
    print("  `asOfDate` meesturen (de runtime 400't 'asOfDate is verplicht' zonder). Zet", file=sys.stderr)
    print("  --set parameterSets.multiVintage=true zodat de NOTES de operator dit vertellen.", file=sys.stderr)
    print("=========================================================================", file=sys.stderr)
elif multi_params and declared:
    print(f"as-of-date-vintage-check: multi-vintage bevestigd ({', '.join(multi_params)}) — "
          "declaratie klopt, geen mismatch.", file=sys.stderr)
else:
    print("as-of-date-vintage-check: geen multi-vintage parameter gevonden — single-value pad, ok.", file=sys.stderr)
PY

exit 0
