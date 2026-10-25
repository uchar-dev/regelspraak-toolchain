#!/usr/bin/env bash
# HOST-side kind smoke: build the decision-service image, load it into a kind cluster,
# and drive the three gates against the LIVE in-cluster service:
#   H2  cluster oracle  — the golden TOKA scenariotoetsen over HTTP (GoldenOverHttpTest)
#   H3  fail-fast       — a marker-scoping descriptor crashes (exit 3) without
#                         --allow-markers; WITH it the service starts, /info names the
#                         marker and a request to that entry point gets 503
#   H4  rollout         — a rebuilt image with a changed parameter (volwassenleeftijd)
#                         changes the decision, proving descriptor overlay + rollout
#
# One linear gate sequence; --teardown deletes the kind cluster and the two H4 images at the end, a failed run
# keeps them for debugging and says how to delete the cluster. Every kubectl
# and helm call names the cluster's context, so the current context of the caller does
# not decide where the releases land. Needs docker, kind, kubectl, helm, mvn, python3.
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
IMG_REPO=decision-service
TAG=${TAG:-dev}
RELEASE=decision-service
MARKER_RELEASE=decision-service-marker
CHART=helm/decision-service
KCTX=kind-$CLUSTER
OVERLAY=docker/.h4-descriptor.json

cd "$(dirname "$0")/.."

k() { kubectl --context "$KCTX" "$@"; }
h() { helm --kube-context "$KCTX" "$@"; }

