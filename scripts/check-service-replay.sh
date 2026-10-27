#!/usr/bin/env bash
# Poort: de replay van uchars goldens door de JDK-dienst van deze distributie.
#
# De set is die van uchars gate 12 (uchar/scripts/check-drools.sh): elke golden met een casebestand die niet in
# EXPECTED_CHANNEL staat, EXPECTED_PLAYED stuks. Die goldens spelen in uchar door dezelfde facade die de dienst
# gebruikt; hier gaan ze door de dienst zelf: per golden start de JDK-dienst (bin/regelspraak serve, uit de jar die
# de reactor maakt, op een vrije poort) over de source.rgs van de golden, of zonder bron over het uchar-ir-v1-document,
# met één entrypoint dat alle vormen draagt; elk casebestand wordt een verzoek en het antwoord moet elke gepinde
# verwachting geven (scripts/service_replay.py). Zo toont de poort dat verzoekmapping, vuren en antwoord van de dienst
# zeggen wat uchars replay zegt.
#
# Kanalen, zoals gate 12 ze telt:
#   PLAYED   de standaard: elke golden die hier niet staat, moet spelen;
#   EXCLUDE  een vorm die het verzoek of het antwoord van de dienst niet uitdrukt, bij naam in REPLAY_EXCLUDED
#            met de reden; een golden die daar staat en toch speelt, of die niet (meer) in de set zit, faalt de
#            poort (verouderd), en een golden die niet speelt en er niet staat, faalt ook;
#   FAIL     een afwijking of een dienst die niet start: faalt altijd.
# EXPECTED_REPLAY_PLAYED pint het aantal spelers (een gelijkheid, zoals gate 12 EXPECTED_PLAYED pint): een nieuwe
# golden die speelt, is voor een lijst die alleen niet-spelers noemt onzichtbaar. Een lege set faalt.
#
# REPLAY_WORKERS (standaard 4) is het aantal diensten dat tegelijk draait.
set -u
cd "$(dirname "$0")/.."

fail() { echo "check-service-replay: FAIL — $*" >&2; exit 1; }
command -v java >/dev/null 2>&1 || fail "java ontbreekt"
command -v python3 >/dev/null 2>&1 || fail "python3 ontbreekt"

