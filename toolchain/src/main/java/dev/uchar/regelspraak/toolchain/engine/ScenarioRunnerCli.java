package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Vuring;

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
 *   dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli [--trace] [--uitleg] <file.rgs> [<file.rgs> ...]
 *   dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli [--trace] --manifest <manifest> [--uitsluitingen <lijst>]
 * }</pre>
 *
 * Multiple files are CONCATENATED in argument order (objectmodel + rules + scenario file). Per scenario one line
 * PASS/FAIL/MARKER; a FAIL prints per expectation its position in the scenario with verwacht/werkelijk, the trace
 * and the coverage lines; {@code --trace} prints the trace even for a PASS; {@code --uitleg} prints under each
 * played scenario the explanation of its outcome ({@link ScenarioRunner#runMetUitleg}), or why there is none. Exit
 * codes: {@code 1} as soon as a scenario FAILS (or the source does not resolve — with the diagnostics); else
 * {@code 3} as soon as a scenario is a MARKER (uchar does not carry its document); else {@code 0}. With no files:
 * exit {@code 2}.
 *
 * <p>{@code --manifest} runs every line of a manifest ({@code <verwachte-exit> <scenario.rgs> <companion.rgs...>})
 * and exits {@code 0} when no line mismatches, else {@code 1}. {@code --uitsluitingen} names the scenarios uchar's
 * scenario resolver refuses, one per line as {@code <scenario-pad>|<fragment van uchars weigering>|<reden>}: such a
 * line must be refused with that fragment, and a listed scenario uchar accepts, or one the manifest does not carry,
 * is a mismatch, so the list cannot outlive its reason.
 *
 * <p>One option of the former runner is refused by name: {@code --parametersets}. uchar's scenario resolver refuses
 * a scenario whose carried rules read a parameter its situation does not set, so the case files of such a scenario
 * are never written and a parameter set has no case file to go into.
 */
public final class ScenarioRunnerCli {
    private ScenarioRunnerCli() {}

    public static void main(String[] args) {
        boolean trace = false, uitleg = false;
        Path manifest = null, uitsluitingen = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--trace")) trace = true;
            else if (a.equals("--uitleg")) uitleg = true;
            else if (a.equals("--parametersets")) {
                System.err.println("--parametersets: uchars scenarioresolver weigert een scenario waarvan een gedragen "
                        + "regel een parameter leest die de situatie niet zet, dus de casebestanden waarin een "
                        + "parameterset zou moeten komen worden nooit geschreven");
                System.exit(2);
                return;
            }
            else if (a.equals("--manifest")) {
                if (manifest != null) { System.err.println("--manifest mag maar één keer"); System.exit(2); return; }
                if (i + 1 >= args.length) { System.err.println("--manifest verwacht een pad naar het manifest-bestand"); System.exit(2); return; }
                manifest = Path.of(args[++i]);
            }
            else if (a.equals("--uitsluitingen")) {
                if (i + 1 >= args.length) { System.err.println("--uitsluitingen verwacht een pad naar de lijst"); System.exit(2); return; }
                uitsluitingen = Path.of(args[++i]);
            }
            else if (a.startsWith("--")) { System.err.println("onbekende optie: " + a); System.exit(2); return; }
            else files.add(Path.of(a));
        }

        // --manifest: draai ALLE manifestregels in ÉÉN JVM (de Makefile-golden-loop, maar zonder 32 JVM-starts).
        // De gedeelde TOKA-fundering + fact-beans worden via de content-gekeyde BeanCompiler-cache herbruikt; per
        // regel dezelfde OK/MISMATCH-uitvoer als de shell-loop, zodat `make scenario-test` vergelijkbaar blijft.
        if (manifest != null) {
            if (!files.isEmpty()) { System.err.println("--manifest neemt zelf de scenario's uit het manifest; geef geen losse .rgs-paden mee"); System.exit(2); return; }
            if (uitleg) { System.err.println("--uitleg geldt voor losse .rgs-bestanden, niet voor --manifest"); System.exit(2); return; }
            System.exit(draaiManifest(manifest, uitsluitingen, trace));
            return;
        }
        if (uitsluitingen != null) { System.err.println("--uitsluitingen hoort bij --manifest"); System.exit(2); return; }

        if (files.isEmpty()) {
            System.err.println("geen .rgs-bestand opgegeven: geef één of meer .rgs-paden, of --manifest <pad>");
            System.exit(2);
            return;
        }

        String bron;
        try {
            bron = files.stream()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .collect(Collectors.joining("\n"));
        } catch (UncheckedIOException e) {
            System.err.println("kon een bestand niet lezen: " + e.getCause().getMessage());
            System.exit(1);
            return;
        }

        List<ScenarioRunner.Uitgelegd> resultaten;
        try {
            var bundel = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(bron);
            resultaten = uitleg ? ScenarioRunner.runMetUitleg(bundel)
                    : ScenarioRunner.run(bundel).stream().map(r -> new ScenarioRunner.Uitgelegd(r, List.of(), null)).toList();
        } catch (ScenarioRunner.ResolveFout f) {
            System.err.println("de bron resolvet niet — " + f.diagnostiek().size() + " diagnose(s):");
            for (String d : f.diagnostiek()) System.err.println("  " + d);
            System.exit(1);
            return;
        }

        int code = rapporteerEnCode(resultaten.stream().map(ScenarioRunner.Uitgelegd::resultaat).toList(), trace,
                uitleg ? resultaten : null);
        System.exit(code);
    }

    /** Print each result (FAIL detail / MARKER / optional trace) and return the run's exit code: 1 if any FAIL, else
     *  3 if any MARKER, else 0 — the single-file CLI's exit contract, extracted so {@code --manifest} reuses it. A
     *  FAIL without a failed expectation (no fixpoint, or a case the replay could not play) prints its diagnose. */
    private static int rapporteerEnCode(List<ScenarioResultaat> resultaten, boolean trace) {
        return rapporteerEnCode(resultaten, trace, null);
    }

    /** As above; with {@code uitleg} (the same scenarios, explained) each played scenario's explanation follows. */
    private static int rapporteerEnCode(List<ScenarioResultaat> resultaten, boolean trace,
                                        List<ScenarioRunner.Uitgelegd> uitleg) {
        boolean enigeFail = false, enigeMarker = false;
        for (int n = 0; n < resultaten.size(); n++) {
            ScenarioResultaat r = resultaten.get(n);
            System.out.println(r.status() + "  " + r.naam());
            if (r.status() == Status.FAIL) {
                enigeFail = true;
                boolean gemeld = false;
                for (VerwachtingResultaat v : r.verwachtingen())
                    if (!v.ok()) {
                        gemeld = true;
                        System.out.println("    verwachting " + v.positie() + " FOUT: " + v.bron().trim()
                                + "  (verwacht=" + v.verwacht() + ", werkelijk=" + v.werkelijk() + ")");
                    }
                if (!gemeld) System.out.println("    FOUT: " + r.diagnose());
                drukTrace(r);
                drukDekking(r);
            } else if (r.status() == Status.MARKER) {
                enigeMarker = true;
                System.out.println("    MARKER: " + r.diagnose());
                drukDekking(r);
            }
            if (trace && r.status() != Status.FAIL) drukTrace(r);
            if (uitleg != null && r.status() != Status.MARKER) drukUitleg(uitleg.get(n));
        }
        return enigeFail ? 1 : enigeMarker ? 3 : 0;
    }

    private static void drukUitleg(ScenarioRunner.Uitgelegd u) {
        if (u.uitlegFout() != null) {
            System.out.println("    geen uitleg: " + u.uitlegFout());
            return;
        }
        System.out.println("    uitleg:" + (u.uitleg().isEmpty() ? " (geen kenmerktoekenning om te verantwoorden)" : ""));
        for (String zin : u.uitleg()) System.out.println("      " + zin);
    }

    /** A scenario uchar's scenario resolver refuses: its refusal must carry {@code fragment}. */
    private record Uitsluiting(String fragment, String reden) {}

    /**
     * Run every manifest line in this one JVM. Each line is {@code <verwachte-exit> <scenario.rgs> <companion.rgs...>};
     * the sources are concatenated in that order, as the single-file CLI concatenates its arguments, and reduced to
     * that CLI's exit code (1 FAIL or resolve error, 3 MARKER, else 0). A line is OK when that code is the expected
     * one; a scenario named in {@code uitsluitingenPad} is OK when the resolver refuses it with the listed fragment.
     * Prints one line per manifest line and a summary, and returns 0 when no line mismatches and every exclusion
     * was met, 1 otherwise, and 1 for a manifest without lines.
     */
    private static int draaiManifest(Path manifest, Path uitsluitingenPad, boolean trace) {
        List<String> regels;
        Map<String, Uitsluiting> uitsluitingen;
        try {
            regels = Files.readAllLines(manifest);
            uitsluitingen = leesUitsluitingen(uitsluitingenPad);
        } catch (IOException e) {
            System.err.println("kon het manifest of de uitsluitingen niet lezen: " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        // the manifest lists paths relative to the directory the CLI runs in (the repository root for make)
        int ok = 0, uitgesloten = 0, mismatch = 0, gelezen = 0;
        java.util.Set<String> gezien = new java.util.HashSet<>();
        for (String regel : regels) {
            String r = regel.strip();
            if (r.isEmpty() || r.startsWith("#")) continue;
            gelezen++;
            String[] velden = r.split("\\s+");
            int verwacht;
            try { verwacht = Integer.parseInt(velden[0]); }
            catch (NumberFormatException nfe) { System.err.println("manifestregel zonder geldige verwachte exitcode: " + r); return 2; }
            if (velden.length < 2) { System.err.println("manifestregel zonder scenario: " + r); return 2; }
            List<Path> paden = new ArrayList<>();
            for (int i = 1; i < velden.length; i++) paden.add(Path.of(velden[i]));
            String naam = paden.get(0).getFileName().toString();
            Uitsluiting u = uitsluitingen.get(velden[1]);
            if (u != null) {
                gezien.add(velden[1]);
                String weigering = weigering(paden);
                if (weigering != null && weigering.contains(u.fragment())) {
                    System.out.println("  UITGESLOTEN  " + naam + "  (" + u.reden() + ")");
                    uitgesloten++;
                } else {
                    System.out.println("  MISMATCH (uitgesloten, maar uchar weigert het niet met '" + u.fragment() + "'"
                            + (weigering == null ? ": het resolvet" : ": " + weigering) + ")  " + naam);
                    mismatch++;
                }
                continue;
            }
            int got = draaiRegel(paden, trace);
            if (got == verwacht) {
                System.out.println("  OK (exit " + got + ")  " + naam);
                ok++;
            } else {
                System.out.println("  MISMATCH (verwacht exit " + verwacht + ", kreeg " + got + ")  " + naam);
                mismatch++;
            }
        }
        for (String pad : uitsluitingen.keySet())
            if (!gezien.contains(pad)) {
                System.out.println("  MISMATCH (uitgesloten, maar het manifest draagt het niet)  " + pad);
                mismatch++;
            }
        System.out.println("-------------------------------------------------------------------");
        System.out.println("manifest: " + gelezen + " regels, " + ok + " ok, " + uitgesloten + " uitgesloten, "
                + mismatch + " mismatch");
        if (gelezen == 0) {
            System.out.println("MANIFEST FAALT: het manifest telt geen regels");
            return 1;
        }
        if (mismatch > 0) {
            System.out.println("MANIFEST FAALT");
            return 1;
        }
        return 0;
    }

    /** The exclusions of {@code pad} by scenario path, or none without a list. */
    private static Map<String, Uitsluiting> leesUitsluitingen(Path pad) throws IOException {
        Map<String, Uitsluiting> uit = new LinkedHashMap<>();
        if (pad == null) return uit;
        for (String regel : Files.readAllLines(pad)) {
            String r = regel.strip();
            if (r.isEmpty() || r.startsWith("#")) continue;
            String[] velden = r.split("\\|", 3);
            if (velden.length != 3 || velden[1].isBlank() || velden[2].isBlank())
                throw new IllegalArgumentException("uitsluiting zonder '<scenario>|<fragment>|<reden>': " + r);
            if (uit.put(velden[0].strip(), new Uitsluiting(velden[1].strip(), velden[2].strip())) != null)
                throw new IllegalArgumentException("scenario twee keer uitgesloten: " + velden[0].strip());
        }
        return uit;
    }

    /** The resolver's refusal of the concatenated {@code paden}, its diagnostics on one line; null when it resolves. */
    private static String weigering(List<Path> paden) {
        String bron;
        try {
            bron = leesBron(paden);
        } catch (UncheckedIOException e) {
            return "kon een bestand niet lezen: " + e.getCause().getMessage();
        }
        var bundel = dev.uchar.regelspraak.resolver.ScenarioResolver.resolve(bron);
        if (bundel.isOk()) return null;
        List<String> d = new ArrayList<>();
        for (var fout : bundel.model().errors()) d.add(fout.message());
        return String.join("; ", d);
    }

    private static String leesBron(List<Path> paden) {
        return paden.stream()
                .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                .collect(Collectors.joining("\n"));
    }

    /** Run ONE manifest line's concatenated sources and return its exit code (1 FAIL/resolve-fout, 3 MARKER, else
     *  0) WITHOUT the per-scenario stdout noise — the single-JVM analog of forking the CLI over {@code paden}. A
     *  read/resolve failure is exit 1, mirroring the single-file path's ResolveFout→1 / read-error→1. */
    private static int draaiRegel(List<Path> paden, boolean trace) {
        String bron;
        try {
            bron = paden.stream()
                    .map(p -> { try { return Files.readString(p); } catch (IOException e) { throw new UncheckedIOException(e); } })
                    .collect(Collectors.joining("\n"));
        } catch (UncheckedIOException e) {
            System.err.println("kon een bestand niet lezen: " + e.getCause().getMessage());
            return 1;
        }
        List<ScenarioResultaat> resultaten;
        try {
            resultaten = ScenarioRunner.run(bron);
        } catch (ScenarioRunner.ResolveFout f) {
            return 1;
        }
        // Reuse the exact per-run exit contract; suppress the per-scenario detail unless --trace (the shell loop
        // printed only OK/MISMATCH per line).
        return trace ? rapporteerEnCode(resultaten, trace) : stilCode(resultaten);
    }

    /** The exit code for a result list WITHOUT printing (1 FAIL, else 3 MARKER, else 0). */
    private static int stilCode(List<ScenarioResultaat> resultaten) {
        boolean fail = false, marker = false;
        for (ScenarioResultaat r : resultaten) {
            if (r.status() == Status.FAIL) fail = true;
            else if (r.status() == Status.MARKER) marker = true;
        }
        return fail ? 1 : marker ? 3 : 0;
    }

    private static void drukTrace(ScenarioResultaat r) {
        System.out.println("    trace (" + r.trace().totaal() + " vuring(en)):");
        for (Vuring v : r.trace().vuringen())
            System.out.println("      " + v.regel() + (v.labels().isEmpty() ? "" : " @ " + v.labels())
                    + " x" + v.aantal());
    }

    private static void drukDekking(ScenarioResultaat r) {
        System.out.println("    dekking:");
        for (DekkingRegel d : r.dekking())
            System.out.println("      " + d.regel() + " : " + d.soort());
    }
}
