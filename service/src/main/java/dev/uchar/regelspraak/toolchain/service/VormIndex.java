package dev.uchar.regelspraak.toolchain.service;

import dev.uchar.regelspraak.emit.CaseEmitter;
import dev.uchar.regelspraak.ir.PrioritizedDefaults;
import dev.uchar.regelspraak.ir.ResolvedRegelGroep;
import dev.uchar.regelspraak.model.RegelSpraakModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The forms of a model's rule layer by the names a descriptor scope uses. A form is what uchar lowers a scope to
 * ({@link CaseEmitter#metScope}): a rule (each version and each decision-table row a form of its own), an object
 * creation, an allocation, a consistency, a uniqueness, a fact creation or a defaults group. A scope name carries the
 * forms of that name, or else the rows of the decision table of that name; a rule group carries the forms of its
 * members. That is how uchar's scenario resolver reads a scope, so an entry point and a scenario that name the same
 * rules build the same document.
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

    private VormIndex(List<String> vormen, Set<String> gedeclareerd, List<ResolvedRegelGroep> groepen,
                      List<RegelSpraakModel.Diagnostic> waarschuwingen) {
        this.vormen = vormen;
        this.gedeclareerd = gedeclareerd;
        this.groepen = groepen;
        this.waarschuwingen = waarschuwingen;
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
                List.copyOf(waarschuwingen));
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

    /** The forms {@code scope} carries: those of each rule name, and those of each member of each rule group. */
    public Keuze scope(DienstDescriptor.Scope scope) {
        LinkedHashSet<String> uit = new LinkedHashSet<>();
        List<String> zonderVorm = new ArrayList<>();
        List<String> namen = new ArrayList<>(scope.regelnamenOfLeeg());
        for (String groep : scope.regelgroepenOfLeeg())
            for (ResolvedRegelGroep g : groepen) if (g.naam().equals(groep)) namen.addAll(g.leden());
        for (String naam : namen) {
            List<String> v = vormenVan(naam);
            if (v.isEmpty()) { if (!zonderVorm.contains(naam)) zonderVorm.add(naam); }
            else uit.addAll(v);
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
