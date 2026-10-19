#!/usr/bin/env bash
# Poort: de drie dialect-tweelingen en de vocabulairetweeling uit uchars corpus vuren via HTTP met de verwachtingen
# van al hun casebestanden.
#
# ww-herleving-inkomen-rgs, jutterswet-uitsluiting-rgs en jutterswet-nestkenmerk-rgs zijn RegelSpraak-tweelingen
# van hand geschreven uchar-goldens in het uitzonderingsdialect: hun basisregels met uitzonderingen worden
# defaultsgroepen (uchars IR-SPEC ch. 7), een vorm die een build zonder de zijtafels van uchars lader stil weglaat.
# Deze poort start per tweeling de JDK-dienst (bin/regelspraak serve, uit de jar die de reactor maakt, op een vrije
# poort) over de source.rgs van de golden met een handgeschreven descriptor (scripts/twins/), zoals een gebruiker
# de dienst inzet: benoemde entrypoints, een scope die de regels en de defaultsgroep bij naam noemt. Elk
# casebestand wordt een verzoek; het antwoord moet elke gepinde verwachting geven (scripts/service_replay.py, dat de
# dienst ook opruimt). Uitkomst per tweeling: PLAYED met het aantal cases, anders faalt de poort.
#
# Vloer: het aantal cases per tweeling is gepind (een gelijkheid): 10 + 4 + 4, gemeten 2026-09-26.
# Een tweeling die een case verliest of wint, faalt hier bij naam; een pin-bump die het corpus verandert, zet dit
# getal bewust bij. Een lege set faalt.
#
# De vocabulairetweeling: jutterswet-vocabulaires (twee vocabulaires, een benaming op elke declaratie) via
# scripts/vocabulary_replay.py met scripts/twins/jutterswet-vocabulaires.service.json. Elke case gaat in de
# canonieke spelling, in die van elk vocabulaire en gemengd, en vraagt zijn antwoord canoniek en in elk vocabulaire;
# daarbij het vastgezette invoervocabulaire, de weigering van een andere spelling daaronder, het standaardvocabulaire
# van een entrypoint en /info. De vertaaltafel is het ir.json van de golden. Gepind: het aantal cases (een gelijkheid,
# 2) en een vloer op het aantal verzoeken. De getuige (scripts/twins/ambiguous-vocabulary.*, eigen materiaal):
# een naam die in twee vocabulaires naar verschillende declaraties wijst, is een 400 die beide lezingen noemt; met het
# invoervocabulaire vastgezet speelt hetzelfde verzoek; een onbekend vocabulaire is een 400 die de gedeclareerde noemt.
set -u
cd "$(dirname "$0")/.."

fail() { echo "check-twins: FAIL — $*" >&2; exit 1; }
command -v java >/dev/null 2>&1 || fail "java ontbreekt"
command -v python3 >/dev/null 2>&1 || fail "python3 ontbreekt"
command -v mvn >/dev/null 2>&1 || fail "mvn ontbreekt"

TWINS=(
  "ww-herleving-inkomen-rgs|10"
  "jutterswet-uitsluiting-rgs|4"
  "jutterswet-nestkenmerk-rgs|4"
)
EXPECTED_CASES=18   # 2026-09-26: 10 + 4 + 4, de som van de pins hierboven

VOCABULARY_TWIN="jutterswet-vocabulaires|2"
# 2026-09-27: 2 cases × (4 spellingen × 3 uitvoervocabulaires + 3 vastgezet + 1 standaard + 1 weigering)
EXPECTED_MIN_VOCABULARY_REQUESTS=34
# 2026-09-27: dubbelzinnig, vastgezet in twee uitvoervocabulaires, onbekend vocabulaire
EXPECTED_MIN_WITNESSES=4

