#!/usr/bin/env bash
# Pin-bewaking: de submodule uchar/ is de pin van deze distributie.
#
# Deze repo heeft geen eigen parser, lader, transpiler of runtime; ze bouwt die
# uit uchar, dat als git-submodule in uchar/ staat. De commit waarop de
# submodule staat is daarmee de pin. Dit script faalt wanneer die pin niet is
# wat de build gebruikt:
#
#   1. de submodule is niet geïnitialiseerd, staat niet op de vastgelegde
#      commit, of heeft mergeconflicten (`git submodule status` met -, + of U);
#   2. de submodule heeft wijzigingen of onbekende bestanden
#      (`git -C uchar status --porcelain` niet leeg; bestanden die uchars eigen
#      .gitignore negeert, zoals target/, tellen niet);
#   3. de vastgelegde commit staat niet op uchars main, de origin/main van de
#      submodule (`git -C uchar merge-base --is-ancestor HEAD origin/main`).
#      De reactor draait uchars suites niet: uchar draait ze, met zijn hele
#      gate-set, voordat een commit op zijn main landt. Een pin buiten main is
#      daarom niet getest. Het script haalt niets op; een verouderde origin/main
#      laat het falen met de opdracht om hem op te halen;
#   4. de versies in de ouder-pom wijken af van die van uchar: antlr.version van
#      ANTLR4_VERSION in uchar/frontends/regelspraak/Makefile, drools.version,
#      jackson.version en junit.version van uchar/backends/drools/pom.xml.
#      De ouder-pom pint die versies voor de eigen modules; uchars backend bouwt
#      met zijn eigen pom. Wijken ze af, dan draait de eigen code op een andere
#      versie dan waarmee uchar test.
#
# Exitcode: 0 groen, 1 bij een afwijking, 2 als een bestand of waarde niet te
# lezen is.
set -u

cd "$(dirname "$0")/.."

status=0
fail() { echo "FAIL: $*"; status=1; }
broken() { echo "FAIL: $*"; exit 2; }

# --- 1. submodule geïnitialiseerd en op de vastgelegde commit ------------------
lines=$(git submodule status) || broken "git submodule status faalt in $(pwd)"
seen=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  path=$(printf '%s\n' "$line" | awk '{print $2}')
  [ "$path" = "uchar" ] && seen=1
  case "${line:0:1}" in
    -) fail "submodule '$path' is niet geïnitialiseerd; draai: git submodule update --init --recursive" ;;
    +) fail "submodule '$path' staat niet op de vastgelegde commit: $line" ;;
    U) fail "submodule '$path' heeft mergeconflicten: $line" ;;
  esac
done <<<"$lines"
[ "$seen" -eq 1 ] || broken "geen submodule 'uchar' in .gitmodules"

# Zonder eigen .git is uchar/ een lege map van deze repo, en dan zou `git -C uchar`
# de status van deze repo tonen in plaats van die van de submodule.
if [ -e uchar/.git ]; then
  echo "pin: uchar op $(git -C uchar rev-parse HEAD)"
  # --- 2. submodule schoon -------------------------------------------------------
  dirty=$(git -C uchar status --porcelain) || broken "git -C uchar status faalt"
  if [ -n "$dirty" ]; then
    fail "submodule uchar heeft wijzigingen of onbekende bestanden:"
    printf '%s\n' "$dirty" | sed 's/^/    /'
  fi
  # --- 3. de pin staat op uchars main --------------------------------------------
  pin=$(git -C uchar rev-parse --short HEAD) || broken "git -C uchar rev-parse HEAD faalt"
  if ! main=$(git -C uchar rev-parse -q --verify --short 'origin/main^{commit}'); then
    fail "de submodule kent origin/main niet, dus is niet na te gaan of de pin $pin op uchars main staat;" \
      "haal hem op: git -C uchar fetch origin"
  else
    git -C uchar merge-base --is-ancestor HEAD origin/main
    case $? in
      0) echo "ok  : de pin $pin staat op uchars main (origin/main van de submodule: $main)" ;;
      1) fail "de pin $pin staat niet op uchars main (origin/main van de submodule: $main). Pin een commit die op" \
           "uchars main geland is; staat hij daar wel, dan is origin/main verouderd: git -C uchar fetch origin" ;;
      *) broken "git -C uchar merge-base --is-ancestor HEAD origin/main faalt" ;;
    esac
  fi
