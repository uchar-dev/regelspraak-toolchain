package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test fixture: the OWN, rights-free Jutterswet-strandvondsten service — a two-level {@code is een} chain
 * ({@code Wrakstuk} is een {@code Strandvondst}) whose subtype inherits attributen, kenmerken AND temporal
 * members, plus two rules that compute on inherited members of a subtype instance.
 *
 * <p>It exists BESIDE the TOKA set and shares nothing with it: its own source file, its own descriptor, its own
 * entry points. The TOKA descriptor, {@link TokaSource} and the baked container image are untouched by it. The
 * source lives under {@code src/test/resources/keten/} rather than {@code examples/}, because
 * {@code CorpusVuurTest} partitions {@code examples/**.rgs} with exact bucket counts (the same reason the
 * overerving-port carried its wrakrecht source inline).
 *
 * <p>Both artifacts are read off the CLASSPATH, so this fixture needs no repo-root path juggling and works
 * identically from the module dir and from the root.
 */
public final class ChainSource {

    private ChainSource() {}

    static final String SOURCE = "chain/jutterswet-strandvondsten.rgs";
    static final String DESCRIPTOR = "chain/jutterswet.dienst.json";

    static String read(String source) {
        try (InputStream in = ChainSource.class.getClassLoader().getResourceAsStream(source)) {
            if (in == null) throw new IllegalStateException("fixture ontbreekt op het klassenpad: " + source);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** file-id → source text (one file). */
    static Map<String, String> sourcePerFile() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(SOURCE, read(SOURCE));
        return m;
    }

    static String descriptorJson() { return read(DESCRIPTOR); }

    /** The resolved model over the fixture source. */
    static RegelSpraakModel model() { return Resolver.resolve(read(SOURCE)); }
}
