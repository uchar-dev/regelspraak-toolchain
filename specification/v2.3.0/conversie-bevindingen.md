# Conversie v2.3.0 PDF → Markdown — bevindingen

Verslag van de omzetting van de drie RegelSpraak v2.3.0 PDF's naar Markdown
(hybride aanpak: tekstlaag als woordanker + paginabeeld voor structuur,
diagrammen → Mermaid/EBNF). Paginamarkeringen `*pagina N*` staan als *footer*
tussen blokken (nooit middenin een alinea/tabel/codeblok/diagram).

## Overzicht

| Document | Pagina's | Mermaid | EBNF-only fallback | Bevindingen (flags) |
|---|---|---|---|---|
| `RegelSpraak-specificatie v2.3.0.md` | 149 | 4 | p.106, 107 | 85 |
| `RegelSpraak-specificatie - syntaxdiagrammen v2.3.0.md` | 81 | 306 | p.9, 30, 31 | 41 |
| `RegelSpraak-specificatie - typeringen v2.3.0.md` | 38 | 0 | — | 6 |

**Validatie uitgevoerd:**
- Paginamarkeringen: alle drie documenten 1..N aaneengesloten, uniek, monotoon, geen dubbele markeringen.
- Geen paginamarkering doorbreekt een alinea/tabel/codeblok (mid-zin-splitsingen hersteld: typeringen 3, spec 15).
- Codefences gebalanceerd in alle documenten.
- **Alle 306 Mermaid-diagrammen van het syntaxdocument en de 4 van het hoofddocument gerenderd met `mmdc` zonder parse-fouten.**

**Soorten flags:**
- `fixed-artifact` — extractie-/opmaakfout door pdftotext, stil hersteld tegen het beeld (geen actie nodig).
- `possible-spec-error` — lijkt een echte fout in de bronspecificatie; **verbatim overgenomen, niet gewijzigd** (zie hieronder; actie voor de spec-eigenaar).
- `uncertain` — twijfelgeval (bron-eigenaardigheid of artefact); verbatim overgenomen.

> De EBNF-grammatica's zijn verbatim uit de tekstlaag overgenomen (met reparatie
> van pure extractie-glitches tegen het beeld). De Mermaid-railroad­diagrammen zijn
> een visuele weergave; bij tegenspraak is de EBNF (of het PDF-beeld) leidend.

---

## A. Mogelijke bronfouten — hoofddocument (`RegelSpraak-specificatie v2.3.0`)

### Ongebalanceerde EBNF / grammatica
- **p.57** `<onderwerpketen>`: ongebalanceerde haakjes — opent `((<selector> "van" <onderwerpketen>)` maar sluit de buitenste groep niet.
- **p.57** `<samengesteldpredicaat>`: losse/extra quote `"volgende voorwaarde"["n"]"` en `voldoen` mist een openingsquote.
- **p.59** `<aggregerenoververzameling>`: lege non-terminals `"vanaf" <> "t/m" <>` (geen symboolnaam tussen `<>`).
- **p.60 / p.137** `<getalexpressie>`: alternatie-operator binnen één non-terminal: `<begrenzingexpressie | afrondingexpressie>` (zou `<begrenzingexpressie> | <afrondingexpressie>` moeten zijn).
- **p.93 / p.106 / p.132** consistentieregel/uniciteit-EBNF: `<enkelvoudigeconsistentieregel` mist `>`; `objecttypenaam>` mist `<`; losse `]`; ongebalanceerde haakjes in `toplevelsamengesteldcriterium`/`samengesteldcriterium`.
- **p.96** `<samengesteldcriterium>`: ongebalanceerde haakjes rond `(<consistentiekwantificatie> "volgende criteria:")`.
- **p.111** `<meervoudigcriterium>` verwijst naar `<veldeelafronding>` terwijl de regel eronder `<verdeelafronding>` heet (typfout in non-terminal).
- **p.115** `<voorwaardendeel>`: drie `(` maar twee `)`.
- **p.117** `voowaardekwantificatie` (mist `r`) t.o.v. `voorwaardekwantificatie` in de producties erboven; plus een losse afsluitregel `")` zonder match.
- **p.131** casinginconsistentie `<regelSpraakregel>` (hoofdletter S) vs `<regelspraakregel>`.
- **p.133** spaties binnen tokennamen: `< startpuntbepaling>`, `<datumexpressie >`.
- **p.138** items 46/48 `<getalaggregatie>`/`<datumaggregatie>`: openingshaakje zonder sluiting; item 56 `[<conditiebijexpressie >]` spatie vóór `>`.
- **p.149** Bijlage 3 CORBA `<versie>` sluit met `}` i.p.v. de openende `)`; W3C/ISO-varianten gebruiken `<datum>` inconsistent met `jaartal | datum`.

