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
    : ( definitie | regel | beslistabel )* EOF
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
    | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD
    | JAAR | MAAND | DAG | WEEK | KWARTAAL | UUR | MINUUT | SECONDE | MILLISECONDE
    | PERIODE | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN
    ;

// A lowercase-led name: a run of name words (which excludes UPPER_ID, so the
// name stops at a capitalised type/domain). The leading article, when present,
// is consumed by the caller's bepaaldLidwoord?, leaving the noun to start here.
lowerNaam : naamWoordLower+ ;

// Word tokens admissible in a fully keyword-bounded FREE name (regel names,
// tijdlijn names, parameter names) — these stop only at a strong delimiter
// (':' , 'geldig', 'heeft tijdvakken van', ';'), so the set is broad: it admits
// capitals plus 'een'/'vanaf'/'zijn', which the spec's own timeline name
// "periode van een kwartaal vanaf de geboortedatum" (§3.8) contains.
naamWoordAny : naamWoordLower | UPPER_ID | EEN | VANAF | ZIJN ;
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

koptekst : KOPTEKST ; // §13.3.1.5 — the whole '--- heading' line is one token

// §13.3.2.1-.3 Kenmerk (the optional (bijvoeglijk)/(bezittelijk) marker classifies
// it; TOKA shows both the marked and unmarked adjectival form).
kenmerkSpecificatie
    : IS lowerNaam ( MV_START meervoudsvorm RPAREN )? KENMERK BIJVOEGLIJK? SEMICOLON?   # bijvoeglijkKenmerk
    | bepaaldLidwoord? lowerNaam KENMERK ( BEZITTELIJK | BIJVOEGLIJK )? SEMICOLON?       # naamwoordKenmerk
    ;

// §13.3.2.5 Attribuut. The 'met eenheid' clause lives inside the numeric datatypes
// per the authoritative §13.3.3.2/.3 diagrams (not at member level as the draft has it).
attribuutSpecificatie
    : bepaaldLidwoord? lowerNaam ( datatype | domeinNaam )
      COMMA? ( GEDIMENSIONEERD_MET dimensieNaam ( EN dimensieNaam )* )?
      tijdlijn?
      SEMICOLON?
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
tijdlijnNaam          : lidwoord? vrijeNaam ; // §13.3.6.9 <naamwoord> (also referenced with 'een', §13.4.12)
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
dimensieDefinition // §13.3.7.1 — the comma before 'bestaande uit de' is part of the source terminal
    : DIMENSIE bepaaldLidwoord dimensieNaam COMMA? BESTAANDE_UIT dimensieNaamMeervoud voorzetselSpecificatie
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
parameterDefinition // §13.3.8.1 — name bounded by ':'; article optional (spec examples omit it)
    : PARAMETER bepaaldLidwoord? parameterNaam COLON ( datatype | domeinNaam ) SEMICOLON?
    ;
parameterNaam : vrijeNaam ; // §13.3.8.3

// ====================================================================
// §13.3.9 Feittypen
// ====================================================================
feitTypeDefinition
    : FEITTYPE feitTypeNaam rolSpecificatie rolSpecificatie feitTypeCardinaliteit SEMICOLON?      // §13.3.9.1
    | WEDERKERIG_FEITTYPE feitTypeNaam rolSpecificatie feitTypeCardinaliteit SEMICOLON?           // §13.3.9.2
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

// ////////////////////////////////////////////////////////////////////
// §13.4 RegelSpraak
// ////////////////////////////////////////////////////////////////////

