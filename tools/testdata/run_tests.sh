#!/usr/bin/env bash
# run_tests.sh — verify tools/csv_naar_beslistabel.py end-to-end (no pytest needed).
#
#   V1 ROUND-TRIP DIFFERENTIAL (the real gate): render each spec §12 beslistabel
#      example as CSV, convert it back with the tool, splice the tool's Beslistabel
#      block onto the ORIGINAL example's pre-table prefix (its comments + Objecttype
#      declaration), and assert BYTE-EQUAL DRL against the original via the compiled
#      RegelSpraakCli --drl path. Byte-equal DRL proves the tool reproduces the table block
#      equivalently at the transpiler level. The CSV metadata `naam` matches the
#      original beslistabelnaam so no name difference can leak into the DRL.
#   V2 ERROR PATHS: pipe cell / wrong column count / missing header each exit 1 with
#      a row+column message and write NOTHING to the output stream.
#   V3 QUOTING: quoted separators, embedded RegelSpraak literal quotes, and quoted
#      leading/trailing spaces survive verbatim; unquoted edge spaces are trimmed.
#      Pinned against tools/testdata/quoting-probe.expected.rgs.
#
# Requires: python3, a JDK, and `make classpath` already run (the toolchain module built
# and toolchain/target/runtime-classpath.txt written). Run from the repo root or anywhere
# — paths are resolved relative to the repo root.
set -u
cd "$(dirname "$0")/../.." || exit 2
ROOT="$(pwd)"
TOOL="python3 tools/csv_naar_beslistabel.py"
TD="tools/testdata"
CP_FILE="toolchain/target/runtime-classpath.txt"
[ -f "$CP_FILE" ] || { echo "FAIL: $CP_FILE ontbreekt — draai eerst make classpath"; exit 2; }
DRL="java -cp toolchain/target/classes:$(cat "$CP_FILE") dev.uchar.regelspraak.toolchain.cli.RegelSpraakCli --drl"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0; fail=0
ok()   { echo "  PASS: $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL: $1"; fail=$((fail+1)); }

echo "== V1 round-trip differential (byte-equal DRL) =="
# name -> original example .rgs
declare -A ORIG=(
  [woonregio-factor]="examples/beslistabel/spec12-woonregio-factor.rgs"
  [reisduur-band]="examples/beslistabel/spec12-belasting-reisduur-band.rgs"
)
for name in woonregio-factor reisduur-band; do
  orig="${ORIG[$name]}"
  # (b) = original prefix (everything before the Beslistabel line) + tool output.
  awk '/^Beslistabel /{exit} {print}' "$orig" > "$TMP/$name.prefix"
  $TOOL --out "$TMP/$name.frag" "$TD/$name.csv" || { bad "$name tool exit"; continue; }
  # The committed golden fragment must match the freshly-generated one.
  if ! diff -q "$TMP/$name.frag" "$TD/$name.expected.rgs" >/dev/null; then
    bad "$name: tool output drifted from $TD/$name.expected.rgs"; diff "$TD/$name.expected.rgs" "$TMP/$name.frag"; continue
  fi
  cat "$TMP/$name.prefix" "$TMP/$name.frag" > "$TMP/$name.tool.rgs"
  # Both DRL runs must succeed: two refusals print two empty DRLs, and those compare byte-equal too.
  $DRL "$orig"           2>"$TMP/$name.orig.err" > "$TMP/$name.orig.drl" \
    || { bad "$name: --drl op het origineel faalt (exit $?): $(tail -1 "$TMP/$name.orig.err")"; continue; }
  $DRL "$TMP/$name.tool.rgs" 2>"$TMP/$name.tool.err" > "$TMP/$name.tool.drl" \
    || { bad "$name: --drl op de tooluitvoer faalt (exit $?): $(tail -1 "$TMP/$name.tool.err")"; continue; }
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
if diff -q "$TMP/quoting.frag" "$TD/quoting-probe.expected.rgs" >/dev/null; then
  ok "quoting-probe matches expected (quoted sep/quotes/spaces preserved, unquoted trimmed)"
else
  bad "quoting-probe drift"; diff "$TD/quoting-probe.expected.rgs" "$TMP/quoting.frag"
fi

echo "-------------------------------------------------------------"
echo "TOTAL: $pass pass, $fail fail"
[ "$fail" = 0 ]
