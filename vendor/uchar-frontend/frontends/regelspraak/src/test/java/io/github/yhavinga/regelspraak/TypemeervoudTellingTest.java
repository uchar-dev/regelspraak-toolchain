package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.ir.Expr;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Diagnostic;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Severity;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "het aantal Vondsten" — a count whose operand names an object type and nothing it is counted from. The
 * card frontend-typemeervoud-telling (2026-09-23) answered the norm question NO: RegelSpraak v2.3.0 has no
 * count over all instances of a type. The count takes a plural subject expression (§5.8.1, typeringen
 * §4.11); §5.8 names the plural ones — a concatenation, a selection whose object or role is plural, a
 * subselection — each reached from the rule's subject; an object type stands in an onderwerpketen only in
 * the singular, as the universal subject bound per instance (§5.5.3, §13.4.1). The one document-wide range,
 * "van alle &lt;objecttype&gt;", belongs to the uniqueness check (§8.1.6) and is no aggregation.
 *
 * <p>The refusal itself predates the card: since the source-less count arm of WF-037 the sentence has
 * been an ERROR, with the generic sentence that an operand "names no instances to count" — untrue for an
 * author who named a population. This suite pins the sentence that states the norm instead, for every
 * spelling that reaches it, and pins that nothing else is re-read: a role count stays clean, and an
 * operand that names no type keeps the generic sentence.
 */
class TypemeervoudTellingTest {

    static final String MODEL = """
        Objecttype de Jutter (mv: Jutters) (bezield)
            het aantal gewogen posten Numeriek (niet-negatief geheel getal);
            de gewogen vangst Numeriek (getal met 2 decimalen);

        Objecttype de Vondst (mv: Vondsten)
            het gewicht Numeriek (getal met 2 decimalen);

        Feittype vondstaanmelding
            de aanmelder Jutter
            de vondst (mv: vondsten) Vondst
        Eén aanmelder heeft meerdere vondsten
        """;

    private static String regel(String expressie) {
        return """

            Regel aantal gewogen posten
                geldig altijd
                    Het aantal gewogen posten van een Jutter moet berekend worden als %s.
            """.formatted(expressie);
    }

    private static List<Diagnostic> overType(RegelSpraakModel m) {
        return m.diagnostics.stream().filter(d -> d.message().startsWith("telling zonder bron, over een objecttype")).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "het aantal Vondsten",          // the type's declared plural, bare
            "het aantal alle Vondsten",     // with the quantifier the uniqueness check writes
            "het aantal de Vondsten",       // with an article
            "het aantal Vondst",            // the type's own name
            "het aantal vondsten",          // the plural in lower case — the role word, with no anchor
            "(het aantal Vondsten)"
    })
    void een_telling_over_een_objecttype_weigert_met_de_norm(String expressie) {
        RegelSpraakModel m = Resolver.resolve(MODEL + regel(expressie));
        List<Diagnostic> ds = overType(m);
        assertEquals(1, ds.size(), () -> "verwacht één typetellingsmelding voor '" + expressie + "', kreeg: " + m.diagnostics);
        Diagnostic d = ds.get(0);
        assertEquals(Severity.ERROR, d.severity(), "ERROR: irModus schrijft het document dan niet");
        String t = d.message();
        assertTrue(t.contains("noemt het objecttype Vondst zonder onderwerp waarvandaan het geteld wordt"), t);
        assertTrue(t.contains("die telling kent de brontaal niet"), t);
        assertTrue(t.contains("§5.8)") && t.contains("§5.5.3") && t.contains("§13.4.1") && t.contains("§8.1.6"), t);
        assertTrue(t.contains("WF-037"), t);
        // the way out, named from the rule's subject: the one role that reaches Vondst from Jutter
        assertTrue(t.contains("vanaf Jutter is dat de rol 'vondsten' (feittype vondstaanmelding)"), t);
        assertEquals(1, m.errors().size(), () -> "precies één fout: " + m.errors());
    }

    @Test void zonder_rol_vanaf_het_onderwerp_blijft_de_uitweg_algemeen() {
        // Jutter counting Jutters: no feittype reaches Jutter from Jutter, so the sentence names no role.
        RegelSpraakModel m = Resolver.resolve(MODEL + regel("het aantal Jutters"));
        List<Diagnostic> ds = overType(m);
        assertEquals(1, ds.size(), () -> "kreeg: " + m.diagnostics);
        assertTrue(ds.get(0).message().contains("noemt het objecttype Jutter"), ds.get(0).message());
        assertFalse(ds.get(0).message().contains("vanaf Jutter is dat de rol"), ds.get(0).message());
    }

    @Test void een_enkelvoudige_rol_wordt_niet_als_uitweg_genoemd() {
        // From Vondst the one role reaching Jutter is 'aanmelder', and a Vondst has ONE aanmelder: counting it
        // is no count over a plural role, which is what the sentence itself says a count needs.
        String src = MODEL + """

            Regel gewicht
                geldig altijd
                    Het gewicht van een Vondst moet berekend worden als het aantal Jutters.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        List<Diagnostic> ds = overType(m);
        assertEquals(1, ds.size(), () -> "kreeg: " + m.diagnostics);
        assertTrue(ds.get(0).message().contains("noemt het objecttype Jutter"), ds.get(0).message());
        assertFalse(ds.get(0).message().contains("is dat de rol"), ds.get(0).message());
    }

    @Test void de_rolteling_blijft_schoon() {
        RegelSpraakModel m = Resolver.resolve(MODEL + regel("het aantal vondsten van de Jutter"));
        assertTrue(m.diagnostics.isEmpty(), () -> "onverwachte diagnostiek: " + m.diagnostics);
        Expr.Functie f = (Expr.Functie) m.resolvedRegels.get(0).waarde();
        assertEquals(Expr.FunctieKind.AANTAL, f.kind());
        assertEquals("Vondst", f.elementObjecttype().naam);
    }

    @Test void een_parameter_met_de_naam_van_een_type_houdt_de_algemene_zin() {
        // Only an operand that resolved to NOTHING declared is read as a type. Here "vondsten" is a declared
        // parameter, so the generic sentence — a parameter is no source — is the right one.
        String src = "Parameter de vondsten : Numeriek (niet-negatief geheel getal)\n\n" + MODEL + regel("het aantal vondsten");
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(overType(m).isEmpty(), () -> "de typezin hoort hier niet: " + m.diagnostics);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().startsWith("telling zonder bron: ")),
                () -> "de algemene bronloze-tellingzin ontbreekt: " + m.diagnostics);
    }

    @Test void een_operand_die_geen_type_noemt_houdt_de_algemene_zin() {
        RegelSpraakModel m = Resolver.resolve(MODEL + regel("het aantal gewogen vangst van de Jutter"));
        assertTrue(overType(m).isEmpty(), () -> "de typezin hoort hier niet: " + m.diagnostics);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().startsWith("telling zonder bron: ")),
                () -> "de algemene bronloze-tellingzin ontbreekt: " + m.diagnostics);
    }
}
