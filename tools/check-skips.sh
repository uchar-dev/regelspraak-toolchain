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
# En het sluit de drie manieren waarop een testrun kan KRIMPEN zonder dat iemand
# het ziet — krimp is de stilte die een skiplijst per definitie niet vangt:
#
#   (c) een AFGEBROKEN CONTAINER. Slaat een @BeforeAll-assumptie toe, dan
#       schrijft surefire een rapport met tests="0" en GEEN ENKEL <testcase>:
#       de hele klasse verdwijnt, en een skiplijst die alleen testgevallen leest
#       ziet niets. Elk klasserapport zonder testgeval faalt daarom bij naam.
#       (De junit-console schrijft in dat geval wél een <skipped> per kind, met
#       "parent was skipped"; die kant vangt (b).)
#   (d) een GEKROMPEN SUITE. Per poort staat hieronder een EXPECTED_MIN-vloer.
#   (e) een RAPPORT DAT NIET GELEZEN IS. Het getelde aantal <testcase>-elementen
#       moet gelijk zijn aan het tests="N"-attribuut van het <testsuite>; wijkt
#       het af, dan heeft deze parser iets gemist en faalt de poort daarop in
#       plaats van een te kleine telling voor waar aan te nemen.
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
#
# BEKENDE GRENZEN van de parser, bewust niet gebouwd omdat ze hier niet
# voorkomen en alle drie LUID stuklopen in plaats van stil:
#   - een testmethode met '#' of '|' in haar naam botst met de scheidingstekens
#     van de pinsleutel hieronder;
#   - een CDATA-melding die letterlijk "<testcase " of "</testcase>" bevat
#     verwart de statemachine (en valt dan op (e));
#   - één <testsuite> per bestand; surefire schrijft er één per klasse, de
#     console één per engine, en meer dan één per bestand komt niet voor.
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
ALLOWED_SKIPS=(
)

# ---------------------------------------------------------------------------
# De ondergrens per poort:  <poort>|<vloer>|<reden>|<meetdatum>
#
# Een ONDERGRENS, geen gelijkheid. Groei hoeft geen bump, krimp faalt. Ze vangen wat
# een skiplijst niet kan zien — een suite die stilletjes de helft draait, een
# testbron die uit de build valt, een klasse die als container afbreekt.
#
# GEVOLG, bewust: een GERICHTE run (`mvn test -Dtest=EenKlasse`) zakt per
# definitie door de vloer en wordt rood. Dat is voor een POORTscript juist: de
# poort IS de volledige run. Wie één klasse wil draaien, draait surefire zonder
# de poort — het make-doel en de pom-binding zijn de poort, niet de losse mvn.
# ---------------------------------------------------------------------------
FLOORS=(
"toolchain|120|de toolchain-kern: engine-facade, scenariorunner met uitleg, verbalisator, parameterset-lader en CLI (de vroegere poorten engine en toolchain-kern in één module), met de naamgeving per vocabulaire; gemeten na de kaart dienst-vocabulaires|2026-09-27"
"service|235|de beslisdienst (JDK-adapter): vormscope, MARKER-antwoord, IR-document als bron, rolinvoer zoals uchars replay, playground, de liberale lezer en het antwoord, /info en de OpenAPI per vocabulaire, de scopetoets over regels en defaultsgroepen, een subtypegroep met haar basis, de default die leest; gemeten na de kaart dienst-scope-defaultsgroepen|2026-10-01"
"service-quarkus|43|de Quarkus-adapter met de pariteit van de vocabulaires; gemeten na de kaart dienst-vocabulaires|2026-09-27"
)

# ---------------------------------------------------------------------------

