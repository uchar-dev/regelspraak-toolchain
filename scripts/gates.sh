#!/usr/bin/env bash
# De controle vóór elke landing: de hele reeks poorten lokaal, in vaste volgorde, tot de eerste rode stap.
#
#   1 pin         scripts/check-pin.sh            de submodule op de pin, schoon, de versies van uchar
#   2 reactor     mvn -B -o verify -Dmaven.test.skip.exec=true   uchars modules zonder hun suites; de eigen suites met hun vloeren
#   3 twins  scripts/check-twins.sh     de dialect-tweelingen en de vocabulairetweeling over HTTP
#   4 replay      scripts/check-service-replay.sh  de goldens van uchars gate 12 door de dienst
#   5 manifest    make scenario-test              het gouden TOKA-manifest
#   6 image       make image                      het image van de dienst
#   7 kind-smoke  make kind-smoke                 het image in kind: H2, H3 en H4 tegen de draaiende dienst
#
# Stap 1 tot en met 5 zijn make gates-no-docker; 6 en 7 vragen docker, kind, kubectl en helm. Per stap één regel met de
# uitkomst, de duur en de kern van de uitvoer; de volledige uitvoer staat in out/gates/<nr>-<stap>.log. Een rode stap
# stopt de reeks: de laatste regels van zijn log komen erbij en de eindregel noemt hem. TAG, DESCRIPTOR_SRC en
# CLUSTER uit de omgeving gaan door naar make image en make kind-smoke.
set -u
cd "$(dirname "$0")/.."

LOGS=out/gates
rm -rf "$LOGS"
mkdir -p "$LOGS"
BEGIN=$(date +%s)
NR=0

duration() { local s=$1; if [ "$s" -ge 60 ]; then printf '%dm%02ds' $((s / 60)) $((s % 60)); else printf '%ds' "$s"; fi; }

# De kern van een geslaagde stap, uit zijn log.
core() {
  case "$1" in
    pin)        grep -E '^PASS' "$2" | tail -1 ;;
    reactor)    printf 'testgevallen per module %s\n' \
                  "$(sed -n 's/^\[INFO\] Tests run: \([0-9][0-9]*\), Failures: 0, Errors: 0, Skipped: 0$/\1/p' "$2" | paste -s -d / -)" ;;
    twins) sed -n 's/^check-twins: PASS — //p' "$2" ;;
    replay)     sed -n 's/^  gespeeld door de dienst: //p' "$2" ;;
    manifest)   sed -n 's/^manifest: //p' "$2" ;;
    image)      sed -n 's/.*naming to docker\.io\/library\/\([^ ]*\).*/\1/p' "$2" | tail -1 ;;
    kind-smoke) sed -n 's/^kind-smoke KLAAR — //p' "$2" ;;
  esac
}

# stap <naam> <commando...>
step() {
  local name=$1; shift
  NR=$((NR + 1))
  local log="$LOGS/$NR-$name.log" begin
  begin=$(date +%s)
  "$@" >"$log" 2>&1
  local code=$?
  local t=$(( $(date +%s) - begin ))
  if [ "$code" -eq 0 ]; then
    printf 'gates: %d %-10s GROEN %7s  %s\n' "$NR" "$name" "$(duration "$t")" "$(core "$name" "$log")"
    return
  fi
  printf 'gates: %d %-10s ROOD  %7s  exit %d, uitvoer in %s:\n' "$NR" "$name" "$(duration "$t")" "$code" "$log"
  tail -n 20 "$log" | sed 's/^/      /'
  printf 'gates: ROOD bij stap %d (%s) na %s; de stappen daarna zijn niet gedraaid\n' \
    "$NR" "$name" "$(duration $(( $(date +%s) - BEGIN )))"
  exit 1
}

step pin        bash scripts/check-pin.sh
step reactor    mvn -B -o verify -Dmaven.test.skip.exec=true
step twins    bash scripts/check-twins.sh
step replay     bash scripts/check-service-replay.sh
step manifest   make --no-print-directory scenario-test
step image      make --no-print-directory image
step kind-smoke make --no-print-directory kind-smoke

echo "gates: GROEN — $NR stappen in $(duration $(( $(date +%s) - BEGIN ))); de uitvoer staat in $LOGS/"
