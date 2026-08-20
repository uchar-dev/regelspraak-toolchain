package io.github.yhavinga.regelspraak.transpiler;

import io.github.yhavinga.regelspraak.ir.Conditie;
import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;

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
 *  early writes a once-true kenmerk that persists — the same transient-window bug one level down
 *  (StratificatieKetenTest, the red case).
 *
 *  <p>The derivation: over the kenmerk graph — keyed by (objecttype, kenmerk), matching the resolver's
 *  type-qualified graph, so a kenmerk homonym on an unrelated objecttype adds no edge; only a NAVIGATED
 *  read whose owning type is not resolved falls back to a name-wide (wildcard) producer match — a rule's
 *  DEPTH is the maximum over its kenmerk reads of the producing rules' depth, plus one when the read is
 *  negated. Positive reads pass depth through without sinking the reader by themselves (a positive reader
 *  that fired too early re-fires on the producer's modify — kenmerken are monotone, so a missed positive
 *  is recoverable; a consumed negation is not); a negation-consumer that ALSO positively reads a derived
 *  kenmerk sinks below that producer too — a deliberate, safe over-approximation (deeper only delays, it
 *  never re-opens the transient window). The salience is {@code -depth} for a rule that itself carries a
 *  negated kenmerk read, {@code 0} otherwise — identical to the heuristic everywhere except chains and the
 *  over-approximation cases above.
 *
 *  <p>A negation CYCLE has no strata. The fallback is CYCLE-LOCAL: only the rules whose depth still moved
 *  in the final sweep — and every rule that transitively reads through them — degrade to the one-level
 *  heuristic (-1 for a direct negation reader); converged chains elsewhere in the model keep their derived
 *  strata. NB the resolver's read-write-cycle warning walks assignment VALUES only, not voorwaarden, so a
 *  pure negation-condition cycle carries no resolver diagnostic — the local fallback is the only guard.
 *
 *  <p>Deliberately out of scope, as before: negated tests over DERIVED non-kenmerk state (a leeg-check on a
 *  computed attribute) — the heuristic never covered those either, and they await the spec's full
 *  dependency-graph treatment. */
public final class Stratificatie {

    private final Map<String, Integer> saliencePerRegel = new HashMap<>();

    private Stratificatie() {}

    public static Stratificatie bereken(List<ResolvedRegel> regels) {
        Stratificatie s = new Stratificatie();

        // producers, type-qualified: (objecttype|kenmerk) -> producing rule indexes; plus the name-wide
        // index for the wildcard fallback of an unresolved navigated read
        Map<String, List<Integer>> producenten = new HashMap<>();
        Map<String, List<Integer>> producentenOpNaam = new HashMap<>();
        for (int i = 0; i < regels.size(); i++) {
            ResolvedRegel r = regels.get(i);
            if (r.soort() == ResolvedRegel.Soort.KENMERKTOEKENNING
                    && r.doel() instanceof Expr.AttribuutRef doel && doel.attribuut() != null) {
                producenten.computeIfAbsent(key(doel.objecttype(), doel.attribuut()), k -> new ArrayList<>()).add(i);
                producentenOpNaam.computeIfAbsent(naamKey(doel.attribuut()), k -> new ArrayList<>()).add(i);
            }
        }

        // reads: per rule the kenmerk reads, with the owning type (the read's eigenaar when resolved, else
        // the rule's own subject type for an un-navigated read, else null = wildcard) and negation flag
        List<List<Read>> reads = new ArrayList<>();
        for (ResolvedRegel r : regels) {
            Objecttype subject =
                    r.doel() instanceof Expr.AttribuutRef doel ? doel.objecttype() : null;
            List<Read> uit = new ArrayList<>();
            verzamelReads(r.voorwaarde(), subject, uit);
            reads.add(uit);
        }

        // depth fixpoint: depth(r) = max over reads (k, neg): maxProducerDepth(k) + (neg ? 1 : 0).
        // Convergence needs at most one sweep per chain level; regels.size() sweeps bound any acyclic graph.
        int[] depth = new int[regels.size()];
        boolean[] bewoog = new boolean[regels.size()];
        boolean stabiel = false;
        for (int iter = 0; iter <= regels.size() && !stabiel; iter++) {
            stabiel = true;
            java.util.Arrays.fill(bewoog, false);
            for (int i = 0; i < regels.size(); i++) {
                int d = 0;
                for (Read read : reads.get(i)) {
                    int p = 0;
                    for (int j : read.producentenVan(producenten, producentenOpNaam)) p = Math.max(p, depth[j]);
                    d = Math.max(d, p + (read.negatie ? 1 : 0));
                }
                if (d != depth[i]) { depth[i] = d; bewoog[i] = true; stabiel = false; }
            }
        }

        // cycle-local contamination: a rule still moving in the final sweep sits on (or under) a negation
        // cycle; every rule reading through a contaminated producer is contaminated too
        boolean[] besmet = bewoog.clone();
        boolean groei = !stabiel;
        while (groei) {
            groei = false;
            for (int i = 0; i < regels.size(); i++) {
                if (besmet[i]) continue;
                for (Read read : reads.get(i)) {
                    for (int j : read.producentenVan(producenten, producentenOpNaam)) {
                        if (besmet[j]) { besmet[i] = true; groei = true; break; }
                    }
                    if (besmet[i]) break;
                }
            }
        }

        for (int i = 0; i < regels.size(); i++) {
            ResolvedRegel r = regels.get(i);
            boolean negLezer = reads.get(i).stream().anyMatch(read -> read.negatie);
            int salience;
            if (!negLezer) salience = 0;
            else if (!stabiel && besmet[i]) salience = -1;   // on/under a negation cycle: no strata — the
                                                             // one-level heuristic, locally
            else salience = -depth[i];
            s.saliencePerRegel.put(r.naam(), salience);
        }
        return s;
    }

    /** The derived agenda salience for {@code r}: 0, or negative for a negation-consumer (its stratum). */
    public int salience(ResolvedRegel r) {
        return saliencePerRegel.getOrDefault(r.naam(), 0);
    }

    private record Read(String typeKey, String kenmerk, boolean negatie) {
        /** The producer indexes this read can see: type-qualified when the owning type resolved, else the
         *  name-wide wildcard (an unresolved navigated read — over-approximate, never under). */
        List<Integer> producentenVan(Map<String, List<Integer>> perType, Map<String, List<Integer>> opNaam) {
            return typeKey != null
                    ? perType.getOrDefault(typeKey + "|" + kenmerk, List.of())
                    : opNaam.getOrDefault(kenmerk, List.of());
        }
    }

    private static void verzamelReads(Conditie c, Objecttype subject, List<Read> uit) {
        if (c == null) return;
        if (c instanceof Conditie.Kenmerk k && k.kenmerk() != null) {
            Objecttype eigenaar = k.eigenaar() != null ? k.eigenaar()
                    : k.navigatie() == null ? subject : null;
            uit.add(new Read(eigenaar != null ? typeKey(eigenaar) : null,
                    naamKey(k.kenmerk()), k.negatie()));
        }
        if (c instanceof Conditie.Samengesteld sam)
            for (Conditie d : sam.onderdelen()) verzamelReads(d, subject, uit);
    }

    private static String key(Objecttype ot, String kenmerk) {
        return (ot != null ? typeKey(ot) : "?") + "|" + naamKey(kenmerk);
    }

    private static String typeKey(Objecttype ot) {
        return ot.naam == null ? "?" : ot.naam.toLowerCase(Locale.ROOT);
    }

    private static String naamKey(String kenmerk) {
        return kenmerk.toLowerCase(Locale.ROOT);
    }
}
