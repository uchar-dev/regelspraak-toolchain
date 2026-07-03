package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import io.github.yhavinga.regelspraak.transpiler.DrlTranspiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §9.9/§13.4.12 startpuntbepaling + §6.8 typering: the anchor of a §3.8 "tijdlijn met variabel
 * startpunt" lifts into the IR as a synthetic derived attribute "startpunt &lt;tijdlijn&gt;"
 * (Datum in dagen) written by an ordinary ResolvedRegel, and a temporal doel riding that tijdlijn
 * buckets at the per-object anchor instead of the calendar.
 */
class StartpuntTest {

    static final String MODEL = """
        Tijdlijn de contractperiode heeft tijdvakken van een maand met variabel startpunt;

        Objecttype de Passagier (mv: Passagiers) (bezield)
            de geboortedatum Datum in dagen;
            het recht op belastingvermindering kenmerk (bezittelijk) voor elke maand;
            het aantal dagen recht op belastingvermindering Numeriek (geheel getal) met eenheid dg voor elke contractperiode;
        """;

    static final String REGELS = """
        Regel Startpunt contractperiode
            geldig altijd
                Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum.

        Regel Aantal dagen recht op belastingvermindering
            geldig altijd
                Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
        """;

    @Test void startpuntbepaling_lift_naar_ir() {
        RegelSpraakModel m = Resolver.resolve(MODEL + REGELS);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte errors: " + m.errors());
        // the anchor member registers once, as a Datum-in-dagen attribute of the subject
        assertEquals(1, m.resolvedStartpunten.size());
        var sp = m.resolvedStartpunten.get(0);
        assertEquals("contractperiode", sp.tijdlijn());
        assertEquals("Passagier", sp.objecttype());
        assertEquals("startpunt contractperiode", sp.lid());
        RegelSpraakModel.Objecttype ot = m.objecttypen.get("Passagier");
        assertInstanceOf(Datatype.DatumInDagen.class, ot.attributen.get("startpunt contractperiode"));
        // the WRITE is an ordinary ResolvedRegel (voorwaarde/geldigheid/freshness ride the standard machinery)
        assertTrue(m.resolvedRegels.stream().anyMatch(r -> r.naam().equals("Startpunt contractperiode")));
        // the named tijdlijn the temporal attribute rides is recorded, and resolves to the anchor
        assertEquals("contractperiode", ot.attribuutTijdlijnNaam.get("aantal dagen recht op belastingvermindering"));
        assertEquals("startpunt contractperiode", m.startpuntAnkerLid(ot, "aantal dagen recht op belastingvermindering"));
        // the old drop-WARNING is gone
        assertTrue(m.diagnostics.stream().noneMatch(d -> d.message().contains("'startpuntbepaling' wordt nog niet")),
                () -> "startpuntbepaling had gelift moeten zijn: " + m.diagnostics);
    }

