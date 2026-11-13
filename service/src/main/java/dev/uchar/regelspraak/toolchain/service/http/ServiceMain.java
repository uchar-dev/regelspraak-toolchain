package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.toolchain.service.DecisionCore;
import dev.uchar.regelspraak.toolchain.service.ServiceError;
import dev.uchar.regelspraak.toolchain.service.CoreConfig;
import dev.uchar.regelspraak.toolchain.service.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The CLI entry point for the decision service. It builds a {@link DecisionCore} from a descriptor + the
 * service's {@code .rgs} sources, or one {@code uchar-ir-v1} document ({@code .json}), then serves it over
 * {@link ServiceHttp}. {@code --port 0} binds a free port; the serving line names the port bound. {@code --host}
 * picks the address to bind (default {@code 0.0.0.0}, every interface); a local caller that binds a free port
 * passes {@code 127.0.0.1}, because a port free on every interface can still be bound on the loopback address
 * alone by another process, which then answers on it. {@code --detail}, or {@code DETAIL=true} in the environment,
 * starts the core with the details of a session on request ({@link CoreConfig#detail()}); default off, as
 * {@code PLAYGROUND}, since they show every intermediate value.
 *
 * <pre>{@code
 *   ServiceMain <descriptor.service.json> <source.rgs...> | <document.json> [--allow-markers] [--detail] [--host H]
 *              [--port N]
 * }</pre>
 *
 * <h2>Startup / exit discipline (the exit-3 MARKER discipline at the service boundary)</h2>
 * <ul>
 *   <li>A {@link ServiceError} carrying only {@link Violation.Kind#MARKER} violations (an entry point uchar does
 *       not carry — a refusal of its emitter, loader or backend, a construct the exec-model branch does not lower
 *       among them, a form without a norm, or a scoped rule that carries no form — without {@code --allow-markers})
 *       → each printed as {@code MARKER: …} with uchar's sentence, and exit <b>3</b>, as {@code check} gives.</li>
 *   <li>Any other startup failure (unknown rule or object type, a source that does not resolve, a name the resolver
 *       bound to no declaration, an incomplete scope, a bad descriptor) → print the violations and exit
 *       <b>1</b>.</li>
 *   <li>Success → print one serving line per entry point ({@code POST /<pad>}) plus {@code GET /info} and
 *       {@code GET /openapi}, then block. With {@code --allow-markers} the same descriptor starts, {@code /info} lists the markers, and a
 *       request to an entry point with a marker is refused with 503: uchar built no KieBase for it.</li>
 * </ul>
 * The marker-vs-other classification lives in {@link #exitCodeFor(ServiceError)} so it is unit-testable without
 * forking a JVM.
 *
 * <h2>The OpenAPI document without a running service</h2>
 * <pre>{@code
 *   ServiceMain --openapi <out.json> [--vocabulary <name>] <descriptor.service.json> <source.rgs...> | <document.json>
 * }</pre>
 * builds the core as a start does and writes the bytes {@code GET /openapi} sends ({@link ServiceHttp#openapi}) to
 * {@code out.json}. The core is built with markers allowed: the document describes the descriptor's entry points
 * whether uchar carries them or not, so it is the one a service started with {@code --allow-markers} serves, and a
 * service started without it serves the same one or does not start. A start that fails exits as above; an unknown
 * vocabulary exits <b>2</b>, a wrong invocation.
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
        dev.uchar.regelspraak.toolchain.engine.MessageLanguage.apply(null);
        if (rawArgs.length > 0 && rawArgs[0].equals("--openapi")) {
            System.exit(openapi(java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length)));
            return;
        }
        Args args;
        try {
            args = parseArgs(rawArgs);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: ServiceMain <descriptor.service.json> <source.rgs...> | <document.json> "
                    + "[--allow-markers] [--detail] [--host H] [--port N]");
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

        printStartWarnings(core);
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

    /** {@code MARKER: regel '<name>' in entrypoint '<ep>'} per marker schending. The naam+ep are recovered from
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

    /** Log every warning of the start ({@link DecisionCore#startWarnings}: a regel-geldigheid × parameter-geldigheid
     *  coverage gap, a descriptor without entrypoints) as a {@code WAARSCHUWING: …} line on stderr. Silent when there
     *  is none. */
    static void printStartWarnings(DecisionCore core) {
        for (String w : core.startWarnings())
            System.err.println("WAARSCHUWING: " + w);
    }

    static void printServingLines(DecisionCore core, int port) {
        System.out.println("dienst '" + name(core) + "' luistert op poort " + port + ":");
        for (DecisionCore.Entrypoint ep : core.entrypoints().values())
            System.out.println("  POST /" + ep.descriptor().path() + "   (" + ep.descriptor().name() + ")"
                    + (ep.executable() ? "" : "   niet uitvoerbaar: " + ep.refusal()));
        System.out.println("  GET  /info");
        System.out.println("  GET  /openapi");
    }

    private static String name(DecisionCore core) {
        return core.descriptor().service() == null ? "?" : core.descriptor().service().name();
    }

    /** Parsed arguments of {@code --openapi}. */
    record OpenapiArgs(Path out, String vocabulary, Path descriptor, List<Path> sources) {}

    /** Exit code 2: a wrong invocation, an unknown vocabulary among them. */
    static final int EXIT_USAGE = 2;

    /** {@code --openapi}: write the document {@code GET /openapi} sends; returns the exit code. */
    static int openapi(String[] raw) {
        OpenapiArgs args;
        try {
            args = parseOpenapiArgs(raw);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: ServiceMain --openapi <out.json> [--vocabulary <naam>] <descriptor.service.json>"
                    + " <source.rgs...> | <document.json>");
            return EXIT_USAGE;
        }
        DecisionCore core;
        try {
            core = DecisionCore.assemble(args.descriptor(), args.sources(), CoreConfig.withMarkers(),
                    Thread.currentThread().getContextClassLoader());
        } catch (ServiceError error) {
            int code = exitCodeFor(error);
            if (code == EXIT_MARKER) printMarkers(error);
            else printViolations(error);
            return code;
        } catch (IOException ioe) {
            System.err.println("kon de dienst niet bouwen: " + ioe.getMessage());
            return EXIT_USAGE;
        }
        printStartWarnings(core);
        ServiceHttp.Document document = ServiceHttp.openapi(core, args.vocabulary());
        if (document.status() != 200) {
            System.err.println(new String(document.body(), java.nio.charset.StandardCharsets.UTF_8));
            return EXIT_USAGE;
        }
        try {
            Files.write(args.out(), document.body());
        } catch (IOException ioe) {
            System.err.println("kon " + args.out() + " niet schrijven: " + ioe.getMessage());
            return EXIT_USAGE;
        }
        return 0;
    }

    /** Parse {@code <out.json> [--vocabulary V] <descriptor> <source...>}. */
    static OpenapiArgs parseOpenapiArgs(String[] raw) {
        Path out = null;
        String vocabulary = null;
        Path descriptor = null;
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String a = raw[i];
            if (a.equals("--vocabulary")) {
                if (i + 1 >= raw.length) throw new IllegalArgumentException("--vocabulary verwacht een naam");
                vocabulary = raw[++i];
            } else if (a.startsWith("--")) {
                throw new IllegalArgumentException("onbekende optie: " + a);
            } else if (out == null) {
                out = Path.of(a);
            } else if (descriptor == null) {
                descriptor = Path.of(a);
            } else {
                sources.add(Path.of(a));
            }
        }
        if (out == null) throw new IllegalArgumentException("geen uitvoerbestand (.json) opgegeven");
        if (descriptor == null) throw new IllegalArgumentException("geen descriptor (.service.json) opgegeven");
        if (sources.isEmpty())
            throw new IllegalArgumentException("geen bron opgegeven: .rgs-bestanden of één uchar-ir-v1-document");
        return new OpenapiArgs(out, vocabulary, descriptor, sources);
    }

    /** Parse {@code <descriptor> <source...> [--allow-markers] [--detail] [--host H] [--port N]}. */
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
            } else if (a.equals("--port")) {
                if (i + 1 >= raw.length) throw new IllegalArgumentException("--port verwacht een getal");
                try { port = Integer.parseInt(raw[++i]); }
                catch (NumberFormatException nfe) { throw new IllegalArgumentException("--port verwacht een getal, kreeg '" + raw[i] + "'"); }
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
