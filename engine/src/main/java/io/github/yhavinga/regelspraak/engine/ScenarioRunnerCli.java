package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Status;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Vuring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CLI for the {@link ScenarioRunner} (docs/SCENARIOTOETSEN.md, § "Runner & CLI"). Usage:
 *
 * <pre>{@code
 *   io.github.yhavinga.regelspraak.engine.ScenarioRunnerCli [--trace] <file.rs> [<file.rs> ...]
 * }</pre>
 *
 * Multiple files are CONCATENATED in argument order (the TokaVuurTest pattern: objectmodel + rules + scenario
 * file). Per scenario one line PASS/FAIL/MARKER; a FAIL prints per-expectation verwacht/werkelijk, the trace, and
 * the coverage lines; {@code --trace} prints the trace even for a PASS. Exit codes: {@code 1} as soon as a
 * scenario FAILS (or the source does not resolve — with the diagnostics); else {@code 3} as soon as a scope rule
 * is a MARKER; else {@code 0}. With no files: exit {@code 2} with a clear message (the golden manifest phase).
 */
public final class ScenarioRunnerCli {
    private ScenarioRunnerCli() {}

    public static void main(String[] args) {
        boolean trace = false;
        List<Path> files = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--trace")) trace = true;
            else if (a.startsWith("--")) { System.err.println("onbekende optie: " + a); System.exit(2); }
            else files.add(Path.of(a));
        }
        if (files.isEmpty()) {
            System.err.println("geen .rs-bestand opgegeven — de golden TOKA-set (examples/toka-scenarios/manifest) "
                    + "bestaat nog niet (volgende fase). Geef één of meer .rs-paden op.");
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
            resultaten = ScenarioRunner.run(bron);
        } catch (ScenarioRunner.ResolveFout f) {
            System.err.println("de bron resolvet niet — " + f.diagnostiek().size() + " diagnose(s):");
            for (String d : f.diagnostiek()) System.err.println("  " + d);
            System.exit(1);
            return;
        }

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
        }

        if (enigeFail) System.exit(1);
        else if (enigeMarker) System.exit(3);
        else System.exit(0);
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
