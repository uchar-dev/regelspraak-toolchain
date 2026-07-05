#!/usr/bin/env bash
# HOST-side kind smoke: build the beslisservice image, load it into a kind cluster,
# and drive the three gates against the LIVE in-cluster service:
#   H2  cluster oracle  — the 30 golden TOKA scenariotoetsen over HTTP
#   H3  fail-fast       — a marker-scoping descriptor crashes (exit 3) without
#                         --allow-markers, and starts clean WITH it
#   H4  rollout         — a rebuilt image with a changed parameter (volwassenleeftijd)
#                         changes the decision, proving descriptor overlay + rollout
#
# One linear gate sequence; --teardown deletes the kind cluster at the end.
set -euo pipefail

TEARDOWN=false
for arg in "$@"; do
  case "$arg" in
    --teardown) TEARDOWN=true ;;
    *) echo "onbekende optie: $arg" >&2; exit 2 ;;
  esac
done

# ---- config -------------------------------------------------------------------
CLUSTER=${CLUSTER:-regelspraak}
IMG_REPO=beslisservice
TAG=${TAG:-dev}
RELEASE=beslisservice
MARKER_RELEASE=beslisservice-marker
LOCAL_PORT=${LOCAL_PORT:-18080}
CHART=helm/beslisservice

cd "$(dirname "$0")/.."

