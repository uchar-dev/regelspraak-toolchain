// RegelSpraak.g4 — parser for RegelSpraak v2.3.0 (GegevensSpraak + RegelSpraak).
//
// Non-terminals mirror Chapter 13 of the spec (the oracle). The authoritative
// syntax diagrams drive structure; the ANTLR-targeted draft is cross-checked,
// not copied. Where a source production is defective (unbalanced parens, stray
// spaces, missing branches) the intended form is reconstructed and noted.
//
// NAMING STRATEGY (see RegelSpraakLexer.g4 header note 2). Multi-word names are
// assembled here from single-word tokens. RegelSpraak's capitalisation
// convention is load-bearing: objecttype/domain names are UPPER_ID-led, while
// attribute/role/kenmerk names are LOWER_ID-led and may embed function words.
// That convention is exactly what lets a context-free parser split
// "de belasting op basis van afstand Bedrag" (lowercase name + capitalised
// domain) and "de reis Vlucht" (lowercase role + capitalised objecttype) with no
// symbol table. Genuinely context-sensitive boundaries that capitalisation does
// NOT resolve — notably the feittype cardinality line, where a role name abuts a
// free-text relatiebeschrijving — are captured as a token run for the resolver
// to split (per CLAUDE.md: name resolution is post-parse). Such deferrals are
// flagged at the rule.

parser grammar RegelSpraak;

options { tokenVocab = RegelSpraakLexer; }

// ====================================================================
// Document
// ====================================================================
regelSpraakDocument
    : definitie* EOF
    ;

definitie
    : objectTypeDefinition
    | objectTypeExtensie
    | domeinDefinition
    | eenheidsysteemDefinition
    | parameterDefinition
    | feitTypeDefinition
    | dagsoortDefinition
    | tijdlijnDefinitie
    | dimensieDefinition
    ;

// ====================================================================
// Name building blocks (§13.2.8/.29/.30)
// ====================================================================

// Word tokens admissible inside a LOWERCASE-led name (attribute / kenmerk / role
// / parameter names). RegelSpraak names freely contain function words and
// numerals; the set deliberately EXCLUDES UPPER_ID and every structural keyword
// (datatype names, KENMERK, ';', operators, …) so a name stops at its boundary.
naamWoordLower
    : LOWER_ID | NUMBER | PERCENT_SIGN | AANTAL_WORD
    | VAN | OP | IN | VOOR | OVER | BIJ | TOT | TOT_EN_MET | PER | MET
    | DE | HET | EN | OF | DAN | ALS | NIET
    | JAAR | MAAND | DAG | WEEK | KWARTAAL | UUR | MINUUT | SECONDE | MILLISECONDE
    | PERIODE | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN
    ;

// A lowercase-led name: a run of name words (which excludes UPPER_ID, so the
// name stops at a capitalised type/domain). The leading article, when present,
// is consumed by the caller's bepaaldLidwoord?, leaving the noun to start here.
lowerNaam : naamWoordLower+ ;

// Word tokens admissible in a fully keyword-bounded name that MAY start with a
// capital (regel names, tijdlijn names, parameter names bounded by ':'/'geldig').
naamWoordAny : naamWoordLower | UPPER_ID ;
vrijeNaam : naamWoordAny+ ;

// Capitalised name (objecttype / domein), per the capitalisation convention.
objectTypeNaam : UPPER_ID ( UPPER_ID | LOWER_ID )* ;
domeinNaam     : UPPER_ID ( UPPER_ID | LOWER_ID )* ;
meervoudsvorm  : ( UPPER_ID | LOWER_ID ) ( UPPER_ID | LOWER_ID )* ;

bepaaldLidwoord   : DE | HET ; // §13.2.10
onbepaaldLidwoord : EEN ;      // §13.2.11
lidwoord          : DE | HET | EEN ; // §13.2.9

// ====================================================================
// §13.3.1 Objecttypen
// ====================================================================
objectTypeDefinition // §13.3.1.1
    : OBJECTTYPE bepaaldLidwoord? objectTypeNaam ( MV_START meervoudsvorm RPAREN )? BEZIELD?
      objectTypeMember*
    ;

objectTypeExtensie // §13.3.1.2
    : EXTENSIE_VAN_OBJECTTYPE bepaaldLidwoord objectTypeNaam
      objectTypeMember+
    ;

objectTypeMember
    : koptekst
    | kenmerkSpecificatie
    | attribuutSpecificatie
    ;

koptekst : DASH3 vrijeNaam ; // §13.3.1.5

// §13.3.2.1-.3 Kenmerk (the optional (bijvoeglijk)/(bezittelijk) marker classifies
// it; TOKA shows both the marked and unmarked adjectival form).
kenmerkSpecificatie
    : IS lowerNaam ( MV_START meervoudsvorm RPAREN )? KENMERK BIJVOEGLIJK? SEMICOLON   # bijvoeglijkKenmerk
    | bepaaldLidwoord? lowerNaam KENMERK ( BEZITTELIJK | BIJVOEGLIJK )? SEMICOLON       # naamwoordKenmerk
    ;

