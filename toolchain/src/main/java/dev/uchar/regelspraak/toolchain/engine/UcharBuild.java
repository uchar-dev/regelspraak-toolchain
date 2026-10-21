package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Diagnostic;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one way from a resolved model to a KieBase in this distribution: uchar's emitter writes the model as a
 * {@code uchar-ir-v1} document and uchar's backend builds that document through {@link DroolsBackend#build}, the
 * path its gate 12 replays. The loader returns the ch. 7 defaults, the role bindings, the Let index, the fold-source
 * paths and the navigation bindings beside the model, and only that build hands them all to the transpiler; a
 * build assembled here could leave one out and still produce a KieBase that answers wrongly without a marker.
 *
 * <p>Every refusal on the way is uchar's, in uchar's sentence, and surfaces as {@link NotCarried}: the emitter's
 * (a form the wire has no spelling for), the loader's, and the backend's (a construct the chosen branch does not
 * lower, among others). An allocation whose method the frontend could not decide is refused here as well: the
 * emitter leaves it out of the document and only says so in a warning, so a KieBase built from that document would
 * lack it without anyone being told.
 */
public final class UcharBuild {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The backend's sentence for a construct its analysis does not lower: {@code DroolsBackend} refuses with
     *  {@code "transpiler <verdict>: <soort>: <naam> — <reden>"}, on both branches in the same sentence, since both
     *  render one analysis. The construct's name is read from it because the refusal carries no structured form of
     *  it; a change of that sentence in uchar fails the marker tests of this repository. */
    private static final Pattern NOT_LOWERED =
            Pattern.compile("^transpiler (?:MARKER|SKIP): [^:]+: (.*?)(?: — .*)?$", Pattern.DOTALL);

    static { MessageLanguage.apply(null); }

    private UcharBuild() {}

    /** A model this distribution cannot run because uchar refused it. {@link #construct()} names the construct the
     *  chosen transpiler branch does not lower (a marker: accepted by the frontend, inert in the engine), or an
     *  allocation the emitter left out of the document; it is null for every other refusal. */
    public static final class NotCarried extends RuntimeException {
        private final String construct;

        NotCarried(String message, String construct, Throwable cause) {
            super(message, cause);
            this.construct = construct;
        }

        public String construct() { return construct; }

        /** The sentence in {@code locale}, for the refusals of uchar that render per call. */
        public String message(java.util.Locale locale) {
            Throwable cause = getCause();
            if (cause instanceof IrLoader.Refusal r) return "loader: " + r.message(locale);
            if (cause instanceof CaseRunner.Excluded x) return x.message(locale);
            return getMessage();
        }
    }

    /** Emit {@code model} and build it on {@code branch}. The model must resolve without errors: an emitter writes no
     *  document for a model that carries an ERROR, so the caller reports those first. */
    public static DroolsBackend.Build assemble(RegelSpraakModel model, DroolsBackend.Branch branch) {
        String document = document(model);
        JsonNode tree;
        try {
            tree = JSON.readTree(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("de emitter schreef geen leesbare JSON: " + e.getOriginalMessage(), e);
        }
        return assemble(tree, branch);
    }

    /** The {@code uchar-ir-v1} document uchar's emitter writes for {@code model}, which must resolve without
     *  errors. A refusal of the emitter, and an allocation it leaves out, is {@link NotCarried}. */
    public static String document(RegelSpraakModel model) {
        if (!model.isOk())
            throw new IllegalArgumentException("UcharBouw: het model draagt resolverfouten " + model.errors()
                    + "; meld die voordat er gebouwd wordt");
        List<Diagnostic> unwritten = IrEmitter.unwrittenAllocations(model);
        if (!unwritten.isEmpty())
            throw new NotCarried("emitter: " + unwritten.get(0).message(), allocationName(model), null);
        try {
            return IrEmitter.emit(model);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            throw new NotCarried("emitter: " + e.getMessage(), null, e);
        }
    }

    /** Build {@code document}, a {@code uchar-ir-v1} document, on {@code branch} through uchar's facade. Every refusal
     *  of the facade — the loader's, a malformed document, the backend's — is {@link NotCarried}. */
    public static DroolsBackend.Build assemble(JsonNode document, DroolsBackend.Branch branch) {
        return assemble(document, branch, false);
    }

    /** {@link #assemble(JsonNode, DroolsBackend.Branch)}, and where {@code readings} the build with readings
     *  ({@link DroolsBackend#build(JsonNode, DroolsBackend.Branch, boolean)}), whose sessions
     *  {@link DroolsBackend#readingsOf} reads for the explanation; an answer is given from the build without. */
    public static DroolsBackend.Build assemble(JsonNode document, DroolsBackend.Branch branch, boolean readings) {
        try {
            return DroolsBackend.build(document, branch, readings);
        } catch (IrLoader.Refusal e) {
            throw new NotCarried("loader: " + e.getMessage(), null, e);
        } catch (IrLoader.DocumentError e) {
            throw new NotCarried("document: " + e.getMessage(), null, e);
        } catch (CaseRunner.Excluded e) {
            throw new NotCarried(e.getMessage(), notLowered(e.getMessage()), e);
        }
    }

    /** The base name of the construct a transpiler refusal names, or null when {@code melding} is another refusal. */
    static String notLowered(String message) {
        if (message == null) return null;
        Matcher m = NOT_LOWERED.matcher(message);
        return m.matches() ? ProducerConsumer.baseName(m.group(1)) : null;
    }

    private static String allocationName(RegelSpraakModel model) {
        return model.allocations.stream()
                .filter(v -> v.method() == dev.uchar.regelspraak.ir.ResolvedAllocation.Method.COMPLEX)
                .map(v -> ProducerConsumer.baseName(v.name())).findFirst().orElseThrow();
    }
}