// --- §13.4 names. A §13.4 name segment stops before a NAVIGATION 'van' (one
// followed by a subject-starter: lidwoord/een/zijn/quantifier). An internal
// 'van <bare word>' ("belasting op basis van afstand") stays in the segment.
// Where this fixed choice mis-splits a name whose own text reads "... van de
// ..." (in TOKA only "leeftijd van de oudste passagier"), the parser yields a
// van-chain the resolver re-splits against the declared model. UPPER_ID is
// admitted so objecttype names ("Natuurlijk persoon") are part of a segment;
// the resolver classifies each segment as objecttype/rol/kenmerk/attribuut.
// DE/HET/MET are admitted so names with internal articles/"met" parse intact
// ("voor het berekenen van de belasting", "reis met paaskorting"). EEN/ZIJN/ALLE
// are NOT, so a navigation 'van een/zijn/alle <subject>' splits the chain; a
// 'van de/het <subject>' is absorbed and the resolver re-splits it.
kaleNaamWoord
    : LOWER_ID | UPPER_ID | NUMBER | PERCENT_SIGN | AANTAL_WORD
    | OP | IN | VOOR | OVER | BIJ | TOT | TOT_EN_MET | PER | MET | EN | OF | DAN | NIET
    | DE | HET
    | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD
    | JAAR | MAAND | DAG | WEEK | KWARTAAL | UUR | MINUUT | SECONDE | MILLISECONDE
    | PERIODE | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN
    ;
kaleNaamDeel : kaleNaamWoord+ ;
kaleNaam     : kaleNaamDeel ( VAN kaleNaamDeel )* ;

rangtelwoord // §13.2.12
    : EERSTGENOEMDE | ALS_TWEEDE_GENOEMDE | ALS_DERDE_GENOEMDE | ALS_VIERDE_GENOEMDE
    | ALS NUMBER E_GENOEMDE | LAATSTGENOEMDE
    ;

// ====================================================================
// §13.4.1 Onderwerpketen + §13.4.2.7-9 selector / subselectie / attribuut-van-onderwerp
// ====================================================================
onderwerpKeten // §13.4.1.1 — source EBNF has an unbalanced '('; flat choice per the diagram,
               // with subselectie inlined as direct left recursion (ANTLR4 has no indirect LR)
    : selector VAN onderwerp
    | universeelOnderwerp
    | onderwerpVerwijzing
    | ZIJN kaleNaam
    | onderwerpKeten ( DIE | DAT ) predicaat
    ;
universeelOnderwerp : onbepaaldLidwoord kaleNaam ;             // §13.4.1.2
onderwerpVerwijzing : bepaaldLidwoord rangtelwoord? kaleNaam ; // §13.4.1.3
selector            : lidwoord kaleNaam ;                      // §13.4.2.7
// objectexpressie (§13.4.17.5): a possibly-quantified subject, incl. the
// quantifier+bare-role form "alle passagiers van de reis".
onderwerp
    : kwantificatie kaleNaam ( VAN onderwerp )?
    | onderwerpKeten
    ;
attribuutVanOnderwerp // §13.4.2.9
    : ( kwantificatie? attribuutMetLidwoord | bepaaldLidwoord rangtelwoord kaleNaam ) VAN onderwerp
    ;
attribuutMetLidwoord : bepaaldLidwoord? kaleNaam ; // §13.3.2.6

referentie // §13.4.17.41-44
    : HIJ                          // bezielde referentie
    | ZIJN kaleNaam                // possessive attribute/role
    | bepaaldLidwoord objectTypeNaam // niet-bezielde (objecttype met lidwoord)
    | DE DAG                       // dagsoortreferentie
    ;

// ====================================================================
// §13.4.2 RegelSpraak-regel
// ====================================================================
regel       : REGEL regelNaam regelVersie+ ;        // §13.4.2.1
// A regelnaam is free <karakterreeks>: it routinely embeds keywords and even
// multi-word phrase tokens ("... geen rondvlucht is", "verdeling treinmiles in
// gelijke delen", "... vertrek min reserveringstermijn"). It is exactly the run
// of tokens up to the 'geldig' that opens the first versie.
regelNaam   : ~GELDIG+ ;                             // §13.4.2.2
regelVersie : versie regelSpraakRegel ;             // §13.4.2.3
versie      : GELDIG versieGeldigheid ;             // §13.4.2.4
versieGeldigheid                                     // §13.4.2.5
    : ALTIJD
    | VANAF datumOfJaar ( TM datumOfJaar )?
    | TM datumOfJaar
    ;