// §13.3.2.5 Attribuut. The 'met eenheid' clause lives inside the numeric datatypes
// per the authoritative §13.3.3.2/.3 diagrams (not at member level as the draft has it).
attribuutSpecificatie
    : bepaaldLidwoord? lowerNaam ( datatype | domeinNaam )
      ( GEDIMENSIONEERD_MET dimensieNaam ( EN dimensieNaam )* )?
      tijdlijn?
      SEMICOLON
    ;

// ====================================================================
// §13.3.3 Datatypen
// ====================================================================
datatype // §13.3.3.1
    : numeriekDatatype
    | percentageDatatype
    | tekstDatatype
    | booleanDatatype
    | datumTijdDatatype
    ;
numeriekDatatype   : NUMERIEK LPAREN getalSpecificatie RPAREN ( MET_EENHEID eenheidExpressie )? ; // §13.3.3.2
percentageDatatype : PERCENTAGE LPAREN getalSpecificatie RPAREN ( MET_EENHEID PERCENT_SIGN ( SLASH eenheidAfkorting )? )? ; // §13.3.3.3
tekstDatatype      : TEKST ;    // §13.3.3.4
booleanDatatype    : BOOLEAN ;  // §13.3.3.5
datumTijdDatatype  : DATUM_IN_DAGEN | DATUM_TIJD_MILLIS ; // §13.3.3.6
getalSpecificatie  // §13.3.3.7
    : ( NEGATIEF | NIET_NEGATIEF | POSITIEF )?
      ( GEHEEL_GETAL | GETAL MET NUMBER DECIMALEN | GETAL )
    ;

// ====================================================================
// §13.3.4 Domeinen
// ====================================================================
domeinDefinition // §13.3.4.1
    : DOMEIN domeinNaam IS_VAN_HET_TYPE ( datatype | enumeratieSpecificatie ) SEMICOLON?
    ;
enumeratieSpecificatie // §13.3.4.2 — each value is a self-delimiting quoted literal
    : ENUMERATIE ENUM_LITERAL+
    ;

// ====================================================================
// §13.3.5 Eenheden
// ====================================================================
eenheidsysteemDefinition // §13.3.5.1
    : EENHEIDSYSTEEM eenheidsysteemNaam eenheidDefinitie+
    ;
eenheidsysteemNaam : ( UPPER_ID | LOWER_ID )+ ;
// A unit line: '<lidwoord> <unit-word> [(mv: ...)] <abbreviation>+ [= n abk]'.
// The next unit line starts with an article (DE/HET), so the abbreviation run and
// optional omrekenspecificatie bound this line without needing newline tokens.
eenheidDefinitie
    : bepaaldLidwoord? eenheidWoord ( MV_START meervoudsvorm RPAREN )? eenheidAfkorting* omrekenSpecificatie?
    ;
omrekenSpecificatie // §13.3.5.2 — '1/1000' etc. is a single rational NUMBER token
    : EQUALS NUMBER eenheidAfkorting
    ;
eenheidExpressie : eenheidMacht ( SLASH eenheidMacht )* ; // §13.3.3.2 composite units (km/u)
eenheidMacht     : eenheidAfkorting ( CARET exponent )? ;  // §13.3.5.5
exponent         : NUMBER ;                                // §13.3.5.6
// Unit words / abbreviations may be plain identifiers, the currency glyph, or a
// time-unit keyword used as its own name ("de minuut minuut", "met eenheid minuut").
eenheidWoord
    : LOWER_ID | UPPER_ID
    | UUR | MINUUT | SECONDE | MILLISECONDE | DAG | WEEK | MAAND | KWARTAAL | JAAR
    ;
eenheidAfkorting
    : LOWER_ID | UPPER_ID | EURO
    | UUR | MINUUT | SECONDE | MILLISECONDE | DAG | WEEK | MAAND | KWARTAAL | JAAR
    ;

// ====================================================================
// §13.3.6 Tijdlijnen
// ====================================================================
tijdlijn // §13.3.6.1 — attachment on an attribute/kenmerk/parameter
    : VOOR ( enkelvoudigePeriode | meervoudigePeriode | ELKE tijdlijnNaam )
    ;
enkelvoudigePeriode // §13.3.6.2
    : ELKE_DAG
    | ELKE_WEEK vastStartpuntDatum
    | ELKE_MAAND vastStartpuntMaand?
    | ELK_KWARTAAL vastStartpuntKwartaal?
    | ELK_JAAR vastStartpuntJaar?
    ;
vastStartpuntDatum    : STARTEND_OP datumWaarde ;                 // §13.3.6.3
vastStartpuntMaand    : STARTEND_OP_DAG NUMBER VAN_DE_MAAND ;     // §13.3.6.4
vastStartpuntKwartaal : STARTEND_OP_DAG NUMBER VAN_MAAND kwartaalGroep ; // §13.3.6.5
vastStartpuntJaar     : STARTEND_OP_DAG NUMBER VAN_MAAND NUMBER ; // §13.3.6.6
kwartaalGroep         : NUMBER COMMA NUMBER COMMA NUMBER EN NUMBER ; // '1, 4, 7 en 10' etc.
meervoudigePeriode    : ELKE tijdseenhedenMeervoud vastStartpuntDatum ; // §13.3.6.7
tijdseenhedenMeervoud : NUMBER ( DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN ) ; // §13.3.6.8
tijdlijnNaam          : bepaaldLidwoord? vrijeNaam ; // §13.3.6.9 <naamwoord>
datumWaarde           : DATE | NUMBER ;              // §13.2.20 (bare date or composed)

