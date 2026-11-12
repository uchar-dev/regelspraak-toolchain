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
# Kanalen, zoals gate 12 ze telt, maar per case:
#   PLAYED   de standaard: een golden speelt als elke case die de dienst draagt speelt, en minstens één;
#   EXCLUDE  een case met een vorm die het verzoek of het antwoord van de dienst niet uitdrukt, bij naam
#            ("<golden>/<case>") in REPLAY_EXCLUDED met de reden, of een golden waarop de dienst niet start, bij naam
#            van de golden; een sleutel die daar staat en niet (meer) wordt uitgesloten, of waarvan de golden niet
#            (meer) in de set zit, faalt de poort (verouderd), en een uitsluiting die er niet staat, faalt ook;
#   FAIL     een afwijking of een dienst die niet start: faalt altijd.
# EXPECTED_REPLAY_PLAYED pint het aantal gespeelde goldens en EXPECTED_REPLAY_CASES het aantal gespeelde cases (beide
# een gelijkheid, zoals gate 12 EXPECTED_PLAYED pint): een nieuwe golden of case die speelt, is voor een lijst die
# alleen niet-spelers noemt onzichtbaar. Een lege set faalt.
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
# 2026-10-27, uchar's kaart test-beyond-thirteen-reads (pin 088659f0): 250 — de set van gate 12 groeit van 279 naar
# 280 met jutterswet-strandvoogdkeuring (een voorwaarde die achttien waarden leest, vier over een rol; DROOLS-264
# vervallen); zij speelt ook door de dienst.
# 2026-10-27, uchar's kaart subtype-characteristic-in-fold-filter (pin 5e327c95): 251 — de set van gate 12 groeit
# van 280 naar 281 met jutterswet-ploegblokkade (een kenmerk dat alleen een subtype declareert, gelezen op het
# supertype: de versmallende host); zij speelt ook door de dienst.
# 2026-11-01, uchar's kaarten fout-op-tussenstand, omgekeerde-eenheid en unit-factors (pin 3d4676b8): 268 — de set van
# gate 12 groeit van 281 naar 299; zeventien spelen ook door de dienst, jutterswet-kadetarief niet (een tijdlijn als
# invoerwaarde).
# 2026-11-02, uchar's kaarten drools-schrijfgrens en instance-depth-owner-hop (pin 46833a11): 269 — de set van gate 12
# groeit van 299 naar 300 met jutterswet-vergunningketen-eigenaarsrol (de instantiegeordende keten waarvan de
# eigenaarsrol van de satellietrelatie de naam van de zusterrol van de schakelrelatie draagt); zij speelt ook door de
# dienst.
# 2026-11-02, uchar's kaart dimensions-executed (pin 1810216d): 275 — de set van gate 12 groeit van 300 naar 306 met de
# zes goldens van een gedimensioneerd attribuut (jutterswet-seizoenvondst, -seizoenvouw, -vormenkabinet,
# -seizoenboeking, -seizoenkopie en -strandseizoen: een cel lezen, schrijven en vouwen); alle zes spelen ook door de
# dienst, die een gedimensioneerd attribuut sindsdien als cellen neemt en teruggeeft.
# 2026-11-02, uchar's kaart dimensions-remaining (pin d9c6fe4d): 278 — de set van gate 12 groeit van 306 naar 309 met
# jutterswet-seizoenaanvang (initialisatie plus berekening op één cel), jutterswet-seizoenlogboek (cellen van tekst,
# datum en boolean; eerste, laatste en aantal voorkomens over cellen) en jutterswet-ploegseizoen (een cel en een vouw
# over de cellen van het object dat een rol bereikt, een cel van elk lid als vouwbron); alle drie spelen ook door de
# dienst, met uitleg.
# 2026-11-03, uchar's kaarten written-value-at-fixpoint, drools-exact-arithmetic en static-precision-gate (pin
# 698a5543): 285 — de set van gate 12 groeit van 309 naar 316 met jutterswet-geschreven-waarde-aan-fixpunt,
# jutterswet-kenmerk-aan-fixpunt en jutterswet-twee-schrijvers-aan-fixpunt, elk in beide regelvolgordes (een
# schrijfactie staat alleen waar haar vorm aan het fixpunt beslist), en jutterswet-precisiepoort (de vormen die de
# precisiepoort doorlaat); alle zeven spelen ook door de dienst, met uitleg.
# 2026-11-04, uchar's kaarten timeline-arithmetic-by-rule, timeline-arithmetic-per-period, conditions-per-period en
# total-over-partial-unit met hun fixrondes (pin 504522cc): 287 — de set van gate 12 groeit van 316 naar 328 met elf
# goldens van tijd over alle tijd en met jutterswet-pachtvenster, die gate 12 nu speelt. Twee spelen ook door de
# dienst: jutterswet-verzuimrente-30-360-afgerond (een tijdsafhankelijke parameter met open grenzen in het verzoek,
# tijdlijnen met open grenzen in het antwoord, sinds deze pin) en time-window-timeline-characteristic (een kenmerk
# binnen een venster, met uitleg); de tien andere hebben een tijdlijn als invoerwaarde of een kenmerk met perioden en
# staan hieronder.
# 2026-11-04, uchar's kaarten defaults-per-period-on-timeline en clingo-lazy-bucket-scalar-fault (pin 474732d3): 287 —
# de set van gate 12 groeit van 328 naar 330 met jutterswet-opgave-per-periode (een defaults-groep op een tijdlijndoel
# beslist per dag, de opgave-markering op een tijdsafhankelijk attribuut) en jutterswet-gevolg-buiten-de-beurt (een
# gevolg buiten de beurt faalt niet); geen van beide speelt door de dienst (een tijdlijn als invoerwaarde, een kenmerk
# met perioden), dus beide staan hieronder.
# 2026-11-04, kaart timeline-attribute-input: 323 — het verzoek draagt een tijdsafhankelijk attribuut als tijdlijn en
# een tijdsafhankelijk kenmerk als zijn perioden, in de vorm van uchars case. 36 goldens spelen sindsdien door de dienst:
# de 27 met de reden "tijdlijn als invoerwaarde" en de 9 met "kenmerk met perioden" die geen scalaire waarde op een
# tijdsafhankelijk attribuut hebben. jutterswet-opgave-per-periode speelt zonder uitleg (EXPLANATION_REFUSED). De zeven
# die blijven, hebben in een case een scalaire waarde op een tijdsafhankelijk attribuut; bij vijf daarvan stond eerst de
# tijdlijn of het kenmerk als reden, de eerste vorm die het verzoek niet droeg.
# 2026-11-04, uchar's kaarten early-faults-and-sum-reading, decisions-per-period en writers-as-one-defaults-stack (pin
# 53605e86): 330 — de set van gate 12 groeit van 330 naar 338 met bound-day-behind-a-failing-condition,
# context-value-behind-a-failing-condition en unresolved-behind-a-failing-condition (een foutsoort achter een
# voorwaarde die niet geldt), jutterswet-invoer-naast-initialisatie, -naast-verdeling, -naast-objectcreatie en
# -op-keurklasse (invoer in de stapel van een doel, naast een initialisatie, een verdeling en een creatie) en
# jutterswet-gevolg-buiten-de-beurt-som. Zeven spelen ook door de dienst, met uitleg; de achtste geeft een scalaire
# waarde op een tijdsafhankelijk attribuut en staat hieronder.
# 2026-11-05, uchar's kaart absence-markers (pin d208bbfa): 330 — de set van gate 12 groeit van 338 naar 339 met
# jutterswet-afwezigheidsmarkers (een attribuut met de markers onbekend en niet van toepassing, een sum type); zij speelt
# niet door de dienst, waarvan het verzoek geen vorm voor een marker heeft, en staat hieronder.
# 2026-11-05, kaart absence-markers-in-service: 331 — het verzoek en het antwoord geven een attribuut met markers zijn
# waarde als waarde van het datatype en een marker als {"marker": <naam>}, ook in een segment van een tijdlijn, en de
# uitleg noemt een marker bij zijn naam; jutterswet-afwezigheidsmarkers speelt sindsdien door de dienst, met uitleg
# (10 cases).
# 2026-11-05, uchar's kaarten reading-and-uniqueness-residue en absence-markers-residue (pin 9c275fd8): 335 — de set van
# gate 12 groeit van 339 naar 344 met jutterswet-uniciteit-reeks (een uniciteit over een unie met een tekstsleutel),
# jutterswet-voorwaarde-buiten-de-beurt (een rechtvaardiging per periode over de rekendatumscalair) en
# jutterswet-afwezigheidsmarkers-cel, -creatie en -kopie (een marker in een cel, in een objectcreatie, de kopie van een
# tijdlijn van een sum type). Vier spelen ook door de dienst, met uitleg (de cel als {"marker": <naam>} in de cellen van
# het verzoek en het antwoord); jutterswet-voorwaarde-buiten-de-beurt geeft een scalaire waarde op een tijdsafhankelijk
# attribuut en staat hieronder.
# 2026-11-05, uchar's kaart timeline-initialisation-allocation-creation (pin 74119a23): 338 — de set van gate 12 groeit
# van 344 naar 347 met jutterswet-initialisatie-per-dag (een initialisatie die per dag de dagen vult die regels en
# invoer leeg laten), jutterswet-creatie-per-dag (de bij de objectcreatie toegekende waarde als invoer van het
# gemaakte object per dag) en jutterswet-afwezigheidsmarkers-initialisatie (een initialisatie op een marker); Drools
# weigerde ze eerder (DROOLS-118, DROOLS-103). Alle drie spelen ook door de dienst, met uitleg.
# 2026-11-06, kaart onopgeloste-naam-weigeren: 337 — de dienst start niet op een document met een Unresolved-knoop
# (een fout in de bron, ook met --allow-markers), dus unresolved-behind-a-failing-condition speelt niet meer door de
# dienst en staat hieronder.
# 2026-11-06, kaart dienst-replay-per-case: 338 — de replay sluit een case uit, niet de golden; van de negen goldens
# met een scalaire waarde op een tijdsafhankelijk attribuut speelt jutterswet-vast-startpunt nu met vijf van haar
# zeven cases, de acht andere hebben die vorm in hun enige case.
# 2026-11-06, pin uchar ff489f7e: 353 — de set van gate 12 groeit van 347 naar 362. Vier N:M-goldens spelen in Drools
# sinds uchars kaart drools-veelopveel-beanmodel (anchor/jutterswet-bootberging, anchor/jutterswet-gemeenteberging,
# jutterswet-ketenkwantor, jutterswet-muntketen: een relatie met twee meervoudige rollen is invoer) en elf goldens van
# uchars opruimronde kwamen erbij (jutterswet-keurquotient, -kwantorvouw-en-unie, -lege-pot-en-negatief-gewicht,
# -lidnamen, -loopgrens, -loopgrens-keuze, -macht-van-nul, -strata-na-een-creatie, -strata-na-een-creatie-consistentie,
# -tekst-van-een-waarde, -vouwfilter-buiten-de-beurt). Alle vijftien spelen ook door de dienst, met uitleg.
# 2026-11-11, pin uchar 3f2da0db: 358 — de set van gate 12 groeit van 362 naar 367: jutterswet-meldplicht,
# jutterswet-meldplicht-catala en jutterswet-meldplicht-flogic (uchars kaart meldplicht-drie-talen), en
# jutterswet-let-keuze-in-laatste en let-bound-period-arithmetic-total (uchars kaart review-candidates-triage).
# Alle vijf spelen ook door de dienst.
# 2026-11-11, pin uchar 1db2a783e: 364 — de set van gate 12 groeit van 367 naar 373 met
# jutterswet-rangmaximering-maximum-in-centen, jutterswet-rangmaximering-in-centen, jutterswet-leeg-deeltal,
# jutterswet-wrakbasis-vervangen-basis-regel, jutterswet-wrakbasis-vervangen-basis-groep en
# jutterswet-bakenmeester-let; alle zes spelen ook door de dienst. jutterswet-rolnaam-in-twee-feittypen kwam ook in
# uchar, maar Drools weigert hem (EXPECTED_CHANNEL van gate 12), dus hij zit niet in de set.
# Pin uchar cee0c1a98: 368 — vier nieuwe goldens spelen door de dienst: jutterswet-meldtijdstip-delen,
# jutterswet-registratienummer, jutterswet-registratienummer-gehele-jaar (een tekst op een tijdlijn) en
# jutterswet-saldotoezicht.
# Pin uchar 970286134: 369 — jutterswet-leeg-deeltal-per-periode speelt door de dienst.
EXPECTED_REPLAY_PLAYED=369
# Het aantal cases dat door de dienst speelt, over de gespeelde goldens; gemeten 2026-11-06 bij de kaart
# dienst-replay-per-case (pin uchar 50f17953): 968. Pin uchar ff489f7e: 999 — de negen cases van de vier N:M-goldens,
# de 21 van de elf nieuwe goldens en jutterswet-filterwacht/case3-een-jutter-die-niet-actief-is-naast-een-actieve.
# 1000: de dienst leest een naam zoals uchars caselezer (Namen.norm: spaties en tabs als één spatie, A-Z gevouwen), dus
# jutterswet-keurunie/case2-spelling.json, met namen met een tab, twee spaties en een spatie vooraan, speelt.
# 1006 (pin uchar 3f2da0db): de cases van de vijf nieuwe goldens hierboven.
# 1014 (pin uchar 1db2a783e): de acht cases van de zes nieuwe goldens hierboven, drie daarvan van jutterswet-leeg-deeltal.
# 1034 (pin uchar cee0c1a98): de 20 cases van de vier nieuwe goldens hierboven.
# 1037 (pin uchar 970286134): de drie cases van jutterswet-leeg-deeltal-per-periode.
EXPECTED_REPLAY_CASES=1037