# Het aantal goldens dat door de dienst speelt. 2026-09-26: 188 van de 215 die gate 12 speelt; de
# 27 andere staan hieronder, alle met een tijdlijnvorm.
# 2026-09-27: 189 — de golden jutterswet-jaarvaststelling (parametersets, scalaire
# parameters) speelt door de dienst; de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-27: 190 — de golden jutterswet-vocabulaires (vocabulairezijtafel, geen semantiek)
# speelt door de dienst; de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-28: 191 — de golden jutterswet-premierecht (een objecttypenaam met een voorzetsel,
# een feittypenaam met een werkwoord; namen die de frontend eerder afkapte) speelt door de dienst; de 27
# uitsluitingen zijn ongewijzigd.
# 2026-09-28: 192 — de golden jutterswet-opgave-datum (de opgave-markering op een Datum in dagen;
# een datum berekend uit een andere datum, een initialisatie met datumliteral als laagste default) speelt door de
# dienst; de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-29: 195 — drie nieuwe spelers: jutterswet-wrakmelding (een subtype-instantie in een rol
# die het supertype vult; de rolschrijver van de toolchain zoekt de setter sindsdien langs de klasselijn van de
# waarde), jutterswet-meldingsketen (een voorwaarde op een afgeleide waarde van een genavigeerd object vuurt nu in
# de exec-tak) en anchor/jutterswet-kustwacht (verliet de weigerlijst van gate 12 om dezelfde reden); de 27
# uitsluitingen zijn ongewijzigd.
# 2026-09-30: 197 — twee nieuwe spelers: jutterswet-zinkende-jutter (een kenmerkcontrole onder een
# kwantor leest 'geen' en een meerwoordige naam heel, zodat "alle … geen X hebben" als ontkenning lowert) en
# jutterswet-premiestaffel (een rechtvaardiging van een default die telt, een keten van rollen of een numerieke
# parameter leest, speelt in de exec-tak; de groepen beslissen na hun producenten); de 27 uitsluitingen zijn
# ongewijzigd.
# 2026-09-30: 198 — één nieuwe speler: jutterswet-sluitdag (de operanden van een
# berekening onder een kwantorvoorwaarde; de exec-tak houdt het onderwerppatroon reactief); de 27
# uitsluitingen zijn ongewijzigd.
# 2026-09-30: 199 — één nieuwe speler: jutterswet-opgave-subtype (de opgave-markering met
# schrijvers op subtypen: de groep op het supertype wordt heel geërfd); de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-30: 202 — drie nieuwe spelers: jutterswet-vroegste-vondst (nieuw), jutterswet-vondstvenster
# en jutterswet-vondstbereik (een vouw over datums langs een rol speelt in de exec-tak als accumulate over een
# Comparable; een vouw in een berekening wordt een eigen accumulate); de 27 uitsluitingen zijn ongewijzigd.
# 2026-09-30: 203 — één nieuwe speler: jutterswet-zwaarste-vondst (een vouw met een subselectie
# op een later afgeleide waarde herziet in de exec-tak); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-01: 204 — één nieuwe speler: jutterswet-sluitdag-gemeld (een vouw als consequentie van
# een default speelt in de exec-tak als accumulate op de reduce-regel); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-01: 205 — één nieuwe speler: jutterswet-havenketen (een voorwaarde die via een keten
# van rollen leest krijgt in de exec-tak een join per object onderweg); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-01: 206 — één nieuwe speler: jutterswet-afrekening (een vouw in de toekenning van een
# objectcreatie speelt in de exec-tak als accumulate, zoals in een regelwaarde); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-01: 207 — één nieuwe speler: jutterswet-melding-naar-aard (twee voorwaardelijke
# schrijvers die elkaar uitsluiten spelen; twee die beide gelden falen nu in alle vier uitvoerders benoemd, rode
# case jutterswet-dubbele-melding); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-01: 208 — één nieuwe speler: jutterswet-meldtermijn-strandjutter (een Uitzondering op of
# Afwijking van voor een subtype onder de basisregel van het supertype lowert als eigen uitzondering van het
# subtype, groep-default op het subtype, ch. 7.9/7.10); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-01: 209 — één nieuwe speler: jutterswet-late-vondst (een vouwfilter dat via een rol van
# het element een later afgeleid lid leest, joint in de exec-tak het bereikte object en wordt na die schrijving
# opnieuw getoetst; de telling-vorm ernaast); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-02: 212 — drie nieuwe spelers uit de frontend-ronde G4+G5: jutterswet-havengeld (het
# percentage van een genavigeerde grondslag), jutterswet-zwaarste-weging (een vouw over een verzameling gevolgd
# door to-one stappen, via een gegenereerd lid) en jutterswet-opgave-bedrag (de opgave-markering op een bedrag met
# één eenheid); de 27 uitsluitingen zijn ongewijzigd.
# 2026-10-06: 213 — één nieuwe speler: jutterswet-premiepot (een objectcreatie telt de leden van een
# rol, een feitcreatie koppelt die bestaande objecten aan het gemaakte object, een rolcheck leest de koppeling; de
# verifier weigerde zo'n document eerder onder WF-045 door de populatieproxy van de feitcreatie); de 27
# uitsluitingen zijn ongewijzigd.
# 2026-10-12 (kaart sessie-via-de-ingang-van-uchar): 222 — de dienst vult, vuurt en leest via
# uchars ingang, en de set van gate 12 groeit met de exec-model-tak op truth maintenance van 240 naar 250 goldens.
# Negen nieuwe spelers: anchor/jutterswet-erflijn, anchor/jutterswet-penninguitgifte, anchor/jutterswet-vindersloon,
# jutterswet-afdrachtstapel, jutterswet-vondsttarief en synth2-afrondingscombinaties (de vorige tak weigerde een
# vorm van elk), en jutterswet-budgetgrens-op, jutterswet-filterwacht en jutterswet-penningbesluit (nieuw in uchars
# corpus). jutterswet-klipwacht komt ook in de set, maar speelt niet door de dienst (een tijdlijn als invoerwaarde,
# hieronder); de 27 andere uitsluitingen zijn ongewijzigd.
# 2026-10-14 (kaart pin-naar-de-drl-teksttak-op-truth-maintenance): 225 — gate 12 heeft één lijst
# niet-spelers en één pin EXPECTED_PLAYED voor beide takken (de DRL-pin en de DRL-lijst vervallen), en de set groeit
# van 250 naar 255 met vijf goldens die uchars kaart drl-teksttak-op-truth-maintenance schreef. Drie nieuwe spelers:
# jutterswet-verdeling-dubbelbezet, jutterswet-uniciteit-drie-bronnen en jutterswet-uitsluiting-gedateerd.
# jutterswet-aantalfilter-gehele-jaar en jutterswet-aantalfilter-gehele-jaar-creatieguard komen ook in de set, maar
# spelen niet door de dienst (een kenmerk met perioden, hieronder); de 28 andere uitsluitingen zijn ongewijzigd.
# 2026-10-19, uchar's card `zero-division-channel` (pin-naming-fixes): 230 — de set van gate 12 groeit van 255 naar 260 met de vijf goldens
# van uchars kaart zero-division-channel die Drools speelt (division-by-numeric-zero, division-abs-by-numeric-zero,
# filled-count-unguarded-division, at-most-reads-until-settled en fold-filter-divides-by-zero); alle vijf spelen ze
# door de dienst. De zesde, closed-runtime-division-by-zero, vouwt over een lijstparameter en staat niet in de set.
# 2026-10-20, uchar's card `frontend-kwantor-over-een-pad` (pin-quantifier-path): 231 — de set van gate 12 groeit van
# 260 naar 261 met jutterswet-dringende-melding, een kwantor over een pad in vijf vormen; zij speelt ook door de dienst.
# 2026-10-21, uchar's card `fold-contributor-verdicts` (pin 4f283d9d): 232 — de set van gate 12 groeit van 261 naar 262
# met jutterswet-keurgemiddelde, een kwantorvouw die stopt vóór een delend lid; zij speelt ook door de dienst.
# 2026-10-21, uchar's cards `clingo-day-kind-walk-after-fold-stop` en `default-statute-annotations` (pin 680e5263):
# 234 — de set van gate 12 groeit van 262 naar 264 met jutterswet-keurtermijn, een kwantorvouw die stopt vóór een lid
# waarvan de dagsoortwandeling faalt, en jutterswet-tweeledige-grondslag, een defaults-blok met twee
# `// Bron:`-annotaties; beide spelen ook door de dienst.
# 2026-10-22, uchar's cards `date-with-in-ref-and-clingo`, `clingo-nested-aggregate-in-fold-filter`,
# `fold-in-element-condition` en `rule-variable-in-subselection` (pin 5d8a06de): 238 — de set van gate 12 groeit van
# 264 naar 268 met date-with-parts (de datumconstructie), fold-filter-nested-aggregate, fold-filter-inner-fold-via-
# element-role en jutterswet-schone-maand-na-zware-klacht (een vouw in het filter van een andere vouw); alle vier
# spelen ook door de dienst.
# 2026-10-22, uchar's cards `cycle-across-instances`, `third-condition-level` en zijn fixronde (pin 88c4624b): 241 —
# de set van gate 12 groeit van 268 naar 271 met jutterswet-vergunningketen (de instantiegeordende kring, een waarde
# die dezelfde waarde van haar voorganger leest; uit het ir.json), jutterswet-vrijstelling-drie-niveaus (drie niveaus
# van geneste voorwaarden) en jutterswet-premietabel-twee-ofcellen (een beslistabelrij met twee of-cellen); alle drie
# spelen ook door de dienst.
# 2026-10-22, uchar's cards `instance-cycle-household-reads`, `check-admits-instance-cycle` en de fix
# clingo-filter-guard-binding (pin 0866c03f): 243 — de set van gate 12 groeit van 271 naar 273 met
# jutterswet-vergunningtermijnen (lezingen binnen het huishouden van een object op de instantiegeordende kring) en
# jutterswet-vroege-vondsten-zonder-schorsing (een datumvergelijking in het filter van een vouw onder een ontkend
# kenmerk); beide spelen ook door de dienst. jutterswet-vergunningketen speelt sindsdien uit haar source.rgs.
# 2026-10-22, uchar's kaartloze fix instance-order-open-points (pin a6eed482): 244 — de set van gate 12 groeit van 273
# naar 274 met jutterswet-termijnoverdracht (een kenmerk van een satelliet dat haar eigenaar ontkend leest op de
# instantiegeordende kring); zij speelt ook door de dienst.
# 2026-10-24, uchar's kaart subselection-reads-subject en de snippetfix daarna (pin 0321f96c): 249 — de set van gate 12
# groeit van 274 naar 279 met de goldens van het vormanker (jutterswet-registervoorrang, fold-filter-subject-two-spellings,
# anchor-once-per-member, anchor-forms, anchor-hub-own-hops); alle vijf spelen ook door de dienst, met uitleg.
EXPECTED_REPLAY_PLAYED=249