if [ "${1:-}" = "--sentinel" ]; then
  [ $# -eq 2 ] || { echo "skip-pin: gebruik --sentinel <pad>"; exit 2; }
  mkdir -p "$(dirname "$2")" || exit 2
  : > "$2" || exit 2
  exit 0
fi

[ $# -ge 3 ] || { echo "skip-pin: gebruik <poort> <ijkpunt> <rapportmap>..."; exit 2; }
PORT=$1; CALIBRATION_POINT=$2; shift 2

[ -f "$CALIBRATION_POINT" ] \
  || { echo "skip-pin: ijkpunt '$CALIBRATION_POINT' ontbreekt — de stap die het vlak voor de testrun zet, draaide niet"; exit 2; }

# De vloer van deze poort. Een onbekende poortsleutel is een vergissing in de
# aanroep, geen reden om zonder ondergrens door te draaien.
FLOOR=""
for line in ${FLOORS[@]+"${FLOORS[@]}"}; do
  IFS='|' read -r p n _reden _datum <<<"$line"
  [ "$p" = "$PORT" ] && FLOOR=$n
done
[ -n "$FLOOR" ] \
  || { echo "skip-pin: poort '$PORT' heeft geen vloer in VLOEREN (tools/check-skips.sh) — voeg hem toe met reden en meetdatum"; exit 2; }

echo ""
echo "=== skip-pin — poort '$PORT' ==================================================="

# Wijzigingstijd in hele seconden; BSD (macOS) en GNU spellen het verschillend.
if stat -f %m . >/dev/null 2>&1; then mtime() { stat -f %m "$1"; }; else mtime() { stat -c %Y "$1"; }; fi

# Vers = NIET OUDER dan het ijkpunt. Bewust >= en niet bash's -nt: dat vergelijkt
# op hele seconden, dus een rapport dat in dezelfde seconde als het ijkpunt wordt
# weggeschreven zou als "verouderd" wegvallen en de poort spookrood maken.
IJK_T=$(mtime "$CALIBRATION_POINT")
fresh=0; outdated=0; FRESH_REPORTS=()
for map in "$@"; do
  [ -d "$map" ] || { echo "skip-pin: rapportmap '$map' bestaat niet — deze poort schreef geen testrapport"; exit 2; }
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    if [ "$(mtime "$f")" -ge "$IJK_T" ]; then FRESH_REPORTS+=("$f"); fresh=$((fresh + 1)); else outdated=$((outdated + 1)); fi
  done < <(find "$map" -name 'TEST-*.xml' -type f | sort)
done

[ "$fresh" -gt 0 ] \
  || { echo "skip-pin: geen enkel rapport is jonger dan het ijkpunt ($outdated ouder) — deze poort draaide geen tests, of deze controle liep vóór de testrun"; exit 2; }

# De legacy-XML uitlezen. Twee soorten regels op stdout:
#   CASE\t<klasse>\t<methode>\t<SKIP|OK>\t<melding>
#   SUITE\t<bestand>\t<suitenaam>\t<tests-attribuut>\t<getelde testcases>
read_out=$(awk '
  # Attribuut lezen met een verplichte scheiding ervoor, zodat name="" niet
  # binnen classname="" wordt gevonden wanneer classname als eerste staat.
  function attr(regel, naam,   s) {
    if (match(regel, "[ \t]" naam "=\"[^\"]*\"")) {
      s = substr(regel, RSTART + 1, RLENGTH - 1)
      return substr(s, length(naam) + 3, length(s) - length(naam) - 3)
    }
    return ""
  }
  # De melding van een <skipped>: uit message="…" of uit de CDATA erachter.
  # Krijgt ALLEEN het stuk vanaf "<skipped", nooit de hele regel.
  function melding(stuk,   s, p) {
    if (match(stuk, /message="[^"]*"/)) {
      s = substr(stuk, RSTART + 9, RLENGTH - 10)
      if (s != "") return s
    }
    p = index(stuk, "<![CDATA[")
    if (p > 0) s = substr(stuk, p + 9); else { p = index(stuk, ">"); s = (p > 0) ? substr(stuk, p + 1) : "" }
    sub(/\]\]>.*$/, "", s); sub(/<\/skipped>.*$/, "", s)
    gsub(/^[ \t]+|[ \t\r]+$/, "", s)
    return s
  }
  function leeg_af() { if (bestand != "") print "SUITE\t" bestand "\t" suite "\t" tests "\t" geteld }
  function schrijf() { print "CASE\t" klasse "\t" naam "\t" (geskipt ? "SKIP" : "OK") "\t" msg; geteld++ }

  FNR == 1 { leeg_af(); bestand = FILENAME; suite = ""; tests = -1; geteld = 0; open = 0 }

  /<testsuite[ \t]/ { suite = attr($0, "name"); t = attr($0, "tests"); tests = (t == "") ? -1 : t + 0 }

  /<testcase[ \t]/ {
    naam = attr($0, "name"); klasse = attr($0, "classname")
    sub(/\(\)$/, "", naam)                       # de console-schrijver zet er haakjes achter, surefire niet
    geskipt = 0; msg = ""
    staart = substr($0, index($0, "<testcase") + 9)
    p = index(staart, "<skipped")
    if (p > 0) { geskipt = 1; msg = melding(substr(staart, p)) }
    # Sluit het element op DEZE regel? Twee vormen: <testcase …/> zonder kind, of
    # <testcase …>…</testcase> compleet op één regel (de vorm die anders stil wegviel).
    if (index(staart, "</testcase>") > 0) { schrijf(); open = 0 }
    else if (p == 0 && staart ~ /\/>[ \t\r]*$/) { schrijf(); open = 0 }
    else open = 1
    next
  }
  open && /<skipped[ \t\/>]/ { geskipt = 1; msg = melding(substr($0, index($0, "<skipped"))); next }
  open && /<\/testcase>/ { schrijf(); open = 0; next }

  END { leeg_af() }
' "${FRESH_REPORTS[@]}")

all_lines=$(printf '%s\n' "$read_out" | awk -F'\t' '$1 == "CASE" { print $2 "\t" $3 "\t" $4 "\t" $5 }' || true)
suites=$(printf '%s\n' "$read_out" | awk -F'\t' '$1 == "SUITE"' || true)

total=$(printf '%s\n' "$all_lines" | grep -c $'\t' || true)
skipped=$(printf '%s\n' "$all_lines" | awk -F'\t' '$3 == "SKIP"' || true)
count_skips=$([ -n "$skipped" ] && printf '%s\n' "$skipped" | wc -l | tr -d ' ' || echo 0)

echo "rapporten    : $fresh vers, $outdated verouderd (genegeerd: uit een eerdere run)"
echo "testgevallen : $total (vloer $FLOOR), waarvan overgeslagen/afgebroken: $count_skips"

# (e) Heeft de parser elk rapport volledig gelezen? En (c): is er een klasse die
# als container afbrak? Een klasserapport (suitenaam = een Java-klassenaam) hoort
# altijd testgevallen te dragen; een engine-rapport van de console (suitenaam met
# spatie, "JUnit Vintage") mag legitiem leeg zijn.
empty_class_report=(); unread=()
while IFS=$'\t' read -r _merk file suite tests geteld; do
  [ -n "${file:-}" ] || continue
  if [ "$tests" -ge 0 ] && [ "$tests" -ne "$geteld" ]; then
    unread+=("$suite ($file): tests=\"$tests\" maar $geteld testgeval(len) gelezen")
    continue
  fi
  case "$suite" in
    *\ *) : ;;                                                            # "JUnit Vintage": een engine-rapport van de console, leeg is normaal
    *.*)  [ "$geteld" -eq 0 ] && empty_class_report+=("$suite ($file)") ;;
  esac