tijdlijnDefinitie // §13.3.6.10 — source EBNF drops a ')'; reconstructed as singular | plural choice
    : TIJDLIJN tijdlijnNaam HEEFT_TIJDVAKKEN_VAN
      ( EEN
          ( DAG
          | WEEK     ( vastStartpuntDatum    | MET_VARIABEL_STARTPUNT )
          | MAAND    ( vastStartpuntMaand    | MET_VARIABEL_STARTPUNT )
          | KWARTAAL ( vastStartpuntKwartaal | MET_VARIABEL_STARTPUNT )
          | JAAR     ( vastStartpuntJaar     | MET_VARIABEL_STARTPUNT )
          )
      | tijdseenhedenMeervoud ( vastStartpuntDatum | MET_VARIABEL_STARTPUNT )
      )
      SEMICOLON?
    ;

// ====================================================================
// §13.3.7 Dimensies
// ====================================================================
dimensieDefinition // §13.3.7.1
    : DIMENSIE bepaaldLidwoord dimensieNaam BESTAANDE_UIT dimensieNaamMeervoud voorzetselSpecificatie
      labelWaardeSpecificatie+
    ;
voorzetselSpecificatie // §13.3.7.2
    : NA_HET_ATTRIBUUT_MET_VOORZETSEL voorzetsel RPAREN COLON
    | VOOR_HET_ATTRIBUUT_ZONDER_VOORZETSEL
    ;
voorzetsel             : VAN | IN | VOOR | OVER | OP | BIJ | UIT ; // §13.3.7.3
dimensieNaam           : lowerNaam ; // §13.3.7.3
dimensieNaamMeervoud   : lowerNaam ; // §13.3.7.4
labelWaardeSpecificatie: NUMBER DOT dimensieWaarde ; // §13.3.7.5
dimensieWaarde         : lowerNaam ; // §13.3.7.6

// ====================================================================
// §13.3.8 Parameters
// ====================================================================
parameterDefinition // §13.3.8.1 — name bounded by ':'; unit lives inside the datatype
    : PARAMETER bepaaldLidwoord parameterNaam COLON ( datatype | domeinNaam ) SEMICOLON?
    ;
parameterNaam : vrijeNaam ; // §13.3.8.3

// ====================================================================
// §13.3.9 Feittypen
// ====================================================================
feitTypeDefinition
    : FEITTYPE feitTypeNaam rolSpecificatie rolSpecificatie feitTypeCardinaliteit       // §13.3.9.1
    | WEDERKERIG_FEITTYPE feitTypeNaam rolSpecificatie feitTypeCardinaliteit            // §13.3.9.2
    ;
feitTypeNaam : feitTypeNaamWoord+ ; // §13.3.9.3 — excludes DE/HET so it stops at the role article
feitTypeNaamWoord
    : LOWER_ID | UPPER_ID | VAN | OP | IN | VOOR | OVER | BIJ | TOT | PER | MET | EN | OF | NUMBER
    ;
// §13.3.9.1: '<lidwoord>? <rolnaam> <objecttypenaam>'. Capitalisation splits role
// (lowercase) from objecttype (capitalised) — see header note on naming.
rolSpecificatie : bepaaldLidwoord? rolNaam ( MV_START meervoudsvorm RPAREN )? objectTypeNaam ;
rolNaam         : lowerNaam ; // §13.3.9.4

// §13.3.9.1/.2 cardinality line: '(één <rol> | meerdere <mvrol>) <relatiebeschrijving>
// (één <rol> | meerdere <mvrol>)'. The role/relatiebeschrijving boundary is not
// markable without the model (a role name abuts free-text prose), so each segment
// between the cardinality keywords is captured as a token run for the resolver.
feitTypeCardinaliteit
    : ( EEN_TELWOORD | MEERDERE ) cardinaliteitDeel ( EEN_TELWOORD | MEERDERE ) cardinaliteitDeel
    ;
cardinaliteitDeel : cardinaliteitWoord+ ;
cardinaliteitWoord
    : LOWER_ID | UPPER_ID | NUMBER
    | VAN | OP | IN | VOOR | OVER | BIJ | TOT | PER | MET | EN | OF | DE | HET
    | HEEFT | HEBBEN | IS | ZIJN | WORDT_VERDEELD_OVER
    ;

// ====================================================================
// §13.3.10 Dagsoort
// ====================================================================
dagsoortDefinition // §13.3.10.1
    : DAGSOORT bepaaldLidwoord? lowerNaam ( MV_START meervoudsvorm RPAREN )? SEMICOLON?
    ;