# De goldens die gate 12 speelt en de dienst niet: "<golden>|<reden>". Het verzoek van de dienst draagt geen
# tijdlijn: een attribuut- of parameterwaarde als tijdlijn, een kenmerk met perioden, en een scalaire waarde op een
# tijdsafhankelijk attribuut (uchars peildatumconventie legt die in de emmer van de peildatum; de dienst weigert elk
# tijdsafhankelijk attribuut als invoer, UNSUPPORTED).
REPLAY_EXCLUDED=(
  "anchor/jutterswet-borgdeel|tijdlijn als invoerwaarde"
  "anchor/jutterswet-dagentelling|kenmerk met perioden"
  "anchor/jutterswet-dubbelwacht|tijdlijn als invoerwaarde"
  "anchor/jutterswet-nummerregister|tijdlijn als invoerwaarde"
  "anchor/jutterswet-strandregister|tijdlijn als invoerwaarde"
  "jutterswet-aantalfilter-gehele-jaar|kenmerk met perioden"
  "jutterswet-aantalfilter-gehele-jaar-creatieguard|kenmerk met perioden"
  "jutterswet-bakenmeester|tijdlijn als invoerwaarde"
  "jutterswet-dagentelling-rgs|kenmerk met perioden"
  "jutterswet-erfwacht|tijdlijn als invoerwaarde"
  "jutterswet-gehele-periode-leeg|tijdlijn als invoerwaarde"
  "jutterswet-gehele-periode-leeg-rgs|tijdlijn als invoerwaarde"
  "jutterswet-getijdenwacht|tijdlijn als invoerwaarde"
  "jutterswet-keurlast|tijdlijn als invoerwaarde"
  "jutterswet-keurploeg|tijdlijn als invoerwaarde"
  "jutterswet-klipwacht|tijdlijn als invoerwaarde"
  "jutterswet-muntbewaring|tijdlijn als invoerwaarde"
  "jutterswet-penningjaar|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-ploegpremie|tijdlijn als invoerwaarde"
  "jutterswet-reddingsvloot|tijdlijn als invoerwaarde"
  "jutterswet-seinjaar|kenmerk met perioden"
  "jutterswet-sleepvloot|tijdlijn als invoerwaarde"
  "jutterswet-tegenwacht|tijdlijn als invoerwaarde"
  "jutterswet-tempototaal|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-tempovouw|tijdlijn als invoerwaarde"
  "jutterswet-vast-startpunt|tijdlijn als invoerwaarde"
  "synth-gehelejaarkenmerk|kenmerk met perioden"
  "synth2-clip-cond-over-prorata|tijdlijn als invoerwaarde"
  "synth2-clip-over-prorata|tijdlijn als invoerwaarde"
  "synth2-prorata-breed|tijdlijn als invoerwaarde"
)

