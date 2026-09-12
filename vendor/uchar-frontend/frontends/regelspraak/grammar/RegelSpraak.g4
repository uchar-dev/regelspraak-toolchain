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
// domain) and "de groep Ploeg" (lowercase role + capitalised objecttype) with no
// symbol table. Genuinely context-sensitive boundaries that capitalisation does
// NOT resolve — notably the feittype cardinality line, where a role name abuts a
// free-text relatiebeschrijving — are captured as a token run for the resolver
// to split (name resolution is post-parse, by design). Such deferrals are
// flagged at the rule.

parser grammar RegelSpraak;

options { tokenVocab = RegelSpraakLexer; }

@members {
// §13.2.24: a <jaar> is exactly four digits — no sign, no decimal/rational
// separator. The lexer folds "12-2024" into NUMBER('12') NUMBER('-2024'), so
// without this guard datumOfJaar would accept the bare '12' as a jaar and leak
// the stray '-2024' into the next name. Gate the NUMBER branch on the literal
// shape so half-dates (12-2024, 3-15) are a hard parse error while a real jaar
// (2024) and a full datumwaarde (01-03-2024, one DATE token) still match.
private static boolean isJaar(org.antlr.v4.runtime.Token t) {
    String s = t.getText();
    if (s.length() != 4) return false;
    for (int i = 0; i < 4; i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
    return true;
}
}

// ====================================================================
// Document
// ====================================================================
regelSpraakDocument
    : ( definitie | regelGroep | uitzonderingGroep | uitzonderingRegel | regel | beslistabel )* EOF
    ;

// §4 uchar DIALECT EXTENSION — the defaults-producing forms (PRIORITIZED-DEFAULTS-EVOLUTIE §4.2;
// grammar/DIVERGENCES.md).
// Purely ADDITIVE top-level alternatives beside regel/regelGroep/beslistabel, the inert-on-official-documents
// posture regelGroep and scenariotoetsen already take (the header tokens occur nowhere in v2.3.0 source). The
// resolver lowers them to PrioritizedDefaults nodes; official documents parse EXACTLY as before.
//
// Ordered exception: 'Uitzondering op <basisregel>: <naam>' then a normal rule body (geldig-clause +
// kenmerktoekenning/gelijkstelling + optional indien). Stacks above the named base rule; the resolver resolves
// the base, enforces the SAME target, and builds one PrioritizedDefaults group (exception default(s) above,
// base default below).
// The base name runs up to the ':' (it never contains one); the exception's own name is a regelNaam (up to
// 'geldig'), so the body's regelVersie+ is reached exactly as a §13.4.2 regel is.
uitzonderingRegel     : UITZONDERING_OP uitzonderingBasisNaam COLON regelNaam regelVersie+ ;
uitzonderingBasisNaam : ~( COLON | GELDIG | GELDIG_CAP )+ ; // base-rule name, bounded by the ':'

// Unordered group: 'Uitzonderingen ongeordend:' scopes CONSECUTIVE ordinary regels that write ONE shared
// target (the §9.5 surface). The resolver builds sibling declared-unordered defaults groups (one per member); members
// deciding differently on one target route to the §9.5 channel. Like regelGroep, the group ends at the first
// construct that is not a regel, so it is inert on any non-extension document.
uitzonderingGroep     : UITZONDERINGEN ONGEORDEND COLON regel+ ;

// §9.10 Regelgroep — a DOCUMENTED EXTENSION, not a §13 production (v2.3.0 gives regelgroep no written
// form; see docs/grammar-divergences.md '§9.10 recursie / regelgroep'). A `Regelgroep <naam>` header,
// optionally `is recursief`, scopes the CONSECUTIVE rule constructs (regel / beslistabel — the latter
// desugars to regels; a consistentieRegel is already a regel's resultaatdeel) that follow it. The group
// ends at the first construct that is NOT a rule (an Objecttype / Parameter / Feittype / another
// Regelgroep), because `groepLid` only matches the rule alternatives — the extension is inert on any
// non-extension document. The name is a `kaleNaam` (it stops at `is` and at the `Regel` keyword, so it
// never swallows the marker or the first member). REGELGROEP is listed before `regel` in the document
// alternation so a leading 'Regelgroep' opens a group, not a bare rule.
regelGroep : REGELGROEP kaleNaam ( IS RECURSIEF )? groepLid+ ;
groepLid   : regel | beslistabel ;

// ====================================================================
// Scenariotoetsen — a DOCUMENTED EXTENSION, not a §13 production (v2.3.0 has no
// test chapter). Reachable ONLY via `testDocument`; `regelSpraakDocument` and
// `corpusEntry` above stay byte-for-byte unchanged, so production parsing and the
// corpus verifier are isolated (gate G6). Binding contract: docs/SCENARIOTOETSEN.md;
// token/collision analysis: the '§Scenariotoetsen' section of grammar-divergences.md.
// Alternatives are #-labelled so the resolver can visit each form directly.
// ====================================================================
testDocument
    : ( definitie | regelGroep | regel | beslistabel | situatieDefinitie | scenario )* EOF
    ;

// A herbruikbare situatie: `Situatie <naam>:` header, then a situatieblok. The name
// is a vrijeNaam and ends at the COLON (SITUATIE is a keyword, so it never opens a
// bare rule/definition; the ':' closes the name).
situatieDefinitie : SITUATIE situatieNaam COLON situatieBlok ;
situatieNaam      : vrijeNaam ; // free name up to the ':'

// A scenario: header (name + scope + optional peildatum + optional gebaseerd-op),
// then an optional inline situatiesectie, then the (mandatory) verwachtingsectie.
// The scenarionaam is the token run up to the `toetst …` scope keyword.
scenario
    : SCENARIO scenarioNaam scenarioScope
      scenarioPeildatum? scenarioBasis*
      inlineSituatie?
      MET_ALS_VERWACHT_RESULTAAT verwachting+
    ;
scenarioNaam : ~( TOETST_DE_REGEL | TOETST_DE_REGELS | TOETST_DE_REGELGROEP )+ ; // free name up to 'toetst …'

scenarioScope
    : TOETST_DE_REGEL      enkeleRegelReferentie                            # scopeEnkeleRegel
    | TOETST_DE_REGELS     regelReferentie ( COMMA regelReferentie )* EN regelReferentie # scopeMeerdereRegels
    | TOETST_DE_REGELGROEP enkeleRegelReferentie                            # scopeRegelgroep
    ;
// A rule/group name in a scope LIST ('toetst de regels a, b en c'): a free token run
// bounded by the list separators (',' , 'en') and by the header keywords that can
// follow the scope (peildatum / gebaseerd-op / the verwachtingsectie opener). A
// regelnaam containing a bare ' en ' or ',' is not expressible in the LIST form — a
// documented limitation, consistent with the existing EN-splitting in §13.4 names.
regelReferentie : regelReferentieWoord+ ;
regelReferentieWoord
    : LOWER_ID | UPPER_ID | NUMBER | PERCENT_SIGN | AANTAL_WORD
    | VAN | OP | IN | VOOR | OVER | BIJ | TOT | TOT_EN_MET | PER | MET | OF | DAN | NIET
    | DE | HET | EEN | ZIJN | VANAF | GELDIG
    | IS | HEEFT | HEBBEN | WORDT_VERDEELD_OVER
    | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD
    | JAAR | MAAND | DAG | WEEK | KWARTAAL | UUR | MINUUT | SECONDE | MILLISECONDE
    | PERIODE | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN
    ;
// A rule/group name in the SINGLE-rule scope ('toetst de regel <naam>' / 'toetst de
// regelgroep <naam>'). There is no list here, so the name is a FREE token run bounded
// only by the four dedicated header tokens that can follow the scope (MET_PEILDATUM /
// GEBASEERD_OP_DE_SITUATIE / GEGEVEN_IS_DE_VOLGENDE_SITUATIE / MET_ALS_VERWACHT_RESULTAAT) —
// the verwachtingsectie opener is mandatory, so the run always terminates. Unlike the
// LIST form (regelReferentie above, which is bounded by the ',' / 'en' separators), the
// single-rule name may therefore contain a bare ' en ', ',', or any multi-word keyword
// token (e.g. 'in gelijke delen', 'op basis van'), which is exactly what lets a golden
// scenario scope a real §9.7 verdeling rule or any rule whose name
// embeds such words. The resolver reconstructs the name from the token text and matches
// it against the model's rule names (normalised). See docs/SCENARIOTOETSEN.md.
enkeleRegelReferentie
    : ~( MET_PEILDATUM | GEBASEERD_OP_DE_SITUATIE | GEGEVEN_IS_DE_VOLGENDE_SITUATIE | MET_ALS_VERWACHT_RESULTAAT )+ ;

scenarioPeildatum : MET_PEILDATUM datumWaarde ;                         // §4.2 rekendatum-global
scenarioBasis     : GEBASEERD_OP_DE_SITUATIE situatieNaam ;             // herhaalbaar
inlineSituatie    : GEGEVEN_IS_DE_VOLGENDE_SITUATIE situatieBlok ;      // optionele inline situatiesectie

// ---- Situatieblok: instanties + parameterwaarden, in vrije volgorde ----
situatieBlok : situatieOnderdeel+ ;
situatieOnderdeel
    : instantie          # instantieOnderdeel
    | parameterWaarde    # parameterWaardeOnderdeel
    ;
// `een <objecttypenaam> <LABEL>` opener + gebullette toewijzingen. The LABEL is one
// UPPER_ID (V1, Tom); the resolver checks its uniqueness and non-collision with a type.
instantie : EEN objectTypeNaam UPPER_ID toewijzing* ;
toewijzing
    : bullet bepaaldLidwoord toewijzingNaam IS situatieWaarde              DOT? # attribuutToewijzing   // de/het <attr> is <waarde>
    | bullet bepaaldLidwoord toewijzingNaam IS UPPER_ID                    DOT? # rolToOneToewijzing    // de/het <rol> is <LABEL>
    | bullet bepaaldLidwoord toewijzingNaam ZIJN UPPER_ID ( EN UPPER_ID )* DOT? # rolToManyToewijzing   // de/het <rol> zijn <LABEL> en <LABEL> …
    | bullet ( IS | HEEFT ) lidwoord? kaleNaam               DOT? # kenmerkToewijzing     // is/heeft [lidw] <kenmerk>
    ;
// The attribute/role name in a bulleted toewijzing. It must STOP before the trailing
// ' is <UPPER_ID>' / ' is <waarde>' / ' zijn <UPPER_ID>' — kaleNaam already excludes
// the UPPER_ID of a following LABEL, and IS/ZIJN are not name words, so the name loop
// halts at the verb (the same UPPER_ID-lookahead boundary the §13.4 names rely on).
// Same bound as verwachtingNaam: a 'de <rolnaam> is <LABEL>' / 'de <attr> is <waarde>' toewijzing
// must stop before the trailing ' is/zijn <…>' AND never absorb a 'van <UPPER_ID>' as a name-segment
// (a rolnaam/attribuutnaam 'van'-segment is LOWER_ID-led). Uses the shared begrensdeNaam.
toewijzingNaam : begrensdeNaam ;

// Ongebullette parameterwaarde — the missing bullet is what distinguishes it from an
// instantie-toewijzing (`de parameter …`, whole-phrase token DE_PARAMETER).
parameterWaarde : DE_PARAMETER parameterNaam IS situatieWaarde DOT? ;

// A situatie/verwachting value: the existing literal tokens (nothing new). A bare
// `NUMBER` unit run reuses getalWaarde. `leeg` is NOT a situatieWaarde — only a
// verwachting states `… is leeg` (its own `verwachtAttribuutLeeg` alternative), so a
// situatie assignment always carries a concrete value.
situatieWaarde
    : getalWaarde       # getalSituatieWaarde
    | datumWaarde       # datumSituatieWaarde
    | booleanWaarde     # booleanSituatieWaarde
    | ENUM_LITERAL      # enumSituatieWaarde
    | tekstWaarde       # tekstSituatieWaarde
    ;

// ---- Verwachtingregels (bullets) ----
// The `is geen <kenmerk>` / `heeft geen <kenmerk>` (absent) forms are NOT separate
// alternatives: `geen` lexes as a plain LOWER_ID and rides inside the trailing
// `kaleNaam`, so `verwachtKenmerk`/`verwachtKenmerkHeeft` already MATCH them and the
// resolver strips the `geen` prefix to read the negation — exactly the production
// `<subject> is/heeft geen <kenmerk>` condition path (grammar-divergences 'negated
// membership'). The optional lidwoord (een|de|het) covers `is een/de/het <kenmerk>`.
verwachting
    : bullet bepaaldLidwoord verwachtingNaam VAN UPPER_ID IS LEEG                 DOT? # verwachtAttribuutLeeg     // … is leeg (listed first: LEEG is not a situatieWaarde)
    | bullet bepaaldLidwoord verwachtingNaam VAN UPPER_ID IS situatieWaarde       DOT? # verwachtAttribuutWaarde   // de/het <attr> van <LABEL> is <waarde>
    | bullet UPPER_ID IS      lidwoord? kaleNaam                     DOT? # verwachtKenmerk           // <LABEL> is [lidw]|geen <kenmerk>
    | bullet UPPER_ID HEEFT   lidwoord? kaleNaam                     DOT? # verwachtKenmerkHeeft      // <LABEL> heeft [lidw]|geen <kenmerk>
    | bullet DE_GEGEVENS_ZIJN INCONSISTENT                           DOT? # verwachtInconsistent      // de gegevens zijn inconsistent
    | bullet DE_GEGEVENS_ZIJN CONSISTENT                             DOT? # verwachtConsistent        // de gegevens zijn consistent
    ;
// The attribuutnaam of a verwachting, which MUST stop before the trailing ' van <LABEL>'
// (VAN then the UPPER_ID label). A plain kaleNaam would ambiguously absorb ' van <LABEL>'
// because kaleNaam's '(VAN kaleNaamDeel)*' loop accepts a bare UPPER_ID as a kaleNaamDeel —
// which made ANTLR's prediction context-dependent (a multi-word attribuut over a UPPER_ID
// label sometimes swallowed the label, mismatching the outer 'VAN UPPER_ID IS'). The bounded
// form below extends a 'van'-segment ONLY when it is LOWER_ID-led (an attribute-name segment
// is always a lowercase word: 'van afstand', 'van bestemming'), so a 'van <UPPER_ID>' label
// is never absorbed. Deterministic; see docs/grammar-divergences.md '§Scenariotoetsen'.
verwachtingNaam : begrensdeNaam ;
// A name that may contain internal ' van <lowercase segment>' but never a trailing
// ' van <UPPER_ID>' — the UPPER_ID label/right-hand side is left for the enclosing rule.
begrensdeNaam : kaleNaamDeel ( VAN naamVervolgDeel )* ;
naamVervolgDeel : naamWoordLower kaleNaamWoord* ; // LOWER_ID-led, so a bare UPPER_ID label ends the name

// Verification entry point for the spec corpus. The worked examples in the spec
// are not all whole documents — most are FRAGMENTS of a single non-terminal: a
// bare expressie, a condition, an attribuutspecificatie, a resultaatdeel. To
// check "does this snippet parse as some documented RegelSpraak construct" we
// union the legal top-level fragment kinds and anchor on EOF, so a rule that
// merely matches a prefix cannot masquerade as success. This is a test harness
// rule — it adds no language construct, only the alternatives that already exist.
corpusEntry
    : regelSpraakDocument            // whole document (already EOF-anchored): definities/regels
    | corpusFragment DOT? EOF        // a single non-document fragment (with optional sentence-final '.')
    ;
corpusFragment
    : resultaatDeel
    | voorwaardeDeel
    | regelSpraakRegel
    | variabeleDeel
    | objectTypeMember+
    | conditieBijExpressie
    | begrenzing
    | datatype
    | onderwerpKeten
    | referentie
    | expressie
    | regelAanhef        // a §4 illustration shows a rule's OPENING alone (name [+ version], no body)
    | extensieAanhef     // a §3.1.2 illustration shows an extensie's opening line alone (no members)
    ;
// Header-only illustrations. These are NOT in the real `regel`/`objectTypeExtensie`
// rules — those stay strict (a document still requires a complete body), so a real
// model with a body-less rule is still rejected. They live only in the corpus
// harness, where the question is "is this a recognizable RegelSpraak opening".
regelAanhef    : REGEL regelNaam versie? ;
extensieAanhef : EXTENSIE_VAN_OBJECTTYPE bepaaldLidwoord ( objectTypeNaam | lowerNaam ) ;

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
    | DE | HET | OF | DAN | ALS | NIET | GELDIG
    | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD
    | JAAR | MAAND | DAG | WEEK | KWARTAAL | UUR | MINUUT | SECONDE | MILLISECONDE
    | PERIODE | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN
    ;
// EN is deliberately NOT a name word: it is the operand separator of the §13.4.17
// concatenation-of-expressies forms ("de eerste/laatste/minimale/maximale waarde van
// A en B", §4.12-4.15) and the §13.4.6 gegevensverstrekking list. The spec defines a
// <naam> as a free <karakterreeks>, so "A en B" is syntactically ambiguous (a name
// containing "en", or two operands); the corpus only ever uses a bare "en" as a
// separator (never inside a declared name — "tot en met" is its own token), so EN
// splits. A name needing a literal "en" is an accepted, documented limitation.

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
objectTypeDefinition // §13.3.1.1 (+ the uchar 'is een' dialect extension, see below)
    : OBJECTTYPE bepaaldLidwoord? objectTypeNaam objectTypeSupertype*
      ( MV_START meervoudsvorm RPAREN )? BEZIELD?
      objectTypeMember*
    ;

// uchar DIALECT EXTENSION (grammar/DIVERGENCES.md §5) — the single direct supertype:
// "Objecttype het Wrakrecht is een Strandrecht (mv: Wrakrechten)". The clause sits between
// the name and the mv-group, whose order relative to (bezield) the grammar already fixes.
//
// Deliberately NO new lexer token. A whole-phrase IS_EEN would fire wherever the phrase occurs
// under maximal munch, and it occurs in ordinary RULE TEXT ("Een dag is een schouwdag"), so it
// would change documents that have nothing to do with subtyping. IS_VAN_HET_TYPE is safe only
// because its phrase occurs nowhere else. The existing IS and EEN tokens in sequence at the head
// position carry the clause instead, where the surrounding context disambiguates: a kenmerk
// declaration is IS lowerNaam …, and naamWoordLower excludes EEN, so no §13.3.2 member can be
// read as this clause.
//
// The rule ADMITS the two shapes WF-106 forbids — a second clause on one head, and "is een A en
// B" — so the resolver refuses them by name instead of leaving the author a bare syntax error.
// Single parenthood is a language bound above the wire; the wire cannot express its violation.
objectTypeSupertype
    : IS onbepaaldLidwoord objectTypeNaam ( EN onbepaaldLidwoord? objectTypeNaam )*
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
// it; casus source shows both the marked and unmarked adjectival form).
// A kenmerk may carry a tijdlijn ("… kenmerk (bezittelijk) voor elke dag", §13.3.6.1
// attaches a timeline to an attribute OR kenmerk).
kenmerkSpecificatie
    : IS lowerNaam ( MV_START meervoudsvorm RPAREN )? KENMERK BIJVOEGLIJK? tijdlijn? SEMICOLON?   # bijvoeglijkKenmerk
    | bepaaldLidwoord? lowerNaam KENMERK ( BEZITTELIJK | BIJVOEGLIJK )? tijdlijn? SEMICOLON?       # naamwoordKenmerk
    ;

// §13.3.2.5 Attribuut. The 'met eenheid' clause lives inside the numeric datatypes
// per the authoritative §13.3.3.2/.3 diagrams (not at member level as the draft has it).
// A domein-typed attribuut may still pin a unit ("… Bedrag met eenheid euro",
// §3.4): the eenheid clause is admitted after either a concrete datatype or a
// domein reference, not only inside numeriekDatatype.
attribuutSpecificatie
    : bepaaldLidwoord? lowerNaam ( MV_START meervoudsvorm RPAREN )? ( datatype | domeinNaam ) ( MET_EENHEID eenheidExpressie )?
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
// §13.3.3.2 mandates the '(' getalspecificatie ')'; a §3 worked example omits it
// ("Numeriek met eenheid jaren"), so the parenthesised precision is made optional.
numeriekDatatype   : NUMERIEK ( LPAREN getalSpecificatie RPAREN )? ( MET_EENHEID eenheidExpressie )? ;
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
    | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN // units spelled out plural ("met eenheid jaren")
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
// §13.3.7.5 numbers each label ('1. <waarde>'); a §3.6 worked example uses bullet
// glyphs instead ('• bruto'). Both delimiters are accepted; either stops the value
// at the next label.
labelWaardeSpecificatie: ( NUMBER DOT | bullet ) dimensieWaarde ;
// A label value excludes NUMBER so a value ("vorig jaar") stops at the next
// label's "N." marker instead of swallowing it (§13.3.7.6).
dimensieWaarde         : dimensieWaardeWoord+ ;
dimensieWaardeWoord
    : LOWER_ID | UPPER_ID | AANTAL_WORD
    | VAN | OP | IN | VOOR | OVER | BIJ | TOT | TOT_EN_MET | PER | MET | EN | OF | DAN | NIET | DE | HET | EEN | VANAF
    | EEN_TELWOORD | TWEE_TELWOORD | DRIE_TELWOORD | VIER_TELWOORD
    | JAAR | MAAND | DAG | WEEK | KWARTAAL | UUR | MINUUT | SECONDE | MILLISECONDE
    | PERIODE | DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN
    ;

// ====================================================================
// §13.3.8 Parameters
// ====================================================================
parameterDefinition // §13.3.8.1 — name bounded by ':'; article optional (spec examples omit it)
    : PARAMETER bepaaldLidwoord? parameterNaam COLON ( datatype | domeinNaam ) tijdlijn? SEMICOLON?
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
// ...", the parser yields a
// van-chain the resolver re-splits against the declared model. UPPER_ID is
// admitted so objecttype names ("Natuurlijk persoon") are part of a segment;
// the resolver classifies each segment as objecttype/rol/kenmerk/attribuut.
// DE/HET/MET are admitted so names with internal articles/"met" parse intact
// ("voor het berekenen van de belasting", "reis met paaskorting"). EEN/ZIJN/ALLE
// are NOT, so a navigation 'van een/zijn/alle <subject>' splits the chain; a
// 'van de/het <subject>' is absorbed and the resolver re-splits it.
kaleNaamWoord
    : LOWER_ID | UPPER_ID | NUMBER | PERCENT_SIGN | AANTAL_WORD
    | OP | IN | VOOR | OVER | BIJ | TOT | TOT_EN_MET | PER | MET | OF | DAN | NIET
    | DE | HET | GELDIG
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
// quantifier+bare-role form "alle deelnemers van de groep".
onderwerp
    : kwantificatie kaleNaam ( VAN onderwerp )?
    | onderwerpKeten
    ;
attribuutVanOnderwerp // §13.4.2.9
    : ( kwantificatie? attribuutMetLidwoord | bepaaldLidwoord rangtelwoord kaleNaam ) VAN onderwerp
    ;
// §13.3.2.6 — article optional. The article is `lidwoord?` (not only the definite
// one): a §8 worked example writes an indefinite attribute reference on a
// berekening's left side ("Een a van een persoon moet berekend worden als …").
attribuutMetLidwoord : lidwoord? kaleNaam ;

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
// multi-word phrase tokens ("... geen dagtaak is", "verdeling punten in
// gelijke delen", "... vertrek min reserveringstermijn"). It is exactly the run
// of tokens up to the 'geldig' that opens the first versie.
regelNaam   : ~GELDIG+ ;                             // §13.4.2.2
regelVersie : versie regelSpraakRegel ;             // §13.4.2.3
versie      : ( GELDIG | GELDIG_CAP ) versieGeldigheid ; // §13.4.2.4 (either case; see lexer note)
versieGeldigheid                                     // §13.4.2.5
    : ALTIJD
    | VANAF datumOfJaar ( TM datumOfJaar )?
    | TM datumOfJaar
    ;
datumOfJaar : DATE | {isJaar(_input.LT(1))}? NUMBER ; // §13.2.24: jaar = 4 digits
// §13.4.2.6 — the terminating '.' is made lenient: casus source omits it on several rules.
regelSpraakRegel
    : resultaatDeel voorwaardeDeel? DOT? variabeleDeel?
    ;
variabeleDeel      : DAARBIJ_GELDT COLON variabeleOnderdeel+ DOT? ;            // §13.4.2.10
variabeleOnderdeel : bepaaldLidwoord? variabeleNaam IS expressie DOT? ;        // §13.4.2.11
variabeleNaam      : vrijeNaam ;                                               // §13.4.2.12

// §12 Beslistabel: the header (name + optional validity) followed by the inline
// markdown pipe-table body (Option A surface syntax). The lexer captures each pipe
// line WHOLE as a TABEL_RIJ token (cells never contain '|'); the resolver splits
// the grid and desugars each data row to an ordinary regel (BeslistabelDesugar),
// so both back-ends get the rows for free. The table is optional — a header-only
// shell (the pre-body corpus) still parses and the resolver warns it is empty.
beslistabel : BESLISTABEL beslistabelNaam versie? TABEL_RIJ* ;
// The name must NOT greedily swallow the "geldig <versie>" (mirrors regelNaam's ~GELDIG+
// exclusion) NOR the pipe-table — without excluding GELDIG, "geldig vanaf 2018" is eaten
// into the name (the keyword 'altijd' happened to stop it, 'vanaf'/<jaar> do not).
beslistabelNaam : ~( GELDIG | TABEL_RIJ )+ ;

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

// §13.4.10 Verdeling. Bullets are '-'; trailing commas/periods are lenient
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
genestCriterium : bullet ( samengesteldCriterium | elementaireVoorwaarde ) DOT? ;
samengesteldCriterium
    : ER? WORDT_VOLDAAN AAN ( VOLGEND_CRITERIUM COLON | consistentieKwantificatie VOLGENDE_CRITERIA COLON )
      samengesteldCriteriumOnderdeel
    ;
uniciteitsControle : ( alleAttribuutVanOnderwerp | uniciteitConcatenatie ) vereniging* MOETEN UNIEK ZIJN DOT? ;
vereniging : VERENIGD_MET ( alleAttribuutVanOnderwerp | uniciteitConcatenatie ) ;
alleAttribuutVanOnderwerp : DE kaleNaam VAN ALLE kaleNaam ( VAN onderwerpKeten )? ( VAN onderwerpKeten )? ;
uniciteitConcatenatie
    : CONCATENATIE_VAN kaleNaam ( COMMA kaleNaam )* EN kaleNaam VAN ALLE kaleNaam ( VAN onderwerpKeten )? ( VAN onderwerpKeten )?
    ;

// ====================================================================
// §13.4.13-14 Voorwaarden
// ====================================================================
// §13.4.13.1: the voorwaardendeel opens with 'indien' OR, for a time-dependent
// condition, 'gedurende de tijd dat' (which replaces 'indien' — §8.4.18.4). The
// trailing condition is the same in both cases.
voorwaardeDeel // §13.4.13.1
    : ( INDIEN | GEDURENDE_DE_TIJD_DAT ) ( toplevelSamengesteldeVoorwaarde | toplevelElementaireVoorwaarde )
    | periodevergelijkingEnkelvoudig
    ;
// §13.4.13.2-.8: a subselectie predicaat is the COMPOUND form or an ELEMENTAIR predicate.
// The spec's elementairpredicaat (§13.4.13.5-.8: getal/tekst/datum/object) is the
// VRAGENDE-MEERVOUD (verb-trailing) operator — "die kleiner zijn dan X", "die gelijk zijn
// aan Y", "die een minderjarige zijn" — i.e. the toplevelOperator register. The verb-leading
// nonToplevelOperator is kept as a permissive tail: the corpus also writes a possession query
// verb-leading after 'die'/'dat' ("die heeft een kind"), and (as with the §8.3.2 bullet rule)
// over-acceptance of the verb-leading register here is harmless — the resolver gives the right
// meaning. toplevelOperator is listed first so the spec form is the preferred parse of an
// ambiguous kenmerk ("een minderjarige zijn"); elementKenmerkFilter reads either register.
predicaat // §13.4.13.2 (used in subselectie)
    : samengesteldPredicaat
    | toplevelOperator
    | nonToplevelOperator
    ;
// The tail after the ':' is EITHER the bulleted onderdeel OR the spec's inline
// <toplevelvoorwaardevergelijking> — a single non-bulleted comparison ("… die aan de
// volgende voorwaarde voldoet: zijn leeftijd is kleiner dan de volwassenleeftijd.",
// the §5.6 worked example). The EBNF names the verb-trailing register, but that very
// worked example writes it verb-LEADING, so the inline tail reuses elementaireVoorwaarde
// (both registers; the same over-acceptance posture as the bullets — the resolver gives
// the right meaning, and its consistentie/periode alternatives stay resolver-rejected).
samengesteldPredicaat // §13.4.13.4
    : AAN kwantificatie ( VOLGENDE_VOORWAARDE | VOLGENDE_VOORWAARDEN ) ( VOLDOET | VOLDOEN ) COLON
      ( samengesteldeVoorwaardeOnderdeel | elementaireVoorwaarde )
    ;

// §8.3.1/§8.1 Tabel 16: an ENKELE conditie used as a voorwaarde (after 'indien', or
// after 'dat'/'gedurende de tijd dat', which reuse this rule) takes the VRAGENDE vorm
// — "het werkwoord staat achteraan" (§8.3.1, §10.1): "indien hij
// een deelnemer is", "… gelijk is aan …", "… leeg is". So the TOPLEVEL elementaire
// voorwaarde admits ONLY the verb-trailing toplevelOperator; the verb-leading
// (stellende) register is reserved for samengestelde-voorwaarde BULLETS
// (elementaireVoorwaarde, §8.3.2). The BULLET rule deliberately stays
// permissive (admits BOTH families) — the draft's own examples mix registers in a
// bullet, and over-acceptance
// there is harmless (the resolver gives the right meaning); operator/operand legality
// stays a resolver concern in both positions.
toplevelElementaireVoorwaarde // §13.4.15.1 (§8.3.1 vragende = verb-trailing only)
    : consistentieVoorwaarde
    | expressie toplevelOperator
    ;
// The ':' before the bullet list is lenient: with the 'gedurende de tijd dat …
// wordt voldaan' opener two worked examples omit it, and the bullets unambiguously
// start the onderdeel regardless.
toplevelSamengesteldeVoorwaarde // §13.4.14.1
    : voorwaardeSubject AAN voorwaardeKwantificatie ( VOLGENDE_VOORWAARDE | VOLGENDE_VOORWAARDEN )
      ( VOLDOET | VOLDOEN | WORDT_VOLDAAN ) COLON? samengesteldeVoorwaardeOnderdeel
    ;
genesteSamengesteldeVoorwaarde // §13.4.14.2
    : voorwaardeSubject ( VOLDOET | VOLDOEN | WORDT_VOLDAAN ) AAN voorwaardeKwantificatie
      ( VOLGENDE_VOORWAARDE | VOLGENDE_VOORWAARDEN ) COLON? samengesteldeVoorwaardeOnderdeel
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
// A bulleted sub-condition may end with a '.', but the list continues with the
// next bullet — so the trailing '.' is consumed HERE, not left for the rule's own
// terminating DOT (which would otherwise close the rule before a following bullet).
genesteVoorwaarde : bullet ( genesteSamengesteldeVoorwaarde | elementaireVoorwaarde ) DOT? ; // §13.4.14.7
bullet : ( DASH | DOTDOT | BULLET )+ ; // '-' top level, '..' nested; EBNF glyph '•' also honoured

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
    | expressie ( nonToplevelOperator | toplevelOperator )
    | periodevergelijkingElementair
    ;
// §13.4.15.66/.18 / §8.1.9: the regelversie reference names the rule and — as in
// "regelversie Controleer of groep geen dagtaak (altijd) is inconsistent." — carries the
// version's geldigheid in parens. The EBNF's <karakterreeks> subsumes both; here the name (which cannot
// contain '(') and the parenthesised versie-aanduiding (the §13.4.2.5 geldigheid vocabulary) split cleanly.
consistentieVoorwaarde : REGELVERSIE vrijeNaam ( LPAREN versieGeldigheid RPAREN )? IS ( GEVUURD | INCONSISTENT ) ; // §13.4.15.66

gehelePeriodevergelijking // §13.4.15.70
    : NIET? ( GEDURENDE_HET_GEHELE JAAR | GEDURENDE_DE_GEHELE MAAND )
    | NIET_GEDURENDE_HET_GEHELE JAAR   // vragende-vorm negatie (the "niet" lexed with the phrase, §13.4.15.70)
    | NIET_GEDURENDE_DE_GEHELE MAAND
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

// §8.1 Tabel 16: the elfproef and numeriek-met-exact predicates have a NEGATED form — "voldoet NIET aan de
// elfproef", "is NIET numeriek met exact N cijfers" (stellende) / "NIET aan de elfproef voldoet" (vragende). The
// NIET sits between the verb and the predicate keyword; the resolver carries it as Predicaat.negatie.
nonToplevelOperator // §13.4.15.71-.100 (verb-leading), unified across types
    : ( IS | ZIJN ) gehelePeriodevergelijking? ( LEEG | GEVULD )
    | ( VOLDOET | VOLDOEN ) gehelePeriodevergelijking? NIET? AAN_DE_ELFPROEF
    | ( IS | ZIJN ) gehelePeriodevergelijking? NIET? NUMERIEK_MET_EXACT NUMBER CIJFERS
    | ( IS | ZIJN ) gehelePeriodevergelijking? vergelijkingsTeken expressie
    // §8.1.8.3 Tabel 16 zelfstandig kenmerkcheck NEGATION "is niet een <k>". The "een" (a separate token, not a
    // kaleNaamWoord) distinguishes it from the bijvoeglijk "is niet <k>" form below, where the NIET lexes INTO the
    // kaleNaam for the §8.1.8 verbatim-first de-negation; this explicit alt keeps that path untouched. Listed before
    // the EEN? kaleNaam alt so the NIET EEN sequence is the preferred parse.
    | ( IS | ZIJN ) gehelePeriodevergelijking? NIET EEN kaleNaam
    | ( IS | HEEFT | ZIJN | HEBBEN ) gehelePeriodevergelijking? EEN? kaleNaam
    | gehelePeriodevergelijking? EEN kaleNaam ( IS | ZIJN )
    ;
toplevelOperator // §13.4.15.19-.48 (verb-split / trailing), unified across types
    : gehelePeriodevergelijking? ( LEEG | GEVULD ) ( IS | ZIJN )
    | gehelePeriodevergelijking? NIET? AAN_DE_ELFPROEF ( VOLDOET | VOLDOEN )
    | gehelePeriodevergelijking? NIET? ( NUMERIEK_IS_MET_EXACT | NUMERIEK_ZIJN_MET_EXACT ) NUMBER CIJFERS
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
// every casus fixture are paren-free).
// ====================================================================
expressie
    : expressie TOT_DE_MACHT primaireExpressie afronding             # machtExpr        // §13.4.17.14 (afronding mandatory; exponent is primair so it rounds the POWER)
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
    | rekenConstante
    | HIJ
    | DE DAG
    | attribuutVanOnderwerp
    | onderwerp
    | variabeleNaam
    | LPAREN expressie RPAREN
    ;

// §13.4.17.10-58 named functions and aggregations
functieAanroep
    : SOM_VAN expressie dimensieSelectie? leegwaardeAlternatief?                                   # somFunc
    // §13.4.17.47 telling. The EBNF (§13.4.17.46) gives all four getalaggregatie functions one
    // <expressie> operand, but a count ranges only over onderwerpexpressies (typeringen §4.11),
    // never an arithmetic one (whereas §5.8.2 lets a sum's operand BE arithmetic). So ONLY aantal
    // takes a higher-precedence primaireExpressie: a trailing 'maal'/'plus' binds OUTSIDE the
    // count. Deliberate divergence from the symmetric EBNF — see docs/grammar-divergences.md.
    | HET AANTAL_WORD primaireExpressie ( COMMA primaireExpressie )* ( EN primaireExpressie )?    # aantalFunc
    | TOTAAL_VAN expressie conditieBijExpressie? leegwaardeAlternatief?                            # totaalFunc      // §13.4.17.56
    | ( MAXIMALE_WAARDE_VAN | MINIMALE_WAARDE_VAN ) expressie ( COMMA expressie )* ( EN expressie )? dimensieSelectie? # minMaxFunc // §13.4.17.15/.16/.47
    | ( EERSTE_VAN | LAATSTE_VAN ) expressie ( COMMA expressie )* ( EN expressie )?                 # eersteLaatsteFunc // §13.4.17.39/.40/.49
    | WORTEL_VAN primaireExpressie afronding                                                      # wortelFunc      // §13.4.17.13 (afronding mandatory; radicand is primair so the afronding rounds the ROOT)
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
    | AANTAL_DAGEN_IN ( DE MAAND | HET JAAR ) DAT ( toplevelSamengesteldeVoorwaarde | toplevelElementaireVoorwaarde | expressie ) # aantalDagenFunc // §13.4.17.57 (worked example §7.2 puts a predicate after 'dat')
    | TIJDSEVENREDIG_DEEL_PER ( MAAND | JAAR ) VAN expressie conditieBijExpressie                  # tijdsevenredigFunc // §13.4.17.58
    | CONCATENATIE_VAN expressie ( COMMA expressie )* EN expressie                                 # concatFunc      // §13.4.17.2/.8.14
    ;
eenheidMeervoud : DAGEN | WEKEN | MAANDEN | KWARTALEN | JAREN | JAAR | MAAND | DAG | UUR | MINUUT | LOWER_ID ;

afronding // §13.4.17.26
    : ( NAAR_BENEDEN | NAAR_BOVEN | REKENKUNDIG | RICHTING_NUL | WEG_VAN_NUL ) AFGEROND_OP NUMBER DECIMALEN
    ;
begrenzing // §13.4.17.28
    : begrenzingMinimum ( EN begrenzingMaximumNaEn )?
    | begrenzingMaximum
    ;
// §13.4.17.29/.30 use 'met een minimum/maximum van'; after 'en' a worked example
// drops the 'met' ("… en een maximum van 1000 €"), so the bare form is accepted too.
// The bound is a primaireExpressie, not the full <getalexpressie> §13.4.17.29/.30 allow: a
// trailing <afronding> on a begrensde expressie ("… met een minimum van 0 € naar beneden
// afgerond op 0 decimalen") must round the WHOLE bounded result, not the bound, and a
// full-expressie bound would greedily absorb the afronding. Bounded divergence (see
// docs/grammar-divergences.md): a bare arithmetic/percentage bound ("maximum van de basis
// maal 2") then mis-binds — write it parenthesised ("maximum van (de basis maal 2)"). Every
// spec bound is a literal or attribute ref. (Same precedence reasoning as the telling.)
begrenzingMinimum : MET_EEN_MINIMUM_VAN primaireExpressie ;
// §13.4.17.28/.30: a STANDALONE maximum requires 'met een maximum van'. The bare 'een maximum
// van' (no 'met') appears ONLY after 'en' (the §6 worked example "… en een maximum van 1000 €"),
// so it is admitted only in the post-'en' rule — not standalone.
begrenzingMaximum     : MET_EEN_MAXIMUM_VAN primaireExpressie ;
begrenzingMaximumNaEn : ( MET_EEN_MAXIMUM_VAN | EEN_MAXIMUM_VAN ) primaireExpressie ;

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
// §5.8.2 a "som van" may declare a fallback for the empty result: "… of 0 als die
// er niet zijn" — the sum yields the given value instead of a leegwaarde. (Only "som
// van"; an empty "totaal van" is unconditionally 0 per §4.20 typeringen, no fallback.)
leegwaardeAlternatief : OF expressie ALS_DIE_ER_NIET_ZIJN ;

// §13.4.17.51 mandates a leading 'over'; a §5.8.5 worked example references the
// dimension inline through its voorzetsel and elides 'over de <meervoud>', leaving
// the bare interval ("… in jaar vanaf <label> t/m <label>", with 'in jaar' absorbed
// into the attribute reference). The bare interval is accepted as a second form.
dimensieSelectie // §13.4.17.51
    : OVER ( aggregerenOverAlleDimensies | aggregerenOverVerzameling | aggregerenOverBereik )
    | VANAF dimensieWaarde TM dimensieWaarde
    ;
aggregerenOverAlleDimensies : ALLE dimensieNaamMeervoud ;                                  // §13.4.17.52
aggregerenOverVerzameling   : DE dimensieNaamMeervoud VANAF dimensieWaarde TM dimensieWaarde ; // §13.4.17.53
aggregerenOverBereik        : DE dimensieNaamMeervoud IN LBRACE dimensieWaarde ( ( COMMA dimensieWaarde )* EN dimensieWaarde )? RBRACE ; // §13.4.17.54

// §13.4.17 built-in reckoning constants. Normally bare, but a §7 worked example
// writes "de Rekendatum" / "het Rekenjaar" with an article; the article is optional.
rekenConstante : ( DE | HET )? ( REKENDATUM | REKENJAAR ) ;

getalWaarde   : NUMBER ( PERCENT_SIGN | eenheidExpressie )? ; // §13.2.17/.18
// §13.2.15 tekstwaarde + §5.4/§13.4.17.9 tekstenwaardereeks. A plain "…" is STRING_START STRING_TEXT? STRING_END;
// a "…«expressie»…" interleaves literal chunks with «expressie» escapes (each a full expressie, recursive).
tekstWaarde     : STRING_START tekstReeksDeel* STRING_END ;
tekstReeksDeel  : STRING_TEXT
                | L_ANGLE_QUOTE expressie R_ANGLE_QUOTE ;
booleanWaarde : WAAR | ONWAAR ;                               // §13.2.16
deDato        : DATE ;                                        // §13.2.19
