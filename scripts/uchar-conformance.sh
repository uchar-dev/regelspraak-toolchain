#!/usr/bin/env bash
# The uchar pin: this toolchain consumes the uchar spec repo at the
# revision pinned in SPEC_REV (repo root, a full 40-hex commit SHA).
# Conformance means the emitter reproduces every uchar emitter golden
# (a bron.rgs beside its ir.json, found recursively) byte-identically —
# byte-identity implies schema-validity and verifier-cleanliness,
# because uchar's own gates pin those on the checked-in files at the
# pinned (gated) revision; this script trusts, and does not re-run,
# those gates. Pin convention: the uchar checkout may sit AHEAD of the
# pin as long as the pin is an ancestor and the normative content
# (corpus/golden, spec/schema, IR-SPEC, RUNTIME-SEMANTICS) has not
# moved since — content drift demands a spec-first pin bump, backlog
# prose does not. An empty golden set is a failure, never silently
# green.
set -u
cd "$(dirname "$0")/.."

fail() { echo "uchar-conformance: FAIL — $*" >&2; exit 1; }

[ -f SPEC_REV ] || fail "SPEC_REV is missing at the repo root (the pin does not exist)"
SPEC_REV=$(cat SPEC_REV)
case "$SPEC_REV" in
  *[!0-9a-f]* | "") fail "SPEC_REV must be a full 40-hex commit SHA, got '$SPEC_REV'" ;;
esac
[ ${#SPEC_REV} -eq 40 ] || fail "SPEC_REV must be a full 40-hex commit SHA, got '$SPEC_REV'"

[ -n "${UCHAR_DIR:-}" ] || fail "set UCHAR_DIR to the uchar checkout to verify against"
[ -d "$UCHAR_DIR/corpus/golden" ] || fail "$UCHAR_DIR does not look like the uchar repo (no corpus/golden)"

HEAD_REV=$(git -C "$UCHAR_DIR" rev-parse HEAD) || fail "cannot resolve HEAD in $UCHAR_DIR"
git -C "$UCHAR_DIR" rev-parse --verify --quiet "$SPEC_REV^{commit}" >/dev/null \
  || fail "pinned SPEC_REV $SPEC_REV is not a commit in $UCHAR_DIR"
NORMATIVE="corpus/golden spec/schema spec/IR-SPEC.md spec/RUNTIME-SEMANTICS.md"
if [ "$HEAD_REV" != "$SPEC_REV" ]; then
  git -C "$UCHAR_DIR" merge-base --is-ancestor "$SPEC_REV" "$HEAD_REV" \
    || fail "uchar HEAD $HEAD_REV does not descend from the pinned SPEC_REV $SPEC_REV"
  git -C "$UCHAR_DIR" diff --quiet "$SPEC_REV" "$HEAD_REV" -- $NORMATIVE \
    || fail "normative content ($NORMATIVE) moved between pin $SPEC_REV and HEAD $HEAD_REV — bump SPEC_REV spec-first"
fi
DIRTY=$(git -C "$UCHAR_DIR" status --porcelain -- $NORMATIVE)
[ -z "$DIRTY" ] || fail "uchar working tree is dirty under the normative paths:
$DIRTY"

JAR=$(ls lib/antlr-*-complete.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || fail "no ANTLR jar under lib/"
make resolver >/dev/null 2>&1 || fail "make resolver failed"

pass=0; diffcount=0; emitfail=0; missing=0
while IFS= read -r src; do
  d=$(dirname "$src"); n=${d#"$UCHAR_DIR"/corpus/golden/}
  if [ ! -f "$d/ir.json" ]; then
    echo "MISSING GOLDEN: $n has bron.rgs but no ir.json"; missing=$((missing+1)); continue
  fi
  out=$(mktemp -t uchar-conf-XXXXXX.json) || fail "mktemp failed"
  if ! java -cp "$JAR:out" \
      io.github.yhavinga.regelspraak.resolver.Resolver --ir "$out" "$src" \
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
done < <(find "$UCHAR_DIR/corpus/golden" -name bron.rgs | sort)

[ $((pass + diffcount + emitfail + missing)) -gt 0 ] || fail "no emitter goldens found under $UCHAR_DIR/corpus/golden (an empty set is a failure)"
if [ $diffcount -gt 0 ] || [ $emitfail -gt 0 ] || [ $missing -gt 0 ]; then
  fail "$pass identical, $diffcount diverging, $emitfail emit failures, $missing missing goldens at pin $SPEC_REV"
fi
echo "uchar-conformance: PASS — $pass emitter goldens byte-identical at pin $SPEC_REV"
