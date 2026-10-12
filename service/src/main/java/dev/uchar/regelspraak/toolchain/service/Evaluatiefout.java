package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.runtime.Breuk;
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
 */
public final class Evaluatiefout extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of a fault, by the type of uchar's exception. */
    public enum Soort { DELING_DOOR_NUL, REKENFOUT, SCHRIJVERSCONFLICT, INVOERVORMFOUT, ANDERE }

    /** One fault: its kind and uchar's sentence. */
    public record Fout(Soort soort, String zin) {}

    private final transient List<Fout> fouten;

    private Evaluatiefout(List<Fout> fouten, RuntimeException oorzaak) {
        super(fouten.size() == 1 ? "de regels geven op deze invoer een evaluatiefout"
                : "de regels geven op deze invoer " + fouten.size() + " evaluatiefouten", oorzaak);
        this.fouten = List.copyOf(fouten);
    }

    /** The faults that stand at the fixpoint, one element per fault in the order of {@code fouten}, which is uchar's:
     *  by the name of the form, then by the sentence. The first fault's exception is the cause. */
    static Evaluatiefout van(List<dev.uchar.regelspraak.transpiler.tms.Fout> fouten) {
        List<Fout> uit = new ArrayList<>();
        for (dev.uchar.regelspraak.transpiler.tms.Fout f : fouten) uit.add(new Fout(soort(f.fout()), f.melding()));
        return new Evaluatiefout(uit, fouten.get(0).fout());
    }

    private static Soort soort(RuntimeException f) {
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
}