else
  fail "uchar/.git ontbreekt, de submodule is niet uitgecheckt"
fi

# --- 4. versies gelijk aan uchar -----------------------------------------------
[ -f uchar/frontends/regelspraak/Makefile ] && [ -f uchar/backends/drools/pom.xml ] \
  || { echo "FAIL: de versiebronnen in uchar/ ontbreken; zie de meldingen hierboven"; exit 1; }

# De eerste <naam>waarde</naam> in een pom.
pomprop() {
  sed -n "s:.*<$2>\([^<]*\)</$2>.*:\1:p" "$1" | head -n 1
}
# De <version> direct na <artifactId>$2</artifactId> in een pom.
pom_version() {
  awk -v a="<artifactId>$2</artifactId>" '
    index($0, a) { zoek = 1; next }
    zoek && /<version>/ { sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit }
  ' "$1"
}
# Een waarde die niet te lezen is, is een fout in dit script of in de bron, geen
# versie om mee te vergelijken. De melding gaat naar stderr, omdat stdout in een
# $(...) wordt opgevangen.
read_property() {
  if [ -z "$3" ]; then echo "FAIL: $2 is niet te lezen uit $1" >&2; return 2; fi
  printf '%s' "$3"
}

PARENT=pom.xml
MAKEFILE=uchar/frontends/regelspraak/Makefile
BACKEND=uchar/backends/drools/pom.xml

compare() {
  local own=$1 own_value=$2 source=$3 source_key=$4 source_value=$5
  if [ "$own_value" = "$source_value" ]; then
    echo "ok  : $own $own_value = $source_key in $source"
  else
    fail "$own in $PARENT is $own_value, maar $source_key in $source is $source_value"
  fi
}

antlr=$(read_property "$PARENT" antlr.version "$(pomprop "$PARENT" antlr.version)") || exit 2
drools=$(read_property "$PARENT" drools.version "$(pomprop "$PARENT" drools.version)") || exit 2
jackson=$(read_property "$PARENT" jackson.version "$(pomprop "$PARENT" jackson.version)") || exit 2
junit=$(read_property "$PARENT" junit.version "$(pomprop "$PARENT" junit.version)") || exit 2

u_antlr=$(read_property "$MAKEFILE" ANTLR4_VERSION \
  "$(sed -n 's/^ANTLR4_VERSION[[:space:]]*:=[[:space:]]*\([^[:space:]]*\).*/\1/p' "$MAKEFILE" | head -n 1)") || exit 2
u_drools=$(read_property "$BACKEND" drools.version "$(pomprop "$BACKEND" drools.version)") || exit 2
u_jackson=$(read_property "$BACKEND" jackson.version "$(pomprop "$BACKEND" jackson.version)") || exit 2
u_junit=$(read_property "$BACKEND" "de version van junit-jupiter" "$(pom_version "$BACKEND" junit-jupiter)") || exit 2

compare antlr.version "$antlr" "$MAKEFILE" ANTLR4_VERSION "$u_antlr"
compare drools.version "$drools" "$BACKEND" drools.version "$u_drools"
compare jackson.version "$jackson" "$BACKEND" jackson.version "$u_jackson"
compare junit.version "$junit" "$BACKEND" "de version van junit-jupiter" "$u_junit"

if [ "$status" -eq 0 ]; then
  echo "PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit, die op uchars main staat; de versies zijn gelijk"
fi
exit $status
