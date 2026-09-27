package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.toolchain.engine.Naamgeving;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The OpenAPI document per vocabulary ({@link OpenApiEmitter#emit(DienstDescriptor, RegelSpraakModel, String)}) over
 * uchar's golden {@code jutterswet-vocabulaires}: the canonical document and those of {@code handboek} and {@code cgm}
 * are byte-pinned against goldens under {@code src/test/resources/openapi/} and written to {@code target/openapi/},
 * each property, value and entry point lists its other spellings, and a model without vocabularies gets none of the
 * extension (the TOKA golden of {@link OpenApiEmitterTest} pins that byte for byte; here an own model without
 * vocabularies is searched for any trace of it).
 */
class OpenApiVocabulaireTest {

    private static final Path GOLDENS = Path.of("src/test/resources/openapi");
    private static DienstDescriptor DESCRIPTOR;
    private static RegelSpraakModel MODEL;

    @BeforeAll
    static void bouw() throws IOException {
        MODEL = VocabulaireBron.model();
        DESCRIPTOR = DescriptorLoader.MAPPER.readValue(VocabulaireBron.descriptorJson(), DienstDescriptor.class);
        for (String v : new String[]{null, "handboek", "cgm"})
            OpenApiEmitter.schrijf(DESCRIPTOR, MODEL, v, Path.of("target", "openapi", OpenApiEmitter.bestandsnaam(DESCRIPTOR, v)));
    }

    private static void byteGelijk(String vocabulaire) throws IOException {
        String naam = OpenApiEmitter.bestandsnaam(DESCRIPTOR, vocabulaire).replace(".json", ".golden.json");
        byte[] verwacht = Files.readAllBytes(GOLDENS.resolve(naam));
        byte[] werkelijk = OpenApiEmitter.naarBytes(OpenApiEmitter.emit(DESCRIPTOR, MODEL, vocabulaire));
        if (Arrays.equals(verwacht, werkelijk)) return;
        String v = new String(verwacht, StandardCharsets.UTF_8), w = new String(werkelijk, StandardCharsets.UTF_8);
        int i = 0;
        while (i < Math.min(v.length(), w.length()) && v.charAt(i) == w.charAt(i)) i++;
        fail(naam + " wijkt af op teken " + i + ": golden '" + v.substring(i, Math.min(v.length(), i + 120))
                + "', emitted '" + w.substring(i, Math.min(w.length(), i + 120)) + "'; is dit een bewuste wijziging,"
                + " schrijf de golden dan opnieuw uit target/openapi/");
    }

    @Test
    void het_canonieke_document_is_byte_gelijk_aan_de_golden() throws IOException { byteGelijk(null); }

    @Test
    void het_document_van_het_handboek_is_byte_gelijk_aan_de_golden() throws IOException { byteGelijk("handboek"); }

    @Test
    void het_document_van_cgm_is_byte_gelijk_aan_de_golden() throws IOException { byteGelijk("cgm"); }

    @Test
    void elke_eigenschap_waarde_en_entrypoint_noemt_zijn_andere_spellingen() {
        ObjectNode doc = OpenApiEmitter.emit(DESCRIPTOR, MODEL, "cgm");
        assertEquals("cgm", doc.at("/info/x-vocabulaire").asText());
        JsonNode vondst = doc.at("/components/schemas/Vondst");
        JsonNode soort = vondst.at("/properties/VONDST_SOORT");
        assertEquals("[\"WRAKHOUT\",\"BARNSTEEN\",\"FLESSENPOST\"]", soort.get("enum").toString());
        assertEquals("soort", soort.at("/x-spellingen/canoniek").asText());
        assertEquals("soort vondst", soort.at("/x-spellingen/handboek").asText());
        assertFalse(soort.get("x-spellingen").has("cgm"), "het eigen vocabulaire is geen andere spelling");
        assertEquals("amber", soort.at("/x-waardespellingen/BARNSTEEN/handboek").asText());
        assertEquals("barnsteen", soort.at("/x-waardespellingen/BARNSTEEN/canoniek").asText());
        assertEquals("De dienst neemt ook 'soort' (canoniek) en 'soort vondst' (handboek) aan.",
                soort.get("description").asText());
        assertEquals("aangespoeld voorwerp", vondst.at("/x-spellingen/handboek").asText());
        JsonNode post = doc.at("/paths/~1premie/post");
        assertEquals("[\"Jutter\",\"Vondst\"]", post.at("/x-spellingen/canoniek/invoer").toString());
        assertEquals("strandjutter:komt in aanmerking voor premie", post.at("/x-spellingen/handboek/uitvoer/5").asText());
        assertEquals("vocabulaire", post.at("/parameters/0/name").asText());
        assertEquals("[\"canoniek\",\"handboek\",\"cgm\"]", post.at("/parameters/1/schema/enum").toString());
        assertTrue(doc.at("/paths/~1premie-cgm/post/parameters/0/description").asText().contains("'cgm', de standaard"),
                doc.at("/paths/~1premie-cgm/post/parameters/0/description").asText());

        ObjectNode canoniek = OpenApiEmitter.emit(DESCRIPTOR, MODEL, "canoniek");
        assertEquals(OpenApiEmitter.emit(DESCRIPTOR, MODEL), canoniek);
        assertEquals("VONDST_SOORT", canoniek.at("/components/schemas/Vondst/properties/soort/x-spellingen/cgm").asText());
    }

    @Test
    void een_model_zonder_vocabulaires_krijgt_geen_extensie() throws IOException {
        DienstDescriptor d = DescriptorLoader.MAPPER.readValue(KetenBron.descriptorJson(), DienstDescriptor.class);
        String doc = new String(OpenApiEmitter.naarBytes(OpenApiEmitter.emit(d, KetenBron.model())), StandardCharsets.UTF_8);
        for (String spoor : new String[]{"x-spellingen", "x-waardespellingen", "x-vocabulaire", "invoervocabulaire"})
            assertFalse(doc.contains(spoor), "'" + spoor + "' in een document zonder vocabulaires");
        assertThrows(Naamgeving.Vocabulairefout.class, () -> OpenApiEmitter.emit(d, KetenBron.model(), "cgm"));
        assertThrows(Naamgeving.Vocabulairefout.class, () -> OpenApiEmitter.emit(DESCRIPTOR, MODEL, "wetboek"));
    }
}
