package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.schema.ContractValidator;
import io.github.yhavinga.regelspraak.schema.FactModelGenerator;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import io.github.yhavinga.regelspraak.transpiler.kern.Namen;
import io.github.yhavinga.regelspraak.transpiler.model.ExecModelTranspiler;
import org.drools.modelcompiler.KieBaseBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Let-port's conformance harness: every uchar golden that carries a ch. 4.10 {@code Let} on the wire AND a
 * {@code bron.rgs} is re-parsed by the vendored frontend, fired through BOTH Drools back-ends, and checked against
 * the ABSOLUTE expectations pinned in that golden's own {@code case*.json} — the same values uchar's gate 5 (ref)
 * and gate 6 (clingo) verify.
 *
 * <p><b>Why this exists.</b> {@code scripts/uchar-conformance.sh} proves the frontend re-EMITS each golden's IR
 * byte-identically; it says nothing about what the back-ends then COMPUTE. Since the Let-port the toolchain
 * executes the variabelendeel with uchar's call-by-need semantics (RUNTIME-SEMANTICS ch. 10.6) instead of the v0
 * inlining, so the two toolchains agree on a class where they used to differ: a READ binder whose value is leeg
 * (v0 coalesces, v1 raises the leeg-barrier — {@code CONSTRUCTS.md} row 24). {@code jutterswet-vondstrecht} is
 * that divergence's corpus witness and is replayed here as an absolute.
 *
 * <p>Driven by {@code -Duchar.dir=<checkout>} (or the {@code UCHAR_DIR} environment variable), mirroring the
 * {@code make uchar-conformance} contract. Without it the test ABORTS (a visible JUnit skip) rather than passing
 * vacuously; with it, an empty golden set is a FAILURE — a green subset is not green.
 */
class UcharLetReplayTest {

    @Test void let_dragende_uchar_goldens_replayen_met_de_uchar_pins() throws Exception {
        Path uchar = ucharDir();
        Path goldens = uchar.resolve("corpus/golden");
        assertTrue(Files.isDirectory(goldens), () -> uchar + " lijkt niet op de uchar-checkout (geen corpus/golden)");

        List<Path> letGoldens = new ArrayList<>();
        try (Stream<Path> w = Files.walk(goldens)) {
            for (Path bron : w.filter(p -> p.getFileName().toString().equals("bron.rgs")).sorted().toList()) {
                Path dir = bron.getParent();
                Path ir = dir.resolve("ir.json");
                // The wire form is the authority on "carries a Let", not the source text: the frontend's own
                // lowering boundaries (a voorwaarde-only variable, a read inside an aggregation subtree) leave a
                // "Daarbij geldt:" substituted, and such a golden exercises nothing this port changed.
                if (Files.exists(ir) && Files.readString(ir).contains("\"Let\"")) letGoldens.add(dir);
            }
        }
        assertFalse(letGoldens.isEmpty(),
                () -> "geen enkele uchar-golden met bron.rgs draagt een Let onder " + goldens
                        + " — een lege verzameling is een failure, nooit stil groen");

        int cases = 0, verwachtingen = 0;
        StringBuilder verslag = new StringBuilder();
        for (Path dir : letGoldens) {
            String naam = goldens.relativize(dir).toString();
            RegelSpraakModel m = Resolver.resolve(Files.readString(dir.resolve("bron.rgs")));
            assertTrue(m.errors().isEmpty(), () -> naam + ": resolver-fout " + m.errors());

            ClassLoader cl = FactModelGeneratorTest.compileer(FactModelGenerator.genereer(m));
            List<String> schendingen = ContractValidator.valideer(m, cl);
            assertTrue(schendingen.isEmpty(), () -> naam + ": bean-contract " + schendingen);

            // Neither back-end may quietly decline a golden: a refusal here would make the replay pass on an
            // empty rule set. The exec side reports it as `overgeslagen`, the DRL side as a marker in `dekking`.
            ExecModelTranspiler em = ExecModelTranspiler.build(m, cl);
            assertTrue(em.overgeslagen().isEmpty(), () -> naam + ": exec-model sloeg over " + em.overgeslagen());
            DrlTranspiler dt = DrlTranspiler.build(m);
            List<String> markers = dt.dekking().stream().filter(d -> !d.lowered()).map(Object::toString).toList();
            assertTrue(markers.isEmpty(), () -> naam + ": DRL-tekst markert " + markers);

            KieBase drlBase = drlBase(dt, cl);
            KieBase execBase = KieBaseBuilder.createKieBaseFromModel(em.model());

            for (Path caseFile : caseBestanden(dir)) {
                Object json = Json.lees(Files.readString(caseFile));
                String label = naam + "/" + caseFile.getFileName();
                for (String route : new String[]{"DRL", "EXEC"})
                    verwachtingen += replay(label + " [" + route + "]", m, cl,
                            "DRL".equals(route) ? drlBase : execBase, json);
                cases++;
                verslag.append("\n  OK  ").append(label);
            }
        }
        assertTrue(cases > 0, "geen enkele Let-dragende golden had een case-bestand");
        System.out.println("[uchar-let-replay] " + letGoldens.size() + " Let-dragende goldens, " + cases
                + " cases x 2 back-ends, " + verwachtingen + " verwachtingen gecontroleerd" + verslag);
    }

