#!/usr/bin/env bash
# Poort: de replay van uchars goldens door de JDK-dienst van deze distributie.
#
# De set is die van uchars gate 12 (uchar/scripts/check-drools.sh): elke golden met een casebestand die niet in
# EXPECTED_CHANNEL staat, EXPECTED_PLAYED stuks. Die goldens spelen in uchar door dezelfde facade die de dienst
# gebruikt; hier gaan ze door de dienst zelf: per golden start de JDK-dienst (bin/regelspraak serve, uit de jar die
# de reactor maakt, op een vrije poort) over de bron.rgs van de golden, of zonder bron over het uchar-ir-v1-document,
# met één entrypoint dat alle vormen draagt; elk casebestand wordt een verzoek en het antwoord moet elke gepinde
# verwachting geven (scripts/dienst_replay.py). Zo toont de poort dat verzoekmapping, vuren en antwoord van de dienst
# zeggen wat uchars replay zegt.
#
# Kanalen, zoals gate 12 ze telt:
#   PLAYED   de standaard: elke golden die hier niet staat, moet spelen;
#   EXCLUDE  een vorm die het verzoek of het antwoord van de dienst niet uitdrukt, bij naam in REPLAY_UITGESLOTEN
#            met de reden; een golden die daar staat en toch speelt, of die niet (meer) in de set zit, faalt de
#            poort (verouderd), en een golden die niet speelt en er niet staat, faalt ook;
#   FAIL     een afwijking of een dienst die niet start: faalt altijd.
# EXPECTED_REPLAY_PLAYED pint het aantal spelers (een gelijkheid, zoals gate 12 EXPECTED_PLAYED pint): een nieuwe
# golden die speelt, is voor een lijst die alleen niet-spelers noemt onzichtbaar. Een lege set faalt.
#
# REPLAY_WERKERS (standaard 4) is het aantal diensten dat tegelijk draait.
set -u
cd "$(dirname "$0")/.."

fail() { echo "check-dienst-replay: FAIL — $*" >&2; exit 1; }
command -v java >/dev/null 2>&1 || fail "java ontbreekt"
command -v python3 >/dev/null 2>&1 || fail "python3 ontbreekt"
command -v mvn >/dev/null 2>&1 || fail "mvn ontbreekt"

# Het aantal goldens dat door de dienst speelt. 2026-09-26, uchar d67f5da1: 188 van de 215 die gate 12 speelt; de
# 27 andere staan hieronder, alle met een tijdlijnvorm.
# 2026-09-27, uchar c70dcad6: 189 — de golden jutterswet-jaarvaststelling (parametersets, scalaire
# parameters) speelt door de dienst; de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-27, uchar 43caed71: 190 — de golden jutterswet-vocabulaires (vocabulairezijtafel, geen semantiek)
# speelt door de dienst; de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-28, uchar 68eed2fb: 191 — de golden jutterswet-premierecht (een objecttypenaam met een voorzetsel,
# een feittypenaam met een werkwoord; namen die de frontend eerder afkapte) speelt door de dienst; de 27
# uitsluitingen zijn ongewijzigd.
# 2026-09-28, uchar ea6695a2: 192 — de golden jutterswet-opgave-datum (de opgave-markering op een Datum in dagen;
# een datum berekend uit een andere datum, een initialisatie met datumliteral als laagste default) speelt door de
# dienst; de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-29, uchar 26ca6b0b: 195 — drie nieuwe spelers: jutterswet-wrakmelding (een subtype-instantie in een rol
# die het supertype vult; de rolschrijver van de toolchain zoekt de setter sindsdien langs de klasselijn van de
# waarde), jutterswet-meldingsketen (een voorwaarde op een afgeleide waarde van een genavigeerd object vuurt nu in
# de exec-tak) en anchor/jutterswet-kustwacht (verliet de weigerlijst van gate 12 om dezelfde reden); de 27
# uitsluitingen zijn ongewijzigd.
# 2026-09-30, uchar de809cf7: 197 — twee nieuwe spelers: jutterswet-zinkende-jutter (een kenmerkcontrole onder een
# kwantor leest 'geen' en een meerwoordige naam heel, zodat "alle … geen X hebben" als ontkenning lowert) en
# jutterswet-premiestaffel (een rechtvaardiging van een default die telt, een keten van rollen of een numerieke
# parameter leest, speelt in de exec-tak; de groepen beslissen na hun producenten); de 27 uitsluitingen zijn
# ongewijzigd.
# 2026-09-30, uchar 6f8b9d1f: 198 — één nieuwe speler: jutterswet-sluitdag (de operanden van een
# berekening onder een kwantorvoorwaarde; de exec-tak houdt het onderwerppatroon reactief); de 27
# uitsluitingen zijn ongewijzigd.
# 2026-09-30, uchar 6b7ab8a2: 199 — één nieuwe speler: jutterswet-opgave-subtype (de opgave-markering met
# schrijvers op subtypen: de groep op het supertype wordt heel geërfd); de 27 uitsluitingen zijn ongewijzigd.
EXPECTED_REPLAY_PLAYED=199

