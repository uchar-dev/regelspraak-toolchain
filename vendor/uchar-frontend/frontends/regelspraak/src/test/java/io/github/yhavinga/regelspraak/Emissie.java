package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.emit.IrEmitter;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;

import java.util.List;

/**
 * The emitter's text for a model that carries an ERROR, for the suites that read what a lowering BUILT (a
 * node present or absent) on a sentence the frontend also refuses. Since WF-113 (a) (2026-09-23)
 * {@link IrEmitter#emit} refuses such a model, because an ERROR means the producer writes no document; the
 * text returned here is therefore never a document. The ERRORs are set aside for the one emission and put
 * back in their original order, so the caller's model is unchanged afterwards.
 */
final class Emissie {

    private Emissie() {}

    static String zonderFouten(RegelSpraakModel m) {
        List<Diagnostic> alle = List.copyOf(m.diagnostics);
        m.diagnostics.removeIf(Diagnostic::isError);
        try {
            return IrEmitter.emit(m);
        } finally {
            m.diagnostics.clear();
            m.diagnostics.addAll(alle);
        }
    }
}