datumOfJaar : DATE | NUMBER ;
// §13.4.2.6 — the terminating '.' is made lenient: TOKA omits it on several rules.
regelSpraakRegel
    : resultaatDeel voorwaardeDeel? DOT? variabeleDeel?
    ;
variabeleDeel      : DAARBIJ_GELDT COLON variabeleOnderdeel+ DOT? ;            // §13.4.2.10
variabeleOnderdeel : bepaaldLidwoord? variabeleNaam IS expressie DOT? ;        // §13.4.2.11
variabeleNaam      : vrijeNaam ;                                               // §13.4.2.12

// Beslistabel: the tabular body is markdown outside grammar scope (Ch.12); the
// header (name + validity) is all that appears inside a regelspraak code block.
beslistabel : BESLISTABEL vrijeNaam versie? ;

// ====================================================================
// §13.4.3 Resultaatdeel
// ====================================================================
resultaatDeel // §13.4.3.1
    : gelijkstelling
    | initialisatie
    | verdeling
    | startpuntBepaling
    | dagsoortResultaat
    | feitCreatie
    | objectCreatie
    | kenmerkToekenning
    | consistentieRegel
    ;

// §13.4.4 Gelijkstelling
gelijkstelling
    : attribuutVanOnderwerp WORDT_GESTELD_OP expressie   // §13.4.4.2 toekenning
    | attribuutVanOnderwerp WORDT_BEREKEND_ALS expressie // §13.4.4.3 berekening (getal|datum expr; untyped here)
    ;
// §13.4.9 Initialisatie
initialisatie : attribuutVanOnderwerp WORDT_GEINITIALISEERD_OP expressie ;
// §13.4.5 Kenmerktoekenning — kaleNaam excludes lidwoorden, so "heeft het <rol> …"
// (objectcreatie) does NOT match here and falls through to objectCreatie.
kenmerkToekenning : onderwerpKeten ( IS | HEEFT ) EEN? kaleNaam ;
// §13.4.6 ObjectCreatie. The subject's article belongs to its onderwerpketen
// (universeelonderwerp), so no leading EEN here. The 'met' that introduces the
// waardetoekenningen is absorbed into the preceding kaleNaam (MET is a name word);
// the waardetoekenning is recognised by its 'gelijk aan', which also distinguishes
// objectcreatie from a plain kenmerktoekenning. The resolver splits rol vs attribuut.
objectCreatie
    : onderwerpKeten HEEFT lidwoord? kaleNaam
      GELIJK AAN expressie ( ( COMMA | EN ) kaleNaam GELIJK AAN expressie )*
    ;
// §13.4.7 FeitCreatie
feitCreatie : EEN kaleNaam VAN onderwerp IS EEN kaleNaam VAN onderwerp ;
// §13.4.11 Dagsoortdefinitie (as a result)
dagsoortResultaat : EEN DAG IS EEN kaleNaam ;
// §13.4.12 Startpuntbepaling
startpuntBepaling : HET_STARTPUNT_VAN tijdlijnNaam VOOR universeelOnderwerp WORDT_BEPAALD_DOOR expressie ;

// §13.4.10 Verdeling. Bullets are '-' (TOKA); trailing commas/periods are lenient
// because the casus places the final '.' as a sentence terminator, not per the EBNF.
verdeling
    : attribuutVanOnderwerp WORDT_VERDEELD_OVER attribuutVanOnderwerp COMMA? WAARBIJ_WORDT_VERDEELD
      ( verdelenZonderGroepen | meervoudigCriterium )
    ;
verdelenZonderGroepen : IN_GELIJKE_DELEN | NAAR_RATO_VAN attribuutMetLidwoord ;
meervoudigCriterium
    : COLON ( verdelenOverGroepen | DASH? verdelenZonderGroepen COMMA? )
      maximumAanspraak? verdeelAfronding? DOT? onverdeeldeRest?
    ;
verdelenOverGroepen
    : DASH OP_VOLGORDE_VAN ( AFNEMENDE | TOENEMENDE ) attribuutMetLidwoord COMMA? criteriumBijGelijkeVolgorde COMMA?
    ;
