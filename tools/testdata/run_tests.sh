#!/usr/bin/env bash
# run_tests.sh — verify tools/csv_naar_beslistabel.py end-to-end (no pytest needed).
#
#   V1 ROUND-TRIP DIFFERENTIAL (the real gate): render each spec §12 beslistabel
#      example as CSV, convert it back with the tool, splice the tool's Beslistabel
#      block onto the ORIGINAL example's pre-table prefix (its comments + Objecttype
#      declaration), and assert BYTE-EQUAL DRL against the original via the compiled
#      Resolver --drl path. Byte-equal DRL proves the tool reproduces the table block
#      equivalently at the transpiler level. The CSV metadata `naam` matches the
#      original beslistabelnaam so no name difference can leak into the DRL.
#   V2 ERROR PATHS: pipe cell / wrong column count / missing header each exit 1 with
#      a row+column message and write NOTHING to the output stream.
#   V3 QUOTING: quoted separators, embedded RegelSpraak literal quotes, and quoted
#      leading/trailing spaces survive verbatim; unquoted edge spaces are trimmed.
#      Pinned against tools/testdata/quoting-probe.expected.rs.
#
# Requires: python3, a JDK, and `make compile` already run (out/ present). Run from
# the repo root or anywhere — paths are resolved relative to the repo root.
set -u
cd "$(dirname "$0")/../.." || exit 2
ROOT="$(pwd)"
TOOL="python3 tools/csv_naar_beslistabel.py"
TD="tools/testdata"
JAR="$(ls lib/antlr-*-complete.jar 2>/dev/null | head -1)"
DRL="java -cp $JAR:out io.github.yhavinga.regelspraak.resolver.Resolver --drl --allow-markers"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0
ok()   { echo "  PASS: $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL: $1"; fail=$((fail+1)); }

echo "== V1 round-trip differential (byte-equal DRL) =="
# name -> original example .rs
declare -A ORIG=(
  [woonregio-factor]="examples/beslistabel/spec12-woonregio-factor.rs"
  [reisduur-band]="examples/beslistabel/spec12-belasting-reisduur-band.rs"
)
for name in woonregio-factor reisduur-band; do
  orig="${ORIG[$name]}"
  # (b) = original prefix (everything before the Beslistabel line) + tool output.
  awk '/^Beslistabel /{exit} {print}' "$orig" > "$TMP/$name.prefix"
  $TOOL --out "$TMP/$name.frag" "$TD/$name.csv" || { bad "$name tool exit"; continue; }
  # The committed golden fragment must match the freshly-generated one.
  if ! diff -q "$TMP/$name.frag" "$TD/$name.expected.rs" >/dev/null; then
    bad "$name: tool output drifted from $TD/$name.expected.rs"; diff "$TD/$name.expected.rs" "$TMP/$name.frag"; continue
  fi
  cat "$TMP/$name.prefix" "$TMP/$name.frag" > "$TMP/$name.tool.rs"
  $DRL "$orig"           2>/dev/null > "$TMP/$name.orig.drl"
  $DRL "$TMP/$name.tool.rs" 2>/dev/null > "$TMP/$name.tool.drl"
  if diff -q "$TMP/$name.orig.drl" "$TMP/$name.tool.drl" >/dev/null; then
    ok "$name: BYTE-EQUAL DRL"
  else
    bad "$name: DRL differs"; diff "$TMP/$name.orig.drl" "$TMP/$name.tool.drl"
  fi
done

echo "== V2 error paths (exit 1, row+column, nothing written) =="
check_err() { # <fixture> <grep-pattern> <label>
  local fx="$1" pat="$2" label="$3" out="$TMP/e.out"
  rm -f "$out"
  msg="$($TOOL --out "$out" "$fx" 2>&1 >/dev/null)"; rc=$?
  if [ "$rc" = 1 ] && echo "$msg" | grep -q "$pat" && [ ! -f "$out" ]; then
    ok "$label (exit 1, no output, msg: $msg)"
  else
    bad "$label (exit $rc, out-exists=$([ -f "$out" ] && echo yes || echo no), msg: $msg)"
  fi
}
check_err "$TD/err-pipe.csv"     "rij 3, kolom 2"        "pipe cell"
check_err "$TD/err-colcount.csv" "verkeerd kolomaantal"  "wrong column count"
check_err "$TD/err-noheader.csv" "geen header-rij"       "missing header"
# Empty stdin -> exit 1, nothing on stdout.
out="$TMP/empty.out"; printf '' | $TOOL > "$out" 2>/dev/null; rc=$?
if [ "$rc" = 1 ] && [ ! -s "$out" ]; then ok "empty stdin (exit 1, empty stdout)"; else bad "empty stdin (exit $rc)"; fi

echo "== V3 quoting (verbatim survival, pinned) =="
$TOOL --out "$TMP/quoting.frag" "$TD/quoting-probe.csv" || bad "quoting tool exit"
if diff -q "$TMP/quoting.frag" "$TD/quoting-probe.expected.rs" >/dev/null; then
  ok "quoting-probe matches expected (quoted sep/quotes/spaces preserved, unquoted trimmed)"
else
  bad "quoting-probe drift"; diff "$TD/quoting-probe.expected.rs" "$TMP/quoting.frag"
fi

echo "-------------------------------------------------------------"
echo "TOTAL: $pass pass, $fail fail"
[ "$fail" = 0 ]
