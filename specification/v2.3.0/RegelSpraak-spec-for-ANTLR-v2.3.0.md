# RegelSpraak Grammar Specification for ANTLR v4

**Structure:**

1.  **Introduction:** Overview and scope.
2.  **Lexer Specification:** Defining the tokens (keywords, identifiers, literals, operators, etc.) based on EBNF terminals.
3.  **Parser Specification:** Defining the grammatical structure (how tokens combine) based on EBNF non-terminals.
4.  **Illustrative Examples:** Showing RegelSpraak snippets and how the EBNF-based grammar rules apply.
5.  **Handling Specific Constructs:** Notes on complex areas like expressions, conditions, units, identifiers, and beslistabellen.

---

## 1. Introduction

This document specifies the grammar for RegelSpraak v2.3.0 in a format intended for use with ANTLR v4. It translates the concepts and EBNF provided in Chapter 13 of the "RegelSpraak-specificatie v2.3.0.md" document into distinct lexer and parser rules.

The goal is to define a grammar (`.g4` file) that can faithfully parse files containing GegevensSpraak definitions (Objecttypes, Parameters, Dimensies, Eenheden, Feittypes, Dagsoorten, Tijdlijnen, etc.) and RegelSpraak rules (Regels, Beslistabellen), accurately reflecting the structure defined in the official EBNF and respecting the natural language style where feasible.

**Scope:** This specification focuses on the syntactic structure defined in the RegelSpraak EBNF (Chapter 13). Semantic validation (e.g., type checking, ensuring referenced names exist, validating unit compatibility beyond basic structure, ensuring correct arity of operations) is considered out of scope for the parser itself and should be handled *after* parsing, typically using ANTLR listeners or visitors operating on the generated parse tree.

**What changed since v2.1.0 (per Chapter 13 syntaxdiagrammen v2.3.0):**
*   **§13.2.12 Rangtelwoord** added (ordinal selector). All subsequent §13.2 labels shifted by one and the v2.1.0 duplicate "§13.2.13" was repaired (Waarde=13, Enumeratiewaarde=14, … Meervoudsvorm=31).
*   **§13.3.1.2 Extensie van objecttype** added; the remaining §13.3.1 sub-rules shifted by one.
*   **§13.3.6 Tijdlijnen** expanded from one rule into ten (single/plural periods, fixed start points, named timelines, timeline definitions).
*   **§13.4.1 Onderwerpketen** split into Onderwerpketen / Universeel onderwerp / Onderwerpverwijzing, threading `<rangtelwoord>` and `<attribuutnaam>` through subject references.
*   **§13.4.2.7 Selector** and **§13.4.2.9 Attribuut van onderwerp** changed to admit attribute references and ordinals.
*   **§13.4.12 Startpuntbepaling** added as a new section and as a new `<resultaatdeel>` alternative; this shifts the §13.4 subsection numbering: Voorwaardendeel §13.4.13, Samengestelde voorwaarde §13.4.14, Elementaire voorwaarde §13.4.15, Berekening §13.4.16, Expressie §13.4.17.
*   **§13.4.17 Expressie** added datetime extraction functions (`uur uit`, `minuut uit`, `seconde uit`, `milliseconde uit`), a `datumtijd met` constructor, and changed the surface syntax of `datum met` and `eerste paasdag van`.

**ANTLR Conventions:**
*   Parser rule names start with a lowercase letter (e.g., `regel`). These generally correspond to EBNF non-terminals (e.g., `<regel>`).
*   Lexer rule names start with an uppercase letter (e.g., `REGEL`, `IDENTIFIER`). Keywords are specific lexer rules matching EBNF terminals in quotes (e.g., `'Regel'`).
*   Literals (keywords, operators defined in EBNF) are enclosed in single quotes in the ANTLR grammar file (e.g., `'Regel'`, `'plus'`).
*   Whitespace is generally skipped using a dedicated lexer rule (`WS`).
*   Comments, if defined in the language, are also typically skipped.

---

## 2. Lexer Specification

The lexer tokenizes the input character stream based on the terminal symbols and basic patterns derived from the EBNF (especially §13.2). Keywords must be defined before the generic `IDENTIFIER` rule. Longer keywords that might contain shorter keywords should generally be defined first.

**2.1 Keywords:** Reserved words or fixed phrases corresponding to EBNF terminals in quotes.

```antlr
// --- Longest Multi-Word Keywords First ---
DE_DATUMTIJD_MET_JAAR: 'de datumtijd met jaar:'; // §13.4.17.36
DE_DATUM_MET_JAAR: 'de datum met jaar:'; // §13.4.17.35
COMMA_MILLISECONDE: ' en milliseconde:'; // §13.4.17.36
COMMA_SECONDE: ', seconde:'; // §13.4.17.36
COMMA_MINUUT: ', minuut:'; // §13.4.17.36
COMMA_UUR: ', uur:'; // §13.4.17.36
COMMA_DAG: ', dag:'; // §13.4.17.36
COMMA_MAAND: ', maand:'; // §13.4.17.35 / §13.4.17.36
EN_DAG: ' en dag:'; // §13.4.17.35
VOOR_HET_ATTRIBUUT_ZONDER_VOORZETSEL: '(voor het attribuut zonder voorzetsel):'; // §13.3.7
NA_HET_ATTRIBUUT_MET_VOORZETSEL: '(na het attribuut met voorzetsel'; // §13.3.7
EXTENSIE_VAN_OBJECTTYPE: 'Extensie van objecttype'; // §13.3.1.2
DATUM_TIJD_MILLIS: 'Datum en tijd in millisecondes'; // §13.3.3
GEDURENDE_DE_TIJD_DAT: 'gedurende de tijd dat'; // §13.4.17.55
GEDURENDE_HET_GEHELE: 'gedurende het gehele'; // §13.4.15.70
GEDURENDE_DE_GEHELE: 'gedurende de gehele'; // §13.4.15.70 (maand)
HET_STARTPUNT_VAN: 'Het startpunt van'; // §13.4.12.1
WORDT_BEPAALD_DOOR: 'wordt bepaald door'; // §13.4.12.1
WORDT_BEREKEND_ALS: 'moet berekend worden als'; // §13.4.4
WORDT_GESTELD_OP: 'moet gesteld worden op';     // §13.4.4
WORDT_GEINITIALISEERD_OP: 'moet geïnitialiseerd worden op'; // §13.4.9
ER_MOET_WORDEN_VOLDAAN_AAN: 'er moet worden voldaan aan'; // §13.4.8
ABSOLUTE_TIJDSDUUR_VAN: 'de absolute tijdsduur van'; // §13.4.17.32
ABSOLUTE_WAARDE_VAN: 'de absolute waarde van'; // §13.4.17.17
MAXIMALE_WAARDE_VAN: 'de maximale waarde van'; // §13.4.17.47
MINIMALE_WAARDE_VAN: 'de minimale waarde van'; // §13.4.17.47
TIJDSEVENREDIG_DEEL_PER: 'het tijdsevenredig deel per'; // §13.4.17.58
ALS_ONVERDEELDE_REST_BLIJFT: 'Als onverdeelde rest blijft';
MET_EEN_MINIMUM_VAN: 'met een minimum van'; // §13.4.17.29
MET_EEN_MAXIMUM_VAN: 'met een maximum van'; // §13.4.17.30
GROTER_OF_GELIJK_AAN: 'groter of gelijk aan';
KLEINER_OF_GELIJK_AAN: 'kleiner of gelijk aan';
LATER_OF_GELIJK_AAN: 'later of gelijk aan';
EERDER_OF_GELIJK_AAN: 'eerder of gelijk aan';
WAARBIJ_WORDT_VERDEELD: ', waarbij wordt verdeeld'; // Handle comma §13.4.10
BESTAANDE_UIT: ', bestaande uit de'; // §13.3.7 (Handle comma)
WEDERKERIG_FEITTYPE: 'Wederkerig feittype'; // §13.3.9
IS_VAN_HET_TYPE: 'is van het type'; // §13.3.4
CONCATENATIE_VAN: 'de concatenatie van'; // §13.4.8
AANTAL_DAGEN_IN: 'het aantal dagen in';       // §13.4.17.57
EERSTE_PAASDAG_VAN: 'de eerste paasdag van'; // §13.4.17.37
VOLGEND_CRITERIUM: 'het volgende criterium:'; // §13.4.8
VOLGENDE_CRITERIA: 'volgende criteria:';     // §13.4.8
BIJ_EVEN_GROOT_CRITERIUM: 'bij even groot criterium'; // §13.4.10
OP_VOLGORDE_VAN: 'op volgorde van'; // §13.4.10
NAAR_RATO_VAN: 'naar rato van'; // §13.4.10
WORDT_VOLDAAN_AAN: 'wordt voldaan aan'; // §13.4.13
NUMERIEK_MET_EXACT: 'numeriek met exact'; // §13.4.15.32 etc.
AAN_DE_ELFPROEF: 'aan de elfproef'; // §13.4.15.20 etc.

// --- Datetime extraction functions (§13.4.17.21-24) ---
HET_UUR_UIT: 'het uur uit';             // §13.4.17.21
DE_MINUUT_UIT: 'de minuut uit';         // §13.4.17.22
DE_SECONDE_UIT: 'de seconde uit';       // §13.4.17.23
DE_MILLISECONDE_UIT: 'de milliseconde uit'; // §13.4.17.24

// --- Rangtelwoord (§13.2.12) ---
ALS_TWEEDE_GENOEMDE: 'als tweede genoemde'; // §13.2.12
ALS_DERDE_GENOEMDE: 'als derde genoemde';   // §13.2.12
ALS_VIERDE_GENOEMDE: 'als vierde genoemde'; // §13.2.12
EERSTGENOEMDE: 'eerstgenoemde';             // §13.2.12
LAATSTGENOEMDE: 'laatstgenoemde';           // §13.2.12
ALS: 'als';                                 // §13.2.12 ("als" <geheelgetal> "e genoemde")
E_GENOEMDE: 'e genoemde';                   // §13.2.12

// --- Tijdlijnen (§13.3.6) ---
HEEFT_TIJDVAKKEN_VAN: 'heeft tijdvakken van'; // §13.3.6.10
MET_VARIABEL_STARTPUNT: 'met variabel startpunt'; // §13.3.6.10
STARTEND_OP_DAG: 'startend op dag'; // §13.3.6.4/.5/.6
STARTEND_OP: 'startend op'; // §13.3.6.3
VAN_DE_MAAND: 'van de maand'; // §13.3.6.4
VAN_MAAND: 'van maand'; // §13.3.6.5/.6
KWARTAAL_GROEP_1: '1, 4, 7 en 10'; // §13.3.6.5
KWARTAAL_GROEP_2: '2, 5, 8 en 11'; // §13.3.6.5
KWARTAAL_GROEP_3: '3, 6, 9 en 12'; // §13.3.6.5
ELKE_DAG: 'elke dag';       // §13.3.6.2 (also v2.1.0 <tijdlijn>)
ELKE_WEEK: 'elke week';     // §13.3.6.2
ELKE_MAAND: 'elke maand';   // §13.3.6.2 (also v2.1.0 <tijdlijn>)
ELK_KWARTAAL: 'elk kwartaal'; // §13.3.6.2
ELK_JAAR: 'elk jaar';       // §13.3.6.2 (also v2.1.0 <tijdlijn>)
ELKE: 'elke'; // §13.3.6.1/.7 (bare, before <tijdlijnnaam> / <tijdseenhedenmeervoud>)
DAGEN: 'dagen';         // §13.3.6.8
WEKEN: 'weken';         // §13.3.6.8
MAANDEN: 'maanden';     // §13.3.6.8
KWARTALEN: 'kwartalen'; // §13.3.6.8
JAREN: 'jaren';         // §13.3.6.8
TIJDLIJN: 'Tijdlijn';   // §13.3.6.10

// --- Other Keywords (Alphabetical subgroups where reasonable) ---
// Top-Level & Definition
BESLISTABEL: 'Beslistabel';
DAGSOORT: 'Dagsoort';
DIMENSIE: 'Dimensie';
DOMEIN: 'Domein';
EENHEIDSYSTEEM: 'Eenheidsysteem';
FEITTYPE: 'Feittype';
OBJECTTYPE: 'Objecttype';
PARAMETER: 'Parameter';
REGEL: 'Regel';

// Rule Structure & Result
DAARBIJ_GELDT: 'Daarbij geldt:';
GELDIG: 'geldig';
HEBBEN: 'hebben';
HEEFT: 'heeft';
INDIEN: 'indien';
IS: 'is';
MOET: 'moet';
MOETEN: 'moeten';
WORDT_VERDEELD_OVER: 'wordt verdeeld over';
ZIJN: 'zijn';

// Conditions & Operators
AAN: 'aan';
AFGEROND_OP: 'afgerond op';
ALLE: 'alle';
EERDER_DAN: 'eerder dan';
GEDEELD_DOOR: 'gedeeld door';
GEDEELD_DOOR_ABS: 'gedeeld door (ABS)';
GELIJK_AAN: 'gelijk aan';
GEVULD: 'gevuld';
GEVUURD: 'gevuurd';
GROTER_DAN: 'groter dan';
INCONSISTENT: 'inconsistent';
KLEINER_DAN: 'kleiner dan';
LATER_DAN: 'later dan';
LEEG: 'leeg';
MAAL: 'maal';
MIN: 'min';
NAAR_BENEDEN: 'naar beneden';
NAAR_BOVEN: 'naar boven';
NIET: 'niet';
ONGELIJK_AAN: 'ongelijk aan';
PLUS: 'plus';
REKENKUNDIG: 'rekenkundig';
RICHTING_NUL: 'richting nul';
TOT: 'tot';
TOT_DE_MACHT: 'tot de macht';
TOT_EN_MET: 'tot en met';
UNIEK: 'uniek';
VANAF: 'vanaf';
VERENIGD_MET: 'verenigd met';
VERMINDERD_MET: 'verminderd met';
VOLDOEN: 'voldoen';
VOLDOET: 'voldoet';
WEG_VAN_NUL: 'weg van nul';
WORTEL_VAN: 'de wortel van';

// GegevensSpraak Details
BEZIELD: '(bezield)';
BEZITTELIJK: '(bezittelijk)';
BIJVOEGLIJK: '(bijvoeglijk)';
BOOLEAN: 'Boolean';
CIJFERS: 'cijfers';
DATUM_IN_DAGEN: 'Datum in dagen';
DECIMALEN: 'decimalen';
ENUMERATIE: 'Enumeratie';
GEDIMENSIONEERD_MET: 'gedimensioneerd met';
GEHEEL_GETAL: 'geheel getal';
GETAL: 'getal';
KENMERK: 'kenmerk';
MET: 'met';
MET_EENHEID: 'met eenheid';
MV_START: '(mv:';
NEGATIEF: 'negatief';
NIET_NEGATIEF: 'niet-negatief';
NUMERIEK: 'Numeriek';
PERCENTAGE: 'Percentage';
POSITIEF: 'positief';
TEKST: 'Tekst';
VOOR_ELK_JAAR: 'voor elk jaar';
VOOR_ELKE_DAG: 'voor elke dag';
VOOR_ELKE_MAAND: 'voor elke maand';

// Functions & Aggregations
AANTAL: 'het aantal';
EERSTE_VAN: 'de eerste van';
IN_HELE: 'in hele';
LAATSTE_VAN: 'de laatste van';
REEKS_VAN_TEKSTEN_EN_WAARDEN: 'reeks van teksten en waarden';
SOM_VAN: 'de som van';
TIJDSDUUR_VAN: 'de tijdsduur van';
TOTAAL_VAN: 'het totaal van';

// Verdeling Details
AFNEMENDE: 'afnemende';
IN_GELIJKE_DELEN: 'in gelijke delen';
OVER_VERDELING: 'over.';
TOENEMENDE: 'toenemende';

// Quantifiers
DRIE_TELWOORD: 'drie';
EEN_TELWOORD: 'één';
GEEN_VAN_DE: 'geen van de';
MEERDERE: 'meerdere';
PRECIES: 'precies';
TEN_HOOGSTE: 'ten hoogste';
TEN_MINSTE: 'ten minste';
TWEE_TELWOORD: 'twee';
VIER_TELWOORD: 'vier';

// Common words, Dates, Misc
ALTIJD: 'altijd';
BIJ: 'bij';
DAG: 'dag';
DAT: 'dat';
DE: 'de';
DD_PUNT: 'dd.';
DIE: 'die';
EEN: 'een';
EN: 'en';
ER: 'er';
HET: 'het';
HIJ: 'hij';
IN: 'in';
JAAR: 'jaar';
KWARTAAL: 'kwartaal';
MAAND: 'maand';
MILLISECONDE: 'milliseconde';
MINUUT: 'minuut';
OF: 'of';
ONWAAR: 'onwaar';
OP: 'op';
OVER: 'over';
PERIODE: 'periode';
REKENDATUM: 'Rekendatum';
REKENJAAR: 'Rekenjaar';
REGELVERSIE: 'regelversie';
SECONDE: 'seconde';
TM: 't/m';
UIT: 'uit';
UUR: 'uur';
VAN: 'van';
VOLGENDE_VOORWAARDE: 'volgende voorwaarde';
VOLGENDE_VOORWAARDEN: 'volgende voorwaarden';
VOOR: 'voor';
WAAR: 'waar';
WEEK: 'week';
```

