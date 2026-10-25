#!/usr/bin/env bash
# De controle vóór elke landing: de poorten lokaal, in vaste volgorde, tot de eerste rode stap.
#
#   1 pin         scripts/check-pin.sh             de submodule op de pin, schoon, een commit van uchars main,
#                                                  de versies van uchar
#   2 reactor     mvn -B -o verify -Dmaven.test.skip.exec=true
#                                                  uchars modules gebouwd zonder hun suites; de suites van
#                                                  toolchain, service en service-quarkus met hun vloeren
#   3 twins       scripts/check-twins.sh           de dialect-tweelingen en de vocabulairetweeling over HTTP
#   4 replay      scripts/check-service-replay.sh  de goldens van uchars gate 12 door de dienst
#   5 manifest    make scenario-test               het gouden TOKA-manifest
#   6 image       make image                       het image van de dienst
#   7 kind-smoke  make kind-smoke                  het image in kind: H2, H3 en H4 tegen de draaiende dienst
#
# 1 en 2 draaien na elkaar; daarna draaien 3, 4 en 5 tegelijk; na die drie draait 6 en daarna 7. De drie delen geen
# poort en geen bestand: elke dienst neemt een vrije poort, elke stap schrijft zijn eigen log en tijdelijke mappen, en
# geen van hen herschrijft de jar van bin/regelspraak, die de reactor net bouwde (`bin/regelspraak jar` bouwt alleen
# als een bron nieuwer is). Met het argument no-docker draaien alleen 1 tot en met 5 (make gates-no-docker); 6 en 7
# vragen docker, kind, kubectl en helm.
#
# Per stap één regel in de volgorde hierboven, met de uitkomst, de duur en de kern van de uitvoer; de volledige
# uitvoer staat in out/gates/<nr>-<stap>.log. Een rode stap komt met de laatste regels van zijn log; na een rode stap,
# of na 3 tot en met 5 als een van hen rood is, draait niets meer en de eindregel noemt de rode stappen. TAG,
# DESCRIPTOR_SRC en CLUSTER uit de omgeving gaan door naar make image en make kind-smoke.
set -u
cd "$(dirname "$0")/.."

case "${1:-}" in
  '') LAST=7 ;;
  no-docker) LAST=5 ;;
  *) echo "gates: onbekend argument '$1'; zonder argument alle stappen, met no-docker stap 1 tot en met 5" >&2; exit 2 ;;
esac

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
    twins)      sed -n 's/^check-twins: PASS — //p' "$2" ;;
    replay)     sed -n 's/^  gespeeld door de dienst: //p' "$2" ;;
    manifest)   sed -n 's/^manifest: //p' "$2" ;;
    image)      sed -n 's/.*naming to docker\.io\/library\/\([^ ]*\).*/\1/p' "$2" | tail -1 ;;
    kind-smoke) sed -n 's/^kind-smoke KLAAR — //p' "$2" ;;
  esac
}

# De stappen die lopen: per stap het nummer, de naam, de begintijd en het proces.
NRS=(); NAMES=(); BEGINS=(); PIDS=()

# Een stap draait op de achtergrond, en zonder taakbeheer negeert hij dan SIGINT. Een onderbreking stopt daarom hier
# elke lopende stap met al zijn processen (diensten, Maven, docker).
stop_tree() { local child; for child in $(pgrep -P "$1"); do stop_tree "$child"; done; kill "$1" 2>/dev/null; }
interrupted() {
  local pid
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do stop_tree "$pid"; done
  echo "gates: onderbroken" >&2
  exit 130
}
trap interrupted INT TERM

# launch <naam> <commando...>: start een stap.
launch() {
  local name=$1; shift
  NR=$((NR + 1))
  NRS+=("$NR"); NAMES+=("$name"); BEGINS+=("$(date +%s)")
  "$@" >"$LOGS/$NR-$name.log" 2>&1 &
  PIDS+=("$!")
}

# finish: wacht tot elke gestarte stap klaar is en meldt ze in volgorde; is er een rood, dan stopt de reeks. De bash
# van macOS (3.2) kent geen `wait -n`, dus kijkt finish om de 0,2 s welke stap klaar is, voor zijn eindtijd.
finish() {
  local i left=${#PIDS[@]} log t red=''
  local -a ends codes
  while [ "$left" -gt 0 ]; do
    for i in "${!PIDS[@]}"; do
      [ -z "${codes[$i]:-}" ] || continue
      kill -0 "${PIDS[$i]}" 2>/dev/null && continue
      wait "${PIDS[$i]}"; codes[$i]=$?; ends[$i]=$(date +%s); left=$((left - 1))
    done
    [ "$left" -eq 0 ] || sleep 0.2
  done
  for i in "${!PIDS[@]}"; do
    log="$LOGS/${NRS[$i]}-${NAMES[$i]}.log"; t=$((ends[i] - BEGINS[i]))
    if [ "${codes[$i]}" -eq 0 ]; then
      printf 'gates: %d %-10s GROEN %7s  %s\n' "${NRS[$i]}" "${NAMES[$i]}" "$(duration "$t")" "$(core "${NAMES[$i]}" "$log")"
    else
      printf 'gates: %d %-10s ROOD  %7s  exit %d, uitvoer in %s:\n' "${NRS[$i]}" "${NAMES[$i]}" "$(duration "$t")" \
        "${codes[$i]}" "$log"
      tail -n 20 "$log" | sed 's/^/      /'
      red="${red:+$red, }${NRS[$i]} (${NAMES[$i]})"
    fi
  done
  NRS=(); NAMES=(); BEGINS=(); PIDS=()
  if [ -n "$red" ]; then
    printf 'gates: ROOD bij stap %s na %s; de stappen daarna zijn niet gedraaid\n' \
      "$red" "$(duration $(( $(date +%s) - BEGIN )))"
    exit 1
  fi
}

launch pin        bash scripts/check-pin.sh
finish
launch reactor    mvn -B -o verify -Dmaven.test.skip.exec=true
finish
launch twins      bash scripts/check-twins.sh
launch replay     bash scripts/check-service-replay.sh
launch manifest   make --no-print-directory scenario-test
finish
if [ "$LAST" -eq 7 ]; then
  launch image      make --no-print-directory image
  finish
  launch kind-smoke make --no-print-directory kind-smoke
  finish
fi

echo "gates: GROEN — $NR stappen in $(duration $(( $(date +%s) - BEGIN ))); de uitvoer staat in $LOGS/"