### Defecte kruisverwijzingen (onopgeloste Word-velden)
- **p.99** "paragraaf **Fout! Verwijzingsbron niet gevonden.**" (sectie 8.4.3).
- **p.114** voetnoot 32: "zie paragraaf **Fout! Verwijzingsbron niet gevonden.**".
- **p.118** "paragraaf **8.4.18.4**" — vermoedelijk verhaspeld sectienummer.
- **p.119** "hoofdstuk **109.9**" — verhaspeld sectienummer.

### Inhoudelijke / reken-inconsistenties
- **p.110** worked example Nel/Ria: tekst noemt pot van **367** treinmiles maar rekent met **337** en **227** (`3/4 * 337 = 252`, `1/4 * 227 = 91`).
- **p.78** Tabel 14: rij-operator is "absolute tijdsduur van…tot…" maar het Voorbeeld in dezelfde cel zegt "tijdsduur van…tot…".
- **p.35** objecttype *Vlucht*: kenmerken met lidwoord "de" (bv. "de reiziger") naast "is …"; "bereikbaar per trein" en "gebruik fossiele brandstof < 50%" verschijnen zowel als kenmerk als als Boolean-attribuut.

### Taal/typefouten (verbatim overgenomen)
- **p.30** "omreken factoren" (los) i.p.v. "omrekenfactoren".
- **p.42** "…in vergelijking gebruik van niet-tijdsafhankelijke…" (grammaticaal incompleet); §5.1.2 eindigt zonder punt.
- **p.43** dubbel "is is"; dubbel "dag dag".
- **p.44** opsomming "jaar, maand, week of dag" — "week" wijkt af van de jaar/maand/dag-reeks.
- **p.47** "moet **de** aansluiten op de knips" (overtollig "de").
- **p.70** "als volg uit" (mist "t": "als volgt").
- **p.82** dubbele "van " vóór elke datumrange in de opsomming.
- **p.85** "…heeft de parameter … **heeft** een numeriek datatype" (dubbel "heeft").
- **p.89** "lager of gelijk" i.p.v. "later of gelijk"; "een beide expressies onwaar **is**" (getal-congruentie).
- **p.93** "datatypes geen kenmerken hebben**Als** we…" (ontbrekende punt).
- **p.94** zin eindigt met komma i.p.v. punt.
- **p.95** "dan ziet zo'n regel **ziet** er als volgt uit" (dubbel "ziet").
- **p.98** "de Passagier niet aan het samengestelde criterium **voldoen**" (enkelvoud/meervoud).
- **p.104** "Contingent **T**reinmiles" vs objecttype "Contingent treinmiles".
- **p.107** "op volgorde van toenemende **de** leeftijd" (overtollig "de"; herhaalt op p.109/110).
- **p.113** "dan kan de **moeten** de regels … gegroepeerd" (redactie-restant).
- **p.123** "Belasting op basis van reisduur **Eerst** schijf" (mist "e").
- **p.124** "is voorbeeld van een conditiekolom" (mist "een").
- **p.144** TOKA-disclaimer: "De … informatie echter volledig verzonnen" (mist "is").
- **p.145** Artikel 5 lid 5: "die minimaal gelijk aan de afstand" (mist "is").

### EBNF-only fallback (diagram niet in Mermaid omgezet)
- **p.106, 107** vol-pagina consistentieregel-/verdeling­diagrammen — EBNF dekt de grammatica volledig; railroad te complex/over meerdere pagina's. Zie PDF.

---

## B. Mogelijke bronfouten — syntaxdiagrammen (`… - syntaxdiagrammen v2.3.0`)

### Ongebalanceerde EBNF / grammatica
- **p.39 (13.4.8.7)** & **p.40 (13.4.8.10)**: ongebalanceerde haakjes rond `("het volgende criterium:" | (<consistentiekwantificatie> "volgende criteria:")`.
- **p.42 (13.4.10.3)** `<meervoudigcriterium>` verwijst naar `<veldeelafronding>`, maar het diagram en §13.4.10.7 gebruiken `<verdeelafronding>`.
- **p.33 (13.4.1.1)** `<onderwerpketen>`: ongebalanceerde `((` zonder sluiting.
- **p.34** casing `<regelspraakregel>` (13.4.2.3) vs `<regelSpraakregel>` (13.4.2.6).

