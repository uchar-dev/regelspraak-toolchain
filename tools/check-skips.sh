#!/usr/bin/env bash
# skip-pin: overgeslagen en afgebroken tests LUID maken, en pinnen.
#
# Waarom dit bestaat. Een JUnit-assumptie die niet houdt levert een test die
# overslaat en een poort die groen blijft: de samenvatting meldt "Skipped: 3" of
# "1 tests aborted" zonder één naam en zonder één reden, en een VIJFDE stille
# skip komt er ongemerkt bij. Dit script leest de testrapporten van een poort na
# en doet twee dingen:
#
#   (a) het NOEMT elke skip/abort in de poortuitvoer bij naam, met de melding
#       die de assumptie zelf gaf en met de reden waarvoor hij gepind staat;
#   (b) het toetst ze aan de TOEGESTANE-skiplijst hieronder — het
#       check-noop-survivorpatroon. Een ongepinde skip FAALT de poort bij naam.
#       Een gepinde skip die NIET optreedt is GEEN faal: de lijst zegt "mag
#       overslaan", niet "moet overslaan" — op een machine mét de externe
#       specificatie of mét UCHAR_DIR draaien die tests gewoon. Een gepinde test
#       die niet meer BESTAAT faalt wel: een pin mag zijn reden niet overleven.
#
# Gebruik:
#   tools/check-skips.sh --sentinel <pad>                      (ijkpunt zetten)
#   tools/check-skips.sh <poort> <ijkpunt> <rapportmap>...      (natellen)
#
# Het ijkpunt is een leeg bestand dat vlak VOOR de testrun wordt aangeraakt.
# Rapporten van een eerdere run (Maven ruimt target/surefire-reports niet op
# zonder `clean`) zijn ouder en tellen niet mee; ze worden geteld en gemeld, niet
# stilzwijgend meegenomen. Draaien er helemaal geen verse rapporten, dan is dat
# een failure — dan las dit script een oude run in plaats van deze.
#
# Leest de legacy JUnit-XML die zowel maven-surefire als de
# junit-platform-console-standalone schrijven (die laatste via --reports-dir);
# beide zetten een afgebroken test neer als <skipped> met de assumptiemelding.
set -u

cd "$(dirname "$0")/.."

# ---------------------------------------------------------------------------
# De TOEGESTANE-skiplijst. Eén regel per (poort, test) die mag overslaan:
#
#     <poort>|<klasse>#<methode>|<reden>|<datum>
#
# De reden zegt WAAROM de afwezigheid legitiem is, niet wat de test doet. De
# datum is de dag waarop de pin is gezet. Voeg hier alleen BEWUST iets toe.
# ---------------------------------------------------------------------------
TOEGESTANE_SKIPS=(
"root|io.github.yhavinga.regelspraak.CorpusVuurTest#differential_firing_walk_over_de_corpus|de normatieve v2.3.0-specificatie is extern (© Belastingdienst, niet meegeleverd) en lokaal niet uitgepakt, dus de eruit geëxtraheerde corpusmap examples/spec-main (gitignored) bestaat niet|2026-09-16"
"root|io.github.yhavinga.regelspraak.DrlTest#corpus_drl_bevat_geen_enkele_function_definitie|de normatieve v2.3.0-specificatie is extern (© Belastingdienst, niet meegeleverd) en lokaal niet uitgepakt, dus de eruit geëxtraheerde corpusmap examples/spec-main (gitignored) bestaat niet|2026-09-16"
"root|io.github.yhavinga.regelspraak.UcharLetReplayTest#let_dragende_uchar_goldens_replayen_met_de_uchar_pins|de replay wil een uchar-checkout: zonder -Duchar.dir=<pad> (of UCHAR_DIR) is er geen goldenverzameling om tegen te draaien|2026-09-16"
"jar|io.github.yhavinga.regelspraak.DrlTest#corpus_drl_bevat_geen_enkele_function_definitie|de normatieve v2.3.0-specificatie is extern (© Belastingdienst, niet meegeleverd) en lokaal niet uitgepakt, dus de eruit geëxtraheerde corpusmap examples/spec-main (gitignored) bestaat niet|2026-09-16"
)

# ---------------------------------------------------------------------------