**2.2 Identifiers:** Corresponds to EBNF `<karakterreeks>` when used for names.

```antlr
IDENTIFIER : LETTER (LETTER | DIGIT | '_' | '-' | '\'' | ' ')* LETTER | LETTER;

fragment LETTER : [\p{L}_] ; // EBNF <letter> plus underscore
fragment DIGIT  : [\p{Nd}] ; // EBNF <digit>
```

**2.3 Literals:** Concrete values based on EBNF definitions (§13.2).

```antlr
NUMBER: MINUS? DIGIT+ ( ',' DIGIT+ )?                 // <geheelgetal> | <decimaalgetal>
      | MINUS? DIGIT+ '_' DIGIT+ '/' POSITIVE_DIGIT+   // <rationeelgetal> (mixed)
      | MINUS? DIGIT+ '/' POSITIVE_DIGIT+              // <rationeelgetal> (simple)
      ;
fragment MINUS: '-';
fragment POSITIVE_DIGIT: [1-9] DIGIT*; // Denominator for rationals

STRING_LITERAL: '"' ( '\\' . | ~["\\\r\n] )*? '"' ;
ENUM_LITERAL: '\'' ( '\\' . | ~['\\\r\n] )*? '\'' ;
DATE_TIME_LITERAL: ( DD_PUNT WS? )? DAY_PART '-' MONTH_PART '-' YEAR_PART ( WS? TIME_PART )? ;

// Date/Time Fragments
fragment DAY_PART  : ('0'?[1-9] | [12]\d | '3'[01]) ; // Approximation
fragment MONTH_PART: ('0'?[1-9] | '1'[0-2]) ;        // Approximation
fragment YEAR_PART : DIGIT DIGIT DIGIT DIGIT ;
fragment TIME_PART : HOUR_PART ':' MINUTE_PART ':' SECOND_PART '.' MILLI_PART ;
fragment HOUR_PART   : ('0'|'1') DIGIT | '2' '0'..'3'; // 00-23
fragment MINUTE_PART : '0'..'5' DIGIT ; // 00-59
fragment SECOND_PART : '0'..'5' DIGIT ; // 00-59
fragment MILLI_PART  : DIGIT DIGIT DIGIT ; // 000-999
```

**2.4 Operators and Punctuation:** Symbols corresponding to EBNF terminals not covered by keywords or literals.

```antlr
// --- Structural Punctuation ---
LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
COMMA: ',';
DOT: '.';
COLON: ':';
SEMICOLON: ';';
BULLET: '•';
L_ANGLE_QUOTE: '«';
R_ANGLE_QUOTE: '»';

// --- Operators / Symbols ---
SLASH: '/';
EQUALS: '=';
PERCENT_SIGN: '%';
CARET: '^';
UNDERSCORE: '_';
APOSTROPHE: '\'';
```

**2.5 Whitespace and Comments:** Ignored by the parser.

```antlr
WS: [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
```

---

## 3. Parser Specification

The parser implements the hierarchical structure defined by the EBNF non-terminals in Chapter 13. Rules are ordered to generally define basic elements before complex ones and group related concepts.