# De cases van de set van gate 12 die de dienst niet speelt, "<golden>/<case>|<reden>", en de goldens waarop hij niet
# start, "<golden>|<reden>". Het verzoek van de dienst draagt een tijdsafhankelijke parameter en een tijdsafhankelijk
# attribuut als tijdlijn en een tijdsafhankelijk kenmerk als zijn perioden, maar geen scalaire waarde op een
# tijdsafhankelijk attribuut: uchars replay legt die in de periode van de rekendatum (RUNTIME-SEMANTICS ch. 8.4), en de
# dienst weigert haar met een 400 UNSUPPORTED. Op een document met een Unresolved-knoop start de dienst niet: hij
# weigert een onopgeloste naam als fout in de bron, waar uchar pas faalt als de evaluatie de knoop bereikt (WF-044).
REPLAY_EXCLUDED=(
  "jutterswet-gevolg-buiten-de-beurt/case1-buiten-de-beurt.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-gevolg-buiten-de-beurt-som/case1-buiten-de-beurt.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-kadetarief/case.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-keurlast/case.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-muntbewaring/case.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-penningjaar/case.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-tempototaal/case.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-vast-startpunt/case5-geklemde-maand.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-vast-startpunt/case7-week-en-kwartaal.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "jutterswet-voorwaarde-buiten-de-beurt/case1-buiten-de-beurt.json|scalaire waarde op een tijdsafhankelijk attribuut (UNSUPPORTED in de dienst)"
  "unresolved-behind-a-failing-condition|een Unresolved-knoop in het document: de dienst start niet op een onopgeloste naam"
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
# zin ontbreekt. Een ondergrens, gemeten 2026-10-27 bij die kaart (4248 over 249 goldens en 673 cases); een leg die
# stilletjes minder waarden aan hun zin koppelt, faalt.
EXPECTED_MIN_READ_LINKS=4248

is_undetermined_listed() {
  local e; for e in "${EXPLANATION_UNDETERMINED[@]+"${EXPLANATION_UNDETERMINED[@]}"}"; do
    [ "${e%%|*}" = "$1" ] && return 0
  done
  return 1
}

is_excluded() { local e; for e in "${REPLAY_EXCLUDED[@]}"; do [ "${e%%|*}" = "$1" ] && return 0; done; return 1; }

# De golden van een sleutel: een case-sleutel eindigt op haar casebestand ("<golden>/case….json"), een
# golden-sleutel is de golden.
golden_of() { case "$1" in */case*.json) printf '%s' "${1%/*}" ;; *) printf '%s' "$1" ;; esac; }

