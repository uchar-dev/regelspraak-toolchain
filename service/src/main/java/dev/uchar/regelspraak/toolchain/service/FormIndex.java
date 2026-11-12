package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.emit.IrEmitter;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The forms of a model's rule layer by the names a descriptor scope uses. A form is what uchar lowers a scope to
 * ({@link CaseEmitter#withScope}): a rule (each version and each decision-table row a form of its own), an object
 * creation, an allocation, a consistency, a uniqueness, a fact creation or a defaults group. What a scope carries is
 * uchar's scope reading ({@link ScenarioResolver#scopeForms}), the one its scenario resolver gives a scenario's scope,
 * so an entry point and a scenario that name the same rules build the same document.
 *
 * <p>A name the source declares as a rule but that carries no form is a rule uchar does not carry as written (its
 * frontend says why in a warning), or an exception rule, which lives in the defaults group of its base rule. An entry
 * point that scopes such a name would answer without that rule; {@link DecisionCore} does not serve it.
 */
public final class FormIndex {

    private final RegelSpraakModel model;
    private final List<PrioritizedDefaults> defaults;
    private final List<String> forms;
    private final Set<String> declared;
    private final List<RegelSpraakModel.Diagnostic> warnings;

    private FormIndex(RegelSpraakModel model, List<PrioritizedDefaults> defaults, List<String> forms,
                      Set<String> declared, List<RegelSpraakModel.Diagnostic> warnings) {
        this.model = model;
        this.defaults = defaults;
        this.forms = forms;
        this.declared = declared;
        this.warnings = warnings;
    }

    /** The forms of {@code model}, with {@code defaults} its defaults groups: a resolver's model holds them
     *  ({@code model.prioritizedDefaults}), a model uchar's loader rebuilt carries them beside it. A form is listed
     *  under its name without a version suffix, once. */
    public static FormIndex of(RegelSpraakModel model, List<PrioritizedDefaults> defaults) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (var r : model.resolvedRules) add(names, r.name());
        for (var x : model.objectCreations) add(names, x.name());
        for (var x : model.allocations) add(names, x.name());
        for (var x : model.consistencies) add(names, x.name());
        for (var x : model.uniquenesses) add(names, x.name());
        for (var x : model.uniquenessUnions) add(names, x.name());
        for (var x : model.factCreations) add(names, x.name());
        for (var x : defaults) add(names, x.name());
        Set<String> declared = new LinkedHashSet<>();
        for (var r : model.rules) if (r.name() != null) declared.add(IrEmitter.norm(r.name()));
        List<RegelSpraakModel.Diagnostic> warnings = new ArrayList<>();
        for (var d : model.diagnostics)
            if (d.severity() == RegelSpraakModel.Severity.WARNING) warnings.add(d);
        return new FormIndex(model, List.copyOf(defaults), List.copyOf(names), declared, List.copyOf(warnings));
    }

    private static void add(Set<String> names, String name) {
        if (name != null) names.add(RegelSpraakModel.formName(name));
    }

    /** Every form, in model order. */
    public List<String> all() { return forms; }

    /** Whether a scope may name {@code name}: it carries a form, or the source declares a rule of that name. */
    public boolean exists(String name) {
        return declared.contains(IrEmitter.norm(name))
                || ScenarioResolver.scopeForms(model, defaults, List.of(name), List.of()).withoutForm().isEmpty();
    }

    /** The forms {@code scope} carries, the names among its rule names and the members of its rule groups that carry
     *  none, and the group names the model has no rule group of, as uchar reads a scope. */
    public ScenarioResolver.ScopeForms scope(ServiceDescriptor.Scope scope) {
        return ScenarioResolver.scopeForms(model, defaults, scope.ruleNamesOrEmpty(), scope.ruleGroupsOrEmpty());
    }

    /** uchar's warnings that name rule {@code naam} in quotes: why its frontend did not make a form of it. */
    public List<String> warningsAbout(String name) {
        String quoted = "'" + IrEmitter.norm(name) + "'";
        List<String> sink = new ArrayList<>();
        for (var d : warnings)
            if (d.message() != null && IrEmitter.norm(d.message()).contains(quoted)) sink.add(d.message());
        return sink;
    }
}
