package dev.uchar.regelspraak.toolchain.quarkus;

import io.quarkus.test.junit.QuarkusTestProfile;

import dev.uchar.regelspraak.toolchain.service.TokaBron;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The @QuarkusTest config profile that points {@code DESCRIPTOR} / {@code BRONNEN_MANIFEST} / {@code ALLOW_MARKERS}
 * at the TOKA fixtures, so the app boots the SAME 29-entrypoint TOKA service the service module tests use. The
 * bron paths + descriptor are read straight off {@link TokaBron} (the service test-jar), and a small
 * {@code BRONNEN_MANIFEST} file is generated (one absolute {@code .rgs} path per line, in
 * {@code TokaBron.bronPerBestand()} order) so the {@link KernProducer}'s manifest reader has a real file to read —
 * exactly mirroring {@code service-entrypoint.sh}. Nothing here is a hand-typed rule/value.
 */
public class TokaTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Path descriptor = TokaBron.REPO.resolve("examples/toka/toka.dienst.json");
        Path manifest = schrijfManifest();
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("DESCRIPTOR", descriptor.toString());
        cfg.put("BRONNEN_MANIFEST", manifest.toString());
        cfg.put("ALLOW_MARKERS", "false");   // strict — the healthy TOKA service compiles clean
        return cfg;
    }

    /** Write the ordered bron .rgs paths (TokaBron's manifest order) to a temp file, one per line. */
    private static Path schrijfManifest() {
        try {
            Path manifest = Files.createTempFile("toka-bronnen-", ".manifest");
            // TokaBron.bronPerBestand() keys are the ordered bron path strings (LinkedHashMap, manifest order).
            Files.write(manifest, TokaBron.bronPerBestand().keySet());
            manifest.toFile().deleteOnExit();
            return manifest;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }
}