```antlr
grammar RegelSpraak;

options { tokenVocab = RegelSpraakLexer; }

// --- Start Rule ---
regelSpraakDocument // Top level container
    : ( definition | regel | beslistabel )* EOF
    ;

// --- Basic Building Blocks ---
identifier : IDENTIFIER ; // Rule for the token

naamwoord
    : (DE | HET)? name=identifier
    ;
lidwoord : DE | HET | EEN ;       // §13.2.9 <lidwoord> ::= <bepaaldlidwoord> | <onbepaaldlidwoord>
bepaaldLidwoord : DE | HET ;      // §13.2.10
onbepaaldLidwoord : EEN ;         // §13.2.11

// §13.2.12 Rangtelwoord (ordinal selector)
// Source defect: the parenthesized middle alternative writes a free <geheelgetal>
// between literals "als" and "e genoemde"; modelled here as ALS integerLiteral E_GENOEMDE.
rangtelwoord
    : EERSTGENOEMDE
    | ALS_TWEEDE_GENOEMDE
    | ALS_DERDE_GENOEMDE
    | ALS_VIERDE_GENOEMDE
    | ALS integerLiteral E_GENOEMDE
    | LAATSTGENOEMDE
    ;

// Literals (used within expressions)
integerLiteral : NUMBER ;
jaarLiteral : NUMBER ;
getalWaarde : NUMBER eenheidNaam? ;
datumLiteral : DATE_TIME_LITERAL ;
booleanWaarde : WAAR | ONWAAR ;
tekstWaarde : STRING_LITERAL ;
enumeratieWaarde : identifier ; // Enum values are parsed as identifiers initially

// Names (used throughout)
objectTypeNaam : identifier ;
kenmerkNaam : identifier ;
attribuutNaam : identifier ;
rolNaam : identifier ;
parameterNaam : identifier ;
variabeleNaam : identifier ;
domeinNaam : identifier ;
dimensieNaam : identifier ;
dimensieNaamMeervoud : identifier ;
dimensieWaarde : identifier ;
dagsoortNaam : identifier ;
regelNaam : identifier ;
feitTypeNaam : identifier ;
tijdlijnNaam : naamwoord ; // §13.3.6.9 <tijdlijnnaam> ::= <naamwoord>
eenheidNaam : identifier ; // EBNF §13.3.5.4 <eenheidsafkorting>
eenheidMeervoud : identifier ; // Years, months, etc.


// --- Definitions (GegevensSpraak §13.3) ---
definition
    : objectTypeDefinition
    | objectTypeExtensie
    | domeinDefinition
    | dimensieDefinition
    | eenheidsysteemDefinition
    | parameterDefinition
    | feitTypeDefinition
    | dagsoortDefinition
    | tijdlijnDefinitie
    ;

// EBNF §13.3.1 Object Type Definition
objectTypeDefinition
    : OBJECTTYPE naamwoord ( MV_START pluralName=identifier RPAREN )? ( BEZIELD )? NEWLINE?
      ( objectTypeMember )*
    ;
objectTypeMember
    : ( koptekst NEWLINE? | kenmerkSpecificatie SEMICOLON NEWLINE? | attribuutSpecificatie SEMICOLON NEWLINE? )
    ;
koptekst // EBNF §13.3.1.5
    : '---' IDENTIFIER
    ;

// EBNF §13.3.1.2 Extensie van objecttype
objectTypeExtensie
    : EXTENSIE_VAN_OBJECTTYPE objectTypeMetLidwoord NEWLINE?
      ( objectTypeMember )+
    ;
objectTypeMetLidwoord // EBNF §13.3.1.3
    : bepaaldLidwoord objectTypeNaam
    ;

// EBNF §13.3.2 Kenmerk Specificatie
kenmerkSpecificatie
    : ( (naamwoord KENMERK)
      | bezittelijkKenmerk
      | bijvoeglijkKenmerk
      )
      tijdlijn?
    ;
bezittelijkKenmerk : naamwoord KENMERK BEZITTELIJK ;
bijvoeglijkKenmerk : IS name=identifier ( MV_START plural=identifier RPAREN )? KENMERK BIJVOEGLIJK ;

// EBNF §13.3.2 Attribuut Specificatie
attribuutSpecificatie
    : naamwoord ( datatype | domeinNaam )
      ( MET_EENHEID eenheidExpressie )?
      ( GEDIMENSIONEERD_MET dimensieNaam (EN dimensieNaam)* )?
      tijdlijn?
    ;

// EBNF §13.3.3 Datatypes
datatype
    : numeriekDatatype | percentageDatatype | tekstDatatype | booleanDatatype | datumTijdDatatype
    ;
numeriekDatatype : NUMERIEK LPAREN getalSpecificatie RPAREN ;
percentageDatatype : PERCENTAGE LPAREN getalSpecificatie RPAREN ;
tekstDatatype : TEKST ;
booleanDatatype : BOOLEAN ;
datumTijdDatatype : DATUM_IN_DAGEN | DATUM_TIJD_MILLIS ;
getalSpecificatie
    : (NEGATIEF | NIET_NEGATIEF | POSITIEF)?
      ( GEHEEL_GETAL | (GETAL MET dec=NUMBER DECIMALEN) | GETAL )
    ;

// EBNF §13.3.4 Domein Definition
domeinDefinition
    : DOMEIN domeinNaam IS_VAN_HET_TYPE
      ( datatype | enumeratieSpecificatie )
      ( MET_EENHEID eenheidExpressie )?
      ( SEMICOLON NEWLINE? )?
    ;
enumeratieSpecificatie
    : ENUMERATIE NEWLINE?
      ( ENUM_LITERAL NEWLINE? )+ // Matches enum literals directly
    ;

// EBNF §13.3.5 Eenheden & Eenheidsysteem
eenheidExpressie // Corresponds to unit structure in §13.3.3.2/3
    : ( eenheidMacht ( SLASH eenheidMacht )? | '1' )
    ;
eenheidMacht // EBNF §13.3.5.5
    : eenheidNaam ( CARET exponent )?
    ;
exponent : integerLiteral ; // EBNF §13.3.5.6 <exponent> maps to <geheelgetal>

eenheidsysteemDefinition
    : EENHEIDSYSTEEM systemName=identifier NEWLINE?
      ( eenheidDefinitie NEWLINE? )+
    ;
eenheidDefinitie // EBNF §13.3.5.1
    : naamwoord abbreviation=identifier?
      ( omrekenSpecificatie )?
    ;
omrekenSpecificatie // EBNF §13.3.5.2
    : EQUALS ( '1' SLASH )? factor=integerLiteral targetUnit=identifier
    ;

// --- Tijdlijnen (EBNF §13.3.6) ---
// EBNF §13.3.6.1 Tijdlijn
tijdlijn
    : VOOR ( enkelvoudigePeriode | meervoudigePeriode | ELKE tijdlijnNaam )
    ;
enkelvoudigePeriode // EBNF §13.3.6.2
    : ELKE_DAG
    | ELKE_WEEK vastStartpuntDatum
    | ELKE_MAAND vastStartpuntMaand?
    | ELK_KWARTAAL vastStartpuntKwartaal?
    | ELK_JAAR vastStartpuntJaar?
    ;
vastStartpuntDatum // EBNF §13.3.6.3
    : STARTEND_OP datumWaarde
    ;
vastStartpuntMaand // EBNF §13.3.6.4
    : STARTEND_OP_DAG dag VAN_DE_MAAND
    ;
vastStartpuntKwartaal // EBNF §13.3.6.5
    : STARTEND_OP_DAG dag VAN_MAAND ( KWARTAAL_GROEP_1 | KWARTAAL_GROEP_2 | KWARTAAL_GROEP_3 )
    ;
vastStartpuntJaar // EBNF §13.3.6.6
    : STARTEND_OP_DAG dag VAN_MAAND maand
    ;
meervoudigePeriode // EBNF §13.3.6.7
    : ELKE tijdseenhedenMeervoud vastStartpuntDatum
    ;
tijdseenhedenMeervoud // EBNF §13.3.6.8
    : NUMBER ( DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN )
    ;
// §13.3.6.3/.4/.6 reference §13.2.20 <datumwaarde>, §13.2.22 <dag>, §13.2.23 <maand>.
// These are scalar numeric / date tokens at lexer level.
datumWaarde : DATE_TIME_LITERAL ;
dag : NUMBER ;
maand : NUMBER ;

// EBNF §13.3.6.10 Tijdlijndefinitie
// Source defect: the EBNF RHS is missing one closing parenthesis (the outer
// `(("een" …) | (<tijdseenhedenmeervoud> …)` never closes). Modelled here as the
// intended two-way choice between a singular tijdvak ("een" ...) and a plural tijdvak.
tijdlijnDefinitie
    : TIJDLIJN tijdlijnNaam HEEFT_TIJDVAKKEN_VAN
      ( EEN
          ( DAG
          | WEEK ( vastStartpuntDatum | MET_VARIABEL_STARTPUNT )
          | MAAND ( vastStartpuntMaand | MET_VARIABEL_STARTPUNT )
          | KWARTAAL ( vastStartpuntKwartaal | MET_VARIABEL_STARTPUNT )
          | JAAR ( vastStartpuntJaar | MET_VARIABEL_STARTPUNT )
          )
      | tijdseenhedenMeervoud ( vastStartpuntDatum | MET_VARIABEL_STARTPUNT )
      )
    ;

// EBNF §13.3.7 Dimensie Definition
dimensieDefinition
    : DIMENSIE bepaaldLidwoord dimensieNaam (COMMA)? BESTAANDE_UIT dimensieNaamMeervoud voorzetselSpecificatie NEWLINE?
      ( labelWaardeSpecificatie NEWLINE? )+
    ;
voorzetselSpecificatie // EBNF §13.3.7.2
    : ( NA_HET_ATTRIBUUT_MET_VOORZETSEL voorzetsel RPAREN COLON )
    | VOOR_HET_ATTRIBUUT_ZONDER_VOORZETSEL
    ;
voorzetsel : VAN | IN | VOOR | OVER | OP | BIJ | UIT ; // EBNF §13.3.7.3
labelWaardeSpecificatie // EBNF §13.3.7.5
    : DIGIT+ DOT dimensieWaarde
    ;

// EBNF §13.3.8 Parameter Definition
parameterDefinition
    : PARAMETER parameterMetLidwoord COLON ( datatype | domeinNaam )
      ( MET_EENHEID eenheidExpressie )?
      tijdlijn? SEMICOLON NEWLINE?
    ;
parameterMetLidwoord : bepaaldLidwoord parameterNaam ;

// EBNF §13.3.9 FeitType Definition
feitTypeDefinition // EBNF §13.3.9.1
    : (FEITTYPE feitTypeNaam NEWLINE? rolSpec1=rolSpecificatie NEWLINE? rolSpec2=rolSpecificatie NEWLINE? card1=feitTypeCardinaliteitRelatie )
    | (WEDERKERIG_FEITTYPE feitTypeNaam NEWLINE? rolSpec=rolSpecificatie NEWLINE? card2=wederkerigFeitTypeCardinaliteitRelatie )
    ;
rolSpecificatie // EBNF §13.3.9.1 (partially)
    : bepaaldLidwoord? rolNaam ( MV_START pluralRol=identifier RPAREN )? objectTypeNaam
    ;
feitTypeCardinaliteitRelatie // EBNF §13.3.9.1 (last line)
    : (EEN rol1=rolNaam | MEERDERE pluralRol1=identifier) relatieBeschrijving (EEN rol2=rolNaam | MEERDERE pluralRol2=identifier)
    ;
wederkerigFeitTypeCardinaliteitRelatie // EBNF §13.3.9.2 (last line)
    : (EEN rol=rolNaam relatieBeschrijving EEN rol=rolNaam) | (MEERDERE pluralRol=identifier relatieBeschrijving MEERDERE pluralRol=identifier)
    ;
relatieBeschrijving : identifier+ ; // EBNF §13.3.9.6

// EBNF §13.3.10 Dagsoort Definition
dagsoortDefinition : DAGSOORT naamwoord ( SEMICOLON NEWLINE? )? ;

// --- Regel Definition (EBNF §13.4.2) ---
regel : REGEL regelNaam NEWLINE? ( regelVersie )+ ;

regelVersie : versie NEWLINE? regelSpraakRegel ;

versie : GELDIG versieGeldigheid ;

versieGeldigheid
    : ALTIJD
    | ( VANAF d1=(datumLiteral | jaarLiteral) ( TM d2=(datumLiteral | jaarLiteral) )? )
    | ( TM d3=(datumLiteral | jaarLiteral) )
    ;

regelSpraakRegel
    : resultaatDeel NEWLINE?
      ( voorwaardeDeel DOT )?
      ( variabeleDeel DOT )? // Added DOT here based on EBNF
    ;

// EBNF §13.4.2.10 Variabelendeel
variabeleDeel : DAARBIJ_GELDT (NEWLINE? variabeleOnderdeel)+ ; // trailing DOT handled in regelSpraakRegel
variabeleOnderdeel : bepaaldLidwoord? variabeleNaam IS expressie ;


// --- Onderwerpketen (EBNF §13.4.1) ---
// §13.4.1.1 Onderwerpketen
// Source defect: the v2.3.0 EBNF RHS has one unmatched '(' (the inner group never
// closes). The intended grouping is the flat five-way choice below.
onderwerpKeten
    : universeelOnderwerp
    | onderwerpVerwijzing
    | ( selector VAN onderwerpKeten )
    | ( ZIJN ( rolNaam | attribuutNaam ) )
    | subselectie
    ;

// §13.4.1.2 Universeel onderwerp
universeelOnderwerp
    : onbepaaldLidwoord (objectTypeNaam | rolNaam | kenmerkNaam)
    ;

// §13.4.1.3 Onderwerpverwijzing
onderwerpVerwijzing
    : bepaaldLidwoord rangtelwoord? (objectTypeNaam | rolNaam | kenmerkNaam | attribuutNaam)
    ;

// §13.4.2.7 Selector (lidwoord now mandatory; attribuutnaam added)
selector : lidwoord (rolNaam | attribuutNaam) ;

// §13.4.2.8 Subselectie
subselectie : onderwerpKeten (DIE | DAT) predicaat ;

// §13.4.2.9 Attribuut van onderwerp (ordinal alternative added)
attribuutVanOnderwerp
    : ( kwantificatie? attribuutMetLidwoord
      | bepaaldLidwoord rangtelwoord attribuutNaam )
      VAN onderwerpKeten
    ;
attribuutMetLidwoord : bepaaldLidwoord attribuutNaam ;

// --- Resultaat Deel (EBNF §13.4.3 - §13.4.12) ---
resultaatDeel // §13.4.3.1
    : gelijkstelling
    | kenmerkToekenning
    | objectCreatie
    | feitCreatie
    | consistentieRegel
    | initialisatie
    | verdeling
    | dagsoortResultaatDefinitie
    | startpuntBepaling
    ;

// EBNF §13.4.4 Gelijkstelling
gelijkstelling
    : gelijkstellingToekenning
    | gelijkstellingBerekening
    ;
gelijkstellingToekenning
    : attribuutVanOnderwerp WORDT_GESTELD_OP expressie
    ;
gelijkstellingBerekening
    : attribuutVanOnderwerp WORDT_BEREKEND_ALS (getalExpressie | datumExpressie)
    ;

// EBNF §13.4.5 Kenmerktoekenning
kenmerkToekenning
    : onderwerpKeten (IS | HEEFT) EEN? kenmerkNaam
    ;

// EBNF §13.4.6 ObjectCreatie
objectCreatie
    : EEN onderwerpKeten HEEFT EEN rolNaam ( MET waardeToekenning ( (COMMA | EN) waardeToekenning )* )?
    ;
waardeToekenning
    : attribuutWaardeToekenning
    | kenmerkWaardeToekenning
    ;
attribuutWaardeToekenning : attribuutNaam GELIJK_AAN expressie ; // Simplified, using attribuutNaam directly
kenmerkWaardeToekenning : kenmerkNaam GELIJK_AAN (WAAR | ONWAAR) ;

// EBNF §13.4.7 FeitCreatie
feitCreatie
    : EEN rolNaam VAN EEN onderwerpKeten IS lidwoord rolNaam VAN lidwoord onderwerpKeten // Adjusted for EBNF
    ;

// EBNF §13.4.8 Consistentieregels
consistentieRegel
    : enkelvoudigeConsistentieRegel
    | toplevelSamengesteldCriterium
    | uniciteitsControle
    ;
enkelvoudigeConsistentieRegel
    : getalConsistentie
    | datumConsistentie
    | tekstConsistentie
    | objectConsistentie
    ;
getalConsistentie
    : getalExpressie MOET (toplevelEenzijdigeGetalVergelijkingsOperatorMeervoud | toplevelTweezijdigeGetalVergelijkingsOperatorMeervoud)
    ;
datumConsistentie
    : datumExpressie MOET (toplevelEenzijdigeDatumVergelijkingsOperatorMeervoud | toplevelTweezijdigeDatumVergelijkingsOperatorMeervoud)
    ;
tekstConsistentie
    : tekstExpressie MOET (toplevelEenzijdigeTekstVergelijkingsOperatorMeervoud | toplevelTweezijdigeTekstVergelijkingsOperatorMeervoud)
    ;
objectConsistentie
    : objectExpressie MOET (toplevelEenzijdigeObjectVergelijkingsOperatorMeervoud | toplevelTweezijdigeObjectVergelijkingsOperatorMeervoud)
    ;

toplevelSamengesteldCriterium
    : ER_MOET_WORDEN_VOLDAAN_AAN (VOLGEND_CRITERIUM | (consistentieKwantificatie VOLGENDE_CRITERIA)) samengesteldCriteriumOnderdeel
    ;
samengesteldCriteriumOnderdeel // Used by both toplevel and nested
    : NEWLINE BULLET genestCriterium (NEWLINE genestCriterium)+
    ;
genestCriterium // EBNF §13.4.8.9
    : (BULLET)+ (voorwaardeVergelijking | samengesteldCriterium) // 'voorwaardevergelijking' defined below
    ;
samengesteldCriterium // EBNF §13.4.8.10 (Note: Recursion with toplevel via genestCriterium)
    : WORDT_VOLDAAN_AAN (VOLGEND_CRITERIUM | (consistentieKwantificatie VOLGENDE_CRITERIA)) samengesteldCriteriumOnderdeel
    ;
consistentieKwantificatie // EBNF §13.4.14.3
    : ALLE
    | GEEN_VAN_DE
    | ((TEN_MINSTE | TEN_HOOGSTE | PRECIES) (NUMBER | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD) VAN DE)
    ;

uniciteitsControle // EBNF §13.4.8.11
    : (alleAttribuutVanOnderwerp | uniciteitConcatenatie) vereniging* MOETEN UNIEK // 'zijn.' assumed handled by context/engine
    ;
vereniging // EBNF §13.4.8.12
    : VERENIGD_MET (alleAttribuutVanOnderwerp | uniciteitConcatenatie)
    ;
alleAttribuutVanOnderwerp // EBNF §13.4.8.13
    : DE meervoudsvorm VAN ALLE ((objectTypeNaam | rolNaam) VAN onderwerpKeten) (VAN onderwerpKeten)?
    ;
uniciteitConcatenatie // EBNF §13.4.8.14
    : CONCATENATIE_VAN meervoudsvorm (COMMA meervoudsvorm)* EN meervoudsvorm VAN ALLE ((objectTypeNaam | rolNaam) VAN onderwerpKeten) (VAN onderwerpKeten)?
    ;
meervoudsvorm : identifier ;

// EBNF §13.4.9 Initialisatie
initialisatie
    : attribuutVanOnderwerp WORDT_GEINITIALISEERD_OP expressie
    ;

// EBNF §13.4.10 Verdeling
verdeling
    : attribuutVanOnderwerp WORDT_VERDEELD_OVER attribuutVanOnderwerp WAARBIJ_WORDT_VERDEELD (verdelenZonderGroepen | meervoudigCriterium)
    ;
verdelenZonderGroepen
    : IN_GELIJKE_DELEN
    | (NAAR_RATO_VAN attribuutMetLidwoord)
    ;
meervoudigCriterium
    : COLON NEWLINE (verdelenOverGroepen | (verdelenZonderGroepen COMMA))
      (NEWLINE maximumAanspraak)?
      (NEWLINE verdeelAfronding)?
      (NEWLINE onverdeeldeRest)?
    ;
verdelenOverGroepen // EBNF §13.4.10.4
    : '- ' OP_VOLGORDE_VAN (AFNEMENDE | TOENEMENDE) attribuutMetLidwoord NEWLINE
      criteriumbijGelijkeVolgorde COMMA // Added comma per EBNF
    ;
criteriumbijGelijkeVolgorde // EBNF §13.4.10.5
    : '- ' BIJ_EVEN_GROOT_CRITERIUM (IN_GELIJKE_DELEN | (NAAR_RATO_VAN attribuutMetLidwoord)) // trailing comma removed
    ;
maximumAanspraak // EBNF §13.4.10.6
    : '- ' MET_EEN_MAXIMUM_VAN attribuutMetLidwoord COMMA
    ;
verdeelAfronding // EBNF §13.4.10.7
    : '- ' AFGEROND_OP integerLiteral DECIMALEN NAAR_BENEDEN DOT
    ;
onverdeeldeRest // EBNF §13.4.10.8
    : ALS_ONVERDEELDE_REST_BLIJFT attribuutVanOnderwerp OVER_VERDELING
    ;

// EBNF §13.4.11 Dagsoort Resultaat Definitie
dagsoortResultaatDefinitie // Renamed from dagsoortdefinitie to avoid clash
    : EEN DAG IS EEN dagsoortNaam
    ;

// EBNF §13.4.12 Startpuntbepaling
// Source spelling defect: written as `< startpuntbepaling>` and `<datumexpressie >`
// with stray spaces; identity is unaffected.
startpuntBepaling // §13.4.12.1
    : HET_STARTPUNT_VAN tijdlijnNaam VOOR universeelOnderwerp WORDT_BEPAALD_DOOR datumExpressie
    ;


// --- Voorwaarde Deel (EBNF §13.4.13 - §13.4.15) ---
voorwaardeDeel // EBNF §13.4.13.1
    : INDIEN (toplevelElementaireVoorwaarde | toplevelSamengesteldeVoorwaarde)
    | periodevergelijkingEnkelvoudig // EBNF §13.4.15.68
    ;

// Predicates (Used in subselectie) EBNF §13.4.13.2+
predicaat
    : elementairPredicaat
    | samengesteldPredicaat
    ;
elementairPredicaat // EBNF §13.4.13.3 & .5-.8
    : getalPredicaat
    | tekstPredicaat
    | datumPredicaat
    | objectPredicaat
    ;
getalPredicaat : toplevelTweezijdigeGetalVergelijkingsOperatorMeervoud getalExpressie ;
tekstPredicaat : toplevelTweezijdigeTekstVergelijkingsOperatorMeervoud tekstExpressie ;
datumPredicaat : toplevelTweezijdigeDatumVergelijkingsOperatorMeervoud datumExpressie ;
objectPredicaat : toplevelTweezijdigeObjectVergelijkingsOperatorMeervoud objectExpressie ;

samengesteldPredicaat // EBNF §13.4.13.4
    : AAN kwantificatie VOLGENDE_VOORWAARDE 'n'? (VOLDOET | VOLDOEN) COLON
      (samengesteldeVoorwaardeOnderdeel | toplevelVoorwaardeVergelijking) // Reusing parts from §13.4.14
    ;

// EBNF §13.4.14 Samengestelde voorwaarde
toplevelSamengesteldeVoorwaarde // EBNF §13.4.14.1
    : (objectExpressie | referentie | aggregatie | ER) AAN voorwaardeKwantificatie VOLGENDE_VOORWAARDE 'n'? (VOLDOET | VOLDOEN | WORDT_VOLDAAN_AAN) COLON samengesteldeVoorwaardeOnderdeel
    ;
genesteSamengesteldeVoorwaarde // EBNF §13.4.14.2 (Note: Recursion via genesteVoorwaarde)
    : (objectExpressie | referentie | aggregatie | ER) (VOLDOET | VOLDOEN | WORDT_VOLDAAN_AAN) AAN voorwaardeKwantificatie VOLGENDE_VOORWAARDE 'n'? COLON samengesteldeVoorwaardeOnderdeel
    ;
voorwaardeKwantificatie // EBNF §13.4.14.4
    : DE
    | ALLE
    | GEEN_VAN_DE
    | ((TEN_MINSTE | TEN_HOOGSTE | PRECIES) (NUMBER | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD) VAN DE)
    ;
kwantificatie // EBNF §13.4.14.5 (Generic quantifier)
    : DE
    | ALLE
    | 'al' // Assuming this is a typo in EBNF and should match ALLE? Or needs 'al' token?
    | GEEN_VAN_DE
    | ((TEN_MINSTE | TEN_HOOGSTE | PRECIES) (NUMBER | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD) VAN DE)
    ;
samengesteldeVoorwaardeOnderdeel // EBNF §13.4.14.6 (Shared with Criterium)
    : NEWLINE BULLET genesteVoorwaarde (NEWLINE genesteVoorwaarde)+
    ;
genesteVoorwaarde // EBNF §13.4.14.7
    : (BULLET)+ (elementaireVoorwaarde | genesteSamengesteldeVoorwaarde)
    ;

// EBNF §13.4.15 Elementaire voorwaarde
elementaireVoorwaarde // EBNF §13.4.15.49 (Non-toplevel)
    : voorwaardeVergelijking
    | consistentieVoorwaarde
    | periodevergelijkingElementair // EBNF §13.4.15.69 Added here
    ;
toplevelElementaireVoorwaarde // EBNF §13.4.15.1
    : toplevelVoorwaardeVergelijking
    | toplevelConsistentieVoorwaarde
    ;

voorwaardeVergelijking // EBNF §13.4.15.50 (Non-toplevel comparisons)
    : getalVergelijking
    | objectVergelijking
    | tekstVergelijking
    | datumVergelijking
    | booleanVergelijking
    ;
toplevelVoorwaardeVergelijking // EBNF §13.4.15.2
    : toplevelGetalVergelijking
    | toplevelObjectVergelijking
    | toplevelTekstVergelijking
    | toplevelDatumVergelijking
    | toplevelBooleanVergelijking
    ;

// Specific comparison types (Toplevel and Non-toplevel)
getalVergelijking : eenzijdigeGetalVergelijking | tweezijdigeGetalVergelijking ;
eenzijdigeGetalVergelijking : getalExpressie eenzijdigeGetalVergelijkingsOperator ;
tweezijdigeGetalVergelijking : getalExpressie tweezijdigeGetalVergelijkingsOperator getalExpressie ;
toplevelGetalVergelijking : toplevelEenzijdigeGetalVergelijking | toplevelTweezijdigeGetalVergelijking ;
toplevelEenzijdigeGetalVergelijking : getalExpressie toplevelEenzijdigeGetalVergelijkingsOperator ;
toplevelTweezijdigeGetalVergelijking : getalExpressie toplevelTweezijdigeGetalVergelijkingsOperator getalExpressie ;

datumVergelijking : eenzijdigeDatumVergelijking | tweezijdigeDatumVergelijking ;
eenzijdigeDatumVergelijking : datumExpressie eenzijdigeDatumVergelijkingsOperator ;
tweezijdigeDatumVergelijking : datumExpressie tweezijdigeDatumVergelijkingsOperator datumExpressie ;
toplevelDatumVergelijking : toplevelEenzijdigeDatumVergelijking | toplevelTweezijdigeDatumVergelijking ;
toplevelEenzijdigeDatumVergelijking : datumExpressie toplevelEenzijdigeDatumVergelijkingsOperator ;
toplevelTweezijdigeDatumVergelijking : datumExpressie toplevelTweezijdigeDatumVergelijkingsOperator datumExpressie ;

tekstVergelijking : eenzijdigeTekstVergelijking | tweezijdigeTekstVergelijking ;
eenzijdigeTekstVergelijking : tekstExpressie eenzijdigeTekstVergelijkingsOperator ;
tweezijdigeTekstVergelijking : tekstExpressie tweezijdigeTekstVergelijkingsOperator tekstExpressie ;
toplevelTekstVergelijking : toplevelEenzijdigeTekstVergelijking | toplevelTweezijdigeTekstVergelijking ;
toplevelEenzijdigeTekstVergelijking : tekstExpressie toplevelEenzijdigeTekstVergelijkingsOperator ;
toplevelTweezijdigeTekstVergelijking : tekstExpressie toplevelTweezijdigeTekstVergelijkingsOperator tekstExpressie ;

booleanVergelijking : eenzijdigeBooleanVergelijking | tweezijdigeBooleanVergelijking ;
eenzijdigeBooleanVergelijking : booleanExpressie eenzijdigeBooleanVergelijkingsOperator ;
tweezijdigeBooleanVergelijking : booleanExpressie tweezijdigeBooleanVergelijkingsOperator booleanExpressie ;
toplevelBooleanVergelijking : toplevelEenzijdigeBooleanVergelijking | toplevelTweezijdigeBooleanVergelijking ;
toplevelEenzijdigeBooleanVergelijking : booleanExpressie toplevelEenzijdigeBooleanVergelijkingsOperator ;
toplevelTweezijdigeBooleanVergelijking : booleanExpressie toplevelTweezijdigeBooleanVergelijkingsOperator booleanExpressie ;

objectVergelijking : eenzijdigeObjectVergelijking | tweezijdigeObjectVergelijking ;
eenzijdigeObjectVergelijking : objectExpressie eenzijdigeObjectVergelijkingsOperator ;
tweezijdigeObjectVergelijking : (objectExpressie | referentie) tweezijdigeObjectVergelijkingsOperator objectExpressie ;
toplevelObjectVergelijking : toplevelEenzijdigeObjectVergelijking | toplevelTweezijdigeObjectVergelijking ;
toplevelEenzijdigeObjectVergelijking : objectExpressie toplevelEenzijdigeObjectVergelijkingsOperator ;
toplevelTweezijdigeObjectVergelijking : (objectExpressie | referentie) toplevelTweezijdigeObjectVergelijkingsOperator objectExpressie ;

consistentieVoorwaarde : REGELVERSIE identifier IS (GEVUURD | INCONSISTENT) ; // EBNF §13.4.15.66
toplevelConsistentieVoorwaarde : REGELVERSIE identifier (GEVUURD | INCONSISTENT) IS ; // EBNF §13.4.15.18 (Same structure?)

// Operator Definitions (EBNF §13.4.15.19-100)
// Grouping operators by type and level (toplevel vs non-toplevel)
gehelePeriodevergelijking // EBNF §13.4.15.70
    : NIET? GEDURENDE (HET_GEHELE JAAR | DE_GEHELE MAAND)
    ;

// Toplevel Operators
toplevelEenzijdigeGetalVergelijkingsOperator : toplevelEenzijdigeGetalVergelijkingsOperatorEnkelvoud | toplevelEenzijdigeGetalVergelijkingsOperatorMeervoud ;
toplevelEenzijdigeGetalVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (LEEG IS | GEVULD IS | AAN_DE_ELFPROEF VOLDOET) ; // EBNF §13.4.15.20
toplevelEenzijdigeGetalVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (LEEG ZIJN | GEVULD ZIJN | AAN_DE_ELFPROEF VOLDOEN) ; // EBNF §13.4.15.21
toplevelTweezijdigeGetalVergelijkingsOperator : toplevelTweezijdigeGetalVergelijkingsOperatorEnkelvoud | toplevelTweezijdigeGetalVergelijkingsOperatorMeervoud ;
toplevelTweezijdigeGetalVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (GELIJK IS AAN | ONGELIJK IS AAN | GROTER IS DAN | GROTER_OF_GELIJK IS AAN | KLEINER_OF_GELIJK IS AAN | KLEINER IS DAN) ; // EBNF §13.4.15.23
toplevelTweezijdigeGetalVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (GELIJK ZIJN AAN | ONGELIJK ZIJN AAN | GROTER ZIJN DAN | GROTER_OF_GELIJK ZIJN AAN | KLEINER_OF_GELIJK ZIJN AAN | KLEINER ZIJN DAN) ; // EBNF §13.4.15.24

toplevelEenzijdigeDatumVergelijkingsOperator : toplevelEenzijdigeDatumVergelijkingsOperatorEnkelvoud | toplevelEenzijdigeDatumVergelijkingsOperatorMeervoud ;
toplevelEenzijdigeDatumVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (LEEG IS | GEVULD IS | (EEN dagsoortNaam IS)) ; // EBNF §13.4.15.26
toplevelEenzijdigeDatumVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (LEEG ZIJN | GEVULD ZIJN | (EEN dagsoortNaam ZIJN)) ; // EBNF §13.4.15.27
toplevelTweezijdigeDatumVergelijkingsOperator : toplevelTweezijdigeDatumVergelijkingsOperatorEnkelvoud | toplevelTweezijdigeDatumVergelijkingsOperatorMeervoud ;
toplevelTweezijdigeDatumVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (GELIJK IS AAN | ONGELIJK IS AAN | LATER IS DAN | LATER_OF_GELIJK IS AAN | EERDER_OF_GELIJK IS AAN | EERDER IS DAN) ; // EBNF §13.4.15.29
toplevelTweezijdigeDatumVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (GELIJK ZIJN AAN | ONGELIJK ZIJN AAN | LATER ZIJN DAN | LATER_OF_GELIJK ZIJN AAN | EERDER_OF_GELIJK ZIJN AAN | EERDER ZIJN DAN) ; // EBNF §13.4.15.30

toplevelEenzijdigeTekstVergelijkingsOperator : toplevelEenzijdigeTekstVergelijkingsOperatorEnkelvoud | toplevelEenzijdigeTekstVergelijkingsOperatorMeervoud ;
toplevelEenzijdigeTekstVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (LEEG IS | GEVULD IS | (NUMERIEK_MET_EXACT integerLiteral CIJFERS) | AAN_DE_ELFPROEF VOLDOET) ; // EBNF §13.4.15.32
toplevelEenzijdigeTekstVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (LEEG ZIJN | GEVULD ZIJN | (NUMERIEK_MET_EXACT integerLiteral CIJFERS) | AAN_DE_ELFPROEF VOLDOEN) ; // EBNF §13.4.15.33
toplevelTweezijdigeTekstVergelijkingsOperator : toplevelTweezijdigeTekstVergelijkingsOperatorEnkelvoud | toplevelTweezijdigeTekstVergelijkingsOperatorMeervoud ;
toplevelTweezijdigeTekstVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (GELIJK IS AAN | ONGELIJK IS AAN) ; // EBNF §13.4.15.35
toplevelTweezijdigeTekstVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (GELIJK ZIJN AAN | ONGELIJK ZIJN AAN) ; // EBNF §13.4.15.36

toplevelEenzijdigeBooleanVergelijkingsOperator : toplevelEenzijdigeBooleanVergelijkingsOperatorEnkelvoud | toplevelEenzijdigeBooleanVergelijkingsOperatorMeervoud ;
toplevelEenzijdigeBooleanVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (LEEG IS | GEVULD IS) ; // EBNF §13.4.15.38
toplevelEenzijdigeBooleanVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (LEEG ZIJN | GEVULD ZIJN) ; // EBNF §13.4.15.39
toplevelTweezijdigeBooleanVergelijkingsOperator : toplevelTweezijdigeBooleanVergelijkingsOperatorEnkelvoud | toplevelTweezijdigeBooleanVergelijkingsOperatorMeervoud ;
toplevelTweezijdigeBooleanVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (GELIJK IS AAN | ONGELIJK IS AAN) ; // EBNF §13.4.15.41
toplevelTweezijdigeBooleanVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (GELIJK ZIJN AAN | ONGELIJK ZIJN AAN) ; // EBNF §13.4.15.42

toplevelEenzijdigeObjectVergelijkingsOperator : toplevelEenzijdigeObjectVergelijkingsOperatorEnkelvoud | toplevelEenzijdigeObjectVergelijkingsOperatorMeervoud ;
toplevelEenzijdigeObjectVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? EEN? (rolNaam | kenmerkNaam) (IS | HEEFT) ; // EBNF §13.4.15.44
toplevelEenzijdigeObjectVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? EEN? (rolNaam | kenmerkNaam) (ZIJN | HEBBEN) ; // EBNF §13.4.15.45
toplevelTweezijdigeObjectVergelijkingsOperator : toplevelTweezijdigeObjectVergelijkingsOperatorEnkelvoud | toplevelTweezijdigeObjectVergelijkingsOperatorMeervoud ;
toplevelTweezijdigeObjectVergelijkingsOperatorEnkelvoud : gehelePeriodevergelijking? (GELIJK IS AAN | ONGELIJK IS AAN) ; // EBNF §13.4.15.47
toplevelTweezijdigeObjectVergelijkingsOperatorMeervoud : gehelePeriodevergelijking? (GELIJK ZIJN AAN | ONGELIJK ZIJN AAN) ; // EBNF §13.4.15.48

// Non-toplevel Operators (nested inside conditions)
eenzijdigeGetalVergelijkingsOperator : eenzijdigeGetalVergelijkingsOperatorEnkelvoud | eenzijdigeGetalVergelijkingsOperatorMeervoud ;
eenzijdigeGetalVergelijkingsOperatorEnkelvoud : (IS gehelePeriodevergelijking? (LEEG | GEVULD)) | (VOLDOET gehelePeriodevergelijking? AAN_DE_ELFPROEF) ; // EBNF §13.4.15.72
eenzijdigeGetalVergelijkingsOperatorMeervoud : (ZIJN gehelePeriodevergelijking? (LEEG | GEVULD)) | (VOLDOEN gehelePeriodevergelijking? AAN_DE_ELFPROEF) ; // EBNF §13.4.15.73
tweezijdigeGetalVergelijkingsOperator : tweezijdigeGetalVergelijkingsOperatorEnkelvoud | tweezijdigeGetalVergelijkingsOperatorMeervoud ;
tweezijdigeGetalVergelijkingsOperatorEnkelvoud : IS gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN | GROTER_DAN | GROTER_OF_GELIJK_AAN | KLEINER_OF_GELIJK_AAN | KLEINER_DAN) ; // EBNF §13.4.15.75
tweezijdigeGetalVergelijkingsOperatorMeervoud : ZIJN gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN | GROTER_DAN | GROTER_OF_GELIJK_AAN | KLEINER_OF_GELIJK_AAN | KLEINER_DAN) ; // EBNF §13.4.15.76

eenzijdigeDatumVergelijkingsOperator : eenzijdigeDatumVergelijkingsOperatorEnkelvoud | eenzijdigeDatumVergelijkingsOperatorMeervoud ;
eenzijdigeDatumVergelijkingsOperatorEnkelvoud : (IS gehelePeriodevergelijking? (LEEG | GEVULD)) | (gehelePeriodevergelijking? EEN dagsoortNaam IS) ; // EBNF §13.4.15.78
eenzijdigeDatumVergelijkingsOperatorMeervoud : (ZIJN gehelePeriodevergelijking? (LEEG | GEVULD)) | (gehelePeriodevergelijking? EEN dagsoortNaam ZIJN) ; // EBNF §13.4.15.79
tweezijdigeDatumVergelijkingsOperator : tweezijdigeDatumVergelijkingsOperatorEnkelvoud | tweezijdigeDatumVergelijkingsOperatorMeervoud ;
tweezijdigeDatumVergelijkingsOperatorEnkelvoud : IS gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN | LATER_DAN | LATER_OF_GELIJK_AAN | EERDER_OF_GELIJK_AAN | EERDER_DAN) ; // EBNF §13.4.15.81
tweezijdigeDatumVergelijkingsOperatorMeervoud : ZIJN gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN | LATER_DAN | LATER_OF_GELIJK_AAN | EERDER_OF_GELIJK_AAN | EERDER_DAN) ; // EBNF §13.4.15.82

eenzijdigeTekstVergelijkingsOperator : eenzijdigeTekstVergelijkingsOperatorEnkelvoud | eenzijdigeTekstVergelijkingsOperatorMeervoud ;
eenzijdigeTekstVergelijkingsOperatorEnkelvoud : (IS gehelePeriodevergelijking? (LEEG | GEVULD | (NUMERIEK_MET_EXACT integerLiteral CIJFERS))) | (VOLDOET gehelePeriodevergelijking? AAN_DE_ELFPROEF) ; // EBNF §13.4.15.84
eenzijdigeTekstVergelijkingsOperatorMeervoud : (ZIJN gehelePeriodevergelijking? (LEEG | GEVULD | (NUMERIEK_MET_EXACT integerLiteral CIJFERS))) | (VOLDOEN gehelePeriodevergelijking? AAN_DE_ELFPROEF) ; // EBNF §13.4.15.85
tweezijdigeTekstVergelijkingsOperator : tweezijdigeTekstVergelijkingsOperatorEnkelvoud | tweezijdigeTekstVergelijkingsOperatorMeervoud ;
tweezijdigeTekstVergelijkingsOperatorEnkelvoud : IS gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN) ; // EBNF §13.4.15.87
tweezijdigeTekstVergelijkingsOperatorMeervoud : ZIJN gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN) ; // EBNF §13.4.15.88

eenzijdigeBooleanVergelijkingsOperator : eenzijdigeBooleanVergelijkingsOperatorEnkelvoud | eenzijdigeBooleanVergelijkingsOperatorMeervoud ;
eenzijdigeBooleanVergelijkingsOperatorEnkelvoud : IS gehelePeriodevergelijking? (LEEG | GEVULD) ; // EBNF §13.4.15.90
eenzijdigeBooleanVergelijkingsOperatorMeervoud : ZIJN gehelePeriodevergelijking? (LEEG | GEVULD) ; // EBNF §13.4.15.91
tweezijdigeBooleanVergelijkingsOperator : tweezijdigeBooleanVergelijkingsOperatorEnkelvoud | tweezijdigeBooleanVergelijkingsOperatorMeervoud ;
tweezijdigeBooleanVergelijkingsOperatorEnkelvoud : IS gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN) ; // EBNF §13.4.15.93
tweezijdigeBooleanVergelijkingsOperatorMeervoud : ZIJN gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN) ; // EBNF §13.4.15.94

eenzijdigeObjectVergelijkingsOperator : eenzijdigeObjectVergelijkingsOperatorEnkelvoud | eenzijdigeObjectVergelijkingsOperatorMeervoud ;
eenzijdigeObjectVergelijkingsOperatorEnkelvoud : (IS | HEEFT) gehelePeriodevergelijking? EEN? kenmerkNaam ; // EBNF §13.4.15.96
eenzijdigeObjectVergelijkingsOperatorMeervoud : (ZIJN | HEBBEN) gehelePeriodevergelijking? EEN? kenmerkNaam ; // EBNF §13.4.15.97
tweezijdigeObjectVergelijkingsOperator : tweezijdigeObjectVergelijkingsOperatorEnkelvoud | tweezijdigeObjectVergelijkingsOperatorMeervoud ;
tweezijdigeObjectVergelijkingsOperatorEnkelvoud : IS gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN) ; // EBNF §13.4.15.99
tweezijdigeObjectVergelijkingsOperatorMeervoud : ZIJN gehelePeriodevergelijking? (GELIJK_AAN | ONGELIJK_AAN) ; // EBNF §13.4.15.100

// Period comparison constructs (non-toplevel elementair usage) EBNF §13.4.15.67-69
periodevergelijking : periodevergelijkingEnkelvoudig | periodevergelijkingElementair ;
periodevergelijkingEnkelvoudig // Used in voorwaardeDeel and conditieBijExpressie
    : VANAF datumExpressie
    | VAN datumExpressie TOT datumExpressie
    | VAN datumExpressie TOT_EN_MET datumExpressie
    | TOT datumExpressie
    | TOT_EN_MET datumExpressie
    ;
periodevergelijkingElementair // Used in elementaireVoorwaarde
    : HET IS DE PERIODE periodevergelijkingEnkelvoudig
    ;


// --- Expressions (EBNF §13.4.16 Berekening, §13.4.17 Expressie) ---
// Using left-recursion and precedence for expression parsing
expressie // EBNF §13.4.17.1
    : getalExpressie
    | objectExpressie
    | datumExpressie
    | tekstExpressie
    | booleanExpressie
    | parameterMetLidwoord // <parametermetlidwoord>
    | variabeleNaam        // <variabelenaam>
    | APOSTROPHE enumeratieWaarde APOSTROPHE // <enumeratiewaarde>
    | concatenatie         // <concatenatie>
    ;

// Precedence approach for getalExpressie (example - others similar)
// §13.4.17.3 <getalexpressie> RHS lists begrenzingexpressie | afrondingexpressie |
// getalfunctie | getalaggregatie | attribuutvanonderwerp | getalwaarde | rekenjaar |
// jaaruitfunctie | maanduitfunctie | daguitfunctie.
// Source defect: the four v2.3.0 datetime-extraction functions (§13.4.17.21-24) are
// NOT wired into <getalexpressie> in the source, even though they yield numbers.
// They are added here so they can be used numerically, as the changelist intends.
getalExpressie // EBNF §13.4.17.3
    : <assoc=right> getalExpressie (PLUS | MIN) getalExpressie           # AddSubExpr
    | <assoc=right> getalExpressie (MAAL | GEDEELD_DOOR | GEDEELD_DOOR_ABS | VERMINDERD_MET) getalExpressie # MulDivExpr
    | <assoc=right> machtsVerheffenFunctie                             # PowExpr
    | <assoc=right> wortelFunctie                                      # SqrtExpr
    | <assoc=right> absoluteWaardeFunctie                              # AbsValExpr
    | <assoc=right> minimaleWaardeFunctie                              # MinValExpr
    | <assoc=right> maximaleWaardeFunctie                              # MaxValExpr
    | <assoc=right> percentageFunctie                                  # PercentageFuncExpr
    | <assoc=right> jaarUitFunctie                                     # YearFromDateExpr
    | <assoc=right> maandUitFunctie                                    # MonthFromDateExpr
    | <assoc=right> dagUitFunctie                                      # DayFromDateExpr
    | <assoc=right> uurUitFunctie                                      # HourFromDateExpr   // §13.4.17.21 (added to numeric expr)
    | <assoc=right> minuutUitFunctie                                   # MinuteFromDateExpr // §13.4.17.22 (added to numeric expr)
    | <assoc=right> secondeUitFunctie                                  # SecondFromDateExpr // §13.4.17.23 (added to numeric expr)
    | <assoc=right> millisecondeUitFunctie                             # MillisFromDateExpr // §13.4.17.24 (added to numeric expr)
    | <assoc=right> afrondingExpressie                                 # RoundingExpr
    | <assoc=right> begrenzingExpressie                                # BoundingExpr
    | <assoc=right> getalAggregatie                                    # NumberAggExpr
    | <assoc=right> attribuutVanOnderwerp                              # AttrRefExpr
    | <assoc=right> parameterMetLidwoord                               # ParamRefExpr
    | <assoc=right> variabeleNaam                                      # VarRefExpr
    | <assoc=right> getalWaarde                                        # NumberLiteralExpr
    | <assoc=right> REKENJAAR                                          # RekenjaarExpr
    | <assoc=right> LPAREN getalExpressie RPAREN                       # ParenGetalExpr
    ;
    // NOTE: The `berekening` rule §13.4.16 is implicitly handled by the precedence in `getalExpressie`

// Other Expression Types
datumExpressie // EBNF §13.4.17.4
    : datumFunctie
    | attribuutVanOnderwerp
    | parameterMetLidwoord
    | variabeleNaam
    | deDato // Date literal
    | datumAggregatie
    | LPAREN datumExpressie RPAREN
    ;
objectExpressie // EBNF §13.4.17.5
    : kwantificatie? onderwerpKeten
    | LPAREN objectExpressie RPAREN
    ;
tekstExpressie // EBNF §13.4.17.6
    : tekstenWaardeReeks
    | tekstWaarde // String literal
    | attribuutVanOnderwerp
    | parameterMetLidwoord
    | variabeleNaam
    | LPAREN tekstExpressie RPAREN
    ;
booleanExpressie // EBNF §13.4.17.7
    : booleanWaarde // Boolean literal
    | attribuutVanOnderwerp
    | parameterMetLidwoord
    | variabeleNaam
    | LPAREN booleanExpressie RPAREN
    ;

expressieTussenHaakjes // EBNF §13.4.17.8 (General form, might be covered by specific types)
    : LPAREN expressie RPAREN
    ;

concatenatie // EBNF §13.4.17.2
    : expressie (COMMA expressie)* (EN | OF) expressie
    ;

tekstenWaardeReeks // EBNF §13.4.17.9
    : STRING_LITERAL // Simplified: Handle interpolation in visitor/engine
    // TODO: Revisit §13.4.17.9 parsing strategy - likely needs custom lexer mode or complex parsing
    // Simplified: Treat as single string for now; interpolation is semantic.
    // Original proposal: STRING_LITERAL_START (L_ANGLE_QUOTE expressie R_ANGLE_QUOTE | identifier)+ STRING_LITERAL_END
    ;

// Functions (EBNF §13.4.17.10+)
functie : datumFunctie | getalFunctie ; // EBNF §13.4.17.10

getalFunctie // EBNF §13.4.17.11 & others
    : percentageFunctie
    | wortelFunctie
    | minimaleWaardeFunctie
    | maximaleWaardeFunctie
    | tijdsduurTussen // Produces number? §13.4.17.32 suggests time duration, which might need its own type
    | absoluteWaardeFunctie
    | jaarUitFunctie
    | maandUitFunctie
    | dagUitFunctie
    | uurUitFunctie        // §13.4.17.21
    | minuutUitFunctie     // §13.4.17.22
    | secondeUitFunctie    // §13.4.17.23
    | millisecondeUitFunctie // §13.4.17.24
    | machtsVerheffenFunctie
    ;
percentageFunctie : getalExpressie PERCENT_SIGN? VAN getalExpressie ; // EBNF §13.4.17.12
wortelFunctie : WORTEL_VAN getalExpressie afronding? ; // EBNF §13.4.17.13
machtsVerheffenFunctie : getalExpressie TOT_DE_MACHT getalExpressie afronding? ; // EBNF §13.4.17.14
minimaleWaardeFunctie : MINIMALE_WAARDE_VAN getalExpressie (COMMA getalExpressie)* EN getalExpressie ; // EBNF §13.4.17.15
maximaleWaardeFunctie : MAXIMALE_WAARDE_VAN getalExpressie (COMMA getalExpressie)* EN getalExpressie ; // EBNF §13.4.17.16
absoluteWaardeFunctie : ABSOLUTE_WAARDE_VAN LPAREN getalExpressie RPAREN ; // EBNF §13.4.17.17
jaarUitFunctie : HET JAAR UIT datumExpressie ; // EBNF §13.4.17.18
maandUitFunctie : DE MAAND UIT datumExpressie ; // EBNF §13.4.17.19
dagUitFunctie : DE DAG UIT datumExpressie ; // EBNF §13.4.17.20
uurUitFunctie : HET_UUR_UIT datumExpressie ; // EBNF §13.4.17.21
minuutUitFunctie : DE_MINUUT_UIT datumExpressie ; // EBNF §13.4.17.22
secondeUitFunctie : DE_SECONDE_UIT datumExpressie ; // EBNF §13.4.17.23
millisecondeUitFunctie : DE_MILLISECONDE_UIT datumExpressie ; // EBNF §13.4.17.24

afrondingExpressie : getalExpressie afronding ; // EBNF §13.4.17.25
afronding : (NAAR_BENEDEN | NAAR_BOVEN | REKENKUNDIG | RICHTING_NUL | WEG_VAN_NUL) AFGEROND_OP integerLiteral DECIMALEN ; // EBNF §13.4.17.26

begrenzingExpressie : getalExpressie COMMA begrenzing ; // EBNF §13.4.17.27
begrenzing : (begrenzingMinimum | begrenzingMaximum | begrenzingMinimum EN begrenzingMaximum) ; // EBNF §13.4.17.28
begrenzingMinimum : MET_EEN_MINIMUM_VAN getalExpressie ; // EBNF §13.4.17.29
begrenzingMaximum : MET_EEN_MAXIMUM_VAN getalExpressie ; // EBNF §13.4.17.30

datumFunctie // EBNF §13.4.17.33 (datumtijdmet added in v2.3.0)
    : datumMet
    | datumTijdMet
    | eerstePaasdagVan
    | deDato // Included here as it's a way to get a date value
    | datumBerekening
    | eersteVan
    | laatsteVan
    | REKENDATUM // EBNF §13.4.17.34
    ;
tijdsduurTussen : (TIJDSDUUR_VAN | ABSOLUTE_TIJDSDUUR_VAN) datumExpressie TOT datumExpressie IN IN_HELE? eenheidMeervoud ; // EBNF §13.4.17.32

// EBNF §13.4.17.35 Datum met (v2.3.0: labeled colon syntax, no closing paren)
datumMet
    : DE_DATUM_MET_JAAR getalExpressie COMMA_MAAND getalExpressie EN_DAG getalExpressie
    ;

// EBNF §13.4.17.36 Datumtijd met (new in v2.3.0)
datumTijdMet
    : DE_DATUMTIJD_MET_JAAR getalExpressie
      COMMA_MAAND getalExpressie
      COMMA_DAG getalExpressie
      COMMA_UUR getalExpressie
      COMMA_MINUUT getalExpressie
      COMMA_SECONDE getalExpressie
      COMMA_MILLISECONDE getalExpressie
    ;

// EBNF §13.4.17.37 Eerste paasdag van (v2.3.0: parentheses dropped)
eerstePaasdagVan : EERSTE_PAASDAG_VAN jaar ;
jaar : NUMBER ; // Year literal
datumBerekening : datumExpressie (PLUS | MIN) getalExpressie eenheidNaam ; // EBNF §13.4.17.38
eersteVan : EERSTE_VAN datumExpressie (COMMA datumExpressie)* EN datumExpressie ; // EBNF §13.4.17.39
laatsteVan : LAATSTE_VAN datumExpressie (COMMA datumExpressie)* EN datumExpressie ; // EBNF §13.4.17.40
deDato : datumLiteral ; // EBNF §13.2.19


// References EBNF §13.4.17.41-44
referentie
    : bezieldeReferentie
    | nietBezieldeReferentie
    | dagsoortReferentie
    ;
bezieldeReferentie // EBNF §13.4.17.42
    : HIJ
    | ZIJN attribuutNaam
    | ZIJN rolNaam
    ;
nietBezieldeReferentie // EBNF §13.4.17.43
    : bepaaldLidwoord objectTypeNaam // <objecttypemetlidwoord>
    ;
dagsoortReferentie // EBNF §13.4.17.44
    : DE DAG
    ;


// Aggregations EBNF §13.4.17.45-58
aggregatie
    : getalAggregatie
    | datumAggregatie
    | dimensieAggregatie
    | waardePerTijdseenheidAggregatie
    | tellingAantalDagen
    | tijdsevenredigDeel
    ;
getalAggregatie // EBNF §13.4.17.46
    : getalAggregatieFunctie LPAREN expressie RPAREN // Simplified from EBNF, assuming parentheses wrap the target
    ;
getalAggregatieFunctie // EBNF §13.4.17.47
    : AANTAL
    | MAXIMALE_WAARDE_VAN
    | MINIMALE_WAARDE_VAN
    | SOM_VAN
    ;
datumAggregatie // EBNF §13.4.17.48
    : datumAggregatieFunctie LPAREN expressie RPAREN // Simplified from EBNF
    ;
datumAggregatieFunctie // EBNF §13.4.17.49
    : EERSTE_VAN
    | LAATSTE_VAN
    ;
dimensieAggregatie // EBNF §13.4.17.50
    : (getalAggregatieFunctie | datumAggregatieFunctie) attribuutVanOnderwerp dimensieSelectie
    ;
dimensieSelectie // EBNF §13.4.17.51
    : OVER (aggregerenOverAlleDimensies | aggregerenOverVerzameling | aggregerenOverBereik) DOT
    ;
aggregerenOverAlleDimensies // EBNF §13.4.17.52
    : ALLE dimensieNaamMeervoud
    ;
aggregerenOverVerzameling // EBNF §13.4.17.53
    : DE dimensieNaamMeervoud VANAF dimensieWaarde TM dimensieWaarde
    ;
aggregerenOverBereik // EBNF §13.4.17.54
    : DE dimensieNaamMeervoud IN LBRACE dimensieWaarde (COMMA dimensieWaarde)* EN dimensieWaarde RBRACE
    ;
conditieBijExpressie // EBNF §13.4.17.55
    : GEDURENDE_DE_TIJD_DAT (toplevelElementaireVoorwaarde | toplevelSamengesteldeVoorwaarde)
    | periodevergelijkingEnkelvoudig
    ;
waardePerTijdseenheidAggregatie // EBNF §13.4.17.56
    : TOTAAL_VAN expressie conditieBijExpressie?
    ;
tellingAantalDagen // EBNF §13.4.17.57
    : AANTAL_DAGEN_IN (DE MAAND | HET JAAR) DAT expressie
    ;
tijdsevenredigDeel // EBNF §13.4.17.58
    : TIJDSEVENREDIG_DEEL_PER (MAAND | JAAR) VAN expressie conditieBijExpressie
    ;


// --- Beslistabel Placeholder ---
beslistabel
    : BESLISTABEL naam=identifier NEWLINE?
      // Actual beslistabel format is outside grammar scope
    ;

```

---

## 4. Illustrative Examples

**(Parsing descriptions updated based on the EBNF-aligned rules described above)**

**Example 1: Object Type Definition (Ref: §3.9)**

```regelspraak
Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
    is minderjarig kenmerk (bijvoeglijk);
    het recht op duurzaamheidskorting kenmerk (bezittelijk);
    het identificatienummer Numeriek (positief geheel getal);
    de geboortedatum Datum in dagen;
    de leeftijd Numeriek (niet-negatief geheel getal) met eenheid jr;
    de snelheid Numeriek (getal) met eenheid km/u;
```

*ANTLR Parsing Description:*
*   `objectTypeDefinition` matches.
*   `OBJECTTYPE`, `naamwoord` (`DE`, `IDENTIFIER`:`Natuurlijk persoon`), `MV_START`, `IDENTIFIER`:`Natuurlijke personen`, `RPAREN`, `BEZIELD`.
*   `objectTypeMember` loop:
    *   `kenmerkSpecificatie` matches `bijvoeglijkKenmerk` (`IS`, `IDENTIFIER`:`minderjarig`, `KENMERK`, `BIJVOEGLIJK`), `SEMICOLON`.
    *   `kenmerkSpecificatie` matches `bezittelijkKenmerk` (`naamwoord`(`HET`, `IDENTIFIER`:`recht op duurzaamheidskorting`), `KENMERK`, `BEZITTELIJK`), `SEMICOLON`.
    *   `attribuutSpecificatie` matches `naamwoord` (`HET`, `IDENTIFIER`:`identificatienummer`), `numeriekDatatype`, `SEMICOLON`.
    *   `attribuutSpecificatie` matches `naamwoord` (`DE`, `IDENTIFIER`:`geboortedatum`), `datumTijdDatatype` (`DATUM_IN_DAGEN`), `SEMICOLON`.
    *   `attribuutSpecificatie` matches `naamwoord` (`DE`, `IDENTIFIER`:`leeftijd`), `numeriekDatatype`, `MET_EENHEID`, `eenheidExpressie` (`eenheidMacht`(`IDENTIFIER`:`jr`)), `SEMICOLON`.
    *   `attribuutSpecificatie` matches `naamwoord` (`DE`, `IDENTIFIER`:`snelheid`), `numeriekDatatype`, `MET_EENHEID`, `eenheidExpressie` (`eenheidMacht`(`IDENTIFIER`:`km`) `SLASH` `eenheidMacht`(`IDENTIFIER`:`u`)), `SEMICOLON`.


**Example 2: Simple Rule - Gelijkstelling (Ref: §4.4, §9.1)**

```regelspraak
Regel bepaal leeftijd
    geldig altijd
        De leeftijd van een Natuurlijk persoon moet berekend worden als de tijdsduur van zijn geboortedatum tot Rekendatum in hele jaren.
```

*ANTLR Parsing Description:*
*   `regel` matches.
*   `REGEL`, `regelNaam` (`IDENTIFIER`:`bepaal leeftijd`).
*   `regelVersie`: `versie` (`GELDIG`, `ALTIJD`).
*   `regelSpraakRegel`:
    *   `resultaatDeel` is `gelijkstelling`: `attribuutVanOnderwerp`, `WORDT_BEREKEND_ALS`, `datumExpressie` (matching `tijdsduurTussen` structure).
    *   No `voorwaardeDeel` or `variabeleDeel`.

**Example 3: Rule with Condition and Variable (Ref: §4.4, §10.1, §11)**

```regelspraak
Parameter het volwassenleeftijd : Numeriek (geheel getal) met eenheid jr;
Regel Kenmerktoekenning persoon minderjarig
geldig altijd
Een Natuurlijk persoon is minderjarig
indien X kleiner is dan het volwassenleeftijd.
Daarbij geldt:
X is de tijdsduur van zijn geboortedatum tot Rekendatum in hele jaren.
```

*ANTLR Parsing Description:*
*   `parameterDefinition` matches.
*   `regel` matches.
*   `REGEL`, `regelNaam` (`IDENTIFIER`:`Kenmerktoekenning persoon minderjarig`).
*   `regelVersie`: `versie` (`GELDIG`, `ALTIJD`).
*   `regelSpraakRegel`:
    *   `resultaatDeel` is `kenmerkToekenning`.
    *   `voorwaardeDeel`: `INDIEN`, `toplevelElementaireVoorwaarde` (`toplevelGetalVergelijking`: `getalExpressie` (`variabeleNaam`:`X`), `toplevelTweezijdigeGetalVergelijkingsOperatorEnkelvoud` (matching `kleiner is dan`), `getalExpressie` (`parameterMetLidwoord`)).
    *   `DOT`.
    *   `variabeleDeel`: `DAARBIJ_GELDT`, `variabeleOnderdeel` (`variabeleNaam`:`X`, `IS`, `datumExpressie` for `tijdsduurTussen`).
    *   `DOT`.

**Example 4: Samengestelde Voorwaarde (Ref: §10.2)**

```regelspraak
indien hij aan alle volgende voorwaarden voldoet:
• zijn reis is duurzaam
• de afstand tot bestemming van zijn reis groter of gelijk is aan 500 km.
```

*ANTLR Parsing Description (within `voorwaardeDeel`):*
*   `INDIEN`.
*   `toplevelSamengesteldeVoorwaarde`: `referentie` (`HIJ`), `AAN`, `voorwaardeKwantificatie` (`ALLE`), `VOLGENDE_VOORWAARDEN`, `VOLDOET`, `COLON`.
*   `samengesteldeVoorwaardeOnderdeel`:
    *   `genesteVoorwaarde`: `BULLET`, `elementaireVoorwaarde` (`objectVergelijking`: `objectExpressie` (`zijn reis`), `eenzijdigeObjectVergelijkingsOperatorEnkelvoud` (matching `is duurzaam`), `kenmerkNaam` (`duurzaam`)).
    *   `genesteVoorwaarde`: `BULLET`, `elementaireVoorwaarde` (`getalVergelijking`: `getalExpressie` (`attribuutVanOnderwerp`), `tweezijdigeGetalVergelijkingsOperatorEnkelvoud` (matching `groter of gelijk is aan`), `getalExpressie` (`getalWaarde`:`500 km`)).
*   Ends with `DOT`.

**Example 5: Tijdlijndefinitie and Startpuntbepaling (Ref: §13.3.6, §13.4.12 — new in v2.3.0)**

```regelspraak
Tijdlijn de boekingsperiode heeft tijdvakken van een maand startend op dag 1 van de maand
Regel bepaal startpunt boekingsperiode
geldig altijd
Het startpunt van de boekingsperiode voor een Natuurlijk persoon wordt bepaald door zijn instroomdatum.
```

*ANTLR Parsing Description:*
*   `tijdlijnDefinitie`: `TIJDLIJN`, `tijdlijnNaam` (`naamwoord`:`de boekingsperiode`), `HEEFT_TIJDVAKKEN_VAN`, `EEN`, `MAAND`, `vastStartpuntMaand` (`STARTEND_OP_DAG`, `dag`:`1`, `VAN_DE_MAAND`).
*   `regel` → `regelSpraakRegel` → `resultaatDeel` is `startpuntBepaling`:
    *   `HET_STARTPUNT_VAN`, `tijdlijnNaam` (`de boekingsperiode`), `VOOR`, `universeelOnderwerp` (`onbepaaldLidwoord`:`een`, `objectTypeNaam`:`Natuurlijk persoon`), `WORDT_BEPAALD_DOOR`, `datumExpressie` (`bezieldeReferentie`/`attribuutVanOnderwerp`:`zijn instroomdatum`).

**Example 6: Ordinal Subject Reference (Ref: §13.2.12, §13.4.1.3, §13.4.2.9 — new in v2.3.0)**

```regelspraak
De bijdrage van de als tweede genoemde passagier ...
```

*ANTLR Parsing Description (within `attribuutVanOnderwerp`):*
*   First alternative `bepaaldLidwoord rangtelwoord attribuutNaam` is not taken here because `bijdrage` follows `de`; instead the subject side uses the ordinal: within `onderwerpKeten` → `onderwerpVerwijzing` (`bepaaldLidwoord`:`de`, `rangtelwoord` (`ALS_TWEEDE_GENOEMDE`), `rolNaam`:`passagier`).

**Example 7: Datumtijd met (Ref: §13.4.17.36 — new in v2.3.0)**

```regelspraak
de datumtijd met jaar: 2025, maand: 6, dag: 16, uur: 9, minuut: 30, seconde: 0 en milliseconde: 0
```

*ANTLR Parsing Description (within `datumExpressie` → `datumFunctie` → `datumTijdMet`):*
*   `DE_DATUMTIJD_MET_JAAR`, `getalExpressie`(`2025`), `COMMA_MAAND`, `getalExpressie`(`6`), `COMMA_DAG`, `getalExpressie`(`16`), `COMMA_UUR`, `getalExpressie`(`9`), `COMMA_MINUUT`, `getalExpressie`(`30`), `COMMA_SECONDE`, `getalExpressie`(`0`), `COMMA_MILLISECONDE`, `getalExpressie`(`0`).

---

## 5. Handling Specific Constructs

*   **Identifiers with Spaces:** The `IDENTIFIER` lexer rule is designed to match multi-word names. Keyword precedence in the lexer is crucial. Ensure keywords are listed before `IDENTIFIER`. Longer keywords should precede shorter ones they contain. The v2.3.0 labeled-colon datetime literals (`'de datumtijd met jaar:'`, `', maand:'`, etc.) embed punctuation; they must precede the bare `COMMA`/`COLON` punctuation tokens so the lexer matches the full multi-word phrase.
*   **Expression Precedence:** ANTLR's left-recursive rule structure with labeled alternatives (`# LabelName`) is used in the `getalExpressie` example to handle precedence. Similar structures should be applied to other expression types (`datumExpressie`, `tekstExpressie`, `booleanExpressie`) if they involve operators with different precedence levels. Parentheses `()` naturally handle explicit precedence.
*   **Datetime extraction (v2.3.0):** `uurUitFunctie`, `minuutUitFunctie`, `secondeUitFunctie`, and `millisecondeUitFunctie` mirror the existing `jaarUitFunctie`/`maandUitFunctie`/`dagUitFunctie` family. The source §13.4.17.3 `<getalexpressie>` does **not** list them as alternatives (a source omission); since they evaluate to numbers, they are wired into `getalExpressie` and `getalFunctie` here so they can be used wherever a numeric value is expected.
*   **Units:** The parser implements `eenheidExpressie` for defining units. Semantic validation (compatibility) occurs post-parsing. `getalWaarde` allows optional units.
*   **Tijdlijnen/Tijdsafhankelijkheid (v2.3.0):** The `tijdlijn` rule now covers single periods (`enkelvoudigePeriode`), plural periods (`meervoudigePeriode`), and references to named timelines (`ELKE tijdlijnNaam`). Fixed start points (`vastStartpuntDatum`/`vastStartpuntMaand`/`vastStartpuntKwartaal`/`vastStartpuntJaar`) qualify the period. `tijdlijnDefinitie` declares a named timeline with its tijdvak length; its source EBNF (§13.3.6.10) has an unbalanced parenthesis, modelled here as a singular (`'een' ...`) versus plural (`tijdseenhedenMeervoud ...`) choice. `startpuntBepaling` (§13.4.12.1) is a new `resultaatDeel` alternative that assigns a timeline's start point from a `datumExpressie`. The runtime engine handles semantic implications.
*   **Onderwerpketen / ordinals (v2.3.0):** `onderwerpKeten` is split into `universeelOnderwerp` (`onbepaaldLidwoord ...`), `onderwerpVerwijzing` (`bepaaldLidwoord rangtelwoord? ...`), navigation (`selector VAN onderwerpKeten`), possessive (`ZIJN (rolNaam | attribuutNaam)`), and `subselectie`. The source §13.4.1.1 RHS has one unmatched `(`; modelled here as the intended flat five-way choice. `selector` now requires a `lidwoord` and accepts an `attribuutNaam`. `attribuutVanOnderwerp` gains an ordinal alternative (`bepaaldLidwoord rangtelwoord attribuutNaam`).
*   **Beslistabellen (Chapter 12):** The grammar includes a placeholder `beslistabel`. Parsing the *tabular layout* is outside scope; the grammar should parse RegelSpraak fragments *within* cells.
*   **Unicode:** Lexer fragments use Unicode properties (`\p{L}`, `\p{Nd}`) for broad character support.
*   **Ambiguity (`zijn`, `is`):** Context in the parser rules is the primary way to disambiguate (e.g., `kenmerkToekenning` expects `IS` or `HEEFT` followed by a name, while operators expect specific phrases like `IS GELIJK_AAN`). The possessive `ZIJN` is handled in `onderwerpKeten` and `bezieldeReferentie`.
*   **EBNF Completeness & Accuracy:** This specification aims to cover all EBNF rules from Chapter 13 of v2.3.0. Several source productions carry defects (unbalanced parentheses in §13.3.6.10, §13.4.1.1; stray spaces in §13.4.12.1; the omission of the datetime functions from §13.4.17.3). Each is noted with a `//` comment at the relevant rule and modelled in the buildable, intended form. Careful comparison with the source EBNF and extensive testing remain vital.
*   **Operator Complexity:** The grammar explicitly defines rules for singular/plural and toplevel/nested operator forms based on EBNF §13.4.15.
*   **Natural Language Structure:** The grammar attempts to follow the natural language patterns where specified (e.g., articles, verb forms), but relies heavily on keyword matching.
*   **Readability vs. Strictness:** The reordered parser aims for better readability by grouping related concepts.
