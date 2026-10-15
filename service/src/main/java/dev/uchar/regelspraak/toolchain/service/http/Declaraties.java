package dev.uchar.regelspraak.toolchain.service.http;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Benoembaar;
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
 *   <li>any other element with more than one, or a member with more than one in a head: {@link Onkoppelbaar}.</li>
 * </ul>
 * A declaration that couples to no element is {@link Onkoppelbaar} as well, except a rule: a rule that becomes no
 * named form on the wire has no element (uchar's {@code Benamingen.keur} names a day kind, a start point and an
 * allocation without a method).
 */
final class Declaraties {

    private Declaraties() {}

    /** Where a declaration stands: its source line, and the comment lines directly above it, each without its
     *  {@code //} and stripped. */
    record Declaratie(int regel, List<String> commentaar) {}

    /** An element that couples to no single declaration, or a declaration that couples to no element. */
    static final class Onkoppelbaar extends RuntimeException {
        Onkoppelbaar(String zin) { super(zin); }
    }

    /** A declaration as the walk finds it: what it declares, whether it stands in an extension, and its context. */
    private record Gevonden(Benoembaar element, boolean inExtensie, ParserRuleContext ctx) {}

    /**
     * The declaration of every element of {@code model} that has one in {@code document}, the tree uchar's parser
     * built over {@code tokens}.
     *
     * @throws Onkoppelbaar an element or a declaration that does not couple (see the class comment)
     */
    static Map<Benoembaar, Declaratie> van(ParserRuleContext document, CommonTokenStream tokens,
                                           RegelSpraakModel model) {
        List<Gevonden> gevonden = new ArrayList<>();
        for (ParseTree kind : document.children == null ? List.<ParseTree>of() : document.children) {
            if (kind instanceof DefinitieContext d) definitie(d, gevonden);
            else if (kind instanceof RegelContext r) regel(r, gevonden);
            else if (kind instanceof RegelGroepContext g) {
                gevonden.add(new Gevonden(Benoembaar.regelgroep(src(g.kaleNaam())), false, g));
                for (GroepLidContext lid : g.groepLid()) if (lid.regel() != null) regel(lid.regel(), gevonden);
            } else if (kind instanceof UitzonderingGroepContext ug) {
                for (RegelContext r : ug.regel()) regel(r, gevonden);
            }
        }
        Map<Benoembaar, Declaratie> uit = new LinkedHashMap<>();
        Set<Gevonden> gekoppeld = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Benoembaar b : model.benoembaar()) {
            List<Gevonden> kandidaten = gevonden.stream().filter(g -> zelfde(g.element(), b)).toList();
            if (kandidaten.isEmpty()) continue;
            gekoppeld.addAll(kandidaten);
            ParserRuleContext ctx = keuze(b, kandidaten);
            uit.put(b, new Declaratie(ctx.getStart().getLine(), commentaar(ctx, tokens)));
        }
        for (Gevonden g : gevonden)
            if (!gekoppeld.contains(g) && g.element().soort() != Benoembaar.Soort.VORM)
                throw new Onkoppelbaar(beschrijving(g.element()) + " op regel " + g.ctx().getStart().getLine()
                        + " is geen element van het model");
        return uit;
    }

    private static void definitie(DefinitieContext d, List<Gevonden> uit) {
        if (d.objectTypeDefinition() != null) {
            ObjectTypeDefinitionContext kop = d.objectTypeDefinition();
            String type = src(kop.objectTypeNaam());
            uit.add(new Gevonden(Benoembaar.objecttype(type), false, kop));
            for (ObjectTypeMemberContext m : kop.objectTypeMember()) lid(type, m, false, uit);
        } else if (d.objectTypeExtensie() != null) {
            ObjectTypeExtensieContext extensie = d.objectTypeExtensie();
            String type = src(extensie.objectTypeNaam());
            for (ObjectTypeMemberContext m : extensie.objectTypeMember()) lid(type, m, true, uit);
        } else if (d.domeinDefinition() != null && d.domeinDefinition().enumeratieSpecificatie() != null) {
            DomeinDefinitionContext domein = d.domeinDefinition();
            String naam = src(domein.domeinNaam());
            for (EnumeratieWaardeContext w : domein.enumeratieSpecificatie().enumeratieWaarde()) {
                String t = w.ENUM_LITERAL().getText();
                uit.add(new Gevonden(Benoembaar.enumeratiewaarde(naam, t.substring(1, t.length() - 1)), false, w));
            }
        } else if (d.parameterDefinition() != null) {
            ParameterDefinitionContext p = d.parameterDefinition();
            uit.add(new Gevonden(Benoembaar.parameter(src(p.parameterNaam())), false, p));
        } else if (d.feitTypeDefinition() != null) {
            FeitTypeDefinitionContext ft = d.feitTypeDefinition();
            String naam = src(ft.feitTypeNaam());
            uit.add(new Gevonden(Benoembaar.feittype(naam), false, ft));
            for (RolSpecificatieContext r : ft.rolSpecificatie())
                uit.add(new Gevonden(Benoembaar.rol(naam, src(r.rolNaam())), false, r));
        }
    }

    private static void lid(String type, ObjectTypeMemberContext m, boolean extensie, List<Gevonden> uit) {
        if (m.attribuutSpecificatie() != null) {
            AttribuutSpecificatieContext a = m.attribuutSpecificatie();
            uit.add(new Gevonden(Benoembaar.attribuut(type, src(a.lowerNaam())), extensie, a));
        } else if (m.kenmerkSpecificatie() instanceof BijvoeglijkKenmerkContext k) {
            uit.add(new Gevonden(Benoembaar.kenmerk(type, src(k.lowerNaam())), extensie, k));
        } else if (m.kenmerkSpecificatie() instanceof NaamwoordKenmerkContext k) {
            uit.add(new Gevonden(Benoembaar.kenmerk(type, src(k.lowerNaam())), extensie, k));
        } else if (m.kenmerkSpecificatie() != null) {
            throw new IllegalStateException("een kenmerkdeclaratie van een onbekende vorm ("
                    + m.kenmerkSpecificatie().getClass().getSimpleName() + ") op regel " + m.getStart().getLine());
        }
    }

    private static void regel(RegelContext r, List<Gevonden> uit) {
        uit.add(new Gevonden(Benoembaar.vorm(src(r.regelNaam())), false, r));
    }

    /** The declaration of {@code b} among its {@code kandidaten}, which are in document order. */
    private static ParserRuleContext keuze(Benoembaar b, List<Gevonden> kandidaten) {
        if (kandidaten.size() == 1) return kandidaten.get(0).ctx();
        boolean lid = b.soort() == Benoembaar.Soort.ATTRIBUUT || b.soort() == Benoembaar.Soort.KENMERK;
        List<Gevonden> inKop = kandidaten.stream().filter(g -> !g.inExtensie()).toList();
        if (lid && inKop.size() == 1) return inKop.get(0).ctx();
        if (lid && inKop.isEmpty()) return kandidaten.get(0).ctx();
        throw new Onkoppelbaar(hoofd(beschrijving(b)) + " heeft declaraties op de regels "
                + kandidaten.stream().map(g -> String.valueOf(g.ctx().getStart().getLine())).toList()
                + ", en uchar geeft niet aan welke daarvan het model draagt");
    }

    /**
     * The comment directly above {@code ctx}: the line comments to the left of its first token, on the lines right
     * above its own, up to the first line that is no comment line or is empty. A comment behind the token before
     * them (on that token's line) ends the run: its line is no comment line.
     */
    static List<String> commentaar(ParserRuleContext ctx, CommonTokenStream tokens) {
        Token start = ctx.getStart();
        int index = start.getTokenIndex();
        if (index < 0 || index >= tokens.size() || tokens.get(index) != start)
            throw new IllegalStateException("de declaratie op regel " + start.getLine()
                    + " staat niet in de tokenstroom van haar parse");
        List<Token> verborgen = tokens.getHiddenTokensToLeft(index);
        if (verborgen == null) return List.of();
        int eerste = verborgen.get(0).getTokenIndex();
        int vorigeRegel = eerste == 0 ? 0 : eindregel(tokens.get(eerste - 1));
        LinkedList<String> uit = new LinkedList<>();
        int verwacht = start.getLine() - 1;
        for (int i = verborgen.size() - 1; i >= 0; i--) {
            Token t = verborgen.get(i);
            if (t.getType() != RegelSpraakLexer.LINE_COMMENT || t.getLine() != verwacht || t.getLine() <= vorigeRegel)
                break;
            uit.addFirst(t.getText().substring(2).strip());
            verwacht--;
        }
        return List.copyOf(uit);
    }

    /** The line of the last character of {@code t}. */
    private static int eindregel(Token t) {
        String tekst = t.getText();
        return t.getLine() + (tekst == null ? 0 : (int) tekst.chars().filter(c -> c == '\n').count());
    }

    /** uchar's name identity of two elements: the kind, and owner and name case-insensitively. */
    private static boolean zelfde(Benoembaar a, Benoembaar b) {
        return a.soort() == b.soort() && a.naam().equalsIgnoreCase(b.naam())
                && (a.eigenaar() == null ? b.eigenaar() == null
                    : b.eigenaar() != null && a.eigenaar().equalsIgnoreCase(b.eigenaar()));
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

    private static String beschrijving(Benoembaar b) {
        return switch (b.soort()) {
            case OBJECTTYPE -> "het objecttype '" + b.naam() + "'";
            case ATTRIBUUT -> "het attribuut '" + b.naam() + "' van '" + b.eigenaar() + "'";
            case KENMERK -> "het kenmerk '" + b.naam() + "' van '" + b.eigenaar() + "'";
            case FEITTYPE -> "het feittype '" + b.naam() + "'";
            case ROL -> "de rol '" + b.naam() + "' van '" + b.eigenaar() + "'";
            case ENUMERATIEWAARDE -> "de enumeratiewaarde '" + b.naam() + "' van '" + b.eigenaar() + "'";
            case PARAMETER -> "de parameter '" + b.naam() + "'";
            case VORM -> "de regel '" + b.naam() + "'";
            case REGELGROEP -> "de regelgroep '" + b.naam() + "'";
        };
    }

    private static String hoofd(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
