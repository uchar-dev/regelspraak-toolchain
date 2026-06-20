package io.github.yhavinga.regelspraak;

import io.github.yhavinga.regelspraak.model.Datatype;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel;
import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import io.github.yhavinga.regelspraak.resolver.Resolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Phase A: the GegevensSpraak definitions resolve to a faithful typed model (§13.3). */
class ModelTest {

    // A representative slice of the TOKA casus (verbatim shapes from §2.1-2.5).
    static final String SRC = """
        Eenheidsysteem Tijd
            de maand           mnd
            het jaar           jr       =    12 mnd
        Domein Bedrag is van het type Numeriek (getal met 2 decimalen)
        Domein Luchthavens is van het type Enumeratie
            'Amsterdam Schiphol'
            'Groningen Eelde'
            'Parijs Charles de Gaulle'
            'Londen Heathrow'
        Parameter de volwassenleeftijd : Numeriek (niet-negatief geheel getal) met eenheid jr
        Objecttype de Natuurlijk persoon (mv: Natuurlijke personen) (bezield)
            is minderjarig kenmerk (bijvoeglijk);
            de leeftijd Numeriek (niet-negatief geheel getal) met eenheid jr;
            de geboortedatum Datum in dagen;
            het burgerservicenummer Tekst;
            de belasting op basis van afstand Bedrag;
        Feittype Vlucht van natuurlijke personen
            de reis Vlucht
            de passagier Natuurlijk persoon
        Eén reis betreft de verplaatsing van meerdere passagiers
        """;

