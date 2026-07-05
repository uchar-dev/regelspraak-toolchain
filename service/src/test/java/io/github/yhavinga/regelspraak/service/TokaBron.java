package io.github.yhavinga.regelspraak.service;

import io.github.yhavinga.regelspraak.engine.BeanCompiler;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test fixture: the TOKA service source manifest — the model foundation (toka-01..16 + the reconciliatie
 * omissie + the scenario model-extensie) followed by the served rule files (toka-17..47), EXCLUDING toka-23
 * (its "A plus B" over two Datum-tijd operands is a resolve-time type error, §4.1 Tabel 2 — the exit-1 marker,
 * not a served rule). This is exactly the file set the pinned facts and the {@code toka.dienst.json} descriptor
 * are built over. Paths are resolved relative to the repo root ({@code ../examples} from the {@code service/}
 * module dir), or {@code examples} when run from the root.
 */
final class TokaBron {

    private TokaBron() {}

    static final Path REPO = repoRoot();
    static final Path DESCRIPTOR = REPO.resolve("examples/toka/toka.dienst.json");

    private static Path repoRoot() {
        Path here = Paths.get("").toAbsolutePath();
        if (Files.exists(here.resolve("examples/toka"))) return here;
        if (Files.exists(here.resolve("../examples/toka"))) return here.getParent();
        throw new IllegalStateException("kan de repo-root (examples/toka) niet vinden vanaf " + here);
    }

    /** The ordered service source files (foundation first, then the served rule files, no toka-23). */
    static List<Path> bronBestanden() {
        List<Path> uit = new ArrayList<>();
        Path toka = REPO.resolve("examples/toka");
        for (int i = 1; i <= 16; i++) {
            final int n = i;
            try (Stream<Path> st = Files.list(toka)) {
                st.filter(p -> p.getFileName().toString().matches("toka-" + String.format("%02d", n) + "-.*\\.rs"))
                  .sorted().forEach(uit::add);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        uit.add(REPO.resolve("examples/toka-reconciliatie/casus-omissies.rs"));
        uit.add(REPO.resolve("examples/toka-scenarios/scenario-model-extensie.rs"));
        // served rule files toka-17..47 except toka-23
        try (Stream<Path> st = Files.list(toka)) {
            st.filter(p -> p.getFileName().toString().matches("toka-(1[7-9]|[2-9][0-9])-.*\\.rs"))
              .filter(p -> !p.getFileName().toString().startsWith("toka-23-"))
              .sorted().forEach(uit::add);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return uit;
    }

    /** file-id (path string) → source text, in manifest order. */
    static Map<String, String> bronPerBestand() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Path p : bronBestanden()) {
            try { m.put(p.toString(), Files.readString(p)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return m;
    }

    static String descriptorJson() {
        try { return Files.readString(DESCRIPTOR); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** The resolved model over the full served TOKA source (no toka-23). */
    static RegelSpraakModel model() {
        StringBuilder bron = new StringBuilder();
        for (String tekst : bronPerBestand().values()) bron.append(tekst).append('\n');
        return Resolver.resolve(bron.toString());
    }

    /** The CHILD bean classloader for the served TOKA model — the DRL back-end's fact source, compiled in
     *  memory by {@link BeanCompiler} exactly as {@link BeslisserKern} does at startup. Mapper/relation tests
     *  build their beans through this loader (the pinned parent-CL fact classes are gone with the exec pivot). */
    static ClassLoader beanLoader() {
        return BeanCompiler.compileer(FactModelGenerator.genereer(model()));
    }
}
