package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.transpiler.tms.Fixpunt;
import dev.uchar.regelspraak.transpiler.tms.Invoervormfout;
import dev.uchar.regelspraak.transpiler.tms.Rekenfout;
import dev.uchar.regelspraak.transpiler.tms.Schrijversconflict;

import java.util.ArrayList;
import java.util.List;

/**
 * The evaluation faults that stand at the fixpoint of a request's session, as uchar's entry reads them
 * ({@code DroolsBackend.lees(…).fouten()}): the rules give no answer on this input. A fault there is an evaluation
 * fault by its place at the fixpoint, whatever the type of its exception, so each one is an element: it keeps uchar's
 * sentence and gets the kind of its exception, one of the four types uchar's README names ("Faults") or
 * {@link Soort#ANDERE} for any other, such as the {@code IllegalStateException} of a fact creation that gives a to-one
 * role a second filling. Both adapters answer it with a 422 ({@link AntwoordBouwer#evaluatiefout}).
 *
 * <p>It carries the {@link Fixpunt} the faults stand at, and, when the request asked to see its session
 * ({@link VerzoekOpties#detailvlaggen()}), the fields the 422 gives beside the faults ({@link #detail()}). Its cause
 * is the exception uchar's write-back throws for these faults: the fault's own for one, uchar's
 * {@code Evaluatiefouten} for two or more, the one uchar's scenario report names in its diagnose.
 */
public final class Evaluatiefout extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of a fault, by the type of uchar's exception. */
    public enum Soort { DELING_DOOR_NUL, REKENFOUT, SCHRIJVERSCONFLICT, INVOERVORMFOUT, ANDERE }

    /** One fault: its kind and uchar's sentence. */
    public record Fout(Soort soort, String zin) {}

    private final transient List<Fout> fouten;
    private final transient Fixpunt fixpunt;
    private final transient ObjectNode detail;

    private Evaluatiefout(List<Fout> fouten, RuntimeException oorzaak, Fixpunt fixpunt, ObjectNode detail) {
        super(fouten.size() == 1 ? "de regels geven op deze invoer een evaluatiefout"
                : "de regels geven op deze invoer " + fouten.size() + " evaluatiefouten", oorzaak);
        this.fouten = List.copyOf(fouten);
        this.fixpunt = fixpunt;
        this.detail = detail;
    }

    /** The faults that stand at {@code fixpunt}, one element per fault in the order of its {@code fouten}, which is
     *  uchar's: by the name of the form, then by the sentence. {@code oorzaak} is what uchar's write-back threw for
     *  them. */
    static Evaluatiefout van(Fixpunt fixpunt, RuntimeException oorzaak) {
        List<Fout> uit = new ArrayList<>();
        for (dev.uchar.regelspraak.transpiler.tms.Fout f : fixpunt.fouten()) uit.add(new Fout(soort(f.fout()), f.melding()));
        return new Evaluatiefout(uit, oorzaak, fixpunt, null);
    }

    /** The same faults with {@code detail}, the fields the request asked to see of its session. */
    Evaluatiefout metDetail(ObjectNode detail) {
        return new Evaluatiefout(fouten, (RuntimeException) getCause(), fixpunt, detail);
    }

    /** The kind of uchar's fault {@code f}. */
    public static Soort soort(RuntimeException f) {
        return switch (f) {
            case Breuk.DelingDoorNul d -> Soort.DELING_DOOR_NUL;
            case Rekenfout r -> Soort.REKENFOUT;
            case Schrijversconflict s -> Soort.SCHRIJVERSCONFLICT;
            case Invoervormfout i -> Soort.INVOERVORMFOUT;
            default -> Soort.ANDERE;
        };
    }

    /** The faults, in the order uchar reports them. */
    public List<Fout> fouten() {
        return fouten;
    }

    /** What stands at the fixpoint the faults stand at, as uchar's entry reads it. */
    public Fixpunt fixpunt() {
        return fixpunt;
    }

    /** The fields the 422 gives beside the faults ({@code trace}, {@code dekking}, {@code fixpunt}), or null when the
     *  request asked none. */
    public ObjectNode detail() {
        return detail;
    }
}
