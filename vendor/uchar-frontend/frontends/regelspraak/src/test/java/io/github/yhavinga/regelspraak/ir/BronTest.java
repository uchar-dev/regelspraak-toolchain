package io.github.yhavinga.regelspraak.ir;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The structured {@link Bron} on a resolved form carries file/line/column back to the source,
 * and a {@code // Bron:} statuutannotatie directly above a rule is retrievable from its
 * {@link ResolvedRegel}. Byte-identity of the v0 wire (the snippet) is proven elsewhere
 * (the emitter goldens); here we prove the ADDED coordinates and annotations.
 */
class BronTest {

    static final String SRC = """
        Objecttype de Som
            het bedrag Numeriek (geheel getal);
            de uitkomst Numeriek (geheel getal);
        // Bron: art. 3 Jutterswet
        Regel bereken uitkomst
            geldig altijd
                De uitkomst van een Som moet gesteld worden op het bedrag van een Som.
        """;

    private static int lineOf(String src, String needle) {
        int idx = src.indexOf(needle);
        assertTrue(idx >= 0, () -> "needle niet gevonden: " + needle);
        return 1 + (int) src.substring(0, idx).chars().filter(c -> c == '\n').count();
    }

    @Test void resolvedVormDraagtFileLineColumn() {
        RegelSpraakModel m = Resolver.resolve(SRC, "Jutterswet.rgs");
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
        assertEquals(1, m.resolvedRegels.size());

        ResolvedRegel rr = m.resolvedRegels.get(0);
        Bron bron = rr.doel().bron();
        assertNotNull(bron, "doel draagt geen Bron");
        assertEquals("Jutterswet.rgs", bron.file(), "de bestandsnaam moet doorgegeven zijn");
        assertEquals(lineOf(SRC, "De uitkomst van een Som moet"), bron.line(), "regelnummer van het doel");
        assertTrue(bron.column() >= 0, "kolom is 0-gebaseerd");
        assertTrue(bron.snippet().contains("uitkomst"), () -> "snippet: " + bron.snippet());
        // toString() levert de snippet (minimaliseert de diff op plekken die de bron als tekst gebruiken)
        assertEquals(bron.snippet(), bron.toString());
    }

    @Test void bronAnnotatieOpvraagbaarVanafResolvedRegel() {
        RegelSpraakModel m = Resolver.resolve(SRC, "Jutterswet.rgs");
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());

        ResolvedRegel rr = m.resolvedRegels.get(0);
        assertTrue(rr.bronAnnotaties().contains("art. 3 Jutterswet"),
                () -> "verwachtte de // Bron:-annotatie boven de regel, kreeg: " + rr.bronAnnotaties());
    }

    @Test void geenBronAnnotatieLevertLegeLijst() {
        // A rule with no // Bron: comment above it carries an empty (never null) annotatielijst.
        String src = """
            Objecttype de Som
                het bedrag Numeriek (geheel getal);
                de uitkomst Numeriek (geheel getal);
            Regel bereken uitkomst
                geldig altijd
                    De uitkomst van een Som moet gesteld worden op het bedrag van een Som.
            """;
        RegelSpraakModel m = Resolver.resolve(src, "Jutterswet.rgs");
        assertTrue(m.resolvedRegels.get(0).bronAnnotaties().isEmpty(), "geen annotatie -> lege lijst");
    }
}
