package io.github.yhavinga.regelspraak.resolver;

import io.github.yhavinga.regelspraak.model.RegelSpraakModel.Objecttype;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins the Dutch adjectival "-e" inflection that lets a subselectie's written kenmerk form bind the
 *  declared base — including the cases deliberately left unmatched (errs-safe markers). */
class KenmerkMatcherTest {

    private static Objecttype met(String... kenmerken) {
        Objecttype ot = new Objecttype("Passagier");
        for (String k : kenmerken) ot.kenmerken.add(k);
        return ot;
    }

    @Test void exacte_basisvorm_matcht() {
        assertEquals("minderjarig", KenmerkMatcher.vind(met("minderjarig"), "minderjarig"));
        assertEquals("minderjarig", KenmerkMatcher.vind(met("minderjarig"), "Minderjarig")); // hoofdletter-ongevoelig
    }

    @Test void verbogen_e_vorm_matcht_de_basis() {                  // de spec's §13.4.2 voorbeeld
        assertEquals("minderjarig", KenmerkMatcher.vind(met("minderjarig"), "minderjarige"));
        assertEquals("groen", KenmerkMatcher.vind(met("groen"), "groene"));
    }

    @Test void lange_klinker_verkort_bij_verbuiging() {             // groot->grote, duurzaam->duurzame
        assertEquals("groot", KenmerkMatcher.vind(met("groot"), "grote"));
        assertEquals("duurzaam", KenmerkMatcher.vind(met("duurzaam"), "duurzame"));
    }

    @Test void exact_gaat_voor_verbuiging() {
        // both "groot" and a hypothetical "grote" declared: the written "grote" binds the exact one.
        assertEquals("grote", KenmerkMatcher.vind(met("groot", "grote"), "grote"));
    }

    @Test void onbekende_vorm_matcht_niet() {
        assertNull(KenmerkMatcher.vind(met("minderjarig"), "volwassene"));
        assertNull(KenmerkMatcher.vind(met("minderjarig"), "meerderjarig"));
    }

    @Test void gedocumenteerde_grens_geen_medeklinkerverdubbeling_of_verscherping() {
        // wit->witte (stress-doubling) and vies->vieze (s->z) are NOT modelled — they would mis-handle
        // unstressed endings; such a written form stays unmatched (a marker), never a wrong bind.
        assertNull(KenmerkMatcher.vind(met("wit"), "witte"));
        assertNull(KenmerkMatcher.vind(met("vies"), "vieze"));
    }
}
