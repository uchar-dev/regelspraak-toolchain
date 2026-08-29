// RegelSpraakLexer.g4 — terminals for RegelSpraak v2.3.0.
//
// Derived from the RegelSpraak v2.3.0 specification (the oracle):
//   - §13.2 syntaxdiagrammen  → literals, numbers, names, dates
//   - an ANTLR-targeted draft → keyword inventory (cross-checked, not copied)
// The draft (its §2) is a buildable-but-buggy starting point; where it
// contradicts the authoritative diagrams or real casus source written in
// RegelSpraak, the diagrams/casus win. The reconciliations below are deliberate:
//
//  1. WHITESPACE IS SKIPPED ENTIRELY (incl. newlines). The draft sprinkles a
//     `NEWLINE?` token through its parser yet its own §2.5 skips '\n' in WS — a
//     contradiction. Worse, real rules wrap mid-sentence across physical lines,
//     so newlines cannot be significant. Structure is delimited by
//     ';' (members), '.' (rule/condition end), bullet markers, and keywords.
//
//  2. NAMES ARE SPLIT BY CASE into UPPER_ID and LOWER_ID. A space-bearing
//     identifier would, under maximal munch, greedily swallow following keywords
//     and even whole sentences. So multi-word names are assembled in the PARSER
//     from single-word tokens. RegelSpraak's documented convention — objecttype/
//     domain/datatype names are Capitalised, attribute/role/parameter names are
//     lowercase — makes capitalisation a reliable, grammar-usable boundary: it is
//     what lets "de belasting op basis van afstand Bedrag" split into the
//     lowercase attribute name and the capitalised domain, and "de groep Ploeg"
//     into role and objecttype, with no symbol table.
//
//  3. COMPARISON OPERATORS ARE ATOMIC WORDS (gelijk, ongelijk, groter, kleiner,
//     later, eerder, of, aan, dan). The draft inconsistently mixes single tokens
//     ('gelijk aan') with split forms; both surface forms occur in real casus
//     source ("X kleiner is dan Y" toplevel vs "X is groter of gelijk aan Y" nested).
//     Atomic words let the parser compose every form without prefix clashes.
//
//  4. BULLETS ARE '-' (top level) AND '..' (nested), as real casus source
//     writes them; the EBNF's '•' glyph is honoured too.
//
//  5. SENTENCE-INITIAL ARTICLES are case-folded (de/De, het/Het, een/Een) since
//     every rule sentence opens with a capitalised article ("De leeftijd …").
//
// Authoring order obeys ANTLR maximal-munch with first-match-on-ties:
// longest multi-word phrases first, then literals, then single-word keywords,
// then atomic operator words, then punctuation, and the ID tokens LAST.

lexer grammar RegelSpraakLexer;

// ============================================================================
// Multi-word fixed phrases (longest / most-specific first).
// Safe as single tokens (unlike open-ended names) because they match an exact
// string and stop; maximal munch prefers them over their single-word prefixes.
// ============================================================================

// --- parenthesised / colon-bearing phrases (precede bare '(' and ':') ---
VOOR_HET_ATTRIBUUT_ZONDER_VOORZETSEL : '(voor' WSP 'het' WSP 'attribuut' WSP 'zonder' WSP 'voorzetsel):' ; // §13.3.7.2
NA_HET_ATTRIBUUT_MET_VOORZETSEL      : '(na' WSP 'het' WSP 'attribuut' WSP 'met' WSP 'voorzetsel'        ; // §13.3.7.2
BEZIELD     : '(bezield)'     ; // §13.3.1.1
BEZITTELIJK : '(bezittelijk)' ; // §13.3.2.2
BIJVOEGLIJK : '(bijvoeglijk)' ; // §13.3.2.3
MV_START    : '(mv:'          ; // §13.2.29 naamwoord plural opener

// --- datum/datumtijd constructors (precede the bare 'datum'/'de' words) ---
DATUMTIJD_MET : 'de' WSP 'datumtijd' WSP 'met' ; // §13.4.17.36
DATUM_MET     : 'de' WSP 'datum' WSP 'met'     ; // §13.4.17.35

