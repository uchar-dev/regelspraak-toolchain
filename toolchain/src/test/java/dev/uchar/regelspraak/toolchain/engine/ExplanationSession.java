package dev.uchar.regelspraak.toolchain.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.uchar.regelspraak.cases.DroolsBackend;
import dev.uchar.regelspraak.cases.Input;
import dev.uchar.regelspraak.loader.IrLoader;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.resolver.Resolver;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint;

import org.kie.api.runtime.KieSession;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A source built with readings for a test that sets up its beans itself and explains their session with the
 * {@link Verbaliser}: uchar's emitter writes the model, uchar builds the document with readings
 * ({@link UcharBuild#assemble(JsonNode, DroolsBackend.Branch, boolean)}) and its loader reads it with the positions of
 * its nodes, as the decision service does for an entry point that explains. A session puts the beans in, fires to the
 * fixpoint, writes back and reads the readings through uchar's entry.
 */
final class ExplanationSession {

    private final DroolsBackend.Build build;
    private final Verbaliser.Document document;

    private ExplanationSession(DroolsBackend.Build build, Verbaliser.Document document) {
        this.build = build;
        this.document = document;
    }

    static ExplanationSession of(String source) throws Exception {
        RegelSpraakModel model = Resolver.resolve(source);
        if (!model.errors().isEmpty()) throw new IllegalArgumentException(model.errors().toString());
        JsonNode tree = new ObjectMapper().readTree(UcharBuild.document(model));
        return new ExplanationSession(UcharBuild.assemble(tree, DroolsBackend.Branch.EXEC_MODEL, true),
                Verbaliser.Document.of(IrLoader.loadWithPositions(tree)));
    }

    /** A fresh bean of object type {@code type} of the build. */
    Object newInstance(String type) throws ReflectiveOperationException {
        return Class.forName(FactModelGenerator.PACKAGE + "." + Namen.ident(type, true), true, build.beanLoader())
                .getConstructor().newInstance();
    }

    /** The attribution sentences ({@link Verbaliser#verbalise(Map, Map)}) of {@code beans}, all of object type
     *  {@code type}, and of the objects the creations made, after a session that fired them to the fixpoint, in
     *  vocabulary {@code vocabulary}. The fixpoint is read with the labels of {@code beans}, before the write-back. */
    List<String> explain(String vocabulary, String type, Map<String, Object> beans) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(build.beanLoader());
        KieSession session = build.kieBase().newKieSession();
        try {
            DroolsBackend.insert(build, session, new Input().beans(new ArrayList<>(beans.values())));
            DroolsBackend.Firing firing = DroolsBackend.fire(build, session);
            if (firing.noFixpoint() != null) throw new IllegalStateException(firing.noFixpoint());
            Map<Object, String> labelOf = new IdentityHashMap<>();
            beans.forEach((label, bean) -> labelOf.put(bean, label));
            Fixpoint fixpoint = DroolsBackend.fixpointOf(build, session, labelOf::get);
            DroolsBackend.setDerivedValues(build, session);
            RegelSpraakModel model = document.model();
            ObjectType ot = model.objectTypeByName(type);
            Map<String, ObjectType> types = new LinkedHashMap<>();
            for (String label : beans.keySet()) types.put(label, ot);
            return new Verbaliser(document, Naming.of(model, vocabulary), DroolsBackend.readingsOf(build, session),
                    fixpoint).verbalise(beans, types);
        } finally {
            session.dispose();
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
