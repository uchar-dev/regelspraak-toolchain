package dev.uchar.regelspraak.toolchain.service;

import com.fasterxml.jackson.databind.JsonNode;

import dev.uchar.regelspraak.model.RegelSpraakModel;
import dev.uchar.regelspraak.resolver.Resolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Test fixtures for the vocabularies. {@link #GOLDEN}: uchar's golden {@code jutterswet-vocabulaires} (the
 * submodule), two vocabularies with a designation on every declaration, served with the descriptor under
 * {@code src/test/resources/vocabulaires/} (an entry point without and one with a default vocabulary).
 * {@link #AMBIGUOUS}: own material in which names read as two declarations — {@code bedrag} is the vondstbedrag in
 * {@code handboek} and the premiebedrag in {@code cgm}; {@code toeslag} is a canonical name and the {@code handboek}
 * designation of the basisbedrag; the value {@code hout} is canonical and the {@code handboek} designation of
 * {@code wrakhout} — and whose vocabularies are incomplete.
 */
final class VocabularySource {

    private VocabularySource() {}

    static final Path GOLDEN = TokaSource.REPO.resolve("uchar/corpus/golden/jutterswet-vocabulaires");

    static final String AMBIGUOUS = """
            Vocabulaire handboek (taal: nl)
            Vocabulaire cgm

            Domein Houtsoort is van het type Enumeratie
                'wrakhout' (handboek: hout) (cgm: WRAKHOUT)
                'hout' (handboek: timmerhout)

            Objecttype de Jutter (mv: Jutters) (bezield) (cgm: JUTTER)
                het vondstbedrag (handboek: bedrag) Numeriek (getal met 2 decimalen);
                het premiebedrag (cgm: bedrag) Numeriek (getal met 2 decimalen);
                het basisbedrag (handboek: toeslag) Numeriek (getal met 2 decimalen);
                de toeslag (handboek: bijslag) Numeriek (getal met 2 decimalen);
                de houtsoort Houtsoort;
                de uitkering (cgm: UITKERING) Numeriek (getal met 2 decimalen);
                is houtjutter (cgm: HOUTJUTTER) kenmerk (bijvoeglijk);

            Regel uitkering van een jutter (cgm: R_UITKERING)
                geldig altijd
                    De uitkering van een Jutter moet berekend worden als zijn vondstbedrag plus zijn premiebedrag plus zijn basisbedrag plus zijn toeslag.

            Regel houtjutter (cgm: R_HOUT)
                geldig altijd
                    Een Jutter is houtjutter indien zijn houtsoort gelijk is aan 'wrakhout'.

            Regel bedragen verschillen (cgm: C_BEDRAGEN)
                geldig altijd
                    Het vondstbedrag van een Jutter moet ongelijk zijn aan het premiebedrag van de Jutter.
            """;

    static final String AMBIGUOUS_DESCRIPTOR = """
            { "service": {"name":"dubbelzinnig","version":"1"},
              "entrypoints": [ { "name":"uitkering","path":"uitkering",
                "scope": {"ruleNames": ["uitkering van een jutter", "houtjutter", "bedragen verschillen"]},
                "input": ["Jutter"], "output": ["Jutter.uitkering", "Jutter.houtsoort", "Jutter:houtjutter"] } ],
              "parameters": {} }""";

    static String source() {
        try {
            return Files.readString(GOLDEN.resolve("source.rgs"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String descriptorJson() { return ChainSource.read("vocabularies/jutterswet-vocabulaires.service.json"); }

    static RegelSpraakModel model() { return Resolver.resolve(source()); }

    static DecisionCore core() {
        return DecisionCore.assemble(descriptorJson(), Map.of("bron.rgs", source()), CoreConfig.strict(),
                Thread.currentThread().getContextClassLoader());
    }

    static DecisionCore ambiguous() {
        return DecisionCore.assemble(AMBIGUOUS_DESCRIPTOR, Map.of("dubbelzinnig.rgs", AMBIGUOUS),
                CoreConfig.strict(), Thread.currentThread().getContextClassLoader());
    }

    static JsonNode json(String text) {
        try {
            return DescriptorLoader.MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The request of the golden's case1, in the canonical names. */
    static final String CASE1 = """
            { "parameters": { "premietarief": 3 },
              "instances": [
                { "type": "Jutter", "id": "J1", "attributes": { "naam": "Aukje" }, "roles": { "vondsten": ["V1", "V2"] } },
                { "type": "Jutter", "id": "J2", "attributes": { "naam": "Bram" }, "roles": { "vondsten": ["V3"] } },
                { "type": "Vondst", "id": "V1", "attributes": { "soort": "barnsteen", "gewicht": 3.00 } },
                { "type": "Vondst", "id": "V2", "attributes": { "soort": "wrakhout", "gewicht": 1.20 } },
                { "type": "Vondst", "id": "V3", "attributes": { "soort": "flessenpost", "gewicht": 0.40 } } ] }""";

    /** The same request with every name as the vocabulary 'handboek' writes it. */
    static final String CASE1_HANDBOEK = """
            { "parameters": { "premie per kilogram": 3 },
              "instances": [
                { "type": "strandjutter", "id": "J1", "attributes": { "naam": "Aukje" },
                  "roles": { "gemelde vondst": ["V1", "V2"] } },
                { "type": "strandjutter", "id": "J2", "attributes": { "naam": "Bram" },
                  "roles": { "gemelde vondst": ["V3"] } },
                { "type": "aangespoeld voorwerp", "id": "V1",
                  "attributes": { "soort vondst": "amber", "gewicht in kilogram": 3.00 } },
                { "type": "aangespoeld voorwerp", "id": "V2",
                  "attributes": { "soort vondst": "hout van een wrak", "gewicht in kilogram": 1.20 } },
                { "type": "aangespoeld voorwerp", "id": "V3",
                  "attributes": { "soort vondst": "bericht in een fles", "gewicht in kilogram": 0.40 } } ] }""";

    /** The same request with the names mixed: canonical, 'handboek' and 'cgm', a relation instead of a role. */
    static final String CASE1_MIXED = """
            { "parameters": { "PREMIE_TARIEF": 3 },
              "instances": [
                { "type": "JUTTER", "id": "J1", "attributes": { "NAAM": "Aukje" }, "roles": { "vondsten": ["V1"] } },
                { "type": "strandjutter", "id": "J2", "attributes": { "naam": "Bram" },
                  "roles": { "GEMELDE_VONDST": ["V3"] } },
                { "type": "Vondst", "id": "V1", "attributes": { "VONDST_SOORT": "amber", "gewicht": 3.00 } },
                { "type": "aangespoeld voorwerp", "id": "V2",
                  "attributes": { "soort": "WRAKHOUT", "GEWICHT": 1.20 } },
                { "type": "VONDST", "id": "V3",
                  "attributes": { "soort vondst": "flessenpost", "gewicht in kilogram": 0.40 } } ],
              "relations": [ { "factType": "aanmelding van een vondst",
                              "roles": { "AANMELDER": "J1", "gemelde vondst": "V2" } } ] }""";
}