criteriumBijGelijkeVolgorde
    : DASH BIJ_EVEN_GROOT_CRITERIUM ( IN_GELIJKE_DELEN | NAAR_RATO_VAN attribuutMetLidwoord ) COMMA?
    ;
maximumAanspraak : DASH MET_EEN_MAXIMUM_VAN attribuutMetLidwoord COMMA? ;
verdeelAfronding : DASH AFGEROND_OP NUMBER DECIMALEN NAAR_BENEDEN DOT? ;
onverdeeldeRest  : ALS_ONVERDEELDE_REST_BLIJFT attribuutVanOnderwerp OVER DOT? ;

// §13.4.8 Consistentieregels
consistentieRegel
    : uniciteitsControle
    | toplevelSamengesteldCriterium
    | samengesteldeConsistentie
    | enkelvoudigeConsistentieRegel
    ;
enkelvoudigeConsistentieRegel : expressie MOET toplevelOperator ; // operand types/operator legality: resolver
// Subject-led compound consistency "<onderwerp> moet voldoen aan <kwantificatie>
// volgende criteria:" from the §9 worked examples (the §13.4.8.7 diagram shows
// only the "er moet worden voldaan aan" opener).
samengesteldeConsistentie
    : onderwerp MOET ( VOLDOEN | VOLDOET ) AAN consistentieKwantificatie
      ( VOLGENDE_CRITERIA | VOLGENDE_VOORWAARDEN | VOLGENDE_VOORWAARDE ) COLON samengesteldCriteriumOnderdeel
    ;
toplevelSamengesteldCriterium
    : ER_MOET_WORDEN_VOLDAAN_AAN ( VOLGEND_CRITERIUM COLON | consistentieKwantificatie VOLGENDE_CRITERIA COLON )
      samengesteldCriteriumOnderdeel
    ;
samengesteldCriteriumOnderdeel : genestCriterium+ ;
genestCriterium : bullet ( samengesteldCriterium | elementaireVoorwaarde ) ;
samengesteldCriterium
    : WORDT_VOLDAAN AAN ( VOLGEND_CRITERIUM COLON | consistentieKwantificatie VOLGENDE_CRITERIA COLON )
      samengesteldCriteriumOnderdeel
    ;
uniciteitsControle : ( alleAttribuutVanOnderwerp | uniciteitConcatenatie ) vereniging* MOETEN UNIEK ZIJN DOT? ;
vereniging : VERENIGD_MET ( alleAttribuutVanOnderwerp | uniciteitConcatenatie ) ;
alleAttribuutVanOnderwerp : DE kaleNaam VAN ALLE kaleNaam VAN onderwerpKeten ( VAN onderwerpKeten )? ;
uniciteitConcatenatie
    : CONCATENATIE_VAN kaleNaam ( COMMA kaleNaam )* EN kaleNaam VAN ALLE kaleNaam VAN onderwerpKeten ( VAN onderwerpKeten )?
    ;

// ====================================================================
// §13.4.13-14 Voorwaarden
// ====================================================================
voorwaardeDeel // §13.4.13.1
    : INDIEN ( toplevelSamengesteldeVoorwaarde | toplevelElementaireVoorwaarde )
    | periodevergelijkingEnkelvoudig
    ;
predicaat // §13.4.13.2 (used in subselectie)
    : samengesteldPredicaat
    | nonToplevelOperator
    ;
samengesteldPredicaat // §13.4.13.4
    : AAN kwantificatie ( VOLGENDE_VOORWAARDE | VOLGENDE_VOORWAARDEN ) ( VOLDOET | VOLDOEN ) COLON
      samengesteldeVoorwaardeOnderdeel
    ;

toplevelElementaireVoorwaarde // §13.4.15.1
    : consistentieVoorwaarde
    | expressie toplevelOperator
    ;
toplevelSamengesteldeVoorwaarde // §13.4.14.1
    : voorwaardeSubject AAN voorwaardeKwantificatie ( VOLGENDE_VOORWAARDE | VOLGENDE_VOORWAARDEN )
      ( VOLDOET | VOLDOEN | WORDT_VOLDAAN ) COLON samengesteldeVoorwaardeOnderdeel
    ;
