# role-check-reads-zijn: de zin van een kenmerkcheck over een rol zegt "zijn <rol>", niet "de <rol>"

*Opgesteld door de hoofdsessie op 2026-11-20, op uchars kaart `role-article-in-explanation`
(`uchar/backlog/done/20261120-role-article-in-explanation.md`), die een werkbanksessie schreef uit de ronde van de
marker `niet van toepassing` op de herlevingsdatum. Zusterkaart van dezelfde dag: `initialisation-reason-reads-zijn`.*

## Aanleiding

De uitleg van een kenmerkcheck over een rol schreef altijd "de" vóór de rol. De regelset v2 declareert
`het recht (mv: rechten) RechtOpUitkering`, en de uitleg zei: "P1 heeft geen berekende herlevingsdatum, want geen regel
gold; de regel 'herlevingsdatum art. 21 lid 1' gold niet, want niet geldt: de recht de indicatie herleving recht op
uitkering heeft". `Verbaliser.who` schreef het lidwoord letterlijk.

De kaart van uchar liet open of het IR het gedeclareerde lidwoord van een rol draagt. Gemeten: het draagt het niet.
`Role` in `uchar-ir-v1.schema.json` heeft `name`, `objectType`, `plural`, `many`, `origin` en `designations`, en
`IrLoader.factType` vult `FactType.roleArticles` niet. uchars frontend bewaart het lidwoord alleen op de route vanaf
de brontekst, voor zijn eigen meldingen. De uitleg leest het model van de lader, dus de toolchain kan het goede
lidwoord niet opvragen.

## Goal

De zin luidt "zijn <rol> <gezegde>": "zijn recht de indicatie herleving recht op uitkering heeft". "Zijn" klopt voor
een de-woord en een het-woord, en zo schrijft de uitleg een lezing en de reden van een initialisatie al. De navigatie
van een kenmerkcheck is op de draad één stap (uchars IR-SPEC ch. 4.1a), dus de vorm dekt elke check die het IR kan
dragen.

Het gedeclareerde lidwoord tonen ("het recht") vraagt een veld op de draad en is deze kaart niet.

## Acceptance

- Een rol die als het-woord is gedeclareerd, met een kenmerkcheck via die rol in een voorwaarde die geldt en in een die
  niet geldt: de uitleg schrijft "zijn recht …". De test is eerst rood getoond.
- De regelset v2 van regelspraak-rules (229d744): geen zin met "de recht" meer; elke andere zin die verandert, staat in
  de Result.
- README als huidige stand; `make gates` groen.

May touch: `toolchain/`, `service/`, README, kaartverplaatsing.

Out of scope: een lidwoord op de draad; de terugval "de <attribuut>" en "de <parameter>" voor een lezing zonder
brontekst.

## Omvang

Klein.