// --- result / rule-shape phrases ---
WORDT_BEREKEND_ALS       : 'moet' WSP 'berekend' WSP 'worden' WSP 'als'        ; // §13.4.4.3
WORDT_GESTELD_OP         : 'moet' WSP 'gesteld' WSP 'worden' WSP 'op'          ; // §13.4.4.2
WORDT_GEINITIALISEERD_OP : 'moet' WSP 'geïnitialiseerd' WSP 'worden' WSP 'op'  ; // §13.4.9.1
WORDT_VERDEELD_OVER      : 'wordt' WSP 'verdeeld' WSP 'over'             ; // §13.4.10.1
WORDT_BEPAALD_DOOR       : 'wordt' WSP 'bepaald' WSP 'door'              ; // §13.4.12.1
HET_STARTPUNT_VAN        : 'Het' WSP 'startpunt' WSP 'van'               ; // §13.4.12.1
ER_MOET_WORDEN_VOLDAAN_AAN : [Ee] 'r' WSP 'moet' WSP 'worden' WSP 'voldaan' WSP 'aan' ; // §13.4.8.7 (sentence-initial 'Er' too)
WORDT_VOLDAAN            : 'wordt' WSP 'voldaan'                    ; // §13.4.8.10 / §13.4.14 (trailing 'aan' is separate)

// --- aggregation / function phrases ---
ABSOLUTE_TIJDSDUUR_VAN : 'de' WSP 'absolute' WSP 'tijdsduur' WSP 'van' ; // §13.4.17.32
TIJDSDUUR_VAN          : 'de' WSP 'tijdsduur' WSP 'van'          ; // §13.4.17.32
ABSOLUTE_WAARDE_VAN    : 'de' WSP 'absolute' WSP 'waarde' WSP 'van'    ; // §13.4.17.17
MAXIMALE_WAARDE_VAN    : 'de' WSP 'maximale' WSP 'waarde' WSP 'van'    ; // §13.4.17.16 / .47
MINIMALE_WAARDE_VAN    : 'de' WSP 'minimale' WSP 'waarde' WSP 'van'    ; // §13.4.17.15 / .47
WORTEL_VAN             : 'de' WSP 'wortel' WSP 'van'             ; // §13.4.17.13
SOM_VAN                : 'de' WSP 'som' WSP 'van'                ; // §13.4.17.47
TOTAAL_VAN             : 'het' WSP 'totaal' WSP 'van'            ; // §13.4.17.56
AANTAL_DAGEN_IN        : 'het' WSP 'aantal' WSP 'dagen' WSP 'in'       ; // §13.4.17.57
EERSTE_PAASDAG_VAN     : 'de' WSP 'eerste' WSP 'paasdag' WSP 'van'     ; // §13.4.17.37
EERSTE_VAN             : 'de' WSP 'eerste' WSP 'van'             ; // §13.4.17.39 / .49
LAATSTE_VAN            : 'de' WSP 'laatste' WSP 'van'            ; // §13.4.17.40 / .49
CONCATENATIE_VAN       : [Dd] 'e' WSP 'concatenatie' WSP 'van'   ; // §13.4.8.14 (sentence-initial 'De' too)
TIJDSEVENREDIG_DEEL_PER: 'het' WSP 'tijdsevenredig' WSP 'deel' WSP 'per'; // §13.4.17.58
// 'in hele <eenheid>' (§13.4.17.32) is composed in the parser as IN HELE <unit>,
// so 'in' and 'hele' stay separate tokens (the bare-unit form is just 'in <unit>').
// 'aantal' is its own word (the telling aggregation 'het aantal' is composed in
// the parser as HET AANTAL_WORD), so the article stays separable from an
// attribute name that merely begins with "aantal" (e.g. "het aantal punten").
// The longer 'het aantal dagen in' phrase is still matched first.

