package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.schema.FactModelGenerator;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles the {@link FactModelGenerator}-emitted bean sources IN MEMORY (javax.tools) and serves the resulting
 * classes from a child-first classloader — the engine-side, reusable form of the {@code FactModelGeneratorTest}/
 * {@code TokaVuurTest} test harness (test code cannot be called from {@code engine/main}). The {@link ScenarioRunner}
 * compiles the bundle's beans ONCE (they are the same for every scenario in a run) and hands the loader to the
 * exec-model transpiler's {@code build(model, classLoader)} passthrough so the compiled rules bind against these
 * beans, and to the {@link io.github.yhavinga.regelspraak.schema.ContractValidator} so the contract is checked against them.
 *
 * <p>CHILD-FIRST for the classes it produced: the generated beans live in {@link FactModelGenerator#PAKKET}
 * (a shared package name), so a parent-first loader could let an unrelated same-named class on the parent shadow
 * the generated one. The rules and the runner must run against the GENERATED model.
 */
public final class BeanCompiler {
    private BeanCompiler() {}

    /**
     * Content-keyed cache of compiled bean bundles for the lifetime of the JVM: a SHA-256 over the (className →
     * source) map maps to the loader serving THAT exact bytecode. The generated beans are immutable classes and
     * the loader is stateless, so many callers that regenerate the SAME fact model (a mini-model shared across
     * dozens of differential/scenario cases, or the 32 golden manifest lines that share one TOKA foundation) get
     * ONE javac invocation + ONE loader instead of recompiling identical sources every time. A distinct model
     * (different beans) keys to a distinct entry, so no two models are ever conflated.
     */
    private static final Map<String, ClassLoader> CACHE = new ConcurrentHashMap<>();

    /** Compile {@code bronnen} (simple class name → Java source) in memory; return a loader serving them.
     *  Identical {@code bronnen} within one JVM are compiled ONCE (content-keyed cache) and share the loader. */
    public static ClassLoader compileer(Map<String, String> bronnen) {
        return CACHE.computeIfAbsent(sleutel(bronnen), k -> compileerOngecached(bronnen));
    }

    /** SHA-256 over the sorted (className → source) entries — the content key that decides cache identity. */
    private static String sleutel(Map<String, String> bronnen) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : new TreeMap<>(bronnen).entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest()) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 niet beschikbaar", e);
        }
    }

    private static ClassLoader compileerOngecached(Map<String, String> bronnen) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null)
            throw new IllegalStateException("geen JDK-compiler beschikbaar (draai op een JDK, niet een JRE)");
        Map<String, ByteArrayOutputStream> klassen = new HashMap<>();
        List<JavaFileObject> eenheden = new ArrayList<>();
        for (Map.Entry<String, String> e : bronnen.entrySet())
            eenheden.add(new SimpleJavaFileObject(
                    URI.create("string:///" + FactModelGenerator.PAKKET.replace('.', '/') + "/" + e.getKey() + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return e.getValue(); }
            });
        // try-with-resources: the file manager holds OS handles on the platform/classpath jars. The returned
        // classloader keeps only the compiled BYTES (the `klassen` map), so closing the manager after the compile
        // completes is safe — and REQUIRED, else a per-run compile (e.g. an orakel harness calling the runner ≥40×)
        // leaks a StandardJavaFileManager + its file descriptors on every invocation.
        StringBuilder diag = new StringBuilder();
        try (StandardJavaFileManager std = javac.getStandardFileManager(null, null, null);
             JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
                 @Override public JavaFileObject getJavaFileForOutput(Location loc, String naam, JavaFileObject.Kind kind, FileObject bron) {
                     ByteArrayOutputStream uit = new ByteArrayOutputStream();
                     klassen.put(naam, uit);
                     return new SimpleJavaFileObject(URI.create("mem:///" + naam.replace('.', '/') + ".class"), kind) {
                         @Override public OutputStream openOutputStream() { return uit; }
                     };
                 }
             }) {
            boolean ok = javac.getTask(null, fm, d -> diag.append(d).append('\n'), null, null, eenheden).call();
            if (!ok)
                throw new IllegalStateException("gegenereerde fact-beans compileren niet:\n" + diag);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("kon de in-memory fact-compiler niet sluiten", e);
        }
        return new ClassLoader(BeanCompiler.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String naam, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(naam)) {
                    Class<?> c = findLoadedClass(naam);
                    if (c == null && klassen.containsKey(naam)) c = findClass(naam);
                    if (c == null) return super.loadClass(naam, resolve);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            @Override protected Class<?> findClass(String naam) throws ClassNotFoundException {
                ByteArrayOutputStream b = klassen.get(naam);
                if (b == null) throw new ClassNotFoundException(naam);
                byte[] bytes = b.toByteArray();
                return defineClass(naam, bytes, 0, bytes.length);
            }
        };
    }
}