genesteSamengesteldeVoorwaarde // §13.4.14.2
    : voorwaardeSubject ( VOLDOET | VOLDOEN | WORDT_VOLDAAN ) AAN voorwaardeKwantificatie
      ( VOLGENDE_VOORWAARDE | VOLGENDE_VOORWAARDEN ) COLON samengesteldeVoorwaardeOnderdeel
    ;
voorwaardeSubject : ER | HIJ | onderwerp ;
// 'alle van de' (vs the diagram's bare 'alle') appears in the §9 worked examples
// — tolerated as an optional 'van de' tail.
voorwaardeKwantificatie // §13.4.14.4
    : DE | ALLE ( VAN DE )? | GEEN_VAN_DE | ( TEN_MINSTE | TEN_HOOGSTE | PRECIES ) telwoord VAN DE
    ;
consistentieKwantificatie // §13.4.14.3
    : ALLE ( VAN DE )? | GEEN_VAN_DE | ( TEN_MINSTE | TEN_HOOGSTE | PRECIES ) telwoord VAN DE
    ;
kwantificatie // §13.4.14.5
    : DE | ALLE | AL | GEEN_VAN_DE | ( TEN_MINSTE | TEN_HOOGSTE | PRECIES ) telwoord VAN DE
    ;
telwoord : NUMBER | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD ;
samengesteldeVoorwaardeOnderdeel : genesteVoorwaarde+ ; // §13.4.14.6 (bullet-delimited; >=2 arity: resolver)
genesteVoorwaarde : bullet ( genesteSamengesteldeVoorwaarde | elementaireVoorwaarde ) ; // §13.4.14.7
bullet : ( DASH | DOTDOT | BULLET )+ ; // TOKA: '-' top level, '..' nested; EBNF glyph '•' also honoured

// ====================================================================
// §13.4.15 Elementaire voorwaarde + comparison operators.
// Types are collapsed: a context-free parser cannot tell a getal operand from a
// datum/tekst/boolean/object one, so one operator family serves all and the
// resolver enforces operator/operand legality per the typeringen matrix. The
// TOPLEVEL ("vragende") forms split the verb out and trail it ("gelijk is aan");
// the NON-TOPLEVEL ("stellende") forms lead with the verb ("is gelijk aan").
// ====================================================================
elementaireVoorwaarde // §13.4.15.49-.50
    : consistentieVoorwaarde
    | expressie nonToplevelOperator
    | periodevergelijkingElementair
    ;
consistentieVoorwaarde : REGELVERSIE vrijeNaam IS ( GEVUURD | INCONSISTENT ) ; // §13.4.15.66

gehelePeriodevergelijking // §13.4.15.70
    : NIET? ( GEDURENDE_HET_GEHELE JAAR | GEDURENDE_DE_GEHELE MAAND )
    ;

// verb-leading operator phrase (non-toplevel)
vergelijkingsTeken
    : ( GELIJK | ONGELIJK ) AAN
    | ( GROTER | KLEINER | LATER | EERDER ) DAN
    | ( GROTER | KLEINER | LATER | EERDER ) OF GELIJK AAN
    ;
// verb-split operator phrase (toplevel): <opword> is/zijn aan/dan
vergelijkingsTekenGesplitst
    : ( GELIJK | ONGELIJK ) ( IS | ZIJN ) AAN
    | ( GROTER | KLEINER | LATER | EERDER ) ( IS | ZIJN ) ( DAN | AAN )
    | ( GROTER | KLEINER | LATER | EERDER ) OF GELIJK ( IS | ZIJN ) AAN
    ;