# De goldens die gate 12 speelt en de dienst niet: "<golden>|<reden>". Het verzoek van de dienst draagt geen
# tijdlijn: een attribuut- of parameterwaarde als tijdlijn, een kenmerk met perioden, en een scalaire waarde op een
# tijdsafhankelijk attribuut (uchars peildatumconventie legt die in de emmer van de peildatum; de dienst weigert elk
# tijdsafhankelijk attribuut als invoer, NIET_ONDERSTEUND).
REPLAY_UITGESLOTEN=(
  "anchor/jutterswet-borgdeel|tijdlijn als invoerwaarde"
  "anchor/jutterswet-dagentelling|kenmerk met perioden"
  "anchor/jutterswet-dubbelwacht|tijdlijn als invoerwaarde"
  "anchor/jutterswet-nummerregister|tijdlijn als invoerwaarde"
  "anchor/jutterswet-strandregister|tijdlijn als invoerwaarde"
  "jutterswet-bakenmeester|tijdlijn als invoerwaarde"
  "jutterswet-dagentelling-rgs|kenmerk met perioden"
  "jutterswet-erfwacht|tijdlijn als invoerwaarde"
  "jutterswet-gehele-periode-leeg|tijdlijn als invoerwaarde"
  "jutterswet-gehele-periode-leeg-rgs|tijdlijn als invoerwaarde"
  "jutterswet-getijdenwacht|tijdlijn als invoerwaarde"
  "jutterswet-keurlast|tijdlijn als invoerwaarde"
  "jutterswet-keurploeg|tijdlijn als invoerwaarde"
  "jutterswet-muntbewaring|tijdlijn als invoerwaarde"
  "jutterswet-penningjaar|scalaire waarde op een tijdsafhankelijk attribuut (NIET_ONDERSTEUND in de dienst)"
  "jutterswet-ploegpremie|tijdlijn als invoerwaarde"
  "jutterswet-reddingsvloot|tijdlijn als invoerwaarde"
  "jutterswet-seinjaar|kenmerk met perioden"
  "jutterswet-sleepvloot|tijdlijn als invoerwaarde"
  "jutterswet-tegenwacht|tijdlijn als invoerwaarde"
  "jutterswet-tempototaal|scalaire waarde op een tijdsafhankelijk attribuut (NIET_ONDERSTEUND in de dienst)"
  "jutterswet-tempovouw|tijdlijn als invoerwaarde"
  "jutterswet-vast-startpunt|tijdlijn als invoerwaarde"
  "synth-gehelejaarkenmerk|kenmerk met perioden"
  "synth2-clip-cond-over-prorata|tijdlijn als invoerwaarde"
  "synth2-clip-over-prorata|tijdlijn als invoerwaarde"
  "synth2-prorata-breed|tijdlijn als invoerwaarde"
)

uitgesloten() { local e; for e in "${REPLAY_UITGESLOTEN[@]}"; do [ "${e%%|*}" = "$1" ] && return 0; done; return 1; }

# Elke regel een golden en een reden, en geen golden twee keer.
gezien=""
for e in "${REPLAY_UITGESLOTEN[@]}"; do
  g="${e%%|*}"; r="${e#*|}"
  [ -n "$r" ] && [ "$r" != "$e" ] || fail "uitsluiting zonder reden: '$e'"
  case " $gezien " in *" $g "*) fail "'$g' staat twee keer in REPLAY_UITGESLOTEN" ;; esac
  gezien="$gezien $g"
done

