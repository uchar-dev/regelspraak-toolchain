package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Drools back-end walks the resolved IR into DRL: a target object-type pattern
 * for the "when", a modify-block setter for the "then", parameters as globals, and
 * an explicit marker wherever the IR was left unresolved.
 */
class DrlTest {

    static final String SRC = """
        Parameter de grens : Numeriek (geheel getal)
        Objecttype de Reis
            de prijs Numeriek (geheel getal);
            de toeslag Numeriek (geheel getal);
        Regel verhoog prijs
            geldig altijd
                De prijs van een Reis moet gesteld worden op de toeslag van een Reis plus de grens
                indien de prijs van een Reis kleiner is dan de grens.
        """;

    @Test void rule_skeleton_en_globals() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        assertTrue(drl.contains("package io.github.yhavinga.regelspraak.generated;"), drl);
        assertTrue(drl.contains("global java.math.BigDecimal grens;"), drl);
        assertTrue(drl.contains("rule \"verhoog prijs\""), drl);
        assertTrue(drl.contains("$subject : Reis("), () -> "subject pattern ontbreekt:\n" + drl);
    }

    @Test void conditie_wordt_when_constraint() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        // "de prijs ... kleiner is dan de grens" -> prijs < grens
        assertTrue(drl.contains("prijs < grens"), () -> "constraint ontbreekt:\n" + drl);
    }

    @Test void toekenning_wordt_modify_setter() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(SRC));
        // "de prijs ... := de toeslag plus de grens" -> modify { setPrijs( ($subject.getToeslag() + grens) ) }
        assertTrue(drl.contains("modify($subject)"), () -> "modify-blok ontbreekt:\n" + drl);
        assertTrue(drl.contains("setPrijs("), () -> "setter ontbreekt:\n" + drl);
        assertTrue(drl.contains("$subject.getToeslag() + grens"), () -> "waarde-expressie ontbreekt:\n" + drl);
    }

    @Test void onopgeloste_constructie_wordt_gemarkeerd_niet_verzonnen() {
        // An aggregation is not yet transpilable (needs accumulate); it must surface as
        // an explicit marker, never as silently-wrong DRL.
        String src = """
            Objecttype de Reis
                de totaal Numeriek (geheel getal);
                de prijs Numeriek (geheel getal);
            Regel tel
                geldig altijd
                    De totaal van een Reis moet berekend worden als de som van de prijs van een Reis.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("/* TODO aggregatie SOM"), () -> "aggregatie niet gemarkeerd:\n" + drl);
    }

    @Test void namen_worden_geldige_java_identifiers() {
        String src = """
            Objecttype de Natuurlijk persoon
                de te betalen belasting Numeriek (geheel getal);
            Regel r
                geldig altijd
                    De te betalen belasting van een Natuurlijk persoon moet gesteld worden op 0.
            """;
        String drl = DrlTranspiler.transpile(Resolver.resolve(src));
        assertTrue(drl.contains("$subject : NatuurlijkPersoon("), () -> "klassenaam:\n" + drl);
        assertTrue(drl.contains("setTeBetalenBelasting("), () -> "setternaam:\n" + drl);
    }
}
