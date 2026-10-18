package dev.uchar.regelspraak.toolchain.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file set whose union does NOT resolve must refuse startup carrying the resolver diagnostics (decision 4).
 * {@code toka-23-regel-bevestigingstijdstip-vlucht.rgs} is a RESOLVE-FOUT fixture (§4.1 Tabel 2: "A plus B" over
 * two Datum-tijd operands is a type error — its manifest line expects exit 1). The TOKA service file set
 * therefore EXCLUDES it ({@link TokaSource}); this test proves that INCLUDING it makes the whole union fail to
 * resolve and the core refuse startup with the diagnostics.
 */
class StartupResolveTest {

    @Test
    void a_non_resolving_source_set_refuses_start_with_diagnostics() throws IOException {
        // the served TOKA set PLUS the exit-1 resolve-fout fixture toka-23
        Map<String, String> source = new LinkedHashMap<>(TokaSource.sourcePerFile());
        var toka23 = TokaSource.REPO.resolve("examples/toka/toka-23-regel-bevestigingstijdstip-vlucht.rgs");
        source.put(toka23.toString(), Files.readString(toka23));

        ServiceError f = assertThrows(ServiceError.class, () -> DecisionCore.assemble(
                TokaSource.descriptorJson(), source, CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader()));
        // the failure is a resolve/model-validation failure carrying resolver diagnostics, not a marker
        assertTrue(f.getMessage().contains("resolve-fout") || f.getMessage().contains("descriptor/model-validatie"),
                f.getMessage());
        assertTrue(f.violations().stream().anyMatch(s -> s.kind() == Violation.Kind.MALFORMED), f.getMessage());
    }
}
