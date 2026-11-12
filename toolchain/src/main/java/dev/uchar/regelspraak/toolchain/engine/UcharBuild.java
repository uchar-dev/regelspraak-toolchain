package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.CaseRunner;
import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.message.Msg;
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
 *
 * <p>A document that holds an {@code Unresolved} node — a name the resolver bound to no declaration — is not built
 * either, and that refusal is {@link UnresolvedName}, not {@link NotCarried}: uchar carries such a rule and its
 * executors fault on it only when a request reaches it (WF-044), so it is a fault in the source, which this
 * distribution reports before a request does. The frontend writes the same node for a form RegelSpraak has and the
 * IR carries without a norm (the eleven test, a time-of-day part of a date-time, "regelversie X is gevuurd"); there
 * the name is resolved and the form is what is missing, so such a {@link Site} says so ({@link Site#reason()}).
 */
public final class UcharBuild {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The backend's sentence for a construct its analysis does not lower: {@code DroolsBackend} refuses with
     *  {@code "transpiler <verdict>: <soort>: <naam> — <reden>"}, on both branches in the same sentence, since both
     *  render one analysis. The construct's name is read from it because the refusal carries no structured form of
     *  it; a change of that sentence in uchar fails the marker tests of this repository. */
    private static final Pattern NOT_LOWERED =
            Pattern.compile("^transpiler (?:MARKER|SKIP): [^:]+: (.*?)(?: — .*)?$", Pattern.DOTALL);

    /** The reasons of RGS-092 for a form the IR does not carry because no norm gives it a meaning. The wire carries no
     *  code (IR-SPEC ch. 2 {@code diagnostics}), so a site recognises its reason by the reason's text, rendered from
     *  uchar's catalogue in each language the frontend writes. */
    private static final List<String> FORMS_WITHOUT_NORM =
            List.of("RGS-092.eleven-test", "RGS-092.time-of-day-part", "RGS-092.rule-fired");
    private static final List<java.util.Locale> FRONTEND_LANGUAGES =
            List.of(java.util.Locale.forLanguageTag("nl"), java.util.Locale.forLanguageTag("en"));

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

    /** A document that holds one or more {@code Unresolved} nodes (IR-SPEC ch. 4, the expression and the condition
     *  marker), each named by its {@link Site}: a name the resolver bound to no declaration, or a form without a
     *  norm. */
    public static final class UnresolvedName extends RuntimeException {
        private final List<Site> sites;

        UnresolvedName(List<Site> sites) {
            super(String.join("; ", sites.stream().map(Site::sentence).toList()));
            this.sites = List.copyOf(sites);
        }

        public List<Site> sites() { return sites; }

        /** Every site is a form without a norm: the source names nothing unresolved, the IR does not carry it. */
        public boolean onlyFormsWithoutNorm() { return sites.stream().allMatch(s -> s.reason() != null); }

        /** Each site as an ERROR at its source line, as the resolver reports an error. */
        public List<Diagnostic> diagnostics() {
            return sites.stream().map(Site::diagnostic).toList();
        }
    }

    /**
     * One {@code Unresolved} node: {@code name} is its source snippet, {@code form} the name of the form of the rule
     * layer it stands in (null outside one), {@code pointer} its place in the document (RFC 6901), {@code line} the
     * source line of the diagnostic that accounts for it (WF-113 (b): a diagnostic whose message quotes the snippet),
     * or 0 when no diagnostic with a line quotes it or diagnostics on more than one line do. {@code reason} is the
     * key of the RGS-092 reason when the quoting diagnostic marks a form without a norm, else null.
     */
    public record Site(String name, String form, String pointer, int line, String reason) {
        /** Without the line, which a caller that reads several files maps to its file. */
        String text() {
            if (reason != null)
                return "vorm zonder norm: " + Msg.frontend(reason).text() + " — '" + name + "'"
                        + (line > 0 ? "" : " op " + pointer);
            return "onopgeloste naam '" + name + "'" + (form == null ? "" : " in de regel '" + form + "'")
                    + (line > 0 ? "" : " op " + pointer);
        }

        public String sentence() { return text() + (line > 0 ? ", bronregel " + line : ""); }

        public Diagnostic diagnostic() {
            return new Diagnostic(dev.uchar.regelspraak.model.RegelSpraakModel.Severity.ERROR, text(), line);
        }
    }

    /** The {@code Unresolved} nodes of {@code document}, in document order. */
    public static List<Site> unresolved(JsonNode document) {
        List<Site> sites = new java.util.ArrayList<>();
        collect(document, "", null, sites);
        return sites.stream().map(site -> {
            java.util.Set<Integer> quoting = new java.util.TreeSet<>();
            java.util.Set<String> reasons = new java.util.TreeSet<>();
            for (JsonNode d : document.path("diagnostics")) {
                String message = d.path("message").asText("");
                if (!message.contains(site.name())) continue;
                if (d.path("line").asInt(0) > 0) quoting.add(d.path("line").asInt());
                for (String key : FORMS_WITHOUT_NORM)
                    for (java.util.Locale language : FRONTEND_LANGUAGES)
                        if (message.contains(Msg.frontend(key).text(language))) reasons.add(key);
            }
            return new Site(site.name(), site.form(), site.pointer(),
                    quoting.size() == 1 ? quoting.iterator().next() : 0,
                    reasons.size() == 1 ? reasons.iterator().next() : null);
        }).toList();
    }

    /** {@code form} is the name of the element of a list under {@code /rules} that {@code node} lies in. */
    private static void collect(JsonNode node, String pointer, String form, List<Site> sink) {
        if (node.isObject()) {
            if ("Unresolved".equals(node.path("node").asText(null)))
                sink.add(new Site(node.path("source").path("snippet").asText(""), form, pointer, 0, null));
            var fields = node.fields();
            while (fields.hasNext()) {
                var f = fields.next();
                collect(f.getValue(), pointer + "/" + escape(f.getKey()), form, sink);
            }
        } else if (node.isArray()) {
            boolean formList = pointer.startsWith("/rules/") && pointer.indexOf('/', "/rules/".length()) < 0;
            for (int i = 0; i < node.size(); i++) {
                JsonNode element = node.get(i);
                String name = formList && element.path("name").isTextual() ? element.path("name").asText() : form;
                collect(element, pointer + "/" + i, name, sink);
            }
        }
    }

    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }

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
     *  errors. A refusal of the emitter is {@link NotCarried}. */
    public static String document(RegelSpraakModel model) {
        if (!model.isOk())
            throw new IllegalArgumentException("UcharBouw: het model draagt resolverfouten " + model.errors()
                    + "; meld die voordat er gebouwd wordt");
        try {
            return IrEmitter.emit(model);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            throw new NotCarried("emitter: " + e.getMessage(), null, e);
        }
    }

    /** Build {@code document}, a {@code uchar-ir-v1} document, on {@code branch} through uchar's facade. Every refusal
     *  of the facade — the loader's, a malformed document, the backend's — is {@link NotCarried}; a document with an
     *  {@code Unresolved} node is {@link UnresolvedName}, before the facade sees it. */
    public static DroolsBackend.Build assemble(JsonNode document, DroolsBackend.Branch branch) {
        return assemble(document, branch, false);
    }

    /** {@link #assemble(JsonNode, DroolsBackend.Branch)}, and where {@code readings} the build with readings
     *  ({@link DroolsBackend#build(JsonNode, DroolsBackend.Branch, boolean)}), whose sessions
     *  {@link DroolsBackend#readingsOf} reads for the explanation; an answer is given from the build without. */
    public static DroolsBackend.Build assemble(JsonNode document, DroolsBackend.Branch branch, boolean readings) {
        List<Site> unresolved = unresolved(document);
        if (!unresolved.isEmpty()) throw new UnresolvedName(unresolved);
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
}
