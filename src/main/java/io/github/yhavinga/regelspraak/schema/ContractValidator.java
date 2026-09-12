package io.github.yhavinga.regelspraak.schema;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator.Lid;
import io.github.yhavinga.regelspraak.transpiler.kern.Namen;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the fact beans on a classpath honour the contract a resolved model implies:
 * per objecttype a class in {@code io.github.yhavinga.regelspraak.facts}, per implied member (the SAME derivation
 * {@link FactModelGenerator#leden} the generator renders) a getter and setter of the right shape, plus the
 * {@code Segment}/{@code Inconsistentie} carriers when the model needs them. Returns ALL violations — the
 * engine fails fast with the complete list, so a consumer fixes the bean contract in one round trip instead
 * of chasing one reflective fire-time abort per run.
 */
public final class ContractValidator {
    private ContractValidator() {}

    /** Every bean↔model violation, as human-readable messages; empty when the contract holds. */
    public static List<String> valideer(RegelSpraakModel model, ClassLoader cl) {
        List<String> uit = new ArrayList<>();
        for (Objecttype ot : model.objecttypen.values()) {
            String klasse = FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true);
            Class<?> c;
            try { c = Class.forName(klasse, false, cl); }
            catch (ClassNotFoundException e) {
                uit.add("objecttype '" + ot.naam + "': feitklasse " + klasse + " ontbreekt op het classpath");
                continue;
            }
            for (Lid l : FactModelGenerator.leden(model, ot)) {
                String p = l.eigenschap(), P = Character.toUpperCase(p.charAt(0)) + p.substring(1);
                Method getter = vind(c, (l.isVorm() ? "is" : "get") + P, 0);
                // a scalar kenmerk may also be exposed as get<X>() (mvel resolves the property either way)
                if (getter == null && l.isVorm()) getter = vind(c, "get" + P, 0);
                if (getter == null)
                    uit.add(ot.naam + "." + p + ": getter " + (l.isVorm() ? "is" : "get") + P + "() ontbreekt op " + klasse);
                else if (!typeOk(l.javaType(), getter.getReturnType()))
                    uit.add(ot.naam + "." + p + ": getter " + getter.getName() + "() geeft " + getter.getReturnType().getName()
                            + ", het model vraagt " + l.javaType());
                Method setter = vind(c, "set" + P, 1);
                if (setter == null)
                    uit.add(ot.naam + "." + p + ": setter set" + P + "(" + l.javaType() + ") ontbreekt op " + klasse);
                else if (!typeOk(l.javaType(), setter.getParameterTypes()[0]))
                    uit.add(ot.naam + "." + p + ": setter set" + P + " neemt " + setter.getParameterTypes()[0].getName()
                            + ", het model vraagt " + l.javaType());
            }
        }
        if (!model.objecttypen.isEmpty()) {
            // the carriers: Segment iff any temporal member exists, Inconsistentie iff any §13.4.8/§6.5/§6.6 fout
            // can be raised (consistenties, uniciteiten, or any division/wortel — checked coarsely: consistenties
            // and uniciteiten only; the fout companions of a division/wortel insert one too, but requiring the
            // class only when statically certain keeps the validator permissive for scalar-only models).
            // The OWN maps, deliberately: this is a disjunction over EVERY objecttype in the model, so a
            // declaring ancestor is visited on its own turn and chain-closing the read would only make
            // each inherited timeline count once per descendant — the same answer, later.
            boolean temporeel = model.objecttypen.values().stream().anyMatch(ot ->
                    !ot.attribuutGranulariteit.isEmpty() || !ot.kenmerkGranulariteit.isEmpty());
            if (temporeel) eisSegment(cl, uit);
            if (!model.consistenties.isEmpty() || !model.uniciteiten.isEmpty() || !model.uniciteitUnies.isEmpty())
                eisInconsistentie(cl, uit);
        }
        return uit;
    }

    private static void eisSegment(ClassLoader cl, List<String> uit) {
        try {
            Class<?> seg = Class.forName(FactModelGenerator.PAKKET + ".Segment", false, cl);
            seg.getConstructor(java.time.LocalDate.class, java.time.LocalDate.class, java.math.BigDecimal.class);
            for (String m : new String[]{"getVan", "getTot", "getWaarde"})
                if (vind(seg, m, 0) == null) uit.add("Segment." + m + "() ontbreekt (temporeel model)");
        } catch (ClassNotFoundException e) {
            uit.add("temporeel model zonder " + FactModelGenerator.PAKKET + ".Segment op het classpath");
        } catch (NoSuchMethodException e) {
            uit.add("Segment(LocalDate, LocalDate, BigDecimal) constructor ontbreekt (temporeel model)");
        }
    }

    private static void eisInconsistentie(ClassLoader cl, List<String> uit) {
        try {
            Class<?> inc = Class.forName(FactModelGenerator.PAKKET + ".Inconsistentie", false, cl);
            inc.getConstructor(String.class, Object.class);
        } catch (ClassNotFoundException e) {
            uit.add("consistentie-/uniciteitsregels zonder " + FactModelGenerator.PAKKET + ".Inconsistentie op het classpath");
        } catch (NoSuchMethodException e) {
            uit.add("Inconsistentie(String, Object) constructor ontbreekt (consistentie-/uniciteitsregels)");
        }
    }

    private static Method vind(Class<?> c, String naam, int arity) {
        for (Method m : c.getMethods())
            if (m.getName().equals(naam) && m.getParameterCount() == arity) return m;
        return null;
    }

    /** Does the actual class satisfy the expected SOURCE type name? Erasure-level: a generic
     *  {@code Map<String,BigDecimal>}/{@code List<Segment>} checks its raw type; {@code boolean} admits both
     *  the primitive and the wrapper; a reference field checks the simple fact-class name. */
    private static boolean typeOk(String verwacht, Class<?> echt) {
        if (verwacht.startsWith("java.util.Map")) return java.util.Map.class.isAssignableFrom(echt);
        if (verwacht.startsWith("java.util.List")) return java.util.List.class.isAssignableFrom(echt);
        return switch (verwacht) {
            // a Booleaan ATTRIBUTE is three-valued (waar/onwaar/LEEG): the bean member must be the nullable
            // wrapper — a primitive boolean silently coerces leeg, exactly the drift this validator exists
            // to catch. A scalar KENMERK ("boolean") is genuinely two-valued, so either form serves it.
            case "Boolean" -> echt == Boolean.class;
            case "boolean" -> echt == boolean.class || echt == Boolean.class;
            case "String" -> echt == String.class;
            case "Object" -> true;   // an Onbekend datatype claims nothing
            default -> echt.getName().equals(verwacht)                       // fully-qualified scalar
                    || echt.getSimpleName().equals(verwacht);                // a fact-class reference by simple name
        };
    }
}
