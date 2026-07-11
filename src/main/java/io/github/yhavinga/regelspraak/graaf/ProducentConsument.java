package io.github.yhavinga.regelspraak.graaf;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The pure-IR producer/consumer analysis over a resolved {@link RegelSpraakModel}: which rule WRITES which
 * kenmerk/attribuut ({@link #producentIndex}) and which kenmerken/attributen a rule READS ({@link #verbruikt}).
 *
 * <p>This is the analysis {@code BeslisserKern}'s scope-completeness validation performs; it is lifted here into
 * the jar-only parser module (no Drools dependency — it walks the IR only) so it is a SINGLE authority shared by
 * the service's scope check and the {@link RegelGraaf} dataflow-graph builder. The element identity is
 * case-folded so a case-only spelling difference (the resolver's own tolerance) still matches producer to
 * consumer.
 */
public final class ProducentConsument {

    private ProducentConsument() {}

    /** The identity of a consumed/produced model element: the objecttype it lives on (canonical name, or ""
     *  when the resolver could not bind the owner), its kenmerk/attribuut naam, and whether it is a kenmerk
     *  (§13.4.5 boolean property) rather than an ordinary attribuut. Case-folded on objecttype and naam. */
    public record ElementSleutel(String objecttype, String naam, boolean kenmerk) {
        public static ElementSleutel van(String objecttype, String naam, boolean kenmerk) {
            return new ElementSleutel(objecttype == null ? "" : objecttype.toLowerCase(Locale.ROOT),
                    naam == null ? "" : naam.trim().toLowerCase(Locale.ROOT), kenmerk);
        }
    }

    /**
     * The model-wide producer index: for every value-writing {@link ResolvedRegel} (a kenmerktoekenning,
     * gelijkstelling, berekening or initialisatie), map the element it WRITES (its {@code doel}) to the set of
     * rule base-names that write it. A kenmerk and an attribuut of the same spelling are distinct keys.
     */
    public static Map<ElementSleutel, Set<String>> producentIndex(RegelSpraakModel model) {
        Map<ElementSleutel, Set<String>> uit = new LinkedHashMap<>();
        for (ResolvedRegel r : model.resolvedRegels) {
            if (!(r.doel() instanceof Expr.AttribuutRef ref)) continue;
            boolean kenmerk = r.soort() == ResolvedRegel.Soort.KENMERKTOEKENNING;
            String ot = ref.objecttype() == null ? "" : ref.objecttype().naam;
            uit.computeIfAbsent(ElementSleutel.van(ot, ref.attribuut(), kenmerk),
                    k -> new LinkedHashSet<>()).add(basisNaam(r.naam()));
        }
        return uit;
    }

    /** The kenmerken/attributen a resolved rule CONSUMES: the kenmerk-membership tests + operand attribuut-reads
     *  of its voorwaarde, plus every attribuut-read in its RHS {@code waarde}. The rule's OWN write target
     *  ({@code doel}) is excluded — a rule reading what it writes is not a cross-rule dependency. */
    public static Set<ElementSleutel> verbruikt(ResolvedRegel r) {
        Set<ElementSleutel> uit = new LinkedHashSet<>();
        String subject = r.doel() instanceof Expr.AttribuutRef ref && ref.objecttype() != null
                ? ref.objecttype().naam : "";
        verzamelConditie(r.voorwaarde(), subject, uit);
        verzamelExpr(r.waarde(), uit);
        return uit;
    }

    /** Collect consumed kenmerken (from {@link Conditie.Kenmerk}) and operand attributen out of a condition tree.
     *  A subject-own kenmerk (no navigatie, no eigenaar) is attributed to {@code subject}; a kenmerk with a bound
     *  {@code eigenaar} to that owner; a navigated kenmerk stays owner-unknown ("") (matched producer-side by
     *  naam only). */
    private static void verzamelConditie(Conditie c, String subject, Set<ElementSleutel> uit) {
        if (c == null) return;
        if (c instanceof Conditie.Kenmerk k) {
            String ot = k.eigenaar() != null ? k.eigenaar().naam : (k.navigatie() == null ? subject : "");
            uit.add(ElementSleutel.van(ot, k.kenmerk(), true));
        }
        if (c instanceof Conditie.Samengesteld s)
            for (Conditie d : s.onderdelen()) verzamelConditie(d, subject, uit);
        // operand attribuut-reads inside comparisons/predicates/periodes
        Conditie.bevatOperand(c, e -> { verzamelExpr(e, uit); return false; });
    }

    /** Collect the attribuut-reads ({@link Expr.AttribuutRef}, kenmerk-flag false) out of an expression tree
     *  via the generic IR scan. */
    private static void verzamelExpr(Expr e, Set<ElementSleutel> uit) {
        Expr.bevat(e, x -> {
            if (x instanceof Expr.AttribuutRef ref) {
                String ot = ref.objecttype() == null ? "" : ref.objecttype().naam;
                uit.add(ElementSleutel.van(ot, ref.attribuut(), false));
            }
            return false;
        });
    }

    /** Strip a §9.10 "&lt;groep&gt;: " prefix and a §13.4.2.3 " #k" version suffix (and a beslistabel " (n)"
     *  multi-conclusie suffix) off an emitted rule name — the base name the index, the scope, and the transpiler
     *  dekking all agree on. Mirrors {@code ScenarioRunner.basisNaam} + {@code RegelBestandIndex.basisNaam} +
     *  {@code BeslisserKern.basisNaam}. */
    public static String basisNaam(String naam) {
        if (naam == null) return "";
        String n = naam;
        int hash = n.indexOf(" #");
        if (hash > 0) n = n.substring(0, hash);
        int colon = n.indexOf(": ");
        if (colon > 0) n = n.substring(colon + 2);
        n = n.replaceFirst("\\s*\\(\\d+\\)\\s*$", "");
        return n.trim();
    }
}
