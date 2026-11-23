package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.EndState;
import dev.uchar.regelspraak.ir.Condition;
import dev.uchar.regelspraak.ir.Default;
import dev.uchar.regelspraak.ir.ExceptionGroup;
import dev.uchar.regelspraak.ir.Expr;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedConsistency;
import dev.uchar.regelspraak.ir.ResolvedRule;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.ObjectType;
import dev.uchar.regelspraak.model.RegelSpraakModel.RoleBindingRef;
import dev.uchar.regelspraak.transpiler.core.Namen;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading;
import dev.uchar.regelspraak.transpiler.tms.ConditionReading.NodeReading;
import dev.uchar.regelspraak.transpiler.tms.Fixpoint.OpenValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What keeps a member open in one session of a strict document, one with {@code inferFromEmpty: false} (uchar's
 * RUNTIME-SEMANTICS ch. 1.5): the open reads uchar recorded in its readings (ch. 5.12, "Open values"), laid on the forms of
 * the document. No condition and no value is evaluated here. A form that left its target open is a reading with the status
 * {@code OPEN}; what left it so is the open read at its undetermined condition, per member of a composite that is
 * undetermined itself, or at its value ({@link #of}); a member that no form left open is open by its own input
 * ({@link #notGiven}); a member open by a form is followed over its open forms to the members open by their input, the
 * input that can be given ({@link #openInputs}). Beside that, {@link #reads} says whether the filter of a quantifier fold
 * reads an open member on a member of its range: it walks the nodes of the filter and the links of uchar's end state and
 * looks the members it reaches up among the open members of that state, every branch of a choice and every value of a
 * binder included, so it says yes also where the evaluation did not read that member.
 */
public final class OpenReads {

    /** Member {@code input}, an open input of the session, with the {@code outcomes} it keeps open, in the order they were
     *  asked. */
    public record OpenInput(OpenValue input, List<OpenValue> outcomes) {}

    /** A form of the document: the member it writes (null for a consistency), its condition and the value it computes, each
     *  null where it has none. */
    private record Form(String member, boolean characteristic, Condition condition, Expr value) {}

    private final Verbaliser.Document document;
    private final RegelSpraakModel model;
    private final Map<String, Form> forms = new HashMap<>();
    private final Map<Object, EndState.EndObject> ends = new IdentityHashMap<>();
    /** The place of every object among those of the end state. */
    private final Map<Object, Integer> order = new IdentityHashMap<>();
    private final List<EndState.Link> links;
    /** The readings that left a target open, per object. */
    private final Map<Object, List<ConditionReading>> open = new IdentityHashMap<>();

    public OpenReads(Verbaliser.Document document, List<ConditionReading> readings, EndState standing) {
        this.document = document;
        this.model = document.model();
        this.links = standing.links();
        for (EndState.EndObject o : standing.objects()) {
            order.put(o.object(), ends.size());
            ends.put(o.object(), o);
        }
        for (ResolvedRule r : document.rules())
            if (r.target() instanceof Expr.AttributeReference a) {
                boolean characteristic = r.kind() == ResolvedRule.Kind.KENMERKTOEKENNING;
                forms.put(document.positions().versionOf(r), new Form(a.attribute(), characteristic, r.condition(),
                        characteristic ? null : r.value()));
            }
        for (int i = 0; i < document.groups().size(); i++) {
            PrioritizedDefaults g = document.groups().get(i);
            for (int j = 0; j < g.defaults().size(); j++)
                defaults("/rules/prioritizedDefaults/" + i + "/defaults/" + j, g, g.defaults().get(j));
        }
        for (ResolvedConsistency c : model.consistencies)
            forms.put(document.positions().versionOf(c), new Form(null, false, c.assertion(), null));
        for (ConditionReading r : readings)
            if (r.status() == ConditionReading.Status.OPEN)
                open.computeIfAbsent(r.object(), o -> new ArrayList<>()).add(r);
    }

    private void defaults(String pointer, PrioritizedDefaults g, Default d) {
        forms.put(pointer, new Form(g.target(), g.isCharacteristic(), d.condition(),
                g.isCharacteristic() ? null : d.consequence()));
        if (d.exceptions() == null) return;
        List<ExceptionGroup> groups = d.exceptions().members();
        for (int m = 0; m < groups.size(); m++)
            for (int l = 0; l < groups.get(m).defaults().size(); l++)
                defaults(pointer + "/exceptions/members/" + m + "/defaults/" + l, g, groups.get(m).defaults().get(l));
    }

    // ---------------- the members ----------------

    /** Member {@code name} of {@code object} among its open members, a characteristic where {@code characteristic}; null
     *  where it is not open. */
    EndState.Open open(Object object, String name, boolean characteristic) {
        String key = Namen.norm(name);
        for (EndState.Open o : end(object).open())
            if (o.characteristic() == characteristic && Namen.norm(o.name()).equals(key)) return o;
        return null;
    }

    /** Whether {@code member} is input on its object: nothing but an initialisation writes it there. */
    boolean input(OpenValue member) {
        return !written(model, document.groups(), typeOf(member.object()), member.member(), member.characteristic());
    }

    /** Whether {@code member}, an open member, is open by its own input: an input member ({@link #input}) that no form left
     *  open, so that its initialisation was not at its turn. A complete input member without a value is settled leeg above
     *  its initialisation, which is at its turn there, and open only where the initialisation leaves it so by an open read
     *  (ch. 1.5, "An initialisation"): that member is open by what its initialisation reads, not by its own input. */
    boolean notGiven(OpenValue member) {
        return input(member) && writers(member.object(), member.member(), member.characteristic()).isEmpty();
    }

    /** Whether a form other than an initialisation writes member {@code name}, a characteristic where
     *  {@code characteristic}, on the objects of {@code type}, one on {@code type} or on a type above it: a rule other than an
     *  initialisation, or one of {@code groups}, the defaults groups of the document, with a default other than its
     *  initialisation default or with an exception group. An initialisation is the lowest writer of its target and makes no
     *  member derived: a member that only an initialisation writes is an input member with a default value (ch. 1.5, "The
     *  three states", "An initialisation"). */
    public static boolean written(RegelSpraakModel model, List<PrioritizedDefaults> groups, ObjectType type, String name,
                                  boolean characteristic) {
        for (ResolvedRule r : model.resolvedRules)
            if (r.kind() != ResolvedRule.Kind.INITIALISATIE
                    && r.target() instanceof Expr.AttributeReference a && a.objectType() != null && a.attribute() != null
                    && (r.kind() == ResolvedRule.Kind.KENMERKTOEKENNING) == characteristic
                    && Namen.norm(a.attribute()).equals(Namen.norm(name)) && type.isA(a.objectType()))
                return true;
        for (PrioritizedDefaults g : groups)
            if (g.isCharacteristic() == characteristic && g.target() != null
                    && Namen.norm(g.target()).equals(Namen.norm(name))
                    && type.isA(model.objectTypeByName(g.subject()))
                    && g.defaults().stream().anyMatch(d -> !d.initialisation() || d.exceptions() != null))
                return true;
        return false;
    }

    private EndState.EndObject end(Object object) {
        EndState.EndObject e = ends.get(object);
        if (e == null) throw new IllegalStateException("uchars eindtoestand noemt een object niet waarvan een lezing spreekt");
        return e;
    }

    private ObjectType typeOf(Object object) {
        return model.objectTypeByName(end(object).type());
    }

    // ---------------- what left a form open ----------------

    /** The reading of form {@code form} on its nodes: those of the condition and then of the value, by their numbers. */
    private final class Nodes {
        private final Map<Object, Integer> numbers = new IdentityHashMap<>();
        private final Map<Integer, NodeReading> read = new HashMap<>();

        Nodes(Form f, ConditionReading r) {
            int offset = 0;
            for (Object root : new Object[]{f.condition(), f.value()}) {
                if (root == null) continue;
                for (Object node : document.positions().subtree(root)) numbers.put(node, offset++);
            }
            for (NodeReading n : r.nodes()) read.put(n.node(), n);
        }

        /** The reading of {@code node}; null where the engine gave none, inside a fold. */
        NodeReading of(Object node) { return read.get(numbers.get(node)); }

        boolean undetermined(Object node) { return of(node) != null && of(node).undetermined(); }
    }

    /** The open reads that left the form of {@code r}, a reading with the status {@code OPEN}, so: of its condition where that
     *  is undetermined, of its value otherwise; the first one is the one uchar's reading names at the root.
     *
     * @throws IllegalStateException where there is none, which an open outcome does not have */
    List<OpenValue> of(ConditionReading r) {
        Form f = form(r);
        Nodes n = new Nodes(f, r);
        Set<OpenValue> out = new LinkedHashSet<>();
        if (f.condition() != null && n.undetermined(f.condition())) undetermined(f.condition(), n, out);
        if (out.isEmpty() && f.value() != null && n.undetermined(f.value())) out.add(n.of(f.value()).open());
        if (out.isEmpty())
            throw new IllegalStateException("de motor liet " + r.form() + " op " + r.object().getClass().getSimpleName()
                    + " open, en haar lezing noemt geen open lid");
        return List.copyOf(out);
    }

    /** What keeps member {@code name} of {@code object} open, a characteristic where {@code characteristic}:
     *  {@link #of(ConditionReading)} of the first form that leaves it open, or, where none does, the member itself, an input
     *  member that only an initialisation writes, which is not at its turn while the member is open (ch. 1.5, "An
     *  initialisation"). */
    List<OpenValue> of(Object object, String name, boolean characteristic) {
        List<ConditionReading> open = writers(object, name, characteristic);
        if (!open.isEmpty()) return of(open.get(0));
        EndState.Open o = open(object, name, characteristic);
        OpenValue member = o == null ? null : new OpenValue(object, o.name(), characteristic);
        if (member == null || !input(member))
            throw new IllegalStateException("de motor gaf '" + name + "' als open, en geen vorm die het schrijft staat open");
        return List.of(member);
    }

    /** The readings of the forms that leave member {@code name} of {@code object} open. */
    private List<ConditionReading> writers(Object object, String name, boolean characteristic) {
        String key = Namen.norm(name);
        List<ConditionReading> out = new ArrayList<>();
        for (ConditionReading r : open.getOrDefault(object, List.of())) {
            Form f = form(r);
            if (f.member() != null && f.characteristic() == characteristic && Namen.norm(f.member()).equals(key)) out.add(r);
        }
        return out;
    }

    private Form form(ConditionReading r) {
        Form f = forms.get(r.form());
        if (f == null)
            throw new IllegalStateException("de vorm " + r.form() + " staat niet in het document van de uitleg");
        return f;
    }

    /** The open reads of condition {@code c}, which is undetermined: of a composite those of its undetermined members, of any
     *  other the read its node names. */
    private void undetermined(Condition c, Nodes n, Set<OpenValue> out) {
        if (c instanceof Condition.Compound s) {
            for (Condition d : s.components()) if (n.undetermined(d)) undetermined(d, n, out);
            return;
        }
        out.add(n.of(c).open());
    }

    // ---------------- an open filter of a quantifier fold ----------------

    /** Whether {@code filter}, the filter of a quantifier fold in a form on {@code subject}, can read an open member on
     *  {@code element}, a member of its range: uchar's reading of the fold gives per member the verdict of its matrix, and not
     *  whether its filter stands there (ch. 1.5, "A quantifier fold"). Read over the nodes of the filter and the links of the
     *  end state, not evaluated: every branch of a choice, the value of every binder and the fallback of every sum count, so
     *  the answer is yes wherever the evaluation can have read an open member, and also where it did not. */
    boolean reads(Condition filter, Object element, Object subject) {
        if (filter == null) return false;
        Set<OpenValue> out = new LinkedHashSet<>();
        read(filter, element, subject, out);
        return !out.isEmpty();
    }

    /** The open members {@code root}, a condition or an expression evaluated on {@code on} in a form on {@code subject}, reads
     *  there: an attribute or a characteristic of its own or over a role, those of the members of a fold in it, and those of
     *  the fallback of a sum in it. */
    private void read(Object root, Object on, Object subject, Set<OpenValue> out) {
        if (root == null) return;
        List<Object> nodes = document.positions().subtree(root);
        for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            if (node instanceof Expr.AttributeReference a) {
                for (Object o : reached(a, a.anchored(), on, subject)) add(o, a.attribute(), false, out);
            } else if (node instanceof Condition.CharacteristicCheck k) {
                for (Object o : reached(k, k.anchored(), on, subject)) add(o, k.characteristic(), true, out);
            } else if (node instanceof Condition.QuantifierFold q) {
                Object from = document.loaded().anchored().contains(q) ? subject : on;
                for (Object e : range(q, from)) {
                    read(q.filter(), e, subject, out);
                    read(q.test(), e, subject, out);
                }
                i += document.positions().subtree(q).size() - 1;
            } else if (node instanceof Expr.FunctionCall f && overASet(f)) {
                Object from = f.anchored() || document.loaded().anchored().contains(f) ? subject : on;
                if (f.arguments().get(0) instanceof Expr.InstanceSetUnion u) {
                    // a count over a union reads nothing but the filter of each selection, on the members of its one step
                    for (Expr.InstanceSetUnion.Branch t : u.branches())
                        for (Object e : range(List.of(t.sourceRole()), t.elementType(), from))
                            read(t.filter(), e, subject, out);
                } else {
                    for (Object e : range(f, from)) {
                        read(f.filter(), e, subject, out);
                        read(f.arguments().get(0), e, subject, out);
                    }
                }
                // the fallback of a sum is evaluated every time the sum is, on what the sum is evaluated on, also over an
                // empty range (RUNTIME-SEMANTICS ch. 5.11)
                read(f.emptyFallback(), on, subject, out);
                i += document.positions().subtree(f).size() - 1;
            }
        }
    }

    private void add(Object object, String name, boolean characteristic, Set<OpenValue> out) {
        EndState.Open o = open(object, name, characteristic);
        if (o != null) out.add(new OpenValue(object, o.name(), characteristic));
    }

    /** Whether {@code f} is a fold over a set with a range of its own ({@link #range}). */
    private boolean overASet(Expr.FunctionCall f) {
        return document.loaded().roleBindings().containsKey(f) || document.loaded().sourcePaths().containsKey(f)
                || f.arguments().size() == 1 && f.arguments().get(0) instanceof Expr.InstanceSetUnion;
    }

    /** The members of the range of {@code f}, a fold over a set, from {@code on}. */
    private List<Object> range(Expr.FunctionCall f, Object on) {
        List<RoleBindingRef> path = document.loaded().sourcePaths().get(f);
        RoleBindingRef role = document.loaded().roleBindings().get(f);
        if (path == null && role == null)
            throw new IllegalStateException("een vouw " + f.kind() + " over een verzameling heeft geen bereik in het document");
        return range(path != null ? path : List.of(role), f.elementObjectType(), on);
    }

    private List<Object> range(Condition.QuantifierFold q, Object on) {
        return range(q.sourceRole() != null ? List.of(q.sourceRole()) : q.sourcePath(), q.elementObjectType(), on);
    }

    private List<Object> range(List<RoleBindingRef> steps, ObjectType element, Object on) {
        List<Object> out = new ArrayList<>();
        if (steps.isEmpty()) return out;
        for (Object o : walk(on, steps)) if (element == null || typeOf(o).isA(element)) out.add(o);
        return out;
    }

    /** The objects the read {@code node} reads its member on, from {@code on}, or from {@code subject} where it is anchored to
     *  the form (IR-SPEC ch. 4.1a). */
    private List<Object> reached(Object node, boolean anchored, Object on, Object subject) {
        Object from = anchored || document.loaded().anchored().contains(node) ? subject : on;
        List<RoleBindingRef> walk = document.loaded().navigationBindings().get(node);
        return walk == null || walk.isEmpty() ? List.of(from) : walk(from, walk);
    }

    /** The objects {@code steps} reach from {@code from} over the links of the end state, each once and in the order of its
     *  objects: every step names the role the object it reaches fills. */
    private List<Object> walk(Object from, List<RoleBindingRef> steps) {
        List<Object> at = List.of(from);
        for (RoleBindingRef step : steps) {
            String factType = Namen.norm(step.factType()), role = Namen.norm(step.role());
            List<Object> next = new ArrayList<>();
            for (Object o : at)
                for (EndState.Link k : links) {
                    if (!Namen.norm(k.factType()).equals(factType)) continue;
                    if (k.first() == o && Namen.norm(k.secondRole()).equals(role)) once(next, k.second());
                    if (k.second() == o && Namen.norm(k.firstRole()).equals(role)) once(next, k.first());
                }
            next.sort(Comparator.comparingInt(order::get));
            at = next;
        }
        return at;
    }

    private static void once(List<Object> sink, Object o) {
        if (sink.stream().noneMatch(p -> p == o)) sink.add(o);
    }

    // ---------------- the input that can be given ----------------

    /**
     * The open input members that keep one of {@code outcomes} open, each once, with the outcomes it keeps open: an outcome
     * that is open itself is followed over the forms that leave it open ({@link #of}) to the members open by their own input
     * ({@link #notGiven}). They come in the order of {@code instances} and then of the declarations; an outcome that is not
     * open has none.
     *
     * <p>It is what the session met and can be given now: a composite was read past its first undetermined member, but a value
     * broke off at its first open read and a fold names its first open member, so once these are given another can turn out
     * open.
     *
     * @throws IllegalStateException an open outcome without such a member, or one on an object that is none of
     *                               {@code instances}
     */
    public List<OpenInput> openInputs(List<Object> instances, List<OpenValue> outcomes) {
        Map<OpenValue, List<OpenValue>> found = new LinkedHashMap<>();
        for (OpenValue outcome : outcomes) {
            EndState.Open o = open(outcome.object(), outcome.member(), outcome.characteristic());
            if (o == null) continue;
            Set<OpenValue> inputs = new LinkedHashSet<>();
            follow(new OpenValue(outcome.object(), o.name(), outcome.characteristic()), new LinkedHashSet<>(), inputs);
            if (inputs.isEmpty())
                throw new IllegalStateException("het lid '" + outcome.member() + "' van een "
                        + end(outcome.object()).type() + " staat open, en geen invoerlid houdt het open");
            for (OpenValue i : inputs) found.computeIfAbsent(i, k -> new ArrayList<>()).add(outcome);
        }
        List<OpenInput> out = new ArrayList<>();
        for (Object instance : instances)
            for (EndState.Open o : end(instance).open()) {
                OpenValue member = new OpenValue(instance, o.name(), o.characteristic());
                List<OpenValue> of = found.remove(member);
                if (of != null) out.add(new OpenInput(member, List.copyOf(of)));
            }
        if (!found.isEmpty())
            throw new IllegalStateException("een open uitkomst rust op een lid dat geen open lid van een instantie is: "
                    + found.keySet().stream().map(OpenValue::member).toList());
        return out;
    }

    /** Every open member of {@code instances} that is not open by its own input ({@link #notGiven}): a derived member, or a
     *  complete input member that its initialisation leaves open; in their order and that of the declarations. */
    public List<OpenValue> derived(List<Object> instances) {
        List<OpenValue> out = new ArrayList<>();
        for (Object instance : instances)
            for (EndState.Open o : end(instance).open()) {
                OpenValue member = new OpenValue(instance, o.name(), o.characteristic());
                if (!notGiven(member)) out.add(member);
            }
        return out;
    }

    private void follow(OpenValue member, Set<OpenValue> seen, Set<OpenValue> inputs) {
        if (!seen.add(member)) return;
        if (notGiven(member)) {
            inputs.add(member);
            return;
        }
        for (ConditionReading r : writers(member.object(), member.member(), member.characteristic()))
            for (OpenValue read : of(r)) follow(read, seen, inputs);
    }
}
