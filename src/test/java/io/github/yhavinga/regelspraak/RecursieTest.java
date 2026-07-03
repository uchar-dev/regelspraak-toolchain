package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §9.10 recursie — the DECIDED posture (see docs/grammar-divergences.md): a read-write cycle
 * between berekeningen is a WARNING (never the "in principe afgekeurd" hard ERROR — the spec
 * itself says cyclic-vs-recursive is not reliably automatable, and v2.3.0 gives no regelgroep
 * syntax to legitimize recursion with), and a same-instance self-derivation (the begrenzing/
 * clamp idiom) is tolerated diagnostics-free as a documented divergence.
 */
class RecursieTest {

    static final String MODEL = """
        Objecttype de Ding
            de x Numeriek (geheel getal);
            de y Numeriek (geheel getal);
        """;

    @Test void kringverwijzing_is_een_warning_geen_error() {
        RegelSpraakModel m = Resolver.resolve(MODEL + """
            Regel a
                geldig altijd
                    De x van een Ding moet berekend worden als de y van de Ding plus 1.
            Regel b
                geldig altijd
                    De y van een Ding moet berekend worden als de x van de Ding plus 1.
            """);
        assertTrue(m.errors().isEmpty(), () -> "§9.10 posture: cyclisch = WARNING, nooit ERROR: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("kringverwijzing")),
                () -> "de kringverwijzing-WARNING ontbreekt: " + m.diagnostics);
    }

    @Test void zelfde_instantie_zelfafleiding_clamp_idioom_resolvet_zonder_diagnose() {
        RegelSpraakModel m = Resolver.resolve(MODEL + """
            Regel begrens x
                geldig altijd
                    De x van een Ding moet gesteld worden op de x van de Ding, met een maximum van de y van de Ding.
            """);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte errors: " + m.errors());
        assertTrue(m.diagnostics.stream().noneMatch(d -> d.message().contains("kringverwijzing")),
                () -> "een zelf-lezing is geen kring (no-loop-terminerend one-shot): " + m.diagnostics);
    }
}
