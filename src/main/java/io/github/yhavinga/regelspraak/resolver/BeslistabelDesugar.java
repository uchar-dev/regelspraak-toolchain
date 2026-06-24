package io.github.yhavinga.regelspraak.resolver;

import java.util.ArrayList;
import java.util.List;

/**
 * §12 BESLISTABEL desugar (the table-body gap, card 20260624-beslistabel-tabelbody-desugar): turn a captured §12
 * decision-table into ONE ordinary RegelSpraak {@code Regel} source string per (data-row × conclusie-column). Each
 * produced string is fed BACK through the existing {@link Resolver} — so a beslistabel reuses the WHOLE rule pipeline
 * (§9.1 gelijkstelling / §9.2 kenmerktoekenning / §5.7 concatenatie / §8 voorwaarden) and BOTH back-ends (DRL +
 * exec-model) get its rows for free, with zero new IR.
 *
 * <p>It takes the table ALREADY split into header cells + data rows (the ANTLR lexer captures each inline markdown
 * pipe line as a {@code TABEL_RIJ} token; {@link RuleChecker} splits it on '|'). A row's cell[0] is the §12 row
 * numbering, used only to name the synthetic regel.
 *
 * <p><b>§12 column model</b> (main-spec §12, L4633-4766): column 0 is the row numbering. A header that starts with
 * {@code "indien"} is a CONDITIE-kolom; every other header is a CONCLUSIE-kolom (a §9.1 gelijkstelling header ending
 * in "moet gesteld worden op", or a §9.2 kenmerk header "… is …"). Conclusie-kolommen may sit before OR after the
 * conditie-kolommen. Per row: one regel per conclusie-kolom, voorwaarde = the AND of the non-n.v.t. conditie cells.
 *
 * <p><b>§5.7 concatenatie cell</b>: a conditie cell under an equality predicate may list several values separated by a
 * comma and a final " of " ("A", "B" of "C") — a DISJUNCTION over that one column. The spec presents the very same
 * Woonregio rules hand-written with this exact disjunction as an "er aan tenminste één van de volgende voorwaarden
 * wordt voldaan" list (L4654-4678), so a multi-value cell desugars to that §13.4.14 OR-list (reusing the resolver's
 * existing compound-condition support) rather than a verbatim value-series (which the resolver does not lower). Net
 * row shape: AND across conditie-kolommen, OR within a multi-value cell; a disjunction inside an AND nests as a
 * §13.4.14.2 "er voldoet aan tenminste één …" sub-list and is reordered LAST so its greedy bullet capture is harmless.
 *
 * <p><b>Cell value syntax</b>: cells hold native RegelSpraak rule-fragment text — text literals are DOUBLE-quoted
 * ("Friesland"), as a hand-written regel would write them. (The spec's printed tables use single quotes purely as a
 * presentation delimiter; the `.rs` is real RegelSpraak, where '"' opens a tekstwaarde and "'" does not.)
 */
public final class BeslistabelDesugar {
    private BeslistabelDesugar() {}

    /**
     * Desugar a captured beslistabel into ordinary {@code Regel} source strings (one per data-row × conclusie-column).
     *
     * @param naam    the beslistabel name (each row's regel is named "{@code <naam> <rij-nummer>}")
     * @param geldig  the validity phrase verbatim from the header ("altijd", "vanaf 2024 t/m 2024", …)
     * @param headers the header cells INCLUDING the numbering column at index 0
     * @param rows    the data rows, each a cell list aligned to {@code headers} (cell[0] = the row numbering)
     */
    public static List<String> desugar(String naam, String geldig, List<String> headers, List<List<String>> rows) {
        List<Integer> conclusieKol = new ArrayList<>(), conditieKol = new ArrayList<>();
        for (int c = 1; c < headers.size(); c++) {
            if (headers.get(c).trim().toLowerCase().startsWith("indien")) conditieKol.add(c);
            else conclusieKol.add(c);
        }
        List<String> uit = new ArrayList<>();
        for (List<String> row : rows) {
            String nr = row.isEmpty() ? "" : row.get(0).trim();
            for (int ci = 0; ci < conclusieKol.size(); ci++) {
                int cc = conclusieKol.get(ci);
                String resultaat = resultaatdeel(headers.get(cc).trim(), cel(row, cc).trim());
                if (resultaat == null) continue;   // a kenmerk "onwaar" cell asserts nothing -> no regel for this row/column

                // each non-n.v.t. conditie cell -> a Voorwaarde (a predicate kop + its 0..n alternative values).
                List<Voorwaarde> condities = new ArrayList<>();
                for (int dc : conditieKol) {
                    String cell = cel(row, dc).trim();
                    if (isNvt(cell)) continue;
                    String kop = stripIndien(headers.get(dc).trim());
                    if (cell.equalsIgnoreCase("waar"))         // a kenmerk conditie that holds -> the kop IS the check
                        condities.add(new Voorwaarde(kop, List.of()));
                    else if (cell.equalsIgnoreCase("onwaar"))  // a kenmerk conditie that must NOT hold -> negate the check
                        condities.add(new Voorwaarde(negeerKenmerkKop(kop), List.of()));
                    else                                       // a comparison: the kop + the cell's 1..n §5.7 value(s)
                        condities.add(new Voorwaarde(kop, splitConcatenatie(cell)));
                }

                String body = resultaat + voorwaardeClausule(condities);
                // name: "<naam> <rij>" (+ a conclusie-column suffix only when a row yields more than one regel).
                String regelNaam = naam + (nr.isEmpty() ? "" : " " + nr) + (conclusieKol.size() > 1 ? " (" + (ci + 1) + ")" : "");
                uit.add("Regel " + regelNaam + "\n  geldig " + geldig + "\n    " + body + ".\n");
            }
        }
        return uit;
    }

