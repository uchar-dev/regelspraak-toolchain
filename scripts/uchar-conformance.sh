#!/usr/bin/env bash
# The uchar pin: this toolchain consumes the uchar spec repo at the
# revision pinned in SPEC_REV (repo root). Conformance means the
# emitter reproduces every uchar emitter golden byte-identically at
# that revision — byte-identity implies schema-validity and
# verifier-cleanliness, because uchar's own gates pin those on the
# checked-in files. Changes flow spec-first: a delta lands in uchar
# (spec + corpus + schema), then a pin bump lands here with this
# check green. An empty golden set is a failure, never silently
# green.
set -u
cd "$(dirname "$0")/.."

fail() { echo "uchar-conformance: FAIL — $*" >&2; exit 1; }

[ -f SPEC_REV ] || fail "SPEC_REV is missing at the repo root (the pin does not exist)"
SPEC_REV=$(cat SPEC_REV)
[ -n "$SPEC_REV" ] || fail "SPEC_REV is empty"

[ -n "${UCHAR_DIR:-}" ] || fail "set UCHAR_DIR to the uchar checkout to verify against"
[ -d "$UCHAR_DIR/corpus/golden" ] || fail "$UCHAR_DIR does not look like the uchar repo (no corpus/golden)"

HEAD_REV=$(git -C "$UCHAR_DIR" rev-parse HEAD) || fail "cannot resolve HEAD in $UCHAR_DIR"
PIN_REV=$(git -C "$UCHAR_DIR" rev-parse --verify --quiet "$SPEC_REV^{commit}") \
  || fail "pinned SPEC_REV $SPEC_REV is not a commit in $UCHAR_DIR"
[ "$HEAD_REV" = "$PIN_REV" ] \
  || fail "uchar HEAD $HEAD_REV is not the pinned SPEC_REV $PIN_REV — check out the pin (or bump it spec-first)"
DIRTY=$(git -C "$UCHAR_DIR" status --porcelain -- corpus/golden spec)
[ -z "$DIRTY" ] || fail "uchar working tree is dirty under corpus/golden or spec:
$DIRTY"

make resolver >/dev/null 2>&1 || fail "make resolver failed"

pass=0; diffcount=0; emitfail=0
for d in "$UCHAR_DIR"/corpus/golden/*/; do
  [ -f "$d/bron.rgs" ] || continue
  n=$(basename "$d")
  out=$(mktemp -t uchar-conf-XXXXXX.json)
  if ! java -cp lib/antlr-4.13.2-complete.jar:out \
      io.github.yhavinga.regelspraak.resolver.Resolver --ir "$out" "$d/bron.rgs" \
      >/dev/null 2>&1; then
    echo "EMIT FAIL: $n"; emitfail=$((emitfail+1)); rm -f "$out"; continue
  fi
  if cmp -s "$out" "$d/ir.json"; then
    pass=$((pass+1))
  else
    echo "DIFF: $n (emitter output diverges from the pinned golden)"
    diffcount=$((diffcount+1))
  fi
  rm -f "$out"
done

[ $((pass + diffcount + emitfail)) -gt 0 ] || fail "no emitter goldens found under $UCHAR_DIR/corpus/golden (an empty set is a failure)"
if [ $diffcount -gt 0 ] || [ $emitfail -gt 0 ]; then
  fail "$pass identical, $diffcount diverging, $emitfail emit failures at pin $SPEC_REV"
fi
echo "uchar-conformance: PASS — $pass emitter goldens byte-identical at pin $SPEC_REV"