// --- bounding / rounding / arithmetic phrases ---
MET_EEN_MINIMUM_VAN : 'met' WSP 'een' WSP 'minimum' WSP 'van' ; // §13.4.17.29
MET_EEN_MAXIMUM_VAN : 'met' WSP 'een' WSP 'maximum' WSP 'van' ; // §13.4.17.30
// A §6 worked example drops the 'met' on the SECOND begrenzing after 'en'
// ("met een minimum van 0 € en een maximum van 1000 €"). Only the maximum form
// is attested, so only it is tokenised; matching 'een minimum van' too would
// needlessly shadow an attribute literally named "minimum".
EEN_MAXIMUM_VAN     : 'een' WSP 'maximum' WSP 'van' ;
GEDEELD_DOOR_ABS    : 'gedeeld' WSP 'door' WSP '(ABS)'  ; // §13.4.17 (abs division)
GEDEELD_DOOR        : 'gedeeld' WSP 'door'        ; // §13.4.16
VERMINDERD_MET      : 'verminderd' WSP 'met'      ; // §13.4.16
TOT_DE_MACHT        : 'tot' WSP 'de' WSP 'macht'        ; // §13.4.17.14
AFGEROND_OP         : 'afgerond' WSP 'op'         ; // §13.4.17.26
NAAR_BENEDEN        : 'naar' WSP 'beneden'        ; // §13.4.17.26
NAAR_BOVEN          : 'naar' WSP 'boven'          ; // §13.4.17.26
RICHTING_NUL        : 'richting' WSP 'nul'        ; // §13.4.17.26
WEG_VAN_NUL         : 'weg' WSP 'van' WSP 'nul'         ; // §13.4.17.26
VERENIGD_MET        : 'verenigd' WSP 'met'        ; // §13.4.8.12
TOT_EN_MET          : 'tot' WSP 'en' WSP 'met'          ; // §13.2 range / kenmerk names

// --- verdeling phrases ---
WAARBIJ_WORDT_VERDEELD     : 'waarbij' WSP 'wordt' WSP 'verdeeld'      ; // §13.4.10.1
ALS_ONVERDEELDE_REST_BLIJFT: 'Als' WSP 'onverdeelde' WSP 'rest' WSP 'blijft' ; // §13.4.10.8
BIJ_EVEN_GROOT_CRITERIUM   : 'bij' WSP 'een' WSP 'even' WSP 'groot' WSP 'criterium'; // §13.4.10.5 (casus usage includes 'een')
OP_VOLGORDE_VAN            : 'op' WSP 'volgorde' WSP 'van'             ; // §13.4.10.4
IN_GELIJKE_DELEN           : 'in' WSP 'gelijke' WSP 'delen'           ; // §13.4.10.2
NAAR_RATO_VAN             : 'naar' WSP 'rato' WSP 'van'                ; // §13.4.10.2

// --- consistency / criterium phrases ---
VOLGEND_CRITERIUM : 'het' WSP 'volgende' WSP 'criterium' ; // §13.4.8.7
VOLGENDE_CRITERIA : 'volgende' WSP 'criteria'      ; // §13.4.8.7
AAN_DE_ELFPROEF   : 'aan' WSP 'de' WSP 'elfproef'        ; // §13.4.15 elfproef
NUMERIEK_IS_MET_EXACT  : 'numeriek' WSP 'is' WSP 'met' WSP 'exact'   ; // §13.4.15.32 toplevel tekst (verb infixed)
NUMERIEK_ZIJN_MET_EXACT: 'numeriek' WSP 'zijn' WSP 'met' WSP 'exact' ; // §13.4.15.33 toplevel tekst meervoud
NUMERIEK_MET_EXACT: 'numeriek' WSP 'met' WSP 'exact'     ; // §13.4.15.84 non-toplevel (is numeriek)

// --- condition phrases ---
// §5.8.2 leegwaarde fallback on a "som van": "… of 0 als die er niet zijn".
ALS_DIE_ER_NIET_ZIJN  : 'als' WSP 'die' WSP 'er' WSP 'niet' WSP 'zijn' ;
GEDURENDE_DE_TIJD_DAT : 'gedurende' WSP 'de' WSP 'tijd' WSP 'dat' ; // §13.4.17.55
// §13.4.15.70 vragende-vorm negatie: the "niet" must lex WITH "gedurende … gehele" as one token, else the greedy
// onderwerp kaleNaam (NIET is a kaleNaamWoord, for "is niet <kenmerk>") swallows the leading "niet" of the §8.2
// qualifier. Longest-match keeps "niet <kenmerk>" (no "gedurende … gehele" follows) on the bare NIET token.
NIET_GEDURENDE_HET_GEHELE : 'niet' WSP 'gedurende' WSP 'het' WSP 'gehele' ; // §13.4.15.70
NIET_GEDURENDE_DE_GEHELE  : 'niet' WSP 'gedurende' WSP 'de' WSP 'gehele'  ; // §13.4.15.70
GEDURENDE_HET_GEHELE  : 'gedurende' WSP 'het' WSP 'gehele'  ; // §13.4.15.70
GEDURENDE_DE_GEHELE   : 'gedurende' WSP 'de' WSP 'gehele'   ; // §13.4.15.70
VOLGENDE_VOORWAARDEN  : 'volgende' WSP 'voorwaarden'  ; // §13.4.14
VOLGENDE_VOORWAARDE   : 'volgende' WSP 'voorwaarde'   ; // §13.4.13
GEEN_VAN_DE           : 'geen' WSP 'van' WSP 'de'           ; // §13.4.14 quantifier
// 'ten minste'; a §7 worked example writes it closed up as 'tenminste'.
TEN_MINSTE            : 'ten' WSP 'minste' | 'tenminste' ; // §13.4.14 quantifier
TEN_HOOGSTE           : 'ten' WSP 'hoogste'           ; // §13.4.14 quantifier

