# engelse-namen: de code volgt de nieuwe namen van uchar, en haar eigen namen worden Engels

*Vastgelegd op 2026-10-18. Hoort bij uchar-kaart `rename-public-api` (stap 6 van het programma "Engelse namen",
`backlog/doing/20261018-rename-public-api.md` in uchar) en landt daarna. Toolchain `97d25bb`, uchar-pin `9159c9bd`.*

## Aanleiding

uchar schrijft sinds 2026-10-17 alle identifiers in het Engels (`CLAUDE.md` van uchar, Conventions). De distributie las daarvan 359
namen van `backends/drools` en `frontends/regelspraak` en hield ze daarmee Nederlands (`frozen/*.tsv`). Haar eigen code heeft 1.484
Nederlandse namen (13.405 plekken) in `service`, `service-quarkus`, `toolchain`, `scripts`, `tools` en `docker`:
`BeslisserKern`, `Verbalisator`, `NaamLezer`, `FixpuntGegevens`, 406 testzinnen. De regel in `CLAUDE.md` ("the domain vocabulary in
Dutch (`BeslisserKern`, `Verbalisator`)") vervalt; de conventie van uchar geldt hier ook.

## Wat er gebeurt

1. uchars kaart `rename-public-api` landt op uchars `main`; de nieuwe `main` komt in de submodule (`git -C uchar fetch`, geen push).
2. Eigen namen, op de oude pin, met het gereedschap van uchar (`scripts/lib/naming/` van uchars `main`, aangeroepen op de boom van de
   distributie): tabel `scripts/lib/naming/tables/` van de distributie, bevroren lijst `frozen/toolchain.tsv` (wat zichtbaar blijft, zie
   hieronder), `apply --files-only`, `apply` met beslislijst (`triage`, Haiku, `merge-decisions`). Eerst, in een eigen commit, een
   `@JsonProperty("<oude naam>")` op de tien niet-geannoteerde componenten van `DienstDescriptor`, zodat het descriptorformaat niet meer aan
   de componentnamen hangt.
3. Pin naar de nieuwe uchar; `javac_follow.py` (de uchar-tabel als bron) volgt de compileerfouten van de reactor tot javac schoon is. Het
   gereedschap kan geen namen hernoemen die in de submodule gedeclareerd zijn (`git ls-files` ziet de submodule als één item, en een
   lid dat via een waarde gelezen wordt telt als "buiten de zone"); het script vervangt de oude naam op precies de plek die javac meldt.
4. Namen in tekst die javac niet ziet (commentaar, javadoc, README, `docs/ENGINE.md`, de ene `Class.forName` in `GoldenOverQuarkusTest`):
   een grep per oude naam uit de tabel, per hit een beslissing.
5. `CLAUDE.md`: de laatste regel van "Working" verwijst naar de conventie van uchar of neemt haar regels over; de Engelse identifiers gelden
   ook voor bestands- en mapnamen.

Blijft staan: alles wat buiten de code zichtbaar is. JSON-sleutels van de HTTP-API en haar OpenAPI-beschrijving, subcommando's en
vlaggen van `bin/regelspraak`, Make-targets en -variabelen, meldingen, bestandsformaten, Helm- en Dockerconfiguratie (`docker/`:
`DESCRIPTOR`, `BRONNEN_MANIFEST`, `POORT`, `ALLOW_MARKERS`), de enumconstanten die zij zelf als JSON of OpenAPI schrijft
(`Schending.Soort`, `Evaluatiefout.Soort`), `REPLAY_WERKERS`, `bronnenManifest` (Helm), `examples/toka/*.rgs` en de domeininhoud van
testdocumenten. De volledige lijst met waar elke naam zichtbaar is en een voorstel per naam staat in het Result van uchars kaart.

## Acceptatie

- (a) `inventory service service-quarkus toolchain scripts tools docker` meldt alleen bevroren namen.
- (b) `make poorten` groen: check-pin, `mvn -B -o verify` met hetzelfde aantal testgevallen als op `97d25bb` (1.619 / 140 / 332 / 53
  in de laatste meting, plus wat uchar toevoegde), tweelingen, replay, manifest. `make gates` (image en kind-smoke) als Docker en kind er zijn;
  zo niet, dan staat dat in het Result.
- (c) Geen verandering buiten de code: de OpenAPI-goldens, de JSON-goldens en de uitlegtests ongewijzigd (een gewijzigd golden is
  een gevonden zichtbare naam en gaat in de lijst, niet in het golden).
- (d) De pin staat op de nieuwe `main` van uchar (`make check-pin`).

## Omvang

May touch: `service/`, `service-quarkus/`, `toolchain/`, `scripts/`, `tools/`, `bin/regelspraak`, `docs/`, `README.md`, `CLAUDE.md`, de
uchar-pin, deze kaart. Niet `uchar/`. Geen verandering van de zichtbare namen hierboven.
