#!/usr/bin/env bash
# The uchar pin — the toolchain's SPEC_REV drift gate.
#
# Since the dependency inversion (LADDER-EVOLUTIE §6 / Q5) the toolchain
# CONSUMES uchar's frontend as a byte-identical vendored slice under
# vendor/uchar-frontend/, compiled as its only frontend. SPEC_REV (repo root, a
# full 40-hex commit SHA) is the SINGLE pin. This gate proves two things at that
# pin:
#
#   (A) DRIFT: every file under vendor/uchar-frontend/frontends/regelspraak/** is
#       byte-identical to the pinned source blob (git -C <uchar> show
#       $SPEC_REV:frontends/regelspraak/<p>) and the file set matches the pin
#       exactly — no missing, no extra. The comparison is against the pinned
#       COMMIT, never uchar's working tree. Without this a vendored copy is
#       unverified against the pin.
#   (B) EMIT: the (vendored) frontend reproduces every uchar emitter golden (a
#       bron.rgs beside its ir.json, found recursively) byte-identically —
#       byte-identity implies schema-validity and verifier-cleanliness because
#       uchar's own gates pin those on the checked-in files at the gated
#       revision; this script trusts, and does not re-run, those gates.
#
# Pin convention: the uchar checkout may sit AHEAD of the pin as long as the pin
# is an ancestor and the NORMATIVE content (corpus/golden, spec/schema, IR-SPEC,
# RUNTIME-SEMANTICS, AND frontends/regelspraak) has not moved since — content
# drift demands a spec-first pin bump (re-vendor + re-pin together), backlog
# prose does not. An empty golden set is a failure, never silently green.
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
# The frontend joins the normative set: after the inversion a frontend change must
# not slip past the spec-first pin discipline (LADDER-EVOLUTIE §6.1).
NORMATIVE="corpus/golden spec/schema spec/IR-SPEC.md spec/RUNTIME-SEMANTICS.md frontends/regelspraak"
if [ "$HEAD_REV" != "$SPEC_REV" ]; then
  git -C "$UCHAR_DIR" merge-base --is-ancestor "$SPEC_REV" "$HEAD_REV" \
    || fail "uchar HEAD $HEAD_REV does not descend from the pinned SPEC_REV $SPEC_REV"
  git -C "$UCHAR_DIR" diff --quiet "$SPEC_REV" "$HEAD_REV" -- $NORMATIVE \
    || fail "normative content ($NORMATIVE) moved between pin $SPEC_REV and HEAD $HEAD_REV — bump SPEC_REV spec-first"
fi
DIRTY=$(git -C "$UCHAR_DIR" status --porcelain -- $NORMATIVE)
[ -z "$DIRTY" ] || fail "uchar working tree is dirty under the normative paths:
$DIRTY"

# ── (A) DRIFT: the vendored copy is byte-identical to the pinned source ─────────
VENDOR_ROOT="vendor/uchar-frontend"
SLICE="frontends/regelspraak"
[ -d "$VENDOR_ROOT/$SLICE" ] || fail "the vendored frontend is missing at $VENDOR_ROOT/$SLICE"

# The pinned file set (from the COMMIT, not the working tree) and the vendored set.
PIN_LIST=$(git -C "$UCHAR_DIR" ls-tree -r --name-only "$SPEC_REV" -- "$SLICE") \
  || fail "cannot list $SLICE at pin $SPEC_REV in $UCHAR_DIR"
[ -n "$PIN_LIST" ] || fail "the pinned slice $SLICE is empty at $SPEC_REV (nothing to vendor)"
VENDOR_LIST=$( (cd "$VENDOR_ROOT" && find "$SLICE" -type f) | sort )

missing_set=$(comm -23 <(printf '%s\n' "$PIN_LIST" | sort) <(printf '%s\n' "$VENDOR_LIST"))
extra_set=$(comm -13 <(printf '%s\n' "$PIN_LIST" | sort) <(printf '%s\n' "$VENDOR_LIST"))
[ -z "$missing_set" ] || fail "vendored slice is MISSING files present at the pin:
$missing_set"
[ -z "$extra_set" ] || fail "vendored slice has EXTRA files absent from the pin:
$extra_set"

drift=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if ! git -C "$UCHAR_DIR" show "$SPEC_REV:$f" | cmp -s - "$VENDOR_ROOT/$f"; then
    echo "DRIFT: $f (vendored copy diverges from the pinned source)"
    drift=$((drift+1))
  fi
done <<< "$PIN_LIST"
[ $drift -eq 0 ] || fail "$drift vendored file(s) drift from the pinned source at $SPEC_REV — re-vendor from the pin"
nfiles=$(printf '%s\n' "$PIN_LIST" | grep -c .)
echo "uchar-conformance: vendored $SLICE byte-identical to pin $SPEC_REV ($nfiles files)"

# ── (B) EMIT: the vendored frontend reproduces every emitter golden ────────────
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
echo "uchar-conformance: PASS — vendored slice byte-identical + $pass emitter goldens reproduced at pin $SPEC_REV"
