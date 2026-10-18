package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Designatable;
import dev.uchar.regelspraak.parser.RegelSpraak.AttribuutSpecificatieContext;
import dev.uchar.regelspraak.parser.RegelSpraak.BijvoeglijkKenmerkContext;
import dev.uchar.regelspraak.parser.RegelSpraak.DefinitieContext;
import dev.uchar.regelspraak.parser.RegelSpraak.DomeinDefinitionContext;
import dev.uchar.regelspraak.parser.RegelSpraak.EnumeratieWaardeContext;
import dev.uchar.regelspraak.parser.RegelSpraak.FeitTypeDefinitionContext;
import dev.uchar.regelspraak.parser.RegelSpraak.GroepLidContext;
import dev.uchar.regelspraak.parser.RegelSpraak.NaamwoordKenmerkContext;
import dev.uchar.regelspraak.parser.RegelSpraak.ObjectTypeDefinitionContext;
import dev.uchar.regelspraak.parser.RegelSpraak.ObjectTypeExtensieContext;
import dev.uchar.regelspraak.parser.RegelSpraak.ObjectTypeMemberContext;
import dev.uchar.regelspraak.parser.RegelSpraak.ParameterDefinitionContext;
import dev.uchar.regelspraak.parser.RegelSpraak.RegelContext;
import dev.uchar.regelspraak.parser.RegelSpraak.RegelGroepContext;
import dev.uchar.regelspraak.parser.RegelSpraak.RolSpecificatieContext;
import dev.uchar.regelspraak.parser.RegelSpraak.UitzonderingGroepContext;
import dev.uchar.regelspraak.parser.RegelSpraakLexer;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each declaration a vocabulary can name stands in the source, read from uchar's parse of it: the line of the
 * declaration and the comment written directly above it. uchar's lexer puts a line comment on the HIDDEN channel
 * ({@code RegelSpraakLexer.g4}, {@code LINE_COMMENT}), so the model carries no comment; the token stream of uchar's
 * parser does, and the comment above a declaration is read with {@code getHiddenTokensToLeft} at its first token, as
 * uchar's rule checker reads the {@code // Bron:} annotations above a rule ({@code RuleChecker.bronAnnotaties}).
 *
 * <p><b>The declarations</b> are the ones uchar's designation pass reads ({@code resolver/Benamingen}, pass 1): an
 * object type's head, the attributes and characteristics of a head and of an extension, the values of an enumeration
 * domain, a parameter, a fact type and its roles, a rule (by its rule name, which is its form name) and a rule group.
 * Each is keyed as that pass keys it: its kind, its owner (for a member of an extension, the type the extension
 * names) and its name, the source slice of its name context with the whitespace collapsed
 * ({@code DefinitionResolver.src}, which is not public in uchar and is one line here). Keys compare under uchar's name
 * identity: the kind, and owner and name case-insensitively.
 *
 * <p><b>Coupling</b> an element of {@link RegelSpraakModel#benoembaar()} to its declarations:
 * <ul>
 *   <li>no declaration: uchar's frontend wrote the element itself (a row of a decision table, a defaults group, the
 *       attributes, the characteristic and the forms an opgave marker adds, an object type that only an extension
 *       names); it has no line and no comment of its own;</li>
 *   <li>one: that one;</li>
 *   <li>an attribute or characteristic declared more than once: the declaration the type carries, as uchar chooses it
 *       ({@code DefinitionResolver.bestaande}): the one in the head, else the first in document order. An extension
 *       repeats a member the type carries only word for word, which uchar checks (WF-002, dialect §7);</li>
 *   <li>any other element with more than one, or a member with more than one in a head: {@link Unlinkable}.</li>
 * </ul>
 * A declaration that couples to no element is {@link Unlinkable} as well, except a rule: a rule that becomes no
 * named form on the wire has no element (uchar's {@code Benamingen.keur} names a day kind, a start point and an
 * allocation without a method).
 */
final class Declarations {

    private Declarations() {}

    /** Where a declaration stands: its source line, and the comment lines directly above it, each without its
     *  {@code //} and stripped. */
    record Declaration(int line, List<String> comment) {}

    /** An element that couples to no single declaration, or a declaration that couples to no element. */
    static final class Unlinkable extends RuntimeException {
        Unlinkable(String sentence) { super(sentence); }
    }

    /** A declaration as the walk finds it: what it declares, whether it stands in an extension, and its context. */
    private record Found(Designatable element, boolean inExtension, ParserRuleContext ctx) {}

