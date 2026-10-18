package dev.uchar.regelspraak.toolchain.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CDI producer of the single {@link DecisionCore} the Quarkus adapter serves. It mirrors the shell/Helm config
 * contract exactly ({@code service-entrypoint.sh}): {@code DESCRIPTOR} points at the {@code .dienst.json},
 * {@code BRONNEN_MANIFEST} at a text file listing one bron {@code .rgs} path per line, {@code ALLOW_MARKERS}
 * toggles strict-vs-marker mode. Those env-var NAMES are shared with the shell launcher so existing Helm values
 * apply unchanged. {@code DETAIL=true} starts the core with the details of a session on request, as
 * {@code --detail} or {@code DETAIL=true} does for the JDK service; default off, and no part of the container
 * contract.
 *
 * <p><b>Fail-fast (Q2).</b> {@link DecisionCore#assemble} throws {@link ServiceError} when a rule cannot lower (e.g. a
 * marker rule in scope under strict). The producer RETHROWS it: CDI bean construction fails, the app never
 * finishes starting, and the readiness probe stays DOWN — the honest startup contract the CLI enforces with
 * exit 3. The core is REUSED UNCHANGED; this class marshals config only.
 */
@ApplicationScoped
public class CoreProducer {

    @ConfigProperty(name = "DESCRIPTOR")
    String descriptor;

    @ConfigProperty(name = "BRONNEN_MANIFEST")
    String bronnenManifest;

    @ConfigProperty(name = "ALLOW_MARKERS", defaultValue = "false")
    boolean allowMarkers;

    @ConfigProperty(name = "DETAIL", defaultValue = "false")
    boolean detail;

    /**
     * Build the {@link DecisionCore} once, shared (singleton) by the resource and the readiness health check.
     * Resolves the bron paths by reading the {@code BRONNEN_MANIFEST} file line-by-line (mirrors the shell
     * entrypoint's {@code cat "$BRONNEN_MANIFEST"} word-split), then calls
     * {@code DecisionCore.assemble(descriptorPath, bronPaths, config, TCCL)}.
     */
    @Produces
    @Singleton
    public DecisionCore decisionCore() {
        Path descriptorPath = Path.of(descriptor);
        List<Path> sources = readManifest(Path.of(bronnenManifest));
        CoreConfig config = allowMarkers ? CoreConfig.withMarkers() : CoreConfig.strict();
        if (detail) config = config.withDetail();
        try {
            // ServiceError (a RuntimeException) propagates out of bouw as-is → fail-fast startup (readiness DOWN).
            return DecisionCore.assemble(descriptorPath, sources, config,
                    Thread.currentThread().getContextClassLoader());
        } catch (IOException ioe) {
            throw new UncheckedIOException("kon de dienst niet bouwen: " + ioe.getMessage(), ioe);
        }
    }

    /** Read the bronnen-manifest into an ordered list of {@link Path}s — one non-blank, non-comment line each. */
    private static List<Path> readManifest(Path manifest) {
        List<Path> sink = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(manifest)) {
                for (String word : line.strip().split("\\s+")) {
                    String t = word.strip();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    sink.add(Path.of(t));
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException("kon bronnen-manifest niet lezen: " + manifest, ioe);
        }
        return sink;
    }
}