// --- scenariotoetsen (DOCUMENTED EXTENSION — see docs/SCENARIOTOETSEN.md and the
//     '§Scenariotoetsen' section of docs/grammar-divergences.md). v2.3.0 has NO test
//     chapter; these forms are reachable ONLY via the `testDocument` start rule and are
//     inert on production input. Each surface form is a WHOLE-PHRASE token (WSP-joined),
//     so it wins by maximal munch ONLY when the complete phrase is present — none of the
//     phrases below occurs anywhere in the 193-file corpus (grepped whitespace-collapsed).
//     Longest / most-specific phrases first, all before the ID tokens.
//     Deliberately NO token for 'is geen'/'heeft geen': `geen` must keep lexing as a
//     plain LOWER_ID inside a kaleNaam so the DOCUMENTED production condition path
//     `<subject> is/heeft geen <kenmerk/rol>` (grammar-divergences 'negated membership')
//     is untouched; the verwachting reuses `IS/HEEFT kaleNaam` with the `geen`-prefix
//     absorbed and the resolver strips it (mirrors that production path exactly).
TOETST_DE_REGELGROEP : 'toetst' WSP 'de' WSP 'regelgroep' ; // scope: één regelgroep
TOETST_DE_REGELS     : 'toetst' WSP 'de' WSP 'regels'     ; // scope: meerdere regels (,/en-lijst)
TOETST_DE_REGEL      : 'toetst' WSP 'de' WSP 'regel'      ; // scope: één regel
GEBASEERD_OP_DE_SITUATIE : 'gebaseerd' WSP 'op' WSP 'de' WSP 'situatie' ; // herbruikbare-situatie-verwijzing
GEGEVEN_IS_DE_VOLGENDE_SITUATIE : 'Gegeven' WSP 'is' WSP 'de' WSP 'volgende' WSP 'situatie:' ; // inline situatiesectie-opener (colon folded in)
MET_ALS_VERWACHT_RESULTAAT : 'Met' WSP 'als' WSP 'verwacht' WSP 'resultaat:' ; // verwachtingsectie-opener (colon folded in)
MET_PEILDATUM        : 'met' WSP 'peildatum'  ; // §4.2 rekendatum-global voor het scenario
DE_GEGEVENS_ZIJN     : 'de' WSP 'gegevens' WSP 'zijn' ; // consistentie-verwachting opener
DE_PARAMETER         : 'de' WSP 'parameter'   ; // ongebullette parameterwaarde in een situatieblok
SCENARIO             : 'Scenario'             ; // scenario-kop
SITUATIE             : 'Situatie'             ; // herbruikbare-situatie-kop
CONSISTENT           : 'consistent'           ; // 'de gegevens zijn consistent' (INCONSISTENT bestaat al)

