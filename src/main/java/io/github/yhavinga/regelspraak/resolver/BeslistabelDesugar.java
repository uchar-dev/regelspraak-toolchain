package io.github.yhavinga.regelspraak.resolver;

import java.util.ArrayList;
import java.util.List;

/**
 * §12 BESLISTABEL desugar (Phase-1 of the table-body gap, card 20260624-beslistabel-tabelbody-desugar): turn a
 * captured §12 decision-table into ONE ordinary RegelSpraak {@code Regel} source string per (data-row ×
 * conclusie-column). Each produced string is fed BACK through the existing {@link Resolver} — so a beslistabel reuses
 * the WHOLE rule pipeline (§9.1 gelijkstelling / §9.2 kenmerktoekenning / §5.7 concatenatie / §8 voorwaarden) and
 * BOTH back-ends (DRL + exec-model) get its rows for free, with zero new IR.
 *
 * <p>This is the SEMANTIC core (the surface-syntax capture — an ANTLR lexer sub-mode for the inline markdown pipe
 * table, the user-chosen Option A — is a later increment). It takes the table ALREADY split into header cells + data
 * rows (a row's cell[0] is the §12 row numbering, used only to name the synthetic regel).
 *
 * <p><b>§12 column model</b> (see the card / main-spec §12): column 0 is the row numbering (ignored except for the
 * name). A header that starts with {@code "indien"} is a CONDITIE-kolom (its cell is the predicate's right operand,
 * or {@code "n.v.t."} = omit that condition for this row); every other header is a CONCLUSIE-kolom (a §9.1
 * gelijkstelling header ending in "moet gesteld worden op", or a §9.2 kenmerk header "... is ...", whose cell is the
 * value / "waar" / "onwaar"). Conclusie-kolommen may sit before OR after the conditie-kolommen. Per row: one regel
 * per conclusie-kolom, voorwaarde = the AND of the non-n.v.t. conditie cells; a multi-value "of"-cell under "gelijk
 * is aan" stays verbatim (the resolver's §5.7 concatenatie ORs it).
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

                // the AND of the non-n.v.t. condition cells, each "<conditie-header> <cell>" (the header carries "indien").
                List<String> condities = new ArrayList<>();
                for (int dc : conditieKol) {
                    String cell = cel(row, dc).trim();
                    if (isNvt(cell)) continue;
                    condities.add(headers.get(dc).trim() + " " + cell);
                }

                StringBuilder body = new StringBuilder(resultaat);
                if (condities.size() == 1) {
                    body.append(' ').append(condities.get(0));
                } else if (condities.size() > 1) {
                    body.append(" indien hij aan alle volgende voorwaarden voldoet:");
                    for (String cond : condities) body.append("\n      • ").append(stripIndien(cond));
                }

                // name: "<naam> <rij>" (+ a conclusie-column suffix only when a row yields more than one regel).
                String regelNaam = naam + (nr.isEmpty() ? "" : " " + nr) + (conclusieKol.size() > 1 ? " (" + (ci + 1) + ")" : "");
                uit.add("Regel " + regelNaam + "\n  geldig " + geldig + "\n    " + body + ".\n");
            }
        }
        return uit;
    }

    /** A conclusie cell -> its resultaatdeel source. A §9.1 gelijkstelling header ("… moet gesteld worden op") takes
     *  the cell as its value; a §9.2 kenmerk header ("een X is …") takes "waar" (assert it) / "onwaar" (null: assert
     *  nothing — the §12 negative form is left to a later refinement). */
    private static String resultaatdeel(String header, String cell) {
        if (header.toLowerCase().contains("moet gesteld worden op")) return header + " " + cell;
        // a kenmerk conclusie: the header IS the assertion ("een passagier is een passagier jonger dan 18 jaar").
        if (cell.equalsIgnoreCase("waar")) return header;
        if (cell.equalsIgnoreCase("onwaar")) return null;
        return header + " " + cell;   // a non-boolean cell under a non-gelijkstelling header: append it (defensive)
    }

    /** Strip a leading "indien " for a bullet inside an "alle volgende voorwaarden" list (the bullets carry no "indien"). */
    private static String stripIndien(String cond) {
        return cond.regionMatches(true, 0, "indien ", 0, 7) ? cond.substring(7) : cond;
    }
    private static boolean isNvt(String cell) {
        String c = cell.replace(".", "").trim();
        return c.equalsIgnoreCase("nvt") || c.isEmpty();
    }
    private static String cel(List<String> row, int i) { return i < row.size() ? row.get(i) : ""; }
}