# --- de set van gate 12 -------------------------------------------------------------------------------------
DROOLS=uchar/scripts/check-drools.sh
[ -f "$DROOLS" ] || fail "$DROOLS ontbreekt (is de submodule geïnitialiseerd?)"
uchar_played=$(sed -n 's/^EXPECTED_PLAYED=\([0-9][0-9]*\).*/\1/p' "$DROOLS")
[ -n "$uchar_played" ] || fail "geen EXPECTED_PLAYED in $DROOLS"
niet_gespeeld=$(awk '/^EXPECTED_CHANNEL=\(/{f=1;next} f&&/^\)/{f=0} f' "$DROOLS" | sed -n 's/^ *"\([^|]*\)|.*/\1/p' | sort)
[ -n "$niet_gespeeld" ] || fail "geen EXPECTED_CHANNEL-regels gelezen uit $DROOLS"
set_goldens=()
while IFS= read -r ir; do
  d=$(dirname "$ir"); g=${d#uchar/corpus/golden/}
  printf '%s\n' "$niet_gespeeld" | grep -qxF "$g" && continue
  ls "$d"/case*.json >/dev/null 2>&1 || continue
  set_goldens+=("$g")
done < <(find uchar/corpus/golden -name ir.json | sort)
[ ${#set_goldens[@]} -gt 0 ] || fail "lege set: geen golden die gate 12 speelt (een lege set is een faal)"
[ ${#set_goldens[@]} -eq "$uchar_played" ] \
  || fail "${#set_goldens[@]} goldens afgeleid, uchars gate 12 speelt er $uchar_played: de afleiding volgt $DROOLS niet meer"

# --- de dienst ---------------------------------------------------------------------------------------------
# De jar van bin/regelspraak, vers gebouwd vóór de diensten tegelijk starten, zodat geen van hen hem zelf bouwt.
log=$(mktemp); trap 'rm -f "$log"' EXIT
mvn -B -o -q -DskipTests package -pl service -am >"$log" 2>&1 \
  || { tail -20 "$log" >&2; fail "de dienst bouwt niet"; }

begin=$(date +%s)
uitvoer=$(python3 scripts/dienst_replay.py --corpus uchar/corpus/golden \
    --werkers "${REPLAY_WERKERS:-4}" "${set_goldens[@]}") || fail "de replay-driver faalde"
duur=$(( $(date +%s) - begin ))

played=0; excl=0; cases=0
afwijkingen=(); ongenoemd=(); verouderd=()
while IFS=$'\t' read -r g status detail; do
  [ -n "$g" ] || continue
  case "$status" in
    PLAYED)
      uitgesloten "$g" && verouderd+=("$g speelt door de dienst maar staat in REPLAY_UITGESLOTEN")
      played=$((played + 1)); cases=$((cases + detail)) ;;
    EXCLUDE)
      uitgesloten "$g" || ongenoemd+=("$g :: $detail")
      excl=$((excl + 1)) ;;
    *) afwijkingen+=("$g :: $status $detail") ;;
  esac
done <<< "$uitvoer"
for e in "${REPLAY_UITGESLOTEN[@]}"; do
  g="${e%%|*}"
  printf '%s\n' "${set_goldens[@]}" | grep -qxF "$g" || verouderd+=("$g staat in REPLAY_UITGESLOTEN maar niet in de set van gate 12")
done

if [ ${#afwijkingen[@]} -gt 0 ]; then
  echo "check-dienst-replay: FAIL — ${#afwijkingen[@]} golden(s) wijken af of spelen niet:" >&2
  printf '    %s\n' "${afwijkingen[@]}" >&2; exit 1
fi
if [ ${#ongenoemd[@]} -gt 0 ]; then
  echo "check-dienst-replay: FAIL — ${#ongenoemd[@]} golden(s) spelen niet en staan niet in REPLAY_UITGESLOTEN:" >&2
  printf '    %s\n' "${ongenoemd[@]}" >&2; exit 1
fi
if [ ${#verouderd[@]} -gt 0 ]; then
  echo "check-dienst-replay: FAIL — REPLAY_UITGESLOTEN is verouderd:" >&2
  printf '    %s\n' "${verouderd[@]}" >&2; exit 1
fi
[ "$played" -gt 0 ] || fail "geen golden speelde door de dienst (een lege set is een faal)"
[ "$played" -eq "$EXPECTED_REPLAY_PLAYED" ] \
  || fail "$played goldens speelden, EXPECTED_REPLAY_PLAYED pint $EXPECTED_REPLAY_PLAYED (een nieuwe speler: zet het getal bij)"

echo "check-dienst-replay: PASS"
echo "  set van gate 12: ${#set_goldens[@]} goldens (uchars EXPECTED_PLAYED $uchar_played)"
echo "  gespeeld door de dienst: $played goldens, $cases cases (pin $EXPECTED_REPLAY_PLAYED)"
echo "  uitgesloten: $excl, bij naam met reden"
echo "  duur: ${duur}s met ${REPLAY_WERKERS:-4} diensten tegelijk"
