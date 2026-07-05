package io.github.yhavinga.regelspraak.transpiler.kern;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.Geldigheid;
import io.github.yhavinga.regelspraak.model.Parameterset;
import io.github.yhavinga.regelspraak.model.Parameterset.WaardeInterval;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Compile-time consistentiecheck <b>regel-geldigheid × parameter-geldigheid</b> (card fase 3, gate G3). Een regel die
 * geldig is in een periode waarin een gerefereerde parameter GEEN geldige waarde heeft, wordt per-periode gerapporteerd
 * als WARNING — niet als ERROR: de deployment mag die periode legitiem nooit serveren (dan levert de
 * peildatum-gate/sessie-gap-fout dat af), maar het gat moet ZICHTBAAR zijn op /info en in de CLI-samenvatting (fase 4/5).
 *
 * <p><b>Scoping (gepind).</b> Alleen parameters die IN de geladen sets voorkomen worden gecheckt. Een gerefereerde
 * parameter die helemaal niet in de sets zit is NIET dit kanaal: die wordt door het degenererende
 * setGlobal-/descriptor-default-pad geleverd en door de parametergate ({@code ScenarioResolver.parameterGate}) en de
 * descriptor-validatie bewaakt. Zijn er geen sets geladen (of geen parameter uit een regel in de pool), dan draait de
 * check dus vacuüm leeg — geen enkele melding.
 *
 * <p><b>Constructielijst.</b> {@link #controleer} spiegelt EXACT de constructielijst en de {@code Expr}-walk van
 * {@code ScenarioResolver.regelParameters} (zie {@code resolver/ScenarioResolver.java}, ~regel 490): per resolved
 * construct de {@code ParameterRef}-namen via {@link Expr#bevat} over de waarde/doel-{@code Expr}s en via
 * {@link Conditie#bevatOperand} over de voorwaarde/assertie. {@code regelParameters} loopt precies TWEE
 * resolved-collecties af — {@link RegelSpraakModel#resolvedRegels} (waarde + doel + voorwaarde) en
 * {@link RegelSpraakModel#consistenties} (assertie) — en die twee dekken we hier identiek. Wijzigt {@code regelParameters}
 * ooit zijn collectie-set, dan moet deze spiegel meebewegen.
 *
 * <p><b>Versies.</b> Een regel kan meerdere §13.4.2.5-VERSIES hebben (zelfde geschreven naam, verschillende
 * {@link Geldigheid}). Elke versie is een aparte {@link io.github.yhavinga.regelspraak.ir.ResolvedRegel} met een eigen
 * {@code #k}-gesuffixte naam en eigen geldigheid, dus door direct over {@code resolvedRegels} te itereren checken we elke
 * versie afzonderlijk met de versie-{@link Geldigheid} als regelperiode.
 *
 * <p><b>Dekking.</b> De dekking van één parameter is de UNIE van zijn intervallen over alle sets — de fase-1
 * pool-semantiek van {@link Parameterset#pool}. Een gat is een deelperiode van de regelperiode die door geen enkel
 * pool-interval gedekt is; bounds zijn inclusief, {@code null} = open kant. Een regel {@code geldig altijd} × een
 * parameter met alleen 2025-waarden levert dus TWEE open gaten: {@code [open t/m 2024-12-31]} en
 * {@code [2026-01-01 t/m open]}.
 *
 * <p><b>Idiomatiek.</b> Als {@link Dekking} is {@link #controleer} PUUR (het meldt niets) en is {@link #meld} de aparte
 * consumer-aangeroepen methode die deduplicerend een WARNING op het model zet — precies zoals {@code Dekking.meld}.
 *
 * @param regel       de (mogelijk {@code #k}-versie-gesuffixte) regelnaam
 * @param parameter   de gerefereerde parameternaam zoals in de sets/pool
 * @param regelPeriode de {@link Geldigheid} van deze regelversie (de regelperiode)
 * @param ontbrekend  de deelperiodes van {@code regelPeriode} die niet door enig parameter-interval gedekt zijn
 *                    ({@link Geldigheid}-shape, {@code null} = open kant); nooit leeg voor een bestaande {@code PeriodeDekking}
 */
public record PeriodeDekking(String regel, String parameter, Geldigheid regelPeriode, List<Geldigheid> ontbrekend) {

    /**
     * Puur: elke (regel-versie, parameter)-combinatie met minstens één gat tussen de regelperiode en de parameterdekking.
     * Meldt niets — de consumer roept daarna {@link #meld} aan (Dekking-idiomatiek). Alleen parameters die in
     * {@code sets} voorkomen tellen (zie klasse-javadoc: het andere kanaal). Bij lege/afwezige sets is het resultaat
     * altijd leeg.
     */
    public static List<PeriodeDekking> controleer(RegelSpraakModel model, List<Parameterset> sets) {
        List<PeriodeDekking> uit = new ArrayList<>();
        if (model == null || sets == null || sets.isEmpty()) return uit;
        Map<String, List<WaardeInterval>> pool = Parameterset.pool(sets);
        if (pool.isEmpty()) return uit;

        // Spiegelt ScenarioResolver.regelParameters: resolvedRegels (waarde + doel + voorwaarde) en consistenties (assertie).
        for (var r : model.resolvedRegels) {
            LinkedHashSet<String> params = new LinkedHashSet<>();
            vangExpr(r.waarde(), params);
            vangExpr(r.doel(), params);
            vangConditie(r.voorwaarde(), params);
            controleerConstruct(r.naam(), r.geldigheid(), params, pool, uit);
        }
        for (var c : model.consistenties) {
            LinkedHashSet<String> params = new LinkedHashSet<>();
            vangConditie(c.assertie(), params);
            controleerConstruct(c.naam(), c.geldigheid(), params, pool, uit);
        }
        return uit;
    }

    private static void controleerConstruct(String naam, Geldigheid regelPeriode, LinkedHashSet<String> params,
                                            Map<String, List<WaardeInterval>> pool, List<PeriodeDekking> uit) {
        Geldigheid periode = regelPeriode == null ? Geldigheid.ALTIJD : regelPeriode;
        for (String param : params) {
            List<WaardeInterval> intervallen = pool.get(param);
            if (intervallen == null) continue;   // niet in de sets -> het andere kanaal, geen melding hier
            List<Geldigheid> gaten = gaten(periode, intervallen);
            if (!gaten.isEmpty()) uit.add(new PeriodeDekking(naam, param, periode, gaten));
        }
    }

    /** De ParameterRef-namen in een waarde/doel-Expr — dezelfde {@code Expr.bevat}-walk als {@code regelParameters}. */
    private static void vangExpr(Expr e, LinkedHashSet<String> uit) {
        Expr.bevat(e, x -> { if (x instanceof Expr.ParameterRef pr) uit.add(pr.parameter().naam()); return false; });
    }

    /** De ParameterRef-namen in een voorwaarde/assertie — dezelfde {@code Conditie.bevatOperand}-walk. */
    private static void vangConditie(Conditie c, LinkedHashSet<String> uit) {
        Conditie.bevatOperand(c, x -> { if (x instanceof Expr.ParameterRef pr) uit.add(pr.parameter().naam()); return false; });
    }

    /**
     * De deelperiodes van {@code regelPeriode} die door geen enkel {@code intervallen}-venster gedekt zijn: regelperiode
     * MINUS unie(intervallen). Sorteert de dekking op {@code vanaf} ({@code null} eerst = open begin), loopt de
     * regelperiode af met een cursor; inclusieve bounds ({@code null} = open kant): een gat begint op de dag NÁ de vorige
     * dekking-tot en eindigt op de dag VÓÓR de volgende dekking-vanaf. Overlappende/aangrenzende dekkingen worden gemerged
     * via de cursor. Leeg als de dekking de hele regelperiode omvat.
     */
    static List<Geldigheid> gaten(Geldigheid regelPeriode, List<WaardeInterval> intervallen) {
        List<Geldigheid> gaten = new ArrayList<>();
        LocalDate rVan = regelPeriode.vanaf();   // null = open begin
        LocalDate rTot = regelPeriode.tot();     // null = open eind

        // Dekking op vanaf sorteren (null-vanaf = open begin, dus eerst). We werken met de sentinel MIN/MAX voor
        // open kanten zodat de sweep geen null hoeft af te vangen; aan het eind vertalen we MIN/MAX terug naar null.
        List<Geldigheid> dekking = new ArrayList<>();
        for (WaardeInterval wi : intervallen) dekking.add(wi.geldigheid());
        dekking.sort(Comparator.comparing(g -> g.vanaf() == null ? LocalDate.MIN : g.vanaf()));

        LocalDate lo = rVan == null ? LocalDate.MIN : rVan;   // ondergrens regelperiode
        LocalDate hi = rTot == null ? LocalDate.MAX : rTot;   // bovengrens regelperiode

        // cursor = de eerste dag binnen [lo,hi] die nog niet als gedekt bewezen is.
        LocalDate cursor = lo;
        for (Geldigheid d : dekking) {
            if (cursor.isAfter(hi)) break;                     // regelperiode volledig gedekt
            LocalDate dVan = d.vanaf() == null ? LocalDate.MIN : d.vanaf();
            LocalDate dTot = d.tot()   == null ? LocalDate.MAX : d.tot();
            if (dTot.isBefore(cursor)) continue;               // dekking ligt geheel vóór de cursor
            if (dVan.isAfter(cursor)) {                         // gat tussen cursor en deze dekking
                LocalDate g1 = min(dVan.minusDays(1), hi);
                if (!cursor.isAfter(g1)) gaten.add(venster(cursor, g1));
            }
            if (dTot.equals(LocalDate.MAX)) { cursor = null; break; }   // dekking loopt open door: niets meer ongedekt
            LocalDate na = dTot.plusDays(1);
            if (na.isAfter(cursor)) cursor = na;               // schuif voorbij de dekking (merge overlap/aangrenzend)
        }
        // Staart-gat: alles van cursor t/m hi dat nog niet gedekt is (cursor null = open-eind gedekt).
        if (cursor != null && !cursor.isAfter(hi)) gaten.add(venster(cursor, hi));
        return gaten;
    }

    /** Vertaal een [van,tot]-sweep-venster terug naar {@link Geldigheid}, met MIN/MAX -> open ({@code null}) kant. */
    private static Geldigheid venster(LocalDate van, LocalDate tot) {
        return new Geldigheid(van.equals(LocalDate.MIN) ? null : van, tot.equals(LocalDate.MAX) ? null : tot);
    }

    private static LocalDate min(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }

    /**
     * Meld deze bevinding als één WARNING-diagnostic op {@code model}, gededupliceerd (zoals {@code Dekking.meld}): één
     * regel tekst die BEIDE periodes citeert — de regelperiode en elk ontbrekend gat. Consumer-aangeroepen; de check
     * ({@link #controleer}) blijft puur.
     */
    public void meld(RegelSpraakModel model) {
        StringBuilder gaten = new StringBuilder();
        for (int i = 0; i < ontbrekend.size(); i++) {
            if (i > 0) gaten.append(", ");
            gaten.append(toon(ontbrekend.get(i)));
        }
        var w = new RegelSpraakModel.Diagnostic(RegelSpraakModel.Severity.WARNING,
                "periodedekking: regel '" + regel + "' (geldig " + toon(regelPeriode) + ") verwijst naar parameter '"
                        + parameter + "' zonder geldige waarde in " + gaten, 0);
        if (!model.diagnostics.contains(w)) model.diagnostics.add(w);
    }

    /** Meld ALLE bevindingen op het model (deduplicerend). Consumer-gemak boven een handmatige lus. */
    public static void meldAlle(List<PeriodeDekking> bevindingen, RegelSpraakModel model) {
        for (PeriodeDekking pd : bevindingen) pd.meld(model);
    }

    /** {@code [vanaf t/m tot]} met "(open)" voor een null-kant — zelfde renderstijl als {@link Parameterset}. */
    private static String toon(Geldigheid g) {
        String v = g.vanaf() == null ? "(open)" : g.vanaf().toString();
        String t = g.tot() == null ? "(open)" : g.tot().toString();
        return "[" + v + " t/m " + t + "]";
    }
}
