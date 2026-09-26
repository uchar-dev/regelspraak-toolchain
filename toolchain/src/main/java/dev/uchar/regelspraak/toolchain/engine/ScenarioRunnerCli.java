package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import dev.uchar.regelspraak.engine.ScenarioResultaat.Vuring;
import dev.uchar.regelspraak.model.Parameterset;
import dev.uchar.regelspraak.resolver.ScenarioResolver;
import dev.uchar.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import dev.uchar.regelspraak.transpiler.kern.PeriodeDekking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CLI for the {@link ScenarioRunner} (docs/SCENARIOTOETSEN.md, § "Runner & CLI"). Usage:
 *
 * <pre>{@code
 *   dev.uchar.regelspraak.toolchain.engine.ScenarioRunnerCli [--trace] <file.rgs> [<file.rgs> ...]
 * }</pre>
 *
 * Multiple files are CONCATENATED in argument order (the TokaVuurTest pattern: objectmodel + rules + scenario
 * file). Per scenario one line PASS/FAIL/MARKER; a FAIL prints per-expectation verwacht/werkelijk, the trace, and
 * the coverage lines; {@code --trace} prints the trace even for a PASS; {@code --uitleg} prints the
 * {@link Verbalisator} verhaal (the assigned kenmerken justified with their evaluated operandwaarden) under each
 * scenario. Exit codes: {@code 1} as soon as a
 * scenario FAILS (or the source does not resolve — with the diagnostics); else {@code 3} as soon as a scope rule
 * is a MARKER; else {@code 0}. With no files: exit {@code 2} with a clear message (the golden manifest phase).
 */
public final class ScenarioRunnerCli {
    private ScenarioRunnerCli() {}

    public static void main(String[] args) {
        boolean trace = false;
        boolean uitleg = false;
        Path parametersets = null;
        Path manifest = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--trace")) trace = true;
            else if (a.equals("--uitleg")) uitleg = true;
            else if (a.equals("--parametersets")) {
                if (parametersets != null) { System.err.println("--parametersets mag maar één keer"); System.exit(2); return; }
                if (i + 1 >= args.length) { System.err.println("--parametersets verwacht een pad naar een .json-bestand"); System.exit(2); return; }
                parametersets = Path.of(args[++i]);
            }
            else if (a.equals("--manifest")) {
                if (manifest != null) { System.err.println("--manifest mag maar één keer"); System.exit(2); return; }
                if (i + 1 >= args.length) { System.err.println("--manifest verwacht een pad naar het manifest-bestand"); System.exit(2); return; }
                manifest = Path.of(args[++i]);
            }
            else if (a.startsWith("--")) { System.err.println("onbekende optie: " + a); System.exit(2); return; }
            else files.add(Path.of(a));
        }

        // --manifest: draai ALLE manifestregels in ÉÉN JVM (de Makefile-golden-loop, maar zonder 32 JVM-starts).
        // De gedeelde TOKA-fundering + fact-beans worden via de content-gekeyde BeanCompiler-cache herbruikt; per
        // regel dezelfde OK/MISMATCH-uitvoer als de shell-loop, zodat `make scenario-test` vergelijkbaar blijft.
        if (manifest != null) {
            if (!files.isEmpty()) { System.err.println("--manifest neemt zelf de scenario's uit het manifest; geef geen losse .rgs-paden mee"); System.exit(2); return; }
            System.exit(draaiManifest(manifest, trace, uitleg));
            return;
        }