# Elke regel een sleutel en een reden, geen sleutel twee keer, en geen golden met een sleutel van zichzelf en een van
# een case.
seen=$'\n'
for e in "${REPLAY_EXCLUDED[@]}"; do
  k="${e%%|*}"; r="${e#*|}"
  [ -n "$r" ] && [ "$r" != "$e" ] || fail "uitsluiting zonder reden: '$e'"
  case "$seen" in *$'\n'"$k"$'\n'*) fail "'$k' staat twee keer in REPLAY_EXCLUDED" ;; esac
  seen="$seen$k"$'\n'
done
for e in "${REPLAY_EXCLUDED[@]}"; do
  k="${e%%|*}"; g=$(golden_of "$k")
  [ "$k" = "$g" ] || ! is_excluded "$g" || fail "'$g' staat in REPLAY_EXCLUDED als golden en met de case '$k'"
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

played=0; excl=0; excl_cases=0; cases=0; sentences=0; conditions=0; computations=0; links=0
deviations=(); unnamed=(); outdated=(); undetermined=(); explained_goldens=(); excluded_keys=$'\n'
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
      excluded_keys="$excluded_keys$g"$'\n'
      if [ "$(golden_of "$g")" = "$g" ]; then excl=$((excl + 1)); else excl_cases=$((excl_cases + 1)); fi ;;
    *) deviations+=("$g :: $status $detail") ;;
  esac