    /** A single conditie cell: the predicate kop ("zijn woonprovincie gelijk is aan", the header minus "indien") and
     *  its value(s). A §5.7 concatenatie cell ("A, B of C") under an equality predicate yields &gt;1 value. */
    private record Voorwaarde(String kop, List<String> waarden) {
        boolean disjunctie() { return waarden.size() > 1; }
    }

    /** Build the "&nbsp;indien …" clause for a row's conditions (empty if none). One condition → inline (or a §13.4.14
     *  "tenminste één van de volgende voorwaarden" list for a §5.7 disjunction); several → an "alle volgende
     *  voorwaarden" AND-list, any disjunction reordered LAST and nested as a §13.4.14.2 sub-list. */
    private static String voorwaardeClausule(List<Voorwaarde> condities) {
        if (condities.isEmpty()) return "";
        if (condities.size() == 1) {
            Voorwaarde v = condities.get(0);
            return v.disjunctie()
                    ? " indien " + disjunctieLijst(v, "      • ", "er aan tenminste één van de volgende voorwaarden wordt voldaan")
                    : " indien " + elementair(v);
        }
        // AND of several: single-value conditions first, any disjunction last (so its greedy bullet capture is harmless).
        List<Voorwaarde> geordend = new ArrayList<>();
        for (Voorwaarde v : condities) if (!v.disjunctie()) geordend.add(v);
        for (Voorwaarde v : condities) if (v.disjunctie()) geordend.add(v);
        StringBuilder b = new StringBuilder(" indien hij aan alle volgende voorwaarden voldoet:");
        for (Voorwaarde v : geordend) {
            if (v.disjunctie())
                b.append("\n      • ").append(disjunctieLijst(v, "        •• ", "er voldoet aan tenminste één van de volgende voorwaarden"));
            else
                b.append("\n      • ").append(elementair(v));
        }
        return b.toString();
    }

    /** One elementary condition: a kenmerk check (no value — the kop IS the condition) or a comparison "kop value". */
    private static String elementair(Voorwaarde v) {
        return v.waarden().isEmpty() ? v.kop() : v.kop() + " " + v.waarden().get(0);
    }

    /** Negate a kenmerk-check kop (an "onwaar" conditie cell): a §3.4 bezittelijk check "hij een recht … heeft" →
     *  "hij geen recht … heeft"; a §3.3 bijvoeglijk check "hij is X" → "hij is niet X". A kop that matches neither
     *  shape is returned with a leading "niet " (a best-effort the resolver rejects → an honest skip of that row,
     *  not a silent mis-render — "onwaar" conditie cells are outside the §12 worked tables). */
    private static String negeerKenmerkKop(String kop) {
        if (kop.contains(" een ")) return kop.replaceFirst(" een ", " geen ");
        if (kop.contains(" is ")) return kop.replaceFirst(" is ", " is niet ");
        return "niet " + kop;
    }

    /** A disjunction over one cell's values: a §13.4.14 "… volgende voorwaarden[:]" opener + one bullet per value
     *  ("&lt;kop&gt; &lt;value_i&gt;"). The opener word order differs top-level ("er aan tenminste één … wordt
     *  voldaan", §13.4.14.1) vs nested inside an AND ("er voldoet aan tenminste één …", §13.4.14.2). */
    private static String disjunctieLijst(Voorwaarde v, String bullet, String opener) {
        StringBuilder b = new StringBuilder(opener).append(':');
        for (String w : v.waarden()) b.append('\n').append(bullet).append(v.kop()).append(' ').append(w);
        return b.toString();
    }

    /** A conclusie cell → its resultaatdeel source. A §9.1 gelijkstelling header ("… moet gesteld worden op") takes
     *  the cell as its value; a §9.2 kenmerk header ("een X is …") takes "waar" (assert it) / "onwaar" (null: assert
     *  nothing — the §12 negative form is left to a later refinement). */
    private static String resultaatdeel(String header, String cell) {
        if (header.toLowerCase().contains("moet gesteld worden op")) return header + " " + cell;
        // a kenmerk conclusie: the header IS the assertion ("een passagier is een passagier jonger dan 18 jaar").
        if (cell.equalsIgnoreCase("waar")) return header;
        if (cell.equalsIgnoreCase("onwaar")) return null;
        return header + " " + cell;   // a non-boolean cell under a non-gelijkstelling header: append it (defensive)
    }

    /** Split a §5.7 concatenatie cell into its values: on commas and the final " of " (cells never contain '|', and a
     *  value is a double-quoted literal / numeric / attribute reference without an embedded comma). A single-value
     *  cell returns a 1-element list. */
    private static List<String> splitConcatenatie(String cell) {
        List<String> waarden = new ArrayList<>();
        for (String w : cell.split("\\s*,\\s*|\\s+of\\s+")) {
            String t = w.trim();
            if (!t.isEmpty()) waarden.add(t);
        }
        if (waarden.isEmpty()) waarden.add(cell);
        return waarden;
    }

    /** Strip a leading "indien " (the conditie header carries it; a bullet / repeated predicate kop does not). */
    private static String stripIndien(String cond) {
        return cond.regionMatches(true, 0, "indien ", 0, 7) ? cond.substring(7) : cond;
    }
    private static boolean isNvt(String cell) {
        String c = cell.replace(".", "").trim();
        return c.equalsIgnoreCase("nvt") || c.isEmpty();
    }
    private static String cel(List<String> row, int i) { return i < row.size() ? row.get(i) : ""; }
}