done <<<"$(printf '%s\n' "$suites" | grep $'\t' || true)"

# De pins van deze poort. Het ${arr[@]+"${arr[@]}"}-idioom staat overal waar een
# array LEEG kan zijn: bash 3.2 (de system-bash op macOS) breekt onder `set -u`
# op "${leeg[@]}", en een poort zonder enkele pin — engine, service,
# service-quarkus — is de normale toestand.
PIN_KEYS=(); PIN_REASONS=(); PIN_DATA=()
for line in ${ALLOWED_SKIPS[@]+"${ALLOWED_SKIPS[@]}"}; do
  IFS='|' read -r p key reason date <<<"$line"
  [ "$p" = "$PORT" ] || continue
  PIN_KEYS+=("$key"); PIN_REASONS+=("$reason"); PIN_DATA+=("$date")
done

pin_index() {
  local i
  for i in "${!PIN_KEYS[@]}"; do [ "${PIN_KEYS[$i]}" = "$1" ] && { echo "$i"; return 0; }; done
  return 1
}

unpinned=(); pinned_occurred=()
if [ "$count_skips" -gt 0 ]; then
  echo ""
  echo "overgeslagen/afgebroken, bij naam:"
  while IFS=$'\t' read -r clazz item kind msg; do
    [ "$kind" = "SKIP" ] || continue
    key="$clazz#$item"
    echo "  $key"
    echo "      melding : ${msg:-(none)}"
    if i=$(pin_index "$key"); then
      echo "      gepind  : ${PIN_REASONS[$i]} (${PIN_DATA[$i]})"
      pinned_occurred+=("$key")
    else
      echo "      gepind  : NEE — deze skip staat in geen enkele pin"
      unpinned+=("$key")
    fi
  done <<<"$skipped"
fi