done <<< "$output"
for e in "${REPLAY_EXCLUDED[@]}"; do
  k="${e%%|*}"; g=$(golden_of "$k")
  if ! printf '%s\n' "${set_goldens[@]}" | grep -qxF "$g"; then
    outdated+=("$k staat in REPLAY_EXCLUDED maar $g zit niet in de set van gate 12")
  else
    case "$excluded_keys" in
      *$'\n'"$k"$'\n'*) ;;
      *) outdated+=("$k staat in REPLAY_EXCLUDED maar wordt niet uitgesloten") ;;
    esac
  fi
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
  echo "check-service-replay: FAIL — ${#unnamed[@]} uitsluiting(en) van een golden of case staan niet in REPLAY_EXCLUDED:" >&2
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
[ "$cases" -eq "$EXPECTED_REPLAY_CASES" ] \
  || fail "$cases cases speelden, EXPECTED_REPLAY_CASES pint $EXPECTED_REPLAY_CASES (een nieuwe case die speelt: zet het getal bij)"

echo "check-service-replay: PASS"
echo "  set van gate 12: ${#set_goldens[@]} goldens (uchars EXPECTED_PLAYED $uchar_played)"
echo "  gespeeld door de dienst: $played goldens (pin $EXPECTED_REPLAY_PLAYED), $cases cases (pin $EXPECTED_REPLAY_CASES)"
echo "  uitgesloten: $excl goldens en $excl_cases cases, bij naam met reden"
echo "  uitleg: $sentences zinnen, elk gelijk aan haar stukken, $conditions uitgelegde voorwaarden (vloer $EXPECTED_MIN_EXPLAINED_CONDITIONS), $computations berekende waarden (vloer $EXPECTED_MIN_EXPLAINED_COMPUTATIONS), $links gelezen waarden aan hun zin gekoppeld (vloer $EXPECTED_MIN_READ_LINKS), \"niet is vast te stellen\" bij ${#EXPLANATION_UNDETERMINED[@]} golden(s) op de lijst"
echo "  duur: ${duration}s met ${REPLAY_WORKERS:-4} diensten tegelijk"
