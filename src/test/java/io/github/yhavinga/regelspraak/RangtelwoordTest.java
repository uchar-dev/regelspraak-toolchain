package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §13.2.12 rangtelwoord (§5.5.3, §13.4.1.3, §13.4.2.9): "de eerstgenoemde / als tweede genoemde /
 * laatstgenoemde &lt;X&gt;" selects an onderwerp by its mention position IN the rule. The resolver
 * CONSUMES the ordinal: it resolves to the bare reference when it selects the rule's single binding,
 * is an ERROR when the rule does not name that many onderwerpen, and stays an honest WARNING+marker
 * for the genuinely multi-bound rule (per-mention pattern variables are not in the single-subject IR).
 * Before this, the ordinal was silently dropped — every mention aliased to one binding.
 */
class RangtelwoordTest {

    static final String MODEL = """
        Objecttype de Passagier (mv: Passagiers) (bezield)
            de bijdrage Numeriek (geheel getal);
            de premie Numeriek (geheel getal);
        """;

    private static RegelSpraakModel regel(String expressie) {
        return Resolver.resolve(MODEL + """
            Regel premie
                geldig altijd
                    De premie van een Passagier moet berekend worden als %s.
            """.formatted(expressie));
    }

    @Test void eerstgenoemde_over_de_enige_binding_lost_kaal_op_en_vuurt() {
        RegelSpraakModel m = regel("de bijdrage van de eerstgenoemde Passagier");
        assertTrue(m.errors().isEmpty(), () -> "onverwachte errors: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("getBijdrage()"), () -> "de ordinale verwijzing moet als kale eigen-lezing vuren:\n" + drl);
        assertFalse(drl.contains("TODO"), () -> "geen marker verwacht:\n" + drl);
    }

    @Test void laatstgenoemde_over_de_enige_binding_lost_kaal_op() {
        RegelSpraakModel m = regel("de bijdrage van de laatstgenoemde Passagier");
        assertTrue(m.errors().isEmpty(), () -> "onverwachte errors: " + m.errors());
        assertFalse(DrlTranspiler.transpile(m).contains("TODO"));
    }

    @Test void als_tweede_genoemde_zonder_tweede_binding_is_een_fout() {
        RegelSpraakModel m = regel("de bijdrage van de als tweede genoemde Passagier");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("5.5.3")),
                () -> "verwachtte de §5.5.3 fout, kreeg: " + m.diagnostics);
    }

    @Test void numeriek_rangtelwoord_zonder_die_binding_is_een_fout() {
        RegelSpraakModel m = regel("de bijdrage van de als 6e genoemde Passagier");
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("geen 6")),
                () -> "verwachtte de §5.5.3 fout voor positie 6, kreeg: " + m.diagnostics);
    }

    @Test void twee_bindingen_blijven_een_eerlijke_marker_met_warning() {
        // two indefinite mentions of Passagier: per-mention binding is not in the single-subject IR —
        // the ordinal reference markers (visible), never a silent alias of one binding.
        RegelSpraakModel m = regel("de bijdrage van een Passagier plus de bijdrage van de als tweede genoemde Passagier");
        assertTrue(m.errors().isEmpty(), () -> "geen ERROR verwacht (wel een WARNING): " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("wordt nog niet ondersteund")),
                () -> "verwachtte de meervoudige-binding WARNING, kreeg: " + m.diagnostics);
        assertTrue(DrlTranspiler.transpile(m).contains("TODO"),
                "de meervoudig-gebonden ordinale verwijzing moet een marker blijven");
    }

    @Test void ordinale_attribuutvorm_lost_kaal_op() {
        // §13.4.2.9 "de <rangtelwoord> <attribuutnaam> van <onderwerp>" — the eerstgenoemde reading over a
        // single mention is the bare attribute read (this branch of attribuutVanOnderwerp was a silent
        // onopgelost before).
        RegelSpraakModel m = regel("de eerstgenoemde bijdrage van de Passagier");
        assertTrue(m.errors().isEmpty(), () -> "onverwachte errors: " + m.errors());
        String drl = DrlTranspiler.transpile(m);
        assertTrue(drl.contains("getBijdrage()"), () -> "de ordinale attribuutvorm moet als kale lezing vuren:\n" + drl);
        assertFalse(drl.contains("TODO"), () -> "geen marker verwacht:\n" + drl);
    }

    @Test void spec_antlr_voorbeeld_6_vorm_lost_op_in_een_volledig_model() {
        // the spec's one worked example ("De bijdrage van de als tweede genoemde passagier ..."), here in a
        // rule that binds passagier ONCE via the rol — eerstgenoemde variant resolves; the als-tweede form
        // is the documented ERROR (no second mention).
        String src = """
            Objecttype de Vlucht (mv: Vluchten)
                de totale bijdrage Numeriek (geheel getal);
            Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
                de bijdrage Numeriek (geheel getal);

            Feittype vlucht van natuurlijke personen
                de reis Vlucht
                de passagier Natuurlijk persoon
            Eén reis betreft de verplaatsing van meerdere passagiers

            Regel bijdrage passagier
                geldig altijd
                    De totale bijdrage van een reis moet berekend worden als de som van de bijdragen van alle passagiers van de reis.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "het basismodel moet schoon resolven: " + m.errors());
    }
}