# Verouderde pin, met de nuance die een GERICHTE run nodig maakt: alleen falen
# wanneer de gepinde KLASSE in deze run een vers rapport heeft en de METHODE
# daarin ontbreekt — dán is de pin zijn reden voorbij. Draaide de klasse in het
# geheel niet mee (`-Dtest=…`, een andere module), dan is dat geen verouderde
# pin maar een partiële run; die wordt gemeld zonder faal. De richting die
# hierdoor openvalt — een hele klasse die VERDWIJNT — wordt gedekt door de
# vloer hierboven: de telling zakt dan.
outdated_pins=(); pins_outside_run=()
classes_in_run=$( { printf '%s\n' "$all_lines" | awk -F'\t' 'NF { print $1 }'; printf '%s\n' "$suites" | awk -F'\t' 'NF { print $3 }'; } | sort -u)
for i in "${!PIN_KEYS[@]}"; do
  key=${PIN_KEYS[$i]}
  clazz=${key%%#*}; item=${key##*#}
  if printf '%s\n' "$all_lines" | awk -F'\t' -v k="$clazz" -v n="$item" '$1 == k && $2 == n { g = 1 } END { exit g ? 0 : 1 }'; then
    continue
  elif printf '%s\n' "$classes_in_run" | grep -qx "$clazz"; then
    outdated_pins+=("$key")
  else
    pins_outside_run+=("$key")
  fi
done

# Gepind maar niet opgetreden: toegestaan, niet verplicht — de voorziening is er en de test draaide.
not_occurred=()
for key in ${PIN_KEYS[@]+"${PIN_KEYS[@]}"}; do
  found=0
  for s in ${pinned_occurred[@]+"${pinned_occurred[@]}"}; do [ "$s" = "$key" ] && found=1; done
  for s in ${outdated_pins[@]+"${outdated_pins[@]}"}; do [ "$s" = "$key" ] && found=1; done
  for s in ${pins_outside_run[@]+"${pins_outside_run[@]}"}; do [ "$s" = "$key" ] && found=1; done
  [ "$found" -eq 0 ] && not_occurred+=("$key")
done
if [ ${#not_occurred[@]} -gt 0 ]; then
  echo ""
  echo "gepind maar NIET overgeslagen (toegestaan, niet verplicht — de test draaide):"
  for key in "${not_occurred[@]}"; do echo "  $key"; done
fi
if [ ${#pins_outside_run[@]} -gt 0 ]; then
  echo ""
  echo "gepinde klasse niet in deze run (partiële run — geen faal, de vloer bewaakt de telling):"
  for key in "${pins_outside_run[@]}"; do echo "  $key"; done
fi

status=0
if [ ${#unread[@]} -gt 0 ]; then
  echo ""
  echo "FAIL: ${#unread[@]} rapport(en) niet volledig gelezen — de telling hieronder is niet te vertrouwen:"
  for r in "${unread[@]}"; do echo "  $r"; done
  echo "  Repareer de parser in tools/check-skips.sh; een te kleine telling mag niet voor waar doorgaan."
  status=1
fi
if [ ${#empty_class_report[@]} -gt 0 ]; then
  echo ""
  echo "FAIL: ${#empty_class_report[@]} testklasse(n) leverden GEEN ENKEL testgeval — de hele klasse viel weg:"
  for r in "${empty_class_report[@]}"; do echo "  $r"; done
  echo "  Dit is de container-abort: een @BeforeAll/@BeforeEach-assumptie of een fout in de klasse-opzet."
  echo "  Een lege testklasse bestaat hier niet, dus dit is nooit de bedoeling."
  status=1
fi
if [ "$total" -lt "$FLOOR" ]; then
  echo ""
  echo "FAIL: $total testgevallen gemeten, vloer is $FLOOR voor poort '$PORT' — de suite is gekrompen."
  echo "  Een gerichte run (-Dtest=…) zakt hier altijd doorheen; de poort is de VOLLEDIGE run."
  echo "  Is de krimp bedoeld, verlaag de vloer in dezelfde commit (VLOEREN in tools/check-skips.sh)."
  status=1
fi
if [ ${#unpinned[@]} -gt 0 ]; then
  echo ""
  echo "FAIL: ${#unpinned[@]} ongepinde skip/abort in poort '$PORT':"
  for key in "${unpinned[@]}"; do echo "  $key"; done
  echo "  Neem de oorzaak weg, of pin hem BEWUST in tools/check-skips.sh (TOEGESTANE_SKIPS: poort, test, reden, datum)."
  status=1
fi
if [ ${#outdated_pins[@]} -gt 0 ]; then
  echo ""
  echo "FAIL: ${#outdated_pins[@]} verouderde pin in poort '$PORT' — de klasse draaide, de gepinde methode bestaat niet meer:"
  for key in "${outdated_pins[@]}"; do
    i=$(pin_index "$key")
    echo "  $key  (gepind: ${PIN_REASONS[$i]}, ${PIN_DATA[$i]})"
  done
  echo "  Haal de regel uit TOEGESTANE_SKIPS in tools/check-skips.sh; een pin mag zijn reden niet overleven."
  status=1
fi

if [ "$status" -eq 0 ]; then
  if [ "$count_skips" -eq 0 ]; then
    echo "PASS: geen enkele skip of abort in $total testgevallen (vloer $FLOOR)"
  else
    echo "PASS: $count_skips overgeslagen, alle gepind; geen ongepinde skip of abort ($total testgevallen, vloer $FLOOR)"
  fi
fi
echo "================================================================================"
exit $status
