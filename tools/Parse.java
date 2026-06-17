import io.github.yhavinga.regelspraak.parser.RegelSpraak;
import io.github.yhavinga.regelspraak.parser.RegelSpraakLexer;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal parse harness for the RegelSpraak grammar.
 *
 * ANTLR's bundled TestRig discovers a parser by suffixing the grammar name with
 * "Parser" (RegelSpraak -> RegelSpraakParser), but a `parser grammar RegelSpraak;`
 * emits a class named exactly `RegelSpraak`. Rather than rename the grammar file
 * away from the spec-mandated grammar/RegelSpraak.g4, this harness loads the
 * lexer and parser directly. It also does what TestRig cannot: report lexer/parser
 * syntax errors AND count `error` nodes, and exit non-zero if the parse is not
 * clean — exactly the pass/fail signal the corpus verifier needs.
 *
 * Usage: Parse <startRule> <file> [-tree]
 *   prints the parse tree (LISP form) on stdout when -tree is given (default on),
 *   prints diagnostics on stderr, exits 1 if any syntax error or error node.
 */
public final class Parse {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Parse <startRule> <file> [-tree|-tokens]");
            System.exit(2);
        }
        String rule = args[0];
        Path file = Paths.get(args[1]);
        boolean showTokens = Arrays.asList(args).contains("-tokens");
        boolean showTree = !Arrays.asList(args).contains("-no-tree");

        final List<String> errors = new ArrayList<>();
        BaseErrorListener listener = new BaseErrorListener() {
            @Override public void syntaxError(Recognizer<?, ?> rec, Object sym, int line,
                                              int col, String msg, RecognitionException e) {
                errors.add("line " + line + ":" + col + " " + msg);
            }
        };

        CharStream cs = CharStreams.fromPath(file);
        RegelSpraakLexer lexer = new RegelSpraakLexer(cs);
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        if (showTokens) {
            tokens.fill();
            for (Token t : tokens.getTokens()) {
                System.out.println(t.getLine() + ":" + t.getCharPositionInLine()
                        + " " + RegelSpraakLexer.VOCABULARY.getSymbolicName(t.getType())
                        + " '" + t.getText().replace("\n", "\\n") + "'");
            }
        }

        RegelSpraak parser = new RegelSpraak(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        Method m;
        try {
            m = RegelSpraak.class.getMethod(rule);
        } catch (NoSuchMethodException nsme) {
            System.err.println("no such start rule: " + rule);
            System.exit(2);
            return;
        }
        ParseTree tree = (ParseTree) m.invoke(parser);

        int errorNodes = countErrorNodes(tree);
        if (showTree) System.out.println(tree.toStringTree(parser));
        for (String e : errors) System.err.println(e);
        if (!errors.isEmpty() || errorNodes > 0) {
            System.err.println("PARSE FAILED: " + errors.size() + " syntax error(s), "
                    + errorNodes + " error node(s) in rule '" + rule + "'");
            System.exit(1);
        }
    }

    private static int countErrorNodes(ParseTree t) {
        if (t instanceof ErrorNode) return 1;
        int c = 0;
        for (int i = 0; i < t.getChildCount(); i++) c += countErrorNodes(t.getChild(i));
        return c;
    }
}
