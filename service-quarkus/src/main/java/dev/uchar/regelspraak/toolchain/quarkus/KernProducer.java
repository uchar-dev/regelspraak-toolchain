package dev.uchar.regelspraak.toolchain.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import dev.uchar.regelspraak.toolchain.service.BeslisserKern;
import dev.uchar.regelspraak.toolchain.service.DienstFout;
import dev.uchar.regelspraak.toolchain.service.KernConfig;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CDI producer of the single {@link BeslisserKern} the Quarkus adapter serves. It mirrors the shell/Helm config
 * contract exactly ({@code service-entrypoint.sh}): {@code DESCRIPTOR} points at the {@code .dienst.json},
 * {@code BRONNEN_MANIFEST} at a text file listing one bron {@code .rgs} path per line, {@code ALLOW_MARKERS}
 * toggles strict-vs-marker mode. Those env-var NAMES are shared with the shell launcher so existing Helm values
 * apply unchanged.
 *
 * <p><b>Fail-fast (Q2).</b> {@link BeslisserKern#bouw} throws {@link DienstFout} when a rule cannot lower (e.g. a
 * marker rule in scope under strict). The producer RETHROWS it: CDI bean construction fails, the app never
 * finishes starting, and the readiness probe stays DOWN — the honest startup contract the CLI enforces with
 * exit 3. The core is REUSED UNCHANGED; this class marshals config only.
 */
@ApplicationScoped
public class KernProducer {

    @ConfigProperty(name = "DESCRIPTOR")
    String descriptor;

    @ConfigProperty(name = "BRONNEN_MANIFEST")
    String bronnenManifest;

    @ConfigProperty(name = "ALLOW_MARKERS", defaultValue = "false")
    boolean allowMarkers;

    /**
     * Build the {@link BeslisserKern} once, shared (singleton) by the resource and the readiness health check.
     * Resolves the bron paths by reading the {@code BRONNEN_MANIFEST} file line-by-line (mirrors the shell
     * entrypoint's {@code cat "$BRONNEN_MANIFEST"} word-split), then calls
     * {@code BeslisserKern.bouw(descriptorPath, bronPaths, config, TCCL)}.
     */
    @Produces
    @Singleton
    public BeslisserKern beslisserKern() {
        Path descriptorPad = Path.of(descriptor);
        List<Path> bronnen = leesManifest(Path.of(bronnenManifest));
        KernConfig config = allowMarkers ? KernConfig.metMarkers() : KernConfig.strikt();
        try {
            // DienstFout (a RuntimeException) propagates out of bouw as-is → fail-fast startup (readiness DOWN).
            return BeslisserKern.bouw(descriptorPad, bronnen, config,
                    Thread.currentThread().getContextClassLoader());
        } catch (IOException ioe) {
            throw new UncheckedIOException("kon de dienst niet bouwen: " + ioe.getMessage(), ioe);
        }
    }

    /** Read the bronnen-manifest into an ordered list of {@link Path}s — one non-blank, non-comment line each. */
    private static List<Path> leesManifest(Path manifest) {
        List<Path> uit = new ArrayList<>();
        try {
            for (String regel : Files.readAllLines(manifest)) {
                for (String woord : regel.strip().split("\\s+")) {
                    String t = woord.strip();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    uit.add(Path.of(t));
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException("kon bronnen-manifest niet lezen: " + manifest, ioe);
        }
        return uit;
    }
}
