package io.github.yhavinga.regelspraak.transpiler.kern;

import io.github.yhavinga.regelspraak.ir.Conditie.Operator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The shared operator table (goal-M2 P2): symbol, §13.4.8 negation, operand-swap reversal. */
class OperatorenTest {

    @Test void symbool_dekt_alle_operatoren() {
        assertEquals("<",  Operatoren.symbool(Operator.KLEINER));
        assertEquals("<",  Operatoren.symbool(Operator.EERDER));
        assertEquals("<=", Operatoren.symbool(Operator.KLEINER_OF_GELIJK));
        assertEquals(">",  Operatoren.symbool(Operator.GROTER));
        assertEquals(">=", Operatoren.symbool(Operator.LATER_OF_GELIJK));
        assertEquals("==", Operatoren.symbool(Operator.GELIJK));
        assertEquals("!=", Operatoren.symbool(Operator.ONGELIJK));
    }

    @Test void flip_is_de_logische_negatie_en_een_involutie() {
        assertEquals(Operator.GROTER_OF_GELIJK, Operatoren.flip(Operator.KLEINER));   // complement moves the boundary
        assertEquals(Operator.ONGELIJK, Operatoren.flip(Operator.GELIJK));
        assertEquals(Operator.LATER_OF_GELIJK, Operatoren.flip(Operator.EERDER));
        for (Operator op : Operator.values())
            assertEquals(op, Operatoren.flip(Operatoren.flip(op)), "flip must be an involution: " + op);
    }

    @Test void omkeren_is_de_operand_spiegel_niet_de_negatie() {
        assertEquals(Operator.GROTER, Operatoren.omkeren(Operator.KLEINER));          // mirror keeps the boundary
        assertEquals(Operator.GELIJK, Operatoren.omkeren(Operator.GELIJK));
        assertEquals(Operator.ONGELIJK, Operatoren.omkeren(Operator.ONGELIJK));
        assertEquals(Operator.LATER, Operatoren.omkeren(Operator.EERDER));
        for (Operator op : Operator.values())
            assertEquals(op, Operatoren.omkeren(Operatoren.omkeren(op)), "omkeren must be an involution: " + op);
        // THE classic confusion, pinned: on KLEINER the two transformations differ exactly by the boundary
        assertNotEquals(Operatoren.flip(Operator.KLEINER), Operatoren.omkeren(Operator.KLEINER));
    }
}
