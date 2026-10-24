# drl-rule-functions-fields: drlRules wijst per regel zijn stuk van drl aan, met zijn functies

*Voorstel van de werkbank (2026-10-24). De eigenaar vroeg de werkbank-sessie deze kaart te schrijven en zelf te bouwen,
na de uchar-kaart `drl-rule-functions` (uchar `backlog/new/20261024-drl-rule-functions.md`) en na de landing van
`drl-rule-part-fields`, omdat die `Playground.drlRules` ook verandert. Niet gecommit tot de bouw.*

## Aanleiding

`drlRules` (`Playground.java` r. ±616, `docs/ENGINE.md`) zegt per regel van `drl` wat hij verlaagt. De werkbank deelt
`drl` op in de stukken `rule "…" … end` en legt die naast de vermeldingen. De functies die een regel aanroept (elke
toets en elke waarde, in het Java-dialect, met de RegelSpraak-zin als commentaar) staan in `drl` vóór de regel, buiten
die stukken; de werkbank toont ze dus niet, en een lezer ziet niet wat een regel berekent. Voorbeeld en getallen: zie de
uchar-kaart (v2/herleving-ww, `terugval 2` van `defaults A:Eindigingsgrond.herlevingsdatum`, functie `r118_afloop2`
met de dag 1; 339 regels en 562 functies).

## Doel

- Elke vermelding van `drlRules` krijgt wat uchar per regel levert (kaart `drl-rule-functions`): de plaatsen van zijn
  stuk in `drl` (functies en regel), of de namen en plaatsen van zijn functies. De werkbank snijdt dan alleen op die
  plaatsen en leest geen namen. De toolchain kiest de sleutels en zegt waarom; zij vertaalt wat uchar levert.
- Plaatsen in tekens van `drl` zoals het antwoord het als JSON-tekst geeft; zeg in ENGINE.md in welke eenheid (UTF-16
  zoals JavaScript telt, of code points), want de bronnen hebben letters als `ë`.
- `docs/ENGINE.md` en de javadoc van `Playground.drlRules` bij.

## Acceptatie

- (a) Op de bundel van v2/herleving-ww: voor elke vermelding geeft het nieuwe veld een stuk van `drl` dat begint met
  zijn functies (of met `rule` zonder functies) en eindigt na zijn `end`; de stukken dekken samen de tekst na de kop,
  zonder overlap. De vermelding van `defaults A:Eindigingsgrond.herlevingsdatum: terugval 2` heeft de functies
  `r118_…`, waaronder `r118_afloop2` met `java.math.BigDecimal("1")` als dag.
- (b) Zonder DRL (`drlError`): `drlRules` blijft een lege lijst.
- (c) `make gates` groen.

## Daarna, in de werkbank (geen deel van deze kaart)

De kaart "Verlaagde DRL" toont boven elke DRL-regel zijn functies, ingeklapt, met de RegelSpraak-zin uit hun
commentaar zichtbaar.
