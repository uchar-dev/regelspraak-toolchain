package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.graaf.ProducentConsument;
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
 * <p>Every refusal on the way is uchar's, in uchar's sentence, and surfaces as {@link NietGedragen}: the emitter's
 * (a form the wire has no spelling for), the loader's, and the backend's (a construct the chosen branch does not
 * lower, among others). An allocation whose method the frontend could not decide is refused here as well: the
 * emitter leaves it out of the document and only says so in a warning, so a KieBase built from that document would
 * lack it without anyone being told.
 */
public final class UcharBouw {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The backend's sentence for a construct its branch does not lower: {@code DroolsBackend} refuses with
     *  {@code "transpiler <verdict>: <soort>: <naam> — <reden>"} on the exec-model branch and the same with a
     *  {@code drl-} prefix on the DRL-text branch. The construct's name is read from it because the refusal carries
     *  no structured form of it; a change of that sentence in uchar fails the marker tests of this repository. */
    private static final Pattern NIET_GELOWERD =
            Pattern.compile("^(?:drl-)?transpiler (?:MARKER|SKIP): [^:]+: (.*?)(?: — .*)?$", Pattern.DOTALL);

    private UcharBouw() {}

    /** A model this distribution cannot run because uchar refused it. {@link #construct()} names the construct the
     *  chosen transpiler branch does not lower (a marker: accepted by the frontend, inert in the engine), or an
     *  allocation the emitter left out of the document; it is null for every other refusal. */
    public static final class NietGedragen extends RuntimeException {
        private final String construct;

        NietGedragen(String melding, String construct, Throwable oorzaak) {
            super(melding, oorzaak);
            this.construct = construct;
        }

        public String construct() { return construct; }
    }

    /** Emit {@code model} and build it on {@code tak}. The model must resolve without errors: an emitter writes no
     *  document for a model that carries an ERROR, so the caller reports those first. */
    public static DroolsBackend.Build bouw(RegelSpraakModel model, DroolsBackend.Branch tak) {
        if (!model.isOk())
            throw new IllegalArgumentException("UcharBouw.bouw: het model draagt resolverfouten " + model.errors()
                    + "; meld die voordat er gebouwd wordt");
        List<Diagnostic> ongeschreven = IrEmitter.ongeschrevenVerdelingen(model);
        if (!ongeschreven.isEmpty())
            throw new NietGedragen("emitter: " + ongeschreven.get(0).message(), verdelingsnaam(model), null);
        String document;
        try {
            document = IrEmitter.emit(model);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            throw new NietGedragen("emitter: " + e.getMessage(), null, e);
        }
        JsonNode boom;
        try {
            boom = JSON.readTree(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("de emitter schreef geen leesbare JSON: " + e.getOriginalMessage(), e);
        }
        try {
            return DroolsBackend.build(boom, tak);
        } catch (IrLoader.Refusal e) {
            throw new NietGedragen("loader: " + e.getMessage(), null, e);
        } catch (IrLoader.Documentfout e) {
            throw new NietGedragen("document: " + e.getMessage(), null, e);
        } catch (CaseRunner.Excluded e) {
            throw new NietGedragen(e.getMessage(), nietGelowerd(e.getMessage()), e);
        }
    }

    /** The base name of the construct a transpiler refusal names, or null when {@code melding} is another refusal. */
    static String nietGelowerd(String melding) {
        if (melding == null) return null;
        Matcher m = NIET_GELOWERD.matcher(melding);
        return m.matches() ? ProducentConsument.basisNaam(m.group(1)) : null;
    }

    private static String verdelingsnaam(RegelSpraakModel model) {
        return model.verdelingen.stream()
                .filter(v -> v.methode() == dev.uchar.regelspraak.ir.ResolvedVerdeling.Methode.COMPLEX)
                .map(v -> ProducentConsument.basisNaam(v.naam())).findFirst().orElseThrow();
    }
}
