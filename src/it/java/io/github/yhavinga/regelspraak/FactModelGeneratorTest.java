package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;
import org.kie.api.runtime.KieSession;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the fact-model contract, the full loop: {@link FactModelGenerator} emits the bean sources a resolved model implies;
 * they compile in-memory (javax.tools), pass the {@link ContractValidator} (generator ⊨ validator by
 * construction — the shared {@code leden} derivation), and a generated rule FIRES against instances of the
 * generated beans. The objecttype names are deliberately absent from the hand-written facts pool, so the
 * generated classes are the only implementation the rule can possibly run against.
 */
class FactModelGeneratorTest {

    private static final String SRC = """
        Objecttype het Proefgeval (mv: proefgevallen)
            de invoer Numeriek (geheel getal);
            de uitkomst Numeriek (geheel getal);
            is verwerkt kenmerk (bijvoeglijk);
        Objecttype de Proefbundel
            het totaal Numeriek (geheel getal);
        Feittype Proefbundel met deelnemers
            de bundel Proefbundel
            de deelnemer Proefgeval
        Eén bundel betreft meerdere deelnemers
        Regel verwerk
            geldig altijd
                De uitkomst van een Proefgeval moet berekend worden als de invoer van het Proefgeval plus 2.
        Regel totaal
            geldig altijd
                Het totaal van een bundel moet berekend worden als de som van de uitkomsten van alle deelnemers van de bundel.
        """;

    @Test void genereer_compileer_valideer_en_vuur() throws Exception {
        RegelSpraakModel m = Resolver.resolve(SRC);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());

        Map<String, String> bronnen = FactModelGenerator.genereer(m);
        assertTrue(bronnen.containsKey("Proefgeval") && bronnen.containsKey("Proefbundel")
                && bronnen.containsKey("Segment") && bronnen.containsKey("Inconsistentie"),
                () -> "verwachtte alle klassen, kreeg " + bronnen.keySet());
        // the shared derivation: attribute getters, the isX kenmerk pair, the many-side reference field
        String proefgeval = bronnen.get("Proefgeval");
        assertTrue(proefgeval.contains("public java.math.BigDecimal getInvoer()"), proefgeval);
        assertTrue(proefgeval.contains("public boolean isVerwerkt()"), proefgeval);
        assertTrue(proefgeval.contains("public Proefbundel getBundel()"), proefgeval);

        ClassLoader cl = compileer(bronnen);
        List<String> schendingen = ContractValidator.valideer(m, cl);
        assertTrue(schendingen.isEmpty(), () -> "generator moet zijn eigen validator halen:\n  " + String.join("\n  ", schendingen));

        // fire the generated rules against instances of the GENERATED beans (DRL path: the oracle back-end,
        // compiled against the in-memory classloader).
        KieHelper h = new KieHelper();
        h.setClassLoader(cl);
        h.addContent(DrlTranspiler.transpile(m), ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "DRL compileert niet tegen de gegenereerde beans: " + h.verify().getMessages());
        KieBase kb = h.build();

        Class<?> geval = Class.forName(FactModelGenerator.PAKKET + ".Proefgeval", true, cl);
        Class<?> bundel = Class.forName(FactModelGenerator.PAKKET + ".Proefbundel", true, cl);
        Object b = bundel.getConstructor().newInstance();
        Object g1 = geval.getConstructor().newInstance();
        Object g2 = geval.getConstructor().newInstance();
        geval.getMethod("setInvoer", BigDecimal.class).invoke(g1, new BigDecimal(10));
        geval.getMethod("setInvoer", BigDecimal.class).invoke(g2, new BigDecimal(30));
        geval.getMethod("setBundel", bundel).invoke(g1, b);
        geval.getMethod("setBundel", bundel).invoke(g2, b);

        KieSession s = kb.newKieSession();
        s.insert(b); s.insert(g1); s.insert(g2);
        s.fireAllRules();
        s.dispose();

