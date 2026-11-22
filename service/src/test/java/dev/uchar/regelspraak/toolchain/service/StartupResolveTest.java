package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.toolchain.engine.UcharBuild;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file set whose union does NOT resolve must refuse startup carrying the resolver diagnostics (decision 4).
 * {@code toka-23-regel-bevestigingstijdstip-vlucht.rgs} is a RESOLVE-FOUT fixture (§4.1 Tabel 2: "A plus B" over
 * two Datum-tijd operands is a type error — its manifest line expects exit 1). The TOKA service file set
 * therefore EXCLUDES it ({@link TokaSource}); this test proves that INCLUDING it makes the whole union fail to
 * resolve and the core refuse startup with the diagnostics.
 *
 * <p>A name the resolver binds to no declaration resolves with a warning and leaves an {@code Unresolved} node in the
 * document, which uchar's executors fault on only when a request reaches it (WF-044). The start refuses it as a
 * fault in the source, from a source and from a document, also with markers allowed.
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

    /** Rule bijvoeglijk writes the een-form over the adjectival characteristic {@code minderjarig}, on line 6. */
    private static final String UNRESOLVED = """
        Objecttype de Passagier
            is minderjarig kenmerk (bijvoeglijk);
            is kind kenmerk;
        Regel bijvoeglijk
            geldig altijd
                Een passagier is kind indien hij een minderjarig is.
        """;

    private static final String UNRESOLVED_DESCRIPTOR = """
        { "service": {"name":"onopgelost","version":"1"},
          "entrypoints": [ { "name":"bijvoeglijk","path":"bijvoeglijk",
            "scope": {"ruleNames": ["bijvoeglijk"]},
            "input": ["Passagier"], "output": ["Passagier:kind"] } ],
          "parameters": {} }""";

    @Test
    void an_unresolved_name_in_a_source_refuses_start_also_with_markers_allowed() {
        for (CoreConfig config : List.of(CoreConfig.strict(), CoreConfig.withMarkers()))
            assertUnresolved(assertThrows(ServiceError.class, () -> DecisionCore.assemble(UNRESOLVED_DESCRIPTOR,
                    Map.of("onopgelost.rgs", UNRESOLVED), config, Thread.currentThread().getContextClassLoader())));
    }

    @Test
    void an_unresolved_node_in_a_document_refuses_start_also_with_markers_allowed() {
        String document = UcharBuild.document(Resolver.resolve(UNRESOLVED));
        for (CoreConfig config : List.of(CoreConfig.strict(), CoreConfig.withMarkers()))
            assertUnresolved(assertThrows(ServiceError.class, () -> DecisionCore.buildFromDocument(
                    UNRESOLVED_DESCRIPTOR, document, config, Thread.currentThread().getContextClassLoader(), null)));
    }

    /** The eleven test is a form the IR carries without a norm (RGS-092): no fault in the source but a form uchar does
     *  not carry, so the entry point is a marker, as {@code check} gives exit 3 for it — strict a MARKER violation
     *  (exit 3), with markers allowed a start whose request gets the 503. */
    private static final String WITHOUT_NORM = """
        Objecttype de Jutter (mv: Jutters)
            de naam Tekst;
            is waardevol kenmerk (bijvoeglijk);

        Regel elf
            geldig altijd
                Een Jutter is waardevol indien zijn naam aan de elfproef voldoet.
        """;

    private static final String WITHOUT_NORM_DESCRIPTOR = """
        { "service": {"name":"e","version":"1"},
          "entrypoints": [ { "name":"b","path":"b", "scope": {"ruleNames": ["elf"]},
            "input": [], "output": ["Jutter:waardevol"] } ] }""";

    @Test
    void a_form_without_norm_makes_the_entry_point_a_marker() {
        String document = UcharBuild.document(Resolver.resolve(WITHOUT_NORM));
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (boolean fromDocument : List.of(false, true)) {
            ServiceError f = assertThrows(ServiceError.class, () -> {
                if (fromDocument)
                    DecisionCore.buildFromDocument(WITHOUT_NORM_DESCRIPTOR, document, CoreConfig.strict(), loader, null);
                else
                    DecisionCore.assemble(WITHOUT_NORM_DESCRIPTOR, Map.of("e.rgs", WITHOUT_NORM), CoreConfig.strict(),
                            loader);
            });
            assertTrue(f.violations().size() == 1 && f.violations().get(0).kind() == Violation.Kind.MARKER
                    && f.violations().get(0).message().contains("vorm zonder norm"), f.getMessage());
            DecisionCore core = fromDocument
                    ? DecisionCore.buildFromDocument(WITHOUT_NORM_DESCRIPTOR, document, CoreConfig.withMarkers(), loader,
                            null)
                    : DecisionCore.assemble(WITHOUT_NORM_DESCRIPTOR, Map.of("e.rgs", WITHOUT_NORM),
                            CoreConfig.withMarkers(), loader);
            assertFalse(core.entrypoint("b").executable(), fromDocument + "");
            assertThrows(DecisionCore.NonExecutable.class, () -> core.process("b",
                    DescriptorLoader.MAPPER.readTree("{\"instances\": []}")));
        }
    }

    private static void assertUnresolved(ServiceError f) {
        assertTrue(f.violations().size() == 1 && f.violations().get(0).kind() == Violation.Kind.MALFORMED
                && f.violations().get(0).message().equals(
                        "onopgeloste naam 'hij een minderjarig is' in de regel 'bijvoeglijk', bronregel 6"),
                f.getMessage() + " / " + f.violations());
    }
}
