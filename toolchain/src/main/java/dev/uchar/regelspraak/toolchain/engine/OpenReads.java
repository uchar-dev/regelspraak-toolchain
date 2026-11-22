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
 * the document. Nothing is evaluated here. A form that left its target open is a reading with the status {@code OPEN}; what
 * left it so is the open read at its undetermined condition, per member of a composite that is undetermined itself, or at
 * its value ({@link #of}); and a derived member is followed over its open forms to the members no form writes, the input
 * that can be given ({@link #openInputs}).
 *
 * <p>uchar's readings name the read a closure closed over where that is the operand of a test on leeg or gevuld. Where the
 * closure is inside a fold — the filled-value count, a filter or a quantified test on gevuld — its node carries no open
 * read: there the reads are the open members that the filter, the test or the counted attribute reads on the members of
 * the range, over the links of the end state ({@link #inFold}).
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

    /** Whether {@code member} is input on its object: no rule and no defaults group of the document writes it there. */
    boolean input(OpenValue member) {
        return !written(model, document.groups(), typeOf(member.object()), member.member(), member.characteristic());
    }

    /** Whether a rule or one of {@code groups}, the defaults groups of the document, writes member {@code name}, a
     *  characteristic where {@code characteristic}, on the objects of {@code type}: one on {@code type} or on a type above it
     *  (ch. 1.5, "An input member"). */
    public static boolean written(RegelSpraakModel model, List<PrioritizedDefaults> groups, ObjectType type, String name,
                                  boolean characteristic) {
        for (ResolvedRule r : model.resolvedRules)
            if (r.target() instanceof Expr.AttributeReference a && a.objectType() != null && a.attribute() != null
                    && (r.kind() == ResolvedRule.Kind.KENMERKTOEKENNING) == characteristic
                    && Namen.norm(a.attribute()).equals(Namen.norm(name)) && type.isA(a.objectType()))
                return true;
        for (PrioritizedDefaults g : groups)
            if (g.isCharacteristic() == characteristic && g.target() != null
                    && Namen.norm(g.target()).equals(Namen.norm(name))
                    && type.isA(model.objectTypeByName(g.subject())))
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
     *  does not stand, of its value otherwise; the first one is the one uchar's reading names at the root where it names one.
     *
     * @throws IllegalStateException where there is none, which an open outcome does not have */
    List<OpenValue> of(ConditionReading r) {
        Form f = form(r);
        Nodes n = new Nodes(f, r);
        Set<OpenValue> out = new LinkedHashSet<>();
        if (f.condition() != null && n.of(f.condition()) != null) {
            NodeReading c = n.of(f.condition());
            if (c.undetermined()) undetermined(f.condition(), n, out);
            else if (c.closure() && !Boolean.TRUE.equals(c.value())) closed(f.condition(), n, r.object(), true, out);
        }
        if (out.isEmpty() && f.value() != null && n.of(f.value()) != null) {
            if (n.undetermined(f.value())) out.add(n.of(f.value()).open());
            else inFolds(f.value(), n, r.object(), false, out);
        }
        if (out.isEmpty())
            throw new IllegalStateException("de motor liet " + r.form() + " op " + r.object().getClass().getSimpleName()
                    + " open, en haar lezing noemt geen open lid");
        return List.copyOf(out);
    }

    /** {@link #of(ConditionReading)} of the first form that leaves member {@code name} of {@code object} open, a characteristic
     *  where {@code characteristic}. */
    List<OpenValue> of(Object object, String name, boolean characteristic) {
        return of(writers(object, name, characteristic).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("de motor gaf '" + name + "' als open, en geen vorm die het"
                        + " schrijft staat open")));
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

    /** The open reads over which a closure decided the form of {@code r}, a reading with the status {@code HOLDS}: its condition
     *  holds for the form itself alone, or its value rests on a closure; empty where what it gave stands for another form as
     *  well. A closure in a value is known where it is certain ({@link #inFolds}). */
    List<OpenValue> closedBy(ConditionReading r) {
        Form f = form(r);
        Nodes n = new Nodes(f, r);
        Set<OpenValue> out = new LinkedHashSet<>();
        if (f.condition() != null && n.of(f.condition()) != null && n.of(f.condition()).closure())
            closed(f.condition(), n, r.object(), false, out);
        if (f.value() != null) inFolds(f.value(), n, r.object(), true, out);
        return List.copyOf(out);
    }

    /** The open reads over which a closure made {@code value}, a value of a form on {@code subject} that was read leeg, so; with
     *  {@code certain} only those that are certain. */
    List<OpenValue> closedOver(Expr value, Object subject, boolean certain) {
        Set<OpenValue> out = new LinkedHashSet<>();
        inFolds(value, null, subject, certain, out);
        return List.copyOf(out);
    }

    private Form form(ConditionReading r) {
        Form f = forms.get(r.form());
        if (f == null)
            throw new IllegalStateException("de vorm " + r.form() + " staat niet in het document van de uitleg");
        return f;
    }

    /** The open reads of condition {@code c}, undetermined for the form itself: of a composite those of its undetermined
     *  members, of any other the read its node names. */
    private void undetermined(Condition c, Nodes n, Set<OpenValue> out) {
        if (c instanceof Condition.Compound s) {
            for (Condition d : s.components()) if (n.undetermined(d)) undetermined(d, n, out);
            return;
        }
        out.add(n.of(c).open());
    }

    /** The open reads a closure in condition {@code c} closed over, {@code c} standing for the form itself alone: of a
     *  composite those of its members that do so, and with {@code all} of the undetermined ones too; of a test on leeg or
     *  gevuld the read of its operand; and of a fold {@link #inFold}. */
    private void closed(Condition c, Nodes n, Object subject, boolean all, Set<OpenValue> out) {
        if (c instanceof Condition.Compound s) {
            for (Condition d : s.components()) {
                NodeReading r = n.of(d);
                if (r == null || !r.reached()) continue;
                if (r.closure()) closed(d, n, subject, all, out);
                else if (all && r.undetermined()) undetermined(d, n, out);
            }
            return;
        }
        if (c instanceof Condition.QuantifierFold) {
            inFold(c, subject, subject, false, out);
            return;
        }
        int before = out.size();
        for (Object node : document.positions().subtree(c))
            if (node != c && n.undetermined(node)) {
                out.add(n.of(node).open());
                break;
            }
        if (out.size() == before) for (Expr side : sides(c)) inFolds(side, n, subject, false, out);
    }

    /** The operands of an atomic condition. */
    private static List<Expr> sides(Condition c) {
        return c instanceof Condition.Comparison v ? List.of(v.left(), v.right())
                : c instanceof Condition.Predicate p ? List.of(p.operand()) : List.of();
    }

    // ---------------- a closure inside a fold ----------------

    /** The open reads the folds of {@code root}, evaluated on {@code subject}, closed over, where the engine reached them
     *  ({@code n}, null where that is not asked); with {@code certain} only those that a closure surely closed over: a filter
     *  that is the test itself, or a filled-value count without a filter. */
    private void inFolds(Expr root, Nodes n, Object subject, boolean certain, Set<OpenValue> out) {
        List<Object> nodes = document.positions().subtree(root);
        for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            if (node instanceof Expr.BinderRef b) {
                Expr.Let let = letOf(b);
                if (let != null && let.value() != root) inFolds(let.value(), n, subject, certain, out);
                continue;
            }
            if (!(node instanceof Expr.FunctionCall f) || !overASet(f)) continue;
            if (n == null || n.of(f) != null && n.of(f).reached() && !n.of(f).undetermined())
                inFold(f, subject, subject, certain, out);
            // the nodes below are read per member of this fold
            i += document.positions().subtree(f).size() - 1;
        }
    }

    /** The Let whose binder {@code ref} reads, the nearest around it; null where the document has none. */
    private Expr.Let letOf(Expr.BinderRef ref) {
        String at = document.positions().of(ref).pointer();
        Expr.Let found = null;
        int depth = -1;
        for (Expr.Let l : document.loaded().lets()) {
            String p = document.positions().of(l).pointer();
            if (l.binder().equals(ref.name()) && at.startsWith(p + "/") && p.length() > depth) {
                depth = p.length();
                found = l;
            }
        }
        return found;
    }

    /** The open members the closures of {@code fold}, whose range starts at {@code on}, read on the members of that range: in
     *  its filter, in the test of a quantifier fold, and as the attribute of a filled-value count. */
    private void inFold(Object fold, Object on, Object subject, boolean certain, Set<OpenValue> out) {
        if (fold instanceof Condition.QuantifierFold q) {
            for (Object e : range(q, on)) {
                read(q.filter(), e, subject, out);
                read(q.test(), e, subject, out);
            }
            return;
        }
        Expr.FunctionCall f = (Expr.FunctionCall) fold;
        if (f.arguments().size() == 1 && f.arguments().get(0) instanceof Expr.InstanceSetUnion u) {
            for (Expr.InstanceSetUnion.Branch b : u.branches())
                for (Object e : walk(on, List.of(b.sourceRole())))
                    if (typeOf(e).isA(b.elementType()) && (!certain || test(b.filter()))) read(b.filter(), e, subject, out);
            return;
        }
        for (Object e : range(f, on)) {
            if (!certain || test(f.filter())) read(f.filter(), e, subject, out);
            if (f.kind() == Expr.FunctionKind.AANTAL_GEVULDE && (!certain || f.filter() == null))
                read(f.arguments().get(0), e, subject, out);
        }
    }

    /** Whether {@code filter} is a test on leeg or gevuld and nothing else. */
    private static boolean test(Condition filter) {
        if (filter instanceof Condition.Compound s && s.components().size() == 1) return test(s.components().get(0));
        return filter instanceof Condition.Predicate p
                && (p.kind() == Condition.PredicateKind.LEEG || p.kind() == Condition.PredicateKind.GEVULD);
    }

    /** Whether {@code c}, a filter or a test of a quantifier fold in a form on {@code subject}, reads an open member on
     *  {@code element}, a member of its range; with {@code closures} only in a test on leeg or gevuld or inside a fold. */
    boolean reads(Condition c, Object element, Object subject, boolean closures) {
        if (c == null) return false;
        Set<OpenValue> out = new LinkedHashSet<>();
        if (!closures) read(c, element, subject, out);
        else for (Object node : document.positions().subtree(c)) {
            if (node instanceof Condition.Predicate p && test(p)) read(p.operand(), element, subject, out);
            else if (node instanceof Condition.QuantifierFold q) read(q, element, subject, out);
            else if (node instanceof Expr.FunctionCall f && overASet(f)) inFold(f, element, subject, false, out);
        }
        return !out.isEmpty();
    }

    /** The open members {@code root}, a condition or an expression evaluated on {@code on} in a form on {@code subject}, reads
     *  there: an attribute or a characteristic of its own or over a role, and those of the members of a fold in it. */
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
                for (Object e : range(f, from)) {
                    read(f.filter(), e, subject, out);
                    read(f.arguments().get(0), e, subject, out);
                }
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
        return range(path != null ? path : role != null ? List.of(role) : List.of(), f.elementObjectType(), on);
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
     * that is open itself is followed over the forms that leave it open ({@link #of}) to the members no form writes. They come
     * in the order of {@code instances} and then of the declarations; an outcome that is not open has none.
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

    /** Every open member a form writes of {@code instances}, in their order and that of the declarations. */
    public List<OpenValue> derived(List<Object> instances) {
        List<OpenValue> out = new ArrayList<>();
        for (Object instance : instances)
            for (EndState.Open o : end(instance).open()) {
                OpenValue member = new OpenValue(instance, o.name(), o.characteristic());
                if (!input(member)) out.add(member);
            }
        return out;
    }

    private void follow(OpenValue member, Set<OpenValue> seen, Set<OpenValue> inputs) {
        if (!seen.add(member)) return;
        if (input(member)) {
            inputs.add(member);
            return;
        }
        for (ConditionReading r : writers(member.object(), member.member(), member.characteristic()))
            for (OpenValue read : of(r)) follow(read, seen, inputs);
    }
}
