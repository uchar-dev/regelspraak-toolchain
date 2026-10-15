package dev.uchar.regelspraak.toolchain.engine;

import dev.uchar.regelspraak.engine.ScenarioResultaat.Status;
import dev.uchar.regelspraak.resolver.ScenarioResolver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The explanation writes a characteristic with the verb RegelSpraak gives its kind (§3.5, the negations of Tabel 16):
 * adjectival "is (niet) X", possessive "heeft de X" or "heeft geen X", nominal "is een X" or "is geen X". That holds
 * for the sentence about an attributed or withheld characteristic and for a condition on one, the failing conjunct
 * included. The article of a present possessive characteristic is the one the rule text writes before its name, none
 * where it writes none; the model does not carry the declaration's article. In a vocabulary the name is the
 * designation and the verb stays the one of the declared kind.
 */
class UitlegVolgtDeKenmerksoortTest {

    private static final String DOCUMENT = """
            Vocabulaire cgm (taal: nl)

            Objecttype het Recht op uitkering (mv: Rechten op uitkering)
                het verzoek kenmerk (bezittelijk);
                de lopende uitkering kenmerk;
                is geschorst kenmerk (bijvoeglijk);
                de uitkeringsduur verstreken (cgm: Indicatie uitkeringsduur verstreken) kenmerk (bezittelijk);
                het recht op grond-van-de-wia (cgm: WIA-recht) kenmerk;
                is herleefbaar (cgm: HERLEEFBAAR) kenmerk (bijvoeglijk);
                het recht op uitkering kenmerk (bezittelijk);

            Regel uitkeringsduur verstreken
                geldig altijd
                    Een Recht op uitkering heeft de uitkeringsduur verstreken
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij heeft het verzoek
                    - hij is een lopende uitkering
                    - hij is geschorst.

            Regel recht op grond van de WIA
                geldig altijd
                    Een Recht op uitkering is een recht op grond-van-de-wia
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij heeft geen verzoek
                    - hij is geen lopende uitkering
                    - hij is niet geschorst.

            Regel herleefbaar
                geldig altijd
                    Een Recht op uitkering is herleefbaar
                    indien hij aan alle volgende voorwaarden voldoet:
                    - hij is een lopende uitkering
                    - hij is niet geschorst.

            Regel recht op uitkering
                geldig altijd
                    Een Recht op uitkering heeft recht op uitkering indien hij geschorst is.

            Scenario drie soorten
            Gegeven is de volgende situatie:
            een Recht op uitkering R1
            - heeft het verzoek
            - is een lopende uitkering
            - is geschorst
            een Recht op uitkering R2
            een Recht op uitkering R3
            - is een lopende uitkering
            Met als verwacht resultaat:
            - R1 heeft de uitkeringsduur verstreken
            - R1 is geen recht op grond-van-de-wia
            - R1 is niet herleefbaar
            - R1 heeft recht op uitkering
            - R2 heeft geen uitkeringsduur verstreken
            - R2 is een recht op grond-van-de-wia
            - R2 is niet herleefbaar
            - R2 heeft geen recht op uitkering
            - R3 is herleefbaar
            """;

    /** A check on a characteristic of another object, reached over a role. */
    private static final String DOCUMENT_ROL = """
            Objecttype het Recht (mv: Rechten)
                is uitgesloten kenmerk (bijvoeglijk);

            Objecttype de Samenloop (mv: Samenlopen)
                het wia-recht kenmerk (bezittelijk);

            Feittype samenloop van een recht
                het recht Recht
                de samenloop Samenloop
            Eén recht heeft één samenloop

            Regel uitgesloten
                geldig altijd
                    Een Recht is uitgesloten indien de samenloop van het Recht een wia-recht heeft.

            Scenario samenloop
            Gegeven is de volgende situatie:
            een Recht R1
            - de samenloop is S1
            een Samenloop S1
            - heeft het wia-recht
            Met als verwacht resultaat:
            - R1 is uitgesloten
            """;