    @Test void paragraaf_6_8_niet_datum_anker_is_een_fout() {
        String src = MODEL + """
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door het aantal dagen recht op belastingvermindering van de Passagier.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("6.8")),
                () -> "verwachtte een §6.8 datum-in-dagen-fout, kreeg: " + m.diagnostics);
    }

    @Test void paragraaf_6_8_datum_en_tijd_anker_is_een_fout() {
        String src = """
            Tijdlijn de contractperiode heeft tijdvakken van een maand met variabel startpunt;
            Objecttype de Passagier (mv: Passagiers) (bezield)
                het boekingsmoment Datum en tijd in millisecondes;
            Regel Startpunt contractperiode
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn boekingsmoment.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("6.8")),
                () -> "§6.8: het anker moet datum IN DAGEN zijn, niet Datum-tijd; kreeg: " + m.diagnostics);
    }

    @Test void onbekende_tijdlijn_is_een_fout() {
        String src = """
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de geboortedatum Datum in dagen;
            Regel Startpunt spookperiode
                geldig altijd
                    Het startpunt van de spookperiode voor een Passagier wordt bepaald door zijn geboortedatum.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().stream().anyMatch(d -> d.message().contains("niet gedeclareerd")),
                () -> "verwachtte een onbekende-tijdlijn-fout, kreeg: " + m.diagnostics);
    }

    @Test void startpunt_voor_vast_startpunt_tijdlijn_waarschuwt_maar_lift() {
        // §9.9 defines the startpuntbepaling for a tijdlijn MET variabel startpunt; the spec's own ANTLR-doc
        // example pairs one with a fixed timeline, so this stays a WARNING + lift, not an ERROR.
        String src = """
            Tijdlijn de boekingsperiode heeft tijdvakken van een maand startend op dag 1 van de maand
            Objecttype de Passagier (mv: Passagiers) (bezield)
                de geboortedatum Datum in dagen;
            Regel Startpunt boekingsperiode
                geldig altijd
                    Het startpunt van de boekingsperiode voor een Passagier wordt bepaald door zijn geboortedatum.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "geen error verwacht: " + m.errors());
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.message().contains("geen variabel startpunt")),
                () -> "verwachtte de vast-startpunt-WARNING, kreeg: " + m.diagnostics);
        assertEquals(1, m.resolvedStartpunten.size());
    }

    @Test void twee_startpunten_onder_voorwaarden_delen_een_anker() {
        // §9.9: "verschillende startpunten voor hetzelfde objecttype onder verschillende voorwaarden"
        String src = MODEL + """
            Regel Startpunt vroeg
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum
                    indien zijn geboortedatum later is dan 01-01-2000.

            Regel Startpunt laat
                geldig altijd
                    Het startpunt van de contractperiode voor een Passagier wordt bepaald door zijn geboortedatum plus 1 maand
                    indien zijn geboortedatum eerder of gelijk is aan 01-01-2000.
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte errors: " + m.errors());
        assertEquals(1, m.resolvedStartpunten.size(), "één anker-lid per (objecttype, tijdlijn)");
        assertEquals(2, m.resolvedRegels.stream().filter(r -> r.naam().startsWith("Startpunt")).count(),
                "elke voorwaarde-variant is een eigen schrijver van dat lid");
    }

    @Test void drl_ankert_de_buckets_en_bewaakt_leeg_anker() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(MODEL + REGELS));
        // the write rule: idempotent scalar write of the anchor, fresh on its own read
        assertTrue(drl.contains("rule \"Startpunt contractperiode\""), drl);
        // the anchored bucketing: rsAantalDagenInVanaf over the subject's startpunt member, watched for freshness
        assertTrue(drl.contains("rsAantalDagenInVanaf("), () -> "anchored bucketing ontbreekt:\n" + drl);
        assertTrue(drl.contains("$subject.getStartpuntContractperiode()"), () -> "anker-read ontbreekt:\n" + drl);
        assertTrue(drl.contains("@watch( startpuntContractperiode )"), () -> "anker-@watch ontbreekt:\n" + drl);
        // the anchored write is idempotent (per-segment equality, never List reference equality)
        assertTrue(drl.contains("rsTijdreeksGelijk("), () -> "idempotente tijdreeks-guard ontbreekt:\n" + drl);
        // §6.8: the leeg-anchor fout companion
        assertTrue(drl.contains("rule \"Startpunt contractperiode (leeg startpunt)\""), () -> "§6.8 companion ontbreekt:\n" + drl);
        assertTrue(drl.contains("import function io.github.yhavinga.regelspraak.runtime.RegelSpraakRuntime.rsAantalDagenInVanaf;"), drl);
    }

    @Test void variabel_startpunt_zonder_startpuntbepaling_markert() {
        String drl = DrlTranspiler.transpile(Resolver.resolve(MODEL + """
            Regel Aantal dagen recht op belastingvermindering
                geldig altijd
                    Het aantal dagen recht op belastingvermindering van een passagier moet gesteld worden op het aantal dagen in de maand dat hij een recht op belastingvermindering heeft.
            """));
        assertTrue(drl.contains("variabel startpunt zonder startpuntbepaling"),
                () -> "een ongeankerde variabele tijdlijn moet markeren, nooit stil kalender-bucketen:\n" + drl);
        assertFalse(drl.contains("rsAantalDagenIn("), () -> "geen kalender-bucketing op een variabele tijdlijn:\n" + drl);
    }
}