# Kaart explanation-from-readings: de leg van de uitleg. Elk verzoek gaat ook met ?explanation=true; het antwoord
# buiten de uitleg moet gelijk zijn, de uitleg mag "niet is vast te stellen" niet meer noemen, want de motor draagt elke
# voorwaarde die de uitleg verwoordt (zij leest de lezingen van de build met lezingen). Sinds de kaart explanation-parts
# vraagt de leg ook &explanationParts=true: per zin moeten de teksten van de bladeren van haar stukken achter elkaar de
# zin zijn, teken voor teken, met de grondslag als laatste stuk (service_replay.py faalt de golden anders). Een golden waarvoor dat toch
# terugkomt, staat hieronder met de reden ("<golden>|<reden>"); de lijst faalt in beide richtingen op veroudering.
EXPLANATION_UNDETERMINED=(
)
# Het aantal uitgelegde voorwaarden (de voorkomens van "omdat " en "want " in de uitleg) over de gespeelde goldens: een
# ondergrens, gemeten 2026-10-21 bij de kaart explanation-from-readings (1092 zinnen, 1241 voorwaarden over 231 goldens
# en 610 cases); een leg die stilletjes minder uitlegt, faalt. Het antwoord met uitleg moet dat zonder zijn, ook in de
# volgorde van de inconsistenties: het fixpunt geeft ze sinds uchar fixpoint-inconsistency-order in een vaste volgorde.
EXPECTED_MIN_EXPLAINED_CONDITIONS=1241
# Kaart explanation-computed-attributes: het aantal zinnen over een waarde die een gewone regel schreef, met haar
# berekening ("<onderwerp> heeft als ...", vorm A van het advies) over de gespeelde goldens: een ondergrens, gemeten
# 2026-10-22 bij die kaart (pin 88c4624b: 3443 zinnen, waarvan 1852 van vorm A, over 241 goldens en 647 cases); een
# leg die stilletjes minder berekeningen uitlegt, faalt. Sinds de kaart explanation-defaults-computation draagt ook de
# zin van een defaults-groep op een attribuut de berekening van de variant die besliste, en telt die zin mee (gemeten
# 2026-10-22: 2046); de vloer bleef staan.
EXPECTED_MIN_EXPLAINED_COMPUTATIONS=1852
# Kaart explanation-read-objects-and-displaced: het aantal gelezen waarden met een schrijver (een NAME of QUOTE met de
# sleutels van een attribuut of kenmerk en een object, waarvan een vorm van de scope dat lid schrijft op het type van
# het object) die de zin van (object, canonical) in dezelfde uitleg vinden; service_replay.py faalt de golden waar die
# zin ontbreekt. Een ondergrens, gemeten MEASURED; een leg die stilletjes minder waarden aan hun zin koppelt, faalt.
EXPECTED_MIN_READ_LINKS=0

