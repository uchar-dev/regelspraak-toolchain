package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.ir.ResolvedScenario;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;

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
 * omissie + the scenario model-extensie) followed by the served rule files (toka-17..47), EXCLUDING the two
 * casus-quirk files that do not resolve and therefore cannot be served:
 *
 * <ul>
 *   <li>toka-23 — its "A plus B" over two Datum-tijd operands is a resolve-time type error, §4.1 Tabel 2;</li>
 *   <li>toka-45 — since besluit 16Y (SPEC_REV re-pin e87964a, 2026-08-29) the eenheid-identiteit typing types
 *       the unitless-Bedrag × km product as km, after which its {@code min} hits the §4 unit rule: the casus is
 *       dimensionally inconsistent as written, and the Onbekend degradation that used to mask that is gone.
 *       The same holdout is pinned in {@code examples/toka-scenarios/manifest} (exit-1 row), in CasusTest's
 *       quirk list, in TokaVuurTest's refusal witness, and in uchar's gate-11 holdout.</li>
 * </ul>
 *
 * Both are exit-1 markers, not served rules. This is exactly the file set the pinned facts and the
 * {@code toka.service.json} descriptor are built over. Paths are resolved relative to the repo root
 * ({@code ../examples} from the {@code service/} module dir), or {@code examples} when run from the root.
 */
public final class TokaSource {

    private TokaSource() {}

    public static final Path REPO = repoRoot();
    static final Path DESCRIPTOR = REPO.resolve("examples/toka/toka.dienst.json");

    private static Path repoRoot() {
        Path here = Paths.get("").toAbsolutePath();
        if (Files.exists(here.resolve("examples/toka"))) return here;
        if (Files.exists(here.resolve("../examples/toka"))) return here.getParent();
        throw new IllegalStateException("kan de repo-root (examples/toka) niet vinden vanaf " + here);
    }

    /** The ordered service source files (foundation first, then the served rule files, no toka-23/toka-45). */
    static List<Path> sourceFiles() {
        List<Path> sink = new ArrayList<>();
        Path toka = REPO.resolve("examples/toka");
        for (int i = 1; i <= 16; i++) {
            final int n = i;
            try (Stream<Path> st = Files.list(toka)) {
                st.filter(p -> p.getFileName().toString().matches("toka-" + String.format("%02d", n) + "-.*\\.rgs"))
                  .sorted().forEach(sink::add);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        sink.add(REPO.resolve("examples/toka-reconciliatie/casus-omissies.rgs"));
        sink.add(REPO.resolve("examples/toka-scenarios/scenario-model-extensie.rgs"));
        // served rule files toka-17..47 except the two quirk files that do not resolve (see the class javadoc)
        try (Stream<Path> st = Files.list(toka)) {
            st.filter(p -> p.getFileName().toString().matches("toka-(1[7-9]|[2-9][0-9])-.*\\.rgs"))
              .filter(p -> !p.getFileName().toString().startsWith("toka-23-"))
              .filter(p -> !p.getFileName().toString().startsWith("toka-45-"))
              .sorted().forEach(sink::add);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return sink;
    }

    /** file-id (path string) → source text, in manifest order. */
    public static Map<String, String> sourcePerFile() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Path p : sourceFiles()) {
            try { m.put(p.toString(), Files.readString(p)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return m;
    }

    public static String descriptorJson() {
        try { return Files.readString(DESCRIPTOR); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** The resolved model over the full served TOKA source (no toka-23/toka-45). */
    static RegelSpraakModel model() {
        StringBuilder source = new StringBuilder();
        for (String text : sourcePerFile().values()) source.append(text).append('\n');
        return Resolver.resolve(source.toString());
    }

    /** uchar's build of the declarations of the served TOKA model, a scope without forms, on the exec-model branch
     *  ({@link UcharBuild}), as {@link DecisionCore} builds an entry point at startup. The whole model does not build
     *  (two of its allocations write one attribute, WF-026), and the mapper reads no rule: mapper/relation tests read
     *  their requests with the input step of this build, whose bean model carries every type and fact type. */
    static DroolsBackend.Build build() {
        return UcharBuild.assemble(CaseEmitter.metScope(model(), new ResolvedScenario.Scope(List.of())),
                DroolsBackend.Branch.EXEC_MODEL);
    }
}
