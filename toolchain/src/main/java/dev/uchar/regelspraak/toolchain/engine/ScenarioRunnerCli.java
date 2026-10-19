package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResult;
import dev.uchar.regelspraak.engine.ScenarioResult.CoverageRule;
import dev.uchar.regelspraak.engine.ScenarioResult.Status;
import dev.uchar.regelspraak.engine.ScenarioResult.ExpectationResult;
import dev.uchar.regelspraak.engine.ScenarioResult.Firing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CLI for the {@link ScenarioRunner}. Usage:
 *
 * <pre>{@code
 *   dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli [--trace] [--explanation] [--vocabulary <naam>] <file.rgs> [<file.rgs> ...]
 *   dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli [--trace] --manifest <manifest> [--exclusions <lijst>]
 * }</pre>
 *
 * Multiple files are CONCATENATED in argument order (objectmodel + rules + scenario file). Per scenario one line
 * PASS/FAIL/MARKER; a FAIL prints per expectation its position in the scenario with verwacht/werkelijk, the trace
 * and the coverage lines; {@code --trace} prints the trace even for a PASS; {@code --explanation} prints under each
 * played scenario the explanation of its outcome ({@link ScenarioRunner#runWithExplanation}), or why there is none;
 * {@code --vocabulary} names the rules of the trace and the coverage, and the declarations in the explanation, in a
 * vocabulary the model declares ({@link Naming}; {@code canoniek} for the canonical names), and an unknown one is
 * a wrong invocation. Exit codes: {@code 1} as soon as a scenario FAILS (or the source does not resolve — with the
 * diagnostics); else {@code 3} as soon as a scenario is a MARKER (uchar does not carry its document); else {@code 0}.
 * With no files: exit {@code 2}.
 *
 * <p>{@code --manifest} runs every line of a manifest ({@code <verwachte-exit> <scenario.rgs> <companion.rgs...>})
 * and exits {@code 0} when no line mismatches, else {@code 1}. {@code --exclusions} names the scenarios uchar's
 * scenario resolver refuses, one per line as {@code <scenario-pad>|<fragment van uchars weigering>|<reden>}: such a
 * line must be refused with that fragment, and a listed scenario uchar accepts, or one the manifest does not carry,
 * is a mismatch, so the list cannot outlive its reason.
 *
 * <p>One option of the former runner is refused by name: {@code --parameter-sets}. uchar's scenario resolver refuses
 * a scenario whose carried rules read a parameter its situation does not set, so the case files of such a scenario
 * are never written and a parameter set has no case file to go into.
 */
public final class ScenarioRunnerCli {
    private ScenarioRunnerCli() {}

    public static void main(String[] args) {
        MessageLanguage.apply(null);
        System.exit(run(args));
    }

    /** The command {@code args} names, printed to {@code System.out} and {@code System.err}; returns its exit code. */
    static int run(String[] args) {
        boolean trace = false, explanation = false;
        Path manifest = null, exclusions = null;
        String vocabulary = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--trace")) trace = true;
            else if (a.equals("--explanation")) explanation = true;
            else if (a.equals("--parameter-sets")) {
                System.err.println("--parameter-sets: uchars scenarioresolver weigert een scenario waarvan een gedragen "
                        + "regel een parameter leest die de situatie niet zet, dus de casebestanden waarin een "
                        + "parameterset zou moeten komen worden nooit geschreven");
                return 2;
            }
            else if (a.equals("--manifest")) {
                if (manifest != null) { System.err.println("--manifest mag maar één keer"); return 2; }
                if (i + 1 >= args.length) { System.err.println("--manifest verwacht een pad naar het manifest-bestand"); return 2; }
                manifest = Path.of(args[++i]);
            }
            else if (a.equals("--exclusions")) {
                if (i + 1 >= args.length) { System.err.println("--exclusions verwacht een pad naar de lijst"); return 2; }
                exclusions = Path.of(args[++i]);
            }
            else if (a.equals("--vocabulary")) {
                if (vocabulary != null) { System.err.println("--vocabulary mag maar één keer"); return 2; }
                if (i + 1 >= args.length) { System.err.println("--vocabulary verwacht de naam van een vocabulaire"); return 2; }
                vocabulary = args[++i];
            }
            else if (a.startsWith("--")) { System.err.println("onbekende optie: " + a); return 2; }
            else files.add(Path.of(a));
        }

        // --manifest: draai ALLE manifestregels in ÉÉN JVM (de Makefile-golden-loop, maar zonder 32 JVM-starts).
        // De gedeelde TOKA-fundering + fact-beans worden via de content-gekeyde BeanCompiler-cache herbruikt; per
        // regel dezelfde OK/MISMATCH-uitvoer als de shell-loop, zodat `make scenario-test` vergelijkbaar blijft.
        if (manifest != null) {
            if (!files.isEmpty()) { System.err.println("--manifest neemt zelf de scenario's uit het manifest; geef geen losse .rgs-paden mee"); return 2; }
            if (explanation) { System.err.println("--explanation geldt voor losse .rgs-bestanden, niet voor --manifest"); return 2; }
            if (vocabulary != null) { System.err.println("--vocabulary geldt voor losse .rgs-bestanden, niet voor --manifest"); return 2; }
            return runManifest(manifest, exclusions, trace);
        }
        if (exclusions != null) { System.err.println("--exclusions hoort bij --manifest"); return 2; }

        if (files.isEmpty()) {
            System.err.println("geen .rgs-bestand opgegeven: geef één of meer .rgs-paden, of --manifest <pad>");
            return 2;
        }

        String source;
        try {
            source = files.stream()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .collect(Collectors.joining("\n"));
        } catch (UncheckedIOException e) {
            System.err.println("kon een bestand niet lezen: " + e.getCause().getMessage());
            return 1;
        }

        List<ScenarioRunner.Explained> results;
        Naming naming = null;
        try {
            var bundle = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(source);
            // the vocabulary is the model's: known only once the source resolves, and asked before anything runs
            if (vocabulary != null && bundle.isOk()) {
                try {
                    naming = Naming.of(bundle.model(), vocabulary);
                } catch (Naming.VocabularyError f) {
                    System.err.println("--vocabulary: " + f.getMessage());
                    return 2;
                }
            }
            results = explanation ? ScenarioRunner.runWithExplanation(bundle, vocabulary)
                    : ScenarioRunner.run(bundle).stream()
                            .map(r -> new ScenarioRunner.Explained(r, List.of(), null, null, null)).toList();
        } catch (ScenarioRunner.ResolveError f) {
            System.err.println("de bron resolvet niet — " + f.diagnostics().size() + " diagnose(s):");
            for (String d : f.diagnostics()) System.err.println("  " + d);
            return 1;
        }

        return reportAndCode(results.stream().map(ScenarioRunner.Explained::result).toList(), trace,
                explanation ? results : null, naming);
    }

    /** Print each result (FAIL detail / MARKER / optional trace) and return the run's exit code: 1 if any FAIL, else
     *  3 if any MARKER, else 0 — the single-file CLI's exit contract, extracted so {@code --manifest} reuses it. A
     *  FAIL without a failed expectation (no fixpoint, or a case the replay could not play) prints its diagnose. */
    private static int reportAndCode(List<ScenarioResult> results, boolean trace) {
        return reportAndCode(results, trace, null, null);
    }

    /** As above; with {@code explanation} (the same scenarios, explained) each played scenario's explanation follows, and
     *  with {@code naming} the rules of the trace and the coverage go by their names in its vocabulary (null: as
     *  uchar's report names them). */
    private static int reportAndCode(List<ScenarioResult> results, boolean trace,
                                        List<ScenarioRunner.Explained> explanation, Naming naming) {
        boolean anyFail = false, anyMarker = false;
        for (int n = 0; n < results.size(); n++) {
            ScenarioResult r = results.get(n);
            System.out.println(r.status() + "  " + r.name());
            if (r.status() == Status.FAIL) {
                anyFail = true;
                boolean reported = false;
                for (ExpectationResult v : r.expectations())
                    if (!v.ok()) {
                        reported = true;
                        System.out.println("    verwachting " + v.position() + " FOUT: " + v.source().trim()
                                + "  (verwacht=" + v.expected() + ", werkelijk=" + v.actual() + ")");
                    }
                if (!reported) System.out.println("    FOUT: " + r.diagnostic());
                printTrace(r, naming);
                printCoverage(r, naming);
            } else if (r.status() == Status.MARKER) {
                anyMarker = true;
                System.out.println("    MARKER: " + r.diagnostic());
                printCoverage(r, naming);
            }
            if (trace && r.status() != Status.FAIL) printTrace(r, naming);
            if (explanation != null && r.status() != Status.MARKER) printExplanation(explanation.get(n));
        }
        return anyFail ? 1 : anyMarker ? 3 : 0;
    }

    private static void printExplanation(ScenarioRunner.Explained u) {
        if (u.explanationError() != null) {
            System.out.println("    geen uitleg: " + u.explanationError());
            return;
        }
        System.out.println("    uitleg:" + (u.explanation().isEmpty() ? " (geen kenmerktoekenning om te verantwoorden)" : ""));
        for (String sentence : u.explanation()) System.out.println("      " + sentence);
    }

    /** A scenario uchar's scenario resolver refuses: its refusal must carry {@code fragment}. */
    private record Exclusion(String fragment, String reason) {}

    /**
     * Run every manifest line in this one JVM. Each line is {@code <verwachte-exit> <scenario.rgs> <companion.rgs...>};
     * the sources are concatenated in that order, as the single-file CLI concatenates its arguments, and reduced to
     * that CLI's exit code (1 FAIL or resolve error, 3 MARKER, else 0). A line is OK when that code is the expected
     * one; a scenario named in {@code exclusionsPath} is OK when the resolver refuses it with the listed fragment.
     * Prints one line per manifest line and a summary, and returns 0 when no line mismatches and every exclusion
     * was met, 1 otherwise, and 1 for a manifest without lines.
     */
    private static int runManifest(Path manifest, Path exclusionsPath, boolean trace) {
        List<String> lines;
        Map<String, Exclusion> exclusions;
        try {
            lines = Files.readAllLines(manifest);
            exclusions = readExclusions(exclusionsPath);
        } catch (IOException e) {
            System.err.println("kon het manifest of de uitsluitingen niet lezen: " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        // the manifest lists paths relative to the directory the CLI runs in (the repository root for make)
        int ok = 0, uitgesloten = 0, mismatch = 0, wasRead = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String line : lines) {
            String r = line.strip();
            if (r.isEmpty() || r.startsWith("#")) continue;
            wasRead++;
            String[] fields = r.split("\\s+");
            int expected;
            try { expected = Integer.parseInt(fields[0]); }
            catch (NumberFormatException nfe) { System.err.println("manifestregel zonder geldige verwachte exitcode: " + r); return 2; }
            if (fields.length < 2) { System.err.println("manifestregel zonder scenario: " + r); return 2; }
            List<Path> paths = new ArrayList<>();
            for (int i = 1; i < fields.length; i++) paths.add(Path.of(fields[i]));
            String name = paths.get(0).getFileName().toString();
            Exclusion u = exclusions.get(fields[1]);
            if (u != null) {
                seen.add(fields[1]);
                String refusal = refusal(paths);
                if (refusal != null && refusal.contains(u.fragment())) {
                    System.out.println("  UITGESLOTEN  " + name + "  (" + u.reason() + ")");
                    uitgesloten++;
                } else {
                    System.out.println("  MISMATCH (uitgesloten, maar uchar weigert het niet met '" + u.fragment() + "'"
                            + (refusal == null ? ": het resolvet" : ": " + refusal) + ")  " + name);
                    mismatch++;
                }
                continue;
            }
            int got = runLine(paths, trace);
            if (got == expected) {
                System.out.println("  OK (exit " + got + ")  " + name);
                ok++;
            } else {
                System.out.println("  MISMATCH (verwacht exit " + expected + ", kreeg " + got + ")  " + name);
                mismatch++;
            }
        }
        for (String path : exclusions.keySet())
            if (!seen.contains(path)) {
                System.out.println("  MISMATCH (uitgesloten, maar het manifest draagt het niet)  " + path);
                mismatch++;
            }
        System.out.println("-------------------------------------------------------------------");
        System.out.println("manifest: " + wasRead + " regels, " + ok + " ok, " + uitgesloten + " uitgesloten, "
                + mismatch + " mismatch");
        if (wasRead == 0) {
            System.out.println("MANIFEST FAALT: het manifest telt geen regels");
            return 1;
        }
        if (mismatch > 0) {
            System.out.println("MANIFEST FAALT");
            return 1;
        }
        return 0;
    }

    /** The exclusions of {@code path} by scenario path, or none without a list. */
    private static Map<String, Exclusion> readExclusions(Path path) throws IOException {
        Map<String, Exclusion> sink = new LinkedHashMap<>();
        if (path == null) return sink;
        for (String line : Files.readAllLines(path)) {
            String r = line.strip();
            if (r.isEmpty() || r.startsWith("#")) continue;
            String[] fields = r.split("\\|", 3);
            if (fields.length != 3 || fields[1].isBlank() || fields[2].isBlank())
                throw new IllegalArgumentException("uitsluiting zonder '<scenario>|<fragment>|<reden>': " + r);
            if (sink.put(fields[0].strip(), new Exclusion(fields[1].strip(), fields[2].strip())) != null)
                throw new IllegalArgumentException("scenario twee keer uitgesloten: " + fields[0].strip());
        }
        return sink;
    }

    /** The resolver's refusal of the concatenated {@code paths}, its diagnostics on one line; null when it resolves. */
    private static String refusal(List<Path> paths) {
        String source;
        try {
            source = readSource(paths);
        } catch (UncheckedIOException e) {
            return "kon een bestand niet lezen: " + e.getCause().getMessage();
        }
        var bundle = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(source);
        if (bundle.isOk()) return null;
        List<String> d = new ArrayList<>();
        for (var error : bundle.model().errors()) d.add(error.message());
        return String.join("; ", d);
    }

    private static String readSource(List<Path> paths) {
        return paths.stream()
                .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                .collect(Collectors.joining("\n"));
    }

    /** Run ONE manifest line's concatenated sources and return its exit code (1 FAIL/resolve-fout, 3 MARKER, else
     *  0) WITHOUT the per-scenario stdout noise — the single-JVM analog of forking the CLI over {@code paths}. A
     *  read/resolve failure is exit 1, mirroring the single-file path's ResolveError→1 / read-error→1. */
    private static int runLine(List<Path> paths, boolean trace) {
        String source;
        try {
            source = paths.stream()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .collect(Collectors.joining("\n"));
        } catch (UncheckedIOException e) {
            System.err.println("kon een bestand niet lezen: " + e.getCause().getMessage());
            return 1;
        }
        List<ScenarioResult> results;
        try {
            results = ScenarioRunner.run(source);
        } catch (ScenarioRunner.ResolveError f) {
            return 1;
        }
        // Reuse the exact per-run exit contract; suppress the per-scenario detail unless --trace (the shell loop
        // printed only OK/MISMATCH per line).
        return trace ? reportAndCode(results, trace) : silentCode(results);
    }

    /** The exit code for a result list WITHOUT printing (1 FAIL, else 3 MARKER, else 0). */
    private static int silentCode(List<ScenarioResult> results) {
        boolean fail = false, marker = false;
        for (ScenarioResult r : results) {
            if (r.status() == Status.FAIL) fail = true;
            else if (r.status() == Status.MARKER) marker = true;
        }
        return fail ? 1 : marker ? 3 : 0;
    }

    private static void printTrace(ScenarioResult r, Naming naming) {
        System.out.println("    trace (" + r.trace().total() + " vuring(en)):");
        for (Firing v : r.trace().firings())
            System.out.println("      " + ruleName(v.rule(), naming) + (v.labels().isEmpty() ? "" : " @ " + v.labels())
                    + " x" + v.count());
    }

    private static void printCoverage(ScenarioResult r, Naming naming) {
        System.out.println("    dekking:");
        for (CoverageRule d : r.coverage())
            System.out.println("      " + ruleName(d.rule(), naming) + " : " + d.kind());
    }

    private static String ruleName(String line, Naming naming) {
        return naming == null ? line : naming.form(line);
    }
}