### EBNF ↔ diagram tegenspraak
- **p.71 (13.4.17.4 Datumexpressie)** EBNF mist de takken `parametermetlidwoord` en `variabelenaam` die het diagram wél toont.
- **p.72 (13.4.17.6 Tekstexpressie, 13.4.17.7 Boolean expressie)** idem: diagram heeft `parametermetlidwoord`/`variabelenaam` die in de EBNF ontbreken.
- **p.80 (13.4.17.53)** `<aggregerenoververzameling>` met lege `<>`-placeholders (komt overeen met diagram zonder operand-boxen).
- **p.47 (13.4.14.7)** EBNF gebruikt bulletglyph `•` in `("•")+` terwijl het diagram `*` toont.

### Kop-/naamfouten
- **p.11** kop "13.2.10 **Bepaallidwoord**" (mist "d") terwijl de non-terminal `<bepaaldlidwoord>` heet.
- **p.30** "13.3.7.1 Parameterdefinitie" staat onder subsectie "13.3.8 Parameters" — nummering zou 13.3.8.1 moeten zijn.
- **p.61 (13.4.15.66)** literal `gevuurd` (zowel EBNF als diagram) — vermoedelijk "gevuld".

### Overig
- **p.5** introtekst noemt "CORBA scripting taal" — CORBA is geen scripttaal (waarschijnlijk OMG IDL/MOF-EBNF-notatie bedoeld).
- **p.4/5** twee verschillende tabellen delen het label "Tabel 1".
- Diverse EBNF-blokken (p.26, 33, 62, 65, 66, 81): gemengde rechte/typografische quotes (`"…”`) binnen één productie.
- **EBNF-only fallback:** p.9 (`<letter>`, ±78 alternatie-takken), p.30/31 (cross-page diagram). EBNF dekt de grammatica.

---

## C. Mogelijke bronfouten — typeringen (`… - typeringen v2.3.0`)

- **p.4** Tabel 1 (Versiebeheer): "expres**ss**ie" (drie s'en) in de 1.2.0-regel.
- **p.14** Tabel 17 (tot de macht): cel "Eenheid restrictie" van de Operator-rij is leeg terwijl elders een `-` staat.
- **p.19** Tabel 25 (laatste van): operatorwaarde "**L**aatste van" (hoofdletter) i.t.t. "eerste van"/"som van" elders.
- **p.24** §5: dubbele ontkenning "… niet in dit hoofdstuk **niet** uitgewerkt".
- **p.21** Tabel 28: operatorwaarde "dag/maand/jaar uit" wijkt af van de volledige kop "Jaar/maand/dag/uur/minuut/seconde/milliseconde uit". *(uncertain)*
- Opmaak: in §5.13/5.15/5.16 staat de kop **Datatypen en eenheden** tweemaal (bron-eigenaardigheid; verbatim overgenomen).

---

## D. Reparaties tijdens conversie (geen actie nodig)

Stil hersteld tegen het paginabeeld, betekenis ongewijzigd:
- Tekstlaag-artefacten: afgebroken woorden samengevoegd, kolom-shuffle in tabellen
  hersteld, verloren inspringing in code/EBNF teruggezet, ontbrekende `<`/`>` in
  EBNF-tokens hersteld waar het beeld dit bevestigde (o.a. syntax p.38/39, spec p.129).
- Breuken die als gestapelde cijfers werden geëxtraheerd, inline weergegeven
  (bv. `1/1000 m`, `2 1/11`, `-3/8 %`).
- Figuren zonder tekstlaag (instantie-/tijdlijn­diagrammen, spec p.25/45) als
  cursieve beschrijving weergegeven zonder cijfers te verzinnen; geen figuur weggelaten.
- Twee Mermaid-knooplabels voor het `"`-terminal omgezet van `\"` naar `&quot;`
  zodat ze correct renderen (syntax p.16 `<tekstwaarde>`, p.71 `<tekstenwaardereeks>`).

---

*De map `_work/` bevat de paginabeelden (PNG, 200 dpi) en tekstlagen die als bron
voor de conversie zijn gebruikt; deze is verwijderbaar.*