# ---- port-forward management --------------------------------------------------
# The forward takes a free local port on 127.0.0.1 and names it (PF_PORT): a fixed port can be
# held by another process on the host, and a forward that then binds only [::1] is not the one
# curl reaches. kubectl runs directly in the background, so PF_PID is kubectl itself.
PF_PID=""; PF_PORT=""; PF_LOG=$(mktemp)
# A failed run skips the teardown on purpose, so the cluster stays for debugging; the
# message at exit says so, because a forgotten cluster keeps costing CPU on the host.
cleanup() {
  local rc=$?
  { [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null; } || true
  rm -f "$OVERLAY" "$PF_LOG"
  if [ "$rc" -ne 0 ] && kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    {
      echo "=================================================================="
      echo "kind-smoke MISLUKT in stap '$STEP' (exit $rc); de oorzaak staat in de uitvoer hierboven."
      echo "De kind cluster '$CLUSTER' is blijven staan om te debuggen: kubectl --context $KCTX get pods -A"
      echo "Een volgende run hergebruikt hem. Ruim hem daarna op, want een draaiende cluster blijft CPU kosten:"
      echo "  kind delete cluster --name $CLUSTER"
    } >&2
  fi
}
trap cleanup EXIT

STEP=start
step() { STEP=$1; echo "== $1 =="; }

# pf <svc>: stop any prior forward, start a fresh one, wait for /info.
pf() {
  local svc="$1" i
  { [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null; } || true
  : > "$PF_LOG"
  kubectl --context "$KCTX" port-forward --address 127.0.0.1 "svc/$svc" :8080 >"$PF_LOG" 2>&1 &
  PF_PID=$!
  for i in $(seq 1 30); do
    PF_PORT=$(sed -n '/^Forwarding from 127\.0\.0\.1:/{s/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> 8080$/\1/p;q;}' "$PF_LOG")
    if [ -n "$PF_PORT" ] && curl -fsS "http://127.0.0.1:$PF_PORT/info" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "port-forward naar $svc kwam niet /info-ready binnen 30s:" >&2
  cat "$PF_LOG" >&2
  return 1
}

# ---- cluster ------------------------------------------------------------------
step "cluster"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "hergebruik bestaande kind cluster '$CLUSTER'"
  k get nodes >/dev/null 2>&1 || {
    echo "kind cluster '$CLUSTER' bestaat maar antwoordt niet (context $KCTX): start zijn node-container" >&2
    echo "(docker start $CLUSTER-control-plane) of verwijder hem (kind delete cluster --name $CLUSTER)" >&2
    exit 1
  }
else
  kind create cluster --name "$CLUSTER"
fi

# ---- build + load -------------------------------------------------------------
step "build + load"
make image TAG="$TAG"
kind load docker-image "$IMG_REPO:$TAG" --name "$CLUSTER"

# ---- install ------------------------------------------------------------------
step "install"
h upgrade --install "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG"
k rollout status "deploy/$RELEASE" --timeout=600s

pf "$RELEASE"
INFO=$(curl -fsS "http://127.0.0.1:$PF_PORT/info")
printf '%s' "$INFO" | python3 -c 'import json, sys
e = json.load(sys.stdin)["entrypoints"]
print("/info:", len(e), "entrypoints,", sum(1 for x in e if x.get("executable")), "uitvoerbaar")'

# ---- H2: cluster oracle -------------------------------------------------------
step "H2"
# The identical golden assertions (GoldenOverHttpTest) against the IN-CLUSTER service via
# -Dservice.url, in the reactor (the service module needs the modules below it). The
# surefire goal runs alone after test-compile: the module's skip-pin step counts full
# suites against a floor, and this is one class. Offline (-o): the deps are in the local repo.
mvn -o -B -pl service -am test-compile surefire:test -Dtest=GoldenOverHttpTest \
    -Dsurefire.failIfNoSpecifiedTests=false -Dservice.url="http://127.0.0.1:$PF_PORT"
echo "H2 PASS: GoldenOverHttpTest tegen de in-cluster dienst (26 over HTTP gereproduceerd, 3 door uchar geweigerd)"

# ---- H3: fail-fast ------------------------------------------------------------
step "H3"
# Install the marker descriptor with allowMarkers=false → the service exits 3 → CrashLoopBackOff.
h upgrade --install "$MARKER_RELEASE" "$CHART" --set image.tag="$TAG" \
    --set descriptor=/app/rules/marker/marker.service.json \
    --set sourcesManifest=/app/rules/marker/manifest.txt

MARKER_POD=""
for i in $(seq 1 60); do
  MARKER_POD=$(k get pods -l "app.kubernetes.io/instance=$MARKER_RELEASE" \
      -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
  [ -n "$MARKER_POD" ] || { sleep 2; continue; }
  reason=$(k get pod "$MARKER_POD" \
      -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}' 2>/dev/null || true)
  restarts=$(k get pod "$MARKER_POD" \
      -o jsonpath='{.status.containerStatuses[0].restartCount}' 2>/dev/null || echo 0)
  if [ "$reason" = "CrashLoopBackOff" ] || [ "${restarts:-0}" -ge 1 ]; then break; fi
  sleep 2
done
[ -n "$MARKER_POD" ] || { echo "marker-pod verscheen niet" >&2; exit 1; }

# The service prints "MARKER: regel '<naam>' in de scope van entrypoint '<ep>' is een marker …".
# The current attempt may still be mid-startup (no output yet), so fall back to the
# PREVIOUS (terminated, exit-3) container's logs.
# Capture the logs in a variable first: piping kubectl straight into grep -m1 would
# SIGPIPE kubectl and, under pipefail, fail the pipeline despite a successful match.
MARKER_LOGS=$( { k logs "$MARKER_POD" 2>/dev/null; k logs --previous "$MARKER_POD" 2>/dev/null; } || true )
echo "marker-pod logregel:"
printf '%s\n' "$MARKER_LOGS" | grep "MARKER:.*regel 'heffing markert'" | head -1 \
  || { echo "verwachte MARKER-logregel niet gevonden" >&2; exit 1; }

EXIT_CODE=$(k get pod "$MARKER_POD" \
    -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}' 2>/dev/null || true)
[ "$EXIT_CODE" = "3" ] || { echo "verwachtte exit 3, kreeg '$EXIT_CODE'" >&2; exit 1; }
echo "H3: fail-fast exit 3 bevestigd (CrashLoopBackOff by design)"

# Same descriptor/manifest, nu MET --allow-markers → start schoon; een verzoek krijgt 503.
h upgrade "$MARKER_RELEASE" "$CHART" --set image.tag="$TAG" \
    --set descriptor=/app/rules/marker/marker.service.json \
    --set sourcesManifest=/app/rules/marker/manifest.txt \
    --set allowMarkers=true
k rollout status "deploy/$MARKER_RELEASE" --timeout=600s
pf "$MARKER_RELEASE"
curl -fsS "http://127.0.0.1:$PF_PORT/info" | grep "heffing markert" \
  || { echo "/info noemt 'heffing markert' niet onder --allow-markers" >&2; exit 1; }
STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PF_PORT/heffing-markert" \
    -H 'Content-Type: application/json' -d '{"instances":[{"type":"Vlucht","id":"V1","attributes":{}}]}')
[ "$STATUS" = 503 ] || { echo "een verzoek aan het marker-entrypoint gaf $STATUS, verwacht 503" >&2; exit 1; }
echo "H3 PASS: met --allow-markers start de dienst, toont /info de marker en krijgt een verzoek 503"
h uninstall "$MARKER_RELEASE"

# ---- H4: rollout --------------------------------------------------------------
step "H4"
# Pinned request: a Vlucht op 2026-07-01 met een persoon geboren 2007-01-01 (19 jr).
read -r -d '' H4_BODY <<'JSON' || true
{"instances":[{"type":"Vlucht","id":"V1","attributes":{"vluchtdatum":"2026-07-01"}},{"type":"Natuurlijk persoon","id":"P1","attributes":{"geboortedatum":"2007-01-01"},"roles":{"reis":"V1"}}]}
JSON

# H4a: volwassenleeftijd = 18 → 19-jarige is NIET minderjarig.
python3 - "$OVERLAY" 18 <<'PY'
import json, sys
src = "examples/toka/toka.service.json"
overlay, age = sys.argv[1], int(sys.argv[2])
d = json.load(open(src))
d["parameters"] = {"volwassenleeftijd": age}
json.dump(d, open(overlay, "w"), ensure_ascii=False, indent=2)
PY
make image TAG="$TAG-h4a" DESCRIPTOR_SRC="$OVERLAY"
kind load docker-image "$IMG_REPO:$TAG-h4a" --name "$CLUSTER"
h upgrade "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG-h4a"
k rollout status "deploy/$RELEASE" --timeout=600s
pf "$RELEASE"

RESP_A=$(curl -fsS -X POST "http://127.0.0.1:$PF_PORT/kenmerktoekenning-persoon-minderjarig" \
    -H 'Content-Type: application/json' -d "$H4_BODY")
echo "H4a respons (volwassenleeftijd 18): $RESP_A"
# The heredoc IS python's stdin (it would shadow a pipe), so the response rides in via env.
RESP="$RESP_A" EXPECTED=false python3 - <<'PY'
import json, os
pid, expected = "P1", os.environ["EXPECTED"] == "true"
resp = json.loads(os.environ["RESP"])
inst = next(i for i in resp["instances"] if i["id"] == pid)
mind = inst.get("characteristics", {}).get("minderjarig", False)
assert bool(mind) == expected, f"P1 minderjarig={mind}, verwacht {expected}"
print(f"H4a OK: P1 minderjarig={mind}")
PY

# H4b: volwassenleeftijd = 21 → 19-jarige IS minderjarig.
python3 - "$OVERLAY" 21 <<'PY'
import json, sys
src = "examples/toka/toka.service.json"
overlay, age = sys.argv[1], int(sys.argv[2])
d = json.load(open(src))
d["parameters"] = {"volwassenleeftijd": age}
json.dump(d, open(overlay, "w"), ensure_ascii=False, indent=2)
PY
make image TAG="$TAG-h4b" DESCRIPTOR_SRC="$OVERLAY"
kind load docker-image "$IMG_REPO:$TAG-h4b" --name "$CLUSTER"
h upgrade "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG-h4b"
k rollout status "deploy/$RELEASE" --timeout=600s
pf "$RELEASE"

RESP_B=$(curl -fsS -X POST "http://127.0.0.1:$PF_PORT/kenmerktoekenning-persoon-minderjarig" \
    -H 'Content-Type: application/json' -d "$H4_BODY")
echo "H4b respons (volwassenleeftijd 21): $RESP_B"
RESP="$RESP_B" EXPECTED=true python3 - <<'PY'
import json, os
pid, expected = "P1", os.environ["EXPECTED"] == "true"
resp = json.loads(os.environ["RESP"])
inst = next(i for i in resp["instances"] if i["id"] == pid)
mind = inst.get("characteristics", {}).get("minderjarig", False)
assert bool(mind) == expected, f"P1 minderjarig={mind}, verwacht {expected}"
print(f"H4b OK: P1 minderjarig={mind}")
PY

rm -f "$OVERLAY"
# Leave the release on the canonical base image.
h upgrade "$RELEASE" "$CHART" -f "$CHART/values-dev.yaml" --set image.tag="$TAG"
k rollout status "deploy/$RELEASE" --timeout=600s
echo "H4 PASS: descriptor-overlay wijzigt de beslissing over een rollout heen"

# ---- teardown -----------------------------------------------------------------
if [ "$TEARDOWN" = true ]; then
  step "teardown"
  h uninstall "$RELEASE" || true
  kind delete cluster --name "$CLUSTER"
  # the two images H4 built for the rollout; the image under $TAG belongs to `make image` and stays
  docker image rm "$IMG_REPO:$TAG-h4a" "$IMG_REPO:$TAG-h4b"
fi

echo "=================================================================="
echo "kind-smoke KLAAR — H2 PASS, H3 PASS, H4 PASS"