// --- GegevensSpraak phrases ---
EXTENSIE_VAN_OBJECTTYPE : 'Extensie' WSP 'van' WSP 'objecttype'         ; // §13.3.1.2
DATUM_TIJD_MILLIS       : 'Datum' WSP 'en' WSP 'tijd' WSP 'in' WSP 'millisecondes'  ; // §13.3.3.6
DATUM_IN_DAGEN          : 'Datum' WSP 'in' WSP 'dagen'                  ; // §13.3.3.6
WEDERKERIG_FEITTYPE     : 'Wederkerig' WSP 'feittype'             ; // §13.3.9.2
IS_VAN_HET_TYPE         : 'is' WSP 'van' WSP 'het' WSP 'type'                 ; // §13.3.4.1
MET_EENHEID             : 'met' WSP 'eenheid'                     ; // §13.3.2.5
GEDIMENSIONEERD_MET     : 'gedimensioneerd' WSP 'met'             ; // §13.3.2.5
// §13.3.3.7 'geheel getal'; spec examples also write it closed up as 'geheelgetal'.
GEHEEL_GETAL            : 'geheel' WSP 'getal' | 'geheelgetal'    ;
NIET_NEGATIEF           : 'niet-negatief'                   ; // §13.3.3.7
BESTAANDE_UIT           : 'bestaande' WSP 'uit' WSP 'de'                ; // §13.3.7.1
DAARBIJ_GELDT           : 'Daarbij' WSP 'geldt'                   ; // §13.4.2.10 (trailing ':' is COLON)

// --- tijdlijn phrases ---
HEEFT_TIJDVAKKEN_VAN   : 'heeft' WSP 'tijdvakken' WSP 'van'  ; // §13.3.6.10
MET_VARIABEL_STARTPUNT : 'met' WSP 'variabel' WSP 'startpunt'; // §13.3.6.10
STARTEND_OP_DAG        : 'startend' WSP 'op' WSP 'dag'       ; // §13.3.6.4/.5/.6
STARTEND_OP            : 'startend' WSP 'op'           ; // §13.3.6.3
VAN_DE_MAAND           : 'van' WSP 'de' WSP 'maand'          ; // §13.3.6.4
VAN_MAAND              : 'van' WSP 'maand'             ; // §13.3.6.5/.6
ELKE_DAG     : 'elke' WSP 'dag'     ; // §13.3.6.2
ELKE_WEEK    : 'elke' WSP 'week'    ; // §13.3.6.2
ELKE_MAAND   : 'elke' WSP 'maand'   ; // §13.3.6.2
ELK_KWARTAAL : 'elk' WSP 'kwartaal' ; // §13.3.6.2
ELK_JAAR     : 'elk' WSP 'jaar'     ; // §13.3.6.2

// --- rangtelwoord (§13.2.12) ---
ALS_TWEEDE_GENOEMDE : 'als' WSP 'tweede' WSP 'genoemde' ;
ALS_DERDE_GENOEMDE  : 'als' WSP 'derde' WSP 'genoemde'  ;
ALS_VIERDE_GENOEMDE : 'als' WSP 'vierde' WSP 'genoemde' ;
EERSTGENOEMDE       : 'eerstgenoemde'       ;
LAATSTGENOEMDE      : 'laatstgenoemde'      ;
E_GENOEMDE          : 'e' WSP 'genoemde'          ;

// ============================================================================
// Literals (§13.2.3-13.2.21). DATE precedes NUMBER so a dotted date is one
// token, not three signed numbers.
// ============================================================================
DATE
    : ( 'dd.' ' '+ )? DIGIT+ '-' DIGIT+ '-' DIGIT DIGIT DIGIT DIGIT
      ( ' '+ DIGIT DIGIT ':' DIGIT DIGIT ':' DIGIT DIGIT '.' DIGIT DIGIT DIGIT )?
    ; // §13.2.19 dedato / §13.2.20 datumwaarde (+ optional §13.2.21 tijdwaarde)

NUMBER
    : '-'? DIGIT+ ( ',' DIGIT+                        // §13.2.4 decimaalgetal
                  | ( '_' DIGIT+ )? '/' [1-9] DIGIT* )? // §13.2.5 rationeelgetal: denominator is
    ; // bare form = §13.2.3 geheelgetal       // POSITIVE_DIGIT, so "1/0" is not a number (it
      // splits into NUMBER SLASH NUMBER and the parser rejects it) rather than lowering to a
      // fire-time divide-by-zero.