    /**
     * The declaration of every element of {@code model} that has one in {@code document}, the tree uchar's parser
     * built over {@code tokens}.
     *
     * @throws Onkoppelbaar an element or a declaration that does not couple (see the class comment)
     */
    static Map<Designatable, Declaration> van(ParserRuleContext document, CommonTokenStream tokens,
                                           RegelSpraakModel model) {
        List<Found> found = new ArrayList<>();
        for (ParseTree kind : document.children == null ? List.<ParseTree>of() : document.children) {
            if (kind instanceof DefinitieContext d) definition(d, found);
            else if (kind instanceof RegelContext r) line(r, found);
            else if (kind instanceof RegelGroepContext g) {
                found.add(new Found(Designatable.ruleGroup(src(g.kaleNaam())), false, g));
                for (GroepLidContext member : g.groepLid()) if (member.regel() != null) line(member.regel(), found);
            } else if (kind instanceof UitzonderingGroepContext ug) {
                for (RegelContext r : ug.regel()) line(r, found);
            }
        }
        Map<Designatable, Declaration> sink = new LinkedHashMap<>();
        Set<Found> linked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Designatable b : model.designatable()) {
            List<Found> candidates = found.stream().filter(g -> same(g.element(), b)).toList();
            if (candidates.isEmpty()) continue;
            linked.addAll(candidates);
            ParserRuleContext ctx = choice(b, candidates);
            sink.put(b, new Declaration(ctx.getStart().getLine(), comment(ctx, tokens)));
        }
        for (Found g : found)
            if (!linked.contains(g) && g.element().category() != Designatable.Kind.VORM)
                throw new Unlinkable(description(g.element()) + " op regel " + g.ctx().getStart().getLine()
                        + " is geen element van het model");
        return sink;
    }

    private static void definition(DefinitieContext d, List<Found> sink) {
        if (d.objectTypeDefinition() != null) {
            ObjectTypeDefinitionContext head = d.objectTypeDefinition();
            String type = src(head.objectTypeNaam());
            sink.add(new Found(Designatable.objectType(type), false, head));
            for (ObjectTypeMemberContext m : head.objectTypeMember()) member(type, m, false, sink);
        } else if (d.objectTypeExtensie() != null) {
            ObjectTypeExtensieContext extension = d.objectTypeExtensie();
            String type = src(extension.objectTypeNaam());
            for (ObjectTypeMemberContext m : extension.objectTypeMember()) member(type, m, true, sink);
        } else if (d.domeinDefinition() != null && d.domeinDefinition().enumeratieSpecificatie() != null) {
            DomeinDefinitionContext domain = d.domeinDefinition();
            String name = src(domain.domeinNaam());
            for (EnumeratieWaardeContext w : domain.enumeratieSpecificatie().enumeratieWaarde()) {
                String t = w.ENUM_LITERAL().getText();
                sink.add(new Found(Designatable.enumerationValue(name, t.substring(1, t.length() - 1)), false, w));
            }
        } else if (d.parameterDefinition() != null) {
            ParameterDefinitionContext p = d.parameterDefinition();
            sink.add(new Found(Designatable.parameter(src(p.parameterNaam())), false, p));
        } else if (d.feitTypeDefinition() != null) {
            FeitTypeDefinitionContext ft = d.feitTypeDefinition();
            String name = src(ft.feitTypeNaam());
            sink.add(new Found(Designatable.factType(name), false, ft));
            for (RolSpecificatieContext r : ft.rolSpecificatie())
                sink.add(new Found(Designatable.role(name, src(r.rolNaam())), false, r));
        }
    }

    private static void member(String type, ObjectTypeMemberContext m, boolean extension, List<Found> sink) {
        if (m.attribuutSpecificatie() != null) {
            AttribuutSpecificatieContext a = m.attribuutSpecificatie();
            sink.add(new Found(Designatable.attribute(type, src(a.lowerNaam())), extension, a));
        } else if (m.kenmerkSpecificatie() instanceof BijvoeglijkKenmerkContext k) {
            sink.add(new Found(Designatable.characteristic(type, src(k.lowerNaam())), extension, k));
        } else if (m.kenmerkSpecificatie() instanceof NaamwoordKenmerkContext k) {
            sink.add(new Found(Designatable.characteristic(type, src(k.lowerNaam())), extension, k));
        } else if (m.kenmerkSpecificatie() != null) {
            throw new IllegalStateException("een kenmerkdeclaratie van een onbekende vorm ("
                    + m.kenmerkSpecificatie().getClass().getSimpleName() + ") op regel " + m.getStart().getLine());
        }
    }

    private static void line(RegelContext r, List<Found> sink) {
        sink.add(new Found(Designatable.form(src(r.regelNaam())), false, r));
    }

    /** The declaration of {@code b} among its {@code candidates}, which are in document order. */
    private static ParserRuleContext choice(Designatable b, List<Found> candidates) {
        if (candidates.size() == 1) return candidates.get(0).ctx();
        boolean member = b.category() == Designatable.Kind.ATTRIBUUT || b.category() == Designatable.Kind.KENMERK;
        List<Found> inHead = candidates.stream().filter(g -> !g.inExtension()).toList();
        if (member && inHead.size() == 1) return inHead.get(0).ctx();
        if (member && inHead.isEmpty()) return candidates.get(0).ctx();
        throw new Unlinkable(capitalise(description(b)) + " heeft declaraties op de regels "
                + candidates.stream().map(g -> String.valueOf(g.ctx().getStart().getLine())).toList()
                + ", en uchar geeft niet aan welke daarvan het model draagt");
    }

    /**
     * The comment directly above {@code ctx}: the line comments to the left of its first token, on the lines right
     * above its own, up to the first line that is no comment line or is empty. A comment behind the token before
     * them (on that token's line) ends the run: its line is no comment line.
     */
    static List<String> comment(ParserRuleContext ctx, CommonTokenStream tokens) {
        Token start = ctx.getStart();
        int index = start.getTokenIndex();
        if (index < 0 || index >= tokens.size() || tokens.get(index) != start)
            throw new IllegalStateException("de declaratie op regel " + start.getLine()
                    + " staat niet in de tokenstroom van haar parse");
        List<Token> hidden = tokens.getHiddenTokensToLeft(index);
        if (hidden == null) return List.of();
        int first = hidden.get(0).getTokenIndex();
        int previousLine = first == 0 ? 0 : endLine(tokens.get(first - 1));
        LinkedList<String> sink = new LinkedList<>();
        int expected = start.getLine() - 1;
        for (int i = hidden.size() - 1; i >= 0; i--) {
            Token t = hidden.get(i);
            if (t.getType() != RegelSpraakLexer.LINE_COMMENT || t.getLine() != expected || t.getLine() <= previousLine)
                break;
            sink.addFirst(t.getText().substring(2).strip());
            expected--;
        }
        return List.copyOf(sink);
    }

    /** The line of the last character of {@code t}. */
    private static int endLine(Token t) {
        String text = t.getText();
        return t.getLine() + (text == null ? 0 : (int) text.chars().filter(c -> c == '\n').count());
    }

    /** uchar's name identity of two elements: the kind, and owner and name case-insensitively. */
    private static boolean same(Designatable a, Designatable b) {
        return a.category() == b.category() && a.naam().equalsIgnoreCase(b.naam())
                && (a.owner() == null ? b.owner() == null
                    : b.owner() != null && a.owner().equalsIgnoreCase(b.owner()));
    }

    /** The source slice of {@code ctx} with its whitespace collapsed: the name as uchar's resolver reads it
     *  ({@code DefinitionResolver.src}). */
    private static String src(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null || ctx.getStop() == null) return "";
        if (ctx.getStop().getStopIndex() < ctx.getStart().getStartIndex()) return "";
        String t = ctx.getStart().getInputStream()
                .getText(Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex()));
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String description(Designatable b) {
        return switch (b.category()) {
            case OBJECTTYPE -> "het objecttype '" + b.naam() + "'";
            case ATTRIBUUT -> "het attribuut '" + b.naam() + "' van '" + b.owner() + "'";
            case KENMERK -> "het kenmerk '" + b.naam() + "' van '" + b.owner() + "'";
            case FEITTYPE -> "het feittype '" + b.naam() + "'";
            case ROL -> "de rol '" + b.naam() + "' van '" + b.owner() + "'";
            case ENUMERATIEWAARDE -> "de enumeratiewaarde '" + b.naam() + "' van '" + b.owner() + "'";
            case PARAMETER -> "de parameter '" + b.naam() + "'";
            case VORM -> "de regel '" + b.naam() + "'";
            case REGELGROEP -> "de regelgroep '" + b.naam() + "'";
        };
    }

    private static String capitalise(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
