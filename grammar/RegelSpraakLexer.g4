// RegelSpraakLexer.g4 — terminals for RegelSpraak v2.3.0.
//
// Derived from the specification in specification/v2.3.0/ (the oracle):
//   - §13.2 syntaxdiagrammen  → literals, numbers, names, dates
//   - the ANTLR-targeted draft → keyword inventory (cross-checked, not copied)
// The draft (RegelSpraak-spec-for-ANTLR-v2.3.0.md §2) is a buildable-but-buggy
// starting point; where it contradicts the authoritative diagrams or the TOKA
// casus, the diagrams/casus win. The reconciliations below are deliberate:
//
//  1. WHITESPACE IS SKIPPED ENTIRELY (incl. newlines). The draft sprinkles a
//     `NEWLINE?` token through its parser yet its own §2.5 skips '\n' in WS — a
//     contradiction. Worse, real rules wrap mid-sentence across physical lines
//     (TOKA §3.1), so newlines cannot be significant. Structure is delimited by
//     ';' (members), '.' (rule/condition end), bullet markers, and keywords.
//
//  2. NAMES ARE SPLIT BY CASE into UPPER_ID and LOWER_ID. A space-bearing
//     identifier would, under maximal munch, greedily swallow following keywords
//     and even whole sentences. So multi-word names are assembled in the PARSER
//     from single-word tokens. RegelSpraak's documented convention — objecttype/
//     domain/datatype names are Capitalised, attribute/role/parameter names are
//     lowercase — makes capitalisation a reliable, grammar-usable boundary: it is
//     what lets "de belasting op basis van afstand Bedrag" split into the
//     lowercase attribute name and the capitalised domain, and "de reis Vlucht"
//     into role and objecttype, with no symbol table.
//
//  3. COMPARISON OPERATORS ARE ATOMIC WORDS (gelijk, ongelijk, groter, kleiner,
//     later, eerder, of, aan, dan). The draft inconsistently mixes single tokens
//     ('gelijk aan') with split forms; both surface forms occur in TOKA
//     ("X kleiner is dan Y" toplevel vs "X is groter of gelijk aan Y" nested).
//     Atomic words let the parser compose every form without prefix clashes.
//
//  4. BULLETS ARE '-' (top level) AND '..' (nested), as the TOKA casus writes
//     them; the EBNF's '•' glyph is honoured too but TOKA is ground truth.
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
ER_MOET_WORDEN_VOLDAAN_AAN : 'er' WSP 'moet' WSP 'worden' WSP 'voldaan' WSP 'aan'    ; // §13.4.8.7
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
CONCATENATIE_VAN       : 'de' WSP 'concatenatie' WSP 'van'       ; // §13.4.8.14
TIJDSEVENREDIG_DEEL_PER: 'het' WSP 'tijdsevenredig' WSP 'deel' WSP 'per'; // §13.4.17.58
// 'in hele <eenheid>' (§13.4.17.32) is composed in the parser as IN HELE <unit>,
// so 'in' and 'hele' stay separate tokens (the bare-unit form is just 'in <unit>').
// 'aantal' is its own word (the telling aggregation 'het aantal' is composed in
// the parser as HET AANTAL_WORD), so the article stays separable from an
// attribute name that merely begins with "aantal" (e.g. "het aantal treinmiles").
// The longer 'het aantal dagen in' phrase is still matched first.

// --- bounding / rounding / arithmetic phrases ---
MET_EEN_MINIMUM_VAN : 'met' WSP 'een' WSP 'minimum' WSP 'van' ; // §13.4.17.29
MET_EEN_MAXIMUM_VAN : 'met' WSP 'een' WSP 'maximum' WSP 'van' ; // §13.4.17.30
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
BIJ_EVEN_GROOT_CRITERIUM   : 'bij' WSP 'een' WSP 'even' WSP 'groot' WSP 'criterium'; // §13.4.10.5 (TOKA includes 'een')
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
GEDURENDE_DE_TIJD_DAT : 'gedurende' WSP 'de' WSP 'tijd' WSP 'dat' ; // §13.4.17.55
GEDURENDE_HET_GEHELE  : 'gedurende' WSP 'het' WSP 'gehele'  ; // §13.4.15.70
GEDURENDE_DE_GEHELE   : 'gedurende' WSP 'de' WSP 'gehele'   ; // §13.4.15.70
VOLGENDE_VOORWAARDEN  : 'volgende' WSP 'voorwaarden'  ; // §13.4.14
VOLGENDE_VOORWAARDE   : 'volgende' WSP 'voorwaarde'   ; // §13.4.13
GEEN_VAN_DE           : 'geen' WSP 'van' WSP 'de'           ; // §13.4.14 quantifier
TEN_MINSTE            : 'ten' WSP 'minste'            ; // §13.4.14 quantifier
TEN_HOOGSTE           : 'ten' WSP 'hoogste'           ; // §13.4.14 quantifier

// --- GegevensSpraak phrases ---
EXTENSIE_VAN_OBJECTTYPE : 'Extensie' WSP 'van' WSP 'objecttype'         ; // §13.3.1.2
DATUM_TIJD_MILLIS       : 'Datum' WSP 'en' WSP 'tijd' WSP 'in' WSP 'millisecondes'  ; // §13.3.3.6
DATUM_IN_DAGEN          : 'Datum' WSP 'in' WSP 'dagen'                  ; // §13.3.3.6
WEDERKERIG_FEITTYPE     : 'Wederkerig' WSP 'feittype'             ; // §13.3.9.2
IS_VAN_HET_TYPE         : 'is' WSP 'van' WSP 'het' WSP 'type'                 ; // §13.3.4.1
MET_EENHEID             : 'met' WSP 'eenheid'                     ; // §13.3.2.5
GEDIMENSIONEERD_MET     : 'gedimensioneerd' WSP 'met'             ; // §13.3.2.5
GEHEEL_GETAL            : 'geheel' WSP 'getal'                    ; // §13.3.3.7
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
                  | ( '_' DIGIT+ )? '/' DIGIT+ )?     // §13.2.5 rationeelgetal
    ; // bare form = §13.2.3 geheelgetal

STRING_LITERAL : '"' ~["\r\n]* '"' ;                       // §13.2.15 tekstwaarde
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
REGEL          : 'Regel'          ;
BESLISTABEL    : 'Beslistabel'    ;
ENUMERATIE     : 'Enumeratie'     ;

// --- datatypes ---
NUMERIEK   : 'Numeriek'   ;
PERCENTAGE : 'Percentage' ;
TEKST      : 'Tekst'      ;
BOOLEAN    : 'Boolean'    ;

// --- rule structure / verbs ---
GELDIG  : 'geldig'  ;
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
DOTDOT     : '..'  ; // nested bullet (TOKA)
BULLET     : '•' ; // EBNF bullet glyph
LPAREN     : '('   ;
RPAREN     : ')'   ;
LBRACE     : '{'   ;
RBRACE     : '}'   ;
L_ANGLE_QUOTE : '«' ; // <<
R_ANGLE_QUOTE : '»' ; // >>
COMMA      : ','   ;
DOT        : '.'   ;
COLON      : ':'   ;
SEMICOLON  : ';'   ;
DASH       : '-'   ; // bullet (TOKA top level) / leftover minus
SLASH      : '/'   ;
EQUALS     : '='   ;
PERCENT_SIGN : '%' ;
CARET      : '^'   ;
EURO       : '€' ; // unit abbreviation
APOSTROPHE : '\''  ;

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
// Skipped: whitespace (incl. newlines, see header note 1) and line comments.
// ============================================================================
WS           : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