is_undetermined_listed() {
  local e; for e in "${EXPLANATION_UNDETERMINED[@]+"${EXPLANATION_UNDETERMINED[@]}"}"; do
    [ "${e%%|*}" = "$1" ] && return 0
  done
  return 1
}

is_excluded() { local e; for e in "${REPLAY_EXCLUDED[@]}"; do [ "${e%%|*}" = "$1" ] && return 0; done; return 1; }

# Elke regel een golden en een reden, en geen golden twee keer.
seen=""
for e in "${REPLAY_EXCLUDED[@]}"; do
  g="${e%%|*}"; r="${e#*|}"
  [ -n "$r" ] && [ "$r" != "$e" ] || fail "uitsluiting zonder reden: '$e'"
  case " $seen " in *" $g "*) fail "'$g' staat twee keer in REPLAY_EXCLUDED" ;; esac
  seen="$seen $g"
done

# --- de set van gate 12 -------------------------------------------------------------------------------------
DROOLS=uchar/scripts/check-drools.sh
[ -f "$DROOLS" ] || fail "$DROOLS ontbreekt (is de submodule geïnitialiseerd?)"
uchar_played=$(sed -n 's/^EXPECTED_PLAYED=\([0-9][0-9]*\).*/\1/p' "$DROOLS")
[ -n "$uchar_played" ] || fail "geen EXPECTED_PLAYED in $DROOLS"
not_played=$(awk '/^EXPECTED_CHANNEL=\(/{f=1;next} f&&/^\)/{f=0} f' "$DROOLS" | sed -n 's/^ *"\([^|]*\)|.*/\1/p' | sort)
[ -n "$not_played" ] || fail "geen EXPECTED_CHANNEL-regels gelezen uit $DROOLS"
set_goldens=()
while IFS= read -r ir; do
  d=$(dirname "$ir"); g=${d#uchar/corpus/golden/}
  printf '%s\n' "$not_played" | grep -qxF "$g" && continue
  ls "$d"/case*.json >/dev/null 2>&1 || continue
  set_goldens+=("$g")
done < <(find uchar/corpus/golden -name ir.json | sort)
[ ${#set_goldens[@]} -gt 0 ] || fail "lege set: geen golden die gate 12 speelt (een lege set is een faal)"
[ ${#set_goldens[@]} -eq "$uchar_played" ] \
  || fail "${#set_goldens[@]} goldens afgeleid, uchars gate 12 speelt er $uchar_played: de afleiding volgt $DROOLS niet meer"

# --- de dienst ---------------------------------------------------------------------------------------------
# De jar van bin/regelspraak is bij voordat de diensten tegelijk starten, zodat geen van hen hem bouwt. bin/regelspraak
# jar bouwt hem alleen als een bron nieuwer is; na de reactor (make gates) herschrijft deze poort hem dus niet terwijl
# de tweelingen en het manifest, die tegelijk draaien, eruit lezen.
bin/regelspraak jar >/dev/null || fail "de jar van bin/regelspraak bouwt niet"

begin=$(date +%s)
output=$(python3 scripts/service_replay.py --corpus uchar/corpus/golden --explanation \
    --workers "${REPLAY_WORKERS:-4}" "${set_goldens[@]}") || fail "de replay-driver faalde"
duration=$(( $(date +%s) - begin ))

played=0; excl=0; cases=0; sentences=0; conditions=0; computations=0; links=0
deviations=(); unnamed=(); outdated=(); undetermined=(); explained_goldens=()
while IFS=$'\t' read -r g status detail g_sentences g_conditions g_undetermined g_computations g_links; do
  [ -n "$g" ] || continue
  case "$status" in
    PLAYED)
      is_excluded "$g" && outdated+=("$g speelt door de dienst maar staat in REPLAY_EXCLUDED")
      [ -n "$g_undetermined" ] && [ -n "$g_computations" ] && [ -n "$g_links" ] \
        || fail "$g: de replay-driver gaf geen telling van de uitleg"
      sentences=$((sentences + g_sentences)); conditions=$((conditions + g_conditions))
      computations=$((computations + g_computations)); links=$((links + g_links))
      explained_goldens+=("$g")
      if [ "$g_undetermined" -gt 0 ]; then
        is_undetermined_listed "$g" \
          || undetermined+=("$g: $g_undetermined keer \"niet is vast te stellen\" in de uitleg")
      elif is_undetermined_listed "$g"; then
        outdated+=("$g staat in EXPLANATION_UNDETERMINED maar zijn uitleg noemt \"niet is vast te stellen\" niet")
      fi
      played=$((played + 1)); cases=$((cases + detail)) ;;
    EXCLUDE)
      is_excluded "$g" || unnamed+=("$g :: $detail")
      excl=$((excl + 1)) ;;
    *) deviations+=("$g :: $status $detail") ;;
  esac
done <<< "$output"
for e in "${REPLAY_EXCLUDED[@]}"; do
  g="${e%%|*}"
  printf '%s\n' "${set_goldens[@]}" | grep -qxF "$g" || outdated+=("$g staat in REPLAY_EXCLUDED maar niet in de set van gate 12")
done

for e in "${EXPLANATION_UNDETERMINED[@]+"${EXPLANATION_UNDETERMINED[@]}"}"; do
  g="${e%%|*}"
  printf '%s\n' "${explained_goldens[@]}" | grep -qxF "$g" \
    || outdated+=("$g staat in EXPLANATION_UNDETERMINED maar speelt niet door de dienst")
done

if [ ${#deviations[@]} -gt 0 ]; then
  echo "check-service-replay: FAIL — ${#deviations[@]} golden(s) wijken af of spelen niet:" >&2
  printf '    %s\n' "${deviations[@]}" >&2; exit 1
fi
if [ ${#unnamed[@]} -gt 0 ]; then
  echo "check-service-replay: FAIL — ${#unnamed[@]} golden(s) spelen niet en staan niet in REPLAY_EXCLUDED:" >&2
  printf '    %s\n' "${unnamed[@]}" >&2; exit 1
fi
if [ ${#outdated[@]} -gt 0 ]; then
  echo "check-service-replay: FAIL — REPLAY_EXCLUDED is verouderd:" >&2
  printf '    %s\n' "${outdated[@]}" >&2; exit 1
fi
if [ ${#undetermined[@]} -gt 0 ]; then
  echo "check-service-replay: FAIL — ${#undetermined[@]} golden(s) leggen een voorwaarde uit als niet vast te stellen:" >&2
  printf '    %s\n' "${undetermined[@]}" >&2; exit 1
fi
[ "$played" -gt 0 ] || fail "geen golden speelde door de dienst (een lege set is een faal)"
[ "$conditions" -gt 0 ] || fail "de uitleg verwoordde over $played goldens geen enkele voorwaarde (een lege leg is een faal)"
[ "$conditions" -ge "$EXPECTED_MIN_EXPLAINED_CONDITIONS" ] \
  || fail "de uitleg verwoordde $conditions voorwaarden, de vloer EXPECTED_MIN_EXPLAINED_CONDITIONS is $EXPECTED_MIN_EXPLAINED_CONDITIONS"
[ "$computations" -ge "$EXPECTED_MIN_EXPLAINED_COMPUTATIONS" ] \
  || fail "de uitleg verwoordde $computations berekende waarden, de vloer EXPECTED_MIN_EXPLAINED_COMPUTATIONS is $EXPECTED_MIN_EXPLAINED_COMPUTATIONS"
[ "$links" -ge "$EXPECTED_MIN_READ_LINKS" ] \
  || fail "de uitleg koppelde $links gelezen waarden aan hun zin, de vloer EXPECTED_MIN_READ_LINKS is $EXPECTED_MIN_READ_LINKS"
[ "$played" -eq "$EXPECTED_REPLAY_PLAYED" ] \
  || fail "$played goldens speelden, EXPECTED_REPLAY_PLAYED pint $EXPECTED_REPLAY_PLAYED (een nieuwe speler: zet het getal bij)"

echo "check-service-replay: PASS"
echo "  set van gate 12: ${#set_goldens[@]} goldens (uchars EXPECTED_PLAYED $uchar_played)"
echo "  gespeeld door de dienst: $played goldens, $cases cases (pin $EXPECTED_REPLAY_PLAYED)"
echo "  uitgesloten: $excl, bij naam met reden"
echo "  uitleg: $sentences zinnen, elk gelijk aan haar stukken, $conditions uitgelegde voorwaarden (vloer $EXPECTED_MIN_EXPLAINED_CONDITIONS), $computations berekende waarden (vloer $EXPECTED_MIN_EXPLAINED_COMPUTATIONS), $links gelezen waarden aan hun zin gekoppeld (vloer $EXPECTED_MIN_READ_LINKS), \"niet is vast te stellen\" bij ${#EXPLANATION_UNDETERMINED[@]} golden(s) op de lijst"
echo "  duur: ${duration}s met ${REPLAY_WORKERS:-4} diensten tegelijk"
