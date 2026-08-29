package io.github.yhavinga.regelspraak.schema;

import io.github.yhavinga.regelspraak.cli.RegelSpraakCli;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins gate G6: the {@code RegelSpraakCli --facts} CLI path (exposed as {@link io.github.yhavinga.regelspraak.cli.RegelSpraakCli#factsNaarDisk}) generates a
 * fact-model that (a) COMPILES as pure-JDK beans and (b) SATISFIES {@link ContractValidator} for the model it
 * came from — generate-then-validate closes the loop, so the generator and the validator can never drift. The
 * fixture is the TOKA model foundation (the manifest's companion model files: examples/toka/toka-01..16 plus
 * the reconciliatie/model-extensie companions), which resolves WITHOUT errors — the toka-2x regel files carry
 * the known §4.1 datum+datum quirk (see {@link io.github.yhavinga.regelspraak.CasusTest}) and add no fact-model structure,
 * so the model-level fact contract is fully determined by the foundation alone.
 *
 * <p>The second test pins the ERROR discipline of the mode: an unresolvable model NEVER touches disk.
 */
class FactModelCliTest {

    /** The manifest's model-foundation companion set — the SAME files `make facts` concatenates. */
    private static final List<String> MODEL_FOUNDATION = Stream.concat(
            Stream.of(
                    "toka-01-objecttype-de-natuurlijk-persoon-mv-natuurlijke",
                    "toka-02-objecttype-de-vlucht-mv-vluchten",
                    "toka-03-objecttype-het-contingent-treinmiles",
                    "toka-04-extensie-van-objecttype-de-natuurlijk-persoon",
                    "toka-05-domein-bedrag-is-van-het-type",
                    "toka-06-domein-luchthavens-is-van-het-type",
                    "toka-07-parameter-de-korting-bij-gebruik-niet",
                    "toka-08-feittype-vlucht-van-natuurlijke-personen",
                    "toka-09-feittype-reis-met-contingent-treinmiles",
                    "toka-10-feittype-verdeling-contingent-treinmiles-over-passagiers",
                    "toka-11-wederkerig-feittype-partnerrelatie",
                    "toka-12-eenheidsysteem-valuta",
                    "toka-13-eenheidsysteem-tijd",
                    "toka-14-eenheidsysteem-afstand",
                    "toka-15-dagsoort-de-kerstdag-mv-kerstdagen",
                    "toka-16-tijdlijn-de-variabele-periode-van-3")
                    .map(n -> "examples/toka/" + n + ".rgs"),
            Stream.of("examples/toka-reconciliatie/casus-omissies.rgs",
                    "examples/toka-scenarios/scenario-model-extensie.rgs"))
            .collect(Collectors.toList());

    @Test void gegenereerde_toka_facts_compileren_en_valideren(@TempDir Path work) throws IOException {
        Path repo = Path.of("");
        assumeTrue(Files.isReadable(repo.resolve(MODEL_FOUNDATION.get(0))), "TOKA fixtures absent");

        String bron = MODEL_FOUNDATION.stream()
                .map(p -> read(repo.resolve(p)))
                .collect(Collectors.joining("\n"));
        RegelSpraakModel model = Resolver.resolve(bron);
        assertTrue(model.isOk(), () -> "model-foundation moet schoon resolveren: " + model.errors());

        // The CLI's own write path — generate the beans to disk exactly as `Resolver --facts` does.
        Path src = work.resolve("src");
        int n = RegelSpraakCli.factsNaarDisk(model, src);
        assertEquals(model.objecttypen.size() + 2, n, "één bean per objecttype + Segment + Inconsistentie");

        // Compile the generated sources as pure-JDK beans (no external classpath) and load them.
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        List<String> bronBestanden;
        try (Stream<Path> ss = Files.walk(src)) {
            bronBestanden = ss.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString).collect(Collectors.toList());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "geen JDK-compiler beschikbaar (draai op een JDK, niet een JRE)");
        List<String> args = new java.util.ArrayList<>(List.of("-d", classes.toString()));
        args.addAll(bronBestanden);
        int rc = javac.run(null, null, null, args.toArray(new String[0]));
        assertEquals(0, rc, "gegenereerde TOKA-facts moeten pure-JDK compileren");

        // Load the GENERATED beans in isolation: the test classpath already carries hand-written
        // io.github.yhavinga.regelspraak.facts.* fixture beans, and a parent-first loader would shadow ours with those.
        // The generated beans reference only java.*, so the platform classloader is a sufficient parent —
        // it resolves java.* but knows nothing of io.github.yhavinga.regelspraak.facts, so the fresh classes win.
        try (URLClassLoader cl = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            List<String> schendingen = ContractValidator.valideer(model, cl);
            assertTrue(schendingen.isEmpty(),
                    () -> "gegenereerde facts moeten de eigen contract-validator leeg laten: " + schendingen);
        }
    }

    @Test void facts_modus_foutinvoer_exit1_en_schrijft_niets(@TempDir Path work) throws IOException {
        // A clean-parsing model with a single type error (Tekst toegekend aan een Numeriek).
        String bron = """
                Objecttype de Persoon (mv: Personen)
                    de leeftijd Numeriek (geheel getal);
                    de naam Tekst;

                Regel fout
                    geldig altijd
                        De leeftijd van een Persoon moet berekend worden als de naam van de Persoon.
                """;
        Path bronBestand = work.resolve("fout.rgs");
        Files.writeString(bronBestand, bron);
        assertFalse(Resolver.resolve(bron).isOk(), "de fixture moet een typefout opleveren");

        // Drive the REAL CLI path (main is exactly `System.exit(factsModus(rest))`): the error discipline is
        // exit 1 with NOTHING written — the outdir must not even be created.
        Path outdir = work.resolve("uit");
        int exit = RegelSpraakCli.factsModus(List.of(outdir.toString(), bronBestand.toString()));
        assertEquals(1, exit, "resolver-fout moet exit 1 geven");
        assertFalse(Files.exists(outdir), "het foutpad mag niets onder de outdir aanmaken");

        // And the usage discipline: a missing argument is exit 2, equally write-free.
        assertEquals(2, RegelSpraakCli.factsModus(List.of(outdir.toString())), "ontbrekend bronbestand moet exit 2 geven");
        assertFalse(Files.exists(outdir));
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
