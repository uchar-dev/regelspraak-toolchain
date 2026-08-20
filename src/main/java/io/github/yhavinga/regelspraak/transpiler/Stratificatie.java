package io.github.yhavinga.regelspraak.transpiler;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** §9.2 negation-stratification, DERIVED from the declared dependency graph instead of the one-level
 *  syntactic heuristic (the uchar spec's WF-046 strata principle: along a positive edge the consumer's
 *  stratum is at least the producer's, along a negative edge strictly greater).
 *
 *  <p>A §13.4.5 kenmerktoekenning is MONOTONIC (a kenmerk is default-onwaar and only ever set WAAR), so a
 *  rule reading "niet &lt;kenmerk&gt;" must fire at the FIXPOINT, after every producer of that kenmerk. The
 *  former heuristic sank every negation-consumer to salience -1 — one level. That is exactly right for a
 *  single negation layer, but a CHAIN ("X is k3 indien X niet k2 is" over "X is k2 indien X niet k1 is")
 *  put producer and consumer in the SAME -1 band, where source order decides and a consumer that fires
 *  early writes a once-true kenmerk that persists — the same transient-window bug one level down.
 *
 *  <p>The derivation: over the kenmerk graph, a rule's DEPTH is the maximum over its kenmerk reads of the
 *  producing rules' depth — plus one when the read is negated. Positive reads pass depth through without
 *  sinking the reader (a positive reader that fired too early re-fires on the producer's modify — kenmerken
 *  are monotone, so a missed positive is recoverable; a consumed negation is not). The salience is
 *  {@code -depth} for a rule that itself carries a negated kenmerk read, {@code 0} otherwise — identical to
 *  the heuristic everywhere except genuine chains, which now stratify correctly.
 *
 *  <p>Deliberately out of scope, as before: negated tests over DERIVED non-kenmerk state (a leeg-check on a
 *  computed attribute) — the heuristic never covered those either, and they await the spec's full
 *  dependency-graph treatment. A negation CYCLE through kenmerk producers has no strata; the resolver
 *  already warns on read-write cycles, and this derivation then falls back to the one-level heuristic for
 *  the rules on the cycle rather than looping. */
public final class Stratificatie {

    private final Map<String, Integer> saliencePerRegel = new HashMap<>();

    private Stratificatie() {}

    public static Stratificatie bereken(List<ResolvedRegel> regels) {
        Stratificatie s = new Stratificatie();

        // producers: kenmerk name (lower-cased; name-keyed like the heuristic, an over-approximation that
        // can only sink a consumer further than needed, never too little) -> producing rule indexes
        Map<String, List<Integer>> producenten = new HashMap<>();
        for (int i = 0; i < regels.size(); i++) {
            ResolvedRegel r = regels.get(i);
            if (r.soort() == ResolvedRegel.Soort.KENMERKTOEKENNING
                    && r.doel() instanceof Expr.AttribuutRef doel && doel.attribuut() != null)
                producenten.computeIfAbsent(key(doel.attribuut()), k -> new ArrayList<>()).add(i);
        }

        // reads: per rule the kenmerk names read, with negation flag
        List<List<Read>> reads = new ArrayList<>();
        for (ResolvedRegel r : regels) {
            List<Read> uit = new ArrayList<>();
            verzamelReads(r.voorwaarde(), uit);
            reads.add(uit);
        }

        // depth fixpoint: depth(r) = max over reads (k, neg): maxProducerDepth(k) + (neg ? 1 : 0)
        int[] depth = new int[regels.size()];
        boolean stabiel = false;
        for (int iter = 0; iter <= regels.size() && !stabiel; iter++) {
            stabiel = true;
            for (int i = 0; i < regels.size(); i++) {
                int d = 0;
                for (Read read : reads.get(i)) {
                    int p = 0;
                    for (int j : producenten.getOrDefault(read.kenmerk, List.of())) p = Math.max(p, depth[j]);
                    d = Math.max(d, p + (read.negatie ? 1 : 0));
                }
                if (d != depth[i]) { depth[i] = d; stabiel = false; }
            }
        }

        for (int i = 0; i < regels.size(); i++) {
            ResolvedRegel r = regels.get(i);
            boolean negLezer = reads.get(i).stream().anyMatch(read -> read.negatie);
            int salience;
            if (!negLezer) salience = 0;
            else if (!stabiel) salience = -1;   // negation cycle: no strata exist — fall back to the
                                                // one-level heuristic (the resolver's cycle warning stands)
            else salience = -depth[i];
            s.saliencePerRegel.put(r.naam(), salience);
        }
        return s;
    }

    /** The derived agenda salience for {@code r}: 0, or negative for a negation-consumer (its stratum). */
    public int salience(ResolvedRegel r) {
        return saliencePerRegel.getOrDefault(r.naam(), 0);
    }

    private record Read(String kenmerk, boolean negatie) {}

    private static void verzamelReads(Conditie c, List<Read> uit) {
        if (c == null) return;
        if (c instanceof Conditie.Kenmerk k && k.kenmerk() != null)
            uit.add(new Read(key(k.kenmerk()), k.negatie()));
        if (c instanceof Conditie.Samengesteld sam)
            for (Conditie d : sam.onderdelen()) verzamelReads(d, uit);
    }

    private static String key(String kenmerk) {
        return kenmerk.toLowerCase(Locale.ROOT);
    }
}