if [ "${1:-}" = "--sentinel" ]; then
  [ $# -eq 2 ] || { echo "skip-pin: gebruik --sentinel <pad>"; exit 2; }
  mkdir -p "$(dirname "$2")" || exit 2
  : > "$2" || exit 2
  exit 0
fi

[ $# -ge 3 ] || { echo "skip-pin: gebruik <poort> <ijkpunt> <rapportmap>..."; exit 2; }
POORT=$1; IJKPUNT=$2; shift 2

[ -f "$IJKPUNT" ] \
  || { echo "skip-pin: ijkpunt '$IJKPUNT' ontbreekt — de stap die het vlak voor de testrun zet, draaide niet"; exit 2; }

echo ""
echo "=== skip-pin — poort '$POORT' ==================================================="

vers=0; verouderd=0; VERSE_RAPPORTEN=()
for map in "$@"; do
  [ -d "$map" ] || { echo "skip-pin: rapportmap '$map' bestaat niet — deze poort schreef geen testrapport"; exit 2; }
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if [ "$f" -nt "$IJKPUNT" ]; then VERSE_RAPPORTEN+=("$f"); vers=$((vers + 1)); else verouderd=$((verouderd + 1)); fi
  done < <(find "$map" -name 'TEST-*.xml' -type f | sort)
done

[ "$vers" -gt 0 ] \
  || { echo "skip-pin: geen enkel rapport is jonger dan het ijkpunt ($verouderd ouder) — deze poort draaide geen tests, of deze controle liep vóór de testrun"; exit 2; }

# De legacy-XML uitlezen: één regel per testgeval,  klasse<TAB>methode<TAB>SKIP|OK<TAB>melding.
# <testcase …> staat in beide schrijvers op één regel; een afgebroken test krijgt
# een <skipped>-kind met de melding in een message-attribuut of in CDATA.
alle_regels=$(awk '
  function attr(regel, naam,   m, s) {
    if (match(regel, naam "=\"[^\"]*\"")) {
      s = substr(regel, RSTART, RLENGTH)
      return substr(s, length(naam) + 3, length(s) - length(naam) - 3)
    }
    return ""
  }
  function melding(regel,   s, p) {
    if (match(regel, /message="[^"]*"/)) {
      s = substr(regel, RSTART + 9, RLENGTH - 10)
      if (s != "") return s
    }
    p = index(regel, "<![CDATA[")
    if (p > 0) s = substr(regel, p + 9); else { p = index(regel, ">"); s = (p > 0) ? substr(regel, p + 1) : "" }
    sub(/\]\]>.*$/, "", s); sub(/<\/skipped>.*$/, "", s)
    gsub(/^[ \t]+|[ \t\r]+$/, "", s)
    return s
  }
  /<testcase[ \t]/ {
    naam = attr($0, "name"); klasse = attr($0, "classname")
    sub(/\(\)$/, "", naam)                       # de console-schrijver zet er haakjes achter, surefire niet
    geskipt = 0; msg = ""
    if ($0 ~ /\/>[ \t\r]*$/) { print klasse "\t" naam "\tOK\t"; open = 0 } else open = 1
    next
  }
  open && /<skipped[ \t\/>]/ { geskipt = 1; msg = melding($0); next }
  open && /<\/testcase>/ {
    print klasse "\t" naam "\t" (geskipt ? "SKIP" : "OK") "\t" msg
    open = 0; geskipt = 0; msg = ""
    next
  }
' "${VERSE_RAPPORTEN[@]}")

totaal=$(printf '%s\n' "$alle_regels" | grep -c $'\t' || true)
[ "$totaal" -gt 0 ] \
  || { echo "skip-pin: verse rapporten zonder één testgeval — een lege testrun is een failure, nooit stil groen"; exit 2; }

overgeslagen=$(printf '%s\n' "$alle_regels" | awk -F'\t' '$3 == "SKIP"' || true)
aantal_skips=$([ -n "$overgeslagen" ] && printf '%s\n' "$overgeslagen" | wc -l | tr -d ' ' || echo 0)

echo "rapporten    : $vers vers, $verouderd verouderd (genegeerd: uit een eerdere run)"
echo "testgevallen : $totaal, waarvan overgeslagen/afgebroken: $aantal_skips"

# De pins van deze poort. Het ${arr[@]+"${arr[@]}"}-idioom staat overal waar een array
# LEEG kan zijn: bash 3.2 (de system-bash op macOS) breekt onder `set -u` op "${leeg[@]}",
# en een poort zonder enkele pin — engine, service, service-quarkus — is de normale toestand.
PIN_SLEUTELS=(); PIN_REDENEN=(); PIN_DATA=()
for regel in ${TOEGESTANE_SKIPS[@]+"${TOEGESTANE_SKIPS[@]}"}; do
  IFS='|' read -r p sleutel reden datum <<<"$regel"
  [ "$p" = "$POORT" ] || continue
  PIN_SLEUTELS+=("$sleutel"); PIN_REDENEN+=("$reden"); PIN_DATA+=("$datum")
done

pin_index() {
  local i
  for i in "${!PIN_SLEUTELS[@]}"; do [ "${PIN_SLEUTELS[$i]}" = "$1" ] && { echo "$i"; return 0; }; done
  return 1
}

ongepind=(); gepind_opgetreden=()
if [ "$aantal_skips" -gt 0 ]; then
  echo ""
  echo "overgeslagen/afgebroken, bij naam:"
  while IFS=$'\t' read -r klasse naam soort msg; do
    [ "$soort" = "SKIP" ] || continue
    sleutel="$klasse#$naam"
    echo "  $sleutel"
    echo "      melding : ${msg:-(geen)}"
    if i=$(pin_index "$sleutel"); then
      echo "      gepind  : ${PIN_REDENEN[$i]} (${PIN_DATA[$i]})"
      gepind_opgetreden+=("$sleutel")
    else
      echo "      gepind  : NEE — deze skip staat in geen enkele pin"
      ongepind+=("$sleutel")
    fi
  done <<<"$overgeslagen"
fi

# Gepinde tests die niet meer bestaan: de pin heeft zijn reden overleefd.
verouderde_pins=()
for i in "${!PIN_SLEUTELS[@]}"; do
  sleutel=${PIN_SLEUTELS[$i]}
  klasse=${sleutel%%#*}; naam=${sleutel##*#}
  printf '%s\n' "$alle_regels" | awk -F'\t' -v k="$klasse" -v n="$naam" '$1 == k && $2 == n { gevonden = 1 } END { exit gevonden ? 0 : 1 }' \
    || verouderde_pins+=("$sleutel")
done

# Gepind maar niet opgetreden: toegestaan, niet verplicht — de voorziening is er en de test draaide.
niet_opgetreden=()
for sleutel in ${PIN_SLEUTELS[@]+"${PIN_SLEUTELS[@]}"}; do
  gevonden=0
  for s in ${gepind_opgetreden[@]+"${gepind_opgetreden[@]}"}; do [ "$s" = "$sleutel" ] && gevonden=1; done
  for s in ${verouderde_pins[@]+"${verouderde_pins[@]}"}; do [ "$s" = "$sleutel" ] && gevonden=1; done
  [ "$gevonden" -eq 0 ] && niet_opgetreden+=("$sleutel")
done
if [ ${#niet_opgetreden[@]} -gt 0 ]; then
  echo ""
  echo "gepind maar NIET overgeslagen (toegestaan, niet verplicht — de test draaide):"
  for sleutel in "${niet_opgetreden[@]}"; do echo "  $sleutel"; done
fi

status=0
if [ ${#ongepind[@]} -gt 0 ]; then
  echo ""
  echo "FAIL: ${#ongepind[@]} ongepinde skip/abort in poort '$POORT':"
  for sleutel in "${ongepind[@]}"; do echo "  $sleutel"; done
  echo "  Neem de oorzaak weg, of pin hem BEWUST in tools/check-skips.sh (TOEGESTANE_SKIPS: poort, test, reden, datum)."
  status=1
fi
if [ ${#verouderde_pins[@]} -gt 0 ]; then
  echo ""
  echo "FAIL: ${#verouderde_pins[@]} verouderde pin in poort '$POORT' — de gepinde test bestaat daar niet meer:"
  for sleutel in "${verouderde_pins[@]}"; do
    i=$(pin_index "$sleutel")
    echo "  $sleutel  (gepind: ${PIN_REDENEN[$i]}, ${PIN_DATA[$i]})"
  done
  echo "  Haal de regel uit TOEGESTANE_SKIPS in tools/check-skips.sh; een pin mag zijn reden niet overleven."
  status=1
fi

if [ "$status" -eq 0 ]; then
  if [ "$aantal_skips" -eq 0 ]; then
    echo "PASS: geen enkele skip of abort in $totaal testgevallen"
  else
    echo "PASS: $aantal_skips overgeslagen, alle gepind; geen ongepinde skip of abort"
  fi
fi
echo "================================================================================"
exit $status