    /** Build one world from a case's {@code input}, fire it, and check every {@code expected} entry. Returns the
     *  number of expectations checked, so the caller can prove the replay actually asserted something. */
    private int replay(String label, RegelSpraakModel m, ClassLoader cl, KieBase base, Object json) throws Exception {
        Map<?, ?> root = Json.object(json, label);
        Map<?, ?> input = Json.object(root.get("input"), label + ".input");

        // ---- instances: one bean per label, attributes set, then the relations wired ----
        Map<String, Object> beans = new LinkedHashMap<>();
        Map<String, Objecttype> typen = new LinkedHashMap<>();
        List<?> instances = Json.array(input.get("instances"), label + ".input.instances");
        for (Object o : instances) {
            Map<?, ?> inst = Json.object(o, label + " instance");
            String naam = Json.tekst(inst.get("label"), label + " instance.label");
            String otNaam = Json.tekst(inst.get("objectType"), label + " instance.objectType");
            Objecttype ot = objecttype(m, otNaam, label);
            Object bean = Class.forName(FactModelGenerator.PAKKET + "." + Namen.ident(ot.naam, true), true, cl)
                    .getConstructor().newInstance();
            for (Map.Entry<?, ?> a : Json.object(inst.get("attributes"), label + " attributes").entrySet()) {
                String attr = String.valueOf(a.getKey());
                Datatype dt = ot.attribuut(attr);
                assertNotNull(dt, () -> label + ": objecttype " + ot.naam + " kent geen attribuut '" + attr + "'");
                assertTrue(zet(bean, attr, waarde(a.getValue(), dt, label + " " + attr)),
                        () -> label + ": geen setter voor attribuut '" + attr + "' op " + bean.getClass());
            }
            for (Object k : Json.array(inst.get("characteristics"), label + " characteristics"))
                assertTrue(zet(bean, String.valueOf(k), Boolean.TRUE),
                        () -> label + ": geen setter voor kenmerk '" + k + "'");
            beans.put(naam, bean);
            typen.put(naam, ot);
        }
        for (Object o : instances) {
            Map<?, ?> inst = Json.object(o, label + " instance");
            String naam = Json.tekst(inst.get("label"), label + " instance.label");
            for (Map.Entry<?, ?> r : Json.object(inst.get("roles"), label + " roles").entrySet())
                wireRol(m, beans, typen, naam, String.valueOf(r.getKey()), r.getValue(), label);
        }

        // ---- fire ----
        KieSession s = base.newKieSession();
        for (Map.Entry<?, ?> p : Json.object(input.get("parameters"), label + ".input.parameters").entrySet()) {
            RegelSpraakModel.Parameter par = m.parameters.get(String.valueOf(p.getKey()));
            assertNotNull(par, () -> label + ": onbekende parameter '" + p.getKey() + "'");
            s.setGlobal(Namen.ident(par.naam(), false), waarde(p.getValue(), par.datatype(), label + " parameter"));
        }
        for (Object b : beans.values()) s.insert(b);
        int gevuurd = s.fireAllRules(10_000);
        assertTrue(gevuurd < 10_000, () -> label + ": vuur-plafond geraakt (geen fixpunt)");
        List<Object> feiten = new ArrayList<>(s.getObjects());
        s.dispose();

        // ---- expectations: EXACTLY the values uchar pins for ref and clingo ----
        int n = 0;
        for (Object o : Json.array(root.get("expected"), label + ".expected")) {
            Map<?, ?> exp = Json.object(o, label + " expected");
            String kind = Json.tekst(exp.get("kind"), label + " expected.kind");
            switch (kind) {
                case "attributeValue", "attributeEmpty" -> {
                    String bl = Json.tekst(exp.get("label"), label + " expected.label");
                    String attr = Json.tekst(exp.get("attribute"), label + " expected.attribute");
                    Object bean = beans.get(bl);
                    assertNotNull(bean, () -> label + ": onbekend label '" + bl + "'");
                    Object werkelijk = lees(bean, attr);
                    if ("attributeEmpty".equals(kind)) {
                        assertEquals(null, werkelijk,
                                () -> label + ": " + attr + " van " + bl + " moet LEEG zijn (uchar-pin), kreeg " + werkelijk);
                    } else {
                        Datatype dt = typen.get(bl).attribuut(attr);
                        Object verwacht = waarde(exp.get("value"), dt, label + " expected.value");
                        assertEquals(canon(verwacht), canon(werkelijk),
                                () -> label + ": " + attr + " van " + bl + " wijkt af van de uchar-pin");
                    }
                    n++;
                }
                case "consistent", "inconsistent" -> {
                    long fouten = feiten.stream()
                            .filter(f -> f.getClass().getSimpleName().equals("Inconsistentie")).count();
                    if ("consistent".equals(kind))
                        assertEquals(0L, fouten, () -> label + ": uchar pint consistent, kreeg " + fouten + " Inconsistentie(s)");
                    else
                        assertTrue(fouten > 0, () -> label + ": uchar pint inconsistent, kreeg er geen");
                    n++;
                }
                // Fail fast on a kind this harness does not implement: a silently ignored expectation would make
                // the replay report a green it never checked.
                default -> fail(label + ": onbekende verwachtingssoort '" + kind + "' — implementeer haar of "
                        + "sluit de golden expliciet uit; stil overslaan mag niet");
            }
        }
        return n;
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static Path ucharDir() {
        String p = System.getProperty("uchar.dir", System.getenv("UCHAR_DIR"));
        Assumptions.assumeTrue(p != null && !p.isBlank(),
                "zet -Duchar.dir=<uchar-checkout> (of UCHAR_DIR) om de Let-goldens te replayen");
        return Path.of(p);
    }

    private static List<Path> caseBestanden(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String f = p.getFileName().toString();
                return f.startsWith("case") && f.endsWith(".json");
            }).sorted().toList();
        }
    }

    private static Objecttype objecttype(RegelSpraakModel m, String naam, String label) {
        for (Objecttype ot : m.objecttypen.values())
            if (ot.naam.equalsIgnoreCase(naam) || Namen.ident(ot.naam, true).equals(Namen.ident(naam, true)))
                return ot;
        return fail(label + ": onbekend objecttype '" + naam + "'");
    }

    /** Wire one case role. A to-ONE sets the owner's own reference; a to-MANY has no setter on the owner (the
     *  generated bean carries the reverse scalar reference — {@code FactModelGenerator}'s authority), so each
     *  member's back-reference to the owner is set instead. Neither direction wiring is a hard failure. */
    private void wireRol(RegelSpraakModel m, Map<String, Object> beans, Map<String, Objecttype> typen,
                         String eigenaar, String rol, Object waarde, String label) throws Exception {
        Object eb = beans.get(eigenaar);
        for (Object lidO : Json.array(waarde, label + " roles." + rol)) {
            String lid = String.valueOf(lidO);
            Object lb = beans.get(lid);
            assertNotNull(lb, () -> label + ": rol '" + rol + "' verwijst naar onbekend label '" + lid + "'");
            if (zet(eb, rol, lb)) continue;                                   // to-one on the owner
            String terug = m.verbindendeRol(typen.get(lid), typen.get(eigenaar));
            assertTrue(terug != null && zet(lb, terug, eb),
                    () -> label + ": rol '" + rol + "' van " + eigenaar + " naar " + lid + " is niet te bedraden");
        }
    }

    private static boolean zet(Object bean, String logischeNaam, Object waarde) throws Exception {
        String setter = Namen.setter(logischeNaam);
        for (Method mm : bean.getClass().getMethods())
            if (mm.getName().equals(setter) && mm.getParameterCount() == 1
                    && mm.getParameterTypes()[0].isInstance(waarde)) { mm.invoke(bean, waarde); return true; }
        return false;
    }

    private static Object lees(Object bean, String attr) throws Exception {
        String g = Namen.getter(attr);
        for (String naam : new String[]{g, "is" + g.substring(3)})
            for (Method mm : bean.getClass().getMethods())
                if (mm.getName().equals(naam) && mm.getParameterCount() == 0) return mm.invoke(bean);
        return fail("geen getter " + g + " op " + bean.getClass().getSimpleName());
    }

    /** One uchar case value ({@code {"number": "30"}} and friends) as the bean's Java value. Fails fast on a
     *  lexeme this harness does not carry, so a golden that grows one cannot silently read as leeg. */
    private static Object waarde(Object v, Datatype dt, String waar) {
        Map<?, ?> o = Json.object(v, waar);
        if (o.containsKey("number")) return new BigDecimal(String.valueOf(o.get("number")));
        if (o.containsKey("text")) return String.valueOf(o.get("text"));
        if (o.containsKey("boolean")) return Boolean.valueOf(String.valueOf(o.get("boolean")));
        if (o.containsKey("date")) return LocalDate.parse(String.valueOf(o.get("date")));
        if (o.containsKey("dateTime")) return LocalDateTime.parse(String.valueOf(o.get("dateTime")));
        if (o.containsKey("enumeration")) return String.valueOf(o.get("enumeration"));
        return fail(waar + ": onbekende waardevorm " + o.keySet() + " (datatype " + dt + ")");
    }

    /** Scale-insensitive for BigDecimal (30 == 30.00), identity otherwise — mirrors the differential's eq(). */
    private static Object canon(Object o) {
        return o instanceof BigDecimal b ? b.stripTrailingZeros() : o;
    }

    private KieBase drlBase(DrlTranspiler t, ClassLoader cl) {
        KieHelper h = new KieHelper();
        h.setClassLoader(cl);
        h.addContent(t.drl(), ResourceType.DRL);
        assertFalse(h.verify().hasMessages(org.kie.api.builder.Message.Level.ERROR),
                () -> "DRL compileert niet:\n" + h.verify().getMessages() + "\n---\n" + t.drl());
        return h.build();
    }

    // ---- a minimal, strict JSON reader ---------------------------------------------------------------------
    // The regelspraak-parser module deliberately carries NO JSON dependency (its own IR emitter writes JSON by
    // hand), and this harness reads a small, machine-generated schema. So: a strict recursive-descent reader that
    // THROWS on anything it does not understand, rather than a new third-party dependency on the test classpath.

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) { this.s = s; }

        static Object lees(String tekst) {
            Json p = new Json(tekst);
            p.ws();
            Object v = p.waarde();
            p.ws();
            if (p.i != p.s.length()) throw new IllegalArgumentException("JSON: rommel na positie " + p.i);
            return v;
        }

        static Map<?, ?> object(Object o, String waar) {
            if (o instanceof Map<?, ?> m) return m;
            if (o == null) return Map.of();   // an absent optional section reads as empty, never as a crash
            throw new IllegalArgumentException(waar + ": object verwacht, kreeg " + o);
        }

        static List<?> array(Object o, String waar) {
            if (o instanceof List<?> l) return l;
            if (o == null) return List.of();
            throw new IllegalArgumentException(waar + ": array verwacht, kreeg " + o);
        }

        static String tekst(Object o, String waar) {
            if (o instanceof String t) return t;
            throw new IllegalArgumentException(waar + ": tekst verwacht, kreeg " + o);
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object waarde() {
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> lit("true", Boolean.TRUE);
                case 'f' -> lit("false", Boolean.FALSE);
                case 'n' -> lit("null", null);
                default -> getal();
            };
        }

        private Object lit(String woord, Object v) {
            if (!s.startsWith(woord, i)) throw new IllegalArgumentException("JSON: verwachtte " + woord + " op " + i);
            i += woord.length();
            return v;
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                if (s.charAt(i) != ':') throw new IllegalArgumentException("JSON: ':' verwacht op " + i);
                i++; ws();
                m.put(k, waarde());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("JSON: ',' of '}' verwacht op " + (i - 1));
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(waarde());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("JSON: ',' of ']' verwacht op " + (i - 1));
            }
        }

        private String str() {
            if (s.charAt(i) != '"') throw new IllegalArgumentException("JSON: string verwacht op " + i);
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw new IllegalArgumentException("JSON: onbekende escape \\" + e);
                }
            }
        }

        private BigDecimal getal() {
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("JSON: waarde verwacht op " + start);
            return new BigDecimal(s.substring(start, i));
        }
    }
}