        assertEquals(0, new BigDecimal(12).compareTo((BigDecimal) geval.getMethod("getUitkomst").invoke(g1)),
                "10 + 2 = 12 op de gegenereerde bean");
        assertEquals(0, new BigDecimal(44).compareTo((BigDecimal) bundel.getMethod("getTotaal").invoke(b)),
                "som van de uitkomsten (12 + 32) = 44 — het meervoud bindt op de gegenereerde accumulate");
    }

    @Test void validator_meldt_alle_schendingen_in_een_keer() {
        // fail FAST but COMPLETE — a bean pool that lacks a class AND mistypes a member lists them all.
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype het Spookding
                de waarde Numeriek (geheel getal);
            Objecttype de Vlucht
                de totaalprijs Tekst;
            """);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        List<String> schendingen = ContractValidator.valideer(m, getClass().getClassLoader());
        assertTrue(schendingen.stream().anyMatch(v -> v.contains("Spookding") && v.contains("ontbreekt")),
                () -> "de ontbrekende klasse moet gemeld worden: " + schendingen);
        assertTrue(schendingen.stream().anyMatch(v -> v.contains("totaalprijs") && v.contains("String")),
                () -> "het mis-getypeerde lid moet OOK gemeld worden (alle schendingen in een keer): " + schendingen);
    }

    /** Compile the generated sources in memory; the returned loader serves them and delegates the rest.
     *  Package-visible: the TOKA end-to-end firing test ({@link TokaVuurTest}) rides the same harness. */
    static ClassLoader compileer(Map<String, String> bronnen) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> klassen = new HashMap<>();
        List<JavaFileObject> eenheden = new ArrayList<>();
        for (Map.Entry<String, String> e : bronnen.entrySet())
            eenheden.add(new SimpleJavaFileObject(
                    URI.create("string:///" + FactModelGenerator.PAKKET.replace('.', '/') + "/" + e.getKey() + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return e.getValue(); }
            });
        StandardJavaFileManager std = javac.getStandardFileManager(null, null, null);
        JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
            @Override public JavaFileObject getJavaFileForOutput(Location loc, String naam, JavaFileObject.Kind kind, FileObject bron) {
                ByteArrayOutputStream uit = new ByteArrayOutputStream();
                klassen.put(naam, uit);
                return new SimpleJavaFileObject(URI.create("mem:///" + naam.replace('.', '/') + ".class"), kind) {
                    @Override public OutputStream openOutputStream() { return uit; }
                };
            }
        };
        assertTrue(javac.getTask(null, fm, null, null, null, eenheden).call(), "gegenereerde bronnen moeten compileren");
        // CHILD-FIRST for the classes this compile produced: the generated beans share the hand-written
        // facts PAKKET, so a parent-first loader would let a same-named static test bean (Vlucht,
        // NatuurlijkPersoon, …) shadow the generated one — the TOKA end-to-end test must run against
        // the GENERATED model, reconciled members included.
        return new ClassLoader(FactModelGeneratorTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String naam, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(naam)) {
                    Class<?> c = findLoadedClass(naam);
                    if (c == null && klassen.containsKey(naam)) c = findClass(naam);
                    if (c == null) return super.loadClass(naam, resolve);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            @Override protected Class<?> findClass(String naam) throws ClassNotFoundException {
                ByteArrayOutputStream b = klassen.get(naam);
                if (b == null) throw new ClassNotFoundException(naam);
                byte[] bytes = b.toByteArray();
                return defineClass(naam, bytes, 0, bytes.length);
            }
        };
    }

    @Test void kenmerk_en_attribuut_met_dezelfde_naam_leveren_een_property() throws Exception {
        // TOKA's Vlucht declares 'bereikbaar per trein' BOTH as a kenmerk and as a Boolean attribute —
        // legal per the resolver; the bean carries ONE property (the attribute derivation wins), so the
        // generated source compiles and the validator demands a single consistent shape.
        RegelSpraakModel m = Resolver.resolve("""
            Objecttype de Proefvlucht
                is bereikbaar per trein kenmerk (bijvoeglijk);
                de bereikbaar per trein Boolean;
            """);
        assertTrue(m.errors().isEmpty(), () -> "" + m.errors());
        Map<String, String> bronnen = FactModelGenerator.genereer(m);
        String klasse = bronnen.get("Proefvlucht");
        assertEquals(1, klasse.split("private ", -1).length - 1,
                () -> "een gedeelde naam levert precies EEN veld:\n" + klasse);
        ClassLoader cl = compileer(bronnen);
        assertTrue(ContractValidator.valideer(m, cl).isEmpty(),
                () -> "de gegenereerde bean moet zijn eigen contract halen:\n" + ContractValidator.valideer(m, cl));
    }
}