nonToplevelOperator // §13.4.15.71-.100 (verb-leading), unified across types
    : ( IS | ZIJN ) gehelePeriodevergelijking? ( LEEG | GEVULD )
    | ( VOLDOET | VOLDOEN ) gehelePeriodevergelijking? AAN_DE_ELFPROEF
    | ( IS | ZIJN ) gehelePeriodevergelijking? NUMERIEK_MET_EXACT NUMBER CIJFERS
    | ( IS | ZIJN ) gehelePeriodevergelijking? vergelijkingsTeken expressie
    | ( IS | HEEFT | ZIJN | HEBBEN ) gehelePeriodevergelijking? EEN? kaleNaam
    | gehelePeriodevergelijking? EEN kaleNaam ( IS | ZIJN )
    ;
toplevelOperator // §13.4.15.19-.48 (verb-split / trailing), unified across types
    : gehelePeriodevergelijking? ( LEEG | GEVULD ) ( IS | ZIJN )
    | gehelePeriodevergelijking? AAN_DE_ELFPROEF ( VOLDOET | VOLDOEN )
    | gehelePeriodevergelijking? ( NUMERIEK_IS_MET_EXACT | NUMERIEK_ZIJN_MET_EXACT ) NUMBER CIJFERS
    | gehelePeriodevergelijking? vergelijkingsTekenGesplitst expressie
    | gehelePeriodevergelijking? EEN? kaleNaam ( IS | HEEFT | ZIJN | HEBBEN )
    ;

periodevergelijkingEnkelvoudig // §13.4.15.68
    : VANAF expressie
    | VAN expressie TOT expressie
    | VAN expressie TOT_EN_MET expressie
    | TOT expressie
    | TOT_EN_MET expressie
    ;
periodevergelijkingElementair : HET IS DE PERIODE periodevergelijkingEnkelvoudig ; // §13.4.15.69

// ====================================================================
// §13.4.16-17 Berekening + Expressie. ONE untyped left-recursive rule; the spec's
// getal/datum/tekst/boolean stratification is a resolver concern. Aggregations
// take NO parentheses (the appendix EBNF '(' is a source typo; the diagrams and
// every TOKA fixture are paren-free).
// ====================================================================
expressie
    : expressie TOT_DE_MACHT expressie afronding?                    # machtExpr        // §13.4.17.14
    | expressie ( MAAL | GEDEELD_DOOR | GEDEELD_DOOR_ABS ) expressie # productExpr      // §13.4.16
    | expressie ( PLUS | MIN | VERMINDERD_MET ) expressie            # somExpr          // §13.4.16 (datum +/- duur too)
    | expressie PERCENT_SIGN VAN expressie                           # percentageVanExpr // §13.4.17.12 (% required to disambiguate from 'van' navigation)
    | expressie COMMA begrenzing                                     # begrensdeExpr    // §13.4.17.27
    | expressie afronding                                            # afgerondeExpr    // §13.4.17.25
    | primaireExpressie                                              # primaireExpr
    ;

primaireExpressie
    : functieAanroep
    | datumMet
    | datumTijdMet
    | getalWaarde
    | tekstWaarde
    | booleanWaarde
    | ENUM_LITERAL
    | deDato
    | REKENJAAR
    | REKENDATUM
    | HIJ
    | DE DAG
    | attribuutVanOnderwerp
    | onderwerp
    | variabeleNaam
    | LPAREN expressie RPAREN
    ;

