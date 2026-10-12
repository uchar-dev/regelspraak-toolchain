package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.runtime.Breuk;
import dev.uchar.regelspraak.transpiler.tms.Evaluatiefouten;
import dev.uchar.regelspraak.transpiler.tms.Invoervormfout;
import dev.uchar.regelspraak.transpiler.tms.Rekenfout;
import dev.uchar.regelspraak.transpiler.tms.Schrijversconflict;

import java.util.ArrayList;
import java.util.List;

/**
 * The evaluation faults that stand at the fixpoint of a request's session, as uchar's write-back throws them
 * ({@code DroolsBackend.zetAfgeleideWaarden}): the rules give no answer on this input. Each fault keeps uchar's
 * sentence and gets the kind of uchar's exception; both adapters answer it with a 422
 * ({@link AntwoordBouwer#evaluatiefout}). The kind is read here, in the core, so that an exception of another type is a
 * broken contract that the adapters answer as an internal fault, and never a 422 with a guessed kind.
 */
public final class Evaluatiefout extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The kind of a fault, by uchar's exception. */
    public enum Soort { DELING_DOOR_NUL, REKENFOUT, SCHRIJVERSCONFLICT, INVOERVORMFOUT }

    /** One fault: its kind and uchar's sentence. */
    public record Fout(Soort soort, String zin) {}

    private final transient List<Fout> fouten;

    private Evaluatiefout(List<Fout> fouten, RuntimeException oorzaak) {
        super(fouten.size() == 1 ? "de regels geven op deze invoer een evaluatiefout"
                : "de regels geven op deze invoer " + fouten.size() + " evaluatiefouten", oorzaak);
        this.fouten = List.copyOf(fouten);
    }

    /** The faults uchar's write-back threw: one fault as its own exception, two or more as {@code Evaluatiefouten},
     *  one element per fault in the order of its {@code fouten()}. */
    static Evaluatiefout van(RuntimeException fout) {
        List<Fout> uit = new ArrayList<>();
        if (fout instanceof Evaluatiefouten meer) for (RuntimeException f : meer.fouten()) uit.add(fout(f));
        else uit.add(fout(fout));
        return new Evaluatiefout(uit, fout);
    }

    private static Fout fout(RuntimeException f) {
        Soort soort = switch (f) {
            case Breuk.DelingDoorNul d -> Soort.DELING_DOOR_NUL;
            case Rekenfout r -> Soort.REKENFOUT;
            case Schrijversconflict s -> Soort.SCHRIJVERSCONFLICT;
            case Invoervormfout i -> Soort.INVOERVORMFOUT;
            default -> throw new IllegalStateException("uchars terugschrijving wierp een evaluatiefout van een onbekend"
                    + " type: " + f.getClass().getName(), f);
        };
        return new Fout(soort, f.getMessage());
    }

    /** The faults, in the order uchar reports them. */
    public List<Fout> fouten() {
        return fouten;
    }
}
