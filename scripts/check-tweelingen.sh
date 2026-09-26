#!/usr/bin/env bash
# Poort: de drie dialect-tweelingen uit uchars corpus vuren via HTTP met de verwachtingen van al hun casebestanden.
#
# ww-herleving-inkomen-rgs, jutterswet-uitsluiting-rgs en jutterswet-nestkenmerk-rgs zijn RegelSpraak-tweelingen
# van hand geschreven uchar-goldens in het uitzonderingsdialect: hun basisregels met uitzonderingen worden
# defaultsgroepen (uchars IR-SPEC ch. 7), een vorm die een build zonder de zijtafels van uchars lader stil weglaat.
# Deze poort start per tweeling de JDK-dienst (bin/regelspraak serve, uit de jar die de reactor maakt, op een vrije
# poort) over de bron.rgs van de golden met een handgeschreven descriptor (scripts/tweelingen/), zoals een gebruiker
# de dienst inzet: benoemde entrypoints, een scope die de regels en de defaultsgroep bij naam noemt. Elk
# casebestand wordt een verzoek; het antwoord moet elke gepinde verwachting geven (scripts/dienst_replay.py, dat de
# dienst ook opruimt). Uitkomst per tweeling: PLAYED met het aantal cases, anders faalt de poort.
#
# Vloer: het aantal cases per tweeling is gepind (een gelijkheid): 10 + 4 + 4, gemeten 2026-09-26 op uchar d67f5da1.
# Een tweeling die een case verliest of wint, faalt hier bij naam; een pin-bump die het corpus verandert, zet dit
# getal bewust bij. Een lege set faalt.
set -u
cd "$(dirname "$0")/.."

fail() { echo "check-tweelingen: FAIL — $*" >&2; exit 1; }
command -v java >/dev/null 2>&1 || fail "java ontbreekt"
command -v python3 >/dev/null 2>&1 || fail "python3 ontbreekt"
command -v mvn >/dev/null 2>&1 || fail "mvn ontbreekt"

TWEELINGEN=(
  "ww-herleving-inkomen-rgs|10"
  "jutterswet-uitsluiting-rgs|4"
  "jutterswet-nestkenmerk-rgs|4"
)
EXPECTED_CASES=18   # 2026-09-26: 10 + 4 + 4, de som van de pins hierboven

[ ${#TWEELINGEN[@]} -gt 0 ] || fail "geen tweeling om te spelen (een lege set is een faal)"

# De jar van bin/regelspraak, vers gebouwd vóór de diensten tegelijk starten, zodat geen van hen hem zelf bouwt.
log=$(mktemp); trap 'rm -f "$log"' EXIT
mvn -B -o -q -DskipTests package -pl service -am >"$log" 2>&1 \
  || { tail -20 "$log" >&2; fail "de dienst bouwt niet"; }

goldens=(); descriptors=()
for t in "${TWEELINGEN[@]}"; do
  g="${t%%|*}"
  [ -f "uchar/corpus/golden/$g/bron.rgs" ] || fail "tweeling '$g' heeft geen bron.rgs in uchars corpus"
  goldens+=("$g"); descriptors+=(--descriptor "$g=scripts/tweelingen/$g.dienst.json")
done

uitvoer=$(python3 scripts/dienst_replay.py --corpus uchar/corpus/golden --werkers 3 \
    "${descriptors[@]}" "${goldens[@]}") || fail "de replay-driver faalde"
echo "$uitvoer" | sed 's/^/  /'

totaal=0
for t in "${TWEELINGEN[@]}"; do
  g="${t%%|*}"; pin="${t#*|}"
  regel=$(printf '%s\n' "$uitvoer" | awk -F'\t' -v g="$g" '$1 == g')
  [ -n "$regel" ] || fail "'$g' kreeg geen uitkomst"
  status=$(printf '%s' "$regel" | cut -f2); detail=$(printf '%s' "$regel" | cut -f3)
  [ "$status" = PLAYED ] || fail "'$g' speelt niet door de dienst: $status $detail"
  [ "$detail" -eq "$pin" ] || fail "'$g' speelde $detail cases, gepind zijn er $pin"
  totaal=$((totaal + detail))
done
[ "$totaal" -eq "$EXPECTED_CASES" ] || fail "$totaal cases gespeeld, EXPECTED_CASES pint $EXPECTED_CASES"

echo "check-tweelingen: PASS — ${#TWEELINGEN[@]} tweelingen, $totaal cases via HTTP met de gepinde verwachtingen"
