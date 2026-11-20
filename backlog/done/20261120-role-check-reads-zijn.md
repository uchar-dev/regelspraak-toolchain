# role-check-reads-zijn: de zin van een kenmerkcheck over een rol zegt "zijn <rol>", niet "de <rol>"

*Opgesteld door de hoofdsessie op 2026-11-20, op uchars kaart `role-article-in-explanation` (in uchar
`backlog/done/20261120-role-article-in-explanation.md`, uchar `0084104db`), die een werkbanksessie schreef uit de ronde
van de marker `niet van toepassing` op de herlevingsdatum. Zusterkaart van dezelfde dag:
`initialisation-reason-reads-zijn`.*

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

## Result

*Hoofdsessie, 2026-11-20.*

- **Code** (`b99c9af14`): `Verbaliser.who` schrijft "zijn " vóór de rol van een kenmerkcheck, waar hij "de " schreef.
  De lus over meer stappen is weg: de wandeling van een check is één stap, en een langere faalt luid, want een rol na
  de eerste vraagt wel een lidwoord.
- **Voorlopig besluit van de hoofdsessie**: "zijn <rol>", geen lidwoord op de draad. De redenen staan in de Result van
  uchars kaart.
- **Dezelfde fout elders, meegenomen.** `initialisationNotDecided` schreef een vast "de" vóór het attribuut: "'bergloon
  zonder gewicht' gold niet, want de bergloon had een invoerwaarde" bij `het bergloon` (gemeten met de proef
  `werk/role-article-in-explanation/probes/displaced-initialisation.rgs`). De zusterkaart veranderde de twee zinnen
  ernaast en miste deze; geen test legde haar vast. Zij zegt nu "want zijn bergloon had een invoerwaarde", en "… had
  al een waarde" waar iets anders het doel afleidde.
- **Nieuwe tests**: `ExplanationFollowsCharacteristicKindTest.a_role_declared_with_het_stands_after_zijn`, eerst rood
  op de onveranderde `Verbaliser` ("P2 is niet herleefd, want niet geldt: de recht de indicatie herleving heeft";
  `werk/role-article-in-explanation/logs/red-het-role.log`), en
  `ExplanationDefaultsComputationTest.an_initialisation_that_waits_for_the_input_names_its_attribute_with_zijn`.
- **Tests die de kaart omdraait**: negen vastgelegde zinnen en één vastgelegde reeks delen, elk van "de <rol>" naar
  "zijn <rol>": `ExplanationReadingsTest` (3), `ExplanationFollowsCharacteristicKindTest` (1),
  `ExplanationReadingsServiceTest` (3) en `ExplanationPartsServiceTest` (2 zinnen en de delen van één ervan: `«de »` →
  `«zijn »`).
- **De regelset v2** (regelspraak-rules 229d744; de meetcasus `meet-gronden.rgs` van de werkbank en de acht
  scenariobestanden van `v2/herleving-ww`; vóór en na in `werk/role-article-in-explanation/meting/`). De 23 van uchars
  kaart zijn regels die `grep "de recht "` telt: 21 zinnen met het foute lidwoord en 2 regels met het wetscitaat "het
  oude recht". De 21 zeggen nu "zijn recht de indicatie herleving recht op uitkering heeft". Over de negen bestanden
  veranderen 397 zinnen, elk alleen in "de" → "zijn" direct vóór een rol, 614 keer: `recht` 321 en `eerdere recht` 74
  (het-woorden, fout), `aanvraag uitkering` 186 en `uitkeringsduur` 33 (de-woorden, waren goed). Geen ander woord van
  enige zin verandert, en "de recht" en "de eerdere recht" staan als woorden in geen zin meer. De uitslagen zijn
  gelijk: 204 PASS en 10 FAIL, vóór en na. De tien FAIL staan in de meetcasus, waarvan de verwachtingen van vóór de
  marker in de regelset zijn ("verwacht=leeg, werkelijk=niet van toepassing").
- **Tekst**: README, één regel in de lijst van contractwijzigingen. `docs/ENGINE.md` noemt de zin van een check over
  een rol en de reden van een wachtende initialisatie niet.
- **Gezien en gelaten**: de terugval "de <attribuut>" en "de <parameter>" voor een lezing zonder brontekst
  (`Verbaliser.describe`) schrijft nog een vast "de"; "zijn" past daar niet zonder meer, want de lezing kan over een
  rol gaan en een parameter heeft geen bezitter. In de zin van een verdeling op een ontvanger leest de voorwaarde op
  de bron: "zijn <rol>" verwijst daar naar de bron, zoals "zijn <attribuut>" in een geciteerde lezing op die plek al
  doet. `initialisationNotDecided` zegt "had een invoerwaarde" zonder het onderscheid voor een gemaakt object dat
  `standingValue` maakt; de ene constructie die ik probeerde (een objectcreatie die een attribuut met de
  opgavemarkering een waarde geeft, `probes/displaced-initialisation-made.rgs`) weigert uchars frontend, dus een
  foute zin is daar niet gevonden.
- **Voor de werkbank**: `werk/role-article-in-explanation/WERKBANK.md`.

`make gates` op `b99c9af14`, groen (`werk/role-article-in-explanation/logs/gates-2.log`): pin, reactor (208/585/62
testgevallen), twins, replay (374 goldens, 1057 cases), manifest, image en kind-smoke; `gates: GROEN — 7 stappen in
8m18s`. Een eerdere run is in stap 2 met de hand gestopt, omdat daarna nog een commentaarregel in `Verbaliser`
veranderde (`gates-1.log`). De commit hierna raakt alleen deze kaart.
