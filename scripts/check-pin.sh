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
#   3. de versies in de ouder-pom wijken af van die van uchar: antlr.version van
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
faal() { echo "FAIL: $*"; status=1; }
kapot() { echo "FAIL: $*"; exit 2; }

# --- 1. submodule geïnitialiseerd en op de vastgelegde commit ------------------
regels=$(git submodule status) || kapot "git submodule status faalt in $(pwd)"
gezien=0
while IFS= read -r regel; do
  [ -n "$regel" ] || continue
  pad=$(printf '%s\n' "$regel" | awk '{print $2}')
  [ "$pad" = "uchar" ] && gezien=1
  case "${regel:0:1}" in
    -) faal "submodule '$pad' is niet geïnitialiseerd; draai: git submodule update --init --recursive" ;;
    +) faal "submodule '$pad' staat niet op de vastgelegde commit: $regel" ;;
    U) faal "submodule '$pad' heeft mergeconflicten: $regel" ;;
  esac
done <<<"$regels"
[ "$gezien" -eq 1 ] || kapot "geen submodule 'uchar' in .gitmodules"

# Zonder eigen .git is uchar/ een lege map van deze repo, en dan zou `git -C uchar`
# de status van deze repo tonen in plaats van die van de submodule.
if [ -e uchar/.git ]; then
  echo "pin: uchar op $(git -C uchar rev-parse HEAD)"
  # --- 2. submodule schoon -------------------------------------------------------
  vuil=$(git -C uchar status --porcelain) || kapot "git -C uchar status faalt"
  if [ -n "$vuil" ]; then
    faal "submodule uchar heeft wijzigingen of onbekende bestanden:"
    printf '%s\n' "$vuil" | sed 's/^/    /'
  fi
else
  faal "uchar/.git ontbreekt, de submodule is niet uitgecheckt"
fi

# --- 3. versies gelijk aan uchar -----------------------------------------------
[ -f uchar/frontends/regelspraak/Makefile ] && [ -f uchar/backends/drools/pom.xml ] \
  || { echo "FAIL: de versiebronnen in uchar/ ontbreken; zie de meldingen hierboven"; exit 1; }

# De eerste <naam>waarde</naam> in een pom.
pomprop() {
  sed -n "s:.*<$2>\([^<]*\)</$2>.*:\1:p" "$1" | head -n 1
}
# De <version> direct na <artifactId>$2</artifactId> in een pom.
pomversie() {
  awk -v a="<artifactId>$2</artifactId>" '
    index($0, a) { zoek = 1; next }
    zoek && /<version>/ { sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit }
  ' "$1"
}
# Een waarde die niet te lezen is, is een fout in dit script of in de bron, geen
# versie om mee te vergelijken. De melding gaat naar stderr, omdat stdout in een
# $(...) wordt opgevangen.
lees() {
  if [ -z "$3" ]; then echo "FAIL: $2 is niet te lezen uit $1" >&2; return 2; fi
  printf '%s' "$3"
}

OUDER=pom.xml
MAKEFILE=uchar/frontends/regelspraak/Makefile
BACKEND=uchar/backends/drools/pom.xml

vergelijk() {
  local eigen=$1 eigenwaarde=$2 bron=$3 bronsleutel=$4 bronwaarde=$5
  if [ "$eigenwaarde" = "$bronwaarde" ]; then
    echo "ok  : $eigen $eigenwaarde = $bronsleutel in $bron"
  else
    faal "$eigen in $OUDER is $eigenwaarde, maar $bronsleutel in $bron is $bronwaarde"
  fi
}

antlr=$(lees "$OUDER" antlr.version "$(pomprop "$OUDER" antlr.version)") || exit 2
drools=$(lees "$OUDER" drools.version "$(pomprop "$OUDER" drools.version)") || exit 2
jackson=$(lees "$OUDER" jackson.version "$(pomprop "$OUDER" jackson.version)") || exit 2
junit=$(lees "$OUDER" junit.version "$(pomprop "$OUDER" junit.version)") || exit 2

u_antlr=$(lees "$MAKEFILE" ANTLR4_VERSION \
  "$(sed -n 's/^ANTLR4_VERSION[[:space:]]*:=[[:space:]]*\([^[:space:]]*\).*/\1/p' "$MAKEFILE" | head -n 1)") || exit 2
u_drools=$(lees "$BACKEND" drools.version "$(pomprop "$BACKEND" drools.version)") || exit 2
u_jackson=$(lees "$BACKEND" jackson.version "$(pomprop "$BACKEND" jackson.version)") || exit 2
u_junit=$(lees "$BACKEND" "de version van junit-jupiter" "$(pomversie "$BACKEND" junit-jupiter)") || exit 2

vergelijk antlr.version "$antlr" "$MAKEFILE" ANTLR4_VERSION "$u_antlr"
vergelijk drools.version "$drools" "$BACKEND" drools.version "$u_drools"
vergelijk jackson.version "$jackson" "$BACKEND" jackson.version "$u_jackson"
vergelijk junit.version "$junit" "$BACKEND" "de version van junit-jupiter" "$u_junit"

if [ "$status" -eq 0 ]; then
  echo "PASS: de submodule uchar is geïnitialiseerd, schoon en op de vastgelegde commit; de versies zijn gelijk"
fi
exit $status