[ ${#TWINS[@]} -gt 0 ] || fail "geen tweeling om te spelen (een lege set is een faal)"

# De jar van bin/regelspraak, vers gebouwd vóór de diensten tegelijk starten, zodat geen van hen hem zelf bouwt.
log=$(mktemp); trap 'rm -f "$log"' EXIT
mvn -B -o -q -DskipTests package -pl service -am >"$log" 2>&1 \
  || { tail -20 "$log" >&2; fail "de dienst bouwt niet"; }

goldens=(); descriptors=()
for t in "${TWINS[@]}"; do
  g="${t%%|*}"
  [ -f "uchar/corpus/golden/$g/source.rgs" ] || fail "tweeling '$g' heeft geen source.rgs in uchars corpus"
  goldens+=("$g"); descriptors+=(--descriptor "$g=scripts/twins/$g.service.json")
done

output=$(python3 scripts/service_replay.py --corpus uchar/corpus/golden --workers 3 \
    "${descriptors[@]}" "${goldens[@]}") || fail "de replay-driver faalde"
echo "$output" | sed 's/^/  /'

total=0
for t in "${TWINS[@]}"; do
  g="${t%%|*}"; pin="${t#*|}"
  line=$(printf '%s\n' "$output" | awk -F'\t' -v g="$g" '$1 == g')
  [ -n "$line" ] || fail "'$g' kreeg geen uitkomst"
  status=$(printf '%s' "$line" | cut -f2); detail=$(printf '%s' "$line" | cut -f3)
  [ "$status" = PLAYED ] || fail "'$g' speelt niet door de dienst: $status $detail"
  [ "$detail" -eq "$pin" ] || fail "'$g' speelde $detail cases, gepind zijn er $pin"
  total=$((total + detail))
done
[ "$total" -eq "$EXPECTED_CASES" ] || fail "$total cases gespeeld, EXPECTED_CASES pint $EXPECTED_CASES"

vg="${VOCABULARY_TWIN%%|*}"; vpin="${VOCABULARY_TWIN#*|}"
[ -f "uchar/corpus/golden/$vg/source.rgs" ] || fail "vocabulairetweeling '$vg' heeft geen source.rgs in uchars corpus"
witness=scripts/twins/ambiguous-vocabulary
output=$(python3 scripts/vocabulary_replay.py --corpus uchar/corpus/golden \
    --golden "$vg=scripts/twins/$vg.service.json" \
    --witness "ambiguous-vocabulary=$witness.rgs,$witness.service.json,$witness.witness.json") \
  || fail "de vocabulaire-driver faalde"
echo "$output" | sed 's/^/  /'
line=$(printf '%s\n' "$output" | awk -F'\t' -v g="$vg" '$1 == g')
[ -n "$line" ] || fail "'$vg' kreeg geen uitkomst"
[ "$(printf '%s' "$line" | cut -f2)" = PLAYED ] || fail "'$vg' speelt niet in elk vocabulaire: $(printf '%s' "$line" | cut -f2-)"
vcases=$(printf '%s' "$line" | cut -f3); vrequests=$(printf '%s' "$line" | cut -f4)
[ "$vcases" -eq "$vpin" ] || fail "'$vg' speelde $vcases cases, gepind zijn er $vpin"
[ "$vrequests" -ge "$EXPECTED_MIN_VOCABULARY_REQUESTS" ] \
  || fail "'$vg' deed $vrequests verzoeken, de vloer EXPECTED_MIN_VOCABULARY_REQUESTS is $EXPECTED_MIN_VOCABULARY_REQUESTS"
line=$(printf '%s\n' "$output" | awk -F'\t' '$1 == "ambiguous-vocabulary"')
[ -n "$line" ] || fail "de getuige kreeg geen uitkomst"
[ "$(printf '%s' "$line" | cut -f2)" = PLAYED ] || fail "de getuige getuigt niet: $(printf '%s' "$line" | cut -f2-)"
witnesses=$(printf '%s' "$line" | cut -f3)
[ "$witnesses" -ge "$EXPECTED_MIN_WITNESSES" ] \
  || fail "de getuige gaf $witnesses getuigenissen, de vloer EXPECTED_MIN_WITNESSES is $EXPECTED_MIN_WITNESSES"

echo "check-twins: PASS — ${#TWINS[@]} tweelingen, $total cases via HTTP met de gepinde verwachtingen;" \
  "vocabulairetweeling $vg: $vcases cases in $vrequests verzoeken; getuige: $witnesses getuigenissen"