# ---- port-forward management --------------------------------------------------
PF_PID=""
cleanup() { [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true; }
trap cleanup EXIT

# pf <svc> <localport>: kill any prior forward, start a fresh one, wait for /info.
pf() {
  local svc="$1" port="$2" i
  [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true
  kubectl port-forward "svc/$svc" "$port:8080" >/dev/null 2>&1 &
  PF_PID=$!
  for i in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:$port/info" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "port-forward naar $svc kwam niet /info-ready binnen 30s" >&2
  return 1
}

# ---- cluster ------------------------------------------------------------------
echo "== cluster =="
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "hergebruik bestaande kind cluster '$CLUSTER'"
else
  kind create cluster --name "$CLUSTER"
fi

# ---- build + load -------------------------------------------------------------
echo "== build + load =="
make image TAG="$TAG"
kind load docker-image "$IMG_REPO:$TAG" --name "$CLUSTER"

# ---- install ------------------------------------------------------------------
echo "== install =="
helm upgrade --install "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG"
kubectl rollout status "deploy/$RELEASE" --timeout=600s

pf "$RELEASE" "$LOCAL_PORT"
echo "/info dekking-excerpt:"
curl -fsS "http://127.0.0.1:$LOCAL_PORT/info" | (grep -o '"dekking"[^}]*}' || true)

# ---- H2: cluster oracle -------------------------------------------------------
echo "== H2 =="
# The identical 30 golden assertions (GoldenOverHttpTest) against the IN-CLUSTER
# service via -Ddienst.url. Offline (-o): deps are already in the local repo.
mvn -o -B -f service/pom.xml test -Dtest=GoldenOverHttpTest \
    -Ddienst.url="http://127.0.0.1:$LOCAL_PORT"
echo "H2 PASS: 30/30 golden over de in-cluster dienst"

# ---- H3: fail-fast ------------------------------------------------------------
echo "== H3 =="
# Install the marker descriptor with allowMarkers=false → DienstMain exit 3 → CrashLoopBackOff.
helm upgrade --install "$MARKER_RELEASE" "$CHART" --set image.tag="$TAG" \
    --set descriptor=/app/rules/marker/marker.dienst.json \
    --set bronnenManifest=/app/rules/marker/manifest.txt

MARKER_POD=""
for i in $(seq 1 60); do
  MARKER_POD=$(kubectl get pods -l "app.kubernetes.io/instance=$MARKER_RELEASE" \
      -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  [ -n "$MARKER_POD" ] || { sleep 2; continue; }
  reason=$(kubectl get pod "$MARKER_POD" \
      -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || true)
  restarts=$(kubectl get pod "$MARKER_POD" \
      -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo 0)
  if [ "$reason" = "CrashLoopBackOff" ] || [ "${restarts:-0}" -ge 1 ]; then break; fi
  sleep 2
done
[ -n "$MARKER_POD" ] || { echo "marker-pod verscheen niet" >&2; exit 1; }

# DienstMain prints "MARKER: in-scope regel '<naam>' is een marker … in entrypoint '<ep>'".
# The current attempt may still be mid-startup (no output yet), so fall back to the
# PREVIOUS (terminated, exit-3) container's logs.
# Capture the logs in a variable first: piping kubectl straight into grep -m1 would
# SIGPIPE kubectl and, under pipefail, fail the pipeline despite a successful match.
MARKER_LOGS=$( { kubectl logs "$MARKER_POD" 2>/dev/null; kubectl logs --previous "$MARKER_POD" 2>/dev/null; } || true )
echo "marker-pod logregel:"
printf '%s\n' "$MARKER_LOGS" | grep "MARKER:.*regel 'heffing markert'" | head -1 \
  || { echo "verwachte MARKER-logregel niet gevonden" >&2; exit 1; }

EXIT_CODE=$(kubectl get pod "$MARKER_POD" \
    -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}' 2>/dev/null || true)
[ "$EXIT_CODE" = "3" ] || { echo "verwachtte exit 3, kreeg '$EXIT_CODE'" >&2; exit 1; }
echo "H3: fail-fast exit 3 bevestigd (CrashLoopBackOff by design)"

# Same descriptor/manifest, nu MET --allow-markers → start schoon.
helm upgrade "$MARKER_RELEASE" "$CHART" --set image.tag="$TAG" \
    --set descriptor=/app/rules/marker/marker.dienst.json \
    --set bronnenManifest=/app/rules/marker/manifest.txt \
    --set allowMarkers=true
kubectl rollout status "deploy/$MARKER_RELEASE" --timeout=600s
pf "$MARKER_RELEASE" "$((LOCAL_PORT + 1))"
curl -fsS "http://127.0.0.1:$((LOCAL_PORT + 1))/info" | grep "heffing markert" \
  || { echo "/info noemt 'heffing markert' niet onder --allow-markers" >&2; exit 1; }
echo "H3 PASS: met --allow-markers start de dienst en toont /info de marker"
helm uninstall "$MARKER_RELEASE"

# ---- H4: rollout --------------------------------------------------------------
echo "== H4 =="
OVERLAY=docker/.h4-descriptor.json
# Pinned request: a Vlucht op 2026-07-01 met een persoon geboren 2007-01-01 (19 jr).
read -r -d '' H4_BODY <<'JSON' || true
{"instanties":[{"type":"Vlucht","id":"V1","attributen":{"vluchtdatum":"2026-07-01"}},{"type":"Natuurlijk persoon","id":"P1","attributen":{"geboortedatum":"2007-01-01"},"rollen":{"reis":"V1"}}]}
JSON

# H4a: volwassenleeftijd = 18 → 19-jarige is NIET minderjarig.
python3 - "$OVERLAY" 18 <<'PY'
import json, sys
src = "examples/toka/toka.dienst.json"
overlay, leeftijd = sys.argv[1], int(sys.argv[2])
d = json.load(open(src))
d["parameters"] = {"volwassenleeftijd": leeftijd}
json.dump(d, open(overlay, "w"), ensure_ascii=False, indent=2)
PY
make image TAG="$TAG-h4a" DESCRIPTOR_SRC="$OVERLAY"
kind load docker-image "$IMG_REPO:$TAG-h4a" --name "$CLUSTER"
helm upgrade "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG-h4a"
kubectl rollout status "deploy/$RELEASE" --timeout=600s
pf "$RELEASE" "$LOCAL_PORT"

RESP_A=$(curl -fsS -X POST "http://127.0.0.1:$LOCAL_PORT/kenmerktoekenning-persoon-minderjarig" \
    -H 'Content-Type: application/json' -d "$H4_BODY")
echo "H4a respons (volwassenleeftijd 18): $RESP_A"
# The heredoc IS python's stdin (it would shadow a pipe), so the response rides in via env.
RESP="$RESP_A" VERWACHT=false python3 - <<'PY'
import json, os
pid, verwacht = "P1", os.environ["VERWACHT"] == "true"
resp = json.loads(os.environ["RESP"])
inst = next(i for i in resp["instanties"] if i["id"] == pid)
mind = inst.get("kenmerken", {}).get("minderjarig", False)
assert bool(mind) == verwacht, f"P1 minderjarig={mind}, verwacht {verwacht}"
print(f"H4a OK: P1 minderjarig={mind}")
PY

# H4b: volwassenleeftijd = 21 → 19-jarige IS minderjarig.
python3 - "$OVERLAY" 21 <<'PY'
import json, sys
src = "examples/toka/toka.dienst.json"
overlay, leeftijd = sys.argv[1], int(sys.argv[2])
d = json.load(open(src))
d["parameters"] = {"volwassenleeftijd": leeftijd}
json.dump(d, open(overlay, "w"), ensure_ascii=False, indent=2)
PY
make image TAG="$TAG-h4b" DESCRIPTOR_SRC="$OVERLAY"
kind load docker-image "$IMG_REPO:$TAG-h4b" --name "$CLUSTER"
helm upgrade "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG-h4b"
kubectl rollout status "deploy/$RELEASE" --timeout=600s
pf "$RELEASE" "$LOCAL_PORT"

RESP_B=$(curl -fsS -X POST "http://127.0.0.1:$LOCAL_PORT/kenmerktoekenning-persoon-minderjarig" \
    -H 'Content-Type: application/json' -d "$H4_BODY")
echo "H4b respons (volwassenleeftijd 21): $RESP_B"
RESP="$RESP_B" VERWACHT=true python3 - <<'PY'
import json, os
pid, verwacht = "P1", os.environ["VERWACHT"] == "true"
resp = json.loads(os.environ["RESP"])
inst = next(i for i in resp["instanties"] if i["id"] == pid)
mind = inst.get("kenmerken", {}).get("minderjarig", False)
assert bool(mind) == verwacht, f"P1 minderjarig={mind}, verwacht {verwacht}"
print(f"H4b OK: P1 minderjarig={mind}")
PY

rm -f "$OVERLAY"
# Leave the release on the canonical base image.
helm upgrade "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG"
kubectl rollout status "deploy/$RELEASE" --timeout=600s
echo "H4 PASS: descriptor-overlay wijzigt de beslissing over een rollout heen"

# ---- teardown -----------------------------------------------------------------
if [ "$TEARDOWN" = true ]; then
  echo "== teardown =="
  helm uninstall "$RELEASE" || true
  kind delete cluster --name "$CLUSTER"
fi

echo "=================================================================="
echo "kind-smoke KLAAR — H2 PASS, H3 PASS, H4 PASS"
