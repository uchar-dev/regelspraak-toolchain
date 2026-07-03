package io.github.yhavinga.regelspraak.schema;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Feittype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Rol;
import io.github.yhavinga.regelspraak.transpiler.kern.Namen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the fact-bean sources a resolved RegelSpraak model implies (the explicit fact-model contract) — the EXPLICIT form
 * of the contract the generated rules assume: one JavaBean per objecttype in {@code io.github.yhavinga.regelspraak.facts}
 * with a getter/setter per attribute ({@code BigDecimal}/{@code String}/{@code Boolean}/{@code LocalDate}/
 * {@code LocalDateTime}; a {@code Map<String,BigDecimal>} for a §3.6 gedimensioneerd attribute; a
 * {@code List<Segment>} for a §13.3.6 tijdsafhankelijk attribute), an {@code is<X>}/{@code set<X>} boolean
 * pair per kenmerk (a temporal kenmerk is a {@code List<Segment>} true-period timeline), and — per §13.3.9
 * feittype — the to-one REFERENCE field on the MANY side, named after the ONE side's role. {@code Segment}
 * and {@code Inconsistentie} are emitted alongside.
 *
 * <p>The MEMBER DERIVATION ({@link #leden}) is shared with {@link ContractValidator}, so what this
 * generator emits and what the validator demands can never drift apart.
 */
public final class FactModelGenerator {
    private FactModelGenerator() {}

    /** The package the generated rules import their fact classes from (both back-ends). */
    public static final String PAKKET = "io.github.yhavinga.regelspraak.facts";

    /** One expected bean member: the Java property, its type source-name, and whether the getter is the
     *  boolean {@code is<X>} form (a scalar kenmerk). */
    public record Lid(String eigenschap, String javaType, boolean isVorm) {}

    /** Every bean member the model implies for {@code ot} — the ONE derivation the generator renders and
     *  the {@link ContractValidator} checks. */
    public static List<Lid> leden(RegelSpraakModel model, Objecttype ot) {
        List<Lid> uit = new ArrayList<>();
        java.util.Set<String> gezien = new java.util.HashSet<>();   // ONE bean property per name: an attribute,
        // kenmerk and to-one role may legally share a name (TOKA's 'bereikbaar per trein' is both a kenmerk
        // and a Boolean attribute); the bean can carry only one field, and the runtime property resolution
        // reads whichever getter exists — so the FIRST derivation (attribute > kenmerk > referentie) wins.
        for (Map.Entry<String, Datatype> e : ot.attributen.entrySet()) {
            String naam = e.getKey();
            String type;
            if (!ot.dimensiesVan(naam).isEmpty()) type = "java.util.Map<String, java.math.BigDecimal>";   // §3.6
            else if (ot.attribuutGranulariteit.containsKey(naam)) type = "java.util.List<Segment>";       // §13.3.6
            else type = javaType(e.getValue());
            if (gezien.add(Namen.ident(naam, false)))
                uit.add(new Lid(Namen.ident(naam, false), type, false));
        }
        for (String k : ot.kenmerken) {
            boolean temporeel = ot.kenmerkGranulariteit.containsKey(k);                                    // §13.3.6
            if (gezien.add(Namen.ident(k, false)))
                uit.add(new Lid(Namen.ident(k, false), temporeel ? "java.util.List<Segment>" : "boolean", !temporeel));
        }
        // §13.3.9: the scalar to-one reference fields — derived from the SAME authority the transpilers
        // navigate by (verbindendeRol + naarVeelRol): for every other objecttype reachable through exactly
        // one feittype, the houder carries a field named after the doelwit's role ("Eén reis betreft
        // meerdere passagiers" -> Passagier.reis : Vlucht), UNLESS that navigation crosses a to-many role
        // (then the scalar reference lives on the other side). A self-referential or ambiguous pair derives
        // nothing: the transpilers navigate those only via the WRITTEN role name, which a static bean
        // derivation cannot know — under-implied, never over-implied.
        for (Objecttype doelwit : model.objecttypen.values()) {
            if (doelwit == ot) continue;
            String rol = model.verbindendeRol(ot, doelwit);
            if (rol == null || model.naarVeelRol(ot, doelwit, rol)) continue;
            if (gezien.add(Namen.ident(rol, false)))
                uit.add(new Lid(Namen.ident(rol, false), Namen.ident(doelwit.naam, true), false));
        }
        return uit;
    }

    /** The scalar Java type of a datatype — the same mapping the DRL back-end assumes of the beans. */
    public static String javaType(Datatype t) {
        if (t.isPercentage() || t.isNumeriek()) return "java.math.BigDecimal";
        if (t.isTekst() || t instanceof Datatype.Enumeratie) return "String";   // an enumeratiewaarde is its String label
        if (t instanceof Datatype.Booleaan) return "Boolean";                   // nullable: a leegwaarde is a real state
        if (t instanceof Datatype.DatumInDagen) return "java.time.LocalDate";
        if (t instanceof Datatype.DatumTijd) return "java.time.LocalDateTime";
        return "Object";
    }

    /** All bean sources the model implies: simple class name → Java source (incl. Segment + Inconsistentie). */
    public static Map<String, String> genereer(RegelSpraakModel model) {
        Map<String, String> uit = new LinkedHashMap<>();
        for (Objecttype ot : model.objecttypen.values()) {
            String klasse = Namen.ident(ot.naam, true);
            StringBuilder b = new StringBuilder();
            b.append("package ").append(PAKKET).append(";\n\n")
             .append("/** Generated fact bean for objecttype '").append(ot.naam).append("' (the explicit fact-model contract). */\n")
             .append("public class ").append(klasse).append(" {\n");
            List<Lid> leden = leden(model, ot);
            for (Lid l : leden)
                b.append("    private ").append(l.javaType()).append(' ').append(l.eigenschap()).append(";\n");
            b.append('\n');
            for (Lid l : leden) {
                String p = l.eigenschap(), P = Character.toUpperCase(p.charAt(0)) + p.substring(1);
                b.append("    public ").append(l.javaType()).append(' ').append(l.isVorm() ? "is" : "get").append(P)
                 .append("() { return ").append(p).append("; }\n")
                 .append("    public void set").append(P).append('(').append(l.javaType()).append(" v) { this.")
                 .append(p).append(" = v; }\n");
            }
            b.append("}\n");
            uit.put(klasse, b.toString());
        }
        uit.put("Segment", SEGMENT_BRON);
        uit.put("Inconsistentie", INCONSISTENTIE_BRON);
        return uit;
    }

    private static final String SEGMENT_BRON = """
            package io.github.yhavinga.regelspraak.facts;

            import java.math.BigDecimal;
            import java.time.LocalDate;

            /** One tijdvak [van, tot) of a §13.3.6 temporal attribute's Tijdreeks (generated, the explicit fact-model contract). */
            public class Segment {
                private LocalDate van;
                private LocalDate tot;
                private BigDecimal waarde;

                public Segment() {}
                public Segment(LocalDate van, LocalDate tot, BigDecimal waarde) {
                    this.van = van; this.tot = tot; this.waarde = waarde;
                }

                public LocalDate getVan() { return van; }
                public void setVan(LocalDate v) { this.van = v; }
                public LocalDate getTot() { return tot; }
                public void setTot(LocalDate v) { this.tot = v; }
                public BigDecimal getWaarde() { return waarde; }
                public void setWaarde(BigDecimal v) { this.waarde = v; }
            }
            """;

    private static final String INCONSISTENTIE_BRON = """
            package io.github.yhavinga.regelspraak.facts;

            /** The observable §13.4.8/§9.5 "inconsistent" result: the violated rule + subject (generated, the explicit fact-model contract). */
            public class Inconsistentie {
                private final String regel;
                private final Object subject;
                public Inconsistentie(String regel, Object subject) { this.regel = regel; this.subject = subject; }
                public String getRegel() { return regel; }
                public Object getSubject() { return subject; }
                @Override public String toString() { return "Inconsistentie[" + regel + " @ " + subject + "]"; }
            }
            """;
}
