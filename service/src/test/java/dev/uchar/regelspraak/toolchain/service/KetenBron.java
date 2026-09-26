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
 * entry points. The TOKA descriptor, {@link TokaBron} and the baked container image are untouched by it. The
 * source lives under {@code src/test/resources/keten/} rather than {@code examples/}, because
 * {@code CorpusVuurTest} partitions {@code examples/**.rgs} with exact bucket counts (the same reason the
 * overerving-port carried its wrakrecht source inline).
 *
 * <p>Both artifacts are read off the CLASSPATH, so this fixture needs no repo-root path juggling and works
 * identically from the module dir and from the root.
 */
public final class KetenBron {

    private KetenBron() {}

    static final String BRON = "keten/jutterswet-strandvondsten.rgs";
    static final String DESCRIPTOR = "keten/jutterswet.dienst.json";

    static String lees(String bron) {
        try (InputStream in = KetenBron.class.getClassLoader().getResourceAsStream(bron)) {
            if (in == null) throw new IllegalStateException("fixture ontbreekt op het klassenpad: " + bron);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** file-id → source text (one file). */
    static Map<String, String> bronPerBestand() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(BRON, lees(BRON));
        return m;
    }

    static String descriptorJson() { return lees(DESCRIPTOR); }

    /** The resolved model over the fixture source. */
    static RegelSpraakModel model() { return Resolver.resolve(lees(BRON)); }
}