        if (files.isEmpty()) {
            System.err.println("geen .rgs-bestand opgegeven — de golden TOKA-set (examples/toka-scenarios/manifest) "
                    + "bestaat nog niet (volgende fase). Geef één of meer .rgs-paden op.");
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

        List<ScenarioResultaat> resultaten;
        try {
            if (parametersets == null) {
                // ZONDER de vlag: exact het bestaande pad (byte-identiek).
                resultaten = ScenarioRunner.run(bron);
            } else {
                // MET sets: eerst de RUWE namen -> resolve met externe parameters (de parametergate laat ze door) ->
                // dan de typed laad tegen bundel.model() -> periodedekking-WARNINGs -> run(bundel, sets).
                String json = Files.readString(parametersets);
                List<String> namen = ParametersetLader.parameterNamen(json);
                ScenarioBundel bundel = ScenarioResolver.resolve(bron, new HashSet<>(namen));
                List<Parameterset> sets = ParametersetLader.laad(json, bundel.model());
                var bevindingen = PeriodeDekking.controleer(bundel.model(), sets);
                PeriodeDekking.meldAlle(bevindingen, bundel.model());
                // G3 CLI-zichtbaarheid: elke periodedekking-WARNING op stdout in de samenvatting (prefix per regel).
                for (var d : bundel.model().diagnostics)
                    if (d.severity() == dev.uchar.regelspraak.model.RegelSpraakModel.Severity.WARNING
                            && d.message().startsWith("periodedekking:"))
                        System.out.println("WAARSCHUWING: " + d.message());
                resultaten = ScenarioRunner.run(bundel, sets);
            }
        } catch (ParametersetLader.ParametersetFout f) {
            System.err.println("parametersets fout — " + f.fouten().size() + " diagnose(s):");
            for (String d : f.fouten()) System.err.println("  " + d);
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("kon de parametersets niet lezen: " + e.getMessage());
            System.exit(1);
            return;
        } catch (ScenarioRunner.ResolveFout f) {
            System.err.println("de bron resolvet niet — " + f.diagnostiek().size() + " diagnose(s):");
            for (String d : f.diagnostiek()) System.err.println("  " + d);
            System.exit(1);
            return;
        }

        System.exit(rapporteerEnCode(resultaten, trace, uitleg));
    }

    /** Print each result (FAIL detail / MARKER / optional trace / optional uitleg-verhaal) and return the run's exit
     *  code: 1 if any FAIL, else 3 if any MARKER, else 0 — the single-file CLI's exit contract, extracted so
     *  {@code --manifest} reuses it. {@code uitleg} threads {@code --uitleg} through so the verhaal prints on both
     *  the single-file and the {@code --manifest} route (incl. {@code --manifest … --uitleg}). */
    private static int rapporteerEnCode(List<ScenarioResultaat> resultaten, boolean trace, boolean uitleg) {
        boolean enigeFail = false, enigeMarker = false;
        for (ScenarioResultaat r : resultaten) {
            System.out.println(r.status() + "  " + r.naam());
            if (r.status() == Status.FAIL) {
                enigeFail = true;
                for (VerwachtingResultaat v : r.verwachtingen())
                    if (!v.ok())
                        System.out.println("    regel " + v.line() + " FOUT: " + v.bron().trim()
                                + "  (verwacht=" + v.verwacht() + ", werkelijk=" + v.werkelijk() + ")");
                drukTrace(r);
                drukDekking(r);
            } else if (r.status() == Status.MARKER) {
                enigeMarker = true;
                System.out.println("    MARKER: " + r.diagnose());
                drukDekking(r);
            }
            if (trace && r.status() != Status.FAIL) drukTrace(r);
            if (uitleg) drukUitleg(r);
        }
        return enigeFail ? 1 : enigeMarker ? 3 : 0;
    }

