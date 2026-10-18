package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.graph.ProducerConsumer;
import dev.uchar.regelspraak.ir.Default;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRuleGroup;
import dev.uchar.regelspraak.model.RegelSpraakModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The forms of a model's rule layer by the names a descriptor scope uses. A form is what uchar lowers a scope to
 * ({@link CaseEmitter#withScope}): a rule (each version and each decision-table row a form of its own), an object
 * creation, an allocation, a consistency, a uniqueness, a fact creation or a defaults group. A scope name carries the
 * forms of that name, or else the rows of the decision table of that name; a rule group carries the forms of its
 * members. That is how uchar's scenario resolver reads a scope, so an entry point and a scenario that name the same
 * rules build the same document, the inheritance line included, which uchar's scenario resolver reads the same way
 * since uchar 2a0c6d60: a defaults group without a base of its own — what uchar makes of
 * the own exceptions of a subtype under the base rule of its supertype, {@code <basis> voor <subtype>} — goes with the
 * group whose base it inherits, and with the other such groups of that base. RUNTIME-SEMANTICS ch. 7.10 composes the
 * stack of a concrete type from both, so a scope that carries one of them carries all of them; no author wrote a rule
 * by the generated name, so none has to name it.
 *
 * <p>A name the source declares as a rule but that carries no form is a rule uchar does not carry as written (its
 * frontend says why in a warning), or an exception rule, which lives in the defaults group of its base rule. An entry
 * point that scopes such a name would answer without that rule; {@link DecisionCore} does not serve it.
 */
public final class FormIndex {

    private final List<String> forms;
    private final Set<String> declared;
    private final List<ResolvedRuleGroup> groups;
    private final List<RegelSpraakModel.Diagnostic> warnings;
    /** Per defaults group (by its normalised form name) the groups of its stack, itself included, in model order;
     *  only for a group that has company. */
    private final Map<String, List<String>> stackMates;

    private FormIndex(List<String> forms, Set<String> declared, List<ResolvedRuleGroup> groups,
                      List<RegelSpraakModel.Diagnostic> warnings, Map<String, List<String>> stackMates) {
        this.forms = forms;
        this.declared = declared;
        this.groups = groups;
        this.warnings = warnings;
        this.stackMates = stackMates;
    }

    /** The forms of {@code model}, with {@code defaults} its defaults groups: a resolver's model holds them
     *  ({@code model.prioritizedDefaults}), a model uchar's loader rebuilt carries them beside it. A form is listed
     *  under its name without a version suffix, once. */
    public static FormIndex van(RegelSpraakModel model, List<PrioritizedDefaults> defaults) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (var r : model.resolvedRegels) add(names, r.naam());
        for (var x : model.objectCreations) add(names, x.naam());
        for (var x : model.allocations) add(names, x.naam());
        for (var x : model.consistenties) add(names, x.naam());
        for (var x : model.uniquenesses) add(names, x.naam());
        for (var x : model.uniquenessUnions) add(names, x.naam());
        for (var x : model.factCreations) add(names, x.naam());
        for (var x : defaults) add(names, x.naam());
        Set<String> declared = new LinkedHashSet<>();
        for (var r : model.regels) if (r.naam() != null) declared.add(CaseEmitter.norm(r.naam()));
        List<RegelSpraakModel.Diagnostic> warnings = new ArrayList<>();
        for (var d : model.diagnostics)
            if (d.severity() == RegelSpraakModel.Severity.WARNING) warnings.add(d);
        return new FormIndex(List.copyOf(names), declared, List.copyOf(model.ruleGroups),
                List.copyOf(warnings), stackMates(model, defaults));
    }

    /**
     * The groups that compose one stack per concrete type (RUNTIME-SEMANTICS ch. 7.10), each with the others. A group
     * on a subtype without a base of its own decides over the base it inherits: it is joined to every group of the
     * nearest type above its subject that writes the same target, and a group there without a base reaches further up
     * in its turn. A group with a base of its own does not reach up, since it is the base of its stack, and neither
     * does a declared-unordered one, which uchar's composition does not fold over an inherited stack. The target is
     * the member's identity of uchar's rule graph ({@link ProducerConsumer#elementOf}), so a member written on the
     * supertype and on a subtype is one target, as ch. 7.10 composes it.
     */
    private static Map<String, List<String>> stackMates(RegelSpraakModel model, List<PrioritizedDefaults> defaults) {
        List<PrioritizedDefaults> named = new ArrayList<>();
        for (PrioritizedDefaults p : defaults) if (p.naam() != null) named.add(p);
        Map<PrioritizedDefaults, PrioritizedDefaults> parent = new IdentityHashMap<>();
        for (PrioritizedDefaults p : named) parent.put(p, p);
        for (PrioritizedDefaults p : named) {
            RegelSpraakModel.Objecttype subject = model.objectTypeByName(p.onderwerp());
            if (subject == null || p.unordered() || ownBase(p)) continue;
            ProducerConsumer.ElementKey target = ProducerConsumer.elementOf(subject, p.target(), p.isCharacteristic());
            List<RegelSpraakModel.Objecttype> chain = subject.chain();
            for (RegelSpraakModel.Objecttype higher : chain.subList(1, chain.size())) {
                List<PrioritizedDefaults> remaining = new ArrayList<>();
                for (PrioritizedDefaults q : named)
                    if (model.objectTypeByName(q.onderwerp()) == higher
                            && ProducerConsumer.elementOf(higher, q.target(), q.isCharacteristic()).equals(target))
                        remaining.add(q);
                if (remaining.isEmpty()) continue;
                for (PrioritizedDefaults q : remaining) unite(parent, p, q);
                break;
            }
        }
        Map<PrioritizedDefaults, List<String>> perRoot = new IdentityHashMap<>();
        for (PrioritizedDefaults p : named)
            perRoot.computeIfAbsent(root(parent, p), w -> new ArrayList<>()).add(CaseEmitter.baseName(p.naam()));
        Map<String, List<String>> sink = new HashMap<>();
        for (PrioritizedDefaults p : named) {
            List<String> stack = perRoot.get(root(parent, p));
            if (stack.size() > 1) sink.put(CaseEmitter.norm(CaseEmitter.baseName(p.naam())), List.copyOf(stack));
        }
        return sink;
    }

    /** Whether {@code p} has a base of its own, as uchar's composition reads one: its lowest ordinary default decides
     *  something itself. A group-default delegates to its exception groups and is no base. */
    private static boolean ownBase(PrioritizedDefaults p) {
        Default last = null;
        for (Default d : p.defaults()) if (!d.initialisatie()) last = d;
        return last != null && last.hasOwnContent();
    }

    private static PrioritizedDefaults root(Map<PrioritizedDefaults, PrioritizedDefaults> parent, PrioritizedDefaults p) {
        while (parent.get(p) != p) p = parent.get(p);
        return p;
    }

    private static void unite(Map<PrioritizedDefaults, PrioritizedDefaults> parent, PrioritizedDefaults a,
                                PrioritizedDefaults b) {
        PrioritizedDefaults wa = root(parent, a), wb = root(parent, b);
        if (wa != wb) parent.put(wa, wb);
    }

    private static void add(Set<String> names, String name) {
        if (name != null) names.add(CaseEmitter.baseName(name));
    }

    /** Every form, in model order. */
    public List<String> all() { return forms; }

    /** The forms rule name {@code naam} carries: the forms of that name, or else the rows of the decision table of
     *  that name ({@code "<naam> <n>"}). Empty when it names no form. */
    public List<String> formsOf(String name) {
        String n = CaseEmitter.norm(name);
        List<String> sink = new ArrayList<>();
        for (String v : forms) if (CaseEmitter.norm(v).equals(n)) sink.add(v);
        if (!sink.isEmpty()) return sink;
        Pattern row = Pattern.compile(Pattern.quote(n) + " [0-9]+");
        for (String v : forms) if (row.matcher(CaseEmitter.norm(v)).matches()) sink.add(v);
        return sink;
    }

    /** Whether a scope may name {@code naam}: it carries a form, or the source declares a rule of that name. */
    public boolean exists(String name) {
        return !formsOf(name).isEmpty() || declared.contains(CaseEmitter.norm(name));
    }

    /** What a scope carries: the forms, and the named rules that carry none, in the order the scope names them. */
    public record Choice(List<String> forms, List<String> withoutForm) {}

    /** The forms {@code scope} carries: those of each rule name, and those of each member of each rule group, each
     *  defaults group with the groups of its stack. */
    public Choice scope(ServiceDescriptor.Scope scope) {
        LinkedHashSet<String> sink = new LinkedHashSet<>();
        List<String> withoutForm = new ArrayList<>();
        List<String> names = new ArrayList<>(scope.ruleNamesOrEmpty());
        for (String group : scope.ruleGroupsOrEmpty())
            for (ResolvedRuleGroup g : groups) if (g.naam().equals(group)) names.addAll(g.members());
        for (String name : names) {
            List<String> v = formsOf(name);
            if (v.isEmpty()) { if (!withoutForm.contains(name)) withoutForm.add(name); }
            else for (String form : v) {
                sink.add(form);
                sink.addAll(stackMates.getOrDefault(CaseEmitter.norm(form), List.of()));
            }
        }
        return new Choice(List.copyOf(sink), List.copyOf(withoutForm));
    }

    /** uchar's warnings that name rule {@code naam} in quotes: why its frontend did not make a form of it. */
    public List<String> warningsAbout(String name) {
        String quoted = "'" + CaseEmitter.norm(name) + "'";
        List<String> sink = new ArrayList<>();
        for (var d : warnings)
            if (d.message() != null && CaseEmitter.norm(d.message()).contains(quoted)) sink.add(d.message());
        return sink;
    }
}
