package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.Violation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The CLI entry point for the decision service. It builds a {@link DecisionCore} from a descriptor + the
 * service's {@code .rgs} sources, or one {@code uchar-ir-v1} document ({@code .json}), then serves it over
 * {@link ServiceHttp}. {@code --poort 0} binds a free port; the serving line names the port bound. {@code --host}
 * picks the address to bind (default {@code 0.0.0.0}, every interface); a local caller that binds a free port
 * passes {@code 127.0.0.1}, because a port free on every interface can still be bound on the loopback address
 * alone by another process, which then answers on it. {@code --detail}, or {@code DETAIL=true} in the environment,
 * starts the core with the details of a session on request ({@link CoreConfig#detail()}); default off, as
 * {@code PLAYGROUND}, since they show every intermediate value.
 *
 * <pre>{@code
 *   ServiceMain <descriptor.service.json> <bron.rgs...> | <document.json> [--allow-markers] [--detail] [--host H]
 *              [--poort N]
 * }</pre>
 *
 * <h2>Startup / exit discipline (the exit-3 MARKER discipline at the service boundary)</h2>
 * <ul>
 *   <li>A {@link ServiceError} carrying ONLY {@link Violation.Kind#MARKER} schendingen (an entry point uchar does
 *       not carry — a refusal of its emitter, loader or backend, a construct the exec-model branch does not lower
 *       among them, or a scoped rule that carries no form — without {@code --allow-markers}) → each printed as
 *       {@code MARKER: …} with uchar's sentence, and exit <b>3</b>.</li>
 *   <li>Any OTHER startup failure (unknown regel/objecttype, un-resolvable source, incomplete scope, bad
 *       descriptor) → print the schendingen and exit <b>1</b>.</li>
 *   <li>Success → print one serving line per entry point ({@code POST /<pad>}) plus {@code GET /info}, then
 *       block. With {@code --allow-markers} the same descriptor starts, {@code /info} lists the markers, and a
 *       request to an entry point with a marker is refused with 503: uchar built no KieBase for it.</li>
 * </ul>
 * The marker-vs-other classification lives in {@link #exitCodeFor(ServiceError)} so it is unit-testable without
 * forking a JVM.
 */
public final class ServiceMain {

    private ServiceMain() {}

    /** Parsed CLI arguments. */
    record Args(Path descriptor, List<Path> sources, boolean allowMarkers, boolean detail, String host, int port) {}

    /** Exit code 3: the startup failure was purely marker schendingen (needs {@code --allow-markers}). */
    static final int EXIT_MARKER = 3;
    /** Exit code 1: any other startup failure. */
    static final int EXIT_OTHER = 1;

    public static void main(String[] rawArgs) {
        Args args;
        try {
            args = parseArgs(rawArgs);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("gebruik: DienstMain <descriptor.service.json> <bron.rgs...> | <document.json> "
                    + "[--allow-markers] [--detail] [--host H] [--poort N]");
            System.exit(2);
            return;
        }

        CoreConfig config = args.allowMarkers() ? CoreConfig.withMarkers() : CoreConfig.strict();
        if (args.detail() || "true".equalsIgnoreCase(System.getenv("DETAIL"))) config = config.withDetail();
        DecisionCore core;
        try {
            core = DecisionCore.assemble(args.descriptor(), args.sources(), config,
                    Thread.currentThread().getContextClassLoader());
        } catch (ServiceError error) {
            int code = exitCodeFor(error);
            if (code == EXIT_MARKER) printMarkers(error);
            else printViolations(error);
            System.exit(code);
            return;
        } catch (IOException ioe) {
            System.err.println("kon de dienst niet bouwen: " + ioe.getMessage());
            System.exit(EXIT_OTHER);
            return;
        }

        printPeriodWarnings(core);
        try {
            ServiceHttp service = ServiceHttp.bind(core, args.host(), args.port()).start();
            printServingLines(core, service.port());
            // block forever — the server runs on its own executor threads
            Thread.currentThread().join();
        } catch (IOException | InterruptedException e) {
            System.err.println("kon de HTTP-server niet starten: " + e.getMessage());
            System.exit(EXIT_OTHER);
        }
    }

    /**
     * Classify a startup {@link ServiceError} into an exit code: {@link #EXIT_MARKER} (3) when EVERY schending is a
     * {@link Violation.Kind#MARKER} (and there is at least one — the honest fail-fast that {@code --allow-markers}
     * would clear), else {@link #EXIT_OTHER} (1). Extracted so a test can pin the classification directly without
     * forking a JVM.
     */
    static int exitCodeFor(ServiceError error) {
        List<Violation> ss = error.violations();
        if (!ss.isEmpty() && ss.stream().allMatch(s -> s.kind() == Violation.Kind.MARKER))
            return EXIT_MARKER;
        return EXIT_OTHER;
    }

    /** {@code MARKER: regel '<naam>' in entrypoint '<ep>'} per marker schending. The naam+ep are recovered from
     *  the schending's message and field ({@code entrypoint:<pad>}); the message already names both. */
    static void printMarkers(ServiceError error) {
        for (Violation s : error.violations()) {
            if (s.kind() != Violation.Kind.MARKER) continue;
            System.err.println("MARKER: " + s.message());
        }
    }

    static void printViolations(ServiceError error) {
        System.err.println(error.getMessage());
    }

    /** Log every regel-geldigheid × parameter-geldigheid coverage gap (gate G3) as a {@code WAARSCHUWING: …}
     *  line at startup — the CLI-visibility of the same period-coverage story /info serves. Silent when no gap. */
    static void printPeriodWarnings(DecisionCore core) {
        for (String w : core.periodWarnings())
            System.err.println("WAARSCHUWING: " + w);
    }

    static void printServingLines(DecisionCore core, int port) {
        System.out.println("dienst '" + name(core) + "' luistert op poort " + port + ":");
        for (DecisionCore.Entrypoint ep : core.entrypoints().values())
            System.out.println("  POST /" + ep.descriptor().path() + "   (" + ep.descriptor().name() + ")"
                    + (ep.executable() ? "" : "   niet uitvoerbaar: " + ep.refusal()));
        System.out.println("  GET  /info");
    }

    private static String name(DecisionCore core) {
        return core.descriptor().service() == null ? "?" : core.descriptor().service().name();
    }

    /** Parse {@code <descriptor> <bron...> [--allow-markers] [--detail] [--host H] [--poort N]}. */
    static Args parseArgs(String[] raw) {
        Path descriptor = null;
        List<Path> sources = new ArrayList<>();
        boolean allowMarkers = false;
        boolean detail = false;
        String host = "0.0.0.0";
        int port = 8080;
        for (int i = 0; i < raw.length; i++) {
            String a = raw[i];
            if (a.equals("--allow-markers")) {
                allowMarkers = true;
            } else if (a.equals("--detail")) {
                detail = true;
            } else if (a.equals("--host")) {
                if (i + 1 >= raw.length) throw new IllegalArgumentException("--host verwacht een adres");
                host = raw[++i];
            } else if (a.equals("--poort")) {
                if (i + 1 >= raw.length) throw new IllegalArgumentException("--poort verwacht een getal");
                try { port = Integer.parseInt(raw[++i]); }
                catch (NumberFormatException nfe) { throw new IllegalArgumentException("--poort verwacht een getal, kreeg '" + raw[i] + "'"); }
            } else if (a.startsWith("--")) {
                throw new IllegalArgumentException("onbekende optie: " + a);
            } else if (descriptor == null) {
                descriptor = Path.of(a);
            } else {
                sources.add(Path.of(a));
            }
        }
        if (descriptor == null) throw new IllegalArgumentException("geen descriptor (.service.json) opgegeven");
        if (sources.isEmpty())
            throw new IllegalArgumentException("geen bron opgegeven: .rgs-bestanden of één uchar-ir-v1-document");
        return new Args(descriptor, sources, allowMarkers, detail, host, port);
    }
}