    /**
     * Run EVERY manifest line in this one JVM (docs/SCENARIOTOETSEN.md gate G2), replacing the Makefile shell loop
     * that forked one JVM per line. Each line is {@code <verwachte-exit> <scenario.rgs> <companion.rgs...>}; the
     * sources are concatenated in the SAME order the shell loop passed them ({@code scenario} then companions), run
     * through {@link ScenarioRunner#run(String)}, and reduced to a per-line exit code with the SAME semantics as a
     * single-file invocation (1 FAIL/resolve-fout, 3 MARKER, else 0). Per line it prints the SAME
     * {@code OK (exit N)} / {@code MISMATCH …} line the shell loop did, plus the golden summary + verdict, so
     * {@code make scenario-test} output stays comparable. The shared TOKA foundation's fact-beans are compiled ONCE
     * (the content-keyed {@link BeanCompiler} cache), so 31 lines reuse one bean bundle instead of recompiling it.
     *
     * <p>Returns the OVERALL exit code: 0 when the golden set passes (all 29 exit-0 lines OK, no mismatch, the
     * exit-3 marker line present), else 1 — matching the Makefile's own pass/fail gate.
     */
    private static int draaiManifest(Path manifest, boolean trace, boolean uitleg) {
        List<String> regels;
        try {
            regels = Files.readAllLines(manifest);
        } catch (IOException e) {
            System.err.println("kon het manifest niet lezen: " + e.getMessage());
            return 2;
        }
        Path basis = manifest.toAbsolutePath().getParent();
        // basis is the manifest dir; the manifest lists paths relative to the REPO ROOT (examples/…), so resolve
        // each path against the CWD (where the Makefile invokes java) exactly as the shell loop did — a bare
        // Path.of(token) is CWD-relative, identical to the shell's `$scenario`/`$companions`.
        if (basis == null) basis = Path.of("");
        int valueOk = 0, otherOk = 0, fail = 0;
        boolean markerOk = false;
        for (String regel : regels) {
            String r = regel.strip();
            if (r.isEmpty() || r.startsWith("#")) continue;
            String[] velden = r.split("\\s+");
            int verwacht;
            try { verwacht = Integer.parseInt(velden[0]); }
            catch (NumberFormatException nfe) { System.err.println("manifestregel zonder geldige verwachte exitcode: " + r); return 2; }
            List<Path> paden = new ArrayList<>();
            for (int i = 1; i < velden.length; i++) paden.add(Path.of(velden[i]));
            String naam = paden.isEmpty() ? "?" : paden.get(0).getFileName().toString();
            int got = draaiRegel(paden, trace, uitleg);
            if (got == verwacht) {
                System.out.println("  OK (exit " + got + ")  " + naam);
                if (verwacht == 0) valueOk++; else otherOk++;
                if (verwacht == 3) markerOk = true;
            } else {
                System.out.println("  MISMATCH (verwacht exit " + verwacht + ", kreeg " + got + ")  " + naam);
                fail++;
            }
        }
        System.out.println("-------------------------------------------------------------------");
        // 29 exit-0-scenario's sinds besluit 16Y (2026-08-29): toka-45 verwacht exit 1 in het
        // manifest — de eenheid-identiteit-typering legt de dimensionele inconsistentie van die
        // casus bloot (zie de manifestnoot). Een verdwenen of extra manifestregel valt hier op.
        System.out.println("golden TOKA: " + valueOk + "/29 PASS, marker-exit-3: " + (markerOk ? "yes" : "no")
                + "  (+" + otherOk + " andere manifestregels ok, " + fail + " mismatch)");
        if (valueOk == 29 && fail == 0 && markerOk) {
            System.out.println("golden TOKA: 29/29 PASS, marker-exit-3: ok");
            return 0;
        }
        System.out.println("GOLDEN FAALT");
        return 1;
    }

    /** Run ONE manifest line's concatenated sources and return its exit code (1 FAIL/resolve-fout, 3 MARKER, else
     *  0) WITHOUT the per-scenario stdout noise — the single-JVM analog of forking the CLI over {@code paden}. A
     *  read/resolve failure is exit 1, mirroring the single-file path's ResolveFout→1 / read-error→1. */
    private static int draaiRegel(List<Path> paden, boolean trace, boolean uitleg) {
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
        // Reuse the exact per-run exit contract; suppress the per-scenario detail unless --trace of --uitleg (the
        // shell loop printed only OK/MISMATCH per line). --uitleg moet ook op de --manifest-route het verhaal tonen,
        // dus dan door rapporteerEnCode (die print) i.p.v. de stille code.
        return (trace || uitleg) ? rapporteerEnCode(resultaten, trace, uitleg) : stilCode(resultaten);
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

    /** The {@link Verbalisator} verhaal under a scenario: one NL sentence per assigned/withheld kenmerk, with the
     *  evaluated operandwaarden — the human-readable justification the jurist/burger reads. Silent when empty. */
    private static void drukUitleg(ScenarioResultaat r) {
        if (r.uitleg().isEmpty()) return;
        System.out.println("    uitleg:");
        for (String zin : r.uitleg()) System.out.println("      " + zin);
    }
}
