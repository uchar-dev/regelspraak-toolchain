package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraak.RegelSpraakDocumentContext;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import org.antlr.v4.runtime.*;

/**
 * Front-end entry point: parse a RegelSpraak source, build the typed model
 * (§13.3), then type-check the rules against the typeringen matrix (§13.4 / the
 * typeringen specification). Syntax errors and type errors land in
 * {@link RegelSpraakModel#diagnostics}.
 */
public final class Resolver {

    public static RegelSpraakModel resolve(String source) {
        RegelSpraakLexer lexer = new RegelSpraakLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RegelSpraak parser = new RegelSpraak(tokens);

        var syntax = new java.util.ArrayList<Diagnostic>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> r, Object sym, int line, int col,
                                              String msg, RecognitionException e) {
                syntax.add(new Diagnostic(Severity.ERROR, "syntaxfout: " + msg, line));
            }
        };
        lexer.removeErrorListeners(); lexer.addErrorListener(listener);
        parser.removeErrorListeners(); parser.addErrorListener(listener);

        RegelSpraakDocumentContext doc = parser.regelSpraakDocument();
        RegelSpraakModel model = new DefinitionResolver().build(doc);
        model.diagnostics.addAll(syntax);

        new RuleChecker(model).check(doc);
        return model;
    }

    private Resolver() {}
}