// §13.4.17.10-58 named functions and aggregations
functieAanroep
    : SOM_VAN expressie dimensieSelectie?                                                          # somFunc
    | HET AANTAL_WORD expressie                                                                    # aantalFunc      // telling §13.4.17.47
    | TOTAAL_VAN expressie conditieBijExpressie?                                                   # totaalFunc      // §13.4.17.56
    | ( MAXIMALE_WAARDE_VAN | MINIMALE_WAARDE_VAN ) expressie ( COMMA expressie )* ( EN expressie )? dimensieSelectie? # minMaxFunc // §13.4.17.15/.16/.47
    | ( EERSTE_VAN | LAATSTE_VAN ) expressie ( COMMA expressie )* ( EN expressie )?                 # eersteLaatsteFunc // §13.4.17.39/.40/.49
    | WORTEL_VAN expressie afronding?                                                              # wortelFunc      // §13.4.17.13
    | ABSOLUTE_WAARDE_VAN LPAREN expressie RPAREN                                                  # absWaardeFunc   // §13.4.17.17
    | ( TIJDSDUUR_VAN | ABSOLUTE_TIJDSDUUR_VAN ) expressie TOT expressie IN HELE? eenheidMeervoud  # tijdsduurFunc   // §13.4.17.32
    | EERSTE_PAASDAG_VAN expressie                                                                 # paasdagFunc     // §13.4.17.37
    | HET JAAR UIT expressie                                                                       # jaarUitFunc     // §13.4.17.18
    | DE MAAND UIT expressie                                                                       # maandUitFunc    // §13.4.17.19
    | DE DAG UIT expressie                                                                         # dagUitFunc      // §13.4.17.20
    | HET UUR UIT expressie                                                                        # uurUitFunc      // §13.4.17.21
    | DE MINUUT UIT expressie                                                                      # minuutUitFunc   // §13.4.17.22
    | DE SECONDE UIT expressie                                                                     # secondeUitFunc  // §13.4.17.23
    | DE MILLISECONDE UIT expressie                                                                # milliUitFunc    // §13.4.17.24
    | AANTAL_DAGEN_IN ( DE MAAND | HET JAAR ) DAT expressie                                        # aantalDagenFunc // §13.4.17.57
    | TIJDSEVENREDIG_DEEL_PER ( MAAND | JAAR ) VAN expressie conditieBijExpressie                  # tijdsevenredigFunc // §13.4.17.58
    | CONCATENATIE_VAN expressie ( COMMA expressie )* EN expressie                                 # concatFunc      // §13.4.17.2/.8.14
    ;
eenheidMeervoud : DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN | JAAR | MAAND | DAG | UUR | MINUUT | LOWER_ID ;

afronding // §13.4.17.26
    : ( NAAR_BENEDEN | NAAR_BOVEN | REKENKUNDIG | RICHTING_NUL | WEG_VAN_NUL ) AFGEROND_OP NUMBER DECIMALEN
    ;
begrenzing // §13.4.17.28
    : begrenzingMinimum ( EN begrenzingMaximum )?
    | begrenzingMaximum
    ;
begrenzingMinimum : MET_EEN_MINIMUM_VAN expressie ; // §13.4.17.29
begrenzingMaximum : MET_EEN_MAXIMUM_VAN expressie ; // §13.4.17.30

datumMet // §13.4.17.35
    : DATUM_MET JAAR COLON expressie COMMA MAAND COLON expressie EN DAG COLON expressie
    ;
datumTijdMet // §13.4.17.36
    : DATUMTIJD_MET JAAR COLON expressie COMMA MAAND COLON expressie COMMA DAG COLON expressie
      COMMA UUR COLON expressie COMMA MINUUT COLON expressie COMMA SECONDE COLON expressie
      EN MILLISECONDE COLON expressie
    ;

conditieBijExpressie // §13.4.17.55
    : GEDURENDE_DE_TIJD_DAT ( toplevelSamengesteldeVoorwaarde | toplevelElementaireVoorwaarde )
    | periodevergelijkingEnkelvoudig
    ;
dimensieSelectie // §13.4.17.51
    : OVER ( aggregerenOverAlleDimensies | aggregerenOverVerzameling | aggregerenOverBereik )
    ;
aggregerenOverAlleDimensies : ALLE dimensieNaamMeervoud ;                                  // §13.4.17.52
aggregerenOverVerzameling   : DE dimensieNaamMeervoud VANAF dimensieWaarde TM dimensieWaarde ; // §13.4.17.53
aggregerenOverBereik        : DE dimensieNaamMeervoud IN LBRACE dimensieWaarde ( ( COMMA dimensieWaarde )* EN dimensieWaarde )? RBRACE ; // §13.4.17.54

getalWaarde   : NUMBER ( PERCENT_SIGN | eenheidExpressie )? ; // §13.2.17/.18
tekstWaarde   : STRING_LITERAL ;                              // §13.2.15
booleanWaarde : WAAR | ONWAAR ;                               // §13.2.16
deDato        : DATE ;                                        // §13.2.19
