package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.graaf.ProducentConsument;
import dev.uchar.regelspraak.ir.Default;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRegelGroep;
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
 * ({@link CaseEmitter#metScope}): a rule (each version and each decision-table row a form of its own), an object
 * creation, an allocation, a consistency, a uniqueness, a fact creation or a defaults group. A scope name carries the
 * forms of that name, or else the rows of the decision table of that name; a rule group carries the forms of its
 * members. That is how uchar's scenario resolver reads a scope, so an entry point and a scenario that name the same
 * rules build the same document, with one addition: a defaults group without a base of its own — what uchar makes of
 * the own exceptions of a subtype under the base rule of its supertype, {@code <basis> voor <subtype>} — goes with the
 * group whose base it inherits, and with the other such groups of that base. RUNTIME-SEMANTICS ch. 7.10 composes the
 * stack of a concrete type from both, so a scope that carries one of them carries all of them; no author wrote a rule
 * by the generated name, so none has to name it.
 *
 * <p>A name the source declares as a rule but that carries no form is a rule uchar does not carry as written (its
 * frontend says why in a warning), or an exception rule, which lives in the defaults group of its base rule. An entry
 * point that scopes such a name would answer without that rule; {@link BeslisserKern} does not serve it.
 */
public final class VormIndex {

    private final List<String> vormen;
    private final Set<String> gedeclareerd;
    private final List<ResolvedRegelGroep> groepen;
    private final List<RegelSpraakModel.Diagnostic> waarschuwingen;
    /** Per defaults group (by its normalised form name) the groups of its stack, itself included, in model order;
     *  only for a group that has company. */
    private final Map<String, List<String>> stapelgenoten;

    private VormIndex(List<String> vormen, Set<String> gedeclareerd, List<ResolvedRegelGroep> groepen,
                      List<RegelSpraakModel.Diagnostic> waarschuwingen, Map<String, List<String>> stapelgenoten) {
        this.vormen = vormen;
        this.gedeclareerd = gedeclareerd;
        this.groepen = groepen;
        this.waarschuwingen = waarschuwingen;
        this.stapelgenoten = stapelgenoten;
    }

    /** The forms of {@code model}, with {@code defaults} its defaults groups: a resolver's model holds them
     *  ({@code model.prioritizedDefaults}), a model uchar's loader rebuilt carries them beside it. A form is listed
     *  under its name without a version suffix, once. */
    public static VormIndex van(RegelSpraakModel model, List<PrioritizedDefaults> defaults) {
        LinkedHashSet<String> namen = new LinkedHashSet<>();
        for (var r : model.resolvedRegels) voegToe(namen, r.naam());
        for (var x : model.objectCreaties) voegToe(namen, x.naam());
        for (var x : model.verdelingen) voegToe(namen, x.naam());
        for (var x : model.consistenties) voegToe(namen, x.naam());
        for (var x : model.uniciteiten) voegToe(namen, x.naam());
        for (var x : model.uniciteitUnies) voegToe(namen, x.naam());
        for (var x : model.feitCreaties) voegToe(namen, x.naam());
        for (var x : defaults) voegToe(namen, x.naam());
        Set<String> gedeclareerd = new LinkedHashSet<>();
        for (var r : model.regels) if (r.naam() != null) gedeclareerd.add(CaseEmitter.norm(r.naam()));
        List<RegelSpraakModel.Diagnostic> waarschuwingen = new ArrayList<>();
        for (var d : model.diagnostics)
            if (d.severity() == RegelSpraakModel.Severity.WARNING) waarschuwingen.add(d);
        return new VormIndex(List.copyOf(namen), gedeclareerd, List.copyOf(model.regelGroepen),
                List.copyOf(waarschuwingen), stapelgenoten(model, defaults));
    }

    /**
     * The groups that compose one stack per concrete type (RUNTIME-SEMANTICS ch. 7.10), each with the others. A group
     * on a subtype without a base of its own decides over the base it inherits: it is joined to every group of the
     * nearest type above its subject that writes the same target, and a group there without a base reaches further up
     * in its turn. A group with a base of its own does not reach up, since it is the base of its stack, and neither
     * does a declared-unordered one, which uchar's composition does not fold over an inherited stack. The target is
     * the member's identity of uchar's rule graph ({@link ProducentConsument#elementVan}), so a member written on the
     * supertype and on a subtype is one target, as ch. 7.10 composes it.
     */
    private static Map<String, List<String>> stapelgenoten(RegelSpraakModel model, List<PrioritizedDefaults> defaults) {
        List<PrioritizedDefaults> benoemd = new ArrayList<>();
        for (PrioritizedDefaults p : defaults) if (p.naam() != null) benoemd.add(p);
        Map<PrioritizedDefaults, PrioritizedDefaults> ouder = new IdentityHashMap<>();
        for (PrioritizedDefaults p : benoemd) ouder.put(p, p);
        for (PrioritizedDefaults p : benoemd) {
            RegelSpraakModel.Objecttype onderwerp = model.objecttypeOpNaam(p.onderwerp());
            if (onderwerp == null || p.ongeordend() || eigenBasis(p)) continue;
            ProducentConsument.ElementSleutel doel = ProducentConsument.elementVan(onderwerp, p.doel(), p.isKenmerk());
            List<RegelSpraakModel.Objecttype> keten = onderwerp.keten();
            for (RegelSpraakModel.Objecttype hoger : keten.subList(1, keten.size())) {
                List<PrioritizedDefaults> daar = new ArrayList<>();
                for (PrioritizedDefaults q : benoemd)
                    if (model.objecttypeOpNaam(q.onderwerp()) == hoger
                            && ProducentConsument.elementVan(hoger, q.doel(), q.isKenmerk()).equals(doel))
                        daar.add(q);
                if (daar.isEmpty()) continue;
                for (PrioritizedDefaults q : daar) verenig(ouder, p, q);
                break;
            }
        }
        Map<PrioritizedDefaults, List<String>> perWortel = new IdentityHashMap<>();
        for (PrioritizedDefaults p : benoemd)
            perWortel.computeIfAbsent(wortel(ouder, p), w -> new ArrayList<>()).add(CaseEmitter.basisNaam(p.naam()));
        Map<String, List<String>> uit = new HashMap<>();
        for (PrioritizedDefaults p : benoemd) {
            List<String> stapel = perWortel.get(wortel(ouder, p));
            if (stapel.size() > 1) uit.put(CaseEmitter.norm(CaseEmitter.basisNaam(p.naam())), List.copyOf(stapel));
        }
        return uit;
    }

    /** Whether {@code p} has a base of its own, as uchar's composition reads one: its lowest ordinary default decides
     *  something itself. A group-default delegates to its exception groups and is no base. */
    private static boolean eigenBasis(PrioritizedDefaults p) {
        Default laatste = null;
        for (Default d : p.defaults()) if (!d.initialisatie()) laatste = d;
        return laatste != null && laatste.heeftEigenInhoud();
    }

    private static PrioritizedDefaults wortel(Map<PrioritizedDefaults, PrioritizedDefaults> ouder, PrioritizedDefaults p) {
        while (ouder.get(p) != p) p = ouder.get(p);
        return p;
    }

    private static void verenig(Map<PrioritizedDefaults, PrioritizedDefaults> ouder, PrioritizedDefaults a,
                                PrioritizedDefaults b) {
        PrioritizedDefaults wa = wortel(ouder, a), wb = wortel(ouder, b);
        if (wa != wb) ouder.put(wa, wb);
    }

    private static void voegToe(Set<String> namen, String naam) {
        if (naam != null) namen.add(CaseEmitter.basisNaam(naam));
    }

    /** Every form, in model order. */
    public List<String> alle() { return vormen; }

    /** The forms rule name {@code naam} carries: the forms of that name, or else the rows of the decision table of
     *  that name ({@code "<naam> <n>"}). Empty when it names no form. */
    public List<String> vormenVan(String naam) {
        String n = CaseEmitter.norm(naam);
        List<String> uit = new ArrayList<>();
        for (String v : vormen) if (CaseEmitter.norm(v).equals(n)) uit.add(v);
        if (!uit.isEmpty()) return uit;
        Pattern rij = Pattern.compile(Pattern.quote(n) + " [0-9]+");
        for (String v : vormen) if (rij.matcher(CaseEmitter.norm(v)).matches()) uit.add(v);
        return uit;
    }

    /** Whether a scope may name {@code naam}: it carries a form, or the source declares a rule of that name. */
    public boolean bestaat(String naam) {
        return !vormenVan(naam).isEmpty() || gedeclareerd.contains(CaseEmitter.norm(naam));
    }

    /** What a scope carries: the forms, and the named rules that carry none, in the order the scope names them. */
    public record Keuze(List<String> vormen, List<String> zonderVorm) {}

    /** The forms {@code scope} carries: those of each rule name, and those of each member of each rule group, each
     *  defaults group with the groups of its stack. */
    public Keuze scope(DienstDescriptor.Scope scope) {
        LinkedHashSet<String> uit = new LinkedHashSet<>();
        List<String> zonderVorm = new ArrayList<>();
        List<String> namen = new ArrayList<>(scope.regelnamenOfLeeg());
        for (String groep : scope.regelgroepenOfLeeg())
            for (ResolvedRegelGroep g : groepen) if (g.naam().equals(groep)) namen.addAll(g.leden());
        for (String naam : namen) {
            List<String> v = vormenVan(naam);
            if (v.isEmpty()) { if (!zonderVorm.contains(naam)) zonderVorm.add(naam); }
            else for (String vorm : v) {
                uit.add(vorm);
                uit.addAll(stapelgenoten.getOrDefault(CaseEmitter.norm(vorm), List.of()));
            }
        }
        return new Keuze(List.copyOf(uit), List.copyOf(zonderVorm));
    }

    /** uchar's warnings that name rule {@code naam} in quotes: why its frontend did not make a form of it. */
    public List<String> waarschuwingenOver(String naam) {
        String gequoot = "'" + CaseEmitter.norm(naam) + "'";
        List<String> uit = new ArrayList<>();
        for (var d : waarschuwingen)
            if (d.message() != null && CaseEmitter.norm(d.message()).contains(gequoot)) uit.add(d.message());
        return uit;
    }
}
