package io.github.yhavinga.regelspraak;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WF-113 read off an emitted document, the way a consumer reads it: (a) every diagnostic is a WARNING, and (b)
 * every {@code Onopgelost} node is accounted for — its {@code bron} is neither null nor blank, and some WARNING's
 * {@code message} contains it verbatim. The check reads the JSON TEXT and not the model: the sweep that raises the WARNINGs walks the model, so
 * a check over the model would share its blind spots, and a marker the emitter writes from a place the sweep does
 * not visit would pass unseen. The frontend links no JSON library (jar-only: ANTLR and JUnit), hence the small
 * reader below; it reads the JSON the emitter writes and fails on anything else.
 */
final class Verslag {

    private Verslag() {}

    /** Fails on the first diagnostic that is no WARNING and on the first marker no WARNING accounts for. */
    static void verantwoord(String json) {
        Map<?, ?> document = (Map<?, ?>) new Lezer(json).document();
        List<String> meldingen = new ArrayList<>();
        for (Object o : (List<?>) document.get("diagnostics")) {
            Map<?, ?> d = (Map<?, ?>) o;
            assertEquals("WARNING", d.get("severity"), () -> "WF-113 (a): de wire draagt alleen WARNING: " + d);
            meldingen.add((String) d.get("message"));
        }
        for (String bron : markerBronnen(document)) {
            // blank counts as absent: an empty bron is contained in every message, and a null one in none
            assertTrue(bron != null && !bron.isBlank(),
                    () -> "WF-113 (b): een marker zonder bron kan door geen WARNING verantwoord worden: " + bron);
            assertTrue(meldingen.stream().anyMatch(m -> m.contains(bron)),
                    () -> "WF-113 (b): geen WARNING citeert de bron van de marker letterlijk: '" + bron
                            + "'; de meldingen: " + meldingen);
        }
    }

    /** The {@code bron} of every Onopgelost node in the document, in document order. */
    static List<String> markerBronnen(String json) {
        return markerBronnen(new Lezer(json).document());
    }

    private static List<String> markerBronnen(Object document) {
        List<String> uit = new ArrayList<>();
        verzamel(document, uit);
        return uit;
    }

    private static void verzamel(Object x, List<String> uit) {
        if (x instanceof Map<?, ?> m) {
            if ("Onopgelost".equals(m.get("node"))) uit.add((String) m.get("bron"));
            for (Object v : m.values()) verzamel(v, uit);
        } else if (x instanceof List<?> l) {
            for (Object v : l) verzamel(v, uit);
        }
    }

    /** Objects as ordered maps, arrays as lists, strings unescaped, numbers as their lexeme. */
    private static final class Lezer {
        private final String s;
        private int i;

        Lezer(String s) { this.s = s; }

        Object document() {
            Object d = waarde();
            spaties();
            if (i != s.length()) throw fout("tekst na het document");
            return d;
        }

        private Object waarde() {
            spaties();
            if (i >= s.length()) throw fout("einde van de tekst");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return lijst();
            if (c == '"') return tekst();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int begin = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (begin == i) throw fout("onverwacht teken '" + c + "'");
            return s.substring(begin, i);
        }

        private Map<String, Object> object() {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            spaties();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                spaties();
                String k = tekst();
                spaties();
                if (s.charAt(i++) != ':') throw fout("':' verwacht");
                m.put(k, waarde());
                spaties();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw fout("',' of '}' verwacht");
            }
        }

        private List<Object> lijst() {
            i++;
            List<Object> l = new ArrayList<>();
            spaties();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(waarde());
                spaties();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw fout("',' of ']' verwacht");
            }
        }

        private String tekst() {
            if (s.charAt(i++) != '"') throw fout("'\"' verwacht");
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw fout("onbekende escape '\\" + e + "'");
                }
            }
        }

        private void spaties() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private AssertionError fout(String wat) {
            return new AssertionError("geen JSON zoals de emitter hem schrijft, op positie " + i + ": " + wat);
        }
    }
}
