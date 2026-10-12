package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.cases.Invoer;
import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.model.RegelSpraakModel.Objecttype;
import dev.uchar.regelspraak.schema.FactModelGenerator;
import dev.uchar.regelspraak.transpiler.kern.Namen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes a role link between two fact beans the way uchar's replay writes a case's roles ({@code CaseRunner}, whose
 * step of writing them is not public API; the beans then go in through uchar's public {@code Invoer}, and each link
 * returns the list it puts its member in, {@link Koppeling}, which {@link Lijsten} orders as the replay orders it),
 * after RUNTIME-SEMANTICS ch. 5.7a: a supplied link establishes the relation in both directions. The role the link
 * names is taken by that name where it names a reference field the bean model derives, else by the type pair; a to-one
 * role is written on this bean, and on a one-to-one relation the far field as well; a to-many role is stored in the far
 * end's field, through the sister role. A reciprocal self-relation (§13.3.9.2) gets its reverse leg in a separate pass
 * over all beans ({@link #spiegelWederkerig}). A link the bean model cannot hold, or a second, different target on a
 * to-one field, is refused by name ({@link Geweigerd}).
 */
public final class RolSchrijver {

    /** A link this bean model cannot hold as written, or ({@link #tweedeDoel()}) a second, different target on a
     *  to-one field: one relation instance per to-one role, as uchar's case reader holds it (WF-013). */
    public static final class Geweigerd extends RuntimeException {
        private final boolean tweedeDoel;

        Geweigerd(String reden, boolean tweedeDoel) {
            super(reden);
            this.tweedeDoel = tweedeDoel;
        }

        public boolean tweedeDoel() { return tweedeDoel; }
    }

    private final RegelSpraakModel model;

    public RolSchrijver(RegelSpraakModel model) {
        this.model = model;
    }

    /** The list a written link puts a member in, as uchar's replay keeps it for the order of a to-many list
     *  ({@code Invoer.lijst}): {@code lid} holds {@code houder} in its reference field {@code rol}. A link written on
     *  the member itself, a to-one role from there, is {@code gespiegeld}: the member joins the holder's list through
     *  the reverse leg (RUNTIME-SEMANTICS ch. 5.7a); a to-many role written on the holder adds the member as written. */
    public record Koppeling(Object houder, String rol, Object lid, boolean gespiegeld) {}

    /**
     * The to-many lists the links of one input fill, in the order uchar's replay gives them to its entry
     * ({@code CaseRunner}, {@code Invoer.lijst}): per holder and per reference field of its members, the members written
     * under the holder first, in the order they were written, then the members whose own link the reverse leg mirrors
     * into the list, by label in plain byte order (RUNTIME-SEMANTICS ch. 5.7a, "member ORDER"), a member in both
     * counting once, where it was written. The order decides which member a fault of a fold names (ch. 5.11), so an
     * input put in with another order than the replay's can give another fault than the replay on the same case.
     */
    public static final class Lijsten {
        private final Map<Object, Map<String, Lijst>> perHouder = new IdentityHashMap<>();

        /** Adds link {@code k}, whose member {@code k.lid()} has the label {@code lidLabel}; a mirrored member is ordered
         *  by it. */
        public void voegToe(Koppeling k, String lidLabel) {
            Lijst l = perHouder.computeIfAbsent(k.houder(), h -> new LinkedHashMap<>())
                    .computeIfAbsent(Namen.ident(k.rol(), false), v -> new Lijst(k.rol()));
            if (k.gespiegeld()) l.gespiegeld.putIfAbsent(lidLabel, k.lid());
            else if (l.geschreven.stream().noneMatch(x -> x == k.lid())) l.geschreven.add(k.lid());
        }

        /** States every list on {@code invoer} and returns it. */
        public Invoer geefAan(Invoer invoer) {
            for (Map.Entry<Object, Map<String, Lijst>> houder : perHouder.entrySet())
                for (Lijst l : houder.getValue().values()) {
                    List<Object> leden = new ArrayList<>(l.geschreven);
                    for (Object lid : l.gespiegeld.values())
                        if (leden.stream().noneMatch(x -> x == lid)) leden.add(lid);
                    invoer.lijst(houder.getKey(), l.rol, leden);
                }
            return invoer;
        }
    }

    /** One to-many list: the members written under the holder, in the order they were written, and the mirrored
     *  members by label in byte order. */
    private static final class Lijst {
        final String rol;
        final List<Object> geschreven = new ArrayList<>();
        final TreeMap<String, Object> gespiegeld = new TreeMap<>(
                Comparator.comparing((String l) -> l.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned));

        Lijst(String rol) {
            this.rol = rol;
        }
    }

    /** Link {@code bean} (of type {@code tI}) to {@code doel} (of type {@code tL}) through the role the input names
     *  {@code rolSleutel}, and return the list the link puts its member in. */
    public Koppeling schrijf(Object bean, Objecttype tI, String rolSleutel, Object doel, Objecttype tL) {
        String viaNaam = model.verbindendeRolViaNaam(tI, tL, rolSleutel);
        String rolIL = viaNaam != null && geldigRolveld(tI, viaNaam, tL) ? viaNaam : model.verbindendeRol(tI, tL);
        String zuster = model.verbindendeRolViaElementnaam(tI, rolSleutel);
        String rolLI = zuster != null && geldigRolveld(tL, zuster, tI) ? zuster : model.verbindendeRol(tL, tI);
        if (rolIL != null && !model.naarVeelRol(tI, tL, rolIL)) {
            naamdelingsPoort(tI, rolIL, tL, rolSleutel);
            zetToOne(bean, rolIL, doel);
            if (rolLI != null && !rolLI.equalsIgnoreCase(rolIL) && !model.naarVeelRol(tL, tI, rolLI)) {
                naamdelingsPoort(tL, rolLI, tI, rolSleutel);
                zetToOne(doel, rolLI, bean);
            }
            return new Koppeling(doel, rolIL, bean, true);
        }
        if (rolLI == null || model.naarVeelRol(tL, tI, rolLI))
            throw new Geweigerd("de rol '" + rolSleutel + "' (" + tI.naam + " -> " + tL.naam
                    + ") bereikt geen naar-één referentieveld", false);
        naamdelingsPoort(tL, rolLI, tI, rolSleutel);
        zetToOne(doel, rolLI, bean);
        return new Koppeling(bean, rolLI, doel, false);
    }

    /** The reverse leg of every reciprocal self-relation among {@code beans} (label -> bean, in input order; its
     *  type in {@code typePerLabel}): one link holds both ways. */
    public void spiegelWederkerig(Map<String, Object> beans, Map<String, Objecttype> typePerLabel) {
        for (Map.Entry<String, Object> e : beans.entrySet()) {
            Object bean = e.getValue();
            String rol = FactModelGenerator.zelfpaarRol(model, typePerLabel.get(e.getKey()));
            if (rol == null) continue;
            Object partner = CaseSessie.lees(bean, Namen.getter(rol));
            if (partner == null) continue;
            Object terug = CaseSessie.lees(partner, Namen.getter(rol));
            if (terug == bean) continue;
            if (terug != null)
                throw new Geweigerd("de wederkerige rol '" + rol + "': het doel is al met een ander verbonden", true);
            roep(partner, Namen.setter(rol), bean.getClass(), bean);
        }
    }

    private boolean geldigRolveld(Objecttype drager, String rol, Objecttype doel) {
        return drager.naam.equalsIgnoreCase(doel.naam)
                ? FactModelGenerator.zelfpaarRollen(model, drager).contains(rol)
                : model.verbindendeRollen(drager, doel).contains(rol);
    }

    private void naamdelingsPoort(Objecttype drager, String rol, Objecttype doel, String geschreven) {
        if (!FactModelGenerator.heeftReferentieVeld(model, drager, rol, doel))
            throw new Geweigerd("de rol '" + geschreven + "' wordt de naar-één rol '" + rol + "' op " + drager.naam
                    + ", waarvan de bean het referentieveld niet draagt", false);
    }

    private static void zetToOne(Object drager, String rol, Object doel) {
        Object huidig = CaseSessie.lees(drager, Namen.getter(rol));
        if (huidig != null && huidig != doel)
            throw new Geweigerd("de naar-één rol '" + rol + "' krijgt een tweede, ander doel", true);
        roep(drager, Namen.setter(rol), doel.getClass(), doel);
    }

    /** Calls {@code bean.methode(waarde)}. The reference field of a role is typed as the role's declared filler
     *  (uchar's bean model since 2026-09-28), so an instance of a subtype of that filler arrives as a value whose own
     *  class the setter does not name: the setter is looked up along the value's class line, the declared type
     *  first, then each superclass, and the first match is called. */
    private static void roep(Object bean, String methode, Class<?> type, Object waarde) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Method m;
            try {
                m = bean.getClass().getMethod(methode, c);
            } catch (NoSuchMethodException e) {
                continue;
            }
            try {
                m.invoke(bean, waarde);
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("kon " + methode + "(" + c.getSimpleName() + ") niet aanroepen op "
                        + bean.getClass().getSimpleName(), e);
            }
        }
        throw new IllegalStateException("kon " + methode + "(" + type.getSimpleName() + ") niet aanroepen op "
                + bean.getClass().getSimpleName() + ": geen setter voor dat type of een supertype ervan");
    }
}
