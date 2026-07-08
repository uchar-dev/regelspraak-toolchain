package io.github.yhavinga.regelspraak.service.http;

import io.github.yhavinga.regelspraak.service.BeslisserKern;
import io.github.yhavinga.regelspraak.service.DienstFout;
import io.github.yhavinga.regelspraak.service.KernConfig;
import io.github.yhavinga.regelspraak.service.Schending;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The CLI entry point for the decision service. It builds a {@link BeslisserKern} from a descriptor + the
 * service's {@code .rgs} sources, then serves it over {@link DienstHttp}.
 *
 * <pre>{@code
 *   DienstMain <descriptor.dienst.json> <bron.rgs...> [--allow-markers] [--poort N]
 * }</pre>
 *
 * <h2>Startup / exit discipline (the exit-3 MARKER discipline at the service boundary)</h2>
 * <ul>
 *   <li>A {@link DienstFout} carrying ONLY {@link Schending.Soort#MARKER} schendingen (an in-scope rule the DRL
 *       back-end could not lower, without {@code --allow-markers}) → each printed as
 *       {@code MARKER: regel '<naam>' in entrypoint '<ep>'} and exit <b>3</b>.</li>
 *   <li>Any OTHER startup failure (unknown regel/objecttype, un-resolvable source, un-compilable DRL, bad
 *       descriptor) → print the schendingen and exit <b>1</b>.</li>
 *   <li>Success → print one serving line per entry point ({@code POST /<pad>}) plus {@code GET /info}, then
 *       block. With {@code --allow-markers} the same descriptor starts and {@code /info} lists the markers.</li>
 * </ul>
 * The marker-vs-other classification lives in {@link #exitCodeVoor(DienstFout)} so it is unit-testable without
 * forking a JVM.
 */
public final class DienstMain {

    private DienstMain() {}

    /** Parsed CLI arguments. */
    record Args(Path descriptor, List<Path> bronnen, boolean allowMarkers, int poort) {}

    /** Exit code 3: the startup failure was purely marker schendingen (needs {@code --allow-markers}). */
    static final int EXIT_MARKER = 3;
    /** Exit code 1: any other startup failure. */
    static final int EXIT_ANDER = 1;

    public static void main(String[] rawArgs) {
        Args args;
        try {
            args = parseArgs(rawArgs);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("gebruik: DienstMain <descriptor.dienst.json> <bron.rgs...> "
                    + "[--allow-markers] [--poort N]");
            System.exit(2);
            return;
        }

        KernConfig config = args.allowMarkers() ? KernConfig.metMarkers() : KernConfig.strikt();
        BeslisserKern kern;
        try {
            kern = BeslisserKern.bouw(args.descriptor(), args.bronnen(), config,
                    Thread.currentThread().getContextClassLoader());
        } catch (DienstFout fout) {
            int code = exitCodeVoor(fout);
            if (code == EXIT_MARKER) drukMarkers(fout);
            else drukSchendingen(fout);
            System.exit(code);
            return;
        } catch (IOException ioe) {
            System.err.println("kon de dienst niet bouwen: " + ioe.getMessage());
            System.exit(EXIT_ANDER);
            return;
        }

        drukPeriodeWaarschuwingen(kern);
        try {
            DienstHttp dienst = DienstHttp.bind(kern, "0.0.0.0", args.poort()).start();
            drukServingLijnen(kern, dienst.poort());
            // block forever — the server runs on its own executor threads
            Thread.currentThread().join();
        } catch (IOException | InterruptedException e) {
            System.err.println("kon de HTTP-server niet starten: " + e.getMessage());
            System.exit(EXIT_ANDER);
        }
    }

    /**
     * Classify a startup {@link DienstFout} into an exit code: {@link #EXIT_MARKER} (3) when EVERY schending is a
     * {@link Schending.Soort#MARKER} (and there is at least one — the honest fail-fast that {@code --allow-markers}
     * would clear), else {@link #EXIT_ANDER} (1). Extracted so a test can pin the classification directly without
     * forking a JVM.
     */
    static int exitCodeVoor(DienstFout fout) {
        List<Schending> ss = fout.schendingen();
        if (!ss.isEmpty() && ss.stream().allMatch(s -> s.soort() == Schending.Soort.MARKER))
            return EXIT_MARKER;
        return EXIT_ANDER;
    }

    /** {@code MARKER: regel '<naam>' in entrypoint '<ep>'} per marker schending. The naam+ep are recovered from
     *  the schending's message and field ({@code entrypoint:<pad>}); the message already names both. */
    static void drukMarkers(DienstFout fout) {
        for (Schending s : fout.schendingen()) {
            if (s.soort() != Schending.Soort.MARKER) continue;
            System.err.println("MARKER: " + s.bericht());
        }
    }

    static void drukSchendingen(DienstFout fout) {
        System.err.println(fout.getMessage());
    }

    /** Log every regel-geldigheid × parameter-geldigheid coverage gap (gate G3) as a {@code WAARSCHUWING: …}
     *  line at startup — the CLI-visibility of the same period-coverage story /info serves. Silent when no gap. */
    static void drukPeriodeWaarschuwingen(BeslisserKern kern) {
        for (String w : kern.periodeWaarschuwingen())
            System.err.println("WAARSCHUWING: " + w);
    }

    static void drukServingLijnen(BeslisserKern kern, int poort) {
        System.out.println("dienst '" + naam(kern) + "' luistert op poort " + poort + ":");
        for (BeslisserKern.Entrypoint ep : kern.entrypoints().values())
            System.out.println("  POST /" + ep.descriptor().pad() + "   (" + ep.descriptor().naam() + ")");
        System.out.println("  GET  /info");
    }

    private static String naam(BeslisserKern kern) {
        return kern.descriptor().dienst() == null ? "?" : kern.descriptor().dienst().naam();
    }

    /** Parse {@code <descriptor> <bron.rgs...> [--allow-markers] [--poort N]}. */
    static Args parseArgs(String[] raw) {
        Path descriptor = null;
        List<Path> bronnen = new ArrayList<>();
        boolean allowMarkers = false;
        int poort = 8080;
        for (int i = 0; i < raw.length; i++) {
            String a = raw[i];
            if (a.equals("--allow-markers")) {
                allowMarkers = true;
            } else if (a.equals("--poort")) {
                if (i + 1 >= raw.length) throw new IllegalArgumentException("--poort verwacht een getal");
                try { poort = Integer.parseInt(raw[++i]); }
                catch (NumberFormatException nfe) { throw new IllegalArgumentException("--poort verwacht een getal, kreeg '" + raw[i] + "'"); }
            } else if (a.startsWith("--")) {
                throw new IllegalArgumentException("onbekende optie: " + a);
            } else if (descriptor == null) {
                descriptor = Path.of(a);
            } else {
                bronnen.add(Path.of(a));
            }
        }
        if (descriptor == null) throw new IllegalArgumentException("geen descriptor (.dienst.json) opgegeven");
        if (bronnen.isEmpty()) throw new IllegalArgumentException("geen .rgs bronbestanden opgegeven");
        return new Args(descriptor, bronnen, allowMarkers, poort);
    }
}
