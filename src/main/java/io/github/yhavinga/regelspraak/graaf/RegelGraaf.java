package io.github.yhavinga.regelspraak.graaf;

import io.github.yhavinga.regelspraak.graaf.ProducentConsument.ElementSleutel;
import io.github.yhavinga.regelspraak.ir.ResolvedRegel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The regelgraaf: the bipartite dataflow graph regels ↔ gegevens extracted from a resolved
 * {@link RegelSpraakModel}. A rule node connects to a data node it PRODUCES (rule → data, its {@code doel}) and a
 * data node connects to a rule that CONSUMES it (data → rule, its voorwaarde/operanden). Direction is therefore
 * dataflow: a request-input (a datum no rule produces) feeds rules, rules produce data, that data feeds
 * downstream rules, and a datum no rule consumes is a product-less eind-output.
 *
 * <p>The producer/consumer analysis is the SHARED {@link ProducentConsument} core (also the authority behind the
 * service's scope-completeness validation), so the graph is coloured by the very facts that check uses:
 * <ul>
 *   <li>{@link NodeKind#REGEL} — a rule;</li>
 *   <li>{@link NodeKind#ATTRIBUUT} — an attribuut written and/or read by rules;</li>
 *   <li>{@link NodeKind#KENMERK} — a §13.4.5 boolean kenmerk;</li>
 *   <li>{@link NodeKind#INVOER} — a datum CONSUMED but produced by no rule (a request/koppelvlak input);</li>
 * </ul>
 * and a produced-but-never-consumed datum is flagged as a product-less eind-output.
 *
 * <p>The render is jar-only: {@link #mermaid()} is the bare Mermaid flowchart source (offline-usable), and
 * {@link #html()} wraps it in a self-contained page that loads Mermaid from a CDN (needs the network to render).
 */
public final class RegelGraaf {

    public enum NodeKind { REGEL, ATTRIBUUT, KENMERK, INVOER }

    /** One graph node. {@code id} is a Mermaid-safe stable identifier; {@code label} is the human text;
     *  {@code eindOutput} marks a produced-but-never-consumed datum (a product-less eind-output). */
    public record Node(String id, String label, NodeKind kind, boolean eindOutput) {}

    /** A directed dataflow edge from {@code van} to {@code naar} (both node ids). */
    public record Edge(String van, String naar) {}

    private final List<Node> nodes;
    private final List<Edge> edges;

    private RegelGraaf(List<Node> nodes, List<Edge> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    public List<Node> nodes() { return nodes; }
    public List<Edge> edges() { return edges; }

    /** Build the graph from a resolved model. Only value-writing rules and the elements they read/write appear;
     *  a data element is INVOER when no rule produces it and a produced element is marked eind-output when no
     *  rule consumes it. */
    public static RegelGraaf bouw(RegelSpraakModel model) {
        Map<ElementSleutel, Set<String>> producenten = ProducentConsument.producentIndex(model);
        // Every element that is consumed by at least one rule, so we can tell producties zonder afnemer apart.
        Set<ElementSleutel> verbruikt = new LinkedHashSet<>();
        for (ResolvedRegel r : model.resolvedRegels) verbruikt.addAll(ProducentConsument.verbruikt(r));

        // Stable id allocation. Rule ids by base name; element ids by sleutel.
        Map<String, String> regelId = new LinkedHashMap<>();
        Map<ElementSleutel, String> elemId = new LinkedHashMap<>();
        List<Node> nodes = new ArrayList<>();
        Set<Edge> edgeSet = new LinkedHashSet<>();

        // 1. element nodes: the union of everything produced and everything consumed.
        Set<ElementSleutel> alleElementen = new LinkedHashSet<>();
        alleElementen.addAll(producenten.keySet());
        alleElementen.addAll(verbruikt);
        for (ElementSleutel el : alleElementen) {
            boolean geproduceerd = producenten.containsKey(el);
            boolean geconsumeerd = verbruikt.contains(el);
            NodeKind kind = !geproduceerd ? NodeKind.INVOER
                    : (el.kenmerk() ? NodeKind.KENMERK : NodeKind.ATTRIBUUT);
            boolean eindOutput = geproduceerd && !geconsumeerd;
            String id = "e" + elemId.size();
            elemId.put(el, id);
            nodes.add(new Node(id, elementLabel(el), kind, eindOutput));
        }

        // 2. rule nodes: every rule that produces or consumes something (a value-writer, plus any pure reader).
        for (ResolvedRegel r : model.resolvedRegels) {
            String basis = ProducentConsument.basisNaam(r.naam());
            regelId.computeIfAbsent(basis, b -> {
                String id = "r" + regelId.size();
                nodes.add(new Node(id, b, NodeKind.REGEL, false));
                return id;
            });
        }

        // 3. producer edges: rule -> element it writes.
        for (var e : producenten.entrySet()) {
            String to = elemId.get(e.getKey());
            for (String rn : e.getValue()) {
                String from = regelId.get(rn);
                if (from != null && to != null) edgeSet.add(new Edge(from, to));
            }
        }
        // 4. consumer edges: element -> rule that reads it.
        for (ResolvedRegel r : model.resolvedRegels) {
            String to = regelId.get(ProducentConsument.basisNaam(r.naam()));
            for (ElementSleutel el : ProducentConsument.verbruikt(r)) {
                String from = elemId.get(el);
                if (from != null && to != null) edgeSet.add(new Edge(from, to));
            }
        }
        return new RegelGraaf(nodes, new ArrayList<>(edgeSet));
    }

    /** The label of an element node: "attribuut van Objecttype" (or just the naam when the owner is unknown). */
    private static String elementLabel(ElementSleutel el) {
        return el.objecttype().isEmpty() ? el.naam() : el.naam() + " · " + el.objecttype();
    }

    // ---------------- render ----------------

    /** The bare Mermaid flowchart source (left-to-right, dataflow direction). Offline-usable: paste into any
     *  Mermaid renderer. Node shapes/classes encode the kind; an eind-output rule-target is dashed-outlined. */
    public String mermaid() {
        StringBuilder b = new StringBuilder();
        b.append("flowchart LR\n");
        for (Node n : nodes) {
            String open, close;
            switch (n.kind()) {
                case REGEL   -> { open = "[[";  close = "]]"; }       // subroutine shape: a rule
                case INVOER  -> { open = "[/";  close = "/]"; }       // parallelogram: an external input
                case KENMERK -> { open = "([";  close = "])"; }       // stadium: a boolean kenmerk
                default      -> { open = "(";   close = ")"; }        // rounded: an attribuut
            }
            b.append("  ").append(n.id()).append(open).append('"').append(escapeMermaid(n.label()))
                    .append('"').append(close).append('\n');
        }
        b.append('\n');
        for (Edge e : edges) b.append("  ").append(e.van()).append(" --> ").append(e.naar()).append('\n');
        b.append('\n');
        // class definitions + assignments
        b.append("  classDef regel fill:#dbeafe,stroke:#2563eb,color:#1e3a8a;\n");
        b.append("  classDef attribuut fill:#dcfce7,stroke:#16a34a,color:#14532d;\n");
        b.append("  classDef kenmerk fill:#fef9c3,stroke:#ca8a04,color:#713f12;\n");
        b.append("  classDef invoer fill:#fae8ff,stroke:#a21caf,color:#701a75;\n");
        b.append("  classDef eindoutput stroke-width:3px,stroke-dasharray:4 3;\n");
        appendClass(b, "regel", NodeKind.REGEL);
        appendClass(b, "attribuut", NodeKind.ATTRIBUUT);
        appendClass(b, "kenmerk", NodeKind.KENMERK);
        appendClass(b, "invoer", NodeKind.INVOER);
        List<String> eind = nodes.stream().filter(Node::eindOutput).map(Node::id).toList();
        if (!eind.isEmpty()) b.append("  class ").append(String.join(",", eind)).append(" eindoutput;\n");
        return b.toString();
    }

    private void appendClass(StringBuilder b, String klasse, NodeKind kind) {
        List<String> ids = nodes.stream().filter(n -> n.kind() == kind).map(Node::id).toList();
        if (!ids.isEmpty()) b.append("  class ").append(String.join(",", ids)).append(' ').append(klasse).append(";\n");
    }

    /** A self-contained HTML page embedding the Mermaid source and loading the Mermaid runtime from a CDN. Works
     *  ONLY with a network (that is the deliberate trade for a single portable file); the {@link #mermaid()}
     *  companion is the offline artefact. The counts + a legend are rendered in plain HTML so the page is still
     *  informative if the script fails to load. */
    public String html() {
        long nRegel = nodes.stream().filter(n -> n.kind() == NodeKind.REGEL).count();
        long nAttr  = nodes.stream().filter(n -> n.kind() == NodeKind.ATTRIBUUT).count();
        long nKenm  = nodes.stream().filter(n -> n.kind() == NodeKind.KENMERK).count();
        long nInvoer = nodes.stream().filter(n -> n.kind() == NodeKind.INVOER).count();
        long nEind  = nodes.stream().filter(Node::eindOutput).count();
        String mmd = mermaid();
        return """
            <!DOCTYPE html>
            <html lang="nl">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>RegelSpraak regelgraaf</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 0; padding: 1.5rem; color: #1f2937; }
              h1 { font-size: 1.25rem; margin: 0 0 .5rem; }
              .telling { color: #4b5563; margin: 0 0 1rem; font-size: .9rem; }
              .telling b { color: #111827; }
              .legenda { display: flex; gap: 1rem; flex-wrap: wrap; margin: 0 0 1rem; font-size: .85rem; }
              .legenda span { padding: .15rem .5rem; border-radius: .3rem; border: 1px solid; }
              .lg-regel { background:#dbeafe; border-color:#2563eb; color:#1e3a8a; }
              .lg-attr  { background:#dcfce7; border-color:#16a34a; color:#14532d; }
              .lg-kenm  { background:#fef9c3; border-color:#ca8a04; color:#713f12; }
              .lg-inv   { background:#fae8ff; border-color:#a21caf; color:#701a75; }
              .lg-eind  { border-style:dashed; border-width:2px; border-color:#111827; }
              .graaf { border:1px solid #e5e7eb; border-radius:.5rem; padding:1rem; overflow:auto; }
            </style>
            <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
            <script>
              window.addEventListener('load', function () {
                if (window.mermaid) { mermaid.initialize({ startOnLoad: false, maxTextSize: 500000 });
                  mermaid.run({ querySelector: '.mermaid' }); }
              });
            </script>
            </head>
            <body>
            <h1>RegelSpraak regelgraaf — dataflow regels &harr; gegevens</h1>
            <p class="telling"><b>__NREGEL__</b> regels, <b>__NATTR__</b> attributen,
              <b>__NKENM__</b> kenmerken, <b>__NINVOER__</b> invoer-bronnen (nergens geproduceerd),
              <b>__NEIND__</b> productloze eind-outputs.</p>
            <div class="legenda">
              <span class="lg-regel">regel</span>
              <span class="lg-attr">attribuut</span>
              <span class="lg-kenm">kenmerk</span>
              <span class="lg-inv">invoer (request/koppelvlak)</span>
              <span class="lg-eind">productloze eind-output</span>
            </div>
            <div class="graaf"><pre class="mermaid">
            __MERMAID__
            </pre></div>
            </body>
            </html>
            """
            .replace("__NREGEL__", Long.toString(nRegel))
            .replace("__NATTR__", Long.toString(nAttr))
            .replace("__NKENM__", Long.toString(nKenm))
            .replace("__NINVOER__", Long.toString(nInvoer))
            .replace("__NEIND__", Long.toString(nEind))
            .replace("__MERMAID__", mmd);
    }

    /** Escape a label for a Mermaid quoted string: double-quotes break the "…" node text, so entity-encode them. */
    private static String escapeMermaid(String s) {
        return s.replace("\"", "&quot;");
    }
}