    private static List<String> uitleg(String document, String vocabulaire) {
        List<ScenarioRunner.Uitgelegd> u = ScenarioRunner.runMetUitleg(ScenarioResolver.resolve(document), vocabulaire);
        assertEquals(1, u.size());
        assertEquals(Status.PASS, u.get(0).resultaat().status(), () -> u.get(0).resultaat().diagnose());
        assertNull(u.get(0).uitlegFout(), u.get(0)::uitlegFout);
        return u.get(0).uitleg();
    }

    @Test
    void het_werkwoord_volgt_de_soort() {
        assertEquals(List.of(
                "R1 heeft de uitkeringsduur verstreken, omdat het onderwerp het verzoek heeft en het onderwerp een"
                        + " lopende uitkering is en het onderwerp geschorst is",
                "R1 is geen recht op grond-van-de-wia, want niet geldt: het onderwerp geen verzoek heeft",
                "R1 is niet herleefbaar, want niet geldt: het onderwerp niet geschorst is",
                "R1 heeft recht op uitkering, omdat het onderwerp geschorst is",
                "R2 heeft geen uitkeringsduur verstreken, want niet geldt: het onderwerp het verzoek heeft",
                "R2 is een recht op grond-van-de-wia, omdat het onderwerp geen verzoek heeft en het onderwerp geen"
                        + " lopende uitkering is en het onderwerp niet geschorst is",
                "R2 is niet herleefbaar, want niet geldt: het onderwerp een lopende uitkering is",
                "R2 heeft geen recht op uitkering, want niet geldt: het onderwerp geschorst is",
                "R3 heeft geen uitkeringsduur verstreken, want niet geldt: het onderwerp het verzoek heeft",
                "R3 is geen recht op grond-van-de-wia, want niet geldt: het onderwerp geen lopende uitkering is",
                "R3 is herleefbaar, omdat het onderwerp een lopende uitkering is en het onderwerp niet geschorst is",
                "R3 heeft geen recht op uitkering, want niet geldt: het onderwerp geschorst is"),
                uitleg(DOCUMENT, Naamgeving.CANONIEK));
    }

    @Test
    void in_een_vocabulaire_blijft_het_werkwoord_dat_van_de_soort() {
        assertEquals(List.of(
                "R1 heeft de Indicatie uitkeringsduur verstreken, omdat het onderwerp het verzoek heeft en het"
                        + " onderwerp een lopende uitkering is en het onderwerp geschorst is",
                "R1 is geen WIA-recht, want niet geldt: het onderwerp geen verzoek heeft",
                "R1 is niet HERLEEFBAAR, want niet geldt: het onderwerp niet geschorst is",
                "R1 heeft recht op uitkering, omdat het onderwerp geschorst is",
                "R2 heeft geen Indicatie uitkeringsduur verstreken, want niet geldt: het onderwerp het verzoek heeft",
                "R2 is een WIA-recht, omdat het onderwerp geen verzoek heeft en het onderwerp geen lopende uitkering"
                        + " is en het onderwerp niet geschorst is",
                "R2 is niet HERLEEFBAAR, want niet geldt: het onderwerp een lopende uitkering is",
                "R2 heeft geen recht op uitkering, want niet geldt: het onderwerp geschorst is",
                "R3 heeft geen Indicatie uitkeringsduur verstreken, want niet geldt: het onderwerp het verzoek heeft",
                "R3 is geen WIA-recht, want niet geldt: het onderwerp geen lopende uitkering is",
                "R3 is HERLEEFBAAR, omdat het onderwerp een lopende uitkering is en het onderwerp niet geschorst is",
                "R3 heeft geen recht op uitkering, want niet geldt: het onderwerp geschorst is"),
                uitleg(DOCUMENT, "cgm"));
    }

    /** The uitleg plays the IR document, whose check over a role carries its role binding beside the condition, not
     *  on it, so the characteristic is looked up on the subject. It is none of the subject's, its kind is unknown,
     *  and the condition stands as the rule writes it; before, the uitleg said "het onderwerp wia-recht is". */
    @Test
    void zonder_soort_staat_de_voorwaarde_zoals_de_regel_haar_schrijft() {
        assertEquals(List.of(
                "R1 is uitgesloten, omdat aan de voorwaarde \"de samenloop van het Recht een wia-recht heeft\""
                        + " is voldaan"),
                uitleg(DOCUMENT_ROL, Naamgeving.CANONIEK));
    }
}