// §13.2.15 tekstwaarde + §5.4/§13.4.17.9 tekstenwaardereeks. A '"' opens TEKST_MODE, which splits the quoted span
// into literal STRING_TEXT chunks and «expressie» escapes (L_ANGLE_QUOTE re-enters DEFAULT_MODE for the embedded
// expression, R_ANGLE_QUOTE returns). A plain "…" (no guillemets) re-tokenises identically: STRING_START STRING_TEXT?
// STRING_END, which the parser reassembles into the same tekstWaarde — so the corpus DRL stays byte-identical.
STRING_START : '"' -> pushMode(TEKST_MODE) ;
ENUM_LITERAL   : '\'' ~['\r\n]* '\'' | '’' ~[‘\r\n]* '‘' ; // §13.2.14 (straight ' or typographic)

// ============================================================================
// Single-word keywords (all BEFORE the ID tokens).
// ============================================================================

// --- top-level definition keywords ---
OBJECTTYPE     : 'Objecttype'     ;
DOMEIN         : 'Domein'         ;
DIMENSIE       : 'Dimensie'       ;
EENHEIDSYSTEEM : 'Eenheidsysteem' ;
PARAMETER      : 'Parameter'      ;
FEITTYPE       : 'Feittype'       ;
DAGSOORT       : 'Dagsoort'       ;
TIJDLIJN       : 'Tijdlijn'       ;
// REGELGROEP is a DOCUMENTED EXTENSION (v2.3.0 §9.10 is prose-only; see docs/grammar-divergences.md).
// Listed BEFORE REGEL so maximal-munch prefers the longer 'Regelgroep' keyword over 'Regel' + a name word.
REGELGROEP     : 'Regelgroep'     ;
REGEL          : 'Regel'          ;
BESLISTABEL    : 'Beslistabel'    ;
ENUMERATIE     : 'Enumeratie'     ;

// --- datatypes ---
NUMERIEK   : 'Numeriek'   ;
PERCENTAGE : 'Percentage' ;
TEKST      : 'Tekst'      ;
BOOLEAN    : 'Boolean'    ;

// --- rule structure / verbs ---
GELDIG  : 'geldig'  ; // the spec version keyword is lowercase; a regelnaam may itself begin "Geldig …"
// A separate capitalised token: §4 worked examples open later versions of a rule
// with 'Geldig'. Kept distinct from GELDIG so a regelnaam may still START with
// "Geldig …" (regelNaam excludes only lowercase GELDIG); the parser's `versie`
// accepts either case, so a sentence-initial capitalised version keyword parses.
GELDIG_CAP : 'Geldig' ;
ALTIJD  : 'altijd'  ;
INDIEN  : 'indien'  ;
MOETEN  : 'moeten'  ;
MOET    : 'moet'    ;
HEBBEN  : 'hebben'  ;
HEEFT   : 'heeft'   ;
ZIJN    : 'zijn'    ;
IS      : 'is'      ;
VOLDOET : 'voldoet' ;
VOLDOEN : 'voldoen' ;
// RECURSIEF marks a §9.10 Regelgroep recursief (DOCUMENTED EXTENSION — see docs/grammar-divergences.md).
// Lowercase, only ever read in the group header "is recursief"; not a name word, so it never mis-splits a name.
RECURSIEF : 'recursief' ;

// --- comparison: state predicates / atomic operator words (see header note 3) ---
GELIJK   : 'gelijk'   ;
ONGELIJK : 'ongelijk' ;
GROTER   : 'groter'   ;
KLEINER  : 'kleiner'  ;
LATER    : 'later'    ;
EERDER   : 'eerder'   ;
LEEG     : 'leeg'     ;
GEVULD   : 'gevuld'   ;
GEVUURD  : 'gevuurd'  ; // §13.4.15.66 (likely a spec typo for 'gevuld'; kept verbatim)
INCONSISTENT : 'inconsistent' ;
UNIEK    : 'uniek'    ;
DAN      : 'dan'      ;
AAN      : 'aan'      ;

// --- arithmetic ---
PLUS : 'plus' ;
MIN  : 'min'  ;
MAAL : 'maal' ;

// --- rounding modes / numeric detail ---
REKENKUNDIG : 'rekenkundig' ;
DECIMALEN   : 'decimalen'   ;
CIJFERS     : 'cijfers'     ;

// --- quantifiers / counts ---
ALLE     : 'alle'     ;
AL       : 'al'       ; // §13.4.14.5 kwantificatie (distinct from ALLE)
MEERDERE : 'meerdere' ;
PRECIES  : 'precies'  ;
EEN_TELWOORD  : 'één' | 'Eén' | 'Één' ; // §13.2 telwoord 'one' (cardinality), distinct from article 'een'
TWEE_TELWOORD : 'twee'  ;
DRIE_TELWOORD : 'drie'  ;
VIER_TELWOORD : 'vier'  ;

// --- enum / domain / spec detail ---
GETAL    : 'getal'    ;
NEGATIEF : 'negatief' ;
POSITIEF : 'positief' ;
AANTAL_WORD : 'aantal' ; // §13.4.17.47 telling: aggregation 'het aantal' = HET AANTAL_WORD

// --- timeline units (plural, §13.3.6.8) ---
DAGEN     : 'dagen'     ;
WEKEN     : 'weken'     ;
MAANDEN   : 'maanden'   ;
KWARTALEN : 'kwartalen' ;
JAREN     : 'jaren'     ;

// --- distribution detail ---
AFNEMENDE  : 'afnemende'  ;
TOENEMENDE : 'toenemende' ;

// --- references / built-ins ---
REKENDATUM : 'Rekendatum' ;
REKENJAAR  : 'Rekenjaar'  ;
REGELVERSIE: 'regelversie' ;
HIJ        : 'hij'        ;

// --- kenmerk keyword ---
KENMERK : 'kenmerk' ;

// --- prepositions / articles / connectors / common words ---
VANAF   : 'vanaf'   ;
VAN     : 'van'     ;
VOOR    : 'voor'    ;
OVER    : 'over'    ;
TOT     : 'tot'     ;
TM      : 't/m'     ;
PER     : 'per'     ;
MET     : 'met'     ;
BIJ     : 'bij'     ;
UIT     : 'uit'     ;
IN      : 'in'      ;
OP      : 'op'      ;
OF      : 'of'      ;
EN      : 'en'      ;
ER      : 'er'      ;
DAT     : 'dat'     ;
DIE     : 'die'     ;
ELKE    : 'elke'    ;
NIET    : 'niet'    ;
ALS     : 'als'     ;
DE      : 'de' | 'De'  ; // sentence-initial article is capitalised (see header note 5)
HET     : 'het' | 'Het';
EEN     : 'een' | 'Een';
WAAR    : 'waar'    ;
ONWAAR  : 'onwaar'  ;

// --- date/time unit words (singular; also used as labels after a comma) ---
DAG          : 'dag'          ;
WEEK         : 'week'         ;
MAAND        : 'maand'        ;
KWARTAAL     : 'kwartaal'     ;
JAAR         : 'jaar'         ;
UUR          : 'uur'          ;
MINUUT       : 'minuut'       ;
SECONDE      : 'seconde'      ;
MILLISECONDE : 'milliseconde' ;
PERIODE      : 'periode'      ;
HELE         : 'hele'         ; // §13.4.17.32 'in hele <eenheid>' (when not fused as IN_HELE)

// ============================================================================
// Operators and punctuation. Multi-char before single-char (---, .., before -, .).
// ============================================================================
KOPTEKST   : '---' ~[\r\n]* ; // §13.3.1.5 koptekst — a heading line; consumes to end-of-line
                              // so the free-text heading cannot run into the next member once WS is skipped
DOTDOT     : '..'  ; // nested bullet
BULLET     : '•' | '▪' ; // EBNF/spec bullet glyphs (• and ▪)
LPAREN     : '('   ;
RPAREN     : ')'   ;
LBRACE     : '{'   ;
RBRACE     : '}'   ;
// «  is defined in TEKST_MODE (it only opens an «expressie» escape INSIDE a string, §5.4); »  closes that escape,
// popping the pushed DEFAULT_MODE back to TEKST_MODE — but ONLY when a mode was actually pushed (the stack is
// non-empty). A top-level » or « (malformed input outside any string) is then just an ordinary token the parser
// rejects with an honest syntax error, instead of underflowing the mode stack (an uncaught EmptyStackException
// that crashed the whole resolver — adversarial-review regression).
R_ANGLE_QUOTE : '»' { if (!_modeStack.isEmpty()) popMode(); } ; // >> closes a «expressie» escape
COMMA      : ','   ;
DOT        : '.'   ;
COLON      : ':'   ;
SEMICOLON  : ';'   ;
DASH       : '-'   ; // bullet (top level) / leftover minus
SLASH      : '/'   ;
EQUALS     : '='   ;
PERCENT_SIGN : '%' ;
CARET      : '^'   ;
EURO       : '€' ; // unit abbreviation
APOSTROPHE : '\''  ;

// ============================================================================
// §12 BESLISTABEL table body — the inline markdown pipe-table (Option A surface
// syntax). '|' occurs NOWHERE ELSE in RegelSpraak, so the first '|' unambiguously
// opens a decision-table row block. A whole row is captured as ONE TABEL_RIJ token
// (cells never contain '|', per §12); the resolver splits it on '|' and hands the
// grid to BeslistabelDesugar. Newlines are globally skipped (header note 1), so the
// TABEL_MODE sub-mode (defined at the end) re-introduces line structure locally:
// each further pipe line is another TABEL_RIJ, and the first newline NOT followed
// by a pipe line pops back to DEFAULT_MODE. A '|' inside a "…" string stays part of
// STRING_TEXT (TEKST_MODE), so a string never triggers the table.
// ============================================================================
TABEL_RIJ : '|' ~[\r\n]* -> pushMode(TABEL_MODE) ;

// ============================================================================
// Identifiers — single words, split by case so the parser can use the
// capitalisation convention as a name boundary (see header note 2). UPPER_ID is
// listed first; LOWER_ID accepts any letter start but is only reached for
// non-uppercase starts (ties on an uppercase word go to UPPER_ID).
// ============================================================================
UPPER_ID : UPPER ( LETTER | DIGIT | '_' | '-' | '\'' )* ; // capitalised name word (objecttype/domein/datatype names)
LOWER_ID : LETTER ( LETTER | DIGIT | '_' | '-' | '\'' )* ; // lowercase name word (attribute/role/parameter names)

fragment WSP    : [ \t\r\n]+ ; // inter-word whitespace inside multi-word keywords (tolerates line wraps)
fragment UPPER  : [\p{Lu}\p{Lt}] ; // §13.2.6 uppercase / titlecase letter
fragment LETTER : [\p{L}]        ; // §13.2.6 letter (Unicode, incl. accented)
fragment DIGIT  : [0-9]          ; // §13.2.1 digit

// ============================================================================
// Skipped: whitespace (incl. newlines, see header note 1). Line comments go to the
// HIDDEN channel (not skipped) so the parser still ignores them, but the resolver can
// gather the `// Bron: <wetsartikel>` statuutannotaties written directly above a rule
// (BufferedTokenStream.getHiddenTokensToLeft) for uitleg to cite. Whitespace stays
// skipped, so the only hidden tokens are comments.
// ============================================================================
WS           : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> channel(HIDDEN) ;

// ============================================================================
// §5.4/§13.4.17.9 string interior (entered from STRING_START). A plain "…" is
// STRING_START STRING_TEXT? STRING_END; an «expressie» escape re-enters DEFAULT_MODE
// for the embedded expression and R_ANGLE_QUOTE pops back here.
// ============================================================================
mode TEKST_MODE;
STRING_TEXT   : ~["«»\r\n]+ ;                    // a karakterreeks chunk (inner spaces preserved)
L_ANGLE_QUOTE : '«' -> pushMode(DEFAULT_MODE) ;  // «expressie» — the embedded expr lexes in DEFAULT_MODE
STRING_END    : '"' -> popMode ;

// ============================================================================
// §12 beslistabel pipe-table capture (entered from TABEL_RIJ in DEFAULT_MODE).
// Each further pipe line is the SAME TABEL_RIJ token (re-typed); a newline NOT
// followed by a pipe line ends the table and returns to DEFAULT_MODE. The longest
// match decides: when a pipe row follows, TABEL_RIJ_VERVOLG (which also consumes
// the line) outruns TABEL_EINDE (the bare break), so blank lines between rows are
// tolerated; when no pipe row follows, only TABEL_EINDE matches.
// ============================================================================
mode TABEL_MODE;
TABEL_RIJ_VERVOLG : ('\r'? '\n')+ [ \t]* '|' ~[\r\n]* -> type(TABEL_RIJ) ; // next data/separator row
TABEL_EINDE       : ('\r'? '\n')+                     -> popMode, skip ;   // table over -> back to DEFAULT_MODE
