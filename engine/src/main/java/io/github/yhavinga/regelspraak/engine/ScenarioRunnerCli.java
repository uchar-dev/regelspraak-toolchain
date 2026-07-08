package io.github.yhavinga.regelspraak.engine;

import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.DekkingRegel;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Status;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.VerwachtingResultaat;
import io.github.yhavinga.regelspraak.engine.ScenarioResultaat.Vuring;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver;
import io.github.yhavinga.regelspraak.resolver.ScenarioResolver.ScenarioBundel;
import io.github.yhavinga.regelspraak.transpiler.kern.PeriodeDekking;

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
 *   io.github.yhavinga.regelspraak.engine.ScenarioRunnerCli [--trace] <file.rgs> [<file.rgs> ...]
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
        Path parametersets = null;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--trace")) trace = true;
            else if (a.equals("--parametersets")) {
                if (parametersets != null) { System.err.println("--parametersets mag maar één keer"); System.exit(2); return; }
                if (i + 1 >= args.length) { System.err.println("--parametersets verwacht een pad naar een .json-bestand"); System.exit(2); return; }
                parametersets = Path.of(args[++i]);
            }
            else if (a.startsWith("--")) { System.err.println("onbekende optie: " + a); System.exit(2); return; }
            else files.add(Path.of(a));
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
                    if (d.severity() == io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity.WARNING
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