    @Test void parse_zonder_fouten() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());
    }

    @Test void objecttype_attributen_zijn_getypeerd() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        Objecttype np = m.objecttypen.get("Natuurlijk persoon");
        assertNotNull(np, "Natuurlijk persoon ontbreekt");
        assertTrue(np.bezield, "(bezield) niet herkend");

        Datatype leeftijd = np.attributen.get("leeftijd");
        assertInstanceOf(Datatype.Numeriek.class, leeftijd);
        assertEquals("jr", ((Datatype.Numeriek) leeftijd).eenheid().afkorting());
        assertEquals("Tijd", ((Datatype.Numeriek) leeftijd).eenheid().stelsel());

        assertInstanceOf(Datatype.DatumInDagen.class, np.attributen.get("geboortedatum"));
        assertInstanceOf(Datatype.Tekst.class, np.attributen.get("burgerservicenummer"));
        assertTrue(np.kenmerken.contains("minderjarig"));
    }

    @Test void domein_typed_attribuut_lost_op() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        // "belasting op basis van afstand" is typed by domain Bedrag = Numeriek(2 decimalen)
        Datatype d = m.objecttypen.get("Natuurlijk persoon").attributen.get("belasting op basis van afstand");
        assertInstanceOf(Datatype.Numeriek.class, d);
        assertEquals(2, ((Datatype.Numeriek) d).decimalen());
    }

    @Test void domeinen_zijn_getypeerd() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        assertInstanceOf(Datatype.Numeriek.class, m.domeinen.get("Bedrag"));
        Datatype lh = m.domeinen.get("Luchthavens");
        assertInstanceOf(Datatype.Enumeratie.class, lh);
        assertEquals(4, ((Datatype.Enumeratie) lh).waarden().size());
        assertTrue(((Datatype.Enumeratie) lh).waarden().contains("Amsterdam Schiphol"));
    }

    @Test void parameter_is_getypeerd() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        var p = m.parameters.get("volwassenleeftijd");
        assertNotNull(p);
        assertInstanceOf(Datatype.Numeriek.class, p.datatype());
        assertEquals("jr", ((Datatype.Numeriek) p.datatype()).eenheid().afkorting());
    }

    @Test void parameter_numeriek_zonder_getalspecificatie_lost_op_zonder_te_crashen() {
        // §4.4's worked example writes "Numeriek met eenheid jaren" — omitting the getalspecificatie
        // that §13.3.3 makes mandatory. The grammar tolerates this; the resolver must degrade to the
        // least-restrictive numeriek reading (== a bare "getal": unbounded decimals, no sign) rather
        // than NPE on the absent specification (the spec-main-43 crash, p31).
        String src = "Parameter de volwassenleeftijd : Numeriek met eenheid jaren";
        RegelSpraakModel m = Resolver.resolve(src);                 // must not throw
        assertTrue(m.errors().isEmpty(), () -> "onverwachte fouten: " + m.errors());

        var p = m.parameters.get("volwassenleeftijd");
        assertNotNull(p, "parameter ontbreekt");
        Datatype.Numeriek dt = assertInstanceOf(Datatype.Numeriek.class, p.datatype());
        assertEquals("jaar", dt.eenheid().afkorting(), "eenheid blijft behouden");
        assertFalse(dt.geheel(), "geen 'geheel getal' geschreven → niet geheel");
        assertEquals(0, dt.decimalen(), "geen decimalenspecificatie → onbeperkt (sentinel 0)");
        assertEquals(Datatype.Teken.ONBEPAALD, dt.teken(), "geen teken geschreven → onbepaald");

        assertTrue(m.diagnostics.stream().anyMatch(d ->
                        d.severity() == RegelSpraakModel.Severity.WARNING
                                && d.message().contains("getalspecificatie")),
                () -> "verwachtte een WARNING over de ontbrekende getalspecificatie; kreeg " + m.diagnostics);
    }

    @Test void dimensie_wordt_gemodelleerd() {
        String src = """
            Dimensie de jaardimensie, bestaande uit de jaardimensies (na het attribuut met voorzetsel van):
                1. vorig jaar
                2. huidig jaar
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "fouten: " + m.errors());
        var d = m.dimensies.get("jaardimensie");
        assertNotNull(d);
        assertEquals("jaardimensies", d.naamMeervoud());
        assertEquals(java.util.List.of("vorig jaar", "huidig jaar"), d.waarden());
    }

    @Test void feittype_rollen_opgelost() {
        RegelSpraakModel m = Resolver.resolve(SRC);
        var ft = m.feittypen.get("Vlucht van natuurlijke personen");
        assertNotNull(ft);
        assertEquals(2, ft.rollen.size());
        assertEquals("reis", ft.rollen.get(0).naam());
        assertEquals("Vlucht", ft.rollen.get(0).objecttypeNaam());
        assertEquals("Natuurlijk persoon", ft.rollen.get(1).objecttypeNaam());
    }

    @Test void feittype_cardinaliteit_markeert_de_meervoudige_rol() {
        // §13.3.9.1: "Eén reis betreft de verplaatsing van meerdere passagiers" makes the
        // passagier role the to-many side and the reis role single.
        RegelSpraakModel m = Resolver.resolve(SRC);
        var ft = m.feittypen.get("Vlucht van natuurlijke personen");
        assertFalse(ft.rollen.get(0).meervoudig(), "reis is enkelvoudig (Eén reis ...)");
        assertTrue(ft.rollen.get(1).meervoudig(), "passagier is meervoudig (... meerdere passagiers)");
    }

    @Test void many_to_many_cardinaliteit_met_kleine_letter_opener_markeert_beide_rollen() {
        // §13.3.9.1/.2: the cardinaliteit terminal is the lowercase "meerdere" (syntaxdiagrammen), and a
        // many-to-many line opens with it. It MUST be lowercase: a sentence-initial capital "Meerdere" is
        // irreducibly ambiguous with an objecttypenaam — the preceding role's greedy objectTypeNaam would
        // absorb it (or, made a keyword, "Meerdere" is stolen from every name). See docs/grammar-divergences.md.
        String src = """
            Objecttype de Student
                de naam Tekst;
            Objecttype de Docent
                de naam Tekst;
            Feittype begeleiding
                de student Student
                de docent Docent
            meerdere studenten begeleiden meerdere docenten
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "de kleine-letter many-to-many opener moet parseren: " + m.errors());
        var ft = m.feittypen.get("begeleiding");
        assertNotNull(ft, () -> "feittype niet opgelost: " + m.feittypen.keySet());
        assertTrue(ft.rollen.get(0).meervoudig(), "student is meervoudig (meerdere studenten ...)");
        assertTrue(ft.rollen.get(1).meervoudig(), "docent is meervoudig (... meerdere docenten)");
    }

    @Test void feittype_naam_met_lidwoord_waarschuwt_voor_de_aangetaste_rol() {
        // §13.3.9.3: feitTypeNaam excludes de/het, so a feittype name containing one ("bonus van de vlucht")
        // truncates there and the leftover "de vlucht" is absorbed into the first role's greedy rolNaam,
        // corrupting it to "vlucht de bonusvlucht" with NO error node. Flag the tell-tale internal article.
        String src = """
            Objecttype de Vlucht
                de naam Tekst;
            Objecttype de Passagier
                de naam Tekst;
            Feittype bonus van de vlucht
                de bonusvlucht Vlucht
                de bonuspassagier Passagier
            Eén bonusvlucht kent meerdere bonuspassagiers
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.diagnostics.stream().anyMatch(d -> d.severity() == RegelSpraakModel.Severity.WARNING
                        && d.message().contains("lidwoord") && d.message().contains("rolnaam")),
                () -> "een feittypenaam met een lidwoord moet de aangetaste rol melden: " + m.diagnostics);
    }

    @Test void feittype_naam_zonder_lidwoord_waarschuwt_niet() {
        // The TOKA-style multi-word role names ("te verdelen contingent treinmiles", "passagier met recht
        // op treinmiles") carry no internal article, so they must not trip the heuristic.
        String src = """
            Objecttype de Contingent
            Objecttype de Natuurlijk persoon
            Feittype verdeling contingent treinmiles over passagiers
                de te verdelen contingent treinmiles Contingent
                de passagier met recht op treinmiles Natuurlijk persoon
            Eén te verdelen contingent treinmiles betreft meerdere passagiers met recht op treinmiles
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("lidwoord")),
                () -> "een schone feittypenaam mag geen lidwoord-waarschuwing geven: " + m.diagnostics);
    }

    @Test void rolnaam_met_lidwoord_na_voorzetsel_waarschuwt_niet() {
        // §13.3.9.4: a rolnaam may legitimately contain an article AFTER a preposition ("eigenaar van de
        // woning", "behandelaar van het dossier"). Only an article after a BARE noun signals the corruption,
        // so these spec-legal names must NOT warn — the discriminator the heuristic refinement keys on.
        String src = """
            Objecttype de Persoon
                de naam Tekst;
            Objecttype de Woning
                de naam Tekst;
            Feittype eigendom
                de eigenaar van de woning Persoon
                de woning Woning
            Eén eigenaar van de woning bezit meerdere woningen
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertEquals("eigenaar van de woning", m.feittypen.get("eigendom").rollen.get(0).naam(),
                () -> "de rol met een legitiem 'van de' moet intact parseren");
        assertFalse(m.diagnostics.stream().anyMatch(d -> d.message().contains("lidwoord")),
                () -> "een 'van de'-rolnaam mag geen lidwoord-waarschuwing geven: " + m.diagnostics);
    }

    @Test void cardinaliteit_onregelmatig_meervoud_wordt_via_eliminatie_gemarkeerd() {
        // "vakken" is the doubling-plural of role "vak" — undeclared, so neither the best-effort nor
        // MeervoudMatcher synthesises it (§footnote 2). But a non-wederkerig feittype has exactly two
        // roles, so once "studenten" names the student role, the unmatched "vakken" segment is the
        // remaining (vak) role by ELIMINATION — no plural guessing. Both end up meervoudig.
        String src = """
            Objecttype de Student
                de naam Tekst;
            Objecttype de Vak
                de naam Tekst;
            Feittype inschrijving
                de student Student
                het vak Vak
            meerdere studenten volgen meerdere vakken
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        var ft = m.feittypen.get("inschrijving");
        assertTrue(ft.rollen.get(0).meervoudig(), "student is meervoudig (regulier meervoud)");
        assertTrue(ft.rollen.get(1).meervoudig(), "vak is meervoudig (onregelmatig 'vakken' via eliminatie)");
    }

    @Test void cardinaliteit_eliminatie_respecteert_de_andere_kardinaliteit() {
        // "pupillen" (doubling) is unmatched; "voogd" names the één-side role by name; so pupil is the
        // remaining role and takes ITS OWN quantifier (meerdere), not voogd's. Elimination assigns the
        // role, the segment's own telwoord assigns the cardinality.
        String src = """
            Objecttype de Persoon
                de naam Tekst;
            Feittype voogdij
                de voogd Persoon
                de pupil Persoon
            Eén voogd heeft meerdere pupillen
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        assertTrue(m.errors().isEmpty(), () -> "moet parseren: " + m.errors());
        var ft = m.feittypen.get("voogdij");
        assertFalse(ft.rollen.get(0).meervoudig(), "voogd is enkelvoudig (Eén voogd ...)");
        assertTrue(ft.rollen.get(1).meervoudig(), "pupil is meervoudig (... meerdere pupillen, via eliminatie)");
    }

    @Test void cardinaliteit_markeert_een_meerwoordige_rolnaam() {
        // The rolnaam is the LEADING phrase of a cardinaliteit segment, so a multi-word role
        // name binds whole — word-by-word matching missed it and left the role wrongly single.
        String src = """
            Objecttype de Contingent
            Objecttype de Natuurlijk persoon
            Feittype Contingent en passagiers
                de contingent Contingent
                de passagier met recht op treinmiles Natuurlijk persoon
            Eén contingent betreft meerdere passagiers met recht op treinmiles
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        var ft = m.feittypen.get("Contingent en passagiers");
        assertFalse(ft.rollen.get(0).meervoudig(), "contingent is enkelvoudig");
        assertTrue(ft.rollen.get(1).meervoudig(), "de meerwoordige rol is meervoudig");
    }

    @Test void cardinaliteit_markeert_geen_rol_die_alleen_in_de_relatiebeschrijving_staat() {
        // The relatiebeschrijving prose may name the OTHER role ("… boeken de reis voor …").
        // Only the segment HEAD takes the quantifier, so the reis role stays single (no
        // over-marking) — else a valid to-one navigation would wrongly become a marker.
        String src = """
            Objecttype de Reis
            Objecttype de Passagier
            Feittype Boeking
                de passagier Passagier
                de reis Reis
            meerdere passagiers boeken de reis voor Eén reis
            """;
        RegelSpraakModel m = Resolver.resolve(src);
        var ft = m.feittypen.get("Boeking");
        assertTrue(ft.rollen.get(0).meervoudig(), "passagier is meervoudig (meerdere passagiers ...)");
        assertFalse(ft.rollen.get(1).meervoudig(), "reis mag niet meervoudig zijn enkel omdat de relatiebeschrijving 'reis' noemt");
    }
}
